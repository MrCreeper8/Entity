package dev.entity.client.baritone;

/**
 * Prevents direct swim steering from stealing the look/body frame needed for an atomic Baritone
 * route interaction.
 *
 * <p>Fast swimming is correct through open water. It is not correct when the current route must
 * break a wall, place a support, or enter a destination whose feet/head collision is still solid.
 * In that case Baritone must retain its exact rotation and interaction inputs until the cell is
 * open; afterwards direct aquatic travel may resume.</p>
 */
public final class AquaticRouteCustodyPolicy {
    private AquaticRouteCustodyPolicy() {
    }

    /**
     * A survival preemption has already cancelled the active route before the execution kernel
     * samples its mandatory neutral frame.  That later neutralization must not erase the one-shot
     * destination snapshot: there is no active operation left to conflict with it, and the next
     * start still verifies exact goal identity, dimension, expiry, and water context.  An active
     * operation, by contrast, is an ordinary hard cancellation and invalidates any old snapshot.
     */
    public static boolean retainSuspendedSafetyRoute(
            boolean activeOperationPresent,
            boolean suspendedSafetyRoutePresent) {
        return suspendedSafetyRoutePresent && !activeOperationPresent;
    }

    /** A user-facing success statement is true only for the currently sampled swim frame. */
    public static boolean hasCurrentObservedDirectSwim(
            boolean exactCurrentFrame,
            boolean observedMotionInCustody,
            boolean ownsMovement,
            boolean swimming,
            int observedSwimTicks) {
        return exactCurrentFrame
                && observedMotionInCustody
                && ownsMovement
                && swimming
                && observedSwimTicks > 0;
    }

    /**
     * A bounded local aquatic recovery can explicitly yield its complete frame to Baritone.  The
     * otherwise-continuous water request must stay suppressed during that window, or the adapter
     * immediately cancels the route executor it just asked to recover the obstacle.
     */
    public static boolean directPursuitRequested(
            boolean physicalWaterRequest,
            AquaticTravelPolicy.Mode travelMode) {
        return physicalWaterRequest
                && travelMode != AquaticTravelPolicy.Mode.BARITONE_HANDOFF;
    }

    /**
     * A follow radius describes a valid land arrival, not an underwater arrival below the owner.
     * While Entity is physically in water and the visible target stands in a dry column, retain
     * direct shore custody. Keep that same input owner through the airborne half of a shore jump
     * or its bounded sidestep recovery; release only on a stable dry landing. Ordinary in-water
     * pursuit still releases at its normal exact-distance boundary.
     */
    public static boolean physicalPlayerPursuitRequested(
            boolean playerTarget,
            boolean touchingWater,
            boolean onGround,
            boolean targetVisible,
            boolean targetDry,
            double squaredDistance,
            double squaredStopDistance,
            AquaticTravelPolicy.Mode travelMode,
            boolean directEpisodeActive) {
        // A remembered SHORE mode is not ownership after a dry landing. Only
        // the still-active water actuator may retain its airborne shore step;
        // otherwise ordinary Baritone land jumps would resurrect swimming.
        boolean committedAirborneWaterlineStep = directEpisodeActive && committedAirborneWaterlineStep(
                touchingWater, onGround, targetDry, travelMode);
        return playerTarget
                && targetVisible
                && Double.isFinite(squaredDistance)
                && Double.isFinite(squaredStopDistance)
                && squaredDistance >= 0.0
                && squaredStopDistance >= 0.0
                && ((touchingWater
                && (targetDry || squaredDistance > squaredStopDistance))
                || committedAirborneWaterlineStep);
    }

    /**
     * Plain coordinate travel uses the same exclusive water actuator as owner pursuit. Mining,
     * hunting, and other interaction-bearing routes remain Baritone-owned because their current
     * step may need one atomic break, place, or attack frame.
     */
    public static boolean directMissionRouteRequested(
            String missionKind,
            boolean touchingWater,
            boolean routeAvailable,
            boolean outsideStopDistance,
            AquaticTravelPolicy.Mode travelMode) {
        return directMissionRouteRequested(missionKind, touchingWater, true, routeAvailable,
                outsideStopDistance, false, travelMode, false);
    }

