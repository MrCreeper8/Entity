package dev.entity.client.autonomy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.entity.client.autonomy.inventory.ExactCleanupDrop;
import dev.entity.client.autonomy.policy.HomeEconomySession;
import dev.entity.client.autonomy.policy.HomeEconomySession.ContainerIdentity;
import dev.entity.client.autonomy.policy.HomeEconomySession.StorageRegistration;
import dev.entity.client.autonomy.policy.InventoryReservationLedger;
import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy;
import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy.Allocation;
import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy.Selection;
import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy.StackFact;
import dev.entity.client.autonomy.policy.TidySession;
import dev.entity.client.autonomy.policy.TidySettingsStore;
import dev.entity.client.autonomy.policy.TidySettingsStore.DisposalSpot;
import dev.entity.client.baritone.FabricBaritonePort;
import dev.entity.client.bridge.BridgeResultPolicy;
import dev.entity.client.control.ActionLease;
import dev.entity.client.control.ActionOwner;
import dev.entity.client.control.ExecutionKernel;
import dev.entity.client.protection.ProtectedAreaClientState;
import dev.entity.client.runtime.WorldStateScope;
import dev.entity.core.control.ControlLease;
import dev.entity.core.port.BaritonePort;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Finite owner-requested cleanup under the existing Home idle lease; never an idle scheduler. */
public final class MinecraftTidyController {
    @FunctionalInterface public interface ActionGate {
        Optional<ActionLease> acquire(ControlLease parent, ActionOwner owner, String operation, long tick, long now);
    }
    public record Outcome(boolean retainLease, String detail) { }
    public record CommandResult(String detail, boolean requestedWork) { }
    public record SpotSelection(String nonce, DisposalSpot spot, long policyRevision, String policyDigest, long expiresAt) { }
    private enum Work { NONE, PREVIEW_HOME, CONFIRM_HOME, AUTO_HOME, EXECUTE, WAIT_DROPS, CANCELLED }
    private record Stored(StackFact fact, String storageId, ContainerIdentity identity, int slot) { }
    private record CommandCompletion(String id, boolean ok, String phase, String message,
                                     String previewId, long generation) { }
    private final MinecraftClient client;
    private final ClientInventoryController inventory;
    private final WorkstationController workstations;
    private final FabricBaritonePort baritone;
    private final ExecutionKernel kernel;
    private final ActionGate actions;
    private final Supplier<HomeEconomySession.Snapshot> home;
    private final Supplier<ProtectedAreaClientState> property;
    private final Supplier<List<InventoryReservationLedger.Reservation>> reservations;
    private final BooleanSupplier fuelNeed;
    private final String worldId;
    private final TidySettingsStore store;
    private final ReceiptJournal receipts;
    private final MinecraftTidyDropGeometry geometry;
    private TidySettingsStore.Settings settings;
    private final String loadProblem;
    private TidySession session = new TidySession();
    private Work work = Work.NONE;
    private long commandGeneration;
    private TidySession.Scope scope;
    private String previewId = "";
    private String asyncCommandId = "";
    private String announcedPreviewId = "";
    private Predicate<JsonObject> commandResultSender = ignored -> false;
    private final ArrayDeque<CommandCompletion> commandCompletions = new ArrayDeque<>();
    private boolean autoQueued;
    private boolean automaticWork;
    private SpotSelection spotPreview;
    private final Map<String, Stored> storage = new LinkedHashMap<>();
    private Map<String, Stored> approvedStorage = Map.of();
    private final Map<String, ContainerIdentity> storageIdentities = new LinkedHashMap<>();
    private Map<String, ContainerIdentity> approvedIdentities = Map.of();
    private final Map<String, Integer> droppedCounts = new LinkedHashMap<>();
    private String openingStorage = "";
    private List<StorageRegistration> visit = List.of();
    private int visitIndex;
    private boolean closeAfterObservation;
    private ClientInventoryController.CleanupWithdrawalPlan withdrawal;
    private String withdrawalOwner = "";
    private TidySession.Next withdrawing;
    private boolean collectingStorageBatch;
    private TidySession.IssuedDrop issued;
    private ExactCleanupDrop.Result lastDrop;
    private BlockPos stand;
    private int aimedAtAge = -1;
    private long settleUntil;
    private long workStarted;
    private long safetyPausedAt = -1;
    private String detail = "No cleanup preview. /e tidy previews carried stacks; /e tidy home includes registered storage.";

    public MinecraftTidyController(MinecraftClient client, ClientInventoryController inventory,
            WorkstationController workstations, FabricBaritonePort baritone, ExecutionKernel kernel,
            Path dataDirectory, Supplier<HomeEconomySession.Snapshot> home,
            Supplier<ProtectedAreaClientState> property,
            Supplier<List<InventoryReservationLedger.Reservation>> reservations,
            BooleanSupplier fuelNeed, ActionGate actions) {
        this.client = client; this.inventory = inventory; this.workstations = workstations;
        this.baritone = baritone; this.kernel = kernel; this.home = home; this.property = property;
        this.reservations = reservations; this.fuelNeed = fuelNeed; this.actions = actions;
        String scopedWorld;
        try {
            scopedWorld = WorldStateScope.parse(dataDirectory.toAbsolutePath().normalize().getFileName().toString()).key();
        } catch (IllegalArgumentException unbound) {
            scopedWorld = "";
        }
        this.worldId = scopedWorld;
        this.geometry = new MinecraftTidyDropGeometry(client);
        // First-install startup intentionally uses world-state/unbound before Paper's
        // authenticated handshake. Runtime reconstructs all stores after binding; never
        // fabricate a UUID, read permissions or create cleanup settings in this namespace.
        if (worldId.isEmpty()) {
            this.store = null; this.settings = null; this.receipts = null;
            this.loadProblem = "Tidy unbound; waiting for a verified Paper world binding; no cleanup is authorized";
            this.detail = loadProblem;
            return;
        }
        this.store = new TidySettingsStore(dataDirectory.resolve("tidy-settings.bin"), worldId);
        var loaded = store.loadSafely();
        this.settings = loaded.settings();
        this.receipts = new ReceiptJournal(dataDirectory.resolve("tidy-receipts.ndjson"), worldId);
        this.loadProblem = loaded.problem().isBlank() ? receipts.problem : loaded.problem();
        if (!loadProblem.isBlank()) detail = loadProblem;
        else if (receipts.sawReceipt) detail = "Restart cancelled all unissued cleanup. Previous receipts are evidence only; no drops replayed.";
    }

