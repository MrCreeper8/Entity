package dev.entity.client.runtime;

import java.util.Objects;

/**
 * Durable-in-process owner stop boundary.
 *
 * <p>A mission cancellation is not a body stop: Home economy and protection can own the same
 * physical controls without an active mission. This latch therefore suppresses every
 * discretionary controller until a later explicit work command releases it. Environmental
 * survival remains outside this policy and is still evaluated by {@code EntityCore}.</p>
 */
public final class OperatorStopLatch {
    private boolean stopped;
    private long generation;
    private String stoppedActivity = "idle";

    public synchronized Snapshot stop(String activity) {
        stopped = true;
        generation++;
        stoppedActivity = normalize(activity, "idle");
        return snapshot();
    }

    /** Releases the stop only for a command that actually requests new physical work. */
    public synchronized Snapshot resumeForExplicitWork(String command) {
        stopped = false;
        stoppedActivity = "";
        return snapshot();
    }

    public synchronized boolean stopped() {
        return stopped;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(stopped, generation, stoppedActivity);
    }

    private static String normalize(String value, String fallback) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    public record Snapshot(boolean stopped, long generation, String stoppedActivity) {
        public Snapshot {
            if (generation < 0L) throw new IllegalArgumentException("generation cannot be negative");
            stoppedActivity = Objects.requireNonNullElse(stoppedActivity, "").trim();
            if (stopped && stoppedActivity.isEmpty()) {
                throw new IllegalArgumentException("a stopped latch requires an activity label");
            }
        }
    }
}
