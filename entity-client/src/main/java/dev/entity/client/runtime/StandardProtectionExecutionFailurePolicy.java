package dev.entity.client.runtime;

/** Fail-closed eligibility for charging an executor exception to sole-target liveness. */
public final class StandardProtectionExecutionFailurePolicy {
    public static boolean eligible(Observation observation) {
        if (observation == null) throw new IllegalArgumentException("observation cannot be null");
        return observation.logicalAttemptGeneration() >= 0L
                && observation.standardContext()
                && observation.leaseValid()
                && observation.targetPresent()
                && !observation.survivalActive();
    }

    public record Observation(
            long logicalAttemptGeneration,
            long fabricOperationGeneration,
            boolean standardContext,
            boolean leaseValid,
            boolean targetPresent,
            boolean survivalActive) {
    }

    private StandardProtectionExecutionFailurePolicy() {
    }
}
