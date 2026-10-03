package dev.entity.client.runtime;

import dev.entity.core.persistence.AtomicStoreRecovery;
import dev.entity.core.persistence.SimpleJson;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Receipts for finite Stock commands; observes the existing idle executor, never owns a body. */
public final class FiniteStockCommands {
    private static final int LIMIT = 64;
    private final Path path;
    private final String world;
    private Map<String, Entry> entries = new LinkedHashMap<>();
    private long revision;
    private String problem = "";

    public record QueueTag(String id, long generation, int step, int attempt) {
        public QueueTag {
            id = Objects.requireNonNullElse(id, "");
            if (id.length() > 128 || (!id.isBlank() && (generation < 1 || step < -1 || attempt < 1)))
                throw new IllegalArgumentException("Invalid queue correlation");
        }
        public static QueueTag none() { return new QueueTag("", 0, 0, 0); }
    }
    public enum State { PREPARED, RUNNING, SUCCEEDED, BLOCKED, CANCELLED;
        public boolean terminal() { return this != PREPARED && this != RUNNING; }
    }
    public record Entry(String id, String requester, QueueTag queue, long homeGeneration,
                        long baselineCycle, long startedAt, State state, String detail, boolean reported) {
        public Entry {
            if (id == null || id.isBlank() || id.length() > 128 || requester == null || requester.length() > 64
                    || homeGeneration < 0 || baselineCycle < 0 || startedAt < 0)
                throw new IllegalArgumentException("Invalid finite Stock command");
            Objects.requireNonNull(queue); Objects.requireNonNull(state);
            detail = Objects.requireNonNullElse(detail, "");
            if (detail.length() > 2048) detail = detail.substring(0, 2048);
        }
        Entry with(State next, String message, boolean sent) {
            return new Entry(id, requester, queue, homeGeneration, baselineCycle, startedAt, next, message, sent);
        }
    }
    public record Observation(long generation, long completedCycle, boolean requested,
                              boolean settled, long blockedAt, String detail) {}

    public FiniteStockCommands(Path directory, String world, long now) {
        this.world = Objects.requireNonNullElse(world, "");
        this.path = this.world.isBlank() ? null : directory.resolve("stock-commands.json");
        if (path == null) { problem = "Stock is waiting for an authenticated world binding"; return; }
        try {
            Snapshot loaded = AtomicStoreRecovery.load(path, temporary(), "finite Stock commands",
                    () -> new Snapshot(0, new LinkedHashMap<>()), this::decode);
            entries = loaded.entries; revision = loaded.revision;
            // Home's explicit-run flag is not persisted. Reissuing an uncertain
            // command after process restart must not restart work under the old ID.
            for (Entry entry : List.copyOf(entries.values())) if (!entry.state.terminal())
                replace(entry.with(State.BLOCKED,
                        "Stock was interrupted by client restart; use /e queue retry or /e stock run", false));
        } catch (IOException | RuntimeException error) {
            problem = "Cannot trust finite Stock receipts: " + error.getMessage();
        }
    }

    public Entry find(String id) { return entries.get(id); }
    public Entry active() { return entries.values().stream().filter(e -> !e.state.terminal()).findFirst().orElse(null); }
    public List<Entry> pendingReplies() { return problem.isBlank()
            ? entries.values().stream().filter(e -> !e.reported).toList() : List.of(); }

    /** Must commit before invoking requestStockRun. Duplicate IDs are observation-only. */
    public Entry prepare(String id, String requester, QueueTag queue, Observation before, long now) throws IOException {
        requireTrusted();
        Entry old = entries.get(id);
        if (old != null) {
            if (!old.queue.equals(queue) || !old.requester.equals(requester))
                throw new IllegalArgumentException("Stock command ID was reused with different authority");
            replace(old.with(old.state, old.detail, false));
            return old;
        }
        if (active() != null) throw new IllegalArgumentException("An earlier finite Stock command still owns its receipt");
        Entry entry = new Entry(id, requester, queue, before.generation, before.completedCycle, now,
                State.PREPARED, "Stock command persisted; awaiting existing Home executor", false);
        replace(entry);
        return entry;
    }

