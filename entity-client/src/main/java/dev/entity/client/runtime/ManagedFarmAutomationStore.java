package dev.entity.client.runtime;

import dev.entity.core.persistence.AtomicStoreRecovery;
import dev.entity.core.persistence.SimpleJson;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * World-bound managed-farm preferences and replay fence.
 *
 * <p>The enabled map grants only recurring admission for the exact saved area
 * fingerprint. The attempt key is written before a local automatic mission is
 * submitted, so a restart cannot replay the same mature-crop observation. A
 * later observation with no mature crops clears that one fence and rearms the
 * farm for a genuinely new growth cycle.</p>
 */
public final class ManagedFarmAutomationStore {
    public record State(Map<String, String> enabledAreas,
                        Map<String, String> attemptedWorkKeys,
                        long revision) {
        public State {
            enabledAreas = normalizedMap(enabledAreas, "enabledAreas", false);
            attemptedWorkKeys = normalizedMap(
                    attemptedWorkKeys, "attemptedWorkKeys", true);
            if (!enabledAreas.keySet().containsAll(attemptedWorkKeys.keySet())) {
                throw new IllegalArgumentException(
                        "attempted farm work must belong to an enabled area");
            }
            if (revision < 0L) {
                throw new IllegalArgumentException("managed-farm revision cannot be negative");
            }
        }
    }

    private final Path path;
    private final String worldId;
    private State state;

    public ManagedFarmAutomationStore(
            Path worldDirectory,
            WorldStateScope scope) throws IOException {
        Objects.requireNonNull(worldDirectory, "worldDirectory");
        worldId = scope == null ? "" : scope.key();
        path = scope == null ? null : worldDirectory.toAbsolutePath().normalize()
                .resolve("managed-farms.json");
        state = path == null ? defaults() : AtomicStoreRecovery.load(
                path, temporary(), "managed-farm automation",
                ManagedFarmAutomationStore::defaults, this::decode);
    }

    public static State defaults() {
        return new State(Map.of(), Map.of(), 0L);
    }

    public State state() {
        return state;
    }

    public boolean bound() {
        return path != null;
    }

    public boolean enabled(String area, String fingerprint) {
        return fingerprint(area, fingerprint).equals(
                state.enabledAreas().get(normalizeArea(area)));
    }

    public String savedFingerprint(String area) {
        return state.enabledAreas().getOrDefault(normalizeArea(area), "");
    }

    public String attemptedWorkKey(String area) {
        return state.attemptedWorkKeys().getOrDefault(normalizeArea(area), "");
    }

    /** Changing OFF/ON or rebinding changed geometry explicitly rearms this farm. */
    public boolean setEnabled(
            String area,
            String fingerprint,
            boolean enabled) throws IOException {
        requireBound();
        String name = normalizeArea(area);
        String exactFingerprint = fingerprint(name, fingerprint);
        LinkedHashMap<String, String> areas = new LinkedHashMap<>(state.enabledAreas());
        LinkedHashMap<String, String> attempts = new LinkedHashMap<>(state.attemptedWorkKeys());
        boolean changed;
        if (enabled) {
            changed = !exactFingerprint.equals(areas.put(name, exactFingerprint));
            if (changed) attempts.remove(name);
        } else {
            changed = areas.remove(name) != null;
            changed |= attempts.remove(name) != null;
        }
        if (!changed) return false;
        save(new State(areas, attempts, Math.addExact(state.revision(), 1L)));
        return true;
    }

    /**
     * Durable before-submit fence for the current growth cycle. Once present,
     * no changed subset or supply fact may replace it until a loaded observation
     * proves the field has no mature crop left.
     */
    public boolean markAttempt(
            String area,
            String fingerprint,
            String workKey) throws IOException {
        requireBound();
        String name = normalizeArea(area);
        if (!enabled(name, fingerprint)) {
            throw new IllegalArgumentException(
                    "managed farm is not enabled for this exact area fingerprint");
        }
        String key = workKey(workKey);
        if (state.attemptedWorkKeys().containsKey(name)) return false;
        LinkedHashMap<String, String> attempts = new LinkedHashMap<>(state.attemptedWorkKeys());
        attempts.put(name, key);
        save(new State(state.enabledAreas(), attempts,
                Math.addExact(state.revision(), 1L)));
        return true;
    }

    /** Rolls back only a submission known to have failed before any mission existed. */
    public boolean abandonUnsubmittedAttempt(
            String area,
            String fingerprint,
            String workKey) throws IOException {
        requireBound();
        String name = normalizeArea(area);
        String key = workKey(workKey);
        if (!enabled(name, fingerprint)
                || !key.equals(state.attemptedWorkKeys().get(name))) return false;
        LinkedHashMap<String, String> attempts = new LinkedHashMap<>(state.attemptedWorkKeys());
        attempts.remove(name);
        save(new State(state.enabledAreas(), attempts,
                Math.addExact(state.revision(), 1L)));
        return true;
    }

