package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure allocation boundary between a direct user mission and pristine Home stock.
 *
 * <p>The allocator deliberately does not mutate either inventory. It first compiles against a
 * superset containing every eligible Home stack, replays that exact program with origin labels,
 * and then compiles again against only the selected prefix of each stack. Home allocation is
 * accepted only when the executable action list is a fixed point. A verified pinned station is a
 * virtual, non-consumable workstation and can never satisfy a final item goal.</p>
 *
 * <p>Food is resolved after the exact-item program. This makes the projected exact-item inventory
 * the food baseline and subtracts every exact final floor before family accounting, so a mixed
 * gather-first batch cannot promise one stack to two objectives.</p>
 */
public final class HomeMissionSupplyAllocator {
    private static final String COAL = "coal";
    private static final String CHARCOAL = "charcoal";
    private static final String COAL_BLOCK = "coal_block";
    private static final int OPERATIONS_PER_COAL = 8;
    private static final int OPERATIONS_PER_COAL_BLOCK = 80;
    private static final Map<String, Integer> PICKAXE_DURABILITY = Map.of(
            "wooden_pickaxe", 59,
            "stone_pickaxe", 131,
            "iron_pickaxe", 250,
            "diamond_pickaxe", 1561,
            "netherite_pickaxe", 2031);

    public enum MissionKind {
        GET,
        BRING,
        GIVE,
        GEAR,
        FARM
    }

    public enum Outcome {
        READY,
        NO_HOME_SUPPLY,
        SKIPPED,
        BLOCKED
    }

    public enum Purpose {
        FINAL,
        INGREDIENT,
        FUEL,
        TOOL,
        WORKSTATION,
        FOOD_READY,
        FOOD_RAW,
        FOOD_SOURCE,
        FOOD_FUEL
    }

    public enum FoodSourceKind {
        READY,
        RAW,
        ITEM_SOURCE
    }

    /**
     * Direct chest sourcing wraps user-requested mission roots only. Home
     * maintenance is already the fallback after stock analysis has exhausted
     * safe chest withdrawals; wrapping that synthetic acquisition in the same
     * source gate would recursively reopen Home instead of producing the
     * deficit.
     */
    public static boolean directSourcingEligible(
            boolean topLevelMissionRoot,
            boolean homeMaintenance) {
        return topLevelMissionRoot && !homeMaintenance;
    }

    /** Local station reuse is independent of whether a mission may source a chest.
     * Keep existing portable receipts authoritative; do not abandon a placed
     * furnace (possibly containing cargo) just because Home becomes available.
     */
    public static boolean nearbyStationReuseEligible(boolean sameDimension,
            double distanceSquared, boolean portableInFlight) {
        return sameDimension && !portableInFlight && Double.isFinite(distanceSquared)
                && distanceSquared >= 0.0 && distanceSquared <= 64.0 * 64.0;
    }

    /**
     * Restores serviceable carried pickaxes as reusable mission capabilities after the Home stock
     * floors have hidden their physical stacks from consumable planning.
     *
     * <p>A stock floor must stop a planner from delivering or crafting away Entity's last tool;
     * it must not make that tool unusable for mining.  Delivery finals stay hidden so a BRING
     * mission cannot spend the protected physical stack.  The returned counts can therefore fund
     * only the compiler's durability-backed tool path for every other objective.</p>
     */
    public static Map<String, Integer> retainReusablePickaxeCapabilities(
            Map<String, Integer> reserveSafeCounts,
            Map<String, Integer> serviceablePickaxeCounts,
            Set<String> deliveryFinalItems) {
        Objects.requireNonNull(reserveSafeCounts, "reserveSafeCounts");
        Objects.requireNonNull(serviceablePickaxeCounts, "serviceablePickaxeCounts");
        Objects.requireNonNull(deliveryFinalItems, "deliveryFinalItems");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>(reserveSafeCounts);
        LinkedHashSet<String> deliveryFinals = new LinkedHashSet<>();
        for (String item : deliveryFinalItems) {
            deliveryFinals.add(canonicalVanillaItem(item));
        }
        serviceablePickaxeCounts.forEach((raw, count) -> {
            String item = canonicalVanillaItem(raw);
            if (count == null || count < 0) {
                throw new IllegalArgumentException(
                        "serviceablePickaxeCounts contains a negative/null count for " + item);
            }
            if (count == 0 || !PICKAXE_DURABILITY.containsKey(item)
                    || deliveryFinals.contains(item)) return;
            result.merge(item, count, Math::max);
        });
        return Collections.unmodifiableMap(result);
    }

    private static String canonicalVanillaItem(String raw) {
        String item = requireText(raw, "item").toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        while (item.startsWith("minecraft:")) {
            item = item.substring("minecraft:".length());
        }
        return item;
    }

    /** One exact chest-slot snapshot. Non-pristine stacks are valid input but never eligible. */
    public record HomeStack(
            String stackId,
            String item,
            int count,
            boolean pristine,
            int usablePickaxeDurability) {
        public HomeStack {
            stackId = requireOpaqueId(stackId, "stackId");
            item = requireText(item, "item");
            if (count <= 0) throw new IllegalArgumentException("Home stack count must be positive");
            if (usablePickaxeDurability < 0) {
                throw new IllegalArgumentException("Home tool durability cannot be negative");
            }
            Integer maximum = pickaxeDurability(item);
            if (maximum == null && usablePickaxeDurability != 0) {
                throw new IllegalArgumentException(
                        "durability is accepted only for a supported pickaxe stack");
            }
            if (pristine && maximum != null) {
                int full = Math.multiplyExact(maximum, count);
                if (usablePickaxeDurability == 0) usablePickaxeDurability = full;
                if (usablePickaxeDurability != full) {
                    throw new IllegalArgumentException(
                            "a pristine Home pickaxe must expose its full durability");
                }
            }
        }

        public HomeStack(String stackId, String item, int count, boolean pristine) {
            this(stackId, item, count, pristine, 0);
        }

        /**
         * Physical item durability is not a mining capability. Preserve every ordinary item,
         * but credit durability only for this planner's supported pickaxes. The observer
         * reports remaining durability per item; the planner owns the aggregate stack count.
         */
        public static HomeStack fromPristineItem(
                String stackId, String item, int count, int remainingItemDurability) {
            if (remainingItemDurability < 0) {
                throw new IllegalArgumentException("observed item durability cannot be negative");
            }
            int miningDurability = pickaxeDurability(item) == null ? 0
                    : Math.multiplyExact(remainingItemDurability, count);
            return new HomeStack(stackId, item, count, true, miningDurability);
        }
    }

    /** Exact verified Home asset. Unverified pins are ignored rather than trusted optimistically. */
    public record VirtualPin(String pinId, String item, boolean verified) {
        public VirtualPin {
            pinId = requireOpaqueId(pinId, "pinId");
            item = requireText(item, "item");
        }
    }

    public record Request(
            MissionKind missionKind,
            List<AcquisitionRequest.ItemGoal> goals,
            ResourcePlanner.InventoryView carried,
            Map<String, Integer> carriedUsablePickaxeDurability,
            List<HomeStack> homeStacks,
            List<VirtualPin> virtualPins,
            Set<String> unavailableHarvestSources) {
        public Request {
            missionKind = Objects.requireNonNull(missionKind, "missionKind");
            Objects.requireNonNull(goals, "goals");
            if (goals.isEmpty()) throw new IllegalArgumentException("at least one goal is required");
            goals = List.copyOf(goals);
            carried = Objects.requireNonNull(carried, "carried");
            carriedUsablePickaxeDurability = immutableNonNegativeIncludingZero(
                    carriedUsablePickaxeDurability, "carriedUsablePickaxeDurability");
            homeStacks = List.copyOf(Objects.requireNonNull(homeStacks, "homeStacks"));
            virtualPins = List.copyOf(Objects.requireNonNull(virtualPins, "virtualPins"));
            unavailableHarvestSources = Set.copyOf(Objects.requireNonNull(
                    unavailableHarvestSources, "unavailableHarvestSources"));
        }

        public Request(
                MissionKind missionKind,
                List<AcquisitionRequest.ItemGoal> goals,
                ResourcePlanner.InventoryView carried,
                Map<String, Integer> carriedUsablePickaxeDurability,
                List<HomeStack> homeStacks,
                List<VirtualPin> virtualPins) {
            this(missionKind, goals, carried, carriedUsablePickaxeDurability,
                    homeStacks, virtualPins, Set.of());
        }

        public Request(
                MissionKind missionKind,
                List<AcquisitionRequest.ItemGoal> goals,
                ResourcePlanner.InventoryView carried,
                List<HomeStack> homeStacks,
                List<VirtualPin> virtualPins) {
            this(missionKind, goals, carried, Map.of(), homeStacks, virtualPins);
        }
    }

    /** Exact prefix to remove from one observed chest stack. */
    public record StackWithdrawal(
            String stackId,
            String item,
            int count,
            int toolDurabilityUsed,
            Set<Purpose> purposes) {
        public StackWithdrawal {
            stackId = requireOpaqueId(stackId, "stackId");
            item = requireText(item, "item");
            if (count <= 0 || toolDurabilityUsed < 0) {
                throw new IllegalArgumentException("invalid withdrawal accounting");
            }
            purposes = Collections.unmodifiableSet(EnumSet.copyOf(purposes));
        }
    }

    /** Origin-independent food conversion selected in stable family/source order. */
    public record FoodStep(
            FoodSourceKind kind,
            String readyItem,
            String sourceItem,
            int inputCount,
            int readyOutputCount) {
        public FoodStep {
            kind = Objects.requireNonNull(kind, "kind");
            readyItem = requireText(readyItem, "readyItem");
            sourceItem = requireText(sourceItem, "sourceItem");
            if (inputCount <= 0 || readyOutputCount <= 0) {
                throw new IllegalArgumentException("food step counts must be positive");
            }
        }
    }

    /** One exact, ordered physical fuel-family tranche selected by the food proof. */
    public record FoodFuelSlice(
            String item,
            int count,
            int operationsPerItem) {
        public FoodFuelSlice {
            item = requireText(item, "item");
            int expected = switch (item) {
                case COAL, CHARCOAL -> OPERATIONS_PER_COAL;
                case COAL_BLOCK -> OPERATIONS_PER_COAL_BLOCK;
                default -> throw new IllegalArgumentException(
                        "unsupported food fuel family " + item);
            };
            if (count <= 0 || operationsPerItem != expected) {
                throw new IllegalArgumentException("invalid food fuel slice");
            }
        }
    }

