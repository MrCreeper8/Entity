package dev.entity.client.autonomy.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Single ownership and acknowledgement boundary for inventory mutations.
 *
 * <p>Every click is fenced until the cursor, the clicked slot, or an explicitly
 * correlated destination count changes. This prevents the old rapid
 * toggle/repeated-click failure when the server packet arrives later than the
 * local executor tick. Cursor transfers and asynchronous removals use the same
 * owner identities, so furnace, crafting, equipment and delivery cannot
 * concurrently claim the same inventory state.</p>
 */
public final class InventoryTransactionEngine {
    public static final long DEFAULT_CLICK_INTERVAL_MILLIS = 125L;
    public static final long DEFAULT_ACK_TIMEOUT_MILLIS = 1_500L;
    /**
     * ScreenHandler#getRevision is a server-side synchronization counter.  On
     * the vanilla client it commonly remains unchanged even after an accepted
     * click, so an exact semantic postcondition must also be able to settle the
     * transaction.  Six client ticks gives a local server ample time to send a
     * correction without making every accepted click wait for the full timeout.
     */
    public static final long DEFAULT_SEMANTIC_STABILITY_MILLIS = 300L;

    private final long clickIntervalMillis;
    private final long acknowledgementTimeoutMillis;
    private final long semanticStabilityMillis;
    private PendingClick pendingClick;
    private CursorTransfer cursorTransfer;
    private final Map<String, RemovalTransaction> removals = new LinkedHashMap<>();
    private long nextClickAt;
    private long acknowledgedClicks;
    private long timedOutClicks;
    private ClickTimeout lastTimeout;

    public InventoryTransactionEngine() {
        this(DEFAULT_CLICK_INTERVAL_MILLIS, DEFAULT_ACK_TIMEOUT_MILLIS,
                DEFAULT_SEMANTIC_STABILITY_MILLIS);
    }

    InventoryTransactionEngine(long clickIntervalMillis, long acknowledgementTimeoutMillis) {
        this(clickIntervalMillis, acknowledgementTimeoutMillis,
                Math.min(DEFAULT_SEMANTIC_STABILITY_MILLIS,
                        Math.max(1L, acknowledgementTimeoutMillis - 1L)));
    }

    InventoryTransactionEngine(
            long clickIntervalMillis,
            long acknowledgementTimeoutMillis,
            long semanticStabilityMillis) {
        if (clickIntervalMillis < 0 || acknowledgementTimeoutMillis < 1
                || semanticStabilityMillis < 1
                || semanticStabilityMillis > acknowledgementTimeoutMillis) {
            throw new IllegalArgumentException("invalid inventory transaction timing");
        }
        this.clickIntervalMillis = clickIntervalMillis;
        this.acknowledgementTimeoutMillis = acknowledgementTimeoutMillis;
        this.semanticStabilityMillis = semanticStabilityMillis;
    }

    /** Reconciles the outstanding click and reports whether this owner may mutate now. */
    public Gate gate(String owner, InventorySnapshot current, long nowMillis) {
        String normalizedOwner = owner(owner);
        Objects.requireNonNull(current, "current");
        reconcileHandler(current.syncId());
        if (pendingClick != null) {
            long age = Math.max(0L, nowMillis - pendingClick.startedAt());
            boolean serverAdvanced = current.hasServerRevisionAfter(pendingClick.before());
            boolean slotProof = pendingClick.requiredSlotDelta() == null
                    ? current.acknowledges(
                    pendingClick.before(), pendingClick.watchedSlots())
                    : pendingClick.requiredSlotDelta().acknowledges(
                    pendingClick.before(), current);
            boolean consumedFuelProof = pendingClick.furnaceFuelConsumptionProof() != null
                    && pendingClick.furnaceFuelConsumptionProof().acknowledges(
                    pendingClick.owner(), pendingClick.before(), current);
            boolean playerCountProof = pendingClick.playerCountDelta() != null
                    && pendingClick.playerCountDelta().acknowledges(
                    pendingClick.before(), current);
            boolean semanticProof = slotProof || consumedFuelProof || playerCountProof;
            if (semanticProof && (serverAdvanced
                    || age >= semanticStabilityMillis)) {
                pendingClick = null;
                acknowledgedClicks++;
            } else {
                if (age <= acknowledgementTimeoutMillis) {
                    return pendingClick.owner().equals(normalizedOwner)
                            ? Gate.WAITING_FOR_ACKNOWLEDGEMENT
                            : Gate.WAITING_FOR_OTHER_OWNER;
                }
                PendingClick timedOut = pendingClick;
                pendingClick = null;
                timedOutClicks++;
                lastTimeout = new ClickTimeout(
                        timedOutClicks,
                        nowMillis,
                        timedOut.startedAt(),
                        timedOut.owner(),
                        timedOut.before().syncId(),
                        timedOut.watchedSlots());
                if (nowMillis < nextClickAt) return Gate.WAITING_FOR_INTERVAL;
                return Gate.READY_AFTER_TIMEOUT;
            }
        }
        return nowMillis >= nextClickAt ? Gate.READY : Gate.WAITING_FOR_INTERVAL;
    }

