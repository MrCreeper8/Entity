package dev.entity.client.autonomy.resource;

import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

/** The production visibility predicate, independently exercisable against vanilla block shapes. */
final class ResourcePerceptionRay {
    private static final double[][] SAMPLES = {{.5,.5,.5}, {.01,.5,.5}, {.99,.5,.5},
            {.5,.01,.5}, {.5,.99,.5}, {.5,.5,.01}, {.5,.5,.99}};

    static boolean block(BlockView world, Vec3d eye, BlockPos target, ShapeContext shape) {
        // Most recipe-hint candidates are buried stone. Reject full enclosure cheaply before rays.
        boolean enclosed = !BlockPos.ofFloored(eye).equals(target);
        for (Direction side : Direction.values()) {
            if (!world.getBlockState(target.offset(side)).isOpaqueFullCube()) { enclosed = false; break; }
        }
        if (enclosed) return false;
        var state = world.getBlockState(target);
        var fluids = state.isOf(Blocks.WATER) || state.isOf(Blocks.BUBBLE_COLUMN)
                ? RaycastContext.FluidHandling.ANY : RaycastContext.FluidHandling.NONE;
        for (double[] offset : SAMPLES) {
            Vec3d point = new Vec3d(target.getX() + offset[0], target.getY() + offset[1],
                    target.getZ() + offset[2]);
            var hit = world.raycast(new RaycastContext(eye, point,
                    RaycastContext.ShapeType.OUTLINE, fluids, shape));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return true;
        }
        return false;
    }

    static boolean clear(BlockView world, Vec3d eye, Vec3d point, ShapeContext shape) {
        return world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, shape)).getType() == HitResult.Type.MISS;
    }

    private ResourcePerceptionRay() { }
}