    public String worldId() { return worldId; }
    /** Read-only owner policy; capacity recovery must honor the same keep/junk overrides. */
    TidySettingsStore.Settings settings() { return settings; }
    public void setCommandResultSender(Predicate<JsonObject> sender) {
        commandResultSender = Objects.requireNonNull(sender, "sender");
    }
    public boolean needsLease() { return !worldId.isEmpty() && (autoQueued || work != Work.NONE && work != Work.CANCELLED || withdrawal != null); }
    public boolean needsForegroundFence() { return needsLease() || issued != null || session.status().phase() == TidySession.Phase.PREVIEW; }
    public boolean drainPending() { return withdrawal != null || issued != null; }

    public CommandResult command(String operation, String commandId, String item, boolean enabled,
            boolean includeStorage, SpotSelection selectedSpot, boolean idleAndSettled, long now) throws IOException {
        if (!operation.equals("status")) requireHealthy();
        return switch (operation) {
            case "status" -> new CommandResult(status(), false);
            case "preview" -> {
                if (drainPending()) throw new IllegalArgumentException("Settle the previous cleanup receipt/cursor first");
                if (includeStorage) requireIdle(idleAndSettled);
                beginPreview(commandId, includeStorage, false, now);
                yield new CommandResult(includeStorage ? "Inspecting only registered Home storage; nothing will be discarded before /e tidy confirm."
                        : previewSummary(), includeStorage);
            }
            case "confirm" -> {
                requireAnnouncedHomePreview();
                requireHealthy(); requireIdle(idleAndSettled);
                if (session.status().phase() != TidySession.Phase.PREVIEW) throw new IllegalArgumentException("Use /e tidy or /e tidy home for a fresh preview first");
                validateConfirmedSpot();
                if (!session.fence(currentScope())) throw new IllegalArgumentException(session.status().detail());
                if (session.status().preview().includeStorage()) {
                    approvedStorage = Map.copyOf(storage);
                    approvedIdentities = Map.copyOf(storageIdentities);
                    asyncCommandId = commandId;
                    beginVisits(Work.CONFIRM_HOME, now);
                    yield new CommandResult("Rechecking the exact registered-storage preview before disposal.", true);
                }
                if (!session.confirm(previewId, currentScope(), settings, allocation(), storageOrigins(), fuelNeed.getAsBoolean())) {
                    throw new IllegalArgumentException(session.status().detail());
                }
                receipts.manualConfirmation();
                work = session.status().phase() == TidySession.Phase.COMPLETE ? Work.NONE : Work.EXECUTE;
                workStarted = now;
                if (work != Work.NONE) asyncCommandId = commandId;
                detail = work == Work.NONE ? "Nothing is approved for discard; kept/store-reuse stacks remain intact."
                        : "Exact carried cleanup confirmed; walking to the selected disposal spot.";
                yield new CommandResult(detail, work != Work.NONE);
            }
            case "keep", "junk", "auto" -> {
                requireHealthy(); cancel("Cleanup policy changed; old preview cancelled");
                var next = switch (operation) {
                    case "keep" -> settings.keep(item);
                    case "junk" -> settings.junk(item);
                    default -> settings.withAutomatic(enabled);
                };
                store.save(next); settings = next;
                yield new CommandResult(status() + (operation.equals("auto") && enabled
                        ? " Policy reviewed: unneeded survival junk and redundant low-tier tools. Auto covers carried AND registered Home storage only at Stock completion; it cannot wake a stopped bot."
                        : " Rules are saved; no cleanup was started."), false);
            }
            case "spot_preview", "spot_confirm" -> {
                requireHealthy(); Objects.requireNonNull(selectedSpot, "selectedSpot");
                validateSpotSelection(selectedSpot, now);
                if (operation.equals("spot_preview")) {
                    spotPreview = selectedSpot;
                    yield new CommandResult("Disposal spot preview " + point(selectedSpot.spot())
                            + "; ordinary throws land around this selected surface, beyond pickup reach. /e tidy spot confirm saves it; no items will be dropped.", false);
                }
                if (!selectedSpot.equals(spotPreview)) throw new IllegalArgumentException("Drop spot preview changed or expired; use /e tidy spot again");
                cancel("Disposal spot changed; old cleanup preview cancelled");
                var next = settings.withConfirmedSpot(selectedSpot.spot());
                store.save(next); settings = next; spotPreview = null;
                yield new CommandResult("Saved disposal spot " + point(settings.spot())
                        + ". Nothing was dropped; use /e tidy then /e tidy confirm.", false);
            }
            default -> throw new IllegalArgumentException("Unknown tidy operation");
        };
    }

    public String status() {
        if (worldId.isEmpty()) return loadProblem;
        return "Tidy " + session.status().phase().name().toLowerCase(java.util.Locale.ROOT)
                + (settings.needsPolicyAcknowledgement()
                    ? "; NEW policy: low-tier tools + survival junk; auto paused. Review /e tidy home, then /e tidy auto on to acknowledge" : "")
                + "; auto=" + settings.automatic() + " (carried + registered storage at Stock completion)"
                + "; junk=" + settings.junkItems() + "; keep=" + settings.keepItems()
                + "; spot=" + (settings.spot() == null ? "not confirmed" : point(settings.spot())) + "; " + detail;
    }

    /** Called only from the existing finite Stock completion boundary. */
    public void queueAutomaticAtStockBoundary() {
        if (loadProblem.isBlank() && settings.permitsAutomatic(worldId, homeFingerprint()) && !needsLease() && !drainPending()) {
            autoQueued = true;
        }
    }

    public void cancel(String reason) {
        safetyPausedAt = -1;
        commandGeneration++;
        session.cancel(reason);
        autoQueued = false;
        work = Work.CANCELLED;
        stand = null; aimedAtAge = -1;
        detail = reason;
        completeCommand(false, "cancelled", reason, false);
        flushCommandResults();
    }

