package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure accounting for the loadout maintained by Entity's owned home chest.
 *
 * <p>Counts supplied as {@code depositEligibleCounts} are deliberately a
 * separate input. The runtime must exclude mission reservations, custom/NBT
 * stacks, damaged equipment, equipped armour, cursor cargo, and anything else
 * it cannot prove safe to store. This policy can therefore never turn a broad
 * item-family match into authority to move a protected stack.</p>
 */
public final class HomeStockPolicy {
    /** Enough ready food to survive the bounded basic-gear bootstrap without filling all stock. */
    public static final int MINIMUM_READY_FOOD_FLOOR = 8;
    private static final List<String> BASIC_SURVIVAL_GEAR_PRIORITY = List.of(
            "pickaxe", "weapon", "shield", "axe");

    public enum CompletionRequirement {
        NONE,
        FILL_WITH_WATER
    }

    public enum Direction {
        WITHDRAW,
        DEPOSIT
    }

    /** Ordered accepted items are also the deterministic retention order. */
    public record Target(
            String id,
            int desiredCount,
            String acquisitionItem,
            List<String> acceptedItems,
            List<String> conversionPrecursors,
            CompletionRequirement completionRequirement,
            String reason) {
        public Target {
            id = requireId(id, "id");
            if (desiredCount <= 0) throw new IllegalArgumentException("desiredCount must be positive");
            acquisitionItem = normalizeItem(acquisitionItem);
            acceptedItems = normalizedItems(acceptedItems, "acceptedItems");
            conversionPrecursors = normalizedItemsAllowEmpty(
                    conversionPrecursors, "conversionPrecursors");
            completionRequirement = Objects.requireNonNull(
                    completionRequirement, "completionRequirement");
            reason = requireText(reason, "reason", 1024);
            if (completionRequirement == CompletionRequirement.NONE
                    && !conversionPrecursors.isEmpty()) {
                throw new IllegalArgumentException(
                        "conversion precursors require a completion requirement");
            }
            if (completionRequirement != CompletionRequirement.NONE
                    && conversionPrecursors.isEmpty()) {
                throw new IllegalArgumentException(
                        "post-processing target requires conversion precursors");
            }
        }

        public boolean accepts(String item) {
            return acceptedItems.contains(normalizeItem(item));
        }
    }

    public record TargetStatus(
            Target target,
            int playerCount,
            int chestCount,
            int withdrawCount,
            int acquisitionCount,
            int conversionCount,
            int depositCount) {
        public TargetStatus {
            target = Objects.requireNonNull(target, "target");
            if (playerCount < 0 || chestCount < 0 || withdrawCount < 0
                    || acquisitionCount < 0 || conversionCount < 0 || depositCount < 0) {
                throw new IllegalArgumentException("stock counts cannot be negative");
            }
            if (withdrawCount > chestCount) {
                throw new IllegalArgumentException("withdrawal exceeds observed chest count");
            }
            if (depositCount > Math.max(0, playerCount - target.desiredCount())) {
                throw new IllegalArgumentException("deposit crosses the carried stock floor");
            }
        }

        public int deficitAfterWithdrawal() {
            return Math.max(0, target.desiredCount() - playerCount - withdrawCount);
        }

        public boolean carriedTargetMet() {
            return playerCount >= target.desiredCount();
        }
    }

    /** One exact, bounded item movement. The controller still chooses slots. */
    public record Transfer(Direction direction, String categoryId, String item, int count) {
        public Transfer {
            direction = Objects.requireNonNull(direction, "direction");
            categoryId = requireId(categoryId, "categoryId");
            item = normalizeItem(item);
            if (count <= 0) throw new IllegalArgumentException("transfer count must be positive");
        }
    }

    public record AcquisitionNeed(
            String categoryId,
            String item,
            int count,
            CompletionRequirement completionRequirement) {
        public AcquisitionNeed {
            categoryId = requireId(categoryId, "categoryId");
            item = normalizeItem(item);
            if (count <= 0) throw new IllegalArgumentException("acquisition count must be positive");
            completionRequirement = Objects.requireNonNull(
                    completionRequirement, "completionRequirement");
        }
    }

