package dev.entity.client.technique;

import dev.entity.client.control.MinecraftActuatorGateway;
import dev.entity.client.technique.FallTechniqueGeometryPolicy.ClutchCandidate;
import dev.entity.client.technique.FallTechniqueGeometryPolicy.ClutchDecision;
import dev.entity.client.technique.FallTechniqueGeometryPolicy.WaterCandidate;
import dev.entity.client.technique.FallTechniqueGeometryPolicy.WaterDecision;
import dev.entity.client.technique.InventoryTechniquePolicy.BucketAccess;
import dev.entity.client.technique.InventoryTechniquePolicy.CapabilityInventory;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidFillable;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Converts current loaded Minecraft state into the pure technique inventory. */
public final class MinecraftFallTechniqueObserver {
    private static final int WATER_SCAN_RADIUS = 4;
    private static final int MAX_WATER_SCAN_DEPTH = 64;
    private static final List<FaceOffset> CLUTCH_FACE_OFFSETS = List.of(
            new FaceOffset(0.50, 0.50),
            new FaceOffset(0.06, 0.06),
            new FaceOffset(0.06, 0.94),
            new FaceOffset(0.94, 0.06),
            new FaceOffset(0.94, 0.94),
            new FaceOffset(0.06, 0.50),
            new FaceOffset(0.94, 0.50),
            new FaceOffset(0.50, 0.06),
            new FaceOffset(0.50, 0.94));

    private final MinecraftClient client;
    private final MinecraftActuatorGateway actuators;

    public MinecraftFallTechniqueObserver(
            MinecraftClient client,
            MinecraftActuatorGateway actuators) {
        this.client = Objects.requireNonNull(client, "client");
        this.actuators = Objects.requireNonNull(actuators, "actuators");
    }

    public Snapshot observe() {
        var player = client.player;
        var world = client.world;
        if (player == null || world == null) {
            InventoryTechniquePolicy.Observation unavailable =
                    InventoryTechniquePolicy.Observation.unavailable(
                            false, BucketAccess.NONE);
            return Snapshot.empty(
                    unavailable, InventoryTechniquePolicy.evaluate(unavailable));
        }

        boolean falling = !player.isOnGround()
                && player.getVelocity().y < -0.02
                && player.fallDistance >= 3.5F;
        BucketSelection bucket = findBucket();
        FallTechniqueGeometryPolicy.Body body = new FallTechniqueGeometryPolicy.Body(
                player.getX(), player.getY(), player.getEyeY(), player.getZ(),
                player.getVelocity().x, player.getVelocity().z,
                player.getBlockInteractionRange());

        WaterDecision water = falling
                ? FallTechniqueGeometryPolicy.selectExistingWater(
                        body, observeWaterCandidates())
                : FallTechniqueGeometryPolicy.selectExistingWater(body, List.of());
        ClutchObservation clutch = observeClutch(body);
        boolean mutationAllowed = clutch.decision().valid()
                && actuators.allowsEmergencyFluidMutation(clutch.placement());
        InventoryTechniquePolicy.Observation policyObservation =
                new InventoryTechniquePolicy.Observation(
                        falling,
                        bucket.access(),
                        world.getDimension().ultrawarm(),
                        water.candidatePresent(),
                        water.loaded(),
                        water.realWaterVolume(),
                        water.corridorClear(),
                        water.reachable(),
                        clutch.decision().loaded(),
                        clutch.decision().reachable(),
                        clutch.decision().replaceable(),
                        clutch.decision().onFallCorridor(),
                        mutationAllowed);
        CapabilityInventory inventory = InventoryTechniquePolicy.evaluate(policyObservation);
        BlockPos landing = water.valid() && water.candidate() != null
                ? new BlockPos(
                        water.candidate().x(), water.candidate().y(), water.candidate().z())
                : null;
        return new Snapshot(
                policyObservation,
                inventory,
                bucket.access(),
                bucket.hand(),
                bucket.hotbarSlot(),
                landing,
                clutch.support(),
                clutch.placement(),
                clutch.hit());
    }

