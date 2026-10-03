package dev.entity.client.autonomy.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable result of one complete multi-item acquisition compilation. */
public record AcquisitionPlan(
        List<AcquisitionRequest.ItemGoal> goals,
        List<ResourcePlanner.Action> actions,
        List<ResourcePlanner.TraceStep> trace,
        Map<String, Integer> initialInventory,
        Map<String, Integer> projectedInventory,
        ResourceTotals totals) {

    public AcquisitionPlan {
        goals = List.copyOf(Objects.requireNonNull(goals, "goals"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
        initialInventory = immutableNonNegative(initialInventory, "initialInventory");
        projectedInventory = immutableNonNegative(projectedInventory, "projectedInventory");
        totals = Objects.requireNonNull(totals, "totals");
        for (AcquisitionRequest.ItemGoal goal : goals) {
            if (projectedInventory.getOrDefault(goal.item(), 0) < goal.count()) {
                throw new IllegalArgumentException(
                        "projected inventory does not satisfy " + goal.item() + "=" + goal.count());
            }
        }
    }

    public boolean complete() {
        return actions.isEmpty();
    }

    public Optional<ResourcePlanner.Action> firstAction() {
        return actions.isEmpty() ? Optional.empty() : Optional.of(actions.getFirst());
    }

    /**
     * Auditable plan-wide accounting. Produced and consumed are gross totals;
     * netInventoryChange may contain negative values. Mining counts are source
     * blocks, workstation uses are production operations, and tool durability
     * is the exact number of planned block breaks assigned to each pickaxe.
     */
    public record ResourceTotals(
            Map<String, Integer> requested,
            Map<String, Integer> produced,
            Map<String, Integer> consumed,
            Map<String, Integer> netInventoryChange,
            Map<String, Integer> minedBlocks,
            Map<String, Integer> workstationUses,
            Map<String, Integer> toolDurabilityUse) {
        public ResourceTotals {
            requested = immutableNonNegative(requested, "requested");
            produced = immutableNonNegative(produced, "produced");
            consumed = immutableNonNegative(consumed, "consumed");
            netInventoryChange = immutableSigned(netInventoryChange, "netInventoryChange");
            minedBlocks = immutableNonNegative(minedBlocks, "minedBlocks");
            workstationUses = immutableNonNegative(workstationUses, "workstationUses");
            toolDurabilityUse = immutableNonNegative(toolDurabilityUse, "toolDurabilityUse");
        }
    }

    private static Map<String, Integer> immutableNonNegative(
            Map<String, Integer> source,
            String field) {
        Objects.requireNonNull(source, field);
        LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String checked = requireText(key, field + " key");
            if (value == null || value < 0) {
                throw new IllegalArgumentException(field + " contains a negative/null value for " + checked);
            }
            if (value > 0) copy.put(checked, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Integer> immutableSigned(
            Map<String, Integer> source,
            String field) {
        Objects.requireNonNull(source, field);
        LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String checked = requireText(key, field + " key");
            if (value == null) throw new IllegalArgumentException(field + " contains null for " + checked);
            if (value != 0) copy.put(checked, value);
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
