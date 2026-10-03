package dev.entity.core.plan;

/** Execution state of one durable frame in a mission's prerequisite stack. */
public enum PlanFrameState {
    PENDING,
    ACTIVE,
    WAITING_ON_CHILD,
    COMPLETED
}
