package dev.entity.client.baritone;

/**
 * Stateful movement ownership for ordinary mission travel through water.
 *
 * <p>Baritone still supplies the next useful world-space route point, but it does not share the
 * movement keys with this controller. While {@link Decision#ownsMovement()} is true, the adapter
 * clears Baritone's previous inputs and writes one complete aquatic command. Transitions are based
 * on the pose and displacement observed on later client ticks; requesting sprint never counts as
 * proof that sprint-swimming actually started.</p>
 */
public final class AquaticTravelPolicy {
    static final int MAX_SUBMERGE_TICKS = 30;
    static final int MAX_POSE_ACQUIRE_TICKS = 20;
    static final int STALL_TICKS = 24;
    static final int RECOVERY_TICKS = 12;
    static final int HANDOFF_TICKS = 20;
    static final int MIN_HANDOFF_TICKS = 5;
    static final int SURFACE_ASCENT_SETTLE_TICKS = 3;
    static final int SURFACE_ASCENT_PULSE_INTERVAL = 8;
    static final double OBSERVED_MOTION_EPSILON = 0.025;
    static final double ROUTE_TARGET_SHIFT_DISTANCE = 0.75;

    public enum Objective {
        ROUTE,
        SURFACE_FOR_AIR,
        SURFACE_ESCAPE
    }

    public enum Mode {
        IDLE,
        YIELD_FOR_AIR,
        SURFACE,
        BREATHE,
        ROUTE_BLOCKED_AT_AIR,
        STEP_TO_DEEP_WATER,
        REDIVE,
        SLOW_SWIM,
        SURFACE_CRUISE,
        SUBMERGE,
        ACQUIRE_SWIM,
        CRUISE,
        SHORE,
        WADE,
        RECOVER,
        BARITONE_HANDOFF
    }

    public record Input(
            boolean touchingWater,
            boolean inLava,
            boolean headSubmerged,
            boolean swimming,
            boolean deepWater,
            boolean routeActive,
            boolean directPursuit,
            boolean leaseValid,
            boolean progressExpected,
            boolean tacticalControllerActive,
            int air,
            int maximumAir,
            double x,
            double y,
            double z,
            double horizontalSpeed,
            double horizontalDistance,
            double verticalDistance,
            boolean destinationDry,
            boolean horizontalCollision,
            boolean ceilingBlocked,
            Objective objective,
            boolean resumedRoute,
            boolean sprintEligible,
            boolean supportingFloor,
            boolean verifiedDeepWaterEntry,
            AquaticRouteEnvironmentPolicy.RediveAssessment rediveAssessment) {
        /** Compatibility constructor for ordinary mission-route callers and pure harnesses. */
        public Input(
                boolean touchingWater,
                boolean inLava,
                boolean headSubmerged,
                boolean swimming,
                boolean deepWater,
                boolean routeActive,
                boolean directPursuit,
                boolean leaseValid,
                boolean progressExpected,
                boolean tacticalControllerActive,
                int air,
                int maximumAir,
                double x,
                double y,
                double z,
                double horizontalSpeed,
                double horizontalDistance,
                double verticalDistance,
                boolean destinationDry,
                boolean horizontalCollision,
                boolean ceilingBlocked) {
            this(
                    touchingWater, inLava, headSubmerged, swimming, deepWater,
                    routeActive, directPursuit, leaseValid, progressExpected,
                    tacticalControllerActive, air, maximumAir, x, y, z,
                    horizontalSpeed, horizontalDistance, verticalDistance,
                    destinationDry, horizontalCollision, ceilingBlocked,
                    Objective.ROUTE, false, true,
                    false, false,
                    AquaticRouteEnvironmentPolicy.RediveAssessment.notResuming());
        }

        /** Compatibility constructor for existing objective-aware callers. */
        public Input(
                boolean touchingWater,
                boolean inLava,
                boolean headSubmerged,
                boolean swimming,
                boolean deepWater,
                boolean routeActive,
                boolean directPursuit,
                boolean leaseValid,
                boolean progressExpected,
                boolean tacticalControllerActive,
                int air,
                int maximumAir,
                double x,
                double y,
                double z,
                double horizontalSpeed,
                double horizontalDistance,
                double verticalDistance,
                boolean destinationDry,
                boolean horizontalCollision,
                boolean ceilingBlocked,
                Objective objective,
                boolean resumedRoute,
                boolean sprintEligible) {
            this(
                    touchingWater, inLava, headSubmerged, swimming, deepWater,
                    routeActive, directPursuit, leaseValid, progressExpected,
                    tacticalControllerActive, air, maximumAir, x, y, z,
                    horizontalSpeed, horizontalDistance, verticalDistance,
                    destinationDry, horizontalCollision, ceilingBlocked,
                    objective, resumedRoute, sprintEligible,
                    false, false,
                    AquaticRouteEnvironmentPolicy.RediveAssessment.notResuming());
        }

        public Input {
            objective = objective == null ? Objective.ROUTE : objective;
            rediveAssessment = rediveAssessment == null
                    ? AquaticRouteEnvironmentPolicy.RediveAssessment.notResuming()
                    : rediveAssessment;
        }
    }

