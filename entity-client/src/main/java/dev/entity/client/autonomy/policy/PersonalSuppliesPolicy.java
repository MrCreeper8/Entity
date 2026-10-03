package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One outbound allocation, not another inventory owner or a mining/pickup restriction.
 * Call before preparing a delivery nonce; an issued transaction reconciles its original
 * selection instead of running this policy again. Input inventory is never changed.
 */
public final class PersonalSuppliesPolicy {
    private PersonalSuppliesPolicy() { }
    public static final int CLEANUP_POLICY_VERSION = 2;
    public static final Set<String> DEFAULT_JUNK_ITEMS = Set.of("minecraft:spider_eye", "minecraft:rotten_flesh",
            "minecraft:poisonous_potato", "minecraft:leaf_litter");
    private static final List<String> ARMOR_CATEGORIES =
            List.of("armor_head", "armor_chest", "armor_legs", "armor_feet");
    private static final List<String> TOOL_CATEGORIES = List.of("pickaxe", "axe", "weapon", "shovel", "hoe");

    /** Measured job capability: harvest level (or zero), and speed/damage/effectiveness. */
    public record Capability(int level, double effectiveness) {
        public Capability {
            if (level < 0 || !Double.isFinite(effectiveness) || effectiveness <= 0) {
                throw new IllegalArgumentException("invalid measured tool capability");
            }
        }
        boolean covers(Capability other) {
            return level >= other.level && effectiveness >= other.effectiveness;
        }
    }

    /**
     * A physical stack, not an item-ID aggregate. componentsKey is a lossless/canonical
     * component identity supplied by the adapter, NOT merely a hash. stackId identifies
     * its inventory/container slot. Non-damageable items use Integer.MAX_VALUE durability.
     * Capability keys are measured job IDs (pickaxe, axe, weapon, shield, shovel, hoe).
     */
    public record StackFact(String stackId, String item, int count, String componentsKey,
                            int remainingDurability, Map<String, Capability> capabilities,
                            boolean custom, boolean equipped, boolean fuelCompatible, int foodNutrition) {
        public StackFact(String stackId, String item, int count, String componentsKey,
                         int remainingDurability, Map<String, Capability> capabilities,
                         boolean custom, boolean equipped, boolean fuelCompatible) {
            this(stackId, item, count, componentsKey, remainingDurability, capabilities,
                    custom, equipped, fuelCompatible, 0);
        }
        public StackFact {
            stackId = text(stackId);
            item = itemId(item);
            componentsKey = Objects.requireNonNull(componentsKey, "componentsKey");
            if (count <= 0 || remainingDurability < 0 || foodNutrition < 0) {
                throw new IllegalArgumentException("invalid physical stack counts");
            }
            capabilities = Map.copyOf(capabilities);
        }
        public int usableDurability() {
            return MiningToolSegmentPolicy.usableWorkDurability(remainingDurability);
        }
    }

    /** Counts are disjoint and sum to the actual stack count. */
    public record StackAllocation(StackFact stack, int transactionCount, int personalCount,
                                  int availableCount, List<String> reasons) {
        public StackAllocation {
            Objects.requireNonNull(stack, "stack");
            reasons = List.copyOf(reasons);
            if (transactionCount < 0 || personalCount < 0 || availableCount < 0
                    || (long) transactionCount + personalCount + availableCount != stack.count) {
                throw new IllegalArgumentException("overlapping physical allocation");
            }
        }
        public int available(boolean useReserves) {
            return availableCount + (useReserves ? personalCount : 0);
        }
    }

    public record Category(String id, int desired, int retained, String acquisitionItem,
                           List<String> acceptedItems, String reason) {
        public Category {
            acceptedItems = List.copyOf(acceptedItems);
        }
        public int shortfall() { return Math.max(0, desired - retained); }
    }

