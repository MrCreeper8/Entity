package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.entity.client.autonomy.policy.InventoryReservationLedger.Purpose;
import dev.entity.client.autonomy.policy.InventoryReservationLedger.Reservation;

/**
 * Existing-only ration allocation. A ration may keep carried food away from
 * crafting/planning, but it never creates an extra acquisition objective.
 */
public final class MissionRationPolicy {
    public enum Phase {
        ACTIVE_WORK,
        DELIVERY,
        NEAR_HANDOFF,
        IDLE
    }

    private MissionRationPolicy() {
    }

    /**
     * A still-acquiring absolute food objective supplies its own body's upkeep.
     * Partition actual carried food before assigning the remainder to that same
     * objective. This never changes the durable requested quantity: its root
     * must replace consumed items and prove the physical quota after eating.
     * Callers must exclude delivery/batch roots and unsettled inventory custody.
     */
    public static List<Reservation> partitionFoodAcquisition(
            String missionId, String owner, int hungerLevel,
            Map<String, Integer> carried, Map<String, Integer> nutrition,
            Map<String, Integer> otherOwners, List<Reservation> owned) {
        if (hungerLevel >= 19) return List.copyOf(owned);
        String selected = "";
        int selectedCount = 0, selectedNutrition = 0;
        for (var entry : carried.entrySet()) {
            String item = InventoryReservationLedger.normalizeItem(entry.getKey());
            // Do not turn risky raw chicken into routine automatic food. The
            // body retains its existing critical-risk and emergency policies.
            if (!FoodFamilyPolicy.isReadyFood(item)
                    && !List.of("minecraft:beef", "minecraft:porkchop", "minecraft:mutton").contains(item)) continue;
            int value = nutrition.getOrDefault(item, 0);
            if (value <= 0) continue;
            int count = reserveExisting(hungerLevel, value, entry.getValue(),
                    otherOwners.getOrDefault(item, 0), Phase.ACTIVE_WORK, Double.NaN);
            if (count > 0 && (value > selectedNutrition
                    || (value == selectedNutrition && item.compareTo(selected) < 0))) {
                selected = item;
                selectedCount = count;
                selectedNutrition = value;
            }
        }
        if (selectedCount == 0) return List.copyOf(owned);

        int objectiveShare = Math.max(0, carried.getOrDefault(selected, 0)
                - otherOwners.getOrDefault(selected, 0) - selectedCount);
        var result = new ArrayList<Reservation>();
        for (var claim : owned) {
            if (!claim.item().equals(selected)
                    || (claim.purpose() != Purpose.MISSION_CARGO && claim.purpose() != Purpose.PLAN_COMMITMENT)) {
                result.add(claim);
                continue;
            }
            int count = Math.min(objectiveShare, claim.count());
            if (count > 0) result.add(new Reservation(claim.id(), claim.owner(), claim.item(), count,
                    claim.purpose(), claim.reason()));
            objectiveShare -= count;
        }
        result.add(new Reservation(missionId + ":ration", owner, selected, selectedCount,
                Purpose.PERSONAL_RATION, "same food acquisition supplies recovery; consumed share must be replenished"));
        return List.copyOf(result);
    }

    /**
     * Allocates food already surplus to cargo and projected ingredients.
     * Therefore a 16-steak delivery with exactly 16 steaks always reserves
     * zero rations and can proceed directly to handoff.
     */
    public static int reserveExisting(
            int hungerLevel,
            int nutrition,
            int carriedCount,
            int planOwnedCount,
            Phase phase,
            double estimatedDistance) {
        if (hungerLevel < 0 || hungerLevel > 20) {
            throw new IllegalArgumentException("hungerLevel must be between 0 and 20");
        }
        if (nutrition <= 0 || carriedCount < 0 || planOwnedCount < 0) {
            throw new IllegalArgumentException("nutrition must be positive and counts non-negative");
        }
        if (phase == null) throw new NullPointerException("phase");
        int surplus = Math.max(0, carriedCount - planOwnedCount);
        if (surplus == 0) return 0;

        int recoveryDeficit = Math.max(0, 18 - hungerLevel);
        int recoveryItems = recoveryDeficit == 0
                ? 0 : (recoveryDeficit + nutrition - 1) / nutrition;
        int travelItems = switch (phase) {
            case NEAR_HANDOFF, IDLE -> 0;
            case DELIVERY -> !Double.isFinite(estimatedDistance) || estimatedDistance > 96.0 ? 1 : 0;
            case ACTIVE_WORK -> !Double.isFinite(estimatedDistance) || estimatedDistance > 128.0 ? 1 : 0;
        };
        return Math.min(surplus, Math.addExact(recoveryItems, travelItems));
    }
}
