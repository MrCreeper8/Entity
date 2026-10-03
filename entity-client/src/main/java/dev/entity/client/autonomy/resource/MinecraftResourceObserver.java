package dev.entity.client.autonomy.resource;

import dev.entity.core.stewardship.ResourceStewardshipPolicy;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Exact loaded-client adapter for {@link LoadedResourceClassifier}.
 *
 * <p>The adapter never loads chunks, searches through hidden world state, or
 * infers the route-safety facts owned by the caller. Any absent chunk, build
 * height cell, thread mismatch, or world transition becomes an unknown sample
 * and therefore a closed final policy gate.</p>
 */
public final class MinecraftResourceObserver {
    /** Source discovery shares the exact tree families supported by classification. */
    public static Set<String> supportedNaturalLogIds() {
        return LOG_FAMILIES.keySet().stream().map(block -> Registries.BLOCK.getId(block).getPath())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static final Map<Block, TreeFamily> LOG_FAMILIES = Map.ofEntries(
            entry(Blocks.OAK_LOG, new TreeFamily("oak", "minecraft:oak_sapling")),
            entry(Blocks.SPRUCE_LOG, new TreeFamily("spruce", "minecraft:spruce_sapling")),
            entry(Blocks.BIRCH_LOG, new TreeFamily("birch", "minecraft:birch_sapling")),
            entry(Blocks.JUNGLE_LOG, new TreeFamily("jungle", "minecraft:jungle_sapling")),
            entry(Blocks.ACACIA_LOG, new TreeFamily("acacia", "minecraft:acacia_sapling")),
            entry(Blocks.DARK_OAK_LOG, new TreeFamily("dark_oak", "minecraft:dark_oak_sapling")),
            entry(Blocks.MANGROVE_LOG, new TreeFamily("mangrove", "minecraft:mangrove_propagule")),
            entry(Blocks.CHERRY_LOG, new TreeFamily("cherry", "minecraft:cherry_sapling")),
            entry(Blocks.PALE_OAK_LOG, new TreeFamily("pale_oak", "minecraft:pale_oak_sapling")));

    private static final Map<Block, String> LEAF_FAMILIES = Map.ofEntries(
            entry(Blocks.OAK_LEAVES, "oak"),
            entry(Blocks.AZALEA_LEAVES, "oak"),
            entry(Blocks.FLOWERING_AZALEA_LEAVES, "oak"),
            entry(Blocks.SPRUCE_LEAVES, "spruce"),
            entry(Blocks.BIRCH_LEAVES, "birch"),
            entry(Blocks.JUNGLE_LEAVES, "jungle"),
            entry(Blocks.ACACIA_LEAVES, "acacia"),
            entry(Blocks.DARK_OAK_LEAVES, "dark_oak"),
            entry(Blocks.MANGROVE_LEAVES, "mangrove"),
            entry(Blocks.CHERRY_LEAVES, "cherry"),
            entry(Blocks.PALE_OAK_LEAVES, "pale_oak"));

    private static final Set<Block> WORKSTATIONS = Set.of(
            Blocks.CRAFTING_TABLE,
            Blocks.FURNACE,
            Blocks.BLAST_FURNACE,
            Blocks.SMOKER,
            Blocks.CARTOGRAPHY_TABLE,
            Blocks.FLETCHING_TABLE,
            Blocks.SMITHING_TABLE,
            Blocks.STONECUTTER,
            Blocks.LOOM,
            Blocks.GRINDSTONE,
            Blocks.ANVIL,
            Blocks.CHIPPED_ANVIL,
            Blocks.DAMAGED_ANVIL,
            Blocks.ENCHANTING_TABLE,
            Blocks.BREWING_STAND,
            Blocks.LECTERN,
            Blocks.COMPOSTER,
            Blocks.CAULDRON,
            Blocks.WATER_CAULDRON,
            Blocks.LAVA_CAULDRON,
            Blocks.POWDER_SNOW_CAULDRON);

    private static final Set<Block> REDSTONE_COMPONENTS = Set.of(
            Blocks.REDSTONE_WIRE,
            Blocks.REDSTONE_TORCH,
            Blocks.REDSTONE_WALL_TORCH,
            Blocks.REPEATER,
            Blocks.COMPARATOR,
            Blocks.LEVER,
            Blocks.TRIPWIRE,
            Blocks.TRIPWIRE_HOOK,
            Blocks.DAYLIGHT_DETECTOR,
            Blocks.OBSERVER,
            Blocks.PISTON,
            Blocks.STICKY_PISTON,
            Blocks.REDSTONE_BLOCK,
            Blocks.TARGET,
            Blocks.SCULK_SENSOR,
            Blocks.CALIBRATED_SCULK_SENSOR,
            Blocks.LIGHTNING_ROD,
            Blocks.CRAFTER);

    private static final Set<Block> CONSTRUCTION_BLOCKS = Set.of(
            Blocks.GLASS,
            Blocks.TINTED_GLASS,
            Blocks.TORCH,
            Blocks.WALL_TORCH,
            Blocks.SOUL_TORCH,
            Blocks.SOUL_WALL_TORCH,
            Blocks.LANTERN,
            Blocks.SOUL_LANTERN,
            Blocks.LADDER,
            Blocks.SCAFFOLDING,
            Blocks.BOOKSHELF,
            Blocks.CHISELED_BOOKSHELF,
            Blocks.END_ROD,
            Blocks.CHAIN,
            Blocks.IRON_BARS,
            Blocks.BRICKS,
            Blocks.MUD_BRICKS,
            Blocks.NETHER_BRICKS,
            Blocks.QUARTZ_BRICKS,
            Blocks.SEA_LANTERN,
            Blocks.GLOWSTONE);

    private static final Set<Block> NATURAL_MINE_BLOCKS = Set.of(
            Blocks.TUFF,
            Blocks.CALCITE,
            Blocks.DRIPSTONE_BLOCK,
            Blocks.GRAVEL,
            Blocks.SAND,
            Blocks.RED_SAND,
            Blocks.CLAY,
            Blocks.NETHERRACK,
            Blocks.SOUL_SAND,
            Blocks.SOUL_SOIL,
            Blocks.BASALT,
            Blocks.SMOOTH_BASALT,
            Blocks.BLACKSTONE,
            Blocks.END_STONE,
            Blocks.OBSIDIAN);

    private final MinecraftClient client;

    public MinecraftResourceObserver(MinecraftClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Must be called on the Minecraft client thread immediately before use. */
    public LoadedResourceClassifier.Observation observe(
            BlockPos target,
            ResourceStewardshipPolicy.Intent intent) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(intent, "intent");
        LoadedResourceClassifier.Point point = point(target);
        if (!client.isOnThread()) {
            return LoadedResourceClassifier.Observation.unavailable(
                    intent, point, 0,
                    "resource geometry must be observed on the Minecraft client thread");
        }
        ClientWorld world = client.world;
        if (world == null) {
            return LoadedResourceClassifier.Observation.unavailable(
                    intent, point, 0, "client has not joined a world");
        }
        String dimension = dimension(world);
        LoadedResourceClassifier.Observation observed =
                LoadedResourceClassifier.observe(
                        intent, point,
                        candidate -> sample(world, dimension, candidate));
        if (client.world != world || !dimension.equals(dimension(world))) {
            return LoadedResourceClassifier.Observation.unavailable(
                    intent, point, observed.sampledCells(),
                    "loaded world changed while resource geometry was observed");
        }
        return observed;
    }

    /**
     * Supplies the complete core gate facts. Area/route/body facts are passed
     * through from their authoritative owners and are never inferred here.
     */
    public ResourceStewardshipPolicy.Facts observeFacts(
            BlockPos target,
            ResourceStewardshipPolicy.Intent intent,
            LoadedResourceClassifier.AdmissionContext context) {
        return observe(target, intent).facts(
                Objects.requireNonNull(context, "context"));
    }

    private LoadedResourceClassifier.Cell sample(
            ClientWorld expectedWorld,
            String expectedDimension,
            LoadedResourceClassifier.Point point) {
        if (client.world != expectedWorld
                || !expectedDimension.equals(dimension(expectedWorld))) {
            return LoadedResourceClassifier.Cell.unknown();
        }
        BlockPos position = block(point);
        if (!withinBuildHeight(expectedWorld, position)
                || !expectedWorld.getChunkManager().isChunkLoaded(
                position.getX() >> 4, position.getZ() >> 4)) {
            return LoadedResourceClassifier.Cell.unknown();
        }
        return classifyLoadedState(expectedWorld.getBlockState(position));
    }

    static LoadedResourceClassifier.Cell classifyLoadedState(BlockState state) {
        Objects.requireNonNull(state, "state");
        Block block = state.getBlock();
        if (state.isAir()) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.AIR);
        }

