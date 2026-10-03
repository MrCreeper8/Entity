package dev.entity.client.autonomy.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Exact inventory semantics for a stock request that accepts any natural log family.
 *
 * <p>The family ID is deliberately not a Minecraft item. It is a durable objective used only
 * when the caller genuinely accepts mixed log species. Concrete recipe ingredients continue to
 * use their exact registry IDs, so acquiring generic Home stock cannot make an acacia recipe
 * believe that birch is acacia.</p>
 */
public final class WoodLogFamilyPolicy {
    public static final String FAMILY_ITEM = "logs";

    private static final List<String> MEMBERS = List.of(
            "oak_log",
            "spruce_log",
            "birch_log",
            "jungle_log",
            "acacia_log",
            "dark_oak_log",
            "mangrove_log",
            "cherry_log",
            "pale_oak_log",
            "crimson_stem",
            "warped_stem");

    private WoodLogFamilyPolicy() {
    }

    public static List<String> members() {
        return MEMBERS;
    }

    public static boolean isFamilyRequest(String rawItem) {
        return FAMILY_ITEM.equals(normalize(rawItem));
    }

    /** Counts every accepted physical member without inventing a preferred species. */
    public static int aggregateCount(Map<String, Integer> inventory) {
        Map<String, Integer> normalized = normalizedCounts(inventory);
        int total = 0;
        for (String member : MEMBERS) {
            total = Math.addExact(total, normalized.getOrDefault(member, 0));
        }
        return total;
    }

    /**
     * Binds an abstract family floor to concrete carried stacks. The returned member counts can
     * be reserved physically, and a planner can remove the same slices from spendable recipe
     * inventory while retaining their aggregate family credit.
     */
    public static Map<String, Integer> allocateExistingFloor(
            Map<String, Integer> inventory,
            int requestedCount) {
        if (requestedCount < 0) {
            throw new IllegalArgumentException("requestedCount cannot be negative");
        }
        Map<String, Integer> normalized = normalizedCounts(inventory);
        LinkedHashMap<String, Integer> allocation = new LinkedHashMap<>();
        int remaining = requestedCount;
        for (String member : MEMBERS) {
            if (remaining == 0) break;
            int count = Math.min(remaining, normalized.getOrDefault(member, 0));
            if (count > 0) {
                allocation.put(member, count);
                remaining -= count;
            }
        }
        return Collections.unmodifiableMap(allocation);
    }

    public static String expectedItemsArgument() {
        return String.join(",", MEMBERS);
    }

    private static Map<String, Integer> normalizedCounts(Map<String, Integer> inventory) {
        Objects.requireNonNull(inventory, "inventory");
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        inventory.forEach((raw, count) -> {
            String item = normalize(raw);
            if (count == null || count < 0) {
                throw new IllegalArgumentException(
                        "inventory contains a negative/null count for " + item);
            }
            if (count > 0) normalized.merge(item, count, Math::addExact);
        });
        return normalized;
    }

    private static String normalize(String raw) {
        Objects.requireNonNull(raw, "item");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return normalized;
    }
}
