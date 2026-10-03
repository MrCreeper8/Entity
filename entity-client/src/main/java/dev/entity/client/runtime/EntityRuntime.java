package dev.entity.client.runtime;

import baritone.api.BaritoneAPI;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.entity.client.autonomy.AutonomyExecutor;
import dev.entity.client.autonomy.collection.ForeignItemClaimClientState;
import dev.entity.client.autonomy.policy.CombatLoadoutCoordinator;
import dev.entity.client.autonomy.policy.DeathReconciliationTokenPolicy;
import dev.entity.client.autonomy.policy.HomeEconomyPolicy;
import dev.entity.client.autonomy.policy.HomeEconomySession;
import dev.entity.client.autonomy.policy.HomeFurnitureBindingPolicy;
import dev.entity.client.autonomy.policy.FieldKitLedger;
import dev.entity.client.autonomy.policy.HomeThreatShelterPolicy;
import dev.entity.client.autonomy.policy.IdleStockPolicy;
import dev.entity.client.autonomy.policy.TacticalCombatPolicy;
import dev.entity.client.baritone.FabricBaritonePort;
import dev.entity.client.baritone.CombatAttackJournal;
import dev.entity.client.bridge.BridgeClient;
import dev.entity.client.bridge.BridgeResultPolicy;
import dev.entity.client.bridge.DeliveryProtocol;
import dev.entity.client.bridge.EntityRelationshipPolicyProtocol;
import dev.entity.client.bridge.ForeignItemClaimProtocol;
import dev.entity.client.bridge.TechniqueCapabilityFrames;
import dev.entity.client.bridge.ProtectedAreaPolicyProtocol;
import dev.entity.client.bridge.ServerDamageEvent;
import dev.entity.client.bridge.ServerThreatTargetEvent;
import dev.entity.client.command.CommandTranslator;
import dev.entity.client.command.IncomingCommand;
import dev.entity.client.config.EntityClientConfig;
import dev.entity.client.control.DirectBodyController;
import dev.entity.client.control.ActionLease;
import dev.entity.client.control.ActionOwner;
import dev.entity.client.control.ExecutionKernel;
import dev.entity.client.control.MinecraftActuatorGateway;
import dev.entity.client.control.MinecraftEntityAttackGateway;
import dev.entity.client.control.MinecraftMovementGateway;
import dev.entity.client.control.MovementFrameActuator;
import dev.entity.client.platform.FabricPlatformPort;
import dev.entity.client.protection.CombatTargetResolutionWatchdog;
import dev.entity.client.protection.DistantThreatArbitrationView;
import dev.entity.client.protection.DistantThreatDisengagementPolicy;
import dev.entity.client.protection.ProtectionArbitrationView;
import dev.entity.client.protection.ProtectionReactivationHandoff;
import dev.entity.client.protection.MinecraftHomeThreatShelterBoundary;
import dev.entity.client.protection.MinecraftPropertyCreeperBoundary;
import dev.entity.client.protection.ProtectedAreaClientState;
import dev.entity.client.protection.ProtectedAreaClientStore;
import dev.entity.client.protection.SealedCombatResolutionSession;
import dev.entity.client.protection.SoleTargetSuppressionSession;
import dev.entity.client.protection.TacticalProtectionPlan;
import dev.entity.client.recipe.PaperRecipeCatalogAssembler;
import dev.entity.client.recipe.PaperRecipeDiscoveryRetry;
import dev.entity.client.telemetry.BlackBoxFrames;
import dev.entity.client.telemetry.BlackBoxRecorder;
import dev.entity.client.telemetry.BehaviorCircuitBreaker;
import dev.entity.client.telemetry.BehaviorInvariantMonitor;
import dev.entity.client.telemetry.CriticalBlackBoxEvents;
import dev.entity.client.telemetry.DecisionJournal;
import dev.entity.client.telemetry.MissionStatusReporter;
import dev.entity.client.telemetry.TelemetryFrames;
import dev.entity.core.EntityCore;
import dev.entity.core.control.ControlLease;
import dev.entity.core.control.BodyChannel;
import dev.entity.core.control.ControlPriority;
import dev.entity.core.farm.ManagedFarmPolicy;
import dev.entity.core.model.Mission;
import dev.entity.core.model.MissionState;
import dev.entity.core.persistence.JsonMissionStore;
import dev.entity.core.persistence.JsonProtectionSettingsStore;
import dev.entity.core.port.BaritonePort;
import dev.entity.core.progress.ProgressMonitor;
import dev.entity.core.progress.BlockedReconciliationPolicy;
import dev.entity.core.protection.ProtectionPolicy;
import dev.entity.core.survival.SurvivalAction;
import dev.entity.core.survival.SurvivalSupervisor;
import dev.entity.core.survival.WorldSnapshot;
import dev.entity.core.stewardship.PropertyCreeperSafetyPolicy;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Main-thread orchestrator. Bridge I/O only enqueues immutable JSON frames. */
public final class EntityRuntime implements AutoCloseable, BridgeClient.Listener {
    private static final long INVARIANT_INCIDENT_COOLDOWN_MILLIS = 10_000L;
    private static final long RECENT_INVARIANT_RETENTION_MILLIS = 60_000L;
    private static final int MAX_RECENT_INVARIANT_VIOLATIONS = 12;
    private static final int INVARIANT_BLOCK_HORIZONTAL_RADIUS = 2;
    private static final int INVARIANT_BLOCK_BELOW = 3;
    private static final int INVARIANT_BLOCK_ABOVE = 2;
    private static final long TACTICAL_SPACING_INTERCEPT_MILLIS = 3_000L;
    private static final long STALLED_SEPARATION_RESOLUTION_MILLIS = 10_000L;
    private static final long HOME_IDLE_LEASE_TTL_MILLIS = 10_000L;
    private static final long MISSION_CARGO_COMPLETION_FENCE_MILLIS = 5_000L;
    private static final long AUTOMATIC_FARM_OBSERVATION_INTERVAL_MILLIS = 5_000L;

    private final MinecraftClient client;
    private final EntityClientConfig config;
    private final Logger logger;
    private final JsonProtectionSettingsStore protectionSettingsStore;
    private final EntityCore core;
    private final BlockedReconciliationPolicy blockedReconciliation = new BlockedReconciliationPolicy();
    private final ExecutionKernel executionKernel;
    private final MinecraftActuatorGateway actuators;
    private final MinecraftEntityAttackGateway entityAttacks;
    private final MinecraftMovementGateway movement;
    private final FabricBaritonePort baritone;
    private final PerceptionModeControl perception;
    private final IdleSettingsStore idleSettings;
    private final ManagedFarmAutomationStore farmAutomation;
    private final IdleWorkCoordinator idleWork = new IdleWorkCoordinator();
    private IdleContext idleContext;
    private long idleAuthenticatedAt;
    private List<AutonomyExecutor.ManagedFarmAutomaticObservation> automaticFarmObservations =
            List.of();
    private long automaticFarmObservedAt = Long.MIN_VALUE;
    private String automaticFarmMissionId = "";
    private String automaticFarmArea = "";
    private String automaticFarmWorkKey = "";
    private final AutonomyExecutor autonomy;
    private final dev.entity.client.blueprint.BlueprintProjects blueprints;
    private volatile boolean blueprintImportPending;
    private volatile boolean blueprintClosed;
    private boolean blueprintRestartFence=true;
    private final FiniteStockCommands stockCommands;
    private final DirectBodyController body;
    private final FabricPlatformPort platform;
    private final DecisionJournal journal;
    private final BlackBoxRecorder blackBox;
    private final WorldScopeGate worldScopeGate;
    private final CriticalBlackBoxEvents criticalBlackBoxEvents =
            new CriticalBlackBoxEvents();
    private Object criticalObservationPlayerSession;
    private Object criticalObservationWorldSession;
    private final BehaviorInvariantMonitor behaviorInvariants = new BehaviorInvariantMonitor();
    private final BehaviorCircuitBreaker behaviorCircuitBreaker = new BehaviorCircuitBreaker();
    private final BehaviorInvariantMonitor.IncidentOutbox invariantIncidentOutbox =
            new BehaviorInvariantMonitor.IncidentOutbox(INVARIANT_INCIDENT_COOLDOWN_MILLIS);
    private final Deque<BehaviorInvariantMonitor.Violation> recentInvariantViolations =
            new ArrayDeque<>();
    private final Map<Long, Boolean> invariantLocalBlocks = new HashMap<>();
    private final BridgeClient bridge;
    private final ProtectedAreaClientState protectedAreas;
    private final ForeignItemClaimClientState foreignItems;
    private final MinecraftHomeThreatShelterBoundary homeThreatShelter;
    private final MissionStatusReporter missionReporter = new MissionStatusReporter();
    private String homeGoCommandMissionId = "";
    private final ProgressMonitor protectionProgress = new ProgressMonitor(6_000, 0.5, 0.25);
    private final CombatTargetResolutionWatchdog protectionTargetResolution =
            new CombatTargetResolutionWatchdog();
    private final ConcurrentLinkedQueue<JsonObject> inbound = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RecipeConnectionEvent> recipeConnections =
            new ConcurrentLinkedQueue<>();
    private final PaperRecipeCatalogAssembler paperRecipes =
            new PaperRecipeCatalogAssembler();
    private final PaperRecipeDiscoveryRetry paperRecipeDiscoveryRetry =
            new PaperRecipeDiscoveryRetry();

    private EntityCore.TickDecision decision;
    private EntityCore.Layer previousLayer = EntityCore.Layer.IDLE;
    private ControlLease homeEconomyLease;
    private final OperatorStopLatch operatorStop = new OperatorStopLatch();
    private final ExplicitSleepContinuation explicitSleepContinuation = new ExplicitSleepContinuation();
    private int ticksUntilTelemetry;
    private List<String> blueprintCatalogIds = List.of();
    private boolean closed;
    private boolean buildIdentityHold;
    private String lastBuildIdentityHoldDetail = "";
    private boolean baritoneFallDelegated;
    private boolean directProtectionRetreat;
    private boolean protectionObservationOnly;
    private long directRetreatFallbackUntil;
    private TacticalProtectionPlan.EngagementIntent directRetreatFallbackIntent;
    private String directRetreatFallbackTargetId = "";
    private boolean directRetreatFallbackResolvesStalledSeparation;
    private final SealedCombatResolutionSession sealedResolution =
            new SealedCombatResolutionSession();
    private final SoleTargetSuppressionSession soleTargetSuppression =
            new SoleTargetSuppressionSession();
    private final DistantThreatDisengagementPolicy distantThreatDisengagement =
            new DistantThreatDisengagementPolicy();
    private DistantThreatDisengagementPolicy.Decision currentDistantThreatDecision;
    private String currentDistantThreatTargetId = "";
    private final ProtectionReactivationHandoff protectionReactivation =
            new ProtectionReactivationHandoff();
    private final AquaticSafetyMissionHandoff aquaticSafetyMissionHandoff =
            new AquaticSafetyMissionHandoff();
    private final MissionCargoCompletionFence missionCargoCompletionFence =
            new MissionCargoCompletionFence(MISSION_CARGO_COMPLETION_FENCE_MILLIS);
    private String lastFoodReservationMissionId = "";
    private WorldSnapshot latestRawWorld = WorldSnapshot.safe(0L);
    private String protectionProgressKey = "";
    private final RespawnCoordinator respawnCoordinator = new RespawnCoordinator();
    private final DeathLoopGuard deathLoopGuard = new DeathLoopGuard();
    private final DeathLoopRecoveryStore deathLoopStore;
    private boolean deathLoopStateUntrusted;
    private String deathLoopLoadWarning = "";
    private String deathMissionId = "";
    private String deathStatus = "";
    private long deathRecoveryBeganAt;
    private boolean deathRecoveryPersistencePending;
    private DeathLoopRecoveryStore.RecoveryPhase deathRecoveryPhase =
            DeathLoopRecoveryStore.RecoveryPhase.PENDING;
    private DeathLoopGuard.Decision deathLoopDecision =
            new DeathLoopGuard.Decision(false, 0, false, "");
    private final LinkedHashSet<String> deferredDeathLoopClears = new LinkedHashSet<>();
    private String lastBlackBoxIncidentKey = "";
    private String lastHomeRouteIncidentKey = "";
    private long lastRecordedCombatAttackSequence;
    private long lastRecordedAttackObjectiveRevision;
    private long lastRecordedRetreatCounterattackSequence;
    private long lastInvariantRetreatCounterattackSequence;
    private long lastSampledCombatAttackSequence;
    private long lastRecordedInventoryTimeoutSequence;
    private BehaviorInvariantMonitor.StateSnapshot invariantState = behaviorInvariants.stateSnapshot();
    private Object invariantWorldSession;
    private String invariantBlockDimension = "";
    private String invariantMissionId = "";
    private String invariantPlanLeaf = "";
    private long invariantObjectiveHighWater;
    private long invariantWorkHighWater;
    private long invariantBaritoneGeneration = -1L;
    private long invariantBaritoneWork;
    private long invariantAutonomyWork;
    private String invariantAutonomyMissionId = "";
    private long invariantAutonomyWorkSample;
    private long invariantAutonomyWorkSampleTick = -1L;
    private long lastInvariantWarningAt;
    private long behaviorCircuitBreakerTrips;
    private String behaviorCircuitBreakerStatus = "armed";
    private long clientTick;
    private ProtectionPolicy.ActivityContext protectionActivity =
            ProtectionPolicy.ActivityContext.IDLE;
    private final ProtectionActivityClassifier.ProtectionModeLatch protectionModeLatch =
            new ProtectionActivityClassifier.ProtectionModeLatch();
    private final TacticalProtectionPlan.RetreatIntentSession protectionRetreatIntent =
            new TacticalProtectionPlan.RetreatIntentSession();
    private final TacticalProtectionPlan.SafetyRetreatLatch protectionSafetyRetreat =
            new TacticalProtectionPlan.SafetyRetreatLatch();
    private DirectBodyController.RetreatIntent aquaticDefenseIntent =
            DirectBodyController.RetreatIntent.NONE;
    private long protectionLoadoutGeneration = -1L;
    private boolean fireDefenseTargetSessionActive;
    private String propertyCreeperSafetyKey = "";
    private String homeThreatShelterKey = "";
    private BaritonePort.Goal homeThreatShelterGoal;
    private String homeThreatShelterDetail = "";

    public EntityRuntime(
            MinecraftClient client,
            EntityClientConfig config,
            Path installationDirectory,
            Path worldDataDirectory,
            WorldStateScope expectedWorldScope,
            Logger logger) throws IOException {
        this.client = Objects.requireNonNull(client, "client");
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        Path globalDataDirectory = Objects.requireNonNull(
                installationDirectory, "installationDirectory").toAbsolutePath().normalize();
        Path dataDirectory = Objects.requireNonNull(
                worldDataDirectory, "worldDataDirectory").toAbsolutePath().normalize();
        this.worldScopeGate = new WorldScopeGate(expectedWorldScope);
        this.idleSettings = new IdleSettingsStore(dataDirectory, expectedWorldScope);
        this.farmAutomation = new ManagedFarmAutomationStore(
                dataDirectory, expectedWorldScope);
        this.blueprints = new dev.entity.client.blueprint.BlueprintProjects(dataDirectory,
                expectedWorldScope == null ? "" : expectedWorldScope.key(),
                new dev.entity.client.blueprint.BlueprintCatalog(globalDataDirectory.resolve("blueprints")));
        blueprintCatalogIds = blueprints.catalog().list();
        long initializedAt = System.currentTimeMillis();
        this.deathLoopStore = new DeathLoopRecoveryStore(
                dataDirectory.resolve("death-recovery.json"));
        DeathLoopRecoveryStore.LoadResult deathLoopLoad = deathLoopStore.load();
        this.deathLoopStateUntrusted = deathLoopLoad.failClosed();
        this.deathLoopLoadWarning = deathLoopLoad.warning();
        deathLoopGuard.restore(deathLoopLoad.state().guard(), initializedAt);
        deathLoopLoad.state().pending().ifPresent(pending -> {
            deathMissionId = pending.missionId();
            deathLoopDecision = pending.decision();
            deathRecoveryBeganAt = pending.beganAtMillis();
            deathRecoveryPhase = pending.phase();
            deathStatus = "restored unfinished death recovery for mission "
                    + shortId(pending.missionId());
        });
        if (deathLoopStateUntrusted) logger.error(deathLoopLoadWarning);
        this.protectionSettingsStore = new JsonProtectionSettingsStore(
                globalDataDirectory.resolve("protection.json"));
        JsonProtectionSettingsStore.LoadResult protectionLoad = protectionSettingsStore.load();
        if (protectionLoad.recoveredWithDisabledDefaults()) {
            logger.warn("{}; both protection toggles were safely disabled", protectionLoad.warning());
        }
        int drowningEnter = config.survival().drowningAirThreshold();
        int drowningExit = Math.min(299, Math.max(280, drowningEnter + 20));
        this.core = new EntityCore(
                new JsonMissionStore(dataDirectory.resolve("missions.json")),
                protectionLoad.settings(),
                new SurvivalSupervisor(
                        config.survival().safeTicksBeforeResume() * 50L,
                        drowningEnter,
                        drowningExit,
                        SurvivalSupervisor.FALL_ENTER_DISTANCE));
        this.executionKernel = new ExecutionKernel(core.arbiter());
        this.protectedAreas = new ProtectedAreaClientState(new ProtectedAreaClientStore(
                dataDirectory.resolve("protected-areas.json")));
        this.foreignItems = new ForeignItemClaimClientState();
        this.actuators = MinecraftActuatorGateway.shared(client);
        this.actuators.installProtectedAreaPolicy(protectedAreas);
        this.entityAttacks = MinecraftEntityAttackGateway.shared(client);
        this.movement = MinecraftMovementGateway.shared(client);
        this.baritone = new FabricBaritonePort(
                client, core.arbiter(), dataDirectory, logger);
        this.perception = new PerceptionModeControl(
                new PerceptionSettingsStore(dataDirectory, expectedWorldScope),
                legitimate -> baritone.resourcePerception().setLegitimate(legitimate));
        this.baritone.installProtectedAreaPolicy(protectedAreas);
        this.baritone.blueprintProjects(blueprints);
        this.homeThreatShelter = new MinecraftHomeThreatShelterBoundary(
                client, baritone::homeInteriorObservation);
        this.autonomy = new AutonomyExecutor(
                client, baritone, executionKernel, foreignItems, dataDirectory, logger);
        this.autonomy.blueprintProjects(blueprints);
        this.stockCommands = new FiniteStockCommands(dataDirectory,
                expectedWorldScope == null ? "" : expectedWorldScope.key(), initializedAt);
        this.homeGoCommandMissionId = core.missions().stream()
                .filter(mission -> "true".equals(mission.parameters().get("homeGoCommand")))
                .max(Comparator.comparingLong(Mission::createdAtMillis)).map(Mission::id).orElse("");
        core.missions().stream()
                .filter(EntityRuntime::isAutomaticFarmMission)
                .filter(mission -> !mission.state().terminal())
                .max(Comparator.comparingLong(Mission::createdAtMillis))
                .ifPresent(mission -> {
                    automaticFarmMissionId = mission.id();
                    automaticFarmArea = mission.parameters().getOrDefault("area", "");
                    automaticFarmWorkKey = mission.parameters().getOrDefault(
                            "automaticFarmWorkKey", "");
                    if (!automaticFarmWorkKey.isBlank()) {
                        idleWork.restoreFarm(automaticFarmWorkKey);
                    }
                });
        MinecraftPropertyCreeperBoundary creeperPropertyBoundary =
                new MinecraftPropertyCreeperBoundary(
                        client,
                        protectedAreas,
                        this::creeperWorkContexts);
        this.entityAttacks.installCreeperBoundary(creeperPropertyBoundary::decide);
        try {
            this.autonomy.retireJournaledMissionPlans(core.missions(), initializedAt);
        } catch (IOException | RuntimeException error) {
            logger.warn("Could not reconcile bounded terminal plan evidence at startup: {}",
                    error.getMessage());
        }
        this.body = new DirectBodyController(
                client,
                BaritoneAPI.getProvider().getPrimaryBaritone(),
                core.arbiter(),
                logger,
                actuators);
        this.autonomy.setFoodConsumptionPending(body::foodConsumptionPending);
        this.platform = new FabricPlatformPort(client, body, core::protectionSettings, logger);
        this.journal = new DecisionJournal(
                globalDataDirectory.resolve("decisions.ndjson"), logger);
        this.blackBox = new BlackBoxRecorder(
                globalDataDirectory.resolve("diagnostics"),
                warning -> logger.warn("Entity black box: {}", warning));
        this.bridge = new BridgeClient(config.bridge(), this, logger);
        this.protectedAreas.installHomePermitSender(frame ->
                bridge.hasFeature(ProtectedAreaPolicyProtocol.HOME_PERMIT_FEATURE)
                        && bridge.send(frame));
        this.autonomy.installProtectedAreaPolicy(protectedAreas);
        this.autonomy.setProtocolSender(bridge::send);
        this.autonomy.setProtocolFeatureChecker(bridge::hasFeature);
        this.ticksUntilTelemetry = config.telemetryIntervalTicks();
        this.blackBox.lifecycle(
                initializedAt, "runtime_created", "Entity runtime initialized with "
                        + bridge.buildIdentity().display());
        if (deathLoopStateUntrusted) {
            this.blackBox.incident(
                    initializedAt,
                    "death_recovery_state_untrusted",
                    deathLoopLoadWarning,
                    null,
                    null);
        }
    }

    public void start() {
        if (!config.hasUsableToken()) {
            logger.warn("Entity 2 local developer mode (commands offline) with {}",
                    bridge.buildIdentity().display());
            return;
        }
        logger.info("Entity 2 awaiting an exact server pair for {}",
                bridge.buildIdentity().display());
        bridge.start();
    }

    public void tick() {
        if (closed) return;
        clientTick++;
        long now = System.currentTimeMillis();
        try {
            // Establish death/orphan identity before any queued command, catalog update,
            // inventory click, or mission synchronization can mutate the selected mission.
            boolean quarantineHold = establishDeathBoundary(now);
            boolean identityHold = !quarantineHold && requiresIdentityHold();
            if (quarantineHold) {
                setRecoveryBoundaryDecision(
                        "RECOVERY_QUARANTINE",
                        "untrusted death-recovery state was quarantined; ordinary execution resumes next tick");
            } else if (identityHold) {
                enforceBuildIdentityHold(now);
            } else {
                leaveBuildIdentityHold(now);
                drainRecipeConnections(now);
                drainInbound(now);
                retryPaperRecipeDiscoveryAfterJoin();
                refreshWorldRouteAndHomeInterior();
            }
            if (!quarantineHold && !identityHold && !handleRespawnCycle(now)) {
                retryDeferredDeathLoopClears(now);
                autonomy.reconcileInventoryTransactions(now);
                // Inventory ownership is decided before the survival snapshot. This
                // keeps mission cargo (for example, sixteen cooked beef to deliver)
                // unavailable to ordinary hunger recovery while still allowing the
                // separately budgeted travel ration to be eaten.
                Optional<Mission> activeMission = core.activeMission();
                Map<String, Integer> foodReservations =
                        autonomy.survivalProtectedReservations(activeMission.orElse(null));
                if (activeMission.isPresent()) {
                    Mission mission = activeMission.orElseThrow();
                    lastFoodReservationMissionId = mission.id();
                    foodReservations = missionCargoCompletionFence.observeActive(
                            mission.id(), foodReservations, now);
                } else if (!lastFoodReservationMissionId.isBlank()) {
                    Mission terminal = core.mission(lastFoodReservationMissionId).orElse(null);
                    if (terminal != null && terminal.state() == MissionState.COMPLETED) {
                        foodReservations = missionCargoCompletionFence.observeCompleted(
                                terminal.id(), now);
                    } else {
                        missionCargoCompletionFence.release(lastFoodReservationMissionId);
                        lastFoodReservationMissionId = "";
                    }
                }
                body.setFoodReservations(foodReservations);
                ProtectionWorkContext protectionWork=protectionWork(now);
                protectionModeLatch.reclassifyForMission(protectionWork.identity());
                protectionActivity = protectionModeLatch.resolve(classifyProtectionActivity(protectionWork));
                // Keep the platform's full observation as the sole source for tactical policy,
                // telemetry, and black-box evidence. Only the immutable view passed into core
                // arbitration may omit one already-proven quiet sole UUID.
                WorldSnapshot rawWorld = platform.observe(now);
                latestRawWorld = rawWorld;
                behaviorCircuitBreaker.retainCombatTargets(rawWorld.threats().stream()
                        .map(WorldSnapshot.Threat::entityId).toList());
                String suppressionEpisodeId = protectionWork.identity();
                soleTargetSuppression.retainEpisode(suppressionEpisodeId);
                WorldSnapshot arbitrationWorld;
                if (operatorStop.stopped()) {
                    // Stop suppresses discretionary protection as well as mission/Home work. Raw
                    // truth remains available to telemetry while environmental survival and idle
                    // flotation retain the physical world except attacker authority.
                    arbitrationWorld = ProtectionArbitrationView.omitAll(rawWorld);
                } else {
                    WorldSnapshot suppressionView = coreSuppressionView(
                            rawWorld, suppressionEpisodeId, now);
                    arbitrationWorld = coreDistantThreatDisengagementView(
                            rawWorld, suppressionView, suppressionEpisodeId, now);
                }
                boolean homeBodyExecutionActive = !operatorStop.stopped()
                        && (homeEconomyLease != null
                        || autonomy.homeEconomyNeedsIdleLease(now));
                decision = core.tick(
                        arbitrationWorld, protectionActivity, homeBodyExecutionActive);
                tickAutomaticIdleWork(now, decision.layer());
                executeDecision(decision, now);
                observeExplicitSleepReturn(now);
                publishAutomaticMissionState(decision);
                stockCommands.observe(autonomy.stockCommandObservation());
                flushStockCommandResults();
                publishHomeGoCommandState();
                decision = telemetryDecision(decision);
            }
            // Route memory is physical evidence, so sample it at the real client-tick
            // cadence. The bounded mission poll can skip an intermediate occupied block
            // while Entity is sprinting and must not be the only route recorder.
            if (!quarantineHold && !identityHold) {
                baritone.observeFunctionalMiningRoute(now);
            }
        } catch (IOException error) {
            logger.error("Entity core persistence failed; stopping body controls", error);
            blackBox.incident(
                    now, "persistence_failure", "Entity core persistence failed", error,
                    this::blackBoxSnapshot);
            long epoch = core.emergencyStop("persistence failure", now);
            homeEconomyLease = null;
            baritone.cancel(epoch, "persistence failure");
            autonomy.releaseControls("persistence failure");
            body.cancelControls(epoch, "persistence failure");
            actuators.neutralizeAll("persistence failure");
            protectionModeLatch.clear();
            protectionSafetyRetreat.clear();
            baritone.clearProtectionTraversalScope();
        } catch (RuntimeException error) {
            logger.error("Entity tick failed", error);
            blackBox.incident(
                    now, "tick_failure", "Entity tick execution failed", error,
                    this::blackBoxSnapshot);
            handleExecutionFailure(error, now);
        }
        captureBehaviorInvariants(now);
        journal.capture(core.trace());
        captureBlackBox(now);
        if (--ticksUntilTelemetry <= 0) {
            ticksUntilTelemetry = config.telemetryIntervalTicks();
            JsonObject telemetry = TelemetryFrames.snapshot(
                    client,
                    decision,
                    baritone.snapshot(),
                    body.waterLocomotionSnapshot(),
                    core.trace().explainLatest());
            ControllerTelemetryProjection.Status controllerStatus =
                    ControllerTelemetryProjection.from(
                            currentControllerStatus(), platform.publishedStatus());
            telemetry.addProperty("state", controllerStatus.state());
            telemetry.addProperty("message", controllerStatus.message());
            telemetry.addProperty("perceptionMode", perception.mode());
            telemetry.add("idleWork", idleWorkFrame(now));
            if(client.player!=null && client.world!=null)
                telemetry.add("companionFacts",autonomy.companionSelfFacts(core.activeMission().orElse(null),now));
            telemetry.add("blueprintCatalog", blueprintCatalogFrame());
            bridge.send(telemetry);
            bridge.send(TechniqueCapabilityFrames.snapshot(
                    body.fallTechniqueInventory(), now));
        }
    }

    private boolean requiresIdentityHold() {
        return config.hasUsableToken()
                && (!bridge.connected() || !worldScopeGate.verified());
    }

    /** Refreshes world-scoped route memory and shelter geometry without granting property. */
    private void refreshWorldRouteAndHomeInterior() {
        if (client.world == null) {
            baritone.clearFunctionalRouteWorldIdentity();
        } else {
            String dimension = client.world.getRegistryKey().getValue().toString();
            bridge.worldIdentity(dimension).ifPresentOrElse(
                    identity -> baritone.installFunctionalRouteWorldIdentity(
                            dimension, identity),
                    baritone::clearFunctionalRouteWorldIdentity);
        }
        autonomy.homeAnchor().ifPresentOrElse(
                baritone::observeHomeInterior,
                baritone::clearHomeInterior);
    }

    /** Prevents any queued mission from reaching Minecraft before the exact paired jar is proven. */
    private void enforceBuildIdentityHold(long now) {
        String failure = bridge.lastHandshakeFailure();
        String detail;
        if (bridge.connected() && !worldScopeGate.verified()) {
            detail = worldScopeGate.detail();
        } else {
            detail = failure.isBlank()
                    ? "waiting for exact Entity2 client/server build identity"
                    : "exact Entity2 build identity rejected: " + failure;
        }
        if (!buildIdentityHold) {
            explicitSleepContinuation.clear();
            behaviorCircuitBreaker.clearCombatTargets();
            buildIdentityHold = true;
            long epoch = core.arbiter().invalidateAll();
            homeEconomyLease = null;
            baritone.cancel(epoch, detail);
            autonomy.releaseControls(detail, clientTick);
            body.cancelControls(epoch, detail);
            actuators.neutralizeAll(detail);
            protectionModeLatch.clear();
            protectionSafetyRetreat.clear();
            baritone.clearProtectionTraversalScope();
        }
        if (!detail.equals(lastBuildIdentityHoldDetail)) {
            lastBuildIdentityHoldDetail = detail;
            logger.warn("{}; local={}", detail, bridge.buildIdentity().display());
            blackBox.incident(now, "build_identity_hold", detail, null, this::blackBoxSnapshot);
        }
        platform.publishStatus(detail);
        setRecoveryBoundaryDecision("BUILD_IDENTITY_HOLD", detail);
    }

    private void leaveBuildIdentityHold(long now) {
        if (!buildIdentityHold) return;
        buildIdentityHold = false;
        lastBuildIdentityHoldDetail = "";
        blackBox.lifecycle(now, "build_identity_verified", "Exact paired build accepted: "
                + bridge.buildIdentity().display());
    }

    /**
     * Produces the one filtered arbitration view without mutating the raw platform observation.
     * SUPPRESSED is keyed to the durable mission ID, so the intentional first MISSION result and
     * normal non-PROTECTION cleanup cannot erase it on the following tick.
     */
    private WorldSnapshot coreSuppressionView(
            WorldSnapshot rawWorld,
            String episodeId,
            long nowMillis) {
        if (soleTargetSuppression.state() == SoleTargetSuppressionSession.State.NONE
                || episodeId == null || episodeId.isBlank()) {
            return protectionReactivation.peek()
                    .map(targetId -> ProtectionArbitrationView.reactivateExact(
                            rawWorld, targetId))
                    .orElse(rawWorld);
        }
        String targetId = soleTargetSuppression.targetId();
        boolean survivalActive = previousLayer == EntityCore.Layer.SURVIVAL
                || immediateSuppressionSurvivalRisk(rawWorld);
        SoleTargetSuppressionSession.SafetyObservation safety =
                platform.soleTargetSuppressionObservation(
                        episodeId,
                        targetId,
                        core.protectionSettings(),
                        protectionActivity,
                        nowMillis,
                        survivalActive);
        SoleTargetSuppressionSession.Arbitration arbitration =
                soleTargetSuppression.arbitrate(safety);
        if (arbitration
                == SoleTargetSuppressionSession.Arbitration.REACTIVATE_EXACT_TARGET
                || arbitration
                == SoleTargetSuppressionSession.Arbitration.PROTECT_EXACT_TARGET) {
            if (arbitration
                    == SoleTargetSuppressionSession.Arbitration.REACTIVATE_EXACT_TARGET) {
                baritone.clearProtectionTraversalScope();
            }
            protectionReactivation.arm(targetId);
            return ProtectionArbitrationView.reactivateExact(rawWorld, targetId);
        }
        if (soleTargetSuppression.state() == SoleTargetSuppressionSession.State.NONE) {
            baritone.clearProtectionTraversalScope();
        }
        if (arbitration
                != SoleTargetSuppressionSession.Arbitration.SUPPRESS_EXACT_TARGET) {
            return rawWorld;
        }
        WorldSnapshot filtered = ProtectionArbitrationView.omitExact(rawWorld, targetId);
        if (filtered == rawWorld) {
            // Observation changed between candidate state and the raw snapshot. Fail open to core
            // protection rather than claiming suppression without removing the exact UUID.
            soleTargetSuppression.clear();
            return rawWorld;
        }
        return filtered;
    }