    /** Records the exact pre-click truth which must change before another click. */
    public void recordClick(
            String owner,
            InventorySnapshot before,
            List<Integer> watchedSlots,
            long nowMillis) {
        recordClick(owner, before, watchedSlots, null, null, null, nowMillis);
    }

    /**
     * Records a click with an additional, item-specific player-inventory proof.
     *
     * <p>This is required for QUICK_MOVE from a container.  A furnace output can
     * be consumed and then refill with an identical stack before the next
     * executor poll, leaving both the cursor and source slot byte-for-byte
     * unchanged.  The correlated player-count delta still proves that the
     * transfer happened.  Changes to any other item, or a smaller-than-expected
     * delta, do not release the acknowledgement fence.</p>
     */
    public void recordClick(
            String owner,
            InventorySnapshot before,
            List<Integer> watchedSlots,
            PlayerCountDelta playerCountDelta,
            long nowMillis) {
        recordClick(owner, before, watchedSlots, playerCountDelta, null, null, nowMillis);
    }

    /**
     * Records a click whose acknowledgement must include one exact container
     * slot delta.
     *
     * <p>A cursor-to-furnace click changes the client cursor optimistically.
     * Treating that cursor change alone as server acknowledgement creates a
     * short window where the item is in neither the player inventory nor the
     * last confirmed furnace slot.  Production then falsely reports the input
     * as lost and recompiles the committed program.  A supplied slot proof
     * makes the destination change, rather than the disappearing cursor, the
     * acknowledgement boundary.</p>
     */
    public void recordClick(
            String owner,
            InventorySnapshot before,
            List<Integer> watchedSlots,
            PlayerCountDelta playerCountDelta,
            SlotCountDelta requiredSlotDelta,
            long nowMillis) {
        recordClick(
                owner, before, watchedSlots, playerCountDelta,
                requiredSlotDelta, null, nowMillis);
    }

    /**
     * Records an exact container deposit with an optional typed proof that one
     * fuel item was accepted and immediately converted into furnace burn time.
     */
    public void recordClick(
            String owner,
            InventorySnapshot before,
            List<Integer> watchedSlots,
            PlayerCountDelta playerCountDelta,
            SlotCountDelta requiredSlotDelta,
            FurnaceFuelConsumptionProof furnaceFuelConsumptionProof,
            long nowMillis) {
        if (pendingClick != null) {
            throw new IllegalStateException("an inventory click is already awaiting acknowledgement");
        }
        String normalizedOwner = owner(owner);
        if (furnaceFuelConsumptionProof != null) {
            if (!furnaceFuelConsumptionProof.owner().equals(normalizedOwner)) {
                throw new IllegalArgumentException("fuel proof owner does not match click owner");
            }
            if (furnaceFuelConsumptionProof.syncId() != before.syncId()) {
                throw new IllegalArgumentException("fuel proof handler does not match click handler");
            }
            if (requiredSlotDelta == null
                    || requiredSlotDelta.slotId() != furnaceFuelConsumptionProof.destinationSlot()
                    || !requiredSlotDelta.itemId().equals(
                    furnaceFuelConsumptionProof.itemId())) {
                throw new IllegalArgumentException(
                        "fuel proof must match the exact required destination delta");
            }
        }
        ArrayList<Integer> watched = new ArrayList<>();
        if (watchedSlots != null) {
            for (Integer slot : watchedSlots) {
                if (slot != null && slot >= 0 && !watched.contains(slot)) watched.add(slot);
            }
        }
        pendingClick = new PendingClick(
                normalizedOwner, before, List.copyOf(watched), playerCountDelta,
                requiredSlotDelta, furnaceFuelConsumptionProof, nowMillis);
        nextClickAt = nowMillis + clickIntervalMillis;
    }

