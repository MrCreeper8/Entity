package dev.entitybridge.tracking;

import dev.entitybridge.bridge.BridgeServer;
import dev.entitybridge.config.BridgeSettings;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Publishes authoritative mob target acquisition, clearing, and bounded refresh facts. */
public final class AuthoritativeThreatTargetListener implements Listener {
    static final long REFRESH_TICKS = 20L;

    private final JavaPlugin plugin;
    private final BridgeServer bridge;
    private final String ownerName;
    private final AuthoritativeThreatTargetTracker tracker =
            new AuthoritativeThreatTargetTracker();
    private BukkitTask refreshTask;

    public AuthoritativeThreatTargetListener(
            JavaPlugin plugin,
            BridgeServer bridge,
            BridgeSettings settings
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        BridgeSettings checkedSettings = Objects.requireNonNull(settings, "settings");
        this.ownerName = checkedSettings.ownerConfigured() ? checkedSettings.ownerName() : "";
    }

    public void start() {
        if (refreshTask != null) return;
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refreshActiveTargets, REFRESH_TICKS, REFRESH_TICKS);
    }

    public void stop() {
        if (refreshTask == null) return;
        refreshTask.cancel();
        refreshTask = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        Entity attacker = event.getEntity();
        if (!(attacker instanceof Mob)) return;

        AuthoritativeThreatTargetFrame.TargetIdentity target =
                protectedTarget(event.getTarget());
        if (target == null) {
            publish(tracker.clear(attacker.getUniqueId()));
            return;
        }

        AuthoritativeThreatTargetFrame.AttackerIdentity attackerIdentity =
                attackerIdentity(attacker, event.getTarget());
        if (attackerIdentity == null) {
            publish(tracker.clear(attacker.getUniqueId()));
            return;
        }
        publish(tracker.observe(attackerIdentity, target));
    }

    private void refreshActiveTargets() {
        for (UUID attackerUuid : tracker.trackedAttackers()) {
            Entity attacker = plugin.getServer().getEntity(attackerUuid);
            if (!(attacker instanceof Mob mob) || !attacker.isValid() || attacker.isDead()) {
                publish(tracker.clear(attackerUuid));
                continue;
            }
            LivingEntity liveTarget = mob.getTarget();
            AuthoritativeThreatTargetFrame.TargetIdentity target = protectedTarget(liveTarget);
            AuthoritativeThreatTargetFrame.AttackerIdentity attackerIdentity =
                    attackerIdentity(attacker, liveTarget);
            if (target == null || attackerIdentity == null) {
                publish(tracker.clear(attackerUuid));
                continue;
            }
            publish(tracker.refresh(attackerIdentity, target));
        }
    }

    private AuthoritativeThreatTargetFrame.TargetIdentity protectedTarget(LivingEntity entity) {
        if (!(entity instanceof Player player)) return null;
        AuthoritativeThreatTargetFrame.Target target = AuthoritativeThreatTargetFrame
                .classifyTarget(player.getName(), ownerName)
                .orElse(null);
        return target == null ? null : new AuthoritativeThreatTargetFrame.TargetIdentity(
                target, player.getUniqueId(), player.getName());
    }

    private static AuthoritativeThreatTargetFrame.AttackerIdentity attackerIdentity(
            Entity attacker,
            LivingEntity target
    ) {
        if (target == null || !attacker.getWorld().equals(target.getWorld())) return null;
        return new AuthoritativeThreatTargetFrame.AttackerIdentity(
                attacker.getUniqueId(),
                attacker.getType().getKey().toString(),
                attacker.getName(),
                attacker.getLocation().distance(target.getLocation())
        );
    }

    private void publish(List<AuthoritativeThreatTargetTracker.Transition> transitions) {
        long timestamp = System.currentTimeMillis();
        for (AuthoritativeThreatTargetTracker.Transition transition : transitions) {
            bridge.sendFrame(AuthoritativeThreatTargetFrame.encode(
                    UUID.randomUUID(),
                    transition.active(),
                    transition.target(),
                    timestamp,
                    transition.attacker()
            ));
        }
    }
}