    /**
     * Applies the separated/quiet ordinary-threat downgrade only to the core arbitration copy.
     * Platform tactical state, {@link #latestRawWorld}, black-box evidence, and telemetry retain
     * the complete unmodified threat list on every tick.
     */
    private WorldSnapshot coreDistantThreatDisengagementView(
            WorldSnapshot rawWorld,
            WorldSnapshot currentArbitrationView,
            String episodeId,
            long nowMillis) {
        // The bounded route-failure session and the physical-separation policy solve different
        // problems and must compose.  The former prevents another Baritone attack-route loop;
        // the latter is what allows a visible ordinary melee mob that is no longer closing to
        // stop owning PROTECTION.  Giving the route-failure session exclusive precedence left
        // Entity oscillating forever in DirectBody retreat against exactly that quiet mob.
        // DistantThreatArbitrationView applies to the already-produced suppression view, so an
        // unsafe/closing target is still reintroduced from raw truth in the same tick while a
        // separated quiet target can finally release the interrupted mission.
        if (episodeId == null || episodeId.isBlank()
                || core.protectionSettings().combatMode()
                == ProtectionPolicy.CombatMode.AGGRESSIVE) {
            clearDistantThreatDisengagement();
            return currentArbitrationView;
        }
        String exactTargetId = distantThreatDisengagement.state()
                == DistantThreatDisengagementPolicy.State.IDLE
                ? ""
                : distantThreatDisengagement.targetId();
        boolean survivalActive = previousLayer == EntityCore.Layer.SURVIVAL
                || immediateSuppressionSurvivalRisk(rawWorld);
        Optional<DistantThreatDisengagementPolicy.Observation> observation =
                platform.distantThreatDisengagementObservation(
                        episodeId,
                        exactTargetId,
                        core.protectionSettings(),
                        protectionActivity,
                        protectionModeLatch.threatKey(),
                        nowMillis,
                        survivalActive);
        if (observation.isEmpty()) {
            clearDistantThreatDisengagement();
            return currentArbitrationView;
        }
        DistantThreatDisengagementPolicy.Observation facts = observation.orElseThrow();
        DistantThreatDisengagementPolicy.Decision disengagement =
                distantThreatDisengagement.observe(facts);
        currentDistantThreatDecision = disengagement;
        currentDistantThreatTargetId = facts.targetId();
        if (disengagement.action() == DistantThreatDisengagementPolicy.Action.PROTECT
                || disengagement.action()
                == DistantThreatDisengagementPolicy.Action.CREATE_SEPARATION
                || disengagement.action()
                == DistantThreatDisengagementPolicy.Action.MAINTAIN_SEPARATION) {
            // The projected core view and raw adapter selection are one exact-identity
            // capability. Retain it through ownership arming or a temporary SURVIVAL layer.
            protectionReactivation.arm(facts.targetId());
        }
        return DistantThreatArbitrationView.apply(
                rawWorld,
                currentArbitrationView,
                facts.targetId(),
                disengagement,
                core.protectionSettings().maximumEngageDistance());
    }

    private void clearDistantThreatDisengagement() {
        distantThreatDisengagement.clear();
        platform.clearDistantThreatRetention();
        currentDistantThreatDecision = null;
        currentDistantThreatTargetId = "";
    }

    /** Conservative, stateless subset of facts that can activate the survival layer this tick. */
    private static boolean immediateSuppressionSurvivalRisk(WorldSnapshot world) {
        double healthFraction = world.health() / world.maximumHealth();
        boolean recovery = healthFraction
                <= SurvivalSupervisor.HEALTH_RECOVERY_ENTER_FRACTION
                && (world.consumableFoodAvailable()
                || world.foodLevel() >= SurvivalSupervisor.NATURAL_REGEN_FOOD_LEVEL);
        boolean hungerRecovery = world.consumableFoodAvailable() && world.foodLevel() <= 14;
        return world.inLava()
                || world.onFire()
                || world.insideWall()
                || !world.onGround()
                && world.fallDistance() >= SurvivalSupervisor.FALL_ENTER_DISTANCE
                || world.headSubmerged()
                || recovery
                || hungerRecovery;
    }

    /**
     * Establishes the fail-closed cross-store boundary before inbound work. Returning true
     * requests one neutral quarantine tick; a concrete pending recovery is advanced later by
     * handleRespawnCycle under its durable mission identity.
     */
    private boolean establishDeathBoundary(long now) throws IOException {
        Optional<Mission> orphanedDeathPause = core.missions().stream()
                .filter(mission -> DeathRecoveryLifecyclePolicy
                        .orphanedDeathPauseRequiresOperator(
                                mission.state(), mission.pauseReason()))
                .sorted(Comparator.comparingLong(Mission::createdAtMillis).thenComparing(Mission::id))
                .findFirst();
        var player = client.player;
        boolean dead = player != null && (player.isDead() || player.getHealth() <= 0.0F);
        if (player != null && !dead) {
            autonomy.observeAliveInventoryForDeathRecovery(now);
        }
        boolean freshDeath = dead && !respawnCoordinator.active() && deathRecoveryBeganAt <= 0L;
        return switch (DeathRecoveryLifecyclePolicy.startupBoundary(
                deathLoopStateUntrusted,
                !deathMissionId.isBlank(),
                orphanedDeathPause.isPresent(),
                freshDeath)) {
            case GLOBAL_QUARANTINE -> quarantineUntrustedDeathState(now);
            case EXISTING_RECOVERY, NONE -> false;
            case ORPHANED_DEATH_PAUSE -> {
                Mission mission = orphanedDeathPause.orElseThrow();
                deathMissionId = mission.id();
                deathRecoveryBeganAt = now;
                deathRecoveryPhase = DeathLoopRecoveryStore.RecoveryPhase.PENDING;
                deathLoopDecision = failClosedDeathDecision(
                        "the persisted death pause has no matching recovery decision");
                deathStatus = "restored unfinished death recovery for mission "
                        + shortId(mission.id()) + "; " + deathLoopDecision.reason();
                deathRecoveryPersistencePending = true;
                persistDeathLoopState(now, true);
                yield false;
            }
            case FRESH_DEATH -> {
                beginDeathRecovery(now);
                yield false;
            }
        };
    }

    private boolean quarantineUntrustedDeathState(long now) throws IOException {
        String reason = deathLoopLoadWarning.isBlank()
                ? "durable death-recovery state was untrusted"
                : deathLoopLoadWarning;
        List<Mission> nonterminal = core.missions().stream()
                .filter(mission -> DeathRecoveryLifecyclePolicy
                        .canBindInterruptedMission(mission.state()))
                .sorted(Comparator.comparingLong(Mission::createdAtMillis).thenComparing(Mission::id))
                .toList();
        LinkedHashMap<String, String> rebaseTokens = new LinkedHashMap<>();

        // MissionStore is the durable cross-store work ledger. Blocking first
        // records the stable token before PlanStore is touched. A crash between
        // the two stores is recovered from this marker on the next startup.
        for (Mission mission : nonterminal) {
            Optional<String> retained = DeathRecoveryLifecyclePolicy
                    .untrustedQuarantineToken(mission.state(), mission.lastError());
            String token = retained.orElseGet(() ->
                    DeathReconciliationTokenPolicy.token(mission.id(), now));
            if (retained.isEmpty()) {
                core.block(
                        mission.id(),
                        DeathRecoveryLifecyclePolicy.untrustedQuarantineReason(token),
                        now);
            }
            rebaseTokens.put(mission.id(), token);
        }

        for (Map.Entry<String, String> work : rebaseTokens.entrySet()) {
            autonomy.reconcileAfterDeath(
                    work.getKey(), work.getValue(), clientTick, now);
        }

        // Replace corrupt/ambiguous state only after every nonterminal mission
        // is durably operator-gated and every hierarchical plan has crossed its
        // tokenized root-rebase fence. A failure leaves the pre-inbound hold.
        persistDeathLoopState(now, false);
        deathMissionId = "";
        deathStatus = "";
        deathRecoveryBeganAt = 0L;
        deathRecoveryPersistencePending = false;
        deathRecoveryPhase = DeathLoopRecoveryStore.RecoveryPhase.PENDING;
        deathLoopDecision = new DeathLoopGuard.Decision(false, 0, false, "");
        blackBox.incident(
                now,
                "death_recovery_global_quarantine",
                reason + "; quarantined and root-rebased " + nonterminal.size() + " mission(s)",
                null,
                this::blackBoxSnapshot);
        platform.publishStatus(
                "Death recovery state was untrusted; quarantined and root-rebased "
                        + nonterminal.size() + " mission(s) for explicit retry");
        return true;
    }

    private boolean handleRespawnCycle(long now) throws IOException {
        var player = client.player;
        boolean present = player != null;
        boolean dead = present && (player.isDead() || player.getHealth() <= 0.0F);
        if (deathRecoveryPersistencePending && !deathMissionId.isBlank()) {
            persistDeathLoopState(now, true);
        }
        if (!deathMissionId.isBlank()
                && (deathRecoveryPhase == DeathLoopRecoveryStore.RecoveryPhase.FINALIZED
                || (deathRecoveryPhase
                        == DeathLoopRecoveryStore.RecoveryPhase.EFFECTS_STARTED
                && deathRecoveryEffectsAlreadyApplied()))) {
            // This path is independent of the client player. A restart or a
            // transient clear failure after completion may only retire the
            // durable journal; it must never replay plan/core side effects.
            finishDeathRecovery(now);
            setRecoveryBoundaryDecision(
                    "RECOVERY_FINALIZED",
                    "death recovery finalized; ordinary execution resumes next tick");
            return true;
        }
        if (DeathRecoveryLifecyclePolicy.waitForPlayerBeforeReconciliation(
                deathMissionId, present)) {
            setRespawnWaitingDecision();
            return true;
        }
        RespawnCoordinator.Decision state = respawnCoordinator.observe(present, dead, now);
        if (state.requestRespawn() && client.player != null) {
            client.player.requestRespawn();
            platform.publishStatus(deathStatus + "; respawn requested, waiting for Paper");
        }
        if (state.respawned() && deathMissionId.isBlank()) {
            if (client.currentScreen instanceof DeathScreen) client.setScreen(null);
            deathRecoveryBeganAt = 0L;
            deathStatus = "";
            deathRecoveryPhase = DeathLoopRecoveryStore.RecoveryPhase.PENDING;
            deathLoopDecision = new DeathLoopGuard.Decision(false, 0, false, "");
            platform.publishStatus("respawned; no interrupted mission required reconciliation");
            setRecoveryBoundaryDecision(
                    "RESPAWNED",
                    "respawn completed; ordinary execution resumes next tick");
            return true;
        }
        if ((state.respawned() && present && !dead)
                || (!state.waiting() && present && !dead && !deathMissionId.isBlank())) {
            // The second branch retries a failed persistence/rebase transaction
            // on the next tick instead of silently leaving the mission paused.
            finishDeathRecovery(now);
            setRecoveryBoundaryDecision(
                    "RECOVERY_FINALIZED",
                    "death recovery finalized; ordinary execution resumes next tick");
            return true;
        }
        if (!state.waiting()) return false;

        setRespawnWaitingDecision();
        return true;
    }

    private void setRespawnWaitingDecision() {
        setRecoveryBoundaryDecision(
                "RESPAWNING",
                deathStatus.isBlank() ? "waiting to respawn" : deathStatus);
    }

    private void setRecoveryBoundaryDecision(String action, String reason) {
        Mission visible = core.activeMission().orElse(null);
        decision = new EntityCore.TickDecision(
                EntityCore.Layer.WAITING,
                action,
                reason,
                visible,
                Optional.empty(),
                core.arbiter().epoch());
    }

    private void beginDeathRecovery(long now) throws IOException {
        baritone.attackMissionObjective().clear(now);
        explicitSleepContinuation.clear();
        if (idleWork.active() == IdleWorkCoordinator.Job.DAY_STOCK
                || idleWork.active() == IdleWorkCoordinator.Job.NIGHT_STOCK
                || idleWork.active() == IdleWorkCoordinator.Job.SLEEP)
            cancelAutomaticIdleWork("Death revoked automatic Home work", now);
        var player = client.player;
        String location = "unknown location";
        String cause = "unknown cause";
        String causeKey = "unknown";
        net.minecraft.util.math.BlockPos deathPosition = null;
        String deathDimension = "";
        if (player != null) {
            var pos = player.getBlockPos();
            String dimension = client.world == null
                    ? "unknown dimension"
                    : client.world.getRegistryKey().getValue().toString();
            deathPosition = pos.toImmutable();
            deathDimension = client.world == null ? "" : dimension;
            location = pos.getX() + " " + pos.getY() + " " + pos.getZ() + " in " + dimension;
            var damage = player.getRecentDamageSource();
            if (damage != null) {
                cause = damage.getDeathMessage(player).getString();
                causeKey = damage.getAttacker() == null
                        ? stableDeathCauseKey(damage.getName())
                        : "combat";
            }
        }

        preemptHomeEconomy(
                HomeEconomyPolicy.Authority.DEATH_RECOVERY,
                now,
                "death recovery preempted Home economy");

        deathMissionId = "";
        deathRecoveryBeganAt = now;
        deathRecoveryPhase = DeathLoopRecoveryStore.RecoveryPhase.PENDING;
        deathLoopDecision = new DeathLoopGuard.Decision(false, 0, false, "");
        Optional<Mission> selected = core.activeMission();
        if (selected.isPresent() && DeathRecoveryLifecyclePolicy
                .canBindInterruptedMission(selected.get().state())) {
            Mission mission = selected.orElseThrow();
            deathMissionId = mission.id();
            if (deathPosition != null) {
                if (!deathDimension.isBlank()) {
                    deathLoopDecision = deathLoopGuard.observe(new DeathLoopGuard.Observation(
                            mission.id(),
                            deathDimension,
                            deathPosition.getX(),
                            deathPosition.getY(),
                            deathPosition.getZ(),
                            causeKey,
                            now));
                }
            }
        }

        if (!deathMissionId.isBlank() && deathLoopStateUntrusted) {
            deathLoopDecision = failClosedDeathDecision(
                    "the durable death-recovery journal was untrusted at startup");
        }

        boolean recoveryStatePersisted = deathMissionId.isBlank();
        if (!deathMissionId.isBlank()) {
            deathRecoveryPersistencePending = true;
            try {
                // Flush the pending decision before the separate mission-pause store. A crash at
                // any later instruction can therefore reconstruct the same bounded decision.
                persistDeathLoopState(now, true);
                recoveryStatePersisted = true;
            } catch (IOException persistenceFailure) {
                deathLoopDecision = failClosedDeathDecision(
                        "the durable death-recovery decision could not be written");
                deathLoopStateUntrusted = true;
                logger.error(
                        "Death-recovery journal write failed; this recovery will require an operator decision",
                        persistenceFailure);
                blackBox.incident(
                        now,
                        "death_recovery_persistence_failure",
                        deathLoopDecision.reason(),
                        persistenceFailure,
                        this::blackBoxSnapshot);
                try {
                    // A transient replace/flush failure gets one conservative retry carrying the
                    // fail-closed decision. A persistent disk failure still cannot skip the core
                    // death pause below.
                    persistDeathLoopState(now, true);
                    recoveryStatePersisted = true;
                } catch (IOException retryFailure) {
                    persistenceFailure.addSuppressed(retryFailure);
                }
            }
        }
        if (!deathMissionId.isBlank() && deathPosition != null && recoveryStatePersisted) {
            Mission mission = selected.orElseThrow();
            try {
                // The recovery journal is the first durable mutation at a
                // death boundary. PlanStore cargo capture is deliberately
                // second, so a crash can never leave an unowned plan mutation.
                String captured = autonomy.captureDeathContext(
                        mission, deathPosition, deathDimension, now);
                logger.info("Death recovery checkpoint for {}: {}", mission.id(), captured);
            } catch (IOException | RuntimeException captureFailure) {
                logger.error(
                        "Death cargo checkpoint failed for {}; continuing with the persisted death pause",
                        mission.id(),
                        captureFailure);
                blackBox.incident(
                        now,
                        "death_context_capture_failure",
                        "Cargo/death context capture failed; death pause and circuit state continue",
                        captureFailure,
                        this::blackBoxSnapshot);
            }
        }
        long epoch = core.prepareForRespawn(
                deathMissionId,
                "death interrupted mission: " + cause + " at " + location,
                now);
        if (!deathMissionId.isBlank()) {
            publishMissionState(deathMissionId, "Entity died; mission retained for automatic recovery");
        }
        baritone.cancel(epoch, "Entity died; awaiting respawn");
        autonomy.releaseControls("Entity died; awaiting respawn", clientTick);
        body.cancelControls(epoch, "Entity died; awaiting respawn");
        actuators.neutralizeAll("Entity died; awaiting respawn");
        movement.neutralizeAll(MovementFrameActuator.Cleanup.DEATH);
        clearProtectionProgress();
        protectionModeLatch.clear();
        protectionSafetyRetreat.clear();
        soleTargetSuppression.clear();
        behaviorCircuitBreaker.clearCombatTargets();
        clearDistantThreatDisengagement();
        protectionReactivation.clear();
        baritone.clearProtectionTraversalScope();
        directProtectionRetreat = false;
        clearDirectRetreatFallback();
        baritoneFallDelegated = false;
        aquaticSafetyMissionHandoff.clear();
        previousLayer = EntityCore.Layer.IDLE;
        deathStatus = "died at " + location + " (" + cause + "); mission "
                + (deathMissionId.isBlank() ? "none" : shortId(deathMissionId) + " retained")
                + (deathLoopDecision.haltAutomaticRecovery()
                ? "; " + deathLoopDecision.reason()
                : "");
        blackBox.incident(now, "death", deathStatus, null, this::blackBoxSnapshot);
        if (!deathMissionId.isBlank()) {
            DeathLoopGuard.Decision guard = deathLoopDecision;
            String eventMissionId = deathMissionId;
            String eventCauseKey = causeKey;
            boolean eventStatePersisted = recoveryStatePersisted;
            blackBox.event(now, "death_recovery_budget", () -> {
                JsonObject event = new JsonObject();
                event.addProperty("missionId", eventMissionId);
                event.addProperty("causeKey", eventCauseKey);
                event.addProperty("missionDeaths", guard.missionDeaths());
                event.addProperty("repeatedSiteAndCause", guard.repeatedSiteAndCause());
                event.addProperty("haltAutomaticRecovery", guard.haltAutomaticRecovery());
                event.addProperty("durableStatePersisted", eventStatePersisted);
                event.addProperty("reason", guard.reason());
                return event;
            });
        }
        platform.publishStatus(deathStatus + "; opening automatic respawn window");
    }

    private void finishDeathRecovery(long now) throws IOException {
        if (deathMissionId.isBlank()) return;
        if (DeathRecoveryLifecyclePolicy.journalClearOnly(deathRecoveryPhase)) {
            clearFinalizedDeathRecovery(now, "respawned; finalized death recovery journal cleared");
            return;
        }
        if (deathRecoveryPhase == DeathLoopRecoveryStore.RecoveryPhase.EFFECTS_STARTED
                && deathRecoveryEffectsAlreadyApplied()) {
            finalizeDeathRecovery(
                    now,
                    "respawned; recovered the already-completed mission state without replaying effects");
            return;
        }

        if (client.currentScreen instanceof DeathScreen) client.setScreen(null);
        String reconciliation = "no hierarchical plan to reconcile";
        DeathRecoveryLifecyclePolicy.Completion completion =
                DeathRecoveryLifecyclePolicy.Completion.CLEAR_ONLY;
        MissionState preservedState = null;
        Optional<Mission> interrupted = core.mission(deathMissionId);
        DeathRecoveryLifecyclePolicy.FinishPlan finish =
                DeathRecoveryLifecyclePolicy.finishPlan(
                        interrupted.map(Mission::state).orElse(null),
                        interrupted.map(Mission::pauseReason).orElse(""),
                        deathLoopDecision.haltAutomaticRecovery());
        if (deathRecoveryPhase == DeathLoopRecoveryStore.RecoveryPhase.PENDING) {
            if (finish.normalizeDeathPause()) {
                core.prepareForRespawn(
                        deathMissionId,
                        "death interrupted mission: restored pending recovery after a crash boundary",
                        now);
            }
            // EFFECTS_STARTED is written only after any crash-window mission
            // normalization is durable. Therefore RUNNING/BLOCKED in this
            // phase can later prove that resume/block already committed.
            deathRecoveryPhase = DeathLoopRecoveryStore.RecoveryPhase.EFFECTS_STARTED;
            deathRecoveryPersistencePending = true;
            persistDeathLoopState(now, true);
            interrupted = core.mission(deathMissionId);
            finish = DeathRecoveryLifecyclePolicy.finishPlan(
                    interrupted.map(Mission::state).orElse(null),
                    interrupted.map(Mission::pauseReason).orElse(""),
                    deathLoopDecision.haltAutomaticRecovery());
        }

        if (deathRecoveryPhase != DeathLoopRecoveryStore.RecoveryPhase.EFFECTS_STARTED) {
            throw new IOException("unsupported death recovery phase " + deathRecoveryPhase);
        }
        if (deathRecoveryEffectsAlreadyApplied()) {
            finalizeDeathRecovery(
                    now,
                    "respawned; recovered the already-completed mission state without replaying effects");
            return;
        }
        completion = finish.completion();
        String recoveryToken = DeathReconciliationTokenPolicy.token(
                deathMissionId, deathRecoveryBeganAt);
        reconciliation = autonomy.reconcileAllPlansAfterDeath(
                recoveryToken, clientTick, now);
        // Mission synchronization is allowed while the body is held at the
        // death boundary. If it already restored RUNNING after EFFECTS_STARTED,
        // the plan token—not state alone—is the proof that stale children were
        // discarded. Finish without trying to replay the narrow safety resume.
        if (deathRecoveryEffectsAlreadyApplied()) {
            finalizeDeathRecovery(
                    now,
                    "respawned; " + reconciliation
                            + "; recovered the already-completed mission state");
            return;
        }
        if (finish.reconcilePlan()) {
            if (completion == DeathRecoveryLifecyclePolicy.Completion.BLOCK) {
                core.block(deathMissionId, deathLoopDecision.reason(), now);
                publishMissionState(
                        deathMissionId,
                        "Respawned; " + reconciliation + "; automatic recovery stopped: "
                                + deathLoopDecision.reason());
                blackBox.incident(
                        now,
                        "death_loop_blocked",
                        deathLoopDecision.reason(),
                        null,
                        this::blackBoxSnapshot);
            } else if (completion
                    == DeathRecoveryLifecyclePolicy.Completion.AUTO_RESUME) {
                boolean resumed = core.resumeAfterRespawn(
                        deathMissionId,
                        reconciliation + "; resuming from current inventory truth",
                        now);
                if (!resumed) {
                    throw new IOException(
                            "Death recovery could not prove the persisted safety pause for mission "
                                    + shortId(deathMissionId));
                }
                publishMissionState(
                        deathMissionId,
                        "Respawned; " + reconciliation + "; resuming from current inventory truth");
            } else {
                preservedState = core.mission(deathMissionId)
                        .map(Mission::state)
                        .orElse(null);
                publishMissionState(
                        deathMissionId,
                        "Respawned; " + reconciliation + "; preserving " + preservedState
                                + " until its existing operator/retry decision changes");
            }
        }
        String resumed = deathMissionId.isBlank()
                ? "no mission to resume"
                : switch (completion) {
                    case BLOCK -> "mission " + shortId(deathMissionId)
                            + " remains blocked for an operator decision after " + reconciliation;
                    case AUTO_RESUME -> "resuming mission " + shortId(deathMissionId)
                            + " after " + reconciliation;
                    case PRESERVE_STATE -> "mission " + shortId(deathMissionId)
                            + " remains " + preservedState + " after " + reconciliation;
                    case CLEAR_ONLY -> "no executable mission remained to reconcile";
                };
        finalizeDeathRecovery(now, "respawned; " + resumed);
    }

    private boolean deathRecoveryEffectsAlreadyApplied() {
        Optional<Mission> mission = core.mission(deathMissionId);
        MissionState state = mission.map(Mission::state).orElse(null);
        String recoveryToken = DeathReconciliationTokenPolicy.token(
                deathMissionId, deathRecoveryBeganAt);
        boolean recoveryBlockApplied = state == MissionState.BLOCKED
                && mission.map(Mission::lastError).orElse("")
                        .equals(deathLoopDecision.reason());
        return DeathRecoveryLifecyclePolicy.completionAlreadyApplied(
                deathRecoveryPhase,
                state,
                deathLoopDecision.haltAutomaticRecovery(),
                autonomy.allOpenPlansReconciledAfterDeath(recoveryToken),
                recoveryBlockApplied);
    }

    private void finalizeDeathRecovery(long now, String status) throws IOException {
        deathRecoveryPhase = DeathLoopRecoveryStore.RecoveryPhase.FINALIZED;
        deathRecoveryPersistencePending = true;
        // The finalized marker is the durable commit record. A failure here
        // leaves FINALIZED in memory; the next tick retries only this save.
        persistDeathLoopState(now, true);
        clearFinalizedDeathRecovery(now, status);
    }

    private void clearFinalizedDeathRecovery(long now, String status) throws IOException {
        if (deathRecoveryPhase != DeathLoopRecoveryStore.RecoveryPhase.FINALIZED) {
            throw new IOException("death recovery journal cannot clear before finalization");
        }
        // Clear storage before resetting memory. If replacement/flush fails,
        // the in-memory and durable FINALIZED phase both fence every effect.
        persistDeathLoopState(now, false);
        platform.publishStatus(status);
        deathMissionId = "";
        deathStatus = "";
        deathRecoveryBeganAt = 0L;
        deathRecoveryPhase = DeathLoopRecoveryStore.RecoveryPhase.PENDING;
        deathLoopDecision = new DeathLoopGuard.Decision(false, 0, false, "");
    }

    private void persistDeathLoopState(long now, boolean includePending) throws IOException {
        Optional<DeathLoopRecoveryStore.PendingRecovery> pending =
                includePending && !deathMissionId.isBlank()
                        ? Optional.of(new DeathLoopRecoveryStore.PendingRecovery(
                                deathMissionId,
                                deathLoopDecision,
                                deathRecoveryBeganAt > 0L ? deathRecoveryBeganAt : now,
                                deathRecoveryPhase))
                        : Optional.empty();
        deathLoopStore.save(new DeathLoopRecoveryStore.State(
                deathLoopGuard.snapshot(now), pending));
        deathRecoveryPersistencePending = false;
        deathLoopStateUntrusted = false;
        deathLoopLoadWarning = "";
    }

    private void clearDeathLoopState(String missionId, long now) throws IOException {
        if (missionId == null || missionId.isBlank()) return;
        String normalized = missionId.trim();
        DeathLoopGuard.Snapshot priorGuard = deathLoopGuard.snapshot(now);
        deathLoopGuard.clear(normalized);
        boolean clearsPending = normalized.equals(deathMissionId);
        Optional<DeathLoopRecoveryStore.PendingRecovery> pending =
                !clearsPending && !deathMissionId.isBlank()
                        ? Optional.of(new DeathLoopRecoveryStore.PendingRecovery(
                                deathMissionId,
                                deathLoopDecision,
                                deathRecoveryBeganAt > 0L ? deathRecoveryBeganAt : now,
                                deathRecoveryPhase))
                        : Optional.empty();
        try {
            deathLoopStore.save(new DeathLoopRecoveryStore.State(
                    deathLoopGuard.snapshot(now), pending));
        } catch (IOException | RuntimeException failure) {
            // A rejected operator command may never erase the in-memory safety budget.
            deathLoopGuard.restore(priorGuard, now);
            throw failure;
        }
        if (clearsPending) deathRecoveryPersistencePending = false;
        deathLoopStateUntrusted = false;
        deathLoopLoadWarning = "";
        if (clearsPending) {
            deathMissionId = "";
            deathStatus = "";
            deathRecoveryBeganAt = 0L;
            deathRecoveryPhase = DeathLoopRecoveryStore.RecoveryPhase.PENDING;
            deathLoopDecision = new DeathLoopGuard.Decision(false, 0, false, "");
        }
    }

    private void acknowledgeDeathLoopAfterCommittedLifecycle(String missionId, long now) {
        if (missionId == null || missionId.isBlank()) return;
        String normalized = missionId.trim();
        try {
            clearDeathLoopState(normalized, now);
            deferredDeathLoopClears.remove(normalized);
        } catch (IOException | RuntimeException failure) {
            if (deferredDeathLoopClears.size() >= 64) {
                deferredDeathLoopClears.remove(deferredDeathLoopClears.iterator().next());
            }
            deferredDeathLoopClears.add(normalized);
            logger.error(
                    "Mission lifecycle committed, but its death-loop acknowledgement will be retried for {}",
                    normalized,
                    failure);
            blackBox.incident(
                    now,
                    "death_loop_acknowledgement_deferred",
                    "Mission lifecycle committed; retained the conservative death-loop budget for retry",
                    failure,
                    this::blackBoxSnapshot);
        }
    }

