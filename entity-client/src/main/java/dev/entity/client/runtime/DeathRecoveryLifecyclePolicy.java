package dev.entity.client.runtime;

import dev.entity.core.model.MissionState;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Pure ordering policy for the durable death/respawn boundary. */
public final class DeathRecoveryLifecyclePolicy {
    private static final String DEATH_PAUSE_PREFIX = "death interrupted mission:";
    private static final String UNTRUSTED_QUARANTINE_PREFIX =
            "automatic execution quarantined after untrusted death recovery";
    private static final String REBASE_TOKEN_MARKER = "; rebase token ";
    private static final String ACKNOWLEDGEMENT_SUFFIX = "; use /retry to acknowledge";

    private DeathRecoveryLifecyclePolicy() {
    }

    public static FinishPlan finishPlan(
            MissionState state,
            String pauseReason,
            boolean haltAutomaticRecovery) {
        if (state == null || state.terminal()) {
            return new FinishPlan(false, false, Completion.CLEAR_ONLY);
        }

        boolean validDeathPause = state == MissionState.PAUSED_BY_SAFETY
                && Objects.requireNonNullElse(pauseReason, "")
                        .startsWith(DEATH_PAUSE_PREFIX);
        // The recovery journal is deliberately flushed before the mission
        // store. These states can therefore be the legitimate crash window
        // after pending was saved but before the death pause reached disk.
        boolean crashBeforePause = state == MissionState.QUEUED
                || state == MissionState.RUNNING
                || state == MissionState.PAUSED_BY_PROTECTION;
        Completion completion = haltAutomaticRecovery
                ? Completion.BLOCK
                : validDeathPause || crashBeforePause
                        ? Completion.AUTO_RESUME
                        : Completion.PRESERVE_STATE;
        return new FinishPlan(crashBeforePause, true, completion);
    }

    public static boolean lifecycleMutationBlocked(
            String pendingMissionId,
            String operation) {
        if (Objects.requireNonNullElse(pendingMissionId, "").isBlank()) return false;
        String normalized = Objects.requireNonNullElse(operation, "")
                .trim().toLowerCase(Locale.ROOT);
        return normalized.equals("resume") || normalized.equals("retry");
    }

    /**
     * A restored death boundary cannot be reconciled until a live player exists.
     * In particular, a whole-client restart spends several ticks with no player;
     * those ticks must not fall through to EntityCore and restart the paused plan.
     */
    public static boolean waitForPlayerBeforeReconciliation(
            String pendingMissionId,
            boolean playerPresent) {
        return !Objects.requireNonNullElse(pendingMissionId, "").isBlank()
                && !playerPresent;
    }

    public static boolean orphanedDeathPauseRequiresOperator(
            MissionState state,
            String pauseReason) {
        return state == MissionState.PAUSED_BY_SAFETY
                && Objects.requireNonNullElse(pauseReason, "")
                        .startsWith(DEATH_PAUSE_PREFIX);
    }

    /** Death invalidates transient execution assumptions even while the mission is parked. */
    public static boolean canBindInterruptedMission(MissionState state) {
        return state != null && !state.terminal();
    }

    /** States which may begin executing without a new explicit owner acknowledgement. */
    public static boolean executableUnderUntrustedRecoveryState(MissionState state) {
        return state != null && switch (state) {
            case QUEUED, RUNNING, PAUSED_BY_SAFETY, PAUSED_BY_PROTECTION, RETRY_WAIT -> true;
            case PAUSED_BY_OWNER, BLOCKED, COMPLETED, CANCELLED -> false;
        };
    }

    /** Pre-inbound ordering for every restart/death boundary candidate. */
    public static StartupBoundary startupBoundary(
            boolean recoveryStateUntrusted,
            boolean pendingRecovery,
            boolean orphanedDeathPause,
            boolean freshDeath) {
        if (recoveryStateUntrusted) return StartupBoundary.GLOBAL_QUARANTINE;
        if (pendingRecovery) return StartupBoundary.EXISTING_RECOVERY;
        if (freshDeath) return StartupBoundary.FRESH_DEATH;
        if (orphanedDeathPause) return StartupBoundary.ORPHANED_DEATH_PAUSE;
        return StartupBoundary.NONE;
    }

    public static String untrustedQuarantineReason(String rebaseToken) {
        String token = Objects.requireNonNullElse(rebaseToken, "").trim();
        if (token.isEmpty() || token.length() > 512 || token.indexOf(';') >= 0) {
            throw new IllegalArgumentException("untrusted recovery rebase token is invalid");
        }
        return UNTRUSTED_QUARANTINE_PREFIX + REBASE_TOKEN_MARKER + token
                + ACKNOWLEDGEMENT_SUFFIX;
    }

    /** Recovers durable work left between MissionStore block and PlanStore rebase. */
    public static Optional<String> untrustedQuarantineToken(
            MissionState state,
            String lastError) {
        if (state != MissionState.BLOCKED) return Optional.empty();
        String reason = Objects.requireNonNullElse(lastError, "");
        if (!reason.startsWith(UNTRUSTED_QUARANTINE_PREFIX + REBASE_TOKEN_MARKER)
                || !reason.endsWith(ACKNOWLEDGEMENT_SUFFIX)) {
            return Optional.empty();
        }
        int start = (UNTRUSTED_QUARANTINE_PREFIX + REBASE_TOKEN_MARKER).length();
        int end = reason.length() - ACKNOWLEDGEMENT_SUFFIX.length();
        if (start >= end) return Optional.empty();
        String token = reason.substring(start, end).trim();
        if (token.isEmpty() || token.length() > 512 || token.indexOf(';') >= 0) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    public static boolean journalClearOnly(DeathLoopRecoveryStore.RecoveryPhase phase) {
        return phase == DeathLoopRecoveryStore.RecoveryPhase.FINALIZED;
    }

    /**
     * EFFECTS_STARTED is persisted after death-pause normalization and before reconciliation.
     * RUNNING/BLOCKED therefore proves that resume/block committed after that marker; a restart
     * must finalize and clear instead of replaying either mutation.
     */
    public static boolean completionAlreadyApplied(
            DeathLoopRecoveryStore.RecoveryPhase phase,
            MissionState state,
            boolean haltAutomaticRecovery,
            boolean allOpenPlansReconciled,
            boolean recoveryBlockApplied) {
        if (phase != DeathLoopRecoveryStore.RecoveryPhase.EFFECTS_STARTED) return false;
        if (!allOpenPlansReconciled) return false;
        if (state == null || state.terminal()) return true;
        return haltAutomaticRecovery
                ? state == MissionState.BLOCKED && recoveryBlockApplied
                : state == MissionState.RUNNING;
    }

    public enum Completion {
        AUTO_RESUME,
        BLOCK,
        PRESERVE_STATE,
        CLEAR_ONLY
    }

    public enum StartupBoundary {
        GLOBAL_QUARANTINE,
        EXISTING_RECOVERY,
        ORPHANED_DEATH_PAUSE,
        FRESH_DEATH,
        NONE
    }

    public record FinishPlan(
            boolean normalizeDeathPause,
            boolean reconcilePlan,
            Completion completion) {
        public FinishPlan {
            Objects.requireNonNull(completion, "completion");
            if (normalizeDeathPause && !reconcilePlan) {
                throw new IllegalArgumentException("normalization requires reconciliation");
            }
        }
    }
}
