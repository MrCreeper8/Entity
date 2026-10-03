package dev.entity.client.baritone;

/**
 * Decides when a server-supplied player waypoint deserves a new Baritone calculation.
 *
 * <p>A calculation is not movement and may legitimately take longer than the movement stall
 * window. Inactive starts, on the other hand, are retried with bounded exponential backoff so a
 * dead custom-goal process cannot be recreated every tick forever.</p>
 */
final class WaypointReplanPolicy {
    static final double TARGET_HORIZONTAL_MOVEMENT_SQUARED = 8.0 * 8.0;
    static final double TARGET_VERTICAL_MOVEMENT = 4.0;
    static final double PROGRESS_DISTANCE = 1.5;
    static final long TARGET_REPLAN_COOLDOWN_MILLIS = 3_000;
    static final long BASE_INACTIVE_BACKOFF_MILLIS = 2_000;
    static final long MAX_INACTIVE_BACKOFF_MILLIS = 16_000;
    static final int MAX_INACTIVE_FAILURES = 4;
    static final int MAX_NO_PROGRESS_REPLANS = 2;
    static final long STALL_REPLAN_COOLDOWN_MILLIS = 5_000;
    static final long NO_PROGRESS_MILLIS = 15_000;

    enum RouteState {
        CALCULATING,
        MOVING,
        INACTIVE
    }

    enum Reason {
        KEEP_ROUTE,
        CALCULATION_IN_PROGRESS,
        INITIAL_ROUTE,
        TARGET_MOVED,
        INACTIVE_BACKOFF,
        ROUTE_INACTIVE,
        NO_PROGRESS,
        ROUTE_EXHAUSTED
    }

    record Target(double x, double y, double z) {
    }

    record Decision(
            boolean replan,
            boolean exhausted,
            Reason reason,
            long retryAfterMillis,
            int failedAttempts) {
        private static Decision keep(Reason reason, long retryAfterMillis, int failedAttempts) {
            return new Decision(false, false, reason,
                    Math.max(0, retryAfterMillis), failedAttempts);
        }

        private static Decision replan(Reason reason, int failedAttempts) {
            return new Decision(true, false, reason, 0, failedAttempts);
        }

        private static Decision exhausted(int failedAttempts) {
            return new Decision(false, true, Reason.ROUTE_EXHAUSTED, 0, failedAttempts);
        }
    }

    static final class Session {
        private boolean routed;
        private Target routedTarget;
        private double bestDistance = Double.POSITIVE_INFINITY;
        private long lastProgressAt;
        private long lastReplanAt;
        private int inactiveFailures;
        private int noProgressReplans;
        private boolean inactiveRecordedForAttempt;
        private long inactiveRetryAt;

        Decision evaluate(
                Target target,
                double distance,
                RouteState routeState,
                long nowMillis) {
            if (!routed || routedTarget == null) {
                return Decision.replan(Reason.INITIAL_ROUTE, 0);
            }

            boolean targetMoved = materiallyMoved(routedTarget, target);
            if (targetMoved
                    && nowMillis - lastReplanAt >= TARGET_REPLAN_COOLDOWN_MILLIS) {
                clearFailureHistory();
                return Decision.replan(Reason.TARGET_MOVED, 0);
            }

            if (routeState == RouteState.CALCULATING) {
                // A long A* calculation has no body-distance progress by definition. Preserve it.
                return Decision.keep(Reason.CALCULATION_IN_PROGRESS, 0, inactiveFailures);
            }

            if (routeState == RouteState.MOVING) {
                inactiveFailures = 0;
                inactiveRetryAt = 0;
                inactiveRecordedForAttempt = false;
                if (madeProgress(distance)) {
                    bestDistance = distance;
                    lastProgressAt = nowMillis;
                    noProgressReplans = 0;
                }
                if (nowMillis - lastProgressAt >= NO_PROGRESS_MILLIS
                        && nowMillis - lastReplanAt >= STALL_REPLAN_COOLDOWN_MILLIS) {
                    if (noProgressReplans >= MAX_NO_PROGRESS_REPLANS) {
                        return Decision.exhausted(noProgressReplans + 1);
                    }
                    noProgressReplans++;
                    return Decision.replan(Reason.NO_PROGRESS, noProgressReplans);
                }
                return Decision.keep(Reason.KEEP_ROUTE, 0, 0);
            }

            if (!inactiveRecordedForAttempt) {
                inactiveRecordedForAttempt = true;
                inactiveFailures++;
                inactiveRetryAt = nowMillis + inactiveBackoff(inactiveFailures);
            }
            if (inactiveFailures >= MAX_INACTIVE_FAILURES) {
                return Decision.exhausted(inactiveFailures);
            }
            if (nowMillis >= inactiveRetryAt) {
                return Decision.replan(Reason.ROUTE_INACTIVE, inactiveFailures);
            }
            return Decision.keep(
                    Reason.INACTIVE_BACKOFF,
                    inactiveRetryAt - nowMillis,
                    inactiveFailures);
        }

        void replanned(Target target, double distance, long nowMillis) {
            if (routedTarget != null && materiallyMoved(routedTarget, target)) {
                clearFailureHistory();
            }
            routed = true;
            routedTarget = target;
            bestDistance = finiteDistance(distance);
            lastProgressAt = nowMillis;
            lastReplanAt = nowMillis;
            inactiveRecordedForAttempt = false;
            inactiveRetryAt = 0;
        }

        void reset() {
            routed = false;
            routedTarget = null;
            bestDistance = Double.POSITIVE_INFINITY;
            lastProgressAt = 0;
            lastReplanAt = 0;
            clearFailureHistory();
        }

        private void clearFailureHistory() {
            inactiveFailures = 0;
            noProgressReplans = 0;
            inactiveRecordedForAttempt = false;
            inactiveRetryAt = 0;
        }

        private boolean madeProgress(double distance) {
            return Double.isFinite(distance)
                    && (!Double.isFinite(bestDistance)
                    || distance <= bestDistance - PROGRESS_DISTANCE);
        }

        private static boolean materiallyMoved(Target from, Target to) {
            double dx = to.x() - from.x();
            double dz = to.z() - from.z();
            return dx * dx + dz * dz >= TARGET_HORIZONTAL_MOVEMENT_SQUARED
                    || Math.abs(to.y() - from.y()) >= TARGET_VERTICAL_MOVEMENT;
        }

        private static long inactiveBackoff(int failure) {
            int shift = Math.max(0, Math.min(20, failure - 1));
            return Math.min(MAX_INACTIVE_BACKOFF_MILLIS,
                    BASE_INACTIVE_BACKOFF_MILLIS << shift);
        }

        private static double finiteDistance(double distance) {
            return Double.isFinite(distance) ? Math.max(0.0, distance) : Double.POSITIVE_INFINITY;
        }
    }
}
