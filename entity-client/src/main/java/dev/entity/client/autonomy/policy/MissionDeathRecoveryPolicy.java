package dev.entity.client.autonomy.policy;

import java.util.Map;
import java.util.Objects;

/**
 * Decides where a pre-respawn inventory checkpoint belongs.
 *
 * <p>Death recovery is an inventory/survival prerequisite, not a property of
 * the command grammar. A retained follow or goto mission deserves the same
 * opportunity to recover its armour and tools as a get/gear mission. Native
 * hierarchical plans retain their own root; direct missions receive a small
 * durable sidecar that completes before the original mission resumes.</p>
 */
public final class MissionDeathRecoveryPolicy {
    public static final String SIDECAR_ROOT_KIND = "root.death_recovery";
    public static final String TERMINAL_DISPOSITION_KEY =
            "deathRecoveryTerminalDisposition";
    public static final String TERMINAL_MISSING_COUNT_KEY =
            "deathRecoveryTerminalMissingCount";
    public static final String TERMINAL_DETAIL_KEY =
            "deathRecoveryTerminalDetail";

    private MissionDeathRecoveryPolicy() {
    }

    public static Decision decide(
            int capturedItemCount,
            boolean openRecoveryPlan,
            boolean openMissionPlan) {
        if (capturedItemCount < 0) {
            throw new IllegalArgumentException("capturedItemCount cannot be negative");
        }
        if (capturedItemCount == 0) {
            return new Decision(Action.NONE, "no carried items required death-site recovery");
        }
        if (openRecoveryPlan) {
            return new Decision(Action.CHECKPOINT_RECOVERY,
                    "updated the existing durable recovery prerequisite");
        }
        if (openMissionPlan) {
            return new Decision(Action.CHECKPOINT_MISSION,
                    "checkpointed death cargo on the mission's durable root");
        }
        return new Decision(Action.CREATE_SIDECAR,
                "created a durable death-cargo prerequisite for the retained direct mission");
    }

    public static String sidecarId(String missionId, long diedAtMillis) {
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("missionId cannot be blank");
        }
        if (diedAtMillis < 0L) {
            throw new IllegalArgumentException("diedAtMillis cannot be negative");
        }
        return missionId + ":death-recovery:" + diedAtMillis;
    }

    /**
     * Creates the durable terminal truth reported when a direct mission resumes.
     * A partial recovery is intentionally non-terminal for the parent mission,
     * but it must never be rendered as a successful item recovery.
     */
    public static TerminalResult terminalResult(int missingCount, String detail) {
        if (missingCount < 0) {
            throw new IllegalArgumentException("missingCount cannot be negative");
        }
        return new TerminalResult(
                missingCount == 0
                        ? TerminalDisposition.VERIFIED_ALL
                        : TerminalDisposition.PARTIAL_LOSS,
                missingCount,
                detail);
    }

    /** Restores a terminal result, failing closed for old or torn checkpoints. */
    public static TerminalResult restoreTerminalResult(Map<String, String> checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String encoded = checkpoint.getOrDefault(TERMINAL_DISPOSITION_KEY, "").trim();
        if (encoded.isEmpty()) {
            return new TerminalResult(
                    TerminalDisposition.UNKNOWN,
                    -1,
                    "no durable death-recovery terminal result was recorded");
        }
        try {
            TerminalDisposition disposition = TerminalDisposition.valueOf(encoded);
            int missing = Integer.parseInt(
                    checkpoint.getOrDefault(TERMINAL_MISSING_COUNT_KEY, "-1"));
            String detail = checkpoint.getOrDefault(TERMINAL_DETAIL_KEY, "");
            return new TerminalResult(disposition, missing, detail);
        } catch (IllegalArgumentException invalid) {
            return new TerminalResult(
                    TerminalDisposition.UNKNOWN,
                    -1,
                    "death-recovery terminal checkpoint was corrupt");
        }
    }

    /** User-facing result that never equates bounded completion with full recovery. */
    public static String resumeDetail(String missionKind, TerminalResult result) {
        String kind = requireText(missionKind, "missionKind");
        Objects.requireNonNull(result, "result");
        return switch (result.disposition()) {
            case VERIFIED_ALL -> "verified all captured death cargo; resuming " + kind;
            case PARTIAL_LOSS -> "WARNING: death-drop recovery ended with "
                    + result.missingCount() + " captured item(s) still missing"
                    + suffix(result.detail()) + "; resuming " + kind;
            case UNKNOWN -> "WARNING: death-drop recovery ended without a verified terminal result"
                    + suffix(result.detail()) + "; resuming " + kind;
        };
    }

    private static String suffix(String detail) {
        String normalized = Objects.requireNonNullElse(detail, "").trim();
        return normalized.isEmpty() ? "" : ": " + normalized;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }

    public enum Action {
        NONE,
        CHECKPOINT_RECOVERY,
        CHECKPOINT_MISSION,
        CREATE_SIDECAR
    }

    public enum TerminalDisposition {
        VERIFIED_ALL,
        PARTIAL_LOSS,
        UNKNOWN
    }

    public record TerminalResult(
            TerminalDisposition disposition,
            int missingCount,
            String detail) {
        public TerminalResult {
            Objects.requireNonNull(disposition, "disposition");
            detail = Objects.requireNonNullElse(detail, "").trim();
            if (disposition == TerminalDisposition.VERIFIED_ALL && missingCount != 0) {
                throw new IllegalArgumentException("verified recovery must have zero missing items");
            }
            if (disposition == TerminalDisposition.PARTIAL_LOSS && missingCount < 1) {
                throw new IllegalArgumentException("partial recovery must name missing items");
            }
            if (disposition == TerminalDisposition.UNKNOWN && missingCount != -1) {
                throw new IllegalArgumentException("unknown recovery must use an unknown count");
            }
        }
    }

    public record Decision(Action action, String detail) {
        public Decision {
            Objects.requireNonNull(action, "action");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }
}
