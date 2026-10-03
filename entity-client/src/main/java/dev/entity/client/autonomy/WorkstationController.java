package dev.entity.client.autonomy;

import dev.entity.client.baritone.FabricBaritonePort;
import dev.entity.client.autonomy.workspace.ClientWorkspacePreparationController;
import dev.entity.client.autonomy.workspace.MinecraftWorkspaceProbe;
import dev.entity.client.autonomy.policy.AtomicFieldKitStore;
import dev.entity.client.autonomy.policy.FieldKitAssetPolicy;
import dev.entity.client.autonomy.policy.FieldKitLedger;
import dev.entity.client.autonomy.policy.FieldKitStore;
import dev.entity.client.autonomy.policy.HomeAssetLayoutPolicy;
import dev.entity.client.autonomy.policy.HomeEconomySession;
import dev.entity.client.autonomy.policy.HomeEconomyPolicy;
import dev.entity.client.autonomy.policy.HomeFurnitureBindingPolicy;
import dev.entity.client.autonomy.policy.HomeSleepPolicy;
import dev.entity.client.autonomy.policy.HomeChestIsolationPolicy;
import dev.entity.client.autonomy.policy.HomePinnedScreenPolicy;
import dev.entity.client.autonomy.policy.HomeWaterFillPolicy;
import dev.entity.client.autonomy.policy.WorkstationReclaimAccessPlanner;
import dev.entity.client.autonomy.policy.WorkstationReclaimAccessPlanner.Cell;
import dev.entity.client.autonomy.policy.WorkstationReclaimAccessPlanner.Point;
import dev.entity.client.autonomy.policy.WorkstationReclaimRecoveryPolicy;
import dev.entity.client.autonomy.policy.WorkstationPlacementConfirmationPolicy;
import dev.entity.client.protection.ProtectedAreaClientState;
import dev.entity.client.control.MinecraftActuatorGateway;
import dev.entity.core.control.ControlLease;
import dev.entity.core.port.BaritonePort;
import dev.entity.core.stewardship.ProtectedAreaHomePermit;
import dev.entity.core.stewardship.ProtectedAreaPolicy;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BedBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FluidDrainable;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Session-based workstation interaction. Distance alone never counts as
 * success: each session chooses a stand position with a real sightline,
 * confirms placements from world state, and confirms the correct screen
 * handler before handing control to an inventory transaction.
 */
public final class WorkstationController {
    private static final int SEARCH_RADIUS = 12;
    private static final int PLACEMENT_RADIUS = 5;
    private static final int STAND_SEARCH_RADIUS = 4;
    private static final int READY_STAND_MIN_Y = -4;
    private static final int READY_STAND_MAX_Y = 3;
    private static final int HOME_NEARBY_HORIZONTAL_RADIUS = 8;
    private static final int HOME_NEARBY_VERTICAL_RADIUS = 4;
    private static final int HOME_MAX_SURFACE_VERTICAL_DELTA = 64;
    private static final double INTERACT_DISTANCE_SQUARED = 4.25 * 4.25;
    private static final long INTERACTION_INTERVAL_MILLIS = 300L;
    private static final long SCREEN_CONFIRM_MILLIS = 1_500L;
    private static final long PLACEMENT_CONFIRM_MILLIS = 1_500L;
    private static final long SLEEP_CONFIRM_MILLIS = 3_000L;
    private static final int MAX_LOCAL_FAILURES = 10;
    private static final int HOME_MAX_PHYSICAL_REPAIR_ATTEMPTS = 2;
    private static final long HOME_REPAIR_WINDOW_MILLIS = 30_000L;
    // Native GoalNear(range=0) admits the full integer feet cell, not a centered quarter-cell.
    private static final double[] WATER_STAND_STOP_OFFSETS = {
            -HomeWaterFillPolicy.NATIVE_GOAL_CELL_OFFSET, HomeWaterFillPolicy.NATIVE_GOAL_CELL_OFFSET};
    private static final Set<Block> LOCAL_HAZARDS = Set.of(
            Blocks.FIRE,
            Blocks.SOUL_FIRE,
            Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE,
            Blocks.MAGMA_BLOCK,
            Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.POWDER_SNOW,
            Blocks.WITHER_ROSE);

    private final MinecraftClient client;
    private final dev.entity.client.autonomy.resource.MinecraftResourcePerception resourcePerception;
    private final ClientInventoryController inventory;
    private final AtomicBlockBreakController atomicBreak;
    private final ClientWorkspacePreparationController workspace;
    private final FieldKitLedger fieldKit;
    private final WorkstationReclaimAccessPlanner reclaimAccessPlanner =
            new WorkstationReclaimAccessPlanner();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private ProtectedAreaClientState protectedAreas;
    private String renewableWaterSearchCacheKey = "";
    private Optional<RenewableWaterSource> renewableWaterSearchCache = Optional.empty();
    private BlockPos lastPlacement;

    public WorkstationController(
            MinecraftClient client,
            ClientInventoryController inventory,
            FabricBaritonePort baritone) {
        this(client, inventory, baritone, transientFieldKit());
    }

    public WorkstationController(
            MinecraftClient client,
            ClientInventoryController inventory,
            FabricBaritonePort baritone,
            Path dataDirectory) throws IOException {
        this(client, inventory, baritone, new FieldKitLedger(new AtomicFieldKitStore(
                Objects.requireNonNull(dataDirectory, "dataDirectory").resolve("field-kit.bin"))));
    }

    private WorkstationController(
            MinecraftClient client,
            ClientInventoryController inventory,
            FabricBaritonePort baritone,
            FieldKitLedger fieldKit) {
        this.client = Objects.requireNonNull(client, "client");
        this.resourcePerception = Objects.requireNonNull(baritone, "baritone").resourcePerception();
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.fieldKit = Objects.requireNonNull(fieldKit, "fieldKit");
        this.atomicBreak = new AtomicBlockBreakController(client);
        this.workspace = new ClientWorkspacePreparationController(
                client, inventory, baritone, atomicBreak);
    }

    public void installProtectedAreaPolicy(ProtectedAreaClientState protectedAreas) {
        this.protectedAreas = Objects.requireNonNull(protectedAreas, "protectedAreas");
    }

