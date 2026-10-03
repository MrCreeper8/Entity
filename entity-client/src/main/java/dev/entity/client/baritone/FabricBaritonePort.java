package dev.entity.client.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.Rotation;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.input.Input;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.entity.client.autonomy.BlockInteractionRaycaster;
import dev.entity.client.autonomy.policy.DeliveryPolicy;
import dev.entity.client.autonomy.policy.DimensionRoutePolicy;
import dev.entity.client.autonomy.policy.AtomicDoorPassageStore;
import dev.entity.client.autonomy.policy.DoorPassageSession;
import dev.entity.client.autonomy.policy.HomeEconomySession;
import dev.entity.client.autonomy.policy.MinecraftHomeInteriorObserver;
import dev.entity.client.autonomy.policy.MiningEntranceCheckpoint;
import dev.entity.client.autonomy.policy.ThrowawayReservationPolicy;
import dev.entity.client.autonomy.resource.LoadedResourceClassifier;
import dev.entity.client.autonomy.resource.ManagedFarmIngressPolicy;
import dev.entity.client.autonomy.resource.MinecraftResourceObserver;
import dev.entity.client.autonomy.resource.MinecraftResourcePerception;
import dev.entity.client.autonomy.resource.ResourceActuationSession;
import dev.entity.client.autonomy.resource.ResourceBreakScope;
import dev.entity.client.autonomy.resource.ResourceInteractionReadiness;
import dev.entity.client.autonomy.resource.SupportedFlowerHarvest;
import dev.entity.client.control.ActionLease;
import dev.entity.client.control.ExecutionKernel;
import dev.entity.client.control.GridEscapePlanner;
import dev.entity.core.control.BodyArbiter;
import dev.entity.core.control.ControlLease;
import dev.entity.core.farm.ManagedFarmPolicy;
import dev.entity.core.port.BaritonePort;
import dev.entity.core.stewardship.EntityDispositionPolicy;
import dev.entity.core.stewardship.PropertyCreeperSafetyPolicy;
import dev.entity.core.stewardship.ProtectedAreaHomePermit;
import dev.entity.core.stewardship.ProtectedAreaPolicy;
import dev.entity.core.stewardship.ResourceStewardshipPolicy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import dev.entity.client.control.MinecraftActuatorGateway;
import dev.entity.client.control.MinecraftEntityAttackGateway;
import dev.entity.client.control.MinecraftMovementGateway;
import dev.entity.client.control.MovementFrame;
import dev.entity.client.control.MovementFrameActuator;
import dev.entity.client.protection.ProtectedAreaClientState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkStatus;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Supported public-API boundary around Baritone 1.15.0. */
public final class FabricBaritonePort implements BaritonePort {
    private static final double TARGET_SEARCH_RADIUS = 128.0;
    private static final double ATTACK_REACH_SQUARED = 3.2 * 3.2;
    private static final long TRAVERSAL_STALL_MILLIS = 7_000;
    private static final long INITIAL_CALCULATION_STALL_MILLIS = 90_000;
    private static final int MAX_RECENT_TRAVERSAL_FAILURES = 3;
    private static final long TRAVERSAL_FAILURE_WINDOW_MILLIS = 120_000;
    private static final int DEFAULT_HUNT_SEARCH_RADIUS = 512;
    private static final long DEFAULT_HUNT_SEARCH_MILLIS = 5 * 60_000L;
    private static final int HUNT_CLOSE_RADIUS = 1;
    private static final double HUNT_COMBAT_HAND_RETENTION_DISTANCE = 6.0;
    private static final long HUNT_TARGET_REJECTION_MILLIS = 90_000L;
    private static final int MAX_REMEMBERED_HUNT_REJECTIONS = 64;
    private static final int MAX_HUNT_ROUTE_SAMPLES = 24;
    private static final int MAX_RECENT_COMBAT_ATTACKS = 16;
    private static final long AQUATIC_ROUTE_COMMIT_MILLIS = 5_000;
    private static final long AQUATIC_BARITONE_HANDOFF_MIN_MILLIS = 5_000;
    private static final long AQUATIC_BARITONE_HANDOFF_MAX_MILLIS = 15_000;
    private static final double AQUATIC_BARITONE_HANDOFF_PROGRESS = 1.5;
    private static final long SUSPENDED_AQUATIC_ROUTE_MILLIS = 30_000;
    private static final GridEscapePlanner.Limits AQUATIC_REDIVE_LIMITS =
            new GridEscapePlanner.Limits(12, 8, 6, 4_096);
    private static final double DIRECT_AQUATIC_STOP_DISTANCE = 1.35;
    private static final long TACTICAL_SHIELD_REISSUE_MILLIS = 750L;
    private static final long TACTICAL_SHIELD_START_TIMEOUT_MILLIS = 2_500L;
    private static final long CANCELED_ROUTE_RESTART_GRACE_MILLIS = 750L;
    private static final long LOCAL_COLLISION_STALL_MILLIS = 1_500L;
    private static final long LOCAL_COLLISION_RECOVERY_MILLIS = 650L;
    private static final long PORTAL_AUTHORITY_TIMEOUT_MILLIS = 10_000L;
    private static final int RESOURCE_SEARCH_HORIZONTAL_RADIUS = 64;
    private static final int RESOURCE_SEARCH_VERTICAL_RADIUS = 48;
    private static final int MANAGED_FARM_SEARCH_VERTICAL_RADIUS = 16;
    private static final int MAX_RESOURCE_SEARCH_CELLS = 300_000;
    private static final int MAX_RESOURCE_CLASSIFIED_CANDIDATES = 32;
    private static final long RESOURCE_REPLANT_TIMEOUT_MILLIS = 4_000L;
    private static final long RESOURCE_DROP_PICKUP_TIMEOUT_MILLIS = 15_000L;
    private static final long RESOURCE_DROP_ROUTE_REFRESH_MILLIS = 500L;
    private static final long RESOURCE_TARGET_ROUTE_STALL_MILLIS = 12_000L;
    private static final int FUNCTIONAL_MINE_LIGHT_THRESHOLD = 7;
    private static final int FUNCTIONAL_MINE_LIGHT_SPACING = 6;
    private static final Set<String> RANGED_COMBAT_ENTITY_TYPES = Set.of(
            "skeleton", "stray", "bogged", "pillager", "illusioner",
            "blaze", "ghast", "breeze", "shulker", "guardian", "elder_guardian");

    private final MinecraftClient client;
    private final MinecraftActuatorGateway actuators;
    private final MinecraftEntityAttackGateway entityAttacks;
    private final MinecraftMovementGateway movement;
    private final IBaritone baritone;
    private final NativeRouteRecoveryProcess routeRecovery;
    private final NativeProspectingProcess nativeProspecting;
    private BlockPos rememberedThreat;
    private String rememberedThreatDimension = "";
    private long rememberedThreatUntil;
    private final Set<BlockPos> threatDetourAttempts = new LinkedHashSet<>();
    private final BodyArbiter arbiter;
    private final Logger logger;
    private final AtomicReference<PathEvent> lastPathEvent = new AtomicReference<>();
    private final TraversalWatchdog.RetryBudget traversalRetryBudget =
            new TraversalWatchdog.RetryBudget(
                    MAX_RECENT_TRAVERSAL_FAILURES, TRAVERSAL_FAILURE_WINDOW_MILLIS);
    private final ProtectionTraversalBudgetScope protectionTraversalScope =
            new ProtectionTraversalBudgetScope();
    private final HuntTargetPolicy.RejectionMemory huntTargetRejections =
            new HuntTargetPolicy.RejectionMemory(
                    HUNT_TARGET_REJECTION_MILLIS, MAX_REMEMBERED_HUNT_REJECTIONS);
    private final CombatAttackJournal combatAttacks =
            new CombatAttackJournal(MAX_RECENT_COMBAT_ATTACKS);
    private final RouteSupportCustody routeSupportCustody = new RouteSupportCustody();
    private final AquaticRouteCustodyPolicy.RouteHandoffLease aquaticRouteHandoff =
            new AquaticRouteCustodyPolicy.RouteHandoffLease();
    private final DoorPassageSession doorPassage;
    private final MinecraftHomeInteriorObserver homeInteriors;
    private final MinecraftFunctionalRoutes functionalRoutes;
    private final dev.entity.client.autonomy.policy.IdleStockMiningLimit idleMiningLimit =
            new dev.entity.client.autonomy.policy.IdleStockMiningLimit();
    private final MinecraftResourceDescentGuard resourceDescentGuard;
    private final MinecraftResourceObserver resourceObserver;
    private final MinecraftResourcePerception resourcePerception;
    private final Set<Block> baselineDisallowedBreaks;
    private final List<Item> baselineAcceptableThrowawayItems;
    private final int baselineMaxFallHeightBucket;
    private Set<String> missionProtectedThrowawayItems = Set.of();
    private Set<Block> dynamicDisallowedBreaks = Set.of();
    private int inventorySafetyHash = Integer.MIN_VALUE;
    private int configuredMaxFallHeight = 3;
    private boolean safetyReplanPending;
    private RouteSupportRecovery routeSupportRecovery;
    private MinecraftLivestockObserver livestockObserver;
    private ProtectedAreaClientState protectedAreas;

    private ActiveOperation active;
    private dev.entity.client.blueprint.BlueprintProjects blueprintProjects;
    private dev.entity.client.blueprint.BlueprintBuildSession blueprintSession;
    private dev.entity.client.blueprint.BlueprintBuildScope blueprintBuildScope;
    private NativePropertyCosts.Blueprint blueprintCostProject;
    private String nativeRouteWorldIdentity = "";
    private long blueprintSupportRevision=-1;

    public synchronized void blueprintProjects(dev.entity.client.blueprint.BlueprintProjects projects) {
        this.blueprintProjects = projects;
    }

    @Override
    public synchronized java.util.Optional<Map<String,Integer>> blueprintLayerMaterials(
            String operationId,long controlEpoch) {
        if(active==null || blueprintSession==null || !active.goal.kind().equals("build")
                || !active.goal.missionId().equals(operationId)
                || active.controlLease.epoch()!=controlEpoch) return java.util.Optional.empty();
        return blueprintSession.layerMaterials();
    }

    private void startBlueprint(ActiveOperation operation) {
        if (blueprintProjects == null || !blueprintProjects.confirmed())
            throw new IllegalArgumentException("No confirmed blueprint project; use /e build show");
        var args = operation.goal.arguments();
        var project = blueprintProjects.require(required(args,"projectId"),required(args,"digest"));
        var scope = new dev.entity.client.blueprint.BlueprintBuildScope(project,
                () -> active == operation && arbiter.isValid(operation.controlLease,System.currentTimeMillis()),blueprintProjects.supports());
        blueprintBuildScope = scope;
        // Door/support handoffs recommit this same operation. Retain the original
        // settings snapshot so final cancellation restores the pre-build values.
        if (blueprintSession == null)
            blueprintSession = new dev.entity.client.blueprint.BlueprintBuildSession(client,baritone,project,
                    blueprintProjects.supports(),blueprintProjects.workProgress(project));
        if (protectedAreas != null) protectedAreas.blueprintScope(scope);
        actuators.blueprintScope(scope);
        refreshBlueprintCosts(operation,project);
        // Native planning and the final click share the same project/receipt authority.
        publishNativePropertyCosts(operation);
        blueprintSession.start();
        operation.sawActiveProcess = true;
    }
    private void refreshBlueprintCosts(ActiveOperation operation,dev.entity.client.blueprint.BlueprintProject project) {
        var supports=blueprintProjects.supports();
        Map<BlockPos,String> ownedSupports=supports.states(project);
        Map<NativePropertyCosts.Coordinate, NativePropertyCosts.BuildCell> costCells = new java.util.HashMap<>();
        for (var cell : project.cells()) {
            BlockState before = dev.entity.client.blueprint.BlueprintDesign.parseState(cell.before());
            BlockState desired = dev.entity.client.blueprint.BlueprintDesign.parseState(cell.state());
            costCells.put(new NativePropertyCosts.Coordinate(cell.x(), cell.y(), cell.z()),
                    new NativePropertyCosts.BuildCell(Block.getRawIdFromState(before),
                            Block.getRawIdFromState(desired), desired.isAir(),
                            dev.entity.client.blueprint.BlueprintDoorMaterials.requiresExactBuildPlacement(desired),
                            dev.entity.client.blueprint.BlueprintSupportLedger.eligible(cell),
                            ownedSupports.containsKey(cell.pos())?Block.getRawIdFromState(
                                    dev.entity.client.blueprint.BlueprintDesign.parseState(ownedSupports.get(cell.pos()))):-1));
        }
        blueprintCostProject = new NativePropertyCosts.Blueprint(project.projectId(), project.digest(),
                operation.goal.missionId(), operation.controlEpoch, costCells,
                dev.entity.client.blueprint.BlueprintTerrainStates.naturalSoilStateIds(),
                dev.entity.client.blueprint.BlueprintProjects.bedPartnerOffsets());
        blueprintSupportRevision=supports.revision();
    }
    private SuspendedAquaticRoute suspendedAquaticRoute;
    private final AttackMissionObjective attackMissionObjective = new AttackMissionObjective();
    private TrackedWaypoint trackedWaypoint;
    private long operationGeneration;
    private MovementAuthority movementAuthority;

    /** Runtime-authorized objective memory has no movement, inventory or attack authority. */
    public AttackMissionObjective attackMissionObjective() { return attackMissionObjective; }

    /** Last native combat decision, timestamped and tied to the active actuator generation. */
    public synchronized JsonObject combatEngagementSnapshot() {
        return active == null ? new JsonObject() : active.combatEngagement.deepCopy();
    }

    public FabricBaritonePort(
            MinecraftClient client,
            BodyArbiter arbiter,
            Path dataDirectory,
            Logger logger) throws IOException {
        this.client = Objects.requireNonNull(client, "client");
        this.actuators = MinecraftActuatorGateway.shared(client);
        this.entityAttacks = MinecraftEntityAttackGateway.shared(client);
        this.movement = MinecraftMovementGateway.shared(client);
        this.arbiter = Objects.requireNonNull(arbiter, "arbiter");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.doorPassage = new DoorPassageSession(new AtomicDoorPassageStore(
                Objects.requireNonNull(dataDirectory, "dataDirectory")
                        .resolve("door-passage.bin")));
        this.homeInteriors = new MinecraftHomeInteriorObserver(client);
        this.functionalRoutes = new MinecraftFunctionalRoutes(
                client, dataDirectory, logger);
        this.resourceDescentGuard = new MinecraftResourceDescentGuard(
                client, actuators);
        this.resourceObserver = new MinecraftResourceObserver(client);
        this.resourcePerception = new MinecraftResourcePerception(client);
        this.actuators.setPortalPassageFence(
                doorPassage.snapshot().intent() != null);
        this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        NativeMiningBuoyancy.bind(baritone.getInputOverrideHandler(),this::nativeMiningWaitingInWater);
        this.routeRecovery = new NativeRouteRecoveryProcess(baritone);
        this.nativeProspecting = new NativeProspectingProcess(baritone);
        this.baselineDisallowedBreaks = Set.copyOf(
                BaritoneAPI.getSettings().blocksToDisallowBreaking.value);
        this.baselineAcceptableThrowawayItems = List.copyOf(
                BaritoneAPI.getSettings().acceptableThrowawayItems.value);
        this.baselineMaxFallHeightBucket = BaritoneAPI.getSettings().maxFallHeightBucket.value;
        configureMaximumMovement();
        refreshBreakSafety(true);
        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onPathEvent(PathEvent event) {
                lastPathEvent.set(event);
                ActiveOperation operation = active;
                if (operation != null) {
                    operation.lastEvent = event;
                    operation.lastEventAt = System.currentTimeMillis();
                }
            }
        });
    }

    /** Evaluated by the current native input handler, not by the slower mission poll. */
    private synchronized boolean nativeMiningWaitingInWater() {
        ActiveOperation operation=active;
        var player=client.player;
        if(operation==null||player==null||client.world==null||player.isDead())return false;
        var input=baritone.getInputOverrideHandler();
        var direct=movement.snapshot();
        boolean interaction=player.isUsingItem()
                ||client.interactionManager!=null&&client.interactionManager.isBreakingBlock()
                ||input.isInputForcedDown(Input.CLICK_LEFT)||input.isInputForcedDown(Input.CLICK_RIGHT);
        boolean directed=input.isInputForcedDown(Input.MOVE_FORWARD)||input.isInputForcedDown(Input.MOVE_BACK)
                ||input.isInputForcedDown(Input.MOVE_LEFT)||input.isInputForcedDown(Input.MOVE_RIGHT)
                ||input.isInputForcedDown(Input.SNEAK);
        return NativeMiningBuoyancy.eligible(
                arbiter.isValid(operation.controlLease,System.currentTimeMillis()),
                isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT)),
                player.isTouchingWater()&&!player.isInLava()
                        &&!player.hasVehicle()&&!player.getAbilities().flying,
                baritone.getPathingBehavior().isPathing()||baritone.getPathingBehavior().getCurrent()!=null,
                interaction,directed,operation.tacticalInputsActive||operation.aquaticInputsForced
                        ||!operation.aquaticPursuitCustody.baritoneMayOwnMovement()
                        ||direct.activeAction().isPresent()||direct.neutralPending());
    }

    /**
     * Binds the exact inner action capability used at the final movement-input boundary.
     * The binding is refreshed once per client tick by the owning runtime/executor.
     */
    public synchronized void bindMovementAction(
            ExecutionKernel authority,
            ControlLease parent,
            ActionLease action,
            long clientTick,
            long nowMillis) {
        Objects.requireNonNull(authority, "authority")
                .requireValid(Objects.requireNonNull(action, "action"),
                        Objects.requireNonNull(parent, "parent"), clientTick, nowMillis);
        movementAuthority = new MovementAuthority(
                authority, parent, action, clientTick, nowMillis);
    }

    /** Refreshes the bounded factual room/portal topology for the selected Home. */
    public synchronized MinecraftHomeInteriorObserver.Observation observeHomeInterior(
            HomeEconomySession.HomeAnchor home) {
        return homeInteriors.observe(Objects.requireNonNull(home, "home"));
    }

    public MinecraftResourcePerception resourcePerception() { return resourcePerception; }

    /** Binds authenticated property facts into both hunt selection and final attack. */
    public synchronized void installProtectedAreaPolicy(
            ProtectedAreaClientState protectedAreas) {
        this.protectedAreas = Objects.requireNonNull(protectedAreas, "protectedAreas");
        livestockObserver = new MinecraftLivestockObserver(
                client,
                this.protectedAreas,
                logger);
        entityAttacks.installHuntBoundary(livestockObserver::decide);
    }

    /** Clears stale room topology immediately when the owner clears Home. */
    public synchronized MinecraftHomeInteriorObserver.Observation clearHomeInterior() {
        return homeInteriors.clearHome();
    }

    /** Immutable factual topology consumed by later shelter policy and diagnostics. */
    public synchronized MinecraftHomeInteriorObserver.Observation homeInteriorObservation() {
        return homeInteriors.cachedObservation();
    }

    /** Installs the server-authoritative UUID for the currently loaded world. */
    public synchronized void installFunctionalRouteWorldIdentity(
            String dimension,
            String worldIdentity) {
        if (!Objects.equals(nativeRouteWorldIdentity, worldIdentity)) {
            clearFailedAscendEdges();
            if (active != null) active.failedAscendEdges.clear();
        }
        nativeRouteWorldIdentity = Objects.requireNonNullElse(worldIdentity, "");
        functionalRoutes.installWorldIdentity(dimension, worldIdentity);
    }

    public synchronized void clearFunctionalRouteWorldIdentity() {
        nativeRouteWorldIdentity = "";
        clearFailedAscendEdges();
        if (active != null) active.failedAscendEdges.clear();
        releaseIdleMiningLimit();
        functionalRoutes.clearWorldIdentity();
    }

    public record IdleUndergroundSite(String routeId, String dimension, BlockPos entrance,
            BlockPos workCell, BlockPos observedOre, String blockId, int observedCount, int maxTargetY) { }

    /** Finite idle admission uses a known usable mine, not a generic ore search or new pathfinder. */
    public synchronized Optional<IdleUndergroundSite> idleUndergroundSite(Set<String> blocks) {
        if (!playerContextReady() || protectedAreas == null) return Optional.empty();
        for (var access : functionalRoutes.knownMiningAccesses()) {
            if (!idleCovered(access.endpoint())) continue;
            var property = protectedAreas.observeProperty(access.dimension(), access.entrance().getX(), access.entrance().getZ());
            if (!property.available() || property.protectedProperty()) continue;
            BlockPos selected = null; String blockId = ""; int count = 0; int maxY = Integer.MIN_VALUE;
            for (BlockPos candidate : BlockPos.iterate(access.endpoint().add(-6, -3, -6), access.endpoint().add(6, 3, 6))) {
                if (!idleCovered(candidate) || !resourcePerception.permitsBlock(candidate)) continue;
                String id = Registries.BLOCK.getId(client.world.getBlockState(candidate).getBlock()).getPath();
                if (!blocks.contains(id) || !hasSafeSuitableTool(client.world.getBlockState(candidate))) continue;
                var allowed = protectedAreas.decide(ProtectedAreaPolicy.Action.BREAK,
                        access.dimension(), candidate.getX(), candidate.getY(), candidate.getZ(), true);
                if (!allowed.allowed()) continue;
                if (selected == null) { selected = candidate.toImmutable(); blockId = id; }
                count++; maxY = Math.max(maxY, candidate.getY());
            }
            if (selected != null) return Optional.of(new IdleUndergroundSite(access.routeId(), access.dimension(),
                    access.entrance(), access.endpoint(), selected, blockId, Math.min(16, count), maxY));
        }
        return Optional.empty();
    }

    private boolean idleCovered(BlockPos cell) {
        return client.world != null && client.world.getChunkManager().isChunkLoaded(cell.getX() >> 4, cell.getZ() >> 4)
                && !client.world.isSkyVisible(cell)
                && cell.getY() + 3 < client.world.getTopY(Heightmap.Type.OCEAN_FLOOR, cell.getX(), cell.getZ());
    }

    private String guardIdleUnderground(ActiveOperation operation) {
        if (!operation.goal.arguments().containsKey("idleUndergroundMaxY")
                || operation.functionalRouteReplay || operation.functionalMiningReturning
                || operation.functionalMiningReturnComplete || operation.functionalNativeReturn) return null;
        String reason = null;
        if (!idleCovered(baritone.getPlayerContext().playerFeet())) reason = "automatic mining left its covered underground worksite";
        else if (baritone.getPathingBehavior().getGoal() instanceof GoalRunAway)
            reason = "known underground ore is unavailable; automatic prospecting was not authorized";
        var path = baritone.getPathingBehavior().getCurrent();
        if (reason == null && path != null && path.getPath().positions().stream().anyMatch(cell -> !idleCovered(cell)))
            reason = "native mining route would leave underground cover; automatic batch deferred";
        if (reason == null) return null;
        cancelProcesses();
        operation.blockedDetail = reason; operation.waitingDetail = reason; operation.progressExpected = false;
        return reason;
    }

    @Override
    public synchronized void start(Goal goal, ControlLease lease) {
        start(goal, lease, false);
    }

    /**
     * Starts a mission with an optional one-shot conservative aquatic handoff. The flag is used
     * only when Runtime observed this exact mission being created after SURFACE_FOR_AIR already
     * owned the body, so there was no earlier {@link ActiveOperation} whose route could be saved.
     */
    public synchronized void start(
            Goal goal,
            ControlLease lease,
            boolean conservativeAquaticResume) {
        long now = System.currentTimeMillis();
        ActiveOperation current = active;
        boolean requestedLeaseValid = arbiter.isValid(lease, now);
        boolean canceledRouteRequiresRestart = current != null
                && canceledRouteRequiresRestart(current, now);
        boolean sameOperation = current != null
                && current.goal.equals(goal)
                && !canceledRouteRequiresRestart;
        boolean sameEpoch = sameOperation && lease != null && current.controlEpoch == lease.epoch();
        boolean sameOwner = sameOperation && lease != null
                && current.controlLease.owner().equals(lease.owner());
        boolean activeLeaseValid = current != null && arbiter.isValid(current.controlLease, now);
        BaritoneStartPolicy.Decision admission = BaritoneStartPolicy.decide(
                requestedLeaseValid, sameOperation, sameEpoch, sameOwner, activeLeaseValid);
        if (admission == BaritoneStartPolicy.Decision.REFRESH_ACTIVE) {
            current.controlLease = lease;
            return;
        }
        if (admission == BaritoneStartPolicy.Decision.IGNORE_STALE_DUPLICATE) {
            logger.debug("Ignored late duplicate Baritone start for {} after its live lease was refreshed",
                    goal.missionId());
            return;
        }
        if (admission == BaritoneStartPolicy.Decision.REJECT_STALE) {
            throw new IllegalStateException("Rejected Baritone start with a stale body lease");
        }

        if (canceledRouteRequiresRestart) {
            logger.info("Restarting canceled Baritone route {} after it remained quiescent and unfinished",
                    current.goal.missionId());
        }

        AquaticRoute resumedAquaticRoute = takeSuspendedAquaticRoute(goal, now);
        cancelProcesses();
        ActiveOperation operation = new ActiveOperation(
                goal, now, lease, ++operationGeneration, goal.missionId());
        active = operation;
        actuators.setPortalPassageFence(true);
        applyThrowawayFence(operation);
        if (resumedAquaticRoute != null) {
            operation.committedAquaticRoute = resumedAquaticRoute;
            operation.aquaticRouteValidUntil = now + AQUATIC_ROUTE_COMMIT_MILLIS;
        }
        operation.aquaticResumePending = resumedAquaticRoute != null
                || conservativeAquaticResume;
        setActuatorScope(BaritoneActuatorScopePolicy.Owner.ROUTE);
        lastPathEvent.set(null);
        configureTraversalMode(operation, now);
        refreshBreakSafety(true);
        Map<String, String> args = goal.arguments();
        if (holdForRouteSupportRecovery(operation)) {
            logger.info("Holding Baritone operation {} until authoritative support rollback lands safely",
                    goal.missionId());
            return;
        }
        switch (goal.kind().toLowerCase(Locale.ROOT)) {
            case "build" -> startBlueprint(operation);
            case "goto", "go" -> startGoto(args);
            case "protection_escape" -> startProtectionEscapeGoal(operation);
            case "come" -> {
                operation.completionRange = Math.max(1.0, parseDouble(args.get("range"), 3.0));
                startFollow(
                        required(args, "player"),
                        DeliveryPolicy.followRadius(operation.completionRange));
            }
            case "follow" -> startFollow(required(args, "player"));
            case "mine", "acquire", "fetch", "get" -> startMine(args);
            case "attack", "hunt" -> startAttack(args);
            case "guard", "protect" -> {
                String player = args.getOrDefault("player", args.getOrDefault("owner", ""));
                if (!player.isBlank()) startFollow(player);
                else operation.blockedDetail = "guard requires a player until the tactical combat module is enabled";
            }
            default -> operation.blockedDetail = "unsupported mission kind: " + goal.kind();
        }
        logger.info("Baritone operation {} started for mission {}{}",
                goal.kind(), goal.missionId(),
                operation.conservativeTraversal ? " with conservative traversal" : "");
    }

    /**
     * Starts a short-lived protection operation pinned to the exact loaded entity chosen by the
     * protection policy. This is deliberately not a durable mission: the core pauses the durable
     * mission while the protection lease owns the body, then resumes it when the threat clears.
     */
    public synchronized void startProtection(Entity target, String description, ControlLease lease) {
        startProtection(target, description, lease, ProtectionCombatContext.STANDARD);
    }

    /**
     * Starts or refreshes an exact protection operation with one operation-scoped combat
     * capability. The sealed-resolution context is deliberately explicit and revocable: ordinary
     * protection starts always use {@link ProtectionCombatContext#STANDARD}.
     */
    public synchronized void startProtection(
            Entity target,
            String description,
            ControlLease lease,
            ProtectionCombatContext combatContext) {
        startProtection(target, description, lease, combatContext, Optional.empty());
    }

    /**
     * Refreshes the exact protection target plus an optional policy-eligible, currently visible
     * ranged-pressure advisory. The advisory never owns target selection; it only supplies shield
     * facing during an ordinary in-reach cooldown YIELD.
     */
    public synchronized void startProtection(
            Entity target,
            String description,
            ControlLease lease,
            ProtectionCombatContext combatContext,
            Optional<Entity> visibleRangedPressure) {
        startProtection(
                target, description, lease, combatContext, visibleRangedPressure,
                "legacy-protection-episode");
    }

    /** Production overload with explicit protection-episode traversal failure ownership. */
    public synchronized void startProtection(
            Entity target,
            String description,
            ControlLease lease,
            ProtectionCombatContext combatContext,
            Optional<Entity> visibleRangedPressure,
            String protectionEpisodeId) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(combatContext, "combatContext");
        visibleRangedPressure = Objects.requireNonNull(
                visibleRangedPressure, "visibleRangedPressure");
        long now = System.currentTimeMillis();
        Goal goal = new Goal(protectionOperationId(target.getUuidAsString()), "attack", Map.of());
        ActiveOperation current = active;
        String requestedBudgetKey = ProtectionTraversalBudgetScope.key(
                protectionEpisodeId, target.getUuidAsString(), combatContext.name());
        boolean sameIdentity = current != null && current.goal.equals(goal)
                && current.attackTarget != null && current.attackTarget.isAlive()
                && !current.attackTarget.isRemoved();
        boolean sameFailedScope = sameIdentity && current.failed()
                && current.protectionCombatContext == combatContext
                && current.traversalBudgetKey.equals(requestedBudgetKey)
                && protectionTraversalScope.activeKey().equals(requestedBudgetKey);
        boolean sameOperation = sameIdentity && (!current.failed() || sameFailedScope);
        boolean sameEpoch = sameOperation && lease != null && current.controlEpoch == lease.epoch();
        boolean sameOwner = sameOperation && lease != null
                && current.controlLease.owner().equals(lease.owner());
        BaritoneStartPolicy.Decision admission = BaritoneStartPolicy.decide(
                arbiter.isValid(lease, now),
                sameOperation,
                sameEpoch,
                sameOwner,
                current != null && arbiter.isValid(current.controlLease, now));
        if (admission == BaritoneStartPolicy.Decision.REFRESH_ACTIVE) {
            // A queued CALC_FAILED is a producer event belonging to this exact generation. Refresh
            // its lease/advisory, but never replace or reset it before poll reports the typed attempt.
            if (current.failed()) {
                current.controlLease = lease;
                refreshVisibleRangedPressure(current, visibleRangedPressure
                        .filter(Entity::isAlive)
                        .filter(entity -> !entity.isRemoved())
                        .orElse(null));
                return;
            }
            current.traversalBudgetKey = activateProtectionTraversalScope(
                    protectionEpisodeId, target.getUuidAsString(), combatContext);
            current.controlLease = lease;
            if (current.protectionCombatContext != combatContext) {
                current.sealedShieldStartState = SealedShieldStartPolicy.State.STARTING;
                current.sealedShieldStartDeadlineAt = 0L;
                current.sealedRouteCustody = false;
            }
            current.protectionCombatContext = combatContext;
            Entity refreshedRangedPressure = visibleRangedPressure
                    .filter(Entity::isAlive)
                    .filter(entity -> !entity.isRemoved())
                    .orElse(null);
            refreshVisibleRangedPressure(current, refreshedRangedPressure);
            return;
        }
        if (admission == BaritoneStartPolicy.Decision.IGNORE_STALE_DUPLICATE) {
            logger.debug("Ignored late duplicate protection start for {} after its live lease was refreshed",
                    goal.missionId());
            return;
        }
        if (admission == BaritoneStartPolicy.Decision.REJECT_STALE) {
            throw new IllegalStateException("Rejected protection start with a stale body lease");
        }

        String traversalBudgetKey = activateProtectionTraversalScope(
                protectionEpisodeId, target.getUuidAsString(), combatContext);
        cancelProcesses();
        ActiveOperation operation = new ActiveOperation(
                goal, now, lease, ++operationGeneration, traversalBudgetKey);
        active = operation;
        actuators.setPortalPassageFence(true);
        // Publish exact STANDARD/SEALED identity with the generation before any settings/process
        // call can throw. Runtime can then debit a repeated executor failure once for this concrete
        // operation instead of recreating unaccounted generations forever.
        operation.attackTarget = target;
        operation.protectionCombatContext = combatContext;
        applyThrowawayFence(operation);
        setActuatorScope(BaritoneActuatorScopePolicy.Owner.ROUTE);
        lastPathEvent.set(null);
        configureTraversalMode(operation, now);
        refreshVisibleRangedPressure(operation, visibleRangedPressure
                .filter(Entity::isAlive)
                .filter(entity -> !entity.isRemoved())
                .orElse(null));
        operation.sealedShieldStartState = SealedShieldStartPolicy.State.STARTING;
        operation.sealedShieldStartDeadlineAt = 0L;
        operation.targetDescription = description == null || description.isBlank()
                ? EntityType.getId(target.getType()).getPath()
                : description;
        operation.targetPredicate = entity -> entity.getUuid().equals(target.getUuid());
        refreshBreakSafety(true);
        BaritoneAPI.getSettings().followRadius.value = 3;
        if (!holdForRouteSupportRecovery(operation)) {
            if (usesProtectionCombatRoute(operation, target)) {
                maintainProtectionCombatRoute(operation, target, now);
            } else {
                baritone.getFollowProcess().follow(operation.targetPredicate);
            }
        }
        operation.creeperFollowing = target instanceof CreeperEntity;
        logger.info("Protection operation started against {} ({}) with {} combat context",
                operation.targetDescription, target.getUuidAsString(), combatContext);
    }

    public synchronized Status pollProtection(Entity target) {
        Objects.requireNonNull(target, "target");
        return poll(protectionOperationId(target.getUuidAsString()));
    }

    /** A pinned native separation goal, never an attack or a replacement durable mission. */
    public synchronized Status executeProtectionEscape(
            Entity target, String workIdentity, ControlLease lease) {
        Objects.requireNonNull(target, "target");
        long now = System.currentTimeMillis();
        if (!playerContextReady() || !target.isAlive() || target.isRemoved()
                || lease == null || !"protection".equals(lease.owner())
                || !arbiter.isValid(lease, now)) {
            throw new IllegalStateException("Native protection escape requires its live exact protection owner");
        }
        String operationId = protectionEscapeOperationId(target, workIdentity);
        ActiveOperation operation = active;
        if (operation != null && operation.goal.missionId().equals(operationId)
                && operation.goal.kind().equals("protection_escape")) {
            // Do not recompute the threat origin, replace a failed generation, or renew a budget.
            if (operation.controlEpoch != lease.epoch()
                    || !operation.controlLease.owner().equals(lease.owner())) {
                throw new IllegalStateException("Native protection escape cannot inherit a different owner epoch");
            }
            operation.controlLease = lease;
            if (operation.pendingCompletionDetail != null
                    && operation.protectionEscapeOrigin != null) {
                BlockPos targetNow = target.getBlockPos();
                BlockPos feet = baritone.getPlayerContext().playerFeet();
                double currentSeparation = Math.hypot(
                        feet.getX() - targetNow.getX(), feet.getZ() - targetNow.getZ());
                if (currentSeparation < operation.protectionEscapeDistance) {
                    // The hostile followed after native arrival. Reopen this exact operation,
                    // not its budget/generation, and pin the materially changed live origin.
                    operation.protectionEscapeOrigin = targetNow.toImmutable();
                    operation.pendingCompletionDetail = null;
                    operation.lastEvent = null;
                    lastPathEvent.set(null);
                    operation.progressExpected = true;
                    operation.traversalWatchdog.reset();
                    configureTraversalMode(operation, now);
                    refreshBreakSafety(true);
                    startProtectionEscapeGoal(operation);
                }
            }
        } else {
            BlockPos origin = target.getBlockPos().toImmutable();
            BlockPos feet = baritone.getPlayerContext().playerFeet();
            double separation = Math.hypot(feet.getX() - origin.getX(), feet.getZ() - origin.getZ());
            double distance = Math.max(
                    dev.entity.client.protection.DistantThreatDisengagementPolicy.RANGED_NEAR_TERM_PRESSURE_RADIUS,
                    separation + dev.entity.client.protection.DistantThreatDisengagementPolicy.DEFAULT_REQUIRED_SEPARATION_GAIN);
            start(new Goal(operationId, "protection_escape", Map.of(
                    "targetId", target.getUuidAsString(),
                    "originX", Integer.toString(origin.getX()),
                    "originY", Integer.toString(origin.getY()),
                    "originZ", Integer.toString(origin.getZ()),
                    "distance", Double.toString(distance),
                    "allowWaterBucketFall", "false")), lease);
        }
        return poll(operationId);
    }

    public synchronized boolean hasProtectionEscape(Entity target, String workIdentity) {
        return active != null && active.goal.kind().equals("protection_escape")
                && active.goal.missionId().equals(protectionEscapeOperationId(target, workIdentity));
    }

    private String protectionEscapeOperationId(Entity target, String workIdentity) {
        return "protection-escape:" + currentDimension() + ':'
                + Objects.requireNonNullElse(workIdentity, "") + ':' + target.getUuidAsString();
    }

    private void startProtectionEscapeGoal(ActiveOperation operation) {
        Map<String, String> args = operation.goal.arguments();
        if (operation.protectionEscapeOrigin == null) {
            operation.protectionEscapeOrigin = new BlockPos(Integer.parseInt(required(args, "originX")),
                    Integer.parseInt(required(args, "originY")), Integer.parseInt(required(args, "originZ")));
            operation.protectionEscapeDistance = Double.parseDouble(required(args, "distance"));
        }
        // No maintainY: the native pathfinder may use ordinary cave stairs and vertical exits.
        baritone.getCustomGoalProcess().setGoalAndPath(
                new GoalRunAway(operation.protectionEscapeDistance, operation.protectionEscapeOrigin));
    }

    private Status pollProtectionEscapeArrival(ActiveOperation operation) {
        if (!operation.goal.kind().equals("protection_escape")) return null;
        var goal = baritone.getCustomGoalProcess().mostRecentGoal();
        var feet = baritone.getPlayerContext().playerFeet();
        if (goal != null && goal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
            operation.completedWorkUnits = 1;
            return completeStatus(operation, "native protection separation reached", 0);
        }
        return null;
    }

    /**
     * Consumes a queued primary calculation failure from the exact STANDARD operation before any
     * caller can replace its generation. Empty means there is no such pending producer edge.
     */
    public synchronized Optional<Status> pollPendingStandardProtectionFailure(
            Entity target,
            long controlEpoch) {
        Objects.requireNonNull(target, "target");
        ActiveOperation operation = exactStandardProtectionOperation(target, controlEpoch);
        if (operation == null || !operation.failed()) return Optional.empty();
        return Optional.of(traversalFailureStatus(
                operation,
                "Baritone could not calculate a usable path for the exact protection target",
                System.currentTimeMillis()));
    }

    /** Whether the exact STANDARD protection operation is already live and refreshable. */
    public synchronized boolean hasStandardProtectionOperation(Entity target, long controlEpoch) {
        Objects.requireNonNull(target, "target");
        return exactStandardProtectionOperation(target, controlEpoch) != null;
    }

    /** Exact operation generation for exception accounting; failed operations remain observable. */
    public synchronized long standardProtectionOperationGeneration(
            Entity target,
            long controlEpoch) {
        Objects.requireNonNull(target, "target");
        ActiveOperation operation = exactStandardProtectionOperation(target, controlEpoch);
        return operation == null ? -1L : operation.generation;
    }

    private ActiveOperation exactStandardProtectionOperation(Entity target, long controlEpoch) {
        ActiveOperation operation = active;
        return operation != null
                && operation.goal.missionId().equals(
                        protectionOperationId(target.getUuidAsString()))
                && operation.attackTarget == target
                && operation.attackTarget.isAlive()
                && !operation.attackTarget.isRemoved()
                && operation.controlEpoch == controlEpoch
                && operation.protectionCombatContext == ProtectionCombatContext.STANDARD
                ? operation
                : null;
    }

    /** True lifecycle boundary: a later protection episode starts with maximum movement. */
    public synchronized void clearProtectionTraversalScope() {
        protectionTraversalScope.clear().ifPresent(traversalRetryBudget::clear);
    }

    /**
     * Returns the last server-observed position of a target defeated by this exact operation.
     * The autonomy layer uses this as the ownership origin for the resulting item drops.
     */
    public synchronized Optional<BlockPos> completedAttackOrigin(String operationId) {
        ActiveOperation operation = active;
        if (operation == null || operationId == null
                || !operation.goal.missionId().equals(operationId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(operation.defeatedAt);
    }

    /**
     * Hands main-hand ownership from Baritone's route tooling to the hunt
     * combat phase only after the pinned target is physically in attack reach.
     * The autonomy inventory transaction may then select a weapon without
     * racing autoTool over the same hotbar slot.
     */
    public synchronized boolean prepareHuntCombatHand(String operationId) {
        return prepareHuntCombatHand(operationId, false);
    }

    /**
     * Production hunt handoff with exact inventory-transaction retention. Once a weapon click
     * owns the hand, a one-step target movement cannot restore autoTool until that click has
     * either been acknowledged or timed out by the inventory transaction engine.
     */
    public synchronized boolean prepareHuntCombatHand(
            String operationId,
            boolean exactWeaponTransactionPending) {
        ActiveOperation operation = active;
        boolean operationMatches = operation != null
                && operationId != null
                && operation.goal.missionId().equals(operationId)
                && operation.goal.kind().equalsIgnoreCase("hunt");
        boolean targetAlive = operationMatches
                && operation.attackTarget != null
                && operation.attackTarget.isAlive();
        boolean inAttackReach = targetAlive
                && client.player != null
                && (!operation.boundedHunt || !resourcePerception.legitimate()
                || resourcePerception.observesEntity(operation.attackTarget))
                && client.player.squaredDistanceTo(operation.attackTarget)
                <= ATTACK_REACH_SQUARED;
        boolean insideClosePursuitEnvelope = targetAlive
                && client.player != null
                && (!operation.boundedHunt || !resourcePerception.legitimate()
                || resourcePerception.observesEntity(operation.attackTarget))
                && client.player.squaredDistanceTo(operation.attackTarget)
                <= HUNT_COMBAT_HAND_RETENTION_DISTANCE
                * HUNT_COMBAT_HAND_RETENTION_DISTANCE;
        boolean ready = BaritoneActuatorScopePolicy.shouldOwnCombatHand(
                operationMatches,
                targetAlive,
                inAttackReach,
                insideClosePursuitEnvelope,
                operation != null && operation.combatHandPrepared,
                exactWeaponTransactionPending);
        if (!ready) {
            releaseCombatHand(operation);
            return false;
        }
        return prepareCombatHand(operation);
    }

    /**
     * Protection equivalent of {@link #prepareHuntCombatHand}: autoTool keeps
     * route ownership until the exact hostile is in melee reach, then yields
     * the hand so a carried weapon cannot oscillate with a route pickaxe.
     */
    public synchronized boolean prepareProtectionCombatHand(Entity target) {
        ActiveOperation operation = active;
        boolean operationMatches = operation != null
                && target != null
                && operation.goal.missionId().equals(protectionOperationId(target.getUuidAsString()))
                && operation.goal.kind().equalsIgnoreCase("attack")
                && operation.attackTarget == target;
        boolean targetAlive = operationMatches && target.isAlive();
        double distanceSquared = targetAlive && client.player != null
                ? client.player.squaredDistanceTo(target) : Double.POSITIVE_INFINITY;
        boolean ready = BaritoneActuatorScopePolicy.shouldOwnCombatHand(
                operationMatches, targetAlive,
                distanceSquared <= ATTACK_REACH_SQUARED,
                distanceSquared <= HUNT_COMBAT_HAND_RETENTION_DISTANCE
                        * HUNT_COMBAT_HAND_RETENTION_DISTANCE,
                operation != null && operation.combatHandPrepared, false);
        if (!ready) {
            releaseCombatHand(operation);
            return false;
        }
        return prepareCombatHand(operation);
    }

    /** Hold an acknowledged equip's operation/hand while releasing only its route inputs. */
    public synchronized boolean holdProtectionLoadout(Entity target, ControlLease lease) {
        ActiveOperation operation = active;
        if (operation == null || target == null || lease == null
                || operation.attackTarget != target || !target.isAlive()
                || !operation.goal.missionId().equals(protectionOperationId(target.getUuidAsString()))
                || operation.controlEpoch != lease.epoch()
                || !operation.controlLease.owner().equals(lease.owner())
                || !arbiter.isValid(lease, System.currentTimeMillis())) return false;
        // cancel() destroys the generation and restores the route inventory settings. The
        // next start then lets Baritone put a pick back in slot zero before the weapon's
        // acknowledgement can be used. Existing close custody pauses that same route and
        // re-pins it after equip without inventing another operation or inventory owner.
        operation.controlLease = lease;
        takeCloseCombatControl(operation);
        return true;
    }

    private boolean prepareCombatHand(ActiveOperation operation) {
        if (!operation.combatHandPrepared) {
            operation.previousAutoTool = BaritoneAPI.getSettings().autoTool.value;
            operation.previousAllowInventory = BaritoneAPI.getSettings().allowInventory.value;
            setActuatorScope(BaritoneActuatorScopePolicy.Owner.COMBAT_HAND);
            operation.combatHandPrepared = true;
        }
        try {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        } catch (RuntimeException ignored) {
        }
        if (client.interactionManager != null && client.interactionManager.isBreakingBlock()) {
            client.interactionManager.cancelBlockBreaking();
        }
        return true;
    }

    @Override
    public synchronized Status poll(String missionId) {
        ActiveOperation operation = active;
        if (operation == null || !operation.goal.missionId().equals(missionId)) {
            return new Status(State.IDLE, "no matching Baritone operation", Double.NaN, 0, arbiter.epoch());
        }
        if (!playerContextReady()) {
            return status(operation, State.CALCULATING,
                    "waiting for Minecraft world/player", Double.NaN);
        }
        if (operation.accessToolRequired)
            return status(operation, State.TRANSIENT_FAILURE, operation.waitingDetail, Double.NaN);
        String idleBoundary = guardIdleUnderground(operation);
        if (idleBoundary != null) return status(operation, State.BLOCKED, idleBoundary, Double.NaN);
        publishNativePropertyCosts(operation);
        long nowMillis = System.currentTimeMillis();
        refreshFailedAscendEdges(operation, nowMillis);
        if (operation.managedFarmIngress == null
                && operation.resourceSession != null
                && operation.resourceSession.sourceKind()
                        == ResourceActuationSession.SourceKind.HARVEST
                && operation.resourceApproachFeet != null) {
            // The first exact route is normally committed while the player is still
            // farther than the bounded gate corridor. Re-evaluate after ordinary
            // Baritone travel reaches that corridor so the loaded handoff can actually
            // occur at the chunk edge instead of only at mission start.
            beginManagedFarmIngress(
                    operation, operation.resourceApproachFeet, nowMillis);
        }
        DoorPassageOutcome farmIngress = maintainManagedFarmIngress(
                operation, nowMillis);
        if (farmIngress != null && farmIngress.hold()) {
            return status(
                    operation,
                    farmIngress.blocked() ? State.BLOCKED : State.EXECUTING,
                    farmIngress.detail(),
                    distanceRemaining());
        }
        DoorPassageOutcome passage = maintainDoorPassage(
                operation, System.currentTimeMillis());
        if (passage != null && passage.hold()) {
            return status(
                    operation,
                    passage.blocked() ? State.BLOCKED : State.EXECUTING,
                    passage.detail(),
                    distanceRemaining());
        }
        if (operation.pendingCompletionDetail != null) {
            return completeStatus(operation, operation.pendingCompletionDetail,
                    operation.pendingCompletionDistance);
        }
        // A persisted door-close debt intentionally cancels native processes.
        // Service its hold/resume boundary before interpreting builder inactivity.
        if (operation.goal.kind().equals("build") && blueprintSession != null) {
            var result = blueprintSession.observe();
            operation.completedWorkUnits = Math.max(operation.completedWorkUnits,result.verifiedWorkUnits());
            Status portalFinish = finishBlueprintPortal(operation, nowMillis);
            if (portalFinish != null) return portalFinish;
            operation.progressExpected = !result.paused();
            if (result.complete()) return completeStatus(operation,result.detail(),0);
            return new Status(result.paused() ? State.BLOCKED : State.EXECUTING,
                    result.detail(),result.total()-result.matched(),result.verifiedWorkUnits(),
                    operation.controlEpoch,!result.paused());
        }
        Status nativeReturn = pollNativeMiningReturn(operation, System.currentTimeMillis());
        if (nativeReturn != null) return nativeReturn;
        Status functionalRoute = pollFunctionalRoute(operation, System.currentTimeMillis());
        if (functionalRoute != null) return functionalRoute;
        refreshBreakSafety(false);
        if (operation.blockedDetail != null) {
            return status(operation, State.BLOCKED, operation.blockedDetail, Double.NaN);
        }
        String kind = operation.goal.kind().toLowerCase(Locale.ROOT);
        Status alternateRoute = pollAlternateRoute(operation, System.currentTimeMillis());
        if (alternateRoute != null) return alternateRoute;
        Status localRecovery = pollLocalCollisionRecovery(operation, System.currentTimeMillis());
        if (localRecovery != null) return localRecovery;
        String unsafeDescent = guardUnsafeResourceDescent(operation);
        if (unsafeDescent != null) {
            operation.progressExpected = false;
            return status(operation, operation.blockedDetail == null ? State.EXECUTING : State.BLOCKED,
                    operation.blockedDetail == null ? unsafeDescent : operation.blockedDetail,
                    distanceRemaining());
        }
        String unsafeBreak = guardUnsafeBlockBreak(operation);
        if (unsafeBreak != null) {
            operation.progressExpected = false;
            return status(operation, State.EXECUTING, unsafeBreak, Double.NaN);
        }
        observeFunctionalMiningRoute(System.currentTimeMillis());
        if (operation.resourceSession != null) {
            return pollScopedResourceOperation(operation, System.currentTimeMillis());
        }
        if (isMiningKind(kind)) {
            String missingTool = firstMissingTargetTool(operation.miningBlocks);
            if (missingTool != null) {
                pauseMiningForTool(operation, missingTool);
                return status(operation, State.EXECUTING, operation.waitingDetail, Double.NaN);
            }
            if (operation.waitingForTool) resumeMiningAfterToolArrives(operation);
            observeVerifiedMinedBlock(operation);
            if (operation.maximumMinedBlocks > 0
                    && operation.verifiedMinedBlocks >= operation.maximumMinedBlocks) {
                try {
                    baritone.getMineProcess().cancel();
                    baritone.getPathingBehavior().cancelEverything();
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    if (client.interactionManager != null) {
                        client.interactionManager.cancelBlockBreaking();
                    }
                } catch (RuntimeException ignored) {
                }
                operation.progressExpected = false;
                return completeStatus(
                        operation,
                        "verified bounded mining segment of "
                                + operation.verifiedMinedBlocks + " block(s)",
                        0);
            }
            int collected = Math.max(
                    0,
                    countTargetItems(operation.expectedItemIds) - operation.startingTargetItemCount);
            if (collected >= operation.quantity) {
                operation.completedWorkUnits = Math.max(
                        operation.completedWorkUnits, (long) collected * 1_000L);
                return beginOrPollFunctionalMiningReturn(
                        operation, System.currentTimeMillis(), collected);
            }
            maintainNativeProspecting(operation, System.currentTimeMillis());
            if (operation.alternateRouteRunning) {
                return status(operation, State.EXECUTING, operation.waitingDetail, distanceRemaining());
            }
        }
        if (operation.failed()) {
            if (kind.equals("hunt") && operation.attackTarget != null) {
                boolean attackableNow = client.player != null
                        && operation.attackTarget.isAlive()
                        && client.player.squaredDistanceTo(operation.attackTarget)
                        <= ATTACK_REACH_SQUARED;
                if (operation.attackTarget.isAlive() && !attackableNow) {
                    return rejectHuntTarget(
                            operation,
                            "Baritone CALC_FAILED for the pinned animal",
                            System.currentTimeMillis());
                }
                // A route is irrelevant after death/removal or once direct attack range is
                // reached. Let pollAttack verify the kill or swing instead of blacklisting it.
                operation.lastEvent = null;
                lastPathEvent.set(null);
            } else if (kind.equals("hunt") && operation.boundedHunt
                    && operation.huntFrontiers.hasCurrent()) {
                return rejectHuntFrontier(operation,
                        "Baritone could not calculate the pinned land frontier", System.currentTimeMillis());
            } else if (isMiningKind(kind) && (baritone.getMineProcess().isActive()
                    || baritone.getPathingBehavior().isPathing())) {
                if (requestMiningAccessTool(operation))
                    return status(operation, State.TRANSIENT_FAILURE, operation.waitingDetail, Double.NaN);
                // MineProcess receives the same failed-calculation signal and maintains its own
                // unreachable-target blacklist. Preserve that state instead of recreating the
                // process and selecting the same bad ore forever.
                operation.waitingDetail = "Baritone rejected one mining route; using its internal blacklist/recovery";
            } else {
                return traversalFailureStatus(
                        operation, "Baritone could not calculate a usable path", System.currentTimeMillis());
            }
        }

        maintainTrackedPlayer(operation);
        if (operation.blockedDetail != null) {
            return status(operation, State.BLOCKED, operation.blockedDetail, Double.NaN);
        }
        Status escapeArrival = pollProtectionEscapeArrival(operation);
        if (escapeArrival != null) return escapeArrival;
        if (kind.equals("goto") || kind.equals("go")) {
            var goal = baritone.getCustomGoalProcess().mostRecentGoal();
            var feet = baritone.getPlayerContext().playerFeet();
            if (goal != null && goal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
                operation.completedWorkUnits = 1;
                return completeStatus(operation, "destination reached", 0);
            }
        }
        if (kind.equals("come") && targetWithin(operation, operation.completionRange)) {
            operation.completedWorkUnits = 1;
            return completeStatus(operation, "owner reached", 0);
        }
        if (kind.equals("attack") || kind.equals("hunt")) {
            Status attackStatus = pollAttack(operation);
            if (attackStatus != null) {
                maintainAquaticTravel(operation);
                maintainRouteMomentum(operation);
                Status traversalFailure = pollTraversalWatchdog(
                        operation, System.currentTimeMillis());
                return traversalFailure == null ? attackStatus : traversalFailure;
            }
        }
        if (isMiningKind(kind)) {
            int inventoryNow = countTargetItems(operation.expectedItemIds);
            int collected = Math.max(0, inventoryNow - operation.startingTargetItemCount);
            operation.completedWorkUnits = Math.max(
                    operation.completedWorkUnits,
                    Math.min(operation.quantity, collected) * 1_000L);
            if (collected >= operation.quantity) {
                return beginOrPollFunctionalMiningReturn(
                        operation, System.currentTimeMillis(), collected);
            }

            boolean currentlyActive = baritone.getMineProcess().isActive()
                    || baritone.getPathingBehavior().isPathing();
            if (currentlyActive) operation.sawActiveProcess = true;
            boolean graceElapsed = System.currentTimeMillis() - operation.startedAt > 1_000;
            if (graceElapsed && operation.sawActiveProcess
                    && !baritone.getMineProcess().isActive()
                    && !baritone.getPathingBehavior().isPathing()) {
                operation.waitingDetail = "mining stopped before verified collection ("
                        + collected + "/" + operation.quantity + "); watchdog will replan";
            } else {
                operation.waitingDetail = operation.prospecting
                        ? "prospecting at Y=" + operation.prospectingY + " and scanning exposed veins; collected "
                        + collected + "/" + operation.quantity
                        : "searching known and new chunks; collected " + collected + "/" + operation.quantity;
            }
            if (client.interactionManager != null
                    && client.interactionManager.getBlockBreakingProgress() >= 0
                    && client.crosshairTarget instanceof BlockHitResult) {
                // Feed real break-stage advances to the watchdog. A genuinely hard block remains
                // alive; a frozen hit animation still reaches the normal no-progress timeout.
                int breakProgress = client.interactionManager.getBlockBreakingProgress();
                String targetKey = client.crosshairTarget instanceof BlockHitResult hit
                        ? hit.getBlockPos().getX() + "," + hit.getBlockPos().getY() + "," + hit.getBlockPos().getZ()
                        : "";
                long breakObservedAt = System.currentTimeMillis();
                BlockBreakProgressTracker.Result breakObservation =
                        operation.breakProgress.observeDetailed(targetKey, breakProgress, breakObservedAt);
                if (client.crosshairTarget instanceof BlockHitResult hit) {
                    rememberMiningBreakTarget(operation, hit.getBlockPos());
                }
                if (breakObservation == BlockBreakProgressTracker.Result.PROGRESSED) {
                    operation.completedWorkUnits++;
                }
                if (breakObservation == BlockBreakProgressTracker.Result.RESET
                        && operation.breakProgress.consecutiveResets() >= 3) {
                    operation.breakProgress.recovered();
                    routeRecovery.requestReplan(System.currentTimeMillis());
                    operation.waitingDetail = "local crack progress restarted repeatedly at "
                            + targetKey + "; replanning path with the mining search retained";
                    return status(operation, State.EXECUTING,
                            operation.waitingDetail, distanceRemaining());
                }
                operation.waitingDetail = "breaking the current block; collected "
                        + collected + "/" + operation.quantity;
            }
        }

        maintainAquaticTravel(operation);
        maintainRouteMomentum(operation);
        Status traversalFailure = pollTraversalWatchdog(operation, System.currentTimeMillis());
        if (traversalFailure != null) return traversalFailure;

        if (baritone.getPathingBehavior().getInProgress().isPresent()) {
            return status(operation, State.CALCULATING, "calculating path", distanceRemaining());
        }
        if (operation.aquaticBlockedDetail != null) {
            return status(operation, State.EXECUTING,
                    operation.aquaticBlockedDetail, distanceRemaining());
        }
        if (operation.waitingDetail != null) {
            return status(operation, State.EXECUTING, operation.waitingDetail, distanceRemaining());
        }
        return status(operation, State.EXECUTING, operationDetail(operation), distanceRemaining());
    }

    private Status beginOrPollFunctionalMiningReturn(
            ActiveOperation operation,
            long nowMillis,
            int collected) {
        String completed = "collected " + collected + "/" + operation.quantity
                + " verified target drops";
        if (operation.functionalMiningReturnComplete) {
            return completeStatus(operation, completed + " and returned to the mine entrance", 0);
        }
        if (!operation.functionalMiningReturning) {
            nativeProspecting.cancel();
            operation.functionalMiningReturning = true;
            operation.resetMiningReturnProgressBaseline = true;
            try {
                baritone.getMineProcess().cancel();
                baritone.getPathingBehavior().cancelEverything();
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                if (client.interactionManager != null) {
                    client.interactionManager.cancelBlockBreaking();
                }
            } catch (RuntimeException ignored) {
            }
        }

        MinecraftFunctionalRoutes.MiningReturnResult result =
                functionalRoutes.beginMiningReturn(nowMillis);
        return switch (result.action()) {
            case WAITING_FOR_STABLE_ENDPOINT -> {
                operation.progressExpected = false;
                operation.waitingDetail = result.detail();
                yield status(operation, State.EXECUTING,
                        completed + "; " + result.detail(), 0);
            }
            case REPLAYING -> {
                operation.functionalRouteReplay = true;
                operation.progressExpected = true;
                operation.waitingDetail = result.detail();
                yield status(operation, State.EXECUTING,
                        completed + "; " + result.detail(), distanceRemaining());
            }
            case NOT_REQUIRED -> {
                if (!atFunctionalMiningEntrance(operation)) {
                    yield beginNativeMiningReturn(operation, nowMillis,
                            "the restarted segment ended before the original mining entrance");
                }
                operation.functionalMiningReturning = false;
                operation.functionalMiningReturnComplete = true;
                operation.progressExpected = false;
                yield completeStatus(operation, completed + "; " + result.detail(), 0);
            }
            case BLOCKED -> {
                if (operation.functionalMiningEntrance != null) {
                    yield beginNativeMiningReturn(operation, nowMillis, result.detail());
                }
                operation.functionalMiningReturning = false;
                operation.functionalMiningReturnBlocked = true;
                operation.progressExpected = false;
                operation.blockedDetail = completed + "; cannot prove a usable mine exit: "
                        + result.detail();
                operation.waitingDetail = operation.blockedDetail;
                yield status(
                        operation,
                        State.BLOCKED,
                        operation.blockedDetail,
                        distanceRemaining(),
                        BaritonePort.FailureCause.TARGET_ROUTE_EXHAUSTED);
            }
        };
    }

    /** Short restarted breadcrumbs cannot replace the leaf's original return destination. */
    private boolean atFunctionalMiningEntrance(ActiveOperation operation) {
        return operation.functionalMiningEntrance == null
                || client.player != null && client.player.isOnGround()
                && operation.functionalMiningEntrance.equals(
                        baritone.getPlayerContext().playerFeet());
    }

    /** A stale breadcrumb is not proof that Baritone cannot find a real exit. */
    private Status beginNativeMiningReturn(ActiveOperation operation, long nowMillis, String reason) {
        stopFunctionalWaypoint();
        functionalRoutes.cancelReplay("recalculate live access to the original mine entrance");
        operation.functionalRouteReplay = false;
        operation.functionalNativeReturn = true;
        operation.functionalMiningReturning = true;
        operation.resetMiningReturnProgressBaseline = true;
        operation.nativeReturnDeadline = nowMillis + 60_000;
        operation.lastEvent = null;
        lastPathEvent.set(null);
        operation.traversalWatchdog.reset();
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(operation.functionalMiningEntrance));
        operation.waitingDetail = "recorded exit changed; Baritone is finding another way to the mine entrance: " + reason;
        logger.info(operation.waitingDetail);
        return pollNativeMiningReturn(operation, nowMillis);
    }

    private Status pollNativeMiningReturn(ActiveOperation operation, long nowMillis) {
        if (!operation.functionalNativeReturn) return null;
        refreshBreakSafety(false);
        BlockPos entrance = operation.functionalMiningEntrance;
        double remaining = Math.sqrt(baritone.getPlayerContext().playerFeet().getSquaredDistance(entrance));
        if (remaining == 0 && client.player.isOnGround()) {
            stopFunctionalWaypoint();
            operation.functionalNativeReturn = false;
            operation.functionalMiningReturning = false;
            operation.functionalMiningReturnComplete = true;
            operation.progressExpected = false;
            return completeStatus(operation, "returned to the original mine entrance by a freshly calculated route", 0);
        }
        if (operation.blockedDetail != null || nowMillis >= operation.nativeReturnDeadline
                || operation.lastEvent == PathEvent.CALC_FAILED
                && baritone.getPathingBehavior().getCurrent() == null
                && baritone.getPathingBehavior().getInProgress().isEmpty()) {
            operation.functionalNativeReturn = false;
            operation.functionalMiningReturning = false;
            operation.functionalMiningReturnBlocked = true;
            operation.blockedDetail = "Baritone could not find or complete an alternative exit to the original entrance";
            cancelProcesses();
            return status(operation, State.BLOCKED, operation.blockedDetail, remaining,
                    BaritonePort.FailureCause.TARGET_ROUTE_EXHAUSTED);
        }
        operation.progressExpected = true;
        // Returning is ordinary native navigation, not a bypass around route recovery.
        // Keep the original entrance, cargo and deadline while servicing the same bounded
        // detour/collision/watchdog paths used by a foreground goto.
        Status alternateRoute = pollAlternateRoute(operation, nowMillis);
        if (alternateRoute != null) return alternateRoute;
        Status localRecovery = pollLocalCollisionRecovery(operation, nowMillis);
        if (localRecovery != null) return localRecovery;
        Status traversalFailure = pollTraversalWatchdog(operation, nowMillis);
        if (traversalFailure != null) return traversalFailure;
        return status(operation, State.EXECUTING,
                "returning to the original mine entrance by live Baritone pathfinding", remaining);
    }

    private Status pollFunctionalRoute(ActiveOperation operation, long nowMillis) {
        if (operation == null || !operation.functionalRouteReplay) return null;
        MinecraftFunctionalRoutes.TickResult route = functionalRoutes.tick(nowMillis);
        return switch (route.action()) {
            case ISSUE_WAYPOINT -> {
                BlockPos target = route.waypoint();
                if (route.purpose() == MinecraftFunctionalRoutes.Purpose.MINING_RETURN) {
                    tryLightFunctionalMineAccess(operation);
                }
                // Retarget the same CustomGoalProcess in place. Clearing its path/input between
                // adjacent cells strands a freshly arrived player at a stair lip before
                // MovementDescend can take ownership of the next edge.
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(target));
                operation.progressExpected = true;
                operation.waitingDetail = "replaying known usable route toward "
                        + target.getX() + ' ' + target.getY() + ' ' + target.getZ();
                yield status(operation, State.EXECUTING,
                        operation.waitingDetail, distanceRemaining());
            }
            case WAITING_FOR_ARRIVAL -> {
                operation.progressExpected = true;
                operation.waitingDetail = route.detail();
                yield status(operation, State.EXECUTING,
                        operation.waitingDetail, distanceRemaining());
            }
            case COMPLETE -> {
                stopFunctionalWaypoint();
                operation.functionalRouteReplay = false;
                operation.lastEvent = null;
                lastPathEvent.set(null);
                if (operation.functionalMiningReturning
                        || route.purpose() == MinecraftFunctionalRoutes.Purpose.MINING_RETURN) {
                    if (!atFunctionalMiningEntrance(operation)) {
                        yield beginNativeMiningReturn(operation, nowMillis,
                                "the replay reached a retry endpoint, not the original mining entrance");
                    }
                    operation.functionalMiningReturning = false;
                    operation.functionalMiningReturnComplete = true;
                    operation.progressExpected = false;
                    operation.waitingDetail = "returned through the proven mine route";
                    yield completeStatus(
                            operation,
                            operation.waitingDetail,
                            0);
                } else if (isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))) {
                    startMiningProcess(operation);
                } else {
                    startGoto(operation.goal.arguments(), false);
                }
                operation.progressExpected = true;
                operation.waitingDetail = "known route replay completed; "
                        + "continuing with ordinary live pathfinding";
                yield status(operation, State.EXECUTING,
                        operation.waitingDetail, distanceRemaining());
            }
            case FALLBACK -> {
                stopFunctionalWaypoint();
                operation.functionalRouteReplay = false;
                operation.lastEvent = null;
                lastPathEvent.set(null);
                if (operation.goal.arguments().containsKey("idleUndergroundMaxY")
                        && !operation.functionalMiningReturning) {
                    cancelProcesses();
                    operation.blockedDetail = "Known automatic mine access changed: " + route.detail();
                    operation.progressExpected = false;
                    yield status(operation, State.BLOCKED, operation.blockedDetail, Double.NaN);
                }
                if (operation.functionalMiningReturning
                        || route.purpose() == MinecraftFunctionalRoutes.Purpose.MINING_RETURN) {
                    if (operation.functionalMiningEntrance != null) {
                        yield beginNativeMiningReturn(operation, nowMillis, route.detail());
                    }
                    operation.functionalMiningReturning = false;
                    operation.functionalMiningReturnBlocked = true;
                    operation.progressExpected = false;
                    operation.blockedDetail = "known mine exit changed or became unsafe: "
                            + route.detail();
                    operation.waitingDetail = operation.blockedDetail;
                    yield status(
                            operation,
                            State.BLOCKED,
                            operation.blockedDetail,
                            distanceRemaining(),
                            BaritonePort.FailureCause.TARGET_ROUTE_EXHAUSTED);
                }
                if (isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))) {
                    startMiningProcess(operation);
                } else {
                    startGoto(operation.goal.arguments(), false);
                }
                operation.progressExpected = true;
                operation.waitingDetail = "known route changed or became unavailable; "
                        + "continuing with ordinary live pathfinding";
                yield status(operation, State.EXECUTING,
                        operation.waitingDetail, distanceRemaining());
            }
            case NONE -> {
                operation.functionalRouteReplay = false;
                if (operation.functionalMiningReturning) {
                    operation.functionalMiningReturning = false;
                    operation.functionalMiningReturnBlocked = true;
                    operation.progressExpected = false;
                    operation.blockedDetail = "mine return replay ended before the entrance was reached";
                    yield status(
                            operation,
                            State.BLOCKED,
                            operation.blockedDetail,
                            distanceRemaining(),
                            BaritonePort.FailureCause.TARGET_ROUTE_EXHAUSTED);
                }
                if (isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))) {
                    startMiningProcess(operation);
                } else {
                    startGoto(operation.goal.arguments(), false);
                }
                yield status(operation, State.EXECUTING,
                        "functional replay ended; continuing with live pathfinding",
                        distanceRemaining());
            }
        };
    }

    /**
     * Places ordinary wall torches while walking a freshly proved mine route back out.
     *
     * <p>The interaction is deliberately opportunistic: mining remains useful when Entity has
     * no torches, while a carried hotbar torch turns the already recorded access into a lit,
     * reusable route.  Placement does not rotate the camera or replace Baritone movement, and
     * the final interaction mixin still applies exact protected-property authority.</p>
     */
    private void tryLightFunctionalMineAccess(ActiveOperation operation) {
        if (operation == null || client.player == null || client.world == null
                || client.interactionManager == null || !client.player.isOnGround()) return;

        BlockPos body = client.player.getBlockPos().toImmutable();
        if (!operation.functionalMiningLightAttempts.add(body)) return;
        BlockState bodyState = client.world.getBlockState(body);
        if (bodyState.isOf(Blocks.TORCH) || bodyState.isOf(Blocks.WALL_TORCH)) {
            operation.lastFunctionalMiningLight = body;
            return;
        }
        if (!bodyState.isAir()
                || !bodyState.getFluidState().isEmpty()
                || client.world.isSkyVisible(body)
                || client.world.getLightLevel(LightType.BLOCK, body)
                        > FUNCTIONAL_MINE_LIGHT_THRESHOLD
                || tooCloseToFunctionalMineLight(operation.lastFunctionalMiningLight, body)) {
            return;
        }

        int torchSlot = -1;
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (client.player.getInventory().getStack(slot).isOf(Items.TORCH)) {
                torchSlot = slot;
                break;
            }
        }
        if (torchSlot < 0) return;

        for (Direction direction : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos support = body.offset(direction);
            Direction face = direction.getOpposite();
            BlockState supportState = client.world.getBlockState(support);
            if (!supportState.getFluidState().isEmpty()
                    || !supportState.isSideSolidFullSquare(client.world, support, face)) continue;

            Vec3d hitPosition = new Vec3d(
                    support.getX() + 0.5 + face.getOffsetX() * 0.5,
                    support.getY() + 0.5 + face.getOffsetY() * 0.5,
                    support.getZ() + 0.5 + face.getOffsetZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitPosition, face, support, false);
            int previousSlot = client.player.getInventory().getSelectedSlot();
            ActionResult result;
            try {
                selectHotbarSlotNow(torchSlot);
                result = client.interactionManager.interactBlock(
                        client.player, Hand.MAIN_HAND, hit);
                if (result.isAccepted()) client.player.swingHand(Hand.MAIN_HAND);
            } finally {
                selectHotbarSlotNow(previousSlot);
            }
            if (result.isAccepted()) {
                operation.lastFunctionalMiningLight = body;
                return;
            }
        }
    }

    private static boolean tooCloseToFunctionalMineLight(BlockPos previous, BlockPos current) {
        if (previous == null) return false;
        int distance = Math.abs(previous.getX() - current.getX())
                + Math.abs(previous.getY() - current.getY())
                + Math.abs(previous.getZ() - current.getZ());
        return distance < FUNCTIONAL_MINE_LIGHT_SPACING;
    }

    private void stopFunctionalWaypoint() {
        try {
            baritone.getCustomGoalProcess().setGoal(null);
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getPathingBehavior().cancelEverything();
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public synchronized void cancel(long invalidatedEpoch, String reason) {
        if (active != null) {
            logger.info("Pausing Baritone mission {} at control epoch {}: {}",
                    active.goal.missionId(), invalidatedEpoch, reason);
        }
        suspendedAquaticRoute = null;
        cancelProcesses();
        cancelAquaticFrame(MovementFrameActuator.Cleanup.CANCEL);
        active = null;
        releaseDoorRouteOwnership(System.currentTimeMillis());
    }

    @Override
    public synchronized void finishFunctionalMiningRoute(long nowMillis) {
        functionalRoutes.finishMiningRecording(nowMillis);
    }

    /**
     * Credits the physically occupied safe cell of an active mining route.
     *
     * <p>The mission executor polls at a deliberately bounded cadence and a sprinting player can
     * cross two block cells between those polls. Route recording therefore also calls this method
     * from the real client-tick loop. Each durable credit still comes only from the current loaded,
     * grounded Minecraft body cell; no path-plan coordinate is inferred or pre-credited.</p>
     */
    public synchronized void observeFunctionalMiningRoute(long nowMillis) {
        ActiveOperation operation = active;
        if (operation == null || !isMiningKind(
                operation.goal.kind().toLowerCase(Locale.ROOT))) return;
        ResourceActuationSession resource = operation.resourceSession;
        if (resource != null && resource.sourceKind()
                != ResourceActuationSession.SourceKind.MINE) return;
        functionalRoutes.observeMining(functionalRouteObjective(operation), nowMillis);
    }

    /**
     * Releases Baritone for the drowning reflex while retaining the exact world-space aquatic
     * destination.  The next start of the same logical operation consumes this one-shot snapshot
     * and deliberately re-enters the submerged travel state after breathing.
     */
    public synchronized void suspendForAir(long invalidatedEpoch, String reason) {
        ActiveOperation operation = active;
        long now = System.currentTimeMillis();
        AquaticRoute route = operation == null ? null : currentAquaticRoute(operation, now);
        if (operation != null && route != null) {
            suspendedAquaticRoute = new SuspendedAquaticRoute(
                    operation.goal,
                    route.destinationX(),
                    route.destinationY(),
                    route.destinationZ(),
                    route.destinationDry(),
                    route.directPursuit(),
                    currentDimension(),
                    now + SUSPENDED_AQUATIC_ROUTE_MILLIS);
            logger.info("Suspending aquatic route {} for air without forgetting its destination: {}",
                    operation.goal.missionId(), reason);
        } else {
            suspendedAquaticRoute = null;
        }
        cancelProcesses();
        cancelAquaticFrame(MovementFrameActuator.Cleanup.OWNER_TRANSITION);
        active = null;
        releaseDoorRouteOwnership(System.currentTimeMillis());
    }

    /**
     * Hard actuator boundary used before a non-Baritone owner touches movement,
     * attack, use, or inventory channels.
     */
    public synchronized void neutralizeActuators(String reason) {
        boolean activeOperationPresent = active != null;
        if (active != null) {
            logger.debug("Neutralizing Baritone actuator owner {}: {}",
                    active.goal.missionId(), Objects.requireNonNullElse(reason, "owner handoff"));
        }
        if (!AquaticRouteCustodyPolicy.retainSuspendedSafetyRoute(
                activeOperationPresent, suspendedAquaticRoute != null)) {
            suspendedAquaticRoute = null;
        }
        cancelProcesses();
        cancelAquaticFrame(MovementFrameActuator.Cleanup.OWNER_TRANSITION);
        active = null;
        releaseDoorRouteOwnership(System.currentTimeMillis());
    }

    @Override
    public synchronized boolean cancelIfOwned(
            String operationId,
            long controlEpoch,
            String reason) {
        String expectedOperation = Objects.requireNonNull(operationId, "operationId").trim();
        if (expectedOperation.isEmpty()) {
            throw new IllegalArgumentException("operationId cannot be blank");
        }
        ActiveOperation operation = active;
        if (operation == null
                || !operation.goal.missionId().equals(expectedOperation)
                || operation.controlEpoch != controlEpoch) {
            return false;
        }
        logger.info("Cancelling owned Baritone mission {} at control epoch {}: {}",
                expectedOperation,
                controlEpoch,
                Objects.requireNonNullElse(reason, "owned Baritone operation cancelled"));
        cancelProcesses();
        cancelAquaticFrame(MovementFrameActuator.Cleanup.CANCEL);
        active = null;
        releaseDoorRouteOwnership(System.currentTimeMillis());
        return true;
    }

    private void releaseDoorRouteOwnership(long nowMillis) {
        DoorPassageSession.Intent intent = doorPassage.snapshot().intent();
        if (intent == null) {
            actuators.setPortalPassageFence(false);
            return;
        }
        try {
            doorPassage.releaseRoute(nowMillis);
        } catch (IOException | RuntimeException error) {
            logger.error("Could not checkpoint door passage during owner release", error);
        }
        actuators.setPortalPassageFence(doorPassage.snapshot().intent() != null);
    }

    public synchronized Snapshot snapshot() {
        boolean ready = playerContextReady();
        AquaticTravelPolicy.Snapshot aquatic = active == null
                ? new AquaticTravelPolicy.Session().snapshot()
                : active.aquaticTravel.snapshot();
        MovementFrameActuator.Observation sampled = movement.snapshot().observation();
        CombatAttackJournal.Snapshot attacks = combatAttacks.snapshot();
        RouteSupportCustody.Snapshot supports = routeSupportCustody.snapshot();
        DoorPassageSession.Snapshot passage = doorPassage.snapshot();
        DoorPassageSession.Intent passageIntent = passage.intent();
        MinecraftHomeInteriorObserver.Observation interior =
                homeInteriors.cachedObservation();
        MinecraftHomeInteriorObserver.Summary interiorSummary =
                interior.summary().orElse(null);
        var interiorAnchor = interior.homeAnchor();
        MinecraftFunctionalRoutes.Snapshot routes = functionalRoutes.snapshot();
        MinecraftResourceDescentGuard.Snapshot descent = resourceDescentGuard.snapshot();
        boolean currentObservedAquatic = active != null
                && AquaticRouteCustodyPolicy.hasCurrentObservedDirectSwim(
                exactAquaticFrameObserved(active),
                active.aquaticObservedMotion,
                aquatic.ownsMovement(),
                aquatic.swimming(),
                aquatic.observedSwimTicks());
        return new Snapshot(
                active == null ? "" : active.goal.missionId(),
                active == null ? "idle" : active.goal.kind(),
                ready && baritone.getPathingBehavior().isPathing(),
                lastPathEvent.get() == null ? "" : lastPathEvent.get().name(),
                ready ? distanceRemaining() : Double.NaN,
                active == null
                        ? AquaticRouteCustodyPolicy.MovementOwner.BARITONE.name().toLowerCase(Locale.ROOT)
                        : active.aquaticPursuitCustody.owner().name().toLowerCase(Locale.ROOT),
                aquatic.mode().name().toLowerCase(Locale.ROOT),
                currentObservedAquatic,
                aquatic.modeTicks(),
                aquatic.stalledTicks(),
                aquatic.recoveryAttempts(),
                aquatic.observedSwimTicks(),
                aquatic.maximumObservedHorizontalSpeed(),
                aquatic.reason(),
                aquatic.headSubmerged(),
                aquatic.swimming(),
                aquatic.air(),
                aquatic.maximumAir(),
                aquatic.horizontalSpeed(),
                aquatic.surfaceTransitions(),
                aquatic.rediveTransitions(),
                aquatic.shoreTransitions(),
                aquatic.sprintEligible(),
                sampled.exactMatch(),
                sampled.detail(),
                active == null ? 0L : active.generation,
                active == null ? 0L : active.startedAt,
                active == null ? 0L : active.completedWorkUnits,
                active == null ? 0 : active.miningProcessStarts,
                active == null ? 0 : active.miningProcessRestarts,
                currentBreakingTarget(),
                currentRouteSignature(),
                supports.tracked(),
                supports.protectedCount(),
                supports.dependentCount(),
                supports.placements(),
                supports.maximumBodyRise(),
                supports.safeReleases(),
                supports.deniedBreaks(),
                supports.placementRollbacks(),
                supports.prematureRemovals(),
                supports.capacityTrips(),
                supports.protectedCoordinates(),
                passage.revision(),
                passageIntent == null ? "idle"
                        : passageIntent.phase().name().toLowerCase(Locale.ROOT),
                passageIntent == null ? ""
                        : passageIntent.dimension() + ' ' + passageIntent.x() + ' '
                        + passageIntent.y() + ' ' + passageIntent.z(),
                passageIntent != null && passageIntent.restorationDue(),
                interior.revision(),
                interiorSummary == null
                        ? interior.availability().name().toLowerCase(Locale.ROOT)
                        : interiorSummary.outcome().name().toLowerCase(Locale.ROOT),
                interior.expectedDimension(),
                interiorAnchor == null ? 0 : interiorAnchor.x(),
                interiorAnchor == null ? 0 : interiorAnchor.y(),
                interiorAnchor == null ? 0 : interiorAnchor.z(),
                interiorSummary == null ? 0 : interiorSummary.sampledBlocks(),
                interiorSummary == null ? 0 : interiorSummary.interiorRooms(),
                interiorSummary == null ? 0 : interiorSummary.roomToRoomPortals(),
                interiorSummary == null ? 0 : interiorSummary.roomToExteriorPortals(),
                interiorSummary == null ? "unknown"
                        : interiorSummary.homeRegionKind()
                        .map(value -> value.name().toLowerCase(Locale.ROOT))
                        .orElse("unknown"),
                routes.revision(),
                routes.routeCount(),
                routes.recordedCells(),
                routes.activeRouteId(),
                routes.replayPurpose(),
                routes.replayStatus(),
                routes.replayEdgesIssued(),
                routes.replayEdgesAccepted(),
                routes.replayRevalidations(),
                routes.replayFallbacks(),
                routes.lastDetail(),
                descent.inspections(),
                descent.authorized(),
                descent.vetoes(),
                descent.verticalShaftVetoes(),
                descent.maximumObservedVerticalLoss(),
                descent.lastState(),
                descent.lastRejection(),
                descent.lastRoute(),
                descent.lastDetail(),
                active == null ? Set.of() : active.protectedThrowawayItems,
                attacks.sequence(),
                attacks.events());
    }

    public synchronized boolean isBlueprintConstructionBlock(String dimension,BlockPos position,BlockState observed) {
        return blueprintBuildScope != null && blueprintBuildScope.ownsPlacedBlock(dimension,position,observed);
    }

    /** Retains one exact air-to-solid route-support transition across owner preemption. */
    public synchronized RouteSupportCustody.PlacementResult observeRouteSupportPlaced(
            String dimension,
            int x,
            int y,
            int z,
            String operationId,
            long clientTick) {
        double bodyY = client.player == null ? y + 1.0 : client.player.getY();
        RouteSupportCustody.PlacementResult result = routeSupportCustody.placed(
                new RouteSupportCustody.Coordinate(dimension, x, y, z),
                operationId, clientTick, bodyY);
        return result;
    }

    /** Advances custody only from a real grounded collision observation. */
    public synchronized RouteSupportCustody.BodyResult observeRouteSupportBody(long clientTick) {
        var player = client.player;
        String dimension = currentDimension();
        boolean onGround = player != null && client.world != null && player.isOnGround();
        Set<RouteSupportCustody.Coordinate> stableContacts = onGround
                ? stableBodySupportContacts() : Set.of();
        RouteSupportCustody.BodyResult result = player == null || client.world == null
                ? routeSupportCustody.observeBody(
                        dimension, false, Set.of(), clientTick, 0.0)
                : routeSupportCustody.observeBody(
                        dimension, onGround, stableContacts,
                        clientTick, player.getY());
        completeRouteSupportRecoveryIfGrounded(
                dimension, onGround, stableContacts, result, clientTick);
        return result;
    }

    /** Observes a solid-to-air transition and reports a bypass of the pre-break gate. */
    public synchronized RouteSupportCustody.RemovalResult observeRouteSupportRemoved(
            String dimension,
            int x,
            int y,
            int z,
            long clientTick) {
        RouteSupportCustody.RemovalResult result = routeSupportCustody.removed(
                new RouteSupportCustody.Coordinate(dimension, x, y, z), clientTick);
        if (result.recoverable()) {
            beginRouteSupportRecovery(result, clientTick);
        } else if (result.premature()) {
            routeSupportRecovery = null;
        }
        return result;
    }

    /**
     * Stops optimistic pillar motion when the server rolls back a newly placed
     * support. The exact dependent block below remains protected as the catch;
     * no route process may restart until vanilla reports a real grounded contact.
     */
    private void beginRouteSupportRecovery(
            RouteSupportCustody.RemovalResult result,
            long clientTick) {
        RouteSupportCustody.Coordinate catchCoordinate = result.catchCoordinate();
        if (catchCoordinate == null) {
            throw new IllegalStateException("recoverable route-support rollback requires an exact catch");
        }
        routeSupportRecovery = new RouteSupportRecovery(
                result.coordinate(),
                catchCoordinate,
                result.operationId(),
                result.recoveryAttempt(),
                result.maximumRecoveryAttempts(),
                clientTick);
        try {
            if (client.interactionManager != null) {
                client.interactionManager.cancelBlockBreaking();
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getPathingBehavior().cancelEverything();
        } catch (RuntimeException ignored) {
        }
        releaseRouteMomentum(active);
        if (active != null) {
            active.progressExpected = false;
            active.waitingDetail = result.detail();
            active.traversalWatchdog.reset();
        }
        logger.warn("{}", result.detail());
    }

    private void completeRouteSupportRecoveryIfGrounded(
            String dimension,
            boolean onGround,
            Set<RouteSupportCustody.Coordinate> stableContacts,
            RouteSupportCustody.BodyResult body,
            long clientTick) {
        RouteSupportRecovery recovery = routeSupportRecovery;
        if (recovery == null || !onGround || stableContacts.isEmpty()
                || !recovery.catchCoordinate().dimension().equals(dimension)) {
            return;
        }
        boolean caughtByRetainedSupport = stableContacts.contains(recovery.catchCoordinate());
        boolean reachedIndependentFooting = body.becameIndependent()
                .contains(recovery.catchCoordinate());
        if (!caughtByRetainedSupport && !reachedIndependentFooting) return;

        routeSupportRecovery = null;
        ActiveOperation operation = active;
        if (operation == null) return;
        operation.traversalWatchdog.reset();
        operation.progressExpected = true;
        operation.waitingDetail = "landed safely after authoritative route-support rollback "
                + recovery.attempt() + '/' + recovery.maximumAttempts()
                + "; recommitting the current goal";
        recommitAfterRouteSupportRecovery(operation);
        logger.info("Recovered route-support rollback at client tick {} on exact catch {}; recommitted {}",
                clientTick, recovery.catchCoordinate(), operation.goal.missionId());
    }

    private boolean holdForRouteSupportRecovery(ActiveOperation operation) {
        RouteSupportRecovery recovery = routeSupportRecovery;
        if (operation == null || recovery == null) return false;
        operation.progressExpected = false;
        operation.waitingDetail = "waiting to land on retained route support "
                + recovery.catchCoordinate() + " after authoritative placement rollback "
                + recovery.attempt() + '/' + recovery.maximumAttempts();
        return true;
    }

    private void recommitAfterRouteSupportRecovery(ActiveOperation operation) {
        var phase = BaritoneActuatorScopePolicy.resumePhase(
                operation.functionalMiningReturning, operation.functionalNativeReturn);
        if (phase != BaritoneActuatorScopePolicy.ResumePhase.ORIGINAL_OPERATION) {
            if (operation.functionalMiningEntrance == null) {
                operation.functionalNativeReturn = false;
                operation.functionalMiningReturning = false;
                operation.functionalMiningReturnBlocked = true;
                operation.blockedDetail = "cannot resume mining return without its original entrance";
                return;
            }
            if (phase == BaritoneActuatorScopePolicy.ResumePhase.RESUME_MINING_RETURN) {
                // Door opening/restoration cancels the native exit goal. Restore that exact
                // phase without restarting MineProcess or renewing its bounded return budget.
                baritone.getMineProcess().cancel();
                baritone.getCustomGoalProcess().setGoalAndPath(
                        new GoalBlock(operation.functionalMiningEntrance));
            } else {
                // A canceled breadcrumb waypoint may already be waiting for arrival. Use the
                // existing native exit fallback instead of silently restarting resource work.
                beginNativeMiningReturn(operation, System.currentTimeMillis(),
                        "resuming the original-entrance return after an actuator handoff");
            }
            return;
        }
        String kind = operation.goal.kind().toLowerCase(Locale.ROOT);
        Map<String, String> args = operation.goal.arguments();
        switch (kind) {
            case "build" -> startBlueprint(operation);
            case "goto", "go" -> startGoto(args);
            case "protection_escape" -> startProtectionEscapeGoal(operation);
            case "mine", "acquire", "fetch", "get" -> {
                if (operation.miningPlan == null) startMine(args);
                else if (operation.resourceSession != null
                        && operation.resourceSession.target().isPresent()) {
                    ResourceActuationSession.Target target =
                            operation.resourceSession.target().orElseThrow();
                    installResourceBreakScope(operation, target);
                    if (target.kind()
                            == ResourceActuationSession.TargetKind.NATURAL_LOG) {
                        startCommittedNaturalTree(operation, target);
                    } else if (target.kind() == ResourceActuationSession.TargetKind.FLOWER) {
                        startNativeResourceTarget(operation, target.coordinate(), "flower");
                    } else {
                        routeToResourceTarget(operation, target);
                    }
                }
                else if (!baritone.getMineProcess().isActive()) startMiningProcess(operation);
            }
            case "come" -> {
                if (operation.targetPredicate == null) {
                    operation.completionRange = Math.max(
                            1.0, parseDouble(args.get("range"), 3.0));
                    startFollow(
                            required(args, "player"),
                            DeliveryPolicy.followRadius(operation.completionRange));
                } else {
                    BaritoneAPI.getSettings().followRadius.value = operation.followRadius;
                    baritone.getFollowProcess().follow(operation.targetPredicate);
                }
            }
            case "follow" -> {
                if (operation.targetPredicate == null) startFollow(required(args, "player"));
                else {
                    BaritoneAPI.getSettings().followRadius.value = operation.followRadius;
                    baritone.getFollowProcess().follow(operation.targetPredicate);
                }
            }
            case "guard", "protect" -> {
                if (operation.targetPredicate != null) {
                    BaritoneAPI.getSettings().followRadius.value = operation.followRadius;
                    baritone.getFollowProcess().follow(operation.targetPredicate);
                } else {
                    String player = args.getOrDefault("player", args.getOrDefault("owner", ""));
                    if (!player.isBlank()) startFollow(player);
                    else operation.blockedDetail =
                            "guard requires a player until the tactical combat module is enabled";
                }
            }
            case "attack", "hunt" -> {
                if (operation.attackTarget != null
                        && operation.attackTarget.isAlive()
                        && !operation.attackTarget.isRemoved()) {
                    pinAttackTarget(operation, operation.attackTarget);
                } else if (operation.boundedHunt) {
                    // A door suspends native actuation, not the hunt's current phase.
                    // Targetless scouting owns a coordinate goal; FollowProcess(predicate)
                    // has nothing to follow and leaves its retained frontier falsely inactive.
                    LandHuntFrontierPolicy.Candidate frontier = operation.huntFrontiers.current();
                    if (frontier != null) {
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(
                                new BlockPos(frontier.x(), frontier.y(), frontier.z()), 4));
                    } else {
                        // Preserve an existing no-frontier wait and its retry time too.
                        operation.progressExpected = false;
                        maintainHuntExploration(operation, System.currentTimeMillis());
                    }
                } else if (operation.targetPredicate != null) {
                    baritone.getFollowProcess().follow(operation.targetPredicate);
                } else startAttack(args);
            }
            default -> operation.blockedDetail =
                    "cannot resume unsupported operation after route-support rollback: " + kind;
        }
    }

    /**
     * Final main-thread mutation fence for direct controllers which do not
     * reach {@link #poll(String)}.  A normal Baritone break is stopped earlier
     * by {@link #guardUnsafeBlockBreak(ActiveOperation)}.
     */
    public synchronized Optional<RouteSupportCustody.BreakDecision>
            enforceProtectedBreakGuardAndObserveSupportIntent() {
        Optional<MinecraftActuatorGateway.BlockedBreakAttempt> attempted =
                actuators.takeBlockedBreakAttempt();
        if (attempted.isPresent()) {
            MinecraftActuatorGateway.BlockedBreakAttempt blocked = attempted.get();
            RouteSupportCustody.Coordinate coordinate = new RouteSupportCustody.Coordinate(
                    blocked.dimension(), blocked.x(), blocked.y(), blocked.z());
            stopProtectedSupportBreak(blocked.detail());
            if (active != null) {
                active.progressExpected = false;
                active.blockedDetail = blocked.detail();
                active.waitingDetail = blocked.detail();
            }
            return Optional.of(new RouteSupportCustody.BreakDecision(
                    RouteSupportCustody.Action.BLOCK, coordinate, blocked.detail()));
        }
        if (client.interactionManager == null
                || !client.interactionManager.isBreakingBlock()
                || !(client.crosshairTarget instanceof BlockHitResult hit)) {
            return Optional.empty();
        }
        RouteSupportCustody.Coordinate coordinate = routeSupportCoordinate(hit.getBlockPos());
        if (!routeSupportCustody.protects(coordinate)) return Optional.empty();
        routeSupportCustody.beforeBreak(coordinate);
        return Optional.empty();
    }

    private DoorPassageOutcome maintainDoorPassage(
            ActiveOperation operation,
            long nowMillis) {
        try {
            // Arrival ends crossing, not ownership of its unverified close debt.
            // Keep the live operation so the existing native body clearance can run.
            if (operation != null && (operation.pendingCompletionDetail != null
                    || operation.blueprintPortalSettling)) {
                doorPassage.releaseRoute(nowMillis);
            }
            return maintainDoorPassageChecked(operation, nowMillis);
        } catch (IOException | RuntimeException error) {
            String detail = "door passage state could not be advanced safely: "
                    + Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
            if (operation != null) {
                operation.progressExpected = false;
                operation.blockedDetail = detail;
                operation.waitingDetail = detail;
            }
            actuators.setPortalPassageFence(true);
            logger.error(detail, error);
            return new DoorPassageOutcome(true, true, detail);
        }
    }

    private DoorPassageOutcome maintainDoorPassageChecked(
            ActiveOperation operation,
            long nowMillis) throws IOException {
        DoorPassageSession.Snapshot snapshot = doorPassage.snapshot();
        DoorPassageSession.Intent intent = snapshot.intent();
        if (intent == null && operation != null && operation.blueprintPortalSettling) {
            // The approach has ended. A stale native click is not a new crossing
            // while construction owns the final use/acknowledgement of this portal.
            actuators.takePortalInteractionAttempt();
            actuators.setPortalPassageFence(true);
            return null;
        }
        if(intent==null) observeNativeDoorEntry(operation,nowMillis);
        Optional<MinecraftActuatorGateway.PortalInteractionAttempt> observed =
                actuators.takePortalInteractionAttempt();
        if (intent == null && observed.isPresent()) {
            MinecraftActuatorGateway.PortalInteractionAttempt portal = observed.orElseThrow();
            if (portal.openBefore() || operation == null) {
                return null;
            }
            double signedDistance = (portal.playerX() - (portal.x() + 0.5D)) * portal.normalX()
                    + (portal.playerY() - (portal.y() + 0.5D)) * portal.normalY()
                    + (portal.playerZ() - (portal.z() + 0.5D)) * portal.normalZ();
            int approachSign = signedDistance < 0.0D ? -1 : 1;
            String transactionId = UUID.randomUUID().toString();
            intent = new DoorPassageSession.Intent(
                    transactionId,
                    operation.goal.missionId(),
                    portal.dimension(),
                    portal.x(), portal.y(), portal.z(),
                    portal.kind(), portal.blockId(), portal.stableFingerprint(),
                    portal.normalX(), portal.normalY(), portal.normalZ(),
                    portal.height(), approachSign,
                    false,
                    DoorPassageSession.Phase.WAITING_OPEN_AUTHORITY,
                    0, 0, 0L, nowMillis, "");
            doorPassage.begin(intent, nowMillis);
            pauseForDoorPassage(operation,
                    "persisted a reversible " + portal.kind() + " passage before opening it");
            snapshot = doorPassage.snapshot();
        }
        if (intent == null) {
            actuators.setPortalPassageFence(operation != null);
            return null;
        }

        // A prior command (including the same mission's explicit retry) must not
        // inherit a terminal open failure. This also repairs alpha.11 persisted
        // failures before an unloaded/changed old door can fence the new route.
        boolean replacementRoute = operation != null
                && (!operation.goal.missionId().equals(intent.missionId())
                || operation.startedAt >= snapshot.updatedAtMillis());
        if (doorPassage.resolveBlockedOpening(false, replacementRoute, nowMillis)) {
            if (operation != null && Objects.equals(operation.blockedDetail, intent.detail())) {
                operation.blockedDetail = null;
            }
            actuators.setPortalPassageFence(operation != null);
            return null;
        }

        actuators.setPortalPassageFence(true);
        if (client.player == null || client.world == null) {
            return holdDoor(operation, "waiting for the world before resolving door passage debt");
        }
        if (!currentDimension().equals(intent.dimension())) {
            return holdDoor(operation,
                    "door passage debt remains in " + intent.dimension()
                            + " while Entity is in " + currentDimension());
        }
        BlockPos position = new BlockPos(intent.x(), intent.y(), intent.z());
        // ClientWorld's inherited convenience overload is not a loaded-cache
        // observation. An arriving chunk's air must not poison saved door debt.
        if (!client.world.getChunkManager().isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
            return holdDoor(operation, "waiting for the saved door cell to be loaded");
        }
        MinecraftActuatorGateway.PortalState portal =
                actuators.inspectPortal(position).orElse(null);
        if (portal == null || !matchesPortal(intent, portal)) {
            String detail = "saved door passage no longer matches the loaded portal at "
                    + intent.x() + " " + intent.y() + " " + intent.z()
                    + "; expected=" + intent.stableFingerprint()
                    + "; observed=" + (portal == null ? client.world.getBlockState(position)
                    : portal.stableFingerprint() + "; normal=" + portal.normalX() + ',' + portal.normalY()
                    + ',' + portal.normalZ() + "; height=" + portal.height());
            doorPassage.block(intent.id(), detail, nowMillis);
            return blockDoor(operation, detail);
        }

        return switch (intent.phase()) {
            case WAITING_OPEN_AUTHORITY -> {
                if (portal.open()) {
                    if (intent.openAttempts() == 0) {
                        doorPassage.complete(intent.id(), nowMillis);
                        resumeAfterDoorPassage(operation,
                                "another actor opened the door; Entity left its state unchanged");
                        yield null;
                    }
                    doorPassage.opened(intent.id(), nowMillis);
                    resumeAfterDoorPassage(operation, "door opened; crossing the exact portal");
                    yield null;
                }
                if (operation == null
                        || !operation.goal.missionId().equals(intent.missionId())) {
                    doorPassage.complete(intent.id(), nowMillis);
                    actuators.setPortalPassageFence(operation != null);
                    yield null;
                }
                if (nowMillis - snapshot.updatedAtMillis() > PORTAL_AUTHORITY_TIMEOUT_MILLIS) {
                    String detail = "timed out waiting for exact door-open authority";
                    doorPassage.block(intent.id(), detail, nowMillis);
                    yield blockDoor(operation, detail);
                }
                ProtectedAreaClientState.HomeAuthorization authority =
                        requestDoorAuthority(intent, false, nowMillis);
                if (authority.state() == ProtectedAreaClientState.HomeAuthorizationState.WAITING) {
                    yield holdDoor(operation, authority.detail());
                }
                if (authority.state() == ProtectedAreaClientState.HomeAuthorizationState.DENIED) {
                    doorPassage.block(intent.id(), authority.detail(), nowMillis);
                    yield blockDoor(operation, authority.detail());
                }
                doorPassage.markOpenIssued(intent.id(), nowMillis);
                issueDoorToggle(intent, false);
                yield holdDoor(operation, "issued one persisted door-open interaction; verifying state");
            }
            case AWAITING_OPEN -> {
                if (portal.open()) {
                    doorPassage.opened(intent.id(), nowMillis);
                    resumeAfterDoorPassage(operation, "door opened; crossing the exact portal");
                    yield null;
                }
                if (nowMillis - intent.actionIssuedAtMillis()
                        <= DoorPassageSession.ACTION_RESULT_TIMEOUT_MILLIS) {
                    yield holdDoor(operation, "verifying the persisted door-open interaction");
                }
                if (operation == null
                        || !operation.goal.missionId().equals(intent.missionId())) {
                    doorPassage.complete(intent.id(), nowMillis);
                    actuators.setPortalPassageFence(operation != null);
                    yield null;
                }
                if (intent.openAttempts() >= DoorPassageSession.MAXIMUM_ACTION_ATTEMPTS) {
                    String detail = "door remained closed after " + intent.openAttempts()
                            + " exact interaction attempts";
                    doorPassage.block(intent.id(), detail, nowMillis);
                    yield blockDoor(operation, detail);
                }
                doorPassage.retryOpen(intent.id(), nowMillis);
                yield holdDoor(operation, "door state was unchanged; retrying within the bounded budget");
            }
            case CROSSING -> {
                if (!portal.open()) {
                    if (operation == null || !operation.goal.missionId().equals(intent.missionId())
                            || intent.crossed(client.player.getX(), client.player.getY(), client.player.getZ())) {
                        doorPassage.complete(intent.id(), nowMillis);
                        resumeAfterDoorPassage(operation, "externally closed door observed; passage settled");
                        yield null;
                    }
                    doorPassage.reopenAfterExternalClosure(intent.id(), nowMillis);
                    pauseForDoorPassage(operation, "door closed during crossing; reobserving and reopening for the same mission");
                    yield holdDoor(operation, "door closed during crossing; reobserving and reopening for the same mission");
                }
                if (operation == null
                        || !operation.goal.missionId().equals(intent.missionId())) {
                    doorPassage.abandonCrossing(intent.id(), nowMillis);
                    pauseForDoorPassage(operation, "route ended; restoring the opened door");
                    yield holdDoor(operation, "route ended; restoring the opened door");
                }
                if (intent.crossed(
                        client.player.getX(), client.player.getY(), client.player.getZ())) {
                    doorPassage.crossed(intent.id(), nowMillis);
                    pauseForDoorPassage(operation, "crossing complete; restoring the door");
                    yield holdDoor(operation, "crossing complete; restoring the door");
                }
                if (nowMillis - snapshot.updatedAtMillis()
                        > DoorPassageSession.CROSSING_TIMEOUT_MILLIS) {
                    doorPassage.abandonCrossing(intent.id(), nowMillis);
                    pauseForDoorPassage(operation, "door crossing timed out; restoring before replanning");
                    yield holdDoor(operation, "door crossing timed out; restoring before replanning");
                }
                if (operation != null) {
                    operation.progressExpected = true;
                    operation.waitingDetail = "crossing the opened " + intent.kind();
                }
                yield null;
            }
            case WAITING_RESTORE_AUTHORITY -> {
                if (operation != null && !intent.id().equals(operation.doorRestorationId)) {
                    operation.doorRestorationId = intent.id();
                    operation.doorClearanceStartedAt = 0L;
                    operation.doorClearanceRunning = false;
                    operation.doorRestoreAuthorityStartedAt = 0L;
                }
                if (!portal.open()) {
                    stopDoorClearance(operation);
                    doorPassage.complete(intent.id(), nowMillis);
                    resumeAfterDoorPassage(operation, "door restoration verified");
                    yield null;
                }
                // Idle/Stop still observes issued results, but owns no new close action
                // or permission deadline. A copied logout debt can be arbitrarily old.
                if (operation == null || !arbiter.isValid(operation.controlLease, nowMillis)) {
                    if (operation != null) operation.doorRestoreAuthorityStartedAt = 0L;
                    yield holdDoor(operation,
                            "door restoration retained; waiting for a live route owner");
                }
                // Workstation handoff may begin restoration already beyond the thin
                // center plane, but still inside the door cell. Clear that stance too.
                if (ownDoorwayCellOccupied(intent)
                        || client.player.getBoundingBox().intersects(portalClosureBounds(intent))
                        || operation.doorClearanceRunning) {
                    DoorPassageOutcome clearance = clearOwnDoorBody(operation, intent, nowMillis);
                    if (clearance != null) yield clearance;
                }
                stopDoorClearance(operation);
                if (!portalClosureClear(intent)) {
                    yield holdDoor(operation,
                            "waiting for every living body to clear the door before closing it");
                }
                // A persisted close debt may be old, or have spent time waiting for a body.
                // Start the permission deadline when this live operation can actually close.
                if (operation.doorRestoreAuthorityStartedAt == 0L) {
                    operation.doorRestoreAuthorityStartedAt = nowMillis;
                }
                long authorityStartedAt = operation.doorRestoreAuthorityStartedAt;
                if (nowMillis - authorityStartedAt > PORTAL_AUTHORITY_TIMEOUT_MILLIS) {
                    String detail = DoorPassageSession.RESTORE_AUTHORITY_TIMEOUT_DETAIL;
                    doorPassage.block(intent.id(), detail, nowMillis);
                    yield blockDoor(operation, detail);
                }
                ProtectedAreaClientState.HomeAuthorization authority =
                        requestDoorAuthority(intent, true, nowMillis);
                if (authority.state() == ProtectedAreaClientState.HomeAuthorizationState.WAITING) {
                    yield holdDoor(operation, authority.detail());
                }
                if (authority.state() == ProtectedAreaClientState.HomeAuthorizationState.DENIED) {
                    doorPassage.block(intent.id(), authority.detail(), nowMillis);
                    yield blockDoor(operation, authority.detail());
                }
                doorPassage.markRestoreIssued(intent.id(), nowMillis);
                issueDoorToggle(intent, true);
                yield holdDoor(operation, "issued one persisted door-close interaction; verifying state");
            }
            case AWAITING_RESTORE -> {
                if (!portal.open()) {
                    doorPassage.complete(intent.id(), nowMillis);
                    resumeAfterDoorPassage(operation, "door restoration verified");
                    yield null;
                }
                if (nowMillis - intent.actionIssuedAtMillis()
                        <= DoorPassageSession.ACTION_RESULT_TIMEOUT_MILLIS) {
                    yield holdDoor(operation, "verifying the persisted door-close interaction");
                }
                if (intent.restoreAttempts() >= DoorPassageSession.MAXIMUM_ACTION_ATTEMPTS) {
                    String detail = "door remained open after " + intent.restoreAttempts()
                            + " exact restoration attempts";
                    doorPassage.block(intent.id(), detail, nowMillis);
                    yield blockDoor(operation, detail);
                }
                doorPassage.retryRestore(intent.id(), nowMillis);
                yield holdDoor(operation,
                        "door remained open; retrying restoration within the bounded budget");
            }
            case BLOCKED -> {
                if (doorPassage.resolveBlockedOpening(portal.open(), false, nowMillis)) {
                    if (operation != null
                            && Objects.equals(operation.blockedDetail, intent.detail())) {
                        operation.blockedDetail = null;
                    }
                    resumeAfterDoorPassage(operation,
                            "previously blocked door is now open; resuming live routing");
                    yield null;
                }
                if (intent.restorationDue() && !portal.open()) {
                    String resolvedDetail = intent.detail();
                    doorPassage.complete(intent.id(), nowMillis);
                    if (operation != null
                            && Objects.equals(operation.blockedDetail, resolvedDetail)) {
                        operation.blockedDetail = null;
                    }
                    resumeAfterDoorPassage(operation,
                            "previously blocked door restoration is now physically verified");
                    yield null;
                }
                // Older clients aged permission waits even while idle. Re-arm only
                // that exact failure, once for this fresh route, after portal matching.
                if (operation != null && arbiter.isValid(operation.controlLease, nowMillis)
                        && !intent.id().equals(operation.doorRestorationId)
                        && doorPassage.retryTimedOutRestoration(replacementRoute, nowMillis)) {
                    operation.doorRestorationId = intent.id();
                    operation.doorClearanceStartedAt = 0L;
                    operation.doorClearanceRunning = false;
                    operation.doorRestoreAuthorityStartedAt = 0L;
                    if (Objects.equals(operation.blockedDetail, intent.detail())) {
                        operation.blockedDetail = null;
                    }
                    yield holdDoor(operation,
                            "retrying saved door-restoration permission under the fresh route owner");
                }
                yield blockDoor(operation, intent.detail());
            }
        };
    }

    /** Place and use are separate vanilla actions. Reuse native travel and the
     * existing exact portal actuator, with the saved project as completion truth. */
    private Status finishBlueprintPortal(ActiveOperation operation, long now) {
        var pending = blueprintSession.pendingPortal();
        if (operation.blueprintPortal == null && pending.isEmpty()) return null;
        if (!operation.blueprintPortalSettling && doorPassage.snapshot().intent() != null) {
            // Visibility through an open doorway is not arrival on its far side.
            // Keep the native approach alive until its crossing/restoration owner
            // finishes; canceling it here sends clearance back to the near side
            // and repeats open -> close -> approach without ever reaching the use stance.
            return status(operation, State.EXECUTING,
                    "finishing the approach passage before final blueprint portal use", distanceRemaining());
        }
        if (operation.blueprintPortal == null) {
            operation.blueprintPortal = pending.orElseThrow().getKey();
            operation.blueprintPortalWanted = pending.orElseThrow().getValue();
            operation.blueprintPortalStarted = 0;
            operation.doorClearanceStartedAt = 0;
            operation.doorClearanceRunning = false;
            pauseForDoorPassage(operation, "settling placed portal to the confirmed blueprint state");
        }
        BlockPos position = operation.blueprintPortal;
        if (!operation.blueprintPortalSettling
                && raycastResource(position, client.player.getBlockInteractionRange()) != null) {
            // Reaching a use stance ends traversal, not its saved close debt.
            // Let the passage owner settle first; otherwise its CROSSING branch
            // mistakes our final close for an external obstruction and reopens it.
            operation.blueprintPortalSettling = true;
            pauseForDoorPassage(operation, "settling approach passage before final blueprint portal use");
        }
        if (operation.blueprintPortalSettling && doorPassage.snapshot().intent() != null) {
            return status(operation, State.EXECUTING,
                    "settling approach passage before final blueprint portal use", 0);
        }
        BlockState observed = client.world.getBlockState(position);
        String authorityId = operation.goal.missionId() + ":blueprint-portal:" + position.asLong();
        if (dev.entity.client.blueprint.BlueprintTerrainStates.matches(operation.blueprintPortalWanted,observed)) {
            actuators.completeExactAuthorization(authorityId);
            stopDoorClearance(operation);
            operation.blueprintPortal = null;
            operation.blueprintPortalApproach = null;
            operation.blueprintPortalIssued = 0;
            operation.blueprintPortalSettling = false;
            resumeAfterDoorPassage(operation, "verified exact blueprint portal state; continuing construction");
            return status(operation,State.EXECUTING,"verified exact blueprint portal state",0);
        }
        var portal = actuators.inspectPortal(position).orElse(null);
        if (portal == null || !dev.entity.client.blueprint.BlueprintBuildSession.sameOpenPortal(observed,operation.blueprintPortalWanted)) {
            actuators.completeExactAuthorization(authorityId);
            stopDoorClearance(operation);
            operation.blueprintPortal = null;
            operation.blueprintPortalApproach = null;
            operation.blueprintPortalIssued = 0;
            operation.blueprintPortalSettling = false;
            resumeAfterDoorPassage(operation,"blueprint portal changed; reconciling native structural repair");
            return status(operation,State.EXECUTING,"blueprint portal changed; reconciling native structural repair",Double.NaN);
        }
        if (operation.blueprintPortalIssued != 0) {
            if (now-operation.blueprintPortalIssued > DoorPassageSession.ACTION_RESULT_TIMEOUT_MILLIS)
                return status(operation,State.BLOCKED,"Blueprint portal use did not produce its requested use state",Double.NaN);
            return status(operation,State.EXECUTING,"verifying exact blueprint portal use",0);
        }
        if(!operation.blueprintPortalWanted.get(net.minecraft.state.property.Properties.OPEN)) {
            double signed=(client.player.getX()-(portal.x()+0.5))*portal.normalX()
                    +(client.player.getY()-(portal.y()+0.5))*portal.normalY()
                    +(client.player.getZ()-(portal.z()+0.5))*portal.normalZ();
            var closure=new DoorPassageSession.Intent(authorityId,operation.goal.missionId(),currentDimension(),
                    portal.x(),portal.y(),portal.z(),portal.kind(),portal.blockId(),portal.stableFingerprint(),
                    portal.normalX(),portal.normalY(),portal.normalZ(),portal.height(),signed<0?-1:1,true,
                    DoorPassageSession.Phase.WAITING_RESTORE_AUTHORITY,0,0,0,now,"");
            if(ownDoorwayCellOccupied(closure)||client.player.getBoundingBox().intersects(portalClosureBounds(closure))
                    ||operation.doorClearanceRunning) {
                var clearance=clearOwnDoorBody(operation,closure,now);
                if(clearance!=null)return status(operation,clearance.blocked()?State.BLOCKED:State.EXECUTING,
                        clearance.detail(),Double.NaN);
            }
            stopDoorClearance(operation);
            if(!portalClosureClear(closure))return status(operation,State.EXECUTING,
                    "waiting for living bodies to clear the blueprint portal before closing",0);
        }
        BlockHitResult hit = raycastResource(position,client.player.getBlockInteractionRange());
        if (hit == null) {
            // Clearance may have moved out of reach. A new native approach may
            // use doors again, but must settle its own debt at the next stance.
            operation.blueprintPortalSettling = false;
            if (!baritone.getCustomGoalProcess().isActive()) {
                BlockPos approach = findHarvestApproach(operation,position,Set.of());
                if (approach == null) return status(operation,State.BLOCKED,"No visible supported approach to the blueprint portal",Double.NaN);
                operation.blueprintPortalApproach = approach;
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(approach));
            }
            operation.progressExpected = true;
            return status(operation,State.EXECUTING,"approaching the placed blueprint portal",distanceRemaining());
        }
        pauseForDoorPassage(operation,"using the placed blueprint portal");
        if (client.player.isSneaking()) return status(operation,State.EXECUTING,"releasing placement sneak before portal use",0);
        var target = new ProtectedAreaHomePermit.Target(currentDimension(),portal.x(),portal.y(),portal.z());
        if(operation.blueprintPortalStarted==0)operation.blueprintPortalStarted=now;
        var authority = actuators.requestExactPassageAuthorization(authorityId,authorityId,target,now);
        if (authority.state() == ProtectedAreaClientState.HomeAuthorizationState.DENIED)
            return status(operation,State.BLOCKED,authority.detail(),Double.NaN);
        if (authority.state() == ProtectedAreaClientState.HomeAuthorizationState.WAITING) {
            if (now-operation.blueprintPortalStarted > PORTAL_AUTHORITY_TIMEOUT_MILLIS)
                return status(operation,State.BLOCKED,"Blueprint portal use authority timed out",Double.NaN);
            return status(operation,State.EXECUTING,authority.detail(),0);
        }
        operation.blueprintPortalIssued = now;
        try {
            actuators.interactPortalWithExactAuthorization(target,portal.stableFingerprint(),Hand.MAIN_HAND,hit);
        } finally { actuators.completeExactAuthorization(authorityId); }
        return status(operation,State.EXECUTING,"issued exact blueprint portal use; awaiting physical state",0);
    }

    private ProtectedAreaClientState.HomeAuthorization requestDoorAuthority(
            DoorPassageSession.Intent intent,
            boolean restoring,
            long nowMillis) {
        return actuators.requestExactPassageAuthorization(
                doorAuthorityOperation(intent, restoring),
                "door-passage:" + intent.id(),
                doorTarget(intent),
                nowMillis);
    }

    private void issueDoorToggle(
            DoorPassageSession.Intent intent,
            boolean restoring) {
        String authorityOperation = doorAuthorityOperation(intent, restoring);
        BlockPos position = new BlockPos(intent.x(), intent.y(), intent.z());
        Direction side = portalDirection(intent);
        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(position), side, position, false);
        ActionResult result;
        try {
            result = actuators.interactPortalWithExactAuthorization(
                    doorTarget(intent), intent.stableFingerprint(), Hand.MAIN_HAND, hit);
        } finally {
            actuators.completeExactAuthorization(authorityOperation);
        }
        if (result.isAccepted() && client.player != null) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private static ProtectedAreaHomePermit.Target doorTarget(DoorPassageSession.Intent intent) {
        return new ProtectedAreaHomePermit.Target(
                intent.dimension(), intent.x(), intent.y(), intent.z());
    }

    private static String doorAuthorityOperation(
            DoorPassageSession.Intent intent,
            boolean restoring) {
        return intent.id() + (restoring ? ":restore" : ":open");
    }

    private static Direction portalDirection(DoorPassageSession.Intent intent) {
        if (intent.normalX() > 0) return Direction.EAST;
        if (intent.normalX() < 0) return Direction.WEST;
        if (intent.normalY() > 0) return Direction.UP;
        if (intent.normalY() < 0) return Direction.DOWN;
        return intent.normalZ() > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static boolean matchesPortal(
            DoorPassageSession.Intent intent,
            MinecraftActuatorGateway.PortalState portal) {
        return intent.dimension().equals(portal.dimension())
                && intent.x() == portal.x() && intent.y() == portal.y()
                && intent.z() == portal.z()
                && intent.kind().equals(portal.kind())
                && intent.blockId().equals(portal.blockId())
                && DoorPassageSession.samePortalFingerprint(intent.stableFingerprint(), portal.stableFingerprint())
                && DoorPassageSession.samePortalNormal(intent.kind(), intent.normalX(), intent.normalY(), intent.normalZ(),
                        portal.normalX(), portal.normalY(), portal.normalZ())
                && intent.height() == portal.height();
    }

    private void observeNativeDoorEntry(ActiveOperation operation,long nowMillis) {
        if(operation==null || client.player==null || client.world==null
                || !arbiter.isValid(operation.controlLease,nowMillis)
                || !baritone.getPathingBehavior().isPathing()) return;
        var executor=baritone.getPathingBehavior().getCurrent();
        if(executor==null) return;
        int index=executor.getPosition();
        var movements=executor.getPath().movements();
        if(index<0 || index>=movements.size()) return;
        var edge=movements.get(index);
        var source=edge.getSrc(); var destination=edge.getDest();
        BlockPos target=new BlockPos(destination.x,destination.y,destination.z);
        var portal=actuators.inspectPortal(target).orElse(null);
        if(portal==null || portal.open() || !portal.kind().equals("door")
                || client.player.squaredDistanceTo(portal.x()+0.5,portal.y()+0.5,portal.z()+0.5)>9.0) return;
        if(RouteRecoveryGeometry.nativePortalEntry(
                new RouteRecoveryGeometry.Cell(source.x,source.y,source.z),
                new RouteRecoveryGeometry.Cell(destination.x,destination.y,destination.z),
                new RouteRecoveryGeometry.Cell(portal.x(),portal.y(),portal.z()),
                portal.normalX(),portal.normalY(),portal.normalZ())) {
            // Ascend and Diagonal do not emit the ordinary flat-step door click.
            // Observe this exact existing edge; no new path or direct-use controller.
            actuators.observeTraversalPortal(target);
        }
    }

    private static Box portalClosureBounds(DoorPassageSession.Intent intent) {
        var bounds = intent.closureBounds();
        return new Box(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    private boolean ownDoorwayCellOccupied(DoorPassageSession.Intent intent) {
        return client.player != null && intent.occupiesDoorwayCell(
                client.player.getX(), client.player.getY(), client.player.getZ());
    }

    private boolean portalClosureClear(DoorPassageSession.Intent intent) {
        Box closurePlane = portalClosureBounds(intent);
        if (ownDoorwayCellOccupied(intent)
                || client.player != null && client.player.getBoundingBox().intersects(closurePlane)) {
            return false;
        }
        return client.world != null && client.world.getOtherEntities(
                client.player,
                closurePlane,
                entity -> entity instanceof LivingEntity && entity.isAlive()).isEmpty();
    }

    /** Resolves only Entity's own obstruction using the existing native route process. */
    private DoorPassageOutcome clearOwnDoorBody(ActiveOperation operation,
            DoorPassageSession.Intent intent, long nowMillis) {
        if (operation == null || !arbiter.isValid(operation.controlLease, nowMillis)) {
            return holdDoor(operation, "door restoration retained; a fresh movement owner must clear Entity's body");
        }
        if (operation.doorClearanceStartedAt == 0L) {
            pauseForDoorPassage(operation, "clearing Entity's own body before restoring the door");
            operation.doorClearanceStartedAt = nowMillis;
            List<BlockPos> candidates = intent.clearanceCells(client.player.getX(),
                            client.player.getY(), client.player.getZ()).stream()
                    .map(cell -> new BlockPos(cell.x(), cell.y(), cell.z()))
                    .filter(this::clearSupportedPortalStance)
                    .filter(candidate -> {
                        Box destination = client.player.getBoundingBox().offset(
                                candidate.getX() + 0.5D - client.player.getX(),
                                candidate.getY() - client.player.getY(),
                                candidate.getZ() + 0.5D - client.player.getZ());
                        return !destination.intersects(portalClosureBounds(intent))
                                && client.world.getOtherEntities(client.player,
                                client.player.getBoundingBox().union(destination),
                                entity -> entity instanceof LivingEntity && entity.isAlive()).isEmpty();
                    }).limit(2).toList();
            operation.doorClearanceRunning = routeRecovery.begin(candidates, nowMillis);
            operation.lastEvent = null;
            lastPathEvent.set(null);
            logger.info("Door restoration clearing own body at {} via {} before closing {}",
                    client.player.getPos(), candidates, new BlockPos(intent.x(), intent.y(), intent.z()));
        }
        // Leaving the thin closure plane can still leave feet inside the door cell.
        // Let the already-issued native safe stance finish before closing/replanning.
        if (routeRecovery.snapshot().result() == NativeRouteRecoveryProcess.Result.REACHED
                && !client.player.getBoundingBox().intersects(portalClosureBounds(intent))
                && !ownDoorwayCellOccupied(intent)
                && nowMillis - operation.doorClearanceStartedAt < DoorPassageSession.CROSSING_TIMEOUT_MILLIS) {
            return null;
        }
        if (!routeRecovery.active() || nowMillis - operation.doorClearanceStartedAt
                >= DoorPassageSession.CROSSING_TIMEOUT_MILLIS) {
            stopDoorClearance(operation);
            String detail = "could not clear Entity's body from the saved door via a nearby dry stance; "
                    + "door left open with restoration debt retained";
            pauseForDoorPassage(operation, detail);
            // A failed movement attempt does not make the unchanged open door itself
            // permanently BLOCKED; a fresh operation or manual clearance may resolve it.
            return new DoorPassageOutcome(true, true, detail);
        }
        observeFunctionalMiningRoute(nowMillis);
        operation.progressExpected = true;
        operation.waitingDetail = "moving clear of the door before restoring it";
        // holdDoor would cancel this very route on every poll, recreating the self-deadlock.
        return new DoorPassageOutcome(true, false, operation.waitingDetail);
    }

    private void stopDoorClearance(ActiveOperation operation) {
        if (operation == null || !operation.doorClearanceRunning) return;
        routeRecovery.cancel();
        operation.doorClearanceRunning = false;
        pauseForDoorPassage(operation, "Entity's door clearance route ended; rechecking closure safety");
    }

    private void pauseForDoorPassage(ActiveOperation operation, String detail) {
        nativeProspecting.cancel();
        try {
            baritone.getFollowProcess().cancel();
            baritone.getMineProcess().cancel();
            baritone.getBuilderProcess().onLostControl();
            baritone.getCustomGoalProcess().setGoal(null);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getPathingBehavior().cancelEverything();
        } catch (RuntimeException ignored) {
        }
        releaseRouteMomentum(operation);
        if (operation != null) {
            operation.progressExpected = false;
            operation.waitingDetail = detail;
        }
    }

    private DoorPassageOutcome holdDoor(ActiveOperation operation, String detail) {
        pauseForDoorPassage(operation, detail);
        return new DoorPassageOutcome(true, false, detail);
    }

    private DoorPassageOutcome blockDoor(ActiveOperation operation, String detail) {
        pauseForDoorPassage(operation, detail);
        if (operation != null) {
            operation.blockedDetail = detail;
        }
        return new DoorPassageOutcome(true, true, detail);
    }

    private void resumeAfterDoorPassage(ActiveOperation operation, String detail) {
        actuators.setPortalPassageFence(operation != null);
        if (operation == null) return;
        if (operation.pendingCompletionDetail != null) {
            operation.progressExpected = false;
            operation.waitingDetail = detail;
            return;
        }
        operation.progressExpected = true;
        operation.waitingDetail = detail;
        operation.traversalWatchdog.reset();
        operation.lastEvent = null;
        lastPathEvent.set(null);
        if (operation.blueprintPortal != null) {
            // Restore the interrupted approach, not Builder or final-use clicks.
            if (!operation.blueprintPortalSettling && operation.blueprintPortalApproach != null) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(operation.blueprintPortalApproach));
            }
            return;
        }
        LandHuntFrontierPolicy.Candidate retainedFrontier = operation.boundedHunt
                && operation.attackTarget == null ? operation.huntFrontiers.current() : null;
        recommitAfterRouteSupportRecovery(operation);
        if (retainedFrontier != null && operation.huntFrontiers.current() == retainedFrontier) {
            logger.info("Door handoff resumed hunt {} frontier {},{},{} deadline {} nativeActive={}: {}",
                    operation.goal.missionId(), retainedFrontier.x(), retainedFrontier.y(),
                    retainedFrontier.z(), operation.huntSearchDeadlineAt,
                    baritone.getCustomGoalProcess().isActive(), detail);
        }
    }

    /** Converts only route-owned vetoes to route failure, not another controller's rejection. */
    public synchronized Optional<String> enforceProtectedInteractionGuard() {
        DoorPassageOutcome passage = maintainDoorPassage(
                active, System.currentTimeMillis());
        if (passage != null && passage.blocked()) {
            return Optional.of(passage.detail());
        }
        Optional<MinecraftActuatorGateway.BlockedInteractionAttempt> attempted =
                actuators.takeBlockedInteractionAttempt();
        if (attempted.isEmpty()) return Optional.empty();
        MinecraftActuatorGateway.BlockedInteractionAttempt blocked = attempted.orElseThrow();
        if (!NativeRouteInteractionPolicy.failsRoute(blocked.action())) {
            logger.debug("Suppressed incidental block use; native route remains owned: {}",
                    blocked.detail());
            return Optional.empty();
        }
        stopProtectedSupportBreak(blocked.detail());
        if (active != null) {
            active.progressExpected = false;
            active.blockedDetail = blocked.detail();
            active.waitingDetail = blocked.detail();
        }
        return Optional.of(blocked.detail());
    }

    private Set<RouteSupportCustody.Coordinate> stableBodySupportContacts() {
        var player = client.player;
        if (player == null || client.world == null || !player.isOnGround()) return Set.of();
        Box body = player.getBoundingBox();
        int minX = MathHelper.floor(body.minX + 1.0E-5);
        int maxX = MathHelper.floor(body.maxX - 1.0E-5);
        int minZ = MathHelper.floor(body.minZ + 1.0E-5);
        int maxZ = MathHelper.floor(body.maxZ - 1.0E-5);
        int minY = MathHelper.floor(body.minY - 1.51);
        int maxY = MathHelper.floor(body.minY + 0.01);
        LinkedHashSet<RouteSupportCustody.Coordinate> contacts = new LinkedHashSet<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = client.world.getBlockState(cursor);
                    // Sand/gravel/anvils may cease to be support after an
                    // adjacent cleanup.  They cannot prove independent footing.
                    if (state.getBlock() instanceof FallingBlock) continue;
                    var shape = state.getCollisionShape(client.world, cursor);
                    if (shape.isEmpty()) continue;
                    boolean supporting = false;
                    for (Box local : shape.getBoundingBoxes()) {
                        double top = y + local.maxY;
                        boolean horizontalOverlap = x + local.maxX > body.minX + 1.0E-5
                                && x + local.minX < body.maxX - 1.0E-5
                                && z + local.maxZ > body.minZ + 1.0E-5
                                && z + local.minZ < body.maxZ - 1.0E-5;
                        if (horizontalOverlap && Math.abs(top - body.minY) <= 0.08) {
                            supporting = true;
                            break;
                        }
                    }
                    if (supporting) contacts.add(routeSupportCoordinate(cursor));
                }
            }
        }
        return Set.copyOf(contacts);
    }

    private RouteSupportCustody.Coordinate routeSupportCoordinate(BlockPos position) {
        return new RouteSupportCustody.Coordinate(
                currentDimension(), position.getX(), position.getY(), position.getZ());
    }

    private void stopProtectedSupportBreak(String reason) {
        try {
            if (client.interactionManager != null) {
                client.interactionManager.cancelBlockBreaking();
            }
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            baritone.getPathingBehavior().cancelEverything();
        } catch (RuntimeException ignored) {
        }
        releaseRouteMomentum(active);
        logger.warn("{}", reason);
    }

    private void neutralizeRejectedResourcePosture() {
        try {
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException ignored) {
        }
        if (client.options != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            client.options.attackKey.setPressed(false);
            client.options.useKey.setPressed(false);
        }
        if (client.player != null) client.player.setPitch(0.0F);
    }

    /**
     * Installs the current mission's count-aware reservation projection. Baritone exposes only an
     * item-type throwaway list, so any reserved count protects that complete item type. The active
     * operation is updated immediately; workstation goto routes therefore receive the same fence
     * as mining routes.
     */
    public synchronized void syncMissionThrowawayReservations(Collection<String> protectedItems) {
        Set<String> normalized = ThrowawayReservationPolicy.normalized(protectedItems);
        if (missionProtectedThrowawayItems.equals(normalized)) return;
        missionProtectedThrowawayItems = normalized;
        applyThrowawayFence(active);
    }

    /** Restores the exact Baritone baseline at a mission lifecycle boundary. */
    public synchronized void releaseMissionThrowawayReservations() {
        missionProtectedThrowawayItems = Set.of();
        BaritoneAPI.getSettings().acceptableThrowawayItems.value =
                new ArrayList<>(baselineAcceptableThrowawayItems);
        if (active != null) active.protectedThrowawayItems = Set.of();
    }

    /** True while the exact mining leaf still owns a live process, path, calculation, or crack. */
    public synchronized boolean isOwnedMiningActive(String operationId, long controlEpoch) {
        ActiveOperation operation = active;
        if (operation == null
                || operation.controlEpoch != controlEpoch
                || !operation.goal.missionId().equals(operationId)
                || !isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))) return false;
        // Typed resource actuation is itself the live mining owner. It may be
        // aligning an exact ray, waiting for the first accepted crack frame, or
        // walking to a just-spawned drop while every Baritone process is briefly
        // quiescent. Treating only a MineProcess/path/crack as ownership made the
        // planner tear down and recreate this exact session at both boundaries.
        if (operation.resourceSession != null
                && operation.resourceSession.phase()
                != ResourceActuationSession.Phase.BLOCKED) {
            return true;
        }
        return BaritoneActuatorScopePolicy.hasLiveMiningWork(
                routeRecovery.active() || nativeProspecting.active() || operation.functionalRouteReplay
                        || operation.functionalMiningReturning
                        || operation.functionalMiningReturnBlocked,
                // Opening/restoring a door cancels native actuators temporarily. The same
                // operation still owns that handoff (or explicit blocked restoration debt).
                // Without it, the executor rebased the segment and abandoned this crossing.
                doorPassage.snapshot().intent() != null,
                baritone.getMineProcess().isActive(),
                baritone.getPathingBehavior().isPathing(),
                baritone.getPathingBehavior().getInProgress().isPresent(),
                client.interactionManager != null && client.interactionManager.isBreakingBlock());
    }

    /**
     * Exact durable ownership, including the quiescent interval between a
     * completed/cancelled actuator and the autonomy layer's explicit handoff.
     */
    public synchronized boolean ownsOperation(String operationId, long controlEpoch) {
        String expectedOperation = Objects.requireNonNull(operationId, "operationId").trim();
        if (expectedOperation.isEmpty()) {
            throw new IllegalArgumentException("operationId cannot be blank");
        }
        ActiveOperation operation = active;
        return operation != null
                && operation.controlEpoch == controlEpoch
                && operation.goal.missionId().equals(expectedOperation);
    }

    @Override
    public synchronized boolean ownsScopedResourceActuation(
            String operationId,
            long controlEpoch) {
        ActiveOperation operation = active;
        return operation != null
                && operation.controlEpoch == controlEpoch
                && operation.goal.missionId().equals(operationId)
                && operation.resourceSession != null
                && operation.resourceSession.phase()
                != ResourceActuationSession.Phase.BLOCKED;
    }

    /**
     * True only while the exact owned route can still select one currently carried,
     * operation-approved support block. Mining uses this boundary to distinguish a legitimate
     * temporary throwaway hand from the empty hand left after the final support is consumed.
     */
    public synchronized boolean hasCarriedPlacementSupport(
            String operationId,
            long controlEpoch) {
        ActiveOperation operation = active;
        if (operation == null
                || operation.controlEpoch != controlEpoch
                || !operation.goal.missionId().equals(operationId)) return false;

        ArrayList<String> acceptable = new ArrayList<>();
        for (Item item : BaritoneAPI.getSettings().acceptableThrowawayItems.value) {
            acceptable.add(Registries.ITEM.getId(item).toString());
        }
        ArrayList<String> carried = new ArrayList<>();
        if (client.player != null) {
            for (var stack : client.player.getInventory().getMainStacks()) {
                if (!stack.isEmpty()) {
                    carried.add(Registries.ITEM.getId(stack.getItem()).toString());
                }
            }
        }
        boolean requested = parseBoolean(
                operation.goal.arguments().get("allowPlace"), true);
        return ThrowawayReservationPolicy.canPlace(requested, acceptable, carried);
    }

    private String currentBreakingTarget() {
        if (client.interactionManager == null || !client.interactionManager.isBreakingBlock()
                || !(client.crosshairTarget instanceof BlockHitResult hit)) return "";
        BlockPos pos = hit.getBlockPos();
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private String currentRouteSignature() {
        if (!playerContextReady()) return "";
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return "";
        int position = executor.getPosition();
        var movements = executor.getPath().movements();
        if (position < 0 || position >= movements.size()) return "";
        var movement = movements.get(position);
        return routeSignature(
                movement.getClass().getSimpleName(), position,
                movement.getSrc().x, movement.getSrc().y, movement.getSrc().z,
                movement.getDest().x, movement.getDest().y, movement.getDest().z);
    }

    /** Enables the useful live route/goal/action overlays in Entity's own client window. */
    public synchronized String setVisualsEnabled(boolean enabled) {
        var settings = BaritoneAPI.getSettings();
        settings.renderPath.value = enabled;
        settings.renderGoal.value = enabled;
        settings.renderSelectionBoxes.value = enabled;
        // The chunk-cache overlay is noisy and expensive; it is not part of the
        // route visualization controlled by this switch.
        settings.renderCachedChunks.value = false;
        return visualsStatus();
    }

    public synchronized String visualsStatus() {
        var settings = BaritoneAPI.getSettings();
        boolean enabled = settings.renderPath.value
                || settings.renderGoal.value
                || settings.renderSelectionBoxes.value;
        return "Baritone visuals=" + (enabled ? "on" : "off")
                + " in Entity's bot-client window"
                + " (path=" + onOff(settings.renderPath.value)
                + ", goal=" + onOff(settings.renderGoal.value)
                + ", actions=" + onOff(settings.renderSelectionBoxes.value) + ")";
    }

    /** True only while Baritone has a live path on which its bucket-fall movement can act. */
    public synchronized boolean hasManagedFallRecovery() {
        if (!hasCommittedFallMovement() || !BaritoneAPI.getSettings().allowWaterBucketFall.value
                || client.world == null || client.world.getDimension().ultrawarm()) return false;
        return hasUsableClutchBucket();
    }

    /** A fall is recoverable only when the direct survival owner can use the bucket now. */
    private boolean hasUsableClutchBucket() {
        if (client.player == null) return false;
        if (client.player.getOffHandStack().isOf(Items.WATER_BUCKET)) return true;
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (inventory.getStack(slot).isOf(Items.WATER_BUCKET)) return true;
        }
        return false;
    }

    /** Preserve a committed downward movement even when no clutch item is usable. */
    public synchronized boolean hasCommittedFallMovement() {
        if (active == null || !baritone.getPathingBehavior().isPathing()
                || client.player == null) return false;
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return false;
        int position = executor.getPosition();
        var movements = executor.getPath().movements();
        if (position < 0 || position >= movements.size()) return false;
        var movement = movements.get(position);
        return movement.getDest().y < movement.getSrc().y && !movement.safeToCancel();
    }

    /**
     * Preserves only a committed descent whose exact loaded destination is
     * already real water. Bucket-fall movements use the acknowledged direct
     * transaction instead of being inferred from a generic downward edge.
     */
    public synchronized boolean hasCommittedExistingWaterLanding() {
        if (!hasCommittedFallMovement() || client.world == null) return false;
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return false;
        int position = executor.getPosition();
        var movements = executor.getPath().movements();
        if (position < 0 || position >= movements.size()) return false;
        var destination = movements.get(position).getDest();
        BlockPos feet = new BlockPos(destination.x, destination.y, destination.z);
        if (!client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        var feetState = client.world.getBlockState(feet);
        var headState = client.world.getBlockState(feet.up());
        return feetState.isOf(net.minecraft.block.Blocks.WATER)
                && feetState.getFluidState().isIn(FluidTags.WATER)
                && feetState.getFluidState().isStill()
                && feetState.getCollisionShape(client.world, feet).isEmpty()
                && headState.getCollisionShape(client.world, feet.up()).isEmpty();
    }

    /** Supplies Paper-authoritative coordinates when the tracked player is outside loaded client chunks. */
    public synchronized void updateTrackedTarget(
            String player,
            boolean online,
            double x,
            double y,
            double z,
            String dimension,
            long timestamp) {
        if (player == null || player.isBlank()) return;
        trackedWaypoint = new TrackedWaypoint(
                player,
                online,
                x,
                y,
                z,
                dimension == null ? "" : dimension,
                timestamp);
    }

    private void startGoto(Map<String, String> args) {
        startGoto(args, true);
    }

    private void startGoto(Map<String, String> args, boolean allowFunctionalReplay) {
        int x = integer(args, "x");
        int z = integer(args, "z");
        int range = Math.max(0, parseInteger(args.get("range"), 1));
        DimensionRoutePolicy.Decision dimension = DimensionRoutePolicy.decide(
                currentDimension(), args.getOrDefault("dimension", ""));
        if (dimension.action() == DimensionRoutePolicy.Action.PORTAL_UNAVAILABLE) {
            active.progressExpected = false;
            active.waitingDetail = dimension.detail();
            active.blockedDetail = dimension.detail();
            return;
        }
        if (Boolean.parseBoolean(args.getOrDefault("horizontalOnly", "false"))) {
            baritone.getCustomGoalProcess().setGoalAndPath(
                    range == 0 ? new GoalXZ(x, z) : new GoalNearXZ(x, z, range));
            return;
        }
        int y = integer(args, "y");
        if (allowFunctionalReplay) {
            MinecraftFunctionalRoutes.BeginResult route = functionalRoutes.beginReturn(
                    currentDimension(),
                    new BlockPos(x, y, z),
                    range,
                    System.currentTimeMillis());
            if (route.replaying()) {
                active.functionalRouteReplay = true;
                active.progressExpected = true;
                active.waitingDetail = route.detail();
                return;
            }
        }
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(new BlockPos(x, y, z), range));
    }

    private void startFollow(String playerName) {
        startFollow(playerName, 3);
    }

    private void startFollow(String playerName, int followRadius) {
        active.playerTarget = true;
        startFollow(
                entity -> entity.getName().getString().equalsIgnoreCase(playerName),
                playerName,
                followRadius);
    }

    private void startFollow(Predicate<Entity> predicate, String description) {
        startFollow(predicate, description, 3);
    }

    private void startFollow(Predicate<Entity> predicate, String description, int followRadius) {
        active.targetPredicate = predicate;
        active.targetDescription = description;
        active.followRadius = Math.max(1, followRadius);
        BaritoneAPI.getSettings().followRadius.value = active.followRadius;
        // A live player gets committed GoalNear snapshots from maintainTrackedPlayer. Baritone's
        // FollowProcess replaces its dynamic goal whenever the player twitches, which restarts a
        // partially broken obstacle. Non-player pursuits retain the ordinary FollowProcess.
        if (!active.playerTarget) {
            Entity visible = nearestTarget(active);
            if (visible != null) {
                configureTraversalMode(
                        active, visible.getBlockPos(), System.currentTimeMillis());
            }
            baritone.getFollowProcess().follow(predicate);
        }
    }

    private void startAttack(Map<String, String> args) {
        String player = args.getOrDefault("player", args.getOrDefault("target", "")).trim();
        String entityType = args.getOrDefault("entityType", args.getOrDefault("type", "")).trim();
        Set<String> entityTypes = Arrays.stream(args.getOrDefault("entityTypes", entityType).split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(FabricBaritonePort::simpleIdentifier)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean hostile = Boolean.parseBoolean(args.getOrDefault("hostile", "false"));
        boolean hunting = active.goal.kind().equalsIgnoreCase("hunt");

        Predicate<Entity> target;
        String description;
        if (!player.isBlank()) {
            target = entity -> entity.getName().getString().equalsIgnoreCase(player)
                    && entityAttacks.permitsSelection(
                    entity, EntityDispositionPolicy.Purpose.EXPLICIT_ATTACK);
            description = player;
            active.playerTarget = true;
        } else if (!entityTypes.isEmpty()) {
            target = entity -> entityTypes.contains(EntityType.getId(entity.getType()).getPath())
                    && (!hunting || eligibleWildAdult(entity))
                    && entityAttacks.permitsSelection(
                    entity, hunting
                            ? EntityDispositionPolicy.Purpose.HUNT
                            : EntityDispositionPolicy.Purpose.EXPLICIT_ATTACK);
            description = String.join(" or ", entityTypes);
        } else if (hostile || args.isEmpty()) {
            target = entity -> entity instanceof HostileEntity
                    && entityAttacks.permitsSelection(
                    entity, EntityDispositionPolicy.Purpose.EXPLICIT_ATTACK);
            description = "nearest hostile mob";
        } else {
            active.blockedDetail = "attack requires a player, entity type, or hostile=true";
            return;
        }
        if (hunting) {
            active.targetPredicate = target;
            active.targetDescription = description;
            // FollowProcess stops anywhere inside this radius. Three blocks left
            // passive animals enough room to flee at almost exactly attack reach,
            // producing the observed 2-4 block chase loop. A hunt commits to a
            // one-block snapshot goal and therefore closes before it swings.
            active.followRadius = HUNT_CLOSE_RADIUS;
            BaritoneAPI.getSettings().followRadius.value = active.followRadius;
            configureHuntSearch(active, args);
            String violation = boundedHuntViolation(active, System.currentTimeMillis());
            if (violation != null) {
                active.blockedDetail = violation;
                active.progressExpected = false;
                return;
            }
            Entity loadedTarget = bestHuntTarget(active, System.currentTimeMillis());
            if (loadedTarget != null) pinAttackTarget(active, loadedTarget);
            else startHuntExploration(active);
            return;
        }
        active.targetPredicate = target;
        active.targetDescription = description;
        active.followRadius = 3;
        BaritoneAPI.getSettings().followRadius.value = active.followRadius;
        // A protection kill may already have satisfied the exact selected objective. Starting
        // another Follow route here would turn a finite Attack into an endless same-type chase.
        if (attackMissionObjective.completed(active.goal, client.world).isPresent()) return;
        Entity retained = attackMissionObjective.retainedTarget(active.goal, client.world).orElse(null);
        if (retained != null) {
            if (!target.test(retained)) {
                active.blockedDetail = "selected attack target is no longer permitted";
                return;
            }
            configureTraversalMode(active, retained.getBlockPos(), System.currentTimeMillis());
            pinAttackTarget(active, retained);
        } else {
            Entity selected = nearestTarget(active);
            if (selected != null) {
                configureTraversalMode(active, selected.getBlockPos(), System.currentTimeMillis());
                pinAttackTarget(active, selected);
            } else startFollow(target, description);
        }
    }

    private void configureHuntSearch(ActiveOperation operation, Map<String, String> args) {
        int fallbackX = client.player == null ? 0 : client.player.getBlockX();
        int fallbackZ = client.player == null ? 0 : client.player.getBlockZ();
        operation.huntSearchOriginX = parseInteger(args.get("huntSearchOriginX"), fallbackX);
        operation.huntSearchOriginZ = parseInteger(args.get("huntSearchOriginZ"), fallbackZ);
        operation.huntSearchRadius = Math.max(
                1,
                parseInteger(args.get("huntSearchRadius"), DEFAULT_HUNT_SEARCH_RADIUS));
        operation.huntSearchDeadlineAt = parseLong(
                args.get("huntSearchDeadlineAt"),
                operation.startedAt + DEFAULT_HUNT_SEARCH_MILLIS);
        operation.boundedHunt = true;
    }

    private void startHuntExploration(ActiveOperation operation) {
        if (!operation.boundedHunt || operation.attackTarget != null) return;
        startNextHuntFrontier(operation, System.currentTimeMillis(), "starting land-aware scouting");
    }

    private void maintainHuntExploration(ActiveOperation operation, long now) {
        LandHuntFrontierPolicy.Candidate frontier = operation.huntFrontiers.current();
        if (frontier == null) {
            if (now >= operation.huntFrontierRetryAt) {
                startNextHuntFrontier(operation, now, "seeking a safe loaded land frontier");
            }
            return;
        }
        double distance = huntFrontierDistance(frontier);
        boolean moving = baritone.getPathingBehavior().isPathing();
        boolean calculating = !moving && (baritone.getPathingBehavior().getInProgress().isPresent()
                || (baritone.getCustomGoalProcess().isActive()
                && isCalculationEvent(operation.lastEvent)));
        LandHuntFrontierPolicy.RouteState routeState = moving
                ? LandHuntFrontierPolicy.RouteState.MOVING
                : calculating ? LandHuntFrontierPolicy.RouteState.CALCULATING
                : LandHuntFrontierPolicy.RouteState.INACTIVE;
        BlockPos feet = new BlockPos(frontier.x(), frontier.y(), frontier.z());
        LandHuntFrontierPolicy.Decision decision = operation.huntFrontiers.evaluate(
                distance, routeState, isWalkableLandFrontier(feet), now);
        if (decision.action() == LandHuntFrontierPolicy.Action.REACHED
                || decision.action() == LandHuntFrontierPolicy.Action.REJECT) {
            if (decision.action() == LandHuntFrontierPolicy.Action.REACHED) {
                operation.huntFrontiers.reachedCurrent();
            } else {
                operation.huntFrontiers.rejectCurrent(now);
            }
            startNextHuntFrontier(operation, now, decision.reason());
            return;
        }
        operation.progressExpected = routeState == LandHuntFrontierPolicy.RouteState.MOVING;
        operation.waitingDetail = "land-aware hunt scouting toward "
                + frontier.x() + ',' + frontier.y() + ',' + frontier.z()
                + ": " + decision.reason();
    }

    private void startNextHuntFrontier(ActiveOperation operation, long now, String reason) {
        try { baritone.getFollowProcess().cancel(); } catch (RuntimeException ignored) { }
        try { baritone.getPathingBehavior().cancelEverything(); } catch (RuntimeException ignored) { }
        operation.lastEvent = null;
        lastPathEvent.set(null);
        Optional<LandHuntFrontierPolicy.Candidate> selected = operation.huntFrontiers.select(
                loadedLandHuntFrontiers(operation), client.player != null && client.player.isTouchingWater(), now);
        if (selected.isEmpty()) {
            operation.progressExpected = false;
            operation.huntFrontierRetryAt = now + 5_000;
            operation.waitingDetail = "holding instead of entering open water; no safe loaded land frontier yet";
            return;
        }
        LandHuntFrontierPolicy.Candidate frontier = selected.get();
        double distance = huntFrontierDistance(frontier);
        operation.huntFrontiers.selected(frontier, distance, now);
        operation.huntFrontierRetryAt = 0;
        // A selected scouting destination is a new traversal generation. Its return leg can
        // legitimately cross the previous frontier's entire body envelope, and its native
        // heuristic is not comparable to the old goal's minimum. Executor/edge changes within
        // this destination still share the same bounded watchdog; do not reset on ordinary polls.
        operation.traversalWatchdog.reset();
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(
                new BlockPos(frontier.x(), frontier.y(), frontier.z()), 4));
        operation.progressExpected = true;
        operation.waitingDetail = reason + "; pinned verified land frontier at "
                + frontier.x() + ',' + frontier.y() + ',' + frontier.z();
    }

    private Status rejectHuntFrontier(ActiveOperation operation, String reason, long now) {
        operation.huntFrontiers.rejectCurrent(now);
        operation.traversalWatchdog.reset();
        operation.lastEvent = null;
        lastPathEvent.set(null);
        startNextHuntFrontier(operation, now, reason);
        return status(operation, State.EXECUTING, operation.waitingDetail, distanceRemaining());
    }

    private List<LandHuntFrontierPolicy.Candidate> loadedLandHuntFrontiers(
            ActiveOperation operation) {
        return loadedLandHuntFrontiers(operation.huntSearchOriginX,
                operation.huntSearchOriginZ, operation.huntSearchRadius);
    }

    private List<LandHuntFrontierPolicy.Candidate> loadedLandHuntFrontiers(
            int originX, int originZ, int searchRadius) {
        if (client.player == null || client.world == null) return List.of();
        LinkedHashMap<String, LandHuntFrontierPolicy.Candidate> candidates = new LinkedHashMap<>();
        int[] radii = {24, 36, 48, 64, 80};
        for (int radius : radii) {
            for (int direction = 0; direction < 24; direction++) {
                double angle = direction * Math.PI * 2.0 / 24.0;
                int x = (int) Math.floor(client.player.getX() + Math.cos(angle) * radius);
                int z = (int) Math.floor(client.player.getZ() + Math.sin(angle) * radius);
                if (!HuntTargetPolicy.insideSearchBoundary(x, z,
                        originX, originZ, searchRadius)) continue;
                LandPatch patch = sampleLandPatch(x, z);
                if (patch == null || !patch.centerWalkable()) continue;
                double dx = x + 0.5 - client.player.getX();
                double dz = z + 0.5 - client.player.getZ();
                double distance = Math.sqrt(dx * dx + dz * dz);
                double originDx = x - originX;
                double originDz = z - originZ;
                double outward = Math.sqrt(originDx * originDx + originDz * originDz);
                LandHuntFrontierPolicy.Candidate candidate = new LandHuntFrontierPolicy.Candidate(
                        (x >> 4) + ":" + (z >> 4), x, patch.y(), z, distance, outward,
                        patch.landFraction(), routeWaterFraction(x, z), patch.loadedFraction(),
                        Math.abs(patch.y() - client.player.getY()));
                candidates.putIfAbsent(candidate.id(), candidate);
            }
        }
        return new ArrayList<>(candidates.values());
    }

    private LandPatch sampleLandPatch(int centerX, int centerZ) {
        if (client.world == null
                || !loadedFullChunk(centerX, centerZ)) return null;
        int centerY = client.world.getTopY(
                Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
        boolean centerWalkable = isWalkableLandFrontier(new BlockPos(centerX, centerY, centerZ));
        int loaded = 0;
        int land = 0;
        for (int dx : new int[]{-6, 0, 6}) {
            for (int dz : new int[]{-6, 0, 6}) {
                int x = centerX + dx;
                int z = centerZ + dz;
                if (!loadedFullChunk(x, z)) continue;
                loaded++;
                int y = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (isWalkableLandFrontier(new BlockPos(x, y, z))) land++;
            }
        }
        if (loaded == 0) return null;
        return new LandPatch(centerY, centerWalkable, land / (double) loaded, loaded / 9.0);
    }

    private boolean isWalkableLandFrontier(BlockPos feet) {
        if (client.world == null || !loadedFullChunk(feet.getX(), feet.getZ())) {
            return false;
        }
        BlockState feetState = client.world.getBlockState(feet);
        BlockState headState = client.world.getBlockState(feet.up());
        BlockState floorState = client.world.getBlockState(feet.down());
        return feetState.getCollisionShape(client.world, feet).isEmpty()
                && headState.getCollisionShape(client.world, feet.up()).isEmpty()
                && !floorState.getCollisionShape(client.world, feet.down()).isEmpty()
                && !feetState.getFluidState().isIn(FluidTags.WATER)
                && !floorState.getFluidState().isIn(FluidTags.WATER)
                && !feetState.getFluidState().isIn(FluidTags.LAVA)
                && !floorState.getFluidState().isIn(FluidTags.LAVA)
                && !floorState.isOf(Blocks.MAGMA_BLOCK)
                && !floorState.isOf(Blocks.CACTUS)
                && !floorState.isOf(Blocks.CAMPFIRE)
                && !floorState.isOf(Blocks.SOUL_CAMPFIRE);
    }

    private double routeWaterFraction(int targetX, int targetZ) {
        if (client.player == null || client.world == null) return 1.0;
        int water = 0;
        int loaded = 0;
        for (int index = 1; index <= 12; index++) {
            double fraction = index / 12.0;
            int x = (int) Math.floor(client.player.getX()
                    + (targetX - client.player.getX()) * fraction);
            int z = (int) Math.floor(client.player.getZ()
                    + (targetZ - client.player.getZ()) * fraction);
            if (!loadedFullChunk(x, z)) continue;
            loaded++;
            int y = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockState floor = client.world.getBlockState(new BlockPos(x, y - 1, z));
            if (floor.getFluidState().isIn(FluidTags.WATER)) water++;
        }
        return loaded == 0 ? 1.0 : water / (double) loaded;
    }

    private double huntFrontierDistance(LandHuntFrontierPolicy.Candidate frontier) {
        if (client.player == null || frontier == null) return Double.NaN;
        double dx = frontier.x() + 0.5 - client.player.getX();
        double dy = frontier.y() - client.player.getY();
        double dz = frontier.z() + 0.5 - client.player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void pinAttackTarget(ActiveOperation operation, Entity target) {
        // Exploration and a live-animal pursuit must never be two competing
        // Baritone processes. The old implementation canceled only the current
        // PathingBehavior while leaving CustomGoalProcess active on the last
        // frontier, then started FollowProcess for the animal. On the next tick
        // the frontier process could regain control and walk away from the
        // selected animal. Bounded hunts now keep both phases on one custom-goal
        // owner and replace the frontier atomically with a committed snapshot.
        stopExploreProcess();
        suspendHuntPursuit(operation);
        suspendProtectionCombatRoute(operation);
        try { baritone.getFollowProcess().cancel(); } catch (RuntimeException ignored) { }
        operation.attackTarget = target;
        attackMissionObjective.selected(operation.goal, client.world, target, System.currentTimeMillis());
        if (operation.boundedHunt) {
            operation.huntTargets.selected(System.currentTimeMillis());
        }
        if (operation.boundedHunt) {
            maintainHuntPursuit(operation, target, System.currentTimeMillis());
        } else if (usesProtectionCombatRoute(operation, target)) {
            maintainProtectionCombatRoute(operation, target, System.currentTimeMillis());
        } else if (!operation.playerTarget) {
            Entity selected = target;
            baritone.getFollowProcess().follow(entity -> entity.getUuid().equals(selected.getUuid()));
        }
        operation.creeperFollowing = target instanceof CreeperEntity;
    }

    private static boolean eligibleWildAdult(Entity entity) {
        if (entity.hasCustomName()) return false;
        if (entity instanceof PassiveEntity passive && passive.isBaby()) return false;
        return !(entity instanceof Leashable leashable) || !leashable.isLeashed();
    }

    private void startMine(Map<String, String> args) {
        String raw = args.getOrDefault("blocks", args.getOrDefault(
                "block", args.getOrDefault("resource", args.getOrDefault("item", ""))));
        if (raw.isBlank()) {
            active.blockedDetail = "mine requires a block or resource";
            return;
        }
        String[] blocks = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(FabricBaritonePort::simpleIdentifier)
                .toArray(String[]::new);
        if (blocks.length == 0) {
            active.blockedDetail = "mine resource resolved to no blocks";
            return;
        }
        int quantity = Math.max(1, parseInteger(
                args.getOrDefault("count", args.getOrDefault("amount", args.get("quantity"))), 1));
        active.quantity = quantity;
        active.maximumMinedBlocks = Math.max(
                0, parseInteger(args.get("maxBlocks"), 0));
        String dimension = client.world == null
                ? ""
                : client.world.getRegistryKey().getValue().toString();
        int currentY = client.player == null ? 16 : client.player.getBlockY();
        MiningPolicy.Plan miningPlan = MiningPolicy.plan(blocks, dimension, currentY);
        Set<String> requestedItems = Arrays.stream(args.getOrDefault("expectedItems", "").split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(FabricBaritonePort::simpleIdentifier)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        active.expectedItemIds = requestedItems.isEmpty()
                ? miningPlan.expectedItemIds()
                : Set.copyOf(requestedItems);
        active.startingTargetItemCount = countTargetItems(active.expectedItemIds);
        active.miningBlocks = blocks;
        active.miningPlan = miningPlan;
        active.resourceAreaName = Objects.requireNonNullElse(
                args.get("resourceAreaName"), "").trim().toLowerCase(Locale.ROOT);
        active.resourceAreaFingerprint = Objects.requireNonNullElse(
                args.get("resourceAreaFingerprint"), "").trim();
        active.completeWhenResourceExhausted = Boolean.parseBoolean(
                args.getOrDefault("completeWhenResourceExhausted", "false"));
        active.resourceExhausted = false;
        applyThrowawayFence(active);
        String missingTool = firstMissingTargetTool(blocks);
        if (missingTool != null) {
            pauseMiningForTool(active, missingTool);
            return;
        }
        String sourceArgument = args.getOrDefault("worldSourceKind", "").trim();
        // Direct /e mine missions predate planner-authored worldSourceKind.
        // They are still automated extraction and must never regain the old
        // unrestricted global-mine bypass merely because the optional planner
        // metadata is absent.
        if (sourceArgument.isEmpty()) sourceArgument = "EXTRACTION";
        try {
            ResourceActuationSession.SourceKind sourceKind =
                    ResourceActuationSession.SourceKind.parse(sourceArgument);
            if (!active.resourceAreaName.isEmpty()) {
                if (sourceKind != ResourceActuationSession.SourceKind.HARVEST) {
                    active.blockedDetail = "a named farm area can only scope crop harvesting";
                    return;
                }
                if (active.resourceAreaFingerprint.isEmpty()) {
                    active.blockedDetail = "named farm area is missing its exact geometry binding";
                    return;
                }
                if (protectedAreas == null) {
                    active.blockedDetail = "typed farm-area authority is unavailable";
                    return;
                }
                ProtectedAreaClientState.NamedResourceAreaObservation named =
                        protectedAreas.observeNamedResourceArea(
                                ProtectedAreaPolicy.AreaKind.HARVESTING,
                                active.resourceAreaName);
                if (!named.available()) {
                    active.blockedDetail = named.detail();
                    return;
                }
                ProtectedAreaPolicy.Area exact = named.area().orElse(null);
                if (exact == null) {
                    active.blockedDetail = named.detail();
                    return;
                }
                if (!ManagedFarmPolicy.areaFingerprint(exact).equals(
                        active.resourceAreaFingerprint)) {
                    active.blockedDetail = "named farm area changed after the command was accepted; "
                            + "review it and issue a new farm run";
                    return;
                }
            }
            if (sourceKind == ResourceActuationSession.SourceKind.MINE) {
                // Native MineProcess owns this complete land segment: scanner
                // target order/blacklist, path/support placement, automatic
                // tool choice, physical break and loose-item scan. Entity2
                // observes the bounded result and retains the final property
                // veto instead of driving a competing exact-target actuator.
                active.resourceSession = null;
                // Native MineProcess owns collection as well as breaking. A
                // block-count stop used to cancel it before the loose item was
                // acquired and hand control to a competing pickup controller.
                active.maximumMinedBlocks = 0;
                startMiningProcess(active);
                return;
            }
            active.resourceSession = new ResourceActuationSession(
                    active.goal.missionId(),
                    sourceKind,
                    Arrays.asList(blocks),
                    active.expectedItemIds,
                    args.getOrDefault("replantItem", ""));
        } catch (IllegalArgumentException invalidResourceScope) {
            active.blockedDetail = "invalid resource actuator scope: "
                    + invalidResourceScope.getMessage();
            return;
        }
        if (!beginNextResourceTarget(active)) return;
    }

    private boolean beginNextResourceTarget(ActiveOperation operation) {
        operation.loadedHarvestSourceAbsent = false;
        operation.resourceExhausted = false;
        operation.resourceCandidateScanPending = false;
        ResourceActuationSession session = operation.resourceSession;
        if (session == null) return false;
        if (protectedAreas == null || client.player == null || client.world == null) {
            operation.blockedDetail = "typed resource-area authority is unavailable";
            session.block(operation.blockedDetail);
            actuators.clearResourceBreakScope(session.operationId());
            return false;
        }
        if (operation.resourceCandidateScan != null
                && !operation.resourceCandidateScanDimension.equals(currentDimension())) {
            operation.blockedDetail = "world changed during the bounded resource candidate scan";
            session.block(operation.blockedDetail);
            actuators.clearResourceBreakScope(session.operationId());
            return false;
        }
        List<ResourceActuationSession.Candidate> candidates =
                loadedResourceCandidates(operation);
        Optional<ResourceActuationSession.Target> selected =
                session.selectCandidate(candidates);
        if (selected.isEmpty()) {
            if (operation.resourceCandidateScanPending) {
                operation.progressExpected = false;
                operation.waitingDetail = "classifying loaded resource candidates "
                        + operation.resourceCandidateScan.inspected() + "/"
                        + operation.resourceCandidateScan.total() + "; next bounded page pending";
                actuators.clearResourceBreakScope(session.operationId());
                return false;
            }
            boolean hadCandidates = operation.resourceCandidateScan != null
                    && operation.resourceCandidateScan.total() > 0;
            String observedRejections = operation.resourceCandidateScanRejections.stream()
                    .limit(4)
                    .map(candidate -> candidate.blockId() + "@"
                            + candidate.coordinate().x() + ","
                            + candidate.coordinate().y() + ","
                            + candidate.coordinate().z() + " admitted="
                            + candidate.admitted() + " detail=" + candidate.detail())
                    .collect(java.util.stream.Collectors.joining("; "));
            if (operation.completeWhenResourceExhausted
                    && !operation.resourceAreaName.isEmpty()
                    && session.sourceKind() == ResourceActuationSession.SourceKind.HARVEST) {
                operation.resourceExhausted = true;
                operation.progressExpected = false;
                operation.waitingDetail = !hadCandidates
                        ? "no mature " + String.join(",", session.objectiveBlockIds())
                        + " remains in the observed part of farm '"
                        + operation.resourceAreaName + "'"
                        : "remaining matching cells in farm '" + operation.resourceAreaName
                        + "' were unchanged because none passed exact crop, seed, and route checks: "
                        + observedRejections;
                actuators.clearResourceBreakScope(session.operationId());
                return false;
            }
            operation.loadedHarvestSourceAbsent = !hadCandidates
                    && session.sourceKind() == ResourceActuationSession.SourceKind.HARVEST
                    && protectedAreas.observeResourceAreas(ProtectedAreaPolicy.AreaKind.HARVESTING).available();
            String kind = session.sourceKind() == ResourceActuationSession.SourceKind.MINE
                    ? "mining volume" : "harvesting footprint";
            ProtectedAreaPolicy.AreaKind areaKind =
                    session.sourceKind() == ResourceActuationSession.SourceKind.MINE
                            ? ProtectedAreaPolicy.AreaKind.MINING
                            : ProtectedAreaPolicy.AreaKind.HARVESTING;
            boolean implicitWilderness = protectedAreas.observeResourceAreas(areaKind)
                    .areas().isEmpty();
            String scope = implicitWilderness
                    ? "nearby unprotected wilderness"
                    : "an acknowledged " + kind;
            operation.blockedDetail = !hadCandidates
                    ? "no loaded objective block exists inside " + scope
                    : "no loaded objective block inside " + scope + " passed exact " + kind
                    + " classification and safety policy"
                    + (observedRejections.isBlank() ? "" : ": " + observedRejections);
            session.block(operation.blockedDetail);
            actuators.clearResourceBreakScope(session.operationId());
            return false;
        }
        ResourceActuationSession.Target target = selected.orElseThrow();
        operation.resourceCandidateScan = null;
        operation.resourceCandidateScanDimension = "";
        operation.resourceCandidateScanPending = false;
        operation.resourceCandidateScanRejections.clear();
        operation.progressExpected = true;
        activateResourceTarget(operation, target);
        return true;
    }

    /** Starts one exact target while leaving route construction to Baritone. */
    private void activateResourceTarget(
            ActiveOperation operation,
            ResourceActuationSession.Target target) {
        operation.resourceReplantDeadlineAt = 0L;
        operation.resourceDropDeadlineAt = 0L;
        operation.resourceDropRouteRefreshAt = 0L;
        operation.resourceDropOrigin = null;
        operation.resourceDropDestination = null;
        operation.resourceDropEntityObserved = false;
        operation.resourceApproachFeet = null;
        resetScopedResourceRouteLiveness(operation, System.currentTimeMillis());
        operation.lastEvent = null;
        lastPathEvent.set(null);
        operation.resourceCollectedBeforeDrop = Math.max(0,
                countTargetItems(operation.expectedItemIds)
                        - operation.startingTargetItemCount);
        installResourceBreakScope(operation, target);
        if (target.kind() == ResourceActuationSession.TargetKind.NATURAL_LOG) {
            startCommittedNaturalTree(operation, target);
            return;
        }
        operation.committedNaturalTreeTargets = Set.of();
        operation.committedNaturalTreeAim.clear();
        operation.committedNaturalTreeBlockId = "";
        operation.committedNaturalTreeStartedAt = 0L;
        operation.committedNaturalTreeLastRemoved = 0;
        operation.committedNaturalTreeLastActivityAt = 0L;
        if (target.kind() == ResourceActuationSession.TargetKind.FLOWER) {
            startNativeResourceTarget(operation, target.coordinate(), "flower");
            return;
        }
        routeToResourceTarget(operation, target);
    }

    /** Read-only source/frontier selection; movement remains the ordinary goto owner's job. */
    @Override
    public Optional<HarvestSearchGoal> findFlowerSearchGoal(
            Set<String> items, int originX, int originZ, Set<String> visitedAreas) {
        return findHarvestSearchGoal(supportedFlowerIds(items), originX, originZ, visitedAreas);
    }

    @Override
    public Optional<HarvestSearchGoal> findHarvestSearchGoal(
            Set<String> items, int originX, int originZ, Set<String> visitedAreas) {
        Set<String> sources = supportedHarvestSearchIds(items);
        if (sources.isEmpty() || client.player == null || client.world == null
                || protectedAreas == null || !client.isOnThread()) return Optional.empty();
        var geometry = protectedAreas.observeResourceAreas(ProtectedAreaPolicy.AreaKind.HARVESTING);
        if (!geometry.available()) return Optional.empty();
        String dimension = currentDimension();
        for (RawResourceCandidate candidate : loadedHarvestSearchCandidates(sources)) {
            BlockPos position = candidate.position();
            boolean flower = SupportedFlowerHarvest.supportedIds().contains(candidate.blockId());
            String areaId = dimension + (flower ? ":flower:" : ":log:") + position.getX() + ':'
                    + position.getY() + ':' + position.getZ();
            if (visitedAreas.contains(areaId) || !HuntTargetPolicy.insideSearchBoundary(
                    position.getX(), position.getZ(), originX, originZ,
                    DEFAULT_HUNT_SEARCH_RADIUS)) continue;
            var authority = protectedAreas.observeResourceAuthority(
                    ProtectedAreaPolicy.AreaKind.HARVESTING, dimension,
                    position.getX(), position.getY(), position.getZ());
            var observation = resourceObserver.observe(position,
                    flower ? ResourceStewardshipPolicy.Intent.HARVEST_FLOWER
                            : ResourceStewardshipPolicy.Intent.HARVEST_LOG);
            var context = new LoadedResourceClassifier.AdmissionContext(
                    protectedAreas.status().synchronizedPolicy(), authority.allowed(),
                    authority.code().equals("protected_overlap"), true, false, false,
                    routeSupportCustody.protects(routeSupportCoordinate(position)), Set.of());
            if (!ResourceStewardshipPolicy.decide(observation.facts(context)).allowed()
                    || !harvestSearchFootprintAdmitted(observation, flower)) continue;
            // A log is a solid block, not a feet destination. Reuse the existing
            // observed harvest stance; actual acquisition will revalidate the tree.
            BlockPos approach = flower ? position : findHarvestApproach(null, position, Set.of());
            if (approach == null) continue;
            return Optional.of(new HarvestSearchGoal(areaId, "minecraft:" + candidate.blockId(),
                    approach.getX(), approach.getY(), approach.getZ()));
        }
        List<LandHuntFrontierPolicy.Candidate> frontiers = loadedLandHuntFrontiers(
                originX, originZ, DEFAULT_HUNT_SEARCH_RADIUS).stream()
                .filter(candidate -> !visitedAreas.contains(dimension + ':' + candidate.id()))
                .toList();
        return new LandHuntFrontierPolicy.Session().select(frontiers,
                        client.player.isTouchingWater(), System.currentTimeMillis())
                .map(candidate -> new HarvestSearchGoal(dimension + ':' + candidate.id(), "",
                        candidate.x(), candidate.y(), candidate.z()));
    }

    private static Set<String> supportedFlowerIds(Set<String> items) {
        Set<String> supported = SupportedFlowerHarvest.supportedIds();
        return items.stream().filter(Objects::nonNull)
                .map(item -> item.startsWith("minecraft:") ? item.substring(10) : item)
                .filter(supported::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> supportedHarvestSearchIds(Set<String> items) {
        Set<String> supported = new HashSet<>(SupportedFlowerHarvest.supportedIds());
        supported.addAll(MinecraftResourceObserver.supportedNaturalLogIds());
        return items.stream().filter(Objects::nonNull)
                .map(item -> item.startsWith("minecraft:") ? item.substring(10) : item)
                .filter(supported::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private boolean harvestSearchFootprintAdmitted(
            LoadedResourceClassifier.Observation observation, boolean flower) {
        if (flower) return flowerFootprintAdmitted(observation);
        return observation.observationComplete() && !observation.connectedNaturalTreeLogs().isEmpty()
                && observation.connectedNaturalTreeLogs().stream().allMatch(point ->
                protectedAreas.observeResourceAuthority(ProtectedAreaPolicy.AreaKind.HARVESTING,
                        currentDimension(), point.x(), point.y(), point.z()).allowed());
    }

    private boolean loadedFullChunk(int blockX, int blockZ) {
        return client.world != null && client.world.getChunkManager().getChunk(
                blockX >> 4, blockZ >> 4, ChunkStatus.FULL, false) != null;
    }

    /**
     * Native palette scan covers the full declared loaded 64x48 observation.
     * No cell budget, matching-block cutoff or near-Y early exit may turn its
     * unexamined outer area into absence. This never starts MineProcess (whose
     * exploration goal uses legitMineYLevel, inappropriate for surface resources).
     */
    private List<RawResourceCandidate> loadedFlowerCandidates(Set<String> flowerIds) {
        return loadedHarvestSearchCandidates(flowerIds);
    }

    private List<RawResourceCandidate> loadedHarvestSearchCandidates(Set<String> sourceIds) {
        if (sourceIds.isEmpty() || client.player == null || client.world == null) return List.of();
        BlockOptionalMetaLookup filter = new BlockOptionalMetaLookup(sourceIds.stream()
                .sorted().map(id -> "minecraft:" + id).toArray(String[]::new));
        List<BlockPos> scanned = BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(
                baritone.getPlayerContext(), filter, Integer.MAX_VALUE, -1,
                RESOURCE_SEARCH_HORIZONTAL_RADIUS / 16 + 1);
        ArrayList<RawResourceCandidate> candidates = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (BlockPos position : scanned) {
            if (Math.abs((long) position.getX() - client.player.getBlockX()) > RESOURCE_SEARCH_HORIZONTAL_RADIUS
                    || Math.abs((long) position.getZ() - client.player.getBlockZ()) > RESOURCE_SEARCH_HORIZONTAL_RADIUS
                    || Math.abs((long) position.getY() - client.player.getBlockY()) > RESOURCE_SEARCH_VERTICAL_RADIUS
                    || !loadedFullChunk(position.getX(), position.getZ())
                    || !seen.add(position.asLong())) continue;
            BlockState state = client.world.getBlockState(position);
            String blockId = Registries.BLOCK.getId(state.getBlock()).getPath();
            if (!sourceIds.contains(blockId) || !resourcePerception.permitsBlock(position)
                    || MinecraftResourceObserver.supportedFlower(state).category()
                    == LoadedResourceClassifier.Category.TALL_FLOWER_UPPER) continue;
            candidates.add(new RawResourceCandidate(position.toImmutable(), blockId,
                    client.player.squaredDistanceTo(Vec3d.ofCenter(position))));
        }
        candidates.sort(Comparator.comparingDouble(RawResourceCandidate::distanceSquared)
                .thenComparingInt(candidate -> candidate.position().getX())
                .thenComparingInt(candidate -> candidate.position().getY())
                .thenComparingInt(candidate -> candidate.position().getZ()));
        return List.copyOf(candidates);
    }

    /**
     * Searches only loaded cells near Entity. An owner-selected typed area narrows the search;
     * with no area of that kind configured, a bounded nearby wilderness scope is used and every
     * candidate still passes synchronized protected-property and natural-resource classification.
     * Raw block-id candidates are ranked before the expensive tree/crop classifier runs.
     */
    private List<ResourceActuationSession.Candidate> loadedResourceCandidates(
            ActiveOperation operation) {
        ResourceActuationSession session = operation.resourceSession;
        if (session == null || protectedAreas == null
                || client.player == null || client.world == null) return List.of();
        ProtectedAreaPolicy.AreaKind areaKind =
                session.sourceKind() == ResourceActuationSession.SourceKind.MINE
                        ? ProtectedAreaPolicy.AreaKind.MINING
                        : ProtectedAreaPolicy.AreaKind.HARVESTING;
        ProtectedAreaClientState.ResourceAreasObservation geometry =
                protectedAreas.observeResourceAreas(areaKind);
        if (!geometry.available()) {
            operation.waitingDetail = geometry.detail();
            return List.of();
        }
        String dimension = currentDimension();
        if (operation.resourceCandidateScan == null) {
        int playerX = client.player.getBlockX();
        int playerY = client.player.getBlockY();
        int playerZ = client.player.getBlockZ();
        int verticalRadius = operation.resourceAreaName.isEmpty()
                ? RESOURCE_SEARCH_VERTICAL_RADIUS
                : MANAGED_FARM_SEARCH_VERTICAL_RADIUS;
        ArrayList<ResourceSearchColumn> columns = new ArrayList<>();
        List<ProtectedAreaPolicy.Area> searchAreas = geometry.areas();
        if (!operation.resourceAreaName.isEmpty()) {
            searchAreas = searchAreas.stream()
                    .filter(area -> area.name().equals(operation.resourceAreaName))
                    .filter(area -> ManagedFarmPolicy.areaFingerprint(area).equals(
                            operation.resourceAreaFingerprint))
                    .toList();
        }
        if (searchAreas.isEmpty()) {
            if (!operation.resourceAreaName.isEmpty()) {
                operation.waitingDetail = "named farm area is absent or changed";
                return List.of();
            }
            int minX = playerX - RESOURCE_SEARCH_HORIZONTAL_RADIUS;
            int maxX = playerX + RESOURCE_SEARCH_HORIZONTAL_RADIUS;
            int minZ = playerZ - RESOURCE_SEARCH_HORIZONTAL_RADIUS;
            int maxZ = playerZ + RESOURCE_SEARCH_HORIZONTAL_RADIUS;
            ProtectedAreaPolicy.Area wilderness = areaKind == ProtectedAreaPolicy.AreaKind.MINING
                    ? ProtectedAreaPolicy.Area.miningBetween(
                            "nearby-wilderness", dimension,
                            minX, Math.min(client.world.getTopYInclusive(),
                                    playerY + RESOURCE_SEARCH_VERTICAL_RADIUS), minZ,
                            maxX, Math.max(client.world.getBottomY(),
                                    playerY - RESOURCE_SEARCH_VERTICAL_RADIUS), maxZ)
                    : ProtectedAreaPolicy.Area.harvestingBetween(
                            "nearby-wilderness", dimension,
                            minX, minZ, maxX, maxZ);
            searchAreas = List.of(wilderness);
        }
        Set<String> nativeSources = session.sourceKind() == ResourceActuationSession.SourceKind.HARVEST
                ? supportedHarvestSearchIds(session.objectiveBlockIds()) : Set.of();
        boolean nativeSourcesOnly = !nativeSources.isEmpty()
                && nativeSources.containsAll(session.objectiveBlockIds());
        for (ProtectedAreaPolicy.Area area : nativeSourcesOnly ? List.<ProtectedAreaPolicy.Area>of() : searchAreas) {
            if (!area.dimension().equals(dimension)) continue;
            int minX = Math.max(area.minX(), playerX - RESOURCE_SEARCH_HORIZONTAL_RADIUS);
            int maxX = Math.min(area.maxX(), playerX + RESOURCE_SEARCH_HORIZONTAL_RADIUS);
            int minZ = Math.max(area.minZ(), playerZ - RESOURCE_SEARCH_HORIZONTAL_RADIUS);
            int maxZ = Math.min(area.maxZ(), playerZ + RESOURCE_SEARCH_HORIZONTAL_RADIUS);
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    long dx = (long) x - playerX;
                    long dz = (long) z - playerZ;
                    columns.add(new ResourceSearchColumn(area, x, z, dx * dx + dz * dz));
                }
            }
        }
        columns.sort(Comparator.comparingLong(ResourceSearchColumn::horizontalDistanceSquared)
                .thenComparing(column -> column.area().name())
                .thenComparingInt(ResourceSearchColumn::x)
                .thenComparingInt(ResourceSearchColumn::z));

        ArrayList<RawResourceCandidate> raw = new ArrayList<>();
        for (RawResourceCandidate candidate : loadedHarvestSearchCandidates(nativeSources)) {
            if (searchAreas.stream().anyMatch(area -> area.contains(dimension,
                    candidate.position().getX(), candidate.position().getZ()))) raw.add(candidate);
        }
        Set<Long> visited = new LinkedHashSet<>();
        int sampled = 0;
        search:
        for (ResourceSearchColumn column : columns) {
            if (!client.world.isChunkLoaded(column.x() >> 4, column.z() >> 4)) continue;
            int minimumY = column.area().kind() == ProtectedAreaPolicy.AreaKind.MINING
                    ? column.area().minY()
                    : Math.max(client.world.getBottomY(),
                    playerY - verticalRadius);
            int maximumY = column.area().kind() == ProtectedAreaPolicy.AreaKind.MINING
                    ? column.area().maxY()
                    : Math.min(client.world.getTopYInclusive(),
                    playerY + verticalRadius);
            minimumY = Math.max(minimumY, playerY - verticalRadius);
            maximumY = Math.min(maximumY, playerY + verticalRadius);
            for (int offset = 0; offset <= verticalRadius; offset++) {
                int[] ys = offset == 0
                        ? new int[] { playerY }
                        : new int[] { playerY + offset, playerY - offset };
                for (int y : ys) {
                    if (y < minimumY || y > maximumY) continue;
                    sampled++;
                    if (sampled > MAX_RESOURCE_SEARCH_CELLS) break search;
                    BlockPos position = new BlockPos(column.x(), y, column.z());
                    if (!visited.add(position.asLong())) continue;
                    BlockState state = client.world.getBlockState(position);
                    String blockId = Registries.BLOCK.getId(state.getBlock()).getPath();
                    if (!session.objectiveBlockIds().contains(blockId)) continue;
                    if (nativeSources.contains(blockId)) continue;
                    if (!resourcePerception.permitsBlock(position)) continue;
                    if (session.sourceKind() == ResourceActuationSession.SourceKind.HARVEST
                            && MinecraftResourceObserver.supportedFlower(state).category()
                            == LoadedResourceClassifier.Category.TALL_FLOWER_UPPER) continue;
                    double distance = client.player.squaredDistanceTo(
                            Vec3d.ofCenter(position));
                    raw.add(new RawResourceCandidate(
                            position.toImmutable(), blockId, distance));
                }
            }
        }
        raw.sort(Comparator.comparingDouble(RawResourceCandidate::distanceSquared)
                .thenComparingInt(candidate -> candidate.position().getX())
                .thenComparingInt(candidate -> candidate.position().getY())
                .thenComparingInt(candidate -> candidate.position().getZ()));
        operation.resourceCandidateScan = new ResourceActuationSession.CandidateScan<>(raw);
        operation.resourceCandidateScanDimension = dimension;
        operation.resourceCandidateScanRejections.clear();
        }

        Set<String> reservedForPolicy = session.reservedReplantItem()
                .filter(item -> findReplantHand(item) != null)
                .map(FabricBaritonePort::policyItemId)
                .stream().collect(java.util.stream.Collectors.toUnmodifiableSet());
        ArrayList<ResourceActuationSession.Candidate> classified = new ArrayList<>();
        for (RawResourceCandidate candidate : operation.resourceCandidateScan.nextPage(
                MAX_RESOURCE_CLASSIFIED_CANDIDATES,
                hint -> !operation.rejectedResourceTargets.contains(resourceCoordinate(hint.position())))) {
            // The snapshot remembers discovery order, never authority or block
            // state. A delayed page must pass the current loaded/perception and
            // exact objective checks before the ordinary classifier can admit it.
            BlockPos position = candidate.position();
            if (!client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)
                    || !resourcePerception.permitsBlock(position)) continue;
            String liveBlock = Registries.BLOCK.getId(client.world.getBlockState(position).getBlock()).getPath();
            if (!liveBlock.equals(candidate.blockId()) || !session.objectiveBlockIds().contains(liveBlock)) continue;
            ResourceStewardshipPolicy.Intent intent;
            LoadedResourceClassifier.Observation observation;
            if (session.sourceKind() == ResourceActuationSession.SourceKind.MINE) {
                intent = ResourceStewardshipPolicy.Intent.MINE;
                observation = resourceObserver.observe(candidate.position(), intent);
            } else {
                LoadedResourceClassifier.Observation crop = resourceObserver.observe(
                        candidate.position(), ResourceStewardshipPolicy.Intent.HARVEST_CROP);
                if (crop.targetCategory()
                        == LoadedResourceClassifier.Category.SUPPORTED_CROP) {
                    intent = ResourceStewardshipPolicy.Intent.HARVEST_CROP;
                    observation = crop;
                } else if (SupportedFlowerHarvest.isFlower(crop.targetCategory())) {
                    // A tall plant is one item source. Pin its lower half once, with both
                    // halves admitted below, rather than scheduling two drops from one plant.
                    if (crop.targetCategory() == LoadedResourceClassifier.Category.TALL_FLOWER_UPPER) continue;
                    intent = ResourceStewardshipPolicy.Intent.HARVEST_FLOWER;
                    observation = resourceObserver.observe(candidate.position(), intent);
                } else {
                    intent = ResourceStewardshipPolicy.Intent.HARVEST_LOG;
                    observation = resourceObserver.observe(candidate.position(), intent);
                }
            }
            ProtectedAreaPolicy.ResourceAuthority authority =
                    protectedAreas.observeResourceAuthority(
                            areaKind, dimension,
                            candidate.position().getX(), candidate.position().getY(),
                            candidate.position().getZ());
            boolean managedFarmCrop = intent == ResourceStewardshipPolicy.Intent.HARVEST_CROP
                    && managedFarmAreaPermits(operation, candidate.position());
            RouteSupportCustody.Coordinate routeCoordinate =
                    routeSupportCoordinate(candidate.position());
            boolean entrance = geometry.areas().stream()
                    .filter(area -> area.name().equals(authority.areaName()))
                    .anyMatch(area -> area.kind() == ProtectedAreaPolicy.AreaKind.MINING
                            && area.entranceX() == candidate.position().getX()
                            && area.entranceZ() == candidate.position().getZ()
                            && candidate.position().getY() >= area.entranceY() - 1
                            && candidate.position().getY() <= area.entranceY() + 1);
            LoadedResourceClassifier.AdmissionContext context =
                    new LoadedResourceClassifier.AdmissionContext(
                            protectedAreas.status().synchronizedPolicy(),
                            authority.allowed() || managedFarmCrop,
                            authority.code().equals("protected_overlap")
                                    && !managedFarmCrop,
                            true,
                            false,
                            entrance,
                            routeSupportCustody.protects(routeCoordinate),
                            reservedForPolicy);
            ResourceStewardshipPolicy.Decision decision =
                    ResourceStewardshipPolicy.decide(observation.facts(context));
            ResourceActuationSession.TargetKind targetKind = switch (intent) {
                case MINE -> ResourceActuationSession.TargetKind.MINE;
                case HARVEST_LOG -> ResourceActuationSession.TargetKind.NATURAL_LOG;
                case HARVEST_CROP -> ResourceActuationSession.TargetKind.MATURE_CROP;
                case HARVEST_FLOWER -> ResourceActuationSession.TargetKind.FLOWER;
                case HARVEST_TREE_ACCESS -> throw new IllegalStateException(
                        "tree access cells cannot become independent resource targets");
            };
            boolean interactionApproachable =
                    session.sourceKind() != ResourceActuationSession.SourceKind.HARVEST
                            || intent == ResourceStewardshipPolicy.Intent.HARVEST_FLOWER
                            || findHarvestApproach(
                            operation, candidate.position(), Set.of()) != null;
            NaturalTreeCommitment treeCommitment = intent
                    == ResourceStewardshipPolicy.Intent.HARVEST_LOG
                    ? admitNaturalTreeCommitment(
                            operation, areaKind, dimension, candidate.blockId(),
                            observation)
                    : NaturalTreeCommitment.notApplicable();
            boolean wholePlantAdmitted = intent != ResourceStewardshipPolicy.Intent.HARVEST_FLOWER
                    || flowerFootprintAdmitted(observation);
            boolean admitted = decision.allowed()
                    && wholePlantAdmitted
                    && treeCommitment.admitted()
                    && interactionApproachable;
            String detail = !decision.allowed()
                    ? decision.detail()
                    : !wholePlantAdmitted
                    ? "the complete flower footprint crosses unavailable or protected property"
                    : !treeCommitment.admitted()
                    ? treeCommitment.detail()
                    : !interactionApproachable
                    ? "no loaded, supported stance has an exact unobstructed interaction ray"
                    : decision.detail();
            ResourceActuationSession.Candidate observedCandidate = new ResourceActuationSession.Candidate(
                    resourceCoordinate(candidate.position()),
                    targetKind,
                    candidate.blockId(),
                    observation.requiredCropReplantItem(),
                    treeCommitment.coordinates(),
                    treeCommitment.accessBreaks(),
                    treeCommitment.accessObjectives(),
                    admitted,
                    candidate.distanceSquared(),
                    detail);
            classified.add(observedCandidate);
            if (!admitted && operation.resourceCandidateScanRejections.size() < 4)
                operation.resourceCandidateScanRejections.add(observedCandidate);
        }
        operation.resourceCandidateScanPending = operation.resourceCandidateScan.pending();
        return List.copyOf(classified);
    }

    private boolean managedFarmAreaPermits(
            ActiveOperation operation,
            BlockPos target) {
        if (operation == null || protectedAreas == null
                || operation.resourceAreaName.isEmpty()
                || operation.resourceAreaFingerprint.isEmpty()) return false;
        ProtectedAreaClientState.NamedResourceAreaObservation named =
                protectedAreas.observeNamedResourceArea(
                        ProtectedAreaPolicy.AreaKind.HARVESTING,
                        operation.resourceAreaName);
        if (!named.available() || named.area().isEmpty()) return false;
        ProtectedAreaPolicy.Area area = named.area().orElseThrow();
        return ManagedFarmPolicy.areaFingerprint(area).equals(
                operation.resourceAreaFingerprint)
                && area.contains(currentDimension(), target.getX(), target.getZ());
    }

    /** Target selection checks the whole plant before Baritone receives any objective. */
    private boolean flowerFootprintAdmitted(LoadedResourceClassifier.Observation observation) {
        if (!observation.observationComplete()) return false;
        return SupportedFlowerHarvest.footprint(observation.targetCategory(), observation.target()).stream()
                .allMatch(point -> protectedAreas.observeResourceAuthority(
                        ProtectedAreaPolicy.AreaKind.HARVESTING, currentDimension(),
                        point.x(), point.y(), point.z()).allowed());
    }

    /**
     * Converts one complete natural-tree observation into an immutable operation
     * commitment. Every connected log must retain the same exact block identity
     * and resource authority; crossing a player-confirmed protected boundary
     * rejects the whole tree instead of cutting it partially.
     */
    private NaturalTreeCommitment admitNaturalTreeCommitment(
            ActiveOperation operation,
            ProtectedAreaPolicy.AreaKind areaKind,
            String dimension,
            String exactBlockId,
            LoadedResourceClassifier.Observation observation) {
        if (observation.connectedNaturalTreeLogs().isEmpty()) {
            return NaturalTreeCommitment.denied(
                    "natural-tree classification retained no connected trunk identity");
        }
        LinkedHashSet<ResourceActuationSession.Coordinate> admitted =
                new LinkedHashSet<>();
        List<LoadedResourceClassifier.Point> ordered =
                observation.connectedNaturalTreeLogs().stream()
                        .sorted(Comparator.comparingInt(LoadedResourceClassifier.Point::y)
                                .thenComparingInt(LoadedResourceClassifier.Point::x)
                                .thenComparingInt(LoadedResourceClassifier.Point::z))
                        .toList();
        for (LoadedResourceClassifier.Point point : ordered) {
            BlockPos position = new BlockPos(point.x(), point.y(), point.z());
            ResourceActuationSession.Coordinate coordinate =
                    new ResourceActuationSession.Coordinate(
                            dimension, point.x(), point.y(), point.z());
            if (operation.rejectedResourceTargets.contains(coordinate)) {
                return NaturalTreeCommitment.denied(
                        "connected tree contains a previously retired target at "
                                + position.toShortString());
            }
            if (!client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
                return NaturalTreeCommitment.denied(
                        "connected tree crosses an unloaded chunk at "
                                + position.toShortString());
            }
            String liveBlockId = Registries.BLOCK.getId(
                    client.world.getBlockState(position).getBlock()).getPath();
            if (!liveBlockId.equals(exactBlockId)) {
                return NaturalTreeCommitment.denied(
                        "connected tree changed block identity at "
                                + position.toShortString());
            }
            ProtectedAreaPolicy.ResourceAuthority authority =
                    protectedAreas.observeResourceAuthority(
                            areaKind, dimension,
                            position.getX(), position.getY(), position.getZ());
            if (!authority.allowed()) {
                return NaturalTreeCommitment.denied(
                        "connected tree crosses denied resource authority at "
                                + position.toShortString() + ": " + authority.detail());
            }
            RouteSupportCustody.Coordinate routeCoordinate =
                    routeSupportCoordinate(position);
            if (routeSupportCustody.protects(routeCoordinate)) {
                return NaturalTreeCommitment.denied(
                        "connected tree contains the only known route support at "
                                + position.toShortString());
            }
            admitted.add(coordinate);
        }
        LinkedHashMap<ResourceActuationSession.Coordinate, String> accessBreaks =
                new LinkedHashMap<>();
        LinkedHashSet<ResourceActuationSession.Coordinate> accessObjectives =
                new LinkedHashSet<>();
        Set<LoadedResourceClassifier.Point> objectiveLeaves = Set.copyOf(
                selectNaturalTreeAccessCorridor(observation));
        List<LoadedResourceClassifier.Point> orderedLeaves =
                observation.connectedNaturalTreeLeaves().stream()
                        .sorted(Comparator.comparingInt(LoadedResourceClassifier.Point::y)
                                .thenComparingInt(LoadedResourceClassifier.Point::x)
                                .thenComparingInt(LoadedResourceClassifier.Point::z))
                        .toList();
        for (LoadedResourceClassifier.Point point : orderedLeaves) {
            BlockPos position = new BlockPos(point.x(), point.y(), point.z());
            ResourceActuationSession.Coordinate coordinate =
                    new ResourceActuationSession.Coordinate(
                            dimension, point.x(), point.y(), point.z());
            if (!client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
                return NaturalTreeCommitment.denied(
                        "natural canopy access crosses an unloaded chunk at "
                                + position.toShortString());
            }
            LoadedResourceClassifier.Observation leaf = resourceObserver.observe(
                    position, ResourceStewardshipPolicy.Intent.HARVEST_TREE_ACCESS);
            if (leaf.targetCategory() != LoadedResourceClassifier.Category.NATURAL_LEAF
                    || !leaf.family().equals(observation.family())) {
                return NaturalTreeCommitment.denied(
                        "natural canopy access changed identity at "
                                + position.toShortString());
            }
            ProtectedAreaPolicy.ResourceAuthority authority =
                    protectedAreas.observeResourceAuthority(
                            areaKind, dimension,
                            position.getX(), position.getY(), position.getZ());
            RouteSupportCustody.Coordinate routeCoordinate =
                    routeSupportCoordinate(position);
            LoadedResourceClassifier.AdmissionContext context =
                    new LoadedResourceClassifier.AdmissionContext(
                            protectedAreas.status().synchronizedPolicy(),
                            authority.allowed(),
                            authority.code().equals("protected_overlap"),
                            true,
                            false,
                            false,
                            routeSupportCustody.protects(routeCoordinate),
                            Set.of());
            ResourceStewardshipPolicy.Decision decision =
                    ResourceStewardshipPolicy.decide(leaf.facts(context, true));
            if (!decision.allowed()) {
                return NaturalTreeCommitment.denied(
                        "natural canopy access is denied at "
                                + position.toShortString() + ": " + decision.detail());
            }
            String liveBlockId = Registries.BLOCK.getId(
                    client.world.getBlockState(position).getBlock()).getPath();
            accessBreaks.put(coordinate, liveBlockId);
            if (objectiveLeaves.contains(point)) accessObjectives.add(coordinate);
        }
        return NaturalTreeCommitment.admitted(
                Set.copyOf(admitted), Map.copyOf(accessBreaks),
                Set.copyOf(accessObjectives));
    }

    /**
     * Selects only natural leaves which obstruct a direct sight corridor from
     * Entity's current eye to a committed trunk cell. These become explicit
     * targets in Baritone's one tree job; the rest of the initially proven
     * canopy remains incidental authority and is not a clearing objective.
     */
    private List<LoadedResourceClassifier.Point> selectNaturalTreeAccessCorridor(
            LoadedResourceClassifier.Observation observation) {
        if (client.player == null || client.world == null
                || observation.connectedNaturalTreeLeaves().isEmpty()) {
            return List.of();
        }
        LinkedHashMap<BlockPos, LoadedResourceClassifier.Point> leaves =
                new LinkedHashMap<>();
        for (LoadedResourceClassifier.Point point
                : observation.connectedNaturalTreeLeaves()) {
            leaves.put(new BlockPos(point.x(), point.y(), point.z()), point);
        }
        LinkedHashSet<BlockPos> logs = new LinkedHashSet<>();
        List<LoadedResourceClassifier.Point> orderedLogs =
                observation.connectedNaturalTreeLogs().stream()
                        .sorted(Comparator.comparingInt(LoadedResourceClassifier.Point::y)
                                .thenComparingInt(LoadedResourceClassifier.Point::x)
                                .thenComparingInt(LoadedResourceClassifier.Point::z))
                        .toList();
        for (LoadedResourceClassifier.Point point : orderedLogs) {
            logs.add(new BlockPos(point.x(), point.y(), point.z()));
        }

        Vec3d eye = client.player.getEyePos();
        LinkedHashSet<LoadedResourceClassifier.Point> selected =
                new LinkedHashSet<>();
        for (LoadedResourceClassifier.Point point : orderedLogs) {
            BlockPos target = new BlockPos(point.x(), point.y(), point.z());
            NaturalTreeAccessRay best = null;
            for (Vec3d aim : naturalTreeAimPoints(target)) {
                NaturalTreeAccessRay candidate = traceNaturalTreeAccessRay(
                        eye, aim, target, logs, leaves);
                if (candidate == null) continue;
                if (best == null
                        || candidate.leaves().size() < best.leaves().size()
                        || (candidate.leaves().size() == best.leaves().size()
                        && candidate.distance() < best.distance())) {
                    best = candidate;
                }
            }
            if (best != null) selected.addAll(best.leaves());
        }
        return selected.stream()
                .sorted(Comparator.comparingInt(LoadedResourceClassifier.Point::y)
                        .thenComparingInt(LoadedResourceClassifier.Point::x)
                        .thenComparingInt(LoadedResourceClassifier.Point::z))
                .toList();
    }

    private NaturalTreeAccessRay traceNaturalTreeAccessRay(
            Vec3d eye,
            Vec3d aim,
            BlockPos target,
            Set<BlockPos> committedLogs,
            Map<BlockPos, LoadedResourceClassifier.Point> admittedLeaves) {
        double distance = eye.distanceTo(aim);
        int samples = Math.max(1, (int) Math.ceil(distance * 32.0));
        LinkedHashSet<LoadedResourceClassifier.Point> crossedLeaves =
                new LinkedHashSet<>();
        BlockPos previous = null;
        for (int index = 1; index <= samples; index++) {
            double fraction = index / (double) samples;
            BlockPos position = BlockPos.ofFloored(
                    eye.x + (aim.x - eye.x) * fraction,
                    eye.y + (aim.y - eye.y) * fraction,
                    eye.z + (aim.z - eye.z) * fraction);
            if (position.equals(previous)) continue;
            previous = position;
            if (position.equals(target) || committedLogs.contains(position)) continue;
            LoadedResourceClassifier.Point leaf = admittedLeaves.get(position);
            if (leaf != null) {
                crossedLeaves.add(leaf);
                continue;
            }
            if (!client.world.isChunkLoaded(
                    position.getX() >> 4, position.getZ() >> 4)) return null;
            BlockState state = client.world.getBlockState(position);
            if (!state.getCollisionShape(client.world, position).isEmpty()) return null;
        }
        return new NaturalTreeAccessRay(List.copyOf(crossedLeaves), distance);
    }

    private static List<Vec3d> naturalTreeAimPoints(BlockPos target) {
        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();
        return List.of(
                new Vec3d(x + 0.50, y + 0.50, z + 0.50),
                new Vec3d(x + 0.01, y + 0.50, z + 0.50),
                new Vec3d(x + 0.99, y + 0.50, z + 0.50),
                new Vec3d(x + 0.50, y + 0.01, z + 0.50),
                new Vec3d(x + 0.50, y + 0.99, z + 0.50),
                new Vec3d(x + 0.50, y + 0.50, z + 0.01),
                new Vec3d(x + 0.50, y + 0.50, z + 0.99));
    }

    private void installResourceBreakScope(
            ActiveOperation operation,
            ResourceActuationSession.Target target) {
        ResourceStewardshipPolicy.Intent intent = switch (target.kind()) {
            case MINE -> ResourceStewardshipPolicy.Intent.MINE;
            case NATURAL_LOG -> ResourceStewardshipPolicy.Intent.HARVEST_LOG;
            case MATURE_CROP -> ResourceStewardshipPolicy.Intent.HARVEST_CROP;
            case FLOWER -> ResourceStewardshipPolicy.Intent.HARVEST_FLOWER;
        };
        Set<String> reserved = operation.resourceSession.reservedReplantItem()
                .filter(item -> findReplantHand(item) != null)
                .stream().collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<ResourceActuationSession.Coordinate> committedTree =
                target.kind() == ResourceActuationSession.TargetKind.NATURAL_LOG
                        ? operation.resourceSession.committedNaturalTreeTargets()
                        : Set.of();
        Map<ResourceActuationSession.Coordinate, String> committedTreeAccess =
                target.kind() == ResourceActuationSession.TargetKind.NATURAL_LOG
                        ? operation.resourceSession.committedNaturalTreeAccessBreaks()
                        : Map.of();
        actuators.installResourceBreakScope(new ResourceBreakScope(
                operation.resourceSession.operationId(),
                operation.resourceSession.sourceKind(),
                intent,
                target.coordinate(),
                target.blockId(),
                operation.resourceSession.objectiveBlockIds(),
                reserved,
                committedTree,
                committedTreeAccess,
                target.kind() == ResourceActuationSession.TargetKind.MATURE_CROP
                        ? operation.resourceAreaName : "",
                target.kind() == ResourceActuationSession.TargetKind.MATURE_CROP
                        ? operation.resourceAreaFingerprint : "",
                target.retainedNaturalTreeProof() || !committedTree.isEmpty()));
    }

    /** Hands one classified tree to a sequence of exact, stable Baritone break targets. */
    private void startCommittedNaturalTree(
            ActiveOperation operation,
            ResourceActuationSession.Target target) {
        Set<ResourceActuationSession.Coordinate> committed =
                operation.resourceSession.committedNaturalTreeTargets();
        Map<ResourceActuationSession.Coordinate, String> accessBreaks =
                operation.resourceSession.committedNaturalTreeAccessBreaks();
        Set<ResourceActuationSession.Coordinate> accessObjectives =
                operation.resourceSession.committedNaturalTreeAccessObjectives();
        if (committed.isEmpty()) {
            operation.blockedDetail =
                    "natural-tree classification did not retain an exact tree for Baritone";
            operation.resourceSession.block(operation.blockedDetail);
            actuators.clearResourceBreakScope(operation.resourceSession.operationId());
            return;
        }
        boolean sameCommitment = operation.committedNaturalTreeTargets.equals(committed)
                && operation.committedNaturalTreeBlockId.equals(target.blockId());
        if (!sameCommitment) operation.committedNaturalTreeAim.clear();
        operation.committedNaturalTreeTargets = committed;
        operation.committedNaturalTreeBlockId = target.blockId();
        operation.committedNaturalTreeStartedAt = System.currentTimeMillis();
        operation.committedNaturalTreeLastRemoved = 0;
        operation.committedNaturalTreeLastActivityAt =
                operation.committedNaturalTreeStartedAt;
        operation.resourceDropDeadlineAt = 0L;
        operation.resourceDropOrigin = block(committed.stream()
                .min(Comparator.comparingInt(ResourceActuationSession.Coordinate::y)
                        .thenComparingInt(ResourceActuationSession.Coordinate::x)
                .thenComparingInt(ResourceActuationSession.Coordinate::z))
                .orElseThrow());
        if (!startCommittedNaturalTreeTarget(operation, committed)) return;
        operation.waitingDetail = "Baritone owns one stable log target inside a committed tree of "
                + committed.size() + " log(s), " + accessBreaks.size()
                + " exact incidental canopy permissions, and "
                + accessObjectives.size() + " direct access objective(s)";
    }

    /**
     * Starts one single-block air schematic and never changes it while that log remains present.
     * Baritone still owns route finding, climbing, and the physical break; the operation-level
     * latch removes the multi-target builder oscillation that made the camera shake between logs.
     */
    private boolean startCommittedNaturalTreeTarget(
            ActiveOperation operation,
            Set<ResourceActuationSession.Coordinate> remaining) {
        ResourceActuationSession.Coordinate exact =
                operation.committedNaturalTreeAim.select(remaining).orElse(null);
        if (exact == null) return false;
        return startNativeResourceTarget(operation, exact, "log");
    }

    /** Existing exact Baritone break primitive, shared by a log and an admitted flower. */
    private boolean startNativeResourceTarget(ActiveOperation operation,
            ResourceActuationSession.Coordinate exact, String label) {
        CommittedTreeRemovalSchematic schematic =
                new CommittedTreeRemovalSchematic(Set.of(exact));
        operation.committedNaturalTreeStartedAt = System.currentTimeMillis();
        operation.committedNaturalTreeLastActivityAt =
                operation.committedNaturalTreeStartedAt;
        if (label.equals("flower")) {
            resetScopedResourceRouteLiveness(operation, operation.committedNaturalTreeStartedAt);
        }
        try {
            baritone.getMineProcess().cancel();
            stopExploreProcess();
            baritone.getCustomGoalProcess().setGoal(null);
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getBuilderProcess().onLostControl();
            baritone.getPathingBehavior().cancelEverything();
            baritone.getInputOverrideHandler().clearAllKeys();

            var settings = BaritoneAPI.getSettings();
            settings.allowBreak.value = true;
            settings.allowInventory.value = true;
            settings.autoTool.value = true;
            settings.buildIgnoreExisting.value = false;
            settings.buildInLayers.value = false;
            settings.startAtLayer.value = 0;
            settings.buildOnlySelection.value = false;
            settings.buildRepeat.value = new BlockPos(0, 0, 0);
            settings.buildRepeatCount.value = 1;
            settings.schematicOrientationX.value = false;
            settings.schematicOrientationY.value = false;
            settings.schematicOrientationZ.value = false;
            // One deterministic GoalBreak stance is less clever and materially more stable than
            // the composite side-or-above goal, which can alternate valid aim solutions per tick.
            settings.goalBreakFromAbove.value = false;

            baritone.getBuilderProcess().build(
                    (label.equals("log") ? "entity2-natural-tree-" : "entity2-native-flower-")
                            + operation.goal.missionId()
                            + '-' + exact.x() + '-' + exact.y() + '-' + exact.z(),
                    schematic,
                    schematic.origin());
        } catch (RuntimeException startFailure) {
            operation.blockedDetail = "Baritone could not start the committed " + label + " job: "
                    + Objects.requireNonNullElse(
                    startFailure.getMessage(), startFailure.getClass().getSimpleName());
            operation.resourceSession.block(operation.blockedDetail);
            actuators.clearResourceBreakScope(operation.resourceSession.operationId());
            return false;
        }
        operation.miningProcessStarts++;
        operation.sawActiveProcess = baritone.getBuilderProcess().isActive();
        operation.progressExpected = true;
        operation.waitingDetail = "Baritone owns stable exact " + label + " target "
                + exact.x() + ' ' + exact.y() + ' ' + exact.z();
        return true;
    }

    private void routeToResourceTarget(
            ActiveOperation operation,
            ResourceActuationSession.Target target) {
        routeToResourceTarget(operation, target, Set.of());
    }

    private void routeToResourceTarget(
            ActiveOperation operation,
            ResourceActuationSession.Target target,
            Set<BlockPos> excludedApproaches) {
        BlockPos position = block(target.coordinate());
        BlockPos approach = operation.resourceSession.sourceKind()
                == ResourceActuationSession.SourceKind.HARVEST
                ? findHarvestApproach(operation, position, excludedApproaches)
                : null;
        if (operation.resourceSession.sourceKind()
                == ResourceActuationSession.SourceKind.HARVEST
                && approach == null) {
            operation.blockedDetail = "no loaded, supported interaction stance can see exact "
                    + target.blockId() + " target at "
                    + position.getX() + ' ' + position.getY() + ' ' + position.getZ();
            operation.resourceSession.block(operation.blockedDetail);
            actuators.clearResourceBreakScope(operation.resourceSession.operationId());
            operation.resourceApproachFeet = null;
            return;
        }
        operation.resourceApproachFeet = approach == null ? null : approach.toImmutable();
        BaritoneAPI.getSettings().exploreForBlocks.value = false;
        if (approach != null && client.player != null
                && client.player.getBlockPos().equals(approach)) {
            operation.progressExpected = true;
            operation.waitingDetail = "verified interaction stance already occupied for "
                    + target.kind().name().toLowerCase(Locale.ROOT)
                    + " at " + position.getX() + ' ' + position.getY() + ' '
                    + position.getZ();
            return;
        }
        if (approach != null
                && beginManagedFarmIngress(operation, approach, System.currentTimeMillis())) {
            return;
        }
        try {
            baritone.getMineProcess().cancel();
            stopExploreProcess();
            if (operation.resourceSession.sourceKind()
                    == ResourceActuationSession.SourceKind.MINE) {
                // Baritone chooses any legal interaction stance around the exact block. Outside
                // player-selected protection it retains normal down-mining, diagonal movement,
                // bridging and pillaring; the coordinate-level actuator is the final safety gate.
                baritone.getCustomGoalProcess().setGoalAndPath(
                        new GoalGetToBlock(position));
            } else {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(approach));
            }
        } catch (RuntimeException routeFailure) {
            operation.blockedDetail = "could not route to the exact resource target: "
                    + routeFailure.getMessage();
            operation.resourceSession.block(operation.blockedDetail);
            actuators.clearResourceBreakScope(operation.resourceSession.operationId());
            return;
        }
        operation.miningProcessStarts++;
        operation.sawActiveProcess = baritone.getCustomGoalProcess().isActive()
                || baritone.getPathingBehavior().isPathing();
        operation.progressExpected = true;
        operation.waitingDetail = "routing to admitted "
                + target.kind().name().toLowerCase(Locale.ROOT)
                + " at " + position.getX() + ' ' + position.getY() + ' ' + position.getZ();
        if (operation.resourceSession.sourceKind()
                == ResourceActuationSession.SourceKind.MINE) {
            operation.waitingDetail += " through Baritone's nearest legal interaction stance";
        }
        if (approach != null) {
            operation.waitingDetail += " from verified interaction stance "
                    + approach.getX() + ' ' + approach.getY() + ' ' + approach.getZ();
        }
    }

    /**
     * Starts one loaded, mutation-free crossing only after ordinary farm travel
     * has reached an aligned exterior approach. This avoids asking a fresh
     * asynchronous A* snapshot to rediscover a six-block path across the chunk
     * edge that contains the already-observed field.
     */
    private boolean beginManagedFarmIngress(
            ActiveOperation operation,
            BlockPos harvestApproach,
            long nowMillis) {
        if (operation == null || operation.managedFarmIngress != null
                || client.player == null || client.world == null
                || protectedAreas == null || operation.resourceAreaName.isEmpty()
                || !managedFarmAreaPermits(operation, harvestApproach)) {
            return operation != null && operation.managedFarmIngress != null;
        }
        ProtectedAreaClientState.NamedResourceAreaObservation named =
                protectedAreas.observeNamedResourceArea(
                        ProtectedAreaPolicy.AreaKind.HARVESTING,
                        operation.resourceAreaName);
        if (!named.available() || named.area().isEmpty()) return false;
        ProtectedAreaPolicy.Area area = named.area().orElseThrow();
        BlockPos feet = client.player.getBlockPos().toImmutable();
        if (area.contains(currentDimension(), feet.getX(), feet.getZ())) return false;

        ArrayList<ManagedFarmIngressCandidate> candidates = new ArrayList<>();
        ArrayList<String> observedPortals = new ArrayList<>();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            collectManagedFarmIngressCandidate(
                    operation, area, harvestApproach, feet,
                    new BlockPos(x, harvestApproach.getY(), area.minZ()),
                    candidates, observedPortals);
            if (area.maxZ() != area.minZ()) {
                collectManagedFarmIngressCandidate(
                        operation, area, harvestApproach, feet,
                        new BlockPos(x, harvestApproach.getY(), area.maxZ()),
                        candidates, observedPortals);
            }
        }
        for (int z = area.minZ() + 1; z < area.maxZ(); z++) {
            collectManagedFarmIngressCandidate(
                    operation, area, harvestApproach, feet,
                    new BlockPos(area.minX(), harvestApproach.getY(), z),
                    candidates, observedPortals);
            if (area.maxX() != area.minX()) {
                collectManagedFarmIngressCandidate(
                        operation, area, harvestApproach, feet,
                        new BlockPos(area.maxX(), harvestApproach.getY(), z),
                        candidates, observedPortals);
            }
        }
        ManagedFarmIngressCandidate selected = candidates.stream()
                .min(Comparator.comparingDouble(candidate ->
                        client.player.squaredDistanceTo(Vec3d.ofCenter(candidate.gate()))))
                .orElse(null);
        if (selected == null) {
            int dx = Math.max(0, Math.max(area.minX() - feet.getX(), feet.getX() - area.maxX()));
            int dz = Math.max(0, Math.max(area.minZ() - feet.getZ(), feet.getZ() - area.maxZ()));
            if (!operation.managedFarmIngressDiagnosticLogged && Math.max(dx, dz) <= 8) {
                operation.managedFarmIngressDiagnosticLogged = true;
                logger.info(
                        "Managed farm {} found no eligible open boundary gate from {} toward {}; "
                                + "observed portals={}",
                        operation.resourceAreaName, feet, harvestApproach,
                        observedPortals.isEmpty() ? "none" : observedPortals);
            }
            return false;
        }

        operation.managedFarmIngress = ManagedFarmIngressPolicy.begin(
                currentDimension(),
                selected.portal().x(), selected.portal().y(), selected.portal().z(),
                selected.portal().blockId(), selected.portal().stableFingerprint(),
                selected.axisX(), selected.axisZ(), selected.outsideSign(), nowMillis);
        operation.managedFarmIngressInputsForced = false;
        pauseForDoorPassage(operation,
                "taking bounded movement custody through the loaded open gate for farm '"
                        + operation.resourceAreaName + "'");
        operation.progressExpected = true;
        logger.info("Managed farm {} using verified open gate {} from {} to interior {}",
                operation.resourceAreaName, selected.gate(), feet, selected.inside());
        return true;
    }

    private void collectManagedFarmIngressCandidate(
            ActiveOperation operation,
            ProtectedAreaPolicy.Area area,
            BlockPos harvestApproach,
            BlockPos feet,
            BlockPos gate,
            List<ManagedFarmIngressCandidate> candidates,
            List<String> observedPortals) {
        if (!client.world.getChunkManager().isChunkLoaded(
                gate.getX() >> 4, gate.getZ() >> 4)) return;
        MinecraftActuatorGateway.PortalState portal =
                actuators.inspectPortal(gate).orElse(null);
        if (portal == null) return;
        if (!portal.open() || !portal.kind().equals("fence_gate")
                || portal.normalY() != 0) {
            observedPortals.add(gate + " kind=" + portal.kind()
                    + " open=" + portal.open() + " normal="
                    + portal.normalX() + ',' + portal.normalY() + ',' + portal.normalZ());
            return;
        }

        int axisX;
        int axisZ;
        int outsideSign;
        if (gate.getX() == area.minX() && portal.normalX() != 0) {
            axisX = 1;
            axisZ = 0;
            outsideSign = -1;
        } else if (gate.getX() == area.maxX() && portal.normalX() != 0) {
            axisX = 1;
            axisZ = 0;
            outsideSign = 1;
        } else if (gate.getZ() == area.minZ() && portal.normalZ() != 0) {
            axisX = 0;
            axisZ = 1;
            outsideSign = -1;
        } else if (gate.getZ() == area.maxZ() && portal.normalZ() != 0) {
            axisX = 0;
            axisZ = 1;
            outsideSign = 1;
        } else {
            observedPortals.add(gate + " facing=" + portal.normalX() + ','
                    + portal.normalZ() + " is not normal to this boundary");
            return;
        }
        BlockPos inside = gate.add(-outsideSign * axisX, 0, -outsideSign * axisZ);
        boolean insideArea = area.contains(
                currentDimension(), inside.getX(), inside.getZ());
        boolean approachArea = area.contains(
                currentDimension(), harvestApproach.getX(), harvestApproach.getZ());
        boolean insideClear = clearSupportedPortalStance(inside);
        boolean corridorSafe = managedFarmIngressCorridorSafe(
                feet, gate, inside, axisX, axisZ, outsideSign);
        observedPortals.add(gate + " open fence gate inside=" + inside
                + " insideArea=" + insideArea + " approachArea=" + approachArea
                + " insideClear=" + insideClear
                + (insideClear ? "" : " [" + describePortalStance(inside) + "]")
                + " corridorSafe=" + corridorSafe);
        if (!insideArea || !approachArea || !insideClear || !corridorSafe) return;
        candidates.add(new ManagedFarmIngressCandidate(
                gate.toImmutable(), inside.toImmutable(), portal,
                axisX, axisZ, outsideSign));
    }

    private boolean managedFarmIngressCorridorSafe(
            BlockPos feet,
            BlockPos gate,
            BlockPos inside,
            int axisX,
            int axisZ,
            int outsideSign) {
        if (feet.getY() != gate.getY()) return false;
        int lateralFeet = axisX == 0 ? feet.getX() : feet.getZ();
        int lateralGate = axisX == 0 ? gate.getX() : gate.getZ();
        if (lateralFeet != lateralGate) return false;
        int axialFeet = axisX == 0 ? feet.getZ() : feet.getX();
        int axialGate = axisX == 0 ? gate.getZ() : gate.getX();
        int axialInside = axisX == 0 ? inside.getZ() : inside.getX();
        int currentSide = (axialFeet - axialGate) * outsideSign;
        if (currentSide > 0) {
            if (Math.abs(axialFeet - axialGate) > 8) return false;
        } else if (axialFeet != axialGate && axialFeet != axialInside) {
            return false;
        }
        int step = Integer.compare(axialInside, axialFeet);
        for (int axial = axialFeet; ; axial += step) {
            BlockPos cell = axisX == 0
                    ? new BlockPos(lateralGate, gate.getY(), axial)
                    : new BlockPos(axial, gate.getY(), lateralGate);
            if (!clearSupportedPortalStance(cell)) return false;
            if (axial == axialInside) break;
        }
        Box destination = client.player.getBoundingBox().offset(
                inside.getX() + 0.5D - client.player.getX(),
                inside.getY() - client.player.getY(),
                inside.getZ() + 0.5D - client.player.getZ());
        return client.world.getOtherEntities(
                client.player,
                client.player.getBoundingBox().union(destination),
                entity -> entity instanceof LivingEntity && entity.isAlive()).isEmpty();
    }

    private DoorPassageOutcome maintainManagedFarmIngress(
            ActiveOperation operation,
            long nowMillis) {
        if (operation == null || operation.managedFarmIngress == null) return null;
        ManagedFarmIngressPolicy.Session session = operation.managedFarmIngress;
        BlockPos gate = new BlockPos(session.gateX(), session.gateY(), session.gateZ());
        MinecraftActuatorGateway.PortalState portal = client.world == null
                ? null : actuators.inspectPortal(gate).orElse(null);
        boolean sameGate = portal != null
                && portal.kind().equals("fence_gate")
                && portal.blockId().equals(session.blockId())
                && DoorPassageSession.samePortalFingerprint(portal.stableFingerprint(), session.stableFingerprint());
        BlockPos inside = new BlockPos(
                session.insideX(), session.gateY(), session.insideZ());
        boolean corridorSafe = client.player != null && client.world != null
                && managedFarmIngressCorridorSafe(
                        client.player.getBlockPos(), gate, inside,
                        session.axisX(), session.axisZ(), session.outsideSign());
        ManagedFarmIngressPolicy.Decision decision = ManagedFarmIngressPolicy.decide(
                session,
                new ManagedFarmIngressPolicy.Observation(
                        currentDimension(),
                        client.player != null && client.world != null,
                        sameGate,
                        portal != null && portal.open(),
                        corridorSafe,
                        aquaticAuthorityValid(operation, nowMillis),
                        client.player != null && client.player.horizontalCollision,
                        client.player == null ? 0.0D : client.player.getX(),
                        client.player == null ? 0.0D : client.player.getZ(),
                        nowMillis));
        return switch (decision.action()) {
            case WAIT -> {
                releaseManagedFarmIngress(
                        operation, MovementFrameActuator.Cleanup.OWNER_TRANSITION);
                operation.progressExpected = false;
                operation.waitingDetail = decision.detail();
                yield new DoorPassageOutcome(true, false, decision.detail());
            }
            case MOVE -> {
                clearAquaticMovementOverrides();
                neutralizeAquaticPhysicalKeys();
                operation.managedFarmIngressInputsForced =
                        submitManagedFarmIngressFrame(
                                new MovementFrame(
                                        true, false, false, false,
                                        false, false, false,
                                        decision.yaw(), 0.0F),
                                nowMillis);
                if (!operation.managedFarmIngressInputsForced) {
                    operation.progressExpected = false;
                    operation.waitingDetail =
                            "farm ingress movement frame has not been accepted yet";
                    yield new DoorPassageOutcome(
                            true, false, operation.waitingDetail);
                }
                operation.progressExpected = true;
                operation.waitingDetail = decision.detail();
                yield new DoorPassageOutcome(true, false, decision.detail());
            }
            case COMPLETE -> {
                releaseManagedFarmIngress(
                        operation, MovementFrameActuator.Cleanup.OWNER_TRANSITION);
                operation.managedFarmIngress = null;
                resumeAfterDoorPassage(operation, decision.detail());
                yield null;
            }
            case BLOCKED -> {
                releaseManagedFarmIngress(operation, MovementFrameActuator.Cleanup.CANCEL);
                operation.managedFarmIngress = null;
                yield blockDoor(operation, decision.detail());
            }
        };
    }

    private void releaseManagedFarmIngress(
            ActiveOperation operation,
            MovementFrameActuator.Cleanup cleanup) {
        if (operation == null || !operation.managedFarmIngressInputsForced) return;
        clearAquaticMovementOverrides();
        cancelAquaticFrame(cleanup);
        neutralizeAquaticPhysicalKeys();
        operation.managedFarmIngressInputsForced = false;
    }

    private boolean submitManagedFarmIngressFrame(
            MovementFrame frame,
            long nowMillis) {
        MovementAuthority binding = movementAuthority;
        if (binding == null) return false;
        try {
            movement.submit(
                    binding.authority(), binding.parent(), binding.action(), frame,
                    binding.clientTick(), nowMillis);
            return true;
        } catch (IllegalStateException rejected) {
            logger.debug("Managed-farm ingress frame rejected at the exact lease boundary: {}",
                    rejected.getMessage());
            return false;
        }
    }

    private ResourceActuationSession.Coordinate resourceCoordinate(BlockPos position) {
        return new ResourceActuationSession.Coordinate(
                currentDimension(), position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos block(ResourceActuationSession.Coordinate coordinate) {
        return new BlockPos(coordinate.x(), coordinate.y(), coordinate.z());
    }

    private static String policyItemId(String itemId) {
        String normalized = Objects.requireNonNullElse(itemId, "").trim();
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private Status pollScopedResourceOperation(
            ActiveOperation operation,
            long nowMillis) {
        ResourceActuationSession session = operation.resourceSession;
        if (operation.resourceExhausted) {
            operation.progressExpected = false;
            return completeStatus(
                    operation,
                    operation.waitingDetail == null || operation.waitingDetail.isBlank()
                            ? "observed farm segment is complete"
                            : operation.waitingDetail,
                    0);
        }
        if (session.phase() == ResourceActuationSession.Phase.BLOCKED) {
            operation.blockedDetail = session.blockedDetail();
            return status(operation, State.BLOCKED,
                    operation.blockedDetail, Double.NaN);
        }
        if (client.player == null || client.world == null
                || client.interactionManager == null) {
            return status(operation, State.CALCULATING,
                    "waiting for loaded resource actuator context", Double.NaN);
        }
        Optional<ResourceActuationSession.Target> committedTarget = session.target();
        if (committedTarget.isPresent()
                && committedTarget.orElseThrow().kind()
                == ResourceActuationSession.TargetKind.NATURAL_LOG
                && !operation.committedNaturalTreeTargets.isEmpty()) {
            return pollCommittedNaturalTree(
                    operation, committedTarget.orElseThrow(), nowMillis);
        }
        if (session.phase() == ResourceActuationSession.Phase.AWAITING_DROP) {
            return pollScopedResourceDrop(operation, nowMillis);
        }
        String missingTool = firstMissingTargetTool(operation.miningBlocks);
        if (missingTool != null) {
            pauseMiningForTool(operation, missingTool);
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, Double.NaN);
        }
        Optional<ResourceActuationSession.Target> targetOptional = session.target();
        if (targetOptional.isEmpty()) {
            if (!beginNextResourceTarget(operation)) {
                if (operation.resourceCandidateScanPending)
                    return status(operation, State.CALCULATING, operation.waitingDetail, Double.NaN);
                if (operation.resourceExhausted) {
                    return completeStatus(operation, operation.waitingDetail, 0);
                }
                return status(operation, State.BLOCKED,
                        operation.blockedDetail, Double.NaN);
            }
            targetOptional = session.target();
        }
        ResourceActuationSession.Target target = targetOptional.orElseThrow();
        // A paged scan can commit/start the native tree Builder in this very tick.
        // Dispatch that new owner now: falling through to generic excavation would
        // cancel its Builder, then next tick falsely report the tree as stopped.
        if (target.kind() == ResourceActuationSession.TargetKind.NATURAL_LOG
                && !operation.committedNaturalTreeTargets.isEmpty()) {
            return pollCommittedNaturalTree(operation, target, nowMillis);
        }
        if (!target.coordinate().dimension().equals(currentDimension())) {
            session.block("world changed before the exact resource target completed");
            actuators.clearResourceBreakScope(session.operationId());
            operation.blockedDetail = session.blockedDetail();
            return status(operation, State.BLOCKED,
                    operation.blockedDetail, Double.NaN);
        }
        BlockPos position = block(target.coordinate());
        if (!client.world.getChunkManager().isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
            operation.waitingDetail = "waiting for the pinned resource target chunk";
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distanceTo(position));
        }

        if (session.phase() == ResourceActuationSession.Phase.REPLANTING
                || session.phase()
                == ResourceActuationSession.Phase.VERIFYING_REPLANT) {
            return pollResourceReplant(operation, target, position, nowMillis);
        }

        String liveBlock = Registries.BLOCK.getId(
                client.world.getBlockState(position).getBlock()).getPath();
        if (!liveBlock.equals(target.blockId())) {
            if (target.kind() == ResourceActuationSession.TargetKind.FLOWER) stopCommittedNaturalTreeBuilder();
            actuators.cancelBlockBreak(resourceBreakOperation(operation));
            session.observeTargetRemoved();
            if (session.phase() == ResourceActuationSession.Phase.REPLANTING) {
                operation.resourceReplantDeadlineAt = nowMillis
                        + RESOURCE_REPLANT_TIMEOUT_MILLIS;
                return pollResourceReplant(operation, target, position, nowMillis);
            }
            return finishScopedResourceTarget(operation, position, nowMillis);
        }

        installResourceBreakScope(operation, target);
        if (target.kind() == ResourceActuationSession.TargetKind.FLOWER) {
            return pollNativeFlower(operation, target, position, nowMillis);
        }
        double distance = distanceTo(position);
        double reach = Math.max(3.0, client.player.getBlockInteractionRange() - 0.2);
        BlockPos expectedApproach = operation.resourceApproachFeet;
        BlockHitResult hit = raycastResource(position, reach);
        boolean exactRay = hit != null && hit.getBlockPos().equals(position);
        if (session.sourceKind() == ResourceActuationSession.SourceKind.MINE
                && session.phase() == ResourceActuationSession.Phase.ROUTING) {
            Status routeFailure = pollScopedResourceRouteLiveness(
                    operation, target, position, exactRay, nowMillis);
            if (routeFailure != null) return routeFailure;
        }
        ResourceInteractionReadiness.Decision readiness =
                ResourceInteractionReadiness.decide(
                        expectedApproach != null,
                        expectedApproach != null
                                && client.player.getBlockPos().equals(expectedApproach),
                        exactRay);
        if (readiness
                == ResourceInteractionReadiness.Decision.ROUTE_TO_PINNED_STANCE) {
            if (!baritone.getCustomGoalProcess().isActive()
                    && !baritone.getPathingBehavior().isPathing()
                    && baritone.getPathingBehavior().getInProgress().isEmpty()) {
                routeToResourceTarget(operation, target);
                if (operation.resourceSession.phase()
                        == ResourceActuationSession.Phase.BLOCKED) {
                    return status(operation, State.BLOCKED,
                            operation.blockedDetail, distance);
                }
                expectedApproach = operation.resourceApproachFeet;
            }
            if (expectedApproach == null) {
                operation.blockedDetail = "harvest route lost its verified interaction stance for "
                        + position.getX() + ' ' + position.getY() + ' ' + position.getZ();
                operation.resourceSession.block(operation.blockedDetail);
                return status(operation, State.BLOCKED,
                        operation.blockedDetail, distance);
            }
            operation.waitingDetail = "approaching verified interaction stance "
                    + expectedApproach.getX() + ' '
                    + expectedApproach.getY() + ' '
                    + expectedApproach.getZ()
                    + " for exact target " + position.getX() + ' '
                    + position.getY() + ' ' + position.getZ();
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distance);
        }
        if (readiness
                == ResourceInteractionReadiness.Decision.RELOCATE_PINNED_STANCE) {
            stopResourceWaypoint();
            BlockPos priorFeet = client.player.getBlockPos().toImmutable();
            routeToResourceTarget(operation, target, Set.of(priorFeet));
            if (operation.resourceSession.phase()
                    == ResourceActuationSession.Phase.BLOCKED) {
                return status(operation, State.BLOCKED,
                        operation.blockedDetail, distance);
            }
            operation.waitingDetail = "relocating after the current interaction ray hit "
                    + (hit == null ? "no block" : hit.getBlockPos().toShortString())
                    + " instead of exact target " + position.toShortString();
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distance);
        }
        if (readiness
                == ResourceInteractionReadiness.Decision.CONTINUE_EXCAVATION) {
            if (!baritone.getCustomGoalProcess().isActive()
                    && !baritone.getPathingBehavior().isPathing()
                    && baritone.getPathingBehavior().getInProgress().isEmpty()) {
                routeToResourceTarget(operation, target);
            }
            operation.waitingDetail = "excavating stepped access to exact target "
                    + position.toShortString()
                    + "; current ray hit "
                    + (hit == null ? "no block" : hit.getBlockPos().toShortString());
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distance);
        }
        if (readiness != ResourceInteractionReadiness.Decision.INTERACT) {
            throw new IllegalStateException("unhandled resource readiness " + readiness);
        }
        stopResourceWaypoint();
        hit = aimAndRaycastResource(position, reach);
        if (hit == null || !hit.getBlockPos().equals(position)) {
            operation.waitingDetail = "final interaction alignment changed before exact target "
                    + position.toShortString();
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distance);
        }
        if (session.phase() == ResourceActuationSession.Phase.ROUTING) {
            operation.resourceCollectedBeforeDrop = Math.max(0,
                    countTargetItems(operation.expectedItemIds)
                            - operation.startingTargetItemCount);
        }
        session.beginBreak();
        MinecraftActuatorGateway.BreakFrame frame = actuators.tickBlockBreak(
                resourceBreakOperation(operation), position, hit.getSide());
        rememberMiningBreakTarget(operation, position);
        if (!frame.accepted()) {
            operation.waitingDetail = "Minecraft has not accepted the pinned resource break yet";
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distance);
        }
        operation.completedWorkUnits++;
        operation.progressExpected = true;
        operation.waitingDetail = "breaking exact admitted resource target; stage "
                + frame.progress();
        return status(operation, State.EXECUTING,
                operation.waitingDetail, distance);
    }

    /** Observe the native job only: no second camera, movement, or break actuator. */
    private Status pollNativeFlower(ActiveOperation operation, ResourceActuationSession.Target target,
            BlockPos position, long nowMillis) {
        LoadedResourceClassifier.Observation current = resourceObserver.observe(position,
                ResourceStewardshipPolicy.Intent.HARVEST_FLOWER);
        if (!current.observationComplete() || current.material() != ResourceStewardshipPolicy.Material.FLOWER
                || !flowerFootprintAdmitted(current)) {
            stopCommittedNaturalTreeBuilder();
            return retireCurrentMiningTarget(operation, target,
                    "the exact flower or its complete authorized footprint changed", nowMillis);
        }
        BlockPos feet = client.player.getBlockPos();
        if (!feet.equals(operation.resourceRouteLastFeet)) {
            operation.resourceRouteLastFeet = feet.toImmutable();
            operation.resourceRouteLastProgressAt = nowMillis;
        }
        long age = nowMillis - operation.committedNaturalTreeStartedAt;
        boolean stalled = nowMillis - operation.resourceRouteLastProgressAt > RESOURCE_TARGET_ROUTE_STALL_MILLIS;
        if (operation.failed() || stalled || (age > 1_500L
                && (!baritone.getBuilderProcess().isActive() || baritone.getBuilderProcess().isPaused()))) {
            stopCommittedNaturalTreeBuilder();
            return retireCurrentMiningTarget(operation, target,
                    "Baritone could not finish the unchanged exact flower target", nowMillis);
        }
        operation.progressExpected = true;
        operation.waitingDetail = "Baritone harvesting exact " + target.blockId() + " at "
                + position.toShortString() + "; waiting for observed plant removal and drop";
        return status(operation, State.EXECUTING, operation.waitingDetail, distanceTo(position));
    }

    /** Observes Baritone's one physical tree job without competing for movement or attack. */
    private Status pollCommittedNaturalTree(
            ActiveOperation operation,
            ResourceActuationSession.Target target,
            long nowMillis) {
        ResourceActuationSession session = operation.resourceSession;
        if (!target.coordinate().dimension().equals(currentDimension())) {
            session.block("world changed before the committed natural tree completed");
            stopCommittedNaturalTreeBuilder();
            actuators.clearResourceBreakScope(session.operationId());
            operation.blockedDetail = session.blockedDetail();
            return status(operation, State.BLOCKED,
                    operation.blockedDetail, Double.NaN);
        }

        int remaining = 0;
        int removed = 0;
        LinkedHashSet<ResourceActuationSession.Coordinate> liveRemaining =
                new LinkedHashSet<>();
        for (ResourceActuationSession.Coordinate coordinate
                : operation.committedNaturalTreeTargets) {
            // Baritone may pillar on an already harvested trunk cell. The tree
            // objective is removal of the original log, not keeping that cell
            // air forever. Preserve verified completion through handoffs too.
            if (operation.committedNaturalTreeAim.wasRemoved(coordinate)) {
                removed++;
                continue;
            }
            BlockPos position = block(coordinate);
            if (!client.world.getChunkManager().isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
                operation.waitingDetail =
                        "waiting for Baritone's committed tree chunk to remain loaded";
                return status(operation, State.EXECUTING,
                        operation.waitingDetail, distanceTo(block(target.coordinate())));
            }
            BlockState state = client.world.getBlockState(position);
            String blockId = Registries.BLOCK.getId(state.getBlock()).getPath();
            if (blockId.equals(operation.committedNaturalTreeBlockId)) {
                remaining++;
                liveRemaining.add(coordinate);
            } else if (state.isAir()) {
                operation.committedNaturalTreeAim.observedRemoved(coordinate);
                resourcePerception.rememberProducedDrops(operation.goal.missionId() + ":" + position.asLong(),
                        position, operation.expectedItemIds);
                removed++;
            } else {
                session.block("committed tree cell changed from exact "
                        + operation.committedNaturalTreeBlockId + " to " + blockId
                        + " at " + position.toShortString());
                stopCommittedNaturalTreeBuilder();
                actuators.clearResourceBreakScope(session.operationId());
                operation.blockedDetail = session.blockedDetail();
                return status(operation, State.BLOCKED,
                        operation.blockedDetail, distanceTo(position));
            }
        }
        operation.completedWorkUnits = Math.max(
                operation.completedWorkUnits, removed * 1_000L);
        if (removed > operation.committedNaturalTreeLastRemoved) {
            operation.committedNaturalTreeLastRemoved = removed;
            operation.committedNaturalTreeLastActivityAt = nowMillis;
        }

        if (remaining > 0) {
            ResourceActuationSession.Coordinate previousTarget =
                    operation.committedNaturalTreeAim.current().orElse(null);
            ResourceActuationSession.Coordinate currentTarget =
                    operation.committedNaturalTreeAim.select(liveRemaining).orElseThrow();
            if (!Objects.equals(previousTarget, currentTarget)) {
                stopCommittedNaturalTreeBuilder();
                if (!startCommittedNaturalTreeTarget(operation, liveRemaining)) {
                    operation.blockedDetail = session.blockedDetail();
                    return status(operation, State.BLOCKED,
                            operation.blockedDetail, distanceTo(block(currentTarget)));
                }
                operation.progressExpected = true;
                operation.waitingDetail = "previous log removal observed; latched exact next log "
                        + currentTarget.x() + ' ' + currentTarget.y() + ' ' + currentTarget.z();
                return status(operation, State.EXECUTING,
                        operation.waitingDetail, distanceTo(block(currentTarget)));
            }
            boolean builderActive = baritone.getBuilderProcess().isActive();
            boolean builderPaused = baritone.getBuilderProcess().isPaused();
            boolean calculatingOrMoving = baritone.getPathingBehavior().isPathing()
                    || baritone.getPathingBehavior().getInProgress().isPresent();
            boolean physicalBreak = client.interactionManager != null
                    && client.interactionManager.isBreakingBlock();
            boolean attackIssued = baritone.getInputOverrideHandler()
                    .isInputForcedDown(Input.CLICK_LEFT);
            if (calculatingOrMoving || physicalBreak || attackIssued) {
                operation.committedNaturalTreeLastActivityAt = nowMillis;
            }
            if (builderPaused
                    && nowMillis - operation.committedNaturalTreeStartedAt > 1_500L) {
                session.block("Baritone paused with " + remaining + "/"
                        + operation.committedNaturalTreeTargets.size()
                        + " committed tree log(s) still present instead of issuing work");
                stopCommittedNaturalTreeBuilder();
                actuators.clearResourceBreakScope(session.operationId());
                operation.blockedDetail = session.blockedDetail();
                return status(operation, State.BLOCKED,
                        operation.blockedDetail, distanceTo(block(target.coordinate())));
            }
            if (!builderActive && !calculatingOrMoving
                    && nowMillis - operation.committedNaturalTreeStartedAt > 1_500L) {
                session.block("Baritone stopped with " + remaining + "/"
                        + operation.committedNaturalTreeTargets.size()
                        + " committed tree log(s) still present");
                actuators.clearResourceBreakScope(session.operationId());
                operation.blockedDetail = session.blockedDetail();
                return status(operation, State.BLOCKED,
                        operation.blockedDetail, distanceTo(block(target.coordinate())));
            }
            if (builderActive && !builderPaused && !calculatingOrMoving
                    && !physicalBreak && !attackIssued
                    && nowMillis - operation.committedNaturalTreeLastActivityAt > 3_000L) {
                session.block("Baritone remained active but issued no path or break with "
                        + remaining + "/"
                        + operation.committedNaturalTreeTargets.size()
                        + " committed tree log(s) still present");
                stopCommittedNaturalTreeBuilder();
                actuators.clearResourceBreakScope(session.operationId());
                operation.blockedDetail = session.blockedDetail();
                return status(operation, State.BLOCKED,
                        operation.blockedDetail, distanceTo(block(target.coordinate())));
            }
            operation.sawActiveProcess |= builderActive || calculatingOrMoving;
            operation.progressExpected = true;
            operation.waitingDetail = "Baritone harvesting one committed natural tree; removed "
                    + removed + "/" + operation.committedNaturalTreeTargets.size()
                    + " log(s); stable target " + currentTarget.x() + ' '
                    + currentTarget.y() + ' ' + currentTarget.z();
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distanceTo(block(target.coordinate())));
        }

        if (session.phase() != ResourceActuationSession.Phase.AWAITING_DROP) {
            stopCommittedNaturalTreeBuilder();
            session.observeCommittedNaturalTreeRemoved();
            operation.resourceDropDeadlineAt = nowMillis
                    + RESOURCE_DROP_PICKUP_TIMEOUT_MILLIS;
            operation.resourceDropRouteRefreshAt = 0L;
            operation.resourceDropDestination = operation.resourceDropOrigin;
            operation.resourceDropEntityObserved = false;
        }
        int collected = Math.max(0,
                countTargetItems(operation.expectedItemIds)
                        - operation.startingTargetItemCount);
        int expected = operation.committedNaturalTreeTargets.size();
        if (collected >= expected) {
            session.observeCommittedNaturalTreeDropsSettled();
            actuators.clearResourceBreakScope(session.operationId());
            operation.committedNaturalTreeTargets = Set.of();
            operation.committedNaturalTreeBlockId = "";
            operation.committedNaturalTreeStartedAt = 0L;
            operation.committedNaturalTreeLastRemoved = 0;
            operation.committedNaturalTreeLastActivityAt = 0L;
            operation.committedNaturalTreeAim.clear();
            operation.completedWorkUnits = Math.max(
                    operation.completedWorkUnits, expected * 1_000L);
            operation.progressExpected = false;
            return completeStatus(operation,
                    "Baritone removed and collected the complete committed natural tree ("
                            + expected + " log(s))", 0);
        }
        if (nowMillis > operation.resourceDropDeadlineAt) {
            session.block("Baritone removed the complete committed tree, but collected "
                    + collected + "/" + expected
                    + " exact log drop(s) before the bounded pickup timeout");
            actuators.clearResourceBreakScope(session.operationId());
            operation.blockedDetail = session.blockedDetail();
            return status(operation, State.BLOCKED,
                    operation.blockedDetail, distanceTo(operation.resourceDropOrigin));
        }
        maintainScopedResourceDropRoute(operation, nowMillis);
        operation.progressExpected = true;
        operation.waitingDetail = "Baritone finished the committed tree; collecting exact drops "
                + collected + "/" + expected;
        return status(operation, State.EXECUTING,
                operation.waitingDetail, distanceTo(operation.resourceDropOrigin));
    }

    private void stopCommittedNaturalTreeBuilder() {
        try {
            baritone.getBuilderProcess().onLostControl();
            baritone.getPathingBehavior().cancelEverything();
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException ignored) {
        }
    }

    private Status pollResourceReplant(
            ActiveOperation operation,
            ResourceActuationSession.Target target,
            BlockPos position,
            long nowMillis) {
        ResourceActuationSession session = operation.resourceSession;
        if (session.phase()
                == ResourceActuationSession.Phase.VERIFYING_REPLANT) {
            LoadedResourceClassifier.Observation replacement = resourceObserver.observe(
                    position, ResourceStewardshipPolicy.Intent.HARVEST_CROP);
            boolean supported = replacement.observationComplete()
                    && replacement.targetCategory()
                    == LoadedResourceClassifier.Category.SUPPORTED_CROP;
            String required = replacement.requiredCropReplantItem().orElse(
                    target.requiredReplantItem().orElseThrow());
            if (session.observeReplacement(
                    resourceCoordinate(position), supported, required)) {
                return finishScopedResourceTarget(operation, position, nowMillis);
            }
            if (nowMillis > operation.resourceReplantDeadlineAt) {
                session.block("the exact crop replacement was not observed before timeout");
                actuators.clearResourceBreakScope(session.operationId());
                operation.blockedDetail = session.blockedDetail();
                return status(operation, State.BLOCKED,
                        operation.blockedDetail, Double.NaN);
            }
            operation.waitingDetail = "waiting for the server-observed replacement crop";
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distanceTo(position));
        }

        String item = target.requiredReplantItem().orElseThrow();
        if (!client.world.getBlockState(position).isAir()
                || !client.world.getBlockState(position.down()).isOf(Blocks.FARMLAND)) {
            session.block("harvested crop cell or its exact farmland support changed before replant");
            actuators.clearResourceBreakScope(session.operationId());
            operation.blockedDetail = session.blockedDetail();
            return status(operation, State.BLOCKED,
                    operation.blockedDetail, Double.NaN);
        }
        ReplantHand selection = findReplantHand(item);
        if (selection == null) {
            session.block("exact reserved replant item " + item
                    + " is not available in a hand or hotbar");
            actuators.clearResourceBreakScope(session.operationId());
            operation.blockedDetail = session.blockedDetail();
            return status(operation, State.BLOCKED,
                    operation.blockedDetail, Double.NaN);
        }
        Vec3d replantPoint = Vec3d.ofBottomCenter(position);
        Vec3d eye = client.player.getEyePos();
        double distance = eye.distanceTo(replantPoint);
        double reach = Math.max(3.0, client.player.getBlockInteractionRange() - 0.2);
        if (!ResourceInteractionReadiness.eyeWithinReach(
                eye.x, eye.y, eye.z,
                replantPoint.x, replantPoint.y, replantPoint.z, reach)) {
            if (!baritone.getCustomGoalProcess().isActive()
                    && !baritone.getPathingBehavior().isPathing()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(position, 1));
            }
            operation.waitingDetail = "approaching the harvested cell before exact replant";
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distance);
        }
        stopResourceWaypoint();
        int previousSlot = client.player.getInventory().getSelectedSlot();
        if (selection.hotbarSlot() >= 0) selectHotbarSlotNow(selection.hotbarSlot());
        BlockHitResult hit = new BlockHitResult(
                new Vec3d(position.getX() + 0.5, position.getY() + 0.0,
                        position.getZ() + 0.5),
                Direction.UP, position.down(), false);
        ActionResult result;
        try {
            result = client.interactionManager.interactBlock(
                    client.player, selection.hand(), hit);
            if (result.isAccepted()) client.player.swingHand(selection.hand());
        } finally {
            if (selection.hotbarSlot() >= 0) selectHotbarSlotNow(previousSlot);
        }
        if (!result.isAccepted()) {
            session.block("Minecraft rejected the exact crop replant interaction");
            actuators.clearResourceBreakScope(session.operationId());
            operation.blockedDetail = session.blockedDetail();
            return status(operation, State.BLOCKED,
                    operation.blockedDetail, Double.NaN);
        }
        session.observeReplantIssued(item);
        operation.resourceReplantDeadlineAt = nowMillis
                + RESOURCE_REPLANT_TIMEOUT_MILLIS;
        operation.waitingDetail = "exact replant issued; waiting for replacement observation";
        return status(operation, State.EXECUTING,
                operation.waitingDetail, distance);
    }

    private Status finishScopedResourceTarget(
            ActiveOperation operation,
            BlockPos dropOrigin,
            long nowMillis) {
        resourcePerception.rememberProducedDrops(operation.goal.missionId() + ":" + dropOrigin.asLong(),
                dropOrigin, operation.expectedItemIds);
        operation.verifiedMinedBlocks++;
        operation.completedWorkUnits++;
        operation.miningBreakCandidate = null;
        operation.miningBreakCandidateBlock = "";
        actuators.cancelBlockBreak(resourceBreakOperation(operation));
        actuators.clearResourceBreakScope(operation.resourceSession.operationId());
        int collected = Math.max(0,
                countTargetItems(operation.expectedItemIds)
                        - operation.startingTargetItemCount);
        ResourceActuationSession.Target completedTarget =
                operation.resourceSession.target().orElseThrow();
        boolean currentDropAlreadyCollected =
                collected > operation.resourceCollectedBeforeDrop;
        boolean boundedMineComplete = completedTarget.kind()
                == ResourceActuationSession.TargetKind.MINE
                && operation.maximumMinedBlocks > 0
                && operation.verifiedMinedBlocks >= operation.maximumMinedBlocks;
        if (currentDropAlreadyCollected || boundedMineComplete) {
            return continueAfterSettledResourceTarget(operation, collected);
        }
        operation.resourceDropDeadlineAt = nowMillis
                + RESOURCE_DROP_PICKUP_TIMEOUT_MILLIS;
        operation.resourceDropRouteRefreshAt = 0L;
        operation.resourceDropOrigin = dropOrigin.toImmutable();
        operation.resourceDropDestination = dropOrigin.toImmutable();
        operation.resourceDropEntityObserved = false;
        operation.resourceApproachFeet = null;
        maintainScopedResourceDropRoute(operation, nowMillis);
        operation.waitingDetail = "verified resource target "
                + operation.verifiedMinedBlocks
                + "; collecting its expected drop before selecting another target";
        return status(operation, State.EXECUTING,
                operation.waitingDetail, distanceTo(dropOrigin));
    }

    private Status pollScopedResourceDrop(
            ActiveOperation operation,
            long nowMillis) {
        ResourceActuationSession session = operation.resourceSession;
        ResourceActuationSession.Target target = session.target().orElseThrow();
        if (!target.coordinate().dimension().equals(currentDimension())) {
            session.block("world changed before the expected resource drop was collected");
            stopResourceWaypoint();
            operation.blockedDetail = session.blockedDetail();
            return status(operation, State.BLOCKED,
                    operation.blockedDetail, Double.NaN);
        }
        int collected = Math.max(0,
                countTargetItems(operation.expectedItemIds)
                        - operation.startingTargetItemCount);
        if (collected > operation.resourceCollectedBeforeDrop) {
            return continueAfterSettledResourceTarget(operation, collected);
        }
        if (nowMillis > operation.resourceDropDeadlineAt) {
            BlockPos lastDestination = operation.resourceDropDestination;
            String lastRoute = lastDestination == null
                    ? "no drop destination was loaded"
                    : (operation.resourceDropEntityObserved
                    ? "last observed expected item at "
                    : "last break-origin fallback at ")
                    + lastDestination.toShortString();
            session.block("the exact resource target broke, but no expected drop was "
                    + "collected before the bounded pickup timeout; " + lastRoute
                    + "; Entity at " + client.player.getBlockPos().toShortString());
            actuators.clearResourceBreakScope(session.operationId());
            stopResourceWaypoint();
            operation.blockedDetail = session.blockedDetail();
            return status(operation, State.BLOCKED,
                    operation.blockedDetail, Double.NaN);
        }
        maintainScopedResourceDropRoute(operation, nowMillis);
        BlockPos origin = operation.resourceDropOrigin == null
                ? block(target.coordinate()) : operation.resourceDropOrigin;
        operation.waitingDetail = "approaching the verified resource drop; collected "
                + collected + "/" + operation.quantity;
        return status(operation, State.EXECUTING,
                operation.waitingDetail, distanceTo(origin));
    }

    /**
     * Settles one exact drop, continues a previously committed natural tree
     * before considering quantity/max limits, and only then selects a new
     * classified resource. Baritone remains the route owner for every target.
     */
    private Status continueAfterSettledResourceTarget(
            ActiveOperation operation,
            int collected) {
        ResourceActuationSession session = operation.resourceSession;
        session.observeDropSettled();
        operation.resourceDropOrigin = null;
        operation.resourceDropDestination = null;
        operation.resourceDropEntityObserved = false;
        operation.resourceDropDeadlineAt = 0L;
        operation.resourceDropRouteRefreshAt = 0L;
        stopResourceWaypoint();

        Optional<ResourceActuationSession.Target> committedNext = session.target();
        if (committedNext.isPresent()) {
            ResourceActuationSession.Target next = committedNext.orElseThrow();
            activateResourceTarget(operation, next);
            if (session.phase() == ResourceActuationSession.Phase.BLOCKED) {
                operation.blockedDetail = session.blockedDetail();
                return status(operation, State.BLOCKED,
                        operation.blockedDetail, Double.NaN);
            }
            operation.waitingDetail = "collected the verified drop; continuing committed tree at "
                    + next.coordinate();
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distanceTo(block(next.coordinate())));
        }
        if (operation.maximumMinedBlocks > 0
                && operation.verifiedMinedBlocks >= operation.maximumMinedBlocks) {
            operation.progressExpected = false;
            return completeStatus(operation,
                    "verified bounded resource segment of "
                            + operation.verifiedMinedBlocks + " block(s)", 0);
        }
        if (collected >= operation.quantity) {
            operation.completedWorkUnits = Math.max(
                    operation.completedWorkUnits, operation.quantity);
            return completeStatus(operation,
                    "collected " + collected + "/" + operation.quantity
                            + " verified target drops", 0);
        }
        if (!beginNextResourceTarget(operation)) {
            if (operation.resourceCandidateScanPending)
                return status(operation, State.CALCULATING, operation.waitingDetail, Double.NaN);
            if (operation.resourceExhausted) {
                return completeStatus(operation, operation.waitingDetail, 0);
            }
            return status(operation, State.BLOCKED,
                    operation.blockedDetail, Double.NaN);
        }
        ResourceActuationSession.Target next = session.target().orElseThrow();
        operation.waitingDetail = "collected the verified drop; continuing at "
                + next.coordinate();
        return status(operation, State.EXECUTING,
                operation.waitingDetail, distanceTo(block(next.coordinate())));
    }

    private void maintainScopedResourceDropRoute(
            ActiveOperation operation,
            long nowMillis) {
        if (client.player == null || client.world == null
                || nowMillis < operation.resourceDropRouteRefreshAt) return;
        BlockPos origin = operation.resourceDropOrigin;
        if (origin == null) return;
        ItemEntity nearest = client.world.getEntitiesByClass(
                        ItemEntity.class, new Box(origin).expand(8.0), item -> {
                            if (item.isRemoved() || item.getStack().isEmpty()) return false;
                            String id = Registries.ITEM.getId(
                                    item.getStack().getItem()).getPath();
                            return operation.expectedItemIds.contains(id)
                                    && resourcePerception.entityPosition(item).isPresent();
                        }).stream()
                .min(Comparator.comparingDouble(item -> resourcePerception.entityPosition(item)
                        .map(client.player::squaredDistanceTo).orElse(Double.POSITIVE_INFINITY)))
                .orElse(null);
        BlockPos destination = nearest == null
                ? origin : BlockPos.ofFloored(resourcePerception.entityPosition(nearest).orElseThrow());
        operation.resourceDropDestination = destination.toImmutable();
        operation.resourceDropEntityObserved = nearest != null;
        operation.resourceDropRouteRefreshAt = nowMillis
                + RESOURCE_DROP_ROUTE_REFRESH_MILLIS;
        if (client.player.getBlockPos().equals(destination)) {
            return;
        }
        if (baritone.getCustomGoalProcess().isActive()
                || baritone.getPathingBehavior().isPathing()
                || baritone.getPathingBehavior().getInProgress().isPresent()) {
            return;
        }
        try {
            baritone.getCustomGoalProcess().setGoalAndPath(
                    new GoalBlock(destination));
        } catch (RuntimeException routeFailure) {
            operation.waitingDetail = "could not walk onto exact verified resource drop cell "
                    + destination.toShortString();
        }
    }

    private ReplantHand findReplantHand(String itemId) {
        if (client.player == null) return null;
        String expected = ResourceActuationSession.normalizeId(itemId);
        var offhand = client.player.getOffHandStack();
        if (!offhand.isEmpty()
                && Registries.ITEM.getId(offhand.getItem()).getPath().equals(expected)) {
            return new ReplantHand(Hand.OFF_HAND, -1);
        }
        int selected = client.player.getInventory().getSelectedSlot();
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            var stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty()
                    && Registries.ITEM.getId(stack.getItem()).getPath().equals(expected)) {
                return new ReplantHand(Hand.MAIN_HAND,
                        slot == selected ? -1 : slot);
            }
        }
        return null;
    }

    private BlockHitResult aimAndRaycastResource(BlockPos target, double reach) {
        if (client.player == null || client.world == null) return null;
        Vec3d eye = client.player.getEyePos();
        BlockInteractionRaycaster.BlockAim aim =
                BlockInteractionRaycaster.visibleAim(client, eye, target, reach)
                        .orElse(null);
        if (aim == null) return null;
        client.player.setYaw(aim.yaw());
        client.player.setPitch(MathHelper.clamp(aim.pitch(), -90.0F, 90.0F));
        return aim.hit();
    }

    /** Observes an exact interaction ray without stealing Baritone's live route rotation. */
    private BlockHitResult raycastResource(BlockPos target, double reach) {
        if (client.player == null) return null;
        return raycastResourceFrom(client.player.getEyePos(), target, reach);
    }

    /** Shared preflight/live ray predicate for one standing eye and exact block. */
    private BlockHitResult raycastResourceFrom(
            Vec3d eye,
            BlockPos target,
            double reach) {
        if (client.player == null || client.world == null) return null;
        return BlockInteractionRaycaster.visibleAim(client, eye, target, reach)
                .map(BlockInteractionRaycaster.BlockAim::hit)
                .orElse(null);
    }

    /** Finds a dry, supported feet cell whose standing eye ray reaches exactly one harvest block. */
    private BlockPos findHarvestApproach(
            ActiveOperation operation,
            BlockPos target,
            Set<BlockPos> excludedApproaches) {
        if (client.player == null || client.world == null) return null;
        Set<BlockPos> excluded = Set.copyOf(excludedApproaches);
        boolean namedFarmBound = operation != null
                && !operation.resourceAreaName.isEmpty();
        double reach = Math.max(3.0, client.player.getBlockInteractionRange() - 0.2);
        double eyeHeight = Math.max(1.5, Math.min(
                1.7, client.player.getEyePos().y - client.player.getY()));
        ResourceInteractionReadiness.ApproachSearchBounds bounds =
                ResourceInteractionReadiness.approachSearchBounds(
                        eyeHeight, reach);
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (int dy = bounds.minimumVerticalOffset();
                dy <= bounds.maximumVerticalOffset(); dy++) {
            for (int dx = -bounds.horizontalRadius();
                    dx <= bounds.horizontalRadius(); dx++) {
                for (int dz = -bounds.horizontalRadius();
                        dz <= bounds.horizontalRadius(); dz++) {
                    if (!ResourceInteractionReadiness.isDistinctApproachOffset(
                            dx, dy, dz)) continue;
                    BlockPos feet = target.add(dx, dy, dz);
                    boolean stableStance = !excluded.contains(feet)
                            && isStableHarvestStance(feet);
                    boolean insideNamedFarm = !namedFarmBound
                            || managedFarmAreaPermits(operation, feet);
                    if (!ResourceInteractionReadiness.harvestApproachScopeAdmissible(
                            stableStance, namedFarmBound, insideNamedFarm)) continue;
                    Vec3d eye = new Vec3d(
                            feet.getX() + 0.5,
                            feet.getY() + eyeHeight,
                            feet.getZ() + 0.5);
                    BlockHitResult observed = raycastResourceFrom(eye, target, reach);
                    if (observed != null && observed.getBlockPos().equals(target)) {
                        candidates.add(feet.toImmutable());
                    }
                }
            }
        }
        return candidates.stream()
                .min(Comparator.comparingDouble((BlockPos feet) ->
                                client.player.squaredDistanceTo(Vec3d.ofBottomCenter(feet)))
                        .thenComparingInt(feet -> Math.abs(feet.getY() - target.getY()))
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getZ))
                .orElse(null);
    }

    /**
     * Requires an unchanged air body column for an exact harvest GoalBlock.
     * Empty collision alone admits crops and vegetation that Baritone will not
     * necessarily occupy as a pinned goal.
     */
    private boolean isStableHarvestStance(BlockPos feet) {
        if (client.world == null
                || !client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) {
            return false;
        }
        BlockState feetState = client.world.getBlockState(feet);
        BlockState headState = client.world.getBlockState(feet.up());
        BlockState floorState = client.world.getBlockState(feet.down());
        boolean narrowBarrierSupport = floorState.getBlock() instanceof FenceBlock
                || floorState.getBlock() instanceof FenceGateBlock
                || floorState.getBlock() instanceof WallBlock;
        return ResourceInteractionReadiness.harvestStanceAdmissible(
                feetState.getCollisionShape(client.world, feet).isEmpty(),
                headState.getCollisionShape(client.world, feet.up()).isEmpty(),
                ResourceInteractionReadiness.harvestSupportAdmissible(
                        !floorState.getCollisionShape(client.world, feet.down()).isEmpty(),
                        narrowBarrierSupport),
                feetState.getFluidState().isEmpty(),
                headState.getFluidState().isEmpty(),
                feetState.isAir(),
                headState.isAir());
    }

    private double distanceTo(BlockPos position) {
        return client.player == null
                ? Double.NaN
                : Math.sqrt(client.player.squaredDistanceTo(Vec3d.ofCenter(position)));
    }

    private Status pollScopedResourceRouteLiveness(
            ActiveOperation operation,
            ResourceActuationSession.Target target,
            BlockPos targetPosition,
            boolean exactRay,
            long nowMillis) {
        BlockPos feet = client.player.getBlockPos().toImmutable();
        if (operation.resourceRouteLastFeet == null
                || !operation.resourceRouteLastFeet.equals(feet)) {
            operation.resourceRouteLastFeet = feet;
            operation.resourceRouteLastProgressAt = nowMillis;
        }
        long withoutProgress = Math.max(
                0L, nowMillis - operation.resourceRouteLastProgressAt);
        ScopedResourceRoutePolicy.Decision decision = ScopedResourceRoutePolicy.decide(
                exactRay,
                operation.failed(),
                ScopedResourceRoutePolicy.isSideApproach(
                        scopedRouteCoordinate(targetPosition),
                        scopedRouteCoordinate(feet)),
                withoutProgress,
                RESOURCE_TARGET_ROUTE_STALL_MILLIS);
        if (decision == ScopedResourceRoutePolicy.Decision.CONTINUE) return null;

        String reason = switch (decision) {
            case RETIRE_CALCULATION_FAILED ->
                    "Baritone could not calculate a survival route to the pinned resource";
            case RETIRE_UNUSABLE_ARRIVAL ->
                    "the completed side approach had no exact interaction ray";
            case RETIRE_NO_PROGRESS ->
                    "the pinned survival route made no physical progress for "
                            + Math.max(1L, withoutProgress / 1_000L) + "s";
            case CONTINUE -> throw new IllegalStateException("handled above");
        };
        return retireCurrentMiningTarget(operation, target, reason, nowMillis);
    }

    /** Retires one unchanged coordinate before any replacement route may start. */
    private Status retireCurrentMiningTarget(
            ActiveOperation operation,
            ResourceActuationSession.Target expectedTarget,
            String reason,
            long nowMillis) {
        ResourceActuationSession session = operation.resourceSession;
        ResourceActuationSession.Target current = session.target().orElse(null);
        if (current == null || !current.coordinate().equals(expectedTarget.coordinate())) {
            return null;
        }
        actuators.cancelBlockBreak(resourceBreakOperation(operation));
        actuators.clearResourceBreakScope(session.operationId());
        stopResourceWaypoint();
        ResourceActuationSession.Target retired = session.rejectUnchangedTarget();
        operation.rejectedResourceTargets.add(retired.coordinate());
        operation.resourceApproachFeet = null;
        operation.miningBreakCandidate = null;
        operation.miningBreakCandidateBlock = "";
        operation.lastEvent = null;
        lastPathEvent.set(null);
        operation.traversalWatchdog.reset();
        resetScopedResourceRouteLiveness(operation, nowMillis);
        neutralizeRejectedResourcePosture();

        String retiredAt = retired.coordinate().x() + " "
                + retired.coordinate().y() + " " + retired.coordinate().z();
        if (beginNextResourceTarget(operation)) {
            operation.progressExpected = true;
            operation.waitingDetail = reason + "; retired unchanged target "
                    + retiredAt + " and selected a different loaded block";
            return status(operation, State.EXECUTING,
                    operation.waitingDetail, distanceRemaining());
        }
        if (operation.resourceCandidateScanPending)
            return status(operation, State.CALCULATING, operation.waitingDetail, Double.NaN);
        operation.progressExpected = false;
        operation.blockedDetail = reason + "; retired unchanged target "
                + retiredAt + "; " + Objects.requireNonNullElse(
                operation.blockedDetail, "no replacement target is available");
        return status(operation, State.BLOCKED,
                operation.blockedDetail, distanceRemaining(),
                BaritonePort.FailureCause.TARGET_ROUTE_EXHAUSTED);
    }

    private void resetScopedResourceRouteLiveness(
            ActiveOperation operation,
            long nowMillis) {
        operation.resourceRouteLastFeet = client.player == null
                ? null : client.player.getBlockPos().toImmutable();
        operation.resourceRouteLastProgressAt = nowMillis;
    }

    private static ScopedResourceRoutePolicy.Coordinate scopedRouteCoordinate(
            BlockPos position) {
        return new ScopedResourceRoutePolicy.Coordinate(
                position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos block(ScopedResourceRoutePolicy.Coordinate coordinate) {
        return new BlockPos(coordinate.x(), coordinate.y(), coordinate.z());
    }

    private void stopResourceWaypoint() {
        try {
            baritone.getCustomGoalProcess().setGoal(null);
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getPathingBehavior().cancelEverything();
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException ignored) {
        }
    }

    private static String resourceBreakOperation(ActiveOperation operation) {
        return "baritone:resource:" + operation.goal.missionId();
    }

    private void configureMiningSearch(MiningPolicy.Plan miningPlan) {
        var settings = BaritoneAPI.getSettings();
        boolean legitimate = resourcePerception().legitimate();
        settings.exploreForBlocks.value = true;
        // Let the native MineProcess own discovery and excavation. Its legitimate mode
        // skips the cached/loaded ore search and continues prospecting when no ore is seen.
        // Do not replace this with an exposed-to-air filter: an unseen cave also has air.
        settings.legitMine.value = legitimate;
        int searchY = miningPlan.targetY();
        if (legitimate && client.player != null && client.world != null) {
            BlockPos feet = client.player.getBlockPos();
            searchY = MiningPolicy.legitimateSearchY(miningPlan,
                    client.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                            feet.getX(), feet.getZ()), client.world.getBottomY());
        }
        settings.legitMineYLevel.value = searchY;
        settings.legitMineIncludeDiagonals.value = !legitimate;
        // Native drop scanning has no visibility gate. Actual vanilla pickup and Entity's
        // acknowledged collection of observed/action-produced drops remain unchanged.
        settings.mineScanDroppedItems.value = !legitimate;
        if (active.goal.arguments().containsKey("idleUndergroundMaxY")) {
            settings.maxYLevelWhileMining.value = idleMiningLimit.apply(settings.maxYLevelWhileMining.value,
                    Integer.parseInt(active.goal.arguments().get("idleUndergroundMaxY")));
        }
        active.prospecting = miningPlan.prospecting();
        active.prospectingY = searchY;
        logger.info("Mining discovery mode={} legitMine={} hiddenDiagonals={} generation={} blocks={}",
                legitimate ? "LEGITIMATE" : "NORMAL", settings.legitMine.value,
                settings.legitMineIncludeDiagonals.value, active.generation,
                java.util.Arrays.toString(active.miningBlocks));
    }

    private String functionalRouteObjective(ActiveOperation operation) {
        // A mine access belongs to its occupied entrance, not to one ore name.
        // beginMining still selects only a route whose exact entrance is the
        // current body cell, so multiple mines remain distinct while later
        // stone/iron/coal work can reuse the same known exit.
        return currentDimension() + "|mine-access";
    }

    /** Native legitMine's RunAway is not a descend-first goal. Retain its miner while
     * Baritone reaches the chosen geological level, using the same stair/tool/return contract. */
    private void maintainNativeProspecting(ActiveOperation operation, long nowMillis) {
        if (!resourcePerception.legitimate() || !operation.prospecting
                || operation.resourceSession != null || operation.functionalMiningReturning
                || operation.prospectingAccessComplete || client.player == null) return;
        if (operation.prospectingAccessStarted
                && nativeProspecting.result() == NativeProspectingProcess.Result.REACHED) {
            operation.prospectingAccessComplete = true;
            logger.info("Legitimate prospecting access reached Y={} generation={}; native miner retained",
                    operation.prospectingY, operation.generation);
            return;
        }
        if (operation.prospectingAccessStarted
                && nativeProspecting.result() == NativeProspectingProcess.Result.FAILED) {
            nativeProspecting.cancel();
            operation.waitingDetail = "native prospecting access failed; trying another entrance";
            beginAlternativeEntrance(operation, nowMillis);
            return;
        }
        if (nativeProspecting.active()) return;
        // Visible-ore GoalComposite work must never be displaced by a statistical search level.
        if (!operation.prospectingAccessStarted
                && !(baritone.getPathingBehavior().getGoal() instanceof GoalRunAway)) return;
        if (baritone.getPlayerContext().playerFeet().getY() == operation.prospectingY) {
            operation.prospectingAccessComplete = true;
            return;
        }
        if (nativeProspecting.begin(operation.prospectingY)) {
            operation.prospectingAccessStarted = true;
            operation.lastEvent = null;
            lastPathEvent.set(null);
            operation.traversalWatchdog.reset();
            logger.info("Legitimate prospecting access started Y={} generation={}; native miner retained",
                    operation.prospectingY, operation.generation);
        }
    }

    private void startMiningProcess(ActiveOperation operation) {
        configureMiningSearch(operation.miningPlan);
        publishNativePropertyCosts(operation);
        configureRoutineMiningDescent();
        // Route ownership includes Baritone's inventory/tool actuator. It may
        // select support material while moving, then atomically select the
        // suitable pick for the block it is about to break.
        setActuatorScope(BaritoneActuatorScopePolicy.Owner.ROUTE);
        if (!operation.functionalMiningRouteInitialized) {
            if (client.player == null || protectedAreas == null) {
                operation.progressExpected = false;
                operation.blockedDetail =
                        "Mine entrance cannot be admitted before property policy and player position are available";
                operation.waitingDetail = operation.blockedDetail;
                return;
            }
            ProtectedAreaClientState.PropertyObservation entrance =
                    protectedAreas.observeProperty(
                            currentDimension(),
                            client.player.getBlockX(),
                            client.player.getBlockZ());
            if (!entrance.available()) {
                operation.progressExpected = false;
                operation.blockedDetail = "Mine entrance cannot be admitted: " + entrance.detail();
                operation.waitingDetail = operation.blockedDetail;
                return;
            }
            // The command/return origin may be inside a house. Native mutation costs
            // fence the actual excavation in both perception modes, not legal travel
            // through the existing doorway. Keep this durable return origin unchanged.
            operation.functionalMiningRouteInitialized = true;
            // The leaf checkpoint survives /retry, protection and client restart. Replacing
            // it with current feet would turn an underground retry cell into the mine exit.
            operation.functionalMiningEntrance = MiningEntranceCheckpoint
                    .fromGoal(operation.goal.arguments(), currentDimension())
                    .map(origin -> new BlockPos(origin.x(), origin.y(), origin.z()))
                    .orElseGet(() -> baritone.getPlayerContext().playerFeet().toImmutable());
            MinecraftFunctionalRoutes.BeginResult route = functionalRoutes.beginMining(
                    functionalRouteObjective(operation), System.currentTimeMillis());
            if (route.replaying()) {
                operation.functionalRouteReplay = true;
                operation.progressExpected = true;
                operation.waitingDetail = route.detail();
                return;
            }
        }
        // Baritone's quantity check compares the requested block item itself. That is wrong for
        // ordinary ore drops (diamond_ore -> diamond), so our inventory observer owns completion.
        baritone.getMineProcess().mineByName(0, operation.miningBlocks);
        operation.miningProcessStarts++;
        operation.sawActiveProcess = baritone.getMineProcess().isActive();
        operation.waitingForTool = false;
        operation.waitingDetail = null;
        operation.progressExpected = true;
    }

    /** Keeps ordinary mining walkable without weakening emergency movement elsewhere. */
    private static void configureRoutineMiningDescent() {
        var settings = BaritoneAPI.getSettings();
        settings.allowDownward.value = false;
        settings.allowDiagonalDescend.value = false;
        settings.allowOvershootDiagonalDescend.value = false;
    }

    /** Give native A* the same explicit property boundary as the final actuator.
     * Publish immutable geometry; pathfinding threads never read client/world state. */
    private void publishNativePropertyCosts(ActiveOperation operation) {
        boolean building = operation != null && operation.goal.kind().equals("build");
        boolean enabled = operation != null;
        if (!enabled) { NativePropertyCosts.clear(); return; }
        var property = protectedAreas == null ? null : protectedAreas.observePropertyRegions();
        String dimension = currentDimension();
        List<NativePropertyCosts.Region> regions = property == null ? List.of()
                : property.areas().stream().filter(area -> area.dimension().equals(dimension))
                .map(area -> new NativePropertyCosts.Region(
                        area.minX(), area.maxX(), area.minZ(), area.maxZ())).toList();
        if (building) {
            if(blueprintProjects!=null&&blueprintCostProject!=null
                    &&blueprintCostProject.matches(operation.goal.missionId(),operation.controlEpoch)
                    &&blueprintSupportRevision!=blueprintProjects.supports().revision())
                refreshBlueprintCosts(operation,blueprintProjects.project());
            boolean owned = blueprintSession != null && blueprintCostProject != null
                    && blueprintCostProject.matches(operation.goal.missionId(), operation.controlEpoch)
                    && arbiter.isValid(operation.controlLease, System.currentTimeMillis());
            NativePropertyCosts.publishBlueprint(owned && property != null && property.available(),
                    regions, owned ? blueprintCostProject : null);
        } else NativePropertyCosts.publish(true, property != null && property.available(), regions);
    }

    private void rememberMiningBreakTarget(ActiveOperation operation, BlockPos position) {
        if (operation == null || position == null || client.world == null) return;
        BlockState state = client.world.getBlockState(position);
        String blockId = Registries.BLOCK.getId(state.getBlock()).getPath();
        if (Arrays.stream(operation.miningBlocks).noneMatch(blockId::equals)) return;
        if (!position.equals(operation.miningBreakCandidate)) {
            operation.miningBreakCandidate = position.toImmutable();
            operation.miningBreakCandidateBlock = blockId;
        }
    }

    private void observeVerifiedMinedBlock(ActiveOperation operation) {
        if (operation == null || operation.miningBreakCandidate == null
                || client.world == null) return;
        BlockPos position = operation.miningBreakCandidate;
        if (!client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) return;
        String liveBlock = Registries.BLOCK.getId(
                client.world.getBlockState(position).getBlock()).getPath();
        if (liveBlock.equals(operation.miningBreakCandidateBlock)) return;
        resourcePerception.rememberProducedDrops(operation.goal.missionId() + ":" + position.asLong(),
                position, operation.expectedItemIds);
        operation.verifiedMinedBlocks++;
        operation.completedWorkUnits++;
        operation.miningBreakCandidate = null;
        operation.miningBreakCandidateBlock = "";
    }

    private void pauseMiningForTool(ActiveOperation operation, String blockName) {
        if (!operation.waitingForTool) {
            try {
                baritone.getMineProcess().cancel();
                baritone.getPathingBehavior().cancelEverything();
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
            } catch (RuntimeException ignored) {
            }
        }
        operation.waitingForTool = true;
        operation.progressExpected = false;
        operation.waitingDetail = "waiting for a suitable, non-broken tool for " + blockName
                + "; give Entity the tool and this mission will continue automatically";
    }

    private void resumeMiningAfterToolArrives(ActiveOperation operation) {
        logger.info("A suitable tool arrived; resuming mining mission {}", operation.goal.missionId());
        startMiningProcess(operation);
    }

    private String firstMissingTargetTool(String[] blocks) {
        if (blocks == null || client.player == null) return null;
        for (String name : blocks) {
            Block block = Registries.BLOCK.get(Identifier.ofVanilla(simpleIdentifier(name)));
            BlockState state = block.getDefaultState();
            if (state.isToolRequired() && !hasSafeSuitableTool(state)) {
                return Registries.BLOCK.getId(block).getPath();
            }
        }
        return null;
    }

    private void refreshBreakSafety(boolean force) {
        var player = client.player;
        var settings = BaritoneAPI.getSettings();
        if (baritone instanceof ResourceDescentSafetyPolicy.NativeOwner owner) {
            owner.entity2$resourceDescentRequired(player != null && active != null
                    && ResourceDescentSafetyPolicy.appliesToOperation(active.goal.kind(), active.goal.arguments()));
        }
        if (player == null) {
            settings.maxFallHeightNoWater.value = 2;
            settings.maxFallHeightBucket.value = baselineMaxFallHeightBucket;
            return;
        }
        int hash = inventorySafetyHash();
        // Ordinary travel keeps its prior limits. Mining's native search must share
        // the final one-level stair contract, including the separate bucket-fall
        // branch: after refill, a water bucket must not re-enable a vetoed edge.
        String kind = active == null ? "" : active.goal.kind();
        Map<String, String> arguments = active == null ? Map.of() : active.goal.arguments();
        int nextFallHeight = ResourceDescentSafetyPolicy.nativeFallLimit(kind, arguments, 2);
        int nextBucketFallHeight = ResourceDescentSafetyPolicy.nativeFallLimit(
                kind, arguments, baselineMaxFallHeightBucket);
        double healthFraction = player.getHealth() / Math.max(1.0, player.getMaxHealth());
        nextFallHeight = CombatRouteHazardPolicy.nativeFallLimit(kind, healthFraction, nextFallHeight);
        nextBucketFallHeight = CombatRouteHazardPolicy.nativeFallLimit(kind, healthFraction, nextBucketFallHeight);
        if (nextFallHeight < configuredMaxFallHeight
                || nextBucketFallHeight < settings.maxFallHeightBucket.value) safetyReplanPending = true;
        configuredMaxFallHeight = nextFallHeight;
        settings.maxFallHeightNoWater.value = nextFallHeight;
        settings.maxFallHeightBucket.value = nextBucketFallHeight;
        if (!force && hash == inventorySafetyHash) {
            applyPendingSafetyReplan();
            return;
        }

        LinkedHashSet<Block> disallowed = new LinkedHashSet<>(baselineDisallowedBreaks);
        for (Block block : Registries.BLOCK) {
            BlockState state = block.getDefaultState();
            if (state.isToolRequired() && !hasSafeSuitableTool(state)) disallowed.add(block);
        }
        Set<Block> immutable = Set.copyOf(disallowed);
        boolean changed = !immutable.equals(dynamicDisallowedBreaks);
        if (changed) safetyReplanPending = true;
        dynamicDisallowedBreaks = immutable;
        settings.blocksToDisallowBreaking.value = new ArrayList<>(disallowed);
        inventorySafetyHash = hash;
        applyPendingSafetyReplan();
    }

    /** Final loaded-world check for the descent edge selected by Baritone. */
    private String guardUnsafeResourceDescent(ActiveOperation operation) {
        if (operation == null || operation.functionalRouteReplay
                || !ResourceDescentSafetyPolicy.appliesToOperation(
                operation.goal.kind(), operation.goal.arguments())) return null;
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return null;
        int position = executor.getPosition();
        var movements = executor.getPath().movements();
        if (position < 0 || position >= movements.size()) return null;
        var movement = movements.get(position);
        var source = movement.getSrc();
        var destination = movement.getDest();
        if (destination.y >= source.y) return null;

        // Ask the selected native movement, not a guessed feet/head column.
        // Descend also clears destination.up(2); construction exits can already
        // be ordinary walkable stairs without requiring any excavation at all.
        boolean nativeMutationFree = false;
        List<BlockPos> nativeBreaks = null;
        if (movement instanceof baritone.cb nativeMovement) {
            nativeMovement.resetBlockCache(); // Only observation caches, never movement state.
            var world = new baritone.fc(baritone.getPlayerContext());
            nativeMutationFree = nativeMovement.a(world).isEmpty() && nativeMovement.b(world).isEmpty();
            nativeBreaks = nativeMovement.a(world).stream()
                    .map(pos -> new BlockPos(pos.getX(), pos.getY(), pos.getZ())).toList();
        }
        MinecraftResourceDescentGuard.Verdict verdict = resourceDescentGuard.inspect(
                operation.goal.missionId(),
                currentRouteSignature(),
                new BlockPos(source.x, source.y, source.z),
                new BlockPos(destination.x, destination.y, destination.z), nativeMutationFree, nativeBreaks);
        if (!verdict.veto()) return null;

        configureRoutineMiningDescent();
        operation.failedAccess = new BlockPos(destination.x, destination.y, destination.z);
        if (movement.safeToCancel()) {
            if (routeRecovery.active()) routeRecovery.rejectCurrent(verdict.detail());
            else beginAlternativeEntrance(operation, System.currentTimeMillis());
        }
        operation.waitingDetail = "rejected mining entrance: " + verdict.detail()
                + "; " + routeRecovery.snapshot().detail();
        return operation.waitingDetail;
    }

    /** Remember observed danger through protection preemption, not all nearby animals. */
    public synchronized void rememberThreatApproach(Entity threat, long nowMillis) {
        if (threat == null || client.player == null) return;
        BlockPos position = threat.getBlockPos();
        if (rememberedThreat == null || !currentDimension().equals(rememberedThreatDimension)
                || rememberedThreat.getSquaredDistance(position) > 16) {
            threatDetourAttempts.clear();
        }
        rememberedThreat = position.toImmutable();
        rememberedThreatDimension = currentDimension();
        rememberedThreatUntil = nowMillis + 60_000;
    }

    /** The outer no-progress watchdog must not destroy MineProcess's blacklist. */
    public synchronized boolean recoverMiningInPlace(long epoch, long nowMillis) {
        ActiveOperation operation = active;
        if (operation == null || operation.controlEpoch != epoch
                || !isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))
                || operation.resourceSession != null
                || !baritone.getMineProcess().isActive()) return false;
        if (routeRecovery.active()) return true;
        beginAlternativeEntrance(operation, nowMillis);
        return true;
    }

    private boolean beginAlternativeEntrance(ActiveOperation operation, long nowMillis) {
        if (client.player == null || client.world == null) return false;
        if (requestMiningAccessTool(operation)) return false;
        BlockPos origin = baritone.getPlayerContext().playerFeet();
        if (operation.miningRetreatBudget.exhausted(operation.verifiedMinedBlocks)) {
            operation.blockedDetail = "Mining access remains exhausted after the original-entrance retreat; no new target was mined";
            cancelProcesses();
            return false;
        }
        BlockPos failure = operation.failedAccess == null ? origin : operation.failedAccess;
        List<BlockPos> candidates = alternativeStances(origin, failure, null,
                operation.alternateEntrances, true);
        if (candidates.isEmpty() || operation.alternateEntrances.size() >= 12) {
            if (beginMiningEntranceRetreat(operation, nowMillis)) return true;
            operation.blockedDetail = "No different loaded dry mine entrance found near "
                    + origin.toShortString() + "; failed access retained, not restarted";
            cancelProcesses();
            return false;
        }
        operation.alternateEntrances.addAll(candidates);
        operation.alternateRouteRunning = routeRecovery.begin(candidates, nowMillis);
        operation.lastEvent = null;
        operation.traversalWatchdog.reset();
        operation.progressExpected = true;
        logger.info("Mining alternate entrance from {} away from {}; candidates {}",
                origin, failure, candidates);
        return operation.alternateRouteRunning;
    }

    /** Called only for an observed failed route/access, never on ordinary hand-mining admission. */
    private boolean requestMiningAccessTool(ActiveOperation operation) {
        boolean nativeMining = operation != null && operation.resourceSession == null
                && isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))
                && !operation.functionalMiningReturning;
        if (!MiningAccessRecoveryPolicy.needsAccessTool(true, nativeMining,
                hasSafeSuitableTool(Blocks.STONE.getDefaultState()))) return false;
        operation.accessToolRequired = true;
        operation.progressExpected = false;
        operation.waitingDetail = "Mining access requires a usable pickaxe; preparing the existing acquisition prerequisite";
        cancelProcesses();
        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        logger.info("Mining access tool required for {} after an observed route failure; partial cargo and original entrance retained",
                operation.goal.missionId());
        return true;
    }

    /** Retreat is an access waypoint, not completion: preserve the miner, quota, and search history. */
    private boolean beginMiningEntranceRetreat(ActiveOperation operation, long nowMillis) {
        if (operation.resourceSession != null || operation.functionalMiningReturning
                || !isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))) return false;
        BlockPos entrance = operation.functionalMiningEntrance;
        if (!operation.miningRetreatBudget.consume(operation.verifiedMinedBlocks,
                usableMiningRetreatEntrance(entrance), entrance != null
                        && entrance.equals(baritone.getPlayerContext().playerFeet()))) return false;
        operation.miningEntranceRetreat = true;
        operation.alternateRouteRunning = routeRecovery.begin(List.of(entrance), nowMillis, 60_000L);
        operation.lastEvent = null;
        lastPathEvent.set(null);
        operation.traversalWatchdog.reset();
        operation.progressExpected = true;
        operation.waitingDetail = "Local mining access exhausted; retreating with partial cargo to the original entrance";
        logger.info("Mining partial-cargo retreat for {} to {} after {} verified target removals; native miner retained",
                operation.goal.missionId(), entrance, operation.verifiedMinedBlocks);
        return operation.alternateRouteRunning;
    }

    /** Original property air space is legal travel; per-cell costs still forbid every protected mutation. */
    private boolean usableMiningRetreatEntrance(BlockPos entrance) {
        if (entrance == null || client.world == null || protectedAreas == null
                || !client.world.isChunkLoaded(entrance.getX() >> 4, entrance.getZ() >> 4)
                || !protectedAreas.observeProperty(currentDimension(), entrance.getX(), entrance.getZ()).available()
                || !dev.entity.client.autonomy.workspace.MinecraftWorkspaceProbe
                        .hasStableFullTopSupport(client, entrance.down())) return false;
        for (BlockPos cell : List.of(entrance, entrance.up())) {
            BlockState state = client.world.getBlockState(cell);
            if (!state.getFluidState().isEmpty() || combatRouteHazardId(state) != null
                    || !state.getCollisionShape(client.world, cell).isEmpty()) return false;
        }
        return true;
    }

    private Status pollAlternateRoute(ActiveOperation operation, long nowMillis) {
        if (routeRecovery.active()) {
            var detourPath = baritone.getPathingBehavior().getCurrent();
            BlockPos candidate = routeRecovery.snapshot().candidate();
            if (detourPath != null && candidate != null
                    && detourPath.getPath().getGoal().isInGoal(
                    candidate.getX(), candidate.getY(), candidate.getZ())) {
                if (isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))) {
                    guardUnsafeResourceDescent(operation);
                    observeFunctionalMiningRoute(nowMillis);
                } else if (rememberedThreat != null
                        && nowMillis < rememberedThreatUntil
                        && rememberedThreatDimension.equals(currentDimension())) {
                    var movements = detourPath.getPath().movements();
                    int index = detourPath.getPosition();
                    if (index >= 0 && index < movements.size()) {
                        var edge = movements.get(index);
                        if (edge.safeToCancel()
                                && Math.abs(edge.getDest().getY() - rememberedThreat.getY()) <= 3
                                && !RouteRecoveryGeometry.clearsCircle(edge.getSrc().getX(),
                                edge.getSrc().getZ(), edge.getDest().getX(), edge.getDest().getZ(),
                                rememberedThreat.getX(), rememberedThreat.getZ(), 5)) {
                            routeRecovery.rejectCurrent("candidate route crosses remembered hostile");
                        }
                    }
                }
            }
            operation.progressExpected = true;
            operation.traversalWatchdog.reset();
            operation.lastEvent = null;
            return status(operation, State.EXECUTING,
                    "retaining original mission; " + routeRecovery.snapshot().detail(),
                    distanceRemaining());
        }
        if (operation.alternateRouteRunning) {
            operation.alternateRouteRunning = false;
            boolean entranceRetreat = operation.miningEntranceRetreat;
            operation.miningEntranceRetreat = false;
            operation.lastEvent = null;
            lastPathEvent.set(null);
            operation.traversalWatchdog.reset();
            var result = routeRecovery.snapshot();
            boolean portalApproach = operation.portalApproachRunning;
            operation.portalApproachRunning = false;
            logger.info("Alternate route result: {} at {}; {}", result.result(),
                    result.candidate(), result.detail());
            if (result.result() == NativeRouteRecoveryProcess.Result.EXHAUSTED
                    || result.result() == NativeRouteRecoveryProcess.Result.TIMED_OUT) {
                if (requestMiningAccessTool(operation))
                    return status(operation, State.TRANSIENT_FAILURE, operation.waitingDetail, Double.NaN);
                if (!entranceRetreat && beginMiningEntranceRetreat(operation, nowMillis))
                    return status(operation, State.EXECUTING, operation.waitingDetail, distanceRemaining());
                operation.blockedDetail = "Alternative approaches exhausted: " + result.detail();
                cancelProcesses();
                return status(operation, State.BLOCKED, operation.blockedDetail,
                        distanceRemaining());
            }
            if (entranceRetreat && result.result() == NativeRouteRecoveryProcess.Result.REACHED) {
                operation.waitingDetail = "Original entrance reached with partial cargo; resuming retained native mining";
                logger.info(operation.waitingDetail);
            }
            if (portalApproach && result.result() == NativeRouteRecoveryProcess.Result.REACHED) {
                // The prior collision back-off may have canceled the native process.
                // Restore the original logical goal once, not merely its stale path.
                recommitAfterRouteSupportRecovery(operation);
            }
        }
        if (rememberedThreat == null || nowMillis >= rememberedThreatUntil
                || !rememberedThreatDimension.equals(currentDimension())
                || ownsDedicatedThreatRoute(operation.goal.kind())) return null;
        var path = baritone.getPathingBehavior().getCurrent();
        if (path == null || client.player == null) return null;
        BlockPos origin = baritone.getPlayerContext().playerFeet();
        if (Math.abs(origin.getY() - rememberedThreat.getY()) > 4
                || origin.getSquaredDistance(rememberedThreat) < 25) return null;
        boolean crosses = path.getPath().positions().stream().anyMatch(cell ->
                Math.abs(cell.getY() - rememberedThreat.getY()) <= 3
                && !RouteRecoveryGeometry.clearsCircle(cell.getX(), cell.getZ(),
                cell.getX(), cell.getZ(), rememberedThreat.getX(), rememberedThreat.getZ(), 5));
        if (!crosses) return null;
        BlockPos goal = path.getPath().getDest();
        List<BlockPos> candidates = alternativeStances(origin, rememberedThreat, goal,
                threatDetourAttempts, false);
        if (candidates.isEmpty()) return null; // A detour is optional, not a global travel ban.
        threatDetourAttempts.addAll(candidates);
        operation.alternateRouteRunning = routeRecovery.begin(candidates, nowMillis);
        operation.lastEvent = null;
        logger.info("Threat-interrupted route choosing alternative around {} via {}",
                rememberedThreat, candidates);
        return status(operation, State.EXECUTING,
                "trying another approach around the previously encountered hostile",
                distanceRemaining());
    }

    private static boolean ownsDedicatedThreatRoute(String kind) {
        // An ordinary travel detour must not replace a committed combat/escape
        // goal with optional candidate stances while a hostile is in reach.
        return Set.of("attack", "hunt", "protect", "guard", "protection_escape")
                .contains(kind.toLowerCase(Locale.ROOT));
    }

    private List<BlockPos> alternativeStances(BlockPos origin, BlockPos hazard,
            BlockPos destination, Set<BlockPos> tried, boolean mining) {
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (int radius : new int[]{4, 8, 12, 16}) {
            for (int dx = -radius; dx <= radius; dx += radius) {
                for (int dz = -radius; dz <= radius; dz += radius) {
                    if (dx == 0 && dz == 0) continue;
                    for (int dy : new int[]{0, 1, -1, 2, -2}) {
                        BlockPos candidate = origin.add(dx, dy, dz);
                        if (tried.contains(candidate) || !dryRecoveryStance(candidate, mining)) continue;
                        if (mining) {
                            if (!RouteRecoveryGeometry.alternative(origin.getX(), origin.getZ(),
                                    candidate.getX(), candidate.getZ(), hazard.getX(), hazard.getZ())) continue;
                        } else if (!RouteRecoveryGeometry.clearsCircle(origin.getX(), origin.getZ(),
                                candidate.getX(), candidate.getZ(), hazard.getX(), hazard.getZ(), 5)
                                || !RouteRecoveryGeometry.clearsCircle(candidate.getX(), candidate.getZ(),
                                destination.getX(), destination.getZ(), hazard.getX(), hazard.getZ(), 5)) continue;
                        candidates.add(candidate);
                        break;
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate ->
                candidate.getSquaredDistance(origin)
                        + (destination == null ? 0 : candidate.getSquaredDistance(destination))));
        return candidates.stream().limit(4).toList();
    }

    private boolean dryRecoveryStance(BlockPos feet, boolean mining) {
        if (client.world == null || protectedAreas == null
                || !client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        var property = protectedAreas.observeProperty(currentDimension(), feet.getX(), feet.getZ());
        if (!property.available() || property.protectedProperty()) return false;
        if (!dev.entity.client.autonomy.workspace.MinecraftWorkspaceProbe
                .hasStableFullTopSupport(client, feet.down())) return false;
        for (BlockPos cell : List.of(feet, feet.up())) {
            BlockState state = client.world.getBlockState(cell);
            if (!state.getFluidState().isEmpty() || combatRouteHazardId(state) != null) return false;
            if (!state.getCollisionShape(client.world, cell).isEmpty()
                    && (!mining || state.hasBlockEntity()
                    || state.getHardness(client.world, cell) < 0
                    || state.isToolRequired() && !hasSafeSuitableTool(state))) return false;
        }
        if (mining) {
            // Select only a dry entrance. Baritone still chooses and excavates every stair.
            for (BlockPos cell : BlockPos.iterate(feet.add(-1, -3, -1), feet.add(1, 0, 1))) {
                if (!client.world.getBlockState(cell).getFluidState().isEmpty()) return false;
            }
        }
        return true;
    }

    private String guardUnsafeBlockBreak(ActiveOperation operation) {
        if (client.player == null || client.world == null || client.interactionManager == null
                || !client.interactionManager.isBreakingBlock()
                || !(client.crosshairTarget instanceof BlockHitResult hit)) return null;
        RouteSupportCustody.Coordinate coordinate = routeSupportCoordinate(hit.getBlockPos());
        if (routeSupportCustody.protects(coordinate)) {
            RouteSupportCustody.BreakDecision decision = routeSupportCustody.beforeBreak(coordinate);
            if (decision.action() == RouteSupportCustody.Action.BLOCK) {
                stopProtectedSupportBreak(decision.detail());
                operation.progressExpected = false;
                operation.blockedDetail = decision.detail();
                operation.waitingDetail = decision.detail();
                return decision.detail();
            }
        }
        BlockState state = client.world.getBlockState(hit.getBlockPos());
        // Requiring the already-selected slot races Baritone autoTool: route
        // support may legitimately be selected immediately before a pickaxe
        // break. Carried suitability is the invariant; Baritone owns the swap.
        boolean safeTool = hasSafeSuitableTool(state);
        if (!state.isToolRequired() || safeTool) return null;
        client.interactionManager.cancelBlockBreaking();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getPathingBehavior().cancelEverything();
        String id = Registries.BLOCK.getId(state.getBlock()).getPath();
        operation.waitingDetail = "refusing to break " + id
                + " without a suitable tool; recalculating instead of wasting the block";
        return operation.waitingDetail;
    }

    private boolean hasSafeSuitableTool(BlockState state) {
        var player = client.player;
        if (player == null) return false;
        for (var stack : player.getInventory().getMainStacks()) {
            if (!stack.isEmpty() && stack.isSuitableFor(state) && remainingDurability(stack) > 1) return true;
        }
        return false;
    }

    private void applyPendingSafetyReplan() {
        if (!safetyReplanPending) return;
        if (active == null || !baritone.getPathingBehavior().isPathing()) {
            safetyReplanPending = false;
            return;
        }
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return;
        int position = executor.getPosition();
        var movements = executor.getPath().movements();
        if (position < 0 || position >= movements.size() || !movements.get(position).safeToCancel()) return;
        routeRecovery.requestReplan(System.currentTimeMillis());
        active.waitingDetail = "health or tool safety changed; retaining process and recalculating path";
        safetyReplanPending = false;
    }

    private int inventorySafetyHash() {
        var player = client.player;
        if (player == null) return 0;
        int hash = 1;
        for (var stack : player.getInventory().getMainStacks()) {
            hash = 31 * hash + stack.getItem().hashCode();
            hash = 31 * hash + stack.getCount();
            hash = 31 * hash + stack.getDamage();
        }
        return hash;
    }

    private static int remainingDurability(net.minecraft.item.ItemStack stack) {
        return stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
    }

    private int countTargetItems(Set<String> expectedItemIds) {
        if (client.player == null || expectedItemIds.isEmpty()) return 0;
        int count = 0;
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (expectedItemIds.contains(id)) count += stack.getCount();
        }
        return count;
    }

    private void cancelProcesses() {
        if (baritone instanceof ResourceDescentSafetyPolicy.NativeOwner owner)
            owner.entity2$resourceDescentRequired(false);
        clearFailedAscendEdges();
        if (active != null && active.blueprintPortal != null)
            actuators.completeExactAuthorization(active.goal.missionId() + ":blueprint-portal:" + active.blueprintPortal.asLong());
        if (blueprintSession != null) { blueprintSession.close(); blueprintSession = null; }
        blueprintBuildScope = null;
        blueprintCostProject = null;
        blueprintSupportRevision = -1;
        if (protectedAreas != null) protectedAreas.blueprintScope(null);
        actuators.blueprintScope(null);
        releaseIdleMiningLimit();
        routeRecovery.cancel();
        nativeProspecting.cancel();
        NativePropertyCosts.clear();
        releaseManagedFarmIngress(active, MovementFrameActuator.Cleanup.CANCEL);
        if (active != null) active.managedFarmIngress = null;
        if (active != null && active.resourceSession != null) {
            actuators.cancelBlockBreak(resourceBreakOperation(active));
            actuators.clearResourceBreakScope(
                    active.resourceSession.operationId());
        } else {
            actuators.clearResourceBreakScope();
        }
        functionalRoutes.cancelReplay("Baritone process ownership changed");
        clearLocalCollisionRecovery(active);
        applyThrowawayFence(null);
        suspendHuntPursuit(active);
        suspendProtectionCombatRoute(active);
        releaseAquaticTravel(active);
        clearAquaticCommitment(active);
        releaseRouteMomentum(active);
        releaseCombatHand(active);
        clearTacticalInputs(active);
        try {
            baritone.getFollowProcess().cancel();
        } catch (RuntimeException ignored) {
        }
        try {
            baritone.getMineProcess().cancel();
        } catch (RuntimeException ignored) {
        }
        try {
            baritone.getBuilderProcess().onLostControl();
        } catch (RuntimeException ignored) {
        }
        stopExploreProcess();
        try {
            baritone.getPathingBehavior().cancelEverything();
        } catch (RuntimeException ignored) {
        }
        try {
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException ignored) {
        }
        setActuatorScope(BaritoneActuatorScopePolicy.Owner.IDLE);
    }

    /**
     * Applies one mission-wide cargo fence to every route and unions mining's local target fence.
     * This prevents a goto-to-workstation movement from pillaring with ingredients committed to a
     * later craft, while leaving explicitly reserved BUILDING_BLOCKS available as traversal stock.
     */
    private void applyThrowawayFence(ActiveOperation operation) {
        LinkedHashSet<String> operationProtected = new LinkedHashSet<>();
        if (operation != null && isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))) {
            for (String expected : operation.expectedItemIds) {
                operationProtected.add(expected);
            }
            for (String blockName : operation.miningBlocks) {
                Identifier id = Identifier.tryParse(
                        blockName.indexOf(':') >= 0 ? blockName : "minecraft:" + blockName);
                if (id == null || !Registries.BLOCK.containsId(id)) continue;
                Item blockItem = Registries.BLOCK.get(id).asItem();
                if (blockItem != Items.AIR) {
                    operationProtected.add(Registries.ITEM.getId(blockItem).toString());
                }
            }
        }

        List<String> baseline = baselineAcceptableThrowawayItems.stream()
                .map(item -> Registries.ITEM.getId(item).toString())
                .toList();
        ThrowawayReservationPolicy.Decision decision = ThrowawayReservationPolicy.decide(
                baseline, missionProtectedThrowawayItems, operationProtected);
        Set<String> acceptable = Set.copyOf(decision.acceptableItems());
        ArrayList<Item> safe = new ArrayList<>();
        for (Item item : baselineAcceptableThrowawayItems) {
            if (acceptable.contains(Registries.ITEM.getId(item).toString())) safe.add(item);
        }
        BaritoneAPI.getSettings().acceptableThrowawayItems.value = safe;
        if (operation != null) {
            operation.protectedThrowawayItems = decision.protectedItems();
            configurePlacementCapability(operation, safe);
        }
    }

    private void configurePlacementCapability(
            ActiveOperation operation,
            Collection<Item> acceptableItems) {
        ArrayList<String> acceptable = new ArrayList<>();
        for (Item item : acceptableItems) {
            acceptable.add(Registries.ITEM.getId(item).toString());
        }

        ArrayList<String> carried = new ArrayList<>();
        if (client.player != null) {
            for (var stack : client.player.getInventory().getMainStacks()) {
                if (!stack.isEmpty()) {
                    carried.add(Registries.ITEM.getId(stack.getItem()).toString());
                }
            }
        }

        RouteMutationPolicy.Decision routeMutation = routeMutationDecision(operation);
        boolean requested = routeMutation.allowPlace();
        boolean enabled = ThrowawayReservationPolicy.canPlace(
                requested, acceptable, carried);
        var settings = BaritoneAPI.getSettings();
        settings.allowPlace.value = enabled;
        settings.allowParkourPlace.value = enabled
                && settings.allowParkour.value
                && routeMutation.allowParkourPlace();
    }

    private void releaseCombatHand(ActiveOperation operation) {
        if (operation == null || !operation.combatHandPrepared) return;
        BaritoneAPI.getSettings().autoTool.value = operation.previousAutoTool;
        BaritoneAPI.getSettings().allowInventory.value = operation.previousAllowInventory;
        operation.combatHandPrepared = false;
    }

    private void stopExploreProcess() {
        try {
            // IExploreProcess intentionally exposes no cancel method. Its supported public
            // lifecycle hook clears the exploration origin and therefore deactivates it.
            baritone.getExploreProcess().onLostControl();
        } catch (RuntimeException ignored) {
        }
        if (active != null) {
            active.huntFrontiers.clearCurrent();
        }
    }

    private void configureMaximumMovement() {
        var settings = BaritoneAPI.getSettings();
        // Baritone deliberately adds random yaw/pitch offsets for human-looking
        // input. Entity needs its native reachable-block aim, not artificial
        // wobble at a leaf edge or reach boundary. Keep LookBehavior as sole
        // native aim owner and preserve its normal mouse quantization.
        settings.randomLooking.value = 0D;
        settings.randomLooking113.value = 0D;
        settings.allowSprint.value = true;
        settings.sprintInWater.value = true;
        settings.sprintAscends.value = true;
        settings.assumeWalkOnWater.value = false;
        settings.allowParkour.value = true;
        settings.allowParkourAscend.value = true;
        settings.allowParkourPlace.value = true;
        settings.allowDiagonalAscend.value = true;
        settings.allowDiagonalDescend.value = true;
        settings.allowOvershootDiagonalDescend.value = true;
        settings.overshootTraverse.value = true;
        settings.allowDownward.value = true;
        settings.allowBreak.value = true;
        settings.allowPlace.value = true;
        // Route tooling is lease-scoped. Idle Baritone is physically unable to
        // race a direct inventory/workstation/combat owner for the hotbar.
        settings.allowInventory.value = false;
        settings.autoTool.value = false;
        // Do not walk away from a slow block and restart its damage animation.
        // Movement resumes only after the current atomic break completes.
        settings.walkWhileBreaking.value = false;
        settings.blockBreakSpeed.value = 1;
        settings.allowWaterBucketFall.value = true;
        settings.avoidance.value = true;
        settings.renderPath.value = true;
        settings.renderGoal.value = true;
        settings.renderSelectionBoxes.value = true;
        settings.renderCachedChunks.value = false;
        settings.primaryTimeoutMS.value = Math.max(settings.primaryTimeoutMS.value, 5_000L);
        settings.failureTimeoutMS.value = Math.max(settings.failureTimeoutMS.value, 2_000L);
    }

    private void setActuatorScope(BaritoneActuatorScopePolicy.Owner owner) {
        var settings = BaritoneAPI.getSettings();
        BaritoneActuatorScopePolicy.Scope scope =
                BaritoneActuatorScopePolicy.forOwner(owner);
        settings.allowInventory.value = scope.allowInventory();
        settings.autoTool.value = scope.autoTool();
    }

    /**
     * A retry of the same mission uses a failure-specific conservative route set. Every failed
     * traversal disables parkour; a proven direct/diagonal descent or diagonal ascent failure
     * additionally disables only the matching movement family. Other vertical movement remains
     * available after an unrelated route failure.
     */
    private void configureTraversalMode(ActiveOperation operation, long nowMillis) {
        configureTraversalMode(operation, routeDestination(operation), nowMillis);
    }

    private void configureTraversalMode(
            ActiveOperation operation,
            BlockPos destination,
            long nowMillis) {
        // The first native route calculation must already see protected cells.
        // Poll refreshes the same snapshot; a Home destination must not globally
        // disable digging out of an unprotected mine on its approach.
        publishNativePropertyCosts(operation);
        refreshFailedAscendEdges(operation, nowMillis);
        operation.propertyCrossingTraversal = routeIntersectsProtectedProperty(destination);
        TraversalWatchdog.RetryPolicy retryPolicy = applyTraversalRetryPolicy(
                operation.traversalBudgetKey, nowMillis);
        operation.conservativeTraversal = retryPolicy.avoidParkour();
        var settings = BaritoneAPI.getSettings();
        RouteMutationPolicy.Decision routeMutation = routeMutationDecision(operation);
        settings.allowBreak.value = routeMutation.allowBreak();
        configurePlacementCapability(operation, settings.acceptableThrowawayItems.value);
        // Baritone may otherwise plan a deep bucket fall from an inventory-only bucket, while the
        // direct survival owner can clutch only from a hand/hotbar slot. Combat pursuit never earns
        // permission to leave safe terrain by bucket-dropping after a hostile.
        settings.allowWaterBucketFall.value = routeMutation.allowWaterBucketFall();
    }

    private RouteMutationPolicy.Decision routeMutationDecision(ActiveOperation operation) {
        RouteMutationPolicy.Decision ordinary = RouteMutationPolicy.decide(
                operation.goal.kind(), operation.goal.arguments(), hasUsableClutchBucket());
        boolean available = protectedAreas != null && client.player != null
                && protectedAreas.observePropertyRegions().available();
        return ordinary.withPropertyCosts(available, operation.propertyCrossingTraversal);
    }

    private boolean routeIntersectsProtectedProperty(BlockPos destination) {
        if (protectedAreas == null || client.player == null) return true;
        ProtectedAreaClientState.PropertyRegionsObservation property =
                protectedAreas.observePropertyRegions();
        if (!property.available()) return true;
        if (destination == null || property.areas().isEmpty()) return false;
        List<ProtectedRouteProbe.Region> regions = property.areas().stream()
                .map(area -> new ProtectedRouteProbe.Region(
                        area.name(), area.dimension(),
                        area.minX(), area.maxX(), area.minZ(), area.maxZ()))
                .toList();
        return ProtectedRouteProbe.firstIntersectingRegion(
                currentDimension(),
                new ProtectedRouteProbe.Cell(
                        client.player.getBlockX(),
                        client.player.getBlockY(),
                        client.player.getBlockZ()),
                new ProtectedRouteProbe.Cell(
                        destination.getX(), destination.getY(), destination.getZ()),
                regions).isPresent();
    }

    private TraversalWatchdog.RetryPolicy applyTraversalRetryPolicy(
            String traversalBudgetKey,
            long nowMillis) {
        TraversalWatchdog.RetryPolicy retryPolicy =
                traversalRetryBudget.retryPolicy(traversalBudgetKey, nowMillis);
        var settings = BaritoneAPI.getSettings();
        settings.allowParkour.value = !retryPolicy.avoidParkour();
        settings.allowParkourAscend.value = !retryPolicy.avoidParkour();
        settings.allowDownward.value = !retryPolicy.avoidDownward();
        settings.allowDiagonalDescend.value = !retryPolicy.avoidDiagonalDescend();
        settings.allowOvershootDiagonalDescend.value =
                !retryPolicy.avoidOvershootDiagonalDescend();
        settings.allowDiagonalAscend.value = !retryPolicy.avoidDiagonalAscend();
        settings.sprintAscends.value = !retryPolicy.conservativeAscend();
        return retryPolicy;
    }

    private String activateProtectionTraversalScope(
            String episodeId,
            String targetId,
            ProtectionCombatContext combatContext) {
        ProtectionTraversalBudgetScope.Transition transition =
                protectionTraversalScope.enter(
                        episodeId, targetId, combatContext.name());
        if (transition.changed()) {
            if (!transition.previousKey().isEmpty()) {
                traversalRetryBudget.clear(transition.previousKey());
            }
            // A key may recur after A->B->A or STANDARD->SEALED->STANDARD. Its prior history
            // belongs to the ended scope and must not poison the fresh boundary.
            traversalRetryBudget.clear(transition.currentKey());
        }
        return transition.currentKey();
    }

    /**
     * Owns ordinary swim steering. Baritone supplies a route point, but while this policy owns
     * movement it writes one complete input frame after clearing Baritone's previous frame. This
     * deliberately avoids the old Baritone-override plus physical-key overlay, which could ask for
     * sprint, jump, and sneak simultaneously while the observed player only sprint-waded.
     */
    private void maintainAquaticTravel(ActiveOperation operation) {
        var player = client.player;
        long now = System.currentTimeMillis();
        if (operation.tacticalInputsActive) {
            // The tactical controller has already cleared/replaced these shared inputs. Forget our
            // old ownership without writing false over the newer retreat/shield commands.
            relinquishAquaticBookkeeping(operation);
            return;
        }
        boolean directAquaticOwner =
                operation.aquaticPursuitCustody.directAquaticOwnsMovement();
        AquaticTravelPolicy.Snapshot aquaticSnapshot = operation.aquaticTravel.snapshot();
        boolean observingBaritoneHandoff =
                AquaticRouteCustodyPolicy.observesBaritoneHandoff(
                        aquaticSnapshot.mode());
        boolean retainWaterlineProgress =
                AquaticRouteCustodyPolicy.retainWaterlineProgress(
                        aquaticSnapshot.mode(),
                        player != null && player.isTouchingWater());
        if ((!directAquaticOwner && !observingBaritoneHandoff)
                || (aquaticBaritoneBusy() && !observingBaritoneHandoff)) {
            // The operation has not crossed the exact Baritone-to-aquatic custody boundary.
            // Never overlay a direct movement frame on the route executor.
            if (retainWaterlineProgress) {
                // A dry sample at a shore lip is often only the top of one buoyancy cycle. Keep
                // the planar high-water/stall history so re-entering the same water column reaches
                // bounded sidestep recovery instead of starting a fresh SHORE episode forever.
                releaseAquaticInputsForHandoff(operation);
            } else {
                releaseAquaticTravel(operation);
            }
            return;
        }
        if (!operation.aquaticResumePending && aquaticRouteRequiresBaritoneInteraction()) {
            // Preserve Baritone's exact look + interaction frame while it opens the route cell.
            // Direct aquatic movement resumes automatically as soon as the destination is clear.
            if (observingBaritoneHandoff) {
                releaseAquaticInputsForHandoff(operation);
            } else {
                releaseAquaticTravel(operation);
            }
            return;
        }
        AquaticRoute missionRoute = currentAquaticRoute(operation, now);
        var velocity = player == null ? null : player.getVelocity();
        AquaticRouteEnvironmentPolicy.DeepWaterEntry deepWaterEntry =
                verifiedAquaticEntry(missionRoute);
        boolean supportingFloor = player != null
                && AquaticRouteEnvironmentPolicy.supportingFloor(player.isOnGround());
        if (player == null || !player.isTouchingWater()) {
            operation.aquaticResumePending = false;
            clearAquaticRediveCommitment(operation);
        }
        boolean resumedRoute = operation.aquaticResumePending;
        AquaticRouteEnvironmentPolicy.RediveAssessment rediveAssessment =
                AquaticRouteEnvironmentPolicy.RediveAssessment.notResuming();
        if (resumedRoute && player != null) {
            BlockPos eyeBlock = BlockPos.ofFloored(player.getEyePos());
            var eye = player.getEyePos();
            operation.aquaticRediveCommitment.observe(
                    new AquaticRouteEnvironmentPolicy.PathObservation(
                            eyeBlock.getX(), eyeBlock.getY(), eyeBlock.getZ(),
                            eye.x, eye.y, eye.z, player.isSubmergedInWater()));
            if (!operation.aquaticRediveCommitment.active()
                    && !player.isSubmergedInWater()) {
                rediveAssessment = assessAquaticRedive(deepWaterEntry, missionRoute);
                operation.aquaticRediveCommitment.install(rediveAssessment);
            } else {
                rediveAssessment = operation.aquaticRediveCommitment.assessment();
            }
        }
        AquaticRoute route = missionRoute;
        if (resumedRoute && operation.aquaticRediveCommitment.active()) {
            // The proved corridor is also the actuator contract. Bind to its exact next waypoint
            // before head submersion; aiming at the distant mission target here can strand the
            // body beside the entry while the waterline bobs across block-Y observations.
            route = aquaticRediveWaypointRoute(
                    operation.aquaticRediveCommitment.waypoint(), missionRoute);
        }
        AquaticTravelPolicy.Decision decision = operation.aquaticTravel.step(
                new AquaticTravelPolicy.Input(
                        player != null && player.isTouchingWater(),
                        player != null && player.isInLava(),
                        player != null && player.isSubmergedInWater(),
                        player != null && player.isSwimming(),
                        hasDeepWaterBelow(),
                        route != null,
                        route != null && route.directPursuit(),
                        aquaticAuthorityValid(operation, now),
                        operation.progressExpected,
                        operation.tacticalInputsActive,
                        player == null ? 0 : player.getAir(),
                        player == null ? 0 : player.getMaxAir(),
                        player == null ? Double.NaN : player.getX(),
                        player == null ? Double.NaN : player.getY(),
                        player == null ? Double.NaN : player.getZ(),
                        velocity == null ? 0.0 : Math.hypot(velocity.x, velocity.z),
                        route == null ? Double.NaN : route.horizontalDistance(),
                        route == null ? Double.NaN : route.verticalDistance(),
                        route != null && route.destinationDry(),
                        player != null && player.horizontalCollision,
                        hasAquaticCeiling(),
                        AquaticTravelPolicy.Objective.ROUTE,
                        resumedRoute,
                        player != null && (player.getHungerManager().getFoodLevel() > 6
                                || player.getAbilities().allowFlying),
                        supportingFloor,
                        deepWaterEntry.verified(),
                        rediveAssessment));
        if (decision.mode() == AquaticTravelPolicy.Mode.BARITONE_HANDOFF
                && player != null) {
            aquaticRouteHandoff.begin(
                    operation.goal.missionId(),
                    now,
                    player.getX(),
                    player.getZ(),
                    AQUATIC_BARITONE_HANDOFF_MIN_MILLIS,
                    AQUATIC_BARITONE_HANDOFF_MAX_MILLIS);
        }
        operation.aquaticBlockedDetail = decision.mode()
                == AquaticTravelPolicy.Mode.ROUTE_BLOCKED_AT_AIR
                ? decision.reason()
                : null;
        if (!directAquaticOwner && observingBaritoneHandoff) {
            // The travel policy must observe Baritone's later displacement and bounded tick
            // budget, but it cannot write a direct frame until the custody session crosses its
            // neutral boundary again.
            releaseAquaticInputsForHandoff(operation);
            operation.waitingDetail = decision.mode()
                    == AquaticTravelPolicy.Mode.BARITONE_HANDOFF
                    ? "Baritone owns the bounded aquatic obstacle-recovery window"
                    : "observed Baritone aquatic recovery; reclaiming direct custody";
            return;
        }
        if (decision.mode() == AquaticTravelPolicy.Mode.IDLE
                || decision.mode() == AquaticTravelPolicy.Mode.YIELD_FOR_AIR
                || player == null || route == null) {
            releaseAquaticTravel(operation);
            return;
        }
        if (!decision.ownsMovement()) {
            releaseAquaticInputsForHandoff(operation);
            return;
        }

        double steeringX = route.dx();
        double steeringZ = route.dz();
        if (decision.mode() == AquaticTravelPolicy.Mode.STEP_TO_DEEP_WATER
                && deepWaterEntry.verified()
                && Math.hypot(deepWaterEntry.steeringX(), deepWaterEntry.steeringZ()) > 0.025) {
            steeringX = deepWaterEntry.steeringX();
            steeringZ = deepWaterEntry.steeringZ();
        }
        float aquaticYaw = (float) (Math.toDegrees(Math.atan2(steeringZ, steeringX))
                - 90.0 + decision.yawOffsetDegrees());
        // This is the movement ownership boundary: no stale Baritone left/back/jump input and no
        // second physical-key channel survive into the aquatic command. Attack/use are separate
        // channels and remain untouched so an underwater block break is not silently cancelled.
        clearAquaticMovementOverrides();
        neutralizeAquaticPhysicalKeys();
        boolean priorAquaticFrameAccepted = operation.aquaticInputsForced;
        operation.aquaticInputsForced = submitAquaticFrame(new MovementFrame(
                decision.moveForward(),
                false,
                false,
                false,
                decision.jump(),
                decision.sneak(),
                decision.sprint(),
                aquaticYaw,
                decision.pitchDegrees()), now);
        if (!operation.aquaticInputsForced) {
            operation.aquaticTravel.reset();
            operation.waitingDetail = "direct aquatic pursuit selected, but its movement frame "
                    + "has not been accepted yet";
        } else if (route.directPursuit()) {
            AquaticTravelPolicy.Snapshot observed = operation.aquaticTravel.snapshot();
            boolean exactCurrentFrame = exactAquaticFrameObserved(operation);
            if (priorAquaticFrameAccepted && exactCurrentFrame) {
                if (!operation.aquaticDirectOriginKnown && player != null) {
                    operation.aquaticDirectOriginKnown = true;
                    operation.aquaticDirectOriginX = player.getX();
                    operation.aquaticDirectOriginY = player.getY();
                    operation.aquaticDirectOriginZ = player.getZ();
                } else if (observed.observedSwimTicks() > 0
                        && directAquaticDisplacement(operation) >= 0.20) {
                    operation.aquaticObservedMotion = true;
                }
            }
            boolean currentObservedSwim =
                    AquaticRouteCustodyPolicy.hasCurrentObservedDirectSwim(
                            exactCurrentFrame,
                            operation.aquaticObservedMotion,
                            observed.ownsMovement(),
                            observed.swimming(),
                            observed.observedSwimTicks());
            operation.waitingDetail = currentObservedSwim
                    ? "directly pursuing " + operation.targetDescription
                    + " with accepted and observed 3D water movement"
                    : exactCurrentFrame
                    ? "direct aquatic frame sampled exactly; awaiting observed swim movement toward "
                    + operation.targetDescription
                    : "direct aquatic frame submitted; awaiting its exact sampled input toward "
                    + operation.targetDescription;
        }
    }

    private boolean aquaticRouteRequiresBaritoneInteraction() {
        var player = client.player;
        if (player == null || client.world == null || !player.isTouchingWater()) return false;
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return false;
        int position = executor.getPosition();
        var movements = executor.getPath().movements();
        if (position < 0 || position >= movements.size()) return false;

        var movement = movements.get(position);
        var source = movement.getSrc();
        var destination = movement.getDest();
        BlockPos feet = new BlockPos(destination.x, destination.y, destination.z);
        boolean feetBlocked = routeBlockLoaded(feet)
                && !client.world.getBlockState(feet)
                .getCollisionShape(client.world, feet).isEmpty();
        BlockPos head = feet.up();
        boolean headBlocked = routeBlockLoaded(head)
                && !client.world.getBlockState(head)
                .getCollisionShape(client.world, head).isEmpty();
        boolean diagonalCornerBlocked = false;
        if (source.x != destination.x && source.z != destination.z) {
            BlockPos xCorner = new BlockPos(destination.x, destination.y, source.z);
            BlockPos zCorner = new BlockPos(source.x, destination.y, destination.z);
            diagonalCornerBlocked = routeBodyCellBlocked(xCorner)
                    || routeBodyCellBlocked(zCorner);
        }
        var input = baritone.getInputOverrideHandler();
        return AquaticRouteCustodyPolicy.decide(new AquaticRouteCustodyPolicy.Input(
                true,
                true,
                client.interactionManager != null
                        && client.interactionManager.isBreakingBlock(),
                input.isInputForcedDown(Input.CLICK_LEFT),
                input.isInputForcedDown(Input.CLICK_RIGHT),
                feetBlocked,
                headBlocked,
                diagonalCornerBlocked))
                == AquaticRouteCustodyPolicy.Decision.BARITONE_INTERACTION;
    }

    private boolean routeBodyCellBlocked(BlockPos feet) {
        if (!routeBlockLoaded(feet) || !routeBlockLoaded(feet.up())) return true;
        return !client.world.getBlockState(feet)
                        .getCollisionShape(client.world, feet).isEmpty()
                || !client.world.getBlockState(feet.up())
                        .getCollisionShape(client.world, feet.up()).isEmpty();
    }

    private boolean routeBlockLoaded(BlockPos position) {
        return client.world != null
                && client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4);
    }

    private boolean aquaticAuthorityValid(ActiveOperation operation, long nowMillis) {
        MovementAuthority binding = movementAuthority;
        return operation != null
                && binding != null
                && binding.parent().token().equals(operation.controlLease.token())
                && binding.parent().epoch() == operation.controlLease.epoch()
                && binding.authority().isValid(
                        binding.action(), operation.controlLease,
                        binding.clientTick(), nowMillis);
    }

    private boolean submitAquaticFrame(MovementFrame frame, long nowMillis) {
        MovementAuthority binding = movementAuthority;
        if (binding == null) return false;
        try {
            movement.submit(
                    binding.authority(), binding.parent(), binding.action(), frame,
                    binding.clientTick(), nowMillis);
            return true;
        } catch (IllegalStateException rejected) {
            logger.debug("Aquatic movement frame rejected at the exact lease boundary: {}",
                    rejected.getMessage());
            return false;
        }
    }

    private boolean exactAquaticFrameObserved(ActiveOperation operation) {
        if (operation == null || !operation.aquaticInputsForced
                || !operation.aquaticPursuitCustody.directAquaticOwnsMovement()) return false;
        MovementFrameActuator.Observation sampled = movement.snapshot().observation();
        return sampled.injectionSequence() > operation.aquaticDirectSampleBaseline
                && exactAquaticActionSample(sampled);
    }

    private boolean exactAquaticActionSample(MovementFrameActuator.Observation sampled) {
        MovementAuthority binding = movementAuthority;
        return sampled != null
                && binding != null
                && sampled.kind() == MovementFrameActuator.Kind.OWNED
                && sampled.exactMatch()
                && sampled.action().filter(binding.action()::equals).isPresent();
    }

    private double directAquaticDisplacement(ActiveOperation operation) {
        var player = client.player;
        if (operation == null || player == null
                || !operation.aquaticDirectOriginKnown) return 0.0;
        double dx = player.getX() - operation.aquaticDirectOriginX;
        double dy = player.getY() - operation.aquaticDirectOriginY;
        double dz = player.getZ() - operation.aquaticDirectOriginZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean hasDeepWaterBelow() {
        if (client.player == null || client.world == null) return false;
        BlockPos feet = client.player.getBlockPos();
        return client.world.getFluidState(feet).isIn(FluidTags.WATER)
                && client.world.getFluidState(feet.down()).isIn(FluidTags.WATER);
    }

    private AquaticRouteEnvironmentPolicy.DeepWaterEntry verifiedAquaticEntry(
            AquaticRoute route) {
        var player = client.player;
        if (player == null || client.world == null || route == null) {
            return AquaticRouteEnvironmentPolicy.DeepWaterEntry.none();
        }
        BlockPos origin = player.getBlockPos();
        int[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        ArrayList<AquaticRouteEnvironmentPolicy.DeepWaterCandidate> candidates =
                new ArrayList<>(offsets.length);
        for (int[] offset : offsets) {
            BlockPos feet = origin.add(offset[0], 0, offset[1]);
            BlockPos below = feet.down();
            BlockPos head = feet.up();
            boolean loaded = client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)
                    && client.world.isChunkLoaded(below.getX() >> 4, below.getZ() >> 4)
                    && client.world.isChunkLoaded(head.getX() >> 4, head.getZ() >> 4);
            boolean feetClear = loaded && client.world.getBlockState(feet)
                    .getCollisionShape(client.world, feet).isEmpty();
            boolean belowClear = loaded && client.world.getBlockState(below)
                    .getCollisionShape(client.world, below).isEmpty();
            boolean headClear = loaded && client.world.getBlockState(head)
                    .getCollisionShape(client.world, head).isEmpty();
            candidates.add(new AquaticRouteEnvironmentPolicy.DeepWaterCandidate(
                    feet.getX(), feet.getY(), feet.getZ(), loaded,
                    loaded && client.world.getFluidState(feet).isIn(FluidTags.WATER),
                    loaded && client.world.getFluidState(below).isIn(FluidTags.WATER),
                    belowClear, feetClear, headClear));
        }
        return AquaticRouteEnvironmentPolicy.selectDeepWaterEntry(
                player.getX(), player.getZ(), route.dx(), route.dz(), candidates);
    }

    private AquaticRouteEnvironmentPolicy.RediveAssessment assessAquaticRedive(
            AquaticRouteEnvironmentPolicy.DeepWaterEntry entry,
            AquaticRoute route) {
        var player = client.player;
        if (player == null || client.world == null || route == null) {
            return AquaticRouteEnvironmentPolicy.RediveAssessment.blocked(
                    "aquatic route blocked at air: the world adapter has no imminent route corridor");
        }
        BlockPos currentAir = BlockPos.ofFloored(player.getEyePos());
        return AquaticRouteEnvironmentPolicy.assessRediveRoute(
                player.getAir(),
                player.getMaxAir(),
                entry,
                new GridEscapePlanner.Point(
                        currentAir.getX(), currentAir.getY(), currentAir.getZ()),
                route.dx(),
                route.dz(),
                this::probeAquaticRediveCell,
                AQUATIC_REDIVE_LIMITS);
    }

    private AquaticRoute aquaticRediveWaypointRoute(
            GridEscapePlanner.Point waypoint,
            AquaticRoute missionRoute) {
        if (waypoint == null) return missionRoute;
        return aquaticRouteTo(
                waypoint.x() + 0.5,
                waypoint.y() + 0.10,
                waypoint.z() + 0.5,
                false,
                missionRoute != null && missionRoute.directPursuit());
    }

    private void clearAquaticRediveCommitment(ActiveOperation operation) {
        if (operation == null) return;
        operation.aquaticRediveCommitment.clear();
    }

    private GridEscapePlanner.Cell probeAquaticRediveCell(GridEscapePlanner.Point point) {
        if (client.world == null) return GridEscapePlanner.Cell.UNLOADED;
        BlockPos position = new BlockPos(point.x(), point.y(), point.z());
        if (position.getY() < client.world.getBottomY()
                || position.getY() > client.world.getTopYInclusive()) {
            return GridEscapePlanner.Cell.BLOCKED;
        }
        if (!client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
            return GridEscapePlanner.Cell.UNLOADED;
        }
        BlockState state = client.world.getBlockState(position);
        if (!state.getCollisionShape(client.world, position).isEmpty()) {
            return GridEscapePlanner.Cell.BLOCKED;
        }
        if (state.getFluidState().isIn(FluidTags.LAVA)) return GridEscapePlanner.Cell.HAZARD;
        if (state.getFluidState().isIn(FluidTags.WATER)) return GridEscapePlanner.Cell.WATER;
        if (!state.getFluidState().isEmpty() || hazardousAquaticAirCell(position, state)) {
            return GridEscapePlanner.Cell.HAZARD;
        }
        return GridEscapePlanner.Cell.BREATHABLE;
    }

    private boolean hazardousAquaticAirCell(BlockPos position, BlockState state) {
        BlockState below = client.world.getBlockState(position.down());
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.CACTUS)
                || below.isOf(Blocks.MAGMA_BLOCK) || below.isOf(Blocks.CAMPFIRE)
                || below.isOf(Blocks.SOUL_CAMPFIRE) || below.isOf(Blocks.CACTUS);
    }

    private boolean hasAquaticCeiling() {
        if (client.player == null || client.world == null) return false;
        BlockPos standingHead = BlockPos.ofFloored(
                client.player.getX(), client.player.getY() + 1.65, client.player.getZ());
        return !client.world.getBlockState(standingHead)
                .getCollisionShape(client.world, standingHead)
                .isEmpty();
    }

    private AquaticRoute currentAquaticRoute(ActiveOperation operation, long nowMillis) {
        if (client.player == null || client.world == null) return null;

        AquaticRoute fresh = directAquaticPlayerRoute(operation);
        if (fresh == null) fresh = baritoneAquaticRoute();
        if (fresh == null) fresh = operationAquaticRoute(operation);
        if (fresh != null) {
            operation.committedAquaticRoute = fresh;
            operation.aquaticRouteValidUntil = nowMillis + AQUATIC_ROUTE_COMMIT_MILLIS;
            return fresh;
        }

        // Path executors briefly disappear between segments and during recalculation. Continue
        // toward the already-owned world-space destination rather than dropping every swim key.
        AquaticRoute committed = operation.committedAquaticRoute;
        if (committed == null || nowMillis > operation.aquaticRouteValidUntil) return null;
        return aquaticRouteTo(
                committed.destinationX(),
                committed.destinationY(),
                committed.destinationZ(),
                committed.destinationDry(),
                committed.directPursuit());
    }

    private AquaticRoute takeSuspendedAquaticRoute(Goal goal, long nowMillis) {
        SuspendedAquaticRoute suspended = suspendedAquaticRoute;
        if (suspended == null) return null;
        suspendedAquaticRoute = null;
        if (nowMillis > suspended.expiresAtMillis()
                || !suspended.goal().equals(goal)
                || !suspended.dimension().equals(currentDimension())
                || client.player == null
                || !client.player.isTouchingWater()) {
            return null;
        }
        return aquaticRouteTo(
                suspended.destinationX(),
                suspended.destinationY(),
                suspended.destinationZ(),
                suspended.destinationDry(),
                suspended.directPursuit());
    }

    private AquaticRoute directAquaticPlayerRoute(ActiveOperation operation) {
        Entity target = directAquaticPlayerTarget(operation);
        if (target == null) return null;

        boolean targetDry = !target.isTouchingWater();
        double horizontalDistance = Math.hypot(
                target.getX() - client.player.getX(),
                target.getZ() - client.player.getZ());
        double targetY = AquaticPursuitDepthPolicy.targetY(
                client.player.getY(),
                target.getY() + Math.min(0.8, target.getHeight() * 0.5),
                horizontalDistance,
                targetDry);
        return aquaticRouteTo(
                target.getX(), targetY, target.getZ(),
                targetDry, true);
    }

    private Entity directAquaticPlayerTarget(ActiveOperation operation) {
        var player = client.player;
        if (player == null || !operation.playerTarget || !player.isTouchingWater()) return null;
        Entity target = nearestTarget(operation);
        return target;
    }

    /**
     * Executor objects disappear during recalculation and at water/land segment boundaries.  The
     * durable operation goal remains valid, so use it as the swim destination instead of dropping
     * sprint-swim ownership every time Baritone briefly has no current movement.
     */
    private AquaticRoute operationAquaticRoute(ActiveOperation operation) {
        var player = client.player;
        if (player == null || !player.isTouchingWater()) return null;
        if (operation.playerTarget && trackedWaypoint != null && trackedWaypoint.online()
                && trackedWaypoint.player().equalsIgnoreCase(operation.targetDescription)
                && currentDimension().equals(trackedWaypoint.dimension())) {
            boolean dry = destinationDry(
                    trackedWaypoint.x(), trackedWaypoint.y(), trackedWaypoint.z());
            double horizontal = Math.hypot(
                    trackedWaypoint.x() - player.getX(),
                    trackedWaypoint.z() - player.getZ());
            double targetY = AquaticPursuitDepthPolicy.targetY(
                    player.getY(), trackedWaypoint.y() + 0.5, horizontal, dry);
            return aquaticRouteTo(
                    trackedWaypoint.x(), targetY, trackedWaypoint.z(),
                    dry,
                    true);
        }
        String kind = operation.goal.kind().toLowerCase(Locale.ROOT);
        if (!kind.equals("goto") && !kind.equals("go")) return null;
        Map<String, String> args = operation.goal.arguments();
        double x = parseDouble(args.get("x"), Double.NaN);
        double y = parseDouble(args.get("y"), Double.NaN);
        double z = parseDouble(args.get("z"), Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        boolean dry = destinationDry(x, y, z);
        double horizontal = Math.hypot(x + 0.5 - player.getX(), z + 0.5 - player.getZ());
        double targetY = AquaticPursuitDepthPolicy.targetY(
                player.getY(), y + 0.2, horizontal, dry);
        return aquaticRouteTo(x + 0.5, targetY, z + 0.5, dry, false);
    }

    private boolean destinationDry(double x, double y, double z) {
        if (client.world == null) return false;
        BlockPos feet = BlockPos.ofFloored(x, y, z);
        if (!client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        return !client.world.getFluidState(feet).isIn(FluidTags.WATER)
                && !client.world.getFluidState(feet.up()).isIn(FluidTags.WATER);
    }

    private AquaticRoute baritoneAquaticRoute() {
        if (!baritone.getPathingBehavior().isPathing()) return null;
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return null;
        int position = executor.getPosition();
        var movements = executor.getPath().movements();
        if (position < 0 || position >= movements.size()) return null;

        var selected = movements.get(position).getDest();
        int last = Math.min(movements.size() - 1, position + 3);
        for (int index = position; index <= last; index++) {
            var candidate = movements.get(index).getDest();
            selected = candidate;
            if (isWalkableLandFrontier(new BlockPos(candidate.x, candidate.y, candidate.z))) break;
        }
        boolean dry = isWalkableLandFrontier(new BlockPos(selected.x, selected.y, selected.z));
        return aquaticRouteTo(
                selected.x + 0.5,
                selected.y + 0.2,
                selected.z + 0.5,
                dry,
                false);
    }

    private AquaticRoute aquaticRouteTo(
            double destinationX,
            double destinationY,
            double destinationZ,
            boolean destinationDry,
            boolean directPursuit) {
        if (client.player == null) return null;
        double dx = destinationX - client.player.getX();
        double dz = destinationZ - client.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double originY = directPursuit
                ? client.player.getY() + Math.min(0.8, client.player.getHeight() * 0.5)
                : client.player.getY();
        double vertical = destinationY - originY;
        return new AquaticRoute(
                destinationX, destinationY, destinationZ,
                dx, dz, horizontal, vertical, destinationDry, directPursuit);
    }

    private void releaseAquaticTravel(ActiveOperation operation) {
        if (operation == null) return;
        operation.aquaticBlockedDetail = null;
        clearAquaticRediveCommitment(operation);
        if (operation.aquaticInputsForced) {
            // Clear our complete movement frame at the ownership handoff. Baritone reconstructs
            // its own frame on the following tick instead of inheriting part of ours.
            clearAquaticMovementOverrides();
            cancelAquaticFrame(MovementFrameActuator.Cleanup.CANCEL);
            operation.aquaticInputsForced = false;
            neutralizeAquaticPhysicalKeys();
        }
        operation.aquaticTravel.reset();
    }

    /** Releases only our exact input frame while retaining the state machine's bounded handoff. */
    private void releaseAquaticInputsForHandoff(ActiveOperation operation) {
        if (operation == null) return;
        if (operation.aquaticInputsForced) {
            clearAquaticMovementOverrides();
            cancelAquaticFrame(MovementFrameActuator.Cleanup.OWNER_TRANSITION);
            operation.aquaticInputsForced = false;
            neutralizeAquaticPhysicalKeys();
        }
    }

    private void clearAquaticCommitment(ActiveOperation operation) {
        if (operation == null) return;
        clearAquaticRediveCommitment(operation);
        operation.committedAquaticRoute = null;
        operation.aquaticRouteValidUntil = 0;
    }

    private void relinquishAquaticBookkeeping(ActiveOperation operation) {
        if (operation == null) return;
        operation.aquaticBlockedDetail = null;
        clearAquaticRediveCommitment(operation);
        // The final input mixin would otherwise replay the prior aquatic heartbeat over tactical
        // movement. Cancel the exact capability; its one neutral sample is the owner boundary.
        cancelAquaticFrame(MovementFrameActuator.Cleanup.OWNER_TRANSITION);
        operation.aquaticInputsForced = false;
        operation.aquaticTravel.reset();
    }

    private void cancelAquaticFrame(MovementFrameActuator.Cleanup cleanup) {
        MovementAuthority binding = movementAuthority;
        if (binding != null) movement.cancel(binding.action(), cleanup);
    }

    private void neutralizeAquaticPhysicalKeys() {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }

    private void clearAquaticMovementOverrides() {
        var input = baritone.getInputOverrideHandler();
        input.setInputForceState(Input.MOVE_FORWARD, false);
        input.setInputForceState(Input.MOVE_BACK, false);
        input.setInputForceState(Input.MOVE_LEFT, false);
        input.setInputForceState(Input.MOVE_RIGHT, false);
        input.setInputForceState(Input.SPRINT, false);
        input.setInputForceState(Input.JUMP, false);
        input.setInputForceState(Input.SNEAK, false);
    }

    /** Keeps sprint held across flat and one-block-down path segments that Baritone chose. */
    private void maintainRouteMomentum(ActiveOperation operation) {
        var player = client.player;
        boolean higherPriorityMovement = operation.tacticalInputsActive
                || !operation.aquaticPursuitCustody.baritoneMayOwnMovement();
        if (higherPriorityMovement) {
            // That controller has already written the shared sprint key this tick. Relinquish our
            // bookkeeping without writing false over its newer command.
            operation.routeSprintForced = false;
            return;
        }
        var executor = baritone.getPathingBehavior().getCurrent();
        double verticalChange = Double.NaN;
        boolean routeActive = baritone.getPathingBehavior().isPathing() && executor != null;
        if (routeActive) {
            int position = executor.getPosition();
            var movements = executor.getPath().movements();
            if (position >= 0 && position < movements.size()) {
                var movement = movements.get(position);
                verticalChange = movement.getDest().y - movement.getSrc().y;
            }
        }
        boolean breaking = client.interactionManager != null
                && (client.interactionManager.isBreakingBlock()
                || client.interactionManager.getBlockBreakingProgress() >= 0);
        boolean canSprint = player != null && !player.isUsingItem()
                && (player.getHungerManager().getFoodLevel() > 6
                || player.getAbilities().allowFlying);
        RouteMomentumPolicy.Decision decision = RouteMomentumPolicy.decide(
                new RouteMomentumPolicy.Input(
                        routeActive,
                        arbiter.isValid(operation.controlLease, System.currentTimeMillis()),
                        canSprint,
                        player != null && player.isTouchingWater(),
                        player != null && player.isInLava(),
                        breaking,
                        false,
                        player != null && player.horizontalCollision,
                        verticalChange));
        if (!decision.forceSprint() || player == null) {
            releaseRouteMomentum(operation);
            return;
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        player.setSprinting(true);
        operation.routeSprintForced = true;
    }

    private void releaseRouteMomentum(ActiveOperation operation) {
        if (operation == null || !operation.routeSprintForced) return;
        try {
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        } catch (RuntimeException ignored) {
        }
        operation.routeSprintForced = false;
    }

    /**
     * Short, exact-body recovery for fences, corners, and failed jump faces.  It runs before the
     * seven-second route watchdog because repeatedly holding forward into a collision is already
     * conclusive after 1.5 seconds.  A bounded back/side step gives the existing Baritone goal a
     * different body cell from which to replan; normal slow block breaking is explicitly exempt.
     */
    private Status pollLocalCollisionRecovery(ActiveOperation operation, long nowMillis) {
        var player = client.player;
        if (operation.localCollisionRecoveryActive) {
            if (player == null || !arbiter.isValid(operation.controlLease, nowMillis)) {
                clearLocalCollisionRecovery(operation);
                return null;
            }
            if (nowMillis < operation.localCollisionRecoveryUntil) {
                var input = baritone.getInputOverrideHandler();
                input.setInputForceState(Input.MOVE_BACK, true);
                input.setInputForceState(Input.MOVE_LEFT, operation.localCollisionRecoverLeft);
                input.setInputForceState(Input.MOVE_RIGHT, !operation.localCollisionRecoverLeft);
                input.setInputForceState(Input.JUMP, player.isOnGround());
                input.setInputForceState(Input.SPRINT, false);
                client.options.backKey.setPressed(true);
                client.options.leftKey.setPressed(operation.localCollisionRecoverLeft);
                client.options.rightKey.setPressed(!operation.localCollisionRecoverLeft);
                client.options.jumpKey.setPressed(player.isOnGround());
                operation.progressExpected = true;
                return status(operation, State.EXECUTING,
                        "backing away from a repeated collision before replanning "
                                + operation.localCollisionRoute,
                        distanceRemaining());
            }
            clearLocalCollisionRecovery(operation);
            operation.localCollisionSince = 0;
            operation.waitingDetail = "local collision cleared; recommitting the existing goal";
            operation.progressExpected = true;
            // Cancellation also relinquishes the native process. Restore the same operation
            // (or retained mining exit), not just an input frame with no remaining goal owner.
            recommitAfterRouteSupportRecovery(operation);
            return status(operation, State.EXECUTING, operation.waitingDetail, distanceRemaining());
        }

        boolean breaking = client.interactionManager != null
                && client.interactionManager.isBreakingBlock();
        boolean eligible = player != null
                && operation.progressExpected
                && !operation.tacticalInputsActive
                && !operation.aquaticInputsForced
                && !player.isTouchingWater()
                && !player.isInLava()
                && player.isOnGround()
                && player.horizontalCollision
                && !breaking;
        if (!eligible) {
            operation.localCollisionSince = 0;
            return null;
        }

        if (operation.localCollisionSince == 0
                || squaredHorizontalDistance(
                player.getX(), player.getZ(),
                operation.localCollisionAnchorX, operation.localCollisionAnchorZ) > 0.35 * 0.35) {
            operation.localCollisionSince = nowMillis;
            operation.localCollisionAnchorX = player.getX();
            operation.localCollisionAnchorZ = player.getZ();
            return null;
        }
        if (nowMillis - operation.localCollisionSince < LOCAL_COLLISION_STALL_MILLIS) return null;

        if (isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))
                && operation.resourceSession == null && baritone.getMineProcess().isActive()) {
            operation.localCollisionSince = 0;
            beginAlternativeEntrance(operation, nowMillis);
            return status(operation, operation.blockedDetail == null ? State.EXECUTING : State.BLOCKED,
                    operation.blockedDetail == null ? "trying alternative mining access after collision"
                            : operation.blockedDetail, distanceRemaining());
        }

        String failedRoute = currentRouteSignature();
        retainFailedAscendEdge(operation, failedRoute, nowMillis);
        try {
            recordLocalCollisionGeometry(operation, failedRoute, nowMillis);
        } catch (RuntimeException diagnosticFailure) {
            // Missing evidence must not change the already-owned recovery action.
            logger.warn("Could not pin local collision geometry", diagnosticFailure);
        }
        TraversalWatchdog.Attempt attempt = traversalRetryBudget.recordFailure(
                operation.traversalBudgetKey, failedRoute, nowMillis);
        if (attempt.exhausted()) {
            cancelProcesses();
            operation.blockedDetail = "repeated collision at " + player.getBlockPos().toShortString()
                    + (failedRoute.isBlank() ? "" : " on " + failedRoute)
                    + "; three bounded back-off/replan attempts made no progress. Change the terrain"
                    + " or use /e retry";
            return status(
                    operation,
                    State.BLOCKED,
                    operation.blockedDetail,
                    distanceRemaining(),
                    BaritonePort.FailureCause.TARGET_ROUTE_EXHAUSTED);
        }

        var failedAscend = NativeFailedAscendEdges.Edge.fromRouteSignature(failedRoute);
        boolean observedFailedAscend = failedAscend != null && operation.failedAscendEdges.stream()
                .anyMatch(failure -> failure.edge().equals(failedAscend));
        boolean nativeMiningActive = baritone.getMineProcess().isActive();
        try {
            var pathing = baritone.getPathingBehavior();
            boolean canceled = pathing.cancelEverything();
            // Native Ascend refuses safe cancellation once support-placement ticks begin.
            // That is essential during an airborne movement, but a failed placement can keep
            // it RUNNING forever while standing on unchanged stone. Never add recovery inputs
            // beside that executor. Only this observed, grounded, independently supported case
            // may abandon it; resource mining and every airborne/fluid/breaking case are exempt.
            if (!canceled && NativeStalledAscendHandoff.mayAbortUncancelableMovement(
                    new NativeStalledAscendHandoff.Evidence(
                            arbiter.isValid(operation.controlLease, nowMillis), observedFailedAscend,
                            player.isOnGround(), !stableBodySupportContacts().isEmpty(),
                            player.isTouchingWater() || player.isInLava(), breaking, nativeMiningActive))) {
                pathing.forceCancel();
                logger.info("Abandoned grounded stalled native ascent {}; exclusive goal replan follows", failedRoute);
            }
            if (pathing.getCurrent() != null || pathing.isPathing()) {
                operation.localCollisionSince = nowMillis;
                operation.waitingDetail = "waiting for native movement to relinquish control before recovery";
                return status(operation, State.EXECUTING, operation.waitingDetail, distanceRemaining());
            }
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException handoffFailure) {
            operation.localCollisionSince = nowMillis;
            logger.warn("Could not obtain exclusive collision recovery custody", handoffFailure);
            return status(operation, State.EXECUTING,
                    "native collision handoff pending; no competing recovery inputs", distanceRemaining());
        }
        if (observedFailedAscend) {
            // The planner now knows this exact directed edge failed. Search from the actual
            // standing body immediately instead of injecting another jump into the same face.
            operation.localCollisionSince = 0;
            operation.traversalWatchdog.reset();
            refreshFailedAscendEdges(operation, nowMillis);
            recommitAfterRouteSupportRecovery(operation);
            operation.waitingDetail = "replanning the same goal around the observed failed ascent";
            operation.progressExpected = true;
            return status(operation, State.EXECUTING, operation.waitingDetail, distanceRemaining());
        }
        operation.localCollisionRecoveryActive = true;
        operation.localCollisionRecoveryUntil = nowMillis + LOCAL_COLLISION_RECOVERY_MILLIS;
        operation.localCollisionRecoverLeft = (attempt.number() & 1) == 1;
        operation.localCollisionRoute = failedRoute.isBlank() ? "the blocked body cell" : failedRoute;
        operation.localCollisionSince = 0;
        operation.traversalWatchdog.reset();
        operation.progressExpected = true;
        return status(operation, State.EXECUTING,
                "collision persisted for 1.5s; starting bounded local recovery attempt "
                        + attempt.number() + '/' + attempt.maximum(),
                distanceRemaining());
    }

    /** A confirmed failed edge is planner feedback, not a global prohibition on mining or jumping. */
    private void retainFailedAscendEdge(ActiveOperation operation, String route, long nowMillis) {
        if (nativeRouteWorldIdentity.isBlank() || !arbiter.isValid(operation.controlLease, nowMillis)) return;
        NativeFailedAscendEdges.Edge edge = NativeFailedAscendEdges.Edge.fromRouteSignature(route);
        if (edge == null) return;
        String geometry = failedAscendGeometry(edge);
        if (geometry == null) return;
        if (!operation.failedAscendDimension.equals(currentDimension())) operation.failedAscendEdges.clear();
        operation.failedAscendDimension = currentDimension();
        operation.failedAscendEdges.removeIf(failure -> failure.edge().equals(edge));
        operation.failedAscendEdges.add(new NativeFailedAscendEdges.Failure(edge, geometry, nowMillis));
        refreshFailedAscendEdges(operation, nowMillis);
        logger.info("Native ascent rejected after observed collision: {}; same operation replans with all other edges available", edge);
    }

    private void refreshFailedAscendEdges(ActiveOperation operation, long nowMillis) {
        if (operation == null || !playerContextReady() || nativeRouteWorldIdentity.isBlank()
                || !arbiter.isValid(operation.controlLease, nowMillis)) {
            clearFailedAscendEdges();
            return;
        }
        if (!operation.failedAscendDimension.equals(currentDimension())) operation.failedAscendEdges.clear();
        operation.failedAscendEdges.removeIf(failure -> {
            String current = failedAscendGeometry(failure.edge());
            return current == null || !current.equals(failure.geometryFingerprint());
        });
        var holder = ((NativeFailedAscendEdges.Owner) baritone).entity2$failedAscendEdges();
        holder.publish(new NativeFailedAscendEdges.Scope(operation.goal.missionId(),
                        operation.controlEpoch, nativeRouteWorldIdentity, currentDimension()),
                operation.failedAscendEdges, nowMillis);
        // Keep operation memory bounded by the same immutable, expiring native snapshot.
        operation.failedAscendEdges.clear();
        operation.failedAscendEdges.addAll(holder.snapshot().failures());
    }

    /** Loaded exact state IDs only, captured on the client thread, never read by native A* workers. */
    private String failedAscendGeometry(NativeFailedAscendEdges.Edge edge) {
        if (client.world == null) return null;
        StringBuilder fingerprint = new StringBuilder(currentDimension());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    BlockPos pos = new BlockPos(edge.sourceX() + dx, edge.sourceY() + dy, edge.sourceZ() + dz);
                    if (!client.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return null;
                    fingerprint.append(',').append(Block.getRawIdFromState(client.world.getBlockState(pos)));
                }
            }
        }
        return fingerprint.toString();
    }

    private void clearFailedAscendEdges() {
        ((NativeFailedAscendEdges.Owner) baritone).entity2$failedAscendEdges().clear();
    }

    /** Pin live shapes before recovery cancels the edge; aggregate/stale chunk facts are insufficient. */
    private void recordLocalCollisionGeometry(ActiveOperation operation, String route, long nowMillis) {
        if (client.player == null || client.world == null) return;
        JsonObject report = new JsonObject();
        report.addProperty("at", nowMillis);
        report.addProperty("mission", operation.goal.missionId().toString());
        report.addProperty("route", route);
        report.addProperty("dimension", currentDimension());
        report.addProperty("nativeReturn", operation.functionalNativeReturn);
        report.addProperty("position", client.player.getPos().toString());
        report.addProperty("bodyBox", client.player.getBoundingBox().toString());
        report.addProperty("velocity", client.player.getVelocity().toString());
        report.addProperty("yaw", client.player.getYaw());
        report.addProperty("pose", client.player.getPose().name());
        report.addProperty("onGround", client.player.isOnGround());
        var settings = BaritoneAPI.getSettings();
        report.addProperty("allowBreak", settings.allowBreak.value);
        report.addProperty("allowPlace", settings.allowPlace.value);
        report.addProperty("sprintAscends", settings.sprintAscends.value);
        JsonArray cells = new JsonArray();
        BlockPos feet = client.player.getBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    BlockPos pos = feet.add(dx, dy, dz);
                    JsonObject cell = new JsonObject();
                    cell.addProperty("cell", pos.toShortString());
                    boolean loaded = client.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
                    cell.addProperty("loaded", loaded);
                    if (loaded) {
                        BlockState state = client.world.getBlockState(pos);
                        cell.addProperty("state", state.toString());
                        JsonArray shapes = new JsonArray();
                        for (Box shape : state.getCollisionShape(client.world, pos).getBoundingBoxes())
                            shapes.add(shape.offset(pos).toString());
                        cell.add("worldCollisionBoxes", shapes);
                        var decision = actuators.inspectProtectedArea(ProtectedAreaPolicy.Action.BREAK,
                                currentDimension(), pos.getX(), pos.getY(), pos.getZ());
                        cell.addProperty("protectedBreakAllowed", decision.map(ProtectedAreaPolicy.Decision::allowed).orElse(true));
                        cell.addProperty("safeToolAvailable", !state.isToolRequired() || hasSafeSuitableTool(state));
                    }
                    cells.add(cell);
                }
            }
        }
        report.add("cells", cells);
        logger.warn("Entity2 local collision geometry {}", report);
    }

    private void clearLocalCollisionRecovery(ActiveOperation operation) {
        if (operation == null || !operation.localCollisionRecoveryActive) return;
        try {
            var input = baritone.getInputOverrideHandler();
            input.setInputForceState(Input.MOVE_BACK, false);
            input.setInputForceState(Input.MOVE_LEFT, false);
            input.setInputForceState(Input.MOVE_RIGHT, false);
            input.setInputForceState(Input.JUMP, false);
        } catch (RuntimeException ignored) {
        }
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        operation.localCollisionRecoveryActive = false;
        operation.localCollisionRecoveryUntil = 0;
    }

    private static double squaredHorizontalDistance(
            double x,
            double z,
            double otherX,
            double otherZ) {
        double dx = x - otherX;
        double dz = z - otherZ;
        return dx * dx + dz * dz;
    }

    private Status pollTraversalWatchdog(ActiveOperation operation, long nowMillis) {
        var executor = baritone.getPathingBehavior().getCurrent();
        boolean expected = operation.progressExpected
                && !operation.tacticalInputsActive
                && operation.aquaticPursuitCustody.baritoneMayOwnMovement()
                && operation.aquaticBlockedDetail == null
                && client.player != null;
        boolean calculating = expected && executor == null
                && baritone.getPathingBehavior().getInProgress().isPresent();
        String signature = "";
        if (executor != null) {
            int position = executor.getPosition();
            var movements = executor.getPath().movements();
            if (position >= 0 && position < movements.size()) {
                var movement = movements.get(position);
                signature = movement.getClass().getSimpleName() + ':' + position + ':'
                        + movement.getSrc().x + ',' + movement.getSrc().y + ',' + movement.getSrc().z
                        + "->"
                        + movement.getDest().x + ',' + movement.getDest().y + ',' + movement.getDest().z;
            }
        }
        boolean productiveWork = operation.completedWorkUnits > operation.lastWatchdogWorkUnits;
        operation.lastWatchdogWorkUnits = operation.completedWorkUnits;
        var player = client.player;
        TraversalWatchdog.Position bodyPosition = player == null ? null
                : new TraversalWatchdog.Position(player.getX(), player.getY(), player.getZ());
        TraversalWatchdog.Result result = operation.traversalWatchdog.observe(
                new TraversalWatchdog.Observation(
                        signature,
                        expected,
                        calculating,
                        executor != null,
                        productiveWork,
                        player != null && player.isOnGround(),
                        bodyPosition,
                        expected && !calculating
                                ? traversalGoalDistance(operation)
                                : Double.NaN,
                        nowMillis));
        if (result.state() != TraversalWatchdog.State.STALLED) return null;

        // Baritone's feet cell is the route coordinate contract. Vanilla's
        // floored entity position can momentarily report the supporting block
        // after a teleport/settle tick, which would misname protected floor as
        // the obstruction even though bodyCellsAlongLine deliberately excludes it.
        BlockPos feet = client.player == null
                ? BlockPos.ORIGIN
                : baritone.getPlayerContext().playerFeet();
        String route = result.failedRouteSignature().isBlank()
                ? "an executor gap"
                : result.failedRouteSignature();
        String detail = "Baritone made no physical, goal, or work progress on " + route
                + " for " + Math.max(1, result.stalledForMillis() / 1_000) + "s near "
                + feet.getX() + ' ' + feet.getY() + ' ' + feet.getZ()
                + (result.routeChangesWithoutProgress() > 0
                ? " despite " + result.routeChangesWithoutProgress()
                + " executor edge change(s)"
                : "");
        if (operation.goal.kind().equalsIgnoreCase("hunt")
                && operation.attackTarget != null) {
            return rejectHuntTarget(operation, detail, nowMillis);
        }
        return traversalFailureStatus(
                operation, detail, result.failedRouteSignature(), nowMillis);
    }

    private Status traversalFailureStatus(
            ActiveOperation operation,
            String reason,
            long nowMillis) {
        return traversalFailureStatus(operation, reason, "", nowMillis);
    }

    private Status traversalFailureStatus(
            ActiveOperation operation,
            String reason,
            String failedRouteSignature,
            long nowMillis) {
        Status protectedObstruction = protectedRouteObstructionStatus(operation);
        if (protectedObstruction != null) return protectedObstruction;
        if (requestMiningAccessTool(operation))
            return status(operation, State.TRANSIENT_FAILURE, operation.waitingDetail, Double.NaN);
        operation.progressExpected = false;
        operation.traversalWatchdog.reset();
        if (isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))
                && client.player != null) {
            BlockPos feet = baritone.getPlayerContext().playerFeet();
            if (operation.lastRecoveryPosition != null
                    && (operation.lastRecoveryPosition.getSquaredDistance(feet) >= 4
                    || operation.completedWorkUnits > operation.lastRecoveryWork)) {
                traversalRetryBudget.clear(operation.traversalBudgetKey);
                operation.alternateEntrances.clear();
            }
            operation.lastRecoveryPosition = feet.toImmutable();
            operation.lastRecoveryWork = operation.completedWorkUnits;
        }
        TraversalWatchdog.Attempt attempt =
                traversalRetryBudget.recordFailure(
                        operation.traversalBudgetKey, failedRouteSignature, nowMillis);

        if (isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))
                && operation.resourceSession == null && baritone.getMineProcess().isActive()) {
            boolean detouring = !attempt.exhausted()
                    && beginAlternativeEntrance(operation, nowMillis);
            if (detouring) return status(operation, State.EXECUTING,
                    reason + "; retaining mining search and trying a different entrance",
                    distanceRemaining());
            if (beginMiningEntranceRetreat(operation, nowMillis))
                return status(operation, State.EXECUTING, operation.waitingDetail, distanceRemaining());
            operation.blockedDetail = reason + "; available alternative entrances exhausted";
            cancelProcesses();
            return status(operation, State.BLOCKED, operation.blockedDetail, distanceRemaining());
        }

        // Retry policy is derived from the failed movement geometry, so a descent/ascent failure
        // changes the movement family that actually selected it.
        operation.conservativeTraversal = true;
        TraversalWatchdog.RetryPolicy retryPolicy = applyTraversalRetryPolicy(
                operation.traversalBudgetKey, nowMillis);

        String recordedEdge = attempt.routeSignature().isBlank()
                ? ""
                : "; recorded failed edge " + attempt.routeSignature()
                + (attempt.sameRouteFailures() > 1
                ? " (seen " + attempt.sameRouteFailures() + " times)"
                : "");
        String activeRestrictions = "; conservative policy disables "
                + traversalRestrictionSummary(retryPolicy)
                + (attempt.movementClass() == TraversalWatchdog.FailedMovementClass.OTHER
                ? " before the bounded replan"
                : " so the failed movement class cannot win unchanged");
        if (attempt.exhausted()) {
            cancelProcesses();
            operation.blockedDetail = reason + recordedEdge + activeRestrictions
                    + "; stopped after " + attempt.number()
                    + " failed route attempts in two minutes instead of retrying forever. "
                    + "Change the terrain or supplies, then use /e retry";
            return status(
                    operation,
                    State.BLOCKED,
                    operation.blockedDetail,
                    distanceRemaining(),
                    BaritonePort.FailureCause.TARGET_ROUTE_EXHAUSTED);
        }
        if (attempt.movementClass() == TraversalWatchdog.FailedMovementClass.ASCEND) {
            beginStalledAscendRecovery(operation, attempt, nowMillis);
            return status(
                    operation,
                    State.EXECUTING,
                    reason + recordedEdge + " (route attempt " + attempt.number() + '/'
                            + attempt.maximum() + ')' + activeRestrictions
                            + "; backing off the failed ascent before its existing goal replans",
                    distanceRemaining());
        }
        if (operation.functionalNativeReturn) {
            operation.lastEvent = null;
            lastPathEvent.set(null);
            if (!routeRecovery.requestReplan(nowMillis)) {
                recommitAfterRouteSupportRecovery(operation);
            }
            operation.progressExpected = true;
            return status(operation, State.EXECUTING,
                    reason + recordedEdge + " (route attempt " + attempt.number() + '/'
                            + attempt.maximum() + ')' + activeRestrictions
                            + "; replanning the retained original-entrance exit",
                    distanceRemaining());
        }
        cancelProcesses();
        if (operation.goal.kind().equals("protection_escape")) {
            // Replan the same pinned non-attack goal with the already-debited native budget.
            // A later exhausted attempt remains BLOCKED in this operation, not a fresh start.
            operation.lastEvent = null;
            lastPathEvent.set(null);
            operation.progressExpected = true;
            applyThrowawayFence(operation);
            setActuatorScope(BaritoneActuatorScopePolicy.Owner.ROUTE);
            configureTraversalMode(operation, nowMillis);
            refreshBreakSafety(true);
            startProtectionEscapeGoal(operation);
            return status(operation, State.TRANSIENT_FAILURE,
                    reason + recordedEdge + " (route attempt " + attempt.number() + '/'
                            + attempt.maximum() + ')' + activeRestrictions
                            + "; replanning the retained native separation goal", distanceRemaining());
        }
        String fallback = attempt.number() == 1
                ? "; replanning so Baritone can route around, break, or place"
                : "; conservative route also failed; one final bounded replan remains";
        return status(operation, State.TRANSIENT_FAILURE,
                reason + recordedEdge + " (route attempt " + attempt.number() + '/'
                        + attempt.maximum() + ')' + activeRestrictions + fallback,
                distanceRemaining());
    }

    /**
     * A stalled corner is not proof that an entire protected building is sealed.
     * Try a relevant loaded portal approach before naming the wall as a blocker.
     */
    private Status protectedRouteObstructionStatus(ActiveOperation operation) {
        RouteMutationPolicy.Decision mutation = routeMutationDecision(operation);
        if (mutation.allowBreak() || mutation.allowPlace()
                || mutation.allowWaterBucketFall()) return null;
        BlockPos destination = routeDestination(operation);
        if (destination == null || client.player == null || client.world == null) return null;

        String dimension = currentDimension();
        BlockPos routeFeet = baritone.getPlayerContext().playerFeet();
        ProtectedRouteProbe.Cell start = new ProtectedRouteProbe.Cell(
                routeFeet.getX(), routeFeet.getY(), routeFeet.getZ());
        ProtectedRouteProbe.Cell end = new ProtectedRouteProbe.Cell(
                destination.getX(), destination.getY(), destination.getZ());
        for (ProtectedRouteProbe.Cell cell
                : ProtectedRouteProbe.bodyCellsAlongLine(start, end, 512)) {
            BlockPos position = new BlockPos(cell.x(), cell.y(), cell.z());
            if (!client.world.getChunkManager().isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) continue;
            BlockState state = client.world.getBlockState(position);
            if (state.getCollisionShape(client.world, position).isEmpty()) continue;
            ProtectedAreaPolicy.Decision decision = actuators.inspectProtectedArea(
                            ProtectedAreaPolicy.Action.BREAK,
                            dimension,
                            position.getX(), position.getY(), position.getZ())
                    .filter(candidate -> !candidate.allowed()
                            && candidate.code().equals("protected_area"))
                    .orElse(null);
            if (decision == null) continue;
            if (beginProtectedPortalApproach(operation, destination, decision.areaName())) {
                return status(operation, State.EXECUTING,
                        "protected wall blocks this approach; routing to the nearby door before continuing",
                        distanceRemaining());
            }
            cancelProcesses();
            operation.progressExpected = false;
            operation.blockedDetail = "No known non-destructive route: protected area '"
                    + decision.areaName() + "' blocks travel at "
                    + decision.x() + ' ' + decision.y() + ' ' + decision.z()
                    + " in " + decision.dimension()
                    + "; block breaking, scaffolding, and bucket-fall placement are disabled";
            operation.waitingDetail = operation.blockedDetail;
            return status(
                    operation,
                    State.BLOCKED,
                    operation.blockedDetail,
                    distanceRemaining(),
                    BaritonePort.FailureCause.WORLD_POLICY_DENIED);
        }
        return null;
    }

    private boolean beginProtectedPortalApproach(
            ActiveOperation operation, BlockPos destination, String areaName) {
        if (routeRecovery.active() || doorPassage.snapshot().intent() != null
                || operation.portalApproaches.size() >= 4) return false;
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (BlockPos cell : BlockPos.iterate(feet.add(-8, -2, -8), feet.add(8, 2, 8))) {
            if (!client.world.getChunkManager().isChunkLoaded(cell.getX() >> 4, cell.getZ() >> 4)) continue;
            MinecraftActuatorGateway.PortalState portal = actuators.inspectPortal(cell).orElse(null);
            if (portal == null || portal.y() != cell.getY() || portal.normalY() != 0) continue;
            var policy = actuators.inspectProtectedArea(ProtectedAreaPolicy.Action.BREAK,
                    currentDimension(), portal.x(), portal.y(), portal.z()).orElse(null);
            if (policy == null || policy.allowed() || !areaName.equals(policy.areaName())) continue;
            var approach = RouteRecoveryGeometry.portalApproach(portal.x(), portal.y(), portal.z(),
                    portal.normalX(), portal.normalZ(), client.player.getX(), client.player.getZ(),
                    destination.getX() + 0.5, destination.getZ() + 0.5);
            if (approach == null) continue;
            BlockPos stance = new BlockPos(approach.x(), approach.y(), approach.z());
            if (operation.portalApproaches.contains(stance) || stance.equals(feet)
                    || !clearSupportedPortalStance(stance)) continue;
            candidates.add(stance);
        }
        candidates.sort(Comparator.comparingDouble(position -> position.getSquaredDistance(feet)));
        List<BlockPos> selected = candidates.stream().distinct().limit(1).toList();
        if (selected.isEmpty()) return false;
        operation.portalApproaches.addAll(selected);
        operation.alternateRouteRunning = routeRecovery.begin(selected, System.currentTimeMillis());
        operation.portalApproachRunning = operation.alternateRouteRunning;
        operation.lastEvent = null;
        lastPathEvent.set(null);
        operation.traversalWatchdog.reset();
        operation.progressExpected = false; // One return-to-approach objective boundary.
        logger.info("Protected portal approach via {} retains original destination {}", selected, destination);
        return operation.alternateRouteRunning;
    }

    private boolean clearSupportedPortalStance(BlockPos stance) {
        if (!client.world.getChunkManager().isChunkLoaded(stance.getX() >> 4, stance.getZ() >> 4)
                || !dev.entity.client.autonomy.workspace.MinecraftWorkspaceProbe
                        .hasStableFullTopSupport(client, stance.down())) return false;
        for (BlockPos cell : List.of(stance, stance.up())) {
            BlockState state = client.world.getBlockState(cell);
            if (!state.getFluidState().isEmpty() || combatRouteHazardId(state) != null
                    || !state.getCollisionShape(client.world, cell).isEmpty()) return false;
        }
        return true;
    }

    private String describePortalStance(BlockPos stance) {
        if (client.world == null) return "world unavailable";
        BlockPos support = stance.down();
        boolean chunkLoaded = client.world.getChunkManager().isChunkLoaded(
                stance.getX() >> 4, stance.getZ() >> 4);
        BlockState supportState = client.world.getBlockState(support);
        StringBuilder detail = new StringBuilder("chunkLoaded=").append(chunkLoaded)
                .append(" support=").append(Registries.BLOCK.getId(supportState.getBlock()))
                .append(" supportTop=").append(supportState.isSideSolidFullSquare(
                        client.world, support, Direction.UP));
        for (BlockPos cell : List.of(stance, stance.up())) {
            BlockState state = client.world.getBlockState(cell);
            detail.append(" cell@").append(cell.getY()).append('=')
                    .append(Registries.BLOCK.getId(state.getBlock()))
                    .append(" fluidEmpty=").append(state.getFluidState().isEmpty())
                    .append(" collisionEmpty=")
                    .append(state.getCollisionShape(client.world, cell).isEmpty())
                    .append(" hazard=").append(combatRouteHazardId(state));
        }
        return detail.toString();
    }

    private BlockPos routeDestination(ActiveOperation operation) {
        String kind = operation.goal.kind().toLowerCase(Locale.ROOT);
        if (kind.equals("goto") || kind.equals("go")) {
            Map<String, String> args = operation.goal.arguments();
            int fallbackY = client.player == null
                    ? 0
                    : client.player.getBlockY();
            return new BlockPos(
                    integer(args, "x"),
                    parseInteger(args.get("y"), fallbackY),
                    integer(args, "z"));
        }
        if (operation.attackTarget != null
                && operation.attackTarget.isAlive()
                && !operation.attackTarget.isRemoved()) {
            return operation.attackTarget.getBlockPos();
        }
        Entity visible = nearestTarget(operation);
        if (visible != null) return visible.getBlockPos();
        TrackedWaypoint waypoint = trackedWaypoint;
        if (operation.playerTarget && waypoint != null
                && waypoint.player().equalsIgnoreCase(operation.targetDescription)
                && waypoint.dimension().equals(currentDimension())) {
            return BlockPos.ofFloored(waypoint.x(), waypoint.y(), waypoint.z());
        }
        return null;
    }

    private static String traversalRestrictionSummary(
            TraversalWatchdog.RetryPolicy retryPolicy) {
        ArrayList<String> restrictions = new ArrayList<>();
        if (retryPolicy.avoidParkour()) restrictions.add("parkour");
        if (retryPolicy.avoidDownward()) restrictions.add("downward shaft moves");
        if (retryPolicy.avoidDiagonalDescend()) restrictions.add("diagonal descents");
        if (retryPolicy.avoidOvershootDiagonalDescend()) {
            restrictions.add("overshoot-descend moves");
        }
        if (retryPolicy.avoidDiagonalAscend()) restrictions.add("diagonal ascents");
        if (retryPolicy.conservativeAscend()) restrictions.add("sprint ascents");
        return restrictions.isEmpty()
                ? "no movement families"
                : String.join(", ", restrictions);
    }

    /**
     * Repositions the exact body for one bounded interval without canceling the owning process.
     * MineProcess can therefore retain its target blacklist/search state and replan from the new
     * cell; rebuilding it here would simply select the same closest block again.
     */
    private void beginStalledAscendRecovery(
            ActiveOperation operation,
            TraversalWatchdog.Attempt attempt,
            long nowMillis) {
        try {
            baritone.getPathingBehavior().cancelEverything();
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException ignored) {
        }
        releaseRouteMomentum(operation);
        operation.localCollisionRecoveryActive = true;
        operation.localCollisionRecoveryUntil = nowMillis + LOCAL_COLLISION_RECOVERY_MILLIS;
        operation.localCollisionRecoverLeft = (attempt.number() & 1) == 1;
        operation.localCollisionRoute = attempt.routeSignature().isBlank()
                ? "the stalled ascent"
                : attempt.routeSignature();
        operation.localCollisionSince = 0;
        operation.traversalWatchdog.reset();
        operation.progressExpected = true;
    }

    private Status completeStatus(ActiveOperation operation, String detail, double distanceRemaining) {
        if (operation.pendingCompletionDetail == null) {
            operation.pendingCompletionDetail = detail;
            operation.pendingCompletionDistance = distanceRemaining;
        }
        DoorPassageOutcome passage = maintainDoorPassage(operation, System.currentTimeMillis());
        if (passage != null && passage.hold()) {
            return status(operation, passage.blocked() ? State.BLOCKED : State.EXECUTING,
                    passage.detail(), distanceRemaining);
        }
        // Observing an outstanding open packet can enter CROSSING without a hold.
        // Do not retire the owner until that exact transaction is physically settled.
        if (doorPassage.snapshot().intent() != null) {
            return status(operation, State.EXECUTING,
                    "arrival retained; settling the exact door passage before completion", distanceRemaining);
        }
        if (operation.goal.arguments().containsKey("idleUndergroundMaxY")) releaseIdleMiningLimit();
        releaseAquaticTravel(operation);
        traversalRetryBudget.clear(operation.traversalBudgetKey);
        return status(operation, State.COMPLETE, operation.pendingCompletionDetail,
                operation.pendingCompletionDistance);
    }

    private void releaseIdleMiningLimit() {
        if (!idleMiningLimit.active()) return;
        var settings = BaritoneAPI.getSettings();
        settings.maxYLevelWhileMining.value = idleMiningLimit.restore(settings.maxYLevelWhileMining.value);
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private double distanceRemaining() {
        // Client ticks begin before Quick Play has created a world/player. Baritone's
        // estimatedTicksToGoal() dereferences playerFeet(), so telemetry must stay
        // unavailable rather than crashing during that short pre-join window.
        if (!playerContextReady()) {
            return Double.NaN;
        }
        try {
            return baritone.getPathingBehavior().estimatedTicksToGoal().orElse(Double.NaN);
        } catch (RuntimeException transientContextFailure) {
            return Double.NaN;
        }
    }

    /**
     * Stable geometric progress for the traversal watchdog. Baritone's
     * estimatedTicksToGoal changes when it swaps or recalculates a path, even
     * when the player's body has not moved. Treating that estimate as progress
     * lets an endlessly changing parkour route evade the watchdog. A Goal's
     * heuristic at the current feet position is independent of executor churn.
     * Mining intentionally relies on physical movement or verified work because
     * MineProcess may replace its goal with another ore while standing still.
     */
    private double traversalGoalDistance(ActiveOperation operation) {
        if (client.player != null && operation.functionalNativeReturn
                && operation.functionalMiningEntrance != null) {
            return Math.sqrt(operation.functionalMiningEntrance.getSquaredDistance(
                    baritone.getPlayerContext().playerFeet()));
        }
        if (client.player == null || isMiningKind(operation.goal.kind().toLowerCase(Locale.ROOT))) {
            return Double.NaN;
        }
        try {
            var goal = baritone.getPathingBehavior().getGoal();
            return goal == null
                    ? Double.NaN
                    : goal.heuristic(baritone.getPlayerContext().playerFeet());
        } catch (RuntimeException transientContextFailure) {
            return Double.NaN;
        }
    }

    private boolean playerContextReady() {
        return client.player != null && client.world != null
                && baritone.getPlayerContext().player() != null;
    }

    private Status pollAttack(ActiveOperation operation) {
        long now = System.currentTimeMillis();
        attackMissionObjective.observeDeath(client.world, now);
        Optional<BlockPos> satisfiedObjective = attackMissionObjective.completed(operation.goal, client.world);
        if (satisfiedObjective.isPresent()) {
            operation.defeatedAt = satisfiedObjective.orElseThrow();
            clearTacticalInputs(operation);
            releaseCombatHand(operation);
            suspendHuntPursuit(operation);
            operation.completedWorkUnits++;
            return completeStatus(operation, "defeated selected " + operation.targetDescription, 0);
        }
        String boundedViolation = boundedHuntViolation(operation, now);
        if (boundedViolation != null) {
            cancelProcesses();
            operation.blockedDetail = boundedViolation;
            operation.progressExpected = false;
            return status(operation, State.BLOCKED, boundedViolation, Double.NaN);
        }
        Entity target = operation.attackTarget;
        boolean visiblyDefeated = target instanceof LivingEntity living && living.getHealth() <= 0.0F;
        if (target != null && (visiblyDefeated || (!target.isAlive() && !target.isRemoved()))
                && (!operation.boundedHunt || !resourcePerception.legitimate()
                || resourcePerception.observesEntity(target))) {
            operation.defeatedAt = target.getBlockPos().toImmutable();
            clearTacticalInputs(operation);
            releaseCombatHand(operation);
            suspendHuntPursuit(operation);
            suspendProtectionCombatRoute(operation);
            operation.completedWorkUnits++;
            return completeStatus(operation,
                    "defeated " + operation.targetDescription, 0);
        }
        if (target != null && target.isRemoved()) {
            clearTacticalInputs(operation);
            releaseCombatHand(operation);
            suspendHuntPursuit(operation);
            suspendProtectionCombatRoute(operation);
            operation.attackTarget = null;
            target = null;
        }
        if (target != null && operation.boundedHunt
                && !huntTargetInsideSearchBoundary(operation, target)) {
            logger.debug("Hunt {} released {} after it moved outside the bounded search area",
                    operation.goal.missionId(), target.getUuidAsString());
            suspendHuntPursuit(operation);
            operation.attackTarget = null;
            operation.huntTargets.reset();
            operation.traversalWatchdog.reset();
            operation.lastEvent = null;
            lastPathEvent.set(null);
            target = null;
        }
        // Water fraction is an admission cost, not a live-target veto. Once Baritone has accepted
        // an animal and the body is making progress, recomputing a straight line from the new
        // shoreline position can suddenly label the same committed route as "open water". Keep
        // the pin until the real path fails, the traversal watchdog stalls, the animal leaves the
        // bounded hunt area, or a substantially cheaper loaded target wins normal hysteresis.
        if (target == null) {
            clearTacticalInputs(operation);
            target = operation.boundedHunt
                    ? bestHuntTarget(operation, now)
                    : nearestTarget(operation);
            if (target == null) {
                if (operation.boundedHunt) {
                    maintainHuntExploration(operation, now);
                    long secondsRemaining = Math.max(
                            0,
                            (operation.huntSearchDeadlineAt - System.currentTimeMillis() + 999) / 1_000);
                    return status(operation, State.EXECUTING,
                            (operation.waitingDetail == null
                                    ? "land-aware hunt scouting for " + operation.targetDescription
                                    : operation.waitingDetail)
                                    + " within " + operation.huntSearchRadius + " blocks of "
                                    + operation.huntSearchOriginX + "," + operation.huntSearchOriginZ
                                    + " (" + secondsRemaining + "s remaining)",
                            Double.NaN);
                }
                operation.progressExpected = false;
                return status(operation, State.EXECUTING,
                        "searching loaded chunks for " + operation.targetDescription,
                        Double.NaN);
            }
            pinAttackTarget(operation, target);
        }
        if (operation.boundedHunt) {
            if (resourcePerception.legitimate() && !resourcePerception.observesEntity(target)) {
                clearTacticalInputs(operation);
                Optional<Vec3d> remembered = resourcePerception.entityPosition(target);
                if (remembered.isEmpty() || (client.player != null && client.player.isOnGround()
                        && client.player.squaredDistanceTo(remembered.orElseThrow()) <= 2.25)) {
                    suspendHuntPursuit(operation);
                    resourcePerception.forgetEntity(target);
                    operation.attackTarget = null;
                    operation.huntTargets.reset();
                    maintainHuntExploration(operation, now);
                    return status(operation, State.EXECUTING,
                            "last observed animal location reached; scouting for another sighting", Double.NaN);
                }
                maintainHuntPursuit(operation, target, now);
                return status(operation, State.EXECUTING, "pursuing last observed animal location",
                        Math.sqrt(client.player.squaredDistanceTo(remembered.orElseThrow())));
            }
            target = reconsiderHuntTarget(operation, target, now);
            if (!operation.tacticalInputsActive) {
                maintainHuntPursuit(operation, target, now);
            }
            double pursuitDistance = client.player == null
                    ? Double.NaN
                    : Math.sqrt(client.player.squaredDistanceTo(target));
            String targetType = EntityType.getId(target.getType()).getPath();
            operation.waitingDetail = "pursuing loaded " + targetType
                    + (Double.isFinite(pursuitDistance)
                    ? " at " + Math.round(pursuitDistance) + " blocks"
                    : "")
                    + (client.player != null && client.player.canSee(target)
                    ? " with sightline"
                    : " without sightline");
        }
        if (target instanceof CreeperEntity creeper) {
            if (operation.protectionCombatContext
                    == ProtectionCombatContext.SEALED_LOW_HEALTH_RESOLUTION) {
                // Defense in depth: policy/session should never grant the ordinary-mob sealed
                // capability to a creeper. Fail stationary before the special creeper dispatcher,
                // which intentionally has its own fuse executor and does not run the shield gate.
                takeCloseCombatControl(operation);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                faceCombatTarget(creeper);
                stopTacticalShield(operation);
                operation.sealedRouteCustody = false;
                operation.progressExpected = false;
                return status(operation, State.BLOCKED,
                        "sealed low-health resolution is forbidden for creeper targets",
                        Double.NaN);
            }
            return pollCreeperAttack(operation, creeper);
        }
        return pollCloseCombat(operation, target);
    }

    /**
     * Pursues one committed passive animal through CustomGoalProcess snapshots.
     * Exploration uses the same process, so replacing the frontier goal is
     * atomic and there is no second Baritone process that can steal control.
     * Snapshot commitment also preserves an in-progress obstacle break instead
     * of restarting it every time the animal wanders by a fraction of a block.
     */
    private void maintainHuntPursuit(
            ActiveOperation operation,
            Entity target,
            long nowMillis) {
        var player = client.player;
        if (!operation.boundedHunt || player == null || target == null) return;
        boolean moving = operation.huntPursuitRouteMode
                && baritone.getPathingBehavior().isPathing();
        boolean calculating = operation.huntPursuitRouteMode && !moving
                && (baritone.getPathingBehavior().getInProgress().isPresent()
                || (baritone.getCustomGoalProcess().isActive()
                && isCalculationEvent(operation.lastEvent)));
        VisiblePlayerPursuitPolicy.RouteState routeState = moving
                ? VisiblePlayerPursuitPolicy.RouteState.MOVING
                : calculating
                ? VisiblePlayerPursuitPolicy.RouteState.CALCULATING
                : VisiblePlayerPursuitPolicy.RouteState.INACTIVE;
        boolean breaking = client.interactionManager != null
                && client.interactionManager.isBreakingBlock();
        Optional<Vec3d> knownPosition = resourcePerception.entityPosition(target);
        if (knownPosition.isEmpty()) return;
        Vec3d destination = knownPosition.orElseThrow();
        VisiblePlayerPursuitPolicy.Target snapshot = new VisiblePlayerPursuitPolicy.Target(
                destination.x, destination.y, destination.z);
        boolean sightline = player.canSee(target);
        VisiblePlayerPursuitPolicy.Decision decision = operation.huntPursuit.evaluate(
                snapshot,
                Math.sqrt(player.squaredDistanceTo(destination)),
                HUNT_CLOSE_RADIUS,
                targetGoalNearSatisfied(destination, HUNT_CLOSE_RADIUS),
                sightline,
                routeState,
                breaking,
                nowMillis);
        if (decision.action() == VisiblePlayerPursuitPolicy.Action.KEEP_COMMITTED) return;

        if (decision.action() == VisiblePlayerPursuitPolicy.Action.HOLD_POSITION) {
            if (operation.huntPursuitRouteMode) {
                try {
                    baritone.getPathingBehavior().cancelEverything();
                } catch (RuntimeException ignored) {
                }
            }
            operation.huntPursuitRouteMode = false;
            operation.huntPursuit.held();
            return;
        }

        try { baritone.getFollowProcess().cancel(); } catch (RuntimeException ignored) { }
        configureTraversalMode(
                operation,
                BlockPos.ofFloored(destination),
                nowMillis);
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(
                BlockPos.ofFloored(destination),
                sightline ? HUNT_CLOSE_RADIUS : 0));
        operation.huntPursuitRouteMode = true;
        operation.huntPursuit.committed(snapshot, nowMillis);
        operation.lastEvent = null;
        lastPathEvent.set(null);
        logger.debug("Committed animal-pursuit segment for {} {}: {}",
                EntityType.getId(target.getType()).getPath(),
                target.getUuidAsString(), decision.reason());
    }

    private void suspendHuntPursuit(ActiveOperation operation) {
        if (operation == null) return;
        if (operation.huntPursuitRouteMode) {
            try {
                baritone.getPathingBehavior().cancelEverything();
            } catch (RuntimeException ignored) {
            }
        }
        operation.huntPursuitRouteMode = false;
        operation.huntPursuit.reset();
    }

    private Entity reconsiderHuntTarget(
            ActiveOperation operation,
            Entity current,
            long nowMillis) {
        if (client.player == null || current == null
                || !operation.huntTargets.reconsiderationDue(nowMillis)) {
            return current;
        }
        Entity best = bestHuntTarget(operation, nowMillis);
        if (best == null) return current;

        HuntTargetPolicy.Candidate pinned = new HuntTargetPolicy.Candidate(
                current.getUuidAsString(), huntSelectionDistance(current));
        HuntTargetPolicy.Candidate challenger = new HuntTargetPolicy.Candidate(
                best.getUuidAsString(), huntSelectionDistance(best));
        // Once the body has closed to a visible animal, finish that engagement. Re-scoring
        // nearby flock members while the pinned animal is already inside the combat envelope
        // caused player-visible sheep-to-sheep chase churn in generated survival worlds.
        boolean engagementCommitted = client.player.canSee(current)
                && client.player.squaredDistanceTo(current)
                <= HUNT_COMBAT_HAND_RETENTION_DISTANCE * HUNT_COMBAT_HAND_RETENTION_DISTANCE;
        HuntTargetPolicy.Decision decision = operation.huntTargets.evaluate(
                pinned, challenger, engagementCommitted, nowMillis);
        if (!decision.switchTarget()) return current;

        logger.info("Hunt {} retargeting from {} at cost {} to {} at cost {}: {}",
                operation.goal.missionId(),
                EntityType.getId(current.getType()).getPath(),
                Math.round(pinned.cost()),
                EntityType.getId(best.getType()).getPath(),
                Math.round(challenger.cost()),
                decision.reason());
        clearTacticalInputs(operation);
        pinAttackTarget(operation, best);
        return best;
    }

    private Entity bestHuntTarget(ActiveOperation operation, long nowMillis) {
        if (client.player == null || client.world == null || operation.targetPredicate == null) return null;
        return client.world.getOtherEntities(
                        client.player,
                        client.player.getBoundingBox().expand(TARGET_SEARCH_RADIUS),
                         entity -> entity.isAlive()
                                && resourcePerception.entityPosition(entity).isPresent()
                                && operation.targetPredicate.test(entity)
                                && huntTargetInsideSearchBoundary(operation, entity)
                                && passiveHuntRouteAllowed(entity)
                                && !huntTargetRejections.isRejected(
                                entity.getUuidAsString(), nowMillis))
                .stream()
                // Eligibility and actual route failures handle oceans, protected targets and
                // unreachable animals. Among eligible loaded animals, behave like a player: take
                // the nearest one instead of letting a speculative terrain score send Entity past it.
                .min(Comparator.comparingDouble(this::huntSelectionDistance)
                        .thenComparingDouble(this::estimatedHuntCost))
                .orElse(null);
    }

    private double huntSelectionDistance(Entity target) {
        return client.player == null || target == null
                ? Double.POSITIVE_INFINITY
                : resourcePerception.entityPosition(target)
                        .map(position -> Math.sqrt(client.player.squaredDistanceTo(position)))
                        .orElse(Double.POSITIVE_INFINITY);
    }

    private boolean passiveHuntRouteAllowed(Entity target) {
        if (client.player == null || target == null) return false;
        Optional<Vec3d> known = resourcePerception.entityPosition(target);
        if (known.isEmpty()) return false;
        return HuntTargetPolicy.passiveRouteAllowed(
                routeWaterFraction(
                        (int) Math.floor(known.orElseThrow().x),
                        (int) Math.floor(known.orElseThrow().z)),
                client.player.isTouchingWater());
    }

    private boolean huntTargetInsideSearchBoundary(ActiveOperation operation, Entity target) {
        if (operation.boundedHunt && resourcePerception.legitimate()) {
            return resourcePerception.entityPosition(target).map(position -> HuntTargetPolicy.insideSearchBoundary(
                    position.x, position.z, operation.huntSearchOriginX, operation.huntSearchOriginZ,
                    operation.huntSearchRadius)).orElse(false);
        }
        return !operation.boundedHunt || HuntTargetPolicy.insideSearchBoundary(
                target.getX(),
                target.getZ(),
                operation.huntSearchOriginX,
                operation.huntSearchOriginZ,
                operation.huntSearchRadius);
    }

    /** Loaded-world route estimate used to avoid deceptively near animals across oceans/cliffs. */
    private double estimatedHuntCost(Entity target) {
        if (client.player == null || client.world == null) return Double.POSITIVE_INFINITY;
        Optional<Vec3d> known = resourcePerception.entityPosition(target);
        if (known.isEmpty()) return Double.POSITIVE_INFINITY;
        double dx = known.orElseThrow().x - client.player.getX();
        double dz = known.orElseThrow().z - client.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double vertical = Math.abs(known.orElseThrow().y - client.player.getY());
        int samples = Math.max(1, Math.min(
                MAX_HUNT_ROUTE_SAMPLES, (int) Math.ceil(horizontal / 4.0)));
        int waterSamples = 0;
        int steepRise = 0;
        int blockedSamples = 0;
        int unloadedSamples = 0;
        Integer previousSurfaceY = null;

        for (int index = 1; index <= samples; index++) {
            double fraction = index / (double) samples;
            int x = (int) Math.floor(client.player.getX() + dx * fraction);
            int z = (int) Math.floor(client.player.getZ() + dz * fraction);
            if (!client.world.isChunkLoaded(x >> 4, z >> 4)) {
                unloadedSamples++;
                continue;
            }

            int routeY = (int) Math.floor(client.player.getY()
                    + (target.getY() - client.player.getY()) * fraction);
            BlockPos routeFeet = new BlockPos(x, routeY, z);
            BlockState feetState = client.world.getBlockState(routeFeet);
            BlockState headState = client.world.getBlockState(routeFeet.up());
            if (!feetState.getCollisionShape(client.world, routeFeet).isEmpty()
                    || !headState.getCollisionShape(client.world, routeFeet.up()).isEmpty()) {
                blockedSamples++;
            }
            if (feetState.getFluidState().isIn(FluidTags.WATER)
                    || headState.getFluidState().isIn(FluidTags.WATER)) {
                waterSamples++;
            }
            if (feetState.getFluidState().isIn(FluidTags.LAVA)
                    || headState.getFluidState().isIn(FluidTags.LAVA)) {
                blockedSamples += 4;
            }

            int surfaceY = client.world.getTopY(
                    Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos surface = new BlockPos(x, surfaceY - 1, z);
            BlockState surfaceState = client.world.getBlockState(surface);
            if (surfaceState.getFluidState().isIn(FluidTags.WATER)
                    && !feetState.getFluidState().isIn(FluidTags.WATER)) {
                waterSamples++;
            }
            if (surfaceState.getFluidState().isIn(FluidTags.LAVA)) {
                blockedSamples += 4;
            }
            if (previousSurfaceY != null) {
                int step = Math.abs(surfaceY - previousSurfaceY);
                if (step > 2) steepRise += step - 2;
            }
            previousSurfaceY = surfaceY;
        }

        return HuntTargetPolicy.pursuitCost(new HuntTargetPolicy.RouteFeatures(
                horizontal,
                vertical,
                waterSamples,
                steepRise,
                blockedSamples,
                unloadedSamples,
                client.player.canSee(target),
                target.isTouchingWater() != client.player.isTouchingWater()));
    }

    private Status rejectHuntTarget(
            ActiveOperation operation,
            String reason,
            long nowMillis) {
        Entity rejected = operation.attackTarget;
        if (rejected == null) {
            return traversalFailureStatus(operation, reason, nowMillis);
        }
        String rejectedId = rejected.getUuidAsString();
        String rejectedType = EntityType.getId(rejected.getType()).getPath();
        huntTargetRejections.reject(rejectedId, nowMillis, reason);
        logger.info("Hunt {} temporarily rejected {} {} for {}s: {}",
                operation.goal.missionId(),
                rejectedType,
                rejectedId,
                HUNT_TARGET_REJECTION_MILLIS / 1_000,
                reason);

        clearTacticalInputs(operation);
        releaseCombatHand(operation);
        suspendHuntPursuit(operation);
        try { baritone.getFollowProcess().cancel(); } catch (RuntimeException ignored) { }
        operation.attackTarget = null;
        operation.huntSafetyUnavailableSince = 0L;
        operation.huntTargets.reset();
        operation.traversalWatchdog.reset();
        operation.lastEvent = null;
        lastPathEvent.set(null);

        Entity alternative = bestHuntTarget(operation, nowMillis);
        operation.progressExpected = true;
        if (alternative != null) {
            pinAttackTarget(operation, alternative);
            double distance = client.player == null
                    ? Double.NaN
                    : Math.sqrt(client.player.squaredDistanceTo(alternative));
            String alternativeType = EntityType.getId(alternative.getType()).getPath();
            operation.waitingDetail = "rejected " + rejectedType
                    + "; pursuing alternative loaded " + alternativeType;
            return status(operation, State.EXECUTING, operation.waitingDetail, distance);
        }

        startHuntExploration(operation);
        operation.waitingDetail = "rejected " + rejectedType
                + " for " + (HUNT_TARGET_REJECTION_MILLIS / 1_000)
                + "s; bounded exploration is searching for another "
                + operation.targetDescription;
        return status(operation, State.EXECUTING, operation.waitingDetail, Double.NaN);
    }

    private String boundedHuntViolation(ActiveOperation operation, long nowMillis) {
        if (!operation.boundedHunt) return null;
        if (nowMillis >= operation.huntSearchDeadlineAt) {
            return "bounded hunt search deadline expired for " + operation.targetDescription
                    + " within " + operation.huntSearchRadius + " blocks of "
                    + operation.huntSearchOriginX + "," + operation.huntSearchOriginZ
                    + "; search and pathing stopped";
        }
        if (client.player == null) return null;
        double dx = client.player.getX() - operation.huntSearchOriginX;
        double dz = client.player.getZ() - operation.huntSearchOriginZ;
        double distanceSquared = dx * dx + dz * dz;
        double radiusSquared = (double) operation.huntSearchRadius * operation.huntSearchRadius;
        if (distanceSquared > radiusSquared) {
            return "bounded hunt search left its " + operation.huntSearchRadius
                    + "-block radius around " + operation.huntSearchOriginX + ","
                    + operation.huntSearchOriginZ + " (current distance "
                    + Math.round(Math.sqrt(distanceSquared)) + " blocks); search and pathing stopped";
        }
        return null;
    }

    /**
     * Executes only the loaded close-range portion of a fight. Long pursuit and obstacle work stay
     * with Baritone; once close, this seam owns one complete movement/use/attack frame so route
     * input cannot race shield or cooldown spacing.
     */
    private Status pollCloseCombat(ActiveOperation operation, Entity target) {
        var player = client.player;
        if (player == null || client.interactionManager == null) {
            return status(operation, State.CALCULATING,
                    "waiting for player combat context", Double.NaN);
        }
        long now = System.currentTimeMillis();
        if (!arbiter.isValid(operation.controlLease, now)) {
            suspendProtectionCombatRoute(operation);
            clearTacticalInputs(operation);
            releaseCombatHand(operation);
            return status(operation, State.TRANSIENT_FAILURE,
                    "combat execution lost its body-control lease", Double.NaN);
        }

        boolean sealedResolution = operation.protectionCombatContext
                == ProtectionCombatContext.SEALED_LOW_HEALTH_RESOLUTION;
        if (sealedResolution) {
            boolean sealedRouteWasActive = operation.sealedRouteCustody;
            SealedShieldStartPolicy.State shieldStart =
                    sealedRouteWasActive
                            ? validateSealedRouteShield(operation, target)
                            : sealedShieldStart(operation, target, now);
            if (shieldStart == SealedShieldStartPolicy.State.FAILED) {
                if (sealedRouteWasActive) {
                    // Validation itself never cancels a healthy route. Once validation fails,
                    // however, fail closed by taking neutral close custody before reporting the
                    // typed edge; clearing only tactical keys would leave Follow moving unshielded.
                    takeCloseCombatControl(operation);
                    applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                    faceCombatTarget(target);
                    stopTacticalShield(operation);
                    operation.sealedRouteCustody = false;
                } else {
                    clearTacticalInputs(operation);
                    clearTacticalMovementInputs(operation);
                }
                operation.progressExpected = false;
                return status(operation, State.BLOCKED,
                        "sealed resolution shield start failed before its fixed deadline",
                        Double.NaN);
            }
            if (shieldStart == SealedShieldStartPolicy.State.STARTING) {
                // A low-health route or manual advance is forbidden until shield use is both
                // accepted and visibly active in the offhand. Close custody deliberately cancels
                // movement while the one fixed acknowledgement window is pending.
                takeCloseCombatControl(operation);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                faceCombatTarget(target);
                operation.progressExpected = false;
                return status(operation, State.CALCULATING,
                        "starting acknowledged offhand shield before sealed resolution movement",
                        Double.NaN);
            }
        }

        double distance = Math.sqrt(player.squaredDistanceTo(target));
        CombatEngagementPolicy.TargetKind kind = combatTargetKind(target);
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double horizontal = Math.hypot(dx, dz);
        double towardX = horizontal < 0.001 ? 0.0 : dx / horizontal;
        double towardZ = horizontal < 0.001 ? 0.0 : dz / horizontal;
        boolean bodyStable = player.isOnGround()
                && !player.isTouchingWater() && !player.isInLava();
        boolean safeForward = bodyStable && horizontal >= 0.001
                && safeCombatGroundStep(towardX, towardZ);
        boolean safeBackward = bodyStable && horizontal >= 0.001
                && safeCombatGroundStep(-towardX, -towardZ);
        boolean safeLeft = bodyStable && horizontal >= 0.001
                && safeCombatGroundStep(towardZ, -towardX);
        boolean safeRight = bodyStable && horizontal >= 0.001
                && safeCombatGroundStep(-towardZ, towardX);
        double maximumHealth = Math.max(1.0, player.getMaxHealth());
        boolean preferLeft = ((operation.completedWorkUnits
                ^ target.getUuid().getLeastSignificantBits()) & 1L) == 0L;
        boolean readyShield = shieldReady();
        boolean boundedLowHealthResolution =
                CombatEngagementPolicy.authorizeBoundedLowHealthResolution(
                        sealedResolution && operation.sealedShieldStartState
                                == SealedShieldStartPolicy.State.ACTIVE,
                        usableOffhandShield());
        CombatEngagementPolicy.Decision decision = CombatEngagementPolicy.decide(
                new CombatEngagementPolicy.Input(
                        kind,
                        distance,
                        Math.max(0.0, Math.min(1.0, player.getHealth() / maximumHealth)),
                        player.getAttackCooldownProgress(0.0F) >= 0.92F,
                        readyShield,
                        bodyStable,
                        player.canSee(target),
                        safeForward,
                        safeBackward,
                        safeLeft,
                        safeRight,
                        preferLeft,
                        false,
                        0.0F,
                        false,
                        false,
                        boundedLowHealthResolution), imminentProjectileThreat(operation, target));
        recordCombatEngagement(operation, target, decision, distance, readyShield,
                bodyStable, safeForward, towardX, towardZ, now);
        return executeCombatDecision(operation, target, kind, decision, distance);
    }

    private void recordCombatEngagement(
            ActiveOperation operation, Entity target, CombatEngagementPolicy.Decision decision,
            double distance, boolean readyShield, boolean bodyStable, boolean safeForward,
            double towardX, double towardZ, long now) {
        var player = client.player;
        JsonObject frame = new JsonObject();
        frame.addProperty("at", now);
        frame.addProperty("operationId", operation.goal.missionId());
        frame.addProperty("generation", operation.controlEpoch);
        frame.addProperty("targetUuid", target.getUuidAsString());
        frame.addProperty("targetX", target.getX());
        frame.addProperty("targetY", target.getY());
        frame.addProperty("targetZ", target.getZ());
        frame.addProperty("verticalDelta", target.getY() - player.getY());
        frame.addProperty("distance", distance);
        frame.addProperty("strikeReach", CombatEngagementPolicy.STRIKE_DISTANCE);
        frame.addProperty("attackCooldown", player.getAttackCooldownProgress(0.0F));
        frame.addProperty("shieldReady", readyShield);
        frame.addProperty("offhandShieldCoolingDown",
                player.getOffHandStack().isOf(Items.SHIELD)
                        && player.getItemCooldownManager().isCoolingDown(player.getOffHandStack()));
        frame.addProperty("bodyStable", bodyStable);
        frame.addProperty("lineOfSight", player.canSee(target));
        frame.addProperty("safeForward", safeForward);
        frame.addProperty("action", decision.action().name());
        frame.addProperty("detail", decision.detail());
        frame.addProperty("exactProtectionRoute", operation.protectionCombatRouteMode);
        if (decision.action() == CombatEngagementPolicy.Action.ROUTE && !safeForward
                && client.world != null) {
            BlockPos feet = BlockPos.ofFloored(player.getX() + towardX * 1.15,
                    player.getY() + 0.1, player.getZ() + towardZ * 1.15);
            JsonObject step = new JsonObject();
            step.add("feet", combatStepCell(feet));
            step.add("head", combatStepCell(feet.up()));
            step.add("floor", combatStepCell(feet.down()));
            frame.add("forwardStep", step);
        }
        operation.combatEngagement = frame;
    }

    private JsonObject combatStepCell(BlockPos position) {
        JsonObject cell = new JsonObject();
        cell.addProperty("x", position.getX());
        cell.addProperty("y", position.getY());
        cell.addProperty("z", position.getZ());
        boolean loaded = client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4);
        cell.addProperty("loaded", loaded);
        if (loaded) {
            BlockState state = client.world.getBlockState(position);
            cell.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
            cell.addProperty("fluid", Registries.FLUID.getId(state.getFluidState().getFluid()).toString());
            cell.addProperty("collisionEmpty", state.getCollisionShape(client.world, position).isEmpty());
        }
        return cell;
    }

    private SealedShieldStartPolicy.State sealedShieldStart(
            ActiveOperation operation,
            Entity target,
            long now) {
        if (operation.sealedShieldStartState == SealedShieldStartPolicy.State.FAILED) {
            return operation.sealedShieldStartState;
        }
        if (!usableOffhandShield()) {
            operation.sealedShieldStartState = SealedShieldStartPolicy.State.FAILED;
            return operation.sealedShieldStartState;
        }
        if (operation.sealedShieldStartDeadlineAt == 0L) {
            operation.sealedShieldStartDeadlineAt = now + TACTICAL_SHIELD_START_TIMEOUT_MILLIS;
        }
        holdShieldAgainst(operation, target);
        boolean activelyUsingOffhandShield = client.player != null
                && operation.tacticalShieldAccepted
                && client.player.isUsingItem()
                && client.player.getActiveHand() == Hand.OFF_HAND
                && client.player.getActiveItem().isOf(Items.SHIELD)
                && client.player.isBlocking();
        operation.sealedShieldStartState = SealedShieldStartPolicy.decide(
                operation.tacticalShieldAccepted,
                activelyUsingOffhandShield,
                now,
                operation.sealedShieldStartDeadlineAt);
        if (operation.sealedShieldStartState == SealedShieldStartPolicy.State.FAILED) {
            stopTacticalShield(operation);
        }
        return operation.sealedShieldStartState;
    }

    /** Route-mode validation never takes close custody or cancels the live Follow process. */
    private SealedShieldStartPolicy.State validateSealedRouteShield(
            ActiveOperation operation,
            Entity target) {
        if (operation.sealedShieldStartState == SealedShieldStartPolicy.State.FAILED) {
            return operation.sealedShieldStartState;
        }
        return holdSealedRouteShield(operation, target)
                ? SealedShieldStartPolicy.State.ACTIVE
                : SealedShieldStartPolicy.State.FAILED;
    }

    private Status executeCombatDecision(
            ActiveOperation operation,
            Entity target,
            CombatEngagementPolicy.TargetKind targetKind,
            CombatEngagementPolicy.Decision decision,
            double distance) {
        var player = client.player;
        if (!CombatEngagementPolicy.retainsRangedPressureShieldAttempt(decision)) {
            resetCooldownYieldShieldAttempt(operation);
        }
        return switch (decision.action()) {
            case ROUTE -> {
                Status hazardVeto = pollCombatRouteHazard(operation);
                if (hazardVeto != null) yield hazardVeto;
                boolean sealedRoute = operation.protectionCombatContext
                        == ProtectionCombatContext.SEALED_LOW_HEALTH_RESOLUTION;
                if (sealedRoute) {
                    // The exceptional low-health capability is kind-agnostic. Preserve the
                    // already-active offhand shield while Baritone owns movement, then prove that
                    // exact shield is still actively blocking after its DirectUse heartbeat.
                    if (SealedShieldStartPolicy.needsRouteHandoff(
                            true, operation.sealedRouteCustody)) {
                        restoreCombatRoute(operation, target, true);
                        operation.sealedRouteCustody = true;
                    }
                    if (!holdSealedRouteShield(operation, target)) {
                        takeCloseCombatControl(operation);
                        applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                        faceCombatTarget(target);
                        operation.progressExpected = false;
                        yield status(operation, State.BLOCKED,
                                "sealed route lost active offhand shield authority; movement held",
                                Math.max(0.0, distance - CombatEngagementPolicy.STRIKE_DISTANCE));
                    }
                } else if (targetKind == CombatEngagementPolicy.TargetKind.RANGED) {
                    // Route-shield custody owns only generation-fenced use. Any previous close
                    // movement is released and the exact target is re-pinned before the held-use
                    // heartbeat, so obstacle/path ownership stays wholly with Baritone.
                    boolean retainCloseCombatHand = operation.combatHandPrepared
                            && distance <= HUNT_COMBAT_HAND_RETENTION_DISTANCE;
                    restoreCombatRoute(operation, target, true, retainCloseCombatHand);
                    holdRangedRouteShield(operation, target);
                } else {
                    boolean retainPassiveCombatHand =
                            targetKind == CombatEngagementPolicy.TargetKind.PASSIVE
                            && operation.boundedHunt
                            && operation.combatHandPrepared;
                    restoreCombatRoute(
                            operation, target, false, retainPassiveCombatHand);
                }
                operation.progressExpected = distance > CombatEngagementPolicy.STRIKE_DISTANCE;
                yield null;
            }
            case STRIKE -> {
                operation.sealedRouteCustody = false;
                takeCloseCombatControl(operation);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.STRIKE);
                stopTacticalShield(operation);
                if (operation.protectionCombatContext
                        == ProtectionCombatContext.SEALED_LOW_HEALTH_RESOLUTION) {
                    // Attacking necessarily releases vanilla shield use. This is witnessed combat
                    // progress, not a rejected shield start, so the next tick may open one fresh
                    // fixed acknowledgement window instead of inheriting an expired initial one.
                    operation.sealedShieldStartState = SealedShieldStartPolicy.State.STARTING;
                    operation.sealedShieldStartDeadlineAt = 0L;
                }
                faceCombatTarget(target);
                selectBestHotbarMeleeWeapon();
                float cooldownProgress = player.getAttackCooldownProgress(0.0F);
                double strikeDistance = Math.sqrt(player.squaredDistanceTo(target));
                if (cooldownProgress >= 0.92F
                        && strikeDistance <= Math.sqrt(ATTACK_REACH_SQUARED)) {
                    MinecraftEntityAttackGateway.Attempt attack = entityAttacks.attack(
                            target, attackPurpose(operation, target), "baritone-close-combat");
                    if (!attack.issued()) {
                        long denialNow = System.currentTimeMillis();
                        if (attack.code()
                                == EntityDispositionPolicy.Code.LIVESTOCK_POLICY_UNAVAILABLE) {
                            if (operation.huntSafetyUnavailableSince == 0L) {
                                operation.huntSafetyUnavailableSince = denialNow;
                            }
                        } else {
                            operation.huntSafetyUnavailableSince = 0L;
                        }
                        long unavailableFor = operation.huntSafetyUnavailableSince == 0L
                                ? 0L
                                : denialNow - operation.huntSafetyUnavailableSince;
                        EntityDispositionPolicy.HuntDenialAction denialAction =
                                EntityDispositionPolicy.huntDenialAction(
                                        operation.boundedHunt,
                                        attack.code(),
                                        unavailableFor);
                        if (denialAction
                                == EntityDispositionPolicy.HuntDenialAction.WAIT_FOR_OBSERVATION) {
                            operation.progressExpected = false;
                            long remaining = Math.max(
                                    0L,
                                    EntityDispositionPolicy.HUNT_OBSERVATION_RETRY_MILLIS
                                            - unavailableFor);
                            operation.waitingDetail =
                                    "holding attack for a fresh livestock safety observation ("
                                            + Math.max(1L, (remaining + 999L) / 1_000L)
                                            + "s grace remaining)";
                            yield status(operation, State.EXECUTING,
                                    operation.waitingDetail, strikeDistance);
                        }
                        if (denialAction
                                == EntityDispositionPolicy.HuntDenialAction.RETARGET) {
                            yield rejectHuntTarget(
                                    operation,
                                    "final livestock safety rejected the target: "
                                            + attack.detail(),
                                    denialNow);
                        }
                        operation.progressExpected = false;
                        yield status(operation, State.BLOCKED,
                                "attack denied by safety policy: " + attack.detail(),
                                strikeDistance);
                    }
                    operation.huntSafetyUnavailableSince = 0L;
                    recordCloseCombatAttack(
                            operation, target, cooldownProgress, strikeDistance);
                    player.swingHand(Hand.MAIN_HAND);
                    operation.completedWorkUnits++;
                }
                operation.progressExpected = false;
                yield status(operation, State.EXECUTING, decision.detail(), 0.0);
            }
            case ADVANCE -> {
                operation.sealedRouteCustody = false;
                holdCloseCombatPosition(operation, target);
                double dx = target.getX() - player.getX();
                double dz = target.getZ() - player.getZ();
                double length = Math.hypot(dx, dz);
                boolean sprintCorridor = length > 0.001
                        && player.getHungerManager().getFoodLevel() > 6
                        && safeCombatGroundStep(dx / length * 1.5, dz / length * 1.5)
                        && safeCombatGroundStep(dx / length * 2.0, dz / length * 2.0);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.ADVANCE, sprintCorridor);
                operation.progressExpected = true;
                yield status(operation, State.EXECUTING, decision.detail(),
                        Math.max(0.0, distance - CombatEngagementPolicy.STRIKE_DISTANCE));
            }
            case SHIELD_ADVANCE -> {
                operation.sealedRouteCustody = false;
                boolean shieldHeld = holdShieldAgainst(operation, target);
                if (shieldHeld) {
                    applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.SHIELD_ADVANCE);
                    operation.progressExpected = true;
                } else {
                    holdCloseCombatPosition(operation, target);
                    applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                    operation.progressExpected = false;
                }
                yield status(operation, State.EXECUTING,
                        shieldHeld ? decision.detail()
                                : "shield unavailable; holding instead of charging ranged target",
                        Math.max(0.0, distance - CombatEngagementPolicy.STRIKE_DISTANCE));
            }
            case BLOCK -> {
                operation.sealedRouteCustody = false;
                Entity rangedPressure = liveVisibleRangedPressure(operation);
                boolean rangedPressureCooldownBlock = operation.protectionCombatContext
                        == ProtectionCombatContext.STANDARD
                        && CombatEngagementPolicy.shouldShieldMeleeCooldownBlock(
                        decision,
                        distance,
                        player.getAttackCooldownProgress(0.0F) >= 0.92F,
                        rangedPressure != null,
                        usableOffhandShield());
                Entity shieldFacing = rangedPressureCooldownBlock ? rangedPressure : target;
                boolean shieldHeld = rangedPressureCooldownBlock
                        ? holdCooldownYieldShield(operation, shieldFacing)
                        != SealedShieldStartPolicy.State.FAILED
                        : holdShieldAgainst(operation, shieldFacing);
                if (!shieldHeld) holdCloseCombatPosition(operation, target);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                operation.progressExpected = false;
                yield status(operation, State.EXECUTING,
                        shieldHeld && rangedPressureCooldownBlock
                                ? "hold shield facing visible ranged pressure through the exact melee cooldown window"
                                : shieldHeld ? decision.detail()
                                : "shield unavailable; holding safe combat position",
                        Math.max(0.0, distance - CombatEngagementPolicy.STRIKE_DISTANCE));
            }
            case BACKSTEP -> {
                operation.sealedRouteCustody = false;
                prepareCombatSpacing(operation, target);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BACKSTEP);
                operation.progressExpected = true;
                yield status(operation, State.EXECUTING, decision.detail(), 0.0);
            }
            case STRAFE_LEFT -> {
                operation.sealedRouteCustody = false;
                prepareCombatSpacing(operation, target);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.STRAFE_LEFT);
                operation.progressExpected = true;
                yield status(operation, State.EXECUTING, decision.detail(), 0.0);
            }
            case STRAFE_RIGHT -> {
                operation.sealedRouteCustody = false;
                prepareCombatSpacing(operation, target);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.STRAFE_RIGHT);
                operation.progressExpected = true;
                yield status(operation, State.EXECUTING, decision.detail(), 0.0);
            }
            case YIELD -> {
                boolean sealedYield = operation.protectionCombatContext
                        == ProtectionCombatContext.SEALED_LOW_HEALTH_RESOLUTION;
                if (sealedYield) {
                    operation.sealedRouteCustody = false;
                    if (!holdSealedStationaryShield(operation, target)) {
                        takeCloseCombatControl(operation);
                        applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                        faceCombatTarget(target);
                        operation.progressExpected = false;
                        yield status(operation, State.BLOCKED,
                                "sealed hold lost active offhand shield authority; movement held",
                                Math.max(0.0, distance - CombatEngagementPolicy.STRIKE_DISTANCE));
                    }
                    applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                    operation.progressExpected = false;
                    yield status(operation, State.EXECUTING,
                            "sealed resolution holds active shield through cooldown or unstable footing",
                            Math.max(0.0, distance - CombatEngagementPolicy.STRIKE_DISTANCE));
                }
                Entity rangedPressure = liveVisibleRangedPressure(operation);
                boolean shieldCooldownYield = CombatEngagementPolicy.shouldShieldCooldownYield(
                        decision.action(),
                        distance,
                        player.getAttackCooldownProgress(0.0F) >= 0.92F,
                        rangedPressure != null,
                        usableOffhandShield());
                if (shieldCooldownYield) {
                    operation.sealedRouteCustody = false;
                    SealedShieldStartPolicy.State shieldState =
                            holdCooldownYieldShield(operation, rangedPressure);
                    applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                    operation.progressExpected = false;
                    yield status(operation, State.EXECUTING,
                            switch (shieldState) {
                                case ACTIVE -> "hold shield facing visible ranged pressure through the melee cooldown window";
                                case STARTING -> "starting shield facing visible ranged pressure; holding stationary";
                                case FAILED -> "visible ranged pressure shield start exhausted its fixed window; holding stationary";
                            },
                            Math.max(0.0, distance - CombatEngagementPolicy.STRIKE_DISTANCE));
                }
                if (player.isTouchingWater() && !player.isInLava()) {
                    // Direct ground spacing deliberately yields in water. Restore the exact route
                    // so the aquatic controller later in this same poll owns the complete 3-D
                    // movement frame instead of leaving the player motionless at the surface.
                    restoreCombatRoute(operation, target);
                    operation.progressExpected = distance > CombatEngagementPolicy.STRIKE_DISTANCE;
                    yield null;
                }
                holdCloseCombatPosition(operation, target);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                operation.progressExpected = false;
                yield status(operation, State.EXECUTING, decision.detail(),
                        Math.max(0.0, distance - CombatEngagementPolicy.STRIKE_DISTANCE));
            }
            // Creepers retain their fuse-aware executor below. These actions cannot be produced by
            // a non-creeper input, but remain total if target classification changes mid-tick.
            case STRIKE_AND_RETREAT, RETREAT -> {
                operation.sealedRouteCustody = false;
                holdCloseCombatPosition(operation, target);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                operation.progressExpected = false;
                yield status(operation, State.EXECUTING,
                        "yielding unexpected explosive combat transition: " + decision.detail(),
                        Double.NaN);
            }
        };
    }

    /**
     * Rejects a close-combat route before the player's swept footprint crosses a loaded hazard.
     * Current plus two future Baritone movements are checked every ROUTE tick. A future hazard is
     * canceled at the preceding safe-to-cancel edge; a hazard already on the current movement is
     * canceled immediately. Both use the normal bounded failed-route budget, so protection cannot
     * recreate the same unsafe Follow route forever.
     */
    private Status pollCombatRouteHazard(ActiveOperation operation) {
        if (client.world == null || client.player == null
                || !baritone.getPathingBehavior().isPathing()) return null;
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return null;
        int position = executor.getPosition();
        var movements = executor.getPath().movements();
        if (position < 0 || position >= movements.size()) return null;

        int last = Math.min(
                movements.size() - 1,
                position + CombatRouteHazardPolicy.MAX_EDGE_LOOKAHEAD);
        ArrayList<CombatRouteHazardPolicy.Edge> edges = new ArrayList<>(last - position + 1);
        for (int index = position; index <= last; index++) {
            var movement = movements.get(index);
            var source = movement.getSrc();
            var destination = movement.getDest();
            edges.add(new CombatRouteHazardPolicy.Edge(
                    routeSignature(movement.getClass().getSimpleName(), index,
                            source.x, source.y, source.z,
                            destination.x, destination.y, destination.z),
                    movement.getClass().getSimpleName(),
                    new CombatRouteHazardPolicy.Cell(source.x, source.y, source.z),
                    new CombatRouteHazardPolicy.Cell(
                            destination.x, destination.y, destination.z),
                    movement.safeToCancel()));
        }

        double maximumHealth = Math.max(1.0, client.player.getMaxHealth());
        double healthFraction = Math.max(0.0, Math.min(
                1.0, client.player.getHealth() / maximumHealth));
        CombatRouteHazardPolicy.Decision decision = CombatRouteHazardPolicy.inspect(
                edges,
                healthFraction,
                this::inspectCombatRouteCell,
                this::inspectCombatRouteLanding);
        if (!decision.veto()) return null;

        CombatRouteHazardPolicy.Finding finding = decision.finding();
        String detail = finding.observation().detail().isBlank()
                ? finding.observation().disposition().name().toLowerCase(Locale.ROOT)
                : finding.observation().detail();
        String timing = decision.action() == CombatRouteHazardPolicy.Action.VETO_CURRENT
                ? "current combat route"
                : "combat route lookahead +" + decision.edgeOffset();
        String location = finding.cell().x() + " " + finding.cell().y()
                + " " + finding.cell().z();
        String reason = switch (finding.kind()) {
            case SWEPT_HAZARD -> timing
                    + " swept the player body within the hazard margin of "
                    + detail + " at " + location;
            case UNVERIFIED_LANDING -> timing
                    + " has no verified supported landing: " + detail + " at " + location;
            case EXCESSIVE_COMBAT_DESCENT -> timing + " rejected " + detail
                    + " at " + location;
            case LOW_HEALTH_DESCENT -> timing + " rejected " + detail + " at " + location
                    + " while health was " + Math.round(healthFraction * 100.0) + "%";
        };
        logger.warn("{}; vetoing {} before contact", reason, decision.edge().routeSignature());
        if (operation.goal.kind().equalsIgnoreCase("hunt") && operation.attackTarget != null) {
            return rejectHuntTarget(operation, reason, System.currentTimeMillis());
        }
        return traversalFailureStatus(
                operation,
                reason,
                decision.edge().routeSignature(),
                System.currentTimeMillis());
    }

    private CombatRouteHazardPolicy.Observation inspectCombatRouteCell(
            CombatRouteHazardPolicy.Cell cell) {
        if (client.world == null) {
            return CombatRouteHazardPolicy.Observation.unknown("world unavailable");
        }
        BlockPos position = new BlockPos(cell.x(), cell.y(), cell.z());
        if (!client.world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4)) {
            return CombatRouteHazardPolicy.Observation.unknown("unloaded chunk");
        }
        BlockState state = client.world.getBlockState(position);
        String hazard = combatRouteHazardId(state);
        return hazard == null
                ? CombatRouteHazardPolicy.Observation.safe()
                : CombatRouteHazardPolicy.Observation.hazard(hazard);
    }

    private CombatRouteHazardPolicy.Landing inspectCombatRouteLanding(
            CombatRouteHazardPolicy.Cell destinationFeet) {
        BlockPos feet = new BlockPos(
                destinationFeet.x(), destinationFeet.y(), destinationFeet.z());
        BlockPos head = feet.up();
        BlockPos floor = feet.down();
        if (client.world == null) {
            return CombatRouteHazardPolicy.Landing.unknown(
                    destinationFeet, "world unavailable");
        }
        if (!routeBlockLoaded(feet)) {
            return CombatRouteHazardPolicy.Landing.unknown(
                    destinationFeet, "destination feet are unloaded");
        }
        if (!routeBlockLoaded(head)) {
            return CombatRouteHazardPolicy.Landing.unknown(
                    new CombatRouteHazardPolicy.Cell(
                            head.getX(), head.getY(), head.getZ()),
                    "destination head is unloaded");
        }
        if (!routeBlockLoaded(floor)) {
            return CombatRouteHazardPolicy.Landing.unknown(
                    new CombatRouteHazardPolicy.Cell(
                            floor.getX(), floor.getY(), floor.getZ()),
                    "destination floor is unloaded");
        }
        boolean feetObstructed = !client.world.getBlockState(feet)
                .getCollisionShape(client.world, feet).isEmpty();
        boolean headObstructed = !client.world.getBlockState(head)
                .getCollisionShape(client.world, head).isEmpty();
        boolean floorSupported = !client.world.getBlockState(floor)
                .getCollisionShape(client.world, floor).isEmpty();
        return CombatRouteHazardPolicy.classifyLoadedLanding(
                destinationFeet,
                new CombatRouteHazardPolicy.Cell(
                        head.getX(), head.getY(), head.getZ()),
                new CombatRouteHazardPolicy.Cell(
                        floor.getX(), floor.getY(), floor.getZ()),
                feetObstructed,
                headObstructed,
                floorSupported);
    }

    private static String combatRouteHazardId(BlockState state) {
        if (state.getFluidState().isIn(FluidTags.LAVA) || state.isOf(Blocks.LAVA)) {
            return "minecraft:lava";
        }
        if (state.isOf(Blocks.FIRE)) return "minecraft:fire";
        if (state.isOf(Blocks.SOUL_FIRE)) return "minecraft:soul_fire";
        if (state.isOf(Blocks.MAGMA_BLOCK)) return "minecraft:magma_block";
        if (state.isOf(Blocks.CAMPFIRE)) return "minecraft:campfire";
        if (state.isOf(Blocks.SOUL_CAMPFIRE)) return "minecraft:soul_campfire";
        if (state.isOf(Blocks.CACTUS)) return "minecraft:cactus";
        if (state.isOf(Blocks.POWDER_SNOW)) return "minecraft:powder_snow";
        return null;
    }

    private static String routeSignature(
            String movementClass,
            int position,
            int sourceX,
            int sourceY,
            int sourceZ,
            int destinationX,
            int destinationY,
            int destinationZ) {
        return movementClass + ':' + position + ':'
                + sourceX + ',' + sourceY + ',' + sourceZ
                + "->" + destinationX + ',' + destinationY + ',' + destinationZ;
    }

    private CombatEngagementPolicy.TargetKind combatTargetKind(Entity target) {
        if (target instanceof CreeperEntity) return CombatEngagementPolicy.TargetKind.CREEPER;
        if (target instanceof net.minecraft.entity.player.PlayerEntity) {
            return CombatEngagementPolicy.TargetKind.PLAYER;
        }
        String type = EntityType.getId(target.getType()).getPath();
        if (RANGED_COMBAT_ENTITY_TYPES.contains(type)) {
            return CombatEngagementPolicy.TargetKind.RANGED;
        }
        if (target instanceof HostileEntity) return CombatEngagementPolicy.TargetKind.MELEE;
        return CombatEngagementPolicy.TargetKind.PASSIVE;
    }

    private boolean usesProtectionCombatRoute(ActiveOperation operation, Entity target) {
        return operation != null && target != null && !(target instanceof CreeperEntity)
                && operation.goal.missionId().equals(protectionOperationId(target.getUuidAsString()));
    }

    /**
     * Follow's integer GoalNear radius can report arrival outside the real strike radius. An
     * exact native feet-cell goal cannot: any two physical points in that cell are <sqrt(3)
     * apart. Close combat still interrupts the native route at its unchanged real reach/LOS
     * predicate. The existing commitment policy protects block work and refreshes ended routes.
     */
    private void maintainProtectionCombatRoute(
            ActiveOperation operation, Entity target, long nowMillis) {
        if (active != operation) return;
        var player = client.player;
        if (player == null || !usesProtectionCombatRoute(operation, target)
                || operation.attackTarget != target || !target.isAlive() || target.isRemoved()
                || !arbiter.isValid(operation.controlLease, nowMillis)) {
            suspendProtectionCombatRoute(operation);
            return;
        }
        boolean moving = operation.protectionCombatRouteMode
                && baritone.getPathingBehavior().isPathing();
        boolean calculating = operation.protectionCombatRouteMode && !moving
                && (baritone.getPathingBehavior().getInProgress().isPresent()
                || (baritone.getCustomGoalProcess().isActive()
                && isCalculationEvent(operation.lastEvent)));
        VisiblePlayerPursuitPolicy.RouteState routeState = moving
                ? VisiblePlayerPursuitPolicy.RouteState.MOVING
                : calculating ? VisiblePlayerPursuitPolicy.RouteState.CALCULATING
                : VisiblePlayerPursuitPolicy.RouteState.INACTIVE;
        VisiblePlayerPursuitPolicy.Target snapshot = new VisiblePlayerPursuitPolicy.Target(
                target.getX(), target.getY(), target.getZ());
        VisiblePlayerPursuitPolicy.Decision decision = operation.protectionCombatPursuit.evaluate(
                snapshot, Math.sqrt(player.squaredDistanceTo(target)),
                CombatEngagementPolicy.STRIKE_DISTANCE, false, false, routeState,
                client.interactionManager != null && client.interactionManager.isBreakingBlock(),
                nowMillis);
        if (decision.action() == VisiblePlayerPursuitPolicy.Action.KEEP_COMMITTED) return;
        if (decision.action() == VisiblePlayerPursuitPolicy.Action.HOLD_POSITION) {
            suspendProtectionCombatRoute(operation);
            return;
        }
        try { baritone.getFollowProcess().cancel(); } catch (RuntimeException ignored) { }
        BlockPos destination = target.getBlockPos().toImmutable();
        configureTraversalMode(operation, destination, nowMillis);
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(destination));
        operation.protectionCombatRouteMode = true;
        operation.protectionCombatPursuit.committed(snapshot, nowMillis);
        operation.lastEvent = null;
        lastPathEvent.set(null);
    }

    private void suspendProtectionCombatRoute(ActiveOperation operation) {
        if (operation == null) return;
        // A late callback may retire its session, but may not cancel a replacement's native goal.
        if (operation.protectionCombatRouteMode && active == operation) {
            try { baritone.getCustomGoalProcess().setGoal(null); } catch (RuntimeException ignored) { }
            try { baritone.getPathingBehavior().cancelEverything(); } catch (RuntimeException ignored) { }
        }
        operation.protectionCombatRouteMode = false;
        operation.protectionCombatPursuit.reset();
    }

    /** Releases close-body ownership and re-pins the exact target only when routing is needed. */
    private void restoreCombatRoute(ActiveOperation operation, Entity target) {
        restoreCombatRoute(operation, target, false);
    }

    private void restoreCombatRoute(
            ActiveOperation operation,
            Entity target,
            boolean preserveShieldUse) {
        restoreCombatRoute(operation, target, preserveShieldUse, false);
    }

    private void restoreCombatRoute(
            ActiveOperation operation,
            Entity target,
            boolean preserveShieldUse,
            boolean preserveCombatHand) {
        // Combat-hand preparation alone does not cancel or replace route movement. Only a
        // complete tactical movement frame requires re-pinning the route afterward. A ranged
        // route shield is deliberately not movement ownership and may stay held across handoff.
        boolean hadCloseControl = operation.tacticalInputsActive;
        if (operation.tacticalInputsActive) clearTacticalMovementInputs(operation);
        if (!preserveShieldUse) stopTacticalShield(operation);
        if (preserveCombatHand && operation.combatHandPrepared) {
            setActuatorScope(BaritoneActuatorScopePolicy.Owner.COMBAT_HAND);
        } else {
            releaseCombatHand(operation);
            setActuatorScope(BaritoneActuatorScopePolicy.Owner.ROUTE);
        }
        if (usesProtectionCombatRoute(operation, target)) {
            // Also refresh an ended snapshot while ROUTE continuously owns movement; hand-only
            // shield use does not set hadCloseControl and must not strand the native route.
            maintainProtectionCombatRoute(operation, target, System.currentTimeMillis());
            return;
        }
        if (!hadCloseControl || target == null || !target.isAlive()) return;

        if (operation.boundedHunt) {
            operation.huntPursuitRouteMode = false;
            operation.huntPursuit.reset();
            maintainHuntPursuit(operation, target, System.currentTimeMillis());
        } else if (operation.playerTarget) {
            operation.visiblePlayerRouteMode = false;
            operation.visiblePlayerPursuit.reset();
            maintainVisiblePlayerPursuit(operation, target, System.currentTimeMillis());
        } else {
            baritone.getFollowProcess().follow(
                    entity -> entity.getUuid().equals(target.getUuid()));
        }
    }

    /** Cancels route keys/processes once and establishes the close-combat actuator scope. */
    private void takeCloseCombatControl(ActiveOperation operation) {
        if (!operation.tacticalInputsActive) {
            suspendProtectionCombatRoute(operation);
            try { baritone.getFollowProcess().cancel(); } catch (RuntimeException ignored) { }
            try { baritone.getPathingBehavior().cancelEverything(); } catch (RuntimeException ignored) { }
            if (operation.playerTarget) {
                operation.visiblePlayerRouteMode = false;
                operation.visiblePlayerPursuit.reset();
            }
            if (operation.boundedHunt) {
                operation.huntPursuitRouteMode = false;
                operation.huntPursuit.reset();
            }
        }
        prepareCombatHand(operation);
        baritone.getInputOverrideHandler().clearAllKeys();
        operation.tacticalInputsActive = true;
        operation.creeperFollowing = false;
    }

    /** Applies one complete movement frame without touching generation-fenced shield use. */
    private void applyCloseCombatMovementFrame(CombatEngagementPolicy.Action action) {
        applyCloseCombatMovementFrame(action, false);
    }

    private void applyCloseCombatMovementFrame(
            CombatEngagementPolicy.Action action, boolean sprintCorridorVerified) {
        CloseCombatMovementPolicy.Frame frame = CloseCombatMovementPolicy.frameFor(
                action, sprintCorridorVerified);
        var input = baritone.getInputOverrideHandler();
        input.setInputForceState(Input.MOVE_FORWARD, frame.forward());
        input.setInputForceState(Input.MOVE_BACK, frame.backward());
        input.setInputForceState(Input.MOVE_LEFT, frame.left());
        input.setInputForceState(Input.MOVE_RIGHT, frame.right());
        input.setInputForceState(Input.JUMP, frame.jump());
        input.setInputForceState(Input.SPRINT, frame.sprint());
    }

    private void holdCloseCombatPosition(ActiveOperation operation, Entity target) {
        stopTacticalShield(operation);
        takeCloseCombatControl(operation);
        faceCombatTarget(target);
    }

    private void prepareCombatSpacing(ActiveOperation operation, Entity target) {
        if (!holdShieldAgainst(operation, target)) {
            takeCloseCombatControl(operation);
            faceCombatTarget(target);
        }
    }

    private void faceCombatTarget(Entity target) {
        var player = client.player;
        if (player == null || target == null) return;
        Rotation rotation = combatRotation(target);
        float yaw = rotation.getYaw();
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setPitch(rotation.getPitch());
    }

    private Rotation combatRotation(Entity target) {
        var player = client.player;
        if (player == null || target == null) return new Rotation(0.0F, 0.0F);
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double horizontal = Math.hypot(dx, dz);
        double dy = target.getEyeY() - player.getEyeY();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(
                Math.atan2(dy, Math.max(0.001, horizontal)));
        return new Rotation(yaw, pitch);
    }

    private void recordCloseCombatAttack(
            ActiveOperation operation,
            Entity target,
            double cooldownProgress,
            double distance) {
        var player = client.player;
        if (player == null) return;
        // This is intentionally invoked only on the line after attackEntity. Defeat observation,
        // generic work counters, shield frames, and creeper interception are not strike authority.
        combatAttacks.append(new CombatAttackJournal.Draft(
                System.currentTimeMillis(),
                player.age,
                "close_combat",
                operation.goal.missionId(),
                operation.generation,
                operation.startedAt,
                operation.controlEpoch,
                target.getUuidAsString(),
                EntityType.getId(target.getType()).toString(),
                Registries.ITEM.getId(player.getMainHandStack().getItem()).toString(),
                cooldownProgress,
                distance));
    }

    /** Selects a carried hotbar weapon only after autoTool has yielded the combat hand. */
    private void selectBestHotbarMeleeWeapon() {
        var player = client.player;
        if (player == null) return;
        var inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        int bestSlot = selected;
        int bestScore = meleeWeaponScore(inventory.getStack(selected).getItem());
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty() || remainingDurability(stack) <= 1) continue;
            int score = meleeWeaponScore(stack.getItem());
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestScore > 0) selectHotbarSlotNow(bestSlot);
    }

    private static int meleeWeaponScore(Item item) {
        if (item == Items.NETHERITE_SWORD) return 90;
        if (item == Items.DIAMOND_SWORD) return 80;
        if (item == Items.IRON_SWORD) return 70;
        if (item == Items.STONE_SWORD) return 60;
        if (item == Items.GOLDEN_SWORD) return 55;
        if (item == Items.WOODEN_SWORD) return 50;
        if (item == Items.NETHERITE_AXE) return 49;
        if (item == Items.DIAMOND_AXE) return 48;
        if (item == Items.IRON_AXE) return 47;
        if (item == Items.STONE_AXE) return 46;
        if (item == Items.GOLDEN_AXE) return 45;
        if (item == Items.WOODEN_AXE) return 44;
        return 0;
    }

    private Status pollCreeperAttack(ActiveOperation operation, CreeperEntity creeper) {
        var player = client.player;
        if (player == null || client.interactionManager == null) {
            return status(operation, State.CALCULATING, "waiting for player combat context", Double.NaN);
        }
        if (!arbiter.isValid(operation.controlLease, System.currentTimeMillis())) {
            clearTacticalInputs(operation);
            return status(operation, State.TRANSIENT_FAILURE,
                    "creeper tactic lost its body-control lease", Double.NaN);
        }

        PropertyCreeperSafetyPolicy.Decision property =
                entityAttacks.creeperDecision(creeper);
        if (property.denied()) {
            operation.creeperRetreating = true;
            if (property.holdPosition()) {
                takeCloseCombatControl(operation);
                applyCloseCombatMovementFrame(CombatEngagementPolicy.Action.BLOCK);
                stopTacticalShield(operation);
                faceCombatTarget(creeper);
                operation.progressExpected = false;
                return status(operation, State.BLOCKED,
                        "creeper approach denied while sheltered: " + property.detail(),
                        property.propertyDistance());
            }
            boolean moving = retreatFromCreeper(
                    operation, creeper, property.escape());
            if (!moving && shieldReady()) holdShieldAgainst(operation, creeper);
            operation.progressExpected = moving;
            return status(operation, State.EXECUTING,
                    "property-aware creeper withdrawal: " + property.detail(),
                    Math.max(0.0,
                            property.engagementClearance() - property.propertyDistance()));
        }

        double distance = Math.sqrt(player.squaredDistanceTo(creeper));
        boolean cooldownReady = player.getAttackCooldownProgress(0.0F) >= 0.92F;
        float fuseProgress = creeper.getLerpedFuseTime(1.0F);
        boolean fuseActive = creeper.getFuseSpeed() > 0 || fuseProgress > 0.01F;
        CreeperTactics.Decision tactic = CreeperTactics.decide(
                distance,
                fuseActive,
                fuseProgress,
                creeper.isCharged(),
                cooldownReady,
                operation.creeperRetreating,
                shieldReady());

        return switch (tactic.action()) {
            case APPROACH -> {
                operation.creeperRetreating = false;
                Status hazardVeto = pollCombatRouteHazard(operation);
                if (hazardVeto != null) yield hazardVeto;
                ensureCreeperFollow(operation, creeper);
                operation.progressExpected = distance > CreeperTactics.ATTACK_DISTANCE;
                yield status(operation, State.EXECUTING, tactic.detail(),
                        Math.max(0, distance - CreeperTactics.ATTACK_DISTANCE));
            }
            case STRIKE_AND_RETREAT -> {
                takeCloseCombatControl(operation);
                stopTacticalShield(operation);
                faceCombatTarget(creeper);
                selectBestHotbarMeleeWeapon();
                MinecraftEntityAttackGateway.Attempt attack = entityAttacks.attack(
                        creeper, attackPurpose(operation, creeper), "baritone-creeper-combat");
                if (!attack.issued()) {
                    operation.progressExpected = false;
                    yield status(operation, State.BLOCKED,
                            "creeper attack denied by safety policy: " + attack.detail(),
                            distance);
                }
                player.swingHand(Hand.MAIN_HAND);
                operation.completedWorkUnits++;
                operation.creeperRetreating = true;
                if (!retreatFromCreeper(operation, creeper) && shieldReady()) {
                    holdShieldAgainst(operation, creeper);
                }
                yield status(operation, State.EXECUTING, tactic.detail(),
                        Math.max(0, tactic.safeDistance() - distance));
            }
            case RETREAT -> {
                operation.creeperRetreating = true;
                if (!retreatFromCreeper(operation, creeper) && shieldReady()) {
                    holdShieldAgainst(operation, creeper);
                }
                operation.progressExpected = distance < tactic.safeDistance();
                yield status(operation, State.EXECUTING, tactic.detail(),
                        Math.max(0, tactic.safeDistance() - distance));
            }
            case SHIELD -> {
                operation.creeperRetreating = true;
                if (!holdShieldAgainst(operation, creeper)) {
                    retreatFromCreeper(operation, creeper);
                }
                operation.progressExpected = false;
                yield status(operation, State.EXECUTING, tactic.detail(),
                        Math.max(0, tactic.safeDistance() - distance));
            }
        };
    }

    private void ensureCreeperFollow(ActiveOperation operation, CreeperEntity creeper) {
        if (operation.creeperFollowing && !operation.tacticalInputsActive
                && !operation.tacticalShieldPrepared) return;
        clearTacticalInputs(operation);
        releaseCombatHand(operation);
        setActuatorScope(BaritoneActuatorScopePolicy.Owner.ROUTE);
        baritone.getFollowProcess().follow(entity -> entity.getUuid().equals(creeper.getUuid()));
        operation.creeperFollowing = true;
    }

    private boolean retreatFromCreeper(ActiveOperation operation, CreeperEntity creeper) {
        return retreatFromCreeper(operation, creeper, null);
    }

    private boolean retreatFromCreeper(
            ActiveOperation operation,
            CreeperEntity creeper,
            PropertyCreeperSafetyPolicy.EscapeVector requestedEscape) {
        var player = client.player;
        stopTacticalShield(operation);
        takeCloseCombatControl(operation);
        var input = baritone.getInputOverrideHandler();
        boolean moving = requestedEscape != null && requestedEscape.present()
                ? faceSafeRetreatAlong(requestedEscape)
                : faceSafeRetreatFrom(creeper);
        if (moving) {
            input.setInputForceState(Input.MOVE_FORWARD, true);
            input.setInputForceState(Input.SPRINT, true);
            if (player.horizontalCollision && player.isOnGround()) {
                input.setInputForceState(Input.JUMP, true);
            }
        }
        operation.creeperFollowing = false;
        operation.tacticalInputsActive = true;
        return moving;
    }

    private boolean faceSafeRetreatAlong(
            PropertyCreeperSafetyPolicy.EscapeVector escape) {
        var player = client.player;
        if (player == null || client.world == null || !escape.present()) return false;
        double[] turns = {0, Math.PI / 4, -Math.PI / 4};
        for (double turn : turns) {
            double cos = Math.cos(turn);
            double sin = Math.sin(turn);
            double dx = escape.x() * cos - escape.z() * sin;
            double dz = escape.x() * sin + escape.z() * cos;
            if (!safeGroundStep(dx, dz)) continue;
            player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
            player.setPitch(0.0F);
            return true;
        }
        return false;
    }

    private boolean faceSafeRetreatFrom(Entity threat) {
        var player = client.player;
        if (player == null || client.world == null) return false;
        double awayX = player.getX() - threat.getX();
        double awayZ = player.getZ() - threat.getZ();
        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 0.001) return false;
        double baseX = awayX / length;
        double baseZ = awayZ / length;
        double[] turns = {0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2};
        for (double turn : turns) {
            double cos = Math.cos(turn);
            double sin = Math.sin(turn);
            double dx = baseX * cos - baseZ * sin;
            double dz = baseX * sin + baseZ * cos;
            if (!safeGroundStep(dx, dz)) continue;
            player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
            player.setPitch(0.0F);
            return true;
        }
        return false;
    }

    private boolean safeGroundStep(double dx, double dz) {
        var player = client.player;
        if (!player.isOnGround()) return true;
        BlockPos feet = BlockPos.ofFloored(
                player.getX() + dx * 1.15,
                player.getY() + 0.1,
                player.getZ() + dz * 1.15);
        if (!client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        BlockState feetState = client.world.getBlockState(feet);
        BlockState headState = client.world.getBlockState(feet.up());
        BlockState floorState = client.world.getBlockState(feet.down());
        if (!feetState.getCollisionShape(client.world, feet).isEmpty()
                || !headState.getCollisionShape(client.world, feet.up()).isEmpty()
                || floorState.getCollisionShape(client.world, feet.down()).isEmpty()) return false;
        return !feetState.getFluidState().isIn(FluidTags.LAVA)
                && !headState.getFluidState().isIn(FluidTags.LAVA)
                && !feetState.isOf(Blocks.FIRE)
                && !feetState.isOf(Blocks.SOUL_FIRE)
                && !feetState.isOf(Blocks.CACTUS)
                && !feetState.isOf(Blocks.POWDER_SNOW)
                && !floorState.isOf(Blocks.MAGMA_BLOCK)
                && !floorState.isOf(Blocks.CACTUS)
                && !floorState.isOf(Blocks.CAMPFIRE)
                && !floorState.isOf(Blocks.SOUL_CAMPFIRE);
    }

    /** Close-combat strafing never voluntarily steps off land into either fluid. */
    private boolean safeCombatGroundStep(double dx, double dz) {
        var player = client.player;
        if (player == null || client.world == null || !safeGroundStep(dx, dz)) return false;
        BlockPos feet = BlockPos.ofFloored(
                player.getX() + dx * 1.15,
                player.getY() + 0.1,
                player.getZ() + dz * 1.15);
        BlockState feetState = client.world.getBlockState(feet);
        BlockState floorState = client.world.getBlockState(feet.down());
        return !feetState.getFluidState().isIn(FluidTags.WATER)
                && !floorState.getFluidState().isIn(FluidTags.WATER)
                && !feetState.getFluidState().isIn(FluidTags.LAVA)
                && !floorState.getFluidState().isIn(FluidTags.LAVA);
    }

    private boolean holdShieldAgainst(ActiveOperation operation, Entity threat) {
        var player = client.player;
        if (player == null || threat == null) {
            stopTacticalShield(operation);
            return false;
        }
        Hand hand = null;
        int shieldSlot = -1;
        if (player.getOffHandStack().isOf(Items.SHIELD)) {
            if (!player.getItemCooldownManager().isCoolingDown(player.getOffHandStack())) {
                hand = Hand.OFF_HAND;
            }
        }
        if (hand == null) {
            var inventory = player.getInventory();
            for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
                if (inventory.getStack(slot).isOf(Items.SHIELD)
                        && !player.getItemCooldownManager().isCoolingDown(inventory.getStack(slot))) {
                    hand = Hand.MAIN_HAND;
                    shieldSlot = slot;
                    break;
                }
            }
        }
        if (hand == null) {
            stopTacticalShield(operation);
            return false;
        }

        RangedRouteShieldPolicy.Decision shieldDecision = operation.rangedRouteShield.step(
                new RangedRouteShieldPolicy.Input(
                RangedRouteShieldPolicy.Intent.CLOSE,
                combatTargetKind(threat) == CombatEngagementPolicy.TargetKind.RANGED,
                threat.isAlive(),
                player.canSee(threat),
                arbiter.isValid(operation.controlLease, System.currentTimeMillis()),
                false,
                hand == Hand.OFF_HAND,
                true,
                false,
                0.0));
        if (!shieldDecision.holdUse()) {
            stopTacticalShield(operation);
            return false;
        }
        takeCloseCombatControl(operation);
        faceCombatTarget(threat);
        if (!holdTacticalShieldUse(operation, hand, shieldSlot)) return false;
        operation.tacticalInputsActive = true;
        operation.creeperFollowing = false;
        return true;
    }

    /** Revalidates the refreshable advisory before it can influence shield facing. */
    private Entity liveVisibleRangedPressure(ActiveOperation operation) {
        Entity rangedPressure = operation.visibleRangedPressure;
        var player = client.player;
        if (rangedPressure == null || player == null
                || !rangedPressure.isAlive() || rangedPressure.isRemoved()
                || !player.canSee(rangedPressure)
                || combatTargetKind(rangedPressure)
                != CombatEngagementPolicy.TargetKind.RANGED) {
            operation.visibleRangedPressure = null;
            return null;
        }
        return rangedPressure;
    }

    private void refreshVisibleRangedPressure(
            ActiveOperation operation,
            Entity rangedPressure) {
        String nextId = rangedPressure == null ? "" : rangedPressure.getUuidAsString();
        String currentId = operation.visibleRangedPressure == null
                ? ""
                : operation.visibleRangedPressure.getUuidAsString();
        operation.visibleRangedPressure = rangedPressure;
        if (nextId.equals(currentId)) return;
        resetCooldownYieldShieldAttempt(operation);
    }

    private void resetCooldownYieldShieldAttempt(ActiveOperation operation) {
        operation.cooldownYieldShieldState = SealedShieldStartPolicy.State.STARTING;
        operation.cooldownYieldShieldDeadlineAt = 0L;
    }

    /**
     * Starts at most one fixed-window shield attempt for the current visible ranged advisory.
     * Repeated executor polls cannot slide or reopen the deadline; a changed advisory explicitly
     * creates a new operation-scoped attempt.
     */
    private SealedShieldStartPolicy.State holdCooldownYieldShield(
            ActiveOperation operation,
            Entity rangedPressure) {
        if (operation.cooldownYieldShieldState == SealedShieldStartPolicy.State.FAILED) {
            takeCloseCombatControl(operation);
            faceCombatTarget(rangedPressure);
            stopTacticalShield(operation);
            return operation.cooldownYieldShieldState;
        }
        long now = System.currentTimeMillis();
        if (operation.cooldownYieldShieldDeadlineAt == 0L) {
            operation.cooldownYieldShieldDeadlineAt =
                    now + TACTICAL_SHIELD_START_TIMEOUT_MILLIS;
        }
        holdShieldAgainst(operation, rangedPressure);
        operation.cooldownYieldShieldState = SealedShieldStartPolicy.decide(
                operation.tacticalShieldAccepted,
                activelyBlockingWithAcceptedOffhandShield(operation),
                now,
                operation.cooldownYieldShieldDeadlineAt);
        if (operation.cooldownYieldShieldState == SealedShieldStartPolicy.State.FAILED) {
            stopTacticalShield(operation);
        }
        return operation.cooldownYieldShieldState;
    }

    /**
     * Holds only a ready offhand shield while Baritone keeps all movement/path ownership.
     * Main-hand shield selection is intentionally excluded: it would steal route tool custody.
     */
    private boolean holdRangedRouteShield(ActiveOperation operation, Entity threat) {
        if (!imminentProjectileThreat(operation, threat)) {
            stopTacticalShield(operation);
            return false;
        }
        var player = client.player;
        long now = System.currentTimeMillis();
        boolean offhandReady = player != null
                && player.getOffHandStack().isOf(Items.SHIELD)
                && !player.getItemCooldownManager().isCoolingDown(player.getOffHandStack());
        Rotation targetRotation = combatRotation(threat);
        float currentYaw = player == null ? 0.0F : player.getYaw();
        try {
            currentYaw = baritone.getPlayerContext().playerRotations().getYaw();
        } catch (RuntimeException ignored) {
        }
        boolean routeSteering = baritone.getPathingBehavior().isPathing();
        boolean exactRouteOwned = active == operation
                && operation.attackTarget == threat
                && threat != null
                && threat.isAlive();
        RangedRouteShieldPolicy.Decision decision = operation.rangedRouteShield.step(
                new RangedRouteShieldPolicy.Input(
                        RangedRouteShieldPolicy.Intent.ROUTE,
                        threat != null && combatTargetKind(threat)
                                == CombatEngagementPolicy.TargetKind.RANGED,
                        threat != null && threat.isAlive(),
                        player != null && threat != null && player.canSee(threat),
                        arbiter.isValid(operation.controlLease, now),
                        exactRouteOwned,
                        offhandReady,
                        offhandReady,
                        routeSteering,
                        RangedRouteShieldPolicy.absoluteYawDelta(
                                currentYaw, targetRotation.getYaw())));
        if (!decision.holdUse()) {
            stopTacticalShield(operation);
            return false;
        }
        if (decision.requestAim()) {
            // updateTarget is a one-tick Baritone look request, not direct body movement. Large
            // obstacle-route turns deliberately skip it; Baritone's steering remains authoritative.
            try {
                baritone.getLookBehavior().updateTarget(targetRotation, false);
            } catch (RuntimeException ignored) {
            }
        }
        if (!holdTacticalShieldUse(operation, Hand.OFF_HAND, -1)) return false;
        operation.creeperFollowing = false;
        return true;
    }

    private boolean imminentProjectileThreat(ActiveOperation operation, Entity threat) {
        long now = System.currentTimeMillis();
        if (observedProjectileThreat(threat)) operation.projectileBlockUntil = now + 500L;
        return now < operation.projectileBlockUntil;
    }

    private boolean observedProjectileThreat(Entity threat) {
        var player = client.player;
        if (player == null || client.world == null) return false;
        if (threat instanceof LivingEntity living && player.canSee(threat)) {
            if (ProjectileThreatPolicy.bowReleaseSoon(
                    living.isUsingItem() && living.getActiveItem().isOf(Items.BOW),
                    living.getItemUseTime())) return true;
            // Crossbows/tridents do not expose the skeleton bow draw contract.
            if (living.isUsingItem() && (living.getActiveItem().isOf(Items.CROSSBOW)
                    || living.getActiveItem().isOf(Items.TRIDENT))) return true;
        }
        for (Entity entity : client.world.getOtherEntities(player,
                player.getBoundingBox().expand(24.0),
                candidate -> candidate instanceof net.minecraft.entity.projectile.ProjectileEntity)) {
            var projectile = (net.minecraft.entity.projectile.ProjectileEntity) entity;
            if (projectile.getOwner() == player || !player.canSee(projectile)) continue;
            Vec3d relative = projectile.getPos().subtract(player.getBoundingBox().getCenter());
            Vec3d velocity = projectile.getVelocity();
            if (ProjectileThreatPolicy.incoming(relative.x, relative.y, relative.z,
                    velocity.x, velocity.y, velocity.z)) return true;
        }
        return false;
    }

    /**
     * Heartbeats a sealed route without the ranged-only route policy. Both melee and ranged sealed
     * routes require the same already-active offhand shield; any ownership or blocking drop latches
     * this exact attempt failed so the caller can cancel movement and return a typed BLOCKED edge.
     */
    private boolean holdSealedRouteShield(ActiveOperation operation, Entity threat) {
        return heartbeatSealedShield(operation, threat, false);
    }

    private boolean heartbeatSealedShield(
            ActiveOperation operation,
            Entity threat,
            boolean stationaryYield) {
        var player = client.player;
        boolean exactRouteOwned = active == operation
                && operation.attackTarget == threat
                && threat != null
                && threat.isAlive();
        boolean activelyBlocking = activelyBlockingWithAcceptedOffhandShield(operation);
        SealedShieldStartPolicy.RouteAction beforeHeartbeat = stationaryYield
                ? SealedShieldStartPolicy.yieldAction(
                        player != null && player.isTouchingWater() && !player.isInLava(),
                        operation.protectionCombatContext
                                == ProtectionCombatContext.SEALED_LOW_HEALTH_RESOLUTION,
                        operation.sealedShieldStartState,
                        exactRouteOwned,
                        usableOffhandShield(),
                        operation.tacticalShieldAccepted,
                        activelyBlocking)
                : SealedShieldStartPolicy.routeAction(
                        operation.protectionCombatContext
                                == ProtectionCombatContext.SEALED_LOW_HEALTH_RESOLUTION,
                        operation.sealedShieldStartState,
                        exactRouteOwned,
                        usableOffhandShield(),
                        operation.tacticalShieldAccepted,
                        activelyBlocking);
        if (beforeHeartbeat != SealedShieldStartPolicy.RouteAction.HEARTBEAT
                || player == null
                || !holdTacticalShieldUse(operation, Hand.OFF_HAND, -1)) {
            operation.sealedShieldStartState = SealedShieldStartPolicy.State.FAILED;
            operation.sealedRouteCustody = false;
            stopTacticalShield(operation);
            return false;
        }
        SealedShieldStartPolicy.RouteAction afterHeartbeat = stationaryYield
                ? SealedShieldStartPolicy.yieldAction(
                        player.isTouchingWater() && !player.isInLava(),
                        true,
                        operation.sealedShieldStartState,
                        exactRouteOwned,
                        usableOffhandShield(),
                        operation.tacticalShieldAccepted,
                        activelyBlockingWithAcceptedOffhandShield(operation))
                : SealedShieldStartPolicy.routeAction(
                        true,
                        operation.sealedShieldStartState,
                        exactRouteOwned,
                        usableOffhandShield(),
                        operation.tacticalShieldAccepted,
                        activelyBlockingWithAcceptedOffhandShield(operation));
        if (afterHeartbeat != SealedShieldStartPolicy.RouteAction.HEARTBEAT) {
            operation.sealedShieldStartState = SealedShieldStartPolicy.State.FAILED;
            operation.sealedRouteCustody = false;
            stopTacticalShield(operation);
            return false;
        }
        operation.creeperFollowing = false;
        return true;
    }

    /** Keeps sealed YIELD stationary while retaining the same authoritative shield generation. */
    private boolean holdSealedStationaryShield(ActiveOperation operation, Entity threat) {
        takeCloseCombatControl(operation);
        faceCombatTarget(threat);
        if (!heartbeatSealedShield(operation, threat, true)) return false;
        operation.tacticalInputsActive = true;
        return true;
    }

    private boolean activelyBlockingWithAcceptedOffhandShield(ActiveOperation operation) {
        var player = client.player;
        return player != null
                && operation.tacticalShieldAccepted
                && player.isUsingItem()
                && player.getActiveHand() == Hand.OFF_HAND
                && player.getActiveItem().isOf(Items.SHIELD)
                && player.isBlocking();
    }

    /** Heartbeats the exact DirectUse generation without claiming movement or attack keys. */
    private boolean holdTacticalShieldUse(
            ActiveOperation operation,
            Hand hand,
            int shieldSlot) {
        var player = client.player;
        if (player == null || client.interactionManager == null) {
            stopTacticalShield(operation);
            return false;
        }
        if (!operation.tacticalShieldPrepared) {
            operation.tacticalShieldPrepared = true;
            operation.tacticalPreviousSlot = -1;
            operation.tacticalShieldChangedSlot = false;
            operation.tacticalShieldAccepted = false;
            operation.tacticalShieldLastStartAt = Long.MIN_VALUE;
            operation.tacticalShieldStartDeadlineAt = System.currentTimeMillis()
                    + TACTICAL_SHIELD_START_TIMEOUT_MILLIS;
        }
        if (hand == Hand.MAIN_HAND) {
            if (!operation.tacticalShieldChangedSlot) {
                operation.tacticalPreviousSlot = player.getInventory().getSelectedSlot();
                operation.tacticalShieldChangedSlot = true;
            }
            selectHotbarSlotNow(shieldSlot);
        }
        long now = System.currentTimeMillis();
        MinecraftActuatorGateway.UseFrame useFrame =
                actuators.holdUseFrame(tacticalShieldOperation(operation));
        if (!useFrame.active()) {
            stopTacticalShield(operation);
            return false;
        }
        operation.tacticalShieldUseGeneration = useFrame.generation();
        if (useFrame.started()) {
            // A focus stall, owner replacement, or stale heartbeat creates a
            // fresh capability. Re-acknowledge the shield once for that exact
            // generation instead of trusting the old accepted flag.
            operation.tacticalShieldAccepted = false;
            operation.tacticalShieldLastStartAt = Long.MIN_VALUE;
            operation.tacticalShieldStartDeadlineAt = now
                    + TACTICAL_SHIELD_START_TIMEOUT_MILLIS;
        }
        if (!operation.tacticalShieldAccepted
                && now <= operation.tacticalShieldStartDeadlineAt
                && (operation.tacticalShieldLastStartAt == Long.MIN_VALUE
                || now - operation.tacticalShieldLastStartAt >= TACTICAL_SHIELD_REISSUE_MILLIS)) {
            operation.tacticalShieldLastStartAt = now;
            operation.tacticalShieldAccepted = client.interactionManager
                    .interactItem(player, hand).isAccepted();
        }
        if (!operation.tacticalShieldAccepted
                && now > operation.tacticalShieldStartDeadlineAt) {
            stopTacticalShield(operation);
            return false;
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        return true;
    }

    private boolean shieldReady() {
        var player = client.player;
        if (player == null) return false;
        if (usableOffhandShield()) return true;
        var inventory = player.getInventory();
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (inventory.getStack(slot).isOf(Items.SHIELD)
                    && !player.getItemCooldownManager().isCoolingDown(inventory.getStack(slot))) return true;
        }
        return false;
    }

    private boolean usableOffhandShield() {
        var player = client.player;
        return player != null
                && player.getOffHandStack().isOf(Items.SHIELD)
                && !player.getItemCooldownManager().isCoolingDown(player.getOffHandStack());
    }

    private void clearTacticalInputs(ActiveOperation operation) {
        if (operation == null) return;
        operation.sealedRouteCustody = false;
        clearTacticalMovementInputs(operation);
        stopTacticalShield(operation);
    }

    private void clearTacticalMovementInputs(ActiveOperation operation) {
        if (operation == null) return;
        try {
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException ignored) {
        }
        operation.tacticalInputsActive = false;
        operation.creeperFollowing = false;
    }

    private void stopTacticalShield(ActiveOperation operation) {
        if (operation == null) return;
        operation.rangedRouteShield.reset();
        if (!operation.tacticalShieldPrepared) return;
        if (operation.tacticalShieldUseGeneration > 0L) {
            actuators.releaseHeldUse(
                    tacticalShieldOperation(operation),
                    operation.tacticalShieldUseGeneration);
        }
        // A food/retreat owner may have replaced this tactical generation
        // before its late cleanup runs.  Generation-fenced release correctly
        // leaves that replacement alive; do not then bypass the lease by
        // stopping Minecraft use or restoring this operation's old slot.
        boolean heldUseStillOwned = actuators.heldUseSnapshot().active();
        operation.tacticalShieldUseGeneration = 0L;
        try {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        } catch (RuntimeException ignored) {
        }
        var player = client.player;
        if (player != null && !heldUseStillOwned) {
            try {
                if (client.interactionManager != null && player.isUsingItem()) {
                    client.interactionManager.stopUsingItem(player);
                }
                if (operation.tacticalShieldChangedSlot
                        && operation.tacticalPreviousSlot >= 0) {
                    selectHotbarSlotNow(operation.tacticalPreviousSlot);
                }
            } catch (RuntimeException ignored) {
            }
        }
        operation.tacticalPreviousSlot = -1;
        operation.tacticalShieldChangedSlot = false;
        operation.tacticalShieldPrepared = false;
        operation.tacticalShieldAccepted = false;
        operation.tacticalShieldLastStartAt = Long.MIN_VALUE;
        operation.tacticalShieldStartDeadlineAt = 0L;
    }

    private static String tacticalShieldOperation(ActiveOperation operation) {
        return "baritone:tactical-shield:" + operation.goal.missionId();
    }

    private void selectHotbarSlotNow(int slot) {
        if (client.player == null || slot < 0 || slot >= PlayerInventory.getHotbarSize()) return;
        if (client.player.getInventory().getSelectedSlot() == slot) return;
        client.player.getInventory().setSelectedSlot(slot);
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private void maintainTrackedPlayer(ActiveOperation operation) {
        operation.waitingDetail = null;
        operation.progressExpected = true;
        if (!operation.playerTarget) {
            boolean directAquaticRequested = directAquaticMissionRouteRequested(operation);
            AquaticRouteCustodyPolicy.MovementOwner movementOwner =
                    stepAquaticCustody(operation, directAquaticRequested);
            if (movementOwner != AquaticRouteCustodyPolicy.MovementOwner.BARITONE) {
                suspendRoutesForAquaticCustody(operation);
                operation.progressExpected = directAquaticRequested;
                operation.waitingDetail = switch (movementOwner) {
                    case SUSPENDING_BARITONE ->
                            "suspending Baritone before direct aquatic coordinate travel";
                    case NEUTRAL_HANDOFF ->
                            "sampling an exact neutral aquatic frame before the land route resumes";
                    case DIRECT_AQUATIC ->
                            "direct aquatic coordinate travel selected; Baritone route suspended";
                    case BARITONE -> throw new IllegalStateException("unreachable custody branch");
                };
            } else if (operation.aquaticCoordinateRouteReissue.admit(
                    operation.goal.kind(),
                    aquaticBaritoneHandoffActive(operation, System.currentTimeMillis()),
                    movementOwner,
                    aquaticBaritoneBusy(),
                    client.player != null && client.player.isOnGround() && !client.player.isTouchingWater())) {
                // The direct actuator canceled the old CustomGoalProcess before yielding. Once a
                // newer exact neutral frame has been sampled, reconnect the same durable Home
                // coordinate to Baritone inside this operation; waiting for a caller to recreate
                // the operation leaves internal Home routes permanently quiescent.
                operation.lastEvent = null;
                operation.lastEventAt = 0L;
                lastPathEvent.set(null);
                operation.traversalWatchdog.reset();
                startGoto(operation.goal.arguments(), false);
                operation.waitingDetail =
                        "reissued the retained coordinate route after exact aquatic handoff";
            }
            return;
        }
        if (operation.tacticalInputsActive) {
            // The exact loaded target is already under the close-combat controller. Do not start a
            // fresh GoalNear in front of its complete movement/shield frame; route ownership is
            // restored explicitly when the target leaves the close envelope.
            operation.progressExpected = false;
            return;
        }
        operation.progressExpected = false;
        Entity visible = nearestTarget(operation);
        boolean directAquaticRequested = directAquaticPursuitRequested(operation, visible);
        AquaticRouteCustodyPolicy.MovementOwner movementOwner =
                stepAquaticCustody(operation, directAquaticRequested);
        if (movementOwner != AquaticRouteCustodyPolicy.MovementOwner.BARITONE) {
            // Cancel both possible player-route sources before the direct controller writes a
            // frame. During NEUTRAL_HANDOFF these calls keep the old goal absent while
            // maintainAquaticTravel cancels the final accepted frame later in this same poll.
            suspendRoutesForAquaticCustody(operation);
            operation.progressExpected = directAquaticRequested;
            operation.waitingDetail = switch (movementOwner) {
                case SUSPENDING_BARITONE ->
                        "suspending Baritone before direct aquatic pursuit";
                case NEUTRAL_HANDOFF ->
                        "sampling an exact neutral aquatic frame before a fresh land route";
                case DIRECT_AQUATIC ->
                        "direct aquatic pursuit selected; Baritone route suspended";
                case BARITONE -> throw new IllegalStateException("unreachable custody branch");
            };
            return;
        }
        if (visible != null) {
            if (operation.waypointMode) {
                baritone.getPathingBehavior().cancelEverything();
                operation.waypointMode = false;
                operation.waypointReplans.reset();
            }
            maintainVisiblePlayerPursuit(operation, visible, System.currentTimeMillis());
            if (client.player != null) {
                // GoalNear decides against floored player feet, while exact entity distance also
                // includes sub-block offsets. Mixing those contracts could leave Baritone
                // correctly finished while the watchdog still expected motion and eventually
                // blocked a healthy follow mission at a one-block beach step.
                operation.progressExpected = !targetGoalNearSatisfied(
                        visible, operation.followRadius);
            }
            if (operation.progressExpected) {
                operation.waitingDetail = "following committed route segment toward "
                        + operation.targetDescription;
            } else {
                operation.waitingDetail = "holding follow radius around " + operation.targetDescription;
            }
            return;
        }

        suspendVisiblePlayerRoute(operation);

        TrackedWaypoint waypoint = trackedWaypoint;
        if (waypoint == null || !waypoint.player().equalsIgnoreCase(operation.targetDescription)) {
            suspendWaypointRoute(operation);
            operation.waitingDetail = "waiting for server coordinates for " + operation.targetDescription;
            return;
        }
        if (!waypoint.online()) {
            suspendWaypointRoute(operation);
            operation.waitingDetail = operation.targetDescription + " is offline; mission retained";
            return;
        }
        if (System.currentTimeMillis() - waypoint.timestamp() > 30_000) {
            suspendWaypointRoute(operation);
            operation.waitingDetail = "waiting for fresh coordinates for " + operation.targetDescription;
            return;
        }
        String localDimension = client.world == null
                ? ""
                : client.world.getRegistryKey().getValue().toString();
        if (!waypoint.dimension().isBlank() && !waypoint.dimension().equals(localDimension)) {
            suspendWaypointRoute(operation);
            operation.waitingDetail = operation.targetDescription + " is in " + waypoint.dimension()
                    + "; portal planning is not yet available";
            return;
        }
        if (client.player == null) return;

        double dx = client.player.getX() - waypoint.x();
        double dy = client.player.getY() - waypoint.y();
        double dz = client.player.getZ() - waypoint.z();
        if (dx * dx + dy * dy + dz * dz <= 36.0) {
            operation.waitingDetail = "waiting for " + operation.targetDescription + " to enter the loaded chunk";
            return;
        }

        long now = System.currentTimeMillis();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        WaypointReplanPolicy.Target target =
                new WaypointReplanPolicy.Target(waypoint.x(), waypoint.y(), waypoint.z());
        boolean moving = operation.waypointMode && baritone.getPathingBehavior().isPathing();
        boolean calculating = operation.waypointMode && !moving
                && (baritone.getPathingBehavior().getInProgress().isPresent()
                || (baritone.getCustomGoalProcess().isActive()
                && isCalculationEvent(operation.lastEvent)));
        WaypointReplanPolicy.RouteState routeState = moving
                ? WaypointReplanPolicy.RouteState.MOVING
                : calculating
                ? WaypointReplanPolicy.RouteState.CALCULATING
                : WaypointReplanPolicy.RouteState.INACTIVE;
        WaypointReplanPolicy.Decision decision = operation.waypointReplans.evaluate(
                target, distance, routeState, now);
        if (decision.exhausted()) {
            suspendWaypointRoute(operation);
            operation.progressExpected = false;
            operation.blockedDetail = "long-range waypoint route to "
                    + operation.targetDescription + " exhausted after "
                    + decision.failedAttempts() + " bounded failed attempt(s); stopped instead of "
                    + "restarting Baritone forever. Use /e retry after changing terrain or position";
            operation.waitingDetail = operation.blockedDetail;
            return;
        }
        if (decision.replan()) {
            baritone.getFollowProcess().cancel();
            BlockPos destination = new BlockPos(
                    (int) Math.floor(waypoint.x()),
                    (int) Math.floor(waypoint.y()),
                    (int) Math.floor(waypoint.z()));
            configureTraversalMode(operation, destination, now);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(destination, 4));
            operation.waypointMode = true;
            operation.waypointReplans.replanned(target, distance, now);
            logger.debug("Waypoint route for {} recalculated: {}",
                    operation.targetDescription, decision.reason());
        }
        if (decision.reason() == WaypointReplanPolicy.Reason.CALCULATION_IN_PROGRESS) {
            operation.waitingDetail = "calculating long-range route toward "
                    + operation.targetDescription + "; preserving the in-progress calculation";
            operation.progressExpected = false;
        } else if (decision.reason() == WaypointReplanPolicy.Reason.INACTIVE_BACKOFF) {
            long retrySeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1_000);
            operation.waitingDetail = "waypoint route ended before becoming active; retrying in "
                    + retrySeconds + "s (failed attempt " + decision.failedAttempts() + '/'
                    + WaypointReplanPolicy.MAX_INACTIVE_FAILURES + ')';
            operation.progressExpected = false;
        } else {
            operation.waitingDetail = "pathing through unloaded terrain toward "
                    + operation.targetDescription;
            operation.progressExpected = true;
        }
    }

    private AquaticRouteCustodyPolicy.MovementOwner stepAquaticCustody(
            ActiveOperation operation,
            boolean directAquaticRequested) {
        MovementFrameActuator.Observation sampledMovement = movement.snapshot().observation();
        AquaticRouteCustodyPolicy.MovementOwner previousMovementOwner =
                operation.aquaticPursuitCustody.owner();
        AquaticRouteCustodyPolicy.MovementOwner movementOwner =
                operation.aquaticPursuitCustody.step(
                        new AquaticRouteCustodyPolicy.OwnershipInput(
                                directAquaticRequested,
                                operation.aquaticInputsForced,
                                baritone.getPathingBehavior().isPathing(),
                                baritone.getPathingBehavior().getInProgress().isPresent(),
                                baritone.getCustomGoalProcess().isActive(),
                                sampledMovement.injectionSequence(),
                                exactAquaticActionSample(sampledMovement),
                                sampledMovement.kind() == MovementFrameActuator.Kind.NEUTRAL_HANDOFF
                                        && sampledMovement.action().isEmpty()
                                        && sampledMovement.exactMatch()));
        if (movementOwner != previousMovementOwner) {
            operation.traversalWatchdog.reset();
            if (movementOwner == AquaticRouteCustodyPolicy.MovementOwner.DIRECT_AQUATIC) {
                operation.aquaticCoordinateRouteReissue.directAquaticStarted();
                operation.aquaticDirectSampleBaseline = sampledMovement.injectionSequence();
                operation.aquaticObservedMotion = false;
                operation.aquaticDirectOriginKnown = false;
            } else {
                operation.aquaticDirectOriginKnown = false;
                operation.aquaticObservedMotion = false;
            }
        }
        return movementOwner;
    }

    private boolean aquaticBaritoneBusy() {
        return baritone.getPathingBehavior().isPathing()
                || baritone.getPathingBehavior().getInProgress().isPresent()
                || baritone.getCustomGoalProcess().isActive();
    }

    private void suspendRoutesForAquaticCustody(ActiveOperation operation) {
        suspendProtectionCombatRoute(operation);
        try {
            baritone.getFollowProcess().cancel();
        } catch (RuntimeException ignored) {
        }
        try {
            baritone.getCustomGoalProcess().setGoal(null);
        } catch (RuntimeException ignored) {
        }
        try {
            baritone.getPathingBehavior().cancelEverything();
        } catch (RuntimeException ignored) {
        }
        operation.visiblePlayerRouteMode = false;
        operation.visiblePlayerPursuit.reset();
        operation.waypointMode = false;
        operation.waypointReplans.reset();
    }

    private boolean directAquaticMissionRouteRequested(ActiveOperation operation) {
        var player = client.player;
        long now = System.currentTimeMillis();
        if (aquaticBaritoneHandoffActive(operation, now)) {
            return false;
        }
        AquaticRoute route = player == null
                ? null
                : currentAquaticRoute(operation, now);
        return AquaticRouteCustodyPolicy.directMissionRouteRequested(
                operation.goal.kind(),
                player != null && player.isTouchingWater(),
                player != null && player.isOnGround(),
                route != null,
                route != null && route.horizontalDistance() > DIRECT_AQUATIC_STOP_DISTANCE,
                route != null && route.destinationDry(),
                operation.aquaticTravel.snapshot().mode(),
                operation.aquaticPursuitCustody.owner()
                        == AquaticRouteCustodyPolicy.MovementOwner.DIRECT_AQUATIC);
    }

    private boolean directAquaticPursuitRequested(
            ActiveOperation operation,
            Entity visibleTarget) {
        var player = client.player;
        if (aquaticBaritoneHandoffActive(operation, System.currentTimeMillis())) {
            return false;
        }
        boolean targetVisible = visibleTarget != null;
        boolean targetDry = targetVisible && destinationDry(
                visibleTarget.getX(), visibleTarget.getY(), visibleTarget.getZ());
        boolean physicalRequest = AquaticRouteCustodyPolicy.physicalPlayerPursuitRequested(
                operation.playerTarget,
                player != null && player.isTouchingWater(),
                player != null && player.isOnGround(),
                targetVisible,
                targetDry,
                player == null || visibleTarget == null
                        ? Double.NaN
                        : player.squaredDistanceTo(visibleTarget),
                DIRECT_AQUATIC_STOP_DISTANCE * DIRECT_AQUATIC_STOP_DISTANCE,
                operation.aquaticTravel.snapshot().mode(),
                operation.aquaticPursuitCustody.owner()
                        == AquaticRouteCustodyPolicy.MovementOwner.DIRECT_AQUATIC);
        return AquaticRouteCustodyPolicy.directPursuitRequested(
                physicalRequest, operation.aquaticTravel.snapshot().mode());
    }

    private boolean aquaticBaritoneHandoffActive(
            ActiveOperation operation,
            long nowMillis) {
        return operation != null && aquaticRouteHandoff.active(
                operation.goal.missionId(),
                nowMillis,
                client.player != null && client.player.isTouchingWater(),
                client.player != null && client.player.isOnGround(),
                client.player == null ? Double.NaN : client.player.getX(),
                client.player == null ? Double.NaN : client.player.getZ(),
                AQUATIC_BARITONE_HANDOFF_PROGRESS);
    }

    private static boolean isCalculationEvent(PathEvent event) {
        return event == PathEvent.CALC_STARTED
                || event == PathEvent.NEXT_SEGMENT_CALC_STARTED
                || event == PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING;
    }

    private boolean canceledRouteRequiresRestart(ActiveOperation operation, long nowMillis) {
        if (operation.pendingCompletionDetail != null) return false;
        if (operation.localCollisionRecoveryActive) return false;
        // Direct swimming deliberately cancels the Baritone executor while retaining the durable
        // coordinate goal. Recreating ActiveOperation during that segment would erase custody and
        // turn one water crossing into a cancel/restart loop.
        if (!operation.aquaticPursuitCustody.baritoneMayOwnMovement()) return false;
        String kind = operation.goal.kind().toLowerCase(Locale.ROOT);
        boolean gotoOperation = kind.equals("goto") || kind.equals("go");
        boolean contextReady = playerContextReady();
        boolean pathing = contextReady && baritone.getPathingBehavior().isPathing();
        boolean calculating = contextReady
                && baritone.getPathingBehavior().getInProgress().isPresent();
        boolean goalSatisfied = contextReady && gotoGoalSatisfied();
        long canceledFor = operation.lastEventAt <= 0L
                ? 0L
                : Math.max(0L, nowMillis - operation.lastEventAt);
        return BaritoneStartPolicy.canceledRouteRequiresRestart(
                gotoOperation,
                contextReady,
                operation.lastEvent == PathEvent.CANCELED,
                goalSatisfied,
                pathing,
                calculating,
                canceledFor,
                CANCELED_ROUTE_RESTART_GRACE_MILLIS);
    }

    private boolean gotoGoalSatisfied() {
        var goal = baritone.getCustomGoalProcess().mostRecentGoal();
        var feet = baritone.getPlayerContext().playerFeet();
        return goal != null && goal.isInGoal(feet.getX(), feet.getY(), feet.getZ());
    }

    private void maintainVisiblePlayerPursuit(
            ActiveOperation operation,
            Entity target,
            long nowMillis) {
        var player = client.player;
        if (player == null) return;
        boolean moving = operation.visiblePlayerRouteMode
                && baritone.getPathingBehavior().isPathing();
        boolean calculating = operation.visiblePlayerRouteMode && !moving
                && (baritone.getPathingBehavior().getInProgress().isPresent()
                || (baritone.getCustomGoalProcess().isActive()
                && isCalculationEvent(operation.lastEvent)));
        VisiblePlayerPursuitPolicy.RouteState routeState = moving
                ? VisiblePlayerPursuitPolicy.RouteState.MOVING
                : calculating
                ? VisiblePlayerPursuitPolicy.RouteState.CALCULATING
                : VisiblePlayerPursuitPolicy.RouteState.INACTIVE;
        boolean breaking = client.interactionManager != null
                && client.interactionManager.isBreakingBlock();
        VisiblePlayerPursuitPolicy.Target snapshot = new VisiblePlayerPursuitPolicy.Target(
                target.getX(), target.getY(), target.getZ());
        VisiblePlayerPursuitPolicy.Decision decision = operation.visiblePlayerPursuit.evaluate(
                snapshot,
                Math.sqrt(player.squaredDistanceTo(target)),
                operation.followRadius,
                targetGoalNearSatisfied(target, operation.followRadius),
                routeState,
                breaking,
                nowMillis);
        if (decision.action() == VisiblePlayerPursuitPolicy.Action.KEEP_COMMITTED) return;

        if (decision.action() == VisiblePlayerPursuitPolicy.Action.HOLD_POSITION) {
            if (operation.visiblePlayerRouteMode) {
                try {
                    baritone.getPathingBehavior().cancelEverything();
                } catch (RuntimeException ignored) {
                }
            }
            operation.visiblePlayerRouteMode = false;
            operation.visiblePlayerPursuit.held();
            return;
        }

        try {
            baritone.getFollowProcess().cancel();
        } catch (RuntimeException ignored) {
        }
        BlockPos destination = BlockPos.ofFloored(
                target.getX(), target.getY(), target.getZ());
        configureTraversalMode(operation, destination, nowMillis);
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(
                destination,
                operation.followRadius));
        operation.visiblePlayerRouteMode = true;
        operation.visiblePlayerPursuit.committed(snapshot, nowMillis);
        logger.debug("Committed player-pursuit segment for {}: {}",
                operation.targetDescription, decision.reason());
    }

    private boolean targetGoalNearSatisfied(Entity target, int radius) {
        return target != null && targetGoalNearSatisfied(target.getPos(), radius);
    }

    private boolean targetGoalNearSatisfied(Vec3d destination, int radius) {
        if (client.player == null) return false;
        BlockPos targetFeet = BlockPos.ofFloored(destination);
        BlockPos playerFeet = baritone.getPlayerContext().playerFeet();
        return new GoalNear(targetFeet, Math.max(1, radius)).isInGoal(
                playerFeet.getX(), playerFeet.getY(), playerFeet.getZ());
    }

    private void suspendVisiblePlayerRoute(ActiveOperation operation) {
        if (operation.visiblePlayerRouteMode) {
            try {
                baritone.getPathingBehavior().cancelEverything();
            } catch (RuntimeException ignored) {
            }
        }
        operation.visiblePlayerRouteMode = false;
        operation.visiblePlayerPursuit.reset();
    }

    private void suspendWaypointRoute(ActiveOperation operation) {
        if (operation.waypointMode) {
            try {
                baritone.getPathingBehavior().cancelEverything();
            } catch (RuntimeException ignored) {
            }
        }
        operation.waypointMode = false;
        operation.waypointReplans.reset();
    }

    private boolean targetWithin(ActiveOperation operation, double range) {
        Entity target = nearestTarget(operation);
        return client.player != null && target != null
                && client.player.squaredDistanceTo(target) <= range * range;
    }

    private Entity nearestTarget(ActiveOperation operation) {
        if (client.player == null || client.world == null || operation.targetPredicate == null) return null;
        return client.world.getOtherEntities(
                        client.player,
                        client.player.getBoundingBox().expand(TARGET_SEARCH_RADIUS),
                        entity -> entity.isAlive() && operation.targetPredicate.test(entity))
                .stream()
                .min(Comparator.comparingDouble(client.player::squaredDistanceTo))
                .orElse(null);
    }

    private static Status status(
            ActiveOperation operation,
            State state,
            String detail,
            double distanceRemaining) {
        return status(
                operation, state, detail, distanceRemaining,
                state == State.BLOCKED && operation.loadedHarvestSourceAbsent
                        ? BaritonePort.FailureCause.LOADED_HARVEST_SOURCE_ABSENT
                        : BaritonePort.FailureCause.NONE);
    }

    private static Status status(
            ActiveOperation operation,
            State state,
            String detail,
            double distanceRemaining,
            BaritonePort.FailureCause failureCause) {
        if (operation.accessToolRequired)
            return new Status(State.TRANSIENT_FAILURE, operation.waitingDetail, Double.NaN,
                    operation.completedWorkUnits, operation.controlEpoch, false,
                    BaritonePort.FailureCause.ACCESS_TOOL_REQUIRED);
        // Mining distance and outbound visited cells are not the return objective.
        // One explicit handoff resets Runtime's existing baseline; subsequent return
        // polls remain fully monitored. A replan within the same phase does not reset it.
        boolean returnPhaseBoundary = operation.resetMiningReturnProgressBaseline
                && state == State.EXECUTING;
        if (returnPhaseBoundary) operation.resetMiningReturnProgressBaseline = false;
        return new Status(
                state,
                detail,
                distanceRemaining,
                operation.completedWorkUnits,
                operation.controlEpoch,
                // ROUTE_BLOCKED_AT_AIR is an intentional stationary pause. Keep the
                // operation's internal progress flag true so the aquatic policy retains
                // custody and can re-assess its exact route, but do not arm Runtime's
                // global displacement watchdog while no safe re-dive is authorized.
                operation.progressExpected
                        && !returnPhaseBoundary
                        && operation.aquaticBlockedDetail == null
                        && state != State.CALCULATING,
                failureCause);
    }

    private static String operationDetail(ActiveOperation operation) {
        return operation.lastEvent == null
                ? "starting " + operation.goal.kind()
                : operation.lastEvent.name().toLowerCase(Locale.ROOT);
    }

    private static String required(Map<String, String> arguments, String key) {
        String value = arguments.getOrDefault(key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static int integer(Map<String, String> arguments, String key) {
        String value = required(arguments, key);
        try {
            return (int) Math.floor(Double.parseDouble(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be numeric", exception);
        }
    }

    private static int parseInteger(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    private static String simpleIdentifier(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace(' ', '_');
        return normalized.startsWith("minecraft:")
                ? normalized.substring("minecraft:".length())
                : normalized;
    }

    private String currentDimension() {
        return client.world == null
                ? "minecraft:unknown"
                : client.world.getRegistryKey().getValue().toString();
    }

    private static boolean isMiningKind(String kind) {
        return kind.equals("mine") || kind.equals("acquire")
                || kind.equals("fetch") || kind.equals("get");
    }

    private static EntityDispositionPolicy.Purpose attackPurpose(
            ActiveOperation operation,
            Entity target) {
        if (operation.goal.missionId().startsWith("protection:")) {
            return target instanceof PlayerEntity
                    ? EntityDispositionPolicy.Purpose.RETALIATION
                    : EntityDispositionPolicy.Purpose.PROTECTION;
        }
        return operation.goal.kind().equalsIgnoreCase("hunt")
                ? EntityDispositionPolicy.Purpose.HUNT
                : EntityDispositionPolicy.Purpose.EXPLICIT_ATTACK;
    }

    private static String protectionOperationId(String entityId) {
        return "protection:" + entityId;
    }

    /** Operation-scoped exception capabilities accepted by the close-combat policy. */
    public enum ProtectionCombatContext {
        STANDARD,
        SEALED_LOW_HEALTH_RESOLUTION
    }

    private record ResourceSearchColumn(
            ProtectedAreaPolicy.Area area,
            int x,
            int z,
            long horizontalDistanceSquared) {
    }

    private record NaturalTreeCommitment(
            boolean admitted,
            Set<ResourceActuationSession.Coordinate> coordinates,
            Map<ResourceActuationSession.Coordinate, String> accessBreaks,
            Set<ResourceActuationSession.Coordinate> accessObjectives,
            String detail) {
        private NaturalTreeCommitment {
            coordinates = Set.copyOf(Objects.requireNonNull(coordinates, "coordinates"));
            accessBreaks = Map.copyOf(Objects.requireNonNull(accessBreaks, "accessBreaks"));
            accessObjectives = Set.copyOf(Objects.requireNonNull(
                    accessObjectives, "accessObjectives"));
            if (!accessBreaks.keySet().containsAll(accessObjectives)) {
                throw new IllegalArgumentException(
                        "natural-tree access objectives require exact access authority");
            }
            detail = Objects.requireNonNullElse(detail, "").trim();
        }

        private static NaturalTreeCommitment admitted(
                Set<ResourceActuationSession.Coordinate> coordinates,
                Map<ResourceActuationSession.Coordinate, String> accessBreaks,
                Set<ResourceActuationSession.Coordinate> accessObjectives) {
            return new NaturalTreeCommitment(
                    true, coordinates, accessBreaks, accessObjectives,
                    "exact connected natural tree and canopy access admitted");
        }

        private static NaturalTreeCommitment denied(String detail) {
            return new NaturalTreeCommitment(
                    false, Set.of(), Map.of(), Set.of(), detail);
        }

        private static NaturalTreeCommitment notApplicable() {
            return new NaturalTreeCommitment(
                    true, Set.of(), Map.of(), Set.of(), "not a natural-log target");
        }
    }

    private record NaturalTreeAccessRay(
            List<LoadedResourceClassifier.Point> leaves,
            double distance) {
        private NaturalTreeAccessRay {
            leaves = List.copyOf(Objects.requireNonNull(leaves, "leaves"));
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException(
                        "natural-tree access ray requires finite nonnegative distance");
            }
        }
    }

    private record RawResourceCandidate(
            BlockPos position,
            String blockId,
            double distanceSquared) {
    }

    private record ReplantHand(Hand hand, int hotbarSlot) {
    }

    /** Exact sparse air schematic for native removal of committed resource cells. */
    private static final class CommittedTreeRemovalSchematic implements ISchematic {
        private final BlockPos origin;
        private final int width;
        private final int height;
        private final int length;
        private final Set<BlockPos> localTargets;

        private CommittedTreeRemovalSchematic(
                Set<ResourceActuationSession.Coordinate> targets) {
            if (targets == null || targets.isEmpty()) {
                throw new IllegalArgumentException(
                        "a committed-tree schematic requires exact targets");
            }
            int minX = targets.stream().mapToInt(
                    ResourceActuationSession.Coordinate::x).min().orElseThrow();
            int minY = targets.stream().mapToInt(
                    ResourceActuationSession.Coordinate::y).min().orElseThrow();
            int minZ = targets.stream().mapToInt(
                    ResourceActuationSession.Coordinate::z).min().orElseThrow();
            int maxX = targets.stream().mapToInt(
                    ResourceActuationSession.Coordinate::x).max().orElseThrow();
            int maxY = targets.stream().mapToInt(
                    ResourceActuationSession.Coordinate::y).max().orElseThrow();
            int maxZ = targets.stream().mapToInt(
                    ResourceActuationSession.Coordinate::z).max().orElseThrow();
            origin = new BlockPos(minX, minY, minZ);
            width = Math.addExact(Math.subtractExact(maxX, minX), 1);
            height = Math.addExact(Math.subtractExact(maxY, minY), 1);
            length = Math.addExact(Math.subtractExact(maxZ, minZ), 1);
            localTargets = targets.stream()
                    .map(target -> new BlockPos(
                            target.x() - minX,
                            target.y() - minY,
                            target.z() - minZ))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        private BlockPos origin() {
            return origin;
        }

        @Override
        public boolean inSchematic(
                int x,
                int y,
                int z,
                BlockState currentState) {
            return x >= 0 && x < width
                    && y >= 0 && y < height
                    && z >= 0 && z < length
                    && localTargets.contains(new BlockPos(x, y, z));
        }

        @Override
        public BlockState desiredState(
                int x,
                int y,
                int z,
                BlockState currentState,
                List<BlockState> approximatelyPlaceable) {
            return Blocks.AIR.getDefaultState();
        }

        @Override
        public int widthX() {
            return width;
        }

        @Override
        public int heightY() {
            return height;
        }

        @Override
        public int lengthZ() {
            return length;
        }
    }

    private static final class ActiveOperation {
        private final List<NativeFailedAscendEdges.Failure> failedAscendEdges = new ArrayList<>();
        private String failedAscendDimension = "";
        private BlockPos blueprintPortal;
        private BlockPos blueprintPortalApproach;
        private BlockState blueprintPortalWanted;
        private long blueprintPortalStarted;
        private long blueprintPortalIssued;
        private boolean blueprintPortalSettling;
        private final Goal goal;
        private final long startedAt;
        private final long controlEpoch;
        private ControlLease controlLease;
        private volatile PathEvent lastEvent;
        private volatile long lastEventAt;
        private Predicate<Entity> targetPredicate;
        private String targetDescription = "target";
        private Entity attackTarget;
        private BlockPos protectionEscapeOrigin;
        private double protectionEscapeDistance;
        private boolean protectionCombatRouteMode;
        private final VisiblePlayerPursuitPolicy.Session protectionCombatPursuit =
                new VisiblePlayerPursuitPolicy.Session();
        private JsonObject combatEngagement = new JsonObject();
        private ProtectionCombatContext protectionCombatContext =
                ProtectionCombatContext.STANDARD;
        /** Defensive advisory only; exact combat ownership remains {@link #attackTarget}. */
        private Entity visibleRangedPressure;
        private long projectileBlockUntil;
        private SealedShieldStartPolicy.State cooldownYieldShieldState =
                SealedShieldStartPolicy.State.STARTING;
        private long cooldownYieldShieldDeadlineAt;
        private BlockPos defeatedAt;
        private boolean playerTarget;
        private int followRadius = 3;
        private boolean boundedHunt;
        private long huntFrontierRetryAt;
        private int huntSearchOriginX;
        private int huntSearchOriginZ;
        private int huntSearchRadius;
        private long huntSearchDeadlineAt;
        private long huntSafetyUnavailableSince;
        private boolean waypointMode;
        private boolean visiblePlayerRouteMode;
        private boolean functionalRouteReplay;
        private boolean functionalMiningRouteInitialized;
        private boolean functionalMiningReturning;
        private boolean functionalMiningReturnComplete;
        private boolean functionalMiningReturnBlocked;
        private boolean resetMiningReturnProgressBaseline;
        private BlockPos functionalMiningEntrance;
        private boolean functionalNativeReturn;
        private long nativeReturnDeadline;
        private final Set<BlockPos> functionalMiningLightAttempts = new LinkedHashSet<>();
        private BlockPos lastFunctionalMiningLight;
        private boolean propertyCrossingTraversal;
        private boolean sawActiveProcess;
        private double completionRange;
        private final WaypointReplanPolicy.Session waypointReplans =
                new WaypointReplanPolicy.Session();
        private final VisiblePlayerPursuitPolicy.Session visiblePlayerPursuit =
                new VisiblePlayerPursuitPolicy.Session();
        private final HuntTargetPolicy.Session huntTargets = new HuntTargetPolicy.Session();
        private final LandHuntFrontierPolicy.Session huntFrontiers =
                new LandHuntFrontierPolicy.Session();
        private final VisiblePlayerPursuitPolicy.Session huntPursuit =
                new VisiblePlayerPursuitPolicy.Session();
        private boolean huntPursuitRouteMode;
        private String waitingDetail;
        private String blockedDetail;
        private String pendingCompletionDetail;
        private double pendingCompletionDistance;
        private int quantity;
        private int maximumMinedBlocks;
        private int verifiedMinedBlocks;
        private boolean accessToolRequired;
        private boolean miningEntranceRetreat;
        private final MiningAccessRecoveryPolicy.RetreatBudget miningRetreatBudget =
                new MiningAccessRecoveryPolicy.RetreatBudget();
        private BlockPos miningBreakCandidate;
        private String miningBreakCandidateBlock = "";
        private Set<String> expectedItemIds = Set.of();
        private int startingTargetItemCount;
        private boolean prospecting;
        private int prospectingY;
        private boolean prospectingAccessStarted;
        private boolean prospectingAccessComplete;
        private String[] miningBlocks = new String[0];
        private MiningPolicy.Plan miningPlan;
        private ResourceActuationSession resourceSession;
        private String resourceAreaName = "";
        private String resourceAreaFingerprint = "";
        private boolean completeWhenResourceExhausted;
        private boolean resourceExhausted;
        private ResourceActuationSession.CandidateScan<RawResourceCandidate> resourceCandidateScan;
        private String resourceCandidateScanDimension = "";
        private boolean resourceCandidateScanPending;
        private final List<ResourceActuationSession.Candidate> resourceCandidateScanRejections = new ArrayList<>();
        private final Set<ResourceActuationSession.Coordinate> rejectedResourceTargets =
                new LinkedHashSet<>();
        private Set<ResourceActuationSession.Coordinate> committedNaturalTreeTargets =
                Set.of();
        private final StableInteractionTargetSession<ResourceActuationSession.Coordinate>
                committedNaturalTreeAim = new StableInteractionTargetSession<>(
                Comparator.comparingInt(ResourceActuationSession.Coordinate::y)
                        .thenComparingInt(ResourceActuationSession.Coordinate::x)
                        .thenComparingInt(ResourceActuationSession.Coordinate::z));
        private String committedNaturalTreeBlockId = "";
        private long committedNaturalTreeStartedAt;
        private int committedNaturalTreeLastRemoved;
        private long committedNaturalTreeLastActivityAt;
        private long resourceReplantDeadlineAt;
        private long resourceDropDeadlineAt;
        private long resourceDropRouteRefreshAt;
        private BlockPos resourceApproachFeet;
        private BlockPos resourceRouteLastFeet;
        private long resourceRouteLastProgressAt;
        private int resourceCollectedBeforeDrop;
        private BlockPos resourceDropOrigin;
        private BlockPos resourceDropDestination;
        private boolean resourceDropEntityObserved;
        private boolean waitingForTool;
        private boolean creeperRetreating;
        private boolean creeperFollowing;
        private boolean tacticalInputsActive;
        private boolean tacticalShieldPrepared;
        private int tacticalPreviousSlot = -1;
        private boolean tacticalShieldChangedSlot;
        private boolean tacticalShieldAccepted;
        private long tacticalShieldUseGeneration;
        private long tacticalShieldLastStartAt = Long.MIN_VALUE;
        private long tacticalShieldStartDeadlineAt;
        private SealedShieldStartPolicy.State sealedShieldStartState =
                SealedShieldStartPolicy.State.STARTING;
        private long sealedShieldStartDeadlineAt;
        /** True only after sealed ROUTE hands movement to Baritone while retaining shield use. */
        private boolean sealedRouteCustody;
        private boolean combatHandPrepared;
        private boolean previousAutoTool = true;
        private boolean previousAllowInventory = true;
        private long completedWorkUnits;
        private boolean progressExpected = true;
        private final BlockBreakProgressTracker breakProgress = new BlockBreakProgressTracker();
        private final TraversalWatchdog traversalWatchdog =
                new TraversalWatchdog(
                        TRAVERSAL_STALL_MILLIS,
                        INITIAL_CALCULATION_STALL_MILLIS);
        private long lastWatchdogWorkUnits;
        private boolean conservativeTraversal;
        private boolean aquaticInputsForced;
        private final AquaticRouteCustodyPolicy.OwnershipSession aquaticPursuitCustody =
                new AquaticRouteCustodyPolicy.OwnershipSession();
        private final AquaticRouteCustodyPolicy.CoordinateRouteReissueSession
                aquaticCoordinateRouteReissue =
                new AquaticRouteCustodyPolicy.CoordinateRouteReissueSession();
        private long aquaticDirectSampleBaseline;
        private boolean aquaticDirectOriginKnown;
        private double aquaticDirectOriginX;
        private double aquaticDirectOriginY;
        private double aquaticDirectOriginZ;
        private boolean aquaticObservedMotion;
        private boolean aquaticResumePending;
        private String aquaticBlockedDetail;
        private final AquaticRouteEnvironmentPolicy.RediveCommitment
                aquaticRediveCommitment = new AquaticRouteEnvironmentPolicy.RediveCommitment();
        private final AquaticTravelPolicy.Session aquaticTravel =
                new AquaticTravelPolicy.Session();
        private final RangedRouteShieldPolicy.Session rangedRouteShield =
                new RangedRouteShieldPolicy.Session();
        private AquaticRoute committedAquaticRoute;
        private long aquaticRouteValidUntil;
        private boolean routeSprintForced;
        private long localCollisionSince;
        private double localCollisionAnchorX;
        private double localCollisionAnchorZ;
        private boolean localCollisionRecoveryActive;
        private long localCollisionRecoveryUntil;
        private boolean localCollisionRecoverLeft;
        private String localCollisionRoute = "";
        private final long generation;
        private String traversalBudgetKey;
        private int miningProcessStarts;
        private final Set<BlockPos> alternateEntrances = new LinkedHashSet<>();
        private BlockPos failedAccess;
        private BlockPos lastRecoveryPosition;
        private long lastRecoveryWork;
        private boolean alternateRouteRunning;
        private boolean loadedHarvestSourceAbsent;
        private final Set<BlockPos> portalApproaches = new HashSet<>();
        private boolean portalApproachRunning;
        private ManagedFarmIngressPolicy.Session managedFarmIngress;
        private boolean managedFarmIngressInputsForced;
        private boolean managedFarmIngressDiagnosticLogged;
        private String doorRestorationId = "";
        private long doorClearanceStartedAt;
        private boolean doorClearanceRunning;
        private long doorRestoreAuthorityStartedAt;
        private int miningProcessRestarts;
        private Set<String> protectedThrowawayItems = Set.of();

        private ActiveOperation(
                Goal goal,
                long startedAt,
                ControlLease controlLease,
                long generation,
                String traversalBudgetKey) {
            this.goal = goal;
            this.startedAt = startedAt;
            this.controlLease = controlLease;
            this.controlEpoch = controlLease.epoch();
            this.generation = generation;
            this.traversalBudgetKey = Objects.requireNonNull(traversalBudgetKey);
        }

        private boolean failed() {
            // NEXT_CALC_FAILED only means optional plan-ahead failed; the current path can remain
            // perfectly usable. Treat only a primary calculation failure as a recovery signal.
            return lastEvent == PathEvent.CALC_FAILED;
        }
    }

    public record Snapshot(
            String missionId,
            String operation,
            boolean pathing,
            String pathEvent,
            double estimatedTicksRemaining,
            String aquaticCustody,
            String aquaticMode,
            boolean aquaticOwner,
            int aquaticModeTicks,
            int aquaticStallTicks,
            int aquaticRecoveryAttempts,
            int observedSwimTicks,
            double maximumObservedAquaticSpeed,
            String aquaticReason,
            boolean aquaticHeadSubmerged,
            boolean aquaticSwimmingPose,
            int aquaticAir,
            int aquaticMaximumAir,
            double observedAquaticSpeed,
            int aquaticSurfaceTransitions,
            int aquaticRediveTransitions,
            int aquaticShoreTransitions,
            boolean aquaticSprintEligible,
            boolean movementSampleExact,
            String movementSampleDetail,
            long operationGeneration,
            long operationStartedAt,
            long completedWorkUnits,
            int miningProcessStarts,
            int miningProcessRestarts,
            String breakingTarget,
            String routeSignature,
            int trackedRouteSupports,
            int protectedRouteSupports,
            int dependentRouteSupports,
            long routeSupportPlacements,
            double routeSupportMaximumBodyRise,
            long routeSupportSafeReleases,
            long routeSupportDeniedBreaks,
            long routeSupportPlacementRollbacks,
            long routeSupportPrematureRemovals,
            long routeSupportCapacityTrips,
            List<String> protectedRouteSupportCoordinates,
            long doorPassageRevision,
            String doorPassagePhase,
            String doorPassageCoordinate,
            boolean doorPassageRestorationDue,
            long homeInteriorRevision,
            String homeInteriorOutcome,
            String homeInteriorDimension,
            int homeInteriorAnchorX,
            int homeInteriorAnchorY,
            int homeInteriorAnchorZ,
            int homeInteriorSampledBlocks,
            int homeInteriorRooms,
            int homeInteriorRoomToRoomPortals,
            int homeInteriorEgressPortals,
            String homeInteriorHomeRegion,
            long functionalRouteRevision,
            int functionalRouteCount,
            int functionalRouteRecordedCells,
            String functionalRouteId,
            String functionalRoutePurpose,
            String functionalRouteStatus,
            long functionalRouteEdgesIssued,
            long functionalRouteEdgesAccepted,
            long functionalRouteRevalidations,
            long functionalRouteFallbacks,
            String functionalRouteDetail,
            long resourceDescentInspections,
            long resourceDescentAuthorized,
            long resourceDescentVetoes,
            long resourceDescentVerticalShaftVetoes,
            int resourceDescentMaximumVerticalLoss,
            String resourceDescentState,
            String resourceDescentRejection,
            String resourceDescentRoute,
            String resourceDescentDetail,
            Set<String> protectedThrowawayItems,
            long combatStrikeSequence,
            List<CombatAttackJournal.Event> combatAttackEvents) {
        public Snapshot {
            protectedRouteSupportCoordinates = List.copyOf(protectedRouteSupportCoordinates);
            protectedThrowawayItems = Set.copyOf(protectedThrowawayItems);
            combatAttackEvents = List.copyOf(combatAttackEvents);
        }
    }

    private record DoorPassageOutcome(boolean hold, boolean blocked, String detail) {
        private DoorPassageOutcome {
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    private record TrackedWaypoint(
            String player,
            boolean online,
            double x,
            double y,
            double z,
            String dimension,
            long timestamp) {
    }

    private record RouteSupportRecovery(
            RouteSupportCustody.Coordinate rejectedCoordinate,
            RouteSupportCustody.Coordinate catchCoordinate,
            String operationId,
            int attempt,
            int maximumAttempts,
            long startedAtClientTick) {
        private RouteSupportRecovery {
            Objects.requireNonNull(rejectedCoordinate, "rejectedCoordinate");
            Objects.requireNonNull(catchCoordinate, "catchCoordinate");
            operationId = Objects.requireNonNullElse(operationId, "").trim();
            if (attempt < 1 || maximumAttempts < attempt || startedAtClientTick < 0L) {
                throw new IllegalArgumentException("invalid route-support recovery identity");
            }
        }
    }

    private record LandPatch(
            int y,
            boolean centerWalkable,
            double landFraction,
            double loadedFraction) {
    }

    private record AquaticRoute(
            double destinationX,
            double destinationY,
            double destinationZ,
            double dx,
            double dz,
            double horizontalDistance,
            double verticalDistance,
            boolean destinationDry,
            boolean directPursuit) {
    }

    private record ManagedFarmIngressCandidate(
            BlockPos gate,
            BlockPos inside,
            MinecraftActuatorGateway.PortalState portal,
            int axisX,
            int axisZ,
            int outsideSign) {
    }

    private record SuspendedAquaticRoute(
            Goal goal,
            double destinationX,
            double destinationY,
            double destinationZ,
            boolean destinationDry,
            boolean directPursuit,
            String dimension,
            long expiresAtMillis) {
    }

    private record MovementAuthority(
            ExecutionKernel authority,
            ControlLease parent,
            ActionLease action,
            long clientTick,
            long boundAtMillis) {
    }
}
