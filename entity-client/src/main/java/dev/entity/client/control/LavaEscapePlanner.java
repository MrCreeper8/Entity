package dev.entity.client.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Bounded weighted 3D planner for emergency lava escape.
 *
 * <p>Lava remains traversable because the player can start several cells inside a pool.  It is
 * heavily penalized, while a supported dry cell or water is a terminal exit.  This differs from
 * the old reflex which selected {@code UP} every tick and could bob in one column until death.</p>
 */
public final class LavaEscapePlanner {
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {0, 1, 0}, {0, -1, 0}
    };

    private LavaEscapePlanner() {
    }

    public static Result plan(Point start, Probe probe, Limits limits, Set<Point> rejected) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(limits, "limits");
        Set<Point> blocked = rejected == null ? Set.of() : Set.copyOf(rejected);

        PriorityQueue<Node> frontier = new PriorityQueue<>();
        Map<Point, Integer> best = new HashMap<>();
        Map<Point, Point> previous = new HashMap<>();
        Set<Point> visited = new HashSet<>();
        long sequence = 0;
        frontier.add(new Node(start, 0, sequence++));
        best.put(start, 0);

        while (!frontier.isEmpty() && visited.size() < limits.maximumVisited()) {
            Node node = frontier.remove();
            if (!visited.add(node.point())) continue;
            for (int[] direction : DIRECTIONS) {
                Point next = new Point(
                        node.point().x() + direction[0],
                        node.point().y() + direction[1],
                        node.point().z() + direction[2]);
                if (!within(start, next, limits) || blocked.contains(next) || visited.contains(next)) {
                    continue;
                }
                Cell cell = Objects.requireNonNull(probe.cell(next), "probe cell");
                if (cell == Cell.BLOCKED || cell == Cell.UNLOADED || cell == Cell.HAZARD) continue;
                int nextCost = node.cost() + movementCost(cell, direction[1]);
                if (nextCost >= best.getOrDefault(next, Integer.MAX_VALUE)) continue;
                best.put(next, nextCost);
                previous.put(next, node.point());
                if (cell == Cell.SAFE || cell == Cell.WATER) {
                    return new Result(reconstruct(start, next, previous), visited.size(), true,
                            cell, nextCost);
                }
                frontier.add(new Node(next, nextCost, sequence++));
            }
        }
        return new Result(List.of(), visited.size(), false, Cell.BLOCKED, Integer.MAX_VALUE);
    }

    private static int movementCost(Cell cell, int vertical) {
        int cost = switch (cell) {
            case WATER -> 1;
            case SAFE -> 2;
            case OPEN -> 3;
            case LAVA -> 12;
            case BLOCKED, HAZARD, UNLOADED -> Integer.MAX_VALUE / 4;
        };
        if (vertical > 0) cost += 2;
        if (vertical < 0) cost += 5;
        return cost;
    }

    private static boolean within(Point start, Point candidate, Limits limits) {
        return Math.abs(candidate.x() - start.x()) <= limits.horizontalRadius()
                && Math.abs(candidate.z() - start.z()) <= limits.horizontalRadius()
                && candidate.y() - start.y() <= limits.upwardRadius()
                && start.y() - candidate.y() <= limits.downwardRadius();
    }

    private static List<Point> reconstruct(
            Point start,
            Point goal,
            Map<Point, Point> previous) {
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
        LAVA,
        OPEN,
        SAFE,
        WATER,
        BLOCKED,
        HAZARD,
        UNLOADED
    }

    public record Point(int x, int y, int z) {
    }

    public record Limits(
            int horizontalRadius,
            int upwardRadius,
            int downwardRadius,
            int maximumVisited) {
        public Limits {
            if (horizontalRadius < 1 || upwardRadius < 1 || downwardRadius < 0
                    || maximumVisited < 1) {
                throw new IllegalArgumentException("Invalid lava escape limits");
            }
        }
    }

    public record Result(
            List<Point> path,
            int visitedCells,
            boolean found,
            Cell exitCell,
            int totalCost) {
        public Result {
            path = List.copyOf(path);
        }
    }

    private record Node(Point point, int cost, long sequence) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) {
            int byCost = Integer.compare(cost, other.cost);
            return byCost != 0 ? byCost : Long.compare(sequence, other.sequence);
        }
    }
}
