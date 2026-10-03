package dev.entity.client.baritone;

/** Pure fail-closed policy for shield use before low-health movement is authorized. */
public final class SealedShieldStartPolicy {
    private SealedShieldStartPolicy() {
    }

    public static State decide(
            boolean interactionAccepted,
            boolean activelyUsingOffhandShield,
            long nowMillis,
            long fixedDeadlineMillis) {
        if (interactionAccepted && activelyUsingOffhandShield) return State.ACTIVE;
        return nowMillis > fixedDeadlineMillis ? State.FAILED : State.STARTING;
    }

    /**
     * Decides whether route movement may retain the already-started shield capability. Standard
     * combat deliberately bypasses this exceptional gate. A sealed route, regardless of target
     * kind, must still own the exact target and have an accepted, actively blocking offhand shield.
     */
    public static RouteAction routeAction(
            boolean sealedContext,
            State startState,
            boolean exactTargetOwned,
            boolean usableOffhandShield,
            boolean interactionAccepted,
            boolean activelyBlockingOffhandShield) {
        if (!sealedContext) return RouteAction.STANDARD;
        if (startState == State.ACTIVE
                && exactTargetOwned
                && usableOffhandShield
                && interactionAccepted
                && activelyBlockingOffhandShield) {
            return RouteAction.HEARTBEAT;
        }
        return RouteAction.BLOCK;
    }

    /** Sealed YIELD never inherits the ordinary water-route exception. */
    public static RouteAction yieldAction(
            boolean touchingWater,
            boolean sealedContext,
            State startState,
            boolean exactTargetOwned,
            boolean usableOffhandShield,
            boolean interactionAccepted,
            boolean activelyBlockingOffhandShield) {
        // touchingWater is intentionally not an authority input: sealed YIELD is stationary on
        // both land and water, while the standard executor retains its ordinary water route.
        return routeAction(
                sealedContext,
                startState,
                exactTargetOwned,
                usableOffhandShield,
                interactionAccepted,
                activelyBlockingOffhandShield);
    }

    /** A live sealed route is handed to Baritone once, then only shield use is heartbeated. */
    public static boolean needsRouteHandoff(boolean sealedContext, boolean routeCustodyActive) {
        return sealedContext && !routeCustodyActive;
    }

    public enum State {
        /** Shield start is still inside its fixed acknowledgement window; movement stays neutral. */
        STARTING,
        /** The server/client accepted and visibly activated the offhand shield. */
        ACTIVE,
        /** The fixed start window elapsed; this exact sealed attempt must be exhausted. */
        FAILED
    }

    public enum RouteAction {
        /** Ordinary combat route behavior, including standard melee, remains unchanged. */
        STANDARD,
        /** Preserve route movement and heartbeat the existing offhand shield capability. */
        HEARTBEAT,
        /** Cancel route movement because sealed shield authority is no longer concrete. */
        BLOCK
    }
}
