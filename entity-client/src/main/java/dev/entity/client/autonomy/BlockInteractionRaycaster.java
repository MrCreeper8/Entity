package dev.entity.client.autonomy;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Optional;

/** Honest multi-point raycasts for block interaction; never fabricates a hit. */
public final class BlockInteractionRaycaster {
    private static final double[][] FACE_SAMPLES = {
            {0, 0, 0}, {0.495, 0, 0}, {-0.495, 0, 0},
            {0, 0.495, 0}, {0, -0.495, 0}, {0, 0, 0.495}, {0, 0, -0.495},
            {0.30, 0.30, 0}, {-0.30, 0.30, 0}, {0, 0.30, 0.30}, {0, 0.30, -0.30}
    };
    private static final double[][] TOP_SAMPLES = {
            {0, 0.499, 0}, {0.28, 0.499, 0}, {-0.28, 0.499, 0},
            {0, 0.499, 0.28}, {0, 0.499, -0.28}
    };

    /** Float packet rotation whose full vanilla reach ray hits the requested source block. */
    public record FluidSourceAim(float yaw, float pitch, BlockHitResult hit) {
    }

    /** Float packet rotation whose full vanilla reach ray hits the requested block outline. */
    public record BlockAim(float yaw, float pitch, BlockHitResult hit) {
    }

    public static Optional<BlockHitResult> visible(
            MinecraftClient client, Vec3d start, BlockPos target) {
        return sampled(client, start, target, FACE_SAMPLES, false,
                RaycastContext.FluidHandling.NONE);
    }

    public static Optional<BlockHitResult> visibleNow(
            MinecraftClient client, BlockPos target) {
        if (client.player == null) return Optional.empty();
        if (client.crosshairTarget instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target)) return Optional.of(hit);
        return visible(client, client.player.getEyePos(), target);
    }

    /**
     * Finds a vanilla-valid aim against the target's actual outline instead of
     * assuming its geometric block center is occupied. Short crops such as
     * beetroot do not necessarily intersect that center point.
     */
    public static Optional<BlockAim> visibleAim(
            MinecraftClient client,
            Vec3d start,
            BlockPos target,
            double reach) {
        if (client.player == null || client.world == null
                || !Double.isFinite(reach) || reach <= 0.0) return Optional.empty();
        Vec3d center = Vec3d.ofCenter(target);
        double reachSquared = reach * reach;
        for (double[] offset : FACE_SAMPLES) {
            Vec3d targetPoint = center.add(offset[0], offset[1], offset[2]);
            if (start.squaredDistanceTo(targetPoint) > reachSquared) continue;
            Vec3d delta = targetPoint.subtract(start);
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
            Vec3d rotation = client.player.getRotationVector(pitch, yaw);
            Vec3d end = start.add(rotation.multiply(reach));
            BlockHitResult hit = client.world.raycast(new RaycastContext(
                    start, end, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) {
                return Optional.of(new BlockAim(yaw, pitch, hit));
            }
        }
        return Optional.empty();
    }

    public static Optional<BlockHitResult> topFace(
            MinecraftClient client, Vec3d start, BlockPos support) {
        return sampled(client, start, support, TOP_SAMPLES, true,
                RaycastContext.FluidHandling.NONE);
    }

    /** Exact source-fluid raycast used by the persisted Home bucket fill. */
    public static Optional<BlockHitResult> fluidSourceVisibleNow(
            MinecraftClient client,
            BlockPos target) {
        if (client.player == null) return Optional.empty();
        return fluidSourceVisible(client, client.player.getEyePos(), target);
    }

    public static Optional<BlockHitResult> fluidSourceVisible(
            MinecraftClient client,
            Vec3d start,
            BlockPos target) {
        return sampled(client, start, target, FACE_SAMPLES, false,
                RaycastContext.FluidHandling.SOURCE_ONLY);
    }

    public static Optional<FluidSourceAim> fluidSourceAimNow(
            MinecraftClient client,
            BlockPos target) {
        if (client.player == null) return Optional.empty();
        return fluidSourceAim(client, client.player.getEyePos(), target);
    }

    /**
     * Finds an aim using the same float yaw/pitch and full-reach ray as
     * BucketItem#use. This rejects geometrically visible but packet-fragile
     * boundary rays before a stand or use is accepted.
     */
    public static Optional<FluidSourceAim> fluidSourceAim(
            MinecraftClient client,
            Vec3d start,
            BlockPos target) {
        if (client.player == null || client.world == null) return Optional.empty();
        Vec3d center = Vec3d.ofCenter(target);
        for (double[] offset : FACE_SAMPLES) {
            Vec3d targetPoint = center.add(offset[0], offset[1], offset[2]);
            Vec3d delta = targetPoint.subtract(start);
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
            Vec3d rotation = client.player.getRotationVector(pitch, yaw);
            Vec3d end = start.add(rotation.multiply(client.player.getBlockInteractionRange()));
            BlockHitResult hit = client.world.raycast(new RaycastContext(
                    start, end, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.SOURCE_ONLY, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) {
                return Optional.of(new FluidSourceAim(yaw, pitch, hit));
            }
        }
        return Optional.empty();
    }

    /** Mirrors Item#raycast so a bucket packet is sent only under a vanilla-valid source aim. */
    public static Optional<BlockHitResult> fluidSourceUnderCurrentAim(
            MinecraftClient client,
            BlockPos target) {
        if (client.player == null || client.world == null) return Optional.empty();
        Vec3d start = client.player.getEyePos();
        Vec3d rotation = client.player.getRotationVector(
                client.player.getPitch(), client.player.getYaw());
        Vec3d end = start.add(rotation.multiply(client.player.getBlockInteractionRange()));
        BlockHitResult hit = client.world.raycast(new RaycastContext(
                start, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.SOURCE_ONLY, client.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)
                ? Optional.of(hit)
                : Optional.empty();
    }

    public static Optional<BlockHitResult> topFaceNow(
            MinecraftClient client, BlockPos support) {
        if (client.player == null) return Optional.empty();
        if (client.crosshairTarget instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(support)
                && hit.getSide() == net.minecraft.util.math.Direction.UP) return Optional.of(hit);
        return topFace(client, client.player.getEyePos(), support);
    }

    private static Optional<BlockHitResult> sampled(
            MinecraftClient client,
            Vec3d start,
            BlockPos target,
            double[][] offsets,
            boolean requireTop,
            RaycastContext.FluidHandling fluidHandling) {
        if (client.world == null || client.player == null) return Optional.empty();
        Vec3d center = Vec3d.ofCenter(target);
        for (double[] offset : offsets) {
            Vec3d end = center.add(offset[0], offset[1], offset[2]);
            BlockHitResult hit = client.world.raycast(new RaycastContext(
                    start, end, RaycastContext.ShapeType.OUTLINE,
                    fluidHandling, client.player));
            if (hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(target)
                    && (!requireTop || hit.getSide() == net.minecraft.util.math.Direction.UP)) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }

    private BlockInteractionRaycaster() {
    }
}