    /** Claims an in-flight cursor stack for an exact source/destination transfer. */
    public CursorTransfer claimCursorTransfer(
            String owner,
            int syncId,
            int sourceSlot,
            int destinationSlot,
            String itemId,
            int desiredCount,
            long nowMillis) {
        CursorTransfer next = new CursorTransfer(
                owner(owner), syncId, sourceSlot, destinationSlot,
                InventorySnapshot.normalize(itemId), desiredCount, nowMillis);
        if (cursorTransfer != null && cursorTransfer.syncId() == syncId) {
            boolean sameTransfer = cursorTransfer.owner().equals(next.owner())
                    && cursorTransfer.sourceSlot() == next.sourceSlot()
                    && cursorTransfer.destinationSlot() == next.destinationSlot()
                    && cursorTransfer.itemId().equals(next.itemId())
                    && cursorTransfer.desiredCount() == next.desiredCount();
            if (!sameTransfer) {
                throw new IllegalStateException(
                        "cursor is already owned by " + cursorTransfer.owner());
            }
            return cursorTransfer;
        }
        cursorTransfer = next;
        return next;
    }

    public Optional<CursorTransfer> cursorTransfer(
            int syncId,
            int destinationSlot,
            String itemId) {
        if (cursorTransfer == null || cursorTransfer.syncId() != syncId) return Optional.empty();
        if (destinationSlot >= 0 && cursorTransfer.destinationSlot() != destinationSlot) {
            return Optional.empty();
        }
        if (!cursorTransfer.itemId().equals(InventorySnapshot.normalize(itemId))) {
            return Optional.empty();
        }
        return Optional.of(cursorTransfer);
    }

    /**
     * Read-only proof that an already-established in-process cursor custody
     * belongs to one exact transfer. This never creates or adopts custody:
     * callers must claim while the cursor is still empty, before issuing the
     * source pickup.
     */
    public Optional<CursorTransfer> exactCursorTransfer(
            String owner,
            int syncId,
            int sourceSlot,
            int destinationSlot,
            String itemId,
            int desiredCount) {
        if (cursorTransfer == null) return Optional.empty();
        String normalizedOwner = owner(owner);
        String normalizedItem = InventorySnapshot.normalize(itemId);
        if (!cursorTransfer.owner().equals(normalizedOwner)
                || cursorTransfer.syncId() != syncId
                || cursorTransfer.sourceSlot() != sourceSlot
                || cursorTransfer.destinationSlot() != destinationSlot
                || !cursorTransfer.itemId().equals(normalizedItem)
                || cursorTransfer.desiredCount() != desiredCount) {
            return Optional.empty();
        }
        return Optional.of(cursorTransfer);
    }

    public Optional<CursorTransfer> cursorTransfer() {
        return Optional.ofNullable(cursorTransfer);
    }

    public void releaseCursorTransfer(String owner) {
        if (cursorTransfer != null && cursorTransfer.owner().equals(owner(owner))) {
            cursorTransfer = null;
        }
    }

    /** Begins proof of an expected player-inventory removal, such as a handoff. */
    public void beginRemoval(
            String owner,
            String itemId,
            int baselineCount,
            int expectedCount,
            long nowMillis) {
        if (baselineCount < 0 || expectedCount < 1) {
            throw new IllegalArgumentException("invalid removal transaction counts");
        }
        String key = owner(owner);
        removals.put(key, new RemovalTransaction(
                key,
                InventorySnapshot.normalize(itemId),
                baselineCount,
                expectedCount,
                nowMillis,
                false));
    }

    public RemovalObservation observeRemoval(
            String owner,
            String itemId,
            int currentCount,
            long nowMillis,
            long timeoutMillis) {
        String key = owner(owner);
        RemovalTransaction transaction = removals.get(key);
        if (transaction == null
                || !transaction.itemId().equals(InventorySnapshot.normalize(itemId))) {
            return RemovalObservation.NOT_FOUND;
        }
        int expectedMaximum = Math.max(
                0, transaction.baselineCount() - transaction.expectedCount());
        boolean decreased = transaction.sawDecrease() || currentCount <= expectedMaximum;
        if (decreased != transaction.sawDecrease()) {
            transaction = transaction.withSawDecrease(true);
            removals.put(key, transaction);
        }
        if (decreased && currentCount >= transaction.baselineCount()) {
            removals.remove(key);
            return RemovalObservation.RESTORED;
        }
        long age = Math.max(0L, nowMillis - transaction.startedAt());
        if (age > Math.max(1L, timeoutMillis)) {
            removals.remove(key);
            return decreased
                    ? RemovalObservation.TIMED_OUT_AFTER_DECREASE
                    : RemovalObservation.TIMED_OUT_UNCHANGED;
        }
        return decreased ? RemovalObservation.DECREASED : RemovalObservation.UNCHANGED;
    }

