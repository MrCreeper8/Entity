package dev.entitybridge.stewardship;

import dev.entity.core.stewardship.ProtectedAreaHomePermit;
import dev.entity.core.stewardship.ProtectedAreaPolicy;
import dev.entitybridge.blueprint.BlueprintBuildAuthority;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Paper-authoritative final veto for every Entity world mutation surface. */
public final class ProtectedAreaMutationListener implements Listener {
    private static final long NOTICE_COOLDOWN_MILLIS = 3_000L;
    private static final Set<Material> INTERACTIVE_WORKSTATIONS = Set.of(
            Material.CRAFTING_TABLE,
            Material.FURNACE,
            Material.BLAST_FURNACE,
            Material.SMOKER,
            Material.BREWING_STAND,
            Material.ANVIL,
            Material.CHIPPED_ANVIL,
            Material.DAMAGED_ANVIL,
            Material.ENCHANTING_TABLE,
            Material.GRINDSTONE,
            Material.LOOM,
            Material.CARTOGRAPHY_TABLE,
            Material.SMITHING_TABLE,
            Material.STONECUTTER);

    private final ProtectedAreaRegistry registry;
    private final ProtectedAreaSynchronization synchronization;
    private final ProtectedAreaHomePermitRegistry homePermits;
    private final BlueprintBuildAuthority blueprintAuthority;
    private final Map<UUID, Notice> notices = new HashMap<>();
    private final Map<UUID, ContainerSession> containerSessions = new HashMap<>();

    public ProtectedAreaMutationListener(
            ProtectedAreaRegistry registry,
            ProtectedAreaSynchronization synchronization,
            ProtectedAreaHomePermitRegistry homePermits) {
        this(registry, synchronization, homePermits, null);
    }

