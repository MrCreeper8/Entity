package dev.entity.client.autonomy.workspace;

import dev.entity.client.autonomy.policy.WorkspaceWorldProbe;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Set;

/** Conservative loaded-world adapter for the pure workspace planner. */
public final class MinecraftWorkspaceProbe implements WorkspaceWorldProbe {
    private static final int HASH_RADIUS = 4;
    private static final int HASH_DOWN = 2;
    private static final int HASH_UP = 4;
    private static final float MAX_AUTOMATIC_CLEAR_HARDNESS = 12.0F;
    private static final Set<Block> HAZARDS = Set.of(
            Blocks.FIRE,
            Blocks.SOUL_FIRE,
            Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE,
            Blocks.MAGMA_BLOCK,
            Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.POWDER_SNOW,
            Blocks.WITHER_ROSE);
    private static final Set<Block> DISPOSABLE_REPLACEABLES = Set.of(
            Blocks.SHORT_GRASS,
            Blocks.TALL_GRASS,
            Blocks.FERN,
            Blocks.LARGE_FERN,
            Blocks.DEAD_BUSH,
            Blocks.SNOW);

    private final MinecraftClient client;
    private final BlockPos revisionOrigin;
    private final Set<BlockPos> serverRejectedBreaks;

    public MinecraftWorkspaceProbe(MinecraftClient client, BlockPos revisionOrigin) {
        this(client, revisionOrigin, Set.of());
    }

    public MinecraftWorkspaceProbe(
            MinecraftClient client,
            BlockPos revisionOrigin,
            Set<BlockPos> serverRejectedBreaks) {
        this.client = Objects.requireNonNull(client, "client");
        this.revisionOrigin = Objects.requireNonNull(revisionOrigin, "revisionOrigin").toImmutable();
        this.serverRejectedBreaks = Set.copyOf(
                Objects.requireNonNull(serverRejectedBreaks, "serverRejectedBreaks"));
    }

    @Override
    public long geometryRevision() {
        return geometryRevisionIgnoring(Set.of());
    }

