package dev.entity.client.autonomy.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, Minecraft-independent view of one open inventory/container.
 *
 * <p>The client adapter captures this before every inventory mutation.  The
 * transaction engine can then distinguish a real acknowledgement from time
 * merely passing without depending on GUI focus or render state.</p>
 */
public record InventorySnapshot(
        int syncId,
        String handlerType,
        StackView cursor,
        Map<Integer, SlotView> slots,
        Map<String, Integer> playerCounts,
        FurnaceState furnaceState,
        int handlerRevision) {

    public static final int REVISION_UNAVAILABLE = -1;

    public InventorySnapshot {
        if (handlerRevision < REVISION_UNAVAILABLE) {
            throw new IllegalArgumentException("handler revision cannot be below -1");
        }
        handlerType = Objects.requireNonNullElse(handlerType, "unknown");
        cursor = cursor == null ? StackView.empty() : cursor;
        slots = Map.copyOf(slots == null ? Map.of() : new LinkedHashMap<>(slots));
        playerCounts = Map.copyOf(
                playerCounts == null ? Map.of() : new LinkedHashMap<>(playerCounts));
        furnaceState = furnaceState == null ? FurnaceState.unavailable() : furnaceState;
    }

    /**
     * Compatibility constructor for isolated adapters which cannot observe the
     * handler revision. Production client snapshots always use the canonical
     * constructor. A revision is useful when the server sends a correction or
     * resynchronization, but an accepted vanilla predicted click can produce no
     * revision change at all.
     */
    public InventorySnapshot(
            int syncId,
            String handlerType,
            StackView cursor,
            Map<Integer, SlotView> slots,
            Map<String, Integer> playerCounts,
            FurnaceState furnaceState) {
        this(syncId, handlerType, cursor, slots, playerCounts,
                furnaceState, REVISION_UNAVAILABLE);
    }

    /**
     * Backward-compatible constructor for non-furnace adapters and isolated
     * transaction tests.  An absent furnace observation can never prove that
     * deposited fuel was consumed.
     */
    public InventorySnapshot(
            int syncId,
            String handlerType,
            StackView cursor,
            Map<Integer, SlotView> slots,
            Map<String, Integer> playerCounts) {
        this(syncId, handlerType, cursor, slots, playerCounts,
                FurnaceState.unavailable(), REVISION_UNAVAILABLE);
    }

    /**
     * Whether a server-applied content update followed {@code before}.
     *
     * <p>{@code clickSlot} changes the client handler immediately without
     * increasing this revision. A later revision plus the exact semantic slot
     * proof can acknowledge immediately. An unchanged revision is deliberately
     * inconclusive: the transaction engine then requires the exact predicted
     * postcondition to remain stable through its correction window. The
     * unavailable fallback exists only for Minecraft-independent legacy
     * harnesses; the real client adapter always captures a revision.</p>
     */
    public boolean hasServerRevisionAfter(InventorySnapshot before) {
        Objects.requireNonNull(before, "before");
        if (syncId != before.syncId) return false;
        if (handlerRevision == REVISION_UNAVAILABLE
                || before.handlerRevision == REVISION_UNAVAILABLE) return true;
        return handlerRevision - before.handlerRevision > 0;
    }

    public Optional<SlotView> slot(int slotId) {
        return Optional.ofNullable(slots.get(slotId));
    }

    public int playerCount(String itemId) {
        return playerCounts.getOrDefault(normalize(itemId), 0);
    }

    /**
     * True only when the cursor or one explicitly watched slot changed.
     * Unrelated furnace progress or another animated container slot cannot
     * accidentally acknowledge a click.
     */
    public boolean acknowledges(InventorySnapshot before, List<Integer> watchedSlots) {
        Objects.requireNonNull(before, "before");
        if (syncId != before.syncId) return true;
        if (!cursor.equals(before.cursor)) return true;
        for (int slotId : watchedSlots) {
            if (!slot(slotId).equals(before.slot(slotId))) return true;
        }
        return false;
    }

    public static String normalize(String itemId) {
        String value = Objects.requireNonNullElse(itemId, "").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.equals("air")) return "minecraft:air";
        return value.contains(":") ? value : "minecraft:" + value;
    }

    public enum Domain {
        PLAYER,
        CONTAINER
    }

    /** Exact furnace state captured in the same handler snapshot as its slots. */
    public record FurnaceState(boolean available, boolean burning) {
        public static FurnaceState unavailable() {
            return new FurnaceState(false, false);
        }

        public static FurnaceState observed(boolean burning) {
            return new FurnaceState(true, burning);
        }
    }

    /**
     * Immutable identity for one stack at acknowledgement time.
     *
     * <p>Item id and count alone are not enough: swapping two equally-sized
     * pickaxe stacks can change only durability/components.  Capturing both
     * prevents a successfully acknowledged tool swap from being retried and
     * reversed after the timeout.</p>
     */
    public record StackView(String itemId, int count, int damage, int componentsHash) {
        public StackView {
            itemId = normalize(itemId);
            if (count < 0) throw new IllegalArgumentException("stack count cannot be negative");
            if (damage < 0) throw new IllegalArgumentException("stack damage cannot be negative");
            if (count == 0) {
                itemId = "minecraft:air";
                damage = 0;
                componentsHash = 0;
            }
        }

        public StackView(String itemId, int count) {
            this(itemId, count, 0, 0);
        }

        public static StackView empty() {
            return new StackView("minecraft:air", 0, 0, 0);
        }

        public boolean isEmpty() {
            return count == 0;
        }
    }

    public record SlotView(
            int slotId,
            int inventoryIndex,
            Domain domain,
            StackView stack) {
        public SlotView {
            if (slotId < 0) throw new IllegalArgumentException("slot id cannot be negative");
            domain = Objects.requireNonNull(domain, "domain");
            stack = stack == null ? StackView.empty() : stack;
        }
    }
}
