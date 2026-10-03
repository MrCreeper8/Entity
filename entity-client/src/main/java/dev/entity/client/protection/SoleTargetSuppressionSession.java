package dev.entity.client.protection;

import java.util.Objects;

/**
 * Episode-local liveness bound for one quiet, distant, unreachable ordinary hostile.
 *
 * <p>The first typed STANDARD protection failure opens one fixed defensive cooldown. Exactly one
 * retry may follow without authoritative combat progress. A second typed failure may suppress
 * only the exact sole target, and only while a fresh safety observation proves that doing so is
 * equivalent to treating an inactive, occluded mission obstacle as absent. Unsafe evidence always
 * returns the raw threat; an established suppression clears only for material combat danger, while
 * an unrelated temporary survival preemption preserves its failure history. Nothing in this class
 * is durable: process restart loses suppression and protects again.</p>
 */
public final class SoleTargetSuppressionSession {
    public static final long DEFENSIVE_COOLDOWN_MILLIS = 2_000L;
    /** Strictly outside vanilla/tactical melee body-blocking reach. */
    public static final double MINIMUM_SUPPRESSION_DISTANCE = 3.2;

    private String episodeId = "";
    private String targetId = "";
    private State state = State.NONE;
    private long retryAtMillis;
    private int exhaustionCount;
    private boolean retryStartClaimed;
    private long lastExecutionFailureGeneration = -1L;
    private String executionAttemptEpisodeId = "";
    private String executionAttemptTargetId = "";
    private boolean executionAttemptRetryPhase;
    private long executionAttemptSequence;
    private long executionAttemptGeneration = -1L;
    private long executionAttemptFabricGeneration = -1L;

    /** Keeps state only for the same concrete mission episode. */
    public void retainEpisode(String nextEpisodeId) {
        String normalized = normalize(nextEpisodeId);
        if (state != State.NONE && !episodeId.equals(normalized)
                || !executionAttemptEpisodeId.isEmpty()
                && !executionAttemptEpisodeId.equals(normalized)) clear();
    }

    /**
     * Mints one logical STANDARD executor attempt before loadout or adapter code can throw. The
     * token is stable across ordinary ticks/refreshes; reaching RETRY_ACTIVE mints the sole second
     * attempt even when the exception happens before Fabric can publish an operation generation.
     */
    public long beginStandardExecutionAttempt(
            String currentEpisodeId,
            String exactTargetId) {
        String episode = require(currentEpisodeId, "currentEpisodeId");
        String target = require(exactTargetId, "exactTargetId");
        boolean retryPhase = state == State.RETRY_ACTIVE;
        if (executionAttemptGeneration < 0L
                || !executionAttemptEpisodeId.equals(episode)
                || !executionAttemptTargetId.equals(target)
                || executionAttemptRetryPhase != retryPhase) {
            executionAttemptSequence = saturatedIncrement(executionAttemptSequence);
            executionAttemptGeneration = executionAttemptSequence;
            executionAttemptEpisodeId = episode;
            executionAttemptTargetId = target;
            executionAttemptRetryPhase = retryPhase;
            executionAttemptFabricGeneration = -1L;
        }
        return executionAttemptGeneration;
    }

    /** Associates producer identity without changing the already-minted logical attempt. */
    public boolean observeStandardFabricGeneration(
            long logicalAttemptGeneration,
            long fabricOperationGeneration) {
        if (logicalAttemptGeneration != executionAttemptGeneration
                || fabricOperationGeneration < 0L) return false;
        executionAttemptFabricGeneration = fabricOperationGeneration;
        return true;
    }

    public long standardFabricGeneration(long logicalAttemptGeneration) {
        return logicalAttemptGeneration == executionAttemptGeneration
                ? executionAttemptFabricGeneration
                : -1L;
    }

    /**
     * Gates STANDARD protection execution after the first failure. Repeated polls cannot move the
     * deadline, and the transition at the deadline authorizes only one retry operation.
     */
    public ExecutionGate executionGate(
            String currentEpisodeId,
            String exactTargetId,
            long nowMillis) {
        requireTime(nowMillis);
        String episode = require(currentEpisodeId, "currentEpisodeId");
        String target = require(exactTargetId, "exactTargetId");
        if (state == State.NONE) return ExecutionGate.ALLOW;
        if (!episodeId.equals(episode) || !targetId.equals(target)) {
            clear();
            return ExecutionGate.ALLOW;
        }
        if (state == State.DEFENSIVE_COOLDOWN) {
            if (nowMillis < retryAtMillis) return ExecutionGate.DEFENSIVE_HOLD;
            state = State.RETRY_ACTIVE;
            return ExecutionGate.ALLOW;
        }
        return state == State.RETRY_ACTIVE
                ? ExecutionGate.ALLOW
                : ExecutionGate.DEFENSIVE_HOLD;
    }

