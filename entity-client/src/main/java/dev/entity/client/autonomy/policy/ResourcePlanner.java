package dev.entity.client.autonomy.policy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-Java recursive prerequisite planner for Entity 2.2. It produces a
 * deterministic action queue but touches no Minecraft or Fabric classes.
 */
public final class ResourcePlanner {
    public enum RequestKind {
        ACQUIRE,
        DELIVER,
        EQUIP
    }

    public enum ActionKind {
        MINE,
        HUNT,
        CRAFT,
        SMELT,
        FILL_WATER,
        EQUIP,
        DELIVER
    }

    public enum TraceState {
        SATISFIED,
        REQUIRED,
        PRODUCE,
        TOOL,
        ACTION
    }

    /**
     * The count means operations for MINE/HUNT/CRAFT/SMELT and item count for
     * EQUIP/DELIVER. Output quantities are explicit in parameters.
     */
    public record Action(
            ActionKind kind,
            String target,
            int count,
            Map<String, String> parameters,
            String description
    ) {
        public Action {
            kind = Objects.requireNonNull(kind, "kind");
            target = requireText(target, "target");
            if (count <= 0) throw new IllegalArgumentException("action count must be positive");
            parameters = parameters == null ? Map.of() : immutableParameters(parameters);
            description = requireText(description, "description");
        }

        private static Map<String, String> immutableParameters(Map<String, String> parameters) {
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();
            parameters.forEach((key, value) -> copy.put(
                    requireText(key, "parameter key"), requireText(value, "parameter value")));
            return Collections.unmodifiableMap(copy);
        }
    }

    public record Request(RequestKind kind, String item, int count, String recipient) {
        public Request {
            kind = Objects.requireNonNull(kind, "kind");
            item = requireText(item, "item");
            if (count <= 0) throw new IllegalArgumentException("request count must be positive");
            recipient = recipient == null || recipient.isBlank() ? null : recipient.trim();
            if (kind == RequestKind.DELIVER && recipient == null) {
                throw new IllegalArgumentException("delivery requests require a recipient");
            }
            if (kind != RequestKind.DELIVER && recipient != null) {
                throw new IllegalArgumentException(kind + " requests cannot name a recipient");
            }
            if (kind == RequestKind.EQUIP && count != 1) {
                throw new IllegalArgumentException("equip requests must have count 1");
            }
        }

        public static Request acquire(String item, int count) {
            return new Request(RequestKind.ACQUIRE, item, count, null);
        }

        public static Request deliver(String item, int count, String recipient) {
            return new Request(RequestKind.DELIVER, item, count, recipient);
        }

        public static Request equip(String item) {
            return new Request(RequestKind.EQUIP, item, 1, null);
        }
    }

    /** Raw inventory IDs are normalized by the planner's catalog. */
    public record InventoryView(
            Map<String, Integer> itemCounts,
            Set<String> equippedItems,
            Set<String> locallyAvailableItems,
            Set<String> availableStations) {
        public InventoryView {
            Objects.requireNonNull(itemCounts, "itemCounts");
            Objects.requireNonNull(equippedItems, "equippedItems");
            Objects.requireNonNull(locallyAvailableItems, "locallyAvailableItems");
            Objects.requireNonNull(availableStations, "availableStations");
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
            itemCounts.forEach((item, count) -> {
                requireText(item, "inventory item");
                if (count == null || count < 0) {
                    throw new IllegalArgumentException("inventory counts cannot be negative");
                }
                if (count > 0) counts.put(item, count);
            });
            itemCounts = Collections.unmodifiableMap(counts);
            equippedItems = Collections.unmodifiableSet(new LinkedHashSet<>(equippedItems));
            locallyAvailableItems = Collections.unmodifiableSet(new LinkedHashSet<>(locallyAvailableItems));
            availableStations = Collections.unmodifiableSet(
                    new LinkedHashSet<>(availableStations));
        }

        public InventoryView(Map<String, Integer> itemCounts) {
            this(itemCounts, Set.of(), Set.of(), Set.of());
        }

        public InventoryView(Map<String, Integer> itemCounts, Set<String> equippedItems) {
            this(itemCounts, equippedItems, Set.of(), Set.of());
        }

        public InventoryView(
                Map<String, Integer> itemCounts,
                Set<String> equippedItems,
                Set<String> locallyAvailableItems) {
            this(itemCounts, equippedItems, locallyAvailableItems, Set.of());
        }

        public static InventoryView empty() {
            return new InventoryView(Map.of(), Set.of(), Set.of(), Set.of());
        }
    }

