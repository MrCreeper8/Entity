package dev.entity.client.baritone;

import java.util.List;
import java.util.Objects;

/**
 * Pure geometry and liveness contract for routing to one buried resource.
 *
 * <p>The target interaction goal lets Baritone prefer a practical side approach
 * or existing passage. It is not a ban on downward mining: ordinary owner
 * missions retain downward, diagonal, placement, and clutch capability outside
 * the player's selected protected area.</p>
 */
public final class ScopedResourceRoutePolicy {
    private ScopedResourceRoutePolicy() {
    }

    public record Coordinate(int x, int y, int z) {
        public Coordinate add(int dx, int dy, int dz) {
            return new Coordinate(
                    Math.addExact(x, dx),
                    Math.addExact(y, dy),
                    Math.addExact(z, dz));
        }
    }

    public enum Decision {
        CONTINUE,
        RETIRE_CALCULATION_FAILED,
        RETIRE_UNUSABLE_ARRIVAL,
        RETIRE_NO_PROGRESS
    }

    /** Stable order keeps the emitted Baritone goal and evidence deterministic. */
    public static List<Coordinate> sideApproaches(Coordinate target) {
        Objects.requireNonNull(target, "target");
        return List.of(
                target.add(1, 0, 0),
                target.add(-1, 0, 0),
                target.add(0, 0, 1),
                target.add(0, 0, -1));
    }

    public static boolean isSideApproach(Coordinate target, Coordinate feet) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(feet, "feet");
        long dx = Math.abs((long) target.x() - feet.x());
        long dz = Math.abs((long) target.z() - feet.z());
        return feet.y() == target.y() && dx + dz == 1;
    }

    /**
     * Converts terminal route evidence into one state transition. Exact target
     * visibility wins because interaction can begin before the goal cell is
     * physically occupied.
     */
    public static Decision decide(
            boolean exactTargetVisible,
            boolean calculationFailed,
            boolean sideGoalOccupied,
            long millisWithoutProgress,
            long stallLimitMillis) {
        if (millisWithoutProgress < 0L) {
            throw new IllegalArgumentException("millisWithoutProgress must be nonnegative");
        }
        if (stallLimitMillis <= 0L) {
            throw new IllegalArgumentException("stallLimitMillis must be positive");
        }
        if (exactTargetVisible) return Decision.CONTINUE;
        if (calculationFailed) return Decision.RETIRE_CALCULATION_FAILED;
        if (sideGoalOccupied) return Decision.RETIRE_UNUSABLE_ARRIVAL;
        if (millisWithoutProgress >= stallLimitMillis) {
            return Decision.RETIRE_NO_PROGRESS;
        }
        return Decision.CONTINUE;
    }
}