    public record FoodProgram(
            int requestedCount,
            List<FoodStep> steps,
            int readyCount,
            int unresolvedReadyCount,
            int cookingOperations,
            int requiredFuelCount,
            int suppliedFuelCount,
            List<FoodFuelSlice> fuelSlices,
            Set<String> requiredStations) {
        public FoodProgram {
            if (requestedCount <= 0 || readyCount < 0 || unresolvedReadyCount < 0
                    || cookingOperations < 0 || requiredFuelCount < 0
                    || suppliedFuelCount < 0 || suppliedFuelCount > requiredFuelCount) {
                throw new IllegalArgumentException("invalid food program accounting");
            }
            steps = List.copyOf(steps);
            fuelSlices = List.copyOf(fuelSlices);
            requiredStations = Collections.unmodifiableSet(
                    new LinkedHashSet<>(requiredStations));
            LinkedHashSet<String> families = new LinkedHashSet<>();
            int rawFuelOperations = 0;
            for (FoodFuelSlice slice : fuelSlices) {
                if (!families.add(slice.item())) {
                    throw new IllegalArgumentException("food fuel family is repeated");
                }
                rawFuelOperations = Math.addExact(rawFuelOperations,
                        Math.multiplyExact(slice.count(), slice.operationsPerItem()));
            }
            int rawRuns = (int) steps.stream()
                    .filter(step -> step.kind() == FoodSourceKind.RAW)
                    .count();
            int requiredOperations = Math.addExact(
                    cookingOperations, Math.max(0, rawRuns - 1));
            int effective = Math.max(0,
                    rawFuelOperations - Math.max(0, fuelSlices.size() - 1));
            int expectedRequiredFuel = requiredOperations == 0
                    ? 0 : ceilDiv(requiredOperations, OPERATIONS_PER_COAL);
            int expectedSuppliedFuel = effective >= requiredOperations
                    ? expectedRequiredFuel
                    : Math.min(expectedRequiredFuel, effective / OPERATIONS_PER_COAL);
            if (requiredFuelCount != expectedRequiredFuel
                    || suppliedFuelCount != expectedSuppliedFuel
                    || (cookingOperations == 0 && !fuelSlices.isEmpty())) {
                throw new IllegalArgumentException("food fuel proof is inconsistent");
            }
        }

        public int missingFuelCount() {
            int rawRuns = (int) steps.stream()
                    .filter(step -> step.kind() == FoodSourceKind.RAW)
                    .count();
            int requiredOperations = Math.addExact(
                    cookingOperations, Math.max(0, rawRuns - 1));
            int effective = effectiveFuelOperations();
            if (effective >= requiredOperations) return 0;
            // The ordinary post-withdraw fallback acquires coal. If the persisted
            // exact bundle currently contains only charcoal or a coal block, adding
            // coal introduces one family transition and loses one operation of burn
            // time. Count that transition before compiling the absolute coal target:
            // block(80)+coal(8) safely covers 87, not 88.
            boolean containsCoal = fuelSlices.stream()
                    .anyMatch(slice -> slice.item().equals(COAL));
            int transition = fuelSlices.isEmpty() || containsCoal ? 0 : 1;
            return ceilDiv(
                    Math.addExact(requiredOperations - effective, transition),
                    OPERATIONS_PER_COAL);
        }

        public int effectiveFuelOperations() {
            int raw = 0;
            for (FoodFuelSlice slice : fuelSlices) {
                raw = Math.addExact(raw,
                        Math.multiplyExact(slice.count(), slice.operationsPerItem()));
            }
            return Math.max(0, raw - Math.max(0, fuelSlices.size() - 1));
        }
    }

    public record Allocation(
            Outcome outcome,
            List<StackWithdrawal> withdrawals,
            Map<String, Integer> allocatedCounts,
            Map<String, Integer> homeConsumedCounts,
            Map<String, Integer> homeRetainedFinalCounts,
            Map<String, Integer> homeToolDurabilityUsed,
            Set<String> virtualPinsUsed,
            Map<String, Integer> exactFinalReservations,
            Map<String, Integer> projectedInventoryBeforeFood,
            Optional<FoodProgram> foodProgram,
            Optional<AcquisitionPlan> probePlan,
            Optional<AcquisitionPlan> fixedPointPlan,
            String reason) {
        public Allocation {
            outcome = Objects.requireNonNull(outcome, "outcome");
            withdrawals = List.copyOf(withdrawals);
            allocatedCounts = immutableNonNegative(allocatedCounts, "allocatedCounts");
            homeConsumedCounts = immutableNonNegative(homeConsumedCounts, "homeConsumedCounts");
            homeRetainedFinalCounts = immutableNonNegative(
                    homeRetainedFinalCounts, "homeRetainedFinalCounts");
            homeToolDurabilityUsed = immutableNonNegative(
                    homeToolDurabilityUsed, "homeToolDurabilityUsed");
            virtualPinsUsed = Collections.unmodifiableSet(new LinkedHashSet<>(virtualPinsUsed));
            exactFinalReservations = immutableNonNegative(
                    exactFinalReservations, "exactFinalReservations");
            projectedInventoryBeforeFood = immutableNonNegative(
                    projectedInventoryBeforeFood, "projectedInventoryBeforeFood");
            foodProgram = Objects.requireNonNull(foodProgram, "foodProgram");
            probePlan = Objects.requireNonNull(probePlan, "probePlan");
            fixedPointPlan = Objects.requireNonNull(fixedPointPlan, "fixedPointPlan");
            reason = requireText(reason, "reason");
        }

        public boolean accepted() {
            return outcome == Outcome.READY || outcome == Outcome.NO_HOME_SUPPLY;
        }
    }

    @FunctionalInterface
    interface PlanCompiler {
        AcquisitionPlan compile(AcquisitionRequest request);
    }

    private final ResourcePlanner planner;
    private final PlanCompiler compiler;

    public HomeMissionSupplyAllocator(ResourcePlanner planner) {
        this(planner, planner::compile);
    }

    HomeMissionSupplyAllocator(ResourcePlanner planner, PlanCompiler compiler) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    public Allocation allocate(Request rawRequest) {
        Objects.requireNonNull(rawRequest, "request");
        Normalized request = normalize(rawRequest);
        UsageTracker selected = new UsageTracker(request.homeStacks());
        Optional<AcquisitionPlan> probe = Optional.empty();
        Optional<AcquisitionPlan> fixed = Optional.empty();
        Map<String, Integer> postExact = request.carriedCounts();
        LinkedHashSet<String> pinsUsed = new LinkedHashSet<>();

        if (!request.exactGoals().isEmpty()) {
            if (rawRequest.missionKind() == MissionKind.GIVE) {
                try {
                    GiveAllocation give = allocateGiveFinalOnly(request, selected);
                    postExact = give.projectedNonVirtual();
                } catch (RuntimeException insufficient) {
                    // GIVE is held-inventory semantics. Fail before exposing any selected
                    // withdrawal so a short order cannot partially rearrange Home storage.
                    selected = new UsageTracker(request.homeStacks());
                    return result(Outcome.BLOCKED, request, selected, pinsUsed,
                            request.exactGoalFloors(), postExact, Optional.empty(), probe, fixed,
                            "give final cargo is incomplete: " + insufficient.getMessage());
                }
            } else {
                ExactAllocation exact;
            try {
                    exact = allocateExactProgram(request, selected);
            } catch (ResourcePlanner.PlanningException unsupported) {
                return result(Outcome.SKIPPED, request, selected, pinsUsed,
                        Map.of(), postExact, Optional.empty(), probe, fixed,
                        "Home supply probe was not compilable: " + unsupported.getMessage());
            } catch (RuntimeException inconsistent) {
                return result(Outcome.BLOCKED, request, selected, pinsUsed,
                        Map.of(), postExact, Optional.empty(), probe, fixed,
                        "Home supply replay failed closed: " + inconsistent.getMessage());
            }
            probe = Optional.of(exact.probe());
            fixed = Optional.of(exact.fixed());
            if (!exact.probe().actions().equals(exact.fixed().actions())) {
                return result(Outcome.BLOCKED, request, selected, pinsUsed,
                        request.exactGoalFloors(), exact.projectedNonVirtual(),
                        Optional.empty(), probe, fixed,
                        "Home allocation changed the compiled executable program");
            }
            postExact = exact.projectedNonVirtual();
            pinsUsed.addAll(exact.virtualPinsUsed());
            }
        }

        LinkedHashSet<String> reusableStationCapabilities = new LinkedHashSet<>();
        reusableStationCapabilities.addAll(request.availableStations());
        for (String station : List.of("crafting_table", "furnace")) {
            if (postExact.getOrDefault(station, 0) > 0) {
                reusableStationCapabilities.add(station);
            }
        }
        Map<String, Integer> foodBaseline;
        try {
            foodBaseline = subtractReservations(postExact, request.exactGoalFloors());
        } catch (RuntimeException inconsistent) {
            return result(Outcome.BLOCKED, request, selected, pinsUsed,
                    request.exactGoalFloors(), postExact, Optional.empty(), probe, fixed,
                    "exact final reservations were not preserved: " + inconsistent.getMessage());
        }

        Optional<FoodProgram> food = Optional.empty();
        if (request.foodGoalCount() > 0) {
            try {
                FoodAllocation foodAllocation = allocateFood(
                        request, foodBaseline, selected,
                        rawRequest.missionKind() == MissionKind.GIVE,
                        reusableStationCapabilities);
                if (rawRequest.missionKind() == MissionKind.GIVE
                        && foodAllocation.fixed().unresolvedReadyCount() > 0) {
                    // A mixed GIVE order is atomic too: no exact or food stack is withdrawn when
                    // carried plus pristine Home ready cargo cannot satisfy the whole order.
                    UsageTracker none = new UsageTracker(request.homeStacks());
                    return result(Outcome.BLOCKED, request, none, Set.of(),
                            request.exactGoalFloors(), request.carriedCounts(),
                            Optional.of(foodAllocation.fixed()), Optional.empty(), Optional.empty(),
                            "give food cargo is incomplete by "
                                    + foodAllocation.fixed().unresolvedReadyCount());
                }
                if (!foodAllocation.probe().equals(foodAllocation.fixed())) {
                    return result(Outcome.BLOCKED, request, selected, pinsUsed,
                            request.exactGoalFloors(), postExact,
                            Optional.of(foodAllocation.probe()), probe, fixed,
                            "Home food allocation did not reproduce its selected conversion program");
                }
                food = Optional.of(foodAllocation.fixed());
                pinsUsed.addAll(foodAllocation.virtualPinsUsed());
            } catch (RuntimeException inconsistent) {
                return result(Outcome.BLOCKED, request, selected, pinsUsed,
                        request.exactGoalFloors(), postExact, food, probe, fixed,
                        "Home food allocation failed closed: " + inconsistent.getMessage());
            }
        }

        Outcome outcome = selected.empty() && pinsUsed.isEmpty()
                ? Outcome.NO_HOME_SUPPLY : Outcome.READY;
        return result(outcome, request, selected, pinsUsed,
                request.exactGoalFloors(), postExact, food, probe, fixed,
                outcome == Outcome.READY
                        ? "selected the minimal fixed-point Home supply"
                        : "the carried/projected inventory needs no eligible Home supply");
    }

