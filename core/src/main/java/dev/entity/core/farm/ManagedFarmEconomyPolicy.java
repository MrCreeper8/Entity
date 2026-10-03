package dev.entity.core.farm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exact inventory boundary for one finite managed-farm pass.
 *
 * <p>The carried baseline is captured after any Home seed withdrawal and before
 * the first crop mutation. Only a positive post-pass delta from the supported
 * crop families is farm-earned surplus. This prevents a farm return from
 * donating pre-existing food, tools, cargo, or the seed it borrowed from Home.</p>
 */
public final class ManagedFarmEconomyPolicy {
    private static final List<String> USEFUL_OUTPUTS;
    private static final Set<String> USEFUL_OUTPUT_SET;

    static {
        LinkedHashSet<String> outputs = new LinkedHashSet<>();
        for (ManagedFarmPolicy.CropFamily crop : ManagedFarmPolicy.crops()) {
            outputs.add(crop.produce());
            outputs.add(crop.replant());
        }
        USEFUL_OUTPUTS = List.copyOf(outputs);
        USEFUL_OUTPUT_SET = Set.copyOf(outputs);
    }

    private ManagedFarmEconomyPolicy() {
    }

    /** Stable useful-item order for deterministic chest allocation. */
    public static List<String> usefulOutputs() {
        return USEFUL_OUTPUTS;
    }

    /** Captures only supported farm items; unrelated inventory never becomes eligible. */
    public static Map<String, Integer> baseline(Map<String, Integer> carriedCounts) {
        Map<String, Integer> carried = normalized(carriedCounts, "carriedCounts");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (String item : USEFUL_OUTPUTS) {
            int count = carried.getOrDefault(item, 0);
            if (count > 0) result.put(item, count);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Returns only physically carried quantities earned after the baseline. */
    public static Map<String, Integer> surplus(
            Map<String, Integer> baseline,
            Map<String, Integer> carriedCounts) {
        Map<String, Integer> floor = normalized(baseline, "baseline");
        if (!USEFUL_OUTPUT_SET.containsAll(floor.keySet())) {
            throw new IllegalArgumentException("baseline contains a non-farm item");
        }
        Map<String, Integer> carried = normalized(carriedCounts, "carriedCounts");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (String item : USEFUL_OUTPUTS) {
            int count = Math.max(0,
                    carried.getOrDefault(item, 0) - floor.getOrDefault(item, 0));
            if (count > 0) result.put(item, count);
        }
        return Collections.unmodifiableMap(result);
    }

    /** The exact carried floor that a Home deposit must leave untouched. */
    public static Map<String, Integer> protectedCounts(Map<String, Integer> baseline) {
        Map<String, Integer> floor = normalized(baseline, "baseline");
        if (!USEFUL_OUTPUT_SET.containsAll(floor.keySet())) {
            throw new IllegalArgumentException("baseline contains a non-farm item");
        }
        return floor;
    }

    private static Map<String, Integer> normalized(
            Map<String, Integer> source,
            String label) {
        Objects.requireNonNull(source, label);
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        source.forEach((raw, count) -> {
            String item = Objects.requireNonNullElse(raw, "").trim()
                    .toLowerCase(Locale.ROOT);
            if (item.startsWith("minecraft:")) item = item.substring("minecraft:".length());
            if (!item.matches("[a-z0-9_]+") || count == null || count < 0) {
                throw new IllegalArgumentException(label + " contains an invalid item/count");
            }
            if (count > 0 && result.putIfAbsent(item, count) != null) {
                throw new IllegalArgumentException(label + " contains duplicate item " + item);
            }
        });
        return Collections.unmodifiableMap(result);
    }
}