    /** Hashes the local proof volume while normalizing explicitly expected changes. */
    public long geometryRevisionIgnoring(Set<Point> ignored) {
        Objects.requireNonNull(ignored, "ignored");
        if (client.world == null) return 0L;
        long hash = 0xcbf29ce484222325L;
        boolean normalizeExpectedCells = !ignored.isEmpty();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int y = -HASH_DOWN; y <= HASH_UP; y++) {
            for (int x = -HASH_RADIUS; x <= HASH_RADIUS; x++) {
                for (int z = -HASH_RADIUS; z <= HASH_RADIUS; z++) {
                    cursor.set(
                            revisionOrigin.getX() + x,
                            revisionOrigin.getY() + y,
                            revisionOrigin.getZ() + z);
                    boolean normalized = normalizeExpectedCells && ignored.contains(
                            new Point(cursor.getX(), cursor.getY(), cursor.getZ()));
                    int stateId;
                    if (normalized) {
                        stateId = Integer.MIN_VALUE;
                    } else if (client.world.isChunkLoaded(cursor.getX() >> 4, cursor.getZ() >> 4)) {
                        stateId = Block.getRawIdFromState(client.world.getBlockState(cursor));
                    } else {
                        stateId = -1;
                    }
                    hash ^= stateId;
                    hash *= 0x100000001b3L;
                }
            }
        }
        return hash;
    }

    @Override
    public Cell cell(Point point) {
        if (client.world == null) return Cell.UNLOADED;
        BlockPos pos = block(point);
        if (!client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return Cell.UNLOADED;
        if (serverRejectedBreaks.contains(pos)) return Cell.PROTECTED;
        BlockState state = client.world.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return Cell.FLUID;
        if (state.isAir()) return Cell.OPEN;
        if (HAZARDS.contains(state.getBlock())) return Cell.HAZARD;
        if (state.getBlock() instanceof FallingBlock) return Cell.FALLING;
        // Never excavate containers, spawners, or other stateful/player-owned blocks as
        // an incidental way to make room for a crafting operation.
        if (state.hasBlockEntity()) return Cell.PROTECTED;
        if (state.isReplaceable()) {
            return DISPOSABLE_REPLACEABLES.contains(state.getBlock())
                    ? Cell.REPLACEABLE
                    : Cell.PROTECTED;
        }
        float hardness = state.getHardness(client.world, pos);
        if (hardness < 0.0F || hardness > MAX_AUTOMATIC_CLEAR_HARDNESS) return Cell.UNBREAKABLE;
        return isDisposableNaturalTerrain(state) ? Cell.SOLID : Cell.PROTECTED;
    }

    @Override
    public boolean hasFullTopSupport(Point point) {
        if (client.world == null) return false;
        BlockPos pos = block(point);
        if (!client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        Cell cell = cell(point);
        if (cell != Cell.SOLID
                && cell != Cell.UNBREAKABLE
                && cell != Cell.PROTECTED
                && cell != Cell.FALLING) return false;
        return hasStableFullTopSupport(client, pos);
    }

    /**
     * Shared live-world proof for a floor which can safely support Entity.
     * Falling blocks are accepted only when their complete column is anchored;
     * callers must still reject hazards and falling blocks in body/overhead
     * cells rather than treating them as excavation candidates.
     */
    public static boolean hasStableFullTopSupport(MinecraftClient client, BlockPos pos) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(pos, "pos");
        if (client.world == null
                || !client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        BlockState state = client.world.getBlockState(pos);
        if (!state.isSideSolidFullSquare(client.world, pos, Direction.UP)) return false;
        return !(state.getBlock() instanceof FallingBlock)
                || hasAnchoredFallingColumn(client, pos);
    }

    /**
     * A falling block is stable only when every falling block below it ends on
     * a block the vanilla falling physics cannot pass through. Checking the
     * complete column prevents a sand/gravel stack suspended over air from
     * being mistaken for a safe floor merely because its immediate neighbour
     * is another falling block.
     */
    private static boolean hasAnchoredFallingColumn(MinecraftClient client, BlockPos top) {
        BlockPos cursor = top;
        int bottomY = client.world.getBottomY();
        while (cursor.getY() > bottomY) {
            cursor = cursor.down();
            if (!client.world.isChunkLoaded(cursor.getX() >> 4, cursor.getZ() >> 4)) return false;
            BlockState below = client.world.getBlockState(cursor);
            if (below.getBlock() instanceof FallingBlock) {
                if (!below.isSideSolidFullSquare(client.world, cursor, Direction.UP)) return false;
                continue;
            }
            return !FallingBlock.canFallThrough(below);
        }
        return false;
    }

    @Override
    public boolean hasStandCollisionClearance(Point feet, Set<Point> assumedOpen) {
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(assumedOpen, "assumedOpen");
        return collisionClear(feet, assumedOpen) && collisionClear(feet.up(), assumedOpen);
    }

    private boolean collisionClear(Point point, Set<Point> assumedOpen) {
        if (assumedOpen.contains(point)) return true;
        if (client.world == null || !cell(point).passable()) return false;
        BlockPos pos = block(point);
        if (!client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState state = client.world.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(client.world, pos).isEmpty();
    }

    private static boolean isDisposableNaturalTerrain(BlockState state) {
        return state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.BASE_STONE_NETHER)
                || state.isIn(BlockTags.DIRT)
                || state.isOf(Blocks.END_STONE);
    }

    @Override
    public boolean hasSightline(
            Point standFeet,
            Point targetBlock,
            Face targetFace,
            Set<Point> assumedOpen) {
        Objects.requireNonNull(standFeet, "standFeet");
        Objects.requireNonNull(targetBlock, "targetBlock");
        Objects.requireNonNull(targetFace, "targetFace");
        Objects.requireNonNull(assumedOpen, "assumedOpen");
        Vec3d start = Vec3d.ofBottomCenter(block(standFeet)).add(0.0, 1.62, 0.0);
        Vec3d end = faceCenter(targetBlock, targetFace);
        double distance = start.distanceTo(end);
        int samples = Math.max(4, (int) Math.ceil(distance * 16.0));
        for (int index = 1; index < samples; index++) {
            double t = index / (double) samples;
            Vec3d sample = start.lerp(end, t);
            Point point = new Point(
                    (int) Math.floor(sample.x),
                    (int) Math.floor(sample.y),
                    (int) Math.floor(sample.z));
            if (point.equals(standFeet) || point.equals(standFeet.up())
                    || point.equals(targetBlock) || assumedOpen.contains(point)) continue;
            if (!cell(point).passable()) return false;
        }
        return true;
    }

    private static Vec3d faceCenter(Point point, Face face) {
        Vec3d center = Vec3d.ofCenter(block(point));
        return switch (face) {
            case NORTH -> center.add(0.0, 0.0, -0.5);
            case SOUTH -> center.add(0.0, 0.0, 0.5);
            case WEST -> center.add(-0.5, 0.0, 0.0);
            case EAST -> center.add(0.5, 0.0, 0.0);
            case UP -> center.add(0.0, 0.5, 0.0);
            case DOWN -> center.add(0.0, -0.5, 0.0);
        };
    }

    public static Point point(BlockPos pos) {
        return new Point(pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockPos block(Point point) {
        return new BlockPos(point.x(), point.y(), point.z());
    }
}
