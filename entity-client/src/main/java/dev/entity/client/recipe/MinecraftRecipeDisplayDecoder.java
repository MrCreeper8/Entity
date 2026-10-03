package dev.entity.client.recipe;

import dev.entity.core.recipe.RecipeDefinition;
import dev.entity.core.recipe.RecipeIngredient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.FurnaceRecipeDisplay;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Maps the 1.21.8 client recipe-display protocol into the generic core model. */
final class MinecraftRecipeDisplayDecoder {
    private MinecraftRecipeDisplayDecoder() {
    }

    static DecodeBatch decodeEntries(Collection<RecipeDisplayEntry> entries) {
        ArrayList<RecipeDisplay> displays = new ArrayList<>(entries.size());
        for (RecipeDisplayEntry entry : entries) {
            displays.add(Objects.requireNonNull(entry, "recipe display entry").display());
        }
        return decodeDisplays(displays);
    }

    /** Package-visible boundary used by the mapped 1.21.8 regression harness. */
    static DecodeBatch decodeDisplays(Collection<? extends RecipeDisplay> displays) {
        ArrayList<RecipeDefinition> recipes = new ArrayList<>();
        LinkedHashSet<String> limitations = new LinkedHashSet<>();
        int observed = 0;
        int unsupported = 0;
        for (RecipeDisplay display : displays) {
            observed++;
            DecodeOne decoded;
            try {
                decoded = decode(Objects.requireNonNull(display, "recipe display"));
            } catch (RuntimeException malformed) {
                decoded = DecodeOne.unsupported(
                        "malformed " + display.getClass().getSimpleName() + ": "
                                + malformed.getClass().getSimpleName());
            }
            if (decoded.recipe().isPresent()) {
                recipes.add(decoded.recipe().orElseThrow());
            } else {
                unsupported++;
                limitations.add(decoded.reason());
            }
        }
        return new DecodeBatch(recipes, observed, unsupported, List.copyOf(limitations));
    }

