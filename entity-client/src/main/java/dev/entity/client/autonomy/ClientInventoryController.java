package dev.entity.client.autonomy;

import dev.entity.client.autonomy.inventory.InventorySnapshot;
import dev.entity.client.autonomy.inventory.InventoryTransactionEngine;
import dev.entity.client.autonomy.inventory.ExactCleanupDrop;
import dev.entity.client.autonomy.policy.CraftingIngredientCapacity;
import dev.entity.client.autonomy.policy.ResourceCatalog;
import dev.entity.client.autonomy.policy.DeliveryPolicy;
import dev.entity.client.autonomy.policy.HomeStockPolicy;
import dev.entity.client.autonomy.policy.HomeEconomySession.ContainerIdentity;
import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy.Selection;
import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy.StackFact;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.context.ContextParameterMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Server-authoritative inventory clicks for Entity's real player inventory.
 *
 * <p>This class deliberately performs at most one click per call. The caller
 * must observe the server-confirmed inventory on a later tick before deciding
 * that an action succeeded.</p>
 */
public final class ClientInventoryController {
    private static final Logger LOGGER = LoggerFactory.getLogger("Entity2Inventory");
    private static final ResourceCatalog RESOURCE_CATALOG = ResourceCatalog.defaults();
    private static final String PASSIVE_TRANSACTION_OBSERVER = "inventory-observer";
    private static final String EMPTY_ARMOR_OWNER = "idle-empty-armor";

    private final MinecraftClient client;
    private final InventoryTransactionEngine transactions = new InventoryTransactionEngine();
    private final MinecraftPersonalSuppliesAdapter personalSupplies;
    private ExactCleanupDrop cleanupDrop;
    private CleanupWithdrawalSession cleanupWithdrawal;
    private String preparedRecipe = "";
    private int preparedRecipeSyncId = -1;
    private EquipSession equipSession;
    private PlayerInventory emptyArmorInventory;
    private final Map<Integer, ItemStack> attemptedEmptyArmor = new LinkedHashMap<>();

    public ClientInventoryController(MinecraftClient client) {
        this(client, new MinecraftPersonalSuppliesAdapter(client));
    }

    ClientInventoryController(MinecraftClient client, MinecraftPersonalSuppliesAdapter personalSupplies) {
        this.client = Objects.requireNonNull(client, "client");
        this.personalSupplies = Objects.requireNonNull(personalSupplies, "personalSupplies");
    }

    public MinecraftPersonalSuppliesAdapter personalSupplies() { return personalSupplies; }

    /**
     * One confirmed cleanup selection, never a first-matching item-ID drop.
     * The caller owns world/generation/disposal authority. false permanently stops
     * unissued actions for this operation while an issued THROW remains observable.
     * This method neither closes a chest nor drops directly from one.
     */
    public ExactCleanupDrop.Result dropExactSelection(String owner, Selection selection,
            boolean authorized, long nowMillis) {
        return exactSelectionDropTick(owner, selection, authorized, true, nowMillis);
    }

    /** Receipt-only tick: no drop or screen mutation, including while operator Stop is latched. */
    public ExactCleanupDrop.Result observeExactSelectionDrop(String owner, Selection selection,
            boolean authorized, long nowMillis) {
        return exactSelectionDropTick(owner, selection, authorized, false, nowMillis);
    }