    public record ConversionNeed(
            String categoryId,
            List<String> precursorItems,
            String requiredResult,
            int count,
            CompletionRequirement requirement) {
        public ConversionNeed {
            categoryId = requireId(categoryId, "categoryId");
            precursorItems = normalizedItems(precursorItems, "precursorItems");
            requiredResult = normalizeItem(requiredResult);
            if (count <= 0) throw new IllegalArgumentException("conversion count must be positive");
            requirement = Objects.requireNonNull(requirement, "requirement");
            if (requirement == CompletionRequirement.NONE) {
                throw new IllegalArgumentException("conversion requires a postcondition");
            }
        }
    }

    public record Analysis(
            List<TargetStatus> targets,
            List<Transfer> withdrawals,
            List<AcquisitionNeed> acquisitions,
            List<ConversionNeed> conversions,
            List<Transfer> deposits) {
        public Analysis {
            targets = immutableCopy(targets, "targets");
            withdrawals = immutableCopy(withdrawals, "withdrawals");
            acquisitions = immutableCopy(acquisitions, "acquisitions");
            conversions = immutableCopy(conversions, "conversions");
            deposits = immutableCopy(deposits, "deposits");
        }

        /** True only when the carried loadout is complete; storage may still need tidying. */
        public boolean loadoutReady() {
            return withdrawals.isEmpty() && acquisitions.isEmpty() && conversions.isEmpty();
        }

        public boolean balanced() {
            return loadoutReady() && deposits.isEmpty();
        }

        /**
         * One additional stock category for the next durable Home acquisition.
         *
         * <p>Home first establishes an eight-food emergency floor, then obtains the basic tool,
         * weapon, and shield capability needed to gather the remaining bulk stock safely. This
         * deliberately prevents a sixteen-food or sixty-four-block reserve from sending an
         * otherwise provisioned player through natural night with only a wooden pickaxe.
         * Each category returns through the existing Home checkpoint before the next starts:
         * incidental drops can consume receiving room during travel, so unrelated stock
         * categories must not trap the later deposit behind one gather-first cargo batch.</p>
         */
        public Map<String, Integer> plannerGoals() {
            LinkedHashMap<String, Integer> goals = new LinkedHashMap<>();

            AcquisitionNeed food = acquisition("food");
            if (food != null) {
                TargetStatus foodStatus = target("food");
                int floorDeficit = Math.max(0,
                        MINIMUM_READY_FOOD_FLOOR
                                - foodStatus.playerCount()
                                - foodStatus.withdrawCount());
                if (floorDeficit > 0) {
                    goals.put(food.item(), Math.min(floorDeficit, food.count()));
                    return Collections.unmodifiableMap(goals);
                }
            }

            for (String category : BASIC_SURVIVAL_GEAR_PRIORITY) {
                AcquisitionNeed need = acquisition(category);
                if (need != null) {
                    goals.merge(need.item(), need.count(), Math::addExact);
                    return Collections.unmodifiableMap(goals);
                }
            }

            for (AcquisitionNeed need : acquisitions) {
                goals.merge(need.item(), need.count(), Math::addExact);
                break;
            }
            return Collections.unmodifiableMap(goals);
        }

        private AcquisitionNeed acquisition(String categoryId) {
            return acquisitions.stream()
                    .filter(need -> need.categoryId().equals(categoryId))
                    .findFirst()
                    .orElse(null);
        }

        public TargetStatus target(String id) {
            String normalized = requireId(id, "id");
            return targets.stream()
                    .filter(status -> status.target().id().equals(normalized))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown home-stock target " + normalized));
        }
    }

