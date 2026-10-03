package dev.entitybridge.mission;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-side durable mission journal. Mission identity is stable across retries,
 * controller reconnects, and Paper restarts; sequence is monotonically allocated.
 */
public final class MissionRegistry {
    private static final int MAX_HISTORY = 200;
    private static final int JOURNAL_REPLACE_ATTEMPTS = 8;

    private final Path persistenceFile;
    private final Logger logger;
    private final Map<String, Mission> missions = new LinkedHashMap<>();
    private long nextSequence = 1L;
    private String currentMissionId;
    private String mode;

    public MissionRegistry(String defaultMode) {
        this(null, Logger.getLogger(MissionRegistry.class.getName()), defaultMode);
    }

    public MissionRegistry(Path persistenceFile, Logger logger, String defaultMode) {
        this.persistenceFile = persistenceFile;
        this.logger = logger;
        this.mode = normalizeMode(defaultMode);
        load();
    }

    public synchronized Mission create(String action, JsonObject args, String requestedBy) {
        return createWithSupersession(action, args, requestedBy).mission();
    }

    /** Allocates an exact proposed identity without making it replayable. Queue saves this first. */
    public synchronized Mission prepare(String action, JsonObject args, String requestedBy) {
        String commandId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        return new Mission(UUID.nameUUIDFromBytes(("entity2-mission:" + commandId)
                .getBytes(StandardCharsets.UTF_8)).toString(), commandId, nextSequence,
                action, args, requestedBy, now, now, MissionStatus.QUEUED, 1,
                "queued", "Waiting for Entity to accept the mission");
    }

    /** Registers a queue's already-durable identity; persistence failure cannot dispatch it. */
    public synchronized Creation registerPrepared(Mission prepared) throws IOException {
        Mission existing = missions.get(prepared.id());
        if (existing != null) {
            if (!existing.commandId().equals(prepared.commandId()) || existing.sequence() != prepared.sequence()) {
                throw new IOException("Prepared mission identity disagrees with journal");
            }
            return new Creation(existing, null, null);
        }
        if (prepared.sequence() != nextSequence) throw new IOException("Prepared mission sequence is stale");
        Map<String, Mission> before = new LinkedHashMap<>(missions);
        String beforeCurrent = currentMissionId;
        Mission prior = currentMission();
        Mission retired = prior == null || prior.terminal() ? null : prior.update(
                MissionStatus.CANCELLED, "superseded", "Superseded by ordered job", System.currentTimeMillis());
        if (retired != null) replace(retired);
        missions.put(prepared.id(), prepared);
        currentMissionId = prepared.id();
        nextSequence++;
        try {
            saveStrict();
        } catch (IOException failure) {
            missions.clear(); missions.putAll(before); currentMissionId = beforeCurrent; nextSequence--;
            throw failure;
        }
        trimHistory();
        return new Creation(prepared, retired == null ? null : prior, retired);
    }

    /**
     * Journals a new primary mission and durably retires the prior objective.
     * The bridge must deliver a matching cancel before the new submission so a
     * paused client mission cannot keep occupying the active execution slot.
     */
    public synchronized Creation createWithSupersession(
            String action,
            JsonObject args,
            String requestedBy
    ) {
        long now = System.currentTimeMillis();
        Mission previous = currentMission();
        String commandId = UUID.randomUUID().toString();
        String missionId = UUID.nameUUIDFromBytes(
                ("entity2-mission:" + commandId).getBytes(StandardCharsets.UTF_8)
        ).toString();
        long sequence = nextSequence++;
        Mission superseded = null;
        Mission prior = null;
        if (previous != null && !previous.terminal() && previous.status() != MissionStatus.PAUSED) {
            prior = previous;
            superseded = previous.update(
                    MissionStatus.CANCELLED,
                    "superseded",
                    "Superseded by mission #" + sequence + " [" + shortId(missionId)
                            + "]; retained in durable history",
                    now
            );
            replace(superseded);
        }

        Mission mission = new Mission(
                missionId,
                commandId,
                sequence,
                action,
                args,
                requestedBy,
                now,
                now,
                MissionStatus.QUEUED,
                1,
                "queued",
                "Waiting for Entity to accept the mission"
        );
        missions.put(mission.id(), mission);
        currentMissionId = mission.id();
        trimHistory();
        save();
        return new Creation(mission, prior, superseded);
    }

    /** Restores the prior current mission when no preemption frames were accepted. */
    public synchronized void rejectCreation(Creation creation, String reason) {
        Mission created = missions.get(creation.mission().id());
        if (created != null) {
            replace(created.update(
                    MissionStatus.BLOCKED,
                    "bridge queue",
                    reason,
                    System.currentTimeMillis()
            ));
        }
        if (creation.prior() != null) {
            replace(creation.prior());
            currentMissionId = creation.prior().id();
        } else if (created != null) {
            currentMissionId = created.id();
        }
        save();
    }