    public record Allocation(List<StackAllocation> stacks, List<Category> categories) {
        public Allocation {
            stacks = List.copyOf(stacks);
            categories = List.copyOf(categories);
        }
        public Map<String, Integer> retainedCounts() {
            LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
            for (StackAllocation stack : stacks) {
                if (stack.personalCount > 0) result.merge(stack.stack.item, stack.personalCount, Math::addExact);
            }
            return immutable(result);
        }
        /** For outbound transfer planning only; never install this as a mining spend filter. */
        public Map<String, Integer> outboundProtectedCounts() {
            LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
            for (StackAllocation stack : stacks) {
                int count = stack.transactionCount + stack.personalCount;
                if (count > 0) result.merge(stack.stack.item, count, Math::addExact);
            }
            return immutable(result);
        }
    }

    /**
     * Only explicit reservation IDs belonging to THIS outgoing order may be exempted,
     * and only before its nonce is prepared. Other transaction cargo takes physical
     * precedence. Personal purposes overlap the disclosed Home floor by maximum,
     * not addition; claims within one purpose still count their distinct quantities.
     */
    public static Allocation allocate(List<StackFact> inventory,
                                      List<HomeStockPolicy.Target> profile,
                                      Collection<InventoryReservationLedger.Reservation> reservations,
                                      Set<String> outgoingReservationIds) {
        return allocate(inventory, profile, reservations, outgoingReservationIds, Set.of());
    }