    /** Rearms only after the loaded world proves that no mature crop remains. */
    public boolean clearAttempt(
            String area,
            String fingerprint) throws IOException {
        requireBound();
        String name = normalizeArea(area);
        if (!enabled(name, fingerprint)
                || !state.attemptedWorkKeys().containsKey(name)) return false;
        LinkedHashMap<String, String> attempts = new LinkedHashMap<>(state.attemptedWorkKeys());
        attempts.remove(name);
        save(new State(state.enabledAreas(), attempts,
                Math.addExact(state.revision(), 1L)));
        return true;
    }

    private void save(State next) throws IOException {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("schemaVersion", 1);
        encoded.put("worldId", worldId);
        encoded.put("enabledAreas", new TreeMap<>(next.enabledAreas()));
        encoded.put("attemptedWorkKeys", new TreeMap<>(next.attemptedWorkKeys()));
        encoded.put("revision", next.revision());
        Files.createDirectories(path.getParent());
        Files.writeString(temporary(), SimpleJson.stringify(encoded),
                StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                temporary(), StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(temporary(), path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary(), path, StandardCopyOption.REPLACE_EXISTING);
        }
        state = next;
    }

    private AtomicStoreRecovery.Decoded<State> decode(
            byte[] bytes,
            Path source) throws IOException {
        if (bytes.length == 0 || bytes.length > 262_144) {
            throw new IOException("Invalid managed-farm settings size: " + source);
        }
        try {
            Object value = SimpleJson.parse(StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString());
            if (!(value instanceof Map<?, ?> root)) {
                throw new IllegalArgumentException(
                        "Expected managed-farm settings object");
            }
            int schema = Math.toIntExact(number(root, "schemaVersion"));
            if (schema != 1) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "managed-farm automation", schema);
            }
            if (!worldId.equals(root.get("worldId"))) {
                throw new IllegalArgumentException(
                        "Managed-farm settings belong to another world");
            }
            State decoded = new State(
                    stringMap(root, "enabledAreas"),
                    stringMap(root, "attemptedWorkKeys"),
                    number(root, "revision"));
            return new AtomicStoreRecovery.Decoded<>(
                    decoded, decoded.revision(), schema);
        } catch (AtomicStoreRecovery.UnsupportedSchemaException unsupported) {
            throw unsupported;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid managed-farm settings: " + source,
                    invalid);
        }
    }

    private Path temporary() {
        return path.resolveSibling(path.getFileName() + ".tmp");
    }

    private void requireBound() {
        if (!bound()) {
            throw new IllegalStateException(
                    "Managed-farm settings require an authenticated world binding");
        }
    }

    private static Map<String, String> stringMap(Map<?, ?> root, String key) {
        Object value = root.get(key);
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Expected object " + key);
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        raw.forEach((name, text) -> {
            if (!(name instanceof String area) || !(text instanceof String entry)) {
                throw new IllegalArgumentException("Expected string map " + key);
            }
            result.put(area, entry);
        });
        return result;
    }

    private static long number(Map<?, ?> root, String key) {
        if (!(root.get(key) instanceof Number value)
                || value.doubleValue() != value.longValue()) {
            throw new IllegalArgumentException("Expected integer " + key);
        }
        return value.longValue();
    }

    private static Map<String, String> normalizedMap(
            Map<String, String> source,
            String label,
            boolean workKeys) {
        Objects.requireNonNull(source, label);
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        source.forEach((rawArea, rawValue) -> {
            String area = normalizeArea(rawArea);
            String value = workKeys ? workKey(rawValue)
                    : fingerprint(area, rawValue);
            if (result.putIfAbsent(area, value) != null) {
                throw new IllegalArgumentException(label
                        + " contains duplicate area " + area);
            }
        });
        return Map.copyOf(result);
    }

    private static String normalizeArea(String value) {
        String normalized = Objects.requireNonNullElse(value, "")
                .trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,24}")) {
            throw new IllegalArgumentException("invalid managed-farm area name");
        }
        return normalized;
    }

    private static String fingerprint(String area, String value) {
        String checked = Objects.requireNonNullElse(value, "").trim();
        if (checked.isEmpty() || checked.length() > 1_024
                || !checked.startsWith("managed-farm-v1|" + normalizeArea(area) + "|")) {
            throw new IllegalArgumentException("invalid managed-farm area fingerprint");
        }
        return checked;
    }

    private static String workKey(String value) {
        String checked = Objects.requireNonNullElse(value, "").trim();
        if (checked.isEmpty() || checked.length() > 4_096) {
            throw new IllegalArgumentException("invalid managed-farm work key");
        }
        return checked;
    }
}
