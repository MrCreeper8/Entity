package dev.entity.client.autonomy.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Selects one bounded job inside the existing Home Stock owner. Never grants movement authority. */
public final class IdleStockPolicy {
    private IdleStockPolicy() { }
    public static final Set<String> SURPLUS_CATEGORIES = Set.of("food", "fuel", "logs", "iron");
    private static final List<String> MAINTENANCE = List.of("food", "pickaxe", "weapon", "shield", "axe", "fuel");
    private static final List<String> SURPLUS_PRIORITY = List.of("food", "fuel", "logs", "iron");
    public enum State { IDLE, INSPECTING, WORKING, COMPLETED, DEFERRED, BLOCKED, CANCELLED }
    public enum Action { WITHDRAW, DEPOSIT, ACQUIRE, COMPLETE, DEFER }

    public record Observation(long homeGeneration, boolean homeConfigured, boolean storageConfigured,
            boolean stockEnabled, Map<String, Integer> carried, Map<String, Integer> stored,
            long storageObservedAtMillis, boolean storageFresh, Map<String, Integer> serviceableTools,
            Map<String, String> workingTools, boolean requested, boolean settled, long completedCycles,
            State state, String resultKey, String detail) {
        public Observation {
            carried = Map.copyOf(carried); stored = Map.copyOf(stored);
            serviceableTools = Map.copyOf(serviceableTools); workingTools = Map.copyOf(workingTools);
            Objects.requireNonNull(state); Objects.requireNonNull(resultKey); Objects.requireNonNull(detail);
        }
    }

    public record Decision(Action action, String category, HomeStockPolicy.Transfer transfer,
            Map<String, Integer> additionalGoals, String key, String detail) {
        public Decision { additionalGoals = Map.copyOf(additionalGoals); }
    }

    public static Map<String, Integer> validateTargets(Map<String, Integer> requested) {
        Map<String, Integer> result = new LinkedHashMap<>();
        requested.forEach((category, count) -> {
            if (!SURPLUS_CATEGORIES.contains(category) || count == null || count < 0 || count > 4096)
                throw new IllegalArgumentException("Idle stock targets must be food/fuel/logs/iron counts 0..4096");
            result.put(category, count);
        });
        return Map.copyOf(result);
    }

    public static List<HomeStockPolicy.Target> retainedProfile(Map<String, String> workingTools) {
        return HomeStockPolicy.retainedProfile(workingTools);
    }

    public static HomeStockPolicy.Target target(String category, Map<String, String> tools) {
        if (category.equals("iron")) return new HomeStockPolicy.Target("iron", 1, "iron_ingot",
                List.of("iron_ingot"), List.of(), HomeStockPolicy.CompletionRequirement.NONE, "Useful stored iron");
        return retainedProfile(tools).stream().filter(t -> t.id().equals(category)).findFirst().orElseThrow();
    }

    public static List<String> accepted(String category, Map<String, String> tools) {
        return target(category, tools).acceptedItems();
    }
    public static int count(Map<String, Integer> counts, List<String> items) {
        return items.stream().mapToInt(item -> counts.getOrDefault(item, counts.getOrDefault("minecraft:" + item, 0))).sum();
    }
    private static int floor(String category, Map<String, String> tools) {
        return category.equals("iron") ? 0 : target(category, tools).desiredCount();
    }

    public static int serviceableToolCount(String category, Map<String, Integer> carried, Map<String, String> tools) {
        String preferred = tools.getOrDefault(category, target(category, tools).acquisitionItem());
        int rank = toolRank(preferred);
        return count(carried, accepted(category, tools).stream().filter(item -> toolRank(item) >= rank).toList());
    }
    public static int toolRank(String item) {
        return HomeStockPolicy.toolRank(item);
    }

    public static String selectCategory(Map<String, Integer> player, Map<String, Integer> stored,
            Map<String, Integer> targets, Map<String, String> tools) {
        for (String category : MAINTENANCE) {
            int carried = SURPLUS_CATEGORIES.contains(category)
                    ? count(player, accepted(category, tools)) : serviceableToolCount(category, player, tools);
            if (carried < floor(category, tools)) return category;
        }
        for (String category : SURPLUS_PRIORITY)
            if (count(stored, accepted(category, tools)) < targets.getOrDefault(category, 0)) return category;
        return "";
    }

