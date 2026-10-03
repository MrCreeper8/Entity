package dev.entity.client.protection;

import dev.entity.client.autonomy.policy.TacticalCombatPolicy;
import dev.entity.core.survival.WorldSnapshot;

import java.util.Objects;

/** Converts a platform-owned raw fact snapshot into the pure disengagement observation. */
public final class DistantThreatObservationFactory {
    private DistantThreatObservationFactory() {
    }

    public static DistantThreatDisengagementPolicy.Observation from(Facts facts) {
        Objects.requireNonNull(facts, "facts");
        DistantThreatDisengagementPolicy.TargetKind kind = switch (facts.combatShape()) {
            case ORDINARY_MELEE -> DistantThreatDisengagementPolicy.TargetKind.ORDINARY_MELEE;
            case ORDINARY_RANGED -> DistantThreatDisengagementPolicy.TargetKind.ORDINARY_RANGED;
            case PLAYER -> DistantThreatDisengagementPolicy.TargetKind.PLAYER;
            case CREEPER -> DistantThreatDisengagementPolicy.TargetKind.CREEPER;
            case OTHER -> DistantThreatDisengagementPolicy.TargetKind.OTHER;
        };
        boolean lowHealth = facts.health() / facts.maximumHealth()
                <= TacticalCombatPolicy.DEFAULT_LIMITS.lowHealthRatio();
        boolean modeledLethal = facts.modeledDamage() >= facts.health();
        boolean bodyBlocker = facts.targetPresent()
                && facts.distance()
                <= DistantThreatDisengagementPolicy.DEFAULT_MINIMUM_CLEARANCE;
        return new DistantThreatDisengagementPolicy.Observation(
                facts.nowMillis(),
                facts.episodeId(),
                facts.targetId(),
                kind,
                facts.targetPresent(),
                facts.distance(),
                facts.trustedLocalTargetingEvidence(),
                facts.paperAuthoritativeAttacker(),
                facts.freshDamageEvidence(),
                facts.projectilePressure(),
                facts.lineOfSight(),
                facts.otherEligibleThreat(),
                lowHealth,
                modeledLethal,
                bodyBlocker,
                facts.survivalActive(), facts.explosiveFactsKnown(),
                facts.explosivePrimed(), facts.explosiveCharged());
    }

    /**
     * Contextualizes Minecraft's local target binding into a trustworthy immediate-pressure fact.
     * A melee mob may keep {@code HostileEntity#getTarget()} long after it stopped closing; the
     * binding keeps the UUID observable, but at long range it cannot by itself renew protection.
     */
    public static boolean trustedLocalTargetingEvidence(
            boolean explicitLocalTargetBinding,
            CombatShape combatShape,
            double distance) {
        Objects.requireNonNull(combatShape, "combatShape");
        if (!Double.isFinite(distance) || distance < 0.0) {
            throw new IllegalArgumentException("distance must be finite and non-negative");
        }
        return explicitLocalTargetBinding
                && (combatShape != CombatShape.ORDINARY_MELEE
                || distance
                <= DistantThreatDisengagementPolicy.MELEE_NEAR_TERM_PRESSURE_RADIUS);
    }

    /** Only SELF pressure is eligible: Entity cannot create or prove owner-relative separation. */
    public static CombatShape scopedCombatShape(
            CombatShape observedShape,
            WorldSnapshot.ThreatTarget target) {
        Objects.requireNonNull(observedShape, "observedShape");
        Objects.requireNonNull(target, "target");
        return target == WorldSnapshot.ThreatTarget.SELF ? observedShape : CombatShape.OTHER;
    }

    public enum CombatShape {
        ORDINARY_MELEE,
        ORDINARY_RANGED,
        PLAYER,
        CREEPER,
        OTHER
    }

    public record Facts(
            long nowMillis,
            String episodeId,
            String targetId,
            CombatShape combatShape,
            boolean targetPresent,
            double distance,
            boolean trustedLocalTargetingEvidence,
            boolean paperAuthoritativeAttacker,
            boolean freshDamageEvidence,
            boolean projectilePressure,
            boolean lineOfSight,
            boolean otherEligibleThreat,
            double health,
            double maximumHealth,
            double modeledDamage,
            boolean survivalActive,
            boolean explosiveFactsKnown,
            boolean explosivePrimed,
            boolean explosiveCharged) {
        public Facts(long nowMillis, String episodeId, String targetId, CombatShape combatShape,
                boolean targetPresent, double distance, boolean trustedLocalTargetingEvidence,
                boolean paperAuthoritativeAttacker, boolean freshDamageEvidence,
                boolean projectilePressure, boolean lineOfSight, boolean otherEligibleThreat,
                double health, double maximumHealth, double modeledDamage, boolean survivalActive) {
            this(nowMillis, episodeId, targetId, combatShape, targetPresent, distance,
                    trustedLocalTargetingEvidence, paperAuthoritativeAttacker, freshDamageEvidence,
                    projectilePressure, lineOfSight, otherEligibleThreat, health, maximumHealth,
                    modeledDamage, survivalActive, false, false, false);
        }
        public Facts {
            if (nowMillis < 0L) throw new IllegalArgumentException("nowMillis must be non-negative");
            episodeId = required(episodeId, "episodeId");
            targetId = required(targetId, "targetId");
            combatShape = Objects.requireNonNull(combatShape, "combatShape");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("distance must be finite and non-negative");
            }
            if (!Double.isFinite(health) || health < 0.0
                    || !Double.isFinite(maximumHealth) || maximumHealth <= 0.0
                    || !Double.isFinite(modeledDamage) || modeledDamage < 0.0) {
                throw new IllegalArgumentException("health and damage facts are invalid");
            }
        }
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
