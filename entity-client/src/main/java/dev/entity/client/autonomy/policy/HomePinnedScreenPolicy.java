package dev.entity.client.autonomy.policy;

/**
 * Pure acknowledgement gate for an Entity-owned Home workstation screen.
 * A matching handler type is not identity proof: another chest can expose the
 * same handler while the pinned block remains valid in the world.  The first
 * accepted handler must therefore be a new sync ID produced after this
 * session's exact block interaction; every later tick must retain that ID.
 */
public final class HomePinnedScreenPolicy {
    public enum Verdict {
        ACCEPT_BOUND,
        CAPTURE_INTERACTION_RESULT,
        REJECT
    }

    private HomePinnedScreenPolicy() {
    }

    public static Verdict evaluate(
            boolean pinnedTargetVerified,
            boolean handlerKindMatches,
            int acknowledgedSyncId,
            int currentSyncId,
            boolean exactInteractionPending,
            int interactionSourceSyncId,
            boolean withinConfirmationWindow) {
        if (acknowledgedSyncId < -1 || currentSyncId < -1
                || interactionSourceSyncId < -1) {
            throw new IllegalArgumentException("screen sync IDs must be -1 or non-negative");
        }
        if (!pinnedTargetVerified || !handlerKindMatches || currentSyncId < 0) {
            return Verdict.REJECT;
        }
        if (acknowledgedSyncId >= 0) {
            return currentSyncId == acknowledgedSyncId
                    ? Verdict.ACCEPT_BOUND
                    : Verdict.REJECT;
        }
        if (exactInteractionPending
                && withinConfirmationWindow
                && interactionSourceSyncId >= 0
                && currentSyncId != interactionSourceSyncId) {
            return Verdict.CAPTURE_INTERACTION_RESULT;
        }
        return Verdict.REJECT;
    }
}
