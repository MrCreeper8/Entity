package dev.entity.client.control;

import dev.entity.client.bridge.EntityRelationshipPolicyProtocol;
import dev.entity.core.stewardship.EntityDispositionPolicy;
import dev.entity.core.stewardship.LivestockStewardshipPolicy;
import dev.entity.core.stewardship.PropertyCreeperSafetyPolicy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** Sole client boundary for deliberate entity attacks and relationship filtering. */
public final class MinecraftEntityAttackGateway {
    private static final Map<MinecraftClient, MinecraftEntityAttackGateway> SHARED =
            new WeakHashMap<>();

    private final MinecraftClient client;
    private EntityRelationshipPolicyProtocol.Snapshot relationshipPolicy;
    private HuntBoundary huntBoundary;
    private CreeperBoundary creeperBoundary;
    private Attempt lastAttempt = Attempt.none();

    private MinecraftEntityAttackGateway(MinecraftClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public static MinecraftEntityAttackGateway shared(MinecraftClient client) {
        Objects.requireNonNull(client, "client");
        synchronized (SHARED) {
            return SHARED.computeIfAbsent(client, MinecraftEntityAttackGateway::new);
        }
    }

    public synchronized void installRelationshipPolicy(
            EntityRelationshipPolicyProtocol.Snapshot policy) {
        relationshipPolicy = Objects.requireNonNull(policy, "policy");
    }

    public synchronized void connectionChanged(boolean connected) {
        if (!connected) relationshipPolicy = null;
    }

    /** Installs the live loaded-world policy used at selection and final strike. */
    public synchronized void installHuntBoundary(HuntBoundary boundary) {
        huntBoundary = Objects.requireNonNull(boundary, "boundary");
    }

    /** Installs the live Home/named-property boundary used before routing and striking. */
    public synchronized void installCreeperBoundary(CreeperBoundary boundary) {
        creeperBoundary = Objects.requireNonNull(boundary, "boundary");
    }

    public synchronized EntityDispositionPolicy.Result decision(
            Entity target,
            EntityDispositionPolicy.Purpose purpose) {
        Objects.requireNonNull(target, "target");
        Set<String> protectedNames = relationshipPolicy == null
                ? Set.of()
                : relationshipPolicy.protectedPlayerNames();
        return EntityDispositionPolicy.decide(
                purpose,
                facts(target),
                protectedNames,
                relationshipPolicy != null);
    }

    public synchronized boolean permitsSelection(
            Entity target,
            EntityDispositionPolicy.Purpose purpose) {
        EntityDispositionPolicy.Result relationship = decision(target, purpose);
        return relationship.allowed()
                && huntDecision(target, purpose).allowed()
                && (!(target instanceof CreeperEntity creeper)
                || creeperDecision(creeper).attackAllowed());
    }

    /** Shared current decision used by protection, explicit missions, and final attack. */
    public synchronized PropertyCreeperSafetyPolicy.Decision creeperDecision(
            CreeperEntity creeper) {
        Objects.requireNonNull(creeper, "creeper");
        if (creeperBoundary == null) {
            return PropertyCreeperSafetyPolicy.decide(
                    PropertyCreeperSafetyPolicy.Observation.of(
                            false,
                            Set.of(),
                            client.world == null
                                    ? "minecraft:unavailable"
                                    : client.world.getRegistryKey().getValue().toString(),
                            client.player == null ? creeper.getX() : client.player.getX(),
                            client.player == null ? creeper.getZ() : client.player.getZ(),
                            creeper.getX(), creeper.getZ(),
                            creeper.isCharged()));
        }
        try {
            return Objects.requireNonNull(
                    creeperBoundary.decide(creeper),
                    "creeper boundary decision");
        } catch (RuntimeException unavailable) {
            return PropertyCreeperSafetyPolicy.decide(
                    PropertyCreeperSafetyPolicy.Observation.of(
                            false,
                            Set.of(),
                            client.world == null
                                    ? "minecraft:unavailable"
                                    : client.world.getRegistryKey().getValue().toString(),
                            client.player == null ? creeper.getX() : client.player.getX(),
                            client.player == null ? creeper.getZ() : client.player.getZ(),
                            creeper.getX(), creeper.getZ(),
                            creeper.isCharged()));
        }
    }

    /** Rechecks the live entity relationship immediately before the attack packet. */
    public synchronized Attempt attack(
            Entity target,
            EntityDispositionPolicy.Purpose purpose,
            String source) {
        EntityDispositionPolicy.Result decision = decision(target, purpose);
        String targetId = target.getUuidAsString();
        String checkedSource = Objects.requireNonNullElse(source, "unknown");
        if (!decision.allowed()
                || client.player == null
                || client.interactionManager == null
                || !target.isAlive()
                || target.isRemoved()) {
            lastAttempt = new Attempt(
                    false, targetId, checkedSource, decision.code(), decision.detail());
            return lastAttempt;
        }
        LivestockStewardshipPolicy.Decision livestock = huntDecision(target, purpose);
        if (!livestock.allowed()) {
            lastAttempt = new Attempt(
                    false,
                    targetId,
                    checkedSource,
                    dispositionCode(livestock.code()),
                    "livestock safety: " + livestock.detail());
            return lastAttempt;
        }
        if (target instanceof CreeperEntity creeper) {
            PropertyCreeperSafetyPolicy.Decision property = creeperDecision(creeper);
            if (!property.attackAllowed()) {
                lastAttempt = new Attempt(
                        false,
                        targetId,
                        checkedSource,
                        dispositionCode(property.code()),
                        "creeper property safety: " + property.detail());
                return lastAttempt;
            }
        }
        client.interactionManager.attackEntity(client.player, target);
        lastAttempt = new Attempt(
                true, targetId, checkedSource, decision.code(), decision.detail());
        return lastAttempt;
    }

    public synchronized Attempt lastAttempt() {
        return lastAttempt;
    }

    private LivestockStewardshipPolicy.Decision huntDecision(
            Entity target,
            EntityDispositionPolicy.Purpose purpose) {
        if (purpose != EntityDispositionPolicy.Purpose.HUNT) {
            return LivestockStewardshipPolicy.Decision.allow();
        }
        if (huntBoundary == null) {
            return LivestockStewardshipPolicy.Decision.deny(
                    LivestockStewardshipPolicy.Code.OBSERVATION_UNAVAILABLE,
                    "livestock boundary has not been installed");
        }
        try {
            return Objects.requireNonNull(
                    huntBoundary.decide(target),
                    "hunt boundary decision");
        } catch (RuntimeException unavailable) {
            return LivestockStewardshipPolicy.Decision.deny(
                    LivestockStewardshipPolicy.Code.OBSERVATION_UNAVAILABLE,
                    "livestock observation failed closed: " + unavailable.getMessage());
        }
    }

    private static EntityDispositionPolicy.Code dispositionCode(
            LivestockStewardshipPolicy.Code code) {
        return switch (code) {
            case ALLOWED -> EntityDispositionPolicy.Code.ALLOWED;
            case OBSERVATION_UNAVAILABLE ->
                    EntityDispositionPolicy.Code.LIVESTOCK_POLICY_UNAVAILABLE;
            case INSIDE_PROTECTED_PROPERTY ->
                    EntityDispositionPolicy.Code.PROTECTED_LIVESTOCK;
            case CONTAINED_COHORT -> EntityDispositionPolicy.Code.CONTAINED_LIVESTOCK;
            case BREEDING_FLOOR -> EntityDispositionPolicy.Code.BREEDING_FLOOR;
            case TAMED -> EntityDispositionPolicy.Code.TAMED;
            case NAMED -> EntityDispositionPolicy.Code.NAMED;
            case LEASHED -> EntityDispositionPolicy.Code.LEASHED;
            case BABY -> EntityDispositionPolicy.Code.BABY;
        };
    }

    private static EntityDispositionPolicy.Code dispositionCode(
            PropertyCreeperSafetyPolicy.Code code) {
        return switch (code) {
            case WILDERNESS_ALLOWED -> EntityDispositionPolicy.Code.ALLOWED;
            case PROPERTY_COMBAT_BUFFER ->
                    EntityDispositionPolicy.Code.CREEPER_PROPERTY_BUFFER;
            case PROPERTY_POLICY_UNAVAILABLE ->
                    EntityDispositionPolicy.Code.CREEPER_PROPERTY_POLICY_UNAVAILABLE;
        };
    }

    private EntityDispositionPolicy.Facts facts(Entity target) {
        EntityDispositionPolicy.Kind kind;
        if (target instanceof PlayerEntity) kind = EntityDispositionPolicy.Kind.PLAYER;
        else if (target instanceof HostileEntity) kind = EntityDispositionPolicy.Kind.HOSTILE;
        else if (target instanceof PassiveEntity) kind = EntityDispositionPolicy.Kind.PASSIVE;
        else kind = EntityDispositionPolicy.Kind.UTILITY;
        boolean self = client.player != null
                && target.getUuid().equals(client.player.getUuid());
        boolean teammate = client.player != null && client.player.isTeammate(target);
        boolean tamed = target instanceof TameableEntity tameable && tameable.isTamed();
        boolean leashed = target instanceof Leashable leashable && leashable.isLeashed();
        boolean baby = target instanceof PassiveEntity passive && passive.isBaby();
        return new EntityDispositionPolicy.Facts(
                kind,
                target.getName().getString(),
                self,
                teammate,
                tamed,
                target.hasCustomName(),
                leashed,
                baby);
    }

    public record Attempt(
            boolean issued,
            String targetId,
            String source,
            EntityDispositionPolicy.Code code,
            String detail) {
        public Attempt {
            targetId = Objects.requireNonNullElse(targetId, "");
            source = Objects.requireNonNullElse(source, "");
            code = Objects.requireNonNull(code, "code");
            detail = Objects.requireNonNullElse(detail, "");
        }

        private static Attempt none() {
            return new Attempt(
                    false, "", "", EntityDispositionPolicy.Code.POLICY_UNAVAILABLE,
                    "no attack has been attempted");
        }
    }

    @FunctionalInterface
    public interface HuntBoundary {
        LivestockStewardshipPolicy.Decision decide(Entity target);
    }

    @FunctionalInterface
    public interface CreeperBoundary {
        PropertyCreeperSafetyPolicy.Decision decide(CreeperEntity creeper);
    }
}
