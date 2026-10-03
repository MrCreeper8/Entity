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
 * Bounded supported-ground planner used after the body has escaped lava but is
 * still burning.
 *
 * <p>The previous reflex faced the first water block found by a box scan, or
 * simply held forward when there was none. Neither choice proved a traversable
 * route, and the production trace showed it sprinting from a dry exit straight
 * back into the lava it had just escaped. This planner only traverses loaded,
 * body-clear cells with safe support. Water is the only extinguishing
 * terminal. A dry cell can be a stationary terminal only when a conservative
 * calculation proves that the player can survive the remaining vanilla burn;
 * otherwise it is merely the safest bounded fallback while the caller keeps
 * rescanning for water.</p>
 */
public final class FireEscapePlanner {
    /** Vanilla applies ordinary fire damage on a 20-tick cadence. */
    static final int FIRE_DAMAGE_INTERVAL_TICKS = 20;
    /** Ignore armor/enchantment mitigation: ordinary fire may consume one health per hit. */
    static final float CONSERVATIVE_DAMAGE_PER_HIT = 1.0F;
    /** Do not call a dry hold survivable unless two hearts remain after the predicted burn. */
    static final float DRY_HOLD_HEALTH_RESERVE = 4.0F;
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {0, 1, 0}, {0, -1, 0},
            {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
            {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1}
    };

    private FireEscapePlanner() {
    }

    public static Result plan(
            Point start,
            Probe probe,
            Limits limits,
            BurnSurvivability burn,
            Set<Point> rejected) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(burn, "burn");
        Set<Point> blocked = rejected == null ? Set.of() : Set.copyOf(rejected);

        PriorityQueue<Node> frontier = new PriorityQueue<>();
        Map<Point, Integer> bestCost = new HashMap<>();
        Map<Point, Point> previous = new HashMap<>();
        Map<Point, Cell> observed = new HashMap<>();
        Set<Point> visited = new HashSet<>();
        long sequence = 0L;
        frontier.add(new Node(start, 0, sequence++));
        bestCost.put(start, 0);

        Point bestEffort = null;
        Cell bestEffortCell = null;
        int bestEffortCost = Integer.MAX_VALUE;
        Point bestDryTerminal = null;
        int bestDryTerminalCost = Integer.MAX_VALUE;

        Cell startCell = Objects.requireNonNull(probe.cell(start), "start probe cell");
        observed.put(start, startCell);
        if (startCell == Cell.WATER) {
            return new Result(
                    List.of(start), 1, true, Cell.WATER, TerminalKind.WATER, 0);
        }
        if (startCell == Cell.SAFE) {
            bestDryTerminal = start;
            bestDryTerminalCost = 0;
        }

        while (!frontier.isEmpty() && visited.size() < limits.maximumVisited()) {
            Node node = frontier.remove();
            if (!visited.add(node.point())) continue;
            for (int[] direction : DIRECTIONS) {
                Point next = new Point(
                        node.point().x() + direction[0],
                        node.point().y() + direction[1],
                        node.point().z() + direction[2]);
                if (!within(start, next, limits)
                        || blocked.contains(next)
                        || visited.contains(next)) {
                    continue;
                }
                Cell cell = observed.computeIfAbsent(
                        next, point -> Objects.requireNonNull(probe.cell(point), "probe cell"));
                if (!cell.traversable()) continue;
                int nextCost = node.cost() + movementCost(cell, direction[1]);
                if (nextCost >= bestCost.getOrDefault(next, Integer.MAX_VALUE)) continue;
                bestCost.put(next, nextCost);
                previous.put(next, node.point());

                if (cell == Cell.WATER) {
                    return new Result(
                            reconstruct(start, next, previous),
                            visited.size(),
                            true,
                            cell,
                            TerminalKind.WATER,
                            nextCost);
                }
                // A dry cell outside the hazard margin is a valid fallback,
                // but it does not extinguish the player. Keep searching the
                // bounded graph for water and use the cheapest dry terminal
                // only when no reachable water exists. Returning the first
                // SAFE neighbor reproduced a subtle version of the old bug:
                // Entity could stand at four health and burn beside water.
                if (cell == Cell.SAFE && nextCost < bestDryTerminalCost) {
                    bestDryTerminal = next;
                    bestDryTerminalCost = nextCost;
                }

                if (betterFallback(cell, nextCost, bestEffortCell, bestEffortCost)) {
                    bestEffort = next;
                    bestEffortCell = cell;
                    bestEffortCost = nextCost;
                }
                frontier.add(new Node(next, nextCost, sequence++));
            }
        }

        if (bestDryTerminal != null) {
            boolean survivable = burn.dryHoldSurvivable();
            return new Result(
                    bestDryTerminal.equals(start)
                            ? List.of(start)
                            : reconstruct(start, bestDryTerminal, previous),
                    visited.size(),
                    survivable,
                    Cell.SAFE,
                    survivable
                            ? TerminalKind.SURVIVABLE_DRY_HOLD
                            : TerminalKind.DRY_FALLBACK,
                    bestDryTerminalCost);
        }

