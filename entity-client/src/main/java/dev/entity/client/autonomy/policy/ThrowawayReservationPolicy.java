package dev.entity.client.autonomy.policy;

import dev.entity.client.autonomy.policy.InventoryReservationLedger.Purpose;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Converts count-aware mission reservations into Baritone's coarser item-type throwaway fence.
 *
 * <p>Baritone cannot reserve only part of a stack: an item type is either an acceptable
 * throwaway or it is not. The safe projection is therefore deliberately conservative and
 * protects the whole type whenever any non-expendable reservation exists for it. Traversal
 * stock remains available because {@link Purpose#BUILDING_BLOCKS} is intentionally omitted.</p>
 */
public final class ThrowawayReservationPolicy {
    private static final Set<Purpose> NON_EXPENDABLE_PURPOSES = Collections.unmodifiableSet(
            EnumSet.of(
                    Purpose.MISSION_CARGO,
                    Purpose.PLAN_COMMITMENT,
                    Purpose.FIELD_KIT,
                    Purpose.TOOL,
                    Purpose.FUEL,
                    Purpose.EMERGENCY_TECHNIQUE,
                    Purpose.PERSONAL_RATION));

    private ThrowawayReservationPolicy() {
    }

    /** Returns every reserved item type that Baritone must not place as route scaffolding. */
    public static Set<String> protectedReservationItems(InventoryReservationLedger reservations) {
        Objects.requireNonNull(reservations, "reservations");
        return Set.copyOf(reservations.snapshot(NON_EXPENDABLE_PURPOSES).keySet());
    }

    /**
     * Applies mission-wide reservations plus operation-local protection to one baseline list.
     * Baseline order is retained because Baritone may use it as a placement preference.
     */
    public static Decision decide(
            Collection<String> baselineAcceptableItems,
            Collection<String> reservedItems,
            Collection<String> operationProtectedItems) {
        Objects.requireNonNull(baselineAcceptableItems, "baselineAcceptableItems");
        LinkedHashSet<String> protectedItems = new LinkedHashSet<>(normalized(reservedItems));
        protectedItems.addAll(normalized(operationProtectedItems));

        ArrayList<String> acceptable = new ArrayList<>();
        for (String raw : baselineAcceptableItems) {
            String item = InventoryReservationLedger.normalizeItem(raw);
            if (!protectedItems.contains(item)) acceptable.add(item);
        }
        return new Decision(acceptable, protectedItems);
    }

    /**
     * Allows a placement-capable route only when one currently carried item can actually satisfy
     * Baritone's fenced throwaway list. Baritone's path cost can otherwise admit an ascend/bridge
     * movement with {@code allowPlace=true} even though every carried building block is reserved;
     * execution then waits forever for a support block it is forbidden to select.
     */
    public static boolean canPlace(
            boolean requested,
            Collection<String> acceptableItems,
            Collection<String> carriedItems) {
        Objects.requireNonNull(acceptableItems, "acceptableItems");
        Objects.requireNonNull(carriedItems, "carriedItems");
        if (!requested) return false;

        Set<String> acceptable = normalized(acceptableItems);
        if (acceptable.isEmpty()) return false;
        for (String carried : carriedItems) {
            if (carried == null || carried.isBlank()) continue;
            if (acceptable.contains(InventoryReservationLedger.normalizeItem(carried))) {
                return true;
            }
        }
        return false;
    }

    /** Canonicalizes IDs at the controller boundary so namespaced items remain distinct. */
    public static Set<String> normalized(Collection<String> items) {
        Objects.requireNonNull(items, "items");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : items) {
            if (item == null || item.isBlank()) continue;
            normalized.add(InventoryReservationLedger.normalizeItem(item));
        }
        return Set.copyOf(normalized);
    }

    public record Decision(List<String> acceptableItems, Set<String> protectedItems) {
        public Decision {
            acceptableItems = List.copyOf(acceptableItems);
            protectedItems = Set.copyOf(protectedItems);
        }
    }
}
