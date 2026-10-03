package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure semantic policy for the user-facing {@code food} resource family.
 *
 * <p>A generic food request is deliberately not rewritten to the first food
 * already carried. Ready food is counted across the whole family, while the
 * next animal is selected exclusively from currently loaded, reachable world
 * opportunities. This keeps five carried chickens from pinning the remainder
 * of a mission to chickens when a pig is standing nearby.</p>
 */
public final class FoodFamilyPolicy {
    public static final String FAMILY_ITEM = "food";
    public static final double REQUIRED_SWITCH_COST_RATIO = 0.65;
    public static final double REQUIRED_SWITCH_ABSOLUTE_SAVING = 6.0;

    /** A non-animal inventory source that can produce ready family items. */
    public record ItemSource(String item, int inputCount, int outputCount) {
        public ItemSource {
            item = requireSafePath(item, "source item");
            if (inputCount <= 0 || outputCount <= 0) {
                throw new IllegalArgumentException("food source counts must be positive");
            }
        }

        int outputFor(int carried) {
            if (carried < 0) throw new IllegalArgumentException("carried count cannot be negative");
            return Math.multiplyExact(carried / inputCount, outputCount);
        }
    }

    /** One ready food and every acquisition form Entity currently knows. */
    public record Member(
            String readyItem,
            String rawItem,
            String animalEntity,
            List<ItemSource> itemSources) {
        public Member {
            readyItem = requireSafePath(readyItem, "ready item");
            rawItem = optionalSafePath(rawItem, "raw item");
            animalEntity = optionalSafePath(animalEntity, "animal entity");
            Objects.requireNonNull(itemSources, "itemSources");
            itemSources = List.copyOf(itemSources);
            if (rawItem.isEmpty() && animalEntity.isEmpty() && itemSources.isEmpty()) {
                throw new IllegalArgumentException("food member needs at least one acquisition source");
            }
        }

        public boolean hunted() {
            return !animalEntity.isEmpty();
        }
    }

