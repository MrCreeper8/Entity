package dev.entity.client.baritone;

import java.util.Objects;

/**
 * Stateful ownership policy for shielding while Baritone retains a ranged-threat route.
 *
 * <p>A route shield owns only the generation-fenced use channel. It never owns movement or
 * attack, and it never cancels the route. Close combat can adopt the already-held use generation
 * without a release/repress gap. Missing or cooling shields degrade to the existing route rather
 * than freezing the body.</p>
 */
public final class RangedRouteShieldPolicy {
    /** A small route-compatible correction; larger turns stay with Baritone's obstacle steering. */
    public static final double MAX_STEERING_AIM_DELTA_DEGREES = 35.0;

    private RangedRouteShieldPolicy() {
    }

    public enum Intent {
        ROUTE,
        CLOSE,
        RELEASE
    }

    public enum Mode {
        IDLE,
        ROUTE_HELD,
        CLOSE_HELD
    }

    public enum Action {
        HOLD_ROUTE_SHIELD,
        RELEASE_BEHIND_COVER,
        KEEP_UNSHIELDED_ROUTE,
        HANDOFF_TO_CLOSE,
        HOLD_CLOSE_SHIELD,
        RELEASE
    }

    public record Input(
            Intent intent,
            boolean rangedTarget,
            boolean targetAlive,
            boolean lineOfSight,
            boolean controlLeaseValid,
            boolean routeOwned,
            boolean offhandShieldReady,
            boolean closeShieldReady,
            boolean routeSteeringActive,
            double absoluteYawDeltaDegrees) {
        public Input {
            Objects.requireNonNull(intent, "intent");
            if (!Double.isFinite(absoluteYawDeltaDegrees)
                    || absoluteYawDeltaDegrees < 0.0
                    || absoluteYawDeltaDegrees > 180.0) {
                throw new IllegalArgumentException(
                        "absoluteYawDeltaDegrees must be finite and in [0,180]");
            }
        }
    }

    public record Decision(
            Action action,
            Mode mode,
            boolean holdUse,
            boolean releaseUse,
            boolean preserveRoute,
            boolean ownsMovement,
            boolean ownsAttack,
            boolean requestAim,
            String reason) {
        public Decision {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(mode, "mode");
            reason = reason == null ? "" : reason;
        }
    }

    public static final class Session {
        private Mode mode = Mode.IDLE;

        public Decision step(Input input) {
            Objects.requireNonNull(input, "input");
            Mode previous = mode;
            if (input.intent() == Intent.RELEASE
                    || !input.targetAlive()
                    || !input.controlLeaseValid()) {
                mode = Mode.IDLE;
                return release(previous,
                        !input.targetAlive() ? "ranged target is gone"
                                : !input.controlLeaseValid() ? "combat lease is no longer valid"
                                : "route shield owner released custody");
            }

            if (input.intent() == Intent.ROUTE) {
                if (!input.rangedTarget() || !input.routeOwned()) {
                    mode = Mode.IDLE;
                    return release(previous, !input.rangedTarget()
                            ? "target is not ranged"
                            : "Baritone no longer owns a route");
                }
                if (!input.lineOfSight()) {
                    // Shield movement is intentionally slow.  Once the exact live target is
                    // verified behind cover, continuing to hold use prevents the obstacle route
                    // from converging on the corner which will restore exposure.  Releasing use
                    // here keeps every movement/path channel with Baritone; the next visible
                    // heartbeat raises the same offhand shield again before exposed travel.
                    mode = Mode.IDLE;
                    return new Decision(
                            Action.RELEASE_BEHIND_COVER,
                            mode,
                            false,
                            previous != Mode.IDLE,
                            true,
                            false,
                            false,
                            false,
                            "verified cover blocks line of sight; release shield and preserve route");
                }
                if (!input.offhandShieldReady()) {
                    mode = Mode.IDLE;
                    return new Decision(
                            Action.KEEP_UNSHIELDED_ROUTE,
                            mode,
                            false,
                            previous != Mode.IDLE,
                            true,
                            false,
                            false,
                            false,
                            "ready offhand shield unavailable; keep obstacle-aware route");
                }
                mode = Mode.ROUTE_HELD;
                boolean compatibleAim = !input.routeSteeringActive()
                        || input.absoluteYawDeltaDegrees()
                        <= MAX_STEERING_AIM_DELTA_DEGREES;
                return new Decision(
                        Action.HOLD_ROUTE_SHIELD,
                        mode,
                        true,
                        false,
                        true,
                        false,
                        false,
                        compatibleAim,
                        compatibleAim
                                ? "hold offhand shield while preserving the ranged route"
                                : "hold offhand shield without overriding obstacle-route steering");
            }

            if (input.closeShieldReady()) {
                mode = Mode.CLOSE_HELD;
                return new Decision(
                        previous == Mode.ROUTE_HELD
                                ? Action.HANDOFF_TO_CLOSE
                                : Action.HOLD_CLOSE_SHIELD,
                        mode,
                        true,
                        false,
                        false,
                        true,
                        false,
                        true,
                        previous == Mode.ROUTE_HELD
                                ? "adopt held route shield in close control without a use gap"
                                : "hold shield under close-combat control");
            }

            mode = Mode.IDLE;
            return release(previous, "ready close-combat shield unavailable");
        }

        public Mode mode() {
            return mode;
        }

        /** Lifecycle cleanup after the generation-fenced actuator has been released. */
        public void reset() {
            mode = Mode.IDLE;
        }

        private static Decision release(Mode previous, String reason) {
            return new Decision(
                    Action.RELEASE,
                    Mode.IDLE,
                    false,
                    previous != Mode.IDLE,
                    false,
                    false,
                    false,
                    false,
                    reason);
        }
    }

    /** Smallest signed-independent yaw difference, suitable for policy input. */
    public static double absoluteYawDelta(float fromDegrees, float toDegrees) {
        double delta = (toDegrees - fromDegrees) % 360.0;
        if (delta > 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return Math.abs(delta);
    }
}
