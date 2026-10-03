package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static dev.entity.client.autonomy.policy.PersonalSuppliesPolicy.*;

/**
 * In-memory authorization for one exact cleanup preview. The physical adapter owns navigation,
 * safe drop stance, cursor/action leases, durable issued receipts and actual drop observations.
 * Nothing in this class searches for another same-item stack or starts work when settings load.
 */
public final class TidySession {
    public enum Phase { EMPTY, PREVIEW, APPROVED, ISSUED, OBSERVED, COMPLETE, CANCELLED }

    public record Scope(String worldId, String homeFingerprint, long homeGeneration,
                        long commandGeneration, long settingsRevision) {
        public Scope {
            worldId = TidySettingsStore.world(worldId);
            homeFingerprint = Objects.requireNonNull(homeFingerprint, "homeFingerprint");
            if (homeFingerprint.length() > 256 || homeGeneration < 0 || commandGeneration < 0 || settingsRevision < 0) {
                throw new IllegalArgumentException("invalid tidy scope");
            }
        }
    }

    /** Empty storageId means carried; nonempty IDs must come from the registered-storage adapter. */
    public record ApprovedStack(Selection selection, String storageId) {
        public ApprovedStack {
            Objects.requireNonNull(selection, "selection");
            storageId = Objects.requireNonNull(storageId, "storageId");
        }
        public boolean carried() { return storageId.isEmpty(); }
    }

    public record Preview(String id, Scope scope, boolean includeStorage,
                          List<CleanupSlice> slices, List<ApprovedStack> discard) {
        public Preview {
            id = TidySettingsStore.text(id, "preview ID", 256);
            Objects.requireNonNull(scope, "scope");
            slices = List.copyOf(slices);
            discard = List.copyOf(discard);
        }
    }

    /** original never changes; physical becomes nonnull only after an exact storage withdrawal. */
    public record Next(int index, ApprovedStack original, Selection physical) { }
    public record IssuedDrop(String receiptId, String previewId, int index, Selection selection) { }
    public record IssueResult(IssuedDrop drop, boolean issueNow) { }
    public record Receipt(IssuedDrop drop, int observedCount, boolean complete) { }
    public record Status(Phase phase, Preview preview, int observedSelections,
                         Receipt pendingReceipt, String detail) { }

    /**
     * A settled physical transfer receipt. after may be null only for an emptied source; before
     * may be null only for an empty carried slot. All facts include exact components/durability.
     */
    public record Withdrawal(String receiptId, Selection sourceBefore, StackFact sourceAfter,
                             StackFact playerBefore, StackFact playerAfter) {
        public Withdrawal {
            receiptId = TidySettingsStore.text(receiptId, "withdrawal receipt ID", 256);
            Objects.requireNonNull(sourceBefore, "sourceBefore");
            Objects.requireNonNull(playerAfter, "playerAfter");
        }
    }

    private Phase phase = Phase.EMPTY;
    private Preview preview;
    private int index;
    private final Map<Integer, Selection> physical = new LinkedHashMap<>();
    private String detail = "";
    private String pendingReceiptId;
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private final Map<String, Integer> abandonedReceipts = new LinkedHashMap<>();
    private final Map<String, Withdrawal> withdrawals = new LinkedHashMap<>();

    public synchronized Status status() {
        return new Status(phase, preview, index,
                pendingReceiptId == null ? null : receipts.get(pendingReceiptId), detail);
    }

    /**
     * allocation is the existing shared personal/transaction allocation, not another junk model.
     * storageByStackId explicitly identifies the observed registered-storage slots in that view.
     * The adapter must not omit a storage slot's origin or add unregistered container contents.
     */
    public synchronized Preview preview(String id, Scope scope, TidySettingsStore.Settings settings,
                                        Allocation allocation, Map<String, String> storageByStackId,
                                        boolean includeStorage, boolean actualFuelNeed) {
        if (pendingReceiptId != null) throw new IllegalStateException("observe the already-issued drop first");
        validateSettings(scope, settings);
        preview = makePreview(id, scope, settings, allocation, storageByStackId, includeStorage, actualFuelNeed);
        index = 0;
        physical.clear();
        detail = "";
        phase = Phase.PREVIEW;
        return preview;
    }