    private static final List<Target> DEFAULTS = List.of(
            target("building_blocks", 64, "cobblestone",
                    List.of("cobblestone", "cobbled_deepslate", "dirt", "netherrack", "blackstone"),
                    "Bridge, pillar, seal hazards, and recover from blocked routes"),
            target("logs", 8, WoodLogFamilyPolicy.FAMILY_ITEM,
                    WoodLogFamilyPolicy.members(),
                    "Recover crafting infrastructure without returning home"),
            target("fuel", 8, "coal", List.of("coal", "charcoal"),
                    "Run furnaces and craft torches"),
            target("food", 16, FoodFamilyPolicy.FAMILY_ITEM,
                    List.of("golden_carrot", "cooked_beef", "cooked_porkchop", "cooked_mutton",
                            "cooked_chicken", "cooked_rabbit", "baked_potato", "bread"),
                    "Maintain a safe survival food buffer"),
            target("pickaxe", 1, "stone_pickaxe",
                    List.of("netherite_pickaxe", "diamond_pickaxe", "iron_pickaxe", "stone_pickaxe"),
                    "Keep one serviceable mining tool"),
            target("axe", 1, "stone_axe",
                    List.of("netherite_axe", "diamond_axe", "iron_axe", "stone_axe"),
                    "Keep one serviceable wood-cutting tool"),
            target("weapon", 1, "stone_sword",
                    List.of("netherite_sword", "diamond_sword", "iron_sword", "stone_sword"),
                    "Keep one serviceable dedicated weapon"),
            target("shield", 1, "shield", List.of("shield"),
                    "Block arrows and high-damage melee hits"),
            new Target("filled_bucket", 1, "bucket", List.of("water_bucket"),
                    List.of("bucket"), CompletionRequirement.FILL_WITH_WATER,
                    "Carry verified emergency water; empty and lava buckets do not satisfy it"),
            target("torches", 32, "torch", List.of("torch"),
                    "Light mines and mark routes")
    );

    static {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        LinkedHashSet<String> accepted = new LinkedHashSet<>();
        for (Target target : DEFAULTS) {
            if (!ids.add(target.id())) throw new ExceptionInInitializerError("duplicate stock target");
            for (String item : target.acceptedItems()) {
                if (!accepted.add(item)) {
                    throw new ExceptionInInitializerError(
                            "stock item belongs to multiple categories: " + item);
                }
            }
        }
    }

    private HomeStockPolicy() {
    }

    public static List<Target> defaults() {
        return DEFAULTS;
    }

    /** One remembered completion floor for direct Stock and optional idle work. */
    public static List<Target> retainedProfile(Map<String, String> workingTools) {
        return DEFAULTS.stream().map(target -> {
            String remembered = workingTools.get(target.id());
            if (remembered == null || !target.accepts(remembered)) return target;
            String replacement = normalizeItem(remembered);
            int rank = toolRank(replacement);
            return new Target(target.id(), target.desiredCount(), replacement,
                    target.acceptedItems().stream().filter(item -> toolRank(item) >= rank).toList(),
                    target.conversionPrecursors(), target.completionRequirement(), target.reason());
        }).toList();
    }

    public static int toolRank(String rawItem) {
        String item = rawItem.startsWith("minecraft:") ? rawItem.substring(10) : rawItem;
        if (item.startsWith("netherite_")) return 5;
        if (item.startsWith("diamond_")) return 4;
        if (item.startsWith("iron_")) return 3;
        if (item.startsWith("stone_") || item.equals("shield")) return 2;
        return 0;
    }

    /**
     * Home's building-block floor is working traversal stock, not sealed cargo.
     * The runtime may spend it on a bridge or pillar and the next stock analysis
     * will replenish whatever was physically consumed.
     */
    public static boolean isWorkingBuildingBlock(String rawItem) {
        String item = normalizeItem(rawItem);
        return DEFAULTS.stream()
                .filter(target -> target.id().equals("building_blocks"))
                .findFirst()
                .orElseThrow()
                .acceptedItems()
                .contains(item);
    }

    /**
     * Converts deficit counts into the absolute inventory goals consumed by
     * the durable Home acquisition mission. Generic food and logs are counted
     * across their qualifying physical stacks rather than as nonexistent
     * literal family items.
     */
    public static Map<String, Integer> absoluteAcquisitionGoals(
            Map<String, Integer> deficits,
            Map<String, Integer> playerCounts) {
        Map<String, Integer> normalizedDeficits = normalizedCounts(deficits, "deficits");
        Map<String, Integer> normalizedPlayer = normalizedCounts(playerCounts, "playerCounts");
        LinkedHashMap<String, Integer> absolute = new LinkedHashMap<>();
        normalizedDeficits.forEach((item, missing) -> absolute.put(
                item,
                Math.addExact(acquisitionGoalCount(item, normalizedPlayer), missing)));
        return Collections.unmodifiableMap(absolute);
    }