    public static boolean directMissionRouteRequested(
            String missionKind,
            boolean touchingWater,
            boolean onGround,
            boolean routeAvailable,
            boolean outsideStopDistance,
            boolean destinationDry,
            AquaticTravelPolicy.Mode travelMode,
            boolean directEpisodeActive) {
        String kind = missionKind == null
                ? ""
                : missionKind.trim().toLowerCase(java.util.Locale.ROOT);
        return (kind.equals("goto") || kind.equals("go"))
                // A coordinate shore step has the same body contract as player pursuit.
                // The low-level SHORE/WADE actuator deliberately jumps clear of the water;
                // releasing its high-level owner there cancels it once on every buoyancy arc.
                // A remembered SHORE mode is diagnostic history after landing,
                // not permission to resurrect swimming on a later dry land jump.
                && (touchingWater || directEpisodeActive && committedAirborneWaterlineStep(
                touchingWater, onGround, destinationDry, travelMode))
                && routeAvailable
                && outsideStopDistance
                && travelMode != AquaticTravelPolicy.Mode.BARITONE_HANDOFF;
    }

    private static boolean committedAirborneWaterlineStep(
            boolean touchingWater,
            boolean onGround,
            boolean destinationDry,
            AquaticTravelPolicy.Mode travelMode) {
        return destinationDry && !touchingWater && !onGround
                && (travelMode == AquaticTravelPolicy.Mode.SHORE
                || travelMode == AquaticTravelPolicy.Mode.WADE
                || travelMode == AquaticTravelPolicy.Mode.RECOVER);
    }

    /** Baritone-owned samples still advance the bounded aquatic handoff observation. */
    public static boolean observesBaritoneHandoff(AquaticTravelPolicy.Mode travelMode) {
        return travelMode == AquaticTravelPolicy.Mode.BARITONE_HANDOFF;
    }

    /**
     * Crossing a natural shore can move the player's feet above and below the waterline while the
     * body remains in the same blocked exit column. Preserve the aquatic session across that brief
     * dry sample so its planar-stall counter can reach bounded sidestep recovery instead of being
     * reset on every buoyancy cycle. A stable dry route is still released normally and restarted by
     * the ordinary canceled-route grace window.
     */
    public static boolean retainWaterlineProgress(
            AquaticTravelPolicy.Mode travelMode,
            boolean touchingWater) {
        return !touchingWater
                && (travelMode == AquaticTravelPolicy.Mode.SHORE
                || travelMode == AquaticTravelPolicy.Mode.WADE
                || travelMode == AquaticTravelPolicy.Mode.RECOVER);
    }

    /** Reissue only the durable coordinate route whose direct actuator explicitly yielded. */
    public static boolean shouldReissueCoordinateRoute(
            String missionKind,
            boolean handoffLeaseActive,
            MovementOwner movementOwner,
            boolean baritoneBusy) {
        String kind = missionKind == null
                ? ""
                : missionKind.trim().toLowerCase(java.util.Locale.ROOT);
        return (kind.equals("goto") || kind.equals("go"))
                && handoffLeaseActive
                && movementOwner == MovementOwner.BARITONE
                && !baritoneBusy;
    }

    /** Operation-scoped one-shot gate for the route canceled by a direct aquatic handoff. */
    public static final class CoordinateRouteReissueSession {
        private boolean issued;
        private boolean directEpisode;

        public boolean admit(
                String missionKind,
                boolean handoffLeaseActive,
                MovementOwner movementOwner,
                boolean baritoneBusy,
                boolean groundedDry) {
            // An obstacle lease keeps Baritone in control while still in water.
            // Ordinary dry shore has no such lease, but its direct episode also
            // canceled the coordinate executor. Preserve that owed submission
            // through DIRECT and NEUTRAL until the exact custody owner is land.
            if (!directEpisode && !handoffLeaseActive) return false;
            // A hop above shallow water is not a dry landing. Only an explicit
            // obstacle lease may restore native routing before solid dry feet.
            if (!handoffLeaseActive && !groundedDry) return false;
            if (issued || !shouldReissueCoordinateRoute(
                    missionKind, true, movementOwner, baritoneBusy)) {
                return false;
            }
            issued = true;
            directEpisode = false;
            return true;
        }

        /** A later direct-water episode earns exactly one later handoff submission. */
        public void directAquaticStarted() {
            issued = false;
            directEpisode = true;
        }

        public boolean issued() {
            return issued;
        }
    }

    /**
     * A route handoff must survive recreation of the canceled ActiveOperation. Otherwise the new
     * operation sees water, immediately reclaims direct custody, and cancels Baritone before it can
     * route around the exact obstacle that requested the handoff.
     */
    public static final class RouteHandoffLease {
        private String missionId = "";
        private long releaseEligibleAt;
        private long expiresAt;
        private double originX = Double.NaN;
        private double originZ = Double.NaN;