    private void retryDeferredDeathLoopClears(long now) {
        if (deferredDeathLoopClears.isEmpty()) return;
        for (String missionId : List.copyOf(deferredDeathLoopClears)) {
            try {
                clearDeathLoopState(missionId, now);
                deferredDeathLoopClears.remove(missionId);
            } catch (IOException | RuntimeException failure) {
                logger.debug(
                        "Deferred death-loop acknowledgement still pending for {}: {}",
                        missionId,
                        Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName()));
                break;
            }
        }
    }

    private void rejectLifecycleMutationDuringDeathRecovery(String operation) {
        if (!DeathRecoveryLifecyclePolicy.lifecycleMutationBlocked(
                deathMissionId, operation)) return;
        throw new IllegalArgumentException(
                "Cannot " + operation + " a mission while death recovery for "
                        + shortId(deathMissionId)
                        + " is awaiting respawn reconciliation; wait for respawn or use /e stop");
    }

    private static DeathLoopGuard.Decision failClosedDeathDecision(String detail) {
        return new DeathLoopGuard.Decision(
                true,
                0,
                false,
                "automatic recovery stopped because " + Objects.requireNonNullElse(detail, "")
                        + "; use /retry, /resume, or /stop to acknowledge the decision");
    }

    private static String stableDeathCauseKey(String raw) {
        String cause = Objects.requireNonNullElse(raw, "").trim().toLowerCase(Locale.ROOT);
        return cause.isEmpty() ? "unknown" : cause;
    }

    private void publishAutomaticMissionState(EntityCore.TickDecision source) {
        if (source == null || source.mission() == null) return;
        core.mission(source.mission().id()).ifPresent(mission -> {
            boolean publish = switch (mission.state()) {
                case RUNNING, RETRY_WAIT, BLOCKED, COMPLETED, CANCELLED -> true;
                case QUEUED, PAUSED_BY_OWNER, PAUSED_BY_SAFETY, PAUSED_BY_PROTECTION -> false;
            };
            if (publish) {
                if ("true".equals(mission.parameters().get("homeGoCommand"))) {
                    if (!missionReporter.delivered(mission))
                        sendMissionResult(mission.commandId(), true, mission, source.reason());
                } else missionReporter.publish(mission, source.reason(), bridge::send);
            }
        });
    }

    private void executeDecision(EntityCore.TickDecision next, long now) throws IOException {
        synchronizeAttackMissionObjective(next, now);
        aquaticSafetyMissionHandoff.observe(
                next.layer() == EntityCore.Layer.SURVIVAL
                        && SurvivalAction.SURFACE_FOR_AIR.name().equals(next.action()),
                next.mission() == null ? "" : next.mission().id());
        if (next.layer() != EntityCore.Layer.IDLE
                && (homeEconomyLease != null
                || previousLayer == EntityCore.Layer.IDLE
                && autonomy.homeEconomyNeedsIdleLease(now)
                || autonomy.homeEconomyNeedsForegroundFence()
                || autonomy.homePreemptionDrainPending())) {
            AutonomyExecutor.HomePreemptionOutcome homeDrain = preemptHomeEconomy(
                    homeAuthority(next.layer()), now,
                    "home economy preempted by "
                            + next.layer().name().toLowerCase(Locale.ROOT));
            if (!homeDrain.terminal() && homeDrain.inventoryActuationBlocked()) {
                baritone.cancel(next.controlEpoch(), "waiting for exact Home inventory drain");
                body.clear("waiting for exact Home inventory drain");
                platform.publishStatus("home preemption: " + homeDrain.detail());
                return;
            }
        }
        if (next.layer() != EntityCore.Layer.PROTECTION
                && next.layer() != EntityCore.Layer.SURVIVAL) {
            protectionReactivation.clear();
            baritone.clearProtectionTraversalScope();
        }
        boolean fireSurvival = next.layer() == EntityCore.Layer.SURVIVAL
                && SurvivalAction.EXTINGUISH_FIRE.name().equals(next.action());
        if (!fireSurvival) clearFireDefenseTargetSession();
        if (next.layer() != EntityCore.Layer.PROTECTION) {
            clearHomeThreatShelterState();
            // Route/progress ownership is invalid outside protection, but SURVIVAL is explicitly a
            // temporary preemption of the same protection episode. Its wall time must continue to
            // count against an ACTIVE sealed target's six-second no-resolution watchdog.
            clearProtectionRouteProgress();
            if (next.layer() != EntityCore.Layer.SURVIVAL) {
                protectionTargetResolution.reset();
            }
        }
        // Survival is a temporary higher-priority interruption. Retain the
        // protection episode's pre-threat activity through it so the same
        // attacker cannot return in the opposite mode merely because the
        // survival reflex moved (or stopped) the player. Normal mission/idle
        // resumption ends the episode and permits a fresh classification.
        if (next.layer() != EntityCore.Layer.PROTECTION
                && next.layer() != EntityCore.Layer.SURVIVAL) {
            protectionModeLatch.clear();
            protectionSafetyRetreat.clear();
            platform.clearTacticalProtectionTargetSession();
            clearProtectionLoadoutEpisode();
            endAquaticDefenseEpisode();
        }
        if (next.layer() != EntityCore.Layer.PROTECTION && directProtectionRetreat) {
            if (next.layer() == EntityCore.Layer.SURVIVAL
                    && SurvivalAction.SURFACE_FOR_AIR.name().equals(next.action())) {
                body.clearForAquaticHandoff("protection escape temporarily yields for air");
            } else body.clear("low-health protection retreat ended");
            directProtectionRetreat = false;
        }
        if (next.layer() != EntityCore.Layer.PROTECTION
                && next.layer() != EntityCore.Layer.SURVIVAL) {
            clearDirectRetreatFallback();
        }
        if (next.layer() == EntityCore.Layer.SURVIVAL) {
            protectionLoadoutGeneration = -1L;
            SurvivalAction action = SurvivalAction.valueOf(next.action());
            if (action == SurvivalAction.SURFACE_FOR_AIR) {
                refreshAquaticDefenseIntent(now);
            } else {
                body.clearAquaticDefenseIntent();
            }
            boolean preserveCommittedWaterLanding = action == SurvivalAction.FALL_CLUTCH
                    && baritone.hasCommittedExistingWaterLanding();
            if (preserveCommittedWaterLanding) {
                baritoneFallDelegated = true;
                platform.publishStatus(
                        "fall recovery: preserving Baritone's exact loaded existing-water landing; no bucket placement requested");
                previousLayer = next.layer();
                return;
            }
            if (previousLayer != EntityCore.Layer.SURVIVAL) {
                autonomy.releaseControls("survival preemption", clientTick);
                if (action == SurvivalAction.SURFACE_FOR_AIR) {
                    baritone.suspendForAir(next.controlEpoch(), next.reason());
                } else {
                    baritone.cancel(next.controlEpoch(), next.reason());
                }
                if (action == SurvivalAction.SURFACE_FOR_AIR) {
                    body.clearForAquaticHandoff("survival preemption boundary");
                } else body.clear("survival preemption boundary");
            } else if (baritoneFallDelegated) {
                baritone.cancel(next.controlEpoch(), "managed fall ended; switching to direct survival reflex");
                body.clear("managed fall ended");
            }
            baritoneFallDelegated = false;
            ControlLease survivalLease = next.lease().orElse(null);
            Optional<ActionLease> survivalAction = acquireRuntimeAction(
                    survivalLease,
                    ActionOwner.SURVIVAL,
                    "survival:" + action.name().toLowerCase(Locale.ROOT),
                    now);
            if (survivalAction.isEmpty()) {
                platform.publishStatus(action.name().toLowerCase(Locale.ROOT)
                        + ": arming the exclusive survival movement owner");
                previousLayer = next.layer();
                return;
            }
            body.bindMovementAction(
                    executionKernel, survivalLease, survivalAction.orElseThrow(), clientTick, now);
            body.setAquaticDefenseIntent(action == SurvivalAction.SURFACE_FOR_AIR
                    ? aquaticDefenseIntent
                    : DirectBodyController.RetreatIntent.NONE);
            net.minecraft.entity.Entity survivalThreat =
                    action == SurvivalAction.EXTINGUISH_FIRE
                            ? activeFireDefenseTarget(now)
                            .map(FabricPlatformPort.ProtectionTarget::entity)
                            .orElse(null)
                            : action == SurvivalAction.RECOVER_HEALTH
                                    ? platform.selectedProtectionDecision(
                                            core.protectionSettings(), next.protectionThreat())
                                            .map(FabricPlatformPort.ProtectionTarget::entity).orElse(null)
                                    : null;
            DirectBodyController.SurvivalExecution execution =
                    body.executeSurvival(survivalLease, action, survivalThreat);
            String detail = switch (execution) {
                case WATER_SEARCHING, WATER_PATHING, WATER_NO_ROUTE, BREATHING_AT_SURFACE,
                        BREACHING_CEILING -> body.waterPlanDetail();
                case LAVA_SEARCHING, LAVA_PATHING, LAVA_NO_ROUTE,
                        LAVA_BUCKET_ATTEMPTED, LAVA_ESCAPED -> body.lavaPlanDetail();
                case FIRE_SEARCHING, FIRE_PATHING, FIRE_NO_ROUTE,
                        FIRE_BUCKET_ATTEMPTED, FIRE_EXTINGUISHED -> {
                    String defense = body.fireDefenseDetail();
                    yield body.firePlanDetail()
                            + (defense.isBlank() ? "" : "; " + defense);
                }
                case SUFFOCATION_MOVING, SUFFOCATION_BREAKING,
                        SUFFOCATION_NO_ESCAPE, SUFFOCATION_ESCAPED ->
                        body.suffocationPlanDetail();
                case EATING -> "eating available hotbar/offhand food";
                case SATIATED_WAIT -> "finished eating; waiting for the recovery hold to clear";
                case NO_USABLE_FOOD -> "no immediately usable hotbar/offhand food remains";
                case RETREATING -> "creating distance from the active threat";
                case RETREAT_NO_ROUTE -> "no direct safe retreat cell; falling back to interception";
                case EXISTING_WATER_LANDING -> body.fallTechniqueDetail();
                case WATER_BUCKET_AIMING -> body.fallTechniqueDetail();
                case WATER_BUCKET_ATTEMPTED -> body.fallTechniqueDetail();
                case WATER_BUCKET_WAITING_ACK -> body.fallTechniqueDetail();
                case WATER_BUCKET_WATER_CONFIRMED -> body.fallTechniqueDetail();
                case WATER_BUCKET_RECOVERING -> body.fallTechniqueDetail();
                case WATER_BUCKET_COMPLETE -> body.fallTechniqueDetail();
                case FALL_TECHNIQUE_UNAVAILABLE -> body.fallTechniqueDetail();
                case NO_USABLE_BUCKET -> "no usable hotbar/offhand water bucket; safe landing not guaranteed";
                case STALE_LEASE -> "survival controls lost their lease";
                case ACTIVE -> next.reason();
            };
            platform.publishStatus(action.name().toLowerCase(Locale.ROOT) + ": " + detail);
            previousLayer = next.layer();
            return;
        }

        if (previousLayer == EntityCore.Layer.SURVIVAL) {
            if (next.layer() == EntityCore.Layer.PROTECTION) {
                body.clearForAquaticHandoff("air recovered; resume the retained shore escape");
            } else body.clear("hazard cleared; returning control to the next eligible layer");
            baritoneFallDelegated = false;
            logger.info("Survival hazard cleared; returning control to protection or the persisted mission");
        }

        switch (next.layer()) {
            case MISSION -> executeMission(next, now);
            case PROTECTION -> executeProtection(next, now);
            case WAITING -> {
                baritone.cancel(next.controlEpoch(), next.reason());
                autonomy.releaseControls("mission waiting");
                body.clear("mission waiting");
                platform.publishStatus(next.action().toLowerCase(Locale.ROOT) + ": " + next.reason());
            }
            case IDLE -> executeIdleHomeEconomy(next, now);
            case SURVIVAL -> throw new IllegalStateException("handled above");
        }
        previousLayer = next.layer();
    }

    /** Keep only a live exact Attack, including genuine higher-priority temporary pauses. */
    private void synchronizeAttackMissionObjective(EntityCore.TickDecision next, long now) {
        Mission mission = next.mission();
        boolean authorized = bridge.connected() && deathMissionId.isBlank() && !operatorStop.stopped()
                && client.player != null && client.player.isAlive() && client.world != null
                && mission != null && mission.kind().equalsIgnoreCase("attack")
                && (next.layer() == EntityCore.Layer.MISSION
                || next.layer() == EntityCore.Layer.SURVIVAL
                || next.layer() == EntityCore.Layer.PROTECTION)
                && (mission.state() == MissionState.RUNNING
                || mission.state() == MissionState.PAUSED_BY_SAFETY
                || mission.state() == MissionState.PAUSED_BY_PROTECTION);
        baritone.attackMissionObjective().authorize(authorized
                ? new BaritonePort.Goal(mission.id(), mission.kind(), mission.parameters())
                : null, client.world, now);
    }

    private void executeIdleHomeEconomy(EntityCore.TickDecision next, long now)
            throws IOException {
        if (previousLayer != EntityCore.Layer.IDLE) {
            baritone.cancel(next.controlEpoch(), "idle");
            if (operatorStop.stopped()) autonomy.releaseControlsForOwnerStop("idle after owner stop", clientTick);
            else autonomy.releaseControls("idle");
            body.clear("idle");
        }
        if (operatorStop.stopped()) {
            releaseHomeEconomyOwnership("owner stop is latched");
            autonomy.releaseControlsForOwnerStop("owner stop is latched", clientTick);
            autonomy.prepareStoppedCapacityTransfers(now);
            autonomy.drainStoppedCapacityTransfer(now);
            platform.publishStatus("operator-stopped");
            return;
        }
        boolean armorWork = autonomy.emptyArmorNeedsIdleLease(now);
        if (!bridge.connected() || !autonomy.homeEconomyNeedsIdleLease(now) && !armorWork) {
            releaseHomeEconomyOwnership("home economy is not runnable while idle");
            platform.publishStatus("idle");
            return;
        }
        Optional<ControlLease> lease = acquireHomeEconomyLease(now);
        if (lease.isEmpty()) {
            platform.publishStatus("idle: waiting to acquire the priority-zero Home lease");
            return;
        }
        if (armorWork) {
            autonomy.tickIdleArmorEquipment(lease.orElseThrow(), clientTick, now);
            platform.publishStatus("idle: equipping carried armor into empty slots");
            if (!autonomy.emptyArmorNeedsIdleLease(now)) releaseHomeEconomyOwnership("empty armor slots settled");
            return;
        }
        AutonomyExecutor.HomeIdleOutcome outcome = autonomy.tickHomeEconomy(
                lease.orElseThrow(), clientTick, now);
        invariantAutonomyMissionId = outcome.progressMissionId();
        invariantAutonomyWorkSample = outcome.completedWorkUnits();
        invariantAutonomyWorkSampleTick = clientTick;
        platform.publishStatus("home: " + outcome.detail());
        if (!outcome.retainLease()) {
            releaseHomeEconomyOwnership("Home economy yielded its idle lease");
        }
    }

    private Optional<ControlLease> acquireHomeEconomyLease(long now) {
        if (homeEconomyLease != null) {
            Optional<ControlLease> renewed = core.arbiter().renew(
                    homeEconomyLease, now, HOME_IDLE_LEASE_TTL_MILLIS);
            if (renewed.isPresent()) {
                homeEconomyLease = renewed.orElseThrow();
                return renewed;
            }
            homeEconomyLease = null;
        }
        Optional<ControlLease> acquired = core.arbiter().acquire(
                "home-economy",
                ControlPriority.IDLE,
                EnumSet.allOf(BodyChannel.class),
                now,
                HOME_IDLE_LEASE_TTL_MILLIS);
        acquired.ifPresent(lease -> homeEconomyLease = lease);
        return acquired;
    }

    private AutonomyExecutor.HomePreemptionOutcome preemptHomeEconomy(
            HomeEconomyPolicy.Authority authority,
            long now,
            String reason) throws IOException {
        AutonomyExecutor.HomePreemptionOutcome outcome =
                autonomy.pauseHomeEconomy(authority, now);
        if (outcome.terminal()) releaseHomeEconomyOwnership(reason);
        return outcome;
    }

    private void releaseHomeEconomyOwnership(String reason) {
        if (homeEconomyLease == null) return;
        core.arbiter().release(homeEconomyLease);
        homeEconomyLease = null;
        baritone.cancel(core.arbiter().epoch(), reason);
        autonomy.releaseControls(reason, clientTick);
    }

    private static HomeEconomyPolicy.Authority homeAuthority(EntityCore.Layer layer) {
        return switch (layer) {
            case SURVIVAL -> HomeEconomyPolicy.Authority.SURVIVAL;
            case PROTECTION -> HomeEconomyPolicy.Authority.PROTECTION;
            case MISSION, WAITING -> HomeEconomyPolicy.Authority.DIRECT_USER_MISSION;
            case IDLE -> throw new IllegalArgumentException("IDLE does not preempt Home economy");
        };
    }

    /**
     * Keeps an emergency run useful when the interrupted mission exposes a
     * concrete loaded destination. The objective can bend an already-safe
     * aggregate escape corridor, but it can never reverse that corridor back
     * through the hostile pressure.
     */
    private TacticalCombatPolicy.EscapeVector missionDirectedRetreatEscape(
            TacticalCombatPolicy.EscapeVector escape) {
        var player = client.player;
        if (player == null || client.world == null
                || escape.equals(TacticalCombatPolicy.EscapeVector.NONE)) {
            return escape;
        }
        Mission mission = core.activeMission().orElse(null);
        // Parked history is display state, not the destination of an explicit
        // Home request that actually owns execution (the alpha.13 boundary).
        var interruptedDestination = autonomy.interruptedWorkDestination(
                core.canYieldToExternalWork() ? null : mission);
        if (interruptedDestination.isPresent()) {
            var destination = interruptedDestination.orElseThrow();
            return TacticalProtectionPlan.biasEscapeTowardObjective(escape,
                    destination.getX() + 0.5 - player.getX(),
                    destination.getZ() + 0.5 - player.getZ());
        }
        if (mission == null) return escape;

        Map<String, String> parameters = mission.parameters();
        double objectiveX;
        double objectiveZ;
        switch (mission.kind().toLowerCase(Locale.ROOT)) {
            case "follow", "come", "guard", "protect" -> {
                String targetName = parameters.getOrDefault(
                        "player", parameters.getOrDefault("owner", "")).trim();
                if (targetName.isEmpty()) return escape;
                var target = client.world.getPlayers().stream()
                        .filter(candidate -> candidate != player)
                        .filter(candidate -> candidate.getName().getString()
                                .equalsIgnoreCase(targetName))
                        .findFirst()
                        .orElse(null);
                if (target == null) return escape;
                objectiveX = target.getX() - player.getX();
                objectiveZ = target.getZ() - player.getZ();
            }
            case "goto", "go" -> {
                String requestedDimension = parameters.getOrDefault("dimension", "").trim();
                String currentDimension = client.world.getRegistryKey()
                        .getValue().toString();
                if (!requestedDimension.isEmpty()
                        && !requestedDimension.equals(currentDimension)) {
                    return escape;
                }
                try {
                    objectiveX = Double.parseDouble(parameters.getOrDefault("x", ""))
                            - player.getX();
                    objectiveZ = Double.parseDouble(parameters.getOrDefault("z", ""))
                            - player.getZ();
                } catch (NumberFormatException invalidGoal) {
                    return escape;
                }
            }
            default -> {
                return escape;
            }
        }
        if (Math.hypot(objectiveX, objectiveZ) <= 3.5) return escape;
        return TacticalProtectionPlan.biasEscapeTowardObjective(
                escape, objectiveX, objectiveZ);
    }

    /**
     * Property safety is a non-negotiable protection override. It cannot enter
     * the ordinary no-route interception fallback, because that fallback is
     * deliberately allowed to close distance and strike its exact target.
     */
    private void executePropertyCreeperSafety(
            EntityCore.TickDecision next,
            ControlLease lease,
            FabricPlatformPort.ProtectionTarget target,
            PropertyCreeperSafetyPolicy.Decision decision) {
        String propertyIdentity = decision.property()
                .map(PropertyCreeperSafetyPolicy.Region::identity)
                .orElse("unknown-property-policy");
        String safetyKey = protectionModeLatch.episodeKey() + ':'
                + target.entity().getUuidAsString() + ':'
                + decision.code() + ':' + propertyIdentity;
        if (!safetyKey.equals(propertyCreeperSafetyKey)) {
            baritone.cancel(next.controlEpoch(), "property-aware creeper safety override");
            clearHomeThreatShelterState();
            body.clear("property-aware creeper safety override");
            clearDirectRetreatFallback();
            protectionSafetyRetreat.clear();
            directProtectionRetreat = false;
            propertyCreeperSafetyKey = safetyKey;
        }

        if (decision.holdPosition()) {
            body.clear("holding inside observed Home room against buffered creeper");
            directProtectionRetreat = false;
            resetProtectionProgressBaseline();
            platform.publishStatus(decision.detail()
                    + "; retained mission waits behind the observed room boundary");
            return;
        }

        PropertyCreeperSafetyPolicy.EscapeVector escape = decision.escape();
        DirectBodyController.RetreatIntent retreatIntent =
                new DirectBodyController.RetreatIntent(
                        safetyKey,
                        escape.x(),
                        escape.z(),
                        true,
                        false,
                        true);
        DirectBodyController.SurvivalExecution execution = body.executeRetreat(
                lease,
                target.entity(),
                retreatIntent);
        directProtectionRetreat = true;
        resetProtectionProgressBaseline();
        platform.publishStatus(decision.detail() + "; terrain-checked response="
                + execution.name().toLowerCase(Locale.ROOT));
    }

    private java.util.Collection<PropertyCreeperSafetyPolicy.Region> creeperWorkContexts() {
        if (!worldScopeGate.verified()) return List.of();
        List<PropertyCreeperSafetyPolicy.Region> contexts = new ArrayList<>(autonomy.ownedAssetCreeperContexts());
        Mission mission = core.activeMission().orElse(null);
        var project = blueprints.project();
        if (mission != null && "build".equals(mission.kind()) && !mission.state().terminal()
                && blueprints.confirmed() && project != null
                && project.projectId().equals(mission.parameters().get("projectId"))
                && worldScopeGate.verifiedScope().filter(scope -> scope.key().equals(project.worldId())).isPresent()) {
            BlockPos min = project.origin();
            BlockPos max = project.maximum();
            contexts.add(PropertyCreeperSafetyPolicy.Region.workContext(
                    "active build " + project.projectId(), project.dimension(),
                    min.getX(), max.getX(), min.getZ(), max.getZ()));
        }
        return contexts;
    }

    /**
     * Runs one protection-owned route into the genuinely observed Home room.
     * The ordinary Baritone route is intentional: it exercises the same
     * persisted open/cross/restore door transaction as every other mission.
     */
    private boolean executeHomeThreatShelter(
            EntityCore.TickDecision next,
            ControlLease lease,
            HomeThreatShelterPolicy.Decision decision) {
        String livePlanKey = decision.admitted()
                ? shelterPlanKey(decision)
                : "";
        String doorPhase = baritone.snapshot().doorPassagePhase();

        if (homeThreatShelterGoal != null) {
            boolean sameEntryPlan = decision.code()
                    == HomeThreatShelterPolicy.Code.ENTER_HOME_ROOM
                    && homeThreatShelterKey.equals(livePlanKey);
            boolean crossedIntoRoom = decision.hold();
            boolean passageInFlight = !doorPhase.equals("idle");
            boolean expectedPassageObservation = passageInFlight && switch (decision.code()) {
                case HOME_BOUNDARY_NOT_SEALABLE,
                        PLAYER_OUTSIDE_OBSERVED_TOPOLOGY,
                        PLAYER_NOT_IN_HOME_EXTERIOR -> true;
                default -> false;
            };
            if (!sameEntryPlan && !crossedIntoRoom && !expectedPassageObservation) {
                baritone.cancel(next.controlEpoch(),
                        "observed Home shelter route is no longer safe");
                clearHomeThreatShelterState();
                platform.publishStatus(
                        "Home shelter route changed before the door opened; using ordinary defensive policy");
                return false;
            }

            baritone.start(homeThreatShelterGoal, lease);
            BaritonePort.Status status = baritone.poll(
                    homeThreatShelterGoal.missionId());
            if (status.state() == BaritonePort.State.BLOCKED
                    || status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
                String detail = "Home shelter route failed safely: " + status.detail();
                baritone.cancel(next.controlEpoch(), detail);
                clearHomeThreatShelterState();
                platform.publishStatus(detail + "; using ordinary defensive policy");
                return false;
            }

            FabricBaritonePort.Snapshot navigation = baritone.snapshot();
            boolean doorRestored = navigation.doorPassagePhase().equals("idle")
                    && !navigation.doorPassageRestorationDue()
                    && decision.doorInitiallyClosed();
            if (crossedIntoRoom && doorRestored) {
                baritone.cancel(next.controlEpoch(),
                        "Home shelter reached and door restoration verified");
                homeThreatShelterGoal = null;
                homeThreatShelterKey = shelterHoldKey(decision);
                homeThreatShelterDetail = decision.detail();
                body.clear("holding inside the observed Home room");
                directProtectionRetreat = false;
                resetProtectionProgressBaseline();
                platform.publishStatus(decision.detail()
                        + "; wooden door is restored closed; retained mission remains paused");
                return true;
            }

            directProtectionRetreat = false;
            resetProtectionProgressBaseline();
            platform.publishStatus(homeThreatShelterDetail + "; "
                    + (navigation.doorPassagePhase().equals("idle")
                    ? status.detail()
                    : "door passage=" + navigation.doorPassagePhase()
                    + (navigation.doorPassageRestorationDue()
                    ? " with restoration due" : "")));
            return true;
        }

        if (!decision.admitted()) {
            if (!homeThreatShelterKey.isBlank()) clearHomeThreatShelterState();
            return false;
        }

        if (decision.hold()) {
            // An already-open door has no Entity-owned restoration history. Do
            // not claim a sealed shelter unless the observed boundary is closed.
            if (!decision.doorInitiallyClosed()) return false;
            String holdKey = shelterHoldKey(decision);
            if (!holdKey.equals(homeThreatShelterKey)) {
                baritone.cancel(next.controlEpoch(),
                        "holding behind the observed closed Home door");
                body.clear("holding inside the observed Home room");
                homeThreatShelterKey = holdKey;
                homeThreatShelterDetail = decision.detail();
            }
            directProtectionRetreat = false;
            resetProtectionProgressBaseline();
            platform.publishStatus(decision.detail()
                    + "; retained mission waits behind the closed wooden boundary");
            return true;
        }

        var targetCell = decision.target().orElseThrow();
        String operationId = "protection-shelter:" + UUID.nameUUIDFromBytes(
                livePlanKey.getBytes(StandardCharsets.UTF_8));
        String dimension = client.world.getRegistryKey().getValue().toString();
        homeThreatShelterGoal = new BaritonePort.Goal(
                operationId,
                "goto",
                Map.of(
                        "x", Integer.toString(targetCell.x()),
                        "y", Integer.toString(targetCell.y()),
                        "z", Integer.toString(targetCell.z()),
                        "range", "0",
                        "dimension", dimension));
        homeThreatShelterKey = livePlanKey;
        homeThreatShelterDetail = decision.detail();
        baritone.start(homeThreatShelterGoal, lease);
        BaritonePort.Status status = baritone.poll(operationId);
        if (status.state() == BaritonePort.State.BLOCKED
                || status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
            String detail = "Home shelter route failed safely: " + status.detail();
            baritone.cancel(next.controlEpoch(), detail);
            clearHomeThreatShelterState();
            platform.publishStatus(detail + "; using ordinary defensive policy");
            return false;
        }
        directProtectionRetreat = false;
        resetProtectionProgressBaseline();
        platform.publishStatus(decision.detail() + "; " + status.detail());
        return true;
    }

    private String shelterPlanKey(HomeThreatShelterPolicy.Decision decision) {
        var door = decision.door().orElseThrow().portalCell();
        var target = decision.target().orElseThrow();
        return protectionModeLatch.episodeKey()
                + ":door=" + door.x() + ',' + door.y() + ',' + door.z()
                + ":room=" + target.x() + ',' + target.y() + ',' + target.z();
    }

    private String shelterHoldKey(HomeThreatShelterPolicy.Decision decision) {
        var door = decision.door().orElseThrow().portalCell();
        return protectionModeLatch.episodeKey()
                + ":hold=" + door.x() + ',' + door.y() + ',' + door.z();
    }

    private void cancelHomeThreatShelterIfActive(long controlEpoch, String reason) {
        if (homeThreatShelterGoal != null) {
            baritone.cancel(controlEpoch, reason);
        }
        clearHomeThreatShelterState();
    }

    private void clearHomeThreatShelterState() {
        homeThreatShelterKey = "";
        homeThreatShelterGoal = null;
        homeThreatShelterDetail = "";
    }

    private void recordDeathDropSafetyRetreat(
            Mission mission,
            TacticalProtectionPlan.EngagementIntent intent,
            DirectBodyController.SurvivalExecution execution,
            String hostileId,
            long now) throws IOException {
        if (intent == TacticalProtectionPlan.EngagementIntent.SAFETY_RETREAT
                && (execution == DirectBodyController.SurvivalExecution.RETREATING
                || execution == DirectBodyController.SurvivalExecution.RETREAT_NO_ROUTE)) {
            autonomy.recordUnsafeDeathDropRetreat(mission, hostileId, now);
        }
    }

    private void executeProtection(EntityCore.TickDecision next, long now) throws IOException {
        // This is a per-tick execution fact, not a property of the retained Core
        // lease. Only the explicit tactical OBSERVE branch below may arm it.
        protectionObservationOnly = false;
        if (next.lease().isEmpty()) return;
        // Neutralize the paused mission once at protection entry. Repeating
        // this every protection tick invalidates the inner execution kernel
        // over and over without changing ownership or improving safety.
        if (protectionModeLatch.begin(protectionActivity)) {
            clearDirectRetreatFallback();
            platform.clearTacticalProtectionTargetSession();
            autonomy.releaseControls("protection preemption");
            protectionLoadoutGeneration = autonomy.beginProtectionLoadoutEpisode();
        } else if (protectionLoadoutGeneration < 0L) {
            // A survival emergency invalidates the prior inventory capability
            // while retaining the protection target. Re-enter with a fresh,
            // fenced loadout generation when protection regains the body.
            protectionLoadoutGeneration = autonomy.beginProtectionLoadoutEpisode();
        }
        // Core may retain a real attacker outside the fresh-acquisition radius.
        // A watchdog clears this adapter's latch, not core's live threat. Carry
        // the actual decision across that boundary instead of re-acquiring it.
        Optional<FabricPlatformPort.ProtectionTarget> decisionTarget =
                platform.selectedProtectionDecision(core.protectionSettings(), next.protectionThreat());
        decisionTarget.ifPresent(target -> protectionModeLatch.observeThreat(
                dev.entity.client.protection.ProtectionTargetSelector.threatKey(target.threat())));
        Optional<String> reactivationTargetId = protectionReactivation.peek();
        Optional<FabricPlatformPort.ProtectionTarget> reactivatedTarget =
                next.protectionThreat() != null ? Optional.empty() : reactivationTargetId
                        .flatMap(targetId -> platform.selectedProtectionTargetExact(
                                core.protectionSettings(), protectionActivity, targetId,
                                protectionModeLatch.threatKey()));
        // Seed the adapter identity before the inner action-owner handshake. Its mandatory
        // neutralization tick must not discard the sole one-tick reactivation authorization.
        reactivatedTarget.ifPresent(target -> protectionModeLatch.observeThreat(
                dev.entity.client.protection.ProtectionTargetSelector.threatKey(target.threat())));
        ControlLease lease = next.lease().orElseThrow();
        Optional<ActionLease> protectionAction = acquireRuntimeAction(
                lease, ActionOwner.PROTECTION, "runtime:protection", now);
        if (protectionAction.isEmpty()) {
            platform.publishStatus("arming the exclusive protection movement owner");
            return;
        }
        ActionLease protectionLease = protectionAction.orElseThrow();
        baritone.bindMovementAction(
                executionKernel, lease, protectionLease, clientTick, now);
        body.bindMovementAction(
                executionKernel, lease, protectionLease, clientTick, now);
        // The core projection made this exact raw UUID eligible for this PROTECTION decision.
        // Prefer it before ordinary TRAVEL filtering; tactical aggregation then uses the retained
        // key seeded above, so the inactive raw observation reaches a real defensive actuator.
        Optional<FabricPlatformPort.ProtectionTarget> selected = next.protectionThreat() != null
                ? decisionTarget
                : reactivatedTarget.isPresent()
                ? reactivatedTarget
                : platform.selectedProtectionTarget(
                        core.protectionSettings(),
                        protectionActivity,
                        protectionModeLatch.threatKey());
        if (selected.isEmpty()) {
            soleTargetSuppression.clear();
            clearDistantThreatDisengagement();
            protectionSafetyRetreat.clear();
            baritone.clearProtectionTraversalScope();
            baritone.cancel(next.controlEpoch(), "selected protection threat is no longer loaded");
            body.clear("protection threat cleared");
            endAquaticDefenseEpisode();
            directProtectionRetreat = false;
            clearDirectRetreatFallback();
            clearProtectionProgress();
            propertyCreeperSafetyKey = "";
            clearHomeThreatShelterState();
            platform.publishStatus("protection target cleared; returning to mission");
            return;
        }

        Optional<FabricPlatformPort.TacticalProtection> tactical =
                platform.tacticalProtection(
                        core.protectionSettings(),
                        protectionActivity,
                        protectionModeLatch.threatKey(),
                        now);
        Optional<TacticalProtectionPlan.Plan> observedTacticalPlan = tactical
                .map(FabricPlatformPort.TacticalProtection::decision)
                .map(decision -> TacticalProtectionPlan.merge(next.action(), decision));
        FabricPlatformPort.ProtectionTarget target = tactical
                .flatMap(FabricPlatformPort.TacticalProtection::target)
                .orElseGet(selected::orElseThrow);
        protectionModeLatch.observeThreat(
                dev.entity.client.protection.ProtectionTargetSelector.threatKey(target.threat()));
        if (behaviorCircuitBreaker.combatBodyFenced(target.threat().entityId())) {
            boolean observationOnly = observedTacticalPlan.isPresent()
                    && observedTacticalPlan.orElseThrow().execution() == TacticalProtectionPlan.Execution.OBSERVE;
            if (!observationOnly && executeNativeProtectionEscape(next, lease, target, now)) return;
            baritone.cancel(next.controlEpoch(), "failed protection actuator remains fenced");
            if (observationOnly) body.clear("retained fenced protection target requires observation only");
            else body.executeStationaryDefense(lease, target.entity());
            directProtectionRetreat = false;
            protectionObservationOnly = true;
            platform.publishStatus("protection route exhausted: stationary defense; original work retained");
            return;
        }
        if (target.entity() instanceof CreeperEntity creeper) {
            PropertyCreeperSafetyPolicy.Decision propertyDecision =
                    entityAttacks.creeperDecision(creeper);
            if (propertyDecision.denied()) {
                executePropertyCreeperSafety(
                        next, lease, target, propertyDecision);
                return;
            }
        }
        propertyCreeperSafetyKey = "";
        Optional<HomeThreatShelterPolicy.Decision> shelterDecision =
                homeThreatShelter.decide(target.entity());
        if (shelterDecision.isPresent()) {
            if (executeHomeThreatShelter(
                    next, lease, shelterDecision.orElseThrow())) {
                return;
            }
        } else if (homeThreatShelterGoal != null
                || !homeThreatShelterKey.isBlank()) {
            cancelHomeThreatShelterIfActive(
                    next.controlEpoch(),
                    "loaded Home shelter facts are no longer available");
        }
        TacticalProtectionPlan.Plan tacticalPlan = protectionSafetyRetreat.resolve(
                protectionModeLatch.episodeKey(),
                target.entity().getUuidAsString(),
                observedTacticalPlan).orElse(null);
        if (tacticalPlan != null
                && tacticalPlan.execution() == TacticalProtectionPlan.Execution.OBSERVE
                && !body.hasAquaticEscapeObjective()) {
            // The coarse core lease deliberately retains an authenticated attacker
            // through a quiet/release window. A complete tactical observation with
            // no relevant pressure must not translate that retention into either
            // more retreat or a fresh attack. Keep the protection lease neutral for
            // this tick and let the original mission resume when core releases it.
            baritone.cancel(next.controlEpoch(),
                    "retained protection target currently requires observation only");
            body.clear("retained protection target currently requires observation only");
            protectionRetreatIntent.clear();
            clearDirectRetreatFallback();
            clearProtectionProgress();
            directProtectionRetreat = false;
            protectionObservationOnly = true;
            platform.publishStatus("monitoring " + target.threat().entityType()
                    + " without moving or attacking; " + tacticalPlan.detail());
            return;
        }
        double exactTargetDistance = client.player == null
                ? 0.0
                : Math.sqrt(client.player.squaredDistanceTo(target.entity()));
        boolean immediateCreeperAvoidance = client.player != null
                && TacticalProtectionPlan.threatKind(target.threat().entityType())
                == TacticalCombatPolicy.ThreatKind.CREEPER
                && exactTargetDistance
                <= TacticalCombatPolicy.IMMEDIATE_CREEPER_AVOIDANCE_RADIUS;
        TacticalCombatPolicy.EscapeVector requestedEscape;
        if (immediateCreeperAvoidance) {
            requestedEscape = TacticalProtectionPlan.immediateHazardEscape(
                    tacticalPlan == null
                            ? TacticalCombatPolicy.EscapeVector.NONE
                            : tacticalPlan.escape(),
                    target.entity().getX() - client.player.getX(),
                    target.entity().getZ() - client.player.getZ());
        } else {
            requestedEscape = missionDirectedRetreatEscape(
                    tacticalPlan == null
                            ? TacticalCombatPolicy.EscapeVector.NONE
                            : tacticalPlan.escape());
        }
        boolean strategicSafetyEscape = tacticalPlan == null
                ? next.action().equals("RETREAT")
                : tacticalPlan.engagementIntent()
                == TacticalProtectionPlan.EngagementIntent.SAFETY_RETREAT;
        TacticalCombatPolicy.EscapeVector aggregateEscape = protectionRetreatIntent.resolve(
                protectionModeLatch.episodeKey(),
                target.entity().getUuidAsString(),
                now,
                requestedEscape,
                immediateCreeperAvoidance,
                strategicSafetyEscape,
                TacticalProtectionPlan.directEscapeFromThreat(
                        target.entity().getX() - client.player.getX(),
                        target.entity().getZ() - client.player.getZ()),
                exactTargetDistance <= switch (TacticalProtectionPlan.threatKind(
                        target.threat().entityType())) {
                    case SKELETON ->
                            DistantThreatDisengagementPolicy.RANGED_NEAR_TERM_PRESSURE_RADIUS;
                    case CREEPER -> TacticalCombatPolicy.IMMEDIATE_CREEPER_AVOIDANCE_RADIUS;
                    case MELEE, PLAYER, OTHER ->
                            DistantThreatDisengagementPolicy.MELEE_NEAR_TERM_PRESSURE_RADIUS;
                });
        DirectBodyController.RetreatIntent directRetreatIntent =
                new DirectBodyController.RetreatIntent(
                        protectionModeLatch.episodeKey(),
                        aggregateEscape.x(),
                        aggregateEscape.z(),
                        tacticalPlan != null && tacticalPlan.retreatAscentAllowed(),
                        tactical.map(FabricPlatformPort.TacticalProtection::eligibleThreatCount)
                                .orElse(0) == 1,
                        immediateCreeperAvoidance);
        aquaticDefenseIntent = directRetreatIntent;
        String exactTargetId = target.entity().getUuidAsString();
        if (distantThreatDisengagement.state()
                != DistantThreatDisengagementPolicy.State.IDLE
                && !distantThreatDisengagement.targetId().equals(exactTargetId)) {
            // Core/tactical challenger selection materially changed A->B. B must create its own
            // separation and quiet evidence; it cannot inherit A's avoidance capability.
            clearDistantThreatDisengagement();
        }
        boolean disengagementSpacing = exactTargetId.equals(currentDistantThreatTargetId)
                && currentDistantThreatDecision != null
                && currentDistantThreatDecision.requiresDefensiveSeparation(
                exactTargetDistance,
                distantThreatDisengagement.targetKind());
        // A projected A may legitimately lose core-compatible hysteresis to a stronger B. Consume
        // the durable handoff only after protection action ownership and final tactical selection
        // both resolve to A; otherwise B runs now and A remains projected for the next arbitration.
        protectionReactivation.consumeIf(exactTargetId);
        // The progress monitor is deliberately cleared during a temporary SURVIVAL layer, while
        // the protection episode and exact target remain live. Preserve the sealed capability for
        // that same real UUID; only a real target change clears its active/exhausted/rearm state.
        sealedResolution.retainTarget(exactTargetId);
        String progressKey = "protection:" + exactTargetId;
        if (!progressKey.equals(protectionProgressKey)) {
            boolean sameSealedTarget = sealedResolution.activeFor(exactTargetId)
                    || sealedResolution.exhaustedFor(exactTargetId);
            clearProtectionRouteProgress();
            if (!sameSealedTarget) protectionTargetResolution.reset();
            protectionProgressKey = progressKey;
            // CombatTargetSession suppresses ordinary pressure jitter, so a
            // different UUID now means death/removal, watchdog failover, or a
            // material safety preemption. The next exact ordinary target must
            // earn its own retreat-first attempt before sealed resolution.
        }
        String description = target.threat().entityType() + " attacking "
                + target.threat().target().name().toLowerCase(Locale.ROOT);
        if (!directRetreatFallbackTargetId.isEmpty()
                && !directRetreatFallbackTargetId.equals(exactTargetId)) {
            // A real A->B target change must earn B its own retreat-first attempt. The short
            // tactical-spacing intercept timer belongs to the exact target that exhausted route
            // geometry; it cannot be inherited merely because the aggregate intent is unchanged.
            clearDirectRetreatFallback();
        }
        // The core protection action is deliberately coarse: defensive travel
        // used to arrive here as RETREAT for every attacker. Once a complete
        // tactical observation exists, its engagement intent is authoritative.
        // Otherwise a healthy mission-blocking zombie could never reach the
        // combat actuator, regardless of what the tactical policy decided.
        boolean directRetreatWanted = disengagementSpacing || body.hasAquaticEscapeObjective()
                || (tacticalPlan == null
                ? next.action().equals("RETREAT")
                : tacticalPlan.engagementIntent()
                != TacticalProtectionPlan.EngagementIntent.NEUTRALIZE_BLOCKER);
        boolean stalledSeparationResolutionEligible =
                TacticalProtectionPlan.stalledSeparationMayNeutralize(
                        disengagementSpacing,
                        target.threat().entityType(),
                        tacticalPlan);
        boolean fallbackWindowActive = exactTargetId.equals(
                directRetreatFallbackTargetId)
                && now < directRetreatFallbackUntil;
        boolean stalledSeparationResolutionActive = fallbackWindowActive
                && directRetreatFallbackResolvesStalledSeparation
                && TacticalProtectionPlan.stalledSeparationResolutionMayContinue(
                        target.threat().entityType(), tacticalPlan);
        if (fallbackWindowActive
                && directRetreatFallbackResolvesStalledSeparation
                && !stalledSeparationResolutionActive) {
            // The admitted exception is sticky across melee knockback distance,
            // but never across a new safety/ranged/creeper decision.
            clearDirectRetreatFallback();
            fallbackWindowActive = false;
        }
        boolean approachForbidden = disengagementSpacing
                || (tacticalPlan == null
                ? next.action().equals("RETREAT")
                : tacticalPlan.engagementIntent()
                == TacticalProtectionPlan.EngagementIntent.SAFETY_RETREAT);
        if (stalledSeparationResolutionActive) approachForbidden = false;
        // Refresh the committed native owner before DirectBody's retreat-entry cancel below.
        if (approachForbidden && baritone.hasProtectionEscape(target.entity(), protectionWork(now).identity())
                && executeNativeProtectionEscape(next, lease, target, now)) return;
        boolean boundedRetreatWanted = !disengagementSpacing && tacticalPlan != null
                && tacticalPlan.engagementIntent()
                == TacticalProtectionPlan.EngagementIntent.BOUNDED_RETREAT;
        String suppressionEpisodeId = protectionWork(now).identity();
        if (!boundedRetreatWanted && !suppressionEpisodeId.isBlank()
                && soleTargetSuppression.executionGate(
                suppressionEpisodeId, exactTargetId, now)
                == SoleTargetSuppressionSession.ExecutionGate.DEFENSIVE_HOLD) {
            // The failed STANDARD operation is already canceled. Keep protection ownership
            // neutral for one fixed cooldown so target failover can win after 1.5s; do not start
            // or refresh the same Baritone UUID until the one retry is explicitly authorized.
            baritone.cancel(next.controlEpoch(),
                    "quiet sole-target retry is inside its fixed defensive cooldown");
            CombatLoadoutCoordinator.Result defensiveReady = tacticalPlan == null
                    ? CombatLoadoutCoordinator.Result.READY
                    : autonomy.prepareProtectionLoadout(
                    protectionLoadoutGeneration, tacticalPlan.loadout(), true, now);
            // Inventory WAITING is not permission to stand unshielded in front of a live
            // skeleton. DirectBody uses the actually carried/offhand state while loadout custody
            // continues asynchronously; it remains retreat/shield/counterstrike fail-closed and
            // never starts Baritone beneath the transaction.
            DirectBodyController.SurvivalExecution defensiveExecution = body.executeRetreat(
                    lease,
                    target.entity(),
                    directRetreatIntent);
            directProtectionRetreat = true;
            resetProtectionProgressBaseline();
            String defensiveLoadout = defensiveReady == CombatLoadoutCoordinator.Result.WAITING
                    ? " while the defensive loadout is being prepared"
                    : "";
            platform.publishStatus("bounded defensive hold" + defensiveLoadout + " against "
                    + target.threat().entityType()
                    + " without starting a new route ("
                    + defensiveExecution.name().toLowerCase(Locale.ROOT)
                    + "); one bounded retry waits for the fixed cooldown or material danger change");
            return;
        }
        TacticalProtectionPlan.EngagementIntent currentRetreatIntent = disengagementSpacing
                ? TacticalProtectionPlan.EngagementIntent.SAFETY_RETREAT
                : tacticalPlan == null
                ? TacticalProtectionPlan.EngagementIntent.SAFETY_RETREAT
                : tacticalPlan.engagementIntent();
        if (!stalledSeparationResolutionActive
                && directRetreatFallbackIntent != null
                && directRetreatFallbackIntent != currentRetreatIntent) {
            clearDirectRetreatFallback();
        }
        if (!stalledSeparationResolutionActive && approachForbidden) {
            clearDirectRetreatFallback();
        }
        boolean sealedResolutionActive = !body.hasAquaticEscapeObjective() && boundedRetreatWanted
                && sealedResolution.activeFor(exactTargetId);
        boolean sealedResolutionExhausted = boundedRetreatWanted
                && sealedResolution.exhaustedFor(exactTargetId);
        if (directRetreatWanted
                && !sealedResolutionActive
                && (body.hasAquaticEscapeObjective() || boundedRetreatWanted
                || approachForbidden
                || now >= directRetreatFallbackUntil)) {
            if (!directProtectionRetreat) {
                baritone.rememberThreatApproach(target.entity(), now);
                baritone.cancel(next.controlEpoch(), "tactical protection retreat");
                body.clearForAquaticHandoff("starting tactical protection retreat");
                directProtectionRetreat = true;
            }
            if (tacticalPlan != null && !disengagementSpacing
                    && !client.player.isTouchingWater() && !body.hasAquaticEscapeObjective()) {
                CombatLoadoutCoordinator.Result loadout = autonomy.prepareProtectionLoadout(
                        protectionLoadoutGeneration, tacticalPlan.loadout(), true, now);
                if (loadout == CombatLoadoutCoordinator.Result.WAITING) {
                    body.clear("waiting for acknowledged defensive loadout");
                    platform.publishStatus("preparing defensive combat loadout; "
                            + tacticalPlan.detail());
                    return;
                }
            }
            DirectBodyController.SurvivalExecution execution =
                    body.executeRetreat(
                            lease,
                            target.entity(),
                            directRetreatIntent);
            recordDeathDropSafetyRetreat(
                    core.canYieldToExternalWork() ? null : next.mission(),
                    currentRetreatIntent, execution, exactTargetId, now);
            if (execution == DirectBodyController.SurvivalExecution.RETREATING) {
                platform.publishStatus("tactical retreat from " + target.threat().entityType()
                        + "; " + (disengagementSpacing
                        ? currentDistantThreatDecision.detail()
                        : tacticalPlan == null
                        ? "protection mission remains retained"
                        : tacticalPlan.detail()));
                return;
            }
            if (execution != DirectBodyController.SurvivalExecution.RETREAT_NO_ROUTE) {
                platform.publishStatus("retreat controller lost protection custody; rearming the episode owner");
                return;
            }
            if (stalledSeparationResolutionEligible) approachForbidden = false;
            if (approachForbidden) {
                if (executeNativeProtectionEscape(next, lease, target, now)) return;
                // Native separation is exhausted for this live encounter. Keep the original
                // mission and defensive hold, never turn failed escape into hostile pursuit.
                platform.publishStatus("holding defensive position against "
                        + target.threat().entityType() + "; "
                        + (disengagementSpacing
                        ? currentDistantThreatDecision.detail()
                        : tacticalPlan == null
                        ? "approach remains forbidden"
                        : tacticalPlan.detail()));
                return;
            }
            if (boundedRetreatWanted && sealedResolutionExhausted
                    && !sealedResolution.rearmReadyFor(exactTargetId, now)) {
                // The exact target already consumed its one no-progress-bounded
                // resolution attempt. Keep retrying real escape geometry and
                // DirectBody's in-reach defensive counterstrike for a fixed
                // two-second grace. An available target alternative wins after
                // 1.5s; a sole unresolved target may then receive one fresh,
                // independently watchdog-bounded attempt.
                platform.publishStatus("exact bounded resolution exhausted for "
                        + target.threat().entityType()
                        + "; retrying defensive separation until bounded retry after defensive grace");
                return;
            }
            // A sealed local geometry is not safety. For BOUNDED_RETREAT, grant
            // this exact ordinary target one shield-gated resolution operation;
            // CombatTargetResolutionWatchdog, not a wall clock here, ends it
            // after six seconds without closing, damage, or legal attack work.
            body.clear("no direct safe retreat route; falling back to protection interception");
            directProtectionRetreat = false;
            directRetreatFallbackIntent = currentRetreatIntent;
            if (boundedRetreatWanted) {
                boolean retryAfterDefensiveGrace = sealedResolutionExhausted;
                if (!sealedResolution.begin(exactTargetId, now)) {
                    throw new IllegalStateException(
                            "sealed resolution began before its fixed rearm deadline");
                }
                protectionTargetResolution.reset();
                resetProtectionProgressBaseline();
                sealedResolutionActive = true;
                platform.publishStatus((retryAfterDefensiveGrace
                        ? "bounded retry after defensive grace against "
                        : "no direct retreat route from ")
                        + target.threat().entityType()
                        + "; beginning one exact-target resolution attempt with a 6s"
                        + " no-progress bound; low-health closing remains disabled unless"
                        + " a usable offhand shield is acknowledged");
            } else {
                directRetreatFallbackResolvesStalledSeparation =
                        stalledSeparationResolutionEligible;
                long resolutionMillis = directRetreatFallbackResolvesStalledSeparation
                        ? STALLED_SEPARATION_RESOLUTION_MILLIS
                        : TACTICAL_SPACING_INTERCEPT_MILLIS;
                directRetreatFallbackUntil = now + resolutionMillis;
                directRetreatFallbackTargetId = exactTargetId;
                platform.publishStatus((stalledSeparationResolutionEligible
                        ? "mission-directed escape stalled against "
                        : "no direct retreat route from ")
                        + target.threat().entityType() + "; committing to one "
                        + (resolutionMillis / 1_000L)
                        + "s exact-target resolution before retrying escape");
            }
        }
        if (directProtectionRetreat) {
            body.clear("tactical policy selected protection interception");
            directProtectionRetreat = false;
        }
        if (!directRetreatWanted && !stalledSeparationResolutionActive) {
            clearDirectRetreatFallback();
        }
        boolean boundedRetreatIntercept = boundedRetreatWanted
                && sealedResolution.activeFor(exactTargetId);
        long logicalStandardAttemptGeneration = !boundedRetreatIntercept
                && !suppressionEpisodeId.isBlank()
                ? soleTargetSuppression.beginStandardExecutionAttempt(
                suppressionEpisodeId, exactTargetId)
                : -1L;
        try {
            Optional<BaritonePort.Status> pendingStandardFailure = boundedRetreatIntercept
                    ? Optional.empty()
                    : baritone.pollPendingStandardProtectionFailure(
                            target.entity(), lease.epoch());
            BaritonePort.Status status;
            if (pendingStandardFailure.isPresent()) {
                // A queued CALC_FAILED belongs to the already-started exact generation. Consume
                // its typed traversal attempt before loadout waits or startProtection can replace
                // the operation and erase the producer edge.
                status = pendingStandardFailure.orElseThrow();
            } else {
                TacticalCombatPolicy.LoadoutIntent approachLoadout = tacticalPlan != null
                        && (tacticalPlan.tacticalAction()
                        == TacticalCombatPolicy.Action.SHIELDED_INTERCEPT
                        || boundedRetreatIntercept)
                        ? TacticalCombatPolicy.LoadoutIntent.SHIELD
                        : TacticalCombatPolicy.LoadoutIntent.NONE;
                CombatLoadoutCoordinator.Result approachReady = autonomy.prepareProtectionLoadout(
                        protectionLoadoutGeneration, approachLoadout, true, now);
                if (approachReady == CombatLoadoutCoordinator.Result.WAITING) {
                    if (!baritone.holdProtectionLoadout(target.entity(), lease)) {
                        baritone.cancel(next.controlEpoch(), "waiting for acknowledged approach loadout");
                    }
                    body.clear("waiting for acknowledged approach loadout");
                    platform.publishStatus("preparing shield before approaching "
                            + target.threat().entityType());
                    return;
                }
                if (!boundedRetreatIntercept
                        && soleTargetSuppression.state()
                        == SoleTargetSuppressionSession.State.RETRY_ACTIVE) {
                    boolean sameLiveRetry = baritone.hasStandardProtectionOperation(
                            target.entity(), lease.epoch());
                    if (!sameLiveRetry && soleTargetSuppression.retryRefreshAllowed(
                            suppressionEpisodeId, exactTargetId)) {
                        soleTargetSuppression.abandonClaimedRetry(
                                suppressionEpisodeId, exactTargetId);
                        baritone.cancel(next.controlEpoch(),
                                "claimed sole-target retry ended without resolution");
                        DirectBodyController.SurvivalExecution defensiveExecution =
                                body.executeRetreat(
                                        lease,
                                        target.entity(),
                                        directRetreatIntent);
                        directProtectionRetreat = true;
                        platform.publishStatus("sole-target retry ended without progress; defensive hold "
                                + defensiveExecution.name().toLowerCase(Locale.ROOT)
                                + " while suppression is revalidated");
                        return;
                    }
                    if (!sameLiveRetry && !soleTargetSuppression.claimRetryStart(
                            suppressionEpisodeId, exactTargetId)) {
                        baritone.cancel(next.controlEpoch(),
                                "sole-target retry authorization is already consumed");
                        DirectBodyController.SurvivalExecution defensiveExecution =
                                body.executeRetreat(
                                        lease,
                                        target.entity(),
                                        directRetreatIntent);
                        directProtectionRetreat = true;
                        platform.publishStatus("sole-target retry authorization is consumed; defensive hold "
                                + defensiveExecution.name().toLowerCase(Locale.ROOT));
                        return;
                    }
                }
                baritone.startProtection(
                        target.entity(),
                        description,
                        lease,
                        boundedRetreatIntercept
                                ? FabricBaritonePort.ProtectionCombatContext
                                .SEALED_LOW_HEALTH_RESOLUTION
                                : FabricBaritonePort.ProtectionCombatContext.STANDARD,
                        tactical.flatMap(FabricPlatformPort.TacticalProtection::visibleRangedPressure)
                                .map(FabricPlatformPort.ProtectionTarget::entity),
                        protectionModeLatch.episodeKey());
                if (baritone.prepareProtectionCombatHand(target.entity()) && tacticalPlan != null) {
                    CombatLoadoutCoordinator.Result combatReady = autonomy.prepareProtectionLoadout(
                            protectionLoadoutGeneration, tacticalPlan.loadout(), false, now);
                    if (combatReady == CombatLoadoutCoordinator.Result.WAITING) {
                        if (!baritone.holdProtectionLoadout(target.entity(), lease)) {
                            baritone.cancel(next.controlEpoch(), "waiting for acknowledged melee loadout");
                        }
                        platform.publishStatus("preparing acknowledged melee loadout against "
                                + target.threat().entityType());
                        return;
                    }
                }
                status = baritone.pollProtection(target.entity());
            }
            if (status.controlEpoch() != lease.epoch()) {
                logger.debug("Ignoring stale protection status from epoch {} (live epoch {})",
                        status.controlEpoch(), lease.epoch());
                return;
            }
            double targetDistance = client.player == null
                    ? Double.NaN
                    : Math.sqrt(client.player.squaredDistanceTo(target.entity()));
            double targetHealth = target.entity() instanceof net.minecraft.entity.LivingEntity living
                    ? Math.max(0.0, living.getHealth())
                    : Double.NaN;
            CombatTargetResolutionWatchdog.Result targetResolution =
                    protectionTargetResolution.observe(
                            new CombatTargetResolutionWatchdog.Observation(
                                    now,
                                    target.entity().getUuidAsString(),
                                    targetDistance,
                                    targetHealth,
                                    status.completedWorkUnits()));
            if (targetResolution.state()
                    == CombatTargetResolutionWatchdog.State.PROGRESS) {
                soleTargetSuppression.markResolutionProgress(exactTargetId);
                baritone.clearProtectionTraversalScope();
                platform.markTacticalProtectionTargetProgress(
                        target.entity().getUuidAsString());
            } else if (targetResolution.state()
                    == CombatTargetResolutionWatchdog.State.STUCK) {
                if (boundedRetreatIntercept) {
                    sealedResolution.exhaust(exactTargetId, now);
                } else if (!suppressionEpisodeId.isBlank()) {
                    soleTargetSuppression.exhaustStandard(
                            suppressionEpisodeId,
                            exactTargetId,
                            SoleTargetSuppressionSession.Exhaustion
                                    .STANDARD_NO_RESOLUTION,
                            now);
                }
                platform.markTacticalProtectionTargetUnreachable(
                        target.entity().getUuidAsString(), now);
                baritone.cancel(next.controlEpoch(),
                        "exact protection target made no combat progress");
                protectionTargetResolution.reset();
                resetProtectionProgressBaseline();
                platform.publishStatus("replanning protection: "
                        + targetResolution.detail() + " for "
                        + Math.max(1L, targetResolution.noResolutionMillis() / 1_000L)
                        + "s");
                return;
            }
            if (status.state() == BaritonePort.State.EXECUTING && status.progressExpected()) {
                ProgressMonitor.Result progress = protectionProgress.observe(
                        protectionProgressKey,
                        new ProgressMonitor.Observation(
                                System.currentTimeMillis(), playerPosition(),
                                status.distanceRemaining(), status.completedWorkUnits()));
                if (progress.status() == ProgressMonitor.Status.STUCK) {
                    if (boundedRetreatIntercept) {
                        sealedResolution.exhaust(exactTargetId, now);
                    }
                    platform.markTacticalProtectionTargetUnreachable(
                            target.entity().getUuidAsString(), now);
                    baritone.cancel(next.controlEpoch(), "protection target pursuit is stuck");
                    resetProtectionProgressBaseline();
                    platform.publishStatus("replanning protection: target pursuit made no progress");
                    return;
                }
            } else {
                resetProtectionProgressBaseline();
            }
            if (!boundedRetreatIntercept
                    && status.failureCause()
                    == BaritonePort.FailureCause.TARGET_ROUTE_EXHAUSTED) {
                // Only Fabric's exhausted three-attempt traversal budget emits this cause. Generic
                // BLOCKED/TRANSIENT_FAILURE states (shield, loadout, lease, etc.) cannot consume a
                // sole-target retry or arm suppression.
                if (!suppressionEpisodeId.isBlank()) {
                    soleTargetSuppression.exhaustStandard(
                            suppressionEpisodeId,
                            exactTargetId,
                            SoleTargetSuppressionSession.Exhaustion
                                    .STANDARD_ROUTE_EXHAUSTED,
                            now);
                } else {
                    // Sleep/true idle has no mission/Stock suppression authority. The producer's
                    // terminal route still must end this exact temporary actuator: next tick uses
                    // the existing stationary defense gate, not another unchanged pursuit.
                    behaviorCircuitBreaker.fenceCombatBody(List.of(exactTargetId));
                }
                platform.markTacticalProtectionTargetUnreachable(exactTargetId, now);
            }
            switch (status.state()) {
                case COMPLETE -> {
                    soleTargetSuppression.markResolutionProgress(exactTargetId);
                    baritone.clearProtectionTraversalScope();
                    baritone.cancel(next.controlEpoch(), "protection threat defeated");
                    clearProtectionProgress();
                    platform.publishStatus("protection threat defeated; checking surroundings");
                }
                case TRANSIENT_FAILURE, BLOCKED -> {
                    if (boundedRetreatIntercept
                            && status.state() == BaritonePort.State.BLOCKED) {
                        sealedResolution.exhaust(exactTargetId, now);
                        platform.markTacticalProtectionTargetUnreachable(
                                exactTargetId, now);
                    }
                    baritone.cancel(next.controlEpoch(), status.detail());
                    resetProtectionProgressBaseline();
                    platform.publishStatus("replanning protection: " + status.detail());
                }
                case IDLE, CALCULATING, EXECUTING -> platform.publishStatus(
                        "protecting: " + description + "; " + status.detail());
            }
        } catch (RuntimeException error) {
            logger.warn("Protection action failed; mission remains paused only while the threat is active", error);
            long failedGeneration = -1L;
            if (boundedRetreatWanted && sealedResolution.activeFor(exactTargetId)) {
                sealedResolution.exhaust(exactTargetId, now);
                platform.markTacticalProtectionTargetUnreachable(exactTargetId, now);
            } else if (!suppressionEpisodeId.isBlank()) {
                failedGeneration = baritone.standardProtectionOperationGeneration(
                        target.entity(), lease.epoch());
                soleTargetSuppression.observeStandardFabricGeneration(
                        logicalStandardAttemptGeneration, failedGeneration);
                boolean targetPresent = target.entity().isAlive() && !target.entity().isRemoved()
                        && latestRawWorld.threats().stream()
                        .anyMatch(threat -> threat.entityId().equals(exactTargetId));
                boolean survivalActive = previousLayer == EntityCore.Layer.SURVIVAL
                        || immediateSuppressionSurvivalRisk(latestRawWorld);
                boolean standardFailureDebited = StandardProtectionExecutionFailurePolicy.eligible(
                        new StandardProtectionExecutionFailurePolicy.Observation(
                                logicalStandardAttemptGeneration,
                                failedGeneration,
                                !boundedRetreatIntercept,
                                core.arbiter().isValid(lease, now),
                                targetPresent,
                                survivalActive))
                        && soleTargetSuppression.exhaustStandardExecutionFailure(
                        suppressionEpisodeId, exactTargetId,
                        logicalStandardAttemptGeneration, now);
                if (standardFailureDebited) {
                    platform.markTacticalProtectionTargetUnreachable(exactTargetId, now);
                    baritone.cancel(next.controlEpoch(), "bounded STANDARD execution attempt failed");
                    try {
                        DirectBodyController.SurvivalExecution defensiveExecution =
                                body.executeRetreat(
                                        lease,
                                        target.entity(),
                                        directRetreatIntent);
                        directProtectionRetreat = true;
                        resetProtectionProgressBaseline();
                        platform.publishStatus("bounded STANDARD execution attempt "
                                + logicalStandardAttemptGeneration + " failed before/within fabric "
                                + failedGeneration + "; defensive hold "
                                + defensiveExecution.name().toLowerCase(Locale.ROOT));
                        return;
                    } catch (RuntimeException defensiveFailure) {
                        error.addSuppressed(defensiveFailure);
                    }
                }
            }
            baritone.cancel(next.controlEpoch(), "protection action failed");
            body.clear("protection action failed");
            resetProtectionProgressBaseline();
            platform.publishStatus("replanning protection attempt "
                    + logicalStandardAttemptGeneration + " (fabric " + failedGeneration + "): "
                    + error.getMessage());
        }
    }

    private void resetProtectionProgressBaseline() {
        if (!protectionProgressKey.isBlank()) protectionProgress.clear(protectionProgressKey);
    }

    private boolean executeNativeProtectionEscape(
            EntityCore.TickDecision next, ControlLease lease,
            FabricPlatformPort.ProtectionTarget target, long now) {
        if (operatorStop.stopped() || next.layer() != EntityCore.Layer.PROTECTION
                || next.lease().isEmpty() || !next.lease().orElseThrow().equals(lease)
                || behaviorCircuitBreaker.nativeProtectionEscapeFenced(target.threat().entityId())) return false;
        String workIdentity = protectionWork(now).identity();
        if (!baritone.hasProtectionEscape(target.entity(), workIdentity)) {
            body.clearForAquaticHandoff("native protection separation owns movement");
        }
        directProtectionRetreat = false;
        BaritonePort.Status escape = baritone.executeProtectionEscape(
                target.entity(), workIdentity, lease);
        if (escape.state() == BaritonePort.State.BLOCKED) {
            behaviorCircuitBreaker.fenceNativeProtectionEscape(target.threat().entityId());
            body.executeStationaryDefense(lease, target.entity());
            protectionObservationOnly = true;
        } else if (escape.state() == BaritonePort.State.COMPLETE) {
            protectionObservationOnly = true;
        }
        platform.publishStatus("native protection separation "
                + escape.state().name().toLowerCase(Locale.ROOT)
                + "; original work retained; " + escape.detail());
        return true;
    }

    private void clearProtectionLoadoutEpisode() {
        if (protectionLoadoutGeneration < 0L) return;
        autonomy.clearProtectionLoadoutEpisode(protectionLoadoutGeneration);
        protectionLoadoutGeneration = -1L;
    }

    private void clearProtectionProgress() {
        clearProtectionRouteProgress();
        protectionTargetResolution.reset();
    }

    private void clearProtectionRouteProgress() {
        resetProtectionProgressBaseline();
        protectionProgressKey = "";
    }

    private void clearDirectRetreatFallback() {
        directRetreatFallbackUntil = 0L;
        directRetreatFallbackIntent = null;
        directRetreatFallbackTargetId = "";
        directRetreatFallbackResolvesStalledSeparation = false;
        sealedResolution.clear();
    }

    /**
     * Re-samples the same authoritative hostile set while SURFACE_FOR_AIR owns
     * movement. Survival retains exclusive actuation; this only refreshes the
     * episode-scoped aggregate direction that its water frame may use.
     */
    /**
     * Resolves a live self-attacker from the observation already taken before
     * core arbitration. This path intentionally does not require a protection
     * entry tick: EXTINGUISH_FIRE may win on the very first dangerous frame.
     * A defensive, self-only view also prevents AGGRESSIVE mode from turning a
     * nearby passive hostile into permission to attack during a fire escape.
     */
    private Optional<FabricPlatformPort.ProtectionTarget> activeFireDefenseTarget(long now) {
        ProtectionPolicy.Settings configured = core.protectionSettings();
        if (!configured.protectSelf()) {
            clearFireDefenseTargetSession();
            return Optional.empty();
        }
        ProtectionPolicy.Settings activeSelfOnly = new ProtectionPolicy.Settings(
                true,
                false,
                "",
                configured.maximumEngageDistance(),
                configured.retreatHealthFraction(),
                ProtectionPolicy.CombatMode.DEFENSIVE);
        fireDefenseTargetSessionActive = true;
        Optional<FabricPlatformPort.ProtectionTarget> selected =
                platform.tacticalProtection(
                        activeSelfOnly,
                        ProtectionPolicy.ActivityContext.STATIONARY_WORK,
                        "",
                        now)
                .flatMap(FabricPlatformPort.TacticalProtection::target)
                .filter(target -> target.threat().activelyAttacking());
        if (selected.isEmpty()) platform.clearTacticalProtectionTargetSession();
        return selected;
    }

    private void clearFireDefenseTargetSession() {
        if (!fireDefenseTargetSessionActive) return;
        fireDefenseTargetSessionActive = false;
        platform.clearTacticalProtectionTargetSession();
    }

    private void refreshAquaticDefenseIntent(long now) {
        if (!protectionModeLatch.active()) {
            endAquaticDefenseEpisode();
            return;
        }
        Optional<FabricPlatformPort.TacticalProtection> tactical =
                platform.tacticalProtection(
                        core.protectionSettings(),
                        protectionActivity,
                        protectionModeLatch.threatKey(),
                        now);
        if (tactical.isEmpty()) {
            endAquaticDefenseEpisode();
            return;
        }
        FabricPlatformPort.TacticalProtection live = tactical.orElseThrow();
        String primaryKey = live.target()
                .map(target -> target.entity().getUuidAsString())
                .orElseGet(() -> live.decision().targetId()
                        .orElse(protectionModeLatch.threatKey()));
        TacticalCombatPolicy.EscapeVector aggregate = protectionRetreatIntent.resolve(
                protectionModeLatch.episodeKey(),
                primaryKey,
                now,
                missionDirectedRetreatEscape(live.decision().escape()),
                false,
                TacticalProtectionPlan.merge("RETREAT", live.decision())
                        .engagementIntent()
                        == TacticalProtectionPlan.EngagementIntent.SAFETY_RETREAT);
        aquaticDefenseIntent = new DirectBodyController.RetreatIntent(
                protectionModeLatch.episodeKey(), aggregate.x(), aggregate.z());
    }

    private void endAquaticDefenseEpisode() {
        aquaticDefenseIntent = DirectBodyController.RetreatIntent.NONE;
        protectionRetreatIntent.clear();
        body.clearAquaticDefenseIntent();
    }

    /** Grants one exact inner body capability and enforces the kernel's neutral handoff tick. */
    private Optional<ActionLease> acquireRuntimeAction(
            ControlLease parent,
            ActionOwner owner,
            String operationId,
            long nowMillis) {
        if (parent == null) return Optional.empty();
        ExecutionKernel.Decision requested = executionKernel.request(
                parent, owner, operationId, clientTick, nowMillis);
        if (requested.state() == ExecutionKernel.State.NEUTRALIZE
                || requested.state() == ExecutionKernel.State.PARENT_INVALID) {
            String reason = requested.detail();
            baritone.neutralizeActuators(reason);
            if (requested.state() == ExecutionKernel.State.NEUTRALIZE
                    && (owner == ActionOwner.PROTECTION
                    || owner == ActionOwner.SURVIVAL
                    && "survival:surface_for_air".equals(operationId))) {
                body.clearForAquaticHandoff(reason);
            } else body.clear(reason);
            actuators.neutralizeAll(reason);
            movement.neutralizeAll(MovementFrameActuator.Cleanup.OWNER_TRANSITION);
            requested.transition().ifPresent(ticket ->
                    executionKernel.confirmNeutralized(ticket, clientTick));
            return Optional.empty();
        }
        if (requested.state() == ExecutionKernel.State.WAIT_NEXT_TICK) {
            return Optional.empty();
        }
        ActionLease action = requested.lease().orElseThrow(() ->
                new IllegalStateException("runtime action owner was not granted a capability"));
        executionKernel.requireValid(action, parent, clientTick, nowMillis);
        return Optional.of(action);
    }

    private void executeMission(EntityCore.TickDecision next, long now) throws IOException {
        Mission mission = next.mission();
        if (mission == null || next.lease().isEmpty()) return;
        ControlLease lease = next.lease().orElseThrow();
        if (client.player == null || client.world == null) {
            platform.publishStatus("waiting to join the Minecraft world; mission " + shortId(mission.id()) + " retained");
            return;
        }

        if (autonomy.handles(mission)) {
            if (mission.kind().equals("build") && blueprintRestartFence) {
                core.pause(mission.id(),"Construction retained after restart; /e build show then /e build resume",now);
                baritone.cancel(core.arbiter().epoch(),"Construction needs explicit restart resume");
                publishMissionState(mission.id(),"Construction retained after restart; /e build show then /e build resume");
                return;
            }
            AutonomyExecutor.Outcome outcome = autonomy.tick(
                    mission, lease, clientTick, now);
            invariantAutonomyMissionId = mission.id();
            invariantAutonomyWorkSample = Math.max(0L, outcome.completedWorkUnits());
            invariantAutonomyWorkSampleTick = clientTick;
            if (handleAutonomyOutcome(mission, lease, outcome, now)) return;
        }
        if (mission.kind().equalsIgnoreCase("attack")) {
            CombatLoadoutCoordinator.Result loadout =
                    autonomy.prepareAttackMissionLoadout(mission.id(), now);
            if (loadout == CombatLoadoutCoordinator.Result.WAITING) {
                baritone.cancel(next.controlEpoch(),
                        "waiting for acknowledged explicit-attack loadout");
                platform.publishStatus("attack [" + shortId(mission.id())
                        + "]: equipping carried weapon and shield");
                return;
            }
        } else if (!autonomy.clearAttackMissionLoadout(now)) {
            baritone.cancel(next.controlEpoch(),
                    "draining the replaced attack mission's inventory transaction");
            platform.publishStatus(mission.kind() + " [" + shortId(mission.id())
                    + "]: finishing the previous attack loadout handoff");
            return;
        }
        Optional<ActionLease> routeAction = acquireRuntimeAction(
                lease,
                ActionOwner.BARITONE_ROUTE,
                "runtime:mission:" + mission.id(),
                now);
        if (routeAction.isEmpty()) {
            platform.publishStatus("arming the exclusive route owner for mission "
                    + shortId(mission.id()));
            return;
        }
        baritone.bindMovementAction(
                executionKernel, lease, routeAction.orElseThrow(), clientTick, now);
        boolean conservativeAquaticResume =
                aquaticSafetyMissionHandoff.consumeForStart(mission.id());
        try {
            baritone.start(
                    new BaritonePort.Goal(mission.id(), mission.kind(), mission.parameters()),
                    lease,
                    conservativeAquaticResume);
        } catch (IllegalArgumentException error) {
            core.block(mission.id(), error.getMessage(), now);
            baritone.cancel(next.controlEpoch(), error.getMessage());
            publishMissionState(mission.id(), error.getMessage());
            return;
        } catch (RuntimeException error) {
            boolean changed = core.reportActionFailure(
                    mission.id(), lease, "Baritone start failed: " + error.getMessage(), now);
            baritone.cancel(core.arbiter().epoch(), error.getMessage());
            if (changed) publishMissionState(mission.id(), error.getMessage());
            return;
        }

        BaritonePort.Status status = baritone.poll(mission.id());
        if (status.controlEpoch() != lease.epoch()) {
            logger.debug("Ignoring stale Baritone status for mission {} from epoch {} (live epoch {})",
                    mission.id(), status.controlEpoch(), lease.epoch());
            return;
        }
        if (tracksProgress(mission.kind())
                && (status.state() == BaritonePort.State.CALCULATING
                || status.state() == BaritonePort.State.EXECUTING)) {
            if (!status.progressExpected()) {
                core.resetProgressBaseline(mission.id());
            } else {
            ProgressMonitor.Result progress = core.reportProgress(
                    mission.id(),
                    new ProgressMonitor.Observation(
                            now,
                            playerPosition(),
                            status.distanceRemaining(),
                            status.completedWorkUnits()));
            if (progress.status() == ProgressMonitor.Status.STUCK) {
                baritone.cancel(core.arbiter().epoch(), progress.reason());
                platform.publishStatus("replanning " + mission.kind()
                        + " after observed no-progress: " + progress.reason());
                publishMissionState(mission.id(), progress.reason());
                return;
            }
            }
        } else {
            core.resetProgressBaseline(mission.id());
        }
        switch (status.state()) {
            case COMPLETE -> {
                if (!core.completeAction(mission.id(), lease, now)) return;
                baritone.cancel(core.arbiter().epoch(), "mission complete");
                platform.publishStatus("completed " + mission.kind());
                core.mission(mission.id()).ifPresent(completed -> {
                    retireCompletedMissionPlan(completed, now);
                        sendMissionResult(completed.commandId(), true, completed,
                                "Completed " + completed.kind());
                });
            }
            case TRANSIENT_FAILURE -> {
                if (!core.reportActionFailure(mission.id(), lease, status.detail(), now)) return;
                baritone.cancel(core.arbiter().epoch(), status.detail());
                publishMissionState(mission.id(), status.detail());
            }
            case BLOCKED -> {
                core.block(mission.id(), status.detail(), now);
                baritone.cancel(core.arbiter().epoch(), status.detail());
                publishMissionState(mission.id(), status.detail());
            }
            case IDLE, CALCULATING, EXECUTING -> platform.publishStatus(
                    mission.kind() + " [" + shortId(mission.id()) + "]: " + status.detail());
        }
    }

    /** Returns true when the hierarchical executor consumed this tick. */
    private boolean handleAutonomyOutcome(
            Mission mission,
            ControlLease lease,
            AutonomyExecutor.Outcome outcome,
            long now) throws IOException {
        switch (outcome.state()) {
            case COMPLETE -> {
                if (!core.completeAction(mission.id(), lease, now)) return true;
                baritone.cancel(core.arbiter().epoch(), "hierarchical mission complete");
                autonomy.releaseControls("hierarchical mission complete");
                platform.publishStatus(outcome.detail());
                core.mission(mission.id()).ifPresent(completed -> {
                    retireCompletedMissionPlan(completed, now);
                        sendMissionResult(completed.commandId(), true, completed,
                                "Completed " + completed.kind());
                });
                return true;
            }
            case BLOCKED -> {
                boolean automaticallyReconcile =
                        outcome.failureCause() != BaritonePort.FailureCause.CONSTRUCTION_STALLED
                                && outcome.failureCause() != BaritonePort.FailureCause.CONSTRUCTION_RECOVERY_EXHAUSTED
                                && blockedReconciliation.permitsAutomaticReconciliation(outcome.detail());
                if (automaticallyReconcile) {
                    core.blockForReconciliation(mission.id(), outcome.detail(), now);
                } else {
                    core.block(mission.id(), outcome.detail(), now);
                }
                baritone.cancel(core.arbiter().epoch(), outcome.detail());
                autonomy.releaseControls("hierarchical mission blocked");
                if (automaticallyReconcile) {
                    autonomy.prepareBlockedReconciliation(outcome.detail());
                }
                publishMissionState(mission.id(), outcome.detail());
                return true;
            }
            case RETRY -> {
                if (core.reportActionFailure(mission.id(), lease, outcome.detail(), now)) {
                    baritone.cancel(core.arbiter().epoch(), outcome.detail());
                    autonomy.releaseControls("hierarchical mission retry");
                    publishMissionState(mission.id(), outcome.detail());
                }
                return true;
            }
            case RUNNING -> {
                if (outcome.progressExpected() && !outcome.localBuildStallOwner()) {
                    ProgressMonitor.Result progress = core.reportProgress(
                            mission.id(),
                            new ProgressMonitor.Observation(
                                    now,
                                    playerPosition(),
                                    outcome.distanceRemaining(),
                                    outcome.completedWorkUnits()));
                    if (progress.status() == ProgressMonitor.Status.STUCK) {
                        baritone.cancel(core.arbiter().epoch(), progress.reason());
                        platform.publishStatus("replanning " + mission.kind()
                                + " after observed no-progress: " + progress.reason());
                        publishMissionState(mission.id(), progress.reason());
                        return true;
                    }
                } else {
                    core.resetProgressBaseline(mission.id());
                }
                platform.publishStatus(mission.kind() + " [" + shortId(mission.id()) + "]: " + outcome.detail());
                return true;
            }
            case RESUME_LEGACY -> {
                core.resetProgressBaseline(mission.id());
                platform.publishStatus(outcome.detail());
                // The next tick either resumes the original hierarchical plan or
                // falls through to its direct Baritone mission with a fresh baseline.
                return true;
            }
            case PLAN_COMPLETE, CONTINUE_PLAN -> {
                core.resetProgressBaseline(mission.id());
                platform.publishStatus(outcome.detail());
                return true;
            }
        }
        throw new IllegalStateException("Unhandled autonomy outcome " + outcome.state());
    }

    private ProgressMonitor.Position playerPosition() {
        var player = client.player;
        if (player == null) return new ProgressMonitor.Position(0, 0, 0);
        return new ProgressMonitor.Position(player.getX(), player.getY(), player.getZ());
    }

    /** MissionStore is already terminal; plan retirement is retryable cleanup. */
    private void retireCompletedMissionPlan(Mission mission, long nowMillis) {
        try {
            autonomy.retireMissionPlanAfterOwnerCommit(mission, nowMillis);
        } catch (IOException | RuntimeException error) {
            logger.warn("Mission {} completed durably, but terminal plan evidence "
                            + "could not yet be bounded: {}",
                    mission.id(), error.getMessage());
        }
    }

    private static boolean tracksProgress(String kind) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "goto", "go", "come", "follow", "guard", "protect",
                    "mine", "get", "fetch", "acquire", "attack", "hunt" -> true;
            default -> false;
        };
    }

    private void handleExecutionFailure(RuntimeException error, long now) {
        Optional<Mission> active = core.activeMission();
        if (active.isPresent()
                && ExecutionFailurePolicy.shouldReportTransientFailure(active.get().state())) {
            try {
                core.reportTransientFailure(active.get().id(), error.getMessage(), now);
                publishMissionState(active.get().id(), error.getMessage());
            } catch (IOException persistenceError) {
                logger.error("Could not persist execution failure", persistenceError);
            }
        }
        long epoch = core.emergencyStop("tick exception", now);
        homeEconomyLease = null;
        baritone.cancel(epoch, "tick exception");
        autonomy.releaseControls("tick exception");
        body.cancelControls(epoch, "tick exception");
        actuators.neutralizeAll("tick exception");
        protectionModeLatch.clear();
        protectionSafetyRetreat.clear();
        soleTargetSuppression.clear();
        clearDistantThreatDisengagement();
        protectionReactivation.clear();
        baritone.clearProtectionTraversalScope();
    }

    private void drainInbound(long tickNow) {
        for (int count = 0; count < 64; count++) {
            JsonObject frame = inbound.poll();
            if (frame == null) break;
            handleFrame(frame, tickNow);
        }
    }

    /**
     * Applies connection-scoped recipe state on Minecraft's main thread. The
     * bridge callback runs on its socket worker, so it only publishes immutable
     * connection facts into this queue.
     */
    private void drainRecipeConnections(long now) throws IOException {
        RecipeConnectionEvent latest = null;
        RecipeConnectionEvent event;
        while ((event = recipeConnections.poll()) != null) latest = event;
        if (latest == null) return;

        baritone.attackMissionObjective().clear(now);

        cancelAutomaticIdleWork("Fresh idle delay after bridge connection change", now, true);
        idleContext = null;
        idleAuthenticatedAt = latest.connected() ? now : 0L;

        // Server threat evidence is authenticated-connection scoped. Never let
        // an attacker binding survive a disconnect or transport replacement.
        platform.resetServerThreats();
        explicitSleepContinuation.clear();
        behaviorCircuitBreaker.clearCombatTargets();
        paperRecipes.reset();
        paperRecipeDiscoveryRetry.reset();
        // A catalog belongs to one authenticated Paper connection. Revoke it
        // before negotiating another instead of planning with stale server data.
        autonomy.suspendResourceCatalog();
        if (!latest.connected()) {
            if (homeEconomyLease != null
                    || previousLayer == EntityCore.Layer.IDLE
                    && autonomy.homeEconomyNeedsIdleLease(now)) {
                preemptHomeEconomy(
                        HomeEconomyPolicy.Authority.DISCONNECTED,
                        now,
                        "bridge disconnect preempted Home economy");
            }
            return;
        }
        stockCommands.reconnect();
        if (latest.protocol() < 2
                || !latest.features().contains(PaperRecipeCatalogAssembler.FEATURE)) {
            autonomy.installResourceCatalog(
                    dev.entity.client.autonomy.policy.ResourceCatalog.defaults());
            logger.info("Paper did not advertise {}; using Entity's bounded fallback catalog",
                    PaperRecipeCatalogAssembler.FEATURE);
            return;
        }

        String requestId = UUID.randomUUID().toString();
        paperRecipes.begin(requestId);
        JsonObject request = PaperRecipeCatalogAssembler.request(requestId, "Entity");
        if (!bridge.send(request)) {
            paperRecipes.reset();
            logger.warn("Could not request Paper's authoritative recipe catalog after reconnect");
            return;
        }
        logger.info("Requested Paper's authoritative recipe catalog ({})", requestId);
    }

    private void retryPaperRecipeDiscoveryAfterJoin() {
        if (!paperRecipeDiscoveryRetry.claim(client.player != null && client.world != null)) return;
        if (!bridge.connected()
                || bridge.negotiatedProtocol() < 2
                || !bridge.hasFeature(PaperRecipeCatalogAssembler.FEATURE)) {
            return;
        }
        String requestId = UUID.randomUUID().toString();
        paperRecipes.begin(requestId);
        if (!bridge.send(PaperRecipeCatalogAssembler.request(requestId, "Entity"))) {
            paperRecipes.reset();
            logger.warn("Could not retry Paper recipe discovery after Entity joined the world");
            return;
        }
        logger.info("Retrying Paper recipe discovery now that Entity is in the world ({})", requestId);
    }

    private void handleFrame(JsonObject frame, long tickNow) {
        String type = text(frame, "type", "");
        if (type.equals("idle_context")) {
            try {
                WorldStateScope scope = worldScopeGate.verifiedScope().orElse(null);
                if (bridge.connected() && scope != null)
                    // All facts in this tick use its sampled time. Reading a later wall-clock
                    // millisecond here made every new receipt appear future-dated to core.tick.
                    idleContext = IdleContext.parse(frame, scope, tickNow);
            } catch (IllegalArgumentException invalid) {
                idleContext = null;
                logger.warn("Rejected idle owner context: {}", invalid.getMessage());
            }
            return;
        }
        if (type.equals(EntityRelationshipPolicyProtocol.POLICY_TYPE)) {
            try {
                EntityRelationshipPolicyProtocol.Snapshot relationship =
                        EntityRelationshipPolicyProtocol.decode(frame);
                entityAttacks.installRelationshipPolicy(relationship);
                foreignItems.installConfiguredOwner(relationship.ownerName());
            } catch (IllegalArgumentException error) {
                entityAttacks.connectionChanged(false);
                foreignItems.installConfiguredOwner("");
                logger.warn("Rejected malformed relationship policy; friendly PvP remains fail-closed: {}",
                        error.getMessage());
            }
            return;
        }
        if (type.equals(ForeignItemClaimProtocol.SNAPSHOT_TYPE)) {
            try {
                ForeignItemClaimClientState.Acceptance acceptance = foreignItems.accept(
                        ForeignItemClaimProtocol.decode(frame));
                if (acceptance.accepted()) {
                    logger.info("{}", acceptance.detail());
                } else {
                    logger.warn("{}", acceptance.detail());
                }
            } catch (IllegalArgumentException error) {
                foreignItems.invalidate();
                logger.warn("Rejected malformed legacy item-claim snapshot; ground pickup remains vanilla: {}",
                        error.getMessage());
            }
            return;
        }
        if (type.equals(ProtectedAreaPolicyProtocol.POLICY_TYPE)) {
            try {
                ProtectedAreaClientState.Acceptance acceptance = protectedAreas.accept(
                        ProtectedAreaPolicyProtocol.decode(frame));
                if (!acceptance.accepted()) {
                    logger.warn("Rejected protected-area policy: {}", acceptance.detail());
                } else if (!bridge.send(acceptance.acknowledgement())) {
                    protectedAreas.connectionChanged(false);
                    logger.warn("Installed protected-area policy but could not acknowledge it; "
                            + "world mutations remain disabled");
                } else {
                    logger.info("{}", acceptance.detail());
                }
            } catch (IllegalArgumentException error) {
                logger.warn("Rejected malformed protected-area policy: {}", error.getMessage());
            }
            return;
        }
        if (type.equals(ProtectedAreaPolicyProtocol.HOME_PERMIT_RESULT_TYPE)) {
            try {
                var result = ProtectedAreaPolicyProtocol.homePermitResult(frame);
                if (!protectedAreas.acceptHomePermitResult(
                        result, System.currentTimeMillis())) {
                    logger.warn("Rejected unsolicited or stale exact Home permit result {}",
                            result.permitId());
                }
            } catch (IllegalArgumentException error) {
                logger.warn("Rejected malformed exact Home permit result: {}",
                        error.getMessage());
            }
            return;
        }
        if (type.equals("recipe_catalog") || type.equals("recipe_catalog_error")) {
            PaperRecipeCatalogAssembler.Result result = paperRecipes.accept(frame);
            switch (result.disposition()) {
                case COMPLETE -> {
                    autonomy.installResourceCatalog(
                            autonomy.resourceCatalog().withRecipeBook(result.recipeBook()),
                            result.catalogId());
                    paperRecipeDiscoveryRetry.observe(result.discoveryApplied());
                    logger.info("Installed verified Paper recipe catalog {} with {} recipes",
                            result.catalogId(), result.recipeCount());
                    if (!result.discoveryApplied()) {
                        logger.info("Paper recipe discovery was not applied ({}); one retry is armed for world join",
                                result.discoveryCode());
                    }
                }
                case FAILED -> logger.warn(
                        "Rejected Paper recipe catalog; keeping the bounded fallback catalog: {}",
                        result.detail());
                case RECEIVING, IGNORED -> {
                    // Partial and stale connection frames cannot mutate planning knowledge.
                }
            }
            return;
        }
        if (type.equals("delivery_prepared")) {
            try {
                DeliveryProtocol.Prepared prepared = DeliveryProtocol.prepared(frame);
                autonomy.acceptDeliveryPrepared(
                        prepared.missionId(), prepared.nonce(), prepared.accepted(), prepared.reason());
            } catch (IllegalArgumentException error) {
                logger.warn("Rejected malformed delivery preparation acknowledgement: {}", error.getMessage());
            }
            return;
        }
        if (type.equals("delivery_commit_result")) {
            try {
                DeliveryProtocol.CommitResult result = DeliveryProtocol.commitResult(frame);
                autonomy.acceptDeliveryCommitResult(result);
            } catch (IllegalArgumentException error) {
                logger.warn("Rejected malformed delivery commit result: {}", error.getMessage());
            }
            return;
        }
        if (type.equals("delivery_receipt")) {
            try {
                DeliveryProtocol.Receipt receipt = DeliveryProtocol.receipt(frame);
                autonomy.acceptDeliveryReceipt(
                        receipt.receiptId(), receipt.missionId(), receipt.nonce(),
                        receipt.recipient(), receipt.item(),
                        receipt.confirmedCount(), receipt.expectedCount());
            } catch (IllegalArgumentException error) {
                logger.warn("Rejected malformed delivery receipt: {}", error.getMessage());
            }
            return;
        }
        if (type.equals("delivery_returned")) {
            try {
                DeliveryProtocol.Returned returned = DeliveryProtocol.returned(frame);
                autonomy.acceptDeliveryReturned(
                        returned.returnId(), returned.missionId(), returned.nonce(),
                        returned.recipient(), returned.item(), returned.confirmedCount(),
                        returned.expectedCount(), returned.remainingCount());
            } catch (IllegalArgumentException error) {
                logger.warn("Rejected malformed returned-delivery frame: {}", error.getMessage());
            }
            return;
        }
        if (type.equals("server_damage")) {
            try {
                ServerDamageEvent damage = ServerDamageEvent.parse(frame);
                retainSelfDamageEvidence(damage, System.currentTimeMillis());
                platform.acceptServerDamage(damage);
            } catch (IllegalArgumentException error) {
                logger.warn("Rejected malformed server damage frame: {}", error.getMessage());
            }
            return;
        }
        if (type.equals("server_attacker_reset")) {
            try {
                var boundary = dev.entity.client.bridge.ServerAttackerResetEvent.parse(frame);
                platform.invalidatePlayerAttacker(boundary.attackerUuid(), boundary.timestamp());
            } catch (IllegalArgumentException error) {
                logger.warn("Rejected malformed attacker life boundary: {}", error.getMessage());
            }
            return;
        }
        if (type.equals("server_threat_target")) {
            try {
                platform.acceptServerThreatTarget(ServerThreatTargetEvent.parse(frame));
            } catch (IllegalArgumentException error) {
                logger.warn("Rejected malformed server threat target frame: {}", error.getMessage());
            }
            return;
        }
        if (type.equals("block_break_rejected")) {
            String dimension = text(frame, "dimension", "");
            if (!dimension.isBlank()) {
                autonomy.acceptBlockBreakRejected(
                        dimension,
                        (int) number(frame, "x", 0),
                        (int) number(frame, "y", 0),
                        (int) number(frame, "z", 0),
                        (long) number(frame, "timestamp", System.currentTimeMillis()));
            }
            return;
        }
        if (type.equals("target_update")) {
            handleTargetUpdate(frame);
            return;
        }
        if (type.equals("mission_sync")) {
            try {
                handleMissionSync(MissionSyncSnapshot.parse(frame));
            } catch (Exception error) {
                logger.warn("Rejected Entity mission sync: {}", error.getMessage());
            }
            return;
        }
        if (!type.equals("command")) return;

        String id = text(frame, "id", "unknown");
        try {
            IncomingCommand command = IncomingCommand.parse(
                    frame,
                    bridge.negotiatedProtocol() == 0 ? 1 : bridge.negotiatedProtocol());
            executeCommand(command);
        } catch (Exception error) {
            logger.warn("Rejected Entity command {}: {}", id, error.getMessage());
            Map<String, String> correlation = Map.of();
            try {
                String argumentsKey = frame.has("operation") ? "payload" : "args";
                if (frame.has(argumentsKey) && frame.get(argumentsKey).isJsonObject())
                    correlation = MissionStatusReporter.queueParameters(frame.getAsJsonObject(argumentsKey));
            } catch (IllegalArgumentException ignored) { /* Malformed authority is not echoed. */ }
            sendResult(id, false, "rejected", null, error.getMessage(), correlation);
        }
    }

    private record ProtectionWorkContext(String identity,String planSummary) { }

    /** Explicit missions win; automatic Stock is work only while its original admission remains valid. */
    private ProtectionWorkContext protectionWork(long now) {
        Optional<Mission> active = core.activeMission();
        if(active.isPresent()) {
            var mission=active.orElseThrow();
            return new ProtectionWorkContext(mission.id(),autonomy.planSummary(mission.id()));
        }
        var idle=idleSettings.settings();
        boolean stock=idleWork.active()==IdleWorkCoordinator.Job.DAY_STOCK
                ||idleWork.active()==IdleWorkCoordinator.Job.NIGHT_STOCK;
        if(!stock||!idle.enabled()||operatorStop.stopped()||!deathMissionId.isBlank()
                ||!bridge.connected()||!worldScopeGate.verified()||client.world==null||client.player==null
                ||client.player.isDead()||idleContext==null||!idleContext.fresh(now,idleAuthenticatedAt)
                ||idleContext.manualWorkPending()||!idle.allowOffline()&&!idleContext.ownerOnline())
            return new ProtectionWorkContext("","");
        return autonomy.automaticStockProtectionWork()
                .filter(work->worldScopeGate.verifiedScope().filter(scope->scope.key().equals(work.worldId())).isPresent())
                .map(work->new ProtectionWorkContext(work.identity(),work.planSummary()))
                .orElseGet(()->new ProtectionWorkContext("",""));
    }

    private ProtectionPolicy.ActivityContext classifyProtectionActivity(ProtectionWorkContext work) {
        boolean breaking = client.interactionManager != null
                && client.interactionManager.isBreakingBlock();
        boolean handledScreen = client.currentScreen instanceof HandledScreen<?>;
        FabricBaritonePort.Snapshot navigation = baritone.snapshot();
        double horizontalSpeed = 0.0;
        if (client.player != null) {
            var velocity = client.player.getVelocity();
            horizontalSpeed = Math.hypot(velocity.x, velocity.z);
        }
        return ProtectionActivityClassifier.classify(
                !work.identity().isBlank(),
                breaking,
                handledScreen,
                work.planSummary(),
                navigation.pathing(),
                horizontalSpeed);
    }

    private void handleTargetUpdate(JsonObject frame) {
        String player = text(frame, "player", "").trim();
        if (player.isBlank()) return;
        boolean online = booleanValue(frame.get("online"), false);
        JsonObject position = object(frame, "position");
        double x = number(position, "x", 0);
        double y = number(position, "y", 0);
        double z = number(position, "z", 0);
        String dimension = text(position, "dimension", "");
        long timestamp = (long) number(frame, "timestamp", System.currentTimeMillis());
        baritone.updateTrackedTarget(player, online, x, y, z, dimension, timestamp);
    }

    private void handleMissionSync(MissionSyncSnapshot sync) throws IOException {
        long now = System.currentTimeMillis();
        Set<String> localWins = new HashSet<>();
        Set<String> remoteIds = new HashSet<>();
        for (MissionSyncSnapshot.RemoteMission remote : sync.missions()) {
            remoteIds.add(remote.id());
            Optional<Mission> local = core.mission(remote.id());
            boolean reconstructed = local.isEmpty();
            if (local.isEmpty()) {
                EntityCore.Submission submission = core.submit(
                        CommandTranslator.toCore(remote.command()),
                        Math.min(now, remote.createdAt()));
                if (!submission.mission().id().equals(remote.id())) {
                    throw new IllegalArgumentException("reconstructed mission identity mismatch");
                }
                local = core.mission(remote.id());
            }
            Mission mission = local.orElseThrow();
            if (mission.state() == MissionState.PAUSED_BY_OWNER
                    && mission.pauseReason().equals("temporarily paused for explicit Home sleep")) {
                publishMissionState(mission.id(), "Sleep continuation remains locally parked");
                localWins.add(mission.id());
                continue;
            }
            if (mission.state().terminal()) {
                publishMissionState(mission.id(), "Local durable terminal state wins during bridge reconciliation");
                localWins.add(mission.id());
                continue;
            }
            if (remote.queuePauseOwnsRecovery(mission.parameters())) {
                core.pause(mission.id(), remote.reason().isBlank()
                        ? "Queue awaits explicit retry" : remote.reason(), now);
                localWins.add(mission.id());
                continue;
            }
            if (!reconstructed && (mission.state() == MissionState.BLOCKED
                    || mission.state() == MissionState.RETRY_WAIT)) {
                publishMissionState(mission.id(), "Restoring the client's durable recovery state after reconnect");
                localWins.add(mission.id());
                continue;
            }
            if (remote.status() == MissionSyncSnapshot.Status.PAUSED) {
                core.pause(mission.id(), remote.reason().isBlank() ? "paused on Paper" : remote.reason(), now);
            } else if (remote.status() == MissionSyncSnapshot.Status.BLOCKED) {
                core.block(mission.id(), remote.reason().isBlank() ? "blocked on Paper" : remote.reason(), now);
            }
        }

        // Protocol-2 missions carry a positive Paper sequence. Their omission
        // from Paper's complete nonterminal snapshot is a durable tombstone,
        // which prevents a cancelled mission from resurrecting after both
        // processes restart and an old in-memory cancel frame is gone.
        for (Mission local : core.missions()) {
            if (!local.state().terminal()
                    && (MissionStatusReporter.sequence(local).isPresent()
                    || MissionStatusReporter.unregisteredQueuedHome(local))
                    && !remoteIds.contains(local.id())) {
                String reason = "Paper no longer lists this mission in its durable journal";
                cancelScopedMission(local, reason, now);
                if (MissionStatusReporter.unregisteredQueuedHome(local)) {
                    sendMissionResult(local.commandId(), true, core.mission(local.id()).orElseThrow(),
                            "Queued Home travel was interrupted; use /e queue retry");
                } else publishMissionState(local.id(),
                        "Cancelled during reconciliation because Paper no longer lists the mission");
            }
        }

        if (!sync.currentMissionId().isBlank()) {
            Optional<MissionSyncSnapshot.RemoteMission> currentRemote = sync.missions().stream()
                    .filter(remote -> remote.id().equals(sync.currentMissionId())).findFirst();
            Optional<Mission> current = core.mission(sync.currentMissionId());
            if (!localWins.contains(sync.currentMissionId())
                    && current.isPresent() && !current.get().state().terminal() && currentRemote.isPresent()
                    && switch (currentRemote.get().status()) {
                    case QUEUED, ACTIVE, RETRYING -> true;
                    case PAUSED, BLOCKED -> false;
                    }) {
                MissionState state = current.get().state();
                if (state == MissionState.PAUSED_BY_OWNER || state == MissionState.BLOCKED) {
                    core.resume(current.get().id(), now);
                } else if (state == MissionState.RETRY_WAIT) {
                    core.retryNow(current.get().id(), now);
                } else {
                    core.focus(current.get().id(), now);
                }
            }
        }
    }

    private void executeCommand(IncomingCommand command) throws IOException {
        long now = System.currentTimeMillis();
        MissionStatusReporter.queueParameters(command.arguments());
        switch (command.action()) {
            case "status" -> sendResult(
                    command.id(), true, "completed", null, statusText());
            case "why" -> sendResult(
                    command.id(), true, "completed", null, whyText(command));
            case "plan" -> sendResult(
                    command.id(), true, "completed", null,
                    autonomy.planSummary(text(command.arguments(), "missionId", "")));
            case "inventory" -> {
                List<String> pages = booleanValue(command.arguments().get("equipment"), false)
                        ? List.of(autonomy.equipmentSummary())
                        : autonomy.inventorySummaryPages(text(command.arguments(), "item", ""));
                for (int page = 0; page < pages.size(); page++) {
                    sendResult(command.id(), true,
                            dev.entity.client.autonomy.inventory.InventoryReport.phase(page, pages.size()),
                            null, pages.get(page));
                }
            }
            case "home" -> handleHome(command, now);
            case "tidy" -> handleTidy(command, now);
            case "perception" -> sendResult(command.id(), true, "completed", null,
                    perception.command(command.arguments(), worldScopeGate.verified(), () -> perceptionWorkActive(now)));
            case "idle" -> handleIdleCommand(command, now);
            case "farm_policy" -> handleFarmPolicy(command, now);
            case "blueprint_policy" -> handleBlueprint(command);
            case "stock" -> {
                String operation = text(command.arguments(), "operation", "status")
                        .trim().toLowerCase(Locale.ROOT);
                String message;
                if (operation.equals("status")) {
                    message = autonomy.stockStatus();
                } else if (operation.equals("set")) {
                    if (!command.arguments().has("enabled")) {
                        throw new IllegalArgumentException("stock set requires enabled=true or false");
                    }
                    message = autonomy.setStockEnabled(
                            booleanValue(command.arguments().get("enabled"), false));
                    if (!booleanValue(command.arguments().get("enabled"), false))
                        stockCommands.cancelActive("Stock was disabled by the owner");
                } else if (operation.equals("run")) {
                    executeStockRun(command, now);
                    return;
                } else {
                    throw new IllegalArgumentException("stock operation must be set, run, or status");
                }
                sendResult(command.id(), true, "completed", null, message);
            }
            case "visuals" -> {
                String operation = text(command.arguments(), "operation", "status")
                        .trim().toLowerCase(Locale.ROOT);
                String message;
                if (operation.equals("status")) {
                    message = baritone.visualsStatus();
                } else if (operation.equals("set")) {
                    if (!command.arguments().has("enabled")) {
                        throw new IllegalArgumentException("visuals set requires enabled=true or false");
                    }
                    message = baritone.setVisualsEnabled(
                            booleanValue(command.arguments().get("enabled"), false));
                } else {
                    throw new IllegalArgumentException("visuals operation must be set or status");
                }
                sendResult(command.id(), true, "completed", null, message);
            }
            case "protect" -> handleProtection(command);
            case "pause" -> {
                Mission mission = lifecycleMission(command);
                explicitSleepContinuation.clear();
                boolean controlled = isControlledMission(mission.id());
                core.pause(mission.id(), "paused by " + command.requestedBy(), now);
                cancelAutomaticIdleWork("Owner paused mission", now);
                soleTargetSuppression.clear();
                clearDistantThreatDisengagement();
                protectionReactivation.clear();
                baritone.clearProtectionTraversalScope();
                if (controlled) {
                    long epoch = core.emergencyStop("owner paused mission", now);
                    baritone.cancel(epoch, "owner paused mission");
                    autonomy.releaseControls("owner paused mission");
                    body.cancelControls(epoch, "owner paused mission");
                }
                core.mission(mission.id()).ifPresent(updated ->
                        sendMissionResult(command.id(), true, updated, "Paused " + updated.kind()));
            }
            case "resume" -> {
                Mission mission = lifecycleMission(command);
                explicitSleepContinuation.clear();
                rejectLifecycleMutationDuringDeathRecovery("resume");
                // Commit the mission transition first. EntityCore rolls it back if MissionStore
                // persistence fails; only an accepted transition may acknowledge the death budget.
                core.resume(mission.id(), now);
                if (mission.kind().equals("build")) blueprintRestartFence=false;
                cancelAutomaticIdleWork("Owner resumed mission", now);
                soleTargetSuppression.clear();
                clearDistantThreatDisengagement();
                protectionReactivation.clear();
                baritone.clearProtectionTraversalScope();
                acknowledgeDeathLoopAfterCommittedLifecycle(mission.id(), now);
                long epoch = core.emergencyStop("owner selected mission to resume", now);
                baritone.cancel(epoch, "owner selected mission to resume");
                autonomy.releaseControls("owner selected mission to resume");
                body.cancelControls(epoch, "owner selected mission to resume");
                operatorStop.resumeForExplicitWork("resume");
                core.mission(mission.id()).ifPresent(updated ->
                        sendMissionResult(command.id(), true, updated, "Resuming " + updated.kind()));
            }
            case "retry" -> {
                Mission mission = lifecycleMission(command);
                explicitSleepContinuation.clear();
                rejectLifecycleMutationDuringDeathRecovery("retry");
                boolean retryable = switch (mission.state()) {
                    case BLOCKED, RETRY_WAIT, PAUSED_BY_OWNER,
                            PAUSED_BY_SAFETY, PAUSED_BY_PROTECTION -> true;
                    default -> false;
                };
                if (!retryable) {
                    throw new IllegalArgumentException(
                            "Mission " + shortId(mission.id())
                                    + " is not blocked, paused, or waiting to retry");
                }
                if (!core.retryNow(mission.id(), now)) {
                    throw new IllegalArgumentException(
                            "Mission " + shortId(mission.id()) + " is not blocked, paused, or waiting to retry");
                }
                if (mission.kind().equals("build")) blueprintRestartFence=false;
                cancelAutomaticIdleWork("Owner retried mission", now);
                soleTargetSuppression.clear();
                clearDistantThreatDisengagement();
                protectionReactivation.clear();
                baritone.clearProtectionTraversalScope();
                // A failed guard-store clear is conservative and retried; the successfully
                // persisted operator transition must not be reported as rejected.
                acknowledgeDeathLoopAfterCommittedLifecycle(mission.id(), now);
                long epoch = core.arbiter().epoch();
                baritone.cancel(epoch, "owner forced mission retry");
                autonomy.releaseControls("owner forced mission retry");
                body.cancelControls(epoch, "owner forced mission retry");
                operatorStop.resumeForExplicitWork("retry");
                core.mission(mission.id()).ifPresent(updated ->
                        sendMissionResult(command.id(), true, updated, "Retrying " + updated.kind()));
            }
            case "cancel" -> {
                Mission mission = lifecycleMission(command);
                cancelScopedMission(mission, "cancelled by " + command.requestedBy(), now);
                core.mission(mission.id()).ifPresent(updated -> sendMissionResult(command.id(), true, updated,
                        "Cancelled " + updated.kind() + "; automatic Idle preference unchanged"));
            }
            case "stop" -> {
                explicitSleepContinuation.clear();
                // Even with no ordinary mission, Stop is a durable opt-out from automatic work.
                boolean commandHandoff = booleanValue(command.arguments().get("commandHandoff"), false);
                try { if (!commandHandoff) saveIdleEnabled(false); }
                catch (IOException | RuntimeException error) {
                    logger.error("Could not persist idle OFF; still stopping all automatic body work", error);
                }
                idleWork.reset(commandHandoff ? "Owner command handoff" : "OFF: stopped by owner");
                try { stockCommands.cancelActive("Stopped by owner"); }
                catch (IOException | IllegalArgumentException error) {
                    logger.error("Could not persist Stock cancellation; still stopping all body work", error);
                }
                String requestedId = text(command.arguments(), "missionId", "").trim();
                Optional<Mission> selected = lifecycleMissionIfPresent(command);
                String acknowledgedId = selected.map(Mission::id).orElse(requestedId);
                String stoppedActivity = currentControllerStatus();
                // Halt before any mission/cargo checkpoint IO. Failed persistence must
                // neither defeat Stop nor discard the original unresolved transfer.
                operatorStop.stop(stoppedActivity);
                baritone.attackMissionObjective().clear(now);
                long epoch = core.emergencyStop("owner stop", now);
                homeEconomyLease = null;
                baritone.cancel(epoch, "owner stop");
                autonomy.releaseControlsForOwnerStop("owner stop", clientTick);
                body.cancelControls(epoch, "owner stop");
                actuators.neutralizeAll("owner stop");
                protectionModeLatch.clear();
                protectionSafetyRetreat.clear();
                if (selected.isPresent()) core.cancel(selected.get().id(), "stopped by owner", now);
                if (selected.filter(EntityRuntime::isAutomaticFarmMission).isPresent()) {
                    clearAutomaticFarmTracking();
                }
                // Stop also acknowledges a retained episode when the mission was already
                // cancelled/absent. For a live mission, cancellation commits first.
                acknowledgeDeathLoopAfterCommittedLifecycle(acknowledgedId, now);
                soleTargetSuppression.clear();
                behaviorCircuitBreaker.clearCombatTargets();
                clearDistantThreatDisengagement();
                protectionReactivation.clear();
                baritone.clearProtectionTraversalScope();
                if (selected.isPresent()) autonomy.prepareStoppedCapacityTransfer(selected.get().id(), now);
                autonomy.ownerStopHomeEconomy(now);
                if (selected.isPresent()) {
                    autonomy.cancelMission(
                            selected.get().id(), core.arbiter().epoch(), "stopped by owner", true);
                }
                String stopMessage = "Stopped " + stoppedActivity + (commandHandoff
                        ? "; handing controls to the next explicit job; Idle preference unchanged"
                        : "; current work cancelled; automatic Idle OFF. Use a new work command,"
                                + " /e build resume for a saved build, or /e idle on for automatic work");
                if (selected.isPresent()) {
                    core.mission(selected.get().id()).ifPresent(cancelled ->
                            sendMissionResult(command.id(), true, cancelled, stopMessage));
                } else {
                    sendResult(command.id(), true, "cancelled",
                            requestedId.isBlank() ? null : requestedId,
                            stopMessage);
                }
            }
            default -> {
                if (command.action().equals("build")) {
                    requireBlueprintWorld(command.arguments());
                    String operation=text(command.arguments(),"operation","confirm");
                    var p=blueprints.require(text(command.arguments(),"projectId",""),
                            text(command.arguments(),"digest",""));
                    var policy=requireBlueprintPolicy(command.arguments());
                    if(operation.equals("resume"))
                        blueprints.resume(command.requestedBy(),policy.revision(),policy.digest());
                    else {
                        blueprints.confirm(client,p.projectId(),p.digest(),booleanValue(command.arguments().get("gather"),false));
                        blueprints.bindAuthority(command.requestedBy(),policy.revision(),policy.digest());
                    }
                    command.arguments().addProperty("gather",blueprints.gather());
                }
                EntityCore.Submission submission = core.submit(CommandTranslator.toCore(command), now);
                if (!submission.duplicate()) explicitSleepContinuation.clear();
                if (command.action().equals("build")) blueprintRestartFence=false;
                if (!submission.duplicate()) cancelAutomaticIdleWork("Owner selected " + command.action(), now);
                if (!submission.duplicate()) stockCommands.cancelActive("Replaced by " + command.action());
                if (submission.duplicate() && submission.mission().state().terminal()) {
                    Mission terminal = submission.mission();
                    if (missionReporter.delivered(terminal)) {
                        sendAcknowledgement(command.id());
                    } else {
                        sendMissionResult(command.id(), true, terminal,
                                "Mission already " + terminal.state().name().toLowerCase(Locale.ROOT));
                    }
                    return;
                }
                operatorStop.resumeForExplicitWork(command.action());
                sendResult(
                        command.id(),
                        true,
                        "persisted",
                        submission.mission().id(),
                        submission.duplicate()
                                ? "Mission already persisted; returning existing mission"
                                : "Accepted " + submission.mission().kind() + " mission",
                        submission.mission().parameters());
            }
        }
    }

    /** Observe existing owners; changing discovery policy never cancels or resumes their work. */
    private boolean perceptionWorkActive(long now) {
        return perceptionWorkActive(now, "");
    }

    private boolean perceptionWorkActive(long now, String parkedMissionId) {
        if (core.activeMission().filter(mission -> !mission.id().equals(parkedMissionId)
                || mission.state() != MissionState.PAUSED_BY_OWNER).isPresent()
                || !deathMissionId.isBlank() || stockCommands.active() != null
                || homeEconomyLease != null || autonomy.homeEconomyNeedsIdleLease(now)
                || autonomy.homeEconomyNeedsForegroundFence() || autonomy.homePreemptionDrainPending()
                || autonomy.boundedGroundItemVerticalRecoveryActive(now)) return true;
        var inventory = autonomy.inventoryTransactionDiagnostics();
        if (!inventory.pendingClickOwner().isBlank() || !inventory.cursorOwner().isBlank()
                || inventory.pendingRemovals() != 0 || client.currentScreen instanceof HandledScreen<?>
                || client.player != null && !client.player.currentScreenHandler.getCursorStack().isEmpty()) return true;
        var pathing = baritone.snapshot();
        if (!pathing.missionId().isBlank() || pathing.pathing()
                || actuators.blockBreakSnapshot().active() || actuators.heldUseSnapshot().active()) return true;
        long epoch = core.arbiter().epoch();
        return executionKernel.activeLease().filter(lease -> lease.parentEpoch() == epoch).isPresent()
                || executionKernel.snapshot().pendingOwner().isPresent()
                || movement.snapshot().activeAction().filter(lease -> lease.parentEpoch() == epoch).isPresent()
                || decision != null && decision.lease().filter(lease -> core.arbiter().isValid(lease, now)).isPresent();
    }

    private IdleWorkCoordinator.Settings idleCoordinatorSettings() {
        var settings = idleSettings.settings();
        return new IdleWorkCoordinator.Settings(settings.enabled() && !operatorStop.stopped(),
                settings.delayMillis(), settings.allowOffline());
    }

    /** One admission decision before any priority-zero Home actuator can run this tick. */
    private void tickAutomaticIdleWork(long now, EntityCore.Layer layer) throws IOException {
        var settings = idleSettings.settings();
        Optional<Mission> automaticMission = automaticFarmMission();
        if (!settings.enabled() && !idleWork.ownsWork() && automaticMission.isEmpty()) return;
        var stock = autonomy.autonomousStockObservation(now);
        var sleep = autonomy.automaticSleepObservation(now);
        String workKey = idleDemandKey(stock, now);
        if (automaticMission.isPresent() && automaticMission.orElseThrow().state().terminal()) {
            Mission settled = automaticMission.orElseThrow();
            boolean success = settled.state() == MissionState.COMPLETED;
            String result = success
                    ? "Automatic farm '" + automaticFarmArea + "' completed"
                    : "Automatic farm '" + automaticFarmArea + "' "
                    + settled.state().name().toLowerCase(Locale.ROOT);
            idleWork.finished(success, result, now, automaticFarmWorkKey);
            clearAutomaticFarmTracking();
            automaticMission = Optional.empty();
        } else if ((idleWork.active() == IdleWorkCoordinator.Job.DAY_STOCK
                || idleWork.active() == IdleWorkCoordinator.Job.NIGHT_STOCK)
                && !stock.requested() && stock.settled()) {
            idleWork.finished(stock.state() == IdleStockPolicy.State.COMPLETED,
                    stock.detail(), now, workKey);
            // The completed inspection may have replaced stale Home contents
            // this same tick. Recompute farm admission immediately so the old
            // five-second cache cannot request a second identical inspection.
            automaticFarmObservedAt = Long.MIN_VALUE;
        }

        if (automaticMission.isEmpty()) {
            refreshAutomaticFarmObservations(now, false);
        }
        AutomaticFarmAdmission farm = automaticFarmAdmission();
        boolean contextFresh = idleContext != null && idleContext.fresh(now, idleAuthenticatedAt);
        boolean manualPending = core.activeMission()
                .filter(mission -> !isAutomaticFarmMission(mission)).isPresent()
                || stockCommands.active() != null
                || contextFresh && idleContext.manualWorkPending();
        boolean externalBusy = !deathMissionId.isBlank() || layer == EntityCore.Layer.SURVIVAL
                || layer == EntityCore.Layer.PROTECTION || layer == EntityCore.Layer.WAITING;
        boolean night = client.world != null && "minecraft:overworld".equals(
                client.world.getRegistryKey().getValue().toString()) && client.world.isNight();
        long nightId = client.world == null ? 0L : Math.floorDiv(client.world.getTimeOfDay(), 24_000L);
        // Cached storage is a reason to inspect, never evidence authorizing acquisition.
        // Satisfied storage is rechecked at most every five minutes, not every tick/minute.
        boolean inspectDue = stock.storageObservedAtMillis() < 0
                || now - stock.storageObservedAtMillis() >= 300_000L;
        boolean needsStock = inspectDue || !IdleStockPolicy.selectCategory(stock.carried(), stock.stored(),
                settings.surplusTargets(), stock.workingTools()).isEmpty()
                || farm.requiresStorageInspection();
        boolean neutral = layer == EntityCore.Layer.IDLE && !perceptionWorkActive(now);
        observeAutomaticSleepCompletion(sleep, neutral, now);
        var facts = new IdleWorkCoordinator.Facts(bridge.connected() && worldScopeGate.verified()
                && contextFresh && client.player != null && client.world != null,
                contextFresh && idleContext.ownerOnline(), manualPending, externalBusy, neutral,
                night, nightId, sleep.bedAvailable(), stock.homeConfigured() && stock.storageConfigured(),
                workKey, needsStock, farm.eligible(), farm.workKey(), farm.detail(),
                deathMissionId.isBlank() && (layer == EntityCore.Layer.SURVIVAL
                        || layer == EntityCore.Layer.PROTECTION));
        IdleWorkCoordinator.Job priorJob = idleWork.active();
        IdleWorkCoordinator.Action action = idleWork.evaluate(idleCoordinatorSettings(), facts, now);
        try {
            switch (action) {
                case CANCEL, YIELD -> {
                    boolean ownerRevoked = !settings.enabled() || operatorStop.stopped() || manualPending;
                    if (ownerRevoked) autonomy.clearAutomaticStockRecovery();
                    if (priorJob == IdleWorkCoordinator.Job.FARM
                            || !automaticFarmMissionId.isBlank()) {
                        cancelAutomaticFarmMission("automatic work yielded", now);
                    } else {
                        // evaluate has revoked the automatic marker, but the existing Home owner
                        // still drains any acknowledged click before foreground work takes over.
                        boolean cancelSleep = priorJob == IdleWorkCoordinator.Job.SLEEP;
                        if (ownerRevoked) autonomy.ownerStopHomeEconomy(now, cancelSleep);
                        else autonomy.interruptIdleHomeEconomy(now, cancelSleep);
                        releaseHomeEconomyOwnership("automatic work yielded");
                    }
                }
                case DAY_STOCK, NIGHT_STOCK -> {
                    String detail = autonomy.requestIdleStockRun(settings.surplusTargets(),
                            action == IdleWorkCoordinator.Action.NIGHT_STOCK, now);
                    logger.info("Idle work {} {}: {}",
                            autonomy.autonomousStockObservation(now).requested() ? "admitted" : "deferred",
                            action, detail);
                }
                case SLEEP -> logger.info("Idle sleep admitted: {}", autonomy.requestAutomaticHomeSleep(now));
                case FARM -> startAutomaticFarm(farm, now);
                case NONE -> { }
            }
        } catch (IllegalArgumentException rejected) {
            idleWork.finished(false, "Resting: " + rejected.getMessage(), now,
                    action == IdleWorkCoordinator.Action.FARM ? farm.workKey() : workKey);
        }
    }

    /**
     * Sleep's result arrives before executeDecision releases its final Home/body lease.
     * Retain the automatic owner until that physical handoff is neutral; otherwise the
     * coordinator mistakes our own cleanup for new activity and resets the quiet clock.
     * evaluate still runs every tick, so manual/Stop/death/waiting/context fences can
     * cancel it; a temporary survival/protection borrow only pauses the Home owner.
     */
    void observeAutomaticSleepCompletion(AutonomyExecutor.IdleSleepObservation sleep,
                                         boolean neutral, long now) {
        if (idleWork.active() == IdleWorkCoordinator.Job.SLEEP
                && !sleep.requested() && sleep.settled() && neutral) {
            idleWork.finished(!sleep.blocked(), sleep.detail(), now);
        }
    }

    private String idleDemandKey(IdleStockPolicy.Observation stock, long now) {
        return idleSettings.settings().revision() + ":" + stock.stockEnabled() + ":"
                + autonomy.homeAnchor().map(HomeEconomySession.HomeAnchor::fingerprint).orElse("none") + ":"
                + new java.util.TreeMap<>(stock.carried()) + ":" + new java.util.TreeMap<>(stock.stored()) + ":"
                + new java.util.TreeMap<>(stock.workingTools()) + ":"
                + autonomy.automaticStockRecoveryKey(now);
    }

    private void refreshAutomaticFarmObservations(long now, boolean force) throws IOException {
        if (!force && automaticFarmObservedAt != Long.MIN_VALUE
                && now >= automaticFarmObservedAt
                && now - automaticFarmObservedAt < AUTOMATIC_FARM_OBSERVATION_INTERVAL_MILLIS) {
            return;
        }
        ArrayList<AutonomyExecutor.ManagedFarmAutomaticObservation> observations =
                new ArrayList<>();
        new java.util.TreeMap<>(farmAutomation.state().enabledAreas())
                .forEach((area, fingerprint) -> observations.add(
                        autonomy.automaticFarmObservation(area, fingerprint, now)));
        automaticFarmObservations = List.copyOf(observations);
        automaticFarmObservedAt = now;
        if (!automaticFarmMissionId.isBlank()) return;
        for (AutonomyExecutor.ManagedFarmAutomaticObservation observation : observations) {
            if (observation.areaAvailable()
                    && observation.areaCompletelyLoaded()
                    && observation.matureTotal() == 0) {
                farmAutomation.clearAttempt(
                        observation.area(), observation.areaFingerprint());
            }
        }
    }

    private AutomaticFarmAdmission automaticFarmAdmission() {
        AutonomyExecutor.ManagedFarmAutomaticObservation fallback = null;
        String fallbackDetail = farmAutomation.state().enabledAreas().isEmpty()
                ? "Resting: no managed farm is enabled for automatic work"
                : "Resting: enabled managed farms are not currently eligible";
        for (AutonomyExecutor.ManagedFarmAutomaticObservation observation
                : automaticFarmObservations) {
            if (fallback == null) {
                fallback = observation;
                fallbackDetail = observation.detail();
            }
            if (!observation.eligible()) continue;
            String attempted = farmAutomation.attemptedWorkKey(observation.area());
            if (!attempted.isBlank()) {
                fallback = observation;
                fallbackDetail = "Resting: farm '" + observation.area()
                        + "' already attempted this growth cycle; it rearms only after "
                        + "a loaded observation finds no mature crop";
                continue;
            }
            return new AutomaticFarmAdmission(
                    observation, true, observation.workKey(), observation.detail(), false);
        }
        boolean inspect = fallback != null && fallback.matureTotal() > 0
                && fallback.homeReady() && fallback.storageConfigured()
                && !fallback.storageFresh()
                && farmAutomation.attemptedWorkKey(fallback.area()).isBlank();
        return new AutomaticFarmAdmission(fallback, false,
                fallback == null ? "" : fallback.workKey(), fallbackDetail, inspect);
    }

    private void startAutomaticFarm(AutomaticFarmAdmission admission, long now)
            throws IOException {
        AutonomyExecutor.ManagedFarmAutomaticObservation observation =
                Objects.requireNonNull(admission.observation(), "farm observation");
        if (!admission.eligible() || admission.workKey().isBlank()) {
            throw new IllegalArgumentException("managed farm is no longer eligible");
        }
        if (!farmAutomation.markAttempt(
                observation.area(), observation.areaFingerprint(), admission.workKey())) {
            idleWork.finished(false,
                    "Resting: this exact farm maturity observation was already attempted",
                    now, admission.workKey());
            return;
        }
        long revision = farmAutomation.state().revision();
        String material = "entity2-idle-farm-v1\u0000" + observation.area()
                + '\u0000' + admission.workKey() + '\u0000' + revision;
        String commandId = "idle-farm-" + UUID.nameUUIDFromBytes(
                material.getBytes(StandardCharsets.UTF_8));
        LinkedHashMap<String, String> arguments = new LinkedHashMap<>();
        arguments.put("area", observation.area());
        arguments.put("areaFingerprint", observation.areaFingerprint());
        arguments.put("automaticFarm", "true");
        arguments.put("automaticFarmWorkKey", admission.workKey());
        arguments.put("automaticFarmRevision", Long.toString(revision));
        try {
            EntityCore.Submission submission = core.submit(
                    new EntityCore.Command(
                            commandId, "Entity automatic idle", "farm", arguments),
                    now);
            if (submission.duplicate()) {
                throw new IllegalStateException(
                        "automatic farm command identity unexpectedly already exists");
            }
            automaticFarmMissionId = submission.mission().id();
            automaticFarmArea = observation.area();
            automaticFarmWorkKey = admission.workKey();
            logger.info("Automatic managed farm admitted area={} mission={} maturity={}",
                    automaticFarmArea, shortId(automaticFarmMissionId),
                    new java.util.TreeMap<>(observation.matureCounts()));
        } catch (IOException | RuntimeException failure) {
            try {
                farmAutomation.abandonUnsubmittedAttempt(
                        observation.area(), observation.areaFingerprint(),
                        admission.workKey());
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private Optional<Mission> automaticFarmMission() {
        if (!automaticFarmMissionId.isBlank()) {
            Optional<Mission> exact = core.mission(automaticFarmMissionId);
            if (exact.isPresent()) return exact;
        }
        Optional<Mission> restored = core.missions().stream()
                .filter(EntityRuntime::isAutomaticFarmMission)
                .filter(mission -> !mission.state().terminal())
                .max(Comparator.comparingLong(Mission::createdAtMillis));
        restored.ifPresent(mission -> {
            automaticFarmMissionId = mission.id();
            automaticFarmArea = mission.parameters().getOrDefault("area", "");
            automaticFarmWorkKey = mission.parameters().getOrDefault(
                    "automaticFarmWorkKey", "");
        });
        return restored;
    }

    private static boolean isAutomaticFarmMission(Mission mission) {
        return mission != null && mission.kind().equals("farm")
                && Boolean.parseBoolean(
                mission.parameters().getOrDefault("automaticFarm", "false"));
    }

    private void cancelAutomaticFarmMission(String reason, long now) throws IOException {
        Optional<Mission> mission = automaticFarmMission();
        if (mission.isPresent() && !mission.orElseThrow().state().terminal()) {
            core.cancel(mission.orElseThrow().id(), reason, now);
            autonomy.cancelMission(
                    mission.orElseThrow().id(), core.arbiter().epoch(), reason);
        }
        clearAutomaticFarmTracking();
    }

    private void clearAutomaticFarmTracking() {
        automaticFarmMissionId = "";
        automaticFarmArea = "";
        automaticFarmWorkKey = "";
        automaticFarmObservedAt = Long.MIN_VALUE;
    }

    private record AutomaticFarmAdmission(
            AutonomyExecutor.ManagedFarmAutomaticObservation observation,
            boolean eligible,
            String workKey,
            String detail,
            boolean requiresStorageInspection) {
        private AutomaticFarmAdmission {
            workKey = Objects.requireNonNullElse(workKey, "");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    private void cancelAutomaticIdleWork(String reason, long now) throws IOException {
        cancelAutomaticIdleWork(reason, now, false);
    }

    private void cancelAutomaticIdleWork(String reason, long now, boolean preserveRecovery) throws IOException {
        IdleWorkCoordinator.Job job = idleWork.active();
        // A real owner command fences even a dormant failed automatic job.
        // Connection lifecycle keeps exact world-scoped recovery evidence, but
        // still resets admission and requires fresh authenticated context.
        if (!preserveRecovery) autonomy.clearAutomaticStockRecovery();
        if (!automaticFarmMissionId.isBlank()
                || automaticFarmMission().isPresent()) {
            cancelAutomaticFarmMission(reason, now);
        }
        if (idleWork.ownsWork() && job != IdleWorkCoordinator.Job.FARM) {
            boolean cancelSleep = job == IdleWorkCoordinator.Job.SLEEP;
            if (preserveRecovery) autonomy.interruptIdleHomeEconomy(now, cancelSleep);
            else autonomy.ownerStopHomeEconomy(now, cancelSleep);
            releaseHomeEconomyOwnership(reason);
        }
        idleWork.reset(reason);
    }

    private void saveIdleEnabled(boolean enabled) throws IOException {
        var current = idleSettings.settings();
        if (!idleSettings.bound() && !enabled) return;
        idleSettings.save(new IdleSettingsStore.Settings(enabled, current.delayMillis(), current.allowOffline(),
                current.surplusTargets(), current.revision()));
    }

    private void requireBlueprintWorld(JsonObject args) {
        if (!worldScopeGate.verified() || worldScopeGate.verifiedScope()
                .filter(scope -> scope.key().equals(text(args,"worldId",""))).isEmpty())
            throw new IllegalArgumentException("Building requires the authenticated current world");
    }

    private void handleBlueprint(IncomingCommand command) throws IOException {
        JsonObject args=command.arguments(); requireBlueprintWorld(args);
        String operation=text(args,"operation","status");
        if (operation.equals("list")) {
            sendBlueprintCatalog(command.id(), "Choose a design above, or use Tab after /e build select. Nothing has been built.");
            return;
        }
        if (operation.equals("import")) {
            if (blueprintImportPending) throw new IllegalArgumentException("A blueprint import is already running");
            blueprintImportPending=true;
            String source=text(args,"source","");
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return blueprints.catalog().importSource(source); }
                catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            }).whenComplete((design,error) -> client.execute(() -> {
                blueprintImportPending=false;
                if (blueprintClosed) return;
                if (error!=null) sendResult(command.id(),false,"blocked",null,
                        "Import failed: "+Objects.requireNonNullElse(error.getCause(),error).getMessage());
                else sendBlueprintCatalog(command.id(), "Imported "+design.name()+" as "
                        +design.id()+". Click its name above to prepare a preview; nothing has been built.");
            }));
            return;
        }
        if (!Set.of("status","show","materials","resume").contains(operation)
                && core.activeMission().filter(m->!m.state().terminal()).isPresent())
            throw new IllegalArgumentException("Stop or finish the current mission before changing the build preview");
        var p=blueprints.project();
        if(operation.equals("resume")) {
            var policy=requireBlueprintPolicy(args);
            if(p!=null&&!p.dimension().equals(text(args,"dimension","")))
                throw new IllegalArgumentException("Resume the saved project in its selected dimension");
            p=blueprints.resume(command.requestedBy(),policy.revision(),policy.digest());
        }
        if (operation.equals("clear") || operation.equals("clear_confirm")) {
            if (!operation.equals("clear_confirm") && !booleanValue(args.get("confirmed"),false)) {
                sendResult(command.id(),true,"completed",null,
                        "Forget the saved build? /e build clear confirm. Existing blocks stay in the world."); return;
            }
            blueprints.clear(); sendResult(command.id(),true,"completed",null,"Saved build cleared; no blocks removed."); return;
        }
        if (Set.of("select","here","rotate").contains(operation)) {
            if (p==null && !operation.equals("select"))
                throw new IllegalArgumentException("Select a design first: /e build select starter-shelter");
            String id=operation.equals("select") ? text(args,"design","") : p.designId();
            String dimension=text(args,"dimension","");
            net.minecraft.util.math.BlockPos anchor=new net.minecraft.util.math.BlockPos(
                    args.get("x").getAsInt(),args.get("y").getAsInt(),args.get("z").getAsInt());
            int rotation=operation.equals("select") ? switch(text(args,"front","north")) {
                case "east" -> 1; case "south" -> 2; case "west" -> 3; default -> 0;
            } : p.rotation();
            if (operation.equals("rotate")) {
                anchor=p.anchor(); dimension=p.dimension();
                rotation=(rotation+args.get("rotation").getAsInt()/90)%4;
            }
            p=blueprints.select(client,id,dimension,anchor,rotation);
        }
        if (!Set.of("status","show","materials","select","here","rotate","resume").contains(operation))
            throw new IllegalArgumentException("Unknown build operation "+operation);
        JsonObject result=new JsonObject(); result.addProperty("type","result");
        result.addProperty("id",command.id()); result.addProperty("ok",true);
        result.addProperty("phase","completed");
        String detail = operation.equals("status") ? blueprints.statusSummary(client) : blueprints.summary(client);
        if (operation.equals("status")) detail += core.activeMission().filter(m -> m.kind().equals("build"))
                .map(m -> " Work: " + m.state() + ", " + m.phase() + ".").orElse(" No build currently owns the mission; /e build resume continues a retained project.");
        result.addProperty("message",BridgeResultPolicy.boundedMessage(detail));
        if (p!=null && Set.of("show", "select", "here", "rotate", "resume").contains(operation)) result.add("blueprintPreview",p.preview());
        if (result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>config.bridge().maxFrameBytes())
            throw new IllegalArgumentException("Preview exceeds the bridge size limit; choose a smaller design");
        bridge.send(result);
    }

    private ProtectedAreaClientState.PropertyRegionsObservation requireBlueprintPolicy(JsonObject args) {
        var policy=protectedAreas.observePropertyRegions();
        if(!policy.available()||!args.has("policyRevision")||!args.has("policyDigest")
                ||args.get("policyRevision").getAsLong()!=policy.revision()
                ||!args.get("policyDigest").getAsString().equals(policy.digest()))
            throw new IllegalArgumentException("Build requires the current authenticated property policy");
        return policy;
    }

    private com.google.gson.JsonArray blueprintCatalogFrame() {
        com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
        blueprintCatalogIds.forEach(ids::add);
        return ids;
    }

    private void sendBlueprintCatalog(String commandId, String message) {
        blueprintCatalogIds = blueprints.catalog().list();
        JsonObject result = new JsonObject();
        result.addProperty("type", "result");
        result.addProperty("id", commandId);
        result.addProperty("ok", true);
        result.addProperty("phase", "completed");
        result.addProperty("message", BridgeResultPolicy.boundedMessage(message));
        result.add("blueprintCatalog", blueprintCatalogFrame());
        bridge.send(result);
    }

    private void handleIdleCommand(IncomingCommand command, long now) throws IOException {
        JsonObject args = command.arguments();
        String operation = text(args, "operation", "status");
        if (operation.equals("status")) {
            sendResult(command.id(), true, "completed", null, idleStatus(now));
            return;
        }
        if (!worldScopeGate.verified() || !idleSettings.bound()
                || worldScopeGate.verifiedScope().filter(scope -> scope.key().equals(text(args, "worldId", ""))).isEmpty())
            throw new IllegalArgumentException("Idle preferences require the authenticated current world");
        var current = idleSettings.settings();
        boolean enabled = current.enabled(), offline = current.allowOffline();
        long delay = current.delayMillis();
        Map<String, Integer> targets = new LinkedHashMap<>(current.surplusTargets());
        switch (operation) {
            case "set", "offline" -> {
                var value = args.get("enabled");
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                    throw new IllegalArgumentException("Idle requires enabled=true or false");
                if (operation.equals("set")) enabled = value.getAsBoolean();
                else offline = value.getAsBoolean();
            }
            case "delay" -> delay = exactIdleInteger(args, "delayMillis");
            case "target" -> {
                String category = text(args, "category", "");
                if (!IdleSettingsStore.CATEGORIES.contains(category))
                    throw new IllegalArgumentException("Idle target must be food, fuel, logs or iron");
                targets.put(category, Math.toIntExact(exactIdleInteger(args, "count")));
            }
            default -> throw new IllegalArgumentException("Unknown idle preference operation");
        }
        // Validate and persist before changing authority; malformed settings never stop work.
        var next = new IdleSettingsStore.Settings(enabled, delay, offline, targets, current.revision());
        boolean changed = idleSettings.save(next);
        if (changed || operation.equals("set")) {
            cancelAutomaticIdleWork(enabled ? "Fresh quiet delay after idle preference change" : "OFF", now);
            if (operation.equals("set") && enabled) operatorStop.resumeForExplicitWork("idle on");
        }
        sendResult(command.id(), true, "completed", null, idleStatus(now));
    }

    private void handleFarmPolicy(IncomingCommand command, long now) throws IOException {
        JsonObject args = command.arguments();
        String operation = text(args, "operation", "status")
                .trim().toLowerCase(Locale.ROOT);
        if (!worldScopeGate.verified() || !farmAutomation.bound()
                || worldScopeGate.verifiedScope().filter(scope ->
                scope.key().equals(text(args, "worldId", ""))).isEmpty()) {
            throw new IllegalArgumentException(
                    "Managed-farm preferences require the authenticated current world");
        }
        if (operation.equals("status")) {
            refreshAutomaticFarmObservations(now, true);
            sendResult(command.id(), true, "completed", null,
                    managedFarmStatus(text(args, "area", ""), now));
            return;
        }
        if (!operation.equals("auto")) {
            throw new IllegalArgumentException(
                    "Farm preference operation must be auto or status");
        }
        String areaName = text(args, "area", "")
                .trim().toLowerCase(Locale.ROOT);
        String fingerprint = text(args, "areaFingerprint", "").trim();
        ProtectedAreaClientState.NamedResourceAreaObservation named =
                protectedAreas.observeNamedResourceArea(
                        dev.entity.core.stewardship.ProtectedAreaPolicy.AreaKind.HARVESTING,
                        areaName);
        if (!named.available() || named.area().isEmpty()) {
            throw new IllegalArgumentException(named.detail());
        }
        String exactFingerprint = ManagedFarmPolicy.areaFingerprint(
                named.area().orElseThrow());
        if (!exactFingerprint.equals(fingerprint)) {
            throw new IllegalArgumentException(
                    "Farm area changed before this preference reached Entity; issue the command again");
        }
        JsonElement enabledValue = args.get("enabled");
        if (enabledValue == null || !enabledValue.isJsonPrimitive()
                || !enabledValue.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Farm auto requires enabled=true or false");
        }
        boolean enabled = enabledValue.getAsBoolean();
        boolean changed = farmAutomation.setEnabled(
                areaName, exactFingerprint, enabled);
        automaticFarmObservedAt = Long.MIN_VALUE;
        if (changed || !enabled) {
            cancelAutomaticIdleWork(
                    "Fresh quiet delay after automatic farm preference change", now);
        }
        refreshAutomaticFarmObservations(now, true);
        String global = idleSettings.settings().enabled() && !operatorStop.stopped()
                ? "global Idle is ON" : "global Idle is OFF";
        sendResult(command.id(), true, "completed", null,
                "Farm '" + areaName + "' automatic admission is "
                        + (enabled ? "ON" : "OFF") + " for this world; " + global
                        + ". Future passes still require the quiet delay, daylight, "
                        + "observed maturity, exact seed supply and usable Home storage. "
                        + managedFarmStatus(areaName, now));
    }

    private String managedFarmStatus(String requestedArea, long now) {
        String only = Objects.requireNonNullElse(requestedArea, "")
                .trim().toLowerCase(Locale.ROOT);
        ProtectedAreaClientState.ResourceAreasObservation areas =
                protectedAreas.observeResourceAreas(
                        dev.entity.core.stewardship.ProtectedAreaPolicy.AreaKind.HARVESTING);
        if (!areas.available()) return "Farm status unavailable: " + areas.detail();
        Map<String, AutonomyExecutor.ManagedFarmAutomaticObservation> observations =
                automaticFarmObservations.stream().collect(
                        java.util.stream.Collectors.toMap(
                                AutonomyExecutor.ManagedFarmAutomaticObservation::area,
                                observation -> observation,
                                (left, right) -> left,
                                LinkedHashMap::new));
        ArrayList<String> lines = new ArrayList<>();
        for (dev.entity.core.stewardship.ProtectedAreaPolicy.Area area
                : areas.areas().stream().sorted(Comparator.comparing(
                dev.entity.core.stewardship.ProtectedAreaPolicy.Area::name)).toList()) {
            if (!only.isBlank() && !area.name().equals(only)) continue;
            String exactFingerprint = ManagedFarmPolicy.areaFingerprint(area);
            String savedFingerprint = farmAutomation.savedFingerprint(area.name());
            boolean enabled = exactFingerprint.equals(savedFingerprint);
            boolean stale = !savedFingerprint.isBlank() && !enabled;
            AutonomyExecutor.ManagedFarmAutomaticObservation observation =
                    observations.get(area.name());
            if (observation == null) {
                observation = autonomy.automaticFarmObservation(
                        area.name(), exactFingerprint, now);
            }
            Mission pass = core.missions().stream()
                    .filter(mission -> mission.kind().equals("farm"))
                    .filter(mission -> area.name().equals(
                            mission.parameters().getOrDefault("area", "")))
                    .filter(mission -> !mission.state().terminal())
                    .max(Comparator.comparingLong(Mission::createdAtMillis))
                    .orElse(null);
            String running = pass == null ? "not running"
                    : (isAutomaticFarmMission(pass) ? "automatic " : "manual ")
                    + pass.state().name().toLowerCase(Locale.ROOT)
                    + " [" + shortId(pass.id()) + "]";
            String auto = stale ? "STALE (area changed)" : enabled ? "ON" : "OFF";
            String gate = enabled && !idleSettings.settings().enabled()
                    ? "; dormant because global Idle is OFF" : "";
            lines.add("Farm '" + area.name() + "': " + area.width() + 'x'
                    + area.depth() + " in " + area.dimension()
                    + "; auto=" + auto + gate
                    + "; pass=" + running
                    + "; mature=" + new java.util.TreeMap<>(
                    observation.matureCounts())
                    + "; seeds carried/Home=" + new java.util.TreeMap<>(
                    observation.seedSupply())
                    + "; Home=" + (observation.homeReady() ? "ready" : "missing")
                    + "; storage=" + (observation.storageFresh()
                    ? observation.storageCapacity() ? "fresh with room" : "fresh but full"
                    : observation.storageConfigured() ? "needs inspection" : "missing")
                    + "; " + observation.detail());
        }
        if (lines.isEmpty()) {
            return only.isBlank()
                    ? "No managed farm is selected. Use /e area begin harvesting <name>."
                    : "No managed farm named '" + only + "' is selected.";
        }
        return String.join(" | ", lines);
    }

    private static long exactIdleInteger(JsonObject args, String key) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Idle requires integer " + key);
        try { return value.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Idle requires integer " + key); }
    }

    private String idleStatus(long now) {
        var settings = idleSettings.settings();
        return "Idle: " + (settings.enabled() ? "ON" : "OFF") + "; " + idleWork.detail()
                + "; delay=" + settings.delayMillis() / 60_000L + "m"
                + (settings.enabled() && !idleWork.ownsWork()
                        ? "; quiet remaining=" + (idleWork.remainingMillis(idleCoordinatorSettings(), now) + 999L) / 1000L + "s" : "")
                + "; offline=" + (settings.allowOffline() ? "on" : "off")
                + "; stored targets=" + new java.util.TreeMap<>(settings.surplusTargets());
    }

    private JsonObject idleWorkFrame(long now) {
        var settings = idleSettings.settings();
        JsonObject frame = new JsonObject();
        frame.addProperty("enabled", settings.enabled());
        frame.addProperty("job", idleWork.active().name());
        frame.addProperty("detail", idleWork.detail());
        frame.addProperty("remainingMillis", idleWork.remainingMillis(idleCoordinatorSettings(), now));
        frame.addProperty("delayMillis", settings.delayMillis());
        frame.addProperty("allowOffline", settings.allowOffline());
        frame.addProperty("ownerOnline", idleContext != null && idleContext.ownerOnline());
        frame.addProperty("manualWorkPending", idleContext != null && idleContext.manualWorkPending());
        frame.addProperty("contextFresh", idleContext != null && idleContext.fresh(now, idleAuthenticatedAt));
        frame.addProperty("settingsRevision", settings.revision());
        frame.addProperty("automaticFarmCount",
                farmAutomation.state().enabledAreas().size());
        frame.addProperty("automaticFarmMissionId", automaticFarmMissionId);
        frame.addProperty("automaticFarmArea", automaticFarmArea);
        frame.addProperty("automaticFarmWorkKey", automaticFarmWorkKey);
        AutonomyExecutor.ManagedFarmAutomaticObservation farm =
                automaticFarmObservations.isEmpty()
                        ? null : automaticFarmObservations.getFirst();
        if (farm != null) {
            frame.addProperty("farmObservedArea", farm.area());
            frame.addProperty("farmMatureTotal", farm.matureTotal());
            frame.addProperty("farmEligible", farm.eligible());
            frame.addProperty("farmObservation", farm.detail());
        }
        return frame;
    }

    private void handleTidy(IncomingCommand command, long now) throws IOException {
        JsonObject args = command.arguments();
        String dimension = text(args, "dimension", "");
        String worldId = text(args, "worldId", "");
        if (client.world == null || !client.world.getRegistryKey().getValue().toString().equals(dimension)
                || bridge.worldIdentity(dimension).filter(worldId::equals).isEmpty()) {
            throw new IllegalArgumentException("Tidy command belongs to another or unverified world/dimension");
        }
        String operation = text(args, "operation", "preview").trim().toLowerCase(Locale.ROOT);
        dev.entity.client.autonomy.MinecraftTidyController.SpotSelection selection = null;
        if (operation.equals("spot_preview") || operation.equals("spot_confirm")) {
            String fingerprint = autonomy.homeAnchor().orElseThrow(() ->
                    new IllegalArgumentException("Establish Home before selecting a disposal spot")).fingerprint();
            var spot = new dev.entity.client.autonomy.policy.TidySettingsStore.DisposalSpot(
                    autonomy.tidyWorldId(), fingerprint, dimension,
                    args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt(),
                    text(args, "blockId", ""), text(args, "blockState", ""),
                    args.get("landingX").getAsDouble(), args.get("landingY").getAsDouble(), args.get("landingZ").getAsDouble());
            selection = new dev.entity.client.autonomy.MinecraftTidyController.SpotSelection(
                    text(args, "nonce", ""), spot, args.get("policyRevision").getAsLong(),
                    text(args, "policyDigest", ""), args.get("expiresAtMillis").getAsLong());
        }
        boolean idle = explicitHomeWorkHasAuthority();
        var result = autonomy.tidyCommand(operation, command.id(), text(args, "item", ""),
                args.has("enabled") && args.get("enabled").getAsBoolean(),
                args.has("includeStorage") && args.get("includeStorage").getAsBoolean(), selection, idle, now);
        if (result.requestedWork()) {
            idleWork.reset("Owner selected tidy work");
            operatorStop.resumeForExplicitWork("tidy " + operation);
            // Ack removes only the bridge's pending delivery, not Paper's exact
            // transient requester. The finite controller sends the terminal result.
            sendAcknowledgement(command.id());
        } else {
            sendResult(command.id(), true, "completed", null, result.detail());
        }
    }

    private void handleHome(IncomingCommand command, long now) throws IOException {
        String operation = text(command.arguments(), "operation", "status")
                .trim().toLowerCase(Locale.ROOT);
        switch (operation) {
            case "status", "show" -> sendResult(
                    command.id(), true, "completed", null, autonomy.homeStatus());
            case "storage_list" -> sendResult(command.id(), true, "completed", null, autonomy.homeStorageStatus());
            case "adopt" -> sendResult(command.id(), true, "completed", null, autonomy.previewHomeAdoption(now));
            case "adopt_confirm" -> {
                String message = HomeMutationAdmission.execute(() -> autonomy.validateHomeAdoption(now),
                        () -> stopForHomeMutation("owner furniture adoption", now),
                        () -> autonomy.confirmHomeAdoption(now));
                sendResult(command.id(), true, "completed", null, message);
            }
            case "storage_remove" -> {
                String storageId = text(command.arguments(), "storageId", "");
                String message = HomeMutationAdmission.execute(
                        () -> autonomy.validateHomeStorageRemoval(storageId),
                        () -> stopForHomeMutation("owner storage removal", now),
                        () -> autonomy.removeHomeStorage(storageId, now));
                sendResult(command.id(), true, "completed", null, message);
            }
            case "bind_preview", "bind_confirm", "storage_preview", "storage_confirm" -> {
                JsonObject args = command.arguments();
                boolean storage = operation.startsWith("storage_");
                HomeEconomySession.AssetRole role = switch (text(args, "role", "")) {
                    case "table" -> HomeEconomySession.AssetRole.CRAFTING_TABLE;
                    case "furnace" -> HomeEconomySession.AssetRole.FURNACE;
                    case "chest" -> HomeEconomySession.AssetRole.CHEST;
                    case "bed" -> HomeEconomySession.AssetRole.BED;
                    default -> throw new IllegalArgumentException("Unknown Home furniture role");
                };
                HomeFurnitureBindingPolicy.ConnectedChest connected = null;
                if (args.has("connectedChest") && args.get("connectedChest").isJsonObject()) {
                    JsonObject half = args.getAsJsonObject("connectedChest");
                    connected = new HomeFurnitureBindingPolicy.ConnectedChest(half.get("x").getAsInt(),
                            half.get("y").getAsInt(), half.get("z").getAsInt(), text(half, "blockId", ""),
                            text(half, "facing", ""), text(half, "type", ""));
                }
                var selected = new HomeFurnitureBindingPolicy.Selection(text(args, "nonce", ""), role,
                        new FieldKitLedger.Position(text(args, "dimension", ""),
                                args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt()),
                        text(args, "blockId", ""), text(args, "facing", ""),
                        text(args, "chestType", role == HomeEconomySession.AssetRole.CHEST ? "single" : ""), connected);
                long revision = args.get("policyRevision").getAsLong();
                String digest = text(args, "policyDigest", "");
                long deadline = args.get("expiresAtMillis").getAsLong();
                String message;
                if (operation.endsWith("_preview")) {
                    message = storage ? autonomy.previewHomeStorage(selected, revision, digest, deadline, now)
                            : autonomy.previewHomeFurnitureBinding(selected, revision, digest, deadline, now);
                } else {
                    message = HomeMutationAdmission.execute(() -> {
                        // Includes durable acquisition as well as exact selection,
                        // property and inventory custody before cancelling work.
                        if (storage) autonomy.validateHomeStorageConfirmation(selected, revision, digest, deadline, now);
                        else autonomy.validateHomeFurnitureConfirmation(selected, revision, digest, deadline, now);
                    }, () -> stopForHomeMutation("owner furniture binding", now),
                            () -> storage ? autonomy.confirmHomeStorage(selected, revision, digest, deadline, now)
                                    : autonomy.confirmHomeFurnitureBinding(selected, revision, digest, deadline, now));
                }
                sendResult(command.id(), true, "completed", null, message);
            }
            case "establish" -> {
                boolean idleAuthority = explicitHomeWorkHasAuthority();
                String message = autonomy.establishHome(idleAuthority, now);
                idleWork.reset("Owner selected Home establishment");
                operatorStop.resumeForExplicitWork("home establish");
                sendResult(command.id(), true, "completed", null,
                        "Home objective persisted; " + message);
            }
            case "setup" -> {
                String message = autonomy.setupHome(explicitHomeWorkHasAuthority(), now);
                idleWork.reset("Owner requested Home provisioning");
                operatorStop.resumeForExplicitWork("home setup");
                sendResult(command.id(), true, "completed", null, message);
            }
            case "set", "set_confirm" -> {
                boolean idleAuthority = explicitHomeWorkHasAuthority();
                String message = operation.equals("set")
                        ? autonomy.setHome(idleAuthority, now)
                        : autonomy.confirmHomeSet(idleAuthority, now);
                sendResult(command.id(), true, "completed", null, message);
            }
            case "clear" -> sendResult(
                    command.id(), true, "completed", null, autonomy.clearHome(now));
            case "clear_confirm" -> {
                String message = autonomy.confirmClearHome(now);
                idleWork.reset("Owner cleared Home");
                sendResult(command.id(), true, "completed", null, message);
            }
            case "sleep" -> {
                String message = requestExplicitHomeSleep(now);
                idleWork.reset("Owner selected Home sleep");
                operatorStop.resumeForExplicitWork("home sleep");
                sendResult(command.id(), true, "completed", null, message);
            }
            case "go" -> {
                HomeEconomySession.HomeAnchor home = autonomy.homeAnchor().orElseThrow(() ->
                        new IllegalArgumentException(
                                "No Home is registered; bring Entity inside and use /e home set. /e help home explains furniture adoption."));
                LinkedHashMap<String, String> arguments = new LinkedHashMap<>();
                arguments.put("x", Integer.toString(home.x()));
                arguments.put("y", Integer.toString(home.y()));
                arguments.put("z", Integer.toString(home.z()));
                arguments.put("dimension", home.dimension());
                arguments.put("range", "3");
                arguments.put("homeGoCommand", "true");
                arguments.putAll(MissionStatusReporter.queueParameters(command.arguments()));
                EntityCore.Submission submission = core.submit(
                        new EntityCore.Command(
                                command.id(),
                                command.requestedBy().isBlank()
                                        ? "owner" : command.requestedBy(),
                                "goto",
                                arguments),
                        now);
                homeGoCommandMissionId = submission.mission().id();
                if (!submission.duplicate()) explicitSleepContinuation.clear();
                if (!submission.duplicate()) cancelAutomaticIdleWork("Owner selected Home travel", now);
                if (!submission.duplicate()) stockCommands.cancelActive("Replaced by home go");
                if (submission.duplicate() && submission.mission().state().terminal()) {
                    Mission terminal = submission.mission();
                    if (missionReporter.delivered(terminal)) {
                        sendAcknowledgement(command.id());
                    } else {
                        sendMissionResult(command.id(), true, terminal,
                                "Home goto already "
                                        + terminal.state().name().toLowerCase(Locale.ROOT));
                    }
                    return;
                }
                operatorStop.resumeForExplicitWork("home go");
                sendResult(
                        command.id(), true, "persisted", submission.mission().id(),
                        submission.duplicate()
                                ? "Home goto mission already persisted"
                                : "Accepted explicit user Home goto mission", arguments);
            }
            default -> throw new IllegalArgumentException(
                    "Use /e help home for set, adopt, setup, bind, storage, go, sleep, show and clear.");
        }
    }

    private void stopForHomeMutation(String reason, long now) throws IOException {
        explicitSleepContinuation.clear();
        cancelAutomaticIdleWork(reason, now);
        Optional<Mission> active = core.activeMission();
        if (active.isPresent()) {
            core.cancel(active.get().id(), reason, now);
            autonomy.cancelMission(active.get().id(), core.arbiter().epoch(), reason);
        }
        operatorStop.stop(reason);
        long epoch = core.emergencyStop(reason, now);
        homeEconomyLease = null;
        baritone.cancel(epoch, reason);
        autonomy.releaseControls(reason, clientTick);
        body.cancelControls(epoch, reason);
        actuators.neutralizeAll(reason);
    }

    private String sleepReturnIdentity() {
        return worldScopeGate.verifiedScope().map(scope -> scope.key() + ":"
                + (client.world == null ? "" : client.world.getRegistryKey().getValue()) + ":"
                + (client.player == null ? "" : client.player.getUuidAsString()) + ":"
                + autonomy.homeSleepIdentity()).orElse("");
    }

    private String requestExplicitHomeSleep(long now) throws IOException {
        rejectLifecycleMutationDuringDeathRecovery("sleep");
        if (!worldScopeGate.verified()) throw new IllegalArgumentException("Sleep requires verified world identity");
        // Read-only admission must precede parking: a missing bed/daytime cannot stop a job.
        autonomy.validateExplicitHomeSleepRequest();
        Mission active = core.activeMission().orElse(null);
        boolean park = ExplicitSleepContinuation.mayPark(active);
        boolean resumeAfterSleep = ExplicitSleepContinuation.mayResumeAfterSleep(active);
        if (active != null && !park && active.state() != MissionState.PAUSED_BY_OWNER) {
            throw new IllegalArgumentException("Current work cannot yield to sleep");
        }
        String pauseReason = resumeAfterSleep ? "temporarily paused for explicit Home sleep"
                : "paused for explicit Home sleep; prior blocked work needs an explicit retry";
        if (park) core.pause(active.id(), pauseReason, now);
        long epoch = core.emergencyStop("explicit Home sleep handoff", now);
        homeEconomyLease = null;
        baritone.cancel(epoch, "explicit Home sleep handoff");
        autonomy.releaseControls("explicit Home sleep handoff", clientTick);
        body.cancelControls(epoch, "explicit Home sleep handoff");
        actuators.neutralizeAll("explicit Home sleep handoff");
        String result = autonomy.requestHomeSleep(true, now);
        if (park) {
            if (resumeAfterSleep) {
                explicitSleepContinuation.retain(core.mission(active.id()).orElseThrow(),
                        operatorStop.snapshot().generation(), sleepReturnIdentity(),
                        autonomy.verifiedHomeSleepCycles());
            } else {
                // Owner sleep parks the failed objective; it is not an implicit work retry.
                explicitSleepContinuation.clear();
                result += "; prior work stays paused until /e retry or /e resume";
            }
            publishMissionState(active.id(), pauseReason);
        }
        return result;
    }

    private void observeExplicitSleepReturn(long now) throws IOException {
        String missionId = explicitSleepContinuation.missionId();
        if (missionId.isBlank()) return;
        boolean neutral = !operatorStop.stopped() && !perceptionWorkActive(now, missionId)
                && decision.layer() != EntityCore.Layer.PROTECTION
                && decision.layer() != EntityCore.Layer.SURVIVAL;
        if (explicitSleepContinuation.ready(core.activeMission().orElse(null),
                operatorStop.snapshot().generation(), sleepReturnIdentity(),
                autonomy.verifiedHomeSleepCycles(), autonomy.homeSleepRequested(), neutral)) {
            core.resume(missionId, now);
            explicitSleepContinuation.clear();
            publishMissionState(missionId, "resumed same mission after verified Home sleep");
        }
    }

    private void handleProtection(IncomingCommand command) throws IOException {
        JsonObject args = command.arguments();
        String operation = text(args, "operation", "set").toLowerCase(Locale.ROOT);
        ProtectionPolicy.Settings current = core.protectionSettings();
        if (operation.equals("status")) {
            sendResult(command.id(), true, "completed", null, protectionText(current));
            return;
        }

        String requestedMode = text(args, "mode", "").toLowerCase(Locale.ROOT);
        if (!requestedMode.isBlank() && !requestedMode.equals("player")) {
            sendResult(command.id(), false, "blocked", null,
                    "Oracle mode is not installed in this client milestone; Entity remains in player mode");
            return;
        }

        boolean protectSelf = current.protectSelf();
        boolean protectOwner = current.protectOwner();
        if (args.has("protectSelf")) protectSelf = args.get("protectSelf").getAsBoolean();
        if (args.has("protectOwner")) protectOwner = args.get("protectOwner").getAsBoolean();
        String scope = text(args, "scope", "").toLowerCase(Locale.ROOT);
        boolean enabled = booleanValue(args.get("enabled"), true);
        if (scope.equals("self")) protectSelf = enabled;
        if (scope.equals("owner") || scope.equals("me")) protectOwner = enabled;
        String ownerScope = scope;
        if (ownerScope.isBlank() && args.has("protectOwner") && protectOwner) {
            // Compatibility with older clients that sent the boolean without
            // the explicit owner/me scope.
            ownerScope = "owner";
        }
        String ownerName = ProtectionPolicy.resolveOwnerName(
                current.ownerName(),
                command.requestedBy(),
                ownerScope,
                enabled,
                text(args, "ownerName", ""));
        ProtectionPolicy.CombatMode combatMode = current.combatMode();
        if (args.has("combatMode")) {
            try {
                combatMode = ProtectionPolicy.CombatMode.parse(args.get("combatMode").getAsString());
            } catch (IllegalArgumentException invalidMode) {
                throw new IllegalArgumentException(
                        "combat mode must be avoid, defensive, or aggressive");
            }
        }
        ProtectionPolicy.Settings updated = new ProtectionPolicy.Settings(
                protectSelf,
                protectOwner,
                ownerName,
                current.maximumEngageDistance(),
                current.retreatHealthFraction(),
                combatMode);
        protectionSettingsStore.save(updated);
        core.updateProtection(updated);
        // Any protection-policy mutation is an explicit lifecycle boundary. Suppression and its
        // one-tick exact adapter handoff were proven under the old stance/scope and cannot survive
        // DEFENSIVE->AGGRESSIVE or disable/re-enable as inherited combat authority.
        soleTargetSuppression.clear();
        clearDistantThreatDisengagement();
        protectionReactivation.clear();
        baritone.clearProtectionTraversalScope();
        sendResult(command.id(), true, "completed", null, protectionText(updated));
    }

    /** The same exact boundary applies to commands and Paper's reconnect tombstones. */
    private void cancelScopedMission(Mission mission, String reason, long now) throws IOException {
        boolean controlled = isControlledMission(mission.id())
                && decision.layer() == EntityCore.Layer.MISSION
                && core.activeMission().map(active -> active.id().equals(mission.id())).orElse(false);
        core.cancel(mission.id(), reason, now);
        if (explicitSleepContinuation.missionId().equals(mission.id())) explicitSleepContinuation.clear();
        if (isAutomaticFarmMission(mission)) clearAutomaticFarmTracking();
        acknowledgeDeathLoopAfterCommittedLifecycle(mission.id(), now);
        if (controlled) {
            long epoch = core.arbiter().epoch();
            autonomy.cancelMission(mission.id(), epoch, reason);
            body.cancelControls(epoch, reason);
        } else {
            autonomy.retireCancelledMissionPlan(mission.id(), reason);
        }
    }

    private Mission lifecycleMission(IncomingCommand command) {
        return lifecycleMissionIfPresent(command)
                .orElseThrow(() -> new IllegalArgumentException("There is no active mission"));
    }

    private boolean explicitHomeWorkHasAuthority() {
        // A retained build is shown as WAITING/PAUSED for resume, not as a body
        // owner. Admission and core dispatch share that distinction. The Stop
        // latch is released by callers only after the requested action accepts.
        return previousLayer != EntityCore.Layer.SURVIVAL
                && previousLayer != EntityCore.Layer.PROTECTION
                && core.canYieldToExternalWork()
                && deathMissionId.isBlank();
    }

    private Optional<Mission> lifecycleMissionIfPresent(IncomingCommand command) {
        String requestedId = text(command.arguments(), "missionId", "").trim();
        if (requestedId.isBlank()) return core.activeMission();
        return core.mission(requestedId);
    }

    private boolean isControlledMission(String missionId) {
        return decision != null
                && decision.mission() != null
                && decision.mission().id().equals(missionId)
                && decision.lease().isPresent();
    }

    private String statusText() {
        String connection = bridge.connected()
                ? "bridge=connected(v" + bridge.negotiatedProtocol() + ")"
                : "bridge=offline";
        connection += ", build=" + bridge.buildIdentity().display();
        connection += ", perception=" + perception.mode();
        connection += ", idle=" + (idleSettings.settings().enabled() ? idleWork.detail() : "OFF");
        if (!bridge.lastHandshakeFailure().isBlank()) {
            connection += ", buildGate=" + bridge.lastHandshakeFailure();
        }
        String homeSafety = autonomy.homeSettingsSafetyStatus();
        String controller = currentControllerStatus();
        String home = autonomy.homeAnchor()
                .map(anchor -> "home=" + anchor.dimension() + '@'
                        + anchor.x() + ',' + anchor.y() + ',' + anchor.z())
                .orElse("home=none");
        Optional<Mission> active = core.activeMission();
        if (active.isEmpty()) {
            String status = connection + ", controller=" + controller + ", " + home
                    + ", mission=none, " + platform.publishedStatus();
            if (!homeSafety.isBlank()) status = homeSafety + "; " + status;
            return BridgeResultPolicy.boundedMessage(status);
        }
        Mission mission = active.get();
        String status = connection + ", controller=" + controller + ", " + home
                + ", mission=" + shortId(mission.id()) + ", task=" + mission.kind()
                + ", state=" + mission.state().name().toLowerCase(Locale.ROOT)
                + ", phase=" + mission.phase() + ", " + platform.publishedStatus();
        if (mission.parameters().containsKey("batchItems")) {
            status += "; " + autonomy.planSummary(mission.id());
        }
        if (!homeSafety.isBlank()) status = homeSafety + "; " + status;
        return BridgeResultPolicy.boundedMessage(status);
    }

    private String currentControllerStatus() {
        if (operatorStop.stopped()) return "operator-stopped";
        if (previousLayer == EntityCore.Layer.SURVIVAL) return "survival";
        if (previousLayer == EntityCore.Layer.PROTECTION) return "protection";
        if (previousLayer == EntityCore.Layer.MISSION) return "mission";
        if (homeEconomyLease != null
                || platform.publishedStatus().toLowerCase(Locale.ROOT).startsWith("home:")) {
            return "home";
        }
        return "idle";
    }

    private String whyText(IncomingCommand command) {
        String missionId = text(command.arguments(), "missionId", "");
        String trace = core.trace().explainLatest();
        if (missionId.isBlank()) return BridgeResultPolicy.boundedMessage(
                "Current activity: " + currentControllerStatus() + "; " + platform.publishedStatus()
                        + "; " + idleStatus(System.currentTimeMillis())
                        + ". Use /e home status, /e tidy status or /e queue status for that activity; /e stop stops all work.");
        return BridgeResultPolicy.boundedMessage(autonomy.planSummary(missionId) + "; " + trace);
    }

    private static String protectionText(ProtectionPolicy.Settings settings) {
        return "protect-self=" + (settings.protectSelf() ? "on" : "off")
                + ", protect-me=" + (settings.protectOwner() ? "on" : "off")
                + ", combat=" + settings.combatMode().name().toLowerCase(Locale.ROOT)
                + "; environmental survival is always on";
    }

    private boolean sendResult(
            String commandId,
            boolean ok,
            String phase,
            String missionId,
            String message) {
        return sendResult(commandId, ok, phase, missionId, message, Map.of());
    }

    private boolean sendResult(String commandId, boolean ok, String phase,
                               String missionId, String message, Map<String, String> correlation) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "result");
        result.addProperty("id", commandId);
        result.addProperty("ok", ok);
        result.addProperty("phase", phase);
        if (missionId != null) result.addProperty("missionId", missionId);
        result.addProperty("message", BridgeResultPolicy.boundedMessage(message));
        MissionStatusReporter.appendQueueCorrelation(result, correlation);
        return bridge.send(result);
    }

    private void sendMissionResult(
            String commandId,
            boolean ok,
            Mission mission,
            String message) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "result");
        result.addProperty("id", commandId);
        result.addProperty("ok", ok);
        result.addProperty("phase", mission.phase());
        result.addProperty("status", MissionStatusReporter.wireStatus(mission.state()));
        result.addProperty("missionId", mission.id());
        MissionStatusReporter.sequence(mission).ifPresent(value -> result.addProperty("sequence", value));
        MissionStatusReporter.appendQueueCorrelation(result, mission.parameters());
        result.addProperty("message", BridgeResultPolicy.boundedMessage(message));
        if (bridge.send(result)) missionReporter.markDelivered(mission);
    }

    private void executeStockRun(IncomingCommand command, long now) throws IOException {
        Map<String, String> metadata = MissionStatusReporter.queueParameters(command.arguments());
        FiniteStockCommands.QueueTag tag = metadata.isEmpty() ? FiniteStockCommands.QueueTag.none()
                : new FiniteStockCommands.QueueTag(metadata.get("queueId"),
                        Long.parseLong(metadata.get("queueGeneration")),
                        Integer.parseInt(metadata.get("queueStep")), Integer.parseInt(metadata.get("queueAttempt")));
        boolean duplicate = stockCommands.find(command.id()) != null;
        if (!duplicate) stockCommands.cancelActive("Replaced by a new Stock command");
        stockCommands.prepare(command.id(), command.requestedBy(), tag, autonomy.stockCommandObservation(), now);
        if (!duplicate) {
            try {
                cancelAutomaticIdleWork("Owner selected Stock run", now);
                String detail = autonomy.requestStockRun(now);
                stockCommands.started(command.id(), detail);
                operatorStop.resumeForExplicitWork("stock run");
            } catch (IllegalArgumentException rejected) {
                stockCommands.rejected(command.id(), rejected.getMessage());
            } catch (IOException failedReceipt) {
                // No actuator runs inside requestStockRun. Revoke its in-memory
                // request before returning a persistence failure to the bridge.
                autonomy.ownerStopHomeEconomy(now);
                throw failedReceipt;
            }
        }
        flushStockCommandResults();
    }

    private void flushStockCommandResults() throws IOException {
        if (!worldScopeGate.verified()) return;
        for (FiniteStockCommands.Entry entry : stockCommands.pendingReplies()) {
            if (entry.state() == FiniteStockCommands.State.PREPARED) continue;
            FiniteStockCommands.QueueTag tag = entry.queue();
            Map<String, String> metadata = tag.id().isBlank() ? Map.of() : Map.of(
                    "queueId", tag.id(), "queueGeneration", Long.toString(tag.generation()),
                    "queueStep", Integer.toString(tag.step()), "queueAttempt", Integer.toString(tag.attempt()));
            boolean ok = entry.state() != FiniteStockCommands.State.BLOCKED;
            if (sendResult(entry.id(), ok, entry.state().name().toLowerCase(Locale.ROOT),
                    null, entry.detail(), metadata)) stockCommands.reported(entry);
        }
    }

    private void publishHomeGoCommandState() {
        if (!worldScopeGate.verified() || homeGoCommandMissionId.isBlank()) return;
        core.mission(homeGoCommandMissionId).ifPresent(mission -> {
            if (!missionReporter.delivered(mission))
                sendMissionResult(mission.commandId(), true, mission, mission.phase());
        });
    }

    private void publishMissionState(String missionId, String reason) {
        core.mission(missionId).ifPresent(mission ->
                missionReporter.publish(mission, reason, bridge::send));
    }

    private void sendAcknowledgement(String commandId) {
        JsonObject acknowledgement = new JsonObject();
        acknowledgement.addProperty("type", "ack");
        acknowledgement.addProperty("id", commandId);
        bridge.send(acknowledgement);
    }

    private EntityCore.TickDecision telemetryDecision(EntityCore.TickDecision source) {
        if (source == null || source.mission() == null) return source;
        Optional<Mission> live = core.mission(source.mission().id());
        if (live.isEmpty()) return source;
        Mission mission = live.get();
        if (mission.state().terminal()) {
            return new EntityCore.TickDecision(
                    EntityCore.Layer.IDLE, "IDLE", "mission " + mission.phase(),
                    null, Optional.empty(), core.arbiter().epoch());
        }
        if (source.layer() == EntityCore.Layer.MISSION && mission.state() != MissionState.RUNNING) {
            return new EntityCore.TickDecision(
                    EntityCore.Layer.WAITING,
                    mission.state().name(),
                    mission.lastError().isBlank() ? mission.pauseReason() : mission.lastError(),
                    mission, Optional.empty(), core.arbiter().epoch());
        }
        return new EntityCore.TickDecision(
                source.layer(), source.action(), source.reason(), mission,
                source.lease(), source.controlEpoch(), source.protectionThreat());
    }

    /**
     * Feeds the pure detector from one coherent end-of-tick view.  Observation
     * failures remain diagnostic-only, but a proved invariant is no longer a
     * warning: its incident is retained first, then the active circuit breaker
     * neutralizes every actuator and durably parks the exact work.
     */
    private void captureBehaviorInvariants(long now) {
        try {
            BehaviorInvariantMonitor.Observation observation = behaviorInvariantObservation(now);
            if (observation == null) {
                pruneRecentInvariantViolations(now);
                invariantState = behaviorInvariants.stateSnapshot();
                flushPendingInvariantIncidents(now);
                return;
            }

            List<BehaviorInvariantMonitor.Violation> violations = new ArrayList<>(
                    behaviorInvariants.observe(observation));
            invariantState = behaviorInvariants.stateSnapshot();
            BehaviorInvariantMonitor.Violation tripViolation = null;
            BehaviorCircuitBreaker.Decision tripDecision = null;
            for (BehaviorInvariantMonitor.Violation violation : violations) {
                recentInvariantViolations.addLast(violation);
                while (recentInvariantViolations.size() > MAX_RECENT_INVARIANT_VIOLATIONS) {
                    recentInvariantViolations.removeFirst();
                }

                String reason = violation.type().name().toLowerCase(Locale.ROOT)
                        + ": " + violation.evidence();
                if (invariantIncidentOutbox.retain(violation, now)) {
                    logger.warn("Entity behavior invariant: {}", reason);
                }
                BehaviorCircuitBreaker.Decision candidate =
                        behaviorCircuitBreaker.observe(violation);
                if (candidate.trip() && tripDecision == null) {
                    tripViolation = violation;
                    tripDecision = candidate;
                }
            }
            // Capture the pre-neutralization state in the one-shot incident.
            flushPendingInvariantIncidents(now);
            if (tripDecision != null) {
                tripBehaviorCircuitBreaker(tripViolation, tripDecision, now);
            }
            pruneRecentInvariantViolations(now);
        } catch (RuntimeException error) {
            if (lastInvariantWarningAt == 0L
                    || now < lastInvariantWarningAt
                    || now - lastInvariantWarningAt >= INVARIANT_INCIDENT_COOLDOWN_MILLIS) {
                lastInvariantWarningAt = now;
                logger.warn("Entity behavior invariant probe failed safely: {}", error.getMessage());
            }
        }
    }

    private void tripBehaviorCircuitBreaker(
            BehaviorInvariantMonitor.Violation violation,
            BehaviorCircuitBreaker.Decision breaker,
            long now) {
        String reason = breaker.reason();
        Mission activeMission = core.activeMission().orElse(null);
        String operationId = baritone.snapshot().missionId();
        boolean homeOwned = autonomy.ownsHomeSafetyOperation(operationId)
                || autonomy.ownsHomeSafetyOperation(violation.missionId());
        boolean preserveObjective = BehaviorCircuitBreaker.preservesUnrelatedMission(
                breaker.scope(), activeMission == null ? "" : activeMission.kind());
        if (breaker.scope() == BehaviorCircuitBreaker.Scope.COMBAT_AND_BODY
                && latestRawWorld != null) {
            behaviorCircuitBreaker.fenceCombatBody(latestRawWorld.threats().stream()
                    .map(WorldSnapshot.Threat::entityId).toList());
        }

        long epoch = core.emergencyStop(reason, now);
        homeEconomyLease = null;
        baritone.cancel(epoch, reason);
        autonomy.releaseControls(reason, clientTick);
        body.cancelControls(epoch, reason);
        actuators.neutralizeAll(reason);
        movement.neutralizeAll(MovementFrameActuator.Cleanup.CANCEL);
        protectionModeLatch.clear();
        protectionSafetyRetreat.clear();
        if (!preserveObjective) soleTargetSuppression.clear();
        clearDistantThreatDisengagement();
        protectionReactivation.clear();
        baritone.clearProtectionTraversalScope();

        boolean durableBlocked = false;
        try {
            if (preserveObjective) {
                // Core already parked runnable work for protection. Leave its exact mission,
                // phase and retry history intact; only the failed temporary actuator is fenced.
                if (activeMission != null) publishMissionState(activeMission.id(), reason);
            } else if (activeMission != null && !activeMission.state().terminal()) {
                core.block(activeMission.id(), reason, now);
                publishMissionState(activeMission.id(), reason);
                durableBlocked = true;
            } else if (homeOwned) {
                durableBlocked = autonomy.blockHomeForSafety(
                        "behavior_circuit_breaker", reason, now);
            }
        } catch (IOException persistenceError) {
            logger.error("Could not persist behavior circuit-breaker result", persistenceError);
            blackBox.incident(
                    now,
                    "behavior_circuit_breaker_persistence_failure",
                    reason,
                    persistenceError,
                    this::blackBoxSnapshot);
            core.emergencyStop("behavior circuit-breaker persistence failure", now);
        }

        behaviorCircuitBreakerTrips++;
        behaviorCircuitBreakerStatus = (preserveObjective ? "failed protection actuator fenced; objective retained: " : durableBlocked
                ? "blocked exact work: " : "neutralized orphan work: ") + reason;
        boolean finalDurableBlocked = durableBlocked;
        blackBox.event(now, "behavior_circuit_breaker_trip", () -> {
            JsonObject event = new JsonObject();
            event.addProperty("violationType", violation.type().name().toLowerCase(Locale.ROOT));
            event.addProperty("missionId", violation.missionId());
            event.addProperty("operationId", operationId);
            event.addProperty("scope", breaker.scope().name().toLowerCase(Locale.ROOT));
            event.addProperty("maximumRecoveryAttempts", breaker.maximumRecoveryAttempts());
            event.addProperty("durableBlocked", finalDurableBlocked);
            event.addProperty("actuatorsNeutralized", true);
            event.addProperty("reason", reason);
            return event;
        });
        platform.publishStatus(behaviorCircuitBreakerStatus);
    }

    private void flushPendingInvariantIncidents(long now) {
        for (BehaviorInvariantMonitor.Violation violation : invariantIncidentOutbox.pending()) {
            String reason = violation.type().name().toLowerCase(Locale.ROOT)
                    + ": " + violation.evidence();
            boolean accepted = blackBox.incident(
                    violation.detectedAtMillis(),
                    "behavior_invariant",
                    reason,
                    null,
                    this::blackBoxSnapshot);
            if (!accepted) return;
            invariantIncidentOutbox.acknowledge(violation, now);
        }
    }

    private BehaviorInvariantMonitor.Observation behaviorInvariantObservation(long now) {
        var player = client.player;
        if (player == null || client.world == null) {
            resetInvariantObservationSession();
            invariantWorldSession = null;
            return null;
        }
        if (invariantWorldSession != client.world) {
            resetInvariantObservationSession();
            invariantWorldSession = client.world;
        }

        FabricBaritonePort.Snapshot navigation = baritone.snapshot();
        Mission mission = core.activeMission().orElse(null);
        boolean homeProgressSample = mission == null
                && invariantAutonomyWorkSampleTick == clientTick
                && !invariantAutonomyMissionId.isBlank();
        String missionId = mission != null
                ? mission.id()
                : homeProgressSample ? invariantAutonomyMissionId
                : navigation.missionId();
        String phase = mission != null
                ? mission.phase()
                : homeProgressSample ? "home-acquisition"
                : navigation.missionId().isBlank() ? "idle" : "route-operation";
        String planSummary = missionId.isBlank()
                ? "" : autonomy.planSummary(missionId);
        String planLeaf = ProtectionActivityClassifier.currentLeaf(planSummary);
        if (planSummary.startsWith("No local task plan")
                || planSummary.startsWith("No active hierarchical task plan")) {
            planLeaf = "";
        }
        if (planLeaf.isBlank() && !missionId.isBlank()) {
            planLeaf = mission != null
                    ? mission.kind()
                    : homeProgressSample ? "home-acquisition" : navigation.operation();
        }

        updateInvariantProgress(missionId, planLeaf, navigation);

        String dimension = client.world.getRegistryKey().getValue().toString();
        List<BehaviorInvariantMonitor.BlockChange> blockChanges =
                observeInvariantBlockChanges(dimension, navigation);
        observeRouteSupportSafety(blockChanges, navigation);
        boolean hazardRecovery = decision != null
                && decision.layer() == EntityCore.Layer.SURVIVAL
                && SurvivalAction.ESCAPE_LAVA.name().equals(decision.action());
        boolean boundedVerticalRecovery = autonomy
                .boundedGroundItemVerticalRecoveryActive(now)
                || (decision != null
                && decision.layer() == EntityCore.Layer.SURVIVAL
                && SurvivalAction.SURFACE_FOR_AIR.name().equals(decision.action()));
        if (!boundedVerticalRecovery && navigation.aquaticOwner()) {
            // These AquaticTravelPolicy phases own Y motion and have strict tick/recovery bounds.
            // CRUISE and ordinary Baritone movement remain observable as real oscillation.
            boundedVerticalRecovery = switch (navigation.aquaticMode()) {
                case "submerge", "acquire_swim", "recover" -> true;
                default -> false;
            };
        }
        DirectBodyController.SuffocationSnapshot suffocation = body.suffocationSnapshot();
        boolean verifiedProtectionCounterattackWork =
                consumeInvariantProtectionCounterattackWork();
        return new BehaviorInvariantMonitor.Observation(
                now,
                clientTick,
                missionId,
                dimension,
                new BehaviorInvariantMonitor.Vec3(player.getX(), player.getY(), player.getZ()),
                phase,
                planLeaf,
                navigation.operation(),
                navigation.missionId(),
                navigation.operationGeneration(),
                invariantObjectiveHighWater,
                invariantWorkHighWater,
                player.isInLava(),
                hazardRecovery,
                false,
                boundedVerticalRecovery,
                body.verifiedRetreatCoverHoldActive(),
                distantThreatDisengagement.awaitingOccludedRelease(now)
                        || protectionObservationOnly,
                verifiedProtectionCounterattackWork,
                decision == null ? "starting" : decision.layer().name(),
                decision == null ? "starting" : decision.action(),
                (decision != null && decision.layer() == EntityCore.Layer.PROTECTION)
                        || (protectionModeLatch.active()
                                && !protectionModeLatch.threatKey().isBlank()),
                Math.max(0.0, player.getHealth()),
                player.isTouchingWater(),
                Math.hypot(player.getVelocity().x, player.getVelocity().z),
                player.isInsideWall(),
                suffocation.target(),
                blockChanges);
    }

    /**
     * Consumes each accepted DirectBody retreat counterattack once for the
     * independent behavior monitor. Black-box recording has its own cursor so
     * diagnostic backpressure cannot change circuit-breaker behavior.
     *
     * <p>One fresh event pauses only the current two-second retreat-stall window.
     * A stale event cannot suppress the detector forever, while normal-cooldown
     * attacks can keep a geometrically sealed but productive defense alive.</p>
     */
    private boolean consumeInvariantProtectionCounterattackWork() {
        boolean observedWork = false;
        for (var attack : body.retreatCounterattackEvents()) {
            if (attack.sequence() <= lastInvariantRetreatCounterattackSequence) continue;
            lastInvariantRetreatCounterattackSequence = attack.sequence();
            if (!attack.episodeKey().equals("survival-fire-defense")) {
                observedWork = true;
            }
        }
        return observedWork;
    }

    private void resetInvariantObservationSession() {
        behaviorInvariants.reset();
        invariantIncidentOutbox.resetCooldowns();
        invariantState = behaviorInvariants.stateSnapshot();
        invariantLocalBlocks.clear();
        invariantBlockDimension = "";
    }

    /** Converts per-leaf counters into monotonic per-mission high-water evidence. */
    private void updateInvariantProgress(
            String missionId,
            String planLeaf,
            FabricBaritonePort.Snapshot navigation) {
        if (!Objects.equals(invariantMissionId, missionId)) {
            invariantMissionId = missionId;
            invariantPlanLeaf = planLeaf;
            invariantObjectiveHighWater = 0L;
            invariantWorkHighWater = 0L;
            invariantBaritoneGeneration = navigation.operationGeneration();
            invariantBaritoneWork = Math.max(0L, navigation.completedWorkUnits());
            invariantAutonomyWork = invariantAutonomyWorkSampleTick == clientTick
                    && invariantAutonomyMissionId.equals(missionId)
                    ? invariantAutonomyWorkSample : 0L;
            invariantIncidentOutbox.resetCooldowns();
            return;
        }

        boolean leafChanged = !planLeaf.isBlank()
                && !invariantPlanLeaf.isBlank()
                && !planLeaf.equals(invariantPlanLeaf);
        if (leafChanged) {
            invariantObjectiveHighWater = saturatingAdd(invariantObjectiveHighWater, 1L);
            invariantAutonomyWork = 0L;
        }
        invariantPlanLeaf = planLeaf;

        long baritoneWork = Math.max(0L, navigation.completedWorkUnits());
        long baritoneDelta;
        if (navigation.operationGeneration() != invariantBaritoneGeneration) {
            baritoneDelta = baritoneWork;
        } else {
            baritoneDelta = Math.max(0L, baritoneWork - invariantBaritoneWork);
        }
        invariantBaritoneGeneration = navigation.operationGeneration();
        invariantBaritoneWork = baritoneWork;

        long autonomyDelta = 0L;
        if (invariantAutonomyWorkSampleTick == clientTick
                && invariantAutonomyMissionId.equals(missionId)) {
            autonomyDelta = Math.max(0L, invariantAutonomyWorkSample - invariantAutonomyWork);
            invariantAutonomyWork = invariantAutonomyWorkSample;
        }
        invariantWorkHighWater = saturatingAdd(
                invariantWorkHighWater, Math.max(baritoneDelta, autonomyDelta));
    }

    private List<BehaviorInvariantMonitor.BlockChange> observeInvariantBlockChanges(
            String dimension,
            FabricBaritonePort.Snapshot navigation) {
        if (!dimension.equals(invariantBlockDimension)) {
            invariantBlockDimension = dimension;
            invariantLocalBlocks.clear();
        }

        BlockPos center = client.player.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        Map<Long, Boolean> current = new HashMap<>(192);
        List<BehaviorInvariantMonitor.BlockChange> changes = new ArrayList<>();
        for (int x = center.getX() - INVARIANT_BLOCK_HORIZONTAL_RADIUS;
                x <= center.getX() + INVARIANT_BLOCK_HORIZONTAL_RADIUS; x++) {
            for (int z = center.getZ() - INVARIANT_BLOCK_HORIZONTAL_RADIUS;
                    z <= center.getZ() + INVARIANT_BLOCK_HORIZONTAL_RADIUS; z++) {
                if (!client.world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = center.getY() - INVARIANT_BLOCK_BELOW;
                        y <= center.getY() + INVARIANT_BLOCK_ABOVE; y++) {
                    cursor.set(x, y, z);
                    var state = client.world.getBlockState(cursor);
                    boolean occupied = !state.getCollisionShape(client.world, cursor).isEmpty();
                    long key = cursor.asLong();
                    current.put(key, occupied);
                    Boolean previous = invariantLocalBlocks.get(key);
                    if (previous == null || previous == occupied) continue;

                    BehaviorInvariantMonitor.BlockChangeKind kind = occupied
                            ? BehaviorInvariantMonitor.BlockChangeKind.PLACED
                            : BehaviorInvariantMonitor.BlockChangeKind.BROKEN;
                    BehaviorInvariantMonitor.BlockPurpose purpose = BehaviorInvariantMonitor.BlockPurpose.OTHER;
                    if (occupied && isInvariantWorkstation(state)) {
                        purpose = BehaviorInvariantMonitor.BlockPurpose.WORKSTATION;
                    } else if (occupied && !baritone.isBlueprintConstructionBlock(dimension,cursor,state)
                            && likelyRouteSupport(cursor, center, navigation)) {
                        purpose = BehaviorInvariantMonitor.BlockPurpose.ROUTE_SUPPORT;
                    }
                    changes.add(new BehaviorInvariantMonitor.BlockChange(
                            new BehaviorInvariantMonitor.BlockCoordinate(dimension, x, y, z),
                            kind,
                            purpose,
                            false));
                }
            }
        }
        invariantLocalBlocks.clear();
        invariantLocalBlocks.putAll(current);
        return List.copyOf(changes);
    }

    private void observeRouteSupportSafety(
            List<BehaviorInvariantMonitor.BlockChange> changes,
            FabricBaritonePort.Snapshot navigation) {
        String operationId = navigation.missionId().isBlank()
                ? invariantAutonomyMissionId : navigation.missionId();
        for (BehaviorInvariantMonitor.BlockChange change : changes) {
            if (change.kind() != BehaviorInvariantMonitor.BlockChangeKind.PLACED
                    || change.purpose() != BehaviorInvariantMonitor.BlockPurpose.ROUTE_SUPPORT) {
                continue;
            }
            BehaviorInvariantMonitor.BlockCoordinate coordinate = change.coordinate();
            baritone.observeRouteSupportPlaced(
                    coordinate.dimension(), coordinate.x(), coordinate.y(), coordinate.z(),
                    operationId, clientTick);
        }

        baritone.observeRouteSupportBody(clientTick);
        // Selected player protection remains a final boundary; route-support
        // intent is observed here but never converted into property authority.
        baritone.enforceProtectedBreakGuardAndObserveSupportIntent();
        baritone.enforceProtectedInteractionGuard();

        for (BehaviorInvariantMonitor.BlockChange change : changes) {
            if (change.kind() != BehaviorInvariantMonitor.BlockChangeKind.BROKEN) continue;
            BehaviorInvariantMonitor.BlockCoordinate coordinate = change.coordinate();
            baritone.observeRouteSupportRemoved(
                    coordinate.dimension(), coordinate.x(), coordinate.y(), coordinate.z(),
                    clientTick);
        }
    }

    private boolean likelyRouteSupport(
            BlockPos position,
            BlockPos feet,
            FabricBaritonePort.Snapshot navigation) {
        boolean beneathBody = position.getY() <= feet.getY()
                && position.getY() >= feet.getY() - 2
                && Math.abs(position.getX() - feet.getX()) <= 1
                && Math.abs(position.getZ() - feet.getZ()) <= 1;
        boolean routeOwned = navigation.pathing()
                || (decision != null && decision.layer() == EntityCore.Layer.MISSION);
        return beneathBody && routeOwned;
    }

    private static boolean isInvariantWorkstation(net.minecraft.block.BlockState state) {
        return state.isOf(Blocks.CRAFTING_TABLE)
                || state.isOf(Blocks.FURNACE)
                || state.isOf(Blocks.BLAST_FURNACE)
                || state.isOf(Blocks.SMOKER)
                || state.isOf(Blocks.CHEST)
                || state.isOf(Blocks.BARREL);
    }

    private void pruneRecentInvariantViolations(long now) {
        long oldest = now - RECENT_INVARIANT_RETENTION_MILLIS;
        while (!recentInvariantViolations.isEmpty()
                && recentInvariantViolations.peekFirst().detectedAtMillis() < oldest) {
            recentInvariantViolations.removeFirst();
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private void captureBlackBox(long now) {
        captureCriticalBlackBoxEvents(now);
        captureMonotonicBlackBoxEvents(now);
        String homeFailure = autonomy.homeRouteFailureIncident();
        if (homeFailure.isEmpty()) lastHomeRouteIncidentKey = "";
        else if (!homeFailure.equals(lastHomeRouteIncidentKey)
                && blackBox.incident(now, "home_route_blocked", homeFailure, null, this::blackBoxSnapshot)) {
            lastHomeRouteIncidentKey = homeFailure;
        }
        long[] sampledCombatHighWater = {lastSampledCombatAttackSequence};
        boolean sampleAccepted = blackBox.sample(
                now, () -> blackBoxSampleSnapshot(sampledCombatHighWater));
        if (sampleAccepted) {
            lastSampledCombatAttackSequence = sampledCombatHighWater[0];
        }
        Mission mission = decision == null ? null : decision.mission();
        if (mission == null || mission.state() != MissionState.BLOCKED) {
            lastBlackBoxIncidentKey = "";
            return;
        }
        String reason = mission.lastError().isBlank() ? decision.reason() : mission.lastError();
        String key = mission.id() + ":" + mission.phase() + ":" + reason;
        if (key.equals(lastBlackBoxIncidentKey)) return;
        if (blackBox.incident(
                now, "mission_blocked", reason, null, this::blackBoxSnapshot)) {
            lastBlackBoxIncidentKey = key;
        }
    }

    private void captureCriticalBlackBoxEvents(long now) {
        var player = client.player;
        var world = client.world;
        if (player == null || world == null) {
            criticalBlackBoxEvents.observationUnavailable();
            criticalObservationPlayerSession = null;
            criticalObservationWorldSession = null;
        } else {
            // ClientPlayNetworkHandler replaces MinecraftClient.player directly
            // on respawn; there is no guaranteed tick with a null player between
            // the dead and replacement objects. Fence exact object identity here
            // so the old hazard/health baseline and delayed Paper damage evidence
            // cannot cross into a new player or world session. Pending events are
            // deliberately retained by observationUnavailable(), including an
            // already-observed killing blow from the prior client tick.
            if (criticalObservationPlayerSession != player
                    || criticalObservationWorldSession != world) {
                criticalBlackBoxEvents.observationUnavailable();
            }
            criticalObservationPlayerSession = player;
            criticalObservationWorldSession = world;
            Mission mission = decision == null ? null : decision.mission();
            CriticalBlackBoxEvents.MissionContext missionContext = mission == null
                    ? CriticalBlackBoxEvents.MissionContext.none()
                    : new CriticalBlackBoxEvents.MissionContext(
                            true,
                            mission.id(),
                            mission.kind(),
                            mission.state().name(),
                            mission.phase());
            CriticalBlackBoxEvents.DecisionContext decisionContext = decision == null
                    ? CriticalBlackBoxEvents.DecisionContext.unavailable()
                    : new CriticalBlackBoxEvents.DecisionContext(
                            true,
                            decision.layer().name(),
                            decision.action(),
                            decision.reason(),
                            decision.controlEpoch());
            criticalBlackBoxEvents.observe(new CriticalBlackBoxEvents.Observation(
                    now,
                    clientTick,
                    new CriticalBlackBoxEvents.Position(
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            world.getRegistryKey().getValue().toString()),
                    player.getHealth(),
                    player.getAir(),
                    player.getMaxAir(),
                    new CriticalBlackBoxEvents.HazardState(
                            player.isInLava(),
                            player.isOnFire(),
                            player.isInsideWall(),
                            player.isSubmergedInWater()),
                    missionContext,
                    decisionContext));
        }
        criticalBlackBoxEvents.flush((timestamp, eventType, body) ->
                blackBox.event(timestamp, eventType, () -> body));
    }

    private void retainSelfDamageEvidence(ServerDamageEvent damage, long receivedAtMillis) {
        if (damage.target() != ServerDamageEvent.Target.SELF) return;
        CriticalBlackBoxEvents.AttackerEvidence attacker = damage.attacker()
                .map(facts -> new CriticalBlackBoxEvents.AttackerEvidence(
                        facts.uuid().toString(),
                        facts.type(),
                        facts.name(),
                        facts.distance(),
                        facts.projectile(),
                        facts.projectileType()))
                .orElse(null);
        criticalBlackBoxEvents.acceptDamageEvidence(
                new CriticalBlackBoxEvents.DamageEvidence(
                        damage.eventId().toString(),
                        damage.cause(),
                        damage.damage(),
                        damage.healthBefore(),
                        damage.timestamp(),
                        receivedAtMillis,
                        attacker));
    }

    private void captureMonotonicBlackBoxEvents(long now) {
        var objective = baritone.attackMissionObjective().snapshot();
        // Native selection/death may occur after this tick's start clock.
        if (objective.revision() > lastRecordedAttackObjectiveRevision
                && blackBox.event(Math.max(now,Math.max(objective.selectedAtMillis(),objective.deathObservedAtMillis())),
                "attack_mission_objective", () -> {
                    JsonObject event = new JsonObject();
                    event.addProperty("objectiveRevision", objective.revision());
                    event.addProperty("state", objective.state());
                    event.addProperty("missionId", objective.missionId());
                    event.addProperty("targetUuid", objective.targetUuid());
                    event.addProperty("targetType", objective.targetType());
                    event.addProperty("selectedAtMillis", objective.selectedAtMillis());
                    event.addProperty("deathObservedAtMillis", objective.deathObservedAtMillis());
                    return event;
                })) lastRecordedAttackObjectiveRevision = objective.revision();
        FabricBaritonePort.Snapshot navigation = baritone.snapshot();
        for (var attack : navigation.combatAttackEvents()) {
            if (attack.sequence() <= lastRecordedCombatAttackSequence) continue;
            boolean accepted = blackBox.event(
                    attack.issuedAtMillis(), "combat_attack",
                    () -> BlackBoxFrames.combatAttackEvent(attack));
            if (!accepted) break;
            lastRecordedCombatAttackSequence = attack.sequence();
        }

        for (var attack : body.retreatCounterattackEvents()) {
            if (attack.sequence() <= lastRecordedRetreatCounterattackSequence) continue;
            boolean accepted = blackBox.event(
                    attack.issuedAtMillis(), "retreat_counterattack", () -> {
                JsonObject event = new JsonObject();
                event.addProperty("counterattackSequence", attack.sequence());
                event.addProperty("issuedAtMillis", attack.issuedAtMillis());
                event.addProperty("playerAge", attack.playerAge());
                event.addProperty("controlEpoch", attack.controlEpoch());
                event.addProperty("episodeKey", attack.episodeKey());
                event.addProperty("targetUuid", attack.targetUuid());
                event.addProperty("targetType", attack.targetType());
                event.addProperty("weaponId", attack.weaponId());
                event.addProperty("cooldownProgress", attack.cooldownProgress());
                event.addProperty("distance", attack.distance());
                event.addProperty("source", "direct_body_retreat");
                event.addProperty("controlContext",
                        attack.episodeKey().equals("survival-fire-defense")
                                ? "survival_fire_defense"
                                : "protection_retreat");
                return event;
            });
            if (!accepted) break;
            lastRecordedRetreatCounterattackSequence = attack.sequence();
        }

        var transactions = autonomy.inventoryTransactionDiagnostics();
        var timeout = transactions == null ? null : transactions.lastTimeout();
        if (timeout != null && timeout.sequence() > lastRecordedInventoryTimeoutSequence) {
            boolean accepted = blackBox.event(
                    timeout.detectedAtMillis(), "inventory_transaction_timeout", () -> {
                JsonObject event = new JsonObject();
                event.addProperty("timeoutSequence", timeout.sequence());
                event.addProperty("detectedAtMillis", timeout.detectedAtMillis());
                event.addProperty("startedAtMillis", timeout.startedAtMillis());
                event.addProperty("owner", timeout.owner());
                event.addProperty("handlerSyncId", timeout.handlerSyncId());
                JsonArray watched = new JsonArray();
                timeout.watchedSlots().forEach(watched::add);
                event.add("watchedSlots", watched);
                event.addProperty("clientTick", clientTick);
                return event;
            });
            if (accepted) lastRecordedInventoryTimeoutSequence = timeout.sequence();
        }
    }

    /**
     * Legacy sample readers receive each typed attack once as a delta. The
     * standalone combat_attack record remains the loss-resistant authority.
     */
    private JsonObject blackBoxSampleSnapshot(long[] sampledCombatHighWater) {
        FabricBaritonePort.Snapshot navigation = baritone.snapshot();
        List<CombatAttackJournal.Event> delta =
                navigation.combatAttackEvents().stream()
                        .filter(attack -> attack.sequence() > lastSampledCombatAttackSequence)
                        .toList();
        JsonObject frame = blackBoxSnapshot(navigation, delta);
        if (!delta.isEmpty()) {
            sampledCombatHighWater[0] = delta.getLast().sequence();
        }
        return frame;
    }

    private JsonObject blackBoxSnapshot() {
        return blackBoxSnapshot(baritone.snapshot(), List.of());
    }

    private JsonObject blackBoxSnapshot(
            FabricBaritonePort.Snapshot navigation,
            List<CombatAttackJournal.Event> combatDelta) {
        JsonObject frame = BlackBoxFrames.snapshot(
                client,
                decision,
                navigation,
                body.waterLocomotionSnapshot(),
                autonomy.inventoryTransactionDiagnostics(),
                core.trace().explainLatest(),
                platform.publishedStatus(),
                clientTick,
                invariantState,
                List.copyOf(recentInvariantViolations));
        BlackBoxFrames.setCombatAttackDelta(frame, combatDelta);
        frame.add("combatEngagement", baritone.combatEngagementSnapshot());
        frame.add("idleWork", idleWorkFrame(System.currentTimeMillis()));
        JsonObject disengagementFrame = new JsonObject();
        disengagementFrame.addProperty(
                "active",
                currentDistantThreatDecision != null
                        && distantThreatDisengagement.state()
                        != DistantThreatDisengagementPolicy.State.IDLE);
        disengagementFrame.addProperty("targetId", currentDistantThreatTargetId);
        disengagementFrame.addProperty(
                "targetKind", distantThreatDisengagement.targetKind().name());
        if (currentDistantThreatDecision != null) {
            disengagementFrame.addProperty(
                    "state", currentDistantThreatDecision.state().name());
            disengagementFrame.addProperty(
                    "action", currentDistantThreatDecision.action().name());
            disengagementFrame.addProperty(
                    "quietForMillis", currentDistantThreatDecision.quietForMillis());
            disengagementFrame.addProperty(
                    "separationGain", currentDistantThreatDecision.separationGain());
            disengagementFrame.addProperty("detail", currentDistantThreatDecision.detail());
        } else {
            disengagementFrame.addProperty("state", "IDLE");
            disengagementFrame.addProperty("action", "PASS_THROUGH");
            disengagementFrame.addProperty("quietForMillis", 0L);
            disengagementFrame.addProperty("separationGain", 0.0);
            disengagementFrame.addProperty("detail", "no retained distant-threat decision");
        }
        frame.add("distantThreatDisengagement", disengagementFrame);
        JsonObject circuitBreaker = new JsonObject();
        circuitBreaker.addProperty("trips", behaviorCircuitBreakerTrips);
        circuitBreaker.addProperty("retainedTripKeys", behaviorCircuitBreaker.retainedTrips());
        circuitBreaker.addProperty("armed", behaviorCircuitBreakerTrips == 0L);
        circuitBreaker.addProperty("status", behaviorCircuitBreakerStatus);
        frame.add("behaviorCircuitBreaker", circuitBreaker);
        JsonObject identity = new JsonObject();
        identity.addProperty("schemaVersion", bridge.buildIdentity().schemaVersion());
        identity.addProperty("version", bridge.buildIdentity().version());
        identity.addProperty("sourceCommit", bridge.buildIdentity().sourceCommit());
        identity.addProperty("buildId", bridge.buildIdentity().buildId());
        identity.addProperty("sourceState", bridge.buildIdentity().sourceState());
        identity.addProperty("generatedAtUtc", bridge.buildIdentity().generatedAt().toString());
        identity.addProperty("paired", bridge.connected());
        if (bridge.serverBuildIdentity() != null) {
            identity.addProperty("serverVersion", bridge.serverBuildIdentity().version());
            identity.addProperty("serverSourceCommit", bridge.serverBuildIdentity().sourceCommit());
            identity.addProperty("serverBuildId", bridge.serverBuildIdentity().buildId());
            identity.addProperty("serverSchemaVersion", bridge.serverBuildIdentity().schemaVersion());
            identity.addProperty("serverSourceState", bridge.serverBuildIdentity().sourceState());
            identity.addProperty(
                    "serverGeneratedAtUtc", bridge.serverBuildIdentity().generatedAt().toString());
        }
        if (!bridge.lastHandshakeFailure().isBlank()) {
            identity.addProperty("handshakeFailure", bridge.lastHandshakeFailure());
        }
        frame.add("buildIdentity", identity);
        DirectBodyController.FallTechniqueSnapshot fall = body.fallTechniqueSnapshot();
        JsonObject fallFrame = TechniqueCapabilityFrames.snapshot(
                body.fallTechniqueInventory(), System.currentTimeMillis());
        fallFrame.addProperty("lastSelection", fall.selection());
        fallFrame.addProperty("lastReason", fall.reason());
        fallFrame.addProperty("detail", fall.detail());
        fallFrame.addProperty("activePhase", fall.activePhase());
        fallFrame.addProperty("evidencePhase", fall.evidencePhase());
        fallFrame.addProperty("target", fall.target());
        fallFrame.addProperty("placement", fall.placement());
        fallFrame.addProperty("placementRequests", fall.placementRequests());
        fallFrame.addProperty("recoveryRequests", fall.recoveryRequests());
        fallFrame.addProperty("recovered", fall.recovered());
        fallFrame.addProperty(
                "existingWaterSteeringTicks", fall.existingWaterSteeringTicks());
        fallFrame.addProperty("viewPrepared", fall.viewPrepared());
        frame.add("fallTechnique", fallFrame);
        DirectBodyController.SuffocationSnapshot suffocation = body.suffocationSnapshot();
        JsonObject suffocationFrame = new JsonObject();
        suffocationFrame.addProperty("active", suffocation.active());
        suffocationFrame.addProperty("insideWall", suffocation.insideWall());
        suffocationFrame.addProperty("target", suffocation.target());
        suffocationFrame.addProperty("direction", suffocation.direction());
        suffocationFrame.addProperty(
                "deadlineRemainingMillis", suffocation.deadlineRemainingMillis());
        suffocationFrame.addProperty("failedDirections", suffocation.failedDirections());
        suffocationFrame.addProperty("failedBlocks", suffocation.failedBlocks());
        suffocationFrame.addProperty("detail", suffocation.detail());
        frame.add("suffocationEscape", suffocationFrame);

        DirectBodyController.LavaSnapshot lava = body.lavaSnapshot();
        JsonObject lavaFrame = new JsonObject();
        lavaFrame.addProperty("active", lava.active());
        lavaFrame.addProperty("inLava", lava.inLava());
        lavaFrame.addProperty("commandedAtMillis", lava.commandedAtMillis());
        lavaFrame.addProperty("commandClientTick", lava.commandClientTick());
        lavaFrame.addProperty("commandMode", lava.commandMode());
        lavaFrame.addProperty("forwardCommanded", lava.forwardCommanded());
        lavaFrame.addProperty("jumpCommanded", lava.jumpCommanded());
        lavaFrame.addProperty("target", lava.target());
        lavaFrame.addProperty("failedWaypoints", lava.failedWaypoints());
        lavaFrame.addProperty("detail", lava.detail());
        frame.add("lavaEscape", lavaFrame);

        DirectBodyController.FireSnapshot fire = body.fireSnapshot();
        JsonObject fireFrame = new JsonObject();
        fireFrame.addProperty("active", fire.active());
        fireFrame.addProperty("onFire", fire.onFire());
        fireFrame.addProperty("commandedAtMillis", fire.commandedAtMillis());
        fireFrame.addProperty("commandClientTick", fire.commandClientTick());
        fireFrame.addProperty("commandMode", fire.commandMode());
        fireFrame.addProperty("forwardCommanded", fire.forwardCommanded());
        fireFrame.addProperty("jumpCommanded", fire.jumpCommanded());
        fireFrame.addProperty("target", fire.target());
        fireFrame.addProperty("failedWaypoints", fire.failedWaypoints());
        fireFrame.addProperty("detail", fire.detail());
        fireFrame.addProperty("defenseMode", fire.defenseMode());
        fireFrame.addProperty("defenseTargetUuid", fire.defenseTargetUuid());
        fireFrame.addProperty("defenseTargetType", fire.defenseTargetType());
        fireFrame.addProperty("defenseShieldRaised", fire.defenseShieldRaised());
        fireFrame.addProperty("fireTicks", fire.fireTicks());
        fireFrame.addProperty("predictedRemainingHits", fire.predictedRemainingHits());
        fireFrame.addProperty("predictedDamage", fire.predictedDamage());
        fireFrame.addProperty("healthReserve", fire.healthReserve());
        fireFrame.addProperty("dryHoldSurvivable", fire.dryHoldSurvivable());
        fireFrame.addProperty("terminalKind", fire.terminalKind());
        frame.add("fireEscape", fireFrame);
        return frame;
    }

    private static boolean booleanValue(JsonElement value, boolean fallback) {
        if (value == null || value.isJsonNull()) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        if (value.isJsonPrimitive()) {
            String text = value.getAsString();
            if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("on")) return true;
            if (text.equalsIgnoreCase("false") || text.equalsIgnoreCase("off")) return false;
        }
        return fallback;
    }

    private static String text(JsonObject object, String field, String fallback) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static JsonObject object(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static double number(JsonObject object, String field, double fallback) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsDouble();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }

    @Override
    public void onFrame(JsonObject frame) {
        inbound.offer(frame.deepCopy());
    }

    @Override
    public void onConnectionChanged(boolean connected, int protocol, String detail) {
        if (connected) {
            WorldScopeGate.Observation worldObservation = worldScopeGate.observe(
                    bridge.worldIdentity(WorldStateScope.OVERWORLD_DIMENSION).orElse(""));
            if (worldObservation.action() == WorldScopeGate.Action.REBIND) {
                logger.warn("{}; no world-owned action will run before the main-thread rebind",
                        worldObservation.detail());
            } else if (worldObservation.action() == WorldScopeGate.Action.HOLD) {
                logger.error("{}; no world-owned action will run", worldObservation.detail());
            }
        } else {
            worldScopeGate.disconnected();
        }
        protectedAreas.connectionChanged(connected);
        autonomy.invalidateHomeFurniturePreview();
        entityAttacks.connectionChanged(connected);
        foreignItems.connectionChanged(connected);
        if (connected) missionReporter.newSession();
        recipeConnections.offer(new RecipeConnectionEvent(
                connected,
                protocol,
                connected ? bridge.negotiatedFeatures() : Set.of()));
        logger.info("EntityBridge {}: {}", connected ? "connected with protocol " + protocol : "disconnected", detail);
    }

    @Override
    public void close() {
        blueprintClosed=true;
        if (closed) return;
        closed = true;
        long now = System.currentTimeMillis();
        blackBox.lifecycle(now, "runtime_stopping", "Minecraft client shutdown");
        try {
            preemptHomeEconomy(
                    HomeEconomyPolicy.Authority.DISCONNECTED,
                    now,
                    "client shutdown preempted Home economy");
        } catch (IOException error) {
            logger.error("Could not persist the Home economy shutdown checkpoint", error);
        }
        long epoch = core.emergencyStop("client shutdown", now);
        baritone.cancel(epoch, "client shutdown");
        baritone.clearProtectionTraversalScope();
        autonomy.releaseControls("client shutdown");
        body.cancelControls(epoch, "client shutdown");
        actuators.neutralizeAll("client shutdown");
        protectionModeLatch.clear();
        protectionSafetyRetreat.clear();
        bridge.close();
        journal.capture(core.trace());
        blackBox.close();
    }

    private record RecipeConnectionEvent(
            boolean connected,
            int protocol,
            Set<String> features) {
        private RecipeConnectionEvent {
            features = Set.copyOf(Objects.requireNonNull(features, "features"));
        }
    }

    /** Product runtime health without exposing bridge mutation authority. */
    public boolean bridgeConnected() {
        return bridge.connected();
    }

    /** Main-thread owner polls this before ticking a runtime built for another save. */
    public Optional<WorldStateScope> requestedWorldScope() {
        return worldScopeGate.requestedScope();
    }

}
