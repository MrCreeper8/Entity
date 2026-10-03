package dev.entity.client.baritone;

/**
 * Pins a moving player's route to a useful snapshot long enough for the current movement (most
 * importantly an in-progress block break) to finish. The live target may keep moving, but it does
 * not get to invalidate the body transaction every client tick.
 */
final class VisiblePlayerPursuitPolicy {
    static final long MIN_COMMITMENT_MILLIS = 3_000;
    static final long RESTART_COOLDOWN_MILLIS = 500;
    static final double ORDINARY_RETARGET_DISTANCE = 5.0;
    static final double URGENT_RETARGET_DISTANCE = 12.0;

    enum RouteState { CALCULATING, MOVING, INACTIVE }

    enum Action { KEEP_COMMITTED, COMMIT_SNAPSHOT, HOLD_POSITION }

    enum Reason {
        INITIAL_SNAPSHOT,
        ACTIVE_BLOCK_BREAK,
        COMMITMENT_WINDOW,
        TARGET_MOVED,
        TARGET_MOVED_FAR,
        ROUTE_ENDED,
        RESTART_COOLDOWN,
        BARITONE_GOAL_SATISFIED,
        INSIDE_FOLLOW_RADIUS
    }

    record Target(double x, double y, double z) { }

    record Decision(Action action, Reason reason) { }

    static final class Session {
        private Target committedTarget;
        private long committedAt;
        private boolean routeCommitted;

        Decision evaluate(
                Target target,
                double distanceToLiveTarget,
                double followRadius,
                RouteState routeState,
                boolean breakingBlock,
                long nowMillis) {
            return evaluate(
                    target,
                    distanceToLiveTarget,
                    followRadius,
                    false,
                    true,
                    routeState,
                    breakingBlock,
                    nowMillis);
        }

        Decision evaluate(
                Target target,
                double distanceToLiveTarget,
                double followRadius,
                boolean baritoneGoalSatisfied,
                RouteState routeState,
                boolean breakingBlock,
                long nowMillis) {
            return evaluate(
                    target,
                    distanceToLiveTarget,
                    followRadius,
                    baritoneGoalSatisfied,
                    true,
                    routeState,
                    breakingBlock,
                    nowMillis);
        }

        Decision evaluate(
                Target target,
                double distanceToLiveTarget,
                double followRadius,
                boolean baritoneGoalSatisfied,
                boolean arrivalPermitted,
                RouteState routeState,
                boolean breakingBlock,
                long nowMillis) {
            if (target == null || !finite(target) || !Double.isFinite(distanceToLiveTarget)) {
                return new Decision(Action.HOLD_POSITION, Reason.INSIDE_FOLLOW_RADIUS);
            }
            // A partially broken block is an atomic movement transaction. Never replace its goal
            // because the followed player twitched, returned, or crossed a retarget threshold.
            if (routeCommitted && committedTarget != null && breakingBlock) {
                return new Decision(Action.KEEP_COMMITTED, Reason.ACTIVE_BLOCK_BREAK);
            }

            double radius = Math.max(1.0, followRadius);
            if (arrivalPermitted && baritoneGoalSatisfied) {
                return new Decision(Action.HOLD_POSITION, Reason.BARITONE_GOAL_SATISFIED);
            }
            if (arrivalPermitted && distanceToLiveTarget <= radius) {
                return new Decision(Action.HOLD_POSITION, Reason.INSIDE_FOLLOW_RADIUS);
            }
            if (!routeCommitted || committedTarget == null) {
                return new Decision(Action.COMMIT_SNAPSHOT, Reason.INITIAL_SNAPSHOT);
            }

            long age = Math.max(0, nowMillis - committedAt);
            double movedSquared = distanceSquared(committedTarget, target);
            if (movedSquared >= URGENT_RETARGET_DISTANCE * URGENT_RETARGET_DISTANCE
                    && age >= RESTART_COOLDOWN_MILLIS) {
                return new Decision(Action.COMMIT_SNAPSHOT, Reason.TARGET_MOVED_FAR);
            }
            if (routeState != RouteState.INACTIVE && age < MIN_COMMITMENT_MILLIS) {
                return new Decision(Action.KEEP_COMMITTED, Reason.COMMITMENT_WINDOW);
            }
            if (movedSquared >= ORDINARY_RETARGET_DISTANCE * ORDINARY_RETARGET_DISTANCE) {
                return new Decision(Action.COMMIT_SNAPSHOT, Reason.TARGET_MOVED);
            }
            if (routeState == RouteState.INACTIVE) {
                if (age < RESTART_COOLDOWN_MILLIS) {
                    return new Decision(Action.KEEP_COMMITTED, Reason.RESTART_COOLDOWN);
                }
                return new Decision(Action.COMMIT_SNAPSHOT, Reason.ROUTE_ENDED);
            }
            return new Decision(Action.KEEP_COMMITTED, Reason.COMMITMENT_WINDOW);
        }

        void committed(Target target, long nowMillis) {
            committedTarget = target;
            committedAt = nowMillis;
            routeCommitted = true;
        }

        void held() {
            routeCommitted = false;
            committedTarget = null;
        }

        void reset() {
            held();
            committedAt = 0;
        }

        private static boolean finite(Target target) {
            return Double.isFinite(target.x())
                    && Double.isFinite(target.y())
                    && Double.isFinite(target.z());
        }

        private static double distanceSquared(Target first, Target second) {
            double dx = first.x() - second.x();
            double dy = first.y() - second.y();
            double dz = first.z() - second.z();
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private VisiblePlayerPursuitPolicy() { }
}