    /**
     * Claims the sole retry at the concrete STANDARD operation-start boundary. Refreshing that
     * same live operation does not call this method. If another layer later cancels it, the
     * authorization remains consumed and cannot create a third operation generation.
     */
    public boolean claimRetryStart(
            String currentEpisodeId,
            String exactTargetId) {
        String episode = require(currentEpisodeId, "currentEpisodeId");
        String target = require(exactTargetId, "exactTargetId");
        if (state != State.RETRY_ACTIVE
                || !episodeId.equals(episode)
                || !targetId.equals(target)
                || retryStartClaimed) {
            return false;
        }
        retryStartClaimed = true;
        return true;
    }

    /** True only while the already-claimed retry may be refreshed as the same live operation. */
    public boolean retryRefreshAllowed(String currentEpisodeId, String exactTargetId) {
        return state == State.RETRY_ACTIVE
                && retryStartClaimed
                && episodeId.equals(normalize(currentEpisodeId))
                && targetId.equals(normalize(exactTargetId));
    }

    /**
     * A claimed retry that disappeared without target progress or a typed failure is still spent.
     * Move to the same terminal/pending arbitration state as a typed second exhaustion.
     */
    public boolean abandonClaimedRetry(String currentEpisodeId, String exactTargetId) {
        if (!retryRefreshAllowed(currentEpisodeId, exactTargetId)) return false;
        state = State.SUPPRESSION_PENDING;
        retryAtMillis = 0L;
        exhaustionCount = 2;
        return true;
    }

    /**
     * Records one typed failure from a STANDARD protection operation. Duplicate reports while the
     * operation is already cooling down or exhausted are ignored and cannot slide any deadline.
     */
    public boolean exhaustStandard(
            String currentEpisodeId,
            String exactTargetId,
            Exhaustion exhaustion,
            long nowMillis) {
        String episode = require(currentEpisodeId, "currentEpisodeId");
        String target = require(exactTargetId, "exactTargetId");
        Objects.requireNonNull(exhaustion, "exhaustion");
        requireTime(nowMillis);
        if (state != State.NONE
                && (!episodeId.equals(episode) || !targetId.equals(target))) {
            clear();
        }
        if (state == State.NONE) {
            episodeId = episode;
            targetId = target;
            state = State.DEFENSIVE_COOLDOWN;
            retryAtMillis = saturatedAdd(nowMillis, DEFENSIVE_COOLDOWN_MILLIS);
            exhaustionCount = 1;
            return true;
        }
        if (state != State.RETRY_ACTIVE) return false;
        state = State.SUPPRESSION_PENDING;
        retryAtMillis = 0L;
        exhaustionCount = 2;
        return true;
    }

    /**
     * Debits a RuntimeException at most once for one exact live STANDARD operation generation.
     * This is deliberately distinct from a typed route failure: an adapter/executor exception
     * consumes the same bounded liveness budget without falsely claiming traversal evidence.
     */
    public boolean exhaustStandardExecutionFailure(
            String currentEpisodeId,
            String exactTargetId,
            long operationGeneration,
            long nowMillis) {
        if (operationGeneration < 0L
                || operationGeneration != executionAttemptGeneration
                || operationGeneration == lastExecutionFailureGeneration) {
            return false;
        }
        boolean accepted = exhaustStandard(
                currentEpisodeId,
                exactTargetId,
                Exhaustion.STANDARD_EXECUTION_FAILURE,
                nowMillis);
        // exhaustStandard may clear a prior target/episode before accepting this one, so publish
        // the generation after that lifecycle normalization. Repeated reports remain free even
        // when another failure state already made this particular debit a no-op.
        lastExecutionFailureGeneration = operationGeneration;
        return accepted;
    }

    /** Any exact-target progress is a meaningful change and earns a fresh future budget. */
    public boolean markResolutionProgress(String exactTargetId) {
        String target = normalize(exactTargetId);
        boolean suppressionMatches = state != State.NONE && targetId.equals(target);
        boolean executionAttemptMatches = executionAttemptGeneration >= 0L
                && executionAttemptTargetId.equals(target);
        if (!suppressionMatches && !executionAttemptMatches) return false;
        clear();
        return true;
    }

