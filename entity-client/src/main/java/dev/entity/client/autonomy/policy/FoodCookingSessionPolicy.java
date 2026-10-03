package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Builds one durable furnace session for every raw-meat subtype currently
 * owned by a generic {@code food} objective.
 *
 * <p>The old executor expanded beef, pork, and mutton into independent smelt
 * leaves. Each leaf reclaimed the furnace, discarded its remaining burn time,
 * and made the next subtype place the same workstation again. A session is the
 * atomic unit instead: it has one owner, one furnace placement, one continuous
 * fuel budget, and one reclaim after every entry has completed.</p>
 */
public final class FoodCookingSessionPolicy {
    private static final String ENTRY_SEPARATOR = ";";
    private static final String OUTPUT_SEPARATOR = ">";
    private static final String COUNT_SEPARATOR = "=";

    /** One exact raw-to-cooked portion inside a shared furnace session. */
    public record Entry(String rawItem, String readyItem, int operations) {
        public Entry {
            rawItem = normalize(rawItem);
            readyItem = normalize(readyItem);
            if (rawItem.isBlank() || readyItem.isBlank()) {
                throw new IllegalArgumentException("food cooking items cannot be blank");
            }
            if (operations <= 0) {
                throw new IllegalArgumentException("food cooking operations must be positive");
            }
            FoodFamilyPolicy.Member member = FoodFamilyPolicy.memberForRawItem(rawItem)
                    .orElse(null);
            if (member == null) {
                throw new IllegalArgumentException(
                        "unsupported raw food in cooking session: " + rawItem);
            }
            if (!member.hunted() || !member.readyItem().equals(readyItem)) {
                throw new IllegalArgumentException(
                        rawItem + " does not cook into " + readyItem);
            }
        }
    }

    /** Immutable ordered work owned by one physical furnace lifecycle. */
    public record Plan(List<Entry> entries) {
        public Plan {
            Objects.requireNonNull(entries, "entries");
            LinkedHashMap<String, Entry> byReadyItem = new LinkedHashMap<>();
            for (Entry entry : entries) {
                Objects.requireNonNull(entry, "entry");
                Entry duplicate = byReadyItem.putIfAbsent(entry.readyItem(), entry);
                if (duplicate != null) {
                    throw new IllegalArgumentException(
                            "duplicate cooked food in one session: " + entry.readyItem());
                }
            }
            entries = Collections.unmodifiableList(new ArrayList<>(byReadyItem.values()));
        }

        public boolean empty() {
            return entries.isEmpty();
        }

        public int totalOperations() {
            int total = 0;
            for (Entry entry : entries) total = Math.addExact(total, entry.operations());
            return total;
        }

        /** A non-empty plan always places/opens one furnace and reclaims it once. */
        public int workstationSessions() {
            return empty() ? 0 : 1;
        }

        public int reclaimsRequired() {
            return empty() ? 0 : 1;
        }

        public Map<String, Integer> rawInputs() {
            LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
            for (Entry entry : entries) result.put(entry.rawItem(), entry.operations());
            return Collections.unmodifiableMap(result);
        }
    }

    private FoodCookingSessionPolicy() {
    }

