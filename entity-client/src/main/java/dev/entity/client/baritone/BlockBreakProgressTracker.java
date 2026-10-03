package dev.entity.client.baritone;

import java.util.Objects;

/** Counts only new, monotonic break stages for one concrete block target. */
public final class BlockBreakProgressTracker {
    private static final long RESET_WINDOW_MILLIS = 10_000L;
    private String target = "";
    private int highestStage = -1;
    private int consecutiveResets;
    private long resetWindowStartedAt = Long.MIN_VALUE;

    public boolean observe(String targetKey, int stage) {
        return observeDetailed(targetKey, stage) == Result.PROGRESSED;
    }

    public Result observeDetailed(String targetKey, int stage) {
        return observeDetailed(targetKey, stage, System.currentTimeMillis());
    }

    public Result observeDetailed(String targetKey, int stage, long nowMillis) {
        String normalized = Objects.requireNonNullElse(targetKey, "");
        if (normalized.isBlank() || stage < 0) return Result.NO_CHANGE;
        if (!normalized.equals(target)) {
            target = normalized;
            highestStage = stage;
            consecutiveResets = 0;
            resetWindowStartedAt = Long.MIN_VALUE;
            return Result.TARGET_CHANGED;
        }
        if (stage < highestStage) {
            if (resetWindowStartedAt == Long.MIN_VALUE
                    || nowMillis - resetWindowStartedAt > RESET_WINDOW_MILLIS) {
                resetWindowStartedAt = nowMillis;
                consecutiveResets = 0;
            }
            consecutiveResets++;
            // A restarted crack animation establishes a new baseline. Keeping
            // the old high-water mark made every later legitimate stage look
            // like another reset forever.
            highestStage = stage;
            return Result.RESET;
        }
        if (stage == highestStage) return Result.NO_CHANGE;
        highestStage = stage;
        return stage > 0 ? Result.PROGRESSED : Result.NO_CHANGE;
    }

    public void recovered() {
        highestStage = -1;
        consecutiveResets = 0;
        resetWindowStartedAt = Long.MIN_VALUE;
    }

    public int consecutiveResets() {
        return consecutiveResets;
    }

    public String target() {
        return target;
    }

    public enum Result {
        TARGET_CHANGED,
        PROGRESSED,
        RESET,
        NO_CHANGE
    }
}
