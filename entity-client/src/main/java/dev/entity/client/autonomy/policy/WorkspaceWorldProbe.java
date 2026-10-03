package dev.entity.client.autonomy.policy;

import java.util.Objects;
import java.util.Set;

/**
 * Small, Minecraft-independent view of local block geometry used by
 * {@link WorkspacePreparationPlanner}. A Fabric adapter is responsible for
 * mapping loaded world state and honest raycasts into these values.
 */
public interface WorkspaceWorldProbe {
    /**
     * Monotonic local-geometry revision. Increment whenever a relevant block
     * changes so the planner discards candidate-local retry memory and probes
     * the new shape instead of replaying a stale workspace.
     */
    long geometryRevision();

    /** Returns the conservative workspace classification for one block cell. */
    Cell cell(Point point);

    /**
     * Whether this block exposes a full, stable upward face. Workspace
     * placement and standing support use the same physical fact as the live
     * workstation controller instead of inferring support from material type.
     */
    boolean hasFullTopSupport(Point point);

    /**
     * Whether a player-sized body can occupy the feet/head column after the
     * supplied cells have been cleared. Implementations must include actual
     * collision shapes and fluids, not only air/replaceability.
     */
    boolean hasStandCollisionClearance(Point feet, Set<Point> assumedOpen);

    /**
     * Whether the requested workstation face can be seen from the supplied
     * standing position after {@code assumedOpen} cells have been cleared.
     */
    boolean hasSightline(
            Point standFeet,
            Point targetBlock,
            Face targetFace,
            Set<Point> assumedOpen);

    /** Integer block coordinate with dependency-free geometry helpers. */
    record Point(int x, int y, int z) {
        public Point add(int dx, int dy, int dz) {
            return new Point(x + dx, y + dy, z + dz);
        }

        public Point up() {
            return add(0, 1, 0);
        }

        public Point up(int amount) {
            return add(0, amount, 0);
        }

        public Point down() {
            return add(0, -1, 0);
        }

        public int horizontalManhattanDistance(Point other) {
            Objects.requireNonNull(other, "other");
            return Math.abs(x - other.x) + Math.abs(z - other.z);
        }
    }

    /** Face of the future workstation that must be honestly visible. */
    public enum Face {
        NORTH,
        SOUTH,
        EAST,
        WEST,
        UP,
        DOWN
    }

    /**
     * Conservative cell categories. Unknown or mixed states should be mapped
     * to the least permissive applicable category.
     */
    public enum Cell {
        OPEN(true, false, false),
        REPLACEABLE(true, false, false),
        SOLID(false, true, true),
        FLUID(false, false, false),
        FALLING(false, false, false),
        HAZARD(false, false, false),
        UNBREAKABLE(false, true, false),
        PROTECTED(false, true, false),
        UNLOADED(false, false, false);

        private final boolean passable;
        private final boolean stableSupport;
        private final boolean clearable;

        Cell(boolean passable, boolean stableSupport, boolean clearable) {
            this.passable = passable;
            this.stableSupport = stableSupport;
            this.clearable = clearable;
        }

        public boolean passable() {
            return passable;
        }

        public boolean stableSupport() {
            return stableSupport;
        }

        public boolean clearable() {
            return clearable;
        }
    }
}