    /** Eating/combat borrow controls without replacing the preview, confirmed session or drop receipt. */
    public Outcome pauseForSafety(long now) throws IOException {
        if (work == Work.CANCELLED) return drain(now);
        if (!needsForegroundFence()) return new Outcome(false, detail);
        if (scope != null && !scope.equals(currentScope())) {
            return cancelAndDrain("Home, world, command or settings changed; cleanup cancelled", now);
        }
        if (safetyPausedAt < 0) safetyPausedAt = now;
        stand = null; aimedAtAge = -1;
        observe(now); // Existing passive hook cannot issue a partial THROW suffix.
        if (work == Work.CANCELLED) return drain(now);
        if (withdrawal != null) {
            var identity = withdrawalIdentity();
            var result = inventory.cleanupWithdrawalTick(withdrawal, identity.orElse(null), withdrawalOwner, true, false, now);
            if (result.state() == ClientInventoryController.CleanupWithdrawalState.COMPLETE) {
                finishWithdrawal(withdrawing, identity.orElseThrow(), result, now);
            } else if (result.state() == ClientInventoryController.CleanupWithdrawalState.STOPPED
                    && result.carriedSelection() == null) {
                if (!inventory.releaseCleanupWithdrawal(withdrawal, withdrawalOwner, now)) {
                    return new Outcome(true, "settling exact cleanup custody before safety");
                }
                withdrawal = null; withdrawing = null;
            } else if (result.state() == ClientInventoryController.CleanupWithdrawalState.STALE) {
                return cancelAndDrain(result.detail(), now);
            } else return new Outcome(true, "settling already-started exact cleanup withdrawal: " + result.detail());
        }
        if (!inventorySettled()
                || inventory.closeHandledScreenIfCursorEmpty(now) != ClientInventoryController.SafeCloseResult.CLOSED) {
            return new Outcome(true, "observing cleanup acknowledgement/cursor before safety");
        }
        detail = "Cleanup paused for safety; the same preview or confirmed selections will resume when safe";
        return new Outcome(false, detail);
    }

    private void resumeAfterSafety(long now) {
        if (safetyPausedAt < 0) return;
        workStarted += Math.max(0, now - safetyPausedAt);
        safetyPausedAt = -1;
        detail = "Resuming the same cleanup preview or confirmed selections after safety";
    }

    /** Read-only receipt reconciliation; never acquires a lease, drops or moves a cursor. */
    public void observe(long now) throws IOException {
        if (worldId.isEmpty()) return;
        flushCommandResults();
        if (issued == null || client.player == null) return;
        lastDrop = inventory.observeExactSelectionDrop(issued.receiptId(), issued.selection(), work != Work.CANCELLED, now);
        observeDropResult(now);
    }

    /** A foreground successor may drain exact old custody, but cannot resume cleanup. */
    public Outcome drain(long now) throws IOException {
        if (worldId.isEmpty()) return new Outcome(false, loadProblem);
        observe(now);
        if (withdrawal != null) {
            var identity = withdrawalIdentity();
            var result = inventory.cleanupWithdrawalTick(withdrawal, identity.orElse(null), withdrawalOwner, false, now);
            if (result.state() == ClientInventoryController.CleanupWithdrawalState.STOPPED
                    || result.state() == ClientInventoryController.CleanupWithdrawalState.COMPLETE) {
                inventory.releaseCleanupWithdrawal(withdrawal, withdrawalOwner, now);
                withdrawal = null; withdrawing = null;
            } else if (result.state() == ClientInventoryController.CleanupWithdrawalState.STALE && inventory.cursorEmpty()
                    && inventory.releaseCleanupWithdrawal(withdrawal, withdrawalOwner, now)) {
                withdrawal = null; withdrawing = null;
            } else return new Outcome(true, "settling exact cleanup withdrawal: " + result.detail());
        }
        if (issued != null) return new Outcome(true, "observing already-issued cleanup drop; no further drop is authorized");
        if (inventory.closeHandledScreenIfCursorEmpty(now) != ClientInventoryController.SafeCloseResult.CLOSED) {
            return new Outcome(true, "waiting for cleanup cursor/click settlement before handoff");
        }
        work = Work.NONE;
        return new Outcome(false, detail);
    }

    public Outcome tick(ControlLease lease, long tick, long now) throws IOException {
        if (worldId.isEmpty()) return new Outcome(false, loadProblem);
        try {
            return tickWork(lease, tick, now);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return cancelAndDrain("Cleanup refused: " + failure.getMessage(), now);
        }
    }

