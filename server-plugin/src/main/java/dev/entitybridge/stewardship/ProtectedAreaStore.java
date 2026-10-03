package dev.entitybridge.stewardship;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Atomic Paper-owned storage for the canonical protected-area snapshot. */
public final class ProtectedAreaStore {
    private static final long MAX_BYTES = 1_048_576L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final Path temporaryPath;

    public ProtectedAreaStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    public synchronized LoadResult load() {
        if (!Files.exists(path) && !Files.exists(temporaryPath)) {
            return new LoadResult(ProtectedAreaPolicy.empty(), false, "");
        }
        IOException primaryFailure = null;
        if (Files.exists(path)) {
            try {
                Decoded decoded = read(path);
                if (decoded.migrated()) {
                    save(decoded.snapshot());
                    return new LoadResult(
                            decoded.snapshot(), false,
                            "Migrated released protected-area schema 1 to typed area schema 2");
                }
                return new LoadResult(decoded.snapshot(), false, "");
            } catch (IOException failure) {
                primaryFailure = failure;
            }
        }
        if (Files.exists(temporaryPath)) {
            try {
                Decoded decoded = read(temporaryPath);
                moveIntoPlace();
                if (decoded.migrated()) save(decoded.snapshot());
                return new LoadResult(
                        decoded.snapshot(),
                        false,
                        "Recovered area policy from its atomic temporary file"
                                + (decoded.migrated() ? " and migrated schema 1 to schema 2" : ""));
            } catch (IOException temporaryFailure) {
                if (primaryFailure != null) temporaryFailure.addSuppressed(primaryFailure);
                return failedClosed(temporaryFailure);
            }
        }
        return failedClosed(primaryFailure == null
                ? new IOException("protected-area policy is unavailable")
                : primaryFailure);
    }

    public synchronized void save(ProtectedAreaPolicy.Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] encoded = GSON.toJson(encode(snapshot)).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) throw new IOException("protected-area policy is too large");
        Files.write(
                temporaryPath,
                encoded,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                StandardOpenOption.SYNC);
        moveIntoPlace();
    }

    Path temporaryPath() {
        return temporaryPath;
    }

    private Decoded read(Path source) throws IOException {
        long size = Files.size(source);
        if (size <= 0L || size > MAX_BYTES) {
            throw new IOException("protected-area policy size is outside the bounded range");
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("root must be an object");
            JsonObject root = parsed.getAsJsonObject();
            int schema = integer(root, "schemaVersion");
            long revision = longValue(root, "revision");
            String digest = text(root, "digest");
            JsonElement areaElement = root.get("areas");
            if (areaElement == null || !areaElement.isJsonArray()) {
                throw new IllegalArgumentException("areas must be an array");
            }
            List<ProtectedAreaPolicy.Area> areas = new ArrayList<>();
            for (JsonElement element : areaElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("area must be an object");
                }
                JsonObject area = element.getAsJsonObject();
                areas.add(decodeArea(schema, area));
            }
            if (schema == ProtectedAreaPolicy.LEGACY_SCHEMA_VERSION) {
                return new Decoded(
                        ProtectedAreaPolicy.migrateLegacySnapshot(revision, digest, areas), true);
            }
            return new Decoded(
                    new ProtectedAreaPolicy.Snapshot(schema, revision, digest, areas), false);
        } catch (RuntimeException error) {
            throw new IOException("Corrupt protected-area policy: " + source, error);
        }
    }

    private static JsonObject encode(ProtectedAreaPolicy.Snapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", snapshot.schemaVersion());
        root.addProperty("revision", snapshot.revision());
        root.addProperty("digest", snapshot.digest());
        JsonArray areas = new JsonArray();
        for (ProtectedAreaPolicy.Area area : snapshot.areas()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("name", area.name());
            encoded.addProperty("kind", area.kind().wireName());
            encoded.addProperty("dimension", area.dimension());
            encoded.addProperty("minX", area.minX());
            encoded.addProperty("maxX", area.maxX());
            encoded.addProperty("minZ", area.minZ());
            encoded.addProperty("maxZ", area.maxZ());
            if (area.kind() == ProtectedAreaPolicy.AreaKind.MINING) {
                encoded.addProperty("minY", area.minY());
                encoded.addProperty("maxY", area.maxY());
                encoded.addProperty("entranceX", area.entranceX());
                encoded.addProperty("entranceY", area.entranceY());
                encoded.addProperty("entranceZ", area.entranceZ());
            }
            areas.add(encoded);
        }
        root.add("areas", areas);
        return root;
    }

    private void moveIntoPlace() throws IOException {
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

    private static int integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int result = value.getAsInt();
        if (value.getAsDouble() != result) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return result;
    }

    private static long longValue(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        long result = value.getAsLong();
        if (value.getAsDouble() != result) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return result;
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return value.getAsString();
    }

    private static ProtectedAreaPolicy.Area decodeArea(int schema, JsonObject area) {
        String name = text(area, "name");
        String dimension = text(area, "dimension");
        int minX = integer(area, "minX");
        int maxX = integer(area, "maxX");
        int minZ = integer(area, "minZ");
        int maxZ = integer(area, "maxZ");
        if (schema == ProtectedAreaPolicy.LEGACY_SCHEMA_VERSION) {
            return new ProtectedAreaPolicy.Area(
                    name, dimension, minX, maxX, minZ, maxZ);
        }
        ProtectedAreaPolicy.AreaKind kind = ProtectedAreaPolicy.AreaKind.parse(
                text(area, "kind"));
        if (kind == ProtectedAreaPolicy.AreaKind.MINING) {
            return new ProtectedAreaPolicy.Area(
                    name, kind, dimension,
                    minX, maxX,
                    integer(area, "minY"), integer(area, "maxY"),
                    minZ, maxZ,
                    integer(area, "entranceX"), integer(area, "entranceY"),
                    integer(area, "entranceZ"));
        }
        return new ProtectedAreaPolicy.Area(
                name, kind, dimension,
                minX, maxX, 0, 0, minZ, maxZ, 0, 0, 0);
    }

    private static LoadResult failedClosed(IOException failure) {
        String detail = failure == null ? "unknown error" : failure.getMessage();
        return new LoadResult(
                ProtectedAreaPolicy.empty(),
                true,
                "Protected-area policy is unreadable; Entity world mutations are disabled: "
                        + Objects.requireNonNullElse(detail, "unknown error"));
    }

    public record LoadResult(
            ProtectedAreaPolicy.Snapshot snapshot,
            boolean failClosed,
            String warning) {
        public LoadResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            warning = Objects.requireNonNullElse(warning, "");
        }
    }

    private record Decoded(ProtectedAreaPolicy.Snapshot snapshot, boolean migrated) {
    }
}
