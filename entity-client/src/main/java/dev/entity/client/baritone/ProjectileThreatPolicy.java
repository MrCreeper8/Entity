package dev.entity.client.baritone;

/** Timing facts, not a periodic shield toggle. Vanilla shield use has a five-tick start delay. */
public final class ProjectileThreatPolicy {
    private ProjectileThreatPolicy() { }

    public static boolean bowReleaseSoon(boolean usingBow, int useTicks) {
        // Skeleton BowAttackGoal fires after twenty draw ticks. Raise with ten ticks to spare.
        return usingBow && useTicks >= 10;
    }

    public static boolean incoming(double x, double y, double z,
                                   double vx, double vy, double vz) {
        double speedSquared = vx * vx + vy * vy + vz * vz;
        if (!Double.isFinite(speedSquared) || speedSquared < 0.01) return false;
        double ticks = -(x * vx + y * vy + z * vz) / speedSquared;
        if (ticks < 0.0 || ticks > 10.0) return false;
        double px = x + vx * ticks;
        double py = y + vy * ticks;
        double pz = z + vz * ticks;
        return px * px + pz * pz <= 1.0 && Math.abs(py) <= 1.4;
    }
}
