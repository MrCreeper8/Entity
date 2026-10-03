package dev.entity.client.autonomy.policy;

import dev.entity.client.autonomy.policy.InventoryReservationLedger.Purpose;
import dev.entity.client.autonomy.policy.ResourcePlanner.Action;
import dev.entity.client.autonomy.policy.ResourcePlanner.ActionKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Converts the planner's complete projected action queue into inventory
 * ownership. The executor only runs one leaf at a time, but later actions in
 * that queue still own their inputs: raw meat belongs to the future smelt,
 * coal belongs to its fuel slot, and a field workstation is not spare stock.
 */
public final class PlanCommitmentPolicy {
    public record Commitment(
            String key,
            String item,
            int count,
            Purpose purpose,
            String reason) {
        public Commitment {
            key = requireText(key, "key");
            item = InventoryReservationLedger.normalizeItem(item);
            if (count <= 0) throw new IllegalArgumentException("count must be positive");
            purpose = Objects.requireNonNull(purpose, "purpose");
            reason = requireText(reason, "reason");
        }
    }

    private PlanCommitmentPolicy() {
    }

    /**
     * Returns one maximum requirement per item/purpose. Projected actions
     * describe the same dependency at several levels (hunt beef, then smelt
     * beef); summing those views would double-own one stack.
     */
    public static List<Commitment> derive(List<Action> projectedActions) {
        Objects.requireNonNull(projectedActions, "projectedActions");
        LinkedHashMap<Key, Need> needs = new LinkedHashMap<>();
        for (Action action : projectedActions) {
            Objects.requireNonNull(action, "action");
            Map<String, String> parameters = action.parameters();
            if (action.kind() == ActionKind.CRAFT || action.kind() == ActionKind.SMELT) {
                parseCounts(parameters.get("ingredients")).forEach((item, count) ->
                        mergeMaximum(needs, item, count, Purpose.PLAN_COMMITMENT,
                                "ingredient for projected " + action.target()));
                String workstation = parameters.getOrDefault("workstation", "").trim();
                if (!workstation.isBlank()) {
                    mergeMaximum(needs, workstation, 1, Purpose.FIELD_KIT,
                            "workstation for projected " + action.target());
                }
            }
            if (action.kind() == ActionKind.SMELT) {
                String fuel = parameters.getOrDefault("fuelItem", "coal");
                int count = positiveInteger(parameters.get("fuelCount"), 1);
                mergeMaximum(needs, fuel, count, Purpose.FUEL,
                        "fuel for projected " + action.target());
            }
            if (action.kind() == ActionKind.HUNT || action.kind() == ActionKind.MINE) {
                int expected = positiveInteger(
                        parameters.get("expectedMinimumItems"), action.count());
                mergeMaximum(needs, action.target(), expected, Purpose.PLAN_COMMITMENT,
                        "projected " + action.kind().name().toLowerCase(Locale.ROOT)
                                + " output for the active plan");
            }
            if (action.kind() == ActionKind.EQUIP) {
                mergeMaximum(needs, action.target(), 1, Purpose.TOOL,
                        "equipment selected by the active plan");
            }
        }

        ArrayList<Commitment> result = new ArrayList<>(needs.size());
        needs.forEach((key, need) -> result.add(new Commitment(
                key.purpose().name().toLowerCase(Locale.ROOT) + ':'
                        + key.item().replace(':', '_'),
                key.item(), need.count(), key.purpose(), need.reason())));
        return Collections.unmodifiableList(result);
    }

    private static void mergeMaximum(
            Map<Key, Need> needs,
            String rawItem,
            int count,
            Purpose purpose,
            String reason) {
        if (count <= 0 || rawItem == null || rawItem.isBlank()) return;
        Key key = new Key(InventoryReservationLedger.normalizeItem(rawItem), purpose);
        Need existing = needs.get(key);
        if (existing == null || count > existing.count()) {
            needs.put(key, new Need(count, reason));
        }
    }

    private static Map<String, Integer> parseCounts(String encoded) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return result;
        for (String raw : encoded.split(",")) {
            String[] pair = raw.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank()) continue;
            int count = positiveInteger(pair[1], 0);
            if (count > 0) result.merge(pair[0].trim(), count, Math::addExact);
        }
        return result;
    }

    private static int positiveInteger(String encoded, int fallback) {
        if (encoded == null || encoded.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(encoded.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }

    private record Key(String item, Purpose purpose) {
    }

    private record Need(int count, String reason) {
    }
}
