package dev.entity.client.autonomy.policy;

/**
 * Converts client-visible placement evidence into a bounded acknowledgement.
 * A locally predicted block and item decrement are not durable proof until the
 * same correlated postcondition survives a server-correction window.
 */
public final class WorkstationPlacementConfirmationPolicy {
    public static final long DEFAULT_STABILITY_MILLIS = 300L;

    public enum Action {
        WAIT_FOR_WORLD,
        WAIT_FOR_ITEM_CONSUMPTION,
        STABILIZE_CORRELATED_EVIDENCE,
        CONFIRM,
        RETRY
    }

    public static Action decide(
            boolean exactBlockPresent,
            int itemCountBefore,
            int itemCountNow,
            long elapsedMillis,
            long correlatedEvidenceAgeMillis,
            long timeoutMillis,
            long stabilityMillis) {
        if (itemCountBefore < 1 || itemCountNow < 0) {
            throw new IllegalArgumentException("placement item counts are invalid");
        }
        if (elapsedMillis < 0 || correlatedEvidenceAgeMillis < 0
                || timeoutMillis < 1 || stabilityMillis < 1
                || stabilityMillis >= timeoutMillis) {
            throw new IllegalArgumentException("placement acknowledgement timing is invalid");
        }

        boolean itemConsumed = itemCountNow < itemCountBefore;
        if (exactBlockPresent && itemConsumed
                && correlatedEvidenceAgeMillis >= stabilityMillis) {
            return Action.CONFIRM;
        }
        if (elapsedMillis >= timeoutMillis) return Action.RETRY;
        if (!exactBlockPresent) return Action.WAIT_FOR_WORLD;
        if (!itemConsumed) return Action.WAIT_FOR_ITEM_CONSUMPTION;
        return Action.STABILIZE_CORRELATED_EVIDENCE;
    }

    private WorkstationPlacementConfirmationPolicy() {
    }
}