    public boolean pinnedClutchStillValid(
            BlockPos support,
            BlockPos placement,
            BlockHitResult pinnedHit) {
        if (support == null || placement == null || pinnedHit == null
                || client.player == null || client.world == null) return false;
        FallTechniqueGeometryPolicy.Body body = new FallTechniqueGeometryPolicy.Body(
                client.player.getX(), client.player.getY(), client.player.getEyeY(),
                client.player.getZ(), client.player.getVelocity().x,
                client.player.getVelocity().z,
                client.player.getBlockInteractionRange());
        BlockHitResult currentHit = raycastToward(body, pinnedHit.getPos());
        if (!sameHit(currentHit, pinnedHit)) return false;
        BlockPos currentPlacement = waterBucketPlacementFor(currentHit);
        if (!placement.equals(currentPlacement)) return false;
        ClutchObservation observed = clutchCandidate(
                body, support, placement, currentHit);
        return observed.decision().valid()
                && actuators.allowsEmergencyFluidMutation(placement);
    }

    /**
     * Mirrors the target choice made by {@code BucketItem#use} for a filled
     * water bucket. A ray may hit replaceable vegetation above the physical
     * floor; vanilla then places water on the hit face instead of pretending
     * that the ray reached the floor underneath it.
     */
    public BlockPos waterBucketPlacementFor(BlockHitResult hit) {
        if (hit == null || hit.getType() != HitResult.Type.BLOCK
                || client.player == null || client.world == null) return null;
        BlockPos clicked = hit.getBlockPos();
        BlockState state = client.world.getBlockState(clicked);
        if (state.getBlock() instanceof FluidFillable fillable) {
            boolean canPlaceAtClicked = state.canBucketPlace(Fluids.WATER)
                    || fillable.canFillWithFluid(
                            client.player, client.world, clicked, state, Fluids.WATER);
            if (canPlaceAtClicked) return clicked.toImmutable();
        }
        return clicked.offset(hit.getSide()).toImmutable();
    }

    public boolean placedWaterPresent(BlockPos placement) {
        return isLoaded(placement)
                && client.world.getBlockState(placement).isOf(Blocks.WATER)
                && client.world.getFluidState(placement).isIn(FluidTags.WATER);
    }

    public boolean safelyLandedIn(BlockPos placement) {
        if (placement == null || client.player == null || client.world == null
                || !client.player.isAlive() || !placedWaterPresent(placement)) return false;
        double dx = client.player.getX() - (placement.getX() + 0.5);
        double dz = client.player.getZ() - (placement.getZ() + 0.5);
        return dx * dx + dz * dz <= 1.15 * 1.15
                && client.player.isTouchingWater()
                && client.player.fallDistance < 0.5F
                && client.player.getVelocity().y > -0.35;
    }