    public record Decision(
            Mode mode,
            boolean ownsMovement,
            float pitchDegrees,
            float yawOffsetDegrees,
            boolean moveForward,
            boolean jump,
            boolean sneak,
            boolean sprint,
            String reason) {
        public static Decision idle(Mode mode, String reason) {
            return new Decision(mode, false, 0.0F, 0.0F,
                    false, false, false, false, reason);
        }
    }

    public record Snapshot(
            Mode mode,
            boolean ownsMovement,
            int modeTicks,
            int stalledTicks,
            int recoveryAttempts,
            int observedSwimTicks,
            double maximumObservedHorizontalSpeed,
            String reason,
            Objective objective,
            boolean headSubmerged,
            boolean swimming,
            int air,
            int maximumAir,
            double horizontalSpeed,
            int surfaceTransitions,
            int rediveTransitions,
            int shoreTransitions,
            boolean sprintEligible) { }

    public static final class Session {
        private Mode mode = Mode.IDLE;
        private int modeTicks;
        private int stalledTicks;
        private int recoveryAttempts;
        private int observedSwimTicks;
        private double maximumObservedHorizontalSpeed;
        private boolean positionKnown;
        private double previousX;
        private double previousY;
        private double previousZ;
        private double previousDistance = Double.NaN;
        private double motionOriginX;
        private double motionOriginZ;
        private double planarProgressHighWater;
        private double bestRouteDistance = Double.POSITIVE_INFINITY;
        private double handoffX;
        private double handoffY;
        private double handoffZ;
        private Mode recoveryObjective = Mode.IDLE;
        private Objective objective = Objective.ROUTE;
        private boolean observedHeadSubmerged;
        private boolean observedSwimming;
        private int observedAir;
        private int observedMaximumAir;
        private double observedHorizontalSpeed;
        private int surfaceTransitions;
        private int rediveTransitions;
        private int shoreTransitions;
        private boolean observedSprintEligible = true;
        private Decision lastDecision = Decision.idle(Mode.IDLE, "inactive");

