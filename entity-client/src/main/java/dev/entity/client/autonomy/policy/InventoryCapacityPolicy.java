package dev.entity.client.autonomy.policy;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Receiving room is part of the current mission, not a new automatic Tidy job. */
public final class InventoryCapacityPolicy {
    private InventoryCapacityPolicy() { }

    public static boolean hasReceivingRoom(int emptyMainSlots, int requiredEmptySlots) {
        if (requiredEmptySlots < 1 || requiredEmptySlots > 36)
            throw new IllegalArgumentException("Invalid receiving-room slot budget");
        return emptyMainSlots >= requiredEmptySlots;
    }

    public record Candidates(List<PersonalSuppliesPolicy.Selection> junk,
                             Map<String, Integer> depositable,
                             Map<String, Integer> protectedCounts) { }

    public static Candidates select(List<PersonalSuppliesPolicy.StackFact> stacks,
            Collection<InventoryReservationLedger.Reservation> claims,
            Set<String> junk, Set<String> keep, boolean fuelNeeded) {
        var allocation = PersonalSuppliesPolicy.allocateCleanup(stacks, List.of(), claims);
        var slices = PersonalSuppliesPolicy.previewCleanup(allocation, junk, keep, fuelNeeded, false);
        var drops = new java.util.ArrayList<PersonalSuppliesPolicy.Selection>();
        Map<String, Integer> stored = new LinkedHashMap<>(), retained = new LinkedHashMap<>();
        for (var slice : slices) {
            var selection = slice.selection();
            var stack = selection.stack();
            if (slice.action() == PersonalSuppliesPolicy.CleanupAction.KEEP) {
                retained.merge(stack.item(), selection.count(), Math::addExact);
            } else {
                // Only an entirely disposable stack frees a physical slot. Never throw
                // part of a ration/claim merely because the inventory is full.
                if (slice.action() == PersonalSuppliesPolicy.CleanupAction.DISCARD_CANDIDATE
                        && selection.count() == stack.count()) drops.add(selection);
                stored.merge(stack.item(), selection.count(), Math::addExact);
            }
        }
        return new Candidates(List.copyOf(drops), Map.copyOf(stored), Map.copyOf(retained));
    }
}
