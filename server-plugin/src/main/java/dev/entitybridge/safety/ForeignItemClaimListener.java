package dev.entitybridge.safety;

import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persists player-drop custody on the item entity and enforces it independently
 * of the client. Entity's own drops and ordinary world loot are deliberately
 * left unclaimed.
 */
public final class ForeignItemClaimListener implements Listener {
    private static final int PERSISTED_SCHEMA = 1;
    private static final int MAX_LOGGED_PICKUP_VETOES = 512;

    private final Plugin plugin;
    private final ForeignItemClaimSynchronization synchronization;
    private final Logger logger;
    private final String configuredOwnerName;
    private final NamespacedKey schemaKey;
    private final NamespacedKey ownerUuidKey;
    private final NamespacedKey ownerNameKey;
    private final NamespacedKey claimedAtKey;
    private final Set<UUID> loggedPickupVetoes = new LinkedHashSet<>();

    public ForeignItemClaimListener(
            Plugin plugin,
            ForeignItemClaimSynchronization synchronization,
            Logger logger,
            String configuredOwnerName) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.synchronization = Objects.requireNonNull(synchronization, "synchronization");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.configuredOwnerName = Objects.requireNonNullElse(
                configuredOwnerName, "").trim();
        schemaKey = new NamespacedKey(plugin, "foreign_item_schema");
        ownerUuidKey = new NamespacedKey(plugin, "foreign_item_owner_uuid");
        ownerNameKey = new NamespacedKey(plugin, "foreign_item_owner_name");
        claimedAtKey = new NamespacedKey(plugin, "foreign_item_claimed_at");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player dropper = event.getPlayer();
        if (ForeignItemClaimPolicy.playerDrop(dropper.getName())
                != ForeignItemClaimPolicy.Decision.CLAIM_PLAYER_DROP) return;
        claim(event.getItemDrop(), dropper.getUniqueId(), dropper.getName(),
                System.currentTimeMillis());
    }

    /**
     * Covers player death drops and other vanilla player-originated item spawns
     * whose owner/thrower is populated without a PlayerDropItemEvent. The
     * one-tick recheck lets vanilla finish assigning those fields first.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!item.isValid() || tagged(item)) return;
            UUID source = item.getThrower() != null ? item.getThrower() : item.getOwner();
            if (source == null) return;
            OfflinePlayer player = plugin.getServer().getOfflinePlayer(source);
            String name = player.getName();
            if (name == null || ForeignItemClaimPolicy.playerDrop(name)
                    != ForeignItemClaimPolicy.Decision.CLAIM_PLAYER_DROP) return;
            claim(item, source, name, System.currentTimeMillis());
        });
    }

    /** A claimed stack cannot merge into an unclaimed UUID and shed custody. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        if (ForeignItemClaimPolicy.merge(
                tagged(event.getEntity()), tagged(event.getTarget()))
                != ForeignItemClaimPolicy.Decision.REJECT_MERGE) return;
        event.setCancelled(true);
        logger.info("Foreign item merge veto source=" + event.getEntity().getUniqueId()
                + " target=" + event.getTarget().getUniqueId());
    }

    /** Paper is the final boundary even if the client routes onto a stale item. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupGuard(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ReadResult read = read(event.getItem());
        String claimOwner = read.claim().map(ForeignItemClaim::ownerName).orElse("");
        if (ForeignItemClaimPolicy.pickup(
                player.getName(), read.tagged(), claimOwner, configuredOwnerName)
                != ForeignItemClaimPolicy.Decision.REJECT_ENTITY_PICKUP) return;
        event.setCancelled(true);
        String owner = read.claim().map(ForeignItemClaim::ownerName).orElse("invalid-claim");
        logPickupVetoOnce(player, event.getItem(), owner);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupComplete(EntityPickupItemEvent event) {
        if (event.getRemaining() == 0 && tagged(event.getItem())) {
            forgetPickupVeto(event.getItem());
            synchronization.remove(key(event.getItem()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (tagged(event.getEntity())) {
            forgetPickupVeto(event.getEntity());
            synchronization.remove(key(event.getEntity()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof Item item && tagged(item)) {
            forgetPickupVeto(item);
            synchronization.remove(key(item));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item) publishLoaded(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item && tagged(item)) {
                forgetPickupVeto(item);
                synchronization.remove(key(item));
            }
        }
    }

    /** Must be invoked on Paper's primary thread. */
    public ScanResult scanLoadedClaims() {
        ArrayList<ForeignItemClaim> claims = new ArrayList<>();
        boolean complete = true;
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                ReadResult read = read(item);
                if (!read.tagged()) continue;
                if (read.claim().isEmpty()) {
                    complete = false;
                } else {
                    claims.add(read.claim().orElseThrow());
                }
            }
        }
        return new ScanResult(List.copyOf(claims), complete);
    }

    private void claim(Item item, UUID ownerId, String ownerName, long nowMillis) {
        PersistentDataContainer data = item.getPersistentDataContainer();
        data.set(schemaKey, PersistentDataType.INTEGER, PERSISTED_SCHEMA);
        data.set(ownerUuidKey, PersistentDataType.STRING, ownerId.toString());
        data.set(ownerNameKey, PersistentDataType.STRING, ownerName);
        data.set(claimedAtKey, PersistentDataType.LONG, nowMillis);
        ForeignItemClaim claim = read(item).claim().orElseThrow(
                () -> new IllegalStateException("new foreign-item claim did not round-trip"));
        synchronization.upsert(claim);
        logger.info("Foreign item claim entity=" + claim.entityId()
                + " owner=" + claim.ownerName() + " item=" + claim.itemId());
    }

    private void publishLoaded(Item item) {
        ReadResult read = read(item);
        if (!read.tagged()) return;
        if (read.claim().isPresent()) {
            synchronization.upsert(read.claim().orElseThrow());
        } else {
            synchronization.markSourceIncomplete();
            logger.warning("Malformed persisted foreign-item claim entity=" + item.getUniqueId()
                    + "; autonomous ground pickup remains fail-closed");
        }
    }

    /**
     * A player standing on a protected stack can generate pickup events every
     * tick. Keep one auditable decision per loaded item instead of flooding the
     * server log while preserving the pickup boundary on every event.
     */
    private void logPickupVetoOnce(Player player, Item item, String owner) {
        UUID itemId = item.getUniqueId();
        if (!loggedPickupVetoes.add(itemId)) return;
        while (loggedPickupVetoes.size() > MAX_LOGGED_PICKUP_VETOES) {
            UUID oldest = loggedPickupVetoes.iterator().next();
            loggedPickupVetoes.remove(oldest);
        }
        logger.info("Foreign item pickup veto collector=" + player.getName()
                + " entity=" + itemId
                + " owner=" + owner);
    }

    private void forgetPickupVeto(Item item) {
        loggedPickupVetoes.remove(item.getUniqueId());
    }

    private ReadResult read(Item item) {
        PersistentDataContainer data = item.getPersistentDataContainer();
        boolean tagged = tagged(data);
        if (!tagged) return new ReadResult(false, Optional.empty());
        try {
            Integer schema = data.get(schemaKey, PersistentDataType.INTEGER);
            String ownerUuid = data.get(ownerUuidKey, PersistentDataType.STRING);
            String ownerName = data.get(ownerNameKey, PersistentDataType.STRING);
            Long claimedAt = data.get(claimedAtKey, PersistentDataType.LONG);
            if (schema == null || schema != PERSISTED_SCHEMA
                    || ownerUuid == null || ownerName == null || claimedAt == null) {
                return new ReadResult(true, Optional.empty());
            }
            return new ReadResult(true, Optional.of(new ForeignItemClaim(
                    item.getUniqueId(),
                    item.getWorld().getUID(),
                    item.getWorld().getKey().toString(),
                    UUID.fromString(ownerUuid),
                    ownerName,
                    item.getItemStack().getType().getKey().toString(),
                    claimedAt)));
        } catch (IllegalArgumentException invalid) {
            return new ReadResult(true, Optional.empty());
        }
    }

    private boolean tagged(Item item) {
        return tagged(item.getPersistentDataContainer());
    }

    private boolean tagged(PersistentDataContainer data) {
        return data.has(schemaKey, PersistentDataType.INTEGER)
                || data.has(ownerUuidKey, PersistentDataType.STRING)
                || data.has(ownerNameKey, PersistentDataType.STRING)
                || data.has(claimedAtKey, PersistentDataType.LONG);
    }

    private static ForeignItemClaim.Key key(Item item) {
        return new ForeignItemClaim.Key(item.getWorld().getUID(), item.getUniqueId());
    }

    private record ReadResult(boolean tagged, Optional<ForeignItemClaim> claim) {
        private ReadResult {
            claim = Objects.requireNonNull(claim, "claim");
            if (!tagged && claim.isPresent()) {
                throw new IllegalArgumentException("untagged item cannot carry a claim");
            }
        }
    }

    public record ScanResult(List<ForeignItemClaim> claims, boolean complete) {
        public ScanResult {
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        }
    }
}