    public record TraceStep(
            String item,
            int requiredCount,
            int availableCount,
            int depth,
            TraceState state,
            String reason
    ) {
        public TraceStep {
            item = requireText(item, "trace item");
            if (requiredCount < 0 || availableCount < 0 || depth < 0) {
                throw new IllegalArgumentException("trace quantities/depth cannot be negative");
            }
            state = Objects.requireNonNull(state, "state");
            reason = requireText(reason, "reason");
        }
    }

    /**
     * action is the immediately executable next action; plan is the complete
     * projected queue and prerequisiteTrace explains why it was generated.
     */
    public record Decision(
            Optional<Action> action,
            List<TraceStep> prerequisiteTrace,
            List<Action> plan,
            boolean complete,
            Map<String, Integer> projectedInventory
    ) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            prerequisiteTrace = List.copyOf(prerequisiteTrace);
            plan = List.copyOf(plan);
            projectedInventory = Collections.unmodifiableMap(new LinkedHashMap<>(projectedInventory));
            if (complete != plan.isEmpty()) {
                throw new IllegalArgumentException("complete must match an empty action plan");
            }
            if (!plan.isEmpty() && (action.isEmpty() || !action.get().equals(plan.getFirst()))) {
                throw new IllegalArgumentException("next action must be the first planned action");
            }
            if (plan.isEmpty() && action.isPresent()) {
                throw new IllegalArgumentException("an empty plan cannot have a next action");
            }
        }
    }

    public static final class PlanningException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final List<String> dependencyPath;

        public PlanningException(String message, List<String> dependencyPath) {
            super(message);
            this.dependencyPath = List.copyOf(dependencyPath);
        }

        public List<String> dependencyPath() {
            return dependencyPath;
        }
    }

    private static final Map<String, ResourceCatalog.ToolTier> PICKAXE_TIERS = Map.of(
            "wooden_pickaxe", ResourceCatalog.ToolTier.WOOD,
            "stone_pickaxe", ResourceCatalog.ToolTier.STONE,
            "iron_pickaxe", ResourceCatalog.ToolTier.IRON,
            "diamond_pickaxe", ResourceCatalog.ToolTier.DIAMOND,
            "netherite_pickaxe", ResourceCatalog.ToolTier.DIAMOND
    );

    private final ResourceCatalog catalog;

    public ResourcePlanner() {
        this(ResourceCatalog.defaults());
    }

    public ResourcePlanner(ResourceCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public ResourceCatalog catalog() {
        return catalog;
    }

    public String normalizeItem(String raw) {
        return catalog.normalizeItem(raw);
    }

    public Map<String, Integer> recipeOutputCounts() {
        return catalog.recipeOutputCounts();
    }

    public Map<String, List<String>> miningBlockAlternatives() {
        return catalog.miningBlockAlternatives();
    }

    public Map<String, Set<String>> miningItemsByBlock() {
        return catalog.miningItemsByBlock();
    }

    /**
     * Compiles one immutable, plan-wide acquisition graph for multiple goals.
     * The existing scalar {@link #nextAction(Request, InventoryView)} contract
     * remains untouched for current runtime callers.
     */
    public AcquisitionPlan compile(AcquisitionRequest request) {
        return new UniversalAcquisitionCompiler(catalog).compile(request);
    }

    public Decision nextAction(Request request, InventoryView inventory) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(inventory, "inventory");
        Context context = new Context(normalizeInventory(inventory));
        String target = catalog.normalizeItem(request.item());

        ensure(target, request.count(), context, 0,
                "requested " + request.kind().name().toLowerCase());

        if (request.kind() == RequestKind.DELIVER) {
            LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
            parameters.put("recipient", request.recipient());
            parameters.put("itemCount", Integer.toString(request.count()));
            context.actions.add(new Action(ActionKind.DELIVER, target, request.count(), parameters,
                    "Deliver " + request.count() + " " + target + " to " + request.recipient()));
            context.trace.add(new TraceStep(target, request.count(), context.ledger.count(target), 0,
                    TraceState.ACTION, "final delivery to " + request.recipient()));
            context.ledger.consume(target, request.count());
        } else if (request.kind() == RequestKind.EQUIP && !context.ledger.isEquipped(target)) {
            addEquip(target, "requested equipment", context, 0);
        }

        List<Action> plan = List.copyOf(context.actions);
        return new Decision(
                plan.isEmpty() ? Optional.empty() : Optional.of(plan.getFirst()),
                context.trace,
                plan,
                plan.isEmpty(),
                context.ledger.snapshot()
        );
    }

    private void ensure(String item, int requiredCount, Context context, int depth, String reason) {
        item = catalog.normalizeItem(item);
        int available = context.ledger.count(item);
        if (available >= requiredCount) {
            context.trace.add(new TraceStep(item, requiredCount, available, depth,
                    TraceState.SATISFIED, reason));
            return;
        }

        context.trace.add(new TraceStep(item, requiredCount, available, depth,
                TraceState.REQUIRED, reason));
        if (context.active.contains(item)) throw cycle(item, context.active);
        context.active.addLast(item);
        try {
            int deficit = Math.subtractExact(requiredCount, available);
            ResourceCatalog.Recipe recipe = catalog.recipe(item);
            ResourceCatalog.MineSpec mine = catalog.mineSpec(item);
            ResourceCatalog.HuntSpec hunt = catalog.huntSpec(item);
            boolean carriedRecipe = recipe != null && recipeIngredientsCarried(
                    recipe, ceilDiv(deficit, recipe.outputCount()), context);
            if (mine != null && (context.locallyAvailable.contains(item)
                    || (mine.worldSourceKind() == ResourceCatalog.WorldSourceKind.HARVEST
                    && !carriedRecipe))) {
                planMining(mine, requiredCount, context, depth);
                return;
            }
            if (recipe != null) {
                planRecipe(recipe, deficit, context, depth);
                return;
            }
            if (mine != null) {
                planMining(mine, requiredCount, context, depth);
                return;
            }
            if (hunt != null) {
                planHunting(hunt, deficit, context, depth);
                return;
            }
            throw unsupported(item, context.active);
        } finally {
            context.active.removeLast();
        }
    }

    private void planRecipe(
            ResourceCatalog.Recipe recipe,
            int deficit,
            Context context,
            int depth
    ) {
        int operations = ceilDiv(deficit, recipe.outputCount());
        int totalOutput = Math.multiplyExact(operations, recipe.outputCount());
        context.trace.add(new TraceStep(recipe.output(), deficit, context.ledger.count(recipe.output()),
                depth, TraceState.PRODUCE,
                operations + " " + recipe.kind().name().toLowerCase() + " operation(s) produce " + totalOutput));

        String workstation = recipe.workstation() == null
                ? null : catalog.normalizeItem(recipe.workstation());
        if (workstation != null && !context.availableStations.contains(workstation)) {
            ensure(workstation, 1, context, depth + 1,
                    "workstation for " + recipe.output());
        }

        LinkedHashMap<String, Integer> totals = scaledCanonicalIngredients(recipe, operations);
        for (Map.Entry<String, Integer> ingredient : totals.entrySet()) {
            ensure(ingredient.getKey(), ingredient.getValue(), context, depth + 1,
                    "ingredient for " + recipe.output());
            // Reserve it immediately so a later prerequisite cannot spend the
            // same stack (for example, sticks consuming pickaxe planks).
            context.ledger.consume(ingredient.getKey(), ingredient.getValue());
        }

        int fuelCount = 0;
        String fuelItem = null;
        if (recipe.kind() == ResourceCatalog.ProductionKind.SMELT) {
            fuelCount = ceilDiv(operations, recipe.operationsPerFuel());
            fuelItem = catalog.normalizeItem(recipe.fuelItem());
            ensure(fuelItem, fuelCount, context, depth + 1,
                    "fuel for " + recipe.output());
            context.ledger.consume(fuelItem, fuelCount);
        }

        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("operations", Integer.toString(operations));
        parameters.put("outputPerOperation", Integer.toString(recipe.outputCount()));
        parameters.put("totalOutput", Integer.toString(totalOutput));
        parameters.put("ingredients", describeCounts(totals));
        if (workstation != null) parameters.put("workstation", workstation);
        if (recipe.kind() == ResourceCatalog.ProductionKind.SMELT) {
            parameters.put("fuelItem", fuelItem);
            parameters.put("fuelCount", Integer.toString(fuelCount));
            parameters.put("operationsPerFuel", Integer.toString(recipe.operationsPerFuel()));
        }

        ActionKind kind = recipe.kind() == ResourceCatalog.ProductionKind.CRAFT
                ? ActionKind.CRAFT : ActionKind.SMELT;
        context.actions.add(new Action(kind, recipe.output(), operations, parameters,
                titleCase(kind.name()) + " " + totalOutput + " " + recipe.output()
                        + " in " + operations + " operation(s)"));
        context.trace.add(new TraceStep(recipe.output(), totalOutput, context.ledger.count(recipe.output()),
                depth, TraceState.ACTION, "production action queued"));
        context.ledger.add(recipe.output(), totalOutput);
    }

    private void planMining(
            ResourceCatalog.MineSpec mine,
            int requiredCount,
            Context context,
            int depth
    ) {
        int initialDeficit = Math.max(0,
                Math.subtractExact(requiredCount, context.ledger.count(mine.item())));
        int blocks = blocksForDeficit(mine, initialDeficit);
        if (mine.requiredToolTier() != ResourceCatalog.ToolTier.NONE) {
            ensureMiningTool(mine, blocks, initialDeficit, context, depth + 1);
        }
        // A replacement tool may legitimately consume the resource being
        // gathered (for example three of 49 carried diamonds). Recompute from
        // the post-craft ledger so the projected plan still reaches the exact
        // requested cargo total instead of silently stopping at 61/64.
        int deficit = Math.max(0,
                Math.subtractExact(requiredCount, context.ledger.count(mine.item())));
        blocks = blocksForDeficit(mine, deficit);
        addMiningSegment(mine, blocks, deficit, context, depth,
                "mining action queued from " + String.join(" or ", mine.blockAlternatives()));
    }

    private void addMiningSegment(
            ResourceCatalog.MineSpec mine,
            int blocks,
            int deficit,
            Context context,
            int depth,
            String traceReason
    ) {
        int expectedMinimum = projectedDrops(mine, blocks);
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("blockAlternatives", String.join(",", mine.blockAlternatives()));
        parameters.put("minimumDropsPerBlock", Integer.toString(mine.minimumDropsPerBlock()));
        parameters.put("expectedMinimumItems", Integer.toString(expectedMinimum));
        parameters.put("requiredToolTier", mine.requiredToolTier().name());
        parameters.put("probabilistic", Boolean.toString(mine.probabilistic()));
        parameters.put("estimatedBlocksPerDrop", Integer.toString(mine.estimatedBlocksPerDrop()));
        parameters.put("worldSourceKind", mine.worldSourceKind().name());
        if (mine.replantItem() != null) parameters.put("replantItem", mine.replantItem());
        String verb = mine.worldSourceKind() == ResourceCatalog.WorldSourceKind.HARVEST
                ? "Harvest" : "Mine";
        context.actions.add(new Action(ActionKind.MINE, mine.item(), blocks, parameters,
                verb + " " + blocks + " block(s) for at least "
                        + expectedMinimum + " " + mine.item()));
        context.trace.add(new TraceStep(mine.item(), deficit, context.ledger.count(mine.item()), depth,
                TraceState.ACTION, traceReason));
        context.ledger.add(mine.item(), expectedMinimum);
    }

    private boolean recipeIngredientsCarried(
            ResourceCatalog.Recipe recipe,
            int operations,
            Context context) {
        for (Map.Entry<String, Integer> ingredient
                : scaledCanonicalIngredients(recipe, operations).entrySet()) {
            if (context.ledger.count(ingredient.getKey()) < ingredient.getValue()) return false;
        }
        if (recipe.kind() == ResourceCatalog.ProductionKind.SMELT) {
            int fuelCount = ceilDiv(operations, recipe.operationsPerFuel());
            if (context.ledger.count(catalog.normalizeItem(recipe.fuelItem())) < fuelCount) {
                return false;
            }
        }
        String workstation = recipe.workstation() == null
                ? null : catalog.normalizeItem(recipe.workstation());
        return workstation == null || context.availableStations.contains(workstation);
    }

    private void planHunting(
            ResourceCatalog.HuntSpec hunt,
            int deficit,
            Context context,
            int depth
    ) {
        int kills = ceilDiv(deficit, hunt.minimumDropsPerKill());
        int expectedMinimum = Math.multiplyExact(kills, hunt.minimumDropsPerKill());
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("entityAlternatives", String.join(",", hunt.entityAlternatives()));
        parameters.put("minimumDropsPerKill", Integer.toString(hunt.minimumDropsPerKill()));
        parameters.put("expectedMinimumItems", Integer.toString(expectedMinimum));
        context.actions.add(new Action(ActionKind.HUNT, hunt.item(), kills, parameters,
                "Hunt " + kills + " target(s) for at least " + expectedMinimum + " " + hunt.item()));
        context.trace.add(new TraceStep(hunt.item(), deficit, context.ledger.count(hunt.item()), depth,
                TraceState.ACTION,
                "hunting action queued from " + String.join(" or ", hunt.entityAlternatives())));
        context.ledger.add(hunt.item(), expectedMinimum);
    }

    private void ensureMiningTool(
            ResourceCatalog.MineSpec mine,
            int remainingBlocks,
            int cargoDeficit,
            Context context,
            int depth
    ) {
        ResourceCatalog.ToolTier required = mine.requiredToolTier();
        String minedItem = mine.item();
        String tool = context.ledger.bestPickaxe(required);
        ToolStrategy.ReplacementRequest request = new ToolStrategy.ReplacementRequest(
                required,
                remainingBlocks,
                cargoDeficit,
                minedItem,
                context.ledger.snapshot(),
                Map.of(),
                0,
                0,
                0.0);
        List<ToolStrategy.Choice> choices = ToolStrategy.choices(request);
        ToolStrategy.Choice preferred = choices.getFirst();

        if (tool != null
                && PICKAXE_TIERS.get(preferred.item()).ordinal()
                > PICKAXE_TIERS.get(tool).ordinal()) {
            // The root executor intentionally commits one prerequisite at a
            // time and replans from observed inventory after each leaf.  Do
            // not let the mere presence of the wooden bootstrap pick erase
            // the three-stone seed segment from that iterative execution.
            int selfFundingDeficit = directRecipeIngredientDeficit(
                    preferred.item(), minedItem, context.ledger);
            Context.Checkpoint upgradeStart = context.checkpoint();
            try {
                if (selfFundingDeficit > 0) {
                    addEquipIfNeeded(tool, minedItem, context, depth);
                    int seedBlocks = ceilDiv(
                            selfFundingDeficit, mine.minimumDropsPerBlock());
                    addMiningSegment(mine, seedBlocks, selfFundingDeficit, context, depth,
                            "bootstrap " + preferred.item() + " from natural "
                                    + String.join(" or ", mine.blockAlternatives()));
                }
                ensureToolCandidate(preferred, required, minedItem, context, depth);
                tool = preferred.item();
            } catch (PlanningException infeasibleUpgrade) {
                context.restore(upgradeStart);
                tool = context.ledger.bestPickaxe(required);
            }
        }

        if (tool == null) {

            // ToolStrategy deliberately prices every tier-legal tool without
            // knowing the planner's live dependency stack.  A long first
            // stone job can therefore make a stone pick look cheapest even
            // though its three cobblestone heads do not exist yet.  Fund that
            // upgrade with the cheapest producible lower-tier pick and one
            // short natural-mining segment, then retry the preferred tool.
            // This preserves the cost model and the real cycle detector.
            int selfFundingDeficit = directRecipeIngredientDeficit(
                    preferred.item(), minedItem, context.ledger);
            if (selfFundingDeficit > 0
                    && PICKAXE_TIERS.get(preferred.item()).ordinal() > required.ordinal()) {
                Context.Checkpoint bootstrapStart = context.checkpoint();
                try {
                    ToolStrategy.Choice bootstrap = firstFeasibleTool(
                            choices.stream()
                                    .filter(choice -> PICKAXE_TIERS.get(choice.item()).ordinal()
                                            < PICKAXE_TIERS.get(preferred.item()).ordinal())
                                    .toList(),
                            required, minedItem, context, depth);
                    addEquipIfNeeded(bootstrap.item(), minedItem, context, depth);
                    int seedBlocks = ceilDiv(
                            selfFundingDeficit, mine.minimumDropsPerBlock());
                    addMiningSegment(mine, seedBlocks, selfFundingDeficit, context, depth,
                            "bootstrap " + preferred.item() + " from natural "
                                    + String.join(" or ", mine.blockAlternatives()));
                    ensureToolCandidate(preferred, required, minedItem, context, depth);
                    tool = preferred.item();
                } catch (PlanningException failedBootstrap) {
                    context.restore(bootstrapStart);
                }
            }

            if (tool == null) {
                ToolStrategy.Choice choice = firstFeasibleTool(
                        choices, required, minedItem, context, depth);
                tool = choice.item();
            }
            tool = context.ledger.bestPickaxe(required);
        }
        if (tool == null) {
            throw new PlanningException("planner failed to produce a " + required + " pickaxe",
                    List.copyOf(context.active));
        }
        if (!context.ledger.isEquipped(tool)) {
            addEquip(tool, "equip tool for mining " + minedItem, context, depth);
        }
    }

    private ToolStrategy.Choice firstFeasibleTool(
            List<ToolStrategy.Choice> choices,
            ResourceCatalog.ToolTier required,
            String minedItem,
            Context context,
            int depth
    ) {
        PlanningException firstFailure = null;
        for (ToolStrategy.Choice choice : choices) {
            Context.Checkpoint checkpoint = context.checkpoint();
            try {
                ensureToolCandidate(choice, required, minedItem, context, depth);
                return choice;
            } catch (PlanningException failure) {
                context.restore(checkpoint);
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) throw firstFailure;
        throw new PlanningException("no " + required + " pickaxe candidate can mine " + minedItem,
                List.copyOf(context.active));
    }

    private void ensureToolCandidate(
            ToolStrategy.Choice choice,
            ResourceCatalog.ToolTier required,
            String minedItem,
            Context context,
            int depth
    ) {
        context.trace.add(new TraceStep(choice.item(), 1, context.ledger.count(choice.item()), depth,
                TraceState.TOOL, required + " pickaxe candidate for " + minedItem));
        ensure(choice.item(), 1, context, depth + 1, "tool prerequisite for " + minedItem);
    }

    private void addEquipIfNeeded(
            String tool,
            String minedItem,
            Context context,
            int depth
    ) {
        if (!context.ledger.isEquipped(tool)) {
            addEquip(tool, "equip bootstrap tool for mining " + minedItem, context, depth);
        }
    }

    private int directRecipeIngredientDeficit(String output, String ingredient, Ledger ledger) {
        ResourceCatalog.Recipe recipe = catalog.recipe(output);
        if (recipe == null) return 0;
        int operations = ceilDiv(Math.max(0, 1 - ledger.count(output)), recipe.outputCount());
        if (operations == 0) return 0;
        String canonicalIngredient = catalog.normalizeItem(ingredient);
        int required = scaledCanonicalIngredients(recipe, operations)
                .getOrDefault(canonicalIngredient, 0);
        return Math.max(0, Math.subtractExact(
                required, ledger.count(canonicalIngredient)));
    }

    /**
     * Scalar planning deliberately treats legacy material aliases as one
     * spendable resource. Resolve every selected runtime-recipe ingredient
     * before both ensuring and consuming it so the ledger uses one key.
     */
    private LinkedHashMap<String, Integer> scaledCanonicalIngredients(
            ResourceCatalog.Recipe recipe,
            int operations) {
        LinkedHashMap<String, Integer> totals = new LinkedHashMap<>();
        recipe.ingredients().forEach((rawIngredient, perOperation) -> {
            String ingredient = catalog.normalizeItem(rawIngredient);
            int total = Math.multiplyExact(perOperation, operations);
            totals.merge(ingredient, total, Math::addExact);
        });
        return totals;
    }

    private void addEquip(String item, String reason, Context context, int depth) {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("slot", equipmentSlot(item));
        context.actions.add(new Action(ActionKind.EQUIP, item, 1, parameters,
                "Equip " + item + " for " + reason));
        context.trace.add(new TraceStep(item, 1, context.ledger.count(item), depth,
                TraceState.ACTION, reason));
        context.ledger.equip(item);
    }

    private NormalizedInventory normalizeInventory(InventoryView inventory) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        inventory.itemCounts().forEach((raw, count) -> {
            if (count <= 0) return;
            String item = catalog.normalizeItem(raw);
            counts.merge(item, count, Math::addExact);
        });
        LinkedHashSet<String> equipped = new LinkedHashSet<>();
        for (String raw : inventory.equippedItems()) equipped.add(catalog.normalizeItem(raw));
        LinkedHashSet<String> local = new LinkedHashSet<>();
        for (String raw : inventory.locallyAvailableItems()) local.add(catalog.normalizeItem(raw));
        LinkedHashSet<String> stations = new LinkedHashSet<>();
        for (String raw : inventory.availableStations()) {
            stations.add(catalog.normalizeItem(raw));
        }
        return new NormalizedInventory(counts, equipped, local, stations);
    }

    private static PlanningException cycle(String item, Deque<String> active) {
        ArrayList<String> path = new ArrayList<>(active);
        int start = path.indexOf(item);
        if (start > 0) path = new ArrayList<>(path.subList(start, path.size()));
        path.add(item);
        return new PlanningException("dependency cycle detected: " + String.join(" -> ", path), path);
    }

    private static PlanningException unsupported(String item, Deque<String> active) {
        ArrayList<String> path = new ArrayList<>(active);
        return new PlanningException("no recipe, mining, or hunting source for " + item
                + " (dependency path: " + String.join(" -> ", path) + ")", path);
    }

    private static int ceilDiv(int numerator, int denominator) {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("ceilDiv operands must be positive");
        }
        return Math.toIntExact(((long) numerator + denominator - 1L) / denominator);
    }

    private static int blocksForDeficit(ResourceCatalog.MineSpec mine, int deficit) {
        int dropUnits = ceilDiv(deficit, mine.minimumDropsPerBlock());
        return Math.multiplyExact(dropUnits, mine.estimatedBlocksPerDrop());
    }

    private static int projectedDrops(ResourceCatalog.MineSpec mine, int blocks) {
        int dropUnits = ceilDiv(blocks, mine.estimatedBlocksPerDrop());
        return Math.multiplyExact(dropUnits, mine.minimumDropsPerBlock());
    }

    private static String equipmentSlot(String item) {
        if (item.endsWith("_helmet")) return "head";
        if (item.endsWith("_chestplate")) return "chest";
        if (item.endsWith("_leggings")) return "legs";
        if (item.endsWith("_boots")) return "feet";
        if (item.equals("shield")) return "offhand";
        return "mainhand";
    }

    private static String describeCounts(Map<String, Integer> values) {
        ArrayList<String> parts = new ArrayList<>();
        values.forEach((item, count) -> parts.add(item + "=" + count));
        return String.join(",", parts);
    }

    private static String titleCase(String value) {
        return value.charAt(0) + value.substring(1).toLowerCase();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }

    private record NormalizedInventory(
            Map<String, Integer> counts,
            Set<String> equipped,
            Set<String> locallyAvailable,
            Set<String> availableStations) {
    }

    private static final class Context {
        private final Ledger ledger;
        private final ArrayList<Action> actions = new ArrayList<>();
        private final ArrayList<TraceStep> trace = new ArrayList<>();
        private final ArrayDeque<String> active = new ArrayDeque<>();
        private final Set<String> locallyAvailable;
        private final Set<String> availableStations;

        private Context(NormalizedInventory inventory) {
            ledger = new Ledger(inventory.counts(), inventory.equipped());
            locallyAvailable = inventory.locallyAvailable();
            availableStations = inventory.availableStations();
        }

        private Checkpoint checkpoint() {
            return new Checkpoint(ledger.snapshot(), ledger.equippedSnapshot(),
                    actions.size(), trace.size());
        }

        private void restore(Checkpoint checkpoint) {
            ledger.restore(checkpoint.counts(), checkpoint.equipped());
            actions.subList(checkpoint.actionCount(), actions.size()).clear();
            trace.subList(checkpoint.traceCount(), trace.size()).clear();
        }

        private record Checkpoint(
                Map<String, Integer> counts,
                Set<String> equipped,
                int actionCount,
                int traceCount
        ) {
        }
    }

    private static final class Ledger {
        private final LinkedHashMap<String, Integer> counts;
        private final LinkedHashSet<String> equipped;

        private Ledger(Map<String, Integer> counts, Set<String> equipped) {
            this.counts = new LinkedHashMap<>(counts);
            this.equipped = new LinkedHashSet<>(equipped);
        }

        private int count(String item) {
            return counts.getOrDefault(item, 0);
        }

        private void add(String item, int amount) {
            counts.merge(item, amount, Math::addExact);
        }

        private void consume(String item, int amount) {
            int before = count(item);
            if (amount < 0 || before < amount) {
                throw new IllegalStateException("cannot consume " + amount + " " + item + " from " + before);
            }
            int after = before - amount;
            if (after == 0) counts.remove(item);
            else counts.put(item, after);
        }

        private boolean isEquipped(String item) {
            return equipped.contains(item);
        }

        private void equip(String item) {
            equipped.add(item);
        }

        private String bestPickaxe(ResourceCatalog.ToolTier required) {
            String best = null;
            int bestRank = -1;
            for (Map.Entry<String, ResourceCatalog.ToolTier> entry : PICKAXE_TIERS.entrySet()) {
                if (count(entry.getKey()) <= 0 || !entry.getValue().satisfies(required)) continue;
                int rank = entry.getValue().ordinal();
                if (rank > bestRank) {
                    best = entry.getKey();
                    bestRank = rank;
                }
            }
            return best;
        }

        private Map<String, Integer> snapshot() {
            return new LinkedHashMap<>(counts);
        }

        private Set<String> equippedSnapshot() {
            return new LinkedHashSet<>(equipped);
        }

        private void restore(Map<String, Integer> restoredCounts, Set<String> restoredEquipped) {
            counts.clear();
            counts.putAll(restoredCounts);
            equipped.clear();
            equipped.addAll(restoredEquipped);
        }
    }
}
