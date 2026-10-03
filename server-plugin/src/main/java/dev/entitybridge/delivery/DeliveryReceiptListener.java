package dev.entitybridge.delivery;

import dev.entitybridge.bridge.BridgeServer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/** Tags Entity's exact prepared drop and publishes durable pickup receipts. */
public final class DeliveryReceiptListener implements Listener {
    private final DeliveryTransactionRegistry transactions;
    private final BridgeServer bridge;
    private final NamespacedKey missionKey;
    private final NamespacedKey nonceKey;
    private final NamespacedKey recipientKey;
    private final NamespacedKey itemKey;

    public DeliveryReceiptListener(
            Plugin plugin,
            DeliveryTransactionRegistry transactions,
            BridgeServer bridge) {
        Objects.requireNonNull(plugin, "plugin");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        missionKey = new NamespacedKey(plugin, "delivery_mission");
        nonceKey = new NamespacedKey(plugin, "delivery_nonce");
        recipientKey = new NamespacedKey(plugin, "delivery_recipient");
        itemKey = new NamespacedKey(plugin, "delivery_item");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!event.getPlayer().getName().equalsIgnoreCase("Entity")) return;
        Item item = event.getItemDrop();
        String itemId = item.getItemStack().getType().getKey().toString();
        transactions.claimDrop(
                item.getUniqueId(), itemId, item.getItemStack().getAmount(),
                System.currentTimeMillis()).ifPresent(claim -> {
            PersistentDataContainer data = item.getPersistentDataContainer();
            data.set(missionKey, PersistentDataType.STRING, claim.missionId());
            data.set(nonceKey, PersistentDataType.STRING, claim.nonce());
            data.set(recipientKey, PersistentDataType.STRING, claim.recipient());
            data.set(itemKey, PersistentDataType.STRING, claim.itemId());
        });
    }

    /** Tagged handoff stacks retain their UUID and accounting boundary. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        if (tagged(event.getEntity()) || tagged(event.getTarget())) event.setCancelled(true);
    }

    /** Only the prepared recipient (or Entity recovering its own stack) may collect it. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupGuard(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        String intended = event.getItem().getPersistentDataContainer()
                .get(recipientKey, PersistentDataType.STRING);
        if (intended == null) return;
        if (DeliveryPickupPolicy.decide(intended, player.getName())
                == DeliveryPickupPolicy.Decision.REJECT) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player recipient)) return;
        Item item = event.getItem();
        PersistentDataContainer data = item.getPersistentDataContainer();
        String expectedRecipient = data.get(recipientKey, PersistentDataType.STRING);
        String expectedItem = data.get(itemKey, PersistentDataType.STRING);
        if (expectedRecipient == null || expectedItem == null) return;
        DeliveryPickupPolicy.Decision decision = DeliveryPickupPolicy.decide(
                expectedRecipient, recipient.getName());
        if (decision == DeliveryPickupPolicy.Decision.ENTITY_RETURN) {
            if (event.getRemaining() == 0) {
                transactions.returnDrop(item.getUniqueId(), System.currentTimeMillis())
                        .ifPresent(returned -> bridge.sendFrame(returned.toFrame()));
            }
            return;
        }
        if (decision != DeliveryPickupPolicy.Decision.INTENDED_RECIPIENT) return;

        transactions.recordPickup(
                item.getUniqueId(),
                recipient.getName(),
                item.getItemStack().getType().getKey().toString(),
                event.getRemaining(),
                System.currentTimeMillis()
        ).ifPresent(receipt -> bridge.sendFrame(receipt.toFrame()));
    }

    private boolean tagged(Item item) {
        return item.getPersistentDataContainer().has(nonceKey, PersistentDataType.STRING);
    }
}