        public Decision step(Input input) {
            if (input != null) {
                objective = input.objective();
                observedHeadSubmerged = input.headSubmerged();
                observedSwimming = input.swimming();
                observedAir = input.air();
                observedMaximumAir = input.maximumAir();
                observedHorizontalSpeed = Math.max(0.0, finiteOrZero(input.horizontalSpeed()));
                observedSprintEligible = input.sprintEligible();
            }
            if (!eligible(input, mode)) {
                resetObservations();
                transition(Mode.IDLE);
                return remember(Decision.idle(Mode.IDLE, "aquatic route inactive"));
            }

            Motion motion = observeMotion(input);
            maximumObservedHorizontalSpeed = Math.max(
                    maximumObservedHorizontalSpeed,
                    Math.max(0.0, finiteOrZero(input.horizontalSpeed())));
            if (input.headSubmerged() && input.swimming()) observedSwimTicks++;

            if (mode == Mode.BARITONE_HANDOFF) {
                modeTicks++;
                double movedFromHandoff = distance3d(
                        input.x(), input.y(), input.z(), handoffX, handoffY, handoffZ);
                if ((modeTicks >= MIN_HANDOFF_TICKS && movedFromHandoff >= 0.40)
                        || modeTicks >= HANDOFF_TICKS) {
                    if (movedFromHandoff >= 0.40) recoveryAttempts = 0;
                    transition(selectTravelMode(input));
                } else {
                    return remember(Decision.idle(
                            Mode.BARITONE_HANDOFF,
                            "temporarily yielded keys to Baritone after an observed aquatic stall"));
                }
            }

            if (mode == Mode.RECOVER) {
                boolean recoveryProven = switch (recoveryObjective) {
                    // Sideways displacement at the surface is not evidence that a dive worked.
                    // Require the exact pose transition whose timeout started this recovery.
                    case SUBMERGE -> input.headSubmerged();
                    case ACQUIRE_SWIM -> input.headSubmerged() && input.swimming();
                    default -> motion.routeProgress();
                };
                if (recoveryProven && modeTicks >= 2) {
                    recoveryAttempts = 0;
                    stalledTicks = 0;
                    recoveryObjective = Mode.IDLE;
                    transition(selectTravelMode(input));
                } else if (modeTicks >= RECOVERY_TICKS) {
                    if (exclusiveWaterOwner(input.objective())) {
                        // Survival and protection have already cancelled the
                        // ordinary Baritone route. Yielding the only water
                        // owner here creates a deterministic drowning or
                        // combat gap. Keep an alternating local recovery frame
                        // alive; DirectBody's watchdog owns bounded failover.
                        recoveryAttempts++;
                        modeTicks = 0;
                        return remember(recoveryDecision(
                                input,
                                "surface safety retained movement ownership; "
                                        + "retrying local air-route recovery "
                                        + recoveryAttempts));
                    }
                    handoffX = input.x();
                    handoffY = input.y();
                    handoffZ = input.z();
                    transition(Mode.BARITONE_HANDOFF);
                    return remember(Decision.idle(
                            Mode.BARITONE_HANDOFF,
                            "bounded local recovery ended; Baritone owns the next route window"));
                } else {
                    modeTicks++;
                    return remember(recoveryDecision(input));
                }
            }

            Mode desired = selectTravelMode(input);
            if (desired != mode) transition(desired);
            else modeTicks++;

            boolean poseTransition = mode == Mode.SUBMERGE || mode == Mode.ACQUIRE_SWIM;
            if ((mode == Mode.BREATHE || mode == Mode.ROUTE_BLOCKED_AT_AIR)
                    && !input.headSubmerged()) {
                // A stationary waterline is the successful behavior. Air can
                // need roughly 75 ticks to refill from an emergency entry, so
                // the ordinary 24-tick displacement watchdog must not turn a
                // genuine breath hold into RECOVER/BARITONE_HANDOFF churn.
                stalledTicks = 0;
            } else if (!poseTransition) {
                if (motion.meaningful()) stalledTicks = 0;
                else stalledTicks++;
            } else if (motion.meaningful()) {
                stalledTicks = 0;
            }

            if (mode == Mode.SUBMERGE && modeTicks >= MAX_SUBMERGE_TICKS) {
                return beginRecovery(input, "head never became submerged");
            }
            if (mode == Mode.ACQUIRE_SWIM && modeTicks >= MAX_POSE_ACQUIRE_TICKS) {
                return beginRecovery(input, "vanilla never confirmed the swimming pose");
            }
            if (stalledTicks >= STALL_TICKS) {
                return beginRecovery(input, "observed body displacement stopped");
            }

            return remember(commandFor(input));
        }

        public Snapshot snapshot() {
            return new Snapshot(
                    mode,
                    lastDecision.ownsMovement(),
                    modeTicks,
                    stalledTicks,
                    recoveryAttempts,
                    observedSwimTicks,
                    maximumObservedHorizontalSpeed,
                    lastDecision.reason(),
                    objective,
                    observedHeadSubmerged,
                    observedSwimming,
                    observedAir,
                    observedMaximumAir,
                    observedHorizontalSpeed,
                    surfaceTransitions,
                    rediveTransitions,
                    shoreTransitions,
                    observedSprintEligible);
        }

