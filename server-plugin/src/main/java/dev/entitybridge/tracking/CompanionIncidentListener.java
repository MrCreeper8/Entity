package dev.entitybridge.tracking;

import dev.entitybridge.command.EntityCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;

/** Read-only social observation; existing damage/combat/recovery owners are unchanged. */
public final class CompanionIncidentListener implements Listener {
    private final EntityCommand command;
    public CompanionIncidentListener(EntityCommand command) { this.command = command; }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player body) || !body.getName().equalsIgnoreCase("Entity")) return;
        Entity actor = attacker(event);
        if (actor instanceof Player player && actor != body && event.getFinalDamage() > 0)
            command.observeCompanionHit(body.getWorld().getUID(), player.getUniqueId(), player.getName(), event.getFinalDamage());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player body = event.getEntity(); if (!body.getName().equalsIgnoreCase("Entity")) return;
        EntityDamageEvent damage = body.getLastDamageCause();
        Entity actor = damage instanceof EntityDamageByEntityEvent by ? attacker(by) : null;
        command.observeCompanionDeath(body.getWorld().getUID(), damage == null ? "UNKNOWN" : damage.getCause().name(),
                actor == null ? "" : actor.getName(), actor == null ? "" : actor.getType().getKey().toString());
    }
    private static Entity attacker(EntityDamageByEntityEvent event) {
        Entity actor = event.getDamager();
        if (actor instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) return shooter;
        return actor;
    }
}