    /** Recompute with current reservation/tool facts, then require the same exact approved list. */
    public synchronized boolean confirm(String id, Scope scope, TidySettingsStore.Settings settings,
                                        Allocation allocation, Map<String, String> storageByStackId,
                                        boolean actualFuelNeed) {
        if (!fence(scope) || phase != Phase.PREVIEW || !preview.id().equals(id)) return false;
        validateSettings(scope, settings);
        Preview current = makePreview(id, scope, settings, allocation, storageByStackId,
                preview.includeStorage(), actualFuelNeed);
        if (!preview.discard().equals(current.discard())) {
            cancel("Inventory or cleanup allocation changed; make a new preview");
            return false;
        }
        if (!settings.hasSpot(scope.worldId(), scope.homeFingerprint())) {
            detail = "Confirm a disposal spot for this Home first";
            return false;
        }
        phase = preview.discard().isEmpty() ? Phase.COMPLETE : Phase.APPROVED;
        for (int slot = 0; slot < preview.discard().size(); slot++) {
            ApprovedStack selected = preview.discard().get(slot);
            if (selected.carried()) physical.put(slot, selected.selection());
        }
        detail = "";
        return true;
    }

    public synchronized Optional<Next> next(Scope scope) {
        if (!fence(scope) || !readyPhase()) return Optional.empty();
        return Optional.of(new Next(index, preview.discard().get(index), physical.get(index)));
    }

    /** One next exact source for a capacity-bounded batch, never authority to drop out of order. */
    public synchronized Optional<Next> nextWithdrawal(Scope scope, int emptyCarriedSlots) {
        if (!fence(scope) || !readyPhase() || emptyCarriedSlots <= 0) return Optional.empty();
        for (int candidate = index; candidate < preview.discard().size(); candidate++) {
            ApprovedStack selected = preview.discard().get(candidate);
            if (!selected.carried() && !physical.containsKey(candidate)) {
                return Optional.of(new Next(candidate, selected, null));
            }
        }
        return Optional.empty();
    }

    /**
     * Rebind only the approved source's proved count delta to one exact carried slot. A matching
     * item ID or an aggregate inventory increase is not a transfer receipt. Merging into an
     * existing identical carried stack is allowed, but only the observed transferred count is selected.
     */
    public synchronized Selection rebound(Scope scope, Withdrawal observed) {
        return rebound(scope, index, observed);
    }

    public synchronized Selection rebound(Scope scope, int selectedIndex, Withdrawal observed) {
        Objects.requireNonNull(observed, "observed");
        if (!fence(scope) || !readyPhase()) throw new IllegalStateException("cleanup is not approved");
        if (selectedIndex < index || selectedIndex >= preview.discard().size()) {
            throw new IllegalArgumentException("withdrawal is not an outstanding approved selection");
        }
        ApprovedStack original = preview.discard().get(selectedIndex);
        Withdrawal previous = withdrawals.get(observed.receiptId());
        if (previous != null) {
            if (!previous.equals(observed) || !original.selection().equals(observed.sourceBefore()) || !physical.containsKey(selectedIndex)) {
                throw new IllegalArgumentException("withdrawal receipt changed or belongs to another selection");
            }
            return physical.get(selectedIndex);
        }
        if (original.carried() || physical.containsKey(selectedIndex) || !original.selection().equals(observed.sourceBefore())) {
            throw new IllegalArgumentException("withdrawal does not match the approved storage stack");
        }
        validateWithdrawal(observed);
        if (physical.values().stream().anyMatch(selection -> selection.stack().stackId().equals(observed.playerAfter().stackId()))) {
            throw new IllegalArgumentException("another approved selection already owns this carried slot");
        }
        Selection carried = new Selection(observed.playerAfter(), original.selection().count());
        physical.put(selectedIndex, carried);
        withdrawals.put(observed.receiptId(), observed);
        return carried;
    }