        public void reset() {
            mode = Mode.IDLE;
            modeTicks = 0;
            stalledTicks = 0;
            recoveryAttempts = 0;
            observedSwimTicks = 0;
            maximumObservedHorizontalSpeed = 0.0;
            recoveryObjective = Mode.IDLE;
            objective = Objective.ROUTE;
            observedHeadSubmerged = false;
            observedSwimming = false;
            observedAir = 0;
            observedMaximumAir = 0;
            observedHorizontalSpeed = 0.0;
            observedSprintEligible = true;
            lastDecision = Decision.idle(Mode.IDLE, "inactive");
            resetObservations();
        }

        private Decision commandFor(Input input) {
            return switch (mode) {
                case SURFACE -> new Decision(
                        mode, true,
                        input.objective() == Objective.SURFACE_ESCAPE
                                ? (input.headSubmerged() ? -28.0F : -10.0F)
                                : (float) clamp(routePitch(input), -85.0, -35.0),
                        0.0F, true, true, false, input.sprintEligible(),
                        input.objective() == Objective.SURFACE_ESCAPE
                                ? "sprinting along the water surface while preserving buoyancy"
                                : "surfacing through a verified three-dimensional air route");
                case BREATHE -> new Decision(
                        mode, true, -70.0F, 0.0F,
                        false, true, false, false,
                        "holding the waterline until the air meter is restored");
                case ROUTE_BLOCKED_AT_AIR -> new Decision(
                        mode, true, -70.0F, 0.0F,
                        false, true, false, false,
                        input.rediveAssessment().reason());
                case STEP_TO_DEEP_WATER -> new Decision(
                        mode, true, 0.0F, 0.0F,
                        true, false, false, input.sprintEligible(),
                        "stepping off the supporting shelf toward a verified deep-water column before descending");
                case REDIVE -> new Decision(
                        mode, true,
                        (float) clamp(Math.max(18.0, routePitch(input)), 16.0, 42.0),
                        0.0F, true, false, true, input.sprintEligible(),
                        input.rediveAssessment().reason());
                case SLOW_SWIM -> new Decision(
                        mode, true,
                        (float) clamp(routePitch(input), -45.0, 35.0),
                        0.0F, true,
                        !input.ceilingBlocked() && input.verticalDistance() > 0.40,
                        input.verticalDistance() < -0.40 && !input.supportingFloor(),
                        false,
                        "sprint-swim unavailable at current hunger; using non-sprint 3-D swimming");
                case SURFACE_CRUISE -> {
                    boolean ascentPulse = surfaceAscentPulse(input);
                    yield new Decision(
                            mode, true,
                            input.headSubmerged() ? -8.0F : 0.0F,
                            0.0F,
                            true,
                            ascentPulse,
                            false,
                            input.sprintEligible(),
                            input.headSubmerged()
                                    ? ascentPulse
                                    ? "one-tick ascent pulse toward the breathable waterline"
                                    : "acquired sprint-swim is settling below the breathable waterline"
                                    : "acquired sprint-swim is translating level at the breathable waterline");
                }
                case SUBMERGE -> new Decision(
                        mode, true, 38.0F, 0.0F,
                        true, false, true, input.sprintEligible(),
                        "descending until head submersion is observed");
                case ACQUIRE_SWIM -> new Decision(
                        mode, true,
                        (float) clamp(Math.max(8.0, routePitch(input)), 6.0, 28.0),
                        0.0F, true, false, false, input.sprintEligible(),
                        "head submerged; waiting for vanilla to confirm sprint-swimming");
                case CRUISE -> {
                    double pitch = routePitch(input);
                    if (input.ceilingBlocked() && input.verticalDistance() > 0.0) {
                        // Do not endlessly swim upward into a solid ceiling. Keep translating
                        // toward Baritone's lateral opening; low air is handled before this point.
                        pitch = Math.max(3.0, pitch);
                    } else if (Math.abs(input.verticalDistance()) < 0.45) {
                        pitch = Math.max(2.0, pitch);
                    }
                    double minimumPitch = input.directPursuit() ? -40.0 : -30.0;
                    double maximumPitch = input.directPursuit() ? 35.0 : 20.0;
                    yield new Decision(
                            mode, true,
                            (float) clamp(pitch, minimumPitch, maximumPitch),
                            0.0F, true,
                            !input.ceilingBlocked()
                                    && (input.horizontalCollision()
                                    || input.verticalDistance() > 1.0),
                            false, input.sprintEligible(),
                            "observed sprint-swimming pose owns route translation");
                }
                case SHORE -> new Decision(
                        mode, true,
                        (float) clamp(routePitch(input), -35.0, -8.0),
                        0.0F, true, !input.ceilingBlocked(), false, input.sprintEligible(),
                        input.ceilingBlocked()
                                ? "shore is overhead-blocked; translating toward a clear exit"
                                : "jumping through the dry shore frontier");
                case WADE -> new Decision(
                        mode, true, 0.0F, 0.0F,
                        true,
                        !input.ceilingBlocked() && (input.horizontalCollision()
                                || input.verticalDistance() > 0.80
                                || (input.destinationDry() && input.verticalDistance() > -0.60)),
                        false, input.sprintEligible(),
                        "shallow water uses bounded sprint-wading");
                case RECOVER -> recoveryDecision(input);
                case IDLE, YIELD_FOR_AIR, BARITONE_HANDOFF ->
                        Decision.idle(mode, "movement is owned elsewhere");
            };
        }

