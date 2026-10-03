package dev.entity.client.autonomy.policy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Transactional, Minecraft-independent compiler for a complete multi-item
 * acquisition objective. Package-private by design; {@link ResourcePlanner}
 * is the public policy facade.
 */
final class UniversalAcquisitionCompiler {
    /*
     * Stable fallback order for equivalent vanilla material families. Paper's
     * recipe catalog is deliberately canonical and therefore sorts tag
     * members by registry ID; using that wire order as policy made "planks"
     * mean acacia in an untouched world. Local evidence and carried stock are
     * stronger signals than this list. The list only breaks otherwise equal
     * choices, favouring common overworld bootstrap sources before rarer or
     * dimension-gated families.
     */
    private static final List<String> MATERIAL_FAMILY_PREFERENCE = List.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped");
    private static final List<String> MATERIAL_FAMILY_DETECTION = List.of(
            "dark_oak", "pale_oak", "mangrove", "crimson", "spruce", "birch",
            "jungle", "acacia", "cherry", "bamboo", "warped", "oak");
    private static final List<String> STONE_MATERIAL_PREFERENCE = List.of(
            "cobblestone", "cobbled_deepslate", "blackstone");
    /** Conservative burn allowance for a server-confirmed retained-input swap. */
    private static final int FURNACE_TRANSITION_HEADROOM_OPERATIONS = 1;
    private static final List<FuelKind> FURNACE_FUELS = List.of(
            new FuelKind("coal_block", 80),
            new FuelKind("coal", 8),
            new FuelKind("charcoal", 8));

    private static final List<ToolProfile> PICKAXES = List.of(
            new ToolProfile("wooden_pickaxe", ResourceCatalog.ToolTier.WOOD, 59),
            new ToolProfile("stone_pickaxe", ResourceCatalog.ToolTier.STONE, 131),
            new ToolProfile("iron_pickaxe", ResourceCatalog.ToolTier.IRON, 250),
            new ToolProfile("diamond_pickaxe", ResourceCatalog.ToolTier.DIAMOND, 1561),
            new ToolProfile("netherite_pickaxe", ResourceCatalog.ToolTier.DIAMOND, 2031));

    private final ResourceCatalog catalog;