    /**
     * Revalidates the complete safety predicate before core arbitration. Pending suppression is
     * latched only here; an already-suppressed target must pass the same predicate every tick.
     */
    public Arbitration arbitrate(SafetyObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (state == State.NONE) return Arbitration.PASS_THROUGH;
        if (!episodeId.equals(observation.episodeId())
                || !targetId.equals(observation.targetId())) {
            clear();
            return Arbitration.PASS_THROUGH;
        }
        if (!observation.targetPresent()) {
            // Real removal/unload ends this exact-target lifecycle. A later entity with the same
            // UUID must earn a fresh typed failure history rather than inherit suppression.
            clear();
            return Arbitration.PASS_THROUGH;
        }
        if (observation.survivalActive() && safeCombatFacts(observation)) {
            // An unrelated higher-priority fall/fire/water emergency temporarily owns core
            // arbitration, but says nothing new about this exact hostile. Preserve both pending
            // failure history and an established suppression; the target is revalidated from
            // fresh combat facts as soon as survival releases.
            return Arbitration.PASS_THROUGH;
        }
        // Safety predicates do not own failure history. Visibility, targeting, or fresh damage
        // must return the raw threat to core immediately, but may not launder a fixed cooldown,
        // retry, or the exhausted terminal into another Baritone start loop.
        if (state != State.SUPPRESSION_PENDING && state != State.SUPPRESSED) {
            return Arbitration.PASS_THROUGH;
        }
        if (!safeToSuppress(observation)) {
            if (state == State.SUPPRESSED) {
                // New danger after a successfully quiet suppression is a meaningful world-state
                // change. Ask Runtime for a one-tick exact-target core projection before clearing
                // the history; raw platform/telemetry evidence remains unchanged.
                clear();
                return Arbitration.REACTIVATE_EXACT_TARGET;
            }
            // The second attempt is already consumed, but unsafe facts forbid suppression. Make
            // the exact inactive raw UUID core-eligible without clearing history, so PROTECTION
            // continues to reach Runtime's terminal DirectBody defense after core's 3s retained
            // engagement grace expires. This projection never authorizes another Baritone start.
            return Arbitration.PROTECT_EXACT_TARGET;
        }
        if (state == State.SUPPRESSION_PENDING) state = State.SUPPRESSED;
        return state == State.SUPPRESSED
                ? Arbitration.SUPPRESS_EXACT_TARGET
                : Arbitration.PASS_THROUGH;
    }

    public State state() {
        return state;
    }

    public String targetId() {
        return targetId;
    }

    public long retryAtMillis() {
        return retryAtMillis;
    }

    public int exhaustionCount() {
        return exhaustionCount;
    }

    public void clear() {
        episodeId = "";
        targetId = "";
        state = State.NONE;
        retryAtMillis = 0L;
        exhaustionCount = 0;
        retryStartClaimed = false;
        lastExecutionFailureGeneration = -1L;
        executionAttemptEpisodeId = "";
        executionAttemptTargetId = "";
        executionAttemptRetryPhase = false;
        executionAttemptGeneration = -1L;
        executionAttemptFabricGeneration = -1L;
    }

    public static boolean safeToSuppress(SafetyObservation observation) {
        Objects.requireNonNull(observation, "observation");
        return safeCombatFacts(observation) && !observation.survivalActive();
    }

    private static boolean safeCombatFacts(SafetyObservation observation) {
        return observation.targetPresent()
                && (observation.targetKind() == TargetKind.ZOMBIE_CLASS
                || observation.targetKind() == TargetKind.SKELETON_CLASS)
                && observation.distance() > MINIMUM_SUPPRESSION_DISTANCE
                && !observation.lineOfSight()
                && !observation.targetingEvidence()
                && !observation.freshDamageEvidence()
                && !observation.otherEligibleThreat()
                && !observation.avoidStance()
                && !observation.lowHealth()
                && !observation.modeledLethal()
                && !observation.bodyBlocker()
                && !observation.visibleRangedPressure();
    }

    public enum Exhaustion {
        STANDARD_ROUTE_EXHAUSTED,
        STANDARD_NO_RESOLUTION,
        STANDARD_EXECUTION_FAILURE
    }

    public enum ExecutionGate {
        ALLOW,
        DEFENSIVE_HOLD
    }

    public enum Arbitration {
        PASS_THROUGH,
        SUPPRESS_EXACT_TARGET,
        REACTIVATE_EXACT_TARGET,
        PROTECT_EXACT_TARGET
    }

    public enum State {
        NONE,
        DEFENSIVE_COOLDOWN,
        RETRY_ACTIVE,
        SUPPRESSION_PENDING,
        SUPPRESSED
    }

    public enum TargetKind {
        ZOMBIE_CLASS,
        SKELETON_CLASS,
        PLAYER,
        CREEPER,
        OTHER
    }

    public record SafetyObservation(
            String episodeId,
            String targetId,
            TargetKind targetKind,
            boolean targetPresent,
            double distance,
            boolean lineOfSight,
            boolean targetingEvidence,
            boolean freshDamageEvidence,
            boolean otherEligibleThreat,
            boolean avoidStance,
            boolean lowHealth,
            boolean modeledLethal,
            boolean bodyBlocker,
            boolean visibleRangedPressure,
            boolean survivalActive) {
        public SafetyObservation {
            episodeId = require(episodeId, "episodeId");
            targetId = require(targetId, "targetId");
            targetKind = Objects.requireNonNull(targetKind, "targetKind");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("distance must be finite and non-negative");
            }
        }
    }

    private static String require(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return normalized;
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static void requireTime(long nowMillis) {
        if (nowMillis < 0L) throw new IllegalArgumentException("nowMillis cannot be negative");
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? 1L : value + 1L;
    }
}
