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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** World-bound preferences only: loading or saving never schedules Minecraft work. */
public final class IdleSettingsStore {
    public static final Set<String> CATEGORIES = Set.of("food", "fuel", "logs", "iron");
    public record Settings(boolean enabled, long delayMillis, boolean allowOffline,
                           Map<String, Integer> surplusTargets, long revision) {
        public Settings {
            if (delayMillis < 60_000L || delayMillis > 86_400_000L || delayMillis % 60_000L != 0)
                throw new IllegalArgumentException("Idle delay must be whole minutes from 1 to 1440");
            Objects.requireNonNull(surplusTargets, "surplusTargets");
            if (!surplusTargets.keySet().equals(CATEGORIES))
                throw new IllegalArgumentException("Idle targets must contain food, fuel, logs and iron exactly");
            for (Integer count : surplusTargets.values()) {
                if (count == null || count < 0 || count > 4096)
                    throw new IllegalArgumentException("Idle target count must be from 0 to 4096");
            }
            surplusTargets = Map.copyOf(surplusTargets);
            if (revision < 0) throw new IllegalArgumentException("Negative idle revision");
        }
    }

    private final Path path;
    private final String worldId;
    private Settings settings;

    public IdleSettingsStore(Path worldDirectory, WorldStateScope scope) throws IOException {
        Objects.requireNonNull(worldDirectory, "worldDirectory");
        worldId = scope == null ? "" : scope.key();
        path = scope == null ? null : worldDirectory.toAbsolutePath().normalize().resolve("idle.json");
        settings = path == null ? defaults() : AtomicStoreRecovery.load(
                path, temporary(), "idle settings", IdleSettingsStore::defaults, this::decode);
    }

    public static Settings defaults() {
        return new Settings(false, 300_000L, false, Map.of("food", 32, "fuel", 32, "logs", 32, "iron", 16), 0);
    }
    public Settings settings() { return settings; }
    public boolean bound() { return path != null; }
    private Path temporary() { return path.resolveSibling(path.getFileName() + ".tmp"); }

    /** Caller revisions never grant authority; one real preference change increments exactly once. */
    public boolean save(Settings requested) throws IOException {
        Objects.requireNonNull(requested, "requested");
        if (!bound()) throw new IllegalStateException("Idle settings require an authenticated world binding");
        if (requested.enabled() == settings.enabled() && requested.delayMillis() == settings.delayMillis()
                && requested.allowOffline() == settings.allowOffline()
                && requested.surplusTargets().equals(settings.surplusTargets())) return false;
        Settings next = new Settings(requested.enabled(), requested.delayMillis(), requested.allowOffline(),
                requested.surplusTargets(), Math.addExact(settings.revision(), 1));
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("schemaVersion", 1); encoded.put("worldId", worldId);
        encoded.put("enabled", next.enabled()); encoded.put("delayMillis", next.delayMillis());
        encoded.put("allowOffline", next.allowOffline());
        encoded.put("surplusTargets", new java.util.TreeMap<>(next.surplusTargets()));
        encoded.put("revision", next.revision());
        Files.createDirectories(path.getParent());
        Files.writeString(temporary(), SimpleJson.stringify(encoded), StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary(), StandardOpenOption.WRITE)) { channel.force(true); }
        try { Files.move(temporary(), path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary(), path, StandardCopyOption.REPLACE_EXISTING);
        }
        settings = next;
        return true;
    }

    private AtomicStoreRecovery.Decoded<Settings> decode(byte[] bytes, Path source) throws IOException {
        if (bytes.length == 0 || bytes.length > 4096) throw new IOException("Invalid idle settings size: " + source);
        try {
            Object value = SimpleJson.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            if (!(value instanceof Map<?, ?> root)) throw new IllegalArgumentException("Expected idle settings object");
            int schema = Math.toIntExact(number(root, "schemaVersion"));
            if (schema != 1) throw new AtomicStoreRecovery.UnsupportedSchemaException("idle settings", schema);
            if (!worldId.equals(root.get("worldId"))) throw new IllegalArgumentException("Idle settings belong to another world");
            if (!(root.get("surplusTargets") instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Missing idle targets");
            Map<String, Integer> targets = new LinkedHashMap<>();
            for (Object key : raw.keySet()) {
                if (!(key instanceof String name)) throw new IllegalArgumentException("Invalid idle target");
                targets.put(name, Math.toIntExact(number(raw, name)));
            }
            Settings decoded = new Settings(bool(root, "enabled"), number(root, "delayMillis"),
                    bool(root, "allowOffline"), targets, number(root, "revision"));
            return new AtomicStoreRecovery.Decoded<>(decoded, decoded.revision(), schema);
        } catch (RuntimeException invalid) { throw new IOException("Invalid idle settings: " + source, invalid); }
    }
    private static boolean bool(Map<?, ?> root, String key) {
        if (!(root.get(key) instanceof Boolean value)) throw new IllegalArgumentException("Expected boolean " + key);
        return value;
    }
    private static long number(Map<?, ?> root, String key) {
        if (!(root.get(key) instanceof Number value) || value.doubleValue() != value.longValue())
            throw new IllegalArgumentException("Expected integer " + key);
        return value.longValue();
    }
}
