package dev.entity.client.autonomy.collection;

import dev.entity.client.autonomy.policy.ResourceCatalog;
import dev.entity.client.autonomy.resource.MinecraftResourcePerception;
import dev.entity.client.autonomy.safety.HazardMemory;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Converts the loaded Minecraft client world into the pure collection model. */
public final class MinecraftGroundItemAdapter {
    private static final long MILLIS_PER_TICK = 50L;
    private static final long LAVA_MEMORY_MILLIS = 30_000L;
    private static final long FIRE_MEMORY_MILLIS = 10_000L;
    private static final long UNSUPPORTED_FLOOR_MEMORY_MILLIS = 1_500L;
    private static final long VOID_MEMORY_MILLIS = 60_000L;
    private static final double DIRECT_APPROACH_SAMPLE_STEP = 0.25;

    private final MinecraftClient client;
    private final MinecraftResourcePerception resourcePerception;
    private final ResourceCatalog catalog;
    private final HazardMemory hazardMemory;
    private final ForeignItemClaimClientState foreignItems;
    private final Logger logger;
    private final Map<String, String> lastAdmissionCode = new LinkedHashMap<>();
    private boolean pickupMovementActive;

    public MinecraftGroundItemAdapter(
            MinecraftClient client,
            ForeignItemClaimClientState foreignItems,
            Logger logger) {
        this(client, ResourceCatalog.defaults(), new HazardMemory(), foreignItems, logger);
    }

    public MinecraftGroundItemAdapter(MinecraftClient client,
            ForeignItemClaimClientState foreignItems, Logger logger, MinecraftResourcePerception perception) {
        this(client, ResourceCatalog.defaults(), new HazardMemory(), foreignItems, logger, perception);
    }

    public MinecraftGroundItemAdapter(
            MinecraftClient client,
            ResourceCatalog catalog,
            HazardMemory hazardMemory,
            ForeignItemClaimClientState foreignItems,
            Logger logger) {
        this(client, catalog, hazardMemory, foreignItems, logger, new MinecraftResourcePerception(client));
    }

