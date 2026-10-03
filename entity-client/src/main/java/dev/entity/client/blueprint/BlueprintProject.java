package dev.entity.client.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable, world-bound consent snapshot. Partial work never changes its identity. */
public record BlueprintProject(String projectId, String worldId, String designId,
        String designHash, String dimension, int anchorX, int anchorY, int anchorZ,
        int rotation, String digest, List<Cell> cells) {
    public record Cell(int x, int y, int z, String before, String state) {
        public BlockPos pos() { return new BlockPos(x, y, z); }
    }
    public BlueprintProject {
        UUID.fromString(projectId);
        Objects.requireNonNull(worldId); Objects.requireNonNull(dimension);
        if (worldId.isBlank() || !designHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid blueprint identity");
        if (rotation < 0 || rotation > 3 || cells.isEmpty() || cells.size() > 32768)
            throw new IllegalArgumentException("Invalid blueprint geometry");
        cells = cells.stream().sorted(Comparator.comparingInt(Cell::x)
                .thenComparingInt(Cell::y).thenComparingInt(Cell::z)).toList();
        Set<BlockPos> seen = new HashSet<>();
        for (Cell cell : cells) {
            if (!seen.add(cell.pos())) throw new IllegalArgumentException("Duplicate blueprint cell");
            BlueprintDesign.parseState(cell.before());
            BlueprintDesign.parseState(cell.state());
        }
        String actual = digest(worldId, projectId, designHash, dimension, cells);
        if (digest == null || digest.isBlank()) digest = actual;
        if (!actual.equals(digest)) throw new IllegalArgumentException("Blueprint snapshot hash mismatch");
    }
    public BlockPos origin() {
        return new BlockPos(cells.stream().mapToInt(Cell::x).min().orElseThrow(),
                cells.stream().mapToInt(Cell::y).min().orElseThrow(),
                cells.stream().mapToInt(Cell::z).min().orElseThrow());
    }
    public BlockPos anchor() { return new BlockPos(anchorX,anchorY,anchorZ); }
    public BlockPos maximum() {
        return new BlockPos(cells.stream().mapToInt(Cell::x).max().orElseThrow(),
                cells.stream().mapToInt(Cell::y).max().orElseThrow(),
                cells.stream().mapToInt(Cell::z).max().orElseThrow());
    }
    public Map<BlockPos, BlockState> desired() {
        Map<BlockPos,BlockState> result = new LinkedHashMap<>();
        cells.forEach(cell -> result.put(cell.pos(), BlueprintDesign.parseState(cell.state())));
        return Map.copyOf(result);
    }
    public JsonObject preview() {
        JsonObject result = new JsonObject();
        result.addProperty("projectId", projectId); result.addProperty("worldId", worldId);
        result.addProperty("designHash", designHash); result.addProperty("digest", digest);
        result.addProperty("dimension", dimension);
        BlockPos min = origin(), max = maximum();
        result.addProperty("minX", min.getX()); result.addProperty("minY", min.getY());
        result.addProperty("minZ", min.getZ()); result.addProperty("maxX", max.getX());
        result.addProperty("maxY", max.getY()); result.addProperty("maxZ", max.getZ());
        JsonArray array = new JsonArray();
        for (Cell cell : cells) {
            JsonObject item = new JsonObject(); item.addProperty("x", cell.x());
            item.addProperty("y", cell.y()); item.addProperty("z", cell.z());
            item.addProperty("before", cell.before()); item.addProperty("state", cell.state());
            array.add(item);
        }
        result.add("cells", array);
        return result;
    }
    private static String digest(String world, String project, String design, String dimension,
            List<Cell> cells) {
        StringBuilder value = new StringBuilder("entity2-blueprint-v1\n")
                .append(world).append('\n').append(project).append('\n')
                .append(design).append('\n').append(dimension).append('\n');
        for (Cell c : cells) value.append(c.x()).append('\t').append(c.y()).append('\t')
                .append(c.z()).append('\t').append(c.before()).append('\t').append(c.state()).append('\n');
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
