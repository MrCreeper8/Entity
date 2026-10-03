package dev.entity.client.control;

import baritone.api.IBaritone;
import baritone.api.utils.input.Input;
import dev.entity.client.autonomy.AtomicBlockBreakController;
import dev.entity.client.autonomy.ClientInventoryController;
import dev.entity.client.autonomy.policy.CombatLoadoutCoordinator;
import dev.entity.client.baritone.AquaticTravelPolicy;
import dev.entity.client.baritone.AquaticRouteEnvironmentPolicy;
import dev.entity.client.technique.InventoryTechniquePolicy;
import dev.entity.client.technique.MinecraftFallTechniqueObserver;
import dev.entity.client.technique.WaterClutchSession;
import dev.entity.core.control.BodyArbiter;
import dev.entity.core.control.BodyChannel;
import dev.entity.core.control.ControlLease;
import dev.entity.core.survival.SurvivalAction;
import dev.entity.core.stewardship.EntityDispositionPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.world.RaycastContext;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** The only non-Baritone writer of movement keys. Every write requires a live lease. */
public final class DirectBodyController {
    private static final String FOOD_USE_OPERATION = "survival:eat";
    private static final String RETREAT_SHIELD_USE_OPERATION = "protection:retreat-shield";
    private static final String FIRE_DEFENSE_SHIELD_USE_OPERATION =
            "survival:fire-defense-shield";
    private static final GridEscapePlanner.Limits WATER_LIMITS =
            new GridEscapePlanner.Limits(12, 16, 4, 8_192);
    private static final LavaEscapePlanner.Limits LAVA_LIMITS =
            new LavaEscapePlanner.Limits(10, 6, 3, 4_096);
    private static final FireEscapePlanner.Limits FIRE_LIMITS =
            new FireEscapePlanner.Limits(10, 2, 2, 4_096);
    private static final long WATER_REPLAN_MILLIS = 1_000;
    private static final long LAVA_REPLAN_MILLIS = 350;
    private static final long LAVA_WAYPOINT_STALL_MILLIS = 450;
    private static final long LAVA_BUCKET_RETRY_MILLIS = 750;
    private static final long FIRE_REPLAN_MILLIS = 350;
    private static final long FIRE_WAYPOINT_STALL_MILLIS = 650;
    private static final double FIRE_WAYPOINT_REACHED_SQUARED = 0.45 * 0.45;
    private static final float LAVA_CRITICAL_HEALTH_FRACTION = 0.40F;
    private static final long SUFFOCATION_OPEN_PROGRESS_MILLIS = 400L;
    private static final double SUFFOCATION_OPEN_PROGRESS_BLOCKS = 0.20;
    private static final long SUFFOCATION_BLOCK_DEADLINE_MIN_MILLIS = 500L;
    private static final long SUFFOCATION_BLOCK_DEADLINE_MAX_MILLIS = 8_000L;
    private static final long SUFFOCATION_BREAK_RETRY_MILLIS = 100L;
    private static final double LAVA_WAYPOINT_REACHED_SQUARED = 0.55 * 0.55;
    private static final double WATER_WAYPOINT_REACHED_SQUARED = 0.30 * 0.30;
    private static final long BREATHING_POCKET_REENTRY_MILLIS = 15_000L;
    private static final long BREATHING_POCKET_BLACKLIST_MILLIS = 45_000L;
    private static final int BREATHING_POCKET_MEMORY_LIMIT = 8;
    private static final int LAST_RESORT_AIR_LEVEL = 60;
    private static final int AIR_TICKS_PER_WATER_ROUTE_STEP = 8;
    private static final long AIR_ROUTE_ESTIMATE_CACHE_MILLIS = 250L;
    private static final long FOOD_RECOVERY_RETRY_COOLDOWN_MILLIS = 5_000L;
    private static final long FOOD_COMPLETION_FENCE_MILLIS = 10_000L;
    private static final long RETREAT_SHIELD_START_TIMEOUT_MILLIS = 2_500L;
    private static final long RETREAT_SHIELD_REISSUE_MILLIS = 750L;
    private static final double RETREAT_ATTACK_REACH_SQUARED = 3.0 * 3.0;
    private static final double FIRE_DEFENSE_ATTACK_REACH_SQUARED = 3.2 * 3.2;
    private static final long RETREAT_PROGRESS_TIMEOUT_MILLIS = 1_500L;
    private static final double RETREAT_PROGRESS_DISTANCE = 0.85;
    private static final double RETREAT_THREAT_DISTANCE_GAIN = 0.35;
    private static final int MAX_RETREAT_ROUTE_FAILURES = 3;
    private static final int MAX_FAILED_RETREAT_ROUTES = 64;
    private static final double MIN_REARWARD_RETREAT_DETOUR_DISTANCE = 6.0;
    private static final long RETREAT_COVER_HOLD_MILLIS = 2_000L;
    private static final float RESERVED_FOOD_REARM_HEALTH_LOSS = 4.0F;
    private static final int RESERVED_FOOD_REARM_HUNGER_LOSS = 4;
    private static final int MAX_RETREAT_COUNTERATTACK_EVENTS = 16;

    private final MinecraftClient client;
    private final IBaritone baritone;
    private final BodyArbiter arbiter;
    private final Logger logger;
    private final ClientInventoryController survivalInventory;
    private final AtomicBlockBreakController emergencyBreak;
    private final MinecraftActuatorGateway actuators;
    private final MinecraftEntityAttackGateway entityAttacks;
    private final MinecraftMovementGateway movement;

    private long appliedEpoch = -1;
    private SurvivalAction activeSurvivalAction = SurvivalAction.NONE;

    private int clutchPreviousSlot = -1;
    private int clutchBucketSlot = -1;
    private float clutchPreviousPitch;
    private float clutchPreviousYaw;
    private boolean clutchPrepared;
    private Hand clutchHand;
    private BlockPos clutchSupport;
    private BlockPos clutchPlacement;
    private BlockHitResult clutchHit;
    private String fallTechniqueDetail = "no active fall technique";
    private String lastFallTechniqueSelection = "none";
    private String lastFallTechniqueReason = "no dangerous fall observed";
    private String lastClutchPlacement = "";
    private long existingWaterSteeringTicks;
    private final WaterClutchSession clutchSession = new WaterClutchSession();
    private final MinecraftFallTechniqueObserver fallTechniques;

    private final ArrayDeque<GridEscapePlanner.Point> waterPath = new ArrayDeque<>();
    private final HashSet<GridEscapePlanner.Point> failedWaterWaypoints = new HashSet<>();
    private final BreathingPocketMemory breathingPocketMemory = new BreathingPocketMemory(
            BREATHING_POCKET_MEMORY_LIMIT,
            BREATHING_POCKET_REENTRY_MILLIS,
            BREATHING_POCKET_BLACKLIST_MILLIS,
            4,
            2,
            2,
            1);
    private GridEscapePlanner.Point waterProgressTarget;
    private double bestWaterTargetDistance = Double.POSITIVE_INFINITY;
    private long lastWaterProgressAt;
    private long lastWaterPlanAt;
    private String waterPlanDetail = "searching for breathable space";
    private BlockPos airEstimateOrigin;
    private int cachedAirRouteTicks = -1;
    private long airRouteEstimatedAt = Long.MIN_VALUE;

    private final ArrayDeque<LavaEscapePlanner.Point> lavaPath = new ArrayDeque<>();
    private final HashSet<LavaEscapePlanner.Point> failedLavaWaypoints = new HashSet<>();
    private LavaEscapePlanner.Point lavaProgressTarget;
    private double bestLavaTargetDistance = Double.POSITIVE_INFINITY;
    private long lastLavaProgressAt;
    private long lastLavaPlanAt;
    private String lavaPlanDetail = "searching for a supported dry lava exit";
    private int lavaBucketPreviousSlot = -1;
    private int lavaBucketSlot = -1;
    private float lavaBucketPreviousPitch;
    private boolean lavaBucketPrepared;
    private Hand lavaBucketHand;
    private long lastLavaBucketAttemptAt;
    private long lavaCommandedAt;
    private long lavaCommandedClientTick = -1L;
    private String lavaCommandMode = "idle";
    private boolean lavaForwardCommanded;
    private boolean lavaJumpCommanded;

    private final ArrayDeque<FireEscapePlanner.Point> firePath = new ArrayDeque<>();
    private final HashSet<FireEscapePlanner.Point> failedFireWaypoints = new HashSet<>();
    private FireEscapePlanner.Point fireProgressTarget;
    private double bestFireTargetDistance = Double.POSITIVE_INFINITY;
    private long lastFireProgressAt;
    private long lastFirePlanAt;
    private long fireCommandedAt;
    private long fireCommandedClientTick = -1L;
    private String fireCommandMode = "idle";
    private boolean fireForwardCommanded;
    private boolean fireJumpCommanded;
    private String firePlanDetail = "searching for extinguishing water and the safest dry fallback";
    private FireEscapePlanner.TerminalKind fireTerminalKind =
            FireEscapePlanner.TerminalKind.NONE;
    private String fireDefenseMode = "none";
    private String fireDefenseTargetUuid = "";
    private String fireDefenseTargetType = "";

    private BlockPos breachBlock;
    private int breachPreviousSlot = -1;
    private boolean breachPrepared;
    private long breachDeadlineAt;
    private BlockPos timedOutBreachBlock;

    private final HashSet<BlockPos> failedSuffocationBlocks = new HashSet<>();
    private final EnumSet<Direction> failedSuffocationDirections =
            EnumSet.noneOf(Direction.class);
    private BlockPos suffocationBlock;
    private Direction suffocationDirection;
    private double suffocationDirectionStartX;
    private double suffocationDirectionStartZ;
    private long suffocationDirectionProgressAt;
    private int suffocationPreviousSlot = -1;
    private long suffocationDeadlineAt;
    private final SuffocationBreakRetry suffocationBreakRetry =
            new SuffocationBreakRetry(SUFFOCATION_BREAK_RETRY_MILLIS);
    private String suffocationPlanDetail = "searching for immediate open space";

    private int foodPreviousSlot = -1;
    private boolean foodPrepared;
    private boolean foodChoicePrepared;
    private boolean foodChoiceConsumesReservation;
    private final EmergencyFoodReservationGate emergencyFoodReservation =
            new EmergencyFoodReservationGate(
                    RESERVED_FOOD_REARM_HEALTH_LOSS,
                    RESERVED_FOOD_REARM_HUNGER_LOSS);
    private Hand foodHand;
    private int foodSlot = -1;
    private long foodUseGeneration;
    private final HeldUseSession foodUseSession = new HeldUseSession();
    private final FoodCompletionFence foodCompletionFence =
            new FoodCompletionFence(FOOD_COMPLETION_FENCE_MILLIS);
    private final FoodRecoveryGate foodRecoveryGate =
            new FoodRecoveryGate(FOOD_RECOVERY_RETRY_COOLDOWN_MILLIS);
    private volatile Map<String, Integer> foodReservations = Map.of();
    private boolean retreatShieldPrepared;
    private Hand retreatShieldHand;
    private int retreatShieldSlot = -1;
    private int retreatShieldPreviousSlot = -1;
    private long retreatShieldUseGeneration;
    private String retreatShieldUseOperation = "";
    private final ShieldHoldSession retreatShieldSession = new ShieldHoldSession(
            RETREAT_SHIELD_START_TIMEOUT_MILLIS,
            RETREAT_SHIELD_REISSUE_MILLIS);
    private final RetreatProgressTracker retreatProgress = new RetreatProgressTracker(
            RETREAT_PROGRESS_TIMEOUT_MILLIS,
            RETREAT_PROGRESS_DISTANCE,
            RETREAT_THREAT_DISTANCE_GAIN,
            MAX_RETREAT_ROUTE_FAILURES);
    private final LinkedHashSet<String> failedRetreatRoutes = new LinkedHashSet<>();
    private String retreatEpisodeKey = "";
    private RetreatRoute committedRetreatRoute;
    private long retreatCoverStartedAt;
    private boolean retreatCoverHoldActive;
    private long retreatCounterattackSequence;
    private final ArrayDeque<RetreatCounterattackEvent> retreatCounterattacks =
            new ArrayDeque<>();
    private RetreatIntent aquaticDefenseIntent = RetreatIntent.NONE;
    private BlockPos shoreEscapeGoal;
    private boolean aquaticEscapeActive;
    private long lastShorePlanAt;
    private boolean waterPathToShore;
    private boolean movementKeysOwned;
    private final AquaticTravelPolicy.Session waterLocomotion =
            new AquaticTravelPolicy.Session();
    private MovementAuthority movementAuthority;

    public DirectBodyController(
            MinecraftClient client,
            IBaritone baritone,
            BodyArbiter arbiter,
            Logger logger) {
        this(client, baritone, arbiter, logger, MinecraftActuatorGateway.shared(client));
    }

    public DirectBodyController(
            MinecraftClient client,
            IBaritone baritone,
            BodyArbiter arbiter,
            Logger logger,
            MinecraftActuatorGateway actuators) {
        this.client = Objects.requireNonNull(client, "client");
        this.baritone = Objects.requireNonNull(baritone, "baritone");
        this.arbiter = Objects.requireNonNull(arbiter, "arbiter");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.survivalInventory = new ClientInventoryController(client);
        this.actuators = Objects.requireNonNull(actuators, "actuators");
        this.fallTechniques = new MinecraftFallTechniqueObserver(client, actuators);
        this.entityAttacks = MinecraftEntityAttackGateway.shared(client);
        this.movement = MinecraftMovementGateway.shared(client);
        this.emergencyBreak = new AtomicBlockBreakController(
                client, this.actuators);
    }

    /** Refreshes the exact survival/protection action capability for this client tick. */
    public void bindMovementAction(
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

    public SurvivalExecution executeSurvival(ControlLease lease, SurvivalAction action) {
        return executeSurvival(lease, action, null);
    }

    /**
     * Executes one survival frame with an optional, already-authenticated
     * attacker for fire defense or moving while eating. The attacker is an
     * argument rather than retained policy state: the runtime must re-prove it
     * from the current observation on every tick, while this controller keeps
     * the single survival capability that already owns every body channel.
     */
    public SurvivalExecution executeSurvival(
            ControlLease lease,
            SurvivalAction action,
            Entity activeThreat) {
        long now = System.currentTimeMillis();
        if (!arbiter.isValid(lease, now)) {
            clear("stale survival lease");
            return SurvivalExecution.STALE_LEASE;
        }
        if (activeSurvivalAction != action) transitionTo(action);
        appliedEpoch = lease.epoch();
        return switch (action) {
            case SURFACE_FOR_AIR -> executeWaterEscape(now);
            case ESCAPE_LAVA -> executeLavaEscape(now);
            case ESCAPE_SUFFOCATION -> executeSuffocationEscape(now);
            case EXTINGUISH_FIRE -> executeFireEscape(now, activeThreat);
            case RECOVER_HEALTH -> {
                SurvivalExecution eating = executeEating();
                if (eating == SurvivalExecution.EATING) continueEatingEscape(activeThreat);
                yield eating;
            }
            case FALL_CLUTCH -> executeFallClutch();
            case NONE -> {
                clear("survival cleared");
                yield SurvivalExecution.ACTIVE;
            }
        };
    }

    /**
     * Escapes a solid collision before any eating, combat, or navigation wait.
     * A verified adjacent body cell is preferred because movement is faster
     * than mining. If the body is sealed, one intersecting breakable block and
     * its tool/operation identity remain pinned until world confirmation or a
     * bounded failure; the target is never changed merely because the camera
     * or crosshair moved.
     */
    private SurvivalExecution executeSuffocationEscape(long now) {
        beginDirectMovementFrame();
        var player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            resetSuffocationState();
            return SurvivalExecution.SUFFOCATION_NO_ESCAPE;
        }
        if (!player.isInsideWall()) {
            resetSuffocationState();
            suffocationPlanDetail = "world state confirms Entity's body is clear";
            return SurvivalExecution.SUFFOCATION_ESCAPED;
        }

        Direction open = findImmediateSuffocationExit(player.getBlockPos());
        while (open != null && suffocationOpenDirectionStalled(open, now)) {
            failedSuffocationDirections.add(open);
            suffocationDirection = null;
            suffocationDirectionProgressAt = 0L;
            open = findImmediateSuffocationExit(player.getBlockPos());
        }
        if (open != null) {
            abandonSuffocationBlock(false);
            faceToward(Vec3d.ofCenter(player.getBlockPos().offset(open)));
            commandMovement(true, false, true,
                    player.horizontalCollision || player.isOnGround(), false);
            suffocationPlanDetail = "moving into a verified adjacent open body cell";
            return SurvivalExecution.SUFFOCATION_MOVING;
        }

        if (suffocationBlock != null) {
            BlockState pinned = client.world.getBlockState(suffocationBlock);
            if (pinned.getCollisionShape(client.world, suffocationBlock).isEmpty()) {
                abandonSuffocationBlock(false);
            } else if (now >= suffocationDeadlineAt) {
                abandonSuffocationBlock(true);
            }
        }
        if (suffocationBlock == null && !prepareSuffocationBlock(now)) {
            // Keep pushing upward instead of entering another motionless hold.
            // This can leave a transient gravel/sand overlap even when every
            // intersecting block is protected or unbreakable.
            commandMovement(false, false, false, true, false);
            suffocationPlanDetail = "no loaded breakable collision is available; pushing toward open space";
            return SurvivalExecution.SUFFOCATION_NO_ESCAPE;
        }

        BlockPos target = suffocationBlock;
        Vec3d center = Vec3d.ofCenter(target);
        Vec3d eye = player.getEyePos();
        faceToward(center);
        if (suffocationBreakRetry.waiting(now)) {
            commandMovement(false, false, false, true, false);
            suffocationPlanDetail = "retrying the same intersecting escape block after transient break failure "
                    + suffocationBreakRetry.failures() + " in "
                    + suffocationBreakRetry.remainingMillis(now) + "ms";
            return SurvivalExecution.SUFFOCATION_BREAKING;
        }
        if (suffocationBreakRetry.failures() > 0) {
            BlockState pinned = client.world.getBlockState(target);
            int toolSlot = fastestHotbarTool(pinned);
            if (toolSlot >= 0) selectHotbarSlotNow(toolSlot);
        }
        Direction side = Direction.getFacing(
                (float) (eye.x - center.x),
                (float) (eye.y - center.y),
                (float) (eye.z - center.z));
        Vec3d hitPos = center.add(
                side.getOffsetX() * 0.5,
                side.getOffsetY() * 0.5,
                side.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, side, target, true);
        commandMovement(false, false, false, true, false);
        AtomicBlockBreakController.Result breaking = emergencyBreak.tick(
                "survival:suffocation:" + currentDimension() + ':' + target.asLong(),
                target,
                hit,
                now);
        return switch (breaking.state()) {
            case WAITING, STARTED, CONTINUE, RESTART_REQUIRED -> {
                suffocationPlanDetail = "breaking the pinned solid block intersecting Entity's body";
                yield SurvivalExecution.SUFFOCATION_BREAKING;
            }
            case COMPLETE -> {
                abandonSuffocationBlock(false);
                suffocationPlanDetail = "the pinned collision cleared; moving out before resuming work";
                yield SurvivalExecution.SUFFOCATION_MOVING;
            }
            case TOOL_PREEMPTED, OUT_OF_REACH, SIGHTLINE_LOST,
                    ACTION_REJECTED, SERVER_REJECTED, STALLED -> {
                String detail = breaking.detail();
                SuffocationFailureDisposition disposition = suffocationFailureDisposition(
                        breaking.state(), now >= suffocationDeadlineAt);
                if (disposition == SuffocationFailureDisposition.ABANDON_BLOCK) {
                    abandonSuffocationBlock(true);
                    suffocationPlanDetail = breaking.state()
                            == AtomicBlockBreakController.State.SERVER_REJECTED
                            ? "server permanently rejected the pinned escape block: " + detail
                            : "the health-bounded escape deadline expired: " + detail;
                    yield SurvivalExecution.SUFFOCATION_NO_ESCAPE;
                }
                emergencyBreak.cancel();
                suffocationBreakRetry.recordTransient(
                        breaking.state(), now, suffocationDeadlineAt);
                suffocationPlanDetail = "transient break failure; retaining the same escape block: "
                        + detail;
                yield SurvivalExecution.SUFFOCATION_BREAKING;
            }
        };
    }

