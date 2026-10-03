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

/** One saved resource-discovery choice per authenticated Minecraft save, not Home state. */
public final class PerceptionSettingsStore {
    public enum Mode { NORMAL, LEGITIMATE }
    public record Settings(Mode mode, long revision) {
        public Settings {
            Objects.requireNonNull(mode, "mode");
            if (revision < 0) throw new IllegalArgumentException("Negative perception revision");
        }
    }

    private final Path path;
    private final String worldId;
    private Settings settings;

    public PerceptionSettingsStore(Path worldDirectory, WorldStateScope scope) throws IOException {
        Objects.requireNonNull(worldDirectory, "worldDirectory");
        worldId = scope == null ? "" : scope.key();
        path = scope == null ? null : worldDirectory.toAbsolutePath().normalize().resolve("perception.json");
        // Pre-handshake runtimes are inert; never create an unscoped settings file.
        settings = path == null ? defaults() : AtomicStoreRecovery.load(
                path, temporary(), "resource perception settings", PerceptionSettingsStore::defaults, this::decode);
    }

    public Settings settings() { return settings; }
    public boolean bound() { return path != null; }
    private static Settings defaults() { return new Settings(Mode.NORMAL, 0); }
    private Path temporary() { return path.resolveSibling(path.getFileName() + ".tmp"); }

    /** Commit before publishing the new mode to resource discovery. Same-mode requests are read-only. */
    public boolean save(Mode mode) throws IOException {
        Objects.requireNonNull(mode, "mode");
        if (mode == settings.mode()) return false;
        if (!bound()) throw new IllegalStateException("Perception is waiting for an authenticated world binding");
        Settings next = new Settings(mode, Math.addExact(settings.revision(), 1));
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("schemaVersion", 1);
        encoded.put("worldId", worldId);
        encoded.put("mode", next.mode().name());
        encoded.put("revision", next.revision());
        Files.createDirectories(path.getParent());
        Files.writeString(temporary(), SimpleJson.stringify(encoded), StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary(), StandardOpenOption.WRITE)) { channel.force(true); }
        try {
            Files.move(temporary(), path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary(), path, StandardCopyOption.REPLACE_EXISTING);
        }
        settings = next;
        return true;
    }

    private AtomicStoreRecovery.Decoded<Settings> decode(byte[] bytes, Path source) throws IOException {
        if (bytes.length == 0 || bytes.length > 4096) throw new IOException("Invalid perception settings size: " + source);
        try {
            Object decoded = SimpleJson.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            if (!(decoded instanceof Map<?, ?> root)) throw new IllegalArgumentException("Expected settings object");
            int schema = Math.toIntExact(number(root, "schemaVersion"));
            if (schema != 1) throw new AtomicStoreRecovery.UnsupportedSchemaException("resource perception", schema);
            if (!worldId.equals(root.get("worldId"))) throw new IllegalArgumentException("Perception settings belong to another world");
            if (!(root.get("mode") instanceof String mode)) throw new IllegalArgumentException("Missing perception mode");
            Settings value = new Settings(Mode.valueOf(mode), number(root, "revision"));
            return new AtomicStoreRecovery.Decoded<>(value, value.revision(), schema);
        } catch (RuntimeException error) {
            throw new IOException("Invalid perception settings: " + source, error);
        }
    }

    private static long number(Map<?, ?> root, String key) {
        if (!(root.get(key) instanceof Number number) || number.doubleValue() != number.longValue())
            throw new IllegalArgumentException("Expected integer " + key);
        return number.longValue();
    }
}
