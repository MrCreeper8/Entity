package dev.entity.client.autonomy;

import com.google.gson.JsonObject;
import dev.entity.client.bridge.DeliveryProtocol;
import dev.entity.client.autonomy.policy.ResourcePlanner;
import dev.entity.client.autonomy.policy.FlowerSearchContinuation;
import dev.entity.client.autonomy.policy.AcquisitionPlan;
import dev.entity.client.autonomy.policy.AcquisitionRequest;
import dev.entity.client.autonomy.collection.ClientGroundItemCollectionController;
import dev.entity.client.autonomy.collection.GroundItemCollectionPolicy;
import dev.entity.client.autonomy.collection.ForeignItemClaimClientState;
import dev.entity.client.autonomy.collection.MinecraftGroundItemAdapter;
import dev.entity.client.autonomy.inventory.InventoryTransactionEngine;
import dev.entity.client.autonomy.inventory.InventoryReport;
import dev.entity.client.autonomy.policy.CombatLoadoutCoordinator;
import dev.entity.client.autonomy.policy.CombatMissionLoadoutGate;
import dev.entity.client.autonomy.policy.CombatLoadoutPolicy;
import dev.entity.client.autonomy.policy.CommittedActionFailurePolicy;
import dev.entity.client.autonomy.policy.BatchReservationPolicy;
import dev.entity.client.autonomy.policy.AutonomyWatchdogPolicy;
import dev.entity.client.autonomy.policy.DeathDropRecoveryPolicy;
import dev.entity.client.autonomy.policy.DeathReconciliationTokenPolicy;
import dev.entity.client.autonomy.policy.DeliveryPolicy;
import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy;
import dev.entity.client.autonomy.policy.InventoryCapacityPolicy;
import dev.entity.client.autonomy.policy.DeliverySuppliesOrder;
import dev.entity.client.autonomy.policy.EmergencyTechniqueReservationPolicy;
import dev.entity.client.autonomy.policy.FoodCookingSessionPolicy;
import dev.entity.client.autonomy.policy.FoodFamilyPolicy;
import dev.entity.client.autonomy.policy.FurnaceTransactionRecoveryPolicy;
import dev.entity.client.autonomy.policy.AtomicHomeEconomyStore;
import dev.entity.client.autonomy.policy.AtomicHomeWaterFillStore;
import dev.entity.client.autonomy.policy.FieldKitLedger;
import dev.entity.client.autonomy.policy.GatherFirstBatchPolicy;
import dev.entity.client.autonomy.policy.HarvestToolAccessPolicy;
import dev.entity.client.autonomy.policy.HomeEconomyPolicy;
import dev.entity.client.autonomy.policy.HomeEconomySession;
import dev.entity.client.autonomy.policy.HomeControlPolicy;
import dev.entity.client.autonomy.policy.HomeFurnitureBindingPolicy;
import dev.entity.client.autonomy.policy.HomeFurnitureAdoptionPolicy;
import dev.entity.client.autonomy.policy.HomeManualRecoveryPolicy;
import dev.entity.client.autonomy.policy.HomeMissionSupplyAllocator;
import dev.entity.client.autonomy.policy.HomeMissionSupplyCodec;
import dev.entity.client.autonomy.policy.HomeTransferIntentCodec;
import dev.entity.client.autonomy.policy.HomePreemptionDrainPolicy;
import dev.entity.client.autonomy.policy.HomeSleepPolicy;
import dev.entity.client.autonomy.policy.HomeStockPolicy;
import dev.entity.client.autonomy.policy.HomeStockDemandForecast;
import dev.entity.client.autonomy.policy.HomeStockStatusPolicy;
import dev.entity.client.autonomy.policy.IdleStockPolicy;
import dev.entity.client.autonomy.policy.IdleStockCancellation;
import dev.entity.client.autonomy.policy.IdleSleepFallback;
import dev.entity.client.autonomy.policy.IdleStockRouteRecovery;
import dev.entity.client.autonomy.policy.IdleStockToolStore;
import dev.entity.client.autonomy.policy.HomeStoreReconciliationPolicy;
import dev.entity.client.autonomy.policy.HomeWaterFillPolicy;
import dev.entity.client.autonomy.policy.HomeWaterFillSession;
import dev.entity.client.autonomy.policy.InventoryReservationLedger;
import dev.entity.client.autonomy.policy.InventoryCommitProofPolicy;
import dev.entity.client.autonomy.policy.InventoryReservationLedger.Purpose;
import dev.entity.client.autonomy.policy.MissionRationPolicy;
import dev.entity.client.autonomy.policy.MissionDeathRecoveryPolicy;
import dev.entity.client.autonomy.policy.MiningEntranceCheckpoint;
import dev.entity.client.autonomy.policy.MiningSession;
import dev.entity.client.autonomy.policy.MiningSessionPolicy;
import dev.entity.client.autonomy.policy.MiningToolSegmentPolicy;
import dev.entity.client.autonomy.policy.PlanCommitmentPolicy;
import dev.entity.client.autonomy.policy.ResourcePlanner.Action;
import dev.entity.client.autonomy.policy.ResourcePlanner.ActionKind;
import dev.entity.client.autonomy.policy.ResourceCatalog;
import dev.entity.client.autonomy.policy.ToolStrategy;
import dev.entity.client.autonomy.policy.ThrowawayReservationPolicy;
import dev.entity.client.autonomy.policy.UniversalProgramCodec;
import dev.entity.client.autonomy.policy.UnavailableHarvestSources;
import dev.entity.client.autonomy.policy.CraftingIngredientCapacity;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment.DivergenceCause;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment.Observation;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment.PlanningBaseline;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment.Program;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment.Status;
import dev.entity.client.autonomy.policy.TacticalCombatPolicy;
import dev.entity.client.autonomy.policy.WoodLogFamilyPolicy;
import dev.entity.client.baritone.FabricBaritonePort;
import dev.entity.client.baritone.MiningPolicy;
import dev.entity.client.control.ActionLease;
import dev.entity.client.control.ActionOwner;
import dev.entity.client.control.ExecutionKernel;
import dev.entity.client.control.MinecraftActuatorGateway;
import dev.entity.client.protection.ProtectedAreaClientState;
import dev.entity.core.action.ActionSupervisor;
import dev.entity.core.control.ControlLease;
import dev.entity.core.farm.ManagedFarmPolicy;
import dev.entity.core.farm.ManagedFarmEconomyPolicy;
import dev.entity.core.model.Mission;
import dev.entity.core.model.MissionState;
import dev.entity.core.persistence.JsonPlanStore;
import dev.entity.core.plan.PlanFrame;
import dev.entity.core.plan.PlanFrameState;
import dev.entity.core.plan.TaskPlan;
import dev.entity.core.plan.TaskPlanCoordinator;
import dev.entity.core.plan.TaskPlanState;
import dev.entity.core.port.BaritonePort;
import dev.entity.core.stewardship.ProtectedAreaHomePermit;
import dev.entity.core.stewardship.ProtectedAreaPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Entity 2.8's long-horizon executor. A durable plan owns intent while
 * Baritone and the inventory/workstation controllers execute one leaf action.
 */
public final class AutonomyExecutor {
    private static final Set<String> SELF_SUFFICIENT_KINDS = Set.of("get", "give", "bring", "gear");
    private static final List<String> FUEL_ITEMS = List.of(
            "minecraft:coal", "minecraft:charcoal", "minecraft:coal_block");
    private static final int COAL_FUEL_OPERATIONS = 8;
    private static final int COAL_BLOCK_FUEL_OPERATIONS = 80;
    /**
     * A furnace consumes burn ticks even while its input slot is empty. A
     * client-controlled mixed-food session necessarily has a short empty-input
     * interval while it acknowledges the last output of one subtype and loads
     * the next subtype. Reserve one operation of burn capacity per subtype
     * transition so an exact eight-item mixed batch does not run out of coal on
     * its final item.
     */
    private static final int FOOD_INPUT_SWAP_HEADROOM_OPERATIONS = 1;
    private static final Map<String, Integer> PICKAXE_RANKS = Map.of(
            "wooden_pickaxe", 1,
            "stone_pickaxe", 2,
            "iron_pickaxe", 3,
            "diamond_pickaxe", 4,
            "netherite_pickaxe", 5);
    private static final long INVENTORY_STALL_TIMEOUT_MILLIS = 20_000L;
    private static final long FURNACE_STALL_TIMEOUT_MILLIS = 30_000L;
    private static final long DROP_SPAWN_GRACE_MILLIS = 2_500L;
    private static final long DROP_COLLECTION_TIMEOUT_MILLIS = 12_000L;
    private static final int MANAGED_FARM_ROUTE_VERTICAL_SCAN = 16;
    private static final Set<String> MANAGED_FARM_CROP_BLOCKS =
            Set.copyOf(ManagedFarmPolicy.crops().stream()
                    .map(ManagedFarmPolicy.CropFamily::block)
                    .toList());
    private static final String MANAGED_FARM_BASELINE_KEY = "farmBaselineV1";
    private static final String MANAGED_FARM_HOME_GENERATION = "farmHomeGeneration";
    private static final String MANAGED_FARM_HOME_FINGERPRINT = "farmHomeFingerprint";
    private static final String MANAGED_FARM_HOME_DIMENSION = "farmHomeDimension";
    private static final String MANAGED_FARM_HOME_X = "farmHomeX";
    private static final String MANAGED_FARM_HOME_Y = "farmHomeY";
    private static final String MANAGED_FARM_HOME_Z = "farmHomeZ";
    private static final String MANAGED_FARM_STORAGE_LEDGERS = "farmStorageLedgers";
    private static final String MANAGED_FARM_STORAGE_INDEX = "farmStorageIndex";
    private static final String MANAGED_FARM_TRANSFER_KEY = "farmTransferV1";
    private static final String CAPACITY_TRANSFER_REOBSERVE_KEY = "capacityTransferReobserveV1";
    private static final String STOP_CAPACITY_CLEANUP_KEY = "stopCapacityCleanupV1";
    private static final String HUNT_RECOVERY_PROBE_SUPPRESSED_UNTIL =
            "huntRecoveryProbeSuppressedUntil";
    // A failed carcass drop remains a loaded item entity after Entity gives up
    // trying to reach it. Keep the interrupted-drop probe away for at least the
    // candidate-age window while normal hunting remains fully active; otherwise
    // the probe immediately reopens the same failed transaction with a fresh
    // failure budget and Entity stands beside the drop forever.
    private static final long HUNT_RECOVERY_PROBE_SUPPRESSION_MILLIS =
            GroundItemCollectionPolicy.DEFAULT_MAXIMUM_AGE_MILLIS;
    private static final long COMBAT_LOADOUT_CLEANUP_TIMEOUT_MILLIS = 750L;
    // A food hunt is allowed to explore real terrain, but it must not turn one
    // meal into a ten-minute, thousand-block expedition. The natural-world gate
    // found passive animals inside ~280 blocks; five minutes / 512 blocks leaves
    // room for sparse terrain while bounding exposure, hunger, and return cost.
    private static final long HUNT_SEARCH_TIMEOUT_MILLIS = 5 * 60_000L;
    private static final int HOME_ASSET_RADIUS = 6;
    private static final int HOME_WATER_SEARCH_RADIUS = 64;
    private static final String HOME_RESERVATION_OWNER = "home-economy-acquisition";
    private static final String HOME_UNPINNED_ASSET_ROLES = "homeUnpinnedAssetRoles";
    private static final String HOME_ACQUISITION_DEPARTURE_KEY =
            "homeAcquisitionDepartureFingerprint";
    private static final String HOME_MISSION_SUPPLY_KEY = "homeSupplyV1";
    private static final String HOME_MISSION_SUPPLY_OWNER_PREFIX =
            "home-mission-supply-owner:";
    private static final String HOME_MISSION_SUPPLY_PIN_AUTHORITY =
            "homeMissionSupplyPinnedStations";
    private static final String HOME_MISSION_SUPPLY_RETAINED_FOOD =
            "homeMissionSupplyRetainedFood";
    private static final int HUNT_SEARCH_RADIUS = 512;
    private static final int MAX_BATCH_ITEM_COUNT = 4_096;
    private static final String FALLBACK_CATALOG_FINGERPRINT =
            "entity2-bounded-fallback-catalog-v1";
    private static final String UNIVERSAL_PROGRAM_KEY = "universalProgramV1";
    private static final String UNIVERSAL_PROGRAM_ACTION_TOKEN_KEY =
            "universalProgramActionToken";
    private static final String COMMITTED_FURNACE_FUEL_AUTHORIZED_KEY =
            "committedFurnaceFuelAuthorized";
    private static final String COMMITTED_FURNACE_FUEL_COMMITTED_KEY =
            "committedFurnaceFuelCommitted";
    private static final String UNIVERSAL_PROGRAM_ROLE_KEY = "universalProgramRole";
    private static final String UNIVERSAL_PROGRAM_COMMITTED_ACTION = "committed_action";
    /**
     * Root-scoped field-kit facts. They are keyed by workstation item so a
     * retained table and furnace can coexist across intervening program
     * actions. Child leaves still use the controller's conventional
     * ownedWorkstation* keys; these keys are the durable hand-off between
     * committed children.
     */
    private static final String UNIVERSAL_WORKSTATION_FACT_PREFIX =
            "universalWorkstation.";
    private static final double MINING_DROP_RADIUS = 2_048.0;
    private static final ActionSupervisor.Policy INVENTORY_ACTION_POLICY =
            new ActionSupervisor.Policy(INVENTORY_STALL_TIMEOUT_MILLIS, 5, 10);
    private static final ActionSupervisor.Policy FURNACE_ACTION_POLICY =
            new ActionSupervisor.Policy(FURNACE_STALL_TIMEOUT_MILLIS, 5, 12);
    private static final ActionSupervisor.Policy MINING_ACTION_POLICY =
            new ActionSupervisor.Policy(15_000L, 2, 4);
    private static final ActionSupervisor.Policy HUNT_ACTION_POLICY =
            new ActionSupervisor.Policy(45_000L, 4, 8);

    enum FoodAcquisitionStep {
        COMPLETE,
        HUNT_RAW,
        ACQUIRE_FUEL,
        CONVERT
    }

    /**
     * Pure decision for one generic-food acquisition tick. The executor first
     * gathers the complete family deficit, then provisions all fuel needed by
     * the currently carried mix, and only then starts conversion. Keeping this
     * decision independent of the live client makes the phase contract
     * deterministic and regression-testable.
     */
    record FoodAcquisitionDecision(
            FoodAcquisitionStep step,
            int rawDeficit,
            int requiredFuel,
            String readyItem,
            int targetReadyCount,
            int conversionUnits) {
        FoodAcquisitionDecision {
            Objects.requireNonNull(step, "step");
            readyItem = Objects.requireNonNullElse(readyItem, "");
            if (rawDeficit < 0 || requiredFuel < 0
                    || targetReadyCount < 0 || conversionUnits < 0) {
                throw new IllegalArgumentException("food acquisition counts cannot be negative");
            }
        }
    }

    /**
     * One food objective's shared furnace-session budget. Regular fuel
     * deliberately combines coal and charcoal because both cover eight
     * operations; a coal block contributes eighty operations to that same
     * continuous session.
     */
    record FoodFuelBudget(int requiredRegularFuel, int carriedRegularFuel) {
        FoodFuelBudget {
            if (requiredRegularFuel < 0 || carriedRegularFuel < 0) {
                throw new IllegalArgumentException("food fuel counts cannot be negative");
            }
        }

        int missingRegularFuel() {
            return Math.max(0, requiredRegularFuel - carriedRegularFuel);
        }

        boolean satisfied() {
            return missingRegularFuel() == 0;
        }
    }

    /** Durable exact-count fence for one compiler-committed furnace action. */
    record CommittedFurnaceFuelBudget(
            int plannedCount,
            int authorizedCount,
            int committedCount) {
        CommittedFurnaceFuelBudget {
            if (plannedCount < 0 || authorizedCount < 0 || committedCount < 0
                    || committedCount > authorizedCount
                    || authorizedCount > plannedCount
                    || authorizedCount - committedCount > 1) {
                throw new IllegalArgumentException(
                        "invalid committed furnace fuel budget");
            }
        }

        boolean hasPendingGrant() {
            return authorizedCount == committedCount + 1;
        }

        boolean exhausted() {
            return committedCount >= plannedCount;
        }

        CommittedFurnaceFuelBudget authorizeNext() {
            if (hasPendingGrant() || authorizedCount >= plannedCount) {
                throw new IllegalStateException("no committed furnace fuel grant is available");
            }
            return new CommittedFurnaceFuelBudget(
                    plannedCount, authorizedCount + 1, committedCount);
        }

        CommittedFurnaceFuelBudget commitPending() {
            if (!hasPendingGrant()) {
                throw new IllegalStateException("no committed furnace fuel grant is pending");
            }
            return new CommittedFurnaceFuelBudget(
                    plannedCount, authorizedCount, authorizedCount);
        }
    }

    /** In-process custody for the one durable grant which may currently click. */
    private record CommittedFurnaceFuelGrant(
            int ordinal,
            boolean transferStarted,
            boolean serverCommitted) {
        private CommittedFurnaceFuelGrant {
            if (ordinal < 1) throw new IllegalArgumentException("fuel grant ordinal must be positive");
            if (serverCommitted && !transferStarted) {
                throw new IllegalArgumentException("unstarted fuel grant cannot be committed");
            }
        }

        private CommittedFurnaceFuelGrant started() {
            return transferStarted ? this
                    : new CommittedFurnaceFuelGrant(ordinal, true, false);
        }

        private CommittedFurnaceFuelGrant committed() {
            return new CommittedFurnaceFuelGrant(ordinal, true, true);
        }
    }

    /** Concrete carried stacks owned by a generic food-family objective. */
    record FoodCargoAllocation(
            Map<String, Integer> readyItems,
            Map<String, Integer> rawInputs,
            Map<String, Integer> sourceInputs) {
        FoodCargoAllocation {
            readyItems = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(readyItems, "readyItems")));
            rawInputs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(rawInputs, "rawInputs")));
            sourceInputs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(sourceInputs, "sourceInputs")));
        }
    }

    private final MinecraftClient client;
    private dev.entity.client.blueprint.BlueprintProjects blueprintProjects;
    public void blueprintProjects(dev.entity.client.blueprint.BlueprintProjects projects) {
        this.blueprintProjects=projects;
    }
    private final FabricBaritonePort baritone;
    private final ExecutionKernel executionKernel;
    private final Logger logger;
    /**
     * Swapped only on the Minecraft client thread when Paper publishes a new
     * authoritative recipe snapshot. A whole planner/catalog pair is replaced
     * atomically so one compilation can never observe half of a recipe reload.
     */
    private volatile ResourcePlanner resourcePlanner = new ResourcePlanner();
    private volatile String resourceCatalogFingerprint = FALLBACK_CATALOG_FINGERPRINT;
    private volatile boolean resourceCatalogReady = true;
    private final TaskPlanCoordinator plans;
    private final ClientInventoryController inventory;
    private final CombatLoadoutCoordinator protectionLoadoutCoordinator =
            new CombatLoadoutCoordinator();
    private final CombatMissionLoadoutGate attackMissionLoadout =
            new CombatMissionLoadoutGate();
    private final CombatLoadoutCoordinator.InventoryPort protectionLoadoutInventory;
    private final WorkstationController workstations;
    private final MinecraftTidyController tidy;
    private final IdleStockToolStore idleWorkingTools;
    private IdleStockCycle idleStockCycle;
    private IdleStockPolicy.State idleStockState = IdleStockPolicy.State.IDLE;
    private String idleStockResultKey = "idle";
    private String idleStockDetail = "No automatic Stock work requested";
    private IdleStockCancellation cancelledIdleStock;
    private final IdleSleepFallback idleSleepFallback = new IdleSleepFallback();
    private final IdleStockRouteRecovery idleStockRouteRecovery;
    private static final class IdleStockCycle {
        final String protectionWorkId = UUID.randomUUID().toString();
        final long homeGeneration;
        final Map<String, Integer> storedTargets;
        final boolean undergroundOnly;
        String category = "";
        boolean acquisitionIssued;
        FabricBaritonePort.IdleUndergroundSite undergroundSite;
        Map<String, Integer> undergroundGoals = Map.of();
        boolean mineEntranceReached;
        IdleStockCycle(long generation, Map<String, Integer> targets, boolean undergroundOnly) {
            this.homeGeneration = generation; this.storedTargets = Map.copyOf(targets); this.undergroundOnly = undergroundOnly;
        }
    }
    private ProtectedAreaClientState protectedAreas;
    private final ClientGroundItemCollectionController groundItems;
    private final DeliveryController deliveries;
    private final InventoryReservationLedger reservations = new InventoryReservationLedger();
    private final ActionSupervisor actionSupervisor = new ActionSupervisor();
    private final AutonomySettingsStore settingsStore;
    private AutonomySettingsStore.Settings settings;
    private AutonomySettingsStore.LoadState settingsLoadState;
    private String settingsLoadDetail;
    private final HomeEconomySession homeEconomy;
    private final String homeEconomyLoadFailure;
    private final HomeWaterFillSession homeWaterFill;
    private final String homeWaterFillLoadFailure;
    private boolean missionWaterFenceFailure;
    private boolean homeRunRequested;
    private boolean homeSleepRequested;
    private long verifiedHomeSleepCycles;
    private boolean homeScreenClosePending;
    private boolean homeManualTransferReobserve;
    private boolean homeManualTransferAbandonIfUnchanged;
    private HomeControlPolicy.Preview homeControlPreview;
    private volatile HomeFurnitureBindingPolicy.Preview homeFurniturePreview;
    private volatile HomeFurnitureBindingPolicy.Preview homeStoragePreview;
    private HomeFurnitureAdoptionPolicy.Preview homeAdoptionPreview;
    private String activeHomeStorageId = "";
    private final Map<String, ObservedHomeStorage> homeStorageObservations = new LinkedHashMap<>();
    private HomeStockForecastInput homeStockForecastInput;
    private List<HomeStockPolicy.Target> homeStockForecastTargets = HomeStockPolicy.defaults();
    private String lastHomeEconomyDetail = "";
    private Map<String, Integer> lastHomeChestCounts = Map.of();
    private String lastHomeChestFingerprint = "";
    private long lastHomeChestObservedAt = -1L;
    private String homeWaterAimIntentId = "";
    private int homeWaterAimPlayerAge = -1;
    private String homeWaterApproachIntentId = "";
    private BlockPos homeWaterApproachStand;
    private BlockPos homeWaterRejectedStand;
    private long homeWaterApproachCompletedAt = Long.MIN_VALUE;
    private final Map<String, String> recoveryPlanByMission = new LinkedHashMap<>();
    private final Map<String, PersonalSuppliesPolicy.Selection> capacityDrops = new LinkedHashMap<>();
    private final Map<String, Integer> capacityAimAges = new LinkedHashMap<>();
    private final Map<String, DeliveryReceiptState> deliveryReceipts = new LinkedHashMap<>();
    private final Map<String, DeliveryReturnState> deliveryReturns = new LinkedHashMap<>();
    private final Map<String, DeliveryPrepareState> deliveryPreparations = new LinkedHashMap<>();
    private final Map<String, Long> deliveryPrepareSentAt = new LinkedHashMap<>();
    private final Map<String, DeliveryProtocol.CommitResult> deliveryCommitResults =
            new LinkedHashMap<>();
    private final Map<String, Long> deliveryCommitSentAt = new LinkedHashMap<>();
    /** Ephemeral actuator segments rebuilt from each frame's durable inventory baseline. */
    private final Map<MiningSessionKey, MiningSession> miningSessions = new LinkedHashMap<>();
    /**
     * Ephemeral half of a durable before-click fuel grant. Its absence after a
     * restart makes an outstanding grant fail closed and forces a truth-based
     * universal recompile instead of authorizing the same item twice.
     */
    private final Map<String, CommittedFurnaceFuelGrant> committedFurnaceFuelGrants =
            new LinkedHashMap<>();
    private Predicate<JsonObject> protocolSender = ignored -> false;
    private Predicate<String> protocolFeature = ignored -> false;
    private BooleanSupplier foodConsumptionPending = () -> false;
    private boolean deliveryPendingInvalidated;
    private long lastClientTick;
    private Map<String, Integer> lastAliveInventoryForDeathRecovery = Map.of();
    private long lastAliveInventoryObservedAt = Long.MIN_VALUE;
    private int lastLocalSourceScanAge = Integer.MIN_VALUE;
    private Set<String> cachedLocalSources = Set.of();
    private long localSourcePerceptionRevision = Long.MIN_VALUE;
    private long protectionLoadoutGeneration;
    private boolean protectionLoadoutEpisodeOpen;
    private boolean protectionLoadoutCleanupPending;
    private boolean attackLoadoutCleanupPending;
    private long protectionLoadoutCleanupStartedAt = Long.MIN_VALUE;
    private long attackLoadoutCleanupStartedAt = Long.MIN_VALUE;
    private String attackLoadoutCleanupMissionId = "";

    public AutonomyExecutor(
            MinecraftClient client,
            FabricBaritonePort baritone,
            ExecutionKernel executionKernel,
            ForeignItemClaimClientState foreignItems,
            Path dataDirectory,
            Logger logger) throws IOException {
        this.client = Objects.requireNonNull(client, "client");
        this.baritone = Objects.requireNonNull(baritone, "baritone");
        this.executionKernel = Objects.requireNonNull(executionKernel, "executionKernel");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.plans = new TaskPlanCoordinator(new JsonPlanStore(dataDirectory.resolve("task-plans.json")));
        this.inventory = new ClientInventoryController(client);
        this.protectionLoadoutInventory = new ProtectionLoadoutInventoryPort();
        this.workstations = new WorkstationController(client, inventory, baritone, dataDirectory);
        this.groundItems = new ClientGroundItemCollectionController(
                new MinecraftGroundItemAdapter(client, foreignItems, logger, baritone.resourcePerception()), baritone);
        this.deliveries = new DeliveryController(client, inventory);
        this.settingsStore = new AutonomySettingsStore(dataDirectory.resolve("autonomy.json"));
        AutonomySettingsStore.LoadResult settingsLoad = settingsStore.load();
        this.settings = settingsLoad.settings();
        this.settingsLoadState = settingsLoad.state();
        this.settingsLoadDetail = settingsLoad.detail();
        if (!settingsLoadState.authoritative()) {
            logger.error(homeSettingsQuarantineStatus());
        }
        HomeEconomySession restoredHome = null;
        String homeLoadFailure = "";
        try {
            restoredHome = new HomeEconomySession(new AtomicHomeEconomyStore(
                    dataDirectory.resolve("home-economy.bin")));
        } catch (IOException | RuntimeException error) {
            homeLoadFailure = "Home economy state is unreadable; no Home action will run: "
                    + Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
            logger.error(homeLoadFailure, error);
        }
        this.homeEconomy = restoredHome;
        this.homeEconomyLoadFailure = homeLoadFailure;
        HomeWaterFillSession restoredWaterFill = null;
        String waterFillLoadFailure = "";
        try {
            restoredWaterFill = new HomeWaterFillSession(new AtomicHomeWaterFillStore(
                    dataDirectory.resolve("home-water-fill.bin")));
            restoredWaterFill.fenceMission(System.currentTimeMillis());
        } catch (IOException | RuntimeException error) {
            waterFillLoadFailure = "Home water-fill state is unreadable; automatic bucket use "
                    + "is disabled: "
                    + Objects.requireNonNullElse(
                    error.getMessage(), error.getClass().getSimpleName());
            logger.error(waterFillLoadFailure, error);
        }
        this.homeWaterFill = restoredWaterFill;
        this.homeWaterFillLoadFailure = waterFillLoadFailure;
        this.tidy = new MinecraftTidyController(client, inventory, workstations, baritone, executionKernel,
                dataDirectory, () -> homeEconomy == null ? null : homeEconomy.snapshot(),
                () -> protectedAreas, this::personalSupplyClaims,
                () -> !committedFurnaceFuelGrants.isEmpty() || reservations.reservations().values().stream()
                        .anyMatch(reservation -> reservation.purpose() == Purpose.FUEL), this::acquireHomeAction);
        this.idleWorkingTools = new IdleStockToolStore(dataDirectory, tidy.worldId());
        this.idleStockRouteRecovery = new IdleStockRouteRecovery(dataDirectory, tidy.worldId());
        if (!idleStockRouteRecovery.problem().isBlank()) logger.warn(idleStockRouteRecovery.problem());
        if (homeEconomy != null) {
            try {
                String reconciliation = reconcileHomeStores(System.currentTimeMillis());
                if (settingsLoadState.authoritative()) {
                    lastHomeEconomyDetail = reconciliation;
                }
            } catch (IOException | RuntimeException error) {
                logger.warn("Could not reconcile Home settings/cursor state at startup: {}",
                        error.getMessage());
            }
        }
        retireUnreferencedInternalTerminalPlans(System.currentTimeMillis());
        restoreRecoveryIndex();
    }

    public boolean handles(Mission mission) {
        if (mission == null) return false;
        String kind = mission.kind().toLowerCase(Locale.ROOT);
        return SELF_SUFFICIENT_KINDS.contains(kind)
                || kind.equals("mine")
                || kind.equals("farm")
                || kind.equals("build")
                || recoveryPlanByMission.containsKey(mission.id());
    }

    /** Installs the authenticated bridge transport after runtime construction. */
    public void setProtocolSender(Predicate<JsonObject> protocolSender) {
        this.protocolSender = Objects.requireNonNull(protocolSender, "protocolSender");
        tidy.setCommandResultSender(protocolSender);
    }

    public void setProtocolFeatureChecker(Predicate<String> protocolFeature) {
        this.protocolFeature = Objects.requireNonNull(protocolFeature, "protocolFeature");
    }

    public void installProtectedAreaPolicy(ProtectedAreaClientState protectedAreas) {
        this.protectedAreas = Objects.requireNonNull(protectedAreas, "protectedAreas");
        workstations.installProtectedAreaPolicy(protectedAreas);
    }

    /** Installs one immutable authoritative recipe/source catalog. */
    public void installResourceCatalog(ResourceCatalog catalog) {
        installResourceCatalog(catalog, FALLBACK_CATALOG_FINGERPRINT);
    }

    /** Installs one immutable catalog together with its authenticated identity. */
    public void installResourceCatalog(ResourceCatalog catalog, String catalogFingerprint) {
        resourcePlanner = new ResourcePlanner(Objects.requireNonNull(catalog, "catalog"));
        if (catalogFingerprint == null || catalogFingerprint.isBlank()) {
            throw new IllegalArgumentException("catalogFingerprint cannot be blank");
        }
        resourceCatalogFingerprint = catalogFingerprint.trim();
        resourceCatalogReady = true;
        lastLocalSourceScanAge = Integer.MIN_VALUE;
        cachedLocalSources = Set.of();
    }

    /** Pauses universal execution while an authenticated connection reloads its catalog. */
    public void suspendResourceCatalog() {
        resourceCatalogReady = false;
        lastLocalSourceScanAge = Integer.MIN_VALUE;
        cachedLocalSources = Set.of();
    }

    public ResourceCatalog resourceCatalog() {
        return resourcePlanner.catalog();
    }

    /** Supplies the direct body's cross-tick food-use synchronization fence. */
    public void setFoodConsumptionPending(BooleanSupplier foodConsumptionPending) {
        this.foodConsumptionPending = Objects.requireNonNull(
                foodConsumptionPending, "foodConsumptionPending");
    }

    public InventoryTransactionEngine.Diagnostics inventoryTransactionDiagnostics() {
        return inventory.transactionDiagnostics();
    }

    /** Safe idle inventory boundary; active Home work and survival keep their owners. */
    public boolean emptyArmorNeedsIdleLease(long nowMillis) {
        return client.player != null && !client.player.isUsingItem() && !client.player.isSleeping()
                && !foodConsumptionPending.getAsBoolean()
                && !homeEconomyNeedsIdleLease(nowMillis) && !homeEconomyNeedsForegroundFence()
                && !homePreemptionDrainPending() && inventory.emptyArmorNeedsAttention();
    }

    public void tickIdleArmorEquipment(ControlLease parent, long clientTick, long nowMillis) {
        if (!emptyArmorNeedsIdleLease(nowMillis)) return;
        Optional<ActionLease> action = acquireHomeAction(parent, ActionOwner.INVENTORY_TRANSACTION,
                "idle-empty-armor", clientTick, nowMillis);
        if (action.isPresent()) inventory.equipEmptyArmor(true, nowMillis);
    }

    /** Passive per-client-tick reconciliation for terminal inventory clicks. */
    public void reconcileInventoryTransactions(long nowMillis) throws IOException {
        // Learn only actual carried craftable tiers, including a worn tool before it disappears.
        // This existing per-tick observation does not grant any automatic work authority.
        try { idleWorkingTools.observe(inventory.counts()); }
        catch (IOException error) { logger.warn("Idle tool replacement memory disabled: {}", error.getMessage()); }
        inventory.reconcilePendingTransaction(nowMillis);
        settleCancelledIdleStock(nowMillis);
        tidy.observe(nowMillis);
        observeFencedMissionWaterFill(nowMillis);
        // Home close/drain is advanced only by its custody-aware owner. This
        // passive hook may settle an acknowledgement, but never moves a cursor.
        drainProtectionLoadoutCleanup(nowMillis);
        drainAttackLoadoutCleanup(nowMillis);
    }

    public void acceptDeliveryPrepared(
            String missionId, String nonce, boolean accepted, String reason) {
        if (missionId == null || missionId.isBlank() || nonce == null || nonce.isBlank()) return;
        deliveryPreparations.put(
                deliveryTransactionKey(missionId, nonce),
                new DeliveryPrepareState(accepted, Objects.requireNonNullElse(reason, "")));
    }

    public void acceptDeliveryCommitResult(DeliveryProtocol.CommitResult result) {
        Objects.requireNonNull(result, "result");
        deliveryCommitResults.put(
                deliveryTransactionKey(result.missionId(), result.nonce()), result);
    }

    public Outcome tick(
            Mission mission,
            ControlLease lease,
            long clientTick,
            long nowMillis) throws IOException {
        Objects.requireNonNull(mission, "mission");
        Objects.requireNonNull(lease, "lease");
        if (clientTick < lastClientTick) {
            throw new IllegalArgumentException("clientTick cannot move backwards");
        }
        lastClientTick = clientTick;
        refreshMissionReservations(mission);
        String recoveryKey = recoveryPlanByMission.get(mission.id());
        if (recoveryKey != null) {
            String recoveryRootKind = plans.restore(recoveryKey)
                    .filter(plan -> !plan.frames().isEmpty())
                    .map(plan -> plan.frames().getFirst().kind())
                    .orElse("");
            Outcome recovery = tickPlan(
                    recoveryKey, mission, lease, clientTick, nowMillis, false);
            if (recovery.state() == State.PLAN_COMPLETE) {
                String completed;
                if (recoveryRootKind.equals(MissionDeathRecoveryPolicy.SIDECAR_ROOT_KIND)) {
                    completed = recovery.detail().isBlank()
                            ? MissionDeathRecoveryPolicy.resumeDetail(
                                    mission.kind(),
                                    MissionDeathRecoveryPolicy.restoreTerminalResult(Map.of()))
                            : recovery.detail();
                } else {
                    completed = "restocked traversal supplies; resuming " + mission.kind();
                }
                plans.retireTerminalAfterOwnerCommit(
                        recoveryKey,
                        "parent-mission:" + mission.id() + ":accepted-recovery-result",
                        nowMillis);
                recoveryPlanByMission.remove(mission.id());
                return new Outcome(
                        State.RESUME_LEGACY,
                        completed,
                        Double.NaN,
                        recovery.completedWorkUnits(),
                        false,
                        BaritonePort.FailureCause.NONE);
            }
            return recovery;
        }

        String kind = mission.kind().toLowerCase(Locale.ROOT);
        if (!SELF_SUFFICIENT_KINDS.contains(kind)
                && !kind.equals("mine") && !kind.equals("farm") && !kind.equals("build")) {
            return Outcome.resumeLegacy("mission remains a direct Baritone action");
        }
        ensureMissionPlan(mission, nowMillis);
        try {
            return tickPlan(mission.id(), mission, lease, clientTick, nowMillis, true);
        } catch (DeliverySuppliesOrder.WorkingKitUnavailableException uncertainKit) {
            return Outcome.blocked("delivery working-kit identity unavailable: " + uncertainKit.getMessage());
        }
    }

    public String planSummary(String missionId) {
        if (missionId != null && !missionId.isBlank()) {
            Optional<TaskPlan> exact = plans.restore(missionId);
            if (exact.isPresent()) {
                TaskPlan plan = exact.orElseThrow();
                return isHomeMaintenancePlan(plan)
                        ? "Home maintenance plan (not a user mission): " + plan.summary()
                        : plan.summary();
            }
            String recovery = recoveryPlanByMission.get(missionId);
            if (recovery != null) return plans.summary(recovery);
            return "No local task plan exists for mission " + missionId;
        }
        Optional<TaskPlan> active = plans.plans().stream()
                .filter(plan -> plan.state() == TaskPlanState.OPEN)
                .filter(plan -> !isHomeMaintenancePlan(plan))
                .reduce((left, right) -> right);
        return active.map(TaskPlan::summary).orElse("No active hierarchical task plan");
    }

    private static boolean isHomeMaintenancePlan(TaskPlan plan) {
        return Boolean.parseBoolean(plan.rootSpec().parameters()
                .getOrDefault("homeMaintenance", "false"));
    }

    /**
     * Retires only plans whose mission outcome has already committed to the
     * durable MissionStore. This is safe to call repeatedly during startup.
     */
    public void retireJournaledMissionPlans(
            Collection<Mission> journaledMissions,
            long nowMillis) throws IOException {
        Objects.requireNonNull(journaledMissions, "journaledMissions");
        for (Mission mission : journaledMissions) {
            if (mission.state().terminal()) {
                retirePlanIfTerminal(
                        mission.id(),
                        "mission-journal:" + mission.state().name().toLowerCase(Locale.ROOT)
                                + ":r" + mission.revision(),
                        nowMillis);
            }
        }
    }

    /** Called immediately after EntityCore durably commits one terminal mission. */
    public void retireMissionPlanAfterOwnerCommit(
            Mission mission,
            long nowMillis) throws IOException {
        Objects.requireNonNull(mission, "mission");
        if (!mission.state().terminal()) {
            throw new IllegalArgumentException("mission journal is not terminal");
        }
        retirePlanIfTerminal(
                mission.id(),
                "mission-journal:" + mission.state().name().toLowerCase(Locale.ROOT)
                        + ":r" + mission.revision(),
                nowMillis);
    }

    private void retirePlanIfTerminal(
            String planId,
            String ownerReceipt,
            long nowMillis) throws IOException {
        Optional<TaskPlan> plan = plans.restore(planId);
        if (plan.isEmpty() || plan.orElseThrow().state() == TaskPlanState.OPEN) return;
        plans.retireTerminalAfterOwnerCommit(planId, ownerReceipt, nowMillis);
    }

    /**
     * Existing internal sidecars have their own durable owner journals. A
     * terminal Home plan remains live only while Home still names it; completed
     * recovery sidecars were already ignored by restart reconciliation.
     */
    private void retireUnreferencedInternalTerminalPlans(long nowMillis) {
        String pendingHomePlan = homeEconomy == null
                || homeEconomy.snapshot().pendingAcquisition() == null
                ? ""
                : homeEconomy.snapshot().pendingAcquisition().planId();
        for (TaskPlan plan : plans.plans()) {
            if (!plan.state().terminal() || plan.state() == TaskPlanState.RETIRED) continue;
            boolean unreferencedHome = isHomeMaintenancePlan(plan)
                    && !plan.missionId().equals(pendingHomePlan);
            boolean completedRecoverySidecar = plan.rootSpec().kind().equals("root.restock")
                    || plan.rootSpec().kind().equals(MissionDeathRecoveryPolicy.SIDECAR_ROOT_KIND);
            if (!unreferencedHome && !completedRecoverySidecar) continue;
            try {
                plans.retireTerminalAfterOwnerCommit(
                        plan.missionId(),
                        unreferencedHome
                                ? "home-journal:no-longer-pending"
                                : "recovery-journal:terminal-sidecar-observed",
                        nowMillis);
            } catch (IOException | RuntimeException error) {
                logger.warn("Could not retire terminal internal plan {}: {}",
                        plan.missionId(), error.getMessage());
            }
        }
    }

    /**
     * Refreshes the derived mission ledger before the core decides whether
     * survival may consume an inventory item. The map contains cargo that an
     * ordinary hunger recovery must not spend.
     */
    public Map<String, Integer> survivalProtectedReservations(Mission mission) {
        refreshMissionReservations(mission);
        // An acquiring Get food root partitions its own useful ration before
        // this snapshot. Delivery cargo and other production inputs retain the
        // existing critical-only fallback; no global hunger threshold changes.
        return reservations.snapshot(Set.of(Purpose.MISSION_CARGO, Purpose.PLAN_COMMITMENT));
    }

    public String stockStatus() {
        long nowMillis = System.currentTimeMillis();
        Map<String, Integer> playerCounts = inventory.homePlayerCounts();
        if (homeEconomy == null) {
            return appendHomeSettingsSafety(HomeStockStatusPolicy.render(
                    settings.stockEnabled(), HomeEconomySession.Phase.DISABLED,
                    playerCounts, Map.of(), HomeStockStatusPolicy.ChestTruth.UNOBSERVED,
                    -1L, nowMillis, homeEconomyLoadFailure));
        }
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        Map<String, Integer> chestCounts = Map.of();
        HomeStockStatusPolicy.ChestTruth chestTruth =
                HomeStockStatusPolicy.ChestTruth.UNOBSERVED;
        HomeEconomySession.PinnedAsset pinned = snapshot.pinnedAssets().get(
                HomeEconomySession.AssetRole.CHEST);
        boolean exactOpen = snapshot.home() != null && pinned != null
                && workstations.exactPinnedScreenOpen(
                WorkstationController.Kind.CHEST,
                homeWorkstationOperation(snapshot, HomeEconomySession.AssetRole.CHEST),
                pinned.ledgerAssetId(), snapshot.home(), HOME_ASSET_RADIUS);
        if (exactOpen) {
            ClientInventoryController.HomeChestSnapshot observed =
                    inventory.homeChestSnapshot(homeProtectedCounts());
            if (!observed.cursorEmpty() || snapshot.pendingTransfer() != null) {
                chestTruth = HomeStockStatusPolicy.ChestTruth.IN_PROGRESS;
            } else if (observed.open()) {
                chestCounts = observed.chestCounts();
                chestTruth = HomeStockStatusPolicy.ChestTruth.LIVE;
            }
        } else if (snapshot.home() != null
                && snapshot.home().fingerprint().equals(lastHomeChestFingerprint)
                && lastHomeChestObservedAt >= 0L) {
            chestCounts = lastHomeChestCounts;
            chestTruth = HomeStockStatusPolicy.ChestTruth.STALE;
        }
        String blocker = snapshot.phase() == HomeEconomySession.Phase.BLOCKED
                && snapshot.block() != null
                ? snapshot.block().detail()
                : snapshot.phase() == HomeEconomySession.Phase.DEGRADED_READY
                ? "water_bucket: 0/1 (no verified fill source)"
                : "";
        return appendHomeSettingsSafety(HomeStockStatusPolicy.render(
                snapshot.enabled(), snapshot.phase(), playerCounts, chestCounts,
                chestTruth, lastHomeChestObservedAt, nowMillis, blocker)
                + homeOperationStatusSuffix(snapshot));
    }

    public String homeStatus() {
        if (homeEconomy == null) return appendHomeSettingsSafety(homeEconomyLoadFailure);
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        String anchor = snapshot.home() == null
                ? "unset"
                : snapshot.home().x() + " " + snapshot.home().y() + " "
                + snapshot.home().z() + " in " + snapshot.home().dimension();
        String water = snapshot.phase() == HomeEconomySession.Phase.DEGRADED_READY
                ? ", water_bucket: 0/1 (no verified fill source)"
                : "";
        String detail = lastHomeEconomyDetail.isBlank()
                ? "" : ", detail=" + lastHomeEconomyDetail;
        return appendHomeSettingsSafety("home=" + anchor
                + ", economy=" + (snapshot.enabled() ? "on" : "off")
                + ", phase=" + snapshot.phase().name().toLowerCase(Locale.ROOT)
                + ", assets=[" + renderHomeAssetDiagnostics(snapshot) + ']'
                + homeOperationStatusSuffix(snapshot) + water + detail);
    }

    /** Read-only companion facts use the same food family, personal allocation and Home journal as gameplay. */
    public JsonObject companionSelfFacts(Mission mission, long nowMillis) {
        Map<String,Integer> carried = inventory.homePlayerCounts();
        var ready = FoodFamilyPolicy.summary(carried);
        int cooked=0, raw=0;
        for(var item:carried.entrySet()) {
            if(FoodFamilyPolicy.isReadyFood(item.getKey())
                    && (item.getKey().replace("minecraft:","").startsWith("cooked_")
                    || item.getKey().replace("minecraft:","").equals("baked_potato"))) cooked+=item.getValue();
            if(FoodFamilyPolicy.memberForRawItem(item.getKey()).isPresent()) raw+=item.getValue();
        }
        // Allocation is the existing personal-supplies calculation; do not change its reserve or cargo rules.
        var claims = List.copyOf(reservations.reservations().values());
        // Exempt only this delivery's cargo, exactly as its existing allocation does.
        // Transaction cargo is not the bot's personal food reserve.
        Set<String> outgoing = mission == null ? Set.of()
                : DeliverySuppliesOrder.outgoingCargoIds(mission.id(), claims);
        var allocation = PersonalSuppliesPolicy.allocate(inventory.personalSupplies().playerStacks(),
                retainedPersonalProfile(), claims, outgoing);
        int retained=FoodFamilyPolicy.summary(allocation.retainedCounts()).readyCount();
        Map<String,Integer> available=new LinkedHashMap<>();
        for(var stack:allocation.stacks()) if(stack.availableCount()>0)
            available.merge(stack.stack().item(),stack.availableCount(),Integer::sum);
        JsonObject food=new JsonObject();
        food.addProperty("cooked_count",cooked); food.addProperty("raw_count",raw);
        food.addProperty("other_edible_count",Math.max(0,ready.readyCount()-cooked));
        food.addProperty("reserved_count",Math.min(ready.readyCount(),retained));
        food.addProperty("deliverable_count",FoodFamilyPolicy.summary(available).readyCount());
        JsonObject result=new JsonObject(); result.addProperty("observed_at_millis",nowMillis);
        result.add("food_summary",food);
        if(mission!=null) {
            result.addProperty("mission_id",mission.id());
            plans.restore(mission.id()).ifPresent(plan -> {
                if(!plan.frames().isEmpty()) {
                    var root=plan.frames().getFirst();
                    if(root.parameters().containsKey("deliveredTotal"))
                        result.addProperty("delivered_count",integer(root.parameters(),"deliveredTotal",0));
                }
            });
        }
        if(homeEconomy!=null) {
            JsonObject home=new JsonObject(); var anchor=homeEconomy.snapshot().home();
            home.addProperty("registered",anchor!=null); home.addProperty("observed_at_millis",nowMillis);
            if(anchor!=null) {
                home.addProperty("x",anchor.x()); home.addProperty("y",anchor.y()); home.addProperty("z",anchor.z());
                home.addProperty("dimension",anchor.dimension());
            }
            result.add("home",home);
        }
        return result;
    }

    private String renderHomeAssetDiagnostics(HomeEconomySession.Snapshot snapshot) {
        ArrayList<String> rendered = new ArrayList<>();
        for (HomeEconomySession.AssetRole role : HomeEconomySession.AssetRole.values()) {
            HomeEconomySession.PinnedAsset pinned = snapshot.pinnedAssets().get(role);
            String id = pinned == null ? "" : pinned.ledgerAssetId();
            WorkstationController.HomeAssetDiagnostic diagnostic =
                    workstations.diagnosePinnedHomeAsset(
                            workstationKind(role), id, snapshot.home(), HOME_ASSET_RADIUS);
            String position = diagnostic.positionKnown()
                    ? diagnostic.dimension() + ' ' + diagnostic.x() + ' '
                    + diagnostic.y() + ' ' + diagnostic.z()
                    : "position=unknown";
            rendered.add(role.item() + "{id="
                    + (diagnostic.ledgerAssetId().isBlank()
                    ? "none" : diagnostic.ledgerAssetId())
                    + ", at=" + position
                    + ", truth=" + diagnostic.truth().name().toLowerCase(Locale.ROOT)
                    + ", detail=" + diagnostic.detail() + '}');
        }
        return String.join("; ", rendered);
    }

    private String homeOperationStatusSuffix(HomeEconomySession.Snapshot snapshot) {
        InventoryTransactionEngine.Diagnostics diagnostics =
                inventory.transactionDiagnostics();
        String cursor = inventory.cursorEmpty() ? "empty" : "occupied";
        String owners = diagnostics.pendingClickOwner().isBlank()
                && diagnostics.cursorOwner().isBlank()
                ? "none"
                : "click=" + (diagnostics.pendingClickOwner().isBlank()
                ? "none" : diagnostics.pendingClickOwner())
                + "/cursor=" + (diagnostics.cursorOwner().isBlank()
                ? "none" : diagnostics.cursorOwner());
        if (snapshot.pendingTransfer() != null) {
            HomeEconomySession.PendingTransfer pending = snapshot.pendingTransfer();
            return ", pending-transfer={id=" + pending.id()
                    + ", direction=" + pending.direction().name().toLowerCase(Locale.ROOT)
                    + ", item=" + pending.item() + ", remaining=" + pending.count()
                    + ", cursor=" + cursor + ", transaction-owner=" + owners + '}';
        }
        if (snapshot.pendingAcquisition() != null) {
            HomeEconomySession.PendingAcquisition pending = snapshot.pendingAcquisition();
            return ", pending-acquisition={plan=" + pending.planId()
                    + ", goals=" + pending.goals()
                    + ", cursor=" + cursor + ", transaction-owner=" + owners + '}';
        }
        if (pendingHomeWaterFill()) {
            HomeWaterFillSession.Intent pending = homeWaterFill.snapshot().intent();
            return ", pending-water-fill={id=" + pending.id()
                    + ", source=" + pending.dimension() + ' ' + pending.sourceX() + ' '
                    + pending.sourceY() + ' ' + pending.sourceZ()
                    + ", phase=" + pending.phase().name().toLowerCase(Locale.ROOT)
                    + ", attempts=" + pending.attempts()
                    + ", empty-before=" + pending.emptyBucketBefore()
                    + ", water-before=" + pending.waterBucketBefore()
                    + ", selection=" + pending.hotbarSlot() + "->"
                    + pending.previousSelectedSlot()
                    + ", cursor=" + cursor + ", transaction-owner=" + owners + '}';
        }
        if (!inventory.cursorEmpty() || !diagnostics.pendingClickOwner().isBlank()
                || !diagnostics.cursorOwner().isBlank()) {
            return ", inventory-recovery={cursor=" + cursor
                    + ", transaction-owner=" + owners + '}';
        }
        return "";
    }

    private boolean pendingHomeWaterFill() {
        return homeWaterFill != null && homeWaterFill.snapshot().intent() != null
                && homeWaterFill.snapshot().intent().homeCaller();
    }

    public Optional<HomeEconomySession.HomeAnchor> homeAnchor() {
        if (homeEconomy != null && homeEconomy.snapshot().home() != null) {
            return Optional.of(homeEconomy.snapshot().home());
        }
        return Optional.ofNullable(configuredHome());
    }

    /** Exact loaded owned asset cells for creeper positioning, not a Home exclusion radius. */
    public List<dev.entity.core.stewardship.PropertyCreeperSafetyPolicy.Region> ownedAssetCreeperContexts() {
        if (homeEconomy == null || client.world == null || !settingsLoadState.authoritative()) return List.of();
        var snapshot = homeEconomy.snapshot();
        if (!snapshot.enabled() || snapshot.home() == null) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        snapshot.pinnedAssets().values().forEach(pin -> ids.add(pin.ledgerAssetId()));
        snapshot.storageRegistrations().values().forEach(storage -> ids.add(storage.ledgerAssetId()));
        List<dev.entity.core.stewardship.PropertyCreeperSafetyPolicy.Region> result = new ArrayList<>();
        for (String id : ids) {
            FieldKitLedger.Asset asset = workstations.ownedAsset(id).orElse(null);
            if (asset == null || asset.state() != FieldKitLedger.AssetState.PLACED
                    || asset.verification() == FieldKitLedger.Verification.NONE
                    || asset.lastKnownPosition() == null) continue;
            var position = asset.lastKnownPosition();
            BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
            if (!position.dimension().equals(currentDimension()) || !client.world.isChunkLoaded(pos)) continue;
            String block = Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).getPath();
            if (!block.equals(asset.kind().item())) continue;
            result.add(dev.entity.core.stewardship.PropertyCreeperSafetyPolicy.Region.workContext(
                    "owned " + asset.kind().item() + " " + id, position.dimension(),
                    position.x(), position.x(), position.z(), position.z()));
        }
        return List.copyOf(result);
    }

    /** Read-only destination of actual interrupted work, never an idle/new objective. */
    public Optional<BlockPos> interruptedWorkDestination(Mission mission) {
        if (client.world == null) return Optional.empty();
        String dimension = client.world.getRegistryKey().getValue().toString();
        if (mission != null && "build".equalsIgnoreCase(mission.kind())
                && blueprintProjects != null && blueprintProjects.confirmed()) {
            var project = blueprintProjects.project();
            if (project != null && project.dimension().equals(dimension)
                    && project.projectId().equals(mission.parameters().get("projectId"))
                    && project.digest().equals(mission.parameters().get("digest"))) {
                return Optional.of(project.anchor());
            }
        }
        if (mission == null && (homeSleepRequested || homeRunRequested)) {
            return homeAnchor().filter(home -> home.dimension().equals(dimension))
                    .map(home -> new BlockPos(home.x(), home.y(), home.z()));
        }
        return Optional.empty();
    }

    /** Read-only incident identity; internal Home routes need not have a core mission. */
    public String homeRouteFailureIncident() {
        if (homeEconomy == null) return "";
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        HomeEconomySession.Block block = snapshot.block();
        if (block == null || !"home_route_blocked".equals(block.code())) return "";
        return "Home generation " + snapshot.generation() + ", blockedAt=" + block.blockedAtMillis()
                + ", retry=" + block.retryPhase() + ": " + block.detail();
    }

    private long completedExplicitStockCycles;

    public record AutomaticStockProtectionWork(String worldId,String identity,String planSummary) { }

    /** Observation of one already-admitted finite cycle; never grants native work or inventory authority. */
    public Optional<AutomaticStockProtectionWork> automaticStockProtectionWork() {
        IdleStockCycle cycle=idleStockCycle;
        if(cycle==null||!homeRunRequested||homeSleepRequested||homeEconomy==null
                ||!settingsLoadState.authoritative())return Optional.empty();
        var snapshot=homeEconomy.snapshot();
        if(!snapshot.enabled()||snapshot.home()==null||snapshot.generation()!=cycle.homeGeneration
                ||snapshot.phase()==HomeEconomySession.Phase.BLOCKED)return Optional.empty();
        String worldId=tidy.worldId();
        String identity="automatic-stock:"+worldId+":"+cycle.homeGeneration+":"+cycle.protectionWorkId;
        String summary=snapshot.pendingAcquisition()==null?"":planSummary(snapshot.pendingAcquisition().planId());
        return Optional.of(new AutomaticStockProtectionWork(worldId,identity,summary));
    }

    /** Observation only; the existing Home idle lease remains the single actuator owner. */
    public dev.entity.client.runtime.FiniteStockCommands.Observation stockCommandObservation() {
        HomeEconomySession.Snapshot snapshot = homeEconomy == null ? null : homeEconomy.snapshot();
        InventoryTransactionEngine.Diagnostics transactions = inventory.transactionDiagnostics();
        boolean settled = !homeScreenClosePending && !pendingHomeWaterFill()
                && client.player != null && client.player.currentScreenHandler == client.player.playerScreenHandler
                && !tidy.needsLease() && !tidy.drainPending() && inventory.cursorEmpty()
                && transactions.pendingClickOwner().isBlank() && transactions.cursorOwner().isBlank()
                && transactions.pendingRemovals() == 0
                && (snapshot == null || snapshot.pendingTransfer() == null && snapshot.pendingAcquisition() == null);
        return new dev.entity.client.runtime.FiniteStockCommands.Observation(
                snapshot == null ? 0 : snapshot.generation(), completedExplicitStockCycles,
                homeRunRequested, settled, snapshot == null || snapshot.block() == null ? 0 : snapshot.block().blockedAtMillis(),
                lastHomeEconomyDetail);
    }

    /** Passive immutable view for Runtime's quiet-time coordinator; never opens a chest or saves state. */
    public IdleStockPolicy.Observation autonomousStockObservation(long nowMillis) {
        var snapshot = homeEconomy == null ? null : homeEconomy.snapshot();
        Map<String, Integer> carried = inventory.homePlayerCounts();
        boolean home = settingsLoadState.authoritative() && snapshot != null && snapshot.home() != null;
        boolean storage = home && !snapshot.storageExplicitlyMissing() && !snapshot.storageRegistrations().isEmpty();
        boolean sameHome = home && snapshot.home().fingerprint().equals(lastHomeChestFingerprint);
        boolean fresh = sameHome && lastHomeChestObservedAt >= 0 && nowMillis - lastHomeChestObservedAt <= 30_000L
                && homeStorageObservations.keySet().containsAll(snapshot.storageRegistrations().keySet());
        Map<String, Integer> tools = new LinkedHashMap<>();
        for (String category : List.of("pickaxe", "axe", "weapon", "shield"))
            tools.put(category, IdleStockPolicy.serviceableToolCount(category, carried, idleWorkingTools.tools()));
        var stock = stockCommandObservation();
        return new IdleStockPolicy.Observation(snapshot == null ? 0 : snapshot.generation(), home, storage,
                settings.stockEnabled(), carried, sameHome ? lastHomeChestCounts : Map.of(),
                sameHome ? lastHomeChestObservedAt : -1, fresh, tools, idleWorkingTools.tools(),
                idleStockCycle != null && homeRunRequested, stock.settled(), completedExplicitStockCycles,
                idleStockState, idleStockResultKey, idleStockDetail);
    }

    /**
     * Passive, loaded-world admission facts for recurring managed-farm work.
     * This method never moves, opens storage, changes a block, or persists a
     * decision. Runtime owns throttling and the durable before-submit fence.
     */
    public ManagedFarmAutomaticObservation automaticFarmObservation(
            String requestedArea,
            String expectedFingerprint,
            long nowMillis) {
        String areaName = Objects.requireNonNullElse(requestedArea, "")
                .trim().toLowerCase(Locale.ROOT);
        String fingerprint = Objects.requireNonNullElse(expectedFingerprint, "").trim();
        if (protectedAreas == null) {
            return ManagedFarmAutomaticObservation.unavailable(
                    areaName, fingerprint, "Farm-area policy is unavailable");
        }
        ProtectedAreaClientState.NamedResourceAreaObservation named =
                protectedAreas.observeNamedResourceArea(
                        ProtectedAreaPolicy.AreaKind.HARVESTING, areaName);
        if (!named.available()) {
            return ManagedFarmAutomaticObservation.unavailable(
                    areaName, fingerprint, named.detail());
        }
        ProtectedAreaPolicy.Area area = named.area().orElse(null);
        if (area == null) {
            return ManagedFarmAutomaticObservation.unavailable(
                    areaName, fingerprint, named.detail());
        }
        String observedFingerprint = ManagedFarmPolicy.areaFingerprint(area);
        if (!observedFingerprint.equals(fingerprint)) {
            return ManagedFarmAutomaticObservation.unavailable(
                    areaName, observedFingerprint,
                    "Farm '" + areaName + "' changed; review it and set automatic farming again");
        }
        if (client.player == null || client.world == null) {
            return ManagedFarmAutomaticObservation.unavailable(
                    areaName, observedFingerprint,
                    "Waiting for Entity and its world before observing the farm");
        }
        if (!area.dimension().equals(currentDimension())) {
            return ManagedFarmAutomaticObservation.unavailable(
                    areaName, observedFingerprint,
                    "Farm is in " + area.dimension() + "; Entity is in " + currentDimension());
        }

        LinkedHashMap<String, Integer> mature = new LinkedHashMap<>();
        ArrayList<String> matureCells = new ArrayList<>();
        boolean completelyLoaded = true;
        int playerY = client.player.getBlockY();
        int homeY = homeAnchor().map(HomeEconomySession.HomeAnchor::y).orElse(playerY);
        int minimumY = Math.max(client.world.getBottomY(), Math.min(playerY, homeY) - 8);
        int maximumY = Math.min(client.world.getTopYInclusive(), Math.max(playerY, homeY) + 8);
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                if (!client.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                    completelyLoaded = false;
                    continue;
                }
                // WORLD_SURFACE catches an ordinary open field without scanning a
                // whole world column. The bounded player/Home band also covers a
                // roofed farm at the same practical working elevation.
                int surfaceY = Math.max(client.world.getBottomY(), Math.min(
                        client.world.getTopYInclusive(),
                        client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1));
                for (int y = minimumY; y <= maximumY; y++) {
                    observeAutomaticFarmCell(cursor, x, y, z, mature, matureCells);
                }
                if (surfaceY < minimumY || surfaceY > maximumY) {
                    observeAutomaticFarmCell(
                            cursor, x, surfaceY, z, mature, matureCells);
                }
            }
        }

        HomeEconomySession.Snapshot home = homeEconomy == null
                ? null : homeEconomy.snapshot();
        boolean homeReady = settingsLoadState.authoritative()
                && home != null && home.home() != null;
        boolean storageConfigured = homeReady && !home.storageExplicitlyMissing()
                && !home.storageRegistrations().isEmpty();
        Map<String, HomeEconomySession.ContainerIdentity> validStorage =
                storageConfigured ? validHomeStorage(home) : Map.of();
        boolean storageObserved = storageConfigured
                && validStorage.size() == home.storageRegistrations().size()
                && homeStorageObservations.keySet().containsAll(validStorage.keySet());
        boolean storageFresh = storageObserved
                && homeStorageObservations.values().stream().allMatch(observation ->
                        observation.observedAtMillis() >= 0L
                                && nowMillis - observation.observedAtMillis() <= 30_000L);
        Map<String, Integer> carried = canonicalInventorySnapshot();
        LinkedHashMap<String, Integer> stored = new LinkedHashMap<>();
        if (storageObserved) {
            // Home chest snapshots use namespaced wire IDs; farm policy uses
            // the same canonical item IDs as acquisition planning. Comparing
            // those maps directly made physically observed seeds look absent.
            aggregateHomeStorageCounts(home).forEach((raw, count) ->
                    stored.merge(resourcePlanner.normalizeItem(raw), count,
                            Math::addExact));
        }
        LinkedHashMap<String, Integer> seedSupply = new LinkedHashMap<>();
        LinkedHashSet<String> missingSeeds = new LinkedHashSet<>();
        LinkedHashSet<String> outputs = new LinkedHashSet<>();
        for (ManagedFarmPolicy.CropFamily crop : ManagedFarmPolicy.crops()) {
            if (mature.getOrDefault(crop.block(), 0) <= 0) continue;
            int supply = Math.addExact(
                    carried.getOrDefault(crop.replant(), 0),
                    stored.getOrDefault(crop.replant(), 0));
            seedSupply.put(crop.replant(), supply);
            if (supply < 1) missingSeeds.add(crop.replant());
            outputs.add(crop.produce());
            outputs.add(crop.replant());
        }
        boolean playerCapacity = automaticFarmPlayerCapacity(outputs);
        boolean storageCapacity = storageFresh
                && automaticFarmStorageCapacity(outputs);
        String workKey = matureCells.isEmpty() ? ""
                : automaticFarmWorkKey(
                        areaName, observedFingerprint,
                        home == null ? 0L : home.generation(),
                        matureCells, seedSupply, storageCapacity, playerCapacity);

        String detail;
        boolean eligible = false;
        if (!completelyLoaded) {
            detail = "Resting: all chunks in farm '" + areaName
                    + "' must be loaded before automatic maturity is claimed";
        } else if (matureCells.isEmpty()) {
            detail = "Resting: farm '" + areaName + "' has no observed mature supported crops";
        } else if (!homeReady) {
            detail = "Resting: automatic farming requires an established Home";
        } else if (!storageConfigured) {
            detail = "Resting: automatic farming requires registered Home storage";
        } else if (!storageFresh) {
            detail = "Waiting for a fresh inspection of every registered Home storage container";
        } else if (!missingSeeds.isEmpty()) {
            detail = "Resting: exact replant supply is missing " + missingSeeds;
        } else if (!playerCapacity) {
            detail = "Resting: Entity inventory has no safe room for this farm pass";
        } else if (!storageCapacity) {
            detail = "Resting: registered Home storage has no room for this farm's useful output";
        } else {
            eligible = true;
            detail = "Eligible: " + matureCells.size()
                    + " mature crop cell(s) in farm '" + areaName + "'";
        }
        return new ManagedFarmAutomaticObservation(
                areaName, observedFingerprint, area.dimension(), area.width(), area.depth(),
                true, completelyLoaded, mature, seedSupply, homeReady,
                storageConfigured, storageFresh, storageCapacity, playerCapacity,
                eligible, workKey, detail);
    }

    private void observeAutomaticFarmCell(
            BlockPos.Mutable cursor,
            int x,
            int y,
            int z,
            Map<String, Integer> mature,
            List<String> matureCells) {
        cursor.set(x, y, z);
        BlockState state = client.world.getBlockState(cursor);
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMature(state)) return;
        String block = Registries.BLOCK.getId(state.getBlock()).getPath();
        if (!MANAGED_FARM_CROP_BLOCKS.contains(block)) return;
        mature.merge(block, 1, Math::addExact);
        matureCells.add(block + '@' + x + ',' + y + ',' + z);
    }

    private boolean automaticFarmPlayerCapacity(Set<String> outputs) {
        if (client.player == null || outputs.isEmpty()) return false;
        int emptySlots = 0;
        LinkedHashSet<String> needsSlot = new LinkedHashSet<>(outputs);
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            String item = simple(Registries.ITEM.getId(stack.getItem()).toString());
            if (stack.getCount() < stack.getMaxCount()) needsSlot.remove(item);
        }
        return emptySlots >= needsSlot.size();
    }

    private boolean automaticFarmStorageCapacity(Set<String> outputs) {
        if (outputs.isEmpty()) return false;
        int emptySlots = homeStorageObservations.values().stream()
                .mapToInt(observation -> observation.capacity().emptySlots()).sum();
        int needsSlot = 0;
        for (String output : outputs) {
            boolean merge = homeStorageObservations.values().stream().anyMatch(
                    observation -> observation.capacity().accepts(output)
                            && (observation.capacity().mergeRoom()
                            .getOrDefault(output, 0) > 0
                            || observation.capacity().mergeRoom()
                            .getOrDefault("minecraft:" + output, 0) > 0));
            if (!merge) needsSlot++;
        }
        return emptySlots >= needsSlot;
    }

    private static String automaticFarmWorkKey(
            String area,
            String fingerprint,
            long homeGeneration,
            List<String> matureCells,
            Map<String, Integer> seedSupply,
            boolean storageCapacity,
            boolean playerCapacity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            homeSupplyDigestPut(digest, "entity2-managed-farm-auto-v1");
            homeSupplyDigestPut(digest, area);
            homeSupplyDigestPut(digest, fingerprint);
            homeSupplyDigestPut(digest, Long.toString(homeGeneration));
            matureCells.forEach(cell -> homeSupplyDigestPut(digest, cell));
            new java.util.TreeMap<>(seedSupply).forEach((item, count) -> {
                homeSupplyDigestPut(digest, item);
                homeSupplyDigestPut(digest, Integer.toString(count));
            });
            homeSupplyDigestPut(digest, Boolean.toString(storageCapacity));
            homeSupplyDigestPut(digest, Boolean.toString(playerCapacity));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record ManagedFarmAutomaticObservation(
            String area,
            String areaFingerprint,
            String dimension,
            int width,
            int depth,
            boolean areaAvailable,
            boolean areaCompletelyLoaded,
            Map<String, Integer> matureCounts,
            Map<String, Integer> seedSupply,
            boolean homeReady,
            boolean storageConfigured,
            boolean storageFresh,
            boolean storageCapacity,
            boolean playerCapacity,
            boolean eligible,
            String workKey,
            String detail) {
        public ManagedFarmAutomaticObservation {
            area = Objects.requireNonNullElse(area, "");
            areaFingerprint = Objects.requireNonNullElse(areaFingerprint, "");
            dimension = Objects.requireNonNullElse(dimension, "");
            matureCounts = Map.copyOf(matureCounts);
            seedSupply = Map.copyOf(seedSupply);
            workKey = Objects.requireNonNullElse(workKey, "");
            detail = Objects.requireNonNullElse(detail, "");
        }

        public int matureTotal() {
            return matureCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        private static ManagedFarmAutomaticObservation unavailable(
                String area,
                String fingerprint,
                String detail) {
            return new ManagedFarmAutomaticObservation(
                    area, fingerprint, "", 0, 0, false, false,
                    Map.of(), Map.of(), false, false, false, false, false,
                    false, "", detail);
        }
    }

    /** Exactly one authorized inspect/category/return/deposit pass, using Home's existing actuator owner. */
    public String requestIdleStockRun(Map<String, Integer> storedTargets, boolean undergroundOnly,
                                      long nowMillis) throws IOException {
        if (idleStockCycle != null) return idleStockDetail;
        Map<String, Integer> targets = IdleStockPolicy.validateTargets(storedTargets);
        var observation = autonomousStockObservation(nowMillis);
        if (!observation.homeConfigured() || !observation.storageConfigured())
            return deferIdleStock("home_storage_missing", "Idle work needs configured Home and registered storage; none will be built automatically");
        if (!observation.stockEnabled()) return deferIdleStock("stock_disabled", "Home Stock is disabled");
        if (!idleWorkingTools.problem().isBlank()) return deferIdleStock("tool_memory_unavailable", idleWorkingTools.problem());
        var snapshot = homeEconomy.snapshot();
        if (!homeRunRequested && !homeSleepRequested
                && idleStockRouteRecovery.mayReobserve(snapshot, observation.settled(), nowMillis)) {
            // Consume provenance before changing Home. A crash may deny a retry, never adopt manual work.
            idleStockRouteRecovery.clear();
            if (snapshot.phase() == HomeEconomySession.Phase.PAUSED) homeEconomy.resume(nowMillis);
            homeEconomy.retryBlocked(nowMillis);
            snapshot = homeEconomy.snapshot();
        }
        if (undergroundOnly && !homeRunRequested && !homeSleepRequested
                && idleSleepFallback.mayReobserve(snapshot, observation.settled())) {
            homeEconomy.retryBlocked(nowMillis);
            idleSleepFallback.clear();
            snapshot = homeEconomy.snapshot();
        }
        if (homeRunRequested || homeSleepRequested || !observation.settled()
                || snapshot.pendingAcquisition() != null || snapshot.pendingTransfer() != null
                || snapshot.block() != null)
            return deferIdleStock("home_work_unsettled", "Existing Home work must settle or be explicitly resolved before automatic Stock");
        idleSleepFallback.clear();
        idleStockRouteRecovery.begin(snapshot);
        idleWorkingTools.observe(inventory.counts());
        invalidateHomeStorageObservations();
        idleStockCycle = new IdleStockCycle(snapshot.generation(), targets, undergroundOnly);
        idleStockState = IdleStockPolicy.State.INSPECTING;
        idleStockResultKey = "inspect_storage";
        idleStockDetail = "Inspecting registered Home storage before one finite automatic Stock job";
        homeRunRequested = true;
        return idleStockDetail;
    }

    private String deferIdleStock(String key, String detail) {
        idleStockState = IdleStockPolicy.State.DEFERRED; idleStockResultKey = key; idleStockDetail = detail;
        return detail;
    }

    /** Passive reconsideration identity; Runtime still proves fresh authority and complete body neutrality. */
    public String automaticStockRecoveryKey(long nowMillis) {
        if (homeEconomy == null || !settingsLoadState.authoritative() || homeRunRequested || homeSleepRequested) return "";
        return idleStockRouteRecovery.workKey(homeEconomy.snapshot(), stockCommandObservation().settled(), nowMillis);
    }

    /** A real owner command revokes old automatic provenance even when no automatic job is currently active. */
    public void clearAutomaticStockRecovery() throws IOException {
        idleStockRouteRecovery.clear();
    }

    private boolean idleHomeUnderground() {
        if (client.player == null || client.world == null || homeAnchor().isEmpty() || !atHome(homeAnchor().orElseThrow())) return false;
        BlockPos feet = client.player.getBlockPos();
        return !client.world.isSkyVisible(feet)
                && feet.getY() + 3 < client.world.getTopY(net.minecraft.world.Heightmap.Type.OCEAN_FLOOR, feet.getX(), feet.getZ());
    }

    public record IdleSleepObservation(boolean bedAvailable, boolean requested, boolean settled,
                                       boolean blocked, String detail) { }

    /** A known exact bed is a candidate, not a claim that the current route/reach is usable. */
    public IdleSleepObservation automaticSleepObservation(long nowMillis) {
        var snapshot = homeEconomy == null ? null : homeEconomy.snapshot();
        var bed = snapshot == null ? null : snapshot.pinnedAssets().get(HomeEconomySession.AssetRole.BED);
        var asset = bed == null ? null : workstations.ownedAsset(bed.ledgerAssetId()).orElse(null);
        boolean available = asset != null && asset.state() == FieldKitLedger.AssetState.PLACED
                && asset.lastKnownPosition() != null && currentDimension().equals(asset.lastKnownPosition().dimension());
        boolean settled = stockCommandObservation().settled();
        boolean blocked = snapshot != null && snapshot.phase() == HomeEconomySession.Phase.BLOCKED;
        return new IdleSleepObservation(available, homeSleepRequested, settled, blocked,
                blocked ? snapshot.block().detail() : lastHomeEconomyDetail);
    }

    private void cancelIdleStockMarker(String key, String detail) {
        if (idleStockCycle == null) return;
        idleStockCycle = null; idleStockState = IdleStockPolicy.State.CANCELLED;
        idleStockResultKey = key; idleStockDetail = detail;
    }

    /** Revokes only this automatic batch; the existing Home drain still owns issued receipts. */
    public void cancelIdleStockRun(long nowMillis) throws IOException {
        if (idleStockCycle != null || cancelledIdleStock != null) ownerStopHomeEconomy(nowMillis);
        else clearAutomaticStockRecovery();
    }

    private void settleCancelledIdleStock(long nowMillis) throws IOException {
        if (cancelledIdleStock == null || homeEconomy == null) return;
        var snapshot = homeEconomy.snapshot();
        if (snapshot.generation() != cancelledIdleStock.homeGeneration()) { cancelledIdleStock = null; return; }
        var tx = inventory.transactionDiagnostics();
        if (!inventory.cursorEmpty() || !tx.pendingClickOwner().isBlank() || !tx.cursorOwner().isBlank()
                || tx.pendingRemovals() != 0 || pendingHomeWaterFill()) return;
        var pending = snapshot.pendingAcquisition();
        if (!cancelledIdleStock.acquisitionToRetire(snapshot).isEmpty()) {
            var plan = plans.restore(pending.planId());
            if (plan.isPresent() && plan.orElseThrow().state() == TaskPlanState.OPEN)
                plans.clear(pending.planId(), "cancelled automatic Stock batch; no success claimed", nowMillis);
            homeEconomy.abandonAcquisitionForForeground(pending.planId(), nowMillis);
            retirePlanIfTerminal(pending.planId(), "home-journal:idle-cancelled", nowMillis);
        }
        var transfer = homeEconomy.snapshot().pendingTransfer();
        if (!cancelledIdleStock.transferToRetire(homeEconomy.snapshot()).isEmpty())
            homeEconomy.abandonTransfer(transfer.id(), nowMillis);
        // No new automatic activity is granted by retiring an exact already-settled receipt.
        // Leave unrelated/manual pending work untouched even if it arrived during acknowledgement.
        cancelledIdleStock = null;
    }

    private HomeIdleOutcome finishIdleStock(IdleStockPolicy.State state, String key, String detail,
                                            long nowMillis) throws IOException {
        var snapshot = homeEconomy.snapshot();
        var phase = snapshot.phase();
        var completedCycle = idleStockCycle;
        boolean genuineCompletion = state == IdleStockPolicy.State.COMPLETED && homeRunRequested
                && completedCycle != null && completedCycle.homeGeneration == snapshot.generation();
        // The finite batch has genuinely settled, not an unfinished return leg.
        // Missing Home assets/custody or revoked authority cannot publish READY.
        if (state == IdleStockPolicy.State.COMPLETED && idleStockCycle != null
                && idleStockCycle.homeGeneration == snapshot.generation()
                && snapshot.enabled() && snapshot.home() != null && snapshot.hasAllPinnedAssets()
                && !snapshot.storageExplicitlyMissing()
                && snapshot.pendingTransfer() == null && snapshot.pendingAcquisition() == null
                && phase != HomeEconomySession.Phase.PAUSED && phase != HomeEconomySession.Phase.BLOCKED
                && phase != HomeEconomySession.Phase.DEGRADED_READY) {
            phase = HomeEconomySession.Phase.READY;
        }
        if (state != IdleStockPolicy.State.COMPLETED) homeRunRequested = false;
        HomeIdleOutcome outcome = finishHomeCycle(phase, detail, nowMillis);
        var completed = homeEconomy.snapshot();
        if (genuineCompletion && completed.generation() == completedCycle.homeGeneration
                && completed.phase() == HomeEconomySession.Phase.READY
                && completed.pendingTransfer() == null && completed.pendingAcquisition() == null) {
            logger.info("Idle Stock durable completion schema=1 generation={} revision={} updatedAtMillis={} completedAtMillis={} category={} phase={} pendingTransfer={} pendingAcquisition={}",
                    completed.generation(), completed.revision(), completed.updatedAtMillis(), nowMillis,
                    completedCycle.category.isBlank() ? "none" : completedCycle.category, completed.phase(),
                    completed.pendingTransfer() != null, completed.pendingAcquisition() != null);
        }
        idleStockCycle = null; idleStockState = state; idleStockResultKey = key; idleStockDetail = detail;
        lastHomeEconomyDetail = detail;
        return outcome;
    }

    private HomeIdleOutcome tickIdleStockAtChest(HomeEconomySession.Snapshot snapshot,
            ClientInventoryController.HomeChestSnapshot chest, Map<String, Integer> protectedCounts,
            long nowMillis) throws IOException {
        var valid = validHomeStorage(snapshot);
        if (valid.size() != snapshot.storageRegistrations().size()
                || !homeStorageObservations.keySet().containsAll(valid.keySet())) {
            return finishIdleStock(IdleStockPolicy.State.DEFERRED, "storage_unknown",
                    "Some registered storage is unavailable or unobserved; no replacement resources were acquired", nowMillis);
        }
        IdleStockCycle cycle = idleStockCycle;
        Map<String, Integer> stored = aggregateHomeStorageCounts(snapshot);
        if (cycle.category.isEmpty()) {
            if (cycle.undergroundOnly) {
                selectIdleNightCategory(cycle, chest, stored);
                if (cycle.category.isEmpty()) return finishIdleStock(IdleStockPolicy.State.DEFERRED,
                        "underground_work_unavailable", "No needed supplied local job or known underground mine is feasible; resting", nowMillis);
            } else cycle.category = IdleStockPolicy.selectCategory(chest.playerCounts(), stored, cycle.storedTargets, idleWorkingTools.tools());
            if (!cycle.category.isEmpty()) logger.info("Idle Stock selected category={} carried={} stored={} targets={}",
                    cycle.category, chest.playerCounts(), stored, cycle.storedTargets);
        }
        int playerRoom = cycle.category.isEmpty() ? 0 : idlePlayerCapacity(cycle.category);
        int storageRoom = cycle.category.isEmpty() ? 0 : idleStorageCapacity(cycle.category);
        var next = IdleStockPolicy.decide(cycle.category, cycle.acquisitionIssued, chest.playerCounts(), stored,
                chest.depositEligibleCounts(), cycle.storedTargets, idleWorkingTools.tools(), playerRoom, storageRoom,
                !inventory.hasEmptyInventorySlot());
        idleStockState = IdleStockPolicy.State.WORKING; idleStockResultKey = next.key(); idleStockDetail = next.detail();
        lastHomeEconomyDetail = next.detail();
        switch (next.action()) {
            case COMPLETE -> {
                return finishIdleStock(IdleStockPolicy.State.COMPLETED, next.key(), next.detail(), nowMillis);
            }
            case DEFER -> {
                return finishIdleStock(IdleStockPolicy.State.DEFERRED, next.key(), next.detail(), nowMillis);
            }
            case WITHDRAW, DEPOSIT -> {
                if (storageForTransfer(snapshot, next.transfer()) == null)
                    return finishIdleStock(IdleStockPolicy.State.DEFERRED, "storage_full_or_changed",
                            "No observed registered chest can perform the selected finite transfer", nowMillis);
                return beginHomeTransfer(next.transfer(), protectedCounts, nowMillis);
            }
            case ACQUIRE -> {
                Map<String, Integer> goals = cycle.undergroundGoals.isEmpty() ? next.additionalGoals() : cycle.undergroundGoals;
                if (cycle.undergroundOnly && cycle.undergroundSite == null && !idleLocalUndergroundPlan(goals))
                    return finishIdleStock(IdleStockPolicy.State.DEFERRED, "underground_prerequisites_unproven",
                            "This automatic job cannot prove an underground-only site and supplied prerequisites; resting", nowMillis);
                HomeIdleOutcome outcome = startHomeAcquisition(goals, nowMillis);
                if (idleStockCycle != null) idleStockCycle.acquisitionIssued = true;
                return outcome;
            }
            default -> throw new IllegalStateException("Unknown idle Stock decision");
        }
    }

    private int idlePlayerCapacity(String category) {
        if (client.player == null) return 0;
        var target = IdleStockPolicy.target(category, idleWorkingTools.tools());
        int max = inventoryStackLimit(target.acquisitionItem()), room = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) room += max;
            else if (target.accepts(Registries.ITEM.getId(stack.getItem()).toString())) room += Math.max(0, stack.getMaxCount() - stack.getCount());
        }
        return Math.min(64, room);
    }

    private int idleStorageCapacity(String category) {
        var target = IdleStockPolicy.target(category, idleWorkingTools.tools());
        int maximum = inventoryStackLimit(target.acquisitionItem()), room = 0;
        for (var observation : homeStorageObservations.values()) {
            room += observation.capacity().emptySlots() * maximum;
            // Unknown cooked-food choice cannot promise a merge into a different food stack.
            if (!category.equals("food")) room += IdleStockPolicy.count(observation.capacity().mergeRoom(), target.acceptedItems());
        }
        return room;
    }

    private boolean idleLocalUndergroundPlan(Map<String, Integer> additional) {
        if (!idleHomeUnderground() || additional.containsKey(FoodFamilyPolicy.FAMILY_ITEM)) return false;
        try {
            var goals = HomeStockPolicy.absoluteAcquisitionGoals(additional, canonicalInventorySnapshot()).entrySet().stream()
                    .map(entry -> new AcquisitionRequest.ItemGoal(entry.getKey(), entry.getValue())).toList();
            return IdleStockPolicy.localUndergroundPlan(resourcePlanner.compile(new AcquisitionRequest(
                    goals, planningInventoryView(), usablePickaxeDurability())));
        } catch (ResourcePlanner.PlanningException invalid) { return false; }
    }

    private boolean idleUndergroundLeafAllowed(PlanFrame frame) {
        if (Set.of("craft", "smelt", "equip").contains(frame.kind()))
            return idleStockCycle.undergroundSite != null || idleHomeUnderground();
        if (!frame.kind().equals("mine") || idleStockCycle.undergroundSite == null) return false;
        Set<String> ores = idleStockCycle.category.equals("fuel") ? Set.of("coal_ore", "deepslate_coal_ore")
                : Set.of("iron_ore", "deepslate_iron_ore");
        return Arrays.stream(frame.parameters().getOrDefault("blockAlternatives", "").split(",")).allMatch(ores::contains);
    }

    /** Skip infeasible surface food/log work; the ordinary compiler must prove all mine prerequisites carried. */
    private void selectIdleNightCategory(IdleStockCycle cycle, ClientInventoryController.HomeChestSnapshot chest,
                                         Map<String, Integer> stored) {
        for (String category : List.of("food", "pickaxe", "weapon", "shield", "axe", "fuel", "iron", "logs")) {
            var decision = IdleStockPolicy.decide(category, false, chest.playerCounts(), stored, chest.depositEligibleCounts(),
                    cycle.storedTargets, idleWorkingTools.tools(), idlePlayerCapacity(category), idleStorageCapacity(category),
                    !inventory.hasEmptyInventorySlot());
            if (decision.action() == IdleStockPolicy.Action.COMPLETE || decision.action() == IdleStockPolicy.Action.DEFER) continue;
            if (decision.action() != IdleStockPolicy.Action.ACQUIRE || idleLocalUndergroundPlan(decision.additionalGoals())) {
                cycle.category = category; return;
            }
            if (!Set.of("fuel", "iron").contains(category)
                    || IdleStockPolicy.count(chest.playerCounts(), IdleStockPolicy.accepted("food", Map.of())) < 8) continue;
            Set<String> ores = category.equals("fuel") ? Set.of("coal_ore", "deepslate_coal_ore")
                    : Set.of("iron_ore", "deepslate_iron_ore");
            var site = baritone.idleUndergroundSite(ores).orElse(null);
            if (site == null) continue;
            Map<String, Integer> bounded = new LinkedHashMap<>();
            decision.additionalGoals().forEach((item, count) -> bounded.put(item, Math.min(count, site.observedCount())));
            try {
                var goals = HomeStockPolicy.absoluteAcquisitionGoals(bounded, canonicalInventorySnapshot()).entrySet().stream()
                        .map(entry -> new AcquisitionRequest.ItemGoal(entry.getKey(), entry.getValue())).toList();
                var compiled = resourcePlanner.compile(new AcquisitionRequest(goals, planningInventoryView(), usablePickaxeDurability()));
                if (!IdleStockPolicy.undergroundMinePlan(compiled, ores)) continue;
            } catch (ResourcePlanner.PlanningException unavailable) { continue; }
            cycle.category = category; cycle.undergroundSite = site; cycle.undergroundGoals = Map.copyOf(bounded);
            logger.info("Idle underground admitted category={} route={} entrance={} site={} observedOre={} count={}",
                    category, site.routeId(), site.entrance(), site.workCell(), site.observedOre(), bounded);
            return;
        }
    }

    /** Exposes only the bounded direct-pickup movement lease to Runtime telemetry. */
    public boolean boundedGroundItemVerticalRecoveryActive(long nowMillis) {
        return groundItems.boundedVerticalRecoveryActive(nowMillis);
    }

    /** Whether Runtime should acquire the priority-zero whole-body lease this idle tick. */
    public boolean homeEconomyNeedsIdleLease(long nowMillis) {
        if (homeEconomy == null || !settingsLoadState.authoritative()) return false;
        if (tidy.needsLease()) return true;
        if (homeScreenClosePending) return true;
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        if (!HomeSleepPolicy.hasWorkAuthority(snapshot, homeRunRequested, homeSleepRequested)) return false;
        // Home is an explicit objective, not a hidden never-idle behavior.
        // Durable leftovers remain visible in status but do not regain body
        // authority until the owner requests Home/stock work again.
        if (!homeRunRequested && !homeSleepRequested) return false;
        if (snapshot.phase() == HomeEconomySession.Phase.NEEDS_HOME
                && configuredHome() == null) return false;
        if (snapshot.phase() == HomeEconomySession.Phase.BLOCKED && !homeRunRequested) {
            return false;
        }
        if ((snapshot.phase() == HomeEconomySession.Phase.READY
                || snapshot.phase() == HomeEconomySession.Phase.DEGRADED_READY)
                && homeSleepRequested && homeSleepDue(snapshot)) {
            return true;
        }
        return true;
    }

    /**
     * A yielded or blocked Home job still owns a durable cursor.  Foreground
     * work must fence that cursor even after the priority-zero body lease was
     * released, otherwise the last Home tick can win the handoff race and
     * leave the owner's command beside an unfenced BLOCKED acquisition.
     */
    public boolean homeEconomyNeedsForegroundFence() {
        if (tidy.needsForegroundFence()) return true;
        if (homeEconomy == null || !settingsLoadState.authoritative()) return false;
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        if (!snapshot.enabled() || snapshot.phase() == HomeEconomySession.Phase.PAUSED) {
            return false;
        }
        return homeScreenClosePending
                || snapshot.pendingTransfer() != null
                || snapshot.pendingAcquisition() != null
                || pendingHomeWaterFill();
    }

    /**
     * True while a higher-priority owner must keep draining the acknowledgement-fenced
     * Home screen close.  This does not grant Home permission to resume economy work.
     */
    public boolean homePreemptionDrainPending() {
        return homeScreenClosePending || tidy.drainPending() || stoppedCapacityCleanupPending()
                || issuedCapacityTransferCustodyPending();
    }

    public String tidyWorldId() { return tidy.worldId(); }

    public MinecraftTidyController.CommandResult tidyCommand(String operation, String commandId, String item,
            boolean enabled, boolean includeStorage, MinecraftTidyController.SpotSelection spot,
            boolean idleAuthority, long nowMillis) throws IOException {
        boolean settledHome = homeEconomy != null && settingsLoadState.authoritative()
                && !homeRunRequested && !homeSleepRequested && !homeScreenClosePending
                && homeEconomy.snapshot().pendingTransfer() == null
                && homeEconomy.snapshot().pendingAcquisition() == null && !pendingHomeWaterFill();
        return tidy.command(operation, commandId, item, enabled, includeStorage, spot,
                idleAuthority && settledHome, nowMillis);
    }

    public String establishHome(boolean idleAuthority, long nowMillis) throws IOException {
        return createHome(idleAuthority, true, nowMillis);
    }

    private String createHome(boolean idleAuthority, boolean provision, long nowMillis) throws IOException {
        requireTrustedHomeSettings("establish Home");
        HomeEconomySession session = requireHomeEconomy();
        if (settings.hasHomeChest() || session.snapshot().home() != null) {
            throw new IllegalArgumentException(
                    "A Home already exists; use /e home set to preview relocation");
        }
        if (session.snapshot().pendingTransfer() != null
                || session.snapshot().pendingAcquisition() != null) {
            throw new IllegalArgumentException(
                    "Home establishment is waiting for an existing durable operation to reconcile");
        }
        if (client.player == null || client.world == null) {
            throw new IllegalArgumentException("Join a loaded world before establishing Home");
        }
        requireStationaryHomeAuthority(idleAuthority);
        BlockPos requested = client.player.getBlockPos().toImmutable();
        inspectExactHomeCandidate(idleAuthority, requested);
        BlockPos anchor = requested;
        String dimension = currentDimension();
        AutonomySettingsStore.Settings next = settings.withStockEnabled(provision)
                .withHome(dimension, anchor.getX(), anchor.getY(), anchor.getZ());
        // Settings are the source of truth. A crash after this commit is
        // repaired by reconcileHomeStores without inventing another anchor.
        settingsStore.save(next);
        markSettingsValid(next);
        reconcileHomeStores(nowMillis);
        homeControlPreview = null;
        homeRunRequested = provision;
        lastHomeEconomyDetail =
                "set Home from Entity's exact stationary position at "
                        + dimension + ' ' + anchor.getX() + ' ' + anchor.getY() + ' '
                        + anchor.getZ() + (provision ? "; stock enabled and provisioning owned assets"
                        : "; registered only, Stock OFF; no furniture placed. Use /e home adopt or /e home setup");
        return homeStatus();
    }

    /** Canonical set: initial creation is immediate; an existing Home gets a frozen preview. */
    public String setHome(boolean idleAuthority, long nowMillis) throws IOException {
        requireTrustedHomeSettings("set Home");
        HomeEconomySession.HomeAnchor current = homeAnchor().orElse(null);
        if (current == null) return createHome(idleAuthority, false, nowMillis);
        requireStationaryHomeAuthority(idleAuthority);
        BlockPos requested = client.player.getBlockPos().toImmutable();
        HomeEconomySession.HomeAnchor candidate = HomeEconomySession.HomeAnchor.at(
                currentDimension(), requested.getX(), requested.getY(), requested.getZ());
        if (candidate.fingerprint().equals(current.fingerprint())) {
            requireHomeEconomy().validateOwnerStorageMutation("register Home");
            requireSettledHomeInventory(requireHomeEconomy().snapshot());
            setStockEnabled(false);
            homeRunRequested = false;
            homeControlPreview = null;
            return "Home is already set from Entity's position at "
                    + candidate.dimension() + ' ' + candidate.x() + ' '
                    + candidate.y() + ' ' + candidate.z() + "; registration only, Stock OFF; no furnishing requested";
        }
        inspectExactHomeCandidate(idleAuthority, requested);
        homeControlPreview = HomeControlPolicy.relocation(current, candidate, nowMillis);
        return "Relocation preview frozen from Entity's position at "
                + candidate.dimension() + ' ' + candidate.x() + ' '
                + candidate.y() + ' ' + candidate.z()
                + " for 30 seconds. Run /e home set confirm. Old bed, chest, "
                + "workstations, and chest contents will remain player property. Stock will be OFF; no furnishing requested.";
    }

    /** Confirms only the exact candidate captured by the immediately preceding set preview. */
    public String confirmHomeSet(boolean idleAuthority, long nowMillis) throws IOException {
        requireTrustedHomeSettings("relocate Home");
        requireStationaryHomeAuthority(idleAuthority);
        HomeEconomySession.HomeAnchor current = homeAnchor().orElse(null);
        HomeControlPolicy.Confirmation confirmation = HomeControlPolicy.confirm(
                homeControlPreview, HomeControlPolicy.Action.RELOCATE, current, nowMillis);
        if (!confirmation.valid()) {
            homeControlPreview = null;
            throw new IllegalArgumentException(confirmation.detail());
        }
        HomeEconomySession.HomeAnchor candidate = confirmation.candidate();
        if (client.player == null || client.world == null
                || !currentDimension().equals(candidate.dimension())
                || !client.player.getBlockPos().equals(new BlockPos(
                candidate.x(), candidate.y(), candidate.z()))) {
            homeControlPreview = null;
            throw new IllegalArgumentException(
                    "Entity moved away from the frozen relocation anchor; stand it on the "
                            + "desired block and run /e home set again");
        }
        inspectExactHomeCandidate(true, client.player.getBlockPos());
        HomeEconomySession session = requireHomeEconomy();
        HomeEconomySession.Snapshot old = session.snapshot();
        fenceOldHomeWork(old, nowMillis, "confirmed owner Home relocation");
        // Cursor first: a crash before settings commit safely rebinds the old
        // settings Home; no physical asset is moved or dismantled either way.
        session.ownerRegisterHome(candidate, nowMillis);
        AutonomySettingsStore.Settings next = settings.withStockEnabled(false)
                .withHome(candidate.dimension(), candidate.x(), candidate.y(), candidate.z());
        settingsStore.save(next);
        markSettingsValid(next);
        finishHomeIdentityChange(old, nowMillis);
        homeControlPreview = null;
        homeRunRequested = false;
        homeSleepRequested = false;
        lastHomeEconomyDetail = "Home relocated to Entity's frozen position at "
                + candidate.dimension() + ' ' + candidate.x() + ' '
                + candidate.y() + ' ' + candidate.z()
                + "; old physical assets and contents were preserved; Stock OFF, no furnishing requested";
        return lastHomeEconomyDetail + "; " + homeStatus();
    }

    /** Explicit provisioning of the already selected anchor; never chooses another site. */
    public String setupHome(boolean idleAuthority, long nowMillis) throws IOException {
        requireTrustedHomeSettings("set up Home");
        if (homeAnchor().isEmpty()) throw new IllegalArgumentException("Register the anchor with /e home set first");
        if (!idleAuthority) throw new IllegalArgumentException("Stop or pause current work before /e home setup");
        setStockEnabled(true);
        return "Home setup requested: Stock ON; missing owned furniture may be crafted/placed within the 6-block 3D Home radius. "
                + homeStatus();
    }

    public void invalidateHomeFurniturePreview() {
        homeFurniturePreview = null;
        homeStoragePreview = null;
        homeAdoptionPreview = null;
    }

    public String previewHomeAdoption(long nowMillis) {
        requireTrustedHomeSettings("preview Home furniture adoption");
        var snapshot = requireHomeEconomy().snapshot();
        if (protectedAreas == null) throw new IllegalArgumentException("Area authority is unavailable");
        var policy = protectedAreas.status();
        requireFurniturePolicyIdentity(policy.revision(), policy.digest());
        String nonce = java.util.UUID.randomUUID().toString();
        var observed = workstations.observeHomeFurnitureForAdoption(snapshot.home(), HOME_ASSET_RADIUS, nonce);
        homeAdoptionPreview = HomeFurnitureAdoptionPolicy.preview(nonce, snapshot,
                policy.revision(), policy.digest(), observed, nowMillis);
        return "Home adoption preview: " + homeAdoptionPreview.detail()
                + ". Scan is the loaded 6-block 3D radius around Home, not whole-house inventory. "
                + "Only unique usable roles are selected; missing/ambiguous roles remain unchanged. "
                + "/e home adopt confirm within 30 seconds registers those exact blocks only; "
                + "no placement, Stock unchanged, current work stops. Use /e home bind <role> to resolve ambiguity.";
    }

    public void validateHomeAdoption(long nowMillis) {
        requireTrustedHomeSettings("adopt Home furniture");
        var preview = homeAdoptionPreview;
        if (preview == null) throw new IllegalArgumentException("Use /e home adopt before confirming");
        var snapshot = requireHomeEconomy().snapshot();
        requireFurniturePolicyIdentity(preview.revision(), preview.digest());
        var observed = workstations.observeHomeFurnitureForAdoption(snapshot.home(), HOME_ASSET_RADIUS, preview.nonce());
        HomeFurnitureAdoptionPolicy.requireConfirmation(preview, snapshot, preview.revision(), preview.digest(), observed, nowMillis);
        requireHomeEconomy().validateOwnerStorageMutation("adopt furniture");
        requireSettledHomeInventory(snapshot);
    }

    public String confirmHomeAdoption(long nowMillis) throws IOException {
        validateHomeAdoption(nowMillis);
        var preview = homeAdoptionPreview;
        HomeEconomySession session = requireHomeEconomy();
        var snapshot = session.snapshot();
        fenceOldHomeWork(snapshot, nowMillis, "owner confirmed existing Home furniture adoption");
        Map<HomeEconomySession.AssetRole, FieldKitLedger.Asset> assets = new LinkedHashMap<>();
        for (var selected : preview.selections()) {
            String id = HomeEconomySession.assetIdPrefix(snapshot.generation(), selected.role()) + "adopted_" + preview.nonce();
            assets.put(selected.role(), workstations.registerOwnerSelectedHomeFurniture(
                    selected, snapshot.home(), HOME_ASSET_RADIUS, id, nowMillis));
        }
        session.ownerRebindAssets(assets, nowMillis);
        invalidateHomeFurniturePreview();
        invalidateHomeStorageObservations();
        homeRunRequested = false;
        homeSleepRequested = false;
        return "Adopted existing furniture: " + preview.detail()
                + "; no blocks placed or items moved; Stock unchanged; work stays stopped until an explicit command.";
    }

    public String previewHomeFurnitureBinding(HomeFurnitureBindingPolicy.Selection selected,
            long policyRevision, String policyDigest, long deadline, long nowMillis) {
        requireTrustedHomeSettings("bind Home furniture");
        if (deadline < nowMillis) throw new IllegalArgumentException("Owner furniture selection expired");
        HomeEconomySession.Snapshot snapshot = requireHomeEconomy().snapshot();
        requireFurniturePolicyIdentity(policyRevision, policyDigest);
        workstations.validateHomeFurnitureSelection(selected, snapshot.home(), HOME_ASSET_RADIUS);
        var candidate = HomeFurnitureBindingPolicy.preview(selected, snapshot,
                policyRevision, policyDigest, nowMillis);
        homeFurniturePreview = new HomeFurnitureBindingPolicy.Preview(candidate.selection(),
                candidate.homeFingerprint(), candidate.generation(), candidate.policyRevision(),
                candidate.policyDigest(), Math.min(deadline, candidate.expiresAtMillis()));
        return "Selected " + selected.blockId() + " at " + selected.position().dimension() + ' '
                + selected.position().x() + ' ' + selected.position().y() + ' ' + selected.position().z()
                + "; /e home bind confirm within 30 seconds binds this exact block to the same Home. "
                + "Old furniture and all inventories remain untouched; binding stops current work.";
    }

    public void validateHomeFurnitureConfirmation(HomeFurnitureBindingPolicy.Selection selected,
            long policyRevision, String policyDigest, long deadline, long nowMillis) {
        validateHomeSelectionConfirmation(homeFurniturePreview, selected,
                policyRevision, policyDigest, deadline, nowMillis);
    }

    private void validateHomeSelectionConfirmation(HomeFurnitureBindingPolicy.Preview preview,
            HomeFurnitureBindingPolicy.Selection selected,
            long policyRevision, String policyDigest, long deadline, long nowMillis) {
        requireTrustedHomeSettings("confirm Home furniture");
        if (deadline < nowMillis) throw new IllegalArgumentException("Owner furniture selection expired");
        HomeEconomySession.Snapshot snapshot = requireHomeEconomy().snapshot();
        requireFurniturePolicyIdentity(policyRevision, policyDigest);
        HomeFurnitureBindingPolicy.requireConfirmation(preview, selected, snapshot,
                policyRevision, policyDigest, nowMillis);
        workstations.validateHomeFurnitureSelection(selected, snapshot.home(), HOME_ASSET_RADIUS);
        requireSettledHomeInventory(snapshot);
    }

    private void requireSettledHomeInventory(HomeEconomySession.Snapshot snapshot) {
        InventoryTransactionEngine.Diagnostics diagnostics = inventory.transactionDiagnostics();
        if (client.player == null || !client.player.isAlive() || client.player.isSleeping()
                || snapshot.pendingTransfer() != null || pendingHomeWaterFill()
                || !inventory.isPlayerInventoryOpen() || !inventory.cursorEmpty()
                || !diagnostics.pendingClickOwner().isBlank() || !diagnostics.cursorOwner().isBlank()
                || diagnostics.pendingRemovals() > 0) {
            throw new IllegalArgumentException("Furniture binding is blocked by an open inventory or unresolved transfer; "
                    + "finish/reconcile it before selecting again. No furniture or contents were changed.");
        }
    }

    public String confirmHomeFurnitureBinding(HomeFurnitureBindingPolicy.Selection selected,
            long policyRevision, String policyDigest, long deadline, long nowMillis) throws IOException {
        validateHomeFurnitureConfirmation(selected, policyRevision, policyDigest, deadline, nowMillis);
        HomeEconomySession session = requireHomeEconomy();
        HomeEconomySession.Snapshot old = session.snapshot();
        fenceOldHomeWork(old, nowMillis, "owner confirmed exact furniture binding");
        // A fresh ledger record is committed before swapping the one Home pin.
        // A crash between stores leaves the previous pin authoritative; the
        // new unpinned Home-only record is never an ordinary FieldKit candidate.
        String assetId = HomeEconomySession.assetIdPrefix(old.generation(), selected.role())
                + "bound_" + selected.blockId().substring("minecraft:".length())
                + '_' + selected.nonce();
        FieldKitLedger.Asset bound = workstations.registerOwnerSelectedHomeFurniture(
                selected, old.home(), HOME_ASSET_RADIUS, assetId, nowMillis);
        session.ownerRebindAsset(selected.role(), bound, nowMillis);
        invalidateHomeStorageObservations();
        homeFurniturePreview = null;
        homeRunRequested = false;
        homeSleepRequested = false;
        homeScreenClosePending = false;
        lastHomeChestCounts = Map.of();
        lastHomeChestFingerprint = "";
        lastHomeChestObservedAt = -1L;
        lastHomeEconomyDetail = "bound owner-selected " + selected.blockId() + " at "
                + selected.position().x() + ' ' + selected.position().y() + ' ' + selected.position().z()
                + "; Home anchor/generation unchanged, old furniture and all contents preserved; "
                + "work remains stopped until an explicit command";
        return lastHomeEconomyDetail;
    }

    public String previewHomeStorage(HomeFurnitureBindingPolicy.Selection selected,
            long revision, String digest, long deadline, long nowMillis) {
        requireTrustedHomeSettings("register Home storage");
        if (selected.role() != HomeEconomySession.AssetRole.CHEST || deadline < nowMillis) {
            throw new IllegalArgumentException("Select a current Home chest");
        }
        HomeEconomySession.Snapshot snapshot = requireHomeEconomy().snapshot();
        requireFurniturePolicyIdentity(revision, digest);
        workstations.validateHomeFurnitureSelection(selected, snapshot.home(), HOME_ASSET_RADIUS);
        var preview = HomeFurnitureBindingPolicy.preview(selected, snapshot, revision, digest, nowMillis);
        homeStoragePreview = new HomeFurnitureBindingPolicy.Preview(selected, preview.homeFingerprint(),
                preview.generation(), revision, digest, Math.min(deadline, preview.expiresAtMillis()));
        return "Add Home storage at " + selected.position().x() + ' ' + selected.position().y() + ' '
                + selected.position().z() + "; /e home storage confirm within 30 seconds. Existing storage and contents stay; "
                + "confirmation stops current work until an explicit command.";
    }

    public void validateHomeStorageConfirmation(HomeFurnitureBindingPolicy.Selection selected,
            long revision, String digest, long deadline, long nowMillis) {
        validateHomeSelectionConfirmation(homeStoragePreview, selected, revision, digest, deadline, nowMillis);
        // Match the durable commit's complete precondition before runtime may
        // cancel Idle/the active mission. Cursor settlement alone does not
        // settle the Home acquisition journal (e.g. an in-progress hunt).
        requireHomeEconomy().validateOwnerStorageMutation("register storage");
        HomeEconomySession.Snapshot snapshot = requireHomeEconomy().snapshot();
        ArrayList<FieldKitLedger.Position> footprint = new ArrayList<>(List.of(selected.position()));
        if (selected.connectedChest() != null) {
            var half = selected.connectedChest();
            footprint.add(new FieldKitLedger.Position(selected.position().dimension(), half.x(), half.y(), half.z()));
        }
        for (var registration : snapshot.storageRegistrations().values()) {
            var asset = workstations.ownedAsset(registration.ledgerAssetId()).orElse(null);
            if (asset != null && footprint.contains(asset.lastKnownPosition())) {
                throw new IllegalArgumentException("That connected chest is already registered as " + registration.id());
            }
        }
    }

    public String confirmHomeStorage(HomeFurnitureBindingPolicy.Selection selected,
            long revision, String digest, long deadline, long nowMillis) throws IOException {
        validateHomeStorageConfirmation(selected, revision, digest, deadline, nowMillis);
        HomeEconomySession session = requireHomeEconomy();
        HomeEconomySession.Snapshot snapshot = session.snapshot();
        fenceOldHomeWork(snapshot, nowMillis, "owner registered Home storage");
        String id = "storage-" + snapshot.revision();
        String assetId = HomeEconomySession.assetIdPrefix(snapshot.generation(), HomeEconomySession.AssetRole.CHEST)
                + "storage_" + selected.nonce();
        var asset = workstations.registerOwnerSelectedHomeFurniture(selected, snapshot.home(),
                HOME_ASSET_RADIUS, assetId, nowMillis);
        session.ownerAddStorage(id, asset, nowMillis);
        homeStoragePreview = null;
        homeRunRequested = false;
        homeSleepRequested = false;
        invalidateHomeStorageObservations();
        return "Registered " + id + "; existing contents preserved. /e stock run uses registered storage.";
    }

    public void validateHomeStorageRemoval(String storageId) {
        requireTrustedHomeSettings("remove Home storage registration");
        requireHomeEconomy().validateOwnerStorageMutation("remove storage");
        var snapshot = requireHomeEconomy().snapshot();
        if (!snapshot.storageRegistrations().containsKey(storageId)) {
            throw new IllegalArgumentException("Unknown storage ID; use /e home storage list");
        }
        requireSettledHomeInventory(snapshot);
    }

    public String removeHomeStorage(String storageId, long nowMillis) throws IOException {
        validateHomeStorageRemoval(storageId);
        HomeEconomySession session = requireHomeEconomy();
        fenceOldHomeWork(session.snapshot(), nowMillis, "owner removed Home storage registration");
        session.ownerRemoveStorage(storageId, nowMillis);
        homeRunRequested = false;
        homeSleepRequested = false;
        invalidateHomeStorageObservations();
        return "Removed registration " + storageId + "; chest and items untouched. "
                + (session.snapshot().storageExplicitlyMissing()
                ? "No storage remains; add one explicitly. No replacement will be built." : "Other registered storage remains.");
    }

    public String homeStorageStatus() {
        var snapshot = requireHomeEconomy().snapshot();
        if (snapshot.storageRegistrations().isEmpty()) return "No registered Home storage. /e home storage add";
        ArrayList<String> lines = new ArrayList<>();
        for (var registration : snapshot.storageRegistrations().values()) {
            var identity = workstations.observeHomeStorageIdentity(registration.id(), registration.ledgerAssetId(),
                    snapshot.home(), HOME_ASSET_RADIUS);
            var observed = homeStorageObservations.get(registration.id());
            String capacity = identity.isEmpty() ? "unavailable; contents unknown"
                    : observed != null && observed.identity().equals(identity.orElseThrow())
                    ? observed.capacity().emptySlots() + " free slots (last observation)"
                    : "available; capacity not yet observed";
            lines.add(registration.id() + (snapshot.primaryStorage() != null && snapshot.primaryStorage().id().equals(registration.id())
                    ? " [primary]" : "") + ": " + capacity);
        }
        return String.join("; ", lines);
    }

    private void invalidateHomeStorageObservations() {
        homeStockForecastInput = null;
        homeStockForecastTargets = HomeStockPolicy.defaults();
        activeHomeStorageId = "";
        homeStorageObservations.clear();
        lastHomeChestCounts = Map.of();
        lastHomeChestFingerprint = "";
        lastHomeChestObservedAt = -1L;
    }

    private void requireFurniturePolicyIdentity(long revision, String digest) {
        if (protectedAreas == null || !protectedAreas.status().synchronizedPolicy()
                || protectedAreas.status().revision() != revision
                || !protectedAreas.status().digest().equals(digest)) {
            throw new IllegalArgumentException("Area policy changed or is unsynchronized; select furniture again");
        }
    }

    /** Explicit direct-user request; owning a bed or observing night grants no authority by itself. */
    public String requestHomeSleep(boolean idleAuthority, long nowMillis) throws IOException {
        return requestHomeSleep(idleAuthority, true, nowMillis);
    }

    private String requestHomeSleep(boolean idleAuthority, boolean explicitOwnerRequest, long nowMillis)
            throws IOException {
        validateHomeSleepRequest(idleAuthority, explicitOwnerRequest);
        HomeEconomySession session = requireHomeEconomy();
        idleSleepFallback.clear();
        clearAutomaticStockRecovery();
        fenceSafeHomeWorkForSleep(session, nowMillis);
        cancelIdleStockMarker("sleep_preempted", "Automatic Stock yielded to owned Home sleep");
        workstations.resetHomeSleepAttempt(homeWorkstationOperation(
                session.snapshot(), HomeEconomySession.AssetRole.BED));
        homeRunRequested = false;
        homeSleepRequested = true;
        lastHomeEconomyDetail = "explicit owned-Home sleep requested";
        return lastHomeEconomyDetail;
    }

    public void validateExplicitHomeSleepRequest() {
        validateHomeSleepRequest(true, true);
    }

    public long verifiedHomeSleepCycles() { return verifiedHomeSleepCycles; }
    public boolean homeSleepRequested() { return homeSleepRequested; }
    public String homeSleepIdentity() {
        if (homeEconomy == null) return "";
        var snapshot = homeEconomy.snapshot();
        return snapshot.home() + ":" + snapshot.pinnedAssets().get(HomeEconomySession.AssetRole.BED);
    }

    private void validateHomeSleepRequest(boolean idleAuthority, boolean explicitOwnerRequest) {
        requireTrustedHomeSettings("request Home sleep");
        HomeEconomySession session = requireHomeEconomy();
        HomeEconomySession.Snapshot snapshot = session.snapshot();
        if (snapshot.home() == null) {
            throw new IllegalArgumentException(
                    "No Home is registered; use /e home set, then /e home adopt for existing furniture; /e help home");
        }
        if (snapshot.block() != null && !HomeSleepPolicy.mayReobserveBlock(
                snapshot.block().code(), snapshot.block().retryable(),
                snapshot.pendingTransfer() != null || snapshot.pendingAcquisition() != null
                        || pendingHomeWaterFill(), explicitOwnerRequest)) {
            throw new IllegalArgumentException(
                    "Home is blocked: " + snapshot.block().detail());
        }
        boolean overworld = client.player != null && client.world != null
                && "minecraft:overworld".equals(currentDimension());
        boolean naturalNight = client.world != null && client.world.isNight();
        if (!HomeSleepPolicy.mayStart(
                true, idleAuthority, overworld, naturalNight)) {
            if (!idleAuthority) {
                throw new IllegalArgumentException(
                        "A direct mission or safety owner is active; finish it before requesting sleep");
            }
            if (!overworld) {
                throw new IllegalArgumentException(
                        "Home sleep is available only in the natural Overworld");
            }
            throw new IllegalArgumentException(
                    "Home sleep can be requested only during the natural night");
        }
        if (!HomeSleepPolicy.hasRegisteredBed(snapshot)) {
            throw new IllegalArgumentException(
                    "Home has no registered bed; use /e home adopt or /e home bind bed for an existing bed; /e home setup explicitly provisions one");
        }
    }

    /** Runtime's automatic branch must identify itself; manual sleep never earns fallback authority. */
    public String requestAutomaticHomeSleep(long nowMillis) throws IOException {
        String detail = requestHomeSleep(true, false, nowMillis);
        idleSleepFallback.begin(homeEconomy.snapshot());
        return detail;
    }

    /** Explicit sleep retires safe background work instead of being held hostage by it. */
    private void fenceSafeHomeWorkForSleep(
            HomeEconomySession session,
            long nowMillis) throws IOException {
        HomeEconomySession.Snapshot snapshot = session.snapshot();
        releaseControls("explicit Home sleep preempted background work", lastClientTick);
        baritone.cancel(0L, "explicit Home sleep preempted background work");
        snapshot = session.reobserveForExplicitSleep(pendingHomeWaterFill(), nowMillis);
        if (snapshot.pendingAcquisition() != null) {
            String planId = snapshot.pendingAcquisition().planId();
            Optional<TaskPlan> plan = plans.restore(planId);
            if (plan.isPresent() && plan.orElseThrow().state() == TaskPlanState.OPEN) {
                plans.clear(planId,
                        "explicit Home sleep preempted safe background acquisition", nowMillis);
            }
            session.abandonAcquisitionForForeground(planId, nowMillis);
            retirePlanIfTerminal(planId, "home-journal:sleep-preempted-acquisition", nowMillis);
            snapshot = session.snapshot();
        }
        InventoryTransactionEngine.Diagnostics diagnostics =
                inventory.transactionDiagnostics();
        boolean inventoryInFlight = !inventory.cursorEmpty()
                || !diagnostics.pendingClickOwner().isBlank();
        if (snapshot.pendingTransfer() != null && !inventoryInFlight) {
            session.abandonTransfer(snapshot.pendingTransfer().id(), nowMillis);
            snapshot = session.snapshot();
        } else if (snapshot.pendingTransfer() != null) {
            // The existing preemption drain owns cursor cargo until its exact
            // acknowledgement completes; bed routing must not overtake it.
            session.pause("explicit sleep waiting for inventory custody", nowMillis);
            snapshot = session.snapshot();
        }
        if (pendingHomeWaterFill() && !inventoryInFlight
                && homeWaterFill.snapshot().intent().phase()
                != HomeWaterFillSession.Phase.AWAITING_RESULT) {
            homeWaterFill.ownerFence(nowMillis);
            if (snapshot.phase() == HomeEconomySession.Phase.ACQUIRING
                    && snapshot.pendingAcquisition() == null) {
                session.abandonWaterFill(nowMillis);
            }
        }
        ClientInventoryController.SafeCloseResult closed =
                inventory.closeHandledScreenIfCursorEmpty(nowMillis);
        homeScreenClosePending = closed != ClientInventoryController.SafeCloseResult.CLOSED;
        workstations.suspendAll();
        reservations.releaseOwner(HOME_RESERVATION_OWNER);
        syncBaritoneThrowawayReservations();
    }

    public String clearHome(long nowMillis) throws IOException {
        HomeEconomySession session = requireHomeEconomy();
        HomeEconomySession.Snapshot snapshot = session.snapshot();
        HomeEconomySession.HomeAnchor current = snapshot.home() != null
                ? snapshot.home() : configuredHome();
        if (current == null) {
            homeControlPreview = null;
            return "Home is already clear; stock economy is "
                    + (settings.stockEnabled() ? "enabled without an anchor" : "disabled");
        }
        homeControlPreview = HomeControlPolicy.clear(current, nowMillis);
        return "Clear preview for Home at " + current.dimension() + ' '
                + current.x() + ' ' + current.y() + ' ' + current.z()
                + ". Run /e home clear confirm to explicitly clear the current Home (no prior preview required). "
                + "This forgets Home and disables stock; it does not break the bed, chest, "
                + "workstations, or destroy chest contents."
                + pendingHomePreviewSuffix(snapshot);
    }

    /** Owner-confirmed recovery boundary; pending Home work can never trap this operation. */
    public String confirmClearHome(long nowMillis) throws IOException {
        HomeEconomySession session = requireHomeEconomy();
        HomeEconomySession.Snapshot snapshot = session.snapshot();
        HomeEconomySession.HomeAnchor current = snapshot.home() != null
                ? snapshot.home() : configuredHome();
        if (current == null) {
            homeControlPreview = null;
            return "Home is already clear; stock economy is "
                    + (settings.stockEnabled() ? "enabled without an anchor" : "disabled");
        }
        HomeControlPolicy.Confirmation confirmation =
                HomeControlPolicy.confirmExplicitClear(current, nowMillis);
        if (!confirmation.valid()) {
            homeControlPreview = null;
            throw new IllegalArgumentException(confirmation.detail());
        }
        fenceOldHomeWork(snapshot, nowMillis, "confirmed owner Home clear");
        // Cursor first for the same crash-order reason as relocation. This is
        // an operator fence, not a claim that pending physical work succeeded.
        session.ownerClearHome(nowMillis);
        AutonomySettingsStore.Settings next = settings.withStockEnabled(false).withoutHome();
        settingsStore.save(next);
        markSettingsValid(next);
        finishHomeIdentityChange(snapshot, nowMillis);
        homeControlPreview = null;
        homeRunRequested = false;
        homeSleepRequested = false;
        lastHomeChestCounts = Map.of();
        lastHomeChestFingerprint = "";
        lastHomeChestObservedAt = -1L;
        lastHomeEconomyDetail = "Home cleared; stock economy disabled; existing Home assets "
                + "and chest contents were preserved as player property";
        // Clear is an owner-control acknowledgement, not a diagnostic dump. Keep the
        // property guarantee and resulting state ahead of the bridge's bounded result.
        return lastHomeEconomyDetail
                + "; home=unset, economy=off, phase=disabled";
    }

    private String pendingHomePreviewSuffix(HomeEconomySession.Snapshot snapshot) {
        ArrayList<String> pending = new ArrayList<>();
        if (snapshot.pendingTransfer() != null) pending.add("transfer");
        if (snapshot.pendingAcquisition() != null) pending.add("acquisition");
        if (pendingHomeWaterFill()) pending.add("water fill");
        if (snapshot.phase() == HomeEconomySession.Phase.BLOCKED) pending.add("blocked repair");
        return pending.isEmpty() ? ""
                : " Confirm will fence pending " + String.join(", ", pending)
                + " without claiming it succeeded.";
    }

    private void requireStationaryHomeAuthority(boolean idleAuthority) {
        if (!idleAuthority) {
            throw new IllegalArgumentException(
                    "Entity must be idle with no direct mission or safety owner before setting Home");
        }
        if (client.player == null || client.world == null) {
            throw new IllegalArgumentException("Join a loaded world before setting Home");
        }
        double horizontalSpeedSquared = client.player.getVelocity().x
                * client.player.getVelocity().x
                + client.player.getVelocity().z * client.player.getVelocity().z;
        if (horizontalSpeedSquared > 0.0025D
                || Math.abs(client.player.getVelocity().y) > 0.08D) {
            throw new IllegalArgumentException(
                    "Entity must be stationary on the desired Home anchor before setting it");
        }
    }

    private void inspectExactHomeCandidate(boolean idleAuthority, BlockPos requested) {
        if (protectedAreas == null) {
            throw new IllegalArgumentException(
                    "Protected-property policy is unavailable; Home cannot be established safely");
        }
        ProtectedAreaPolicy.Decision property = protectedAreas.decide(
                ProtectedAreaPolicy.Action.PLACE,
                currentDimension(), requested.getX(), requested.getY(), requested.getZ(), true);
        if (!property.allowed()) {
            throw new IllegalArgumentException(
                    "Entity Home must be outside player-protected property: "
                            + property.detail());
        }
        WorkstationController.HomeAnchorAssessment assessment =
                workstations.assessHomeAnchor(idleAuthority, requested);
        HomeEconomyPolicy.Eligibility eligibility =
                HomeEconomyPolicy.evaluateHomeCandidate(assessment.candidate());
        if (eligibility.eligible()) return;
        String detail;
        if ("unsupported_floor".equals(eligibility.code())
                && !assessment.anchorDetail().isBlank()) {
            detail = assessment.anchorDetail();
        } else {
            detail = eligibility.detail();
        }
        throw new IllegalArgumentException(
                "Entity's exact feet at " + currentDimension() + ' '
                        + requested.getX() + ' ' + requested.getY() + ' '
                        + requested.getZ() + " cannot be Home: " + detail);
    }

    /** Stops every old-generation actuator before its durable identity is replaced. */
    private void fenceOldHomeWork(
            HomeEconomySession.Snapshot old,
            long nowMillis,
            String reason) throws IOException {
        Objects.requireNonNull(old, "old Home snapshot");
        clearAutomaticStockRecovery();
        String why = Objects.requireNonNullElse(reason, "owner Home generation fence");
        releaseControls(why, lastClientTick);
        baritone.cancel(0L, why);
        clearHomeWorkstationSessions(old.generation());
        if (old.pendingAcquisition() != null) {
            String planId = old.pendingAcquisition().planId();
            Optional<TaskPlan> plan = plans.restore(planId);
            if (plan.isPresent() && plan.orElseThrow().state() == TaskPlanState.OPEN) {
                plans.clear(planId, why + "; no acquisition success claimed", nowMillis);
            }
        }
        if (homeWaterFill != null) homeWaterFill.ownerFence(nowMillis);
        homeWaterAimIntentId = "";
        homeWaterAimPlayerAge = -1;
        homeManualTransferReobserve = false;
        homeManualTransferAbandonIfUnchanged = false;
        reservations.releaseOwner(HOME_RESERVATION_OWNER);
        syncBaritoneThrowawayReservations();
    }

    private void finishHomeIdentityChange(
            HomeEconomySession.Snapshot old,
            long nowMillis) {
        invalidateHomeStorageObservations();
        clearHomeWorkstationSessions(old.generation());
        InventoryTransactionEngine.Diagnostics diagnostics =
                inventory.transactionDiagnostics();
        ClientInventoryController.SafeCloseResult close =
                inventory.closeHandledScreenIfCursorEmpty(nowMillis);
        homeScreenClosePending = close != ClientInventoryController.SafeCloseResult.CLOSED
                || !diagnostics.pendingClickOwner().isBlank()
                || !diagnostics.cursorOwner().isBlank()
                || diagnostics.pendingRemovals() > 0;
    }

    public String requestStockRun(long nowMillis) throws IOException {
        idleSleepFallback.clear();
        clearAutomaticStockRecovery();
        cancelIdleStockMarker("direct_stock", "Replaced by an explicit Stock command");
        requireTrustedHomeSettings("run stock maintenance");
        HomeEconomySession session = requireHomeEconomy();
        HomeEconomySession.HomeAnchor home = homeAnchor().orElse(null);
        HomeEconomyPolicy.Eligibility prerequisite =
                HomeEconomyPolicy.evaluateStockRunPrerequisite(
                        home, settings.stockEnabled());
        if (!prerequisite.eligible()) {
            throw new IllegalArgumentException(prerequisite.detail());
        }
        if (pendingHomeWaterFill()) {
            recoverHomeWaterFillForExplicitRun(nowMillis);
        }
        HomeEconomySession.Snapshot snapshot = session.snapshot();
        if (snapshot.phase() == HomeEconomySession.Phase.BLOCKED) {
            InventoryTransactionEngine.Diagnostics diagnostics =
                    inventory.transactionDiagnostics();
            HomeManualRecoveryPolicy.Decision manual = HomeManualRecoveryPolicy.decide(
                    new HomeManualRecoveryPolicy.Observation(
                            snapshot.block().code(),
                            snapshot.pendingTransfer() != null,
                            snapshot.pendingAcquisition() != null,
                            snapshot.pendingAcquisition() != null
                                    && snapshot.ownsUnderlyingPhase(
                                    HomeEconomySession.Phase.RETURNING_HOME),
                            homeAcquisitionTruth(snapshot),
                            inventory.cursorEmpty(),
                            diagnostics.pendingClickOwner(),
                            diagnostics.cursorOwner(),
                            diagnostics.pendingRemovals()));
            switch (manual.action()) {
                case REOBSERVE_TRANSFER -> {
                    session.retryBlockedForReobservation(nowMillis);
                    homeManualTransferReobserve = true;
                    homeManualTransferAbandonIfUnchanged = switch (snapshot.block().code()) {
                        case "asset_identity_mismatch", "home_binding_changed",
                                "home_settings_missing", "pending_transfer_chest_unverified" -> true;
                        default -> false;
                    };
                }
                case RETIRE_ACQUISITION -> {
                    String planId = snapshot.pendingAcquisition().planId();
                    session.retireAcquisition(planId, nowMillis);
                    retirePlanIfTerminal(
                            planId, "home-journal:manual-retirement-committed", nowMillis);
                    reservations.releaseOwner(HOME_RESERVATION_OWNER);
                    syncBaritoneThrowawayReservations();
                    lastHomeEconomyDetail = "retired stale Home acquisition " + planId
                            + " without claiming its goals";
                }
                case RETRY_ACQUISITION_RETURN -> session.retryBlocked(nowMillis);
                case WAIT_CURSOR_EMPTY, WAIT_TRANSACTION_OWNER ->
                        throw new IllegalArgumentException(manual.detail());
                case NOT_APPLICABLE, REJECT_UNSAFE_STATE -> {
                    if (!snapshot.block().retryable()) {
                        throw new IllegalArgumentException(snapshot.block().detail());
                    }
                    session.retryBlocked(nowMillis);
                }
            }
        }
        if (session.snapshot().pendingTransfer() == null) invalidateHomeStorageObservations();
        homeRunRequested = true;
        return HomeEconomyPolicy.stockRunAcceptedDetail(
                home, session.snapshot().phase());
    }

    /** Explicit recovery classifies durable counts first and issues no click/use. */
    private void recoverHomeWaterFillForExplicitRun(long nowMillis) throws IOException {
        HomeWaterFillSession.Intent intent = homeWaterFill.snapshot().intent();
        if (intent == null) return;
        HomeEconomySession.Snapshot home = homeEconomy.snapshot();
        if (home.phase() == HomeEconomySession.Phase.PAUSED) {
            throw new IllegalArgumentException(
                    "Water fill is paused for higher-priority work; wait for idle ownership");
        }
        InventoryTransactionEngine.Diagnostics diagnostics =
                inventory.transactionDiagnostics();
        if (!inventory.cursorEmpty() || !diagnostics.pendingClickOwner().isBlank()
                || !diagnostics.cursorOwner().isBlank()
                || diagnostics.pendingRemovals() > 0) {
            throw new IllegalArgumentException(
                    "Clear the manual cursor/wait for the inventory acknowledgement; "
                            + "stock run will issue zero recovery clicks");
        }
        int empty = inventory.count("minecraft:bucket");
        int water = inventory.count("minecraft:water_bucket");
        boolean unchanged = empty == intent.emptyBucketBefore()
                && water == intent.waterBucketBefore();
        boolean filled = empty == intent.emptyBucketBefore() - 1
                && water == intent.waterBucketBefore() + 1;
        if (home.phase() == HomeEconomySession.Phase.BLOCKED
                && !home.block().retryable()) {
            throw new IllegalArgumentException(home.block().detail());
        }
        if (unchanged) {
            if (intent.phase() == HomeWaterFillSession.Phase.AWAITING_RESULT
                    && nowMillis - intent.useIssuedAtMillis()
                    < HomeWaterFillPolicy.RESULT_TIMEOUT_MILLIS) {
                throw new IllegalArgumentException(
                        "Persisted water use is still inside its acknowledgement window; retry shortly");
            }
            restoreWaterFillSelection(intent);
            homeWaterFill.abandonUnchanged(intent.id(), nowMillis);
            if (home.phase() == HomeEconomySession.Phase.BLOCKED) {
                homeEconomy.retryBlocked(nowMillis);
            }
            if (homeEconomy.snapshot().phase() == HomeEconomySession.Phase.ACQUIRING) {
                homeEconomy.abandonWaterFill(nowMillis);
            }
            lastHomeEconomyDetail =
                    "retired exact unchanged water-fill intent without claiming success";
            return;
        }
        if (filled) {
            BlockPos source = new BlockPos(
                    intent.sourceX(), intent.sourceY(), intent.sourceZ());
            int mask = workstations.renewableHomeWaterNeighborMask(source);
            if (mask != intent.renewableNeighborMask()) {
                throw new IllegalArgumentException(
                        "Filled bucket is present, but exact renewable source geometry has not "
                                + "restored; restore the source and run stock again");
            }
            if (home.phase() == HomeEconomySession.Phase.BLOCKED) {
                homeEconomy.retryBlocked(nowMillis);
            }
            lastHomeEconomyDetail =
                    "reobserved exact filled-bucket/source truth; awaiting durable commit";
            return;
        }
        throw new IllegalArgumentException(
                "Water-fill bucket counts diverged from both the exact before-state and -1/+1 "
                        + "result; restore bucket counts manually before retrying");
    }

    public String equipmentSummary() {
        if (client.player == null) return "Equipment unavailable: Entity is not in a world.";
        List<String> equipment = new ArrayList<>();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
            ItemStack stack = client.player.getEquippedStack(slot);
            equipment.add(slot.getName() + "=" + (stack.isEmpty() ? "empty"
                    : Registries.ITEM.getId(stack.getItem()).getPath()
                    + (stack.isDamageable() ? " (" + (stack.getMaxDamage() - stack.getDamage()) + "/" + stack.getMaxDamage() + " durability)" : "")));
        }
        return "Equipped: " + String.join(", ", equipment)
                + ". Tools may be carried rather than held: /e inventory. /e gear iron obtains a kit; this query changes nothing.";
    }

    public List<String> inventorySummaryPages(String requestedItem) {
        if (client.player == null) return List.of("Inventory unavailable: Entity is not in a world.");
        return InventoryReport.pages(inventory.counts(), requestedItem);
    }

    /** Accepts Paper's authoritative proof that the intended recipient picked up a handoff. */
    public void acceptDeliveryReceipt(
            String receiptId,
            String missionId,
            String nonce,
            String recipient,
            String itemId,
            int confirmedCount,
            int expectedCount) {
        if (missionId == null || missionId.isBlank() || nonce == null || nonce.isBlank()
                || confirmedCount <= 0 || expectedCount <= 0) return;
        String key = deliveryTransactionKey(missionId, nonce);
        DeliveryReceiptState next = new DeliveryReceiptState(
                Set.of(Objects.requireNonNullElse(receiptId, "")),
                Objects.requireNonNullElse(recipient, ""),
                DeliveryPolicy.normalizeItemId(itemId),
                Math.min(confirmedCount, expectedCount),
                expectedCount);
        deliveryReceipts.merge(key, next, (prior, incoming) -> {
            if (!prior.recipient().equalsIgnoreCase(incoming.recipient())
                    || !prior.itemId().equals(incoming.itemId())
                    || prior.expectedCount() != incoming.expectedCount()) return prior;
            LinkedHashSet<String> receiptIds = new LinkedHashSet<>(prior.receiptIds());
            receiptIds.addAll(incoming.receiptIds());
            return new DeliveryReceiptState(
                    Set.copyOf(receiptIds), prior.recipient(), prior.itemId(),
                    Math.max(prior.confirmedCount(), incoming.confirmedCount()),
                    prior.expectedCount());
        });
    }

    public void acceptDeliveryReturned(
            String returnId,
            String missionId,
            String nonce,
            String recipient,
            String itemId,
            int confirmedCount,
            int expectedCount,
            int remainingCount) {
        if (missionId == null || missionId.isBlank() || nonce == null || nonce.isBlank()
                || returnId == null || returnId.isBlank()) return;
        deliveryReturns.put(
                deliveryTransactionKey(missionId, nonce),
                new DeliveryReturnState(
                        returnId,
                        Objects.requireNonNullElse(recipient, ""),
                        DeliveryPolicy.normalizeItemId(itemId),
                        Math.max(0, confirmedCount),
                        expectedCount,
                        remainingCount));
    }

    /** Injects Paper's exact cancelled BlockBreakEvent into the atomic break owner. */
    public void acceptBlockBreakRejected(
            String dimension,
            int x,
            int y,
            int z,
            long timestamp) {
        workstations.reportServerBreakRejected(dimension, x, y, z, timestamp);
    }

    /**
     * Persists the last live inventory and death coordinates before vanilla replaces the player.
     * This must run on the death screen, not after respawn, because the latter inventory is already
     * empty. The root checkpoint also fences an in-flight Paper handoff from being orphaned by the
     * subsequent execution-generation rebase.
     */
    public String captureDeathContext(
            Mission mission,
            BlockPos deathPosition,
            String deathDimension,
            long nowMillis) throws IOException {
        Objects.requireNonNull(mission, "mission");
        Objects.requireNonNull(deathPosition, "deathPosition");
        Map<String, Integer> capturedInventory = DeathDropRecoveryPolicy.selectCapturedInventory(
                canonicalInventorySnapshot(),
                lastAliveInventoryForDeathRecovery,
                lastAliveInventoryObservedAt,
                nowMillis);
        int capturedCount = DeathDropRecoveryPolicy.total(capturedInventory);

        String recoveryPlanId = recoveryPlanByMission.get(mission.id());
        Optional<TaskPlan> openRecovery = recoveryPlanId == null
                ? Optional.empty()
                : plans.restore(recoveryPlanId)
                .filter(plan -> plan.state() == TaskPlanState.OPEN && !plan.frames().isEmpty());
        Optional<TaskPlan> openMission = plans.restore(mission.id())
                .filter(plan -> plan.state() == TaskPlanState.OPEN && !plan.frames().isEmpty());

        String kind = mission.kind().toLowerCase(Locale.ROOT);
        if (openRecovery.isEmpty() && openMission.isEmpty()
                && (SELF_SUFFICIENT_KINDS.contains(kind) || kind.equals("mine"))) {
            ensureMissionPlan(mission, nowMillis);
            openMission = plans.restore(mission.id())
                    .filter(plan -> plan.state() == TaskPlanState.OPEN && !plan.frames().isEmpty());
        }

        MissionDeathRecoveryPolicy.Decision ownership = MissionDeathRecoveryPolicy.decide(
                capturedCount, openRecovery.isPresent(), openMission.isPresent());
        if (ownership.action() == MissionDeathRecoveryPolicy.Action.NONE) {
            return ownership.detail();
        }

        String planId;
        Optional<TaskPlan> candidate;
        switch (ownership.action()) {
            case CHECKPOINT_RECOVERY -> {
                planId = recoveryPlanId;
                candidate = openRecovery;
            }
            case CHECKPOINT_MISSION -> {
                planId = mission.id();
                candidate = openMission;
            }
            case CREATE_SIDECAR -> {
                planId = MissionDeathRecoveryPolicy.sidecarId(mission.id(), nowMillis);
                LinkedHashMap<String, String> sidecarParameters = new LinkedHashMap<>();
                sidecarParameters.put("parentMission", mission.id());
                sidecarParameters.put("parentKind", mission.kind());
                sidecarParameters.put("description", "recover carried equipment before resuming "
                        + mission.kind());
                LinkedHashMap<String, String> initialSidecarCheckpoint =
                        new LinkedHashMap<>(sidecarParameters);
                recordDeathRecoveryCheckpoint(
                        initialSidecarCheckpoint,
                        capturedInventory,
                        deathPosition,
                        deathDimension,
                        nowMillis);
                PlanFrame.Spec sidecar = new PlanFrame.Spec(
                        MissionDeathRecoveryPolicy.SIDECAR_ROOT_KIND,
                        "inventory",
                        capturedCount,
                        sidecarParameters);
                plans.createOrRestore(
                        planId, sidecar, initialSidecarCheckpoint, nowMillis);
                recoveryPlanByMission.put(mission.id(), planId);
                candidate = plans.restore(planId)
                        .filter(plan -> plan.state() == TaskPlanState.OPEN
                                && !plan.frames().isEmpty());
            }
            case NONE -> throw new IllegalStateException("empty capture was not returned above");
            default -> throw new IllegalStateException(
                    "unsupported death recovery ownership " + ownership.action());
        }
        if (candidate.isEmpty()) return "no open cargo plan accepted the death checkpoint";

        TaskPlan plan = candidate.orElseThrow();
        PlanFrame root = plan.frames().getFirst();
        Map<String, String> checkpoint = new LinkedHashMap<>(root.parameters());
        PlanFrame interrupted = plan.currentFrame().orElse(root);
        if (isCommittedUniversalAction(interrupted)) {
            captureUniversalWorkstationFacts(checkpoint, interrupted);
        }
        recordDeathRecoveryCheckpoint(
                checkpoint,
                capturedInventory,
                deathPosition,
                deathDimension,
                nowMillis);
        clearDeathHandoffCheckpoint(checkpoint);

        captureInFlightHandoff(plan, checkpoint);
        plans.checkpointFrames(
                planId,
                Map.of(root.id(), checkpoint),
                "captured pre-respawn inventory and exact death-site recovery intent",
                nowMillis);
        return "captured " + DeathDropRecoveryPolicy.total(capturedInventory)
                + " carried item(s) at " + deathPosition.getX() + ' ' + deathPosition.getY()
                + ' ' + deathPosition.getZ() + " in " + Objects.requireNonNullElse(deathDimension, "unknown")
                + "; " + ownership.detail()
                + (Boolean.parseBoolean(checkpoint.getOrDefault("deathHandoffPending", "false"))
                ? "; preserved Paper handoff nonce" : "");
    }

    /**
     * Retains the latest alive inventory fact for the vanilla death boundary, where the client
     * inventory may already be empty. The policy accepts this snapshot only for the immediately
     * following, tightly bounded death observation.
     */
    public void observeAliveInventoryForDeathRecovery(long nowMillis) {
        if (nowMillis < 0L) {
            throw new IllegalArgumentException("alive inventory timestamp cannot be negative");
        }
        lastAliveInventoryForDeathRecovery = canonicalInventorySnapshot();
        lastAliveInventoryObservedAt = nowMillis;
    }

    private static void recordDeathRecoveryCheckpoint(
            Map<String, String> checkpoint,
            Map<String, Integer> capturedInventory,
            BlockPos deathPosition,
            String deathDimension,
            long nowMillis) {
        clearDeathRecoveryCheckpoint(checkpoint);
        checkpoint.put("deathRecoveryPending", "true");
        checkpoint.put("deathRecoveryInventory",
                DeathDropRecoveryPolicy.encodeCounts(capturedInventory));
        checkpoint.put("deathRecoveryX", Integer.toString(deathPosition.getX()));
        checkpoint.put("deathRecoveryY", Integer.toString(deathPosition.getY()));
        checkpoint.put("deathRecoveryZ", Integer.toString(deathPosition.getZ()));
        checkpoint.put("deathRecoveryDimension", Objects.requireNonNullElse(deathDimension, ""));
        checkpoint.put("deathRecoveryDiedAt", Long.toString(nowMillis));
        checkpoint.put("deathRecoveryDeadline", Long.toString(
                Math.addExact(nowMillis, DeathDropRecoveryPolicy.MAXIMUM_RECOVERY_MILLIS)));
    }

    public String setStockEnabled(boolean enabled) throws IOException {
        clearAutomaticStockRecovery();
        requireTrustedHomeSettings("change stock settings");
        HomeEconomySession session = requireHomeEconomy();
        if (!enabled && (session.snapshot().pendingTransfer() != null
                || session.snapshot().pendingAcquisition() != null
                || pendingHomeWaterFill())) {
            throw new IllegalArgumentException(
                    "Cannot turn stock off while a durable Home operation is unresolved; "
                            + "use /e stock run and wait for reconciliation");
        }
        AutonomySettingsStore.Settings next = settings.withStockEnabled(enabled);
        settingsStore.save(next);
        markSettingsValid(next);
        reconcileHomeStores(System.currentTimeMillis());
        homeRunRequested = enabled;
        if (enabled) {
        } else {
            homeSleepRequested = false;
        }
        return stockStatus();
    }

    /** Save-before-release preemption boundary for mission/safety/death ownership. */
    public HomePreemptionOutcome pauseHomeEconomy(
            HomeEconomyPolicy.Authority authority,
            long nowMillis) throws IOException {
        Objects.requireNonNull(authority, "authority");
        if (authority == HomeEconomyPolicy.Authority.DIRECT_USER_MISSION) clearAutomaticStockRecovery();
        // Eating and combat both borrow the body, not the player's cleanup order.
        // Only a successor command/death/disconnect revokes the remaining work.
        boolean temporarySafety = authority == HomeEconomyPolicy.Authority.SURVIVAL
                || authority == HomeEconomyPolicy.Authority.PROTECTION;
        if (temporarySafety && tidy.needsForegroundFence()) {
            var pause = tidy.pauseForSafety(nowMillis);
            if (pause.retainLease()) return HomePreemptionOutcome.blocked(pause.detail());
        } else if (authority != HomeEconomyPolicy.Authority.IDLE && tidy.needsForegroundFence()) {
            tidy.cancel("Cleanup cancelled for " + authority.name().toLowerCase(Locale.ROOT));
        }
        if (!temporarySafety && tidy.drainPending()) {
            var drain = tidy.drain(nowMillis);
            if (drain.retainLease()) return HomePreemptionOutcome.blocked(drain.detail());
        }
        if (!settingsLoadState.authoritative()) {
            return HomePreemptionOutcome.terminal(homeSettingsQuarantineStatus());
        }
        if (authority == HomeEconomyPolicy.Authority.IDLE || homeEconomy == null) {
            return HomePreemptionOutcome.terminal("Home economy has no active ownership");
        }
        // A live bed request is suspended, not replaced, by a temporary body
        // borrow. Keep only that attempt's existing failed-bed fallback; all
        // real revocations (and already-settled requests) still fence it.
        if (!temporarySafety || !homeSleepRequested) idleSleepFallback.clear();
        if (authority == HomeEconomyPolicy.Authority.DIRECT_USER_MISSION) {
            homeRunRequested = false;
            homeSleepRequested = false;
        }
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        if (snapshot.enabled() && snapshot.phase() != HomeEconomySession.Phase.PAUSED) {
            if (authority == HomeEconomyPolicy.Authority.DEATH_RECOVERY) {
                homeEconomy.pauseForDeath(nowMillis);
            } else {
                homeEconomy.pause(authority.name().toLowerCase(Locale.ROOT), nowMillis);
            }
        }
        // A live automatic cycle keeps its admission provenance along with its
        // pending acquisition. The route-failure wrapper is for retired cycles.
        if (authority != HomeEconomyPolicy.Authority.DIRECT_USER_MISSION
                && !(temporarySafety && idleStockCycle != null))
            idleStockRouteRecovery.paused(snapshot, homeEconomy.snapshot());
        if (stoppedCapacityCleanupPending() || issuedCapacityTransferCustodyPending()) workstations.suspendAllKeepingOpenScreen();
        else workstations.suspendAll();
        reservations.releaseOwner(HOME_RESERVATION_OWNER);
        syncBaritoneThrowawayReservations();
        HomePreemptionOutcome drain = drainHomePreemption(nowMillis, temporarySafety);
        String authorityDetail = "paused for "
                + authority.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        lastHomeEconomyDetail = drain.terminal()
                ? authorityDetail
                : authorityDetail + "; " + drain.detail();
        return new HomePreemptionOutcome(
                drain.terminal(), drain.inventoryActuationBlocked(),
                lastHomeEconomyDetail);
    }

    /** Stop is immediate: persist the pause but issue no reconciliation clicks. */
    public void ownerStopHomeEconomy(long nowMillis) throws IOException {
        ownerStopHomeEconomy(nowMillis, true);
    }

    /** Automatic cancellation may wake only its captured SLEEP job, not unrelated native Sleep. */
    public void ownerStopHomeEconomy(long nowMillis, boolean cancelSleep) throws IOException {
        if (cancelSleep) MinecraftActuatorGateway.shared(client).stopSleeping();
        idleSleepFallback.clear();
        try { stopHomeEconomy(nowMillis, true); }
        finally { clearAutomaticStockRecovery(); }
    }

    /** Non-temporary safety/time/disconnect revoke actions, preserving only their exact route-failure wrapper. */
    public void interruptIdleHomeEconomy(long nowMillis) throws IOException {
        interruptIdleHomeEconomy(nowMillis, false);
    }

    public void interruptIdleHomeEconomy(long nowMillis, boolean cancelSleep) throws IOException {
        if (cancelSleep) MinecraftActuatorGateway.shared(client).stopSleeping();
        idleSleepFallback.clear();
        var before = homeEconomy == null ? null : homeEconomy.snapshot();
        stopHomeEconomy(nowMillis, false);
        idleStockRouteRecovery.paused(before, homeEconomy == null ? null : homeEconomy.snapshot());
    }

    private void stopHomeEconomy(long nowMillis, boolean ownerStop) throws IOException {
        if (idleStockCycle != null && homeEconomy != null) {
            var automatic = homeEconomy.snapshot();
            if (automatic.generation() == idleStockCycle.homeGeneration)
                cancelledIdleStock = new IdleStockCancellation(idleStockCycle.homeGeneration,
                    automatic.pendingAcquisition() == null ? "" : automatic.pendingAcquisition().planId(),
                    automatic.pendingTransfer() == null ? "" : automatic.pendingTransfer().id());
        }
        cancelIdleStockMarker(ownerStop ? "owner_stop" : "automatic_yield",
                ownerStop ? "Automatic Stock cancelled by owner" : "Automatic Stock yielded to higher-priority work");
        tidy.cancel(ownerStop ? "Owner Stop fenced all unissued cleanup; issued physical receipts remain observable"
                : "Automatic work yielded; issued physical receipts remain observable");
        homeRunRequested = false;
        homeSleepRequested = false;
        if (homeEconomy == null || !settingsLoadState.authoritative()) return;
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        if (snapshot.enabled() && snapshot.phase() != HomeEconomySession.Phase.PAUSED) {
            homeEconomy.pause(ownerStop ? "owner_stop" : "automatic_yield", nowMillis);
            snapshot = homeEconomy.snapshot();
        }
        InventoryTransactionEngine.Diagnostics diagnostics =
                inventory.transactionDiagnostics();
        homeScreenClosePending = snapshot.pendingTransfer() != null
                || pendingHomeWaterFill()
                || !inventory.cursorEmpty()
                || !diagnostics.pendingClickOwner().isBlank();
        if (ownerStop || stoppedCapacityCleanupPending()) workstations.suspendAllKeepingOpenScreen();
        else workstations.suspendAll();
        reservations.releaseOwner(HOME_RESERVATION_OWNER);
        syncBaritoneThrowawayReservations();
        lastHomeEconomyDetail = ownerStop ? "paused by owner stop; no background work is authorized"
                : "automatic work paused until higher-priority work and physical custody settle";
        settleCancelledIdleStock(nowMillis);
    }

    /**
     * Advances only the exact persisted Home transfer while another layer is
     * waiting. It never adopts or generically places a foreign cursor.
     */
    private HomePreemptionOutcome drainHomePreemption(long nowMillis) throws IOException {
        return drainHomePreemption(nowMillis, false);
    }

    private HomePreemptionOutcome drainHomePreemption(long nowMillis, boolean preservePausedTidy) throws IOException {
        if (stoppedCapacityCleanupPending()) {
            HomePreemptionOutcome stopped = drainStoppedCapacityTransfer(nowMillis);
            if (!stopped.terminal()) return stopped;
        }
        HomePreemptionOutcome capacity = drainActiveCapacityTransfer(nowMillis);
        if (capacity != null && !capacity.terminal()) return capacity;
        // pauseForSafety already settled the issued click/cursor. Draining its
        // retained partial receipt again would revoke/block the resumable suffix.
        // Durable Home transfers below still reconcile normally for every layer.
        if (!preservePausedTidy && tidy.drainPending()) {
            var drain = tidy.drain(nowMillis);
            if (drain.retainLease()) return HomePreemptionOutcome.blocked(drain.detail());
        }
        if (homeEconomy == null) {
            homeScreenClosePending = false;
            return HomePreemptionOutcome.terminal("Home economy store is unavailable");
        }
        inventory.reconcilePendingTransaction(nowMillis);
        HomePreemptionOutcome waterDrain = drainHomeWaterFillPreemption(nowMillis);
        if (waterDrain != null) return waterDrain;
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        HomeEconomySession.PendingTransfer pending = snapshot.pendingTransfer();
        HomeEconomySession.StorageRegistration storage = pending == null ? null : selectedHomeStorage(snapshot);
        boolean exactChest = pending != null && snapshot.home() != null
                && storage != null
                && workstations.exactPinnedStorageIdentity(storage.id(),
                homeWorkstationOperation(snapshot, HomeEconomySession.AssetRole.CHEST),
                storage.ledgerAssetId(), snapshot.home(), HOME_ASSET_RADIUS)
                .filter(identity -> pending.containerIdentity() == null
                        ? identity.slotCount() == 27 : identity.equals(pending.containerIdentity())).isPresent();
        ClientInventoryController.HomeTransferPlan plan = pending == null
                ? null : homeTransferPlan(pending);
        String owner = pending == null ? "" : homeTransferOwner(pending);
        boolean exactCustody = pending != null
                && inventory.hasExactHomeTransferCustody(plan, owner);
        InventoryTransactionEngine.Diagnostics diagnostics =
                inventory.transactionDiagnostics();
        HomePreemptionDrainPolicy.Decision gate = HomePreemptionDrainPolicy.decide(
                new HomePreemptionDrainPolicy.Observation(
                        pending != null,
                        exactChest,
                        inventory.cursorEmpty(),
                        exactCustody,
                        !diagnostics.pendingClickOwner().isBlank()));
        homeScreenClosePending = true;
        switch (gate.action()) {
            case REQUIRE_MANUAL_CURSOR_CLEAR -> {
                return HomePreemptionOutcome.blocked(gate.detail());
            }
            case WAIT_EXACT_CHEST_OBSERVATION -> {
                return HomePreemptionOutcome.blocked(gate.detail());
            }
            case WAIT_ACKNOWLEDGEMENT -> {
                return HomePreemptionOutcome.blocked(gate.detail());
            }
            case SAFE_CLOSE -> {
                ClientInventoryController.SafeCloseResult closed =
                        inventory.closeHandledScreenIfCursorEmpty(nowMillis);
                if (closed == ClientInventoryController.SafeCloseResult.CLOSED) {
                    homeScreenClosePending = false;
                    return HomePreemptionOutcome.terminal(gate.detail());
                }
                return HomePreemptionOutcome.blocked(
                        closed == ClientInventoryController.SafeCloseResult.CURSOR_OCCUPIED
                                ? "foreign/manual cursor appeared; Entity issued zero clicks"
                                : "waiting for the final inventory acknowledgement before close");
            }
            case ADVANCE_EXACT_TRANSFER -> {
                return drainExactPausedHomeTransfer(snapshot, pending, plan, owner, nowMillis);
            }
        }
        throw new IllegalStateException("unhandled Home preemption action " + gate.action());
    }

    /**
     * A sent water-use packet is an inventory mutation even though it never
     * owns the cursor.  Keep the successor fenced until its exact counts are
     * committed or proved unchanged, then restore the user's prior hotbar
     * selection before releasing ownership.
     */
    private HomePreemptionOutcome drainHomeWaterFillPreemption(long nowMillis)
            throws IOException {
        if (!pendingHomeWaterFill()) return null;
        HomeWaterFillSession.Intent intent = homeWaterFill.snapshot().intent();
        InventoryTransactionEngine.Diagnostics transactions =
                inventory.transactionDiagnostics();
        if (!inventory.cursorEmpty()) {
            return HomePreemptionOutcome.blocked(
                    "manual/foreign cursor appeared during water fill; Entity issued zero clicks");
        }
        if (!transactions.pendingClickOwner().isBlank()) {
            return HomePreemptionOutcome.blocked(
                    "waiting for exact bucket-selection acknowledgement before handoff");
        }
        HomeEconomySession.Snapshot home = homeEconomy.snapshot();
        BlockPos source = new BlockPos(
                intent.sourceX(), intent.sourceY(), intent.sourceZ());
        int mask = workstations.renewableHomeWaterNeighborMask(source);
        Optional<WorkstationController.RenewableWaterSource> exact =
                home.home() == null ? Optional.empty()
                        : workstations.inspectRenewableHomeWaterSource(
                        home.home(), source, intent.renewableNeighborMask(),
                        HOME_WATER_SEARCH_RADIUS);
        boolean selectedBucket = client.player != null
                && client.player.getInventory().getSelectedSlot() == intent.hotbarSlot()
                && client.player.getInventory().getStack(intent.hotbarSlot()).isOf(Items.BUCKET);
        HomeWaterFillPolicy.Decision decision = HomeWaterFillPolicy.decide(
                intent,
                new HomeWaterFillPolicy.Observation(
                        home.home() != null
                                && home.generation() == intent.homeGeneration()
                                && home.home().fingerprint().equals(intent.homeFingerprint())
                                && home.home().dimension().equals(intent.dimension()),
                        true,
                        false,
                        inventory.count("minecraft:bucket"),
                        inventory.count("minecraft:water_bucket"),
                        selectedBucket,
                        exact.map(WorkstationController.RenewableWaterSource::reachableNow)
                                .orElse(false),
                        mask == intent.renewableNeighborMask(),
                        mask,
                        nowMillis));
        return switch (decision.action()) {
            case WAIT_ACKNOWLEDGEMENT -> HomePreemptionOutcome.blocked(decision.detail());
            case WAIT_RESULT -> HomePreemptionOutcome.blocked(decision.detail());
            case COMMIT_FILLED -> {
                restoreWaterFillSelection(intent);
                homeWaterFill.complete(intent.id(), nowMillis);
                homeEconomy.completeWaterFillWhilePaused(nowMillis);
                yield null;
            }
            case RETRY_UNCHANGED -> {
                homeWaterFill.retryUnchanged(intent.id(), nowMillis);
                restoreWaterFillSelection(intent);
                yield null;
            }
            case BLOCK -> {
                if (intent.phase() != HomeWaterFillSession.Phase.BLOCKED) {
                    homeWaterFill.block(intent.id(), decision.detail(), nowMillis);
                }
                if (home.phase() == HomeEconomySession.Phase.PAUSED
                        && home.pause().resumePhase() == HomeEconomySession.Phase.ACQUIRING) {
                    homeEconomy.blockWaterFillWhilePaused(
                            decision.code(), decision.detail(), true, nowMillis);
                }
                restoreWaterFillSelection(intent);
                yield null;
            }
            case SELECT_BUCKET, ROUTE_TO_SOURCE, ISSUE_USE -> {
                // No item-use or inventory click remains in flight.  Keep the
                // durable intent for resume but return selection ownership now.
                restoreWaterFillSelection(intent);
                yield null;
            }
        };
    }

    private HomePreemptionOutcome drainExactPausedHomeTransfer(
            HomeEconomySession.Snapshot snapshot,
            HomeEconomySession.PendingTransfer pending,
            ClientInventoryController.HomeTransferPlan plan,
            String owner,
            long nowMillis) throws IOException {
        if (!inventory.homeTransferClicksSettled(nowMillis))
            return HomePreemptionOutcome.blocked("waiting for exact Home transfer click acknowledgement before drain");
        ClientInventoryController.HomeTransferObservation observed =
                inventory.observeHomeTransfer(plan);
        if (observed.syncId() < 0) {
            return HomePreemptionOutcome.blocked(
                    "exact Home chest observation disappeared during preemption drain");
        }
        HomeEconomyPolicy.TransferObservation truth =
                new HomeEconomyPolicy.TransferObservation(
                        observed.syncId(), observed.cursorEmpty(),
                        inventory.hasExactHomeTransferCustody(plan, owner),
                        observed.playerItemCount(), observed.chestItemCount());
        if (observed.cursorEmpty()) {
            HomeEconomyPolicy.TransferResolution resolution =
                    HomeEconomyPolicy.reconcileTransfer(pending, truth);
            if (resolution.truth() == HomeEconomyPolicy.TransferTruth.COMMITTED) {
                if (!releaseSettledHomeTransferCustody(pending)) {
                    return HomePreemptionOutcome.blocked(
                            "settled Home transfer retained foreign cursor custody");
                }
                homeEconomy.completeTransferWhilePaused(pending.id(), nowMillis);
                logCommittedHomeTransfer(pending, truth);
                return closeSettledHomePreemption(nowMillis,
                        "committed exact Home transfer before inventory handoff");
            }
            if (resolution.needsRebase()) {
                int remaining = pending.count() - resolution.movedCount();
                Optional<ClientInventoryController.HomeTransferPlan> replacement =
                        inventory.planHomeChestTransfer(
                                pending.direction(), pending.item(), remaining,
                                homeProtectedCounts());
                if (replacement.isEmpty()) {
                    homeEconomy.blockTransferWhilePaused(
                            "transfer_rebase_slots_unavailable",
                            "Observed safe transfer progress, but no exact slot is available for "
                                    + remaining + " remaining " + pending.item(),
                            true, nowMillis);
                    return closeSettledHomePreemption(nowMillis,
                            "blocked the rebased transfer with an empty conserved cursor");
                }
                HomeStockPolicy.Transfer remainingTransfer = new HomeStockPolicy.Transfer(
                        pending.direction(), pending.categoryId(), pending.item(),
                        replacement.orElseThrow().count());
                HomeEconomySession.PendingTransfer rebased = pendingTransfer(
                        pending.id(), remainingTransfer, replacement.orElseThrow(), nowMillis);
                homeEconomy.rebaseTransferWhilePaused(pending.id(), rebased, nowMillis);
                return HomePreemptionOutcome.blocked(
                        "durably rebased " + remaining + " remaining Home item(s); draining next tick");
            }
            if (resolution.truth() == HomeEconomyPolicy.TransferTruth.DIVERGED) {
                homeEconomy.blockTransferWhilePaused(
                        resolution.code(), resolution.detail(), false, nowMillis);
                return closeSettledHomePreemption(nowMillis,
                        "blocked divergent Home counts with an empty cursor");
            }
        }

        ClientInventoryController.HomeTransferResult result =
                inventory.homeChestTransferTick(plan, owner, nowMillis);
        return switch (result) {
            case CLICKED -> HomePreemptionOutcome.blocked(
                    "issued one exact acknowledgement-fenced drain click");
            case WAITING -> HomePreemptionOutcome.blocked(
                    "waiting for the exact Home drain click acknowledgement");
            case COMPLETE -> {
                ClientInventoryController.HomeTransferObservation after =
                        inventory.observeHomeTransfer(plan);
                HomeEconomyPolicy.TransferObservation settledObservation =
                        new HomeEconomyPolicy.TransferObservation(
                                after.syncId(), after.cursorEmpty(),
                                inventory.hasExactHomeTransferCustody(plan, owner),
                                after.playerItemCount(), after.chestItemCount());
                HomeEconomyPolicy.TransferResolution resolution =
                        HomeEconomyPolicy.reconcileTransfer(pending, settledObservation);
                if (!resolution.committed()) {
                    yield HomePreemptionOutcome.blocked(
                            "Home controller reported complete before exact aggregate truth settled");
                }
                if (!releaseSettledHomeTransferCustody(pending)) {
                    yield HomePreemptionOutcome.blocked(
                            "settled Home transfer retained foreign cursor custody");
                }
                homeEconomy.completeTransferWhilePaused(pending.id(), nowMillis);
                logCommittedHomeTransfer(pending, settledObservation);
                yield closeSettledHomePreemption(nowMillis,
                        "finished the persisted Home count and returned its exact remainder");
            }
            case CURSOR_NOT_OWNED -> HomePreemptionOutcome.blocked(
                    "foreign/manual cursor is not exact Home custody; Entity issued zero clicks");
            case NEEDS_OWNED_CHEST, STALE_HANDLER -> HomePreemptionOutcome.blocked(
                    "exact owned Home chest identity changed; waiting to reobserve without clicking");
            case SLOT_CHANGED, SOURCE_CHANGED, DESTINATION_CHANGED ->
                    HomePreemptionOutcome.blocked(
                            "Home slots changed; waiting for empty-cursor aggregate reconciliation");
            case COUNTS_DIVERGED -> HomePreemptionOutcome.blocked(
                    "Home counts are not yet a conserved terminal state; no successor inventory actuation");
        };
    }

    private HomePreemptionOutcome closeSettledHomePreemption(
            long nowMillis,
            String detail) {
        ClientInventoryController.SafeCloseResult closed =
                inventory.closeHandledScreenIfCursorEmpty(nowMillis);
        if (closed == ClientInventoryController.SafeCloseResult.CLOSED) {
            homeScreenClosePending = false;
            return HomePreemptionOutcome.terminal(detail);
        }
        homeScreenClosePending = true;
        return HomePreemptionOutcome.blocked(detail + "; waiting for safe close acknowledgement");
    }

    /** Advances only under Runtime's priority-zero IDLE lease. */
    public HomeIdleOutcome tickHomeEconomy(
            ControlLease lease,
            long clientTick,
            long nowMillis) throws IOException {
        Objects.requireNonNull(lease, "lease");
        if (!settingsLoadState.authoritative()) {
            lastHomeEconomyDetail = homeSettingsQuarantineStatus();
            return HomeIdleOutcome.release(lastHomeEconomyDetail);
        }
        if (clientTick < lastClientTick) {
            throw new IllegalArgumentException("clientTick cannot move backwards");
        }
        lastClientTick = clientTick;
        if (tidy.needsLease()) {
            var outcome = tidy.tick(lease, clientTick, nowMillis);
            lastHomeEconomyDetail = outcome.detail();
            return outcome.retainLease() ? homeRunning(outcome.detail()) : HomeIdleOutcome.release(outcome.detail());
        }
        HomeEconomySession session = requireHomeEconomy();
        if (session.snapshot().pendingTransfer() != null && !inventory.homeTransferClicksSettled(nowMillis))
            return homeRunning("waiting for exact Home transfer click acknowledgement before crediting inventory");
        String storeStatus = reconcileHomeStores(nowMillis);
        if (homeScreenClosePending) {
            workstations.suspendAll();
            HomeEconomySession.Snapshot closingSnapshot = session.snapshot();
            if (closingSnapshot.phase() == HomeEconomySession.Phase.PAUSED
                    && (!inventory.cursorEmpty()
                    || homeSleepRequested && closingSnapshot.pendingTransfer() != null)) {
                HomePreemptionOutcome drain = drainHomePreemption(nowMillis);
                if (!drain.terminal()) return homeRunning(drain.detail());
                return HomeIdleOutcome.release(drain.detail());
            }
            ClientInventoryController.SafeCloseResult closed =
                    inventory.closeHandledScreenIfCursorEmpty(nowMillis);
            if (closed != ClientInventoryController.SafeCloseResult.CLOSED) {
                return homeRunning(
                        closed == ClientInventoryController.SafeCloseResult.CURSOR_OCCUPIED
                                ? "waiting for manual/foreign cursor clear; Entity will issue zero clicks"
                                : "reconciling the final Home click acknowledgement before close");
            }
            homeScreenClosePending = false;
            // Idle authority may now resume the persisted phase in this tick.
        }
        if (!homeRunRequested && !homeSleepRequested)
            return HomeIdleOutcome.release("Home inventory acknowledgement settled; no work is authorized");
        HomeEconomySession.Snapshot snapshot = session.snapshot();
        if (idleStockCycle != null && idleStockCycle.homeGeneration != snapshot.generation()) {
            ownerStopHomeEconomy(nowMillis);
            return HomeIdleOutcome.release("Automatic Stock Home generation changed; no old job was resumed");
        }
        if (snapshot.phase() == HomeEconomySession.Phase.PAUSED) {
            session.resume(nowMillis);
            snapshot = session.snapshot();
        }
        if (!HomeSleepPolicy.hasWorkAuthority(snapshot, homeRunRequested, homeSleepRequested)) {
            lastHomeEconomyDetail = storeStatus.isBlank()
                    ? "stock maintenance is disabled" : storeStatus;
            return HomeIdleOutcome.release(lastHomeEconomyDetail);
        }
        if (snapshot.phase() == HomeEconomySession.Phase.BLOCKED) {
            if (homeRunRequested && snapshot.block().retryable()) {
                session.retryBlocked(nowMillis);
                snapshot = session.snapshot();
            } else {
                lastHomeEconomyDetail = snapshot.block().detail();
                return HomeIdleOutcome.release("blocked: " + lastHomeEconomyDetail);
            }
        }
        HomeEconomySession.HomeAnchor settingsHome = configuredHome();
        HomeEconomySession.HomeAnchor operationHome = snapshot.pendingTransfer() != null
                && snapshot.home() != null ? snapshot.home() : settingsHome;
        if (operationHome == null) {
            lastHomeEconomyDetail = "use /e home set at a safe location, then /e home adopt for existing furniture; /e help home";
            return HomeIdleOutcome.release(lastHomeEconomyDetail);
        }
        if (client.player == null || client.world == null) {
            lastHomeEconomyDetail = "waiting for a loaded world/player";
            return HomeIdleOutcome.release(lastHomeEconomyDetail);
        }
        if (pendingHomeWaterFill()) {
            return tickPendingHomeWaterFill(lease, clientTick, nowMillis);
        }

        if (homeSleepRequested) {
            if (!homeSleepDue(snapshot)) {
                homeSleepRequested = false;
                idleSleepFallback.clear();
                lastHomeEconomyDetail =
                        "explicit Home sleep request expired before a sleep cycle began";
                return HomeIdleOutcome.release(lastHomeEconomyDetail);
            }
            if (HomeSleepPolicy.bedCustodyReady(
                    snapshot.pendingTransfer() != null,
                    snapshot.pendingAcquisition() != null,
                    pendingHomeWaterFill(), inventory.cursorEmpty(),
                    !inventory.transactionDiagnostics().pendingClickOwner().isBlank())) {
                // Sleep uses the exact bound bed's load/range/access/authority
                // checks. The old crafting anchor and unrelated furniture do
                // not authorize or prohibit this explicit player action.
                return tickHomeSleep(snapshot, lease, clientTick, nowMillis);
            }
            if (snapshot.pendingTransfer() != null) {
                session.pause("explicit sleep waiting for inventory custody", nowMillis);
            }
            homeScreenClosePending = true;
            return homeRunning("settling existing inventory custody before explicit sleep");
        }

        // A finite automatic resource job is not authority to provision unrelated Home furniture.
        // Its real compiler/actuator still requires whichever owned station the recipe actually uses.
        if (idleStockCycle != null && snapshot.pendingAcquisition() != null)
            return tickHomeAcquisition(snapshot.pendingAcquisition(), lease, clientTick, nowMillis);
        EnumMap<HomeEconomySession.AssetRole, HomeEconomyPolicy.AssetObservation> assets =
                observeHomeAssets(snapshot);
        if (snapshot.storageExplicitlyMissing()) {
            return blockHome("home_storage_missing", "No registered storage remains; use /e home storage add. "
                    + "No replacement chest will be built.", false, nowMillis);
        }
        if (atHome(operationHome) && snapshot.pendingAcquisition() == null
                && !snapshot.storageRegistrations().isEmpty() && selectedHomeStorage(snapshot) == null) {
            return blockHome("home_storage_unavailable", "Registered storage is unavailable; its contents are unknown. "
                    + "Restore access or register another chest.", true, nowMillis);
        }
        boolean atHome = atHome(operationHome);
        boolean homeLoaded = homeLoaded(operationHome);
        boolean workspaceSafe = atHome
                && workstations.homeWorkspaceSafe(operationHome, HOME_ASSET_RADIUS);
        boolean workspaceRepairable = atHome && !workspaceSafe
                && workstations.homeWorkspaceRepairable(
                operationHome, HOME_ASSET_RADIUS);
        HomeEconomyPolicy.AcquisitionTruth acquisitionTruth =
                homeAcquisitionTruth(snapshot);
        String acquisitionDetail = homeAcquisitionDetail(snapshot.pendingAcquisition());
        Map<String, Integer> protectedCounts = homeProtectedCounts();
        ClientInventoryController.HomeChestSnapshot chest =
                ClientInventoryController.HomeChestSnapshot.closed();

        boolean transferChestVerified = pendingTransferChestVerified(snapshot, assets);
        if (snapshot.pendingAcquisition() == null
                && atHome && workspaceSafe
                && (snapshot.pendingTransfer() != null
                ? transferChestVerified : idleStockCycle != null || allHomeAssetsPlacedVerified(assets))) {
            HomeIdleOutcome open = ensureOwnedHomeChestOpen(
                    snapshot, lease, clientTick, nowMillis);
            if (open != null) return open;
            chest = inventory.homeChestSnapshot(protectedCounts);
            if (!chest.open()) {
                return homeRunning("owned chest acknowledgement disappeared before observation");
            }
            if (!chest.cursorEmpty() && snapshot.pendingTransfer() == null) {
                return homeRunning(
                        "reconciling the owned chest cursor before any Home decision");
            }
            rememberHomeChestObservation(snapshot, chest, nowMillis);
            if (snapshot.pendingTransfer() == null && chest.cursorEmpty()) {
                String unobserved = validHomeStorage(snapshot).keySet().stream()
                        .filter(id -> !homeStorageObservations.containsKey(id)).findFirst().orElse(null);
                if (unobserved != null) return switchHomeStorage(unobserved, nowMillis);
            }
        }

        HomeEconomyPolicy.TransferObservation transferObservation = null;
        if (idleStockCycle != null && snapshot.pendingAcquisition() == null && snapshot.pendingTransfer() == null
                && chest.open() && chest.cursorEmpty()) {
            return tickIdleStockAtChest(snapshot, chest, protectedCounts, nowMillis);
        }
        if (snapshot.pendingTransfer() != null && chest.open()) {
            ClientInventoryController.HomeTransferPlan transferPlan =
                    homeTransferPlan(snapshot.pendingTransfer());
            String transferOwner = homeTransferOwner(snapshot.pendingTransfer());
            ClientInventoryController.HomeTransferObservation observed =
                    inventory.observeHomeTransfer(transferPlan);
            if (observed.syncId() >= 0) {
                transferObservation = new HomeEconomyPolicy.TransferObservation(
                        observed.syncId(), observed.cursorEmpty(),
                        inventory.hasExactHomeTransferCustody(transferPlan, transferOwner),
                        observed.playerItemCount(), observed.chestItemCount());
            }
        }
        boolean chestCapacity = true;
        List<HomeStockPolicy.Target> stockTargets = forecastHomeStockTargets(snapshot, chest);
        if (chest.open() && snapshot.pendingTransfer() == null) {
            HomeStockPolicy.Analysis preview = HomeStockPolicy.analyze(
                    chest.playerCounts(), aggregateHomeStorageCounts(snapshot), chest.depositEligibleCounts(), stockTargets);
            if (!preview.deposits().isEmpty()) {
                HomeStockPolicy.Transfer deposit = preview.deposits().getFirst();
                chestCapacity = storageForTransfer(snapshot, deposit) != null;
            }
        }
        HomeEconomyPolicy.Observation observation = new HomeEconomyPolicy.Observation(
                HomeEconomyPolicy.Authority.IDLE,
                settingsHome,
                atHome,
                homeLoaded,
                workspaceSafe,
                workspaceRepairable,
                assets,
                chest.open(),
                chestCapacity,
                chest.playerCounts(),
                snapshot.pendingTransfer() == null ? aggregateHomeStorageCounts(snapshot) : chest.chestCounts(),
                chest.depositEligibleCounts(),
                acquisitionTruth,
                acquisitionDetail,
                resourceCatalogReady,
                homeWaterFill != null && workstations.findRenewableHomeWaterSource(
                        operationHome, HOME_WATER_SEARCH_RADIUS).isPresent(),
                transferObservation, inventory.hasEmptyInventorySlot());
        HomeEconomyPolicy.Decision decision = HomeEconomyPolicy.decide(
                session.snapshot(), observation, stockTargets);
        if (idleStockCycle != null && Set.of(HomeEconomyPolicy.Action.ACQUIRE_ASSET,
                HomeEconomyPolicy.Action.REPLACE_ASSET, HomeEconomyPolicy.Action.PLACE_ASSET,
                HomeEconomyPolicy.Action.VERIFY_ASSET).contains(decision.action()))
            return finishIdleStock(IdleStockPolicy.State.DEFERRED, "home_assets_unavailable",
                    "Existing Home assets need owner attention; automatic Stock will not establish or replace Home", nowMillis);
        lastHomeEconomyDetail = decision.detail();

        return switch (decision.action()) {
            case WAIT_DISABLED, REQUIRE_HOME -> HomeIdleOutcome.release(decision.detail());
            case RESUME_CHECKPOINT -> {
                session.resume(nowMillis);
                yield homeRunning(decision.detail());
            }
            case BIND_CONFIGURED_HOME -> {
                reconcileHomeStores(nowMillis);
                yield homeRunning(decision.detail());
            }
            case PAUSE_FOR_HIGHER_PRIORITY -> throw new IllegalStateException(
                    "IDLE Home tick produced a higher-priority pause");
            case REPORT_BLOCKED -> blockHome(decision, nowMillis);
            case RETURN_HOME -> tickReturnHome(
                    session.snapshot(), lease, clientTick, nowMillis);
            case ACQUIRE_ASSET, REPLACE_ASSET, PLACE_ASSET, VERIFY_ASSET ->
                    tickHomeAsset(decision, lease, clientTick, nowMillis);
            case OPEN_CHEST -> {
                HomeIdleOutcome open = ensureOwnedHomeChestOpen(
                        session.snapshot(), lease, clientTick, nowMillis);
                yield open == null ? homeRunning("verified owned Home chest open") : open;
            }
            case WITHDRAW, DEPOSIT -> beginHomeTransfer(
                    decision.transfer(), protectedCounts, nowMillis);
            case RECONCILE_TRANSFER -> {
                HomeEconomySession.PendingTransfer pending =
                        session.snapshot().pendingTransfer();
                if (homeManualTransferReobserve && transferObservation != null
                        && transferObservation.cursorEmpty()) {
                    HomeEconomyPolicy.TransferResolution resolution =
                            HomeEconomyPolicy.reconcileTransfer(pending, transferObservation);
                    if (resolution.truth() == HomeEconomyPolicy.TransferTruth.PENDING
                            && homeManualTransferAbandonIfUnchanged) {
                        session.abandonTransfer(pending.id(), nowMillis);
                        homeManualTransferReobserve = false;
                        homeManualTransferAbandonIfUnchanged = false;
                        yield homeRunning("reobserved the exact unchanged before-state and "
                                + "abandoned the old Home transfer without claiming success");
                    }
                    // The exact empty-cursor classification has completed.
                    // Any subsequent click is now owned by the durable plan.
                    homeManualTransferReobserve = false;
                    homeManualTransferAbandonIfUnchanged = false;
                }
                yield tickPendingHomeTransfer(pending, protectedCounts, nowMillis);
            }
            case REBASE_TRANSFER -> rebasePendingHomeTransfer(
                    session.snapshot().pendingTransfer(), transferObservation,
                    protectedCounts, nowMillis);
            case COMMIT_TRANSFER -> {
                HomeEconomySession.PendingTransfer pending =
                        session.snapshot().pendingTransfer();
                if (!releaseSettledHomeTransferCustody(pending)) {
                    yield blockHome(
                            "home_transfer_custody_not_settled",
                            "The exact count delta settled with foreign cursor custody",
                            false,
                            nowMillis);
                }
                session.completeTransfer(pending.id(), nowMillis);
                logCommittedHomeTransfer(pending, transferObservation);
                homeManualTransferReobserve = false;
                homeManualTransferAbandonIfUnchanged = false;
                yield homeRunning(decision.detail());
            }
            case ABANDON_TRANSFER -> {
                String transferId = session.snapshot().pendingTransfer().id();
                session.abandonTransfer(transferId, nowMillis);
                homeManualTransferReobserve = false;
                homeManualTransferAbandonIfUnchanged = false;
                yield homeRunning("abandoned unchanged Home transfer " + transferId
                        + " without claiming it completed");
            }
            case START_ACQUISITION -> {
                if (snapshot.storageRegistrations().values().stream().anyMatch(registration ->
                        workstations.observeHomeStorageIdentity(registration.id(), registration.ledgerAssetId(),
                                operationHome, HOME_ASSET_RADIUS).isEmpty())) {
                    yield blockHome("home_storage_unknown", "Some registered storage is inaccessible; "
                            + "its contents are unknown. Restore access or remove that registration before acquiring replacements.",
                            true, nowMillis);
                }
                yield startHomeAcquisition(decision.acquisitionGoals(), nowMillis);
            }
            case WAIT_ACQUISITION -> tickHomeAcquisition(
                    session.snapshot().pendingAcquisition(), lease, clientTick, nowMillis);
            case CANCEL_ACQUISITION_FOR_ASSET_REPAIR ->
                    cancelHomeAcquisitionForAssetRepair(decision, lease, nowMillis);
            case RETIRE_ACQUISITION -> {
                String planId = session.snapshot().pendingAcquisition().planId();
                session.retireAcquisition(planId, nowMillis);
                retirePlanIfTerminal(
                        planId, "home-journal:automatic-retirement-committed", nowMillis);
                reservations.releaseOwner(HOME_RESERVATION_OWNER);
                syncBaritoneThrowawayReservations();
                homeStorageObservations.clear();
                yield homeRunning(decision.detail());
            }
            case FILL_WATER_BUCKET -> tickHomeWaterFill(
                    lease, clientTick, nowMillis);
            case DEGRADED_READY -> finishHomeCycle(
                    HomeEconomySession.Phase.DEGRADED_READY,
                    decision.detail(), nowMillis);
            case READY -> finishHomeCycle(
                    HomeEconomySession.Phase.READY,
                    decision.detail(), nowMillis);
        };
    }

    private HomeIdleOutcome cancelHomeAcquisitionForAssetRepair(
            HomeEconomyPolicy.Decision decision,
            ControlLease lease,
            long nowMillis) throws IOException {
        HomeEconomySession.PendingAcquisition pending =
                homeEconomy.snapshot().pendingAcquisition();
        if (pending == null) {
            return homeRunning("Home acquisition was already fenced; reobserving asset loss");
        }
        Optional<TaskPlan> plan = plans.restore(pending.planId());
        if (plan.isPresent() && plan.orElseThrow().state() == TaskPlanState.OPEN) {
            plans.clear(
                    pending.planId(),
                    "pinned Home asset loss took priority; no acquisition success claimed",
                    nowMillis);
        }
        releaseControls(
                "fencing Home acquisition for pinned-asset repair", lastClientTick);
        baritone.cancel(lease.epoch(), "fencing Home acquisition for pinned-asset repair");
        homeEconomy.retireAcquisition(pending.planId(), nowMillis);
        Optional<TaskPlan> terminal = plans.restore(pending.planId());
        if (terminal.isPresent()
                && terminal.orElseThrow().state() != TaskPlanState.OPEN
                && terminal.orElseThrow().state() != TaskPlanState.RETIRED) {
            plans.retireTerminalAfterOwnerCommit(
                    pending.planId(),
                    "home-journal:asset-repair-acquisition-fenced",
                    nowMillis);
        }
        reservations.releaseOwner(HOME_RESERVATION_OWNER);
        syncBaritoneThrowawayReservations();
        HomeEconomySession.AssetRole role = decision.assetRole();
        return homeRunning("fenced acquisition " + pending.planId()
                + " without claiming success; repairing pinned Home "
                + (role == null ? "asset" : role.item()));
    }

    private HomeIdleOutcome tickHomeWaterFill(
            ControlLease lease,
            long clientTick,
            long nowMillis) throws IOException {
        if (homeWaterFill == null) {
            return finishHomeCycle(
                    HomeEconomySession.Phase.DEGRADED_READY,
                    "water_bucket: 0/1 (" + (homeWaterFillLoadFailure.isBlank()
                            ? "durable fill store unavailable" : homeWaterFillLoadFailure) + ')',
                    nowMillis);
        }
        if (homeWaterFill.snapshot().intent() != null && !homeWaterFill.snapshot().intent().homeCaller()) {
            return finishHomeCycle(HomeEconomySession.Phase.DEGRADED_READY,
                    "water reserve unchanged: an exact mission fill remains unresolved", nowMillis);
        }
        HomeEconomySession.Snapshot home = homeEconomy.snapshot();
        Optional<WorkstationController.RenewableWaterSource> source =
                workstations.findRenewableHomeWaterSource(
                        home.home(), HOME_WATER_SEARCH_RADIUS);
        if (source.isEmpty()) {
            return finishHomeCycle(
                    HomeEconomySession.Phase.DEGRADED_READY,
                    "water_bucket: 0/1 (no verified loaded/reachable renewable source)",
                    nowMillis);
        }
        inventory.reconcilePendingTransaction(nowMillis);
        ClientInventoryController.SafeCloseResult closed =
                inventory.closeHandledScreenIfCursorEmpty(nowMillis);
        if (closed != ClientInventoryController.SafeCloseResult.CLOSED) {
            return homeRunning(closed == ClientInventoryController.SafeCloseResult.CURSOR_OCCUPIED
                    ? "manual/foreign cursor must be cleared before water fill; zero clicks issued"
                    : "waiting for the last chest acknowledgement before water fill");
        }
        int previousSelected = client.player.getInventory().getSelectedSlot();
        int bucketSlot = waterFillHotbarSlot(previousSelected);
        WorkstationController.RenewableWaterSource exact = source.orElseThrow();
        HomeWaterFillSession.Intent intent = new HomeWaterFillSession.Intent(
                "home-water-fill:" + home.generation() + ':' + nowMillis,
                home.generation(), home.home().fingerprint(), home.home().dimension(),
                exact.source().getX(), exact.source().getY(), exact.source().getZ(),
                exact.renewableNeighborMask(),
                inventory.count("minecraft:bucket"),
                inventory.count("minecraft:water_bucket"),
                bucketSlot,
                previousSelected,
                HomeWaterFillSession.Phase.SELECTING_BUCKET,
                0,
                0L,
                "",
                nowMillis);
        // The E2HW intent is the actuation source of truth and is committed
        // before the Home phase cursor or any selection/use packet.
        homeWaterFill.begin(intent, nowMillis);
        if (homeEconomy.snapshot().phase() != HomeEconomySession.Phase.ACQUIRING) {
            homeEconomy.beginWaterFill(nowMillis);
        }
        return tickPendingHomeWaterFill(lease, clientTick, nowMillis);
    }

    private HomeIdleOutcome tickPendingHomeWaterFill(
            ControlLease lease,
            long clientTick,
            long nowMillis) throws IOException {
        return tickPendingWaterFill(lease, null, false, clientTick, nowMillis);
    }

    /** One shared physical fill actuator; caller identity, not a synthetic Home, selects policy. */
    private HomeIdleOutcome tickPendingWaterFill(ControlLease lease, ActionLease missionAction,
            boolean missionBindingMatches, long clientTick, long nowMillis) throws IOException {
        HomeWaterFillSession.Intent intent = homeWaterFill.snapshot().intent();
        if (intent == null) return homeRunning("water-fill intent settled");
        if (!intent.id().equals(homeWaterApproachIntentId)) {
            homeWaterApproachIntentId = intent.id();
            homeWaterApproachStand = null;
            homeWaterRejectedStand = null;
            homeWaterApproachCompletedAt = Long.MIN_VALUE;
        }
        HomeEconomySession.Snapshot home = homeEconomy == null ? null : homeEconomy.snapshot();
        if (intent.homeCaller() && home != null && home.phase() != HomeEconomySession.Phase.ACQUIRING
                && home.phase() != HomeEconomySession.Phase.BLOCKED
                && home.phase() != HomeEconomySession.Phase.PAUSED
                && home.pendingTransfer() == null && home.pendingAcquisition() == null) {
            homeEconomy.beginWaterFill(nowMillis);
            home = homeEconomy.snapshot();
        }
        BlockPos sourcePosition = new BlockPos(
                intent.sourceX(), intent.sourceY(), intent.sourceZ());
        int observedMask = intent.homeCaller() ? workstations.renewableHomeWaterNeighborMask(sourcePosition) : 0;
        Optional<WorkstationController.RenewableWaterSource> exactSource =
                !intent.homeCaller() ? workstations.inspectMissionWaterSource(intent.dimension(),
                        sourcePosition, intent.sourceState(), homeWaterRejectedStand)
                        : home == null || home.home() == null ? Optional.empty()
                        : workstations.inspectRenewableHomeWaterSource(
                        home.home(), sourcePosition, intent.renewableNeighborMask(),
                        HOME_WATER_SEARCH_RADIUS, homeWaterRejectedStand);
        InventoryTransactionEngine.Diagnostics transactions =
                inventory.transactionDiagnostics();
        boolean selectedBucket = client.player != null
                && client.player.getInventory().getSelectedSlot() == intent.hotbarSlot()
                && client.player.getInventory().getStack(intent.hotbarSlot()).isOf(Items.BUCKET);
        boolean bindingMatches = !intent.homeCaller() ? missionBindingMatches : home != null && home.home() != null
                && home.generation() == intent.homeGeneration()
                && home.home().fingerprint().equals(intent.homeFingerprint())
                && home.home().dimension().equals(intent.dimension());
        HomeWaterFillPolicy.Decision decision = HomeWaterFillPolicy.decide(
                intent,
                new HomeWaterFillPolicy.Observation(
                        bindingMatches,
                        inventory.cursorEmpty(),
                        !transactions.pendingClickOwner().isBlank(),
                        inventory.count("minecraft:bucket"),
                        inventory.count("minecraft:water_bucket"),
                        selectedBucket,
                        exactSource.map(
                                WorkstationController.RenewableWaterSource::reachableNow)
                                .orElse(false),
                        intent.homeCaller() ? observedMask == intent.renewableNeighborMask()
                                : workstations.missionWaterSourceValid(sourcePosition)
                                && workstations.waterSourceState(sourcePosition).equals(intent.sourceState()),
                        observedMask,
                        nowMillis));
        lastHomeEconomyDetail = decision.detail();
        String operationId = intent.id() + ":actuator";
        return switch (decision.action()) {
            case SELECT_BUCKET -> {
                ClientInventoryController.SafeCloseResult closed =
                        inventory.closeHandledScreenIfCursorEmpty(nowMillis);
                if (closed != ClientInventoryController.SafeCloseResult.CLOSED) {
                    yield homeRunning("waiting for an empty cursor/player handler before bucket selection");
                }
                Optional<ActionLease> action = waterFillAction(missionAction,
                        lease, ActionOwner.WORKSTATION_INTERACTION,
                        operationId, clientTick, nowMillis);
                if (action.isEmpty()) yield homeRunning("arming persisted bucket selection");
                ClientInventoryController.ClickResult selected = inventory.moveToHotbar(
                        "minecraft:bucket", intent.hotbarSlot(),
                        intent.id() + ":select", nowMillis);
                if (selected == ClientInventoryController.ClickResult.ALREADY_DONE) {
                    homeWaterFill.selected(intent.id(), nowMillis);
                    yield homeRunning("verified exact empty bucket selection");
                }
                if (selected == ClientInventoryController.ClickResult.ITEM_MISSING) {
                    homeWaterFill.block(intent.id(),
                            "empty bucket disappeared before persisted selection", nowMillis);
                    yield blockWaterFill(intent,
                            "water_fill_bucket_missing",
                            "empty bucket disappeared before persisted selection",
                            nowMillis);
                }
                yield homeRunning("persisted water fill is selecting its exact empty bucket");
            }
            case ROUTE_TO_SOURCE -> {
                WorkstationController.RenewableWaterSource target = exactSource.orElse(null);
                if (target == null) {
                    if (homeWaterRejectedStand != null) {
                        String detail = "no different closer water interaction stand remains for the exact source";
                        homeWaterFill.block(intent.id(), detail, nowMillis);
                        yield blockWaterFill(intent, "water_interaction_unreachable", detail, nowMillis);
                    }
                    homeWaterFill.block(intent.id(),
                            "exact renewable water source is no longer loaded/valid", nowMillis);
                    yield blockWaterFill(intent,
                            "water_source_changed",
                            "exact renewable water source is no longer loaded/valid",
                            nowMillis);
                }
                Optional<ActionLease> action = waterFillAction(missionAction,
                        lease, ActionOwner.WORKSTATION_INTERACTION,
                        operationId, clientTick, nowMillis);
                if (action.isEmpty()) yield homeRunning("arming route to renewable water source");
                baritone.bindMovementAction(
                        executionKernel, lease, action.orElseThrow(), clientTick, nowMillis);
                if (homeWaterApproachStand == null) homeWaterApproachStand = target.standAt().toImmutable();
                BlockPos stand = homeWaterApproachStand;
                String goalId = intent.id() + ":route:" + stand.asLong();
                BaritonePort.Goal goal = new BaritonePort.Goal(
                        goalId, "goto",
                        Map.of(
                                "dimension", intent.dimension(),
                                "x", Integer.toString(stand.getX()),
                                "y", Integer.toString(stand.getY()),
                                "z", Integer.toString(stand.getZ()),
                                "range", "0",
                                "allowBreak", "true",
                                "allowPlace", "true",
                                "allowParkourPlace", "true"));
                baritone.start(goal, lease);
                BaritonePort.Status status = baritone.poll(goalId);
                if (status.state() != BaritonePort.State.COMPLETE) homeWaterApproachCompletedAt = Long.MIN_VALUE;
                if (status.state() == BaritonePort.State.BLOCKED
                        || status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
                    homeWaterFill.block(intent.id(),
                            "verified water interaction stand was unreachable: "
                                    + status.detail(), nowMillis);
                    yield blockWaterFill(intent,
                            "water_source_unreachable",
                            "verified water interaction stand was unreachable: "
                                    + status.detail(), nowMillis);
                }
                if (status.state() == BaritonePort.State.COMPLETE) {
                    if (homeWaterApproachCompletedAt == Long.MIN_VALUE) homeWaterApproachCompletedAt = nowMillis;
                    boolean actualRay = waterFillHit(intent, sourcePosition).isPresent();
                    boolean settled = client.player != null && client.player.isOnGround()
                            && client.player.getVelocity().horizontalLengthSquared() < 0.0001;
                    HomeWaterFillPolicy.ApproachAction approach = HomeWaterFillPolicy.completedApproach(
                            actualRay, settled, nowMillis - homeWaterApproachCompletedAt,
                            homeWaterRejectedStand != null);
                    if (approach == HomeWaterFillPolicy.ApproachAction.WAIT_FOR_NATIVE) {
                        yield homeRunning("water goal cell reached; waiting briefly for native movement to settle");
                    }
                    if (approach == HomeWaterFillPolicy.ApproachAction.TRY_ALTERNATE) {
                        homeWaterRejectedStand = stand;
                        homeWaterApproachStand = null;
                        homeWaterApproachCompletedAt = Long.MIN_VALUE;
                        baritone.cancelIfOwned(goalId, lease.epoch(), "water stand lacks an actual source ray after settling");
                        yield homeRunning("trying one closer native stand for the same exact water source");
                    }
                    HomeWaterFillPolicy.Decision arrival = HomeWaterFillPolicy.afterCompletedApproach(actualRay);
                    if (approach == HomeWaterFillPolicy.ApproachAction.BLOCK) {
                        homeWaterFill.block(intent.id(), arrival.detail(), nowMillis);
                        yield blockWaterFill(intent, arrival.code(), arrival.detail(), nowMillis);
                    }
                    yield homeRunning(arrival.detail());
                }
                yield homeRunning("routing to exact renewable water source; " + status.detail());
            }
            case ISSUE_USE -> {
                if (intent.phase() == HomeWaterFillSession.Phase.SELECTING_BUCKET) {
                    homeWaterFill.selected(intent.id(), nowMillis);
                    yield homeRunning("durably committed exact bucket selection before use");
                }
                Optional<ActionLease> action = waterFillAction(missionAction,
                        lease, ActionOwner.WORKSTATION_INTERACTION,
                        operationId, clientTick, nowMillis);
                if (action.isEmpty()) yield homeRunning("arming persisted renewable-source use");
                baritone.bindMovementAction(
                        executionKernel, lease, action.orElseThrow(), clientTick, nowMillis);
                if (waterFillHit(intent, sourcePosition).isEmpty()) yield homeRunning(
                        "revalidating exact renewable water sightline before use");
                if (!homeWaterAimReady(intent.id(), sourcePosition)) {
                    yield homeRunning("aiming at the exact verified source hit before use");
                }
                String authorityOperation = intent.id() + ":fluid-authority";
                if (intent.homeCaller() && protectedAreas != null) {
                    ProtectedAreaClientState.HomeAuthorization authority =
                            protectedAreas.requestExactHomeAuthorization(
                                    authorityOperation,
                                    "home-water:" + intent.homeFingerprint(),
                                    ProtectedAreaPolicy.Action.FLUID,
                                    List.of(new ProtectedAreaHomePermit.Target(
                                            intent.dimension(),
                                            sourcePosition.getX(),
                                            sourcePosition.getY(),
                                            sourcePosition.getZ())),
                                    nowMillis);
                    if (authority.state()
                            == ProtectedAreaClientState.HomeAuthorizationState.WAITING) {
                        yield homeRunning(authority.detail());
                    }
                    if (authority.state()
                            == ProtectedAreaClientState.HomeAuthorizationState.DENIED) {
                        homeWaterFill.block(intent.id(), authority.detail(), nowMillis);
                        yield blockWaterFill(intent,
                                "protected_home_water_denied",
                                authority.detail(), nowMillis);
                    }
                }
                // Save-before-send closes the restart duplicate-use window.
                homeWaterFill.markUseIssued(intent.id(), nowMillis);
                logger.info("Water fill use persisted caller={} mission={} action={} source={},{},{} intent={}",
                        intent.callerKind(), intent.missionId(), intent.actionId(),
                        intent.sourceX(), intent.sourceY(), intent.sourceZ(), intent.id());
                // Buckets are BucketItem#use actions.  A block-interaction packet only probes the
                // water block and returns PASS; vanilla's client then performs interactItem.
                // We already proved the exact persisted source hit above and aimed for a full
                // tick, so issue exactly that one bounded item-use packet here.
                List<ProtectedAreaHomePermit.Target> fluidTargets = List.of(
                        new ProtectedAreaHomePermit.Target(
                                intent.dimension(),
                                sourcePosition.getX(),
                                sourcePosition.getY(),
                                sourcePosition.getZ()));
                ActionResult result = intent.homeCaller() ? MinecraftActuatorGateway.shared(client)
                        .interactItemWithExactHomeAuthorization(
                                ProtectedAreaPolicy.Action.FLUID,
                                fluidTargets,
                                Hand.MAIN_HAND)
                        : client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                if (intent.homeCaller() && protectedAreas != null) {
                    protectedAreas.completeExactHomeAuthorization(authorityOperation);
                }
                if (!result.isAccepted()) {
                    Optional<MinecraftActuatorGateway.BlockedInteractionAttempt> blocked =
                            MinecraftActuatorGateway.shared(client)
                                    .takeBlockedInteractionAttempt();
                    if (blocked.isPresent()) {
                        String detail = blocked.orElseThrow().detail();
                        homeWaterFill.block(intent.id(), detail, nowMillis);
                        yield blockWaterFill(intent,
                                "protected_water_vetoed", detail, nowMillis);
                    }
                }
                if (result.isAccepted()) client.player.swingHand(Hand.MAIN_HAND);
                yield homeRunning(result.isAccepted()
                        ? "sent one persisted renewable-source use; awaiting exact -1/+1 counts"
                        : "local interaction rejected persisted use; awaiting unchanged timeout");
            }
            case WAIT_ACKNOWLEDGEMENT -> homeRunning(decision.detail());
            case WAIT_RESULT -> homeRunning(decision.detail());
            case RETRY_UNCHANGED -> {
                homeWaterFill.retryUnchanged(intent.id(), nowMillis);
                yield homeRunning(decision.detail());
            }
            case COMMIT_FILLED -> {
                restoreWaterFillSelection(intent);
                homeWaterFill.complete(intent.id(), nowMillis);
                if (intent.homeCaller() && homeEconomy.snapshot().phase() == HomeEconomySession.Phase.PAUSED) {
                    homeEconomy.completeWaterFillWhilePaused(nowMillis);
                } else if (intent.homeCaller()) {
                    homeEconomy.completeWaterFill(nowMillis);
                }
                logger.info("Water fill verified caller={} mission={} action={} source={},{},{} empty={}->{} filled={}->{} intent={}",
                        intent.callerKind(), intent.missionId(), intent.actionId(), intent.sourceX(), intent.sourceY(), intent.sourceZ(),
                        intent.emptyBucketBefore(), inventory.count("minecraft:bucket"), intent.waterBucketBefore(),
                        inventory.count("minecraft:water_bucket"), intent.id());
                baritone.cancel(lease.epoch(), "verified exact water bucket fill");
                yield homeRunning(decision.detail());
            }
            case BLOCK -> {
                if (intent.phase() != HomeWaterFillSession.Phase.BLOCKED) {
                    homeWaterFill.block(intent.id(), decision.detail(), nowMillis);
                }
                restoreWaterFillSelection(intent);
                yield blockWaterFill(intent, decision.code(), decision.detail(), nowMillis);
            }
        };
    }

    private Optional<net.minecraft.util.hit.BlockHitResult> waterFillHit(HomeWaterFillSession.Intent intent, BlockPos source) {
        return intent.homeCaller() ? workstations.renewableWaterHit(source, intent.renewableNeighborMask())
                : workstations.missionWaterHit(source, intent.sourceState());
    }

    private Optional<ActionLease> waterFillAction(ActionLease missionAction, ControlLease lease,
            ActionOwner owner, String operationId, long clientTick, long nowMillis) {
        if (missionAction == null) return acquireHomeAction(lease, owner, operationId, clientTick, nowMillis);
        executionKernel.requireValid(missionAction, lease, clientTick, nowMillis);
        return Optional.of(missionAction);
    }

    private HomeIdleOutcome blockWaterFill(HomeWaterFillSession.Intent intent, String code,
            String detail, long nowMillis) throws IOException {
        return intent.homeCaller() ? blockHome(code, detail, true, nowMillis) : HomeIdleOutcome.release(detail);
    }

    private int waterFillHotbarSlot(int previousSelectedSlot) {
        if (client.player != null) {
            for (int slot = 0; slot < 9; slot++) {
                if (client.player.getInventory().getStack(slot).isOf(Items.BUCKET)) return slot;
            }
        }
        return previousSelectedSlot == 8 ? 7 : 8;
    }

    private void restoreWaterFillSelection(HomeWaterFillSession.Intent intent) {
        homeWaterAimIntentId = "";
        homeWaterAimPlayerAge = -1;
        if (intent != null) inventory.selectHotbarSlotNow(intent.previousSelectedSlot());
    }

    /** Requires one complete client tick after rotation before the persisted use packet. */
    private boolean homeWaterAimReady(String intentId, BlockPos source) {
        if (client.player == null) return false;
        if (!workstations.aimAtRenewableWater(source)) {
            homeWaterAimIntentId = "";
            homeWaterAimPlayerAge = -1;
            return false;
        }
        if (!intentId.equals(homeWaterAimIntentId)) {
            homeWaterAimIntentId = intentId;
            homeWaterAimPlayerAge = client.player.age;
            return false;
        }
        return client.player.age > homeWaterAimPlayerAge;
    }

    private HomeIdleOutcome ensureOwnedHomeChestOpen(
            HomeEconomySession.Snapshot snapshot,
            ControlLease lease,
            long clientTick,
            long nowMillis) throws IOException {
        HomeEconomySession.StorageRegistration pinned = selectedHomeStorage(snapshot);
        if (pinned == null) return homeRunning("waiting to own and pin the Home chest");
        String operationId = homeWorkstationOperation(snapshot, HomeEconomySession.AssetRole.CHEST);
        HomeEconomySession.PendingTransfer pending = snapshot.pendingTransfer();
        inventory.reconcilePendingTransaction(nowMillis);
        if (pending == null) {
            var diagnostics = inventory.transactionDiagnostics();
            if (!inventory.cursorEmpty() || !diagnostics.pendingClickOwner().isBlank()
                    || !diagnostics.cursorOwner().isBlank() || diagnostics.pendingRemovals() > 0) {
                return homeRunning("settling the last inventory acknowledgement before releasing its container identity");
            }
        }
        if (pending != null && pending.containerIdentity() == null) {
            var identity = workstations.observeHomeStorageIdentity(pinned.id(), pinned.ledgerAssetId(),
                    snapshot.home(), HOME_ASSET_RADIUS);
            if (identity.isEmpty() || identity.orElseThrow().halves().size() != 1) {
                return blockHome("legacy_transfer_layout_changed", "Restore the original single chest to reconcile "
                        + "its pre-update transfer; no changed layout will be used.", false, nowMillis);
            }
        }
        workstations.requirePinnedStorageIdentity(operationId, pending == null ? null : pending.containerIdentity());
        Optional<ActionLease> action = acquireHomeAction(
                lease, ActionOwner.WORKSTATION_INTERACTION,
                operationId, clientTick, nowMillis);
        if (action.isEmpty()) return homeRunning("arming exact owned-chest interaction");
        baritone.bindMovementAction(
                executionKernel, lease, action.orElseThrow(), clientTick, nowMillis);
        WorkstationController.Result opened = workstations.tickOpenPinned(
                WorkstationController.Kind.CHEST,
                operationId,
                pinned.ledgerAssetId(),
                snapshot.home(),
                HOME_ASSET_RADIUS,
                lease,
                baritone,
                nowMillis);
        if (opened.state() == WorkstationController.State.OPEN) return null;
        if (opened.state() == WorkstationController.State.BLOCKED) {
            return blockHome("owned_chest_unavailable", opened.detail(), true, nowMillis);
        }
        if (opened.state() == WorkstationController.State.ITEM_MISSING) {
            return blockHome("owned_chest_item_missing", opened.detail(), true, nowMillis);
        }
        return homeRunning(opened.detail());
    }

    private HomeIdleOutcome tickReturnHome(
            HomeEconomySession.Snapshot snapshot,
            ControlLease lease,
            long clientTick,
            long nowMillis) throws IOException {
        if (snapshot.phase() != HomeEconomySession.Phase.RETURNING_HOME) {
            homeEconomy.advance(HomeEconomySession.Phase.RETURNING_HOME, nowMillis);
            snapshot = homeEconomy.snapshot();
        }
        String operationId = "home:economy:" + snapshot.generation() + ":return";
        Optional<ActionLease> action = acquireHomeAction(
                lease, ActionOwner.BARITONE_ROUTE, operationId, clientTick, nowMillis);
        if (action.isEmpty()) return homeRunning("arming Home return route");
        baritone.bindMovementAction(
                executionKernel, lease, action.orElseThrow(), clientTick, nowMillis);
        HomeEconomySession.HomeAnchor home = snapshot.home();
        BaritonePort.Goal goal = new BaritonePort.Goal(
                operationId,
                "goto",
                Map.of(
                        "x", Integer.toString(home.x()),
                        "y", Integer.toString(home.y()),
                        "z", Integer.toString(home.z()),
                        "dimension", home.dimension(),
                        "range", "3"));
        baritone.start(goal, lease);
        BaritonePort.Status status = baritone.poll(operationId);
        if (status.state() == BaritonePort.State.BLOCKED) {
            return blockHome("home_route_blocked", status.detail(), true, nowMillis);
        }
        if (status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
            return homeRunning("retrying Home route: " + status.detail());
        }
        return homeRunning(status.state() == BaritonePort.State.COMPLETE
                ? "reached Home; reobserving exact assets"
                : "returning Home: " + status.detail());
    }

    private HomeIdleOutcome tickHomeAsset(
            HomeEconomyPolicy.Decision decision,
            ControlLease lease,
            long clientTick,
            long nowMillis) throws IOException {
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        HomeEconomySession.AssetRole role = Objects.requireNonNull(
                decision.assetRole(), "asset decision role");
        HomeEconomySession.PinnedAsset pinned = snapshot.pinnedAssets().get(role);
        String assetId = homeAssetId(snapshot, role);
        Optional<FieldKitLedger.Asset> owned = workstations.ownedAsset(assetId);

        if (decision.action() == HomeEconomyPolicy.Action.REPLACE_ASSET && pinned != null) {
            if (snapshot.pendingTransfer() != null || snapshot.pendingAcquisition() != null) {
                return blockHome(
                        "owned_asset_changed_during_pending_operation",
                        "The pinned " + role.item()
                                + " changed while durable Home cargo is unresolved; restore the exact asset "
                                + "and use /e stock run",
                        true,
                        nowMillis);
            }
            if (owned.isEmpty()) {
                return blockHome(
                        "owned_asset_ledger_missing",
                        "The Home cursor references a missing FieldKit ledger asset " + assetId,
                        false,
                        nowMillis);
            }
            FieldKitLedger.Asset asset = owned.orElseThrow();
            if (asset.state() != FieldKitLedger.AssetState.LOST) {
                String operationId = homeWorkstationOperation(snapshot, role);
                workstations.forgetLostOwnedPlacement(
                        operationId,
                        "loaded exact Home placement no longer contains its owned " + role.item(),
                        nowMillis);
                asset = workstations.ownedAsset(assetId).orElse(asset);
            }
            if (asset.state() != FieldKitLedger.AssetState.LOST) {
                return blockHome(
                        "owned_asset_loss_unproved",
                        "Could not durably prove the missing Home " + role.item() + " lost",
                        true,
                        nowMillis);
            }
            homeEconomy.unpinLostAsset(role, asset, nowMillis);
            return homeRunning("committed loss of Home " + role.item()
                    + "; a generation-scoped replacement will be acquired");
        }

        if (pinned == null && owned.isPresent()) {
            homeEconomy.pinOwnedAsset(role, owned.orElseThrow(), nowMillis);
            snapshot = homeEconomy.snapshot();
            pinned = snapshot.pinnedAssets().get(role);
        }
        if (pinned == null && !inventory.has("minecraft:" + role.item(), 1)) {
            return startHomeAcquisition(Map.of(role.item(), 1), nowMillis);
        }

        String operationId = homeWorkstationOperation(snapshot, role);
        Optional<ActionLease> action = acquireHomeAction(
                lease, ActionOwner.WORKSTATION_INTERACTION,
                operationId, clientTick, nowMillis);
        if (action.isEmpty()) return homeRunning("arming Home " + role.item() + " placement");
        baritone.bindMovementAction(
                executionKernel, lease, action.orElseThrow(), clientTick, nowMillis);
        WorkstationController.Result result = role == HomeEconomySession.AssetRole.BED
                ? workstations.tickPlacePinned(
                        workstationKind(role), operationId, assetId, snapshot.home(),
                        HOME_ASSET_RADIUS, lease, baritone, nowMillis)
                : workstations.tickOpenPinned(
                        workstationKind(role), operationId, assetId, snapshot.home(),
                        HOME_ASSET_RADIUS, lease, baritone, nowMillis);
        owned = workstations.ownedAsset(assetId);
        if (snapshot.pinnedAssets().get(role) == null && owned.isPresent()) {
            homeEconomy.pinOwnedAsset(role, owned.orElseThrow(), nowMillis);
        }
        if (result.state() == WorkstationController.State.BLOCKED) {
            boolean degradedRepair = result.detail().startsWith("Home ")
                    && result.detail().contains("repair degraded after");
            return blockHome(
                    degradedRepair
                            ? "home_asset_repair_degraded" : "home_asset_blocked",
                    result.detail(), !degradedRepair, nowMillis);
        }
        if (result.state() == WorkstationController.State.ITEM_MISSING) {
            return startHomeAcquisition(Map.of(role.item(), 1), nowMillis);
        }
        return homeRunning(result.detail());
    }

    private boolean homeSleepDue(HomeEconomySession.Snapshot snapshot) {
        if (snapshot == null || snapshot.home() == null
                || client.player == null || client.world == null) return false;
        HomeEconomySession.PinnedAsset bed = snapshot.pinnedAssets().get(
                HomeEconomySession.AssetRole.BED);
        if (bed == null) return false;
        String operationId = homeWorkstationOperation(
                snapshot, HomeEconomySession.AssetRole.BED);
        return workstations.homeSleepCycleActive(operationId)
                || "minecraft:overworld".equals(currentDimension())
                && client.world.isNight();
    }

    private HomeIdleOutcome tickHomeSleep(
            HomeEconomySession.Snapshot snapshot,
            ControlLease lease,
            long clientTick,
            long nowMillis) throws IOException {
        HomeEconomySession.PinnedAsset bed = snapshot.pinnedAssets().get(
                HomeEconomySession.AssetRole.BED);
        if (bed == null) {
            return homeRunning("owned Home bed is not yet pinned");
        }
        String operationId = homeWorkstationOperation(
                snapshot, HomeEconomySession.AssetRole.BED);
        Optional<ActionLease> action = acquireHomeAction(
                lease, ActionOwner.WORKSTATION_INTERACTION,
                operationId + ":sleep", clientTick, nowMillis);
        if (action.isEmpty()) {
            return homeRunning("arming exclusive owned-Home sleep interaction");
        }
        baritone.bindMovementAction(
                executionKernel, lease, action.orElseThrow(), clientTick, nowMillis);
        WorkstationController.Result result = workstations.tickSleepPinned(
                operationId,
                bed.ledgerAssetId(),
                snapshot.home(),
                HOME_ASSET_RADIUS,
                immediateHomeSleepHostilePressure(),
                lease,
                baritone,
                nowMillis);
        if (result.state() == WorkstationController.State.BLOCKED) {
            homeSleepRequested = false;
            HomeIdleOutcome outcome = blockHome("home_sleep_blocked", result.detail(), true, nowMillis);
            idleSleepFallback.failed(homeEconomy.snapshot());
            return outcome;
        }
        if (result.state() == WorkstationController.State.SLEPT) {
            homeSleepRequested = false;
            verifiedHomeSleepCycles++;
            idleSleepFallback.clear();
            String detail = "verified natural-night sleep in the exact owned Home bed; morning observed";
            logger.info(detail);
            return homeRunning(detail);
        }
        return homeRunning(result.detail());
    }

    private boolean immediateHomeSleepHostilePressure() {
        if (client.player == null || client.world == null) return true;
        return !client.world.getOtherEntities(
                client.player,
                client.player.getBoundingBox().expand(12.0D, 6.0D, 12.0D),
                entity -> entity instanceof net.minecraft.entity.mob.HostileEntity
                        && entity.isAlive()).isEmpty();
    }

    private HomeIdleOutcome beginHomeTransfer(
            HomeStockPolicy.Transfer transfer,
            Map<String, Integer> protectedCounts,
            long nowMillis) throws IOException {
        Objects.requireNonNull(transfer, "transfer");
        InventoryTransactionEngine.Diagnostics diagnostics = inventory.transactionDiagnostics();
        if (!diagnostics.pendingClickOwner().isBlank() || !diagnostics.cursorOwner().isBlank()) {
            return homeRunning("waiting for the previous inventory transaction owner to reconcile");
        }
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        String destinationStorage = storageForTransfer(snapshot, transfer);
        if (destinationStorage != null && !destinationStorage.equals(selectedHomeStorage(snapshot).id())) {
            return switchHomeStorage(destinationStorage, nowMillis);
        }
        Optional<ClientInventoryController.HomeTransferPlan> planned =
                inventory.planHomeChestTransfer(
                        transfer.direction(), transfer.item(), transfer.count(), protectedCounts);
        if (planned.isEmpty()) {
            return blockHome(
                    transfer.direction() == HomeStockPolicy.Direction.DEPOSIT
                            ? "home_chest_full" : "home_chest_source_changed",
                    "Could not prove exact slots for " + transfer.direction().name().toLowerCase(Locale.ROOT)
                            + " of " + transfer.count() + ' ' + transfer.item(),
                    true,
                    nowMillis);
        }
        ClientInventoryController.HomeTransferPlan plan = planned.orElseThrow();
        String id = "home-transfer:" + homeEconomy.snapshot().generation()
                + ':' + homeEconomy.snapshot().revision();
        HomeEconomySession.PendingTransfer pending = pendingTransfer(id, transfer, plan, nowMillis);
        homeEconomy.beginTransfer(pending, nowMillis);
        return homeRunning("durably prepared " + transfer.direction().name().toLowerCase(Locale.ROOT)
                + " of " + plan.count() + ' ' + transfer.item() + "; click begins next tick");
    }

    private HomeIdleOutcome tickPendingHomeTransfer(
            HomeEconomySession.PendingTransfer pending,
            Map<String, Integer> protectedCounts,
            long nowMillis) throws IOException {
        if (pending == null) return blockHome(
                "transfer_checkpoint_missing", "Home transfer checkpoint is missing", false, nowMillis);
        ClientInventoryController.HomeTransferPlan plan = homeTransferPlan(pending);
        ClientInventoryController.HomeTransferResult result = inventory.homeChestTransferTick(
                plan, homeTransferOwner(pending), nowMillis);
        return switch (result) {
            case CLICKED -> homeRunning("issued one acknowledgement-fenced Home transfer click");
            case WAITING -> homeRunning("waiting for Home transfer acknowledgement");
            case COMPLETE -> homeRunning("transfer postcondition visible; committing next tick");
            case STALE_HANDLER, SLOT_CHANGED, SOURCE_CHANGED, DESTINATION_CHANGED ->
                    replanUnchangedHomeTransfer(pending, protectedCounts, result, nowMillis);
            case NEEDS_OWNED_CHEST -> homeRunning("reopening the exact owned Home chest");
            case CURSOR_NOT_OWNED -> blockHome(
                    "home_cursor_not_owned",
                    "The open cursor is not owned by the durable Home transfer",
                    false,
                    nowMillis);
            case COUNTS_DIVERGED -> blockHome(
                    "home_transfer_counts_diverged",
                    "Home transfer counts changed without a conserved acknowledgement",
                    false,
                    nowMillis);
        };
    }

    private HomeIdleOutcome rebasePendingHomeTransfer(
            HomeEconomySession.PendingTransfer pending,
            HomeEconomyPolicy.TransferObservation observed,
            Map<String, Integer> protectedCounts,
            long nowMillis) throws IOException {
        if (pending == null || observed == null) {
            return homeRunning("waiting to reobserve the durable Home transfer");
        }
        HomeEconomyPolicy.TransferResolution resolution =
                HomeEconomyPolicy.reconcileTransfer(pending, observed);
        if (!resolution.needsRebase()) {
            return blockHome(
                    "transfer_rebase_invalid",
                    "Home transfer no longer has a safe rebase postcondition: " + resolution.detail(),
                    false,
                    nowMillis);
        }
        int remaining = pending.count() - resolution.movedCount();
        Optional<ClientInventoryController.HomeTransferPlan> planned =
                inventory.planHomeChestTransfer(
                        pending.direction(), pending.item(), remaining, protectedCounts);
        if (planned.isEmpty()) {
            return blockHome(
                    "transfer_rebase_slots_unavailable",
                    "Observed " + resolution.movedCount()
                            + " committed item(s), but no safe slot exists for the remaining " + remaining,
                    true,
                    nowMillis);
        }
        HomeStockPolicy.Transfer remainingTransfer = new HomeStockPolicy.Transfer(
                pending.direction(), pending.categoryId(), pending.item(),
                planned.orElseThrow().count());
        HomeEconomySession.PendingTransfer replacement = pendingTransfer(
                pending.id(), remainingTransfer, planned.orElseThrow(), nowMillis);
        homeEconomy.rebaseTransfer(pending.id(), replacement, nowMillis);
        return homeRunning("durably rebased Home transfer after "
                + resolution.movedCount() + " acknowledged item(s)");
    }

    private HomeIdleOutcome replanUnchangedHomeTransfer(
            HomeEconomySession.PendingTransfer pending,
            Map<String, Integer> protectedCounts,
            ClientInventoryController.HomeTransferResult reason,
            long nowMillis) throws IOException {
        Optional<ClientInventoryController.HomeTransferPlan> replacement =
                inventory.planHomeChestTransfer(
                        pending.direction(), pending.item(), pending.count(), protectedCounts);
        if (replacement.isEmpty()) {
            return blockHome(
                    "transfer_slots_changed",
                    "Home transfer slots changed and no safe replacement is currently available ("
                            + reason.name().toLowerCase(Locale.ROOT) + ')',
                    true,
                    nowMillis);
        }
        HomeStockPolicy.Transfer transfer = new HomeStockPolicy.Transfer(
                pending.direction(), pending.categoryId(), pending.item(),
                replacement.orElseThrow().count());
        homeEconomy.rebaseTransfer(
                pending.id(), pendingTransfer(
                        pending.id(), transfer, replacement.orElseThrow(), nowMillis), nowMillis);
        return homeRunning("durably rebound changed Home transfer slots");
    }

    private HomeIdleOutcome startHomeAcquisition(
            Map<String, Integer> additionalGoals,
            long nowMillis) throws IOException {
        if (!resourceCatalogReady) {
            return blockHome(
                    "acquisition_planner_unavailable",
                    "The authoritative resource catalog is not ready",
                    true,
                    nowMillis);
        }
        // Home Stock's serviceability boundary is the authority for maintenance
        // goals. Counting a one-use-left tool here turned a deficit of one into
        // an absolute goal of two and produced duplicate replacements.
        Map<String, Integer> current = inventory.homePlayerCounts();
        Map<String, Integer> absolute = HomeStockPolicy.absoluteAcquisitionGoals(
                additionalGoals, current);
        String planId = "home-economy:" + homeEconomy.snapshot().generation()
                + ":acquire:" + homeEconomy.snapshot().revision();
        HomeEconomySession.PendingAcquisition pending =
                new HomeEconomySession.PendingAcquisition(planId, absolute, nowMillis);
        Mission synthetic = homeAcquisitionMission(pending);
        // PlanStore first: a crash may leave a harmless orphan, never a Home
        // cursor that names a nonexistent planner checkpoint.
        ensureMissionPlan(synthetic, nowMillis);
        homeEconomy.beginAcquisition(pending, nowMillis);
        return homeRunning("started isolated universal Home acquisition " + planId);
    }

    private HomeIdleOutcome tickHomeAcquisition(
            HomeEconomySession.PendingAcquisition pending,
            ControlLease lease,
            long clientTick,
            long nowMillis) throws IOException {
        if (pending == null) {
            return blockHome(
                    "acquisition_checkpoint_missing",
                    "Home acquisition checkpoint is missing",
                    false,
                    nowMillis);
        }
        if (homeAcquisitionTruth(homeEconomy.snapshot())
                == HomeEconomyPolicy.AcquisitionTruth.COMPLETED) {
            return tickHomeAcquisitionReturn(pending, lease, clientTick, nowMillis);
        }
        HomeIdleOutcome departure = tickHomeAcquisitionDeparture(
                pending, lease, nowMillis);
        if (departure != null) {
            return departure.retainLease()
                    ? homeAcquisitionRunning(pending, departure.detail(), 0L)
                    : departure;
        }
        Mission synthetic = homeAcquisitionMission(pending);
        refreshMissionReservations(synthetic, HOME_RESERVATION_OWNER);
        if (idleStockCycle != null && idleStockCycle.undergroundSite != null && !idleStockCycle.mineEntranceReached) {
            var site = idleStockCycle.undergroundSite;
            if (client.player != null && client.player.isOnGround() && client.player.getBlockPos().equals(site.entrance())) {
                idleStockCycle.mineEntranceReached = true;
                baritone.cancel(lease.epoch(), "known idle mine entrance reached");
                return homeRunning("Known mine entrance reached; existing mining plan will replay its access");
            }
            String operation = pending.planId() + ":idle-mine-entrance";
            var action = acquireHomeAction(lease, ActionOwner.BARITONE_ROUTE, operation, clientTick, nowMillis);
            if (action.isEmpty()) return homeRunning("Arming travel to known mine entrance");
            baritone.bindMovementAction(executionKernel, lease, action.orElseThrow(), clientTick, nowMillis);
            baritone.start(new BaritonePort.Goal(operation, "goto", Map.of("dimension", site.dimension(), "range", "0",
                    "x", Integer.toString(site.entrance().getX()), "y", Integer.toString(site.entrance().getY()),
                    "z", Integer.toString(site.entrance().getZ()))), lease);
            var route = baritone.poll(operation);
            if (route.state() == BaritonePort.State.BLOCKED) return blockHome("idle_mine_access_blocked", route.detail(), true, nowMillis);
            return homeRunning("Travel to existing mine entrance: " + route.detail());
        }
        Outcome outcome = tickPlan(
                pending.planId(), synthetic, lease, clientTick, nowMillis, false);
        if (outcome.state() == State.PLAN_COMPLETE || outcome.state() == State.COMPLETE) {
            if (!homeGoalsSatisfied(pending.goals())) {
                return blockHome(
                        "acquisition_postcondition_missing",
                        "Universal Home plan completed without its exact inventory goals",
                        false,
                        nowMillis);
            }
            return tickHomeAcquisitionReturn(pending, lease, clientTick, nowMillis);
        }
        if (outcome.state() == State.BLOCKED) {
            return blockHome(
                    "acquisition_failed",
                    outcome.detail(),
                    true,
                    nowMillis);
        }
        if (outcome.state() == State.RETRY) {
            return homeAcquisitionRunning(
                    pending,
                    "Home acquisition bounded retry: " + outcome.detail(),
                    outcome.completedWorkUnits());
        }
        return homeAcquisitionRunning(
                pending,
                "Home acquisition: " + outcome.detail(),
                outcome.completedWorkUnits());
    }

    /**
     * Keeps the durable acquisition cursor until Entity has physically
     * restored the exact reserved Home anchor. Natural cave departures may
     * legitimately pillar upward; an interaction-range stop above the table
     * must not turn that temporary pillar into a permanently blocked Home.
     */
    private HomeIdleOutcome tickHomeAcquisitionReturn(
            HomeEconomySession.PendingAcquisition pending,
            ControlLease lease,
            long clientTick,
            long nowMillis) throws IOException {
        HomeEconomySession.HomeAnchor home = homeEconomy.snapshot().home();
        if (home == null || client.player == null || client.world == null) {
            return blockHome(
                    "acquisition_return_home_missing",
                    "The exact Home/player world truth is unavailable after acquisition",
                    true,
                    nowMillis);
        }
        if (homeEconomy.snapshot().phase() != HomeEconomySession.Phase.RETURNING_HOME) {
            homeEconomy.beginAcquisitionReturn(pending.planId(), nowMillis);
        }

        ClientInventoryController.SafeCloseResult closed =
                inventory.closeHandledScreenIfCursorEmpty(nowMillis);
        if (closed != ClientInventoryController.SafeCloseResult.CLOSED) {
            homeScreenClosePending = true;
            return homeRunning(closed
                    == ClientInventoryController.SafeCloseResult.CURSOR_OCCUPIED
                    ? "waiting for the owned acquisition cursor to clear before exact Home return"
                    : "reconciling the final acquisition click before exact Home return");
        }
        homeScreenClosePending = false;
        workstations.suspendAll();

        BlockPos current = client.player.getBlockPos();
        boolean exactAnchor = current.getX() == home.x()
                && current.getY() == home.y()
                && current.getZ() == home.z();
        HomeEconomyPolicy.AcquisitionReturnDecision decision =
                HomeEconomyPolicy.decideAcquisitionReturn(
                        home,
                        currentDimension(),
                        current.getX(), current.getY(), current.getZ(),
                        homeLoaded(home),
                        exactAnchor && workstations.homeWorkspaceSafe(
                                home, HOME_ASSET_RADIUS));
        if (decision.action() == HomeEconomyPolicy.AcquisitionReturnAction.BLOCKED) {
            return blockHome(
                    "acquisition_return_anchor_unsafe",
                    decision.detail(),
                    true,
                    nowMillis);
        }
        if (decision.action() == HomeEconomyPolicy.AcquisitionReturnAction.COMMIT) {
            baritone.cancel(lease.epoch(), "exact Home acquisition return verified");
            homeEconomy.completeAcquisition(pending.planId(), nowMillis);
            retirePlanIfTerminal(
                    pending.planId(), "home-journal:return-complete", nowMillis);
            reservations.releaseOwner(HOME_RESERVATION_OWNER);
            syncBaritoneThrowawayReservations();
            return homeRunning(
                    "completed isolated Home acquisition at the restored exact anchor");
        }

        String operationId = pending.planId() + ":home-return";
        Optional<ActionLease> action = acquireHomeAction(
                lease, ActionOwner.BARITONE_ROUTE,
                operationId, clientTick, nowMillis);
        if (action.isEmpty()) return homeRunning("arming exact Home acquisition return");
        baritone.bindMovementAction(
                executionKernel, lease, action.orElseThrow(), clientTick, nowMillis);
        BaritonePort.Goal goal = new BaritonePort.Goal(
                operationId,
                "goto",
                Map.of(
                        "x", Integer.toString(home.x()),
                        "y", Integer.toString(home.y()),
                        "z", Integer.toString(home.z()),
                        "dimension", home.dimension(),
                        "range", "0"));
        baritone.start(goal, lease);
        BaritonePort.Status status = baritone.poll(operationId);
        if (status.state() == BaritonePort.State.BLOCKED) {
            return blockHome(
                    "acquisition_return_route_blocked",
                    "Could not restore the exact reserved Home anchor: " + status.detail(),
                    true,
                    nowMillis);
        }
        if (status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
            return homeRunning("retrying exact Home acquisition return: " + status.detail());
        }
        return homeRunning(status.state() == BaritonePort.State.COMPLETE
                ? "reached the exact Home anchor; verifying restored workspace next tick"
                : "restoring the exact Home anchor after acquisition: " + status.detail());
    }

    /**
     * Physically returns to and checkpoints the exact reserved Home anchor
     * before a new acquisition may begin. The durable fingerprint makes this a
     * one-time departure gate: once the plan leaves Home it is not recalled on
     * every subsequent mining/crafting tick.
     */
    private HomeIdleOutcome tickHomeAcquisitionDeparture(
            HomeEconomySession.PendingAcquisition pending,
            ControlLease lease,
            long nowMillis) throws IOException {
        TaskPlan plan = plans.restore(pending.planId()).orElse(null);
        if (plan == null || plan.state() != TaskPlanState.OPEN || plan.frames().isEmpty()) {
            return blockHome(
                    "acquisition_departure_checkpoint_missing",
                    "The Home acquisition has no open planner root for departure verification",
                    false,
                    nowMillis);
        }
        HomeEconomySession.HomeAnchor home = homeEconomy.snapshot().home();
        if (home == null || client.player == null || client.world == null) {
            return blockHome(
                    "acquisition_departure_home_missing",
                    "The exact Home/player world truth is unavailable before acquisition departure",
                    true,
                    nowMillis);
        }
        PlanFrame root = plan.frames().getFirst();
        boolean checkpointMatchesHome = home.fingerprint().equals(
                root.parameters().getOrDefault(HOME_ACQUISITION_DEPARTURE_KEY, ""));
        BlockPos current = client.player.getBlockPos();
        boolean exactAnchor = current.getX() == home.x()
                && current.getY() == home.y()
                && current.getZ() == home.z();
        HomeEconomyPolicy.AcquisitionDepartureDecision decision =
                HomeEconomyPolicy.decideAcquisitionDeparture(
                        checkpointMatchesHome,
                        home,
                        currentDimension(),
                        current.getX(), current.getY(), current.getZ(),
                        homeLoaded(home),
                        exactAnchor && workstations.homeWorkspaceSafe(
                                home, HOME_ASSET_RADIUS));
        return switch (decision.action()) {
            case PROCEED -> null;
            case BLOCKED -> blockHome(
                    "acquisition_departure_blocked",
                    decision.detail(),
                    true,
                    nowMillis);
            case ROUTE_TO_ANCHOR -> {
                String operationId = pending.planId() + ":home-departure";
                BaritonePort.Goal goal = new BaritonePort.Goal(
                        operationId,
                        "goto",
                        Map.of(
                                "x", Integer.toString(home.x()),
                                "y", Integer.toString(home.y()),
                                "z", Integer.toString(home.z()),
                                "dimension", home.dimension(),
                                "range", "0"));
                baritone.start(goal, lease);
                BaritonePort.Status status = baritone.poll(operationId);
                if (status.state() == BaritonePort.State.BLOCKED) {
                    yield blockHome(
                            "acquisition_departure_route_blocked",
                            "Could not return to the exact reserved Home anchor: "
                                    + status.detail(),
                            true,
                            nowMillis);
                }
                if (status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
                    yield homeRunning(
                            "retrying exact Home departure route: " + status.detail());
                }
                yield homeRunning(status.state() == BaritonePort.State.COMPLETE
                        ? "reached the exact Home departure anchor; verifying next tick"
                        : "returning to the exact Home departure anchor: " + status.detail());
            }
            case CHECKPOINT_ANCHOR -> {
                if (plan.frames().size() != 1 || !plan.currentFrame().orElseThrow().id().equals(root.id())) {
                    yield blockHome(
                            "acquisition_departure_order_invalid",
                            "A Home acquisition child exists before its exact departure was checkpointed",
                            false,
                            nowMillis);
                }
                LinkedHashMap<String, String> checkpoint =
                        new LinkedHashMap<>(root.parameters());
                checkpoint.put(HOME_ACQUISITION_DEPARTURE_KEY, home.fingerprint());
                plans.checkpointCurrent(
                        pending.planId(), checkpoint,
                        "verified exact safe Home acquisition departure", nowMillis);
                baritone.cancel(lease.epoch(), "Home acquisition departure checkpointed");
                yield homeRunning(
                        "checkpointed the exact safe Home anchor before resource travel");
            }
        };
    }

    private HomeIdleOutcome finishHomeCycle(
            HomeEconomySession.Phase phase,
            String detail,
            long nowMillis) throws IOException {
        boolean completedExplicitStock = homeRunRequested;
        if (homeEconomy.snapshot().phase() != phase) homeEconomy.advance(phase, nowMillis);
        homeRunRequested = false;
        if (completedExplicitStock) completedExplicitStockCycles++;
        if (idleStockCycle != null) {
            idleStockCycle = null; idleStockState = IdleStockPolicy.State.COMPLETED;
            idleStockResultKey = "completed"; idleStockDetail = detail;
        }
        reservations.releaseOwner(HOME_RESERVATION_OWNER);
        syncBaritoneThrowawayReservations();
        boolean closed = inventory.closeHandledScreenIfCursorEmpty(nowMillis)
                == ClientInventoryController.SafeCloseResult.CLOSED;
        workstations.suspendAll();
        homeScreenClosePending = !closed;
        if (completedExplicitStock) tidy.queueAutomaticAtStockBoundary();
        return closed
                ? HomeIdleOutcome.release(detail)
                : homeRunning(detail + "; reconciling the final chest click before release");
    }

    private HomeIdleOutcome blockHome(
            HomeEconomyPolicy.Decision decision,
            long nowMillis) throws IOException {
        return blockHome(
                decision.code(), decision.detail(), retryableHomeBlock(decision.code()), nowMillis);
    }

    private HomeIdleOutcome blockHome(
            String code,
            String detail,
            boolean retryable,
            long nowMillis) throws IOException {
        // Stock-OFF bed use reports its physical failure without inventing an
        // enabled economy phase. Exact bed/custody checks remain in the actuator.
        if (homeEconomy.snapshot().enabled()
                && homeEconomy.snapshot().phase() != HomeEconomySession.Phase.BLOCKED) {
            homeEconomy.block(code, detail, retryable, nowMillis);
        }
        homeRunRequested = false;
        if (idleStockCycle != null) {
            idleStockRouteRecovery.failed(homeEconomy.snapshot(), nowMillis);
            idleStockCycle = null; idleStockState = IdleStockPolicy.State.BLOCKED;
            idleStockResultKey = code; idleStockDetail = detail;
        }
        homeScreenClosePending = inventory.closeHandledScreenIfCursorEmpty(nowMillis)
                != ClientInventoryController.SafeCloseResult.CLOSED;
        workstations.suspendAll();
        reservations.releaseOwner(HOME_RESERVATION_OWNER);
        syncBaritoneThrowawayReservations();
        lastHomeEconomyDetail = detail;
        return HomeIdleOutcome.release("blocked: " + detail);
    }

    private Optional<ActionLease> acquireHomeAction(
            ControlLease parent,
            ActionOwner owner,
            String operationId,
            long clientTick,
            long nowMillis) {
        ExecutionKernel.Decision requested = executionKernel.request(
                parent, owner, operationId, clientTick, nowMillis);
        if (requested.state() == ExecutionKernel.State.NEUTRALIZE
                || requested.state() == ExecutionKernel.State.PARENT_INVALID) {
            neutralizeActionOwnerControls(requested.detail());
            requested.transition().ifPresent(ticket ->
                    executionKernel.confirmNeutralized(ticket, clientTick));
            return Optional.empty();
        }
        if (requested.state() == ExecutionKernel.State.WAIT_NEXT_TICK) return Optional.empty();
        ActionLease lease = requested.lease().orElseThrow(() ->
                new IllegalStateException("Home action owner was not granted a capability"));
        executionKernel.requireValid(lease, parent, clientTick, nowMillis);
        return Optional.of(lease);
    }

    private EnumMap<HomeEconomySession.AssetRole, HomeEconomyPolicy.AssetObservation>
    observeHomeAssets(HomeEconomySession.Snapshot snapshot) {
        EnumMap<HomeEconomySession.AssetRole, HomeEconomyPolicy.AssetObservation> result =
                new EnumMap<>(HomeEconomySession.AssetRole.class);
        for (HomeEconomySession.AssetRole role : HomeEconomySession.AssetRole.values()) {
            String id = homeAssetId(snapshot, role);
            Optional<FieldKitLedger.Asset> owned = workstations.ownedAsset(id);
            if (owned.isEmpty()) {
                result.put(role, HomeEconomyPolicy.AssetObservation.missing(role));
                continue;
            }
            FieldKitLedger.Asset asset = owned.orElseThrow();
            HomeEconomyPolicy.AssetTruth truth;
            if (asset.state() == FieldKitLedger.AssetState.CARRIED) {
                truth = HomeEconomyPolicy.AssetTruth.OWNED_CARRIED;
            } else if (asset.state() == FieldKitLedger.AssetState.LOST) {
                truth = HomeEconomyPolicy.AssetTruth.OWNED_LOST;
            } else if (asset.lastKnownPosition() == null
                    || client.world == null
                    || !asset.lastKnownPosition().dimension().equals(currentDimension())
                    // ClientWorld.isChunkLoaded(int,int) is always true in 1.21.8.
                    // Query the client cache before interpreting an absent chunk as air.
                    || !client.world.getChunkManager().isChunkLoaded(
                    asset.lastKnownPosition().x() >> 4,
                    asset.lastKnownPosition().z() >> 4)) {
                truth = HomeEconomyPolicy.AssetTruth.OWNED_PLACED_UNLOADED;
            } else {
                BlockPos position = new BlockPos(
                        asset.lastKnownPosition().x(),
                        asset.lastKnownPosition().y(),
                        asset.lastKnownPosition().z());
                truth = workstationKind(role).matches(client.world.getBlockState(position))
                        ? HomeEconomyPolicy.AssetTruth.OWNED_PLACED_VERIFIED
                        : HomeEconomyPolicy.AssetTruth.OWNED_PLACED_MISSING;
            }
            result.put(role, new HomeEconomyPolicy.AssetObservation(role, truth, asset.id()));
        }
        return result;
    }

    private static boolean allHomeAssetsPlacedVerified(
            Map<HomeEconomySession.AssetRole, HomeEconomyPolicy.AssetObservation> assets) {
        return Arrays.stream(HomeEconomySession.AssetRole.values()).allMatch(role ->
                assets.get(role) != null
                        && assets.get(role).truth()
                        == HomeEconomyPolicy.AssetTruth.OWNED_PLACED_VERIFIED);
    }

    private boolean pendingTransferChestVerified(
            HomeEconomySession.Snapshot snapshot,
            Map<HomeEconomySession.AssetRole, HomeEconomyPolicy.AssetObservation> assets) {
        HomeEconomySession.StorageRegistration pinned = selectedHomeStorage(snapshot);
        HomeEconomyPolicy.AssetObservation observed = assets.get(
                HomeEconomySession.AssetRole.CHEST);
        return pinned != null && observed != null
                && pinned.ledgerAssetId().equals(observed.ledgerAssetId())
                && observed.truth()
                == HomeEconomyPolicy.AssetTruth.OWNED_PLACED_VERIFIED;
    }

    private String homeAssetId(
            HomeEconomySession.Snapshot snapshot,
            HomeEconomySession.AssetRole role) {
        if (role == HomeEconomySession.AssetRole.CHEST) {
            var registered = selectedHomeStorage(snapshot);
            if (registered != null) return registered.ledgerAssetId();
        }
        HomeEconomySession.PinnedAsset pinned = snapshot.pinnedAssets().get(role);
        if (pinned != null) return pinned.ledgerAssetId();
        String prefix = HomeEconomySession.assetIdPrefix(snapshot.generation(), role);
        String primary = prefix + "primary";
        Optional<FieldKitLedger.Asset> existing = workstations.ownedAsset(primary);
        if (existing.isEmpty() || existing.orElseThrow().state() != FieldKitLedger.AssetState.LOST) {
            return primary;
        }
        return prefix + "replacement-" + snapshot.revision();
    }

    private static WorkstationController.Kind workstationKind(
            HomeEconomySession.AssetRole role) {
        return switch (role) {
            case CRAFTING_TABLE -> WorkstationController.Kind.CRAFTING_TABLE;
            case FURNACE -> WorkstationController.Kind.FURNACE;
            case CHEST -> WorkstationController.Kind.CHEST;
            case BED -> WorkstationController.Kind.WHITE_BED;
        };
    }

    private String homeWorkstationOperation(
            HomeEconomySession.Snapshot snapshot,
            HomeEconomySession.AssetRole role) {
        // A replacement is a new physical ownership transaction. Including the
        // exact ledger ID prevents a session deliberately pinned to a lost
        // asset from rejecting its generation-scoped successor as an identity
        // mutation of the old operation.
        return "home:economy:" + snapshot.generation() + ":asset:" + role.item()
                + ':' + homeAssetId(snapshot, role);
    }

    private HomeEconomyPolicy.AcquisitionTruth homeAcquisitionTruth(
            HomeEconomySession.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        boolean durableReturnPending = snapshot.pendingAcquisition() != null
                && snapshot.ownsUnderlyingPhase(
                HomeEconomySession.Phase.RETURNING_HOME);
        return HomeEconomyPolicy.latchAcquisitionReturn(
                durableReturnPending,
                durableReturnPending
                        ? HomeEconomyPolicy.AcquisitionTruth.NONE
                        : homePlannerAcquisitionTruth(snapshot.pendingAcquisition()));
    }

    private HomeEconomyPolicy.AcquisitionTruth homePlannerAcquisitionTruth(
            HomeEconomySession.PendingAcquisition pending) {
        if (pending == null) return HomeEconomyPolicy.AcquisitionTruth.NONE;
        Optional<TaskPlan> plan = plans.restore(pending.planId());
        if (plan.isEmpty()) return HomeEconomyPolicy.AcquisitionTruth.NONE;
        return switch (plan.orElseThrow().state()) {
            case OPEN -> HomeEconomyPolicy.AcquisitionTruth.ACTIVE;
            case COMPLETED -> homeGoalsSatisfied(pending.goals())
                    ? HomeEconomyPolicy.AcquisitionTruth.COMPLETED
                    : HomeEconomyPolicy.AcquisitionTruth.DIVERGED;
            case CLEARED, RETIRED -> HomeEconomyPolicy.AcquisitionTruth.FAILED;
        };
    }

    private String homeAcquisitionDetail(HomeEconomySession.PendingAcquisition pending) {
        if (pending == null) return "";
        return plans.restore(pending.planId()).map(TaskPlan::detail)
                .orElse("planner checkpoint " + pending.planId() + " is missing");
    }

    private Mission homeAcquisitionMission(HomeEconomySession.PendingAcquisition pending) {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("homeMaintenance", "true");
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        parameters.put("homeGeneration", Long.toString(snapshot.generation()));
        if (snapshot.home() != null) {
            parameters.put("homeFingerprint", snapshot.home().fingerprint());
            parameters.put("homeDimension", snapshot.home().dimension());
            parameters.put("homeX", Integer.toString(snapshot.home().x()));
            parameters.put("homeY", Integer.toString(snapshot.home().y()));
            parameters.put("homeZ", Integer.toString(snapshot.home().z()));
        }
        snapshot.pinnedAssets().forEach((role, asset) ->
                parameters.put(homeLedgerParameter(role), asset.ledgerAssetId()));
        String unpinnedAssetRoles = HomeEconomyPolicy
                .unpinnedAssetRolesAtPlanStart(snapshot).stream()
                .map(Enum::name)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        if (!unpinnedAssetRoles.isBlank()) {
            parameters.put(HOME_UNPINNED_ASSET_ROLES, unpinnedAssetRoles);
        }
        if (pending.goals().size() == 1) {
            Map.Entry<String, Integer> goal = pending.goals().entrySet().iterator().next();
            parameters.put("item", goal.getKey());
            parameters.put("count", Integer.toString(goal.getValue()));
        } else {
            parameters.put("batchItems", pending.goals().entrySet().stream()
                    .map(entry -> entry.getKey() + '=' + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",")));
            parameters.put("batch", "true");
        }
        return new Mission(
                pending.planId(), pending.planId(), "entity-economy", "get",
                parameters, pending.startedAtMillis());
    }

    private boolean homeGoalsSatisfied(Map<String, Integer> goals) {
        return HomeStockPolicy.acquisitionGoalsSatisfied(
                goals, inventory.homePlayerCounts());
    }

    private Map<String, Integer> homeProtectedCounts() {
        return PersonalSuppliesPolicy.allocate(inventory.personalSupplies().playerStacks(),
                retainedPersonalProfile(), personalSupplyClaims(), Set.of()).outboundProtectedCounts();
    }

    private List<HomeStockPolicy.Target> retainedPersonalProfile() {
        return HomeStockPolicy.retainedProfile(idleWorkingTools.tools());
    }

    private List<InventoryReservationLedger.Reservation> personalSupplyClaims() {
        refreshEmergencyTechniqueReservationsAndSync();
        var claims = new ArrayList<>(reservations.reservations().values());
        workstations.carriedFieldKitCounts().forEach((item, count) -> {
            int alreadyClaimed = reservations.reservedCount(item, Set.of(Purpose.FIELD_KIT));
            if (count > alreadyClaimed) claims.add(new InventoryReservationLedger.Reservation(
                    "home-carried-kit:" + item, HOME_RESERVATION_OWNER, item, count - alreadyClaimed,
                    Purpose.FIELD_KIT, "Retain the exact carried Home workstation"));
        });
        return List.copyOf(claims);
    }

    private boolean atHome(HomeEconomySession.HomeAnchor home) {
        if (client.player == null || client.world == null
                || !home.dimension().equals(currentDimension())) return false;
        BlockPos player = client.player.getBlockPos();
        long dx = (long) player.getX() - home.x();
        long dy = (long) player.getY() - home.y();
        long dz = (long) player.getZ() - home.z();
        long radius = HOME_ASSET_RADIUS + 2L;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private boolean homeLoaded(HomeEconomySession.HomeAnchor home) {
        return client.world != null
                && home.dimension().equals(currentDimension())
                && client.world.getChunkManager().isChunkLoaded(home.x() >> 4, home.z() >> 4);
    }

    private HomeEconomySession.PendingTransfer pendingTransfer(
            String id,
            HomeStockPolicy.Transfer transfer,
            ClientInventoryController.HomeTransferPlan plan,
            long nowMillis) {
        var snapshot = homeEconomy.snapshot();
        var storage = selectedHomeStorage(snapshot);
        var identity = storage == null ? null : workstations.exactPinnedStorageIdentity(storage.id(),
                homeWorkstationOperation(snapshot, HomeEconomySession.AssetRole.CHEST), storage.ledgerAssetId(),
                snapshot.home(), HOME_ASSET_RADIUS).orElse(null);
        if (identity == null) throw new IllegalStateException("No exact registered container identity for Home transfer");
        return new HomeEconomySession.PendingTransfer(
                id,
                transfer.direction(),
                transfer.categoryId(),
                transfer.item(),
                plan.count(),
                plan.syncId(),
                plan.sourceSlot(),
                plan.destinationSlot(),
                plan.destinationCountBefore(),
                plan.playerCountBefore(),
                plan.chestCountBefore(),
                nowMillis, identity);
    }

    private static ClientInventoryController.HomeTransferPlan homeTransferPlan(
            HomeEconomySession.PendingTransfer pending) {
        return new ClientInventoryController.HomeTransferPlan(
                pending.direction(),
                pending.item(),
                pending.count(),
                pending.syncId(),
                pending.sourceSlot(),
                pending.destinationSlot(),
                pending.playerCountBefore(),
                pending.chestCountBefore(),
                pending.destinationCountBefore());
    }

    private static String homeTransferOwner(HomeEconomySession.PendingTransfer pending) {
        return "home-transfer-owner:" + pending.id();
    }

    private void logCommittedHomeTransfer(
            HomeEconomySession.PendingTransfer pending,
            HomeEconomyPolicy.TransferObservation observed) {
        // The caller has accepted exact native count truth, released custody and
        // durably retired this original intent. Do not log predicted click deltas.
        logger.info("Home transfer settled owner={} item={} count={} player={} chest={}",
                homeTransferOwner(pending), ClientInventoryController.normalizeId(pending.item()),
                pending.count(), observed.playerItemCount(), observed.chestItemCount());
    }

    private boolean releaseSettledHomeTransferCustody(
            HomeEconomySession.PendingTransfer pending) {
        if (pending == null) return false;
        return inventory.releaseSettledHomeTransferCustody(
                homeTransferPlan(pending), homeTransferOwner(pending));
    }

    private void rememberHomeChestObservation(
            HomeEconomySession.Snapshot snapshot,
            ClientInventoryController.HomeChestSnapshot observed,
            long nowMillis) {
        if (snapshot.home() == null || !observed.open() || !observed.cursorEmpty()
                || snapshot.pendingTransfer() != null) return;
        var storage = selectedHomeStorage(snapshot);
        if (storage == null) return;
        var identity = workstations.exactPinnedStorageIdentity(storage.id(),
                homeWorkstationOperation(snapshot, HomeEconomySession.AssetRole.CHEST), storage.ledgerAssetId(),
                snapshot.home(), HOME_ASSET_RADIUS);
        if (identity.isEmpty()) return;
        homeStorageObservations.put(storage.id(), new ObservedHomeStorage(identity.orElseThrow(),
                observed, inventory.homeStorageCapacity(), nowMillis));
        lastHomeChestCounts = aggregateHomeStorageCounts(snapshot);
        lastHomeChestFingerprint = snapshot.home().fingerprint();
        lastHomeChestObservedAt = nowMillis;
    }

    private record HomeStockForecastInput(Map<String, Integer> carried, Map<String, Integer> stored,
            Map<String, Integer> capacity, Set<String> stations, Map<String, Integer> durability, String catalog,
            Map<String, String> workingTools) { }

    private List<HomeStockPolicy.Target> forecastHomeStockTargets(HomeEconomySession.Snapshot snapshot,
            ClientInventoryController.HomeChestSnapshot chest) {
        var retainedProfile = retainedPersonalProfile();
        if (!resourceCatalogReady || !chest.open() || !chest.cursorEmpty()
                || snapshot.pendingTransfer() != null || snapshot.pendingAcquisition() != null) {
            return retainedProfile;
        }
        var valid = validHomeStorage(snapshot);
        if (valid.size() != snapshot.storageRegistrations().size()
                || !homeStorageObservations.keySet().containsAll(valid.keySet())) return retainedProfile;
        Map<String, Integer> stored = aggregateHomeStorageCounts(snapshot);
        Map<String, Integer> carried = canonicalInventorySnapshot();
        var physical = planningInventoryView();
        Map<String, Integer> capacity = new LinkedHashMap<>();
        for (var target : retainedProfile) {
            if (!target.id().equals("fuel") && !target.id().equals("logs")) continue;
            int room = 0;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                if (stack.isEmpty()) room += inventoryStackLimit(target.acquisitionItem());
                else if (target.accepts(Registries.ITEM.getId(stack.getItem()).toString())) {
                    room += Math.max(0, stack.getMaxCount() - stack.getCount());
                }
            }
            capacity.put(target.id(), room);
        }
        var input = new HomeStockForecastInput(carried, stored, Map.copyOf(capacity),
                physical.availableStations(), usablePickaxeDurability(), resourceCatalogFingerprint, idleWorkingTools.tools());
        if (input.equals(homeStockForecastInput)) return homeStockForecastTargets;
        homeStockForecastInput = input;
        homeStockForecastTargets = retainedProfile;
        Map<String, Integer> combined = new LinkedHashMap<>(carried);
        stored.forEach((item, count) -> combined.merge(resourcePlanner.catalog().normalizeExactItem(item), count, Math::addExact));
        var base = HomeStockPolicy.analyze(chest.playerCounts(), stored, chest.depositEligibleCounts(), retainedProfile);
        var goals = HomeStockDemandForecast.recipeGoals(base, combined);
        AcquisitionPlan planned = null;
        try {
            if (!goals.isEmpty()) planned = resourcePlanner.compile(new AcquisitionRequest(goals,
                    new ResourcePlanner.InventoryView(combined, physical.equippedItems(),
                            physical.locallyAvailableItems(), physical.availableStations()), input.durability()));
        } catch (ResourcePlanner.PlanningException unavailableForecast) {
            logger.info("Stock consumption forecast deferred to existing category execution: {}", unavailableForecast.getMessage());
            return homeStockForecastTargets;
        }
        // Forecast only already-observed convertible food, using the existing
        // continuous furnace budget. Never guess the species of a future hunt.
        Map<String, Integer> foodSpendable = new LinkedHashMap<>(combined);
        if (planned != null) planned.totals().consumed().forEach((item, count) ->
                foodSpendable.computeIfPresent(item, (ignored, present) -> Math.max(0, present - count)));
        var food = FoodFamilyPolicy.summary(combined);
        int desiredFood = retainedProfile.stream().filter(target -> target.id().equals("food"))
                .findFirst().orElseThrow().desiredCount();
        int knownFoodTarget = Math.min(desiredFood, food.readyCount() + food.convertibleCount());
        int foodFuel = knownFoodTarget <= food.readyCount() ? 0
                : decideFoodAcquisition(knownFoodTarget, combined, foodSpendable).requiredFuel();
        var consumed = HomeStockDemandForecast.knownConsumption(planned,
                foodFuel == 0 ? Map.of() : Map.of("coal", foodFuel));
        var forecast = HomeStockDemandForecast.forecast(retainedProfile,
                carried, stored, consumed, capacity);
        homeStockForecastTargets = forecast.targets();
        if (forecast.materials().values().stream().anyMatch(demand -> demand.knownConsumption() > 0)) {
            logger.info("Stock known-consumption budget (one category remains the only executor): {}", forecast.materials());
        }
        return homeStockForecastTargets;
    }

    private record ObservedHomeStorage(HomeEconomySession.ContainerIdentity identity,
            ClientInventoryController.HomeChestSnapshot chest,
            ClientInventoryController.HomeStorageCapacity capacity, long observedAtMillis) { }

    private Map<String, HomeEconomySession.ContainerIdentity> validHomeStorage(HomeEconomySession.Snapshot snapshot) {
        Map<String, HomeEconomySession.ContainerIdentity> valid = new LinkedHashMap<>();
        Set<FieldKitLedger.Position> occupied = new LinkedHashSet<>();
        for (var registration : snapshot.storageRegistrations().values()) {
            var identity = workstations.observeHomeStorageIdentity(registration.id(), registration.ledgerAssetId(),
                    snapshot.home(), HOME_ASSET_RADIUS);
            if (identity.isEmpty() || identity.orElseThrow().halves().stream().anyMatch(half -> occupied.contains(half.position()))) {
                homeStorageObservations.remove(registration.id());
                continue;
            }
            var exact = identity.orElseThrow();
            exact.halves().forEach(half -> occupied.add(half.position()));
            valid.put(registration.id(), exact);
            var old = homeStorageObservations.get(registration.id());
            if (old != null && !old.identity().equals(exact)) homeStorageObservations.remove(registration.id());
        }
        homeStorageObservations.keySet().retainAll(valid.keySet());
        return valid;
    }

    private HomeEconomySession.StorageRegistration selectedHomeStorage(HomeEconomySession.Snapshot snapshot) {
        if (snapshot.pendingTransfer() != null && snapshot.pendingTransfer().containerIdentity() != null) {
            return snapshot.storageRegistrations().get(snapshot.pendingTransfer().containerIdentity().storageId());
        }
        if (snapshot.pendingTransfer() != null) return snapshot.primaryStorage();
        // The original provisioning path pins a carried chest before placing it.
        // Registration is not a requirement that a new Home's asset already exists in-world.
        if (snapshot.primaryStorage() != null) {
            var initial = workstations.ownedAsset(snapshot.primaryStorage().ledgerAssetId()).orElse(null);
            if (initial != null && initial.state() == FieldKitLedger.AssetState.CARRIED) return snapshot.primaryStorage();
        }
        var valid = validHomeStorage(snapshot);
        if (!valid.containsKey(activeHomeStorageId)) {
            activeHomeStorageId = snapshot.primaryStorage() != null && valid.containsKey(snapshot.primaryStorage().id())
                    ? snapshot.primaryStorage().id() : valid.keySet().stream().findFirst().orElse("");
        }
        return snapshot.storageRegistrations().get(activeHomeStorageId);
    }

    private Map<String, Integer> aggregateHomeStorageCounts(HomeEconomySession.Snapshot snapshot) {
        validHomeStorage(snapshot);
        Map<String, Integer> counts = new LinkedHashMap<>();
        homeStorageObservations.values().forEach(observation -> observation.chest().chestCounts()
                .forEach((item, count) -> counts.merge(item, count, Math::addExact)));
        return Map.copyOf(counts);
    }

    private String storageForTransfer(HomeEconomySession.Snapshot snapshot, HomeStockPolicy.Transfer transfer) {
        validHomeStorage(snapshot);
        String item = ClientInventoryController.normalizeId(transfer.item());
        return homeStorageObservations.entrySet().stream()
                .filter(entry -> transfer.direction() == HomeStockPolicy.Direction.DEPOSIT
                        ? entry.getValue().capacity().accepts(item)
                        : entry.getValue().chest().chestCounts().getOrDefault(item, 0) > 0)
                .sorted(Comparator.comparingInt(entry -> transfer.direction() == HomeStockPolicy.Direction.DEPOSIT
                        && entry.getValue().capacity().mergeRoom().getOrDefault(item, 0) > 0 ? 0 : 1))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private HomeIdleOutcome switchHomeStorage(String storageId, long nowMillis) {
        if (!inventory.cursorEmpty() || !inventory.transactionDiagnostics().pendingClickOwner().isBlank()) {
            return homeRunning("settling current chest before selecting registered storage " + storageId);
        }
        if (inventory.closeHandledScreenIfCursorEmpty(nowMillis) != ClientInventoryController.SafeCloseResult.CLOSED) {
            return homeRunning("closing current chest before selecting registered storage " + storageId);
        }
        workstations.suspendAll();
        activeHomeStorageId = storageId;
        return homeRunning("observing registered storage " + storageId);
    }

    private static boolean retryableHomeBlock(String code) {
        return switch (code) {
            case "asset_identity_mismatch", "home_binding_changed",
                    "home_settings_missing", "transfer_counts_diverged",
                    "cursor_occupied" -> false;
            default -> true;
        };
    }

    private HomeIdleOutcome homeRunning(String detail) {
        lastHomeEconomyDetail = Objects.requireNonNullElse(detail, "Home economy active");
        return HomeIdleOutcome.retain(lastHomeEconomyDetail);
    }

    private HomeIdleOutcome homeAcquisitionRunning(
            HomeEconomySession.PendingAcquisition pending,
            String detail,
            long completedWorkUnits) {
        lastHomeEconomyDetail = Objects.requireNonNullElse(detail, "Home acquisition active");
        return HomeIdleOutcome.retainProgress(
                lastHomeEconomyDetail,
                pending.planId(),
                completedWorkUnits);
    }

    private HomeEconomySession requireHomeEconomy() {
        if (homeEconomy == null) {
            throw new IllegalStateException(homeEconomyLoadFailure.isBlank()
                    ? "Home economy store is unavailable" : homeEconomyLoadFailure);
        }
        return homeEconomy;
    }

    private HomeEconomySession.HomeAnchor configuredHome() {
        return settings.hasHomeChest()
                ? HomeEconomySession.HomeAnchor.at(
                settings.homeDimension(), settings.homeX(), settings.homeY(), settings.homeZ())
                : null;
    }

    /**
     * The settings/cursor policy is the only automatic owner of Home identity
     * reconciliation.  No caller may infer a missing Home from untrusted
     * settings or mutate the cursor around this boundary.
     */
    private String reconcileHomeStores(long nowMillis) throws IOException {
        if (homeEconomy == null) return homeEconomyLoadFailure;
        HomeStoreReconciliationPolicy.Result result =
                HomeStoreReconciliationPolicy.reconcile(
                        settingsLoadState.authoritative()
                                ? HomeStoreReconciliationPolicy.SettingsAuthority.TRUSTED
                                : HomeStoreReconciliationPolicy.SettingsAuthority.QUARANTINED,
                        settings.stockEnabled(), configuredHome(), homeEconomy, nowMillis,
                        this::clearHomeWorkstationSessions);
        if (result.quarantined()) {
            return homeSettingsQuarantineStatus();
        }
        return result.detail();
    }

    /** Empty when startup settings are authoritative; suitable for /e status. */
    public String homeSettingsSafetyStatus() {
        return settingsLoadState.authoritative() ? "" : homeSettingsQuarantineStatus();
    }

    private String appendHomeSettingsSafety(String status) {
        String safety = homeSettingsSafetyStatus();
        return safety.isBlank() ? status : safety + "; preserved-state: " + status;
    }

    private String homeSettingsQuarantineStatus() {
        return "HOME QUARANTINED: autonomy settings are "
                + settingsLoadState.name().toLowerCase(Locale.ROOT)
                + " at " + settingsLoadDetail
                + ". Automatic Home/economy is off and the checksummed Home is preserved. "
                + "Restore schema-1 autonomy.json and restart; /e home clear intentionally "
                + "forgets it";
    }

    private void requireTrustedHomeSettings(String operation) {
        if (!settingsLoadState.authoritative()) {
            throw new IllegalArgumentException(
                    "Cannot " + Objects.requireNonNullElse(operation, "change Home") + ": "
                            + homeSettingsQuarantineStatus());
        }
    }

    private void markSettingsValid(AutonomySettingsStore.Settings next) {
        settings = Objects.requireNonNull(next, "next");
        settingsLoadState = AutonomySettingsStore.LoadState.VALID;
        settingsLoadDetail = "";
    }

    private void clearHomeWorkstationSessions(long generation) {
        if (generation <= 0) return;
        workstations.clearPrefix("home:economy:" + generation + ":asset:");
    }

    /**
     * Death is a discontinuity, not an ordinary retry. Every transient leaf
     * baseline and controller generation is discarded, then the immutable
     * mission root replans from the inventory that actually survived respawn.
     * Only server-confirmed cumulative delivery progress is allowed across the
     * boundary.
     */
    public String reconcileAfterDeath(
            String missionId,
            String recoveryToken,
            long clientTick,
            long nowMillis) throws IOException {
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("missionId is required for death reconciliation");
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String recovery = recoveryPlanByMission.get(missionId);
        if (recovery != null) candidates.add(recovery);
        candidates.add(missionId);

        return reconcilePlanCandidatesAfterDeath(
                candidates, recoveryToken, clientTick, nowMillis);
    }

    /**
     * A player death invalidates shared inventory and world assumptions for every parked as well
     * as active mission. Rebase every open plan while leaving each core mission's parked/runnable
     * state unchanged. The one boundary token makes retries and crash recovery idempotent.
     */
    public String reconcileAllPlansAfterDeath(
            String recoveryToken,
            long clientTick,
            long nowMillis) throws IOException {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (TaskPlan plan : plans.plans()) {
            if (plan.state() == TaskPlanState.OPEN) candidates.add(plan.missionId());
        }
        return reconcilePlanCandidatesAfterDeath(
                candidates, recoveryToken, clientTick, nowMillis);
    }

    /** True only after every currently open plan crossed the exact death boundary. */
    public boolean allOpenPlansReconciledAfterDeath(String recoveryToken) {
        DeathReconciliationTokenPolicy.markApplied(Map.of(), recoveryToken);
        for (TaskPlan plan : plans.plans()) {
            if (plan.state() != TaskPlanState.OPEN) continue;
            if (plan.frames().isEmpty() || !DeathReconciliationTokenPolicy.alreadyApplied(
                    plan.frames().getFirst().parameters(), recoveryToken)) {
                return false;
            }
        }
        return true;
    }

    private String reconcilePlanCandidatesAfterDeath(
            LinkedHashSet<String> candidates,
            String recoveryToken,
            long clientTick,
            long nowMillis) throws IOException {
        // Validate before clearing any transient owner state. The same token is
        // written atomically with each plan rebase and fences crash retries.
        DeathReconciliationTokenPolicy.markApplied(Map.of(), recoveryToken);
        releaseControls("death recovery reconciliation", clientTick);
        actionSupervisor.clearAll();
        workstations.clearAll();
        groundItems.clear(Long.MIN_VALUE, "death recovery reconciliation");
        deliveries.clearAll();
        deliveryPendingInvalidated = false;
        reservations.clear();
        // Paper replays unacknowledged cumulative receipts. They deliberately
        // survive a death/rebase discontinuity and cannot double count.

        int rebased = 0;
        int alreadyRebased = 0;
        for (String planId : candidates) {
            Optional<TaskPlan> restored = plans.restore(planId);
            if (restored.isEmpty() || restored.orElseThrow().state() != TaskPlanState.OPEN) continue;
            TaskPlan plan = restored.orElseThrow();
            PlanFrame root = plan.frames().isEmpty() ? null : plan.frames().getFirst();
            if (root != null && DeathReconciliationTokenPolicy.alreadyApplied(
                    root.parameters(), recoveryToken)) {
                alreadyRebased++;
                continue;
            }
            plans.rebaseToRoot(
                    planId,
                    DeathReconciliationTokenPolicy.markApplied(
                            confirmedDeathFacts(plan), recoveryToken),
                    "death recovery rebuilt execution from current world and inventory truth",
                    nowMillis);
            rebased++;
        }
        if (rebased == 0 && alreadyRebased > 0) {
            return "retained " + alreadyRebased + " already committed death-recovery rebase"
                    + (alreadyRebased == 1 ? "" : "s");
        }
        return rebased == 0
                ? "cleared transient controllers; no hierarchical plan required rebasing"
                : "rebased " + rebased + " hierarchical plan generation"
                + (rebased == 1 ? "" : "s");
    }

    private static Map<String, String> confirmedDeathFacts(TaskPlan plan) {
        if (plan.frames().isEmpty()) {
            return Map.of();
        }
        PlanFrame root = plan.frames().get(0);
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        if (root.kind().startsWith("root.batch.")) {
            copyFact(root.parameters(), facts, GatherFirstBatchPolicy.PHASE_KEY);
            copyFact(root.parameters(), facts, GatherFirstBatchPolicy.ACQUIRE_CURSOR_KEY);
            copyFact(root.parameters(), facts, GatherFirstBatchPolicy.DELIVERY_CURSOR_KEY);
            copyFact(root.parameters(), facts, GatherFirstBatchPolicy.ACQUIRED_COUNTS_KEY);
            copyFact(root.parameters(), facts, GatherFirstBatchPolicy.DELIVERED_COUNTS_KEY);
            // Retain the legacy scalar checkpoint until it has been migrated by
            // tickBatchRoot.  A death must never turn an old partial mission
            // into a brand-new order.
            copyPositiveOrZero(root.parameters(), facts, "batchIndex");
            copyPositiveOrZero(root.parameters(), facts, "batchDelivered");
        } else if (root.kind().equals("root.bring")
                || root.kind().equals("root.give")
                || root.kind().equals("root.food.bring")
                || root.kind().equals("root.food.give")) {
            copyPositiveOrZero(root.parameters(), facts, "deliveredTotal");
            copyFact(root.parameters(), facts, "foodPhase");
        }
        // The exact program/cursor survives death. The death-drop root first
        // reconciles inventory truth; only a later confirmed loss invalidates
        // and recompiles it. Silently deleting the payload here would turn
        // every respawn into an untyped fresh plan.
        copyFact(root.parameters(), facts, UNIVERSAL_PROGRAM_KEY);
        copyPrefixedFacts(
                root.parameters(), facts, UNIVERSAL_WORKSTATION_FACT_PREFIX);
        copyPrefixedFacts(root.parameters(), facts, "deathRecovery");
        copyPrefixedFacts(root.parameters(), facts, "deathHandoff");
        return Map.copyOf(facts);
    }

    public void releaseControls(String reason) {
        releaseControls(reason, lastClientTick);
    }

    public void releaseControls(String reason, long clientTick) {
        releaseControls(reason, clientTick, false);
    }

    /** Stop revokes work immediately; only an exact separately fenced cargo suffix may click. */
    public void releaseControlsForOwnerStop(String reason, long clientTick) {
        releaseControls(reason, clientTick, true);
    }

    private void releaseControls(String reason, long clientTick, boolean ownerStop) {
        if (homeWaterFill != null) {
            try { homeWaterFill.fenceMission(System.currentTimeMillis()); }
            catch (IOException error) {
                missionWaterFenceFailure = true;
                logger.error("Mission water-use fence could not be persisted; further fills are disabled", error);
            }
        }
        long transitionTick = Math.max(lastClientTick, clientTick);
        lastClientTick = transitionTick;
        invalidateProtectionLoadoutEpisode();
        invalidateAttackMissionLoadout();
        ExecutionKernel.TransitionTicket transition = executionKernel.invalidateAll(
                transitionTick, Objects.requireNonNullElse(reason, "autonomy controls released"));
        neutralizePhysicalControls(reason, ownerStop || stoppedCapacityCleanupPending());
        executionKernel.confirmNeutralized(transition, transitionTick);
        miningSessions.clear();
        reservations.clear();
        baritone.releaseMissionThrowawayReservations();
    }

    /** True when an exact Baritone child operation belongs to retained Home acquisition. */
    public boolean ownsHomeSafetyOperation(String operationId) {
        String candidate = Objects.requireNonNullElse(operationId, "").trim();
        HomeEconomySession.PendingAcquisition pending =
                homeEconomy.snapshot().pendingAcquisition();
        if (candidate.isBlank() || pending == null) return false;
        return candidate.equals(pending.planId())
                || candidate.startsWith(pending.planId() + '/');
    }

    /** Durably parks active or protection-paused Home work after a hard safety invariant. */
    public boolean blockHomeForSafety(String code, String detail, long nowMillis)
            throws IOException {
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        if (!snapshot.enabled()
                || snapshot.phase() == HomeEconomySession.Phase.BLOCKED
                || (snapshot.phase() == HomeEconomySession.Phase.PAUSED
                        && snapshot.pause().resumePhase()
                        == HomeEconomySession.Phase.BLOCKED)) {
            return false;
        }
        if (snapshot.phase() == HomeEconomySession.Phase.PAUSED) {
            homeEconomy.blockWhilePaused(code, detail, false, nowMillis);
        } else {
            blockHome(code, detail, false, nowMillis);
        }
        return true;
    }

    /**
     * Starts a new acknowledgement-fenced protection loadout episode. The
     * returned generation is the capability token for every later prepare and
     * release call; a stale generation can never reopen or clear a newer one.
     */
    public long beginProtectionLoadoutEpisode() {
        if (protectionLoadoutEpisodeOpen) protectionLoadoutCleanupPending = true;
        protectionLoadoutCoordinator.invalidateActive();
        protectionLoadoutGeneration = Math.incrementExact(protectionLoadoutGeneration);
        protectionLoadoutEpisodeOpen = true;
        protectionLoadoutCleanupPending = true;
        protectionLoadoutCleanupStartedAt = Long.MIN_VALUE;
        return protectionLoadoutGeneration;
    }

    /**
     * Compatibility entry point for the current runtime. New integration should
     * call {@link #beginProtectionLoadoutEpisode()} once at protection entry and
     * use the generation-bearing overload below.
     */
    public CombatLoadoutCoordinator.Result prepareProtectionLoadout(
            TacticalCombatPolicy.LoadoutIntent intent,
            boolean defensive,
            long nowMillis) {
        if (!protectionLoadoutEpisodeOpen) beginProtectionLoadoutEpisode();
        return prepareProtectionLoadout(
                protectionLoadoutGeneration, intent, defensive, nowMillis);
    }

    /**
     * Advances at most one server-acknowledged loadout mutation. Callers must
     * withhold retreat/intercept body actuation while this returns WAITING and
     * choose an unarmed/no-shield fallback when it returns UNAVAILABLE.
     */
    public CombatLoadoutCoordinator.Result prepareProtectionLoadout(
            long episodeGeneration,
            TacticalCombatPolicy.LoadoutIntent intent,
            boolean defensive,
            long nowMillis) {
        Objects.requireNonNull(intent, "intent");
        if (!protectionLoadoutEpisodeOpen
                || episodeGeneration != protectionLoadoutGeneration) {
            return CombatLoadoutCoordinator.Result.UNAVAILABLE;
        }
        // Reconcile before reading acknowledgement counters. A semantic timeout
        // is surfaced to the coordinator as UNAVAILABLE, never as readiness.
        inventory.reconcilePendingTransaction(nowMillis);
        CombatLoadoutCoordinator.Result cleanup = drainCombatLoadoutCleanup(nowMillis);
        if (cleanup != CombatLoadoutCoordinator.Result.READY) return cleanup;
        return protectionLoadoutCoordinator.step(
                episodeGeneration,
                protectionRequirement(intent),
                defensive
                        ? CombatLoadoutCoordinator.Order.SHIELD_FIRST
                        : CombatLoadoutCoordinator.Order.WEAPON_FIRST,
                protectionLoadoutInventory,
                nowMillis);
    }

    /** Releases exactly one protection episode; stale releases are harmless. */
    public void clearProtectionLoadoutEpisode(long episodeGeneration) {
        if (!protectionLoadoutEpisodeOpen
                || episodeGeneration != protectionLoadoutGeneration) return;
        protectionLoadoutCoordinator.release(episodeGeneration);
        protectionLoadoutEpisodeOpen = false;
        protectionLoadoutCleanupPending = true;
        protectionLoadoutCleanupStartedAt = Long.MIN_VALUE;
    }

    /**
     * Acknowledgement-fences the carried equipment available when one explicit
     * attack mission starts. WAITING is the only result that withholds route
     * ownership; missing or unusable equipment returns a reduced-loadout
     * terminal result so the attack itself can still proceed.
     */
    public CombatLoadoutCoordinator.Result prepareAttackMissionLoadout(
            String missionId,
            long nowMillis) {
        inventory.reconcilePendingTransaction(nowMillis);
        String normalizedMissionId = Objects.requireNonNullElse(missionId, "").trim();
        if (!normalizedMissionId.equals(attackMissionLoadout.missionId())
                && !normalizedMissionId.equals(attackLoadoutCleanupMissionId)) {
            attackLoadoutCleanupPending = true;
            attackLoadoutCleanupStartedAt = Long.MIN_VALUE;
            attackLoadoutCleanupMissionId = normalizedMissionId;
        }
        CombatLoadoutCoordinator.Result cleanup = drainCombatLoadoutCleanup(nowMillis);
        if (cleanup != CombatLoadoutCoordinator.Result.READY) return cleanup;
        return attackMissionLoadout.step(missionId, protectionLoadoutInventory, nowMillis);
    }

    /**
     * Ends any explicit-attack loadout before a different mission takes the
     * body.  A replacement command can arrive without an intervening IDLE
     * tick, so the successor route must wait until an acknowledged inventory
     * mutation/cursor session from the old attack has been drained.
     */
    public boolean clearAttackMissionLoadout(long nowMillis) {
        inventory.reconcilePendingTransaction(nowMillis);
        invalidateAttackMissionLoadout();
        return drainCombatLoadoutCleanup(nowMillis)
                != CombatLoadoutCoordinator.Result.WAITING;
    }

    private void invalidateProtectionLoadoutEpisode() {
        if (protectionLoadoutEpisodeOpen) {
            protectionLoadoutCleanupPending = true;
            protectionLoadoutCleanupStartedAt = Long.MIN_VALUE;
        }
        protectionLoadoutCoordinator.invalidateActive();
        protectionLoadoutEpisodeOpen = false;
    }

    private void invalidateAttackMissionLoadout() {
        if (attackMissionLoadout.invalidate()) {
            attackLoadoutCleanupPending = true;
            attackLoadoutCleanupStartedAt = Long.MIN_VALUE;
        }
        attackLoadoutCleanupMissionId = "";
    }

    /**
     * Completes or rolls back the controller's cursor transfer only after its
     * preceding click is acknowledged. Until this succeeds, a newer episode is
     * fenced at WAITING and cannot inherit an old optimistic equip session.
     */
    private boolean drainProtectionLoadoutCleanup(long nowMillis) {
        if (!protectionLoadoutCleanupPending) return true;
        if (!inventory.closeHandledScreen(nowMillis)) {
            if (protectionLoadoutCleanupStartedAt == Long.MIN_VALUE) {
                protectionLoadoutCleanupStartedAt = nowMillis;
            }
            return false;
        }
        protectionLoadoutCleanupPending = false;
        protectionLoadoutCleanupStartedAt = Long.MIN_VALUE;
        return true;
    }

    private boolean drainAttackLoadoutCleanup(long nowMillis) {
        if (!attackLoadoutCleanupPending) return true;
        if (!inventory.closeHandledScreen(nowMillis)) {
            if (attackLoadoutCleanupStartedAt == Long.MIN_VALUE) {
                attackLoadoutCleanupStartedAt = nowMillis;
            }
            return false;
        }
        attackLoadoutCleanupPending = false;
        attackLoadoutCleanupStartedAt = Long.MIN_VALUE;
        return true;
    }

    private CombatLoadoutCoordinator.Result drainCombatLoadoutCleanup(long nowMillis) {
        boolean protectionReady = drainProtectionLoadoutCleanup(nowMillis);
        boolean attackReady = drainAttackLoadoutCleanup(nowMillis);
        if (protectionReady && attackReady) return CombatLoadoutCoordinator.Result.READY;
        boolean timedOut = protectionLoadoutCleanupPending
                && protectionLoadoutCleanupStartedAt != Long.MIN_VALUE
                && nowMillis - protectionLoadoutCleanupStartedAt
                >= COMBAT_LOADOUT_CLEANUP_TIMEOUT_MILLIS
                || attackLoadoutCleanupPending
                && attackLoadoutCleanupStartedAt != Long.MIN_VALUE
                && nowMillis - attackLoadoutCleanupStartedAt
                >= COMBAT_LOADOUT_CLEANUP_TIMEOUT_MILLIS;
        if (!timedOut) return CombatLoadoutCoordinator.Result.WAITING;

        // Keep an unknown cursor exactly where the player left it. The combat
        // layer falls back without the requested loadout rather than owning or
        // dropping an item it cannot return safely.
        protectionLoadoutCleanupPending = false;
        attackLoadoutCleanupPending = false;
        protectionLoadoutCleanupStartedAt = Long.MIN_VALUE;
        attackLoadoutCleanupStartedAt = Long.MIN_VALUE;
        return CombatLoadoutCoordinator.Result.UNAVAILABLE;
    }

    private static CombatLoadoutCoordinator.Requirement protectionRequirement(
            TacticalCombatPolicy.LoadoutIntent intent) {
        return switch (intent) {
            case WEAPON -> CombatLoadoutCoordinator.Requirement.WEAPON;
            case SHIELD -> CombatLoadoutCoordinator.Requirement.SHIELD;
            case WEAPON_AND_SHIELD -> CombatLoadoutCoordinator.Requirement.WEAPON_AND_SHIELD;
            case NONE, FOOD -> CombatLoadoutCoordinator.Requirement.NONE;
        };
    }

    private final class ProtectionLoadoutInventoryPort
            implements CombatLoadoutCoordinator.InventoryPort {
        @Override
        public int count(String itemId) {
            return inventory.count(itemId);
        }

        @Override
        public boolean isMainHandEquipped(String itemId) {
            if (client.player == null) return false;
            ItemStack stack = client.player.getMainHandStack();
            return !stack.isEmpty()
                    && Registries.ITEM.getId(stack.getItem()).toString().equals(itemId);
        }

        @Override
        public boolean isShieldEquippedOffhand() {
            if (client.player == null) return false;
            ItemStack stack = client.player.getOffHandStack();
            return !stack.isEmpty()
                    && Registries.ITEM.getId(stack.getItem()).toString()
                    .equals(CombatLoadoutCoordinator.SHIELD_ITEM);
        }

        @Override
        public boolean isShieldUsableOffhand() {
            if (!isShieldEquippedOffhand()) return false;
            ItemStack stack = client.player.getOffHandStack();
            return !client.player.getItemCooldownManager().isCoolingDown(stack);
        }

        @Override
        public boolean hasUnsettledMutation() {
            InventoryTransactionEngine.Diagnostics diagnostics = inventory.transactionDiagnostics();
            // A physical cursor without an Entity transaction owner is manual
            // state. Never overwrite it, but do not confuse it with an
            // acknowledgement fence and freeze combat indefinitely.
            return !diagnostics.pendingClickOwner().isBlank()
                    || !diagnostics.cursorOwner().isBlank();
        }

        @Override
        public long acknowledgedMutations() {
            return inventory.transactionDiagnostics().acknowledgedClicks();
        }

        @Override
        public long timedOutMutations() {
            return inventory.transactionDiagnostics().timedOutClicks();
        }

        @Override
        public CombatLoadoutCoordinator.EquipResult equip(
                String itemId,
                String transactionOwner,
                long nowMillis) {
            return switch (inventory.equip(itemId, transactionOwner, nowMillis)) {
                case CLICKED -> CombatLoadoutCoordinator.EquipResult.CLICKED;
                case ALREADY_DONE -> CombatLoadoutCoordinator.EquipResult.ALREADY_DONE;
                case WAITING -> CombatLoadoutCoordinator.EquipResult.WAITING;
                case ITEM_MISSING -> CombatLoadoutCoordinator.EquipResult.ITEM_MISSING;
                case SLOT_UNAVAILABLE -> CombatLoadoutCoordinator.EquipResult.SLOT_UNAVAILABLE;
                case CURSOR_NOT_EMPTY -> CombatLoadoutCoordinator.EquipResult.CURSOR_NOT_EMPTY;
            };
        }
    }

    private void neutralizePhysicalControls(String reason) {
        neutralizePhysicalControls(reason, stoppedCapacityCleanupPending() || issuedCapacityTransferCustodyPending());
    }

    private void neutralizePhysicalControls(String reason, boolean keepOwnedScreen) {
        // A BLOCKED release can precede Stop. Keep the accepted chest receipt while
        // its original typed capacity intent still owns the issued cursor/ack.
        keepOwnedScreen |= issuedCapacityTransferCustodyPending();
        capacityAimAges.clear();
        if (!keepOwnedScreen) inventory.closeHandledScreen();
        else if (!stoppedCapacityCleanupPending())
            inventory.closeHandledScreenIfCursorEmpty(System.currentTimeMillis());
        deliveries.clearAll();
        if (keepOwnedScreen) workstations.suspendAllKeepingOpenScreen();
        else workstations.suspendAll();
        groundItems.suspend(reason);
        deliveryPendingInvalidated = true;
        actionSupervisor.suspendAll(System.currentTimeMillis());
        logger.debug("Autonomy controls released: {}", reason);
    }

    private void neutralizeActionOwnerControls(String reason) {
        baritone.neutralizeActuators(reason);
        neutralizePhysicalControls(reason);
    }

    /** Gives one automatic blocked reconciliation a clean transient attempt. */
    public void prepareBlockedReconciliation(String reason) {
        actionSupervisor.clearAll();
        workstations.resetTransientRecoveryForReconciliation();
        groundItems.clear(Long.MIN_VALUE,
                Objects.requireNonNullElse(reason, "blocked mission reconciliation"));
        deliveries.clearAll();
    }

    public void cancelMission(String missionId, long epoch, String reason) throws IOException {
        cancelMission(missionId, epoch, reason, false);
    }

    public void cancelMission(String missionId, long epoch, String reason, boolean ownerStop) throws IOException {
        releaseControls(reason, lastClientTick, ownerStop);
        deliveryPendingInvalidated = false;
        actionSupervisor.clearAll();
        if (!stoppedCapacityCleanupPending()) workstations.clearAll();
        groundItems.clear(epoch, reason);
        retireCancelledMissionPlan(missionId, reason);
        baritone.cancel(epoch, reason);
    }

    /** Retire one journaled objective without touching another owner's live controls. */
    public void retireCancelledMissionPlan(String missionId, String reason) throws IOException {
        Optional<TaskPlan> missionPlan = plans.restore(missionId);
        if (missionPlan.isPresent() && stoppedCapacityCleanupFrame(missionPlan.orElseThrow()).isPresent()) return;
        if (missionPlan.isPresent() && missionPlan.orElseThrow().state() == TaskPlanState.OPEN) {
            plans.clear(missionId, reason, System.currentTimeMillis());
        }
        retirePlanIfTerminal(
                missionId, "mission-journal:cancelled-before-plan-cleanup", System.currentTimeMillis());
        String recovery = recoveryPlanByMission.remove(missionId);
        if (recovery != null) {
            Optional<TaskPlan> recoveryPlan = plans.restore(recovery);
            if (recoveryPlan.isPresent()
                    && recoveryPlan.orElseThrow().state() == TaskPlanState.OPEN) {
                plans.clear(recovery, reason, System.currentTimeMillis());
            }
            retirePlanIfTerminal(
                    recovery,
                    "mission-journal:cancelled-parent:" + missionId,
                    System.currentTimeMillis());
        }
    }

    private static Optional<PlanFrame> stoppedCapacityCleanupFrame(TaskPlan plan) {
        if (plan.state() != TaskPlanState.OPEN) return Optional.empty();
        return plan.frames().stream().filter(frame -> frame.kind().equals("root.inventory_capacity")
                && frame.parameters().containsKey(STOP_CAPACITY_CLEANUP_KEY)).findFirst();
    }

    private boolean stoppedCapacityCleanupPending() {
        return plans.plans().stream().anyMatch(plan -> stoppedCapacityCleanupFrame(plan).isPresent());
    }

    private boolean issuedCapacityTransferCustodyPending() {
        for (TaskPlan plan : plans.plans()) {
            if (plan.state() != TaskPlanState.OPEN) continue;
            var current = plan.currentFrame();
            if (current.isEmpty() || !current.orElseThrow().kind().equals("root.inventory_capacity")) continue;
            String encoded = current.orElseThrow().parameters().get(MANAGED_FARM_TRANSFER_KEY);
            if (encoded == null) continue;
            var pending = HomeTransferIntentCodec.decode(encoded);
            if (pending.direction() == HomeStockPolicy.Direction.DEPOSIT
                    && (pending.id().equals(current.orElseThrow().parameters().get(CAPACITY_TRANSFER_REOBSERVE_KEY))
                    || inventory.hasExactHomeTransferCustody(homeTransferPlan(pending), homeTransferOwner(pending)))) return true;
        }
        return false;
    }

    /** Borrowed-body handoff settles issued capacity cargo without cancelling its active objective. */
    private HomePreemptionOutcome drainActiveCapacityTransfer(long nowMillis) throws IOException {
        for (TaskPlan plan : plans.plans()) {
            if (plan.state() != TaskPlanState.OPEN) continue;
            var current = plan.currentFrame();
            if (current.isEmpty() || !current.orElseThrow().kind().equals("root.inventory_capacity")) continue;
            PlanFrame frame = current.orElseThrow();
            String encoded = frame.parameters().get(MANAGED_FARM_TRANSFER_KEY);
            if (encoded == null || frame.parameters().containsKey(STOP_CAPACITY_CLEANUP_KEY)) continue;
            var pending = HomeTransferIntentCodec.decode(encoded);
            var diagnostics = inventory.transactionDiagnostics();
            if (!frame.parameters().containsKey(CAPACITY_TRANSFER_REOBSERVE_KEY)
                    && inventory.cursorEmpty() && diagnostics.cursorOwner().isBlank()
                    && diagnostics.pendingClickOwner().isBlank()) continue;
            var access = managedFarmAccessState(plan.missionId(), frame, managedFarmBoundStorageLedgers(frame),
                    integer(frame.parameters(), MANAGED_FARM_STORAGE_INDEX, 0));
            if (pending.direction() != HomeStockPolicy.Direction.DEPOSIT
                    || homeMissionSupplyExactIdentity(plan.missionId(), access)
                    .filter(pending.containerIdentity()::equals).isEmpty())
                return HomePreemptionOutcome.blocked("active capacity chest identity changed; zero handoff clicks");
            if (!frame.parameters().containsKey(CAPACITY_TRANSFER_REOBSERVE_KEY)) {
                if (!inventory.hasExactHomeTransferCustody(homeTransferPlan(pending), homeTransferOwner(pending)))
                    return HomePreemptionOutcome.blocked("active capacity cursor has no exact custody; zero handoff clicks");
                var checkpoint = new LinkedHashMap<>(frame.parameters());
                checkpoint.put(CAPACITY_TRANSFER_REOBSERVE_KEY, pending.id());
                plans.checkpointCurrent(plan.missionId(), checkpoint,
                        "settle issued capacity cargo before temporary body handoff", nowMillis);
                return HomePreemptionOutcome.blocked("capacity handoff checkpointed; waiting for exact settlement");
            }
            Outcome settled = reobserveChangedCapacityDeposit(plan.missionId(), frame, pending, nowMillis);
            if (settled.state() != State.CONTINUE_PLAN)
                return HomePreemptionOutcome.blocked(settled.detail());
            if (inventory.closeHandledScreenIfCursorEmpty(nowMillis) != ClientInventoryController.SafeCloseResult.CLOSED)
                return HomePreemptionOutcome.blocked("waiting for acknowledged capacity safe close before body handoff");
            return HomePreemptionOutcome.terminal("active capacity cargo settled; original objective retained");
        }
        return null;
    }

    /** Called only while the runtime Stop latch is held; a failed save authorizes no click. */
    public void prepareStoppedCapacityTransfers(long nowMillis) throws IOException {
        for (TaskPlan plan : plans.plans()) prepareStoppedCapacityTransfer(plan.missionId(), nowMillis);
    }

    /** Pin existing typed intent before Stop invalidates workstation/body ownership. No click is issued. */
    public void prepareStoppedCapacityTransfer(String missionId, long nowMillis) throws IOException {
        var restored = plans.restore(missionId);
        if (restored.isEmpty() || restored.orElseThrow().state() != TaskPlanState.OPEN) return;
        TaskPlan plan = restored.orElseThrow();
        if (stoppedCapacityCleanupFrame(plan).isPresent()) return;
        var current = plan.currentFrame();
        if (current.isEmpty() || !current.orElseThrow().kind().equals("root.inventory_capacity")) return;
        PlanFrame frame = current.orElseThrow();
        String encoded = frame.parameters().get(MANAGED_FARM_TRANSFER_KEY);
        if (encoded == null) return;
        HomeEconomySession.PendingTransfer pending = HomeTransferIntentCodec.decode(encoded);
        if (pending.direction() != HomeStockPolicy.Direction.DEPOSIT
                || !inventory.hasExactHomeTransferCustody(homeTransferPlan(pending), homeTransferOwner(pending))) return;
        // Retention grants no action: if the accepted chest receipt is no longer
        // exact, the suffix below remains blocked with this original debt intact.
        var parameters = new LinkedHashMap<>(frame.parameters());
        parameters.put(STOP_CAPACITY_CLEANUP_KEY, pending.id());
        plans.checkpointFrames(missionId, Map.of(frame.id(), parameters),
                "owner cancelled work; retaining only exact capacity cursor settlement", nowMillis);
    }

    /** Advances only held cargo from the cancelled frame; never runs capacity/deposit planning. */
    public HomePreemptionOutcome drainStoppedCapacityTransfer(long nowMillis) throws IOException {
        for (TaskPlan plan : plans.plans()) {
            var retained = stoppedCapacityCleanupFrame(plan);
            if (retained.isEmpty()) continue;
            PlanFrame frame = retained.orElseThrow();
            var pending = HomeTransferIntentCodec.decode(frame.parameters().get(MANAGED_FARM_TRANSFER_KEY));
            if (!pending.id().equals(frame.parameters().get(STOP_CAPACITY_CLEANUP_KEY))
                    || pending.direction() != HomeStockPolicy.Direction.DEPOSIT)
                return HomePreemptionOutcome.blocked("cancelled capacity intent identity changed; zero cleanup clicks");
            inventory.reconcilePendingTransaction(nowMillis);
            var diagnostics = inventory.transactionDiagnostics();
            if (!diagnostics.pendingClickOwner().isBlank())
                return HomePreemptionOutcome.blocked("waiting for cancelled capacity inventory acknowledgement");
            if (!inventory.cursorEmpty() || !diagnostics.cursorOwner().isBlank()) {
                var access = managedFarmAccessState(plan.missionId(), frame, managedFarmBoundStorageLedgers(frame),
                        integer(frame.parameters(), MANAGED_FARM_STORAGE_INDEX, 0));
                if (homeMissionSupplyExactIdentity(plan.missionId(), access)
                        .filter(pending.containerIdentity()::equals).isEmpty())
                    return HomePreemptionOutcome.blocked("cancelled capacity chest identity changed; zero cleanup clicks");
                var settled = inventory.settleChangedHomeTransfer(homeTransferPlan(pending), homeTransferOwner(pending), nowMillis);
                if (settled != ClientInventoryController.HomeTransferSettlement.SETTLED)
                    return HomePreemptionOutcome.blocked("settling cancelled capacity cursor: " + settled);
            }
            if (inventory.closeHandledScreenIfCursorEmpty(nowMillis) != ClientInventoryController.SafeCloseResult.CLOSED)
                return HomePreemptionOutcome.blocked("waiting for cancelled capacity safe container close");
            var parameters = new LinkedHashMap<>(frame.parameters());
            parameters.remove(STOP_CAPACITY_CLEANUP_KEY);
            plans.checkpointFrames(plan.missionId(), Map.of(frame.id(), parameters),
                    "cancelled capacity cursor settled and acknowledged; no work resumed", nowMillis);
            retireCancelledMissionPlan(plan.missionId(), "stopped by owner; exact cursor cleanup settled");
            workstations.clearAll();
            return HomePreemptionOutcome.terminal("cancelled capacity cargo settled; work remains cancelled");
        }
        return HomePreemptionOutcome.terminal("no cancelled capacity cargo remains");
    }

    private Outcome tickPlan(
            String planId,
            Mission mission,
            ControlLease lease,
            long clientTick,
            long nowMillis,
            boolean completesMission) throws IOException {
        for (int transition = 0; transition < 4; transition++) {
            TaskPlan plan = plans.restore(planId)
                    .orElseThrow(() -> new IllegalStateException("Missing task plan " + planId));
            if (plan.state() == TaskPlanState.COMPLETED) {
                return completesMission
                        ? Outcome.complete("completed hierarchical " + mission.kind() + " plan")
                        : Outcome.planComplete(plan.detail().isBlank()
                                ? "completed inserted prerequisite plan"
                                : plan.detail());
            }
            if (plan.state() != TaskPlanState.OPEN) {
                return Outcome.blocked("task plan is " + plan.state().name().toLowerCase(Locale.ROOT));
            }
            Outcome universalGate = reconcileCommittedUniversalProgram(
                    planId, plan, nowMillis);
            if (universalGate != null) {
                if (universalGate.state() == State.CONTINUE_PLAN) continue;
                return universalGate;
            }
            PlanFrame frame = plan.currentFrame().orElseThrow();
            if (frame.state() == PlanFrameState.PENDING) {
                plans.activate(planId, frame.parameters().getOrDefault("description", frame.displayName()), nowMillis);
                continue;
            }
            if (frame.kind().startsWith("root.")) {
                Outcome root = tickRoot(planId, frame, mission, lease, nowMillis);
                if (root.state() == State.CONTINUE_PLAN) continue;
                if (root.failureCause() == BaritonePort.FailureCause.INVENTORY_CAPACITY)
                    return insertCapacityRecovery(planId, frame, nowMillis);
                return root;
            }
            ExecutionKernel.Decision action = executionKernel.request(
                    lease,
                    actionOwner(planId, frame),
                    planId + ':' + frame.id(),
                    clientTick,
                    nowMillis);
            if (action.state() == ExecutionKernel.State.NEUTRALIZE
                    || action.state() == ExecutionKernel.State.PARENT_INVALID) {
                neutralizeActionOwnerControls(action.detail());
                action.transition().ifPresent(ticket ->
                        executionKernel.confirmNeutralized(ticket, clientTick));
                return Outcome.running(action.detail(), Double.NaN, 0L, false);
            }
            if (action.state() == ExecutionKernel.State.WAIT_NEXT_TICK) {
                return Outcome.running(action.detail(), Double.NaN, 0L, false);
            }
            ActionLease actionLease = action.lease().orElseThrow(() ->
                    new IllegalStateException("execution kernel did not grant an action capability"));
            // Every leaf is synchronous on the Minecraft client thread. This is
            // therefore the final authority check immediately before any leaf
            // can route, click, break, attack, or mutate inventory.
            if (idleStockCycle != null && idleStockCycle.undergroundOnly && isHomeMaintenancePlan(plan)
                    && !idleUndergroundLeafAllowed(frame)) {
                return Outcome.blocked("Automatic underground job lost its proved local site or supplied prerequisites; no surface action issued");
            }
            executionKernel.requireValid(actionLease, lease, clientTick, nowMillis);
            baritone.bindMovementAction(
                    executionKernel, lease, actionLease, clientTick, nowMillis);
            Outcome leaf = tickLeaf(planId, frame, lease, actionLease, nowMillis);
            if (leaf.state() == State.CONTINUE_PLAN) continue;
            if (leaf.state() == State.BLOCKED && isCommittedUniversalAction(frame)
                    && UnavailableHarvestSources.canRecover(
                            frame.parameters().get("worldSourceKind"), leaf.failureCause())) {
                return invalidateUniversalProgramAndRebase(
                        planId, frame, DivergenceCause.VERIFIED_ACTION_FAILURE,
                        leaf.detail() + "; retained absent harvest source " + frame.target(), nowMillis, true);
            }
            return recoverCommittedActionFailure(planId, frame, leaf, nowMillis);
        }
        return Outcome.running("advancing prerequisite stack", Double.NaN, 0, false);
    }

    /** Keeps the exact terminal source result attached to its child instead of rebuilding it. */
    private Outcome recoverCommittedActionFailure(
            String planId, PlanFrame frame, Outcome leaf, long nowMillis) throws IOException {
        if (leaf.failureCause() == BaritonePort.FailureCause.INVENTORY_CAPACITY)
            return insertCapacityRecovery(planId, frame, nowMillis);
        if (leaf.state() == State.BLOCKED
                && isCommittedUniversalAction(frame)
                && CommittedActionFailurePolicy.shouldRecompile(
                        // Filling also exhausts a bounded source query. Unlike mining/harvest it
                        // has no worldSourceKind metadata, but recompiling cannot reveal water.
                        frame.kind().equals("fill_water") || frame.parameters().containsKey("worldSourceKind"),
                        leaf.failureCause())) {
            return invalidateUniversalProgramAndRebase(
                    planId, frame, DivergenceCause.VERIFIED_ACTION_FAILURE, leaf.detail(), nowMillis);
        }
        return leaf;
    }

    private ActionOwner actionOwner(String planId, PlanFrame frame) {
        return switch (frame.kind()) {
            case "mine" -> {
                MiningSession session = miningSessions.get(miningSessionKey(planId, frame));
                yield session != null && session.segment() == MiningSession.Segment.PICKUP
                        ? ActionOwner.GROUND_PICKUP
                        : ActionOwner.BARITONE_ROUTE;
            }
            case "goto", "build" -> ActionOwner.BARITONE_ROUTE;
            case "recover_death_drops" -> ActionOwner.GROUND_PICKUP;
            case "hunt" -> frame.parameters().containsKey("dropCollectionStartedAt")
                    ? ActionOwner.GROUND_PICKUP
                    : ActionOwner.HUNT_COMBAT;
            case "craft", "smelt", "fill_water" -> ActionOwner.WORKSTATION_INTERACTION;
            case "equip" -> ActionOwner.INVENTORY_TRANSACTION;
            case "deliver" -> ActionOwner.DELIVERY_HANDOFF;
            default -> throw new IllegalArgumentException(
                    "unsupported prerequisite action " + frame.kind());
        };
    }

    private Outcome insertCapacityRecovery(String planId, PlanFrame parent, long now) throws IOException {
        boolean building = plans.restore(planId).map(p -> p.frames().stream()
                .anyMatch(f -> f.kind().equals("root.build"))).orElse(false);
        return insertCapacityRecoveryForSlots(planId, parent, now, building
                ? dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.minimumEmptySlotsForBatch() : 1);
    }

    private Outcome insertCapacityRecoveryForSlots(String planId, PlanFrame parent, long now,
            int receivingSlots) throws IOException {
        var plan = plans.restore(planId);
        boolean building = plan.map(p -> p.frames().stream().anyMatch(f -> f.kind().equals("root.build")))
                .orElse(false);
        // A nested supply batch still belongs to the house. Retain both finished
        // building cargo and inputs of its already committed recipe program when
        // the existing capacity owner deposits surplus at Home.
        var physical = inventoryViewWithoutStations();
        var retained = new LinkedHashMap<String, Integer>();
        if (building) retained.putAll(dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy
                .carriedProjectMaterials(blueprintProjects.remainingMaterials(client), physical,
                        blueprintProjects.needsSupportReserve()));
        plan.flatMap(AutonomyExecutor::foodObjectiveRoot).ifPresent(food ->
                FoodFamilyPolicy.retainedAcquisitionInputs(Math.toIntExact(food.count()), physical)
                        .forEach((item, count) -> retained.merge(item, count, Math::max)));
        for (var ancestor : plan.orElseThrow().frames()) {
            String encoded = ancestor.parameters().get(UNIVERSAL_PROGRAM_KEY);
            if (encoded == null) continue;
            var program = UniversalProgramCodec.decode(encoded);
            if (program.status() != Status.ACTIVE) continue;
            for (var commitment : PlanCommitmentPolicy.derive(program.remainingActions())) {
                int carried = Math.min(commitment.count(), physical.getOrDefault(commitment.item(), 0));
                if (carried > 0) retained.merge(commitment.item(), carried, Math::max);
            }
        }
        return insertCapacityRecovery(planId, parent, now, receivingSlots, retained);
    }

    /** Keeps upkeep inside the interrupted productive job, with no idle enablement dependency. */
    private Outcome tickPersonalWorkMaintenance(String planId, PlanFrame frame,
            ControlLease lease, long now) throws IOException {
        var plan = plans.restore(planId).orElseThrow();
        boolean existingOwner = plan.frames().stream().anyMatch(candidate ->
                "true".equals(candidate.parameters().get("personalMaintenance"))
                        || isHomeMaintenance(candidate) || candidate.kind().startsWith("root.food.")
                        || Set.of("root.inventory_capacity", "root.mining_access",
                                "root.give", "root.batch.give").contains(candidate.kind()));
        boolean gearOwnsTools = plan.frames().stream().anyMatch(candidate -> candidate.kind().equals("root.gear"));
        if (existingOwner || client.player == null || foodConsumptionPending.getAsBoolean()
                || client.player.isUsingItem() || client.player.isSleeping()
                || !inventory.transactionDiagnostics().pendingClickOwner().isBlank()
                || !inventory.transactionDiagnostics().cursorOwner().isBlank()) return null;
        // A settled Gear root/mine may yield for food, never an in-flight furnace
        // recipe or its acknowledged-but-uncommitted fuel capability.
        if (gearOwnsTools && (client.player.currentScreenHandler instanceof net.minecraft.screen.AbstractFurnaceScreenHandler
                || !committedFurnaceFuelGrants.isEmpty())) return null;
        var allocation = PersonalSuppliesPolicy.allocate(inventory.personalSupplies().playerStacks(),
                retainedPersonalProfile(), personalSupplyClaims(), Set.of());
        boolean woodWork = HarvestToolAccessPolicy.isWoodTarget(frame.parameters().getOrDefault(
                "blockAlternatives", frame.parameters().getOrDefault("blocks", "")));
        var goal = PersonalSuppliesPolicy.maintenanceGoal(frame.kind(), woodWork, false, allocation,
                inventory.homePlayerCounts(), idleWorkingTools.tools());
        if (goal.isEmpty()) return null;
        if (gearOwnsTools && !goal.containsKey("food")) return null;
        if (inventory.closeHandledScreenIfCursorEmpty(now) != ClientInventoryController.SafeCloseResult.CLOSED)
            return Outcome.running("settling inventory before personal upkeep", Double.NaN, 0, false);
        var need = goal.entrySet().iterator().next();
        var child = new LinkedHashMap<String, String>();
        child.put("personalMaintenance", "true");
        child.put("description", "restore personal " + need.getKey() + ", then resume " + frame.displayName());
        var protectedItems = new LinkedHashMap<String, Integer>();
        allocation.stacks().stream().filter(stack -> stack.transactionCount() > 0).forEach(stack ->
                protectedItems.merge(simple(stack.stack().item()), stack.transactionCount(), Math::addExact));
        if (plan.frames().stream().anyMatch(ancestor -> ancestor.kind().equals("root.build"))) {
            dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.carriedProjectMaterials(
                    blueprintProjects.remainingMaterials(client), inventoryViewWithoutStations(),
                    blueprintProjects.needsSupportReserve()).forEach((item, count) ->
                            protectedItems.merge(simple(item), count, Math::max));
        }
        Map<String, Integer> physical = inventoryViewWithoutStations();
        for (var ancestor : plan.frames()) {
            String encoded = ancestor.parameters().get(UNIVERSAL_PROGRAM_KEY);
            if (encoded == null) continue;
            var program = UniversalProgramCodec.decode(encoded);
            if (program.status() != Status.ACTIVE) continue;
            for (var commitment : PlanCommitmentPolicy.derive(program.remainingActions())) {
                String item = simple(commitment.item());
                int carried = Math.min(commitment.count(), physical.getOrDefault(commitment.item(), 0));
                if (carried > 0) protectedItems.merge(item, carried, Math::max);
            }
        }
        child.put("personalMaintenanceProtectedItems",
                dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.encode(protectedItems));
        copyHomeBinding(frame.parameters(), child);
        child.remove(HOME_MISSION_SUPPLY_RETAINED_FOOD); // goal already contains its one personal floor
        // Root Gear has no active production child to abandon. Productive leaves
        // retain their existing cancellation/suspend boundary before this child.
        if (!frame.kind().equals("root.gear"))
            baritone.cancel(lease.epoch(), "productive work yields to its required personal supplies");
        actionSupervisor.suspend(frame.id(), now);
        plans.pushPrerequisite(planId, new PlanFrame.Spec(need.getKey().equals("food")
                        ? "root.food.acquire" : "root.acquire", need.getKey(), need.getValue(), child),
                "same objective retained while necessary personal supplies recover", now);
        return Outcome.continuePlan();
    }

    private Outcome insertCapacityRecovery(String planId, PlanFrame parent, long now,
            int requiredEmptySlots, Map<String,Integer> protectedItems) throws IOException {
        if (parent.kind().equals("root.inventory_capacity"))
            return Outcome.blocked("No receiving room: all carried items are needed and registered storage cannot accept surplus",
                    BaritonePort.FailureCause.INVENTORY_CAPACITY);
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("description", "Make receiving room, then resume " + parent.displayName());
        parameters.put("requiredEmptySlots", Integer.toString(requiredEmptySlots));
        if (!protectedItems.isEmpty()) parameters.put("capacityProtectedItems",
                dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.encode(protectedItems));
        if (homeEconomy != null && settingsLoadState.authoritative()) {
            var snapshot = homeEconomy.snapshot();
            var home = snapshot.home();
            if (home != null && !snapshot.storageRegistrations().isEmpty()) {
                parameters.put(MANAGED_FARM_HOME_GENERATION, Long.toString(snapshot.generation()));
                parameters.put(MANAGED_FARM_HOME_FINGERPRINT, home.fingerprint());
                parameters.put(MANAGED_FARM_HOME_DIMENSION, home.dimension());
                parameters.put(MANAGED_FARM_HOME_X, Integer.toString(home.x()));
                parameters.put(MANAGED_FARM_HOME_Y, Integer.toString(home.y()));
                parameters.put(MANAGED_FARM_HOME_Z, Integer.toString(home.z()));
                parameters.put(MANAGED_FARM_STORAGE_LEDGERS, String.join(",", managedFarmStorageLedgers(snapshot)));
                parameters.put(MANAGED_FARM_STORAGE_INDEX, "0");
            }
        }
        plans.pushPrerequisite(planId, new PlanFrame.Spec("root.inventory_capacity", parent.target(), 1, parameters),
                "recover actual receiving room without replacing the interrupted objective", now);
        return Outcome.continuePlan();
    }

    private InventoryCapacityPolicy.Candidates capacityCandidates(PlanFrame frame) {
        var settings = tidy.settings();
        boolean fuelNeeded = !committedFurnaceFuelGrants.isEmpty()
                || reservations.reservations().values().stream().anyMatch(r -> r.purpose() == Purpose.FUEL);
        var claims = new ArrayList<>(personalSupplyClaims());
        String protectedItems = frame.parameters().get("capacityProtectedItems");
        if (protectedItems != null) dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.decode(protectedItems)
                .forEach((item,count) -> {
                    int claimed = claims.stream().filter(r -> r.item().equals(item))
                            .mapToInt(InventoryReservationLedger.Reservation::count).sum();
                    if (count > claimed) claims.add(new InventoryReservationLedger.Reservation(
                            frame.id() + ":capacity-cargo:" + item, frame.id(), item, count - claimed,
                            Purpose.PLAN_COMMITMENT, "Retain carried materials for the interrupted blueprint"));
                });
        return InventoryCapacityPolicy.select(inventory.personalSupplies().playerStacks(), claims,
                settings.junkItems(), settings.keepItems(), fuelNeeded);
    }

    private boolean capacityRoomReady(PlanFrame frame) {
        int emptySlots = 0;
        for (ItemStack stack : client.player.getInventory().getMainStacks()) if (stack.isEmpty()) emptySlots++;
        return InventoryCapacityPolicy.hasReceivingRoom(emptySlots,
                integer(frame.parameters(), "requiredEmptySlots", 1));
    }

    /** A prerequisite on the same durable stack, using the existing drop/chest engines. */
    private Outcome tickInventoryCapacity(String planId, PlanFrame frame, ControlLease lease, long now) throws IOException {
        String owner = planId + ":capacity:" + frame.id();
        var parameters = new LinkedHashMap<>(frame.parameters());
        String pending = parameters.get(MANAGED_FARM_TRANSFER_KEY);
        String encodedDrop = parameters.get("capacityDropV1");
        var candidates = capacityCandidates(frame);
        // Once this job has travelled to registered storage, finish compatible
        // surplus in that same open chest instead of returning after its first slot.
        boolean batchingAtChest = parameters.containsKey("capacityStorageStarted")
                && inventory.homeChestSnapshot(candidates.protectedCounts()).open();
        if (pending == null && encodedDrop == null && capacityRoomReady(frame) && !batchingAtChest) {
            if (inventory.closeHandledScreenIfCursorEmpty(now) != ClientInventoryController.SafeCloseResult.CLOSED)
                return Outcome.running("settling inventory before resuming the original mission", Double.NaN, 0, false);
            capacityDrops.remove(owner); capacityAimAges.remove(owner);
            plans.completeCurrent(planId, "receiving room verified; resuming the unchanged objective", now);
            return Outcome.continuePlan();
        }
        if (pending == null && !parameters.containsKey("capacityStorageStarted")) {
            var action = executionKernel.request(lease, ActionOwner.INVENTORY_TRANSACTION,
                    owner, lastClientTick, now);
            if (action.state() == ExecutionKernel.State.NEUTRALIZE || action.state() == ExecutionKernel.State.PARENT_INVALID) {
                neutralizeActionOwnerControls(action.detail());
                action.transition().ifPresent(ticket -> executionKernel.confirmNeutralized(ticket, lastClientTick));
                return Outcome.running(action.detail(), Double.NaN, 0, false);
            }
            if (action.lease().isEmpty()) return Outcome.running("arming receiving-room inventory owner", Double.NaN, 0, false);
            executionKernel.requireValid(action.lease().orElseThrow(), lease, lastClientTick, now);
            if (inventory.closeHandledScreenIfCursorEmpty(now) != ClientInventoryController.SafeCloseResult.CLOSED)
                return Outcome.running("settling the current screen before making room", Double.NaN, 0, false);
            var geometry = new MinecraftTidyDropGeometry(client);
            var aim = geometry.capacityAim(tidy.worldId(), protectedAreas);
            if (encodedDrop == null && aim.isPresent() && !candidates.junk().isEmpty()) {
                var selected = candidates.junk().getFirst();
                parameters.put("capacityDropV1", new com.google.gson.Gson().toJson(selected));
                parameters.put("capacityDropOwner", owner + ":" + now);
                plans.checkpointCurrent(planId, parameters, "prepared exact disposable stack to free one receiving slot", now);
                return Outcome.continuePlan();
            }
            if (encodedDrop != null) {
                boolean issued = "true".equals(parameters.get("capacityDropIssued"));
                if (issued && !capacityDrops.containsKey(owner)) {
                    // Restart loses the live click receipt. Never replay the saved THROW;
                    // verify receiving room, or use a fresh storage action instead.
                    parameters.remove("capacityDropV1"); parameters.remove("capacityDropOwner");
                    parameters.remove("capacityDropIssued");
                    if (!capacityRoomReady(frame)) parameters.put("capacityStorageStarted", "true");
                    plans.checkpointCurrent(planId, parameters, "reconciled pre-restart disposal without replaying its click", now);
                    return Outcome.continuePlan();
                }
                var selection = capacityDrops.computeIfAbsent(owner, ignored -> new com.google.gson.Gson().fromJson(
                        encodedDrop, PersonalSuppliesPolicy.Selection.class));
                boolean stillDisposable = candidates.junk().stream().anyMatch(selection::equals);
                // Once issued, only the exact removal receipt may finish this selection.
                if (!issued && (!stillDisposable || aim.isEmpty())) {
                    parameters.remove("capacityDropV1"); parameters.remove("capacityDropOwner");
                    capacityDrops.remove(owner); capacityAimAges.remove(owner);
                    plans.checkpointCurrent(planId, parameters, "rechecking changed disposal circumstances", now);
                    return Outcome.continuePlan();
                }
                if (!issued && aim.isPresent()) {
                    if (Float.compare(client.player.getYaw(), aim.orElseThrow().yaw()) != 0
                            || Float.compare(client.player.getPitch(), aim.orElseThrow().pitch()) != 0)
                        capacityAimAges.remove(owner);
                    client.player.setYaw(aim.orElseThrow().yaw()); client.player.setPitch(aim.orElseThrow().pitch());
                    Integer age = capacityAimAges.putIfAbsent(owner, client.player.age);
                    if (age == null || client.player.age <= age)
                        return Outcome.running("aiming disposable junk beyond pickup reach", Double.NaN, 0, false);
                }
                var result = issued
                        ? inventory.observeExactSelectionDrop(parameters.get("capacityDropOwner"), selection, true, now)
                        : inventory.dropExactSelection(parameters.get("capacityDropOwner"), selection, true, now);
                if (result.state() == dev.entity.client.autonomy.inventory.ExactCleanupDrop.State.ISSUED) {
                    parameters.put("capacityDropIssued", "true");
                    plans.checkpointCurrent(planId, parameters, "issued one exact junk-stack throw; awaiting physical removal", now);
                } else if (result.state() == dev.entity.client.autonomy.inventory.ExactCleanupDrop.State.COMPLETE
                        || result.state() == dev.entity.client.autonomy.inventory.ExactCleanupDrop.State.STALE) {
                    // A completed receipt may be followed by a fresh disposable stack
                    // until the caller's slot budget is met. Never replay a stale selection.
                    parameters.remove("capacityDropV1"); parameters.remove("capacityDropOwner");
                    parameters.remove("capacityDropIssued");
                    if (result.state() == dev.entity.client.autonomy.inventory.ExactCleanupDrop.State.STALE
                            && !capacityRoomReady(frame)) parameters.put("capacityStorageStarted", "true");
                    capacityDrops.remove(owner); capacityAimAges.remove(owner);
                    plans.checkpointCurrent(planId, parameters, "observed disposal outcome; checking receiving room", now);
                } else if (result.state() != dev.entity.client.autonomy.inventory.ExactCleanupDrop.State.WAITING) {
                    return Outcome.blocked("Receiving-room discard needs reconciliation: " + result.detail(),
                            BaritonePort.FailureCause.INVENTORY_CAPACITY);
                }
                return Outcome.running("making room: " + result.detail(), Double.NaN, result.observedDropped(), false);
            }
            parameters.put("capacityStorageStarted", "true");
            plans.checkpointCurrent(planId, parameters, "store useful surplus in registered Home storage to make room", now);
            return Outcome.continuePlan();
        }
        List<String> ledgers = managedFarmBoundStorageLedgers(frame);
        String bindingFailure = managedFarmHomeBindingFailure(frame, ledgers);
        if (!bindingFailure.isBlank()) return Outcome.blocked(
                "No safely disposable carried stack or usable registered storage: " + bindingFailure,
                BaritonePort.FailureCause.INVENTORY_CAPACITY);
        int index = integer(frame.parameters(), MANAGED_FARM_STORAGE_INDEX, 0);
        if (index >= ledgers.size()) return Outcome.blocked(
                "Registered storage cannot accept the remaining surplus; useful kit and mission items retained",
                BaritonePort.FailureCause.INVENTORY_CAPACITY);
        var access = managedFarmAccessState(planId, frame, ledgers, index);
        var intent = pending == null ? null : HomeTransferIntentCodec.decode(pending);
        var opened = ensureHomeMissionSupplyChestOpen(planId, access,
                HomeEconomySession.HomeAnchor.at(access.dimension(), access.homeX(), access.homeY(), access.homeZ()),
                lease, now, intent == null ? null : intent.containerIdentity());
        if (opened != null) {
            if (opened.state() == State.BLOCKED && intent == null)
                return advanceManagedFarmStorage(planId, frame, access, index, "trying the next registered storage for surplus", now);
            return opened;
        }
        if (intent != null) return tickManagedFarmPendingDeposit(planId, frame, access, intent, candidates.protectedCounts(), now);
        for (var item : new java.util.TreeMap<>(candidates.depositable()).entrySet()) {
            var transfer = inventory.planFarmEarnedHomeDeposit(item.getKey(), item.getValue(), candidates.protectedCounts());
            if (transfer.isEmpty()) continue;
            var identity = homeMissionSupplyExactIdentity(planId, access);
            if (identity.isEmpty()) return Outcome.running("verifying exact storage before deposit", Double.NaN, 0, false);
            var plan = transfer.orElseThrow();
            var prepared = new HomeEconomySession.PendingTransfer(owner + ":" + now,
                    HomeStockPolicy.Direction.DEPOSIT, "capacity_surplus", item.getKey(), plan.count(), plan.syncId(),
                    plan.sourceSlot(), plan.destinationSlot(), plan.destinationCountBefore(), plan.playerCountBefore(),
                    plan.chestCountBefore(), now, identity.orElseThrow());
            parameters.put(MANAGED_FARM_TRANSFER_KEY, HomeTransferIntentCodec.encode(prepared));
            plans.checkpointCurrent(planId, parameters, "prepared exact surplus deposit before its first click", now);
            return Outcome.continuePlan();
        }
        if (capacityRoomReady(frame)) {
            if (inventory.closeHandledScreenIfCursorEmpty(now) != ClientInventoryController.SafeCloseResult.CLOSED)
                return Outcome.running("settling batched Home surplus before resuming work", Double.NaN, 0, false);
            capacityDrops.remove(owner); capacityAimAges.remove(owner);
            plans.completeCurrent(planId, "receiving room and compatible Home surplus settled; same objective resumes", now);
            return Outcome.continuePlan();
        }
        return advanceManagedFarmStorage(planId, frame, access, index, "checking the next registered chest for receiving room", now);
    }

    private Outcome tickRoot(
            String planId,
            PlanFrame frame,
            Mission mission,
            ControlLease lease,
            long nowMillis) throws IOException {
        if (frame.kind().equals("root.inventory_capacity"))
            return tickInventoryCapacity(planId, frame, lease, nowMillis);
        Outcome deathDrops = tickDeathDropRecoveryRoot(planId, frame, nowMillis);
        if (deathDrops != null) return deathDrops;
        Outcome interruptedHandoff = tickInterruptedHandoffRoot(planId, frame, nowMillis);
        if (interruptedHandoff != null) return interruptedHandoff;
        Outcome flowerSearch = tickPendingFlowerSearch(planId, frame, nowMillis);
        if (flowerSearch != null) return flowerSearch;
        Outcome homeSupply = tickTopRootHomeMissionSupply(
                planId, frame, mission, lease, nowMillis);
        if (homeSupply != null) return homeSupply;
        Outcome supplies = tickDeliverySuppliesRoot(planId, frame, nowMillis);
        if (supplies != null) return supplies;
        return switch (frame.kind()) {
            case "root.batch.acquire", "root.batch.give", "root.batch.bring" ->
                    tickBatchRoot(planId, frame, nowMillis);
            case "root.food.acquire", "root.food.give", "root.food.bring" ->
                    tickFoodRoot(planId, frame, nowMillis);
            case "root.acquire", "root.restock" -> planResourceRequest(
                    planId,
                    frame,
                    ResourcePlanner.Request.acquire(frame.target(), Math.toIntExact(frame.count())),
                    nowMillis,
                    false);
            case "root.death_recovery" -> {
                MissionDeathRecoveryPolicy.TerminalResult result =
                        MissionDeathRecoveryPolicy.restoreTerminalResult(frame.parameters());
                String terminal = MissionDeathRecoveryPolicy.resumeDetail(
                        frame.parameters().getOrDefault("parentKind", mission.kind()), result);
                plans.completeCurrent(
                        planId,
                        terminal,
                        nowMillis);
                yield Outcome.continuePlan();
            }
            case "root.bring" -> {
                int confirmedBeforePlanning = integer(frame.parameters(), "deliveredTotal", 0);
                if (confirmedBeforePlanning >= frame.count()) {
                    plans.completeCurrent(planId,
                            "Paper verified the complete inventory objective",
                            nowMillis);
                    yield Outcome.continuePlan();
                }
                if (resumedAfterDelivery(frame)) {
                    boolean transactionAccounted = Boolean.parseBoolean(
                            frame.parameters().getOrDefault("deliveryTransactionAccounted", "false"));
                    int delivered = integer(frame.parameters(), "deliveredTotal", 0)
                            + (transactionAccounted ? 0
                            : integer(frame.parameters(), "deliveryBatch", Math.toIntExact(frame.count())));
                    if (delivered >= frame.count()) {
                        plans.completeCurrent(planId,
                                "verified delivered " + delivered + "/" + frame.count()
                                        + " inventory objective",
                                nowMillis);
                        yield Outcome.continuePlan();
                    }
                    Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
                    checkpoint.put("deliveredTotal", Integer.toString(delivered));
                    checkpoint.remove("deliveryBatch");
                    checkpoint.remove("deliverySuppliesGoals");
                    checkpoint.remove("deliverySuppliesHomeGoals");
                    checkpoint.remove("deliverySuppliesHomeBound");
                    checkpoint.remove("deliverySuppliesHomeDeliveredBase");
                    checkpoint.remove("deliveryTransactionAccounted");
                    // A confirmed handoff completed this acquisition program.
                    // A later delivery batch is a new root phase with a new
                    // baseline, not a divergence of the completed program.
                    checkpoint.remove(UNIVERSAL_PROGRAM_KEY);
                    plans.checkpointCurrent(planId, checkpoint,
                            "delivered partial batch " + delivered + "/" + frame.count()
                                    + "; resuming acquisition",
                            nowMillis);
                    yield Outcome.continuePlan();
                }
                int delivered = integer(frame.parameters(), "deliveredTotal", 0);
                int remaining = Math.toIntExact(frame.count()) - delivered;
                int batch = integer(frame.parameters(), "deliveryBatch", 0);
                if (batch < 1) {
                    // Bring owns acquisition, so choose the efficient full trip
                    // and let the planner top it up. Basing this on currently
                    // spendable inventory recreated the 15-delivered + 1-hunted
                    // failure whenever one food item was reserved as a ration.
                    batch = DeliveryPolicy.bringBatchSize(
                            remaining, inventoryStackLimit(frame.target()));
                    Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
                    checkpoint.put("deliveryBatch", Integer.toString(batch));
                    plans.checkpointCurrent(planId, checkpoint,
                            "prepared delivery batch " + batch + " ("
                                    + delivered + "/" + frame.count() + " already delivered)",
                            nowMillis);
                    yield Outcome.continuePlan();
                }
                yield planResourceRequest(
                        planId,
                        frame,
                        ResourcePlanner.Request.deliver(
                                frame.target(), batch,
                                required(frame.parameters(), "player")),
                        nowMillis,
                        true);
            }
            case "root.give" -> {
                int confirmedBeforePlanning = integer(frame.parameters(), "deliveredTotal", 0);
                if (confirmedBeforePlanning >= frame.count()) {
                    plans.completeCurrent(planId,
                            "Paper verified delivery of existing inventory",
                            nowMillis);
                    yield Outcome.continuePlan();
                }
                if (resumedAfterDelivery(frame)) {
                    int delivered = integer(frame.parameters(), "deliveredTotal", 0);
                    if (delivered >= frame.count()) {
                        plans.completeCurrent(planId, "verified delivered existing inventory", nowMillis);
                        yield Outcome.continuePlan();
                    }
                }
                int delivered = integer(frame.parameters(), "deliveredTotal", 0);
                int remaining = Math.toIntExact(frame.count()) - delivered;
                if (requestItemCount(frame.target()) < remaining) {
                    yield Outcome.blocked("give needs " + remaining + " " + frame.target()
                            + " already in Entity's inventory; use /e bring to acquire it first");
                }
                boolean exactDelivery = isExactUniversalRequest(frame.target());
                String deliveryItem = exactDelivery
                        ? resourcePlanner.catalog().resolveUniversalItem(frame.target())
                        : frame.target();
                LinkedHashMap<String, String> deliveryParameters = new LinkedHashMap<>();
                deliveryParameters.put("recipient", required(frame.parameters(), "player"));
                deliveryParameters.put("itemCount", Long.toString(frame.count()));
                deliveryParameters.put("allowAcquire", "false");
                deliveryParameters.put("exactDelivery", Boolean.toString(exactDelivery));
                Action delivery = new Action(
                        ActionKind.DELIVER,
                        deliveryItem,
                        remaining,
                        deliveryParameters,
                        "Deliver existing inventory to " + required(frame.parameters(), "player"));
                pushAction(planId, delivery, nowMillis, false);
                yield Outcome.continuePlan();
            }
            case "root.mining_access" -> tickMiningAccessTool(planId, frame, nowMillis);
            case "root.gear" -> tickGearRoot(planId, frame, lease, nowMillis);
            case "root.legacy_mine" -> tickLegacyMineRoot(planId, frame, mission, lease, nowMillis);
            case "root.farm" -> tickManagedFarmRoot(planId, frame, lease, nowMillis);
            case "root.build" -> tickBlueprintRoot(planId,frame,nowMillis);
            default -> Outcome.blocked("unsupported plan root " + frame.kind());
        };
    }

    private Outcome tickBlueprintRoot(String planId,PlanFrame root,long nowMillis) throws IOException {
        if (blueprintProjects==null) return Outcome.blocked("Blueprint store is unavailable");
        var project=blueprintProjects.require(required(root.parameters(),"projectId"),required(root.parameters(),"digest"));
        if (root.parameters().getOrDefault("buildStage","").equals("building")) {
            plans.completeCurrent(planId,"Verified complete physical blueprint",nowMillis);
            return Outcome.continuePlan();
        }
        Outcome supplied=finishBlueprintSupply(planId,root,nowMillis);
        if(supplied!=null) return supplied;
        if(!dev.entity.client.blueprint.BlueprintProjects.siteLoaded(client,project))
            return returnToBlueprintSite(planId,root,null,project,nowMillis);
        var materials=blueprintProjects.remainingMaterials(client);
        var withSupports=blueprintProjects.supplyMaterials(client,inventoryViewWithoutStations());
        if(!withSupports.equals(materials)&&withSupports.containsKey("minecraft:dirt")
                &&inventoryViewWithoutStations().getOrDefault("minecraft:dirt",0)==0)
            return requestBlueprintSupply(planId,root,null,Map.of("minecraft:dirt",withSupports.get("minecraft:dirt")),nowMillis);
        if(!dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.canStart(materials,inventoryViewWithoutStations()))
            return requestBlueprintSupply(planId,root,null,materials,nowMillis);
        Map<String,String> checkpoint=new LinkedHashMap<>(root.parameters()); checkpoint.put("buildStage","building");
        plans.checkpointCurrent(planId,checkpoint,"Materials ready; native builder owns construction",nowMillis);
        plans.pushPrerequisite(planId,new PlanFrame.Spec("build",root.target(),1,root.parameters()),
                "build the exact confirmed design",nowMillis);
        return Outcome.continuePlan();
    }

    /** A supply child pops back to the same build frame, never to a completed construction root. */
    private Outcome finishBlueprintSupply(String planId,PlanFrame frame,long nowMillis) throws IOException {
        if(!Boolean.parseBoolean(frame.parameters().getOrDefault("blueprintSupplyAwaiting","false")))
            return null;
        var targets=dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.decode(
                required(frame.parameters(),"blueprintSupplyTargets"));
        if(!dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.supplied(targets,inventoryViewWithoutStations()))
            return Outcome.blocked("Blueprint acquisition returned without its carried material batch; "
                    +"supply the missing items and /e build resume");
        Map<String,String> checkpoint=new LinkedHashMap<>(frame.parameters());
        checkpoint.remove("blueprintSupplyAwaiting");
        checkpoint.remove("blueprintSupplyTargets");
        plans.checkpointCurrent(planId,checkpoint,"Verified carried blueprint batch; resume the same project",nowMillis);
        actionSupervisor.suspend(frame.id(),nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome requestBlueprintSupply(String planId,PlanFrame frame,ControlLease lease,
            Map<String,Integer> remaining,long nowMillis) throws IOException {
        var physical=inventoryViewWithoutStations();
        var missing=dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.exhausted(remaining,physical);
        String missingText=missing.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(7)
                .map(e->e.getKey().replace("minecraft:","")+"="+e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        if(!Boolean.parseBoolean(frame.parameters().getOrDefault("gather","false")))
            return Outcome.blocked("Blueprint needs materials: "+missingText
                    +". Supply items then /e build resume, or /e build confirm gather to authorize acquisition");
        String fingerprint=dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.fingerprint(remaining,physical);
        if(fingerprint.equals(frame.parameters().get("blueprintSupplyBefore")))
            return Outcome.blocked("Blueprint materials and remaining work are unchanged since the previous supply attempt; "
                    +"no repeated gathering was started");
        int emptySlots=0;
        for(ItemStack stack:client.player.getInventory().getMainStacks()) if(stack.isEmpty()) emptySlots++;
        Map<String,Integer> stackSizes=new LinkedHashMap<>();
        missing.keySet().forEach(item->stackSizes.put(item,
                Registries.ITEM.get(Identifier.of(item)).getDefaultStack().getMaxCount()));
        var batch=dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.batch(
                remaining,physical,stackSizes,emptySlots);
        if(batch.isEmpty()) {
            if(missing.isEmpty()) return Outcome.blocked("Blueprint has no exhausted material kind; inspect the blocked site");
            if(lease!=null) baritone.cancel(lease.epoch(),"making receiving and production room for blueprint supplies");
            actionSupervisor.suspend(frame.id(),nowMillis);
            return insertCapacityRecovery(planId,frame,nowMillis,
                    dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.minimumEmptySlotsForBatch(),
                    dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.carriedProjectMaterials(
                            blueprintProjects.remainingMaterials(client),physical,blueprintProjects.needsSupportReserve()));
        }
        if(lease!=null) baritone.cancel(lease.epoch(),"handing exhausted blueprint materials to the existing acquisition planner");
        actionSupervisor.suspend(frame.id(),nowMillis);
        Map<String,String> checkpoint=new LinkedHashMap<>(frame.parameters());
        checkpoint.put("blueprintSupplyBefore",fingerprint);
        checkpoint.put("blueprintSupplyAwaiting","true");
        checkpoint.put("blueprintSupplyTargets",dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.encode(batch));
        plans.checkpointCurrent(planId,checkpoint,"Acquire one inventory-sized blueprint batch",nowMillis);
        if(batch.size()==1) {
            var target=batch.entrySet().iterator().next();
            plans.pushPrerequisite(planId,new PlanFrame.Spec("root.acquire",target.getKey(),target.getValue(),
                    Map.of("description","acquire the next confirmed blueprint material batch")),
                    "existing material planner supplies the same project",nowMillis);
        } else plans.pushPrerequisite(planId,new PlanFrame.Spec("root.batch.acquire","items",batch.size(),
                Map.of("batchItems",dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.encode(batch),
                        "description","acquire the next confirmed blueprint material batch")),
                "existing batch material planner supplies the same project",nowMillis);
        return Outcome.continuePlan();
    }

    /** One durable cursor covers the exact named farm, one crop family at a time. */
    private Outcome tickManagedFarmRoot(
            String planId,
            PlanFrame root,
            ControlLease lease,
            long nowMillis) throws IOException {
        if (protectedAreas == null) {
            return Outcome.running(
                    "waiting for authenticated farm-area policy", Double.NaN, 0, false);
        }
        String areaName = required(root.parameters(), "area");
        String expectedFingerprint = required(root.parameters(), "areaFingerprint");
        ProtectedAreaClientState.NamedResourceAreaObservation named =
                protectedAreas.observeNamedResourceArea(
                        ProtectedAreaPolicy.AreaKind.HARVESTING, areaName);
        if (!named.available()) {
            return Outcome.running(named.detail(), Double.NaN, 0, false);
        }
        ProtectedAreaPolicy.Area area = named.area().orElse(null);
        if (area == null) return Outcome.blocked(named.detail());
        if (!ManagedFarmPolicy.areaFingerprint(area).equals(expectedFingerprint)) {
            return Outcome.blocked(
                    "farm '" + areaName + "' changed after this run was accepted; "
                            + "review the selection and issue a new run");
        }

        if (!root.parameters().containsKey(MANAGED_FARM_BASELINE_KEY)) {
            return bindManagedFarmEconomy(planId, root, nowMillis);
        }

        List<ManagedFarmPolicy.CoveragePoint> coverage =
                ManagedFarmPolicy.coverage(area);
        int tile = integer(root.parameters(), "farmTile", 0);
        int family = integer(root.parameters(), "farmFamily", 0);
        String stage = root.parameters().getOrDefault("farmStage", "route");
        if (tile < 0 || tile > coverage.size()
                || family < 0 || family > ManagedFarmPolicy.crops().size()) {
            return Outcome.blocked("durable managed-farm cursor is outside its bounded pass");
        }
        if (tile >= coverage.size()) {
            return tickManagedFarmSettlement(
                    planId, root, areaName, lease, nowMillis);
        }

        ManagedFarmPolicy.CoveragePoint point = coverage.get(tile);
        if (stage.equals("route")) {
            if (client.player == null) {
                return Outcome.running(
                        "waiting to observe Entity before approaching the farm",
                        Double.NaN, 0, false);
            }
            java.util.Optional<ManagedFarmPolicy.CoveragePoint> approach =
                    ManagedFarmPolicy.exteriorApproach(
                            area, client.player.getBlockX(), client.player.getBlockZ());
            if (approach.isEmpty()) {
                checkpointManagedFarmCursor(
                        planId, root, tile, 0, "route_local",
                        "farm is inside the fresh local routing ring",
                        nowMillis);
                return Outcome.continuePlan();
            }
            ManagedFarmPolicy.CoveragePoint exterior = approach.orElseThrow();
            LinkedHashMap<String, String> checkpoint =
                    new LinkedHashMap<>(root.parameters());
            checkpoint.put("farmStage", "route_approach_running");
            checkpoint.put("farmTile", Integer.toString(tile));
            checkpoint.put("farmFamily", "0");
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "bound exterior approach for farm coverage "
                            + (tile + 1) + "/" + coverage.size(),
                    nowMillis);
            plans.pushPrerequisite(
                    planId,
                    new PlanFrame.Spec(
                            "goto", areaName, 1,
                            Map.of(
                                    "x", Integer.toString(exterior.x()),
                                    "z", Integer.toString(exterior.z()),
                                    "dimension", area.dimension(),
                                    "horizontalOnly", "true",
                                    "allowBreak", "false",
                                    "allowPlace", "false",
                                    "allowParkourPlace", "false",
                                    "allowWaterBucketFall", "false",
                                    "description", "travel to exterior of farm coverage "
                                            + (tile + 1) + "/" + coverage.size())),
                    "finish remote travel outside the next named-farm coverage point",
                    nowMillis);
            return Outcome.continuePlan();
        }
        if (stage.equals("route_approach_running")) {
            checkpointManagedFarmCursor(
                    planId, root, tile, 0, "route_local",
                    "reached the farm's exterior routing ring", nowMillis);
            return Outcome.continuePlan();
        }
        if (stage.equals("route_local")) {
            // The exterior goal exists only to load the selected field with a fresh
            // Baritone context. Once a real supported crop is already visible, do
            // not add another X/Z-only movement that can leave a raised path or
            // choose the correct column at the wrong elevation. The crop actuator
            // below pins the actual block and an exact supported 3D stance.
            if (observesManagedFarmCrop(area, point)) {
                checkpointManagedFarmCursor(
                        planId, root, tile, 0, "family",
                        "observed farm coverage; exact crop stance owns local entry",
                        nowMillis);
                return Outcome.continuePlan();
            }
            LinkedHashMap<String, String> checkpoint =
                    new LinkedHashMap<>(root.parameters());
            checkpoint.put("farmStage", "route_running");
            checkpoint.put("farmTile", Integer.toString(tile));
            checkpoint.put("farmFamily", "0");
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "started fresh local farm route "
                            + (tile + 1) + "/" + coverage.size(),
                    nowMillis);
            plans.pushPrerequisite(
                    planId,
                    new PlanFrame.Spec(
                            "goto", areaName, 1,
                            Map.of(
                                    "x", Integer.toString(point.x()),
                                    "z", Integer.toString(point.z()),
                                    "dimension", area.dimension(),
                                    "horizontalOnly", "true",
                                    "allowBreak", "false",
                                    "allowPlace", "false",
                                    "allowParkourPlace", "false",
                                    "allowWaterBucketFall", "false",
                                    "description", "enter observed farm coverage "
                                            + (tile + 1) + "/" + coverage.size())),
                    "enter the field with a fresh loaded-chunk snapshot",
                    nowMillis);
            return Outcome.continuePlan();
        }
        if (stage.equals("route_running")) {
            checkpointManagedFarmCursor(
                    planId, root, tile, 0, "family",
                    "reached farm coverage " + (tile + 1) + "/" + coverage.size(),
                    nowMillis);
            return Outcome.continuePlan();
        }
        if (stage.equals("seed_binding")) {
            checkpointManagedFarmCursor(
                    planId, root, tile, family, "family",
                    "verified exact replant item in the hotbar", nowMillis);
            return Outcome.continuePlan();
        }
        if (stage.equals("family_running")) {
            checkpointManagedFarmCursor(
                    planId, root, tile, family + 1, "family",
                    "settled crop family " + (family + 1) + "/"
                            + ManagedFarmPolicy.crops().size(),
                    nowMillis);
            return Outcome.continuePlan();
        }
        if (!stage.equals("family")) {
            return Outcome.blocked("unsupported managed-farm phase " + stage);
        }
        if (family >= ManagedFarmPolicy.crops().size()) {
            checkpointManagedFarmCursor(
                    planId, root, tile + 1, 0, "route",
                    "finished all supported crops at coverage "
                            + (tile + 1) + "/" + coverage.size(),
                    nowMillis);
            return Outcome.continuePlan();
        }

        ManagedFarmPolicy.CropFamily crop = ManagedFarmPolicy.crops().get(family);
        if (canonicalCount(crop.replant()) < 1) {
            checkpointManagedFarmCursor(
                    planId, root, tile, family + 1, "family",
                    "left mature " + crop.block() + " standing: no exact "
                            + crop.replant() + " was available to reserve",
                    nowMillis);
            return Outcome.continuePlan();
        }
        if (!hotbarContains(crop.replant())) {
            int hotbar = preferredFarmHotbarSlot();
            checkpointManagedFarmCursor(
                    planId, root, tile, family, "seed_binding",
                    "preparing exact " + crop.replant() + " replant custody",
                    nowMillis);
            plans.pushPrerequisite(
                    planId,
                    new PlanFrame.Spec(
                            "equip", crop.replant(), 1,
                            Map.of(
                                    "farmSeedBinding", "true",
                                    "farmSeedHotbarSlot", Integer.toString(hotbar),
                                    "description", "move exact replant item into a usable hotbar slot")),
                    "reserve the exact crop replacement before mutation",
                    nowMillis);
            return Outcome.continuePlan();
        }

        checkpointManagedFarmCursor(
                planId, root, tile, family, "family_running",
                "committed " + crop.block() + " segment at farm coverage "
                        + (tile + 1) + "/" + coverage.size(),
                nowMillis);
        LinkedHashMap<String, String> harvest = new LinkedHashMap<>();
        harvest.put("blockAlternatives", crop.block());
        harvest.put("expectedMinimumItems", Integer.toString(MAX_BATCH_ITEM_COUNT));
        harvest.put("minimumDropsPerBlock", "1");
        harvest.put("estimatedBlocksPerDrop", "1");
        harvest.put("probabilistic", "false");
        harvest.put("worldSourceKind", "HARVEST");
        harvest.put("replantItem", crop.replant());
        harvest.put("resourceAreaName", areaName);
        harvest.put("resourceAreaFingerprint", expectedFingerprint);
        harvest.put("completeWhenResourceExhausted", "true");
        harvest.put("managedFarm", "true");
        // Crop breaking and replanting are owned by the exact crop actuator. Baritone may
        // navigate between cells, but a farm pass never earns general terrain mutation.
        harvest.put("allowBreak", "false");
        harvest.put("allowPlace", "false");
        harvest.put("allowParkourPlace", "false");
        harvest.put("allowWaterBucketFall", "false");
        harvest.put("description", "harvest mature " + crop.block()
                + " once inside named farm '" + areaName + "'");
        plans.pushPrerequisite(
                planId,
                new PlanFrame.Spec(
                        "mine", crop.produce(), MAX_BATCH_ITEM_COUNT, harvest),
                "run one exact sustainable crop-family segment",
                nowMillis);
        return Outcome.continuePlan();
    }

    /** Bounded loaded-world probe used only to avoid a redundant local loading route. */
    private boolean observesManagedFarmCrop(
            ProtectedAreaPolicy.Area area,
            ManagedFarmPolicy.CoveragePoint point) {
        if (client.player == null || client.world == null) return false;
        int horizontal = ManagedFarmPolicy.COVERAGE_SPACING / 2;
        int minimumX = Math.max(area.minX(), point.x() - horizontal);
        int maximumX = Math.min(area.maxX(), point.x() + horizontal);
        int minimumZ = Math.max(area.minZ(), point.z() - horizontal);
        int maximumZ = Math.min(area.maxZ(), point.z() + horizontal);
        int playerY = client.player.getBlockY();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int offset = 0; offset <= MANAGED_FARM_ROUTE_VERTICAL_SCAN; offset++) {
            int[] ys = offset == 0
                    ? new int[] { playerY }
                    : new int[] { playerY + offset, playerY - offset };
            for (int y : ys) {
                if (y < client.world.getBottomY() || y > client.world.getTopYInclusive()) continue;
                for (int x = minimumX; x <= maximumX; x++) {
                    for (int z = minimumZ; z <= maximumZ; z++) {
                        if (!client.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
                        cursor.set(x, y, z);
                        String block = Registries.BLOCK.getId(
                                client.world.getBlockState(cursor).getBlock()).getPath();
                        if (MANAGED_FARM_CROP_BLOCKS.contains(block)) return true;
                    }
                }
            }
        }
        return false;
    }

    private void checkpointManagedFarmCursor(
            String planId,
            PlanFrame root,
            int tile,
            int family,
            String stage,
            String detail,
            long nowMillis) throws IOException {
        LinkedHashMap<String, String> checkpoint =
                new LinkedHashMap<>(root.parameters());
        checkpoint.put("farmTile", Integer.toString(tile));
        checkpoint.put("farmFamily", Integer.toString(family));
        checkpoint.put("farmStage", stage);
        plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
    }

    /** Captures the crop-only custody floor after Home seed sourcing and before mutation. */
    private Outcome bindManagedFarmEconomy(
            String planId,
            PlanFrame root,
            long nowMillis) throws IOException {
        LinkedHashMap<String, Integer> carried = new LinkedHashMap<>();
        for (String item : ManagedFarmEconomyPolicy.usefulOutputs()) {
            int count = canonicalCount(item);
            if (count > 0) carried.put(item, count);
        }
        Map<String, Integer> baseline = ManagedFarmEconomyPolicy.baseline(carried);
        LinkedHashMap<String, String> checkpoint =
                new LinkedHashMap<>(root.parameters());
        checkpoint.put(MANAGED_FARM_BASELINE_KEY,
                encodeManagedFarmCounts(baseline));

        if (settingsLoadState.authoritative() && homeEconomy != null) {
            HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
            if (snapshot.home() != null && snapshot.generation() > 0L
                    && !snapshot.storageExplicitlyMissing()
                    && !snapshot.storageRegistrations().isEmpty()) {
                HomeEconomySession.HomeAnchor home = snapshot.home();
                checkpoint.put(MANAGED_FARM_HOME_GENERATION,
                        Long.toString(snapshot.generation()));
                checkpoint.put(MANAGED_FARM_HOME_FINGERPRINT,
                        home.fingerprint());
                checkpoint.put(MANAGED_FARM_HOME_DIMENSION,
                        home.dimension());
                checkpoint.put(MANAGED_FARM_HOME_X, Integer.toString(home.x()));
                checkpoint.put(MANAGED_FARM_HOME_Y, Integer.toString(home.y()));
                checkpoint.put(MANAGED_FARM_HOME_Z, Integer.toString(home.z()));
                List<String> ledgers = managedFarmStorageLedgers(snapshot);
                if (!ledgers.isEmpty()) {
                    checkpoint.put(MANAGED_FARM_STORAGE_LEDGERS,
                            String.join(",", ledgers));
                    checkpoint.put(MANAGED_FARM_STORAGE_INDEX, "0");
                }
            }
        }
        plans.checkpointCurrent(
                planId, checkpoint,
                "captured the exact pre-pass crop inventory floor after Home seed sourcing",
                nowMillis);
        return Outcome.continuePlan();
    }

    /** Returns only post-baseline farm output through acknowledged exact Home transfers. */
    private Outcome tickManagedFarmSettlement(
            String planId,
            PlanFrame root,
            String areaName,
            ControlLease lease,
            long nowMillis) throws IOException {
        Map<String, Integer> baseline = managedFarmBaseline(root);
        Map<String, Integer> surplus = ManagedFarmEconomyPolicy.surplus(
                baseline, managedFarmCarriedCounts());
        String encodedPending = root.parameters().get(MANAGED_FARM_TRANSFER_KEY);
        HomeEconomySession.PendingTransfer pending = null;
        if (encodedPending != null) {
            try {
                pending = HomeTransferIntentCodec.decode(encodedPending);
            } catch (IllegalArgumentException corrupt) {
                return Outcome.blocked(
                        "durable farm Home transfer is corrupt: " + corrupt.getMessage());
            }
            if (pending.direction() != HomeStockPolicy.Direction.DEPOSIT
                    || !pending.categoryId().equals("farm_surplus")) {
                return Outcome.blocked("durable farm Home transfer has invalid authority");
            }
        }

        if (pending == null && surplus.isEmpty()) {
            return finishManagedFarmPass(
                    planId, root, areaName,
                    "completed the farm pass with no new mature-crop surplus",
                    nowMillis);
        }

        List<String> ledgers = managedFarmBoundStorageLedgers(root);
        int storageIndex = integer(root.parameters(), MANAGED_FARM_STORAGE_INDEX, 0);
        String bindingFailure = managedFarmHomeBindingFailure(root, ledgers);
        if (!bindingFailure.isBlank()) {
            if (pending != null) {
                return Outcome.blocked(
                        "farm Home return cannot reconcile: " + bindingFailure);
            }
            return finishManagedFarmPass(
                    planId, root, areaName,
                    "completed the farm pass with useful produce carried: "
                            + bindingFailure,
                    nowMillis);
        }
        if (storageIndex < 0 || storageIndex > ledgers.size()) {
            return Outcome.blocked("durable farm storage cursor is invalid");
        }
        if (storageIndex >= ledgers.size()) {
            return finishManagedFarmPass(
                    planId, root, areaName,
                    surplus.isEmpty()
                            ? "completed the farm pass and returned all farm-earned surplus to Home"
                            : "completed the farm pass with remaining produce carried because registered storage is full",
                    nowMillis);
        }

        HomeMissionSupplyCodec.State access = managedFarmAccessState(
                planId, root, ledgers, storageIndex);
        HomeEconomySession.ContainerIdentity requiredIdentity =
                pending == null ? null : pending.containerIdentity();
        Outcome opened = ensureHomeMissionSupplyChestOpen(
                planId, access,
                HomeEconomySession.HomeAnchor.at(
                        access.dimension(), access.homeX(), access.homeY(), access.homeZ()),
                lease, nowMillis, requiredIdentity);
        if (opened != null) {
            if (opened.state() == State.BLOCKED && pending == null) {
                return advanceManagedFarmStorage(
                        planId, root, access, storageIndex,
                        "registered farm-return storage was unavailable: " + opened.detail(),
                        nowMillis);
            }
            return opened;
        }

        if (pending != null) {
            return tickManagedFarmPendingDeposit(
                    planId, root, access, pending, baseline, nowMillis);
        }
        if (surplus.isEmpty()) {
            return finishManagedFarmPass(
                    planId, root, areaName,
                    "completed the farm pass and returned all farm-earned surplus to Home",
                    nowMillis);
        }

        ClientInventoryController.HomeTransferPlan plan = null;
        String selectedItem = "";
        for (String item : ManagedFarmEconomyPolicy.usefulOutputs()) {
            int count = surplus.getOrDefault(item, 0);
            if (count <= 0) continue;
            Optional<ClientInventoryController.HomeTransferPlan> candidate =
                    inventory.planFarmEarnedHomeDeposit(item, count, baseline);
            if (candidate.isPresent()) {
                plan = candidate.orElseThrow();
                selectedItem = item;
                break;
            }
        }
        if (plan == null) {
            return advanceManagedFarmStorage(
                    planId, root, access, storageIndex,
                    "registered storage has no exact capacity for remaining farm output",
                    nowMillis);
        }
        Optional<HomeEconomySession.ContainerIdentity> identity =
                homeMissionSupplyExactIdentity(planId, access);
        if (identity.isEmpty()) {
            return Outcome.blocked(
                    "farm return lost the exact registered container identity before transfer");
        }
        String transferId = managedFarmSettlementCycle(planId, root)
                + ':' + storageIndex + ':' + selectedItem + ':'
                + plan.playerCountBefore() + ':' + plan.count();
        HomeEconomySession.PendingTransfer intent =
                new HomeEconomySession.PendingTransfer(
                        transferId, HomeStockPolicy.Direction.DEPOSIT,
                        "farm_surplus", selectedItem, plan.count(),
                        plan.syncId(), plan.sourceSlot(), plan.destinationSlot(),
                        plan.destinationCountBefore(), plan.playerCountBefore(),
                        plan.chestCountBefore(), nowMillis, identity.orElseThrow());
        LinkedHashMap<String, String> checkpoint =
                new LinkedHashMap<>(root.parameters());
        checkpoint.put(MANAGED_FARM_TRANSFER_KEY,
                HomeTransferIntentCodec.encode(intent));
        plans.checkpointCurrent(
                planId, checkpoint,
                "durably prepared exact Home deposit of " + plan.count()
                        + ' ' + selectedItem + " before its first click",
                nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome tickManagedFarmPendingDeposit(
            String planId,
            PlanFrame root,
            HomeMissionSupplyCodec.State access,
            HomeEconomySession.PendingTransfer pending,
            Map<String, Integer> baseline,
            long nowMillis) throws IOException {
        if (!inventory.homeTransferClicksSettled(nowMillis))
            return Outcome.running("waiting for exact Home deposit click acknowledgement", Double.NaN, 0L, false);
        Optional<HomeEconomySession.ContainerIdentity> identity =
                homeMissionSupplyExactIdentity(planId, access);
        if (identity.isEmpty()
                || !pending.containerIdentity().equals(identity.orElseThrow())) {
            return Outcome.blocked(
                    "pending farm deposit cannot reconcile against a different container");
        }
        if (root.kind().equals("root.inventory_capacity")
                && root.parameters().containsKey(CAPACITY_TRANSFER_REOBSERVE_KEY)) {
            return reobserveChangedCapacityDeposit(planId, root, pending, nowMillis);
        }
        ClientInventoryController.HomeTransferPlan plan = homeTransferPlan(pending);
        ClientInventoryController.HomeTransferObservation observed =
                inventory.observeHomeTransfer(plan);
        if (observed.syncId() < 0) {
            return Outcome.running(
                    "reopening the exact Home chest to reconcile the pending farm deposit",
                    Double.NaN, 0L, false);
        }
        HomeEconomyPolicy.TransferResolution resolution =
                HomeEconomyPolicy.reconcileTransfer(
                        pending,
                        new HomeEconomyPolicy.TransferObservation(
                                observed.syncId(), observed.cursorEmpty(),
                                inventory.hasExactHomeTransferCustody(
                                        plan, homeTransferOwner(pending)),
                                observed.playerItemCount(), observed.chestItemCount()));
        switch (resolution.truth()) {
            case COMMITTED, PARTIAL_COMMITTED -> {
                if (!inventory.releaseSettledHomeTransferCustody(
                        plan, homeTransferOwner(pending))) {
                    return Outcome.blocked(
                            "settled farm deposit retained ambiguous cursor custody");
                }
                return commitManagedFarmDeposit(
                        planId, root, pending.item(), resolution.movedCount(),
                        resolution.detail(), nowMillis);
            }
            case REBIND_REQUIRED -> {
                if (!inventory.releaseSettledHomeTransferCustody(
                        plan, homeTransferOwner(pending))) {
                    return Outcome.blocked(
                            "unchanged reopened farm deposit retained cursor custody");
                }
                Optional<ClientInventoryController.HomeTransferPlan> replacement =
                        inventory.planFarmEarnedHomeDeposit(
                                pending.item(), pending.count(), baseline);
                if (replacement.isEmpty()) {
                    return Outcome.blocked(
                            "farm deposit source or exact destination changed while reopening");
                }
                return rebindManagedFarmDeposit(
                        planId, root, pending, replacement.orElseThrow(),
                        identity.orElseThrow(), nowMillis, resolution.detail());
            }
            case CURSOR_OCCUPIED, DIVERGED -> {
                return Outcome.blocked(
                        "farm Home deposit failed closed: " + resolution.detail());
            }
            case PENDING -> {
                // The exact before-counts or owned cursor still authorize one bounded tick.
            }
        }

        ClientInventoryController.HomeTransferResult result =
                inventory.homeChestTransferTick(
                        plan, homeTransferOwner(pending), nowMillis);
        return switch (result) {
            case COMPLETE -> {
                if (!inventory.releaseSettledHomeTransferCustody(
                        plan, homeTransferOwner(pending))) {
                    yield Outcome.blocked(
                            "completed farm deposit retained ambiguous cursor custody");
                }
                yield commitManagedFarmDeposit(
                        planId, root, pending.item(), pending.count(),
                        "observed exact player/chest deltas for farm-earned surplus",
                        nowMillis);
            }
            case CLICKED, WAITING -> Outcome.running(
                    "waiting for exact farm deposit acknowledgement",
                    Double.NaN, 0L, false);
            case NEEDS_OWNED_CHEST, STALE_HANDLER -> Outcome.running(
                    "reopening the exact owned Home chest without another farm click",
                    Double.NaN, 0L, false);
            case SLOT_CHANGED, SOURCE_CHANGED, DESTINATION_CHANGED -> {
                if (root.kind().equals("root.inventory_capacity")) {
                    // Persist before settlement can park extra cursor cargo: its
                    // obsolete before-counts must not win next tick's reconciliation.
                    var checkpoint = new LinkedHashMap<>(root.parameters());
                    checkpoint.put(CAPACITY_TRANSFER_REOBSERVE_KEY, pending.id());
                    plans.checkpointCurrent(planId, checkpoint,
                            "settle changed capacity deposit before fresh observation", nowMillis);
                    yield Outcome.continuePlan();
                }
                Optional<ClientInventoryController.HomeTransferPlan> replacement =
                        inventory.planFarmEarnedHomeDeposit(
                                pending.item(), pending.count(), baseline);
                if (replacement.isEmpty()) {
                    yield Outcome.blocked(
                            "farm deposit slots changed and no exact replacement is available");
                }
                yield rebindManagedFarmDeposit(
                        planId, root, pending, replacement.orElseThrow(),
                        identity.orElseThrow(), nowMillis,
                        "durably rebound changed farm deposit slots");
            }
            case CURSOR_NOT_OWNED, COUNTS_DIVERGED -> Outcome.blocked(
                    "farm Home deposit failed closed: "
                            + result.name().toLowerCase(Locale.ROOT));
        };
    }

    private Outcome reobserveChangedCapacityDeposit(
            String planId,
            PlanFrame root,
            HomeEconomySession.PendingTransfer pending,
            long nowMillis) throws IOException {
        if (!pending.id().equals(root.parameters().get(CAPACITY_TRANSFER_REOBSERVE_KEY)))
            return Outcome.blocked("capacity settlement marker belongs to a different transfer");
        var settled = inventory.settleChangedHomeTransfer(
                homeTransferPlan(pending), homeTransferOwner(pending), nowMillis);
        if (settled == ClientInventoryController.HomeTransferSettlement.WAITING)
            return Outcome.running("settling owned capacity cargo before fresh Home observation",
                    Double.NaN, 0L, false);
        if (settled != ClientInventoryController.HomeTransferSettlement.SETTLED)
            return Outcome.blocked("changed capacity deposit cannot settle cursor safely: " + settled);
        if (!inventory.cursorEmpty()
                || !inventory.transactionDiagnostics().pendingClickOwner().isBlank()
                || !inventory.transactionDiagnostics().cursorOwner().isBlank())
            return Outcome.running("settling capacity inventory ownership before reallocating",
                    Double.NaN, 0L, false);
        var checkpoint = new LinkedHashMap<>(root.parameters());
        checkpoint.remove(MANAGED_FARM_TRANSFER_KEY);
        checkpoint.remove(CAPACITY_TRANSFER_REOBSERVE_KEY);
        plans.checkpointCurrent(planId, checkpoint,
                "reobserve capacity after exact custody settlement; no unproved deposit credited", nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome rebindManagedFarmDeposit(
            String planId,
            PlanFrame root,
            HomeEconomySession.PendingTransfer pending,
            ClientInventoryController.HomeTransferPlan replacement,
            HomeEconomySession.ContainerIdentity identity,
            long nowMillis,
            String detail) throws IOException {
        HomeEconomySession.PendingTransfer rebound =
                new HomeEconomySession.PendingTransfer(
                        pending.id(), HomeStockPolicy.Direction.DEPOSIT,
                        pending.categoryId(), pending.item(), replacement.count(),
                        replacement.syncId(), replacement.sourceSlot(),
                        replacement.destinationSlot(),
                        replacement.destinationCountBefore(),
                        replacement.playerCountBefore(),
                        replacement.chestCountBefore(), nowMillis, identity);
        LinkedHashMap<String, String> checkpoint =
                new LinkedHashMap<>(root.parameters());
        checkpoint.put(MANAGED_FARM_TRANSFER_KEY,
                HomeTransferIntentCodec.encode(rebound));
        plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome commitManagedFarmDeposit(
            String planId,
            PlanFrame root,
            String item,
            int moved,
            String detail,
            long nowMillis) throws IOException {
        LinkedHashMap<String, String> checkpoint =
                new LinkedHashMap<>(root.parameters());
        checkpoint.remove(MANAGED_FARM_TRANSFER_KEY);
        Map<String, Integer> deposited = parseCounts(
                checkpoint.getOrDefault("farmDepositedV1", ""));
        LinkedHashMap<String, Integer> updated = new LinkedHashMap<>(deposited);
        updated.merge(simple(item), moved, Math::addExact);
        checkpoint.put("farmDepositedV1", encodeManagedFarmCounts(updated));
        plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome advanceManagedFarmStorage(
            String planId,
            PlanFrame root,
            HomeMissionSupplyCodec.State access,
            int storageIndex,
            String detail,
            long nowMillis) throws IOException {
        ClientInventoryController.SafeCloseResult close =
                inventory.closeHandledScreenIfCursorEmpty(nowMillis);
        if (close == ClientInventoryController.SafeCloseResult.CURSOR_OCCUPIED) {
            return Outcome.blocked(
                    "farm return chest cursor is occupied while changing storage");
        }
        if (close != ClientInventoryController.SafeCloseResult.CLOSED
                || !inventory.cursorEmpty()
                || !inventory.transactionDiagnostics().pendingClickOwner().isBlank()
                || !inventory.transactionDiagnostics().cursorOwner().isBlank()) {
            return Outcome.running(
                    "settling farm deposit acknowledgement before changing storage",
                    Double.NaN, 0L, false);
        }
        workstations.clear(homeMissionSupplyOperationId(planId, access));
        LinkedHashMap<String, String> checkpoint =
                new LinkedHashMap<>(root.parameters());
        checkpoint.put(MANAGED_FARM_STORAGE_INDEX,
                Integer.toString(storageIndex + 1));
        plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome finishManagedFarmPass(
            String planId,
            PlanFrame root,
            String areaName,
            String detail,
            long nowMillis) throws IOException {
        ClientInventoryController.SafeCloseResult close =
                inventory.closeHandledScreenIfCursorEmpty(nowMillis);
        if (close == ClientInventoryController.SafeCloseResult.CURSOR_OCCUPIED) {
            return Outcome.blocked(
                    "farm pass cannot finish while the inventory cursor is occupied");
        }
        if (close != ClientInventoryController.SafeCloseResult.CLOSED
                || !inventory.cursorEmpty()
                || !inventory.transactionDiagnostics().pendingClickOwner().isBlank()
                || !inventory.transactionDiagnostics().cursorOwner().isBlank()) {
            return Outcome.running(
                    "settling the final farm inventory acknowledgement",
                    Double.NaN, 0L, false);
        }
        List<String> ledgers = managedFarmBoundStorageLedgers(root);
        int index = integer(root.parameters(), MANAGED_FARM_STORAGE_INDEX, 0);
        if (index >= 0 && index < ledgers.size()) {
            try {
                workstations.clear(homeMissionSupplyOperationId(
                        planId, managedFarmAccessState(planId, root, ledgers, index)));
            } catch (IllegalArgumentException ignored) {
                // A non-bound manual pass has no Home workstation operation.
            }
        }
        plans.completeCurrent(
                planId, detail + " for farm '" + areaName + "'", nowMillis);
        return Outcome.continuePlan();
    }

    private Map<String, Integer> managedFarmBaseline(PlanFrame root) {
        Map<String, Integer> parsed = parseCounts(
                root.parameters().getOrDefault(MANAGED_FARM_BASELINE_KEY, ""));
        return ManagedFarmEconomyPolicy.protectedCounts(parsed);
    }

    private Map<String, Integer> managedFarmCarriedCounts() {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (String item : ManagedFarmEconomyPolicy.usefulOutputs()) {
            int count = canonicalCount(item);
            if (count > 0) result.put(item, count);
        }
        return Map.copyOf(result);
    }

    private static String encodeManagedFarmCounts(Map<String, Integer> counts) {
        return ManagedFarmEconomyPolicy.usefulOutputs().stream()
                .filter(item -> counts.getOrDefault(item, 0) > 0)
                .map(item -> item + '=' + counts.get(item))
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static List<String> managedFarmStorageLedgers(
            HomeEconomySession.Snapshot snapshot) {
        HomeEconomySession.StorageRegistration primary = snapshot.primaryStorage();
        return snapshot.storageRegistrations().values().stream()
                .sorted(Comparator
                        .comparingInt((HomeEconomySession.StorageRegistration storage) ->
                                storage.equals(primary) ? 0 : 1)
                        .thenComparingLong(
                                HomeEconomySession.StorageRegistration::registeredAtMillis)
                        .thenComparing(HomeEconomySession.StorageRegistration::id))
                .map(HomeEconomySession.StorageRegistration::ledgerAssetId)
                .toList();
    }

    private static List<String> managedFarmBoundStorageLedgers(PlanFrame root) {
        String encoded = root.parameters().getOrDefault(
                MANAGED_FARM_STORAGE_LEDGERS, "");
        if (encoded.isBlank()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : encoded.split(",")) {
            String ledger = raw.trim();
            if (ledger.isEmpty() || !result.add(ledger)) {
                throw new IllegalArgumentException(
                        "durable farm storage binding is invalid");
            }
        }
        return List.copyOf(result);
    }

    private String managedFarmHomeBindingFailure(
            PlanFrame root,
            List<String> ledgers) {
        if (ledgers.isEmpty()
                || !root.parameters().containsKey(MANAGED_FARM_HOME_GENERATION)) {
            return "no registered Home storage was bound before the pass";
        }
        if (!settingsLoadState.authoritative()) {
            return "Home settings are not authoritative";
        }
        if (homeEconomy == null || homeEconomy.snapshot().home() == null) {
            return "Home economy is unavailable";
        }
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        long generation;
        try {
            generation = Long.parseLong(required(
                    root.parameters(), MANAGED_FARM_HOME_GENERATION));
        } catch (NumberFormatException corrupt) {
            return "durable Home generation is corrupt";
        }
        HomeEconomySession.HomeAnchor home = snapshot.home();
        if (snapshot.generation() != generation
                || !home.fingerprint().equals(required(
                        root.parameters(), MANAGED_FARM_HOME_FINGERPRINT))
                || !home.dimension().equals(required(
                        root.parameters(), MANAGED_FARM_HOME_DIMENSION))
                || home.x() != integer(root.parameters(), MANAGED_FARM_HOME_X, Integer.MIN_VALUE)
                || home.y() != integer(root.parameters(), MANAGED_FARM_HOME_Y, Integer.MIN_VALUE)
                || home.z() != integer(root.parameters(), MANAGED_FARM_HOME_Z, Integer.MIN_VALUE)) {
            return "Home changed during the finite farm pass";
        }
        Set<String> registered = snapshot.storageRegistrations().values().stream()
                .map(HomeEconomySession.StorageRegistration::ledgerAssetId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!registered.containsAll(ledgers)) {
            return "registered Home storage changed during the farm pass";
        }
        return "";
    }

    private HomeMissionSupplyCodec.State managedFarmAccessState(
            String planId,
            PlanFrame root,
            List<String> ledgers,
            int storageIndex) {
        if (storageIndex < 0 || storageIndex >= ledgers.size()) {
            throw new IllegalArgumentException("farm storage cursor is out of bounds");
        }
        return new HomeMissionSupplyCodec.State(
                HomeMissionSupplyCodec.Phase.READY,
                managedFarmSettlementCycle(planId, root),
                resourceCatalogFingerprint,
                Long.parseLong(required(root.parameters(), MANAGED_FARM_HOME_GENERATION)),
                required(root.parameters(), MANAGED_FARM_HOME_FINGERPRINT),
                required(root.parameters(), MANAGED_FARM_HOME_DIMENSION),
                integer(root.parameters(), MANAGED_FARM_HOME_X, Integer.MIN_VALUE),
                integer(root.parameters(), MANAGED_FARM_HOME_Y, Integer.MIN_VALUE),
                integer(root.parameters(), MANAGED_FARM_HOME_Z, Integer.MIN_VALUE),
                ledgers.get(storageIndex), "", "", false,
                Map.of(), Map.of(), List.of(), Map.of(), null,
                -1, "", ledgers.subList(0, storageIndex));
    }

    private static String managedFarmSettlementCycle(
            String planId,
            PlanFrame root) {
        String material = "entity2-managed-farm-return-v1\u0000" + planId
                + '\u0000' + root.id() + '\u0000'
                + root.parameters().getOrDefault("areaFingerprint", "");
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    private boolean hotbarContains(String item) {
        if (client.player == null) return false;
        String expected = simple(item);
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.getHotbarSize(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty()
                    && simple(Registries.ITEM.getId(stack.getItem()).toString())
                    .equals(expected)) return true;
        }
        return false;
    }

    private int preferredFarmHotbarSlot() {
        if (client.player == null) return 8;
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.getHotbarSize(); slot++) {
            if (client.player.getInventory().getStack(slot).isEmpty()) return slot;
        }
        return 8;
    }

    private record DeliveryOrder(PlanFrame root, List<PersonalSuppliesPolicy.Request> requests,
                                 int activeLine, boolean bring) { }

    private DeliveryOrder deliveryOrder(String planId) {
        TaskPlan plan = plans.restore(planId).orElseThrow();
        PlanFrame root = plan.frames().getFirst();
        if (!Set.of("root.give", "root.bring", "root.food.give", "root.food.bring",
                "root.batch.give", "root.batch.bring").contains(root.kind())) return null;
        boolean bring = root.kind().endsWith("bring");
        List<PersonalSuppliesPolicy.Request> requests = new java.util.ArrayList<>();
        int activeLine = 0;
        if (root.kind().startsWith("root.batch.")) {
            List<BatchItem> items = parseBatchItems(required(root.parameters(), "batchItems"));
            GatherFirstBatchPolicy.Decision restored = GatherFirstBatchPolicy.evaluate(
                    required(root.parameters(), "batchItems"), root.parameters(),
                    java.util.Collections.nCopies(items.size(), 0), true,
                    GatherFirstBatchPolicy.Mode.ACQUIRE_THEN_DELIVER);
            if (restored.action() == GatherFirstBatchPolicy.Action.CORRUPT)
                throw new IllegalArgumentException("invalid delivery order checkpoint: " + restored.reason());
            int cursor = integer(root.parameters(), GatherFirstBatchPolicy.DELIVERY_CURSOR_KEY, 0);
            for (int i = 0; i < items.size(); i++) {
                int delivered = restored.delivered().get(i);
                int remaining = items.get(i).count() - delivered;
                if (remaining < 0) throw new IllegalArgumentException("delivery receipt exceeds order");
                if (remaining == 0) continue;
                if (i == cursor) activeLine = requests.size();
                requests.add(PersonalSuppliesPolicy.Request.counted(items.get(i).item(), remaining));
            }
        } else {
            int remaining = Math.toIntExact(root.count()) - integer(root.parameters(), "deliveredTotal", 0);
            if (remaining < 0) throw new IllegalArgumentException("delivery receipt exceeds order");
            if (remaining > 0) {
                if (root.kind().equals("root.bring")) remaining = Math.min(remaining,
                        integer(root.parameters(), "deliveryBatch",
                                DeliveryPolicy.bringBatchSize(remaining, inventoryStackLimit(root.target()))));
                requests.add(PersonalSuppliesPolicy.Request.counted(root.target(), remaining));
            }
        }
        return new DeliveryOrder(root, List.copyOf(requests), activeLine, bring);
    }

    private PersonalSuppliesPolicy.Allocation deliveryAllocation(String planId) {
        var ledger = personalSupplyClaims();
        return DeliverySuppliesOrder.initialAllocation(inventory.personalSupplies().playerStacks(), ledger,
                DeliverySuppliesOrder.outgoingCargoIds(planId, ledger));
    }

    private PersonalSuppliesPolicy.DeliveryPlan deliverySuppliesPlan(String planId, DeliveryOrder order) {
        boolean useReserves = Boolean.parseBoolean(order.root().parameters().getOrDefault("useReserves", "false"));
        var physical = inventory.personalSupplies().playerStacks();
        var ledger = personalSupplyClaims();
        final Set<String> working;
        try {
            working = useReserves ? Set.of() : DeliverySuppliesOrder.resolveWorkingKit(
                    DeliverySuppliesOrder.decodeWorkingKit(order.root().parameters()
                            .getOrDefault(DeliverySuppliesOrder.WORKING_KIT_KEY, "1")),
                    physical, inventory.personalSupplies().workingKitComponents(), order.requests());
        } catch (IllegalArgumentException uncertainKit) {
            throw new DeliverySuppliesOrder.WorkingKitUnavailableException(uncertainKit.getMessage());
        }
        var allocation = PersonalSuppliesPolicy.allocate(physical, retainedPersonalProfile(), ledger,
                DeliverySuppliesOrder.outgoingCargoIds(planId, ledger), working);
        return order.bring()
                ? PersonalSuppliesPolicy.planBring(allocation, order.requests(), useReserves)
                : PersonalSuppliesPolicy.planGive(allocation, order.requests(), useReserves);
    }

    /** One existing acquisition child at a time; goals are absolute and never hide physical tools from Baritone. */
    private Outcome tickDeliverySuppliesRoot(String planId, PlanFrame frame, long nowMillis) throws IOException {
        DeliveryOrder order = deliveryOrder(planId);
        if (order == null || !order.root().id().equals(frame.id()) || order.requests().isEmpty()) return null;
        if (foodConsumptionPending.getAsBoolean())
            return Outcome.running("waiting for food consumption before personal-supplies accounting", Double.NaN, 0, false);
        PersonalSuppliesPolicy.DeliveryPlan allocation = deliverySuppliesPlan(planId, order);
        if (allocation.ready()) return null;
        if (!order.bring()) return Outcome.blocked("give moved nothing: " + DeliverySuppliesOrder.explanation(allocation)
                + "; use --use-reserves to explicitly include personal supplies");
        Map<String, Integer> goals = parseCounts(frame.parameters().getOrDefault("deliverySuppliesGoals", ""));
        Map<String, Integer> physical = inventoryViewWithoutStations();
        if (goals.isEmpty() || HomeStockPolicy.acquisitionGoalsSatisfied(goals, physical)) {
            goals = HomeStockPolicy.absoluteAcquisitionGoals(allocation.additionalGoals(), physical);
            if (goals.isEmpty()) return Outcome.blocked("bring cannot allocate this order: "
                    + DeliverySuppliesOrder.explanation(allocation));
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            checkpoint.put("deliverySuppliesGoals", goals.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(",")));
            plans.checkpointCurrent(planId, checkpoint, "bound delivery plus relevant personal shortfall", nowMillis);
            return Outcome.continuePlan();
        }
        Map.Entry<String, Integer> goal = goals.entrySet().stream().filter(e -> !HomeStockPolicy.acquisitionGoalsSatisfied(Map.of(e.getKey(), e.getValue()), physical))
                .sorted(java.util.Comparator.comparing(e -> DeliverySuppliesOrder.family(e.getKey())))
                .findFirst().orElseThrow();
        Map<String, String> child = new LinkedHashMap<>();
        child.put("description", "acquire delivery and relevant personal supplies");
        copyHomeBinding(frame.parameters(), child);
        child.remove(HOME_MISSION_SUPPLY_RETAINED_FOOD); // the absolute supply goal already contains its one floor
        plans.pushPrerequisite(planId, new PlanFrame.Spec(FoodFamilyPolicy.isFamilyRequest(goal.getKey())
                ? "root.food.acquire" : "root.acquire", goal.getKey(), goal.getValue(), child),
                "replenishing only the requested supply category", nowMillis);
        return Outcome.continuePlan();
    }

    private List<PersonalSuppliesPolicy.Selection> activeDeliverySelections(String planId) {
        DeliveryOrder order = deliveryOrder(planId);
        if (order == null || order.requests().isEmpty()) return List.of();
        PersonalSuppliesPolicy.DeliveryPlan decision = deliverySuppliesPlan(planId, order);
        return DeliverySuppliesOrder.selectionsForLine(decision, order.activeLine());
    }

    private String exactDeliverySelection(String planId, String item, int count) {
        DeliveryOrder order = deliveryOrder(planId);
        if (order == null || order.requests().isEmpty()) throw new IllegalArgumentException("delivery has no remaining order");
        PersonalSuppliesPolicy.DeliveryPlan decision = deliverySuppliesPlan(planId, order);
        if (!decision.ready()) throw new IllegalArgumentException(DeliverySuppliesOrder.explanation(decision));
        com.google.gson.JsonArray encoded = new com.google.gson.JsonArray();
        int remaining = count;
        for (PersonalSuppliesPolicy.Selection selection : DeliverySuppliesOrder.selectionsForLine(decision, order.activeLine())) {
            if (!DeliveryPolicy.normalizeItemId(item).equals(selection.stack().item())) continue;
            int slot = MinecraftPersonalSuppliesAdapter.playerInventorySlot(selection.stack().stackId());
            if (!selection.stillMatches(inventory.personalSupplies().playerStack(slot).orElse(null)))
                throw new IllegalArgumentException("exact selected stack changed before preparation");
            int take = Math.min(remaining, selection.count());
            if (take <= 0) continue;
            String bytes = java.util.Base64.getEncoder().encodeToString(
                    inventory.personalSupplies().playerStackBytes(slot).orElseThrow());
            if (bytes.length() > 65_536) throw new IllegalArgumentException("exact stack payload exceeds delivery limit");
            JsonObject stack = new JsonObject();
            stack.addProperty("slot", slot);
            stack.addProperty("stackCount", selection.stack().count());
            stack.addProperty("count", take);
            stack.addProperty("stackNbt", bytes);
            encoded.add(stack);
            remaining -= take;
        }
        if (remaining != 0) throw new IllegalArgumentException("permitted exact stacks no longer cover this handoff");
        String result = encoded.toString();
        if (result.length() > 262_144) throw new IllegalArgumentException("exact handoff payload too large");
        return result;
    }

    private Optional<ClientInventoryController.ConcreteItem> permittedConcreteDelivery(String planId, String objective) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PersonalSuppliesPolicy.Selection selection : activeDeliverySelections(planId)) {
            if (DeliverySuppliesOrder.matches(objective, selection.stack().item()))
                counts.merge(selection.stack().item(), selection.count(), Math::addExact);
        }
        Map.Entry<String, Integer> best = null;
        for (Map.Entry<String, Integer> entry : counts.entrySet())
            if (best == null || entry.getValue() > best.getValue()) best = entry;
        return best == null ? Optional.empty()
                : Optional.of(new ClientInventoryController.ConcreteItem(best.getKey(), best.getValue()));
    }

    /**
     * Runs once at a real get/bring/gear root, or the explicit same-job personal food root,
     * before its production child exists. Each owner frame retains its own transfer receipt.
     * A null result hands the unchanged root to its ordinary executor; every non-null result owns
     * this tick. GIVE is intentionally absent because its contract is carried-only and atomic.
     */
    private Outcome tickTopRootHomeMissionSupply(
            String planId,
            PlanFrame root,
            Mission mission,
            ControlLease lease,
            long nowMillis) throws IOException {
        if (!HomeMissionSupplyAllocator.directSourcingEligible(
                planId.equals(mission.id()), isHomeMaintenance(root))) return null;
        Optional<TaskPlan> restored = plans.restore(planId)
                .filter(plan -> plan.state() == TaskPlanState.OPEN);
        if (restored.isEmpty() || restored.orElseThrow().frames().isEmpty()
                || (!restored.orElseThrow().frames().getFirst().id().equals(root.id())
                    && !(root.kind().equals("root.food.acquire")
                        && "true".equals(root.parameters().get("personalMaintenance"))))) {
            return null;
        }
        HomeMissionSupplyAllocator.MissionKind missionKind = switch (root.kind()) {
            case "root.acquire", "root.food.acquire", "root.batch.acquire" ->
                    HomeMissionSupplyAllocator.MissionKind.GET;
            case "root.bring", "root.food.bring", "root.batch.bring" ->
                    HomeMissionSupplyAllocator.MissionKind.BRING;
            case "root.gear" -> HomeMissionSupplyAllocator.MissionKind.GEAR;
            case "root.farm" -> HomeMissionSupplyAllocator.MissionKind.FARM;
            default -> null;
        };
        if (missionKind == null) return null;
        if (!resourceCatalogReady) {
            return Outcome.running(
                    "waiting for the authenticated recipe catalog before Home sourcing",
                    Double.NaN, 0L, false);
        }

        if (missionKind == HomeMissionSupplyAllocator.MissionKind.BRING
                && !root.parameters().containsKey("deliverySuppliesHomeBound")
                && !root.parameters().containsKey(HOME_MISSION_SUPPLY_KEY)) {
            Map<String, Integer> requested = new LinkedHashMap<>();
            homeMissionSupplyRequestedCounts(homeMissionSupplyGoals(root))
                    .forEach((item, count) -> requested.merge(simple(item), count, Math::max));
            DeliveryOrder order = deliveryOrder(planId);
            if (order != null && !order.requests().isEmpty()) {
                PersonalSuppliesPolicy.DeliveryPlan decision = deliverySuppliesPlan(planId, order);
                HomeStockPolicy.absoluteAcquisitionGoals(decision.additionalGoals(), inventoryViewWithoutStations())
                        .forEach((item, count) -> requested.merge(item, count, Math::max));
            }
            Map<String, String> checkpoint = new LinkedHashMap<>(root.parameters());
            checkpoint.put("deliverySuppliesHomeBound", "true");
            checkpoint.put("deliverySuppliesHomeDeliveredBase", root.parameters().getOrDefault("deliveredTotal", "0"));
            checkpoint.put("deliverySuppliesHomeGoals", requested.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(",")));
            plans.checkpointCurrent(planId, checkpoint,
                    "bound delivery plus relevant personal supplies before Home sourcing", nowMillis);
            return Outcome.continuePlan();
        }
        List<AcquisitionRequest.ItemGoal> goals = homeMissionSupplyGoals(root);
        if (goals.isEmpty()) return null;
        String cycle = homeMissionSupplyCycleFingerprint(planId, root, goals);
        String encoded = root.parameters().get(HOME_MISSION_SUPPLY_KEY);
        HomeMissionSupplyCodec.State state = null;
        if (encoded != null) {
            try {
                state = HomeMissionSupplyCodec.decode(encoded);
            } catch (IllegalArgumentException corrupt) {
                return Outcome.blocked(
                        "durable Home mission supply is corrupt: " + corrupt.getMessage());
            }
        }

        if (state != null && !state.cycleFingerprint().equals(cycle)) {
            if (state.phase() != HomeMissionSupplyCodec.Phase.READY
                    && state.phase() != HomeMissionSupplyCodec.Phase.SKIPPED) {
                return blockHomeMissionSupply(
                        planId, root, state,
                        "Home supply objective changed during an unresolved transfer",
                        nowMillis);
            }
            String program = root.parameters().get(UNIVERSAL_PROGRAM_KEY);
            if (program != null) {
                try {
                    if (UniversalProgramCodec.decode(program).status() == Status.ACTIVE) {
                        return Outcome.blocked(
                                "a previous Home-sourced planner program is still active");
                    }
                } catch (IllegalArgumentException corrupt) {
                    return Outcome.blocked(
                            "durable universal program is corrupt: " + corrupt.getMessage());
                }
            }
            LinkedHashMap<String, String> checkpoint =
                    new LinkedHashMap<>(root.parameters());
            checkpoint.remove(HOME_MISSION_SUPPLY_KEY);
            checkpoint.remove(HOME_MISSION_SUPPLY_PIN_AUTHORITY);
            checkpoint.remove(UNIVERSAL_PROGRAM_KEY);
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "retired the completed Home-source cycle before the next delivery batch",
                    nowMillis);
            return Outcome.continuePlan();
        }

        if (state == null) {
            ResourcePlanner.InventoryView carried = homeMissionSupplyCarriedView(
                    missionKind, goals);
            if (homeMissionSupplyGoalsSatisfied(goals, carried.itemCounts())) {
                HomeMissionSupplyCodec.State skipped =
                        HomeMissionSupplyCodec.State.skipped(cycle, -1);
                return checkpointHomeMissionSupply(
                        planId, root, skipped, null,
                        "Home source skipped: owner-authorized carried inventory already satisfies "
                                + "the objective",
                        nowMillis);
            }
            // Observe at admission, before the owned-chest detour can hide a visible pond.
            // The existing selector records only permitted observations; this does not bind
            // a fill intent, use a bucket, or change the Home-first supply preference.
            observeWaterAcquisitionBeforeHomeSupply(baritone.resourcePerception().legitimate(), goals,
                    () -> workstations.findMissionWaterSource(HOME_WATER_SEARCH_RADIUS));
            if (!settingsLoadState.authoritative()) {
                return Outcome.blocked(
                        "Home settings are not authoritative; refusing direct chest access");
            }
            if (homeEconomy == null) {
                if (settings.hasHomeChest()) return Outcome.blocked(homeEconomyLoadFailure);
                return checkpointHomeMissionSupplyWithoutChest(
                        planId, root, missionKind, goals, carried, cycle,
                        "no Home is configured", nowMillis);
            }
            HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
            HomeEconomySession.StorageRegistration chest =
                    nextHomeMissionSupplyStorage(snapshot, null);
            // A distant registered chest may not be loaded yet. Its existing exact
            // workstation route loads it; unknown contents are never an empty observation.
            if (chest == null) chest = snapshot.primaryStorage();
            if (snapshot.home() == null || snapshot.generation() <= 0L || chest == null) {
                return checkpointHomeMissionSupplyWithoutChest(
                        planId, root, missionKind, goals, carried, cycle,
                        "no exact owned Home chest is available", nowMillis);
            }
            String table = homePinnedLedgerId(
                    snapshot, HomeEconomySession.AssetRole.CRAFTING_TABLE);
            String furnace = homePinnedLedgerId(
                    snapshot, HomeEconomySession.AssetRole.FURNACE);
            HomeMissionSupplyCodec.State bound = new HomeMissionSupplyCodec.State(
                    HomeMissionSupplyCodec.Phase.BOUND,
                    cycle,
                    resourceCatalogFingerprint,
                    snapshot.generation(),
                    snapshot.home().fingerprint(),
                    snapshot.home().dimension(),
                    snapshot.home().x(), snapshot.home().y(), snapshot.home().z(),
                    chest.ledgerAssetId(), table, furnace, false,
                    homeMissionSupplyRequestedCounts(goals), Map.of(), List.of(), Map.of(),
                    null, -1, "");
            LinkedHashMap<String, String> checkpoint = new LinkedHashMap<>(root.parameters());
            checkpoint.remove(HOME_MISSION_SUPPLY_PIN_AUTHORITY);
            copyHomeSnapshotBinding(snapshot, checkpoint);
            checkpoint.put(HOME_MISSION_SUPPLY_KEY, HomeMissionSupplyCodec.encode(bound));
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "bound exact Home generation and owned chest before direct sourcing",
                    nowMillis);
            return Outcome.continuePlan();
        }

        if (state.phase() == HomeMissionSupplyCodec.Phase.SKIPPED) return null;
        if (state.phase() == HomeMissionSupplyCodec.Phase.BLOCKED) {
            return Outcome.blocked("Home mission supply blocked: " + state.detail());
        }
        String bindingFailure = homeMissionSupplyBindingFailure(root, state);
        if (!bindingFailure.isBlank()) {
            return blockHomeMissionSupply(planId, root, state, bindingFailure, nowMillis);
        }
        if (!state.catalogFingerprint().equals(resourceCatalogFingerprint)) {
            return blockHomeMissionSupply(
                    planId, root, state,
                    "authenticated recipe catalog changed during Home sourcing",
                    nowMillis);
        }
        if (state.phase() == HomeMissionSupplyCodec.Phase.READY) return null;
        if (state.phase() == HomeMissionSupplyCodec.Phase.BOUND) {
            return checkpointHomeMissionSupply(
                    planId, root, state.withPhase(HomeMissionSupplyCodec.Phase.ROUTING), null,
                    "routing to the exact owned Home chest", nowMillis);
        }
        // Close/advance before asking the workstation to reopen anything. A second
        // chest cannot share the first chest's cursor, receipt, or speculative plan.
        if (state.phase() == HomeMissionSupplyCodec.Phase.CLOSING) {
            return finishHomeMissionSupplyStorage(planId, root, missionKind, goals, state, nowMillis);
        }

        HomeEconomySession.HomeAnchor home = HomeEconomySession.HomeAnchor.at(
                state.dimension(), state.homeX(), state.homeY(), state.homeZ());
        Outcome opened = ensureHomeMissionSupplyChestOpen(
                planId, state, home, lease, nowMillis);
        if (opened != null) {
            if (opened.state() == State.BLOCKED) {
                if (state.pendingTransfer() == null && state.allocatedCounts().isEmpty()
                        && nextHomeMissionSupplyStorage(homeEconomy.snapshot(), state) != null) {
                    return checkpointHomeMissionSupply(planId, root,
                            state.withAllocation(state.requestedCounts(), Map.of(), List.of(), false, -1), null,
                            "registered storage access unavailable; no contents inferred: " + opened.detail()
                                    + "; settling before another registered chest", nowMillis);
                }
                return blockHomeMissionSupply(
                        planId, root, state, opened.detail(), nowMillis);
            }
            return opened;
        }
        if (state.phase() == HomeMissionSupplyCodec.Phase.ROUTING) {
            return checkpointHomeMissionSupply(
                    planId, root, state.withPhase(HomeMissionSupplyCodec.Phase.OBSERVING), null,
                    "verified the exact owned Home chest screen", nowMillis);
        }
        if (state.phase() == HomeMissionSupplyCodec.Phase.OBSERVING) {
            return allocateHomeMissionSupply(
                    planId, root, missionKind, goals, state, home, nowMillis);
        }
        if (state.phase() == HomeMissionSupplyCodec.Phase.ALLOCATED) {
            return beginHomeMissionSupplyTransfer(planId, root, state, nowMillis);
        }
        if (state.phase() == HomeMissionSupplyCodec.Phase.TRANSFERRING) {
            return tickHomeMissionSupplyTransfer(planId, root, state, nowMillis);
        }
        return Outcome.blocked("unsupported Home mission supply phase " + state.phase());
    }

    private HomeEconomySession.StorageRegistration homeMissionSupplyStorage(
            HomeEconomySession.Snapshot snapshot, String ledgerId) {
        return snapshot.storageRegistrations().values().stream()
                .filter(storage -> storage.ledgerAssetId().equals(ledgerId)).findFirst().orElse(null);
    }

    private HomeEconomySession.StorageRegistration nextHomeMissionSupplyStorage(
            HomeEconomySession.Snapshot snapshot, HomeMissionSupplyCodec.State state) {
        if (snapshot.home() == null) return null;
        var valid = validHomeStorage(snapshot); // loaded exact geometry, overlapping halves counted once
        return snapshot.storageRegistrations().values().stream()
                .filter(storage -> valid.containsKey(storage.id()))
                .filter(storage -> state == null || (!storage.ledgerAssetId().equals(state.chestLedgerId())
                        && !state.visitedStorageLedgerIds().contains(storage.ledgerAssetId())))
                .sorted(Comparator.comparingInt((HomeEconomySession.StorageRegistration storage) ->
                        storage.equals(snapshot.primaryStorage()) ? 0 : 1)
                        .thenComparingLong(HomeEconomySession.StorageRegistration::registeredAtMillis)
                        .thenComparing(HomeEconomySession.StorageRegistration::id))
                .findFirst().orElse(null);
    }

    private Outcome finishHomeMissionSupplyStorage(String planId, PlanFrame root,
            HomeMissionSupplyAllocator.MissionKind missionKind, List<AcquisitionRequest.ItemGoal> goals,
            HomeMissionSupplyCodec.State state, long nowMillis) throws IOException {
        ClientInventoryController.SafeCloseResult close = inventory.closeHandledScreenIfCursorEmpty(nowMillis);
        if (close == ClientInventoryController.SafeCloseResult.CURSOR_OCCUPIED) {
            return blockHomeMissionSupply(planId, root, state,
                    "Home chest cursor is occupied after all selected withdrawals", nowMillis);
        }
        if (close != ClientInventoryController.SafeCloseResult.CLOSED || !inventory.cursorEmpty()
                || !inventory.transactionDiagnostics().pendingClickOwner().isBlank()
                || !inventory.transactionDiagnostics().cursorOwner().isBlank()) {
            return Outcome.running("settling the exact Home chest receipt and close before handoff",
                    Double.NaN, 0L, false);
        }
        ResourcePlanner.InventoryView carried = homeMissionSupplyCarriedView(missionKind, goals);
        boolean satisfied = homeMissionSupplyGoalsSatisfied(goals, carried.itemCounts());
        HomeEconomySession.StorageRegistration next = satisfied ? null
                : nextHomeMissionSupplyStorage(homeEconomy.snapshot(), state);
        if (next != null) {
            HomeMissionSupplyCodec.State advanced = state.nextStorage(next.ledgerAssetId());
            workstations.clear(homeMissionSupplyOperationId(planId, state));
            return checkpointHomeMissionSupply(planId, root, advanced, null,
                    "closed acknowledged storage " + state.chestLedgerId()
                            + "; observing registered storage " + next.id() + " before field acquisition", nowMillis);
        }

        Program fixed = null;
        boolean pinsUsed = false;
        if (!satisfied) {
            HomeEconomySession.HomeAnchor home = HomeEconomySession.HomeAnchor.at(
                    state.dimension(), state.homeX(), state.homeY(), state.homeZ());
            ArrayList<HomeMissionSupplyAllocator.VirtualPin> pins = new ArrayList<>();
            addVerifiedHomeMissionPin(pins, state.tableLedgerId(), "crafting_table",
                    WorkstationController.Kind.CRAFTING_TABLE, home);
            addVerifiedHomeMissionPin(pins, state.furnaceLedgerId(), "furnace",
                    WorkstationController.Kind.FURNACE, home);
            Map<String, Integer> durability = homeMissionSupplyUsablePickaxeDurability(carried.itemCounts());
            HomeMissionSupplyAllocator.Allocation finalAllocation = new HomeMissionSupplyAllocator(resourcePlanner)
                    .allocate(new HomeMissionSupplyAllocator.Request(missionKind, goals, carried,
                            durability, List.of(), pins, UnavailableHarvestSources.read(root.parameters())));
            if (finalAllocation.outcome() == HomeMissionSupplyAllocator.Outcome.BLOCKED) {
                return blockHomeMissionSupply(planId, root, state, finalAllocation.reason(), nowMillis);
            }
            pinsUsed = !finalAllocation.virtualPinsUsed().isEmpty();
            if (finalAllocation.fixedPointPlan().isPresent() && homeMissionSupplyRootUsesUniversalProgram(root)) {
                fixed = UniversalProgramCommitment.commit(resourceCatalogFingerprint,
                        finalAllocation.fixedPointPlan().orElseThrow(), PlanningBaseline.from(
                                homeMissionSupplyFixedBaseline(carried, finalAllocation, pins), durability));
            }
        }
        HomeMissionSupplyCodec.State ready = new HomeMissionSupplyCodec.State(
                HomeMissionSupplyCodec.Phase.READY, state.cycleFingerprint(), state.catalogFingerprint(),
                state.homeGeneration(), state.homeFingerprint(), state.dimension(), state.homeX(), state.homeY(), state.homeZ(),
                state.chestLedgerId(), state.tableLedgerId(), state.furnaceLedgerId(), pinsUsed,
                state.requestedCounts(), state.allocatedCounts(), state.withdrawals(), state.withdrawnCounts(),
                null, state.resolvedAllCount(), "", state.visitedStorageLedgerIds());
        workstations.clear(homeMissionSupplyOperationId(planId, state));
        return checkpointHomeMissionSupply(planId, root, ready, fixed,
                satisfied ? "Home supply finished: requested cargo is physically carried and chest is closed"
                        : "available registered storage exhausted; field plan uses fresh carried inventory"
                                + " (unavailable storage contents remain unknown)", nowMillis);
    }

    static void observeWaterAcquisitionBeforeHomeSupply(boolean legitimate,
            List<AcquisitionRequest.ItemGoal> goals, Runnable observeExistingSelector) {
        if (legitimate && goals.stream().anyMatch(goal -> simple(goal.item()).equals("water_bucket"))) {
            observeExistingSelector.run();
        }
    }

    private List<AcquisitionRequest.ItemGoal> homeMissionSupplyGoals(PlanFrame root) {
        String supplies = root.parameters().getOrDefault("deliverySuppliesHomeGoals", "");
        if (!supplies.isBlank()) return parseCounts(supplies).entrySet().stream()
                .map(e -> new AcquisitionRequest.ItemGoal(e.getKey(), e.getValue())).toList();
        return switch (root.kind()) {
            case "root.acquire", "root.food.acquire" -> List.of(
                    new AcquisitionRequest.ItemGoal(root.target(), Math.toIntExact(root.count())));
            case "root.bring" -> {
                int delivered = integer(root.parameters(), "deliveredTotal", 0);
                int remaining = Math.max(0, Math.toIntExact(root.count()) - delivered);
                if (remaining == 0) yield List.of();
                int batch = integer(root.parameters(), "deliveryBatch", 0);
                if (batch < 1) {
                    batch = DeliveryPolicy.bringBatchSize(
                            remaining, inventoryStackLimit(root.target()));
                }
                yield List.of(new AcquisitionRequest.ItemGoal(root.target(), batch));
            }
            case "root.food.bring" -> {
                int delivered = integer(root.parameters(), "deliveredTotal", 0);
                int remaining = Math.max(0, Math.toIntExact(root.count()) - delivered);
                yield remaining == 0 ? List.of() : List.of(
                        new AcquisitionRequest.ItemGoal(root.target(), remaining));
            }
            case "root.batch.acquire", "root.batch.bring" ->
                    parseBatchItems(required(root.parameters(), "batchItems")).stream()
                            .map(item -> new AcquisitionRequest.ItemGoal(item.item(), item.count()))
                            .toList();
            case "root.gear" -> GearProfile.forTier(root.target()).acquire().stream()
                    .map(item -> new AcquisitionRequest.ItemGoal(item, 1))
                    .toList();
            case "root.farm" -> ManagedFarmPolicy.crops().stream()
                    .map(crop -> new AcquisitionRequest.ItemGoal(crop.replant(), 1))
                    .toList();
            default -> List.of();
        };
    }

    private Map<String, Integer> homeMissionSupplyRequestedCounts(
            List<AcquisitionRequest.ItemGoal> goals) {
        LinkedHashMap<String, Integer> requested = new LinkedHashMap<>();
        for (AcquisitionRequest.ItemGoal goal : goals) {
            String item = FoodFamilyPolicy.isFamilyRequest(goal.item())
                    ? FoodFamilyPolicy.FAMILY_ITEM
                    : resourcePlanner.catalog().resolveUniversalItem(goal.item());
            requested.merge(item, goal.count(), Math::addExact);
        }
        return Map.copyOf(requested);
    }

    private String homeMissionSupplyCycleFingerprint(
            String planId,
            PlanFrame root,
            List<AcquisitionRequest.ItemGoal> goals) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            homeSupplyDigestPut(digest, "entity2-home-mission-supply-cycle-v1");
            homeSupplyDigestPut(digest, planId);
            homeSupplyDigestPut(digest, root.id());
            homeSupplyDigestPut(digest, root.kind());
            homeSupplyDigestPut(digest, root.target());
            homeSupplyDigestPut(digest, Long.toString(root.count()));
            homeSupplyDigestPut(digest, resourceCatalogFingerprint);
            // A prepared personal-supply cycle is stable while partial delivery receipts
            // arrive. Only the next explicit delivery trip binds fresh absolute goals.
            homeSupplyDigestPut(digest, root.parameters().getOrDefault("deliverySuppliesHomeDeliveredBase",
                    root.parameters().getOrDefault("deliveredTotal", "0")));
            for (AcquisitionRequest.ItemGoal goal : goals) {
                homeSupplyDigestPut(digest, goal.item());
                homeSupplyDigestPut(digest, Integer.toString(goal.count()));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void homeSupplyDigestPut(MessageDigest digest, String value) {
        byte[] encoded = Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
        digest.update(encoded);
    }

    private ResourcePlanner.InventoryView homeMissionSupplyCarriedView(
            HomeMissionSupplyAllocator.MissionKind missionKind,
            List<AcquisitionRequest.ItemGoal> goals) {
        ResourcePlanner.InventoryView observed = EmergencyTechniqueReservationPolicy.includeRetainedGoalWater(
                planningInventoryView(), canonicalInventorySnapshot(), goals, reservations);
        if (missionKind == HomeMissionSupplyAllocator.MissionKind.GET
                && goals.stream().anyMatch(goal -> FoodFamilyPolicy.isFamilyRequest(goal.item()))) {
            observed = new ResourcePlanner.InventoryView(FoodFamilyPolicy.withObservedReadyFood(
                    observed.itemCounts(), inventoryViewWithoutStations()), observed.equippedItems(),
                    observed.locallyAvailableItems(), observed.availableStations());
        }
        LinkedHashMap<String, Integer> spendable = new LinkedHashMap<>();
        observed.itemCounts().forEach((raw, count) -> {
            if (count == null || count <= 0) return;
            spendable.merge(
                    resourcePlanner.catalog().normalizeExactItem(raw), count, Math::addExact);
        });
        // Home stock values are replenishment targets, not hidden ownership locks. An explicit
        // owner mission may consume carried cobblestone, logs, food or tools. True emergency
        // reservations remain at their actual actuators (food recovery, clutch bucket and mission
        // throwaway projection) instead of deleting ordinary inventory from the planner's view.
        LinkedHashSet<String> deliveryFinals = new LinkedHashSet<>();
        if (missionKind == HomeMissionSupplyAllocator.MissionKind.BRING) {
            for (AcquisitionRequest.ItemGoal goal : goals) {
                if (!FoodFamilyPolicy.isFamilyRequest(goal.item())) {
                    deliveryFinals.add(resourcePlanner.catalog().resolveUniversalItem(goal.item()));
                }
            }
        }
        Map<String, Integer> withReusablePickaxes =
                HomeMissionSupplyAllocator.retainReusablePickaxeCapabilities(
                        spendable, serviceablePickaxeCounts(), deliveryFinals);
        spendable.clear();
        spendable.putAll(withReusablePickaxes);
        LinkedHashSet<String> equipped = new LinkedHashSet<>();
        for (String item : observed.equippedItems()) {
            String exact = resourcePlanner.catalog().normalizeExactItem(item);
            if (spendable.getOrDefault(exact, 0) > 0) equipped.add(exact);
        }
        return new ResourcePlanner.InventoryView(
                Map.copyOf(spendable), Set.copyOf(equipped),
                observed.locallyAvailableItems(), observed.availableStations());
    }

    private Map<String, Integer> serviceablePickaxeCounts() {
        if (client.player == null) return Map.of();
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (ItemStack stack : client.player.getInventory()) {
            if (stack.isEmpty() || remainingDurability(stack) <= 1) continue;
            String item = simple(Registries.ITEM.getId(stack.getItem()).toString());
            if (PICKAXE_RANKS.containsKey(item)) {
                result.merge(item, stack.getCount(), Math::addExact);
            }
        }
        return Map.copyOf(result);
    }

    private boolean homeMissionSupplyGoalsSatisfied(
            List<AcquisitionRequest.ItemGoal> goals,
            Map<String, Integer> counts) {
        for (AcquisitionRequest.ItemGoal goal : goals) {
            if (FoodFamilyPolicy.isFamilyRequest(goal.item())) {
                if (FoodFamilyPolicy.summary(counts).readyCount() < goal.count()) return false;
                continue;
            }
            String exact = resourcePlanner.catalog().resolveUniversalItem(goal.item());
            int present = counts.entrySet().stream()
                    .filter(entry -> resourcePlanner.catalog().normalizeExactItem(entry.getKey())
                            .equals(exact))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
            if (present < goal.count()) return false;
        }
        return true;
    }

    private static String homePinnedLedgerId(
            HomeEconomySession.Snapshot snapshot,
            HomeEconomySession.AssetRole role) {
        HomeEconomySession.PinnedAsset asset = snapshot.pinnedAssets().get(role);
        return asset == null ? "" : asset.ledgerAssetId();
    }

    private static void copyHomeSnapshotBinding(
            HomeEconomySession.Snapshot snapshot,
            Map<String, String> destination) {
        for (HomeEconomySession.AssetRole role : HomeEconomySession.AssetRole.values()) {
            destination.remove(homeLedgerParameter(role));
        }
        destination.put("homeGeneration", Long.toString(snapshot.generation()));
        destination.put("homeFingerprint", snapshot.home().fingerprint());
        destination.put("homeDimension", snapshot.home().dimension());
        destination.put("homeX", Integer.toString(snapshot.home().x()));
        destination.put("homeY", Integer.toString(snapshot.home().y()));
        destination.put("homeZ", Integer.toString(snapshot.home().z()));
        snapshot.pinnedAssets().forEach((role, asset) ->
                destination.put(homeLedgerParameter(role), asset.ledgerAssetId()));
        String unpinned = HomeEconomyPolicy.unpinnedAssetRolesAtPlanStart(snapshot).stream()
                .map(Enum::name)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        if (unpinned.isBlank()) destination.remove(HOME_UNPINNED_ASSET_ROLES);
        else destination.put(HOME_UNPINNED_ASSET_ROLES, unpinned);
    }

    private String homeMissionSupplyBindingFailure(
            PlanFrame root,
            HomeMissionSupplyCodec.State state) {
        if (homeEconomy == null) return homeEconomyLoadFailure;
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        if (snapshot.generation() != state.homeGeneration()) {
            return "home_binding_changed: encoded Home generation no longer matches";
        }
        HomeBinding binding = resolveHomeBinding(root.parameters());
        if (!binding.valid()) return binding.detail();
        if (binding.home() == null
                || !binding.home().fingerprint().equals(state.homeFingerprint())
                || !binding.home().dimension().equals(state.dimension())
                || binding.home().x() != state.homeX()
                || binding.home().y() != state.homeY()
                || binding.home().z() != state.homeZ()) {
            return "home_binding_changed: encoded Home anchor no longer matches its root binding";
        }
        HomeEconomySession.StorageRegistration storage = homeMissionSupplyStorage(snapshot, state.chestLedgerId());
        if (storage == null) {
            return "home_binding_changed: exact mission storage is no longer registered";
        }
        if (state.pendingTransfer() != null && state.pendingTransfer().containerIdentity() != null
                && !storage.id().equals(state.pendingTransfer().containerIdentity().storageId())) {
            return "home_binding_changed: pending withdrawal belongs to another registered storage";
        }
        if (!state.tableLedgerId().equals(Objects.requireNonNullElse(
                binding.ledgerIds().get(HomeEconomySession.AssetRole.CRAFTING_TABLE), ""))) {
            return "home_binding_changed: exact Home crafting-table ledger pin changed";
        }
        if (!state.furnaceLedgerId().equals(Objects.requireNonNullElse(
                binding.ledgerIds().get(HomeEconomySession.AssetRole.FURNACE), ""))) {
            return "home_binding_changed: exact Home furnace ledger pin changed";
        }
        return "";
    }

    private Outcome ensureHomeMissionSupplyChestOpen(
            String planId,
            HomeMissionSupplyCodec.State state,
            HomeEconomySession.HomeAnchor home,
            ControlLease lease,
            long nowMillis) throws IOException {
        return ensureHomeMissionSupplyChestOpen(
                planId, state, home, lease, nowMillis, null);
    }

    private Outcome ensureHomeMissionSupplyChestOpen(
            String planId,
            HomeMissionSupplyCodec.State state,
            HomeEconomySession.HomeAnchor home,
            ControlLease lease,
            long nowMillis,
            HomeEconomySession.ContainerIdentity rootOwnedIdentity) throws IOException {
        String operationId = homeMissionSupplyOperationId(planId, state);
        HomeEconomySession.Snapshot snapshot = homeEconomy.snapshot();
        HomeEconomySession.StorageRegistration storage = homeMissionSupplyStorage(snapshot, state.chestLedgerId());
        if (storage == null) return Outcome.blocked("the mission storage is no longer registered");
        var geometry = workstations.observeHomeStorageIdentity(storage.id(), storage.ledgerAssetId(), home, HOME_ASSET_RADIUS);
        var valid = validHomeStorage(snapshot);
        if (geometry.isPresent() && !valid.containsKey(storage.id())) {
            return Outcome.blocked("registered chest overlaps another registered container; no duplicate inventory access");
        }
        HomeMissionSupplyCodec.PendingTransfer pending = state.pendingTransfer();
        HomeEconomySession.ContainerIdentity requiredIdentity = rootOwnedIdentity != null
                ? rootOwnedIdentity
                : pending == null ? null : pending.containerIdentity();
        if (requiredIdentity != null && !storage.id().equals(requiredIdentity.storageId())) {
            return Outcome.blocked(
                    "pending Home transfer belongs to another registered storage");
        }
        inventory.reconcilePendingTransaction(nowMillis);
        if (pending == null && rootOwnedIdentity == null) {
            var diagnostics = inventory.transactionDiagnostics();
            if (!inventory.cursorEmpty() || !diagnostics.pendingClickOwner().isBlank()
                    || !diagnostics.cursorOwner().isBlank() || diagnostics.pendingRemovals() > 0) {
                return Outcome.running("settling the last inventory acknowledgement before releasing mission container identity",
                        Double.NaN, 0L, false);
            }
        }
        if (pending != null && pending.containerIdentity() == null) {
            if (!storage.equals(snapshot.primaryStorage()) || geometry.isEmpty()
                    || geometry.orElseThrow().halves().size() != 1) {
                return Outcome.blocked("legacy mission transfer requires its original single primary chest; changed or unknown layout cannot reconcile");
            }
        }
        workstations.requirePinnedStorageIdentity(operationId, requiredIdentity);
        if (workstations.exactPinnedScreenOpen(
                WorkstationController.Kind.CHEST,
                operationId,
                state.chestLedgerId(),
                home,
                HOME_ASSET_RADIUS)) return null;
        if (!inventory.cursorEmpty() || !inventory.transactionDiagnostics().pendingClickOwner().isBlank()) {
            return Outcome.blocked(
                    "Home mission supply cannot open its owned chest while the cursor is occupied");
        }
        Optional<ActionLease> action = acquireHomeAction(
                lease,
                ActionOwner.WORKSTATION_INTERACTION,
                operationId,
                lastClientTick,
                nowMillis);
        if (action.isEmpty()) {
            return Outcome.running(
                    "arming the exact owned Home chest interaction",
                    Double.NaN, 0L, false);
        }
        baritone.bindMovementAction(
                executionKernel, lease, action.orElseThrow(), lastClientTick, nowMillis);
        WorkstationController.Result opened = workstations.tickOpenPinned(
                WorkstationController.Kind.CHEST,
                operationId,
                state.chestLedgerId(),
                home,
                HOME_ASSET_RADIUS,
                lease,
                baritone,
                nowMillis);
        if (opened.state() == WorkstationController.State.OPEN) return null;
        if (opened.state() == WorkstationController.State.BLOCKED
                || opened.state() == WorkstationController.State.ITEM_MISSING
                || opened.state() == WorkstationController.State.DROP_MISSING) {
            return Outcome.blocked("owned Home chest is unavailable: " + opened.detail());
        }
        return Outcome.running(opened.detail(), Double.NaN, 0L, false);
    }

    private static String homeMissionSupplyOperationId(
            String planId,
            HomeMissionSupplyCodec.State state) {
        return "home:mission-supply:" + planId + ':'
                + state.cycleFingerprint().substring(0, 16) + ':' + state.chestLedgerId();
    }

    private Outcome allocateHomeMissionSupply(
            String planId,
            PlanFrame root,
            HomeMissionSupplyAllocator.MissionKind missionKind,
            List<AcquisitionRequest.ItemGoal> goals,
            HomeMissionSupplyCodec.State state,
            HomeEconomySession.HomeAnchor home,
            long nowMillis) throws IOException {
        if (!workstations.exactPinnedScreenOpen(
                WorkstationController.Kind.CHEST,
                homeMissionSupplyOperationId(planId, state),
                state.chestLedgerId(), home, HOME_ASSET_RADIUS)) {
            return Outcome.running(
                    "reopening the exact owned Home chest before allocation",
                    Double.NaN, 0L, false);
        }
        if (!inventory.cursorEmpty()) {
            return blockHomeMissionSupply(
                    planId, root, state,
                    "Home chest cursor is occupied before allocation", nowMillis);
        }
        if (homeMissionSupplyExactIdentity(planId, state).isEmpty()) {
            return blockHomeMissionSupply(planId, root, state,
                    "Home allocation requires the exact registered connected container", nowMillis);
        }
        List<ClientInventoryController.HomeChestStack> observed =
                inventory.homeChestPristineStacks();
        List<HomeMissionSupplyAllocator.HomeStack> stacks = observed.stream()
                .map(ClientInventoryController.HomeChestStack::missionSupplyStack)
                .toList();
        ArrayList<HomeMissionSupplyAllocator.VirtualPin> pins = new ArrayList<>();
        addVerifiedHomeMissionPin(
                pins, state.tableLedgerId(), "crafting_table",
                WorkstationController.Kind.CRAFTING_TABLE, home);
        addVerifiedHomeMissionPin(
                pins, state.furnaceLedgerId(), "furnace",
                WorkstationController.Kind.FURNACE, home);
        ResourcePlanner.InventoryView carried = homeMissionSupplyCarriedView(
                missionKind, goals);
        Map<String, Integer> carriedDurability =
                homeMissionSupplyUsablePickaxeDurability(carried.itemCounts());
        HomeMissionSupplyAllocator.Allocation allocation =
                new HomeMissionSupplyAllocator(resourcePlanner).allocate(
                        new HomeMissionSupplyAllocator.Request(
                                missionKind, goals, carried, carriedDurability, stacks, pins,
                                UnavailableHarvestSources.read(root.parameters())));
        if (allocation.outcome() == HomeMissionSupplyAllocator.Outcome.BLOCKED) {
            return blockHomeMissionSupply(
                    planId, root, state, allocation.reason(), nowMillis);
        }

        LinkedHashMap<String, ClientInventoryController.HomeChestStack> stackById =
                new LinkedHashMap<>();
        for (ClientInventoryController.HomeChestStack stack : observed) {
            stackById.put(stack.stackId(), stack);
        }
        ArrayList<HomeMissionSupplyCodec.Withdrawal> withdrawals = new ArrayList<>();
        for (HomeMissionSupplyAllocator.StackWithdrawal selected : allocation.withdrawals()) {
            ClientInventoryController.HomeChestStack stack = stackById.get(selected.stackId());
            if (stack == null || selected.count() > stack.count()
                    || !resourcePlanner.catalog().normalizeExactItem(stack.itemId()).equals(
                    resourcePlanner.catalog().normalizeExactItem(selected.item()))) {
                return blockHomeMissionSupply(
                        planId, root, state,
                        "selected Home stack changed before its allocation was checkpointed",
                        nowMillis);
            }
            withdrawals.add(new HomeMissionSupplyCodec.Withdrawal(
                    selected.stackId(), stack.sourceSlot(), selected.item(),
                    selected.count(), selected.count()));
        }
        boolean pinsUsed = !allocation.virtualPinsUsed().isEmpty();
        HomeMissionSupplyCodec.State allocated = state.withAllocation(
                state.requestedCounts(), allocation.allocatedCounts(), withdrawals,
                pinsUsed, -1);

        Program fixedProgram = null;
        if ((allocation.outcome() == HomeMissionSupplyAllocator.Outcome.READY
                || allocation.outcome() == HomeMissionSupplyAllocator.Outcome.NO_HOME_SUPPLY)
                && allocation.fixedPointPlan().isPresent()
                && homeMissionSupplyRootUsesUniversalProgram(root)) {
            ResourcePlanner.InventoryView baseline = homeMissionSupplyFixedBaseline(
                    carried, allocation, pins);
            Map<String, Integer> durability = new LinkedHashMap<>(carriedDurability);
            for (HomeMissionSupplyCodec.Withdrawal withdrawal : withdrawals) {
                ClientInventoryController.HomeChestStack stack =
                        stackById.get(withdrawal.stackId());
                int selectedDurability = stack == null ? 0
                        : stack.missionSupplyPickaxeDurability(withdrawal.totalCount());
                if (selectedDurability > 0) {
                    durability.merge(
                            resourcePlanner.catalog().normalizeExactItem(stack.itemId()),
                            selectedDurability, Math::addExact);
                }
            }
            fixedProgram = UniversalProgramCommitment.commit(
                    resourceCatalogFingerprint,
                    allocation.fixedPointPlan().orElseThrow(),
                    PlanningBaseline.from(baseline, Map.copyOf(durability)));
        }
        return checkpointHomeMissionSupply(
                planId, root, allocated, fixedProgram,
                allocation.outcome() == HomeMissionSupplyAllocator.Outcome.READY
                        ? "persisted the minimal fixed-point Home allocation before any click"
                        : "observed no eligible Home stock; closing without a transfer",
                nowMillis);
    }

    private Outcome checkpointHomeMissionSupplyWithoutChest(
            String planId,
            PlanFrame root,
            HomeMissionSupplyAllocator.MissionKind missionKind,
            List<AcquisitionRequest.ItemGoal> goals,
            ResourcePlanner.InventoryView carried,
            String cycle,
            String reason,
            long nowMillis) throws IOException {
        Map<String, Integer> durability =
                homeMissionSupplyUsablePickaxeDurability(carried.itemCounts());
        HomeMissionSupplyAllocator.Allocation allocation =
                new HomeMissionSupplyAllocator(resourcePlanner).allocate(
                        new HomeMissionSupplyAllocator.Request(
                                missionKind, goals, carried, durability,
                                List.of(), List.of(), UnavailableHarvestSources.read(root.parameters())));
        if (allocation.outcome() == HomeMissionSupplyAllocator.Outcome.BLOCKED) {
            return Outcome.blocked(
                    "owner-authorized field planning failed: " + allocation.reason());
        }
        Program fixed = null;
        if (allocation.fixedPointPlan().isPresent()
                && homeMissionSupplyRootUsesUniversalProgram(root)) {
            fixed = UniversalProgramCommitment.commit(
                    resourceCatalogFingerprint,
                    allocation.fixedPointPlan().orElseThrow(),
                    PlanningBaseline.from(carried, durability));
        }
        return checkpointHomeMissionSupply(
                planId, root, HomeMissionSupplyCodec.State.skipped(cycle, -1), fixed,
                "Home source skipped: " + reason
                        + "; committed owner-authorized field plan where applicable",
                nowMillis);
    }

    private boolean homeMissionSupplyRootUsesUniversalProgram(PlanFrame root) {
        return root.kind().equals("root.gear") || usesCommittedUniversalProgram(root);
    }

    private void addVerifiedHomeMissionPin(
            List<HomeMissionSupplyAllocator.VirtualPin> destination,
            String ledgerId,
            String item,
            WorkstationController.Kind kind,
            HomeEconomySession.HomeAnchor home) {
        if (ledgerId == null || ledgerId.isBlank()) return;
        WorkstationController.HomeAssetDiagnostic diagnostic =
                workstations.diagnosePinnedHomeAsset(
                        kind, ledgerId, home, HOME_ASSET_RADIUS);
        if (diagnostic.truth() == WorkstationController.HomeAssetLiveTruth.EXACT_VERIFIED) {
            destination.add(new HomeMissionSupplyAllocator.VirtualPin(
                    ledgerId, item, true));
        }
    }

    private Map<String, Integer> homeMissionSupplyUsablePickaxeDurability(
            Map<String, Integer> spendableCounts) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (String pickaxe : PICKAXE_RANKS.keySet()) {
            int count = spendableCounts.entrySet().stream()
                    .filter(entry -> simple(entry.getKey()).equals(pickaxe))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
            if (count <= 0) continue;
            List<Integer> durabilities = exactPickaxeDurabilities(pickaxe).stream()
                    .filter(value -> value > 1)
                    .sorted()
                    .limit(count)
                    .toList();
            int total = durabilities.stream()
                    .mapToInt(MiningToolSegmentPolicy::usableWorkDurability)
                    .sum();
            if (total > 0) result.put(pickaxe, total);
        }
        return Map.copyOf(result);
    }

    private ResourcePlanner.InventoryView homeMissionSupplyFixedBaseline(
            ResourcePlanner.InventoryView carried,
            HomeMissionSupplyAllocator.Allocation allocation,
            List<HomeMissionSupplyAllocator.VirtualPin> pins) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>(carried.itemCounts());
        allocation.allocatedCounts().forEach((item, count) ->
                counts.merge(item, count, Math::addExact));
        LinkedHashSet<String> stations = new LinkedHashSet<>(carried.availableStations());
        Set<String> used = allocation.virtualPinsUsed();
        pins.stream().filter(pin -> used.contains(pin.pinId()))
                .forEach(pin -> stations.add(pin.item()));
        return new ResourcePlanner.InventoryView(
                Map.copyOf(counts), carried.equippedItems(),
                carried.locallyAvailableItems(), Set.copyOf(stations));
    }

    private Outcome beginHomeMissionSupplyTransfer(
            String planId,
            PlanFrame root,
            HomeMissionSupplyCodec.State state,
            long nowMillis) throws IOException {
        HomeMissionSupplyCodec.Withdrawal next = state.withdrawals().stream()
                .filter(withdrawal -> withdrawal.remainingCount() > 0)
                .findFirst()
                .orElse(null);
        if (next == null) {
            return checkpointHomeMissionSupply(
                    planId, root,
                    state.withPhase(HomeMissionSupplyCodec.Phase.CLOSING), null,
                    "all exact Home stack prefixes are acknowledged", nowMillis);
        }
        var identity = homeMissionSupplyExactIdentity(planId, state);
        if (identity.isEmpty()) {
            return blockHomeMissionSupply(planId, root, state,
                    "exact registered container identity changed before withdrawal", nowMillis);
        }
        Optional<ClientInventoryController.HomeTransferPlan> planned =
                inventory.planExactHomeChestWithdrawal(
                        next.sourceSlot(), next.item(), next.remainingCount());
        if (planned.isEmpty()) {
            boolean sourceStillPresent = inventory.homeChestPristineStacks().stream()
                    .anyMatch(stack -> stack.sourceSlot() == next.sourceSlot()
                            && resourcePlanner.catalog().normalizeExactItem(stack.itemId()).equals(
                                    resourcePlanner.catalog().normalizeExactItem(next.item())) && stack.count() > 0);
            Outcome reobserved = reobserveChangedHomeMissionSupply(planId, root, state,
                    "selected Home stack or destination capacity changed before transfer", nowMillis);
            return sourceStillPresent && reobserved.state() == State.CONTINUE_PLAN
                    ? Outcome.capacity("making room before observing the remaining Home supplies") : reobserved;
        }
        ClientInventoryController.HomeTransferPlan plan = planned.orElseThrow();
        String transferId = state.cycleFingerprint().substring(0, 16) + ':'
                + state.visitedStorageLedgerIds().size() + ':' + next.stackId() + ':' + next.remainingCount()
                + ':' + java.util.UUID.randomUUID();
        String owner = HOME_MISSION_SUPPLY_OWNER_PREFIX + transferId;
        HomeMissionSupplyCodec.PendingTransfer pending =
                new HomeMissionSupplyCodec.PendingTransfer(
                        transferId, owner, next.stackId(), next.item(), plan.count(),
                        plan.syncId(), plan.sourceSlot(), plan.destinationSlot(),
                        plan.destinationCountBefore(), plan.playerCountBefore(),
                        plan.chestCountBefore(), nowMillis, identity.orElseThrow());
        return checkpointHomeMissionSupply(
                planId, root, state.beginTransfer(pending), null,
                "persisted exact Home withdrawal intent before its first click", nowMillis);
    }

    private Outcome tickHomeMissionSupplyTransfer(
            String planId,
            PlanFrame root,
            HomeMissionSupplyCodec.State state,
            long nowMillis) throws IOException {
        if (!inventory.homeTransferClicksSettled(nowMillis))
            return Outcome.running("waiting for exact Home withdrawal click acknowledgement", Double.NaN, 0L, false);
        HomeMissionSupplyCodec.PendingTransfer pending = state.pendingTransfer();
        var identity = homeMissionSupplyExactIdentity(planId, state);
        if (identity.isEmpty() || (pending.containerIdentity() != null
                && !pending.containerIdentity().equals(identity.orElseThrow()))) {
            return blockHomeMissionSupply(planId, root, state,
                    "pending withdrawal cannot reconcile against a different or unknown container", nowMillis);
        }
        ClientInventoryController.HomeTransferPlan plan = homeMissionSupplyTransferPlan(pending);
        ClientInventoryController.HomeTransferObservation observed =
                inventory.observeHomeTransfer(plan);
        if (observed.syncId() < 0) {
            return Outcome.running(
                    "reopening the exact Home chest to reconcile its pending withdrawal",
                    Double.NaN, 0L, false);
        }
        HomeEconomyPolicy.TransferResolution resolution =
                HomeEconomyPolicy.reconcileTransfer(
                        homeMissionSupplyPolicyTransfer(pending),
                        new HomeEconomyPolicy.TransferObservation(
                                observed.syncId(), observed.cursorEmpty(),
                                inventory.hasExactHomeTransferCustody(
                                        plan, pending.stableOwner()),
                                observed.playerItemCount(), observed.chestItemCount()));
        switch (resolution.truth()) {
            case COMMITTED, PARTIAL_COMMITTED -> {
                if (!inventory.releaseSettledHomeTransferCustody(
                        plan, pending.stableOwner())) {
                    return blockHomeMissionSupply(
                            planId, root, state,
                            "settled Home withdrawal retained ambiguous cursor custody",
                            nowMillis);
                }
                return checkpointHomeMissionSupply(
                        planId, root, state.commitTransfer(resolution.movedCount()), null,
                        resolution.detail(), nowMillis);
            }
            case REBIND_REQUIRED -> {
                if (!inventory.releaseSettledHomeTransferCustody(plan, pending.stableOwner())) {
                    return blockHomeMissionSupply(planId, root, state,
                            "unchanged reopened withdrawal still has unresolved cursor custody", nowMillis);
                }
                var replanned = inventory.planExactHomeChestWithdrawal(
                        pending.sourceSlot(), pending.item(), pending.count());
                if (replanned.isEmpty()) {
                    return reobserveChangedHomeMissionSupply(planId, root, state,
                            "original withdrawal stack or receiving capacity changed while reopening", nowMillis);
                }
                var replacement = replanned.orElseThrow();
                HomeMissionSupplyCodec.PendingTransfer rebound = new HomeMissionSupplyCodec.PendingTransfer(
                        pending.id(), pending.stableOwner(), pending.stackId(), pending.item(), replacement.count(),
                        replacement.syncId(), replacement.sourceSlot(), replacement.destinationSlot(),
                        replacement.destinationCountBefore(), replacement.playerCountBefore(),
                        replacement.chestCountBefore(), nowMillis, identity.orElseThrow());
                return checkpointHomeMissionSupply(
                        planId, root, state.rebaseTransfer(rebound), null,
                        resolution.detail(), nowMillis);
            }
            case CURSOR_OCCUPIED -> {
                return blockHomeMissionSupply(
                        planId, root, state, resolution.detail(), nowMillis);
            }
            case DIVERGED -> {
                return reobserveChangedHomeMissionSupply(planId, root, state, resolution.detail(), nowMillis);
            }
            case PENDING -> {
                // Continue below. The exact before-counts or owned cursor remain authoritative.
            }
        }

        ClientInventoryController.HomeTransferResult result =
                inventory.homeChestTransferTick(plan, pending.stableOwner(), nowMillis);
        return switch (result) {
            case COMPLETE -> {
                if (!inventory.releaseSettledHomeTransferCustody(
                        plan, pending.stableOwner())) {
                    yield blockHomeMissionSupply(
                            planId, root, state,
                            "completed Home withdrawal retained ambiguous cursor custody",
                            nowMillis);
                }
                yield checkpointHomeMissionSupply(
                        planId, root, state.commitTransfer(pending.count()), null,
                        "observed exact player/chest count deltas for the selected stack prefix",
                        nowMillis);
            }
            case CLICKED, WAITING -> Outcome.running(
                    "waiting for exact Home withdrawal acknowledgement",
                    Double.NaN, 0L, false);
            case NEEDS_OWNED_CHEST, STALE_HANDLER -> Outcome.running(
                    "reopening the exact owned Home chest without issuing another click",
                    Double.NaN, 0L, false);
            case SLOT_CHANGED, SOURCE_CHANGED, DESTINATION_CHANGED, COUNTS_DIVERGED ->
                    reobserveChangedHomeMissionSupply(planId, root, state,
                            "Home contents changed: " + result.name().toLowerCase(Locale.ROOT), nowMillis);
            case CURSOR_NOT_OWNED -> blockHomeMissionSupply(
                    planId, root, state,
                    "Home withdrawal failed closed: "
                            + result.name().toLowerCase(Locale.ROOT),
                    nowMillis);
        };
    }

    private Outcome reobserveChangedHomeMissionSupply(String planId, PlanFrame root,
            HomeMissionSupplyCodec.State state, String reason, long nowMillis) throws IOException {
        var identity = homeMissionSupplyExactIdentity(planId, state);
        var pending = state.pendingTransfer();
        if (identity.isEmpty() || (pending != null && pending.containerIdentity() != null
                && !pending.containerIdentity().equals(identity.orElseThrow()))) {
            return blockHomeMissionSupply(planId, root, state,
                    "changed Home contents belong to an unverified container", nowMillis);
        }
        inventory.reconcilePendingTransaction(nowMillis);
        if (pending != null) {
            var settled = inventory.settleChangedHomeTransfer(homeMissionSupplyTransferPlan(pending),
                    pending.stableOwner(), nowMillis);
            if (settled == ClientInventoryController.HomeTransferSettlement.WAITING)
                return Outcome.running("settling owned cursor cargo before fresh Home observation", Double.NaN, 0L, false);
            if (settled != ClientInventoryController.HomeTransferSettlement.SETTLED)
                return blockHomeMissionSupply(planId, root, state,
                        "Home change cannot settle cursor safely: " + settled, nowMillis);
        }
        if (!inventory.cursorEmpty() || !inventory.transactionDiagnostics().pendingClickOwner().isBlank()
                || !inventory.transactionDiagnostics().cursorOwner().isBlank()) {
            return Outcome.running("settling Home inventory ownership before reallocating", Double.NaN, 0L, false);
        }
        logger.info("Home supply changed; settled exact custody and reobserving plan={} reason={}", planId, reason);
        return checkpointHomeMissionSupply(planId, root, state.reobserveSettledContents(true), null,
                "reobserving changed Home contents without crediting an unproved transfer: " + reason, nowMillis);
    }

    private static ClientInventoryController.HomeTransferPlan homeMissionSupplyTransferPlan(
            HomeMissionSupplyCodec.PendingTransfer pending) {
        return new ClientInventoryController.HomeTransferPlan(
                HomeStockPolicy.Direction.WITHDRAW,
                pending.item(), pending.count(), pending.syncId(),
                pending.sourceSlot(), pending.destinationSlot(),
                pending.playerCountBefore(), pending.chestCountBefore(),
                pending.destinationCountBefore());
    }

    private static HomeEconomySession.PendingTransfer homeMissionSupplyPolicyTransfer(
            HomeMissionSupplyCodec.PendingTransfer pending) {
        return new HomeEconomySession.PendingTransfer(
                pending.id(), HomeStockPolicy.Direction.WITHDRAW,
                "mission_supply", pending.item(), pending.count(), pending.syncId(),
                pending.sourceSlot(), pending.destinationSlot(),
                pending.destinationCountBefore(), pending.playerCountBefore(),
                pending.chestCountBefore(), pending.startedAtMillis(), pending.containerIdentity());
    }

    private Optional<HomeEconomySession.ContainerIdentity> homeMissionSupplyExactIdentity(
            String planId, HomeMissionSupplyCodec.State state) {
        if (homeEconomy == null) return Optional.empty();
        var snapshot = homeEconomy.snapshot();
        var storage = homeMissionSupplyStorage(snapshot, state.chestLedgerId());
        if (storage == null) return Optional.empty();
        var valid = validHomeStorage(snapshot);
        var expected = valid.get(storage.id());
        if (expected == null) return Optional.empty();
        return workstations.exactPinnedStorageIdentity(storage.id(), homeMissionSupplyOperationId(planId, state),
                storage.ledgerAssetId(), snapshot.home(), HOME_ASSET_RADIUS).filter(expected::equals);
    }

    private Outcome checkpointHomeMissionSupply(
            String planId,
            PlanFrame root,
            HomeMissionSupplyCodec.State state,
            Program fixedProgram,
            String detail,
            long nowMillis) throws IOException {
        LinkedHashMap<String, String> checkpoint = new LinkedHashMap<>(root.parameters());
        checkpoint.put(HOME_MISSION_SUPPLY_KEY, HomeMissionSupplyCodec.encode(state));
        if (state.pinnedStationsAllowed()
                && state.phase() != HomeMissionSupplyCodec.Phase.SKIPPED
                && state.phase() != HomeMissionSupplyCodec.Phase.BLOCKED) {
            checkpoint.put(HOME_MISSION_SUPPLY_PIN_AUTHORITY, "true");
        } else {
            checkpoint.remove(HOME_MISSION_SUPPLY_PIN_AUTHORITY);
        }
        if (fixedProgram != null) {
            checkpoint.put(UNIVERSAL_PROGRAM_KEY, UniversalProgramCodec.encode(fixedProgram));
        } else if ((state.phase() == HomeMissionSupplyCodec.Phase.BOUND
                && !state.visitedStorageLedgerIds().isEmpty())
                || state.phase() == HomeMissionSupplyCodec.Phase.READY
                || state.phase() == HomeMissionSupplyCodec.Phase.OBSERVING) {
            checkpoint.remove(UNIVERSAL_PROGRAM_KEY);
        }
        plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome blockHomeMissionSupply(
            String planId,
            PlanFrame root,
            HomeMissionSupplyCodec.State state,
            String detail,
            long nowMillis) throws IOException {
        String blocker = Objects.requireNonNullElse(detail, "Home mission supply blocked").trim();
        if (state.phase() != HomeMissionSupplyCodec.Phase.BLOCKED) {
            checkpointHomeMissionSupply(
                    planId, root, state.block(blocker), null,
                    "Home mission supply blocked: " + blocker, nowMillis);
        }
        return Outcome.blocked("Home mission supply blocked: " + blocker);
    }

    /** Returns null when this root has no pending pre-respawn cargo recovery. */
    private Outcome tickDeathDropRecoveryRoot(
            String planId,
            PlanFrame root,
            long nowMillis) throws IOException {
        if (!Boolean.parseBoolean(root.parameters().getOrDefault(
                "deathRecoveryPending", "false"))) return null;
        DeathDropRecoveryPolicy.Capture capture = deathCapture(root.parameters());
        DeathDropRecoveryPolicy.Decision decision = DeathDropRecoveryPolicy.assess(
                capture, canonicalInventorySnapshot(), currentDimension(), nowMillis);
        if (decision.action() != DeathDropRecoveryPolicy.Action.RECOVER) {
            Map<String, String> checkpoint = new LinkedHashMap<>(root.parameters());
            if (decision.action() != DeathDropRecoveryPolicy.Action.NONE_MISSING) {
                try {
                    invalidateUniversalProgramCheckpoint(
                            checkpoint,
                            DivergenceCause.INVENTORY_LOSS,
                            decision.detail() + "; " + decision.missingCount()
                                    + " captured item(s) are confirmed missing");
                } catch (IllegalArgumentException corrupt) {
                    return Outcome.blocked(
                            "durable universal program is corrupt: " + corrupt.getMessage());
                }
            }
            clearDeathRecoveryCheckpoint(checkpoint);
            plans.checkpointCurrent(
                    planId,
                    checkpoint,
                    decision.detail() + "; continuing from current inventory truth",
                    nowMillis);
            return Outcome.continuePlan();
        }

        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        copyPrefixedFacts(root.parameters(), parameters, "deathRecovery");
        parameters.put("description", "recover inventory dropped at the last death site");
        plans.pushPrerequisite(
                planId,
                new PlanFrame.Spec(
                        "recover_death_drops",
                        "inventory",
                        decision.missingCount(),
                        parameters),
                decision.detail(),
                nowMillis);
        return Outcome.continuePlan();
    }

    /**
     * Rehydrates the exact interrupted Paper transaction only after death drops have been handled.
     * A batch first recreates its nested bring/give root so delivery receipt accounting still has
     * the same ancestor structure it had before death.
     */
    private Outcome tickInterruptedHandoffRoot(
            String planId,
            PlanFrame root,
            long nowMillis) throws IOException {
        if (!Boolean.parseBoolean(root.parameters().getOrDefault(
                "deathHandoffPending", "false"))) return null;
        if (root.kind().startsWith("root.batch.")
                || root.kind().equals("root.food.bring")
                || root.kind().equals("root.food.give")) {
            if (root.detail().startsWith("resumed after root.")) {
                Map<String, String> checkpoint = new LinkedHashMap<>(root.parameters());
                clearDeathHandoffCheckpoint(checkpoint);
                plans.checkpointCurrent(
                        planId, checkpoint,
                        "finished the preserved nested Paper handoff after death",
                        nowMillis);
                return Outcome.continuePlan();
            }
            String nestedKind = root.parameters().getOrDefault("deathHandoffRootKind", "");
            if (!nestedKind.equals("root.bring") && !nestedKind.equals("root.give")) {
                return discardInterruptedHandoff(
                        planId, root, "interrupted handoff had no valid delivery-root ancestor", nowMillis);
            }
            LinkedHashMap<String, String> child = recoveredHandoffRootParameters(root.parameters());
            plans.pushPrerequisite(
                    planId,
                    new PlanFrame.Spec(
                            nestedKind,
                            required(root.parameters(), "deathHandoffRootTarget"),
                            integer(root.parameters(), "deathHandoffRootCount", 1),
                            child),
                    "rehydrating the exact interrupted Paper handoff",
                    nowMillis);
            return Outcome.continuePlan();
        }
        if (!root.kind().equals("root.bring") && !root.kind().equals("root.give")) {
            return discardInterruptedHandoff(
                    planId, root, "interrupted handoff no longer belongs to a delivery root", nowMillis);
        }
        if (root.detail().startsWith("resumed after deliver ")) {
            Map<String, String> checkpoint = new LinkedHashMap<>(root.parameters());
            clearDeathHandoffCheckpoint(checkpoint);
            checkpoint.remove("deliveryBatch");
            checkpoint.remove("deliveryTransactionAccounted");
            plans.checkpointCurrent(
                    planId,
                    checkpoint,
                    "finished the preserved Paper handoff after death",
                    nowMillis);
            return Outcome.continuePlan();
        }

        LinkedHashMap<String, String> leaf = recoveredHandoffLeafParameters(root.parameters(), nowMillis);
        deliveryPendingInvalidated = true;
        plans.pushPrerequisite(
                planId,
                new PlanFrame.Spec(
                        "deliver",
                        required(root.parameters(), "deathHandoffItem"),
                        integer(root.parameters(), "deathHandoffCount", 1),
                        leaf),
                "resuming preserved Paper handoff nonce "
                        + required(root.parameters(), "deathHandoffNonce"),
                nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome discardInterruptedHandoff(
            String planId,
            PlanFrame root,
            String reason,
            long nowMillis) throws IOException {
        Map<String, String> checkpoint = new LinkedHashMap<>(root.parameters());
        clearDeathHandoffCheckpoint(checkpoint);
        plans.checkpointCurrent(planId, checkpoint, reason + "; replanning honestly", nowMillis);
        return Outcome.continuePlan();
    }

    /**
     * A witnessed safety escape retires only the interrupted corpse excursion.
     * Protection still owns the body; the next mission tick commits the normal
     * partial-recovery result before any item/origin route can be resubmitted.
     */
    public boolean recordUnsafeDeathDropRetreat(
            Mission mission, String hostileId, long nowMillis) throws IOException {
        if (mission == null || hostileId == null || hostileId.isBlank()
                || (mission.state() != MissionState.RUNNING
                && mission.state() != MissionState.PAUSED_BY_PROTECTION
                && mission.state() != MissionState.PAUSED_BY_SAFETY)) return false;
        String planId = recoveryPlanByMission.getOrDefault(mission.id(), mission.id());
        Optional<TaskPlan> retained = plans.restore(planId)
                .filter(plan -> plan.state() == TaskPlanState.OPEN);
        if (retained.isEmpty()) return false;
        PlanFrame frame = retained.orElseThrow().currentFrame().orElse(null);
        if (frame == null || !frame.kind().equals("recover_death_drops")
                || !Boolean.parseBoolean(frame.parameters().getOrDefault(
                "deathRecoveryPending", "false"))) return false;
        if (!unsafeDeathDropRetreatDetail(frame).isBlank()) return false;
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        checkpoint.put("deathRecoveryUnsafeFrame", frame.id());
        checkpoint.put("deathRecoveryUnsafeCapture", deathDropCaptureIdentity(frame));
        checkpoint.put("deathRecoveryUnsafeThreat", hostileId);
        checkpoint.put("deathRecoveryUnsafeAt", Long.toString(nowMillis));
        plans.checkpointCurrent(planId, checkpoint,
                "safety retreat interrupted corpse recovery; retaining pickups and original work",
                nowMillis);
        return true;
    }

    private static String deathDropCaptureIdentity(PlanFrame frame) {
        return java.util.stream.Stream.of("deathRecoveryDimension", "deathRecoveryX",
                "deathRecoveryY", "deathRecoveryZ", "deathRecoveryDiedAt", "deathRecoveryInventory")
                .map(key -> frame.parameters().getOrDefault(key, ""))
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private static String unsafeDeathDropRetreatDetail(PlanFrame frame) {
        Map<String, String> facts = frame.parameters();
        if (!frame.id().equals(facts.get("deathRecoveryUnsafeFrame"))
                || !deathDropCaptureIdentity(frame).equals(facts.get("deathRecoveryUnsafeCapture"))
                || facts.getOrDefault("deathRecoveryUnsafeThreat", "").isBlank()) return "";
        return "abandoned unsafe corpse route after protection safety retreat from "
                + facts.get("deathRecoveryUnsafeThreat")
                + "; keeping verified pickups and replanning original work from current inventory";
    }

    private Outcome tickDeathDropRecoveryLeaf(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            long nowMillis) throws IOException {
        if (Boolean.parseBoolean(frame.parameters().getOrDefault(
                "deathRecoveryFinished", "false"))) {
            completeLeaf(
                    planId, frame, lease,
                    "committed bounded death-drop recovery outcome",
                    nowMillis);
            return Outcome.continuePlan();
        }
        String unsafeRetreat = unsafeDeathDropRetreatDetail(frame);
        if (!unsafeRetreat.isBlank()) {
            return finishDeathDropRecovery(planId, frame, lease, unsafeRetreat, nowMillis);
        }
        DeathDropRecoveryPolicy.Capture capture = deathCapture(frame.parameters());
        DeathDropRecoveryPolicy.Decision decision = DeathDropRecoveryPolicy.assess(
                capture, canonicalInventorySnapshot(), currentDimension(), nowMillis);
        if (decision.action() != DeathDropRecoveryPolicy.Action.RECOVER) {
            return finishDeathDropRecovery(
                    planId, frame, lease,
                    decision.detail() + "; replanning only remaining mission deficits",
                    nowMillis);
        }

        GroundItemCollectionPolicy.Request request = new GroundItemCollectionPolicy.Request(
                new GroundItemCollectionPolicy.Scope(planId, frame.id() + ":death-drops"),
                decision.missing().keySet(),
                new GroundItemCollectionPolicy.Point(
                        capture.x() + 0.5, capture.y() + 0.5, capture.z() + 0.5),
                GroundItemCollectionPolicy.DEFAULT_ORIGIN_RADIUS,
                DeathDropRecoveryPolicy.maximumCandidateAgeMillis(capture, nowMillis),
                decision.missingCount(),
                GroundItemCollectionPolicy.DEFAULT_BLACKLIST_MILLIS,
                GroundItemCollectionPolicy.DEFAULT_MAXIMUM_TARGET_FAILURES);
        ClientGroundItemCollectionController.Result result =
                groundItems.tick(request, lease, nowMillis, true);
        return switch (result.state()) {
            case COLLECTED, PROGRESS -> {
                DeathDropRecoveryPolicy.Decision afterPickup = DeathDropRecoveryPolicy.assess(
                        capture, canonicalInventorySnapshot(), currentDimension(), nowMillis);
                if (afterPickup.action() == DeathDropRecoveryPolicy.Action.NONE_MISSING) {
                    yield finishDeathDropRecovery(
                            planId, frame, lease,
                            "verified all captured death cargo by inventory increase",
                            nowMillis);
                }
                yield checkpointDeathRecoveryProgress(
                        planId, frame, result.detail(), nowMillis, true);
            }
            case SEARCHING -> {
                long searchingSince = longInteger(
                        frame.parameters(), "deathRecoveryNoCandidateSince", 0L);
                if (searchingSince == 0L) {
                    Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
                    checkpoint.put("deathRecoveryNoCandidateSince", Long.toString(nowMillis));
                    plans.checkpointCurrent(
                            planId, checkpoint,
                            "reached the death site; starting bounded drop scan",
                            nowMillis);
                    yield Outcome.continuePlan();
                }
                if (DeathDropRecoveryPolicy.originSearchExpired(searchingSince, nowMillis)) {
                    yield finishDeathDropRecovery(
                            planId, frame, lease,
                            "no matching loaded death drops appeared during the bounded origin scan",
                            nowMillis);
                }
                yield Outcome.running(
                        "scanning the loaded death site for " + decision.missingCount()
                                + " missing item(s)",
                        result.distanceRemaining(), 0L, false);
            }
            case LOST, BLOCKED -> {
                int failures = integer(frame.parameters(), "deathRecoveryRouteFailures", 0) + 1;
                if (failures >= DeathDropRecoveryPolicy.MAXIMUM_ROUTE_FAILURES) {
                    yield finishDeathDropRecovery(
                            planId, frame, lease,
                            "death-site recovery exhausted " + failures
                                    + " bounded route/pickup attempts: " + result.detail(),
                            nowMillis);
                }
                Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
                checkpoint.put("deathRecoveryRouteFailures", Integer.toString(failures));
                plans.checkpointCurrent(
                        planId, checkpoint,
                        "retrying death-site recovery route " + failures + '/'
                                + DeathDropRecoveryPolicy.MAXIMUM_ROUTE_FAILURES,
                        nowMillis);
                yield Outcome.continuePlan();
            }
            case INVENTORY_FULL -> finishDeathDropRecovery(
                    planId, frame, lease,
                    "inventory became full during death-drop recovery; keeping verified pickups",
                    nowMillis);
            case WAITING_FOR_WORLD -> Outcome.running(
                    result.detail(), Double.NaN, 0L, false);
            case APPROACHING, WAITING_FOR_PICKUP, RECOVERING -> checkpointDeathRecoveryProgress(
                    planId, frame, result.detail(), nowMillis, false);
        };
    }

    private Outcome checkpointDeathRecoveryProgress(
            String planId,
            PlanFrame frame,
            String detail,
            long nowMillis,
            boolean clearNoCandidate) throws IOException {
        if (clearNoCandidate && frame.parameters().containsKey("deathRecoveryNoCandidateSince")) {
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            checkpoint.remove("deathRecoveryNoCandidateSince");
            plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
            return Outcome.continuePlan();
        }
        return Outcome.running(
                detail,
                Double.NaN,
                0L,
                AutonomyWatchdogPolicy.delegatesProgressToGlobalWatchdog(
                        frame.kind(), true));
    }

    private Outcome finishDeathDropRecovery(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            String detail,
            long nowMillis) throws IOException {
        DeathDropRecoveryPolicy.Decision finalInventory = DeathDropRecoveryPolicy.assess(
                deathCapture(frame.parameters()),
                canonicalInventorySnapshot(),
                currentDimension(),
                nowMillis);
        return commitDeathDropRecoveryOutcome(planId, frame, detail, finalInventory,
                () -> clearCompletedLeafRuntimeState(planId, frame, lease, detail), nowMillis);
    }

    /** One completion path for both verified cargo and an honestly bounded partial loss. */
    private Outcome commitDeathDropRecoveryOutcome(
            String planId,
            PlanFrame frame,
            String detail,
            DeathDropRecoveryPolicy.Decision finalInventory,
            Runnable releaseCompletedLeaf,
            long nowMillis) throws IOException {
        TaskPlan plan = plans.restore(planId).orElseThrow();
        PlanFrame root = plan.frames().getFirst();
        Map<String, String> rootCheckpoint = new LinkedHashMap<>(root.parameters());
        if (finalInventory.action() != DeathDropRecoveryPolicy.Action.NONE_MISSING) {
            try {
                invalidateUniversalProgramCheckpoint(
                        rootCheckpoint,
                        DivergenceCause.INVENTORY_LOSS,
                        detail + "; " + finalInventory.missingCount()
                                + " captured item(s) remain missing");
            } catch (IllegalArgumentException corrupt) {
                return Outcome.blocked(
                        "durable universal program is corrupt: " + corrupt.getMessage());
            }
        }
        clearDeathRecoveryCheckpoint(rootCheckpoint);
        MissionDeathRecoveryPolicy.TerminalResult terminal =
                MissionDeathRecoveryPolicy.terminalResult(
                        finalInventory.missingCount(), detail);
        rootCheckpoint.put(
                MissionDeathRecoveryPolicy.TERMINAL_DISPOSITION_KEY,
                terminal.disposition().name());
        rootCheckpoint.put(
                MissionDeathRecoveryPolicy.TERMINAL_MISSING_COUNT_KEY,
                Integer.toString(terminal.missingCount()));
        rootCheckpoint.put(
                MissionDeathRecoveryPolicy.TERMINAL_DETAIL_KEY,
                terminal.detail());
        releaseCompletedLeaf.run();
        plans.completeCurrentAndCheckpointParent(
                planId,
                frame.id(),
                rootCheckpoint,
                detail,
                finalInventory.action() == DeathDropRecoveryPolicy.Action.NONE_MISSING
                        ? "verified all captured death cargo before resuming the committed program"
                        : "typed death cargo loss before resuming the universal root",
                nowMillis);
        return Outcome.continuePlan();
    }

    /**
     * Executes the semantic {@code food} family without ever collapsing the
     * mission to one concrete meat. Acquisition may therefore use pork after
     * chicken, while delivery accounts the mixed ready-food total.
     */
    private Outcome tickFoodRoot(
            String planId,
            PlanFrame frame,
            long nowMillis) throws IOException {
        int requested = Math.toIntExact(frame.count());
        int delivered = integer(frame.parameters(), "deliveredTotal", 0);
        if (delivered < 0 || delivered > requested) {
            return Outcome.blocked("invalid durable food-family delivery checkpoint");
        }

        boolean acquireOnly = frame.kind().equals("root.food.acquire");
        boolean mayAcquire = acquireOnly || frame.kind().equals("root.food.bring");
        boolean deliverPhase = frame.kind().equals("root.food.give")
                || frame.parameters().getOrDefault("foodPhase", "acquire_all")
                .equals("deliver_all");
        int outstanding = acquireOnly ? requested : requested - delivered;
        int ready = foodReadyCount();

        if (!deliverPhase) {
            // GET and inserted acquisition roots already carry an absolute target.
            // Ignore the obsolete saved Home food floor; Bring's existing personal
            // supplies allocator owns its separate retained/delivered shares.
            int requiredReady = outstanding;
            if ("true".equals(frame.parameters().get("personalFoodCookingSettled"))
                    && personalFoodMaintenanceMayResume(frame, personalReadyFoodCount(),
                    foodConsumptionPending.getAsBoolean())) {
                plans.completeCurrent(planId,
                        "available food cooked and useful personal ration restored; resuming original work", nowMillis);
                return Outcome.continuePlan();
            }
            if (!foodObjectiveMayAdvance(
                    ready, requiredReady, foodConsumptionPending.getAsBoolean())) {
                return Outcome.running(
                        "waiting for an in-flight food consumption to synchronize",
                        Double.NaN, 0L, false);
            }
            if (ready < requiredReady) {
                if (!mayAcquire) {
                    return Outcome.blocked("Entity has only " + ready + "/" + outstanding
                            + " deliverable food across all supported food types");
                }
                return pushFoodAcquisition(
                        planId, frame, requiredReady,
                        "acquiring a flexible food-family remainder", nowMillis);
            }
            if (acquireOnly) {
                plans.completeCurrent(
                        planId, "verified " + ready + " mixed ready food", nowMillis);
                return Outcome.continuePlan();
            }
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            checkpoint.put("foodPhase", "deliver_all");
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "acquired the complete food-family objective before delivery",
                    nowMillis);
            return Outcome.continuePlan();
        }

        if (delivered >= requested) {
            plans.completeCurrent(
                    planId, "Paper verified the complete mixed-food handoff", nowMillis);
            return Outcome.continuePlan();
        }
        outstanding = requested - delivered;
        ready = foodReadyCount();
        int requiredReady = outstanding;
        if (!foodObjectiveMayAdvance(
                ready, requiredReady, foodConsumptionPending.getAsBoolean())) {
            return Outcome.running(
                    "waiting for an in-flight food consumption before delivery",
                    Double.NaN, delivered, false);
        }
        if (ready < requiredReady) {
            if (!mayAcquire) {
                return Outcome.blocked("food-family give needs " + outstanding
                        + " ready food but Entity retains only " + ready);
            }
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            checkpoint.put("foodPhase", "acquire_all");
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "food cargo changed; closing delivery until the full remainder is reacquired",
                    nowMillis);
            return Outcome.continuePlan();
        }

        ClientInventoryController.ConcreteItem availableFood = permittedConcreteDelivery(planId, FoodFamilyPolicy.FAMILY_ITEM)
                .orElse(null);
        Map.Entry<String, Integer> concrete = availableFood == null ? null
                : Map.entry(availableFood.itemId(), availableFood.count());
        if (concrete == null) {
            DeliveryOrder order = deliveryOrder(planId);
            if (order != null && order.bring() && !order.requests().isEmpty()) {
                plans.rebaseToRoot(planId, order.root().parameters(),
                        "food supplies changed before handoff; reobserve the relevant shortfall", nowMillis);
                return Outcome.continuePlan();
            }
            return Outcome.blocked("food-family accounting found no concrete deliverable stack");
        }
        int batch = Math.min(outstanding, concrete.getValue());
        LinkedHashMap<String, String> child = new LinkedHashMap<>();
        child.put("player", required(frame.parameters(), "player"));
        child.put("familyItem", FoodFamilyPolicy.FAMILY_ITEM);
        child.put("description", "deliver mixed food-family cargo");
        plans.pushPrerequisite(
                planId,
                new PlanFrame.Spec("root.give", concrete.getKey(), batch, child),
                "delivering " + batch + " " + concrete.getKey()
                        + " from the mixed food-family objective",
                nowMillis);
        return Outcome.continuePlan();
    }

    static boolean foodObjectiveMayAdvance(
            int readyCount, int outstandingCount, boolean consumptionPending) {
        return readyCount < outstandingCount || !consumptionPending;
    }

    static boolean personalFoodMaintenanceMayResume(PlanFrame frame, int personalReady, boolean consumptionPending) {
        return frame.kind().equals("root.food.acquire")
                && "true".equals(frame.parameters().get("personalMaintenance"))
                && "true".equals(frame.parameters().get("personalFoodCookingSettled"))
                && !consumptionPending
                && personalReady >= HomeStockPolicy.MINIMUM_READY_FOOD_FLOOR;
    }

    static int personalFoodAcquisitionTarget(
            PlanFrame frame, int requested, Map<String, Integer> eligibleInventory) {
        if (!frame.kind().equals("root.food.acquire")
                || !"true".equals(frame.parameters().get("personalMaintenance"))) {
            return requested;
        }
        FoodFamilyPolicy.Summary available = FoodFamilyPolicy.summary(eligibleInventory);
        int potential = Math.addExact(available.readyCount(), available.convertibleCount());
        // Personal upkeep is not an exact delivery order. Travel can consume raw
        // meat after hunting: cook a useful carried ration instead of repeatedly
        // replacing that consumption before any cooking. The durable target and
        // settled-custody completion check above remain unchanged.
        return available.convertibleCount() > 0
                && potential >= HomeStockPolicy.MINIMUM_READY_FOOD_FLOOR
                ? Math.min(requested, potential) : requested;
    }

    private int personalReadyFoodCount() {
        return PersonalSuppliesPolicy.allocate(inventory.personalSupplies().playerStacks(),
                        retainedPersonalProfile(), personalSupplyClaims(), Set.of())
                .categories().stream().filter(category -> category.id().equals("food"))
                .mapToInt(PersonalSuppliesPolicy.Category::retained).findFirst().orElse(0);
    }

    private static boolean isConsumableFoodObjective(String item) {
        return FoodFamilyPolicy.isFamilyRequest(item)
                || FoodFamilyPolicy.isReadyFood(item)
                || FoodFamilyPolicy.memberForRawItem(item).isPresent();
    }

    private Outcome pushFoodAcquisition(
            String planId,
            PlanFrame parent,
            int requiredReadyTotal,
            String reason,
            long nowMillis) throws IOException {
        // Mixed-food cooking bypasses the universal compiler. It must still acquire
        // the same nearby owned-station binding before deciding to place a field furnace.
        Outcome stationBinding = bindNearbyHomeStations(planId, parent, nowMillis);
        if (stationBinding != null) return stationBinding;
        ResourcePlanner.InventoryView foodInventory = foodPlanningInventoryView(planId, false);
        int acquisitionTarget = personalFoodAcquisitionTarget(
                parent, requiredReadyTotal, foodInventory.itemCounts());
        FoodAcquisitionDecision decision = decideFoodAcquisition(
                acquisitionTarget,
                foodInventory.itemCounts(),
                foodInventory.itemCounts());
        if (decision.step() == FoodAcquisitionStep.COMPLETE) {
            return Outcome.continuePlan();
        }
        if (decision.step() == FoodAcquisitionStep.ACQUIRE_FUEL) {
            int coalTarget = foodCoalAcquisitionTarget(
                    planId, decision.requiredFuel(), foodInventory.itemCounts());
            return pushExactMissingResource(
                    planId,
                    "coal",
                    coalTarget,
                    reason + "; provisioning the complete mixed-meat fuel budget beyond protected cargo",
                    nowMillis,
                    // coalTarget is an absolute inventory target which already
                    // includes protected future cargo. Plan against the observed
                    // inventory so one child acquires the whole deficit instead
                    // of restarting MineProcess once per newly reserved coal.
                    inventoryView(),
                    "exact food-fuel acquisition");
        }
        if (decision.step() == FoodAcquisitionStep.CONVERT) {
            FoodCookingSessionPolicy.Plan cooking =
                    FoodCookingSessionPolicy.planForObjective(
                            acquisitionTarget, foodInventory.itemCounts());
            if (!cooking.empty()) {
                Map<String, String> parameters = foodCookingParameters(
                        parent, cooking, decision.requiredFuel());
                plans.pushPrerequisite(
                        planId,
                        new PlanFrame.Spec(
                                "smelt", FoodFamilyPolicy.FAMILY_ITEM,
                                cooking.totalOperations(), parameters),
                        reason + "; cooking " + cooking.totalOperations()
                                + " mixed raw food in one furnace lifecycle",
                        nowMillis);
                return Outcome.continuePlan();
            }
            plans.pushPrerequisite(
                    planId,
                    new PlanFrame.Spec(
                            "root.acquire", decision.readyItem(), decision.targetReadyCount(),
                            Map.of("description",
                                    "convert one complete carried food subtype after aggregate gathering")),
                    reason + "; preparing " + decision.conversionUnits() + " "
                            + decision.readyItem() + " from already-carried inputs",
                    nowMillis);
            return Outcome.continuePlan();
        }

        List<FoodFamilyPolicy.AnimalCandidate> candidates = loadedFoodAnimals();
        FoodFamilyPolicy.AnimalDecision animal = FoodFamilyPolicy.chooseLoadedAnimal(
                candidates, "", false);
        LinkedHashSet<String> alternatives = new LinkedHashSet<>();
        animal.target().ifPresent(target -> alternatives.add(target.entityType()));
        FoodFamilyPolicy.members().stream()
                .filter(FoodFamilyPolicy.Member::hunted)
                .map(FoodFamilyPolicy.Member::animalEntity)
                .forEach(alternatives::add);
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("entityAlternatives", String.join(",", alternatives));
        parameters.put("minimumDropsPerKill", "1");
        parameters.put("expectedMinimumItems", Integer.toString(decision.rawDeficit()));
        parameters.put("description",
                "gather the complete flexible raw-food deficit before any cooking");
        parameters.put("foodSelectionReason", animal.reason());
        plans.pushPrerequisite(
                planId,
                new PlanFrame.Spec(
                        "hunt", "raw_food", decision.rawDeficit(), parameters),
                reason + "; gathering all remaining raw food without locking to one species; "
                        + animal.reason(),
                nowMillis);
        return Outcome.continuePlan();
    }

    /**
     * Binds a Home stock-cooking child to the already-placed Home furnace.
     * Hunting may finish far from Home, so an unbound cooking frame would
     * mistake the pinned furnace for a missing disposable field-kit station.
     */
    static Map<String, String> foodCookingParameters(
            PlanFrame parent,
            FoodCookingSessionPolicy.Plan cooking,
            int requiredFuel) {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("foodCookingSession", FoodCookingSessionPolicy.encode(cooking));
        parameters.put("ingredients", cooking.rawInputs().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(",")));
        parameters.put("workstation", "furnace");
        parameters.put("operations", Integer.toString(cooking.totalOperations()));
        parameters.put("fuelItem", "coal");
        parameters.put("fuelCount", Integer.toString(requiredFuel));
        parameters.put("description",
                "cook every carried raw-meat subtype in one persistent furnace session");
        if (usesPinnedHomeStation(parent, "furnace")) {
            parameters.put("workstationSession",
                    pinnedHomeWorkstationOperation(parent, "furnace"));
            parameters.put("retainWorkstation", "true");
            parameters.put("homePinnedStation", "true");
            copyHomeBinding(parent.parameters(), parameters);
        }
        return Map.copyOf(parameters);
    }

    static FoodAcquisitionDecision decideFoodAcquisition(
            int requiredReadyTotal,
            Map<String, Integer> carriedInventory,
            Map<String, Integer> spendableInventory) {
        if (requiredReadyTotal <= 0) {
            throw new IllegalArgumentException("required ready food must be positive");
        }
        Objects.requireNonNull(carriedInventory, "carriedInventory");
        Objects.requireNonNull(spendableInventory, "spendableInventory");
        FoodFamilyPolicy.Summary summary = FoodFamilyPolicy.summary(carriedInventory);
        if (summary.readyCount() >= requiredReadyTotal) {
            return new FoodAcquisitionDecision(
                    FoodAcquisitionStep.COMPLETE, 0, 0, "", 0, 0);
        }

        int conversionDeficit = Math.subtractExact(requiredReadyTotal, summary.readyCount());
        int rawDeficit = Math.max(
                0, Math.subtractExact(conversionDeficit, summary.convertibleCount()));
        if (rawDeficit > 0) {
            return new FoodAcquisitionDecision(
                    FoodAcquisitionStep.HUNT_RAW, rawDeficit, 0, "", 0, 0);
        }

        int remaining = conversionDeficit;
        java.util.ArrayList<Integer> cookingBatches = new java.util.ArrayList<>();
        String nextReadyItem = "";
        int nextConversionUnits = 0;
        for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
            int convertible = summary.convertibleByReadyItem()
                    .getOrDefault(member.readyItem(), 0);
            int allocated = Math.min(remaining, convertible);
            if (allocated <= 0) continue;
            if (nextReadyItem.isBlank()) {
                nextReadyItem = member.readyItem();
                nextConversionUnits = allocated;
            }
            // Every meat subtype is now owned by one persistent furnace leaf.
            // Keep the portions here so fuel accounting can aggregate their
            // total operations into that single continuous burn session.
            if (member.hunted()) {
                cookingBatches.add(allocated);
            }
            remaining -= allocated;
            if (remaining == 0) break;
        }
        if (remaining != 0 || nextReadyItem.isBlank()) {
            throw new IllegalStateException(
                    "food conversion allocation disagrees with family potential accounting");
        }

        FoodFuelBudget fuel = foodFuelBudget(cookingBatches, spendableInventory);
        if (!fuel.satisfied()) {
            return new FoodAcquisitionDecision(
                    FoodAcquisitionStep.ACQUIRE_FUEL, 0,
                    fuel.requiredRegularFuel(), "", 0, 0);
        }
        int targetReadyCount = Math.addExact(
                normalizedInventoryCount(carriedInventory, nextReadyItem),
                nextConversionUnits);
        return new FoodAcquisitionDecision(
                FoodAcquisitionStep.CONVERT,
                0,
                fuel.requiredRegularFuel(),
                nextReadyItem,
                targetReadyCount,
                nextConversionUnits);
    }

    static FoodFuelBudget foodFuelBudget(
            List<Integer> cookingBatches,
            Map<String, Integer> spendableInventory) {
        int required = requiredRegularFoodFuel(cookingBatches, spendableInventory);
        int carried = Math.addExact(
                normalizedInventoryCount(spendableInventory, "coal"),
                normalizedInventoryCount(spendableInventory, "charcoal"));
        return new FoodFuelBudget(required, carried);
    }

    /**
     * Returns the total coal/charcoal count needed for one persistent food
     * cooking session. Raw subtypes share the same furnace lifecycle, while
     * each subtype transition reserves a small burn-time margin for the
     * server-confirmed input swap.
     */
    static int requiredRegularFoodFuel(
            List<Integer> cookingBatches,
            Map<String, Integer> spendableInventory) {
        Objects.requireNonNull(cookingBatches, "cookingBatches");
        Objects.requireNonNull(spendableInventory, "spendableInventory");
        int remaining = 0;
        for (Integer batch : cookingBatches) {
            if (batch == null || batch <= 0) {
                throw new IllegalArgumentException("food cooking batches must be positive");
            }
            remaining = Math.addExact(remaining, batch);
        }
        int transitions = Math.max(0, cookingBatches.size() - 1);
        remaining = Math.addExact(
                remaining,
                Math.multiplyExact(transitions, FOOD_INPUT_SWAP_HEADROOM_OPERATIONS));
        int blocks = normalizedInventoryCount(spendableInventory, "coal_block");
        long blockCapacity = Math.multiplyExact((long) blocks, COAL_BLOCK_FUEL_OPERATIONS);
        int uncovered = (int) Math.max(0L, (long) remaining - blockCapacity);
        return uncovered == 0 ? 0 : ceilDiv(uncovered, COAL_FUEL_OPERATIONS);
    }

    static FoodCargoAllocation allocateFoodCargo(
            int objective,
            Map<String, Integer> carriedInventory) {
        if (objective < 0) throw new IllegalArgumentException("food objective cannot be negative");
        Objects.requireNonNull(carriedInventory, "carriedInventory");
        FoodFamilyPolicy.Summary summary = FoodFamilyPolicy.summary(carriedInventory);
        LinkedHashMap<String, Integer> ready = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> raw = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> sources = new LinkedHashMap<>();
        int remaining = objective;
        for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
            int allocated = Math.min(
                    remaining,
                    summary.readyByItem().getOrDefault(member.readyItem(), 0));
            if (allocated > 0) {
                ready.put(member.readyItem(), allocated);
                remaining -= allocated;
            }
            if (remaining == 0) break;
        }
        if (remaining > 0) {
            for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
                if (!member.rawItem().isBlank()) {
                    int allocated = Math.min(
                            remaining,
                            normalizedInventoryCount(carriedInventory, member.rawItem()));
                    if (allocated > 0) {
                        raw.put(member.rawItem(), allocated);
                        remaining -= allocated;
                    }
                }
                for (FoodFamilyPolicy.ItemSource source : member.itemSources()) {
                    if (remaining == 0) break;
                    int availableInputs = normalizedInventoryCount(
                            carriedInventory, source.item());
                    int availableOperations = availableInputs / source.inputCount();
                    int availableOutputs = Math.multiplyExact(
                            availableOperations, source.outputCount());
                    int allocatedOutputs = Math.min(remaining, availableOutputs);
                    if (allocatedOutputs <= 0) continue;
                    int requiredOperations = ceilDiv(
                            allocatedOutputs, source.outputCount());
                    sources.merge(
                            source.item(),
                            Math.multiplyExact(requiredOperations, source.inputCount()),
                            Math::addExact);
                    remaining -= allocatedOutputs;
                }
                if (remaining == 0) break;
            }
        }
        return new FoodCargoAllocation(ready, raw, sources);
    }

    private static int normalizedInventoryCount(
            Map<String, Integer> inventory,
            String wantedItem) {
        String wanted = simple(wantedItem);
        int total = 0;
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            Integer count = entry.getValue();
            if (count == null || count < 0) {
                throw new IllegalArgumentException("inventory counts cannot be null or negative");
            }
            if (simple(entry.getKey()).equals(wanted)) {
                total = Math.addExact(total, count);
            }
        }
        return total;
    }

    private List<FoodFamilyPolicy.AnimalCandidate> loadedFoodAnimals() {
        if (client.player == null || client.world == null) return List.of();
        java.util.ArrayList<FoodFamilyPolicy.AnimalCandidate> candidates =
                new java.util.ArrayList<>();
        for (Entity entity : client.world.getEntities()) {
            if (!entity.isAlive()) continue;
            String type = EntityType.getId(entity.getType()).getPath();
            if (FoodFamilyPolicy.memberForAnimal(type).isEmpty()) continue;
            candidates.add(new FoodFamilyPolicy.AnimalCandidate(
                    entity.getUuidAsString(), type,
                    Math.sqrt(client.player.squaredDistanceTo(entity)),
                    true, true));
        }
        return List.copyOf(candidates);
    }

    private Outcome tickBatchRoot(
            String planId,
            PlanFrame frame,
            long nowMillis) throws IOException {
        List<BatchItem> items = parseBatchItems(required(frame.parameters(), "batchItems"));
        Map<String, String> migrated = migrateLegacyBatchCheckpoint(frame, items);
        if (migrated != null) {
            plans.checkpointCurrent(
                    planId, migrated,
                    "migrated the legacy sequential batch into gather-first durable state",
                    nowMillis);
            return Outcome.continuePlan();
        }
        List<Integer> observed = batchObservedCounts(items, frame.parameters());
        if (items.stream().anyMatch(item -> isConsumableFoodObjective(item.item()))
                && foodConsumptionPending.getAsBoolean()) {
            return Outcome.running(
                    "waiting for an in-flight food consumption before batch accounting",
                    Double.NaN, 0L, false);
        }
        GatherFirstBatchPolicy.Mode mode = frame.kind().equals("root.batch.acquire")
                ? GatherFirstBatchPolicy.Mode.ACQUIRE_ONLY
                : GatherFirstBatchPolicy.Mode.ACQUIRE_THEN_DELIVER;
        GatherFirstBatchPolicy.Decision decision = GatherFirstBatchPolicy.evaluate(
                required(frame.parameters(), "batchItems"),
                frame.parameters(), observed, true, mode);
        if (decision.action() == GatherFirstBatchPolicy.Action.ACQUIRE
                && !batchHasAcquisitionCapacity(decision.current().item())) {
            decision = GatherFirstBatchPolicy.evaluate(
                    required(frame.parameters(), "batchItems"),
                    frame.parameters(), observed, false, mode);
        }

        return switch (decision.action()) {
            case CHECKPOINT -> {
                plans.checkpointCurrent(
                        planId, decision.checkpoint(), decision.reason(), nowMillis);
                yield Outcome.continuePlan();
            }
            case COMPLETE -> {
                plans.completeCurrent(planId, decision.reason(), nowMillis);
                yield Outcome.continuePlan();
            }
            case CORRUPT -> Outcome.blocked(
                    "invalid durable gather-first batch state: " + decision.reason());
            case WAIT_FOR_CAPACITY -> {
                if (frame.kind().equals("root.batch.give"))
                    yield Outcome.blocked("batch give needs the complete order in Entity's inventory; missing "
                            + decision.remaining() + " " + decision.current().item());
                // Crafting earlier batch outputs can consume the last slot.
                // Waiting cannot create room: suspend the same committed batch
                // under the existing capacity owner, then resume its cursor.
                yield insertCapacityRecovery(planId, frame, nowMillis);
            }
            case ACQUIRE -> {
                if (frame.kind().equals("root.batch.give")) {
                    yield Outcome.blocked("batch give needs the complete order in Entity's inventory; missing "
                            + decision.remaining() + " " + decision.current().item());
                }
                if (items.stream().noneMatch(item ->
                        FoodFamilyPolicy.isFamilyRequest(item.item()))) {
                    yield planUniversalBatchAcquisition(
                            planId, frame, items, decision.delivered(), nowMillis);
                }
                int index = decision.index();
                BatchItem item = items.get(index);
                int requiredOwnedTotal = item.count() - decision.delivered().get(index);
                String childKind = FoodFamilyPolicy.isFamilyRequest(item.item())
                        ? "root.food.acquire" : "root.acquire";
                LinkedHashMap<String, String> child = new LinkedHashMap<>();
                child.put("batchObjectiveIndex", Integer.toString(index));
                child.put("batchObjectiveItem", item.item());
                child.put("description", "gather-first batch acquisition "
                        + (index + 1) + "/" + items.size());
                // A Home batch owns verified, non-consumable workstation pins at its
                // durable root. Every scalar or food-family child must inherit that exact
                // generation binding; otherwise the child treats the placed Home table or
                // furnace as an ordinary disposable field-kit session.
                copyHomeBinding(frame.parameters(), child);
                plans.pushPrerequisite(
                        planId,
                        new PlanFrame.Spec(
                                childKind, item.item(), requiredOwnedTotal,
                                child),
                        decision.reason(),
                        nowMillis);
                yield Outcome.continuePlan();
            }
            case DELIVER -> {
                int index = decision.index();
                BatchItem item = items.get(index);
                String childKind = FoodFamilyPolicy.isFamilyRequest(item.item())
                        ? "root.food.give" : "root.give";
                LinkedHashMap<String, String> child = new LinkedHashMap<>();
                child.put("player", required(frame.parameters(), "player"));
                child.put("batchObjectiveIndex", Integer.toString(index));
                child.put("batchObjectiveItem", item.item());
                child.put("description", "gather-first batch delivery "
                        + (index + 1) + "/" + items.size());
                copyHomeBinding(frame.parameters(), child);
                plans.pushPrerequisite(
                        planId,
                        new PlanFrame.Spec(childKind, item.item(), decision.remaining(), child),
                        decision.reason(),
                        nowMillis);
                yield Outcome.continuePlan();
            }
        };
    }

    /**
     * Converts the pre-2.12 sequential scalar cursor exactly once. Completed
     * earlier delivery objectives are durable facts; undelivered cargo is
     * rebuilt from current inventory truth and must pass acquire-all again.
     */
    private Map<String, String> migrateLegacyBatchCheckpoint(
            PlanFrame frame,
            List<BatchItem> items) {
        Map<String, String> source = frame.parameters();
        if (source.containsKey(GatherFirstBatchPolicy.PHASE_KEY)
                || source.containsKey(GatherFirstBatchPolicy.ACQUIRED_COUNTS_KEY)
                || source.containsKey(GatherFirstBatchPolicy.DELIVERED_COUNTS_KEY)
                || (!source.containsKey("batchIndex")
                && !source.containsKey("batchDelivered"))) {
            return null;
        }

        int legacyIndex = Math.min(
                items.size(), Math.max(0, integer(source, "batchIndex", 0)));
        int legacyPartial = Math.max(0, integer(source, "batchDelivered", 0));
        boolean delivery = frame.kind().equals("root.batch.give")
                || frame.kind().equals("root.batch.bring");
        List<Integer> observed = batchObservedCounts(items, frame.parameters());
        java.util.ArrayList<Integer> acquired = new java.util.ArrayList<>(items.size());
        java.util.ArrayList<Integer> delivered = new java.util.ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            int objective = items.get(index).count();
            int confirmed = delivery && index < legacyIndex ? objective : 0;
            if (delivery && index == legacyIndex && index < items.size()) {
                confirmed = Math.min(objective, legacyPartial);
            }
            delivered.add(confirmed);
            acquired.add(Math.min(objective, Math.addExact(confirmed, observed.get(index))));
        }
        int acquireCursor = firstIncompleteBatch(items, acquired);
        int deliveryCursor = firstIncompleteBatch(items, delivered);
        LinkedHashMap<String, String> migrated = new LinkedHashMap<>(source);
        migrated.remove("batchIndex");
        migrated.remove("batchDelivered");
        migrated.put(GatherFirstBatchPolicy.PHASE_KEY, "acquire_all");
        migrated.put(GatherFirstBatchPolicy.ACQUIRE_CURSOR_KEY,
                Integer.toString(acquireCursor));
        migrated.put(GatherFirstBatchPolicy.DELIVERY_CURSOR_KEY,
                Integer.toString(deliveryCursor));
        migrated.put(GatherFirstBatchPolicy.ACQUIRED_COUNTS_KEY,
                encodeBatchCounts(acquired));
        migrated.put(GatherFirstBatchPolicy.DELIVERED_COUNTS_KEY,
                encodeBatchCounts(delivered));
        return migrated;
    }

    private static int firstIncompleteBatch(
            List<BatchItem> items,
            List<Integer> counts) {
        for (int index = 0; index < items.size(); index++) {
            if (counts.get(index) < items.get(index).count()) return index;
        }
        return items.size();
    }

    private static String encodeBatchCounts(List<Integer> counts) {
        return counts.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private Outcome planResourceRequest(
            String planId,
            PlanFrame root,
            ResourcePlanner.Request request,
            long nowMillis,
            boolean deliveryCanAcquire) throws IOException {
        if (request.kind() == ResourcePlanner.RequestKind.EQUIP) {
            ResourcePlanner.Decision decision;
            try {
                decision = resourcePlanner.nextAction(
                        request, planningInventoryViewForPlan(planId));
            } catch (ResourcePlanner.PlanningException error) {
                return Outcome.blocked(error.getMessage());
            }
            if (decision.complete()) {
                plans.completeCurrent(planId, "verified equipment objective", nowMillis);
                return Outcome.continuePlan();
            }
            pushAction(planId, decision.action().orElseThrow(), nowMillis, deliveryCanAcquire);
            return Outcome.continuePlan();
        }

        boolean carriedDeliveryReady = request.kind() == ResourcePlanner.RequestKind.DELIVER
                && DeliveryPolicy.readyForHandoff(
                requestItemCount(request.item()), request.count());
        Outcome acquisition = carriedDeliveryReady
                ? null
                : driveUniversalProgram(
                planId,
                root,
                List.of(new AcquisitionRequest.ItemGoal(request.item(), request.count())),
                nowMillis,
                deliveryCanAcquire);
        if (acquisition != null) return acquisition;
        {
            if (isConsumableFoodObjective(request.item())
                    && foodConsumptionPending.getAsBoolean()) {
                return Outcome.running(
                        "waiting for an in-flight food consumption to synchronize",
                        Double.NaN, 0L, false);
            }
            if (request.kind() == ResourcePlanner.RequestKind.DELIVER) {
                String exactTarget = resourcePlanner.catalog().resolveUniversalItem(request.item());
                boolean exactDelivery = isExactUniversalRequest(request.item());
                Action delivery = new Action(
                        ActionKind.DELIVER,
                        exactTarget,
                        request.count(),
                        Map.of(
                                "recipient", Objects.requireNonNull(request.recipient()),
                                "itemCount", Integer.toString(request.count()),
                                "exactDelivery", Boolean.toString(exactDelivery)),
                        "Deliver " + request.count() + " " + request.item()
                                + " to " + request.recipient());
                pushAction(planId, delivery, nowMillis, deliveryCanAcquire);
                return Outcome.continuePlan();
            }
            plans.completeCurrent(planId, "verified inventory objective", nowMillis);
            return Outcome.continuePlan();
        }
    }

    private Outcome planUniversalBatchAcquisition(
            String planId,
            PlanFrame root,
            List<BatchItem> items,
            List<Integer> delivered,
            long nowMillis) throws IOException {
        ArrayList<AcquisitionRequest.ItemGoal> goals = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            BatchItem item = items.get(index);
            int requiredOwned = Math.max(0, item.count() - delivered.get(index));
            if (requiredOwned > 0) {
                goals.add(new AcquisitionRequest.ItemGoal(item.item(), requiredOwned));
            }
        }
        if (goals.isEmpty()) return Outcome.continuePlan();

        Outcome acquisition = driveUniversalProgram(
                planId, root, goals, nowMillis, frameAllowsAcquisition(root));
        return acquisition == null ? Outcome.continuePlan() : acquisition;
    }

    private static boolean frameAllowsAcquisition(PlanFrame root) {
        return root.kind().equals("root.batch.acquire")
                || root.kind().equals("root.batch.bring");
    }

    /**
     * Binds every production leaf to one plan-wide field-kit owner. The first
     * action is allowed to leave a station placed only when the same complete
     * compilation proves another consumer still exists.
     */
    private Action prepareUniversalAction(
            String planId,
            PlanFrame parent,
            Program program) {
        Action first = program.currentAction().orElseThrow();
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>(first.parameters());
        parameters.put("universalAcquisition", "true");
        parameters.put(UNIVERSAL_PROGRAM_ROLE_KEY, UNIVERSAL_PROGRAM_COMMITTED_ACTION);
        parameters.put(UNIVERSAL_PROGRAM_ACTION_TOKEN_KEY,
                program.currentActionToken().orElseThrow());
        if (first.kind() == ActionKind.EQUIP && PICKAXE_RANKS.containsKey(simple(first.target()))) {
            program.remainingActions().stream().skip(1)
                    .filter(action -> action.kind() == ActionKind.MINE)
                    .filter(action -> simple(first.target()).equals(simple(
                            action.parameters().getOrDefault("plannedTool", ""))))
                    .findFirst()
                    .ifPresent(action -> {
                        int retainedFloor = program.goals().stream()
                                .filter(goal -> simple(goal.item()).equals(simple(first.target())))
                                .mapToInt(AcquisitionRequest.ItemGoal::count)
                                .sum();
                        parameters.put("minimumToolDurability", Integer.toString(
                                MiningToolSegmentPolicy.minimumEquippedDurability(
                                        action.count(), exactPickaxeDurabilities(
                                                simple(first.target())))));
                        parameters.put("retainedToolFloor", Integer.toString(retainedFloor));
                    });
        }
        String workstation = parameters.getOrDefault("workstation", "");
        if (!workstation.isBlank()) {
            // Compilation counts stations from the nearest bound ancestor, which
            // can sit below an unbound personal-upkeep root. Preserve that exact
            // authority on the child instead of repeatedly rebuilding an unpinned
            // action against the same distant Home station capability.
            PlanFrame stationOwner = plans.restore(planId)
                    .filter(candidate -> candidate.state() == TaskPlanState.OPEN)
                    .flatMap(AutonomyExecutor::innermostHomeStationBinding)
                    .orElse(parent);
            boolean pinnedHomeStation = usesPinnedHomeStation(stationOwner, workstation);
            String expectedSession = pinnedHomeStation
                    ? pinnedHomeWorkstationOperation(stationOwner, workstation)
                    : universalWorkstationOperationId(planId, workstation);
            parameters.put("workstationSession", expectedSession);
            // The compiler authenticated the exact physical-station generation and its
            // consumption boundary in this committed action. A suffix-wide search for any
            // later same-kind station use crosses consume/replacement generations (for example,
            // furnace -> smoker ingredient -> replacement furnace) and can strand the physical
            // ingredient in the world. Home pins are the only externally-owned stations which
            // deliberately remain placed regardless of the action-local annotation.
            boolean committedRetain = Boolean.parseBoolean(
                    parameters.getOrDefault("retainWorkstation", "false"));
            parameters.put("retainWorkstation", Boolean.toString(
                    pinnedHomeStation || committedRetain));
            if (pinnedHomeStation) {
                parameters.put("homePinnedStation", "true");
                copyHomeBinding(stationOwner.parameters(), parameters);
            } else {
                restoreUniversalWorkstationFacts(
                        parent.parameters(), parameters, workstation, expectedSession);
            }
        }
        return new Action(
                first.kind(), first.target(), first.count(), parameters, first.description());
    }

    /**
     * Runs one durable full-program acquisition. A null result means the exact
     * committed program is complete; every other result must be returned to the
     * plan loop. Compilation occurs only at a new root phase or after a typed
     * invalidation, never on an ordinary tick or child completion.
     */
    private Outcome driveUniversalProgram(
            String planId,
            PlanFrame root,
            List<AcquisitionRequest.ItemGoal> requestedGoals,
            long nowMillis,
            boolean deliveryCanAcquire) throws IOException {
        if (!resourceCatalogReady) {
            return Outcome.running(
                    "waiting for the authenticated recipe catalog after reconnect",
                    Double.NaN, 0L, false);
        }
        List<AcquisitionRequest.ItemGoal> goals = canonicalUniversalGoals(requestedGoals);
        Program program;
        String encoded = root.parameters().get(UNIVERSAL_PROGRAM_KEY);
        if (encoded == null) {
            return compileAndCheckpointUniversalProgram(planId, root, goals, nowMillis);
        }
        try {
            program = UniversalProgramCodec.decode(encoded);
        } catch (IllegalArgumentException corrupt) {
            return Outcome.blocked("durable universal program is corrupt: " + corrupt.getMessage());
        }

        if (program.status() == Status.INVALIDATED) {
            return compileAndCheckpointUniversalProgram(planId, root, goals, nowMillis);
        }
        if (!sameUniversalGoals(program.goals(), goals)) {
            if (program.status() == Status.COMPLETE) {
                return compileAndCheckpointUniversalProgram(planId, root, goals, nowMillis);
            }
            return invalidateUniversalProgramAndRebase(
                    planId, root,
                    DivergenceCause.EXTERNAL_WORLD_CHANGE,
                    "root acquisition objective changed while its committed program was active",
                    nowMillis);
        }
        if (program.status() == Status.COMPLETE) {
            if (universalGoalsSatisfied(goals)) return null;
            return invalidateUniversalProgramAndRebase(
                    planId, root, DivergenceCause.INVENTORY_LOSS,
                    "completed universal cargo is no longer present in inventory",
                    nowMillis);
        }
        if (!program.identity().catalogFingerprint().equals(resourceCatalogFingerprint)) {
            return invalidateUniversalProgramAndRebase(
                    planId, root,
                    DivergenceCause.RECIPE_CATALOG_CHANGED,
                    "authenticated recipe catalog changed from "
                            + program.identity().catalogFingerprint() + " to "
                            + resourceCatalogFingerprint,
                    nowMillis);
        }

        Action action = prepareUniversalAction(planId, root, program);
        pushAction(
                planId, action, nowMillis, deliveryCanAcquire,
                "committed universal action " + (program.cursor() + 1) + "/"
                        + program.actions().size() + ": " + action.description());
        return Outcome.continuePlan();
    }

    private Outcome compileAndCheckpointUniversalProgram(
            String planId,
            PlanFrame root,
            List<AcquisitionRequest.ItemGoal> goals,
            long nowMillis) throws IOException {
        Outcome stationBinding = bindNearbyHomeStations(planId, root, nowMillis);
        if (stationBinding != null) return stationBinding;
        String homeBindingFailure = homeMaintenanceBindingFailure(root);
        if (!homeBindingFailure.isBlank()) {
            return Outcome.blocked(homeBindingFailure);
        }
        ResourcePlanner.InventoryView inventoryView = planningInventoryViewForPlan(planId);
        Map<String, Integer> durability = usablePickaxeDurability();
        final AcquisitionPlan compiled;
        try {
            compiled = resourcePlanner.compile(new AcquisitionRequest(
                    goals, inventoryView, durability, UnavailableHarvestSources.read(root.parameters())));
        } catch (ResourcePlanner.PlanningException error) {
            Outcome search = recoverMissingFlowerSource(planId, root, error.getMessage(), nowMillis);
            if (search != null) return search;
            return Outcome.blocked(error.getMessage());
        }
        Program committed = UniversalProgramCommitment.commit(
                resourceCatalogFingerprint,
                compiled,
                PlanningBaseline.from(inventoryView, durability));
        LinkedHashMap<String, String> checkpoint = new LinkedHashMap<>(root.parameters());
        checkpoint.put(UNIVERSAL_PROGRAM_KEY, UniversalProgramCodec.encode(committed));
        plans.checkpointCurrent(
                planId,
                checkpoint,
                committed.status() == Status.COMPLETE
                        ? "committed already-satisfied universal acquisition"
                        : "committed complete universal acquisition program with "
                        + committed.actions().size() + " action(s)",
                nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome recoverMissingFlowerSource(String planId, PlanFrame root,
            String failure, long nowMillis) throws IOException {
        Set<String> absent = UnavailableHarvestSources.read(root.parameters());
        if (client.player == null || absent.isEmpty()
                || !failure.contains("loaded harvest source already absent")) return null;
        if (FlowerSearchContinuation.exhausted(root.parameters())) {
            return Outcome.blocked("No usable harvest source found after visiting "
                    + FlowerSearchContinuation.MAX_AREAS + " search areas for " + root.target()
                    + "; saved build and supplies retained");
        }
        var target = baritone.findHarvestSearchGoal(absent,
                FlowerSearchContinuation.number(root.parameters(), "OriginX", client.player.getBlockX()),
                FlowerSearchContinuation.number(root.parameters(), "OriginZ", client.player.getBlockZ()),
                FlowerSearchContinuation.visited(root.parameters()));
        if (target.isEmpty()) return null;
        var goal = target.orElseThrow();
        var facts = new LinkedHashMap<>(FlowerSearchContinuation.begin(root.parameters(), currentDimension(),
                client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ(),
                goal.areaId(), goal.item(), goal.x(), goal.y(), goal.z()));
        // The exhausted program is already invalidated; retaining it would make
        // ordinary divergence reconciliation discard this new goto child as stale.
        facts.remove(UNIVERSAL_PROGRAM_KEY);
        plans.checkpointCurrent(planId, facts,
                goal.item().isBlank() ? "Local harvest sources absent; visiting another loaded land area"
                        : "Observed " + goal.item() + "; approaching before resuming the same acquisition", nowMillis);
        logger.info("Harvest acquisition search mission={} target={} source={} position={},{},{}",
                planId, root.target(), goal.item(), goal.x(), goal.y(), goal.z());
        return Outcome.continuePlan();
    }

    private Outcome tickPendingFlowerSearch(String planId, PlanFrame root, long nowMillis) throws IOException {
        if (!FlowerSearchContinuation.pending(root.parameters())) return null;
        if (client.player == null) return Outcome.running("waiting for the harvest-search world", Double.NaN, 0, false);
        if (!currentDimension().equals(root.parameters().get(FlowerSearchContinuation.PREFIX + "Dimension")))
            return Outcome.blocked("Harvest search belongs to another dimension; original acquisition retained");
        if (FlowerSearchContinuation.arrived(root.parameters(), currentDimension(),
                client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ())) {
            var facts = new LinkedHashMap<>(FlowerSearchContinuation.complete(root.parameters(), currentDimension(),
                    client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ()));
            facts.remove(UNIVERSAL_PROGRAM_KEY);
            lastLocalSourceScanAge = Integer.MIN_VALUE;
            plans.checkpointCurrent(planId, facts,
                    "Physically reached harvest search area; reobserve supplies for the same objective", nowMillis);
            return Outcome.continuePlan();
        }
        plans.pushPrerequisite(planId, new PlanFrame.Spec("goto", root.target(), 1, Map.of(
                "x", root.parameters().get(FlowerSearchContinuation.PREFIX + "PendingX"),
                "y", root.parameters().get(FlowerSearchContinuation.PREFIX + "PendingY"),
                "z", root.parameters().get(FlowerSearchContinuation.PREFIX + "PendingZ"),
                "dimension", currentDimension(), "range", "3",
                "description", "looking for a usable harvest source, then resuming " + root.target())),
                "Existing Baritone route approaches the harvest search area", nowMillis);
        return Outcome.continuePlan();
    }

    /** Nested supplies have the same owned workstation capabilities as direct work.
     * Bind before compilation, not after a portable furnace has already been made.
     * This grants station use only; it does not start another chest-sourcing owner.
     */
    private Outcome bindNearbyHomeStations(String planId, PlanFrame root, long nowMillis) throws IOException {
        if (isHomeMaintenance(root) || hasHomeMissionSupplyPinAuthority(root)
                || homeEconomy == null || client.player == null || !settingsLoadState.authoritative()) return null;
        var snapshot = homeEconomy.snapshot();
        if (snapshot.home() == null || snapshot.generation() <= 0L) return null;
        boolean portableInFlight = rootOwnsPortableWorkstation(root.parameters());
        double distanceSquared = client.player.squaredDistanceTo(snapshot.home().x() + 0.5,
                snapshot.home().y(), snapshot.home().z() + 0.5);
        if (!HomeMissionSupplyAllocator.nearbyStationReuseEligible(
                currentDimension().equals(snapshot.home().dimension()), distanceSquared, portableInFlight)) return null;
        boolean usable = false;
        for (var role : List.of(HomeEconomySession.AssetRole.CRAFTING_TABLE, HomeEconomySession.AssetRole.FURNACE)) {
            String id = homePinnedLedgerId(snapshot, role);
            if (id.isBlank()) continue;
            var truth = workstations.diagnosePinnedHomeAsset(workstationKind(role), id,
                    snapshot.home(), HOME_ASSET_RADIUS).truth();
            // Initial admission needs an observed block. Later unloaded travel uses
            // the existing durable exact-pin route, not a fresh guess.
            if (truth == WorkstationController.HomeAssetLiveTruth.EXACT_VERIFIED
                    || truth == WorkstationController.HomeAssetLiveTruth.ACCESS_RECOVERABLE) usable = true;
            else return null;
        }
        if (!usable) return null;
        Map<String, String> checkpoint = new LinkedHashMap<>(root.parameters());
        copyHomeSnapshotBinding(snapshot, checkpoint);
        checkpoint.put(HOME_MISSION_SUPPLY_PIN_AUTHORITY, "true");
        plans.checkpointCurrent(planId, checkpoint,
                "bound nearby owned Home workstations before compiling supply work", nowMillis);
        return Outcome.continuePlan();
    }

    static boolean rootOwnsPortableWorkstation(Map<String, String> parameters) {
        // Only this root's exact operation facts establish in-flight custody.
        // Another job's placed/reclaim-pending field kit remains owned in its
        // ledger, but cannot veto use of a separately verified Home station.
        return parameters.keySet().stream()
                .anyMatch(key -> key.startsWith(UNIVERSAL_WORKSTATION_FACT_PREFIX));
    }

    private List<AcquisitionRequest.ItemGoal> canonicalUniversalGoals(
            List<AcquisitionRequest.ItemGoal> requested) {
        LinkedHashMap<String, Integer> merged = new LinkedHashMap<>();
        for (AcquisitionRequest.ItemGoal goal : requested) {
            merged.merge(
                    resourcePlanner.catalog().resolveUniversalItem(goal.item()),
                    goal.count(),
                    Math::addExact);
        }
        return merged.entrySet().stream()
                .map(entry -> new AcquisitionRequest.ItemGoal(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static boolean sameUniversalGoals(
            List<AcquisitionRequest.ItemGoal> left,
            List<AcquisitionRequest.ItemGoal> right) {
        return left.equals(right);
    }

    private boolean universalGoalsSatisfied(List<AcquisitionRequest.ItemGoal> goals) {
        for (AcquisitionRequest.ItemGoal goal : goals) {
            if (exactCount(goal.item()) < goal.count()) return false;
        }
        return true;
    }

    /** Pauses old-program actuation and fences catalog changes before any child tick. */
    private Outcome reconcileCommittedUniversalProgram(
            String planId,
            TaskPlan plan,
            long nowMillis) throws IOException {
        if (plan.frames().isEmpty()) return null;
        PlanFrame current = plan.currentFrame().orElseThrow();
        // The preserved committed action is temporarily suspended while its physical
        // receiving room is recovered; it must not be rebased out from under that work.
        if (current.kind().equals("root.inventory_capacity")) return null;
        PlanFrame owner = plan.nearestAncestorWithParameter(
                current.id(), UNIVERSAL_PROGRAM_KEY).orElse(null);
        if (owner == null) return null;
        // The suspended production program does not own the temporary upkeep subtree.
        // Its own catalog/station checks resume after that child pops; a child program
        // still validates its own identity normally.
        for (int index = plan.frames().indexOf(owner) + 1; index < plan.frames().size(); index++)
            if ("true".equals(plan.frames().get(index).parameters().get("personalMaintenance"))) return null;
        if (homeMissionSupplyProgramAwaitingTransfer(owner)) {
            // The fixed program was committed against selected Home prefixes. Until those exact
            // withdrawals and the empty-cursor close are acknowledged, live carried counts are
            // intentionally behind the committed baseline and are not inventory-loss evidence.
            return null;
        }
        String encoded = owner.parameters().get(UNIVERSAL_PROGRAM_KEY);
        if (!resourceCatalogReady) {
            return Outcome.running(
                    "waiting for the authenticated recipe catalog after reconnect",
                    Double.NaN, 0L, false);
        }
        final Program program;
        try {
            program = UniversalProgramCodec.decode(encoded);
        } catch (IllegalArgumentException corrupt) {
            return Outcome.blocked("durable universal program is corrupt: " + corrupt.getMessage());
        }
        if (program.status() == Status.ACTIVE
                && !program.identity().catalogFingerprint().equals(resourceCatalogFingerprint)) {
            return invalidateUniversalProgramAndRebase(
                    planId,
                    current,
                    DivergenceCause.RECIPE_CATALOG_CHANGED,
                    "authenticated recipe catalog changed from "
                            + program.identity().catalogFingerprint() + " to "
                            + resourceCatalogFingerprint,
                    nowMillis);
        }
        if (program.status() == Status.ACTIVE
                && !program.remainingRequiredStations().isEmpty()) {
            Set<String> liveStations = planningInventoryViewForPlan(planId).availableStations()
                    .stream()
                    .map(resourcePlanner::normalizeItem)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> committedStations = program.remainingRequiredStations().stream()
                    .map(resourcePlanner::normalizeItem)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!liveStations.containsAll(committedStations)) {
                return invalidateUniversalProgramAndRebase(
                        planId,
                        current,
                        DivergenceCause.WORKSTATION_LOSS,
                        "committed non-consumable workstation availability changed",
                        nowMillis);
            }
        }
        if (program.status() == Status.INVALIDATED && !current.id().equals(owner.id())) {
            LinkedHashMap<String, String> facts = new LinkedHashMap<>(owner.parameters());
            // decode(v1) authenticates the old child token but deliberately returns a v2
            // INVALIDATED program. Persist that migration in the same atomic owner rebase which
            // removes the old-token descendants, so no restart boundary can actuate them.
            facts.put(UNIVERSAL_PROGRAM_KEY, UniversalProgramCodec.encode(program));
            plans.rebaseToFrame(
                    planId, owner.id(), facts,
                    "discarded stale child generation after typed universal divergence",
                    nowMillis);
            return Outcome.continuePlan();
        }
        return null;
    }

    private static boolean homeMissionSupplyProgramAwaitingTransfer(PlanFrame owner) {
        String encoded = owner.parameters().get(HOME_MISSION_SUPPLY_KEY);
        if (encoded == null) return false;
        try {
            HomeMissionSupplyCodec.Phase phase =
                    HomeMissionSupplyCodec.decode(encoded).phase();
            return phase != HomeMissionSupplyCodec.Phase.READY
                    && phase != HomeMissionSupplyCodec.Phase.SKIPPED;
        } catch (IllegalArgumentException corrupt) {
            return false;
        }
    }

    private Outcome invalidateUniversalProgramAndRebase(
            String planId,
            PlanFrame evidenceFrame,
            DivergenceCause cause,
            String detail,
            long nowMillis) throws IOException {
        return invalidateUniversalProgramAndRebase(planId, evidenceFrame, cause, detail, nowMillis, false);
    }

    private Outcome invalidateUniversalProgramAndRebase(
            String planId, PlanFrame evidenceFrame, DivergenceCause cause,
            String detail, long nowMillis, boolean unavailableHarvest) throws IOException {
        String evidenceActionToken = evidenceFrame.parameters().get(
                UNIVERSAL_PROGRAM_ACTION_TOKEN_KEY);
        if (evidenceActionToken != null) {
            committedFurnaceFuelGrants.remove(evidenceActionToken);
        }
        TaskPlan plan = plans.restore(planId)
                .filter(candidate -> candidate.state() == TaskPlanState.OPEN)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing open task plan " + planId));
        final PlanFrame owner;
        try {
            owner = plan.nearestAncestorWithParameter(
                    evidenceFrame.id(), UNIVERSAL_PROGRAM_KEY).orElse(null);
        } catch (IllegalArgumentException staleEvidence) {
            return Outcome.blocked("stale universal child divergence evidence was rejected");
        }
        String encoded = owner == null
                ? null : owner.parameters().get(UNIVERSAL_PROGRAM_KEY);
        if (encoded == null) return Outcome.blocked(detail);
        final Program program;
        try {
            program = UniversalProgramCodec.decode(encoded);
        } catch (IllegalArgumentException corrupt) {
            return Outcome.blocked("durable universal program is corrupt: " + corrupt.getMessage());
        }
        Program invalidated = program;
        if (program.status() == Status.ACTIVE) {
            String expected = program.currentActionToken().orElseThrow();
            String supplied = evidenceFrame.parameters().get(UNIVERSAL_PROGRAM_ACTION_TOKEN_KEY);
            if (supplied != null && !supplied.equals(expected)) {
                return Outcome.blocked("stale universal child divergence evidence was rejected");
            }
            invalidated = program.observe(Observation.diverged(expected, cause, detail)).program();
        } else if (program.status() == Status.COMPLETE) {
            invalidated = program.invalidate(cause, detail).program();
        }
        LinkedHashMap<String, String> facts = new LinkedHashMap<>(owner.parameters());
        if (unavailableHarvest && !UnavailableHarvestSources.record(facts, evidenceFrame.target())) {
            return Outcome.blocked(detail + "; this unavailable harvest source was already rejected");
        }
        if (cause == DivergenceCause.WORKSTATION_LOSS) {
            clearUniversalWorkstationFacts(
                    facts, evidenceFrame.parameters().getOrDefault("workstation", ""));
        }
        facts.put(UNIVERSAL_PROGRAM_KEY, UniversalProgramCodec.encode(invalidated));
        plans.rebaseToFrame(
                planId, owner.id(), facts,
                "typed universal divergence: " + cause.name().toLowerCase(Locale.ROOT)
                        + ": " + detail,
                nowMillis);
        return Outcome.continuePlan();
    }

    /**
     * Applies a typed discontinuity to a root checkpoint without discarding its
     * full executable program. Callers can clear death facts in the same atomic
     * plan mutation, so a crash can expose neither an active stale program nor
     * an untyped missing-cargo state.
     */
    private static void invalidateUniversalProgramCheckpoint(
            Map<String, String> checkpoint,
            DivergenceCause cause,
            String detail) {
        String encoded = checkpoint.get(UNIVERSAL_PROGRAM_KEY);
        if (encoded == null) return;
        Program program = UniversalProgramCodec.decode(encoded);
        Program invalidated = program;
        if (program.status() == Status.ACTIVE) {
            String token = program.currentActionToken().orElseThrow();
            invalidated = program.observe(
                    Observation.diverged(token, cause, detail)).program();
        } else if (program.status() == Status.COMPLETE) {
            invalidated = program.invalidate(cause, detail).program();
        }
        checkpoint.put(UNIVERSAL_PROGRAM_KEY, UniversalProgramCodec.encode(invalidated));
    }

    private static String universalWorkstationOperationId(String planId, String workstation) {
        return planId + ":universal-workstation:" + workstation;
    }

    private Outcome tickGearRoot(String planId, PlanFrame root, ControlLease lease, long nowMillis) throws IOException {
        String tier = root.target().toLowerCase(Locale.ROOT);
        GearProfile profile = GearProfile.forTier(tier);
        Map<String, Integer> serviceable = inventory.homePlayerCounts();
        if (profile.acquire().stream().anyMatch(item -> serviceable.getOrDefault(
                ClientInventoryController.normalizeId(item), 0) < 1)) {
            Outcome upkeep = tickPersonalWorkMaintenance(planId, root, lease, nowMillis);
            if (upkeep != null) return upkeep;
        }
        // Gear used to call the legacy scalar planner once per item. Apart
        // from duplicating shared prerequisites, that compatibility path
        // projects the first authenticated recipe variant and cannot
        // transactionally fall back from compression cycles such as
        // iron_ingot -> iron_block -> iron_ingot. Use the same committed,
        // multi-variant universal program as /get and /bring so the complete
        // loadout is planned once from the real recipe catalog.
        List<AcquisitionRequest.ItemGoal> gearGoals = profile.acquire().stream()
                .map(item -> new AcquisitionRequest.ItemGoal(item, 1))
                .toList();
        Outcome acquisition = driveUniversalProgram(
                planId, root, gearGoals, nowMillis, false);
        if (acquisition != null) return acquisition;

        ResourcePlanner.InventoryView view = planningInventoryView();
        Set<String> equipped = view.equippedItems();
        for (String item : profile.equip()) {
            String normalized = resourcePlanner.normalizeItem(item);
            if (equipped.contains(normalized)) continue;
            ResourcePlanner.Decision decision = resourcePlanner.nextAction(
                    ResourcePlanner.Request.equip(item), planningInventoryView());
            if (!decision.complete()) {
                pushAction(planId, decision.action().orElseThrow(), nowMillis, false);
                return Outcome.continuePlan();
            }
        }
        plans.completeCurrent(planId, "equipped " + tier + " loadout", nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome tickLegacyMineRoot(
            String planId,
            PlanFrame root,
            Mission mission,
            ControlLease lease,
            long nowMillis) throws IOException {
        String[] blocks = Arrays.stream(required(root.parameters(), "blocks").split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toArray(String[]::new);
        int requiredTier = requiredPickaxeTier(blocks);
        String blockTargets = String.join(",", blocks);
        boolean surfaceWood = HarvestToolAccessPolicy.isWoodTarget(blockTargets);

        MiningPolicy.Plan mining = MiningPolicy.plan(
                blocks,
                client.world == null ? "" : client.world.getRegistryKey().getValue().toString(),
                client.player == null ? 16 : client.player.getBlockY());
        Map<String, String> parameters = new LinkedHashMap<>(root.parameters());
        int current = rawCount(mining.expectedItemIds());
        if (surfaceWood && !"HARVEST".equals(parameters.get("worldSourceKind"))) {
            // The native goal and the durable completion frame must agree. A safety pause may
            // retire the native tree before the root resumes with already-collected cargo.
            parameters.put("worldSourceKind", "HARVEST");
            plans.checkpointCurrent(planId, parameters, "retained direct surface-harvest classification", nowMillis);
            return Outcome.continuePlan();
        }
        if (!parameters.containsKey("startingCount")) {
            parameters.put("startingCount", Integer.toString(current));
            BlockPos origin = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
            parameters.put("legacyMiningDropOriginX", Integer.toString(origin.getX()));
            parameters.put("legacyMiningDropOriginY", Integer.toString(origin.getY()));
            parameters.put("legacyMiningDropOriginZ", Integer.toString(origin.getZ()));
            parameters.put("legacyMiningDropOriginDimension", currentDimension());
            plans.checkpointCurrent(planId, parameters, "recorded mining inventory baseline", nowMillis);
            return Outcome.continuePlan();
        }

        int baseline = integer(parameters, "startingCount", current);
        int requested = Math.toIntExact(root.count());
        // Unlike ordinary leaves, legacy direct mining executes at the root boundary. Its
        // land work could run with an old inner capability, while a water return wrote no
        // movement at all. Acquire the existing route owner here, including completion/exit.
        ExecutionKernel.Decision routeAction = executionKernel.request(
                lease, ActionOwner.BARITONE_ROUTE, planId + ':' + root.id(), lastClientTick, nowMillis);
        if (routeAction.state() == ExecutionKernel.State.NEUTRALIZE
                || routeAction.state() == ExecutionKernel.State.PARENT_INVALID) {
            neutralizeActionOwnerControls(routeAction.detail());
            routeAction.transition().ifPresent(ticket -> executionKernel.confirmNeutralized(ticket, lastClientTick));
            return Outcome.running(routeAction.detail(), Double.NaN, 0L, false);
        }
        if (routeAction.state() == ExecutionKernel.State.WAIT_NEXT_TICK) {
            return Outcome.running(routeAction.detail(), Double.NaN, 0L, false);
        }
        baritone.bindMovementAction(
                executionKernel, lease, routeAction.lease().orElseThrow(), lastClientTick, nowMillis);
        MiningSessionKey sessionKey = miningSessionKey(planId, root);
        MiningSession session = miningSession(
                sessionKey,
                root.id() + ":mine",
                baseline,
                current,
                requested,
                1);
        MiningSessionPolicy.Decision progress = MiningSessionPolicy.observe(session, current);
        if (progress.complete()) {
            if (session.hasMiningGoal()
                    && baritone.isOwnedMiningActive(
                            session.goalIdentity(), lease.epoch())) {
                // Quota completion must still refresh the exact native operation's
                // parent lease while its already-committed tree/vein settles.
                // Poll alone reads the old lease and eventually freezes that tail.
                baritone.start(legacyMiningGoal(mission, root, session.goalIdentity(),
                        session.goalBlockCount(), blockTargets, surfaceWood), lease);
                BaritonePort.Status completion = baritone.poll(session.goalIdentity());
                if (completion.state() != BaritonePort.State.COMPLETE) {
                    return observeLeaf(
                            root,
                            fromBaritone(completion,
                                    surfaceWood ? "finishing the committed tree" : "returning through the proven mine access"),
                            lease,
                            nowMillis,
                            MINING_ACTION_POLICY);
                }
            }
            Outcome exit = finishMiningExit(root, "legacyMiningDropOrigin", lease, nowMillis);
            if (exit != null) return exit;
            int gained = progress.acquiredItemCount();
            miningSessions.remove(sessionKey);
            baritone.finishFunctionalMiningRoute(nowMillis);
            plans.completeCurrent(planId, "verified " + gained + "/" + requested + " mined drops", nowMillis);
            baritone.cancel(lease.epoch(), "self-provisioning mine complete");
            return Outcome.continuePlan();
        }
        session = progress.session();
        int gained = progress.acquiredItemCount();
        if (!dropOriginMatchesCurrentDimension(root.parameters(), "legacyMiningDropOrigin")) {
            baritone.cancelIfOwned(
                    session.hasMiningGoal() ? session.goalIdentity() : root.id() + ":mine",
                    lease.epoch(),
                    "legacy mining drop origin changed dimension");
            miningSessions.remove(sessionKey);
            groundItems.clear(lease.epoch(), "legacy mining drop origin changed dimension");
            checkpointDropOrigin(planId, root, "legacyMiningDropOrigin", nowMillis,
                    "rebased mining drop collection after a dimension change");
            return Outcome.continuePlan();
        }

        if (session.segment() == MiningSession.Segment.PICKUP) {
            String endedOperationId = precedingMiningOperationId(session);
            if (baritone.isOwnedMiningActive(endedOperationId, lease.epoch())) {
                baritone.cancelIfOwned(
                        endedOperationId, lease.epoch(),
                        "waiting for the ended legacy mining segment to release pickup actuators");
                return Outcome.running(
                        "waiting for the ended mining segment to become quiescent before pickup",
                        Double.NaN, gained, false);
            }
            MiningDropCollectionResult collection = tickLegacyMiningDropCollection(
                    planId, root, mining.expectedItemIds(), lease, nowMillis, gained, requested);
            if (!collection.finished()) return collection.outcome();

            int afterCollection = rawCount(mining.expectedItemIds());
            MiningSessionPolicy.Decision after = MiningSessionPolicy.observe(session, afterCollection);
            if (after.complete()) {
                miningSessions.put(sessionKey, after.session());
                groundItems.clear(lease.epoch(), "collected cargo now requires its mine exit check");
                // Return through the common completion gate on the next tick.
                return Outcome.continuePlan();
            }
            MiningSessionPolicy.Decision resumed = MiningSessionPolicy.resumeMining(
                    after.session(), afterCollection);
            miningSessions.put(sessionKey, resumed.session());
            return collection.outcome() != null
                    ? collection.outcome()
                    : Outcome.running(
                            "mined-drop collection finished; rebasing the next fixed mining segment",
                            Double.NaN, resumed.acquiredItemCount(), false);
        }

        Outcome upkeep = tickPersonalWorkMaintenance(planId, root, lease, nowMillis);
        if (upkeep != null) return upkeep;
        String operationId = session.goalIdentity();
        int segmentBlocks = session.goalBlockCount();
        if (surfaceWood && HarvestToolAccessPolicy.shouldPrepare(
                baritone.ownsOperation(operationId, lease.epoch()), "HARVEST", blockTargets)) {
            Outcome preparation = prepareCarriedTreeTool(planId, blockTargets, nowMillis);
            if (preparation != null) return preparation;
        }
        if (requiredTier > 0) {
            boolean ownedMining = baritone.ownsOperation(operationId, lease.epoch());
            if (!ownedMining) {
                Optional<MiningToolBinding> binding = miningToolBinding(
                        root, requiredTier, segmentBlocks);
                if (binding.isEmpty()) {
                    return provisionCommittedMiningTool(
                            planId, root, requiredTier, segmentBlocks,
                            missionItem(mission), progress.remainingItemCount(), nowMillis);
                }
                MiningToolBinding exact = binding.orElseThrow();
                int safeSegment = exact.safeSegmentBlocks();
                if (safeSegment > 0) {
                    MiningSession bounded = MiningSessionPolicy.boundUnownedSegment(
                            session, safeSegment);
                    if (bounded != session) {
                        session = bounded;
                        miningSessions.put(sessionKey, session);
                        segmentBlocks = session.goalBlockCount();
                    }
                }
                if (!selectedMiningToolMatches(
                        requiredTier, exact.item(), exact.minimumDurability())) {
                    pushMiningToolBinding(planId, exact, nowMillis);
                    return Outcome.continuePlan();
                }
            }
            MiningToolSegmentPolicy.Action toolAction = MiningToolSegmentPolicy.decide(
                    ownedMining,
                    hasBoundServiceableMiningTool(root, requiredTier),
                    selectedMiningToolMatches(
                            requiredTier,
                            root.parameters().getOrDefault("plannedTool", ""),
                            2),
                    ownedMining && baritone.hasCarriedPlacementSupport(
                            operationId, lease.epoch()),
                    !ownedMining);
            if (toolAction == MiningToolSegmentPolicy.Action.RELEASE_OWNED_SEGMENT) {
                baritone.cancelIfOwned(
                        operationId, lease.epoch(),
                        "legacy fixed mining segment lost its exact serviceable tool; releasing route ownership");
                MiningSessionPolicy.Decision pickup = MiningSessionPolicy.enterPickup(session, current);
                miningSessions.put(sessionKey, pickup.session());
                return Outcome.running(
                        "released legacy mining segment after exact serviceable tool loss",
                        Double.NaN, gained, false);
            }
            if (toolAction == MiningToolSegmentPolicy.Action.PROVISION_TOOL) {
                return provisionCommittedMiningTool(
                        planId, root, requiredTier, segmentBlocks,
                        missionItem(mission), progress.remainingItemCount(), nowMillis);
            }
            if (toolAction == MiningToolSegmentPolicy.Action.REBIND_OWNED_SEGMENT) {
                MiningToolBinding exact = miningToolBinding(
                        root, requiredTier, segmentBlocks).orElseThrow();
                baritone.cancelIfOwned(
                        operationId, lease.epoch(),
                        "route support exhausted with the exact mining tool still carried; rebinding its acknowledged hand");
                pushMiningToolBinding(planId, exact, nowMillis);
                return Outcome.continuePlan();
            }
            if (clearMiningToolCommitment(planId, root, nowMillis)) {
                return Outcome.continuePlan();
            }
        }

        BaritonePort.Goal goal = legacyMiningGoal(mission, root, operationId, segmentBlocks, blockTargets, surfaceWood);
        baritone.start(goal, lease);
        BaritonePort.Status status = baritone.poll(operationId);

        if (status.failureCause() == BaritonePort.FailureCause.ACCESS_TOOL_REQUIRED) {
            return insertMiningAccessTool(planId, root, sessionKey, session, current,
                    operationId, lease, nowMillis);
        }

        if (status.state() == BaritonePort.State.COMPLETE) {
            // Fabric's fixed segment quota may complete before the root's absolute
            // inventory objective (for example a multi-drop ore). End that exact
            // operation before considering a pickup handoff.
            baritone.cancelIfOwned(
                    operationId, lease.epoch(),
                    "fixed legacy mining segment ended; checking its loaded drops");
        }
        if (baritone.isOwnedMiningActive(operationId, lease.epoch())) {
            return observeLeaf(
                    root,
                    fromBaritone(status, "mining after satisfying tool prerequisites"),
                    lease,
                    nowMillis,
                    MINING_ACTION_POLICY);
        }
        if (status.state() == BaritonePort.State.BLOCKED
                || status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
            return observeLeaf(
                    root,
                    fromBaritone(status, "mining after satisfying tool prerequisites"),
                    lease,
                    nowMillis,
                    MINING_ACTION_POLICY);
        }

        GroundItemCollectionPolicy.Request dropRequest = legacyMiningDropRequest(
                planId, root, mining.expectedItemIds());
        if (groundItems.nearestLoadedCandidate(dropRequest, nowMillis).isPresent()) {
            MiningSessionPolicy.Decision pickup = MiningSessionPolicy.enterPickup(session, current);
            miningSessions.put(sessionKey, pickup.session());
            return Outcome.running(
                    "fixed mining segment ended; releasing it before collecting a loaded drop",
                    Double.NaN, gained, false);
        }
        MiningSessionPolicy.Decision pickup = MiningSessionPolicy.enterPickup(session, current);
        MiningSessionPolicy.Decision resumed = MiningSessionPolicy.resumeMining(
                pickup.session(), current);
        miningSessions.put(sessionKey, resumed.session());
        return observeLeaf(
                root,
                Outcome.running(
                        "fixed mining segment ended without a loaded drop; rebasing once",
                        Double.NaN, gained, false),
                lease,
                nowMillis,
                MINING_ACTION_POLICY);
    }

    private static BaritonePort.Goal legacyMiningGoal(
            Mission mission, PlanFrame root, String operationId, int segmentBlocks,
            String blockTargets, boolean surfaceWood) {
        Map<String, String> goalArguments = new LinkedHashMap<>(mission.parameters());
        goalArguments.put("blocks", blockTargets);
        goalArguments.put("count", Integer.toString(segmentBlocks));
        if (surfaceWood) goalArguments.put("worldSourceKind", "HARVEST");
        goalArguments.putAll(MiningEntranceCheckpoint.goalArguments(root.parameters(), "legacyMiningDropOrigin"));
        return new BaritonePort.Goal(operationId, "mine", goalArguments);
    }

    private MiningDropCollectionResult tickLegacyMiningDropCollection(
            String planId,
            PlanFrame frame,
            Set<String> expectedItems,
            ControlLease lease,
            long nowMillis,
            int gained,
            int requested) {
        GroundItemCollectionPolicy.Request request = legacyMiningDropRequest(
                planId, frame, expectedItems);
        ClientGroundItemCollectionController.Result result =
                groundItems.tick(request, lease, nowMillis, false);
        return switch (result.state()) {
            case COLLECTED -> {
                groundItems.clear(lease.epoch(), "verified legacy mined drop inventory increase");
                yield new MiningDropCollectionResult(
                        Outcome.running(
                                "verified collected a requested mining drop ("
                                        + gained + "/" + requested + ")",
                                0.0, gained + result.confirmedInventoryDelta(), false),
                        true);
            }
            case INVENTORY_FULL -> new MiningDropCollectionResult(
                    Outcome.capacity("making receiving room for the requested mined drops"),
                    false);
            case WAITING_FOR_WORLD -> new MiningDropCollectionResult(
                    Outcome.running(result.detail(), Double.NaN, gained, false), false);
            case APPROACHING, WAITING_FOR_PICKUP, PROGRESS -> new MiningDropCollectionResult(
                    Outcome.running(
                            result.detail(), result.distanceRemaining(), gained,
                            result.progressExpected()),
                    false);
            case LOST, RECOVERING -> new MiningDropCollectionResult(
                    Outcome.running(result.detail(), Double.NaN, gained, false), false);
            case SEARCHING -> MiningDropCollectionResult.completedResult();
            // The policy keeps this exact UUID exhausted for the leaf. Do not clear the
            // scope here, or the same unreachable item will immediately hijack mining again.
            case BLOCKED -> MiningDropCollectionResult.completedResult();
        };
    }

    private GroundItemCollectionPolicy.Request legacyMiningDropRequest(
            String planId,
            PlanFrame frame,
            Set<String> expectedItems) {
        Set<String> canonical = expectedItems.stream()
                .map(resourcePlanner::normalizeItem)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new GroundItemCollectionPolicy.Request(
                new GroundItemCollectionPolicy.Scope(planId, frame.id() + ":legacy-mined-drop"),
                canonical,
                dropOrigin(frame.parameters(), "legacyMiningDropOrigin"),
                MINING_DROP_RADIUS,
                60_000L,
                1,
                4_000L,
                3);
    }

    private Outcome tickLeaf(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            ActionLease actionLease,
            long nowMillis) throws IOException {
        return switch (frame.kind()) {
            case "mine" -> tickMineLeaf(planId, frame, lease, actionLease, nowMillis);
            case "goto" -> tickGotoLeaf(planId, frame, lease, nowMillis);
            case "build" -> tickBlueprintLeaf(planId,frame,lease,nowMillis);
            case "recover_death_drops" -> tickDeathDropRecoveryLeaf(
                    planId, frame, lease, nowMillis);
            case "hunt" -> tickHuntLeaf(planId, frame, lease, actionLease, nowMillis);
            case "craft" -> tickCraftLeaf(planId, frame, lease, actionLease, nowMillis);
            case "smelt" -> tickSmeltLeaf(planId, frame, lease, actionLease, nowMillis);
            case "fill_water" -> tickMissionWaterFill(planId, frame, lease, actionLease, nowMillis);
            case "equip" -> tickEquipLeaf(planId, frame, lease, actionLease, nowMillis);
            case "deliver" -> tickDeliveryLeaf(planId, frame, lease, actionLease, nowMillis);
            default -> Outcome.blocked("unsupported prerequisite action " + frame.kind());
        };
    }

    private Outcome tickBlueprintLeaf(String planId,PlanFrame frame,ControlLease lease,long nowMillis) throws IOException {
        Outcome supplied=finishBlueprintSupply(planId,frame,nowMillis);
        if(supplied!=null) return supplied;
        var project=blueprintProjects.require(required(frame.parameters(),"projectId"),required(frame.parameters(),"digest"));
        if(!dev.entity.client.blueprint.BlueprintProjects.siteLoaded(client,project))
            return returnToBlueprintSite(planId,frame,lease,project,nowMillis);
        BaritonePort.Status status;
        try {
            baritone.start(new BaritonePort.Goal(frame.id(),"build",frame.parameters()),lease);
            status=baritone.poll(frame.id());
        } catch(dev.entity.client.blueprint.BlueprintProjects.SiteChangedException changed) {
            baritone.cancel(lease.epoch(),"confirmed blueprint has an unsafe repair obstruction");
            actionSupervisor.clear(frame.id());
            return Outcome.blocked(changed.getMessage(),BaritonePort.FailureCause.WORLD_POLICY_DENIED);
        }
        if(status.state()==BaritonePort.State.COMPLETE) {
            completeLeaf(planId,frame,lease,status.detail(),nowMillis); return Outcome.continuePlan();
        }
        Outcome upkeep = tickPersonalWorkMaintenance(planId, frame, lease, nowMillis);
        if (upkeep != null) return upkeep;
        if(status.state()==BaritonePort.State.BLOCKED) {
            var remaining=dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.currentStage(
                    blueprintProjects.supplyMaterials(client,inventoryViewWithoutStations()),
                    baritone.blueprintLayerMaterials(frame.id(),lease.epoch()));
            if(!dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.exhausted(
                    remaining,inventoryViewWithoutStations()).isEmpty())
                return requestBlueprintSupply(planId,frame,lease,remaining,nowMillis);
        }
        Outcome observed=observeLeaf(frame,fromBaritone(status,"building confirmed blueprint"),lease,nowMillis,MINING_ACTION_POLICY);
        if(observed.failureCause()==BaritonePort.FailureCause.CONSTRUCTION_STALLED) {
            // A running native process is not proof that its next step is
            // executable. Diagnose supplies before escalating its local stall;
            // recovery must change a prerequisite, not restart the same job.
            var remaining=dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.currentStage(
                    blueprintProjects.supplyMaterials(client,inventoryViewWithoutStations()),
                    baritone.blueprintLayerMaterials(frame.id(),lease.epoch()));
            if(!dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy.exhausted(
                    remaining,inventoryViewWithoutStations()).isEmpty())
                return requestBlueprintSupply(planId,frame,lease,remaining,nowMillis);
        }
        return observed;
    }

    private Outcome returnToBlueprintSite(String planId,PlanFrame frame,ControlLease lease,
            dev.entity.client.blueprint.BlueprintProject project,long nowMillis) throws IOException {
        if(client.world==null||!client.world.getRegistryKey().getValue().toString().equals(project.dimension()))
            return Outcome.blocked("Entity must return to the confirmed project's dimension");
        if(lease!=null)baritone.cancel(lease.epoch(),"return to the confirmed site before observing or repairing it");
        actionSupervisor.suspend(frame.id(),nowMillis);
        var anchor=project.anchor();
        if(client.player!=null&&client.player.squaredDistanceTo(anchor.getX()+0.5,anchor.getY(),anchor.getZ()+0.5)<=25)
            return Outcome.running("At the confirmed build site; waiting for its actual client chunks",0,0,false);
        plans.pushPrerequisite(planId,new PlanFrame.Spec("goto","confirmed build site",1,
                Map.of("x",Integer.toString(anchor.getX()),"y",Integer.toString(anchor.getY()),
                        "z",Integer.toString(anchor.getZ()),"range","4")),
                "Return to the same confirmed build site; unavailable chunks are not changed blocks",nowMillis);
        return Outcome.continuePlan();
    }

    /** Finite planner-authored movement leaf used by managed multi-stage workflows. */
    private Outcome tickGotoLeaf(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            long nowMillis) throws IOException {
        LinkedHashMap<String, String> arguments =
                new LinkedHashMap<>(frame.parameters());
        arguments.putIfAbsent("range", "1");
        String operationId = frame.id();
        baritone.start(
                new BaritonePort.Goal(operationId, "goto", Map.copyOf(arguments)),
                lease);
        BaritonePort.Status status = baritone.poll(operationId);
        if (status.state() == BaritonePort.State.COMPLETE) {
            completeLeaf(
                    planId, frame, lease,
                    frame.parameters().getOrDefault(
                            "description", "reached the requested position"),
                    nowMillis);
            return Outcome.continuePlan();
        }
        return observeLeaf(
                frame,
                fromBaritone(
                        status,
                        frame.parameters().getOrDefault(
                                "description", "travelling to the requested position")),
                lease,
                nowMillis,
                MINING_ACTION_POLICY);
    }

    private Outcome tickMissionWaterFill(String planId, PlanFrame frame, ControlLease lease,
            ActionLease action, long nowMillis) throws IOException {
        if (homeWaterFill == null || missionWaterFenceFailure) return Outcome.blocked(
                "water-fill persistence is unavailable; no item use issued: " + homeWaterFillLoadFailure);
        if (frame.parameters().containsKey("waterFillInterrupted")) return Outcome.blocked(
                "previous water use settled unchanged after interruption; issue a new explicit water order");
        HomeWaterFillSession.Intent intent = homeWaterFill.snapshot().intent();
        Baseline baseline = baseline(planId, frame, nowMillis);
        if (!baseline.ready()) return Outcome.continuePlan();
        if (intent == null && frameObjectiveCount(frame, frame.target()) >= baseline.count() + frame.count()) {
            completeLeaf(planId, frame, lease, "verified acquired filled-water bucket", nowMillis);
            return Outcome.continuePlan();
        }
        if (intent != null && !intent.matchesMission(planId, frame.id())) return Outcome.blocked(
                "another exact water-fill intent remains unresolved; no second fill issued");
        if (intent == null) {
            if (inventory.count("minecraft:bucket") < 1) return pushMissingProductionResource(
                    planId, frame, "bucket", 1, "empty bucket was lost before filling", nowMillis,
                    planningInventoryViewForPlan(planId));
            ClientInventoryController.SafeCloseResult closed = inventory.closeHandledScreenIfCursorEmpty(nowMillis);
            if (closed != ClientInventoryController.SafeCloseResult.CLOSED)
                return Outcome.running("waiting for exact inventory acknowledgement/cursor before water fill", Double.NaN, 0, false);
            var source = workstations.findMissionWaterSource(HOME_WATER_SEARCH_RADIUS);
            if (source.isEmpty()) return Outcome.blocked(
                    "no loaded unprotected source water with a safe reachable dry interaction stand nearby");
            var exact = source.orElseThrow();
            int previous = client.player.getInventory().getSelectedSlot();
            intent = new HomeWaterFillSession.Intent("mission-water:" + UUID.randomUUID(), 0L, "", currentDimension(),
                    exact.source().getX(), exact.source().getY(), exact.source().getZ(), 0,
                    inventory.count("minecraft:bucket"), inventory.count("minecraft:water_bucket"),
                    waterFillHotbarSlot(previous), previous, HomeWaterFillSession.Phase.SELECTING_BUCKET,
                    0, 0L, "", nowMillis, HomeWaterFillSession.CallerKind.MISSION, planId, frame.id(),
                    workstations.waterSourceState(exact.source()), false);
            homeWaterFill.begin(intent, nowMillis);
            logger.info("Water fill selected caller=MISSION mission={} action={} source={},{},{} state={} emptyBefore={} filledBefore={} intent={}",
                    planId, frame.id(), intent.sourceX(), intent.sourceY(), intent.sourceZ(), intent.sourceState(),
                    intent.emptyBucketBefore(), intent.waterBucketBefore(), intent.id());
        }
        HomeIdleOutcome result = tickPendingWaterFill(lease, action,
                intent.matchesMission(planId, frame.id()) && intent.dimension().equals(currentDimension()), lastClientTick, nowMillis);
        if (homeWaterFill.snapshot().intent() == null) {
            completeLeaf(planId, frame, lease, "verified exact source fill: bucket -1, water_bucket +1", nowMillis);
            return Outcome.continuePlan();
        }
        return result.retainLease() ? Outcome.running(result.detail(), Double.NaN, 0, false)
                : Outcome.blocked(result.detail());
    }

    /** Passive Stop/restart settlement never uses, selects or routes. Unrelated work is not held by old fill debt. */
    private void observeFencedMissionWaterFill(long nowMillis) throws IOException {
        if (homeWaterFill == null || client.player == null) return;
        HomeWaterFillSession.Intent intent = homeWaterFill.snapshot().intent();
        if (intent == null || intent.homeCaller() || !intent.fenced()) return;
        if (!inventory.cursorEmpty() || !inventory.transactionDiagnostics().pendingClickOwner().isBlank()) return;
        int empty = inventory.count("minecraft:bucket");
        int water = inventory.count("minecraft:water_bucket");
        boolean filled = empty == intent.emptyBucketBefore() - 1 && water == intent.waterBucketBefore() + 1;
        boolean unchanged = empty == intent.emptyBucketBefore() && water == intent.waterBucketBefore();
        if (!filled && (!unchanged || nowMillis - intent.useIssuedAtMillis() < HomeWaterFillPolicy.RESULT_TIMEOUT_MILLIS)) return;
        TaskPlan owner = plans.restore(intent.missionId()).orElse(null);
        PlanFrame frame = owner != null && owner.state() == TaskPlanState.OPEN
                ? owner.currentFrame().filter(candidate -> candidate.id().equals(intent.actionId())).orElse(null) : null;
        if (filled && frame != null) return; // that exact leaf observes and commits its own completion
        if (unchanged && frame != null) {
            Map<String,String> checkpoint = new LinkedHashMap<>(frame.parameters());
            checkpoint.put("waterFillInterrupted", intent.id());
            plans.checkpointCurrent(intent.missionId(), checkpoint,
                    "issued water use settled unchanged after interruption; no automatic repeat", nowMillis);
        }
        if (filled) homeWaterFill.complete(intent.id(), nowMillis);
        else homeWaterFill.abandonUnchanged(intent.id(), nowMillis);
        logger.info("Water fill fenced settlement mission={} action={} filled={} unchanged={} intent={}; no new use issued",
                intent.missionId(), intent.actionId(), filled, unchanged, intent.id());
    }

    private Outcome tickMineLeaf(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            ActionLease actionLease,
            long nowMillis) throws IOException {
        Baseline baseline = baseline(planId, frame, nowMillis);
        if (!baseline.ready()) return Outcome.continuePlan();
        int desired = integer(frame.parameters(), "expectedMinimumItems", Math.toIntExact(frame.count()));
        int current = frameObjectiveCount(frame, frame.target());
        String blocks = required(frame.parameters(), "blockAlternatives");
        int minimumDrops = Math.max(1, integer(frame.parameters(), "minimumDropsPerBlock", 1));
        int estimatedBlocksPerDrop = Math.max(
                1, integer(frame.parameters(), "estimatedBlocksPerDrop", 1));
        boolean probabilistic = Boolean.parseBoolean(
                frame.parameters().getOrDefault("probabilistic", "false"));
        MiningSessionKey sessionKey = miningSessionKey(planId, frame);
        MiningSession session = miningSession(
                sessionKey,
                frame.id(),
                baseline.count(),
                current,
                desired,
                minimumDrops,
                estimatedBlocksPerDrop,
                probabilistic);
        MiningSessionPolicy.Decision progress = MiningSessionPolicy.observe(session, current);
        boolean unfinishedMiningCustody = session.hasMiningGoal()
                && baritone.isOwnedMiningActive(
                session.goalIdentity(), lease.epoch());
        if (MiningSessionPolicy.mayCommitItemCompletion(
                progress, unfinishedMiningCustody)) {
            Outcome exit = finishMiningExit(frame, "miningDropOrigin", lease, nowMillis);
            if (exit != null) return exit;
            completeLeaf(planId, frame, lease, "mined at least " + desired + " " + frame.target(), nowMillis);
            return Outcome.continuePlan();
        }
        // Keep the original mining segment identity while the exact actuator
        // finishes its atomic commitment. The inventory observation's COMPLETE
        // session intentionally has no goal identity and must not preempt it.
        if (!progress.complete()) session = progress.session();
        int gained = progress.acquiredItemCount();
        if (!frame.parameters().containsKey("miningDropOriginX")) {
            checkpointDropOrigin(planId, frame, "miningDropOrigin", nowMillis,
                    "recorded mining drop-collection origin");
            return Outcome.continuePlan();
        }
        if (!dropOriginMatchesCurrentDimension(frame.parameters(), "miningDropOrigin")) {
            baritone.cancelIfOwned(
                    session.hasMiningGoal() ? session.goalIdentity() : frame.id(),
                    lease.epoch(),
                    "mining drop origin changed dimension");
            miningSessions.remove(sessionKey);
            groundItems.clear(lease.epoch(), "mining drop origin changed dimension");
            checkpointDropOrigin(planId, frame, "miningDropOrigin", nowMillis,
                    "rebased mining drop collection after a dimension change");
            return Outcome.continuePlan();
        }

        if (session.segment() == MiningSession.Segment.PICKUP) {
            requireMiningActionOwner(actionLease, ActionOwner.GROUND_PICKUP);
            String endedOperationId = precedingMiningOperationId(session);
            if (baritone.isOwnedMiningActive(endedOperationId, lease.epoch())) {
                baritone.cancelIfOwned(
                        endedOperationId, lease.epoch(),
                        "waiting for the ended mining segment to release pickup actuators");
                return Outcome.running(
                        "waiting for the ended mining segment to become quiescent before pickup",
                        Double.NaN, gained, false);
            }
            MiningDropCollectionResult collection = tickMiningDropCollection(
                    planId, frame, lease, nowMillis, gained, desired);
            if (!collection.finished()) return collection.outcome();

            int afterCollection = frameObjectiveCount(frame, frame.target());
            MiningSessionPolicy.Decision after = MiningSessionPolicy.observe(session, afterCollection);
            if (after.complete()) {
                miningSessions.put(sessionKey, after.session());
                groundItems.clear(lease.epoch(), "collected cargo now requires its mine exit check");
                // Release GROUND_PICKUP before the next tick grants BARITONE_ROUTE.
                return Outcome.continuePlan();
            }
            MiningSessionPolicy.Decision resumed = MiningSessionPolicy.resumeMining(
                    after.session(), afterCollection);
            miningSessions.put(sessionKey, resumed.session());
            return collection.outcome() != null
                    ? collection.outcome()
                    : Outcome.running(
                            "mined-drop collection finished; rebasing the next fixed mining segment",
                            Double.NaN, resumed.acquiredItemCount(), false);
        }

        GroundItemCollectionPolicy.Request pendingDropRequest = miningDropRequest(planId, frame);
        boolean loadedExpectedDrop = groundItems.nearestLoadedCandidate(
                pendingDropRequest, nowMillis).isPresent();
        boolean miningActuatorOwnsCustody =
                baritone.isOwnedMiningActive(
                        session.goalIdentity(), lease.epoch());
        if (MiningSessionPolicy.shouldFenceDeterministicDrop(
                session, current, loadedExpectedDrop,
                miningActuatorOwnsCustody)) {
            String operationId = session.goalIdentity();
            baritone.cancelIfOwned(
                    operationId,
                    lease.epoch(),
                    loadedExpectedDrop
                            ? "loaded deterministic drop must be reconciled before another break"
                            : "deterministic inventory gain must be reconciled before another break");
            MiningSessionPolicy.Decision pickup = MiningSessionPolicy.enterPickup(session, current);
            miningSessions.put(sessionKey, pickup.session());
            return Outcome.running(
                    loadedExpectedDrop
                            ? "loaded deterministic drop fenced before the next mining block"
                            : "deterministic mining gain fenced before the next mining block",
                    Double.NaN,
                    pickup.acquiredItemCount(),
                    false);
        }

        Outcome upkeep = tickPersonalWorkMaintenance(planId, frame, lease, nowMillis);
        if (upkeep != null) return upkeep;
        requireMiningActionOwner(actionLease, ActionOwner.BARITONE_ROUTE);
        int segmentBlocks = session.goalBlockCount();
        int requiredTier = tierRank(frame.parameters().getOrDefault("requiredToolTier", "NONE"));
        String expectedItems = WoodLogFamilyPolicy.isFamilyRequest(frame.target())
                ? WoodLogFamilyPolicy.expectedItemsArgument()
                : frame.target();
        Map<String, String> args = miningGoalArguments(
                frame, blocks,
                // Item completion and block-sampling limits are deliberately separate. A
                // four-redstone objective must not stop after the first ore merely because the
                // block quota is one; an apple search may stop either on real apples or after its
                // bounded leaf sample.
                // The live remaining count is mission accounting, not part of a running
                // operation's identity. Keep the segment quota immutable until the explicit
                // pickup/resume transition creates a new generation.
                session.goalItemCount(), segmentBlocks, probabilistic, expectedItems);
        String operationId = session.goalIdentity();
        if (HarvestToolAccessPolicy.shouldPrepare(
                baritone.ownsOperation(operationId, lease.epoch()),
                frame.parameters().getOrDefault("worldSourceKind", ""), blocks)) {
            Outcome preparation = prepareCarriedTreeTool(planId, blocks, nowMillis);
            if (preparation != null) return preparation;
        }
        if (requiredTier > 0) {
            boolean ownedMining = baritone.ownsOperation(operationId, lease.epoch());
            String committedTool = frame.parameters().getOrDefault("plannedTool", "");
            if (!ownedMining) {
                Optional<MiningToolBinding> binding = miningToolBinding(
                        frame, requiredTier, segmentBlocks);
                if (binding.isEmpty()) {
                    return isCommittedUniversalAction(frame)
                            ? invalidateUniversalProgramAndRebase(
                                    planId, frame, DivergenceCause.TOOL_LOSS,
                                    "no exact serviceable " + committedTool
                                            + " remains for the committed mining segment",
                                    nowMillis)
                            : provisionCommittedMiningTool(
                                    planId, frame, requiredTier, segmentBlocks,
                                    frame.target(), progress.remainingItemCount(), nowMillis);
                }
                MiningToolBinding exact = binding.orElseThrow();
                int safeSegment = exact.safeSegmentBlocks();
                if (safeSegment > 0) {
                    MiningSession bounded = MiningSessionPolicy.boundUnownedSegment(
                            session, safeSegment);
                    if (bounded != session) {
                        session = bounded;
                        miningSessions.put(sessionKey, session);
                        segmentBlocks = session.goalBlockCount();
                        args = miningGoalArguments(
                                frame, blocks, session.goalItemCount(), segmentBlocks,
                                probabilistic, expectedItems);
                    }
                }
                if (!selectedMiningToolMatches(
                        requiredTier, exact.item(), exact.minimumDurability())) {
                    pushMiningToolBinding(planId, exact, nowMillis);
                    return Outcome.continuePlan();
                }
            }
            MiningToolSegmentPolicy.Action toolAction = MiningToolSegmentPolicy.decide(
                    ownedMining,
                    hasBoundServiceableMiningTool(frame, requiredTier),
                    selectedMiningToolMatches(
                            requiredTier,
                            frame.parameters().getOrDefault("plannedTool", ""),
                            2),
                    ownedMining && baritone.hasCarriedPlacementSupport(
                            operationId, lease.epoch()),
                    !ownedMining);
            if (toolAction == MiningToolSegmentPolicy.Action.RELEASE_OWNED_SEGMENT) {
                baritone.cancelIfOwned(
                        operationId, lease.epoch(),
                        "fixed mining segment lost its exact serviceable tool; releasing route ownership");
                MiningSessionPolicy.Decision pickup = MiningSessionPolicy.enterPickup(session, current);
                miningSessions.put(sessionKey, pickup.session());
                return Outcome.running(
                        "released mining segment after exact serviceable tool loss",
                        Double.NaN, gained, false);
            }
            if (toolAction == MiningToolSegmentPolicy.Action.PROVISION_TOOL) {
                return provisionCommittedMiningTool(
                        planId, frame, requiredTier, segmentBlocks,
                        frame.target(), progress.remainingItemCount(), nowMillis);
            }
            if (toolAction == MiningToolSegmentPolicy.Action.REBIND_OWNED_SEGMENT) {
                MiningToolBinding exact = miningToolBinding(
                        frame, requiredTier, segmentBlocks).orElseThrow();
                baritone.cancelIfOwned(
                        operationId, lease.epoch(),
                        "route support exhausted with the exact mining tool still carried; rebinding its acknowledged hand");
                pushMiningToolBinding(planId, exact, nowMillis);
                return Outcome.continuePlan();
            }
            if (clearMiningToolCommitment(planId, frame, nowMillis)) {
                return Outcome.continuePlan();
            }
        }
        if (idleStockCycle != null && idleStockCycle.undergroundSite != null
                && homeEconomy.snapshot().pendingAcquisition() != null
                && homeEconomy.snapshot().pendingAcquisition().planId().equals(planId)) {
            Map<String, String> boundedArgs = new LinkedHashMap<>(args);
            boundedArgs.put("idleUndergroundMaxY", Integer.toString(idleStockCycle.undergroundSite.maxTargetY()));
            args = Map.copyOf(boundedArgs);
        }
        BaritonePort.Goal goal = new BaritonePort.Goal(operationId, "mine", args);
        baritone.start(goal, lease);
        BaritonePort.Status status = baritone.poll(operationId);

        if (status.failureCause() == BaritonePort.FailureCause.ACCESS_TOOL_REQUIRED) {
            return insertMiningAccessTool(planId, frame, sessionKey, session, current,
                    operationId, lease, nowMillis);
        }

        int afterPollCount = frameObjectiveCount(frame, frame.target());
        MiningSessionPolicy.Decision afterPoll = MiningSessionPolicy.observe(session, afterPollCount);
        boolean unfinishedAfterPoll = status.state() != BaritonePort.State.COMPLETE
                && baritone.isOwnedMiningActive(
                operationId, lease.epoch());
        if (MiningSessionPolicy.mayCommitItemCompletion(
                afterPoll, unfinishedAfterPoll)) {
            Outcome exit = finishMiningExit(frame, "miningDropOrigin", lease, nowMillis);
            if (exit != null) return exit;
            completeLeaf(planId, frame, lease, status.detail(), nowMillis);
            return Outcome.continuePlan();
        }

        if (status.state() == BaritonePort.State.COMPLETE
                && Boolean.parseBoolean(frame.parameters().getOrDefault(
                "completeWhenResourceExhausted", "false"))) {
            // The exact crop actuator reports COMPLETE only after its crop/drop/replant
            // commitment settled, or after proving no eligible mature crop remains.
            completeLeaf(planId, frame, lease, status.detail(), nowMillis);
            return Outcome.continuePlan();
        }

        if (status.state() == BaritonePort.State.COMPLETE) {
            baritone.cancelIfOwned(
                    operationId, lease.epoch(),
                    "fixed mining segment ended; checking its loaded drops");
        }
        if (baritone.isOwnedMiningActive(operationId, lease.epoch())) {
            return observeLeaf(
                    frame,
                    fromBaritone(
                            status,
                            frame.parameters().getOrDefault(
                                    "description", frame.displayName())),
                    lease,
                    nowMillis,
                    MINING_ACTION_POLICY);
        }
        if (status.state() == BaritonePort.State.BLOCKED
                || status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
            return observeLeaf(
                    frame,
                    fromBaritone(
                            status,
                            frame.parameters().getOrDefault(
                                    "description", frame.displayName())),
                    lease,
                    nowMillis,
                    MINING_ACTION_POLICY);
        }

        GroundItemCollectionPolicy.Request dropRequest = miningDropRequest(planId, frame);
        if (groundItems.nearestLoadedCandidate(dropRequest, nowMillis).isPresent()) {
            MiningSessionPolicy.Decision pickup = MiningSessionPolicy.enterPickup(
                    session, afterPollCount);
            miningSessions.put(sessionKey, pickup.session());
            return Outcome.running(
                    "fixed mining segment ended; releasing it before collecting a loaded drop",
                    Double.NaN, afterPoll.acquiredItemCount(), false);
        }

        // No explicit collection owner is needed when the ended segment left no
        // loaded requested drop. Rebase exactly once so the next tick starts a
        // new immutable goal rather than refreshing a quiescent MineProcess.
        MiningSessionPolicy.Decision pickup = MiningSessionPolicy.enterPickup(
                session, afterPollCount);
        MiningSessionPolicy.Decision resumed = MiningSessionPolicy.resumeMining(
                pickup.session(), afterPollCount);
        miningSessions.put(sessionKey, resumed.session());
        return observeLeaf(
                frame,
                Outcome.running(
                        "fixed mining segment ended without a loaded drop; rebasing once",
                        Double.NaN, afterPoll.acquiredItemCount(), false),
                lease,
                nowMillis,
                MINING_ACTION_POLICY);
    }

    /**
     * A route prerequisite belongs to the existing acquisition stack, not to the
     * native miner. Target harvestability and route excavation are different facts:
     * dirt can be hand-mined while intervening stone requires a carried pickaxe.
     * Only observed native access failure inserts this work; surface hand gathering
     * is unchanged. The saved leaf's absolute count and original entrance survive.
     */
    private Outcome insertMiningAccessTool(
            String planId, PlanFrame parent, MiningSessionKey key, MiningSession session,
            int current, String operationId, ControlLease lease, long now) throws IOException {
        baritone.cancelIfOwned(operationId, lease.epoch(), "handing route equipment to acquisition");
        MiningSession pickup = MiningSessionPolicy.enterPickup(session, current).session();
        MiningSession next = MiningSessionPolicy.resumeMining(pickup, current).session();
        miningSessions.put(key, next);
        // A second missing-tool request inside this same preparation would recurse
        // without changing capability. Return an explicit, finite dependency failure.
        if (plans.restore(planId).orElseThrow().frames().stream()
                .anyMatch(f -> f.kind().equals("root.mining_access"))) {
            return Outcome.blocked("Cannot prepare the route tool from the currently reachable supplies; "
                    + "the original objective and collected items are retained");
        }
        String prefix = parent.kind().equals("root.legacy_mine") ? "legacyMiningDropOrigin" : "miningDropOrigin";
        var entrance = MiningEntranceCheckpoint.fromGoal(
                MiningEntranceCheckpoint.goalArguments(parent.parameters(), prefix), currentDimension());
        int accessWork = entrance.map(e -> 2 * (Math.abs(client.player.getBlockX() - e.x())
                + Math.abs(client.player.getBlockY() - e.y()) + Math.abs(client.player.getBlockZ() - e.z())))
                .orElse(1);
        plans.pushPrerequisite(planId, new PlanFrame.Spec("root.mining_access", parent.target(), 1,
                Map.of("description", "Prepare missing route equipment, then resume " + parent.displayName(),
                        "accessWork", Integer.toString(Math.max(1, accessWork)))),
                "native route needs equipment; preserving partial cargo and original mine entrance", now);
        return Outcome.continuePlan();
    }

    private Outcome tickMiningAccessTool(String planId, PlanFrame frame, long now) throws IOException {
        String item = frame.parameters().get("accessToolItem");
        if (item == null && !hasUsablePickaxe(1)) {
            // Reuse the same carried-material/durability cost model as ordinary
            // mining. Commit one tool, not the dirt quota's worth of pickaxes.
            ToolStrategy.Choice choice = preferredPickaxeChoice(1,
                    integer(frame.parameters(), "accessWork", 1), frame.target(), 0);
            Map<String, String> facts = new LinkedHashMap<>(frame.parameters());
            facts.put("accessToolItem", choice.item());
            facts.put("accessToolCount", Integer.toString(canonicalCount(choice.item()) + 1));
            plans.checkpointCurrent(planId, facts, "selected one serviceable route tool from actual supplies", now);
            return Outcome.continuePlan();
        }
        if (item != null) {
            Outcome acquisition = driveUniversalProgram(planId, frame,
                    List.of(new AcquisitionRequest.ItemGoal(item,
                            integer(frame.parameters(), "accessToolCount", 1))), now, false);
            if (acquisition != null) return acquisition;
        }
        if (!hasUsablePickaxe(1)) {
            return Outcome.blocked("Route-tool preparation ended without a serviceable pickaxe; original mission retained");
        }
        if (!selectedMiningToolMatches(1, "", 2)) {
            MiningToolBinding binding = miningToolBinding(frame, 1, 1).orElseThrow();
            pushMiningToolBinding(planId, binding, now);
            return Outcome.continuePlan();
        }
        plans.completeCurrent(planId, "usable route tool physically equipped; resuming remaining acquisition", now);
        return Outcome.continuePlan();
    }

    /**
     * Cargo completion survives a stopped adapter, so its exit obligation must survive too.
     * Reuses the existing leaf checkpoint and ordinary exact goto; it never restarts mining
     * with a fresh inventory baseline or creates a second movement controller.
     */
    private Outcome finishMiningExit(
            PlanFrame frame, String checkpointPrefix, ControlLease lease, long nowMillis) {
        if (!HarvestToolAccessPolicy.requiresExtractionReturn(
                frame.parameters().get("worldSourceKind"), frame.parameters().get("blocks"))) return null;
        Map<String, String> checkpoint = MiningEntranceCheckpoint.goalArguments(
                frame.parameters(), checkpointPrefix);
        if (checkpoint.isEmpty()) return null;
        if (client.player == null || client.world == null) {
            return Outcome.running("waiting for the recorded mine exit world", Double.NaN, 0, false);
        }
        if (!MiningEntranceCheckpoint.returnRequired(true, checkpoint, currentDimension(),
                client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ(),
                client.player.isOnGround())) return null;
        Optional<MiningEntranceCheckpoint.Origin> entrance = MiningEntranceCheckpoint.fromGoal(
                checkpoint, currentDimension());
        if (entrance.isEmpty()) {
            return Outcome.blocked("collected mining cargo still requires return in its original dimension");
        }
        MiningEntranceCheckpoint.Origin target = entrance.orElseThrow();
        String operationId = frame.id() + ":mine-exit";
        boolean alreadyOwned = baritone.ownsOperation(operationId, lease.epoch());
        baritone.start(new BaritonePort.Goal(operationId, "goto", Map.of(
                "x", Integer.toString(target.x()), "y", Integer.toString(target.y()),
                "z", Integer.toString(target.z()), "dimension", target.dimension(), "range", "0")), lease);
        BaritonePort.Status status = baritone.poll(operationId);
        if (status.state() == BaritonePort.State.COMPLETE) {
            return Outcome.running("settling at the recorded mine entrance", 0, 0, false);
        }
        Outcome route = fromBaritone(status, "returning collected cargo to the original mine entrance");
        if (!alreadyOwned && route.state() == State.RUNNING) {
            // The outbound objective may already have distance zero and a visited envelope
            // containing this entire exit. Establish one fresh return-phase baseline.
            route = Outcome.running(route.detail(), route.distanceRemaining(),
                    route.completedWorkUnits(), false);
        }
        return observeLeaf(frame, route, lease, nowMillis, MINING_ACTION_POLICY);
    }

    /**
     * Opportunistically preempts mining only when a loaded requested drop actually exists.
     * Returning null means there is no collection work and MineProcess may continue.
     */
    private MiningDropCollectionResult tickMiningDropCollection(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            long nowMillis,
            int gained,
            int desired) {
        GroundItemCollectionPolicy.Request request = miningDropRequest(planId, frame);
        ClientGroundItemCollectionController.Result result =
                groundItems.tick(request, lease, nowMillis, false);
        return switch (result.state()) {
            case COLLECTED -> {
                groundItems.clear(lease.epoch(), "verified mined drop inventory increase");
                yield new MiningDropCollectionResult(
                        Outcome.running(
                                "verified collected mined " + frame.target() + " ("
                                        + Math.max(gained, gained + result.confirmedInventoryDelta())
                                        + "/" + desired + ")",
                                0.0, gained + result.confirmedInventoryDelta(), false),
                        true);
            }
            case INVENTORY_FULL -> new MiningDropCollectionResult(
                    Outcome.capacity("making receiving room for mined " + frame.target()),
                    false);
            case WAITING_FOR_WORLD -> new MiningDropCollectionResult(
                    Outcome.running(result.detail(), Double.NaN, gained, false), false);
            case APPROACHING, WAITING_FOR_PICKUP, PROGRESS -> new MiningDropCollectionResult(
                    Outcome.running(
                            result.detail(), result.distanceRemaining(), gained,
                            result.progressExpected()),
                    false);
            case LOST, RECOVERING -> new MiningDropCollectionResult(
                    Outcome.running(result.detail(), Double.NaN, gained, false), false);
            case SEARCHING -> MiningDropCollectionResult.completedResult();
            // The policy permanently ignores this exhausted UUID for this leaf. Mining
            // continues and a later item entity can still be selected independently.
            case BLOCKED -> MiningDropCollectionResult.completedResult();
        };
    }

    private GroundItemCollectionPolicy.Request miningDropRequest(
            String planId,
            PlanFrame frame) {
        boolean routeWorldMutationAllowed = !Boolean.parseBoolean(
                frame.parameters().getOrDefault("managedFarm", "false"));
        return new GroundItemCollectionPolicy.Request(
                new GroundItemCollectionPolicy.Scope(planId, frame.id() + ":mined-drop"),
                collectionItems(frame.target()),
                dropOrigin(frame.parameters(), "miningDropOrigin"),
                MINING_DROP_RADIUS,
                60_000L,
                1,
                4_000L,
                3,
                routeWorldMutationAllowed);
    }

    private Outcome tickHuntLeaf(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            ActionLease actionLease,
            long nowMillis) throws IOException {
        Baseline baseline = baseline(planId, frame, nowMillis);
        if (!baseline.ready()) return Outcome.continuePlan();
        int desired = integer(frame.parameters(), "expectedMinimumItems", Math.toIntExact(frame.count()));
        int gained = Math.max(0, frameObjectiveCount(frame, frame.target()) - baseline.count());
        int minimumDrops = Math.max(1,
                integer(frame.parameters(), "minimumDropsPerKill", 1));
        if (gained >= desired) {
            completeLeaf(planId, frame, lease,
                    "collected at least " + desired + " " + frame.target() + " from hunting",
                    nowMillis);
            return Outcome.continuePlan();
        }

        if (frame.parameters().containsKey("dropCollectionStartedAt")) {
            return tickHuntDropCollection(planId, frame, lease, nowMillis, gained, desired);
        }

        if (!frame.parameters().containsKey("huntSearchStartedAt")) {
            checkpointHuntSearch(planId, frame, nowMillis,
                    "recorded bounded animal-search origin");
            return Outcome.continuePlan();
        }
        if (!dropOriginMatchesCurrentDimension(frame.parameters(), "huntSearchOrigin")) {
            groundItems.clear(lease.epoch(), "hunt search changed dimension");
            checkpointHuntSearch(planId, frame, nowMillis,
                    "rebased bounded animal search after a dimension change");
            return Outcome.continuePlan();
        }

        // Reconcile a kill/drop that arrived just before combat was preempted or
        // the client restarted. This probe is read-only: it cannot cancel the live
        // hunt until the collection transaction has first been durably checkpointed.
        BlockPos playerOrigin = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
        GroundItemCollectionPolicy.Request recoveryProbe = new GroundItemCollectionPolicy.Request(
                new GroundItemCollectionPolicy.Scope(planId, frame.id() + ":hunt-recovery-probe"),
                collectionItems(frame.target()),
                new GroundItemCollectionPolicy.Point(
                        playerOrigin.getX() + 0.5, playerOrigin.getY() + 0.5, playerOrigin.getZ() + 0.5),
                64.0,
                60_000L,
                huntDropCollectionDelta(desired, gained, minimumDrops),
                4_000L,
                3);
        Optional<GroundItemCollectionPolicy.Candidate> orphanedDrop =
                huntRecoveryProbeAllowed(frame.parameters(), nowMillis)
                        ? groundItems.nearestLoadedCandidate(recoveryProbe, nowMillis)
                        : Optional.empty();
        if (orphanedDrop.isPresent()) {
            GroundItemCollectionPolicy.Candidate candidate = orphanedDrop.orElseThrow();
            BlockPos origin = BlockPos.ofFloored(
                    candidate.position().x(), candidate.position().y(), candidate.position().z());
            checkpointHuntDropPhase(
                    planId, frame, origin,
                    huntDropCollectionDelta(desired, gained, minimumDrops), nowMillis,
                    "found an expected hunt drop left by an interrupted combat phase");
            baritone.cancelIfOwned(frame.id(), lease.epoch(),
                    "reconciling a loaded expected hunt drop");
            return Outcome.continuePlan();
        }

        int remainingKills = ceilDiv(Math.max(1, desired - gained), minimumDrops);
        ResourcePlanner.InventoryView huntInventory =
                planningInventoryViewForPlan(planId);
        CombatLoadoutPolicy.Decision loadout = CombatLoadoutPolicy.passiveHunt(
                remainingKills,
                huntInventory.itemCounts(),
                equippedItems());
        if (loadout.action() == CombatLoadoutPolicy.Action.ACQUIRE) {
            return pushExactMissingResource(
                    planId,
                    loadout.item(),
                    1,
                    loadout.reason(),
                    nowMillis,
                    huntInventory,
                    "exact passive-hunt loadout acquisition");
        }

        String entities = required(frame.parameters(), "entityAlternatives");
        String entity = Arrays.stream(entities.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("hunt action has no entity target"));
        Map<String, String> huntArguments = new LinkedHashMap<>();
        huntArguments.put("entityTypes", entities);
        huntArguments.put("huntSearchOriginX", frame.parameters().get("huntSearchOriginX"));
        huntArguments.put("huntSearchOriginZ", frame.parameters().get("huntSearchOriginZ"));
        huntArguments.put("huntSearchRadius", Integer.toString(HUNT_SEARCH_RADIUS));
        huntArguments.put("huntSearchDeadlineAt", Long.toString(
                longInteger(frame.parameters(), "huntSearchStartedAt", nowMillis)
                        + HUNT_SEARCH_TIMEOUT_MILLIS));
        BaritonePort.Goal goal = new BaritonePort.Goal(
                frame.id(),
                "hunt",
                huntArguments);
        baritone.start(goal, lease);
        // During pursuit Baritone owns the hand and autoTool may legitimately
        // select a pickaxe for route excavation. Only after the target is in
        // direct reach does combat suppress autoTool and request a sword. Once
        // that click is pending, the hand remains fenced through a brief target
        // movement so route autoTool cannot undo the unacknowledged SWAP.
        String weaponTransactionOwner = inventoryTransactionOwner(actionLease, "hunt-equip");
        boolean weaponTransactionPending = inventory.transactionDiagnostics()
                .pendingClickOwner().equals(weaponTransactionOwner);
        if (baritone.prepareHuntCombatHand(frame.id(), weaponTransactionPending)) {
            Outcome weapon = equipBestCarriedWeapon(actionLease, nowMillis);
            if (weapon != null) return observeLeaf(
                    frame, weapon, lease, nowMillis, INVENTORY_ACTION_POLICY);
        }
        BaritonePort.Status status = baritone.poll(frame.id());
        if (status.state() == BaritonePort.State.COMPLETE && gained < desired) {
            BlockPos origin = baritone.completedAttackOrigin(frame.id())
                    .orElseGet(() -> client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos())
                    .toImmutable();
            baritone.resourcePerception().rememberProducedDrops(frame.id(), origin, collectionItems(frame.target()));
            checkpointHuntDropPhase(
                    planId, frame, origin,
                    huntDropCollectionDelta(desired, gained, minimumDrops), nowMillis,
                    "target defeated; awaiting verified " + frame.target() + " pickup");
            baritone.cancelIfOwned(frame.id(), lease.epoch(),
                    "hunt target defeated; switching to ground-item collection");
            return Outcome.continuePlan();
        }
        return observeLeaf(frame,
                fromBaritone(status, "hunting " + entity + " for " + frame.target()),
                lease,
                nowMillis,
                HUNT_ACTION_POLICY);
    }

    /**
     * A hunt leaf owns the complete aggregate objective, but one ground-item
     * transaction can only prove the drops from one defeated target. Variable
     * drops may overshoot the remaining objective; the next leaf tick observes
     * that inventory increase and completes without scheduling another kill.
     */
    static int huntDropCollectionDelta(int desired, int gained, int minimumDropsPerKill) {
        if (desired <= 0 || gained < 0 || minimumDropsPerKill <= 0) {
            throw new IllegalArgumentException("invalid hunt collection counts");
        }
        int remaining = Math.max(0, desired - gained);
        return Math.min(remaining, minimumDropsPerKill);
    }

    static int huntDropCollectionDelta(
            int desired,
            int gained,
            int minimumDropsPerKill,
            int recordedRequiredDelta) {
        if (recordedRequiredDelta <= 0) {
            throw new IllegalArgumentException("recorded hunt collection delta must be positive");
        }
        return Math.min(
                recordedRequiredDelta,
                huntDropCollectionDelta(desired, gained, minimumDropsPerKill));
    }

    private Outcome tickHuntDropCollection(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            long nowMillis,
            int gained,
            int desired) throws IOException {
        if (!dropOriginMatchesCurrentDimension(frame.parameters(), "dropOrigin")) {
            groundItems.clear(lease.epoch(), "hunt drop origin changed dimension");
            checkpointWithoutDropCollection(
                    planId, frame,
                    "left the defeated target's dimension; hunting a replacement",
                    nowMillis,
                    true);
            return Outcome.continuePlan();
        }
        long startedAt = longInteger(frame.parameters(), "dropCollectionStartedAt", nowMillis);
        BlockPos fallbackOrigin = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
        int minimumDrops = Math.max(1,
                integer(frame.parameters(), "minimumDropsPerKill", 1));
        int recordedDelta = Math.max(1, integer(
                frame.parameters(), "dropCollectionRequiredDelta", minimumDrops));
        GroundItemCollectionPolicy.Request request = new GroundItemCollectionPolicy.Request(
                new GroundItemCollectionPolicy.Scope(
                        planId, frame.id() + ":hunt-drop:" + startedAt),
                collectionItems(frame.target()),
                new GroundItemCollectionPolicy.Point(
                        integer(frame.parameters(), "dropOriginX", fallbackOrigin.getX()) + 0.5,
                        integer(frame.parameters(), "dropOriginY", fallbackOrigin.getY()) + 0.5,
                        integer(frame.parameters(), "dropOriginZ", fallbackOrigin.getZ()) + 0.5),
                14.0,
                60_000L,
                huntDropCollectionDelta(
                        desired, gained, minimumDrops, recordedDelta),
                4_000L,
                3);
        ClientGroundItemCollectionController.Result result =
                groundItems.tick(request, lease, nowMillis);
        long elapsed = Math.max(0L, nowMillis - startedAt);
        switch (result.state()) {
            case COLLECTED -> {
                groundItems.clear(lease.epoch(), "verified hunted drop pickup");
                checkpointWithoutDropCollection(planId, frame,
                        "verified hunted drop pickup; checking requested quantity",
                        nowMillis,
                        false);
                return Outcome.continuePlan();
            }
            case INVENTORY_FULL -> {
                return Outcome.capacity("making receiving room for hunted " + frame.target());
            }
            case WAITING_FOR_WORLD -> {
                return Outcome.running(result.detail(), Double.NaN, gained, false);
            }
            case SEARCHING -> {
                if (elapsed < DROP_SPAWN_GRACE_MILLIS) {
                    return Outcome.running(
                            "waiting for the defeated " + frame.target() + " drop to spawn",
                            Double.NaN, gained, false);
                }
                // No matching entity ever appeared. Any immediate pickup is already
                // reflected in the mission inventory baseline, so select another adult.
            }
            case APPROACHING, WAITING_FOR_PICKUP, PROGRESS -> {
                return Outcome.running(
                        result.detail() + " (" + gained + "/" + desired + " " + frame.target() + ")",
                        result.distanceRemaining(), gained, result.progressExpected());
            }
            case RECOVERING -> {
                if (huntDropRecoveryMayContinue(startedAt, nowMillis)) {
                    return Outcome.running(
                            result.detail() + " (" + gained + "/" + desired + " " + frame.target() + ")",
                            result.distanceRemaining(), gained, result.progressExpected());
                }
            }
            case LOST -> {
                if (huntDropRecoveryMayContinue(startedAt, nowMillis)) {
                    return Outcome.running(result.detail(), Double.NaN, gained, false);
                }
            }
            case BLOCKED -> {
                // One unreachable or hazardous carcass drop is not a terminal food mission:
                // abandon that bounded collection transaction and obtain a replacement.
            }
        }
        groundItems.clear(lease.epoch(), "hunted drop was not safely collectible");
        checkpointWithoutDropCollection(planId, frame,
                "drop was absent, stolen, hazardous, or unreachable; hunting a replacement",
                nowMillis,
                true);
        return Outcome.continuePlan();
    }

    static boolean huntRecoveryProbeAllowed(Map<String, String> parameters, long nowMillis) {
        Objects.requireNonNull(parameters, "parameters");
        return nowMillis >= longInteger(
                parameters, HUNT_RECOVERY_PROBE_SUPPRESSED_UNTIL, 0L);
    }

    static boolean huntDropRecoveryMayContinue(long startedAtMillis, long nowMillis) {
        return Math.max(0L, nowMillis - startedAtMillis) < DROP_COLLECTION_TIMEOUT_MILLIS;
    }

    private void checkpointWithoutDropCollection(
            String planId,
            PlanFrame frame,
            String detail,
            long nowMillis,
            boolean suppressRecoveryProbe) throws IOException {
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        checkpoint.remove("dropCollectionStartedAt");
        checkpoint.remove("dropOriginX");
        checkpoint.remove("dropOriginY");
        checkpoint.remove("dropOriginZ");
        checkpoint.remove("dropOriginDimension");
        checkpoint.remove("dropCollectionRequiredDelta");
        BlockPos origin = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
        checkpoint.put("huntSearchStartedAt", Long.toString(nowMillis));
        checkpoint.put("huntSearchOriginX", Integer.toString(origin.getX()));
        checkpoint.put("huntSearchOriginY", Integer.toString(origin.getY()));
        checkpoint.put("huntSearchOriginZ", Integer.toString(origin.getZ()));
        checkpoint.put("huntSearchOriginDimension", currentDimension());
        if (suppressRecoveryProbe) {
            checkpoint.put(HUNT_RECOVERY_PROBE_SUPPRESSED_UNTIL, Long.toString(
                    Math.addExact(nowMillis, HUNT_RECOVERY_PROBE_SUPPRESSION_MILLIS)));
        } else {
            checkpoint.remove(HUNT_RECOVERY_PROBE_SUPPRESSED_UNTIL);
        }
        plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
    }

    private void checkpointHuntDropPhase(
            String planId,
            PlanFrame frame,
            BlockPos origin,
            int requiredDelta,
            long nowMillis,
            String detail) throws IOException {
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        checkpoint.remove(HUNT_RECOVERY_PROBE_SUPPRESSED_UNTIL);
        checkpoint.put("dropCollectionStartedAt", Long.toString(nowMillis));
        checkpoint.put("dropCollectionRequiredDelta", Integer.toString(Math.max(1, requiredDelta)));
        checkpoint.put("dropOriginX", Integer.toString(origin.getX()));
        checkpoint.put("dropOriginY", Integer.toString(origin.getY()));
        checkpoint.put("dropOriginZ", Integer.toString(origin.getZ()));
        checkpoint.put("dropOriginDimension", currentDimension());
        plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
    }

    private void checkpointHuntSearch(
            String planId,
            PlanFrame frame,
            long nowMillis,
            String detail) throws IOException {
        BlockPos origin = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        checkpoint.put("huntSearchStartedAt", Long.toString(nowMillis));
        checkpoint.put("huntSearchOriginX", Integer.toString(origin.getX()));
        checkpoint.put("huntSearchOriginY", Integer.toString(origin.getY()));
        checkpoint.put("huntSearchOriginZ", Integer.toString(origin.getZ()));
        checkpoint.put("huntSearchOriginDimension", currentDimension());
        plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
    }

    private void checkpointDropOrigin(
            String planId,
            PlanFrame frame,
            String prefix,
            long nowMillis,
            String detail) throws IOException {
        BlockPos origin = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        checkpoint.put(prefix + "X", Integer.toString(origin.getX()));
        checkpoint.put(prefix + "Y", Integer.toString(origin.getY()));
        checkpoint.put(prefix + "Z", Integer.toString(origin.getZ()));
        checkpoint.put(prefix + "Dimension", currentDimension());
        plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
    }

    private boolean dropOriginMatchesCurrentDimension(
            Map<String, String> parameters,
            String prefix) {
        String recorded = parameters.getOrDefault(prefix + "Dimension", "");
        String current = currentDimension();
        // With no loaded world we cannot disprove ownership. Once a world exists,
        // an old checkpoint lacking dimension is deliberately treated as unknown
        // instead of interpreting the coordinates in whichever dimension loaded.
        return current.isBlank() || (!recorded.isBlank() && recorded.equals(current));
    }

    private String currentDimension() {
        return client.world == null
                ? ""
                : client.world.getRegistryKey().getValue().toString();
    }

    private static GroundItemCollectionPolicy.Point dropOrigin(
            Map<String, String> parameters,
            String prefix) {
        return new GroundItemCollectionPolicy.Point(
                integer(parameters, prefix + "X", 0) + 0.5,
                integer(parameters, prefix + "Y", 0) + 0.5,
                integer(parameters, prefix + "Z", 0) + 0.5);
    }

    private Outcome equipBestCarriedWeapon(ActionLease actionLease, long nowMillis) {
        for (String weapon : List.of(
                "netherite_sword", "diamond_sword", "iron_sword", "stone_sword", "wooden_sword",
                "netherite_axe", "diamond_axe", "iron_axe", "stone_axe", "wooden_axe")) {
            if (canonicalCount(weapon) <= 0) continue;
            ClientInventoryController.ClickResult result = inventory.selectServiceableMainHand(
                    "minecraft:" + weapon,
                    0,
                    inventoryTransactionOwner(actionLease, "hunt-equip"),
                    nowMillis);
            return switch (result) {
                case CLICKED, WAITING -> Outcome.running(
                        "equipping " + weapon + " before hunting", Double.NaN, 0, false);
                case ALREADY_DONE -> null;
                case ITEM_MISSING -> null;
                case SLOT_UNAVAILABLE, CURSOR_NOT_EMPTY -> Outcome.retry(
                        "could not equip " + weapon + " for hunting: " + result);
            };
        }
        return null;
    }

    private Outcome tickCraftLeaf(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            ActionLease actionLease,
            long nowMillis) throws IOException {
        // Reserve receiving room before recipe fill can move ingredients into the grid.
        // Output/cursor transactions already in flight retain their existing owner.
        if (inventory.craftingInputsEmpty() && !inventory.canAccept(minecraftItemId(frame.target())))
            return Outcome.capacity("making receiving room before crafting " + frame.target());
        boolean pinnedHomeTable = usesPinnedHomeStation(
                frame, WorkstationController.Kind.CRAFTING_TABLE);
        if (frame.parameters().getOrDefault("workstation", "").equals("crafting_table")
                && !pinnedHomeTable) {
            restoreDurableOwnedWorkstation(frame, WorkstationController.Kind.CRAFTING_TABLE);
        }
        if (!frame.parameters().containsKey("startingCount") && !pinnedHomeTable) {
            Outcome missing = ensureProductionPrerequisites(planId, frame, false, nowMillis);
            if (missing != null) return missing;
        }
        Baseline baseline = baseline(planId, frame, nowMillis);
        if (!baseline.ready()) return Outcome.continuePlan();
        int desired = integer(frame.parameters(), "totalOutput", Math.toIntExact(frame.count()));
        int gained = Math.max(0, frameObjectiveCount(frame, frame.target()) - baseline.count());
        if (gained >= desired) {
            if (frame.parameters().getOrDefault("workstation", "").equals("crafting_table")
                    && !pinnedHomeTable && !retainWorkstation(frame)) {
                Outcome reclaim = reclaimBeforeCompletion(
                        planId, frame, WorkstationController.Kind.CRAFTING_TABLE,
                        gained, lease, nowMillis);
                if (reclaim != null) return reclaim;
            }
            Outcome close = closeWorkstationScreenBeforeCompletion(
                    frame, lease, "crafting", gained, nowMillis);
            if (close != null) return close;
            completeLeaf(planId, frame, lease, "crafted " + desired + " " + frame.target(), nowMillis);
            return Outcome.continuePlan();
        }

        String station = frame.parameters().getOrDefault("workstation", "");
        if (station.equals("crafting_table")) {
            WorkstationController.Result opened = tickOpenPlanWorkstation(
                    frame, WorkstationController.Kind.CRAFTING_TABLE,
                    lease, nowMillis);
            if (checkpointOwnedWorkstation(
                    planId, frame, WorkstationController.Kind.CRAFTING_TABLE, nowMillis)) {
                return Outcome.continuePlan();
            }
            if (opened.state() == WorkstationController.State.ITEM_MISSING) {
                if (pinnedHomeTable) {
                    return Outcome.blocked(
                            "home_crafting_table_not_verified: " + opened.detail());
                }
                return pushMissingProductionResource(
                        planId, frame, "crafting_table", 1, opened.detail(), nowMillis,
                        planningInventoryViewForPlan(planId));
            }
            if (opened.state() == WorkstationController.State.AWAITING_DROP) {
                return tickReclaimedWorkstationDrop(
                        planId, frame, WorkstationController.Kind.CRAFTING_TABLE,
                        opened.position(), gained, lease, nowMillis);
            }
            if (opened.state() == WorkstationController.State.BLOCKED) {
                return Outcome.blocked(opened.detail(), opened.failureCause());
            }
            if (opened.state() == WorkstationController.State.RETRY) {
                return observeLeaf(frame, Outcome.retry(opened.detail()), lease, nowMillis, INVENTORY_ACTION_POLICY);
            }
            if (opened.state() != WorkstationController.State.OPEN) {
                return observeLeaf(frame,
                        Outcome.running(opened.detail(), Double.NaN, gained, false),
                        lease, nowMillis, INVENTORY_ACTION_POLICY);
            }
            if (pinnedHomeTable && !frame.parameters().containsKey("startingCount")) {
                Outcome missing = ensureProductionPrerequisites(
                        planId, frame, false, nowMillis);
                if (missing != null) return missing;
            }
        } else if (!inventory.isPlayerInventoryOpen()) {
            inventory.closeHandledScreen();
            return observeLeaf(frame,
                    Outcome.running("opening player crafting grid", Double.NaN, gained, false),
                    lease, nowMillis, INVENTORY_ACTION_POLICY);
        }

        if (isCommittedUniversalAction(frame)
                && "true".equals(frame.parameters().get("mixedIngredientGroups"))
                && inventory.craftingInputsEmpty()) {
            // Re-check after partial progress and on restart, but let an
            // already-filled grid or result cursor finish its owned operation.
            Outcome missing = ensureProductionPrerequisites(planId, frame, false, nowMillis);
            if (missing != null) return missing;
        }
        ClientInventoryController.CraftResult result = inventory.craftTick(
                minecraftItemId(frame.target()),
                integer(frame.parameters(), "outputPerOperation", 0),
                plannedRecipeMetadata(frame),
                parseCounts(frame.parameters().getOrDefault("ingredients", "")),
                inventoryTransactionOwner(actionLease, "craft"),
                nowMillis);
        Outcome outcome = switch (result) {
            case RECIPE_UNKNOWN -> Boolean.parseBoolean(
                    frame.parameters().getOrDefault("universalAcquisition", "false"))
                    ? Outcome.retry(
                    "waiting for Paper to synchronize the selected recipe for "
                            + frame.target())
                    : Outcome.blocked(
                    "Minecraft has not exposed a recipe for " + frame.target()
                            + "; acquire its ingredients once and retry");
            case CURSOR_NOT_EMPTY -> Outcome.retry("crafting cursor is occupied by another item");
            case INVENTORY_FULL -> Outcome.capacity("making receiving room for crafted " + frame.target());
            case SLOT_UNAVAILABLE -> Outcome.retry(
                    "crafting handler slot layout was unavailable; reopening the workstation");
            case INGREDIENTS_UNAVAILABLE -> {
                Outcome replenishment = ensureProductionPrerequisites(planId, frame, false, nowMillis);
                yield replenishment != null
                        ? replenishment
                        : Outcome.retry("waiting for a craftable " + frame.target() + " recipe variant");
            }
            case NEEDS_CRAFTING_TABLE -> Outcome.running(
                    "opening crafting table for " + frame.target(), Double.NaN, gained, false);
            case NEEDS_PLAYER_INVENTORY -> Outcome.running(
                    "returning to player crafting grid", Double.NaN, gained, false);
            case FILLED_RECIPE, TOOK_RESULT, STORED_RESULT, WAITING -> Outcome.running(
                    "crafting " + frame.target() + " (" + gained + "/" + desired + ")",
                        Double.NaN, gained, false);
        };
        return observeLeaf(frame, outcome, lease, nowMillis, INVENTORY_ACTION_POLICY);
    }

    private Outcome tickSmeltLeaf(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            ActionLease actionLease,
            long nowMillis) throws IOException {
        if (frame.parameters().containsKey("foodCookingSession")) {
            return tickFoodCookingSession(
                    planId, frame, lease, actionLease, nowMillis);
        }
        boolean pinnedHomeFurnace = usesPinnedHomeStation(
                frame, WorkstationController.Kind.FURNACE);
        if (!pinnedHomeFurnace) {
            restoreDurableOwnedWorkstation(frame, WorkstationController.Kind.FURNACE);
        }
        Baseline baseline = baseline(planId, frame, nowMillis);
        if (!baseline.ready()) return Outcome.continuePlan();
        int desired = integer(frame.parameters(), "totalOutput", Math.toIntExact(frame.count()));
        int gained = Math.max(0, frameObjectiveCount(frame, frame.target()) - baseline.count());
        if (gained >= desired) {
            if (!pinnedHomeFurnace && !retainWorkstation(frame)) {
                Outcome reclaim = reclaimBeforeCompletion(
                        planId, frame, WorkstationController.Kind.FURNACE,
                        gained, lease, nowMillis);
                if (reclaim != null) return reclaim;
            }
            Outcome close = closeWorkstationScreenBeforeCompletion(
                    frame, lease, "smelting", gained, nowMillis);
            if (close != null) return close;
            completeLeaf(planId, frame, lease, "smelted " + desired + " " + frame.target(), nowMillis);
            return Outcome.continuePlan();
        }

        WorkstationController.Result opened = tickOpenPlanWorkstation(
                frame, WorkstationController.Kind.FURNACE, lease, nowMillis);
        if (checkpointOwnedWorkstation(
                planId, frame, WorkstationController.Kind.FURNACE, nowMillis)) {
            return Outcome.continuePlan();
        }
        if (opened.state() == WorkstationController.State.ITEM_MISSING) {
            if (pinnedHomeFurnace) {
                return Outcome.blocked("home_furnace_not_verified: " + opened.detail());
            }
            return pushMissingProductionResource(
                    planId, frame, "furnace", 1, opened.detail(), nowMillis,
                    planningInventoryViewForPlan(planId));
        }
        if (opened.state() == WorkstationController.State.AWAITING_DROP) {
            return tickReclaimedWorkstationDrop(
                    planId, frame, WorkstationController.Kind.FURNACE,
                    opened.position(), gained, lease, nowMillis);
        }
        if (opened.state() == WorkstationController.State.BLOCKED) {
            return Outcome.blocked(opened.detail(), opened.failureCause());
        }
        if (opened.state() == WorkstationController.State.RETRY) {
            return observeLeaf(frame, Outcome.retry(opened.detail()), lease, nowMillis, FURNACE_ACTION_POLICY);
        }
        if (opened.state() != WorkstationController.State.OPEN) {
            return observeLeaf(frame,
                    Outcome.running(opened.detail(), Double.NaN, gained, false),
                    lease, nowMillis, FURNACE_ACTION_POLICY);
        }

        Outcome prepared = preparePinnedFurnaceRecipe(planId,frame,lease,actionLease,nowMillis);
        if(prepared!=null)return prepared;
        Outcome missing = ensureProductionPrerequisites(planId, frame, true, nowMillis);
        if (missing != null) return missing;

        Map<String, Integer> ingredients = parseCounts(frame.parameters().getOrDefault("ingredients", ""));
        String input = ingredients.keySet().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("smelt action has no input"));
        boolean committedUniversal = isCommittedUniversalAction(frame);
        String plannedFuelItem = frame.parameters().getOrDefault("fuelItem", "coal");
        int plannedFuelCount = integer(frame.parameters(), "fuelCount", 1);
        String transactionOwner = inventoryTransactionOwner(actionLease, "furnace");
        String actionToken = committedUniversal
                ? required(frame.parameters(), UNIVERSAL_PROGRAM_ACTION_TOKEN_KEY) : "";
        CommittedFurnaceFuelBudget committedBudget = null;
        ClientInventoryController.FurnaceFuelAuthorization fuelAuthorization =
                ClientInventoryController.FurnaceFuelAuthorization.legacy();
        if (committedUniversal) {
            try {
                committedBudget = committedFurnaceFuelBudget(frame, plannedFuelCount);
            } catch (IllegalArgumentException corrupt) {
                return invalidateUniversalProgramAndRebase(
                        planId, frame, DivergenceCause.INVENTORY_LOSS,
                        "durable committed furnace fuel budget is invalid: "
                                + corrupt.getMessage(),
                        nowMillis);
            }
            CommittedFurnaceFuelGrant liveGrant =
                    committedFurnaceFuelGrants.get(actionToken);
            if (committedBudget.hasPendingGrant()) {
                if (liveGrant == null
                        || liveGrant.ordinal() != committedBudget.authorizedCount()) {
                    return invalidateUniversalProgramAndRebase(
                            planId, frame, DivergenceCause.INVENTORY_LOSS,
                            "committed furnace fuel grant lost in-process custody; "
                                    + "reobserving inventory before any further insertion",
                            nowMillis);
                }
                if (liveGrant.serverCommitted()) {
                    return checkpointCommittedFurnaceFuelGrant(
                            planId, frame, actionToken, committedBudget,
                            "persisted the server-confirmed committed furnace fuel insertion",
                            nowMillis);
                }
                if (liveGrant.transferStarted()
                        && !inventory.hasExactFurnaceFuelTransfer(
                        transactionOwner, minecraftItemId(plannedFuelItem))) {
                    return invalidateUniversalProgramAndRebase(
                            planId, frame, DivergenceCause.INVENTORY_LOSS,
                            "committed furnace fuel transfer lost exact cursor custody; "
                                    + "reobserving before another insertion",
                            nowMillis);
                }
            } else {
                committedFurnaceFuelGrants.remove(actionToken);
            }
            fuelAuthorization = ClientInventoryController.FurnaceFuelAuthorization.committed(
                    committedBudget.hasPendingGrant());
        }
        List<String> fuelCandidates = committedUniversal
                ? ClientInventoryController.committedFurnaceFuelCandidates(
                        plannedFuelItem, plannedFuelCount)
                : furnaceFuelCandidates(planId, desired - gained);
        ClientInventoryController.FurnaceResult result = inventory.furnaceTick(
                "minecraft:" + input,
                "minecraft:" + frame.target(),
                fuelCandidates,
                desired - gained,
                transactionOwner,
                fuelAuthorization,
                nowMillis);
        if (committedUniversal
                && result == ClientInventoryController.FurnaceResult.FUEL_AUTHORIZATION_REQUIRED) {
            if (committedBudget.hasPendingGrant()) {
                return invalidateUniversalProgramAndRebase(
                        planId, frame, DivergenceCause.INVENTORY_LOSS,
                        "committed furnace rejected its live exact fuel grant",
                        nowMillis);
            }
            if (committedBudget.exhausted()) {
                return invalidateUniversalProgramAndRebase(
                        planId, frame, DivergenceCause.INVENTORY_LOSS,
                        "committed furnace fuel insertion budget exhausted while the furnace "
                                + "was cold; retained burn proof is stale",
                        nowMillis);
            }
            CommittedFurnaceFuelBudget authorized = committedBudget.authorizeNext();
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            checkpoint.put(COMMITTED_FURNACE_FUEL_AUTHORIZED_KEY,
                    Integer.toString(authorized.authorizedCount()));
            checkpoint.put(COMMITTED_FURNACE_FUEL_COMMITTED_KEY,
                    Integer.toString(authorized.committedCount()));
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "authorized committed furnace fuel insertion "
                            + authorized.authorizedCount() + "/" + authorized.plannedCount()
                            + " before its first click",
                    nowMillis);
            committedFurnaceFuelGrants.put(
                    actionToken,
                    new CommittedFurnaceFuelGrant(
                            authorized.authorizedCount(), false, false));
            return Outcome.continuePlan();
        }
        if (committedUniversal
                && result == ClientInventoryController.FurnaceResult.FUEL_INSERTION_COMMITTED) {
            CommittedFurnaceFuelGrant grant = committedFurnaceFuelGrants.get(actionToken);
            if (!committedBudget.hasPendingGrant()
                    || grant == null
                    || grant.ordinal() != committedBudget.authorizedCount()) {
                return invalidateUniversalProgramAndRebase(
                        planId, frame, DivergenceCause.INVENTORY_LOSS,
                        "server reported furnace fuel outside its exact durable grant",
                        nowMillis);
            }
            committedFurnaceFuelGrants.put(actionToken, grant.committed());
            return checkpointCommittedFurnaceFuelGrant(
                    planId, frame, actionToken, committedBudget,
                    "persisted the server-confirmed committed furnace fuel insertion",
                    nowMillis);
        }
        if (committedUniversal
                && result == ClientInventoryController.FurnaceResult.MOVED_FUEL
                && committedBudget.hasPendingGrant()) {
            int authorizedOrdinal = committedBudget.authorizedCount();
            committedFurnaceFuelGrants.computeIfPresent(
                    actionToken,
                    (ignored, grant) -> grant.ordinal() == authorizedOrdinal
                            ? grant.started() : grant);
        }
        Outcome outcome = switch (result) {
            case INPUT_MISSING -> pushMissingProductionResource(
                    planId, frame, input,
                    Math.max(1, desired - gained
                            - inventory.openFurnaceSlotCount(0, "minecraft:" + input)),
                    "smelting input was lost", nowMillis,
                    planningInventoryViewForPlan(planId));
            case FUEL_MISSING -> pushMissingSmeltingFuel(
                    planId, frame, desired - gained,
                    "smelting fuel was lost", nowMillis);
            case INPUT_OCCUPIED -> Outcome.retry(
                    "furnace input contains another item; selecting a clean furnace");
            case FUEL_OCCUPIED -> Outcome.retry(
                    "furnace fuel slot contains an unusable item; selecting a clean furnace");
            case OUTPUT_OCCUPIED -> Outcome.retry(
                    "furnace output belongs to another recipe; selecting a clean furnace");
            case INVENTORY_FULL -> Outcome.capacity("making receiving room for furnace output");
            case CURSOR_NOT_EMPTY -> Outcome.retry(
                    "furnace cursor is occupied; closing and reopening the transaction");
            case SLOT_UNAVAILABLE -> Outcome.retry(
                    "furnace handler slot mapping is unavailable; closing and reopening it");
            case NEEDS_FURNACE -> Outcome.running("opening furnace", Double.NaN, gained, false);
            case FUEL_AUTHORIZATION_REQUIRED, FUEL_INSERTION_COMMITTED -> Outcome.blocked(
                    "legacy furnace returned a committed-fuel protocol state");
            case MOVED_INPUT, MOVED_FUEL, COOKING, TOOK_OUTPUT, WAITING -> Outcome.running(
                    "smelting " + frame.target() + " (" + gained + "/" + desired + ")",
                        Double.NaN, gained, false);
        };
        return observeLeaf(frame, outcome, lease, nowMillis, FURNACE_ACTION_POLICY);
    }

    /** Retained Home cargo is a recipe handoff, not a reason to reopen the same dirty station. */
    private Outcome preparePinnedFurnaceRecipe(String planId,PlanFrame frame,ControlLease lease,
            ActionLease actionLease,long nowMillis) throws IOException {
        String input=parseCounts(frame.parameters().getOrDefault("ingredients","")).keySet().stream().findFirst().orElse("");
        List<String> fuels=isCommittedUniversalAction(frame)
                ?ClientInventoryController.committedFurnaceFuelCandidates(frame.parameters().getOrDefault("fuelItem","coal"),
                    integer(frame.parameters(),"fuelCount",1))
                :furnaceFuelCandidates(planId,Math.toIntExact(frame.count()));
        return preparePinnedFurnaceRecipe(planId,frame,lease,actionLease,nowMillis,input,frame.target(),fuels);
    }

    private Outcome preparePinnedFurnaceRecipe(String planId,PlanFrame frame,ControlLease lease,
            ActionLease actionLease,long nowMillis,String input,String output,List<String> fuels) throws IOException {
        if(!usesPinnedHomeStation(frame,WorkstationController.Kind.FURNACE))return null;
        HomeBinding binding=resolveHomeBinding(frame.parameters());
        String asset=binding.ledgerIds().get(HomeEconomySession.AssetRole.FURNACE);
        if(!binding.valid()||asset==null||!workstations.exactPinnedScreenOpen(WorkstationController.Kind.FURNACE,
                workstationOperationId(frame),asset,binding.home(),HOME_ASSET_RADIUS))
            return Outcome.blocked("Home furnace recipe handoff lost exact station/screen identity");
        int slot=inventory.unrelatedFurnaceSlot(input,output,fuels);
        boolean pending=Boolean.parseBoolean(frame.parameters().getOrDefault("homeFurnaceRecipeHandoff","false"));
        if(slot<0&&!pending)return null;
        if(!pending){
            Map<String,String> checkpoint=new LinkedHashMap<>(frame.parameters());checkpoint.put("homeFurnaceRecipeHandoff","true");
            plans.checkpointCurrent(planId,checkpoint,"preserving prior Home furnace contents before changing recipe",nowMillis);
            return Outcome.continuePlan();
        }
        inventory.reconcilePendingTransaction(nowMillis);
        if(!inventory.cursorEmpty()||!inventory.transactionDiagnostics().pendingClickOwner().isBlank())
            return Outcome.running("waiting for acknowledged Home furnace contents transfer",Double.NaN,0,false);
        if(slot>=0){
            var result=inventory.collectFurnaceResidue(slot,inventoryTransactionOwner(actionLease,"furnace-recipe-handoff"),nowMillis);
            if(result==ClientInventoryController.FurnaceResult.INVENTORY_FULL)
                return Outcome.capacity("making receiving room for retained Home furnace contents");
            return observeLeaf(frame,Outcome.running("collecting prior Home furnace contents before changing recipe",Double.NaN,0,false),
                    lease,nowMillis,FURNACE_ACTION_POLICY);
        }
        if(isCommittedUniversalAction(frame))return invalidateUniversalProgramAndRebase(planId,frame,DivergenceCause.EXTERNAL_WORLD_CHANGE,
                "retained Home furnace contents collected and acknowledged; replan from actual inventory",nowMillis);
        Map<String,String> checkpoint=new LinkedHashMap<>(frame.parameters());checkpoint.remove("homeFurnaceRecipeHandoff");
        plans.checkpointCurrent(planId,checkpoint,"Home furnace recipe handoff complete",nowMillis);
        return Outcome.continuePlan();
    }

    private static CommittedFurnaceFuelBudget committedFurnaceFuelBudget(
            PlanFrame frame,
            int plannedFuelCount) {
        return new CommittedFurnaceFuelBudget(
                plannedFuelCount,
                integer(frame.parameters(), COMMITTED_FURNACE_FUEL_AUTHORIZED_KEY, 0),
                integer(frame.parameters(), COMMITTED_FURNACE_FUEL_COMMITTED_KEY, 0));
    }

    private Outcome checkpointCommittedFurnaceFuelGrant(
            String planId,
            PlanFrame frame,
            String actionToken,
            CommittedFurnaceFuelBudget budget,
            String detail,
            long nowMillis) throws IOException {
        CommittedFurnaceFuelBudget committed = budget.commitPending();
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        checkpoint.put(COMMITTED_FURNACE_FUEL_AUTHORIZED_KEY,
                Integer.toString(committed.authorizedCount()));
        checkpoint.put(COMMITTED_FURNACE_FUEL_COMMITTED_KEY,
                Integer.toString(committed.committedCount()));
        plans.checkpointCurrent(planId, checkpoint, detail, nowMillis);
        committedFurnaceFuelGrants.remove(actionToken);
        return Outcome.continuePlan();
    }

    /**
     * Runs every raw-meat subtype as one physical furnace transaction. The
     * durable frame owns the workstation until the aggregate plan is finished,
     * so switching beef to pork never tears down the furnace or loses burn
     * progress.
     */
    private Outcome tickFoodCookingSession(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            ActionLease actionLease,
            long nowMillis) throws IOException {
        FoodCookingSessionPolicy.Plan cooking;
        try {
            cooking = FoodCookingSessionPolicy.decode(frame.parameters().get("foodCookingSession"));
        } catch (IllegalArgumentException error) {
            return Outcome.blocked("invalid durable food cooking session: " + error.getMessage());
        }
        boolean reconciled = "true".equals(frame.parameters().get("foodCookingReconciled"));
        if (cooking.totalOperations() > frame.count()
                || (!reconciled && (cooking.empty() || cooking.totalOperations() != frame.count()))) {
            return Outcome.blocked("food cooking session disagrees with its durable operation count");
        }

        inventory.reconcilePendingTransaction(nowMillis);
        if (!inventory.transactionDiagnostics().pendingClickOwner().isBlank()) {
            return Outcome.running("waiting for acknowledged mixed-food inventory truth", Double.NaN, 0, false);
        }

        boolean pinnedHomeFurnace = usesPinnedHomeStation(
                frame, WorkstationController.Kind.FURNACE);
        if (!pinnedHomeFurnace) {
            restoreDurableOwnedWorkstation(frame, WorkstationController.Kind.FURNACE);
        }
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        boolean checkpointChanged = false;
        for (FoodCookingSessionPolicy.Entry entry : cooking.entries()) {
            String baselineKey = foodCookingBaselineKey(entry.readyItem());
            String progressKey = foodCookingProgressKey(entry.readyItem());
            if (!checkpoint.containsKey(baselineKey)) {
                checkpoint.put(baselineKey, Integer.toString(canonicalCount(entry.readyItem())));
                checkpointChanged = true;
            }
            if (!checkpoint.containsKey(progressKey)) {
                checkpoint.put(progressKey, "0");
                checkpointChanged = true;
            }
        }
        if (checkpointChanged) {
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "recorded all mixed-food furnace baselines before inserting input",
                    nowMillis);
            return Outcome.continuePlan();
        }

        int completed = 0;
        checkpointChanged = false;
        for (FoodCookingSessionPolicy.Entry entry : cooking.entries()) {
            String baselineKey = foodCookingBaselineKey(entry.readyItem());
            String progressKey = foodCookingProgressKey(entry.readyItem());
            int durable = Math.min(
                    entry.operations(), integer(checkpoint, progressKey, 0));
            int nowObserved = canonicalCount(entry.readyItem());
            String observationKey = foodCookingObservationKey(entry.readyItem());
            int previousObserved = integer(checkpoint, observationKey,
                    integer(checkpoint, baselineKey, nowObserved) + durable);
            int next = FoodCookingSessionPolicy.observedCompleted(
                    entry.operations(), durable, previousObserved, nowObserved);
            if (next != durable) {
                checkpoint.put(progressKey, Integer.toString(next));
                checkpointChanged = true;
            }
            if (!checkpoint.containsKey(observationKey) || previousObserved != nowObserved) {
                checkpoint.put(observationKey, Integer.toString(nowObserved));
                checkpointChanged = true;
            }
            completed = Math.addExact(completed, next);
        }
        if (checkpointChanged) {
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "committed mixed-food furnace output before selecting the next subtype",
                    nowMillis);
            return Outcome.continuePlan();
        }

        Outcome rationHandoff = yieldFoodCookingForRation(planId, frame, completed, nowMillis);
        if (rationHandoff != null) return rationHandoff;

        if (completed >= cooking.totalOperations()) {
            if (!pinnedHomeFurnace) {
                Outcome reclaim = reclaimBeforeCompletion(
                        planId, frame, WorkstationController.Kind.FURNACE,
                        completed, lease, nowMillis);
                if (reclaim != null) return reclaim;
            }
            Outcome close = closeWorkstationScreenBeforeCompletion(
                    frame, lease, "food cooking", completed, nowMillis);
            if (close != null) return close;
            var foodRoot = plans.restore(planId).flatMap(AutonomyExecutor::foodObjectiveRoot);
            if (foodRoot.isPresent()
                    && "true".equals(foodRoot.orElseThrow().parameters().get("personalMaintenance"))) {
                var root = foodRoot.orElseThrow();
                var parentCheckpoint = new LinkedHashMap<>(root.parameters());
                parentCheckpoint.put("personalFoodCookingSettled", "true");
                plans.checkpointFrames(planId, Map.of(root.id(), parentCheckpoint),
                        "settled personal cooking custody before reconsidering the productive-work ration", nowMillis);
            }
            completeLeaf(
                    planId, frame, lease,
                    "cooked " + completed + " mixed food in one furnace session",
                    nowMillis);
            return Outcome.continuePlan();
        }

        WorkstationController.Result opened = tickOpenPlanWorkstation(
                frame, WorkstationController.Kind.FURNACE, lease, nowMillis);
        if (checkpointOwnedWorkstation(
                planId, frame, WorkstationController.Kind.FURNACE, nowMillis)) {
            return Outcome.continuePlan();
        }
        if (opened.state() == WorkstationController.State.ITEM_MISSING) {
            if (pinnedHomeFurnace) {
                return Outcome.blocked("home_furnace_not_verified: " + opened.detail());
            }
            // A foodCookingSession is a production frame too.  Keep its missing
            // workstation on the authoritative multi-variant compiler; the
            // scalar compatibility recipe otherwise projects Paper's
            // #minecraft:stone_crafting_materials tag to its first wire item
            // (blackstone), even in a natural Overworld with carried cobble.
            return pushMissingProductionResource(
                    planId, frame, "furnace", 1, opened.detail(), nowMillis,
                    planningInventoryViewForPlan(planId));
        }
        if (opened.state() == WorkstationController.State.AWAITING_DROP) {
            return tickReclaimedWorkstationDrop(
                    planId, frame, WorkstationController.Kind.FURNACE,
                    opened.position(), completed, lease, nowMillis);
        }
        if (opened.state() == WorkstationController.State.BLOCKED) {
            return Outcome.blocked(opened.detail(), opened.failureCause());
        }
        if (opened.state() == WorkstationController.State.RETRY) {
            return observeLeaf(
                    frame, Outcome.retry(opened.detail()), lease,
                    nowMillis, FURNACE_ACTION_POLICY);
        }
        if (opened.state() != WorkstationController.State.OPEN) {
            return observeLeaf(
                    frame,
                    Outcome.running(opened.detail(), Double.NaN, completed, false),
                    lease, nowMillis, FURNACE_ACTION_POLICY);
        }

        if (client.player == null || !(client.player.currentScreenHandler
                instanceof net.minecraft.screen.AbstractFurnaceScreenHandler handler)) {
            return Outcome.running("waiting for the mixed-food furnace screen", Double.NaN, completed, false);
        }
        FoodCookingSessionPolicy.Entry residentRecipe = activeFoodCookingEntry(cooking, checkpoint);
        if (residentRecipe != null) {
            // A shared Home furnace can retain a previous job's meat/ore/output.
            // Preserve it through the same acknowledged recipe handoff used by
            // ordinary smelting, then reconcile family inputs from real inventory.
            Outcome handoff = preparePinnedFurnaceRecipe(planId, frame, lease, actionLease, nowMillis,
                    residentRecipe.rawItem(), residentRecipe.readyItem(),
                    furnaceFuelCandidates(planId, Math.max(1, cooking.totalOperations() - completed)));
            if (handoff != null) return handoff;
        }
        boolean batchSettled = handler.getSlot(0).getStack().isEmpty()
                && handler.getSlot(2).getStack().isEmpty() && handler.getCursorStack().isEmpty()
                && inventory.transactionDiagnostics().cursorOwner().isBlank()
                && !foodConsumptionPending.getAsBoolean();
        Map<String, Integer> completedByItem = new LinkedHashMap<>();
        for (var entry : cooking.entries()) completedByItem.put(entry.readyItem(),
                integer(checkpoint, foodCookingProgressKey(entry.readyItem()), 0));
        Map<String, Integer> available = foodPlanningInventoryView(planId, false).itemCounts();
        var currentCooking = FoodCookingSessionPolicy.reconcileAvailable(Math.toIntExact(frame.count()),
                cooking, completedByItem, available, batchSettled);
        if (!currentCooking.equals(cooking)) {
            checkpoint.put("foodCookingSession", FoodCookingSessionPolicy.encode(currentCooking));
            checkpoint.put("foodCookingReconciled", "true");
            checkpoint.put("ingredients", currentCooking.rawInputs().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",")));
            for (var entry : currentCooking.entries()) {
                checkpoint.putIfAbsent(foodCookingBaselineKey(entry.readyItem()),
                        Integer.toString(canonicalCount(entry.readyItem())));
                checkpoint.putIfAbsent(foodCookingProgressKey(entry.readyItem()), "0");
                checkpoint.putIfAbsent(foodCookingObservationKey(entry.readyItem()),
                        Integer.toString(canonicalCount(entry.readyItem())));
            }
            plans.checkpointCurrent(planId, checkpoint,
                    "reconciled mixed-food portions with actual carried inputs; retained furnace and fuel custody", nowMillis);
            logger.info("Mixed-food session reconciled plan={} frame={} oldInputs={} newInputs={} completed={} budget={} fuel={}",
                    planId, frame.id(), cooking.rawInputs(), currentCooking.rawInputs(), completedByItem,
                    frame.count(), handler.getSlot(1).getStack());
            return Outcome.continuePlan();
        }
        FoodCookingSessionPolicy.Entry active = activeFoodCookingEntry(cooking, checkpoint);
        if (active == null) {
            return Outcome.retry("mixed-food progress changed while selecting furnace input");
        }
        int activeDone = Math.min(
                active.operations(),
                integer(checkpoint, foodCookingProgressKey(active.readyItem()), 0));
        int cursorRaw = !handler.getCursorStack().isEmpty()
                && stackItem(handler.getCursorStack()).equals(active.rawItem())
                ? handler.getCursorStack().getCount() : 0;
        int activeRemaining = FoodCookingSessionPolicy.availableBatchOperations(active.operations() - activeDone,
                normalizedInventoryCount(available, active.rawItem()),
                inventory.openFurnaceSlotCount(0, "minecraft:" + active.rawItem()), cursorRaw);
        int totalRemaining = Math.max(1, cooking.totalOperations() - completed);
        if (inventory.openFurnaceSlotCount(
                        2, "minecraft:" + active.readyItem()) > 0
                && !inventory.canAccept("minecraft:" + active.readyItem())) {
            return observeLeaf(
                    frame,
                    Outcome.capacity("making receiving room for cooked food"),
                    lease,
                    nowMillis,
                    FURNACE_ACTION_POLICY);
        }
        ClientInventoryController.FurnaceResult result = inventory.furnaceTick(
                "minecraft:" + active.rawItem(),
                "minecraft:" + active.readyItem(),
                furnaceFuelCandidates(planId, totalRemaining),
                activeRemaining,
                inventoryTransactionOwner(actionLease, "food-furnace"),
                nowMillis);
        Outcome outcome = switch (result) {
            case INPUT_MISSING -> Outcome.running(
                    "mixed-food input changed; settling the furnace before family reconciliation",
                    Double.NaN, completed, false);
            case FUEL_MISSING -> pushMissingSmeltingFuel(
                    planId, frame,
                    remainingFoodCookingBatches(cooking, checkpoint),
                    "mixed-food furnace fuel was lost", nowMillis);
            case INPUT_OCCUPIED -> Outcome.retry(
                    "mixed-food furnace still contains an unexpected input");
            case FUEL_OCCUPIED -> Outcome.retry(
                    "mixed-food furnace fuel slot contains an unusable item");
            case OUTPUT_OCCUPIED -> Outcome.retry(
                    "mixed-food furnace output belongs to an unexpected recipe");
            case INVENTORY_FULL -> Outcome.capacity("making receiving room for cooked food");
            case CURSOR_NOT_EMPTY -> Outcome.retry(
                    "mixed-food furnace cursor is occupied by another item");
            case SLOT_UNAVAILABLE -> Outcome.retry(
                    "mixed-food furnace handler slot mapping is unavailable");
            case NEEDS_FURNACE -> Outcome.running(
                    "opening the persistent mixed-food furnace", Double.NaN, completed, false);
            case FUEL_AUTHORIZATION_REQUIRED, FUEL_INSERTION_COMMITTED -> Outcome.blocked(
                    "mixed-food furnace entered a committed-fuel-only protocol state");
            case MOVED_INPUT, MOVED_FUEL, COOKING, TOOK_OUTPUT, WAITING -> Outcome.running(
                    "cooking " + active.readyItem() + " inside one mixed-food session ("
                            + completed + "/" + cooking.totalOperations() + ")",
                    Double.NaN, completed, false);
        };
        return observeLeaf(frame, outcome, lease, nowMillis, FURNACE_ACTION_POLICY);
    }

    /** Pause the SAME cooking frame, not a recursive food-acquisition root. */
    private Outcome yieldFoodCookingForRation(String planId, PlanFrame frame, int completed,
            long nowMillis) {
        if (client.player == null || !plans.restore(planId).map(AutonomyExecutor::foodAcquisitionOwnsRation).orElse(false))
            return null;
        int hunger = client.player.getHungerManager().getFoodLevel();
        boolean recovery = hunger <= 14 || client.player.getHealth() <= client.player.getMaxHealth()
                * dev.entity.core.survival.SurvivalSupervisor.HEALTH_RECOVERY_ENTER_FRACTION;
        if (!recovery || hunger >= 19) return null;
        var carried = canonicalInventorySnapshot();
        if (foodAcquisitionRation(planId, "active-mission", hunger, carried, List.of()).stream()
                .noneMatch(claim -> claim.purpose() == Purpose.PERSONAL_RATION)) return null;
        var custody = inventory.transactionDiagnostics();
        // A partially inserted input must keep advancing its existing cursor
        // owner. Acknowledged resident input/fuel/output remain in this exact
        // furnace while the body eats; none are evacuated or certified done.
        if (!foodRationCustodySettled(true, inventory.cursorEmpty(),
                custody.pendingClickOwner(), custody.cursorOwner())) return null;
        boolean closingFurnace = inventory.isFurnaceOpen();
        if (inventory.closeHandledScreenIfCursorEmpty(nowMillis) != ClientInventoryController.SafeCloseResult.CLOSED)
            return Outcome.running("settling mixed-food inventory custody before personal eating", Double.NaN, completed, false);
        actionSupervisor.suspend(frame.id(), nowMillis);
        if (closingFurnace) logger.info("Personal food ration handoff plan={} frame={} hunger={} cooked={} station={}; same quota retained",
                planId, frame.id(), hunger, completed, workstationOperationId(frame));
        return Outcome.running("same food session paused for its personal ration; requested quota remains due",
                Double.NaN, completed, false);
    }

    private static List<Integer> remainingFoodCookingBatches(
            FoodCookingSessionPolicy.Plan cooking,
            Map<String, String> checkpoint) {
        java.util.ArrayList<Integer> remaining = new java.util.ArrayList<>();
        for (FoodCookingSessionPolicy.Entry entry : cooking.entries()) {
            int completed = Math.min(
                    entry.operations(),
                    integer(checkpoint, foodCookingProgressKey(entry.readyItem()), 0));
            int outstanding = entry.operations() - completed;
            if (outstanding > 0) remaining.add(outstanding);
        }
        return List.copyOf(remaining);
    }

    private FoodCookingSessionPolicy.Entry activeFoodCookingEntry(
            FoodCookingSessionPolicy.Plan cooking,
            Map<String, String> checkpoint) {
        if (client.player != null
                && client.player.currentScreenHandler
                instanceof net.minecraft.screen.AbstractFurnaceScreenHandler handler) {
            ItemStack cursor = handler.getCursorStack();
            if (!cursor.isEmpty()) {
                String cursorItem = stackItem(cursor);
                for (FoodCookingSessionPolicy.Entry entry : cooking.entries()) {
                    if (entry.rawItem().equals(cursorItem)) return entry;
                }
            }
            ItemStack output = handler.getSlot(2).getStack();
            if (!output.isEmpty()) {
                String outputItem = stackItem(output);
                for (FoodCookingSessionPolicy.Entry entry : cooking.entries()) {
                    if (entry.readyItem().equals(outputItem)) return entry;
                }
            }
            ItemStack input = handler.getSlot(0).getStack();
            if (!input.isEmpty()) {
                String inputItem = stackItem(input);
                for (FoodCookingSessionPolicy.Entry entry : cooking.entries()) {
                    if (entry.rawItem().equals(inputItem)) return entry;
                }
            }
        }
        for (FoodCookingSessionPolicy.Entry entry : cooking.entries()) {
            int completed = integer(
                    checkpoint, foodCookingProgressKey(entry.readyItem()), 0);
            if (completed < entry.operations()) return entry;
        }
        return null;
    }

    private static String foodCookingBaselineKey(String readyItem) {
        return "foodCookStart." + simple(readyItem);
    }

    private static String foodCookingProgressKey(String readyItem) {
        return "foodCookDone." + simple(readyItem);
    }

    private static String foodCookingObservationKey(String readyItem) {
        return "foodCookObserved." + simple(readyItem);
    }

    private List<String> furnaceFuelCandidates(String planId, int remainingOutput) {
        Optional<TaskPlan> plan = plans.restore(planId)
                .filter(candidate -> candidate.state() == TaskPlanState.OPEN);
        if (plan.flatMap(AutonomyExecutor::foodObjectiveRoot).isEmpty()) return FUEL_ITEMS;

        Map<String, Integer> spendable = foodPlanningInventoryView(planId, false).itemCounts();
        List<String> preferred = remainingOutput > COAL_FUEL_OPERATIONS
                ? List.of("minecraft:coal_block", "minecraft:coal", "minecraft:charcoal")
                : FUEL_ITEMS;
        return preferred.stream()
                .filter(item -> normalizedInventoryCount(spendable, item) > 0)
                .toList();
    }

    private Outcome pushMissingSmeltingFuel(
            String planId,
            PlanFrame frame,
            int remainingOperations,
            String reason,
            long nowMillis) throws IOException {
        return pushMissingSmeltingFuel(
                planId, frame, List.of(Math.max(1, remainingOperations)), reason, nowMillis);
    }

    private Outcome pushMissingSmeltingFuel(
            String planId,
            PlanFrame frame,
            List<Integer> remainingBatches,
            String reason,
            long nowMillis) throws IOException {
        Optional<TaskPlan> plan = plans.restore(planId)
                .filter(candidate -> candidate.state() == TaskPlanState.OPEN);
        if (isCommittedUniversalAction(frame)) {
            return invalidateUniversalProgramAndRebase(
                    planId, frame, DivergenceCause.INVENTORY_LOSS, reason, nowMillis);
        }
        if (plan.flatMap(AutonomyExecutor::foodObjectiveRoot).isEmpty()) {
            return pushMissingResource(
                    planId,
                    frame.parameters().getOrDefault("fuelItem", "coal"),
                    1, reason, nowMillis);
        }

        ResourcePlanner.InventoryView spendable = foodPlanningInventoryView(planId, false);
        FoodFuelBudget budget = foodFuelBudget(
                remainingBatches.isEmpty() ? List.of(1) : remainingBatches,
                spendable.itemCounts());
        if (budget.satisfied()) {
            return Outcome.retry(
                    "furnace reported missing fuel while scoped coal/charcoal/block capacity remains; "
                            + "reopening the furnace transaction");
        }
        return pushMissingResource(
                planId, "coal", budget.requiredRegularFuel(), reason,
                nowMillis, spendable);
    }

    private Outcome reclaimBeforeCompletion(
            String planId,
            PlanFrame frame,
            WorkstationController.Kind kind,
            int completedWork,
            ControlLease lease,
            long nowMillis) throws IOException {
        WorkstationController.Result reclaim = workstations.tickReclaim(
                kind, workstationOperationId(frame), lease, baritone, nowMillis);
        if (reclaim.state() == WorkstationController.State.RECLAIMED) return null;
        if (reclaim.state() == WorkstationController.State.AWAITING_DROP) {
            return tickReclaimedWorkstationDrop(
                    planId, frame, kind, reclaim.position(), completedWork, lease, nowMillis);
        }
        if (reclaim.state() == WorkstationController.State.DROP_MISSING) {
            return replaceLostWorkstation(
                    planId, frame, kind, lease,
                    reclaim.detail() + "; owned workstation drop was already absent",
                    nowMillis);
        }
        if (reclaim.state() == WorkstationController.State.ITEM_MISSING) {
            if (isCommittedUniversalAction(frame)) {
                return invalidateUniversalProgramAndRebase(
                        planId, frame, DivergenceCause.TOOL_LOSS,
                        reclaim.detail() + "; tool required to reclaim "
                                + kind.displayName() + " is missing",
                        nowMillis);
            }
            if (kind == WorkstationController.Kind.FURNACE) {
                return pushMissingResource(planId, "stone_pickaxe", 1, reclaim.detail(), nowMillis);
            }
            return Outcome.retry(reclaim.detail());
        }
        if (reclaim.state() == WorkstationController.State.BLOCKED) {
            return Outcome.blocked(reclaim.detail());
        }
        Outcome outcome = reclaim.state() == WorkstationController.State.RETRY
                ? Outcome.retry(reclaim.detail())
                : Outcome.running(reclaim.detail(), Double.NaN, completedWork, false);
        return observeLeaf(frame, outcome, lease, nowMillis, INVENTORY_ACTION_POLICY);
    }

    /**
     * A produced item is not a complete actuator result while its handled GUI
     * still owns movement input or an unacknowledged cursor transaction. This
     * applies equally to pinned Home stations, retained field stations, and the
     * local crafting grid. Reclamation already closes its screen, but this
     * idempotent boundary also covers every station that deliberately remains
     * placed.
     */
    private Outcome closeWorkstationScreenBeforeCompletion(
            PlanFrame frame,
            ControlLease lease,
            String operation,
            int completedWork,
            long nowMillis) {
        if (inventory.closeHandledScreen(nowMillis)) return null;
        return observeLeaf(
                frame,
                Outcome.running(
                        "settling the final " + operation
                                + " inventory acknowledgement before closing its screen",
                        Double.NaN,
                        completedWork,
                        false),
                lease,
                nowMillis,
                INVENTORY_ACTION_POLICY);
    }

    private void restoreDurableOwnedWorkstation(
            PlanFrame frame,
            WorkstationController.Kind kind) {
        if (usesPinnedHomeStation(frame, kind)) return;
        String encoded = frame.parameters().getOrDefault("ownedWorkstationPosition", "");
        if (encoded.isBlank()) return;
        String recordedDimension = frame.parameters().getOrDefault("ownedWorkstationDimension", "");
        try {
            workstations.restoreOwnedPlacement(
                    kind,
                    workstationOperationId(frame),
                    BlockPos.fromLong(Long.parseLong(encoded)),
                    recordedDimension);
        } catch (NumberFormatException ignored) {
        }
    }

    private boolean checkpointOwnedWorkstation(
            String planId,
            PlanFrame frame,
            WorkstationController.Kind kind,
            long nowMillis) throws IOException {
        if (usesPinnedHomeStation(frame, kind)) return false;
        String operationId = workstationOperationId(frame);
        Optional<BlockPos> owned = workstations.ownedPlacement(operationId);
        if (owned.isEmpty()) return false;
        String encoded = Long.toString(owned.orElseThrow().asLong());
        String dimension = workstations.ownedPlacementDimension(operationId)
                .orElseGet(this::currentDimension);
        if (dimension.isBlank()) return false;
        if (encoded.equals(frame.parameters().get("ownedWorkstationPosition"))
                && dimension.equals(frame.parameters().get("ownedWorkstationDimension"))) return false;
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        checkpoint.put("ownedWorkstationPosition", encoded);
        checkpoint.put("ownedWorkstationDimension", dimension);
        plans.checkpointCurrent(planId, checkpoint,
                "persisted ownership of Entity-placed " + kind.displayName(), nowMillis);
        return true;
    }

    private Outcome tickReclaimedWorkstationDrop(
            String planId,
            PlanFrame frame,
            WorkstationController.Kind kind,
            BlockPos origin,
            int completedWork,
            ControlLease lease,
            long nowMillis) throws IOException {
        String operationId = workstationOperationId(frame);
        long brokenAt = workstations.reclaimBrokenAt(operationId);
        GroundItemCollectionPolicy.Request request = new GroundItemCollectionPolicy.Request(
                new GroundItemCollectionPolicy.Scope(planId, frame.id() + ":reclaim-drop"),
                Set.of(resourcePlanner.normalizeItem(kind.itemId())),
                new GroundItemCollectionPolicy.Point(
                        origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5),
                10.0,
                5 * 60_000L,
                1,
                4_000L,
                3);
        ClientGroundItemCollectionController.Result result =
                groundItems.tick(request, lease, nowMillis);
        long elapsed = brokenAt <= 0L ? 0L : Math.max(0L, nowMillis - brokenAt);
        return switch (result.state()) {
            case COLLECTED -> {
                if (!workstations.confirmReclaimed(operationId, nowMillis)) {
                    String failure = workstations.persistenceFailure(operationId);
                    yield Outcome.blocked(failure.isBlank()
                            ? "could not commit reclaimed " + kind.displayName() + " ownership"
                            : failure);
                }
                yield Outcome.running(
                        "verified reclaimed " + kind.displayName() + " inventory increase",
                        0.0, completedWork, false);
            }
            case INVENTORY_FULL -> insertCapacityRecoveryForSlots(planId, frame, nowMillis, 1);
            case WAITING_FOR_WORLD -> Outcome.running(result.detail(), Double.NaN, completedWork, false);
            case SEARCHING -> {
                if (elapsed < DROP_COLLECTION_TIMEOUT_MILLIS) {
                    yield Outcome.running(
                            elapsed < DROP_SPAWN_GRACE_MILLIS
                                    ? "waiting for reclaimed " + kind.displayName() + " item to spawn"
                                    : "searching the reclaim location for " + kind.displayName(),
                            Double.NaN, completedWork, false);
                }
                yield replaceLostWorkstation(
                        planId, frame, kind, lease,
                        "no matching workstation item existed after the bounded collection window",
                        nowMillis);
            }
            case LOST -> elapsed < DROP_COLLECTION_TIMEOUT_MILLIS
                    ? Outcome.running(result.detail(), Double.NaN, completedWork, false)
                    : replaceLostWorkstation(planId, frame, kind, lease, result.detail(), nowMillis);
            case BLOCKED -> replaceLostWorkstation(
                    planId, frame, kind, lease,
                    "workstation drop was not safely reachable: " + result.detail(), nowMillis);
            case APPROACHING, WAITING_FOR_PICKUP, PROGRESS, RECOVERING -> Outcome.running(
                    result.detail(), result.distanceRemaining(), completedWork, result.progressExpected());
        };
    }

    private Outcome replaceLostWorkstation(
            String planId,
            PlanFrame frame,
            WorkstationController.Kind kind,
            ControlLease lease,
            String reason,
            long nowMillis) throws IOException {
        groundItems.clear(lease.epoch(), reason);
        workstations.forgetLostOwnedPlacement(
                workstationOperationId(frame), reason, nowMillis);
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        checkpoint.remove("ownedWorkstationPosition");
        checkpoint.remove("ownedWorkstationDimension");
        plans.checkpointCurrent(
                planId,
                checkpoint,
                "forgot durable ownership of a lost field-kit " + kind.displayName(),
                nowMillis);
        if (isCommittedUniversalAction(frame)) {
            return invalidateUniversalProgramAndRebase(
                    planId, frame, DivergenceCause.WORKSTATION_LOSS,
                    reason + "; lost field-kit " + kind.displayName(), nowMillis);
        }
        return pushMissingResource(
                planId,
                simple(kind.itemId()),
                1,
                reason + "; replacing the lost field-kit " + kind.displayName(),
                nowMillis);
    }

    private Outcome tickEquipLeaf(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            ActionLease actionLease,
            long nowMillis) throws IOException {
        if (Boolean.parseBoolean(
                frame.parameters().getOrDefault("farmSeedBinding", "false"))) {
            int hotbarSlot = integer(
                    frame.parameters(), "farmSeedHotbarSlot", -1);
            if (hotbarSlot < 0
                    || hotbarSlot >= net.minecraft.entity.player.PlayerInventory.getHotbarSize()) {
                return Outcome.blocked("invalid managed-farm replant hotbar binding");
            }
            baritone.cancel(lease.epoch(), "binding exact managed-farm replant item");
            ClientInventoryController.ClickResult result = inventory.moveToHotbar(
                    minecraftItemId(frame.target()),
                    hotbarSlot,
                    inventoryTransactionOwner(actionLease, "farm-replant"),
                    nowMillis);
            if (result == ClientInventoryController.ClickResult.ALREADY_DONE) {
                completeLeaf(
                        planId, frame, lease,
                        "verified exact " + frame.target()
                                + " in managed-farm hotbar custody",
                        nowMillis);
                return Outcome.continuePlan();
            }
            if (result == ClientInventoryController.ClickResult.ITEM_MISSING) {
                // No crop was touched. Pop this binding so the durable farm root can
                // reobserve inventory and leave this crop family standing.
                completeLeaf(
                        planId, frame, lease,
                        "replant item changed before binding; no farm mutation issued",
                        nowMillis);
                return Outcome.continuePlan();
            }
            Outcome binding = switch (result) {
                case CLICKED, WAITING -> Outcome.running(
                        "binding exact " + frame.target() + " for replanting",
                        Double.NaN, 0, false);
                case SLOT_UNAVAILABLE, CURSOR_NOT_EMPTY -> Outcome.retry(
                        "waiting for a safe managed-farm hotbar binding: " + result);
                case ALREADY_DONE, ITEM_MISSING -> throw new IllegalStateException(
                        "managed-farm binding terminal result escaped dispatch");
            };
            return observeLeaf(
                    frame, binding, lease, nowMillis, INVENTORY_ACTION_POLICY);
        }
        int minimumToolDurability = integer(
                frame.parameters(), "minimumToolDurability", 0);
        boolean harvestToolBinding = Boolean.parseBoolean(
                frame.parameters().getOrDefault("harvestToolBinding", "false"));
        if (minimumToolDurability == 0
                && equippedItems().contains(resourcePlanner.normalizeItem(frame.target()))) {
            completeLeaf(planId, frame, lease, "equipped " + frame.target(), nowMillis);
            return Outcome.continuePlan();
        }
        baritone.cancel(lease.epoch(), "equipping prerequisite");
        if (harvestToolBinding) {
            int harvestHotbar = integer(frame.parameters(), "harvestToolHotbarSlot", -1);
            if (!HarvestToolAccessPolicy.isStableToolSlot(harvestHotbar)) {
                return Outcome.blocked("invalid native harvest tool hotbar binding");
            }
            // Keep the existing acknowledgement-fenced SWAP out of Baritone's pickaxe/throwaway
            // slots. Passive route tools remain disabled until this EQUIP leaf completes.
            inventory.selectHotbarSlotNow(harvestHotbar);
        }
        // The controller owns a staged PICKUP -> equipment-slot -> source-slot
        // transaction. After its first acknowledged click the item is on the
        // cursor, so player-inventory counts are legitimately zero. Always let
        // the matching controller session resume before interpreting absence;
        // ITEM_MISSING remains the authoritative no-session loss result.
        ClientInventoryController.ClickResult result = minimumToolDurability > 0
                ? inventory.selectServiceableMainHand(
                        "minecraft:" + frame.target(),
                        minimumToolDurability,
                        inventoryTransactionOwner(actionLease, "equip-mainhand"),
                        nowMillis)
                : inventory.equipServiceable(
                        "minecraft:" + frame.target(), 0,
                        inventoryTransactionOwner(actionLease, "equip"), nowMillis);
        if (result == ClientInventoryController.ClickResult.ALREADY_DONE) {
            // For an exact mining-segment binding this is the acknowledged postcondition:
            // the selected main-hand stack now has the persisted minimum durability. A
            // committed universal EQUIP uses the same proof before advancing its cursor.
            completeLeaf(
                    planId, frame, lease,
                    "equipped exact serviceable " + frame.target(), nowMillis);
            return Outcome.continuePlan();
        }
        if (harvestToolBinding && result == ClientInventoryController.ClickResult.ITEM_MISSING) {
            // This is opportunistic access to a carried tool, not authority to acquire an axe.
            completeLeaf(planId, frame, lease, "carried harvest tool changed; reobserve before native work", nowMillis);
            return Outcome.continuePlan();
        }
        Outcome outcome = switch (result) {
            case ITEM_MISSING -> isCommittedUniversalAction(frame)
                    ? invalidateUniversalProgramAndRebase(
                    planId, frame, DivergenceCause.TOOL_LOSS,
                    "equipment item disappeared", nowMillis)
                    : pushMissingResource(planId, frame.target(), 1,
                    "equipment item disappeared", nowMillis);
            case SLOT_UNAVAILABLE, CURSOR_NOT_EMPTY -> Outcome.retry("could not equip " + frame.target() + ": " + result);
            case CLICKED, WAITING -> Outcome.running(
                    "equipping " + frame.target(), Double.NaN, 0, false);
            case ALREADY_DONE -> throw new IllegalStateException(
                    "acknowledged equipment was not completed before result dispatch");
        };
        return observeLeaf(frame, outcome, lease, nowMillis, INVENTORY_ACTION_POLICY);
    }

    /**
     * Migrates a pre-exact delivery leaf before Paper binds a new transaction.
     * Planner aliases remain on the durable root; a leaf always names one real
     * carried Minecraft item key and one bounded concrete chunk.
     */
    private Outcome concretizeLegacyDeliveryLeaf(
            String planId,
            PlanFrame frame,
            long nowMillis) throws IOException {
        String nonce = frame.parameters().getOrDefault("handoffNonce", "");
        if (!nonce.isBlank()) {
            int exact = inventory.count(frame.target());
            boolean transactionAlreadyAdvanced = integer(
                    frame.parameters(), "handoffConfirmedCount", 0) > 0
                    || Boolean.parseBoolean(frame.parameters().getOrDefault(
                    "handoffAwaitingInventorySync", "false"));
            if (exact > 0 || transactionAlreadyAdvanced) {
                Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
                checkpoint.put("concreteDeliveryItem", "true");
                checkpoint.putIfAbsent("deliveryObjectiveItem", frame.target());
                plans.checkpointCurrent(
                        planId, checkpoint,
                        "accepted an existing exact Paper handoff leaf",
                        nowMillis);
                return Outcome.continuePlan();
            }
            long preparedAt = longInteger(
                    frame.parameters(), "dropPreparedAt", nowMillis);
            if (nowMillis - preparedAt < 12_000L) {
                return Outcome.running(
                        "waiting for the existing Paper handoff proof before alias migration",
                        Double.NaN, integer(frame.parameters(), "delivered", 0), false);
            }
            TaskPlan plan = plans.restore(planId).orElseThrow();
            PlanFrame root = plan.frames().getFirst();
            Map<String, String> rootCheckpoint = new LinkedHashMap<>(root.parameters());
            clearDeathHandoffCheckpoint(rootCheckpoint);
            retireDeliveryTransaction(planId, nonce);
            plans.rebaseToRoot(
                    planId, rootCheckpoint,
                    "retired an unprovable legacy alias handoff and will bind an exact carried item",
                    nowMillis);
            return Outcome.continuePlan();
        }

        Optional<ClientInventoryController.ConcreteItem> concrete =
                permittedConcreteDelivery(planId, frame.target());
        if (concrete.isEmpty()) return null;
        ClientInventoryController.ConcreteItem selected = concrete.orElseThrow();
        int delivered = integer(frame.parameters(), "delivered", 0);
        int remaining = Math.max(0, Math.toIntExact(frame.count()) - delivered);
        int concreteChunk = Math.min(remaining, selected.count());
        if (concreteChunk <= 0) return null;
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>(frame.parameters());
        parameters.put("concreteDeliveryItem", "true");
        parameters.put("deliveryObjectiveItem", frame.target());
        plans.replaceCurrent(
                planId,
                new PlanFrame.Spec(
                        "deliver", selected.itemId(), delivered + concreteChunk, parameters),
                "resolved delivery alias to exact carried item " + selected.itemId(),
                nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome tickDeliveryLeaf(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            ActionLease actionLease,
            long nowMillis) throws IOException {
        if (!Boolean.parseBoolean(frame.parameters().getOrDefault(
                "concreteDeliveryItem", "false"))) {
            Outcome concretized = concretizeLegacyDeliveryLeaf(planId, frame, nowMillis);
            if (concretized != null) return concretized;
        }
        int alreadyDelivered = integer(frame.parameters(), "delivered", 0);
        int requested = Math.toIntExact(frame.count());
        String recipient = required(frame.parameters(), "recipient");
        String nonce = frame.parameters().getOrDefault("handoffNonce", "");
        if (nonce.isBlank()
                && inventory.count(frame.target()) < requested - alreadyDelivered) {
            Outcome reselected = reselectConcreteDeliveryLeaf(
                    planId, frame, alreadyDelivered, requested, nowMillis);
            if (reselected != null) return reselected;
        }
        String transactionKey = nonce.isBlank() ? "" : deliveryTransactionKey(planId, nonce);
        DeliveryReceiptState receipt = transactionKey.isBlank()
                ? null : deliveryReceipts.get(transactionKey);
        if (receipt != null) {
            String expectedItem = DeliveryPolicy.normalizeItemId(frame.target());
            if (!receipt.recipient().equalsIgnoreCase(recipient)
                    || !receipt.itemId().equals(expectedItem)) {
                return Outcome.blocked("Paper handoff receipt did not match the prepared recipient/item");
            }
            int preparedCount = integer(frame.parameters(), "dropExpectedCount", 0);
            int priorConfirmed = integer(frame.parameters(), "handoffConfirmedCount", 0);
            if (preparedCount <= 0 || receipt.expectedCount() != preparedCount) {
                return Outcome.blocked(
                        "Paper handoff receipt violated the prepared count or monotonic checkpoint");
            }
            if (receipt.confirmedCount() < priorConfirmed) {
                for (String receiptId : receipt.receiptIds()) {
                    protocolSender.test(DeliveryProtocol.acknowledge(receiptId));
                }
                deliveryReceipts.remove(transactionKey);
                return Outcome.continuePlan();
            }
            int baseDelivered = integer(frame.parameters(), "handoffBaseDelivered", alreadyDelivered);
            int confirmedDelivered = Math.min(
                    requested,
                    Math.max(alreadyDelivered, baseDelivered + receipt.confirmedCount()));
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            checkpoint.put("delivered", Integer.toString(confirmedDelivered));
            checkpoint.put("handoffConfirmedCount", Integer.toString(receipt.confirmedCount()));
            TaskPlan deliveryPlan = plans.restore(planId).orElseThrow();
            PlanFrame deliveryRoot = nearestDeliveryRoot(deliveryPlan);
            Map<String, String> rootCheckpoint = new LinkedHashMap<>(deliveryRoot.parameters());
            int rootBase = integer(
                    frame.parameters(), "handoffRootDeliveredBase",
                    integer(deliveryRoot.parameters(), "deliveredTotal", 0));
            rootCheckpoint.put(
                    "deliveredTotal", Integer.toString(rootBase + receipt.confirmedCount()));
            rootCheckpoint.put("deliveryTransactionAccounted", "true");
            deliveries.confirmReceipt(planId, confirmedDelivered);
            boolean transactionComplete = receipt.confirmedCount() >= receipt.expectedCount();
            checkpoint.put("handoffAwaitingInventorySync", "true");
            checkpoint.put("handoffInventorySyncTarget", Integer.toString(Math.max(
                    0, integer(frame.parameters(), "dropBaselineCount", 0)
                            - receipt.confirmedCount())));
            checkpoint.put("handoffInventorySyncStartedAt", Long.toString(nowMillis));
            if (transactionComplete) {
                deliveryReceipts.remove(transactionKey);
                deliveryPreparations.remove(transactionKey);
                deliveryPrepareSentAt.remove(transactionKey);
                deliveryCommitResults.remove(transactionKey);
                deliveryCommitSentAt.remove(transactionKey);
                deliveries.clear(planId);
            }
            LinkedHashMap<String, Map<String, String>> frameCheckpoints = new LinkedHashMap<>();
            frameCheckpoints.put(deliveryRoot.id(), rootCheckpoint);
            Optional<PlanFrame> foodRoot = foodDeliveryRoot(deliveryPlan);
            if (foodRoot.isPresent()) {
                PlanFrame family = foodRoot.orElseThrow();
                Map<String, String> foodCheckpoint = new LinkedHashMap<>(family.parameters());
                int foodBase = integer(
                        frame.parameters(), "handoffFoodDeliveredBase",
                        integer(family.parameters(), "deliveredTotal", 0));
                foodCheckpoint.put("deliveredTotal", Integer.toString(
                        Math.min(Math.toIntExact(family.count()),
                                foodBase + receipt.confirmedCount())));
                frameCheckpoints.put(family.id(), foodCheckpoint);
            }
            Optional<PlanFrame> batch = batchRoot(deliveryPlan);
            if (batch.isPresent()) {
                PlanFrame batchFrame = batch.orElseThrow();
                int batchIndex = integer(frame.parameters(), "handoffBatchIndex", -1);
                int batchBase = integer(frame.parameters(), "handoffBatchDeliveredBase", 0);
                List<BatchItem> batchItems = parseBatchItems(required(
                        batchFrame.parameters(), "batchItems"));
                if (batchIndex < 0 || batchIndex >= batchItems.size()) {
                    return Outcome.blocked("Paper receipt had no valid gather-first batch cursor");
                }
                GatherFirstBatchPolicy.Update update =
                        GatherFirstBatchPolicy.confirmDeliveredTotal(
                                required(batchFrame.parameters(), "batchItems"),
                                batchFrame.parameters(), batchIndex,
                                batchItems.get(batchIndex).item(),
                                batchBase + receipt.confirmedCount());
                if (!update.valid()) {
                    return Outcome.blocked(
                            "Paper receipt conflicted with gather-first batch state: "
                                    + update.reason());
                }
                frameCheckpoints.put(batchFrame.id(), update.checkpoint());
            }
            frameCheckpoints.put(frame.id(), checkpoint);
            plans.checkpointFrames(
                    planId, frameCheckpoints,
                    "Paper verified handoff " + confirmedDelivered + "/" + requested
                            + "; synchronizing local inventory",
                    nowMillis);
            for (String receiptId : receipt.receiptIds()) {
                protocolSender.test(DeliveryProtocol.acknowledge(receiptId));
            }
            deliveryReceipts.remove(transactionKey);
            return Outcome.continuePlan();
        }

        if (Boolean.parseBoolean(frame.parameters().getOrDefault(
                "handoffAwaitingInventorySync", "false"))) {
            int targetCount = integer(frame.parameters(), "handoffInventorySyncTarget", 0);
            int currentCount = inventory.count(frame.target());
            long syncStartedAt = longInteger(
                    frame.parameters(), "handoffInventorySyncStartedAt", nowMillis);
            if (currentCount > targetCount && nowMillis - syncStartedAt < 5_000L) {
                return Outcome.running(
                        "Paper committed the handoff; waiting for the vanilla inventory update",
                        Double.NaN, alreadyDelivered, false);
            }
            int confirmed = integer(frame.parameters(), "handoffConfirmedCount", 0);
            int expected = integer(frame.parameters(), "dropExpectedCount", 0);
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            checkpoint.remove("handoffAwaitingInventorySync");
            checkpoint.remove("handoffInventorySyncTarget");
            checkpoint.remove("handoffInventorySyncStartedAt");
            if (confirmed >= expected && expected > 0) {
                clearHandoffCheckpoint(checkpoint);
                plans.checkpointCurrent(
                        planId, checkpoint,
                        "local inventory reconciled with Paper's complete handoff",
                        nowMillis);
                completeLeaf(
                        planId, frame, lease,
                        "Paper verified the complete exact handoff to " + recipient,
                        nowMillis);
                return Outcome.continuePlan();
            }
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "local inventory reconciled with Paper's partial handoff",
                    nowMillis);
            return Outcome.continuePlan();
        }

        DeliveryReturnState returned = transactionKey.isBlank()
                ? null : deliveryReturns.get(transactionKey);
        if (returned != null) {
            String expectedItem = DeliveryPolicy.normalizeItemId(frame.target());
            if (!returned.recipient().equalsIgnoreCase(recipient)
                    || !returned.itemId().equals(expectedItem)) {
                return Outcome.blocked("Paper returned-handoff frame did not match recipient/item");
            }
            int baseDelivered = integer(frame.parameters(), "handoffBaseDelivered", alreadyDelivered);
            int confirmedDelivered = Math.min(
                    requested, Math.max(alreadyDelivered, baseDelivered + returned.confirmedCount()));
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            checkpoint.put("delivered", Integer.toString(confirmedDelivered));
            checkpoint.put("handoffPrepared", "true");
            checkpoint.put("dropPending", "true");
            checkpoint.put("dropBaselineCount", Integer.toString(inventory.count(frame.target())));
            checkpoint.put("dropExpectedCount", Integer.toString(returned.remainingCount()));
            checkpoint.put("dropPreparedAt", Long.toString(nowMillis));
            checkpoint.remove("handoffConfirmedCount");
            deliveries.confirmReceipt(planId, confirmedDelivered);
            plans.checkpointCurrent(
                    planId, checkpoint,
                    "Paper confirmed Entity re-collected the handoff remainder; re-dropping same nonce",
                    nowMillis);
            protocolSender.test(DeliveryProtocol.acknowledgeReturned(returned.returnId()));
            deliveryReturns.remove(transactionKey);
            return Outcome.continuePlan();
        }

        if (!nonce.isBlank()
                && !Boolean.parseBoolean(frame.parameters().getOrDefault("handoffPrepared", "false"))) {
            if (!frame.parameters().getOrDefault("handoffSelections", "").isBlank()
                    && !protocolFeature.test("delivery_exact_inventory_commit")) {
                return Outcome.blocked("prepared exact-stack handoff requires its matching Paper capability; no physical fallback");
            }
            DeliveryPrepareState prepared = deliveryPreparations.get(transactionKey);
            if (prepared != null && !prepared.accepted()) {
                return Outcome.blocked("Paper rejected handoff preparation: " + prepared.reason());
            }
            if (prepared != null) {
                Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
                checkpoint.put("handoffPrepared", "true");
                checkpoint.put("dropPreparedAt", Long.toString(nowMillis));
                if (protocolFeature.test("delivery_inventory_commit")) {
                    checkpoint.put("handoffMode", "inventory_commit");
                    checkpoint.remove("dropPending");
                } else {
                    checkpoint.put("handoffMode", "physical_drop");
                    checkpoint.put("dropPending", "true");
                }
                plans.checkpointCurrent(
                        planId, checkpoint, "Paper bound exact handoff transaction " + nonce, nowMillis);
                return Outcome.continuePlan();
            }
            long lastSent = deliveryPrepareSentAt.getOrDefault(transactionKey, 0L);
            if (nowMillis - lastSent >= 1_000L) {
                int batch = integer(frame.parameters(), "dropExpectedCount", 0);
                boolean sent = protocolSender.test(DeliveryProtocol.prepare(
                        planId, nonce, recipient, frame.target(), batch, nowMillis,
                        frame.parameters().getOrDefault("handoffSelections", "")));
                if (sent) deliveryPrepareSentAt.put(transactionKey, nowMillis);
            }
            return Outcome.running(
                    "waiting for Paper to prepare exact handoff " + nonce,
                    Double.NaN, alreadyDelivered, false);
        }
        if (!nonce.isBlank()
                && frame.parameters().getOrDefault("handoffMode", "")
                .equals("inventory_commit")) {
            return tickInventoryCommitHandoff(
                    planId, frame, lease, actionLease, recipient, nonce,
                    requested, alreadyDelivered, nowMillis);
        }
        boolean dropPending = Boolean.parseBoolean(frame.parameters().getOrDefault("dropPending", "false"));
        int current = inventory.count(frame.target());
        if (dropPending && nonce.isBlank()) {
            int legacyBaseline = integer(frame.parameters(), "dropBaselineCount", current);
            if (current < legacyBaseline) {
                return Outcome.blocked(
                        "an old untagged handoff left Entity's inventory and cannot be proven; use /e retry");
            }
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            clearHandoffCheckpoint(checkpoint);
            plans.checkpointCurrent(
                    planId, checkpoint, "migrated old delivery state to exact Paper transactions", nowMillis);
            return Outcome.continuePlan();
        }
        if (dropPending && deliveryPendingInvalidated) {
            deliveries.clear(planId);
            int pendingBaseline = integer(frame.parameters(), "dropBaselineCount", current);
            long replayUntil = longInteger(
                    frame.parameters(), "deathHandoffReplayUntil", 0L);
            if (Boolean.parseBoolean(frame.parameters().getOrDefault(
                    "deathRecoveredHandoff", "false"))
                    && nowMillis < replayUntil) {
                return Outcome.running(
                        "waiting for Paper to replay the preserved handoff receipt/return",
                        Double.NaN,
                        alreadyDelivered,
                        false);
            }
            deliveryPendingInvalidated = false;
            if (current < pendingBaseline) {
                long preparedAt = Long.parseLong(frame.parameters().getOrDefault(
                        "dropPreparedAt", Long.toString(nowMillis)));
                if (nowMillis - preparedAt > 12_000L) {
                    return Outcome.blocked(
                            "handoff left Entity's inventory but Paper pickup proof did not arrive; "
                                    + "the recipient inventory may be full. Make space, keep the bridge connected, "
                                    + "and use /e retry");
                }
                return Outcome.running(
                        "waiting for Paper to verify the interrupted handoff",
                        Double.NaN,
                        alreadyDelivered,
                        false);
            }
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            clearHandoffCheckpoint(checkpoint);
            plans.checkpointCurrent(
                    planId,
                    checkpoint,
                    "discarded pending delivery proof after control preemption",
                    nowMillis);
            return Outcome.continuePlan();
        }
        if (dropPending) {
            // Inventory loss proves only that an item entity was created. Paper's
            // delivery_receipt frame, above, proves the intended player got it.
            int expected = integer(frame.parameters(), "dropExpectedCount", 0);
            int confirmed = integer(frame.parameters(), "handoffConfirmedCount", 0);
            if (confirmed > 0 && confirmed < expected) {
                return Outcome.running(
                        "Paper verified " + confirmed + "/" + expected
                                + " from the exact stack; waiting for recipient pickup",
                        Double.NaN, alreadyDelivered, false);
            }
        } else {
            deliveryPendingInvalidated = false;
            int remaining = requested - alreadyDelivered;
            if (current < remaining) {
                if (!nonce.isBlank()) {
                    return reconcileLostDeliveryCargo(
                            planId, frame, nonce,
                            "exact prepared delivery cargo changed before handoff",
                            nowMillis);
                }
                if (Boolean.parseBoolean(frame.parameters().getOrDefault("allowAcquire", "false"))) {
                    return pushMissingResource(
                            planId, frame.target(), remaining,
                            "delivery inventory is short", nowMillis);
                }
                return Outcome.blocked("Entity no longer has enough " + frame.target() + " to give");
            }
        }
        DeliveryController.Result result = deliveries.tick(
                planId,
                recipient,
                DeliveryPolicy.normalizeItemId(frame.target()),
                requested,
                alreadyDelivered,
                dropPending,
                inventoryTransactionOwner(actionLease, "physical-handoff"),
                actionLease.operationId() + ":handoff-removal",
                lease,
                baritone,
                nowMillis);
        if (result.delivered() != alreadyDelivered) {
            Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
            checkpoint.put("delivered", Integer.toString(result.delivered()));
            plans.checkpointCurrent(planId, checkpoint, result.detail(), nowMillis);
        }
        String exactSelection = null;
        if (result.state() == DeliveryController.State.DROP_READY) {
            if (!protocolFeature.test("delivery_exact_inventory_commit"))
                return Outcome.blocked("This delivery requires the matching Paper exact-stack handoff capability");
            DeliveryOrder currentOrder = deliveryOrder(planId);
            if (currentOrder != null && currentOrder.bring() && !currentOrder.requests().isEmpty()
                    && !deliverySuppliesPlan(planId, currentOrder).ready()) {
                deliveries.clear(planId);
                plans.rebaseToRoot(planId, currentOrder.root().parameters(),
                        "delivery supplies changed before nonce; reobserve relevant shortfall", nowMillis);
                return Outcome.continuePlan();
            }
            try {
                exactSelection = exactDeliverySelection(planId, frame.target(), requested - alreadyDelivered);
            } catch (IllegalArgumentException changed) {
                return Outcome.blocked("delivery selection unavailable before preparing nonce: " + changed.getMessage());
            }
        }
        Outcome outcome = switch (result.state()) {
            case COMPLETE -> {
                deliveries.clear(planId);
                completeLeaf(planId, frame, lease, result.detail(), nowMillis);
                yield Outcome.continuePlan();
            }
            case ITEM_MISSING -> Boolean.parseBoolean(frame.parameters().getOrDefault("allowAcquire", "false"))
                    ? pushMissingResource(planId, frame.target(), requested - result.delivered(),
                    result.detail(), nowMillis)
                    : Outcome.blocked(result.detail());
            case BLOCKED -> Outcome.blocked(result.detail());
            case RETRY -> Outcome.retry(result.detail());
            case DROP_READY -> {
                Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
                checkpoint.put("handoffSelections", exactSelection);
                String preparedNonce = UUID.randomUUID().toString();
                checkpoint.put("handoffNonce", preparedNonce);
                checkpoint.put("handoffPrepared", "false");
                checkpoint.put("handoffBaseDelivered", Integer.toString(alreadyDelivered));
                TaskPlan plan = plans.restore(planId).orElseThrow();
                PlanFrame deliveryRoot = nearestDeliveryRoot(plan);
                checkpoint.put("handoffRootDeliveredBase", Integer.toString(integer(
                        deliveryRoot.parameters(), "deliveredTotal", 0)));
                foodDeliveryRoot(plan).ifPresent(foodRoot -> checkpoint.put(
                        "handoffFoodDeliveredBase", Integer.toString(integer(
                                foodRoot.parameters(), "deliveredTotal", 0))));
                batchRoot(plan).ifPresent(batch -> {
                    int batchIndex = integer(batch.parameters(),
                            GatherFirstBatchPolicy.DELIVERY_CURSOR_KEY, 0);
                    checkpoint.put("handoffBatchIndex", Integer.toString(batchIndex));
                    checkpoint.put("handoffBatchDeliveredBase", Integer.toString(
                            encodedCountAt(batch.parameters().get(
                                    GatherFirstBatchPolicy.DELIVERED_COUNTS_KEY), batchIndex)));
                });
                checkpoint.put("dropBaselineCount", Integer.toString(current));
                int handoffCount = protocolFeature.test("delivery_inventory_commit")
                        ? requested - alreadyDelivered
                        : inventory.exactDropQuantity(
                        frame.target(), requested - alreadyDelivered);
                checkpoint.put("dropExpectedCount", Integer.toString(Math.max(1, handoffCount)));
                plans.checkpointCurrent(planId, checkpoint, result.detail(), nowMillis);
                yield Outcome.continuePlan();
            }
            case APPROACHING, HOLDING_POSITION, DROPPING, WAITING_FOR_PICKUP, WAITING -> Outcome.running(
                    result.detail(), Double.NaN, result.delivered(),
                    result.state() == DeliveryController.State.APPROACHING);
        };
        return outcome.state() == State.RUNNING || outcome.state() == State.RETRY
                ? observeLeaf(frame, outcome, lease, nowMillis, INVENTORY_ACTION_POLICY)
                : outcome;
    }

    private Outcome reselectConcreteDeliveryLeaf(
            String planId,
            PlanFrame frame,
            int alreadyDelivered,
            int requested,
            long nowMillis) throws IOException {
        String objective = frame.parameters().getOrDefault(
                "deliveryObjectiveItem", frame.target());
        Optional<ClientInventoryController.ConcreteItem> replacement =
                permittedConcreteDelivery(planId, objective);
        if (replacement.isPresent()) {
            ClientInventoryController.ConcreteItem selected = replacement.orElseThrow();
            int chunk = Math.min(requested - alreadyDelivered, selected.count());
            if (chunk > 0) {
                LinkedHashMap<String, String> parameters =
                        new LinkedHashMap<>(frame.parameters());
                parameters.put("concreteDeliveryItem", "true");
                parameters.put("deliveryObjectiveItem", objective);
                plans.replaceCurrent(
                        planId,
                        new PlanFrame.Spec(
                                "deliver", selected.itemId(), alreadyDelivered + chunk, parameters),
                        "delivery cargo changed before Paper preparation; selected exact carried item "
                                + selected.itemId(),
                        nowMillis);
                return Outcome.continuePlan();
            }
        }

        TaskPlan plan = plans.restore(planId).orElseThrow();
        PlanFrame root = plan.frames().getFirst();
        boolean mayReacquire = root.kind().equals("root.bring")
                || root.kind().equals("root.food.bring")
                || root.kind().equals("root.batch.bring");
        Map<String, String> rootCheckpoint = new LinkedHashMap<>(root.parameters());
        clearDeathHandoffCheckpoint(rootCheckpoint);
        deliveries.clear(planId);
        plans.rebaseToRoot(
                planId, rootCheckpoint,
                "concrete delivery cargo changed before Paper preparation; "
                        + (mayReacquire
                        ? "reacquiring the durable objective"
                        : "returning to the give root for an honest inventory decision"),
                nowMillis);
        return Outcome.continuePlan();
    }

    private Outcome tickInventoryCommitHandoff(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            ActionLease actionLease,
            String recipient,
            String nonce,
            int requested,
            int alreadyDelivered,
            long nowMillis) throws IOException {
        int remaining = Math.max(0, requested - alreadyDelivered);
        String transactionKey = deliveryTransactionKey(planId, nonce);
        DeliveryProtocol.CommitResult result = deliveryCommitResults.get(transactionKey);
        if (result != null && (!result.recipient().isBlank()
                && !result.recipient().equalsIgnoreCase(recipient)
                || !result.item().isBlank()
                && !result.item().equals(DeliveryPolicy.normalizeItemId(frame.target()))
                || result.expectedCount() > 0
                && result.expectedCount() != integer(
                frame.parameters(), "dropExpectedCount", 0))) {
            return Outcome.blocked("Paper inventory-commit result did not match the prepared handoff");
        }

        int current = inventory.count(frame.target());
        long lastSent = deliveryCommitSentAt.getOrDefault(transactionKey, 0L);
        int checkpointConfirmed = integer(frame.parameters(), "handoffConfirmedCount", 0);
        if (current < remaining) {
            InventoryCommitProofPolicy.Decision proofDecision =
                    InventoryCommitProofPolicy.inventoryDeficit(
                            nowMillis,
                            lastSent,
                            checkpointConfirmed,
                            result != null,
                            result != null && result.accepted(),
                            result == null ? 0 : result.confirmedCount());
            if (proofDecision
                    == InventoryCommitProofPolicy.Decision.WAIT_FOR_DURABLE_PROOF) {
                return Outcome.running(
                        "Paper may already have committed this exact nonce; waiting for its durable receipt",
                        Double.NaN, alreadyDelivered, false);
            }
            return reconcileLostDeliveryCargo(
                    planId, frame, nonce,
                    "inventory-commit cargo changed without durable Paper progress",
                    nowMillis);
        }

        DeliveryController.Result movement = deliveries.tick(
                planId,
                recipient,
                DeliveryPolicy.normalizeItemId(frame.target()),
                requested,
                alreadyDelivered,
                false,
                inventoryTransactionOwner(actionLease, "paper-handoff-position"),
                actionLease.operationId() + ":handoff-removal",
                lease,
                baritone,
                nowMillis);
        if (movement.state() == DeliveryController.State.ITEM_MISSING) {
            return reconcileLostDeliveryCargo(
                    planId, frame, nonce, movement.detail(), nowMillis);
        }
        if (movement.state() == DeliveryController.State.BLOCKED) {
            return Outcome.blocked(movement.detail());
        }
        if (movement.state() == DeliveryController.State.RETRY) {
            return Outcome.retry(movement.detail());
        }
        if (movement.state() != DeliveryController.State.DROP_READY) {
            return Outcome.running(
                    movement.detail(), Double.NaN, alreadyDelivered,
                    movement.state() == DeliveryController.State.APPROACHING);
        }

        String detail = "ready for Paper's inventory handoff";
        long retryDelay = 1_000L;
        if (result != null) {
            detail = result.message().isBlank() ? result.code() : result.message();
            if (!result.accepted() && !result.retryable()) {
                return Outcome.blocked("Paper rejected inventory handoff: "
                        + result.code() + " (" + detail + ")");
            }
            if (result.code().equals("recipient_full")) retryDelay = 2_000L;
        }

        if (nowMillis - lastSent >= retryDelay) {
            if (protocolSender.test(DeliveryProtocol.commit(planId, nonce, nowMillis))) {
                deliveryCommitSentAt.put(transactionKey, nowMillis);
            }
        }
        return Outcome.running(
                detail + "; retaining every unconfirmed item and retrying the same nonce",
                Double.NaN, alreadyDelivered, false);
    }

    private Outcome reconcileLostDeliveryCargo(
            String planId,
            PlanFrame frame,
            String nonce,
            String reason,
            long nowMillis) throws IOException {
        TaskPlan plan = plans.restore(planId).orElseThrow();
        PlanFrame root = plan.frames().getFirst();
        boolean mayReacquire = root.kind().equals("root.bring")
                || root.kind().equals("root.food.bring")
                || root.kind().equals("root.batch.bring");
        if (!mayReacquire) {
            return Outcome.blocked(reason + "; this give mission may not acquire replacements");
        }
        retireDeliveryTransaction(planId, nonce);
        Map<String, String> rootCheckpoint = new LinkedHashMap<>(root.parameters());
        // A death-rehydrated transaction whose cargo was destroyed must not
        // recreate the same now-impossible nonce forever. Confirmed root,
        // family, and batch totals remain; only the interrupted handoff is
        // retired before acquisition resumes.
        clearDeathHandoffCheckpoint(rootCheckpoint);
        plans.rebaseToRoot(
                planId,
                rootCheckpoint,
                reason + "; rebasing to the durable root so gather-first can reacquire",
                nowMillis);
        return Outcome.continuePlan();
    }

    private void retireDeliveryTransaction(String planId, String nonce) {
        String transactionKey = deliveryTransactionKey(planId, nonce);
        deliveryReceipts.remove(transactionKey);
        deliveryReturns.remove(transactionKey);
        deliveryPreparations.remove(transactionKey);
        deliveryPrepareSentAt.remove(transactionKey);
        deliveryCommitResults.remove(transactionKey);
        deliveryCommitSentAt.remove(transactionKey);
        deliveries.clear(planId);
    }

    private Outcome ensureProductionPrerequisites(
            String planId,
            PlanFrame frame,
            boolean smelting,
            long nowMillis) throws IOException {
        if (smelting && inventory.isFurnaceOpen()) return null;
        int operations = Math.max(1, integer(frame.parameters(), "operations", Math.toIntExact(frame.count())));
        int completedOperations = 0;
        String baseline = frame.parameters().get("startingCount");
        if (baseline != null) {
            int outputPerOperation = Math.max(1,
                    integer(frame.parameters(), "outputPerOperation", 1));
            int gained = Math.max(0, frameObjectiveCount(frame, frame.target())
                    - integer(frame.parameters(), "startingCount",
                    frameObjectiveCount(frame, frame.target())));
            completedOperations = Math.min(operations, gained / outputPerOperation);
        }
        int remainingOperations = Math.max(0, operations - completedOperations);
        Map<String, Integer> plannedIngredients = parseCounts(
                frame.parameters().getOrDefault("ingredients", ""));
        boolean groupedCraft = !smelting && isCommittedUniversalAction(frame)
                && "true".equals(frame.parameters().get("mixedIngredientGroups"));
        if (groupedCraft) {
            ResourceCatalog.RecipeMetadata metadata = plannedRecipeMetadata(frame);
            if (metadata.slots().isEmpty()
                    || (metadata.layout() != ResourceCatalog.RecipeLayout.SHAPED
                    && metadata.layout() != ResourceCatalog.RecipeLayout.SHAPELESS)) {
                return Outcome.blocked("mixed crafting allocation has no authoritative recipe groups");
            }
            // Vanilla recipe filling chooses the physical alternative on each
            // operation. Reconcile the remaining authoritative slot groups, not
            // an invented per-operation requirement for every selected colour.
            // Caps in canComplete retain the compiler's exact allocated inputs;
            // food's existing reservation exemption is scoped to its own cargo.
            boolean food = plans.restore(planId).flatMap(AutonomyExecutor::foodObjectiveRoot).isPresent();
            Map<String, Integer> spendable = food
                    ? foodPlanningInventoryView(planId, false).itemCounts()
                    : reservations.spendableCounts(
                            planningInventoryViewForPlan(planId).itemCounts(), Set.of(Purpose.MISSION_CARGO));
            // Recipe-book filling cannot promise which equivalent colour it
            // consumes. Only this new mixed-allocation path needs this guard;
            // legacy/single-choice recipe admission remains unchanged.
            if (!CraftingIngredientCapacity.protectsReservedAlternatives(
                    metadata.slots(), inventoryView().itemCounts(), spendable, remainingOperations)) {
                return Outcome.blocked("mixed crafting alternatives overlap reserved cargo; "
                        + "vanilla recipe fill cannot safely select only the unreserved items");
            }
            if (!CraftingIngredientCapacity.canComplete(
                    metadata.slots(), plannedIngredients, spendable, operations, completedOperations)) {
                return invalidateUniversalProgramAndRebase(
                        planId, frame, DivergenceCause.INVENTORY_LOSS,
                        "remaining authoritative crafting ingredient groups lost allocated capacity",
                        nowMillis);
            }
        } else {
            for (Map.Entry<String, Integer> ingredient : plannedIngredients.entrySet()) {
                int perOperation = Math.max(1, ingredient.getValue() / operations);
                int requiredRemaining = Math.multiplyExact(perOperation, remainingOperations);
                int missing = requiredRemaining - frameObjectiveCount(frame, ingredient.getKey());
                if (missing > 0) {
                    return pushMissingProductionResource(
                            planId, frame, ingredient.getKey(), requiredRemaining,
                            "ingredient was consumed or lost before " + frame.kind(), nowMillis,
                            planningInventoryViewForPlan(planId));
                }
            }
        }
        String station = frame.parameters().getOrDefault("workstation", "");
        if (!station.isBlank() && virtualStationCount(station) <= 0) {
            return pushMissingProductionResource(
                    planId, frame, station, 1,
                    "workstation is no longer available", nowMillis,
                    planningInventoryViewForPlan(planId));
        }
        if (smelting) {
            String fuel = frame.parameters().getOrDefault("fuelItem", "coal");
            int requiredFuel = integer(frame.parameters(), "fuelCount", 1);
            Optional<TaskPlan> plan = plans.restore(planId)
                    .filter(candidate -> candidate.state() == TaskPlanState.OPEN);
            if (plan.flatMap(AutonomyExecutor::foodObjectiveRoot).isPresent()) {
                ResourcePlanner.InventoryView spendable =
                        foodPlanningInventoryView(planId, false);
                FoodFuelBudget budget = foodFuelBudget(
                        List.of(Math.max(1, remainingOperations)),
                        spendable.itemCounts());
                if (!budget.satisfied()) {
                    int coalTarget = foodCoalAcquisitionTarget(
                            planId, budget.requiredRegularFuel(), spendable.itemCounts());
                    return pushMissingProductionResource(
                            planId, frame, "coal", coalTarget,
                            "smelting fuel is missing beyond protected cargo",
                            nowMillis, inventoryView());
                }
            } else if (frameObjectiveCount(frame, fuel) < requiredFuel
                    && !inventory.isFurnaceOpen()) {
                return pushMissingProductionResource(
                        planId, frame, fuel, requiredFuel,
                        "smelting fuel is missing", nowMillis,
                        planningInventoryViewForPlan(planId));
            }
        }
        return null;
    }

    /**
     * Reconciles a prerequisite lost after a production leaf was compiled.
     *
     * <p>Authoritative recipes preserve exact ingredient alternatives. Every
     * production prerequisite therefore uses the multi-variant compiler,
     * including a workstation requested by a legacy food/session frame.
     * Falling through the legacy scalar planner collapsed recipe tags to their
     * first wire alternative and discarded measured tool durability. In a
     * natural Overworld run, Paper's furnace tag consequently became a futile
     * blackstone bootstrap even though cobblestone and a live wooden pick were
     * already carried. Recompiling the outstanding exact floor keeps the
     * recovery transactional and chooses a feasible physical variant.</p>
     */
    private Outcome pushMissingProductionResource(
            String planId,
            PlanFrame productionFrame,
            String item,
            int requiredTotal,
            String reason,
            long nowMillis,
            ResourcePlanner.InventoryView planningView) throws IOException {
        if (isCommittedUniversalAction(productionFrame)) {
            String station = productionFrame.parameters().getOrDefault("workstation", "");
            DivergenceCause cause = !station.isBlank()
                    && resourcePlanner.normalizeItem(station)
                    .equals(resourcePlanner.normalizeItem(item))
                    ? DivergenceCause.WORKSTATION_LOSS
                    : PICKAXE_RANKS.containsKey(resourcePlanner.normalizeItem(item))
                    ? DivergenceCause.TOOL_LOSS
                    : DivergenceCause.INVENTORY_LOSS;
            return invalidateUniversalProgramAndRebase(
                    planId, productionFrame, cause, reason, nowMillis);
        }

        return pushExactMissingResource(
                planId, item, requiredTotal, reason, nowMillis, planningView,
                "exact universal prerequisite recovery");
    }

    /**
     * Compiles one scalar runtime prerequisite through the exact variant and
     * durability-aware planner before pushing its first physical action.
     *
     * <p>The legacy scalar planner intentionally has neither authoritative
     * ingredient alternatives nor live remaining tool durability.  It must
     * therefore not be used at a Paper recipe boundary.  The natural food
     * run {@code 20260822-075951-d37ddfbe1c} exposed the remaining call site:
     * while carrying 32 cobblestone and a wooden pick with 22 durability, a
     * nine-coal fuel target tried to upgrade through Paper's first stone tag
     * member and searched the Overworld for three blackstone.  Exact
     * compilation keeps the carried wooden pick and mines the coal directly.</p>
     */
    private Outcome pushExactMissingResource(
            String planId,
            String item,
            int requiredTotal,
            String reason,
            long nowMillis,
            ResourcePlanner.InventoryView planningView,
            String exactReason) throws IOException {
        AcquisitionPlan compiled;
        try {
            compiled = resourcePlanner.compile(new AcquisitionRequest(
                    List.of(new AcquisitionRequest.ItemGoal(item, Math.max(1, requiredTotal))),
                    planningView,
                    usablePickaxeDurability()));
        } catch (ResourcePlanner.PlanningException error) {
            return Outcome.blocked(error.getMessage());
        }
        if (compiled.complete()) return Outcome.continuePlan();
        pushAction(
                planId,
                prepareLegacyUniversalAction(planId, compiled),
                nowMillis,
                false,
                reason + "; " + exactReason);
        return Outcome.continuePlan();
    }

    /** Compatibility for a persisted pre-commitment universal leaf. */
    private Action prepareLegacyUniversalAction(String planId, AcquisitionPlan compiled) {
        PlanFrame homeRoot = plans.restore(planId)
                .filter(candidate -> candidate.state() == TaskPlanState.OPEN)
                .flatMap(AutonomyExecutor::innermostHomeStationBinding)
                .orElse(null);
        return prepareRuntimePrerequisiteAction(
                planId, homeRoot, compiled.firstAction().orElseThrow());
    }

    /** Exact runtime prerequisite with the same Home pin semantics as a committed program. */
    static Action prepareRuntimePrerequisiteAction(
            String planId,
            PlanFrame homeRoot,
            Action first) {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>(first.parameters());
        parameters.put("universalAcquisition", "true");
        if (homeRoot != null && (isHomeMaintenance(homeRoot) || hasHomeMissionSupplyPinAuthority(homeRoot))) {
            copyHomeBinding(homeRoot.parameters(), parameters);
        }
        String workstation = parameters.getOrDefault("workstation", "");
        if (!workstation.isBlank()) {
            boolean pinnedHomeStation = homeRoot != null && usesPinnedHomeStation(homeRoot, workstation);
            parameters.put("workstationSession", pinnedHomeStation
                    ? pinnedHomeWorkstationOperation(homeRoot, workstation)
                    : universalWorkstationOperationId(planId, workstation));
            // The compiler's action-local annotation is generation-aware. A suffix scan would
            // retain a station past an intervening recipe which consumes it, then strand that
            // physical ingredient before the later replacement generation.
            boolean committedRetain = Boolean.parseBoolean(
                    parameters.getOrDefault("retainWorkstation", "false"));
            parameters.put("retainWorkstation", Boolean.toString(
                    pinnedHomeStation || committedRetain));
            if (pinnedHomeStation) parameters.put("homePinnedStation", "true");
        }
        return new Action(
                first.kind(), first.target(), first.count(), parameters, first.description());
    }

    private Outcome pushMissingResource(
            String planId,
            String item,
            int requiredTotal,
            String reason,
            long nowMillis) throws IOException {
        return pushMissingResource(
                planId, item, requiredTotal, reason, nowMillis,
                planningInventoryViewForPlan(planId));
    }

    private Outcome pushMissingResource(
            String planId,
            String item,
            int requiredTotal,
            String reason,
            long nowMillis,
            ResourcePlanner.InventoryView planningView) throws IOException {
        ResourcePlanner.Decision decision;
        try {
            decision = resourcePlanner.nextAction(
                    ResourcePlanner.Request.acquire(item, Math.max(1, requiredTotal)),
                    planningView);
        } catch (ResourcePlanner.PlanningException error) {
            return Outcome.blocked(error.getMessage());
        }
        if (decision.complete()) return Outcome.continuePlan();
        pushAction(planId, decision.action().orElseThrow(), nowMillis, false, reason);
        return Outcome.continuePlan();
    }

    private void pushAction(
            String planId,
            Action action,
            long nowMillis,
            boolean deliveryCanAcquire) throws IOException {
        pushAction(planId, action, nowMillis, deliveryCanAcquire, action.description());
    }

    private void pushAction(
            String planId,
            Action action,
            long nowMillis,
            boolean deliveryCanAcquire,
            String reason) throws IOException {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>(action.parameters());
        parameters.put("description", action.description());
        String frameTarget = action.target();
        int frameCount = action.count();
        if (action.kind() == ActionKind.DELIVER) {
            parameters.put("allowAcquire", Boolean.toString(deliveryCanAcquire));
            parameters.putIfAbsent("delivered", "0");
            boolean exactDelivery = Boolean.parseBoolean(
                    parameters.getOrDefault("exactDelivery", "false"));
            Optional<ClientInventoryController.ConcreteItem> concrete = exactDelivery
                    ? inventory.count(action.target()) > 0
                    ? Optional.of(new ClientInventoryController.ConcreteItem(
                    ClientInventoryController.normalizeId(action.target()),
                    inventory.count(action.target())))
                    : Optional.empty()
                    : inventory.largestConcreteEquivalent(action.target());
            if (concrete.isPresent()) {
                ClientInventoryController.ConcreteItem selected = concrete.orElseThrow();
                frameTarget = selected.itemId();
                frameCount = Math.min(frameCount, selected.count());
                parameters.put("concreteDeliveryItem", "true");
                parameters.put("deliveryObjectiveItem", action.target());
            }
        }
        plans.pushPrerequisite(
                planId,
                new PlanFrame.Spec(
                        action.kind().name().toLowerCase(Locale.ROOT),
                        frameTarget,
                        frameCount,
                        parameters),
                reason,
                nowMillis);
    }

    private Baseline baseline(String planId, PlanFrame frame, long nowMillis) throws IOException {
        int current = frameObjectiveCount(frame, frame.target());
        String encoded = frame.parameters().get("startingCount");
        if (encoded != null) return new Baseline(true, integer(frame.parameters(), "startingCount", current));
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        checkpoint.put("startingCount", Integer.toString(current));
        plans.checkpointCurrent(planId, checkpoint, "recorded inventory baseline", nowMillis);
        return new Baseline(false, current);
    }

    private static MiningSessionKey miningSessionKey(String planId, PlanFrame frame) {
        return new MiningSessionKey(planId, frame.id());
    }

    /** Carries catalog-authenticated resource semantics to the final live actuator. */
    private static Map<String, String> miningGoalArguments(
            PlanFrame frame,
            String blocks,
            int itemCount,
            int maximumBlocks,
            boolean probabilistic,
            String expectedItems) {
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("blocks", blocks);
        args.put("count", Integer.toString(itemCount));
        args.put("maxBlocks", Integer.toString(maximumBlocks));
        args.put("probabilistic", Boolean.toString(probabilistic));
        args.put("expectedItems", expectedItems);
        args.putAll(MiningEntranceCheckpoint
                .goalArguments(frame.parameters(), "miningDropOrigin"));
        for (String key : List.of(
                "worldSourceKind",
                "replantItem",
                "resourceAreaName",
                "resourceAreaFingerprint",
                "completeWhenResourceExhausted",
                "managedFarm",
                "allowBreak",
                "allowPlace",
                "allowParkourPlace",
                "allowWaterBucketFall")) {
            String value = frame.parameters().getOrDefault(key, "").trim();
            if (!value.isEmpty()) args.put(key, value);
        }
        return Map.copyOf(args);
    }

    private MiningSession miningSession(
            MiningSessionKey key,
            String sessionId,
            int missionBaselineItemCount,
            int currentItemCount,
            int requestedItemGain,
            int minimumDropsPerBlock) {
        return miningSession(
                key,
                sessionId,
                missionBaselineItemCount,
                currentItemCount,
                requestedItemGain,
                minimumDropsPerBlock,
                1,
                false);
    }

    private MiningSession miningSession(
            MiningSessionKey key,
            String sessionId,
            int missionBaselineItemCount,
            int currentItemCount,
            int requestedItemGain,
            int minimumDropsPerBlock,
            int estimatedBlocksPerDrop,
            boolean probabilistic) {
        MiningSession existing = miningSessions.get(key);
        if (existing != null) {
            int expectedTarget = Math.addExact(missionBaselineItemCount, requestedItemGain);
            if (!existing.sessionId().equals(sessionId)
                    || existing.missionBaselineItemCount() != missionBaselineItemCount
                    || existing.absoluteTargetItemCount() != expectedTarget
                    || existing.minimumDropsPerBlock() != minimumDropsPerBlock
                    || existing.estimatedBlocksPerDrop() != estimatedBlocksPerDrop
                    || existing.probabilistic() != probabilistic) {
                throw new IllegalStateException(
                        "live mining session disagrees with its durable plan frame " + key.frameId());
            }
            return existing;
        }
        MiningSession created = MiningSessionPolicy.startFromMissionBaseline(
                sessionId,
                missionBaselineItemCount,
                currentItemCount,
                requestedItemGain,
                minimumDropsPerBlock,
                estimatedBlocksPerDrop,
                probabilistic);
        miningSessions.put(key, created);
        return created;
    }

    private static void requireMiningActionOwner(
            ActionLease actionLease,
            ActionOwner expectedOwner) {
        Objects.requireNonNull(actionLease, "actionLease");
        if (actionLease.owner() != expectedOwner) {
            throw new IllegalStateException(
                    "mining segment requires " + expectedOwner
                            + " but holds " + actionLease.owner());
        }
    }

    private static String precedingMiningOperationId(MiningSession pickupSession) {
        if (pickupSession.segment() != MiningSession.Segment.PICKUP
                || pickupSession.segmentGeneration() < 1) {
            throw new IllegalArgumentException(
                    "a pickup segment must follow an explicitly ended mining segment");
        }
        return pickupSession.sessionId() + "/mining/"
                + (pickupSession.segmentGeneration() - 1);
    }

    private static boolean retainWorkstation(PlanFrame frame) {
        return Boolean.parseBoolean(
                frame.parameters().getOrDefault("retainWorkstation", "false"));
    }

    private static boolean usesPinnedHomeStation(
            PlanFrame frame,
            WorkstationController.Kind kind) {
        if (!Boolean.parseBoolean(
                frame.parameters().getOrDefault("homePinnedStation", "false"))) {
            return false;
        }
        return simple(frame.parameters().getOrDefault("workstation", ""))
                .equals(simple(kind.itemId()));
    }

    private WorkstationController.Result tickOpenPlanWorkstation(
            PlanFrame frame,
            WorkstationController.Kind kind,
            ControlLease lease,
            long nowMillis) {
        if (!usesPinnedHomeStation(frame, kind)) {
            return workstations.tickOpen(
                    kind, workstationOperationId(frame), lease, baritone, nowMillis);
        }
        HomeBinding binding = resolveHomeBinding(frame.parameters());
        if (!binding.valid()) {
            return new WorkstationController.Result(
                    WorkstationController.State.BLOCKED, binding.detail(), null);
        }
        if (!binding.home().dimension().equals(currentDimension())) {
            return new WorkstationController.Result(
                    WorkstationController.State.BLOCKED,
                    "portal-unavailable: pinned Home " + kind.displayName() + " is in "
                            + binding.home().dimension() + " while Entity is in "
                            + currentDimension(),
                    null);
        }
        HomeEconomySession.AssetRole role = switch (kind) {
            case CRAFTING_TABLE -> HomeEconomySession.AssetRole.CRAFTING_TABLE;
            case FURNACE -> HomeEconomySession.AssetRole.FURNACE;
            case CHEST -> HomeEconomySession.AssetRole.CHEST;
            case WHITE_BED -> throw new IllegalArgumentException(
                    "a Home bed is not a workstation screen");
        };
        String ledgerAssetId = binding.ledgerIds().get(role);
        if (ledgerAssetId == null) {
            return new WorkstationController.Result(
                    WorkstationController.State.BLOCKED,
                    "home_" + role.item() + "_not_pinned: an acquisition cannot use "
                            + "the not-yet-owned target asset as its workstation",
                    null);
        }
        return workstations.tickOpenPinned(
                kind,
                workstationOperationId(frame),
                ledgerAssetId,
                binding.home(),
                HOME_ASSET_RADIUS,
                lease,
                baritone,
                nowMillis);
    }

    private static String universalWorkstationFactKey(
            String workstation,
            String fact) {
        return UNIVERSAL_WORKSTATION_FACT_PREFIX + simple(workstation) + '.' + fact;
    }

    /** Restores only a matching, complete, authenticated-by-session field-kit fact set. */
    private static void restoreUniversalWorkstationFacts(
            Map<String, String> parent,
            Map<String, String> child,
            String workstation,
            String expectedSession) {
        String session = parent.getOrDefault(
                universalWorkstationFactKey(workstation, "session"), "");
        String position = parent.getOrDefault(
                universalWorkstationFactKey(workstation, "position"), "");
        String dimension = parent.getOrDefault(
                universalWorkstationFactKey(workstation, "dimension"), "");
        if (!session.equals(expectedSession) || position.isBlank() || dimension.isBlank()) return;
        child.put("ownedWorkstationPosition", position);
        child.put("ownedWorkstationDimension", dimension);
    }

    /**
     * Carries a retained owned placement into the program parent, or removes a
     * stale placement after the final verified consumer reclaimed it.
     */
    private static void checkpointUniversalWorkstationFacts(
            Map<String, String> parent,
            PlanFrame child) {
        if (Boolean.parseBoolean(
                child.parameters().getOrDefault("homePinnedStation", "false"))) return;
        String workstation = child.parameters().getOrDefault("workstation", "");
        if (workstation.isBlank()) return;
        if (retainWorkstation(child)) {
            captureUniversalWorkstationFacts(parent, child);
            return;
        }
        clearUniversalWorkstationFacts(parent, workstation);
    }

    /** Captures an in-flight placement across death even when this is its final consumer. */
    private static void captureUniversalWorkstationFacts(
            Map<String, String> parent,
            PlanFrame child) {
        if (Boolean.parseBoolean(
                child.parameters().getOrDefault("homePinnedStation", "false"))) return;
        String workstation = child.parameters().getOrDefault("workstation", "");
        if (workstation.isBlank()) return;
        String session = child.parameters().getOrDefault("workstationSession", "");
        String position = child.parameters().getOrDefault("ownedWorkstationPosition", "");
        String dimension = child.parameters().getOrDefault("ownedWorkstationDimension", "");
        if (session.isBlank() || position.isBlank() || dimension.isBlank()) return;
        parent.put(universalWorkstationFactKey(workstation, "session"), session);
        parent.put(universalWorkstationFactKey(workstation, "position"), position);
        parent.put(universalWorkstationFactKey(workstation, "dimension"), dimension);
    }

    private static void clearUniversalWorkstationFacts(
            Map<String, String> parent,
            String workstation) {
        if (workstation == null || workstation.isBlank()) return;
        parent.remove(universalWorkstationFactKey(workstation, "session"));
        parent.remove(universalWorkstationFactKey(workstation, "position"));
        parent.remove(universalWorkstationFactKey(workstation, "dimension"));
    }

    private static boolean isCommittedUniversalAction(PlanFrame frame) {
        return UNIVERSAL_PROGRAM_COMMITTED_ACTION.equals(
                frame.parameters().get(UNIVERSAL_PROGRAM_ROLE_KEY))
                && frame.parameters().containsKey(UNIVERSAL_PROGRAM_ACTION_TOKEN_KEY);
    }

    private static String workstationOperationId(PlanFrame frame) {
        String shared = frame.parameters().getOrDefault("workstationSession", "").trim();
        return shared.isEmpty() ? frame.id() : shared;
    }

    private ResourceCatalog.RecipeMetadata plannedRecipeMetadata(PlanFrame frame) {
        String recipeId = frame.parameters().getOrDefault("recipeId", "").trim();
        if (recipeId.isEmpty()) return ResourceCatalog.RecipeMetadata.unknown();
        return resourcePlanner.catalog().recipeVariantsExact(frame.target()).stream()
                .map(ResourceCatalog.RecipeVariant::metadata)
                .filter(metadata -> recipeId.equals(metadata.recipeId()))
                .findFirst()
                .orElse(ResourceCatalog.RecipeMetadata.unknown());
    }

    private static String minecraftItemId(String item) {
        String normalized = item.trim().toLowerCase(Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private void completeLeaf(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            String detail,
            long nowMillis) throws IOException {
        if (frame.kind().equals("mine")) {
            baritone.finishFunctionalMiningRoute(nowMillis);
        }
        clearCompletedLeafRuntimeState(planId, frame, lease, detail);
        if (isCommittedUniversalAction(frame)) {
            advanceCommittedUniversalAction(planId, frame, detail, nowMillis);
        } else {
            plans.completeCurrent(planId, detail, nowMillis);
        }
    }

    private void clearCompletedLeafRuntimeState(
            String planId,
            PlanFrame frame,
            ControlLease lease,
            String detail) {
        String actionToken = frame.parameters().get(UNIVERSAL_PROGRAM_ACTION_TOKEN_KEY);
        if (actionToken != null) committedFurnaceFuelGrants.remove(actionToken);
        baritone.cancel(lease.epoch(), detail);
        inventory.closeHandledScreen();
        deliveries.clear(frame.id());
        actionSupervisor.clear(frame.id());
        String workstationOperation = workstationOperationId(frame);
        if (!retainWorkstation(frame) || workstationOperation.equals(frame.id())) {
            workstations.clear(workstationOperation);
        }
        groundItems.clear(lease.epoch(), detail);
        miningSessions.remove(miningSessionKey(planId, frame));
    }

    /** Atomically advances the durable cursor in the same save that pops the child. */
    private void advanceCommittedUniversalAction(
            String planId,
            PlanFrame frame,
            String detail,
            long nowMillis) throws IOException {
        TaskPlan plan = plans.restore(planId)
                .filter(candidate -> candidate.state() == TaskPlanState.OPEN)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing open task plan " + planId));
        List<PlanFrame> frames = plan.frames();
        if (frames.size() < 2 || !frames.getLast().id().equals(frame.id())) {
            throw new IllegalStateException("stale universal child completion was rejected");
        }
        PlanFrame parent = frames.get(frames.size() - 2);
        String encoded = parent.parameters().get(UNIVERSAL_PROGRAM_KEY);
        if (encoded == null) {
            throw new IllegalStateException("committed universal child has no parent program");
        }
        Program program = UniversalProgramCodec.decode(encoded);
        String suppliedToken = required(
                frame.parameters(), UNIVERSAL_PROGRAM_ACTION_TOKEN_KEY);
        UniversalProgramCommitment.Decision decision = program.observe(
                Observation.verifiedComplete(suppliedToken, detail));
        if (decision.disposition() != UniversalProgramCommitment.Disposition.ADVANCED
                && decision.disposition() != UniversalProgramCommitment.Disposition.COMPLETED) {
            throw new IllegalStateException(
                    "universal child completion did not advance its exact action: "
                            + decision.detail());
        }
        LinkedHashMap<String, String> parentCheckpoint =
                new LinkedHashMap<>(parent.parameters());
        checkpointUniversalWorkstationFacts(parentCheckpoint, frame);
        parentCheckpoint.put(
                UNIVERSAL_PROGRAM_KEY,
                UniversalProgramCodec.encode(decision.program()));
        plans.completeCurrentAndCheckpointParent(
                planId,
                frame.id(),
                parentCheckpoint,
                detail,
                decision.detail(),
                nowMillis);
    }

    private Outcome observeLeaf(
            PlanFrame frame,
            Outcome outcome,
            ControlLease lease,
            long nowMillis,
            ActionSupervisor.Policy policy) {
        if (outcome.state() == State.BLOCKED || outcome.state() == State.COMPLETE) {
            actionSupervisor.clear(frame.id());
            return outcome;
        }
        if (outcome.state() != State.RUNNING && outcome.state() != State.RETRY) return outcome;

        boolean construction = frame.kind().equals("build");
        String phase = actionPhase(frame.kind(), outcome.detail());
        long evidence = construction ? outcome.completedWorkUnits() : clientStateFingerprint();
        ActionSupervisor.Observation observation = outcome.state() == State.RETRY
                ? ActionSupervisor.Observation.failure(
                        nowMillis,
                        phase,
                        evidence,
                        failureCode(frame.kind(), outcome.detail()),
                        outcome.detail())
                : ActionSupervisor.Observation.running(
                        nowMillis, phase, evidence, outcome.detail());
        ActionSupervisor.Decision decision = construction
                ? actionSupervisor.observeBuild(frame.id(), observation, policy,
                        client != null && client.player != null
                                ? new dev.entity.core.progress.ProgressMonitor.Position(
                                        client.player.getX(), client.player.getY(), client.player.getZ())
                                : null)
                : actionSupervisor.observe(frame.id(), observation, policy);
        return switch (decision.state()) {
            case CONTINUE, PROGRESS -> {
                Outcome running = outcome.state() == State.RETRY
                        ? Outcome.running("recovering " + frame.kind() + ": " + outcome.detail(),
                                Double.NaN, outcome.completedWorkUnits(), false)
                        : outcome;
                yield construction ? running.withLocalBuildStallOwner() : running;
            }
            case RECOVER -> {
                // A timeout is a diagnosis request, not proof that cancelling
                // this same native job changes its prerequisites or approach.
                // tickBlueprintLeaf may hand an observed shortage to supply;
                // otherwise the normal BLOCKED boundary fences all actuators.
                if (construction) {
                    yield Outcome.blocked("Construction stalled without new verified work; "
                            + decision.detail(), BaritonePort.FailureCause.CONSTRUCTION_STALLED);
                }
                if (frame.kind().equals("mine")
                        && baritone.recoverMiningInPlace(lease.epoch(), nowMillis)) {
                    yield Outcome.running("retaining mining search; trying different access: "
                            + decision.detail(), Double.NaN, outcome.completedWorkUnits(), false);
                }
                // Never turn a transient cursor/handler acknowledgement race
                // into cargo loss. The open furnace is part of this smelt
                // transaction when one of its machine slots (or the cursor)
                // still carries the expected input, fuel, or output. Keep the
                // exact GUI alive and let ActionSupervisor retry in place; a
                // persistent fault remains bounded and becomes BLOCKED without
                // rejecting the loaded station or placing a fresh furnace.
                if (FurnaceTransactionRecoveryPolicy.preserveOpenTransaction(
                        frame.kind(),
                        failureCode(frame.kind(), outcome.detail()),
                        furnaceTransactionSnapshot(frame))) {
                    yield Outcome.running(
                            "preserving loaded furnace transaction during recovery: "
                                    + decision.detail(),
                            Double.NaN,
                            outcome.completedWorkUnits(),
                            false);
                }
                // Nested workstation routes use IDs such as
                // `<frame>:reclaim:<position>`, so exact frame-ID cancellation
                // silently left a CANCELED adapter operation alive. This leaf
                // owns the live mission lease; invalidate whichever Baritone
                // sub-operation it currently owns before local recovery.
                baritone.cancel(lease.epoch(), decision.detail());
                inventory.closeHandledScreen();
                deliveries.clear(frame.id());
                workstations.recover(workstationOperationId(frame), decision.detail());
                groundItems.clear(lease.epoch(), decision.detail());
                yield Outcome.running(
                        "recovery " + decision.totalRecoveryAttempt() + " for " + frame.kind()
                                + ": " + decision.detail(),
                        Double.NaN,
                        outcome.completedWorkUnits(),
                        false);
            }
            case BLOCKED -> construction
                    ? Outcome.blocked("Construction stall episode exhausted; " + decision.detail(),
                            BaritonePort.FailureCause.CONSTRUCTION_RECOVERY_EXHAUSTED)
                    : Outcome.blocked(decision.detail());
            case SUCCEEDED -> outcome;
        };
    }

    private FurnaceTransactionRecoveryPolicy.Snapshot furnaceTransactionSnapshot(
            PlanFrame frame) {
        if (client.player == null
                || !(client.player.currentScreenHandler
                instanceof net.minecraft.screen.AbstractFurnaceScreenHandler handler)) {
            return FurnaceTransactionRecoveryPolicy.Snapshot.closed();
        }

        Set<String> expectedInputs = parseCounts(
                frame.parameters().getOrDefault("ingredients", ""))
                .keySet().stream()
                .map(resourcePlanner::normalizeItem)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> expectedFuels = FUEL_ITEMS.stream()
                .map(resourcePlanner::normalizeItem)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> expectedOutputs;
        if (frame.parameters().containsKey("foodCookingSession")) {
            try {
                expectedOutputs = FoodCookingSessionPolicy.decode(
                                frame.parameters().get("foodCookingSession"))
                        .entries().stream()
                        .map(FoodCookingSessionPolicy.Entry::readyItem)
                        .map(resourcePlanner::normalizeItem)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            } catch (IllegalArgumentException ignored) {
                expectedOutputs = Set.of();
            }
        } else {
            expectedOutputs = Set.of(resourcePlanner.normalizeItem(frame.target()));
        }

        boolean inputPresent = furnaceSlotMatches(handler, 0, expectedInputs);
        boolean fuelPresent = furnaceSlotMatches(handler, 1, expectedFuels);
        boolean outputPresent = furnaceSlotMatches(handler, 2, expectedOutputs);
        ItemStack cursor = handler.getCursorStack();
        boolean cursorPresent = !cursor.isEmpty()
                && (expectedInputs.contains(stackItem(cursor))
                || expectedFuels.contains(stackItem(cursor))
                || expectedOutputs.contains(stackItem(cursor)));
        return new FurnaceTransactionRecoveryPolicy.Snapshot(
                true, inputPresent, fuelPresent, outputPresent, cursorPresent);
    }

    private boolean furnaceSlotMatches(
            net.minecraft.screen.AbstractFurnaceScreenHandler handler,
            int slot,
            Set<String> expectedItems) {
        if (slot < 0 || slot >= handler.slots.size() || expectedItems.isEmpty()) return false;
        ItemStack stack = handler.getSlot(slot).getStack();
        return !stack.isEmpty() && expectedItems.contains(stackItem(stack));
    }

    private String stackItem(ItemStack stack) {
        return resourcePlanner.normalizeItem(Registries.ITEM.getId(stack.getItem()).toString());
    }

    private static String actionPhase(String kind, String detail) {
        String text = Objects.requireNonNullElse(detail, "").toLowerCase(Locale.ROOT);
        String phase = switch (kind.toLowerCase(Locale.ROOT)) {
            case "craft" -> contains(text, "table", "surface", "aim", "open") ? "workstation" : "crafting";
            case "smelt" -> contains(text, "furnace", "surface", "aim", "open") ? "workstation" : "smelting";
            case "equip" -> "equipping";
            case "deliver" -> contains(text, "follow", "reach", "moving") ? "approach" : "handoff";
            default -> kind.toLowerCase(Locale.ROOT);
        };
        return kind.toLowerCase(Locale.ROOT) + ':' + phase;
    }

    private static String failureCode(String kind, String detail) {
        String text = Objects.requireNonNullElse(detail, "").toLowerCase(Locale.ROOT);
        if (text.contains("slot")) return "slot_unavailable";
        if (text.contains("cursor")) return "cursor_occupied";
        if (text.contains("line of sight") || text.contains("visible")) return "no_sightline";
        if (text.contains("surface") || text.contains("plac")) return "placement_rejected";
        if (text.contains("recipe")) return "recipe_unavailable";
        if (text.contains("furnace")) return "furnace_transaction";
        return kind.toLowerCase(Locale.ROOT) + "_retry";
    }

    private long clientStateFingerprint() {
        if (client.player == null) return 0L;
        long hash = 17L;
        hash = 31L * hash + client.player.getBlockPos().asLong();
        hash = 31L * hash + inventory.counts().hashCode();
        if (client.interactionManager != null && client.interactionManager.isBreakingBlock()) {
            hash = 31L * hash + client.interactionManager.getBlockBreakingProgress();
            if (client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit) {
                hash = 31L * hash + hit.getBlockPos().asLong();
            }
        }
        var handler = client.player.currentScreenHandler;
        hash = 31L * hash + handler.syncId;
        hash = 31L * hash + handler.getClass().getName().hashCode();
        for (var slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                hash = 31L * hash;
            } else {
                hash = 31L * hash + Registries.ITEM.getId(stack.getItem()).hashCode();
                hash = 31L * hash + stack.getCount();
            }
        }
        ItemStack cursor = handler.getCursorStack();
        if (!cursor.isEmpty()) {
            hash = 31L * hash + Registries.ITEM.getId(cursor.getItem()).hashCode();
            hash = 31L * hash + cursor.getCount();
        }
        if (handler instanceof net.minecraft.screen.AbstractFurnaceScreenHandler furnace) {
            hash = 31L * hash + Float.floatToIntBits(furnace.getCookProgress());
            hash = 31L * hash + Float.floatToIntBits(furnace.getFuelProgress());
            hash = 31L * hash + (furnace.isBurning() ? 1 : 0);
        }
        return hash;
    }

    private void ensureMissionPlan(Mission mission, long nowMillis) throws IOException {
        if (plans.restore(mission.id()).isPresent()) return;
        String kind = mission.kind().toLowerCase(Locale.ROOT);
        String encodedBatch = mission.parameters().getOrDefault("batchItems", "");
        PlanFrame.Spec root;
        if (!encodedBatch.isBlank()) {
            List<BatchItem> items = parseBatchItems(encodedBatch);
            if (!kind.equals("get") && !kind.equals("give") && !kind.equals("bring")) {
                throw new IllegalArgumentException("Unsupported batch mission " + kind);
            }
            LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
            parameters.put("batchItems", encodedBatch);
            parameters.put("description", "execute " + items.size() + " requested item objectives");
            parameters.putAll(missionRootParameters(mission, Map.of()));
            if (kind.equals("give") || kind.equals("bring")) {
                parameters.put("player", missionPlayer(mission));
            }
            root = new PlanFrame.Spec(
                    "root.batch." + (kind.equals("get") ? "acquire" : kind),
                    "items",
                    items.size(),
                    parameters);
        } else root = switch (kind) {
            case "get" -> new PlanFrame.Spec(
                    FoodFamilyPolicy.isFamilyRequest(missionItem(mission))
                            ? "root.food.acquire" : "root.acquire",
                    missionItem(mission),
                    missionCount(mission),
                    missionRootParameters(
                            mission, Map.of("description", "obtain requested inventory")));
            case "give" -> new PlanFrame.Spec(
                    FoodFamilyPolicy.isFamilyRequest(missionItem(mission))
                            ? "root.food.give" : "root.give",
                    missionItem(mission),
                    giveMissionCount(mission),
                    missionRootParameters(mission, Map.of(
                            "player", missionPlayer(mission),
                            "description", "give existing inventory")));
            case "bring" -> new PlanFrame.Spec(
                    FoodFamilyPolicy.isFamilyRequest(missionItem(mission))
                            ? "root.food.bring" : "root.bring",
                    missionItem(mission),
                    missionCount(mission),
                    missionRootParameters(mission, Map.of(
                            "player", missionPlayer(mission),
                            "description", "obtain and deliver inventory")));
            case "gear" -> new PlanFrame.Spec(
                    "root.gear",
                    mission.parameters().getOrDefault("tier", "iron"),
                    1,
                    Map.of("description", "build and equip a survival loadout"));
            case "mine" -> new PlanFrame.Spec(
                    "root.legacy_mine",
                    mission.parameters().getOrDefault("blocks", missionItem(mission)),
                    missionCount(mission),
                    merge(mission.parameters(), Map.of(
                            "blocks", mission.parameters().getOrDefault("blocks", missionItem(mission)),
                            "description", "self-provision tools, then mine")));
            case "build" -> new PlanFrame.Spec("root.build",required(mission.parameters(),"projectId"),1,
                    mission.parameters());
            case "farm" -> new PlanFrame.Spec(
                    "root.farm",
                    required(mission.parameters(), "area"),
                    1,
                    Map.of(
                            "area", required(mission.parameters(), "area"),
                            "areaFingerprint", required(
                                    mission.parameters(), "areaFingerprint"),
                            "description", "run one finite pass over the named managed farm"));
            default -> throw new IllegalArgumentException("Unsupported autonomous mission " + kind);
        };
        if (kind.equals("give") || kind.equals("bring")) {
            // Immutable order admission fact: persist before Home/crafting can add a
            // better spare. Root intent survives restart and execution-generation rebase.
            String workingKit = DeliverySuppliesOrder.encodeWorkingKit(DeliverySuppliesOrder.captureWorkingKit(
                    deliveryAllocation(mission.id()), inventory.personalSupplies().workingKitComponents()));
            root = new PlanFrame.Spec(root.kind(), root.target(), root.count(),
                    merge(root.parameters(), Map.of(DeliverySuppliesOrder.WORKING_KIT_KEY, workingKit)));
        }
        plans.createOrRestore(mission.id(), root, nowMillis);
    }

    private static Map<String, String> missionRootParameters(
            Mission mission,
            Map<String, String> base) {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>(base);
        for (String key : List.of(
                "useReserves",
                "homeMaintenance", "homeGeneration", "homeFingerprint",
                "homeDimension", "homeX", "homeY", "homeZ",
                "homeCraftingTableLedgerId", "homeFurnaceLedgerId",
                "homeChestLedgerId", "homeBedLedgerId", HOME_UNPINNED_ASSET_ROLES)) {
            String value = mission.parameters().getOrDefault(key, "").trim();
            if (!value.isEmpty()) parameters.put(key, value);
        }
        return Map.copyOf(parameters);
    }

    private static boolean isHomeMaintenance(PlanFrame frame) {
        return Boolean.parseBoolean(
                frame.parameters().getOrDefault("homeMaintenance", "false"));
    }

    private static boolean hasHomeMissionSupplyPinAuthority(PlanFrame frame) {
        return Boolean.parseBoolean(frame.parameters().getOrDefault(
                HOME_MISSION_SUPPLY_PIN_AUTHORITY, "false"));
    }

    private static boolean usesPinnedHomeStation(PlanFrame frame, String workstation) {
        String station = simple(workstation);
        if (!station.equals("crafting_table") && !station.equals("furnace")) return false;
        if (!isHomeMaintenance(frame) && !hasHomeMissionSupplyPinAuthority(frame)) return false;
        String ledgerKey = station.equals("crafting_table")
                ? "homeCraftingTableLedgerId" : "homeFurnaceLedgerId";
        return !frame.parameters().getOrDefault(ledgerKey, "").isBlank();
    }

    private static String homeLedgerParameter(HomeEconomySession.AssetRole role) {
        return switch (role) {
            case CRAFTING_TABLE -> "homeCraftingTableLedgerId";
            case FURNACE -> "homeFurnaceLedgerId";
            case CHEST -> "homeChestLedgerId";
            case BED -> "homeBedLedgerId";
        };
    }

    private static void copyHomeBinding(
            Map<String, String> source,
            Map<String, String> destination) {
        for (String key : List.of(
                "homeMaintenance", "homeGeneration", "homeFingerprint",
                "homeDimension", "homeX", "homeY", "homeZ",
                "homeCraftingTableLedgerId", "homeFurnaceLedgerId",
                "homeChestLedgerId", "homeBedLedgerId", HOME_UNPINNED_ASSET_ROLES,
                HOME_MISSION_SUPPLY_PIN_AUTHORITY)) {
            String value = source.getOrDefault(key, "").trim();
            if (!value.isEmpty()) destination.put(key, value);
        }
    }

    private static String pinnedHomeWorkstationOperation(
            PlanFrame frame,
            String workstation) {
        long generation = longInteger(frame.parameters(), "homeGeneration", -1L);
        return "home:economy:" + generation + ":asset:" + simple(workstation);
    }

    /**
     * A Home-maintenance compilation may count only the exact, live pins bound
     * into its durable root. An ordinary field-kit placement or a same-kind
     * world block is deliberately insufficient.
     */
    private String homeMaintenanceBindingFailure(PlanFrame root) {
        if (!isHomeMaintenance(root) && !hasHomeMissionSupplyPinAuthority(root)) return "";
        HomeBinding binding = resolveHomeBinding(root.parameters());
        if (!binding.valid()) return binding.detail();
        for (HomeEconomySession.AssetRole role : HomeEconomySession.AssetRole.values()) {
            if (!isHomeMaintenance(root) && role != HomeEconomySession.AssetRole.CRAFTING_TABLE
                    && role != HomeEconomySession.AssetRole.FURNACE) continue;
            if (!binding.ledgerIds().containsKey(role)) continue;
            WorkstationController.HomeAssetDiagnostic diagnostic =
                    workstations.diagnosePinnedHomeAsset(
                            workstationKind(role), binding.ledgerIds().get(role),
                            binding.home(), HOME_ASSET_RADIUS);
            if (!homeAssetUsableByBoundPlan(diagnostic.truth())) {
                return "home_" + role.item() + "_not_verified: "
                        + diagnostic.truth().name().toLowerCase(Locale.ROOT)
                        + " (" + diagnostic.detail() + ')';
            }
        }
        return "";
    }

    /**
     * A durable exact pin remains usable while its chunk is unloaded or while
     * disposable terrain temporarily covers every interaction stand. The
     * pinned workstation executor must load or restore access before the first
     * real screen interaction; missing, replaced, or irrecoverably obstructed
     * blocks still fail closed here.
     */
    private static boolean homeAssetUsableByBoundPlan(
            WorkstationController.HomeAssetLiveTruth truth) {
        return truth == WorkstationController.HomeAssetLiveTruth.EXACT_VERIFIED
                || truth == WorkstationController.HomeAssetLiveTruth.ACCESS_RECOVERABLE
                || truth == WorkstationController.HomeAssetLiveTruth.UNLOADED;
    }

    private HomeBinding resolveHomeBinding(Map<String, String> parameters) {
        long generation = longInteger(parameters, "homeGeneration", -1L);
        String dimension = parameters.getOrDefault("homeDimension", "").trim();
        String fingerprint = parameters.getOrDefault("homeFingerprint", "").trim();
        if (generation <= 0L || dimension.isBlank() || fingerprint.isBlank()
                || !parameters.containsKey("homeX")
                || !parameters.containsKey("homeY")
                || !parameters.containsKey("homeZ")) {
            return HomeBinding.invalid("home_binding_incomplete: durable Home context is incomplete");
        }
        HomeEconomySession.HomeAnchor home;
        try {
            home = HomeEconomySession.HomeAnchor.at(
                    dimension,
                    Integer.parseInt(parameters.get("homeX")),
                    Integer.parseInt(parameters.get("homeY")),
                    Integer.parseInt(parameters.get("homeZ")));
        } catch (IllegalArgumentException error) {
            return HomeBinding.invalid("home_binding_invalid: " + error.getMessage());
        }
        if (!home.fingerprint().equals(fingerprint)) {
            return HomeBinding.invalid(
                    "home_binding_invalid: durable anchor fingerprint does not match its coordinates");
        }
        HomeEconomySession.Snapshot current = homeEconomy.snapshot();
        if (current.home() == null || current.generation() != generation
                || !current.home().equals(home)) {
            return HomeBinding.invalid(
                    "home_binding_changed: configured Home generation or anchor changed");
        }
        EnumMap<HomeEconomySession.AssetRole, String> expectedLedgerIds =
                new EnumMap<>(HomeEconomySession.AssetRole.class);
        for (HomeEconomySession.AssetRole role : HomeEconomySession.AssetRole.values()) {
            String expected = parameters.getOrDefault(homeLedgerParameter(role), "").trim();
            if (!expected.isBlank()) expectedLedgerIds.put(role, expected);
        }
        Set<HomeEconomySession.AssetRole> permittedUnpinned;
        try {
            permittedUnpinned = parseHomeAssetRoles(
                    parameters.getOrDefault(HOME_UNPINNED_ASSET_ROLES, ""));
        } catch (IllegalArgumentException error) {
            return HomeBinding.invalid("home_binding_invalid: " + error.getMessage());
        }
        HomeEconomyPolicy.PlanAssetBinding assets =
                HomeEconomyPolicy.validatePlanAssetBindings(
                        expectedLedgerIds, permittedUnpinned, current);
        if (!assets.valid()) return HomeBinding.invalid(assets.detail());
        return new HomeBinding(true, home, assets.ledgerIds(), "");
    }

    private static Set<HomeEconomySession.AssetRole> parseHomeAssetRoles(String encoded) {
        if (encoded == null || encoded.isBlank()) return Set.of();
        EnumSet<HomeEconomySession.AssetRole> roles =
                EnumSet.noneOf(HomeEconomySession.AssetRole.class);
        for (String token : encoded.split(",", -1)) {
            String value = token.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("empty unpinned Home asset role");
            }
            try {
                roles.add(HomeEconomySession.AssetRole.valueOf(value));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "unknown unpinned Home asset role " + value, error);
            }
        }
        return Set.copyOf(roles);
    }

    private ResourcePlanner.InventoryView pinnedHomePlanningInventoryView(
            ResourcePlanner.InventoryView base,
            PlanFrame root) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>(base.itemCounts());
        LinkedHashSet<String> availableStations = new LinkedHashSet<>(
                base.availableStations());
        for (String station : List.of("crafting_table", "furnace", "chest")) {
            counts.entrySet().removeIf(entry ->
                    resourcePlanner.normalizeItem(entry.getKey()).equals(station));
            int carried = inventory.count("minecraft:" + station);
            if (carried > 0) counts.put("minecraft:" + station, carried);
        }
        HomeBinding binding = resolveHomeBinding(root.parameters());
        if (binding.valid()) {
            for (HomeEconomySession.AssetRole role : List.of(
                    HomeEconomySession.AssetRole.CRAFTING_TABLE,
                    HomeEconomySession.AssetRole.FURNACE)) {
                if (!binding.ledgerIds().containsKey(role)) continue;
                WorkstationController.HomeAssetDiagnostic diagnostic =
                        workstations.diagnosePinnedHomeAsset(
                                workstationKind(role), binding.ledgerIds().get(role),
                                binding.home(), HOME_ASSET_RADIUS);
                if (homeAssetUsableByBoundPlan(diagnostic.truth())) {
                    availableStations.add("minecraft:" + role.item());
                }
            }
        }
        return new ResourcePlanner.InventoryView(
                Map.copyOf(counts), base.equippedItems(), base.locallyAvailableItems(),
                Set.copyOf(availableStations));
    }

    private record HomeBinding(
            boolean valid,
            HomeEconomySession.HomeAnchor home,
            Map<HomeEconomySession.AssetRole, String> ledgerIds,
            String detail) {
        private HomeBinding {
            ledgerIds = Map.copyOf(Objects.requireNonNull(ledgerIds, "ledgerIds"));
            detail = Objects.requireNonNullElse(detail, "");
        }

        private static HomeBinding invalid(String detail) {
            return new HomeBinding(false, null, Map.of(), detail);
        }
    }

    private ResourcePlanner.InventoryView inventoryView() {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        LinkedHashSet<String> availableStations = new LinkedHashSet<>();
        if (client.player != null) {
            var playerInventory = client.player.getInventory();
            for (int slot = 0; slot < playerInventory.size(); slot++) {
                ItemStack stack = playerInventory.getStack(slot);
                if (stack.isEmpty()) continue;
                String raw = Registries.ITEM.getId(stack.getItem()).toString();
                String simple = resourcePlanner.normalizeItem(raw);
                if (PICKAXE_RANKS.containsKey(simple) && remainingDurability(stack) <= 1) continue;
                counts.merge(raw, stack.getCount(), Integer::sum);
            }
        }
        // A placed/open workstation is a reusable capability, not a physical inventory item.
        // Injecting it into itemCounts could satisfy a final-item goal or let a recipe consume a
        // fake furnace into a smoker. Real carried stacks remain exclusively in counts.
        if (inventory.isCraftingTableOpen() || workstations.hasDurableFieldKitPlacement(
                WorkstationController.Kind.CRAFTING_TABLE)) {
            availableStations.add("minecraft:crafting_table");
        }
        if (inventory.isFurnaceOpen() || workstations.hasDurableFieldKitPlacement(
                WorkstationController.Kind.FURNACE)) {
            availableStations.add("minecraft:furnace");
        }
        if ((client.player != null
                && client.player.currentScreenHandler
                instanceof net.minecraft.screen.GenericContainerScreenHandler)
                || workstations.hasDurableFieldKitPlacement(
                WorkstationController.Kind.CHEST)) {
            availableStations.add("minecraft:chest");
        }
        return new ResourcePlanner.InventoryView(
                counts, equippedItems(), localResourceSources(), availableStations);
    }

    private ResourcePlanner.InventoryView planningInventoryView() {
        refreshEmergencyTechniqueReservationsAndSync();
        ResourcePlanner.InventoryView observed = inventoryView();
        return new ResourcePlanner.InventoryView(
                reservations.spendableCounts(
                        observed.itemCounts(),
                        Set.of(Purpose.PERSONAL_RATION, Purpose.EMERGENCY_TECHNIQUE)),
                observed.equippedItems(),
                observed.locallyAvailableItems(), observed.availableStations());
    }

    private ResourcePlanner.InventoryView planningInventoryViewForPlan(String planId) {
        Optional<TaskPlan> plan = plans.restore(planId)
                .filter(candidate -> candidate.state() == TaskPlanState.OPEN);
        ResourcePlanner.InventoryView base = plan.flatMap(AutonomyExecutor::foodObjectiveRoot).isPresent()
                ? foodPlanningInventoryView(planId, true)
                : planningInventoryView();
        if (plan.isPresent() && plan.orElseThrow().frames().stream().anyMatch(frame ->
                "true".equals(frame.parameters().get("personalMaintenance")))) {
            base = homeServiceablePlanningInventoryView(base);
            if (plan.flatMap(AutonomyExecutor::foodObjectiveRoot).isEmpty())
                base = personalMaintenancePlanningInventory(planId, base);
        }
        if (plan.isPresent() && !plan.orElseThrow().frames().isEmpty()) {
            base = EmergencyTechniqueReservationPolicy.includeRetainedGoalWater(base,
                    canonicalInventorySnapshot(), homeMissionSupplyGoals(plan.orElseThrow().frames().getFirst()), reservations);
        }
        Optional<PlanFrame> stationRoot = plan.flatMap(AutonomyExecutor::innermostHomeStationBinding);
        if (stationRoot.isPresent()) {
            return homeServiceablePlanningInventoryView(pinnedHomePlanningInventoryView(
                    base, stationRoot.orElseThrow()));
        }
        return new ResourcePlanner.InventoryView(
                base.itemCounts(), base.equippedItems(), base.locallyAvailableItems(),
                base.availableStations());
    }

    private ResourcePlanner.InventoryView personalMaintenancePlanningInventory(String planId,
            ResourcePlanner.InventoryView base) {
        var protectedItems = plans.restore(planId).stream().flatMap(plan -> plan.frames().stream())
                .filter(frame -> "true".equals(frame.parameters().get("personalMaintenance")))
                .findFirst().map(frame -> frame.parameters().getOrDefault("personalMaintenanceProtectedItems", ""))
                .filter(encoded -> !encoded.isBlank())
                .map(dev.entity.client.blueprint.BlueprintMaterialSupplyPolicy::decode).orElse(Map.of());
        var spendable = PersonalSuppliesPolicy.maintenanceSpendable(base.itemCounts(), protectedItems);
        return new ResourcePlanner.InventoryView(spendable, base.equippedItems(),
                base.locallyAvailableItems(), base.availableStations());
    }

    private static Optional<PlanFrame> innermostHomeStationBinding(TaskPlan plan) {
        return plan.frames().reversed().stream()
                .filter(frame -> isHomeMaintenance(frame) || hasHomeMissionSupplyPinAuthority(frame))
                .findFirst();
    }

    /** Worn maintenance tools cannot satisfy the plan that replaces them. */
    private ResourcePlanner.InventoryView homeServiceablePlanningInventoryView(ResourcePlanner.InventoryView base) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>(base.itemCounts());
        Map<String, Integer> serviceable = inventory.homePlayerCounts();
        Set<String> toolItems = HomeStockPolicy.defaults().stream()
                .filter(target -> Set.of("pickaxe", "axe", "weapon", "shield").contains(target.id()))
                .flatMap(target -> target.acceptedItems().stream()).collect(java.util.stream.Collectors.toSet());
        counts.replaceAll((raw, count) -> toolItems.contains(resourcePlanner.normalizeItem(raw))
                ? Math.min(count, serviceable.getOrDefault(raw,
                        serviceable.getOrDefault("minecraft:" + resourcePlanner.normalizeItem(raw), 0)))
                : count);
        counts.entrySet().removeIf(entry -> entry.getValue() <= 0);
        return new ResourcePlanner.InventoryView(Map.copyOf(counts), base.equippedItems(),
                base.locallyAvailableItems(), base.availableStations());
    }

    /** Exact aggregate remaining durability for universal plan costing. */
    private Map<String, Integer> usablePickaxeDurability() {
        if (client.player == null) return Map.of();
        LinkedHashMap<String, Integer> usable = new LinkedHashMap<>();
        var playerInventory = client.player.getInventory();
        for (int slot = 0; slot < playerInventory.size(); slot++) {
            ItemStack stack = playerInventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String item = resourcePlanner.normalizeItem(
                    Registries.ITEM.getId(stack.getItem()).toString());
            if (!PICKAXE_RANKS.containsKey(item)) continue;
            int remaining = remainingDurability(stack);
            // Match inventoryView's physical serviceability boundary exactly. A one-point
            // stack is deliberately retained in the real inventory but is neither countable nor
            // spendable by a universal mining program.
            int work = MiningToolSegmentPolicy.usableWorkDurability(remaining);
            if (work > 0) usable.merge(item, work, Math::addExact);
        }
        return Map.copyOf(usable);
    }

    /** Exact live stack distribution used only for physical EQUIP/segment boundaries. */
    private List<Integer> exactPickaxeDurabilities(String rawItem) {
        if (client.player == null) return List.of();
        String wanted = simple(rawItem);
        ArrayList<Integer> result = new ArrayList<>();
        var playerInventory = client.player.getInventory();
        for (int slot = 0; slot < playerInventory.size(); slot++) {
            ItemStack stack = playerInventory.getStack(slot);
            if (stack.isEmpty()
                    || !simple(Registries.ITEM.getId(stack.getItem()).toString()).equals(wanted)) {
                continue;
            }
            result.add(Math.max(0, remainingDurability(stack)));
        }
        return List.copyOf(result);
    }

    /**
     * Food may spend only cargo reservations explicitly allocated to the food
     * family. Raw/fuel cargo remains protected. For an absolute acquisition
     * root, physically ready food counts toward its output target even when
     * retained for personal eating; delivery selection still honors that claim.
     */
    private ResourcePlanner.InventoryView foodPlanningInventoryView(
            String planId,
            boolean synthesizeCoalBlockCapacity) {
        refreshEmergencyTechniqueReservationsAndSync();
        ResourcePlanner.InventoryView observed = inventoryView();
        Optional<String> batchObjectiveIndex = plans.restore(planId)
                .filter(candidate -> candidate.state() == TaskPlanState.OPEN)
                .flatMap(AutonomyExecutor::foodObjectiveRoot)
                .map(frame -> frame.parameters().getOrDefault("batchObjectiveIndex", ""))
                .filter(index -> !index.isBlank());
        Set<String> foodReservations = new LinkedHashSet<>(reservations.reservations().values().stream()
                .filter(reservation -> reservation.purpose() == Purpose.MISSION_CARGO)
                .map(InventoryReservationLedger.Reservation::id)
                .filter(id -> isFoodCargoReservationForObjective(
                        id, batchObjectiveIndex.orElse("")))
                .collect(java.util.stream.Collectors.toSet()));
        if (plans.restore(planId).map(AutonomyExecutor::foodAcquisitionOwnsRation).orElse(false)) {
            // This same food session may cook its own raw ration. Survival
            // preempts before the next recipe click when hunger requires it;
            // retaining an uneaten raw ration must not invent extra hunting.
            foodReservations.add(planId + ":ration");
        }
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>(
                reservations.spendableCountsExceptReservations(
                        observed.itemCounts(),
                        Set.of(
                                Purpose.PERSONAL_RATION,
                                Purpose.MISSION_CARGO,
                                Purpose.EMERGENCY_TECHNIQUE),
                        foodReservations));
        if (plans.restore(planId).stream().flatMap(plan -> plan.frames().stream()).anyMatch(frame ->
                "true".equals(frame.parameters().get("personalMaintenance")))) {
            // Personal cooking is not the parent's requested food output. In
            // particular its fuel/ingredients may not spend a suspended recipe.
            var personal = new ResourcePlanner.InventoryView(personalFoodInputs(observed.itemCounts(), reservations),
                    observed.equippedItems(),
                    observed.locallyAvailableItems(), observed.availableStations());
            counts = new LinkedHashMap<>(personalMaintenancePlanningInventory(planId, personal).itemCounts());
        }
        Optional<PlanFrame> foodRoot = plans.restore(planId).flatMap(AutonomyExecutor::foodObjectiveRoot);
        if (foodRoot.isPresent() && absoluteFoodAcquisition(foodRoot.orElseThrow()))
            counts = new LinkedHashMap<>(FoodFamilyPolicy.withObservedReadyFood(counts, observed.itemCounts()));
        if (synthesizeCoalBlockCapacity) {
            int blocks = normalizedInventoryCount(counts, "coal_block");
            if (blocks > 0) {
                counts.merge(
                        "minecraft:coal",
                        Math.multiplyExact(
                                blocks,
                                COAL_BLOCK_FUEL_OPERATIONS / COAL_FUEL_OPERATIONS),
                        Math::addExact);
            }
        }
        ResourcePlanner.InventoryView base = new ResourcePlanner.InventoryView(
                counts, observed.equippedItems(), observed.locallyAvailableItems(),
                observed.availableStations());
        Optional<PlanFrame> root = plans.restore(planId)
                .filter(candidate -> candidate.state() == TaskPlanState.OPEN)
                .filter(candidate -> !candidate.frames().isEmpty())
                .map(candidate -> candidate.frames().getFirst());
        if (root.isPresent() && (isHomeMaintenance(root.orElseThrow())
                || hasHomeMissionSupplyPinAuthority(root.orElseThrow()))) {
            return pinnedHomePlanningInventoryView(base, root.orElseThrow());
        }
        return base;
    }

    static Map<String, Integer> personalFoodInputs(Map<String, Integer> observed,
            InventoryReservationLedger reservations) {
        // Raw meat can itself be the retained ration. Its own upkeep may cook
        // that share; the caller still subtracts the suspended parent's cargo
        // and recipe snapshot before planning, and emergency claims stay fenced.
        return reservations.spendableCounts(observed, Set.of(Purpose.EMERGENCY_TECHNIQUE));
    }

    static boolean absoluteFoodAcquisition(PlanFrame frame) {
        // Gather-first sibling quantities keep their existing disjoint cargo accounting.
        return frame.kind().equals("root.food.acquire")
                && !frame.parameters().containsKey("batchObjectiveIndex");
    }

    /**
     * Returns the absolute coal inventory target needed to leave a food
     * operation with its fuel after every non-food reservation is honored.
     *
     * <p>The reservation ledger intentionally records future cargo even before
     * those items exist. A child mining leaf, however, proves progress from the
     * real inventory. Passing only the spendable deficit therefore caused one
     * ore to complete the child while a future cargo reservation immediately
     * absorbed that ore, spawning another identical MineProcess. Convert the
     * virtual spendable requirement back into one real-inventory target at this
     * boundary.</p>
     */
    private int foodCoalAcquisitionTarget(
            String planId,
            int requiredRegularFuel,
            Map<String, Integer> spendableInventory) {
        int protectedCoal = protectedFoodPlanningReservationCount(planId, "coal");
        int spendableCharcoal = normalizedInventoryCount(spendableInventory, "charcoal");
        return requiredTotalCoalForFoodFuel(
                protectedCoal, spendableCharcoal, requiredRegularFuel);
    }

    static int requiredTotalCoalForFoodFuel(
            int protectedCoal,
            int spendableCharcoal,
            int requiredRegularFuel) {
        if (protectedCoal < 0 || spendableCharcoal < 0 || requiredRegularFuel < 0) {
            throw new IllegalArgumentException("food fuel counts cannot be negative");
        }
        return Math.addExact(
                protectedCoal,
                Math.max(0, requiredRegularFuel - spendableCharcoal));
    }

    private int protectedFoodPlanningReservationCount(String planId, String rawItem) {
        String item = InventoryReservationLedger.normalizeItem(rawItem);
        Optional<TaskPlan> activePlan = plans.restore(planId)
                .filter(candidate -> candidate.state() == TaskPlanState.OPEN);
        Optional<String> batchObjectiveIndex = activePlan
                .filter(candidate -> candidate.state() == TaskPlanState.OPEN)
                .flatMap(AutonomyExecutor::foodObjectiveRoot)
                .map(frame -> frame.parameters().getOrDefault("batchObjectiveIndex", ""))
                .filter(index -> !index.isBlank());
        int protectedCount = 0;
        for (InventoryReservationLedger.Reservation reservation
                : reservations.reservations().values()) {
            if (reservation.purpose() != Purpose.PERSONAL_RATION
                    && reservation.purpose() != Purpose.MISSION_CARGO) {
                continue;
            }
            if (!reservation.item().equals(item)) continue;
            if (isFoodCargoReservationForObjective(
                    reservation.id(), batchObjectiveIndex.orElse(""))) {
                continue;
            }
            protectedCount = Math.addExact(protectedCount, reservation.count());
        }
        int futureBatchTarget = 0;
        if (activePlan.isPresent() && batchObjectiveIndex.isPresent()
                && !activePlan.orElseThrow().frames().isEmpty()) {
            PlanFrame root = activePlan.orElseThrow().frames().getFirst();
            if (root.kind().startsWith("root.batch.")) {
                int currentIndex = Integer.parseInt(batchObjectiveIndex.orElseThrow());
                futureBatchTarget = otherBatchOutstandingTarget(
                        required(root.parameters(), "batchItems"),
                        root.parameters(), currentIndex, rawItem);
            }
        }
        // appendCargoReservations can reserve only items that already exist,
        // while the durable batch root knows the full future objective. The
        // larger value is the actual inventory boundary food fuel must clear.
        return Math.max(protectedCount, futureBatchTarget);
    }

    static int otherBatchOutstandingTarget(
            String encodedItems,
            Map<String, String> durableCheckpoint,
            int currentObjectiveIndex,
            String rawItem) {
        List<BatchItem> items = parseBatchItems(encodedItems);
        if (currentObjectiveIndex < 0 || currentObjectiveIndex >= items.size()) {
            throw new IllegalArgumentException("current batch objective index is out of range");
        }
        GatherFirstBatchPolicy.Decision decision = GatherFirstBatchPolicy.evaluate(
                encodedItems,
                durableCheckpoint,
                java.util.Collections.nCopies(items.size(), 0),
                true,
                GatherFirstBatchPolicy.Mode.ACQUIRE_THEN_DELIVER);
        if (decision.action() == GatherFirstBatchPolicy.Action.CORRUPT) {
            throw new IllegalArgumentException(
                    "invalid batch checkpoint while protecting food fuel: " + decision.reason());
        }
        String wanted = simple(rawItem);
        int outstanding = 0;
        for (int index = 0; index < items.size(); index++) {
            if (index == currentObjectiveIndex
                    || !simple(items.get(index).item()).equals(wanted)) {
                continue;
            }
            outstanding = Math.addExact(
                    outstanding,
                    Math.max(0, items.get(index).count() - decision.delivered().get(index)));
        }
        return outstanding;
    }

    static boolean isFoodCargoReservationId(String reservationId) {
        if (reservationId == null) return false;
        return reservationId.contains(":food-ready:")
                || reservationId.contains(":food-raw:")
                || reservationId.contains(":food-source:");
    }

    static boolean isFoodCargoReservationForObjective(
            String reservationId,
            String batchObjectiveIndex) {
        if (!isFoodCargoReservationId(reservationId)) return false;
        if (batchObjectiveIndex == null || batchObjectiveIndex.isBlank()) return true;
        return reservationId.contains(
                ":cargo:" + batchObjectiveIndex.trim() + ":food-");
    }

    private void refreshMissionReservations(Mission mission) {
        refreshMissionReservations(mission, "active-mission");
    }

    private void refreshMissionReservations(Mission mission, String owner) {
        Objects.requireNonNull(owner, "owner");
        refreshEmergencyTechniqueReservations();
        if (mission == null || mission.state().terminal()) {
            reservations.releaseOwner(owner);
            syncBaritoneThrowawayReservations();
            return;
        }
        String kind = mission.kind().toLowerCase(Locale.ROOT);
        Optional<TaskPlan> plan = activeReservationPlan(mission);
        BatchReservationPolicy.Decision batch = batchReservation(mission, plan);
        if (batch.state() == BatchReservationPolicy.State.CORRUPT) {
            failClosedBatchReservations(mission, owner, batch.reason());
            return;
        }
        String target = reservationTarget(mission, plan, batch);
        int objective = reservationObjective(mission, plan, target, batch);
        List<InventoryReservationLedger.Reservation> next = new java.util.ArrayList<>();

        if (plan.isPresent() && hasBatchRoot(plan.orElseThrow())) {
            if (!appendGatherFirstBatchCargo(
                    mission, plan.orElseThrow().frames().getFirst(), owner, next)) return;
        } else if ((kind.equals("get") || kind.equals("give") || kind.equals("bring"))
                && !target.isBlank()) {
            appendCargoReservations(
                    next, mission.id() + ":cargo", owner, target, objective,
                    "allocated " + kind + " cargo");
        }
        for (PlanCommitmentPolicy.Commitment commitment : PlanCommitmentPolicy.derive(
                projectedPlanActions(mission, plan, target, objective, batch))) {
            next.add(new InventoryReservationLedger.Reservation(
                    mission.id() + ":" + commitment.key(), owner, commitment.item(),
                    commitment.count(), commitment.purpose(), commitment.reason()));
        }
        var custody = inventory.transactionDiagnostics();
        boolean ownFoodRation = kind.equals("get") && FoodFamilyPolicy.isFamilyRequest(target)
                && !mission.parameters().containsKey("batchItems")
                && (plan.isEmpty() || foodAcquisitionOwnsRation(plan.orElseThrow()));
        if (ownFoodRation && foodRationCustodySettled(inventory.isPlayerInventoryOpen(), inventory.cursorEmpty(),
                custody.pendingClickOwner(), custody.cursorOwner())) {
            next = new ArrayList<>(foodAcquisitionRation(mission.id(), owner,
                    client.player.getHungerManager().getFoodLevel(), canonicalInventorySnapshot(), next));
        }
        if (next.stream().noneMatch(claim -> claim.purpose() == Purpose.PERSONAL_RATION)) {
            InventoryReservationLedger.Reservation ration = selectExistingMissionRation(mission, plan, next, owner);
            if (ration != null) next.add(ration);
        }
        reservations.replaceOwner(owner, next);
        syncBaritoneThrowawayReservations();
    }

    static boolean foodAcquisitionOwnsRation(TaskPlan plan) {
        return !plan.frames().isEmpty() && absoluteFoodAcquisition(plan.frames().getFirst())
                && FoodFamilyPolicy.isFamilyRequest(plan.frames().getFirst().target());
    }

    static boolean foodRationCustodySettled(boolean playerHandler, boolean cursorEmpty,
            String pendingClickOwner, String cursorOwner) {
        return playerHandler && cursorEmpty && pendingClickOwner.isBlank() && cursorOwner.isBlank();
    }

    private List<InventoryReservationLedger.Reservation> foodAcquisitionRation(String missionId, String owner,
            int hunger, Map<String, Integer> carried, List<InventoryReservationLedger.Reservation> owned) {
        var physical = new LinkedHashMap<String, Integer>();
        carried.forEach((item, count) -> physical.merge(InventoryReservationLedger.normalizeItem(item), count, Math::addExact));
        var nutrition = new LinkedHashMap<String, Integer>();
        physical.keySet().forEach(item -> nutrition.put(item, foodNutrition(item)));
        var external = new LinkedHashMap<String, Integer>();
        reservations.reservations().values().stream().filter(claim -> !claim.owner().equals(owner))
                .forEach(claim -> external.merge(claim.item(), claim.count(), Math::addExact));
        return MissionRationPolicy.partitionFoodAcquisition(missionId, owner, hunger, physical, nutrition, external, owned);
    }

    /**
     * Reconciles one real carried emergency item without creating an acquisition goal.
     * Returns true only when the shared ledger changed, so status/planner reads do not
     * continuously rewrite Baritone's throwaway fence.
     */
    private boolean refreshEmergencyTechniqueReservations() {
        List<InventoryReservationLedger.Reservation> next =
                EmergencyTechniqueReservationPolicy.derive(canonicalInventorySnapshot());
        List<InventoryReservationLedger.Reservation> current = reservations.reservations()
                .values().stream()
                .filter(reservation -> reservation.owner().equals(
                        EmergencyTechniqueReservationPolicy.OWNER))
                .toList();
        if (current.equals(next)) return false;
        reservations.replaceOwner(EmergencyTechniqueReservationPolicy.OWNER, next);
        return true;
    }

    private void refreshEmergencyTechniqueReservationsAndSync() {
        if (refreshEmergencyTechniqueReservations()) syncBaritoneThrowawayReservations();
    }

    private void syncBaritoneThrowawayReservations() {
        baritone.syncMissionThrowawayReservations(
                ThrowawayReservationPolicy.protectedReservationItems(reservations));
    }

    private boolean appendGatherFirstBatchCargo(
            Mission mission,
            PlanFrame root,
            String owner,
            List<InventoryReservationLedger.Reservation> destination) {
        List<BatchItem> items;
        try {
            items = parseBatchItems(required(root.parameters(), "batchItems"));
            GatherFirstBatchPolicy.Decision state = GatherFirstBatchPolicy.evaluate(
                    required(root.parameters(), "batchItems"), root.parameters(),
                    batchObservedCounts(items, root.parameters()), true,
                    root.kind().equals("root.batch.acquire")
                            ? GatherFirstBatchPolicy.Mode.ACQUIRE_ONLY
                            : GatherFirstBatchPolicy.Mode.ACQUIRE_THEN_DELIVER);
            if (state.action() == GatherFirstBatchPolicy.Action.CORRUPT) {
                failClosedBatchReservations(mission, owner, state.reason());
                return false;
            }
            for (int index = 0; index < items.size(); index++) {
                int outstanding = Math.max(
                        0, items.get(index).count() - state.delivered().get(index));
                appendCargoReservations(
                        destination, mission.id() + ":cargo:" + index, owner,
                        items.get(index).item(), outstanding,
                        "secured gather-first batch cargo " + (index + 1)
                                + "/" + items.size(),
                        batchCargoPurpose(root, items.get(index).item()));
            }
            return true;
        } catch (RuntimeException error) {
            failClosedBatchReservations(mission, owner, error.getMessage());
            return false;
        }
    }

    private void appendCargoReservations(
            List<InventoryReservationLedger.Reservation> destination,
            String idPrefix,
            String owner,
            String target,
            int objective,
            String reason) {
        appendCargoReservations(
                destination, idPrefix, owner, target, objective, reason,
                Purpose.MISSION_CARGO);
    }

    private void appendCargoReservations(
            List<InventoryReservationLedger.Reservation> destination,
            String idPrefix,
            String owner,
            String target,
            int objective,
            String reason,
            Purpose cargoPurpose) {
        if (objective <= 0) return;
        if (FoodFamilyPolicy.isFamilyRequest(target)) {
            FoodCargoAllocation allocation = allocateFoodCargo(
                    objective, inventoryViewWithoutStations());
            int memberIndex = 0;
            for (Map.Entry<String, Integer> food : allocation.readyItems().entrySet()) {
                destination.add(new InventoryReservationLedger.Reservation(
                        idPrefix + ":food-ready:" + memberIndex, owner,
                        food.getKey(), food.getValue(), cargoPurpose, reason));
                memberIndex++;
            }
            memberIndex = 0;
            for (Map.Entry<String, Integer> input : allocation.rawInputs().entrySet()) {
                destination.add(new InventoryReservationLedger.Reservation(
                        idPrefix + ":food-raw:" + memberIndex, owner,
                        input.getKey(), input.getValue(), cargoPurpose,
                        reason + "; protected raw mission food before cooking"));
                memberIndex++;
            }
            memberIndex = 0;
            for (Map.Entry<String, Integer> input : allocation.sourceInputs().entrySet()) {
                destination.add(new InventoryReservationLedger.Reservation(
                        idPrefix + ":food-source:" + memberIndex, owner,
                        input.getKey(), input.getValue(), cargoPurpose,
                        reason + "; protected convertible mission food before crafting"));
                memberIndex++;
            }
            return;
        }
        if (WoodLogFamilyPolicy.isFamilyRequest(target)) {
            int memberIndex = 0;
            for (Map.Entry<String, Integer> log : WoodLogFamilyPolicy
                    .allocateExistingFloor(inventoryViewWithoutStations(), objective)
                    .entrySet()) {
                destination.add(new InventoryReservationLedger.Reservation(
                        idPrefix + ":log:" + memberIndex, owner,
                        log.getKey(), log.getValue(), cargoPurpose, reason));
                memberIndex++;
            }
            return;
        }
        boolean exact = isExactUniversalRequest(target);
        int allocated = Math.min(objective, requestItemCount(target));
        String reservationItem = exact
                ? resourcePlanner.catalog().resolveUniversalItem(target)
                : resourcePlanner.normalizeItem(target);
        if (allocated > 0) destination.add(new InventoryReservationLedger.Reservation(
                idPrefix, owner, reservationItem, allocated, cargoPurpose, reason));
    }

    /** Home bridge/pillar stock remains expendable; all ordinary mission cargo stays sealed. */
    static Purpose batchCargoPurpose(PlanFrame root, String item) {
        return isHomeMaintenance(root) && HomeStockPolicy.isWorkingBuildingBlock(item)
                ? Purpose.BUILDING_BLOCKS
                : Purpose.MISSION_CARGO;
    }

    private void failClosedBatchReservations(
            Mission mission,
            String owner,
            String reason) {
        List<InventoryReservationLedger.Reservation> protectedInventory =
                new java.util.ArrayList<>();
        inventoryViewWithoutStations().forEach((item, count) -> {
            if (count == null || count <= 0) return;
            protectedInventory.add(new InventoryReservationLedger.Reservation(
                    mission.id() + ":corrupt-batch:" + simple(item),
                    owner,
                    item,
                    count,
                    Purpose.MISSION_CARGO,
                    "fail-closed reservation: " + reason));
        });
        reservations.replaceOwner(owner, protectedInventory);
        syncBaritoneThrowawayReservations();
        logger.debug("Fail-closed batch reservation for {}: {}", mission.id(), reason);
    }

    private Optional<TaskPlan> activeReservationPlan(Mission mission) {
        String planId = recoveryPlanByMission.getOrDefault(mission.id(), mission.id());
        return plans.restore(planId)
                .filter(plan -> plan.state() == TaskPlanState.OPEN && !plan.frames().isEmpty());
    }

    private String reservationTarget(
            Mission mission,
            Optional<TaskPlan> plan,
            BatchReservationPolicy.Decision batch) {
        if (plan.isPresent()) {
            Optional<PlanFrame> objective = reservationObjectiveFrame(plan.orElseThrow());
            if (objective.isPresent()) {
                return reservationItem(objective.orElseThrow().target());
            }
        }
        if (batch.state() == BatchReservationPolicy.State.ACTIVE) {
            return reservationItem(batch.current().item());
        }
        if (batch.state() == BatchReservationPolicy.State.COMPLETE) return "";
        String kind = mission.kind().toLowerCase(Locale.ROOT);
        if (!kind.equals("get") && !kind.equals("give") && !kind.equals("bring")) return "";
        return reservationItem(missionItem(mission));
    }

    private String reservationItem(String rawItem) {
        return WoodLogFamilyPolicy.isFamilyRequest(rawItem)
                ? WoodLogFamilyPolicy.FAMILY_ITEM
                : resourcePlanner.normalizeItem(rawItem);
    }

    private int reservationObjective(
            Mission mission,
            Optional<TaskPlan> plan,
            String target,
            BatchReservationPolicy.Decision batch) {
        if (target.isBlank()) return 0;
        if (plan.isPresent()) {
            PlanFrame root = reservationObjectiveFrame(plan.orElseThrow()).orElse(null);
            if (root == null) return 0;
            if (root.kind().equals("root.acquire") || root.kind().equals("root.restock")) {
                return Math.toIntExact(root.count());
            }
            if (root.kind().equals("root.food.acquire")) {
                return Math.toIntExact(root.count());
            }
            if (root.kind().equals("root.give")) {
                return Math.max(1, Math.toIntExact(root.count())
                        - integer(root.parameters(), "deliveredTotal", 0));
            }
            if (root.kind().equals("root.food.give")
                    || root.kind().equals("root.food.bring")) {
                return Math.max(0, Math.toIntExact(root.count())
                        - integer(root.parameters(), "deliveredTotal", 0));
            }
            if (root.kind().equals("root.bring")) {
                int delivered = integer(root.parameters(), "deliveredTotal", 0);
                int remaining = Math.max(1, Math.toIntExact(root.count()) - delivered);
                return Math.max(1, integer(root.parameters(), "deliveryBatch",
                        DeliveryPolicy.bringBatchSize(remaining, inventoryStackLimit(target))));
            }
        }
        if (batch.state() == BatchReservationPolicy.State.ACTIVE) {
            int remaining = Math.max(0, batch.current().count() - batch.delivered());
            if (remaining == 0) return 0;
            return mission.kind().equalsIgnoreCase("bring")
                    ? DeliveryPolicy.bringBatchSize(remaining, inventoryStackLimit(target))
                    : remaining;
        }
        int count = Boolean.parseBoolean(mission.parameters().getOrDefault("all", "false"))
                ? Math.max(1, canonicalCount(target)) : missionCount(mission);
        return mission.kind().equalsIgnoreCase("bring")
                ? DeliveryPolicy.bringBatchSize(count, inventoryStackLimit(target)) : count;
    }

    private List<Action> projectedPlanActions(
            Mission mission,
            Optional<TaskPlan> plan,
            String target,
            int objective,
            BatchReservationPolicy.Decision batch) {
        if (plan.isPresent() && !plan.orElseThrow().frames().isEmpty()) {
            PlanFrame root = plan.orElseThrow().frames().getFirst();
            String encoded = root.parameters().get(UNIVERSAL_PROGRAM_KEY);
            if (encoded != null) {
                try {
                    Program committed = UniversalProgramCodec.decode(encoded);
                    return committed.status() == Status.ACTIVE
                            ? committed.remainingActions() : List.of();
                } catch (IllegalArgumentException corrupt) {
                    logger.warn("Ignoring corrupt universal reservation program for {}: {}",
                            mission.id(), corrupt.getMessage());
                    return List.of();
                }
            }
            // The root itself owns the one legal compilation. Reservation
            // projection must not run a shadow compiler before that durable
            // decision is checkpointed.
            if (usesCommittedUniversalProgram(root)) return List.of();
        }
        if (plan.isEmpty()
                && (mission.kind().equalsIgnoreCase("get")
                || mission.kind().equalsIgnoreCase("bring"))
                && !FoodFamilyPolicy.isFamilyRequest(target)) {
            return List.of();
        }
        if (plan.isPresent() && hasBatchRoot(plan.orElseThrow())) {
            try {
                PlanFrame root = plan.orElseThrow().frames().getFirst();
                List<BatchItem> items = parseBatchItems(required(root.parameters(), "batchItems"));
                if (items.stream().anyMatch(item ->
                        FoodFamilyPolicy.isFamilyRequest(item.item()))) return List.of();
                GatherFirstBatchPolicy.Decision state = GatherFirstBatchPolicy.evaluate(
                        required(root.parameters(), "batchItems"), root.parameters(),
                        batchObservedCounts(items, root.parameters()), true,
                        root.kind().equals("root.batch.acquire")
                                ? GatherFirstBatchPolicy.Mode.ACQUIRE_ONLY
                                : GatherFirstBatchPolicy.Mode.ACQUIRE_THEN_DELIVER);
                if (state.action() == GatherFirstBatchPolicy.Action.CORRUPT) return List.of();
                ArrayList<AcquisitionRequest.ItemGoal> goals = new ArrayList<>();
                for (int index = 0; index < items.size(); index++) {
                    int outstanding = Math.max(
                            0, items.get(index).count() - state.delivered().get(index));
                    if (outstanding > 0) goals.add(new AcquisitionRequest.ItemGoal(
                            items.get(index).item(), outstanding));
                }
                if (goals.isEmpty()) return List.of();
                return resourcePlanner.compile(new AcquisitionRequest(
                        goals, inventoryView(), usablePickaxeDurability())).actions();
            } catch (ResourcePlanner.PlanningException | ArithmeticException error) {
                logger.debug("Could not project batch commitments for {}: {}",
                        mission.id(), error.getMessage());
                return List.of();
            }
        }
        if (plan.isPresent()) {
            PlanFrame root = reservationObjectiveFrame(plan.orElseThrow()).orElse(null);
            if (root == null) {
                if (batch.state() != BatchReservationPolicy.State.ACTIVE
                        || !hasBatchRoot(plan.orElseThrow())) return List.of();
            } else if (!root.kind().equals("root.acquire") && !root.kind().equals("root.restock")
                    && !root.kind().equals("root.bring")) return List.of();
        }
        if (target.isBlank() || objective <= 0) return List.of();
        try {
            return resourcePlanner.compile(new AcquisitionRequest(
                    List.of(new AcquisitionRequest.ItemGoal(target, objective)),
                    inventoryView(), usablePickaxeDurability())).actions();
        } catch (ResourcePlanner.PlanningException | ArithmeticException error) {
            logger.debug("Could not project commitments for {}: {}", mission.id(), error.getMessage());
            return List.of();
        }
    }

    private BatchReservationPolicy.Decision batchReservation(
            Mission mission,
            Optional<TaskPlan> plan) {
        String items = mission.parameters().get("batchItems");
        String index = mission.parameters().get("batchIndex");
        String delivered = mission.parameters().get("batchDelivered");
        if (plan.isPresent() && hasBatchRoot(plan.orElseThrow())) {
            PlanFrame root = plan.orElseThrow().frames().getFirst();
            items = root.parameters().getOrDefault("batchItems", items);
            index = root.parameters().getOrDefault("batchIndex", index);
            delivered = root.parameters().getOrDefault("batchDelivered", delivered);
        }
        return BatchReservationPolicy.inspect(items, index, delivered);
    }

    private static boolean hasBatchRoot(TaskPlan plan) {
        return !plan.frames().isEmpty()
                && plan.frames().getFirst().kind().startsWith("root.batch.");
    }

    private boolean usesCommittedUniversalProgram(PlanFrame root) {
        if (root.kind().equals("root.acquire")
                || root.kind().equals("root.restock")
                || root.kind().equals("root.bring")) {
            return !FoodFamilyPolicy.isFamilyRequest(root.target());
        }
        if (!root.kind().equals("root.batch.acquire")
                && !root.kind().equals("root.batch.bring")) return false;
        try {
            return parseBatchItems(required(root.parameters(), "batchItems")).stream()
                    .noneMatch(item -> FoodFamilyPolicy.isFamilyRequest(item.item()));
        } catch (RuntimeException corrupt) {
            return false;
        }
    }

    private static Optional<PlanFrame> reservationObjectiveFrame(TaskPlan plan) {
        List<PlanFrame> frames = plan.frames();
        int objectiveEnd = frames.size();
        for (int index = 0; index < frames.size(); index++) {
            if ("true".equals(frames.get(index).parameters().get("personalMaintenance"))) {
                objectiveEnd = index;
                break;
            }
        }
        for (int index = objectiveEnd - 1; index >= 0; index--) {
            PlanFrame frame = frames.get(index);
            if (frame.kind().equals("root.acquire") || frame.kind().equals("root.restock")
                    || frame.kind().equals("root.bring") || frame.kind().equals("root.give")
                    || frame.kind().equals("root.food.acquire")
                    || frame.kind().equals("root.food.bring")
                    || frame.kind().equals("root.food.give")) {
                return Optional.of(frame);
            }
        }
        return Optional.empty();
    }

    private InventoryReservationLedger.Reservation selectExistingMissionRation(
            Mission mission,
            Optional<TaskPlan> plan,
            List<InventoryReservationLedger.Reservation> owned,
            String owner) {
        if (client.player == null) return null;
        LinkedHashMap<String, Integer> carried = new LinkedHashMap<>();
        inventoryViewWithoutStations().forEach((item, count) -> {
            if (count != null && count > 0) carried.merge(
                    InventoryReservationLedger.normalizeItem(item), count, Math::addExact);
        });
        LinkedHashMap<String, Integer> protectedCounts = new LinkedHashMap<>();
        for (InventoryReservationLedger.Reservation reservation : owned) {
            protectedCounts.merge(reservation.item(), reservation.count(), Math::addExact);
        }
        double distance = estimatedRecipientDistance(mission);
        MissionRationPolicy.Phase phase = rationPhase(mission, plan, distance);
        int hunger = Math.max(0, Math.min(20, client.player.getHungerManager().getFoodLevel()));
        String best = "";
        int bestCount = 0;
        int bestNutrition = 0;
        for (Map.Entry<String, Integer> entry : carried.entrySet()) {
            int nutrition = foodNutrition(entry.getKey());
            if (nutrition <= 0) continue;
            int count = MissionRationPolicy.reserveExisting(
                    hunger, nutrition, entry.getValue(),
                    protectedCounts.getOrDefault(entry.getKey(), 0), phase, distance);
            if (count > 0 && (nutrition > bestNutrition
                    || (nutrition == bestNutrition && count > bestCount))) {
                best = entry.getKey();
                bestCount = count;
                bestNutrition = nutrition;
            }
        }
        return bestCount == 0 ? null : new InventoryReservationLedger.Reservation(
                mission.id() + ":ration", owner, best, bestCount,
                Purpose.PERSONAL_RATION, "existing surplus for recovery and remaining travel");
    }

    private MissionRationPolicy.Phase rationPhase(
            Mission mission, Optional<TaskPlan> plan, double distance) {
        String kind = mission.kind().toLowerCase(Locale.ROOT);
        boolean delivery = kind.equals("bring") || kind.equals("give");
        if (plan.isPresent()) {
            String leaf = plan.orElseThrow().currentFrame().map(PlanFrame::kind).orElse("");
            delivery = delivery && (leaf.equals("deliver")
                    || leaf.equals("root.bring") || leaf.equals("root.give"));
        }
        if (!delivery) return MissionRationPolicy.Phase.ACTIVE_WORK;
        return Double.isFinite(distance) && distance <= DeliveryPolicy.HANDOFF_RANGE
                ? MissionRationPolicy.Phase.NEAR_HANDOFF : MissionRationPolicy.Phase.DELIVERY;
    }

    private double estimatedRecipientDistance(Mission mission) {
        String kind = mission.kind().toLowerCase(Locale.ROOT);
        if ((!kind.equals("bring") && !kind.equals("give"))
                || client.player == null || client.world == null) return Double.NaN;
        String recipient = missionPlayer(mission);
        return client.world.getPlayers().stream()
                .filter(player -> !player.getUuid().equals(client.player.getUuid()))
                .filter(player -> player.getName().getString().equalsIgnoreCase(recipient))
                .mapToDouble(player -> Math.sqrt(client.player.squaredDistanceTo(player)))
                .min().orElse(Double.NaN);
    }

    private int foodNutrition(String item) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(
                item.contains(":") ? item : "minecraft:" + item);
        if (id == null || !Registries.ITEM.containsId(id)) return 0;
        var food = Registries.ITEM.get(id).getDefaultStack().get(DataComponentTypes.FOOD);
        return food == null ? 0 : Math.max(0, food.nutrition());
    }

    private Set<String> localResourceSources() {
        if (client.player == null || client.world == null) return Set.of();
        long perceptionRevision = baritone.resourcePerception().revision();
        if (localSourcePerceptionRevision != perceptionRevision) {
            localSourcePerceptionRevision = perceptionRevision;
            lastLocalSourceScanAge = Integer.MIN_VALUE;
            cachedLocalSources = Set.of();
        }
        int age = client.player.age;
        if (lastLocalSourceScanAge != Integer.MIN_VALUE
                && age >= lastLocalSourceScanAge
                && age - lastLocalSourceScanAge < 20) {
            return cachedLocalSources;
        }
        lastLocalSourceScanAge = age;
        Map<String, Set<String>> producedByBlock = resourcePlanner.miningItemsByBlock();
        int producibleItemCount = Math.toIntExact(producedByBlock.values().stream()
                .flatMap(Set::stream)
                .distinct()
                .count());
        LinkedHashSet<String> found = new LinkedHashSet<>();
        BlockPos origin = client.player.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        int horizontal = 16;
        int vertical = 8;
        for (int y = -vertical; y <= vertical && found.size() < producibleItemCount; y++) {
            for (int x = -horizontal; x <= horizontal; x++) {
                for (int z = -horizontal; z <= horizontal; z++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!client.world.getChunkManager().isChunkLoaded(cursor.getX() >> 4, cursor.getZ() >> 4)) continue;
                    String block = Registries.BLOCK.getId(client.world.getBlockState(cursor).getBlock()).getPath();
                    Set<String> produced = producedByBlock.get(block);
                    if (produced != null && baritone.resourcePerception().permitsBlock(cursor)) found.addAll(produced);
                }
            }
        }
        cachedLocalSources = Set.copyOf(found);
        return cachedLocalSources;
    }

    private Set<String> equippedItems() {
        LinkedHashSet<String> equipped = new LinkedHashSet<>();
        if (client.player == null) return equipped;
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = client.player.getEquippedStack(slot);
            if (stack.isEmpty()) continue;
            EquipmentSlot armor = MinecraftPersonalSuppliesAdapter.protectiveArmorSlot(stack);
            if (armor != null && armor != slot) continue;
            String raw = Registries.ITEM.getId(stack.getItem()).toString();
            if (PICKAXE_RANKS.containsKey(resourcePlanner.normalizeItem(raw))
                    && remainingDurability(stack) <= 1) continue;
            equipped.add(raw);
        }
        return Set.copyOf(equipped.stream().map(resourcePlanner::normalizeItem).toList());
    }

    private int foodReadyCount() {
        return FoodFamilyPolicy.aggregateReadyCount(inventoryViewWithoutStations());
    }

    private Map.Entry<String, Integer> nextReadyFoodStack() {
        return FoodFamilyPolicy.summary(inventoryViewWithoutStations()).readyByItem().entrySet()
                .stream()
                .max(java.util.Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .orElse(null);
    }

    private List<Integer> batchObservedCounts(List<BatchItem> items, Map<String, String> parameters) {
        return items.stream()
                .map(item -> {
                    int count = FoodFamilyPolicy.isFamilyRequest(item.item())
                            ? foodReadyCount() : requestItemCount(item.item());
                    if (DeliverySuppliesOrder.family(item.item())) {
                        for (int i = 0; i < items.size(); i++) {
                            BatchItem exact = items.get(i);
                            if (DeliverySuppliesOrder.family(exact.item())
                                    || !DeliverySuppliesOrder.matches(item.item(), exact.item())) continue;
                            int required = Math.max(0, exact.count() - encodedCountAt(
                                    parameters.get(GatherFirstBatchPolicy.DELIVERED_COUNTS_KEY), i));
                            count -= Math.min(required, requestItemCount(exact.item()));
                        }
                    }
                    return Math.max(0, count);
                })
                .toList();
    }

    private boolean batchHasAcquisitionCapacity(String item) {
        if (WoodLogFamilyPolicy.isFamilyRequest(item)) {
            return WoodLogFamilyPolicy.members().stream().anyMatch(inventory::canAccept);
        }
        if (!FoodFamilyPolicy.isFamilyRequest(item)) return inventory.canAccept(item);
        for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
            if (inventory.canAccept(member.readyItem())) return true;
            if (!member.rawItem().isBlank() && inventory.canAccept(member.rawItem())) return true;
            for (FoodFamilyPolicy.ItemSource source : member.itemSources()) {
                if (inventory.canAccept(source.item())) return true;
            }
        }
        return false;
    }

    private int objectiveCount(String item) {
        if (simple(item).equals("raw_food")) {
            int total = 0;
            for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
                if (!member.rawItem().isBlank()) {
                    total = Math.addExact(total, canonicalCount(member.rawItem()));
                }
            }
            return total;
        }
        if (FoodFamilyPolicy.isFamilyRequest(item)) return foodReadyCount();
        return canonicalCount(item);
    }

    /**
     * Runtime recipe leaves must keep exact registry identities. Legacy
     * leaves retain Entity's historical interchangeable wood/log aliases.
     */
    private int frameObjectiveCount(PlanFrame frame, String item) {
        if (Boolean.parseBoolean(
                frame.parameters().getOrDefault("universalAcquisition", "false"))) {
            return exactCount(item);
        }
        return objectiveCount(item);
    }

    private int requestItemCount(String rawItem) {
        return isExactUniversalRequest(rawItem)
                ? exactCount(resourcePlanner.catalog().resolveUniversalItem(rawItem))
                : objectiveCount(rawItem);
    }

    private boolean isExactUniversalRequest(String rawItem) {
        ResourceCatalog catalog = resourcePlanner.catalog();
        return catalog.normalizeExactItem(rawItem)
                .equals(catalog.resolveUniversalItem(rawItem));
    }

    private int exactCount(String item) {
        if (WoodLogFamilyPolicy.isFamilyRequest(item)) {
            return WoodLogFamilyPolicy.aggregateCount(inventoryViewWithoutStations());
        }
        String exact = ClientInventoryController.normalizeId(item);
        return inventoryViewWithoutStations().entrySet().stream()
                .filter(entry -> ClientInventoryController.normalizeId(entry.getKey()).equals(exact))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    private Set<String> collectionItems(String item) {
        if (WoodLogFamilyPolicy.isFamilyRequest(item)) {
            return WoodLogFamilyPolicy.members().stream()
                    .map(resourcePlanner::normalizeItem)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (!simple(item).equals("raw_food")) {
            return Set.of(resourcePlanner.normalizeItem(item));
        }
        LinkedHashSet<String> accepted = new LinkedHashSet<>();
        FoodFamilyPolicy.members().stream()
                .map(FoodFamilyPolicy.Member::rawItem)
                .filter(raw -> !raw.isBlank())
                .map(resourcePlanner::normalizeItem)
                .forEach(accepted::add);
        return Set.copyOf(accepted);
    }

    private int inventoryStackLimit(String itemId) {
        String normalized = resourcePlanner.normalizeItem(itemId);
        if (client.player != null) {
            var playerInventory = client.player.getInventory();
            for (int slot = 0; slot < playerInventory.size(); slot++) {
                ItemStack stack = playerInventory.getStack(slot);
                if (!stack.isEmpty()
                        && resourcePlanner.normalizeItem(Registries.ITEM.getId(stack.getItem()).toString())
                        .equals(normalized)) {
                    return Math.max(1, stack.getMaxCount());
                }
            }
        }
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(
                itemId.contains(":") ? itemId : "minecraft:" + itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return 64;
        return Math.max(1, Registries.ITEM.get(id).getDefaultStack().getMaxCount());
    }

    private int canonicalCount(String item) {
        String wanted = resourcePlanner.normalizeItem(item);
        return inventoryViewWithoutStations().entrySet().stream()
                .filter(entry -> resourcePlanner.normalizeItem(entry.getKey()).equals(wanted))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    private Map<String, Integer> inventoryViewWithoutStations() {
        return inventory.counts();
    }

    private int virtualStationCount(String station) {
        return switch (resourcePlanner.normalizeItem(station)) {
            case "crafting_table" -> inventory.count("minecraft:crafting_table")
                    + (inventory.isCraftingTableOpen() ? 1 : 0)
                    + (workstations.hasDurableFieldKitPlacement(
                    WorkstationController.Kind.CRAFTING_TABLE) ? 1 : 0);
            case "furnace" -> inventory.count("minecraft:furnace")
                    + (inventory.isFurnaceOpen() ? 1 : 0)
                    + (workstations.hasDurableFieldKitPlacement(
                    WorkstationController.Kind.FURNACE) ? 1 : 0);
            case "chest" -> inventory.count("minecraft:chest")
                    + (client.player != null
                    && client.player.currentScreenHandler instanceof net.minecraft.screen.GenericContainerScreenHandler
                    ? 1 : 0)
                    + (workstations.hasDurableFieldKitPlacement(
                    WorkstationController.Kind.CHEST) ? 1 : 0);
            default -> canonicalCount(station);
        };
    }

    private int rawCount(Set<String> simpleIds) {
        if (client.player == null) return 0;
        int result = 0;
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (simpleIds.contains(id)) result += stack.getCount();
        }
        return result;
    }

    private boolean hasPickaxeCapacity(int requiredRank, int remainingBlocks) {
        if (client.player == null) return false;
        ArrayList<Integer> usableDurabilities = new ArrayList<>();
        for (ItemStack stack : client.player.getInventory()) {
            if (stack.isEmpty()) continue;
            String simple = simple(Registries.ITEM.getId(stack.getItem()).toString());
            if (PICKAXE_RANKS.getOrDefault(simple, 0) < requiredRank) continue;
            int remaining = remainingDurability(stack);
            int work = MiningToolSegmentPolicy.usableWorkDurability(remaining);
            if (work > 0) usableDurabilities.add(work);
        }
        return MiningToolSegmentPolicy.hasCapacity(
                Math.max(1, remainingBlocks), usableDurabilities);
    }

    private Outcome prepareCarriedTreeTool(
            String planId, String blockAlternatives, long nowMillis) throws IOException {
        if (client.player == null) return null;
        var target = Registries.BLOCK.get(Identifier.of(
                "minecraft:" + simple(blockAlternatives.split(",")[0]))).getDefaultState();
        var playerInventory = client.player.getInventory();
        ArrayList<HarvestToolAccessPolicy.Candidate> candidates = new ArrayList<>();
        Set<Integer> emptyHotbar = new LinkedHashSet<>();
        for (int slot = 0; slot < playerInventory.getMainStacks().size(); slot++) {
            ItemStack stack = playerInventory.getStack(slot);
            if (stack.isEmpty()) {
                if (slot < 9) emptyHotbar.add(slot);
                continue;
            }
            candidates.add(new HarvestToolAccessPolicy.Candidate(
                    simple(Registries.ITEM.getId(stack.getItem()).toString()), slot,
                    remainingDurability(stack), stack.getMiningSpeedMultiplier(target)));
        }
        var prepared = HarvestToolAccessPolicy.choose(
                candidates, emptyHotbar, playerInventory.getSelectedSlot());
        if (prepared.isEmpty()) return null; // Native hand harvesting remains supported.
        var tool = prepared.orElseThrow();
        ItemStack selected = client.player.getMainHandStack();
        if (playerInventory.getSelectedSlot() == tool.hotbarSlot()
                && simple(Registries.ITEM.getId(selected.getItem()).toString()).equals(tool.item())
                && remainingDurability(selected) >= tool.minimumDurability()) return null;
        pushAction(planId, new Action(ActionKind.EQUIP, tool.item(), 1,
                Map.of("slot", "mainhand", "minimumToolDurability", Integer.toString(tool.minimumDurability()),
                        "harvestToolBinding", "true", "harvestToolHotbarSlot", Integer.toString(tool.hotbarSlot())),
                "Expose carried " + tool.item() + " to native tree autoTool"),
                nowMillis, false, "prepare the carried harvest tool before granting native tree work");
        return Outcome.continuePlan();
    }

    /** One Baritone goal is capped and explicitly selected from the same exact live stack type. */
    private Optional<MiningToolBinding> miningToolBinding(
            PlanFrame frame,
            int requiredRank,
            int requestedBlocks) {
        if (client.player == null) return Optional.empty();
        String committed = simple(frame.parameters().getOrDefault(
                "plannedTool",
                frame.parameters().getOrDefault("miningToolCommitmentItem", "")));
        int selectedSlot = client.player.getInventory().getSelectedSlot();
        MiningToolBinding best = null;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            String item = simple(Registries.ITEM.getId(stack.getItem()).toString());
            if (!committed.isBlank() && !item.equals(committed)) continue;
            if (PICKAXE_RANKS.getOrDefault(item, 0) < requiredRank) continue;
            int remaining = remainingDurability(stack);
            if (remaining <= 1) continue;
            int safeSegment = MiningToolSegmentPolicy.maximumSafeSegmentWork(
                    Math.max(1, requestedBlocks), List.of(remaining));
            MiningToolBinding offered = new MiningToolBinding(
                    item, slot, safeSegment, Math.addExact(safeSegment, 1));
            if (best == null || offered.safeSegmentBlocks() > best.safeSegmentBlocks()
                    || offered.safeSegmentBlocks() == best.safeSegmentBlocks()
                    && slot == selectedSlot && best.inventorySlot() != selectedSlot) {
                best = offered;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * An owned route may temporarily select a throwaway block while bridging.
     * Tool loss is therefore an inventory fact, not a main-hand fact. The
     * selected-stack acknowledgement remains mandatory before the segment
     * starts, and FabricBaritonePort independently refuses an unsafe break.
     */
    private boolean hasBoundServiceableMiningTool(
            PlanFrame frame,
            int requiredRank) {
        return miningToolBinding(frame, requiredRank, 1).isPresent();
    }

    private boolean selectedMiningToolMatches(
            int requiredRank,
            String committedItem,
            int minimumDurability) {
        if (client.player == null) return false;
        ItemStack selected = client.player.getMainHandStack();
        if (selected.isEmpty()) return false;
        String item = simple(Registries.ITEM.getId(selected.getItem()).toString());
        String committed = simple(committedItem);
        return (committed.isBlank() || item.equals(committed))
                && PICKAXE_RANKS.getOrDefault(item, 0) >= requiredRank
                && remainingDurability(selected) >= minimumDurability;
    }

    private void pushMiningToolBinding(
            String planId,
            MiningToolBinding binding,
            long nowMillis) throws IOException {
        pushAction(
                planId,
                new Action(
                        ActionKind.EQUIP,
                        binding.item(),
                        1,
                        Map.of(
                                "slot", "mainhand",
                                "minimumToolDurability",
                                Integer.toString(binding.minimumDurability()),
                                "miningSegmentBinding", "true"),
                        "Select the exact serviceable " + binding.item()
                                + " for the next bounded mining segment"),
                nowMillis,
                false,
                "binding the exact selected tool before Baritone may mine");
    }

    private record MiningToolBinding(
            String item,
            int inventorySlot,
            int safeSegmentBlocks,
            int minimumDurability) {
    }

    private boolean hasUsablePickaxe(int requiredRank) {
        if (client.player == null) return false;
        for (ItemStack stack : client.player.getInventory()) {
            if (stack.isEmpty() || remainingDurability(stack) <= 1) continue;
            String simple = simple(Registries.ITEM.getId(stack.getItem()).toString());
            if (PICKAXE_RANKS.getOrDefault(simple, 0) >= requiredRank) return true;
        }
        return false;
    }

    private Outcome provisionCommittedMiningTool(
            String planId,
            PlanFrame frame,
            int requiredRank,
            int segmentBlocks,
            String cargoItem,
            int cargoDeficit,
            long nowMillis) throws IOException {
        String committedItem = frame.parameters().get("miningToolCommitmentItem");
        int committedCount = integer(frame.parameters(), "miningToolCommitmentCount", 0);
        int committedSegmentBlocks = integer(
                frame.parameters(), "miningToolCommitmentSegmentBlocks", segmentBlocks);
        if (committedItem == null || committedItem.isBlank() || committedCount <= 0) {
            ToolStrategy.Choice choice = preferredPickaxeChoice(
                    requiredRank, segmentBlocks, cargoItem, cargoDeficit);
            committedItem = choice.item();
            committedCount = MiningToolSegmentPolicy.requiredInventoryCount(
                    canonicalCount(committedItem), choice);
            committedSegmentBlocks = segmentBlocks;
            checkpointMiningToolCommitment(
                    planId, frame, committedItem, committedCount, committedSegmentBlocks,
                    "committed " + committedCount + " " + committedItem
                            + " for fixed " + committedSegmentBlocks + "-block mining segment",
                    nowMillis);
            return Outcome.continuePlan();
        }

        // A capacity failure may outlive its original absolute item target after an
        // external inventory/durability change. Reprice from live stacks. A zero-craft
        // choice must rebind the carried tool, not fabricate another inventory objective.
        if (canonicalCount(committedItem) >= committedCount) {
            ToolStrategy.Choice rebased = preferredPickaxeChoice(
                    requiredRank, committedSegmentBlocks, cargoItem, cargoDeficit);
            String rebasedItem = rebased.item();
            int rebasedCount = MiningToolSegmentPolicy.requiredInventoryCount(
                    canonicalCount(rebasedItem), rebased);
            checkpointMiningToolCommitment(
                    planId, frame, rebasedItem, rebasedCount, committedSegmentBlocks,
                    "rebased already-satisfied mining tool commitment to "
                            + rebasedCount + " " + rebasedItem,
                    nowMillis);
            return Outcome.continuePlan();
        }
        return pushExactMissingResource(
                planId,
                committedItem,
                committedCount,
                "provisioning committed tool capacity for fixed "
                        + committedSegmentBlocks + "-block mining segment",
                nowMillis,
                planningInventoryViewForPlan(planId),
                "exact durability-aware mining-tool recovery");
    }

    private void checkpointMiningToolCommitment(
            String planId,
            PlanFrame frame,
            String item,
            int count,
            int segmentBlocks,
            String detail,
            long nowMillis) throws IOException {
        Map<String, String> checkpoint = MiningToolSegmentPolicy.withReplacementCommitment(
                frame.parameters(), item, count, segmentBlocks);
        plans.checkpointCurrent(
                planId, checkpoint, detail, nowMillis);
    }

    private boolean clearMiningToolCommitment(
            String planId,
            PlanFrame frame,
            long nowMillis) throws IOException {
        if (!frame.parameters().containsKey("miningToolCommitmentItem")) return false;
        Map<String, String> checkpoint = new LinkedHashMap<>(frame.parameters());
        checkpoint.remove("miningToolCommitmentItem");
        checkpoint.remove("miningToolCommitmentCount");
        checkpoint.remove("miningToolCommitmentSegmentBlocks");
        plans.checkpointCurrent(
                planId, checkpoint,
                "verified committed mining tool capacity; starting fixed segment",
                nowMillis);
        return true;
    }

    private ToolStrategy.Choice preferredPickaxeChoice(
            int requiredRank,
            int remainingBlocks,
            String cargoItem,
            int cargoDeficit) {
        ResourceCatalog.ToolTier required = switch (requiredRank) {
            case 1 -> ResourceCatalog.ToolTier.WOOD;
            case 2 -> ResourceCatalog.ToolTier.STONE;
            case 3 -> ResourceCatalog.ToolTier.IRON;
            default -> ResourceCatalog.ToolTier.DIAMOND;
        };
        LinkedHashMap<String, Integer> durability = new LinkedHashMap<>();
        if (client.player != null) {
            for (ItemStack stack : client.player.getInventory()) {
                if (stack.isEmpty()) continue;
                String item = simple(Registries.ITEM.getId(stack.getItem()).toString());
                if (!PICKAXE_RANKS.containsKey(item)) continue;
                durability.merge(
                        item,
                        MiningToolSegmentPolicy.usableWorkDurability(
                                remainingDurability(stack)),
                        Math::addExact);
            }
        }
        int travelBlocks = client.player == null
                ? 0
                : 32 + Math.abs(64 - client.player.getBlockY()) * 2;
        int bootstrapActions = (virtualStationCount("crafting_table") > 0 ? 0 : 1)
                + (canonicalCount("stick") >= 2 ? 0 : 2);
        double danger = 0.0D;
        if (client.player != null) {
            danger += Math.max(0.0D, 1.0D
                    - client.player.getHealth() / Math.max(1.0F, client.player.getMaxHealth())) * 3.0D;
            if (client.world != null) {
                long nearbyHostiles = client.world.getOtherEntities(
                        client.player,
                        client.player.getBoundingBox().expand(16.0D),
                        entity -> entity instanceof net.minecraft.entity.mob.HostileEntity
                                && entity.isAlive()).size();
                danger += Math.min(4.0D, nearbyHostiles * 0.5D);
            }
        }
        ToolStrategy.Choice choice = ToolStrategy.choose(new ToolStrategy.ReplacementRequest(
                required,
                Math.max(1, remainingBlocks),
                Math.max(0, cargoDeficit),
                cargoItem,
                inventoryViewWithoutStations(),
                durability,
                travelBlocks,
                bootstrapActions,
                Math.min(10.0D, danger)));
        logger.info("Tool replacement decision: {}", choice.reason());
        return choice;
    }

    private void restoreRecoveryIndex() {
        for (TaskPlan plan : plans.plans()) {
            if (plan.state() != TaskPlanState.OPEN || plan.frames().isEmpty()) continue;
            PlanFrame root = plan.frames().getFirst();
            if (!root.kind().equals("root.restock")
                    && !root.kind().equals(MissionDeathRecoveryPolicy.SIDECAR_ROOT_KIND)) continue;
            String parent = root.parameters().getOrDefault("parentMission", "");
            if (!parent.isBlank()) recoveryPlanByMission.put(parent, plan.missionId());
        }
    }

    private Outcome fromBaritone(BaritonePort.Status status, String prefix) {
        return switch (status.state()) {
            case COMPLETE -> Outcome.running(prefix + "; verifying inventory", 0,
                    status.completedWorkUnits(), false);
            case TRANSIENT_FAILURE -> Outcome.retry(status.detail());
            case BLOCKED -> Outcome.blocked(status.detail(), status.failureCause());
            case IDLE, CALCULATING, EXECUTING -> Outcome.running(
                    prefix + "; " + status.detail(), status.distanceRemaining(),
                    status.completedWorkUnits(), status.progressExpected());
        };
    }

    private static int requiredPickaxeTier(String[] blocks) {
        int tier = 0;
        for (String raw : blocks) {
            String block = simple(raw);
            int candidate;
            if (block.equals("obsidian") || block.equals("ancient_debris")) candidate = 4;
            else if (block.equals("nether_gold_ore")) candidate = 1;
            else if (contains(block, "diamond", "emerald", "gold_ore", "redstone")) candidate = 3;
            else if (contains(block, "iron_ore", "copper_ore", "lapis")) candidate = 2;
            else if (block.equals("stone") || block.equals("cobblestone")
                    || block.equals("deepslate") || block.equals("blackstone")
                    || block.endsWith("coal_ore") || block.endsWith("quartz_ore")) candidate = 1;
            else candidate = 0;
            tier = Math.max(tier, candidate);
        }
        return tier;
    }

    private static int tierRank(String tier) {
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "WOOD" -> 1;
            case "STONE" -> 2;
            case "IRON" -> 3;
            case "DIAMOND" -> 4;
            default -> 0;
        };
    }

    private static int remainingDurability(ItemStack stack) {
        return stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
    }

    private static boolean resumedAfterDelivery(PlanFrame frame) {
        return frame.detail().startsWith("resumed after deliver ");
    }

    private static PlanFrame nearestDeliveryRoot(TaskPlan plan) {
        List<PlanFrame> frames = plan.frames();
        for (int index = frames.size() - 2; index >= 0; index--) {
            PlanFrame candidate = frames.get(index);
            if (candidate.kind().equals("root.bring") || candidate.kind().equals("root.give")) {
                return candidate;
            }
        }
        throw new IllegalStateException("delivery leaf has no delivery root ancestor");
    }

    private static Optional<PlanFrame> batchRoot(TaskPlan plan) {
        if (plan.frames().isEmpty()) return Optional.empty();
        PlanFrame root = plan.frames().getFirst();
        return root.kind().startsWith("root.batch.") ? Optional.of(root) : Optional.empty();
    }

    private static Optional<PlanFrame> foodDeliveryRoot(TaskPlan plan) {
        List<PlanFrame> frames = plan.frames();
        for (int index = frames.size() - 2; index >= 0; index--) {
            PlanFrame candidate = frames.get(index);
            if (candidate.kind().equals("root.food.bring")
                    || candidate.kind().equals("root.food.give")) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Optional<PlanFrame> foodObjectiveRoot(TaskPlan plan) {
        List<PlanFrame> frames = plan.frames();
        for (int index = frames.size() - 1; index >= 0; index--) {
            PlanFrame candidate = frames.get(index);
            if (candidate.kind().equals("root.food.acquire")
                    || candidate.kind().equals("root.food.bring")
                    || candidate.kind().equals("root.food.give")) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static int encodedCountAt(String encoded, int index) {
        if (encoded == null || encoded.isBlank() || index < 0) return 0;
        String[] counts = encoded.split(",", -1);
        if (index >= counts.length) return 0;
        try {
            return Math.max(0, Integer.parseInt(counts[index]));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int ceilDiv(int numerator, int denominator) {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("ceilDiv operands must be positive");
        }
        return Math.toIntExact(((long) numerator + denominator - 1L) / denominator);
    }

    private static String missionItem(Mission mission) {
        return first(mission.parameters(), "item", "query", "resource", "block", "blocks");
    }

    private static int missionCount(Mission mission) {
        return Math.max(1, integer(mission.parameters(), "count",
                integer(mission.parameters(), "amount", 1)));
    }

    private int giveMissionCount(Mission mission) {
        if (!Boolean.parseBoolean(mission.parameters().getOrDefault("all", "false"))) {
            return missionCount(mission);
        }
        PersonalSuppliesPolicy.DeliveryPlan decision = PersonalSuppliesPolicy.planGive(deliveryAllocation(mission.id()),
                List.of(PersonalSuppliesPolicy.Request.all(missionItem(mission))),
                Boolean.parseBoolean(mission.parameters().getOrDefault("useReserves", "false")));
        int available = decision.lines().getFirst().selected();
        if (available < 1) {
            throw new IllegalArgumentException("No carried surplus to give: " + DeliverySuppliesOrder.explanation(decision));
        }
        return available;
    }

    private static String missionPlayer(Mission mission) {
        String player = first(mission.parameters(), "player", "recipient", "target");
        return player.isBlank() ? mission.issuedBy() : player;
    }

    private static Map<String, String> merge(Map<String, String> first, Map<String, String> second) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(first);
        result.putAll(second);
        return Map.copyOf(result);
    }

    private static Map<String, Integer> parseCounts(String encoded) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return result;
        for (String part : encoded.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank()) continue;
            try {
                result.put(simple(pair[0]), Math.max(1, Integer.parseInt(pair[1])));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static List<BatchItem> parseBatchItems(String encoded) {
        java.util.ArrayList<BatchItem> result = new java.util.ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int total = 0;
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("batchItems is required");
        }
        for (String part : encoded.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank()) {
                throw new IllegalArgumentException("invalid batch item " + part);
            }
            int count;
            try {
                count = Integer.parseInt(pair[1]);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("invalid batch count " + pair[1], error);
            }
            if (count < 1 || count > MAX_BATCH_ITEM_COUNT) {
                throw new IllegalArgumentException(
                        "batch count must be between 1 and " + MAX_BATCH_ITEM_COUNT);
            }
            String identity = simple(pair[0]);
            if (!seen.add(identity)) {
                throw new IllegalArgumentException("duplicate batch item " + pair[0]);
            }
            try {
                total = Math.addExact(total, count);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("batch total overflow", overflow);
            }
            result.add(new BatchItem(pair[0], count));
        }
        if (result.size() < 2) {
            throw new IllegalArgumentException("batch mission requires at least two items");
        }
        return List.copyOf(result);
    }

    private static void copyPositiveOrZero(
            Map<String, String> source,
            Map<String, String> target,
            String key) {
        String value = source.get(key);
        if (value == null || value.isBlank()) return;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 0) target.put(key, Integer.toString(parsed));
        } catch (NumberFormatException ignored) {
        }
    }

    private void captureInFlightHandoff(
            TaskPlan plan,
            Map<String, String> rootCheckpoint) {
        List<PlanFrame> frames = plan.frames();
        int leafIndex = -1;
        for (int index = frames.size() - 1; index >= 0; index--) {
            PlanFrame candidate = frames.get(index);
            if (candidate.kind().equals("deliver")
                    && !candidate.parameters().getOrDefault("handoffNonce", "").isBlank()) {
                leafIndex = index;
                break;
            }
        }
        if (leafIndex < 0) return;
        PlanFrame leaf = frames.get(leafIndex);
        PlanFrame deliveryRoot = null;
        for (int index = leafIndex - 1; index >= 0; index--) {
            PlanFrame candidate = frames.get(index);
            if (candidate.kind().equals("root.bring") || candidate.kind().equals("root.give")) {
                deliveryRoot = candidate;
                break;
            }
        }
        if (deliveryRoot == null) return;

        rootCheckpoint.put("deathHandoffPending", "true");
        rootCheckpoint.put("deathHandoffItem", leaf.target());
        rootCheckpoint.put("deathHandoffCount", Long.toString(leaf.count()));
        rootCheckpoint.put("deathHandoffRecipient",
                leaf.parameters().getOrDefault("recipient", ""));
        rootCheckpoint.put("deathHandoffDelivered",
                leaf.parameters().getOrDefault("delivered", "0"));
        rootCheckpoint.put("deathHandoffAllowAcquire",
                leaf.parameters().getOrDefault("allowAcquire", "false"));
        rootCheckpoint.put("deathHandoffNonce",
                leaf.parameters().get("handoffNonce"));
        rootCheckpoint.put("deathHandoffPrepared",
                leaf.parameters().getOrDefault("handoffPrepared", "false"));
        rootCheckpoint.put("deathHandoffMode",
                leaf.parameters().getOrDefault("handoffMode", ""));
        rootCheckpoint.put("deathHandoffBaseDelivered",
                leaf.parameters().getOrDefault("handoffBaseDelivered", "0"));
        rootCheckpoint.put("deathHandoffRootDeliveredBase",
                leaf.parameters().getOrDefault("handoffRootDeliveredBase", "0"));
        rootCheckpoint.put("deathHandoffFoodDeliveredBase",
                leaf.parameters().getOrDefault("handoffFoodDeliveredBase", "0"));
        rootCheckpoint.put("deathHandoffBatchIndex",
                leaf.parameters().getOrDefault("handoffBatchIndex", "-1"));
        rootCheckpoint.put("deathHandoffBatchDeliveredBase",
                leaf.parameters().getOrDefault("handoffBatchDeliveredBase", "0"));
        rootCheckpoint.put("deathHandoffAwaitingInventorySync",
                leaf.parameters().getOrDefault("handoffAwaitingInventorySync", "false"));
        rootCheckpoint.put("deathHandoffInventorySyncTarget",
                leaf.parameters().getOrDefault("handoffInventorySyncTarget", "0"));
        rootCheckpoint.put("deathHandoffInventorySyncStartedAt",
                leaf.parameters().getOrDefault("handoffInventorySyncStartedAt", "0"));
        rootCheckpoint.put("deathHandoffDropPending",
                leaf.parameters().getOrDefault("dropPending", "false"));
        rootCheckpoint.put("deathHandoffDropBaselineCount",
                leaf.parameters().getOrDefault("dropBaselineCount", "0"));
        rootCheckpoint.put("deathHandoffDropExpectedCount",
                leaf.parameters().getOrDefault("dropExpectedCount", "0"));
        rootCheckpoint.put("deathHandoffDropPreparedAt",
                leaf.parameters().getOrDefault("dropPreparedAt", "0"));
        rootCheckpoint.put("deathHandoffConfirmedCount",
                leaf.parameters().getOrDefault("handoffConfirmedCount", "0"));
        rootCheckpoint.put("deathHandoffRootKind", deliveryRoot.kind());
        rootCheckpoint.put("deathHandoffSelections", leaf.parameters().getOrDefault("handoffSelections", ""));
        rootCheckpoint.put("deathHandoffRootTarget", deliveryRoot.target());
        rootCheckpoint.put("deathHandoffRootCount", Long.toString(deliveryRoot.count()));
        rootCheckpoint.put("deathHandoffRootDeliveredTotal",
                deliveryRoot.parameters().getOrDefault("deliveredTotal", "0"));
        rootCheckpoint.put("deathHandoffDeliveryBatch",
                deliveryRoot.parameters().getOrDefault("deliveryBatch", "0"));
    }

    private static LinkedHashMap<String, String> recoveredHandoffRootParameters(
            Map<String, String> persisted) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("player", required(persisted, "deathHandoffRecipient"));
        result.put("description", "resume delivery transaction interrupted by death");
        int delivered = integer(persisted, "deathHandoffRootDeliveredTotal", 0);
        if (delivered > 0) result.put("deliveredTotal", Integer.toString(delivered));
        int batch = integer(persisted, "deathHandoffDeliveryBatch", 0);
        if (batch > 0) result.put("deliveryBatch", Integer.toString(batch));
        copyPrefixedFacts(persisted, result, "deathHandoff");
        return result;
    }

    private static LinkedHashMap<String, String> recoveredHandoffLeafParameters(
            Map<String, String> persisted,
            long nowMillis) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("description", "resume exact Paper handoff interrupted by death");
        result.put("deathRecoveredHandoff", "true");
        result.put("deathHandoffReplayUntil", Long.toString(Math.addExact(nowMillis, 5_000L)));
        result.put("recipient", required(persisted, "deathHandoffRecipient"));
        result.put("allowAcquire",
                persisted.getOrDefault("deathHandoffAllowAcquire", "false"));
        result.put("delivered", persisted.getOrDefault("deathHandoffDelivered", "0"));
        result.put("handoffNonce", required(persisted, "deathHandoffNonce"));
        String selections = persisted.getOrDefault("deathHandoffSelections", "");
        if (!selections.isBlank()) result.put("handoffSelections", selections);
        result.put("handoffPrepared",
                persisted.getOrDefault("deathHandoffPrepared", "false"));
        String mode = persisted.getOrDefault("deathHandoffMode", "");
        if (!mode.isBlank()) result.put("handoffMode", mode);
        result.put("handoffBaseDelivered",
                persisted.getOrDefault("deathHandoffBaseDelivered", "0"));
        result.put("handoffRootDeliveredBase",
                persisted.getOrDefault("deathHandoffRootDeliveredBase", "0"));
        result.put("handoffFoodDeliveredBase",
                persisted.getOrDefault("deathHandoffFoodDeliveredBase", "0"));
        result.put("handoffBatchIndex",
                persisted.getOrDefault("deathHandoffBatchIndex", "-1"));
        result.put("handoffBatchDeliveredBase",
                persisted.getOrDefault("deathHandoffBatchDeliveredBase", "0"));
        if (Boolean.parseBoolean(persisted.getOrDefault(
                "deathHandoffAwaitingInventorySync", "false"))) {
            result.put("handoffAwaitingInventorySync", "true");
            result.put("handoffInventorySyncTarget",
                    persisted.getOrDefault("deathHandoffInventorySyncTarget", "0"));
            result.put("handoffInventorySyncStartedAt",
                    persisted.getOrDefault("deathHandoffInventorySyncStartedAt", "0"));
        }
        result.put("dropPending", persisted.getOrDefault("deathHandoffDropPending", "false"));
        result.put("dropBaselineCount",
                persisted.getOrDefault("deathHandoffDropBaselineCount", "0"));
        result.put("dropExpectedCount",
                persisted.getOrDefault("deathHandoffDropExpectedCount", "0"));
        // Give the bridge one fresh bounded replay window after a potentially long respawn route.
        result.put("dropPreparedAt", Boolean.parseBoolean(result.get("dropPending"))
                ? Long.toString(nowMillis)
                : persisted.getOrDefault("deathHandoffDropPreparedAt", "0"));
        int confirmed = integer(persisted, "deathHandoffConfirmedCount", 0);
        if (confirmed > 0) result.put("handoffConfirmedCount", Integer.toString(confirmed));
        return result;
    }

    private static void copyPrefixedFacts(
            Map<String, String> source,
            Map<String, String> target,
            String prefix) {
        source.forEach((key, value) -> {
            if (key.startsWith(prefix) && value != null) target.put(key, value);
        });
    }

    private static void copyFact(
            Map<String, String> source,
            Map<String, String> target,
            String key) {
        String value = source.get(key);
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static void clearDeathRecoveryCheckpoint(Map<String, String> checkpoint) {
        checkpoint.keySet().removeIf(key -> key.startsWith("deathRecovery"));
    }

    private static void clearDeathHandoffCheckpoint(Map<String, String> checkpoint) {
        checkpoint.keySet().removeIf(key -> key.startsWith("deathHandoff"));
    }

    private DeathDropRecoveryPolicy.Capture deathCapture(Map<String, String> parameters) {
        return new DeathDropRecoveryPolicy.Capture(
                parameters.getOrDefault("deathRecoveryDimension", ""),
                integer(parameters, "deathRecoveryX", 0),
                integer(parameters, "deathRecoveryY", 0),
                integer(parameters, "deathRecoveryZ", 0),
                longInteger(parameters, "deathRecoveryDiedAt", 0L),
                DeathDropRecoveryPolicy.decodeCounts(
                        parameters.getOrDefault("deathRecoveryInventory", "")));
    }

    private Map<String, Integer> canonicalInventorySnapshot() {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        inventory.counts().forEach((raw, count) -> result.merge(
                resourcePlanner.normalizeItem(raw), count, Math::addExact));
        return Map.copyOf(result);
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.getOrDefault(key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longInteger(Map<String, String> values, String key, long fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String simple(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return normalized.startsWith("minecraft:") ? normalized.substring("minecraft:".length()) : normalized;
    }

    private static String inventoryTransactionOwner(ActionLease lease, String role) {
        Objects.requireNonNull(lease, "lease");
        String normalizedRole = Objects.requireNonNullElse(role, "inventory")
                .trim().toLowerCase(Locale.ROOT);
        return lease.operationId() + ":generation-" + lease.generation() + ':' + normalizedRole;
    }

    private static String deliveryTransactionKey(String missionId, String nonce) {
        return missionId.trim().toLowerCase(Locale.ROOT) + '|'
                + Objects.requireNonNullElse(nonce, "").trim().toLowerCase(Locale.ROOT);
    }

    private static void clearHandoffCheckpoint(Map<String, String> checkpoint) {
        checkpoint.remove("handoffSelections");
        checkpoint.remove("deathRecoveredHandoff");
        checkpoint.remove("deathHandoffReplayUntil");
        checkpoint.remove("handoffNonce");
        checkpoint.remove("handoffPrepared");
        checkpoint.remove("handoffMode");
        checkpoint.remove("handoffBaseDelivered");
        checkpoint.remove("handoffRootDeliveredBase");
        checkpoint.remove("handoffFoodDeliveredBase");
        checkpoint.remove("handoffBatchIndex");
        checkpoint.remove("handoffBatchDeliveredBase");
        checkpoint.remove("handoffConfirmedCount");
        checkpoint.remove("handoffAwaitingInventorySync");
        checkpoint.remove("handoffInventorySyncTarget");
        checkpoint.remove("handoffInventorySyncStartedAt");
        checkpoint.remove("dropPending");
        checkpoint.remove("dropBaselineCount");
        checkpoint.remove("dropExpectedCount");
        checkpoint.remove("dropPreparedAt");
    }

    private static boolean contains(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }

    private record BatchItem(String item, int count) {
        private BatchItem {
            item = Objects.requireNonNullElse(item, "").trim();
            if (item.isBlank() || count < 1) {
                throw new IllegalArgumentException("invalid batch item");
            }
        }
    }

    public enum State {
        RUNNING,
        COMPLETE,
        BLOCKED,
        RETRY,
        RESUME_LEGACY,
        PLAN_COMPLETE,
        CONTINUE_PLAN
    }

    public record HomeIdleOutcome(
            boolean retainLease,
            String detail,
            String progressMissionId,
            long completedWorkUnits) {
        public HomeIdleOutcome {
            detail = Objects.requireNonNullElse(detail, "").trim();
            progressMissionId = Objects.requireNonNullElse(progressMissionId, "").trim();
            if (completedWorkUnits < 0L) {
                throw new IllegalArgumentException("completedWorkUnits cannot be negative");
            }
        }

        public static HomeIdleOutcome retain(String detail) {
            return new HomeIdleOutcome(true, detail, "", 0L);
        }

        public static HomeIdleOutcome retainProgress(
                String detail,
                String progressMissionId,
                long completedWorkUnits) {
            if (progressMissionId == null || progressMissionId.isBlank()) {
                throw new IllegalArgumentException("progressMissionId cannot be blank");
            }
            return new HomeIdleOutcome(
                    true, detail, progressMissionId, completedWorkUnits);
        }

        public static HomeIdleOutcome release(String detail) {
            return new HomeIdleOutcome(false, detail, "", 0L);
        }
    }

    public record HomePreemptionOutcome(
            boolean terminal,
            boolean inventoryActuationBlocked,
            String detail) {
        public HomePreemptionOutcome {
            detail = Objects.requireNonNullElse(detail, "").trim();
        }

        public static HomePreemptionOutcome terminal(String detail) {
            return new HomePreemptionOutcome(true, false, detail);
        }

        public static HomePreemptionOutcome blocked(String detail) {
            return new HomePreemptionOutcome(false, true, detail);
        }
    }

    public record Outcome(
            State state,
            String detail,
            double distanceRemaining,
            long completedWorkUnits,
            boolean progressExpected,
            BaritonePort.FailureCause failureCause,
            boolean localBuildStallOwner) {

        public Outcome(State state, String detail, double distanceRemaining, long completedWorkUnits,
                boolean progressExpected, BaritonePort.FailureCause failureCause) {
            this(state, detail, distanceRemaining, completedWorkUnits,
                    progressExpected, failureCause, false);
        }

        public Outcome {
            Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
            failureCause = Objects.requireNonNull(failureCause, "failureCause");
            if (localBuildStallOwner && state != State.RUNNING)
                throw new IllegalArgumentException("only a running build observation owns the local stall clock");
        }

        private Outcome withLocalBuildStallOwner() {
            return new Outcome(state, detail, distanceRemaining, completedWorkUnits,
                    progressExpected, failureCause, true);
        }

        public static Outcome running(
                String detail, double distanceRemaining, long completedWorkUnits, boolean progressExpected) {
            return new Outcome(State.RUNNING, detail, distanceRemaining, completedWorkUnits,
                    progressExpected, BaritonePort.FailureCause.NONE);
        }

        public static Outcome complete(String detail) {
            return new Outcome(State.COMPLETE, detail, 0, 1, false,
                    BaritonePort.FailureCause.NONE);
        }

        public static Outcome blocked(String detail) {
            return blocked(detail, BaritonePort.FailureCause.NONE);
        }

        public static Outcome capacity(String detail) {
            return blocked(detail, BaritonePort.FailureCause.INVENTORY_CAPACITY);
        }

        public static Outcome blocked(
                String detail,
                BaritonePort.FailureCause failureCause) {
            return new Outcome(State.BLOCKED, detail, Double.NaN, 0, false, failureCause);
        }

        public static Outcome retry(String detail) {
            return new Outcome(State.RETRY, detail, Double.NaN, 0, false,
                    BaritonePort.FailureCause.NONE);
        }

        public static Outcome resumeLegacy(String detail) {
            return new Outcome(State.RESUME_LEGACY, detail, Double.NaN, 0, false,
                    BaritonePort.FailureCause.NONE);
        }

        public static Outcome planComplete(String detail) {
            return new Outcome(State.PLAN_COMPLETE, detail, 0, 1, false,
                    BaritonePort.FailureCause.NONE);
        }

        public static Outcome continuePlan() {
            return new Outcome(State.CONTINUE_PLAN, "plan advanced", Double.NaN, 0, false,
                    BaritonePort.FailureCause.NONE);
        }
    }

    private record DeliveryPrepareState(boolean accepted, String reason) {
    }

    private record DeliveryReceiptState(
            Set<String> receiptIds,
            String recipient,
            String itemId,
            int confirmedCount,
            int expectedCount) {
    }

    private record DeliveryReturnState(
            String returnId,
            String recipient,
            String itemId,
            int confirmedCount,
            int expectedCount,
            int remainingCount) {
    }

    private record Baseline(boolean ready, int count) {
    }

    private record MiningSessionKey(String planId, String frameId) {
        private MiningSessionKey {
            planId = Objects.requireNonNull(planId, "planId").trim();
            frameId = Objects.requireNonNull(frameId, "frameId").trim();
            if (planId.isEmpty() || frameId.isEmpty()) {
                throw new IllegalArgumentException("mining session key cannot be blank");
            }
        }
    }

    private record MiningDropCollectionResult(Outcome outcome, boolean finished) {
        private MiningDropCollectionResult {
            if (!finished && outcome == null) {
                throw new IllegalArgumentException(
                        "an active mined-drop transaction requires a running outcome");
            }
        }

        private static MiningDropCollectionResult completedResult() {
            return new MiningDropCollectionResult(null, true);
        }
    }

    private record GearProfile(List<String> acquire, List<String> equip) {
        private static GearProfile forTier(String tier) {
            return switch (tier) {
                case "wood", "wooden" -> new GearProfile(
                        List.of("wooden_pickaxe", "wooden_axe", "wooden_sword"),
                        List.of("wooden_sword"));
                case "stone" -> new GearProfile(
                        List.of("stone_pickaxe", "stone_axe", "stone_sword"),
                        List.of("stone_sword"));
                case "diamond" -> new GearProfile(
                        List.of(
                                "diamond_pickaxe", "diamond_axe", "diamond_sword",
                                "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots",
                                "shield", "bucket"),
                        List.of(
                                "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots",
                                "shield", "diamond_sword"));
                case "iron" -> new GearProfile(
                        List.of(
                                "iron_pickaxe", "iron_axe", "iron_sword",
                                "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots",
                                "shield", "water_bucket"),
                        List.of(
                                "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots",
                                "shield", "iron_sword"));
                default -> throw new IllegalArgumentException("gear tier must be wood, stone, iron, or diamond");
            };
        }
    }
}