    /**
     * issueNow is true ONCE. Persist/pass this exact receipt to the physical adapter before sending
     * a drop; every retry of its ID returns false, even after observation/cancellation. Never infer
     * permission to send again from the presence of drop(). Revalidate the shared allocation now.
     */
    public synchronized IssueResult issue(String receiptId, Scope scope, TidySettingsStore.Settings settings,
                                          Allocation currentAllocation, boolean actualFuelNeed) {
        String id = TidySettingsStore.text(receiptId, "drop receipt ID", 256);
        fence(scope);
        Receipt previous = receipts.get(id);
        if (previous != null) return new IssueResult(previous.drop(), false);
        if (!readyPhase()) throw new IllegalStateException("cleanup is not approved for another drop");
        validateSettings(scope, settings);
        if (!settings.hasSpot(scope.worldId(), scope.homeFingerprint())) {
            cancel("The confirmed disposal spot no longer belongs to this Home");
            throw new IllegalStateException(detail);
        }
        Selection selected = physical.get(index);
        if (selected == null) throw new IllegalStateException("observe the exact storage withdrawal first");
        boolean stillPermitted = previewCleanup(currentAllocation, settings.junkItems(), settings.keepItems(), actualFuelNeed)
                .stream().anyMatch(slice -> slice.action() == CleanupAction.DISCARD_CANDIDATE
                        && slice.selection().stack().equals(selected.stack())
                        && slice.selection().count() >= selected.count());
        if (!stillPermitted) {
            cancel("The approved physical stack or its retention changed; make a new preview");
            throw new IllegalStateException(detail);
        }
        IssuedDrop drop = new IssuedDrop(id, preview.id(), index, selected);
        receipts.put(id, new Receipt(drop, 0, false));
        pendingReceiptId = id;
        phase = Phase.ISSUED;
        return new IssueResult(drop, true);
    }

    /**
     * The adapter must correlate this cumulative removal to the issued ordinary-drop receipt,
     * not merely a lower item-ID count. Partial/unchanged observations never permit another drop.
     * An issued receipt may settle after Stop, but cannot revive the cancelled remaining work.
     */
    public synchronized boolean observe(IssuedDrop issued, int cumulativeRemoved) {
        Objects.requireNonNull(issued, "issued");
        Receipt receipt = receipts.get(issued.receiptId());
        if (receipt == null || !receipt.drop().equals(issued)) throw new IllegalArgumentException("unknown exact drop receipt");
        if (cumulativeRemoved < 0 || cumulativeRemoved > issued.selection().count()) {
            throw new IllegalArgumentException("invalid observed removal count");
        }
        Integer abandoned = abandonedReceipts.get(issued.receiptId());
        if (abandoned != null) {
            if (cumulativeRemoved > abandoned) throw new IllegalArgumentException("settled abandoned receipt changed");
            return receipt.complete();
        }
        if (receipt.complete()) return true;
        int observed = Math.max(cumulativeRemoved, receipt.observedCount());
        boolean complete = observed == issued.selection().count();
        receipts.put(issued.receiptId(), new Receipt(issued, observed, complete));
        if (!complete) return false;
        if (!issued.receiptId().equals(pendingReceiptId)) throw new IllegalStateException("receipt is not the outstanding drop");
        pendingReceiptId = null;
        physical.remove(index);
        index++;
        if (phase != Phase.CANCELLED) {
            phase = index == preview.discard().size() ? Phase.COMPLETE : Phase.OBSERVED;
        }
        return true;
    }

    /**
     * Called ONLY after the physical adapter reports terminal and its click/cursor debt is settled.
     * Retains the cumulative receipt and fences its unissued suffix forever, while allowing a NEW
     * explicit preview. This is not permission to retry an uncertain physical action.
     */
    public synchronized void abandon(IssuedDrop issued, int cumulativeRemoved, String reason) {
        Objects.requireNonNull(issued, "issued");
        Receipt receipt = receipts.get(issued.receiptId());
        if (receipt == null || !receipt.drop().equals(issued)) throw new IllegalArgumentException("unknown exact drop receipt");
        if (cumulativeRemoved < receipt.observedCount() || cumulativeRemoved > issued.selection().count()) {
            throw new IllegalArgumentException("invalid terminal removal count");
        }
        Integer previous = abandonedReceipts.get(issued.receiptId());
        if (previous != null) {
            if (previous != cumulativeRemoved) throw new IllegalArgumentException("settled abandoned receipt changed");
            return;
        }
        if (!issued.receiptId().equals(pendingReceiptId) && !receipt.complete()) {
            throw new IllegalArgumentException("receipt is not the outstanding drop");
        }
        receipts.put(issued.receiptId(), new Receipt(issued, cumulativeRemoved,
                cumulativeRemoved == issued.selection().count()));
        abandonedReceipts.put(issued.receiptId(), cumulativeRemoved);
        if (issued.receiptId().equals(pendingReceiptId)) pendingReceiptId = null;
        cancel(reason);
    }

