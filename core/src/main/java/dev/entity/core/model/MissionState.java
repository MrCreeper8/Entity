package dev.entity.core.model;

/** Persistent lifecycle. Only COMPLETED and CANCELLED are terminal. */
public enum MissionState {
    QUEUED,
    RUNNING,
    PAUSED_BY_OWNER,
    PAUSED_BY_SAFETY,
    PAUSED_BY_PROTECTION,
    RETRY_WAIT,
    BLOCKED,
    COMPLETED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