    private ExactAllocation allocateExactProgram(Normalized request, UsageTracker selected) {
        List<VirtualPin> pins = request.virtualPins();
        CompiledWithPins probeCompilation = compileWithNonConsumablePins(
                request, request.homeStacks(), pins);
        AcquisitionPlan probe = probeCompilation.plan();
        List<VirtualPin> activePins = probeCompilation.pins();
        try {
            AcquisitionPlan carriedOnly = compileWithNonConsumablePins(
                    request, List.of(), activePins).plan();
            // Home is working storage, not a reason to replace an equally cheap carried recipe,
            // material family, or already-qualified tool. It participates only when it makes the
            // selected executable work strictly smaller in this stable, world-cost-first order.
            if (planCost(carriedOnly).compareTo(planCost(probe)) <= 0) {
                UsageTracker none = new UsageTracker(request.homeStacks());
                Replay replay = replay(
                        carriedOnly, request.carriedCounts(), request.carriedDurability(),
                        request.availableStations(), List.of(), activePins,
                        request.exactGoals(), none);
                return new ExactAllocation(
                        carriedOnly, carriedOnly, replay.projectedNonVirtual(),
                        replay.virtualPinsUsed());
            }
        } catch (ResourcePlanner.PlanningException unavailableWithoutHome) {
            // Home contains a required final/dependency/tool; continue with the full probe.
        }
        Replay probeReplay = replay(
                probe, request.carriedCounts(), request.carriedDurability(),
                request.availableStations(), request.homeStacks(), activePins,
                request.exactGoals(), selected);

        List<HomeStack> chosen = selectedStacks(request.homeStacks(), selected);
        CompiledWithPins fixedCompilation = compileWithNonConsumablePins(
                request, chosen, activePins);
        AcquisitionPlan fixed = fixedCompilation.plan();
        if (!pinIds(activePins).equals(pinIds(fixedCompilation.pins()))) {
            throw new IllegalStateException(
                    "fixed-point compile changed non-consumable Home pin availability");
        }
        UsageTracker verification = new UsageTracker(chosen);
        Replay fixedReplay = replay(
                fixed, request.carriedCounts(), request.carriedDurability(),
                request.availableStations(), chosen, activePins,
                request.exactGoals(), verification);
        requireExactSelection(chosen, verification);
        if (!probeReplay.virtualPinsUsed().equals(fixedReplay.virtualPinsUsed())) {
            throw new IllegalStateException(
                    "fixed-point replay changed the exact virtual-station selection");
        }
        return new ExactAllocation(
                probe, fixed, fixedReplay.projectedNonVirtual(),
                union(probeReplay.virtualPinsUsed(), fixedReplay.virtualPinsUsed()));
    }

    private static PlanCost planCost(AcquisitionPlan plan) {
        int mine = 0;
        int hunt = 0;
        int smelt = 0;
        int craft = 0;
        int deliver = 0;
        int equip = 0;
        for (ResourcePlanner.Action action : plan.actions()) {
            switch (action.kind()) {
                case MINE -> mine = Math.addExact(mine, action.count());
                case HUNT -> hunt = Math.addExact(hunt, action.count());
                case SMELT -> smelt = Math.addExact(smelt, action.count());
                case CRAFT -> craft = Math.addExact(craft, action.count());
                case DELIVER -> deliver = Math.addExact(deliver, action.count());
                case EQUIP -> equip = Math.addExact(equip, action.count());
            }
        }
        return new PlanCost(mine, hunt, smelt, craft, deliver, equip, plan.actions().size());
    }

    /** A verified Home pin is a non-consumable planning capability, never an item count. */
    private CompiledWithPins compileWithNonConsumablePins(
            Normalized request,
            List<HomeStack> includedHome,
            List<VirtualPin> candidatePins) {
        List<VirtualPin> active = candidatePins.stream()
                .filter(VirtualPin::verified)
                .toList();
        return new CompiledWithPins(
                compiler.compile(compilerRequest(request, includedHome, active)), active);
    }

    private static List<String> pinIds(List<VirtualPin> pins) {
        return pins.stream().map(VirtualPin::pinId).toList();
    }

    private GiveAllocation allocateGiveFinalOnly(Normalized request, UsageTracker selected) {
        OriginLedger finalLedger = new OriginLedger(selected, List.of());
        finalLedger.addCarried(request.carriedCounts(), request.carriedDurability());
        finalLedger.addHome(request.homeStacks(), 0);
        request.exactGoalFloors().forEach((item, count) -> {
            if (finalLedger.available(item) < count) {
                throw new IllegalStateException("needs " + count + " " + item
                        + " but carried plus pristine Home stock has only "
                        + finalLedger.available(item));
            }
        });
        request.exactGoalFloors().forEach((item, count) ->
                finalLedger.reserve(item, count, Purpose.FINAL));

        List<HomeStack> chosen = selectedStacks(request.homeStacks(), selected);
        UsageTracker verification = new UsageTracker(chosen);
        OriginLedger fixed = new OriginLedger(verification, List.of());
        fixed.addCarried(request.carriedCounts(), request.carriedDurability());
        fixed.addHome(chosen, 0);
        Map<String, Integer> projected = fixed.nonVirtualCounts();
        request.exactGoalFloors().forEach((item, count) ->
                fixed.reserve(item, count, Purpose.FINAL));
        requireExactSelection(chosen, verification);
        return new GiveAllocation(projected);
    }

    private FoodAllocation allocateFood(
            Normalized request,
            Map<String, Integer> baseline,
            UsageTracker selected,
            boolean giveOnlyReady,
            Set<String> reusableStationCapabilities) {
        Map<String, Integer> exactPrefixes = selected.highWaterByStack();
        List<HomeStack> remainder = remainingStacks(request.homeStacks(), exactPrefixes);
        OriginLedger probeLedger = new OriginLedger(selected, request.virtualPins());
        probeLedger.addReusableStationCapabilities(reusableStationCapabilities);
        probeLedger.addCarried(baseline, Map.of());
        probeLedger.addHome(remainder, exactPrefixes);
        FoodRun probe = planFood(
                request.foodGoalCount(), giveOnlyReady, probeLedger);

        Map<String, Integer> afterFoodPrefixes = selected.highWaterByStack();
        List<HomeStack> foodChosen = selectedStackDelta(
                request.homeStacks(), exactPrefixes, afterFoodPrefixes);
        UsageTracker verification = new UsageTracker(foodChosen);
        OriginLedger fixedLedger = new OriginLedger(verification, request.virtualPins());
        fixedLedger.addReusableStationCapabilities(reusableStationCapabilities);
        fixedLedger.addCarried(baseline, Map.of());
        fixedLedger.addHome(foodChosen, 0);
        FoodRun fixed = planFood(
                request.foodGoalCount(), giveOnlyReady, fixedLedger);
        requireExactSelection(foodChosen, verification);
        if (!probe.virtualPinsUsed().equals(fixed.virtualPinsUsed())) {
            throw new IllegalStateException(
                    "fixed-point replay changed the food virtual-station selection");
        }
        return new FoodAllocation(
                probe.program(), fixed.program(),
                union(probe.virtualPinsUsed(), fixed.virtualPinsUsed()));
    }

    private FoodRun planFood(
            int requested,
            boolean giveOnlyReady,
            OriginLedger ledger) {
        ArrayList<FoodStep> steps = new ArrayList<>();
        int remaining = requested;

        // Origin dominates conversion class: exhaust every usable carried ready/raw/source
        // route before touching persistent Home cargo. A carried hay bale, for example, must
        // beat Home beef+fuel even though raw cooking precedes item-source conversion in the
        // stable within-origin preference order.
        remaining = reserveReadyFood(ledger, remaining, false, steps);

        int cookingOperations = 0;
        boolean sourceCrafting = false;
        // Consume the largest carried raw prefix which the already-carried fuel and ordinary
        // station can actually finish. This is intentionally not all-or-nothing: eight safe
        // coal operations plus nine carried beef need only one Home ready item, not five.
        int carriedRawPrefix = giveOnlyReady ? 0
                : carriedRawExecutablePrefix(ledger, remaining);
        if (carriedRawPrefix > 0) {
            RawFoodPass carriedRaw = consumeRawFood(
                    ledger, carriedRawPrefix, false, steps);
            remaining -= carriedRawPrefix - carriedRaw.remaining();
            cookingOperations = Math.addExact(cookingOperations, carriedRaw.operations());
        }
        boolean carriedSourcesExecutable = !giveOnlyReady && remaining > 0
                && ledger.stationAvailableWithoutHomeCargo("crafting_table");
        if (carriedSourcesExecutable) {
            SourceFoodPass carriedSources = consumeFoodSources(
                    ledger, remaining, false, steps);
            remaining = carriedSources.remaining();
            sourceCrafting |= carriedSources.used();
        }

        remaining = reserveReadyFood(ledger, remaining, true, steps);

        // Once zero-work Home ready cargo has had its strict-work advantage, carried conversion
        // inputs win every equal-work tie. Missing fuel/station dependencies may be selected from
        // Home later; this prevents Home raw/source cargo from displacing an identical carried
        // route merely because the shared dependency was not chosen yet.
        if (!giveOnlyReady && remaining > 0) {
            RawFoodPass carriedRaw = consumeRawFood(ledger, remaining, false, steps);
            remaining = carriedRaw.remaining();
            cookingOperations = Math.addExact(cookingOperations, carriedRaw.operations());
        }
        if (!giveOnlyReady && remaining > 0 && !carriedSourcesExecutable) {
            SourceFoodPass carriedSources = consumeFoodSources(
                    ledger, remaining, false, steps);
            remaining = carriedSources.remaining();
            sourceCrafting |= carriedSources.used();
        }

        if (!giveOnlyReady && remaining > 0) {
            RawFoodPass homeRaw = consumeRawFood(ledger, remaining, true, steps);
            remaining = homeRaw.remaining();
            cookingOperations = Math.addExact(cookingOperations, homeRaw.operations());
        }

        if (!giveOnlyReady && remaining > 0) {
            SourceFoodPass homeSources = consumeFoodSources(
                    ledger, remaining, true, steps);
            remaining = homeSources.remaining();
            sourceCrafting |= homeSources.used();
        }

        int requiredFuel = 0;
        int suppliedFuel = 0;
        List<FoodFuelSlice> fuelSlices = List.of();
        List<FoodStep> normalizedSteps = coalesceRawFoodSteps(steps);
        if (cookingOperations > 0) {
            int rawRuns = (int) normalizedSteps.stream()
                    .filter(step -> step.kind() == FoodSourceKind.RAW)
                    .count();
            int transitionHeadroom = Math.max(0, rawRuns - 1);
            int requiredOperations = Math.addExact(cookingOperations, transitionHeadroom);
            requiredFuel = ceilDiv(requiredOperations, OPERATIONS_PER_COAL);
            FoodFuelSelection fuel = chooseFoodFuel(ledger, requiredOperations, true);
            consumeFuelSelection(ledger, fuel.carried(), false);
            consumeFuelSelection(ledger, fuel.home(), true);
            // FoodProgram exposes regular-fuel-equivalent whole units. Never round a
            // transition-short bundle up: coal8 + charcoal8 has only 15 safe operations,
            // and block80 + coal8 has only 87. The ordinary post-withdraw plan must still
            // see one missing fuel unit instead of accepting an impossible exact cook.
            suppliedFuel = fuel.effectiveOperations() >= requiredOperations
                    ? requiredFuel
                    : Math.min(requiredFuel,
                            fuel.effectiveOperations() / OPERATIONS_PER_COAL);
            fuelSlices = foodFuelSlices(fuel);
            ledger.requireOptionalStation("furnace");
        }
        if (sourceCrafting) ledger.requireOptionalStation("crafting_table");

        LinkedHashSet<String> stations = new LinkedHashSet<>();
        if (cookingOperations > 0) stations.add("furnace");
        if (sourceCrafting) stations.add("crafting_table");
        int ready = requested - remaining;
        return new FoodRun(new FoodProgram(
                requested, normalizedSteps, ready, remaining, cookingOperations,
                requiredFuel, suppliedFuel, fuelSlices, stations), ledger.virtualPinsUsed());
    }