    UniversalAcquisitionCompiler(ResourceCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    AcquisitionPlan compile(AcquisitionRequest request) {
        Objects.requireNonNull(request, "request");
        NormalizedRequest normalized = normalize(request);
        Context context = new Context(normalized.inventory(), normalized.goalFloors(),
                normalized.usableDurability(), normalized.unavailableHarvestSources());

        for (AcquisitionRequest.ItemGoal goal : normalized.goals()) {
            ensure(goal.item(), goal.count(), context, 0, "plan-wide acquisition goal");
        }

        Map<String, Integer> projected = context.ledger.countsSnapshot();
        for (AcquisitionRequest.ItemGoal goal : normalized.goals()) {
            if (projected.getOrDefault(goal.item(), 0) < goal.count()) {
                throw new IllegalStateException("compiled plan did not preserve final goal "
                        + goal.item() + "=" + goal.count());
            }
        }

        List<ResourcePlanner.Action> optimizedActions = annotateWorkstationLifetime(
                scheduleAllocatedDemand(context.actions, normalized, projected),
                normalized.inventory().availableStations());
        context.actions.clear();
        context.actions.addAll(optimizedActions);

        return new AcquisitionPlan(
                normalized.goals(),
                context.actions,
                context.trace,
                normalized.inventory().counts(),
                projected,
                totals(normalized.goalFloors(), normalized.inventory().counts(),
                        projected, context));
    }

    private void ensure(
            String rawItem,
            int requiredCount,
            Context context,
            int depth,
            String reason) {
        String item = catalog.normalizeExactItem(rawItem);
        int available = context.ledger.count(item);
        if (available >= requiredCount) {
            context.trace.add(new ResourcePlanner.TraceStep(item, requiredCount, available, depth,
                    ResourcePlanner.TraceState.SATISFIED, reason));
            return;
        }

        context.trace.add(new ResourcePlanner.TraceStep(item, requiredCount, available, depth,
                ResourcePlanner.TraceState.REQUIRED, reason));
        if (context.active.contains(item)) throw cycle(item, context.active);
        context.active.addLast(item);
        try {
            int deficit = Math.subtractExact(requiredCount, available);
            if (item.equals("water_bucket")) {
                ensureConsumable("bucket", deficit, context, depth + 1, "empty vessel for real source-water use");
                context.ledger.consume("bucket", deficit);
                // One durable source/use acknowledgement per leaf; never pretend this is a recipe.
                for (int index = 0; index < deficit; index++) context.actions.add(new ResourcePlanner.Action(
                        ResourcePlanner.ActionKind.FILL_WATER, "water_bucket", 1,
                        Map.of("ingredients", "bucket=1", "totalOutput", "1", "operations", "1", "outputPerOperation", "1"),
                        "Fill one empty bucket from an observed water source"));
                context.ledger.add("water_bucket", deficit);
                return;
            }
            List<ResourceCatalog.RecipeVariant> recipes = catalog.recipeVariantsExact(item);
            ResourceCatalog.MineSpec mine = catalog.mineSpecExact(item);
            ResourceCatalog.HuntSpec hunt = catalog.huntSpecExact(item);
            boolean carriedRecipe = recipes.stream().anyMatch(recipe ->
                    recipeIngredientsCarried(
                            recipe, ceilDiv(deficit, recipe.outputCount()), context));
            boolean preferredHarvest = mine != null
                    && mine.worldSourceKind() == ResourceCatalog.WorldSourceKind.HARVEST
                    && !carriedRecipe;

            // Authoritative catalogs contain reversible compression recipes beside direct
            // sources. A recipe with its complete input already carried is real immediate
            // work and keeps priority. Otherwise a direct hunt source is cheaper and safer
            // than recursively hunting conversion inputs. Non-local mining stays behind
            // recipes because the source catalog also models recoverable placed materials
            // such as planks. A salvage source explicitly marked production-first also stays
            // behind recipes when observed: one visible block is not quantity evidence for a
            // large batch. Every branch remains transactional.
            ResourcePlanner.PlanningException firstFailure = null;
            boolean mineAttempted = false;
            boolean preferredLocalSource = mine != null
                    && context.locallyAvailable.contains(item)
                    && !mine.preferProductionBeforeLocalSalvage();
            if (mine != null && (preferredLocalSource || preferredHarvest)) {
                Context.Checkpoint checkpoint = context.checkpoint();
                try {
                    planMining(mine, requiredCount, context, depth);
                    requireEnsuredCount(
                            item, requiredCount, context, "local mining branch");
                    return;
                } catch (ResourcePlanner.PlanningException failure) {
                    context.restore(checkpoint);
                    firstFailure = failure;
                    mineAttempted = true;
                }
            }
            if (carriedRecipe) {
                Context.Checkpoint checkpoint = context.checkpoint();
                try {
                    planRecipeVariants(item, recipes, deficit, context, depth);
                    requireEnsuredCount(
                            item, requiredCount, context, "recipe branch");
                    return;
                } catch (ResourcePlanner.PlanningException failure) {
                    context.restore(checkpoint);
                    if (firstFailure == null) firstFailure = failure;
                }
            }
            if (mine != null && !mineAttempted && carriedRecipe) {
                Context.Checkpoint checkpoint = context.checkpoint();
                try {
                    planMining(mine, requiredCount, context, depth);
                    requireEnsuredCount(
                            item, requiredCount, context, "mining branch");
                    return;
                } catch (ResourcePlanner.PlanningException failure) {
                    context.restore(checkpoint);
                    if (firstFailure == null) firstFailure = failure;
                }
            }
            if (hunt != null) {
                Context.Checkpoint checkpoint = context.checkpoint();
                try {
                    planHunting(hunt, deficit, context, depth);
                    requireEnsuredCount(
                            item, requiredCount, context, "hunting branch");
                    return;
                } catch (ResourcePlanner.PlanningException failure) {
                    context.restore(checkpoint);
                    if (firstFailure == null) firstFailure = failure;
                }
            }
            if (!recipes.isEmpty() && !carriedRecipe) {
                Context.Checkpoint checkpoint = context.checkpoint();
                try {
                    planRecipeVariants(item, recipes, deficit, context, depth);
                    requireEnsuredCount(
                            item, requiredCount, context, "recipe branch");
                    return;
                } catch (ResourcePlanner.PlanningException failure) {
                    context.restore(checkpoint);
                    if (firstFailure == null) firstFailure = failure;
                }
            }
            if (mine != null && !mineAttempted && !carriedRecipe) {
                Context.Checkpoint checkpoint = context.checkpoint();
                try {
                    planMining(mine, requiredCount, context, depth);
                    requireEnsuredCount(
                            item, requiredCount, context, "mining branch");
                    return;
                } catch (ResourcePlanner.PlanningException failure) {
                    context.restore(checkpoint);
                    if (firstFailure == null) firstFailure = failure;
                }
            }
            if (firstFailure != null) throw firstFailure;
            throw unsupported(item, context.active);
        } finally {
            context.active.removeLast();
        }
    }

    private void planRecipeVariants(
            String output,
            List<ResourceCatalog.RecipeVariant> variants,
            int deficit,
            Context context,
            int depth) {
        int requiredCount = Math.addExact(context.ledger.count(output), deficit);
        ResourcePlanner.PlanningException firstFailure = null;
        for (RecipeCandidate candidate : orderedRecipeVariants(
                output, variants, deficit, context)) {
            ResourceCatalog.RecipeVariant variant = candidate.variant();
            Context.Checkpoint checkpoint = context.checkpoint();
            try {
                planRecipeVariant(
                        variant, deficit, context, depth, candidate.catalogIndex());
                requireEnsuredCount(
                        output, requiredCount, context,
                        "recipe " + recipeIdentity(variant));
                return;
            } catch (ResourcePlanner.PlanningException failure) {
                context.restore(checkpoint);
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) throw firstFailure;
        throw new ResourcePlanner.PlanningException(
                "no feasible recipe variant for " + output, List.copyOf(context.active));
    }

    private static void requireEnsuredCount(
            String item,
            int requiredCount,
            Context context,
            String branch) {
        int actualCount = context.ledger.count(item);
        if (actualCount >= requiredCount) return;
        throw new ResourcePlanner.PlanningException(
                branch + " did not establish " + requiredCount + " " + item
                        + " (projected " + actualCount + ")",
                List.copyOf(context.active));
    }

    /**
     * Runtime recipe registries are serialized by recipe ID, not by acquisition cost. In
     * vanilla that places recipes such as {@code dye_white_bed} before the ordinary
     * {@code white_bed} recipe. Blindly accepting the first feasible branch can therefore
     * turn three carried wool and planks into a tour through every bed colour.
     *
     * <p>An already satisfiable recipe remains strongest because it can execute without new
     * acquisition. Then prefer a supported carried/loaded ingredient chain over unknown
     * sources; canonical output recipes break remaining ties before conversion variants.
     * Every candidate retains its transactional checkpoint and
     * original catalog index, so an infeasible canonical recipe still falls back exactly as
     * before and metadata identity remains unchanged.</p>
     */
    private List<RecipeCandidate> orderedRecipeVariants(
            String output,
            List<ResourceCatalog.RecipeVariant> variants,
            int deficit,
            Context context) {
        ArrayList<RecipeCandidate> ordered = new ArrayList<>(variants.size());
        for (int index = 0; index < variants.size(); index++) {
            ResourceCatalog.RecipeVariant variant = variants.get(index);
            int operations = ceilDiv(deficit, variant.outputCount());
            ordered.add(new RecipeCandidate(
                    index,
                    variant,
                    recipeIngredientsCarried(variant, operations, context),
                    recipeSourcesAvailable(variant, operations, context,
                            new LinkedHashSet<>(), new int[] {64}),
                    canonicalOutputRecipe(output, variant)));
        }
        ordered.sort(Comparator
                .comparingInt((RecipeCandidate candidate) ->
                        candidate.ingredientsCarried() ? 0 : 1)
                .thenComparingInt(candidate -> candidate.sourcesAvailable() ? 0 : 1)
                .thenComparingInt(candidate -> candidate.canonicalOutputRecipe() ? 0 : 1)
                .thenComparingInt(RecipeCandidate::catalogIndex));
        return List.copyOf(ordered);
    }

    /** A bounded ranking hint, never a permission fence or proof of world quantity. */
    private boolean recipeSourcesAvailable(ResourceCatalog.RecipeVariant recipe,
            int operations, Context context, Set<String> visiting, int[] remaining) {
        // A colour-conversion graph can have many cyclic tag alternatives. Exhausting
        // the hint budget means "unknown", not "unobtainable"; normal planning remains intact.
        if (remaining[0]-- <= 0 || visiting.size() >= 6 || !visiting.add(recipe.output())) return false;
        try {
            for (ResourceCatalog.IngredientChoice choice : recipe.ingredients()) {
                int required = Math.multiplyExact(choice.count(), operations);
                boolean available = false;
                for (String raw : choice.alternatives()) {
                    String item = catalog.normalizeExactItem(raw);
                    if (context.spendableConsumable(item) >= required) {
                        available = true;
                        break;
                    }
                    ResourceCatalog.MineSpec source = catalog.mineSpecExact(item);
                    if (source != null && context.locallyAvailable.contains(item)
                            && !context.unavailableHarvestSources.contains(item)) {
                        available = true;
                        break;
                    }
                    int deficit = Math.max(1, required - context.spendableConsumable(item));
                    for (ResourceCatalog.RecipeVariant alternative : catalog.recipeVariantsExact(item)) {
                        if (recipeSourcesAvailable(alternative,
                                ceilDiv(deficit, alternative.outputCount()), context, visiting, remaining)) {
                            available = true;
                            break;
                        }
                    }
                    if (available) break;
                }
                if (!available) return false;
            }
            return true;
        } catch (ArithmeticException oversizedHint) {
            return false;
        } finally {
            visiting.remove(recipe.output());
        }
    }

    private boolean recipeIngredientsCarried(
            ResourceCatalog.RecipeVariant recipe,
            int operations,
            Context context) {
        for (ResourceCatalog.IngredientChoice choice : recipe.ingredients()) {
            int required = Math.multiplyExact(choice.count(), operations);
            boolean carried = false;
            for (String alternative : choice.alternatives()) {
                String item = catalog.normalizeExactItem(alternative);
                if (context.spendableConsumable(item) >= required) {
                    carried = true;
                    break;
                }
            }
            if (!carried) return false;
        }
        return true;
    }

    private static boolean canonicalOutputRecipe(
            String output,
            ResourceCatalog.RecipeVariant recipe) {
        String recipeId = recipe.metadata().recipeId();
        return recipeId != null
                && (recipeId.equals(output) || recipeId.equals("minecraft:" + output));
    }

    private void planRecipeVariant(
            ResourceCatalog.RecipeVariant recipe,
            int deficit,
            Context context,
            int depth,
            int variantIndex) {
        int operations = ceilDiv(deficit, recipe.outputCount());
        int totalOutput = Math.multiplyExact(operations, recipe.outputCount());
        context.trace.add(new ResourcePlanner.TraceStep(
                recipe.output(), deficit, context.ledger.count(recipe.output()), depth,
                ResourcePlanner.TraceState.PRODUCE,
                "recipe variant " + variantIndex + " uses " + operations
                        + " " + recipe.kind().name().toLowerCase() + " operation(s)"));

        chooseIngredients(recipe, operations, totalOutput, 0,
                new LinkedHashMap<>(), false, context, depth);
    }

    private void chooseIngredients(
            ResourceCatalog.RecipeVariant recipe,
            int operations,
            int totalOutput,
            int ingredientIndex,
            LinkedHashMap<String, Integer> selectedTotals,
            boolean mixedIngredientGroups,
            Context context,
            int depth) {
        if (ingredientIndex >= recipe.ingredients().size()) {
            finishRecipe(recipe, operations, totalOutput, selectedTotals,
                    mixedIngredientGroups, context, depth);
            return;
        }

        ResourceCatalog.IngredientChoice choice = recipe.ingredients().get(ingredientIndex);
        int amount = Math.multiplyExact(choice.count(), operations);
        ResourcePlanner.PlanningException firstFailure = null;
        // A tag describes interchangeable physical inputs, not one colour/type
        // for every operation. Spend an already-carried mixture before asking
        // for more of one alternative (owner's black wool 2 + white wool 1).
        // Keep one grouped CRAFT action: vanilla recipe fill selects the order,
        // while exact projected totals and the normal transaction remain owned
        // by this allocation. Smelt slicing still requires homogeneous inputs.
        if (recipe.kind() == ResourceCatalog.ProductionKind.CRAFT
                && recipe.metadata().recipeId() != null
                && !recipe.metadata().slots().isEmpty()
                && (recipe.metadata().layout() == ResourceCatalog.RecipeLayout.SHAPED
                || recipe.metadata().layout() == ResourceCatalog.RecipeLayout.SHAPELESS)) {
            LinkedHashMap<String, Integer> carried = new LinkedHashMap<>();
            int remaining = amount;
            for (String alternative : orderedAlternatives(choice, amount, context)) {
                // Already-produced output reduces this recipe's deficit; feeding
                // it back into a colour-conversion recipe cannot fill that deficit.
                if (alternative.equals(recipe.output())) continue;
                int take = Math.min(remaining, context.spendableConsumable(alternative));
                if (take > 0) carried.put(alternative, take);
                remaining -= take;
                if (remaining == 0) break;
            }
            if (remaining == 0 && carried.size() > 1) {
                Context.Checkpoint checkpoint = context.checkpoint();
                try {
                    LinkedHashMap<String, Integer> nextTotals = new LinkedHashMap<>(selectedTotals);
                    for (Map.Entry<String, Integer> entry : carried.entrySet()) {
                        context.ledger.consume(entry.getKey(), entry.getValue());
                        nextTotals.merge(entry.getKey(), entry.getValue(), Math::addExact);
                    }
                    chooseIngredients(recipe, operations, totalOutput, ingredientIndex + 1,
                            nextTotals, true, context, depth);
                    return;
                } catch (ResourcePlanner.PlanningException failure) {
                    context.restore(checkpoint);
                    firstFailure = failure;
                }
            }
        }
        for (String alternative : orderedAlternatives(choice, amount, context)) {
            Context.Checkpoint checkpoint = context.checkpoint();
            try {
                ensureConsumable(alternative, amount, context, depth + 1,
                        "ingredient alternative for " + recipe.output());
                context.ledger.consume(alternative, amount);
                LinkedHashMap<String, Integer> nextTotals = new LinkedHashMap<>(selectedTotals);
                nextTotals.merge(alternative, amount, Math::addExact);
                chooseIngredients(recipe, operations, totalOutput, ingredientIndex + 1,
                        nextTotals, mixedIngredientGroups, context, depth);
                return;
            } catch (ResourcePlanner.PlanningException failure) {
                context.restore(checkpoint);
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) throw firstFailure;
        throw new ResourcePlanner.PlanningException(
                "no feasible ingredient alternative for " + recipe.output(),
                List.copyOf(context.active));
    }

    /**
     * Ingredient alternatives are policy, not serialization order.
     *
     * <p>A complete carried stack (including surplus produced earlier in this
     * plan) always wins. Next comes a material whose missing quantity can be
     * crafted directly from carried ingredients, then material whose exact
     * family is observed in loaded nearby blocks, then useful partial stock. Only after those live
     * signals do we use source shape and the stable vanilla-family fallback.
     * This keeps transactional fallback intact: sorting changes the order in
     * which independently checkpointed branches are attempted, never their
     * state boundaries.</p>
     */
    private List<String> orderedAlternatives(
            ResourceCatalog.IngredientChoice choice,
            int amount,
            Context context) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : choice.alternatives()) {
            normalized.add(catalog.normalizeExactItem(raw));
        }
        ArrayList<String> ordered = new ArrayList<>(normalized);
        ordered.sort(Comparator
                .comparingInt((String item) -> availabilityClass(item, amount, context))
                .thenComparingInt(item -> localMaterialRank(item, context.locallyAvailable))
                .thenComparing(Comparator.comparingInt(
                        (String item) -> Math.min(amount, context.ledger.count(item))).reversed())
                .thenComparingInt(this::sourceShapeRank)
                .thenComparingInt(UniversalAcquisitionCompiler::materialFamilyRank)
                .thenComparingInt(UniversalAcquisitionCompiler::stoneMaterialRank)
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(ordered);
    }

    private int availabilityClass(String item, int amount, Context context) {
        int count = context.ledger.count(item);
        if (count >= amount) return 0;
        int deficit = Math.subtractExact(amount, count);
        // A source hint is not a reason to abandon already-owned convertible
        // inputs: owner red_bed needed three planks while carrying two oak
        // planks and three oak logs, yet a nearby birch hint sent it harvesting.
        // This is one-hop ranking only; the existing transactional branch still
        // proves exact ingredients, workstation custody, and resulting count.
        boolean carriedConversion = catalog.recipeVariantsExact(item).stream()
                .filter(recipe -> recipe.kind() == ResourceCatalog.ProductionKind.CRAFT)
                .anyMatch(recipe -> recipeIngredientsCarried(
                        recipe, ceilDiv(deficit, recipe.outputCount()), context));
        if (carriedConversion) return 1;
        if (localMaterialRank(item, context.locallyAvailable) < Integer.MAX_VALUE) return 2;
        if (count > 0) return 3;
        return 4;
    }

    /** Exact local item evidence wins; family evidence covers log -> plank recipes. */
    private int localMaterialRank(String item, Set<String> locallyAvailable) {
        if (locallyAvailable.contains(item) && isMaterialSourceSignal(item)) return 0;
        String family = materialFamily(item);
        if (family == null) return Integer.MAX_VALUE;

        boolean sameFamily = false;
        boolean otherExactNonOakFamily = false;
        for (String local : locallyAvailable) {
            if (!isMaterialSourceSignal(local)) continue;
            String localFamily = materialFamily(local);
            if (family.equals(localFamily)) sameFamily = true;
            if (localFamily != null && !localFamily.equals("oak")) {
                otherExactNonOakFamily = true;
            }
        }
        if (!sameFamily) return Integer.MAX_VALUE;

        // The legacy oak_log source intentionally represents every log. When
        // an exact non-oak family is also present, oak is ambiguous rather
        // than positive evidence and must not mask that exact local family.
        if (family.equals("oak") && otherExactNonOakFamily) return 2;
        return 1;
    }

    /** A recoverable placed block is fallback cargo, not renewable-family evidence. */
    private boolean isMaterialSourceSignal(String item) {
        ResourceCatalog.MineSpec mine = catalog.mineSpecExact(item);
        return mine == null || !mine.preferProductionBeforeLocalSalvage();
    }

    private int sourceShapeRank(String item) {
        if (catalog.mineSpecExact(item) != null) return 0;
        if (catalog.huntSpecExact(item) != null) return 1;
        if (!catalog.recipeVariantsExact(item).isEmpty()) return 2;
        return 3;
    }

    private static int materialFamilyRank(String item) {
        String family = materialFamily(item);
        if (family == null) return MATERIAL_FAMILY_PREFERENCE.size();
        int index = MATERIAL_FAMILY_PREFERENCE.indexOf(family);
        return index >= 0 ? index : MATERIAL_FAMILY_PREFERENCE.size();
    }

    private static int stoneMaterialRank(String item) {
        int index = STONE_MATERIAL_PREFERENCE.indexOf(item);
        return index >= 0 ? index : STONE_MATERIAL_PREFERENCE.size();
    }

    private static String materialFamily(String item) {
        for (String family : MATERIAL_FAMILY_DETECTION) {
            if (item.equals(family)
                    || item.startsWith(family + "_")
                    || item.endsWith("_" + family)
                    || item.contains("_" + family + "_")) {
                return family;
            }
        }
        return null;
    }

    private void finishRecipe(
            ResourceCatalog.RecipeVariant recipe,
            int operations,
            int totalOutput,
            Map<String, Integer> selectedTotals,
            boolean mixedIngredientGroups,
            Context context,
            int depth) {
        // Consumables (and the tools needed to obtain them) deliberately come
        // first. This avoids cold-start plans that build an eight-cobble
        // furnace before funding the three-cobble stone pick required to mine
        // the furnace's raw input.
        if (recipe.workstation() != null
                && !context.availableStations.contains(recipe.workstation())) {
            int workstationFloor = context.goalFloors.getOrDefault(recipe.workstation(), 0);
            ensure(recipe.workstation(), Math.max(1, workstationFloor), context, depth + 1,
                    "retained workstation for " + recipe.output());
        }

        // Station acquisition/replacement can end a previous physical furnace generation, so
        // fuel continuity is evaluated only after the exact workstation prerequisite is stable.
        List<SmeltFuelSlice> fuelSlices = recipe.kind() == ResourceCatalog.ProductionKind.SMELT
                ? planSmeltFuelSlices(recipe, operations, selectedTotals, context, depth)
                : List.of();

        ResourcePlanner.ActionKind kind = recipe.kind() == ResourceCatalog.ProductionKind.CRAFT
                ? ResourcePlanner.ActionKind.CRAFT : ResourcePlanner.ActionKind.SMELT;
        if (kind == ResourcePlanner.ActionKind.CRAFT) {
            context.actions.add(productionAction(
                    recipe, kind, operations, totalOutput, selectedTotals, null, mixedIngredientGroups));
        } else {
            int assigned = 0;
            for (SmeltFuelSlice slice : fuelSlices) {
                int output = Math.multiplyExact(slice.operations(), recipe.outputCount());
                LinkedHashMap<String, Integer> ingredients = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> entry : selectedTotals.entrySet()) {
                    int perOperation = entry.getValue() / operations;
                    if (Math.multiplyExact(perOperation, operations) != entry.getValue()) {
                        throw new IllegalStateException(
                                "smelt ingredient total is not operation-aligned");
                    }
                    ingredients.put(entry.getKey(),
                            Math.multiplyExact(perOperation, slice.operations()));
                }
                context.actions.add(productionAction(
                        recipe, kind, slice.operations(), output, ingredients, slice, false));
                assigned = Math.addExact(assigned, slice.operations());
            }
            if (assigned != operations) {
                throw new IllegalStateException("smelt fuel slices do not cover every operation");
            }
        }
        context.trace.add(new ResourcePlanner.TraceStep(
                recipe.output(), totalOutput, context.ledger.count(recipe.output()), depth,
                ResourcePlanner.TraceState.ACTION, "production action queued"));
        context.ledger.add(recipe.output(), totalOutput);
    }

    private ResourcePlanner.Action productionAction(
            ResourceCatalog.RecipeVariant recipe,
            ResourcePlanner.ActionKind kind,
            int operations,
            int totalOutput,
            Map<String, Integer> ingredients,
            SmeltFuelSlice fuel,
            boolean mixedIngredientGroups) {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("operations", Integer.toString(operations));
        parameters.put("outputPerOperation", Integer.toString(recipe.outputCount()));
        parameters.put("totalOutput", Integer.toString(totalOutput));
        parameters.put("ingredients", describeCounts(ingredients));
        if (mixedIngredientGroups) parameters.put("mixedIngredientGroups", "true");
        ResourceCatalog.RecipeMetadata metadata = recipe.metadata();
        if (metadata.recipeId() != null) parameters.put("recipeId", metadata.recipeId());
        parameters.put("recipeVariant", recipeIdentity(recipe));
        parameters.put("recipeLayout", metadata.layout().name());
        if (metadata.layout() == ResourceCatalog.RecipeLayout.SHAPED) {
            parameters.put("recipeWidth", Integer.toString(metadata.width()));
            parameters.put("recipeHeight", Integer.toString(metadata.height()));
        }
        if (recipe.workstation() != null) parameters.put("workstation", recipe.workstation());
        if (fuel != null) {
            parameters.put("fuelItem", fuel.item());
            parameters.put("fuelCount", Integer.toString(fuel.count()));
            parameters.put("operationsPerFuel", Integer.toString(fuel.operationsPerFuel()));
            parameters.put("burnPool", burnPoolKey(
                    recipe.workstation(), fuel.item(), fuel.operationsPerFuel()));
            parameters.put("burnTransitionHeadroom",
                    Integer.toString(fuel.transitionHeadroom()));
        }
        return new ResourcePlanner.Action(
                kind, recipe.output(), operations, parameters,
                titleCase(kind.name()) + " " + totalOutput + " " + recipe.output()
                        + " in " + operations + " operation(s)");
    }

    private void ensureConsumable(
            String item,
            int amount,
            Context context,
            int depth,
            String reason) {
        int floor = context.active.contains(item)
                ? 0 : context.consumableFloor(item);
        int required = Math.addExact(floor, amount);
        ensure(item, required, context, depth, reason);
    }

    /**
     * Funds one exact smelt from equivalent physical fuels. Coal/charcoal may be mixed and a
     * coal block contributes its real 80-operation capacity; each physical item remains explicit
     * in the resulting action slices and conservation ledger.
     */
    private List<SmeltFuelSlice> planSmeltFuelSlices(
            ResourceCatalog.RecipeVariant recipe,
            int operations,
            Map<String, Integer> ingredients,
            Context context,
            int depth) {
        RetainedFuel retained = retainedFuel(recipe, ingredients, context);
        int continuityActionCount = context.actions.size();
        // Burn time belongs to one concrete, continuously-open furnace session.  Once the
        // immediately preceding allocated action is not that furnace, old credit is no longer
        // evidence: time may have elapsed, the block may have been reclaimed, or a replacement
        // furnace may now occupy the role.
        context.furnaceBurnCredits.clear();
        if (!"coal".equals(recipe.fuelItem())) {
            int retainedOperations = retained != null
                            && retained.kind().item().equals(recipe.fuelItem())
                            && retained.kind().operationsPerItem() == recipe.operationsPerFuel()
                    ? retained.operations() : 0;
            return List.of(fundSingleFuel(
                    recipe, recipe.fuelItem(), recipe.operationsPerFuel(), operations,
                    context, depth, retainedOperations));
        }

        FuelBundle available = chooseFuelBundle(
                operations,
                context.spendableConsumable("coal"),
                context.spendableConsumable("charcoal"),
                context.spendableConsumable("coal_block"),
                retained);
        for (int iteration = 0;
                available.effectiveOperations() < operations && iteration < 8;
                iteration++) {
            int carriedCoal = context.spendableConsumable("coal");
            int suppliedCoal = carriedCoal;
            int limit = Math.addExact(carriedCoal,
                    Math.addExact(ceilDiv(operations, recipe.operationsPerFuel()), 1));
            FuelBundle prospective = available;
            do {
                suppliedCoal = Math.addExact(suppliedCoal, 1);
                prospective = chooseFuelBundle(
                        operations,
                        suppliedCoal,
                        context.spendableConsumable("charcoal"),
                        context.spendableConsumable("coal_block"),
                        retained);
            } while (prospective.effectiveOperations() < operations && suppliedCoal < limit);
            if (prospective.effectiveOperations() < operations) break;

            // Keep the physical fuel lots used by the prospective bundle reserved while coal
            // itself is acquired. Otherwise the reversible coal-block recipe can consume the
            // very block whose 80-operation capacity made the bundle complete.
            Map<String, Integer> previousFloors = context.temporaryConsumableFloors();
            context.addTemporaryConsumableFloor(
                    "coal_block", context.ledger.count("coal_block"));
            context.addTemporaryConsumableFloor(
                    "charcoal", context.ledger.count("charcoal"));
            try {
                ensureConsumable("coal", prospective.coal(), context, depth + 1,
                        "mixed furnace fuel for " + recipe.output());
            } finally {
                context.restoreTemporaryConsumableFloors(previousFloors);
            }
            if (context.actions.size() != continuityActionCount) {
                // Mining/crafting a missing fuel item is intervening work. Any live burn observed
                // before that prerequisite is no longer bounded evidence for the later smelt.
                retained = null;
                context.furnaceBurnCredits.clear();
                continuityActionCount = context.actions.size();
            }
            // Re-evaluate against actual post-acquisition ledger truth. The bounded loop is a
            // transactional fixed point; no provisional capacity reaches an emitted action.
            available = chooseFuelBundle(
                    operations,
                    context.spendableConsumable("coal"),
                    context.spendableConsumable("charcoal"),
                    context.spendableConsumable("coal_block"),
                    retained);
        }
        if (available.effectiveOperations() < operations) {
            throw new IllegalStateException(
                    "could not fund a transition-safe mixed furnace fuel bundle");
        }
        int coal = available.coal();
        int charcoal = available.charcoal();
        int blocks = available.coalBlocks();
        if (blocks > 0) context.ledger.consume("coal_block", blocks);
        if (coal > 0) context.ledger.consume("coal", coal);
        if (charcoal > 0) context.ledger.consume("charcoal", charcoal);

        ArrayList<FuelKind> orderedFuel = new ArrayList<>();
        if (retained != null) orderedFuel.add(retained.kind());
        for (FuelKind kind : FURNACE_FUELS) {
            if (retained == null || !kind.item().equals(retained.kind().item())) {
                orderedFuel.add(kind);
            }
        }

        ArrayList<SmeltFuelSlice> slices = new ArrayList<>();
        int remaining = operations;
        for (FuelKind kind : orderedFuel) {
            int count = switch (kind.item()) {
                case "coal_block" -> blocks;
                case "coal" -> coal;
                case "charcoal" -> charcoal;
                default -> 0;
            };
            int retainedCredit = retained != null && kind.item().equals(retained.kind().item())
                    ? retained.operations() : 0;
            if ((count <= 0 && retainedCredit <= 0) || remaining <= 0) continue;
            int headroom = retained != null || !slices.isEmpty()
                    ? FURNACE_TRANSITION_HEADROOM_OPERATIONS : 0;
            int capacity = Math.addExact(retainedCredit,
                    Math.multiplyExact(count, kind.operationsPerItem()));
            int usable = Math.max(0, capacity - headroom);
            int assigned = Math.min(remaining, usable);
            if (assigned <= 0) continue;
            String pool = burnPoolKey(
                    recipe.workstation(), kind.item(), kind.operationsPerItem());
            int remainingCredit = capacity - Math.addExact(assigned, headroom);
            slices.add(new SmeltFuelSlice(
                    kind.item(), count, kind.operationsPerItem(), assigned, headroom));
            // A physical furnace has one active fuel slot/timeline.  Switching families discards
            // the prior family's residual credit; only the final emitted slice may be retained by
            // the immediately adjacent next smelt.
            context.furnaceBurnCredits.clear();
            if (remainingCredit > 0) {
                context.furnaceBurnCredits.put(pool, remainingCredit);
            }
            remaining -= assigned;
        }
        if (remaining > 0) {
            throw new IllegalStateException(
                    "mixed fuel selection underfunded smelt by " + remaining + " operation(s)");
        }
        return List.copyOf(slices);
    }

    private static RetainedFuel retainedFuel(
            ResourceCatalog.RecipeVariant recipe,
            Map<String, Integer> ingredients,
            Context context) {
        if (context.actions.isEmpty()) return null;
        int previousSmeltIndex = -1;
        ResourcePlanner.Action previous = null;
        for (int index = context.actions.size() - 1; index >= 0; index--) {
            ResourcePlanner.Action candidate = context.actions.get(index);
            if (candidate.kind() == ResourcePlanner.ActionKind.SMELT) {
                previousSmeltIndex = index;
                previous = candidate;
                break;
            }
        }
        if (previous == null || !Objects.equals(recipe.workstation(),
                previous.parameters().get("workstation"))) return null;

        // Interposed consumers that are independent can be deferred by pass two so the furnace
        // session remains adjacent. A producer needed by this smelt, a fuel producer, or any
        // physical workstation consumption/replacement cannot be deferred and ends the session.
        LinkedHashSet<String> predecessorDependentOutputs = new LinkedHashSet<>();
        predecessorDependentOutputs.add(previous.target());
        for (int index = previousSmeltIndex + 1; index < context.actions.size(); index++) {
            ResourcePlanner.Action intervening = context.actions.get(index);
            if (ingredients.containsKey(intervening.target())
                    || recipe.workstation().equals(intervening.target())
                    || FURNACE_FUELS.stream().anyMatch(
                            fuel -> fuel.item().equals(intervening.target()))
                    || parseDescribedCounts(intervening.parameters().get("ingredients"))
                            .containsKey(recipe.workstation())) {
                return null;
            }
            Set<String> dependencies = actionDependencyItems(
                    intervening, context.availableStations);
            boolean dependsOnPredecessor = dependencies.stream()
                    .anyMatch(predecessorDependentOutputs::contains);
            if (dependsOnPredecessor) {
                // Pass two may hoist an independent reader before the prior smelt, but cannot do
                // so when that reader itself requires output from the smelt. The executable order
                // crosses the reader, therefore this successor must be cold-funded now.
                boolean readsSuccessorIngredient = nonConsumingItemReads(
                        intervening, context.availableStations).stream()
                        .anyMatch(ingredients::containsKey);
                if (readsSuccessorIngredient) return null;
                predecessorDependentOutputs.add(intervening.target());
            }
        }

        String previousPool = previous.parameters().get("burnPool");
        for (FuelKind fuel : FURNACE_FUELS) {
            String pool = burnPoolKey(
                    recipe.workstation(), fuel.item(), fuel.operationsPerItem());
            if (!pool.equals(previousPool)) continue;
            int operations = context.furnaceBurnCredits.getOrDefault(pool, 0);
            if (operations > 0) return new RetainedFuel(fuel, operations);
        }
        return null;
    }

    private static Set<String> actionDependencyItems(
            ResourcePlanner.Action action,
            Set<String> availableStations) {
        LinkedHashSet<String> result = new LinkedHashSet<>(
                parseDescribedCounts(action.parameters().get("ingredients")).keySet());
        String workstation = action.parameters().get("workstation");
        if (workstation != null && !availableStations.contains(workstation)) {
            result.add(workstation);
        }
        if (action.kind() == ResourcePlanner.ActionKind.SMELT
                && nonNegativeParameter(action, "fuelCount", 0) > 0) {
            result.add(action.parameters().getOrDefault("fuelItem", "coal"));
        } else if (action.kind() == ResourcePlanner.ActionKind.EQUIP
                || action.kind() == ResourcePlanner.ActionKind.DELIVER) {
            result.add(action.target());
        } else if (action.kind() == ResourcePlanner.ActionKind.MINE) {
            String plannedTool = action.parameters().get("plannedTool");
            if (plannedTool != null) result.add(plannedTool);
        }
        return Set.copyOf(result);
    }

    private static Set<String> nonConsumingItemReads(
            ResourcePlanner.Action action,
            Set<String> availableStations) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String workstation = action.parameters().get("workstation");
        if (workstation != null && !availableStations.contains(workstation)) {
            result.add(workstation);
        }
        if (action.kind() == ResourcePlanner.ActionKind.EQUIP) {
            result.add(action.target());
        } else if (action.kind() == ResourcePlanner.ActionKind.MINE) {
            String plannedTool = action.parameters().get("plannedTool");
            if (plannedTool != null) result.add(plannedTool);
        }
        return Set.copyOf(result);
    }