    /** Keeps an already-issued receipt observable; fences every unissued action immediately. */
    public synchronized void cancel(String reason) {
        phase = Phase.CANCELLED;
        physical.clear();
        detail = Objects.requireNonNullElse(reason, "Cleanup cancelled");
    }

    public synchronized boolean fence(Scope current) {
        Objects.requireNonNull(current, "current");
        if (preview == null || !preview.scope().equals(current)) {
            if (preview != null) cancel("World, Home, command or settings generation changed");
            return false;
        }
        return phase != Phase.CANCELLED;
    }

    private boolean readyPhase() { return phase == Phase.APPROVED || phase == Phase.OBSERVED; }

    private static void validateSettings(Scope scope, TidySettingsStore.Settings settings) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(settings, "settings");
        if (!scope.worldId().equals(settings.worldId()) || scope.settingsRevision() != settings.revision()) {
            throw new IllegalArgumentException("settings do not match the current tidy scope");
        }
    }

    private static Preview makePreview(String id, Scope scope, TidySettingsStore.Settings settings,
                                       Allocation allocation, Map<String, String> storageByStackId,
                                       boolean includeStorage, boolean actualFuelNeed) {
        Objects.requireNonNull(storageByStackId, "storageByStackId");
        for (Map.Entry<String, String> entry : storageByStackId.entrySet()) {
            TidySettingsStore.text(entry.getKey(), "storage stack ID", 1024);
            TidySettingsStore.text(entry.getValue(), "registered storage ID", 256);
        }
        List<CleanupSlice> slices = new ArrayList<>();
        List<ApprovedStack> discard = new ArrayList<>();
        Map<String, StackFact> physicalSlots = new LinkedHashMap<>();
        for (StackAllocation stack : allocation.stacks()) {
            if (physicalSlots.putIfAbsent(stack.stack().stackId(), stack.stack()) != null) {
                throw new IllegalArgumentException("duplicate physical stack in cleanup allocation");
            }
        }
        for (CleanupSlice slice : previewCleanup(allocation, settings.junkItems(), settings.keepItems(), actualFuelNeed)) {
            String storage = storageByStackId.getOrDefault(slice.selection().stack().stackId(), "");
            if (!includeStorage && !storage.isEmpty()) continue;
            slices.add(slice);
            if (slice.action() == CleanupAction.DISCARD_CANDIDATE) discard.add(new ApprovedStack(slice.selection(), storage));
        }
        return new Preview(id, scope, includeStorage, slices, discard);
    }

    private static void validateWithdrawal(Withdrawal receipt) {
        Selection selected = receipt.sourceBefore();
        StackFact source = selected.stack();
        StackFact after = receipt.sourceAfter();
        int remaining = source.count() - selected.count();
        if (remaining == 0 ? after != null : after == null || after.count() != remaining
                || !source.stackId().equals(after.stackId()) || !sameContents(source, after)) {
            throw new IllegalArgumentException("source removal does not match the approved selection");
        }
        StackFact before = receipt.playerBefore();
        StackFact carried = receipt.playerAfter();
        if (source.stackId().equals(carried.stackId()) || !sameContents(source, carried)
                || carried.count() != (long) (before == null ? 0 : before.count()) + selected.count()
                || (before != null && (!before.stackId().equals(carried.stackId()) || !sameContents(source, before)))) {
            throw new IllegalArgumentException("carried stack does not prove the exact withdrawal");
        }
    }

    /** Count and physical slot may change only at a proved withdrawal, never components or tool state. */
    private static boolean sameContents(StackFact left, StackFact right) {
        return left.item().equals(right.item()) && left.componentsKey().equals(right.componentsKey())
                && left.remainingDurability() == right.remainingDurability()
                && left.capabilities().equals(right.capabilities()) && left.custom() == right.custom()
                && left.equipped() == right.equipped() && left.fuelCompatible() == right.fuelCompatible()
                && left.foodNutrition() == right.foodNutrition();
    }
}
