package dev.entity.client.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded deterministic 3D search used by the drowning reflex. */
public final class GridEscapePlanner {
    private static final int[][] DIRECTIONS = {
            {0, 1, 0},
            {1, 0, 0},
            {-1, 0, 0},
            {0, 0, 1},
            {0, 0, -1},
            {0, -1, 0}
    };

    private GridEscapePlanner() {
    }

    public static Result plan(Point start, Probe probe, Limits limits) {
        return plan(start, probe, limits, point -> probe.cell(point) == Cell.BREATHABLE, false);
    }

    /** Same bounded search, with a dry-shore goal instead of the first brief air sample. */
    public static Result plan(Point start, Probe probe, Limits limits,
                              java.util.function.Predicate<Point> goal,
                              boolean traverseBreathable) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(limits, "limits");

        ArrayDeque<Point> queue = new ArrayDeque<>();
        Set<Point> visited = new HashSet<>();
        Map<Point, Point> previous = new HashMap<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty() && visited.size() <= limits.maximumVisited()) {
            Point current = queue.removeFirst();
            for (int[] direction : DIRECTIONS) {
                Point next = new Point(
                        current.x() + direction[0],
                        current.y() + direction[1],
                        current.z() + direction[2]);
                if (!within(start, next, limits) || !visited.add(next)) continue;
                Cell cell = Objects.requireNonNull(probe.cell(next), "probe cell");
                if (cell == Cell.BLOCKED || cell == Cell.HAZARD || cell == Cell.UNLOADED) continue;
                previous.put(next, current);
                if (goal.test(next)) {
                    return new Result(reconstruct(start, next, previous), visited.size(), true);
                }
                if (cell == Cell.WATER || traverseBreathable && cell == Cell.BREATHABLE)
                    queue.addLast(next);
            }
        }
        return new Result(List.of(), visited.size(), false);
    }

    private static boolean within(Point start, Point candidate, Limits limits) {
        return Math.abs(candidate.x() - start.x()) <= limits.horizontalRadius()
                && candidate.y() - start.y() <= limits.upwardRadius()
                && start.y() - candidate.y() <= limits.downwardRadius();
    }

    private static List<Point> reconstruct(Point start, Point goal, Map<Point, Point> previous) {
        ArrayDeque<Point> reversed = new ArrayDeque<>();
        Point cursor = goal;
        while (!cursor.equals(start)) {
            reversed.addFirst(cursor);
            cursor = previous.get(cursor);
            if (cursor == null) return List.of();
        }
        return List.copyOf(new ArrayList<>(reversed));
    }

    public interface Probe {
        Cell cell(Point point);
    }

    public enum Cell {
        WATER,
        BREATHABLE,
        BLOCKED,
        HAZARD,
        UNLOADED
    }

    public record Point(int x, int y, int z) {
    }

    public record Limits(int horizontalRadius, int upwardRadius, int downwardRadius, int maximumVisited) {
        public Limits {
            if (horizontalRadius < 1 || upwardRadius < 1 || downwardRadius < 0 || maximumVisited < 1) {
                throw new IllegalArgumentException("Invalid escape-search limits");
            }
        }
    }

    public record Result(List<Point> path, int visitedCells, boolean found) {
        public Result {
            path = List.copyOf(path);
        }
    }
}