    private SmeltFuelSlice fundSingleFuel(
            ResourceCatalog.RecipeVariant recipe,
            String item,
            int operationsPerFuel,
            int operations,
            Context context,
            int depth,
            int retained) {
        String pool = burnPoolKey(recipe.workstation(), item, operationsPerFuel);
        int headroom = retained > 0 ? FURNACE_TRANSITION_HEADROOM_OPERATIONS : 0;
        int required = Math.addExact(operations, headroom);
        int unfunded = Math.max(0, required - retained);
        int count = unfunded == 0 ? 0 : ceilDiv(unfunded, operationsPerFuel);
        if (count > 0) {
            int actionsBeforeFuelAcquisition = context.actions.size();
            ensureConsumable(item, count, context, depth + 1,
                    "fuel for " + recipe.output());
            if (retained > 0 && context.actions.size() != actionsBeforeFuelAcquisition) {
                // Producing missing fuel is real intervening work.  It cannot coexist with a
                // promise that the previous furnace remains continuously open and burning.
                // Re-fund the successor from a cold furnace before consuming any fuel lot.
                retained = 0;
                headroom = 0;
                required = operations;
                count = ceilDiv(required, operationsPerFuel);
                ensureConsumable(item, count, context, depth + 1,
                        "cold-session fuel for " + recipe.output());
            }
            context.ledger.consume(item, count);
        }
        int funded = Math.addExact(retained,
                Math.multiplyExact(count, operationsPerFuel));
        int remainingCredit = funded - required;
        context.furnaceBurnCredits.clear();
        if (remainingCredit > 0) context.furnaceBurnCredits.put(pool, remainingCredit);
        return new SmeltFuelSlice(item, count, operationsPerFuel, operations, headroom);
    }

