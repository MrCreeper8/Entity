package dev.entity.client.autonomy.policy;

import java.util.List;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives real-inventory reservations for supported emergency techniques.
 *
 * <p>This policy never creates an acquisition objective and never claims unavailable
 * equipment. It reserves one already-carried water bucket so ordinary planning, Home
 * storage, and traversal scaffolding cannot spend the only item that makes
 * {@code fall.water_bucket_clutch} available.</p>
 */
public final class EmergencyTechniqueReservationPolicy {
    public static final String OWNER = "emergency-techniques";
    public static final String WATER_CLUTCH_RESERVATION_ID = "technique:water-bucket-clutch";
    public static final String WATER_BUCKET = "minecraft:water_bucket";

    private EmergencyTechniqueReservationPolicy() {
    }

    /**
     * An absolute filled-water goal already includes carried personal water. Count
     * that exact clutch reservation toward the final goal, not as an additional
     * deficit. The compiler's final-goal floor retains it; outbound allocation and
     * every other planning reservation remain unchanged.
     */
    public static ResourcePlanner.InventoryView includeRetainedGoalWater(
            ResourcePlanner.InventoryView planning, Map<String, Integer> physical,
            Collection<AcquisitionRequest.ItemGoal> goals, InventoryReservationLedger ledger) {
        if (goals.stream().noneMatch(goal -> WATER_BUCKET.equals(
                InventoryReservationLedger.normalizeItem(goal.item())))) return planning;
        Map<String, Integer> eligible = ledger.spendableCountsExceptReservations(physical,
                Set.of(InventoryReservationLedger.Purpose.PERSONAL_RATION,
                        InventoryReservationLedger.Purpose.EMERGENCY_TECHNIQUE),
                Set.of(WATER_CLUTCH_RESERVATION_ID));
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>(planning.itemCounts());
        counts.keySet().removeIf(item -> WATER_BUCKET.equals(InventoryReservationLedger.normalizeItem(item)));
        int water = eligible.getOrDefault(WATER_BUCKET, 0);
        if (water > 0) counts.put(WATER_BUCKET, water);
        return new ResourcePlanner.InventoryView(counts, planning.equippedItems(),
                planning.locallyAvailableItems(), planning.availableStations());
    }

    /** Returns either one physical water-bucket reservation or no reservation. */
    public static List<InventoryReservationLedger.Reservation> derive(
            Map<String, Integer> carriedInventory) {
        Objects.requireNonNull(carriedInventory, "carriedInventory");
        int carriedWaterBuckets = 0;
        for (Map.Entry<String, Integer> entry : carriedInventory.entrySet()) {
            String item = InventoryReservationLedger.normalizeItem(entry.getKey());
            Integer count = Objects.requireNonNull(entry.getValue(), "inventory count");
            if (count < 0) throw new IllegalArgumentException("inventory count cannot be negative");
            if (item.equals(WATER_BUCKET)) {
                carriedWaterBuckets = Math.addExact(carriedWaterBuckets, count);
            }
        }
        if (carriedWaterBuckets == 0) return List.of();
        return List.of(new InventoryReservationLedger.Reservation(
                WATER_CLUTCH_RESERVATION_ID,
                OWNER,
                WATER_BUCKET,
                1,
                InventoryReservationLedger.Purpose.EMERGENCY_TECHNIQUE,
                "retain one carried water bucket for fall.water_bucket_clutch"));
    }
}