    private ExactCleanupDrop.Result exactSelectionDropTick(String owner, Selection selection,
            boolean authorized, boolean allowNewClick, long nowMillis) {
        Objects.requireNonNull(selection, "selection");
        int inventorySlot = MinecraftPersonalSuppliesAdapter.playerInventorySlot(selection.stack().stackId());
        if (inventorySlot < 0 || inventorySlot >= 36) {
            return new ExactCleanupDrop.Result(ExactCleanupDrop.State.STALE, 0, 0,
                    "cleanup requires a selected ordinary player slot, not chest/equipment");
        }
        if (client.player == null) return new ExactCleanupDrop.Result(
                ExactCleanupDrop.State.WAITING, 0, 0, "player unavailable");
        if (cleanupDrop == null || !cleanupDrop.matches(owner, selection)) {
            if (cleanupDrop != null && cleanupDrop.unsettled()
                    || cleanupWithdrawal != null && !cleanupWithdrawal.settled
                    || !transactions.diagnostics().pendingClickOwner().isBlank()
                    || !transactions.diagnostics().cursorOwner().isBlank()
                    || transactions.diagnostics().pendingRemovals() != 0) {
                return new ExactCleanupDrop.Result(ExactCleanupDrop.State.WAITING, 0, 0,
                        "another inventory operation is unsettled");
            }
            cleanupDrop = new ExactCleanupDrop(owner, selection);
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        boolean playerHandler = handler instanceof PlayerScreenHandler;
        return cleanupDrop.tick(personalSupplies.playerStack(inventorySlot).orElse(null), snapshot(handler),
                handlerSlot(handler, inventorySlot), authorized && playerHandler, allowNewClick, nowMillis, transactions,
                button -> issueSlotClick(owner, handler, handlerSlot(handler, inventorySlot), button,
                        SlotActionType.THROW, nowMillis));
    }

    /**
     * Explicit cleanup may withdraw an ordinary worn tool. This separate exact
     * selection path does not widen normal Home stock's pristine eligibility.
     * One empty player slot preserves a provable origin-to-player authorization.
     */
    public Optional<CleanupWithdrawalPlan> planCleanupWithdrawal(Selection selection,
            ContainerIdentity acknowledgedIdentity, int acknowledgedSyncId) {
        Objects.requireNonNull(selection, "selection");
        if (client.player == null || !(client.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler)
                || !MinecraftPersonalSuppliesAdapter.acknowledgedScreen(
                        handler, acknowledgedIdentity, acknowledgedSyncId)
                || !handler.getCursorStack().isEmpty()) return Optional.empty();
        PlayerInventory inventory = client.player.getInventory();
        for (Slot source : handler.slots) {
            if (source.inventory == inventory || source.getStack().isEmpty()
                    || !MinecraftPersonalSuppliesAdapter.chestStackId(
                            acknowledgedIdentity, handler.syncId, source.id).equals(selection.stack().stackId())) continue;
            StackFact observed = personalSupplies.observedFact(selection.stack().stackId(), source.getStack(), false);
            if (!selection.stillMatches(observed)) return Optional.empty();
            for (Slot destination : handler.slots) {
                if (destination.inventory != inventory || destination.getIndex() < 0
                        || destination.getIndex() >= 36 || !destination.getStack().isEmpty()
                        || !destination.canInsert(source.getStack())
                        || destination.getMaxItemCount(source.getStack()) < selection.count()) continue;
                HomeTransferPlan transfer = new HomeTransferPlan(HomeStockPolicy.Direction.WITHDRAW,
                        selection.stack().item(), selection.count(), handler.syncId, source.id, destination.id,
                        snapshot(handler).playerCount(selection.stack().item()),
                        containerItemCount(handler, selection.stack().item()), 0);
                return Optional.of(new CleanupWithdrawalPlan(selection, acknowledgedIdentity,
                        transfer, destination.getIndex()));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Uses the existing Home cursor claim and click acknowledgement engine.
     * Stop may return an already-owned cursor to its exact source; no new source
     * pickup occurs. A settled result binds the actual destination player slot.
     */
    public CleanupWithdrawalResult cleanupWithdrawalTick(CleanupWithdrawalPlan plan,
            ContainerIdentity currentAcknowledgedIdentity, String owner, boolean authorized, long nowMillis) {
        return cleanupWithdrawalTick(plan, currentAcknowledgedIdentity, owner, authorized, true, nowMillis);
    }

    /** Survival may settle an already-started exact transfer, but cannot pick up another source. */
    public CleanupWithdrawalResult cleanupWithdrawalTick(CleanupWithdrawalPlan plan,
            ContainerIdentity currentAcknowledgedIdentity, String owner, boolean authorized,
            boolean allowNewSourcePickup, long nowMillis) {
        Objects.requireNonNull(plan, "plan");
        if (client.player == null || !(client.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler)) {
            return new CleanupWithdrawalResult(CleanupWithdrawalState.STALE, null, "acknowledged chest closed");
        }
        if (cleanupWithdrawal == null || !cleanupWithdrawal.matches(plan, owner)) {
            if (cleanupWithdrawal != null && !cleanupWithdrawal.settled
                    || cleanupDrop != null && cleanupDrop.unsettled()
                    || !transactions.diagnostics().pendingClickOwner().isBlank()
                    || !transactions.diagnostics().cursorOwner().isBlank()
                    || transactions.diagnostics().pendingRemovals() != 0) {
                return new CleanupWithdrawalResult(CleanupWithdrawalState.WAITING, null, "another inventory owner");
            }
            cleanupWithdrawal = new CleanupWithdrawalSession(plan, owner);
        }
        CleanupWithdrawalSession session = cleanupWithdrawal;
        if (!authorized) session.stopped = true;
        if (session.result != null) return session.result;
        if (!plan.identity().equals(currentAcknowledgedIdentity)
                || !MinecraftPersonalSuppliesAdapter.acknowledgedScreen(handler, plan.identity(), plan.transfer().syncId())) {
            return session.fail("registered chest identity/layout changed");
        }
        HomeTransferPlan transfer = plan.transfer();
        if (transfer.sourceSlot() >= handler.slots.size() || transfer.destinationSlot() >= handler.slots.size()) {
            return session.fail("source/destination slot changed");
        }
        Slot source = handler.getSlot(transfer.sourceSlot());
        Slot destination = handler.getSlot(transfer.destinationSlot());
        if (source.inventory == client.player.getInventory() || destination.inventory != client.player.getInventory()
                || destination.getIndex() != plan.destinationPlayerSlot()) return session.fail("slot domain changed");
        var gate = transactions.gate(owner, snapshot(handler), nowMillis);
        if (!gate.permitsClick()) return session.waiting();
        if (gate == InventoryTransactionEngine.Gate.READY_AFTER_TIMEOUT) return session.fail("withdrawal click timed out");
        ItemStack cursor = handler.getCursorStack();
        int sourceCount = source.getStack().getCount();
        int destinationCount = destination.getStack().getCount();
        if (!cleanupStackMatches(plan.selection().stack(), source.getStack())
                || !cleanupStackMatches(plan.selection().stack(), destination.getStack())
                || !cleanupStackMatches(plan.selection().stack(), cursor)) return session.fail("exact components changed");
        int original = plan.selection().stack().count();
        if (destinationCount > transfer.count() || sourceCount + destinationCount + cursor.getCount() != original
                || count(transfer.itemId()) != transfer.playerCountBefore() + destinationCount
                || containerItemCount(handler, transfer.itemId()) != transfer.chestCountBefore() - original + sourceCount) {
            return session.fail("exact withdrawal conservation changed");
        }
        if (cursor.isEmpty()) {
            if (session.started && (destinationCount == transfer.count() || session.stopped)) {
                transactions.releaseCursorTransfer(owner);
                session.settled = true;
                Selection carried = destinationCount == 0 ? null : new Selection(
                        personalSupplies.playerStack(plan.destinationPlayerSlot()).orElseThrow(), destinationCount);
                return session.result = new CleanupWithdrawalResult(session.stopped
                        ? CleanupWithdrawalState.STOPPED : CleanupWithdrawalState.COMPLETE,
                        carried, "exact player destination and empty cursor observed");
            }
            if (session.stopped || !session.started && !allowNewSourcePickup) {
                session.settled = true;
                return session.result = new CleanupWithdrawalResult(CleanupWithdrawalState.STOPPED, null,
                        "stopped or paused before source pickup");
            }
            if (session.started || sourceCount != original || destinationCount != 0) return session.fail("source changed");
            transactions.claimCursorTransfer(owner, handler.syncId, source.id, destination.id,
                    transfer.itemId(), transfer.count(), nowMillis);
            if (issueSlotClick(owner, handler, source.id, 0, SlotActionType.PICKUP, nowMillis)) {
                session.started = true;
                return session.clicked();
            }
            return session.waiting();
        }
        if (!session.started || sourceCount != 0 || transactions.exactCursorTransfer(owner, handler.syncId,
                source.id, destination.id, transfer.itemId(), transfer.count()).isEmpty()) {
            return session.fail("cursor does not belong to this exact withdrawal");
        }
        if (session.stopped || destinationCount == transfer.count()) {
            return issueSlotClick(owner, handler, source.id, 0, SlotActionType.PICKUP, nowMillis)
                    ? session.clicked() : session.waiting();
        }
        int remaining = transfer.count() - destinationCount;
        if (!destination.canInsert(cursor) || destination.getMaxItemCount(cursor) < transfer.count()) {
            return session.fail("exact destination no longer accepts selected stack");
        }
        int placed = cursor.getCount() <= remaining ? cursor.getCount() : 1;
        boolean clicked = issueInventoryMutation(owner, handler, List.of(destination.id), nowMillis, null,
                InventoryTransactionEngine.SlotCountDelta.increase(destination.id, transfer.itemId(), placed),
                () -> client.interactionManager.clickSlot(handler.syncId, destination.id,
                        placed == cursor.getCount() ? 0 : 1, SlotActionType.PICKUP, client.player));
        return clicked ? session.clicked() : session.waiting();
    }

    private boolean cleanupStackMatches(StackFact expected, ItemStack actual) {
        return actual.isEmpty() || itemId(actual).equals(expected.item())
                && personalSupplies.observedComponents(actual).equals(expected.componentsKey());
    }

    /** Explicit cancellation release only after the caller has safely settled cursor/click debt. */
    public boolean releaseCleanupWithdrawal(CleanupWithdrawalPlan plan, String owner, long nowMillis) {
        if (cleanupWithdrawal == null || !cleanupWithdrawal.matches(plan, owner)) return true;
        if (client.player == null) return false;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (!transactions.gate(owner, snapshot(handler), nowMillis).permitsClick()
                || !handler.getCursorStack().isEmpty()) return false;
        String custodyOwner = transactions.diagnostics().cursorOwner();
        if (!custodyOwner.isBlank() && !custodyOwner.equals(owner)) return false;
        transactions.releaseCursorTransfer(owner);
        cleanupWithdrawal = null;
        return true;
    }

    public record CleanupWithdrawalPlan(Selection selection, ContainerIdentity identity,
            HomeTransferPlan transfer, int destinationPlayerSlot) {
        public CleanupWithdrawalPlan {
            Objects.requireNonNull(selection); Objects.requireNonNull(identity); Objects.requireNonNull(transfer);
            if (transfer.direction() != HomeStockPolicy.Direction.WITHDRAW || transfer.count() != selection.count()
                    || !transfer.itemId().equals(selection.stack().item()) || transfer.destinationCountBefore() != 0
                    || transfer.sourceSlot() >= identity.slotCount()
                    || !selection.stack().stackId().equals(MinecraftPersonalSuppliesAdapter.chestStackId(
                            identity, transfer.syncId(), transfer.sourceSlot()))
                    || destinationPlayerSlot < 0 || destinationPlayerSlot >= 36) {
                throw new IllegalArgumentException("invalid exact cleanup withdrawal");
            }
        }
    }
    public enum CleanupWithdrawalState { WAITING, CLICKED, COMPLETE, STOPPED, STALE }
    public record CleanupWithdrawalResult(CleanupWithdrawalState state, Selection carriedSelection, String detail) { }

    private static final class CleanupWithdrawalSession {
        final CleanupWithdrawalPlan plan;
        final String owner;
        boolean started;
        boolean stopped;
        boolean settled;
        CleanupWithdrawalResult result;
        CleanupWithdrawalSession(CleanupWithdrawalPlan plan, String owner) {
            this.plan = plan; this.owner = Objects.requireNonNull(owner);
        }
        boolean matches(CleanupWithdrawalPlan plan, String owner) { return this.plan.equals(plan) && this.owner.equals(owner); }
        CleanupWithdrawalResult fail(String detail) {
            return result = new CleanupWithdrawalResult(CleanupWithdrawalState.STALE, null, detail);
        }
        CleanupWithdrawalResult waiting() { return new CleanupWithdrawalResult(CleanupWithdrawalState.WAITING, null, "awaiting acknowledgement"); }
        CleanupWithdrawalResult clicked() { return new CleanupWithdrawalResult(CleanupWithdrawalState.CLICKED, null, "one exact pickup/placement issued"); }
    }

    public Map<String, Integer> counts() {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        if (client.player == null) return result;
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            result.merge(id, stack.getCount(), Integer::sum);
        }
        return Map.copyOf(result);
    }

    /** Fresh carried truth using the same serviceability boundary as Home accounting. */
    public Map<String, Integer> homePlayerCounts() {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        if (client.player == null) return Map.of();
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!serviceableHomeStack(stack)) continue;
            result.merge(itemId(stack), stack.getCount(), Math::addExact);
        }
        return Map.copyOf(result);
    }

    public int count(String itemId) {
        if (client.player == null) return 0;
        String wanted = normalizeId(itemId);
        int count = 0;
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && itemId(stack).equals(wanted)) count += stack.getCount();
        }
        return count;
    }

    /** Counts interchangeable catalog resources, such as any log or plank family. */
    public int countEquivalent(String itemId) {
        if (client.player == null) return 0;
        String wanted = canonicalItemId(itemId);
        int count = 0;
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && canonicalItemId(itemId(stack)).equals(wanted)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Resolves a planner equivalence class to one real Minecraft item key.
     * Paper transactions are intentionally exact and must never be prepared
     * against an alias while a different variant is actually carried.
     */
    public Optional<ConcreteItem> largestConcreteEquivalent(String itemId) {
        if (client.player == null) return Optional.empty();
        String wanted = canonicalItemId(itemId);
        LinkedHashMap<String, Integer> exactCounts = new LinkedHashMap<>();
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String exact = itemId(stack);
            if (!canonicalItemId(exact).equals(wanted)) continue;
            exactCounts.merge(exact, stack.getCount(), Math::addExact);
        }
        return exactCounts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new ConcreteItem(entry.getKey(), entry.getValue()));
    }

    public boolean has(String itemId, int count) {
        return count(itemId) >= Math.max(0, count);
    }

    /**
     * Whether the ordinary 36-slot player inventory can receive at least one
     * more item of this exact type. Equipment and crafting slots deliberately
     * do not count as pickup capacity.
     */
    public boolean canAccept(String itemId) {
        if (client.player == null) return false;
        String wanted = normalizeId(itemId);
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack.isEmpty()) return true;
            if (itemId(stack).equals(wanted) && stack.getCount() < stack.getMaxCount()) return true;
        }
        return false;
    }

    /** Physical receiving room, excluding cursor, armor and crafting slots. */
    public boolean hasEmptyInventorySlot() {
        return client.player != null && client.player.getInventory().getMainStacks()
                .stream().anyMatch(ItemStack::isEmpty);
    }

    public boolean isPlayerInventoryOpen() {
        return client.player != null && client.player.currentScreenHandler instanceof PlayerScreenHandler;
    }

    public boolean isCraftingTableOpen() {
        return client.player != null && client.player.currentScreenHandler instanceof CraftingScreenHandler;
    }

    /** A safe admission boundary between recipe operations, never an in-flight fill/result. */
    public boolean craftingInputsEmpty() {
        if (client.player == null) return false;
        ScreenHandler handler = client.player.currentScreenHandler;
        int inputs = handler instanceof CraftingScreenHandler ? 9
                : handler instanceof PlayerScreenHandler ? 4 : 0;
        if (inputs == 0 || !handler.getCursorStack().isEmpty()) return false;
        for (int slot = 0; slot <= inputs; slot++) {
            if (!handler.getSlot(slot).getStack().isEmpty()) return false;
        }
        return true;
    }

    public boolean isFurnaceOpen() {
        return client.player != null && client.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler;
    }

    public int openFurnaceSlotCount(int slotId, String itemId) {
        if (!(client.player != null
                && client.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) return 0;
        if (slotId < 0 || slotId > 2) throw new IllegalArgumentException("furnace slot must be 0..2");
        ItemStack stack = handler.getSlot(slotId).getStack();
        return !stack.isEmpty() && itemId(stack).equals(normalizeId(itemId)) ? stack.getCount() : 0;
    }

    /**
     * Exact generic-chest truth for Home accounting. Container counts exclude
     * custom/component-modified and nearly-broken stacks so foreign valuables
     * are never treated as managed stock. Deposit eligibility is narrower: it
     * includes only pristine main-inventory stacks after exact reservations.
     */
    public HomeChestSnapshot homeChestSnapshot(Map<String, Integer> protectedPlayerCounts) {
        Map<String, Integer> protectedCounts = normalizedNonNegativeCounts(
                protectedPlayerCounts, "protectedPlayerCounts");
        if (client.player == null
                || !(client.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler)) {
            return HomeChestSnapshot.closed();
        }
        PlayerInventory playerInventory = client.player.getInventory();
        LinkedHashMap<String, Integer> playerCounts = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> chestCounts = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> depositEligible = new LinkedHashMap<>();
        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !serviceableHomeStack(stack)) continue;
            String id = itemId(stack);
            if (slot.inventory == playerInventory) {
                playerCounts.merge(id, stack.getCount(), Math::addExact);
                // The selected hotbar stack is live body state (the tool,
                // weapon, food, or block Entity is actively holding), not
                // idle surplus. Keep the whole exact stack out of deposits.
                if (slot.getIndex() >= 0 && slot.getIndex() < 36
                        && slot.getIndex() != playerInventory.getSelectedSlot()
                        && pristineStack(stack)) {
                    depositEligible.merge(id, stack.getCount(), Math::addExact);
                }
            } else if (pristineStack(stack)) {
                chestCounts.merge(id, stack.getCount(), Math::addExact);
            }
        }
        // Equipment/offhand slots can satisfy a carried target even though they
        // are never deposit candidates and are not always present in a generic
        // container handler's player-slot projection.
        for (int slot = 36; slot < playerInventory.size(); slot++) {
            ItemStack stack = playerInventory.getStack(slot);
            if (!stack.isEmpty() && serviceableHomeStack(stack)) {
                playerCounts.merge(itemId(stack), stack.getCount(), Math::addExact);
            }
        }
        protectedCounts.forEach((item, count) -> depositEligible.computeIfPresent(
                item, (ignored, eligible) -> Math.max(0, eligible - count)));
        depositEligible.values().removeIf(count -> count <= 0);
        boolean emptyContainerSlot = handler.slots.stream().anyMatch(slot ->
                slot.inventory != playerInventory && slot.getStack().isEmpty());
        return new HomeChestSnapshot(true, handler.syncId,
                handler.getCursorStack().isEmpty(), playerCounts, chestCounts,
                depositEligible, emptyContainerSlot);
    }

    /** Exact pristine container stacks for a root-checkpointed Home allocation probe. */
    public List<HomeChestStack> homeChestPristineStacks() {
        if (client.player == null || !(client.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler)) return List.of();
        PlayerInventory playerInventory = client.player.getInventory();
        return handler.slots.stream()
                .filter(slot -> slot.inventory != playerInventory)
                .filter(slot -> !slot.getStack().isEmpty() && pristineStack(slot.getStack()))
                .sorted(Comparator.comparingInt(slot -> slot.id))
                .map(slot -> {
                    ItemStack stack = slot.getStack();
                    int durability = stack.isDamageable()
                            ? Math.max(0, stack.getMaxDamage() - stack.getDamage()) : 0;
                    return new HomeChestStack(
                            String.format(java.util.Locale.ROOT, "slot:%05d", slot.id),
                            slot.id, itemId(stack), stack.getCount(), durability);
                })
                .toList();
    }

    /**
     * Replans only the exact stack selected by a root allocation. A same-item
     * stack in another slot cannot silently replace the persisted origin.
     */
    public Optional<HomeTransferPlan> planExactHomeChestWithdrawal(
            int exactSourceSlot,
            String itemId,
            int maximumCount) {
        if (maximumCount <= 0) throw new IllegalArgumentException("maximumCount must be positive");
        String wanted = normalizeId(itemId);
        if (client.player == null || !(client.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler)
                || !handler.getCursorStack().isEmpty()
                || exactSourceSlot < 0 || exactSourceSlot >= handler.slots.size()) {
            return Optional.empty();
        }
        PlayerInventory playerInventory = client.player.getInventory();
        Slot source = handler.getSlot(exactSourceSlot);
        if (source.inventory == playerInventory || source.getStack().isEmpty()
                || !itemId(source.getStack()).equals(wanted)
                || !pristineStack(source.getStack())) return Optional.empty();
        // Eligibility is filtered above; conservation must use the same raw
        // physical totals as the click engine and observeHomeTransfer.
        InventorySnapshot observed = snapshot(handler);
        List<Slot> destinations = handler.slots.stream()
                .filter(slot -> slot.inventory == playerInventory)
                .filter(slot -> slot.getIndex() >= 0 && slot.getIndex() < 36)
                .filter(slot -> slot.getStack().isEmpty()
                        || itemId(slot.getStack()).equals(wanted)
                        && pristineStack(slot.getStack())
                        && slot.getStack().getCount() < slot.getStack().getMaxCount())
                .sorted(Comparator
                        .comparingInt((Slot slot) -> slot.getStack().isEmpty() ? 1 : 0)
                        .thenComparingInt(slot -> slot.id))
                .toList();
        for (Slot destination : destinations) {
            int present = destination.getStack().isEmpty()
                    ? 0 : destination.getStack().getCount();
            int capacity = destination.getStack().isEmpty()
                    ? source.getStack().getMaxCount()
                    : destination.getStack().getMaxCount() - present;
            int count = Math.min(maximumCount,
                    Math.min(source.getStack().getCount(), capacity));
            if (count <= 0) continue;
            return Optional.of(new HomeTransferPlan(
                    HomeStockPolicy.Direction.WITHDRAW,
                    wanted,
                    count,
                    handler.syncId,
                    source.id,
                    destination.id,
                    observed.playerCount(wanted),
                    containerItemCount(handler, wanted),
                    present));
        }
        return Optional.empty();
    }

    /** Plans one deterministic exact movement; no click is issued here. */
    public Optional<HomeTransferPlan> planHomeChestTransfer(
            HomeStockPolicy.Direction direction,
            String itemId,
            int maximumCount,
            Map<String, Integer> protectedPlayerCounts) {
        return planHomeChestTransfer(
                direction, itemId, maximumCount, protectedPlayerCounts, false);
    }

    /**
     * Plans an exact deposit of only the quantity earned after a managed-farm
     * baseline. The selected slot is eligible here because the caller-provided
     * protected count retains the complete pre-pass stack floor; ordinary Home
     * maintenance keeps its stronger selected-slot exclusion.
     */
    public Optional<HomeTransferPlan> planFarmEarnedHomeDeposit(
            String itemId,
            int maximumCount,
            Map<String, Integer> protectedPlayerCounts) {
        return planHomeChestTransfer(
                HomeStockPolicy.Direction.DEPOSIT,
                itemId,
                maximumCount,
                protectedPlayerCounts,
                true);
    }

    private Optional<HomeTransferPlan> planHomeChestTransfer(
            HomeStockPolicy.Direction direction,
            String itemId,
            int maximumCount,
            Map<String, Integer> protectedPlayerCounts,
            boolean allowSelectedDepositSource) {
        Objects.requireNonNull(direction, "direction");
        if (maximumCount <= 0) throw new IllegalArgumentException("maximumCount must be positive");
        String wanted = normalizeId(itemId);
        if (client.player == null
                || !(client.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler)
                || !handler.getCursorStack().isEmpty()) return Optional.empty();
        // Serviceable/pristine stock counts decide what may move, not the
        // conservation baseline: damaged same-ID items remain physically here.
        InventorySnapshot observed = snapshot(handler);
        PlayerInventory playerInventory = client.player.getInventory();
        List<Slot> sources = handler.slots.stream()
                .filter(slot -> (slot.inventory == playerInventory)
                        == (direction == HomeStockPolicy.Direction.DEPOSIT))
                .filter(slot -> !slot.getStack().isEmpty())
                .filter(slot -> itemId(slot.getStack()).equals(wanted))
                .filter(slot -> pristineStack(slot.getStack()))
                .filter(slot -> direction != HomeStockPolicy.Direction.DEPOSIT
                        || slot.getIndex() < 36)
                .filter(slot -> direction != HomeStockPolicy.Direction.DEPOSIT
                        || allowSelectedDepositSource
                        || slot.getIndex() != playerInventory.getSelectedSlot())
                .sorted(Comparator.comparingInt(slot -> slot.id))
                .toList();
        if (sources.isEmpty()) return Optional.empty();
        Map<String, Integer> protectedCounts = normalizedNonNegativeCounts(
                protectedPlayerCounts, "protectedPlayerCounts");
        int protectedRemaining = direction == HomeStockPolicy.Direction.DEPOSIT
                ? protectedCounts.getOrDefault(wanted, 0) : 0;
        Slot source = null;
        int sourceSpendable = 0;
        for (Slot candidate : sources) {
            int count = candidate.getStack().getCount();
            if (direction == HomeStockPolicy.Direction.DEPOSIT && protectedRemaining > 0) {
                int consumedProtection = Math.min(protectedRemaining, count);
                protectedRemaining -= consumedProtection;
                count -= consumedProtection;
            }
            if (count > 0) {
                source = candidate;
                sourceSpendable = count;
                break;
            }
        }
        if (source == null) return Optional.empty();

        List<Slot> destinations = handler.slots.stream()
                .filter(slot -> (slot.inventory == playerInventory)
                        != (direction == HomeStockPolicy.Direction.DEPOSIT))
                .filter(slot -> direction != HomeStockPolicy.Direction.WITHDRAW
                        || (slot.getIndex() >= 0 && slot.getIndex() < 36))
                .filter(slot -> slot.getStack().isEmpty()
                        || (itemId(slot.getStack()).equals(wanted)
                        && pristineStack(slot.getStack())
                        && slot.getStack().getCount() < slot.getStack().getMaxCount()))
                .sorted(Comparator
                        .comparingInt((Slot slot) -> slot.getStack().isEmpty() ? 1 : 0)
                        .thenComparingInt(slot -> slot.id))
                .toList();
        for (Slot destination : destinations) {
            ItemStack present = destination.getStack();
            int capacity = present.isEmpty()
                    ? source.getStack().getMaxCount()
                    : present.getMaxCount() - present.getCount();
            int count = Math.min(maximumCount, Math.min(sourceSpendable, capacity));
            if (count <= 0) continue;
            return Optional.of(new HomeTransferPlan(
                    direction, wanted, count, handler.syncId, source.id, destination.id,
                    observed.playerCount(wanted),
                    containerItemCount(handler, wanted),
                    present.isEmpty() ? 0 : present.getCount()));
        }
        return Optional.empty();
    }

    /**
     * Advances an exact cursor transfer by at most one acknowledged click.
     * The durable Home intent must be committed before the first call.
     */
    public HomeTransferResult homeChestTransferTick(
            HomeTransferPlan plan,
            String transactionOwner,
            long nowMillis) {
        Objects.requireNonNull(plan, "plan");
        if (client.player == null || client.interactionManager == null
                || !(client.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler)) {
            return HomeTransferResult.NEEDS_OWNED_CHEST;
        }
        if (handler.syncId != plan.syncId()) return HomeTransferResult.STALE_HANDLER;
        if (plan.sourceSlot() >= handler.slots.size()
                || plan.destinationSlot() >= handler.slots.size()) {
            return HomeTransferResult.SLOT_CHANGED;
        }
        PlayerInventory playerInventory = client.player.getInventory();
        Slot source = handler.getSlot(plan.sourceSlot());
        Slot destination = handler.getSlot(plan.destinationSlot());
        boolean sourceIsPlayer = source.inventory == playerInventory;
        boolean destinationIsPlayer = destination.inventory == playerInventory;
        if (sourceIsPlayer != (plan.direction() == HomeStockPolicy.Direction.DEPOSIT)
                || destinationIsPlayer == sourceIsPlayer) {
            return HomeTransferResult.SLOT_CHANGED;
        }

        InventorySnapshot current = snapshot(handler);
        InventoryTransactionEngine.Gate gate = transactions.gate(
                transactionOwner, current, nowMillis);
        if (!gate.permitsClick()) return HomeTransferResult.WAITING;

        int playerCount = current.playerCount(plan.itemId());
        int chestCount = containerItemCount(handler, plan.itemId());
        int expectedPlayer = plan.direction() == HomeStockPolicy.Direction.WITHDRAW
                ? Math.addExact(plan.playerCountBefore(), plan.count())
                : Math.subtractExact(plan.playerCountBefore(), plan.count());
        int expectedChest = plan.direction() == HomeStockPolicy.Direction.WITHDRAW
                ? Math.subtractExact(plan.chestCountBefore(), plan.count())
                : Math.addExact(plan.chestCountBefore(), plan.count());
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty()) {
            if (playerCount == expectedPlayer && chestCount == expectedChest) {
                transactions.releaseCursorTransfer(transactionOwner);
                LOGGER.info("Home transfer settled owner={} item={} count={} player={} chest={}",
                        transactionOwner, plan.itemId(), plan.count(), playerCount, chestCount);
                return HomeTransferResult.COMPLETE;
            }
            if (playerCount != plan.playerCountBefore()
                    || chestCount != plan.chestCountBefore()) {
                return HomeTransferResult.COUNTS_DIVERGED;
            }
            ItemStack sourceStack = source.getStack();
            if (sourceStack.isEmpty() || !itemId(sourceStack).equals(plan.itemId())
                    || !pristineStack(sourceStack) || sourceStack.getCount() < plan.count()) {
                return HomeTransferResult.SOURCE_CHANGED;
            }
            if (!destinationCanAccept(destination, plan.itemId(), plan.count())) {
                return HomeTransferResult.DESTINATION_CHANGED;
            }
            transactions.claimCursorTransfer(
                    transactionOwner, handler.syncId, source.id, destination.id,
                    plan.itemId(), plan.count(), nowMillis);
            client.interactionManager.clickSlot(
                    handler.syncId, source.id, 0, SlotActionType.PICKUP, client.player);
            transactions.recordClick(transactionOwner, current, List.of(source.id), nowMillis);
            LOGGER.info("Home transfer click owner={} phase=pickup item={} count={}",
                    transactionOwner, plan.itemId(), current.slot(source.id).orElseThrow().stack().count());
            return HomeTransferResult.CLICKED;
        }

        InventoryTransactionEngine.CursorTransfer custody = transactions.exactCursorTransfer(
                        transactionOwner, handler.syncId, source.id, destination.id,
                        plan.itemId(), plan.count())
                .orElse(null);
        if (custody == null) return HomeTransferResult.CURSOR_NOT_OWNED;
        if (!custody.itemId().equals(plan.itemId())
                || !itemId(cursor).equals(plan.itemId())) {
            return HomeTransferResult.CURSOR_NOT_OWNED;
        }
        int desiredDestinationCount = Math.addExact(
                plan.destinationCountBefore(), plan.count());
        int destinationCount = destination.getStack().isEmpty()
                ? 0 : destination.getStack().getCount();
        if (destinationCount < desiredDestinationCount) {
            int placementCount = homeTransferPlacementCount(
                    cursor.getCount(), desiredDestinationCount - destinationCount);
            if (!destinationCanAccept(destination, plan.itemId(), placementCount)) {
                return HomeTransferResult.DESTINATION_CHANGED;
            }
            boolean clicked = issueInventoryMutation(
                    transactionOwner, handler, List.of(destination.id), nowMillis,
                    null,
                    InventoryTransactionEngine.SlotCountDelta.increase(
                            destination.id, plan.itemId(), placementCount),
                    () -> client.interactionManager.clickSlot(
                            handler.syncId, destination.id,
                            placementCount == cursor.getCount() ? 0 : 1,
                            SlotActionType.PICKUP, client.player));
            if (clicked) LOGGER.info("Home transfer click owner={} phase=place item={} count={}",
                    transactionOwner, plan.itemId(), placementCount);
            return clicked ? HomeTransferResult.CLICKED : HomeTransferResult.WAITING;
        }
        if (destinationCount != desiredDestinationCount) {
            return HomeTransferResult.COUNTS_DIVERGED;
        }
        if (!acceptsCursorWithoutSwap(source, cursor)) {
            return HomeTransferResult.SOURCE_CHANGED;
        }
        boolean clicked = issueSlotClick(
                transactionOwner, handler, source.id, 0,
                SlotActionType.PICKUP, nowMillis);
        if (clicked) LOGGER.info("Home transfer click owner={} phase=return item={}",
                transactionOwner, plan.itemId());
        return clicked ? HomeTransferResult.CLICKED : HomeTransferResult.WAITING;
    }

    /** Bulk placement only when the entire cursor is the exact authorized remainder. */
    static int homeTransferPlacementCount(int cursorCount, int authorizedRemainder) {
        if (cursorCount <= 0 || authorizedRemainder <= 0) {
            throw new IllegalArgumentException("Home placement needs a nonempty cursor and remainder");
        }
        return cursorCount == authorizedRemainder ? cursorCount : 1;
    }

    public HomeTransferObservation observeHomeTransfer(HomeTransferPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (client.player == null || !(client.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler)) {
            return new HomeTransferObservation(-1, false, 0, 0);
        }
        InventorySnapshot observed = snapshot(handler);
        return new HomeTransferObservation(handler.syncId,
                handler.getCursorStack().isEmpty(),
                observed.playerCount(plan.itemId()),
                containerItemCount(handler, plan.itemId()));
    }

    /** Read-only exact in-process custody proof for Home preemption. */
    public boolean hasExactHomeTransferCustody(
            HomeTransferPlan plan,
            String transactionOwner) {
        Objects.requireNonNull(plan, "plan");
        if (client.player == null || !(client.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler)) return false;
        return transactions.exactCursorTransfer(
                transactionOwner, handler.syncId,
                plan.sourceSlot(), plan.destinationSlot(),
                plan.itemId(), plan.count()).isPresent();
    }

    /**
     * Releases only a settled Home cursor claim. A fresh client has no
     * in-process claim to release; a foreign or non-empty cursor remains a
     * fail-closed handoff boundary.
     */
    public boolean releaseSettledHomeTransferCustody(
            HomeTransferPlan plan,
            String transactionOwner) {
        Objects.requireNonNull(plan, "plan");
        String owner = Objects.requireNonNullElse(transactionOwner, "").trim();
        if (owner.isEmpty() || !cursorEmpty()
                || !transactions.diagnostics().pendingClickOwner().isBlank()) return false;
        String cursorOwner = transactions.diagnostics().cursorOwner();
        if (cursorOwner.isBlank()) return true;
        if (!cursorOwner.equals(owner)
                || !hasExactHomeTransferCustody(plan, owner)) return false;
        transactions.releaseCursorTransfer(owner);
        return transactions.diagnostics().cursorOwner().isBlank();
    }

    public void closeHandledScreen() {
        closeHandledScreen(System.currentTimeMillis());
    }

    /** Client-predicted slot deltas alone must not retire durable transfer intent. */
    public boolean homeTransferClicksSettled(long nowMillis) {
        reconcilePendingTransaction(nowMillis);
        return transactions.diagnostics().pendingClickOwner().isBlank();
    }

    /** Settle an obsolete transfer without guessing whether another player's count change was ours. */
    public HomeTransferSettlement settleChangedHomeTransfer(
            HomeTransferPlan plan, String owner, long nowMillis) {
        if (client.player == null || client.interactionManager == null
                || !(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            return HomeTransferSettlement.WAITING;
        }
        if (!transactions.gate(owner, snapshot(handler), nowMillis).permitsClick()) {
            return HomeTransferSettlement.WAITING;
        }
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty()) {
            return releaseSettledHomeTransferCustody(plan, owner)
                    ? HomeTransferSettlement.SETTLED : HomeTransferSettlement.FOREIGN_CURSOR;
        }
        if (!hasExactHomeTransferCustody(plan, owner) || !itemId(cursor).equals(plan.itemId())) {
            return HomeTransferSettlement.FOREIGN_CURSOR;
        }
        // Prefer returning to the original source. Never swap another stack onto the cursor,
        // drop cargo, or close the screen with an outstanding click.
        int destination = -1;
        if (plan.sourceSlot() < handler.slots.size()
                && acceptsCursorWithoutSwap(handler.getSlot(plan.sourceSlot()), cursor)) {
            destination = plan.sourceSlot();
        }
        if (destination < 0) destination = cursorDestinationSlot(handler, cursor);
        if (destination < 0) {
            for (Slot slot : handler.slots) {
                if (acceptsCursorWithoutSwap(slot, cursor)) { destination = slot.id; break; }
            }
        }
        if (destination < 0) return HomeTransferSettlement.NO_RECEIVING_SPACE;
        issueSlotClick(owner, handler, destination, 0, SlotActionType.PICKUP, nowMillis);
        return HomeTransferSettlement.WAITING;
    }

    static boolean acceptsCursorWithoutSwap(Slot slot, ItemStack cursor) {
        if (!slot.canInsert(cursor)) return false;
        ItemStack present = slot.getStack();
        return present.isEmpty() || (ItemStack.areItemsAndComponentsEqual(present, cursor)
                && present.getCount() < Math.min(present.getMaxCount(), slot.getMaxItemCount(present)));
    }

    /**
     * Reconciles any owned cursor before closing a container.  Closing a GUI is
     * itself forbidden while an earlier click has no observed acknowledgement.
     */
    public boolean closeHandledScreen(long nowMillis) {
        if (client.player == null || client.interactionManager == null) return false;
        ScreenHandler handler = client.player.currentScreenHandler;
        String owner = "screen-close:" + handler.syncId;
        InventorySnapshot current = snapshot(handler);
        if (!transactions.gate(owner, current, nowMillis).permitsClick()) return false;
        ItemStack cursor = handler.getCursorStack();
        if (!cursor.isEmpty()) {
            int destination = cursorDestinationSlot(handler, cursor);
            if (destination < 0) return false;
            issueSlotClick(
                    owner, handler, destination, 0,
                    SlotActionType.PICKUP, nowMillis);
            return false;
        }
        if (!(handler instanceof PlayerScreenHandler)) {
            client.player.closeHandledScreen();
        } else if (client.currentScreen instanceof HandledScreen<?>) {
            // Player inventory has no remote container to close, but leaving
            // its GUI open still consumes all movement input. The cursor is
            // proven empty above, so closing the local screen cannot drop or
            // overwrite an unknown stack.
            client.setScreen(null);
        }
        preparedRecipe = "";
        preparedRecipeSyncId = -1;
        equipSession = null;
        transactions.resetInteractive();
        return true;
    }

    /**
     * Closes only after acknowledgement and only with an actually empty
     * cursor. Unlike the generic cleanup path, this method never chooses a
     * destination slot and therefore cannot move foreign or excess cargo.
     */
    public SafeCloseResult closeHandledScreenIfCursorEmpty(long nowMillis) {
        if (client.player == null || client.interactionManager == null) {
            return SafeCloseResult.WAITING_ACKNOWLEDGEMENT;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        InventorySnapshot current = snapshot(handler);
        InventoryTransactionEngine.Gate gate = transactions.gate(
                "home-safe-close:" + handler.syncId, current, nowMillis);
        if (!gate.permitsClick()) return SafeCloseResult.WAITING_ACKNOWLEDGEMENT;
        if (!handler.getCursorStack().isEmpty()) return SafeCloseResult.CURSOR_OCCUPIED;
        if (!(handler instanceof PlayerScreenHandler)) {
            client.player.closeHandledScreen();
        } else if (client.currentScreen instanceof HandledScreen<?>) {
            client.setScreen(null);
        }
        preparedRecipe = "";
        preparedRecipeSyncId = -1;
        equipSession = null;
        transactions.resetInteractive();
        return SafeCloseResult.CLOSED;
    }

    /** Drops one server-confirmed item from an inventory slot. */
    public ClickResult dropOne(String itemId, long nowMillis) {
        return dropOne(itemId, "drop-one:" + normalizeId(itemId), nowMillis);
    }

    public ClickResult dropOne(String itemId, String transactionOwner, long nowMillis) {
        if (!ensurePlayerHandler()) return ClickResult.WAITING;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (!handler.getCursorStack().isEmpty()) return ClickResult.CURSOR_NOT_EMPTY;
        int inventorySlot = findInventorySlot(itemId);
        if (inventorySlot < 0) return ClickResult.ITEM_MISSING;
        int handlerSlot = handlerSlot(handler, inventorySlot);
        if (handlerSlot < 0) return ClickResult.SLOT_UNAVAILABLE;
        if (!issueSlotClick(
                transactionOwner, handler, handlerSlot, 0,
                SlotActionType.THROW, nowMillis)) return ClickResult.WAITING;
        return ClickResult.CLICKED;
    }

    /** Drops one item from an exact ID first, then from a catalog-equivalent stack. */
    public ClickResult dropOneEquivalent(String itemId, long nowMillis) {
        String transactionOwner = "drop-one-equivalent:" + normalizeId(itemId);
        if (!ensurePlayerHandler()) return ClickResult.WAITING;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (!handler.getCursorStack().isEmpty()) return ClickResult.CURSOR_NOT_EMPTY;
        int inventorySlot = findInventorySlot(itemId);
        if (inventorySlot < 0) inventorySlot = findEquivalentInventorySlot(itemId);
        if (inventorySlot < 0) return ClickResult.ITEM_MISSING;
        int handlerSlot = handlerSlot(handler, inventorySlot);
        if (handlerSlot < 0) return ClickResult.SLOT_UNAVAILABLE;
        if (!issueSlotClick(
                transactionOwner, handler, handlerSlot, 0,
                SlotActionType.THROW, nowMillis)) return ClickResult.WAITING;
        return ClickResult.CLICKED;
    }

    /**
     * Drops one item, or the entire matching stack when it fits inside the
     * requested remainder. SlotActionType.THROW button 1 is vanilla Ctrl-Q.
     */
    public DropResult dropEquivalentBatch(String itemId, int maximumCount, long nowMillis) {
        return dropEquivalentBatch(
                itemId, maximumCount, "drop-equivalent:" + normalizeId(itemId), nowMillis);
    }

    public DropResult dropEquivalentBatch(
            String itemId,
            int maximumCount,
            String transactionOwner,
            long nowMillis) {
        if (!ensurePlayerHandler()) return new DropResult(ClickResult.WAITING, 0);
        ScreenHandler handler = client.player.currentScreenHandler;
        if (!handler.getCursorStack().isEmpty()) {
            return new DropResult(ClickResult.CURSOR_NOT_EMPTY, 0);
        }
        int inventorySlot = findInventorySlot(itemId);
        if (inventorySlot < 0) inventorySlot = findEquivalentInventorySlot(itemId);
        if (inventorySlot < 0) return new DropResult(ClickResult.ITEM_MISSING, 0);
        int handlerSlot = handlerSlot(handler, inventorySlot);
        if (handlerSlot < 0) return new DropResult(ClickResult.SLOT_UNAVAILABLE, 0);

        int stackCount = client.player.getInventory().getStack(inventorySlot).getCount();
        int dropped = stackCount <= Math.max(1, maximumCount) ? stackCount : 1;
        int throwButton = dropped == stackCount ? 1 : 0;
        if (!issueSlotClick(
                transactionOwner, handler, handlerSlot, throwButton,
                SlotActionType.THROW, nowMillis)) {
            return new DropResult(ClickResult.WAITING, 0);
        }
        return new DropResult(ClickResult.CLICKED, dropped);
    }

    /** Drops one item or one whole stack of the exact Minecraft item key. */
    public DropResult dropExactBatch(String itemId, int maximumCount, long nowMillis) {
        return dropExactBatch(
                itemId, maximumCount, "drop-exact:" + normalizeId(itemId), nowMillis);
    }

    public DropResult dropExactBatch(
            String itemId,
            int maximumCount,
            String transactionOwner,
            long nowMillis) {
        if (!ensurePlayerHandler()) return new DropResult(ClickResult.WAITING, 0);
        ScreenHandler handler = client.player.currentScreenHandler;
        if (!handler.getCursorStack().isEmpty()) {
            return new DropResult(ClickResult.CURSOR_NOT_EMPTY, 0);
        }
        int inventorySlot = findInventorySlot(itemId);
        if (inventorySlot < 0) return new DropResult(ClickResult.ITEM_MISSING, 0);
        int handlerSlot = handlerSlot(handler, inventorySlot);
        if (handlerSlot < 0) return new DropResult(ClickResult.SLOT_UNAVAILABLE, 0);

        int stackCount = client.player.getInventory().getStack(inventorySlot).getCount();
        int dropped = stackCount <= Math.max(1, maximumCount) ? stackCount : 1;
        int throwButton = dropped == stackCount ? 1 : 0;
        if (!issueSlotClick(
                transactionOwner, handler, handlerSlot, throwButton,
                SlotActionType.THROW, nowMillis)) {
            return new DropResult(ClickResult.WAITING, 0);
        }
        return new DropResult(ClickResult.CLICKED, dropped);
    }

    /** Quantity the next batched equivalent drop would use. */
    public int equivalentDropQuantity(String itemId, int maximumCount) {
        if (client.player == null) return 0;
        int inventorySlot = findInventorySlot(itemId);
        if (inventorySlot < 0) inventorySlot = findEquivalentInventorySlot(itemId);
        if (inventorySlot < 0) return 0;
        int stackCount = client.player.getInventory().getStack(inventorySlot).getCount();
        return stackCount <= Math.max(1, maximumCount) ? stackCount : 1;
    }

    /** Quantity the next exact-ID physical drop would use. */
    public int exactDropQuantity(String itemId, int maximumCount) {
        if (client.player == null) return 0;
        int inventorySlot = findInventorySlot(itemId);
        if (inventorySlot < 0) return 0;
        int stackCount = client.player.getInventory().getStack(inventorySlot).getCount();
        return stackCount <= Math.max(1, maximumCount) ? stackCount : 1;
    }

    /** Moves a matching stack into a selected hotbar slot using a normal swap packet. */
    public ClickResult moveToHotbar(String itemId, int hotbarSlot, long nowMillis) {
        return moveToHotbar(
                itemId, hotbarSlot,
                "hotbar:" + normalizeId(itemId) + ':' + hotbarSlot,
                nowMillis);
    }

    public ClickResult moveToHotbar(
            String itemId,
            int hotbarSlot,
            String transactionOwner,
            long nowMillis) {
        if (hotbarSlot < 0 || hotbarSlot >= PlayerInventory.getHotbarSize()) {
            throw new IllegalArgumentException("hotbar slot must be 0..8");
        }
        if (!ensurePlayerHandler()) return ClickResult.WAITING;
        ScreenHandler handler = client.player.currentScreenHandler;
        // SWAP mutates the local handler optimistically. Fence that prediction
        // before ALREADY_DONE can authorize item use; otherwise rapid Home
        // placements can use three locally swapped stacks in less than one
        // server-correction window.
        if (!transactions.gate(
                transactionOwner, snapshot(handler), nowMillis).permitsClick()) {
            return ClickResult.WAITING;
        }
        int inventorySlot = findInventorySlot(itemId);
        if (inventorySlot < 0) return ClickResult.ITEM_MISSING;
        if (inventorySlot == hotbarSlot) {
            client.player.getInventory().setSelectedSlot(hotbarSlot);
            return ClickResult.ALREADY_DONE;
        }
        int source = handlerSlot(handler, inventorySlot);
        if (source < 0) return ClickResult.SLOT_UNAVAILABLE;
        int hotbarHandlerSlot = handlerSlot(handler, hotbarSlot);
        if (!issueSlotClick(
                transactionOwner, handler, source, hotbarSlot,
                SlotActionType.SWAP, nowMillis,
                hotbarHandlerSlot < 0 ? List.of(source) : List.of(source, hotbarHandlerSlot))) {
            return ClickResult.WAITING;
        }
        client.player.getInventory().setSelectedSlot(hotbarSlot);
        return ClickResult.CLICKED;
    }

    /** Selection-only cleanup; does not move a stack or touch the cursor. */
    public boolean selectHotbarSlotNow(int hotbarSlot) {
        if (client.player == null || hotbarSlot < 0
                || hotbarSlot >= PlayerInventory.getHotbarSize()) return false;
        if (client.player.getInventory().getSelectedSlot() != hotbarSlot) {
            client.player.getInventory().setSelectedSlot(hotbarSlot);
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendPacket(
                        new UpdateSelectedSlotC2SPacket(hotbarSlot));
            }
        }
        return true;
    }

    /** Moves the exact selected main-inventory stack, preserving durability/enchant identity. */
    public ClickResult moveInventorySlotToHotbar(
            int inventorySlot,
            int hotbarSlot,
            long nowMillis) {
        return moveInventorySlotToHotbar(
                inventorySlot, hotbarSlot,
                "hotbar-slot:" + inventorySlot + ':' + hotbarSlot,
                nowMillis);
    }

    public ClickResult moveInventorySlotToHotbar(
            int inventorySlot,
            int hotbarSlot,
            String transactionOwner,
            long nowMillis) {
        if (hotbarSlot < 0 || hotbarSlot >= PlayerInventory.getHotbarSize()) {
            throw new IllegalArgumentException("hotbar slot must be 0..8");
        }
        if (!ensurePlayerHandler()) return ClickResult.WAITING;
        PlayerInventory inventory = client.player.getInventory();
        if (inventorySlot < 0 || inventorySlot >= inventory.getMainStacks().size()) {
            throw new IllegalArgumentException("main inventory slot is out of range");
        }
        if (inventory.getStack(inventorySlot).isEmpty()) return ClickResult.ITEM_MISSING;
        if (inventorySlot == hotbarSlot) {
            inventory.setSelectedSlot(hotbarSlot);
            return ClickResult.ALREADY_DONE;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        int source = handlerSlot(handler, inventorySlot);
        if (source < 0) return ClickResult.SLOT_UNAVAILABLE;
        int hotbarHandlerSlot = handlerSlot(handler, hotbarSlot);
        if (!issueSlotClick(
                transactionOwner, handler, source, hotbarSlot,
                SlotActionType.SWAP, nowMillis,
                hotbarHandlerSlot < 0 ? List.of(source) : List.of(source, hotbarHandlerSlot))) {
            return ClickResult.WAITING;
        }
        inventory.setSelectedSlot(hotbarSlot);
        return ClickResult.CLICKED;
    }

    /**
     * Equips armor, a shield, or a main-hand item using vanilla slot clicks.
     * Existing equipment is swapped back into the source inventory slot.
     */
    public ClickResult equip(String itemId, long nowMillis) {
        return equip(itemId, "equip:" + normalizeId(itemId), nowMillis);
    }

    public ClickResult equip(String itemId, String transactionOwner, long nowMillis) {
        return equipServiceable(itemId, 0, transactionOwner, nowMillis);
    }

    /** Selects a same-item tool only when its exact live stack can fund the committed work. */
    public ClickResult equipServiceable(
            String itemId,
            int minimumRemainingDurability,
            String transactionOwner,
            long nowMillis) {
        if (minimumRemainingDurability < 0) {
            throw new IllegalArgumentException("minimumRemainingDurability cannot be negative");
        }
        if (!ensurePlayerHandler()) return ClickResult.WAITING;
        String wanted = normalizeId(itemId);
        ScreenHandler handler = client.player.currentScreenHandler;
        InventorySnapshot current = snapshot(handler);
        // A vanilla slot click mutates the local handler optimistically. Reconcile the exact
        // owner's pending click before either the staged armor flow or isEquipped can accept
        // that prediction as durable equipment. Otherwise a later server correction can make
        // the caller resume and repeat the same SWAP forever.
        if (!transactions.gate(transactionOwner, current, nowMillis).permitsClick()) {
            return ClickResult.WAITING;
        }
        if (equipSession != null && equipSession.matches(wanted, handler.syncId)) {
            if (equipSession.stage == 1) {
                if (!issueSlotClick(
                        transactionOwner, handler, equipSession.destinationSlot, 0,
                        SlotActionType.PICKUP, nowMillis)) return ClickResult.WAITING;
                equipSession.stage = 2;
                return ClickResult.CLICKED;
            }
            if (handler.getCursorStack().isEmpty()) {
                equipSession = null;
                return isEquipped(wanted, minimumRemainingDurability)
                        ? ClickResult.ALREADY_DONE
                        : ClickResult.WAITING;
            }
            if (!issueSlotClick(
                    transactionOwner, handler, equipSession.sourceSlot, 0,
                    SlotActionType.PICKUP, nowMillis)) return ClickResult.WAITING;
            equipSession = null;
            return ClickResult.CLICKED;
        }
        if (isEquipped(wanted, minimumRemainingDurability)) {
            equipSession = null;
            return ClickResult.ALREADY_DONE;
        }
        if (!handler.getCursorStack().isEmpty()) return ClickResult.CURSOR_NOT_EMPTY;

        int inventorySlot = findServiceableInventorySlot(
                wanted, minimumRemainingDurability);
        if (inventorySlot < 0) return ClickResult.ITEM_MISSING;
        ItemStack stack = client.player.getInventory().getStack(inventorySlot);
        EquipmentSlot preferred = client.player.getPreferredEquipmentSlot(stack);
        int source = handlerSlot(handler, inventorySlot);
        if (source < 0) return ClickResult.SLOT_UNAVAILABLE;

        int destination = equipmentHandlerSlot(preferred);
        if (destination >= 0) {
            if (!issueSlotClick(
                    transactionOwner, handler, source, 0,
                    SlotActionType.PICKUP, nowMillis)) return ClickResult.WAITING;
            equipSession = new EquipSession(wanted, handler.syncId, source, destination, 1);
        } else {
            int selected = client.player.getInventory().getSelectedSlot();
            int selectedHandlerSlot = handlerSlot(handler, selected);
            if (!issueSlotClick(
                    transactionOwner, handler, source, selected,
                    SlotActionType.SWAP, nowMillis,
                    selectedHandlerSlot < 0 ? List.of(source) : List.of(source, selectedHandlerSlot))) {
                return ClickResult.WAITING;
            }
        }
        return ClickResult.CLICKED;
    }

    /**
     * Acknowledgement-fenced hotbar selection for a main-hand action. Unlike generic equipment,
     * an off-hand same-item stack can never satisfy this postcondition: the exact selected
     * main-hand stack must meet the requested durability threshold. Mining requests at least
     * two remaining uses; combat may request zero while retaining the same acknowledgement fence.
     */
    public ClickResult selectServiceableMainHand(
            String itemId,
            int minimumRemainingDurability,
            String transactionOwner,
            long nowMillis) {
        if (minimumRemainingDurability < 0) {
            throw new IllegalArgumentException(
                    "minimumRemainingDurability cannot be negative");
        }
        if (!ensurePlayerHandler()) return ClickResult.WAITING;
        String wanted = normalizeId(itemId);
        ScreenHandler handler = client.player.currentScreenHandler;
        InventorySnapshot current = snapshot(handler);
        // SWAP mutates the local handler optimistically. Do not accept the apparent selected
        // stack as the durable postcondition until the exact transaction owner has crossed the
        // same revision/semantic-stability acknowledgement fence as every other inventory click.
        if (!transactions.gate(transactionOwner, current, nowMillis).permitsClick()) {
            return ClickResult.WAITING;
        }
        ItemStack selectedStack = client.player.getMainHandStack();
        if (!selectedStack.isEmpty() && itemId(selectedStack).equals(wanted)
                && remainingDurability(selectedStack) >= minimumRemainingDurability) {
            return ClickResult.ALREADY_DONE;
        }
        if (!handler.getCursorStack().isEmpty()) return ClickResult.CURSOR_NOT_EMPTY;
        int inventorySlot = findServiceableInventorySlot(
                wanted, minimumRemainingDurability);
        if (inventorySlot < 0) return ClickResult.ITEM_MISSING;
        int source = handlerSlot(handler, inventorySlot);
        if (source < 0) return ClickResult.SLOT_UNAVAILABLE;
        int selected = client.player.getInventory().getSelectedSlot();
        int selectedHandlerSlot = handlerSlot(handler, selected);
        if (!issueSlotClick(
                transactionOwner, handler, source, selected,
                SlotActionType.SWAP, nowMillis,
                selectedHandlerSlot < 0 ? List.of(source)
                        : List.of(source, selectedHandlerSlot))) {
            return ClickResult.WAITING;
        }
        return ClickResult.CLICKED;
    }

    public boolean isEquipped(String itemId) {
        return isEquipped(normalizeId(itemId), 0);
    }

    /** Read-only admission; only an idle lease owner may call equipEmptyArmor. */
    public boolean emptyArmorNeedsAttention() {
        return EMPTY_ARMOR_OWNER.equals(transactions.diagnostics().pendingClickOwner())
                || emptyArmorCandidate() >= 0;
    }

    /**
     * One vanilla click into an EMPTY armor/offhand slot. No cursor sequence, screen
     * closing, worn-item replacement, or task creation. A rejected unchanged stack
     * is not clicked indefinitely; moving/replacing it permits a fresh observation.
     */
    public ClickResult equipEmptyArmor(boolean authorized, long nowMillis) {
        reconcilePendingTransaction(nowMillis);
        if (!authorized) return ClickResult.WAITING;
        int source = emptyArmorCandidate();
        if (source < 0) return emptyArmorNeedsAttention() ? ClickResult.WAITING : ClickResult.ALREADY_DONE;
        PlayerInventory inventory = client.player.getInventory();
        ItemStack stack = inventory.getStack(source);
        EquipmentSlot armor = emptyEquipmentSlot(stack);
        ScreenHandler handler = client.player.currentScreenHandler;
        int sourceHandler = handlerSlot(handler, source);
        int destination = equipmentHandlerSlot(armor);
        ItemStack attempted = stack.copy();
        // This is an internal player-inventory transfer: aggregate item count must
        // NOT decrease as it does for container QUICK_MOVE. Watch both real slots.
        if (!issueInventoryMutation(EMPTY_ARMOR_OWNER, handler, List.of(sourceHandler, destination),
                nowMillis, () -> client.interactionManager.clickSlot(handler.syncId, sourceHandler,
                        armor == EquipmentSlot.OFFHAND ? 40 : 0,
                        armor == EquipmentSlot.OFFHAND ? SlotActionType.SWAP : SlotActionType.QUICK_MOVE,
                        client.player))) return ClickResult.WAITING;
        attemptedEmptyArmor.put(source, attempted);
        LOGGER.info("Empty armor slot recovery issued item={} slot={} source={}", itemId(attempted), armor, source);
        return ClickResult.CLICKED;
    }

    private int emptyArmorCandidate() {
        if (client.player == null || client.interactionManager == null || client.currentScreen != null
                || !(client.player.currentScreenHandler instanceof PlayerScreenHandler)
                || !cursorEmpty() || equipSession != null
                || cleanupDrop != null && cleanupDrop.unsettled()
                || cleanupWithdrawal != null && !cleanupWithdrawal.settled) return -1;
        var diagnostics = transactions.diagnostics();
        if (!diagnostics.pendingClickOwner().isBlank() || !diagnostics.cursorOwner().isBlank()
                || diagnostics.pendingRemovals() != 0) return -1;
        PlayerInventory inventory = client.player.getInventory();
        if (emptyArmorInventory != inventory) {
            emptyArmorInventory = inventory;
            attemptedEmptyArmor.clear();
        }
        for (int source = 0; source < 36; source++) {
            ItemStack stack = inventory.getStack(source);
            ItemStack attempted = attemptedEmptyArmor.get(source);
            if (attempted != null && ItemStack.areEqual(stack, attempted)) continue;
            attemptedEmptyArmor.remove(source);
            EquipmentSlot armor = emptyEquipmentSlot(stack);
            if (armor != null && remainingDurability(stack) > 0
                    && client.player.getEquippedStack(armor).isEmpty()) return source;
        }
        return -1;
    }

    private static EquipmentSlot emptyEquipmentSlot(ItemStack stack) {
        // Vanilla shift-click equips armor, but a shield needs the offhand swap
        // button. Both stay under the same safe idle inventory transaction owner.
        return stack.isOf(Items.SHIELD) ? EquipmentSlot.OFFHAND
                : MinecraftPersonalSuppliesAdapter.protectiveArmorSlot(stack);
    }

    private boolean isEquipped(String wanted, int minimumRemainingDurability) {
        if (client.player == null) return false;
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack equipped = client.player.getEquippedStack(slot);
            EquipmentSlot armor = MinecraftPersonalSuppliesAdapter.protectiveArmorSlot(equipped);
            if (armor != null && armor != slot) continue;
            if (!equipped.isEmpty() && itemId(equipped).equals(wanted)
                    && remainingDurability(equipped) >= minimumRemainingDurability) return true;
        }
        return false;
    }

    public Optional<RecipeChoice> findCraftingRecipe(String itemId) {
        return findCraftingRecipe(
                itemId, 0, ResourceCatalog.RecipeMetadata.unknown(), Map.of());
    }

    /**
     * Finds the unlocked client recipe-book entry which represents one
     * planner-selected recipe variant.
     *
     * <p>{@code selectedIngredientCounts} may contain totals for any positive
     * number of recipe operations. Minecraft 1.21.8 does not expose a
     * namespaced recipe key on {@link RecipeDisplayEntry}; the packet-local
     * integer ID is therefore used only after the display has been correlated
     * by output, layout, dimensions, slot alternatives and selected concrete
     * ingredients.</p>
     */
    public Optional<RecipeChoice> findCraftingRecipe(
            String itemId,
            ResourceCatalog.RecipeMetadata recipeMetadata,
            Map<String, Integer> selectedIngredientCounts) {
        return findCraftingRecipe(itemId, 0, recipeMetadata, selectedIngredientCounts);
    }

    public Optional<RecipeChoice> findCraftingRecipe(
            String itemId,
            int expectedOutputCount,
            ResourceCatalog.RecipeMetadata recipeMetadata,
            Map<String, Integer> selectedIngredientCounts) {
        if (client.player == null || client.world == null) return Optional.empty();
        if (expectedOutputCount < 0) {
            throw new IllegalArgumentException("expected output count cannot be negative");
        }
        ResourceCatalog.RecipeMetadata metadata = Objects.requireNonNull(
                recipeMetadata, "recipeMetadata");
        Map<String, Integer> selected = normalizeIngredientCounts(selectedIngredientCounts);
        boolean selectedVariant = expectedOutputCount > 0
                || metadata.recipeId() != null
                || metadata.layout() != ResourceCatalog.RecipeLayout.UNKNOWN
                || !selected.isEmpty();
        String wanted = normalizeId(itemId);
        var context = SlotDisplayContexts.createParameters(client.world);
        RecipeFinder finder = new RecipeFinder();
        client.player.getInventory().populateRecipeFinder(finder);
        if (client.player.currentScreenHandler instanceof AbstractRecipeScreenHandler recipeHandler) {
            recipeHandler.populateRecipeFinder(finder);
        }
        List<CraftingRecipeCandidate> choices = new ArrayList<>();
        for (RecipeResultCollection collection : client.player.getRecipeBook().getOrderedResults()) {
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                RecipeDisplay display = entry.display();
                boolean crafting = display instanceof ShapedCraftingRecipeDisplay
                        || display instanceof ShapelessCraftingRecipeDisplay;
                if (!crafting) continue;
                ItemStack output;
                try {
                    output = display.result().getFirst(context);
                } catch (RuntimeException unsupportedDisplay) {
                    continue;
                }
                if (output.isEmpty()) continue;
                String actualOutput = itemId(output);
                if (!matchesCraftingOutput(
                        wanted, actualOutput, metadata, selectedVariant)) continue;
                if (expectedOutputCount > 0 && output.getCount() != expectedOutputCount) continue;
                Optional<CraftingDisplayShape> shape = craftingDisplayShape(display, context);
                if (shape.isEmpty()) continue;
                CraftingDisplayShape resolvedShape = shape.orElseThrow();
                if (!matchesCraftingSelection(
                        resolvedShape.layout(),
                        resolvedShape.width(),
                        resolvedShape.height(),
                        resolvedShape.slots(),
                        metadata,
                        selected)) continue;
                boolean table = requiresTable(display);
                boolean craftable;
                try {
                    craftable = entry.isCraftable(finder);
                } catch (RuntimeException malformedRequirements) {
                    continue;
                }
                RecipeChoice choice = new RecipeChoice(
                        entry, output.getCount(), table, craftable);
                choices.add(new CraftingRecipeCandidate(
                        choice, resolvedShape.stableKey()));
            }
        }
        return choices.stream()
                .sorted(Comparator
                        .comparing((CraftingRecipeCandidate candidate) ->
                                candidate.choice().craftable()).reversed()
                        .thenComparing(candidate -> candidate.choice().requiresTable())
                        .thenComparing(
                                (CraftingRecipeCandidate candidate) ->
                                        candidate.choice().outputCount(),
                                Comparator.reverseOrder())
                        .thenComparing(CraftingRecipeCandidate::stableKey)
                        .thenComparingInt(candidate -> candidate.choice().entry().id().index()))
                .map(CraftingRecipeCandidate::choice)
                .findFirst();
    }

    /** Fills a known recipe, crafts exactly one operation, then stores its cursor result. */
    public CraftResult craftTick(String itemId, long nowMillis) {
        return craftTick(itemId, "craft:" + normalizeId(itemId), nowMillis);
    }

    public CraftResult craftTick(String itemId, String transactionOwner, long nowMillis) {
        return craftTick(
                itemId,
                0,
                ResourceCatalog.RecipeMetadata.unknown(),
                Map.of(),
                transactionOwner,
                nowMillis);
    }

    public CraftResult craftTick(
            String itemId,
            ResourceCatalog.RecipeMetadata recipeMetadata,
            Map<String, Integer> selectedIngredientCounts,
            long nowMillis) {
        return craftTick(
                itemId,
                0,
                recipeMetadata,
                selectedIngredientCounts,
                "craft:" + normalizeId(itemId),
                nowMillis);
    }

    public CraftResult craftTick(
            String itemId,
            ResourceCatalog.RecipeMetadata recipeMetadata,
            Map<String, Integer> selectedIngredientCounts,
            String transactionOwner,
            long nowMillis) {
        return craftTick(
                itemId,
                0,
                recipeMetadata,
                selectedIngredientCounts,
                transactionOwner,
                nowMillis);
    }

    /**
     * Fills and crafts exactly the planner-selected recipe display.
     *
     * <p>Only recipe displays synchronized into this player's recipe book can
     * be clicked safely. If the selected recipe has not been discovered this
     * method returns {@link CraftResult#RECIPE_UNKNOWN}; it deliberately does
     * not improvise a multi-click manual grid fill because the current cursor
     * transaction ownership is furnace-specific and cannot safely recover an
     * interrupted arbitrary-grid placement.</p>
     */
    public CraftResult craftTick(
            String itemId,
            int expectedOutputCount,
            ResourceCatalog.RecipeMetadata recipeMetadata,
            Map<String, Integer> selectedIngredientCounts,
            String transactionOwner,
            long nowMillis) {
        if (client.player == null || client.interactionManager == null) return CraftResult.WAITING;
        if (expectedOutputCount < 0) {
            throw new IllegalArgumentException("expected output count cannot be negative");
        }
        ResourceCatalog.RecipeMetadata metadata = Objects.requireNonNull(
                recipeMetadata, "recipeMetadata");
        Map<String, Integer> selected = normalizeIngredientCounts(selectedIngredientCounts);
        boolean selectedVariant = expectedOutputCount > 0
                || metadata.recipeId() != null
                || metadata.layout() != ResourceCatalog.RecipeLayout.UNKNOWN
                || !selected.isEmpty();
        String wanted = normalizeId(itemId);
        ScreenHandler handler = client.player.currentScreenHandler;
        ItemStack cursor = handler.getCursorStack();
        if (!cursor.isEmpty()) {
            boolean cursorIsResult = matchesCraftingOutput(
                    wanted, itemId(cursor), metadata, selectedVariant);
            if (!cursorIsResult) return CraftResult.CURSOR_NOT_EMPTY;
            int destination = cursorDestinationSlot(handler, cursor);
            if (destination < 0) return CraftResult.INVENTORY_FULL;
            if (!issueSlotClick(
                    transactionOwner, handler, destination, 0,
                    SlotActionType.PICKUP, nowMillis)) return CraftResult.WAITING;
            return CraftResult.STORED_RESULT;
        }

        Optional<RecipeChoice> recipe = findCraftingRecipe(
                itemId, expectedOutputCount, metadata, selected);
        if (recipe.isEmpty()) return CraftResult.RECIPE_UNKNOWN;
        RecipeChoice choice = recipe.orElseThrow();
        String displayKey = craftingDisplayShape(
                choice.entry().display(), SlotDisplayContexts.createParameters(client.world))
                .map(CraftingDisplayShape::stableKey)
                .orElse("unsupported");
        String selectedRecipeKey = wanted + '#' + choice.entry().id().index()
                + '#' + choice.outputCount() + '#' + displayKey;
        boolean compatible = choice.requiresTable()
                ? handler instanceof CraftingScreenHandler
                : handler instanceof PlayerScreenHandler || handler instanceof CraftingScreenHandler;
        if (!compatible) return choice.requiresTable()
                ? CraftResult.NEEDS_CRAFTING_TABLE
                : CraftResult.NEEDS_PLAYER_INVENTORY;
        if (!choice.craftable()) return CraftResult.INGREDIENTS_UNAVAILABLE;

        ItemStack output = handler.getSlot(0).getStack();
        boolean expectedOutput = !output.isEmpty()
                && matchesCraftingOutput(
                        wanted, itemId(output), metadata, selectedVariant)
                && (expectedOutputCount <= 0 || output.getCount() == expectedOutputCount);
        // An output left in the grid by another same-output recipe is not
        // proof that the planner-selected entry was prepared. Re-fill first.
        boolean selectedRecipePrepared = !selectedVariant
                || (preparedRecipe.equals(selectedRecipeKey)
                && preparedRecipeSyncId == handler.syncId);
        if (expectedOutput && selectedRecipePrepared) {
            if (cursorDestinationSlot(handler, output) < 0) return CraftResult.INVENTORY_FULL;
            if (!issueSlotClick(
                    transactionOwner, handler, 0, 0,
                    SlotActionType.PICKUP, nowMillis)) return CraftResult.WAITING;
            preparedRecipe = "";
            preparedRecipeSyncId = -1;
            return CraftResult.TOOK_RESULT;
        }

        if (!preparedRecipe.equals(selectedRecipeKey) || preparedRecipeSyncId != handler.syncId) {
            if (!issueInventoryMutation(
                    transactionOwner, handler,
                    handler.slots.stream().map(slot -> slot.id).toList(),
                    nowMillis,
                    () -> client.interactionManager.clickRecipe(
                            handler.syncId, choice.entry().id(), false))) {
                return CraftResult.WAITING;
            }
            preparedRecipe = selectedRecipeKey;
            preparedRecipeSyncId = handler.syncId;
            return CraftResult.FILLED_RECIPE;
        }

        // The server has not populated the output yet. Permit a bounded refill
        // attempt after the ordinary click interval instead of spamming packets.
        if (!issueInventoryMutation(
                transactionOwner, handler,
                handler.slots.stream().map(slot -> slot.id).toList(),
                nowMillis,
                () -> client.interactionManager.clickRecipe(
                        handler.syncId, choice.entry().id(), false))) {
            return CraftResult.WAITING;
        }
        return CraftResult.FILLED_RECIPE;
    }

    /** Read-only recipe handoff diagnosis; callers must separately prove station ownership. */
    public int unrelatedFurnaceSlot(String input,String output,List<String> fuels) {
        if(client.player==null||!(client.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler h))return -1;
        return incompatibleFurnaceSlot(itemId(h.getSlot(0).getStack()),itemId(h.getSlot(1).getStack()),
                itemId(h.getSlot(2).getStack()),input,output,fuels);
    }
    static int incompatibleFurnaceSlot(String input,String fuel,String output,String wantedInput,String wantedOutput,List<String> fuels) {
        if(!normalizeId(output).equals("minecraft:air")&&!normalizeId(output).equals(normalizeId(wantedOutput)))return 2;
        if(!normalizeId(input).equals("minecraft:air")&&!normalizeId(input).equals(normalizeId(wantedInput)))return 0;
        if(!normalizeId(fuel).equals("minecraft:air")&&fuels.stream().map(ClientInventoryController::normalizeId).noneMatch(normalizeId(fuel)::equals))return 1;
        return -1;
    }
    /** Uses the existing acknowledgement/conservation-fenced click engine, never discards cargo. */
    public FurnaceResult collectFurnaceResidue(int slot,String transactionOwner,long nowMillis) {
        if(slot<0||slot>2)throw new IllegalArgumentException("not a furnace content slot");
        if(client.player==null||client.interactionManager==null
                ||!(client.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler))return FurnaceResult.NEEDS_FURNACE;
        if(!handler.getCursorStack().isEmpty())return FurnaceResult.WAITING;
        ItemStack stack=handler.getSlot(slot).getStack();
        if(stack.isEmpty())return FurnaceResult.WAITING;
        if(cursorDestinationSlot(handler,stack)<0)return FurnaceResult.INVENTORY_FULL;
        return issueSlotClick(transactionOwner,handler,slot,0,SlotActionType.QUICK_MOVE,nowMillis)
                ?FurnaceResult.TOOK_OUTPUT:FurnaceResult.WAITING;
    }

    public FurnaceResult furnaceTick(
            String inputItemId,
            String outputItemId,
            List<String> fuelCandidates,
            int remainingOutputCount,
            long nowMillis) {
        return furnaceTick(
                inputItemId, outputItemId, fuelCandidates, remainingOutputCount,
                "furnace:" + normalizeId(outputItemId),
                FurnaceFuelAuthorization.legacy(), nowMillis);
    }

    public FurnaceResult furnaceTick(
            String inputItemId,
            String outputItemId,
            List<String> fuelCandidates,
            int remainingOutputCount,
            String transactionOwner,
            long nowMillis) {
        return furnaceTick(
                inputItemId, outputItemId, fuelCandidates, remainingOutputCount,
                transactionOwner, FurnaceFuelAuthorization.legacy(), nowMillis);
    }

    /**
     * Runs one furnace tick with an optional compiler-authenticated, one-item
     * insertion grant. Committed actions must obtain the grant durably before
     * this method may pick up a new fuel stack; an already-owned cursor may
     * finish, but an unowned same-item cursor is never adopted.
     */
    public FurnaceResult furnaceTick(
            String inputItemId,
            String outputItemId,
            List<String> fuelCandidates,
            int remainingOutputCount,
            String transactionOwner,
            FurnaceFuelAuthorization fuelAuthorization,
            long nowMillis) {
        Objects.requireNonNull(fuelAuthorization, "fuelAuthorization");
        if (client.player == null || client.interactionManager == null) return FurnaceResult.WAITING;
        if (!(client.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
            return FurnaceResult.NEEDS_FURNACE;
        }
        InventoryTransactionEngine.Gate furnaceGate = reconcileFurnaceTick(
                transactions, transactionOwner, snapshot(handler), nowMillis);
        if (!furnaceGate.permitsClick()) return FurnaceResult.WAITING;
        int remaining = Math.max(1, remainingOutputCount);
        String inputId = normalizeId(inputItemId);
        String outputId = normalizeId(outputItemId);

        ItemStack cursor = handler.getCursorStack();
        InventoryTransactionEngine.CursorTransfer furnaceTransferSession =
                transactions.cursorTransfer().orElse(null);
        if (furnaceTransferSession != null) {
            InventoryTransactionEngine.CursorTransfer session = furnaceTransferSession;
            ItemStack destination = handler.getSlot(session.destinationSlot()).getStack();
            boolean committedFuelInsertion = fuelAuthorization.committedAction()
                    && committedFurnaceFuelTransferSettled(
                    session,
                    handler.syncId,
                    cursor.isEmpty(),
                    handler.isBurning(),
                    destination.isEmpty() ? "minecraft:air" : itemId(destination),
                    destination.isEmpty() ? 0 : destination.getCount());
            if (!retainFurnaceTransferSession(
                    session,
                    handler.syncId,
                    cursor.isEmpty(),
                    handler.isBurning(),
                    destination.isEmpty() ? "minecraft:air" : itemId(destination),
                    destination.isEmpty() ? 0 : destination.getCount())) {
                transactions.releaseCursorTransfer(session.owner());
                furnaceTransferSession = null;
            }
            if (committedFuelInsertion) {
                return FurnaceResult.FUEL_INSERTION_COMMITTED;
            }
        }
        if (!cursor.isEmpty()) {
            String cursorId = itemId(cursor);
            FurnaceCursorRoute route = classifyFurnaceCursor(
                    cursorId,
                    inputId,
                    fuelCandidates,
                    handler.syncId,
                    furnaceTransferSession);
            if (route == FurnaceCursorRoute.OWNED_INPUT || route == FurnaceCursorRoute.INPUT) {
                int desiredInput = route == FurnaceCursorRoute.OWNED_INPUT
                        ? furnaceTransferSession.desiredCount()
                        : Math.min(remaining, handler.getSlot(0).getMaxItemCount(cursor));
                return mapInputTransfer(transferExactToSlot(
                        handler, 0, inputId, desiredInput, transactionOwner, nowMillis));
            }
            if (route == FurnaceCursorRoute.OWNED_FUEL
                    || (route == FurnaceCursorRoute.FUEL
                    && !fuelAuthorization.committedAction())) {
                // Ownership preserves the cursor's identity and destination,
                // while the live burn state still decides whether one fuel
                // item is needed.  A furnace can consume the first coal before
                // the cursor remainder is returned; freezing this at one would
                // insert a second, unnecessary coal.
                int desiredFuel = desiredFurnaceFuelCount(
                        handler.isBurning(), handler.getSlot(1).getStack().isEmpty());
                return mapFuelTransfer(transferExactToSlot(
                        handler, 1, cursorId, desiredFuel, transactionOwner, nowMillis));
            }
            return FurnaceResult.CURSOR_NOT_EMPTY;
        }

        ItemStack output = handler.getSlot(2).getStack();
        if (!output.isEmpty() && !itemId(output).equals(outputId)) {
            return FurnaceResult.OUTPUT_OCCUPIED;
        }
        if (!output.isEmpty()) {
            if (!issueSlotClick(
                    transactionOwner + ":output", handler, 2, 0,
                    SlotActionType.QUICK_MOVE, nowMillis)) return FurnaceResult.WAITING;
            return FurnaceResult.TOOK_OUTPUT;
        }

        ItemStack input = handler.getSlot(0).getStack();
        if (!input.isEmpty() && !itemId(input).equals(inputId)) {
            return FurnaceResult.INPUT_OCCUPIED;
        }
        int maximumInput = input.isEmpty()
                ? Math.min(64, remaining)
                : Math.min(remaining, handler.getSlot(0).getMaxItemCount(input));
        if (!input.isEmpty() && input.getCount() > maximumInput) {
            if (!issueSlotClick(
                    transactionOwner + ":input-return", handler, 0, 0,
                    SlotActionType.QUICK_MOVE, nowMillis)) return FurnaceResult.WAITING;
            return FurnaceResult.MOVED_INPUT;
        }
        TransferResult inputTransfer = transferExactToSlot(
                handler, 0, inputId, maximumInput, transactionOwner, nowMillis);
        if (inputTransfer != TransferResult.DONE) return mapInputTransfer(inputTransfer);

        ItemStack fuel = handler.getSlot(1).getStack();
        if (!fuel.isEmpty() && !isAcceptedFurnaceFuel(
                itemId(fuel), fuelCandidates, handler.syncId, furnaceTransferSession)) {
            return FurnaceResult.FUEL_OCCUPIED;
        }
        if (handler.isBurning()) {
            if (!fuel.isEmpty()) {
                if (!issueSlotClick(
                        transactionOwner + ":fuel-return", handler, 1, 0,
                        SlotActionType.QUICK_MOVE, nowMillis)) return FurnaceResult.WAITING;
                return FurnaceResult.MOVED_FUEL;
            }
            return FurnaceResult.COOKING;
        }
        if (fuel.isEmpty()) {
            if (fuelAuthorization.committedAction()
                    && !fuelAuthorization.newInsertionAuthorized()) {
                return FurnaceResult.FUEL_AUTHORIZATION_REQUIRED;
            }
            String selectedFuel = null;
            for (String candidate : fuelCandidates) {
                if (findInventorySlot(candidate) >= 0) {
                    selectedFuel = normalizeId(candidate);
                    break;
                }
            }
            if (selectedFuel == null) return FurnaceResult.FUEL_MISSING;
            return mapFuelTransfer(transferExactToSlot(
                    handler, 1, selectedFuel, 1, transactionOwner, nowMillis));
        }
        return FurnaceResult.COOKING;
    }

    private TransferResult transferExactToSlot(
            ScreenHandler handler,
            int destinationSlot,
            String itemId,
            int desiredCount,
            String transactionOwner,
            long nowMillis) {
        String wanted = normalizeId(itemId);
        Slot destination = handler.getSlot(destinationSlot);
        ItemStack present = destination.getStack();
        if (!present.isEmpty() && !itemId(present).equals(wanted)) {
            return TransferResult.SLOT_UNAVAILABLE;
        }
        int presentCount = present.isEmpty() ? 0 : present.getCount();
        ItemStack cursor = handler.getCursorStack();
        if (!cursor.isEmpty()) {
            InventoryTransactionEngine.CursorTransfer owned = matchingFurnaceTransfer(
                    handler.syncId, destinationSlot, itemId(cursor),
                    transactions.cursorTransfer().orElse(null));
            if (owned != null) {
                wanted = owned.itemId();
                desiredCount = effectiveFurnaceTransferCount(
                        destinationSlot, desiredCount, owned);
            }
            if (!itemId(cursor).equals(wanted)) return TransferResult.CURSOR_NOT_EMPTY;
            if (presentCount < desiredCount) {
                if (!issueExactContainerDeposit(
                        transactionOwner, handler, destinationSlot,
                        wanted, nowMillis)) return TransferResult.WAITING;
                return TransferResult.CLICKED;
            }
            int returnSlot = cursorDestinationSlot(handler, cursor);
            if (returnSlot < 0) return TransferResult.INVENTORY_FULL;
            if (!issueSlotClick(
                    transactionOwner, handler, returnSlot, 0,
                    SlotActionType.PICKUP, nowMillis)) return TransferResult.WAITING;
            return TransferResult.CLICKED;
        }
        if (presentCount >= desiredCount) return TransferResult.DONE;
        // Reconcile the preceding source/deposit click before consulting the
        // player inventory.  During the final right-click deposit the cursor
        // can already be empty while the server-confirmed destination count is
        // still pending.  Reporting ITEM_MISSING in that custody gap used to
        // invalidate the full universal program and mine the entire batch a
        // second time.
        InventorySnapshot current = snapshot(handler);
        InventoryTransactionEngine.Gate gate = transactions.gate(
                transactionOwner, current, nowMillis);
        if (!gate.permitsClick()) return TransferResult.WAITING;
        int sourceInventory = findInventorySlot(wanted);
        if (sourceInventory < 0) return TransferResult.ITEM_MISSING;
        int source = handlerSlot(handler, sourceInventory);
        if (source < 0) return TransferResult.SLOT_UNAVAILABLE;
        InventorySnapshot before = current;
        transactions.claimCursorTransfer(
                transactionOwner, handler.syncId, source, destinationSlot,
                wanted, desiredCount, nowMillis);
        client.interactionManager.clickSlot(
                handler.syncId, source, 0, SlotActionType.PICKUP, client.player);
        transactions.recordClick(transactionOwner, before, List.of(source), nowMillis);
        return TransferResult.CLICKED;
    }

    /**
     * Places exactly one cursor item into a container and requires the
     * destination slot increase as acknowledgement.
     */
    private boolean issueExactContainerDeposit(
            String transactionOwner,
            ScreenHandler handler,
            int destinationSlot,
            String itemId,
            long nowMillis) {
        InventoryTransactionEngine.FurnaceFuelConsumptionProof fuelConsumptionProof =
                furnaceFuelConsumptionProof(
                        transactionOwner, handler.syncId, destinationSlot, itemId);
        return issueInventoryMutation(
                transactionOwner,
                handler,
                List.of(destinationSlot),
                nowMillis,
                null,
                InventoryTransactionEngine.SlotCountDelta.increase(
                        destinationSlot, itemId, 1),
                fuelConsumptionProof,
                () -> client.interactionManager.clickSlot(
                        handler.syncId, destinationSlot, 1,
                        SlotActionType.PICKUP, client.player));
    }

    static InventoryTransactionEngine.Gate reconcileFurnaceTick(
            InventoryTransactionEngine transactions,
            String transactionOwner,
            InventorySnapshot snapshot,
            long nowMillis) {
        return transactions.gate(transactionOwner, snapshot, nowMillis);
    }

    static InventoryTransactionEngine.FurnaceFuelConsumptionProof
    furnaceFuelConsumptionProof(
            String transactionOwner,
            int handlerSyncId,
            int destinationSlot,
            String itemId) {
        return destinationSlot == 1
                ? new InventoryTransactionEngine.FurnaceFuelConsumptionProof(
                transactionOwner, handlerSyncId, destinationSlot, itemId)
                : null;
    }

    /**
     * Classifies a furnace cursor before consulting the live inventory again.
     *
     * <p>Picking a stack up removes it from {@link #counts()}, so a dynamic
     * reservation/fuel candidate list may legitimately stop mentioning the
     * item while the multi-click transfer is in flight.  The sync-scoped
     * transfer session is therefore authoritative until the cursor is empty.
     * An unowned cursor still has to be present in the caller's reservation-
     * filtered candidate list.</p>
     */
    static FurnaceCursorRoute classifyFurnaceCursor(
            String cursorItemId,
            String inputItemId,
            List<String> fuelCandidates,
            int handlerSyncId,
            InventoryTransactionEngine.CursorTransfer session) {
        String cursor = normalizeId(cursorItemId);
        InventoryTransactionEngine.CursorTransfer owned = matchingFurnaceTransfer(
                handlerSyncId, -1, cursor, session);
        if (owned != null) {
            if (owned.destinationSlot() == 0) return FurnaceCursorRoute.OWNED_INPUT;
            if (owned.destinationSlot() == 1) return FurnaceCursorRoute.OWNED_FUEL;
        }
        if (cursor.equals(normalizeId(inputItemId))) return FurnaceCursorRoute.INPUT;
        boolean listedFuel = fuelCandidates.stream()
                .map(ClientInventoryController::normalizeId)
                .anyMatch(cursor::equals);
        return listedFuel
                ? FurnaceCursorRoute.FUEL
                : FurnaceCursorRoute.UNRELATED;
    }

    static boolean isAcceptedFurnaceFuel(
            String fuelItemId,
            List<String> fuelCandidates,
            int handlerSyncId,
            InventoryTransactionEngine.CursorTransfer session) {
        String fuel = normalizeId(fuelItemId);
        if (matchingFurnaceTransfer(handlerSyncId, 1, fuel, session) != null) return true;
        return fuelCandidates.stream()
                .map(ClientInventoryController::normalizeId)
                .anyMatch(fuel::equals);
    }

    /**
     * Narrows a compiler-committed smelt to its authenticated physical fuel family. A zero-count
     * retained-burn slice deliberately authorizes no new fuel insertion; if the furnace is cold,
     * the caller receives {@link FurnaceResult#FUEL_MISSING} and invalidates the stale program.
     */
    static List<String> committedFurnaceFuelCandidates(
            String plannedFuelItemId,
            int plannedFuelCount) {
        if (plannedFuelCount < 0) {
            throw new IllegalArgumentException("planned furnace fuel count cannot be negative");
        }
        if (plannedFuelCount == 0) return List.of();
        return List.of(normalizeId(plannedFuelItemId));
    }

    /** Exact acknowledgement that one owned fuel grant reached furnace burn. */
    static boolean committedFurnaceFuelTransferSettled(
            InventoryTransactionEngine.CursorTransfer session,
            int handlerSyncId,
            boolean cursorEmpty,
            boolean burning,
            String destinationItemId,
            int destinationCount) {
        return session != null
                && session.destinationSlot() == 1
                && session.syncId() == handlerSyncId
                && cursorEmpty
                && burning
                && normalizeId(destinationItemId).equals("minecraft:air")
                && destinationCount == 0;
    }

    /** Read-only proof that an authorized committed fuel transfer is still in process. */
    public boolean hasExactFurnaceFuelTransfer(
            String transactionOwner,
            String fuelItemId) {
        if (client.player == null || !(client.player.currentScreenHandler
                instanceof AbstractFurnaceScreenHandler handler)) return false;
        return transactions.cursorTransfer(handler.syncId, 1, fuelItemId)
                .filter(session -> session.owner().equals(
                        Objects.requireNonNullElse(transactionOwner, "")
                                .trim().toLowerCase(java.util.Locale.ROOT)))
                .isPresent();
    }

    static boolean retainFurnaceTransferSession(
            InventoryTransactionEngine.CursorTransfer session,
            int handlerSyncId,
            boolean cursorEmpty,
            boolean burning,
            String destinationItemId,
            int destinationCount) {
        if (session == null || session.syncId() != handlerSyncId) return false;
        if (!cursorEmpty) return true;
        String destination = normalizeId(destinationItemId);
        if (session.destinationSlot() == 0) {
            // furnaceTick has already reconciled the exact pending click. An
            // acknowledged empty cursor ends this physical transfer, even if
            // consumption/theft reduced its original planned quantity. Recipe
            // demand remains with the caller; retaining a nonexistent cursor
            // here prevents a subsequent fuel claim from ever owning it.
            return false;
        }
        return !(burning && destination.equals("minecraft:air"));
    }

    static int desiredFurnaceFuelCount(boolean burning, boolean fuelSlotEmpty) {
        return burning || !fuelSlotEmpty ? 0 : 1;
    }

    static int effectiveFurnaceTransferCount(
            int destinationSlot,
            int requestedCount,
            InventoryTransactionEngine.CursorTransfer owned) {
        return owned != null && destinationSlot == 0
                ? owned.desiredCount()
                : requestedCount;
    }

    private static InventoryTransactionEngine.CursorTransfer matchingFurnaceTransfer(
            int handlerSyncId,
            int destinationSlot,
            String itemId,
            InventoryTransactionEngine.CursorTransfer session) {
        if (session == null
                || session.syncId() != handlerSyncId
                || (destinationSlot >= 0 && session.destinationSlot() != destinationSlot)
                || !session.itemId().equals(normalizeId(itemId))) {
            return null;
        }
        return session;
    }

    private static FurnaceResult mapInputTransfer(TransferResult result) {
        return switch (result) {
            case DONE -> FurnaceResult.COOKING;
            case CLICKED -> FurnaceResult.MOVED_INPUT;
            case WAITING -> FurnaceResult.WAITING;
            case ITEM_MISSING -> FurnaceResult.INPUT_MISSING;
            case CURSOR_NOT_EMPTY -> FurnaceResult.CURSOR_NOT_EMPTY;
            case SLOT_UNAVAILABLE -> FurnaceResult.SLOT_UNAVAILABLE;
            case INVENTORY_FULL -> FurnaceResult.INVENTORY_FULL;
        };
    }

    private static FurnaceResult mapFuelTransfer(TransferResult result) {
        return switch (result) {
            case DONE -> FurnaceResult.COOKING;
            case CLICKED -> FurnaceResult.MOVED_FUEL;
            case WAITING -> FurnaceResult.WAITING;
            case ITEM_MISSING -> FurnaceResult.FUEL_MISSING;
            case CURSOR_NOT_EMPTY -> FurnaceResult.CURSOR_NOT_EMPTY;
            case SLOT_UNAVAILABLE -> FurnaceResult.SLOT_UNAVAILABLE;
            case INVENTORY_FULL -> FurnaceResult.INVENTORY_FULL;
        };
    }

    private boolean ensurePlayerHandler() {
        if (client.player == null || client.interactionManager == null) return false;
        if (client.player.currentScreenHandler instanceof PlayerScreenHandler) return true;
        closeHandledScreen(System.currentTimeMillis());
        return false;
    }

    private int findInventorySlot(String itemId) {
        if (client.player == null) return -1;
        String wanted = normalizeId(itemId);
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && itemId(stack).equals(wanted)) return slot;
        }
        return -1;
    }

    private int findServiceableInventorySlot(
            String itemId,
            int minimumRemainingDurability) {
        if (client.player == null) return -1;
        String wanted = normalizeId(itemId);
        PlayerInventory inventory = client.player.getInventory();
        int selected = -1;
        int selectedDurability = -1;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !itemId(stack).equals(wanted)) continue;
            int durability = remainingDurability(stack);
            if (durability < minimumRemainingDurability) continue;
            if (durability > selectedDurability) {
                selected = slot;
                selectedDurability = durability;
            }
        }
        return selected;
    }

    private static int remainingDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return stack.isDamageable()
                ? Math.max(0, stack.getMaxDamage() - stack.getDamage())
                : Integer.MAX_VALUE;
    }

    private int findEquivalentInventorySlot(String itemId) {
        if (client.player == null) return -1;
        String wanted = canonicalItemId(itemId);
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && canonicalItemId(itemId(stack)).equals(wanted)) return slot;
        }
        return -1;
    }

    private int handlerSlot(ScreenHandler handler, int inventoryIndex) {
        PlayerInventory inventory = client.player.getInventory();
        for (Slot slot : handler.slots) {
            if (slot.inventory == inventory && slot.getIndex() == inventoryIndex) return slot.id;
        }
        return -1;
    }

    private int cursorDestinationSlot(ScreenHandler handler, ItemStack cursor) {
        PlayerInventory inventory = client.player.getInventory();
        int empty = -1;
        for (Slot slot : handler.slots) {
            if (slot.inventory != inventory || !slot.canInsert(cursor)) continue;
            ItemStack present = slot.getStack();
            if (present.isEmpty()) {
                if (empty < 0) empty = slot.id;
                continue;
            }
            if (ItemStack.areItemsAndComponentsEqual(present, cursor)
                    && present.getCount() < Math.min(present.getMaxCount(), slot.getMaxItemCount(present))) {
                return slot.id;
            }
        }
        return empty;
    }

    /** Starts a lease-owned proof that an exact handoff left player inventory. */
    public void beginRemovalTransaction(
            String transactionOwner,
            String itemId,
            int baselineCount,
            int expectedCount,
            long nowMillis) {
        transactions.beginRemoval(
                transactionOwner, itemId, baselineCount, expectedCount, nowMillis);
    }

    public InventoryTransactionEngine.RemovalObservation observeRemovalTransaction(
            String transactionOwner,
            String itemId,
            int currentCount,
            long nowMillis,
            long timeoutMillis) {
        return transactions.observeRemoval(
                transactionOwner, itemId, currentCount, nowMillis, timeoutMillis);
    }

    public void clearRemovalTransaction(String transactionOwner) {
        transactions.clearRemoval(transactionOwner);
    }

    public InventoryTransactionEngine.Diagnostics transactionDiagnostics() {
        return transactions.diagnostics();
    }

    /** Current handler cursor truth without opening, closing, or clicking anything. */
    public boolean cursorEmpty() {
        return client.player == null
                || client.player.currentScreenHandler.getCursorStack().isEmpty();
    }

    /**
     * Reconciles a terminal click even after its action generation has already
     * completed or the next action does not touch inventory. This observer can
     * settle exact prior-owner truth, but it never issues a mutation itself.
     */
    public InventoryTransactionEngine.Gate reconcilePendingTransaction(long nowMillis) {
        if (client.player == null) return InventoryTransactionEngine.Gate.READY;
        return reconcileInventoryTick(
                transactions, snapshot(client.player.currentScreenHandler), nowMillis);
    }

    static InventoryTransactionEngine.Gate reconcileInventoryTick(
            InventoryTransactionEngine transactions,
            InventorySnapshot snapshot,
            long nowMillis) {
        return transactions.gate(PASSIVE_TRANSACTION_OBSERVER, snapshot, nowMillis);
    }

    private boolean issueSlotClick(
            String transactionOwner,
            ScreenHandler handler,
            int slot,
            int button,
            SlotActionType action,
            long nowMillis) {
        return issueSlotClick(
                transactionOwner, handler, slot, button, action,
                nowMillis, List.of(slot));
    }

    private boolean issueSlotClick(
            String transactionOwner,
            ScreenHandler handler,
            int slot,
            int button,
            SlotActionType action,
            long nowMillis,
            Collection<Integer> watchedSlots) {
        InventoryTransactionEngine.PlayerCountDelta playerCountDelta =
                action == SlotActionType.QUICK_MOVE
                        ? expectedQuickMovePlayerDelta(handler, slot).orElse(null)
                        : null;
        return issueInventoryMutation(
                transactionOwner,
                handler,
                watchedSlots,
                nowMillis,
                playerCountDelta,
                () -> client.interactionManager.clickSlot(
                        handler.syncId, slot, button, action, client.player));
    }

    private boolean issueInventoryMutation(
            String transactionOwner,
            ScreenHandler handler,
            Collection<Integer> watchedSlots,
            long nowMillis,
            Runnable mutation) {
        return issueInventoryMutation(
                transactionOwner, handler, watchedSlots, nowMillis, null, mutation);
    }

    private boolean issueInventoryMutation(
            String transactionOwner,
            ScreenHandler handler,
            Collection<Integer> watchedSlots,
            long nowMillis,
            InventoryTransactionEngine.PlayerCountDelta playerCountDelta,
            Runnable mutation) {
        return issueInventoryMutation(
                transactionOwner, handler, watchedSlots, nowMillis,
                playerCountDelta, null, mutation);
    }

    private boolean issueInventoryMutation(
            String transactionOwner,
            ScreenHandler handler,
            Collection<Integer> watchedSlots,
            long nowMillis,
            InventoryTransactionEngine.PlayerCountDelta playerCountDelta,
            InventoryTransactionEngine.SlotCountDelta requiredSlotDelta,
            Runnable mutation) {
        return issueInventoryMutation(
                transactionOwner, handler, watchedSlots, nowMillis,
                playerCountDelta, requiredSlotDelta, null, mutation);
    }

    private boolean issueInventoryMutation(
            String transactionOwner,
            ScreenHandler handler,
            Collection<Integer> watchedSlots,
            long nowMillis,
            InventoryTransactionEngine.PlayerCountDelta playerCountDelta,
            InventoryTransactionEngine.SlotCountDelta requiredSlotDelta,
            InventoryTransactionEngine.FurnaceFuelConsumptionProof fuelConsumptionProof,
            Runnable mutation) {
        if (client.player == null || client.interactionManager == null) return false;
        InventorySnapshot before = snapshot(handler);
        InventoryTransactionEngine.Gate gate = transactions.gate(
                transactionOwner, before, nowMillis);
        if (!gate.permitsClick()) return false;
        mutation.run();
        transactions.recordClick(
                transactionOwner, before,
                watchedSlots == null ? List.of() : List.copyOf(watchedSlots),
                playerCountDelta,
                requiredSlotDelta,
                fuelConsumptionProof,
                nowMillis);
        return true;
    }

    /** Correlated destination proof for a QUICK_MOVE source stack. */
    private Optional<InventoryTransactionEngine.PlayerCountDelta> expectedQuickMovePlayerDelta(
            ScreenHandler handler,
            int sourceSlotId) {
        if (client.player == null) return Optional.empty();
        if (sourceSlotId < 0 || sourceSlotId >= handler.slots.size()) return Optional.empty();
        Slot source = handler.getSlot(sourceSlotId);
        ItemStack stack = source.getStack();
        if (stack.isEmpty()) return Optional.empty();
        boolean playerSource = source.inventory == client.player.getInventory();
        return Optional.of(playerSource
                ? InventoryTransactionEngine.PlayerCountDelta.decrease(
                        itemId(stack), stack.getCount())
                : InventoryTransactionEngine.PlayerCountDelta.increase(
                        itemId(stack), stack.getCount()));
    }

    private InventorySnapshot snapshot(ScreenHandler handler) {
        LinkedHashMap<Integer, InventorySnapshot.SlotView> slots = new LinkedHashMap<>();
        PlayerInventory playerInventory = client.player.getInventory();
        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            slots.put(slot.id, new InventorySnapshot.SlotView(
                    slot.id,
                    slot.getIndex(),
                    slot.inventory == playerInventory
                            ? InventorySnapshot.Domain.PLAYER
                            : InventorySnapshot.Domain.CONTAINER,
                    stackView(stack)));
        }
        LinkedHashMap<String, Integer> playerCounts = new LinkedHashMap<>();
        for (int slot = 0; slot < playerInventory.size(); slot++) {
            ItemStack stack = playerInventory.getStack(slot);
            if (stack.isEmpty()) continue;
            playerCounts.merge(itemId(stack), stack.getCount(), Math::addExact);
        }
        return new InventorySnapshot(
                handler.syncId,
                handler.getClass().getName(),
                stackView(handler.getCursorStack()),
                slots,
                playerCounts,
                handler instanceof AbstractFurnaceScreenHandler furnace
                        ? InventorySnapshot.FurnaceState.observed(furnace.isBurning())
                        : InventorySnapshot.FurnaceState.unavailable(),
                handler.getRevision());
    }

    private static InventorySnapshot.StackView stackView(ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? InventorySnapshot.StackView.empty()
                : new InventorySnapshot.StackView(
                        itemId(stack),
                        stack.getCount(),
                        stack.getDamage(),
                        stack.getComponents().hashCode());
    }

    private static Optional<CraftingDisplayShape> craftingDisplayShape(
            RecipeDisplay display,
            ContextParameterMap context) {
        ResourceCatalog.RecipeLayout layout;
        int width;
        int height;
        List<SlotDisplay> ingredientDisplays;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            layout = ResourceCatalog.RecipeLayout.SHAPED;
            width = shaped.width();
            height = shaped.height();
            ingredientDisplays = shaped.ingredients();
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            layout = ResourceCatalog.RecipeLayout.SHAPELESS;
            width = 0;
            height = 0;
            ingredientDisplays = shapeless.ingredients();
        } else {
            return Optional.empty();
        }

        ArrayList<List<String>> slots = new ArrayList<>(ingredientDisplays.size());
        try {
            for (SlotDisplay ingredient : ingredientDisplays) {
                TreeSet<String> alternatives = new TreeSet<>();
                for (ItemStack stack : ingredient.getStacks(context)) {
                    if (!stack.isEmpty()) alternatives.add(itemId(stack));
                }
                slots.add(List.copyOf(alternatives));
            }
        } catch (RuntimeException unsupportedDisplay) {
            return Optional.empty();
        }
        return Optional.of(new CraftingDisplayShape(
                layout, width, height, List.copyOf(slots)));
    }

    /**
     * Pure structural correlation boundary used by the mapped recipe harness.
     * Display slot IDs and planner IDs may be namespaced or namespace-free.
     */
    static boolean matchesCraftingSelection(
            ResourceCatalog.RecipeLayout displayLayout,
            int displayWidth,
            int displayHeight,
            List<List<String>> displaySlots,
            ResourceCatalog.RecipeMetadata recipeMetadata,
            Map<String, Integer> selectedIngredientCounts) {
        Objects.requireNonNull(displayLayout, "displayLayout");
        Objects.requireNonNull(displaySlots, "displaySlots");
        ResourceCatalog.RecipeMetadata metadata = Objects.requireNonNull(
                recipeMetadata, "recipeMetadata");
        Map<String, Integer> selected = normalizeIngredientCounts(selectedIngredientCounts);
        List<List<String>> actualSlots = normalizeRecipeSlots(displaySlots);
        if (actualSlots.size() > 9 || selected.size() > 64) return false;

        if (displayLayout == ResourceCatalog.RecipeLayout.SHAPED) {
            if (displayWidth <= 0 || displayHeight <= 0
                    || actualSlots.size() != displayWidth * displayHeight) return false;
        } else if (displayLayout == ResourceCatalog.RecipeLayout.SHAPELESS) {
            if (displayWidth != 0 || displayHeight != 0) return false;
        } else {
            return false;
        }

        switch (metadata.layout()) {
            case COOKING -> {
                return false;
            }
            case SHAPED -> {
                if (displayLayout != ResourceCatalog.RecipeLayout.SHAPED
                        || displayWidth != metadata.width()
                        || displayHeight != metadata.height()) return false;
                if (!actualSlots.equals(normalizeRecipeSlots(metadata.slots()))) return false;
            }
            case SHAPELESS -> {
                if (displayLayout != ResourceCatalog.RecipeLayout.SHAPELESS) return false;
                if (!metadata.slots().isEmpty()
                        && !shapelessSlotKeys(actualSlots).equals(
                        shapelessSlotKeys(normalizeRecipeSlots(metadata.slots())))) return false;
            }
            case UNKNOWN -> {
                // Paper recipe identifiers have no corresponding client-side
                // identifier in 1.21.8. Aggregate selected inputs are the safe
                // structural fallback when the server did not export a shape.
            }
        }
        return selected.isEmpty() || plannedIngredientsFit(actualSlots, selected);
    }

    private static List<List<String>> normalizeRecipeSlots(List<List<String>> rawSlots) {
        ArrayList<List<String>> slots = new ArrayList<>(rawSlots.size());
        for (List<String> rawSlot : rawSlots) {
            Objects.requireNonNull(rawSlot, "recipe slot");
            TreeSet<String> alternatives = new TreeSet<>();
            for (String alternative : rawSlot) {
                String normalized = normalizeId(alternative);
                if (!normalized.equals("minecraft:air")) alternatives.add(normalized);
            }
            slots.add(List.copyOf(alternatives));
        }
        return List.copyOf(slots);
    }

    private static List<String> shapelessSlotKeys(List<List<String>> slots) {
        ArrayList<String> keys = new ArrayList<>(slots.size());
        for (List<String> slot : slots) {
            if (!slot.isEmpty()) keys.add(String.join("\u0000", slot));
        }
        keys.sort(String::compareTo);
        return List.copyOf(keys);
    }

    private static Map<String, Integer> normalizeIngredientCounts(
            Map<String, Integer> rawCounts) {
        Objects.requireNonNull(rawCounts, "selectedIngredientCounts");
        TreeMap<String, Integer> normalized = new TreeMap<>();
        rawCounts.forEach((item, count) -> {
            if (count == null || count <= 0) {
                throw new IllegalArgumentException(
                        "selected ingredient count must be positive: " + item);
            }
            normalized.merge(normalizeId(item), count, Math::addExact);
        });
        return Map.copyOf(normalized);
    }

    /** Exact capacity matching avoids greedy failures for overlapping tags. */
    private static boolean plannedIngredientsFit(
            List<List<String>> normalizedSlots,
            Map<String, Integer> selected) {
        return CraftingIngredientCapacity.matchesExact(
                normalizedSlots, selected, ClientInventoryController::craftingIngredientEquivalent);
    }

    private static boolean requiresTable(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return shaped.width() > 2 || shaped.height() > 2;
        }
        return display instanceof ShapelessCraftingRecipeDisplay shapeless
                && shapeless.ingredients().size() > 4;
    }

    private static int equipmentHandlerSlot(EquipmentSlot slot) {
        if (slot == EquipmentSlot.OFFHAND) return PlayerScreenHandler.OFFHAND_ID;
        if (slot == EquipmentSlot.HEAD) return 5;
        if (slot == EquipmentSlot.CHEST) return 6;
        if (slot == EquipmentSlot.LEGS) return 7;
        if (slot == EquipmentSlot.FEET) return 8;
        return -1;
    }

    /**
     * A static fallback recipe may describe an interchangeable wood family by
     * its canonical oak ID. Runtime recipe metadata, by contrast, identifies
     * one exact physical recipe and must never adopt another same-family
     * output or a stale cursor from it.
     */
    static boolean matchesCraftingOutput(
            String requested,
            String actual,
            ResourceCatalog.RecipeMetadata metadata,
            boolean selectedVariant) {
        String wanted = normalizeId(requested);
        String observed = normalizeId(actual);
        if (wanted.equals(observed)) return true;
        if (!selectedVariant) return recipeEquivalent(wanted, observed);
        boolean exactRuntimeRecipe = metadata.recipeId() != null
                || metadata.layout() != ResourceCatalog.RecipeLayout.UNKNOWN;
        return !exactRuntimeRecipe && recipeEquivalent(wanted, observed);
    }

    private static boolean recipeEquivalent(String requested, String actual) {
        return craftingFamilyEquivalent(requested, actual, "oak_planks");
    }

    private static boolean craftingIngredientEquivalent(String planned, String actual) {
        if (normalizeId(planned).equals(normalizeId(actual))) return true;
        return craftingFamilyEquivalent(planned, actual, "oak_log")
                || craftingFamilyEquivalent(planned, actual, "oak_planks");
    }

    private static boolean craftingFamilyEquivalent(
            String first,
            String second,
            String allowedCanonicalFamily) {
        // Equivalence is directional: the planner's canonical representative
        // may accept one physical family member, but a concrete birch/spruce
        // selection must not be widened back into every wood variant.
        return normalizeId(first).equals("minecraft:" + allowedCanonicalFamily)
                && canonicalItemId(second).equals(allowedCanonicalFamily);
    }

    private static Map<String, Integer> normalizedNonNegativeCounts(
            Map<String, Integer> raw,
            String field) {
        Objects.requireNonNull(raw, field);
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        raw.forEach((item, count) -> {
            if (count == null || count < 0) {
                throw new IllegalArgumentException(field + " contains a negative/null count");
            }
            if (count > 0) normalized.merge(normalizeId(item), count, Math::addExact);
        });
        return Map.copyOf(normalized);
    }

    private static boolean serviceableHomeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isDamageable()) return true;
        int remaining = Math.max(0, stack.getMaxDamage() - stack.getDamage());
        return remaining >= Math.max(16, stack.getMaxDamage() / 10);
    }

    /** Component equality with a pristine stack rejects names, lore, enchantments, and damage. */
    private static boolean pristineStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemStack pristine = new ItemStack(stack.getItem());
        return stack.getComponents().equals(pristine.getComponents());
    }

    private int containerItemCount(GenericContainerScreenHandler handler, String item) {
        String wanted = normalizeId(item);
        int count = 0;
        PlayerInventory playerInventory = client.player.getInventory();
        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (slot.inventory != playerInventory && !stack.isEmpty()
                    && itemId(stack).equals(wanted)) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    private static boolean destinationCanAccept(Slot destination, String item, int count) {
        if (count <= 0) return false;
        ItemStack present = destination.getStack();
        if (present.isEmpty()) return count <= destination.getMaxItemCount();
        return pristineStack(present)
                && itemId(present).equals(normalizeId(item))
                && present.getCount() + count <= Math.min(
                present.getMaxCount(), destination.getMaxItemCount(present));
    }

    public static String normalizeId(String value) {
        return DeliveryPolicy.normalizeItemId(value);
    }

    public static String canonicalItemId(String value) {
        return RESOURCE_CATALOG.normalizeItem(normalizeId(value));
    }

    private static String itemId(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }

    public enum ClickResult {
        CLICKED,
        ALREADY_DONE,
        WAITING,
        ITEM_MISSING,
        SLOT_UNAVAILABLE,
        CURSOR_NOT_EMPTY
    }

    public enum HomeTransferResult {
        CLICKED,
        WAITING,
        COMPLETE,
        NEEDS_OWNED_CHEST,
        STALE_HANDLER,
        SLOT_CHANGED,
        SOURCE_CHANGED,
        DESTINATION_CHANGED,
        CURSOR_NOT_OWNED,
        COUNTS_DIVERGED
    }

    public enum SafeCloseResult {
        CLOSED,
        WAITING_ACKNOWLEDGEMENT,
        CURSOR_OCCUPIED
    }

    public enum HomeTransferSettlement { WAITING, SETTLED, FOREIGN_CURSOR, NO_RECEIVING_SPACE }

    /** Physical room in the currently open container; no unopened chest is guessed empty. */
    public HomeStorageCapacity homeStorageCapacity() {
        if (client.player == null || !(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            return new HomeStorageCapacity(0, Map.of());
        }
        int empty = 0;
        Map<String, Integer> mergeRoom = new LinkedHashMap<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory == client.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) empty++;
            else if (pristineStack(stack)) {
                int room = Math.min(slot.getMaxItemCount(stack), stack.getMaxCount()) - stack.getCount();
                if (room > 0) mergeRoom.merge(itemId(stack), room, Math::addExact);
            }
        }
        return new HomeStorageCapacity(empty, Map.copyOf(mergeRoom));
    }

    public record HomeStorageCapacity(int emptySlots, Map<String, Integer> mergeRoom) {
        public HomeStorageCapacity { mergeRoom = Map.copyOf(mergeRoom); }
        public boolean accepts(String item) { return emptySlots > 0 || mergeRoom.getOrDefault(normalizeId(item), 0) > 0; }
    }

    public record HomeChestSnapshot(
            boolean open,
            int syncId,
            boolean cursorEmpty,
            Map<String, Integer> playerCounts,
            Map<String, Integer> chestCounts,
            Map<String, Integer> depositEligibleCounts,
            boolean hasEmptyContainerSlot) {
        public HomeChestSnapshot {
            if (open != (syncId >= 0)) {
                throw new IllegalArgumentException("open chest and sync ID must agree");
            }
            playerCounts = normalizedNonNegativeCounts(playerCounts, "playerCounts");
            chestCounts = normalizedNonNegativeCounts(chestCounts, "chestCounts");
            depositEligibleCounts = normalizedNonNegativeCounts(
                    depositEligibleCounts, "depositEligibleCounts");
        }

        public static HomeChestSnapshot closed() {
            return new HomeChestSnapshot(false, -1, true,
                    Map.of(), Map.of(), Map.of(), false);
        }
    }

    public record HomeChestStack(
            String stackId,
            int sourceSlot,
            String itemId,
            int count,
            int usableDurability) {
        public HomeChestStack {
            stackId = Objects.requireNonNull(stackId, "stackId").trim();
            itemId = normalizeId(itemId);
            if (stackId.isEmpty() || sourceSlot < 0 || count <= 0 || usableDurability < 0) {
                throw new IllegalArgumentException("invalid exact Home chest stack");
            }
        }

        /** Explicit physical-item to mining-planner projection; item/slot identity is retained. */
        public dev.entity.client.autonomy.policy.HomeMissionSupplyAllocator.HomeStack missionSupplyStack() {
            return dev.entity.client.autonomy.policy.HomeMissionSupplyAllocator.HomeStack.fromPristineItem(
                    stackId, itemId, count, usableDurability);
        }

        public int missionSupplyPickaxeDurability(int selectedCount) {
            if (selectedCount < 0 || selectedCount > count) {
                throw new IllegalArgumentException("selected Home count is outside its observed stack");
            }
            return Math.multiplyExact(missionSupplyStack().usablePickaxeDurability() / count, selectedCount);
        }
    }

    public record HomeTransferPlan(
            HomeStockPolicy.Direction direction,
            String itemId,
            int count,
            int syncId,
            int sourceSlot,
            int destinationSlot,
            int playerCountBefore,
            int chestCountBefore,
            int destinationCountBefore) {
        public HomeTransferPlan {
            direction = Objects.requireNonNull(direction, "direction");
            itemId = normalizeId(itemId);
            if (count <= 0 || syncId < 0 || sourceSlot < 0 || destinationSlot < 0
                    || playerCountBefore < 0 || chestCountBefore < 0
                    || destinationCountBefore < 0) {
                throw new IllegalArgumentException("invalid home transfer plan");
            }
        }
    }

    public record HomeTransferObservation(
            int syncId,
            boolean cursorEmpty,
            int playerItemCount,
            int chestItemCount) {
        public HomeTransferObservation {
            if (syncId < -1 || playerItemCount < 0 || chestItemCount < 0) {
                throw new IllegalArgumentException("invalid home transfer observation");
            }
        }
    }

    public record DropResult(ClickResult result, int count) {
        public DropResult {
            if (count < 0) throw new IllegalArgumentException("drop count cannot be negative");
        }
    }

    public record ConcreteItem(String itemId, int count) {
        public ConcreteItem {
            itemId = normalizeId(itemId);
            if (count < 1) throw new IllegalArgumentException("concrete item count must be positive");
        }
    }

    public enum CraftResult {
        FILLED_RECIPE,
        TOOK_RESULT,
        STORED_RESULT,
        WAITING,
        RECIPE_UNKNOWN,
        INGREDIENTS_UNAVAILABLE,
        NEEDS_CRAFTING_TABLE,
        NEEDS_PLAYER_INVENTORY,
        CURSOR_NOT_EMPTY,
        SLOT_UNAVAILABLE,
        INVENTORY_FULL
    }

    public enum FurnaceResult {
        MOVED_INPUT,
        MOVED_FUEL,
        FUEL_AUTHORIZATION_REQUIRED,
        FUEL_INSERTION_COMMITTED,
        COOKING,
        TOOK_OUTPUT,
        WAITING,
        NEEDS_FURNACE,
        INPUT_MISSING,
        FUEL_MISSING,
        INPUT_OCCUPIED,
        FUEL_OCCUPIED,
        OUTPUT_OCCUPIED,
        INVENTORY_FULL,
        SLOT_UNAVAILABLE,
        CURSOR_NOT_EMPTY
    }

    /**
     * A committed grant permits at most one new player-to-furnace fuel
     * transfer. Legacy and food sessions retain their existing flexible fuel
     * behavior.
     */
    public record FurnaceFuelAuthorization(
            boolean committedAction,
            boolean newInsertionAuthorized) {
        public FurnaceFuelAuthorization {
            if (!committedAction && !newInsertionAuthorized) {
                throw new IllegalArgumentException(
                        "legacy furnace fuel authorization cannot be restricted");
            }
        }

        public static FurnaceFuelAuthorization legacy() {
            return new FurnaceFuelAuthorization(false, true);
        }

        public static FurnaceFuelAuthorization committed(boolean authorized) {
            return new FurnaceFuelAuthorization(true, authorized);
        }
    }

    private enum TransferResult {
        DONE,
        CLICKED,
        WAITING,
        ITEM_MISSING,
        SLOT_UNAVAILABLE,
        INVENTORY_FULL,
        CURSOR_NOT_EMPTY
    }

    enum FurnaceCursorRoute {
        OWNED_INPUT,
        OWNED_FUEL,
        INPUT,
        FUEL,
        UNRELATED
    }

    public record RecipeChoice(
            RecipeDisplayEntry entry,
            int outputCount,
            boolean requiresTable,
            boolean craftable) {
    }

    private record CraftingRecipeCandidate(RecipeChoice choice, String stableKey) {
    }

    private record CraftingDisplayShape(
            ResourceCatalog.RecipeLayout layout,
            int width,
            int height,
            List<List<String>> slots) {
        private String stableKey() {
            StringBuilder key = new StringBuilder(layout.name())
                    .append(':').append(width).append('x').append(height);
            for (List<String> slot : slots) {
                key.append('|').append(String.join("\u0000", slot));
            }
            return key.toString();
        }
    }

    private static final class EquipSession {
        private final String itemId;
        private final int syncId;
        private final int sourceSlot;
        private final int destinationSlot;
        private int stage;

        private EquipSession(String itemId, int syncId, int sourceSlot, int destinationSlot, int stage) {
            this.itemId = itemId;
            this.syncId = syncId;
            this.sourceSlot = sourceSlot;
            this.destinationSlot = destinationSlot;
            this.stage = stage;
        }

        private boolean matches(String itemId, int syncId) {
            return this.itemId.equals(itemId) && this.syncId == syncId;
        }
    }
}
