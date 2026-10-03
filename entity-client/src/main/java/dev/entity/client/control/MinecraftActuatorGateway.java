package dev.entity.client.control;

import dev.entity.client.autonomy.resource.LoadedResourceClassifier;
import dev.entity.client.autonomy.resource.MinecraftResourceObserver;
import dev.entity.client.autonomy.resource.ResourceActuationSession;
import dev.entity.client.autonomy.resource.ResourceBreakScope;
import dev.entity.client.autonomy.resource.SupportedFlowerHarvest;
import dev.entity.core.farm.ManagedFarmPolicy;
import dev.entity.client.protection.ProtectedAreaClientState;
import dev.entity.core.stewardship.ProtectedAreaHomePermit;
import dev.entity.core.stewardship.ProtectedAreaPolicy;
import dev.entity.core.stewardship.ResourceStewardshipPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The focus-independent write boundary for exact Minecraft interactions.
 *
 * <p>Vanilla normally advances a held block break only while its window owns
 * the cursor. Entity instead pins the target here and advances that exact
 * transaction from its client tick. A mixin suppresses vanilla's competing
 * cancel/continue handler only while this gateway has a fresh heartbeat.</p>
 */
public final class MinecraftActuatorGateway {
    private static final long VANILLA_SUPPRESSION_AGE_TICKS = 2L;
    private static final Map<MinecraftClient, MinecraftActuatorGateway> SHARED =
            new WeakHashMap<>();

    private final MinecraftClient client;
    private final MinecraftResourceObserver resourceObserver;
    private final DirectBlockBreakLease blockBreakLease = new DirectBlockBreakLease();
    private final DirectUseLease heldUseLease = new DirectUseLease();
    private ProtectedAreaClientState protectedAreas;
    private BlockedBreakAttempt blockedBreakAttempt;
    private BlockedInteractionAttempt blockedInteractionAttempt;
    private PortalInteractionAttempt portalInteractionAttempt;
    private BlockAttemptObservation lastBlockAttempt;
    private long lastBlueprintUseTrace;

    /** Read-only action-boundary facts; renderer crosshair may be absent headlessly. */
    public record BlockAttemptObservation(long timestamp, String dimension, int x, int y, int z,
                                          String block, String item, double speed, boolean blocked) { }

    public synchronized Optional<BlockAttemptObservation> lastBlockAttempt() {
        return Optional.ofNullable(lastBlockAttempt);
    }
    private PortalInteractionScope portalInteractionScope;
    private boolean portalPassageFence;
    private ProtectedAreaClientState.ExactHomeCapability exactHomeInteraction;
    private ExactHomeOperationScope exactHomeOperation;
    private ResourceBreakScope resourceBreakScope;
    private dev.entity.client.blueprint.BlueprintBuildScope blueprintScope;

    public synchronized void blueprintScope(dev.entity.client.blueprint.BlueprintBuildScope scope) {
        blueprintScope = scope;
    }

    private MinecraftActuatorGateway(MinecraftClient client) {
        this.client = Objects.requireNonNull(client, "client");
        this.resourceObserver = new MinecraftResourceObserver(client);
    }

    /** Returns the sole actuator gateway associated with this Minecraft client. */
    public static MinecraftActuatorGateway shared(MinecraftClient client) {
        Objects.requireNonNull(client, "client");
        synchronized (SHARED) {
            return SHARED.computeIfAbsent(client, MinecraftActuatorGateway::new);
        }
    }

