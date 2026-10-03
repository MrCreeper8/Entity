package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure, durable phase policy for ordered multi-item missions.
 *
 * <p>A delivery batch has one hard gate: every still-undelivered objective must
 * be present before the first (or next) handoff is allowed. The caller supplies
 * one aggregate inventory count per ordered objective, so category objectives
 * such as {@code food} may include several concrete item families without this
 * policy having to understand Minecraft item identities.</p>
 *
 * <p>The returned checkpoint is a full copy of the caller's map with the five
 * durable batch fields updated. Callers can persist it directly. A
 * {@link Action#CHECKPOINT} is an intentional write barrier: persist it and
 * evaluate again before starting a child operation.</p>
 */
public final class GatherFirstBatchPolicy {
    public static final String PHASE_KEY = "batchPhase";
    public static final String ACQUIRE_CURSOR_KEY = "batchAcquireIndex";
    public static final String DELIVERY_CURSOR_KEY = "batchDeliveryIndex";
    public static final String ACQUIRED_COUNTS_KEY = "batchAcquiredCounts";
    public static final String DELIVERED_COUNTS_KEY = "batchDeliveredCounts";

    private GatherFirstBatchPolicy() {
    }

    public static Decision evaluate(
            String encodedItems,
            Map<String, String> durableCheckpoint,
            List<Integer> observedAvailableCounts,
            boolean inventoryCapacityAvailable,
            Mode mode) {
        Map<String, String> original = durableCheckpoint == null
                ? Map.of() : durableCheckpoint;
        BatchReservationPolicy.Decision parsed =
                BatchReservationPolicy.inspect(encodedItems, "0", "0");
        if (parsed.state() == BatchReservationPolicy.State.NOT_BATCH
                || parsed.state() == BatchReservationPolicy.State.CORRUPT) {
            return corrupt(original, parsed.reason());
        }

        List<Item> items = parsed.items().stream()
                .map(item -> new Item(item.item(), item.count()))
                .toList();
        if (mode == null) return corrupt(original, "batch mode is required");
        if (observedAvailableCounts == null
                || observedAvailableCounts.size() != items.size()) {
            return corrupt(original, "inventory evidence does not match the batch objective");
        }
        for (Integer count : observedAvailableCounts) {
            if (count == null || count < 0) {
                return corrupt(original, "inventory evidence contains an invalid count");
            }
        }

        Restored restored = restore(items, original);
        if (!restored.valid()) return corrupt(original, restored.reason());

        Phase phase = restored.phase();
        ArrayList<Integer> acquired = new ArrayList<>(restored.acquired());
        ArrayList<Integer> delivered = new ArrayList<>(restored.delivered());

        // Observed ownership is stronger than a stale cursor. The aggregate is
        // the currently secured mission quantity (already delivered plus still
        // owned), not a historical high-water mark; death or consumption must
        // be able to move the acquisition cursor back to the first deficit.
        for (int index = 0; index < items.size(); index++) {
            int evidence;
            try {
                evidence = Math.addExact(delivered.get(index), observedAvailableCounts.get(index));
            } catch (ArithmeticException overflow) {
                return corrupt(original, "inventory evidence overflowed");
            }
            acquired.set(index, Math.min(items.get(index).count(), evidence));
        }

        int missing = firstMissing(items, delivered, observedAvailableCounts);
        if (phase == Phase.DELIVER_ALL && missing >= 0) {
            // Death, consumption, or another inventory mutation invalidated the
            // handoff gate. Persist the phase rollback before reacquiring.
            phase = Phase.ACQUIRE_ALL;
            Map<String, String> checkpoint = checkpoint(
                    original, phase, missing,
                    firstIncomplete(items, delivered), acquired, delivered);
            return decision(
                    Action.CHECKPOINT, phase, items, missing, 0,
                    checkpoint, acquired, delivered,
                    "inventory changed; reacquire every missing batch remainder before delivery");
        }

        if (phase == Phase.ACQUIRE_ALL) {
            int acquireIndex = missing < 0 ? items.size() : missing;
            Map<String, String> checkpoint = checkpoint(
                    original, phase, acquireIndex,
                    firstIncomplete(items, delivered), acquired, delivered);

            if (missing < 0) {
                if (mode == Mode.ACQUIRE_ONLY) {
                    return decision(
                            Action.COMPLETE, phase, items, items.size(), 0,
                            checkpoint, acquired, delivered,
                            "all ordered batch objectives are present");
                }
                phase = Phase.DELIVER_ALL;
                checkpoint = checkpoint(
                        original, phase, items.size(),
                        firstIncomplete(items, delivered), acquired, delivered);
                return decision(
                        Action.CHECKPOINT, phase, items,
                        firstIncomplete(items, delivered), 0,
                        checkpoint, acquired, delivered,
                        "acquire-all gate committed; delivery may begin after this checkpoint");
            }

            if (!sameDurableState(original, checkpoint)) {
                return decision(
                        Action.CHECKPOINT, phase, items, missing, 0,
                        checkpoint, acquired, delivered,
                        "persist observed acquisition aggregates before starting more work");
            }

            int remaining = outstanding(items, delivered, missing)
                    - observedAvailableCounts.get(missing);
            Action action = inventoryCapacityAvailable
                    ? Action.ACQUIRE : Action.WAIT_FOR_CAPACITY;
            return decision(
                    action, phase, items, missing, remaining,
                    checkpoint, acquired, delivered,
                    inventoryCapacityAvailable
                            ? "acquire ordered batch item " + (missing + 1) + "/" + items.size()
                            : "inventory capacity unavailable; retain the same batch objective");
        }

        int deliveryIndex = firstIncomplete(items, delivered);
        Map<String, String> checkpoint = checkpoint(
                original, phase, items.size(), deliveryIndex, acquired, delivered);
        if (deliveryIndex >= items.size()) {
            return decision(
                    Action.COMPLETE, phase, items, items.size(), 0,
                    checkpoint, acquired, delivered,
                    "every ordered batch handoff is confirmed");
        }
        if (!sameDurableState(original, checkpoint)) {
            return decision(
                    Action.CHECKPOINT, phase, items, deliveryIndex, 0,
                    checkpoint, acquired, delivered,
                    "persist observed delivery aggregates before the next handoff");
        }
        int remaining = outstanding(items, delivered, deliveryIndex);
        return decision(
                Action.DELIVER, phase, items, deliveryIndex, remaining,
                checkpoint, acquired, delivered,
                "deliver ordered batch item " + (deliveryIndex + 1) + "/" + items.size());
    }

    /**
     * Applies a confirmed receipt delta to the exact delivery returned by
     * {@link #evaluate}. The aggregate is advanced once and the ordered cursor
     * cannot skip an incomplete item.
     */
    public static Update confirmDeliveredDelta(Decision delivery, int confirmedDelta) {
        if (delivery == null || delivery.action() != Action.DELIVER
                || delivery.phase() != Phase.DELIVER_ALL) {
            return invalidUpdate(delivery, "delivery confirmation requires an active DELIVER decision");
        }
        if (confirmedDelta < 1) {
            return invalidUpdate(delivery, "confirmed delivery delta must be positive");
        }
        int index = delivery.index();
        int confirmedTotal;
        try {
            confirmedTotal = Math.addExact(delivery.delivered().get(index), confirmedDelta);
        } catch (ArithmeticException overflow) {
            return invalidUpdate(delivery, "confirmed delivery count overflowed");
        }
        return confirmDeliveredTotal(delivery, confirmedTotal);
    }

    /**
     * Applies an asynchronous server receipt directly to durable state.
     *
     * <p>This overload intentionally requires no local inventory observation:
     * Paper may remove the stack before the client receives the receipt. The
     * durable delivery cursor and optional item identity bind the receipt to the
     * one handoff currently allowed by the ordered policy.</p>
     */
    public static Update confirmDeliveredDelta(
            String encodedItems,
            Map<String, String> durableCheckpoint,
            int expectedIndex,
            String expectedItem,
            int confirmedDelta) {
        Map<String, String> original = durableCheckpoint == null
                ? Map.of() : durableCheckpoint;
        BatchReservationPolicy.Decision parsed =
                BatchReservationPolicy.inspect(encodedItems, "0", "0");
        if (parsed.state() == BatchReservationPolicy.State.NOT_BATCH
                || parsed.state() == BatchReservationPolicy.State.CORRUPT) {
            return new Update(false, original, expectedIndex, parsed.reason());
        }
        List<Item> items = parsed.items().stream()
                .map(item -> new Item(item.item(), item.count()))
                .toList();
        Restored restored = restore(items, original);
        if (!restored.valid()) {
            return new Update(false, original, expectedIndex, restored.reason());
        }
        if (restored.phase() != Phase.DELIVER_ALL) {
            return new Update(false, original, expectedIndex,
                    "server receipt arrived outside the deliver-all phase");
        }
        int cursor = firstIncomplete(items, restored.delivered());
        if (expectedIndex != cursor || expectedIndex < 0 || expectedIndex >= items.size()) {
            return new Update(false, original, cursor,
                    "server receipt does not match the durable delivery cursor");
        }
        if (expectedItem != null && !expectedItem.isBlank()
                && !canonical(expectedItem).equals(canonical(items.get(cursor).item()))) {
            return new Update(false, original, cursor,
                    "server receipt item does not match the durable delivery objective");
        }
        Map<String, String> normalized = checkpoint(
                original, restored.phase(), items.size(), cursor,
                restored.acquired(), restored.delivered());
        Decision delivery = decision(
                Action.DELIVER, restored.phase(), items, cursor,
                outstanding(items, restored.delivered(), cursor),
                normalized, restored.acquired(), restored.delivered(),
                "apply asynchronous server delivery receipt");
        return confirmDeliveredDelta(delivery, confirmedDelta);
    }

    public static Update confirmDeliveredDelta(
            String encodedItems,
            Map<String, String> durableCheckpoint,
            int expectedIndex,
            int confirmedDelta) {
        return confirmDeliveredDelta(
                encodedItems, durableCheckpoint, expectedIndex, null, confirmedDelta);
    }

    /**
     * Applies a cumulative, nonce-relative Paper receipt against a durable
     * ordered item. Supplying the already-accounted base makes replay of the
     * same cumulative receipt idempotent across reconnects and restarts.
     */
    public static Update confirmDeliveredTotal(
            String encodedItems,
            Map<String, String> durableCheckpoint,
            int expectedIndex,
            String expectedItem,
            int confirmedTotal) {
        Map<String, String> original = durableCheckpoint == null
                ? Map.of() : durableCheckpoint;
        BatchReservationPolicy.Decision parsed =
                BatchReservationPolicy.inspect(encodedItems, "0", "0");
        if (parsed.state() == BatchReservationPolicy.State.NOT_BATCH
                || parsed.state() == BatchReservationPolicy.State.CORRUPT) {
            return new Update(false, original, expectedIndex, parsed.reason());
        }
        List<Item> items = parsed.items().stream()
                .map(item -> new Item(item.item(), item.count()))
                .toList();
        Restored restored = restore(items, original);
        if (!restored.valid()) {
            return new Update(false, original, expectedIndex, restored.reason());
        }
        if (restored.phase() != Phase.DELIVER_ALL) {
            return new Update(false, original, expectedIndex,
                    "server receipt arrived outside the deliver-all phase");
        }
        int cursor = firstIncomplete(items, restored.delivered());
        if (expectedIndex != cursor || expectedIndex < 0 || expectedIndex >= items.size()) {
            // An identical receipt replay after this item advanced is already
            // reflected durably and therefore succeeds without another write.
            if (expectedIndex >= 0 && expectedIndex < items.size()
                    && restored.delivered().get(expectedIndex) == confirmedTotal) {
                return new Update(true, checkpoint(
                        original, restored.phase(), items.size(), cursor,
                        restored.acquired(), restored.delivered()), cursor,
                        "cumulative server receipt was already committed");
            }
            return new Update(false, original, cursor,
                    "server receipt does not match the durable delivery cursor");
        }
        if (expectedItem != null && !expectedItem.isBlank()
                && !canonical(expectedItem).equals(canonical(items.get(cursor).item()))) {
            return new Update(false, original, cursor,
                    "server receipt item does not match the durable delivery objective");
        }
        Map<String, String> normalized = checkpoint(
                original, restored.phase(), items.size(), cursor,
                restored.acquired(), restored.delivered());
        Decision delivery = decision(
                Action.DELIVER, restored.phase(), items, cursor,
                outstanding(items, restored.delivered(), cursor),
                normalized, restored.acquired(), restored.delivered(),
                "apply cumulative asynchronous server delivery receipt");
        return confirmDeliveredTotal(delivery, confirmedTotal);
    }

    /** Records the aggregate confirmed count for the current ordered item. */
    public static Update confirmDeliveredTotal(Decision delivery, int confirmedTotal) {
        if (delivery == null || delivery.action() != Action.DELIVER
                || delivery.phase() != Phase.DELIVER_ALL) {
            return invalidUpdate(delivery, "delivery confirmation requires an active DELIVER decision");
        }
        int index = delivery.index();
        int previous = delivery.delivered().get(index);
        int objective = delivery.items().get(index).count();
        if (confirmedTotal < previous || confirmedTotal > objective) {
            return invalidUpdate(
                    delivery, "confirmed aggregate cannot move backward or exceed the objective");
        }
        ArrayList<Integer> delivered = new ArrayList<>(delivery.delivered());
        delivered.set(index, confirmedTotal);
        int next = firstIncomplete(delivery.items(), delivered);
        Map<String, String> checkpoint = checkpoint(
                delivery.checkpoint(), Phase.DELIVER_ALL,
                delivery.items().size(), next, delivery.acquired(), delivered);
        return new Update(true, checkpoint, next,
                confirmedTotal == objective
                        ? "confirmed batch item and advanced the ordered delivery cursor"
                        : "confirmed partial batch delivery without advancing the cursor");
    }

    private static Restored restore(List<Item> items, Map<String, String> checkpoint) {
        Phase phase;
        try {
            String raw = checkpoint.getOrDefault(PHASE_KEY, "").trim();
            phase = raw.isEmpty() ? Phase.ACQUIRE_ALL : Phase.fromWire(raw);
        } catch (IllegalArgumentException error) {
            return Restored.invalid(error.getMessage());
        }

        List<Integer> acquired = decodeCounts(
                checkpoint.get(ACQUIRED_COUNTS_KEY), items.size(), "acquired");
        List<Integer> delivered = decodeCounts(
                checkpoint.get(DELIVERED_COUNTS_KEY), items.size(), "delivered");
        if (acquired == null || delivered == null) {
            return Restored.invalid("durable batch aggregates are malformed");
        }
        for (int index = 0; index < items.size(); index++) {
            int objective = items.get(index).count();
            if (acquired.get(index) > objective
                    || delivered.get(index) > objective
                    || delivered.get(index) > acquired.get(index)) {
                return Restored.invalid("durable batch aggregates exceed their ordered objective");
            }
        }
        if (phase == Phase.DELIVER_ALL) {
            for (int index = 0; index < items.size(); index++) {
                if (acquired.get(index) < items.get(index).count()) {
                    return Restored.invalid("deliver-all phase was persisted before acquire-all completed");
                }
            }
        }

        int expectedAcquire = firstIncomplete(items, acquired);
        int expectedDelivery = firstIncomplete(items, delivered);
        Integer acquireCursor = decodeCursor(
                checkpoint.get(ACQUIRE_CURSOR_KEY), expectedAcquire, items.size());
        Integer deliveryCursor = decodeCursor(
                checkpoint.get(DELIVERY_CURSOR_KEY), expectedDelivery, items.size());
        if (acquireCursor == null || deliveryCursor == null) {
            return Restored.invalid("durable batch cursor is malformed or out of range");
        }
        if (checkpoint.containsKey(ACQUIRE_CURSOR_KEY) && acquireCursor != expectedAcquire) {
            return Restored.invalid("acquisition cursor disagrees with durable aggregates");
        }
        if (checkpoint.containsKey(DELIVERY_CURSOR_KEY) && deliveryCursor != expectedDelivery) {
            return Restored.invalid("delivery cursor disagrees with durable aggregates");
        }
        return new Restored(true, phase, acquired, delivered, "restored durable batch phase");
    }

    private static List<Integer> decodeCounts(String raw, int size, String label) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>(java.util.Collections.nCopies(size, 0));
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != size) return null;
        ArrayList<Integer> counts = new ArrayList<>(size);
        try {
            for (String part : parts) {
                if (part.isBlank()) return null;
                int value = Integer.parseInt(part);
                if (value < 0) return null;
                counts.add(value);
            }
        } catch (NumberFormatException error) {
            return null;
        }
        return counts;
    }

    private static Integer decodeCursor(String raw, int fallback, int size) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw);
            return value < 0 || value > size ? null : value;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static int firstMissing(
            List<Item> items,
            List<Integer> delivered,
            List<Integer> available) {
        for (int index = 0; index < items.size(); index++) {
            if (available.get(index) < outstanding(items, delivered, index)) return index;
        }
        return -1;
    }

    private static int outstanding(List<Item> items, List<Integer> delivered, int index) {
        return items.get(index).count() - delivered.get(index);
    }

    private static int firstIncomplete(List<Item> items, List<Integer> counts) {
        for (int index = 0; index < items.size(); index++) {
            if (counts.get(index) < items.get(index).count()) return index;
        }
        return items.size();
    }

    private static Map<String, String> checkpoint(
            Map<String, String> base,
            Phase phase,
            int acquireCursor,
            int deliveryCursor,
            List<Integer> acquired,
            List<Integer> delivered) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(base);
        result.put(PHASE_KEY, phase.wire());
        result.put(ACQUIRE_CURSOR_KEY, Integer.toString(acquireCursor));
        result.put(DELIVERY_CURSOR_KEY, Integer.toString(deliveryCursor));
        result.put(ACQUIRED_COUNTS_KEY, encodeCounts(acquired));
        result.put(DELIVERED_COUNTS_KEY, encodeCounts(delivered));
        return Map.copyOf(result);
    }

    private static String encodeCounts(List<Integer> counts) {
        StringBuilder encoded = new StringBuilder();
        for (int index = 0; index < counts.size(); index++) {
            if (index > 0) encoded.append(',');
            encoded.append(counts.get(index));
        }
        return encoded.toString();
    }

    private static String canonical(String raw) {
        String value = raw == null ? ""
                : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return value.startsWith("minecraft:")
                ? value.substring("minecraft:".length()) : value;
    }

    private static boolean sameDurableState(
            Map<String, String> left,
            Map<String, String> right) {
        return sameValue(left, right, PHASE_KEY)
                && sameValue(left, right, ACQUIRE_CURSOR_KEY)
                && sameValue(left, right, DELIVERY_CURSOR_KEY)
                && sameValue(left, right, ACQUIRED_COUNTS_KEY)
                && sameValue(left, right, DELIVERED_COUNTS_KEY);
    }

    private static boolean sameValue(
            Map<String, String> left,
            Map<String, String> right,
            String key) {
        return java.util.Objects.equals(left.get(key), right.get(key));
    }

    private static Decision decision(
            Action action,
            Phase phase,
            List<Item> items,
            int index,
            int remaining,
            Map<String, String> checkpoint,
            List<Integer> acquired,
            List<Integer> delivered,
            String reason) {
        return new Decision(
                action, phase, items, index, remaining,
                checkpoint, acquired, delivered, reason);
    }

    private static Decision corrupt(Map<String, String> checkpoint, String reason) {
        return new Decision(
                Action.CORRUPT, null, List.of(), -1, 0,
                Map.copyOf(checkpoint), List.of(), List.of(), reason);
    }

    private static Update invalidUpdate(Decision decision, String reason) {
        Map<String, String> checkpoint = decision == null
                ? Map.of() : decision.checkpoint();
        int index = decision == null ? -1 : decision.index();
        return new Update(false, checkpoint, index, reason);
    }

    public enum Mode {
        ACQUIRE_ONLY,
        ACQUIRE_THEN_DELIVER
    }

    public enum Phase {
        ACQUIRE_ALL("acquire_all"),
        DELIVER_ALL("deliver_all");

        private final String wire;

        Phase(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        private static Phase fromWire(String raw) {
            for (Phase value : values()) {
                if (value.wire.equals(raw)) return value;
            }
            throw new IllegalArgumentException("unknown durable batch phase '" + raw + "'");
        }
    }

    public enum Action {
        ACQUIRE,
        CHECKPOINT,
        DELIVER,
        WAIT_FOR_CAPACITY,
        COMPLETE,
        CORRUPT
    }

    public record Item(String item, int count) {
    }

    public record Decision(
            Action action,
            Phase phase,
            List<Item> items,
            int index,
            int remaining,
            Map<String, String> checkpoint,
            List<Integer> acquired,
            List<Integer> delivered,
            String reason) {
        public Decision {
            items = List.copyOf(items);
            checkpoint = Map.copyOf(checkpoint);
            acquired = List.copyOf(acquired);
            delivered = List.copyOf(delivered);
        }

        public Item current() {
            if (index < 0 || index >= items.size()) return null;
            return items.get(index);
        }
    }

    public record Update(
            boolean valid,
            Map<String, String> checkpoint,
            int nextIndex,
            String reason) {
        public Update {
            checkpoint = Map.copyOf(checkpoint);
        }
    }

    private record Restored(
            boolean valid,
            Phase phase,
            List<Integer> acquired,
            List<Integer> delivered,
            String reason) {
        private static Restored invalid(String reason) {
            return new Restored(false, null, List.of(), List.of(), reason);
        }
    }
}
