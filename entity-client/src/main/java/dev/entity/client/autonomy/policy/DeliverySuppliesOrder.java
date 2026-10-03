package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/** Outbound-only whole-order allocation. It never changes the planner's physical inventory. */
public final class DeliverySuppliesOrder {
    private DeliverySuppliesOrder() { }
    public static final String WORKING_KIT_KEY = "deliveryWorkingKitV1";

    public static final class WorkingKitUnavailableException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public WorkingKitUnavailableException(String message) { super(message); }
    }

    /** Admission only: physically equipped kit is not silently exchanged for a carried spare. */
    public static PersonalSuppliesPolicy.Allocation initialAllocation(List<PersonalSuppliesPolicy.StackFact> inventory,
            java.util.Collection<InventoryReservationLedger.Reservation> reservations, Set<String> outgoingIds) {
        Set<String> equipped = inventory.stream().filter(stack -> stack.equipped() && !stack.capabilities().isEmpty())
                .map(PersonalSuppliesPolicy.StackFact::stackId).collect(java.util.stream.Collectors.toSet());
        return PersonalSuppliesPolicy.allocate(inventory, HomeStockPolicy.defaults(), reservations, outgoingIds, equipped);
    }

    /** Existing personal equipment at order admission, not a permanent inventory asset ID. */
    public record WorkingStack(String originalSlot, String item, String componentsWithoutWear,
                               int remainingDurability, int count) {
        public WorkingStack {
            if (originalSlot == null || !originalSlot.matches("player:(?:[0-9]|[1-3][0-9]|40)")
                    || componentsWithoutWear == null || componentsWithoutWear.isBlank()
                    || remainingDurability <= 0 || remainingDurability == Integer.MAX_VALUE || count < 1 || count > 64)
                throw new IllegalArgumentException("invalid delivery working-kit binding");
            item = InventoryReservationLedger.normalizeItem(item);
        }
    }

    public static List<WorkingStack> captureWorkingKit(PersonalSuppliesPolicy.Allocation allocation,
                                                       Map<String, String> componentsWithoutWear) {
        List<WorkingStack> result = new ArrayList<>();
        for (var allocated : allocation.stacks()) {
            var stack = allocated.stack();
            if (allocated.personalCount() == 0 || stack.capabilities().isEmpty()
                    || stack.remainingDurability() == Integer.MAX_VALUE) continue;
            result.add(new WorkingStack(stack.stackId(), stack.item(), componentsWithoutWear.get(stack.stackId()),
                    stack.remainingDurability(), allocated.personalCount()));
        }
        return List.copyOf(result);
    }

    /**
     * Slot moves and decreasing durability preserve the pre-order share. New pristine
     * output cannot match a worn original. Missing/broken originals use ordinary kit
     * fallback; exact equivalent duplicates are interchangeable, unlike different wear.
     */
    public static Set<String> resolveWorkingKit(List<WorkingStack> bindings,
            List<PersonalSuppliesPolicy.StackFact> inventory, Map<String, String> componentsWithoutWear,
            List<PersonalSuppliesPolicy.Request> requests) {
        Set<String> result = new LinkedHashSet<>();
        Map<String, Integer> assigned = new LinkedHashMap<>();
        for (WorkingStack binding : bindings) {
            if (requests.stream().noneMatch(request -> matches(request.item(), binding.item()))) continue;
            var candidates = inventory.stream().filter(stack -> stack.item().equals(binding.item())
                            && binding.componentsWithoutWear().equals(componentsWithoutWear.get(stack.stackId()))
                            && stack.usableDurability() > 0
                            && stack.remainingDurability() <= binding.remainingDurability()
                            && stack.count() > assigned.getOrDefault(stack.stackId(), 0))
                    .sorted(Comparator.comparing((PersonalSuppliesPolicy.StackFact stack) -> !stack.stackId().equals(binding.originalSlot()))
                            .thenComparing(PersonalSuppliesPolicy.StackFact::stackId)).toList();
            if (candidates.isEmpty()) continue; // Lost/exhausted kit must not pin a mission forever.
            var unchangedWear = candidates.stream()
                    .filter(stack -> stack.remainingDurability() == binding.remainingDurability()).toList();
            if (!unchangedWear.isEmpty()) candidates = unchangedWear;
            if (candidates.stream().map(PersonalSuppliesPolicy.StackFact::componentsKey).distinct().count() > 1)
                throw new IllegalArgumentException("working kit has multiple non-equivalent wear matches; cannot safely choose delivery cargo");
            int left = binding.count();
            for (var stack : candidates) {
                int take = Math.min(left, stack.count() - assigned.getOrDefault(stack.stackId(), 0));
                if (take > 0) {
                    assigned.merge(stack.stackId(), take, Math::addExact);
                    result.add(stack.stackId());
                    left -= take;
                }
                if (left == 0) break;
            }
        }
        return Set.copyOf(result);
    }

    /** Compact bounded checkpoint owned by the existing durable delivery root. */
    public static String encodeWorkingKit(List<WorkingStack> bindings) {
        if (bindings.size() > 41) throw new IllegalArgumentException("too many working-kit bindings");
        StringBuilder encoded = new StringBuilder("1");
        for (WorkingStack binding : bindings) encoded.append('\n').append(binding.originalSlot()).append('|')
                .append(binding.item()).append('|').append(binding.remainingDurability()).append('|')
                .append(binding.count()).append('|').append(Base64.getEncoder().encodeToString(
                        binding.componentsWithoutWear().getBytes(StandardCharsets.UTF_8)));
        if (encoded.length() > 262_144) throw new IllegalArgumentException("working-kit binding too large");
        return encoded.toString();
    }

    public static List<WorkingStack> decodeWorkingKit(String encoded) {
        if (encoded == null || encoded.length() > 262_144) throw new IllegalArgumentException("invalid working-kit checkpoint");
        String[] lines = encoded.split("\\n", -1);
        if (!lines[0].equals("1") || lines.length > 42) throw new IllegalArgumentException("invalid working-kit schema");
        List<WorkingStack> result = new ArrayList<>();
        Set<String> slots = new LinkedHashSet<>();
        for (int index = 1; index < lines.length; index++) {
            String[] parts = lines[index].split("\\|", -1);
            if (parts.length != 5 || !slots.add(parts[0])) throw new IllegalArgumentException("invalid working-kit entry");
            result.add(new WorkingStack(parts[0], parts[1], new String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
        }
        return List.copyOf(result);
    }

    public static Set<String> outgoingCargoIds(String missionId,
            java.util.Collection<InventoryReservationLedger.Reservation> reservations) {
        Set<String> ids = new LinkedHashSet<>();
        String prefix = missionId + ":cargo";
        for (var reservation : reservations) {
            if (reservation.purpose() == InventoryReservationLedger.Purpose.MISSION_CARGO
                    && (reservation.id().equals(prefix) || reservation.id().startsWith(prefix + ":")))
                ids.add(reservation.id());
        }
        return Set.copyOf(ids);
    }

    /** Partition the already atomic plan, exact lines first, so food cannot spend later bread cargo. */
    public static List<PersonalSuppliesPolicy.Selection> selectionsForLine(
            PersonalSuppliesPolicy.DeliveryPlan plan, int lineIndex) {
        if (!plan.ready()) return List.of();
        if (lineIndex < 0 || lineIndex >= plan.lines().size()) throw new IllegalArgumentException("line index");
        int[] left = plan.selections().stream().mapToInt(PersonalSuppliesPolicy.Selection::count).toArray();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < plan.lines().size(); i++) order.add(i);
        order.sort(Comparator.comparing((Integer i) -> plan.lines().get(i).request().all())
                .thenComparing(i -> family(plan.lines().get(i).request().item())).thenComparingInt(i -> i));
        for (int index : order) {
            var line = plan.lines().get(index);
            int needed = line.selected();
            List<PersonalSuppliesPolicy.Selection> selected = new ArrayList<>();
            for (int slot = 0; slot < left.length && needed > 0; slot++) {
                var stack = plan.selections().get(slot).stack();
                if (!matches(line.request().item(), stack.item())) continue;
                int take = Math.min(needed, left[slot]);
                if (take > 0) selected.add(new PersonalSuppliesPolicy.Selection(stack, take));
                left[slot] -= take;
                needed -= take;
            }
            if (needed != 0) throw new IllegalStateException("atomic order selection lost a required item");
            if (index == lineIndex) return List.copyOf(selected);
        }
        throw new IllegalStateException("missing order line");
    }

    public static boolean matches(String requested, String item) {
        String normalized = InventoryReservationLedger.normalizeItem(requested);
        String actual = InventoryReservationLedger.normalizeItem(item);
        return normalized.equals(actual)
                || FoodFamilyPolicy.isFamilyRequest(normalized) && FoodFamilyPolicy.isReadyFood(actual)
                || WoodLogFamilyPolicy.isFamilyRequest(normalized) && WoodLogFamilyPolicy.members().stream()
                        .anyMatch(member -> InventoryReservationLedger.normalizeItem(member).equals(actual));
    }

    public static boolean family(String item) {
        return FoodFamilyPolicy.isFamilyRequest(item) || WoodLogFamilyPolicy.isFamilyRequest(item);
    }

    public static String explanation(PersonalSuppliesPolicy.DeliveryPlan plan) {
        return plan.lines().stream().map(line -> line.request().item() + ": carried " + line.carried()
                + ", retained " + line.retained() + ", other cargo " + line.transactionLocked()
                + ", available " + line.available() + ", requested " + line.request().count())
                .collect(java.util.stream.Collectors.joining("; "));
    }
}