        public void begin(
                String missionId,
                long nowMillis,
                long durationMillis) {
            begin(missionId, nowMillis, Double.NaN, Double.NaN,
                    durationMillis, durationMillis);
        }

        /**
         * Starts one bounded ownership window. Re-observing the same handoff must not move either
         * deadline; doing so turns a recovery lease into an accidental permanent movement ban.
         */
        public void begin(
                String missionId,
                long nowMillis,
                double originX,
                double originZ,
                long minimumMillis,
                long maximumMillis) {
            if (missionId == null || missionId.isBlank()
                    || nowMillis < 0L || minimumMillis < 0L
                    || maximumMillis <= 0L || maximumMillis < minimumMillis) {
                clear();
                return;
            }
            if (this.missionId.equals(missionId) && nowMillis < expiresAt) return;
            this.missionId = missionId;
            this.releaseEligibleAt = saturatedAdd(nowMillis, minimumMillis);
            this.expiresAt = saturatedAdd(nowMillis, maximumMillis);
            this.originX = originX;
            this.originZ = originZ;
        }

        public boolean active(
                String missionId,
                long nowMillis) {
            if (!this.missionId.equals(missionId)
                    || nowMillis < 0L || nowMillis >= expiresAt) {
                clear();
                return false;
            }
            // Do not release on the first dry displacement. At a shore lip Baritone can move the
            // body out of the original water column before its route executor has finished the
            // break/place/step that makes the land route stable. Releasing here lets direct swim
            // cancel that executor one tick after it finally starts. The short deadline is the
            // bounded owner transition; after it expires ordinary dry travel remains Baritone-
            // owned, while a genuinely unresolved water route may request direct custody again.
            return true;
        }

        /**
         * Retains Baritone long enough to commit its obstacle step, then releases as soon as the
         * body has either landed dry or materially left the failed water column. The hard deadline
         * guarantees that a failed route can never suppress direct swimming forever.
         */
        public boolean active(
                String missionId,
                long nowMillis,
                boolean touchingWater,
                boolean onGround,
                double currentX,
                double currentZ,
                double materialHorizontalDistance) {
            if (!this.missionId.equals(missionId)
                    || nowMillis < 0L || nowMillis >= expiresAt) {
                clear();
                return false;
            }
            if (nowMillis < releaseEligibleAt) return true;
            boolean stableDryLanding = !touchingWater && onGround;
            boolean displaced = Double.isFinite(originX)
                    && Double.isFinite(originZ)
                    && Double.isFinite(currentX)
                    && Double.isFinite(currentZ)
                    && Double.isFinite(materialHorizontalDistance)
                    && materialHorizontalDistance >= 0.0
                    && squaredDistance(originX, originZ, currentX, currentZ)
                    >= materialHorizontalDistance * materialHorizontalDistance;
            if (stableDryLanding || displaced) {
                clear();
                return false;
            }
            return true;
        }

        public void clear() {
            missionId = "";
            releaseEligibleAt = 0L;
            expiresAt = 0L;
            originX = Double.NaN;
            originZ = Double.NaN;
        }

        private static double squaredDistance(
                double leftX,
                double leftZ,
                double rightX,
                double rightZ) {
            double dx = rightX - leftX;
            double dz = rightZ - leftZ;
            return dx * dx + dz * dz;
        }

        private static long saturatedAdd(long left, long right) {
            if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
            return left + right;
        }
    }

    public static Decision decide(Input input) {
        if (!input.touchingWater() || !input.routeActive()) return Decision.DIRECT_AQUATIC;
        if (input.breakingBlock()
                || input.breakInput()
                || input.useInput()
                || input.destinationFeetBlocked()
                || input.destinationHeadBlocked()
                || input.diagonalCornerBlocked()) {
            return Decision.BARITONE_INTERACTION;
        }
        return Decision.DIRECT_AQUATIC;
    }

    public enum Decision {
        DIRECT_AQUATIC,
        BARITONE_INTERACTION
    }

    public record Input(
            boolean touchingWater,
            boolean routeActive,
            boolean breakingBlock,
            boolean breakInput,
            boolean useInput,
            boolean destinationFeetBlocked,
            boolean destinationHeadBlocked,
            boolean diagonalCornerBlocked) {
    }

    /**
     * One-tick-neutral ownership boundary between a visible-player swim and Baritone land
     * pursuit.  The direct frame is cancelled while {@link MovementOwner#NEUTRAL_HANDOFF} is
     * active; Baritone may only receive a newly sampled goal on a later tick after that frame is
     * observably gone.
     */
    public static final class OwnershipSession {
        private MovementOwner owner = MovementOwner.BARITONE;
        private long neutralBaselineSequence = Long.MIN_VALUE;
        private long directBaselineSequence = Long.MIN_VALUE;

