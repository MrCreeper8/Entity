package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Minecraft-independent recipe, mining, and hunting knowledge used by Entity's
 * resource planner. Identifiers are canonical, namespace-free item IDs.
 */
public final class ResourceCatalog {
    public enum ToolTier {
        NONE(0), WOOD(1), STONE(2), IRON(3), DIAMOND(4);

        private final int rank;

        ToolTier(int rank) {
            this.rank = rank;
        }

        public boolean satisfies(ToolTier required) {
            return rank >= Objects.requireNonNull(required, "required").rank;
        }
    }

    public enum ProductionKind {
        CRAFT,
        SMELT
    }

    /**
     * The player-facing authority needed to collect a world source.  The
     * legacy planner still emits a MINE leaf for both modes, but retaining the
     * distinction in the catalog lets the final actuator enforce a mining
     * volume for extraction and a harvesting footprint for renewable plants
     * and rooted trees.
     */
    public enum WorldSourceKind {
        EXTRACTION,
        HARVEST
    }

    public enum RecipeLayout {
        SHAPED,
        SHAPELESS,
        COOKING,
        UNKNOWN
    }

    /**
     * Optional execution metadata retained from a runtime recipe registry.
     * For shaped recipes, slots are row-major and an empty alternative list
     * denotes an empty grid cell. For shapeless recipes each list is one
     * ingredient slot. Planning uses aggregated {@link IngredientChoice}s;
     * this metadata preserves the exact layout needed by a manual crafter.
     */
    public record RecipeMetadata(
            String recipeId,
            RecipeLayout layout,
            int width,
            int height,
            List<List<String>> slots) {
        public RecipeMetadata {
            recipeId = recipeId == null || recipeId.isBlank()
                    ? null : normalizeRecipeId(recipeId);
            layout = Objects.requireNonNull(layout, "layout");
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("recipe width/height cannot be negative");
            }
            Objects.requireNonNull(slots, "slots");
            ArrayList<List<String>> slotCopy = new ArrayList<>(slots.size());
            for (List<String> rawSlot : slots) {
                Objects.requireNonNull(rawSlot, "recipe slot");
                LinkedHashSet<String> alternatives = new LinkedHashSet<>();
                for (String rawAlternative : rawSlot) {
                    alternatives.add(requireId(rawAlternative, "recipe slot alternative"));
                }
                slotCopy.add(List.copyOf(alternatives));
            }
            slots = List.copyOf(slotCopy);
            if (layout == RecipeLayout.SHAPED) {
                if (width <= 0 || height <= 0 || slots.size() != width * height) {
                    throw new IllegalArgumentException(
                            "shaped recipe slots must exactly match positive width*height");
                }
            } else if (width != 0 || height != 0) {
                throw new IllegalArgumentException(
                        "only shaped recipes may declare width/height");
            }
        }