        private Decision beginRecovery(Input input, String reason) {
            recoveryAttempts++;
            stalledTicks = 0;
            recoveryObjective = mode;
            transition(Mode.RECOVER);
            return remember(recoveryDecision(
                    input,
                    reason + "; starting bounded local recovery " + recoveryAttempts));
        }

        private Decision recoveryDecision(Input input) {
            return recoveryDecision(
                    input, "bounded aquatic sidestep recovery " + recoveryAttempts);
        }

        private Decision recoveryDecision(Input input, String reason) {
            boolean poseRecovery = recoveryObjective == Mode.SUBMERGE
                    || recoveryObjective == Mode.ACQUIRE_SWIM;
            boolean continueDescending = recoveryObjective == Mode.SUBMERGE
                    && !input.headSubmerged()
                    && !input.supportingFloor();
            float pitch = recoveryObjective == Mode.ACQUIRE_SWIM
                    ? (float) clamp(Math.max(8.0, routePitch(input)), 6.0, 28.0)
                    : recoveryPitch(input);
            return new Decision(
                    Mode.RECOVER, true,
                    pitch, recoveryYawOffset(),
                    true,
                    !poseRecovery && !input.ceilingBlocked(),
                    continueDescending,
                    input.sprintEligible(),
                    reason);
        }

        private float recoveryPitch(Input input) {
            if (input.objective() == Objective.SURFACE_ESCAPE) return -25.0F;
            if (!input.headSubmerged() && input.deepWater()) return 28.0F;
            if (input.ceilingBlocked()) return 8.0F;
            return (float) clamp(routePitch(input), -18.0, 22.0);
        }

        private float recoveryYawOffset() {
            return recoveryAttempts % 2 == 0 ? -55.0F : 55.0F;
        }

        /**
         * Vanilla needs a submerged sprint-swim before it can retain that pose at the surface.
         * A held jump accumulates upward velocity until the body leaves the fluid and loses the
         * pose; pitch alone can settle a fraction of a block below breathing height.  Wait for
         * several observed swim ticks, then add one isolated buoyancy input at a bounded cadence.
         * The first breathable sample disables the pulse immediately while forward sprint keeps
         * the already-acquired swimming pose.
         */
        private boolean surfaceAscentPulse(Input input) {
            if (!input.headSubmerged() || input.ceilingBlocked()
                    || observedSwimTicks < SURFACE_ASCENT_SETTLE_TICKS + 1
                    || modeTicks < SURFACE_ASCENT_SETTLE_TICKS) {
                return false;
            }
            return (modeTicks - SURFACE_ASCENT_SETTLE_TICKS)
                    % SURFACE_ASCENT_PULSE_INTERVAL == 0;
        }

