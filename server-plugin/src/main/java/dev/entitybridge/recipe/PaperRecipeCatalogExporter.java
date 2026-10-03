package dev.entitybridge.recipe;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.ComplexRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Enumerates the server's complete registered recipe set on Paper's primary thread. */
public final class PaperRecipeCatalogExporter {
    private PaperRecipeCatalogExporter() {
    }

    public static RecipeCatalogSnapshot export(Server server) {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException("Paper recipe export must run on the primary thread");
        }
        TreeMap<String, RecipeCatalogEntry> recipes = new TreeMap<>();
        TreeMap<String, Integer> exclusions = new TreeMap<>();
        int scanned = 0;
        Iterator<Recipe> iterator = server.recipeIterator();
        while (iterator.hasNext()) {
            scanned++;
            Recipe recipe;
            try {
                recipe = iterator.next();
            } catch (RuntimeException failure) {
                increment(exclusions, "invalid_recipe");
                continue;
            }

            Conversion conversion = convert(recipe);
            if (conversion.exclusion() != null) {
                increment(exclusions, conversion.exclusion());
                continue;
            }
            RecipeCatalogEntry entry = conversion.entry();
            RecipeCatalogEntry previous = recipes.putIfAbsent(entry.id(), entry);
            if (previous != null) {
                increment(exclusions, "duplicate_id");
                // Plugin registries should not contain duplicate keys. If one
                // does, retain a deterministic value independent of iterator
                // order and report the other registration as excluded.
                if (entry.canonicalJson().compareTo(previous.canonicalJson()) < 0) {
                    recipes.put(entry.id(), entry);
                }
            }
        }

