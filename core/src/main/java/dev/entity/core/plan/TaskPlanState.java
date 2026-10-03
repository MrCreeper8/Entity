package dev.entity.core.plan;

/** Lifecycle of a persisted task plan. */
public enum TaskPlanState {
    OPEN,
    COMPLETED,
    CLEARED,
    /**
     * The owning durable journal has already recorded the terminal outcome.
     * A retired plan is evidence only and can never become executable again.
     */
    RETIRED;

    public boolean terminal() {
        return this != OPEN;
    }
}
