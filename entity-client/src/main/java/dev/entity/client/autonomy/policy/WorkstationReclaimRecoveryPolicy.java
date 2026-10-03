package dev.entity.client.autonomy.policy;

/**
 * Owns the bounded, workstation-local retry decision for reclaim geometry.
 *
 * <p>A failed stand has already been removed from the candidate set, so the
 * next tick is useful local exploration rather than a generic action retry.
 * Only exhaustion is exposed to the outer action supervisor.</p>
 */
public final class WorkstationReclaimRecoveryPolicy {
    private WorkstationReclaimRecoveryPolicy() {
    }

    public static Disposition afterLocalFailure(int completedFailures, int maximumFailures) {
        if (completedFailures <= 0) {
            throw new IllegalArgumentException("completedFailures must be positive");
        }
        if (maximumFailures <= 0) {
            throw new IllegalArgumentException("maximumFailures must be positive");
        }
        return completedFailures >= maximumFailures
                ? Disposition.BLOCKED
                : Disposition.CONTINUE_RECLAIMING;
    }

    /**
     * Charges only a newly rejected candidate. Re-observing an already
     * exhausted candidate set is terminal without manufacturing another
     * failure on every client tick.
     */
    public static FailureDecision recordCandidateFailure(
            int previousFailures,
            int maximumFailures,
            boolean newlyRejectedCandidate) {
        if (previousFailures < 0) {
            throw new IllegalArgumentException("previousFailures cannot be negative");
        }
        if (maximumFailures <= 0) {
            throw new IllegalArgumentException("maximumFailures must be positive");
        }
        if (!newlyRejectedCandidate) {
            return new FailureDecision(previousFailures, Disposition.CANDIDATES_EXHAUSTED);
        }
        int completed = Math.addExact(previousFailures, 1);
        return new FailureDecision(completed, afterLocalFailure(completed, maximumFailures));
    }

    public record FailureDecision(int completedFailures, Disposition disposition) {
        public FailureDecision {
            if (completedFailures < 0) {
                throw new IllegalArgumentException("completedFailures cannot be negative");
            }
            if (disposition == null) throw new NullPointerException("disposition");
        }
    }

    public enum Disposition {
        CONTINUE_RECLAIMING,
        BLOCKED,
        CANDIDATES_EXHAUSTED
    }
}
