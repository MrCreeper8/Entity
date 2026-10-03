package dev.entity.core.recipe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** A Minecraft-version-independent, deterministic production recipe. */
public record RecipeDefinition(
        Kind kind,
        Station station,
        String outputItem,
        int outputCount,
        int width,
        int height,
        List<RecipeIngredient> ingredients,
        boolean fuelRequired,
        int cookingTicks,
        float experience) {

    public RecipeDefinition {
        kind = Objects.requireNonNull(kind, "kind");
        station = Objects.requireNonNull(station, "station");
        outputItem = canonicalId(outputItem, "outputItem");
        if (outputCount <= 0) {
            throw new IllegalArgumentException("outputCount must be positive");
        }
        if (!Float.isFinite(experience) || experience < 0) {
            throw new IllegalArgumentException("experience must be finite and non-negative");
        }
        Objects.requireNonNull(ingredients, "ingredients");
        ArrayList<RecipeIngredient> copy = new ArrayList<>(ingredients.size());
        for (RecipeIngredient ingredient : ingredients) {
            copy.add(Objects.requireNonNull(ingredient, "ingredient"));
        }

        if (kind == Kind.SHAPED_CRAFTING) {
            if (station != Station.CRAFTING_TABLE || width < 1 || height < 1
                    || width > 3 || height > 3) {
                throw new IllegalArgumentException("shaped crafting requires a 1..3 by 1..3 crafting grid");
            }
            copy.sort(Comparator.comparingInt(RecipeIngredient::slot));
            Set<Integer> slots = new HashSet<>();
            for (RecipeIngredient ingredient : copy) {
                if (ingredient.slot() >= width * height || !slots.add(ingredient.slot())) {
                    throw new IllegalArgumentException("invalid or duplicate shaped ingredient slot");
                }
            }
            if (fuelRequired || cookingTicks != 0 || experience != 0) {
                throw new IllegalArgumentException("crafting recipes cannot declare cooking metadata");
            }
        } else if (kind == Kind.SHAPELESS_CRAFTING) {
            if (station != Station.CRAFTING_TABLE || width != 0 || height != 0) {
                throw new IllegalArgumentException("shapeless crafting uses an unordered crafting grid");
            }
            copy.sort(Comparator.comparing(RecipeIngredient::alternativesKey));
            for (int index = 0; index < copy.size(); index++) {
                copy.set(index, copy.get(index).withSlot(index));
            }
            if (fuelRequired || cookingTicks != 0 || experience != 0) {
                throw new IllegalArgumentException("crafting recipes cannot declare cooking metadata");
            }
        } else {
            if (!station.isCookingStation() || width != 0 || height != 0
                    || copy.size() != 1 || copy.getFirst().slot() != 0 || cookingTicks <= 0) {
                throw new IllegalArgumentException("cooking requires one input and positive duration");
            }
            boolean expectedFuel = station != Station.CAMPFIRE;
            if (fuelRequired != expectedFuel) {
                throw new IllegalArgumentException(
                        "fuel requirement does not match cooking station " + station);
            }
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("recipe must contain at least one ingredient");
        }
        ingredients = List.copyOf(copy);
    }

    /** Stable across packet-local recipe IDs and source collection order. */
    public String stableKey() {
        String canonical = canonicalForm();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return "recipe:" + outputItem + ":" + hash;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    public String canonicalForm() {
        StringBuilder value = new StringBuilder(160)
                .append(kind).append('|')
                .append(station).append('|')
                .append(outputItem).append('|')
                .append(outputCount).append('|')
                .append(width).append('x').append(height).append('|')
                .append(fuelRequired).append('|')
                .append(cookingTicks).append('|')
                .append(Float.toHexString(experience));
        for (RecipeIngredient ingredient : ingredients) {
            value.append('|').append(ingredient.canonicalKey());
        }
        return value.toString();
    }

    private static String canonicalId(String raw, String label) {
        String id = Objects.requireNonNull(raw, label).trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || id.indexOf(':') <= 0) {
            throw new IllegalArgumentException(label + " must be a namespaced identifier: " + raw);
        }
        return id;
    }

    public enum Kind {
        SHAPED_CRAFTING,
        SHAPELESS_CRAFTING,
        COOKING
    }

    public enum Station {
        CRAFTING_TABLE,
        FURNACE,
        BLAST_FURNACE,
        SMOKER,
        CAMPFIRE;

        public boolean isCookingStation() {
            return this != CRAFTING_TABLE;
        }
    }
}
