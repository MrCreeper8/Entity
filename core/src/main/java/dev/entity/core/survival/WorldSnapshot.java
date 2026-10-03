package dev.entity.core.survival;

import java.util.List;
import java.util.Objects;

/** Version-independent facts sampled from the Minecraft client. */
public record WorldSnapshot(
        long nowMillis,
        double health,
        double maximumHealth,
        int remainingAir,
        int maximumAir,
        int foodLevel,
        boolean consumableFoodAvailable,
        boolean inWater,
        boolean headSubmerged,
        boolean inLava,
        boolean onFire,
        boolean insideWall,
        double fallDistance,
        boolean onGround,
        boolean waterBucketAvailable,
        boolean fireResistanceActive,
        int estimatedAirTicksToBreathableSpace,
        List<Threat> threats) {

    public WorldSnapshot {
        if (maximumHealth <= 0 || maximumAir <= 0 || foodLevel < 0 || foodLevel > 20) {
            throw new IllegalArgumentException("Invalid health or air maximum");
        }
        if (estimatedAirTicksToBreathableSpace < -1) {
            throw new IllegalArgumentException("breathable route estimate cannot be below -1");
        }
        threats = List.copyOf(Objects.requireNonNull(threats, "threats"));
    }

    /** Compatibility constructor for ports/tests created before suffocation was observable. */
    public WorldSnapshot(
            long nowMillis,
            double health,
            double maximumHealth,
            int remainingAir,
            int maximumAir,
            int foodLevel,
            boolean consumableFoodAvailable,
            boolean inWater,
            boolean headSubmerged,
            boolean inLava,
            boolean onFire,
            double fallDistance,
            boolean onGround,
            boolean waterBucketAvailable,
            boolean fireResistanceActive,
            int estimatedAirTicksToBreathableSpace,
            List<Threat> threats) {
        this(nowMillis, health, maximumHealth, remainingAir, maximumAir, foodLevel,
                consumableFoodAvailable, inWater, headSubmerged, inLava, onFire, false,
                fallDistance, onGround, waterBucketAvailable, fireResistanceActive,
                estimatedAirTicksToBreathableSpace, threats);
    }

    /** Compatibility constructor for ports/tests that do not yet know a verified air route. */
    public WorldSnapshot(
            long nowMillis,
            double health,
            double maximumHealth,
            int remainingAir,
            int maximumAir,
            int foodLevel,
            boolean consumableFoodAvailable,
            boolean inWater,
            boolean headSubmerged,
            boolean inLava,
            boolean onFire,
            double fallDistance,
            boolean onGround,
            boolean waterBucketAvailable,
            boolean fireResistanceActive,
            List<Threat> threats) {
        this(nowMillis, health, maximumHealth, remainingAir, maximumAir, foodLevel,
                consumableFoodAvailable, inWater, headSubmerged, inLava, onFire, false, fallDistance,
                onGround, waterBucketAvailable, fireResistanceActive, -1, threats);
    }

    /** Convenience constructor for callers that can observe suffocation but not an air route. */
    public WorldSnapshot(
            long nowMillis,
            double health,
            double maximumHealth,
            int remainingAir,
            int maximumAir,
            int foodLevel,
            boolean consumableFoodAvailable,
            boolean inWater,
            boolean headSubmerged,
            boolean inLava,
            boolean onFire,
            boolean insideWall,
            double fallDistance,
            boolean onGround,
            boolean waterBucketAvailable,
            boolean fireResistanceActive,
            List<Threat> threats) {
        this(nowMillis, health, maximumHealth, remainingAir, maximumAir, foodLevel,
                consumableFoodAvailable, inWater, headSubmerged, inLava, onFire, insideWall,
                fallDistance, onGround, waterBucketAvailable, fireResistanceActive, -1, threats);
    }

    public static WorldSnapshot safe(long nowMillis) {
        return new WorldSnapshot(
                nowMillis, 20, 20, 300, 300, 20, false,
                false, false, false, false, false, 0, true,
                false, false, 0, List.of());
    }

    public enum ThreatTarget {
        SELF,
        OWNER,
        OTHER
    }

    public record Threat(
            String entityId,
            String entityType,
            ThreatTarget target,
            double distance,
            double estimatedDamage,
            boolean activelyAttacking) {
        public Threat {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(target, "target");
        }
    }
}
