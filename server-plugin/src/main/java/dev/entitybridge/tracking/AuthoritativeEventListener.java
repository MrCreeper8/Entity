package dev.entitybridge.tracking;

import com.google.gson.JsonObject;
import dev.entitybridge.bridge.BridgeServer;
import dev.entitybridge.config.BridgeSettings;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Objects;
import java.util.UUID;

/** Paper-authoritative damage and cancelled-block facts for the Entity client. */
public final class AuthoritativeEventListener implements Listener {
    private final BridgeServer bridge;
    private final String ownerName;

    public AuthoritativeEventListener(BridgeServer bridge, BridgeSettings settings) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        BridgeSettings checkedSettings = Objects.requireNonNull(settings, "settings");
        this.ownerName = checkedSettings.ownerConfigured() ? checkedSettings.ownerName() : "";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        AuthoritativeDamageFrame.Target target = AuthoritativeDamageFrame
                .classifyTarget(player.getName(), ownerName)
                .orElse(null);
        if (target == null) return;

        long now = System.currentTimeMillis();
        Entity attacker = directOrProjectileAttacker(event);
        AuthoritativeDamageFrame.AttackerIdentity attackerIdentity = null;
        if (attacker != null && attacker != player) {
            boolean projectileDamage = event instanceof EntityDamageByEntityEvent by
                    && by.getDamager() instanceof Projectile;
            String projectileType = projectileDamage
                    ? ((Projectile) ((EntityDamageByEntityEvent) event).getDamager())
                            .getType().getKey().toString()
                    : "";
            attackerIdentity = new AuthoritativeDamageFrame.AttackerIdentity(
                    attacker.getUniqueId(),
                    attacker.getType().getKey().toString(),
                    attacker.getName(),
                    attacker.getLocation().distance(player.getLocation()),
                    projectileDamage,
                    projectileType
            );
        }
        JsonObject frame = AuthoritativeDamageFrame.encode(
                UUID.randomUUID(),
                new AuthoritativeDamageFrame.TargetIdentity(
                        target, player.getUniqueId(), player.getName()),
                event.getCause().name(),
                event.getFinalDamage(),
                player.getHealth(),
                now,
                attackerIdentity
        );
        bridge.sendFrame(frame);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        publishAttackerLifeBoundary(event.getEntity(), "death");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        publishAttackerLifeBoundary(event.getPlayer(), "respawn");
    }

    private void publishAttackerLifeBoundary(Player player, String reason) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "server_attacker_reset");
        frame.addProperty("schema", 1);
        frame.addProperty("attackerUuid", player.getUniqueId().toString());
        frame.addProperty("timestamp", System.currentTimeMillis());
        frame.addProperty("reason", reason);
        bridge.sendFrame(frame);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!event.isCancelled()
                || !event.getPlayer().getName().equalsIgnoreCase("Entity")) return;
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "block_break_rejected");
        frame.addProperty("eventId", UUID.randomUUID().toString());
        frame.addProperty("dimension", event.getBlock().getWorld().getKey().toString());
        frame.addProperty("x", event.getBlock().getX());
        frame.addProperty("y", event.getBlock().getY());
        frame.addProperty("z", event.getBlock().getZ());
        frame.addProperty("timestamp", System.currentTimeMillis());
        bridge.sendFrame(frame);
    }

    private static Entity directOrProjectileAttacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent by)) return null;
        Entity damager = by.getDamager();
        if (!(damager instanceof Projectile projectile)) return damager;
        ProjectileSource shooter = projectile.getShooter();
        return shooter instanceof Entity entity ? entity : damager;
    }
}