    public boolean recoveryReachable(BlockPos placement) {
        if (!placedWaterPresent(placement) || client.player == null) return false;
        return client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(placement))
                <= Math.pow(client.player.getBlockInteractionRange(), 2.0);
    }

    public boolean waterBucketRecovered(Hand hand, int hotbarSlot) {
        if (client.player == null || hand == null) return false;
        if (hand == Hand.OFF_HAND) {
            return client.player.getOffHandStack().isOf(Items.WATER_BUCKET);
        }
        return hotbarSlot >= 0
                && hotbarSlot < PlayerInventory.getHotbarSize()
                && client.player.getInventory().getStack(hotbarSlot).isOf(Items.WATER_BUCKET);
    }

    private List<WaterCandidate> observeWaterCandidates() {
        var player = client.player;
        int centerX = (int) Math.floor(player.getX());
        int centerZ = (int) Math.floor(player.getZ());
        int topY = (int) Math.floor(player.getY()) - 1;
        int bottomY = Math.max(client.world.getBottomY(), topY - MAX_WATER_SCAN_DEPTH);
        ArrayList<WaterCandidate> candidates = new ArrayList<>();
        for (int x = centerX - WATER_SCAN_RADIUS; x <= centerX + WATER_SCAN_RADIUS; x++) {
            for (int z = centerZ - WATER_SCAN_RADIUS; z <= centerZ + WATER_SCAN_RADIUS; z++) {
                if (!client.world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = topY; y >= bottomY; y--) {
                    BlockPos position = new BlockPos(x, y, z);
                    BlockState state = client.world.getBlockState(position);
                    boolean water = state.getFluidState().isIn(FluidTags.WATER);
                    if (water) {
                        boolean realVolume = state.isOf(Blocks.WATER)
                                && state.getFluidState().isStill();
                        boolean bodyClear = state.getCollisionShape(
                                        client.world, position).isEmpty()
                                && client.world.getBlockState(position.up())
                                        .getCollisionShape(client.world, position.up()).isEmpty();
                        candidates.add(new WaterCandidate(
                                x, y, z, true, realVolume, bodyClear,
                                clearVerticalCorridor(position.up(), topY)));
                        break;
                    }
                    if (!state.getCollisionShape(client.world, position).isEmpty()) break;
                }
            }
        }
        return List.copyOf(candidates);
    }

    private boolean clearVerticalCorridor(BlockPos start, int topY) {
        for (int y = start.getY(); y <= topY; y++) {
            BlockPos position = new BlockPos(start.getX(), y, start.getZ());
            if (!isLoaded(position)
                    || !client.world.getBlockState(position)
                            .getCollisionShape(client.world, position).isEmpty()) return false;
        }
        return true;
    }

    private ClutchObservation observeClutch(FallTechniqueGeometryPolicy.Body body) {
        int x = (int) Math.floor(body.x());
        int z = (int) Math.floor(body.z());
        int topY = (int) Math.floor(body.feetY()) - 1;
        int scan = (int) Math.ceil(body.interactionRange() + 2.0);
        for (int y = topY; y >= topY - scan; y--) {
            BlockPos support = new BlockPos(x, y, z);
            if (!isLoaded(support)) break;
            BlockState state = client.world.getBlockState(support);
            if (!state.getCollisionShape(client.world, support).isEmpty()) {
                return clutchAt(body, support);
            }
        }
        return new ClutchObservation(
                null, null, null,
                FallTechniqueGeometryPolicy.validateClutch(body, null));
    }

    private ClutchObservation clutchAt(
            FallTechniqueGeometryPolicy.Body body,
            BlockPos support) {
        ClutchObservation firstObserved = null;
        for (FaceOffset offset : CLUTCH_FACE_OFFSETS) {
            Vec3d aim = new Vec3d(
                    support.getX() + offset.x(),
                    support.getY() + 1.0,
                    support.getZ() + offset.z());
            BlockHitResult hit = raycastToward(body, aim);
            if (hit == null || hit.getType() != HitResult.Type.BLOCK
                    || hit.getSide() != Direction.UP) continue;
            BlockPos placement = waterBucketPlacementFor(hit);
            if (placement == null) continue;
            ClutchObservation observed = clutchCandidate(
                    body, support, placement, hit);
            if (observed.decision().valid()) return observed;
            if (firstObserved == null) firstObserved = observed;
        }
        if (firstObserved != null) return firstObserved;
        return new ClutchObservation(
                support.toImmutable(), null, null,
                FallTechniqueGeometryPolicy.validateClutch(body, null));
    }

    private ClutchObservation clutchCandidate(
            FallTechniqueGeometryPolicy.Body body,
            BlockPos support,
            BlockPos placement,
            BlockHitResult hit) {
        boolean supportLoaded = isLoaded(support);
        boolean placementLoaded = isLoaded(placement);
        boolean clickedLoaded = isLoaded(hit.getBlockPos());
        BlockState supportState = supportLoaded
                ? client.world.getBlockState(support)
                : Blocks.AIR.getDefaultState();
        BlockState placementState = placementLoaded
                ? client.world.getBlockState(placement)
                : Blocks.AIR.getDefaultState();
        BlockState clickedState = clickedLoaded
                ? client.world.getBlockState(hit.getBlockPos())
                : Blocks.AIR.getDefaultState();
        Vec3d hitPosition = hit.getPos();
        boolean supportSolid = supportLoaded
                && !supportState.getCollisionShape(client.world, support).isEmpty();
        boolean replaceable = placementLoaded
                && (placementState.isAir() || placementState.isReplaceable())
                && placementState.getFluidState().isEmpty();
        boolean hazardFree = supportLoaded && placementLoaded && clickedLoaded
                && !hazardous(supportState) && !hazardous(placementState)
                && !hazardous(clickedState);
        ClutchCandidate candidate = new ClutchCandidate(
                hitPosition.x, hitPosition.y, hitPosition.z,
                placement.getX(), placement.getY(), placement.getZ(),
                supportLoaded, placementLoaded, hit.getSide() == Direction.UP, supportSolid,
                replaceable, hazardFree);
        ClutchDecision decision = FallTechniqueGeometryPolicy.validateClutch(body, candidate);
        return new ClutchObservation(
                support.toImmutable(), placement.toImmutable(), hit, decision);
    }

    /**
     * Repeats the same outline/fluid ray family used by a filled BucketItem,
     * but terminates just behind the selected immutable hit point so a later
     * render-frame target cannot replace the live interaction evidence.
     */
    private BlockHitResult raycastToward(
            FallTechniqueGeometryPolicy.Body body,
            Vec3d target) {
        if (client.world == null || client.player == null) return null;
        Vec3d start = new Vec3d(body.x(), body.eyeY(), body.z());
        Vec3d delta = target.subtract(start);
        if (delta.lengthSquared() < 1.0E-8) return null;
        return client.world.raycast(new RaycastContext(
                start,
                target.add(delta.normalize().multiply(0.10)),
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                client.player));
    }

    private static boolean sameHit(BlockHitResult current, BlockHitResult pinned) {
        return current != null && pinned != null
                && current.getType() == HitResult.Type.BLOCK
                && current.getBlockPos().equals(pinned.getBlockPos())
                && current.getSide() == pinned.getSide();
    }

    private BucketSelection findBucket() {
        var player = client.player;
        if (player.getOffHandStack().isOf(Items.WATER_BUCKET)) {
            return new BucketSelection(BucketAccess.OFF_HAND, Hand.OFF_HAND, -1);
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (inventory.getStack(slot).isOf(Items.WATER_BUCKET)) {
                return new BucketSelection(BucketAccess.HOTBAR, Hand.MAIN_HAND, slot);
            }
        }
        for (int slot = PlayerInventory.getHotbarSize(); slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(Items.WATER_BUCKET)) {
                return new BucketSelection(BucketAccess.INVENTORY, null, -1);
            }
        }
        return new BucketSelection(BucketAccess.NONE, null, -1);
    }

    private boolean isLoaded(BlockPos position) {
        return position != null && client.world != null
                && client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4);
    }

    private static boolean hazardous(BlockState state) {
        return state.isOf(Blocks.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.POWDER_SNOW);
    }

    public record Snapshot(
            InventoryTechniquePolicy.Observation observation,
            CapabilityInventory inventory,
            BucketAccess bucketAccess,
            Hand bucketHand,
            int bucketHotbarSlot,
            BlockPos existingWaterLanding,
            BlockPos clutchSupport,
            BlockPos clutchPlacement,
            BlockHitResult clutchHit) {
        public Snapshot {
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(bucketAccess, "bucketAccess");
        }

        private static Snapshot empty(
                InventoryTechniquePolicy.Observation observation,
                CapabilityInventory inventory) {
            return new Snapshot(
                    observation, inventory, BucketAccess.NONE, null, -1,
                    null, null, null, null);
        }
    }

    private record BucketSelection(BucketAccess access, Hand hand, int hotbarSlot) {
    }

    private record ClutchObservation(
            BlockPos support,
            BlockPos placement,
            BlockHitResult hit,
            ClutchDecision decision) {
    }

    private record FaceOffset(double x, double z) {
    }
}
