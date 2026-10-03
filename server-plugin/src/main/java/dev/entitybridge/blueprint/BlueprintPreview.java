package dev.entitybridge.blueprint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/** A bounded, immutable copy of the exact design/site shown before owner authorization. */
public record BlueprintPreview(String projectId, String digest, String designHash, String worldId, String dimension,
                               int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                               List<Cell> cells) {
    public static final int MAX_CELLS = 32_768;
    public static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

    public BlueprintPreview {
        UUID.fromString(projectId);
        UUID.fromString(worldId);
        if (digest == null || !digest.matches("[a-f0-9]{64}")) invalid("digest");
        if (designHash == null || !designHash.matches("[a-f0-9]{64}")) invalid("design hash");
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || dimension.length() > 128) invalid("dimension");
        if (minX < -30_000_000 || maxX > 30_000_000 || minZ < -30_000_000 || maxZ > 30_000_000
                || minY < -2048 || maxY > 2048 || minX > maxX || minY > maxY || minZ > maxZ
                || (long) maxX - minX >= 128 || (long) maxY - minY >= 128 || (long) maxZ - minZ >= 128)
            invalid("bounds");
        cells = List.copyOf(cells);
        if (cells.isEmpty() || cells.size() > MAX_CELLS) invalid("cell count");
        HashSet<Position> positions = new HashSet<>();
        for (Cell cell : cells) {
            if (cell.x() < minX || cell.x() > maxX || cell.y() < minY || cell.y() > maxY
                    || cell.z() < minZ || cell.z() > maxZ || !positions.add(cell.position())) invalid("cell bounds/duplicate");
        }
        if (!digest.equals(computeDigest(worldId, projectId, designHash, dimension, cells))) invalid("content digest");
    }

    public static BlueprintPreview parse(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) return null;
        if (!raw.isJsonObject() || raw.toString().getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
            throw new IllegalArgumentException("Blueprint preview is malformed or too large");
        JsonObject json = raw.getAsJsonObject();
        JsonElement rawCells = json.get("cells");
        if (rawCells == null || !rawCells.isJsonArray() || rawCells.getAsJsonArray().size() > MAX_CELLS)
            throw new IllegalArgumentException("Blueprint preview needs bounded exact cells");
        List<Cell> cells = new ArrayList<>();
        for (JsonElement entry : rawCells.getAsJsonArray()) {
            if (!entry.isJsonObject()) throw new IllegalArgumentException("Invalid blueprint cell");
            JsonObject cell = entry.getAsJsonObject();
            cells.add(new Cell(integer(cell, "x"), integer(cell, "y"), integer(cell, "z"),
                    string(cell, "before"), string(cell, "state")));
        }
        return new BlueprintPreview(string(json, "projectId"), string(json, "digest"), string(json, "designHash"),
                string(json, "worldId"), string(json, "dimension"), integer(json, "minX"),
                integer(json, "minY"), integer(json, "minZ"), integer(json, "maxX"),
                integer(json, "maxY"), integer(json, "maxZ"), cells);
    }

    private static int integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Blueprint " + key + " must be an integer");
        try { return new BigDecimal(value.getAsString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Blueprint " + key + " must be an integer", invalid);
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Blueprint " + key + " must be text");
        return value.getAsString();
    }

    /** Yarn and Bukkit order properties differently; order never changes physical authority. */
    public static String normalizeState(String value) {
        if (value == null || value.length() > 512 || !value.matches(
                "minecraft:[a-z0-9_]+(?:\\[[a-z0-9_]+=[a-z0-9_]+(?:,[a-z0-9_]+=[a-z0-9_]+)*\\])?")) {
            throw new IllegalArgumentException("Invalid blueprint block state");
        }
        int bracket = value.indexOf('[');
        if (bracket < 0) return value;
        TreeMap<String, String> properties = new TreeMap<>();
        for (String property : value.substring(bracket + 1, value.length() - 1).split(",")) {
            String[] pair = property.split("=", 2);
            if (properties.put(pair[0], pair[1]) != null) invalid("duplicate block property");
        }
        return value.substring(0, bracket) + "[" + String.join(",", properties.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toList()) + "]";
    }

    private static void invalid(String part) { throw new IllegalArgumentException("Invalid blueprint " + part); }

    /** Wire-side counterpart of the narrow vanilla terrain/activity structural matcher. */
    public static boolean matchesState(String wanted,String observed) {
        String expected=normalizeState(wanted),actual=normalizeState(observed);
        return expected.equals(actual) || naturalSoil(expected)&&naturalSoil(actual)
                || sameFurnaceStructure(expected,actual);
    }
    private static boolean sameFurnaceStructure(String expected,String actual) {
        int properties=expected.indexOf('[');
        if(properties<0 || actual.indexOf('[')!=properties
                || !expected.substring(0,properties).equals(actual.substring(0,properties)))return false;
        String block=expected.substring(0,properties);
        if(!block.equals("minecraft:furnace") && !block.equals("minecraft:blast_furnace")
                && !block.equals("minecraft:smoker"))return false;
        // Canonical ordering preserves every other property, including unknown or missing ones.
        String heat="(?<=\\[|,)lit=(?:true|false)(?=,|\\])";
        return expected.replaceAll(heat,"lit=false").equals(actual.replaceAll(heat,"lit=false"));
    }
    private static boolean naturalSoil(String state) {
        return state.equals("minecraft:dirt") || state.equals("minecraft:grass_block")
                || state.equals("minecraft:grass_block[snowy=false]") || state.equals("minecraft:grass_block[snowy=true]");
    }

    public static String computeDigest(String worldId, String projectId, String designHash, String dimension, List<Cell> cells) {
        StringBuilder canonical = new StringBuilder("entity2-blueprint-v1\n").append(worldId).append('\n')
                .append(projectId).append('\n').append(designHash).append('\n').append(dimension).append('\n');
        cells.stream().sorted(java.util.Comparator.comparingInt(Cell::x).thenComparingInt(Cell::y).thenComparingInt(Cell::z))
                .forEach(cell -> canonical.append(cell.x()).append('\t').append(cell.y()).append('\t').append(cell.z())
                        .append('\t').append(cell.before()).append('\t').append(cell.state()).append('\n'));
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record Position(int x, int y, int z) { }
    public record Cell(int x, int y, int z, String before, String state) {
        public Cell {
            before = normalizeState(before);
            state = normalizeState(state);
        }
        public Position position() { return new Position(x, y, z); }
        public boolean matchesBeforeOrDesired(String observed) {
            String normalized = normalizeState(observed);
            return matchesState(before,normalized) || matchesState(state,normalized);
        }
        public String material() {
            // Vanilla's vertically attached torch item selects standing/wall
            // block state during use. This is only click preflight material;
            // matchesState still requires the exact final block and facing.
            return switch(state.split("\\[",2)[0]) {
                case "minecraft:wall_torch" -> "minecraft:torch";
                case "minecraft:soul_wall_torch" -> "minecraft:soul_torch";
                case "minecraft:redstone_wall_torch" -> "minecraft:redstone_torch";
                default -> state.split("\\[",2)[0];
            };
        }
    }
}
