package dev.entitybridge.safety;

import com.google.gson.JsonObject;
import dev.entity.core.stewardship.EntityDispositionPolicy;
import dev.entitybridge.bridge.BridgeServer;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/** Paper-final veto and evidence for every damage attempt made by Entity. */
public final class FriendlyDamageGuardListener implements Listener {
    public static final String ENTITY_PLAYER_NAME = "Entity";

    private final BridgeServer bridge;
    private final TrustedIdentityRegistry identities;
    private final Logger logger;

    public FriendlyDamageGuardListener(
            BridgeServer bridge,
            TrustedIdentityRegistry identities,
            Logger logger) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFriendlyDamageGuard(EntityDamageByEntityEvent event) {
        Player attacker = entityAttacker(event.getDamager());
        if (attacker == null || !attacker.getName().equalsIgnoreCase(ENTITY_PLAYER_NAME)) return;

        EntityDispositionPolicy.Result decision = decision(attacker, event.getEntity());
        if (!decision.allowed()) event.setCancelled(true);
        bridge.sendFrame(authorizationFrame(event.getEntity(), decision));
        logger.info("Entity attack authorization target="
                + event.getEntity().getUniqueId()
                + " name=" + event.getEntity().getName()
                + " type=" + event.getEntity().getType().getKey()
                + " allowed=" + decision.allowed()
                + " code=" + decision.code().name().toLowerCase());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityDamageObserved(EntityDamageByEntityEvent event) {
        Player attacker = entityAttacker(event.getDamager());
        if (attacker == null || !attacker.getName().equalsIgnoreCase(ENTITY_PLAYER_NAME)) return;
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "entity_attack_observed");
        frame.addProperty("eventId", UUID.randomUUID().toString());
        frame.addProperty("targetUuid", event.getEntity().getUniqueId().toString());
        frame.addProperty("targetType", event.getEntity().getType().getKey().toString());
        frame.addProperty("cancelled", event.isCancelled());
        frame.addProperty("finalDamage", event.isCancelled() ? 0.0D : event.getFinalDamage());
        frame.addProperty("timestamp", System.currentTimeMillis());
        bridge.sendFrame(frame);
        logger.info("Entity attack observed target="
                + event.getEntity().getUniqueId()
                + " name=" + event.getEntity().getName()
                + " type=" + event.getEntity().getType().getKey()
                + " cancelled=" + event.isCancelled()
                + " finalDamage=" + (event.isCancelled() ? 0.0D : event.getFinalDamage()));
    }

    EntityDispositionPolicy.Result decision(Player attacker, Entity target) {
        TrustedIdentityRegistry.Snapshot snapshot = identities.snapshot();
        return EntityDispositionPolicy.decide(
                EntityDispositionPolicy.Purpose.EXPLICIT_ATTACK,
                facts(attacker, target),
                snapshot.protectedPlayerNames(),
                true);
    }

    private static EntityDispositionPolicy.Facts facts(Player attacker, Entity target) {
        EntityDispositionPolicy.Kind kind;
        if (target instanceof Player) kind = EntityDispositionPolicy.Kind.PLAYER;
        else if (target instanceof Monster) kind = EntityDispositionPolicy.Kind.HOSTILE;
        else if (target instanceof Animals) kind = EntityDispositionPolicy.Kind.PASSIVE;
        else if (target instanceof Villager || target instanceof ArmorStand) {
            kind = EntityDispositionPolicy.Kind.UTILITY;
        } else kind = EntityDispositionPolicy.Kind.OTHER;

        boolean teammate = target instanceof Player player
                && sameTeam(attacker, player);
        boolean tamed = target instanceof Tameable tameable && tameable.isTamed();
        boolean leashed = target instanceof LivingEntity living && living.isLeashed();
        boolean baby = target instanceof Ageable ageable && !ageable.isAdult();
        return new EntityDispositionPolicy.Facts(
                kind,
                target.getName(),
                target.getUniqueId().equals(attacker.getUniqueId()),
                teammate,
                tamed,
                target.customName() != null,
                leashed,
                baby);
    }

    private static boolean sameTeam(Player left, Player right) {
        var team = left.getScoreboard().getEntryTeam(left.getName());
        return team != null && team.hasEntry(right.getName());
    }

    private static Player entityAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (!(damager instanceof Projectile projectile)) return null;
        ProjectileSource shooter = projectile.getShooter();
        return shooter instanceof Player player ? player : null;
    }

    private static JsonObject authorizationFrame(
            Entity target,
            EntityDispositionPolicy.Result decision) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "entity_attack_authorization");
        frame.addProperty("eventId", UUID.randomUUID().toString());
        frame.addProperty("targetUuid", target.getUniqueId().toString());
        frame.addProperty("targetType", target.getType().getKey().toString());
        frame.addProperty("allowed", decision.allowed());
        frame.addProperty("code", decision.code().name().toLowerCase());
        frame.addProperty("detail", decision.detail());
        frame.addProperty("timestamp", System.currentTimeMillis());
        return frame;
    }
}
