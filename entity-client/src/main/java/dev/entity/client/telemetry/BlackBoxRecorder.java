package dev.entity.client.telemetry;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A private, bounded flight recorder for Entity's own diagnostic state.
 *
 * <p>The game thread only creates a compact JSON line and offers it to a bounded queue. A daemon
 * writer owns all filesystem work. Every public method is a no-throw boundary: losing diagnostics
 * must never cost Entity control of its body or mission.</p>
 */
public final class BlackBoxRecorder implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final String CURRENT_FILE = "blackbox.ndjson";
    private static final String INCIDENT_FILE = "incidents.ndjson";
    private static final Pattern ROTATED_FILE = Pattern.compile("blackbox\\.(\\d+)\\.ndjson");
    private static final Pattern ROTATED_INCIDENT_FILE =
            Pattern.compile("incidents\\.(\\d+)\\.ndjson");
    private static final long MAXIMUM_INCIDENT_FILE_BYTES = 2_097_152L;
    private static final int MAXIMUM_INCIDENT_FILES = 4;
    private static final String SECRET_KEY =
            "(?:token|secret|password|passwd|api[_-]?key|authorization|cookie|entity_bridge_token)";
    private static final Pattern DOUBLE_QUOTED_SECRET = Pattern.compile(
            "(?i)((?<![a-z0-9_])[\\\"']?" + SECRET_KEY
                    + "[\\\"']?\\s*[:=]\\s*\\\")((?:\\\\.|[^\\\"\\\\])*)(\\\")");
    private static final Pattern SINGLE_QUOTED_SECRET = Pattern.compile(
            "(?i)((?<![a-z0-9_])[\\\"']?" + SECRET_KEY
                    + "[\\\"']?\\s*[:=]\\s*')((?:\\\\.|[^'\\\\])*)(')");
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)((?<![a-z0-9_])[\\\"']?" + SECRET_KEY
                    + "[\\\"']?\\s*[:=]\\s*)(?![\\\"'])([^\\s,;}]+)");
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)(\\bbearer\\s+)[A-Za-z0-9._~+\\-/=]{6,}");
    private static final int MAX_DEPTH = 8;
    private static final int MAX_OBJECT_FIELDS = 96;
    private static final int MAX_ARRAY_ELEMENTS = 64;
    private static final int MAX_STACK_FRAMES = 16;
    private static final long WARNING_INTERVAL_MILLIS = 60_000L;
    private static final long WRITE_RETRY_MILLIS = 100L;

    private final Path directory;
    private final Settings settings;
    private final Consumer<String> warningSink;
    private final ArrayBlockingQueue<PendingRecord> pending;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private final String sessionId = UUID.randomUUID().toString();
    private final Thread writer;

    private long nextSampleAt;

    public BlackBoxRecorder(Path directory, Consumer<String> warningSink) {
        this(directory, Settings.defaults(), warningSink, true);
    }

    public BlackBoxRecorder(Path directory, Settings settings, Consumer<String> warningSink) {
        this(directory, settings, warningSink, true);
    }

    /** Package-private writer switch keeps queue-overflow tests deterministic. */
    BlackBoxRecorder(
            Path directory,
            Settings settings,
            Consumer<String> warningSink,
            boolean startWriter) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        this.warningSink = warningSink == null ? ignored -> { } : warningSink;
        this.pending = new ArrayBlockingQueue<>(this.settings.queueCapacity());

        Thread candidate = null;
        if (startWriter) {
            try {
                candidate = new Thread(this::writerLoop, "Entity2-black-box");
                candidate.setDaemon(true);
                candidate.setUncaughtExceptionHandler((thread, error) ->
                        warn("diagnostic writer stopped unexpectedly: " + safeErrorName(error)));
                candidate.start();
            } catch (Throwable error) {
                warn("diagnostic writer could not start: " + safeErrorName(error));
                candidate = null;
            }
        }
        this.writer = candidate;
    }

    /** Samples at a fixed maximum rate. The supplier is not evaluated for skipped samples. */
    public boolean sample(long nowMillis, Supplier<JsonObject> snapshotSupplier) {
        try {
            if (closed.get() || writer == null || !sampleDue(nowMillis)) return false;
            JsonObject snapshot = snapshotSupplier == null ? new JsonObject() : snapshotSupplier.get();
            return enqueue("sample", nowMillis, snapshot, null);
        } catch (Throwable error) {
            warn("sample capture failed: " + safeErrorName(error));
            return false;
        }
    }

    /** Records a significant failure immediately, independently of the ordinary sample cadence. */
    public boolean incident(
            long nowMillis,
            String category,
            String reason,
            Throwable error,
            Supplier<JsonObject> contextSupplier) {
        try {
            if (closed.get() || writer == null) return false;
            JsonObject context = new JsonObject();
            if (contextSupplier != null) {
                try {
                    JsonObject supplied = contextSupplier.get();
                    if (supplied != null) context = supplied;
                } catch (Throwable contextError) {
                    context.addProperty("contextCaptureFailure", safeErrorName(contextError));
                }
            }
            JsonObject incident = new JsonObject();
            incident.addProperty("category", safeText(category));
            incident.addProperty("reason", safeText(reason));
            if (error != null) incident.add("exception", exception(error));
            return enqueue("incident", nowMillis, context, incident);
        } catch (Throwable recorderError) {
            warn("incident capture failed: " + safeErrorName(recorderError));
            return false;
        }
    }

    public void lifecycle(long nowMillis, String phase, String reason) {
        try {
            if (closed.get() || writer == null) return;
            JsonObject detail = new JsonObject();
            detail.addProperty("phase", safeText(phase));
            detail.addProperty("reason", safeText(reason));
            enqueue("lifecycle", nowMillis, new JsonObject(), detail);
        } catch (Throwable error) {
            warn("lifecycle capture failed: " + safeErrorName(error));
        }
    }

    /**
     * Records one compact typed fact independently of the sampled state stream.
     * Monotonic domain events belong here instead of in every later snapshot;
     * readers can join them by sessionId, sequence and their domain timestamp.
     */
    public boolean event(long nowMillis, String eventType, Supplier<JsonObject> detailSupplier) {
        try {
            if (closed.get() || writer == null) return false;
            JsonObject detail = detailSupplier == null ? new JsonObject() : detailSupplier.get();
            return enqueue(normalizeEventType(eventType), nowMillis,
                    detail == null ? new JsonObject() : detail, null);
        } catch (Throwable error) {
            warn("event capture failed: " + safeErrorName(error));
            return false;
        }
    }

    private synchronized boolean sampleDue(long nowMillis) {
        if (nowMillis < nextSampleAt && nowMillis + settings.sampleIntervalMillis() >= nextSampleAt) {
            return false;
        }
        // Also recovers immediately if the wall clock jumps backwards by more than one interval.
        nextSampleAt = nowMillis + settings.sampleIntervalMillis();
        return true;
    }

    private synchronized boolean enqueue(
            String eventType,
            long nowMillis,
            JsonObject body,
            JsonObject detail) {
        // close() takes this same monitor before publishing the closed state.  A
        // supplier may have passed its cheap public pre-check and then blocked;
        // this in-lock check prevents it from admitting a record after the
        // writer has observed an empty, closed queue and exited.
        if (closed.get()) return false;
        JsonElement sanitized = sanitize(body == null ? new JsonObject() : body, "", 0);
        JsonObject event = sanitized.isJsonObject() ? sanitized.getAsJsonObject() : new JsonObject();
        event.addProperty("blackBoxSchema", 1);
        event.addProperty("eventType", eventType);
        event.addProperty("recordedAt", nowMillis);
        event.addProperty("sessionId", sessionId);
        event.addProperty("sequence", sequence.incrementAndGet());
        if (detail != null) event.add("event", sanitize(detail, "event", 0));
        long missed = dropped.get();
        boolean ordinarySample = eventType.equals("sample");
        boolean pinnedIncident = eventType.equals("incident");
        PendingRecord record = pendingRecord(event, missed, ordinarySample, pinnedIncident);
        if (pending.offer(record)) {
            dropped.set(0L);
            return true;
        }

        // A one-shot typed fact is more valuable than another periodic state
        // sample. Evict at most one queued sample; never displace an incident,
        // lifecycle, or another typed event. Total memory/disk bounds remain
        // unchanged because queue capacity and file rotation are unchanged.
        if (!ordinarySample) {
            PendingRecord displaced = null;
            for (PendingRecord candidate : pending) {
                if (candidate.ordinarySample() && pending.remove(candidate)) {
                    displaced = candidate;
                    break;
                }
            }
            if (displaced != null) {
                long missedWithDisplaced = saturatingIncrement(missed);
                record = pendingRecord(event, missedWithDisplaced, false, pinnedIncident);
                if (pending.offer(record)) {
                    dropped.set(0L);
                    return true;
                }
                missed = missedWithDisplaced;
            }
        }
        dropped.set(saturatingIncrement(missed));
        return false;
    }

    private PendingRecord pendingRecord(
            JsonObject event,
            long missed,
            boolean ordinarySample,
            boolean pinnedIncident) {
        if (missed > 0L) event.addProperty("droppedBefore", missed);
        else event.remove("droppedBefore");
        return new PendingRecord(GSON.toJson(event), ordinarySample, pinnedIncident);
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static String normalizeEventType(String value) {
        String normalized = Objects.requireNonNullElse(value, "event")
                .trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        return normalized.isBlank() ? "event" : normalized;
    }

    /** Test seam: bypasses the disabled writer while exercising the real enqueue path. */
    boolean enqueueForTest(long nowMillis, JsonObject body) {
        return enqueue("sample", nowMillis, body, null);
    }

    boolean eventForTest(long nowMillis, String eventType, JsonObject body) {
        return enqueue(normalizeEventType(eventType), nowMillis, body, null);
    }

    boolean incidentForTest(long nowMillis, JsonObject body) {
        return enqueue("incident", nowMillis, body, null);
    }

    String pollPendingForTest() {
        PendingRecord record = pending.poll();
        return record == null ? null : record.line();
    }

    long droppedForTest() {
        return dropped.get();
    }

    private JsonObject exception(Throwable error) {
        JsonObject detail = new JsonObject();
        detail.addProperty("type", error.getClass().getName());
        detail.addProperty("message", safeText(error.getMessage()));
        JsonArray stack = new JsonArray();
        StackTraceElement[] frames = error.getStackTrace();
        for (int index = 0; index < Math.min(frames.length, MAX_STACK_FRAMES); index++) {
            StackTraceElement frame = frames[index];
            JsonObject encoded = new JsonObject();
            encoded.addProperty("class", safeText(frame.getClassName()));
            encoded.addProperty("method", safeText(frame.getMethodName()));
            if (frame.getFileName() != null) encoded.addProperty("file", safeText(frame.getFileName()));
            encoded.addProperty("line", frame.getLineNumber());
            stack.add(encoded);
        }
        detail.add("stack", stack);
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            JsonObject encodedCause = new JsonObject();
            encodedCause.addProperty("type", cause.getClass().getName());
            encodedCause.addProperty("message", safeText(cause.getMessage()));
            detail.add("cause", encodedCause);
        }
        return detail;
    }

    private JsonElement sanitize(JsonElement value, String key, int depth) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (sensitiveKey(key)) return JsonNull.INSTANCE;
        if (depth >= MAX_DEPTH) return new JsonPrimitive("[depth-limit]");
        if (value.isJsonObject()) {
            JsonObject clean = new JsonObject();
            int retained = 0;
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (sensitiveKey(entry.getKey())) continue;
                if (retained++ >= MAX_OBJECT_FIELDS) {
                    clean.addProperty("truncatedFields", true);
                    break;
                }
                clean.add(entry.getKey(), sanitize(entry.getValue(), entry.getKey(), depth + 1));
            }
            return clean;
        }
        if (value.isJsonArray()) {
            JsonArray clean = new JsonArray();
            int retained = 0;
            for (JsonElement element : value.getAsJsonArray()) {
                if (retained++ >= MAX_ARRAY_ELEMENTS) {
                    clean.add("[array-limit]");
                    break;
                }
                clean.add(sanitize(element, key, depth + 1));
            }
            return clean;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(safeText(value.getAsString()));
        }
        return value.deepCopy();
    }

    private boolean sensitiveKey(String key) {
        if (key == null || key.isBlank()) return false;
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("privateconfig")
                || normalized.endsWith("config")
                || normalized.contains("chat")
                || normalized.contains("rawcommand");
    }

    private String safeText(String raw) {
        if (raw == null) return "";
        String clean = redactQuoted(DOUBLE_QUOTED_SECRET, raw);
        clean = redactQuoted(SINGLE_QUOTED_SECRET, clean);
        clean = redact(BEARER_SECRET, clean, 1);
        clean = redact(INLINE_SECRET, clean, 1);
        if (clean.length() <= settings.maximumStringCharacters()) return clean;
        return clean.substring(0, settings.maximumStringCharacters()) + "…[truncated]";
    }

    private static String redact(Pattern pattern, String value, int prefixGroup) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    matcher.group(prefixGroup) + "[redacted]"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String redactQuoted(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    matcher.group(1) + "[redacted]" + matcher.group(3)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void writerLoop() {
        long currentBytes = prepareDirectory();
        long incidentBytes = prepareIncidentJournal();
        PendingRecord owned = null;
        byte[] encoded = null;
        boolean mainWritten = false;
        boolean incidentWritten = false;
        while (!closed.get() || owned != null || !pending.isEmpty()) {
            if (owned == null) {
                try {
                    owned = pending.poll(100L, TimeUnit.MILLISECONDS);
                    if (owned != null) {
                        encoded = fit(owned.line());
                        mainWritten = false;
                        incidentWritten = !owned.pinnedIncident();
                    }
                } catch (InterruptedException ignored) {
                    continue;
                } catch (Throwable error) {
                    warn("diagnostic queue failed: " + safeErrorName(error));
                    continue;
                }
            }
            if (owned == null) continue;
            try {
                if (!mainWritten) {
                    if (currentBytes > 0L
                            && currentBytes + encoded.length > settings.maximumFileBytes()) {
                        rotate();
                        currentBytes = 0L;
                    }
                    Files.write(
                            directory.resolve(CURRENT_FILE),
                            encoded,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND,
                            StandardOpenOption.WRITE);
                    currentBytes += encoded.length;
                    mainWritten = true;
                }
                if (!incidentWritten) {
                    if (incidentBytes > 0L
                            && incidentBytes + encoded.length > MAXIMUM_INCIDENT_FILE_BYTES) {
                        rotateIncidentJournal();
                        incidentBytes = 0L;
                    }
                    Files.write(
                            directory.resolve(INCIDENT_FILE),
                            encoded,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND,
                            StandardOpenOption.WRITE);
                    incidentBytes += encoded.length;
                    incidentWritten = true;
                }
                // Queue admission transfers ownership to the writer.  Release
                // that ownership only after the append returns successfully.
                // An ambiguous partial append may therefore be retried; readers
                // can deduplicate by the stable sessionId/sequence pair.
                owned = null;
            } catch (Throwable error) {
                warn("diagnostic write failed: " + safeErrorName(error));
                // Retain the exact record (and its sequence) across transient
                // filesystem/rotation failures.  Once shutdown begins, retain
                // the historical bounded-close behavior instead of leaking a
                // daemon forever against a permanently unwritable directory.
                if (closed.get()) return;
                try {
                    Thread.sleep(WRITE_RETRY_MILLIS);
                } catch (InterruptedException ignored) {
                    if (closed.get()) return;
                }
            }
        }
    }

    private long prepareIncidentJournal() {
        try {
            Files.createDirectories(directory);
            try (var entries = Files.newDirectoryStream(directory, "incidents.*.ndjson")) {
                for (Path entry : entries) {
                    Matcher match = ROTATED_INCIDENT_FILE.matcher(
                            entry.getFileName().toString());
                    if (match.matches()
                            && Integer.parseInt(match.group(1)) >= MAXIMUM_INCIDENT_FILES) {
                        Files.deleteIfExists(entry);
                    }
                }
            }
            Path current = directory.resolve(INCIDENT_FILE);
            if (!Files.exists(current)) return 0L;
            long size = Files.size(current);
            if (size <= MAXIMUM_INCIDENT_FILE_BYTES) return size;
            rotateIncidentJournal();
            return 0L;
        } catch (Throwable error) {
            warn("incident journal preparation failed: " + safeErrorName(error));
            return 0L;
        }
    }

    private long prepareDirectory() {
        try {
            Files.createDirectories(directory);
            try (var entries = Files.newDirectoryStream(directory, "blackbox.*.ndjson")) {
                for (Path entry : entries) {
                    Matcher match = ROTATED_FILE.matcher(entry.getFileName().toString());
                    if (match.matches() && Integer.parseInt(match.group(1)) >= settings.maximumFiles()) {
                        Files.deleteIfExists(entry);
                    }
                }
            }
            Path current = directory.resolve(CURRENT_FILE);
            if (!Files.exists(current)) return 0L;
            long size = Files.size(current);
            if (size <= settings.maximumFileBytes()) return size;
            rotate();
            return 0L;
        } catch (Throwable error) {
            warn("diagnostic directory preparation failed: " + safeErrorName(error));
            return 0L;
        }
    }

    private byte[] fit(String line) {
        byte[] encoded = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= settings.maximumFileBytes()) return encoded;
        JsonObject replacement = new JsonObject();
        replacement.addProperty("blackBoxSchema", 1);
        replacement.addProperty("eventType", "oversize_event_dropped");
        replacement.addProperty("recordedAt", System.currentTimeMillis());
        replacement.addProperty("sessionId", sessionId);
        replacement.addProperty("sequence", sequence.incrementAndGet());
        replacement.addProperty("originalBytes", encoded.length);
        return (GSON.toJson(replacement) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private void rotate() throws IOException {
        Files.createDirectories(directory);
        int oldest = settings.maximumFiles() - 1;
        if (oldest <= 0) {
            Files.deleteIfExists(directory.resolve(CURRENT_FILE));
            return;
        }
        Files.deleteIfExists(rotated(oldest));
        for (int index = oldest - 1; index >= 1; index--) {
            Path source = rotated(index);
            if (Files.exists(source)) {
                Files.move(source, rotated(index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path current = directory.resolve(CURRENT_FILE);
        if (Files.exists(current)) {
            Files.move(current, rotated(1), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rotateIncidentJournal() throws IOException {
        Files.createDirectories(directory);
        int oldest = MAXIMUM_INCIDENT_FILES - 1;
        Files.deleteIfExists(rotatedIncident(oldest));
        for (int index = oldest - 1; index >= 1; index--) {
            Path source = rotatedIncident(index);
            if (Files.exists(source)) {
                Files.move(source, rotatedIncident(index + 1),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path current = directory.resolve(INCIDENT_FILE);
        if (Files.exists(current)) {
            Files.move(current, rotatedIncident(1), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path rotated(int index) {
        return directory.resolve("blackbox." + index + ".ndjson");
    }

    private Path rotatedIncident(int index) {
        return directory.resolve("incidents." + index + ".ndjson");
    }

    private void warn(String message) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now < due || !nextWarningAt.compareAndSet(due, now + WARNING_INTERVAL_MILLIS)) return;
        try {
            warningSink.accept(message);
        } catch (Throwable ignored) {
            // A diagnostic warning callback is deliberately not allowed to escape either.
        }
    }

    private static String safeErrorName(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @Override
    public void close() {
        // Serialize the close boundary with enqueue().  If an enqueue already
        // owns the monitor, its record is visible before closed becomes true;
        // if close wins, the in-lock closed check rejects the late admission.
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
        }
        if (writer == null) return;
        writer.interrupt();
        try {
            writer.join(1_500L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // Shutdown diagnostics must never interfere with Minecraft shutdown.
        }
    }

    public record Settings(
            long sampleIntervalMillis,
            long maximumFileBytes,
            int maximumFiles,
            int queueCapacity,
            int maximumStringCharacters) {

        public Settings validated() {
            return new Settings(
                    Math.max(0L, sampleIntervalMillis),
                    Math.max(512L, maximumFileBytes),
                    Math.max(1, Math.min(16, maximumFiles)),
                    Math.max(8, Math.min(4_096, queueCapacity)),
                    Math.max(64, Math.min(4_096, maximumStringCharacters)));
        }

        public static Settings defaults() {
            // Eight four-MiB segments retain roughly 30-40 minutes at the observed
            // production sample rate while preserving a strict 32-MiB storage cap.
            return new Settings(250L, 4_194_304L, 8, 512, 768);
        }
    }

    private record PendingRecord(String line, boolean ordinarySample, boolean pinnedIncident) {
        private PendingRecord {
            line = Objects.requireNonNull(line, "line");
        }
    }
}