    /** Delivery-root bindings take precedence only within the existing personal share. */
    public static Allocation allocate(List<StackFact> inventory,
                                      List<HomeStockPolicy.Target> profile,
                                      Collection<InventoryReservationLedger.Reservation> reservations,
                                      Set<String> outgoingReservationIds,
                                      Set<String> workingKitStackIds) {
        Objects.requireNonNull(outgoingReservationIds, "outgoingReservationIds");
        Set<String> working = Set.copyOf(workingKitStackIds);
        List<MutableStack> stacks = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (StackFact stack : inventory) {
            if (!ids.add(stack.stackId)) throw new IllegalArgumentException("duplicate physical stack identity");
            stacks.add(new MutableStack(stack));
        }
        stacks.sort(Comparator.comparing(stack -> stack.fact.stackId));
        List<Demand> demands = new ArrayList<>();
        Set<String> accepted = new LinkedHashSet<>();
        Set<String> categoryIds = new LinkedHashSet<>();
        for (HomeStockPolicy.Target target : profile) {
            if (!categoryIds.add(target.id())) throw new IllegalArgumentException("duplicate personal category");
            for (String item : target.acceptedItems()) {
                if (!accepted.add(itemId(item))) throw new IllegalArgumentException("overlapping personal categories");
            }
            demands.add(new Demand(target.id(), target.desiredCount(), itemId(target.acquisitionItem()),
                    target.acceptedItems().stream().map(PersonalSuppliesPolicy::itemId).toList(), target.reason()));
        }
        // Retain observed working armor, not a new requirement to acquire an armor set.
        // Worn pieces win over better spares: delivery must not silently undress the body.
        for (String id : ARMOR_CATEGORIES) {
            List<StackFact> armor = inventory.stream()
                    .filter(stack -> stack.capabilities.containsKey(id) && stack.remainingDurability > 0)
                    .sorted(Comparator.comparing((StackFact stack) -> !stack.equipped)
                            .thenComparing(StackFact::stackId)).toList();
            if (!armor.isEmpty() && categoryIds.add(id)) {
                demands.add(new Demand(id, 1, armor.getFirst().item,
                        armor.stream().map(StackFact::item).distinct().toList(),
                        "Retain one working " + id.substring(6) + " armor piece"));
            }
        }

        Map<InventoryReservationLedger.Purpose, Map<String, Integer>> personal =
                new EnumMap<>(InventoryReservationLedger.Purpose.class);
        for (InventoryReservationLedger.Reservation reservation : reservations) {
            if (reservation.purpose() == InventoryReservationLedger.Purpose.MISSION_CARGO
                    || reservation.purpose() == InventoryReservationLedger.Purpose.PLAN_COMMITMENT) {
                if (!outgoingReservationIds.contains(reservation.id())) {
                    int left = reservation.count();
                    for (MutableStack stack : stacks) {
                        if (!matches(reservation.item(), stack.fact.item)) continue;
                        int take = Math.min(left, stack.free());
                        stack.transaction += take;
                        if (take > 0) stack.reasons.add(reservation.reason());
                        left -= take;
                    }
                }
            } else {
                personal.computeIfAbsent(reservation.purpose(), ignored -> new LinkedHashMap<>())
                        .merge(itemId(reservation.item()), reservation.count(), Math::addExact);
            }
        }
        // Personal aliases describe shares of one category, never one floor per species.
        for (Map<String, Integer> claims : personal.values()) {
            Map<String, Integer> totals = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> claim : claims.entrySet()) {
                Demand demand = demands.stream().filter(candidate -> candidate.matchesItem(claim.getKey())
                                || stacks.stream().anyMatch(stack -> matches(claim.getKey(), stack.fact.item)
                                && stack.fact.capabilities.containsKey(candidate.id)))
                        .findFirst().orElse(null);
                if (demand == null) {
                    demand = new Demand("item:" + claim.getKey(), 0, claim.getKey(),
                            List.of(claim.getKey()), "Existing personal equipment/supply reservation");
                    demands.add(demand);
                }
                totals.merge(demand.id, claim.getValue(), Math::addExact);
            }
            for (Demand demand : demands) demand.desired = Math.max(demand.desired, totals.getOrDefault(demand.id, 0));
        }
        List<Category> categories = new ArrayList<>();
        for (Demand demand : demands) {
            List<MutableStack> candidates = stacks.stream().filter(stack -> demand.accepts(stack.fact))
                    .sorted(Comparator.comparing((MutableStack stack) -> !working.contains(stack.fact.stackId))
                            .thenComparing(demand.preference())).toList();
            int left = demand.desired;
            for (MutableStack stack : candidates) {
                int take = Math.min(left, stack.free());
                stack.personal += take;
                if (take > 0) stack.reasons.add(demand.reason);
                left -= take;
            }
            categories.add(new Category(demand.id, demand.desired, demand.desired - left,
                    demand.acquisitionItem, demand.acceptedItems, demand.reason));
            // A missing retained iron tier is still a deficit, but its last usable
            // stone/wood bootstrap must remain carried while that iron is acquired.
            if (left > 0 && TOOL_CATEGORIES.contains(demand.id)) {
                stacks.stream().filter(stack -> stack.free() > 0
                                && stack.fact.capabilities.containsKey(demand.id)
                                && stack.fact.usableDurability() > 0)
                        .sorted(demand.preference()).findFirst().ifPresent(stack -> {
                            stack.personal++;
                            stack.reasons.add("Retain usable bootstrap while restoring working tier");
                        });
            }
        }
        return new Allocation(stacks.stream().map(MutableStack::freeze).toList(), categories);
    }

    public static Allocation allocate(List<StackFact> inventory,
                                      Collection<InventoryReservationLedger.Reservation> reservations,
                                      Set<String> outgoingReservationIds) {
        return allocate(inventory, HomeStockPolicy.defaults(), reservations, outgoingReservationIds);
    }

    public record Request(String item, int count, boolean all) {
        public Request {
            item = itemId(item);
            if (count < 0 || (!all && count == 0) || (all && count != 0)) {
                throw new IllegalArgumentException("use a positive exact count or all with count zero");
            }
        }
        public static Request counted(String item, int count) { return new Request(item, count, false); }
        public static Request all(String item) { return new Request(item, 0, true); }
    }

    /** A necessary existing prerequisite, never an idle scheduler or a complete-kit gate. */
    public static Map<String, Integer> maintenanceGoal(String action, boolean woodWork,
            boolean alreadyMaintaining, Allocation allocation, Map<String, Integer> serviceable,
            Map<String, String> workingTools) {
        if (alreadyMaintaining || !Set.of("build", "mine", "root.legacy_mine", "root.gear").contains(action)) return Map.of();
        Category food = allocation.categories.stream().filter(category -> category.id.equals("food"))
                .findFirst().orElse(null);
        if (food != null && food.retained < HomeStockPolicy.MINIMUM_READY_FOOD_FLOOR) {
            int carried = allocation.stacks.stream().filter(stack -> matches("minecraft:food", stack.stack.item))
                    .mapToInt(stack -> stack.stack.count).sum();
            return Map.of("food", Math.addExact(carried, food.shortfall()));
        }
        if (action.equals("root.gear")) return Map.of(); // Gear already owns every kit/tool goal.
        List<String> required = action.equals("build") ? List.of("pickaxe", "axe")
                : List.of(woodWork ? "axe" : "pickaxe");
        for (HomeStockPolicy.Target target : HomeStockPolicy.retainedProfile(workingTools)) {
            // No historic working tool means normal action-specific bootstrap remains
            // authoritative. This must not equip a complete kit before the first action.
            if (!required.contains(target.id()) || !workingTools.containsKey(target.id())) continue;
            int carried = target.acceptedItems().stream().mapToInt(item -> serviceable.getOrDefault(item,
                    serviceable.getOrDefault("minecraft:" + item, 0))).sum();
            if (carried < target.desiredCount()) return Map.of(target.acquisitionItem(), target.desiredCount());
        }
        return Map.of();
    }

    /** Preserves actual parent cargo once while the child may spend newly acquired inputs. */
    public static Map<String, Integer> maintenanceSpendable(Map<String, Integer> observed,
            Map<String, Integer> parentProtected) {
        var normalized = new LinkedHashMap<String, Integer>();
        parentProtected.forEach((item, count) -> normalized.merge(itemId(item), count, Math::max));
        var result = new LinkedHashMap<String, Integer>();
        observed.forEach((item, count) -> {
            int free = Math.max(0, count - normalized.getOrDefault(itemId(item), 0));
            if (free > 0) result.put(item, free);
        });
        return immutable(result);
    }

    public record Selection(StackFact stack, int count) {
        public Selection {
            if (count < 1 || count > stack.count) throw new IllegalArgumentException("invalid exact selection");
        }
        /** Rejection rather than silently selecting a different same-ID stack. */
        public boolean stillMatches(StackFact current) {
            return current != null && stack.stackId.equals(current.stackId) && stack.item.equals(current.item)
                    && stack.count == current.count && stack.componentsKey.equals(current.componentsKey)
                    && stack.remainingDurability == current.remainingDurability
                    && stack.custom == current.custom && stack.equipped == current.equipped;
        }
    }

    public record Line(Request request, int carried, int transactionLocked, int retained,
                       int available, int selected, int shortfall) { }

    /**
     * Give is atomic: any counted shortage makes selections empty. Bring's selections
     * are also empty until all delivery AND relevant personal shortfalls are filled.
     * additionalGoals are inventory-goal deltas, not independent acquisition commands:
     * a family total includes its concrete subset goals. Convert together using Home's
     * absoluteAcquisitionGoals, never sum a food goal and its bread subset twice.
     */
    public record DeliveryPlan(boolean ready, List<Line> lines, List<Selection> selections,
                               Map<String, Integer> additionalGoals) {
        public DeliveryPlan {
            lines = List.copyOf(lines);
            selections = List.copyOf(selections);
            additionalGoals = immutable(additionalGoals);
        }
    }

    public static DeliveryPlan planGive(Allocation allocation, List<Request> requests, boolean useReserves) {
        return plan(allocation, requests, useReserves, false);
    }

    public static DeliveryPlan planBring(Allocation allocation, List<Request> requests, boolean useReserves) {
        if (requests.stream().anyMatch(Request::all)) throw new IllegalArgumentException("bring requires exact counts");
        return plan(allocation, requests, useReserves, true);
    }

    private static DeliveryPlan plan(Allocation allocation, List<Request> requests,
                                     boolean useReserves, boolean bring) {
        if (requests.isEmpty()) throw new IllegalArgumentException("empty delivery order");
        int[] available = allocation.stacks.stream().mapToInt(stack -> stack.available(useReserves)).toArray();
        int[] personal = allocation.stacks.stream().mapToInt(stack -> useReserves ? 0 : stack.personalCount).toArray();
        Line[] lines = new Line[requests.size()];
        int[] selected = new int[available.length];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) order.add(i);
        // Exact requirements first; then flexible families use what remains. An all
        // request cannot consume the stock promised to another counted order line.
        order.sort(Comparator.comparing((Integer i) -> requests.get(i).all)
                .thenComparing(i -> isFamily(requests.get(i).item)).thenComparingInt(i -> i));
        LinkedHashMap<String, Integer> goals = new LinkedHashMap<>();
        boolean ready = true;
        for (int i : order) {
            Request request = requests.get(i);
            releaseAlternativeSupplies(allocation, request, available, personal);
            int carried = 0, locked = 0, retained = 0, capacity = 0;
            for (int slot = 0; slot < available.length; slot++) {
                StackAllocation stack = allocation.stacks.get(slot);
                if (!matches(request.item, stack.stack.item)) continue;
                carried = Math.addExact(carried, stack.stack.count);
                locked = Math.addExact(locked, stack.transactionCount);
                retained = Math.addExact(retained, personal[slot]);
                capacity = Math.addExact(capacity, available[slot]);
            }
            int wanted = request.all ? capacity : request.count;
            int left = wanted;
            for (int slot = 0; slot < available.length; slot++) {
                if (!matches(request.item, allocation.stacks.get(slot).stack.item)) continue;
                int take = Math.min(left, available[slot]);
                available[slot] -= take;
                selected[slot] += take;
                left -= take;
            }
            lines[i] = new Line(request, carried, locked, retained, capacity, wanted - left, left);
            if (left > 0) {
                ready = false;
                if (bring) goals.merge(request.item, left, Math::addExact);
            }
        }
        if (bring) {
            for (Category category : allocation.categories) {
                List<Request> relevant = requests.stream().filter(request -> relevant(category, request.item, allocation))
                        .toList();
                if (relevant.isEmpty()) continue;
                int personalShortfall = useReserves ? 0 : category.shortfall();
                String family = relevant.stream().map(Request::item).filter(PersonalSuppliesPolicy::isFamily)
                        .findFirst().orElse(null);
                if (family != null) {
                    int total = personalShortfall;
                    for (Map.Entry<String, Integer> goal : goals.entrySet()) {
                        if (goal.getKey().equals(family) || matches(family, goal.getKey())) {
                            total = Math.addExact(total, goal.getValue());
                        }
                    }
                    if (total > 0) goals.put(family, total);
                } else if (personalShortfall > 0) {
                    // Replenish only the requested kind, not unrelated missing gear.
                    goals.merge(relevant.get(0).item, personalShortfall, Math::addExact);
                }
                if (personalShortfall > 0) ready = false;
            }
        }
        List<Selection> selections = new ArrayList<>();
        if (ready) {
            for (int slot = 0; slot < selected.length; slot++) {
                if (selected[slot] > 0) selections.add(new Selection(allocation.stacks.get(slot).stack, selected[slot]));
            }
        }
        return new DeliveryPlan(ready, List.of(lines), selections, goals);
    }

    /** A category floor is not a requirement to keep coal when charcoal can do its job. */
    private static void releaseAlternativeSupplies(Allocation allocation, Request request,
                                                   int[] available, int[] personal) {
        int needed = request.all ? Integer.MAX_VALUE : request.count;
        if (!request.all) {
            for (int i = 0; i < available.length; i++) {
                if (matches(request.item, allocation.stacks.get(i).stack.item)) {
                    needed = Math.max(0, needed - available[i]);
                }
            }
        }
        for (Category category : allocation.categories) {
            for (int source = 0; source < available.length && needed > 0; source++) {
                StackFact from = allocation.stacks.get(source).stack;
                if (personal[source] == 0 || !from.capabilities.isEmpty()
                        || !category.acceptedItems.contains(from.item) || !matches(request.item, from.item)) continue;
                for (int destination = 0; destination < available.length && needed > 0; destination++) {
                    StackFact to = allocation.stacks.get(destination).stack;
                    if (available[destination] == 0 || !to.capabilities.isEmpty()
                            || !category.acceptedItems.contains(to.item) || matches(request.item, to.item)) continue;
                    int moved = Math.min(needed, Math.min(personal[source], available[destination]));
                    personal[source] -= moved;
                    available[source] += moved;
                    personal[destination] += moved;
                    available[destination] -= moved;
                    needed -= moved;
                }
            }
        }
    }

    public enum CleanupAction { KEEP, STORE_REUSE, DISCARD_CANDIDATE }
    public record CleanupSlice(Selection selection, CleanupAction action, String reason) { }

    /**
     * Retain the carried kit independently of storage, then protect the same claims in
     * the registered combined view. This never creates acquisition goals or moves kit.
     * An unfulfilled cargo/stock claim must not become disposable just because it is
     * currently in Home instead of the player's inventory.
     */
    public static Allocation allocateCleanup(List<StackFact> carried, List<StackFact> storage,
                                               Collection<InventoryReservationLedger.Reservation> claims) {
        Allocation personal = allocate(carried, claims, Set.of());
        List<StackFact> combined = new ArrayList<>(carried);
        combined.addAll(storage);
        Allocation shared = allocate(combined, claims, Set.of());
        Map<String, StackAllocation> carriedById = new LinkedHashMap<>();
        personal.stacks.forEach(stack -> carriedById.put(stack.stack.stackId, stack));
        List<StackAllocation> result = new ArrayList<>();
        for (StackAllocation stack : shared.stacks) {
            StackAllocation original = carriedById.get(stack.stack.stackId);
            if (original == null) result.add(stack);
            else {
                int transaction = Math.max(original.transactionCount, stack.transactionCount);
                int retained = Math.min(stack.stack.count - transaction,
                        Math.max(original.personalCount, stack.personalCount));
                result.add(new StackAllocation(stack.stack, transaction, retained,
                        stack.stack.count - transaction - retained, original.reasons));
            }
        }
        return new Allocation(result, personal.categories);
    }

    /** Preview only. Junk IDs/keep overrides come from the user's disclosed policy. */
    public static List<CleanupSlice> previewCleanup(Allocation allocation, Set<String> approvedJunk,
                                                    Set<String> keepItems, boolean actualFuelNeed) {
        return previewCleanup(allocation, approvedJunk, keepItems, actualFuelNeed, true);
    }

    /** Capacity may store an unclaimed specialty tool; Tidy may retain one in Home. */
    public static List<CleanupSlice> previewCleanup(Allocation allocation, Set<String> approvedJunk,
            Set<String> keepItems, boolean actualFuelNeed, boolean retainUnrequestedSpecialtyTools) {
        Set<String> junk = normalizedSet(approvedJunk), keep = normalizedSet(keepItems);
        Map<String, Integer> protectedCounts = cleanupRetained(allocation, keep, retainUnrequestedSpecialtyTools);
        List<CleanupSlice> result = new ArrayList<>();
        for (StackAllocation allocated : allocation.stacks) {
            StackFact stack = allocated.stack;
            int retained = protectedCounts.get(stack.stackId);
            if (retained > 0) result.add(new CleanupSlice(new Selection(stack, retained), CleanupAction.KEEP,
                    stack.custom ? "Named, enchanted or custom stack"
                            : retained > allocated.transactionCount + allocated.personalCount && !stack.equipped && !keep.contains(stack.item)
                            ? stack.foodNutrition > 0 ? "Emergency food fallback retained" : "Usable working tool retained"
                            : "Personal, equipped, kept or transaction-owned"));
            int surplus = stack.count - retained;
            if (surplus == 0) continue;
            boolean obsolete = ordinaryLowTierTool(stack)
                    && allocation.stacks.stream().anyMatch(other -> replacementCovers(other, stack, protectedCounts));
            CleanupAction action = CleanupAction.STORE_REUSE;
            String reason = "Useful spare or ordinary surplus; not approved junk";
            if (obsolete && actualFuelNeed && stack.fuelCompatible) {
                reason = "Obsolete ordinary wooden tool can satisfy current fuel work";
            } else if (obsolete || (stack.capabilities.isEmpty() && junk.contains(stack.item))) {
                action = CleanupAction.DISCARD_CANDIDATE;
                reason = obsolete ? "Redundant low-tier tool; usable replacement retained"
                        : "Item is on the owner's approved junk list";
            }
            result.add(new CleanupSlice(new Selection(stack, surplus), action, reason));
        }
        return List.copyOf(result);
    }

    /** Observed tools only: no hoe/shovel shortage, stock target or future job is invented. */
    private static Map<String, Integer> cleanupRetained(Allocation allocation, Set<String> keep,
            boolean retainUnrequestedSpecialtyTools) {
        Map<String, Integer> retained = new LinkedHashMap<>();
        for (StackAllocation stack : allocation.stacks) {
            retained.put(stack.stack.stackId, stack.stack.custom || stack.stack.equipped || keep.contains(stack.stack.item)
                    ? stack.stack.count : stack.personalCount + stack.transactionCount);
        }
        for (String job : TOOL_CATEGORIES) {
            if (!retainUnrequestedSpecialtyTools && Set.of("hoe", "shovel").contains(job)) continue;
            allocation.stacks.stream().filter(stack -> stack.transactionCount < stack.stack.count
                            && stack.stack.capabilities.containsKey(job) && stack.stack.usableDurability() > 0)
                    .sorted(Comparator.comparingInt((StackAllocation stack) -> stack.stack.capabilities.get(job).level).reversed()
                            .thenComparing(Comparator.comparingDouble((StackAllocation stack) -> stack.stack.capabilities.get(job).effectiveness).reversed())
                            .thenComparing(Comparator.comparingInt((StackAllocation stack) -> stack.stack.usableDurability()).reversed())
                            // An approved duplicate changes from storage:* to player:*
                            // during withdrawal. An equal retained Home tool must still
                            // win; otherwise that action invents a new kit and cancels
                            // every first batch before its exact physical throw.
                            .thenComparing(stack -> stack.stack.stackId.startsWith("player:"))
                            .thenComparing(stack -> stack.stack.stackId))
                    .findFirst().ifPresent(stack -> retained.put(stack.stack.stackId,
                            Math.max(retained.get(stack.stack.stackId), stack.transactionCount + 1)));
        }
        // Existing rations/cargo take precedence. If there is no useful non-junk food,
        // retain eight nutrition points of actually edible fallback (two rotten flesh,
        // or four eyes/potatoes). This is a discard floor, never permission to eat or
        // acquire dangerous food. Registry nutrition comes from the physical adapter.
        int nutrition = 0;
        for (StackAllocation stack : allocation.stacks) {
            int availableFood = DEFAULT_JUNK_ITEMS.contains(stack.stack.item)
                    ? retained.get(stack.stack.stackId) - stack.transactionCount
                    : stack.stack.count - stack.transactionCount;
            nutrition = (int) Math.min(8L, nutrition + (long) availableFood * stack.stack.foodNutrition);
        }
        int deficit = 8 - nutrition;
        for (StackAllocation stack : allocation.stacks.stream()
                .filter(stack -> DEFAULT_JUNK_ITEMS.contains(stack.stack.item) && stack.stack.foodNutrition > 0)
                .sorted(Comparator.comparingInt((StackAllocation stack) -> stack.stack.foodNutrition).reversed()
                        .thenComparing(stack -> stack.stack.stackId.startsWith("player:"))
                        .thenComparing(stack -> stack.stack.stackId)).toList()) {
            if (deficit <= 0) break;
            int current = retained.get(stack.stack.stackId);
            int take = Math.min(stack.stack.count - current,
                    (deficit + stack.stack.foodNutrition - 1) / stack.stack.foodNutrition);
            retained.put(stack.stack.stackId, current + take);
            deficit -= take * stack.stack.foodNutrition;
        }
        return retained;
    }

    private static boolean ordinaryLowTierTool(StackFact stack) {
        return (stack.item.startsWith("minecraft:wooden_") || stack.item.startsWith("minecraft:stone_"))
                && TOOL_CATEGORIES.stream().anyMatch(stack.capabilities::containsKey);
    }

    private static boolean replacementCovers(StackAllocation replacement, StackFact old, Map<String, Integer> retained) {
        if (retained.get(replacement.stack.stackId) <= replacement.transactionCount || replacement.stack.stackId.equals(old.stackId)
                || replacement.stack.usableDurability() < Math.max(1, old.usableDurability())) return false;
        return old.capabilities.entrySet().stream().allMatch(entry -> {
            Capability capability = replacement.stack.capabilities.get(entry.getKey());
            return capability != null && capability.covers(entry.getValue());
        });
    }

    private static boolean relevant(Category category, String request, Allocation allocation) {
        if (category.acceptedItems.stream().anyMatch(item -> matches(request, item))) return true;
        return allocation.stacks.stream().anyMatch(stack -> matches(request, stack.stack.item)
                && stack.stack.capabilities.containsKey(category.id));
    }

    private static boolean isFamily(String item) {
        return FoodFamilyPolicy.isFamilyRequest(item) || WoodLogFamilyPolicy.isFamilyRequest(item);
    }

    private static boolean matches(String request, String item) {
        if (request.equals(item)) return true;
        if (FoodFamilyPolicy.isFamilyRequest(request)) {
            return FoodFamilyPolicy.members().stream().anyMatch(member -> itemId(member.readyItem()).equals(item));
        }
        return WoodLogFamilyPolicy.isFamilyRequest(request)
                && WoodLogFamilyPolicy.members().stream().anyMatch(member -> itemId(member).equals(item));
    }

    private static final class Demand {
        final String id, acquisitionItem, reason;
        final List<String> acceptedItems;
        int desired;
        Demand(String id, int desired, String acquisitionItem, List<String> acceptedItems, String reason) {
            this.id = id; this.desired = desired; this.acquisitionItem = acquisitionItem;
            this.acceptedItems = acceptedItems; this.reason = reason;
        }
        boolean matchesItem(String item) {
            return acceptedItems.stream().anyMatch(accepted -> matches(item, accepted));
        }
        boolean accepts(StackFact stack) {
            if (!acceptedItems.contains(stack.item) && !stack.capabilities.containsKey(id)) return false;
            if (TOOL_CATEGORIES.contains(id) && HomeStockPolicy.toolRank(acquisitionItem) > 2
                    && HomeStockPolicy.toolRank(stack.item) < HomeStockPolicy.toolRank(acquisitionItem)) return false;
            if (ARMOR_CATEGORIES.contains(id)) return stack.remainingDurability > 0;
            return stack.capabilities.isEmpty() || stack.usableDurability() > 0;
        }
        Comparator<MutableStack> preference() {
            return Comparator.comparing((MutableStack stack) -> ARMOR_CATEGORIES.contains(id) && !stack.fact.equipped)
                    .thenComparing(Comparator.comparingInt((MutableStack stack) -> capability(stack.fact).level).reversed())
                    .thenComparing(Comparator.comparingDouble((MutableStack stack) -> capability(stack.fact).effectiveness).reversed())
                    .thenComparing(Comparator.comparingInt((MutableStack stack) -> stack.fact.usableDurability()).reversed())
                    .thenComparingInt(stack -> {
                        int index = acceptedItems.indexOf(stack.fact.item);
                        return index < 0 ? Integer.MAX_VALUE : index;
                    }).thenComparing(stack -> stack.fact.stackId);
        }
        private Capability capability(StackFact stack) {
            return stack.capabilities.getOrDefault(id, new Capability(0, 1));
        }
    }

    private static final class MutableStack {
        final StackFact fact;
        final Set<String> reasons = new LinkedHashSet<>();
        int transaction, personal;
        MutableStack(StackFact fact) { this.fact = Objects.requireNonNull(fact, "stack"); }
        int free() { return fact.count - transaction - personal; }
        StackAllocation freeze() { return new StackAllocation(fact, transaction, personal, free(), List.copyOf(reasons)); }
    }

    private static String itemId(String value) { return DeliveryPolicy.normalizeItemId(text(value)); }
    private static String text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("nonblank identity required");
        return value.trim();
    }
    private static Set<String> normalizedSet(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(itemId(value)));
        return Set.copyOf(result);
    }
    private static <K, V> Map<K, V> immutable(Map<K, V> input) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
