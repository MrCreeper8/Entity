package dev.entity.client.blueprint;

import baritone.api.schematic.ISchematic;
import baritone.api.schematic.RotatedSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable relative cells: absent cells are outside the project, explicit air is clearing. */
public final class BlueprintDesign {
    public static final int MAX_SIDE = 128;
    public static final int MAX_CELLS = 32_768;
    private final String id;
    private final String name;
    private final String source;
    private final String sha256;
    private final int width;
    private final int height;
    private final int length;
    private final Map<BlockPos, BlockState> cells;
    private final ISchematic schematic;

    public BlueprintDesign(String id, String name, String source, int width, int height,
            int length, Map<BlockPos, BlockState> cells) {
        validateDimensions(width, height, length);
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.source = Objects.requireNonNull(source);
        this.width = width;
        this.height = height;
        this.length = length;
        if (cells.isEmpty() || cells.size() > MAX_CELLS) {
            throw new IllegalArgumentException("Blueprint must contain 1.." + MAX_CELLS + " cells");
        }
        LinkedHashMap<BlockPos, BlockState> stable = new LinkedHashMap<>();
        cells.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator
                .comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getX))).forEach(entry -> {
                    BlockPos pos = entry.getKey().toImmutable();
                    if (pos.getX() < 0 || pos.getX() >= width || pos.getY() < 0
                            || pos.getY() >= height || pos.getZ() < 0 || pos.getZ() >= length) {
                        throw new IllegalArgumentException("Blueprint cell is outside its bounds: " + pos);
                    }
                    stable.put(pos, Objects.requireNonNull(entry.getValue()));
                });
        this.cells = Collections.unmodifiableMap(stable);
        this.schematic = new ISchematic() {
            @Override public int widthX() { return width; }
            @Override public int heightY() { return height; }
            @Override public int lengthZ() { return length; }
            @Override public boolean inSchematic(int x, int y, int z, BlockState current) {
                return BlueprintDesign.this.cells.containsKey(new BlockPos(x, y, z));
            }
            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                    List<BlockState> placeable) {
                return BlueprintDesign.this.cells.get(new BlockPos(x, y, z));
            }
        };
        this.sha256 = digest(canonicalText());
    }

    public String id() { return id; }
    public String name() { return name; }
    public String source() { return source; }
    public String sha256() { return sha256; }
    public int width() { return width; }
    public int height() { return height; }
    public int length() { return length; }
    public Map<BlockPos, BlockState> cells() { return cells; }
    public ISchematic schematic() { return schematic; }

    /** Rotation is delegated to Baritone, including block-state orientation and sparse masks. */
    public BlueprintDesign rotated(int clockwiseQuarterTurns) {
        int turns = Math.floorMod(clockwiseQuarterTurns, 4);
        if (turns == 0) return this;
        BlockRotation rotation = switch (turns) {
            case 1 -> BlockRotation.CLOCKWISE_90;
            case 2 -> BlockRotation.CLOCKWISE_180;
            default -> BlockRotation.COUNTERCLOCKWISE_90;
        };
        ISchematic rotated = new RotatedSchematic(schematic, rotation);
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (int y = 0; y < rotated.heightY(); y++) {
            for (int z = 0; z < rotated.lengthZ(); z++) {
                for (int x = 0; x < rotated.widthX(); x++) {
                    if (rotated.inSchematic(x, y, z, Blocks.AIR.getDefaultState())) {
                        result.put(new BlockPos(x, y, z), rotated.desiredState(x, y, z,
                                Blocks.AIR.getDefaultState(), List.of()));
                    }
                }
            }
        }
        return new BlueprintDesign(id, name, source, rotated.widthX(), rotated.heightY(),
                rotated.lengthZ(), result);
    }

    /** Total construction items, not missing inventory. Multipart blocks consume one item. */
    public Map<String, Integer> materials() {
        TreeMap<String, Integer> totals = new TreeMap<>();
        for (BlockState state : cells.values()) {
            if (state.isAir()) continue;
            if (state.getBlock() instanceof DoorBlock
                    && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) continue;
            if (state.getBlock() instanceof BedBlock && state.get(BedBlock.PART) == BedPart.HEAD) continue;
            if (state.getBlock() instanceof TallPlantBlock && state.get(TallPlantBlock.HALF) == DoubleBlockHalf.UPPER) continue;
            var item = state.getBlock().asItem();
            if (state.isOf(Blocks.WALL_TORCH)) item = Items.TORCH;
            if (state.isOf(Blocks.SOUL_WALL_TORCH)) item = Items.SOUL_TORCH;
            if (item == Items.AIR) {
                throw new IllegalStateException("No supported placement item for " + state);
            }
            int count = state.getBlock() instanceof SlabBlock
                    && state.get(SlabBlock.TYPE) == SlabType.DOUBLE ? 2 : 1;
            totals.merge(Registries.ITEM.getId(item).toString(), count, Integer::sum);
        }
        return Collections.unmodifiableMap(totals);
    }

    static void validateDimensions(int width, int height, int length) {
        if (width < 1 || height < 1 || length < 1 || width > MAX_SIDE || height > MAX_SIDE
                || length > MAX_SIDE || (long) width * height * length > MAX_CELLS) {
            throw new IllegalArgumentException("Blueprint bounds exceed " + MAX_SIDE
                    + " blocks per side or " + MAX_CELLS + " cells");
        }
    }

    public static String stateString(BlockState state) {
        StringBuilder key = new StringBuilder(Registries.BLOCK.getId(state.getBlock()).toString());
        TreeMap<String, String> properties = new TreeMap<>();
        state.getEntries().forEach((property, value) -> properties.put(property.getName(), propertyValue(state, property)));
        if (!properties.isEmpty()) {
            key.append('[');
            properties.forEach((property, value) -> key.append(property).append('=').append(value).append(','));
            key.setCharAt(key.length() - 1, ']');
        }
        return key.toString();
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        // Enum.toString() is not the serialized Minecraft value (e.g. ChestType.RIGHT).
        return property.name(state.get(property));
    }

    /** Strict state decoding: unknown blocks/properties never silently turn into air. */
    public static BlockState parseState(String text) {
        if (text == null || text.length() > 1024) throw new IllegalArgumentException("Invalid block state");
        int bracket = text.indexOf('[');
        String blockName = bracket < 0 ? text : text.substring(0, bracket);
        Identifier identifier = Identifier.tryParse(blockName);
        if (identifier == null || !Registries.BLOCK.containsId(identifier)) {
            throw new IllegalArgumentException("Unknown block: " + blockName);
        }
        BlockState state = Registries.BLOCK.get(identifier).getDefaultState();
        if (bracket < 0) return state;
        if (!text.endsWith("]") || text.indexOf('[', bracket + 1) >= 0) {
            throw new IllegalArgumentException("Invalid block state: " + text);
        }
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        String properties = text.substring(bracket + 1, text.length() - 1);
        if (properties.isEmpty()) return state;
        for (String pair : properties.split(",", -1)) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2 || !seen.add(parts[0])) {
                throw new IllegalArgumentException("Invalid or duplicate block property: " + pair);
            }
            Property<?> property = state.getBlock().getStateManager().getProperty(parts[0]);
            if (property == null) throw new IllegalArgumentException("Unknown property: " + pair);
            state = applyProperty(state, property, parts[1]);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state,
            Property<T> property, String value) {
        T parsed = property.parse(value).orElseThrow(() ->
                new IllegalArgumentException("Invalid value for " + property.getName() + ": " + value));
        return state.with(property, parsed);
    }

    private String canonicalText() {
        StringBuilder canonical = new StringBuilder("entity2-blueprint-v1\n")
                .append(width).append(',').append(height).append(',').append(length).append('\n');
        cells.forEach((pos, state) -> canonical.append(pos.getX()).append(',').append(pos.getY())
                .append(',').append(pos.getZ()).append(':').append(stateString(state)).append('\n'));
        return canonical.toString();
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