    /** True only when every persisted Home acquisition goal is physically present. */
    public static boolean acquisitionGoalsSatisfied(
            Map<String, Integer> goals,
            Map<String, Integer> playerCounts) {
        Map<String, Integer> normalizedGoals = normalizedCounts(goals, "goals");
        Map<String, Integer> normalizedPlayer = normalizedCounts(playerCounts, "playerCounts");
        return normalizedGoals.entrySet().stream().allMatch(entry ->
                acquisitionGoalCount(entry.getKey(), normalizedPlayer) >= entry.getValue());
    }

    public static Analysis analyze(
            Map<String, Integer> playerCounts,
            Map<String, Integer> chestCounts,
            Map<String, Integer> depositEligibleCounts) {
        return analyze(playerCounts, chestCounts, depositEligibleCounts, DEFAULTS);
    }

    public static Analysis analyze(
            Map<String, Integer> playerCounts,
            Map<String, Integer> chestCounts,
            Map<String, Integer> depositEligibleCounts,
            List<Target> targets) {
        Map<String, Integer> player = normalizedCounts(playerCounts, "playerCounts");
        Map<String, Integer> chest = normalizedCounts(chestCounts, "chestCounts");
        Map<String, Integer> eligible = normalizedCounts(
                depositEligibleCounts, "depositEligibleCounts");
        LinkedHashMap<String, Integer> generalDepositEligible =
                new LinkedHashMap<>(eligible);
        eligible.forEach((item, count) -> {
            if (count > player.getOrDefault(item, 0)) {
                throw new IllegalArgumentException(
                        "deposit-eligible count exceeds player count for " + item);
            }
        });
        Objects.requireNonNull(targets, "targets");

        ArrayList<TargetStatus> statuses = new ArrayList<>();
        ArrayList<Transfer> withdrawals = new ArrayList<>();
        ArrayList<AcquisitionNeed> acquisitions = new ArrayList<>();
        ArrayList<ConversionNeed> conversions = new ArrayList<>();
        ArrayList<Transfer> deposits = new ArrayList<>();
        LinkedHashSet<String> targetIds = new LinkedHashSet<>();
        LinkedHashSet<String> assignedAcceptedItems = new LinkedHashSet<>();

        for (Target target : targets) {
            Objects.requireNonNull(target, "target");
            if (!targetIds.add(target.id())) {
                throw new IllegalArgumentException("duplicate target " + target.id());
            }
            for (String acceptedItem : target.acceptedItems()) {
                if (!assignedAcceptedItems.add(acceptedItem)) {
                    throw new IllegalArgumentException(
                            "accepted item belongs to multiple targets: " + acceptedItem);
                }
            }

            int playerAccepted = count(player, target.acceptedItems());
            int chestAccepted = count(chest, target.acceptedItems());
            int shortage = Math.max(0, target.desiredCount() - playerAccepted);
            int withdrawAccepted = Math.min(shortage, chestAccepted);
            appendTransfers(withdrawals, Direction.WITHDRAW, target.id(),
                    target.acceptedItems(), chest, withdrawAccepted);
            int remaining = shortage - withdrawAccepted;

            int acquisitionCount = 0;
            int conversionCount = 0;
            if (remaining > 0 && target.completionRequirement() == CompletionRequirement.NONE) {
                if (FoodFamilyPolicy.isFamilyRequest(target.acquisitionItem())) {
                    // The synthetic food root already cooks/crafts carried inputs.
                    // Transfer observed Home inputs first; do not recursively wrap
                    // Home's acquisition in the user-mission chest-sourcing owner.
                    retainAndWithdrawFoodInputs(withdrawals, generalDepositEligible, player, chest, remaining);
                }
                acquisitionCount = remaining;
                acquisitions.add(new AcquisitionNeed(target.id(), target.acquisitionItem(),
                        acquisitionCount, CompletionRequirement.NONE));
            } else if (remaining > 0) {
                int playerPrecursors = count(player, target.conversionPrecursors());
                retainForConversion(generalDepositEligible, player,
                        target.conversionPrecursors(), Math.min(remaining, playerPrecursors));
                int chestPrecursors = count(chest, target.conversionPrecursors());
                int precursorWithdraw = Math.min(remaining, chestPrecursors);
                appendTransfers(withdrawals, Direction.WITHDRAW, target.id(),
                        target.conversionPrecursors(), chest, precursorWithdraw);
                int availablePrecursors = Math.min(
                        remaining, Math.addExact(playerPrecursors, precursorWithdraw));
                acquisitionCount = remaining - availablePrecursors;
                if (acquisitionCount > 0) {
                    acquisitions.add(new AcquisitionNeed(target.id(), target.acquisitionItem(),
                            acquisitionCount, target.completionRequirement()));
                }
                conversionCount = remaining;
                conversions.add(new ConversionNeed(target.id(), target.conversionPrecursors(),
                        target.acceptedItems().get(0), conversionCount,
                        target.completionRequirement()));
            }

            int surplus = Math.max(0, playerAccepted - target.desiredCount());
            int plannedDeposit = appendDeposits(deposits, target, player, eligible, surplus);
            statuses.add(new TargetStatus(target, playerAccepted, chestAccepted,
                    withdrawAccepted, acquisitionCount, conversionCount, plannedDeposit));
        }


        // Everything left in this caller-proved safe subset is ordinary Home
        // working storage. Target-family stacks were already limited by their
        // carried floors above; only unclassified pristine surplus belongs in
        // this deterministic catch-all.
        generalDepositEligible.entrySet().stream()
                .filter(entry -> !assignedAcceptedItems.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> deposits.add(new Transfer(
                        Direction.DEPOSIT,
                        "general_surplus",
                        entry.getKey(),
                        entry.getValue())));

        return new Analysis(statuses, withdrawals, acquisitions, conversions, deposits);
    }