    public synchronized Optional<Mission> resolve(String reference) {
        if (reference == null || reference.isBlank() || reference.equalsIgnoreCase("current")) {
            return Optional.ofNullable(currentMission());
        }
        if (reference.matches("#?[0-9]+")) {
            try {
                long sequence = Long.parseLong(reference.startsWith("#") ? reference.substring(1) : reference);
                Optional<Mission> numbered = missions.values().stream().filter(mission -> mission.sequence() == sequence).findFirst();
                // A displayed UUID prefix can contain only digits. Keep existing
                // sequence precedence, but do not discard a valid short ID when
                // no such sequence exists. Explicit # still means sequence only.
                if (numbered.isPresent() || reference.startsWith("#")) return numbered;
            } catch (NumberFormatException invalidSequence) {
                return Optional.empty();
            }
        }
        Mission exact = missions.get(reference);
        if (exact != null) {
            return Optional.of(exact);
        }
        Mission match = null;
        for (Mission candidate : missions.values()) {
            if (candidate.id().startsWith(reference)) {
                if (match != null) {
                    return Optional.empty();
                }
                match = candidate;
            }
        }
        return Optional.ofNullable(match);
    }

    public synchronized Optional<Mission> update(
            String id,
            Long expectedSequence,
            MissionStatus status,
            String step,
            String reason
    ) {
        Mission mission = missions.get(id);
        if (mission == null || (expectedSequence != null && expectedSequence != mission.sequence())) {
            return Optional.empty();
        }
        // Client acknowledgements can arrive after an ordered supersession. A
        // late submit result must never resurrect a terminal, retired mission.
        if ((mission.status() == MissionStatus.CANCELLED || mission.status() == MissionStatus.SUCCEEDED)
                && status != null && status != mission.status()) {
            return Optional.of(mission);
        }
        Mission updated = mission.update(
                status == null ? mission.status() : status,
                step == null ? mission.step() : step,
                reason == null ? mission.reason() : reason,
                System.currentTimeMillis()
        );
        replace(updated);
        if (!updated.terminal() && updated.status() != MissionStatus.PAUSED) {
            currentMissionId = updated.id();
        } else if (updated.id().equals(currentMissionId) && updated.terminal()) {
            currentMissionId = newestNonTerminalId();
        }
        save();
        return Optional.of(updated);
    }

    public synchronized Optional<Mission> retry(String reference) {
        Optional<Mission> resolved = resolve(reference);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        if (resolved.get().status() == MissionStatus.CANCELLED
                || resolved.get().status() == MissionStatus.SUCCEEDED) {
            return Optional.empty();
        }
        Mission retried = resolved.get().retry(System.currentTimeMillis());
        replace(retried);
        currentMissionId = retried.id();
        save();
        return Optional.of(retried);
    }

    public synchronized void cancelStrict(String id, long sequence, String reason) throws IOException {
        Mission prior = missions.get(id);
        if (prior == null || prior.sequence() != sequence
                || prior.status() == MissionStatus.CANCELLED || prior.status() == MissionStatus.SUCCEEDED) return;
        String beforeCurrent = currentMissionId;
        replace(prior.update(MissionStatus.CANCELLED, "cancelled", reason, System.currentTimeMillis()));
        if (id.equals(currentMissionId)) currentMissionId = newestNonTerminalId();
        try { saveStrict(); }
        catch (IOException failure) { replace(prior); currentMissionId = beforeCurrent; throw failure; }
    }

    /** Queue retry preserves the objective and its client-side quantity/nonce progress. */
    public synchronized Mission retryStrict(String id, long sequence) throws IOException {
        Mission prior = missions.get(id);
        if (prior == null || prior.sequence() != sequence || prior.status() == MissionStatus.CANCELLED
                || prior.status() == MissionStatus.SUCCEEDED) throw new IOException("Original mission cannot be retried");
        String beforeCurrent = currentMissionId;
        Mission retried = prior.retry(System.currentTimeMillis());
        replace(retried); currentMissionId = retried.id();
        try { saveStrict(); }
        catch (IOException failure) { replace(prior); currentMissionId = beforeCurrent; throw failure; }
        return retried;
    }

    public synchronized void pauseStrict(String id, long sequence, String reason) throws IOException {
        Mission prior = missions.get(id);
        if (prior == null || prior.sequence() != sequence || prior.terminal()) return;
        replace(prior.update(MissionStatus.PAUSED, "queue_paused", reason, System.currentTimeMillis()));
        try { saveStrict(); }
        catch (IOException failure) { replace(prior); throw failure; }
    }

    public synchronized Mission current() {
        return currentMission();
    }

    public synchronized List<Mission> snapshot() {
        return List.copyOf(missions.values());
    }

    public synchronized String mode() {
        return mode;
    }

    public synchronized void setMode(String mode) {
        this.mode = normalizeMode(mode);
        save();
    }