    public MinecraftGroundItemAdapter(MinecraftClient client, ResourceCatalog catalog,
            HazardMemory hazardMemory, ForeignItemClaimClientState foreignItems,
            Logger logger, MinecraftResourcePerception perception) {
        this.client = Objects.requireNonNull(client, "client");
        this.resourcePerception = Objects.requireNonNull(perception, "perception");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.hazardMemory = Objects.requireNonNull(hazardMemory, "hazardMemory");
        this.foreignItems = Objects.requireNonNull(foreignItems, "foreignItems");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Optional<Snapshot> observe(
            GroundItemCollectionPolicy.Request request,
            long nowMillis) {
        Objects.requireNonNull(request, "request");
        if (client.player == null || client.world == null) return Optional.empty();

        String dimension = currentDimension();
        LinkedHashMap<String, ItemEntity> entities = new LinkedHashMap<>();
        ArrayList<GroundItemCollectionPolicy.Candidate> candidates = new ArrayList<>();
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof ItemEntity item) || !item.isAlive() || item.isRemoved()) continue;
            ItemStack stack = item.getStack();
            if (stack.isEmpty()) continue;
            String canonical = catalog.normalizeItem(Registries.ITEM.getId(stack.getItem()).toString());
            if (!request.expectedCanonicalItemIds().contains(canonical)) continue;
            var observedPosition = resourcePerception.entityPosition(item);
            if (observedPosition.isEmpty()) continue;
            String id = item.getUuidAsString();
            ForeignItemClaimClientState.Admission admission =
                    foreignItems.decide(item.getUuid());
            logAdmission(item, canonical, admission);
            if (!admission.allowed()) continue;
            EnumSet<GroundItemCollectionPolicy.Hazard> hazards = pickupHazards(
                    item.getBlockPos(), item.isInLava(), item.isOnFire(), dimension, nowMillis);
            if (item.getY() < client.world.getBottomY()) {
                hazards.add(GroundItemCollectionPolicy.Hazard.VOID);
                remember(
                        dimension,
                        item.getBlockPos(),
                        HazardMemory.Kind.VOID,
                        HazardMemory.Severity.LETHAL,
                        nowMillis,
                        VOID_MEMORY_MILLIS);
            }
            GroundItemCollectionPolicy.Candidate candidate = new GroundItemCollectionPolicy.Candidate(
                    id,
                    canonical,
                    stack.getCount(),
                    point(observedPosition.orElseThrow().x, observedPosition.orElseThrow().y,
                            observedPosition.orElseThrow().z),
                    Math.max(0L, item.getItemAge() * MILLIS_PER_TICK),
                    hazards);
            candidates.add(candidate);
            entities.put(id, item);
        }

        Map<String, Integer> inventory = canonicalInventoryCounts();
        List<GroundItemCollectionPolicy.Candidate> eligible =
                GroundItemCollectionPolicy.matchingCandidates(request, candidates);
        boolean inventoryFull = !eligible.isEmpty() && !canAcceptAny(eligible, entities);
        GroundItemCollectionPolicy.Observation observation =
                new GroundItemCollectionPolicy.Observation(
                        nowMillis,
                        point(client.player.getX(), client.player.getY(), client.player.getZ()),
                        inventory,
                        candidates,
                        inventoryFull);
        return Optional.of(new Snapshot(observation, entities));
    }

    /**
     * Owns only the sub-block final approach after the Baritone route has been
     * cancelled. Walking to the entity coordinate (rather than its containing
     * block) makes normal player/item bounding boxes perform the pickup.
     */
    public boolean steerOnto(ItemEntity item) {
        return steerOnto(item, System.currentTimeMillis());
    }

    /**
     * Revalidates the item and every sampled cell of the short direct sweep
     * before owning movement keys.  A safe observation from the beginning of
     * a tick is not treated as a permanent lease over changing lava, fire, or
     * an unsupported edge.
     */
    public boolean steerOnto(ItemEntity item, long nowMillis) {
        return approach(item, nowMillis).steering();
    }

    public GroundItemCollectionPolicy.Approach approach(ItemEntity item, long nowMillis) {
        Objects.requireNonNull(item, "item");
        var player = client.player;
        if (player == null || client.world == null || item.isRemoved() || !item.isAlive()) {
            stopPickupMovement();
            return new GroundItemCollectionPolicy.Approach(GroundItemCollectionPolicy.ApproachKind.REJECTED,
                    "player/world unavailable or item entity no longer alive");
        }
        String canonical = catalog.normalizeItem(
                Registries.ITEM.getId(item.getStack().getItem()).toString());
        ForeignItemClaimClientState.Admission admission =
                foreignItems.decide(item.getUuid());
        logAdmission(item, canonical, admission);
        if (!admission.allowed()) {
            stopPickupMovement();
            return new GroundItemCollectionPolicy.Approach(GroundItemCollectionPolicy.ApproachKind.REJECTED,
                    "item authority changed: " + admission.decision().code());
        }
        String dimension = currentDimension();
        var assessment = GroundItemCollectionPolicy.assessDirectApproach(
                pickupHazards(item.getBlockPos(), item.isInLava(), item.isOnFire(), dimension, nowMillis),
                directApproachHazards(item, dimension, nowMillis));
        if (!assessment.steering()) {
            stopPickupMovement();
            return assessment;
        }
        var observed = resourcePerception.entityPosition(item);
        if (observed.isEmpty()) {
            stopPickupMovement();
            return new GroundItemCollectionPolicy.Approach(GroundItemCollectionPolicy.ApproachKind.REJECTED,
                    "item position is outside current perception");
        }
        double dx = observed.orElseThrow().x - player.getX();
        double dz = observed.orElseThrow().z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.01) {
            player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        }
        client.options.forwardKey.setPressed(horizontal > 0.06);
        client.options.sprintKey.setPressed(horizontal > 1.25);
        client.options.jumpKey.setPressed(
                (player.horizontalCollision && player.isOnGround())
                        || (observed.orElseThrow().y > player.getY() + 0.55 && player.isOnGround()));
        pickupMovementActive = true;
        return assessment;
    }

    public void stopPickupMovement() {
        if (!pickupMovementActive) return;
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        pickupMovementActive = false;
    }

    private Map<String, Integer> canonicalInventoryCounts() {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String canonical = catalog.normalizeItem(Registries.ITEM.getId(stack.getItem()).toString());
            counts.merge(canonical, stack.getCount(), Math::addExact);
        }
        return Map.copyOf(counts);
    }

    private boolean canAcceptAny(
            List<GroundItemCollectionPolicy.Candidate> eligible,
            Map<String, ItemEntity> entities) {
        var main = client.player.getInventory().getMainStacks();
        for (ItemStack present : main) {
            if (present.isEmpty()) return true;
            if (present.getCount() >= present.getMaxCount()) continue;
            for (GroundItemCollectionPolicy.Candidate candidate : eligible) {
                ItemEntity entity = entities.get(candidate.id());
                if (entity != null
                        && ItemStack.areItemsAndComponentsEqual(present, entity.getStack())) {
                    return true;
                }
            }
        }
        return false;
    }

    private EnumSet<GroundItemCollectionPolicy.Hazard> pickupHazards(
            BlockPos pickup,
            boolean entityInLava,
            boolean entityOnFire,
            String dimension,
            long nowMillis) {
        EnumSet<GroundItemCollectionPolicy.Hazard> hazards =
                EnumSet.noneOf(GroundItemCollectionPolicy.Hazard.class);
        Set<BlockPos> inspected = pickupSafetyCells(pickup);
        if (entityInLava) {
            hazards.add(GroundItemCollectionPolicy.Hazard.LAVA);
            remember(
                    dimension, pickup, HazardMemory.Kind.LAVA, HazardMemory.Severity.LETHAL,
                    nowMillis, LAVA_MEMORY_MILLIS);
        }
        if (entityOnFire) {
            hazards.add(GroundItemCollectionPolicy.Hazard.ON_FIRE);
            remember(
                    dimension, pickup, HazardMemory.Kind.FIRE, HazardMemory.Severity.DANGEROUS,
                    nowMillis, FIRE_MEMORY_MILLIS);
        }

        for (BlockPos source : inspected) {
            if (!isLoaded(source)) {
                hazards.add(GroundItemCollectionPolicy.Hazard.UNLOADED_ENVIRONMENT);
                continue;
            }
            BlockState state = client.world.getBlockState(source);
            if (state.getFluidState().isIn(FluidTags.LAVA)) {
                hazards.add(source.equals(pickup)
                        ? GroundItemCollectionPolicy.Hazard.LAVA
                        : GroundItemCollectionPolicy.Hazard.NEAR_LAVA);
                remember(
                        dimension, source, HazardMemory.Kind.LAVA, HazardMemory.Severity.LETHAL,
                        nowMillis, LAVA_MEMORY_MILLIS);
            }
            if (isFire(state)) {
                hazards.add(source.equals(pickup)
                        ? GroundItemCollectionPolicy.Hazard.ON_FIRE
                        : GroundItemCollectionPolicy.Hazard.NEAR_FIRE);
                remember(
                        dimension, source, HazardMemory.Kind.FIRE,
                        HazardMemory.Severity.DANGEROUS, nowMillis, FIRE_MEMORY_MILLIS);
            }
        }

        BlockPos floor = pickup.down();
        if (!hasPickupSupport(pickup, floor)) {
            hazards.add(GroundItemCollectionPolicy.Hazard.UNSUPPORTED_FLOOR);
            remember(
                    dimension, floor, HazardMemory.Kind.UNSUPPORTED_FLOOR,
                    HazardMemory.Severity.DANGEROUS,
                    nowMillis, UNSUPPORTED_FLOOR_MEMORY_MILLIS);
        }

        HazardMemory.Cell pickupCell = memoryCell(pickup);
        Set<HazardMemory.Cell> inspectedCells = new HashSet<>();
        for (BlockPos source : inspected) inspectedCells.add(memoryCell(source));
        for (HazardMemory.Entry remembered : hazardMemory.near(
                dimension, pickupCell, 1, nowMillis)) {
            if (!inspectedCells.contains(remembered.key().cell())) continue;
            switch (remembered.key().kind()) {
                case LAVA -> hazards.add(remembered.key().cell().equals(pickupCell)
                        ? GroundItemCollectionPolicy.Hazard.LAVA
                        : GroundItemCollectionPolicy.Hazard.NEAR_LAVA);
                case FIRE -> hazards.add(remembered.key().cell().equals(pickupCell)
                        ? GroundItemCollectionPolicy.Hazard.ON_FIRE
                        : GroundItemCollectionPolicy.Hazard.NEAR_FIRE);
                case UNSUPPORTED_FLOOR -> {
                    if (remembered.key().cell().equals(memoryCell(floor))) {
                        hazards.add(GroundItemCollectionPolicy.Hazard.UNSUPPORTED_FLOOR);
                    }
                }
                case VOID -> {
                    if (remembered.key().cell().equals(pickupCell)) {
                        hazards.add(GroundItemCollectionPolicy.Hazard.VOID);
                    }
                }
                case HOSTILE, TRAVERSAL_FAILURE, OTHER -> {
                    // These shared-memory kinds do not make an item cell physically unsafe.
                }
            }
        }
        return hazards;
    }

    private Set<GroundItemCollectionPolicy.Hazard> directApproachHazards(ItemEntity item, String dimension, long nowMillis) {
        var player = client.player;
        double dx = item.getX() - player.getX();
        double dy = item.getY() - player.getY();
        double dz = item.getZ() - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int samples = Math.max(1, (int) Math.ceil(horizontal / DIRECT_APPROACH_SAMPLE_STEP));
        for (int index = 1; index <= samples; index++) {
            double progress = index / (double) samples;
            BlockPos sampledFeet = BlockPos.ofFloored(
                    player.getX() + dx * progress,
                    player.getY() + dy * progress,
                    player.getZ() + dz * progress);
            var hazards = pickupHazards(sampledFeet, false, false, dimension, nowMillis);
            if (!hazards.isEmpty()) return hazards;
        }
        return Set.of();
    }

    private Set<BlockPos> pickupSafetyCells(BlockPos pickup) {
        HashSet<BlockPos> cells = new HashSet<>();
        cells.add(pickup.toImmutable());
        cells.add(pickup.down().toImmutable());
        for (Direction direction : Direction.Type.HORIZONTAL) {
            cells.add(pickup.offset(direction).toImmutable());
        }
        return Set.copyOf(cells);
    }

    private boolean hasPickupSupport(BlockPos pickup, BlockPos floor) {
        if (!isLoaded(pickup) || !isLoaded(floor)) return false;
        BlockState pickupState = client.world.getBlockState(pickup);
        BlockState floorState = client.world.getBlockState(floor);
        if (pickupState.getFluidState().isIn(FluidTags.WATER)
                || floorState.getFluidState().isIn(FluidTags.WATER)) return true;
        // Item entities can rest inside the block coordinate of a slab, snow
        // layer, stair, or other partial collision shape.  That shape itself
        // is support; requiring only pickup.down() would misclassify it as a
        // cliff and preserve a false hazard after the item had already landed.
        return !pickupState.getCollisionShape(client.world, pickup).isEmpty()
                || !floorState.getCollisionShape(client.world, floor).isEmpty();
    }

    private boolean isLoaded(BlockPos position) {
        return client.world != null
                && client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4);
    }

    private static boolean isFire(BlockState state) {
        return state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE);
    }

    private void remember(
            String dimension,
            BlockPos position,
            HazardMemory.Kind kind,
            HazardMemory.Severity severity,
            long nowMillis,
            long ttlMillis) {
        hazardMemory.remember(
                dimension, memoryCell(position), kind, severity, nowMillis, ttlMillis);
    }

    private String currentDimension() {
        return client.world.getRegistryKey().getValue().toString();
    }

    private static HazardMemory.Cell memoryCell(BlockPos position) {
        return new HazardMemory.Cell(position.getX(), position.getY(), position.getZ());
    }

    private static GroundItemCollectionPolicy.Point point(double x, double y, double z) {
        return new GroundItemCollectionPolicy.Point(x, y, z);
    }

    private void logAdmission(
            ItemEntity item,
            String canonical,
            ForeignItemClaimClientState.Admission admission) {
        String id = item.getUuidAsString();
        String owner = admission.claim().map(claim -> claim.ownerName()).orElse("none");
        String evidence = admission.decision().code().name() + ':' + owner;
        if (evidence.equals(lastAdmissionCode.get(id))) return;
        if (lastAdmissionCode.size() >= 512 && !lastAdmissionCode.containsKey(id)) {
            lastAdmissionCode.clear();
        }
        lastAdmissionCode.put(id, evidence);
        logger.info("Ground-item admission target={} item={} allowed={} code={} owner={}",
                id,
                canonical,
                admission.allowed(),
                admission.decision().code(),
                owner);
    }

    public record Snapshot(
            GroundItemCollectionPolicy.Observation observation,
            Map<String, ItemEntity> entitiesByCandidateId) {
        public Snapshot {
            observation = Objects.requireNonNull(observation, "observation");
            entitiesByCandidateId = Map.copyOf(
                    Objects.requireNonNull(entitiesByCandidateId, "entitiesByCandidateId"));
        }
    }
}
