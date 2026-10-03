package dev.entity.client.runtime;

import dev.entity.core.model.MissionState;

import java.util.Objects;

/**
 * Decides whether an uncaught client-tick exception represents a new mission
 * attempt failure or merely another tick while that mission is already parked.
 *
 * <p>The runtime ticks even while a durable mission is waiting, blocked, or
 * paused. Reporting the same adapter exception from those states would turn a
 * single failure into one persisted failure per Minecraft tick. Only a mission
 * that is eligible to be executing may consume another retry attempt.</p>
 */
public final class ExecutionFailurePolicy {
    private ExecutionFailurePolicy() {
    }

    public static boolean shouldReportTransientFailure(MissionState state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case QUEUED, RUNNING -> true;
            case PAUSED_BY_OWNER, PAUSED_BY_SAFETY, PAUSED_BY_PROTECTION,
                    RETRY_WAIT, BLOCKED, COMPLETED, CANCELLED -> false;
        };
    }
}