    private static FuelBundle chooseFuelBundle(
            int operations,
            int coalAvailable,
            int charcoalAvailable,
            int blockAvailable,
            RetainedFuel retained) {
        FuelBundle best = FuelBundle.EMPTY;
        // One extra block may be required when a retained pool and a fuel-family transition
        // consume the otherwise exact 80-operation boundary. The optimizer still selects the
        // smallest complete bundle, so widening enumeration cannot over-consume a spare block.
        int blockLimit = Math.min(blockAvailable, Math.addExact(ceilDiv(operations, 80), 1));
        int regularAvailable = Math.addExact(coalAvailable, charcoalAvailable);
        int regularLimit = Math.min(regularAvailable, Math.addExact(ceilDiv(operations, 8), 1));
        for (int blocks = 0; blocks <= blockLimit; blocks++) {
            for (int regular = 0; regular <= regularLimit; regular++) {
                int minimumCoal = Math.max(0, regular - charcoalAvailable);
                int maximumCoal = Math.min(coalAvailable, regular);
                for (int coal = minimumCoal; coal <= maximumCoal; coal++) {
                    int charcoal = regular - coal;
                int types = (blocks > 0 ? 1 : 0) + (coal > 0 ? 1 : 0)
                        + (charcoal > 0 ? 1 : 0);
                int capacity = Math.addExact(
                        Math.multiplyExact(blocks, 80), Math.multiplyExact(regular, 8));
                int retainedOperations = retained == null ? 0 : retained.operations();
                int continuingTypes = retained == null ? 0 : switch (retained.kind().item()) {
                    case "coal_block" -> blocks > 0 ? 1 : 0;
                    case "coal" -> coal > 0 ? 1 : 0;
                    case "charcoal" -> charcoal > 0 ? 1 : 0;
                    default -> 0;
                };
                int transitions = retained == null
                        ? Math.max(0, types - 1)
                        : Math.addExact(FURNACE_TRANSITION_HEADROOM_OPERATIONS,
                                Math.max(0, types - continuingTypes));
                int effective = Math.max(0, Math.addExact(retainedOperations, capacity)
                        - transitions);
                FuelBundle offered = new FuelBundle(coal, charcoal, blocks, effective);
                if (betterFuelBundle(offered, best, operations)) best = offered;
                }
            }
        }
        return best;
    }

    private static boolean betterFuelBundle(
            FuelBundle offered,
            FuelBundle current,
            int operations) {
        boolean offeredComplete = offered.effectiveOperations() >= operations;
        boolean currentComplete = current.effectiveOperations() >= operations;
        if (offeredComplete != currentComplete) return offeredComplete;
        if (!offeredComplete
                && offered.effectiveOperations() != current.effectiveOperations()) {
            return offered.effectiveOperations() > current.effectiveOperations();
        }
        if (offered.itemCount() != current.itemCount()) {
            return offered.itemCount() < current.itemCount();
        }
        int offeredExcess = Math.max(0, offered.effectiveOperations() - operations);
        int currentExcess = Math.max(0, current.effectiveOperations() - operations);
        if (offeredExcess != currentExcess) return offeredExcess < currentExcess;
        if (offered.coalBlocks() != current.coalBlocks()) {
            return offered.coalBlocks() > current.coalBlocks();
        }
        return offered.charcoal() < current.charcoal();
    }

    private void planMining(
            ResourceCatalog.MineSpec mine,
            int requiredCount,
            Context context,
            int depth) {
        if (mine.worldSourceKind() == ResourceCatalog.WorldSourceKind.HARVEST
                && context.unavailableHarvestSources.contains(mine.item())) {
            throw new ResourcePlanner.PlanningException(
                    "loaded harvest source already absent in this acquisition: " + mine.item(),
                    List.copyOf(context.active));
        }
        ToolProfile tool = null;
        int deficit = Math.max(0,
                Math.subtractExact(requiredCount, context.ledger.count(mine.item())));
        int blocks = blocksForDeficit(mine, deficit);

        if (mine.requiredToolTier() != ResourceCatalog.ToolTier.NONE) {
            tool = ensureMiningTool(mine, blocks, deficit, context, depth + 1);
            // Tool recipes may consume the cargo being mined. Recompute until
            // the selected durability capacity covers the actual final work.
            for (int iteration = 0; iteration < 8; iteration++) {
                deficit = Math.max(0,
                        Math.subtractExact(requiredCount, context.ledger.count(mine.item())));
                blocks = blocksForDeficit(mine, deficit);
                int safeCapacity = safeToolWorkCapacity(context.ledger, tool);
                // Live Baritone segments never spend a stack's final durability point. Mirror
                // that exact actuator boundary here: every observed physical tool contributes
                // durability-1 work, independently of whether it is itself a final cargo goal.
                if (safeCapacity >= blocks) break;
                int additional = ceilDiv(
                        blocks - safeCapacity, tool.durability() - 1);
                ensure(tool.item(), Math.addExact(context.ledger.count(tool.item()), additional),
                        context, depth + 1, "additional tool durability for " + mine.item());
                if (iteration == 7) {
                    throw new ResourcePlanner.PlanningException(
                            "tool capacity did not converge for " + mine.item(),
                            List.copyOf(context.active));
                }
            }
        }

        deficit = Math.max(0,
                Math.subtractExact(requiredCount, context.ledger.count(mine.item())));
        blocks = blocksForDeficit(mine, deficit);
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
        if (tool != null) parameters.put("plannedTool", tool.item());
        String verb = mine.worldSourceKind() == ResourceCatalog.WorldSourceKind.HARVEST
                ? "Harvest" : "Mine";
        context.actions.add(new ResourcePlanner.Action(
                ResourcePlanner.ActionKind.MINE, mine.item(), blocks, parameters,
                verb + " " + blocks + " block(s) for at least "
                        + expectedMinimum + " " + mine.item()));
        context.trace.add(new ResourcePlanner.TraceStep(
                mine.item(), deficit, context.ledger.count(mine.item()), depth,
                ResourcePlanner.TraceState.ACTION,
                "mining action queued from " + String.join(" or ", mine.blockAlternatives())));
        if (tool != null) context.ledger.useDurability(tool.item(), blocks);
        context.ledger.add(mine.item(), expectedMinimum);
    }