    /** Canonical, immutable inventory accounting for a generic food request. */
    public record Summary(
            Map<String, Integer> readyByItem,
            int readyCount,
            Map<String, Integer> convertibleByReadyItem,
            int convertibleCount) {
        public Summary {
            readyByItem = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(readyByItem, "readyByItem")));
            convertibleByReadyItem = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(convertibleByReadyItem, "convertibleByReadyItem")));
            if (readyCount < 0 || convertibleCount < 0) {
                throw new IllegalArgumentException("food summary counts cannot be negative");
            }
        }

        public int potentialCount() {
            return Math.addExact(readyCount, convertibleCount);
        }

        public int remainingReady(int requestedCount) {
            if (requestedCount <= 0) {
                throw new IllegalArgumentException("requested food count must be positive");
            }
            return Math.max(0, requestedCount - readyCount);
        }
    }

    /** Minecraft-independent view of one animal observed by the live client. */
    public record AnimalCandidate(
            String id,
            String entityType,
            double routeCost,
            boolean loaded,
            boolean reachable) {
        public AnimalCandidate {
            id = requireOpaqueId(id);
            entityType = requireSafePath(entityType, "entity type");
            if (!Double.isFinite(routeCost) || routeCost < 0.0) {
                throw new IllegalArgumentException("animal route cost must be finite and non-negative");
            }
        }
    }

    /** A selected animal plus the raw and ready items it can produce. */
    public record AnimalTarget(
            String id,
            String entityType,
            String rawItem,
            String readyItem,
            double routeCost) {
        public AnimalTarget {
            id = requireOpaqueId(id);
            entityType = requireSafePath(entityType, "entity type");
            rawItem = requireSafePath(rawItem, "raw item");
            readyItem = requireSafePath(readyItem, "ready item");
            if (!Double.isFinite(routeCost) || routeCost < 0.0) {
                throw new IllegalArgumentException("animal route cost must be finite and non-negative");
            }
        }
    }

    /** Stateless selection result; callers own the current target identity. */
    public record AnimalDecision(
            Optional<AnimalTarget> target,
            boolean switched,
            String reason) {
        public AnimalDecision {
            target = Objects.requireNonNull(target, "target");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    /** One complete family observation suitable for direct executor integration. */
    public record Evaluation(
            Summary inventory,
            int requestedCount,
            int remainingCount,
            AnimalDecision animalDecision) {
        public Evaluation {
            inventory = Objects.requireNonNull(inventory, "inventory");
            animalDecision = Objects.requireNonNull(animalDecision, "animalDecision");
            if (requestedCount <= 0 || remainingCount < 0) {
                throw new IllegalArgumentException("invalid food evaluation counts");
            }
        }

        public boolean complete() {
            return remainingCount == 0;
        }
    }

    private static final List<Member> MEMBERS = List.of(
            hunted("cooked_beef", "beef", "cow"),
            hunted("cooked_porkchop", "porkchop", "pig"),
            hunted("cooked_chicken", "chicken", "chicken"),
            hunted("cooked_mutton", "mutton", "sheep"),
            new Member("bread", "", "", List.of(
                    new ItemSource("wheat", 3, 1),
                    new ItemSource("hay_bale", 1, 3)))
    );
    private static final Map<String, Member> BY_READY = index(Member::readyItem);
    private static final Map<String, Member> BY_RAW = indexNonBlank(Member::rawItem);
    private static final Map<String, Member> BY_ANIMAL = indexNonBlank(Member::animalEntity);
    private static final Map<String, Member> BY_ITEM_SOURCE = sourceIndex();
    private static final Map<String, Integer> MEMBER_ORDER = memberOrder();

    private FoodFamilyPolicy() {
    }

    public static List<Member> members() {
        return MEMBERS;
    }

    public static boolean isFamilyRequest(String rawItem) {
        return safePath(rawItem).map(FAMILY_ITEM::equals).orElse(false);
    }

    public static boolean isReadyFood(String rawItem) {
        return safePath(rawItem).map(BY_READY::containsKey).orElse(false);
    }

    public static Optional<Member> memberForReadyItem(String rawItem) {
        return safePath(rawItem).map(BY_READY::get);
    }

    public static Optional<Member> memberForRawItem(String rawItem) {
        return safePath(rawItem).map(BY_RAW::get);
    }

    public static Optional<Member> memberForItemSource(String rawItem) {
        return safePath(rawItem).map(BY_ITEM_SOURCE::get);
    }

    public static Optional<Member> memberForAnimal(String rawEntityType) {
        return safePath(rawEntityType).map(BY_ANIMAL::get);
    }

    public static Optional<String> readyItemForAnimal(String rawEntityType) {
        return memberForAnimal(rawEntityType).map(Member::readyItem);
    }

    public static Map<String, Integer> qualifyingReadyCounts(Map<String, Integer> inventory) {
        return summary(inventory).readyByItem();
    }

    public static int aggregateReadyCount(Map<String, Integer> inventory) {
        return summary(inventory).readyCount();
    }

    /** Physical food/input custody while a nested task temporarily makes receiving room. */
    public static Map<String, Integer> retainedAcquisitionInputs(int requested, Map<String, Integer> inventory) {
        if (requested <= 0) throw new IllegalArgumentException("food target must be positive");
        var physical = canonicalInventory(inventory);
        var retained = new LinkedHashMap<String, Integer>();
        int remaining = requested;
        for (Member member : MEMBERS) {
            int count = Math.min(remaining, physical.getOrDefault(member.readyItem(), 0));
            if (count > 0) retained.put(member.readyItem(), count);
            remaining -= count;
        }
        int cooking = 0;
        for (Member member : MEMBERS) {
            if (!member.rawItem().isEmpty()) {
                int count = Math.min(remaining, physical.getOrDefault(member.rawItem(), 0));
                if (count > 0) retained.put(member.rawItem(), count);
                remaining -= count; cooking += count;
            }
            for (ItemSource source : member.itemSources()) {
                int batches = Math.min(physical.getOrDefault(source.item(), 0) / source.inputCount(),
                        (remaining + source.outputCount() - 1) / source.outputCount());
                if (batches > 0) retained.put(source.item(), batches * source.inputCount());
                remaining = Math.max(0, remaining - batches * source.outputCount());
            }
        }
        // The existing food cooker accepts coal/charcoal and unpacks coal blocks.
        int fuel = (cooking + 7) / 8;
        for (String item : List.of("charcoal", "coal", "coal_block")) {
            int units = item.equals("coal_block") ? 9 : 1;
            int count = Math.min(physical.getOrDefault(item, 0), (fuel + units - 1) / units);
            if (count > 0) retained.put(item, count);
            fuel = Math.max(0, fuel - count * units);
        }
        return Map.copyOf(retained);
    }

    /**
     * Absolute ready-food goals count the actual finished food, including a
     * retained ration. Raw ingredients and fuel stay at their reservation-safe
     * counts; observing finished output is not permission to deliver/eat it.
     */
    public static Map<String, Integer> withObservedReadyFood(Map<String, Integer> spendableInputs,
            Map<String, Integer> physicalInventory) {
        var counts = new LinkedHashMap<>(spendableInputs);
        counts.entrySet().removeIf(entry -> isReadyFood(entry.getKey()));
        summary(physicalInventory).readyByItem().forEach((item, count) -> {
            if (count > 0) counts.put(item, count);
        });
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Counts ready items and bounded inventory conversions without treating raw
     * meat as already deliverable. Unknown items are ignored; invalid counts
     * fail closed rather than corrupting mission accounting.
     */
    public static Summary summary(Map<String, Integer> inventory) {
        Map<String, Integer> canonical = canonicalInventory(inventory);
        LinkedHashMap<String, Integer> ready = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> convertible = new LinkedHashMap<>();
        int readyTotal = 0;
        int convertibleTotal = 0;
        for (Member member : MEMBERS) {
            int readyCount = canonical.getOrDefault(member.readyItem(), 0);
            if (readyCount > 0) {
                ready.put(member.readyItem(), readyCount);
                readyTotal = Math.addExact(readyTotal, readyCount);
            }

            int memberConvertible = 0;
            if (!member.rawItem().isEmpty()) {
                memberConvertible = canonical.getOrDefault(member.rawItem(), 0);
            }
            for (ItemSource source : member.itemSources()) {
                memberConvertible = Math.addExact(memberConvertible,
                        source.outputFor(canonical.getOrDefault(source.item(), 0)));
            }
            if (memberConvertible > 0) {
                convertible.put(member.readyItem(), memberConvertible);
                convertibleTotal = Math.addExact(convertibleTotal, memberConvertible);
            }
        }
        return new Summary(Collections.unmodifiableMap(ready), readyTotal,
                Collections.unmodifiableMap(convertible), convertibleTotal);
    }

    /**
     * Selects among loaded, reachable family animals. Carried food is not an
     * input by design. A live target is retained unless a challenger is
     * materially cheaper, avoiding target thrash while permitting obvious
     * chicken-to-pig corrections.
     */
    public static AnimalDecision chooseLoadedAnimal(
            Collection<AnimalCandidate> candidates,
            String currentTargetId,
            boolean currentWithinAttackReach) {
        Objects.requireNonNull(candidates, "candidates");
        String currentId = optionalOpaqueId(currentTargetId);
        ArrayList<AnimalTarget> viable = new ArrayList<>();
        for (AnimalCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            Member member = BY_ANIMAL.get(candidate.entityType());
            if (!candidate.loaded() || !candidate.reachable() || member == null) continue;
            viable.add(new AnimalTarget(candidate.id(), candidate.entityType(),
                    member.rawItem(), member.readyItem(), candidate.routeCost()));
        }
        if (viable.isEmpty()) {
            return new AnimalDecision(Optional.empty(), false,
                    "no loaded reachable food animal is currently available");
        }
        viable.sort(Comparator.comparingDouble(AnimalTarget::routeCost)
                .thenComparingInt(target -> MEMBER_ORDER.get(target.readyItem()))
                .thenComparing(AnimalTarget::id));
        AnimalTarget best = viable.getFirst();
        AnimalTarget current = viable.stream()
                .filter(candidate -> candidate.id().equals(currentId))
                .findFirst().orElse(null);
        if (current == null) {
            return new AnimalDecision(Optional.of(best), !currentId.isEmpty(),
                    currentId.isEmpty()
                            ? "selected the cheapest loaded reachable food animal"
                            : "the previous food animal is no longer viable");
        }
        if (current.id().equals(best.id())) {
            return new AnimalDecision(Optional.of(current), false,
                    "the current food animal remains cheapest");
        }
        if (currentWithinAttackReach) {
            return new AnimalDecision(Optional.of(current), false,
                    "the current food animal is already in attack reach");
        }
        double saving = current.routeCost() - best.routeCost();
        boolean materiallyBetter = saving >= REQUIRED_SWITCH_ABSOLUTE_SAVING
                && best.routeCost() <= current.routeCost() * REQUIRED_SWITCH_COST_RATIO;
        if (!materiallyBetter) {
            return new AnimalDecision(Optional.of(current), false,
                    "the challenger is not materially cheaper than the current food animal");
        }
        return new AnimalDecision(Optional.of(best), true,
                "switched to a materially cheaper loaded food animal");
    }

    /** Combines mixed-food accounting and live animal selection in one call. */
    public static Evaluation evaluate(
            int requestedCount,
            Map<String, Integer> inventory,
            Collection<AnimalCandidate> candidates,
            String currentTargetId,
            boolean currentWithinAttackReach) {
        if (requestedCount <= 0) {
            throw new IllegalArgumentException("requested food count must be positive");
        }
        Summary summary = summary(inventory);
        int remaining = summary.remainingReady(requestedCount);
        AnimalDecision animal = remaining == 0
                ? new AnimalDecision(Optional.empty(), false,
                "the mixed ready-food total already satisfies the request")
                : chooseLoadedAnimal(candidates, currentTargetId, currentWithinAttackReach);
        return new Evaluation(summary, requestedCount, remaining, animal);
    }

    private static Member hunted(String ready, String raw, String animal) {
        return new Member(ready, raw, animal, List.of());
    }

    private interface MemberKey {
        String get(Member member);
    }

    private static Map<String, Member> index(MemberKey key) {
        LinkedHashMap<String, Member> indexed = new LinkedHashMap<>();
        for (Member member : MEMBERS) indexed.put(key.get(member), member);
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<String, Member> indexNonBlank(MemberKey key) {
        LinkedHashMap<String, Member> indexed = new LinkedHashMap<>();
        for (Member member : MEMBERS) {
            String value = key.get(member);
            if (!value.isEmpty()) indexed.put(value, member);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<String, Member> sourceIndex() {
        LinkedHashMap<String, Member> indexed = new LinkedHashMap<>();
        for (Member member : MEMBERS) {
            for (ItemSource source : member.itemSources()) indexed.put(source.item(), member);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<String, Integer> memberOrder() {
        LinkedHashMap<String, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < MEMBERS.size(); index++) {
            order.put(MEMBERS.get(index).readyItem(), index);
        }
        return Collections.unmodifiableMap(order);
    }

    private static Map<String, Integer> canonicalInventory(Map<String, Integer> inventory) {
        Objects.requireNonNull(inventory, "inventory");
        LinkedHashMap<String, Integer> canonical = new LinkedHashMap<>();
        inventory.forEach((rawItem, count) -> {
            if (count == null || count < 0) {
                throw new IllegalArgumentException("inventory count cannot be null or negative");
            }
            Optional<String> item = safePath(rawItem);
            if (item.isEmpty() || count == 0) return;
            canonical.merge(item.orElseThrow(), count, Math::addExact);
        });
        return canonical;
    }

    private static Optional<String> safePath(String raw) {
        if (raw == null) return Optional.empty();
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        normalized = normalized.replace(' ', '_');
        if (normalized.isEmpty() || normalized.length() > 128) return Optional.empty();
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            boolean safe = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_' || character == '-' || character == '.' || character == '/';
            if (!safe) return Optional.empty();
        }
        return Optional.of(normalized);
    }

    private static String requireSafePath(String raw, String label) {
        return safePath(raw).orElseThrow(() ->
                new IllegalArgumentException(label + " must be a safe Minecraft identifier path"));
    }

    private static String optionalSafePath(String raw, String label) {
        if (raw == null || raw.isBlank()) return "";
        return requireSafePath(raw, label);
    }

    private static String requireOpaqueId(String raw) {
        String id = optionalOpaqueId(raw);
        if (id.isEmpty()) throw new IllegalArgumentException("animal id cannot be blank");
        return id;
    }

    private static String optionalOpaqueId(String raw) {
        if (raw == null) return "";
        String id = raw.trim();
        if (id.length() > 256) throw new IllegalArgumentException("animal id is too long");
        for (int index = 0; index < id.length(); index++) {
            char character = id.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException("animal id contains control characters");
            }
        }
        return id;
    }
}
