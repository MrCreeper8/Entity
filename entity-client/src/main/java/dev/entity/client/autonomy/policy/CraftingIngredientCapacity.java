package dev.entity.client.autonomy.policy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

/** Exact recipe-slot capacity matching shared by display selection and committed crafting. */
public final class CraftingIngredientCapacity {
    private static final int MAX_SLOTS = 9;
    private static final int MAX_SELECTED_ITEMS = 64;

    private CraftingIngredientCapacity() {
    }

    /**
     * Matches all selected ingredients against a positive whole number of recipe operations.
     * The caller supplies normalized IDs and any explicitly supported legacy equivalence.
     * Empty shaped cells are ignored; an unknown or entirely empty recipe is rejected.
     */
    public static boolean matchesExact(
            List<List<String>> slots,
            Map<String, Integer> selected,
            BiPredicate<String, String> equivalent) {
        List<List<String>> ingredients = ingredientSlots(slots);
        if (ingredients == null || selected == null || equivalent == null
                || selected.isEmpty() || selected.size() > MAX_SELECTED_ITEMS) return false;
        long total = 0;
        for (Map.Entry<String, Integer> entry : selected.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                return false;
            }
            total += entry.getValue();
        }
        if (total > Integer.MAX_VALUE || total % ingredients.size() != 0) return false;
        return suppliesOperations(ingredients, selected,
                (int) (total / ingredients.size()), equivalent);
    }

    /**
     * Tests the remaining capacity of one committed recipe, without assuming which alternative
     * vanilla consumed first. Planned totals must exactly cover the original operation count.
     * Live counts must already exclude other owners' reservations; each item is additionally
     * capped by its original planned total, and unplanned items never provide capacity.
     * This is an admission check, not a grant to mutate inventory or select another recipe.
     */
    public static boolean canComplete(
            List<List<String>> authoritativeSlots,
            Map<String, Integer> plannedTotals,
            Map<String, Integer> liveSpendable,
            int operations,
            int completedOperations) {
        if (operations <= 0 || completedOperations < 0 || completedOperations > operations) {
            return false;
        }
        List<List<String>> slots = normalizeSlots(authoritativeSlots);
        List<List<String>> ingredients = ingredientSlots(slots);
        Map<String, Integer> planned = normalizeCounts(plannedTotals, false);
        Map<String, Integer> live = normalizeCounts(liveSpendable, true);
        if (ingredients == null || planned == null || live == null || planned.isEmpty()) return false;
        long required = (long) ingredients.size() * operations;
        long plannedCount = 0;
        for (int count : planned.values()) plannedCount += count;
        if (required > Integer.MAX_VALUE || plannedCount != required
                || !matchesExact(slots, planned, String::equals)) return false;

        LinkedHashMap<String, Integer> available = new LinkedHashMap<>();
        planned.forEach((item, count) -> {
            int capped = Math.min(count, live.getOrDefault(item, 0));
            if (capped > 0) available.put(item, capped);
        });
        return suppliesOperations(ingredients, available,
                operations - completedOperations, String::equals);
    }

    /**
     * Conservatively fences recipe-book filling, which does not pin the chosen alternative.
     * A reserved item is safe only when its spendable count covers every remaining slot that
     * vanilla could fill with it. Unreserved alternatives do not create a reservation risk.
     */
    public static boolean protectsReservedAlternatives(
            List<List<String>> authoritativeSlots,
            Map<String, Integer> physicalCounts,
            Map<String, Integer> liveSpendable,
            int remainingOperations) {
        List<List<String>> ingredients = ingredientSlots(normalizeSlots(authoritativeSlots));
        Map<String, Integer> physical = normalizeCounts(physicalCounts, true);
        Map<String, Integer> spendable = normalizeCounts(liveSpendable, true);
        if (ingredients == null || physical == null || spendable == null
                || remainingOperations < 0
                || (long) ingredients.size() * remainingOperations > Integer.MAX_VALUE) return false;

        LinkedHashMap<String, Integer> alternativeSlots = new LinkedHashMap<>();
        for (List<String> slot : ingredients) {
            slot.stream().distinct().forEach(item -> alternativeSlots.merge(item, 1, Integer::sum));
        }
        for (Map.Entry<String, Integer> entry : alternativeSlots.entrySet()) {
            int carried = physical.getOrDefault(entry.getKey(), 0);
            int available = spendable.getOrDefault(entry.getKey(), 0);
            if (available > carried) return false;
            long possibleUse = (long) entry.getValue() * remainingOperations;
            if (carried > available && available < possibleUse) return false;
        }
        return true;
    }

    private static List<List<String>> ingredientSlots(List<List<String>> slots) {
        if (slots == null || slots.isEmpty() || slots.size() > MAX_SLOTS) return null;
        ArrayList<List<String>> ingredients = new ArrayList<>();
        for (List<String> slot : slots) {
            if (slot == null) return null;
            for (String alternative : slot) {
                if (alternative == null || alternative.isBlank()) return null;
            }
            if (!slot.isEmpty()) ingredients.add(slot);
        }
        return ingredients.isEmpty() ? null : ingredients;
    }

    private static List<List<String>> normalizeSlots(List<List<String>> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > MAX_SLOTS) return null;
        ArrayList<List<String>> result = new ArrayList<>();
        for (List<String> slot : raw) {
            if (slot == null) return null;
            ArrayList<String> alternatives = new ArrayList<>();
            for (String item : slot) {
                String normalized = normalizeId(item);
                if (normalized == null) return null;
                if (!normalized.equals("minecraft:air")) alternatives.add(normalized);
            }
            result.add(List.copyOf(alternatives));
        }
        return List.copyOf(result);
    }

    private static Map<String, Integer> normalizeCounts(
            Map<String, Integer> raw,
            boolean allowZero) {
        if (raw == null || raw.size() > MAX_SELECTED_ITEMS) return null;
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            String item = normalizeId(entry.getKey());
            Integer count = entry.getValue();
            if (item == null || count == null || count < 0 || (!allowZero && count == 0)) return null;
            long combined = (long) result.getOrDefault(item, 0) + count;
            if (combined > Integer.MAX_VALUE) return null;
            if (combined > 0) result.put(item, (int) combined);
        }
        return result;
    }

    private static String normalizeId(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        return id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") ? id : null;
    }

    /** The existing augmenting-path matcher, with a caller-specified operation demand. */
    private static boolean suppliesOperations(
            List<List<String>> ingredientSlots,
            Map<String, Integer> selected,
            int operations,
            BiPredicate<String, String> equivalent) {
        if (operations == 0) return true;
        int total = Math.multiplyExact(operations, ingredientSlots.size());
        List<Map.Entry<String, Integer>> items = new ArrayList<>(selected.entrySet());
        int source = 0;
        int firstItem = 1;
        int firstSlot = firstItem + items.size();
        int sink = firstSlot + ingredientSlots.size();
        int[][] residual = new int[sink + 1][sink + 1];
        for (int index = 0; index < items.size(); index++) {
            Map.Entry<String, Integer> item = items.get(index);
            int itemNode = firstItem + index;
            residual[source][itemNode] = item.getValue();
            for (int slot = 0; slot < ingredientSlots.size(); slot++) {
                if (ingredientSlots.get(slot).stream().anyMatch(
                        alternative -> equivalent.test(item.getKey(), alternative))) {
                    residual[itemNode][firstSlot + slot] = total;
                }
            }
        }
        for (int slot = 0; slot < ingredientSlots.size(); slot++) {
            residual[firstSlot + slot][sink] = operations;
        }

        int flow = 0;
        int[] parent = new int[residual.length];
        while (flow < total) {
            java.util.Arrays.fill(parent, -1);
            parent[source] = source;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty() && parent[sink] < 0) {
                int from = queue.removeFirst();
                for (int to = 0; to < residual.length; to++) {
                    if (parent[to] < 0 && residual[from][to] > 0) {
                        parent[to] = from;
                        queue.addLast(to);
                    }
                }
            }
            if (parent[sink] < 0) return false;
            int augmentation = total - flow;
            for (int node = sink; node != source; node = parent[node]) {
                augmentation = Math.min(augmentation, residual[parent[node]][node]);
            }
            for (int node = sink; node != source; node = parent[node]) {
                int previous = parent[node];
                residual[previous][node] -= augmentation;
                residual[node][previous] += augmentation;
            }
            flow += augmentation;
        }
        return true;
    }
}