        private Mode selectTravelMode(Input input) {
            if (input.objective() == Objective.SURFACE_FOR_AIR) {
                return input.headSubmerged() ? Mode.SURFACE : Mode.BREATHE;
            }
            if (input.objective() == Objective.SURFACE_ESCAPE) {
                // Escape is committed to a reachable exit, not acquisition of a swimming pose.
                // A shelf beside shore must never turn into a search for deeper water.
                return input.destinationDry() ? Mode.SHORE : Mode.SURFACE;
            }
            if (input.destinationDry()
                    && (mode == Mode.SHORE
                    || input.horizontalDistance() <= 3.5
                    || input.horizontalCollision())) {
                // A natural shore is usually a staircase of submerged block faces. Jumping over
                // one face clears horizontalCollision for a few ticks before the next face, but
                // that is still one continuous water-to-land handoff. Retain SHORE until the dry
                // destination changes, recovery takes over, or leaving water resets the session;
                // otherwise CRUISE/SHORE toggles repeatedly on an entirely healthy ascent.
                return Mode.SHORE;
            }
            if (input.destinationDry()) {
                // Vanilla cannot start sprint-swimming from a standing waterline. A player first
                // enters a two-block-deep column, becomes head-submerged, and lets vanilla confirm
                // the swimming pose. Only that confirmed pose may rise to and persist along the
                // breathable surface. Holding jump after acquisition launches the body completely
                // out of the fluid, which stops sprinting and creates the observed jump/bob loop.
                if (input.swimming()) return Mode.SURFACE_CRUISE;
                if (input.headSubmerged()) {
                    return input.sprintEligible() ? Mode.ACQUIRE_SWIM : Mode.SLOW_SWIM;
                }
                if (input.supportingFloor()) {
                    return input.verifiedDeepWaterEntry()
                            ? Mode.STEP_TO_DEEP_WATER
                            : Mode.WADE;
                }
                return input.deepWater() ? Mode.SUBMERGE : Mode.WADE;
            }
            if (input.headSubmerged()) {
                if (!input.sprintEligible()) return Mode.SLOW_SWIM;
                return input.swimming() ? Mode.CRUISE : Mode.ACQUIRE_SWIM;
            }
            if (input.resumedRoute()) {
                if (!input.rediveAssessment().permitsRedive()) {
                    return input.rediveAssessment().permission()
                            == AquaticRouteEnvironmentPolicy.RedivePermission.WAIT_FOR_AIR
                            ? Mode.BREATHE
                            : Mode.ROUTE_BLOCKED_AT_AIR;
                }
                if (input.supportingFloor()) {
                    return input.verifiedDeepWaterEntry()
                            ? Mode.STEP_TO_DEEP_WATER
                            : Mode.ROUTE_BLOCKED_AT_AIR;
                }
                // A committed path is stronger evidence than the current feet/down sample. At a
                // waterline that sample can alternate as Y crosses an integer boundary, even
                // though the exact adjacent descent corridor remains open. Keep the acknowledged
                // redive bound to that corridor; the movement watchdog remains the bounded
                // failure path if observed descent/translation never follows.
                return Mode.REDIVE;
            }
            if (input.supportingFloor()) {
                return input.verifiedDeepWaterEntry()
                        ? Mode.STEP_TO_DEEP_WATER
                        : Mode.WADE;
            }
            return input.deepWater() ? Mode.SUBMERGE : Mode.WADE;
        }

