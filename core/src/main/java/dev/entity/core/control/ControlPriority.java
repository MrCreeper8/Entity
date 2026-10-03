package dev.entity.core.control;

/** Higher values may atomically preempt lower-priority body owners. */
public final class ControlPriority {
    public static final int IDLE = 0;
    public static final int MISSION = 400;
    public static final int PROTECTION = 700;
    public static final int MANUAL = 850;
    public static final int SURVIVAL_RECOVERY = 900;
    public static final int SURVIVAL_FIRE = 1_000;
    public static final int SURVIVAL_LAVA = 1_100;
    public static final int SURVIVAL_DROWNING = 1_200;
    public static final int SURVIVAL_SUFFOCATION = 1_250;
    public static final int SURVIVAL_FALL = 1_300;

    private ControlPriority() {
    }
}