    private static DecodeOne decode(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            Output output = output(shaped.result());
            RecipeDefinition.Station station = station(shaped.craftingStation());
            if (station != RecipeDefinition.Station.CRAFTING_TABLE) {
                return DecodeOne.unsupported("shaped recipe uses an unsupported station display");
            }
            if (shaped.ingredients().size() != shaped.width() * shaped.height()) {
                return DecodeOne.unsupported("shaped recipe grid size does not match its dimensions");
            }
            ArrayList<RecipeIngredient> ingredients = new ArrayList<>();
            for (int slot = 0; slot < shaped.ingredients().size(); slot++) {
                IngredientResult ingredient = ingredient(shaped.ingredients().get(slot), slot);
                if (ingredient.unsupported()) {
                    return DecodeOne.unsupported(ingredient.reason());
                }
                ingredient.value().ifPresent(ingredients::add);
            }
            return DecodeOne.recipe(new RecipeDefinition(
                    RecipeDefinition.Kind.SHAPED_CRAFTING,
                    station,
                    output.itemId(),
                    output.count(),
                    shaped.width(),
                    shaped.height(),
                    ingredients,
                    false,
                    0,
                    0));
        }

        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            Output output = output(shapeless.result());
            RecipeDefinition.Station station = station(shapeless.craftingStation());
            if (station != RecipeDefinition.Station.CRAFTING_TABLE) {
                return DecodeOne.unsupported("shapeless recipe uses an unsupported station display");
            }
            ArrayList<RecipeIngredient> ingredients = new ArrayList<>();
            for (int slot = 0; slot < shapeless.ingredients().size(); slot++) {
                IngredientResult ingredient = ingredient(shapeless.ingredients().get(slot), slot);
                if (ingredient.unsupported()) {
                    return DecodeOne.unsupported(ingredient.reason());
                }
                ingredient.value().ifPresent(ingredients::add);
            }
            return DecodeOne.recipe(new RecipeDefinition(
                    RecipeDefinition.Kind.SHAPELESS_CRAFTING,
                    station,
                    output.itemId(),
                    output.count(),
                    0,
                    0,
                    ingredients,
                    false,
                    0,
                    0));
        }

        if (display instanceof FurnaceRecipeDisplay cooking) {
            Output output = output(cooking.result());
            RecipeDefinition.Station station = station(cooking.craftingStation());
            if (station == null || !station.isCookingStation()) {
                return DecodeOne.unsupported("cooking recipe uses an unsupported station display");
            }
            IngredientResult input = ingredient(cooking.ingredient(), 0);
            if (input.unsupported() || input.value().isEmpty()) {
                return DecodeOne.unsupported(input.unsupported()
                        ? input.reason() : "cooking recipe has no concrete input");
            }
            return DecodeOne.recipe(new RecipeDefinition(
                    RecipeDefinition.Kind.COOKING,
                    station,
                    output.itemId(),
                    output.count(),
                    0,
                    0,
                    List.of(input.value().orElseThrow()),
                    station != RecipeDefinition.Station.CAMPFIRE,
                    cooking.duration(),
                    cooking.experience()));
        }

        return DecodeOne.unsupported(
                "unsupported recipe display type " + display.getClass().getSimpleName());
    }

    private static IngredientResult ingredient(SlotDisplay display, int slot) {
        if (display instanceof SlotDisplay.EmptySlotDisplay) {
            return IngredientResult.empty();
        }
        AlternativeSet alternatives = alternatives(display);
        if (!alternatives.supported()) {
            return IngredientResult.unsupported(alternatives.reason());
        }
        if (alternatives.items().isEmpty() && alternatives.tags().isEmpty()) {
            return IngredientResult.empty();
        }
        return IngredientResult.value(new RecipeIngredient(
                slot,
                alternatives.count(),
                List.copyOf(alternatives.items()),
                List.copyOf(alternatives.tags()),
                List.copyOf(alternatives.remainderItems()),
                List.copyOf(alternatives.remainderTags())));
    }

    private static AlternativeSet alternatives(SlotDisplay display) {
        if (display instanceof SlotDisplay.ItemSlotDisplay item) {
            return AlternativeSet.value(
                    1, Set.of(itemId(item.item().value())), Set.of(), Set.of(), Set.of());
        }
        if (display instanceof SlotDisplay.StackSlotDisplay stackDisplay) {
            ItemStack stack = stackDisplay.stack();
            if (stack.isEmpty() || stack.getCount() <= 0) {
                return AlternativeSet.empty();
            }
            return AlternativeSet.value(
                    stack.getCount(), Set.of(itemId(stack.getItem())), Set.of(), Set.of(), Set.of());
        }
        if (display instanceof SlotDisplay.TagSlotDisplay tagDisplay) {
            TreeSet<String> items = new TreeSet<>();
            for (RegistryEntry<Item> entry : Registries.ITEM.iterateEntries(tagDisplay.tag())) {
                items.add(itemId(entry.value()));
            }
            return AlternativeSet.value(
                    1,
                    items,
                    Set.of(tagDisplay.tag().id().toString()),
                    Set.of(),
                    Set.of());
        }
        if (display instanceof SlotDisplay.CompositeSlotDisplay composite) {
            AlternativeSet combined = null;
            for (SlotDisplay child : composite.contents()) {
                AlternativeSet next = alternatives(child);
                if (!next.supported()) return next;
                if (next.items().isEmpty() && next.tags().isEmpty()) continue;
                if (combined == null) {
                    combined = next;
                } else if (combined.count() != next.count()) {
                    return AlternativeSet.unsupported(
                            "composite ingredient alternatives consume different counts");
                } else {
                    combined = combined.union(next);
                }
            }
            return combined == null ? AlternativeSet.empty() : combined;
        }
        if (display instanceof SlotDisplay.WithRemainderSlotDisplay withRemainder) {
            AlternativeSet input = alternatives(withRemainder.input());
            AlternativeSet remainder = alternatives(withRemainder.remainder());
            if (!input.supported()) return input;
            if (!remainder.supported()) {
                return AlternativeSet.unsupported(
                        "unsupported recipe remainder: " + remainder.reason());
            }
            return input.withRemainders(remainder.items(), remainder.tags());
        }
        if (display instanceof SlotDisplay.EmptySlotDisplay) {
            return AlternativeSet.empty();
        }
        if (display instanceof SlotDisplay.AnyFuelSlotDisplay) {
            return AlternativeSet.unsupported(
                    "wildcard fuel is represented by the synchronized fuel registry, not an ingredient");
        }
        return AlternativeSet.unsupported(
                "unsupported ingredient display type " + display.getClass().getSimpleName());
    }

    private static Output output(SlotDisplay display) {
        if (display instanceof SlotDisplay.StackSlotDisplay stackDisplay) {
            ItemStack stack = stackDisplay.stack();
            if (stack.isEmpty() || stack.getCount() <= 0) {
                throw new IllegalArgumentException("recipe output stack is empty");
            }
            return new Output(itemId(stack.getItem()), stack.getCount());
        }
        if (display instanceof SlotDisplay.ItemSlotDisplay itemDisplay) {
            return new Output(itemId(itemDisplay.item().value()), 1);
        }
        throw new IllegalArgumentException(
                "recipe output is not a constant item stack: " + display.getClass().getSimpleName());
    }

    private static RecipeDefinition.Station station(SlotDisplay display) {
        String item;
        try {
            item = output(display).itemId();
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
        return switch (item) {
            case "minecraft:crafting_table" -> RecipeDefinition.Station.CRAFTING_TABLE;
            case "minecraft:furnace" -> RecipeDefinition.Station.FURNACE;
            case "minecraft:blast_furnace" -> RecipeDefinition.Station.BLAST_FURNACE;
            case "minecraft:smoker" -> RecipeDefinition.Station.SMOKER;
            case "minecraft:campfire", "minecraft:soul_campfire" -> RecipeDefinition.Station.CAMPFIRE;
            default -> null;
        };
    }

    static Map<String, Integer> fuelBurnTicks(net.minecraft.item.FuelRegistry fuelRegistry) {
        java.util.TreeMap<String, Integer> fuels = new java.util.TreeMap<>();
        for (Item item : fuelRegistry.getFuelItems()) {
            int ticks = fuelRegistry.getFuelTicks(item.getDefaultStack());
            if (ticks > 0) fuels.put(itemId(item), ticks);
        }
        return Map.copyOf(fuels);
    }

    private static String itemId(Item item) {
        String id = Registries.ITEM.getId(Objects.requireNonNull(item, "item")).toString();
        if ("minecraft:air".equals(id)) {
            throw new IllegalArgumentException("air cannot be a recipe item");
        }
        return id;
    }

    record DecodeBatch(
            List<RecipeDefinition> recipes,
            int observed,
            int unsupported,
            List<String> limitations) {
        DecodeBatch {
            recipes = List.copyOf(recipes);
            limitations = List.copyOf(limitations);
        }
    }

    private record DecodeOne(Optional<RecipeDefinition> recipe, String reason) {
        private static DecodeOne recipe(RecipeDefinition recipe) {
            return new DecodeOne(Optional.of(recipe), "");
        }

        private static DecodeOne unsupported(String reason) {
            return new DecodeOne(Optional.empty(), reason);
        }
    }

    private record IngredientResult(
            Optional<RecipeIngredient> value, boolean unsupported, String reason) {
        private static IngredientResult value(RecipeIngredient ingredient) {
            return new IngredientResult(Optional.of(ingredient), false, "");
        }

        private static IngredientResult empty() {
            return new IngredientResult(Optional.empty(), false, "");
        }

        private static IngredientResult unsupported(String reason) {
            return new IngredientResult(Optional.empty(), true, reason);
        }
    }

    private record AlternativeSet(
            int count,
            Set<String> items,
            Set<String> tags,
            Set<String> remainderItems,
            Set<String> remainderTags,
            boolean supported,
            String reason) {
        private AlternativeSet {
            items = Set.copyOf(items);
            tags = Set.copyOf(tags);
            remainderItems = Set.copyOf(remainderItems);
            remainderTags = Set.copyOf(remainderTags);
        }

        private static AlternativeSet value(
                int count,
                Collection<String> items,
                Collection<String> tags,
                Collection<String> remainderItems,
                Collection<String> remainderTags) {
            return new AlternativeSet(
                    count,
                    new TreeSet<>(items),
                    new TreeSet<>(tags),
                    new TreeSet<>(remainderItems),
                    new TreeSet<>(remainderTags),
                    true,
                    "");
        }

        private static AlternativeSet empty() {
            return value(1, Set.of(), Set.of(), Set.of(), Set.of());
        }

        private static AlternativeSet unsupported(String reason) {
            return new AlternativeSet(1, Set.of(), Set.of(), Set.of(), Set.of(), false, reason);
        }

        private AlternativeSet union(AlternativeSet other) {
            TreeSet<String> mergedItems = new TreeSet<>(items);
            TreeSet<String> mergedTags = new TreeSet<>(tags);
            TreeSet<String> mergedRemainderItems = new TreeSet<>(remainderItems);
            TreeSet<String> mergedRemainderTags = new TreeSet<>(remainderTags);
            mergedItems.addAll(other.items);
            mergedTags.addAll(other.tags);
            mergedRemainderItems.addAll(other.remainderItems);
            mergedRemainderTags.addAll(other.remainderTags);
            return value(count, mergedItems, mergedTags, mergedRemainderItems, mergedRemainderTags);
        }

        private AlternativeSet withRemainders(
                Collection<String> extraItems, Collection<String> extraTags) {
            TreeSet<String> allRemainderItems = new TreeSet<>(remainderItems);
            TreeSet<String> allRemainderTags = new TreeSet<>(remainderTags);
            allRemainderItems.addAll(extraItems);
            allRemainderTags.addAll(extraTags);
            return value(count, items, tags, allRemainderItems, allRemainderTags);
        }
    }

    private record Output(String itemId, int count) {
    }
}