    public void clearRemoval(String owner) {
        removals.remove(owner(owner));
    }

    /** Cancels GUI ownership without discarding longer-lived delivery proof. */
    public void resetInteractive() {
        pendingClick = null;
        cursorTransfer = null;
        nextClickAt = 0L;
    }

    public void resetAll() {
        resetInteractive();
        removals.clear();
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(
                pendingClick == null ? "" : pendingClick.owner(),
                cursorTransfer == null ? "" : cursorTransfer.owner(),
                removals.size(),
                acknowledgedClicks,
                timedOutClicks,
                lastTimeout);
    }

    private void reconcileHandler(int currentSyncId) {
        if (pendingClick != null && pendingClick.before().syncId() != currentSyncId) {
            pendingClick = null;
        }
        if (cursorTransfer != null && cursorTransfer.syncId() != currentSyncId) {
            cursorTransfer = null;
        }
    }

    private static String owner(String owner) {
        String value = Objects.requireNonNullElse(owner, "").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) throw new IllegalArgumentException("transaction owner is required");
        return value;
    }

    public enum Gate {
        READY,
        READY_AFTER_TIMEOUT,
        WAITING_FOR_INTERVAL,
        WAITING_FOR_ACKNOWLEDGEMENT,
        WAITING_FOR_OTHER_OWNER;

        public boolean permitsClick() {
            return this == READY || this == READY_AFTER_TIMEOUT;
        }
    }

    public enum RemovalObservation {
        NOT_FOUND,
        UNCHANGED,
        DECREASED,
        RESTORED,
        TIMED_OUT_UNCHANGED,
        TIMED_OUT_AFTER_DECREASE
    }

    private record PendingClick(
            String owner,
            InventorySnapshot before,
            List<Integer> watchedSlots,
            PlayerCountDelta playerCountDelta,
            SlotCountDelta requiredSlotDelta,
            FurnaceFuelConsumptionProof furnaceFuelConsumptionProof,
            long startedAt) {
    }

    /**
     * Minimum signed change expected in one exact item across player inventory.
     * Positive values prove container-to-player movement; negative values prove
     * player-to-container movement.
     */
    public record PlayerCountDelta(String itemId, int minimumSignedDelta) {
        public PlayerCountDelta {
            itemId = InventorySnapshot.normalize(itemId);
            if (itemId.equals("minecraft:air")) {
                throw new IllegalArgumentException("player-count proof requires an item");
            }
            if (minimumSignedDelta == 0) {
                throw new IllegalArgumentException("player-count proof requires a non-zero delta");
            }
        }

        public static PlayerCountDelta increase(String itemId, int minimumCount) {
            if (minimumCount < 1) {
                throw new IllegalArgumentException("minimum increase must be positive");
            }
            return new PlayerCountDelta(itemId, minimumCount);
        }

        public static PlayerCountDelta decrease(String itemId, int minimumCount) {
            if (minimumCount < 1) {
                throw new IllegalArgumentException("minimum decrease must be positive");
            }
            return new PlayerCountDelta(itemId, -minimumCount);
        }

        boolean acknowledges(InventorySnapshot before, InventorySnapshot current) {
            int delta = current.playerCount(itemId) - before.playerCount(itemId);
            return minimumSignedDelta > 0
                    ? delta >= minimumSignedDelta
                    : delta <= minimumSignedDelta;
        }
    }

    /** Exact item/count proof for a cursor deposit into one container slot. */
    public record SlotCountDelta(int slotId, String itemId, int minimumIncrease) {
        public SlotCountDelta {
            if (slotId < 0) throw new IllegalArgumentException("slot id cannot be negative");
            itemId = InventorySnapshot.normalize(itemId);
            if (itemId.equals("minecraft:air")) {
                throw new IllegalArgumentException("slot proof requires an item");
            }
            if (minimumIncrease < 1) {
                throw new IllegalArgumentException("slot increase must be positive");
            }
        }

        public static SlotCountDelta increase(
                int slotId, String itemId, int minimumIncrease) {
            return new SlotCountDelta(slotId, itemId, minimumIncrease);
        }

        boolean acknowledges(InventorySnapshot before, InventorySnapshot current) {
            if (current.syncId() != before.syncId()) return false;
            InventorySnapshot.StackView previous = before.slot(slotId)
                    .map(InventorySnapshot.SlotView::stack)
                    .orElse(InventorySnapshot.StackView.empty());
            InventorySnapshot.StackView observed = current.slot(slotId)
                    .map(InventorySnapshot.SlotView::stack)
                    .orElse(InventorySnapshot.StackView.empty());
            int previousCount = previous.itemId().equals(itemId)
                    ? previous.count() : 0;
            return observed.itemId().equals(itemId)
                    && observed.count() >= previousCount + minimumIncrease;
        }
    }

    /**
     * Exact alternative acknowledgement for a one-item furnace-fuel deposit.
     *
     * <p>The server can consume the deposited item before a later client tick
     * observes it in slot 1.  This proof is deliberately narrower than cursor
     * disappearance: it requires the same transaction owner and handler, the
     * fuel destination, the exact cursor item at click time, and an observed
     * false-to-true furnace-burning transition.</p>
     */
    public record FurnaceFuelConsumptionProof(
            String owner,
            int syncId,
            int destinationSlot,
            String itemId) {
        public FurnaceFuelConsumptionProof {
            owner = InventoryTransactionEngine.owner(owner);
            if (syncId < 0) throw new IllegalArgumentException("fuel proof sync id is invalid");
            if (destinationSlot != 1) {
                throw new IllegalArgumentException("consumed fuel proof requires furnace slot 1");
            }
            itemId = InventorySnapshot.normalize(itemId);
            if (itemId.equals("minecraft:air")) {
                throw new IllegalArgumentException("consumed fuel proof requires an item");
            }
        }

        boolean acknowledges(
                String requestingOwner,
                InventorySnapshot before,
                InventorySnapshot current) {
            if (!owner.equals(requestingOwner)
                    || before.syncId() != syncId
                    || current.syncId() != syncId) {
                return false;
            }
            InventorySnapshot.FurnaceState previousState = before.furnaceState();
            InventorySnapshot.FurnaceState currentState = current.furnaceState();
            if (!previousState.available() || !currentState.available()
                    || previousState.burning() || !currentState.burning()) {
                return false;
            }
            InventorySnapshot.StackView cursor = before.cursor();
            if (!cursor.itemId().equals(itemId) || cursor.count() < 1) return false;
            InventorySnapshot.StackView previousDestination = before.slot(destinationSlot)
                    .map(InventorySnapshot.SlotView::stack)
                    .orElse(InventorySnapshot.StackView.empty());
            return previousDestination.isEmpty();
        }
    }

    public record CursorTransfer(
            String owner,
            int syncId,
            int sourceSlot,
            int destinationSlot,
            String itemId,
            int desiredCount,
            long startedAt) {
        public CursorTransfer {
            owner = InventoryTransactionEngine.owner(owner);
            if (sourceSlot < 0 || destinationSlot < 0) {
                throw new IllegalArgumentException("cursor transfer slots cannot be negative");
            }
            itemId = InventorySnapshot.normalize(itemId);
            if (desiredCount < 0) {
                throw new IllegalArgumentException("desired transfer count cannot be negative");
            }
        }
    }

    private record RemovalTransaction(
            String owner,
            String itemId,
            int baselineCount,
            int expectedCount,
            long startedAt,
            boolean sawDecrease) {
        private RemovalTransaction withSawDecrease(boolean value) {
            return new RemovalTransaction(
                    owner, itemId, baselineCount, expectedCount, startedAt, value);
        }
    }

    public record Diagnostics(
            String pendingClickOwner,
            String cursorOwner,
            int pendingRemovals,
            long acknowledgedClicks,
            long timedOutClicks,
            ClickTimeout lastTimeout) {
    }

    /** Exact, durable-in-process location of the most recently expired click fence. */
    public record ClickTimeout(
            long sequence,
            long detectedAtMillis,
            long startedAtMillis,
            String owner,
            int handlerSyncId,
            List<Integer> watchedSlots) {
        public ClickTimeout {
            if (sequence < 1L || detectedAtMillis < 0L || startedAtMillis < 0L) {
                throw new IllegalArgumentException("invalid inventory timeout identity");
            }
            owner = InventoryTransactionEngine.owner(owner);
            watchedSlots = List.copyOf(Objects.requireNonNull(watchedSlots, "watchedSlots"));
        }
    }
}