    /** Full personal profile is retained even though exactly one category may acquire. */
    public static Decision decide(String selected, boolean acquisitionIssued,
            Map<String, Integer> player, Map<String, Integer> stored, Map<String, Integer> eligible,
            Map<String, Integer> targets, Map<String, String> tools,
            int playerRoom, int storageRoom, boolean inventoryFull) {
        String category = selected.isEmpty() ? selectCategory(player, stored, targets, tools) : selected;
        if (category.isEmpty()) return terminal(Action.COMPLETE, category, "demand_met", "Personal supplies and configured stored surplus are met");
        HomeStockPolicy.Target target = target(category, tools);
        List<String> accepted = target.acceptedItems();
        boolean tool = !SURPLUS_CATEGORIES.contains(category);
        List<String> suitable = tool ? accepted.stream().filter(item -> toolRank(item) >= toolRank(target.acquisitionItem())).toList() : accepted;
        int carried = count(player, suitable), saved = count(stored, suitable), retained = floor(category, tools);
        int desiredStored = targets.getOrDefault(category, 0);
        int carryDeficit = Math.max(0, retained - carried);
        if (carryDeficit > 0 && saved > 0) {
            for (String item : suitable) {
                int available = count(stored, List.of(item));
                if (available > 0) return transfer(Action.WITHDRAW, category, item, Math.min(carryDeficit, available));
            }
        }
        int savedDeficit = Math.max(0, desiredStored - saved);
        var analysis = HomeStockPolicy.analyze(player, stored, eligible, retainedProfile(tools));
        for (var deposit : analysis.deposits()) {
            if ((deposit.categoryId().equals(category) || category.equals("iron") && deposit.item().equals("iron_ingot"))
                    && savedDeficit > 0) {
                if (storageRoom <= 0) return terminal(Action.DEFER, category, "storage_full", "Registered storage has no observed room for " + category);
                return transfer(Action.DEPOSIT, category, deposit.item(), Math.min(deposit.count(), Math.min(savedDeficit, storageRoom)));
            }
        }
        if (savedDeficit > 0 && carried > retained && count(eligible, accepted) == 0)
            return terminal(Action.DEFER, category, "surplus_reserved", "Existing carried surplus is held or reserved; no duplicate batch will be acquired");
        if (acquisitionIssued) return terminal(Action.COMPLETE, category, "batch_complete", "Finite " + category + " batch returned and settled; remaining demand waits for a later idle period");
        if (carryDeficit == 0 && savedDeficit == 0)
            return terminal(Action.COMPLETE, category, "category_met", "Selected " + category + " supplies are met");
        if (savedDeficit > 0 && storageRoom <= 0 && carryDeficit == 0)
            return terminal(Action.DEFER, category, "storage_full", "Registered storage has no observed room for " + category);
        if (playerRoom <= 0 || inventoryFull) {
            if (!analysis.deposits().isEmpty()) {
                var deposit = analysis.deposits().getFirst();
                return new Decision(Action.DEPOSIT, category, deposit, Map.of(), "capacity_escape", "Store existing eligible surplus before this finite acquisition");
            }
            return terminal(Action.DEFER, category, "inventory_full", "No receiving room or eligible surplus can be stored");
        }
        int additional = Math.min(64, Math.min(playerRoom, carryDeficit + Math.min(savedDeficit, storageRoom)));
        if (additional <= 0) return terminal(Action.DEFER, category, "capacity_unavailable", "No physically bounded receiving capacity is available");
        return new Decision(Action.ACQUIRE, category, null, Map.of(target.acquisitionItem(), additional),
                "acquire_batch", "Acquire finite " + category + " batch " + additional + ", then return and store only surplus");
    }

    public static boolean localUndergroundPlan(AcquisitionPlan plan) {
        return plan.actions().stream().allMatch(action -> switch (action.kind()) {
            case CRAFT, SMELT, EQUIP -> true;
            default -> false;
        });
    }
    public static boolean undergroundMinePlan(AcquisitionPlan plan, Set<String> oreBlocks) {
        return plan.actions().stream().anyMatch(action -> action.kind() == ResourcePlanner.ActionKind.MINE)
                && plan.actions().stream().allMatch(action -> switch (action.kind()) {
                    case CRAFT, SMELT, EQUIP -> true;
                    case MINE -> java.util.Arrays.stream(action.parameters().getOrDefault("blockAlternatives", "").split(","))
                            .allMatch(oreBlocks::contains);
                    default -> false;
                });
    }
    private static Decision transfer(Action action, String category, String item, int count) {
        return new Decision(action, category, new HomeStockPolicy.Transfer(
                action == Action.WITHDRAW ? HomeStockPolicy.Direction.WITHDRAW : HomeStockPolicy.Direction.DEPOSIT,
                category, item, count), Map.of(), action.name().toLowerCase(java.util.Locale.ROOT),
                (action == Action.WITHDRAW ? "Withdraw " : "Store ") + count + ' ' + item + " using observed Home stock");
    }
    private static Decision terminal(Action action, String category, String key, String detail) {
        return new Decision(action, category, null, Map.of(), key, detail);
    }
}
