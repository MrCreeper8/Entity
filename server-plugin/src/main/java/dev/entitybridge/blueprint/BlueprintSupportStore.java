package dev.entitybridge.blueprint;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded world-owned receipts, never a durable grant to mutate without a live mission. */
final class BlueprintSupportStore {
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final Gson GSON = new Gson();
    record Key(String worldId, String dimension, int x, int y, int z) { }
    record Receipt(Key key, String owner, String projectId, String digest, String state) {
        Receipt {
            Objects.requireNonNull(key);
            UUID.fromString(key.worldId());
            UUID.fromString(projectId);
            if (owner == null || owner.isBlank() || owner.length() > 64
                    || digest == null || !digest.matches("[a-f0-9]{64}")
                    || key.dimension() == null || !key.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || key.dimension().length() > 128
                    || Math.abs((long) key.x()) > 30_000_000 || Math.abs((long) key.z()) > 30_000_000
                    || key.y() < -2048 || key.y() > 2048 || !support(state))
                throw new IllegalArgumentException("Invalid blueprint support receipt");
            owner = owner.toLowerCase(Locale.ROOT);
        }
    }
    private final Path path;
    private final Path temporary;
    BlueprintSupportStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
        temporary = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }
    Map<Key, Receipt> load() throws IOException {
        if (!Files.exists(path) && !Files.exists(temporary)) return new HashMap<>();
        if (Files.exists(temporary)) {
            // A pending invalidation is newer than the primary receipt. Never
            // resurrect old ownership by preferring the old file after a crash.
            Map<Key, Receipt> recovered = read(temporary);
            moveIntoPlace();
            return recovered;
        }
        return read(path);
    }
    private Map<Key, Receipt> read(Path source) throws IOException {
        if (Files.size(source) <= 0 || Files.size(source) > MAX_BYTES)
            throw new IOException("Blueprint support receipt file size is invalid");
        try {
            var root = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Receipt schema");
            var entries = root.getAsJsonArray("receipts");
            if (entries.size() > BlueprintPreview.MAX_CELLS) throw new IllegalArgumentException("Receipt count");
            Map<Key, Receipt> result = new HashMap<>();
            for (var entry : entries) {
                Receipt receipt = GSON.fromJson(entry, Receipt.class);
                if (result.putIfAbsent(receipt.key(), receipt) != null)
                    throw new IllegalArgumentException("Duplicate receipt cell");
            }
            return result;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid blueprint support receipts", invalid);
        }
    }
    void save(Map<Key, Receipt> receipts) throws IOException {
        if (receipts.size() > BlueprintPreview.MAX_CELLS) throw new IOException("Too many support receipts");
        var root = new com.google.gson.JsonObject();
        root.addProperty("schema", 1);
        var ordered = receipts.values().stream().sorted(Comparator.comparing((Receipt r) -> r.key().worldId())
                .thenComparing(r -> r.key().dimension()).thenComparingInt(r -> r.key().x())
                .thenComparingInt(r -> r.key().y()).thenComparingInt(r -> r.key().z())).toList();
        root.add("receipts", GSON.toJsonTree(ordered));
        byte[] encoded = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) throw new IOException("Support receipts exceed file limit");
        Files.createDirectories(path.getParent());
        Files.write(temporary, encoded, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        moveIntoPlace();
    }
    private void moveIntoPlace() throws IOException {
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static boolean support(String state) {
        return "minecraft:dirt".equals(state) || "minecraft:cobblestone".equals(state);
    }
}
