package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Typed input for compiling one plan-wide acquisition objective.
 *
 * <p>Goals retain caller order. The planner canonicalizes and merges aliases,
 * but this model deliberately does not know Minecraft registries. Remaining
 * pickaxe durability is optional: an omitted carried tool is treated as
 * undamaged, while an explicitly supplied zero is treated as exhausted.
 * Unavailable harvest sources are observed failures retained by this acquisition;
 * their absence never forbids exploration or extraction of unobserved ores.</p>
 */
public record AcquisitionRequest(
        List<ItemGoal> goals,
        ResourcePlanner.InventoryView inventory,
        Map<String, Integer> usablePickaxeDurability,
        Set<String> unavailableHarvestSources) {

    public AcquisitionRequest {
        Objects.requireNonNull(goals, "goals");
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("at least one acquisition goal is required");
        }
        ArrayList<ItemGoal> goalCopy = new ArrayList<>(goals.size());
        for (ItemGoal goal : goals) {
            goalCopy.add(Objects.requireNonNull(goal, "goal"));
        }
        goals = List.copyOf(goalCopy);
        inventory = Objects.requireNonNull(inventory, "inventory");
        usablePickaxeDurability = immutableCounts(
                usablePickaxeDurability, "usablePickaxeDurability");
        unavailableHarvestSources = Set.copyOf(Objects.requireNonNull(unavailableHarvestSources));
    }

    public AcquisitionRequest(List<ItemGoal> goals, ResourcePlanner.InventoryView inventory,
            Map<String, Integer> usablePickaxeDurability) {
        this(goals, inventory, usablePickaxeDurability, Set.of());
    }

    public AcquisitionRequest(
            List<ItemGoal> goals,
            ResourcePlanner.InventoryView inventory) {
        this(goals, inventory, Map.of());
    }

    public static AcquisitionRequest of(ItemGoal... goals) {
        return new AcquisitionRequest(List.of(goals), ResourcePlanner.InventoryView.empty());
    }

    /** Ordered, namespace-tolerant item/count objective. */
    public record ItemGoal(String item, int count) {
        public ItemGoal {
            item = requireText(item, "item");
            if (count <= 0) throw new IllegalArgumentException("goal count must be positive");
        }
    }

    private static Map<String, Integer> immutableCounts(
            Map<String, Integer> source,
            String field) {
        Objects.requireNonNull(source, field);
        LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
        source.forEach((item, count) -> {
            String key = requireText(item, field + " item");
            if (count == null || count < 0) {
                throw new IllegalArgumentException(field + " contains a negative/null count for " + key);
            }
            copy.put(key, count);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }
}