    /**
     * Allocates only the raw-meat part of the unfinished family objective.
     * Ready food is deducted first. Non-furnace sources (currently bread) stay
     * for the ordinary crafting planner after this single cooking session.
     */
    public static Plan planForObjective(
            int requiredReadyTotal,
            Map<String, Integer> carriedInventory) {
        if (requiredReadyTotal <= 0) {
            throw new IllegalArgumentException("required ready food must be positive");
        }
        Objects.requireNonNull(carriedInventory, "carriedInventory");
        FoodFamilyPolicy.Summary summary = FoodFamilyPolicy.summary(carriedInventory);
        int remaining = Math.max(0, requiredReadyTotal - summary.readyCount());
        ArrayList<Entry> entries = new ArrayList<>();
        for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
            if (remaining == 0) break;
            int convertible = summary.convertibleByReadyItem()
                    .getOrDefault(member.readyItem(), 0);
            int allocated = Math.min(remaining, convertible);
            if (allocated <= 0) continue;
            if (member.hunted()) {
                entries.add(new Entry(member.rawItem(), member.readyItem(), allocated));
            }
            remaining -= allocated;
        }
        return new Plan(entries);
    }

    /**
     * Reallocates a generic session only between acknowledged, empty furnace
     * batches. A travelled-with raw portion is not an exact-item mission: eating
     * or loss may remove it while another subtype remains available. Collected
     * output stays durable even if subsequently eaten. Loaded slots/cursor must
     * finish under their existing plan before this boundary may change it.
     */
    public static Plan reconcileAvailable(int operationBudget, Plan current,
            Map<String, Integer> completedByReadyItem, Map<String, Integer> available,
            boolean furnaceBatchSettled) {
        if (!furnaceBatchSettled) return current;
        LinkedHashMap<String, Integer> completed = new LinkedHashMap<>();
        for (Entry entry : current.entries()) {
            int done = Math.min(entry.operations(), Math.max(0,
                    completedByReadyItem.getOrDefault(entry.readyItem(), 0)));
            completed.put(entry.readyItem(), done);
        }
        int remaining = Math.max(0, operationBudget - completed.values().stream().mapToInt(Integer::intValue).sum());
        var summary = FoodFamilyPolicy.summary(available);
        ArrayList<Entry> entries = new ArrayList<>();
        for (FoodFamilyPolicy.Member member : FoodFamilyPolicy.members()) {
            if (!member.hunted()) continue;
            int allocated = Math.min(remaining,
                    summary.convertibleByReadyItem().getOrDefault(member.readyItem(), 0));
            int operations = Math.addExact(completed.getOrDefault(member.readyItem(), 0), allocated);
            if (operations > 0) entries.add(new Entry(member.rawItem(), member.readyItem(), operations));
            remaining -= allocated;
        }
        return new Plan(entries);
    }

    /** Count acknowledged gains, not a lifetime inventory high-water mark. */
    public static int observedCompleted(int operations, int durable, int previousObserved, int nowObserved) {
        return Math.min(operations, Math.addExact(Math.max(0, durable),
                Math.max(0, nowObserved - previousObserved)));
    }

    /** Do not demand a vanished top-up before cooking input already in custody. */
    public static int availableBatchOperations(int outstanding, int carried, int furnaceInput, int cursorInput) {
        return Math.max(1, Math.min(outstanding, Math.addExact(carried, Math.addExact(furnaceInput, cursorInput))));
    }

    /** Compact, strict representation safe for durable plan parameters. */
    public static String encode(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        return plan.entries().stream()
                .map(entry -> entry.rawItem() + OUTPUT_SEPARATOR + entry.readyItem()
                        + COUNT_SEPARATOR + entry.operations())
                .collect(java.util.stream.Collectors.joining(ENTRY_SEPARATOR));
    }

    public static Plan decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return new Plan(List.of());
        ArrayList<Entry> entries = new ArrayList<>();
        for (String rawEntry : encoded.split(ENTRY_SEPARATOR, -1)) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                throw new IllegalArgumentException("empty food cooking session entry");
            }
            String[] counted = entry.split(COUNT_SEPARATOR, 2);
            String[] conversion = counted[0].split(OUTPUT_SEPARATOR, 2);
            if (counted.length != 2 || conversion.length != 2) {
                throw new IllegalArgumentException("invalid food cooking session entry: " + entry);
            }
            int operations;
            try {
                operations = Integer.parseInt(counted[1]);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        "invalid food cooking operation count: " + counted[1], error);
            }
            entries.add(new Entry(conversion[0], conversion[1], operations));
        }
        return new Plan(entries);
    }

    private static String normalize(String item) {
        String normalized = Objects.requireNonNullElse(item, "")
                .trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return normalized.startsWith("minecraft:")
                ? normalized.substring("minecraft:".length())
                : normalized;
    }
}
