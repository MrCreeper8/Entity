package dev.entity.core.recipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * One consumed recipe slot and every item/tag accepted by that slot.
 *
 * <p>Tag identifiers are retained even when the current registry can expand
 * them. This prevents a datapack tag from being flattened into an unexplained
 * list and lets a future authoritative recipe source preserve its semantics.</p>
 */
public record RecipeIngredient(
        int slot,
        int count,
        List<String> itemAlternatives,
        List<String> tagAlternatives,
        List<String> remainderItemAlternatives,
        List<String> remainderTagAlternatives) {

    public RecipeIngredient {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be non-negative");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("ingredient count must be positive");
        }
        itemAlternatives = canonicalIds(itemAlternatives, "item alternative");
        tagAlternatives = canonicalIds(tagAlternatives, "tag alternative");
        remainderItemAlternatives = canonicalIds(
                remainderItemAlternatives, "remainder item alternative");
        remainderTagAlternatives = canonicalIds(
                remainderTagAlternatives, "remainder tag alternative");
        if (itemAlternatives.isEmpty() && tagAlternatives.isEmpty()) {
            throw new IllegalArgumentException("ingredient must accept at least one item or tag");
        }
    }

    public RecipeIngredient(
            int slot,
            int count,
            Collection<String> itemAlternatives,
            Collection<String> tagAlternatives) {
        this(slot, count, List.copyOf(itemAlternatives), List.copyOf(tagAlternatives),
                List.of(), List.of());
    }

    public RecipeIngredient withSlot(int newSlot) {
        return new RecipeIngredient(
                newSlot,
                count,
                itemAlternatives,
                tagAlternatives,
                remainderItemAlternatives,
                remainderTagAlternatives);
    }

    /** Canonical value excluding the grid position, used for shapeless order. */
    String alternativesKey() {
        return count + ";i=" + String.join(",", itemAlternatives)
                + ";t=" + String.join(",", tagAlternatives)
                + ";ri=" + String.join(",", remainderItemAlternatives)
                + ";rt=" + String.join(",", remainderTagAlternatives);
    }

    String canonicalKey() {
        return slot + ";" + alternativesKey();
    }

    private static List<String> canonicalIds(Collection<String> raw, String label) {
        Objects.requireNonNull(raw, label + "s");
        TreeSet<String> sorted = new TreeSet<>();
        for (String id : raw) {
            String normalized = Objects.requireNonNull(id, label)
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || normalized.indexOf(':') <= 0) {
                throw new IllegalArgumentException(label + " must be a namespaced identifier: " + id);
            }
            sorted.add(normalized);
        }
        return List.copyOf(new ArrayList<>(sorted));
    }
}