    public ProtectedAreaMutationListener(ProtectedAreaRegistry registry,
            ProtectedAreaSynchronization synchronization, ProtectedAreaHomePermitRegistry homePermits,
            BlueprintBuildAuthority blueprintAuthority) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.synchronization = Objects.requireNonNull(synchronization, "synchronization");
        this.homePermits = Objects.requireNonNull(homePermits, "homePermits");
        this.blueprintAuthority = blueprintAuthority;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isEntity(event.getPlayer())) return;
        Block block = event.getBlock();
        if (blueprintAuthority != null && !occupied(block.getState()) && blueprintAuthority.mayBreak(
                block.getWorld().getKey().toString(), block.getX(), block.getY(), block.getZ(), block.getBlockData().getAsString(),
                blueprintRepairable(block))) return;
        denyIfForbidden(event.getPlayer(), ProtectedAreaPolicy.Action.BREAK,
                List.of(target(event.getBlock())), false, event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEntity(event.getPlayer())) return;
        List<BlockState> replacedBlocks = event instanceof BlockMultiPlaceEvent multi
                ? multi.getReplacedBlockStates() : List.of(event.getBlockReplacedState());
        if (blueprintAuthority != null && replacedBlocks.stream().allMatch(replaced -> !occupied(replaced)
                && blueprintAuthority.mayPlace(replaced.getWorld().getKey().toString(), replaced.getX(), replaced.getY(),
                replaced.getZ(), replaced.getBlockData().getAsString(), replaced.getBlock().getBlockData().getAsString()))) return;
        LinkedHashSet<ProtectedAreaHomePermit.Target> targets = new LinkedHashSet<>();
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                targets.add(target(replaced.getBlock()));
            }
        } else {
            targets.add(target(event.getBlockPlaced()));
        }
        denyIfForbidden(event.getPlayer(), ProtectedAreaPolicy.Action.PLACE,
                List.copyOf(targets), true, event::setCancelled);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAcceptedSupportBreak(BlockBreakEvent event) {
        if (blueprintAuthority == null) return;
        Block block = event.getBlock();
        blueprintAuthority.invalidateSupport(block.getWorld().getKey().toString(), block.getX(), block.getY(), block.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSupportReplacement(BlockPlaceEvent event) {
        if (blueprintAuthority == null) return;
        // mayPlace records before the event returns. A later cancellation cannot
        // leave a provisional receipt; accepted foreign placement never inherits it.
        if (!invalidatesSupportPlacement(isEntity(event.getPlayer()), event.isCancelled(), event.canBuild())) return;
        List<BlockState> replaced = event instanceof BlockMultiPlaceEvent multi
                ? multi.getReplacedBlockStates() : List.of(event.getBlockReplacedState());
        for (BlockState state : replaced) blueprintAuthority.invalidateSupport(state.getWorld().getKey().toString(),
                state.getX(), state.getY(), state.getZ());
    }

    static boolean invalidatesSupportPlacement(boolean entity, boolean cancelled, boolean canBuild) {
        return entity ? cancelled || !canBuild : !cancelled && canBuild;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!isEntity(event.getPlayer())) return;
        denyIfForbidden(event.getPlayer(), ProtectedAreaPolicy.Action.FLUID,
                List.of(target(event.getBlock())), true, event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!isEntity(event.getPlayer())) return;
        denyIfForbidden(event.getPlayer(), ProtectedAreaPolicy.Action.FLUID,
                List.of(target(event.getBlock())), true, event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (!isEntity(player)) return;
        denyIfForbidden(player, ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                List.of(target(event.getBlock())), true, event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isEntity(player) || event.getClickedBlock() == null) return;
        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> {
                ItemStack item = event.getItem();
                Material held = item == null ? Material.AIR : item.getType();
                Block clicked = event.getClickedBlock();
                ProtectedAreaHomePermit.Target exactTarget = target(clicked);
                long now = System.currentTimeMillis();
                ProtectedAreaPolicy.Action action = isContainer(clicked)
                        ? ProtectedAreaPolicy.Action.CONTAINER
                        : ProtectedAreaPolicy.Action.BLOCK_INTERACT;
                if (action == ProtectedAreaPolicy.Action.BLOCK_INTERACT
                        && isPlayerOperablePortal(clicked)
                        && !player.isSneaking()) {
                    // Vanilla uses a wooden portal before the carried BlockItem.
                    // Outside property there is deliberately no passage permit;
                    // do not reinterpret this ordinary toggle as scaffold placement.
                    if (!inside(exactTarget)) return;
                    if (homePermits.peek(
                        ProtectedAreaHomePermit.Purpose.REVERSIBLE_PASSAGE,
                        action, List.of(exactTarget), now).isPresent()) {
                        if (protectedPassageDecision(exactTarget).allowed()) {
                            homePermits.consume(
                                    ProtectedAreaHomePermit.Purpose.REVERSIBLE_PASSAGE,
                                    action, List.of(exactTarget), now);
                            return;
                        }
                    }
                    event.setCancelled(true);
                    notifyOnce(player, protectedDecision(action, exactTarget, false));
                    return;
                }
                // Exact reversible passage has priority: a builder can still be
                // carrying project blocks when it needs to open its own door.
                // Standing use of a container is not placement, even when the
                // carried block also matches a neighboring project/support cell.
                // It must reach the existing exact CONTAINER permit/session gate.
                // Native sneaking still selects blueprint placement on that support.
                if (blueprintAuthority != null
                        && (action != ProtectedAreaPolicy.Action.CONTAINER || player.isSneaking())
                        && held.isBlock()) {
                    Block adjacent = clicked.getRelative(event.getBlockFace());
                    if (blueprintAuthority.mayClickPlacement(clicked.getWorld().getKey().toString(),
                            adjacent.getX(), adjacent.getY(), adjacent.getZ(), held.getKey().toString())
                            || blueprintAuthority.mayClickPlacement(clicked.getWorld().getKey().toString(),
                            clicked.getX(), clicked.getY(), clicked.getZ(), held.getKey().toString())) {
                        configureBlueprintPlacementClick(event, player.isSneaking(), clicked.getType().isInteractable());
                        return;
                    }
                }
                if (action != ProtectedAreaPolicy.Action.CONTAINER && held.isBlock()) {
                    if (homePermits.peek(action, List.of(exactTarget), now).isPresent()
                            && protectedDecision(action, exactTarget, true).allowed()) {
                        homePermits.consume(action, List.of(exactTarget), now);
                        return;
                    }
                    // A pinned furniture placement targets the adjacent cell,
                    // not the existing support/shell block clicked to place it.
                    ProtectedAreaHomePermit.Target placement = target(
                            clicked.getRelative(event.getBlockFace()));
                    if (isHomeFurniture(held)
                            && homePermits.peek(ProtectedAreaPolicy.Action.PLACE,
                            List.of(placement), now).isPresent()
                            && protectedDecision(ProtectedAreaPolicy.Action.PLACE,
                            placement, true).allowed()) return;
                    if (!inside(exactTarget)) return;
                    event.setCancelled(true);
                    notifyOnce(player, protectedDecision(
                            ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                            exactTarget, false));
                    return;
                }
                if (action != ProtectedAreaPolicy.Action.CONTAINER && isBucket(held)) {
                    ProtectedAreaHomePermit.Target adjacent = target(
                            clicked.getRelative(event.getBlockFace()));
                    List<ProtectedAreaHomePermit.Target> fluidTargets =
                            held == Material.BUCKET
                                    ? List.of(exactTarget)
                                    : List.of(adjacent);
                    boolean protectedFluidTarget = fluidTargets.stream().anyMatch(this::inside);
                    if (!protectedFluidTarget) return;
                    event.setCancelled(true);
                    ProtectedAreaHomePermit.Target deniedTarget = fluidTargets.stream()
                            .filter(this::inside).findFirst().orElse(exactTarget);
                    notifyOnce(player, protectedDecision(
                            ProtectedAreaPolicy.Action.FLUID,
                            deniedTarget, false));
                    return;
                }
                Denial denial = decision(action, List.of(exactTarget), false, now);
                if (denial != null) {
                    event.setCancelled(true);
                    notifyOnce(player, denial.decision());
                    return;
                }
                if (action == ProtectedAreaPolicy.Action.CONTAINER) {
                    boolean exactHome = homePermits.consume(action,
                            List.of(exactTarget), now).isPresent();
                    if (!inside(exactTarget) || exactHome
                            && protectedDecision(action, exactTarget, true).allowed()) {
                        ProtectedAreaSynchronization.Status status =
                                synchronization.status();
                        containerSessions.put(player.getUniqueId(), new ContainerSession(
                                exactTarget, status.currentRevision(), status.currentDigest(),
                                exactHome ? "registered-home-asset" : "outside-protected-property"));
                        return;
                    }
                    event.setCancelled(true);
                    notifyOnce(player, protectedDecision(action, exactTarget, false));
                } else {
                    homePermits.consume(action, List.of(exactTarget), now);
                }
            }
            case PHYSICAL -> denyIfForbidden(
                    player, ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                    List.of(target(event.getClickedBlock())), true, event::setCancelled);
            default -> {
                // Left-click mutation is finalized by BlockBreakEvent.
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !isEntity(player)) return;
        ProtectedAreaHomePermit.Target inventoryTarget = inventoryTarget(event.getInventory())
                .orElse(null);
        if (inventoryTarget == null || !inside(inventoryTarget)) return;
        if (validContainerSession(player, inventoryTarget) != null) return;
        event.setCancelled(true);
        notifyOnce(player, protectedDecision(
                ProtectedAreaPolicy.Action.CONTAINER, inventoryTarget, false));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isEntity(player)) return;
        guardContainerMutation(player, event.getView().getTopInventory(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isEntity(player)) return;
        guardContainerMutation(player, event.getView().getTopInventory(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && isEntity(player)) {
            containerSessions.remove(player.getUniqueId());
        }
    }

    private void guardContainerMutation(
            Player player,
            Inventory topInventory,
            Canceller canceller) {
        ProtectedAreaHomePermit.Target inventoryTarget = inventoryTarget(topInventory)
                .orElse(null);
        ContainerSession session = containerSessions.get(player.getUniqueId());
        if (inventoryTarget == null) {
            // Workbench-style handlers do not expose a Bukkit inventory location;
            // they are admitted only after an exact permitted block interaction.
            if (session != null && sessionCurrent(session)) return;
            if (topInventory != null
                    && topInventory.getType() == InventoryType.WORKBENCH) {
                canceller.cancel(true);
            }
            return;
        }
        if (!inside(inventoryTarget)) return;
        if (validContainerSession(player, inventoryTarget) != null) return;
        canceller.cancel(true);
        notifyOnce(player, protectedDecision(
                ProtectedAreaPolicy.Action.CONTAINER, inventoryTarget, false));
    }

    private void denyIfForbidden(
            Player player,
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            boolean consumePermit,
            Canceller canceller) {
        Denial denial = decision(action, targets, consumePermit, System.currentTimeMillis());
        if (denial == null) return;
        canceller.cancel(true);
        notifyOnce(player, denial.decision());
    }

    private Denial decision(
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            boolean consumePermit,
            long nowMillis) {
        boolean exactHome = (consumePermit
                ? homePermits.consume(action, targets, nowMillis)
                : homePermits.peek(action, targets, nowMillis)).isPresent();
        for (ProtectedAreaHomePermit.Target target : targets) {
            ProtectedAreaPolicy.Decision decision = protectedDecision(
                    action, target, exactHome);
            if (!decision.allowed()) return new Denial(decision);
        }
        return null;
    }

    private static boolean isHomeFurniture(Material material) {
        return material == Material.CRAFTING_TABLE || material == Material.FURNACE
                || material == Material.CHEST || material.name().endsWith("_BED");
    }

    private static boolean occupied(BlockState state) {
        if (!(state instanceof InventoryHolder holder)) return false;
        for (ItemStack item : holder.getInventory().getContents()) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) return true;
        }
        return false;
    }

    private boolean blueprintRepairable(Block block) {
        if(block.isLiquid())return false;
        if(!(block.getState() instanceof org.bukkit.block.TileState))return block.getType().getHardness()>=0;
        if(!(block.getBlockData() instanceof org.bukkit.block.data.type.Bed bed)||bed.isOccupied()
                ||!((org.bukkit.block.TileState)block.getState()).getPersistentDataContainer().isEmpty())return false;
        var facing=bed.getPart()==org.bukkit.block.data.type.Bed.Part.FOOT?bed.getFacing():bed.getFacing().getOppositeFace();
        Block partner=block.getRelative(facing);
        if(partner.getBlockData() instanceof org.bukkit.block.data.type.Bed other&&other.isOccupied())return false;
        if(partner.getState() instanceof org.bukkit.block.TileState tile&&!tile.getPersistentDataContainer().isEmpty())return false;
        return blueprintAuthority.includesBedPair(block.getWorld().getKey().toString(),block.getX(),block.getY(),block.getZ(),
                partner.getX(),partner.getY(),partner.getZ());
    }

    private ProtectedAreaPolicy.Decision protectedDecision(
            ProtectedAreaPolicy.Action action,
            ProtectedAreaHomePermit.Target target,
            boolean exactHome) {
        return ProtectedAreaPolicy.decide(
                registry.snapshot(),
                synchronization.mutationAuthorityReady(),
                action,
                target.dimension(), target.x(), target.y(), target.z(),
                exactHome);
    }

    private ProtectedAreaPolicy.Decision protectedPassageDecision(
            ProtectedAreaHomePermit.Target target) {
        return ProtectedAreaPolicy.decideReversiblePassage(
                registry.snapshot(), synchronization.mutationAuthorityReady(),
                ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                target.dimension(), target.x(), target.y(), target.z());
    }

    private ContainerSession validContainerSession(
            Player player,
            ProtectedAreaHomePermit.Target target) {
        ContainerSession session = containerSessions.get(player.getUniqueId());
        if (session == null || !session.target().equals(target) || !sessionCurrent(session)) {
            containerSessions.remove(player.getUniqueId());
            return null;
        }
        return session;
    }

    private boolean sessionCurrent(ContainerSession session) {
        ProtectedAreaSynchronization.Status status = synchronization.status();
        return status.mutationAuthorityReady()
                && status.currentRevision() == session.revision()
                && status.currentDigest().equals(session.digest());
    }

    private boolean inside(ProtectedAreaHomePermit.Target target) {
        return registry.snapshot().firstContaining(
                target.dimension(), target.x(), target.z()).isPresent();
    }

    static void configureBlueprintPlacementClick(PlayerInteractEvent event, boolean sneaking, boolean supportInteractable) {
        // Paper 1.21.8 returns early from useItemOn when useInteractedBlock is
        // DENY, even if useItemInHand is ALLOW. That rolls every predicted block
        // back. Only an interactive support needs the native sneaking bypass:
        // ordinary oak/stone supports have no interaction to suppress. This is
        // reached only after exact project BlockItem/coordinate authorization;
        // the final BlockPlace/BlockMultiPlace state checks remain independent.
        if (supportInteractable && !sneaking) { event.setCancelled(true); return; }
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DEFAULT);
        event.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
    }

    private static boolean isContainer(Block block) {
        return block.getState() instanceof InventoryHolder
                || INTERACTIVE_WORKSTATIONS.contains(block.getType());
    }

    /** Final server-side semantic check for reversible passage authority. */
    private static boolean isPlayerOperablePortal(Block block) {
        Material material = block.getType();
        if (material == Material.IRON_DOOR || material == Material.IRON_TRAPDOOR) {
            return false;
        }
        BlockData data = block.getBlockData();
        return data instanceof Door || data instanceof TrapDoor || data instanceof Gate;
    }

    private static boolean isBucket(Material material) {
        return material == Material.BUCKET
                || material.name().endsWith("_BUCKET");
    }

    private static Optional<ProtectedAreaHomePermit.Target> inventoryTarget(
            Inventory inventory) {
        // Bukkit exposes the player's own 2x2 crafting handler with the
        // player's world location. That location is not a container block: if
        // it is treated as one, every ordinary hotbar/inventory click made
        // while standing inside protected property is cancelled and corrected
        // by Paper. Only world-backed inventories belong to this fence.
        if (inventory == null || ProtectedInventorySurfacePolicy.isPlayerLocal(
                inventory.getType().name())) {
            return Optional.empty();
        }
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) return Optional.empty();
        return Optional.of(new ProtectedAreaHomePermit.Target(
                location.getWorld().getKey().toString(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    private static ProtectedAreaHomePermit.Target target(Block block) {
        return new ProtectedAreaHomePermit.Target(
                block.getWorld().getKey().toString(),
                block.getX(), block.getY(), block.getZ());
    }

    private static boolean isEntity(Player player) {
        return player != null && player.getName().equalsIgnoreCase("Entity");
    }

    private void notifyOnce(Player player, ProtectedAreaPolicy.Decision decision) {
        long now = System.currentTimeMillis();
        Notice prior = notices.get(player.getUniqueId());
        String fingerprint = decision.code() + '|' + decision.dimension() + '|'
                + decision.x() + '|' + decision.y() + '|' + decision.z();
        if (prior != null && prior.fingerprint().equals(fingerprint)
                && now - prior.atMillis() < NOTICE_COOLDOWN_MILLIS) {
            return;
        }
        notices.put(player.getUniqueId(), new Notice(fingerprint, now));
        player.sendActionBar(Component.text("Entity stopped: ", NamedTextColor.RED)
                .append(Component.text(decision.detail(), NamedTextColor.YELLOW)));
    }

    @FunctionalInterface
    private interface Canceller {
        void cancel(boolean cancelled);
    }

    private record Denial(ProtectedAreaPolicy.Decision decision) {
    }

    private record Notice(String fingerprint, long atMillis) {
    }

    private record ContainerSession(
            ProtectedAreaHomePermit.Target target,
            long revision,
            String digest,
            String authorityId) {
    }
}