    /** Native hard revocation; the server acknowledgement owns waking and screen closure. */
    public boolean stopSleeping() {
        if (client.world == null || client.player == null || !client.player.isAlive()
                || !client.player.isSleeping() || client.player.networkHandler == null) return false;
        client.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                client.player, ClientCommandC2SPacket.Mode.STOP_SLEEPING));
        return true;
    }

    /** Installs the authenticated policy state before any autonomous action starts. */
    public synchronized void installProtectedAreaPolicy(ProtectedAreaClientState protectedAreas) {
        this.protectedAreas = Objects.requireNonNull(protectedAreas, "protectedAreas");
    }

    /** Installs one immutable exact resource target for the current route owner. */
    public synchronized void installResourceBreakScope(ResourceBreakScope scope) {
        resourceBreakScope = Objects.requireNonNull(scope, "scope");
        blockedBreakAttempt = null;
    }

    /** Revokes only the resource operation which still owns the final boundary. */
    public synchronized void clearResourceBreakScope(String operationId) {
        String expected = Objects.requireNonNullElse(operationId, "").trim();
        if (resourceBreakScope != null
                && resourceBreakScope.operationId().equals(expected)) {
            resourceBreakScope = null;
        }
    }

    /** Hard owner-transition cleanup; no stale resource capability survives it. */
    public synchronized void clearResourceBreakScope() {
        resourceBreakScope = null;
    }

    /** Called by the Minecraft mixin before vanilla handles physical attack input. */
    public static boolean suppressVanillaBlockBreaking(MinecraftClient client) {
        if (client == null) return false;
        MinecraftActuatorGateway gateway;
        synchronized (SHARED) {
            gateway = SHARED.get(client);
        }
        return gateway != null && gateway.hasFreshDirectBreak();
    }

    /**
     * Final exact-coordinate veto used by the interaction-manager mixin before
     * Minecraft or Baritone can start, advance, or commit a physical break.
     */
    public static boolean suppressProtectedBlockBreaking(
            MinecraftClient client,
            BlockPos target) {
        if (client == null || target == null) return false;
        MinecraftActuatorGateway gateway;
        synchronized (SHARED) {
            gateway = SHARED.get(client);
        }
        if (gateway == null) return false;
        boolean blocked = gateway.blockProtectedBreak(target);
        if (client.world != null && client.player != null) {
            BlockState state = client.world.getBlockState(target);
            ItemStack hand = client.player.getMainHandStack();
            // This records an attempted native action, not a claim that Paper
            // accepted it. World changes/inventory deltas remain separate proof.
            synchronized (gateway) {
                gateway.lastBlockAttempt = new BlockAttemptObservation(System.currentTimeMillis(),
                        client.world.getRegistryKey().getValue().toString(), target.getX(), target.getY(), target.getZ(),
                        Registries.BLOCK.getId(state.getBlock()).toString(), Registries.ITEM.getId(hand.getItem()).toString(),
                        hand.getMiningSpeedMultiplier(state), blocked);
            }
        }
        return blocked;
    }

    /** Final client-side veto for block use, placement, fluid, and containers. */
    public static boolean suppressProtectedBlockInteraction(
            MinecraftClient client,
            Hand hand,
            BlockHitResult hit) {
        if (client == null || hand == null || hit == null) return false;
        MinecraftActuatorGateway gateway;
        synchronized (SHARED) {
            gateway = SHARED.get(client);
        }
        return gateway != null && gateway.blockProtectedInteraction(hand, hit);
    }

    /** Guards BucketItem#use paths which bypass interactBlock. */
    public static boolean suppressProtectedItemInteraction(
            MinecraftClient client,
            Hand hand) {
        if (client == null || hand == null) return false;
        MinecraftActuatorGateway gateway;
        synchronized (SHARED) {
            gateway = SHARED.get(client);
        }
        return gateway != null && gateway.blockProtectedItemInteraction(hand);
    }

    /**
     * Read-only final-policy preflight for one emergency fluid coordinate.
     * The interaction mixin still repeats this decision immediately before the
     * packet; capability reporting never creates authority.
     */
    public synchronized boolean allowsEmergencyFluidMutation(BlockPos position) {
        if (position == null || client.player == null || client.world == null
                || protectedAreas == null) return false;
        ProtectedBlock key = new ProtectedBlock(
                currentDimension(), position.getX(), position.getY(), position.getZ());
        return protectedAreas.decide(
                ProtectedAreaPolicy.Action.FLUID,
                key.dimension(), key.x(), key.y(), key.z(), false).allowed();
    }

    /** Called by the Minecraft mixin before its repeated physical-use loop. */
    public static boolean suppressVanillaItemUse(MinecraftClient client) {
        if (client == null) return false;
        MinecraftActuatorGateway gateway;
        synchronized (SHARED) {
            gateway = SHARED.get(client);
        }
        return gateway != null && gateway.hasFreshHeldUse();
    }

    /**
     * Starts once, then advances exactly one pinned block break per Entity tick.
     * No physical attack key is ever latched.
     */
    public synchronized BreakFrame tickBlockBreak(
            String operationId,
            BlockPos target,
            Direction side) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(side, "side");
        if (client.player == null || client.world == null || client.interactionManager == null) {
            if (blockBreakLease.clear()) cancelMinecraftBreak();
            return new BreakFrame(false, false, false, 0, 0,
                    "waiting for a loaded interaction context");
        }

        releasePhysicalAttackKey();
        String dimension = currentDimension();
        if (blockBreakLease.expireUnlessFresh(
                dimension,
                client.player.age,
                VANILLA_SUPPRESSION_AGE_TICKS) == DirectBlockBreakLease.Freshness.EXPIRED) {
            cancelMinecraftBreak();
        }
        DirectBlockBreakLease.Claim claim = blockBreakLease.claim(
                operationId,
                dimension,
                target.asLong(),
                side.name(),
                client.player.age);

        if (claim.replacedPriorOwner() && client.interactionManager.isBreakingBlock()) {
            client.interactionManager.cancelBlockBreaking();
        }

        boolean accepted = claim.started()
                ? client.interactionManager.attackBlock(target, side)
                : client.interactionManager.updateBlockBreakingProgress(target, side);
        if (accepted) client.player.swingHand(Hand.MAIN_HAND);
        return new BreakFrame(
                claim.started(),
                accepted,
                client.interactionManager.isBreakingBlock(),
                client.interactionManager.getBlockBreakingProgress(),
                claim.generation(),
                claim.started() ? "started pinned direct break" : "continued pinned direct break");
    }

    /** Cancels only the exact operation that still owns the direct breaker. */
    public synchronized boolean cancelBlockBreak(String operationId) {
        if (!blockBreakLease.release(operationId)) return false;
        cancelMinecraftBreak();
        return true;
    }

    /** Cancels only the exact generation represented by the caller's capability. */
    public synchronized boolean cancelBlockBreak(String operationId, long generation) {
        if (!blockBreakLease.release(operationId, generation)) return false;
        cancelMinecraftBreak();
        return true;
    }

    /** Returns one vetoed protected/resource mutation for cancellation and telemetry. */
    public synchronized Optional<BlockedBreakAttempt> takeBlockedBreakAttempt() {
        BlockedBreakAttempt result = blockedBreakAttempt;
        blockedBreakAttempt = null;
        return Optional.ofNullable(result);
    }

    /** Returns one exact non-break veto for a controller's terminal result. */
    public synchronized Optional<BlockedInteractionAttempt> takeBlockedInteractionAttempt() {
        BlockedInteractionAttempt result = blockedInteractionAttempt;
        blockedInteractionAttempt = null;
        return Optional.ofNullable(result);
    }

    /** Returns one portal interaction observed before Minecraft handled it. */
    public synchronized Optional<PortalInteractionAttempt> takePortalInteractionAttempt() {
        PortalInteractionAttempt result = portalInteractionAttempt;
        portalInteractionAttempt = null;
        return Optional.ofNullable(result);
    }

    /** Reads one loaded portal without changing it. */
    public synchronized Optional<PortalState> inspectPortal(BlockPos position) {
        if (client.world == null || position == null) return Optional.empty();
        return portalState(position);
    }

    /** Queues observation only; the existing saved passage transaction still owns permission and use. */
    public synchronized void observeTraversalPortal(BlockPos position) {
        if(client.player==null || portalInteractionAttempt!=null) return;
        PortalState portal=inspectPortal(position).orElse(null);
        if(portal!=null && !portal.open()) observePortalInteraction(portal);
    }

    /** Enables save-before-use portal interception while Entity owns route movement. */
    public synchronized void setPortalPassageFence(boolean enabled) {
        portalPassageFence = enabled;
        if (!enabled) portalInteractionAttempt = null;
    }

    /** Read-only exact-coordinate policy probe used after a route calculation fails. */
    public synchronized Optional<ProtectedAreaPolicy.Decision> inspectProtectedArea(
            ProtectedAreaPolicy.Action action,
            String dimension,
            int x,
            int y,
            int z) {
        if (protectedAreas == null) return Optional.empty();
        return Optional.of(protectedAreas.decide(
                Objects.requireNonNull(action, "action"),
                dimension, x, y, z, false));
    }

    /**
     * Executes one item interaction while carrying its immutable exact Home
     * operation through the nested interaction-manager mixin. Paper's permit is
     * attached when the target is inside protected property.
     */
    public synchronized ActionResult interactItemWithExactHomeAuthorization(
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            Hand hand) {
        Objects.requireNonNull(action, "action");
        List<ProtectedAreaHomePermit.Target> exactTargets = List.copyOf(targets);
        Objects.requireNonNull(hand, "hand");
        blockedInteractionAttempt = null;
        if (client.player == null || client.world == null
                || client.interactionManager == null || protectedAreas == null) {
            return ActionResult.FAIL;
        }
        long now = System.currentTimeMillis();
        ProtectedAreaClientState.Status status = protectedAreas.status();
        Optional<ProtectedAreaClientState.ExactHomeCapability> capability =
                protectedAreas.grantedExactHomeCapability(action, exactTargets, now);
        List<ProtectedAreaHomePermit.Target> protectedTargets = new ArrayList<>();
        for (ProtectedAreaHomePermit.Target target : exactTargets) {
            ProtectedAreaPolicy.Decision ordinary = protectedAreas.decide(
                    action, target.dimension(), target.x(), target.y(), target.z(), false);
            if (ordinary.allowed()) continue;
            protectedTargets.add(target);
            if (capability.isEmpty()) {
                blockedInteractionAttempt = new BlockedInteractionAttempt(
                        action, target.dimension(), target.x(), target.y(), target.z(),
                        "exact_home_capability_missing",
                        "Paper-granted exact Home capability was absent at the actuator "
                                + "boundary for " + action.name().toLowerCase()
                                + " at " + target.x() + " " + target.y() + " " + target.z()
                                + " in " + target.dimension());
                return ActionResult.FAIL;
            }
        }
        // Carry the exact operation through the nested interaction mixin even
        // when no Paper permit is needed outside protected property. The
        // independent Home route-cleanliness fence still requires the exact
        // action/target scope before it will admit Home-owned maintenance.
        exactHomeInteraction = capability.orElse(null);
        exactHomeOperation = new ExactHomeOperationScope(
                action, exactTargets, protectedTargets,
                status.revision(), status.digest(), capability.orElse(null));
        try {
            return client.interactionManager.interactItem(client.player, hand);
        } finally {
            exactHomeOperation = null;
            exactHomeInteraction = null;
        }
    }

    /** Executes one exact portal toggle under a Paper-acknowledged passage capability. */
    public synchronized ActionResult interactPortalWithExactAuthorization(
            ProtectedAreaHomePermit.Target target,
            String stableFingerprint,
            Hand hand,
            BlockHitResult hit) {
        Objects.requireNonNull(target, "target");
        String expectedFingerprint = Objects.requireNonNullElse(
                stableFingerprint, "").trim();
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(hit, "hit");
        blockedInteractionAttempt = null;
        if (client.player == null || client.world == null
                || client.interactionManager == null || protectedAreas == null) {
            return ActionResult.FAIL;
        }
        long now = System.currentTimeMillis();
        List<ProtectedAreaHomePermit.Target> exactTargets = List.of(target);
        PortalState portal = portalState(hit.getBlockPos()).orElse(null);
        if (portal == null
                || !portal.dimension().equals(target.dimension())
                || portal.x() != target.x() || portal.y() != target.y()
                || portal.z() != target.z()
                || !dev.entity.client.autonomy.policy.DoorPassageSession.samePortalFingerprint(portal.stableFingerprint(), expectedFingerprint)) {
            blockedInteractionAttempt = new BlockedInteractionAttempt(
                    ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                    target.dimension(), target.x(), target.y(), target.z(),
                    "portal_identity_mismatch",
                    "loaded portal identity changed before the exact interaction");
            return ActionResult.FAIL;
        }
        // Sneak-use can skip a door's onUse and place the held block instead. A route
        // pause releases sneak; wait for that physical state before an exact toggle.
        if (client.player.isSneaking()) return ActionResult.FAIL;
        Optional<ProtectedAreaClientState.ExactHomeCapability> capability =
                protectedAreas.grantedExactPassageCapability(exactTargets, now);
        boolean protectedTarget = !protectedAreas.decide(
                ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                target.dimension(), target.x(), target.y(), target.z(), false).allowed();
        if (protectedTarget && capability.isEmpty()) {
            blockedInteractionAttempt = new BlockedInteractionAttempt(
                    ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                    target.dimension(), target.x(), target.y(), target.z(),
                    "exact_capability_missing",
                    "Paper-granted reversible-passage capability was absent at the actuator boundary");
            return ActionResult.FAIL;
        }
        exactHomeInteraction = protectedTarget ? capability.orElseThrow() : null;
        portalInteractionScope = new PortalInteractionScope(
                target.dimension(), target.x(), target.y(), target.z(), expectedFingerprint);
        try {
            return client.interactionManager.interactBlock(client.player, hand, hit);
        } finally {
            portalInteractionScope = null;
            exactHomeInteraction = null;
        }
    }

    public synchronized ProtectedAreaClientState.HomeAuthorization
            requestExactPassageAuthorization(
            String operationId,
            String authorityId,
            ProtectedAreaHomePermit.Target target,
            long nowMillis) {
        if (protectedAreas == null) {
            return new ProtectedAreaClientState.HomeAuthorization(
                    ProtectedAreaClientState.HomeAuthorizationState.DENIED,
                    "protected-area state is unavailable");
        }
        return protectedAreas.requestExactPassageAuthorization(
                operationId, authorityId, List.of(target), nowMillis);
    }

    public synchronized void completeExactAuthorization(String operationId) {
        if (protectedAreas != null) {
            protectedAreas.completeExactHomeAuthorization(operationId);
        }
    }

    /**
     * Holds use for Minecraft's continuous-use lifecycle while the mixin blocks
     * duplicate calls to {@code doItemUse}. The controller itself sends the
     * one explicit start interaction and verifies the resulting world state.
     */
    public synchronized boolean holdUse(String operationId) {
        return holdUseFrame(operationId).started();
    }

    /**
     * Claims or heartbeats one held-use generation. Controllers that issue the
     * explicit start interaction must restart it whenever this frame reports a
     * new generation, including after focus stalls and owner preemption.
     */
    public synchronized UseFrame holdUseFrame(String operationId) {
        if (client.player == null || client.world == null || client.options == null) {
            if (heldUseLease.clear()) cancelMinecraftUse();
            return new UseFrame(false, false, false, 0L,
                    "waiting for a loaded use context");
        }
        String dimension = currentDimension();
        if (heldUseLease.expireUnlessFresh(
                dimension,
                client.player.age,
                VANILLA_SUPPRESSION_AGE_TICKS) == DirectUseLease.Freshness.EXPIRED) {
            cancelMinecraftUse();
        }
        DirectUseLease.Claim claim = heldUseLease.claim(
                operationId, dimension, client.player.age);
        if (claim.replacedPriorOwner()) cancelMinecraftUse();
        client.options.useKey.setPressed(true);
        return new UseFrame(true, claim.started(), claim.replacedPriorOwner(),
                claim.generation(),
                claim.started() ? "started held use" : "continued held use");
    }

    /** Releases only the exact held-use owner and its physical key state. */
    public synchronized boolean releaseHeldUse(String operationId) {
        if (!heldUseLease.release(operationId)) return false;
        cancelMinecraftUse();
        return true;
    }

    /** Releases only the exact generation represented by the caller's capability. */
    public synchronized boolean releaseHeldUse(String operationId, long generation) {
        if (!heldUseLease.release(operationId, generation)) return false;
        cancelMinecraftUse();
        return true;
    }

    /** Emergency/disconnect boundary. This is the only non-owner-specific cancellation. */
    public synchronized void neutralizeAll(String reason) {
        blockBreakLease.clear();
        heldUseLease.clear();
        blockedBreakAttempt = null;
        blockedInteractionAttempt = null;
        portalInteractionAttempt = null;
        portalInteractionScope = null;
        portalPassageFence = false;
        exactHomeOperation = null;
        exactHomeInteraction = null;
        cancelMinecraftBreak();
        cancelMinecraftUse();
    }

    public synchronized DirectBlockBreakLease.Snapshot blockBreakSnapshot() {
        return blockBreakLease.snapshot();
    }

    public synchronized DirectUseLease.Snapshot heldUseSnapshot() {
        return heldUseLease.snapshot();
    }

    private synchronized boolean hasFreshDirectBreak() {
        if (client.player == null || client.world == null) {
            if (!blockBreakLease.clear()) return false;
            cancelMinecraftBreak();
            // Consume the transition that discovered the stale action. Vanilla
            // may run on its next invocation, after the exact break is neutral.
            return true;
        }
        DirectBlockBreakLease.Freshness freshness = blockBreakLease.expireUnlessFresh(
                currentDimension(), client.player.age, VANILLA_SUPPRESSION_AGE_TICKS);
        if (freshness == DirectBlockBreakLease.Freshness.FRESH) return true;
        if (freshness == DirectBlockBreakLease.Freshness.EXPIRED) {
            cancelMinecraftBreak();
            return true;
        }
        return false;
    }

    private synchronized boolean hasFreshHeldUse() {
        if (client.player == null || client.world == null) {
            if (!heldUseLease.clear()) return false;
            cancelMinecraftUse();
            // Suppress this already-triggered vanilla call; the stale bot key is
            // now released, so a later real physical press is admitted normally.
            return true;
        }
        DirectUseLease.Freshness freshness = heldUseLease.expireUnlessFresh(
                currentDimension(), client.player.age, VANILLA_SUPPRESSION_AGE_TICKS);
        if (freshness == DirectUseLease.Freshness.FRESH) return true;
        if (freshness == DirectUseLease.Freshness.EXPIRED) {
            cancelMinecraftUse();
            return true;
        }
        return false;
    }

    private synchronized boolean blockProtectedBreak(BlockPos target) {
        if (client.world == null) return false;
        if (blueprintScope != null && blueprintScope.includes(ProtectedAreaPolicy.Action.BREAK,
                currentDimension(),target)) {
            if (blueprintScope.breakAllowed(client,target)) return false;
            releasePhysicalAttackKey(); return true;
        }
        ProtectedBlock key = new ProtectedBlock(
                currentDimension(), target.getX(), target.getY(), target.getZ());
        if (protectedAreas != null) {
            var flower = MinecraftResourceObserver.supportedFlower(client.world.getBlockState(target));
            var footprint = SupportedFlowerHarvest.footprint(flower.category(),
                    new LoadedResourceClassifier.Point(target.getX(), target.getY(), target.getZ()));
            for (var affected : footprint) {
                ProtectedAreaPolicy.Decision decision = protectedAreas.decide(
                    ProtectedAreaPolicy.Action.BREAK,
                        key.dimension(), affected.x(), affected.y(), affected.z(), false);
                if (!decision.allowed() && !managedFarmBreakPermitted(key, target)) {
                    blockedBreakAttempt = new BlockedBreakAttempt(
                            key.dimension(), key.x(), key.y(), key.z(),
                            decision.code(), decision.detail());
                    releasePhysicalAttackKey();
                    return true;
                }
            }
        }
        return resourceBreakScope != null && blockResourceBreak(key, target);
    }

    /** Final loaded-world classifier for an exact scoped resource objective. */
    private boolean blockResourceBreak(ProtectedBlock key, BlockPos target) {
        ResourceBreakScope scope = resourceBreakScope;
        if (scope == null) return false;
        String blockId = Registries.BLOCK.getId(
                client.world.getBlockState(target).getBlock()).getPath();
        ResourceActuationSession.Coordinate coordinate =
                new ResourceActuationSession.Coordinate(
                        key.dimension(), key.x(), key.y(), key.z());
        boolean exactTarget = scope.exactTarget(coordinate, blockId);
        boolean committedTreeTarget = scope.committedNaturalTreeTarget(
                coordinate, blockId);
        boolean committedTreeAccessTarget = scope.committedNaturalTreeAccessTarget(
                coordinate, blockId);
        boolean authorizedHarvestTarget = exactTarget || committedTreeTarget
                || committedTreeAccessTarget;
        // Resource classification selects and revalidates the objective. It is
        // not property authority over Baritone's route to that objective. Every
        // incidental route break has already passed the synchronized protected
        // area, body-support, and known-route-support gates above.
        if (!scope.requiresResourceClassification(coordinate, blockId)) return false;

        if (protectedAreas == null) {
            return denyResourceBreak(
                    key, "policy_unsynchronized",
                    "Typed resource-area policy is unavailable at the actuator boundary");
        }
        ProtectedAreaPolicy.AreaKind requiredKind =
                scope.sourceKind() == ResourceActuationSession.SourceKind.MINE
                        ? ProtectedAreaPolicy.AreaKind.MINING
                        : ProtectedAreaPolicy.AreaKind.HARVESTING;
        ProtectedAreaPolicy.ResourceAuthority authority =
                protectedAreas.observeResourceAuthority(
                        requiredKind,
                        key.dimension(), key.x(), key.y(), key.z());
        boolean managedFarmCrop = managedFarmBreakPermitted(key, target);
        ResourceStewardshipPolicy.Intent intent = committedTreeAccessTarget
                ? ResourceStewardshipPolicy.Intent.HARVEST_TREE_ACCESS
                : authorizedHarvestTarget
                ? scope.targetIntent()
                : ResourceStewardshipPolicy.Intent.MINE;
        Set<String> retainedReplantItems = scope.reservedReplantItemIds().stream()
                .filter(this::carriesItem)
                .map(item -> item.contains(":") ? item : "minecraft:" + item)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        LoadedResourceClassifier.AdmissionContext context =
                new LoadedResourceClassifier.AdmissionContext(
                        protectedAreas.status().synchronizedPolicy(),
                        authority.allowed() || managedFarmCrop,
                        authority.code().equals("protected_overlap")
                                && !managedFarmCrop,
                        authorizedHarvestTarget
                                || scope.sourceKind()
                                == ResourceActuationSession.SourceKind.MINE,
                        false,
                        miningEntranceBodyCell(requiredKind, authority.areaName(), key),
                        false,
                        retainedReplantItems);
        ResourceStewardshipPolicy.Decision decision = ResourceStewardshipPolicy.decide(
                resourceObserver.observe(target, intent).facts(
                        context, scope.retainedNaturalTreeProof()
                                || committedTreeTarget
                                || committedTreeAccessTarget));
        if (!decision.allowed()) {
            return denyResourceBreak(key, decision.code().name().toLowerCase(
                    java.util.Locale.ROOT), decision.detail());
        }
        return false;
    }

    private boolean managedFarmBreakPermitted(
            ProtectedBlock key,
            BlockPos target) {
        ResourceBreakScope scope = resourceBreakScope;
        if (scope == null || !scope.managedFarmCropTarget()
                || client.world == null || protectedAreas == null) return false;
        String blockId = Registries.BLOCK.getId(
                client.world.getBlockState(target).getBlock()).getPath();
        ResourceActuationSession.Coordinate coordinate =
                new ResourceActuationSession.Coordinate(
                        key.dimension(), key.x(), key.y(), key.z());
        if (!scope.exactTarget(coordinate, blockId)
                || !managedFarmAreaBindingValid(scope, coordinate)) return false;
        LoadedResourceClassifier.Observation observed = resourceObserver.observe(
                target, ResourceStewardshipPolicy.Intent.HARVEST_CROP);
        return observed.observationComplete()
                && observed.targetCategory()
                == LoadedResourceClassifier.Category.SUPPORTED_CROP
                && observed.matureCrop()
                && observed.requiredCropReplantItem()
                .map(ResourceActuationSession::normalizeId)
                .filter(scope.reservedReplantItemIds()::contains)
                .filter(this::carriesItem)
                .isPresent();
    }

    private boolean managedFarmAreaBindingValid(
            ResourceBreakScope scope,
            ResourceActuationSession.Coordinate coordinate) {
        ProtectedAreaClientState.NamedResourceAreaObservation named =
                protectedAreas.observeNamedResourceArea(
                        ProtectedAreaPolicy.AreaKind.HARVESTING,
                        scope.managedFarmAreaName());
        if (!named.available() || named.area().isEmpty()) return false;
        ProtectedAreaPolicy.Area area = named.area().orElseThrow();
        return ManagedFarmPolicy.areaFingerprint(area).equals(
                scope.managedFarmAreaFingerprint())
                && area.contains(coordinate.dimension(), coordinate.x(), coordinate.z());
    }

    private boolean miningEntranceBodyCell(
            ProtectedAreaPolicy.AreaKind kind,
            String areaName,
            ProtectedBlock target) {
        if (kind != ProtectedAreaPolicy.AreaKind.MINING
                || protectedAreas == null || areaName == null || areaName.isBlank()) {
            return false;
        }
        return protectedAreas.observeResourceAreas(kind).areas().stream()
                .filter(area -> area.name().equals(areaName))
                .anyMatch(area -> area.entranceX() == target.x()
                        && area.entranceZ() == target.z()
                        && target.y() >= area.entranceY() - 1
                        && target.y() <= area.entranceY() + 1);
    }

    private boolean carriesItem(String itemId) {
        if (client.player == null) return false;
        String expected = ResourceActuationSession.normalizeId(itemId);
        ItemStack offhand = client.player.getOffHandStack();
        if (!offhand.isEmpty()
                && Registries.ITEM.getId(offhand.getItem()).getPath().equals(expected)) {
            return true;
        }
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.getHotbarSize(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty()
                    && Registries.ITEM.getId(stack.getItem()).getPath().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean denyResourceBreak(
            ProtectedBlock key,
            String code,
            String detail) {
        blockedBreakAttempt = new BlockedBreakAttempt(
                key.dimension(), key.x(), key.y(), key.z(), code, detail);
        releasePhysicalAttackKey();
        return true;
    }

    private synchronized boolean blockProtectedInteraction(
            Hand hand,
            BlockHitResult hit) {
        blockedInteractionAttempt = null;
        if (client.player == null || client.world == null) {
            return false;
        }
        PortalState portal = portalState(hit.getBlockPos()).orElse(null);
        boolean directPortalTransaction = portal != null && portalInteractionScope != null
                && portalInteractionScope.matches(portal);
        ItemStack stack = client.player.getStackInHand(hand);
        // A native main-hand use can PASS while walking toward its target, then
        // vanilla probes the empty offhand against the ground. Suppress that
        // unused hand before sending anything; it is not a failed construction
        // mutation. Main-hand placement, occupied offhand and portal transactions
        // retain their existing exact authority and rejection handling.
        if (!directPortalTransaction && blueprintScope != null
                && blueprintScope.suppressEmptyOffhandProbe(hand,stack)) return true;
        if(blueprintScope!=null && stack.getItem() instanceof BlockItem item
                && (item.getBlock() instanceof DoorBlock || item.getBlock() instanceof net.minecraft.block.BedBlock)
                && System.currentTimeMillis()-lastBlueprintUseTrace>=1000) {
            lastBlueprintUseTrace=System.currentTimeMillis();
            var placement=new ItemPlacementContext(client.player,hand,stack,hit);
            org.slf4j.LoggerFactory.getLogger("Entity2Blueprint").info(
                    "Actual build use at {} clicked={} side={} hit={} placing={} predicted={} sneaking={}",
                    client.player.getPos(),hit.getBlockPos(),hit.getSide(),hit.getPos(),
                    placement.getBlockPos(),item.getBlock().getPlacementState(placement),client.player.isSneaking());
        }
        // Native BuilderProcess can use a door's face to place an adjacent block.
        // Vanilla sneaking suppresses door activation: classify that click as
        // placement below, with unchanged exact-cell authority, not as passage.
        boolean sneakingBlueprintPlacement = !directPortalTransaction && blueprintScope != null
                && client.player.isSneaking() && stack.getItem() instanceof BlockItem;
        if (portal != null) {
            if (portalPassageFence && !directPortalTransaction && !sneakingBlueprintPlacement) {
                observePortalInteraction(portal);
                releasePhysicalUseKey();
                return true;
            }
        }
        if (protectedAreas == null) return false;
        // An exact passage transaction is a door toggle, even when the builder
        // carries planks. Do not reject it as placement in an adjacent design cell.
        if (!directPortalTransaction && blueprintScope != null && stack.getItem() instanceof BlockItem) {
            ItemPlacementContext placement = new ItemPlacementContext(client.player,hand,stack,hit);
            BlockPos placed = placement.getBlockPos();
            if (blueprintScope.includes(ProtectedAreaPolicy.Action.PLACE,currentDimension(),placed)) {
                if (blueprintScope.placeAllowed(client,placed,stack,placement)) return false;
                releasePhysicalUseKey(); return true;
            }
        }
        ProtectedAreaPolicy.Action action;
        List<BlockPos> positions = new ArrayList<>();
        ProtectedAreaHomePermit.Target clickedTarget = new ProtectedAreaHomePermit.Target(
                currentDimension(),
                hit.getBlockPos().getX(),
                hit.getBlockPos().getY(),
                hit.getBlockPos().getZ());
        if (directPortalTransaction) {
            // This scope belongs only to interactPortalWithExactAuthorization. Its
            // REVERSIBLE_PASSAGE capability is not a HOME_MAINTENANCE grant, and a
            // carried dirt block must not turn this exact door toggle into PLACE.
            // The action/coordinate/purpose capability is still checked below.
            action = ProtectedAreaPolicy.Action.BLOCK_INTERACT;
            positions.add(new BlockPos(portal.x(), portal.y(), portal.z()));
        } else if (protectedAreas.hasGrantedExactHomeAuthorization(
                ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                List.of(clickedTarget),
                System.currentTimeMillis())) {
            action = ProtectedAreaPolicy.Action.BLOCK_INTERACT;
            positions.add(hit.getBlockPos().toImmutable());
        } else if (client.world.getBlockState(hit.getBlockPos())
                .createScreenHandlerFactory(client.world, hit.getBlockPos()) != null) {
            action = ProtectedAreaPolicy.Action.CONTAINER;
            positions.add(hit.getBlockPos().toImmutable());
        } else if (stack.getItem() instanceof BlockItem) {
            action = ProtectedAreaPolicy.Action.PLACE;
            positions.add(new ItemPlacementContext(
                    client.player, hand, stack, hit).getBlockPos().toImmutable());
        } else if (stack.getItem() instanceof BucketItem) {
            action = ProtectedAreaPolicy.Action.FLUID;
            positions.add(stack.isOf(Items.BUCKET)
                    ? hit.getBlockPos().toImmutable()
                    : hit.getBlockPos().offset(hit.getSide()).toImmutable());
        } else {
            action = ProtectedAreaPolicy.Action.BLOCK_INTERACT;
            positions.add(hit.getBlockPos().toImmutable());
        }
        if (action == ProtectedAreaPolicy.Action.PLACE
                && managedFarmReplantPermitted(stack, positions)) return false;
        return blockProtectedInteraction(action, positions);
    }

    private boolean managedFarmReplantPermitted(
            ItemStack stack,
            List<BlockPos> positions) {
        ResourceBreakScope scope = resourceBreakScope;
        if (scope == null || !scope.managedFarmCropTarget()
                || stack == null || stack.isEmpty()
                || client.world == null || protectedAreas == null
                || positions.size() != 1) return false;
        BlockPos position = positions.getFirst();
        ResourceActuationSession.Coordinate coordinate =
                new ResourceActuationSession.Coordinate(
                        currentDimension(), position.getX(), position.getY(), position.getZ());
        String item = ResourceActuationSession.normalizeId(
                Registries.ITEM.getId(stack.getItem()).toString());
        return coordinate.equals(scope.exactTarget())
                && scope.reservedReplantItemIds().contains(item)
                && managedFarmAreaBindingValid(scope, coordinate)
                && client.world.getBlockState(position).isAir()
                && client.world.getBlockState(position.down()).isOf(Blocks.FARMLAND);
    }

    private void observePortalInteraction(PortalState portal) {
        portalInteractionAttempt = new PortalInteractionAttempt(
                portal.dimension(), portal.x(), portal.y(), portal.z(),
                portal.kind(), portal.blockId(), portal.stableFingerprint(),
                portal.open(), portal.normalX(), portal.normalY(), portal.normalZ(),
                portal.height(),
                client.player.getX(), client.player.getY(), client.player.getZ());
    }

    private Optional<PortalState> portalState(BlockPos clicked) {
        BlockPos position = clicked.toImmutable();
        BlockState state = client.world.getBlockState(position);
        if (state.isOf(Blocks.IRON_DOOR) || state.isOf(Blocks.IRON_TRAPDOOR)) {
            return Optional.empty();
        }
        String kind;
        Direction normal;
        int height;
        if (state.getBlock() instanceof DoorBlock) {
            if (state.contains(DoorBlock.HALF)
                    && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                position = position.down();
                state = client.world.getBlockState(position);
            }
            if (!(state.getBlock() instanceof DoorBlock)
                    || !state.contains(Properties.OPEN)
                    || !state.contains(Properties.HORIZONTAL_FACING)) {
                return Optional.empty();
            }
            kind = "door";
            normal = state.get(Properties.HORIZONTAL_FACING);
            height = 2;
        } else if (state.getBlock() instanceof FenceGateBlock) {
            if (!state.contains(Properties.OPEN)
                    || !state.contains(Properties.HORIZONTAL_FACING)) {
                return Optional.empty();
            }
            kind = "fence_gate";
            normal = state.get(Properties.HORIZONTAL_FACING);
            height = 1;
        } else if (state.getBlock() instanceof TrapdoorBlock) {
            if (!state.contains(Properties.OPEN)) return Optional.empty();
            kind = "trapdoor";
            normal = Direction.UP;
            height = 1;
        } else {
            return Optional.empty();
        }
        return Optional.of(new PortalState(
                currentDimension(), position.getX(), position.getY(), position.getZ(),
                kind,
                Registries.BLOCK.getId(state.getBlock()).toString(),
                stablePortalFingerprint(state),
                state.get(Properties.OPEN),
                normal.getOffsetX(), normal.getOffsetY(), normal.getOffsetZ(),
                height));
    }

    private static String stablePortalFingerprint(BlockState state) {
        StringBuilder fingerprint = new StringBuilder(
                Registries.BLOCK.getId(state.getBlock()).toString());
        state.getEntries().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(Properties.OPEN))
                .sorted(java.util.Comparator.comparing(entry -> entry.getKey().getName()))
                .forEach(entry -> fingerprint.append('|')
                        .append(entry.getKey().getName()).append('=')
                        .append(entry.getValue()));
        return fingerprint.toString();
    }

    private synchronized boolean blockProtectedItemInteraction(Hand hand) {
        blockedInteractionAttempt = null;
        if (client.player == null || client.world == null || protectedAreas == null) {
            return false;
        }
        if (!(client.player.getStackInHand(hand).getItem() instanceof BucketItem)) return false;
        // MinecraftClient#crosshairTarget belongs to the preceding render frame and can
        // still name a neighbouring water cell after Entity changes yaw in this tick.
        // BucketItem#use raycasts synchronously from the current rotation with SOURCE_ONLY,
        // so the safety decision must mirror that physical ray instead of trusting the
        // stale rendered crosshair.
        Vec3d start = client.player.getEyePos();
        Vec3d rotation = client.player.getRotationVector(
                client.player.getPitch(), client.player.getYaw());
        Vec3d end = start.add(rotation.multiply(client.player.getBlockInteractionRange()));
        BlockHitResult hit = client.world.raycast(new RaycastContext(
                start, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.SOURCE_ONLY, client.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            ProtectedAreaClientState.Status status = protectedAreas.status();
            boolean blocked = !status.synchronizedPolicy() || status.areaCount() > 0;
            if (blocked) {
                releasePhysicalUseKey();
                blockedInteractionAttempt = new BlockedInteractionAttempt(
                        ProtectedAreaPolicy.Action.FLUID,
                        currentDimension(), 0, 0, 0,
                        "unproved_target",
                        "Bucket use has no proved block target while protected-area "
                                + "mutation authority is constrained");
            }
            return blocked;
        }
        return blockProtectedInteraction(hand, hit);
    }

    private boolean blockProtectedInteraction(
            ProtectedAreaPolicy.Action action,
            List<BlockPos> positions) {
        String dimension = currentDimension();
        List<ProtectedAreaHomePermit.Target> targets = positions.stream()
                .distinct()
                .map(position -> new ProtectedAreaHomePermit.Target(
                        dimension,
                        position.getX(), position.getY(), position.getZ()))
                .toList();
        long now = System.currentTimeMillis();
        ProtectedAreaClientState.Status status = protectedAreas.status();
        Optional<String> scopedMismatch = exactHomeOperation != null
                ? exactHomeOperation.mismatch(action, targets, status, now)
                : exactHomeInteraction == null
                        ? Optional.empty()
                        : exactHomeInteraction.mismatch(action, targets, status, now);
        boolean exactPassage = exactHomeInteraction != null
                && exactHomeInteraction.purpose()
                == ProtectedAreaHomePermit.Purpose.REVERSIBLE_PASSAGE
                && scopedMismatch.isEmpty();
        boolean exactHome = scopedMismatch.isEmpty()
                && protectedAreas.hasGrantedExactHomeAuthorization(action, targets, now);
        for (ProtectedAreaHomePermit.Target target : targets) {
            ProtectedAreaPolicy.Decision decision = exactPassage
                    ? protectedAreas.decideReversiblePassage(
                            target.dimension(), target.x(), target.y(), target.z())
                    : protectedAreas.decide(
                            action,
                            target.dimension(), target.x(), target.y(), target.z(),
                            exactHome);
            if (decision.allowed()) continue;
            blockedInteractionAttempt = new BlockedInteractionAttempt(
                    action,
                    target.dimension(), target.x(), target.y(), target.z(),
                    scopedMismatch.isPresent()
                            ? "exact_home_capability_mismatch"
                            : decision.code(),
                    scopedMismatch.map(reason ->
                                    "Exact Home capability mismatch at the Minecraft item-use "
                                            + "boundary (" + reason + "); " + decision.detail())
                            .orElse(decision.detail()));
            releasePhysicalUseKey();
            return true;
        }
        return false;
    }

    private void cancelMinecraftBreak() {
        releasePhysicalAttackKey();
        if (client.interactionManager != null && client.interactionManager.isBreakingBlock()) {
            client.interactionManager.cancelBlockBreaking();
        }
    }

    private void releasePhysicalAttackKey() {
        if (client.options != null) client.options.attackKey.setPressed(false);
    }

    private void releasePhysicalUseKey() {
        if (client.options != null) client.options.useKey.setPressed(false);
    }

    private void cancelMinecraftUse() {
        releasePhysicalUseKey();
        try {
            if (client.player != null && client.interactionManager != null
                    && client.player.isUsingItem()) {
                client.interactionManager.stopUsingItem(client.player);
            }
        } catch (RuntimeException ignored) {
            // Cleanup must never leave a logical lease alive just because the
            // Minecraft interaction context disappeared mid-transition.
        }
    }

    private String currentDimension() {
        return client.world.getRegistryKey().getValue().toString();
    }

    public record BreakFrame(
            boolean started,
            boolean accepted,
            boolean stillBreaking,
            int progress,
            long generation,
            String detail) {
    }

    public record UseFrame(
            boolean active,
            boolean started,
            boolean replacedPriorOwner,
            long generation,
            String detail) {
    }

    public record BlockedBreakAttempt(
            String dimension,
            int x,
            int y,
            int z,
            String code,
            String detail) {
    }

    public record BlockedInteractionAttempt(
            ProtectedAreaPolicy.Action action,
            String dimension,
            int x,
            int y,
            int z,
            String code,
            String detail) {
    }

    public record PortalInteractionAttempt(
            String dimension,
            int x,
            int y,
            int z,
            String kind,
            String blockId,
            String stableFingerprint,
            boolean openBefore,
            int normalX,
            int normalY,
            int normalZ,
            int height,
            double playerX,
            double playerY,
            double playerZ) {
    }

    public record PortalState(
            String dimension,
            int x,
            int y,
            int z,
            String kind,
            String blockId,
            String stableFingerprint,
            boolean open,
            int normalX,
            int normalY,
            int normalZ,
            int height) {
    }

    private record PortalInteractionScope(
            String dimension,
            int x,
            int y,
            int z,
            String stableFingerprint) {
        private boolean matches(PortalState state) {
            return dimension.equals(state.dimension())
                    && x == state.x() && y == state.y() && z == state.z()
                    && dev.entity.client.autonomy.policy.DoorPassageSession.samePortalFingerprint(stableFingerprint, state.stableFingerprint());
        }

    }

    /**
     * One synchronous, exact Home action. Paper supplies the nested capability
     * for targets inside protected property; outside it, the unchanged policy
     * identity plus exact action/target scope is sufficient because ordinary
     * protected-area policy already permits the mutation.
     */
    private record ExactHomeOperationScope(
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            List<ProtectedAreaHomePermit.Target> protectedTargets,
            long revision,
            String digest,
            ProtectedAreaClientState.ExactHomeCapability capability) {
        private ExactHomeOperationScope {
            action = Objects.requireNonNull(action, "action");
            targets = List.copyOf(targets);
            protectedTargets = List.copyOf(protectedTargets);
            digest = Objects.requireNonNull(digest, "digest");
        }

        private Optional<String> mismatch(
                ProtectedAreaPolicy.Action attemptedAction,
                List<ProtectedAreaHomePermit.Target> attemptedTargets,
                ProtectedAreaClientState.Status status,
                long nowMillis) {
            if (action != attemptedAction) {
                return Optional.of("action expected=" + action + " actual=" + attemptedAction);
            }
            if (attemptedTargets == null || attemptedTargets.isEmpty()) {
                return Optional.of("no exact attempted target");
            }
            if (!targets.containsAll(attemptedTargets)) {
                return Optional.of("target expected=" + targets + " actual=" + attemptedTargets);
            }
            if (status == null || !status.synchronizedPolicy()) {
                return Optional.of("policy became unsynchronized");
            }
            if (status.revision() != revision || !status.digest().equals(digest)) {
                return Optional.of("policy identity changed from revision " + revision
                        + " to " + status.revision());
            }
            if (protectedTargets.isEmpty()) return Optional.empty();
            if (capability == null) {
                return Optional.of("Paper capability missing for protected target");
            }
            return capability.mismatch(action, protectedTargets, status, nowMillis);
        }
    }

    private record ProtectedBlock(String dimension, int x, int y, int z) {
        private ProtectedBlock {
            dimension = Objects.requireNonNullElse(dimension, "").trim();
            if (dimension.isBlank()) {
                throw new IllegalArgumentException("dimension is required");
            }
        }
    }
}
