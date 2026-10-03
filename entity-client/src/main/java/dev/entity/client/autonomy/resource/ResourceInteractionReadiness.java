package dev.entity.client.autonomy.resource;

/**
 * One decision boundary between a completed resource route and the exact
 * Minecraft interaction that follows it.
 *
 * <p>A pinned harvest stance is a real precondition, not a proximity hint. Once
 * that feet cell is occupied, only the standing eye's exact target ray decides
 * whether the block can be used. Body-to-block distance is deliberately absent:
 * using it after admitting an eye-visible stance creates two conflicting reach
 * definitions and can restart an already completed route forever.</p>
 */
public final class ResourceInteractionReadiness {
    private static final double MINIMUM_RAY_LENGTH_SQUARED = 1.0E-12;

    public enum Decision {
        ROUTE_TO_PINNED_STANCE,
        INTERACT,
        RELOCATE_PINNED_STANCE,
        CONTINUE_EXCAVATION
    }

    private ResourceInteractionReadiness() {
    }

    /**
     * Admits only a body column Baritone can occupy without first modifying it.
     *
     * <p>Collision-free is not enough: crops, tall grass, and other non-air
     * blocks can have empty collision shapes while still being unsuitable as an
     * exact {@code GoalBlock}. A harvest stance is deliberately stricter than a
     * generic walkable frontier because it is pinned for the interaction
     * handoff and must be occupiable exactly as observed.</p>
     */
    public static boolean harvestStanceAdmissible(
            boolean feetCollisionClear,
            boolean headCollisionClear,
            boolean supported,
            boolean feetDry,
            boolean headDry,
            boolean feetAir,
            boolean headAir) {
        return feetCollisionClear && headCollisionClear && supported
                && feetDry && headDry && feetAir && headAir;
    }

    /**
     * Collision below the player is necessary but not sufficient for an exact
     * feet goal. Fence-, gate-, and wall-height collision can look supported to
     * a block-state probe while remaining an unusable standing cell for normal
     * pathing.
     */
    public static boolean harvestSupportAdmissible(
            boolean hasCollisionSupport,
            boolean narrowBarrierSupport) {
        return hasCollisionSupport && !narrowBarrierSupport;
    }

    /**
     * A named farm is an operation boundary as well as a mutation boundary.
     * Harvesting through a fence from a closer outside stance strands the crop
     * drop behind that fence. Keep ordinary wilderness harvest selection
     * unchanged, but require a named-farm worker's pinned feet cell to be inside
     * the exact selected footprint before any crop is broken.
     */
    public static boolean harvestApproachScopeAdmissible(
            boolean stableStance,
            boolean namedFarmBound,
            boolean insideNamedFarm) {
        return stableStance && (!namedFarmBound || insideNamedFarm);
    }

    /**
     * Exact integer offset bounds whose standing-eye height can still reach a
     * block center. This includes the cleared trunk column below a later tree
     * log; only the target cell itself is not an approach candidate.
     */
    public static ApproachSearchBounds approachSearchBounds(
            double standingEyeHeight,
            double reach) {
        if (!Double.isFinite(standingEyeHeight) || standingEyeHeight <= 0.0
                || !Double.isFinite(reach) || reach <= 0.0) {
            throw new IllegalArgumentException(
                    "standing eye height and interaction reach must be positive and finite");
        }
        int horizontalRadius = Math.max(1, (int) Math.floor(reach));
        int minimumVerticalOffset = (int) Math.ceil(
                0.5 - standingEyeHeight - reach);
        int maximumVerticalOffset = (int) Math.floor(
                0.5 - standingEyeHeight + reach);
        return new ApproachSearchBounds(
                horizontalRadius,
                minimumVerticalOffset,
                maximumVerticalOffset);
    }

    public static boolean isDistinctApproachOffset(int dx, int dy, int dz) {
        return dx != 0 || dy != 0 || dz != 0;
    }

    public static Decision decide(
            boolean hasPinnedStance,
            boolean occupiesPinnedStance,
            boolean exactTargetRay) {
        if (hasPinnedStance && !occupiesPinnedStance) {
            return Decision.ROUTE_TO_PINNED_STANCE;
        }
        if (exactTargetRay) return Decision.INTERACT;
        return hasPinnedStance
                ? Decision.RELOCATE_PINNED_STANCE
                : Decision.CONTINUE_EXCAVATION;
    }

    /** Uses the interaction eye, never the player's feet/body origin. */
    public static boolean eyeWithinReach(
            double eyeX,
            double eyeY,
            double eyeZ,
            double targetX,
            double targetY,
            double targetZ,
            double reach) {
        if (!Double.isFinite(eyeX) || !Double.isFinite(eyeY)
                || !Double.isFinite(eyeZ) || !Double.isFinite(targetX)
                || !Double.isFinite(targetY) || !Double.isFinite(targetZ)
                || !Double.isFinite(reach) || reach <= 0.0) {
            return false;
        }
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        return distanceSquared > MINIMUM_RAY_LENGTH_SQUARED
                && distanceSquared <= reach * reach;
    }

    public record ApproachSearchBounds(
            int horizontalRadius,
            int minimumVerticalOffset,
            int maximumVerticalOffset) {
        public ApproachSearchBounds {
            if (horizontalRadius < 1
                    || minimumVerticalOffset > maximumVerticalOffset) {
                throw new IllegalArgumentException("invalid harvest approach bounds");
            }
        }
    }
}