    /**
     * One furnace can process equal raw subtypes as a single contiguous run even when carried and
     * Home origins contributed separate lots. Coalescing first makes transition headroom follow
     * the emitted execution sequence (beef,chicken), never a misleading origin sequence such as
     * beef,chicken,beef.
     */
    private static List<FoodStep> coalesceRawFoodSteps(List<FoodStep> source) {
        ArrayList<FoodStep> ready = new ArrayList<>();
        LinkedHashMap<String, FoodStep> raw = new LinkedHashMap<>();
        ArrayList<FoodStep> itemSources = new ArrayList<>();
        for (FoodStep step : source) {
            switch (step.kind()) {
                case READY -> ready.add(step);
                case ITEM_SOURCE -> itemSources.add(step);
                case RAW -> raw.merge(step.sourceItem(), step, (left, right) -> new FoodStep(
                        FoodSourceKind.RAW,
                        left.readyItem(),
                        left.sourceItem(),
                        Math.addExact(left.inputCount(), right.inputCount()),
                        Math.addExact(left.readyOutputCount(), right.readyOutputCount())));
            }
        }
        ArrayList<FoodStep> result = new ArrayList<>(source.size());
        result.addAll(ready);
        result.addAll(raw.values());
        result.addAll(itemSources);
        return List.copyOf(result);
    }

