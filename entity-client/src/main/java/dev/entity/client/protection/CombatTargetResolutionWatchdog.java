package dev.entity.client.protection;

import java.util.Objects;

/**
 * Bounded liveness clock for one exact protection target.
 *
 * <p>Route state is deliberately absent from this contract. Baritone may report
 * {@code AT_GOAL}, stop expecting body movement, or repeatedly replace a route
 * while the selected hostile remains unresolved. Only progress toward resolving
 * that exact hostile resets the clock: materially closing its distance, reducing
 * its health, or advancing the operation's authoritative strike/work counter.</p>
 */
public final class CombatTargetResolutionWatchdog {
    public static final long DEFAULT_NO_RESOLUTION_MILLIS = 6_000L;
    public static final double DEFAULT_DISTANCE_IMPROVEMENT = 0.25;
    public static final double DEFAULT_HEALTH_REDUCTION = 0.01;

    private final long noResolutionMillis;
    private final double distanceImprovement;
    private final double healthReduction;
    private Baseline baseline;

    public CombatTargetResolutionWatchdog() {
        this(DEFAULT_NO_RESOLUTION_MILLIS,
                DEFAULT_DISTANCE_IMPROVEMENT,
                DEFAULT_HEALTH_REDUCTION);
    }

    CombatTargetResolutionWatchdog(
            long noResolutionMillis,
            double distanceImprovement,
            double healthReduction) {
        if (noResolutionMillis <= 0L
                || !Double.isFinite(distanceImprovement) || distanceImprovement < 0.0
                || !Double.isFinite(healthReduction) || healthReduction < 0.0) {
            throw new IllegalArgumentException("invalid combat target resolution thresholds");
        }
        this.noResolutionMillis = noResolutionMillis;
        this.distanceImprovement = distanceImprovement;
        this.healthReduction = healthReduction;
    }

    public Result observe(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        String targetId = normalize(observation.targetId());
        if (targetId.isEmpty()) {
            throw new IllegalArgumentException("targetId cannot be blank");
        }
        if (baseline == null || !baseline.targetId().equals(targetId)) {
            baseline = Baseline.from(observation, targetId);
            return new Result(State.INITIALIZED, 0L,
                    "exact target resolution baseline established");
        }

        boolean distanceClosed = finite(baseline.distance(), observation.distance())
                && baseline.distance() - observation.distance() >= distanceImprovement;
        boolean healthReduced = finite(baseline.health(), observation.health())
                && baseline.health() - observation.health() >= healthReduction;
        boolean workAdvanced = observation.completedWorkUnits()
                > baseline.completedWorkUnits();
        if (distanceClosed || healthReduced || workAdvanced) {
            baseline = Baseline.from(observation, targetId);
            String evidence = workAdvanced
                    ? "authoritative combat work advanced"
                    : healthReduced
                    ? "target health decreased"
                    : "target distance materially closed";
            return new Result(State.PROGRESS, 0L, evidence);
        }

        long idleMillis = Math.max(
                0L, observation.nowMillis() - baseline.lastResolutionAtMillis());
        if (idleMillis >= noResolutionMillis) {
            // Establish the next bounded epoch here. Runtime also resets after
            // canceling the exact operation, so neither layer can emit a failure
            // every client tick if retargeting needs its separate grace window.
            baseline = Baseline.from(observation, targetId);
            return new Result(State.STUCK, idleMillis,
                    "exact target had no closing distance, health loss, or strike/work progress");
        }
        return new Result(State.NO_CHANGE, idleMillis,
                "exact target remains within its bounded resolution grace");
    }

    public void reset() {
        baseline = null;
    }

    private static boolean finite(double first, double second) {
        return Double.isFinite(first) && Double.isFinite(second);
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    public enum State {
        INITIALIZED,
        PROGRESS,
        NO_CHANGE,
        STUCK
    }

    public record Observation(
            long nowMillis,
            String targetId,
            double distance,
            double health,
            long completedWorkUnits) {
    }

    public record Result(State state, long noResolutionMillis, String detail) {
        public Result {
            Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    private record Baseline(
            String targetId,
            long lastResolutionAtMillis,
            double distance,
            double health,
            long completedWorkUnits) {
        private static Baseline from(Observation observation, String targetId) {
            return new Baseline(
                    targetId,
                    observation.nowMillis(),
                    observation.distance(),
                    observation.health(),
                    observation.completedWorkUnits());
        }
    }
}
