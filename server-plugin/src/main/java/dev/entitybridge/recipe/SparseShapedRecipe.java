package dev.entitybridge.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Normalizes Bukkit's shaped-recipe view without assuming that empty cells are
 * represented by a literal space.
 *
 * <p>CraftBukkit assigns a synthetic character to every cell when it converts
 * a vanilla recipe (for example {@code abc/def/ghi}). Empty cells retain their
 * synthetic character in the shape but deliberately have no entry in the
 * choice map. Consequently, both a space and an undefined character mean an
 * empty grid cell.</p>
 */
final class SparseShapedRecipe {
    private SparseShapedRecipe() {
    }

    static <T> Layout<T> normalize(String[] shape, Function<Character, T> choiceLookup) {
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(choiceLookup, "choiceLookup");
        if (shape.length == 0) throw new IllegalArgumentException("shape is empty");

        int width = 0;
        for (String row : shape) {
            width = Math.max(width, Objects.requireNonNull(row, "shape row").length());
        }
        if (width == 0) throw new IllegalArgumentException("shape is empty");

        ArrayList<Cell<T>> cells = new ArrayList<>(Math.multiplyExact(width, shape.length));
        LinkedHashMap<Character, Integer> occurrences = new LinkedHashMap<>();
        for (String row : shape) {
            for (int column = 0; column < width; column++) {
                char symbol = column < row.length() ? row.charAt(column) : ' ';
                T choice = symbol == ' ' ? null : choiceLookup.apply(symbol);
                if (choice == null) {
                    cells.add(Cell.empty());
                } else {
                    cells.add(Cell.occupied(choice));
                    occurrences.merge(symbol, 1, Math::addExact);
                }
            }
        }
        return new Layout<>(width, shape.length,
                Collections.unmodifiableList(cells),
                Collections.unmodifiableMap(occurrences));
    }

    record Layout<T>(int width, int height, List<Cell<T>> cells,
                     Map<Character, Integer> occurrences) {
        Layout {
            cells = List.copyOf(cells);
            occurrences = Collections.unmodifiableMap(new LinkedHashMap<>(occurrences));
        }
    }

    record Cell<T>(T choice) {
        static <T> Cell<T> empty() {
            return new Cell<>(null);
        }

        static <T> Cell<T> occupied(T choice) {
            return new Cell<>(Objects.requireNonNull(choice, "choice"));
        }

        boolean occupied() {
            return choice != null;
        }
    }
}