    private static void retainAndWithdrawFoodInputs(List<Transfer> withdrawals,
            Map<String, Integer> eligible, Map<String, Integer> player,
            Map<String, Integer> chest, int deficit) {
        int remaining = deficit;
        for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
            ArrayList<FoodFamilyPolicy.ItemSource> sources = new ArrayList<>();
            if (!member.rawItem().isEmpty()) sources.add(new FoodFamilyPolicy.ItemSource(member.rawItem(), 1, 1));
            sources.addAll(member.itemSources());
            for (FoodFamilyPolicy.ItemSource source : sources) {
                if (remaining == 0) return;
                int carried = player.getOrDefault(source.item(), 0);
                int stored = chest.getOrDefault(source.item(), 0);
                int batches = Math.min((remaining + source.outputCount() - 1) / source.outputCount(),
                        Math.addExact(carried, stored) / source.inputCount());
                int inputs = Math.multiplyExact(batches, source.inputCount());
                int retained = Math.min(inputs, carried);
                if (retained > 0) retainForConversion(eligible, player, List.of(source.item()), retained);
                int needed = inputs - retained;
                if (needed > 0) withdrawals.add(new Transfer(Direction.WITHDRAW, "food", source.item(), needed));
                remaining = Math.max(0, remaining - Math.multiplyExact(batches, source.outputCount()));
            }
        }
    }

    private static void retainForConversion(
            Map<String, Integer> depositEligible,
            Map<String, Integer> player,
            List<String> orderedItems,
            int requested) {
        int remaining = requested;
        for (String item : orderedItems) {
            if (remaining == 0) break;
            int retained = Math.min(remaining, player.getOrDefault(item, 0));
            if (retained > 0) {
                int eligible = depositEligible.getOrDefault(item, 0);
                int updated = Math.max(0, eligible - retained);
                if (updated == 0) {
                    depositEligible.remove(item);
                } else {
                    depositEligible.put(item, updated);
                }
                remaining -= retained;
            }
        }
        if (remaining != 0) {
            throw new IllegalStateException("conversion retention exceeded observed items");
        }
    }

    private static int appendDeposits(
            List<Transfer> result,
            Target target,
            Map<String, Integer> player,
            Map<String, Integer> eligible,
            int requested) {
        int remaining = requested;
        for (int index = target.acceptedItems().size() - 1; index >= 0 && remaining > 0; index--) {
            String item = target.acceptedItems().get(index);
            int safe = Math.min(player.getOrDefault(item, 0), eligible.getOrDefault(item, 0));
            int moved = Math.min(remaining, safe);
            if (moved > 0) {
                result.add(new Transfer(Direction.DEPOSIT, target.id(), item, moved));
                remaining -= moved;
            }
        }
        return requested - remaining;
    }

    private static void appendTransfers(
            List<Transfer> result,
            Direction direction,
            String categoryId,
            List<String> orderedItems,
            Map<String, Integer> available,
            int requested) {
        int remaining = requested;
        for (String item : orderedItems) {
            if (remaining == 0) break;
            int moved = Math.min(remaining, available.getOrDefault(item, 0));
            if (moved > 0) {
                result.add(new Transfer(direction, categoryId, item, moved));
                remaining -= moved;
            }
        }
        if (remaining != 0) {
            throw new IllegalStateException("transfer allocation exceeded observed items");
        }
    }

    private static int count(Map<String, Integer> counts, List<String> items) {
        int total = 0;
        for (String item : items) total = Math.addExact(total, counts.getOrDefault(item, 0));
        return total;
    }

    private static int acquisitionGoalCount(
            String item,
            Map<String, Integer> playerCounts) {
        if (FoodFamilyPolicy.isFamilyRequest(item)) {
            return FoodFamilyPolicy.aggregateReadyCount(playerCounts);
        }
        if (WoodLogFamilyPolicy.isFamilyRequest(item)) {
            return WoodLogFamilyPolicy.aggregateCount(playerCounts);
        }
        return playerCounts.getOrDefault(item, 0);
    }

    private static Map<String, Integer> normalizedCounts(
            Map<String, Integer> rawCounts,
            String field) {
        Objects.requireNonNull(rawCounts, field);
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        rawCounts.forEach((rawItem, rawCount) -> {
            if (rawCount == null || rawCount < 0) {
                throw new IllegalArgumentException(field + " cannot contain negative/null counts");
            }
            if (rawCount > 0) {
                normalized.merge(normalizeItem(rawItem), rawCount, Math::addExact);
            }
        });
        return Collections.unmodifiableMap(normalized);
    }

    private static Target target(
            String id,
            int desiredCount,
            String acquisitionItem,
            List<String> acceptedItems,
            String reason) {
        return new Target(id, desiredCount, acquisitionItem, acceptedItems,
                List.of(), CompletionRequirement.NONE, reason);
    }

    private static String normalizeItem(String value) {
        String item = requireText(value, "item", 256)
                .toLowerCase(Locale.ROOT).replace(' ', '_');
        return item.startsWith("minecraft:")
                ? item.substring("minecraft:".length()) : item;
    }

    private static String requireId(String value, String field) {
        String id = requireText(value, field, 128)
                .toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!id.matches("[a-z0-9_.:-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return id;
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        if (trimmed.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return trimmed;
    }

    private static List<String> normalizedItems(List<String> values, String field) {
        List<String> normalized = normalizedItemsAllowEmpty(values, field);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " cannot be empty");
        return normalized;
    }

    private static List<String> normalizedItemsAllowEmpty(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) normalized.add(normalizeItem(value));
        if (normalized.size() != values.size()) {
            throw new IllegalArgumentException(field + " contains duplicates");
        }
        return List.copyOf(normalized);
    }

    private static <T> List<T> immutableCopy(List<T> values, String field) {
        Objects.requireNonNull(values, field);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value, field + " entry"));
        return List.copyOf(copy);
    }
}
