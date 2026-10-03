package dev.entity.core.persistence;

import dev.entity.core.protection.ProtectionPolicy;

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

/** Atomic, dependency-free persistence for the user-controlled protection policy. */
public final class JsonProtectionSettingsStore {
    public static final int SCHEMA_VERSION = 1;
    public static final double MAXIMUM_SAFE_ENGAGE_DISTANCE = 128.0;
    private static final int MAXIMUM_FILE_BYTES = 64 * 1024;

    private final Path path;
    private final Path temporaryPath;

    public JsonProtectionSettingsStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    /**
     * A genuinely new install protects Entity itself by default. An existing
     * but unreadable/corrupt policy still fails closed with both toggles off;
     * corruption must never be mistaken for informed user consent.
     */
    public synchronized LoadResult load() {
        try {
            ProtectionPolicy.Settings settings = AtomicStoreRecovery.load(
                    path,
                    temporaryPath,
                    "protection-settings store",
                    ProtectionPolicy.Settings::firstRunDefaults,
                    JsonProtectionSettingsStore::decodeStore);
            return new LoadResult(settings, false, "");
        } catch (IOException | RuntimeException error) {
            return new LoadResult(
                    ProtectionPolicy.Settings.disabled(),
                    true,
                    "Could not load protection settings from " + path + ": " + error.getMessage());
        }
    }

    private static AtomicStoreRecovery.Decoded<ProtectionPolicy.Settings> decodeStore(
            byte[] encoded,
            Path source) throws IOException {
        if (encoded.length < 1 || encoded.length > MAXIMUM_FILE_BYTES) {
            throw new IOException("protection-settings file size is outside the bounded range");
        }
        try {
            Object parsed = SimpleJson.parse(StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(encoded)).toString());
            Map<String, Object> root = object(parsed, "root");
            int schemaVersion = integer(root.get("schemaVersion"), "schemaVersion");
            if (schemaVersion != SCHEMA_VERSION) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "protection-settings store", schemaVersion);
            }
            ProtectionPolicy.Settings settings = checked(new ProtectionPolicy.Settings(
                    bool(root.get("protectSelf"), "protectSelf"),
                    bool(root.get("protectOwner"), "protectOwner"),
                    text(root.get("ownerName"), "ownerName"),
                    decimal(root.get("maximumEngageDistance"), "maximumEngageDistance"),
                    decimal(root.get("retreatHealthFraction"), "retreatHealthFraction"),
                    ProtectionPolicy.CombatMode.parse(optionalText(
                            root.get("combatMode"), "combatMode", "defensive"))));
            return new AtomicStoreRecovery.Decoded<>(settings, 0L, schemaVersion);
        } catch (AtomicStoreRecovery.UnsupportedSchemaException unsupported) {
            throw unsupported;
        } catch (IllegalArgumentException error) {
            throw new IOException("Corrupt protection-settings store: " + source, error);
        }
    }

    /** Writes and flushes a sibling temporary file before replacing the live file. */
    public synchronized void save(ProtectionPolicy.Settings settings) throws IOException {
        ProtectionPolicy.Settings safe = checked(Objects.requireNonNull(settings, "settings"));
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("protectSelf", safe.protectSelf());
        root.put("protectOwner", safe.protectOwner());
        root.put("ownerName", safe.ownerName());
        root.put("maximumEngageDistance", safe.maximumEngageDistance());
        root.put("retreatHealthFraction", safe.retreatHealthFraction());
        root.put("combatMode", safe.combatMode().name().toLowerCase(java.util.Locale.ROOT));
        Files.writeString(
                temporaryPath,
                SimpleJson.stringify(root),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(
                    temporaryPath,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ProtectionPolicy.Settings checked(ProtectionPolicy.Settings settings) {
        double distance = settings.maximumEngageDistance();
        double retreat = settings.retreatHealthFraction();
        if (!Double.isFinite(distance) || distance <= 0 || distance > MAXIMUM_SAFE_ENGAGE_DISTANCE
                || !Double.isFinite(retreat) || retreat < 0 || retreat > 1) {
            throw new IllegalArgumentException("unsafe protection numeric settings");
        }
        return settings;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(name + " contains a non-string key");
            }
        }
        return (Map<String, Object>) map;
    }

    private static boolean bool(Object value, String name) {
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(name + " must be boolean");
        }
        return result;
    }

    private static String text(Object value, String name) {
        if (!(value instanceof String result)) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return result;
    }

    private static String optionalText(Object value, String name, String fallback) {
        return value == null ? fallback : text(value, name);
    }

    private static int integer(Object value, String name) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return number.intValue();
    }

    private static double decimal(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be numeric");
        }
        return number.doubleValue();
    }

    public record LoadResult(
            ProtectionPolicy.Settings settings,
            boolean recoveredWithDisabledDefaults,
            String warning) {
        public LoadResult {
            settings = Objects.requireNonNull(settings, "settings");
            warning = Objects.requireNonNullElse(warning, "");
        }
    }
}
