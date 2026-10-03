package dev.entitybridge.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** One ordinary, machine-actionable Paper recipe. */
public record RecipeCatalogEntry(
        String id,
        String type,
        String station,
        String outputItem,
        int outputCount,
        List<Ingredient> ingredients,
        int cookTime,
        int width,
        int height,
        List<List<String>> slots
) {
    private static final Pattern KEY = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+"
    );
    private static final Set<String> TYPES = Set.of(
            "shaped", "shapeless", "smelting", "blasting", "smoking", "campfire_cooking"
    );
    private static final Set<String> STATIONS = Set.of(
            "crafting_table", "furnace", "blast_furnace", "smoker", "campfire"
    );

    public RecipeCatalogEntry {
        id = requireKey(id, "recipe id");
        type = requireToken(type, "recipe type");
        station = requireToken(station, "recipe station");
        outputItem = requireKey(outputItem, "output item");
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("unsupported recipe type: " + type);
        }
        if (!STATIONS.contains(station)) {
            throw new IllegalArgumentException("unsupported recipe station: " + station);
        }
        if ((type.equals("shaped") || type.equals("shapeless"))
                != station.equals("crafting_table")) {
            throw new IllegalArgumentException("recipe type/station mismatch");
        }
        if (outputCount < 1 || outputCount > 4_096) {
            throw new IllegalArgumentException("output count must be between 1 and 4096");
        }
        if (cookTime < 0 || cookTime > 20_000_000) {
            throw new IllegalArgumentException("cook time is outside supported bounds");
        }
        if ((type.equals("shaped") || type.equals("shapeless")) && cookTime != 0) {
            throw new IllegalArgumentException("crafting recipes cannot have cook time");
        }
        if (!(type.equals("shaped") || type.equals("shapeless")) && cookTime < 1) {
            throw new IllegalArgumentException("cooking recipes require positive cook time");
        }
        if (ingredients == null || ingredients.isEmpty() || ingredients.size() > 64) {
            throw new IllegalArgumentException("recipe requires 1 through 64 ingredient groups");
        }
        ArrayList<Ingredient> ordered = new ArrayList<>(ingredients);
        ordered.sort(Comparator
                .comparing((Ingredient ingredient) -> String.join("\u0000", ingredient.alternatives()))
                .thenComparingInt(Ingredient::count));
        ingredients = List.copyOf(ordered);

        Objects.requireNonNull(slots, "recipe slots");
        if (type.equals("shaped")) {
            if (width < 1 || width > 3 || height < 1 || height > 3
                    || slots.size() != width * height) {
                throw new IllegalArgumentException(
                        "shaped recipe dimensions/slots must describe a 1x1 through 3x3 grid");
            }
            ArrayList<List<String>> canonicalSlots = new ArrayList<>(slots.size());
            for (List<String> rawSlot : slots) {
                if (rawSlot == null || rawSlot.size() > 1_024) {
                    throw new IllegalArgumentException("shaped recipe slot is invalid");
                }
                LinkedHashSet<String> alternatives = new LinkedHashSet<>();
                rawSlot.stream()
                        .map(value -> requireKey(value, "slot alternative"))
                        .sorted()
                        .forEach(alternatives::add);
                canonicalSlots.add(List.copyOf(alternatives));
            }
            slots = List.copyOf(canonicalSlots);
            if (!ingredientMultiplicity(ingredients).equals(slotMultiplicity(slots))) {
                throw new IllegalArgumentException(
                        "shaped recipe slots do not match its grouped ingredients");
            }
        } else {
            if (width != 0 || height != 0 || !slots.isEmpty()) {
                throw new IllegalArgumentException(
                        "only shaped recipes may declare dimensions or slots");
            }
            slots = List.of();
        }
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("type", type);
        json.addProperty("station", station);
        JsonObject output = new JsonObject();
        output.addProperty("item", outputItem);
        output.addProperty("count", outputCount);
        json.add("output", output);
        JsonArray encodedIngredients = new JsonArray();
        for (Ingredient ingredient : ingredients) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("count", ingredient.count());
            JsonArray alternatives = new JsonArray();
            ingredient.alternatives().forEach(alternatives::add);
            encoded.add("alternatives", alternatives);
            encodedIngredients.add(encoded);
        }
        json.add("ingredients", encodedIngredients);
        json.addProperty("cookTime", cookTime);
        json.addProperty("width", width);
        json.addProperty("height", height);
        JsonArray encodedSlots = new JsonArray();
        for (List<String> slot : slots) {
            JsonArray alternatives = new JsonArray();
            slot.forEach(alternatives::add);
            encodedSlots.add(alternatives);
        }
        json.add("slots", encodedSlots);
        return json;
    }

    String canonicalJson() {
        return toJson().toString();
    }

    private static String requireKey(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 256 || !KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException(label + " must be a namespaced identifier");
        }
        return normalized;
    }

    private static String requireToken(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

    private static java.util.Map<List<String>, Integer> ingredientMultiplicity(
            List<Ingredient> ingredients) {
        java.util.LinkedHashMap<List<String>, Integer> result = new java.util.LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            result.merge(ingredient.alternatives(), ingredient.count(), Math::addExact);
        }
        return result;
    }

    private static java.util.Map<List<String>, Integer> slotMultiplicity(
            List<List<String>> slots) {
        java.util.LinkedHashMap<List<String>, Integer> result = new java.util.LinkedHashMap<>();
        for (List<String> slot : slots) {
            if (!slot.isEmpty()) result.merge(slot, 1, Math::addExact);
        }
        return result;
    }

    public record Ingredient(int count, List<String> alternatives) {
        public Ingredient {
            if (count < 1 || count > 64) {
                throw new IllegalArgumentException("ingredient count must be between 1 and 64");
            }
            if (alternatives == null || alternatives.isEmpty() || alternatives.size() > 1_024) {
                throw new IllegalArgumentException(
                        "ingredient choice requires 1 through 1024 alternatives");
            }
            LinkedHashSet<String> canonical = new LinkedHashSet<>();
            alternatives.stream()
                    .map(value -> requireKey(value, "ingredient alternative"))
                    .sorted()
                    .forEach(canonical::add);
            alternatives = List.copyOf(canonical);
        }
    }
}
