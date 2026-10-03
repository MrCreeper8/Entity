package dev.entity.client.baritone;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import dev.entity.core.farm.ManagedFarmPolicy;

import java.util.Objects;

/**
 * A horizontal-only arrival region.
 *
 * <p>{@link GoalXZ} requires one exact X/Z column. That is too strict for a
 * coverage/loading waypoint: Baritone may already be beside the requested
 * column with the relevant chunks loaded, while the next exact interaction
 * route is responsible for choosing a real three-dimensional stance.</p>
 */
final class GoalNearXZ implements Goal {
    private final int x;
    private final int z;
    private final int range;

    GoalNearXZ(int x, int z, int range) {
        if (range < 0) throw new IllegalArgumentException("range cannot be negative");
        this.x = x;
        this.z = z;
        this.range = range;
    }

    @Override
    public boolean isInGoal(int candidateX, int ignoredY, int candidateZ) {
        return ManagedFarmPolicy.horizontalArrivalSatisfied(
                x, z, candidateX, candidateZ, range);
    }

    @Override
    public double heuristic(int candidateX, int ignoredY, int candidateZ) {
        double dx = Math.max(0.0D, Math.abs((double) candidateX - x) - range);
        double dz = Math.max(0.0D, Math.abs((double) candidateZ - z) - range);
        return GoalXZ.calculate(dx, dz);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GoalNearXZ goal)) return false;
        return x == goal.x && z == goal.z && range == goal.range;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, z, range);
    }

    @Override
    public String toString() {
        return "GoalNearXZ{x=" + x + ",z=" + z + ",range=" + range + '}';
    }
}
