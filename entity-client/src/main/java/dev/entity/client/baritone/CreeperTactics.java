package dev.entity.client.baritone;

/** Deterministic melee spacing for creepers; independent of Minecraft classes for testing. */
public final class CreeperTactics {
    public static final double NORMAL_SAFE_DISTANCE = 6.5;
    public static final double CHARGED_SAFE_DISTANCE = 9.0;
    public static final double ATTACK_DISTANCE = 3.2;
    private static final float SHIELD_FUSE_PROGRESS = 0.68F;

    private CreeperTactics() {
    }

    public static Decision decide(
            double distance,
            boolean fuseActive,
            float fuseProgress,
            boolean charged,
            boolean cooldownReady,
            boolean retreating,
            boolean shieldAvailable) {
        double safeDistance = charged ? CHARGED_SAFE_DISTANCE : NORMAL_SAFE_DISTANCE;
        if (fuseActive && shieldAvailable
                && fuseProgress >= SHIELD_FUSE_PROGRESS && distance < safeDistance) {
            return new Decision(Action.SHIELD, safeDistance, "blocking a primed creeper");
        }
        if (fuseActive || (retreating && distance < safeDistance)) {
            return new Decision(Action.RETREAT, safeDistance,
                    fuseActive ? "retreating from a primed creeper" : "creating space after striking creeper");
        }
        if (retreating) {
            return new Decision(Action.APPROACH, safeDistance, "safe distance reached; re-engaging creeper");
        }
        if (distance <= ATTACK_DISTANCE && cooldownReady) {
            return new Decision(Action.STRIKE_AND_RETREAT, safeDistance, "striking once, then retreating");
        }
        return new Decision(Action.APPROACH, safeDistance, "approaching an unprimed creeper");
    }

    public enum Action {
        APPROACH,
        STRIKE_AND_RETREAT,
        RETREAT,
        SHIELD
    }

    public record Decision(Action action, double safeDistance, String detail) {
    }
}