        public static RecipeMetadata unknown() {
            return new RecipeMetadata(null, RecipeLayout.UNKNOWN, 0, 0, List.of());
        }
    }

    /**
     * One counted recipe ingredient whose item may be selected from an
     * ordered tag/slot alternative set. The count is per recipe operation.
     */
    public record IngredientChoice(int count, List<String> alternatives) {
        public IngredientChoice {
            if (count <= 0) throw new IllegalArgumentException("ingredient count must be positive");
            Objects.requireNonNull(alternatives, "alternatives");
            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException("ingredient alternatives cannot be empty");
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String alternative : alternatives) {
                normalized.add(requireId(alternative, "ingredient alternative"));
            }
            alternatives = List.copyOf(normalized);
        }

        public IngredientChoice(int count, String... alternatives) {
            this(count, List.of(alternatives));
        }
    }

    /**
     * One concrete recipe variant. Multiple variants may produce the same
     * output, and every ingredient choice may represent a vanilla tag or slot
     * alternative. Variant and alternative order are deterministic policy.
     */
    public record RecipeVariant(
            String output,
            int outputCount,
            List<IngredientChoice> ingredients,
            ProductionKind kind,
            String workstation,
            String fuelItem,
            int operationsPerFuel,
            RecipeMetadata metadata) {
        public RecipeVariant {
            output = requireId(output, "output");
            if (outputCount <= 0) throw new IllegalArgumentException("outputCount must be positive");
            Objects.requireNonNull(ingredients, "ingredients");
            ArrayList<IngredientChoice> copy = new ArrayList<>(ingredients.size());
            for (IngredientChoice ingredient : ingredients) {
                copy.add(Objects.requireNonNull(ingredient, "ingredient"));
            }
            ingredients = List.copyOf(copy);
            kind = Objects.requireNonNull(kind, "kind");
            workstation = optionalId(workstation);
            fuelItem = optionalId(fuelItem);
            if (kind == ProductionKind.SMELT) {
                if (fuelItem == null || operationsPerFuel <= 0) {
                    throw new IllegalArgumentException("smelting variants require fuel and positive capacity");
                }
            } else if (fuelItem != null || operationsPerFuel != 0) {
                throw new IllegalArgumentException("crafting variants cannot declare smelting fuel");
            }
            metadata = metadata == null ? RecipeMetadata.unknown() : metadata;
        }

        public RecipeVariant(
                String output,
                int outputCount,
                List<IngredientChoice> ingredients,
                ProductionKind kind,
                String workstation,
                String fuelItem,
                int operationsPerFuel) {
            this(output, outputCount, ingredients, kind, workstation, fuelItem,
                    operationsPerFuel, RecipeMetadata.unknown());
        }

        public static RecipeVariant craft(
                String output,
                int outputCount,
                String workstation,
                IngredientChoice... ingredients) {
            return new RecipeVariant(output, outputCount, List.of(ingredients),
                    ProductionKind.CRAFT, workstation, null, 0, RecipeMetadata.unknown());
        }

        public static RecipeVariant smelt(
                String output,
                int outputCount,
                String workstation,
                String fuelItem,
                int operationsPerFuel,
                IngredientChoice... ingredients) {
            return new RecipeVariant(output, outputCount, List.of(ingredients),
                    ProductionKind.SMELT, workstation, fuelItem, operationsPerFuel,
                    RecipeMetadata.unknown());
        }

        private static RecipeVariant fromLegacy(Recipe recipe) {
            ArrayList<IngredientChoice> ingredients = new ArrayList<>();
            recipe.ingredients().forEach((item, count) ->
                    ingredients.add(new IngredientChoice(count, item)));
            return new RecipeVariant(
                    recipe.output(), recipe.outputCount(), ingredients, recipe.kind(),
                    recipe.workstation(), recipe.fuelItem(), recipe.operationsPerFuel(),
                    RecipeMetadata.unknown());
        }

        /** Compatibility projection used by existing scalar callers. */
        private Recipe toLegacyRecipe() {
            LinkedHashMap<String, Integer> selected = new LinkedHashMap<>();
            for (IngredientChoice ingredient : ingredients) {
                selected.merge(ingredient.alternatives().getFirst(),
                        ingredient.count(), Math::addExact);
            }
            return new Recipe(output, outputCount, selected, kind,
                    workstation, fuelItem, operationsPerFuel);
        }
    }

    /** Type-safe constructor boundary for a multi-variant runtime recipe set. */
    public record RecipeBook(Map<String, List<RecipeVariant>> variants) {
        public RecipeBook {
            Objects.requireNonNull(variants, "variants");
            LinkedHashMap<String, List<RecipeVariant>> copy = new LinkedHashMap<>();
            variants.forEach((rawOutput, rawVariants) -> {
                String output = normalizeBasic(rawOutput);
                Objects.requireNonNull(rawVariants, "recipe variants for " + output);
                if (rawVariants.isEmpty()) {
                    throw new IllegalArgumentException("recipe variants cannot be empty for " + output);
                }
                ArrayList<RecipeVariant> checked = new ArrayList<>(rawVariants.size());
                for (RecipeVariant variant : rawVariants) {
                    RecipeVariant nonNull = Objects.requireNonNull(variant, "recipe variant");
                    if (!output.equals(nonNull.output())) {
                        throw new IllegalArgumentException(
                                "recipe key/output mismatch: " + output + " / " + nonNull.output());
                    }
                    checked.add(nonNull);
                }
                copy.put(output, List.copyOf(checked));
            });
            variants = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * A deterministic production recipe. Ingredient counts are per operation.
     * Smelting fuel capacity is the number of operations one fuel item covers.
     */
    public record Recipe(
            String output,
            int outputCount,
            Map<String, Integer> ingredients,
            ProductionKind kind,
            String workstation,
            String fuelItem,
            int operationsPerFuel
    ) {
        public Recipe {
            output = requireId(output, "output");
            if (outputCount <= 0) throw new IllegalArgumentException("outputCount must be positive");
            Objects.requireNonNull(ingredients, "ingredients");
            LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
            ingredients.forEach((item, count) -> {
                String id = requireId(item, "ingredient");
                if (count == null || count <= 0) {
                    throw new IllegalArgumentException("ingredient count must be positive: " + id);
                }
                copy.merge(id, count, Math::addExact);
            });
            ingredients = Collections.unmodifiableMap(copy);
            kind = Objects.requireNonNull(kind, "kind");
            workstation = optionalId(workstation);
            fuelItem = optionalId(fuelItem);
            if (kind == ProductionKind.SMELT) {
                if (fuelItem == null || operationsPerFuel <= 0) {
                    throw new IllegalArgumentException("smelting recipes require fuel and positive capacity");
                }
            } else if (fuelItem != null || operationsPerFuel != 0) {
                throw new IllegalArgumentException("crafting recipes cannot declare smelting fuel");
            }
        }

        public static Recipe craft(
                String output,
                int outputCount,
                Map<String, Integer> ingredients,
                String workstation
        ) {
            return new Recipe(output, outputCount, ingredients, ProductionKind.CRAFT,
                    workstation, null, 0);
        }

        public static Recipe smelt(
                String output,
                int outputCount,
                Map<String, Integer> ingredients,
                String workstation,
                String fuelItem,
                int operationsPerFuel
        ) {
            return new Recipe(output, outputCount, ingredients, ProductionKind.SMELT,
                    workstation, fuelItem, operationsPerFuel);
        }
    }

    /** The guaranteed minimum yield is deliberately Fortune-independent. */
    public record MineSpec(
            String item,
            List<String> blockAlternatives,
            int minimumDropsPerBlock,
            ToolTier requiredToolTier,
            int estimatedBlocksPerDrop,
            boolean probabilistic,
            WorldSourceKind worldSourceKind,
            String replantItem,
            boolean preferProductionBeforeLocalSalvage
    ) {
        public MineSpec {
            item = requireId(item, "item");
            Objects.requireNonNull(blockAlternatives, "blockAlternatives");
            if (blockAlternatives.isEmpty()) {
                throw new IllegalArgumentException("at least one block alternative is required");
            }
            ArrayList<String> alternatives = new ArrayList<>(blockAlternatives.size());
            for (String block : blockAlternatives) alternatives.add(requireId(block, "block"));
            blockAlternatives = List.copyOf(alternatives);
            if (minimumDropsPerBlock <= 0) {
                throw new IllegalArgumentException("minimumDropsPerBlock must be positive");
            }
            requiredToolTier = Objects.requireNonNull(requiredToolTier, "requiredToolTier");
            worldSourceKind = Objects.requireNonNull(worldSourceKind, "worldSourceKind");
            replantItem = optionalId(replantItem);
            if (replantItem != null && worldSourceKind != WorldSourceKind.HARVEST) {
                throw new IllegalArgumentException(
                        "only a harvesting source can require a replant item");
            }
            if (estimatedBlocksPerDrop <= 0) {
                throw new IllegalArgumentException("estimatedBlocksPerDrop must be positive");
            }
            if (!probabilistic && estimatedBlocksPerDrop != 1) {
                throw new IllegalArgumentException(
                        "deterministic mining must use estimatedBlocksPerDrop=1");
            }
        }

        public MineSpec(
                String item,
                List<String> blockAlternatives,
                int minimumDropsPerBlock,
                ToolTier requiredToolTier,
                int estimatedBlocksPerDrop,
                boolean probabilistic,
                WorldSourceKind worldSourceKind,
                String replantItem) {
            this(item, blockAlternatives, minimumDropsPerBlock, requiredToolTier,
                    estimatedBlocksPerDrop, probabilistic, worldSourceKind, replantItem, false);
        }

        public MineSpec(
                String item,
                List<String> blockAlternatives,
                int minimumDropsPerBlock,
                ToolTier requiredToolTier,
                int estimatedBlocksPerDrop,
                boolean probabilistic) {
            this(item, blockAlternatives, minimumDropsPerBlock, requiredToolTier,
                    estimatedBlocksPerDrop, probabilistic,
                    WorldSourceKind.EXTRACTION, null);
        }

        public MineSpec(
                String item,
                List<String> blockAlternatives,
                int minimumDropsPerBlock,
                ToolTier requiredToolTier) {
            this(item, blockAlternatives, minimumDropsPerBlock, requiredToolTier,
                    1, false, WorldSourceKind.EXTRACTION, null);
        }
    }

    /**
     * A hunting source executable by the existing HUNT leaf.
     *
     * <p>For deterministic sources {@code minimumDropsPerKill} is a real,
     * Looting-independent lower bound. Some useful secondary mob drops have a
     * zero-drop outcome. Those sources are explicitly marked probabilistic;
     * their estimate and retry budget describe bounded planning policy rather
     * than pretending that every kill is guaranteed to produce an item.</p>
     */
    public record HuntSpec(
            String item,
            List<String> entityAlternatives,
            int minimumDropsPerKill,
            int estimatedKillsPerDrop,
            int retryBudgetPerDrop,
            boolean probabilistic
    ) {
        public HuntSpec {
            item = requireId(item, "item");
            Objects.requireNonNull(entityAlternatives, "entityAlternatives");
            if (entityAlternatives.isEmpty()) {
                throw new IllegalArgumentException("at least one entity alternative is required");
            }
            ArrayList<String> alternatives = new ArrayList<>(entityAlternatives.size());
            for (String entity : entityAlternatives) alternatives.add(requireId(entity, "entity"));
            entityAlternatives = List.copyOf(alternatives);
            if (minimumDropsPerKill <= 0) {
                throw new IllegalArgumentException("minimumDropsPerKill must be positive");
            }
            if (estimatedKillsPerDrop <= 0) {
                throw new IllegalArgumentException("estimatedKillsPerDrop must be positive");
            }
            if (retryBudgetPerDrop < estimatedKillsPerDrop) {
                throw new IllegalArgumentException(
                        "retryBudgetPerDrop must cover estimatedKillsPerDrop");
            }
            if (!probabilistic
                    && (estimatedKillsPerDrop != 1 || retryBudgetPerDrop != 1)) {
                throw new IllegalArgumentException(
                        "deterministic hunting must use one estimated/budgeted kill per drop");
            }
        }

        public HuntSpec(
                String item,
                List<String> entityAlternatives,
                int minimumDropsPerKill) {
            this(item, entityAlternatives, minimumDropsPerKill, 1, 1, false);
        }
    }

    private static final ResourceCatalog DEFAULT = createDefault();

    private final Map<String, Recipe> recipes;
    private final Map<String, List<RecipeVariant>> recipeVariants;
    private final Map<String, MineSpec> mineSpecs;
    private final Map<String, HuntSpec> huntSpecs;
    private final Map<String, String> aliases;
    private final Map<String, Integer> recipeOutputCounts;
    private final Map<String, List<String>> miningBlockAlternatives;
    private final Map<String, Set<String>> miningItemsByBlock;

    /** Public to permit policy tests and future datapack-derived catalogs. */
    public ResourceCatalog(
            Map<String, Recipe> recipes,
            Map<String, MineSpec> mineSpecs,
            Map<String, String> aliases
    ) {
        this(recipes, mineSpecs, Map.of(), aliases);
    }

    public ResourceCatalog(
            Map<String, Recipe> recipes,
            Map<String, MineSpec> mineSpecs,
            Map<String, HuntSpec> huntSpecs,
            Map<String, String> aliases
    ) {
        this(legacyRecipeBook(recipes), mineSpecs, huntSpecs, aliases);
    }

    public ResourceCatalog(
            RecipeBook recipes,
            Map<String, MineSpec> mineSpecs,
            Map<String, HuntSpec> huntSpecs,
            Map<String, String> aliases
    ) {
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(mineSpecs, "mineSpecs");
        Objects.requireNonNull(huntSpecs, "huntSpecs");
        Objects.requireNonNull(aliases, "aliases");

        LinkedHashMap<String, List<RecipeVariant>> variantCopy = new LinkedHashMap<>();
        recipes.variants().forEach((key, variants) ->
                variantCopy.put(key, List.copyOf(variants)));
        this.recipeVariants = Collections.unmodifiableMap(variantCopy);

        LinkedHashMap<String, Recipe> recipeCopy = new LinkedHashMap<>();
        variantCopy.forEach((key, variants) ->
                recipeCopy.put(key, variants.getFirst().toLegacyRecipe()));
        this.recipes = Collections.unmodifiableMap(recipeCopy);

        LinkedHashMap<String, MineSpec> mineCopy = new LinkedHashMap<>();
        mineSpecs.forEach((key, spec) -> {
            String id = normalizeBasic(key);
            if (!id.equals(spec.item())) {
                throw new IllegalArgumentException("mine key/item mismatch: " + id + " / " + spec.item());
            }
            mineCopy.put(id, spec);
        });
        this.mineSpecs = Collections.unmodifiableMap(mineCopy);

        LinkedHashMap<String, HuntSpec> huntCopy = new LinkedHashMap<>();
        huntSpecs.forEach((key, spec) -> {
            String id = normalizeBasic(key);
            if (!id.equals(spec.item())) {
                throw new IllegalArgumentException("hunt key/item mismatch: " + id + " / " + spec.item());
            }
            huntCopy.put(id, spec);
        });
        this.huntSpecs = Collections.unmodifiableMap(huntCopy);

        LinkedHashMap<String, String> aliasCopy = new LinkedHashMap<>();
        aliases.forEach((key, value) -> aliasCopy.put(normalizeBasic(key), normalizeBasic(value)));
        this.aliases = Collections.unmodifiableMap(aliasCopy);

        LinkedHashMap<String, Integer> outputs = new LinkedHashMap<>();
        recipeCopy.forEach((item, recipe) -> outputs.put(item, recipe.outputCount()));
        recipeOutputCounts = Collections.unmodifiableMap(outputs);

        LinkedHashMap<String, List<String>> alternatives = new LinkedHashMap<>();
        mineCopy.forEach((item, spec) -> alternatives.put(item, spec.blockAlternatives()));
        miningBlockAlternatives = Collections.unmodifiableMap(alternatives);

        LinkedHashMap<String, LinkedHashSet<String>> itemsByBlock = new LinkedHashMap<>();
        mineCopy.forEach((item, spec) -> spec.blockAlternatives().forEach(block ->
                itemsByBlock.computeIfAbsent(block, ignored -> new LinkedHashSet<>()).add(item)));
        LinkedHashMap<String, Set<String>> immutableItemsByBlock = new LinkedHashMap<>();
        itemsByBlock.forEach((block, items) -> immutableItemsByBlock.put(
                block, Collections.unmodifiableSet(new LinkedHashSet<>(items))));
        miningItemsByBlock = Collections.unmodifiableMap(immutableItemsByBlock);
    }

    public static ResourceCatalog defaults() {
        return DEFAULT;
    }

    public static Map<String, Integer> defaultRecipeOutputCounts() {
        return DEFAULT.recipeOutputCounts();
    }

    public static Map<String, List<String>> defaultMiningBlockAlternatives() {
        return DEFAULT.miningBlockAlternatives();
    }

    /** Friendly names, plurals, namespace IDs and common ore block IDs normalize here. */
    public String normalizeItem(String raw) {
        String id = normalizeBasic(raw);
        Set<String> visited = new LinkedHashSet<>();
        while (aliases.containsKey(id)) {
            if (!visited.add(id)) throw new IllegalStateException("alias cycle: " + visited);
            id = aliases.get(id);
        }
        return id;
    }

    /**
     * Normalizes syntax without applying legacy material-substitution aliases.
     *
     * <p>The scalar planner historically collapses every plank/log variant to
     * oak so old missions can spend interchangeable wood. A universal recipe
     * graph cannot do that: acacia planks are valid in a plank tag but an
     * acacia-stairs output is not an oak-stairs output. Runtime recipe
     * adapters should therefore store and query exact IDs through this path
     * and express interchangeability with {@link IngredientChoice}.</p>
     */
    public String normalizeExactItem(String raw) {
        return normalizeBasic(raw);
    }

    /**
     * Resolves friendly legacy names only when the submitted ID is not already
     * part of the exact production graph.
     */
    public String resolveUniversalItem(String raw) {
        String exact = normalizeBasic(raw);
        if (isKnownExactItem(exact)) return exact;
        String legacy = normalizeItem(raw);
        return isKnownExactItem(legacy) ? legacy : exact;
    }

    public Map<String, Recipe> recipes() {
        return recipes;
    }

    public Map<String, List<RecipeVariant>> recipeVariants() {
        return recipeVariants;
    }

    /** Replaces recipes atomically while retaining this catalog's sources and aliases. */
    public ResourceCatalog withRecipeBook(RecipeBook authoritativeRecipes) {
        return new ResourceCatalog(authoritativeRecipes, mineSpecs, huntSpecs, aliases);
    }

    /**
     * Adds runtime variants and replaces only colliding outputs, retaining
     * static fallback recipes for outputs absent from the supplied book.
     */
    public ResourceCatalog mergeRecipeBook(RecipeBook additions) {
        Objects.requireNonNull(additions, "additions");
        LinkedHashMap<String, List<RecipeVariant>> merged = new LinkedHashMap<>(recipeVariants);
        additions.variants().forEach((output, variants) ->
                merged.put(output, List.copyOf(variants)));
        return new ResourceCatalog(new RecipeBook(merged), mineSpecs, huntSpecs, aliases);
    }

    public Map<String, MineSpec> mineSpecs() {
        return mineSpecs;
    }

    public Map<String, HuntSpec> huntSpecs() {
        return huntSpecs;
    }

    public Map<String, Integer> recipeOutputCounts() {
        return recipeOutputCounts;
    }

    public Map<String, List<String>> miningBlockAlternatives() {
        return miningBlockAlternatives;
    }

    /**
     * Exact reverse index for loaded-world source observation. A block can
     * satisfy both a legacy aggregate source and an exact universal source;
     * callers must retain the complete set rather than an arbitrary first
     * match.
     */
    public Map<String, Set<String>> miningItemsByBlock() {
        return miningItemsByBlock;
    }

    public Recipe recipe(String rawItem) {
        return recipes.get(normalizeItem(rawItem));
    }

    public List<RecipeVariant> recipeVariants(String rawItem) {
        return recipeVariants.getOrDefault(normalizeItem(rawItem), List.of());
    }

    public List<RecipeVariant> recipeVariantsExact(String rawItem) {
        return recipeVariants.getOrDefault(normalizeBasic(rawItem), List.of());
    }

    public MineSpec mineSpec(String rawItem) {
        return mineSpecs.get(normalizeItem(rawItem));
    }

    public MineSpec mineSpecExact(String rawItem) {
        return mineSpecs.get(normalizeBasic(rawItem));
    }

    public HuntSpec huntSpec(String rawItem) {
        return huntSpecs.get(normalizeItem(rawItem));
    }

    public HuntSpec huntSpecExact(String rawItem) {
        return huntSpecs.get(normalizeBasic(rawItem));
    }

    public boolean canProduce(String rawItem) {
        String item = normalizeItem(rawItem);
        return recipes.containsKey(item) || mineSpecs.containsKey(item) || huntSpecs.containsKey(item);
    }

    private boolean isKnownExactItem(String id) {
        if (recipeVariants.containsKey(id) || mineSpecs.containsKey(id) || huntSpecs.containsKey(id)) {
            return true;
        }
        for (List<RecipeVariant> variants : recipeVariants.values()) {
            for (RecipeVariant variant : variants) {
                if (id.equals(variant.workstation()) || id.equals(variant.fuelItem())) return true;
                for (IngredientChoice ingredient : variant.ingredients()) {
                    if (ingredient.alternatives().contains(id)) return true;
                }
            }
        }
        return false;
    }

    private static RecipeBook legacyRecipeBook(Map<String, Recipe> recipes) {
        Objects.requireNonNull(recipes, "recipes");
        LinkedHashMap<String, List<RecipeVariant>> variants = new LinkedHashMap<>();
        recipes.forEach((rawOutput, recipe) -> {
            String output = normalizeBasic(rawOutput);
            Recipe checked = Objects.requireNonNull(recipe, "recipe for " + output);
            if (!output.equals(checked.output())) {
                throw new IllegalArgumentException(
                        "recipe key/output mismatch: " + output + " / " + checked.output());
            }
            variants.put(output, List.of(RecipeVariant.fromLegacy(checked)));
        });
        return new RecipeBook(variants);
    }

    private static ResourceCatalog createDefault() {
        LinkedHashMap<String, Recipe> recipes = new LinkedHashMap<>();
        LinkedHashMap<String, MineSpec> mines = new LinkedHashMap<>();
        LinkedHashMap<String, HuntSpec> hunts = new LinkedHashMap<>();
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();

        addCraft(recipes, "oak_planks", 4, null, "oak_log", 1);
        addCraft(recipes, "stick", 4, null, "oak_planks", 2);
        addCraft(recipes, "crafting_table", 1, null, "oak_planks", 4);
        addCraft(recipes, "chest", 1, "crafting_table", "oak_planks", 8);
        addCraft(recipes, "furnace", 1, "crafting_table", "cobblestone", 8);
        addCraft(recipes, "torch", 4, null, "coal", 1, "stick", 1);
        addCraft(recipes, "wheat", 9, null, "hay_bale", 1);
        addCraft(recipes, "bread", 1, null, "wheat", 3);
        addCraft(recipes, "shield", 1, "crafting_table", "oak_planks", 6, "iron_ingot", 1);
        addCraft(recipes, "bucket", 1, "crafting_table", "iron_ingot", 3);

        addToolSet(recipes, "wooden", "oak_planks");
        addToolSet(recipes, "stone", "cobblestone");
        addToolSet(recipes, "iron", "iron_ingot");
        addToolSet(recipes, "diamond", "diamond");
        addArmorSet(recipes, "iron", "iron_ingot");
        addArmorSet(recipes, "diamond", "diamond");

        addSmelt(recipes, "iron_ingot", "raw_iron");
        addSmelt(recipes, "copper_ingot", "raw_copper");
        addSmelt(recipes, "gold_ingot", "raw_gold");
        addSmelt(recipes, "stone", "cobblestone");
        addSmelt(recipes, "glass", "sand");
        addSmelt(recipes, "netherite_scrap", "ancient_debris");
        addSmelt(recipes, "cooked_beef", "beef");
        addSmelt(recipes, "cooked_porkchop", "porkchop");
        addSmelt(recipes, "cooked_chicken", "chicken");
        addSmelt(recipes, "cooked_mutton", "mutton");

        addHarvest(mines, WoodLogFamilyPolicy.FAMILY_ITEM, 1, ToolTier.NONE,
                WoodLogFamilyPolicy.members().toArray(String[]::new));
        addHarvest(mines, "oak_log", 1, ToolTier.NONE, "oak_log");
        // Exact-species roots are separate from the explicit interchangeable-log
        // family above. Universal recipes such as acacia stairs must bottom out
        // in an acacia log; crediting a spruce log as acacia would make a plan
        // compile but leave the live crafter with the wrong item.
        for (String log : List.of(
                "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log",
                "mangrove_log", "cherry_log", "pale_oak_log", "crimson_stem", "warped_stem")) {
            addHarvest(mines, log, 1, ToolTier.NONE, log);
        }
        // Mineshafts and existing structures remain valid fallback wood sources,
        // but one observed plank cannot prove an arbitrarily large batch. The
        // universal compiler therefore tries renewable log production first.
        mines.put("oak_planks", new MineSpec(
                "oak_planks",
                List.of("oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
                        "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks",
                        "pale_oak_planks", "bamboo_planks", "crimson_planks", "warped_planks"),
                1, ToolTier.NONE, 1, false, WorldSourceKind.EXTRACTION, null, true));
        // Mine only natural source blocks. The dropped cargo is deliberately
        // not a target: Baritone may use carried cobble as a temporary support,
        // and targeting that support creates a place/break oscillation.
        addMine(mines, "cobblestone", 1, ToolTier.WOOD, "stone");
        addMine(mines, "cobbled_deepslate", 1, ToolTier.WOOD, "deepslate");
        addMine(mines, "dirt", 1, ToolTier.NONE,
                "dirt", "grass_block", "coarse_dirt", "rooted_dirt", "podzol", "mycelium");
        addMine(mines, "sand", 1, ToolTier.NONE, "sand", "red_sand");
        addMine(mines, "gravel", 1, ToolTier.NONE, "gravel");
        mines.put("flint", new MineSpec(
                "flint", List.of("gravel"), 1, ToolTier.NONE, 10, true));
        addMine(mines, "clay_ball", 4, ToolTier.NONE, "clay");
        addMine(mines, "hay_bale", 1, ToolTier.NONE, "hay_block");
        // The scalar planner retains the historical hay_bale alias, while
        // Paper's authoritative recipe graph uses the real hay_block item ID.
        // Keep an exact source so wheat <-> hay compression can fall back to
        // world acquisition instead of terminating as a false dependency cycle.
        addMine(mines, "hay_block", 1, ToolTier.NONE, "hay_block");
        // Resource Economy harvests the actual renewable crop rather than treating a
        // player's crafted hay bale as wilderness. The final harvesting boundary admits
        // only mature plants inside an acknowledged harvesting area and fences an exact
        // replant item before the break.
        addSustainableCrop(mines, "wheat", "wheat_seeds", "wheat");
        addSustainableCrop(mines, "carrot", "carrot", "carrots");
        addSustainableCrop(mines, "potato", "potato", "potatoes");
        addSustainableCrop(mines, "beetroot", "beetroot_seeds", "beetroots");
        addSustainableCrop(mines, "beetroot_seeds", "beetroot_seeds", "beetroots");
        addHarvest(mines, "sugar_cane", 1, ToolTier.NONE, "sugar_cane");
        addHarvest(mines, "bamboo", 1, ToolTier.NONE, "bamboo");
        addHarvest(mines, "cactus", 1, ToolTier.NONE, "cactus");
        addHarvest(mines, "kelp", 1, ToolTier.NONE, "kelp");
        addHarvest(mines, "melon_slice", 3, ToolTier.NONE, "melon");
        addHarvest(mines, "pumpkin", 1, ToolTier.NONE, "pumpkin");
        addHarvest(mines, "brown_mushroom", 1, ToolTier.NONE, "brown_mushroom");
        addHarvest(mines, "red_mushroom", 1, ToolTier.NONE, "red_mushroom");
        addHarvest(mines, "lily_pad", 1, ToolTier.NONE, "lily_pad");
        addHarvest(mines, "cocoa_beans", 1, ToolTier.NONE, "cocoa");
        for (String flower : List.of(
                "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
                "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
                "oxeye_daisy", "cornflower", "lily_of_the_valley",
                "sunflower", "lilac", "rose_bush", "peony")) {
            addHarvest(mines, flower, 1, ToolTier.NONE, flower);
        }
        addMine(mines, "netherrack", 1, ToolTier.NONE, "netherrack");
        addMine(mines, "blackstone", 1, ToolTier.WOOD, "blackstone");
        addMine(mines, "obsidian", 1, ToolTier.DIAMOND, "obsidian");
        mines.put("apple", new MineSpec(
                "apple", List.of("oak_leaves", "dark_oak_leaves"),
                1, ToolTier.NONE, 200, true));

        addMine(mines, "coal", 1, ToolTier.WOOD, "coal_ore", "deepslate_coal_ore");
        addMine(mines, "raw_copper", 2, ToolTier.STONE, "copper_ore", "deepslate_copper_ore");
        addMine(mines, "raw_iron", 1, ToolTier.STONE, "iron_ore", "deepslate_iron_ore");
        addMine(mines, "raw_gold", 1, ToolTier.IRON, "gold_ore", "deepslate_gold_ore");
        addMine(mines, "diamond", 1, ToolTier.IRON, "diamond_ore", "deepslate_diamond_ore");
        addMine(mines, "emerald", 1, ToolTier.IRON, "emerald_ore", "deepslate_emerald_ore");
        addMine(mines, "redstone", 4, ToolTier.IRON, "redstone_ore", "deepslate_redstone_ore");
        addMine(mines, "lapis_lazuli", 4, ToolTier.STONE, "lapis_ore", "deepslate_lapis_ore");
        addMine(mines, "quartz", 1, ToolTier.WOOD, "nether_quartz_ore");
        addMine(mines, "gold_nugget", 2, ToolTier.WOOD, "nether_gold_ore");
        addMine(mines, "ancient_debris", 1, ToolTier.DIAMOND, "ancient_debris");

        addHunt(hunts, "beef", 1, "cow");
        addHunt(hunts, "porkchop", 1, "pig");
        addHunt(hunts, "chicken", 1, "chicken");
        addHunt(hunts, "mutton", 1, "sheep");
        addProbabilisticHunt(hunts, "leather", 2, 6, "cow");
        addProbabilisticHunt(hunts, "white_wool", 2, 5, "sheep");
        addProbabilisticHunt(hunts, "feather", 2, 6, "chicken");
        addProbabilisticHunt(hunts, "string", 2, 6, "spider", "cave_spider");
        addProbabilisticHunt(hunts, "bone", 2, 6, "skeleton", "stray", "bogged");
        addProbabilisticHunt(hunts, "gunpowder", 2, 6, "creeper");

        alias(aliases, "log", "oak_log");
        alias(aliases, "logs", "oak_log");
        alias(aliases, "wood", "oak_log");
        alias(aliases, "woods", "oak_log");
        alias(aliases, "oak_wood", "oak_log");
        alias(aliases, "wood_log", "oak_log");
        alias(aliases, "wooden_log", "oak_log");
        alias(aliases, "plank", "oak_planks");
        alias(aliases, "planks", "oak_planks");
        alias(aliases, "wood_plank", "oak_planks");
        alias(aliases, "wood_planks", "oak_planks");
        alias(aliases, "wooden_plank", "oak_planks");
        alias(aliases, "wooden_planks", "oak_planks");
        alias(aliases, "sticks", "stick");
        alias(aliases, "workbench", "crafting_table");
        alias(aliases, "crafting_bench", "crafting_table");
        alias(aliases, "craftingtable", "crafting_table");
        alias(aliases, "crafting_tables", "crafting_table");
        alias(aliases, "furnaces", "furnace");
        alias(aliases, "cobble", "cobblestone");
        alias(aliases, "cobblestones", "cobblestone");
        alias(aliases, "building_block", "cobblestone");
        alias(aliases, "building_blocks", "cobblestone");
        alias(aliases, "blocks", "cobblestone");
        alias(aliases, "fuel", "coal");
        alias(aliases, "charcoal", "coal");
        alias(aliases, "coal_ore", "coal");
        alias(aliases, "deepslate_coal_ore", "coal");
        alias(aliases, "iron", "raw_iron");
        alias(aliases, "iron_ore", "raw_iron");
        alias(aliases, "deepslate_iron_ore", "raw_iron");
        alias(aliases, "iron_ingots", "iron_ingot");
        alias(aliases, "copper", "raw_copper");
        alias(aliases, "copper_ore", "raw_copper");
        alias(aliases, "deepslate_copper_ore", "raw_copper");
        alias(aliases, "copper_ingots", "copper_ingot");
        alias(aliases, "gold", "raw_gold");
        alias(aliases, "gold_ore", "raw_gold");
        alias(aliases, "deepslate_gold_ore", "raw_gold");
        alias(aliases, "gold_ingots", "gold_ingot");
        alias(aliases, "diamond_ore", "diamond");
        alias(aliases, "deepslate_diamond_ore", "diamond");
        alias(aliases, "diamonds", "diamond");
        alias(aliases, "emerald_ore", "emerald");
        alias(aliases, "deepslate_emerald_ore", "emerald");
        alias(aliases, "emeralds", "emerald");
        alias(aliases, "redstone_ore", "redstone");
        alias(aliases, "deepslate_redstone_ore", "redstone");
        alias(aliases, "redstone_dust", "redstone");
        alias(aliases, "lapis", "lapis_lazuli");
        alias(aliases, "lapis_ore", "lapis_lazuli");
        alias(aliases, "deepslate_lapis_ore", "lapis_lazuli");
        alias(aliases, "quartz_ore", "quartz");
        alias(aliases, "nether_quartz", "quartz");
        alias(aliases, "nether_quartz_ore", "quartz");
        alias(aliases, "ancient_debrises", "ancient_debris");
        alias(aliases, "buckets", "bucket");
        alias(aliases, "shields", "shield");
        alias(aliases, "chests", "chest");
        alias(aliases, "torches", "torch");
        alias(aliases, "hay", "hay_bale");
        alias(aliases, "hay_block", "hay_bale");
        alias(aliases, "bread_loaf", "bread");
        alias(aliases, "bread_loaves", "bread");
        alias(aliases, "apples", "apple");
        alias(aliases, "wool", "white_wool");
        alias(aliases, "wools", "white_wool");
        alias(aliases, "leathers", "leather");
        alias(aliases, "feathers", "feather");
        alias(aliases, "strings", "string");
        alias(aliases, "bones", "bone");
        alias(aliases, "sugarcanes", "sugar_cane");
        alias(aliases, "sugar_canes", "sugar_cane");

        for (String type : List.of("wooden", "stone", "iron", "diamond")) {
            alias(aliases, type + "_pick", type + "_pickaxe");
            alias(aliases, type + "_picks", type + "_pickaxe");
            alias(aliases, type + "_pickaxes", type + "_pickaxe");
            alias(aliases, type + "_swords", type + "_sword");
            alias(aliases, type + "_axes", type + "_axe");
            alias(aliases, type + "_shovels", type + "_shovel");
            alias(aliases, type + "_hoes", type + "_hoe");
        }
        alias(aliases, "wood_pickaxe", "wooden_pickaxe");
        alias(aliases, "wood_pick", "wooden_pickaxe");
        alias(aliases, "wood_sword", "wooden_sword");
        alias(aliases, "wood_axe", "wooden_axe");
        alias(aliases, "wood_shovel", "wooden_shovel");
        alias(aliases, "wood_hoe", "wooden_hoe");
        alias(aliases, "iron_armour", "iron_chestplate");
        alias(aliases, "iron_armor", "iron_chestplate");
        alias(aliases, "diamond_armour", "diamond_chestplate");
        alias(aliases, "diamond_armor", "diamond_chestplate");

        // Canonicalize interchangeable inventory variants for recipe accounting.
        for (String id : List.of("spruce_log", "birch_log", "jungle_log", "acacia_log",
                "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log",
                "crimson_stem", "warped_stem")) alias(aliases, id, "oak_log");
        for (String id : List.of("spruce_planks", "birch_planks", "jungle_planks", "acacia_planks",
                "dark_oak_planks", "mangrove_planks", "cherry_planks", "pale_oak_planks",
                "crimson_planks", "warped_planks", "bamboo_planks")) alias(aliases, id, "oak_planks");

        return new ResourceCatalog(recipes, mines, hunts, aliases);
    }

    private static void addToolSet(Map<String, Recipe> recipes, String prefix, String material) {
        addCraft(recipes, prefix + "_pickaxe", 1, "crafting_table", material, 3, "stick", 2);
        addCraft(recipes, prefix + "_axe", 1, "crafting_table", material, 3, "stick", 2);
        addCraft(recipes, prefix + "_shovel", 1, "crafting_table", material, 1, "stick", 2);
        addCraft(recipes, prefix + "_hoe", 1, "crafting_table", material, 2, "stick", 2);
        addCraft(recipes, prefix + "_sword", 1, "crafting_table", material, 2, "stick", 1);
    }

    private static void addArmorSet(Map<String, Recipe> recipes, String prefix, String material) {
        addCraft(recipes, prefix + "_helmet", 1, "crafting_table", material, 5);
        addCraft(recipes, prefix + "_chestplate", 1, "crafting_table", material, 8);
        addCraft(recipes, prefix + "_leggings", 1, "crafting_table", material, 7);
        addCraft(recipes, prefix + "_boots", 1, "crafting_table", material, 4);
    }

    private static void addCraft(
            Map<String, Recipe> recipes,
            String output,
            int outputCount,
            String workstation,
            Object... ingredientPairs
    ) {
        recipes.put(output, Recipe.craft(output, outputCount, ingredients(ingredientPairs), workstation));
    }

    private static void addSmelt(Map<String, Recipe> recipes, String output, String ingredient) {
        recipes.put(output, Recipe.smelt(output, 1, Map.of(ingredient, 1),
                "furnace", "coal", 8));
    }

    private static void addMine(
            Map<String, MineSpec> mines,
            String item,
            int minimumDrops,
            ToolTier tier,
            String... blocks
    ) {
        mines.put(item, new MineSpec(item, List.of(blocks), minimumDrops, tier));
    }

    private static void addHarvest(
            Map<String, MineSpec> mines,
            String item,
            int minimumDrops,
            ToolTier tier,
            String... blocks
    ) {
        mines.put(item, new MineSpec(item, List.of(blocks), minimumDrops, tier,
                1, false, WorldSourceKind.HARVEST, null));
    }

    private static void addSustainableCrop(
            Map<String, MineSpec> mines,
            String item,
            String replantItem,
            String block
    ) {
        mines.put(item, new MineSpec(item, List.of(block), 1, ToolTier.NONE,
                1, false, WorldSourceKind.HARVEST, replantItem));
    }

    private static void addHunt(
            Map<String, HuntSpec> hunts,
            String item,
            int minimumDrops,
            String... entities
    ) {
        hunts.put(item, new HuntSpec(item, List.of(entities), minimumDrops));
    }

    private static void addProbabilisticHunt(
            Map<String, HuntSpec> hunts,
            String item,
            int estimatedKillsPerDrop,
            int retryBudgetPerDrop,
            String... entities
    ) {
        hunts.put(item, new HuntSpec(
                item, List.of(entities), 1,
                estimatedKillsPerDrop, retryBudgetPerDrop, true));
    }

    private static Map<String, Integer> ingredients(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("ingredient pairs required");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            String item = (String) pairs[index];
            int count = (Integer) pairs[index + 1];
            result.put(item, count);
        }
        return result;
    }

    private static void alias(Map<String, String> aliases, String from, String to) {
        aliases.put(normalizeBasic(from), normalizeBasic(to));
    }

    private static String normalizeBasic(String raw) {
        Objects.requireNonNull(raw, "item");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        // Be tolerant of the 2.2.0 delivery bug which could persist an extra
        // Minecraft namespace in a durable frame/error before retry.
        while (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        normalized = normalized.replace('-', '_').replace(' ', '_');
        while (normalized.contains("__")) normalized = normalized.replace("__", "_");
        if (normalized.isBlank()) throw new IllegalArgumentException("item identifier cannot be blank");
        return normalized;
    }

    private static String normalizeRecipeId(String raw) {
        Objects.requireNonNull(raw, "recipeId");
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (normalized.isBlank()) throw new IllegalArgumentException("recipeId cannot be blank");
        return normalized;
    }

    private static String requireId(String raw, String field) {
        try {
            return normalizeBasic(raw);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be a valid identifier", exception);
        }
    }

    private static String optionalId(String raw) {
        return raw == null || raw.isBlank() ? null : normalizeBasic(raw);
    }
}
