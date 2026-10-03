package dev.entity.client.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.entity.client.autonomy.policy.ResourceCatalog;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict, bounded reassembler for the authenticated Paper recipe snapshot.
 *
 * <p>No partially received or malformed catalog is ever exposed. A recipe
 * book becomes available only after every ordered part, summary count, and
 * the server's SHA-256 catalog identity have been verified.</p>
 */
public final class PaperRecipeCatalogAssembler {
    public static final int SCHEMA_VERSION = 1;
    public static final String FEATURE = "paper_recipe_catalog_v1";
    public static final int MAX_PARTS = 4_096;
    public static final int MAX_RECIPES = 100_000;
    public static final int MAX_CATALOG_BYTES = 64 * 1024 * 1024;

    private static final int MAX_INGREDIENT_GROUPS = 64;
    private static final int MAX_ALTERNATIVES_PER_GROUP = 1_024;
    private static final int MAX_TOTAL_ALTERNATIVES = 2_000_000;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CATALOG_ID = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]+");
    private static final Set<String> RECIPE_TYPES = Set.of(
            "shaped", "shapeless", "smelting", "blasting", "smoking",
            "campfire_cooking");
    private static final Set<String> STATIONS = Set.of(
            "crafting_table", "furnace", "blast_furnace", "smoker", "campfire");

    private State state = State.IDLE;
    private String requestId = "";
    private String catalogId = "";
    private int expectedParts;
    private int nextPart;
    private int accumulatedBytes;
    private int totalAlternatives;
    private JsonObject canonicalSummary;
    private JsonObject canonicalDiscovery;
    private boolean discoveryPresent;
    private boolean discoveryApplied;
    private String discoveryCode = "";
    private Summary summary;
    private String failure = "";
    private String lastRecipeId = "";
    private final ArrayList<JsonObject> encodedRecipes = new ArrayList<>();
    private final ArrayList<ParsedRecipe> parsedRecipes = new ArrayList<>();
    private final HashSet<String> recipeIds = new HashSet<>();

    /** Starts one fresh connection-scoped request and discards every partial prior response. */
    public synchronized void begin(String newRequestId) {
        String checked = Objects.requireNonNull(newRequestId, "requestId").trim();
        if (!REQUEST_ID.matcher(checked).matches()) {
            throw new IllegalArgumentException("invalid recipe catalog requestId");
        }
        clear();
        requestId = checked;
        state = State.RECEIVING;
    }

    /** Revokes any partial or completed connection-scoped response. */
    public synchronized void reset() {
        clear();
    }

    public synchronized State state() {
        return state;
    }

    public synchronized String requestId() {
        return requestId;
    }

    public synchronized String failure() {
        return failure;
    }

    /** Builds the only supported catalog request shape. */
    public static JsonObject request(String requestId, String entityPlayer) {
        String checkedRequest = Objects.requireNonNull(requestId, "requestId").trim();
        if (!REQUEST_ID.matcher(checkedRequest).matches()) {
            throw new IllegalArgumentException("invalid recipe catalog requestId");
        }
        String checkedPlayer = Objects.requireNonNull(entityPlayer, "entityPlayer").trim();
        if (!checkedPlayer.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("invalid Entity player name");
        }
        JsonObject request = new JsonObject();
        request.addProperty("type", "recipe_catalog_request");
        request.addProperty("schema", SCHEMA_VERSION);
        request.addProperty("requestId", checkedRequest);
        request.addProperty("unlockForEntity", true);
        request.addProperty("entityPlayer", checkedPlayer);
        return request;
    }

    /** Accepts one response part or matching error frame. */
    public synchronized Result accept(JsonObject frame) {
        Objects.requireNonNull(frame, "frame");
        if (state == State.IDLE) return Result.ignored("no active catalog request");
        String type;
        try {
            type = requiredString(frame, "type", 32);
        } catch (IllegalArgumentException malformed) {
            return reject(malformed.getMessage());
        }
        if (!type.equals("recipe_catalog") && !type.equals("recipe_catalog_error")) {
            return Result.ignored("not a recipe catalog frame");
        }

        String incomingRequest;
        try {
            incomingRequest = requiredString(frame, "requestId", 128);
        } catch (IllegalArgumentException malformed) {
            return reject(malformed.getMessage());
        }
        // A late frame from the previous authenticated socket must never poison
        // the new request, and it can never complete it because IDs differ.
        if (!requestId.equals(incomingRequest)) {
            return Result.ignored("stale recipe catalog requestId");
        }
        if (state == State.COMPLETE) return Result.ignored("catalog is already complete");
        if (state == State.FAILED) return Result.ignored("catalog request already failed");

        if (type.equals("recipe_catalog_error")) {
            try {
                requireSchema(frame);
                String code = requiredToken(frame, "code", 64);
                String message = requiredString(frame, "message", 1_024);
                return reject("Paper recipe catalog error " + code + ": " + message);
            } catch (IllegalArgumentException malformed) {
                return reject(malformed.getMessage());
            }
        }

        try {
            return acceptCatalogPart(frame);
        } catch (IllegalArgumentException | ArithmeticException malformed) {
            return reject(malformed.getMessage());
        }
    }

    private Result acceptCatalogPart(JsonObject frame) {
        requireSchema(frame);
        String incomingCatalogId = requiredString(frame, "catalogId", 64)
                .toLowerCase(Locale.ROOT);
        if (!CATALOG_ID.matcher(incomingCatalogId).matches()) {
            throw new IllegalArgumentException("invalid recipe catalogId");
        }
        int part = requiredInt(frame, "part", 0, MAX_PARTS - 1);
        int parts = requiredInt(frame, "parts", 1, MAX_PARTS);
        boolean complete = requiredBoolean(frame, "complete");
        if (part >= parts) throw new IllegalArgumentException("recipe catalog part is outside parts");
        if (complete != (part == parts - 1)) {
            throw new IllegalArgumentException("recipe catalog complete marker is inconsistent");
        }
        if (part != nextPart) {
            throw new IllegalArgumentException(
                    "recipe catalog parts are not contiguous: expected " + nextPart + ", got " + part);
        }

        JsonObject incomingSummary = requiredObject(frame, "summary");
        Summary validatedSummary = parseSummary(incomingSummary);
        boolean incomingDiscoveryPresent = frame.has("discovery");
        JsonObject incomingDiscovery = incomingDiscoveryPresent
                ? requiredObject(frame, "discovery") : null;
        Discovery validatedDiscovery = incomingDiscovery == null
                ? Discovery.missing()
                : parseDiscovery(incomingDiscovery, validatedSummary.exported());

        if (part == 0) {
            catalogId = incomingCatalogId;
            expectedParts = parts;
            summary = validatedSummary;
            canonicalSummary = incomingSummary.deepCopy();
            discoveryPresent = incomingDiscoveryPresent;
            canonicalDiscovery = incomingDiscovery == null ? null : incomingDiscovery.deepCopy();
            discoveryApplied = validatedDiscovery.applied();
            discoveryCode = validatedDiscovery.code();
        } else {
            if (!catalogId.equals(incomingCatalogId) || expectedParts != parts) {
                throw new IllegalArgumentException("recipe catalog identity changed between parts");
            }
            if (!canonicalSummary.equals(incomingSummary)
                    || discoveryPresent != incomingDiscoveryPresent
                    || discoveryPresent && !canonicalDiscovery.equals(incomingDiscovery)) {
                throw new IllegalArgumentException("recipe catalog metadata changed between parts");
            }
        }

        JsonArray recipes = requiredArray(frame, "recipes");
        ArrayList<JsonObject> rawPart = new ArrayList<>(recipes.size());
        ArrayList<ParsedRecipe> parsedPart = new ArrayList<>(recipes.size());
        HashSet<String> partIds = new HashSet<>();
        int partAlternatives = 0;
        String priorRecipeId = lastRecipeId;
        for (JsonElement element : recipes) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("recipe catalog contains a non-object recipe");
            }
            JsonObject raw = element.getAsJsonObject();
            ParsedRecipe parsed = parseRecipe(raw);
            if (!priorRecipeId.isEmpty() && priorRecipeId.compareTo(parsed.id()) >= 0) {
                throw new IllegalArgumentException("recipe catalog IDs are not strictly ordered");
            }
            priorRecipeId = parsed.id();
            if (!partIds.add(parsed.id()) || recipeIds.contains(parsed.id())) {
                throw new IllegalArgumentException("duplicate recipe id: " + parsed.id());
            }
            partAlternatives = Math.addExact(partAlternatives, parsed.alternativeCount());
            rawPart.add(raw.deepCopy());
            parsedPart.add(parsed);
        }
        if ((long) parsedRecipes.size() + parsedPart.size() > MAX_RECIPES) {
            throw new IllegalArgumentException("recipe catalog exceeds the recipe limit");
        }
        if ((long) totalAlternatives + partAlternatives > MAX_TOTAL_ALTERNATIVES) {
            throw new IllegalArgumentException("recipe catalog exceeds the ingredient-alternative limit");
        }
        int frameBytes = frame.toString().getBytes(StandardCharsets.UTF_8).length;
        if ((long) accumulatedBytes + frameBytes > MAX_CATALOG_BYTES) {
            throw new IllegalArgumentException("recipe catalog exceeds the byte limit");
        }

        encodedRecipes.addAll(rawPart);
        parsedRecipes.addAll(parsedPart);
        recipeIds.addAll(partIds);
        totalAlternatives += partAlternatives;
        accumulatedBytes += frameBytes;
        lastRecipeId = priorRecipeId;
        nextPart++;

        if (!complete) {
            return Result.receiving(nextPart, expectedParts, parsedRecipes.size());
        }
        if (nextPart != expectedParts || parsedRecipes.size() != summary.exported()) {
            throw new IllegalArgumentException("recipe catalog exported count does not match contents");
        }
        String actualId = catalogDigest(canonicalSummary, encodedRecipes);
        if (!catalogId.equals(actualId)) {
            throw new IllegalArgumentException("recipe catalog SHA-256 identity mismatch");
        }

        ResourceCatalog.RecipeBook book = toRecipeBook(parsedRecipes);
        state = State.COMPLETE;
        return Result.complete(
                book, catalogId, parsedRecipes.size(), discoveryApplied, discoveryCode);
    }

    private static ResourceCatalog.RecipeBook toRecipeBook(List<ParsedRecipe> recipes) {
        LinkedHashMap<String, List<ResourceCatalog.RecipeVariant>> byOutput = new LinkedHashMap<>();
        for (ParsedRecipe recipe : recipes) {
            // The present executor owns exactly these two station kinds. It
            // would otherwise run a smoker/blast/campfire variant through its
            // ordinary furnace controller, which is semantically wrong. Keep
            // those entries authenticated in the snapshot/hash but omit them
            // from executable planning knowledge until their station exists.
            if (!recipe.station().equals("crafting_table")
                    && !recipe.station().equals("furnace")) {
                continue;
            }
            ArrayList<ResourceCatalog.IngredientChoice> choices = new ArrayList<>();
            for (ParsedIngredient ingredient : recipe.ingredients()) {
                choices.add(new ResourceCatalog.IngredientChoice(
                        ingredient.count(), ingredient.alternatives()));
            }

            ResourceCatalog.RecipeLayout layout;
            List<List<String>> slots;
            String effectiveStation = recipe.station();
            if (recipe.type().equals("shapeless")) {
                layout = ResourceCatalog.RecipeLayout.SHAPELESS;
                slots = expandedSlots(recipe.ingredients());
                if (slots.size() <= 4) effectiveStation = null;
            } else if (recipe.type().equals("shaped")) {
                layout = ResourceCatalog.RecipeLayout.SHAPED;
                slots = recipe.slots();
                if (recipe.width() <= 2 && recipe.height() <= 2) effectiveStation = null;
            } else {
                layout = ResourceCatalog.RecipeLayout.COOKING;
                slots = expandedSlots(recipe.ingredients());
            }
            ResourceCatalog.RecipeMetadata metadata = new ResourceCatalog.RecipeMetadata(
                    recipe.id(), layout,
                    layout == ResourceCatalog.RecipeLayout.SHAPED ? recipe.width() : 0,
                    layout == ResourceCatalog.RecipeLayout.SHAPED ? recipe.height() : 0,
                    slots);
            boolean crafting = recipe.type().equals("shaped") || recipe.type().equals("shapeless");
            ResourceCatalog.RecipeVariant variant = new ResourceCatalog.RecipeVariant(
                    recipe.outputItem(), recipe.outputCount(), choices,
                    crafting ? ResourceCatalog.ProductionKind.CRAFT
                            : ResourceCatalog.ProductionKind.SMELT,
                    effectiveStation, crafting ? null : "coal", crafting ? 0 : 8,
                    metadata);
            String output = stripMinecraftNamespace(recipe.outputItem());
            byOutput.computeIfAbsent(output, ignored -> new ArrayList<>()).add(variant);
        }

        LinkedHashMap<String, List<ResourceCatalog.RecipeVariant>> ordered = new LinkedHashMap<>();
        byOutput.forEach((output, variants) -> {
            ArrayList<ResourceCatalog.RecipeVariant> sorted = new ArrayList<>(variants);
            // Stable ordering remains useful for multiple ordinary variants.
            sorted.sort(Comparator
                    .comparingInt(PaperRecipeCatalogAssembler::stationPriority)
                    .thenComparing(variant -> Objects.requireNonNullElse(
                            variant.metadata().recipeId(), "")));
            ordered.put(output, List.copyOf(sorted));
        });
        return new ResourceCatalog.RecipeBook(ordered);
    }

    private static List<List<String>> expandedSlots(List<ParsedIngredient> ingredients) {
        ArrayList<List<String>> slots = new ArrayList<>();
        for (ParsedIngredient ingredient : ingredients) {
            for (int copy = 0; copy < ingredient.count(); copy++) {
                slots.add(ingredient.alternatives());
            }
        }
        return List.copyOf(slots);
    }

    private static int stationPriority(ResourceCatalog.RecipeVariant variant) {
        return switch (Objects.requireNonNullElse(variant.workstation(), "")) {
            case "", "furnace" -> 0;
            case "crafting_table" -> 1;
            default -> 2;
        };
    }

    private static ParsedRecipe parseRecipe(JsonObject recipe) {
        String id = requiredKey(recipe, "id");
        String type = requiredToken(recipe, "type", 32);
        String station = requiredToken(recipe, "station", 32);
        if (!RECIPE_TYPES.contains(type)) {
            throw new IllegalArgumentException("unsupported recipe type: " + type);
        }
        if (!STATIONS.contains(station)) {
            throw new IllegalArgumentException("unsupported recipe station: " + station);
        }
        boolean crafting = type.equals("shaped") || type.equals("shapeless");
        if (crafting != station.equals("crafting_table")) {
            throw new IllegalArgumentException("recipe type/station mismatch");
        }
        JsonObject output = requiredObject(recipe, "output");
        String outputItem = requiredKey(output, "item");
        int outputCount = requiredInt(output, "count", 1, 4_096);
        int cookTime = requiredInt(recipe, "cookTime", 0, 20_000_000);
        if (crafting && cookTime != 0 || !crafting && cookTime == 0) {
            throw new IllegalArgumentException("recipe cook time is inconsistent with its type");
        }

        JsonArray encodedIngredients = requiredArray(recipe, "ingredients");
        if (encodedIngredients.isEmpty() || encodedIngredients.size() > MAX_INGREDIENT_GROUPS) {
            throw new IllegalArgumentException("recipe ingredient group count is outside bounds");
        }
        ArrayList<ParsedIngredient> ingredients = new ArrayList<>(encodedIngredients.size());
        int alternativeCount = 0;
        for (JsonElement element : encodedIngredients) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("recipe contains a non-object ingredient");
            }
            JsonObject encoded = element.getAsJsonObject();
            int count = requiredInt(encoded, "count", 1, 64);
            JsonArray alternativesArray = requiredArray(encoded, "alternatives");
            if (alternativesArray.isEmpty()
                    || alternativesArray.size() > MAX_ALTERNATIVES_PER_GROUP) {
                throw new IllegalArgumentException("ingredient alternative count is outside bounds");
            }
            ArrayList<String> alternatives = new ArrayList<>(alternativesArray.size());
            HashSet<String> unique = new HashSet<>();
            String prior = null;
            for (JsonElement alternative : alternativesArray) {
                String key = requiredKey(alternative, "ingredient alternative");
                if (!unique.add(key)) {
                    throw new IllegalArgumentException("ingredient contains duplicate alternatives");
                }
                if (prior != null && prior.compareTo(key) > 0) {
                    throw new IllegalArgumentException("ingredient alternatives are not ordered");
                }
                prior = key;
                alternatives.add(key);
            }
            alternativeCount = Math.addExact(alternativeCount, alternatives.size());
            ingredients.add(new ParsedIngredient(count, List.copyOf(alternatives)));
        }

        int width = requiredInt(recipe, "width", 0, 3);
        int height = requiredInt(recipe, "height", 0, 3);
        JsonArray encodedSlots = requiredArray(recipe, "slots");
        ArrayList<List<String>> slots = new ArrayList<>(encodedSlots.size());
        for (JsonElement slotElement : encodedSlots) {
            if (!slotElement.isJsonArray()) {
                throw new IllegalArgumentException("recipe contains a non-array shaped slot");
            }
            JsonArray slotArray = slotElement.getAsJsonArray();
            if (slotArray.size() > MAX_ALTERNATIVES_PER_GROUP) {
                throw new IllegalArgumentException("recipe slot alternative count is outside bounds");
            }
            ArrayList<String> slot = new ArrayList<>(slotArray.size());
            String prior = null;
            for (JsonElement alternative : slotArray) {
                String key = requiredKey(alternative, "recipe slot alternative");
                if (prior != null && prior.compareTo(key) >= 0) {
                    throw new IllegalArgumentException(
                            "recipe slot alternatives are duplicated or not ordered");
                }
                prior = key;
                slot.add(key);
            }
            slots.add(List.copyOf(slot));
        }
        if (type.equals("shaped")) {
            if (width < 1 || height < 1 || encodedSlots.size() != width * height) {
                throw new IllegalArgumentException("shaped recipe dimensions/slots are inconsistent");
            }
            if (!ingredientMultiplicity(ingredients).equals(slotMultiplicity(slots))) {
                throw new IllegalArgumentException(
                        "shaped recipe slots do not match its grouped ingredients");
            }
        } else if (width != 0 || height != 0 || !slots.isEmpty()) {
            throw new IllegalArgumentException(
                    "only shaped recipes may declare dimensions or slots");
        }
        return new ParsedRecipe(
                id, type, station, outputItem, outputCount, List.copyOf(ingredients),
                cookTime, alternativeCount, width, height, List.copyOf(slots));
    }

    private static Map<List<String>, Integer> ingredientMultiplicity(
            List<ParsedIngredient> ingredients) {
        LinkedHashMap<List<String>, Integer> result = new LinkedHashMap<>();
        for (ParsedIngredient ingredient : ingredients) {
            result.merge(ingredient.alternatives(), ingredient.count(), Math::addExact);
        }
        return result;
    }

    private static Map<List<String>, Integer> slotMultiplicity(List<List<String>> slots) {
        LinkedHashMap<List<String>, Integer> result = new LinkedHashMap<>();
        for (List<String> slot : slots) {
            if (!slot.isEmpty()) result.merge(slot, 1, Math::addExact);
        }
        return result;
    }

    private static Summary parseSummary(JsonObject value) {
        int scanned = requiredInt(value, "scanned", 0, 200_000);
        int exported = requiredInt(value, "exported", 0, MAX_RECIPES);
        int excluded = requiredInt(value, "excluded", 0, 200_000);
        if (scanned != Math.addExact(exported, excluded)) {
            throw new IllegalArgumentException("recipe summary scanned count is inconsistent");
        }
        int exportedTypes = countObject(requiredObject(value, "exportedByType"));
        int excludedReasons = countObject(requiredObject(value, "excludedByReason"));
        if (exportedTypes != exported || excludedReasons != excluded) {
            throw new IllegalArgumentException("recipe summary category counts are inconsistent");
        }
        return new Summary(scanned, exported, excluded);
    }

    private static int countObject(JsonObject counts) {
        int total = 0;
        for (Map.Entry<String, JsonElement> entry : counts.entrySet()) {
            if (!TOKEN.matcher(entry.getKey()).matches()) {
                throw new IllegalArgumentException("recipe summary contains an invalid category");
            }
            int value = exactInt(entry.getValue(), "recipe summary category", 0, MAX_RECIPES);
            if (value == 0) {
                throw new IllegalArgumentException("recipe summary contains a zero category");
            }
            total = Math.addExact(total, value);
        }
        return total;
    }

    private static Discovery parseDiscovery(JsonObject value, int exported) {
        if (!requiredBoolean(value, "requested")) {
            throw new IllegalArgumentException("recipe discovery response was not requested");
        }
        boolean applied = requiredBoolean(value, "applied");
        String player = requiredString(value, "player", 16);
        if (!player.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("invalid recipe discovery player");
        }
        int exportedKeys = requiredInt(value, "exportedKeyCount", 0, MAX_RECIPES);
        int discoverable = requiredInt(value, "discoverableKeyCount", 0, exportedKeys);
        int newlyDiscovered = requiredInt(value, "newlyDiscovered", 0, discoverable);
        int undiscoverable = requiredInt(value, "undiscoverableCount", 0, exportedKeys);
        if (exportedKeys != exported || discoverable + undiscoverable != exportedKeys) {
            throw new IllegalArgumentException("recipe discovery coverage is inconsistent");
        }
        boolean coverageComplete = requiredBoolean(value, "keyCoverageComplete");
        if (coverageComplete != (undiscoverable == 0)) {
            throw new IllegalArgumentException("recipe discovery completion is inconsistent");
        }
        JsonArray sample = requiredArray(value, "undiscoverableSample");
        if (sample.size() > Math.min(16, undiscoverable)) {
            throw new IllegalArgumentException("recipe discovery sample is outside bounds");
        }
        for (JsonElement id : sample) requiredKey(id, "undiscoverable recipe id");
        boolean truncated = requiredBoolean(value, "undiscoverableSampleTruncated");
        if (truncated != (sample.size() < undiscoverable)) {
            throw new IllegalArgumentException("recipe discovery truncation marker is inconsistent");
        }
        String code = requiredToken(value, "code", 64);
        requiredString(value, "message", 1_024);
        // Referenced to make the two bounded counters explicit to static analysis.
        if (newlyDiscovered < 0) throw new IllegalArgumentException("invalid discovery count");
        return new Discovery(applied, code);
    }

    private static String catalogDigest(JsonObject summary, List<JsonObject> recipes) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        root.add("summary", summary.deepCopy());
        JsonArray encoded = new JsonArray();
        recipes.forEach(recipe -> encoded.add(recipe.deepCopy()));
        root.add("recipes", encoded);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(root.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Result reject(String reason) {
        state = State.FAILED;
        failure = reason == null || reason.isBlank()
                ? "malformed Paper recipe catalog" : reason;
        encodedRecipes.clear();
        parsedRecipes.clear();
        recipeIds.clear();
        return Result.failed(failure);
    }

    private void clear() {
        state = State.IDLE;
        requestId = "";
        catalogId = "";
        expectedParts = 0;
        nextPart = 0;
        accumulatedBytes = 0;
        totalAlternatives = 0;
        canonicalSummary = null;
        canonicalDiscovery = null;
        discoveryPresent = false;
        discoveryApplied = false;
        discoveryCode = "";
        summary = null;
        failure = "";
        lastRecipeId = "";
        encodedRecipes.clear();
        parsedRecipes.clear();
        recipeIds.clear();
    }

    private static void requireSchema(JsonObject object) {
        if (requiredInt(object, "schema", 1, Integer.MAX_VALUE) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported recipe catalog schema");
        }
    }

    private static JsonObject requiredObject(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String field, int maximumLength) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String text = value.getAsString();
        if (text.isBlank() || text.length() > maximumLength) {
            throw new IllegalArgumentException(field + " length is outside bounds");
        }
        return text;
    }

    private static String requiredToken(JsonObject object, String field, int maximumLength) {
        String value = requiredString(object, field, maximumLength).toLowerCase(Locale.ROOT);
        if (!TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a token");
        }
        return value;
    }

    private static String requiredKey(JsonObject object, String field) {
        String value = requiredString(object, field, 256).toLowerCase(Locale.ROOT);
        if (!KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a namespaced identifier");
        }
        return value;
    }

    private static String requiredKey(JsonElement element, String label) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(label + " must be a string");
        }
        String value = element.getAsString().toLowerCase(Locale.ROOT);
        if (value.length() > 256 || !KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a namespaced identifier");
        }
        return value;
    }

    private static int requiredInt(
            JsonObject object, String field, int minimum, int maximum) {
        JsonElement value = object.get(field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return exactInt(value, field, minimum, maximum);
    }

    private static int exactInt(JsonElement value, String label, int minimum, int maximum) {
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(label + " must be an integer");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) throw new IllegalArgumentException(label + " must be an integer");
        try {
            BigDecimal decimal = primitive.getAsBigDecimal();
            int result = decimal.intValueExact();
            if (result < minimum || result > maximum) {
                throw new IllegalArgumentException(label + " is outside bounds");
            }
            return result;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(label + " must be an exact bounded integer", invalid);
        }
    }

    private static boolean requiredBoolean(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static String stripMinecraftNamespace(String item) {
        return item.startsWith("minecraft:")
                ? item.substring("minecraft:".length()) : item;
    }

    public enum State { IDLE, RECEIVING, COMPLETE, FAILED }

    public enum Disposition { IGNORED, RECEIVING, COMPLETE, FAILED }

    public record Result(
            Disposition disposition,
            ResourceCatalog.RecipeBook recipeBook,
            String detail,
            String catalogId,
            boolean discoveryApplied,
            String discoveryCode,
            int part,
            int parts,
            int recipeCount) {
        public Result {
            disposition = Objects.requireNonNull(disposition, "disposition");
            detail = Objects.requireNonNullElse(detail, "");
            catalogId = Objects.requireNonNullElse(catalogId, "");
            discoveryCode = Objects.requireNonNullElse(discoveryCode, "");
            if (disposition == Disposition.COMPLETE && recipeBook == null) {
                throw new IllegalArgumentException("complete result requires a recipe book");
            }
            if (part < 0 || parts < 0 || recipeCount < 0) {
                throw new IllegalArgumentException("negative recipe catalog result counter");
            }
        }

        static Result ignored(String detail) {
            return new Result(Disposition.IGNORED, null, detail, "", false, "", 0, 0, 0);
        }

        static Result receiving(int part, int parts, int recipes) {
            return new Result(
                    Disposition.RECEIVING, null, "receiving", "", false, "",
                    part, parts, recipes);
        }

        static Result complete(
                ResourceCatalog.RecipeBook book,
                String id,
                int recipes,
                boolean discoveryApplied,
                String discoveryCode) {
            return new Result(
                    Disposition.COMPLETE, book, "complete", id,
                    discoveryApplied, discoveryCode, 0, 0, recipes);
        }

        static Result failed(String detail) {
            return new Result(Disposition.FAILED, null, detail, "", false, "", 0, 0, 0);
        }
    }

    private record Summary(int scanned, int exported, int excluded) { }

    private record ParsedIngredient(int count, List<String> alternatives) { }

    private record ParsedRecipe(
            String id,
            String type,
            String station,
            String outputItem,
            int outputCount,
            List<ParsedIngredient> ingredients,
            int cookTime,
            int alternativeCount,
            int width,
            int height,
            List<List<String>> slots) { }

    private record Discovery(boolean applied, String code) {
        private static Discovery missing() {
            return new Discovery(false, "missing");
        }
    }
}
