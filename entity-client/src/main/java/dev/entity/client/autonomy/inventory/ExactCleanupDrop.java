package dev.entity.client.autonomy.inventory;

import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy.Selection;
import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy.StackFact;

import java.util.Locale;
import java.util.Objects;
import java.util.function.IntPredicate;

/** A single explicitly authorized stack drop, using the existing inventory/removal owner. */
public final class ExactCleanupDrop {
    public enum State { WAITING, ISSUED, COMPLETE, STOPPED, STALE, REAPPEARED, TIMED_OUT }
    public record Result(State state, int observedDropped, int issuedCount, String detail) { }

    private final String owner;
    private final Selection selection;
    private int baseline = -1;
    private int syncId = -1;
    private int observed;
    private int pending;
    private long issuedAt;
    private boolean stopped;
    private State terminal;
    private String detail = "";

    public ExactCleanupDrop(String owner, Selection selection) {
        this.owner = Objects.requireNonNull(owner, "owner").trim().toLowerCase(Locale.ROOT);
        if (this.owner.isEmpty()) throw new IllegalArgumentException("drop owner required");
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    public boolean matches(String owner, Selection selection) {
        return this.owner.equals(Objects.requireNonNull(owner).trim().toLowerCase(Locale.ROOT))
                && this.selection.equals(selection);
    }

    public boolean unsettled() { return pending > 0; }

    /**
     * throwClick receives vanilla THROW button 1 (whole stack) or 0 (one item).
     * It must issue and record the click through the supplied transaction engine.
     * Passing authorized=false is a permanent fence for this operation, including after Stop.
     */
    public Result tick(StackFact current, InventorySnapshot inventory, int handlerSlot,
            boolean authorized, long now, InventoryTransactionEngine transactions, IntPredicate throwClick) {
        return tick(current, inventory, handlerSlot, authorized, true, now, transactions, throwClick);
    }

    /** Passive observations do not consume/cancel authorization and can never send another THROW. */
    public Result tick(StackFact current, InventorySnapshot inventory, int handlerSlot,
            boolean authorized, boolean allowNewClick, long now, InventoryTransactionEngine transactions,
            IntPredicate throwClick) {
        Objects.requireNonNull(inventory, "inventory");
        if (!authorized) stopped = true;
        int rawCount = inventory.playerCount(selection.stack().item());
        if (terminal != null) {
            if (terminal == State.COMPLETE && rawCount > baseline - observed) {
                terminal = State.REAPPEARED;
                detail = "same item reappeared after observed drop; never replay this authorization";
            }
            return result(terminal, 0);
        }
        if (syncId >= 0 && inventory.syncId() != syncId) {
            return finish(State.STALE, "drop handler changed; issued action cannot be replayed", transactions);
        }
        var gate = transactions.gate(owner, inventory, now);
        if (pending > 0) {
            int expectedCount = selection.stack().count() - observed - pending;
            boolean exactAfter = matchesRemaining(current, expectedCount)
                    && rawCount == baseline - observed - pending;
            boolean exactBefore = matchesRemaining(current, expectedCount + pending)
                    && rawCount == baseline - observed;
            var removal = transactions.observeRemoval(owner, selection.stack().item(), rawCount, now, 2_000L);
            if (removal == InventoryTransactionEngine.RemovalObservation.RESTORED) {
                return finish(State.REAPPEARED, "issued drop was restored; no repeated disposal", transactions);
            }
            if (!exactAfter && !exactBefore) {
                return finish(State.STALE, "selected slot/components or raw removal count changed", transactions);
            }
            if (exactAfter && gate == InventoryTransactionEngine.Gate.READY
                    && now - issuedAt >= InventoryTransactionEngine.DEFAULT_SEMANTIC_STABILITY_MILLIS) {
                observed += pending;
                pending = 0;
                transactions.clearRemoval(owner);
                if (observed == selection.count()) {
                    return finish(State.COMPLETE, "exact selected quantity observed removed", transactions);
                }
            } else if (gate == InventoryTransactionEngine.Gate.READY_AFTER_TIMEOUT || now - issuedAt > 2_000L) {
                return finish(State.TIMED_OUT, "drop acknowledgement uncertain; never retry issued quantity", transactions);
            } else return result(State.WAITING, 0);
        }
        if (stopped) return finish(State.STOPPED, "Stop fenced remaining unissued quantity", transactions);
        if (baseline < 0) {
            if (!selection.stillMatches(current)) return finish(State.STALE, "preview stack changed", transactions);
            if (!transactions.diagnostics().cursorOwner().isBlank()
                    || transactions.diagnostics().pendingRemovals() != 0) return result(State.WAITING, 0);
            baseline = rawCount;
            syncId = inventory.syncId();
        }
        if (!matchesRemaining(current, selection.stack().count() - observed)
                || rawCount != baseline - observed || !inventory.cursor().isEmpty()) {
            return finish(State.STALE, "exact source quantity/components no longer match", transactions);
        }
        if (!allowNewClick || handlerSlot < 0 || !gate.permitsClick()) return result(State.WAITING, 0);
        int remaining = selection.count() - observed;
        int amount = current.count() == remaining ? remaining : 1;
        if (!throwClick.test(amount == current.count() ? 1 : 0)) return result(State.WAITING, 0);
        pending = amount;
        issuedAt = now;
        transactions.beginRemoval(owner, selection.stack().item(), rawCount, amount, now);
        return result(State.ISSUED, amount);
    }

    private boolean matchesRemaining(StackFact current, int expected) {
        if (expected == 0) return current == null;
        if (current == null) return false;
        StackFact before = selection.stack();
        return current.count() == expected && before.stackId().equals(current.stackId())
                && before.item().equals(current.item()) && before.componentsKey().equals(current.componentsKey())
                && before.remainingDurability() == current.remainingDurability()
                && before.custom() == current.custom() && before.equipped() == current.equipped();
    }

    private Result finish(State state, String detail, InventoryTransactionEngine transactions) {
        this.terminal = state;
        this.detail = detail;
        pending = 0;
        transactions.clearRemoval(owner);
        return result(state, 0);
    }

    private Result result(State state, int issued) { return new Result(state, observed, issued, detail); }
}