        List<Point> partial = bestEffort == null
                ? List.of()
                : reconstruct(start, bestEffort, previous);
        return new Result(
                partial,
                visited.size(),
                false,
                bestEffortCell == null ? Cell.BLOCKED : bestEffortCell,
                bestEffort == null ? TerminalKind.NONE : TerminalKind.FRONTIER,
                bestEffortCost);
    }

    /**
     * Pure, deliberately pessimistic burn forecast. The exact remaining fire
     * ticks come from {@code Entity#getFireTicks()}. The extra cadence hit
     * covers observing a frame immediately before vanilla's damage phase; the
     * separate health reserve covers ordinary combat/world jitter rather than
     * pretending that a mathematically non-lethal dry wait is operationally
     * safe.
     */
    public static BurnSurvivability assessBurn(float health, int fireTicks) {
        if (!Float.isFinite(health) || health < 0.0F) {
            throw new IllegalArgumentException("health must be finite and non-negative");
        }
        int remainingTicks = Math.max(0, fireTicks);
        int cadenceHits = Math.floorDiv(remainingTicks, FIRE_DAMAGE_INTERVAL_TICKS)
                + (remainingTicks % FIRE_DAMAGE_INTERVAL_TICKS == 0 ? 0 : 1);
        int predictedHits = remainingTicks == 0 ? 0 : Math.addExact(cadenceHits, 1);
        float predictedDamage = predictedHits * CONSERVATIVE_DAMAGE_PER_HIT;
        boolean survivable = health - predictedDamage >= DRY_HOLD_HEALTH_RESERVE;
        return new BurnSurvivability(
                fireTicks,
                health,
                predictedHits,
                predictedDamage,
                DRY_HOLD_HEALTH_RESERVE,
                survivable);
    }

    private static boolean betterFallback(
            Cell candidate,
            int candidateCost,
            Cell current,
            int currentCost) {
        if (current == null) return true;
        int bySafety = Integer.compare(candidate.safetyRank(), current.safetyRank());
        return bySafety > 0 || (bySafety == 0 && candidateCost < currentCost);
    }

    private static int movementCost(Cell cell, int vertical) {
        int cost = switch (cell) {
            case WATER -> 1;
            case SAFE -> 2;
            case MARGIN -> 5;
            case NEAR_HAZARD -> 12;
            case BLOCKED, HAZARD, UNLOADED -> Integer.MAX_VALUE / 4;
        };
        if (vertical > 0) cost += 3;
        if (vertical < 0) cost += 4;
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
        WATER(true, true, 4),
        SAFE(true, true, 3),
        MARGIN(true, false, 2),
        NEAR_HAZARD(true, false, 1),
        BLOCKED(false, false, 0),
        HAZARD(false, false, 0),
        UNLOADED(false, false, 0);

        private final boolean traversable;
        private final boolean terminal;
        private final int safetyRank;

        Cell(boolean traversable, boolean terminal, int safetyRank) {
            this.traversable = traversable;
            this.terminal = terminal;
            this.safetyRank = safetyRank;
        }

        boolean traversable() {
            return traversable;
        }

        boolean terminal() {
            return terminal;
        }

        int safetyRank() {
            return safetyRank;
        }
    }

    public enum TerminalKind {
        WATER,
        SURVIVABLE_DRY_HOLD,
        DRY_FALLBACK,
        FRONTIER,
        NONE;

        public boolean extinguishing() {
            return this == WATER;
        }

        public boolean stationaryTerminal() {
            return this == WATER || this == SURVIVABLE_DRY_HOLD;
        }
    }

    public record Point(int x, int y, int z) {
    }

    public record Limits(
            int horizontalRadius,
            int upwardRadius,
            int downwardRadius,
            int maximumVisited) {
        public Limits {
            if (horizontalRadius < 1 || upwardRadius < 0 || downwardRadius < 0
                    || maximumVisited < 1) {
                throw new IllegalArgumentException("Invalid fire-escape limits");
            }
        }
    }

    public record BurnSurvivability(
            int fireTicks,
            float currentHealth,
            int predictedRemainingHits,
            float predictedDamage,
            float healthReserve,
            boolean dryHoldSurvivable) {
        public BurnSurvivability {
            if (!Float.isFinite(currentHealth) || currentHealth < 0.0F
                    || predictedRemainingHits < 0
                    || !Float.isFinite(predictedDamage) || predictedDamage < 0.0F
                    || !Float.isFinite(healthReserve) || healthReserve < 0.0F) {
                throw new IllegalArgumentException("Invalid burn-survivability forecast");
            }
        }
    }

    public record Result(
            List<Point> path,
            int visitedCells,
            boolean found,
            Cell exitCell,
            TerminalKind terminalKind,
            int totalCost) {
        public Result {
            path = List.copyOf(path);
            Objects.requireNonNull(exitCell, "exitCell");
            Objects.requireNonNull(terminalKind, "terminalKind");
            if (terminalKind == TerminalKind.WATER && exitCell != Cell.WATER) {
                throw new IllegalArgumentException("water terminal must end in water");
            }
            if (terminalKind == TerminalKind.SURVIVABLE_DRY_HOLD
                    && (exitCell != Cell.SAFE || !found)) {
                throw new IllegalArgumentException("dry terminal must be found safe ground");
            }
            if (terminalKind == TerminalKind.DRY_FALLBACK && found) {
                throw new IllegalArgumentException("dry fallback cannot claim terminal success");
            }
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
