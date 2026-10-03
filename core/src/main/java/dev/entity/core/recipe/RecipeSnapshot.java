package dev.entity.core.recipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable recipe/fuel knowledge with an explicit authority boundary.
 *
 * <p>A client recipe book is only the player's currently unlocked display
 * set. Consumers that need a complete production graph must call
 * {@link #requireComplete()} and therefore fail closed on client-only data.</p>
 */
public record RecipeSnapshot(
        Completeness completeness,
        List<RecipeDefinition> recipes,
        Map<String, Integer> fuelBurnTicks,
        String detail) {

    public RecipeSnapshot {
        completeness = Objects.requireNonNull(completeness, "completeness");
        detail = Objects.requireNonNullElse(detail, "").trim();
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(fuelBurnTicks, "fuelBurnTicks");

        TreeMap<String, RecipeDefinition> recipesByKey = new TreeMap<>();
        for (RecipeDefinition recipe : recipes) {
            RecipeDefinition checked = Objects.requireNonNull(recipe, "recipe");
            RecipeDefinition duplicate = recipesByKey.putIfAbsent(checked.stableKey(), checked);
            if (duplicate != null && !duplicate.equals(checked)) {
                throw new IllegalArgumentException("stable recipe key collision");
            }
        }
        recipes = List.copyOf(new ArrayList<>(recipesByKey.values()));

        TreeMap<String, Integer> sortedFuel = new TreeMap<>();
        fuelBurnTicks.forEach((rawId, ticks) -> {
            String id = Objects.requireNonNull(rawId, "fuel item")
                    .trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() || id.indexOf(':') <= 0 || ticks == null || ticks <= 0) {
                throw new IllegalArgumentException("invalid fuel entry: " + rawId + "=" + ticks);
            }
            sortedFuel.put(id, ticks);
        });
        fuelBurnTicks = Collections.unmodifiableMap(new LinkedHashMap<>(sortedFuel));

        if (completeness == Completeness.UNAVAILABLE) {
            if (!recipes.isEmpty() || !fuelBurnTicks.isEmpty() || detail.isEmpty()) {
                throw new IllegalArgumentException(
                        "unavailable snapshots must be empty and explain why");
            }
        }
    }

    public static RecipeSnapshot unavailable(String reason) {
        return new RecipeSnapshot(Completeness.UNAVAILABLE, List.of(), Map.of(), reason);
    }

    public static RecipeSnapshot partialUnlocked(
            Collection<RecipeDefinition> recipes,
            Map<String, Integer> fuelBurnTicks,
            String detail) {
        return new RecipeSnapshot(
                Completeness.PARTIAL_UNLOCKED,
                List.copyOf(recipes),
                fuelBurnTicks,
                detail);
    }

    public boolean isAvailable() {
        return completeness != Completeness.UNAVAILABLE;
    }

    public boolean isComplete() {
        return completeness == Completeness.COMPLETE_AUTHORITATIVE;
    }

    public RecipeSnapshot requireComplete() {
        if (!isComplete()) {
            throw new IncompleteRecipeSnapshotException(completeness, detail);
        }
        return this;
    }

    public Map<String, List<RecipeDefinition>> recipesByOutput() {
        LinkedHashMap<String, List<RecipeDefinition>> grouped = new LinkedHashMap<>();
        for (RecipeDefinition recipe : recipes) {
            grouped.computeIfAbsent(recipe.outputItem(), ignored -> new ArrayList<>()).add(recipe);
        }
        grouped.replaceAll((ignored, variants) -> List.copyOf(variants));
        return Collections.unmodifiableMap(grouped);
    }

    public enum Completeness {
        UNAVAILABLE,
        PARTIAL_UNLOCKED,
        COMPLETE_AUTHORITATIVE
    }

    public static final class IncompleteRecipeSnapshotException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final Completeness completeness;

        public IncompleteRecipeSnapshotException(Completeness completeness, String detail) {
            super("Recipe snapshot is not complete (" + completeness + "): " + detail);
            this.completeness = completeness;
        }

        public Completeness completeness() {
            return completeness;
        }
    }
}