        private Motion observeMotion(Input input) {
            double routeDistance = Math.hypot(
                    input.horizontalDistance(), input.verticalDistance());
            if (!positionKnown) {
                positionKnown = true;
                previousX = input.x();
                previousY = input.y();
                previousZ = input.z();
                previousDistance = routeDistance;
                motionOriginX = input.x();
                motionOriginZ = input.z();
                planarProgressHighWater = 0.0;
                bestRouteDistance = routeDistance;
                return new Motion(false, false);
            }
            double displacement = distance3d(
                    input.x(), input.y(), input.z(), previousX, previousY, previousZ);
            boolean targetShifted = Double.isFinite(previousDistance)
                    && routeDistance - previousDistance
                    >= Math.max(ROUTE_TARGET_SHIFT_DISTANCE, displacement + 0.50);
            if (targetShifted) {
                motionOriginX = input.x();
                motionOriginZ = input.z();
                planarProgressHighWater = 0.0;
                bestRouteDistance = routeDistance;
            }
            double planarRadius = Math.hypot(
                    input.x() - motionOriginX, input.z() - motionOriginZ);
            boolean expandedEnvelope = planarRadius - planarProgressHighWater
                    >= OBSERVED_MOTION_EPSILON;
            boolean routeProgress = bestRouteDistance - routeDistance
                    >= OBSERVED_MOTION_EPSILON;
            if (expandedEnvelope) planarProgressHighWater = planarRadius;
            if (routeProgress) bestRouteDistance = routeDistance;
            previousX = input.x();
            previousY = input.y();
            previousZ = input.z();
            previousDistance = routeDistance;
            // Velocity into a wall can remain non-zero even though the server-observed body never
            // changes position. Likewise, A/B bobbing cannot perpetually renew progress: only a
            // new planar episode high-water or real 3-D route-distance reduction does so.
            return new Motion(targetShifted || expandedEnvelope || routeProgress, routeProgress);
        }

        private Decision remember(Decision decision) {
            lastDecision = decision;
            return decision;
        }

        private void transition(Mode next) {
            if (mode != next) {
                mode = next;
                modeTicks = 0;
                if (next == Mode.SURFACE) surfaceTransitions++;
                if (next == Mode.REDIVE) rediveTransitions++;
                if (next == Mode.SHORE) shoreTransitions++;
            }
        }

        private void resetObservations() {
            positionKnown = false;
            previousDistance = Double.NaN;
            motionOriginX = 0.0;
            motionOriginZ = 0.0;
            planarProgressHighWater = 0.0;
            bestRouteDistance = Double.POSITIVE_INFINITY;
        }
    }

    private record Motion(boolean meaningful, boolean routeProgress) { }

    private static boolean eligible(Input input, Mode currentMode) {
        boolean committedAirborneWaterlineStep = input != null
                && (currentMode == Mode.SHORE
                || currentMode == Mode.WADE
                || currentMode == Mode.RECOVER)
                && input.destinationDry()
                && !input.touchingWater()
                && !input.supportingFloor();
        return input != null
                && (input.touchingWater() || committedAirborneWaterlineStep)
                && !input.inLava()
                && input.routeActive()
                && input.leaseValid()
                && (input.progressExpected() || input.directPursuit())
                && !input.tacticalControllerActive()
                && Double.isFinite(input.x())
                && Double.isFinite(input.y())
                && Double.isFinite(input.z())
                && Double.isFinite(input.horizontalDistance())
                && Double.isFinite(input.verticalDistance())
                && (exclusiveWaterOwner(input.objective()) || !arrived(input));
    }

    private static boolean exclusiveWaterOwner(Objective objective) {
        return objective == Objective.SURFACE_FOR_AIR
                || objective == Objective.SURFACE_ESCAPE;
    }

    private static double routePitch(Input input) {
        return -Math.toDegrees(Math.atan2(
                input.verticalDistance(), Math.max(0.001, input.horizontalDistance())));
    }

    private static boolean arrived(Input input) {
        if (input.directPursuit() && !input.destinationDry()) {
            return Math.hypot(input.horizontalDistance(), input.verticalDistance()) <= 1.35;
        }
        return input.horizontalDistance() < 0.30
                && Math.abs(input.verticalDistance()) < 0.45;
    }

    private static double distance3d(
            double x1, double y1, double z1,
            double x2, double y2, double z2) {
        return Math.sqrt(
                square(x1 - x2) + square(y1 - y2) + square(z1 - z2));
    }

    private static double square(double value) {
        return value * value;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private AquaticTravelPolicy() { }
}
