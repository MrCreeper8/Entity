package dev.entity.client.autonomy.policy;

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
import java.util.UUID;

/** Remembers only physically owned, ordinarily craftable working tiers. Never authorizes a job. */
public final class IdleStockToolStore {
    private final Path path;
    private final String worldId;
    private Map<String, String> tools = Map.of();
    private long revision;
    private String problem = "";
    private record Saved(long revision, Map<String, String> tools) { }
    public IdleStockToolStore(Path directory, String worldId) {
        this.worldId = worldId;
        this.path = worldId == null || worldId.isBlank() ? null : directory.resolve("idle-working-tools.json");
        if (path == null) { problem = "Working-tool memory awaits authenticated world binding"; return; }
        try {
            UUID.fromString(worldId);
            Saved saved = AtomicStoreRecovery.load(path, temporary(), "idle working tools", () -> new Saved(0, Map.of()), this::decode);
            tools = saved.tools(); revision = saved.revision();
        } catch (IOException | RuntimeException error) { problem = "Working-tool memory is unreadable: " + error.getMessage(); }
    }
    public Map<String, String> tools() { return tools; }
    public String problem() { return problem; }
    private Path temporary() { return path.resolveSibling(path.getFileName() + ".tmp"); }

    public void observe(Map<String, Integer> actualCarried) throws IOException {
        if (path == null || !problem.isBlank()) return;
        Map<String, String> next = new LinkedHashMap<>(tools);
        actualCarried.forEach((raw, count) -> {
            String item = raw.startsWith("minecraft:") ? raw.substring(10) : raw;
            if (count <= 0 || !(item.startsWith("stone_") || item.startsWith("iron_") || item.startsWith("diamond_"))) return;
            String category = item.endsWith("_pickaxe") ? "pickaxe" : item.endsWith("_axe") ? "axe" : item.endsWith("_sword") ? "weapon" : "";
            if (!category.isEmpty() && IdleStockPolicy.toolRank(item) > IdleStockPolicy.toolRank(next.getOrDefault(category, ""))) next.put(category, item);
        });
        if (next.equals(tools)) return;
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("schema", 1); encoded.put("worldId", worldId); encoded.put("revision", revision + 1); encoded.put("tools", next);
        try {
            Files.createDirectories(path.getParent()); Files.writeString(temporary(), SimpleJson.stringify(encoded), StandardCharsets.UTF_8);
            try (var file = FileChannel.open(temporary(), StandardOpenOption.WRITE)) { file.force(true); }
            try { Files.move(temporary(), path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary(), path, StandardCopyOption.REPLACE_EXISTING); }
            tools = Map.copyOf(next); revision++;
        } catch (IOException error) { problem = "Working-tool memory could not be saved: " + error.getMessage(); throw error; }
    }
    private AtomicStoreRecovery.Decoded<Saved> decode(byte[] bytes, Path source) throws IOException {
        try {
            if (bytes.length > 4096 || !(SimpleJson.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof Map<?, ?> root))
                throw new IllegalArgumentException("Invalid working-tool object");
            if (!(root.get("schema") instanceof Number schema) || schema.doubleValue() != 1)
                throw new AtomicStoreRecovery.UnsupportedSchemaException("idle working tools", 0);
            if (!worldId.equals(root.get("worldId")) || !(root.get("tools") instanceof Map<?, ?> rows)
                    || !(root.get("revision") instanceof Number version) || version.longValue() < 0
                    || version.doubleValue() != (double) version.longValue())
                throw new IllegalArgumentException("Invalid working-tool scope");
            Map<String, String> restored = new LinkedHashMap<>();
            for (var entry : rows.entrySet()) {
                if (!(entry.getKey() instanceof String category) || !(entry.getValue() instanceof String item)
                        || !java.util.Set.of("pickaxe", "axe", "weapon").contains(category)
                        || !IdleStockPolicy.accepted(category, Map.of()).contains(item) || item.startsWith("netherite_"))
                    throw new IllegalArgumentException("Invalid working-tool tier");
                restored.put(category, item);
            }
            return new AtomicStoreRecovery.Decoded<>(new Saved(version.longValue(), Map.copyOf(restored)), version.longValue(), 1);
        } catch (RuntimeException error) { throw new IOException("Invalid idle working tools: " + source, error); }
    }
}