        TreeFamily logFamily = LOG_FAMILIES.get(block);
        if (logFamily != null) {
            return LoadedResourceClassifier.Cell.log(
                    logFamily.name(), logFamily.saplingItem());
        }
        String leafFamily = LEAF_FAMILIES.get(block);
        if (leafFamily != null) {
            boolean persistent = state.contains(LeavesBlock.PERSISTENT)
                    && state.get(LeavesBlock.PERSISTENT);
            return persistent
                    ? LoadedResourceClassifier.Cell.persistentLeaf(leafFamily)
                    : LoadedResourceClassifier.Cell.naturalLeaf(leafFamily);
        }

        LoadedResourceClassifier.Cell crop = supportedCrop(state);
        if (crop != null) return crop;
        LoadedResourceClassifier.Cell flower = supportedFlower(state);
        if (SupportedFlowerHarvest.isFlower(flower.category())) return flower;
        if (isOre(state)) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.ORE);
        }
        if (WORKSTATIONS.contains(block)) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.WORKSTATION);
        }
        if (state.isIn(BlockTags.DOORS)
                || state.isIn(BlockTags.TRAPDOORS)
                || state.isIn(BlockTags.FENCE_GATES)
                || state.isIn(BlockTags.PORTALS)) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.DOOR);
        }
        if (block instanceof AbstractRailBlock || state.isIn(BlockTags.RAILS)) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.RAIL);
        }
        if (REDSTONE_COMPONENTS.contains(block)
                || state.isIn(BlockTags.BUTTONS)
                || state.isIn(BlockTags.PRESSURE_PLATES)) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.REDSTONE);
        }
        if (isConstruction(state)) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.CONSTRUCTION);
        }
        // Any remaining stateful block is preserved as a container/property
        // boundary. This safely includes chests, barrels, hoppers, shulkers,
        // decorated pots, spawners, signs, banners, and future stateful blocks.
        if (state.hasBlockEntity()) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.CONTAINER);
        }
        if (block == Blocks.FARMLAND) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.CROP_SUPPORT);
        }
        if (block == Blocks.MANGROVE_ROOTS
                || block == Blocks.MUDDY_MANGROVE_ROOTS) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.NATURAL_TREE_ROOT);
        }
        if (state.isIn(BlockTags.DIRT)
                || block == Blocks.MUD
                || block == Blocks.MOSS_BLOCK
                || block == Blocks.PALE_MOSS_BLOCK) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.NATURAL_GROUND);
        }
        if (state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.BASE_STONE_NETHER)
                || NATURAL_MINE_BLOCKS.contains(block)) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.NATURAL_MINE);
        }
        if (!state.getFluidState().isEmpty()) {
            return LoadedResourceClassifier.Cell.of(
                    LoadedResourceClassifier.Category.FLUID);
        }
        return LoadedResourceClassifier.Cell.of(
                LoadedResourceClassifier.Category.OTHER);
    }

    public static LoadedResourceClassifier.Cell supportedFlower(BlockState state) {
        return SupportedFlowerHarvest.classify(Registries.BLOCK.getId(state.getBlock()).toString(),
                state.contains(Properties.DOUBLE_BLOCK_HALF)
                        && state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER);
    }

    private static LoadedResourceClassifier.Cell supportedCrop(BlockState state) {
        Block block = state.getBlock();
        if (!(block instanceof CropBlock crop)) return null;
        boolean mature = crop.isMature(state);
        if (block == Blocks.WHEAT) {
            return LoadedResourceClassifier.Cell.crop(
                    "wheat", mature, "minecraft:wheat_seeds");
        }
        if (block == Blocks.CARROTS) {
            return LoadedResourceClassifier.Cell.crop(
                    "carrot", mature, "minecraft:carrot");
        }
        if (block == Blocks.POTATOES) {
            return LoadedResourceClassifier.Cell.crop(
                    "potato", mature, "minecraft:potato");
        }
        if (block == Blocks.BEETROOTS) {
            return LoadedResourceClassifier.Cell.crop(
                    "beetroot", mature, "minecraft:beetroot_seeds");
        }
        return null;
    }

    private static boolean isOre(BlockState state) {
        return state.isIn(BlockTags.COAL_ORES)
                || state.isIn(BlockTags.IRON_ORES)
                || state.isIn(BlockTags.COPPER_ORES)
                || state.isIn(BlockTags.GOLD_ORES)
                || state.isIn(BlockTags.REDSTONE_ORES)
                || state.isIn(BlockTags.LAPIS_ORES)
                || state.isIn(BlockTags.DIAMOND_ORES)
                || state.isIn(BlockTags.EMERALD_ORES)
                || state.isOf(Blocks.NETHER_QUARTZ_ORE)
                || state.isOf(Blocks.ANCIENT_DEBRIS);
    }

    private static boolean isConstruction(BlockState state) {
        if (CONSTRUCTION_BLOCKS.contains(state.getBlock())
                || state.isIn(BlockTags.PLANKS)
                || state.isIn(BlockTags.STONE_BRICKS)
                || state.isIn(BlockTags.SLABS)
                || state.isIn(BlockTags.STAIRS)
                || state.isIn(BlockTags.WALLS)
                || state.isIn(BlockTags.FENCES)
                || state.isIn(BlockTags.BEDS)
                || state.isIn(BlockTags.WOOL)
                || state.isIn(BlockTags.WOOL_CARPETS)
                || state.isIn(BlockTags.ALL_SIGNS)
                || state.isIn(BlockTags.BANNERS)
                || state.isIn(BlockTags.FLOWER_POTS)
                || state.isIn(BlockTags.CANDLES)) {
            return true;
        }
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        return path.equals("glass_pane")
                || path.endsWith("_stained_glass")
                || path.endsWith("_stained_glass_pane")
                || path.endsWith("_concrete")
                || path.endsWith("_concrete_powder")
                || path.endsWith("_bricks")
                || path.endsWith("_tiles");
    }

    private static boolean withinBuildHeight(ClientWorld world, BlockPos position) {
        return position.getY() >= world.getBottomY()
                && position.getY() <= world.getTopYInclusive();
    }

    private static String dimension(ClientWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    private static LoadedResourceClassifier.Point point(BlockPos position) {
        return new LoadedResourceClassifier.Point(
                position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos block(LoadedResourceClassifier.Point point) {
        return new BlockPos(point.x(), point.y(), point.z());
    }

    private record TreeFamily(String name, String saplingItem) {
        private TreeFamily {
            name = Objects.requireNonNull(name, "name");
            saplingItem = Objects.requireNonNull(saplingItem, "saplingItem");
        }
    }
}