    private Outcome tickWork(ControlLease lease, long tick, long now) throws IOException {
        if (work == Work.CANCELLED) return drain(now);
        resumeAfterSafety(now);
        if (autoQueued) {
            if (!inventorySettled()) return running("waiting for final Stock inventory acknowledgement before optional cleanup");
            autoQueued = false;
            if (!settings.permitsAutomatic(worldId, homeFingerprint())) return done("Automatic cleanup is not authorized for this Home");
            beginPreview("auto-" + UUID.randomUUID(), true, true, now);
        }
        if (work == Work.NONE) return new Outcome(false, detail);
        if (now - workStarted > 180_000L) return cancelAndDrain("Cleanup route/observation deadline reached; make a fresh preview", now);
        if (!scope.equals(currentScope())) return cancelAndDrain("Home, world, command or settings changed; cleanup cancelled", now);
        if (work == Work.PREVIEW_HOME || work == Work.CONFIRM_HOME || work == Work.AUTO_HOME) {
            return tickVisits(lease, tick, now);
        }
        if (issued != null) {
            // Client prediction can remove a stack before the acknowledgement is stable.
            // Observe that issued click first; do not demand the already-thrown quantity again.
            if (!inventory.transactionDiagnostics().pendingClickOwner().isBlank()) return running("observing issued exact throw acknowledgement");
            Outcome travel = travelToDisposal(lease, tick, now);
            if (travel != null) return travel;
            Optional<ActionLease> action = acquire(lease, ActionOwner.INVENTORY_TRANSACTION, issued.receiptId(), tick, now);
            if (action.isEmpty()) return running("arming exact cleanup inventory owner");
            if (!permittedRemaining(issued.selection())) return cancelAndDrain("Reservation, useful replacement or selected stack changed", now);
            if (!aim(lease, tick, now)) return running("waiting for stable exact disposal aim");
            lastDrop = inventory.dropExactSelection(issued.receiptId(), issued.selection(), true, now);
            observeDropResult(now);
            return running(detail);
        }
        if (work == Work.WAIT_DROPS) {
            if (droppedCounts.entrySet().stream().anyMatch(entry -> inventory.count(entry.getKey()) > entry.getValue())) {
                return cancelAndDrain("Dropped item reappeared; cleanup stopped without another throw", now);
            }
            if (now < settleUntil) return running("observed ordinary drops; holding beyond pickup reach through pickup delay");
            return done("Cleanup complete; ordinary dropped items remain in Minecraft. Kept/store-reuse stacks were not discarded.");
        }
        if (withdrawal != null) return tickWithdrawal(withdrawing, lease, tick, now);
        Optional<TidySession.Next> next = session.next(currentScope());
        if (next.isEmpty()) {
            if (session.status().phase() == TidySession.Phase.COMPLETE) {
                work = Work.WAIT_DROPS; settleUntil = now + 2_500L;
                return running("all exact selected quantities observed removed");
            }
            return cancelAndDrain(session.status().detail(), now);
        }
        TidySession.Next selected = next.orElseThrow();
        if (selected.physical() == null) return tickWithdrawal(selected, lease, tick, now);
        if (collectingStorageBatch) {
            // Finish each acknowledged exact transfer before reserving another real empty slot.
            // Once disposal starts, drain the carried batch before returning to Home again.
            Optional<TidySession.Next> source = session.nextWithdrawal(currentScope(), emptyCarriedSlots());
            if (source.isPresent()) return tickWithdrawal(source.orElseThrow(), lease, tick, now);
            collectingStorageBatch = false;
        }
        if (client.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            return inventory.closeHandledScreenIfCursorEmpty(now) == ClientInventoryController.SafeCloseResult.CLOSED
                    ? running("closed exact source chest before disposal travel") : running("settling source chest before disposal travel");
        }
        Outcome travel = travelToDisposal(lease, tick, now);
        if (travel != null) return travel;
        String receiptId = "tidy:" + previewId + ":drop:" + selected.index();
        if (acquire(lease, ActionOwner.INVENTORY_TRANSACTION, receiptId, tick, now).isEmpty()) return running("arming exact ordinary drop owner");
        if (!aim(lease, tick, now)) return running("holding stable throw aim beyond pickup reach");
        var issue = session.issue(receiptId, currentScope(), settings, allocation(), fuelNeed.getAsBoolean());
        if (!issue.issueNow()) return cancelAndDrain("Old drop receipt cannot authorize another physical action", now);
        issued = issue.drop();
        receipts.record("prepared", issued, scope, 0, inventory.count(issued.selection().stack().item()), now);
        lastDrop = inventory.dropExactSelection(receiptId, issued.selection(), true, now);
        observeDropResult(now);
        return running(detail);
    }

    /** Reuse the ordinary confirmed-spot route if a safety maneuver moved a partial drop away. */
    private Outcome travelToDisposal(ControlLease lease, long tick, long now) throws IOException {
        validateConfirmedSpot();
        if (stand == null) {
            var search = geometry.search(settings.spot(), home.get().home(), property.get());
            stand = search.stands().stream().findFirst().orElseThrow(() -> new IllegalArgumentException(search.rejection()));
        }
        if (!client.player.getBlockPos().equals(stand)) {
            String route = "tidy:" + previewId + ":disposal";
            Optional<ActionLease> action = acquire(lease, ActionOwner.BARITONE_ROUTE, route, tick, now);
            if (action.isEmpty()) return running("arming route to the owner-confirmed disposal stance");
            baritone.bindMovementAction(kernel, lease, action.orElseThrow(), tick, now);
            baritone.start(new BaritonePort.Goal(route, "goto", Map.of("x", "" + stand.getX(), "y", "" + stand.getY(),
                    "z", "" + stand.getZ(), "dimension", settings.spot().dimension(), "range", "0")), lease);
            var status = baritone.poll(route);
            if (status.state() == BaritonePort.State.BLOCKED || status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
                return cancelAndDrain("Selected disposal stance is unreachable: " + status.detail(), now);
            }
            return running("walking to confirmed disposal spot without collecting its drops");
        }
        return null;
    }

    private Outcome tickVisits(ControlLease lease, long tick, long now) throws IOException {
        if (closeAfterObservation) {
            if (inventory.closeHandledScreenIfCursorEmpty(now) != ClientInventoryController.SafeCloseResult.CLOSED) return running("settling observed registered chest");
            closeAfterObservation = false; visitIndex++;
            openingStorage = "";
        }
        if (visitIndex >= visit.size()) {
            if (work == Work.CONFIRM_HOME) {
                if (!session.confirm(previewId, currentScope(), settings, allocation(), storageOrigins(), fuelNeed.getAsBoolean())) {
                    return cancelAndDrain(session.status().detail(), now);
                }
                receipts.manualConfirmation();
            } else {
                session.preview(previewId, scope, settings, allocation(), storageOrigins(), true, fuelNeed.getAsBoolean());
                if (automaticWork) {
                    if (session.status().preview().discard().stream().anyMatch(stack -> receipts.mayHaveReappeared(stack.selection().stack()))) {
                        return cancelAndDrain("Previously discarded item may have reappeared; automatic cleanup paused. Use /e tidy home to review it.", now);
                    }
                    if (!session.confirm(previewId, currentScope(), settings, allocation(), storageOrigins(), fuelNeed.getAsBoolean())) {
                        return cancelAndDrain(session.status().detail(), now);
                    }
                } else return done(previewSummary());
            }
            work = session.status().phase() == TidySession.Phase.COMPLETE ? Work.NONE : Work.EXECUTE;
            return work == Work.NONE ? done("No approved discard candidates; useful/custom/retained items remain intact")
                    : running("exact Home cleanup approved; obtaining only selected discard stacks");
        }
        StorageRegistration registration = visit.get(visitIndex);
        Outcome opening = open(registration, lease, tick, now);
        if (opening != null) return opening;
        ContainerIdentity identity = currentOpenIdentity(registration.id()).orElseThrow();
        for (var previous : storageIdentities.entrySet()) {
            if (!previous.getKey().equals(registration.id()) && previous.getValue().halves().stream()
                    .anyMatch(half -> identity.halves().stream().anyMatch(other -> other.position().equals(half.position())))) {
                return cancelAndDrain("Two registrations refer to the same connected chest; remove the duplicate before Home cleanup", now);
            }
        }
        if (work == Work.CONFIRM_HOME && !identity.equals(approvedIdentities.get(registration.id()))) {
            return cancelAndDrain("Registered connected layout changed; make a new Home preview", now);
        }
        storageIdentities.put(registration.id(), identity);
        int syncId = client.player.currentScreenHandler.syncId;
        for (StackFact fact : inventory.personalSupplies().homeChestStacks(identity, syncId)) {
            int slot = sourceSlot(fact.stackId(), identity, syncId);
            String stableId = stableStorageId(registration.id(), slot);
            Stored observed = new Stored(withId(fact, stableId), registration.id(), identity, slot);
            if (work == Work.CONFIRM_HOME) {
                Stored approved = approvedStorage.get(stableId);
                if (approved == null || !approved.equals(observed)) return cancelAndDrain("Registered chest contents or connected layout changed; make a new Home preview", now);
            }
            storage.put(stableId, observed);
        }
        if (work == Work.CONFIRM_HOME && approvedStorage.values().stream().filter(s -> s.storageId().equals(registration.id()))
                .anyMatch(s -> !storage.containsKey(s.fact().stackId()))) {
            return cancelAndDrain("A previewed registered-chest stack disappeared; make a new Home preview", now);
        }
        closeAfterObservation = true;
        return running("observed exact registered storage " + registration.id() + "; no discard during inspection");
    }

