package dev.entity.client.autonomy;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Atomic persistence for Entity 2.2 inventory reserves and private home chest. */
public final class AutonomySettingsStore {
    private static final int SCHEMA_VERSION = 1;

    private final Path path;
    private final Path temporaryPath;

    public AutonomySettingsStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    /**
     * Loads settings without collapsing an existing-but-untrusted file into a
     * first-run absence.  Callers may apply defaults only for {@link
     * LoadState#ABSENT}; corrupt and future-schema files are deliberately
     * non-authoritative so they can never erase a separately checksummed Home
     * cursor.
     */
    public synchronized LoadResult load() {
        try {
            if (definitelyAbsent(path) && definitelyAbsent(temporaryPath)) {
                return new LoadResult(LoadState.ABSENT, Settings.defaults(), "");
            }
            Settings settings = AtomicStoreRecovery.load(
                    path,
                    temporaryPath,
                    "autonomy settings",
                    Settings::defaults,
                    AutonomySettingsStore::decodeStore);
            return new LoadResult(LoadState.VALID, settings, "");
        } catch (AtomicStoreRecovery.RecoveryException error) {
            return failedLoad(
                    error.unsupportedSchema() ? LoadState.UNSUPPORTED : LoadState.CORRUPT,
                    Objects.requireNonNullElse(
                            error.getMessage(), error.getClass().getSimpleName()));
        } catch (IOException | RuntimeException error) {
            return failedLoad(
                    LoadState.CORRUPT,
                    Objects.requireNonNullElse(
                            error.getMessage(), error.getClass().getSimpleName()));
        }
    }

    private static AtomicStoreRecovery.Decoded<Settings> decodeStore(
            byte[] bytes,
            Path source) throws IOException {
        try {
            Map<String, Object> encoded = object(SimpleJson.parse(
                    StandardCharsets.UTF_8.newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()));
            int schema = integer(encoded.get("schemaVersion"), "schemaVersion");
            if (schema != SCHEMA_VERSION) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "autonomy settings", schema);
            }
            Settings settings = new Settings(
                    bool(encoded.get("stockEnabled"), "stockEnabled"),
                    integer(encoded.get("buildingBlockReserve"), "buildingBlockReserve"),
                    integer(encoded.get("foodReserve"), "foodReserve"),
                    text(encoded.get("homeDimension"), "homeDimension"),
                    integer(encoded.get("homeX"), "homeX"),
                    integer(encoded.get("homeY"), "homeY"),
                    integer(encoded.get("homeZ"), "homeZ"),
                    bool(encoded.get("hasHomeChest"), "hasHomeChest"));
            settings.validate();
            return new AtomicStoreRecovery.Decoded<>(settings, 0L, schema);
        } catch (AtomicStoreRecovery.UnsupportedSchemaException unsupported) {
            throw unsupported;
        } catch (IOException | RuntimeException error) {
            throw new IOException("Corrupt autonomy settings: " + source, error);
        }
    }

    private static boolean definitelyAbsent(Path candidate) throws IOException {
        if (Files.exists(candidate)) return false;
        if (Files.notExists(candidate)) return true;
        throw new IOException("could not determine whether settings candidate exists: " + candidate);
    }

    private LoadResult failedLoad(LoadState state, String reason) {
        return new LoadResult(
                state,
                Settings.defaults(),
                path + ": " + Objects.requireNonNullElse(reason, "unreadable settings"));
    }

    public synchronized void save(Settings settings) throws IOException {
        Objects.requireNonNull(settings, "settings").validate();
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        LinkedHashMap<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("schemaVersion", SCHEMA_VERSION);
        encoded.put("stockEnabled", settings.stockEnabled());
        encoded.put("buildingBlockReserve", settings.buildingBlockReserve());
        encoded.put("foodReserve", settings.foodReserve());
        encoded.put("homeDimension", settings.homeDimension());
        encoded.put("homeX", settings.homeX());
        encoded.put("homeY", settings.homeY());
        encoded.put("homeZ", settings.homeZ());
        encoded.put("hasHomeChest", settings.hasHomeChest());
        Files.writeString(
                temporaryPath,
                SimpleJson.stringify(encoded) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(temporaryPath, path,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record Settings(
            boolean stockEnabled,
            int buildingBlockReserve,
            int foodReserve,
            String homeDimension,
            int homeX,
            int homeY,
            int homeZ,
            boolean hasHomeChest) {

        public Settings {
            homeDimension = Objects.requireNonNullElse(homeDimension, "");
        }

        public static Settings defaults() {
            return new Settings(true, 32, 12, "", 0, 0, 0, false);
        }

        public Settings withStockEnabled(boolean enabled) {
            return new Settings(
                    enabled, buildingBlockReserve, foodReserve,
                    homeDimension, homeX, homeY, homeZ, hasHomeChest);
        }

        public Settings withHome(String dimension, int x, int y, int z) {
            return new Settings(
                    stockEnabled, buildingBlockReserve, foodReserve,
                    Objects.requireNonNullElse(dimension, ""), x, y, z, true);
        }

        public Settings withoutHome() {
            return new Settings(
                    stockEnabled, buildingBlockReserve, foodReserve,
                    "", 0, 0, 0, false);
        }

        private void validate() {
            if (buildingBlockReserve < 0 || buildingBlockReserve > 512) {
                throw new IllegalArgumentException("buildingBlockReserve must be 0..512");
            }
            if (foodReserve < 0 || foodReserve > 256) {
                throw new IllegalArgumentException("foodReserve must be 0..256");
            }
            if (hasHomeChest && homeDimension.isBlank()) {
                throw new IllegalArgumentException("home chest dimension is required");
            }
        }
    }

    public enum LoadState {
        ABSENT(true),
        VALID(true),
        CORRUPT(false),
        UNSUPPORTED(false);

        private final boolean authoritative;

        LoadState(boolean authoritative) {
            this.authoritative = authoritative;
        }

        /** Whether automatic settings-to-Home reconciliation may use this result. */
        public boolean authoritative() {
            return authoritative;
        }
    }

    public record LoadResult(LoadState state, Settings settings, String detail) {
        public LoadResult {
            state = Objects.requireNonNull(state, "state");
            settings = Objects.requireNonNull(settings, "settings");
            detail = Objects.requireNonNullElse(detail, "");
            if (state.authoritative() && !detail.isBlank()) {
                throw new IllegalArgumentException(
                        "authoritative settings cannot carry a load failure");
            }
            if (!state.authoritative() && detail.isBlank()) {
                throw new IllegalArgumentException(
                        "untrusted settings require an actionable diagnostic");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("settings root must be an object");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException("settings contains a non-string key");
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

    private static int integer(Object value, String name) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return number.intValue();
    }

    private static String text(Object value, String name) {
        if (!(value instanceof String result)) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return result;
    }
}
