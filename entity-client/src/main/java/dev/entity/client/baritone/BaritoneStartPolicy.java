package dev.entity.client.baritone;

/** Admission policy for idempotent adapter starts across short-lived control-lease refreshes. */
final class BaritoneStartPolicy {
    enum Decision {
        START,
        REFRESH_ACTIVE,
        IGNORE_STALE_DUPLICATE,
        REJECT_STALE
    }

    static Decision decide(
            boolean requestedLeaseValid,
            boolean sameOperation,
            boolean sameEpoch,
            boolean sameOwner,
            boolean activeLeaseValid) {
        if (requestedLeaseValid) {
            return sameOperation && sameEpoch && sameOwner
                    ? Decision.REFRESH_ACTIVE
                    : Decision.START;
        }
        if (sameOperation && sameEpoch && sameOwner && activeLeaseValid) {
            // A late tick may still carry the owner's previous token after a newer same-epoch
            // token already refreshed the exact operation. The desired route is already alive;
            // ignoring that duplicate is safer than tearing it down or surfacing an exception.
            return Decision.IGNORE_STALE_DUPLICATE;
        }
        return Decision.REJECT_STALE;
    }

    /**
     * A primary {@code CANCELED} event is sometimes followed immediately by a
     * replacement calculation. Give that handoff a short grace period, but do
     * not keep refreshing an unfinished operation that has neither a path nor
     * a calculation after the grace expires.
     */
    static boolean canceledRouteRequiresRestart(
            boolean gotoOperation,
            boolean playerContextReady,
            boolean canceled,
            boolean goalSatisfied,
            boolean pathing,
            boolean calculationInProgress,
            long canceledForMillis,
            long restartGraceMillis) {
        if (canceledForMillis < 0L || restartGraceMillis < 0L) {
            throw new IllegalArgumentException("route cancellation times cannot be negative");
        }
        return gotoOperation
                && playerContextReady
                && canceled
                && !goalSatisfied
                && !pathing
                && !calculationInProgress
                && canceledForMillis >= restartGraceMillis;
    }

    private BaritoneStartPolicy() {
    }
}
