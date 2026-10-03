package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Pure decoder for the durable cursor of a multi-item mission.
 *
 * <p>Reservation refresh runs before the hierarchical plan is guaranteed to
 * exist. This policy therefore accepts either the immutable mission payload or
 * a restored {@code root.batch.*} checkpoint and never guesses past malformed
 * durable state.</p>
 */
public final class BatchReservationPolicy {
    public static final int MAX_ITEM_COUNT = 4_096;

    private BatchReservationPolicy() {
    }

    public static Decision inspect(
            String encodedItems,
            String encodedIndex,
            String encodedDelivered) {
        if (encodedItems == null || encodedItems.isBlank()) {
            return new Decision(State.NOT_BATCH, List.of(), 0, 0, "not a batch mission");
        }

        ArrayList<Item> items = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int total = 0;
        try {
            for (String entry : encodedItems.split(",", -1)) {
                String[] pair = entry.split("=", -1);
                if (pair.length != 2 || pair[0].isBlank()
                        || pair[0].contains(",") || pair[0].contains("=")) {
                    return corrupt(items, "malformed batch item '" + entry + "'");
                }
                String item = pair[0].trim();
                String identity = canonical(item);
                if (identity.isBlank() || !seen.add(identity)) {
                    return corrupt(items, "blank or duplicate batch item '" + item + "'");
                }
                int count = Integer.parseInt(pair[1]);
                if (count < 1 || count > MAX_ITEM_COUNT) {
                    return corrupt(items, "batch count is outside 1.." + MAX_ITEM_COUNT);
                }
                total = Math.addExact(total, count);
                items.add(new Item(item, count));
            }
        } catch (NumberFormatException | ArithmeticException error) {
            return corrupt(items, "invalid or overflowing batch count");
        }
        if (items.size() < 2) {
            return corrupt(items, "a batch requires at least two items");
        }

        Integer index = nonNegative(encodedIndex, 0);
        Integer delivered = nonNegative(encodedDelivered, 0);
        if (index == null || index > items.size()) {
            return corrupt(items, "batch index is outside the durable objective");
        }
        if (delivered == null) {
            return corrupt(items, "batch delivered count is invalid");
        }
        if (index == items.size()) {
            return delivered == 0
                    ? new Decision(State.COMPLETE, List.copyOf(items), index, 0,
                    "all batch items are committed")
                    : corrupt(items, "completed batch retained an in-flight delivery count");
        }
        if (delivered > items.get(index).count()) {
            return corrupt(items, "batch delivered count exceeds the current item objective");
        }
        return new Decision(
                State.ACTIVE, List.copyOf(items), index, delivered,
                "batch item " + (index + 1) + "/" + items.size());
    }

    private static Decision corrupt(List<Item> parsed, String reason) {
        return new Decision(State.CORRUPT, List.copyOf(parsed), 0, 0, reason);
    }

    private static Integer nonNegative(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw);
            return value < 0 ? null : value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String canonical(String value) {
        String normalized = value == null ? ""
                : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return normalized.startsWith("minecraft:")
                ? normalized.substring("minecraft:".length()) : normalized;
    }

    public enum State {
        NOT_BATCH,
        ACTIVE,
        COMPLETE,
        CORRUPT
    }

    public record Item(String item, int count) {
    }

    public record Decision(
            State state,
            List<Item> items,
            int index,
            int delivered,
            String reason) {
        public Decision {
            items = List.copyOf(items);
        }

        public Item current() {
            if (state != State.ACTIVE || index < 0 || index >= items.size()) return null;
            return items.get(index);
        }
    }
}