    private Direction findImmediateSuffocationExit(BlockPos origin) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (failedSuffocationDirections.contains(direction)) continue;
            BlockPos feet = origin.offset(direction);
            BlockPos head = feet.up();
            BlockPos below = feet.down();
            if (!loaded(feet) || !loaded(head) || !loaded(below)) continue;
            BlockState feetState = client.world.getBlockState(feet);
            BlockState headState = client.world.getBlockState(head);
            BlockState support = client.world.getBlockState(below);
            if (!feetState.getCollisionShape(client.world, feet).isEmpty()
                    || !headState.getCollisionShape(client.world, head).isEmpty()
                    || lavaFluid(feetState) || lavaFluid(headState)
                    || dangerousLavaExitCell(feet, feetState)
                    || dangerousLavaExitCell(head, headState)) continue;
            boolean fluidSupported = feetState.getFluidState().isIn(FluidTags.WATER)
                    || headState.getFluidState().isIn(FluidTags.WATER);
            boolean solidSupported = !support.getCollisionShape(client.world, below).isEmpty()
                    && !lavaFluid(support)
                    && !dangerousLavaExitCell(below, support);
            if (fluidSupported || solidSupported) return direction;
        }
        return null;
    }

    private boolean suffocationOpenDirectionStalled(Direction direction, long now) {
        var player = client.player;
        if (player == null) return true;
        if (direction != suffocationDirection) {
            suffocationDirection = direction;
            suffocationDirectionStartX = player.getX();
            suffocationDirectionStartZ = player.getZ();
            suffocationDirectionProgressAt = now;
            return false;
        }
        double projection = (player.getX() - suffocationDirectionStartX)
                * direction.getOffsetX()
                + (player.getZ() - suffocationDirectionStartZ)
                * direction.getOffsetZ();
        if (projection >= SUFFOCATION_OPEN_PROGRESS_BLOCKS) {
            suffocationDirectionStartX = player.getX();
            suffocationDirectionStartZ = player.getZ();
            suffocationDirectionProgressAt = now;
            return false;
        }
        return now - suffocationDirectionProgressAt >= SUFFOCATION_OPEN_PROGRESS_MILLIS;
    }

    private boolean prepareSuffocationBlock(long now) {
        var player = client.player;
        if (player == null || client.world == null) return false;
        var box = player.getBoundingBox().contract(0.001);
        BlockPos best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos mutable : BlockPos.iterate(
                (int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
                (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            BlockPos candidate = mutable.toImmutable();
            if (!loaded(candidate) || failedSuffocationBlocks.contains(candidate)) continue;
            BlockState state = client.world.getBlockState(candidate);
            if (state.getCollisionShape(client.world, candidate).isEmpty()
                    || state.getHardness(client.world, candidate) < 0.0F) continue;
            double cost = suffocationBreakCost(state, candidate);
            double distance = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (cost < bestCost || (cost == bestCost && distance < bestDistance)) {
                best = candidate;
                bestCost = cost;
                bestDistance = distance;
            }
        }
        if (best == null) return false;

        BlockState state = client.world.getBlockState(best);
        suffocationBlock = best;
        suffocationDirection = null;
        suffocationPreviousSlot = player.getInventory().getSelectedSlot();
        int toolSlot = fastestHotbarTool(state);
        if (toolSlot >= 0) selectHotbarSlotNow(toolSlot);
        float perTick = state.calcBlockBreakingDelta(player, client.world, best);
        if (!(perTick > 0.0F) || !Float.isFinite(perTick)) {
            abandonSuffocationBlock(true);
            return false;
        }
        long expected = (long) Math.ceil(1.0D / perTick) * 50L;
        long healthBudget = Math.max(
                SUFFOCATION_BLOCK_DEADLINE_MIN_MILLIS,
                (long) Math.floor(Math.max(1.0F, player.getHealth() - 1.0F) * 450.0F));
        long deadline = Math.max(
                SUFFOCATION_BLOCK_DEADLINE_MIN_MILLIS,
                Math.min(expected + 1_000L, healthBudget));
        suffocationDeadlineAt = now + Math.min(
                SUFFOCATION_BLOCK_DEADLINE_MAX_MILLIS, deadline);
        return true;
    }

    private double suffocationBreakCost(BlockState state, BlockPos position) {
        var player = client.player;
        if (player == null) return Double.POSITIVE_INFINITY;
        float bestSpeed = 1.0F;
        boolean suitable = false;
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty() || !stack.isSuitableFor(state)
                    || remainingDurability(stack) <= 1) continue;
            suitable = true;
            bestSpeed = Math.max(bestSpeed, stack.getMiningSpeedMultiplier(state));
        }
        double hardness = Math.max(0.001, state.getHardness(client.world, position));
        double requiredToolPenalty = state.isToolRequired() && !suitable ? 1_000.0 : 0.0;
        return requiredToolPenalty + hardness / Math.max(0.001F, bestSpeed);
    }

    private boolean loaded(BlockPos pos) {
        return client.world != null
                && client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private void abandonSuffocationBlock(boolean blacklist) {
        BlockPos prior = suffocationBlock;
        emergencyBreak.cancel();
        if (blacklist && prior != null) failedSuffocationBlocks.add(prior);
        if (client.player != null && suffocationPreviousSlot >= 0) {
            selectHotbarSlotNow(suffocationPreviousSlot);
        }
        suffocationBlock = null;
        suffocationPreviousSlot = -1;
        suffocationDeadlineAt = 0L;
        suffocationBreakRetry.reset();
    }

    private void resetSuffocationState() {
        abandonSuffocationBlock(false);
        failedSuffocationBlocks.clear();
        failedSuffocationDirections.clear();
        suffocationDirection = null;
        suffocationDirectionProgressAt = 0L;
        suffocationPlanDetail = "searching for immediate open space";
    }

    public SurvivalExecution executeRetreat(ControlLease lease, Entity threat) {
        if (client.player == null || threat == null) {
            return executeRetreat(lease, threat, RetreatIntent.NONE);
        }
        Vec3d away = client.player.getPos().subtract(threat.getPos());
        return executeRetreat(
                lease,
                threat,
                new RetreatIntent(threat.getUuidAsString(), away.x, away.z));
    }

    /** Terminal defense after a proven route failure. Never starts another movement attempt. */
    public SurvivalExecution executeStationaryDefense(ControlLease lease, Entity threat) {
        long now = System.currentTimeMillis();
        if (!arbiter.isValid(lease, now) || client.player == null || threat == null) {
            clear("stale stationary defense");
            return SurvivalExecution.STALE_LEASE;
        }
        appliedEpoch = lease.epoch();
        beginDirectMovementFrame();
        commandMovement(false, false, false, false, false);
        Entity attacker = nearestRetreatCounterattackTarget(threat);
        float cooldown = client.player.getAttackCooldownProgress(0.5F);
        if (attacker != null && cooldown >= 0.9F) {
            stopRetreatShield();
            faceToward(attacker.getEyePos());
            issueRetreatCounterattack(
                    lease, attacker, now, "exhausted-route-stationary-defense");
        } else if (findRetreatShield() != null) {
            holdRetreatShield(threat);
        } else {
            stopRetreatShield();
            faceToward(threat.getEyePos());
        }
        return SurvivalExecution.RETREAT_NO_ROUTE;
    }

    /**
     * Executes one protection-episode retreat. The episode identity is
     * intentionally independent of the current primary mob UUID: the tactical
     * target may alternate while all loaded hostiles keep contributing to the
     * aggregate escape vector.
     */
    public SurvivalExecution executeRetreat(
            ControlLease lease,
            Entity threat,
            RetreatIntent intent) {
        long now = System.currentTimeMillis();
        if (!arbiter.isValid(lease, now) || client.player == null || threat == null
                || intent == null) {
            clear("stale protection retreat");
            return SurvivalExecution.STALE_LEASE;
        }
        appliedEpoch = lease.epoch();
        var player = client.player;
        beginDirectMovementFrame();
        retreatCoverHoldActive = false;
        String nextThreatKey = threat.getUuidAsString();
        String nextEpisodeKey = intent.episodeKey().isBlank()
                ? nextThreatKey
                : intent.episodeKey();
        if (!nextEpisodeKey.equals(retreatEpisodeKey)) {
            resetRetreatState();
            retreatEpisodeKey = nextEpisodeKey;
        }
        RetreatVector escape = resolveRetreatVector(
                intent,
                player.getX(), player.getZ(),
                threat.getX(), threat.getZ());
        if (shoreEscapeGoal != null && intent.immediateHazardAvoidance()
                && (shoreEscapeGoal.getX() + 0.5 - player.getX()) * escape.x()
                + (shoreEscapeGoal.getZ() + 0.5 - player.getZ()) * escape.z() < 0.0) {
            failedWaterWaypoints.add(point(shoreEscapeGoal));
            shoreEscapeGoal = null;
            waterPath.clear();
            waterPathToShore = false;
        }
        String threatType = EntityType.getId(threat.getType()).getPath();
        boolean ranged = threatType.equals("skeleton") || threatType.equals("stray")
                || threatType.equals("bogged") || threatType.equals("pillager")
                || threatType.equals("drowned");
        boolean lineOfSight = player.canSee(threat);
        double horizontalThreatDistance = horizontalDistance(player.getPos(), threat.getPos());
        RetreatProgress progress = retreatProgress.observe(
                retreatEpisodeKey,
                nextThreatKey,
                now,
                player.getX(),
                player.getY(),
                player.getZ(),
                horizontalThreatDistance,
                lineOfSight,
                player.isOnGround(),
                player.isTouchingWater() && !player.isInLava());
        if (progress == RetreatProgress.STALLED) {
            blacklistCommittedRetreatRoute();
            committedRetreatRoute = null;
            if (shoreEscapeGoal != null) {
                failedWaterWaypoints.add(point(shoreEscapeGoal));
                shoreEscapeGoal = null;
                waterPath.clear();
                waterPathToShore = false;
            }
        }

        if ((player.isTouchingWater() || hasAquaticEscapeObjective()) && !player.isInLava()) {
            // Land route probes deliberately require an on-ground collision
            // sample. In water that used to produce route=null and BRACE on
            // every tick. Keep the same protection action lease, but translate
            // the aggregate escape intent through the atomic 3-D aquatic frame.
            committedRetreatRoute = null;
            AquaticTravelPolicy.Decision aquatic = driveAquaticRetreat(escape, now);
            // A newly found different shore route is a bounded geometric alternative. A failed
            // goal stays excluded; do not let the old point's stall erase the new escape frame.
            boolean sealedAquaticRetreat = shoreEscapeGoal == null
                    && mustAbortAquaticRetreat(progress, retreatProgress.exhausted());
            if (!sealedAquaticRetreat) stopRetreatShield();
            if (sealedAquaticRetreat) {
                releaseWaterLocomotion(MovementFrameActuator.Cleanup.CANCEL);
                // RETREAT_NO_ROUTE is still a defensive state. Leaving both the
                // aquatic frame and shield/attack actuator neutral here made a
                // low-health Entity float motionless while the hostile kept
                // attacking. Keep buoyancy and seal the cell with the same
                // cooldown-counterattack-or-shield fallback used on land.
                faceToward(threat.getEyePos());
                Entity counterattackTarget = nearestRetreatCounterattackTarget(threat);
                boolean liveReachableAttacker = counterattackTarget != null
                        && client.interactionManager != null;
                float cooldown = player.getAttackCooldownProgress(0.5F);
                RetreatFallback fallback = chooseRetreatFallback(
                        false,
                        false,
                        findRetreatShield() != null,
                        liveReachableAttacker,
                        cooldown);
                if (fallback == RetreatFallback.COUNTER_ATTACK) {
                    stopRetreatShield();
                    issueRetreatCounterattack(
                            lease, counterattackTarget, now, "aquatic-retreat-counterattack");
                } else if (fallback == RetreatFallback.HOLD_SHIELD) {
                    holdRetreatShield(threat);
                } else {
                    stopRetreatShield();
                }
                // Defensive use/attack must never erase the upward water input:
                // even a sealed aquatic retreat still owns buoyancy this frame.
                commandMovement(false, false, false, true, false);
                return SurvivalExecution.RETREATING;
            }
            if (aquatic.ownsMovement()) return SurvivalExecution.RETREATING;
            return SurvivalExecution.RETREATING;
        }
        releaseWaterLocomotion(MovementFrameActuator.Cleanup.CANCEL);

        boolean rangedCoverEligible = rangedCoverHoldAllowed(
                ranged,
                lineOfSight,
                findRetreatShield() != null,
                intent.allowRangedCoverHold());
        if (rangedCoverHoldWindowActive(
                rangedCoverEligible, retreatCoverStartedAt, now)) {
            // Broken sightline is actual cover only against one ranged threat.
            // A second eligible mob keeps the escape corridor moving instead
            // of letting skeleton cover become a spider/creeper waiting room.
            // This one bounded window lets the policy's release grace observe
            // real cover. Once consumed, the same hidden skeleton cannot pin
            // Entity there forever while walking around the obstacle.
            committedRetreatRoute = null;
            if (retreatCoverStartedAt == 0L) retreatCoverStartedAt = now;
            retreatCoverHoldActive = true;
            stopRetreatShield();
            commandMovement(false, false, false, false, false);
            return SurvivalExecution.RETREATING;
        }

        RetreatRoute route = committedRetreatRoute;
        if (route != null && immediateHazardRouteReplanRequired(
                intent.immediateHazardAvoidance(),
                route.dx(), route.dz(),
                escape.x(), escape.z())) {
            // A route was geometrically valid when selected, but the exact
            // creeper has since crossed its immediate reaction margin. Do not
            // wait to finish that stale cell if it now points into the hazard.
            committedRetreatRoute = null;
            route = null;
        }
        if (player.isOnGround()) {
            boolean segmentCompleted = route != null && retreatRouteSegmentCompleted(
                    route.originX(), route.originZ(), route.dx(), route.dz(),
                    route.landingBlockX(), route.landingBlockZ(),
                    player.getX(), player.getZ(),
                    player.getBlockX(), player.getBlockZ());
            if (segmentCompleted) {
                // Crossing a cell boundary is not necessarily new escape
                // geometry. At a natural corner the body can cross the same
                // boundary east/west forever. Forget rejected headings only
                // when the episode watchdog independently proves a novel
                // planar envelope, real separation, or newly reached cover.
                if (shouldClearFailedRetreatRoutes(true, progress)) {
                    failedRetreatRoutes.clear();
                }
                route = null;
            }
            if (route != null && retreatRoutePhysicallyBlocked(
                    player.horizontalCollision,
                    Math.hypot(player.getVelocity().x, player.getVelocity().z))) {
                // Collision is authoritative body geometry. Do not wait for a
                // one-second watchdog while repeatedly jumping against the
                // same corner; reject that exact route and try another heading
                // in this frame.
                rememberFailedRetreatRoute(route);
                route = null;
            }
            if (route == null) {
                // Keep one verified segment stable until it is crossed or
                // physically blocked. Re-running the five-heading probe every
                // grounded tick let target motion and fractional block edges
                // flip the body 90/180 degrees while a pursuer caught up.
                route = findRetreatRoute(
                        escape.x(), escape.z(), failedRetreatRoutes, intent.allowAscent(),
                        rearwardRetreatDetourAllowed(
                                intent.immediateHazardAvoidance(), horizontalThreatDistance));
            }
            committedRetreatRoute = route;
        } else if (route != null && !airborneRetreatMovementAllowed(
                route.kind(),
                player.getBlockX(),
                player.getBlockZ(),
                route.landingBlockX(),
                route.landingBlockZ())) {
            // A route proves one bounded body segment, not permission to keep
            // accelerating after support disappears. The production cave trace
            // crossed a valid shallow cell, retained forward+sprint in the air,
            // and sailed into a fourteen-block shaft. A drop therefore coasts
            // vertically with no new horizontal input; an unexpected airborne
            // LEVEL segment fails closed; a JUMP drives only until its already
            // verified landing column is reached.
            committedRetreatRoute = null;
            route = null;
        }
        boolean shieldAvailable = findRetreatShield() != null;
        boolean usableRetreatRoute = route != null && !retreatProgress.exhausted();
        Entity counterattackTarget = nearestRetreatCounterattackTarget(threat);
        boolean liveReachableAttacker = counterattackTarget != null
                && client.interactionManager != null;
        float attackCooldown = player.getAttackCooldownProgress(0.5F);
        RetreatFallback fallback = chooseRetreatFallback(
                usableRetreatRoute && route.kind() == RetreatRouteKind.LEVEL,
                usableRetreatRoute && route.kind() != RetreatRouteKind.LEVEL,
                shieldAvailable,
                liveReachableAttacker,
                attackCooldown);

        if (ranged && shieldAvailable && usableRetreatRoute) {
            holdRetreatShield(threat);
            // Backpedal only when the exact cell behind the shield is verified.
            // Otherwise keep the shield facing the projectile instead of walking
            // blindly into an unloaded edge or a drop.
            RetreatRoute safeBehindRoute = route;
            boolean safeBehind = safeBehindRoute != null && !retreatProgress.exhausted();
            if (safeBehind) {
                ShieldRetreatInput retreatInput = shieldRetreatInput(
                        player.getX() - threat.getX(),
                        player.getZ() - threat.getZ(),
                        safeBehindRoute.dx(),
                        safeBehindRoute.dz());
                commandMovement(
                        false,
                        retreatInput.backward(),
                        retreatInput.left(),
                        retreatInput.right(),
                        false,
                        retreatJumpCommanded(safeBehindRoute.kind()),
                        false);
            } else {
                commandMovement(false, false, false, false, false);
            }
            if (!safeBehind && progress == RetreatProgress.STALLED) {
                return SurvivalExecution.RETREAT_NO_ROUTE;
            }
            return SurvivalExecution.RETREATING;
        }
        if (fallback == RetreatFallback.MOVE_LEVEL
                || fallback == RetreatFallback.MOVE_VERTICAL) {
            stopRetreatShield();
            // A real player does not stop fleeing merely because a zombie has
            // entered arm's reach. Take the normal-cooldown counterstrike while
            // retaining the already-verified escape segment. Creepers and
            // neutral endermen are excluded by the candidate policy above.
            if (movingRetreatCounterattackAllowed(
                    usableRetreatRoute,
                    liveReachableAttacker,
                    attackCooldown)) {
                issueRetreatCounterattack(
                        lease, counterattackTarget, now, "moving-retreat-counterattack");
            }
            faceRetreatRoute(route);
            // Revalidate the next body cell every tick, but cross each accepted
            // level/step segment at real escape speed. Walking here let every
            // pursuer keep contact while the body covered otherwise safe open
            // ground. A verified one-block drop stays unsprinted so forward
            // momentum cannot outrun its landing proof.
            boolean sprint = route.sprintCorridorVerified() && retreatSprintAllowed(
                    player.getHungerManager().getFoodLevel(),
                    player.isUsingItem(),
                    route.kind());
            commandMovement(true, false, sprint,
                    retreatJumpCommanded(route.kind()), false);
            return SurvivalExecution.RETREATING;
        }
        if (fallback == RetreatFallback.HOLD_SHIELD) {
            holdRetreatShield(threat);
            commandMovement(false, false, false, false, false);
            if (!stationaryShieldHoldMayContinue(
                    progress,
                    retreatShieldSession.failed(),
                    retreatProgress.exhausted())) {
                return SurvivalExecution.RETREAT_NO_ROUTE;
            }
            return SurvivalExecution.RETREATING;
        }

        // A geometrically sealed retreat must still defend itself. Previously
        // this branch emitted no keys at all while health fell to zero. Counter-
        // attack only at normal melee reach/cooldown; otherwise stop issuing the
        // failed jump and let the committed interception fallback take over.
        faceToward(threat.getEyePos());
        if (fallback == RetreatFallback.COUNTER_ATTACK) {
            stopRetreatShield();
            issueRetreatCounterattack(
                    lease, counterattackTarget, now, "sealed-retreat-counterattack");
        } else {
            stopRetreatShield();
        }
        commandMovement(false, false, false, false, false);
        return SurvivalExecution.RETREAT_NO_ROUTE;
    }

    /**
     * True while a locally accepted food use can still reduce mission cargo.
     *
     * <p>This fence deliberately survives {@link #clear(String)}. Minecraft can
     * acknowledge use locally, let survival release its controls, and only
     * synchronize the consumed stack a few ticks later. A food mission must not
     * terminal-complete in that gap.</p>
     */
    public boolean foodConsumptionPending() {
        long now = monotonicMillis();
        var player = client.player;
        if (player == null) return foodCompletionFence.pending(now, 0, 0);
        String itemId = foodCompletionFence.itemId();
        int count = itemId.isBlank() ? 0 : inventoryCount(itemId);
        return foodCompletionFence.pending(
                now, count, player.getHungerManager().getFoodLevel());
    }

    public boolean hasConsumableFood() {
        var player = client.player;
        if (player == null) return false;
        if (foodRecoveryGate.blocks(
                monotonicMillis(),
                player.getHungerManager().getFoodLevel(),
                foodInventoryFingerprint(player))) {
            return false;
        }
        if (foodAvailability(player.getOffHandStack(), player).eligible()) return true;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (foodAvailability(inventory.getStack(slot), player).eligible()) return true;
        }
        return false;
    }

    /**
     * Supplies the inventory quantities committed to mission cargo or another
     * owner. Keys may be bare vanilla paths or full registry identifiers.
     * Copies are intentional: a mission replan cannot mutate survival policy
     * halfway through a client tick.
     */
    public void setFoodReservations(Map<String, Integer> reservations) {
        if (reservations == null || reservations.isEmpty()) {
            foodReservations = Map.of();
            return;
        }
        HashMap<String, Integer> normalized = new HashMap<>();
        reservations.forEach((itemId, count) -> {
            if (itemId == null || itemId.isBlank() || count == null || count <= 0) return;
            normalized.merge(normalizeItemId(itemId), count, Integer::sum);
        });
        foodReservations = Map.copyOf(normalized);
    }

    /**
     * Supplies an episode-scoped threat bias to the survival water owner. The
     * caller still has to bind the SURVIVAL action lease; this method stores no
     * movement/input authority and cannot itself actuate the player.
     */
    public void setAquaticDefenseIntent(RetreatIntent intent) {
        aquaticDefenseIntent = intent == null ? RetreatIntent.NONE : intent;
    }

    public void clearAquaticDefenseIntent() {
        aquaticDefenseIntent = RetreatIntent.NONE;
    }

    public boolean hasUsableOffhandWaterBucket() {
        return client.player != null && client.world != null
                && !client.world.getDimension().ultrawarm()
                && client.player.getOffHandStack().isOf(Items.WATER_BUCKET);
    }

    /**
     * Estimates the air budget of a verified, collision-free route from the player's eye to a
     * breathable cell.  Unknown is {@code -1}; callers then retain the configured conservative
     * drowning threshold.  The bounded search is cached briefly because platform observation runs
     * every client tick.
     */
    public int estimatedAirTicksToBreathableSpace(long nowMillis) {
        var player = client.player;
        if (player == null || client.world == null) return -1;
        if (!player.isSubmergedInWater()) return 0;

        BlockPos origin = BlockPos.ofFloored(player.getEyePos());
        if (origin.equals(airEstimateOrigin)
                && nowMillis >= airRouteEstimatedAt
                && nowMillis - airRouteEstimatedAt <= AIR_ROUTE_ESTIMATE_CACHE_MILLIS) {
            return cachedAirRouteTicks;
        }

        GridEscapePlanner.Result local = GridEscapePlanner.plan(
                point(origin), this::probeWaterCell, WATER_LIMITS);
        int steps = local.found() ? local.path().size() : -1;
        if (steps < 0) {
            // The local planner intentionally caps vertical search at sixteen blocks. Preserve
            // deep but open oceans by verifying the same straight-up fallback used by survival.
            for (int offset = 1; offset <= 64; offset++) {
                GridEscapePlanner.Cell cell = probeWaterCell(point(origin.up(offset)));
                if (cell == GridEscapePlanner.Cell.WATER) continue;
                if (cell == GridEscapePlanner.Cell.BREATHABLE) steps = offset;
                break;
            }
        }
        cachedAirRouteTicks = steps < 0
                ? -1
                : Math.min(player.getMaxAir(), Math.max(1, steps * AIR_TICKS_PER_WATER_ROUTE_STEP));
        airEstimateOrigin = origin.toImmutable();
        airRouteEstimatedAt = nowMillis;
        return cachedAirRouteTicks;
    }

    private SurvivalExecution executeWaterEscape(long now) {
        beginDirectMovementFrame();
        var player = client.player;
        if (player == null || client.world == null) {
            cancelBreach();
            releaseWaterLocomotion(MovementFrameActuator.Cleanup.CANCEL);
            return SurvivalExecution.WATER_NO_ROUTE;
        }
        String memoryScope = waterMemoryScope();
        GridEscapePlanner.Point currentEye = point(BlockPos.ofFloored(player.getEyePos()));

        if (hasAquaticEscapeObjective() && !player.isSubmergedInWater()) {
            cancelBreach();
            driveAquaticRetreat(new RetreatVector(
                    aquaticDefenseIntent.escapeX(), aquaticDefenseIntent.escapeZ()), now);
            return SurvivalExecution.WATER_PATHING;
        }

        if (!player.isSubmergedInWater()) {
            boolean airRecovered = player.getAir() >= Math.max(0, player.getMaxAir() - 20);
            breathingPocketMemory.observeBreathing(
                    memoryScope, currentEye, now, airRecovered);
            waterPath.clear();
            cancelBreach();
            waterPlanDetail = "breathing at the air pocket until air is restored ("
                    + player.getAir() + "/" + player.getMaxAir() + ")";
            driveWaterTarget(player.getEyePos().add(0.0, 0.75, 0.0), false, now);
            return SurvivalExecution.BREATHING_AT_SURFACE;
        }

        Vec3d eye = player.getEyePos();
        boolean repeatedPocket = breathingPocketMemory.observeSubmerged(
                memoryScope, currentEye, now);
        if (repeatedPocket) {
            waterPath.clear();
            waterProgressTarget = null;
            bestWaterTargetDistance = Double.POSITIVE_INFINITY;
            lastWaterPlanAt = 0;
            waterPlanDetail = "the recent breathing pocket led back underwater; seeking a different exit";
        }
        discardReachedWaterWaypoints(eye);
        boolean plannedNow = false;
        if (waterPath.isEmpty() && !breachPrepared) {
            if (now - lastWaterPlanAt < WATER_REPLAN_MILLIS) {
                return executeVerifiedUpwardFallback(eye, now);
            }
            planWaterEscape(BlockPos.ofFloored(eye), now);
            if (repeatedPocket) {
                waterPlanDetail = "avoiding the recently repeated breathing pocket; " + waterPlanDetail;
            }
            plannedNow = true;
        }
        discardReachedWaterWaypoints(eye);
        if (!waterPath.isEmpty()) {
            cancelBreach();
            GridEscapePlanner.Point target = waterPath.peekFirst();
            if (waterWaypointStalled(eye, target, now)) {
                failedWaterWaypoints.add(target);
                waterPath.clear();
                waterProgressTarget = null;
                bestWaterTargetDistance = Double.POSITIVE_INFINITY;
                lastWaterPlanAt = 0;
                waterPlanDetail = "water route made no forward progress; trying a different exit";
                return SurvivalExecution.WATER_SEARCHING;
            }
            GridEscapePlanner.Cell liveCell = probeWaterCell(target);
            if (liveCell == GridEscapePlanner.Cell.BLOCKED
                    || liveCell == GridEscapePlanner.Cell.HAZARD
                    || liveCell == GridEscapePlanner.Cell.UNLOADED) {
                waterPath.clear();
                lastWaterPlanAt = 0;
                waterPlanDetail = "water route changed; searching again";
                return SurvivalExecution.WATER_SEARCHING;
            }
            steerEyeToward(target, now);
            return SurvivalExecution.WATER_PATHING;
        }

        if (!breachPrepared && !plannedNow) return SurvivalExecution.WATER_SEARCHING;
        SurvivalExecution breach = attemptCeilingBreach(now);
        if (breach != SurvivalExecution.WATER_NO_ROUTE) {
            // Stay pressed against the selected ceiling cell while the real
            // attack key owns the crack; do not sink away between client ticks.
            driveWaterTarget(player.getEyePos().add(0.0, 1.0, 0.0), false, now);
            return breach;
        }

        if (player.getAir() <= LAST_RESORT_AIR_LEVEL
                && planLastResortBreathingRoute(BlockPos.ofFloored(eye))) {
            return SurvivalExecution.WATER_SEARCHING;
        }

        return executeVerifiedUpwardFallback(eye, now);
    }

    private SurvivalExecution executeVerifiedUpwardFallback(
            Vec3d eye,
            long now) {
        GridEscapePlanner.Point above = point(BlockPos.ofFloored(eye).up());
        GridEscapePlanner.Cell aboveCell = probePlanningWaterCell(above, now);
        if (aboveCell == GridEscapePlanner.Cell.WATER
                || aboveCell == GridEscapePlanner.Cell.BREATHABLE) {
            if (client.player == null) return SurvivalExecution.WATER_NO_ROUTE;
            driveWaterTarget(eye.add(0.0, 1.0, 0.0), false, now);
            return SurvivalExecution.WATER_SEARCHING;
        }
        waterPlanDetail = "no reachable air found in the local water search";
        // With no verified lateral route, holding ascent is safer than silently
        // sinking while the next bounded plan/breach decision is made.
        driveWaterTarget(eye.add(0.0, 1.0, 0.0), false, now);
        return SurvivalExecution.WATER_NO_ROUTE;
    }

    private void planWaterEscape(BlockPos startBlock, long now) {
        waterPathToShore = false;
        lastWaterPlanAt = now;
        waterPath.clear();

        GridEscapePlanner.Result result = GridEscapePlanner.plan(
                point(startBlock), candidate -> probePlanningWaterCell(candidate, now), WATER_LIMITS);
        if (result.found()) {
            waterPath.addAll(result.path());
            waterPlanDetail = "following the shortest local 3D route to breathable space ("
                    + result.path().size() + " steps)";
            return;
        }

        ArrayList<GridEscapePlanner.Point> straightUp = new ArrayList<>();
        for (int offset = 1; offset <= 64; offset++) {
            GridEscapePlanner.Point candidate = point(startBlock.up(offset));
            GridEscapePlanner.Cell cell = probePlanningWaterCell(candidate, now);
            if (cell == GridEscapePlanner.Cell.WATER) {
                straightUp.add(candidate);
                continue;
            }
            if (cell == GridEscapePlanner.Cell.BREATHABLE) {
                straightUp.add(candidate);
                waterPath.addAll(straightUp);
                waterPlanDetail = "swimming up an open water column to air";
                return;
            }
            break;
        }
        waterPlanDetail = "no connected local air pocket found after checking "
                + result.visitedCells() + " water cells";
    }

    private boolean planLastResortBreathingRoute(BlockPos startBlock) {
        GridEscapePlanner.Result result = GridEscapePlanner.plan(
                point(startBlock),
                candidate -> failedWaterWaypoints.contains(candidate)
                        ? GridEscapePlanner.Cell.BLOCKED
                        : probeWaterCell(candidate),
                WATER_LIMITS);
        if (!result.found()) return false;
        waterPath.clear();
        waterPath.addAll(result.path());
        waterPlanDetail = "no alternate exit remained with critical air; returning to the known pocket as a last resort";
        return true;
    }

    private GridEscapePlanner.Cell probePlanningWaterCell(
            GridEscapePlanner.Point point,
            long now) {
        if (failedWaterWaypoints.contains(point)) return GridEscapePlanner.Cell.BLOCKED;
        GridEscapePlanner.Cell actual = probeWaterCell(point);
        if (actual == GridEscapePlanner.Cell.BREATHABLE
                && breathingPocketMemory.isBlacklisted(waterMemoryScope(), point, now)) {
            return GridEscapePlanner.Cell.BLOCKED;
        }
        return actual;
    }

    private GridEscapePlanner.Cell probeWaterCell(GridEscapePlanner.Point point) {
        if (client.world == null) return GridEscapePlanner.Cell.UNLOADED;
        BlockPos pos = new BlockPos(point.x(), point.y(), point.z());
        if (pos.getY() < client.world.getBottomY() || pos.getY() > client.world.getTopYInclusive()) {
            return GridEscapePlanner.Cell.BLOCKED;
        }
        if (client.world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return GridEscapePlanner.Cell.UNLOADED;
        }
        BlockState state = client.world.getBlockState(pos);
        if (!state.getCollisionShape(client.world, pos).isEmpty()) {
            return GridEscapePlanner.Cell.BLOCKED;
        }
        if (state.getFluidState().isIn(FluidTags.LAVA)) return GridEscapePlanner.Cell.HAZARD;
        if (state.getFluidState().isIn(FluidTags.WATER)) return GridEscapePlanner.Cell.WATER;
        if (!state.getFluidState().isEmpty() || hazardousBreathingCell(pos, state)) {
            return GridEscapePlanner.Cell.HAZARD;
        }
        return GridEscapePlanner.Cell.BREATHABLE;
    }

    private boolean hazardousBreathingCell(BlockPos pos, BlockState state) {
        BlockState below = client.world.getBlockState(pos.down());
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.CACTUS)
                || below.isOf(Blocks.MAGMA_BLOCK) || below.isOf(Blocks.CAMPFIRE)
                || below.isOf(Blocks.SOUL_CAMPFIRE) || below.isOf(Blocks.CACTUS);
    }

    private void discardReachedWaterWaypoints(Vec3d eye) {
        while (!waterPath.isEmpty()) {
            GridEscapePlanner.Point next = waterPath.peekFirst();
            Vec3d center = new Vec3d(next.x() + 0.5, next.y() + 0.5, next.z() + 0.5);
            if (eye.squaredDistanceTo(center) > WATER_WAYPOINT_REACHED_SQUARED) break;
            if (probeWaterCell(next) == GridEscapePlanner.Cell.BREATHABLE
                    && !BlockPos.ofFloored(eye).equals(new BlockPos(next.x(), next.y(), next.z()))) {
                break;
            }
            waterPath.removeFirst();
        }
    }

    private boolean waterWaypointStalled(Vec3d eye, GridEscapePlanner.Point target, long now) {
        Vec3d center = new Vec3d(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);
        double distance = eye.distanceTo(center);
        if (!target.equals(waterProgressTarget)) {
            waterProgressTarget = target;
            bestWaterTargetDistance = distance;
            lastWaterProgressAt = now;
            return false;
        }
        if (distance <= bestWaterTargetDistance - 0.05) {
            bestWaterTargetDistance = distance;
            lastWaterProgressAt = now;
            return false;
        }
        return now - lastWaterProgressAt >= WATER_REPLAN_MILLIS;
    }

    private void steerEyeToward(GridEscapePlanner.Point target, long nowMillis) {
        boolean breathable = probeWaterCell(target) == GridEscapePlanner.Cell.BREATHABLE;
        driveWaterTarget(
                new Vec3d(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5),
                breathable,
                nowMillis);
    }

    /**
     * The single survival-water translator. The grid planner chooses a safe 3-D waypoint; this
     * shared aquatic controller chooses the pose transition, and the atomic gateway installs the
     * complete sampled input frame. It never writes {@code setSwimming} or physical key state.
     */
    private AquaticTravelPolicy.Decision driveWaterTarget(
            Vec3d target,
            boolean destinationDry,
            long nowMillis) {
        var player = client.player;
        if (player == null || client.world == null || target == null) {
            releaseWaterLocomotion(MovementFrameActuator.Cleanup.CANCEL);
            return AquaticTravelPolicy.Decision.idle(
                    AquaticTravelPolicy.Mode.IDLE, "no loaded water-control context");
        }
        Vec3d eye = player.getEyePos();
        target = defensiveSurfaceTarget(eye, target);
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.hypot(dx, dz);
        Vec3d velocity = player.getVelocity();
        AquaticRouteEnvironmentPolicy.DeepWaterEntry deepWaterEntry =
                verifiedAquaticEntry(dx, dz);
        boolean supportingFloor = AquaticRouteEnvironmentPolicy.supportingFloor(
                player.isOnGround());
        AquaticTravelPolicy.Decision decision = waterLocomotion.step(
                new AquaticTravelPolicy.Input(
                        player.isTouchingWater(),
                        player.isInLava(),
                        player.isSubmergedInWater(),
                        player.isSwimming(),
                        hasDeepWaterBelow(player.getBlockPos()),
                        true,
                        false,
                        movementAuthorityValid(nowMillis),
                        true,
                        false,
                        player.getAir(),
                        player.getMaxAir(),
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        Math.hypot(velocity.x, velocity.z),
                        horizontal,
                        dy,
                        destinationDry,
                        player.horizontalCollision,
                        hasWaterCeiling(player),
                        AquaticTravelPolicy.Objective.SURFACE_FOR_AIR,
                        false,
                        player.getHungerManager().getFoodLevel() > 6
                                || player.getAbilities().allowFlying,
                        supportingFloor,
                        deepWaterEntry.verified(),
                        AquaticRouteEnvironmentPolicy.RediveAssessment.notResuming()));
        if (!decision.ownsMovement()) {
            releaseWaterFrame(MovementFrameActuator.Cleanup.CANCEL);
            return decision;
        }
        double steeringX = dx;
        double steeringZ = dz;
        if (decision.mode() == AquaticTravelPolicy.Mode.STEP_TO_DEEP_WATER
                && deepWaterEntry.verified()
                && Math.hypot(deepWaterEntry.steeringX(), deepWaterEntry.steeringZ()) > 0.025) {
            steeringX = deepWaterEntry.steeringX();
            steeringZ = deepWaterEntry.steeringZ();
        }
        float yaw = Math.hypot(steeringX, steeringZ) > 0.025
                ? (float) (Math.toDegrees(Math.atan2(steeringZ, steeringX)) - 90.0
                + decision.yawOffsetDegrees())
                : player.getYaw();
        submitWaterFrame(new MovementFrame(
                decision.moveForward(), false, false, false,
                decision.jump(), decision.sneak(), decision.sprint(),
                yaw, decision.pitchDegrees()), nowMillis);
        return decision;
    }

    /**
     * Adds a bounded lateral component without changing the verified air
     * waypoint's height. Both the current-height and target-height cells must
     * remain water/breathable, so threat avoidance never steers through an
     * unverified wall merely to gain distance.
     */
    private Vec3d defensiveSurfaceTarget(Vec3d eye, Vec3d verifiedTarget) {
        SurfaceDefenseOffset offset = surfaceDefenseOffset(aquaticDefenseIntent, 0.65);
        if (!offset.active()) return verifiedTarget;
        Vec3d biased = verifiedTarget.add(offset.x(), 0.0, offset.z());
        BlockPos lateralAtEye = BlockPos.ofFloored(
                eye.x + offset.x(), eye.y, eye.z + offset.z());
        BlockPos lateralAtTarget = BlockPos.ofFloored(biased);
        if (!safeSurfaceDefenseCell(lateralAtEye)
                || !safeSurfaceDefenseCell(lateralAtTarget)) {
            return verifiedTarget;
        }
        return biased;
    }

    private boolean safeSurfaceDefenseCell(BlockPos position) {
        GridEscapePlanner.Cell cell = probeWaterCell(point(position));
        return cell == GridEscapePlanner.Cell.WATER
                || cell == GridEscapePlanner.Cell.BREATHABLE;
    }

    static SurfaceDefenseOffset surfaceDefenseOffset(
            RetreatIntent intent,
            double maximumDistance) {
        if (intent == null || maximumDistance <= 0.0
                || (!Double.isFinite(maximumDistance))) {
            return SurfaceDefenseOffset.NONE;
        }
        double length = Math.hypot(intent.escapeX(), intent.escapeZ());
        if (length < 0.001) return SurfaceDefenseOffset.NONE;
        return new SurfaceDefenseOffset(
                intent.escapeX() / length * maximumDistance,
                intent.escapeZ() / length * maximumDistance,
                true);
    }

    /**
     * Protection-owned counterpart to {@link #driveWaterTarget}. It submits
     * through the exact action lease already bound by the runtime; no Baritone
     * movement override or second physical-key writer is layered on top.
     */
    private AquaticTravelPolicy.Decision driveAquaticRetreat(
            RetreatVector escape,
            long nowMillis) {
        var player = client.player;
        if (player == null || client.world == null || escape == null) {
            releaseWaterLocomotion(MovementFrameActuator.Cleanup.CANCEL);
            return AquaticTravelPolicy.Decision.idle(
                    AquaticTravelPolicy.Mode.IDLE,
                    "no loaded aquatic retreat context");
        }
        aquaticEscapeActive = true;
        Vec3d waypoint = shoreEscapeWaypoint(escape, nowMillis);
        if (waypoint == null) {
            // With no verified shore corridor, use the existing real air planner. An arbitrary
            // away-vector is not proof of collision-free water or a dry exit.
            if (waterPathToShore || waterPath.isEmpty()) {
                planWaterEscape(BlockPos.ofFloored(player.getEyePos()), nowMillis);
            }
            discardReachedWaterWaypoints(player.getEyePos());
            Vec3d air = waterPath.isEmpty()
                    ? player.getEyePos().add(0.0, 0.75, 0.0)
                    : Vec3d.ofCenter(new BlockPos(waterPath.peekFirst().x(),
                            waterPath.peekFirst().y(), waterPath.peekFirst().z()));
            return driveWaterTarget(air, false, nowMillis);
        }
        double steeringX = waypoint.x - player.getX();
        double steeringZ = waypoint.z - player.getZ();
        double horizontalDistance = Math.hypot(steeringX, steeringZ);
        double verticalDistance = waypoint.y - player.getY();
        Vec3d velocity = player.getVelocity();
        boolean supportingFloor = AquaticRouteEnvironmentPolicy.supportingFloor(
                player.isOnGround());
        AquaticTravelPolicy.Decision decision = waterLocomotion.step(
                new AquaticTravelPolicy.Input(
                        player.isTouchingWater(),
                        player.isInLava(),
                        player.isSubmergedInWater(),
                        player.isSwimming(),
                        hasDeepWaterBelow(player.getBlockPos()),
                        true,
                        true,
                        movementAuthorityValid(nowMillis),
                        true,
                        false,
                        player.getAir(),
                        player.getMaxAir(),
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        Math.hypot(velocity.x, velocity.z),
                        horizontalDistance,
                        verticalDistance,
                        true,
                        player.horizontalCollision,
                        hasWaterCeiling(player),
                        AquaticTravelPolicy.Objective.SURFACE_ESCAPE,
                        false,
                        player.getHungerManager().getFoodLevel() > 6
                                || player.getAbilities().allowFlying,
                        supportingFloor,
                        false,
                        AquaticRouteEnvironmentPolicy.RediveAssessment.notResuming()));
        if (!decision.ownsMovement()) {
            releaseWaterFrame(MovementFrameActuator.Cleanup.CANCEL);
            // BARITONE_HANDOFF is useful for normal routes, but protection has
            // already cancelled that owner. Forget the bounded handoff so the
            // next protection tick can reacquire an aquatic frame immediately.
            if (decision.mode() == AquaticTravelPolicy.Mode.BARITONE_HANDOFF) {
                waterLocomotion.reset();
            }
            return decision;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(steeringZ, steeringX)) - 90.0
                + decision.yawOffsetDegrees());
        submitWaterFrame(new MovementFrame(
                decision.moveForward(), false, false, false,
                decision.jump(), decision.sneak(), decision.sprint(),
                yaw, decision.pitchDegrees()), nowMillis);
        return decision;
    }

    /** A dry grounded landing ends escape; a brief jump/air sample in the lake does not. */
    public boolean hasAquaticEscapeObjective() {
        if (!aquaticEscapeActive || client.player == null) return false;
        if (aquaticEscapeComplete(client.player.isTouchingWater(), client.player.isOnGround())) {
            shoreEscapeGoal = null;
            aquaticEscapeActive = false;
            waterPathToShore = false;
            waterPath.clear();
            return false;
        }
        return true;
    }

    static boolean aquaticEscapeComplete(boolean touchingWater, boolean grounded) {
        return !touchingWater && grounded;
    }

    /** Neutralize old actuator leases without discarding the same physical shore objective. */
    public void clearForAquaticHandoff(String reason) {
        boolean retainedActive = hasAquaticEscapeObjective();
        BlockPos retained = retainedActive ? shoreEscapeGoal : null;
        var path = waterPathToShore ? java.util.List.copyOf(waterPath) : java.util.List.<GridEscapePlanner.Point>of();
        var failed = java.util.Set.copyOf(failedWaterWaypoints);
        RetreatIntent intent = aquaticDefenseIntent;
        clear(reason);
        if (retainedActive) {
            aquaticEscapeActive = true;
            shoreEscapeGoal = retained;
            waterPath.addAll(path);
            waterPathToShore = !path.isEmpty();
            aquaticDefenseIntent = intent;
            failedWaterWaypoints.addAll(failed);
        }
    }

    private Vec3d shoreEscapeWaypoint(RetreatVector escape, long now) {
        var player = client.player;
        BlockPos start = player.getBlockPos();
        if (shoreEscapeGoal != null && !safeDryShoreFeet(shoreEscapeGoal)) {
            shoreEscapeGoal = null;
            waterPath.clear();
            waterPathToShore = false;
        }
        if (waterPathToShore) {
            while (waterPath.size() > 1) {
                var first = waterPath.peekFirst();
                if (Math.hypot(first.x() + 0.5 - player.getX(),
                        first.z() + 0.5 - player.getZ()) >= 0.6
                        || Math.abs(first.y() - player.getY()) >= 1.0) break;
                waterPath.removeFirst();
            }
        }
        if ((!waterPathToShore || waterPath.isEmpty()) && now - lastShorePlanAt >= 1000L) {
            lastShorePlanAt = now;
            BlockPos retained = shoreEscapeGoal;
            var result = GridEscapePlanner.plan(point(start), candidate -> {
                        double projection = (candidate.x() - start.getX()) * escape.x()
                                + (candidate.z() - start.getZ()) * escape.z();
                        return projection < -0.75 ? GridEscapePlanner.Cell.BLOCKED
                                : probeShoreFeet(candidate);
                    },
                    new GridEscapePlanner.Limits(16, 6, 1, 4096),
                    candidate -> {
                        BlockPos feet = new BlockPos(candidate.x(), candidate.y(), candidate.z());
                        if (!safeDryShoreFeet(feet)) return false;
                        if (failedWaterWaypoints.contains(candidate)) return false;
                        if (retained != null) return retained.equals(feet);
                        double dx = feet.getX() + 0.5 - player.getX();
                        double dz = feet.getZ() + 0.5 - player.getZ();
                        double length = Math.hypot(dx, dz);
                        return length > 1.5 && (dx * escape.x() + dz * escape.z()) >= -0.1 * length;
                    }, true);
            if (result.found()) {
                waterPath.clear();
                waterPath.addAll(result.path());
                waterPathToShore = true;
                var end = result.path().getLast();
                shoreEscapeGoal = new BlockPos(end.x(), end.y(), end.z());
                waterPlanDetail = "following retained dry shore " + shoreEscapeGoal.toShortString();
                logger.info("Retained aquatic shore escape: episode={} goal={} steps={}",
                        retreatEpisodeKey, shoreEscapeGoal.toShortString(), waterPath.size());
            } else if (retained != null) {
                failedWaterWaypoints.add(point(retained));
                shoreEscapeGoal = null;
            }
        }
        if (shoreEscapeGoal == null) return null;
        if (!waterPathToShore || waterPath.isEmpty()) {
            return null;
        }
        var next = waterPath.peekFirst();
        if (probeShoreFeet(next) == GridEscapePlanner.Cell.BLOCKED
                || probeShoreFeet(next) == GridEscapePlanner.Cell.UNLOADED) {
            waterPath.clear();
            waterPathToShore = false;
            return null;
        }
        return Vec3d.ofBottomCenter(new BlockPos(next.x(), next.y(), next.z()));
    }

    private GridEscapePlanner.Cell probeShoreFeet(GridEscapePlanner.Point point) {
        BlockPos feet = new BlockPos(point.x(), point.y(), point.z());
        var lower = probeWaterCell(point);
        var upper = probeWaterCell(point(feet.up()));
        return classifyShoreFeet(lower, upper, safeDryShoreFeet(feet),
                client.world.getFluidState(feet.down()).isIn(FluidTags.WATER));
    }

    static GridEscapePlanner.Cell classifyShoreFeet(GridEscapePlanner.Cell lower,
            GridEscapePlanner.Cell upper, boolean drySupported, boolean waterBelow) {
        if (lower == GridEscapePlanner.Cell.UNLOADED || upper == GridEscapePlanner.Cell.UNLOADED)
            return GridEscapePlanner.Cell.UNLOADED;
        if ((lower != GridEscapePlanner.Cell.WATER && lower != GridEscapePlanner.Cell.BREATHABLE)
                || (upper != GridEscapePlanner.Cell.WATER && upper != GridEscapePlanner.Cell.BREATHABLE))
            return GridEscapePlanner.Cell.BLOCKED;
        if (lower == GridEscapePlanner.Cell.WATER || upper == GridEscapePlanner.Cell.WATER)
            return GridEscapePlanner.Cell.WATER;
        if (drySupported) return GridEscapePlanner.Cell.BREATHABLE;
        // The body must rise above the waterline before stepping onto a bank.
        // This is a transit cell, not a completed dry shore and not free flight.
        return waterBelow ? GridEscapePlanner.Cell.WATER : GridEscapePlanner.Cell.BLOCKED;
    }

    private boolean safeDryShoreFeet(BlockPos feet) {
        return client.world != null
                && client.world.getChunkManager().getWorldChunk(feet.getX() >> 4, feet.getZ() >> 4) != null
                && safeRetreatFeetCell(feet)
                && client.world.getFluidState(feet).isEmpty()
                && client.world.getFluidState(feet.up()).isEmpty()
                && client.world.getFluidState(feet.down()).isEmpty();
    }

    private SurvivalExecution attemptCeilingBreach(long now) {
        var player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            return SurvivalExecution.WATER_NO_ROUTE;
        }
        BlockPos eyeBlock = BlockPos.ofFloored(player.getEyePos());
        BlockPos candidate = eyeBlock.up();
        BlockState state = client.world.getBlockState(candidate);

        if (candidate.equals(timedOutBreachBlock)) {
            waterPlanDetail = "the verified ceiling breach timed out; holding for another escape route";
            return SurvivalExecution.WATER_NO_ROUTE;
        }

        if (breachBlock != null && (!breachBlock.equals(candidate)
                || state.getCollisionShape(client.world, candidate).isEmpty())) {
            cancelBreach();
        }
        if (breachBlock == null) {
            if (state.getCollisionShape(client.world, candidate).isEmpty()
                    || state.getBlock() instanceof FallingBlock
                    || probePlanningWaterCell(point(candidate.up()), now)
                    != GridEscapePlanner.Cell.BREATHABLE
                    || lavaAdjacentOrUnknown(candidate)
                    || lavaAdjacentOrUnknown(candidate.up())
                    || state.getHardness(client.world, candidate) < 0) {
                return SurvivalExecution.WATER_NO_ROUTE;
            }
            int toolSlot = fastestHotbarTool(state);
            if (state.isToolRequired() && toolSlot < 0) {
                waterPlanDetail = "air is above the ceiling, but a suitable hotbar tool is required to break through";
                return SurvivalExecution.WATER_NO_ROUTE;
            }
            int previousSlot = player.getInventory().getSelectedSlot();
            if (toolSlot >= 0) selectHotbarSlotNow(toolSlot);
            float delta = state.calcBlockBreakingDelta(player, client.world, candidate);
            if (!(delta > 0.0F)) {
                selectHotbarSlotNow(previousSlot);
                return SurvivalExecution.WATER_NO_ROUTE;
            }
            int estimatedTicks = (int) Math.ceil(1.0 / delta);
            if (player.getAir() <= estimatedTicks + 20) {
                selectHotbarSlotNow(previousSlot);
                waterPlanDetail = "ceiling breach is too slow for the remaining air";
                return SurvivalExecution.WATER_NO_ROUTE;
            }
            breachPrepared = true;
            breachBlock = candidate.toImmutable();
            breachPreviousSlot = previousSlot;
            breachDeadlineAt = now + Math.max(5_000L, estimatedTicks * 100L + 2_000L);
        } else if (now >= breachDeadlineAt) {
            BlockPos timedOut = breachBlock;
            cancelBreach();
            timedOutBreachBlock = timedOut;
            waterPlanDetail = "the verified ceiling breach made no timely progress; it will not be retried underwater";
            return SurvivalExecution.WATER_NO_ROUTE;
        }

        boolean aimObserved = waterFrameSampled(-90.0F);
        submitWaterFrame(new MovementFrame(
                false, false, false, false,
                true, false, true,
                player.getYaw(), -90.0F), now);
        if (!aimObserved) {
            waterPlanDetail = "aligning with the emergency ceiling breach";
            return SurvivalExecution.BREACHING_CEILING;
        }
        HitResult rawHit = player.raycast(player.getBlockInteractionRange(), 1.0F, false);
        if (!(rawHit instanceof BlockHitResult hit) || !hit.getBlockPos().equals(breachBlock)) {
            waterPlanDetail = "aligning with the emergency ceiling breach";
            return SurvivalExecution.BREACHING_CEILING;
        }
        String operationId = "survival:ceiling:" + currentDimension() + ':' + breachBlock.asLong();
        AtomicBlockBreakController.Result breaking = emergencyBreak.tick(
                operationId, breachBlock, hit, now);
        return switch (breaking.state()) {
            case WAITING, STARTED, CONTINUE, RESTART_REQUIRED -> {
                waterPlanDetail = "breaking one verified ceiling block with breathable space above";
                yield SurvivalExecution.BREACHING_CEILING;
            }
            case COMPLETE -> {
                cancelBreach();
                waterPlanDetail = "ceiling breach cleared; recalculating the route to breathable space";
                yield SurvivalExecution.WATER_SEARCHING;
            }
            case TOOL_PREEMPTED, OUT_OF_REACH, SIGHTLINE_LOST,
                    ACTION_REJECTED, SERVER_REJECTED, STALLED -> {
                BlockPos failed = breachBlock;
                String detail = breaking.detail();
                cancelBreach();
                timedOutBreachBlock = failed;
                waterPlanDetail = "ceiling breach stopped safely: " + detail;
                yield SurvivalExecution.WATER_NO_ROUTE;
            }
        };
    }

    private int fastestHotbarTool(BlockState state) {
        var inventory = client.player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 1.0F;
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isSuitableFor(state) || remainingDurability(stack) <= 1) continue;
            float speed = stack.getMiningSpeedMultiplier(state);
            if (bestSlot < 0 || speed > bestSpeed) {
                bestSlot = slot;
                bestSpeed = speed;
            }
        }
        return bestSlot;
    }

    private boolean lavaAdjacentOrUnknown(BlockPos origin) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = origin.offset(direction);
            if (!client.world.isChunkLoaded(adjacent.getX() >> 4, adjacent.getZ() >> 4)
                    || client.world.getBlockState(adjacent).getFluidState().isIn(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private String currentDimension() {
        return client.world == null
                ? "minecraft:unknown"
                : client.world.getRegistryKey().getValue().toString();
    }

    private SurvivalExecution executeLavaEscape(long now) {
        beginDirectMovementFrame();
        var player = client.player;
        if (player == null || client.world == null) {
            resetLavaState();
            return SurvivalExecution.LAVA_NO_ROUTE;
        }
        if (!player.isInLava()) {
            restoreLavaBucket();
            lavaPath.clear();
            return SurvivalExecution.LAVA_ESCAPED;
        }

        Vec3d body = player.getPos();
        discardReachedLavaWaypoints(body);
        if (lavaPath.isEmpty() && now - lastLavaPlanAt >= LAVA_REPLAN_MILLIS) {
            planLavaEscape(player.getBlockPos(), now);
        }
        discardReachedLavaWaypoints(body);

        // A water countermeasure is an urgent side effect, never a replacement
        // for escape locomotion. The old branch spent a whole tick jumping in
        // place, and could repeat with an empty bucket after the first accepted
        // placement. Keep the route/fallback moving in the same tick.
        boolean fireProtected = player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE);
        boolean urgentBucket = !fireProtected
                && (player.getHealth() <= player.getMaxHealth()
                        * LAVA_CRITICAL_HEALTH_FRACTION
                || lavaPath.isEmpty());
        boolean bucketAccepted = urgentBucket && attemptLavaWaterCountermeasure(now);

        if (!lavaPath.isEmpty()) {
            LavaEscapePlanner.Point target = lavaPath.peekFirst();
            LavaEscapePlanner.Cell live = probeLavaCell(target);
            if (live == LavaEscapePlanner.Cell.BLOCKED
                    || live == LavaEscapePlanner.Cell.HAZARD
                    || live == LavaEscapePlanner.Cell.UNLOADED) {
                failedLavaWaypoints.add(target);
                lavaPath.clear();
                lastLavaPlanAt = 0;
                lavaPlanDetail = "the committed lava exit changed; selecting another loaded route";
                pushLateralLavaEscape(player, now, "invalid-waypoint-fallback");
                return SurvivalExecution.LAVA_SEARCHING;
            }
            Vec3d targetCenter = new Vec3d(
                    target.x() + 0.5, target.y() + 0.1, target.z() + 0.5);
            boolean terminalExitHold = retainTerminalLavaWaypoint(
                    player.isInLava(),
                    lavaPath.size(),
                    live,
                    body.squaredDistanceTo(targetCenter));
            if (lavaWaypointStalled(body, target, now, terminalExitHold)) {
                failedLavaWaypoints.add(target);
                lavaPath.clear();
                lavaProgressTarget = null;
                bestLavaTargetDistance = Double.POSITIVE_INFINITY;
                lastLavaPlanAt = 0;
                lavaPlanDetail = "lava escape made no displacement; blacklisting that waypoint";
                pushLateralLavaEscape(player, now, "stalled-waypoint-fallback");
                return SurvivalExecution.LAVA_SEARCHING;
            }
            steerBodyTowardLavaWaypoint(target, now);
            if (terminalExitHold) {
                // Reaching the block center is not equivalent to moving the
                // full player bounding box out of the source lava. Keep the
                // verified exit committed; only vanilla's isInLava=false may
                // complete this final leg.
                lavaProgressTarget = target;
                bestLavaTargetDistance = Math.min(
                        bestLavaTargetDistance,
                        body.distanceTo(targetCenter));
                lastLavaProgressAt = now;
                lavaPlanDetail = "inside the terminal dry/water cell; steering inward until vanilla confirms lava exit";
            } else if (bucketAccepted) {
                lavaPlanDetail = "water countermeasure accepted while continuing the committed lateral exit";
            }
            return SurvivalExecution.LAVA_PATHING;
        }

        // A sealed or unloaded pool still gets horizontal motion.  Never revert to the old
        // jump-only loop with zero X/Z velocity.
        pushLateralLavaEscape(player, now, "no-route-fallback");
        lavaPlanDetail = bucketAccepted
                ? "water countermeasure accepted while pushing laterally toward an exit"
                : "no verified exit is loaded; pushing laterally while the bounded search retries";
        return bucketAccepted
                ? SurvivalExecution.LAVA_BUCKET_ATTEMPTED
                : SurvivalExecution.LAVA_NO_ROUTE;
    }

    private void pushLateralLavaEscape(
            net.minecraft.client.network.ClientPlayerEntity player,
            long now,
            String mode) {
        Direction fallback = leastLavaHorizontalDirection(player.getBlockPos());
        if (fallback != null) {
            faceToward(Vec3d.ofCenter(player.getBlockPos().offset(fallback)));
        }
        commandMovement(true, false, true, true, false);
        recordLavaCommand(now, mode, true, true);
    }

    private void planLavaEscape(BlockPos startBlock, long now) {
        lastLavaPlanAt = now;
        lavaPath.clear();
        LavaEscapePlanner.Result result = LavaEscapePlanner.plan(
                lavaPoint(startBlock), this::probeLavaCell, LAVA_LIMITS, failedLavaWaypoints);
        if (!result.found()) {
            lavaPlanDetail = "no supported dry or water exit found in "
                    + result.visitedCells() + " loaded cells";
            return;
        }
        lavaPath.addAll(result.path());
        lavaProgressTarget = null;
        bestLavaTargetDistance = Double.POSITIVE_INFINITY;
        lastLavaProgressAt = now;
        lavaPlanDetail = "committed a " + result.path().size() + "-cell route to "
                + result.exitCell().name().toLowerCase(Locale.ROOT);
    }

    private LavaEscapePlanner.Cell probeLavaCell(LavaEscapePlanner.Point point) {
        if (client.world == null) return LavaEscapePlanner.Cell.UNLOADED;
        BlockPos feet = new BlockPos(point.x(), point.y(), point.z());
        if (feet.getY() < client.world.getBottomY()
                || feet.getY() >= client.world.getTopYInclusive()) {
            return LavaEscapePlanner.Cell.BLOCKED;
        }
        if (!client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) {
            return LavaEscapePlanner.Cell.UNLOADED;
        }
        BlockPos head = feet.up();
        BlockState feetState = client.world.getBlockState(feet);
        BlockState headState = client.world.getBlockState(head);
        if (!feetState.getCollisionShape(client.world, feet).isEmpty()
                || !headState.getCollisionShape(client.world, head).isEmpty()) {
            return LavaEscapePlanner.Cell.BLOCKED;
        }
        if (lavaFluid(feetState) || lavaFluid(headState)) return LavaEscapePlanner.Cell.LAVA;
        if (dangerousLavaExitCell(feet, feetState) || dangerousLavaExitCell(head, headState)) {
            return LavaEscapePlanner.Cell.HAZARD;
        }
        if (feetState.getFluidState().isIn(FluidTags.WATER)
                || headState.getFluidState().isIn(FluidTags.WATER)) {
            return LavaEscapePlanner.Cell.WATER;
        }
        BlockPos below = feet.down();
        if (!client.world.isChunkLoaded(below.getX() >> 4, below.getZ() >> 4)) {
            return LavaEscapePlanner.Cell.UNLOADED;
        }
        BlockState support = client.world.getBlockState(below);
        boolean supported = !support.getCollisionShape(client.world, below).isEmpty()
                && !lavaFluid(support)
                && !dangerousLavaExitCell(below, support);
        return supported ? LavaEscapePlanner.Cell.SAFE : LavaEscapePlanner.Cell.OPEN;
    }

    private boolean dangerousLavaExitCell(BlockPos pos, BlockState state) {
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.MAGMA_BLOCK);
    }

    private static boolean lavaFluid(BlockState state) {
        return state.getFluidState().isIn(FluidTags.LAVA);
    }

    private void discardReachedLavaWaypoints(Vec3d body) {
        while (!lavaPath.isEmpty()) {
            LavaEscapePlanner.Point point = lavaPath.peekFirst();
            Vec3d center = new Vec3d(point.x() + 0.5, point.y() + 0.1, point.z() + 0.5);
            if (body.squaredDistanceTo(center) > LAVA_WAYPOINT_REACHED_SQUARED) break;
            if (client.player != null && retainTerminalLavaWaypoint(
                    client.player.isInLava(),
                    lavaPath.size(),
                    probeLavaCell(point),
                    body.squaredDistanceTo(center))) {
                // Body-center proximity is not proof that the whole player
                // bounding box left lava. Keep steering into the verified
                // terminal cell until vanilla confirms isInLava=false.
                break;
            }
            lavaPath.removeFirst();
            lavaProgressTarget = null;
            bestLavaTargetDistance = Double.POSITIVE_INFINITY;
        }
    }

    static boolean retainTerminalLavaWaypoint(
            boolean inLava,
            int remainingWaypoints,
            LavaEscapePlanner.Cell liveCell,
            double distanceSquared) {
        return inLava
                && remainingWaypoints == 1
                && (liveCell == LavaEscapePlanner.Cell.SAFE
                        || liveCell == LavaEscapePlanner.Cell.WATER)
                && Double.isFinite(distanceSquared)
                && distanceSquared <= LAVA_WAYPOINT_REACHED_SQUARED;
    }

    private boolean lavaWaypointStalled(
            Vec3d body,
            LavaEscapePlanner.Point target,
            long now,
            boolean terminalExitHold) {
        Vec3d center = new Vec3d(target.x() + 0.5, target.y() + 0.1, target.z() + 0.5);
        double distance = body.distanceTo(center);
        if (terminalExitHold) {
            lavaProgressTarget = target;
            bestLavaTargetDistance = Math.min(bestLavaTargetDistance, distance);
            lastLavaProgressAt = now;
            return false;
        }
        if (!target.equals(lavaProgressTarget)) {
            lavaProgressTarget = target;
            bestLavaTargetDistance = distance;
            lastLavaProgressAt = now;
            return false;
        }
        if (distance <= bestLavaTargetDistance - 0.08) {
            bestLavaTargetDistance = distance;
            lastLavaProgressAt = now;
            return false;
        }
        return ordinaryLavaWaypointStallExpired(
                Math.max(0L, now - lastLavaProgressAt), false);
    }

    static boolean ordinaryLavaWaypointStallExpired(
            long withoutProgressMillis,
            boolean terminalExitHold) {
        return !terminalExitHold
                && withoutProgressMillis >= LAVA_WAYPOINT_STALL_MILLIS;
    }

    private void steerBodyTowardLavaWaypoint(LavaEscapePlanner.Point target, long now) {
        var player = client.player;
        if (player == null) return;
        Vec3d body = player.getPos();
        double dx = target.x() + 0.5 - body.x;
        double dy = target.y() + 0.1 - body.y;
        double dz = target.z() + 0.5 - body.z;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal > 0.04) {
            player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        }
        player.setPitch((float) Math.max(-70.0, Math.min(70.0,
                -Math.toDegrees(Math.atan2(dy, Math.max(0.001, horizontal))))));
        boolean forward = horizontal > 0.04;
        boolean jump = dy > 0.15 || player.horizontalCollision;
        commandMovement(forward, false, forward, jump, dy < -0.55);
        recordLavaCommand(now, "verified-route", forward, jump);
    }

    private void recordLavaCommand(long now, String mode, boolean forward, boolean jump) {
        lavaCommandedAt = now;
        lavaCommandedClientTick = movementAuthority == null
                ? -1L
                : movementAuthority.clientTick();
        lavaCommandMode = Objects.requireNonNullElse(mode, "unknown");
        lavaForwardCommanded = forward;
        lavaJumpCommanded = jump;
    }

    private Direction leastLavaHorizontalDirection(BlockPos origin) {
        Direction best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos first = origin.offset(direction);
            BlockPos firstHead = first.up();
            if (!loaded(first) || !loaded(firstHead)) continue;
            BlockState firstState = client.world.getBlockState(first);
            BlockState headState = client.world.getBlockState(firstHead);
            if (!firstState.getCollisionShape(client.world, first).isEmpty()
                    || !headState.getCollisionShape(client.world, firstHead).isEmpty()
                    || dangerousLavaExitCell(first, firstState)
                    || dangerousLavaExitCell(firstHead, headState)) continue;
            int depth = 0;
            for (int step = 1; step <= LAVA_LIMITS.horizontalRadius(); step++) {
                BlockPos candidate = origin.offset(direction, step);
                if (!client.world.isChunkLoaded(candidate.getX() >> 4, candidate.getZ() >> 4)) {
                    depth += 8;
                    break;
                }
                if (!client.world.getFluidState(candidate).isIn(FluidTags.LAVA)) break;
                depth++;
            }
            if (depth < bestDepth) {
                bestDepth = depth;
                best = direction;
            }
        }
        return best;
    }

    private boolean attemptLavaWaterCountermeasure(long now) {
        var player = client.player;
        if (player == null || client.world == null || client.interactionManager == null
                || client.world.getDimension().ultrawarm()
                || now - lastLavaBucketAttemptAt < LAVA_BUCKET_RETRY_MILLIS) return false;
        boolean bucketPresent = lavaBucketPrepared
                || player.getOffHandStack().isOf(Items.WATER_BUCKET);
        for (int index = 0; !bucketPresent && index < PlayerInventory.getHotbarSize(); index++) {
            bucketPresent = player.getInventory().getStack(index).isOf(Items.WATER_BUCKET);
        }
        if (!bucketPresent) return false;
        // An explicit bucket interaction and a held shield cannot own USE_ITEM
        // at once. Release only on the bounded bucket-attempt tick, before any
        // slot is prepared, then restore defense later in this same frame.
        stopFireDefenseShield();
        if (!lavaBucketPrepared) {
            Hand hand = null;
            int slot = -1;
            if (player.getOffHandStack().isOf(Items.WATER_BUCKET)) {
                hand = Hand.OFF_HAND;
            } else {
                for (int index = 0; index < PlayerInventory.getHotbarSize(); index++) {
                    if (player.getInventory().getStack(index).isOf(Items.WATER_BUCKET)) {
                        hand = Hand.MAIN_HAND;
                        slot = index;
                        break;
                    }
                }
            }
            if (hand == null) return false;
            lavaBucketPreviousSlot = player.getInventory().getSelectedSlot();
            lavaBucketPreviousPitch = player.getPitch();
            lavaBucketHand = hand;
            lavaBucketSlot = slot;
            if (lavaBucketSlot >= 0) selectHotbarSlotNow(lavaBucketSlot);
            lavaBucketPrepared = true;
        }
        if (lavaBucketSlot >= 0) selectHotbarSlotNow(lavaBucketSlot);
        ItemStack held = lavaBucketHand == Hand.OFF_HAND
                ? player.getOffHandStack()
                : lavaBucketSlot >= 0
                ? player.getInventory().getStack(lavaBucketSlot)
                : ItemStack.EMPTY;
        if (!held.isOf(Items.WATER_BUCKET)) {
            restoreLavaBucket();
            return false;
        }
        player.setPitch(85.0F);
        HitResult raw = player.raycast(player.getBlockInteractionRange(), 1.0F, false);
        lastLavaBucketAttemptAt = now;
        boolean accepted = false;
        if (raw instanceof BlockHitResult hit) {
            var result = client.interactionManager.interactBlock(player, lavaBucketHand, hit);
            accepted = result.isAccepted();
            if (accepted) {
                player.swingHand(lavaBucketHand);
                restoreLavaBucket();
            }
        }
        if (!accepted && lavaBucketPrepared) {
            // A rejected/awaiting use must not leave the route looking straight
            // down for the rest of this tick. Custody remains prepared for one
            // later bounded retry, but locomotion immediately regains its aim.
            player.setPitch(lavaBucketPreviousPitch);
        }
        return accepted;
    }

    private void restoreLavaBucket() {
        if (lavaBucketPrepared && client.player != null) {
            if (lavaBucketPreviousSlot >= 0) selectHotbarSlotNow(lavaBucketPreviousSlot);
            client.player.setPitch(lavaBucketPreviousPitch);
        }
        lavaBucketPreviousSlot = -1;
        lavaBucketSlot = -1;
        lavaBucketPreviousPitch = 0;
        lavaBucketPrepared = false;
        lavaBucketHand = null;
    }

    private static LavaEscapePlanner.Point lavaPoint(BlockPos pos) {
        return new LavaEscapePlanner.Point(pos.getX(), pos.getY(), pos.getZ());
    }

    private SurvivalExecution executeFireEscape(long now, Entity activeThreat) {
        beginDirectMovementFrame();
        var player = client.player;
        if (player == null || client.world == null) {
            resetFireState();
            return SurvivalExecution.FIRE_NO_ROUTE;
        }
        if (!player.isOnFire()) {
            // The survival latch deliberately holds for another second after the
            // fire clears. That hold must be stationary; continuing the previous
            // forward command is precisely how the retained production run
            // re-entered lava after reaching a dry exit.
            resetFireState();
            commandFireMovement(now, "extinguished-hold", false, false);
            applyStationaryFireDefense(activeThreat, now);
            return SurvivalExecution.FIRE_EXTINGUISHED;
        }

        // Use the exact vanilla timer on every burning frame. Health or the
        // timer can change while holding a dry cell, so survivability is never
        // inherited from the frame that originally planned the route.
        FireEscapePlanner.BurnSurvivability burn = FireEscapePlanner.assessBurn(
                player.getHealth(), player.getFireTicks());

        // Water placement is an extinguishing side effect, never permission to
        // skip route safety. Even after an accepted interaction, keep following
        // the supported path until vanilla confirms the fire is gone.
        boolean bucketAccepted = attemptLavaWaterCountermeasure(now);
        Vec3d body = player.getPos();
        discardReachedFireWaypoints(body, burn);
        if (firePath.isEmpty() && now - lastFirePlanAt >= FIRE_REPLAN_MILLIS) {
            planFireEscape(player.getBlockPos(), now, burn);
        }
        discardReachedFireWaypoints(body, burn);

        if (firePath.isEmpty()) {
            // Sealed geometry is safer than an invented heading. Keep the body
            // centered and let the bucket/burn timer work; never turn an unknown
            // cell into a full-speed command.
            commandFireMovement(now, bucketAccepted
                    ? "bucket-no-route-hold" : "no-route-hold", false, false);
            applyStationaryFireDefense(activeThreat, now);
            firePlanDetail = unresolvedFireHoldDetail(burn, bucketAccepted);
            return bucketAccepted
                    ? SurvivalExecution.FIRE_BUCKET_ATTEMPTED
                    : SurvivalExecution.FIRE_NO_ROUTE;
        }

        FireEscapePlanner.Point target = firePath.peekFirst();
        FireEscapePlanner.Cell live = probeFireCell(target);
        if (live == FireEscapePlanner.Cell.WATER) {
            fireTerminalKind = FireEscapePlanner.TerminalKind.WATER;
        }
        if (!fireCellTraversable(live)) {
            failedFireWaypoints.add(target);
            firePath.clear();
            fireProgressTarget = null;
            bestFireTargetDistance = Double.POSITIVE_INFINITY;
            lastFirePlanAt = 0L;
            commandFireMovement(now, "invalid-route-hold", false, false);
            applyStationaryFireDefense(activeThreat, now);
            firePlanDetail = "the committed fire route changed; refusing blind movement and replanning";
            return SurvivalExecution.FIRE_SEARCHING;
        }

        Vec3d center = fireCenter(target);
        boolean terminalHold = retainFireTerminalWaypoint(
                true,
                firePath.size(),
                live,
                body.squaredDistanceTo(center),
                burn.dryHoldSurvivable(),
                fireTerminalKind);
        if (fireWaypointStalled(body, target, now, terminalHold)) {
            failedFireWaypoints.add(target);
            firePath.clear();
            fireProgressTarget = null;
            bestFireTargetDistance = Double.POSITIVE_INFINITY;
            lastFirePlanAt = 0L;
            commandFireMovement(now, "stalled-route-hold", false, false);
            applyStationaryFireDefense(activeThreat, now);
            firePlanDetail = "fire route made no progress; blacklisting it before selecting another";
            return SurvivalExecution.FIRE_SEARCHING;
        }

        steerBodyTowardFireWaypoint(target, terminalHold, now, live);
        applyStationaryFireDefense(activeThreat, now);
        if (terminalHold) {
            firePlanDetail = live == FireEscapePlanner.Cell.WATER
                    ? "holding inside verified water until vanilla confirms extinguished"
                    : "holding the safest dry cell; burn remains unresolved but the exact timer forecast is survivable ("
                            + burnForecast(burn) + "); this dry hold does not extinguish";
        } else if (bucketAccepted) {
            firePlanDetail = "water countermeasure accepted while continuing the verified supported route";
        }
        return SurvivalExecution.FIRE_PATHING;
    }

    private void planFireEscape(
            BlockPos start,
            long now,
            FireEscapePlanner.BurnSurvivability burn) {
        lastFirePlanAt = now;
        firePath.clear();
        FireEscapePlanner.Result result = FireEscapePlanner.plan(
                firePoint(start), this::probeFireCell, FIRE_LIMITS, burn,
                failedFireWaypoints);
        firePath.addAll(result.path());
        fireTerminalKind = result.terminalKind();
        fireProgressTarget = null;
        bestFireTargetDistance = Double.POSITIVE_INFINITY;
        lastFireProgressAt = now;
        if (result.terminalKind() == FireEscapePlanner.TerminalKind.WATER) {
            firePlanDetail = "committed a " + result.path().size()
                    + "-cell supported route to extinguishing water";
        } else if (result.terminalKind()
                == FireEscapePlanner.TerminalKind.SURVIVABLE_DRY_HOLD) {
            firePlanDetail = "bounded search found no reachable water; committed the safest dry hold; "
                    + "burn remains unresolved but is conservatively survivable ("
                    + burnForecast(burn) + "); dry ground is not extinguishing success";
        } else if (result.terminalKind() == FireEscapePlanner.TerminalKind.DRY_FALLBACK) {
            firePlanDetail = "bounded search found no reachable water; committed the safest dry fallback; "
                    + "burn unresolved/likely fatal (" + burnForecast(burn)
                    + "); this is fallback movement, not terminal success";
        } else if (!result.path().isEmpty()) {
            firePlanDetail = "no water or safe dry hold is loaded; committed the safest bounded frontier; "
                    + unresolvedBurnAssessment(burn);
        } else {
            firePlanDetail = "no traversable supported fire-escape cell found in "
                    + result.visitedCells() + " loaded cells; "
                    + unresolvedBurnAssessment(burn);
        }
    }

    private static String burnForecast(FireEscapePlanner.BurnSurvivability burn) {
        return "fireTicks=" + burn.fireTicks()
                + ", predictedHits=" + burn.predictedRemainingHits()
                + ", predictedDamage=" + burn.predictedDamage()
                + ", health=" + burn.currentHealth()
                + ", reserve=" + burn.healthReserve();
    }

    private static String unresolvedBurnAssessment(
            FireEscapePlanner.BurnSurvivability burn) {
        return burn.dryHoldSurvivable()
                ? "burn remains unresolved but the exact timer forecast is survivable ("
                        + burnForecast(burn) + ")"
                : "burn unresolved/likely fatal without water (" + burnForecast(burn) + ")";
    }

    private static String unresolvedFireHoldDetail(
            FireEscapePlanner.BurnSurvivability burn,
            boolean bucketAccepted) {
        String prefix = bucketAccepted
                ? "water countermeasure accepted but vanilla has not confirmed extinguishing; "
                : "no supported route to water is currently loaded; ";
        return prefix + "holding the safest verified dry position with defense; "
                + unresolvedBurnAssessment(burn) + "; rescanning remains bounded";
    }

    private FireEscapePlanner.Cell probeFireCell(FireEscapePlanner.Point point) {
        if (client.world == null) return FireEscapePlanner.Cell.UNLOADED;
        BlockPos feet = new BlockPos(point.x(), point.y(), point.z());
        BlockPos head = feet.up();
        BlockPos floor = feet.down();
        if (!loaded(feet) || !loaded(head) || !loaded(floor)) {
            return FireEscapePlanner.Cell.UNLOADED;
        }
        BlockState feetState = client.world.getBlockState(feet);
        BlockState headState = client.world.getBlockState(head);
        if (!feetState.getCollisionShape(client.world, feet).isEmpty()
                || !headState.getCollisionShape(client.world, head).isEmpty()) {
            return FireEscapePlanner.Cell.BLOCKED;
        }
        if (dangerousFireEscapeCell(feet, feetState)
                || dangerousFireEscapeCell(head, headState)) {
            return FireEscapePlanner.Cell.HAZARD;
        }
        BlockState floorState = client.world.getBlockState(floor);
        // Water is not a safe terminal when its floor is itself lava or another damaging block.
        // Deep water with an empty, harmless floor remains a valid extinguishing volume.
        if (dangerousFireEscapeCell(floor, floorState)) {
            return FireEscapePlanner.Cell.HAZARD;
        }
        if (feetState.getFluidState().isIn(FluidTags.WATER)
                || headState.getFluidState().isIn(FluidTags.WATER)) {
            return FireEscapePlanner.Cell.WATER;
        }
        if (floorState.getCollisionShape(client.world, floor).isEmpty()) {
            return FireEscapePlanner.Cell.BLOCKED;
        }
        int nearestHazard = nearestFireHazardDistance(feet, 2);
        if (nearestHazard <= 1) return FireEscapePlanner.Cell.NEAR_HAZARD;
        if (nearestHazard == 2) return FireEscapePlanner.Cell.MARGIN;
        return FireEscapePlanner.Cell.SAFE;
    }

    private int nearestFireHazardDistance(BlockPos origin, int radius) {
        int nearest = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int horizontal = Math.max(Math.abs(dx), Math.abs(dz));
                if (horizontal == 0 || horizontal >= nearest) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (!loaded(candidate)) return 0;
                    BlockState state = client.world.getBlockState(candidate);
                    if (dangerousFireEscapeCell(candidate, state)) {
                        nearest = horizontal;
                        break;
                    }
                }
            }
        }
        return nearest;
    }

    private boolean dangerousFireEscapeCell(BlockPos position, BlockState state) {
        return lavaFluid(state)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.POINTED_DRIPSTONE)
                || state.isOf(Blocks.WITHER_ROSE);
    }

    private void discardReachedFireWaypoints(
            Vec3d body,
            FireEscapePlanner.BurnSurvivability burn) {
        while (!firePath.isEmpty()) {
            FireEscapePlanner.Point point = firePath.peekFirst();
            Vec3d center = fireCenter(point);
            if (body.squaredDistanceTo(center) > FIRE_WAYPOINT_REACHED_SQUARED) return;
            FireEscapePlanner.Cell live = probeFireCell(point);
            if (live == FireEscapePlanner.Cell.WATER) {
                fireTerminalKind = FireEscapePlanner.TerminalKind.WATER;
            }
            if (retainFireTerminalWaypoint(
                    client.player != null && client.player.isOnFire(),
                    firePath.size(),
                    live,
                    body.squaredDistanceTo(center),
                    burn.dryHoldSurvivable(),
                    fireTerminalKind)) {
                return;
            }
            firePath.removeFirst();
            fireProgressTarget = null;
            bestFireTargetDistance = Double.POSITIVE_INFINITY;
        }
    }

    static boolean retainFireTerminalWaypoint(
            boolean onFire,
            int pathSize,
            FireEscapePlanner.Cell live,
            double squaredDistance,
            boolean dryHoldSurvivable,
            FireEscapePlanner.TerminalKind terminalKind) {
        if (!onFire || pathSize != 1
                || squaredDistance > FIRE_WAYPOINT_REACHED_SQUARED) {
            return false;
        }
        if (live == FireEscapePlanner.Cell.WATER) return true;
        return live == FireEscapePlanner.Cell.SAFE
                && dryHoldSurvivable
                && terminalKind == FireEscapePlanner.TerminalKind.SURVIVABLE_DRY_HOLD;
    }

    private boolean fireWaypointStalled(
            Vec3d body,
            FireEscapePlanner.Point target,
            long now,
            boolean terminalHold) {
        Vec3d center = fireCenter(target);
        double distance = body.distanceTo(center);
        if (terminalHold) {
            fireProgressTarget = target;
            bestFireTargetDistance = Math.min(bestFireTargetDistance, distance);
            lastFireProgressAt = now;
            return false;
        }
        if (!target.equals(fireProgressTarget)) {
            fireProgressTarget = target;
            bestFireTargetDistance = distance;
            lastFireProgressAt = now;
            return false;
        }
        if (distance <= bestFireTargetDistance - 0.08) {
            bestFireTargetDistance = distance;
            lastFireProgressAt = now;
            return false;
        }
        return Math.max(0L, now - lastFireProgressAt) >= FIRE_WAYPOINT_STALL_MILLIS;
    }

    private void steerBodyTowardFireWaypoint(
            FireEscapePlanner.Point target,
            boolean terminalHold,
            long now,
            FireEscapePlanner.Cell live) {
        var player = client.player;
        if (player == null) return;
        Vec3d body = player.getPos();
        Vec3d center = fireCenter(target);
        double dx = center.x - body.x;
        double dy = center.y - body.y;
        double dz = center.z - body.z;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal > 0.04) {
            player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        }
        player.setPitch(0.0F);
        boolean forward = horizontal > (terminalHold ? 0.12 : 0.04);
        boolean jump = !terminalHold && (dy > 0.35
                || (player.horizontalCollision && player.isOnGround()));
        // Do not sprint: a one-cell verified waypoint is not permission for
        // momentum to carry the body into the following unverified cell.
        String mode = terminalHold
                ? (live == FireEscapePlanner.Cell.WATER
                        ? "terminal-water-hold" : "terminal-safe-hold")
                : "verified-route";
        commandFireMovement(now, mode, forward, jump);
    }

    private void commandFireMovement(
            long now,
            String mode,
            boolean forward,
            boolean jump) {
        commandMovement(forward, false, false, jump, false);
        fireCommandedAt = now;
        fireCommandedClientTick = movementAuthority == null
                ? -1L
                : movementAuthority.clientTick();
        fireCommandMode = Objects.requireNonNullElse(mode, "unknown");
        fireForwardCommanded = forward;
        fireJumpCommanded = jump;
    }

    /**
     * A shield changes look direction, so it may compose only with an exact
     * fire state whose movement command is a stationary hold. In particular,
     * a temporarily zero-length verified-route frame is not a terminal: the
     * fire planner retains exclusive look/movement custody until it publishes
     * one of these explicit hold modes.
     */
    static boolean stationaryFireDefenseAllowed(
            String commandMode,
            boolean forwardCommanded,
            boolean jumpCommanded) {
        if (forwardCommanded || jumpCommanded) return false;
        return switch (Objects.requireNonNullElse(commandMode, "")) {
            case "extinguished-hold",
                    "bucket-no-route-hold",
                    "no-route-hold",
                    "invalid-route-hold",
                    "stalled-route-hold",
                    "terminal-water-hold",
                    "terminal-safe-hold" -> true;
            default -> false;
        };
    }

    static FireDefenseAction chooseFireDefenseAction(
            boolean stationaryHold,
            boolean threatLive,
            boolean offhandShieldReady,
            boolean attackReachable,
            float attackCooldownProgress) {
        if (!stationaryHold || !threatLive) return FireDefenseAction.NONE;
        if (attackReachable && Float.isFinite(attackCooldownProgress)
                && attackCooldownProgress >= 0.9F) {
            return FireDefenseAction.COUNTER_ATTACK;
        }
        return offhandShieldReady
                ? FireDefenseAction.HOLD_SHIELD
                : FireDefenseAction.NONE;
    }

    private void applyStationaryFireDefense(Entity threat, long now) {
        var player = client.player;
        boolean live = player != null && threat != null
                && threat.isAlive() && !threat.isRemoved();
        boolean stationary = stationaryFireDefenseAllowed(
                fireCommandMode, fireForwardCommanded, fireJumpCommanded);
        boolean shieldReady = player != null
                && player.getOffHandStack().isOf(Items.SHIELD)
                && !player.getItemCooldownManager().isCoolingDown(player.getOffHandStack());
        boolean attackReachable = live && client.interactionManager != null
                && player.squaredDistanceTo(threat) <= FIRE_DEFENSE_ATTACK_REACH_SQUARED;
        float cooldown = player == null ? 0.0F : player.getAttackCooldownProgress(0.5F);
        FireDefenseAction defense = chooseFireDefenseAction(
                stationary, live, shieldReady, attackReachable, cooldown);

        if (defense == FireDefenseAction.NONE) {
            stopFireDefenseShield();
            fireDefenseMode = "none";
            fireDefenseTargetUuid = "";
            fireDefenseTargetType = "";
            return;
        }

        String targetUuid = threat.getUuidAsString();
        String targetType = EntityType.getId(threat.getType()).toString();
        if (defense == FireDefenseAction.COUNTER_ATTACK) {
            // A failed bucket placement deliberately keeps its hotbar slot
            // prepared. Restore the pre-bucket weapon before the bounded
            // strike, then let the next fire tick retry the countermeasure.
            stopFireDefenseShield();
            restoreLavaBucket();
            faceToward(threat.getEyePos());
            double distance = player.distanceTo(threat);
            if (entityAttacks.attack(
                    threat,
                    protectionAttackPurpose(threat),
                    "fire-defense-counterattack").issued()) {
                player.swingHand(Hand.MAIN_HAND);
                recordFireDefenseCounterattack(threat, cooldown, distance, now);
                fireDefenseMode = "counterattack";
            } else {
                fireDefenseMode = "relationship-denied";
            }
            fireDefenseTargetUuid = targetUuid;
            fireDefenseTargetType = targetType;
            return;
        }

        if (holdFireDefenseShield(threat)) {
            fireDefenseMode = "shield-hold";
            fireDefenseTargetUuid = targetUuid;
            fireDefenseTargetType = targetType;
        } else {
            fireDefenseMode = "none";
            fireDefenseTargetUuid = "";
            fireDefenseTargetType = "";
        }
    }

    private static Vec3d fireCenter(FireEscapePlanner.Point point) {
        return new Vec3d(point.x() + 0.5, point.y() + 0.1, point.z() + 0.5);
    }

    private static FireEscapePlanner.Point firePoint(BlockPos position) {
        return new FireEscapePlanner.Point(position.getX(), position.getY(), position.getZ());
    }

    private static boolean fireCellTraversable(FireEscapePlanner.Cell cell) {
        return cell == FireEscapePlanner.Cell.WATER
                || cell == FireEscapePlanner.Cell.SAFE
                || cell == FireEscapePlanner.Cell.MARGIN
                || cell == FireEscapePlanner.Cell.NEAR_HAZARD;
    }

    private SurvivalExecution executeEating() {
        beginDirectMovementFrame();
        var player = client.player;
        if (player == null || client.interactionManager == null) {
            restoreFoodSlot();
            return SurvivalExecution.NO_USABLE_FOOD;
        }
        if (player.getHungerManager().getFoodLevel() >= 19) {
            restoreFoodSlot();
            return SurvivalExecution.SATIATED_WAIT;
        }
        long now = monotonicMillis();
        long foodFingerprint = foodInventoryFingerprint(player);
        if (foodRecoveryGate.blocks(
                now,
                player.getHungerManager().getFoodLevel(),
                foodFingerprint)) {
            releaseFoodUseInput();
            return SurvivalExecution.NO_USABLE_FOOD;
        }
        if (foodUseSession.failed()) {
            // The cooldown or a meaningful state change reopened recovery. The
            // old terminal attempt may now be explicitly replaced once.
            foodUseSession.reset();
        }
        if (!foodPrepared) {
            foodPrepared = true;
            foodPreviousSlot = player.getInventory().getSelectedSlot();
        }

        if (!foodChoicePrepared && !prepareFoodChoice()) {
            restoreFoodSlot();
            return SurvivalExecution.NO_USABLE_FOOD;
        }
        ItemStack selected = selectedFoodStack();
        HeldUseSession.Decision use = foodUseSession.observe(
                now,
                player.getHungerManager().getFoodLevel(),
                selected.isEmpty() ? 0 : selected.getCount(),
                player.isUsingItem());

        if (use.state() == HeldUseSession.State.CONSUMED) {
            releaseFoodUseInput();
            if (foodChoiceConsumesReservation) {
                emergencyFoodReservation.recordConsumption(
                        player.getHealth(), player.getHungerManager().getFoodLevel());
            }
            clearFoodChoice();
            if (player.getHungerManager().getFoodLevel() >= 19) {
                restoreFoodSlot();
                return SurvivalExecution.SATIATED_WAIT;
            }
            if (!prepareFoodChoice()) {
                restoreFoodSlot();
                return SurvivalExecution.NO_USABLE_FOOD;
            }
            // Leave one clean client tick between food items. The next call will
            // press the real use key for the newly selected stack.
            return SurvivalExecution.EATING;
        }

        FoodAvailability currentAvailability = foodAvailability(selected, player);
        if (use.state() == HeldUseSession.State.FAILED
                || !currentAvailability.eligible()
                || (!foodChoiceConsumesReservation && currentAvailability.reservedFallback())) {
            foodUseSession.fail();
            foodRecoveryGate.recordFailure(
                    now,
                    player.getHungerManager().getFoodLevel(),
                    foodFingerprint);
            latchFoodUseFailure();
            return SurvivalExecution.NO_USABLE_FOOD;
        }

        // Press before the one explicit interaction so Minecraft's normal input
        // loop cannot immediately cancel the acknowledged use. The interaction
        // starts exactly once per HeldUseSession and therefore still works when
        // a transient GameMenuScreen prevents ordinary key-event processing.
        MinecraftActuatorGateway.UseFrame useFrame = setFoodUsePressed(use.holdUseKey());
        if (shouldStartHeldUse(
                use.startUseAction(),
                use.holdUseKey(),
                useFrame != null && useFrame.started())) {
            String consumedItem = itemId(selected);
            int countBeforeUse = inventoryCount(consumedItem);
            int hungerBeforeUse = player.getHungerManager().getFoodLevel();
            var interaction = client.interactionManager.interactItem(player, foodHand);
            if (!interaction.isAccepted()) {
                foodUseSession.fail();
                foodRecoveryGate.recordFailure(
                        now,
                        player.getHungerManager().getFoodLevel(),
                        foodFingerprint);
                latchFoodUseFailure();
                return SurvivalExecution.NO_USABLE_FOOD;
            }
            foodCompletionFence.begin(
                    now, consumedItem, countBeforeUse, hungerBeforeUse);
        }
        return SurvivalExecution.EATING;
    }

    /** One survival owner holds the meal and a verified local escape segment. */
    private void continueEatingEscape(Entity threat) {
        var player = client.player;
        if (player == null || threat == null || !threat.isAlive() || threat.isRemoved()
                || player.isTouchingWater() || player.isInLava()) {
            committedRetreatRoute = null;
            return;
        }
        RetreatRoute route = committedRetreatRoute;
        if (player.isOnGround()) {
            if (route != null && (retreatRouteSegmentCompleted(
                    route.originX(), route.originZ(), route.dx(), route.dz(),
                    route.landingBlockX(), route.landingBlockZ(),
                    player.getX(), player.getZ(), player.getBlockX(), player.getBlockZ())
                    || retreatRoutePhysicallyBlocked(player.horizontalCollision,
                    Math.hypot(player.getVelocity().x, player.getVelocity().z)))) {
                route = null;
            }
            if (route == null) {
                route = findRetreatRoute(player.getX() - threat.getX(),
                        player.getZ() - threat.getZ(), Set.of(), true, false);
            }
        } else if (route != null && !airborneRetreatMovementAllowed(route.kind(),
                player.getBlockX(), player.getBlockZ(),
                route.landingBlockX(), route.landingBlockZ())) {
            route = null;
        }
        committedRetreatRoute = route;
        if (route != null) {
            faceRetreatRoute(route);
            // Vanilla owns the eating slowdown. Do not switch weapons, attack,
            // restart held use or run a competing native route to hide it.
            commandMovement(true, false, false, retreatJumpCommanded(route.kind()), false);
        }
    }

    private boolean prepareFoodChoice() {
        var player = client.player;
        if (player == null) return false;
        FoodChoice choice = bestFoodChoice();
        if (choice == null) {
            ItemStack stored = bestStoredFood();
            if (!stored.isEmpty()) {
                int selected = player.getInventory().getSelectedSlot();
                int hotbar = -1;
                for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
                    if (player.getInventory().getStack(slot).isEmpty()) {
                        hotbar = slot;
                        break;
                    }
                }
                if (hotbar < 0) hotbar = selected == 8 ? 7 : 8;
                String itemId = Registries.ITEM.getId(stored.getItem()).toString();
                ClientInventoryController.ClickResult staged =
                        survivalInventory.moveToHotbar(itemId, hotbar, System.currentTimeMillis());
                if (staged == ClientInventoryController.ClickResult.CLICKED
                        || staged == ClientInventoryController.ClickResult.WAITING) return false;
                choice = bestFoodChoice();
            }
        }
        if (choice == null) return false;
        foodHand = choice.hand();
        foodSlot = choice.slot();
        foodChoiceConsumesReservation = choice.reservedFallback();
        if (foodHand == Hand.MAIN_HAND) selectHotbarSlotNow(foodSlot);
        foodChoicePrepared = true;
        ItemStack selected = selectedFoodStack();
        if (selected.isEmpty()) {
            clearFoodChoice();
            return false;
        }
        foodUseSession.begin(
                monotonicMillis(),
                player.getHungerManager().getFoodLevel(),
                selected.getCount());
        return true;
    }

    private ItemStack selectedFoodStack() {
        var player = client.player;
        if (player == null || !foodChoicePrepared || foodHand == null) return ItemStack.EMPTY;
        return foodHand == Hand.OFF_HAND
                ? player.getOffHandStack()
                : player.getInventory().getStack(foodSlot);
    }

    private void clearFoodChoice() {
        foodChoicePrepared = false;
        foodChoiceConsumesReservation = false;
        foodHand = null;
        foodSlot = -1;
        foodUseSession.reset();
    }

    private FoodChoice bestFoodChoice() {
        var player = client.player;
        if (player == null) return null;
        FoodChoice best = null;
        ItemStack offhand = player.getOffHandStack();
        FoodAvailability offhandAvailability = foodAvailability(offhand, player);
        if (offhandAvailability.eligible()) {
            best = new FoodChoice(
                    Hand.OFF_HAND, -1, foodScore(offhand), offhandAvailability.reservedFallback());
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            FoodAvailability availability = foodAvailability(stack, player);
            if (!availability.eligible()) continue;
            FoodChoice candidate = new FoodChoice(
                    Hand.MAIN_HAND, slot, foodScore(stack), availability.reservedFallback());
            if (betterFoodChoice(candidate, best)) best = candidate;
        }
        return best;
    }

    private static boolean betterFoodChoice(FoodChoice candidate, FoodChoice current) {
        if (current == null) return true;
        if (candidate.reservedFallback() != current.reservedFallback()) {
            return !candidate.reservedFallback();
        }
        return candidate.score() > current.score();
    }

    private ItemStack bestStoredFood() {
        var player = client.player;
        if (player == null) return ItemStack.EMPTY;
        ItemStack best = ItemStack.EMPTY;
        float bestScore = Float.NEGATIVE_INFINITY;
        boolean bestReservedFallback = true;
        var inventory = player.getInventory();
        for (int slot = PlayerInventory.getHotbarSize(); slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            FoodAvailability availability = foodAvailability(stack, player);
            if (!availability.eligible()) continue;
            float score = foodScore(stack);
            if (best.isEmpty()
                    || (bestReservedFallback && !availability.reservedFallback())
                    || (bestReservedFallback == availability.reservedFallback() && score > bestScore)) {
                best = stack;
                bestScore = score;
                bestReservedFallback = availability.reservedFallback();
            }
        }
        return best;
    }

    private FoodAvailability foodAvailability(
            ItemStack stack,
            net.minecraft.entity.player.PlayerEntity player) {
        boolean critical = player.getHungerManager().getFoodLevel() <= 2
                || player.getHealth() <= player.getMaxHealth() * 0.3F;
        boolean reservedFallbackAlreadyConsumed = !emergencyFoodReservation.allowsConsumption(
                critical,
                player.getHealth(),
                player.getHungerManager().getFoodLevel());
        if (!baseUsableFood(stack, player)) return FoodAvailability.UNAVAILABLE;
        String itemId = itemId(stack);
        int reserved = foodReservations.getOrDefault(itemId, 0);
        return reservationAvailability(
                inventoryCount(itemId), reserved, critical, reservedFallbackAlreadyConsumed);
    }

    private int inventoryCount(String itemId) {
        var player = client.player;
        if (player == null) return 0;
        int count = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack candidate = inventory.getStack(slot);
            if (!candidate.isEmpty() && itemId(candidate).equals(itemId)) count += candidate.getCount();
        }
        return count;
    }

    private long foodInventoryFingerprint(net.minecraft.entity.player.PlayerEntity player) {
        TreeMap<String, Integer> totals = new TreeMap<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()
                    || stack.get(DataComponentTypes.FOOD) == null
                    || !stack.contains(DataComponentTypes.CONSUMABLE)) {
                continue;
            }
            totals.merge(itemId(stack), stack.getCount(), Integer::sum);
        }
        long fingerprint = 1_469_598_103_934_665_603L;
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            fingerprint ^= entry.getKey().hashCode();
            fingerprint *= 1_099_511_628_211L;
            fingerprint ^= entry.getValue();
            fingerprint *= 1_099_511_628_211L;
        }
        fingerprint ^= foodReservations.hashCode();
        return fingerprint;
    }

    private static boolean baseUsableFood(
            ItemStack stack,
            net.minecraft.entity.player.PlayerEntity player) {
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        return !stack.isEmpty() && food != null && stack.contains(DataComponentTypes.CONSUMABLE)
                && safeAutomaticFood(stack, player)
                && player.canConsume(food.canAlwaysEat());
    }

    private static String itemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString().toLowerCase(Locale.ROOT);
    }

    static String normalizeItemId(String itemId) {
        String normalized = itemId.trim().toLowerCase(Locale.ROOT);
        return normalized.indexOf(':') >= 0 ? normalized : "minecraft:" + normalized;
    }

    static FoodAvailability reservationAvailability(
            int totalCount,
            int reservedCount,
            boolean criticalSurvival,
            boolean reservedFallbackAlreadyConsumed) {
        if (totalCount <= 0) return FoodAvailability.UNAVAILABLE;
        if (reservedCount <= 0 || totalCount > reservedCount) return FoodAvailability.UNRESERVED;
        return criticalSurvival && !reservedFallbackAlreadyConsumed
                ? FoodAvailability.RESERVED_FALLBACK
                : FoodAvailability.UNAVAILABLE;
    }

    private static boolean safeAutomaticFood(
            ItemStack stack,
            net.minecraft.entity.player.PlayerEntity player) {
        if (stack.isOf(Items.PUFFERFISH)
                || stack.isOf(Items.SPIDER_EYE)
                || stack.isOf(Items.POISONOUS_POTATO)
                || stack.isOf(Items.CHORUS_FRUIT)
                || stack.isOf(Items.SUSPICIOUS_STEW)) {
            return false;
        }
        if (stack.isOf(Items.GOLDEN_APPLE) || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)) {
            return player.getHungerManager().getFoodLevel() <= 4
                    || player.getHealth() <= player.getMaxHealth() * 0.3F;
        }
        if (stack.isOf(Items.ROTTEN_FLESH) || stack.isOf(Items.CHICKEN)) {
            return criticalRiskyFoodAllowed(
                    player.getHungerManager().getFoodLevel(),
                    player.getHealth(),
                    player.getMaxHealth());
        }
        return true;
    }

    /**
     * Hunger-side-effect food is a last resort, but near-death health is itself an emergency.
     * The retained playtest had 3.5/20 health, hunger 5, and two rotten flesh; requiring hunger
     * four ignored the only available path back toward regeneration.
     */
    static boolean criticalRiskyFoodAllowed(
            int foodLevel,
            float health,
            float maximumHealth) {
        return foodLevel <= 4
                || (maximumHealth > 0.0F && health <= maximumHealth * 0.3F);
    }

    private static float foodScore(ItemStack stack) {
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        if (food == null) return -1;
        return automaticFoodScore(
                food.nutrition(),
                food.saturation(),
                stack.isOf(Items.ROTTEN_FLESH) || stack.isOf(Items.CHICKEN),
                stack.isOf(Items.GOLDEN_APPLE),
                stack.isOf(Items.ENCHANTED_GOLDEN_APPLE));
    }

    static float automaticFoodScore(
            float nutrition,
            float saturation,
            boolean riskySideEffects,
            boolean goldenApple,
            boolean enchantedGoldenApple) {
        float score = nutrition + saturation * 2.0F;
        // Eligibility does not make risky emergency food preferable. Every ordinary safe food
        // remains ahead of rotten flesh/raw chicken; these win only when nothing safer can be
        // consumed under the reservation and inventory rules above.
        if (riskySideEffects) score -= 100.0F;
        if (enchantedGoldenApple) score -= 100.0F;
        else if (goldenApple) score -= 25.0F;
        return score;
    }

    private SurvivalExecution executeFallClutch() {
        beginDirectMovementFrame();
        long now = System.currentTimeMillis();
        var player = client.player;
        if (player == null || client.interactionManager == null) {
            restoreClutchSlot();
            fallTechniqueDetail = "waiting for a loaded player and interaction manager";
            return SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
        }
        if (client.world == null) {
            restoreClutchSlot();
            fallTechniqueDetail = "waiting for a loaded world";
            return SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
        }
        if (clutchSession.snapshot().phase() != WaterClutchSession.Phase.IDLE) {
            return tickPinnedWaterClutch(now);
        }
        MinecraftFallTechniqueObserver.Snapshot observed = fallTechniques.observe();
        if (!observed.observation().falling()) {
            restoreClutchSlot();
            fallTechniqueDetail = "fall episode ended with neutral controls";
            return SurvivalExecution.WATER_BUCKET_COMPLETE;
        }
        lastFallTechniqueSelection = observed.inventory().selection()
                .name().toLowerCase(Locale.ROOT);
        lastFallTechniqueReason = observed.inventory().reason();
        return switch (observed.inventory().selection()) {
            case EXISTING_WATER_LANDING -> steerToExistingWater(observed);
            case WATER_BUCKET_CLUTCH -> beginPinnedWaterClutch(observed, now);
            case DECLINE -> {
                fallTechniqueDetail = observed.inventory().reason();
                yield SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
            }
            case NONE -> {
                fallTechniqueDetail = "no dangerous fall is active";
                yield SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
            }
        };
    }

    private SurvivalExecution steerToExistingWater(
            MinecraftFallTechniqueObserver.Snapshot observed) {
        BlockPos landing = observed.existingWaterLanding();
        if (landing == null || client.player == null) {
            fallTechniqueDetail = "existing-water selection lost its exact landing";
            return SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
        }
        existingWaterSteeringTicks++;
        prepareClutchView();
        Vec3d center = Vec3d.ofCenter(landing);
        double dx = center.x - client.player.getX();
        double dz = center.z - client.player.getZ();
        double horizontalSquared = dx * dx + dz * dz;
        if (horizontalSquared > 0.20 * 0.20) {
            faceExactToward(new Vec3d(center.x, client.player.getEyeY(), center.z));
            commandMovement(true, false, true, false, false);
        }
        fallTechniqueDetail = "steering toward loaded existing water at "
                + landing.getX() + ' ' + landing.getY() + ' ' + landing.getZ()
                + "; no bucket placement requested";
        return SurvivalExecution.EXISTING_WATER_LANDING;
    }

    private SurvivalExecution beginPinnedWaterClutch(
            MinecraftFallTechniqueObserver.Snapshot observed,
            long now) {
        if (observed.bucketHand() == null || observed.clutchSupport() == null
                || observed.clutchPlacement() == null || observed.clutchHit() == null) {
            fallTechniqueDetail = "available clutch capability omitted its exact actuator target";
            return SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
        }
        prepareClutchView();
        clutchHand = observed.bucketHand();
        clutchBucketSlot = observed.bucketHotbarSlot();
        clutchSupport = observed.clutchSupport().toImmutable();
        clutchPlacement = observed.clutchPlacement().toImmutable();
        lastClutchPlacement = clutchPlacement.getX() + " "
                + clutchPlacement.getY() + " " + clutchPlacement.getZ();
        clutchHit = observed.clutchHit();
        String key = currentDimension() + ':' + clutchSupport.asLong()
                + ':' + clutchPlacement.asLong();
        if (!clutchSession.begin(key, now)) {
            fallTechniqueDetail = "a different clutch transaction already owns the episode";
            return SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
        }
        return tickPinnedWaterClutch(now);
    }

    private SurvivalExecution tickPinnedWaterClutch(long now) {
        var player = client.player;
        boolean waterPresent = fallTechniques.placedWaterPresent(clutchPlacement);
        WaterClutchSession.Observation observation = new WaterClutchSession.Observation(
                player != null && player.isAlive(),
                fallTechniques.pinnedClutchStillValid(
                        clutchSupport, clutchPlacement, clutchHit),
                waterPresent,
                waterPresent,
                fallTechniques.safelyLandedIn(clutchPlacement),
                fallTechniques.recoveryReachable(clutchPlacement),
                fallTechniques.waterBucketRecovered(clutchHand, clutchBucketSlot));
        WaterClutchSession.Action action = clutchSession.advance(now, observation);
        return switch (action) {
            case PLACE_WATER -> requestPinnedWaterPlacement(now);
            case RECOVER_WATER -> requestPinnedWaterRecovery(now);
            case WAIT -> {
                fallTechniqueDetail = clutchSession.snapshot().detail();
                yield clutchSession.snapshot().phase()
                        == WaterClutchSession.Phase.WATER_PLACED
                        ? SurvivalExecution.WATER_BUCKET_WATER_CONFIRMED
                        : SurvivalExecution.WATER_BUCKET_WAITING_ACK;
            }
            case COMPLETE -> {
                fallTechniqueDetail = clutchSession.snapshot().detail();
                restoreClutchView();
                yield SurvivalExecution.WATER_BUCKET_COMPLETE;
            }
            case FAILED -> {
                fallTechniqueDetail = clutchSession.snapshot().detail();
                restoreClutchView();
                yield SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
            }
        };
    }

    private SurvivalExecution requestPinnedWaterPlacement(long now) {
        var player = client.player;
        if (player == null || clutchHand == null || clutchHit == null
                || !heldClutchItemIs(Items.WATER_BUCKET)) {
            clutchSession.abort("pinned water bucket disappeared before placement");
            fallTechniqueDetail = clutchSession.snapshot().detail();
            restoreClutchView();
            return SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
        }
        if (clutchHand == Hand.MAIN_HAND) selectHotbarSlotNow(clutchBucketSlot);
        faceExactToward(clutchHit.getPos());
        BlockHitResult exactRay = raycastPinnedClutchSupport();
        boolean exactRayMatches = exactRay != null
                && exactRay.getType() == HitResult.Type.BLOCK
                && exactRay.getBlockPos().equals(clutchHit.getBlockPos())
                && exactRay.getSide() == clutchHit.getSide()
                && clutchPlacement.equals(
                        fallTechniques.waterBucketPlacementFor(exactRay));
        boolean fluidAllowed = actuators.allowsEmergencyFluidMutation(clutchPlacement);
        if (!exactRayMatches || !fluidAllowed) {
            String rayDetail = exactRay == null
                    ? "ray unavailable"
                    : exactRay.getType().name().toLowerCase(Locale.ROOT)
                    + " at " + exactRay.getBlockPos().getX() + ' '
                    + exactRay.getBlockPos().getY() + ' '
                    + exactRay.getBlockPos().getZ() + " side="
                    + exactRay.getSide().name().toLowerCase(Locale.ROOT);
            clutchSession.abort(!exactRayMatches
                    ? "pinned clutch support line resolved " + rayDetail
                    : "final clutch fluid authority changed");
            fallTechniqueDetail = clutchSession.snapshot().detail();
            restoreClutchView();
            return SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
        }
        boolean accepted;
        try {
            var result = client.interactionManager.interactItem(player, clutchHand);
            player.swingHand(clutchHand);
            accepted = result.isAccepted();
        } catch (RuntimeException error) {
            accepted = false;
            logger.warn("Pinned water clutch interaction failed safely: {}", error.getMessage());
        }
        clutchSession.recordPlacementRequest(accepted, now);
        fallTechniqueDetail = clutchSession.snapshot().detail();
        return accepted
                ? SurvivalExecution.WATER_BUCKET_ATTEMPTED
                : SurvivalExecution.FALL_TECHNIQUE_UNAVAILABLE;
    }

    /**
     * Verifies the immutable pinned face from the current eye position. The
     * observer has already selected that exact point; rebuilding a second
     * full-range ray from camera angles can disagree at the final reachable
     * fall tick and must not discard the pinned transaction.
     */
    private BlockHitResult raycastPinnedClutchSupport() {
        var player = client.player;
        if (player == null || client.world == null || clutchHit == null
                || clutchSupport == null) return null;
        Vec3d start = player.getEyePos();
        Vec3d target = clutchHit.getPos();
        Vec3d delta = target.subtract(start);
        if (delta.lengthSquared() < 1.0E-8) return null;
        // The observer pinned the actual first outline hit, including
        // replaceable vegetation. Continue through that exact hit so the
        // verified mutation coordinate and BucketItem use agree.
        Vec3d end = target.add(delta.normalize().multiply(0.10));
        return client.world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player));
    }

    private SurvivalExecution requestPinnedWaterRecovery(long now) {
        var player = client.player;
        if (player == null || clutchHand == null || clutchPlacement == null
                || !heldClutchItemIs(Items.BUCKET)) {
            clutchSession.recordRecoveryRequest(false, now);
            fallTechniqueDetail = clutchSession.snapshot().detail();
            restoreClutchView();
            return SurvivalExecution.WATER_BUCKET_COMPLETE;
        }
        if (clutchHand == Hand.MAIN_HAND) selectHotbarSlotNow(clutchBucketSlot);
        faceExactToward(Vec3d.ofCenter(clutchPlacement));
        Vec3d start = player.getEyePos();
        Vec3d rotation = player.getRotationVector(player.getPitch(), player.getYaw());
        BlockHitResult hit = client.world.raycast(new RaycastContext(
                start,
                start.add(rotation.multiply(player.getBlockInteractionRange())),
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.SOURCE_ONLY,
                player));
        boolean exact = hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(clutchPlacement)
                && actuators.allowsEmergencyFluidMutation(clutchPlacement);
        boolean accepted = false;
        if (exact) {
            try {
                var result = client.interactionManager.interactItem(player, clutchHand);
                player.swingHand(clutchHand);
                accepted = result.isAccepted();
            } catch (RuntimeException error) {
                logger.warn("Pinned clutch-water recovery failed safely: {}", error.getMessage());
            }
        }
        clutchSession.recordRecoveryRequest(accepted, now);
        fallTechniqueDetail = clutchSession.snapshot().detail();
        return accepted
                ? SurvivalExecution.WATER_BUCKET_RECOVERING
                : SurvivalExecution.WATER_BUCKET_COMPLETE;
    }

    private boolean heldClutchItemIs(net.minecraft.item.Item item) {
        if (client.player == null || clutchHand == null) return false;
        if (clutchHand == Hand.OFF_HAND) return client.player.getOffHandStack().isOf(item);
        return clutchBucketSlot >= 0
                && clutchBucketSlot < PlayerInventory.getHotbarSize()
                && client.player.getInventory().getStack(clutchBucketSlot).isOf(item);
    }

    private void prepareClutchView() {
        if (clutchPrepared || client.player == null) return;
        clutchPrepared = true;
        clutchPreviousSlot = client.player.getInventory().getSelectedSlot();
        clutchPreviousPitch = client.player.getPitch();
        clutchPreviousYaw = client.player.getYaw();
    }

    public InventoryTechniquePolicy.CapabilityInventory fallTechniqueInventory() {
        return fallTechniques.observe().inventory();
    }

    public String fallTechniqueDetail() {
        return fallTechniqueDetail;
    }

    /** Loss-resistant transaction facts for focused live acceptance. */
    public FallTechniqueSnapshot fallTechniqueSnapshot() {
        WaterClutchSession.Snapshot active = clutchSession.snapshot();
        WaterClutchSession.Snapshot evidence = clutchSession.evidenceSnapshot();
        return new FallTechniqueSnapshot(
                lastFallTechniqueSelection,
                lastFallTechniqueReason,
                fallTechniqueDetail,
                active.phase().name().toLowerCase(Locale.ROOT),
                evidence.phase().name().toLowerCase(Locale.ROOT),
                evidence.targetKey(),
                lastClutchPlacement,
                evidence.placementRequests(),
                evidence.recoveryRequests(),
                evidence.recovered(),
                existingWaterSteeringTicks,
                clutchPrepared);
    }

    public String waterPlanDetail() {
        return waterPlanDetail;
    }

    public record FallTechniqueSnapshot(
            String selection,
            String reason,
            String detail,
            String activePhase,
            String evidencePhase,
            String target,
            String placement,
            int placementRequests,
            int recoveryRequests,
            boolean recovered,
            long existingWaterSteeringTicks,
            boolean viewPrepared) {
    }

    public AquaticTravelPolicy.Snapshot waterLocomotionSnapshot() {
        return waterLocomotion.snapshot();
    }

    public String lavaPlanDetail() {
        return lavaPlanDetail;
    }

    public String firePlanDetail() {
        return firePlanDetail;
    }

    public String fireDefenseDetail() {
        return switch (fireDefenseMode) {
            case "shield-hold" -> "holding the offhand shield against " + fireDefenseTargetType;
            case "counterattack" -> "cooldown-timed counterattack against " + fireDefenseTargetType;
            default -> "";
        };
    }

    public FireSnapshot fireSnapshot() {
        FireEscapePlanner.BurnSurvivability burn = client.player == null
                ? FireEscapePlanner.assessBurn(0.0F, 0)
                : FireEscapePlanner.assessBurn(
                        client.player.getHealth(), client.player.getFireTicks());
        String target = firePath.isEmpty()
                ? ""
                : firePath.peekFirst().x() + "," + firePath.peekFirst().y() + ","
                        + firePath.peekFirst().z();
        return new FireSnapshot(
                activeSurvivalAction == SurvivalAction.EXTINGUISH_FIRE,
                client.player != null && client.player.isOnFire(),
                fireCommandedAt,
                fireCommandedClientTick,
                fireCommandMode,
                fireForwardCommanded,
                fireJumpCommanded,
                target,
                failedFireWaypoints.size(),
                firePlanDetail,
                fireDefenseMode,
                fireDefenseTargetUuid,
                fireDefenseTargetType,
                FIRE_DEFENSE_SHIELD_USE_OPERATION.equals(retreatShieldUseOperation)
                        && retreatShieldSession.blocking(),
                burn.fireTicks(),
                burn.predictedRemainingHits(),
                burn.predictedDamage(),
                burn.healthReserve(),
                burn.dryHoldSurvivable(),
                fireTerminalKind.name().toLowerCase(Locale.ROOT));
    }

    public LavaSnapshot lavaSnapshot() {
        String target = lavaPath.isEmpty()
                ? ""
                : lavaPath.peekFirst().x() + "," + lavaPath.peekFirst().y() + ","
                        + lavaPath.peekFirst().z();
        return new LavaSnapshot(
                activeSurvivalAction == SurvivalAction.ESCAPE_LAVA,
                client.player != null && client.player.isInLava(),
                lavaCommandedAt,
                lavaCommandedClientTick,
                lavaCommandMode,
                lavaForwardCommanded,
                lavaJumpCommanded,
                target,
                failedLavaWaypoints.size(),
                lavaPlanDetail);
    }

    public String suffocationPlanDetail() {
        return suffocationPlanDetail;
    }

    public SuffocationSnapshot suffocationSnapshot() {
        long now = System.currentTimeMillis();
        String target = suffocationBlock == null
                ? ""
                : suffocationBlock.getX() + "," + suffocationBlock.getY() + ","
                        + suffocationBlock.getZ();
        return new SuffocationSnapshot(
                activeSurvivalAction == SurvivalAction.ESCAPE_SUFFOCATION,
                client.player != null && client.player.isInsideWall(),
                target,
                suffocationDirection == null
                        ? ""
                        : suffocationDirection.name().toLowerCase(Locale.ROOT),
                Math.max(0L, suffocationDeadlineAt - now),
                failedSuffocationDirections.size(),
                failedSuffocationBlocks.size(),
                suffocationPlanDetail);
    }

    public java.util.List<RetreatCounterattackEvent> retreatCounterattackEvents() {
        return java.util.List.copyOf(retreatCounterattacks);
    }

    /** True only during the currently executing, geometry-proven ranged cover window. */
    public boolean verifiedRetreatCoverHoldActive() {
        return retreatCoverHoldActive;
    }

    private void recordRetreatCounterattack(
            Entity threat,
            float cooldown,
            double distance,
            long issuedAtMillis) {
        recordCounterattack(
                threat, cooldown, distance, issuedAtMillis, retreatEpisodeKey);
    }

    /**
     * The retreat already owns the body; only its strike hand changes here.
     * Loadout inventory swaps remain with the acknowledged runtime coordinator.
     * Selection uses the same ordered packet handoff as close combat and never
     * waits, moves a stack, or takes movement/look from the escape frame.
     */
    private boolean issueRetreatCounterattack(
            ControlLease lease, Entity target, long now, String source) {
        var player = client.player;
        if (player == null || target == null || client.interactionManager == null
                || client.getNetworkHandler() == null
                || appliedEpoch != lease.epoch() || !arbiter.isValid(lease, now)
                || !lease.channels().contains(BodyChannel.HOTBAR)
                || !lease.channels().contains(BodyChannel.ATTACK)
                || actuators.heldUseSnapshot().active()) return false;
        if (!selectRetreatCounterattackWeapon()) return false;
        // A block and a sword have different attack speeds. The earlier route
        // policy's block cooldown cannot authorize a freshly selected weapon.
        float cooldown = player.getAttackCooldownProgress(0.5F);
        if (cooldown < 0.9F || player.squaredDistanceTo(target) > RETREAT_ATTACK_REACH_SQUARED
                || !arbiter.isValid(lease, System.currentTimeMillis())) return false;
        double distance = player.distanceTo(target);
        if (!entityAttacks.attack(target, protectionAttackPurpose(target), source).issued()) return false;
        player.swingHand(Hand.MAIN_HAND);
        recordRetreatCounterattack(target, cooldown, distance, now);
        return true;
    }

    private boolean selectRetreatCounterattackWeapon() {
        var inventory = client.player.getInventory();
        for (String weapon : CombatLoadoutCoordinator.CARRIED_WEAPON_ORDER) {
            for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack.isEmpty() || stack.isDamageable()
                        && stack.getMaxDamage() - stack.getDamage() <= 1
                        || !Registries.ITEM.getId(stack.getItem()).toString().equals(weapon)) continue;
                selectHotbarSlotNow(slot);
                return inventory.getSelectedSlot() == slot
                        && client.player.getMainHandStack() == stack;
            }
        }
        // No immediately usable weapon is a reduced-loadout defense, not
        // permission to open a cursor swap or stop an emergency escape.
        return true;
    }

    private void recordFireDefenseCounterattack(
            Entity threat,
            float cooldown,
            double distance,
            long issuedAtMillis) {
        recordCounterattack(
                threat, cooldown, distance, issuedAtMillis, "survival-fire-defense");
    }

    private static EntityDispositionPolicy.Purpose protectionAttackPurpose(Entity target) {
        return target instanceof net.minecraft.entity.player.PlayerEntity
                ? EntityDispositionPolicy.Purpose.RETALIATION
                : EntityDispositionPolicy.Purpose.PROTECTION;
    }

    private void recordCounterattack(
            Entity threat,
            float cooldown,
            double distance,
            long issuedAtMillis,
            String episodeKey) {
        ItemStack selected = client.player == null
                ? ItemStack.EMPTY
                : client.player.getMainHandStack();
        String weapon = selected.isEmpty()
                ? "minecraft:air"
                : Registries.ITEM.getId(selected.getItem()).toString();
        retreatCounterattacks.addLast(new RetreatCounterattackEvent(
                ++retreatCounterattackSequence,
                issuedAtMillis,
                client.player == null ? -1L : client.player.age,
                appliedEpoch,
                episodeKey,
                threat.getUuidAsString(),
                EntityType.getId(threat.getType()).toString(),
                weapon,
                cooldown,
                distance));
        while (retreatCounterattacks.size() > MAX_RETREAT_COUNTERATTACK_EVENTS) {
            retreatCounterattacks.removeFirst();
        }
    }

    public void cancelControls(long invalidatedEpoch, String reason) {
        if (appliedEpoch <= invalidatedEpoch) clear(reason);
    }

    public void clear(String reason) {
        shoreEscapeGoal = null;
        aquaticEscapeActive = false;
        lastShorePlanAt = 0L;
        waterPathToShore = false;
        try {
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException error) {
            logger.warn("Could not clear direct body controls after {}: {}", reason, error.getMessage());
        }
        restoreClutchSlot();
        restoreFoodSlot();
        stopRetreatShield();
        resetRetreatState();
        clearAquaticDefenseIntent();
        cancelBreach();
        resetSuffocationState();
        resetLavaState();
        resetFireState();
        releaseDirectMovementKeys();
        resetWaterState();
        activeSurvivalAction = SurvivalAction.NONE;
        appliedEpoch = -1;
    }

    private void transitionTo(SurvivalAction action) {
        try {
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException ignored) {
        }
        restoreClutchSlot();
        restoreFoodSlot();
        stopRetreatShield();
        resetRetreatState();
        cancelBreach();
        resetSuffocationState();
        resetLavaState();
        resetFireState();
        releaseDirectMovementKeys();
        resetWaterState();
        activeSurvivalAction = action;
    }

    private void resetWaterState() {
        breathingPocketMemory.onActionReset(System.currentTimeMillis());
        waterPath.clear();
        failedWaterWaypoints.clear();
        waterProgressTarget = null;
        bestWaterTargetDistance = Double.POSITIVE_INFINITY;
        lastWaterProgressAt = 0;
        lastWaterPlanAt = 0;
        waterPlanDetail = "searching for breathable space";
        timedOutBreachBlock = null;
        releaseWaterLocomotion(MovementFrameActuator.Cleanup.OWNER_TRANSITION);
    }

    private void resetLavaState() {
        lavaPath.clear();
        failedLavaWaypoints.clear();
        lavaProgressTarget = null;
        bestLavaTargetDistance = Double.POSITIVE_INFINITY;
        lastLavaProgressAt = 0;
        lastLavaPlanAt = 0;
        lavaPlanDetail = "searching for a supported dry lava exit";
        restoreLavaBucket();
        lastLavaBucketAttemptAt = 0;
        lavaCommandedAt = 0L;
        lavaCommandedClientTick = -1L;
        lavaCommandMode = "idle";
        lavaForwardCommanded = false;
        lavaJumpCommanded = false;
    }

    private void resetFireState() {
        stopFireDefenseShield();
        firePath.clear();
        failedFireWaypoints.clear();
        fireProgressTarget = null;
        bestFireTargetDistance = Double.POSITIVE_INFINITY;
        lastFireProgressAt = 0L;
        lastFirePlanAt = 0L;
        fireCommandedAt = 0L;
        fireCommandedClientTick = -1L;
        fireCommandMode = "idle";
        fireForwardCommanded = false;
        fireJumpCommanded = false;
        firePlanDetail = "searching for extinguishing water and the safest dry fallback";
        fireTerminalKind = FireEscapePlanner.TerminalKind.NONE;
        fireDefenseMode = "none";
        fireDefenseTargetUuid = "";
        fireDefenseTargetType = "";
        restoreLavaBucket();
    }

    private void cancelBreach() {
        emergencyBreak.cancel();
        if (breachPrepared && client.player != null) {
            try {
                if (breachPreviousSlot >= 0) {
                    selectHotbarSlotNow(breachPreviousSlot);
                }
            } catch (RuntimeException error) {
                logger.warn("Could not restore controls after a water escape breach: {}", error.getMessage());
            }
        }
        breachBlock = null;
        breachPreviousSlot = -1;
        breachPrepared = false;
        breachDeadlineAt = 0;
    }

    private void restoreFoodSlot() {
        boolean slotSafe = releaseFoodUseInput();
        if (slotSafe && foodPrepared && client.player != null && foodPreviousSlot >= 0) {
            selectHotbarSlotNow(foodPreviousSlot);
        }
        foodPreviousSlot = -1;
        foodPrepared = false;
        clearFoodChoice();
    }

    private void latchFoodUseFailure() {
        boolean slotSafe = releaseFoodUseInput();
        foodChoicePrepared = false;
        foodChoiceConsumesReservation = false;
        foodHand = null;
        foodSlot = -1;
        if (slotSafe && foodPrepared && client.player != null && foodPreviousSlot >= 0) {
            selectHotbarSlotNow(foodPreviousSlot);
        }
        foodPreviousSlot = -1;
        foodPrepared = false;
        // Intentionally preserve HeldUseSession.FAILED. Only an explicit action
        // transition/cancellation calls clearFoodChoice() and permits a retry.
    }

    private MinecraftActuatorGateway.UseFrame setFoodUsePressed(boolean pressed) {
        MinecraftActuatorGateway.UseFrame frame = null;
        if (pressed) {
            frame = actuators.holdUseFrame(FOOD_USE_OPERATION);
            if (frame.active()) foodUseGeneration = frame.generation();
        } else {
            if (foodUseGeneration > 0L) {
                actuators.releaseHeldUse(FOOD_USE_OPERATION, foodUseGeneration);
            }
            foodUseGeneration = 0L;
        }
        try {
            // Clear any legacy Baritone pulse left by an earlier controller.
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        } catch (RuntimeException ignored) {
        }
        return frame;
    }

    private boolean releaseFoodUseInput() {
        setFoodUsePressed(false);
        return !actuators.heldUseSnapshot().active();
    }

    private void stopRetreatShield() {
        if (!retreatShieldPrepared && retreatShieldUseGeneration <= 0L) {
            retreatShieldSession.releaseAttempt();
            return;
        }
        if (retreatShieldUseGeneration > 0L) {
            String operation = retreatShieldUseOperation.isBlank()
                    ? RETREAT_SHIELD_USE_OPERATION
                    : retreatShieldUseOperation;
            actuators.releaseHeldUse(
                    operation, retreatShieldUseGeneration);
        }
        retreatShieldUseGeneration = 0L;
        try {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        } catch (RuntimeException ignored) {
        }
        if (!actuators.heldUseSnapshot().active()
                && client.player != null && retreatShieldPreviousSlot >= 0) {
            selectHotbarSlotNow(retreatShieldPreviousSlot);
        }
        retreatShieldPrepared = false;
        retreatShieldHand = null;
        retreatShieldSlot = -1;
        retreatShieldPreviousSlot = -1;
        retreatShieldUseOperation = "";
        // An ordinary release (for example, a cooldown-timed counterattack)
        // may start one later fixed attempt. A failed start stays latched until
        // the protection episode/action is explicitly reset.
        retreatShieldSession.releaseAttempt();
    }

    private void stopFireDefenseShield() {
        if (FIRE_DEFENSE_SHIELD_USE_OPERATION.equals(retreatShieldUseOperation)) {
            stopRetreatShield();
        }
    }

    private void restoreClutchSlot() {
        restoreClutchView();
        clutchBucketSlot = -1;
        clutchHand = null;
        clutchSupport = null;
        clutchPlacement = null;
        clutchHit = null;
        clutchSession.reset();
    }

    private void restoreClutchView() {
        if (clutchPrepared && client.player != null) {
            try {
                if (clutchPreviousSlot >= 0) {
                    selectHotbarSlotNow(clutchPreviousSlot);
                }
                client.player.setPitch(clutchPreviousPitch);
                client.player.setYaw(clutchPreviousYaw);
                client.player.setHeadYaw(clutchPreviousYaw);
                client.player.setBodyYaw(clutchPreviousYaw);
            } catch (RuntimeException error) {
                logger.warn("Could not restore the selected hotbar slot after fall recovery: {}", error.getMessage());
            }
        }
        clutchPreviousSlot = -1;
        clutchPrepared = false;
    }

    /** Clears last frame's direct movement before this frame chooses an action. */
    private void beginDirectMovementFrame() {
        try {
            baritone.getInputOverrideHandler().clearAllKeys();
        } catch (RuntimeException ignored) {
        }
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        movementKeysOwned = false;
    }

    /** Writes both Baritone's override and Minecraft's real held keys. */
    private void commandMovement(
            boolean forward,
            boolean backward,
            boolean sprint,
            boolean jump,
            boolean sneak) {
        commandMovement(forward, backward, false, false, sprint, jump, sneak);
    }

    private void commandMovement(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean sprint,
            boolean jump,
            boolean sneak) {
        var input = baritone.getInputOverrideHandler();
        input.setInputForceState(Input.MOVE_FORWARD, forward);
        input.setInputForceState(Input.MOVE_BACK, backward);
        input.setInputForceState(Input.MOVE_LEFT, left);
        input.setInputForceState(Input.MOVE_RIGHT, right);
        input.setInputForceState(Input.SPRINT, sprint);
        input.setInputForceState(Input.JUMP, jump);
        input.setInputForceState(Input.SNEAK, sneak);
        client.options.forwardKey.setPressed(forward);
        client.options.backKey.setPressed(backward);
        client.options.leftKey.setPressed(left);
        client.options.rightKey.setPressed(right);
        client.options.sprintKey.setPressed(sprint);
        client.options.jumpKey.setPressed(jump);
        client.options.sneakKey.setPressed(sneak);
        movementKeysOwned = forward || backward || left || right || sprint || jump || sneak;
        // Sprint is a player state, not just a one-frame key. Explicitly clear
        // it when an edge/drop/defensive frame revokes acceleration; otherwise
        // Minecraft retains sprint while forward remains held.
        if (client.player != null) client.player.setSprinting(sprint);
    }

    private void releaseDirectMovementKeys() {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        if (movementKeysOwned && client.player != null) {
            client.player.setSprinting(false);
        }
        movementKeysOwned = false;
    }

    private boolean hasDeepWaterBelow(BlockPos feet) {
        return client.world != null
                && client.world.getFluidState(feet).isIn(FluidTags.WATER)
                && client.world.getFluidState(feet.down()).isIn(FluidTags.WATER);
    }

    private AquaticRouteEnvironmentPolicy.DeepWaterEntry verifiedAquaticEntry(
            double routeX,
            double routeZ) {
        var player = client.player;
        if (player == null || client.world == null) {
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
        return selectAquaticDeepWaterEntry(
                player.getX(), player.getZ(), routeX, routeZ, candidates);
    }

    static AquaticRouteEnvironmentPolicy.DeepWaterEntry selectAquaticDeepWaterEntry(
            double playerX,
            double playerZ,
            double routeX,
            double routeZ,
            List<AquaticRouteEnvironmentPolicy.DeepWaterCandidate> candidates) {
        return AquaticRouteEnvironmentPolicy.selectDeepWaterEntry(
                playerX, playerZ, routeX, routeZ, candidates);
    }

    private boolean hasWaterCeiling(net.minecraft.client.network.ClientPlayerEntity player) {
        if (client.world == null) return false;
        BlockPos head = BlockPos.ofFloored(
                player.getX(), player.getY() + 1.65, player.getZ());
        return !client.world.getBlockState(head)
                .getCollisionShape(client.world, head)
                .isEmpty();
    }

    private boolean movementAuthorityValid(long nowMillis) {
        MovementAuthority binding = movementAuthority;
        return binding != null && binding.authority().isValid(
                binding.action(), binding.parent(), binding.clientTick(), nowMillis);
    }

    private boolean submitWaterFrame(MovementFrame frame, long nowMillis) {
        MovementAuthority binding = movementAuthority;
        if (binding == null) return false;
        try {
            movement.submit(
                    binding.authority(), binding.parent(), binding.action(), frame,
                    binding.clientTick(), nowMillis);
            return true;
        } catch (IllegalStateException rejected) {
            logger.debug("Survival aquatic movement rejected at the exact lease boundary: {}",
                    rejected.getMessage());
            return false;
        }
    }

    private boolean waterFrameSampled(float pitch) {
        MovementAuthority binding = movementAuthority;
        if (binding == null) return false;
        MovementFrameActuator.Observation observation = movement.snapshot().observation();
        return observation.exactMatch()
                && observation.action().filter(binding.action()::equals).isPresent()
                && Math.abs(observation.sampled().pitch() - pitch) <= 0.25F;
    }

    private void releaseWaterFrame(MovementFrameActuator.Cleanup cleanup) {
        MovementAuthority binding = movementAuthority;
        if (binding != null) movement.cancel(binding.action(), cleanup);
    }

    private void releaseWaterLocomotion(MovementFrameActuator.Cleanup cleanup) {
        releaseWaterFrame(cleanup);
        waterLocomotion.reset();
    }

    private void selectHotbarSlotNow(int slot) {
        if (client.player == null || slot < 0 || slot >= PlayerInventory.getHotbarSize()) return;
        if (client.player.getInventory().getSelectedSlot() == slot) return;
        client.player.getInventory().setSelectedSlot(slot);
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private ShieldChoice findRetreatShield() {
        var player = client.player;
        if (player == null) return null;
        if (usableRetreatShield(
                player.getOffHandStack().isOf(Items.SHIELD),
                player.getItemCooldownManager().isCoolingDown(
                        player.getOffHandStack()))) {
            return new ShieldChoice(Hand.OFF_HAND, -1);
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (usableRetreatShield(
                    inventory.getStack(slot).isOf(Items.SHIELD),
                    player.getItemCooldownManager().isCoolingDown(
                            inventory.getStack(slot)))) {
                return new ShieldChoice(Hand.MAIN_HAND, slot);
            }
        }
        return null;
    }

    private boolean holdRetreatShield(Entity threat) {
        var player = client.player;
        if (player == null || client.interactionManager == null) return false;
        ShieldChoice choice = findRetreatShield();
        if (choice == null) {
            stopRetreatShield();
            return false;
        }
        return holdShield(threat, choice, RETREAT_SHIELD_USE_OPERATION);
    }

    /** Fire defense never changes the hotbar selected by the escape planner. */
    private boolean holdFireDefenseShield(Entity threat) {
        var player = client.player;
        if (player == null || client.interactionManager == null
                || !player.getOffHandStack().isOf(Items.SHIELD)
                || player.getItemCooldownManager().isCoolingDown(player.getOffHandStack())) {
            stopFireDefenseShield();
            return false;
        }
        return holdShield(
                threat,
                new ShieldChoice(Hand.OFF_HAND, -1),
                FIRE_DEFENSE_SHIELD_USE_OPERATION);
    }

    private boolean holdShield(Entity threat, ShieldChoice choice, String operation) {
        var player = client.player;
        if (player == null || client.interactionManager == null || threat == null) return false;
        if (retreatShieldPrepared
                && (retreatShieldHand != choice.hand()
                || retreatShieldSlot != choice.slot()
                || !operation.equals(retreatShieldUseOperation))) {
            stopRetreatShield();
        }
        long now = monotonicMillis();
        String sessionKey = operation + ':' + choice.hand().name() + ':' + choice.slot();
        if (!retreatShieldSession.bind(sessionKey, now)) {
            stopRetreatShield();
            return false;
        }
        faceToward(threat.getEyePos());
        if (!retreatShieldPrepared) {
            retreatShieldPrepared = true;
            retreatShieldHand = choice.hand();
            retreatShieldSlot = choice.slot();
            retreatShieldPreviousSlot = retreatShieldPreviousSlot(
                    retreatShieldHand, player.getInventory().getSelectedSlot());
            retreatShieldUseOperation = operation;
            if (retreatShieldHand == Hand.MAIN_HAND) selectHotbarSlotNow(retreatShieldSlot);
        } else if (retreatShieldHand == Hand.MAIN_HAND) {
            selectHotbarSlotNow(retreatShieldSlot);
        }
        MinecraftActuatorGateway.UseFrame frame =
                actuators.holdUseFrame(operation);
        if (!frame.active()) {
            stopRetreatShield();
            return false;
        }
        retreatShieldUseGeneration = frame.generation();
        boolean activelyBlockingExactShield = player.isUsingItem()
                && player.getActiveHand() == retreatShieldHand
                && player.getActiveItem().isOf(Items.SHIELD)
                && player.isBlocking();
        ShieldHoldSession.Decision decision = retreatShieldSession.observe(
                sessionKey,
                frame.generation(),
                now,
                activelyBlockingExactShield);
        if (decision.state() == ShieldHoldSession.State.FAILED) {
            stopRetreatShield();
            return false;
        }
        if (decision.issueInteraction()) {
            boolean accepted = client.interactionManager
                    .interactItem(player, retreatShieldHand).isAccepted();
            retreatShieldSession.recordInteraction(
                    sessionKey, frame.generation(), accepted);
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        // Held-use ownership is not protection. Vanilla acknowledges shield
        // use first, then isBlocking becomes authoritative after its delay.
        return decision.state() == ShieldHoldSession.State.BLOCKING;
    }

    static boolean shouldStartHeldUse(
            boolean sessionRequestedStart,
            boolean holdRequested,
            boolean actuatorStarted) {
        return sessionRequestedStart || (holdRequested && actuatorStarted);
    }

    static boolean usableRetreatShield(boolean shield, boolean coolingDown) {
        return shield && !coolingDown;
    }

    static int retreatShieldPreviousSlot(Hand hand, int selectedSlot) {
        return hand == Hand.MAIN_HAND ? selectedSlot : -1;
    }

    static RetreatFallback chooseRetreatFallback(
            boolean levelRoute,
            boolean verticalRoute,
            boolean shieldAvailable,
            boolean liveAttackReachable,
            float attackCooldown) {
        if (levelRoute) return RetreatFallback.MOVE_LEVEL;
        if (verticalRoute) return RetreatFallback.MOVE_VERTICAL;
        // A shield is the defense between strikes, not a reason to suppress
        // every strike forever. A sealed, reachable live attacker gets exactly
        // one normal-cooldown counterattack; the following cooldown frame falls
        // back to the shield again.
        if (liveAttackReachable && attackCooldown >= 0.9F) {
            return RetreatFallback.COUNTER_ATTACK;
        }
        if (shieldAvailable) return RetreatFallback.HOLD_SHIELD;
        return RetreatFallback.BRACE;
    }

    /**
     * A shield can cover one bounded route-search interval, but it cannot turn a
     * stationary body into reported retreat progress. The first watchdog stall
     * therefore exposes RETREAT_NO_ROUTE to Runtime immediately; Runtime can
     * choose a typed combat or shelter fallback before the outer invariant trips.
     */
    static boolean stationaryShieldHoldMayContinue(
            RetreatProgress progress,
            boolean shieldFailed,
            boolean retreatExhausted) {
        return progress != RetreatProgress.STALLED
                && !shieldFailed
                && !retreatExhausted;
    }

    static boolean retreatCoverHoldExpired(long startedAt, long now) {
        return startedAt > 0L
                && Math.max(0L, now - startedAt) >= RETREAT_COVER_HOLD_MILLIS;
    }

    static boolean rangedCoverHoldWindowActive(
            boolean coverEligible,
            long startedAt,
            long now) {
        return coverEligible && (startedAt <= 0L || !retreatCoverHoldExpired(startedAt, now));
    }

    static boolean mustAbortAquaticRetreat(
            RetreatProgress progress,
            boolean exhausted) {
        return progress == RetreatProgress.STALLED && exhausted;
    }

    private void faceToward(Vec3d target) {
        var player = client.player;
        Vec3d delta = target.subtract(player.getEyePos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        // 1.21.8 resolves the blocking cone from server-side head yaw. Keep all
        // local facing fields coherent with the rotation sent by the client so
        // the shield, camera, and movement packet cannot describe different
        // directions during a strafe/backpedal frame.
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setPitch((float) Math.max(-75.0, Math.min(75.0,
                -Math.toDegrees(Math.atan2(delta.y, Math.max(0.001, horizontal))))));
    }

    /** Exact interaction facing; unlike combat facing this may look straight down. */
    private void faceExactToward(Vec3d target) {
        var player = client.player;
        Vec3d delta = target.subtract(player.getEyePos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setPitch((float) Math.max(-90.0, Math.min(90.0,
                -Math.toDegrees(Math.atan2(delta.y, Math.max(0.0001, horizontal))))));
    }

    private RetreatRoute findRetreatRoute(
            double baseX,
            double baseZ,
            Set<String> rejectedRoutes,
            boolean allowAscent,
            boolean allowRearwardDetour) {
        var player = client.player;
        if (player == null || client.world == null) return null;
        double length = Math.hypot(baseX, baseZ);
        if (length < 0.001) return null;
        baseX /= length;
        baseZ /= length;
        List<RetreatVector> preferredHeadings = orderedGridRetreatHeadings(
                baseX, baseZ, false);
        RetreatRoute preferred = findRetreatRouteForHeadings(
                rejectedRoutes, allowAscent, preferredHeadings);
        if (preferred != null || !allowRearwardDetour) return preferred;

        // Standing still is not a safe result merely because terrain sealed the
        // away-facing semicircle. When the primary threat is still outside the
        // immediate danger margin, take one verified rearward-lateral detour and
        // replan from the next cell. A closing creeper inside its immediate margin
        // keeps the strict direct-away gate and can never use this fallback.
        return findRetreatRouteForHeadings(
                rejectedRoutes,
                allowAscent,
                orderedGridRetreatHeadings(baseX, baseZ, true));
    }

    /**
     * Orders the eight actual Minecraft grid headings by agreement with the
     * desired escape vector. Rotating an arbitrary vector by fixed angles can
     * miss a one-block cardinal corridor even though that corridor is open;
     * route probes must therefore be grid-aligned and only their priority may
     * depend on the tactical vector.
     */
    static List<RetreatVector> orderedGridRetreatHeadings(
            double desiredX,
            double desiredZ,
            boolean rearward) {
        double length = Math.hypot(desiredX, desiredZ);
        if (length < 0.001) return List.of();
        double normalizedX = desiredX / length;
        double normalizedZ = desiredZ / length;
        double diagonal = Math.sqrt(0.5);
        List<RetreatVector> headings = new ArrayList<>(List.of(
                new RetreatVector(1.0, 0.0),
                new RetreatVector(diagonal, diagonal),
                new RetreatVector(0.0, 1.0),
                new RetreatVector(-diagonal, diagonal),
                new RetreatVector(-1.0, 0.0),
                new RetreatVector(-diagonal, -diagonal),
                new RetreatVector(0.0, -1.0),
                new RetreatVector(diagonal, -diagonal)));
        headings.removeIf(heading -> {
            double alignment = heading.x() * normalizedX + heading.z() * normalizedZ;
            return rearward ? alignment >= -0.000_001 : alignment < -0.000_001;
        });
        headings.sort((first, second) -> Double.compare(
                second.x() * normalizedX + second.z() * normalizedZ,
                first.x() * normalizedX + first.z() * normalizedZ));
        return List.copyOf(headings);
    }

    private RetreatRoute findRetreatRouteForHeadings(
            Set<String> rejectedRoutes,
            boolean allowAscent,
            List<RetreatVector> headings) {
        var player = client.player;
        if (player == null || client.world == null) return null;
        for (RetreatVector heading : headings) {
            double dx = heading.x();
            double dz = heading.z();
            BlockPos levelLanding = retreatProbeFeet(dx, dz, 0);
            RetreatRoute candidate = new RetreatRoute(
                    dx,
                    dz,
                    RetreatRouteKind.LEVEL,
                    false,
                    player.getX(),
                    player.getZ(),
                    levelLanding.getX(),
                    levelLanding.getZ(),
                    safeGroundSprintCorridor(dx, dz));
            if (!rejectedRoutes.contains(retreatRouteKey(candidate)) && safeGroundStep(dx, dz)) {
                return candidate;
            }
        }
        for (RetreatVector heading : headings) {
            double dx = heading.x();
            double dz = heading.z();
            BlockPos upperFeet = retreatProbeFeet(dx, dz, 0);
            RetreatRoute jump = new RetreatRoute(
                    dx,
                    dz,
                    RetreatRouteKind.JUMP,
                    true,
                    player.getX(),
                    player.getZ(),
                    upperFeet.getX(),
                    upperFeet.getZ(),
                    true);
            if (retreatRouteKindAllowed(jump.kind(), allowAscent)
                    && !rejectedRoutes.contains(retreatRouteKey(jump))
                    && safeJumpStep(dx, dz)) {
                return jump;
            }
            RetreatRoute drop = new RetreatRoute(
                    dx,
                    dz,
                    RetreatRouteKind.DROP,
                    false,
                    player.getX(),
                    player.getZ(),
                    upperFeet.getX(),
                    upperFeet.getZ(),
                    safeOneBlockDropSprintCorridor(dx, dz));
            if (retreatRouteKindAllowed(drop.kind(), allowAscent)
                    && !rejectedRoutes.contains(retreatRouteKey(drop))
                    && safeOneBlockDropStep(dx, dz)) {
                return drop;
            }
        }
        return null;
    }

    static ShieldRetreatInput shieldRetreatInput(
            double awayX,
            double awayZ,
            double routeX,
            double routeZ) {
        double awayLength = Math.hypot(awayX, awayZ);
        double routeLength = Math.hypot(routeX, routeZ);
        if (awayLength < 0.001 || routeLength < 0.001) {
            return new ShieldRetreatInput(false, false, false);
        }
        double ax = awayX / awayLength;
        double az = awayZ / awayLength;
        double rx = routeX / routeLength;
        double rz = routeZ / routeLength;
        double backwardAlignment = ax * rx + az * rz;
        double lateralAlignment = ax * rz - az * rx;
        return new ShieldRetreatInput(
                backwardAlignment > 0.25,
                lateralAlignment > 0.25,
                lateralAlignment < -0.25);
    }

    private void blacklistCommittedRetreatRoute() {
        if (committedRetreatRoute != null) {
            rememberFailedRetreatRoute(committedRetreatRoute);
        }
    }

    private void rememberFailedRetreatRoute(RetreatRoute route) {
        String key = retreatRouteKey(route);
        if (failedRetreatRoutes.contains(key)) return;
        if (failedRetreatRoutes.size() >= MAX_FAILED_RETREAT_ROUTES) {
            var oldest = failedRetreatRoutes.iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        failedRetreatRoutes.add(key);
    }

    private void resetRetreatState() {
        // A terminal start failure belongs to one protection episode. A new
        // episode may try again, while a healthy active heartbeat is retained
        // without an artificial release/repress gap.
        retreatShieldSession.resetFailure();
        retreatProgress.reset();
        failedRetreatRoutes.clear();
        retreatEpisodeKey = "";
        committedRetreatRoute = null;
        retreatCoverStartedAt = 0L;
        retreatCoverHoldActive = false;
    }

    static RetreatVector resolveRetreatVector(
            RetreatIntent intent,
            double playerX,
            double playerZ,
            double threatX,
            double threatZ) {
        double x = intent == null ? 0.0 : intent.escapeX();
        double z = intent == null ? 0.0 : intent.escapeZ();
        double length = Math.hypot(x, z);
        if (length < 0.001) {
            x = playerX - threatX;
            z = playerZ - threatZ;
            length = Math.hypot(x, z);
        }
        if (length < 0.001) return new RetreatVector(1.0, 0.0);
        return new RetreatVector(x / length, z / length);
    }

    static boolean immediateHazardRouteReplanRequired(
            boolean immediateHazardAvoidance,
            double routeX,
            double routeZ,
            double escapeX,
            double escapeZ) {
        if (!immediateHazardAvoidance) return false;
        double routeLength = Math.hypot(routeX, routeZ);
        double escapeLength = Math.hypot(escapeX, escapeZ);
        if (routeLength < 0.001 || escapeLength < 0.001) return false;
        return (routeX / routeLength) * (escapeX / escapeLength)
                + (routeZ / routeLength) * (escapeZ / escapeLength) < 0.0;
    }

    private static String retreatRouteKey(RetreatRoute route) {
        return retreatRouteFailureKey(
                route.kind(), route.dx(), route.dz(), route.originX(), route.originZ());
    }

    static String retreatRouteFailureKey(
            RetreatRouteKind kind,
            double routeX,
            double routeZ,
            double originX,
            double originZ) {
        Objects.requireNonNull(kind, "kind");
        int originBlockX = (int) Math.floor(originX);
        int originBlockZ = (int) Math.floor(originZ);
        int x = (int) Math.round(routeX * 8.0);
        int z = (int) Math.round(routeZ * 8.0);
        return originBlockX + ":" + originBlockZ + ':' + kind.name() + ':' + x + ':' + z;
    }

    static boolean rearwardRetreatDetourAllowed(
            boolean immediateHazardAvoidance,
            double horizontalThreatDistance) {
        return !immediateHazardAvoidance
                && Double.isFinite(horizontalThreatDistance)
                && horizontalThreatDistance > MIN_REARWARD_RETREAT_DETOUR_DISTANCE;
    }

    private static double horizontalDistance(Vec3d first, Vec3d second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void faceRetreatRoute(RetreatRoute route) {
        if (route == null || client.player == null) return;
        client.player.setYaw((float) (Math.toDegrees(Math.atan2(route.dz(), route.dx())) - 90.0));
        client.player.setPitch(0.0F);
    }

    private boolean safeGroundStep(double dx, double dz) {
        return safeGroundStep(dx, dz, 1.15);
    }

    private boolean safeGroundStep(double dx, double dz, double distance) {
        var player = client.player;
        // An airborne frame proves nothing about the collision or floor at the
        // intended destination. Treating it as automatically safe caused a
        // wall-facing jump to renew itself until a skeleton killed Entity.
        if (!player.isOnGround()) return false;
        BlockPos feet = BlockPos.ofFloored(
                player.getX() + dx * distance,
                player.getY() + 0.1,
                player.getZ() + dz * distance);
        return safeRetreatFeetCell(feet);
    }

    private boolean safeRetreatFeetCell(BlockPos feet) {
        if (client.world == null) return false;
        if (!client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        BlockState feetState = client.world.getBlockState(feet);
        BlockState headState = client.world.getBlockState(feet.up());
        BlockState floorState = client.world.getBlockState(feet.down());
        if (!feetState.getCollisionShape(client.world, feet).isEmpty()
                || !headState.getCollisionShape(client.world, feet.up()).isEmpty()
                || floorState.getCollisionShape(client.world, feet.down()).isEmpty()) return false;
        return retreatHazardMarginClear(feet)
                && !feetState.getFluidState().isIn(FluidTags.LAVA)
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

    /**
     * Sprint is granted only when the next two body lengths retain loaded,
     * hazard-free floor. One-cell proof is enough for a walking step, but not
     * enough braking distance for a sprinting player at a cave edge.
     */
    private boolean safeGroundSprintCorridor(double dx, double dz) {
        return safeGroundStep(dx, dz, 1.15)
                && safeGroundStep(dx, dz, 1.75)
                && safeGroundStep(dx, dz, 2.35);
    }

    private BlockPos retreatProbeFeet(double dx, double dz, int yOffset) {
        var player = client.player;
        return BlockPos.ofFloored(
                player.getX() + dx * 1.15,
                player.getY() + 0.1 + yOffset,
                player.getZ() + dz * 1.15);
    }

    private boolean safeJumpStep(double dx, double dz) {
        var player = client.player;
        if (player == null || client.world == null || !player.isOnGround()) return false;
        BlockPos obstacle = BlockPos.ofFloored(
                player.getX() + dx * 1.15,
                player.getY() + 0.1,
                player.getZ() + dz * 1.15);
        if (!client.world.isChunkLoaded(obstacle.getX() >> 4, obstacle.getZ() >> 4)) return false;
        BlockState step = client.world.getBlockState(obstacle);
        BlockState landingFeet = client.world.getBlockState(obstacle.up());
        BlockState landingHead = client.world.getBlockState(obstacle.up(2));
        return !step.getCollisionShape(client.world, obstacle).isEmpty()
                && landingFeet.getCollisionShape(client.world, obstacle.up()).isEmpty()
                && landingHead.getCollisionShape(client.world, obstacle.up(2)).isEmpty()
                && retreatHazardMarginClear(obstacle.up())
                && !hazardousRetreatCell(obstacle.up(), landingFeet, step);
    }

    private boolean safeOneBlockDropStep(double dx, double dz) {
        var player = client.player;
        if (player == null || client.world == null || !player.isOnGround()) return false;
        BlockPos upperFeet = BlockPos.ofFloored(
                player.getX() + dx * 1.15,
                player.getY() + 0.1,
                player.getZ() + dz * 1.15);
        if (!client.world.isChunkLoaded(upperFeet.getX() >> 4, upperFeet.getZ() >> 4)) return false;
        BlockPos landingFeet = upperFeet.down();
        BlockPos floor = landingFeet.down();
        BlockState upper = client.world.getBlockState(upperFeet);
        BlockState landing = client.world.getBlockState(landingFeet);
        BlockState floorState = client.world.getBlockState(floor);
        return upper.getCollisionShape(client.world, upperFeet).isEmpty()
                && landing.getCollisionShape(client.world, landingFeet).isEmpty()
                && !floorState.getCollisionShape(client.world, floor).isEmpty()
                && retreatHazardMarginClear(landingFeet)
                && !hazardousRetreatCell(landingFeet, landing, floorState);
    }

    /**
     * A one-block descent may retain sprint only when the landing and the next
     * two braking samples form one loaded, hazard-free lower corridor. This is
     * the downhill equivalent of {@link #safeGroundSprintCorridor(double, double)}:
     * it lets an undergeared player escape across an ordinary slope without
     * granting blind momentum over a cave edge.
     */
    private boolean safeOneBlockDropSprintCorridor(double dx, double dz) {
        var player = client.player;
        if (player == null || client.world == null
                || !safeOneBlockDropStep(dx, dz)) return false;
        for (double distance : new double[]{1.75, 2.35}) {
            BlockPos lowerFeet = BlockPos.ofFloored(
                    player.getX() + dx * distance,
                    player.getY() - 0.9,
                    player.getZ() + dz * distance);
            if (!safeRetreatFeetCell(lowerFeet)) return false;
        }
        return true;
    }

    /**
     * A destination is not a safe combat step merely because its own block is
     * dry. Refuse the one-block margin around lava, fire, and damaging floors;
     * that turns the latest production trace's visible lava edge into a hard
     * route boundary before retreat momentum reaches it.
     */
    private boolean retreatHazardMarginClear(BlockPos feet) {
        if (client.world == null) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = feet.add(dx, dy, dz);
                    if (!loaded(candidate)) return false;
                    BlockState state = client.world.getBlockState(candidate);
                    if (dangerousFireEscapeCell(candidate, state)) return false;
                }
            }
        }
        return true;
    }

    private boolean hazardousRetreatCell(
            BlockPos feet,
            BlockState feetState,
            BlockState floorState) {
        return feetState.getFluidState().isIn(FluidTags.LAVA)
                || feetState.isOf(Blocks.FIRE)
                || feetState.isOf(Blocks.SOUL_FIRE)
                || feetState.isOf(Blocks.CACTUS)
                || feetState.isOf(Blocks.POWDER_SNOW)
                || floorState.isOf(Blocks.MAGMA_BLOCK)
                || floorState.isOf(Blocks.CACTUS)
                || floorState.isOf(Blocks.CAMPFIRE)
                || floorState.isOf(Blocks.SOUL_CAMPFIRE);
    }

    private static int remainingDurability(ItemStack stack) {
        return stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private String waterMemoryScope() {
        return client.world == null
                ? ""
                : client.world.getRegistryKey().getValue().toString();
    }

    private static GridEscapePlanner.Point point(BlockPos pos) {
        return new GridEscapePlanner.Point(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Small expiring memory for an air pocket that restored breathing but fed
     * the body straight back into the same underwater trap. This deliberately
     * survives action resets; it never marks a pocket while the body is still
     * breathing there, and it only blocks breathable goal cells.
     */
    static final class BreathingPocketMemory {
        private final int maximumEntries;
        private final long reentryWindowMillis;
        private final long blacklistMillis;
        private final int reentryHorizontalRadius;
        private final int reentryVerticalRadius;
        private final int blacklistHorizontalRadius;
        private final int blacklistVerticalRadius;
        private final ArrayDeque<BlockedPocket> blocked = new ArrayDeque<>();
        private BreathingCandidate candidate;

        BreathingPocketMemory(
                int maximumEntries,
                long reentryWindowMillis,
                long blacklistMillis,
                int reentryHorizontalRadius,
                int reentryVerticalRadius,
                int blacklistHorizontalRadius,
                int blacklistVerticalRadius) {
            if (maximumEntries < 1 || reentryWindowMillis < 1 || blacklistMillis < 1
                    || reentryHorizontalRadius < 0 || reentryVerticalRadius < 0
                    || blacklistHorizontalRadius < 0 || blacklistVerticalRadius < 0) {
                throw new IllegalArgumentException("invalid breathing-pocket memory limits");
            }
            this.maximumEntries = maximumEntries;
            this.reentryWindowMillis = reentryWindowMillis;
            this.blacklistMillis = blacklistMillis;
            this.reentryHorizontalRadius = reentryHorizontalRadius;
            this.reentryVerticalRadius = reentryVerticalRadius;
            this.blacklistHorizontalRadius = blacklistHorizontalRadius;
            this.blacklistVerticalRadius = blacklistVerticalRadius;
        }

        void observeBreathing(
                String scope,
                GridEscapePlanner.Point pocket,
                long now,
                boolean airRecovered) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(pocket, "pocket");
            prune(now);
            if (candidate != null
                    && candidate.scope().equals(scope)
                    && nearby(candidate.point(), pocket,
                    blacklistHorizontalRadius, blacklistVerticalRadius)) {
                candidate = new BreathingCandidate(
                        scope,
                        candidate.point(),
                        now,
                        candidate.airRecovered() || airRecovered,
                        candidate.armedForReentry() || airRecovered);
                return;
            }
            // Once air is actually restored, the next nearby submerged sample is
            // evidence that this pocket fed Entity back into the same trap. Arm
            // immediately: a current or low ceiling can pull the body under again
            // before SurvivalSupervisor has time to clear/reset the action.
            candidate = new BreathingCandidate(scope, pocket, now, airRecovered, airRecovered);
        }

        void onActionReset(long now) {
            prune(now);
            if (candidate != null && candidate.airRecovered()) {
                candidate = new BreathingCandidate(
                        candidate.scope(),
                        candidate.point(),
                        candidate.observedAt(),
                        true,
                        true);
            }
        }

        boolean observeSubmerged(
                String scope,
                GridEscapePlanner.Point position,
                long now) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(position, "position");
            prune(now);
            if (candidate == null
                    || !candidate.airRecovered()
                    || !candidate.armedForReentry()
                    || !candidate.scope().equals(scope)
                    || !nearby(candidate.point(), position,
                    reentryHorizontalRadius, reentryVerticalRadius)) {
                return false;
            }
            remember(candidate.scope(), candidate.point(), now);
            candidate = null;
            return true;
        }

        boolean isBlacklisted(
                String scope,
                GridEscapePlanner.Point pocket,
                long now) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(pocket, "pocket");
            prune(now);
            for (BlockedPocket entry : blocked) {
                if (entry.scope().equals(scope)
                        && nearby(entry.point(), pocket,
                        blacklistHorizontalRadius, blacklistVerticalRadius)) {
                    return true;
                }
            }
            return false;
        }

        int blacklistedCount(long now) {
            prune(now);
            return blocked.size();
        }

        private void remember(String scope, GridEscapePlanner.Point point, long now) {
            blocked.removeIf(entry -> entry.scope().equals(scope)
                    && nearby(entry.point(), point,
                    blacklistHorizontalRadius, blacklistVerticalRadius));
            while (blocked.size() >= maximumEntries) blocked.removeFirst();
            blocked.addLast(new BlockedPocket(scope, point, now + blacklistMillis));
        }

        private void prune(long now) {
            blocked.removeIf(entry -> now >= entry.expiresAt());
            if (candidate != null && now - candidate.observedAt() > reentryWindowMillis) {
                candidate = null;
            }
        }

        private static boolean nearby(
                GridEscapePlanner.Point first,
                GridEscapePlanner.Point second,
                int horizontalRadius,
                int verticalRadius) {
            return Math.abs(first.x() - second.x()) <= horizontalRadius
                    && Math.abs(first.z() - second.z()) <= horizontalRadius
                    && Math.abs(first.y() - second.y()) <= verticalRadius;
        }

        private record BreathingCandidate(
                String scope,
                GridEscapePlanner.Point point,
                long observedAt,
                boolean airRecovered,
                boolean armedForReentry) {
        }

        private record BlockedPocket(
                String scope,
                GridEscapePlanner.Point point,
                long expiresAt) {
        }
    }

    /** A single bounded retry gate shared by execution and food availability. */
    static final class FoodRecoveryGate {
        private final long retryCooldownMillis;
        private boolean failed;
        private long failedAt;
        private int hungerAtFailure;
        private long inventoryFingerprintAtFailure;

        FoodRecoveryGate(long retryCooldownMillis) {
            if (retryCooldownMillis < 1) {
                throw new IllegalArgumentException("food retry cooldown must be positive");
            }
            this.retryCooldownMillis = retryCooldownMillis;
        }

        void recordFailure(long now, int hungerLevel, long inventoryFingerprint) {
            failed = true;
            failedAt = now;
            hungerAtFailure = hungerLevel;
            inventoryFingerprintAtFailure = inventoryFingerprint;
        }

        boolean blocks(long now, int hungerLevel, long inventoryFingerprint) {
            if (!failed) return false;
            boolean stateChanged = hungerLevel != hungerAtFailure
                    || inventoryFingerprint != inventoryFingerprintAtFailure;
            long elapsed = Math.max(0L, now - failedAt);
            if (stateChanged || elapsed >= retryCooldownMillis) {
                clear();
                return false;
            }
            return true;
        }

        private void clear() {
            failed = false;
            failedAt = 0;
            hungerAtFailure = 0;
            inventoryFingerprintAtFailure = 0;
        }
    }

    /**
     * Bounds mission-cargo sacrifice while still reacting to a worsening
     * emergency. One reserved item is available on initial critical entry. A
     * second is armed only after another two hearts (or four hunger points)
     * are actually lost; ordinary repeated client ticks cannot drain cargo.
     */
    static final class EmergencyFoodReservationGate {
        private final float healthLossToRearm;
        private final int hungerLossToRearm;
        private boolean consumed;
        private float healthAtConsumption;
        private int hungerAtConsumption;

        EmergencyFoodReservationGate(float healthLossToRearm, int hungerLossToRearm) {
            if (!Float.isFinite(healthLossToRearm) || healthLossToRearm <= 0
                    || hungerLossToRearm <= 0) {
                throw new IllegalArgumentException("emergency food rearm thresholds must be positive");
            }
            this.healthLossToRearm = healthLossToRearm;
            this.hungerLossToRearm = hungerLossToRearm;
        }

        boolean allowsConsumption(boolean critical, float health, int hunger) {
            if (!critical) {
                reset();
                return false;
            }
            if (!consumed) return true;
            return health <= healthAtConsumption - healthLossToRearm
                    || hunger <= hungerAtConsumption - hungerLossToRearm;
        }

        void recordConsumption(float health, int hunger) {
            consumed = true;
            healthAtConsumption = health;
            hungerAtConsumption = hunger;
        }

        private void reset() {
            consumed = false;
            healthAtConsumption = 0.0F;
            hungerAtConsumption = 0;
        }
    }

    /**
     * Pure lifecycle for one physical hold of Minecraft's use key. A session
     * never returns to WAITING_FOR_USE after vanilla acknowledges it, and a
     * terminal failure cannot be restarted without an explicit reset.
     */
    static final class HeldUseSession {
        static final long START_TIMEOUT_MILLIS = 4_000L;
        static final long COMPLETE_TIMEOUT_MILLIS = 8_000L;
        static final long TRANSIENT_END_GRACE_MILLIS = 250L;
        static final long VERIFICATION_GRACE_MILLIS = 750L;

        private State state = State.IDLE;
        private long startedAt;
        private long useEndedAt;
        private long useMissingAt = -1;
        private int initialFoodLevel;
        private int initialStackCount;
        private boolean startIssued;

        void begin(long now, int foodLevel, int stackCount) {
            if (state != State.IDLE) {
                throw new IllegalStateException("held-use session must be reset before begin");
            }
            if (stackCount <= 0) {
                throw new IllegalArgumentException("held-use session requires a non-empty stack");
            }
            state = State.WAITING_FOR_USE;
            startedAt = now;
            useEndedAt = 0;
            useMissingAt = -1;
            initialFoodLevel = foodLevel;
            initialStackCount = stackCount;
            startIssued = false;
        }

        Decision observe(long now, int foodLevel, int stackCount, boolean usingItem) {
            if (state == State.IDLE || state == State.CONSUMED || state == State.FAILED) {
                return decision(false);
            }
            if (foodLevel > initialFoodLevel || stackCount < initialStackCount) {
                state = State.CONSUMED;
                return decision(false);
            }

            long elapsed = Math.max(0L, now - startedAt);
            if (elapsed >= COMPLETE_TIMEOUT_MILLIS) {
                state = State.FAILED;
                return decision(false);
            }

            boolean startUseAction = false;
            if (state == State.WAITING_FOR_USE) {
                if (usingItem) state = State.CONSUMING;
                else if (elapsed >= START_TIMEOUT_MILLIS) state = State.FAILED;
                else if (!startIssued) {
                    startIssued = true;
                    startUseAction = true;
                }
            } else if (state == State.CONSUMING && !usingItem) {
                // One false sample can occur between local use animation and
                // server inventory acknowledgement. Keep the same physical hold
                // alive briefly, without ever issuing a second interaction.
                if (useMissingAt < 0) useMissingAt = now;
                if (now - useMissingAt >= TRANSIENT_END_GRACE_MILLIS) {
                    state = State.VERIFYING;
                    useEndedAt = now;
                }
            } else if (state == State.CONSUMING) {
                useMissingAt = -1;
            } else if (state == State.VERIFYING
                    && now - useEndedAt >= VERIFICATION_GRACE_MILLIS) {
                state = State.FAILED;
            }
            return decision(startUseAction);
        }

        void fail() {
            if (state != State.CONSUMED) state = State.FAILED;
        }

        boolean failed() {
            return state == State.FAILED;
        }

        void reset() {
            state = State.IDLE;
            startedAt = 0;
            useEndedAt = 0;
            useMissingAt = -1;
            initialFoodLevel = 0;
            initialStackCount = 0;
            startIssued = false;
        }

        private Decision decision(boolean startUseAction) {
            boolean hold = state == State.WAITING_FOR_USE || state == State.CONSUMING;
            return new Decision(state, hold, startUseAction);
        }

        enum State {
            IDLE,
            WAITING_FOR_USE,
            CONSUMING,
            VERIFYING,
            CONSUMED,
            FAILED
        }

        record Decision(State state, boolean holdUseKey, boolean startUseAction) {
        }
    }

    /**
     * Inventory-synchronization fence for an accepted food interaction. Unlike
     * HeldUseSession, this latch is not reset when survival relinquishes body
     * ownership: the server-side decrement may still be in flight.
     */
    static final class FoodCompletionFence {
        private final long timeoutMillis;
        private boolean active;
        private long startedAt;
        private String itemId = "";
        private int initialItemCount;
        private int initialHunger;

        FoodCompletionFence(long timeoutMillis) {
            if (timeoutMillis < 1L) {
                throw new IllegalArgumentException("food completion timeout must be positive");
            }
            this.timeoutMillis = timeoutMillis;
        }

        void begin(long now, String itemId, int itemCount, int hunger) {
            this.active = true;
            this.startedAt = now;
            this.itemId = normalizeItemId(Objects.requireNonNull(itemId, "itemId"));
            this.initialItemCount = Math.max(0, itemCount);
            this.initialHunger = hunger;
        }

        String itemId() {
            return active ? itemId : "";
        }

        boolean pending(long now, int itemCount, int hunger) {
            if (!active) return false;
            if (itemCount < initialItemCount || hunger > initialHunger
                    || Math.max(0L, now - startedAt) >= timeoutMillis) {
                clear();
                return false;
            }
            return true;
        }

        private void clear() {
            active = false;
            startedAt = 0L;
            itemId = "";
            initialItemCount = 0;
            initialHunger = 0;
        }
    }

    /**
     * Fixed-deadline acknowledgement for one continuously held shield use.
     *
     * <p>The held-use lease proves only input ownership. Protection is true
     * only after an accepted interaction and the exact active hand/item both
     * reach vanilla's authoritative blocking state. Rejected starts may be
     * reissued without releasing the physical key, but neither retries nor a
     * replacement held-use generation move the original deadline.</p>
     */
    static final class ShieldHoldSession {
        private final long timeoutMillis;
        private final long reissueMillis;

        private String key = "";
        private long generation;
        private long deadlineAt;
        private long lastInteractionAt = Long.MIN_VALUE;
        private boolean interactionAccepted;
        private State state = State.IDLE;

        ShieldHoldSession(long timeoutMillis, long reissueMillis) {
            if (timeoutMillis < 1L || reissueMillis < 1L
                    || reissueMillis > timeoutMillis) {
                throw new IllegalArgumentException(
                        "shield acknowledgement limits are invalid");
            }
            this.timeoutMillis = timeoutMillis;
            this.reissueMillis = reissueMillis;
        }

        boolean bind(String nextKey, long now) {
            String normalized = Objects.requireNonNull(nextKey, "nextKey").trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("shield session key cannot be blank");
            }
            if (!normalized.equals(key)) {
                clear();
                key = normalized;
                deadlineAt = saturatingAdd(now, timeoutMillis);
                state = State.STARTING;
            }
            return state != State.FAILED;
        }

        Decision observe(
                String expectedKey,
                long nextGeneration,
                long now,
                boolean activelyBlockingExactShield) {
            if (nextGeneration <= 0L) {
                throw new IllegalArgumentException("shield use generation must be positive");
            }
            if (!bind(expectedKey, now)) return new Decision(State.FAILED, false);
            if (generation != nextGeneration) {
                // A replacement capability must earn its own accepted start,
                // but cannot buy a fresh failure window for the same attempt.
                generation = nextGeneration;
                interactionAccepted = false;
                lastInteractionAt = Long.MIN_VALUE;
                state = State.STARTING;
            }
            if (interactionAccepted && activelyBlockingExactShield) {
                state = State.BLOCKING;
                return new Decision(state, false);
            }
            if (now > deadlineAt) {
                state = State.FAILED;
                return new Decision(state, false);
            }
            state = State.STARTING;
            boolean issue = !interactionAccepted
                    && (lastInteractionAt == Long.MIN_VALUE
                    || Math.max(0L, now - lastInteractionAt) >= reissueMillis);
            if (issue) lastInteractionAt = now;
            return new Decision(state, issue);
        }

        void recordInteraction(
                String expectedKey,
                long expectedGeneration,
                boolean accepted) {
            if (state == State.FAILED
                    || !key.equals(Objects.requireNonNullElse(expectedKey, ""))
                    || generation != expectedGeneration) return;
            interactionAccepted |= accepted;
        }

        boolean blocking() {
            return state == State.BLOCKING;
        }

        boolean failed() {
            return state == State.FAILED;
        }

        State state() {
            return state;
        }

        void releaseAttempt() {
            if (state != State.FAILED) clear();
        }

        void resetFailure() {
            if (state == State.FAILED) clear();
        }

        void clear() {
            key = "";
            generation = 0L;
            deadlineAt = 0L;
            lastInteractionAt = Long.MIN_VALUE;
            interactionAccepted = false;
            state = State.IDLE;
        }

        private static long saturatingAdd(long value, long increment) {
            return value > Long.MAX_VALUE - increment
                    ? Long.MAX_VALUE
                    : value + increment;
        }

        enum State {
            IDLE,
            STARTING,
            BLOCKING,
            FAILED
        }

        record Decision(State state, boolean issueInteraction) {
        }
    }

    /** Bounded explicit starts around one continuously held physical attack key. */
    static final class HeldBreakLatch {
        static final long REISSUE_MILLIS = 500L;
        private long lastIssuedAt = Long.MIN_VALUE;

        boolean shouldIssueStart(long now, boolean vanillaBreaking) {
            if (vanillaBreaking) return false;
            if (lastIssuedAt != Long.MIN_VALUE
                    && Math.max(0L, now - lastIssuedAt) < REISSUE_MILLIS) {
                return false;
            }
            lastIssuedAt = now;
            return true;
        }

        void reset() {
            lastIssuedAt = Long.MIN_VALUE;
        }
    }

    /**
     * Threat-scoped movement watchdog for direct combat movement.
     *
     * <p>Only supported land displacement, aquatic horizontal displacement,
     * horizontal separation from the same measured threat, or breaking line
     * of sight count as progress. Vertical jump oscillation and alternating
     * on-ground/airborne samples intentionally do not renew the deadline.</p>
     */
    static final class RetreatProgressTracker {
        private final long timeoutMillis;
        private final double displacement;
        private final double threatDistanceGain;
        private final int maximumFailures;

        private String threatKey = "";
        private String measurementThreatKey = "";
        private long lastProgressAt;
        private PlanarProgressGeometry horizontalProgress;
        private double checkpointThreatDistance;
        private boolean previousLineOfSight;
        private boolean coverProgressRecorded;
        private int failures;
        private boolean initialized;

        RetreatProgressTracker(
                long timeoutMillis,
                double displacement,
                double threatDistanceGain,
                int maximumFailures) {
            if (timeoutMillis <= 0 || displacement <= 0 || threatDistanceGain <= 0
                    || maximumFailures <= 0) {
                throw new IllegalArgumentException("retreat progress limits must be positive");
            }
            this.timeoutMillis = timeoutMillis;
            this.displacement = displacement;
            this.threatDistanceGain = threatDistanceGain;
            this.maximumFailures = maximumFailures;
        }

        RetreatProgress observe(
                String nextThreatKey,
                long now,
                double x,
                double y,
                double z,
                double horizontalThreatDistance,
                boolean lineOfSight,
                boolean onGround) {
            return observe(
                    nextThreatKey,
                    nextThreatKey,
                    now,
                    x,
                    y,
                    z,
                    horizontalThreatDistance,
                    lineOfSight,
                    onGround,
                    false);
        }

        RetreatProgress observe(
                String nextEpisodeKey,
                String nextMeasurementThreatKey,
                long now,
                double x,
                double y,
                double z,
                double horizontalThreatDistance,
                boolean lineOfSight,
                boolean onGround,
                boolean aquatic) {
            String normalizedKey = Objects.requireNonNullElse(nextEpisodeKey, "");
            String normalizedMeasurement =
                    Objects.requireNonNullElse(nextMeasurementThreatKey, "");
            if (!initialized || !normalizedKey.equals(threatKey)) {
                initialize(
                        normalizedKey,
                        normalizedMeasurement,
                        now,
                        x,
                        z,
                        horizontalThreatDistance,
                        lineOfSight);
                return RetreatProgress.STARTED;
            }

            if (!normalizedMeasurement.equals(measurementThreatKey)) {
                // Separation from two different mobs is not comparable. Keep
                // the episode deadline/failure budget, but establish a fresh
                // distance and line-of-sight measurement without calling the
                // raw primary UUID change "progress".
                measurementThreatKey = normalizedMeasurement;
                checkpointThreatDistance = horizontalThreatDistance;
                previousLineOfSight = lineOfSight;
            }

            boolean movedHorizontally = (onGround || aquatic)
                    && horizontalProgress.advanceIfNovel(x, z, displacement);
            boolean openedDistance = horizontalThreatDistance - checkpointThreatDistance
                    >= threatDistanceGain;
            boolean reachedCover = previousLineOfSight && !lineOfSight
                    && !coverProgressRecorded;
            previousLineOfSight = lineOfSight;
            if (movedHorizontally || openedDistance || reachedCover) {
                if (reachedCover) coverProgressRecorded = true;
                recordProgress(now, horizontalThreatDistance);
                failures = 0;
                return RetreatProgress.ADVANCED;
            }

            // y and onGround are sampled deliberately but cannot prove escape.
            // A headless wall-jump alternates both while x/z and threat distance
            // remain unchanged.
            if (Math.max(0L, now - lastProgressAt) >= timeoutMillis) {
                lastProgressAt = now;
                failures++;
                return RetreatProgress.STALLED;
            }
            return RetreatProgress.WAITING;
        }

        boolean exhausted() {
            return failures >= maximumFailures;
        }

        int failures() {
            return failures;
        }

        void reset() {
            threatKey = "";
            measurementThreatKey = "";
            lastProgressAt = 0L;
            horizontalProgress = null;
            checkpointThreatDistance = 0.0;
            previousLineOfSight = false;
            coverProgressRecorded = false;
            failures = 0;
            initialized = false;
        }

        private void initialize(
                String nextThreatKey,
                String nextMeasurementThreatKey,
                long now,
                double x,
                double z,
                double horizontalThreatDistance,
                boolean lineOfSight) {
            threatKey = nextThreatKey;
            measurementThreatKey = nextMeasurementThreatKey;
            initialized = true;
            failures = 0;
            previousLineOfSight = lineOfSight;
            coverProgressRecorded = !lineOfSight;
            horizontalProgress = new PlanarProgressGeometry(x, z);
            lastProgressAt = now;
            checkpointThreatDistance = horizontalThreatDistance;
        }

        private void recordProgress(long now, double horizontalThreatDistance) {
            lastProgressAt = now;
            checkpointThreatDistance = Math.max(
                    checkpointThreatDistance, horizontalThreatDistance);
        }
    }

    enum RetreatProgress {
        STARTED,
        WAITING,
        ADVANCED,
        STALLED
    }

    enum RetreatFallback {
        MOVE_LEVEL,
        MOVE_VERTICAL,
        HOLD_SHIELD,
        COUNTER_ATTACK,
        BRACE
    }

    enum FireDefenseAction {
        NONE,
        HOLD_SHIELD,
        COUNTER_ATTACK
    }

    enum RetreatRouteKind { LEVEL, JUMP, DROP }

    static boolean retreatRouteKindAllowed(RetreatRouteKind kind, boolean allowAscent) {
        return kind != RetreatRouteKind.JUMP || allowAscent;
    }

    static boolean airborneRetreatMovementAllowed(
            RetreatRouteKind routeKind,
            int currentBlockX,
            int currentBlockZ,
            int landingBlockX,
            int landingBlockZ) {
        return routeKind == RetreatRouteKind.JUMP
                && (currentBlockX != landingBlockX || currentBlockZ != landingBlockZ);
    }

    static boolean retreatRoutePhysicallyBlocked(
            boolean horizontalCollision,
            double horizontalSpeed) {
        return horizontalCollision
                && Double.isFinite(horizontalSpeed)
                && horizontalSpeed < 0.08;
    }

    static boolean retreatRouteSegmentCompleted(
            double originX,
            double originZ,
            double routeX,
            double routeZ,
            int landingBlockX,
            int landingBlockZ,
            double currentX,
            double currentZ,
            int currentBlockX,
            int currentBlockZ) {
        if (!Double.isFinite(originX) || !Double.isFinite(originZ)
                || !Double.isFinite(routeX) || !Double.isFinite(routeZ)
                || !Double.isFinite(currentX) || !Double.isFinite(currentZ)) {
            return false;
        }
        double length = Math.hypot(routeX, routeZ);
        if (length < 0.001) return false;
        double directedProgress = (currentX - originX) * routeX / length
                + (currentZ - originZ) * routeZ / length;
        boolean reachedDistinctLandingCell = (landingBlockX != (int) Math.floor(originX)
                || landingBlockZ != (int) Math.floor(originZ))
                && currentBlockX == landingBlockX
                && currentBlockZ == landingBlockZ;
        return reachedDistinctLandingCell || directedProgress >= 0.75;
    }

    static boolean shouldClearFailedRetreatRoutes(
            boolean segmentCompleted,
            RetreatProgress progress) {
        return segmentCompleted && progress == RetreatProgress.ADVANCED;
    }

    static boolean movingRetreatCounterattackAllowed(
            boolean usableRetreatRoute,
            boolean liveAttackReachable,
            float attackCooldown) {
        return usableRetreatRoute
                && liveAttackReachable
                && Float.isFinite(attackCooldown)
                && attackCooldown >= 0.9F;
    }

    static boolean retreatJumpCommanded(RetreatRouteKind routeKind) {
        return routeKind == RetreatRouteKind.JUMP;
    }

    private Entity nearestRetreatCounterattackTarget(Entity preferred) {
        var player = client.player;
        if (player == null || client.world == null) return null;
        Entity nearest = null;
        double nearestDistance = RETREAT_ATTACK_REACH_SQUARED;
        // Only the already-authenticated tactical primary can be a player. Never scan for
        // bystanders; the attack boundary rechecks fresh retaliation authority before swinging.
        if (preferred instanceof net.minecraft.entity.player.PlayerEntity
                && defensivePlayerReach(preferred.isAlive(), preferred.isRemoved(),
                player.canSee(preferred), player.squaredDistanceTo(preferred))) {
            nearest = preferred;
            nearestDistance = player.squaredDistanceTo(preferred);
        }
        for (Entity candidate : client.world.getOtherEntities(
                player,
                player.getBoundingBox().expand(3.0),
                entity -> entity instanceof HostileEntity)) {
            double squaredDistance = player.squaredDistanceTo(candidate);
            String type = EntityType.getId(candidate.getType()).getPath();
            boolean targetingSelf = candidate instanceof HostileEntity hostile
                    && hostile.getTarget() == player;
            if (!retreatCounterattackCandidateAllowed(
                    type,
                    candidate.isAlive(),
                    candidate.isRemoved(),
                    player.canSee(candidate),
                    squaredDistance,
                    true,
                    targetingSelf)) continue;
            // Preserve the tactical primary when equally close, but never stare
            // past a zombie already inside the body to attack a farther UUID.
            if (nearest == null
                    || squaredDistance < nearestDistance - 0.01
                    || Math.abs(squaredDistance - nearestDistance) <= 0.01
                    && candidate == preferred) {
                nearest = candidate;
                nearestDistance = squaredDistance;
            }
        }
        return nearest;
    }

    static boolean defensivePlayerReach(boolean alive, boolean removed, boolean visible, double squaredDistance) {
        return alive && !removed && visible && Double.isFinite(squaredDistance)
                && squaredDistance >= 0.0 && squaredDistance <= RETREAT_ATTACK_REACH_SQUARED;
    }

    static boolean retreatCounterattackCandidateAllowed(
            String entityType,
            boolean alive,
            boolean removed,
            boolean lineOfSight,
            double squaredDistance,
            boolean hostile,
            boolean targetingSelf) {
        String type = Objects.requireNonNullElse(entityType, "")
                .trim().toLowerCase(Locale.ROOT);
        if (!alive || removed || !lineOfSight || !hostile
                || !Double.isFinite(squaredDistance)
                || squaredDistance > RETREAT_ATTACK_REACH_SQUARED) return false;
        // Advancing a creeper fuse is not a defensive opening. A neutral
        // enderman likewise becomes a new problem if struck merely because it
        // happens to occupy the escape cell.
        if (type.equals("creeper")) return false;
        return !type.equals("enderman") || targetingSelf;
    }

    static boolean retreatSprintAllowed(
            int foodLevel,
            boolean usingItem,
            RetreatRouteKind routeKind) {
        return foodLevel > 6
                && !usingItem
                && routeKind != null;
    }

    static boolean rangedCoverHoldAllowed(
            boolean ranged,
            boolean lineOfSight,
            boolean shieldAvailable,
            boolean soleEligibleThreat) {
        return ranged && !lineOfSight && !shieldAvailable && soleEligibleThreat;
    }

    private record RetreatRoute(
            double dx,
            double dz,
            RetreatRouteKind kind,
            boolean jump,
            double originX,
            double originZ,
            int landingBlockX,
            int landingBlockZ,
            boolean sprintCorridorVerified) {
    }

    /** Stable episode identity plus the aggregate horizontal escape vector. */
    public record RetreatIntent(
            String episodeKey,
            double escapeX,
            double escapeZ,
            boolean allowAscent,
            boolean allowRangedCoverHold,
            boolean immediateHazardAvoidance) {
        public static final RetreatIntent NONE =
                new RetreatIntent("", 0.0, 0.0, false, false, false);

        public RetreatIntent(String episodeKey, double escapeX, double escapeZ) {
            this(episodeKey, escapeX, escapeZ, true, true, false);
        }

        public RetreatIntent(
                String episodeKey,
                double escapeX,
                double escapeZ,
                boolean allowAscent) {
            this(episodeKey, escapeX, escapeZ, allowAscent, true, false);
        }

        public RetreatIntent(
                String episodeKey,
                double escapeX,
                double escapeZ,
                boolean allowAscent,
                boolean allowRangedCoverHold) {
            this(episodeKey, escapeX, escapeZ,
                    allowAscent, allowRangedCoverHold, false);
        }

        public RetreatIntent {
            episodeKey = Objects.requireNonNullElse(episodeKey, "").trim();
            if (!Double.isFinite(escapeX) || !Double.isFinite(escapeZ)) {
                throw new IllegalArgumentException("retreat escape vector must be finite");
            }
            double length = Math.hypot(escapeX, escapeZ);
            if (length > 0.000_001) {
                escapeX /= length;
                escapeZ /= length;
            }
        }
    }

    record RetreatVector(double x, double z) {
    }

    record SurfaceDefenseOffset(double x, double z, boolean active) {
        static final SurfaceDefenseOffset NONE =
                new SurfaceDefenseOffset(0.0, 0.0, false);
    }

    record ShieldRetreatInput(boolean backward, boolean left, boolean right) {
    }

    private record ShieldChoice(Hand hand, int slot) {
    }

    enum FoodAvailability {
        UNAVAILABLE(false, false),
        UNRESERVED(true, false),
        RESERVED_FALLBACK(true, true);

        private final boolean eligible;
        private final boolean reservedFallback;

        FoodAvailability(boolean eligible, boolean reservedFallback) {
            this.eligible = eligible;
            this.reservedFallback = reservedFallback;
        }

        boolean eligible() {
            return eligible;
        }

        boolean reservedFallback() {
            return reservedFallback;
        }
    }

    private record FoodChoice(Hand hand, int slot, float score, boolean reservedFallback) {
    }

    private record MovementAuthority(
            ExecutionKernel authority,
            ControlLease parent,
            ActionLease action,
            long clientTick,
            long boundAtMillis) {
    }

    enum SuffocationFailureDisposition {
        RETRY_SAME_BLOCK,
        ABANDON_BLOCK
    }

    static SuffocationFailureDisposition suffocationFailureDisposition(
            AtomicBlockBreakController.State state,
            boolean healthDeadlineExpired) {
        Objects.requireNonNull(state, "state");
        if (healthDeadlineExpired
                || state == AtomicBlockBreakController.State.SERVER_REJECTED) {
            return SuffocationFailureDisposition.ABANDON_BLOCK;
        }
        return switch (state) {
            case TOOL_PREEMPTED, OUT_OF_REACH, SIGHTLINE_LOST,
                    ACTION_REJECTED, STALLED -> SuffocationFailureDisposition.RETRY_SAME_BLOCK;
            default -> throw new IllegalArgumentException(
                    "state is not a suffocation break failure: " + state);
        };
    }

    /** Health-deadline-bounded cooldown for retrying one still-intersecting block. */
    static final class SuffocationBreakRetry {
        private final long cooldownMillis;
        private long retryAt;
        private int failures;

        SuffocationBreakRetry(long cooldownMillis) {
            if (cooldownMillis < 1L) {
                throw new IllegalArgumentException("cooldownMillis must be positive");
            }
            this.cooldownMillis = cooldownMillis;
        }

        void recordTransient(
                AtomicBlockBreakController.State state,
                long now,
                long deadlineAt) {
            if (suffocationFailureDisposition(state, false)
                    != SuffocationFailureDisposition.RETRY_SAME_BLOCK) {
                throw new IllegalArgumentException("terminal failure cannot be retried");
            }
            long requested = now > Long.MAX_VALUE - cooldownMillis
                    ? Long.MAX_VALUE : now + cooldownMillis;
            retryAt = Math.min(Math.max(now, deadlineAt), requested);
            failures = failures == Integer.MAX_VALUE ? Integer.MAX_VALUE : failures + 1;
        }

        boolean waiting(long now) {
            return now < retryAt;
        }

        long remainingMillis(long now) {
            return Math.max(0L, retryAt - now);
        }

        int failures() {
            return failures;
        }

        void reset() {
            retryAt = 0L;
            failures = 0;
        }
    }

    public enum SurvivalExecution {
        ACTIVE,
        LAVA_SEARCHING,
        LAVA_PATHING,
        LAVA_NO_ROUTE,
        LAVA_BUCKET_ATTEMPTED,
        LAVA_ESCAPED,
        WATER_SEARCHING,
        WATER_PATHING,
        WATER_NO_ROUTE,
        BREATHING_AT_SURFACE,
        BREACHING_CEILING,
        SUFFOCATION_MOVING,
        SUFFOCATION_BREAKING,
        SUFFOCATION_NO_ESCAPE,
        SUFFOCATION_ESCAPED,
        EATING,
        SATIATED_WAIT,
        RETREATING,
        RETREAT_NO_ROUTE,
        EXISTING_WATER_LANDING,
        WATER_BUCKET_AIMING,
        WATER_BUCKET_ATTEMPTED,
        WATER_BUCKET_WAITING_ACK,
        WATER_BUCKET_WATER_CONFIRMED,
        WATER_BUCKET_RECOVERING,
        WATER_BUCKET_COMPLETE,
        FALL_TECHNIQUE_UNAVAILABLE,
        NO_USABLE_BUCKET,
        NO_USABLE_FOOD,
        FIRE_SEARCHING,
        FIRE_PATHING,
        FIRE_NO_ROUTE,
        FIRE_BUCKET_ATTEMPTED,
        FIRE_EXTINGUISHED,
        STALE_LEASE
    }

    public record SuffocationSnapshot(
            boolean active,
            boolean insideWall,
            String target,
            String direction,
            long deadlineRemainingMillis,
            int failedDirections,
            int failedBlocks,
            String detail) {
        public SuffocationSnapshot {
            target = Objects.requireNonNullElse(target, "");
            direction = Objects.requireNonNullElse(direction, "");
            detail = Objects.requireNonNullElse(detail, "");
            if (deadlineRemainingMillis < 0L || failedDirections < 0 || failedBlocks < 0) {
                throw new IllegalArgumentException("invalid suffocation snapshot counters");
            }
        }
    }

    public record LavaSnapshot(
            boolean active,
            boolean inLava,
            long commandedAtMillis,
            long commandClientTick,
            String commandMode,
            boolean forwardCommanded,
            boolean jumpCommanded,
            String target,
            int failedWaypoints,
            String detail) {
        public LavaSnapshot {
            commandMode = Objects.requireNonNullElse(commandMode, "");
            target = Objects.requireNonNullElse(target, "");
            detail = Objects.requireNonNullElse(detail, "");
            if (commandedAtMillis < 0L || commandClientTick < -1L || failedWaypoints < 0) {
                throw new IllegalArgumentException("invalid lava snapshot counters");
            }
        }
    }

    public record FireSnapshot(
            boolean active,
            boolean onFire,
            long commandedAtMillis,
            long commandClientTick,
            String commandMode,
            boolean forwardCommanded,
            boolean jumpCommanded,
            String target,
            int failedWaypoints,
            String detail,
            String defenseMode,
            String defenseTargetUuid,
            String defenseTargetType,
            boolean defenseShieldRaised,
            int fireTicks,
            int predictedRemainingHits,
            float predictedDamage,
            float healthReserve,
            boolean dryHoldSurvivable,
            String terminalKind) {
        public FireSnapshot {
            commandMode = Objects.requireNonNullElse(commandMode, "");
            target = Objects.requireNonNullElse(target, "");
            detail = Objects.requireNonNullElse(detail, "");
            defenseMode = Objects.requireNonNullElse(defenseMode, "");
            defenseTargetUuid = Objects.requireNonNullElse(defenseTargetUuid, "");
            defenseTargetType = Objects.requireNonNullElse(defenseTargetType, "");
            terminalKind = Objects.requireNonNullElse(terminalKind, "none");
            if (commandedAtMillis < 0L || commandClientTick < -1L || failedWaypoints < 0
                    || predictedRemainingHits < 0
                    || !Float.isFinite(predictedDamage) || predictedDamage < 0.0F
                    || !Float.isFinite(healthReserve) || healthReserve < 0.0F) {
                throw new IllegalArgumentException("invalid fire snapshot counters");
            }
        }
    }

    public record RetreatCounterattackEvent(
            long sequence,
            long issuedAtMillis,
            long playerAge,
            long controlEpoch,
            String episodeKey,
            String targetUuid,
            String targetType,
            String weaponId,
            float cooldownProgress,
            double distance) {
        public RetreatCounterattackEvent {
            episodeKey = Objects.requireNonNullElse(episodeKey, "");
            targetUuid = Objects.requireNonNullElse(targetUuid, "");
            targetType = Objects.requireNonNullElse(targetType, "");
            weaponId = Objects.requireNonNullElse(weaponId, "");
            if (sequence <= 0L || issuedAtMillis < 0L || playerAge < -1L
                    || !Float.isFinite(cooldownProgress)
                    || cooldownProgress < 0.0F || cooldownProgress > 1.0F
                    || !Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("invalid retreat counterattack event");
            }
        }
    }
}
