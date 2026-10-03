package dev.entity.client.baritone;

/** Preserves the real vertical destination; travel mode owns surface-vs-underwater technique. */
public final class AquaticPursuitDepthPolicy {
    public static final double SHORE_HANDOFF_DISTANCE = 3.5;

    private AquaticPursuitDepthPolicy() {
    }

    /** Surface cruising keeps its own bounded pitch and must not falsify the durable target. */
    public static double targetY(
            double playerY,
            double targetY,
            double horizontalDistance,
            boolean targetDry) {
        if (!Double.isFinite(playerY) || !Double.isFinite(targetY)
                || !Double.isFinite(horizontalDistance) || horizontalDistance < 0.0) {
            throw new IllegalArgumentException("aquatic pursuit coordinates must be finite");
        }
        return targetY;
    }
}