    private Outcome tickWithdrawal(TidySession.Next selected, ControlLease lease, long tick, long now) throws IOException {
        StorageRegistration registration = home.get().storageRegistrations().get(selected.original().storageId());
        if (registration == null) return cancelAndDrain("Approved storage registration was removed", now);
        Outcome opening = open(registration, lease, tick, now);
        if (opening != null) return opening;
        ContainerIdentity identity = currentOpenIdentity(registration.id()).orElseThrow();
        Stored origin = storage.get(selected.original().selection().stack().stackId());
        if (origin == null || !origin.identity().equals(identity)) return cancelAndDrain("Approved connected storage changed", now);
        if (withdrawal == null) {
            StackFact actual = inventory.personalSupplies().homeChestStacks(identity, client.player.currentScreenHandler.syncId).stream()
                    .filter(fact -> fact.stackId().equals(MinecraftPersonalSuppliesAdapter.chestStackId(identity,
                            client.player.currentScreenHandler.syncId, origin.slot()))).findFirst().orElse(null);
            if (actual == null || !selected.original().selection().stillMatches(withId(actual, origin.fact().stackId()))) {
                return cancelAndDrain("Exact approved source components/quantity changed", now);
            }
            withdrawal = inventory.planCleanupWithdrawal(new Selection(actual, selected.original().selection().count()), identity,
                    client.player.currentScreenHandler.syncId).orElse(null);
            if (withdrawal == null) return cancelAndDrain("An empty carried slot is needed for exact cleanup withdrawal; no chest item moved", now);
            withdrawing = selected;
            withdrawalOwner = "tidy:" + previewId + ":withdraw:" + selected.index();
        }
        if (acquireStorageAction(lease, registration.id(), tick, now).isEmpty()) return running("arming exact cleanup withdrawal owner");
        var result = inventory.cleanupWithdrawalTick(withdrawal, identity, withdrawalOwner, true, now);
        if (result.state() == ClientInventoryController.CleanupWithdrawalState.STALE) return cancelAndDrain(result.detail(), now);
        if (result.state() != ClientInventoryController.CleanupWithdrawalState.COMPLETE) return running(result.detail());
        finishWithdrawal(selected, identity, result, now);
        return running("proved exact Home stack in carried inventory; filling remaining empty slots before disposal");
    }

    private void finishWithdrawal(TidySession.Next selected, ContainerIdentity identity,
            ClientInventoryController.CleanupWithdrawalResult result, long now) {
        Stored origin = storage.get(selected.original().selection().stack().stackId());
        StackFact sourceAfter = inventory.personalSupplies().homeChestStacks(identity, client.player.currentScreenHandler.syncId).stream()
                .filter(fact -> fact.stackId().equals(MinecraftPersonalSuppliesAdapter.chestStackId(identity,
                        client.player.currentScreenHandler.syncId, origin.slot())))
                .map(fact -> withId(fact, origin.fact().stackId())).findFirst().orElse(null);
        session.rebound(currentScope(), selected.index(), new TidySession.Withdrawal(withdrawalOwner, selected.original().selection(), sourceAfter,
                null, result.carriedSelection().stack()));
        if (sourceAfter == null) storage.remove(origin.fact().stackId());
        else storage.put(sourceAfter.stackId(), new Stored(sourceAfter, origin.storageId(), identity, origin.slot()));
        inventory.releaseCleanupWithdrawal(withdrawal, withdrawalOwner, now);
        withdrawal = null; withdrawing = null;
        collectingStorageBatch = true;
    }

    private Outcome open(StorageRegistration registration, ControlLease lease, long tick, long now) throws IOException {
        if (currentOpenIdentity(registration.id()).isPresent()) return null;
        if (client.player.currentScreenHandler instanceof GenericContainerScreenHandler
                && !openingStorage.equals(registration.id())) {
            if (withdrawal != null) return cancelAndDrain("Exact cleanup chest screen changed during withdrawal", now);
            if (inventory.closeHandledScreenIfCursorEmpty(now) != ClientInventoryController.SafeCloseResult.CLOSED) return running("settling previous registered chest before switching");
        }
        String operation = storageOperation(registration.id());
        ContainerIdentity required = work == Work.CONFIRM_HOME ? approvedIdentities.get(registration.id())
                : storageIdentities.get(registration.id());
        if (withdrawal == null) workstations.requirePinnedStorageIdentity(operation, required);
        var action = acquireStorageAction(lease, registration.id(), tick, now);
        if (action.isEmpty()) return running("arming registered chest " + registration.id());
        openingStorage = registration.id();
        baritone.bindMovementAction(kernel, lease, action.orElseThrow(), tick, now);
        var result = workstations.tickOpenPinned(WorkstationController.Kind.CHEST, operation, registration.ledgerAssetId(),
                home.get().home(), 6, lease, baritone, now);
        if (result.state() == WorkstationController.State.BLOCKED || result.state() == WorkstationController.State.ITEM_MISSING) {
            return cancelAndDrain("Registered storage " + registration.id() + " unavailable; contents unknown: " + result.detail(), now);
        }
        return result.state() == WorkstationController.State.OPEN ? null : running(result.detail());
    }