    public Result tickOpen(
            Kind kind,
            String operationId,
            ControlLease lease,
            FabricBaritonePort baritone,
            long nowMillis) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.opensScreen()) {
            throw new IllegalArgumentException(
                    kind + " is a place/use Home asset, not a workstation screen");
        }
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(baritone, "baritone");
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Session session = sessions.compute(operationId, (id, existing) ->
                existing == null || existing.kind != kind ? new Session(id, kind) : existing);
        hydrateFieldKit(session);
        if (client.player == null || client.world == null || client.interactionManager == null) {
            session.clearAim();
            return new Result(State.WAITING, "waiting for Minecraft world/player", null);
        }
        resetTransientGeometryOnDimensionChange(session);
        if (!preferCarriedFieldKit(session, nowMillis)) {
            return new Result(State.BLOCKED, session.persistenceFailure, session.ownedPlacement);
        }
        retryDurableCommit(session, nowMillis);
        if (!session.persistenceFailure.isBlank()) {
            return new Result(State.BLOCKED, session.persistenceFailure, session.ownedPlacement);
        }
        if (session.reclaimRecoveryRequired) {
            Result recovery = tickReclaim(kind, operationId, lease, baritone, nowMillis);
            if (recovery.state() != State.RECLAIMED) return recovery;
        }
        if (session.ownedPlacement != null && !ownedInCurrentDimension(session)) {
            return new Result(
                    State.BLOCKED,
                    "Entity's field-kit " + kind.displayName() + " remains in "
                            + session.ownedDimension
                            + "; return Entity to that dimension before resuming this operation",
                    session.ownedPlacement);
        }
        if (session.ownedPlacement != null && !isLoaded(session.ownedPlacement)) {
            return approachToLoad(
                    operationId + ":load-owned:" + session.ownedPlacement.asLong(),
                    session.ownedPlacement,
                    "owned " + kind.displayName(),
                    lease,
                    baritone,
                    session);
        }
        if (verifiedOpenScreen(kind, session, nowMillis)) {
            session.openAttemptAt = 0;
            session.placementAttemptAt = 0;
            session.placementInventoryBaseline = -1;
            session.placementEvidenceAt = 0L;
            session.failures = 0;
            return new Result(State.OPEN, "verified open " + kind.displayName(), session.target);
        }

        Result placementConfirmation = confirmPendingPlacement(session, nowMillis);
        if (placementConfirmation != null) return placementConfirmation;

        if (session.target != null && !isLoaded(session.target)) {
            return approachToLoad(
                    operationId + ":load-target:" + session.target.asLong(),
                    session.target,
                    kind.displayName(),
                    lease,
                    baritone,
                    session);
        }
        if (session.target != null && (!kind.matches(client.world.getBlockState(session.target))
                || !session.homePinned && session.ownedPlacement == null
                && !ordinaryWorkstationAllowed(ProtectedAreaPolicy.Action.CONTAINER, session.target))) {
            if (session.homePinned && session.ownedPlacement != null
                    && session.target.equals(session.ownedPlacement)) {
                return new Result(State.BLOCKED,
                        "pinned home " + kind.displayName()
                                + " is missing at its verified ledger position",
                        session.ownedPlacement);
            }
            session.reject(session.target);
            session.target = null;
            session.standAt = null;
        }

        if (session.target == null) {
            Optional<Target> selected = session.homePinned
                    ? Optional.empty()
                    : selectExistingTarget(kind, session.rejected);
            if (selected.isPresent()) {
                Target target = selected.orElseThrow();
                session.target = target.block();
                session.standAt = target.standAt();
            } else {
                return place(kind, operationId, lease, baritone, session, nowMillis);
            }
        }
        return approachAndOpen(kind, operationId, lease, baritone, session, nowMillis);
    }

    /**
     * Opens or places one exact generation-scoped home asset. Nearby world
     * stations and ordinary FieldKit assets are never candidates.
     */
    public Result tickOpenPinned(
            Kind kind,
            String operationId,
            String ledgerAssetId,
            HomeEconomySession.HomeAnchor home,
            int maximumRadius,
            ControlLease lease,
            FabricBaritonePort baritone,
            long nowMillis) {
        PinnedSession pinned = configurePinnedHomeSession(
                kind, operationId, ledgerAssetId, home, maximumRadius);
        if (pinned.failure() != null) return pinned.failure();
        return tickOpen(kind, operationId, lease, baritone, nowMillis);
    }

    /** Places and durably verifies an exact Home asset without requiring a GUI. */
    public Result tickPlacePinned(
            Kind kind,
            String operationId,
            String ledgerAssetId,
            HomeEconomySession.HomeAnchor home,
            int maximumRadius,
            ControlLease lease,
            FabricBaritonePort baritone,
            long nowMillis) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(baritone, "baritone");
        PinnedSession pinned = configurePinnedHomeSession(
                kind, operationId, ledgerAssetId, home, maximumRadius);
        if (pinned.failure() != null) return pinned.failure();
        Session session = pinned.session();
        hydrateFieldKit(session);
        if (client.player == null || client.world == null || client.interactionManager == null) {
            session.clearAim();
            return new Result(State.WAITING, "waiting for Minecraft world/player", null);
        }
        resetTransientGeometryOnDimensionChange(session);
        if (!preferCarriedFieldKit(session, nowMillis)) {
            return new Result(State.BLOCKED, session.persistenceFailure, session.ownedPlacement);
        }
        retryDurableCommit(session, nowMillis);
        if (!session.persistenceFailure.isBlank()) {
            return new Result(State.BLOCKED, session.persistenceFailure, session.ownedPlacement);
        }
        if (session.ownedPlacement != null && !ownedInCurrentDimension(session)) {
            return new Result(State.BLOCKED,
                    "Entity's owned Home " + kind.displayName() + " remains in "
                            + session.ownedDimension,
                    session.ownedPlacement);
        }
        if (session.ownedPlacement != null && !isLoaded(session.ownedPlacement)) {
            return approachToLoad(
                    operationId + ":load-owned:" + session.ownedPlacement.asLong(),
                    session.ownedPlacement, "owned " + kind.displayName(),
                    lease, baritone, session);
        }
        Result placementConfirmation = confirmPendingPlacement(session, nowMillis);
        if (placementConfirmation != null) {
            if (session.ownedPlacement != null && pinnedTargetVerified(session)) {
                return new Result(State.PLACED,
                        "verified exact owned Home " + kind.displayName(),
                        session.ownedPlacement);
            }
            return placementConfirmation;
        }
        if (session.ownedPlacement != null) {
            if (!validOwnedBlock(kind, session.ownedPlacement)) {
                return new Result(State.BLOCKED,
                        "pinned home " + kind.displayName()
                                + " is missing or incomplete at its verified ledger position",
                        session.ownedPlacement);
            }
            if (!pinnedTargetVerified(session)) {
                return new Result(State.BLOCKED,
                        "pinned home " + kind.displayName()
                                + " does not match its durable ownership record",
                        session.ownedPlacement);
            }
            return new Result(State.PLACED,
                    "verified exact owned Home " + kind.displayName(),
                    session.ownedPlacement);
        }
        return place(kind, operationId, lease, baritone, session, nowMillis);
    }

    /** Uses only the exact owned bed and reports success after sleep plus morning truth. */
    public Result tickSleepPinned(
            String operationId,
            String ledgerAssetId,
            HomeEconomySession.HomeAnchor home,
            int maximumRadius,
            boolean immediateHostilePressure,
            ControlLease lease,
            FabricBaritonePort baritone,
            long nowMillis) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(baritone, "baritone");
        PinnedSession pinned = configurePinnedHomeSession(
                Kind.WHITE_BED, operationId, ledgerAssetId, home, maximumRadius);
        if (pinned.failure() != null) return pinned.failure();
        Session session = pinned.session();
        hydrateFieldKit(session);
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new Result(State.WAITING, "waiting for Minecraft world/player", null);
        }
        resetTransientGeometryOnDimensionChange(session);
        HomeSleepPolicy.BedAccess access = HomeSleepPolicy.bedAccess(
                session.ownedPlacement != null, ownedInCurrentDimension(session),
                ownedBedChunksLoaded(session.ownedPlacement), pinnedTargetVerified(session));
        if (access == HomeSleepPolicy.BedAccess.LOAD) {
            return approachToLoad(operationId + ":sleep-load:" + session.ownedPlacement.asLong(),
                    session.ownedPlacement, "owned Home bed", lease, baritone, session);
        }
        if (access != HomeSleepPolicy.BedAccess.VERIFY_ACCESS) {
            return new Result(State.BLOCKED,
                    access == HomeSleepPolicy.BedAccess.WRONG_DIMENSION
                            ? "the exact owned Home bed is in another dimension"
                            : "the exact owned Home bed is missing, incomplete, or no longer verified",
                    session.ownedPlacement);
        }

        HomeSleepPolicy.Decision decision = HomeSleepPolicy.decide(
                session.sleepCursor,
                new HomeSleepPolicy.Observation(
                        "minecraft:overworld".equals(currentDimension()),
                        client.world.isNight(),
                        immediateHostilePressure,
                        client.player.isSleeping(),
                        Math.max(0L, client.world.getTimeOfDay())));
        // INTERACT returns the cursor that may be published only after the
        // actual vanilla interaction is accepted. Routing/aiming is not a
        // pending sleep acknowledgement.
        if (decision.action() != HomeSleepPolicy.Action.INTERACT) {
            session.sleepCursor = decision.cursor();
        }
        switch (decision.action()) {
            case INACTIVE -> {
                session.sleepInteractionAt = 0L;
                session.sleepFailures = 0;
                return new Result(State.WAITING, decision.detail(), session.ownedPlacement);
            }
            case WAIT_UNSAFE -> {
                session.sleepInteractionAt = 0L;
                return new Result(State.WAITING, decision.detail(), session.ownedPlacement);
            }
            case OBSERVE_SLEEPING, WAIT_SLEEPING -> {
                session.sleepInteractionAt = 0L;
                session.sleepFailures = 0;
                return new Result(State.SLEEPING, decision.detail(), session.ownedPlacement);
            }
            case VERIFIED -> {
                session.sleepInteractionAt = 0L;
                session.sleepFailures = 0;
                return new Result(State.SLEPT, decision.detail(), session.ownedPlacement);
            }
            case WAIT_FOR_SLEEP -> {
                if (session.sleepInteractionAt > 0L
                        && nowMillis - session.sleepInteractionAt < SLEEP_CONFIRM_MILLIS) {
                    return new Result(State.SLEEPING, decision.detail(), session.ownedPlacement);
                }
                session.sleepCursor = session.sleepCursor.withoutPendingInteraction();
                session.sleepInteractionAt = 0L;
                session.sleepFailures++;
                if (session.sleepFailures >= 2) {
                    return new Result(State.BLOCKED,
                            "two accepted owned-bed interactions produced no sleeping state",
                            session.ownedPlacement);
                }
                return new Result(State.WAITING,
                        "sleep acknowledgement was not observed; retrying the exact owned bed",
                        session.ownedPlacement);
            }
            case INTERACT -> {
                return interactWithOwnedBed(
                        operationId, lease, baritone, session, decision, nowMillis);
            }
        }
        throw new IllegalStateException("unhandled Home sleep action " + decision.action());
    }

    /** Keeps the idle lease alive until an observed sleep is paired with morning truth. */
    public boolean homeSleepCycleActive(String operationId) {
        Session session = sessions.get(Objects.requireNonNullElse(operationId, "").trim());
        return session != null && session.kind == Kind.WHITE_BED
                && (session.sleepCursor.interactionPending()
                || session.sleepCursor.sleepingObserved()
                || client.player != null && client.player.isSleeping());
    }

    /** A new explicit request gets fresh bed geometry without resetting other workstations. */
    public void resetHomeSleepAttempt(String operationId) {
        Session session = sessions.get(Objects.requireNonNullElse(operationId, "").trim());
        if (session == null || session.kind != Kind.WHITE_BED) return;
        session.standAt = null;
        session.failedStands.clear();
        session.failures = 0;
        session.sleepCursor = HomeSleepPolicy.Cursor.empty();
        session.sleepInteractionAt = 0L;
        session.sleepFailures = 0;
        session.clearAim();
    }

    private boolean ownedBedChunksLoaded(BlockPos foot) {
        if (!isLoaded(foot)) return false;
        BlockState state = client.world.getBlockState(foot);
        // A loaded non-bed is an invalid bed, not unknown chunk truth.
        return !(state.getBlock() instanceof BedBlock)
                || isLoaded(foot.offset(state.get(BedBlock.FACING)));
    }

    private Result interactWithOwnedBed(
            String operationId,
            ControlLease lease,
            FabricBaritonePort baritone,
            Session session,
            HomeSleepPolicy.Decision decision,
            long nowMillis) {
        BlockPos target = session.ownedPlacement;
        if (!inventory.isPlayerInventoryOpen()) {
            boolean closed = inventory.closeHandledScreen(nowMillis);
            return new Result(State.WAITING,
                    closed ? "closed an unrelated container before sleeping"
                            : "reconciling the open container before sleeping",
                    target);
        }
        Optional<BlockHitResult> currentHit = visibleHit(target);
        boolean inReach = client.player.squaredDistanceTo(Vec3d.ofCenter(target))
                <= INTERACT_DISTANCE_SQUARED;
        boolean inSleepRange = withinOwnedBedServerRange(target, client.player.getPos());
        if (!inReach || currentHit.isEmpty() || !inSleepRange) {
            if (session.standAt == null || !isStandable(session.standAt)
                    || interactionHitFrom(session.standAt, target).isEmpty()
                    || !standWithinOwnedBedServerRange(target, session.standAt)) {
                Optional<BlockPos> stand = findSleepInteractionStand(
                        target, session.failedStands);
                if (stand.isEmpty()) {
                    return new Result(State.BLOCKED,
                            "no safe reachable stand satisfies the exact owned Home bed range",
                            target);
                }
                session.standAt = stand.orElseThrow();
            }
            return approach(operationId + ":sleep:" + target.asLong(), session.standAt,
                    "owned Home bed", lease, baritone, session);
        }
        baritone.cancel(lease.epoch(), "sleeping in exact owned Home bed");
        if (nowMillis < session.nextInteractionAt) {
            return new Result(State.WAITING, "waiting to interact with owned Home bed", target);
        }
        BlockHitResult hit = currentHit.orElseThrow();
        if (!aimReady(session, "sleep:" + target.asLong(), hit.getPos())) {
            return new Result(State.WAITING, "aiming at exact owned Home bed", target);
        }
        String authorityOperation = operationId + ":sleep-authority";
        Result authority = exactHomeAuthority(
                session,
                authorityOperation,
                ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                List.of(target),
                target,
                nowMillis);
        if (authority != null) return authority;
        ActionResult action = client.interactionManager.interactBlock(
                client.player, Hand.MAIN_HAND, hit);
        completeExactHomeAuthority(authorityOperation);
        client.player.swingHand(Hand.MAIN_HAND);
        session.clearAim();
        session.nextInteractionAt = nowMillis + INTERACTION_INTERVAL_MILLIS;
        if (!action.isAccepted()) {
            Optional<MinecraftActuatorGateway.BlockedInteractionAttempt> blocked =
                    MinecraftActuatorGateway.shared(client).takeBlockedInteractionAttempt();
            if (blocked.isPresent()) {
                return Result.worldPolicyDenied(
                        blocked.orElseThrow().detail(), target);
            }
            session.sleepCursor = session.sleepCursor.withoutPendingInteraction();
            return new Result(State.WAITING,
                    "server rejected owned Home bed interaction; reobserving safety",
                    target);
        }
        session.sleepCursor = decision.cursor();
        session.sleepInteractionAt = nowMillis;
        return new Result(State.SLEEPING,
                "owned Home bed interaction accepted; waiting for sleeping state",
                target);
    }

    private PinnedSession configurePinnedHomeSession(
            Kind kind,
            String operationId,
            String ledgerAssetId,
            HomeEconomySession.HomeAnchor home,
            int maximumRadius) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(home, "home");
        String id = Objects.requireNonNullElse(operationId, "").trim();
        if (id.isEmpty()) throw new IllegalArgumentException("operationId must not be blank");
        String assetId = Objects.requireNonNullElse(ledgerAssetId, "").trim();
        if (!FieldKitAssetPolicy.isHomeAsset(assetId)
                || !assetId.contains(':' + kind.name().toLowerCase(java.util.Locale.ROOT) + ':')) {
            throw new IllegalArgumentException("invalid pinned home asset ID for " + kind);
        }
        if (maximumRadius < 1 || maximumRadius > 32) {
            throw new IllegalArgumentException("home asset radius must be 1..32");
        }
        Session session = sessions.compute(id, (key, existing) ->
                existing == null || existing.kind != kind ? new Session(key, kind) : existing);
        if (session.homePinned && (!session.pinnedAssetId.equals(assetId)
                || !session.homeFingerprint.equals(home.fingerprint()))) {
            return new PinnedSession(session, new Result(State.BLOCKED,
                    "Home asset operation changed pinned identity", session.ownedPlacement));
        }
        session.homePinned = true;
        session.pinnedAssetId = assetId;
        session.homeFingerprint = home.fingerprint();
        session.homeDimension = home.dimension();
        session.homeAnchor = new BlockPos(home.x(), home.y(), home.z());
        session.homeRadius = maximumRadius;
        return new PinnedSession(session, null);
    }

    /** Read-only durable truth used to bind or reconcile the Home session. */
    public Optional<FieldKitLedger.Asset> ownedAsset(String ledgerAssetId) {
        if (ledgerAssetId == null || ledgerAssetId.isBlank()) return Optional.empty();
        return Optional.ofNullable(fieldKit.snapshot().assets().get(ledgerAssetId.trim()));
    }

    /** Loaded, bounded read-only scan. Confirmation still owns every registration. */
    public List<dev.entity.client.autonomy.policy.HomeFurnitureAdoptionPolicy.Observation>
            observeHomeFurnitureForAdoption(HomeEconomySession.HomeAnchor home, int radius, String nonce) {
        if (home == null || client.world == null || client.player == null
                || !currentDimension().equals(home.dimension())) {
            throw new IllegalArgumentException("Entity must be in the registered Home dimension to inspect furniture");
        }
        BlockPos anchor = new BlockPos(home.x(), home.y(), home.z());
        var observed = new ArrayList<dev.entity.client.autonomy.policy.HomeFurnitureAdoptionPolicy.Observation>();
        for (BlockPos mutable : BlockPos.iterate(anchor.add(-radius, -radius, -radius), anchor.add(radius, radius, radius))) {
            BlockPos position = mutable.toImmutable();
            if (!withinRadius(anchor, position, radius)) continue;
            if (!isLoaded(position)) throw new IllegalArgumentException(
                    "The full 6-block 3D Home scan is not loaded; bring Entity Home and adopt again");
            BlockState state = client.world.getBlockState(position);
            HomeEconomySession.AssetRole role;
            if (state.isOf(Blocks.CRAFTING_TABLE)) role = HomeEconomySession.AssetRole.CRAFTING_TABLE;
            else if (state.isOf(Blocks.FURNACE)) role = HomeEconomySession.AssetRole.FURNACE;
            else if (state.isOf(Blocks.CHEST)) {
                // One connected chest is one candidate, never two competing halves.
                if (state.get(ChestBlock.CHEST_TYPE) == ChestType.RIGHT) continue;
                role = HomeEconomySession.AssetRole.CHEST;
            } else if (state.getBlock() instanceof BedBlock) {
                if (state.get(BedBlock.PART) != BedPart.FOOT) continue;
                role = HomeEconomySession.AssetRole.BED;
            } else continue;
            String stateIdentity = state.toString();
            String blocked = "";
            HomeFurnitureBindingPolicy.Selection selected = null;
            var location = new FieldKitLedger.Position(home.dimension(), position.getX(), position.getY(), position.getZ());
            try {
                String facing = state.contains(Properties.HORIZONTAL_FACING)
                        ? state.get(Properties.HORIZONTAL_FACING).asString() : "";
                String chestType = role == HomeEconomySession.AssetRole.CHEST
                        ? state.get(ChestBlock.CHEST_TYPE).asString() : "";
                HomeFurnitureBindingPolicy.ConnectedChest connected = null;
                if (role == HomeEconomySession.AssetRole.CHEST) {
                    var identity = observeChestGeometry("adopt", position, anchor, radius)
                            .orElseThrow(() -> new IllegalArgumentException("Chest geometry/access is unavailable"));
                    for (var half : identity.halves()) if (!half.position().equals(location)) {
                        connected = new HomeFurnitureBindingPolicy.ConnectedChest(half.position().x(), half.position().y(),
                                half.position().z(), half.blockId(), half.facing(), half.chestType());
                        stateIdentity += "|" + client.world.getBlockState(chestPosition(half));
                    }
                }
                if (role == HomeEconomySession.AssetRole.BED) {
                    BlockPos head = position.offset(state.get(BedBlock.FACING));
                    if (!isLoaded(head)) throw new IllegalArgumentException("Bed head is not loaded");
                    stateIdentity += "|" + client.world.getBlockState(head);
                }
                selected = new HomeFurnitureBindingPolicy.Selection(nonce, role, location,
                        Registries.BLOCK.getId(state.getBlock()).toString(), facing, chestType, connected);
                validateHomeFurnitureSelection(selected, home, radius);
            } catch (IllegalArgumentException invalid) {
                selected = null;
                blocked = invalid.getMessage();
            }
            observed.add(new dev.entity.client.autonomy.policy.HomeFurnitureAdoptionPolicy.Observation(
                    role, location, stateIdentity, selected, blocked));
        }
        return List.copyOf(observed);
    }

    /** Read-only validation of one explicit owner-selected block; no nearby search/adoption. */
    public void validateHomeFurnitureSelection(HomeFurnitureBindingPolicy.Selection selected,
            HomeEconomySession.HomeAnchor home, int radius) {
        if (home == null || client.world == null || client.player == null
                || !currentDimension().equals(selected.position().dimension())
                || !home.dimension().equals(selected.position().dimension())) {
            throw new IllegalArgumentException("Entity and selected furniture must be in the established Home dimension");
        }
        BlockPos position = new BlockPos(selected.position().x(), selected.position().y(), selected.position().z());
        BlockPos anchor = new BlockPos(home.x(), home.y(), home.z());
        if (!withinRadius(anchor, position, radius)) {
            throw new IllegalArgumentException("Furniture must remain within the existing " + radius + "-block Home asset radius");
        }
        if (!client.world.getChunkManager().isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
            throw new IllegalArgumentException("Selected furniture is not loaded for Entity; bring Entity into view and select again");
        }
        BlockState state = client.world.getBlockState(position);
        String facing = state.contains(Properties.HORIZONTAL_FACING)
                ? state.get(Properties.HORIZONTAL_FACING).asString() : "";
        if (!Registries.BLOCK.getId(state.getBlock()).toString().equals(selected.blockId())
                || !facing.equals(selected.facing())) {
            throw new IllegalArgumentException("The frozen furniture block or orientation changed; select again");
        }
        ArrayList<BlockPos> footprint = new ArrayList<>(List.of(position));
        if (selected.role() == HomeEconomySession.AssetRole.BED) {
            if (!bedStructureVerified(position)) throw new IllegalArgumentException("Both matching bed halves must be loaded and intact");
            BlockPos head = position.offset(state.get(BedBlock.FACING));
            if (!withinRadius(anchor, head, radius)) throw new IllegalArgumentException("The bed head is outside the Home asset radius");
            footprint.add(head);
        }
        if (selected.role() == HomeEconomySession.AssetRole.CHEST) {
            HomeEconomySession.ContainerIdentity identity = observeChestGeometry("selection", position, anchor, radius)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Both exact chest halves must be loaded, within Home, authorized and unobstructed"));
            HomeEconomySession.ChestHalf selectedHalf = identity.halves().stream()
                    .filter(half -> half.position().equals(selected.position())).findFirst().orElseThrow();
            HomeFurnitureBindingPolicy.ConnectedChest connected = identity.halves().stream()
                    .filter(half -> !half.position().equals(selected.position()))
                    .map(half -> new HomeFurnitureBindingPolicy.ConnectedChest(half.position().x(),
                            half.position().y(), half.position().z(), half.blockId(), half.facing(), half.chestType()))
                    .findFirst().orElse(null);
            if (!selected.chestType().equals(selectedHalf.chestType())
                    || !Objects.equals(selected.connectedChest(), connected)) {
                throw new IllegalArgumentException("The selected chest connection changed; select both exact halves again");
            }
            for (HomeEconomySession.ChestHalf half : identity.halves()) {
                BlockPos cell = chestPosition(half);
                if (!cell.equals(position)) footprint.add(cell);
            }
        }
        for (BlockPos cell : footprint) {
            if (protectedAreas == null) throw new IllegalArgumentException("Area authority is unavailable");
            var decision = protectedAreas.decide(ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                    currentDimension(), cell.getX(), cell.getY(), cell.getZ(), true);
            if (!decision.allowed()) throw new IllegalArgumentException(decision.detail());
        }
        if (findInteractionStand(position, Set.of()).isEmpty()) {
            throw new IllegalArgumentException("Selected furniture has no loaded safe interaction stand");
        }
    }

    /** Records only the exact confirmed selection; no inventory, placement, or break call. */
    public FieldKitLedger.Asset registerOwnerSelectedHomeFurniture(
            HomeFurnitureBindingPolicy.Selection selected, HomeEconomySession.HomeAnchor home,
            int radius, String freshAssetId, long nowMillis) throws IOException {
        validateHomeFurnitureSelection(selected, home, radius);
        return fieldKit.ownerRegisterPlaced(freshAssetId, selected.role().ledgerKind(),
                selected.position(), nowMillis);
    }

    /** Read-only exact ledger/world truth for {@code /e home status}. */
    public HomeAssetDiagnostic diagnosePinnedHomeAsset(
            Kind kind,
            String ledgerAssetId,
            HomeEconomySession.HomeAnchor home,
            int maximumRadius) {
        Objects.requireNonNull(kind, "kind");
        String id = Objects.requireNonNullElse(ledgerAssetId, "").trim();
        if (id.isEmpty()) {
            return HomeAssetDiagnostic.withoutPosition(
                    id, HomeAssetLiveTruth.NO_RECORD, "no pinned ledger ID");
        }
        FieldKitLedger.Asset asset = fieldKit.snapshot().assets().get(id);
        if (asset == null) {
            return HomeAssetDiagnostic.withoutPosition(
                    id, HomeAssetLiveTruth.NO_RECORD, "ledger record is missing");
        }
        if (asset.kind() != kindToLedgerKind(kind)) {
            return positionedDiagnostic(asset, HomeAssetLiveTruth.REPLACED,
                    "ledger kind does not match the pinned role");
        }
        if (asset.state() == FieldKitLedger.AssetState.CARRIED) {
            return HomeAssetDiagnostic.withoutPosition(
                    id, HomeAssetLiveTruth.CARRIED, "owned asset is carried");
        }
        FieldKitLedger.Position placed = asset.lastKnownPosition();
        if (placed == null) {
            return HomeAssetDiagnostic.withoutPosition(
                    id, HomeAssetLiveTruth.NO_POSITION, "ledger has no exact position");
        }
        if (asset.state() == FieldKitLedger.AssetState.LOST
                || asset.state() == FieldKitLedger.AssetState.RECOVERY_REQUIRED) {
            return positionedDiagnostic(asset, HomeAssetLiveTruth.LOST,
                    asset.recoveryReason().isBlank()
                            ? "ledger marks the asset lost" : asset.recoveryReason());
        }
        if (home == null || !placed.dimension().equals(home.dimension())) {
            return positionedDiagnostic(asset, HomeAssetLiveTruth.WRONG_DIMENSION,
                    "asset position is outside the configured Home dimension");
        }
        BlockPos position = new BlockPos(placed.x(), placed.y(), placed.z());
        BlockPos anchor = new BlockPos(home.x(), home.y(), home.z());
        if (!withinRadius(anchor, position, maximumRadius)) {
            return positionedDiagnostic(asset, HomeAssetLiveTruth.REPLACED,
                    "asset position is outside the pinned Home radius");
        }
        if (!placed.dimension().equals(currentDimension())) {
            return positionedDiagnostic(asset, HomeAssetLiveTruth.WRONG_DIMENSION,
                    "live world is " + currentDimension() + "; asset is in " + placed.dimension());
        }
        if (client.world == null || !isLoaded(position)) {
            return positionedDiagnostic(asset, HomeAssetLiveTruth.UNLOADED,
                    "asset chunk is not loaded; block truth is unknown");
        }
        BlockState state = client.world.getBlockState(position);
        if (state.isAir() || state.isReplaceable()) {
            return positionedDiagnostic(asset, HomeAssetLiveTruth.MISSING,
                    "loaded exact position no longer contains a block");
        }
        if (!kind.matches(state)) {
            return positionedDiagnostic(asset, HomeAssetLiveTruth.REPLACED,
                    "loaded exact position contains " + state.getBlock().getName().getString());
        }
        if (kind == Kind.WHITE_BED && !bedStructureVerified(position)) {
            return positionedDiagnostic(asset, HomeAssetLiveTruth.REPLACED,
                    "the owned white bed no longer has its exact foot/head structure");
        }
        if (kind == Kind.CHEST) {
            if (state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE
                    && !isLoaded(position.offset(ChestBlock.getFacing(state)))) {
                return positionedDiagnostic(asset, HomeAssetLiveTruth.UNLOADED,
                        "connected chest half is unloaded; combined inventory is unknown");
            }
            if (observeChestGeometry("diagnostic", position, anchor, maximumRadius).isEmpty()) {
                return positionedDiagnostic(asset, HomeAssetLiveTruth.OBSTRUCTED,
                        "connected chest is incomplete, outside Home, unauthorized, or blocked by lid space/a sitting cat");
            }
        }
        if (findInteractionStand(position, Set.of()).isEmpty()) {
            if (client.player != null
                    && findProspectiveHomeAccessStand(position, Set.of()).isPresent()) {
                return positionedDiagnostic(asset, HomeAssetLiveTruth.ACCESS_RECOVERABLE,
                        "exact owned block is temporarily obstructed; a bounded natural-terrain "
                                + "access column can be restored without breaking a Home asset");
            }
            return positionedDiagnostic(asset, HomeAssetLiveTruth.OBSTRUCTED,
                    "no loaded standable or safely restorable sightline reaches the exact block");
        }
        if (asset.state() != FieldKitLedger.AssetState.PLACED
                || asset.verification() == FieldKitLedger.Verification.NONE) {
            return positionedDiagnostic(asset, HomeAssetLiveTruth.UNVERIFIED,
                    "block exists but durable placement ownership is not verified");
        }
        return positionedDiagnostic(asset, HomeAssetLiveTruth.EXACT_VERIFIED,
                "exact owned block and reachable interaction stand verified");
    }

    private static FieldKitLedger.AssetKind kindToLedgerKind(Kind kind) {
        return switch (kind) {
            case CRAFTING_TABLE -> FieldKitLedger.AssetKind.CRAFTING_TABLE;
            case FURNACE -> FieldKitLedger.AssetKind.FURNACE;
            case CHEST -> FieldKitLedger.AssetKind.CHEST;
            case WHITE_BED -> FieldKitLedger.AssetKind.WHITE_BED;
        };
    }

    private static HomeAssetDiagnostic positionedDiagnostic(
            FieldKitLedger.Asset asset,
            HomeAssetLiveTruth truth,
            String detail) {
        FieldKitLedger.Position position = asset.lastKnownPosition();
        if (position == null) {
            return HomeAssetDiagnostic.withoutPosition(asset.id(), truth, detail);
        }
        return new HomeAssetDiagnostic(
                asset.id(), true, position.dimension(),
                position.x(), position.y(), position.z(), truth, detail);
    }

    /** Read-only proof that the current GUI remains bound to one exact pinned asset. */
    public boolean exactPinnedScreenOpen(
            Kind kind,
            String operationId,
            String ledgerAssetId,
            HomeEconomySession.HomeAnchor home,
            int maximumRadius) {
        Session session = sessions.get(Objects.requireNonNullElse(operationId, "").trim());
        if (session == null || client.player == null || kind == null || home == null
                || maximumRadius < 1 || session.kind != kind || !session.homePinned
                || !session.pinnedAssetId.equals(
                Objects.requireNonNullElse(ledgerAssetId, "").trim())
                || !session.homeFingerprint.equals(home.fingerprint())
                || session.homeRadius != maximumRadius
                || session.pinnedOpenSyncId < 0
                || client.player.currentScreenHandler.syncId != session.pinnedOpenSyncId) {
            return false;
        }
        return screenMatches(kind) && pinnedTargetVerified(session)
                && (kind != Kind.CHEST || chestScreenMatches(session, session.pinnedChestOpenIdentity));
    }

    /** Current exact loaded storage geometry. This does not open or adopt any screen. */
    public Optional<HomeEconomySession.ContainerIdentity> observeHomeStorageIdentity(String storageId,
            String ledgerAssetId, HomeEconomySession.HomeAnchor home, int radius) {
        if (home == null || client.world == null || !currentDimension().equals(home.dimension())
                || !FieldKitAssetPolicy.isHomeAsset(ledgerAssetId)) return Optional.empty();
        FieldKitLedger.Asset asset = fieldKit.snapshot().assets().get(ledgerAssetId);
        if (asset == null || asset.kind() != FieldKitLedger.AssetKind.CHEST
                || asset.state() != FieldKitLedger.AssetState.PLACED
                || asset.verification() == FieldKitLedger.Verification.NONE || asset.lastKnownPosition() == null
                || !asset.lastKnownPosition().dimension().equals(home.dimension())) return Optional.empty();
        FieldKitLedger.Position position = asset.lastKnownPosition();
        return observeChestGeometry(storageId, new BlockPos(position.x(), position.y(), position.z()),
                new BlockPos(home.x(), home.y(), home.z()), radius);
    }

    /** Identity of an acknowledged exact pinned screen, in its actual vanilla slot order. */
    public Optional<HomeEconomySession.ContainerIdentity> exactPinnedStorageIdentity(String storageId,
            String operationId, String ledgerAssetId, HomeEconomySession.HomeAnchor home, int radius) {
        if (!exactPinnedScreenOpen(Kind.CHEST, operationId, ledgerAssetId, home, radius)) return Optional.empty();
        Session session = sessions.get(operationId.trim());
        return Optional.of(new HomeEconomySession.ContainerIdentity(storageId, session.pinnedChestOpenIdentity.halves()));
    }

    /** Runtime supplies the durable transfer fence before reopening; null only after that debt settles. */
    public void requirePinnedStorageIdentity(String operationId, HomeEconomySession.ContainerIdentity expected) {
        String id = Objects.requireNonNull(operationId).trim();
        if (id.isEmpty()) throw new IllegalArgumentException("operationId must not be blank");
        Session session = sessions.computeIfAbsent(id, key -> new Session(key, Kind.CHEST));
        if (session.kind != Kind.CHEST) throw new IllegalArgumentException("storage identity needs a chest session");
        if (Objects.equals(expected, session.requiredChestIdentity)) return;
        boolean lockingCurrent = session.requiredChestIdentity == null && expected != null
                && MinecraftHomeStorageGeometry.sameLayout(expected, session.pinnedChestOpenIdentity);
        if (inventoryMutationPending() && !lockingCurrent) {
            throw new IllegalStateException("cannot change storage identity while inventory/cursor work is pending");
        }
        session.requiredChestIdentity = expected;
    }

    /** Shared anchor truth for an explicit player-selected Home request. */
    public HomeEconomyPolicy.HomeCandidate inspectHomeAnchor(
            boolean idleAuthority,
            BlockPos anchor) {
        return inspectHomeAnchorDetails(idleAuthority, anchor).candidate();
    }

    /** Anchor truth plus the exact stationary-position explanation used by commands. */
    public HomeAnchorAssessment assessHomeAnchor(
            boolean idleAuthority,
            BlockPos anchor) {
        HomeAnchorInspection inspection = inspectHomeAnchorDetails(idleAuthority, anchor);
        return new HomeAnchorAssessment(
                inspection.candidate(), inspection.anchorDetail());
    }

    /**
     * Resolves an explicit Home request without silently replacing a safe site.
     * The requested feet win when eligible. Otherwise only loaded, fully
     * inspected nearby cave/floor cells and bounded surface cells compete.
     */
    public Optional<HomeSiteSelection> findHomeEstablishmentSite(
            boolean idleAuthority,
            BlockPos requested,
            int maximumAssetRadius,
            int maximumSearchRadius) {
        Objects.requireNonNull(requested, "requested");
        if (maximumSearchRadius < 1 || maximumSearchRadius > 64) {
            throw new IllegalArgumentException("home search radius must be 1..64");
        }
        if (client.player == null || client.world == null) return Optional.empty();

        LinkedHashMap<Long, BlockPos> positions = new LinkedHashMap<>();
        addHomeSitePosition(positions, requested);

        int nearbyRadius = Math.min(HOME_NEARBY_HORIZONTAL_RADIUS, maximumSearchRadius);
        int minimumY = Math.max(client.world.getBottomY(),
                requested.getY() - HOME_NEARBY_VERTICAL_RADIUS);
        int maximumY = Math.min(client.world.getTopYInclusive(),
                requested.getY() + HOME_NEARBY_VERTICAL_RADIUS);
        for (int x = -nearbyRadius; x <= nearbyRadius; x++) {
            for (int z = -nearbyRadius; z <= nearbyRadius; z++) {
                if (x * x + z * z > nearbyRadius * nearbyRadius) continue;
                for (int y = minimumY; y <= maximumY; y++) {
                    addHomeSitePosition(positions,
                            new BlockPos(requested.getX() + x, y,
                                    requested.getZ() + z));
                }
            }
        }

        for (int x = -maximumSearchRadius; x <= maximumSearchRadius; x++) {
            for (int z = -maximumSearchRadius; z <= maximumSearchRadius; z++) {
                if (x * x + z * z > maximumSearchRadius * maximumSearchRadius) continue;
                int worldX = requested.getX() + x;
                int worldZ = requested.getZ() + z;
                if (!client.world.isChunkLoaded(worldX >> 4, worldZ >> 4)) continue;
                int surfaceY = client.world.getTopY(
                        Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
                if (Math.abs(surfaceY - requested.getY())
                        > HOME_MAX_SURFACE_VERTICAL_DELTA) continue;
                addHomeSitePosition(positions, new BlockPos(worldX, surfaceY, worldZ));
            }
        }

        ArrayList<HomeEconomyPolicy.HomeSiteCandidate> candidates = new ArrayList<>();
        for (BlockPos position : positions.values()) {
            HomeAnchorInspection inspection = inspectHomeAnchorDetails(
                    idleAuthority, position);
            HomeLayoutCandidates layout = homeLayoutCandidates(
                    position, maximumAssetRadius, Set.of());
            HomeEconomyPolicy.Eligibility eligibility =
                    HomeEconomyPolicy.evaluateHomeCandidate(inspection.candidate());
            long dx = (long) position.getX() - requested.getX();
            long dy = (long) position.getY() - requested.getY();
            long dz = (long) position.getZ() - requested.getZ();
            candidates.add(new HomeEconomyPolicy.HomeSiteCandidate(
                    position.getX(), position.getY(), position.getZ(),
                    eligibility.eligible(), position.equals(requested),
                    layout.selection().isPresent() ? 4 : 0,
                    dx * dx + dy * dy + dz * dz,
                    isLoaded(position) && client.world.isSkyVisible(position)));
        }
        return HomeEconomyPolicy.selectHomeSite(candidates).map(selected ->
                new HomeSiteSelection(
                        new BlockPos(selected.x(), selected.y(), selected.z()),
                        selected.exactRequest(), selected.immediateAssetCells(),
                        selected.skyVisible()));
    }

    private HomeAnchorInspection inspectHomeAnchorDetails(
            boolean idleAuthority,
            BlockPos anchor) {
        Objects.requireNonNull(anchor, "anchor");
        boolean worldReady = client.player != null && client.world != null;
        boolean loaded = worldReady
                && client.world.isChunkLoaded(anchor.getX() >> 4, anchor.getZ() >> 4);
        String anchorDetail = loaded
                ? homeAnchorStandFailure(anchor)
                : "the anchor chunk is not loaded";
        // Set-time eligibility must remain true after Entity steps away to
        // place an asset. A grounded body can temporarily stand on snow, a
        // path edge, or another partial surface that the durable Home anchor
        // cannot safely reacquire; accepting that transient proof creates a
        // Home which blocks on its very first provisioning route.
        boolean supported = loaded && anchorDetail.isBlank();
        boolean dry = worldReady
                && !client.player.isTouchingWater()
                && !client.player.isInLava()
                && client.world.getFluidState(anchor).isEmpty()
                && client.world.getFluidState(anchor.up()).isEmpty();
        return new HomeAnchorInspection(new HomeEconomyPolicy.HomeCandidate(
                idleAuthority,
                worldReady && client.player.isAlive(),
                loaded,
                worldReady && client.player.isOnGround(),
                dry,
                supported,
                loaded && homeHazardClear(anchor)), anchorDetail);
    }

    private static void addHomeSitePosition(
            Map<Long, BlockPos> positions,
            BlockPos position) {
        positions.putIfAbsent(position.asLong(), position.toImmutable());
    }

    /** Revalidates the durable anchor without requiring its occupied asset cells to be empty. */
    public boolean homeWorkspaceSafe(HomeEconomySession.HomeAnchor home, int maximumRadius) {
        Objects.requireNonNull(home, "home");
        if (maximumRadius < 1 || client.player == null || client.world == null
                || !home.dimension().equals(currentDimension())) return false;
        BlockPos anchor = new BlockPos(home.x(), home.y(), home.z());
        return withinRadius(anchor, client.player.getBlockPos(), maximumRadius + 2)
                && homeAnchorStandFailure(anchor).isBlank()
                && homeHazardClear(anchor);
    }

    /** Exact invariant shared by Home selection and every later Home tick. */
    private String homeAnchorStandFailure(BlockPos anchor) {
        if (client.player == null || client.world == null || !isLoaded(anchor)) {
            return "the anchor is not loaded";
        }
        BlockState foot = client.world.getBlockState(anchor);
        BlockState head = client.world.getBlockState(anchor.up());
        BlockState overhead = client.world.getBlockState(anchor.up(2));
        if (!foot.getCollisionShape(client.world, anchor).isEmpty()) {
            return "the anchor feet cell contains "
                    + foot.getBlock().getName().getString();
        }
        if (!head.getCollisionShape(client.world, anchor.up()).isEmpty()) {
            return "the anchor head cell contains "
                    + head.getBlock().getName().getString();
        }
        if (unsafeLocalState(foot) || unsafeLocalState(head)) {
            return "the anchor body column contains fluid or a local hazard";
        }
        if (!hasSafeFloorSupport(anchor.down())) {
            return "the anchor floor is not stable full-top support after Entity steps away";
        }
        if (overhead.getBlock() instanceof FallingBlock) {
            return "the anchor has a falling block overhead";
        }
        return "";
    }

    /**
     * True only for a non-hazardous anchor whose empty/replaceable support may
     * be restored by the exact in-flight owned placement route. This is not
     * inventory-safe truth: callers may use it solely to let that controller
     * finish. Natural run 20260822-120356 retained air at the anchor foot/head
     * and at the formerly solid floor while the ascent route was still active.
     */
    public boolean homeWorkspaceRepairable(
            HomeEconomySession.HomeAnchor home,
            int maximumRadius) {
        Objects.requireNonNull(home, "home");
        if (maximumRadius < 1 || client.player == null || client.world == null
                || !home.dimension().equals(currentDimension())) return false;
        BlockPos anchor = new BlockPos(home.x(), home.y(), home.z());
        if (!withinRadius(anchor, client.player.getBlockPos(), maximumRadius + 2)
                || !isLoaded(anchor) || !homeHazardClear(anchor)) return false;
        BlockState foot = client.world.getBlockState(anchor);
        BlockState head = client.world.getBlockState(anchor.up());
        BlockState overhead = client.world.getBlockState(anchor.up(2));
        BlockState floor = client.world.getBlockState(anchor.down());
        boolean bodyClear = foot.getCollisionShape(client.world, anchor).isEmpty()
                && head.getCollisionShape(client.world, anchor.up()).isEmpty()
                && !unsafeLocalState(foot) && !unsafeLocalState(head)
                && !(overhead.getBlock() instanceof FallingBlock);
        boolean supportRepairable = homeAnchorSupported(anchor)
                || (floor.getCollisionShape(client.world, anchor.down()).isEmpty()
                && !unsafeLocalState(floor));
        return bodyClear && supportRepairable;
    }

    /**
     * Finds one deterministic, loaded infinite-source-safe water cell inside
     * Home's bounded sourcing range. The returned stand has stable support and
     * a real source-fluid sightline; no water block is guessed through terrain.
     */
    public Optional<RenewableWaterSource> findRenewableHomeWaterSource(
            HomeEconomySession.HomeAnchor home,
            int maximumRadius) {
        Objects.requireNonNull(home, "home");
        return findWaterSource(new BlockPos(home.x(), home.y(), home.z()), home.dimension(),
                home.fingerprint(), maximumRadius, true);
    }

    /** Ordinary acquisition uses the same bounded loaded scan, without a Home/renewability claim. */
    public Optional<RenewableWaterSource> findMissionWaterSource(int maximumRadius) {
        if (client.player == null || client.world == null) return Optional.empty();
        BlockPos anchor = client.player.getBlockPos();
        return findWaterSource(anchor, currentDimension(), "mission:" + anchor.asLong(), maximumRadius, false);
    }

    private Optional<RenewableWaterSource> findWaterSource(BlockPos anchor, String dimension,
            String scope, int maximumRadius, boolean renewableRequired) {
        if (maximumRadius < 1 || maximumRadius > 128
                || client.player == null || client.world == null
                || !dimension.equals(currentDimension())) return Optional.empty();
        BlockPos origin = client.player.getBlockPos();
        long searchEpoch = client.world.getTime() / 200L;
        String cacheKey = resourcePerception.revision() + ":" + System.identityHashCode(client.world) + ":"
                + scope + ':' + renewableRequired + ':' + maximumRadius + ':'
                + (origin.getX() >> 4) + ':' + (origin.getZ() >> 4) + ':' + searchEpoch;
        if (cacheKey.equals(renewableWaterSearchCacheKey)
                && (renewableRequired || renewableWaterSearchCache.isEmpty()
                || missionWaterSourceValid(renewableWaterSearchCache.orElseThrow().source()))) {
            return renewableWaterSearchCache;
        }

        ArrayList<RenewableWaterCandidate> candidates = new ArrayList<>();
        HashSet<Long> visited = new HashSet<>();

        // Inspect the complete nearby cave volume, where a same-level source is
        // genuinely cheap to reach. Never scan the full 64-block 3-D sphere on
        // Minecraft's render thread.
        int localRadius = Math.min(maximumRadius, 24);
        int localRadiusSquared = localRadius * localRadius;
        for (int y = -localRadius; y <= localRadius; y++) {
            int ySquared = y * y;
            for (int x = -localRadius; x <= localRadius; x++) {
                int horizontalSquared = ySquared + x * x;
                if (horizontalSquared > localRadiusSquared) continue;
                int maximumZ = (int) Math.floor(
                        Math.sqrt(localRadiusSquared - horizontalSquared));
                for (int z = -maximumZ; z <= maximumZ; z++) {
                    collectRenewableWaterCandidate(
                            anchor, anchor.add(x, y, z), maximumRadius,
                            visited, candidates, renewableRequired);
                }
            }
        }

        // Ordinary ponds, rivers, and oceans are found from one loaded heightmap
        // lookup per horizontal column. This covers real surface terrain without
        // multiplying the search by the world's vertical span.
        int radiusSquared = maximumRadius * maximumRadius;
        for (int x = -maximumRadius; x <= maximumRadius; x++) {
            int xSquared = x * x;
            int maximumZ = (int) Math.floor(Math.sqrt(radiusSquared - xSquared));
            for (int z = -maximumZ; z <= maximumZ; z++) {
                int worldX = anchor.getX() + x;
                int worldZ = anchor.getZ() + z;
                if (!isLoaded(new BlockPos(worldX, anchor.getY(), worldZ))) continue;
                int topY = client.world.getTopY(
                        Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
                for (int y = topY - 3; y <= topY + 1; y++) {
                    collectRenewableWaterCandidate(
                            anchor, new BlockPos(worldX, y, worldZ), maximumRadius,
                            visited, candidates, renewableRequired);
                }
            }
        }
        candidates.sort(Comparator
                // Vertical separation is disproportionately expensive in ordinary
                // survival terrain. Prefer a slightly farther same-level pond over
                // an apparently close sealed cave source.
                .comparingLong((RenewableWaterCandidate candidate) ->
                        renewableWaterTravelScore(candidate.source(), origin))
                .thenComparingLong(candidate -> candidate.source().asLong()));
        Optional<RenewableWaterSource> result = candidates.stream()
                .map(candidate -> findWaterInteractionStand(candidate.source())
                        .map(stand -> new RenewableWaterSource(
                                candidate.source(), stand, candidate.neighborMask(),
                                (renewableRequired ? renewableWaterHit(candidate.source(), candidate.neighborMask())
                                        : missionWaterHit(candidate.source(), waterSourceState(candidate.source()))).isPresent())))
                .flatMap(Optional::stream)
                .findFirst();
        renewableWaterSearchCacheKey = cacheKey;
        renewableWaterSearchCache = result;
        return result;
    }

    private void collectRenewableWaterCandidate(
            BlockPos anchor,
            BlockPos source,
            int maximumRadius,
            Set<Long> visited,
            List<RenewableWaterCandidate> candidates, boolean renewableRequired) {
        if (!withinRadius(anchor, source, maximumRadius)
                || !visited.add(source.asLong()) || !isLoaded(source)) return;
        int mask = renewableWaterNeighborMask(source);
        if (renewableRequired ? !renewableWaterGeometrySafe(source, mask) : !missionWaterSourceValid(source)) return;
        if (!resourcePerception.permitsBlock(source)) return;
        candidates.add(new RenewableWaterCandidate(source.toImmutable(), mask));
    }

    private static long renewableWaterTravelScore(BlockPos source, BlockPos origin) {
        long dx = (long) source.getX() - origin.getX();
        long dy = (long) source.getY() - origin.getY();
        long dz = (long) source.getZ() - origin.getZ();
        return dx * dx + dz * dz + 16L * dy * dy;
    }

    /** Revalidates one persisted source coordinate and its exact neighbor mask. */
    public Optional<RenewableWaterSource> inspectRenewableHomeWaterSource(
            HomeEconomySession.HomeAnchor home,
            BlockPos source,
            int expectedNeighborMask,
            int maximumRadius) {
        return inspectRenewableHomeWaterSource(home, source, expectedNeighborMask, maximumRadius, null);
    }

    /** One failed approach may be replaced, but the exact persisted source never changes. */
    public Optional<RenewableWaterSource> inspectRenewableHomeWaterSource(
            HomeEconomySession.HomeAnchor home, BlockPos source,
            int expectedNeighborMask, int maximumRadius, BlockPos rejectedStand) {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(source, "source");
        if (client.player == null || client.world == null
                || !home.dimension().equals(currentDimension())
                || !withinRadius(new BlockPos(home.x(), home.y(), home.z()), source, maximumRadius)
                || !isLoaded(source)
                || renewableWaterNeighborMask(source) != expectedNeighborMask
                || !renewableWaterGeometrySafe(source, expectedNeighborMask)) {
            return Optional.empty();
        }
        boolean reachableNow = renewableWaterHit(source, expectedNeighborMask).isPresent();
        if (reachableNow) return Optional.of(new RenewableWaterSource(
                source.toImmutable(), client.player.getBlockPos(), expectedNeighborMask, true));
        Optional<BlockPos> stand = findWaterInteractionStand(source, rejectedStand);
        return stand.map(position -> new RenewableWaterSource(
                source.toImmutable(), position.toImmutable(), expectedNeighborMask,
                false));
    }

    /** Returns a real source-fluid hit only while the persisted geometry remains exact. */
    public Optional<BlockHitResult> renewableWaterHit(
            BlockPos source,
            int expectedNeighborMask) {
        if (client.player == null || client.world == null || !isLoaded(source)
                || renewableWaterNeighborMask(source) != expectedNeighborMask
                || !renewableWaterGeometrySafe(source, expectedNeighborMask)) return Optional.empty();
        // BucketItem uses a full interaction-range ray from the actual eye to a
        // fluid face, not a shorter arbitrary radius to the source block's center.
        // A canonical stand ray is useful for routing, never proof of a live hit.
        return BlockInteractionRaycaster.fluidSourceAimNow(client, source)
                .map(BlockInteractionRaycaster.FluidSourceAim::hit);
    }

    /** Exact loaded block-state proof; never reads a synthetic/unloaded water cell. */
    public String waterSourceState(BlockPos source) {
        return source != null && client.world != null && isLoaded(source)
                ? client.world.getBlockState(source).toString() : "";
    }

    public boolean missionWaterSourceValid(BlockPos source) {
        return source != null && client.world != null && isLoaded(source) && protectedAreas != null
                && missionWaterBlockValid(client.world.getBlockState(source),
                protectedAreas.decide(ProtectedAreaPolicy.Action.FLUID, currentDimension(),
                        source.getX(), source.getY(), source.getZ(), true).allowed());
    }

    static boolean missionWaterBlockValid(BlockState state, boolean fluidAllowed) {
        return state != null && fluidAllowed && state.getFluidState().isIn(FluidTags.WATER)
                && state.getFluidState().isStill() && state.getBlock() instanceof FluidDrainable;
    }

    public Optional<BlockHitResult> missionWaterHit(BlockPos source, String expectedState) {
        if (!missionWaterSourceValid(source) || !waterSourceState(source).equals(expectedState))
            return Optional.empty();
        return BlockInteractionRaycaster.fluidSourceAimNow(client, source)
                .map(BlockInteractionRaycaster.FluidSourceAim::hit);
    }

    public Optional<RenewableWaterSource> inspectMissionWaterSource(String dimension,
            BlockPos source, String expectedState, BlockPos rejectedStand) {
        if (client.player == null || client.world == null || !currentDimension().equals(dimension)
                || !missionWaterSourceValid(source) || !waterSourceState(source).equals(expectedState))
            return Optional.empty();
        if (missionWaterHit(source, expectedState).isPresent()) return Optional.of(
                new RenewableWaterSource(source.toImmutable(), client.player.getBlockPos(), 0, true));
        return findWaterInteractionStand(source, rejectedStand).map(stand ->
                new RenewableWaterSource(source.toImmutable(), stand.toImmutable(), 0, false));
    }

    /**
     * Applies one pre-verified float packet rotation, then independently proves
     * that the player's exact current vanilla bucket ray still hits the source.
     */
    public boolean aimAtRenewableWater(BlockPos source) {
        Objects.requireNonNull(source, "source");
        if (client.player == null || client.world == null) return false;
        Optional<BlockInteractionRaycaster.FluidSourceAim> aim =
                BlockInteractionRaycaster.fluidSourceAimNow(client, source);
        if (aim.isEmpty()) return false;
        client.player.setYaw(MathHelper.wrapDegrees(aim.orElseThrow().yaw()));
        client.player.setPitch(MathHelper.clamp(aim.orElseThrow().pitch(), -90.0F, 90.0F));
        return BlockInteractionRaycaster.fluidSourceUnderCurrentAim(client, source).isPresent();
    }

    /** Read-only exact geometry value for restart/result reconciliation. */
    public int renewableHomeWaterNeighborMask(BlockPos source) {
        if (client.world == null || source == null || !isLoaded(source)) return 0;
        int mask = renewableWaterNeighborMask(source);
        return renewableWaterGeometrySafe(source, mask) ? mask : 0;
    }

    /** Marks the current geometry/GUI as unsuitable and keeps the durable leaf alive. */
    public void recover(String operationId, String reason) {
        Session session = sessions.get(operationId);
        if (session == null) return;
        boolean workspaceHandoffReported = false;
        boolean ownedStillExists = session.ownedPlacement != null
                && client.world != null
                && ownedInCurrentDimension(session)
                && isLoaded(session.ownedPlacement)
                && validOwnedBlock(session.kind, session.ownedPlacement);
        // An Entity-placed station is durable mission state, not a disposable bad
        // candidate. Recovery must retry its stand/sightline instead of orphaning it.
        boolean ownedContextUnknown = session.ownedPlacement != null
                && (!ownedInCurrentDimension(session) || !isLoaded(session.ownedPlacement));
        if (session.target != null && !ownedStillExists && !ownedContextUnknown) session.reject(session.target);
        if (session.placement != null) {
            if (session.placementFromWorkspace) {
                ClientWorkspacePreparationController.Result feedback = workspace.reportPlacementRejected(
                        operationId,
                        session.placement.placeAt(),
                        Objects.requireNonNullElse(reason, "workspace handoff recovery requested"));
                session.workspaceActive = feedback.state()
                        != ClientWorkspacePreparationController.State.BLOCKED;
                workspaceHandoffReported = true;
            }
            session.reject(session.placement.placeAt());
        }
        inventory.closeHandledScreen();
        clearPinnedScreenAcknowledgement(session);
        session.target = ownedStillExists || ownedContextUnknown ? session.ownedPlacement : null;
        if (ownedStillExists) session.rejected.remove(session.ownedPlacement);
        session.standAt = null;
        session.placement = null;
        session.placementFromWorkspace = false;
        session.openAttemptAt = 0;
        session.placementAttemptAt = 0;
        session.placementInventoryBaseline = -1;
        session.placementEvidenceAt = 0L;
        atomicBreak.cancel();
        session.clearAim();
        if (session.workspaceActive && !workspaceHandoffReported) {
            workspace.resetTransient(operationId);
        }
    }

    public void clear(String operationId) {
        if (operationId != null) {
            sessions.remove(operationId);
            atomicBreak.cancel();
            workspace.clear(operationId);
        }
    }

    /** Clears every transient session in one durable generation namespace. */
    public void clearPrefix(String operationPrefix) {
        String prefix = Objects.requireNonNullElse(operationPrefix, "");
        if (prefix.isBlank()) return;
        List<String> matching = sessions.keySet().stream()
                .filter(id -> id.startsWith(prefix))
                .toList();
        for (String operationId : matching) clear(operationId);
    }

    public void clearAll() {
        atomicBreak.cancel();
        sessions.clear();
        workspace.clearAll();
    }

    /**
     * Stops transient GUI/aim/break ownership while preserving the verified
     * workstation and placement transaction for a later mission resume.
     */
    public void suspendAll() {
        suspendAll(false);
    }

    /** Cancel all work, retaining only the already verified screen for an exact cargo cleanup suffix. */
    public void suspendAllKeepingOpenScreen() {
        suspendAll(true);
    }

    private void suspendAll(boolean keepExactScreen) {
        if (!keepExactScreen) inventory.closeHandledScreen();
        atomicBreak.cancel();
        workspace.suspendAll();
        sessions.values().forEach(session -> {
            if (!keepExactScreen) clearPinnedScreenAcknowledgement(session);
            // A placement packet remains a pending world transaction while the
            // mission is preempted. Preserve its timestamp so resume confirms
            // or times out that exact transaction instead of placing twice.
            session.clearAim();
        });
    }

    /**
     * Starts a fresh bounded geometry attempt after an externally mutable
     * blocker has had time to change. Durable field-kit ownership, exact
     * placement/reclaim facts, and inventory baselines remain intact; only
     * exhausted local stands, rejected geometry, aim and retry counters are
     * discarded.
     */
    public void resetTransientRecoveryForReconciliation() {
        inventory.closeHandledScreen();
        atomicBreak.cancel();
        workspace.clearAll();
        sessions.values().forEach(session -> {
            session.rejected.clear();
            session.failedStands.clear();
            session.reclaimStandReselectionRequired = false;
            session.target = null;
            session.standAt = null;
            session.placement = null;
            session.placementAttemptAt = 0L;
            session.placementInventoryBaseline = -1;
            session.placementEvidenceAt = 0L;
            clearPinnedScreenAcknowledgement(session);
            session.nextInteractionAt = 0L;
            session.openFailures = 0;
            session.failures = 0;
            session.workspaceActive = false;
            session.placementFromWorkspace = false;
            session.sleepCursor = HomeSleepPolicy.Cursor.empty();
            session.sleepInteractionAt = 0L;
            session.sleepFailures = 0;
            session.clearAim();
        });
    }

    /** Routes Paper's exact cancellation evidence to the shared break owner. */
    public void reportServerBreakRejected(
            String dimension,
            int x,
            int y,
            int z,
            long nowMillis) {
        workspace.reportServerBreakRejected(dimension, x, y, z, nowMillis);
    }

    /**
     * Reclaims only a workstation this operation actually placed. Existing
     * world stations are never stolen. Completion waits briefly for the drop
     * to enter Entity's inventory so the next remote task keeps its field kit.
     */
    public Result tickReclaim(
            Kind kind,
            String operationId,
            ControlLease lease,
            FabricBaritonePort baritone,
            long nowMillis) {
        Session session = sessions.compute(operationId, (id, existing) ->
                existing == null || existing.kind != kind ? new Session(id, kind) : existing);
        hydrateFieldKit(session);
        if (session.pendingCarriedProof) persistCarried(session, nowMillis);
        if (!session.persistenceFailure.isBlank()) {
            return new Result(State.BLOCKED, session.persistenceFailure, session.ownedPlacement);
        }
        if (session.ownedPlacement == null) {
            return new Result(State.RECLAIMED,
                    "no Entity-placed " + kind.displayName() + " needs reclaiming", null);
        }
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new Result(State.WAITING, "waiting to reclaim " + kind.displayName(), session.ownedPlacement);
        }
        resetTransientGeometryOnDimensionChange(session);
        if (!ownedInCurrentDimension(session)) {
            return new Result(
                    State.BLOCKED,
                    "cannot verify or reclaim Entity's " + kind.displayName() + " from "
                            + currentDimension() + "; its durable owner record is in "
                            + session.ownedDimension,
                    session.ownedPlacement);
        }
        inventory.closeHandledScreen();
        clearPinnedScreenAcknowledgement(session);
        BlockPos target = session.ownedPlacement;
        if (!isLoaded(target)) {
            return approachToLoad(
                    operationId + ":load-reclaim:" + target.asLong(),
                    target,
                    "owned " + kind.displayName() + " reclaim chunk",
                    lease,
                    baritone,
                    session);
        }
        if (!kind.matches(client.world.getBlockState(target))) {
            atomicBreak.cancel();
            if (session.reclaimBrokenAt == 0) session.reclaimBrokenAt = nowMillis;
            if (session.reclaimInventoryBaseline >= 0
                    && inventory.count(kind.itemId()) > session.reclaimInventoryBaseline) {
                session.pendingCarriedProof = true;
                if (!persistCarried(session, nowMillis)) {
                    return new Result(State.BLOCKED, session.persistenceFailure, target);
                }
                session.ownedPlacement = null;
                session.ownedDimension = "";
                session.placementOwnedBySession = false;
                session.reclaimStandReselectionRequired = false;
                return new Result(State.RECLAIMED,
                        "reclaimed Entity's " + kind.displayName() + " for the field kit", target);
            }
            if (session.reclaimInventoryBaseline < 0) {
                return new Result(State.AWAITING_DROP,
                        "restored an interrupted reclaim without a durable inventory baseline; "
                                + "locating the exact owned " + kind.displayName()
                                + " drop before trusting any already-carried duplicate",
                        target);
            }
            return new Result(State.AWAITING_DROP,
                    "workstation block is gone; awaiting verified collection of its item entity", target);
        }
        if (session.reclaimInventoryBaseline < 0) {
            session.reclaimInventoryBaseline = inventory.count(kind.itemId());
        }

        Optional<BlockHitResult> hit = visibleHit(target);
        boolean inReach = client.player.squaredDistanceTo(Vec3d.ofCenter(target)) <= INTERACT_DISTANCE_SQUARED;
        if (!inReach || hit.isEmpty() || session.reclaimStandReselectionRequired) {
            if (session.standAt == null || !isStandable(session.standAt)
                    || interactionHitFrom(session.standAt, target).isEmpty()) {
                Optional<BlockPos> stand = findInteractionStand(target, session.failedStands);
                if (stand.isEmpty()) {
                    Optional<BlockPos> prospective = findProspectiveReclaimStand(
                            target, session.failedStands);
                    if (prospective.isEmpty()) {
                        return reclaimCandidatesExhausted(session,
                                "no safe existing or excavatable sightline remains to reclaim "
                                        + kind.displayName(), target);
                    }
                    session.standAt = prospective.orElseThrow();
                    session.reclaimStandReselectionRequired = false;
                    return approachProspectiveReclaimStand(
                            operationId,
                            session.standAt,
                            kind,
                            target,
                            lease,
                            baritone,
                            session);
                }
                session.standAt = stand.orElseThrow();
                session.reclaimStandReselectionRequired = false;
            }
            return approach(operationId + ":reclaim:" + target.asLong(), session.standAt,
                    "reclaim position for " + kind.displayName(), lease, baritone, session);
        }

        // Directly starting in reach has no planner goal, but it is still an
        // exact, verified reclaim stand. Capture it before the break begins so
        // a failure rejects this strategy only, not wherever Entity stands on
        // a later tick.
        if (session.standAt == null) {
            session.standAt = client.player.getBlockPos().toImmutable();
        }

        // Transfer both hand and inventory ownership before selecting a reclaim
        // tool. This closes the autoTool/hotbar oscillation race.
        baritone.neutralizeActuators("reclaiming Entity-placed workstation");
        ToolPreparation tool = prepareReclaimTool(kind, nowMillis);
        if (!tool.ready()) {
            return new Result(tool.missingRequiredTool() ? State.ITEM_MISSING : State.RECLAIMING,
                    tool.detail(), target);
        }

        if (!persistReclaimPending(session, nowMillis)) {
            return new Result(State.BLOCKED, session.persistenceFailure, target);
        }
        BlockHitResult visible = hit.orElseThrow();
        if (!aimReady(session, "reclaim:" + target.asLong(), visible.getPos())) {
            return new Result(State.RECLAIMING, "aiming to reclaim " + kind.displayName(), target);
        }
        AtomicBlockBreakController.Result breaking = atomicBreak.tick(
                operationId + ":reclaim:" + kind.name(),
                target,
                visible,
                nowMillis);
        return switch (breaking.state()) {
            case WAITING -> new Result(State.WAITING, breaking.detail(), target);
            case STARTED, CONTINUE, RESTART_REQUIRED -> new Result(
                    State.RECLAIMING,
                    "breaking Entity's " + kind.displayName() + " for pickup"
                            + (breaking.restarts() > 0
                            ? " (pinned restart " + breaking.restarts() + ")" : ""),
                    target);
            case COMPLETE -> new Result(
                    State.AWAITING_DROP,
                    "world confirmed the owned " + kind.displayName()
                            + " was broken; awaiting its exact drop",
                    target);
            case TOOL_PREEMPTED -> reclaimFailure(
                    session,
                    "held-tool ownership changed while reclaiming " + kind.displayName(),
                    target);
            case OUT_OF_REACH, SIGHTLINE_LOST -> reclaimFailure(
                    session, breaking.detail(), target);
            case ACTION_REJECTED -> reclaimFailure(
                    session,
                    "local interaction manager rejected reclaiming " + kind.displayName(),
                    target);
            case SERVER_REJECTED -> localFailure(
                    session,
                    "Paper rejected reclaiming the exact owned " + kind.displayName(),
                    target);
            case STALLED -> reclaimFailure(
                    session,
                    "could not reclaim owned " + kind.displayName() + ": " + breaking.detail(),
                    target);
        };
    }

    private ToolPreparation prepareReclaimTool(Kind kind, long nowMillis) {
        List<String> candidates = kind == Kind.FURNACE
                ? List.of("minecraft:netherite_pickaxe", "minecraft:diamond_pickaxe",
                "minecraft:iron_pickaxe", "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe")
                : List.of("minecraft:netherite_axe", "minecraft:diamond_axe",
                "minecraft:iron_axe", "minecraft:stone_axe", "minecraft:wooden_axe");
        for (String tool : candidates) {
            if (!inventory.has(tool, 1)) continue;
            ClientInventoryController.ClickResult selected = inventory.moveToHotbar(tool, 7, nowMillis);
            return switch (selected) {
                case ALREADY_DONE -> new ToolPreparation(true, false, "tool ready");
                case CLICKED, WAITING -> new ToolPreparation(false, false,
                        "preparing " + tool.substring("minecraft:".length()) + " to reclaim " + kind.displayName());
                case ITEM_MISSING -> new ToolPreparation(false, false, "rechecking reclaim tool inventory");
                case SLOT_UNAVAILABLE, CURSOR_NOT_EMPTY -> new ToolPreparation(false, false,
                        "inventory could not prepare reclaim tool: " + selected);
            };
        }
        if (kind == Kind.FURNACE) {
            return new ToolPreparation(false, true,
                    "a pickaxe is required to reclaim the furnace without destroying it");
        }
        return new ToolPreparation(true, false, "reclaiming crafting table by hand");
    }

    public Optional<BlockPos> findNearest(Block block) {
        if (client.player == null || client.world == null) return Optional.empty();
        return findBlocks(block).stream().min(Comparator.comparingDouble(
                pos -> pos.getSquaredDistance(client.player.getBlockPos())));
    }

    public Optional<BlockPos> lastPlacement() {
        return Optional.ofNullable(lastPlacement);
    }

    public long reclaimBrokenAt(String operationId) {
        Session session = sessions.get(operationId);
        return session == null ? 0L : session.reclaimBrokenAt;
    }

    /** Commits an exact ground-item collection proof after an interrupted reclaim. */
    public boolean confirmReclaimed(String operationId, long nowMillis) {
        Session session = sessions.get(operationId);
        if (session == null) return false;
        session.pendingCarriedProof = true;
        if (!persistCarried(session, nowMillis)) return false;
        session.ownedPlacement = null;
        session.ownedDimension = "";
        session.placementOwnedBySession = false;
        session.reclaimStandReselectionRequired = false;
        session.reclaimInventoryBaseline = -1;
        session.reclaimBrokenAt = 0L;
        atomicBreak.cancel();
        return true;
    }

    public String persistenceFailure(String operationId) {
        Session session = sessions.get(operationId);
        return session == null ? "" : session.persistenceFailure;
    }

    public Optional<BlockPos> ownedPlacement(String operationId) {
        Session session = sessions.get(operationId);
        return session == null ? Optional.empty() : Optional.ofNullable(session.ownedPlacement);
    }

    public Optional<String> ownedPlacementDimension(String operationId) {
        Session session = sessions.get(operationId);
        return session == null || session.ownedPlacement == null || session.ownedDimension.isBlank()
                ? Optional.empty()
                : Optional.of(session.ownedDimension);
    }

    /**
     * Planning-level view of a verified Entity field-kit station that is
     * currently outside the inventory. This survives leaf replacement and a
     * full client restart through {@link FieldKitLedger}; it intentionally
     * excludes LOST assets so the planner must replace those honestly.
     */
    public boolean hasDurableFieldKitPlacement(Kind kind) {
        Objects.requireNonNull(kind, "kind");
        String dimension = currentDimension();
        if (dimension.isBlank()) return false;
        return fieldKit.assets(fieldKitKind(kind)).stream().anyMatch(asset ->
                !FieldKitAssetPolicy.isHomeAsset(asset.id())
                        && FieldKitAssetPolicy.isReusablePlacement(asset)
                        && dimension.equals(asset.lastKnownPosition().dimension())
                );
    }

    /** Carried owned station stacks are infrastructure, never Home surplus. */
    public Map<String, Integer> carriedFieldKitCounts() {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        fieldKit.snapshot().assets().values().stream()
                .filter(asset -> asset.state() == FieldKitLedger.AssetState.CARRIED)
                .sorted(Comparator.comparing(FieldKitLedger.Asset::id))
                .forEach(asset -> counts.merge(
                        asset.kind().item(), 1, Math::addExact));
        return Map.copyOf(counts);
    }

    /** Reconstructs physical ownership recorded in the durable plan after client restart. */
    public boolean restoreOwnedPlacement(
            Kind kind,
            String operationId,
            BlockPos position,
            String dimension) {
        if (kind == null || operationId == null || operationId.isBlank()
                || position == null) return false;
        Session session = sessions.compute(operationId, (id, existing) ->
                existing == null || existing.kind != kind ? new Session(id, kind) : existing);
        hydrateFieldKit(session);
        if (!session.ownedAssetId.isBlank()) {
            FieldKitLedger.Asset durable = fieldKit.asset(session.ownedAssetId);
            if (durable.state() == FieldKitLedger.AssetState.CARRIED
                    || durable.state() == FieldKitLedger.AssetState.LOST) {
                session.ownedPlacement = null;
                session.ownedDimension = "";
                session.placementOwnedBySession = false;
                session.reclaimStandReselectionRequired = false;
                session.target = null;
                return false;
            }
            FieldKitLedger.Position durablePosition = durable.lastKnownPosition();
            if (durablePosition != null) {
                position = new BlockPos(
                        durablePosition.x(), durablePosition.y(), durablePosition.z());
                dimension = durablePosition.dimension();
            }
        }
        session.ownedPlacement = position.toImmutable();
        session.ownedDimension = dimension == null || dimension.isBlank()
                ? currentDimension()
                : dimension;
        boolean correctDimension = client.world != null && ownedInCurrentDimension(session);
        boolean loaded = correctDimension && isLoaded(position);
        boolean blockPresent = loaded && validOwnedBlock(kind, position);
        session.placementOwnedBySession = blockPresent;
        session.reclaimStandReselectionRequired = false;
        // Keep the target when its same-dimension chunk is merely unloaded so tickOpen
        // routes back and loads it before making an existence decision.
        session.target = correctDimension && (!loaded || blockPresent)
                ? session.ownedPlacement
                : null;
        if (blockPresent) {
            session.rejected.remove(session.ownedPlacement);
            if (session.ownedAssetId.isBlank()) {
                persistPlaced(session, session.ownedPlacement, System.currentTimeMillis());
            }
        }
        return blockPresent;
    }

    /** Ends ownership only after the planner has committed to replacing a proven-lost drop. */
    public void forgetLostOwnedPlacement(String operationId) {
        forgetLostOwnedPlacement(
                operationId,
                "bounded recovery found no owned workstation block or matching drop",
                System.currentTimeMillis());
    }

    public void forgetLostOwnedPlacement(String operationId, String reason, long nowMillis) {
        Session session = sessions.get(operationId);
        if (session == null) return;
        atomicBreak.cancel();
        persistLost(session, reason, nowMillis);
        session.ownedPlacement = null;
        session.ownedDimension = "";
        session.placementOwnedBySession = false;
        session.reclaimStandReselectionRequired = false;
        session.reclaimInventoryBaseline = -1;
        session.reclaimBrokenAt = 0L;
    }

    private Result confirmPendingPlacement(Session session, long nowMillis) {
        if (session.placement == null || session.placementAttemptAt <= 0) return null;
        Placement pending = session.placement;
        boolean exactBlockPresent = validOwnedBlock(session.kind, pending.placeAt());
        int itemCountNow = inventory.count(session.kind.itemId());
        boolean correlatedEvidence = exactBlockPresent
                && session.placementInventoryBaseline > 0
                && itemCountNow < session.placementInventoryBaseline;
        if (correlatedEvidence) {
            if (session.placementEvidenceAt <= 0L) {
                session.placementEvidenceAt = nowMillis;
            }
        } else {
            session.placementEvidenceAt = 0L;
        }
        long elapsed = Math.max(0L, nowMillis - session.placementAttemptAt);
        long evidenceAge = session.placementEvidenceAt <= 0L
                ? 0L : Math.max(0L, nowMillis - session.placementEvidenceAt);
        WorkstationPlacementConfirmationPolicy.Action confirmation =
                WorkstationPlacementConfirmationPolicy.decide(
                        exactBlockPresent,
                        session.placementInventoryBaseline,
                        itemCountNow,
                        elapsed,
                        evidenceAge,
                        PLACEMENT_CONFIRM_MILLIS,
                        WorkstationPlacementConfirmationPolicy.DEFAULT_STABILITY_MILLIS);
        if (confirmation == WorkstationPlacementConfirmationPolicy.Action.CONFIRM) {
            lastPlacement = pending.placeAt();
            session.ownedPlacement = pending.placeAt().toImmutable();
            session.ownedDimension = currentDimension();
            session.placementOwnedBySession = true;
            session.rejected.remove(session.ownedPlacement);
            session.target = pending.placeAt();
            session.standAt = pending.standAt();
            session.placement = null;
            session.placementAttemptAt = 0;
            session.placementInventoryBaseline = -1;
            session.placementEvidenceAt = 0L;
            session.workspaceActive = false;
            session.placementFromWorkspace = false;
            workspace.clear(session.operationId);
            if (!persistPlaced(session, session.ownedPlacement, nowMillis)) {
                return new Result(State.BLOCKED, session.persistenceFailure, session.ownedPlacement);
            }
            return new Result(State.PLACING,
                    "verified placed " + session.kind.displayName() + "; preparing to open it",
                    session.target);
        }
        if (confirmation != WorkstationPlacementConfirmationPolicy.Action.RETRY) {
            String detail = switch (confirmation) {
                case WAIT_FOR_WORLD -> "waiting for server block confirmation of ";
                case WAIT_FOR_ITEM_CONSUMPTION -> "waiting for server inventory consumption of ";
                case STABILIZE_CORRELATED_EVIDENCE -> "stabilizing correlated block/inventory proof for ";
                case CONFIRM, RETRY -> throw new IllegalStateException("handled placement verdict");
            };
            return new Result(State.PLACING,
                    detail + session.kind.displayName() + " placement",
                    pending.placeAt());
        }
        boolean workspacePlacement = session.placementFromWorkspace;
        session.reject(pending.placeAt());
        session.placement = null;
        session.placementAttemptAt = 0;
        session.placementInventoryBaseline = -1;
        session.placementEvidenceAt = 0L;
        if (workspacePlacement) {
            return workspacePlacementFailure(
                    session,
                    pending.placeAt(),
                    "server did not confirm " + session.kind.displayName() + " placement at "
                            + coordinates(pending.placeAt()) + " (protected or invalid position)");
        }
        return localFailure(session,
                "server did not confirm " + session.kind.displayName() + " placement at "
                        + coordinates(pending.placeAt()) + " (protected or invalid position)",
                pending.placeAt());
    }

    private Result approachAndOpen(
            Kind kind,
            String operationId,
            ControlLease lease,
            FabricBaritonePort baritone,
            Session session,
            long nowMillis) {
        BlockPos target = session.target;
        if (session.homePinned) {
            if (target == null || !target.equals(session.ownedPlacement)
                    || !pinnedTargetVerified(session)) {
                clearPinnedScreenAcknowledgement(session);
                return new Result(State.BLOCKED,
                        "pinned home " + kind.displayName()
                                + " is not the exact verified owned placement",
                        session.ownedPlacement);
            }
            // A matching foreign container cannot be allowed to become the
            // acknowledgement source for the pinned block interaction.
            if (!inventory.isPlayerInventoryOpen()) {
                clearPinnedScreenAcknowledgement(session);
                boolean closed = inventory.closeHandledScreen(nowMillis);
                return new Result(State.WAITING,
                        closed
                                ? "closed an unrelated container before opening the pinned home "
                                        + kind.displayName()
                                : "reconciling the open container before the pinned home "
                                        + kind.displayName(),
                        target);
            }
        }
        Optional<BlockHitResult> currentHit = visibleHit(target);
        boolean inReach = client.player.squaredDistanceTo(Vec3d.ofCenter(target)) <= INTERACT_DISTANCE_SQUARED;
        if (!inReach || currentHit.isEmpty()) {
            if (session.standAt == null || !isStandable(session.standAt)
                    || interactionHitFrom(session.standAt, target).isEmpty()) {
                Optional<BlockPos> stand = findInteractionStand(target, session.failedStands);
                if (stand.isEmpty()) {
                    if (session.homePinned) {
                        Optional<BlockPos> prospective = findProspectiveHomeAccessStand(
                                target, session.failedStands);
                        if (prospective.isPresent()) {
                            session.standAt = prospective.orElseThrow();
                            return approachProspectiveOpenStand(
                                    operationId,
                                    session.standAt,
                                    kind,
                                    target,
                                    lease,
                                    baritone,
                                    session);
                        }
                    }
                    session.reject(target);
                    session.target = null;
                    session.standAt = null;
                    return localFailure(session,
                            "no reachable sightline to " + kind.displayName() + " at " + coordinates(target),
                            target);
                }
                session.standAt = stand.orElseThrow();
            }
            if (reachedVerifiedStand(session, session.standAt)) {
                session.failedStands.add(session.standAt);
                Optional<BlockPos> alternative = findInteractionStand(target, session.failedStands);
                if (alternative.isPresent()) {
                    session.standAt = alternative.orElseThrow();
                } else {
                    session.reject(target);
                    session.target = null;
                    session.standAt = null;
                    return localFailure(session,
                            "reached " + kind.displayName() + " but no side is actually visible",
                            target);
                }
            }
            return approach(operationId + ":open:" + target.asLong(), session.standAt,
                    kind.displayName(), lease, baritone, session);
        }

        baritone.cancel(lease.epoch(), "opening verified workstation directly");
        if (session.openAttemptAt > 0) {
            if (nowMillis - session.openAttemptAt < SCREEN_CONFIRM_MILLIS) {
                return new Result(State.OPENING,
                        "waiting for verified " + kind.displayName() + " screen", target);
            }
            session.openAttemptAt = 0;
            clearPinnedInteractionAttempt(session);
            session.openFailures++;
            if (session.openFailures >= 2) {
                session.reject(target);
                session.target = null;
                session.standAt = null;
                return localFailure(session,
                        kind.displayName() + " interaction produced no matching screen twice",
                        target);
            }
        }
        if (nowMillis < session.nextInteractionAt) {
            return new Result(State.OPENING, "waiting to interact with " + kind.displayName(), target);
        }
        BlockHitResult hit = currentHit.orElseThrow();
        if (!aimReady(session, "open:" + target.asLong(), hit.getPos())) {
            return new Result(State.OPENING, "aiming at visible " + kind.displayName(), target);
        }
        String authorityOperation = operationId + ":open-authority";
        HomeEconomySession.ContainerIdentity chestIdentity = session.homePinned && kind == Kind.CHEST
                ? observePinnedChestGeometry(session).orElse(null) : null;
        if (session.homePinned && kind == Kind.CHEST && (chestIdentity == null
                || session.requiredChestIdentity != null
                && !MinecraftHomeStorageGeometry.sameLayout(session.requiredChestIdentity, chestIdentity))) {
            return new Result(State.BLOCKED, "registered chest layout differs from its unresolved transfer", target);
        }
        if (session.homePinned && kind == Kind.CHEST && inventoryMutationPending()) {
            return new Result(State.WAITING, "reconcile inventory/cursor work before opening registered storage", target);
        }
        Result authority = exactHomeAuthority(
                session,
                authorityOperation,
                ProtectedAreaPolicy.Action.CONTAINER,
                chestIdentity == null ? List.of(target) : chestIdentity.halves().stream()
                        .map(WorkstationController::chestPosition).toList(),
                target,
                nowMillis);
        if (authority != null) return authority;
        int interactionSourceSyncId = client.player.currentScreenHandler.syncId;
        ActionResult action = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        completeExactHomeAuthority(authorityOperation);
        client.player.swingHand(Hand.MAIN_HAND);
        session.clearAim();
        session.nextInteractionAt = nowMillis + INTERACTION_INTERVAL_MILLIS;
        if (!action.isAccepted()) {
            Optional<MinecraftActuatorGateway.BlockedInteractionAttempt> blocked =
                    MinecraftActuatorGateway.shared(client).takeBlockedInteractionAttempt();
            if (blocked.isPresent()) {
                clearPinnedInteractionAttempt(session);
                return Result.worldPolicyDenied(
                        blocked.orElseThrow().detail(), target);
            }
            clearPinnedInteractionAttempt(session);
            return localFailure(session, "server rejected " + kind.displayName() + " interaction", target);
        }
        session.openAttemptAt = nowMillis;
        if (session.homePinned) {
            session.pinnedInteractionPending = true;
            session.pinnedInteractionSourceSyncId = interactionSourceSyncId;
            session.pinnedChestInteractionIdentity = chestIdentity;
        }
        return new Result(State.OPENING,
                "interaction accepted; verifying " + kind.displayName() + " screen", target);
    }

    private Result place(
            Kind kind,
            String operationId,
            ControlLease lease,
            FabricBaritonePort baritone,
            Session session,
            long nowMillis) {
        if (!inventory.has(kind.itemId(), 1)) {
            return new Result(State.ITEM_MISSING, "missing " + kind.itemId(), null);
        }
        inventory.closeHandledScreen();
        clearPinnedScreenAcknowledgement(session);
        // Workspace preparation owns the main hand until every excavation and
        // positioning step is complete. Selecting the station before this phase
        // made the outer controller choose a furnace/table every tick while the
        // nested workspace controller chose a pickaxe, repeatedly resetting both
        // the held item and the block-breaking session.
        if (session.workspaceActive) {
            Result anchorRoute = routeToHomeWorkspaceAnchorIfNeeded(
                    operationId, lease, baritone, session);
            if (anchorRoute != null) return anchorRoute;
            Result preparation = tickWorkspace(operationId, lease, session, nowMillis);
            if (preparation != null) return preparation;
        }
        if (session.placement == null) {
            Optional<Placement> placement = findPlacement(session);
            if (placement.isEmpty()) {
                if (session.homePinned) {
                    return new Result(State.BLOCKED,
                            session.homeLayoutFailure.isBlank()
                                    ? "no coherent non-destructive asset layout remains in the bounded Home workspace"
                                    : session.homeLayoutFailure,
                            session.homeAnchor);
                }
                session.workspaceActive = true;
                Result anchorRoute = routeToHomeWorkspaceAnchorIfNeeded(
                        operationId, lease, baritone, session);
                if (anchorRoute != null) return anchorRoute;
                Result preparation = tickWorkspace(operationId, lease, session, nowMillis);
                if (preparation != null) return preparation;
            }
            if (session.placement == null) {
                session.placement = placement.orElseThrow();
                session.placementFromWorkspace = false;
            }
        }
        Placement candidate = session.placement;
        if (!reachedVerifiedStand(session, candidate.standAt())) {
            return approach(operationId + ":place:" + candidate.placeAt().asLong(), candidate.standAt(),
                    "placement position for " + kind.displayName(), lease, baritone, session);
        }

        baritone.cancel(lease.epoch(), "placing verified workstation directly");
        ClientInventoryController.ClickResult selected = inventory.moveToHotbar(kind.itemId(), 8, nowMillis);
        if (selected == ClientInventoryController.ClickResult.CLICKED
                || selected == ClientInventoryController.ClickResult.WAITING) {
            return new Result(State.PLACING, "moving " + kind.displayName() + " to hotbar", candidate.placeAt());
        }
        if (selected != ClientInventoryController.ClickResult.ALREADY_DONE) {
            return localFailure(session, "could not select " + kind.itemId() + ": " + selected, candidate.placeAt());
        }
        Optional<BlockHitResult> visibleHit = placementHit(candidate);
        if (visibleHit.isEmpty()) {
            boolean workspacePlacement = session.placementFromWorkspace;
            session.reject(candidate.placeAt());
            session.placement = null;
            if (workspacePlacement) {
                return workspacePlacementFailure(
                        session,
                        candidate.placeAt(),
                        "lost the verified support face for " + kind.displayName() + " at "
                                + coordinates(candidate.placeAt()));
            }
            return localFailure(session,
                    "lost the verified support face for " + kind.displayName() + " at "
                            + coordinates(candidate.placeAt()), candidate.placeAt());
        }
        if (nowMillis < session.nextInteractionAt) {
            return new Result(State.PLACING, "waiting to place " + kind.displayName(), candidate.placeAt());
        }
        if (session.homePinned && session.homePhysicalAttemptStartedAt >= 0L
                && (session.homePhysicalAttempts >= HOME_MAX_PHYSICAL_REPAIR_ATTEMPTS
                || nowMillis - session.homePhysicalAttemptStartedAt
                > HOME_REPAIR_WINDOW_MILLIS)) {
            return new Result(
                    State.BLOCKED,
                    "Home " + kind.displayName() + " repair degraded after "
                            + session.homePhysicalAttempts
                            + " physical placement attempt(s) within 30 seconds at "
                            + coordinates(candidate.placeAt())
                            + "; owner set/relocate/clear remains available",
                    candidate.placeAt());
        }
        BlockHitResult hit = visibleHit.orElseThrow();
        if (!aimReady(session, "place:" + candidate.placeAt().asLong(), hit.getPos())) {
            return new Result(State.PLACING,
                    "aiming at verified support for " + kind.displayName(), candidate.placeAt());
        }
        if (kind == Kind.WHITE_BED
                && !bedFootprintAvailable(
                session, candidate.placeAt(), client.player.getHorizontalFacing())) {
            session.reject(candidate.placeAt());
            session.placement = null;
            return localFailure(session,
                    "the aimed white-bed footprint is no longer clear and supported",
                    candidate.placeAt());
        }
        List<BlockPos> placementTargets = kind == Kind.WHITE_BED
                ? List.of(
                        candidate.placeAt(),
                        candidate.placeAt().offset(client.player.getHorizontalFacing()))
                : List.of(candidate.placeAt());
        String authorityOperation = operationId + ":place-authority";
        Result authority = exactHomeAuthority(
                session,
                authorityOperation,
                ProtectedAreaPolicy.Action.PLACE,
                placementTargets,
                candidate.placeAt(),
                nowMillis);
        if (authority != null) return authority;
        int placementInventoryBaseline = inventory.count(kind.itemId());
        ActionResult action = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        completeExactHomeAuthority(authorityOperation);
        client.player.swingHand(Hand.MAIN_HAND);
        session.clearAim();
        session.nextInteractionAt = nowMillis + INTERACTION_INTERVAL_MILLIS;
        if (!action.isAccepted()) {
            Optional<MinecraftActuatorGateway.BlockedInteractionAttempt> blocked =
                    MinecraftActuatorGateway.shared(client).takeBlockedInteractionAttempt();
            if (blocked.isPresent()) {
                return Result.worldPolicyDenied(
                        blocked.orElseThrow().detail(), candidate.placeAt());
            }
            boolean workspacePlacement = session.placementFromWorkspace;
            session.reject(candidate.placeAt());
            session.placement = null;
            if (workspacePlacement) {
                return workspacePlacementFailure(
                        session,
                        candidate.placeAt(),
                        "server rejected " + kind.displayName() + " placement at "
                                + coordinates(candidate.placeAt()));
            }
            return localFailure(session,
                    "server rejected " + kind.displayName() + " placement at "
                            + coordinates(candidate.placeAt()), candidate.placeAt());
        }
        if (session.homePinned) {
            if (session.homePhysicalAttemptStartedAt < 0L) {
                session.homePhysicalAttemptStartedAt = nowMillis;
            }
            session.homePhysicalAttempts++;
        }
        session.placementAttemptAt = nowMillis;
        session.placementInventoryBaseline = placementInventoryBaseline;
        session.placementEvidenceAt = 0L;
        return new Result(State.PLACING,
                "placement packet accepted; waiting for world confirmation", candidate.placeAt());
    }

    /**
     * A pinned Home workspace is always prepared from its reserved anchor.
     * Otherwise sequential placements can climb a hillside/workstation stack
     * and make the next outward route begin beneath an impassable ceiling.
     */
    private Result routeToHomeWorkspaceAnchorIfNeeded(
            String operationId,
            ControlLease lease,
            FabricBaritonePort baritone,
            Session session) {
        if (!session.homePinned || session.homeAnchor == null
                || client.player.getBlockPos().equals(session.homeAnchor)) return null;
        return approach(
                operationId + ":home-anchor:workspace",
                session.homeAnchor,
                "reserved Home workspace anchor",
                lease,
                baritone,
                session);
    }

    /** Returns null only when a freshly verified prepared placement may proceed immediately. */
    private Result tickWorkspace(
            String operationId,
            ControlLease lease,
            Session session,
            long nowMillis) {
        ClientWorkspacePreparationController.Result result =
                workspace.tick(
                        operationId,
                        lease,
                        nowMillis,
                        session.kind == Kind.CHEST);
        return switch (result.state()) {
            case READY -> {
                BlockPos placement = Objects.requireNonNull(result.placement(), "prepared placement");
                BlockPos stand = Objects.requireNonNull(result.stand(), "prepared stand");
                if (!withinPinnedHomeRadius(session, placement)
                        || session.homePinned && !HomeEconomyPolicy.coherentHomeAssetCell(
                        session.homeAnchor.getX(),
                        session.homeAnchor.getY(),
                        session.homeAnchor.getZ(),
                        placement.getX(), placement.getY(), placement.getZ())) {
                    session.workspaceActive = false;
                    yield new Result(State.BLOCKED,
                            "prepared workstation placement does not preserve the bounded Home anchor plane",
                            placement);
                }
                if (session.kind == Kind.CHEST && !chestOpenable(placement)) {
                    yield workspacePlacementFailure(
                            session,
                            placement,
                            "prepared chest placement does not preserve exact vanilla lid clearance");
                }
                session.rejected.clear();
                session.failedStands.clear();
                session.placement = new Placement(placement, placement.down(), stand);
                session.standAt = stand;
                session.workspaceActive = false;
                session.placementFromWorkspace = true;
                // The workspace controller has already verified support and future sightline;
                // the ordinary placement path revalidates both against the live world.
                yield null;
            }
            case BLOCKED -> {
                session.workspaceActive = false;
                yield new Result(State.BLOCKED, result.detail(), result.placement());
            }
            case RETRY -> new Result(State.RETRY, result.detail(), result.placement());
            case WAITING, PREPARING -> new Result(State.PLACING, result.detail(), result.placement());
        };
    }

    private Result workspacePlacementFailure(
            Session session,
            BlockPos placement,
            String detail) {
        ClientWorkspacePreparationController.Result feedback =
                workspace.reportPlacementRejected(session.operationId, placement, detail);
        session.placementFromWorkspace = false;
        session.workspaceActive = feedback.state()
                != ClientWorkspacePreparationController.State.BLOCKED;
        State state = feedback.state() == ClientWorkspacePreparationController.State.BLOCKED
                ? State.BLOCKED
                : State.RETRY;
        return new Result(state, feedback.detail(), placement);
    }

    private Result approachToLoad(
            String goalId,
            BlockPos position,
            String description,
            ControlLease lease,
            FabricBaritonePort baritone,
            Session session) {
        inventory.closeHandledScreen();
        clearPinnedScreenAcknowledgement(session);
        BaritonePort.Goal goal = new BaritonePort.Goal(
                goalId,
                "goto",
                Map.of(
                        "x", Integer.toString(position.getX()),
                        "y", Integer.toString(position.getY()),
                        "z", Integer.toString(position.getZ()),
                        "range", "4"));
        baritone.start(goal, lease);
        BaritonePort.Status status = baritone.poll(goalId);
        if (status.state() == BaritonePort.State.BLOCKED) {
            return new Result(
                    State.BLOCKED,
                    "could not load " + description + " without abandoning its durable record: "
                            + status.detail(),
                    position);
        }
        if (status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
            return new Result(
                    State.RETRY,
                    "retrying route to load " + description + ": " + status.detail(),
                    position);
        }
        session.clearAim();
        return new Result(
                State.APPROACHING,
                (status.state() == BaritonePort.State.COMPLETE ? "reached " : "approaching ")
                        + description + " so its chunk can be verified; " + status.detail(),
                position);
    }

    private Result approach(
            String goalId,
            BlockPos standAt,
            String description,
            ControlLease lease,
            FabricBaritonePort baritone,
        Session session) {
        session.clearAim();
        inventory.closeHandledScreen();
        clearPinnedScreenAcknowledgement(session);
        // Reclaim, sleep, and every pinned Home stand are exact geometry proofs,
        // not loose waypoints. GoalNear(1) may complete from an adjacent block
        // that cannot see the support face or that gives a bed another facing.
        boolean exactInteraction = goalId.contains(":reclaim:")
                || goalId.contains(":sleep:");
        String goalRange = Integer.toString(HomeAssetLayoutPolicy.approachRange(
                session.homePinned, exactInteraction));
        BaritonePort.Goal goal = new BaritonePort.Goal(
                goalId,
                "goto",
                Map.of(
                        "x", Integer.toString(standAt.getX()),
                        "y", Integer.toString(standAt.getY()),
                        "z", Integer.toString(standAt.getZ()),
                        "range", goalRange));
        baritone.start(goal, lease);
        BaritonePort.Status status = baritone.poll(goal.missionId());
        if (status.state() == BaritonePort.State.BLOCKED
                || status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
            boolean newlyRejectedStand = session.failedStands.add(standAt.toImmutable());
            session.standAt = null;
            if (session.placementFromWorkspace && goalId.contains(":place:")) {
                BlockPos placement = session.placement == null ? standAt : session.placement.placeAt();
                session.placement = null;
                return workspacePlacementFailure(
                        session,
                        placement,
                        "could not reach the planner-approved workstation stand: " + status.detail());
            }
            if (goalId.contains(":reclaim:")) {
                session.reclaimStandReselectionRequired = true;
                return localReclaimFailure(
                        session, newlyRejectedStand,
                        "could not reach " + description + ": " + status.detail(), standAt);
            }
            return localFailure(session, "could not reach " + description + ": " + status.detail(), standAt);
        }
        return new Result(State.APPROACHING,
                (status.state() == BaritonePort.State.COMPLETE ? "reached " : "approaching ")
                        + description + "; " + status.detail(), standAt);
    }

    private Optional<Target> selectExistingTarget(Kind kind, Set<BlockPos> rejected) {
        ArrayList<Target> candidates = new ArrayList<>();
        for (BlockPos block : findBlocks(kind.block())) {
            if (rejected.contains(block)
                    || !ordinaryWorkstationAllowed(ProtectedAreaPolicy.Action.CONTAINER, block)) continue;
            Optional<BlockPos> stand = findInteractionStand(block, Set.of());
            stand.ifPresent(pos -> candidates.add(new Target(block, pos)));
        }
        BlockPos origin = client.player.getBlockPos();
        return candidates.stream().min(Comparator.comparingDouble(target ->
                target.standAt().getSquaredDistance(origin)
                        + target.block().getSquaredDistance(origin) * 0.15));
    }

    /** Ordinary field crafting cannot borrow the separate exact pinned-Home permit. */
    private boolean ordinaryWorkstationAllowed(ProtectedAreaPolicy.Action action, BlockPos position) {
        return protectedAreas != null && protectedAreas.decide(action, currentDimension(),
                position.getX(), position.getY(), position.getZ(), false).allowed();
    }

    private List<BlockPos> findBlocks(Block block) {
        if (client.player == null || client.world == null) return List.of();
        BlockPos origin = client.player.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        ArrayList<BlockPos> found = new ArrayList<>();
        for (int y = -SEARCH_RADIUS / 2; y <= SEARCH_RADIUS / 2; y++) {
            for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!client.world.isChunkLoaded(cursor.getX() >> 4, cursor.getZ() >> 4)) continue;
                    if (client.world.getBlockState(cursor).isOf(block)) found.add(cursor.toImmutable());
                }
            }
        }
        return found;
    }

    private Optional<BlockPos> findInteractionStand(BlockPos target, Set<BlockPos> excluded) {
        ArrayList<BlockPos> stands = new ArrayList<>();
        for (int y = READY_STAND_MIN_Y; y <= READY_STAND_MAX_Y; y++) {
            for (int x = -STAND_SEARCH_RADIUS; x <= STAND_SEARCH_RADIUS; x++) {
                for (int z = -STAND_SEARCH_RADIUS; z <= STAND_SEARCH_RADIUS; z++) {
                    BlockPos stand = target.add(x, y, z);
                    if (excluded.contains(stand) || !isStandable(stand)) continue;
                    if (interactionHitFrom(stand, target).isPresent()) stands.add(stand);
                }
            }
        }
        BlockPos origin = client.player.getBlockPos();
        return stands.stream().min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(origin)));
    }

    private Optional<BlockPos> findSleepInteractionStand(
            BlockPos foot,
            Set<BlockPos> excluded) {
        ArrayList<BlockPos> stands = new ArrayList<>();
        for (int y = READY_STAND_MIN_Y; y <= READY_STAND_MAX_Y; y++) {
            for (int x = -STAND_SEARCH_RADIUS; x <= STAND_SEARCH_RADIUS; x++) {
                for (int z = -STAND_SEARCH_RADIUS; z <= STAND_SEARCH_RADIUS; z++) {
                    BlockPos stand = foot.add(x, y, z);
                    if (excluded.contains(stand) || !isStandable(stand)
                            || interactionHitFrom(stand, foot).isEmpty()
                            || !standWithinOwnedBedServerRange(foot, stand)) {
                        continue;
                    }
                    stands.add(stand);
                }
            }
        }
        BlockPos origin = client.player.getBlockPos();
        return stands.stream().min(Comparator.comparingDouble(
                position -> position.getSquaredDistance(origin)));
    }

    private boolean withinOwnedBedServerRange(BlockPos foot, Vec3d playerPosition) {
        if (client.world == null || foot == null || playerPosition == null
                || !bedStructureVerified(foot)) {
            return false;
        }
        Direction facing = client.world.getBlockState(foot).get(BedBlock.FACING);
        BlockPos head = foot.offset(facing);
        return HomeSleepPolicy.withinServerBedRange(
                playerPosition.x, playerPosition.y, playerPosition.z,
                foot.getX(), foot.getY(), foot.getZ(),
                head.getX(), head.getY(), head.getZ());
    }

    private boolean standWithinOwnedBedServerRange(BlockPos foot, BlockPos stand) {
        if (client.world == null || foot == null || stand == null
                || !bedStructureVerified(foot)) {
            return false;
        }
        Direction facing = client.world.getBlockState(foot).get(BedBlock.FACING);
        BlockPos head = foot.offset(facing);
        return HomeSleepPolicy.standBlockWithinServerBedRange(
                stand.getX(), stand.getY(), stand.getZ(),
                foot.getX(), foot.getY(), foot.getZ(),
                head.getX(), head.getY(), head.getZ());
    }

    /**
     * Plans a future exact stand when an owned workstation is buried or its old
     * body column was filled after Entity left. Unlike ordinary interaction
     * stands, feet/head may be ordinary breakable terrain; the owned workstation
     * itself is structurally excluded from the clearing set by the pure planner.
     */
    private Optional<BlockPos> findProspectiveReclaimStand(
            BlockPos target,
            Set<BlockPos> excluded) {
        Point targetPoint = point(target);
        Point origin = point(client.player.getBlockPos());
        Set<Point> excludedPoints = excluded.stream()
                .map(WorkstationController::point)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return reclaimAccessPlanner.plan(
                        new WorkstationReclaimAccessPlanner.Request(
                                targetPoint, origin, excludedPoints),
                        position -> reclaimAccessCell(block(position), target))
                .map(plan -> block(plan.stand()));
    }

    /**
     * Plans access to a pinned Home asset through disposable natural terrain
     * only. A dirt pillar or stone body column may be cleared, but a
     * player-built or stateful block is never made expendable merely because
     * it obstructs a Home workstation.
     */
    private Optional<BlockPos> findProspectiveHomeAccessStand(
            BlockPos target,
            Set<BlockPos> excluded) {
        Point targetPoint = point(target);
        Point origin = point(client.player.getBlockPos());
        Set<Point> excludedPoints = excluded.stream()
                .map(WorkstationController::point)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        MinecraftWorkspaceProbe probe = new MinecraftWorkspaceProbe(client, target);
        return reclaimAccessPlanner.plan(
                        new WorkstationReclaimAccessPlanner.Request(
                                targetPoint, origin, excludedPoints),
                        position -> homeAccessCell(probe, block(position)))
                .map(plan -> block(plan.stand()));
    }

    private static Cell homeAccessCell(
            MinecraftWorkspaceProbe probe,
            BlockPos position) {
        dev.entity.client.autonomy.policy.WorkspaceWorldProbe.Point point =
                MinecraftWorkspaceProbe.point(position);
        dev.entity.client.autonomy.policy.WorkspaceWorldProbe.Cell cell = probe.cell(point);
        return switch (cell) {
            case OPEN, REPLACEABLE -> Cell.PASSABLE;
            case SOLID -> Cell.BREAKABLE;
            case UNBREAKABLE, PROTECTED, FALLING ->
                    probe.hasFullTopSupport(point) ? Cell.SOLID_SUPPORT : Cell.BLOCKED;
            case FLUID, HAZARD, UNLOADED -> Cell.BLOCKED;
        };
    }

    private Cell reclaimAccessCell(BlockPos position, BlockPos ownedTarget) {
        if (client.world == null
                || !client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
            return Cell.BLOCKED;
        }
        BlockState state = client.world.getBlockState(position);
        if (unsafeLocalState(state)) return Cell.BLOCKED;
        // A falling block is never safe to excavate, but an anchored full-top
        // column is valid floor support for a prospective reclaim stand.
        if (state.getBlock() instanceof FallingBlock) {
            return hasSafeFloorSupport(position) ? Cell.SOLID_SUPPORT : Cell.BLOCKED;
        }
        if (state.getCollisionShape(client.world, position).isEmpty()) {
            return Cell.PASSABLE;
        }
        if (state.getHardness(client.world, position) < 0) {
            return Cell.SOLID_SUPPORT;
        }
        // Never excavate a second workstation/container just to retrieve this
        // one. The exact owned target is allowed only as floor support for the
        // above-table candidate; the planner cannot put it in its clearing list.
        if (!position.equals(ownedTarget) && protectedReclaimObstruction(state)) {
            return Cell.SOLID_SUPPORT;
        }
        return Cell.BREAKABLE;
    }

    private static boolean protectedReclaimObstruction(BlockState state) {
        return state.isOf(Blocks.CRAFTING_TABLE)
                || state.isOf(Blocks.FURNACE)
                || state.isOf(Blocks.BLAST_FURNACE)
                || state.isOf(Blocks.SMOKER)
                || state.isOf(Blocks.CHEST)
                || state.isOf(Blocks.TRAPPED_CHEST)
                || state.isOf(Blocks.BARREL);
    }

    private Result approachProspectiveReclaimStand(
            String operationId,
            BlockPos standAt,
            Kind kind,
            BlockPos ownedTarget,
            ControlLease lease,
            FabricBaritonePort baritone,
            Session session) {
        session.clearAim();
        inventory.closeHandledScreen();
        clearPinnedScreenAcknowledgement(session);
        String goalId = operationId + ":reclaim-access:" + ownedTarget.asLong()
                + ':' + standAt.asLong();
        BaritonePort.Goal goal = new BaritonePort.Goal(
                goalId,
                "goto",
                Map.of(
                        "x", Integer.toString(standAt.getX()),
                        "y", Integer.toString(standAt.getY()),
                        "z", Integer.toString(standAt.getZ()),
                        "range", "0",
                        "allowBreak", "true",
                        "allowPlace", "true",
                        "allowParkourPlace", "true"));
        baritone.start(goal, lease);
        BaritonePort.Status status = baritone.poll(goalId);
        if (status.state() == BaritonePort.State.BLOCKED
                || status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
            boolean newlyRejectedStand = session.failedStands.add(standAt.toImmutable());
            session.standAt = null;
            session.reclaimStandReselectionRequired = true;
            return localReclaimFailure(
                    session, newlyRejectedStand,
                    "could not excavate exact access stand " + coordinates(standAt)
                            + " for the owned " + kind.displayName() + ": " + status.detail(),
                    ownedTarget);
        }
        return new Result(
                State.APPROACHING,
                (status.state() == BaritonePort.State.COMPLETE
                        ? "reached excavated exact access stand; verifying owned "
                        : "excavating and approaching exact access stand for owned ")
                        + kind.displayName() + "; " + status.detail(),
                ownedTarget);
    }

    /** Restores a usable body column for an exact pinned Home interaction. */
    private Result approachProspectiveOpenStand(
            String operationId,
            BlockPos standAt,
            Kind kind,
            BlockPos ownedTarget,
            ControlLease lease,
            FabricBaritonePort baritone,
            Session session) {
        session.clearAim();
        inventory.closeHandledScreen();
        clearPinnedScreenAcknowledgement(session);
        String goalId = operationId + ":open-access:" + ownedTarget.asLong()
                + ':' + standAt.asLong();
        BaritonePort.Goal goal = new BaritonePort.Goal(
                goalId,
                "goto",
                Map.of(
                        "x", Integer.toString(standAt.getX()),
                        "y", Integer.toString(standAt.getY()),
                        "z", Integer.toString(standAt.getZ()),
                        "range", "0",
                        "allowBreak", "true",
                        "allowPlace", "true",
                        "allowParkourPlace", "true"));
        baritone.start(goal, lease);
        BaritonePort.Status status = baritone.poll(goalId);
        if (status.state() == BaritonePort.State.BLOCKED
                || status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
            session.failedStands.add(standAt.toImmutable());
            session.standAt = null;
            return localFailure(
                    session,
                    "could not restore exact access stand " + coordinates(standAt)
                            + " for the pinned Home " + kind.displayName() + ": "
                            + status.detail(),
                    ownedTarget);
        }
        return new Result(
                State.APPROACHING,
                (status.state() == BaritonePort.State.COMPLETE
                        ? "reached restored exact access stand; verifying pinned Home "
                        : "restoring and approaching exact access stand for pinned Home ")
                        + kind.displayName() + "; " + status.detail(),
                ownedTarget);
    }

    private static Point point(BlockPos position) {
        return new Point(position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos block(Point position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private Optional<Placement> findPlacement(Session session) {
        if (session.homePinned) {
            return findPinnedHomePlacement(session);
        }
        Set<BlockPos> rejected = session.rejected;
        BlockPos feet = client.player.getBlockPos();
        BlockPos center = feet;
        ArrayList<Placement> candidates = new ArrayList<>();
        for (int y = -2; y <= 2; y++) {
            for (int x = -PLACEMENT_RADIUS; x <= PLACEMENT_RADIUS; x++) {
                for (int z = -PLACEMENT_RADIUS; z <= PLACEMENT_RADIUS; z++) {
                    BlockPos placeAt = center.add(x, y, z);
                    if (!withinPinnedHomeRadius(session, placeAt)) continue;
                    if (!session.homePinned
                            && !ordinaryWorkstationAllowed(ProtectedAreaPolicy.Action.PLACE, placeAt)) continue;
                    if (session.homePinned && !HomeEconomyPolicy.coherentHomeAssetCell(
                            session.homeAnchor.getX(),
                            session.homeAnchor.getY(),
                            session.homeAnchor.getZ(),
                            placeAt.getX(), placeAt.getY(), placeAt.getZ())) continue;
                    if (rejected.contains(placeAt) || placeAt.equals(feet) || placeAt.equals(feet.up())) continue;
                    if (!client.world.isChunkLoaded(placeAt.getX() >> 4, placeAt.getZ() >> 4)) continue;
                    if (client.player.getBoundingBox().intersects(new Box(placeAt))) continue;
                    BlockPos support = placeAt.down();
                    BlockState destination = client.world.getBlockState(placeAt);
                    if (!(destination.isAir() || destination.isReplaceable())
                            || unsafeLocalState(destination)
                            || !hasSafeFloorSupport(support)
                            || session.kind == Kind.CHEST
                            && (!standaloneHomeChest(placeAt)
                            || !chestOpenable(placeAt))) continue;
                    Optional<BlockPos> stand = session.homePinned
                            && isStandable(session.homeAnchor)
                            && placementHitFrom(session.homeAnchor, support).isPresent()
                            ? Optional.of(session.homeAnchor)
                            : findPlacementStand(placeAt, support);
                    stand.filter(pos -> session.kind != Kind.WHITE_BED
                                    || bedFootprintAvailable(
                                    session, placeAt, placementFacing(pos, placeAt)))
                            .ifPresent(pos -> candidates.add(
                                    new Placement(placeAt, support, pos)));
                }
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(candidate ->
                candidate.standAt().getSquaredDistance(feet)
                        + candidate.placeAt().getSquaredDistance(feet) * 0.1));
    }

    /**
     * Sequential Home provisioning always chooses from a complete remaining
     * layout.  Earlier owned assets are solid live-world exclusions, while the
     * current choice reserves enough disjoint cells for every later role.  This
     * prevents table/furnace/chest ordering from consuming the only bed pair.
     */
    private Optional<Placement> findPinnedHomePlacement(Session session) {
        HomeEconomySession.AssetRole currentRole = homeRole(session.kind);
        List<HomeEconomySession.AssetRole> remaining = new ArrayList<>();
        boolean include = false;
        for (HomeEconomySession.AssetRole role : HomeEconomySession.AssetRole.values()) {
            if (role == currentRole) include = true;
            if (include) remaining.add(role);
        }
        HomeLayoutCandidates candidates = homeLayoutCandidates(
                session.homeAnchor, session.homeRadius, session.rejected);
        Optional<HomeAssetLayoutPolicy.Layout> selected = candidates.select(remaining);
        if (selected.isEmpty()) {
            session.homeLayoutFailure = candidates.detail(remaining);
            return Optional.empty();
        }
        HomeAssetLayoutPolicy.Option option = selected.orElseThrow().require(currentRole);
        Placement placement = candidates.placements().get(option);
        if (placement == null) {
            session.homeLayoutFailure = "selected " + currentRole.item()
                    + " layout has no verified interaction stand";
            return Optional.empty();
        }
        session.homeLayoutFailure = "";
        return Optional.of(placement);
    }

    private HomeLayoutCandidates homeLayoutCandidates(
            BlockPos anchor,
            int maximumRadius,
            Set<BlockPos> rejected) {
        if (maximumRadius < 1 || maximumRadius > 32) {
            throw new IllegalArgumentException("home asset radius must be 1..32");
        }
        if (client.player == null || client.world == null) {
            return HomeLayoutCandidates.unavailable(
                    anchor,
                    "Home layout cannot be inspected without a loaded world and Entity");
        }
        EnumMap<HomeEconomySession.AssetRole, List<HomeAssetLayoutPolicy.Option>> options =
                new EnumMap<>(HomeEconomySession.AssetRole.class);
        for (HomeEconomySession.AssetRole role : HomeEconomySession.AssetRole.values()) {
            options.put(role, new ArrayList<>());
        }
        LinkedHashMap<HomeAssetLayoutPolicy.Option, Placement> placements =
                new LinkedHashMap<>();
        ArrayList<BlockPos> cells = new ArrayList<>();
        int radius = Math.min(maximumRadius, PLACEMENT_RADIUS);
        List<Set<HomeAssetLayoutPolicy.Cell>> mobilityRoutes =
                homeMobilityRoutes(anchor, radius);
        if (mobilityRoutes.isEmpty()) {
            return HomeLayoutCandidates.unavailable(
                    anchor,
                    "no proved walkable route beyond the asset envelope");
        }
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos cell = anchor.add(x, 0, z);
                if (!cell.equals(anchor) && withinRadius(anchor, cell, maximumRadius)) {
                    cells.add(cell);
                }
            }
        }
        cells.sort(Comparator
                .comparingDouble((BlockPos cell) -> cell.getSquaredDistance(anchor))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));

        EnumMap<HomeEconomySession.AssetRole, LayoutBlocker> blockers =
                new EnumMap<>(HomeEconomySession.AssetRole.class);
        for (BlockPos placeAt : cells) {
            String baseFailure = homePlacementCellFailure(placeAt, rejected);
            if (!baseFailure.isBlank()) {
                for (HomeEconomySession.AssetRole role
                        : HomeEconomySession.AssetRole.values()) {
                    blockers.putIfAbsent(role, new LayoutBlocker(
                            role, placeAt, baseFailure));
                }
                continue;
            }
            BlockPos support = placeAt.down();
            List<BlockPos> stands = homePlacementStands(anchor, placeAt, support);
            if (stands.isEmpty()) {
                for (HomeEconomySession.AssetRole role
                        : HomeEconomySession.AssetRole.values()) {
                    blockers.putIfAbsent(role, new LayoutBlocker(
                            role, placeAt,
                            "no reachable stand has a verified support-face sightline"));
                }
                continue;
            }

            HomeAssetLayoutPolicy.Cell primary = layoutCell(placeAt);
            Placement ordinaryPlacement = new Placement(placeAt, support, stands.getFirst());
            for (HomeEconomySession.AssetRole role : List.of(
                    HomeEconomySession.AssetRole.CRAFTING_TABLE,
                    HomeEconomySession.AssetRole.FURNACE)) {
                HomeAssetLayoutPolicy.Option option =
                        HomeAssetLayoutPolicy.Option.single(role, primary);
                options.get(role).add(option);
                placements.put(option, ordinaryPlacement);
            }

            if (standaloneHomeChest(placeAt) && chestOpenable(placeAt)) {
                HomeAssetLayoutPolicy.Option chest = HomeAssetLayoutPolicy.Option.single(
                        HomeEconomySession.AssetRole.CHEST, primary);
                options.get(HomeEconomySession.AssetRole.CHEST).add(chest);
                placements.put(chest, ordinaryPlacement);
            } else {
                blockers.putIfAbsent(HomeEconomySession.AssetRole.CHEST,
                        new LayoutBlocker(
                                HomeEconomySession.AssetRole.CHEST, placeAt,
                                "a standalone chest would merge with a neighbor or its lid is blocked"));
            }

            boolean bedAdded = false;
            LinkedHashSet<HomeAssetLayoutPolicy.Option> uniqueBedOptions =
                    new LinkedHashSet<>();
            for (BlockPos stand : stands) {
                Direction facing = placementFacing(stand, placeAt);
                BlockPos head = placeAt.offset(facing);
                String bedFailure = homeBedFootprintFailure(
                        anchor, maximumRadius, placeAt, head);
                if (!bedFailure.isBlank()) {
                    blockers.putIfAbsent(HomeEconomySession.AssetRole.BED,
                            new LayoutBlocker(
                                    HomeEconomySession.AssetRole.BED, head, bedFailure));
                    continue;
                }
                HomeAssetLayoutPolicy.Option bed = HomeAssetLayoutPolicy.Option.bed(
                        primary, layoutCell(head));
                if (!uniqueBedOptions.add(bed)) continue;
                options.get(HomeEconomySession.AssetRole.BED).add(bed);
                placements.put(bed, new Placement(placeAt, support, stand));
                bedAdded = true;
            }
            if (!bedAdded) {
                blockers.putIfAbsent(HomeEconomySession.AssetRole.BED,
                        new LayoutBlocker(
                                HomeEconomySession.AssetRole.BED, placeAt,
                                "no interaction stand produces a clear supported two-cell bed footprint"));
            }
        }
        return new HomeLayoutCandidates(
                options, placements, blockers, mobilityRoutes, "");
    }

    /**
     * Finds deterministic same-level routes from Home to just beyond every
     * possible asset coordinate. A selected layout reserves one whole route,
     * so provisioning cannot turn a cave corridor into a staircase over its
     * own table/furnace and strand later acquisition.
     */
    private List<Set<HomeAssetLayoutPolicy.Cell>> homeMobilityRoutes(
            BlockPos anchor,
            int assetRadius) {
        if (!isStandable(anchor)) return List.of();
        int boundary = Math.addExact(assetRadius, 1);
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        LinkedHashMap<Long, BlockPos> visited = new LinkedHashMap<>();
        LinkedHashMap<Long, Long> predecessor = new LinkedHashMap<>();
        ArrayList<BlockPos> exits = new ArrayList<>();
        pending.add(anchor.toImmutable());
        visited.put(anchor.asLong(), anchor.toImmutable());
        List<Direction> directions = List.of(
                Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST);
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            for (Direction direction : directions) {
                BlockPos next = current.offset(direction);
                int dx = Math.abs(next.getX() - anchor.getX());
                int dz = Math.abs(next.getZ() - anchor.getZ());
                if (dx > boundary || dz > boundary
                        || visited.containsKey(next.asLong())
                        || !isHomeMobilityTraversable(next)) continue;
                BlockPos immutable = next.toImmutable();
                visited.put(immutable.asLong(), immutable);
                predecessor.put(immutable.asLong(), current.asLong());
                pending.addLast(immutable);
                if (dx == boundary || dz == boundary) exits.add(immutable);
            }
        }
        ArrayList<Set<HomeAssetLayoutPolicy.Cell>> routes = new ArrayList<>();
        for (BlockPos exit : exits) {
            ArrayList<BlockPos> reversed = new ArrayList<>();
            BlockPos cursor = exit;
            while (!cursor.equals(anchor)) {
                reversed.add(cursor);
                Long previous = predecessor.get(cursor.asLong());
                if (previous == null) {
                    reversed.clear();
                    break;
                }
                cursor = visited.get(previous);
            }
            if (reversed.isEmpty()) continue;
            LinkedHashSet<HomeAssetLayoutPolicy.Cell> route =
                    new LinkedHashSet<>();
            for (int index = reversed.size() - 1; index >= 0; index--) {
                route.add(layoutCell(reversed.get(index)));
            }
            routes.add(Set.copyOf(route));
        }
        return List.copyOf(routes);
    }

    private String homePlacementCellFailure(BlockPos placeAt, Set<BlockPos> rejected) {
        if (rejected != null && rejected.contains(placeAt)) {
            return "the placement was already rejected by live server/world evidence";
        }
        if (!isLoaded(placeAt)) return "the coordinate is not loaded";
        String protectedFailure = protectedHomeCellFailure(placeAt);
        if (!protectedFailure.isBlank()) return protectedFailure;
        if (client.player.getBoundingBox().intersects(new Box(placeAt))) {
            return "Entity's body currently occupies the asset cell";
        }
        BlockState destination = client.world.getBlockState(placeAt);
        if (!(destination.isAir() || destination.isReplaceable())) {
            return "the destination contains non-replaceable "
                    + destination.getBlock().getName().getString();
        }
        if (unsafeLocalState(destination)) {
            return "the destination contains a fluid or local hazard";
        }
        if (!hasSafeFloorSupport(placeAt.down())) {
            return "the block below is not stable full-top support";
        }
        return "";
    }

    private String homeBedFootprintFailure(
            BlockPos anchor,
            int maximumRadius,
            BlockPos foot,
            BlockPos head) {
        if (head.equals(anchor)) return "the bed head would occupy Entity's Home anchor";
        if (!withinRadius(anchor, head, maximumRadius)) {
            return "the two-cell bed footprint would leave the bounded Home envelope";
        }
        if (!isLoaded(head)) return "the bed head coordinate is not loaded";
        String protectedFailure = protectedHomeCellFailure(head);
        if (!protectedFailure.isBlank()) return protectedFailure;
        if (client.player.getBoundingBox().intersects(new Box(head))) {
            return "Entity's body currently occupies the bed head cell";
        }
        BlockState destination = client.world.getBlockState(head);
        if (!(destination.isAir() || destination.isReplaceable())) {
            return "the bed head contains non-replaceable "
                    + destination.getBlock().getName().getString();
        }
        if (unsafeLocalState(destination)) {
            return "the bed head contains a fluid or local hazard";
        }
        if (!hasSafeFloorSupport(foot.down())) {
            return "the bed foot lacks stable full-top support";
        }
        if (!hasSafeFloorSupport(head.down())) {
            return "the bed head lacks stable full-top support";
        }
        return "";
    }

    private String protectedHomeCellFailure(BlockPos position) {
        if (protectedAreas == null) {
            return "protected-property policy is unavailable";
        }
        ProtectedAreaPolicy.Decision decision = protectedAreas.decide(
                ProtectedAreaPolicy.Action.PLACE,
                currentDimension(), position.getX(), position.getY(), position.getZ(), true);
        return decision.allowed() ? ""
                : "Entity Home assets must remain outside player-protected property: "
                        + decision.detail();
    }

    private List<BlockPos> homePlacementStands(
            BlockPos anchor,
            BlockPos placeAt,
            BlockPos support) {
        LinkedHashMap<Long, BlockPos> stands = new LinkedHashMap<>();
        if (isStandable(anchor) && placementHitFrom(anchor, support).isPresent()) {
            stands.put(anchor.asLong(), anchor.toImmutable());
        }
        for (BlockPos stand : findPlacementStands(placeAt, support)) {
            stands.putIfAbsent(stand.asLong(), stand);
        }
        return List.copyOf(stands.values());
    }

    private static HomeAssetLayoutPolicy.Cell layoutCell(BlockPos position) {
        return new HomeAssetLayoutPolicy.Cell(
                position.getX(), position.getY(), position.getZ());
    }

    private static HomeEconomySession.AssetRole homeRole(Kind kind) {
        return switch (kind) {
            case CRAFTING_TABLE -> HomeEconomySession.AssetRole.CRAFTING_TABLE;
            case FURNACE -> HomeEconomySession.AssetRole.FURNACE;
            case CHEST -> HomeEconomySession.AssetRole.CHEST;
            case WHITE_BED -> HomeEconomySession.AssetRole.BED;
        };
    }

    private boolean homeHazardClear(BlockPos anchor) {
        for (int y = -1; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos checked = anchor.add(x, y, z);
                    if (!isLoaded(checked)
                            || unsafeLocalState(client.world.getBlockState(checked))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean standaloneHomeChest(BlockPos position) {
        return HomeChestIsolationPolicy.standalone(
                mergeCompatibleChest(position.north()),
                mergeCompatibleChest(position.south()),
                mergeCompatibleChest(position.east()),
                mergeCompatibleChest(position.west()));
    }

    /** Mirrors the exact vanilla server gate used by ChestBlock before opening a screen. */
    private boolean chestOpenable(BlockPos position) {
        return client.world != null
                && isLoaded(position)
                && !ChestBlock.isChestBlocked(client.world, position);
    }

    private int renewableWaterNeighborMask(BlockPos source) {
        if (!waterSource(source)) return 0;
        int mask = 0;
        if (waterSource(source.north())) mask |= 1;
        if (waterSource(source.south())) mask |= 2;
        if (waterSource(source.east())) mask |= 4;
        if (waterSource(source.west())) mask |= 8;
        return mask;
    }

    private boolean waterSource(BlockPos position) {
        return isLoaded(position)
                && client.world.getFluidState(position).isIn(FluidTags.WATER)
                && client.world.getFluidState(position).isStill();
    }

    private boolean renewableWaterSupport(BlockPos source) {
        return waterSource(source.down()) || hasSafeFloorSupport(source.down());
    }

    private boolean renewableWaterGeometrySafe(BlockPos source, int sourceNeighborMask) {
        return HomeWaterFillPolicy.regenerationSafeGeometry(
                sourceNeighborMask,
                renewableWaterContainmentMask(source, sourceNeighborMask),
                renewableWaterSupport(source));
    }

    private int renewableWaterContainmentMask(BlockPos source, int sourceNeighborMask) {
        int mask = 0;
        if ((sourceNeighborMask & 1) == 0
                && laterallyContainsWater(source.north(), Direction.SOUTH)) mask |= 1;
        if ((sourceNeighborMask & 2) == 0
                && laterallyContainsWater(source.south(), Direction.NORTH)) mask |= 2;
        if ((sourceNeighborMask & 4) == 0
                && laterallyContainsWater(source.east(), Direction.WEST)) mask |= 4;
        if ((sourceNeighborMask & 8) == 0
                && laterallyContainsWater(source.west(), Direction.EAST)) mask |= 8;
        return mask;
    }

    private boolean laterallyContainsWater(BlockPos position, Direction faceTowardSource) {
        return isLoaded(position)
                && client.world.getFluidState(position).isEmpty()
                && client.world.getBlockState(position).isSideSolidFullSquare(
                client.world, position, faceTowardSource);
    }

    private Optional<BlockPos> findWaterInteractionStand(BlockPos source) {
        return findWaterInteractionStand(source, null);
    }

    private Optional<BlockPos> findWaterInteractionStand(BlockPos source, BlockPos rejectedStand) {
        ArrayList<BlockPos> stands = new ArrayList<>();
        for (int y = READY_STAND_MIN_Y; y <= READY_STAND_MAX_Y; y++) {
            for (int x = -STAND_SEARCH_RADIUS; x <= STAND_SEARCH_RADIUS; x++) {
                for (int z = -STAND_SEARCH_RADIUS; z <= STAND_SEARCH_RADIUS; z++) {
                    BlockPos stand = source.add(x, y, z);
                    if (stand.equals(rejectedStand)) continue;
                    if (rejectedStand != null && stand.getSquaredDistance(source)
                            >= rejectedStand.getSquaredDistance(source)) continue;
                    if (!isStandable(stand)) continue;
                    Vec3d eye = Vec3d.ofBottomCenter(stand).add(0, 1.62, 0);
                    if (eye.squaredDistanceTo(Vec3d.ofCenter(source))
                            > INTERACT_DISTANCE_SQUARED) continue;
                    if (BlockInteractionRaycaster.fluidSourceAim(
                            client, eye, source).isPresent()) stands.add(stand);
                }
            }
        }
        BlockPos origin = client.player.getBlockPos();
        return stands.stream()
                .sorted(Comparator.comparingDouble(stand -> stand.getSquaredDistance(origin)))
                .filter(stand -> robustWaterAimFromStand(stand, source))
                .findFirst();
    }

    private boolean robustWaterAimFromStand(BlockPos stand, BlockPos source) {
        Vec3d centerEye = Vec3d.ofBottomCenter(stand).add(0, 1.62, 0);
        for (double x : WATER_STAND_STOP_OFFSETS) {
            for (double z : WATER_STAND_STOP_OFFSETS) {
                if (BlockInteractionRaycaster.fluidSourceAim(
                        client, centerEye.add(x, 0, z), source).isEmpty()) return false;
            }
        }
        return true;
    }

    private boolean mergeCompatibleChest(BlockPos position) {
        if (!isLoaded(position)) return true;
        Block block = client.world.getBlockState(position).getBlock();
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }

    private static boolean withinRadius(BlockPos center, BlockPos point, int radius) {
        long dx = (long) point.getX() - center.getX();
        long dy = (long) point.getY() - center.getY();
        long dz = (long) point.getZ() - center.getZ();
        long bounded = radius;
        return dx * dx + dy * dy + dz * dz <= bounded * bounded;
    }

    private Optional<BlockPos> findPlacementStand(BlockPos placeAt, BlockPos support) {
        return findPlacementStands(placeAt, support).stream().findFirst();
    }

    private List<BlockPos> findPlacementStands(BlockPos placeAt, BlockPos support) {
        ArrayList<BlockPos> stands = new ArrayList<>();
        for (int y = -2; y <= 2; y++) {
            for (int x = -STAND_SEARCH_RADIUS; x <= STAND_SEARCH_RADIUS; x++) {
                for (int z = -STAND_SEARCH_RADIUS; z <= STAND_SEARCH_RADIUS; z++) {
                    BlockPos stand = placeAt.add(x, y, z);
                    if (stand.equals(placeAt) || stand.equals(placeAt.up()) || !isStandable(stand)) continue;
                    if (placementHitFrom(stand, support).isPresent()) stands.add(stand);
                }
            }
        }
        BlockPos origin = client.player.getBlockPos();
        stands.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.getSquaredDistance(origin))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(stands);
    }

    private boolean isStandable(BlockPos feet) {
        if (!client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        BlockState foot = client.world.getBlockState(feet);
        BlockState head = client.world.getBlockState(feet.up());
        BlockPos floorPos = feet.down();
        BlockState overhead = client.world.getBlockState(feet.up(2));
        return foot.getCollisionShape(client.world, feet).isEmpty()
                && head.getCollisionShape(client.world, feet.up()).isEmpty()
                && !unsafeLocalState(foot)
                && !unsafeLocalState(head)
                && hasSafeFloorSupport(floorPos)
                && !(overhead.getBlock() instanceof FallingBlock);
    }

    /**
     * Home selection must not call a normal wooden doorway a sealed wall. The
     * cell still needs loaded, dry, supported geometry; this merely credits the
     * same player-operable portal that the route actuator can open and restore.
     * It is used only for the reserved Home exit route, never as an interaction
     * stand for placing or using an asset.
     */
    private boolean isHomeMobilityTraversable(BlockPos feet) {
        if (isStandable(feet)) return true;
        if (!client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        BlockState foot = client.world.getBlockState(feet);
        BlockState head = client.world.getBlockState(feet.up());
        BlockState overhead = client.world.getBlockState(feet.up(2));
        boolean woodenDoor = foot.getBlock() instanceof DoorBlock
                && foot.isIn(BlockTags.WOODEN_DOORS)
                && foot.contains(DoorBlock.HALF)
                && foot.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && head.isOf(foot.getBlock())
                && head.contains(DoorBlock.HALF)
                && head.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
        boolean fenceGate = foot.getBlock() instanceof FenceGateBlock
                && foot.isIn(BlockTags.FENCE_GATES)
                && head.getCollisionShape(client.world, feet.up()).isEmpty();
        return (woodenDoor || fenceGate)
                && !unsafeLocalState(foot)
                && !unsafeLocalState(head)
                && hasSafeFloorSupport(feet.down())
                && !(overhead.getBlock() instanceof FallingBlock);
    }

    private boolean hasSafeFloorSupport(BlockPos floorPos) {
        BlockState floor = client.world.getBlockState(floorPos);
        return !unsafeLocalState(floor)
                && MinecraftWorkspaceProbe.hasStableFullTopSupport(client, floorPos);
    }

    /**
     * An explicit Home anchor is Entity's exact feet, not an asset cell.  A
     * stationary grounded body is stronger live evidence for that exact cell
     * than the conservative full-square floor classifier (paths, snow, and
     * other ordinary walkable surfaces need not expose a full solid face).
     * Asset cells still require stable full-top support independently.
     */
    private boolean homeAnchorSupported(BlockPos anchor) {
        if (client.player == null || client.world == null) return false;
        return hasSafeFloorSupport(anchor.down())
                || client.player.isOnGround()
                && client.player.getBlockPos().equals(anchor);
    }

    private boolean unsafeLocalState(BlockState state) {
        return LOCAL_HAZARDS.contains(state.getBlock())
                || !state.getFluidState().isEmpty()
                || state.getFluidState().isIn(FluidTags.LAVA);
    }

    private boolean reachedVerifiedStand(Session session, BlockPos stand) {
        return HomeAssetLayoutPolicy.reachedVerifiedStand(
                session.homePinned,
                layoutCell(client.player.getBlockPos()),
                layoutCell(stand));
    }

    private Optional<BlockHitResult> interactionHitFrom(BlockPos stand, BlockPos target) {
        Vec3d start = Vec3d.ofBottomCenter(stand).add(0, 1.62, 0);
        if (start.squaredDistanceTo(Vec3d.ofCenter(target)) > INTERACT_DISTANCE_SQUARED) {
            return Optional.empty();
        }
        return BlockInteractionRaycaster.visible(client, start, target);
    }

    private Optional<BlockHitResult> placementHitFrom(BlockPos stand, BlockPos support) {
        Vec3d start = Vec3d.ofBottomCenter(stand).add(0, 1.62, 0);
        if (start.squaredDistanceTo(Vec3d.ofCenter(support)) > INTERACT_DISTANCE_SQUARED) {
            return Optional.empty();
        }
        return BlockInteractionRaycaster.topFace(client, start, support);
    }

    private Optional<BlockHitResult> placementHit(Placement placement) {
        return BlockInteractionRaycaster.topFaceNow(client, placement.support());
    }

    private Optional<BlockHitResult> visibleHit(BlockPos target) {
        return BlockInteractionRaycaster.visibleNow(client, target);
    }

    /** Rotate for one full client tick before sending an interaction packet. */
    private boolean aimReady(Session session, String interaction, Vec3d target) {
        Vec3d delta = target.subtract(client.player.getEyePos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        client.player.setYaw(MathHelper.wrapDegrees(yaw));
        client.player.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
        if (!session.aimedInteraction.equals(interaction)) {
            session.aimedInteraction = interaction;
            session.aimedAtPlayerAge = client.player.age;
            return false;
        }
        return client.player.age > session.aimedAtPlayerAge;
    }

    private Result localFailure(Session session, String detail, BlockPos position) {
        atomicBreak.cancel();
        session.failures++;
        session.clearAim();
        if (session.failures >= MAX_LOCAL_FAILURES) {
            return new Result(State.BLOCKED,
                    detail + "; exhausted " + session.failures
                            + " distinct local geometry/interaction recoveries",
                    position);
        }
        return new Result(State.RETRY,
                detail + "; selecting a different verified strategy (local attempt "
                        + session.failures + '/' + MAX_LOCAL_FAILURES + ')',
                position);
    }

    /**
     * A fully searched candidate set is terminal. Re-observing the same world
     * on later ticks must not manufacture more failed strategies.
     */
    private Result reclaimCandidatesExhausted(
            Session session,
            String detail,
            BlockPos position) {
        atomicBreak.cancel();
        session.clearAim();
        WorkstationReclaimRecoveryPolicy.FailureDecision decision =
                WorkstationReclaimRecoveryPolicy.recordCandidateFailure(
                        session.failures, MAX_LOCAL_FAILURES, false);
        session.failures = decision.completedFailures();
        String history = session.failures == 0
                ? "; exhaustive local search found no distinct reclaim strategy"
                : "; no distinct reclaim strategy remains after " + session.failures
                        + " failed attempt" + (session.failures == 1 ? "" : "s");
        return new Result(State.BLOCKED,
                detail + history,
                position);
    }

    /**
     * Advances reclaim geometry inside the workstation's own budget. The
     * failed stand has already been blacklisted, so exposing RETRY here would
     * charge the same recovery again in the outer action supervisor.
     */
    private Result localReclaimFailure(
            Session session,
            boolean newlyRejectedStrategy,
            String detail,
            BlockPos position) {
        atomicBreak.cancel();
        session.clearAim();
        WorkstationReclaimRecoveryPolicy.FailureDecision decision =
                WorkstationReclaimRecoveryPolicy.recordCandidateFailure(
                        session.failures, MAX_LOCAL_FAILURES, newlyRejectedStrategy);
        session.failures = decision.completedFailures();
        if (decision.disposition()
                != WorkstationReclaimRecoveryPolicy.Disposition.CONTINUE_RECLAIMING) {
            String exhaustion = decision.disposition()
                    == WorkstationReclaimRecoveryPolicy.Disposition.CANDIDATES_EXHAUSTED
                    ? "; no distinct reclaim strategy remains after " + session.failures
                            + " failed attempt" + (session.failures == 1 ? "" : "s")
                    : "; exhausted " + session.failures + " distinct local reclaim strategies";
            return new Result(State.BLOCKED,
                    detail + exhaustion,
                    position);
        }
        return new Result(State.RECLAIMING,
                detail + "; advancing to a different verified reclaim strategy (local attempt "
                        + session.failures + '/' + MAX_LOCAL_FAILURES + ')',
                position);
    }

    /**
     * A failed reclaim interaction must not advertise a different strategy and
     * then retry the identical stand. Only the exact verified/planned stand is
     * rejected; a later body position is not evidence about that candidate.
     */
    private Result reclaimFailure(Session session, String detail, BlockPos position) {
        boolean newlyRejectedStrategy = session.standAt != null
                && session.failedStands.add(session.standAt.toImmutable());
        session.standAt = null;
        session.reclaimStandReselectionRequired = true;
        return localReclaimFailure(session, newlyRejectedStrategy, detail, position);
    }

    private void hydrateFieldKit(Session session) {
        if (!session.ownedAssetId.isBlank()) return;
        if (session.homePinned) {
            FieldKitLedger.Asset exact = fieldKit.snapshot().assets().get(session.pinnedAssetId);
            if (exact != null) hydrateAsset(session, exact);
            return;
        }
        String current = currentDimension();
        List<FieldKitLedger.Asset> assets = fieldKit.assets(fieldKitKind(session.kind));
        Optional<FieldKitLedger.Asset> selectedAsset = FieldKitAssetPolicy.selectOrdinary(
                assets, current, false);
        if (selectedAsset.isEmpty()) return;
        hydrateAsset(session, selectedAsset.orElseThrow());
    }

    private void hydrateAsset(Session session, FieldKitLedger.Asset selected) {
        session.ownedAssetId = selected.id();
        session.placementOwnedBySession = false;
        session.reclaimStandReselectionRequired = false;
        session.reclaimRecoveryRequired =
                selected.state() == FieldKitLedger.AssetState.RECLAIM_PENDING;
        FieldKitLedger.Position position = selected.lastKnownPosition();
        if (position != null && selected.state() != FieldKitLedger.AssetState.LOST) {
            session.ownedPlacement = new BlockPos(position.x(), position.y(), position.z());
            session.ownedDimension = position.dimension();
            if (position.dimension().equals(currentDimension())) {
                session.target = session.ownedPlacement;
            }
        }
    }

    /**
     * A workstation already in inventory is usable now. Prefer an existing
     * durable carried asset, or register this separately observed item, while
     * leaving any remote placed/reclaim-pending asset in the ledger for later
     * cleanup. This prevents a stale remote record from hijacking local work.
     */
    private boolean preferCarriedFieldKit(Session session, long nowMillis) {
        if (session.placementOwnedBySession || !inventory.has(session.kind.itemId(), 1)) return true;
        if (session.homePinned && session.ownedPlacement != null) return true;
        List<FieldKitLedger.Asset> assets = fieldKit.assets(fieldKitKind(session.kind));
        Optional<FieldKitLedger.Asset> carried = session.homePinned
                ? Optional.ofNullable(fieldKit.snapshot().assets().get(session.pinnedAssetId))
                        .filter(asset -> asset.state() == FieldKitLedger.AssetState.CARRIED)
                : FieldKitAssetPolicy.selectOrdinary(
                        assets, currentDimension(), true)
                .filter(asset -> asset.state() == FieldKitLedger.AssetState.CARRIED);
        FieldKitLedger.Asset selected;
        try {
            selected = carried.isPresent()
                    ? carried.orElseThrow()
                    : session.homePinned
                    ? fieldKit.registerCarried(
                            session.pinnedAssetId,
                            fieldKitKind(session.kind),
                            FieldKitLedger.Verification.CLIENT_OBSERVED,
                            Math.max(0L, nowMillis))
                    : fieldKit.registerCarried(
                            fieldKitKind(session.kind),
                            FieldKitLedger.Verification.CLIENT_OBSERVED,
                            Math.max(0L, nowMillis));
        } catch (IOException | RuntimeException error) {
            session.persistenceFailure = "could not durably register carried "
                    + session.kind.displayName() + ": " + error.getMessage();
            return false;
        }
        if (selected.id().equals(session.ownedAssetId)
                && session.ownedPlacement == null
                && !session.reclaimRecoveryRequired) return true;
        atomicBreak.cancel();
        workspace.clear(session.operationId);
        session.ownedAssetId = selected.id();
        session.ownedPlacement = null;
        session.ownedDimension = "";
        session.target = null;
        session.standAt = null;
        session.placement = null;
        session.placementAttemptAt = 0L;
        session.placementInventoryBaseline = -1;
        session.placementEvidenceAt = 0L;
        clearPinnedScreenAcknowledgement(session);
        session.nextInteractionAt = 0L;
        session.reclaimInventoryBaseline = -1;
        session.reclaimBrokenAt = 0L;
        session.reclaimRecoveryRequired = false;
        session.pendingCarriedProof = false;
        session.workspaceActive = false;
        session.placementFromWorkspace = false;
        session.placementOwnedBySession = false;
        session.reclaimStandReselectionRequired = false;
        session.rejected.clear();
        session.failedStands.clear();
        session.failures = 0;
        session.persistenceFailure = "";
        session.sleepCursor = HomeSleepPolicy.Cursor.empty();
        session.sleepInteractionAt = 0L;
        session.sleepFailures = 0;
        session.clearAim();
        return true;
    }

    private void retryDurableCommit(Session session, long nowMillis) {
        if (session.persistenceFailure.isBlank()
                || session.ownedPlacement == null
                || client.world == null
                || !ownedInCurrentDimension(session)
                || !isLoaded(session.ownedPlacement)
                || !validOwnedBlock(session.kind, session.ownedPlacement)) return;
        persistPlaced(session, session.ownedPlacement, nowMillis);
    }

    private boolean persistPlaced(Session session, BlockPos position, long nowMillis) {
        try {
            String id = ensureFieldKitAsset(session, nowMillis);
            fieldKit.markPlaced(
                    id,
                    new FieldKitLedger.Position(
                            currentDimension(), position.getX(), position.getY(), position.getZ()),
                    FieldKitLedger.Verification.CLIENT_OBSERVED,
                    Math.max(0L, nowMillis));
            session.persistenceFailure = "";
            return true;
        } catch (IOException | RuntimeException error) {
            session.persistenceFailure = "could not durably commit Entity's placed "
                    + session.kind.displayName() + ": " + error.getMessage();
            return false;
        }
    }

    private boolean persistReclaimPending(Session session, long nowMillis) {
        if (session.ownedAssetId.isBlank()) return true;
        try {
            FieldKitLedger.Asset asset = fieldKit.asset(session.ownedAssetId);
            if (asset.state() != FieldKitLedger.AssetState.RECLAIM_PENDING) {
                fieldKit.markReclaimPending(session.ownedAssetId, Math.max(0L, nowMillis));
            }
            session.reclaimRecoveryRequired = true;
            session.persistenceFailure = "";
            return true;
        } catch (IOException | RuntimeException error) {
            session.persistenceFailure = "could not commit " + session.kind.displayName()
                    + " reclaim ownership: " + error.getMessage();
            return false;
        }
    }

    private boolean persistCarried(Session session, long nowMillis) {
        if (session.ownedAssetId.isBlank()) return true;
        try {
            fieldKit.markCarried(
                    session.ownedAssetId,
                    FieldKitLedger.Verification.CLIENT_OBSERVED,
                    Math.max(0L, nowMillis));
            session.pendingCarriedProof = false;
            session.reclaimRecoveryRequired = false;
            session.persistenceFailure = "";
            return true;
        } catch (IOException | RuntimeException error) {
            session.persistenceFailure = "reclaimed " + session.kind.displayName()
                    + " was observed but its durable ledger could not commit: " + error.getMessage();
            return false;
        }
    }

    private void persistLost(Session session, String reason, long nowMillis) {
        if (session.ownedAssetId.isBlank()) return;
        try {
            FieldKitLedger.Asset asset = fieldKit.asset(session.ownedAssetId);
            if (asset.lastKnownPosition() != null) {
                fieldKit.markLost(
                        session.ownedAssetId,
                        Objects.requireNonNullElse(reason, "field-kit asset was not recoverable"),
                        Math.max(0L, nowMillis));
            }
            session.reclaimRecoveryRequired = false;
        } catch (IOException | RuntimeException error) {
            session.persistenceFailure = "could not record lost " + session.kind.displayName()
                    + " in the durable ledger: " + error.getMessage();
        }
    }

    private String ensureFieldKitAsset(Session session, long nowMillis) throws IOException {
        if (!session.ownedAssetId.isBlank()) return session.ownedAssetId;
        if (session.homePinned) {
            FieldKitLedger.Asset exact = fieldKit.snapshot().assets().get(session.pinnedAssetId);
            if (exact == null) {
                exact = fieldKit.registerCarried(
                        session.pinnedAssetId,
                        fieldKitKind(session.kind),
                        FieldKitLedger.Verification.CLIENT_OBSERVED,
                        Math.max(0L, nowMillis));
            }
            session.ownedAssetId = exact.id();
            return session.ownedAssetId;
        }
        List<FieldKitLedger.Asset> existing = fieldKit.assets(fieldKitKind(session.kind));
        Optional<FieldKitLedger.Asset> ordinary = FieldKitAssetPolicy.selectOrdinary(
                existing, currentDimension(), inventory.has(session.kind.itemId(), 1));
        if (ordinary.isPresent()) {
            session.ownedAssetId = ordinary.orElseThrow().id();
            return session.ownedAssetId;
        }
        String id = "field-kit:" + session.kind.name().toLowerCase() + ":primary";
        fieldKit.registerCarried(
                id,
                fieldKitKind(session.kind),
                FieldKitLedger.Verification.CLIENT_OBSERVED,
                Math.max(0L, nowMillis));
        session.ownedAssetId = id;
        return id;
    }

    private static FieldKitLedger.AssetKind fieldKitKind(Kind kind) {
        return switch (kind) {
            case CRAFTING_TABLE -> FieldKitLedger.AssetKind.CRAFTING_TABLE;
            case FURNACE -> FieldKitLedger.AssetKind.FURNACE;
            case CHEST -> FieldKitLedger.AssetKind.CHEST;
            case WHITE_BED -> FieldKitLedger.AssetKind.WHITE_BED;
        };
    }

    /** Compatibility-only store for isolated mapped tests; live runtime injects disk persistence. */
    private static FieldKitLedger transientFieldKit() {
        try {
            return new FieldKitLedger(new FieldKitStore() {
                private FieldKitLedger.Snapshot snapshot = FieldKitLedger.Snapshot.empty();

                @Override
                public FieldKitLedger.Snapshot load() {
                    return snapshot;
                }

                @Override
                public void save(FieldKitLedger.Snapshot next) {
                    snapshot = next;
                }
            });
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory field-kit store failed", impossible);
        }
    }

    private boolean screenMatches(Kind kind) {
        if (client.player == null) return false;
        return switch (kind) {
            case CRAFTING_TABLE -> client.player.currentScreenHandler instanceof CraftingScreenHandler;
            case FURNACE -> client.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler;
            case CHEST -> client.player.currentScreenHandler instanceof GenericContainerScreenHandler;
            case WHITE_BED -> false;
        };
    }

    /**
     * Binds a pinned screen to the exact accepted block interaction that
     * opened it. Handler type plus world-block truth is insufficient because
     * every ordinary chest uses the same GenericContainer handler class.
     */
    private boolean verifiedOpenScreen(Kind kind, Session session, long nowMillis) {
        boolean kindMatches = screenMatches(kind);
        if (!session.homePinned) return kindMatches;
        boolean targetVerified = pinnedTargetVerified(session);
        if (kind == Kind.CHEST) {
            kindMatches &= chestScreenMatches(session, session.pinnedOpenSyncId >= 0
                    ? session.pinnedChestOpenIdentity : session.pinnedChestInteractionIdentity);
        }
        int currentSyncId = client.player == null
                ? -1 : client.player.currentScreenHandler.syncId;
        boolean withinWindow = session.pinnedInteractionPending
                && session.openAttemptAt >= 0L
                && nowMillis >= session.openAttemptAt
                && nowMillis - session.openAttemptAt < SCREEN_CONFIRM_MILLIS;
        HomePinnedScreenPolicy.Verdict verdict = HomePinnedScreenPolicy.evaluate(
                targetVerified,
                kindMatches,
                session.pinnedOpenSyncId,
                currentSyncId,
                session.pinnedInteractionPending,
                session.pinnedInteractionSourceSyncId,
                withinWindow);
        if (verdict == HomePinnedScreenPolicy.Verdict.CAPTURE_INTERACTION_RESULT) {
            session.pinnedChestOpenIdentity = session.pinnedChestInteractionIdentity;
            session.pinnedOpenSyncId = currentSyncId;
            session.pinnedInteractionPending = false;
            session.pinnedInteractionSourceSyncId = -1;
            session.openAttemptAt = 0L;
            return true;
        }
        if (verdict == HomePinnedScreenPolicy.Verdict.ACCEPT_BOUND) return true;
        if (session.pinnedOpenSyncId >= 0
                && (!targetVerified || !kindMatches
                || currentSyncId != session.pinnedOpenSyncId)) {
            session.pinnedOpenSyncId = -1;
        }
        return false;
    }

    private static void clearPinnedInteractionAttempt(Session session) {
        session.pinnedInteractionPending = false;
        session.pinnedInteractionSourceSyncId = -1;
        session.pinnedChestInteractionIdentity = null;
    }

    private static void clearPinnedScreenAcknowledgement(Session session) {
        session.pinnedOpenSyncId = -1;
        session.pinnedChestOpenIdentity = null;
        clearPinnedInteractionAttempt(session);
        session.openAttemptAt = 0L;
    }

    private void resetTransientGeometryOnDimensionChange(Session session) {
        String current = currentDimension();
        if (current.isBlank()) return;
        if (!session.contextDimension.isBlank() && !session.contextDimension.equals(current)) {
            session.target = null;
            session.standAt = null;
            session.placement = null;
            session.placementAttemptAt = 0L;
            session.placementInventoryBaseline = -1;
            session.placementEvidenceAt = 0L;
            clearPinnedScreenAcknowledgement(session);
            session.nextInteractionAt = 0L;
            session.rejected.clear();
            session.failedStands.clear();
            session.reclaimStandReselectionRequired = false;
            session.workspaceActive = false;
            session.placementFromWorkspace = false;
            session.clearAim();
            workspace.clear(session.operationId);
        }
        session.contextDimension = current;
        if (session.ownedPlacement != null && ownedInCurrentDimension(session)) {
            session.target = session.ownedPlacement;
        }
    }

    private boolean ownedInCurrentDimension(Session session) {
        String current = currentDimension();
        return session.ownedPlacement != null
                && !current.isBlank()
                && !session.ownedDimension.isBlank()
                && session.ownedDimension.equals(current);
    }

    private boolean isLoaded(BlockPos position) {
        return position != null && client.world != null
                && client.world.getChunkManager().isChunkLoaded(position.getX() >> 4, position.getZ() >> 4);
    }

    private boolean validOwnedBlock(Kind kind, BlockPos position) {
        if (kind == null || position == null || client.world == null || !isLoaded(position)) {
            return false;
        }
        return kind == Kind.WHITE_BED
                ? bedStructureVerified(position)
                : client.world.getBlockState(position).isOf(kind.block());
    }

    private boolean bedStructureVerified(BlockPos foot) {
        if (client.world == null || !isLoaded(foot)) return false;
        BlockState footState = client.world.getBlockState(foot);
        if (!(footState.getBlock() instanceof BedBlock)
                || footState.get(BedBlock.PART) != BedPart.FOOT) return false;
        Direction facing = footState.get(BedBlock.FACING);
        BlockPos head = foot.offset(facing);
        if (!isLoaded(head)) return false;
        BlockState headState = client.world.getBlockState(head);
        return headState.isOf(footState.getBlock())
                && headState.get(BedBlock.PART) == BedPart.HEAD
                && headState.get(BedBlock.FACING) == facing;
    }

    private boolean bedFootprintAvailable(
            Session session,
            BlockPos foot,
            Direction facing) {
        if (facing == null || facing.getAxis().isVertical()) return false;
        BlockPos head = foot.offset(facing);
        BlockState footDestination = client.world.getBlockState(foot);
        if (!withinPinnedHomeRadius(session, head)
                || session.homePinned && !HomeEconomyPolicy.coherentHomeAssetCell(
                session.homeAnchor.getX(), session.homeAnchor.getY(), session.homeAnchor.getZ(),
                head.getX(), head.getY(), head.getZ())
                || head.equals(session.homeAnchor)
                || !isLoaded(head)
                || client.player.getBoundingBox().intersects(new Box(head))) return false;
        BlockState destination = client.world.getBlockState(head);
        return (footDestination.isAir() || footDestination.isReplaceable())
                && !unsafeLocalState(footDestination)
                && hasSafeFloorSupport(foot.down())
                && (destination.isAir() || destination.isReplaceable())
                && !unsafeLocalState(destination)
                && hasSafeFloorSupport(head.down());
    }

    private static Direction placementFacing(BlockPos stand, BlockPos placement) {
        int dx = placement.getX() - stand.getX();
        int dz = placement.getZ() - stand.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean withinPinnedHomeRadius(Session session, BlockPos position) {
        if (!session.homePinned) return true;
        if (position == null || session.homeAnchor == null
                || !session.homeDimension.equals(currentDimension())) return false;
        long dx = (long) position.getX() - session.homeAnchor.getX();
        long dy = (long) position.getY() - session.homeAnchor.getY();
        long dz = (long) position.getZ() - session.homeAnchor.getZ();
        long radius = session.homeRadius;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private boolean pinnedTargetVerified(Session session) {
        if (!session.homePinned || session.ownedPlacement == null
                || !session.ownedAssetId.equals(session.pinnedAssetId)
                || !withinPinnedHomeRadius(session, session.ownedPlacement)
                || !isLoaded(session.ownedPlacement)
                || !validOwnedBlock(session.kind, session.ownedPlacement)
                || session.kind == Kind.CHEST && observePinnedChestGeometry(session).isEmpty()) {
            return false;
        }
        FieldKitLedger.Asset asset = fieldKit.snapshot().assets().get(session.pinnedAssetId);
        if (asset == null || asset.state() != FieldKitLedger.AssetState.PLACED
                || asset.verification() == FieldKitLedger.Verification.NONE
                || asset.lastKnownPosition() == null) return false;
        FieldKitLedger.Position position = asset.lastKnownPosition();
        return position.dimension().equals(session.homeDimension)
                && position.x() == session.ownedPlacement.getX()
                && position.y() == session.ownedPlacement.getY()
                && position.z() == session.ownedPlacement.getZ();
    }

    private Optional<HomeEconomySession.ContainerIdentity> observeChestGeometry(String storageId,
            BlockPos position, BlockPos anchor, int radius) {
        if (client.world == null || protectedAreas == null || anchor == null) return Optional.empty();
        return MinecraftHomeStorageGeometry.observe(storageId, currentDimension(), position, anchor, radius,
                client.world::getBlockState, this::isLoaded,
                cell -> protectedAreas.decide(ProtectedAreaPolicy.Action.CONTAINER, currentDimension(),
                        cell.getX(), cell.getY(), cell.getZ(), true).allowed(), this::chestOpenable);
    }

    private Optional<HomeEconomySession.ContainerIdentity> observePinnedChestGeometry(Session session) {
        if (session.ownedPlacement == null || !session.homeDimension.equals(currentDimension())) return Optional.empty();
        Optional<HomeEconomySession.ContainerIdentity> observed = observeChestGeometry("pinned",
                session.ownedPlacement, session.homeAnchor, session.homeRadius);
        return observed.filter(identity -> session.requiredChestIdentity == null
                || MinecraftHomeStorageGeometry.sameLayout(session.requiredChestIdentity, identity));
    }

    private boolean chestScreenMatches(Session session, HomeEconomySession.ContainerIdentity frozen) {
        return client.player != null && client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler
                && MinecraftHomeStorageGeometry.screenMatches(frozen,
                observePinnedChestGeometry(session).orElse(null), handler.getRows());
    }

    private boolean inventoryMutationPending() {
        var diagnostics = inventory.transactionDiagnostics();
        return !inventory.cursorEmpty() || !diagnostics.pendingClickOwner().isBlank()
                || !diagnostics.cursorOwner().isBlank() || diagnostics.pendingRemovals() > 0;
    }

    private static BlockPos chestPosition(HomeEconomySession.ChestHalf half) {
        return new BlockPos(half.position().x(), half.position().y(), half.position().z());
    }

    private Result exactHomeAuthority(
            Session session,
            String operationId,
            ProtectedAreaPolicy.Action action,
            List<BlockPos> positions,
            BlockPos reportedPosition,
            long nowMillis) {
        if (!session.homePinned || protectedAreas == null) return null;
        String dimension = currentDimension();
        List<ProtectedAreaHomePermit.Target> targets = positions.stream()
                .map(position -> new ProtectedAreaHomePermit.Target(
                        dimension,
                        position.getX(), position.getY(), position.getZ()))
                .toList();
        ProtectedAreaClientState.HomeAuthorization authority =
                protectedAreas.requestExactHomeAuthorization(
                        operationId,
                        "home-asset:" + session.pinnedAssetId,
                        action,
                        targets,
                        nowMillis);
        return switch (authority.state()) {
            case GRANTED -> null;
            case WAITING -> new Result(
                    State.WAITING, authority.detail(), reportedPosition);
            case DENIED -> Result.worldPolicyDenied(
                    authority.detail(), reportedPosition);
        };
    }

    private void completeExactHomeAuthority(String operationId) {
        if (protectedAreas != null) {
            protectedAreas.completeExactHomeAuthorization(operationId);
        }
    }

    private String currentDimension() {
        return client.world == null
                ? ""
                : client.world.getRegistryKey().getValue().toString();
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public enum Kind {
        CRAFTING_TABLE(Blocks.CRAFTING_TABLE, "minecraft:crafting_table", "crafting table"),
        FURNACE(Blocks.FURNACE, "minecraft:furnace", "furnace"),
        CHEST(Blocks.CHEST, "minecraft:chest", "chest"),
        WHITE_BED(Blocks.WHITE_BED, "minecraft:white_bed", "white bed");

        private final Block block;
        private final String itemId;
        private final String displayName;

        Kind(Block block, String itemId, String displayName) {
            this.block = block;
            this.itemId = itemId;
            this.displayName = displayName;
        }

        public Block block() { return block; }
        public String itemId() { return itemId; }
        public String displayName() { return displayName; }
        public boolean opensScreen() { return this != WHITE_BED; }
        public boolean matches(BlockState state) {
            return this == WHITE_BED ? state.getBlock() instanceof BedBlock : state.isOf(block);
        }
    }

    public enum State {
        OPEN,
        APPROACHING,
        OPENING,
        PLACING,
        PLACED,
        SLEEPING,
        SLEPT,
        RECLAIMING,
        AWAITING_DROP,
        RECLAIMED,
        DROP_MISSING,
        WAITING,
        ITEM_MISSING,
        RETRY,
        BLOCKED
    }

    public enum HomeAssetLiveTruth {
        NO_RECORD,
        NO_POSITION,
        CARRIED,
        WRONG_DIMENSION,
        UNLOADED,
        EXACT_VERIFIED,
        ACCESS_RECOVERABLE,
        MISSING,
        REPLACED,
        OBSTRUCTED,
        DOUBLE_CHEST,
        UNVERIFIED,
        LOST
    }

    public record HomeAssetDiagnostic(
            String ledgerAssetId,
            boolean positionKnown,
            String dimension,
            int x,
            int y,
            int z,
            HomeAssetLiveTruth truth,
            String detail) {
        public HomeAssetDiagnostic {
            ledgerAssetId = Objects.requireNonNullElse(ledgerAssetId, "").trim();
            dimension = Objects.requireNonNullElse(dimension, "").trim();
            truth = Objects.requireNonNull(truth, "truth");
            detail = Objects.requireNonNullElse(detail, "").trim();
            if (!positionKnown && !dimension.isEmpty()) {
                throw new IllegalArgumentException(
                        "positionless Home diagnostic cannot name a dimension");
            }
        }

        public static HomeAssetDiagnostic withoutPosition(
                String id,
                HomeAssetLiveTruth truth,
                String detail) {
            return new HomeAssetDiagnostic(id, false, "", 0, 0, 0, truth, detail);
        }
    }

    public record RenewableWaterSource(
            BlockPos source,
            BlockPos standAt,
            int renewableNeighborMask,
            boolean reachableNow) {
        public RenewableWaterSource {
            source = Objects.requireNonNull(source, "source").toImmutable();
            standAt = Objects.requireNonNull(standAt, "standAt").toImmutable();
            // Legacy record name; mission candidates may be nonrenewable. The
            // Home entry points still require regenerationSafeGeometry before construction.
            if (renewableNeighborMask < 0 || renewableNeighborMask > 15) {
                throw new IllegalArgumentException("water neighbor mask is invalid");
            }
        }
    }

    public record HomeSiteSelection(
            BlockPos anchor,
            boolean exactRequest,
            int immediateAssetCells,
            boolean skyVisible) {
        public HomeSiteSelection {
            anchor = Objects.requireNonNull(anchor, "anchor").toImmutable();
            if (immediateAssetCells != 4) {
                throw new IllegalArgumentException("a selected Home needs four immediate asset cells");
            }
        }
    }

    public record Result(
            State state,
            String detail,
            BlockPos position,
            BaritonePort.FailureCause failureCause) {
        public Result(State state, String detail, BlockPos position) {
            this(state, detail, position, BaritonePort.FailureCause.NONE);
        }

        public Result {
            state = Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
            failureCause = Objects.requireNonNull(failureCause, "failureCause");
        }

        public static Result worldPolicyDenied(String detail, BlockPos position) {
            return new Result(
                    State.BLOCKED,
                    detail,
                    position,
                    BaritonePort.FailureCause.WORLD_POLICY_DENIED);
        }
    }

    public record HomeAnchorAssessment(
            HomeEconomyPolicy.HomeCandidate candidate,
            String anchorDetail) {
        public HomeAnchorAssessment {
            candidate = Objects.requireNonNull(candidate, "candidate");
            anchorDetail = Objects.requireNonNullElse(anchorDetail, "");
        }
    }

    private record HomeAnchorInspection(
            HomeEconomyPolicy.HomeCandidate candidate,
            String anchorDetail) {
    }

    private record LayoutBlocker(
            HomeEconomySession.AssetRole role,
            BlockPos position,
            String reason) {
        private LayoutBlocker {
            role = Objects.requireNonNull(role, "role");
            position = Objects.requireNonNull(position, "position").toImmutable();
            reason = Objects.requireNonNullElse(reason, "");
        }
    }

    private record HomeLayoutCandidates(
            Map<HomeEconomySession.AssetRole, List<HomeAssetLayoutPolicy.Option>> options,
            Map<HomeAssetLayoutPolicy.Option, Placement> placements,
            Map<HomeEconomySession.AssetRole, LayoutBlocker> blockers,
            List<Set<HomeAssetLayoutPolicy.Cell>> mobilityRoutes,
            String unavailableDetail) {
        private HomeLayoutCandidates {
            EnumMap<HomeEconomySession.AssetRole, List<HomeAssetLayoutPolicy.Option>> copied =
                    new EnumMap<>(HomeEconomySession.AssetRole.class);
            options.forEach((role, candidates) ->
                    copied.put(role, List.copyOf(candidates)));
            options = Map.copyOf(copied);
            placements = Map.copyOf(placements);
            blockers = Map.copyOf(blockers);
            mobilityRoutes = mobilityRoutes.stream()
                    .map(Set::copyOf)
                    .toList();
            unavailableDetail = Objects.requireNonNullElse(unavailableDetail, "");
        }

        private static HomeLayoutCandidates unavailable(
                BlockPos anchor,
                String detail) {
            Objects.requireNonNull(anchor, "anchor");
            return new HomeLayoutCandidates(
                    Map.of(), Map.of(), Map.of(), List.of(),
                    "Home layout from exact anchor " + coordinates(anchor)
                            + " is blocked: " + Objects.requireNonNullElse(detail, ""));
        }

        private Optional<HomeAssetLayoutPolicy.Layout> selection() {
            return select(List.of(HomeEconomySession.AssetRole.values()));
        }

        private Optional<HomeAssetLayoutPolicy.Layout> select(
                List<HomeEconomySession.AssetRole> required) {
            LinkedHashMap<HomeAssetLayoutPolicy.Option, HomeAssetLayoutPolicy.Cell>
                    accessCells = new LinkedHashMap<>();
            placements.forEach((option, placement) -> accessCells.put(
                    option, layoutCell(placement.standAt())));
            for (Set<HomeAssetLayoutPolicy.Cell> route : mobilityRoutes) {
                Optional<HomeAssetLayoutPolicy.Layout> selected =
                        HomeAssetLayoutPolicy.chooseSequential(
                                required, options, accessCells, route);
                if (selected.isPresent()) return selected;
            }
            return Optional.empty();
        }

        private String detail(List<HomeEconomySession.AssetRole> required) {
            if (!unavailableDetail.isBlank()) return unavailableDetail;
            for (HomeEconomySession.AssetRole role : required) {
                if (options.getOrDefault(role, List.of()).isEmpty()) {
                    LayoutBlocker blocker = blockers.get(role);
                    if (blocker == null) {
                        return "required " + role.item()
                                + " has no safe supported placement in the bounded Home envelope";
                    }
                    return "required " + role.item() + " footprint is blocked at "
                            + coordinates(blocker.position()) + ": " + blocker.reason();
                }
            }
            LayoutBlocker firstBlocker = required.stream()
                    .map(blockers::get)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
            if (firstBlocker != null) {
                return "no coherent non-overlapping Home layout exists; first blocked "
                        + firstBlocker.role().item() + " coordinate "
                        + coordinates(firstBlocker.position()) + ": "
                        + firstBlocker.reason();
            }
            if (!mobilityRoutes.isEmpty()) {
                return "no coherent Home layout preserves a proved walkable route "
                        + "from the anchor beyond the asset envelope";
            }
            HomeAssetLayoutPolicy.Option conflict = required.stream()
                    .map(role -> options.getOrDefault(role, List.of()))
                    .filter(candidates -> !candidates.isEmpty())
                    .map(List::getFirst)
                    .findFirst().orElse(null);
            return conflict == null
                    ? "no coherent non-overlapping Home layout exists in the bounded envelope"
                    : "required Home footprints conflict at "
                    + conflict.primary().x() + ' ' + conflict.primary().y() + ' '
                    + conflict.primary().z()
                    + ": table, furnace, chest, and bed require five distinct safe cells";
        }
    }

    private record RenewableWaterCandidate(BlockPos source, int neighborMask) {
    }

    private record Target(BlockPos block, BlockPos standAt) {
    }

    private record Placement(BlockPos placeAt, BlockPos support, BlockPos standAt) {
    }

    private record PinnedSession(Session session, Result failure) {
    }

    private record ToolPreparation(boolean ready, boolean missingRequiredTool, String detail) {
    }

    private static final class Session {
        private final String operationId;
        private final Kind kind;
        private final Set<BlockPos> rejected = new HashSet<>();
        private final Set<BlockPos> failedStands = new HashSet<>();
        private BlockPos target;
        private BlockPos standAt;
        private Placement placement;
        private long placementAttemptAt;
        private int placementInventoryBaseline = -1;
        private long placementEvidenceAt;
        private long openAttemptAt;
        private long nextInteractionAt;
        private int openFailures;
        private int failures;
        private String aimedInteraction = "";
        private int aimedAtPlayerAge = Integer.MIN_VALUE;
        private String ownedAssetId = "";
        private BlockPos ownedPlacement;
        private String ownedDimension = "";
        private String persistenceFailure = "";
        private String contextDimension = "";
        private int reclaimInventoryBaseline = -1;
        private long reclaimBrokenAt;
        private boolean workspaceActive;
        private boolean placementFromWorkspace;
        private boolean pendingCarriedProof;
        private boolean reclaimRecoveryRequired;
        private boolean placementOwnedBySession;
        private boolean reclaimStandReselectionRequired;
        private boolean homePinned;
        private String pinnedAssetId = "";
        private int pinnedOpenSyncId = -1;
        private boolean pinnedInteractionPending;
        private int pinnedInteractionSourceSyncId = -1;
        private HomeEconomySession.ContainerIdentity pinnedChestInteractionIdentity;
        private HomeEconomySession.ContainerIdentity pinnedChestOpenIdentity;
        private HomeEconomySession.ContainerIdentity requiredChestIdentity;
        private String homeFingerprint = "";
        private String homeDimension = "";
        private BlockPos homeAnchor;
        private int homeRadius;
        private HomeSleepPolicy.Cursor sleepCursor = HomeSleepPolicy.Cursor.empty();
        private long sleepInteractionAt;
        private int sleepFailures;
        private String homeLayoutFailure = "";
        private int homePhysicalAttempts;
        private long homePhysicalAttemptStartedAt = -1L;

        private Session(String operationId, Kind kind) {
            this.operationId = operationId;
            this.kind = kind;
        }

        private void reject(BlockPos pos) {
            if (pos != null) rejected.add(pos.toImmutable());
            failedStands.clear();
            openFailures = 0;
            clearPinnedScreenAcknowledgement(this);
            clearAim();
        }

        private void clearAim() {
            aimedInteraction = "";
            aimedAtPlayerAge = Integer.MIN_VALUE;
        }
    }
}
