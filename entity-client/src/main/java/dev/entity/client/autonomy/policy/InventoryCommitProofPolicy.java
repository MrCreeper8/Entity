package dev.entity.client.autonomy.policy;

/**
 * Prevents a vanilla inventory update from outracing Paper's durable delivery proof.
 * Inventory loss alone is not permission to retire a nonce once a commit was sent.
 */
public final class InventoryCommitProofPolicy {
    public static final long PROOF_WINDOW_MILLIS = 12_000L;

    private InventoryCommitProofPolicy() {
    }

    public enum Decision {
        WAIT_FOR_DURABLE_PROOF,
        RECONCILE_MISSING_CARGO
    }

    public static Decision inventoryDeficit(
            long nowMillis,
            long lastCommitSentAt,
            int checkpointConfirmedCount,
            boolean resultPresent,
            boolean resultAccepted,
            int resultConfirmedCount) {
        int checkpoint = Math.max(0, checkpointConfirmedCount);
        int serverConfirmed = Math.max(0, resultConfirmedCount);

        // Any newer cumulative server count proves that Paper advanced this exact
        // nonce. Only the durable receipt may account that progress locally.
        if (resultPresent && serverConfirmed > checkpoint) {
            return Decision.WAIT_FOR_DURABLE_PROOF;
        }
        if (lastCommitSentAt <= 0L) {
            return Decision.RECONCILE_MISSING_CARGO;
        }

        // A no-progress rejection proves this commit attempt did not remove the
        // missing cargo. It is safe to retire/reacquire immediately.
        if (resultPresent && !resultAccepted) {
            return Decision.RECONCILE_MISSING_CARGO;
        }

        long age = Math.max(0L, nowMillis - lastCommitSentAt);
        return age <= PROOF_WINDOW_MILLIS
                ? Decision.WAIT_FOR_DURABLE_PROOF
                : Decision.RECONCILE_MISSING_CARGO;
    }
}