    private static int carriedRawExecutablePrefix(
            OriginLedger ledger,
            int neededReady) {
        if (neededReady <= 0
                || !ledger.stationAvailableWithoutHomeCargo("furnace")) return 0;
        int operations = 0;
        LinkedHashSet<String> rawItems = new LinkedHashSet<>();
        int remaining = neededReady;
        for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
            if (member.rawItem().isEmpty()) continue;
            int maximum = Math.min(remaining, ledger.availableNonHome(member.rawItem()));
            for (int used = 0; used < maximum; used++) {
                operations = Math.addExact(operations, 1);
                rawItems.add(member.rawItem());
                int requiredOperations = Math.addExact(
                        operations, Math.max(0, rawItems.size() - 1));
                if (chooseFoodFuel(ledger, requiredOperations, false)
                        .effectiveOperations() < requiredOperations) {
                    return operations - 1;
                }
                remaining--;
                if (remaining == 0) return operations;
            }
        }
        return operations;
    }

    private static int reserveReadyFood(
            OriginLedger ledger,
            int remaining,
            boolean homeOnly,
            List<FoodStep> steps) {
        for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
            int available = homeOnly
                    ? ledger.availableHome(member.readyItem())
                    : ledger.availableNonHome(member.readyItem());
            int use = Math.min(remaining, available);
            if (use <= 0) continue;
            ledger.reserveFrom(member.readyItem(), use, Purpose.FOOD_READY, homeOnly);
            steps.add(new FoodStep(FoodSourceKind.READY,
                    member.readyItem(), member.readyItem(), use, use));
            remaining -= use;
            if (remaining == 0) break;
        }
        return remaining;
    }

    private static RawFoodPass consumeRawFood(
            OriginLedger ledger,
            int remaining,
            boolean homeOnly,
            List<FoodStep> steps) {
        int operations = 0;
        for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
            if (member.rawItem().isEmpty()) continue;
            int available = homeOnly
                    ? ledger.availableHome(member.rawItem())
                    : ledger.availableNonHome(member.rawItem());
            int use = Math.min(remaining, available);
            if (use <= 0) continue;
            ledger.consumeFrom(member.rawItem(), use, Purpose.FOOD_RAW, homeOnly);
            steps.add(new FoodStep(FoodSourceKind.RAW,
                    member.readyItem(), member.rawItem(), use, use));
            remaining -= use;
            operations = Math.addExact(operations, use);
            if (remaining == 0) break;
        }
        return new RawFoodPass(remaining, operations);
    }

    private static SourceFoodPass consumeFoodSources(
            OriginLedger ledger,
            int remaining,
            boolean homeOnly,
            List<FoodStep> steps) {
        if (remaining <= 0) return new SourceFoodPass(0, false);
        List<FoodSourceCandidate> candidates = foodSourceCandidates(
                ledger, remaining, homeOnly);
        SourceSelection selection = chooseFoodSources(candidates, remaining);
        if (selection == null) return new SourceFoodPass(remaining, false);
        boolean used = false;
        for (int index = 0; index < candidates.size(); index++) {
            int operations = selection.operations().get(index);
            if (operations <= 0) continue;
            FoodSourceCandidate candidate = candidates.get(index);
            int input = Math.multiplyExact(operations, candidate.source().inputCount());
            int output = Math.multiplyExact(operations, candidate.source().outputCount());
            ledger.consumeFrom(
                    candidate.source().item(), input, Purpose.FOOD_SOURCE, homeOnly);
            steps.add(new FoodStep(FoodSourceKind.ITEM_SOURCE,
                    candidate.member().readyItem(), candidate.source().item(), input, output));
            remaining = Math.max(0, remaining - output);
            used = true;
        }
        return new SourceFoodPass(remaining, used);
    }

    private static List<FoodSourceCandidate> foodSourceCandidates(
            OriginLedger ledger,
            int neededReady,
            boolean homeOnly) {
        ArrayList<FoodSourceCandidate> result = new ArrayList<>();
        for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
            for (FoodFamilyPolicy.ItemSource source : member.itemSources()) {
                int maximum = Math.min(
                        (homeOnly ? ledger.availableHome(source.item())
                                : ledger.availableNonHome(source.item())) / source.inputCount(),
                        ceilDiv(neededReady, source.outputCount()));
                if (maximum > 0) result.add(new FoodSourceCandidate(member, source, maximum));
            }
        }
        return List.copyOf(result);
    }

    /** Bounded deterministic knapsack: least overproduction, then least physical input. */
    private static SourceSelection chooseFoodSources(
            List<FoodSourceCandidate> candidates,
            int neededReady) {
        if (candidates.isEmpty()) return null;
        int maximumOutput = candidates.stream()
                .mapToInt(candidate -> candidate.source().outputCount())
                .max().orElse(1);
        int outputLimit = Math.addExact(neededReady, maximumOutput - 1);
        LinkedHashMap<Integer, SourceSelection> states = new LinkedHashMap<>();
        states.put(0, new SourceSelection(0, 0, 0,
                new ArrayList<>(java.util.Collections.nCopies(candidates.size(), 0))));
        for (int index = 0; index < candidates.size(); index++) {
            FoodSourceCandidate candidate = candidates.get(index);
            LinkedHashMap<Integer, SourceSelection> next = new LinkedHashMap<>(states);
            for (SourceSelection prior : states.values()) {
                for (int operations = 1; operations <= candidate.maximumOperations(); operations++) {
                    int output = Math.min(outputLimit, Math.addExact(
                            prior.output(), Math.multiplyExact(
                                    operations, candidate.source().outputCount())));
                    int inputs = Math.addExact(prior.totalInputs(), Math.multiplyExact(
                            operations, candidate.source().inputCount()));
                    ArrayList<Integer> vector = new ArrayList<>(prior.operations());
                    vector.set(index, operations);
                    SourceSelection offered = new SourceSelection(
                            output, inputs, Math.addExact(prior.totalOperations(), operations),
                            vector);
                    SourceSelection existing = next.get(output);
                    if (existing == null || betterSourceSelection(offered, existing)) {
                        next.put(output, offered);
                    }
                }
            }
            states = next;
        }
        SourceSelection best = null;
        for (SourceSelection candidate : states.values()) {
            if (candidate.output() <= 0) continue;
            if (best == null) {
                best = candidate;
            } else if (candidate.output() >= neededReady && best.output() < neededReady) {
                best = candidate;
            } else if (candidate.output() >= neededReady && best.output() >= neededReady) {
                if (betterFinalSourceSelection(candidate, best, neededReady)) best = candidate;
            } else if (candidate.output() < neededReady && best.output() < neededReady) {
                if (candidate.output() > best.output()
                        || (candidate.output() == best.output()
                        && betterSourceSelection(candidate, best))) best = candidate;
            }
        }
        return best;
    }

    private static FoodFuelSelection chooseFoodFuel(
            OriginLedger ledger,
            int requiredOperations,
            boolean allowHome) {
        if (requiredOperations <= 0) return FoodFuelSelection.EMPTY;
        int carriedCoal = ledger.availableNonHome(COAL);
        int carriedCharcoal = ledger.availableNonHome(CHARCOAL);
        int carriedBlocks = ledger.availableNonHome(COAL_BLOCK);
        int homeCoal = allowHome ? ledger.availableHome(COAL) : 0;
        int homeCharcoal = allowHome ? ledger.availableHome(CHARCOAL) : 0;
        int homeBlocks = allowHome ? ledger.availableHome(COAL_BLOCK) : 0;
        int coalAvailable = Math.addExact(carriedCoal, homeCoal);
        int charcoalAvailable = Math.addExact(carriedCharcoal, homeCharcoal);
        int blockAvailable = Math.addExact(carriedBlocks, homeBlocks);
        FoodFuelSelection best = FoodFuelSelection.EMPTY;
        int blockLimit = Math.min(blockAvailable,
                Math.addExact(ceilDiv(requiredOperations, OPERATIONS_PER_COAL_BLOCK), 1));
        int regularAvailable = Math.addExact(coalAvailable, charcoalAvailable);
        int regularLimit = Math.min(regularAvailable,
                Math.addExact(ceilDiv(requiredOperations, OPERATIONS_PER_COAL), 1));
        for (int blocks = 0; blocks <= blockLimit; blocks++) {
            for (int regular = 0; regular <= regularLimit; regular++) {
                int minimumCoal = Math.max(0, regular - charcoalAvailable);
                int maximumCoal = Math.min(coalAvailable, regular);
                for (int coal = minimumCoal; coal <= maximumCoal; coal++) {
                    int charcoal = regular - coal;
                    int types = (blocks > 0 ? 1 : 0)
                            + (coal > 0 ? 1 : 0) + (charcoal > 0 ? 1 : 0);
                    int rawCapacity = Math.addExact(
                            Math.multiplyExact(blocks, OPERATIONS_PER_COAL_BLOCK),
                            Math.multiplyExact(regular, OPERATIONS_PER_COAL));
                    int effective = Math.max(0, rawCapacity - Math.max(0, types - 1));

                    int carriedBlockUse = Math.min(blocks, carriedBlocks);
                    int carriedCoalUse = Math.min(coal, carriedCoal);
                    int carriedCharcoalUse = Math.min(charcoal, carriedCharcoal);
                    FuelSelection carried = new FuelSelection(
                            carriedCoalUse, carriedCharcoalUse, carriedBlockUse);
                    FuelSelection home = new FuelSelection(
                            coal - carriedCoalUse,
                            charcoal - carriedCharcoalUse,
                            blocks - carriedBlockUse);
                    FoodFuelSelection candidate = new FoodFuelSelection(
                            carried, home, effective);
                    if (betterFoodFuel(candidate, best, requiredOperations)) best = candidate;
                }
            }
        }
        return best;
    }

    private static boolean betterFoodFuel(
            FoodFuelSelection offered,
            FoodFuelSelection current,
            int requiredOperations) {
        boolean offeredComplete = offered.effectiveOperations() >= requiredOperations;
        boolean currentComplete = current.effectiveOperations() >= requiredOperations;
        if (offeredComplete != currentComplete) return offeredComplete;
        if (!offeredComplete
                && offered.effectiveOperations() != current.effectiveOperations()) {
            return offered.effectiveOperations() > current.effectiveOperations();
        }
        if (offeredComplete && offered.home().itemCount() != current.home().itemCount()) {
            return offered.home().itemCount() < current.home().itemCount();
        }
        if (offered.itemCount() != current.itemCount()) {
            return offered.itemCount() < current.itemCount();
        }
        int offeredExcess = Math.max(0, offered.effectiveOperations() - requiredOperations);
        int currentExcess = Math.max(0, current.effectiveOperations() - requiredOperations);
        if (offeredExcess != currentExcess) return offeredExcess < currentExcess;
        if (offered.home().itemCount() != current.home().itemCount()) {
            return offered.home().itemCount() < current.home().itemCount();
        }
        if (offered.coalBlocks() != current.coalBlocks()) {
            return offered.coalBlocks() > current.coalBlocks();
        }
        return offered.charcoal() < current.charcoal();
    }

    private static void consumeFuelSelection(
            OriginLedger ledger,
            FuelSelection selection,
            boolean homeOnly) {
        if (selection.coal() > 0) {
            ledger.consumeFrom(COAL, selection.coal(), Purpose.FOOD_FUEL, homeOnly);
        }
        if (selection.charcoal() > 0) {
            ledger.consumeFrom(
                    CHARCOAL, selection.charcoal(), Purpose.FOOD_FUEL, homeOnly);
        }
        if (selection.coalBlocks() > 0) {
            ledger.consumeFrom(
                    COAL_BLOCK, selection.coalBlocks(), Purpose.FOOD_FUEL, homeOnly);
        }
    }

    private static List<FoodFuelSlice> foodFuelSlices(FoodFuelSelection selection) {
        int blocks = selection.coalBlocks();
        int coal = Math.addExact(selection.carried().coal(), selection.home().coal());
        int charcoal = Math.addExact(
                selection.carried().charcoal(), selection.home().charcoal());
        ArrayList<FoodFuelSlice> result = new ArrayList<>();
        if (blocks > 0) {
            result.add(new FoodFuelSlice(COAL_BLOCK, blocks, OPERATIONS_PER_COAL_BLOCK));
        }
        if (coal > 0) result.add(new FoodFuelSlice(COAL, coal, OPERATIONS_PER_COAL));
        if (charcoal > 0) {
            result.add(new FoodFuelSlice(CHARCOAL, charcoal, OPERATIONS_PER_COAL));
        }
        return List.copyOf(result);
    }

    private static boolean betterSourceSelection(
            SourceSelection candidate,
            SourceSelection existing) {
        if (candidate.totalInputs() != existing.totalInputs()) {
            return candidate.totalInputs() < existing.totalInputs();
        }
        if (candidate.totalOperations() != existing.totalOperations()) {
            return candidate.totalOperations() < existing.totalOperations();
        }
        return earlierSourceVector(candidate.operations(), existing.operations());
    }

    private static boolean betterFinalSourceSelection(
            SourceSelection candidate,
            SourceSelection existing,
            int needed) {
        int candidateWaste = candidate.output() - needed;
        int existingWaste = existing.output() - needed;
        if (candidateWaste != existingWaste) return candidateWaste < existingWaste;
        return betterSourceSelection(candidate, existing);
    }

    private static boolean earlierSourceVector(List<Integer> left, List<Integer> right) {
        for (int index = 0; index < left.size(); index++) {
            int compared = Integer.compare(left.get(index), right.get(index));
            if (compared != 0) return compared > 0;
        }
        return false;
    }

    private Replay replay(
            AcquisitionPlan plan,
            Map<String, Integer> carried,
            Map<String, Integer> carriedDurability,
            Set<String> availableStations,
            List<HomeStack> home,
            List<VirtualPin> pins,
            List<AcquisitionRequest.ItemGoal> finalGoals,
            UsageTracker usage) {
        OriginLedger ledger = new OriginLedger(usage, pins);
        ledger.addReusableStationCapabilities(availableStations);
        ledger.addCarried(carried, carriedDurability);
        ledger.addHome(home, 0);
        LinkedHashMap<String, Integer> finalFloors = new LinkedHashMap<>();
        finalGoals.forEach(goal -> finalFloors.merge(
                goal.item(), goal.count(), Math::addExact));

        for (ResourcePlanner.Action action : plan.actions()) {
            switch (action.kind()) {
                case MINE -> {
                    String tool = action.parameters().get("plannedTool");
                    if (tool != null) {
                        ledger.require(tool, 1, Purpose.TOOL);
                        ledger.consumeDurability(
                                tool, action.count(), finalFloors.getOrDefault(tool, 0));
                    }
                    ledger.addProduced(action.target(), intParameter(action, "expectedMinimumItems"));
                }
                case HUNT -> ledger.addProduced(
                        action.target(), intParameter(action, "expectedMinimumItems"));
                case EQUIP -> ledger.require(action.target(), 1, Purpose.TOOL);
                case DELIVER -> ledger.consume(action.target(), action.count(), Purpose.FINAL);
                case CRAFT, SMELT, FILL_WATER -> {
                    String station = action.parameters().get("workstation");
                    if (station != null) ledger.requireStation(station);
                    parseCounts(action.parameters().get("ingredients")).forEach(
                            (item, count) -> ledger.consume(item, count, Purpose.INGREDIENT));
                    if (action.kind() == ResourcePlanner.ActionKind.SMELT) {
                        int fuel = nonNegativeParameter(action, "fuelCount", 0);
                        if (fuel > 0) ledger.consume(
                                action.parameters().getOrDefault("fuelItem", COAL),
                                fuel, Purpose.FUEL);
                    }
                    int output = intParameter(action, "totalOutput");
                    ledger.addProduced(action.target(), output);
                }
            }
        }

        Map<String, Integer> projected = ledger.nonVirtualCounts();
        for (AcquisitionRequest.ItemGoal goal : finalGoals) {
            ledger.reserve(goal.item(), goal.count(), Purpose.FINAL);
        }
        return new Replay(projected, ledger.virtualPinsUsed());
    }

    private AcquisitionRequest compilerRequest(
            Normalized request,
            List<HomeStack> includedHome,
            List<VirtualPin> includedPins) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>(request.carriedCounts());
        for (HomeStack stack : includedHome) {
            if (stack.pristine()) counts.merge(stack.item(), stack.count(), Math::addExact);
        }

        LinkedHashMap<String, Integer> durability = new LinkedHashMap<>(request.carriedDurability());
        for (HomeStack stack : includedHome) {
            if (stack.pristine() && stack.usablePickaxeDurability() > 0) {
                durability.merge(
                        stack.item(), stack.usablePickaxeDurability(), Math::addExact);
            }
        }
        LinkedHashSet<String> availableStations =
                new LinkedHashSet<>(request.availableStations());
        for (VirtualPin pin : includedPins) {
            if (pin.verified()) availableStations.add(pin.item());
        }
        return new AcquisitionRequest(
                request.exactGoals(),
                new ResourcePlanner.InventoryView(
                        counts, request.equippedItems(), request.locallyAvailableItems(),
                        availableStations),
                durability, request.unavailableHarvestSources());
    }

    private Normalized normalize(Request request) {
        ResourceCatalog catalog = planner.catalog();
        LinkedHashMap<String, Integer> carried = new LinkedHashMap<>();
        request.carried().itemCounts().forEach((raw, count) -> {
            if (count > 0) carried.merge(catalog.normalizeExactItem(raw), count, Math::addExact);
        });
        LinkedHashSet<String> equipped = new LinkedHashSet<>();
        request.carried().equippedItems().forEach(
                raw -> equipped.add(catalog.normalizeExactItem(raw)));
        LinkedHashSet<String> local = new LinkedHashSet<>();
        request.carried().locallyAvailableItems().forEach(
                raw -> local.add(catalog.normalizeExactItem(raw)));
        LinkedHashSet<String> availableStations = new LinkedHashSet<>();
        request.carried().availableStations().forEach(
                raw -> availableStations.add(catalog.normalizeExactItem(raw)));

        LinkedHashMap<String, Integer> suppliedDurability = new LinkedHashMap<>();
        request.carriedUsablePickaxeDurability().forEach((raw, amount) ->
                suppliedDurability.merge(catalog.normalizeExactItem(raw), amount, Math::addExact));
        LinkedHashMap<String, Integer> carriedDurability = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> profile : PICKAXE_DURABILITY.entrySet()) {
            int count = carried.getOrDefault(profile.getKey(), 0);
            int maximum = Math.multiplyExact(profile.getValue(), count);
            int usable = suppliedDurability.containsKey(profile.getKey())
                    ? suppliedDurability.get(profile.getKey()) : maximum;
            if (usable < 0 || usable > maximum) {
                throw new IllegalArgumentException(
                        "carried pickaxe durability exceeds carried whole-tool capacity for "
                                + profile.getKey());
            }
            if (count > 0) carriedDurability.put(profile.getKey(), usable);
        }
        for (String item : suppliedDurability.keySet()) {
            if (!PICKAXE_DURABILITY.containsKey(item)) {
                throw new IllegalArgumentException(
                        "durability is accepted only for a supported carried pickaxe");
            }
        }
        // Positive aggregate durability does not reveal stack packing. Preserve the exact
        // observed physical count (two half-used picks are still two serviceable items); only an
        // explicit zero proves that none of those reported tool units can be used.
        for (Map.Entry<String, Integer> profile : PICKAXE_DURABILITY.entrySet()) {
            int count = carried.getOrDefault(profile.getKey(), 0);
            if (count <= 0) continue;
            if (carriedDurability.getOrDefault(profile.getKey(), 0) == 0) {
                carried.remove(profile.getKey());
                equipped.remove(profile.getKey());
            }
        }

        ArrayList<HomeStack> home = new ArrayList<>();
        LinkedHashSet<String> stackIds = new LinkedHashSet<>();
        for (HomeStack raw : request.homeStacks()) {
            String item = catalog.normalizeExactItem(raw.item());
            HomeStack normalized = new HomeStack(
                    raw.stackId(), item, raw.count(), raw.pristine(),
                    raw.usablePickaxeDurability());
            if (!stackIds.add(normalized.stackId())) {
                throw new IllegalArgumentException(
                        "duplicate Home stack identity " + normalized.stackId());
            }
            if (normalized.pristine()) home.add(normalized);
        }
        home.sort(Comparator.comparing(HomeStack::stackId));

        ArrayList<VirtualPin> pins = new ArrayList<>();
        LinkedHashSet<String> pinIds = new LinkedHashSet<>();
        for (VirtualPin raw : request.virtualPins()) {
            VirtualPin normalized = new VirtualPin(
                    raw.pinId(), catalog.normalizeExactItem(raw.item()), raw.verified());
            if (!pinIds.add(normalized.pinId())) {
                throw new IllegalArgumentException(
                        "duplicate Home pin identity " + normalized.pinId());
            }
            if (normalized.verified()) pins.add(normalized);
        }
        pins.sort(Comparator.comparing(VirtualPin::pinId));

        LinkedHashMap<String, Integer> exactFloors = new LinkedHashMap<>();
        int food = 0;
        for (AcquisitionRequest.ItemGoal goal : request.goals()) {
            Objects.requireNonNull(goal, "goal");
            if (FoodFamilyPolicy.isFamilyRequest(goal.item())) {
                food = Math.addExact(food, goal.count());
            } else {
                String item = catalog.resolveUniversalItem(goal.item());
                exactFloors.merge(item, goal.count(), Math::addExact);
            }
        }
        ArrayList<AcquisitionRequest.ItemGoal> exactGoals = new ArrayList<>();
        exactFloors.forEach((item, count) ->
                exactGoals.add(new AcquisitionRequest.ItemGoal(item, count)));
        return new Normalized(
                List.copyOf(exactGoals), Collections.unmodifiableMap(exactFloors), food,
                Collections.unmodifiableMap(carried), Collections.unmodifiableMap(carriedDurability),
                Collections.unmodifiableSet(equipped), Collections.unmodifiableSet(local),
                Collections.unmodifiableSet(availableStations),
                List.copyOf(home), List.copyOf(pins), request.unavailableHarvestSources());
    }

    private Allocation result(
            Outcome outcome,
            Normalized request,
            UsageTracker selected,
            Set<String> pinsUsed,
            Map<String, Integer> exactReservations,
            Map<String, Integer> postExact,
            Optional<FoodProgram> food,
            Optional<AcquisitionPlan> probe,
            Optional<AcquisitionPlan> fixed,
            String reason) {
        return new Allocation(
                outcome,
                selected.withdrawals(request.homeStacks()),
                selected.allocatedCounts(request.homeStacks()),
                selected.consumedByItem(),
                selected.retainedByItem(),
                selected.durabilityByItem(),
                pinsUsed,
                exactReservations,
                postExact,
                food,
                probe,
                fixed,
                reason);
    }

    private static Map<String, Integer> subtractReservations(
            Map<String, Integer> inventory,
            Map<String, Integer> reservations) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>(inventory);
        reservations.forEach((item, amount) -> {
            int before = result.getOrDefault(item, 0);
            if (before < amount) {
                throw new IllegalStateException(
                        "missing reserved final " + item + "=" + amount + " from " + before);
            }
            if (before == amount) result.remove(item);
            else result.put(item, before - amount);
        });
        return Collections.unmodifiableMap(result);
    }

    private static List<HomeStack> selectedStacks(
            List<HomeStack> source,
            UsageTracker usage) {
        Map<String, Integer> selected = usage.highWaterByStack();
        ArrayList<HomeStack> result = new ArrayList<>();
        for (HomeStack stack : source) {
            int count = selected.getOrDefault(stack.stackId(), 0);
            if (count <= 0) continue;
            result.add(prefix(stack, count));
        }
        return List.copyOf(result);
    }

    private static List<HomeStack> remainingStacks(
            List<HomeStack> source,
            Map<String, Integer> selected) {
        ArrayList<HomeStack> result = new ArrayList<>();
        for (HomeStack stack : source) {
            int used = selected.getOrDefault(stack.stackId(), 0);
            if (used < 0 || used > stack.count()) {
                throw new IllegalStateException("Home stack allocation exceeded observed count");
            }
            int remaining = stack.count() - used;
            if (remaining <= 0) continue;
            result.add(prefix(stack, remaining));
        }
        return List.copyOf(result);
    }

    private static List<HomeStack> selectedStackDelta(
            List<HomeStack> source,
            Map<String, Integer> before,
            Map<String, Integer> after) {
        ArrayList<HomeStack> result = new ArrayList<>();
        for (HomeStack stack : source) {
            int start = before.getOrDefault(stack.stackId(), 0);
            int end = after.getOrDefault(stack.stackId(), 0);
            if (end < start || end > stack.count()) {
                throw new IllegalStateException("Home food allocation is not a stack prefix");
            }
            if (end > start) result.add(prefix(stack, end - start));
        }
        return List.copyOf(result);
    }

    private static HomeStack prefix(HomeStack stack, int count) {
        Integer durability = pickaxeDurability(stack.item());
        return new HomeStack(
                stack.stackId(), stack.item(), count, true,
                durability == null ? 0 : Math.multiplyExact(durability, count));
    }

    private static void requireExactSelection(
            List<HomeStack> selected,
            UsageTracker verification) {
        Map<String, Integer> selectedCounts = new LinkedHashMap<>();
        selected.forEach(stack -> selectedCounts.put(stack.stackId(), stack.count()));
        Map<String, Integer> replayed = verification.highWaterByStack();
        for (Map.Entry<String, Integer> entry : selectedCounts.entrySet()) {
            if (!entry.getValue().equals(replayed.getOrDefault(entry.getKey(), 0))) {
                throw new IllegalStateException(
                        "fixed-point replay changed selected Home stack " + entry.getKey());
            }
        }
        for (String stackId : replayed.keySet()) {
            if (!selectedCounts.containsKey(stackId)) {
                throw new IllegalStateException(
                        "fixed-point replay selected an unallocated Home stack " + stackId);
            }
        }
    }

    private static int intParameter(ResourcePlanner.Action action, String key) {
        String value = action.parameters().get(key);
        if (value == null) throw new IllegalStateException("missing action parameter " + key);
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) throw new IllegalStateException(key + " must be positive");
        return parsed;
    }

    private static int nonNegativeParameter(
            ResourcePlanner.Action action,
            String key,
            int fallback) {
        String value = action.parameters().get(key);
        if (value == null) return fallback;
        int parsed = Integer.parseInt(value);
        if (parsed < 0) throw new IllegalStateException(key + " cannot be negative");
        return parsed;
    }

    private static Map<String, Integer> parseCounts(String encoded) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return result;
        for (String entry : encoded.split(",")) {
            int separator = entry.lastIndexOf('=');
            if (separator <= 0) {
                throw new IllegalStateException("invalid action item accounting: " + entry);
            }
            int count = Integer.parseInt(entry.substring(separator + 1));
            if (count <= 0) throw new IllegalStateException("action item count must be positive");
            result.merge(entry.substring(0, separator), count, Math::addExact);
        }
        return result;
    }

    private static int ceilDiv(int numerator, int denominator) {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("ceilDiv operands must be positive");
        }
        return Math.toIntExact(((long) numerator + denominator - 1L) / denominator);
    }

    private static Integer pickaxeDurability(String rawItem) {
        if (rawItem == null) return null;
        String item = rawItem.trim().toLowerCase(java.util.Locale.ROOT);
        while (item.startsWith("minecraft:")) item = item.substring("minecraft:".length());
        return PICKAXE_DURABILITY.get(item.replace('-', '_').replace(' ', '_'));
    }

    private static <T> Set<T> union(Set<T> first, Set<T> second) {
        LinkedHashSet<T> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return Collections.unmodifiableSet(result);
    }

    private static Map<String, Integer> immutableNonNegative(
            Map<String, Integer> source,
            String field) {
        Objects.requireNonNull(source, field);
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String checked = requireText(key, field + " key");
            if (value == null || value < 0) {
                throw new IllegalArgumentException(field + " contains an invalid count for " + checked);
            }
            if (value > 0) result.put(checked, value);
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Integer> immutableNonNegativeIncludingZero(
            Map<String, Integer> source,
            String field) {
        Objects.requireNonNull(source, field);
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String checked = requireText(key, field + " key");
            if (value == null || value < 0) {
                throw new IllegalArgumentException(field + " contains an invalid count for " + checked);
            }
            result.put(checked, value);
        });
        return Collections.unmodifiableMap(result);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }

    private static String requireOpaqueId(String value, String field) {
        String checked = requireText(value, field);
        for (int index = 0; index < checked.length(); index++) {
            if (Character.isISOControl(checked.charAt(index))) {
                throw new IllegalArgumentException(field + " contains control characters");
            }
        }
        return checked;
    }

    private record Normalized(
            List<AcquisitionRequest.ItemGoal> exactGoals,
            Map<String, Integer> exactGoalFloors,
            int foodGoalCount,
            Map<String, Integer> carriedCounts,
            Map<String, Integer> carriedDurability,
            Set<String> equippedItems,
            Set<String> locallyAvailableItems,
            Set<String> availableStations,
            List<HomeStack> homeStacks,
            List<VirtualPin> virtualPins,
            Set<String> unavailableHarvestSources) {
    }

    private record ExactAllocation(
            AcquisitionPlan probe,
            AcquisitionPlan fixed,
            Map<String, Integer> projectedNonVirtual,
            Set<String> virtualPinsUsed) {
    }

    private record GiveAllocation(Map<String, Integer> projectedNonVirtual) {
        private GiveAllocation {
            projectedNonVirtual = Collections.unmodifiableMap(
                    new LinkedHashMap<>(projectedNonVirtual));
        }
    }

    private record CompiledWithPins(
            AcquisitionPlan plan,
            List<VirtualPin> pins) {
        private CompiledWithPins {
            plan = Objects.requireNonNull(plan, "plan");
            pins = List.copyOf(pins);
        }
    }

    private record Replay(
            Map<String, Integer> projectedNonVirtual,
            Set<String> virtualPinsUsed) {
    }

    private record FoodRun(FoodProgram program, Set<String> virtualPinsUsed) {
    }

    private record RawFoodPass(int remaining, int operations) {
    }

    private record SourceFoodPass(int remaining, boolean used) {
    }

    private record FuelSelection(
            int coal,
            int charcoal,
            int coalBlocks) {
        private static final FuelSelection EMPTY = new FuelSelection(0, 0, 0);

        private FuelSelection {
            if (coal < 0 || charcoal < 0 || coalBlocks < 0) {
                throw new IllegalArgumentException("invalid fuel selection");
            }
        }

        private int itemCount() {
            return Math.addExact(Math.addExact(coal, charcoal), coalBlocks);
        }
    }

    private record FoodFuelSelection(
            FuelSelection carried,
            FuelSelection home,
            int effectiveOperations) {
        private static final FoodFuelSelection EMPTY = new FoodFuelSelection(
                FuelSelection.EMPTY, FuelSelection.EMPTY, 0);

        private FoodFuelSelection {
            carried = Objects.requireNonNull(carried, "carried");
            home = Objects.requireNonNull(home, "home");
            if (effectiveOperations < 0) {
                throw new IllegalArgumentException("effective fuel operations cannot be negative");
            }
        }

        private int itemCount() {
            return Math.addExact(carried.itemCount(), home.itemCount());
        }

        private int coalBlocks() {
            return Math.addExact(carried.coalBlocks(), home.coalBlocks());
        }

        private int charcoal() {
            return Math.addExact(carried.charcoal(), home.charcoal());
        }
    }

    private record PlanCost(
            int minedBlocks,
            int huntedTargets,
            int smeltOperations,
            int craftOperations,
            int deliveredItems,
            int equipActions,
            int actionCount) implements Comparable<PlanCost> {
        @Override
        public int compareTo(PlanCost other) {
            int compared = Integer.compare(minedBlocks, other.minedBlocks);
            if (compared != 0) return compared;
            compared = Integer.compare(huntedTargets, other.huntedTargets);
            if (compared != 0) return compared;
            compared = Integer.compare(smeltOperations, other.smeltOperations);
            if (compared != 0) return compared;
            compared = Integer.compare(craftOperations, other.craftOperations);
            if (compared != 0) return compared;
            compared = Integer.compare(deliveredItems, other.deliveredItems);
            if (compared != 0) return compared;
            compared = Integer.compare(equipActions, other.equipActions);
            if (compared != 0) return compared;
            return Integer.compare(actionCount, other.actionCount);
        }
    }

    private record FoodSourceCandidate(
            FoodFamilyPolicy.Member member,
            FoodFamilyPolicy.ItemSource source,
            int maximumOperations) {
        private FoodSourceCandidate {
            member = Objects.requireNonNull(member, "member");
            source = Objects.requireNonNull(source, "source");
            if (maximumOperations <= 0) {
                throw new IllegalArgumentException("food source maximum must be positive");
            }
        }
    }

    private record SourceSelection(
            int output,
            int totalInputs,
            int totalOperations,
            List<Integer> operations) {
        private SourceSelection {
            if (output < 0 || totalInputs < 0 || totalOperations < 0) {
                throw new IllegalArgumentException("invalid food source selection");
            }
            operations = List.copyOf(operations);
        }
    }

    private record FoodAllocation(
            FoodProgram probe,
            FoodProgram fixed,
            Set<String> virtualPinsUsed) {
    }

    private enum Origin {
        CARRIED,
        PRODUCED,
        HOME
    }

    private static final class ItemLot {
        private final Origin origin;
        private final String stackId;
        private final String item;
        private final int homeOffset;
        private final int initial;
        private int remaining;

        private ItemLot(
                Origin origin,
                String stackId,
                String item,
                int amount,
                int homeOffset) {
            this.origin = origin;
            this.stackId = stackId;
            this.item = item;
            this.homeOffset = homeOffset;
            this.initial = amount;
            this.remaining = amount;
        }

        private int used() {
            return initial - remaining;
        }
    }

    private static final class DurabilityLot {
        private final Origin origin;
        private final String stackId;
        private final String item;
        private final int homeOffset;
        private final int durabilityPerUnit;
        private int physicalUnits;
        private final int initial;
        private int remaining;

        private DurabilityLot(
                Origin origin,
                String stackId,
                String item,
                int amount,
                int homeOffset,
                int durabilityPerUnit,
                int physicalUnits) {
            if (physicalUnits <= 0 || amount < physicalUnits) {
                throw new IllegalArgumentException(
                        "durability lot must preserve one point per physical tool");
            }
            this.origin = origin;
            this.stackId = stackId;
            this.item = item;
            this.homeOffset = homeOffset;
            this.durabilityPerUnit = durabilityPerUnit;
            this.physicalUnits = physicalUnits;
            this.initial = amount;
            this.remaining = amount;
        }
    }

    /** Origin queues spend carried/committed surplus before touching persistent Home stock. */
    private static final class OriginLedger {
        private final LinkedHashMap<String, ArrayList<ItemLot>> items = new LinkedHashMap<>();
        private final LinkedHashMap<String, ArrayList<DurabilityLot>> durability =
                new LinkedHashMap<>();
        private final UsageTracker usage;
        private final LinkedHashMap<String, List<VirtualPin>> pinsByItem = new LinkedHashMap<>();
        private final LinkedHashSet<String> pinsUsed = new LinkedHashSet<>();
        private final LinkedHashSet<String> reusableStationCapabilities = new LinkedHashSet<>();

        private OriginLedger(UsageTracker usage, List<VirtualPin> pins) {
            this.usage = usage;
            for (VirtualPin pin : pins) {
                if (pin.verified()) {
                    pinsByItem.computeIfAbsent(pin.item(), ignored -> new ArrayList<>()).add(pin);
                }
            }
        }

        private void addCarried(
                Map<String, Integer> counts,
                Map<String, Integer> usableDurability) {
            counts.forEach((item, count) -> addItem(Origin.CARRIED, null, item, count, 0));
            usableDurability.forEach((item, amount) -> {
                Integer perUnit = pickaxeDurability(item);
                if (amount > 0 && perUnit != null) {
                    addDurability(Origin.CARRIED, null, item, amount, 0, perUnit,
                            counts.getOrDefault(item, 0));
                }
            });
        }

        private void addReusableStationCapabilities(Set<String> stations) {
            reusableStationCapabilities.addAll(stations);
        }

        private void addHome(List<HomeStack> stacks, int sharedOffset) {
            for (HomeStack stack : stacks) {
                addItem(Origin.HOME, stack.stackId(), stack.item(), stack.count(), sharedOffset);
                Integer perUnit = pickaxeDurability(stack.item());
                if (perUnit != null && stack.usablePickaxeDurability() > 0) {
                    addDurability(Origin.HOME, stack.stackId(), stack.item(),
                            stack.usablePickaxeDurability(), sharedOffset, perUnit, stack.count());
                }
            }
        }

        private void addHome(List<HomeStack> stacks, Map<String, Integer> offsets) {
            for (HomeStack stack : stacks) {
                int offset = offsets.getOrDefault(stack.stackId(), 0);
                addItem(Origin.HOME, stack.stackId(), stack.item(), stack.count(), offset);
                Integer perUnit = pickaxeDurability(stack.item());
                if (perUnit != null && stack.usablePickaxeDurability() > 0) {
                    addDurability(Origin.HOME, stack.stackId(), stack.item(),
                            stack.usablePickaxeDurability(), offset, perUnit, stack.count());
                }
            }
        }

        private void addProduced(String item, int count) {
            addItem(Origin.PRODUCED, null, item, count, 0);
            Integer perUnit = pickaxeDurability(item);
            if (perUnit != null) {
                addDurability(Origin.PRODUCED, null, item,
                        Math.multiplyExact(perUnit, count), 0, perUnit, count);
            }
        }

        private void addItem(
                Origin origin,
                String stackId,
                String item,
                int count,
                int offset) {
            if (count <= 0) return;
            items.computeIfAbsent(item, ignored -> new ArrayList<>())
                    .add(new ItemLot(origin, stackId, item, count, offset));
        }

        private void addDurability(
                Origin origin,
                String stackId,
                String item,
                int amount,
                int offset,
                int perUnit,
                int physicalUnits) {
            durability.computeIfAbsent(item, ignored -> new ArrayList<>())
                    .add(new DurabilityLot(
                            origin, stackId, item, amount, offset, perUnit, physicalUnits));
        }

        private int available(String item) {
            int result = 0;
            for (ItemLot lot : items.getOrDefault(item, new ArrayList<>())) {
                result = Math.addExact(result, lot.remaining);
            }
            return result;
        }

        private int availableNonHome(String item) {
            return availableFrom(item, false);
        }

        private int availableHome(String item) {
            return availableFrom(item, true);
        }

        private int availableFrom(String item, boolean homeOnly) {
            int result = 0;
            for (ItemLot lot : items.getOrDefault(item, new ArrayList<>())) {
                if ((lot.origin == Origin.HOME) == homeOnly) {
                    result = Math.addExact(result, lot.remaining);
                }
            }
            return result;
        }

        private void consume(String item, int amount, Purpose purpose) {
            transfer(item, amount, purpose, true, false);
        }

        private void reserve(String item, int amount, Purpose purpose) {
            transfer(item, amount, purpose, true, true);
        }

        private void consumeFrom(
                String item,
                int amount,
                Purpose purpose,
                boolean homeOnly) {
            transferFrom(item, amount, purpose, true, false, homeOnly);
        }

        private void reserveFrom(
                String item,
                int amount,
                Purpose purpose,
                boolean homeOnly) {
            transferFrom(item, amount, purpose, true, true, homeOnly);
        }

        /** GIVE may reserve only the currently available final prefix and leave the deficit normal. */
        private void reserveAvailable(String item, int amount, Purpose purpose) {
            int use = Math.min(amount, available(item));
            if (use > 0) reserve(item, use, purpose);
        }

        private void require(String item, int amount, Purpose purpose) {
            transfer(item, amount, purpose, false, false);
        }

        private void transfer(
                String item,
                int amount,
                Purpose purpose,
                boolean remove,
                boolean retained) {
            if (amount <= 0) throw new IllegalArgumentException("required amount must be positive");
            int remaining = amount;
            for (Origin origin : Origin.values()) {
                for (ItemLot lot : items.getOrDefault(item, new ArrayList<>())) {
                    if (lot.origin != origin || lot.remaining <= 0) continue;
                    int use = Math.min(remaining, lot.remaining);
                    if (lot.origin == Origin.HOME) {
                        int end = Math.addExact(lot.homeOffset, Math.addExact(lot.used(), use));
                        usage.mark(lot.stackId, lot.item, end, purpose);
                        if (retained) usage.retained(lot.item, use);
                        else if (remove) usage.consumed(lot.item, use);
                    }
                    if (remove) {
                        lot.remaining -= use;
                        retireDurability(lot, use);
                    }
                    remaining -= use;
                    if (remaining == 0) return;
                }
            }
            throw new IllegalStateException("origin replay lacks " + remaining + " " + item);
        }

        private void transferFrom(
                String item,
                int amount,
                Purpose purpose,
                boolean remove,
                boolean retained,
                boolean homeOnly) {
            if (amount <= 0) throw new IllegalArgumentException("required amount must be positive");
            int remaining = amount;
            for (Origin origin : Origin.values()) {
                if ((origin == Origin.HOME) != homeOnly) continue;
                for (ItemLot lot : items.getOrDefault(item, new ArrayList<>())) {
                    if (lot.origin != origin || lot.remaining <= 0) continue;
                    int use = Math.min(remaining, lot.remaining);
                    if (lot.origin == Origin.HOME) {
                        int end = Math.addExact(lot.homeOffset, Math.addExact(lot.used(), use));
                        usage.mark(lot.stackId, lot.item, end, purpose);
                        if (retained) usage.retained(lot.item, use);
                        else if (remove) usage.consumed(lot.item, use);
                    }
                    if (remove) {
                        lot.remaining -= use;
                        retireDurability(lot, use);
                    }
                    remaining -= use;
                    if (remaining == 0) return;
                }
            }
            throw new IllegalStateException("origin replay lacks " + remaining + " " + item);
        }

        private void retireDurability(ItemLot itemLot, int physicalUnits) {
            Integer perUnit = pickaxeDurability(itemLot.item);
            if (perUnit == null || physicalUnits <= 0) return;
            int remainingUnits = physicalUnits;
            for (DurabilityLot lot : durability.getOrDefault(
                    itemLot.item, new ArrayList<>())) {
                if (lot.origin != itemLot.origin
                        || !Objects.equals(lot.stackId, itemLot.stackId)
                        || lot.homeOffset != itemLot.homeOffset
                        || lot.physicalUnits <= 0) {
                    continue;
                }
                int removedUnits = Math.min(remainingUnits, lot.physicalUnits);
                int afterUnits = lot.physicalUnits - removedUnits;
                // The input carries aggregate durability for non-Home stacks, so assume a recipe
                // consumed the healthiest matching physical units. Exact Home stack IDs make the
                // same operation precise for withdrawn tools. Never leave durability without its
                // owning item, and preserve one non-spendable point for each surviving unit.
                int maximumRemovable = Math.max(0, lot.remaining - afterUnits);
                int removedDurability = Math.min(maximumRemovable,
                        Math.multiplyExact(removedUnits, lot.durabilityPerUnit));
                lot.remaining -= removedDurability;
                lot.physicalUnits = afterUnits;
                remainingUnits -= removedUnits;
                if (remainingUnits == 0) return;
            }
            // A count without an explicit durability lot is legal (the caller may know only that
            // the tool exists), so there is nothing further to revoke. Any later durability-
            // qualified action will fail closed or select another exact lot.
        }

        private void consumeDurability(String item, int amount, int retainedToolFloor) {
            if (amount <= 0) throw new IllegalArgumentException("durability use must be positive");
            if (retainedToolFloor < 0) {
                throw new IllegalArgumentException("retained tool floor cannot be negative");
            }
            int physicalTools = durability.getOrDefault(item, new ArrayList<>()).stream()
                    .mapToInt(lot -> lot.physicalUnits)
                    .sum();
            if (physicalTools < retainedToolFloor) {
                throw new IllegalStateException(
                        "origin replay lacks the retained physical tool floor for " + item);
            }
            int remaining = amount;
            remaining -= spendDurabilityFrom(item, remaining, false);
            if (remaining > 0) remaining -= spendDurabilityFrom(item, remaining, true);
            if (remaining != 0) {
                throw new IllegalStateException(
                        "origin replay lacks " + remaining + " durability for " + item);
            }
        }

        private int spendDurabilityFrom(
                String item,
                int requested,
                boolean homeOnly) {
            int remaining = requested;
            for (Origin origin : Origin.values()) {
                if ((origin == Origin.HOME) != homeOnly) continue;
                for (DurabilityLot lot : durability.getOrDefault(item, new ArrayList<>())) {
                    if (lot.origin != origin || lot.remaining <= 0) continue;
                    int safe = Math.max(0, lot.remaining - lot.physicalUnits);
                    int use = Math.min(remaining, safe);
                    if (use <= 0) continue;
                    if (lot.origin == Origin.HOME) {
                        int usedAfter = Math.addExact(lot.initial - lot.remaining, use);
                        int unitEnd = Math.addExact(lot.homeOffset,
                                Math.toIntExact(((long) usedAfter + lot.durabilityPerUnit - 1L)
                                        / lot.durabilityPerUnit));
                        usage.mark(lot.stackId, lot.item, unitEnd, Purpose.TOOL);
                        usage.durability(lot.item, lot.stackId, use);
                    }
                    lot.remaining -= use;
                    remaining -= use;
                    if (remaining == 0) return requested;
                }
            }
            return requested - remaining;
        }

        private void requireStation(String item) {
            List<VirtualPin> pins = pinsByItem.getOrDefault(item, List.of());
            if (!pins.isEmpty()) {
                // The generic capability may describe this very Home block. Preserve its
                // exact authority so execution reuses it instead of placing a carried kit.
                pinsUsed.add(pins.getFirst().pinId());
                return;
            }
            if (reusableStationCapabilities.contains(item)) return;
            require(item, 1, Purpose.WORKSTATION);
        }

        private void requireOptionalStation(String item) {
            List<VirtualPin> pins = pinsByItem.getOrDefault(item, List.of());
            if (!pins.isEmpty()) {
                pinsUsed.add(pins.getFirst().pinId());
                return;
            }
            if (reusableStationCapabilities.contains(item)) return;
            if (available(item) > 0) require(item, 1, Purpose.WORKSTATION);
            // Missing physical station is deliberately left to the ordinary post-withdraw
            // planner. Raw/source/fuel Home stock remains useful and cannot deadlock here.
        }

        private boolean stationAvailableWithoutHomeCargo(String item) {
            return !pinsByItem.getOrDefault(item, List.of()).isEmpty()
                    || reusableStationCapabilities.contains(item)
                    || availableNonHome(item) > 0;
        }

        private Map<String, Integer> nonVirtualCounts() {
            LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
            items.forEach((item, lots) -> {
                int count = 0;
                for (ItemLot lot : lots) count = Math.addExact(count, lot.remaining);
                if (count > 0) result.put(item, count);
            });
            return Collections.unmodifiableMap(result);
        }

        private Set<String> virtualPinsUsed() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(pinsUsed));
        }
    }

    private static final class StackUse {
        private final String item;
        private int highWater;
        private int durability;
        private final EnumSet<Purpose> purposes = EnumSet.noneOf(Purpose.class);

        private StackUse(String item) {
            this.item = item;
        }
    }

    private static final class UsageTracker {
        private final LinkedHashMap<String, StackUse> byStack = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> consumed = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> retained = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> durability = new LinkedHashMap<>();

        private UsageTracker(List<HomeStack> stacks) {
            for (HomeStack stack : stacks) byStack.put(stack.stackId(), new StackUse(stack.item()));
        }

        private void mark(
                String stackId,
                String item,
                int highWater,
                Purpose purpose) {
            StackUse use = byStack.get(stackId);
            if (use == null || !use.item.equals(item)) {
                throw new IllegalStateException("unknown or changed Home stack " + stackId);
            }
            use.highWater = Math.max(use.highWater, highWater);
            use.purposes.add(purpose);
        }

        private void consumed(String item, int count) {
            consumed.merge(item, count, Math::addExact);
        }

        private void retained(String item, int count) {
            retained.merge(item, count, Math::addExact);
        }

        private void durability(String item, String stackId, int amount) {
            byStack.get(stackId).durability = Math.addExact(byStack.get(stackId).durability, amount);
            durability.merge(item, amount, Math::addExact);
        }

        private boolean empty() {
            return byStack.values().stream().noneMatch(use -> use.highWater > 0);
        }

        private Map<String, Integer> highWaterByStack() {
            LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
            byStack.forEach((id, use) -> {
                if (use.highWater > 0) result.put(id, use.highWater);
            });
            return Collections.unmodifiableMap(result);
        }

        private List<StackWithdrawal> withdrawals(List<HomeStack> source) {
            ArrayList<StackWithdrawal> result = new ArrayList<>();
            for (HomeStack stack : source) {
                StackUse use = byStack.get(stack.stackId());
                if (use == null || use.highWater <= 0) continue;
                if (use.highWater > stack.count()) {
                    throw new IllegalStateException("withdrawal exceeds Home stack " + stack.stackId());
                }
                result.add(new StackWithdrawal(
                        stack.stackId(), stack.item(), use.highWater,
                        use.durability, use.purposes));
            }
            return List.copyOf(result);
        }

        private Map<String, Integer> allocatedCounts(List<HomeStack> source) {
            LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
            for (HomeStack stack : source) {
                StackUse use = byStack.get(stack.stackId());
                if (use != null && use.highWater > 0) {
                    result.merge(stack.item(), use.highWater, Math::addExact);
                }
            }
            return Collections.unmodifiableMap(result);
        }

        private Map<String, Integer> consumedByItem() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(consumed));
        }

        private Map<String, Integer> retainedByItem() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(retained));
        }

        private Map<String, Integer> durabilityByItem() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(durability));
        }
    }
}