    private Optional<ContainerIdentity> currentOpenIdentity(String storageId) {
        var current = home.get();
        if (current == null || current.home() == null) return Optional.empty();
        var registration = current.storageRegistrations().get(storageId);
        return registration == null ? Optional.empty() : workstations.exactPinnedStorageIdentity(storageId,
                storageOperation(storageId), registration.ledgerAssetId(), current.home(), 6);
    }

    /** Stop may clear a Workstation acknowledgement, never the frozen transfer's screen provenance. */
    private Optional<ContainerIdentity> withdrawalIdentity() {
        if (withdrawal == null || client.player == null
                || client.player.currentScreenHandler.syncId != withdrawal.transfer().syncId()) return Optional.empty();
        var registration = home.get().storageRegistrations().get(withdrawing.original().storageId());
        if (registration == null) return Optional.empty();
        return workstations.observeHomeStorageIdentity(registration.id(), registration.ledgerAssetId(), home.get().home(), 6)
                .filter(withdrawal.identity()::equals);
    }

    private boolean aim(ControlLease lease, long tick, long now) {
        validateConfirmedSpot();
        var aim = geometry.aimAtCurrentPosition(settings.spot(), home.get().home(), property.get());
        if (aim.isEmpty()) { aimedAtAge = -1; return false; }
        client.player.setYaw(aim.orElseThrow().yaw()); client.player.setPitch(aim.orElseThrow().pitch());
        if (aimedAtAge < 0) { aimedAtAge = client.player.age; return false; }
        return client.player.age > aimedAtAge;
    }

    private void observeDropResult(long now) throws IOException {
        if (issued == null || lastDrop == null) return;
        if (lastDrop.state() == ExactCleanupDrop.State.ISSUED) {
            receipts.record("issued", issued, scope, lastDrop.observedDropped(), inventory.count(issued.selection().stack().item()), now);
            detail = "issued one ordinary exact-stack throw; waiting for acknowledgement";
        }
        if (lastDrop.observedDropped() > 0) session.observe(issued, lastDrop.observedDropped());
        switch (lastDrop.state()) {
            case COMPLETE -> {
                receipts.record("observed", issued, scope, lastDrop.observedDropped(), inventory.count(issued.selection().stack().item()), now);
                droppedCounts.put(issued.selection().stack().item(), inventory.count(issued.selection().stack().item()));
                detail = "observed " + lastDrop.observedDropped() + " exact " + issued.selection().stack().item() + " removed";
                issued = null; lastDrop = null;
            }
            case STOPPED, STALE, REAPPEARED, TIMED_OUT -> {
                if (!inventory.transactionDiagnostics().pendingClickOwner().isBlank() || !inventory.cursorEmpty()) return;
                receipts.record("cancelled", issued, scope, lastDrop.observedDropped(), inventory.count(issued.selection().stack().item()), now);
                session.abandon(issued, lastDrop.observedDropped(), lastDrop.detail());
                issued = null; lastDrop = null;
                cancel("Cleanup cancelled after exact receipt observation; no issued quantity will be retried");
            }
            default -> { }
        }
    }

    private boolean permittedRemaining(Selection selection) {
        int observed = lastDrop == null ? 0 : lastDrop.observedDropped();
        int remaining = selection.count() - observed;
        if (remaining <= 0) return true;
        StackFact current = inventory.personalSupplies().playerStack(MinecraftPersonalSuppliesAdapter.playerInventorySlot(selection.stack().stackId())).orElse(null);
        return current != null && PersonalSuppliesPolicy.previewCleanup(allocation(), settings.junkItems(), settings.keepItems(), fuelNeed.getAsBoolean())
                .stream().anyMatch(slice -> slice.action() == PersonalSuppliesPolicy.CleanupAction.DISCARD_CANDIDATE
                        && slice.selection().stack().equals(current) && slice.selection().count() >= remaining);
    }

    private void beginPreview(String id, boolean includeStorage, boolean automatic, long now) throws IOException {
        requireHealthy();
        if (automatic) validateConfirmedSpot();
        if (!asyncCommandId.isEmpty()) cancel("Cleanup replaced by a new explicit preview");
        commandGeneration++; scope = currentScope(); previewId = id;
        announcedPreviewId = "";
        storage.clear(); storageIdentities.clear(); approvedStorage = Map.of(); approvedIdentities = Map.of();
        droppedCounts.clear(); stand = null; aimedAtAge = -1; openingStorage = ""; collectingStorageBatch = false;
        automaticWork = automatic; workStarted = now;
        safetyPausedAt = -1;
        if (includeStorage) {
            if (home.get() == null || home.get().home() == null) throw new IllegalArgumentException("Establish Home before inspecting registered storage");
            if (!automatic) asyncCommandId = id;
            beginVisits(automatic ? Work.AUTO_HOME : Work.PREVIEW_HOME, now);
        } else {
            session.preview(id, scope, settings, allocation(), Map.of(), false, fuelNeed.getAsBoolean());
            work = Work.NONE; detail = previewSummary();
        }
    }

    private void beginVisits(Work mode, long now) {
        work = mode; workStarted = now; storage.clear(); storageIdentities.clear(); visitIndex = 0;
        closeAfterObservation = false; openingStorage = "";
        visit = home.get().storageRegistrations().values().stream().sorted(Comparator.comparingLong(StorageRegistration::registeredAtMillis)
                .thenComparing(StorageRegistration::id)).toList();
        detail = "inspecting only " + visit.size() + " explicitly registered Home containers";
    }