    private ToolProfile ensureMiningTool(
            ResourceCatalog.MineSpec mine,
            int remainingBlocks,
            int cargoDeficit,
            Context context,
            int depth) {
        ToolStrategy.ReplacementRequest request = new ToolStrategy.ReplacementRequest(
                mine.requiredToolTier(), remainingBlocks, cargoDeficit, mine.item(),
                context.ledger.countsSnapshot(), context.ledger.durabilitySnapshot(),
                0, 0, 0.0);
        ResourcePlanner.PlanningException firstFailure = null;
        for (ToolStrategy.Choice choice : ToolStrategy.choices(request)) {
            ToolProfile profile = profile(choice.item());
            if (profile == null) continue;
            Context.Checkpoint checkpoint = context.checkpoint();
            try {
                if (choice.toolsToCraft() > 0) {
                    int requiredCount = Math.addExact(
                            context.ledger.count(profile.item()), choice.toolsToCraft());
                    ensure(profile.item(), requiredCount, context, depth + 1,
                            "costed tool prerequisite for " + mine.item());
                }
                int safeCapacity = safeToolWorkCapacity(context.ledger, profile);
                if (safeCapacity < choice.adjustedWorkload()) {
                    int additional = ceilDiv(
                            choice.adjustedWorkload() - safeCapacity,
                            profile.durability() - 1);
                    ensure(profile.item(),
                            Math.addExact(context.ledger.count(profile.item()), additional),
                            context, depth + 1,
                            "remaining durability prerequisite for " + mine.item());
                }
                if (safeToolWorkCapacity(context.ledger, profile) < remainingBlocks) {
                    throw new ResourcePlanner.PlanningException(
                            "insufficient usable durability from " + profile.item(),
                            List.copyOf(context.active));
                }
                if (!context.ledger.isEquipped(profile.item())) {
                    addEquip(profile.item(), "equip tool for mining " + mine.item(), context, depth);
                }
                return profile;
            } catch (ResourcePlanner.PlanningException failure) {
                context.restore(checkpoint);
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) throw firstFailure;
        throw new ResourcePlanner.PlanningException(
                "no feasible " + mine.requiredToolTier() + " pickaxe for " + mine.item(),
                List.copyOf(context.active));
    }

    private static int safeToolWorkCapacity(Ledger ledger, ToolProfile profile) {
        return Math.max(0, Math.subtractExact(
                ledger.usableDurability(profile.item()), ledger.count(profile.item())));
    }

    private void planHunting(
            ResourceCatalog.HuntSpec hunt,
            int deficit,
            Context context,
            int depth) {
        int kills = ceilDiv(deficit, hunt.minimumDropsPerKill());
        int expectedMinimum = Math.multiplyExact(kills, hunt.minimumDropsPerKill());
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("entityAlternatives", String.join(",", hunt.entityAlternatives()));
        parameters.put("minimumDropsPerKill", Integer.toString(hunt.minimumDropsPerKill()));
        parameters.put("expectedMinimumItems", Integer.toString(expectedMinimum));
        context.actions.add(new ResourcePlanner.Action(
                ResourcePlanner.ActionKind.HUNT, hunt.item(), kills, parameters,
                "Hunt " + kills + " target(s) for at least "
                        + expectedMinimum + " " + hunt.item()));
        context.trace.add(new ResourcePlanner.TraceStep(
                hunt.item(), deficit, context.ledger.count(hunt.item()), depth,
                ResourcePlanner.TraceState.ACTION,
                "hunting action queued from " + String.join(" or ", hunt.entityAlternatives())));
        context.ledger.add(hunt.item(), expectedMinimum);
    }

    private void addEquip(
            String item,
            String reason,
            Context context,
            int depth) {
        context.actions.add(new ResourcePlanner.Action(
                ResourcePlanner.ActionKind.EQUIP, item, 1,
                Map.of("slot", "mainhand"), "Equip " + item + " for " + reason));
        context.trace.add(new ResourcePlanner.TraceStep(
                item, 1, context.ledger.count(item), depth,
                ResourcePlanner.TraceState.ACTION, reason));
        context.ledger.equip(item);
    }

    private NormalizedRequest normalize(AcquisitionRequest request) {
        LinkedHashMap<String, Integer> goals = new LinkedHashMap<>();
        for (AcquisitionRequest.ItemGoal goal : request.goals()) {
            String item = catalog.resolveUniversalItem(goal.item());
            goals.merge(item, goal.count(), Math::addExact);
        }
        ArrayList<AcquisitionRequest.ItemGoal> normalizedGoals = new ArrayList<>();
        goals.forEach((item, count) ->
                normalizedGoals.add(new AcquisitionRequest.ItemGoal(item, count)));

        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        request.inventory().itemCounts().forEach((raw, count) -> {
            if (count > 0) {
                counts.merge(catalog.normalizeExactItem(raw), count, Math::addExact);
            }
        });
        Integer logFloor = goals.get(WoodLogFamilyPolicy.FAMILY_ITEM);
        if (logFloor != null) {
            Map<String, Integer> protectedLogs =
                    WoodLogFamilyPolicy.allocateExistingFloor(counts, logFloor);
            int protectedCount = 0;
            for (Map.Entry<String, Integer> entry : protectedLogs.entrySet()) {
                int remaining = counts.getOrDefault(entry.getKey(), 0) - entry.getValue();
                if (remaining == 0) counts.remove(entry.getKey());
                else counts.put(entry.getKey(), remaining);
                protectedCount = Math.addExact(protectedCount, entry.getValue());
            }
            if (protectedCount > 0) {
                counts.put(WoodLogFamilyPolicy.FAMILY_ITEM, protectedCount);
            }
        }
        LinkedHashSet<String> equipped = new LinkedHashSet<>();
        for (String raw : request.inventory().equippedItems()) {
            equipped.add(catalog.normalizeExactItem(raw));
        }
        LinkedHashSet<String> local = new LinkedHashSet<>();
        for (String raw : request.inventory().locallyAvailableItems()) {
            local.add(catalog.normalizeExactItem(raw));
        }
        LinkedHashSet<String> stations = new LinkedHashSet<>();
        for (String raw : request.inventory().availableStations()) {
            stations.add(catalog.normalizeExactItem(raw));
        }
        LinkedHashMap<String, Integer> durability = new LinkedHashMap<>();
        request.usablePickaxeDurability().forEach((raw, amount) ->
                durability.merge(catalog.normalizeExactItem(raw), amount, Math::addExact));

        return new NormalizedRequest(
                List.copyOf(normalizedGoals),
                Collections.unmodifiableMap(new LinkedHashMap<>(goals)),
                new NormalizedInventory(
                        Collections.unmodifiableMap(counts),
                        Collections.unmodifiableSet(equipped),
                        Collections.unmodifiableSet(local),
                        Collections.unmodifiableSet(stations)),
                Collections.unmodifiableMap(durability),
                request.unavailableHarvestSources().stream().map(catalog::normalizeExactItem)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private AcquisitionPlan.ResourceTotals totals(
            Map<String, Integer> requested,
            Map<String, Integer> initial,
            Map<String, Integer> projected,
            Context context) {
        LinkedHashMap<String, Integer> net = new LinkedHashMap<>();
        LinkedHashSet<String> items = new LinkedHashSet<>(initial.keySet());
        items.addAll(projected.keySet());
        for (String item : items) {
            int change = Math.subtractExact(
                    projected.getOrDefault(item, 0), initial.getOrDefault(item, 0));
            if (change != 0) net.put(item, change);
        }

        LinkedHashMap<String, Integer> mined = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> workstationUses = new LinkedHashMap<>();
        for (ResourcePlanner.Action action : context.actions) {
            if (action.kind() == ResourcePlanner.ActionKind.MINE) {
                mined.merge(action.target(), action.count(), Math::addExact);
            }
            String workstation = action.parameters().get("workstation");
            if (workstation != null) {
                workstationUses.merge(workstation, action.count(), Math::addExact);
            }
        }
        return new AcquisitionPlan.ResourceTotals(
                requested,
                context.ledger.producedSnapshot(),
                context.ledger.consumedSnapshot(),
                net,
                mined,
                workstationUses,
                context.ledger.toolUseSnapshot());
    }

    /**
     * Pass two of universal planning.  The recursive allocator above chooses
     * recipes and alternatives transactionally; this pass recovers the exact
     * producer/consumer edges from that committed allocation, then schedules
     * only ready nodes.  Unlike a global action sort, it cannot move mining
     * ahead of the action which supplies its tool, fuel, or workstation.
     */
    private List<ResourcePlanner.Action> scheduleAllocatedDemand(
            List<ResourcePlanner.Action> allocated,
            NormalizedRequest normalized,
            Map<String, Integer> expectedProjected) {
        ArrayList<DemandNode> nodes = hoistRetainedBurnPrerequisites(
                allocateDemandGraph(allocated, normalized));
        ArrayList<ResourcePlanner.Action> scheduled = new ArrayList<>();
        LinkedHashSet<Integer> completed = new LinkedHashSet<>();
        LinkedHashMap<Integer, Integer> burnContinuationByPredecessor = new LinkedHashMap<>();
        for (DemandNode node : nodes) {
            if (node.burnContinuation() && node.furnacePredecessor() != null) {
                Integer previous = burnContinuationByPredecessor.put(
                        node.furnacePredecessor(), node.id());
                if (previous != null) {
                    throw new IllegalStateException(
                            "one furnace action has multiple retained-burn successors");
                }
            }
        }
        String retainedWorkstation = null;
        Integer forcedBurnContinuation = null;

        while (completed.size() < nodes.size()) {
            ArrayList<DemandNode> ready = new ArrayList<>();
            for (DemandNode node : nodes) {
                if (!completed.contains(node.id()) && completed.containsAll(node.dependencies())) {
                    ready.add(node);
                }
            }
            if (ready.isEmpty()) {
                throw new IllegalStateException("allocated acquisition demand contains a cycle");
            }
            DemandNode selected = null;
            if (forcedBurnContinuation != null) {
                for (DemandNode candidate : ready) {
                    if (candidate.id() == forcedBurnContinuation) {
                        selected = candidate;
                        break;
                    }
                }
                if (selected == null) {
                    throw new IllegalStateException(
                            "retained furnace burn cannot cross an intervening prerequisite");
                }
            } else {
                final String activeStation = retainedWorkstation;
                ready.sort(Comparator
                        .comparingInt((DemandNode node) ->
                                schedulingRank(node.action(), activeStation))
                        .thenComparingInt(DemandNode::id));
                selected = ready.getFirst();
            }

            ResourcePlanner.Action combined = selected.action();
            ArrayList<DemandNode> batch = new ArrayList<>();
            batch.add(selected);
            // Smelts are deliberately never batch-selected: their order and immediate retained
            // successor encode one physical furnace/fuel timeline. Other ready work can retain
            // the original deterministic coalescing behavior.
            if (selected.action().kind() != ResourcePlanner.ActionKind.SMELT) {
                for (DemandNode candidate : ready) {
                    if (candidate == selected
                            || candidate.action().kind() == ResourcePlanner.ActionKind.SMELT) {
                        continue;
                    }
                    ResourcePlanner.Action merged = mergeCompatible(combined, candidate.action());
                    if (merged != null) {
                        combined = merged;
                        batch.add(candidate);
                    }
                }
            }
            scheduled.add(combined);
            for (DemandNode node : batch) completed.add(node.id());
            forcedBurnContinuation = burnContinuationByPredecessor.get(selected.id());
            String station = combined.parameters().get("workstation");
            if (station != null) retainedWorkstation = station;
        }

        List<ResourcePlanner.Action> result = coalesceAdjacent(scheduled);
        replaySchedule(result, normalized, expectedProjected);
        return result;
    }

    /**
     * A retained-burn successor must remain adjacent to its furnace predecessor.  If that
     * successor also depends on an independent read (for example, an earlier recipe must use a
     * table before the successor consumes it), execute the read before opening the furnace
     * session.  Dependencies that themselves require the predecessor would form an impossible
     * retained session and are rejected rather than silently crossing intervening work.
     */
    private static ArrayList<DemandNode> hoistRetainedBurnPrerequisites(
            ArrayList<DemandNode> source) {
        ArrayList<DemandNode> nodes = new ArrayList<>(source);
        for (DemandNode successor : source) {
            if (!successor.burnContinuation() || successor.furnacePredecessor() == null) continue;
            int predecessorId = successor.furnacePredecessor();
            DemandNode predecessor = nodes.get(predecessorId);
            LinkedHashSet<Integer> hoisted = new LinkedHashSet<>(predecessor.dependencies());
            for (int dependency : successor.dependencies()) {
                if (dependency == predecessorId) continue;
                if (dependsTransitively(nodes, dependency, predecessorId, new LinkedHashSet<>())) {
                    throw new IllegalStateException(
                            "retained furnace burn crosses a prerequisite produced after its "
                                    + "predecessor");
                }
                hoisted.add(dependency);
            }
            nodes.set(predecessorId, new DemandNode(
                    predecessor.id(), predecessor.action(), Set.copyOf(hoisted),
                    predecessor.furnacePredecessor(), predecessor.burnContinuation()));
        }
        return nodes;
    }

    private static boolean dependsTransitively(
            List<DemandNode> nodes,
            int nodeId,
            int sought,
            Set<Integer> visited) {
        if (nodeId == sought) return true;
        if (!visited.add(nodeId)) return false;
        for (int dependency : nodes.get(nodeId).dependencies()) {
            if (dependsTransitively(nodes, dependency, sought, visited)) return true;
        }
        return false;
    }

    private ArrayList<DemandNode> allocateDemandGraph(
            List<ResourcePlanner.Action> actions,
            NormalizedRequest normalized) {
        OwnedResourceLedger resources = new OwnedResourceLedger(normalized.goalFloors());
        normalized.inventory().counts().forEach(
                (item, count) -> resources.seedInitialItem(item, count));
        initialDurability(normalized).forEach(
                (tool, capacity) -> resources.addDurability(tool, capacity, -1));

        ArrayList<DemandNode> nodes = new ArrayList<>();
        LinkedHashMap<String, Integer> latestEquip = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<Integer>> priorItemReaders = new LinkedHashMap<>();
        Integer latestFurnaceAction = null;
        for (int id = 0; id < actions.size(); id++) {
            ResourcePlanner.Action action = actions.get(id);
            LinkedHashSet<Integer> dependencies = new LinkedHashSet<>();
            Integer furnacePredecessor = null;
            boolean burnContinuation = false;
            switch (action.kind()) {
                case CRAFT, SMELT, FILL_WATER -> {
                    for (Map.Entry<String, Integer> ingredient :
                            parseDescribedCounts(action.parameters().get("ingredients")).entrySet()) {
                        addPriorReaders(priorItemReaders, ingredient.getKey(), dependencies);
                        resources.consumeItem(
                                ingredient.getKey(), ingredient.getValue(), dependencies);
                    }
                    String workstation = action.parameters().get("workstation");
                    if (workstation != null
                            && !normalized.inventory().availableStations()
                                    .contains(workstation)) {
                        resources.requireItem(workstation, 1, dependencies);
                        priorItemReaders.computeIfAbsent(
                                workstation, ignored -> new LinkedHashSet<>()).add(id);
                    }
                    if (action.kind() == ResourcePlanner.ActionKind.SMELT) {
                        String fuel = action.parameters().getOrDefault("fuelItem", "coal");
                        int fuelCount = nonNegativeParameter(action, "fuelCount", 0);
                        if (fuelCount > 0) {
                            addPriorReaders(priorItemReaders, fuel, dependencies);
                            resources.consumeItem(fuel, fuelCount, dependencies);
                        }
                        furnacePredecessor = latestFurnaceAction;
                        if (furnacePredecessor != null) dependencies.add(furnacePredecessor);
                        burnContinuation = nonNegativeParameter(
                                action, "burnTransitionHeadroom", 0) > 0;
                        if (burnContinuation && furnacePredecessor == null) {
                            throw new IllegalStateException(
                                    "retained furnace burn has no physical predecessor");
                        }
                        latestFurnaceAction = id;
                    }
                }
                case EQUIP -> {
                    if (profile(action.target()) != null) {
                        resources.requireDurability(action.target(), 1, dependencies);
                    } else {
                        resources.requireItem(action.target(), 1, dependencies);
                    }
                    priorItemReaders.computeIfAbsent(
                            action.target(), ignored -> new LinkedHashSet<>()).add(id);
                    latestEquip.put(action.target(), id);
                }
                case MINE -> {
                    String tool = action.parameters().get("plannedTool");
                    if (tool != null) {
                        resources.requireDurability(tool, 1, dependencies);
                        resources.consumeDurability(tool, action.count(), dependencies);
                        priorItemReaders.computeIfAbsent(
                                tool, ignored -> new LinkedHashSet<>()).add(id);
                        Integer equip = latestEquip.get(tool);
                        if (equip != null) dependencies.add(equip);
                    }
                }
                case HUNT, DELIVER -> {
                    if (action.kind() == ResourcePlanner.ActionKind.DELIVER) {
                        addPriorReaders(priorItemReaders, action.target(), dependencies);
                        resources.consumeItem(action.target(), action.count(), dependencies);
                    }
                }
            }

            DemandNode node = new DemandNode(
                    id, action, Set.copyOf(dependencies), furnacePredecessor, burnContinuation);
            nodes.add(node);
            switch (action.kind()) {
                case MINE, HUNT -> resources.addItem(action.target(),
                        intParameter(action, "expectedMinimumItems"), id);
                case CRAFT, SMELT, FILL_WATER -> {
                    int output = intParameter(action, "totalOutput");
                    resources.addItem(action.target(), output, id);
                    ToolProfile tool = profile(action.target());
                    if (tool != null) {
                        resources.addDurability(tool.item(),
                                Math.multiplyExact(output, tool.durability() - 1), id);
                    }
                }
                case EQUIP, DELIVER -> {
                    // No inventory output.
                }
            }
        }
        return nodes;
    }

    private static void addPriorReaders(
            Map<String, LinkedHashSet<Integer>> readers,
            String item,
            Set<Integer> dependencies) {
        Set<Integer> prior = readers.get(item);
        if (prior != null) dependencies.addAll(prior);
    }

    private static int schedulingRank(
            ResourcePlanner.Action action,
            String retainedWorkstation) {
        if (action.kind() == ResourcePlanner.ActionKind.MINE
                || action.kind() == ResourcePlanner.ActionKind.HUNT) return 0;
        String workstation = action.parameters().get("workstation");
        if (workstation != null && workstation.equals(retainedWorkstation)) return 1;
        if (workstation == null) return 2;
        return 3;
    }

    private static Map<String, Integer> initialDurability(NormalizedRequest normalized) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (ToolProfile tool : PICKAXES) {
            int count = normalized.inventory().counts().getOrDefault(tool.item(), 0);
            if (count <= 0) continue;
            int capacity = normalized.usableDurability().containsKey(tool.item())
                    ? normalized.usableDurability().get(tool.item())
                    : Math.multiplyExact(count, tool.durability());
            int safeCapacity = Math.max(0, capacity - count);
            if (safeCapacity > 0) result.put(tool.item(), safeCapacity);
        }
        return result;
    }

    /** Replays the scheduled program as its final non-negative proof. */
    private static void replaySchedule(
            List<ResourcePlanner.Action> actions,
            NormalizedRequest normalized,
            Map<String, Integer> expectedProjected) {
        LinkedHashMap<String, Integer> counts =
                new LinkedHashMap<>(normalized.inventory().counts());
        LinkedHashMap<String, Integer> durability =
                new LinkedHashMap<>(initialDurability(normalized));
        String activeBurnPool = null;
        int activeBurnCredit = 0;
        LinkedHashSet<String> establishedFloors = new LinkedHashSet<>();
        normalized.goalFloors().forEach((item, floor) -> {
            if (counts.getOrDefault(item, 0) >= floor) establishedFloors.add(item);
        });

        for (ResourcePlanner.Action action : actions) {
            if (action.kind() != ResourcePlanner.ActionKind.SMELT) {
                // Burn credit is accepted only across an immediately adjacent furnace action.
                // Arbitrary routing/crafting/mining time cannot preserve a bounded live burn.
                activeBurnPool = null;
                activeBurnCredit = 0;
            }
            switch (action.kind()) {
                case MINE -> {
                    String tool = action.parameters().get("plannedTool");
                    if (tool != null) {
                        requireCount(counts, tool, 1, action);
                        spendToolDurability(
                                counts, durability, tool, action.count(), action);
                    }
                    add(counts, action.target(),
                            intParameter(action, "expectedMinimumItems"));
                }
                case HUNT -> add(counts, action.target(),
                        intParameter(action, "expectedMinimumItems"));
                case EQUIP -> {
                    requireCount(counts, action.target(), 1, action);
                    if (profile(action.target()) != null
                            && durability.getOrDefault(action.target(), 0) < 1) {
                        throw new IllegalStateException(
                                "scheduled EQUIP lacks safe durability: " + action);
                    }
                }
                case DELIVER -> spendPhysicalItems(
                        counts, durability, action.target(), action.count(), "item", action);
                case CRAFT, SMELT, FILL_WATER -> {
                    String workstation = action.parameters().get("workstation");
                    if (workstation != null
                            && !normalized.inventory().availableStations()
                                    .contains(workstation)) {
                        requireCount(counts, workstation, 1, action);
                    }
                    parseDescribedCounts(action.parameters().get("ingredients"))
                            .forEach((item, count) ->
                                    spendPhysicalItems(
                                            counts, durability, item, count,
                                            "ingredient", action));
                    if (action.kind() == ResourcePlanner.ActionKind.SMELT) {
                        String fuel = action.parameters().getOrDefault("fuelItem", "coal");
                        int fuelCount = nonNegativeParameter(action, "fuelCount", 0);
                        if (fuelCount > 0) spend(counts, fuel, fuelCount, "fuel", action);
                        int operations = intParameter(action, "operations");
                        int perFuel = intParameter(action, "operationsPerFuel");
                        int transitionHeadroom = nonNegativeParameter(
                                action, "burnTransitionHeadroom", 0);
                        int requiredBurn = Math.addExact(operations, transitionHeadroom);
                        String pool = action.parameters().getOrDefault(
                                "burnPool", workstation + "|" + fuel + "|" + perFuel);
                        int retained = pool.equals(activeBurnPool) ? activeBurnCredit : 0;
                        int funded = Math.addExact(retained,
                                Math.multiplyExact(fuelCount, perFuel));
                        if (funded < requiredBurn) {
                            throw new IllegalStateException(
                                    "scheduled smelt overspent retained burn credit: " + action);
                        }
                        activeBurnPool = pool;
                        activeBurnCredit = funded - requiredBurn;
                    }
                    int output = intParameter(action, "totalOutput");
                    add(counts, action.target(), output);
                    ToolProfile tool = profile(action.target());
                    if (tool != null) {
                        add(durability, tool.item(),
                                Math.multiplyExact(output, tool.durability() - 1));
                    }
                }
            }

            normalized.goalFloors().forEach((item, floor) -> {
                int count = counts.getOrDefault(item, 0);
                if (count >= floor) establishedFloors.add(item);
                if (establishedFloors.contains(item) && count < floor) {
                    throw new IllegalStateException(
                            "scheduled action spent protected cargo " + item + " below " + floor);
                }
            });
        }

        LinkedHashMap<String, Integer> actual = positiveCounts(counts);
        LinkedHashMap<String, Integer> expected = positiveCounts(expectedProjected);
        if (!actual.equals(expected)) {
            throw new IllegalStateException("scheduled acquisition replay disagrees with allocation: "
                    + "expected=" + expected + ", actual=" + actual);
        }
        normalized.goalFloors().forEach((item, floor) -> {
            if (actual.getOrDefault(item, 0) < floor) {
                throw new IllegalStateException(
                        "scheduled acquisition did not preserve goal " + item + "=" + floor);
            }
        });
    }

    private static LinkedHashMap<String, Integer> positiveCounts(Map<String, Integer> source) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        source.forEach((item, count) -> {
            if (count != null && count > 0) result.put(item, count);
        });
        return result;
    }

    private static void requireCount(
            Map<String, Integer> counts,
            String item,
            int amount,
            ResourcePlanner.Action action) {
        if (counts.getOrDefault(item, 0) < amount) {
            throw new IllegalStateException("scheduled action lacks " + amount + " " + item
                    + ": " + action);
        }
    }

    private static void spendToolDurability(
            Map<String, Integer> counts,
            Map<String, Integer> durability,
            String tool,
            int amount,
            ResourcePlanner.Action action) {
        spend(durability, tool, amount, "durability", action);
        // The public request currently carries aggregate durability per item type, not exact
        // per-stack durability. Aggregate thresholds cannot prove how many physical stacks broke
        // (66+66 and 131+1 have the same total but different break boundaries), so scheduled
        // replay deliberately leaves item counts unchanged. Exact Home allocation uses its own
        // stack-origin durability ledger and live execution binds EQUIP to a serviceable stack.
    }

    private static void spendPhysicalItems(
            Map<String, Integer> counts,
            Map<String, Integer> durability,
            String item,
            int amount,
            String resource,
            ResourcePlanner.Action action) {
        spend(counts, item, amount, resource, action);
        ToolProfile tool = profile(item);
        if (tool == null) return;

        // A recipe/delivery may select any same-item stack.  With aggregate durability only,
        // the safe proof must assume the consumed units were the healthiest physical stacks.
        // Removing up to one full safe-capacity tranche per unit prevents ghost durability from
        // surviving after its owning pickaxe item has left the inventory.
        int before = durability.getOrDefault(item, 0);
        int removed = Math.min(before,
                Math.multiplyExact(amount, tool.durability() - 1));
        int after = before - removed;
        if (counts.getOrDefault(item, 0) == 0 || after == 0) durability.remove(item);
        else durability.put(item, after);
    }

    private static void spend(
            Map<String, Integer> values,
            String item,
            int amount,
            String resource,
            ResourcePlanner.Action action) {
        if (amount < 0) throw new IllegalArgumentException("spent amount cannot be negative");
        int before = values.getOrDefault(item, 0);
        if (before < amount) {
            throw new IllegalStateException("scheduled action overspent " + resource + " "
                    + item + "=" + before + " by " + amount + ": " + action);
        }
        int after = before - amount;
        if (after == 0) values.remove(item);
        else values.put(item, after);
    }

    private static void add(Map<String, Integer> values, String item, int amount) {
        if (amount <= 0) throw new IllegalArgumentException("added amount must be positive");
        values.merge(item, amount, Math::addExact);
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

    private record DemandNode(
            int id,
            ResourcePlanner.Action action,
            Set<Integer> dependencies,
            Integer furnacePredecessor,
            boolean burnContinuation) {
    }

    /** Quantity ownership used only while allocating producer edges. */
    private static final class OwnedResourceLedger {
        private final LinkedHashMap<String, ArrayDeque<OwnedQuantity>> items =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, ArrayDeque<OwnedQuantity>> retainedItems =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, ArrayDeque<OwnedQuantity>> durability =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> remainingGoalFloors =
                new LinkedHashMap<>();

        private OwnedResourceLedger(Map<String, Integer> goalFloors) {
            Objects.requireNonNull(goalFloors, "goalFloors").forEach((item, floor) -> {
                if (floor > 0) remainingGoalFloors.put(item, floor);
            });
        }

        private void seedInitialItem(String item, int amount) {
            addItemWithFloor(item, amount, -1);
        }

        private void addItem(String item, int amount, int owner) {
            addItemWithFloor(item, amount, owner);
        }

        private void addItemWithFloor(String item, int amount, int owner) {
            if (amount <= 0) throw new IllegalArgumentException("owned amount must be positive");
            int missingFloor = remainingGoalFloors.getOrDefault(item, 0);
            int retained = Math.min(amount, missingFloor);
            if (retained > 0) {
                add(retainedItems, item, retained, owner);
                int remaining = missingFloor - retained;
                if (remaining == 0) remainingGoalFloors.remove(item);
                else remainingGoalFloors.put(item, remaining);
            }
            int spendable = amount - retained;
            if (spendable > 0) add(items, item, spendable, owner);
        }

        private void addDurability(String tool, int amount, int owner) {
            add(durability, tool, amount, owner);
        }

        private void consumeItem(String item, int amount, Set<Integer> dependencies) {
            int spendable = ownedAmount(items, item);
            boolean floorIncomplete = remainingGoalFloors.containsKey(item);
            int provisional = floorIncomplete ? ownedAmount(retainedItems, item) : 0;
            int available = Math.addExact(spendable, provisional);
            if (available < amount) {
                throw new IllegalStateException(
                        "allocated action lacks " + (amount - available) + " " + item);
            }

            ArrayList<OwnedQuantity> consumed = new ArrayList<>();
            int spendableAmount = Math.min(amount, spendable);
            if (spendableAmount > 0) {
                consumed.addAll(take(items, item, spendableAmount, dependencies));
            }
            int provisionalAmount = amount - spendableAmount;
            if (provisionalAmount > 0) {
                // A partial goal floor is not yet durable cargo. It may fund the
                // tool or workstation needed to complete that very floor, but
                // consuming it reopens the exact retained quantity. Once the
                // floor is complete, remainingGoalFloors has no entry and the
                // retained lot is strictly unavailable to consumers.
                consumed.addAll(take(
                        retainedItems, item, provisionalAmount, dependencies));
                remainingGoalFloors.merge(item, provisionalAmount, Math::addExact);
            }

            ToolProfile tool = profile(item);
            if (tool == null) return;
            int safePerItem = tool.durability() - 1;
            for (OwnedQuantity quantity : consumed) {
                discardOwnedDurability(
                        item,
                        Math.multiplyExact(quantity.amount(), safePerItem),
                        quantity.owner(),
                        dependencies);
            }
        }

        private void consumeDurability(String tool, int amount, Set<Integer> dependencies) {
            consume(durability, tool, amount, dependencies, true);
        }

        private void requireItem(String item, int amount, Set<Integer> dependencies) {
            int remaining = require(retainedItems, item, amount, dependencies);
            if (remaining > 0) {
                remaining = require(items, item, remaining, dependencies);
            }
            if (remaining > 0) {
                throw new IllegalStateException(
                        "allocated action lacks " + remaining + " " + item);
            }
        }

        private void requireDurability(String tool, int amount, Set<Integer> dependencies) {
            int remaining = require(durability, tool, amount, dependencies);
            if (remaining > 0) {
                throw new IllegalStateException(
                        "allocated action lacks " + remaining + " safe " + tool + " durability");
            }
        }

        private static int require(
                Map<String, ArrayDeque<OwnedQuantity>> resources,
                String item,
                int amount,
                Set<Integer> dependencies) {
            ArrayDeque<OwnedQuantity> available = resources.get(item);
            if (available == null) return amount;
            int remaining = amount;
            for (OwnedQuantity quantity : available) {
                int used = Math.min(remaining, quantity.amount());
                if (used > 0 && quantity.owner() >= 0) dependencies.add(quantity.owner());
                remaining -= used;
                if (remaining == 0) break;
            }
            return remaining;
        }

        private static void add(
                Map<String, ArrayDeque<OwnedQuantity>> resources,
                String item,
                int amount,
                int owner) {
            if (amount <= 0) throw new IllegalArgumentException("owned amount must be positive");
            resources.computeIfAbsent(item, ignored -> new ArrayDeque<>())
                    .addLast(new OwnedQuantity(owner, amount));
        }

        private static int ownedAmount(
                Map<String, ArrayDeque<OwnedQuantity>> resources,
                String item) {
            ArrayDeque<OwnedQuantity> available = resources.get(item);
            if (available == null) return 0;
            int total = 0;
            for (OwnedQuantity quantity : available) {
                total = Math.addExact(total, quantity.amount());
            }
            return total;
        }

        private static void consume(
                Map<String, ArrayDeque<OwnedQuantity>> resources,
                String item,
                int amount,
                Set<Integer> dependencies,
                boolean remove) {
            if (amount <= 0) throw new IllegalArgumentException("required amount must be positive");
            ArrayDeque<OwnedQuantity> available = resources.get(item);
            if (available == null) {
                throw new IllegalStateException("allocated action lacks " + amount + " " + item);
            }
            int remaining = amount;
            if (!remove) {
                for (OwnedQuantity quantity : available) {
                    int used = Math.min(remaining, quantity.amount());
                    if (used > 0 && quantity.owner() >= 0) dependencies.add(quantity.owner());
                    remaining -= used;
                    if (remaining == 0) return;
                }
            } else {
                while (remaining > 0 && !available.isEmpty()) {
                    OwnedQuantity quantity = available.removeFirst();
                    int used = Math.min(remaining, quantity.amount());
                    if (quantity.owner() >= 0) dependencies.add(quantity.owner());
                    remaining -= used;
                    int leftover = quantity.amount() - used;
                    if (leftover > 0) {
                        available.addFirst(new OwnedQuantity(quantity.owner(), leftover));
                    }
                }
            }
            if (remaining > 0) {
                throw new IllegalStateException("allocated action lacks " + remaining + " " + item);
            }
        }

        private static List<OwnedQuantity> take(
                Map<String, ArrayDeque<OwnedQuantity>> resources,
                String item,
                int amount,
                Set<Integer> dependencies) {
            if (amount <= 0) throw new IllegalArgumentException("required amount must be positive");
            ArrayDeque<OwnedQuantity> available = resources.get(item);
            if (available == null) {
                throw new IllegalStateException("allocated action lacks " + amount + " " + item);
            }
            ArrayList<OwnedQuantity> result = new ArrayList<>();
            int remaining = amount;
            while (remaining > 0 && !available.isEmpty()) {
                OwnedQuantity quantity = available.removeFirst();
                int used = Math.min(remaining, quantity.amount());
                if (quantity.owner() >= 0) dependencies.add(quantity.owner());
                result.add(new OwnedQuantity(quantity.owner(), used));
                remaining -= used;
                int leftover = quantity.amount() - used;
                if (leftover > 0) {
                    available.addFirst(new OwnedQuantity(quantity.owner(), leftover));
                }
            }
            if (remaining > 0) {
                throw new IllegalStateException("allocated action lacks " + remaining + " " + item);
            }
            return List.copyOf(result);
        }

        private void discardOwnedDurability(
                String tool,
                int maximum,
                int owner,
                Set<Integer> dependencies) {
            if (maximum <= 0) return;
            ArrayDeque<OwnedQuantity> available = durability.get(tool);
            if (available == null) return;
            ArrayDeque<OwnedQuantity> retained = new ArrayDeque<>();
            int remaining = maximum;
            while (!available.isEmpty()) {
                OwnedQuantity quantity = available.removeFirst();
                if (remaining > 0 && quantity.owner() == owner) {
                    int removed = Math.min(remaining, quantity.amount());
                    if (removed > 0 && quantity.owner() >= 0) {
                        dependencies.add(quantity.owner());
                    }
                    remaining -= removed;
                    int leftover = quantity.amount() - removed;
                    if (leftover > 0) {
                        retained.addLast(new OwnedQuantity(quantity.owner(), leftover));
                    }
                } else {
                    retained.addLast(quantity);
                }
            }
            available.addAll(retained);
        }
    }

    private record OwnedQuantity(int owner, int amount) {
    }

    /**
     * Deterministically combines adjacent identical work leaves. Recursive
     * dependencies are never reordered, so prerequisite causality is
     * preserved. Shared-ledger planning already eliminates most duplicates;
     * this pass removes the compatible remainder without inventing execution
     * semantics.
     */
    private static List<ResourcePlanner.Action> coalesceAdjacent(
            List<ResourcePlanner.Action> source) {
        ArrayList<ResourcePlanner.Action> result = new ArrayList<>();
        for (ResourcePlanner.Action action : source) {
            if (!result.isEmpty()) {
                ResourcePlanner.Action merged = mergeCompatible(result.getLast(), action);
                if (merged != null) {
                    result.set(result.size() - 1, merged);
                    continue;
                }
            }
            result.add(action);
        }
        return List.copyOf(result);
    }

    private static ResourcePlanner.Action mergeCompatible(
            ResourcePlanner.Action left,
            ResourcePlanner.Action right) {
        if (left.kind() != right.kind() || !left.target().equals(right.target())) return null;
        if (left.kind() == ResourcePlanner.ActionKind.EQUIP
                || left.kind() == ResourcePlanner.ActionKind.DELIVER) return null;

        LinkedHashMap<String, String> parameters = new LinkedHashMap<>(left.parameters());
        int combinedCount = Math.addExact(left.count(), right.count());
        switch (left.kind()) {
            case MINE -> {
                if (!sameExcept(left.parameters(), right.parameters(), Set.of("expectedMinimumItems"))) {
                    return null;
                }
                parameters.put("expectedMinimumItems", Integer.toString(Math.addExact(
                        intParameter(left, "expectedMinimumItems"),
                        intParameter(right, "expectedMinimumItems"))));
            }
            case HUNT -> {
                if (!sameExcept(left.parameters(), right.parameters(), Set.of("expectedMinimumItems"))) {
                    return null;
                }
                parameters.put("expectedMinimumItems", Integer.toString(Math.addExact(
                        intParameter(left, "expectedMinimumItems"),
                        intParameter(right, "expectedMinimumItems"))));
            }
            case CRAFT, SMELT -> {
                Set<String> additive = left.kind() == ResourcePlanner.ActionKind.SMELT
                        ? Set.of("operations", "totalOutput", "ingredients", "fuelCount")
                        : Set.of("operations", "totalOutput", "ingredients");
                if (!sameExcept(left.parameters(), right.parameters(), additive)) return null;
                parameters.put("operations", Integer.toString(combinedCount));
                parameters.put("totalOutput", Integer.toString(Math.addExact(
                        intParameter(left, "totalOutput"), intParameter(right, "totalOutput"))));
                parameters.put("ingredients", describeCounts(mergeDescribedCounts(
                        left.parameters().get("ingredients"), right.parameters().get("ingredients"))));
                if (left.kind() == ResourcePlanner.ActionKind.SMELT) {
                    parameters.put("fuelCount", Integer.toString(Math.addExact(
                            nonNegativeParameter(left, "fuelCount", 0),
                            nonNegativeParameter(right, "fuelCount", 0))));
                }
            }
            default -> {
                return null;
            }
        }
        return new ResourcePlanner.Action(left.kind(), left.target(), combinedCount, parameters,
                titleCase(left.kind().name()) + " coalesced " + left.target()
                        + " in " + combinedCount + " operation(s)");
    }

    private static boolean sameExcept(
            Map<String, String> left,
            Map<String, String> right,
            Set<String> ignored) {
        LinkedHashMap<String, String> leftCopy = new LinkedHashMap<>(left);
        LinkedHashMap<String, String> rightCopy = new LinkedHashMap<>(right);
        ignored.forEach(leftCopy::remove);
        ignored.forEach(rightCopy::remove);
        return leftCopy.equals(rightCopy);
    }

    private static int intParameter(ResourcePlanner.Action action, String key) {
        return Integer.parseInt(action.parameters().get(key));
    }

    private static Map<String, Integer> mergeDescribedCounts(String left, String right) {
        LinkedHashMap<String, Integer> result = parseDescribedCounts(left);
        parseDescribedCounts(right).forEach((item, count) ->
                result.merge(item, count, Math::addExact));
        return result;
    }

    private static LinkedHashMap<String, Integer> parseDescribedCounts(String description) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        if (description == null || description.isBlank()) return result;
        for (String entry : description.split(",")) {
            int separator = entry.lastIndexOf('=');
            if (separator <= 0) throw new IllegalStateException("invalid ingredient accounting: " + entry);
            result.merge(entry.substring(0, separator),
                    Integer.parseInt(entry.substring(separator + 1)), Math::addExact);
        }
        return result;
    }

    private static List<ResourcePlanner.Action> annotateWorkstationLifetime(
            List<ResourcePlanner.Action> actions,
            Set<String> availableStations) {
        ArrayList<ResourcePlanner.Action> annotated = new ArrayList<>(actions);
        LinkedHashSet<String> laterUseInGeneration = new LinkedHashSet<>();
        for (int index = actions.size() - 1; index >= 0; index--) {
            ResourcePlanner.Action action = actions.get(index);
            // Consuming a physical station ends the preceding placement generation. A later use
            // must open/place a new generation and cannot keep the older station retained.
            for (String consumed :
                    parseDescribedCounts(action.parameters().get("ingredients")).keySet()) {
                if (!availableStations.contains(consumed)) {
                    laterUseInGeneration.remove(consumed);
                }
            }
            String workstation = action.parameters().get("workstation");
            if (workstation == null) {
                continue;
            }
            boolean last = !laterUseInGeneration.contains(workstation);
            LinkedHashMap<String, String> parameters = new LinkedHashMap<>(action.parameters());
            parameters.put("retainWorkstation", Boolean.toString(!last));
            parameters.put("lastWorkstationUse", Boolean.toString(last));
            annotated.set(index, new ResourcePlanner.Action(
                    action.kind(), action.target(), action.count(), parameters, action.description()));
            laterUseInGeneration.add(workstation);
        }
        return List.copyOf(annotated);
    }

    private static ToolProfile profile(String item) {
        for (ToolProfile profile : PICKAXES) {
            if (profile.item().equals(item)) return profile;
        }
        return null;
    }

    private static ResourcePlanner.PlanningException cycle(String item, Deque<String> active) {
        ArrayList<String> path = new ArrayList<>(active);
        int start = path.indexOf(item);
        if (start > 0) path = new ArrayList<>(path.subList(start, path.size()));
        path.add(item);
        return new ResourcePlanner.PlanningException(
                "dependency cycle detected: " + String.join(" -> ", path), path);
    }

    private static ResourcePlanner.PlanningException unsupported(
            String item,
            Deque<String> active) {
        ArrayList<String> path = new ArrayList<>(active);
        return new ResourcePlanner.PlanningException(
                "no recipe, mining, or hunting source for " + item
                        + " (dependency path: " + String.join(" -> ", path) + ")",
                path);
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

    private static String burnPoolKey(ResourceCatalog.RecipeVariant recipe) {
        return burnPoolKey(
                recipe.workstation(), recipe.fuelItem(), recipe.operationsPerFuel());
    }

    private static String burnPoolKey(
            String workstation,
            String fuelItem,
            int operationsPerFuel) {
        return workstation + "|" + fuelItem + "|" + operationsPerFuel;
    }

    private static String recipeIdentity(ResourceCatalog.RecipeVariant recipe) {
        if (recipe.metadata().recipeId() != null) {
            return "id:" + recipe.metadata().recipeId();
        }
        ArrayList<String> ingredients = new ArrayList<>();
        for (ResourceCatalog.IngredientChoice choice : recipe.ingredients()) {
            ingredients.add(choice.count() + "*[" + String.join("|", choice.alternatives()) + "]");
        }
        return "struct:" + recipe.kind() + ":" + recipe.output() + ":"
                + recipe.outputCount() + ":" + recipe.workstation() + ":"
                + String.join("+", ingredients);
    }

    private static String describeCounts(Map<String, Integer> values) {
        ArrayList<String> parts = new ArrayList<>();
        values.forEach((item, count) -> parts.add(item + "=" + count));
        return String.join(",", parts);
    }

    private static String titleCase(String value) {
        return value.charAt(0) + value.substring(1).toLowerCase();
    }

    private record ToolProfile(
            String item,
            ResourceCatalog.ToolTier tier,
            int durability) {
    }

    private record FuelKind(String item, int operationsPerItem) {
    }

    private record RecipeCandidate(
            int catalogIndex,
            ResourceCatalog.RecipeVariant variant,
            boolean ingredientsCarried,
            boolean sourcesAvailable,
            boolean canonicalOutputRecipe) {
        private RecipeCandidate {
            if (catalogIndex < 0) {
                throw new IllegalArgumentException("recipe catalog index cannot be negative");
            }
            Objects.requireNonNull(variant, "variant");
        }
    }

    private record RetainedFuel(FuelKind kind, int operations) {
        private RetainedFuel {
            Objects.requireNonNull(kind, "kind");
            if (operations <= 0) {
                throw new IllegalArgumentException("retained fuel operations must be positive");
            }
        }
    }

    private record SmeltFuelSlice(
            String item,
            int count,
            int operationsPerFuel,
            int operations,
            int transitionHeadroom) {
    }

    private record FuelBundle(
            int coal,
            int charcoal,
            int coalBlocks,
            int effectiveOperations) {
        private static final FuelBundle EMPTY = new FuelBundle(0, 0, 0, 0);

        private int itemCount() {
            return Math.addExact(Math.addExact(coal, charcoal), coalBlocks);
        }
    }

    private record NormalizedRequest(
            List<AcquisitionRequest.ItemGoal> goals,
            Map<String, Integer> goalFloors,
            NormalizedInventory inventory,
            Map<String, Integer> usableDurability,
            Set<String> unavailableHarvestSources) {
    }

    private record NormalizedInventory(
            Map<String, Integer> counts,
            Set<String> equipped,
            Set<String> locallyAvailable,
            Set<String> availableStations) {
    }

    private static final class Context {
        private final Ledger ledger;
        private final Map<String, Integer> goalFloors;
        private final ArrayList<ResourcePlanner.Action> actions = new ArrayList<>();
        private final ArrayList<ResourcePlanner.TraceStep> trace = new ArrayList<>();
        private final ArrayDeque<String> active = new ArrayDeque<>();
        private final Set<String> locallyAvailable;
        private final Set<String> availableStations;
        private final Set<String> unavailableHarvestSources;
        private final LinkedHashMap<String, Integer> furnaceBurnCredits = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> temporaryConsumableFloors =
                new LinkedHashMap<>();

        private Context(
                NormalizedInventory inventory,
                Map<String, Integer> goalFloors,
                Map<String, Integer> explicitDurability,
                Set<String> unavailableHarvestSources) {
            this.ledger = new Ledger(inventory.counts(), inventory.equipped(), explicitDurability);
            this.goalFloors = goalFloors;
            this.locallyAvailable = inventory.locallyAvailable();
            this.availableStations = inventory.availableStations();
            this.unavailableHarvestSources = unavailableHarvestSources;
        }

        private int consumableFloor(String item) {
            return Math.max(
                    goalFloors.getOrDefault(item, 0),
                    temporaryConsumableFloors.getOrDefault(item, 0));
        }

        /** Physical units available to a direct consumer after every durable cargo floor. */
        private int spendableConsumable(String item) {
            return Math.max(0, ledger.count(item) - consumableFloor(item));
        }

        private Map<String, Integer> temporaryConsumableFloors() {
            return new LinkedHashMap<>(temporaryConsumableFloors);
        }

        private void addTemporaryConsumableFloor(String item, int floor) {
            if (floor > 0) temporaryConsumableFloors.merge(item, floor, Math::max);
        }

        private void restoreTemporaryConsumableFloors(Map<String, Integer> floors) {
            temporaryConsumableFloors.clear();
            temporaryConsumableFloors.putAll(floors);
        }

        private Checkpoint checkpoint() {
            return new Checkpoint(ledger.state(), actions.size(), trace.size(),
                    new LinkedHashMap<>(furnaceBurnCredits));
        }

        private void restore(Checkpoint checkpoint) {
            ledger.restore(checkpoint.ledgerState());
            actions.subList(checkpoint.actionCount(), actions.size()).clear();
            trace.subList(checkpoint.traceCount(), trace.size()).clear();
            furnaceBurnCredits.clear();
            furnaceBurnCredits.putAll(checkpoint.furnaceBurnCredits());
        }

        private record Checkpoint(
                Ledger.State ledgerState,
                int actionCount,
                int traceCount,
                Map<String, Integer> furnaceBurnCredits) {
        }
    }

    private static final class Ledger {
        private final LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        private final LinkedHashSet<String> equipped = new LinkedHashSet<>();
        private final LinkedHashMap<String, Integer> durability = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> produced = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> consumed = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> toolUse = new LinkedHashMap<>();

        private Ledger(
                Map<String, Integer> initialCounts,
                Set<String> initiallyEquipped,
                Map<String, Integer> explicitDurability) {
            counts.putAll(initialCounts);
            equipped.addAll(initiallyEquipped);
            for (ToolProfile profile : PICKAXES) {
                int count = counts.getOrDefault(profile.item(), 0);
                if (count <= 0) continue;
                int capacity = explicitDurability.containsKey(profile.item())
                        ? explicitDurability.get(profile.item())
                        : Math.multiplyExact(count, profile.durability());
                durability.put(profile.item(), capacity);
            }
        }

        private int count(String item) {
            return counts.getOrDefault(item, 0);
        }

        private void add(String item, int amount) {
            if (amount <= 0) throw new IllegalArgumentException("added amount must be positive");
            counts.merge(item, amount, Math::addExact);
            produced.merge(item, amount, Math::addExact);
            ToolProfile profile = profile(item);
            if (profile != null) {
                durability.merge(item,
                        Math.multiplyExact(amount, profile.durability()), Math::addExact);
            }
        }

        private void consume(String item, int amount) {
            int before = count(item);
            if (amount <= 0 || before < amount) {
                throw new IllegalStateException(
                        "cannot consume " + amount + " " + item + " from " + before);
            }
            int after = before - amount;
            if (after == 0) counts.remove(item);
            else counts.put(item, after);
            consumed.merge(item, amount, Math::addExact);

            ToolProfile tool = profile(item);
            if (tool != null) {
                // Ingredient slot selection is not stack-bound in the planning input. Assume the
                // healthiest possible units were consumed so no durability can outlive its
                // physical pickaxe. Any tool-item consumption also invalidates type-only equipped
                // evidence; a later mining segment must explicitly bind a surviving/new stack.
                int durabilityBefore = usableDurability(item);
                int removed = Math.min(durabilityBefore,
                        Math.multiplyExact(amount, tool.durability()));
                int durabilityAfter = durabilityBefore - removed;
                if (after == 0 || durabilityAfter == 0) durability.remove(item);
                else durability.put(item, durabilityAfter);
                equipped.remove(item);
            }
        }

        private int usableDurability(String tool) {
            return durability.getOrDefault(tool, 0);
        }

        private void useDurability(String tool, int amount) {
            int before = usableDurability(tool);
            if (amount <= 0 || before < amount) {
                throw new IllegalStateException(
                        "cannot spend " + amount + " durability from " + tool + "=" + before);
            }
            int after = before - amount;
            durability.put(tool, after);
            toolUse.merge(tool, amount, Math::addExact);
        }

        private boolean isEquipped(String item) {
            return equipped.contains(item);
        }

        private void equip(String item) {
            equipped.add(item);
        }

        private Map<String, Integer> countsSnapshot() {
            return new LinkedHashMap<>(counts);
        }

        private Map<String, Integer> durabilitySnapshot() {
            return new LinkedHashMap<>(durability);
        }

        private Map<String, Integer> producedSnapshot() {
            return new LinkedHashMap<>(produced);
        }

        private Map<String, Integer> consumedSnapshot() {
            return new LinkedHashMap<>(consumed);
        }

        private Map<String, Integer> toolUseSnapshot() {
            return new LinkedHashMap<>(toolUse);
        }

        private State state() {
            return new State(countsSnapshot(), new LinkedHashSet<>(equipped),
                    durabilitySnapshot(), producedSnapshot(), consumedSnapshot(), toolUseSnapshot());
        }

        private void restore(State state) {
            restoreMap(counts, state.counts());
            equipped.clear();
            equipped.addAll(state.equipped());
            restoreMap(durability, state.durability());
            restoreMap(produced, state.produced());
            restoreMap(consumed, state.consumed());
            restoreMap(toolUse, state.toolUse());
        }

        private static void restoreMap(
                LinkedHashMap<String, Integer> target,
                Map<String, Integer> source) {
            target.clear();
            target.putAll(source);
        }

        private record State(
                Map<String, Integer> counts,
                Set<String> equipped,
                Map<String, Integer> durability,
                Map<String, Integer> produced,
                Map<String, Integer> consumed,
                Map<String, Integer> toolUse) {
        }
    }
}