    public void started(String id, String detail) throws IOException { transition(id, State.RUNNING, detail); }
    public void rejected(String id, String detail) throws IOException { transition(id, State.BLOCKED, detail); }
    public void cancelActive(String detail) throws IOException {
        Entry active = active(); if (active != null) transition(active.id, State.CANCELLED, detail);
    }
    private void transition(String id, State state, String detail) throws IOException {
        Entry old = entries.get(id);
        if (old == null || old.state.terminal()) return;
        replace(old.with(state, detail, false));
    }
    public void observe(Observation observation) throws IOException {
        if (!problem.isBlank()) return;
        Entry active = active();
        if (active == null || active.state != State.RUNNING) return;
        if (observation.generation != active.homeGeneration) {
            transition(active.id, State.CANCELLED, "Home changed during the Stock command");
        } else if (observation.blockedAt >= active.startedAt && observation.blockedAt > 0) {
            transition(active.id, State.BLOCKED, observation.detail);
        } else if (observation.completedCycle > active.baselineCycle && !observation.requested && observation.settled) {
            transition(active.id, State.SUCCEEDED, "Stock cycle completed with settled inventory; " + observation.detail);
        } else if (!observation.requested && observation.completedCycle <= active.baselineCycle) {
            transition(active.id, State.CANCELLED, "Stock was interrupted before its cycle completed");
        }
    }
    public void reported(Entry sent) throws IOException {
        Entry current = entries.get(sent.id);
        if (sent.equals(current)) replace(current.with(current.state, current.detail, true));
    }
    public void reconnect() throws IOException {
        if (!problem.isBlank()) return;
        Entry latest = entries.values().stream().reduce((a, b) -> b).orElse(null);
        if (latest != null) replace(latest.with(latest.state, latest.detail, false));
    }
    private void requireTrusted() {
        if (!problem.isBlank()) throw new IllegalArgumentException(problem);
    }
    private void replace(Entry entry) throws IOException {
        requireTrusted();
        Map<String, Entry> next = new LinkedHashMap<>(entries);
        next.put(entry.id, entry);
        while (next.size() > LIMIT) {
            String victim = next.values().stream().filter(e -> e.state.terminal() && e.reported)
                    .map(Entry::id).findFirst().orElseThrow(() -> new IOException("Unreported Stock receipts are full"));
            next.remove(victim);
        }
        save(new Snapshot(revision + 1, next));
        entries = next; revision++;
    }
    private record Snapshot(long revision, Map<String, Entry> entries) {}
    private Path temporary() { return path.resolveSibling(path.getFileName() + ".tmp"); }
    private void save(Snapshot snapshot) throws IOException {
        var root = new LinkedHashMap<String, Object>();
        root.put("schema", 1); root.put("world", world); root.put("revision", snapshot.revision);
        var rows = new ArrayList<Map<String, Object>>();
        for (Entry entry : snapshot.entries.values()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", entry.id); row.put("requester", entry.requester);
            row.put("queueId", entry.queue.id); row.put("queueGeneration", entry.queue.generation);
            row.put("queueStep", entry.queue.step); row.put("queueAttempt", entry.queue.attempt);
            row.put("homeGeneration", entry.homeGeneration); row.put("baselineCycle", entry.baselineCycle);
            row.put("startedAt", entry.startedAt); row.put("state", entry.state.name());
            row.put("detail", entry.detail); row.put("reported", entry.reported); rows.add(row);
        }
        root.put("entries", rows);
        Files.createDirectories(path.getParent());
        Files.writeString(temporary(), SimpleJson.stringify(root), StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary(), StandardOpenOption.WRITE)) { channel.force(true); }
        try { Files.move(temporary(), path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary(), path, StandardCopyOption.REPLACE_EXISTING); }
    }
    private AtomicStoreRecovery.Decoded<Snapshot> decode(byte[] bytes, Path source) throws IOException {
        if (bytes.length < 1 || bytes.length > 262144) throw new IOException("Invalid Stock receipt size");
        try {
            Map<?, ?> root = object(SimpleJson.parse(new String(bytes, StandardCharsets.UTF_8)));
            if (number(root, "schema") != 1) throw new AtomicStoreRecovery.UnsupportedSchemaException("finite Stock commands", (int) number(root, "schema"));
            if (!world.equals(text(root, "world"))) throw new IllegalArgumentException("Stock receipt belongs to another world");
            long version = number(root, "revision");
            if (version < 0 || !(root.get("entries") instanceof List<?> rows) || rows.size() > LIMIT)
                throw new IllegalArgumentException("Invalid Stock receipt list");
            Map<String, Entry> restored = new LinkedHashMap<>();
            int active = 0;
            for (Object value : rows) {
                Map<?, ?> row = object(value);
                if (!(row.get("reported") instanceof Boolean sent)) throw new IllegalArgumentException("Invalid reported flag");
                Entry entry = new Entry(text(row, "id"), text(row, "requester"),
                        new QueueTag(text(row, "queueId"), number(row, "queueGeneration"),
                                Math.toIntExact(number(row, "queueStep")), Math.toIntExact(number(row, "queueAttempt"))),
                        number(row, "homeGeneration"), number(row, "baselineCycle"), number(row, "startedAt"),
                        State.valueOf(text(row, "state")), text(row, "detail"), sent);
                if (restored.put(entry.id, entry) != null) throw new IllegalArgumentException("Duplicate Stock receipt");
                if (!entry.state.terminal()) active++;
            }
            if (active > 1) throw new IllegalArgumentException("Overlapping Stock receipts");
            return new AtomicStoreRecovery.Decoded<>(new Snapshot(version, restored), version, 1);
        } catch (RuntimeException error) { throw new IOException("Invalid Stock receipt store " + source, error); }
    }
    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected object"); return map;
    }
    private static String text(Map<?, ?> row, String key) {
        if (!(row.get(key) instanceof String value)) throw new IllegalArgumentException("Expected " + key); return value;
    }
    private static long number(Map<?, ?> row, String key) {
        if (!(row.get(key) instanceof Number number) || number.doubleValue() != number.longValue())
            throw new IllegalArgumentException("Expected integer " + key);
        return number.longValue();
    }
}