    /** Personal supplies are retained in carried inventory; chest contents cannot replace a carried kit silently. */
    private Allocation allocation() {
        return PersonalSuppliesPolicy.allocateCleanup(inventory.personalSupplies().playerStacks(),
                storage.values().stream().map(Stored::fact).toList(), reservations.get());
    }

    private Map<String, String> storageOrigins() {
        Map<String, String> origins = new LinkedHashMap<>();
        storage.forEach((id, stack) -> origins.put(id, stack.storageId()));
        return Map.copyOf(origins);
    }

    private TidySession.Scope currentScope() {
        var snapshot = home.get();
        return new TidySession.Scope(worldId, homeFingerprint(), snapshot == null ? 0 : snapshot.generation(), commandGeneration, settings.revision());
    }
    private String homeFingerprint() { var snapshot = home.get(); return snapshot == null || snapshot.home() == null ? "" : snapshot.home().fingerprint(); }
    private String storageOperation(String storageId) { return "tidy:" + previewId + ":storage:" + storageId; }
    private boolean inventorySettled() {
        var truth = inventory.transactionDiagnostics();
        return inventory.cursorEmpty() && truth.pendingClickOwner().isBlank() && truth.cursorOwner().isBlank() && truth.pendingRemovals() == 0;
    }
    private int emptyCarriedSlots() {
        if (!inventorySettled()) return 0;
        int empty = 0;
        for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getStack(slot).isEmpty()) empty++;
        return empty;
    }
    private Optional<ActionLease> acquire(ControlLease lease, ActionOwner owner, String id, long tick, long now) {
        return actions.acquire(lease, owner, id, tick, now);
    }
    /** Opening and clicking one chest share custody: an owner switch closes its screen. */
    Optional<ActionLease> acquireStorageAction(ControlLease lease, String storageId, long tick, long now) {
        return acquire(lease, ActionOwner.WORKSTATION_INTERACTION, storageOperation(storageId), tick, now);
    }
    private void validateSpotSelection(SpotSelection selected, long now) {
        if (selected.nonce().isBlank() || now > selected.expiresAt()) throw new IllegalArgumentException("Drop spot selection expired");
        var policy = property.get();
        if (policy == null || !policy.status().synchronizedPolicy() || policy.status().revision() != selected.policyRevision()
                || !policy.status().digest().equals(selected.policyDigest())) throw new IllegalArgumentException("Drop spot area policy changed; select it again");
        if (!selected.spot().belongsTo(worldId, homeFingerprint())) throw new IllegalArgumentException("Drop spot belongs to another world or Home");
        var search = geometry.search(selected.spot(), home.get().home(), policy);
        if (search.stands().isEmpty()) throw new IllegalArgumentException(search.rejection());
    }
    private void validateConfirmedSpot() {
        if (!settings.hasSpot(worldId, homeFingerprint())) throw new IllegalArgumentException("Confirm a disposal spot for this Home with /e tidy spot then /e tidy spot confirm");
        geometry.validateSpot(settings.spot(), home.get().home(), property.get());
    }
    private void requireHealthy() { if (!loadProblem.isBlank()) throw new IllegalArgumentException(loadProblem); }
    private void requireIdle(boolean idle) { if (!idle || !inventorySettled() || drainPending() || needsLease()) throw new IllegalArgumentException("Finish the active mission/Home work and settle inventory before requesting Tidy work"); }
    private Outcome cancelAndDrain(String reason, long now) throws IOException { cancel(reason); return drain(now); }
    private Outcome running(String reason) { detail = reason; return new Outcome(true, reason); }
    private Outcome done(String reason) {
        boolean previewReady = work == Work.PREVIEW_HOME && session.status().phase() == TidySession.Phase.PREVIEW;
        boolean cancelled = session.status().phase() == TidySession.Phase.CANCELLED;
        work = Work.NONE; detail = reason;
        completeCommand(!cancelled, cancelled ? "cancelled" : "completed", reason, previewReady);
        return new Outcome(false, reason);
    }

    /** The Home list must leave through its original command result before confirm can actuate. */
    private void requireAnnouncedHomePreview() {
        var preview = session.status().preview();
        if (preview != null && preview.includeStorage() && !preview.id().equals(announcedPreviewId)) {
            throw new IllegalArgumentException("Wait for the exact Home cleanup preview reply before confirming; use /e tidy home again if it was cancelled");
        }
    }

    private void completeCommand(boolean ok, String phase, String message, boolean previewReady) {
        if (asyncCommandId.isEmpty()) return;
        commandCompletions.addLast(new CommandCompletion(asyncCommandId, ok, phase, message,
                previewReady ? previewId : "", commandGeneration));
        asyncCommandId = "";
        flushCommandResults();
    }

    /** Retry only a result frame, never physical work or a cancelled preview authorization. */
    private void flushCommandResults() {
        while (!commandCompletions.isEmpty()) {
            CommandCompletion completion = commandCompletions.peekFirst();
            if (!completion.previewId().isEmpty() && (completion.generation() != commandGeneration
                    || !completion.previewId().equals(previewId)
                    || session.status().phase() != TidySession.Phase.PREVIEW
                    || scope == null || !scope.equals(currentScope()))) {
                commandCompletions.removeFirst();
                completion = new CommandCompletion(completion.id(), false, "cancelled",
                        "Home cleanup preview cancelled before delivery; request a fresh /e tidy home preview", "", commandGeneration);
                commandCompletions.addFirst(completion);
            }
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "result"); frame.addProperty("id", completion.id());
            frame.addProperty("ok", completion.ok()); frame.addProperty("phase", completion.phase());
            frame.addProperty("message", BridgeResultPolicy.boundedMessage(completion.message()));
            if (!commandResultSender.test(frame)) return;
            commandCompletions.removeFirst();
            if (!completion.previewId().isEmpty()) announcedPreviewId = completion.previewId();
        }
    }

    private String previewSummary() {
        var preview = session.status().preview();
        if (preview == null) return detail;
        Map<String, Integer> grouped = new LinkedHashMap<>();
        preview.discard().forEach(stack -> {
            String item = stack.selection().stack().item();
            if (item.startsWith("minecraft:")) item = item.substring("minecraft:".length());
            String reason = stack.selection().stack().capabilities().isEmpty()
                    ? "unneeded junk" : "redundant; usable replacement kept";
            grouped.merge(item + " @" + (stack.carried() ? "carried" : "home") + " (" + reason + ")",
                    stack.selection().count(), Integer::sum);
        });
        String exact = grouped.entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey())
                .collect(java.util.stream.Collectors.joining("; "));
        String message = "Tidy preview " + (preview.includeStorage() ? "Home" : "carried") + "; exact discard: "
                + (exact.isBlank() ? "none" : exact) + ". Nothing discarded. /e tidy confirm.";
        // The server's chat result is bounded. Never authorize a suffix the owner cannot see.
        if (message.length() >= 490) {
            session.cancel("Too many distinct discard groups to show safely; use a carried-only preview or narrower junk rules");
            return "Tidy preview cancelled: too many discard groups for one visible confirmation. "
                    + "Use /e tidy for carried only or /e tidy keep <item> to narrow it. Nothing discarded.";
        }
        int kept = preview.slices().stream().filter(slice -> slice.action() == PersonalSuppliesPolicy.CleanupAction.KEEP)
                .mapToInt(slice -> slice.selection().count()).sum();
        int reuse = preview.slices().stream().filter(slice -> slice.action() == PersonalSuppliesPolicy.CleanupAction.STORE_REUSE)
                .mapToInt(slice -> slice.selection().count()).sum();
        String retained = " Kept " + kept + "; store/reuse " + reuse + ".";
        return message.length() + retained.length() < 490 ? message + retained : message;
    }
    private static String point(DisposalSpot spot) { return spot.dimension() + " " + spot.x() + " " + spot.y() + " " + spot.z(); }
    private static String stableStorageId(String storageId, int slot) { return "storage:" + storageId + ":slot:" + slot; }
    private static StackFact withId(StackFact fact, String id) { return new StackFact(id, fact.item(), fact.count(), fact.componentsKey(), fact.remainingDurability(), fact.capabilities(), fact.custom(), fact.equipped(), fact.fuelCompatible(), fact.foodNutrition()); }
    private static int sourceSlot(String id, ContainerIdentity identity, int syncId) {
        for (int slot = 0; slot < identity.slotCount(); slot++) if (MinecraftPersonalSuppliesAdapter.chestStackId(identity, syncId, slot).equals(id)) return slot;
        throw new IllegalArgumentException("unknown exact chest slot");
    }

    /** Append-and-force evidence, never a persisted instruction queue. Restart never replays it. */
    static final class ReceiptJournal {
        final Path path;
        final String world;
        private record PendingReceipt(String signature, int selectedCount) { }
        final Map<String, PendingReceipt> unresolved = new LinkedHashMap<>();
        String problem = "";
        boolean sawReceipt;
        ReceiptJournal(Path path, String world) {
            this.path = path; this.world = world;
            try {
                if (Files.exists(path)) {
                    if (Files.size(path) > 1_048_576) throw new IOException("receipt evidence exceeds bounded size");
                    for (String line : Files.readAllLines(path)) {
                        JsonObject entry = JsonParser.parseString(line).getAsJsonObject();
                        if (!world.equals(entry.get("worldId").getAsString())) throw new IOException("receipt world mismatch");
                        apply(entry);
                    }
                }
            } catch (IOException | RuntimeException failure) { problem = "Tidy receipt evidence unavailable; no automatic/discard action: " + failure.getMessage(); }
        }
        boolean mayHaveReappeared(StackFact fact) {
            String signature = fact.item() + '\n' + fact.componentsKey();
            return unresolved.values().stream().anyMatch(receipt -> receipt.signature().equals(signature));
        }
        void manualConfirmation() throws IOException {
            JsonObject entry = new JsonObject(); entry.addProperty("worldId", world); entry.addProperty("stage", "manual_confirm");
            append(entry); apply(entry);
        }
        void record(String stage, TidySession.IssuedDrop drop, TidySession.Scope scope, int observed, int rawCount, long now) throws IOException {
            JsonObject entry = new JsonObject();
            entry.addProperty("worldId", world); entry.addProperty("home", scope.homeFingerprint());
            entry.addProperty("generation", scope.commandGeneration()); entry.addProperty("receipt", drop.receiptId());
            entry.addProperty("stage", stage); entry.addProperty("at", now); entry.addProperty("slot", drop.selection().stack().stackId());
            entry.addProperty("item", drop.selection().stack().item()); entry.addProperty("components", drop.selection().stack().componentsKey());
            entry.addProperty("originalCount", drop.selection().stack().count()); entry.addProperty("selectedCount", drop.selection().count());
            entry.addProperty("observedCount", observed); entry.addProperty("rawPlayerCount", rawCount);
            append(entry); apply(entry);
        }
        /** A settled receipt clears only itself; it is not a lifetime ban on this kind of item. */
        private void apply(JsonObject entry) {
            String stage = entry.get("stage").getAsString();
            if (stage.equals("manual_confirm")) { unresolved.clear(); return; }
            sawReceipt = true;
            String id = entry.get("receipt").getAsString();
            String signature = entry.get("item").getAsString() + '\n' + entry.get("components").getAsString();
            int selected = entry.get("selectedCount").getAsInt();
            int observed = entry.get("observedCount").getAsInt();
            if (id.isBlank() || selected <= 0 || observed < 0 || observed > selected) {
                throw new IllegalArgumentException("invalid exact cleanup receipt counts");
            }
            PendingReceipt receipt = new PendingReceipt(signature, selected);
            PendingReceipt previous = unresolved.get(id);
            if (previous != null && !previous.equals(receipt)) throw new IllegalArgumentException("cleanup receipt identity changed");
            switch (stage) {
                case "prepared", "issued", "cancelled" -> unresolved.put(id, receipt);
                case "observed" -> {
                    if (observed != selected) throw new IllegalArgumentException("incomplete cleanup observation is not terminal");
                    unresolved.remove(id);
                }
                default -> throw new IllegalArgumentException("unknown cleanup receipt stage");
            }
        }
        private void append(JsonObject entry) throws IOException {
            byte[] bytes = (entry + "\n").getBytes(StandardCharsets.UTF_8);
            if (Files.exists(path) && Files.size(path) + bytes.length > 1_048_576) throw new IOException("Tidy receipt evidence is full; no further discard issued");
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
        }
    }
}
