package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Selects a prospective body position from which an owned workstation can be
 * reclaimed after Baritone clears that position's feet/head cells.
 *
 * <p>The owned target is deliberately never a clearing step. Candidates are
 * exact cardinal, below, or above positions close enough to expose a face once
 * their two-cell body column is open.</p>
 */
public final class WorkstationReclaimAccessPlanner {
    private static final List<Point> OFFSETS = List.of(
            new Point(1, 0, 0), new Point(-1, 0, 0),
            new Point(0, 0, 1), new Point(0, 0, -1),
            new Point(1, -1, 0), new Point(-1, -1, 0),
            new Point(0, -1, 1), new Point(0, -1, -1),
            new Point(0, -2, 0),
            new Point(0, 1, 0),
            new Point(1, 1, 0), new Point(-1, 1, 0),
            new Point(0, 1, 1), new Point(0, 1, -1));

    public Optional<AccessPlan> plan(Request request, Probe probe) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(probe, "probe");
        ArrayList<AccessPlan> candidates = new ArrayList<>();
        for (int index = 0; index < OFFSETS.size(); index++) {
            Point offset = OFFSETS.get(index);
            Point stand = request.target().add(offset);
            Point head = stand.add(0, 1, 0);
            Point floor = stand.add(0, -1, 0);
            if (stand.equals(request.target()) || head.equals(request.target())
                    || request.excludedStands().contains(stand)) {
                continue;
            }
            Cell footCell = probe.cell(stand);
            Cell headCell = probe.cell(head);
            Cell floorCell = probe.cell(floor);
            if (!footCell.bodyClearable() || !headCell.bodyClearable()
                    || !floorCell.supportsBody()) {
                continue;
            }
            ArrayList<Point> clearing = new ArrayList<>(2);
            if (footCell.needsBreak()) clearing.add(stand);
            if (headCell.needsBreak()) clearing.add(head);
            // This invariant is the ownership boundary: an access plan may
            // clear its own body column, never the workstation being reclaimed.
            if (clearing.contains(request.target())) continue;
            double score = stand.squaredDistance(request.origin())
                    + clearing.size() * 2.0
                    + Math.abs(offset.y()) * 0.35;
            candidates.add(new AccessPlan(stand, List.copyOf(clearing), accessRank(index), score));
        }
        return candidates.stream().min(Comparator
                .comparingInt(AccessPlan::rank)
                .thenComparingDouble(AccessPlan::score)
                .thenComparing(plan -> plan.stand().x())
                .thenComparing(plan -> plan.stand().y())
                .thenComparing(plan -> plan.stand().z()));
    }

    private static int accessRank(int offsetIndex) {
        if (offsetIndex < 4) return 0;  // exact same-level cardinal face
        if (offsetIndex < 8) return 1;  // cardinal from one block below
        if (offsetIndex == 8) return 2; // directly below in a shaft
        return 3;                      // top/raised fallback
    }

    public record Request(Point target, Point origin, Set<Point> excludedStands) {
        public Request {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(origin, "origin");
            excludedStands = Set.copyOf(Objects.requireNonNull(excludedStands, "excludedStands"));
        }
    }

    public record AccessPlan(Point stand, List<Point> clearing, int rank, double score) {
        public AccessPlan {
            Objects.requireNonNull(stand, "stand");
            clearing = List.copyOf(Objects.requireNonNull(clearing, "clearing"));
            if (rank < 0) throw new IllegalArgumentException("invalid rank");
            if (!Double.isFinite(score) || score < 0) throw new IllegalArgumentException("invalid score");
        }
    }

    public record Point(int x, int y, int z) {
        public Point add(Point other) {
            return add(other.x, other.y, other.z);
        }

        public Point add(int dx, int dy, int dz) {
            return new Point(x + dx, y + dy, z + dz);
        }

        public long squaredDistance(Point other) {
            long dx = (long) x - other.x;
            long dy = (long) y - other.y;
            long dz = (long) z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    public enum Cell {
        PASSABLE(true, false, false),
        BREAKABLE(true, true, true),
        SOLID_SUPPORT(false, false, true),
        BLOCKED(false, false, false);

        private final boolean bodyClearable;
        private final boolean needsBreak;
        private final boolean supportsBody;

        Cell(boolean bodyClearable, boolean needsBreak, boolean supportsBody) {
            this.bodyClearable = bodyClearable;
            this.needsBreak = needsBreak;
            this.supportsBody = supportsBody;
        }

        public boolean bodyClearable() {
            return bodyClearable;
        }

        public boolean needsBreak() {
            return needsBreak;
        }

        public boolean supportsBody() {
            return supportsBody;
        }
    }

    @FunctionalInterface
    public interface Probe {
        Cell cell(Point position);
    }
}