        public MovementOwner step(OwnershipInput input) {
            if (input == null) {
                owner = MovementOwner.BARITONE;
                neutralBaselineSequence = Long.MIN_VALUE;
                directBaselineSequence = Long.MIN_VALUE;
                return owner;
            }
            if (owner == MovementOwner.DIRECT_AQUATIC && input.exactDirectSample()) {
                // Remember the newest frame vanilla really sampled while direct control owned the
                // body. A cleanup request must not relabel that same sequence as neutral and let
                // Baritone resume before the pending neutral frame reaches vanilla.
                directBaselineSequence = Math.max(
                        directBaselineSequence, input.sampledSequence());
            }
            owner = switch (owner) {
                case BARITONE -> {
                    if (input.directFrameActive()) yield beginNeutral(input);
                    if (!input.directAquaticRequested()) yield MovementOwner.BARITONE;
                    yield input.baritoneBusy()
                            ? MovementOwner.SUSPENDING_BARITONE
                            : beginDirect(input);
                }
                case SUSPENDING_BARITONE -> {
                    if (input.directFrameActive()) yield beginNeutral(input);
                    if (input.baritoneBusy()) yield MovementOwner.SUSPENDING_BARITONE;
                    yield input.directAquaticRequested()
                            ? beginDirect(input)
                            : MovementOwner.BARITONE;
                }
                case DIRECT_AQUATIC -> input.directAquaticRequested()
                        && !input.baritoneBusy()
                        ? MovementOwner.DIRECT_AQUATIC
                        : beginNeutral(input);
                case NEUTRAL_HANDOFF -> {
                    if (!input.newerExactNeutralThan(neutralBaselineSequence)) {
                        yield MovementOwner.NEUTRAL_HANDOFF;
                    }
                    neutralBaselineSequence = Long.MIN_VALUE;
                    if (input.baritoneBusy()) yield MovementOwner.SUSPENDING_BARITONE;
                    yield input.directAquaticRequested()
                            ? beginDirect(input)
                            : MovementOwner.BARITONE;
                }
            };
            return owner;
        }

        private MovementOwner beginNeutral(OwnershipInput input) {
            // The low-level aquatic policy can cancel its direct frame while this higher-level
            // session still says DIRECT_AQUATIC. If vanilla already sampled that resulting
            // neutral frame, it is the required handoff; no second cancellation will exist.
            if (!input.directFrameActive()
                    && input.newerExactNeutralThan(directBaselineSequence)) {
                neutralBaselineSequence = Long.MIN_VALUE;
                directBaselineSequence = Long.MIN_VALUE;
                if (input.baritoneBusy()) return MovementOwner.SUSPENDING_BARITONE;
                return input.directAquaticRequested()
                        ? beginDirect(input)
                        : MovementOwner.BARITONE;
            }
            if (owner != MovementOwner.NEUTRAL_HANDOFF) {
                neutralBaselineSequence = input.sampledSequence();
            }
            return MovementOwner.NEUTRAL_HANDOFF;
        }

        private MovementOwner beginDirect(OwnershipInput input) {
            directBaselineSequence = input.sampledSequence();
            neutralBaselineSequence = Long.MIN_VALUE;
            return MovementOwner.DIRECT_AQUATIC;
        }

        public MovementOwner owner() {
            return owner;
        }

        public boolean baritoneMayOwnMovement() {
            return owner == MovementOwner.BARITONE;
        }

        public boolean directAquaticOwnsMovement() {
            return owner == MovementOwner.DIRECT_AQUATIC;
        }
    }

    public enum MovementOwner {
        BARITONE,
        SUSPENDING_BARITONE,
        DIRECT_AQUATIC,
        NEUTRAL_HANDOFF
    }

    public record OwnershipInput(
            boolean directAquaticRequested,
            boolean directFrameActive,
            boolean baritonePathing,
            boolean baritoneCalculating,
            boolean baritoneGoalActive,
            long sampledSequence,
            boolean exactDirectSample,
            boolean exactNeutralSample) {
        public boolean baritoneBusy() {
            return baritonePathing || baritoneCalculating || baritoneGoalActive;
        }

        public boolean newerExactNeutralThan(long sequence) {
            return exactNeutralSample && sampledSequence > sequence;
        }
    }
}
