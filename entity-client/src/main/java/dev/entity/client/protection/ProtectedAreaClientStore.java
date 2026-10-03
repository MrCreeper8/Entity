package dev.entity.client.protection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.entity.client.bridge.ProtectedAreaPolicyProtocol;
import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Atomic cache used only after a fresh authenticated Paper policy is installed. */
public final class ProtectedAreaClientStore {
    private static final long MAX_BYTES = 1_048_576L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final Path temporaryPath;

    public ProtectedAreaClientStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    public synchronized LoadResult load() {
        if (!Files.exists(path) && !Files.exists(temporaryPath)) {
            return new LoadResult(null, false, "");
        }
        IOException primaryFailure = null;
        if (Files.exists(path)) {
            try {
                Decoded decoded = read(path);
                if (decoded.migrated()) {
                    save(decoded.update());
                    return new LoadResult(
                            decoded.update(), false,
                            "Migrated cached protected-area schema 1 to typed area schema 2");
                }
                return new LoadResult(decoded.update(), false, "");
            } catch (IOException failure) {
                primaryFailure = failure;
            }
        }
        if (Files.exists(temporaryPath)) {
            try {
                Decoded decoded = read(temporaryPath);
                moveIntoPlace();
                if (decoded.migrated()) save(decoded.update());
                return new LoadResult(
                        decoded.update(), false,
                        "Recovered the cached area policy from its temporary file"
                                + (decoded.migrated()
                                ? " and migrated schema 1 to schema 2" : ""));
            } catch (IOException failure) {
                if (primaryFailure != null) failure.addSuppressed(primaryFailure);
                return failedClosed(failure);
            }
        }
        return failedClosed(primaryFailure == null
                ? new IOException("cached protected-area policy is unavailable")
                : primaryFailure);
    }

    public synchronized void save(ProtectedAreaPolicyProtocol.Update update) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] encoded = GSON.toJson(ProtectedAreaPolicyProtocol.encodeForStore(update))
                .getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) {
            throw new IOException("cached protected-area policy is too large");
        }
        Files.write(
                temporaryPath,
                encoded,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                StandardOpenOption.SYNC);
        moveIntoPlace();
    }

    private Decoded read(Path source) throws IOException {
        long size = Files.size(source);
        if (size <= 0L || size > MAX_BYTES) {
            throw new IOException("cached protected-area policy size is outside the bounded range");
        }
        try {
            JsonElement parsed = JsonParser.parseString(
                    Files.readString(source, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("root must be an object");
            JsonElement schema = parsed.getAsJsonObject().get("schemaVersion");
            boolean migrated = schema != null && schema.isJsonPrimitive()
                    && schema.getAsInt() == ProtectedAreaPolicy.LEGACY_SCHEMA_VERSION;
            return new Decoded(
                    ProtectedAreaPolicyProtocol.decode(parsed.getAsJsonObject()), migrated);
        } catch (RuntimeException error) {
            throw new IOException("Corrupt cached protected-area policy: " + source, error);
        }
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

    private static LoadResult failedClosed(IOException error) {
        return new LoadResult(
                null,
                true,
                "Cached protected-area policy is unreadable; Entity world mutations remain disabled: "
                        + Objects.requireNonNullElse(error.getMessage(), "unknown error"));
    }

    public record LoadResult(
            ProtectedAreaPolicyProtocol.Update update,
            boolean failClosed,
            String warning) {
        public LoadResult {
            warning = Objects.requireNonNullElse(warning, "");
        }
    }

    private record Decoded(ProtectedAreaPolicyProtocol.Update update, boolean migrated) {
    }
}