    public synchronized JsonObject syncFrame() {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "mission_sync");
        frame.addProperty("protocol", 2);
        frame.addProperty("mode", mode);
        if (currentMissionId != null) {
            frame.addProperty("currentMissionId", currentMissionId);
        }
        JsonArray active = new JsonArray();
        for (Mission mission : missions.values()) {
            if (!mission.terminal()) {
                active.add(mission.toJson());
            }
        }
        frame.add("missions", active);
        frame.addProperty("timestamp", System.currentTimeMillis());
        return frame;
    }

    private Mission currentMission() {
        return currentMissionId == null ? null : missions.get(currentMissionId);
    }

    private void replace(Mission mission) {
        missions.put(mission.id(), mission);
    }

    private String newestNonTerminalId() {
        String result = null;
        for (Mission mission : missions.values()) {
            if (!mission.terminal() && mission.status() != MissionStatus.PAUSED) {
                result = mission.id();
            }
        }
        return result;
    }

    private void trimHistory() {
        if (missions.size() <= MAX_HISTORY) {
            return;
        }
        List<String> removable = new ArrayList<>();
        for (Mission mission : missions.values()) {
            if (mission.terminal() && !mission.id().equals(currentMissionId)) {
                removable.add(mission.id());
                if (missions.size() - removable.size() <= MAX_HISTORY) {
                    break;
                }
            }
        }
        removable.forEach(missions::remove);
    }

    private void load() {
        if (persistenceFile == null || !Files.isRegularFile(persistenceFile)) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(persistenceFile, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("mission journal root is not an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            mode = normalizeMode(text(root, "mode", mode));
            nextSequence = Math.max(1L, number(root, "nextSequence", 1L));
            currentMissionId = text(root, "currentMissionId", null);
            JsonElement values = root.get("missions");
            if (values != null && values.isJsonArray()) {
                for (JsonElement value : values.getAsJsonArray()) {
                    if (!value.isJsonObject()) {
                        continue;
                    }
                    try {
                        Mission mission = Mission.fromJson(value.getAsJsonObject());
                        missions.put(mission.id(), mission);
                        nextSequence = Math.max(nextSequence, mission.sequence() + 1L);
                    } catch (RuntimeException exception) {
                        logger.warning("Skipped malformed mission journal entry: " + exception.getMessage());
                    }
                }
            }
            if (!missions.containsKey(currentMissionId)) {
                currentMissionId = newestNonTerminalId();
            }
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Could not load durable mission journal", exception);
        }
    }

    private void save() {
        try {
            saveStrict();
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not persist durable mission journal", exception);
        }
    }

    private void saveStrict() throws IOException {
        if (persistenceFile == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("mode", mode);
        root.addProperty("nextSequence", nextSequence);
        if (currentMissionId != null) {
            root.addProperty("currentMissionId", currentMissionId);
        }
        JsonArray values = new JsonArray();
        missions.values().forEach(mission -> values.add(mission.toJson()));
        root.add("missions", values);

        persistJournal(
                    persistenceFile,
                    root.toString(),
                    MissionRegistry::moveReplacing,
                    Thread::sleep,
                    JOURNAL_REPLACE_ATTEMPTS
            );
    }

    /**
     * Writes a complete journal beside the destination before attempting any replacement.
     * A unique temporary name prevents a failed/slow save from colliding with stale files
     * left by an older process. The destination is never deleted, so an exhausted retry
     * leaves the last durable journal intact.
     */
    static void persistJournal(
            Path destination,
            String contents,
            MoveOperation moveOperation,
            RetryDelay retryDelay,
            int maxAttempts
    ) throws IOException {
        Path absoluteDestination = destination.toAbsolutePath().normalize();
        Path parent = absoluteDestination.getParent();
        if (parent == null) {
            throw new IOException("mission journal has no parent directory: " + destination);
        }
        Files.createDirectories(parent);

        String prefix = absoluteDestination.getFileName() + ".";
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        IOException failure = null;
        try {
            writeAndForce(temporary, contents);
            replaceWithRetry(
                    temporary,
                    absoluteDestination,
                    moveOperation,
                    retryDelay,
                    maxAttempts
            );
        } catch (IOException exception) {
            failure = exception;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    static void replaceWithRetry(
            Path temporary,
            Path destination,
            MoveOperation moveOperation,
            RetryDelay retryDelay,
            int maxAttempts
    ) throws IOException {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least one");
        }
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                moveOperation.move(temporary, destination);
                return;
            } catch (FileSystemException exception) {
                lastFailure = exception;
                if (attempt == maxAttempts) {
                    break;
                }
                try {
                    retryDelay.pause(replaceBackoffMillis(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException interruptedSave = new IOException(
                            "interrupted while retrying mission journal replacement",
                            interrupted
                    );
                    interruptedSave.addSuppressed(exception);
                    throw interruptedSave;
                }
            }
        }
        throw lastFailure;
    }

    private static void writeAndForce(Path temporary, String contents) throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(contents);
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }

    static void moveReplacing(Path temporary, Path destination) throws IOException {
        try {
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long replaceBackoffMillis(int failedAttempt) {
        return Math.min(200L, 5L << Math.min(6, Math.max(0, failedAttempt - 1)));
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path temporary, Path destination) throws IOException;
    }

    @FunctionalInterface
    interface RetryDelay {
        void pause(long millis) throws InterruptedException;
    }

    private static String normalizeMode(String value) {
        return "oracle".equals(value == null ? null : value.toLowerCase(Locale.ROOT)) ? "oracle" : "player";
    }

    private static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long number(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public record Creation(Mission mission, Mission prior, Mission superseded) {
    }
}