        TreeMap<String, Integer> exportedByType = new TreeMap<>();
        recipes.values().forEach(recipe -> increment(exportedByType, recipe.type()));
        return new RecipeCatalogSnapshot(
                List.copyOf(recipes.values()), exportedByType, exclusions, scanned);
    }

    private static Conversion convert(Recipe recipe) {
        if (recipe == null) return Conversion.excluded("invalid_recipe");
        if (recipe instanceof ComplexRecipe) return Conversion.excluded("special_recipe");
        if (!(recipe instanceof Keyed keyed)) return Conversion.excluded("missing_key");

        ItemStack result;
        try {
            result = recipe.getResult();
        } catch (RuntimeException failure) {
            return Conversion.excluded("invalid_recipe");
        }
        if (!ordinaryStack(result)) return Conversion.excluded("nbt_or_invalid_output");

        String type;
        String station;
        int cookTime = 0;
        List<RecipeChoice> choices = new ArrayList<>();
        List<Integer> occurrences = new ArrayList<>();
        try {
            if (recipe instanceof ShapedRecipe shaped) {
                return convertShaped(
                        keyed.getKey().toString(), result, shaped.getShape(), shaped.getChoiceMap());
            } else if (recipe instanceof ShapelessRecipe shapeless) {
                type = "shapeless";
                station = "crafting_table";
                for (RecipeChoice choice : shapeless.getChoiceList()) {
                    choices.add(choice);
                    occurrences.add(1);
                }
            } else if (recipe instanceof FurnaceRecipe furnace) {
                type = "smelting";
                station = "furnace";
                choices.add(furnace.getInputChoice());
                occurrences.add(1);
                cookTime = furnace.getCookingTime();
            } else if (recipe instanceof BlastingRecipe blasting) {
                type = "blasting";
                station = "blast_furnace";
                choices.add(blasting.getInputChoice());
                occurrences.add(1);
                cookTime = blasting.getCookingTime();
            } else if (recipe instanceof SmokingRecipe smoking) {
                type = "smoking";
                station = "smoker";
                choices.add(smoking.getInputChoice());
                occurrences.add(1);
                cookTime = smoking.getCookingTime();
            } else if (recipe instanceof CampfireRecipe campfire) {
                type = "campfire_cooking";
                station = "campfire";
                choices.add(campfire.getInputChoice());
                occurrences.add(1);
                cookTime = campfire.getCookingTime();
            } else {
                return Conversion.excluded("unsupported_type");
            }
        } catch (RuntimeException failure) {
            return Conversion.excluded("invalid_recipe");
        }

        LinkedHashMap<List<String>, Integer> grouped = new LinkedHashMap<>();
        for (int index = 0; index < choices.size(); index++) {
            Choice conversion = alternatives(choices.get(index));
            if (conversion.exclusion() != null) {
                return Conversion.excluded(conversion.exclusion());
            }
            grouped.merge(conversion.alternatives(), occurrences.get(index), Integer::sum);
        }
        if (grouped.isEmpty()) return Conversion.excluded("invalid_ingredient");

        ArrayList<RecipeCatalogEntry.Ingredient> ingredients = new ArrayList<>();
        try {
            grouped.forEach((alternatives, count) -> ingredients.add(
                    new RecipeCatalogEntry.Ingredient(count, alternatives)));
            return Conversion.exported(new RecipeCatalogEntry(
                    keyed.getKey().toString(),
                    type,
                    station,
                    result.getType().getKey().toString(),
                    result.getAmount(),
                    ingredients,
                    cookTime,
                    0,
                    0,
                    List.of()));
        } catch (RuntimeException failure) {
            return Conversion.excluded("invalid_recipe");
        }
    }

    private static Conversion convertShaped(
            String id,
            ItemStack result,
            String[] shape,
            Map<Character, RecipeChoice> choiceMap
    ) {
        try {
            SparseShapedRecipe.Layout<RecipeChoice> layout =
                    SparseShapedRecipe.normalize(shape, choiceMap::get);
            LinkedHashMap<Character, List<String>> alternativesBySymbol = new LinkedHashMap<>();
            for (Character symbol : layout.occurrences().keySet()) {
                Choice converted = alternatives(choiceMap.get(symbol));
                if (converted.exclusion() != null) {
                    return Conversion.excluded(converted.exclusion());
                }
                alternativesBySymbol.put(symbol, converted.alternatives());
            }
            return convertSparseShaped(
                    id,
                    result.getType().getKey().toString(),
                    result.getAmount(),
                    shape,
                    alternativesBySymbol);
        } catch (RuntimeException failure) {
            return Conversion.excluded("invalid_recipe");
        }
    }

    /**
     * Offline regression seam for CraftBukkit's sparse shaped-recipe view.
     * Production shaped recipes pass through the same conversion below after
     * their MaterialChoices have been reduced to namespaced alternatives.
     */
    static RecipeCatalogEntry convertSparseShapedForTesting(
            String id,
            String outputItem,
            int outputCount,
            String[] shape,
            Map<Character, List<String>> alternativesBySymbol
    ) {
        Conversion conversion = convertSparseShaped(
                id, outputItem, outputCount, shape, alternativesBySymbol);
        if (conversion.exclusion() != null) {
            throw new IllegalArgumentException(
                    "shaped recipe was excluded: " + conversion.exclusion());
        }
        return conversion.entry();
    }

    private static Conversion convertSparseShaped(
            String id,
            String outputItem,
            int outputCount,
            String[] shape,
            Map<Character, List<String>> alternativesBySymbol
    ) {
        try {
            SparseShapedRecipe.Layout<List<String>> layout =
                    SparseShapedRecipe.normalize(shape, alternativesBySymbol::get);
            LinkedHashMap<List<String>, Integer> grouped = new LinkedHashMap<>();
            ArrayList<List<String>> slots = new ArrayList<>(layout.cells().size());
            for (SparseShapedRecipe.Cell<List<String>> cell : layout.cells()) {
                if (!cell.occupied()) {
                    slots.add(List.of());
                    continue;
                }
                List<String> alternatives = cell.choice().stream()
                        .distinct()
                        .sorted()
                        .toList();
                if (alternatives.isEmpty()) {
                    return Conversion.excluded("invalid_ingredient");
                }
                slots.add(alternatives);
                grouped.merge(alternatives, 1, Math::addExact);
            }
            if (grouped.isEmpty()) return Conversion.excluded("invalid_ingredient");

            ArrayList<RecipeCatalogEntry.Ingredient> ingredients = new ArrayList<>();
            grouped.forEach((alternatives, count) -> ingredients.add(
                    new RecipeCatalogEntry.Ingredient(count, alternatives)));
            return Conversion.exported(new RecipeCatalogEntry(
                    id,
                    "shaped",
                    "crafting_table",
                    outputItem,
                    outputCount,
                    ingredients,
                    0,
                    layout.width(),
                    layout.height(),
                    slots));
        } catch (RuntimeException failure) {
            return Conversion.excluded("invalid_recipe");
        }
    }

    private static Choice alternatives(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.ExactChoice) {
            // Exact choices may depend on NBT/data components. Reducing them
            // to material IDs would make the autonomous client craft a
            // different recipe, so they are deliberately excluded.
            return Choice.excluded("exact_or_nbt_choice");
        }
        if (!(choice instanceof RecipeChoice.MaterialChoice materials)) {
            return Choice.excluded("unsupported_choice");
        }
        List<String> alternatives = materials.getChoices().stream()
                .filter(PaperRecipeCatalogExporter::ordinaryMaterial)
                .map(material -> material.getKey().toString())
                .distinct()
                .sorted()
                .toList();
        return alternatives.isEmpty()
                ? Choice.excluded("invalid_ingredient")
                : Choice.exported(alternatives);
    }

    private static boolean ordinaryStack(ItemStack stack) {
        return stack != null
                && stack.getAmount() > 0
                && ordinaryMaterial(stack.getType())
                && !stack.hasItemMeta();
    }

    private static boolean ordinaryMaterial(Material material) {
        return material != null && !material.isAir() && material.isItem();
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private record Conversion(RecipeCatalogEntry entry, String exclusion) {
        private static Conversion exported(RecipeCatalogEntry entry) {
            return new Conversion(entry, null);
        }

        private static Conversion excluded(String reason) {
            return new Conversion(null, reason);
        }
    }

    private record Choice(List<String> alternatives, String exclusion) {
        private static Choice exported(List<String> alternatives) {
            return new Choice(List.copyOf(alternatives), null);
        }

        private static Choice excluded(String reason) {
            return new Choice(List.of(), reason);
        }
    }
}
