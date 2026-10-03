package dev.entity.client.baritone;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure swept-volume safety gate for Baritone edges used during close combat.
 *
 * <p>An endpoint-only check misses diagonal descents whose body footprint crosses the side of a
 * lava cell. This policy samples the complete source-to-destination segment and expands every
 * sample by the player's footprint plus a deliberate horizontal hazard margin. The floor and the
 * full standing body column are checked. Unknown cells fail closed.</p>
 */
final class CombatRouteHazardPolicy {
    static final double PLAYER_HALF_WIDTH = 0.30;
    static final double HORIZONTAL_HAZARD_MARGIN = 0.35;
    static final double PLAYER_HEIGHT = 1.80;
    static final double SAMPLE_STRIDE = 0.20;
    static final int MAX_EDGE_LOOKAHEAD = 2;
    static final double LOW_HEALTH_DESCENT_THRESHOLD = 0.40;
    static final int MAX_LOW_HEALTH_DESCENT_BLOCKS = 1;
    /** Combat may step down terrain, but it may never turn target pursuit into a cliff drop. */
    static final int MAX_COMBAT_DESCENT_BLOCKS = 2;

    private static final double HORIZONTAL_RADIUS =
            PLAYER_HALF_WIDTH + HORIZONTAL_HAZARD_MARGIN;
    private static final double EPSILON = 1.0E-7;

    private CombatRouteHazardPolicy() {
    }

    /** Native search and the eventual pursuit-edge check must share one contract. */
    static int nativeFallLimit(String kind, double healthFraction, int ordinaryLimit) {
        if (!"attack".equalsIgnoreCase(kind) && !"hunt".equalsIgnoreCase(kind)) return ordinaryLimit;
        int pursuitLimit = !Double.isFinite(healthFraction) || healthFraction <= LOW_HEALTH_DESCENT_THRESHOLD
                ? MAX_LOW_HEALTH_DESCENT_BLOCKS : MAX_COMBAT_DESCENT_BLOCKS;
        return Math.min(ordinaryLimit, pursuitLimit);
    }

    static Decision inspect(
            List<Edge> edges,
            double healthFraction,
            CellProbe probe,
            LandingProbe landingProbe) {
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(landingProbe, "landingProbe");
        if (edges.isEmpty()) return Decision.allow();

        boolean lowHealth = !Double.isFinite(healthFraction)
                || healthFraction <= LOW_HEALTH_DESCENT_THRESHOLD;
        int edgeCount = Math.min(edges.size(), MAX_EDGE_LOOKAHEAD + 1);
        Edge current = Objects.requireNonNull(edges.getFirst(), "current edge");
        for (int offset = 0; offset < edgeCount; offset++) {
            Edge edge = Objects.requireNonNull(edges.get(offset), "edge");
            Finding finding = firstUnsafeCell(edge, probe);
            if (finding == null) {
                finding = firstUnsafeDescent(edge, lowHealth, landingProbe);
            }
            if (finding == null) continue;
            if (offset == 0) {
                return new Decision(Action.VETO_CURRENT, offset, edge, finding);
            }
            return new Decision(
                    current.safeToCancel() ? Action.VETO_LOOKAHEAD : Action.DEFER_LOOKAHEAD,
                    offset,
                    edge,
                    finding);
        }
        return Decision.allow();
    }

    private static Finding firstUnsafeDescent(
            Edge edge,
            boolean lowHealth,
            LandingProbe landingProbe) {
        int descentBlocks = edge.source().y() - edge.destination().y();
        if (descentBlocks <= 0) return null;

        Landing landing = Objects.requireNonNull(
                landingProbe.inspect(edge.destination()), "landing observation");
        Disposition landingDisposition = landing.observation().disposition();
        if (landingDisposition != Disposition.SAFE
                && landingDisposition != Disposition.PENDING_BREAK) {
            return new Finding(FindingKind.UNVERIFIED_LANDING,
                    landing.cell(), landing.observation());
        }
        // A loaded solid destination is not a landing yet. Baritone may safely clear that block
        // before entering the cell, and this probe will validate real floor support once the body
        // column becomes passable. Keep the independent low-health depth fence below: deferring
        // support validation must not authorize a dangerous multi-block fall.
        if (descentBlocks > MAX_COMBAT_DESCENT_BLOCKS) {
            return new Finding(
                    FindingKind.EXCESSIVE_COMBAT_DESCENT,
                    edge.destination(),
                    Observation.hazard("combat descent of " + descentBlocks
                            + " blocks exceeds the two-block pursuit limit"));
        }
        if (lowHealth && descentBlocks > MAX_LOW_HEALTH_DESCENT_BLOCKS) {
            return new Finding(
                    FindingKind.LOW_HEALTH_DESCENT,
                    edge.destination(),
                    Observation.hazard("low-health combat descent of "
                            + descentBlocks + " blocks exceeds the one-block limit"));
        }
        return null;
    }

    private static Finding firstUnsafeCell(Edge edge, CellProbe probe) {
        int sampleCount = Math.max(1, (int) Math.ceil(edge.length() / SAMPLE_STRIDE));
        Set<Cell> inspected = new HashSet<>();
        for (int sample = 0; sample <= sampleCount; sample++) {
            double progress = (double) sample / sampleCount;
            double centerX = lerp(edge.source().x() + 0.5, edge.destination().x() + 0.5,
                    progress);
            double feetY = lerp(edge.source().y(), edge.destination().y(), progress);
            double centerZ = lerp(edge.source().z() + 0.5, edge.destination().z() + 0.5,
                    progress);
            int minimumX = floor(centerX - HORIZONTAL_RADIUS);
            int maximumX = floor(centerX + HORIZONTAL_RADIUS - EPSILON);
            int minimumY = floor(feetY - EPSILON);
            int maximumY = floor(feetY + PLAYER_HEIGHT - EPSILON);
            int minimumZ = floor(centerZ - HORIZONTAL_RADIUS);
            int maximumZ = floor(centerZ + HORIZONTAL_RADIUS - EPSILON);
            for (int y = minimumY; y <= maximumY; y++) {
                for (int x = minimumX; x <= maximumX; x++) {
                    for (int z = minimumZ; z <= maximumZ; z++) {
                        Cell cell = new Cell(x, y, z);
                        if (!inspected.add(cell)) continue;
                        Observation observation = Objects.requireNonNull(
                                probe.inspect(cell), "cell observation");
                        if (observation.disposition() != Disposition.SAFE) {
                            return new Finding(FindingKind.SWEPT_HAZARD, cell, observation);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static double lerp(double source, double destination, double progress) {
        return source + (destination - source) * progress;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /**
     * Classifies a fully loaded destination after the world-facing probe has sampled collision.
     * Floor support is authoritative even while Baritone still has body-column blocks to clear:
     * breaking a loaded obstacle must never turn an unsupported descent into an allowed fall.
     */
    static Landing classifyLoadedLanding(
            Cell destinationFeet,
            Cell destinationHead,
            Cell destinationFloor,
            boolean feetObstructed,
            boolean headObstructed,
            boolean floorSupported) {
        Objects.requireNonNull(destinationFeet, "destinationFeet");
        Objects.requireNonNull(destinationHead, "destinationHead");
        Objects.requireNonNull(destinationFloor, "destinationFloor");
        if (!floorSupported) {
            return Landing.unsupported(
                    destinationFloor, "destination floor has no collision support");
        }
        if (feetObstructed) {
            return Landing.pendingBreak(
                    destinationFeet,
                    "destination feet are loaded but obstructed pending Baritone break");
        }
        if (headObstructed) {
            return Landing.pendingBreak(
                    destinationHead,
                    "destination head is loaded but obstructed pending Baritone break");
        }
        return Landing.supported(destinationFloor);
    }

    enum Action {
        ALLOW,
        VETO_CURRENT,
        VETO_LOOKAHEAD,
        DEFER_LOOKAHEAD
    }

    enum Disposition {
        SAFE,
        PENDING_BREAK,
        HAZARD,
        UNKNOWN
    }

    enum FindingKind {
        SWEPT_HAZARD,
        UNVERIFIED_LANDING,
        EXCESSIVE_COMBAT_DESCENT,
        LOW_HEALTH_DESCENT
    }

    record Cell(int x, int y, int z) {
    }

    record Edge(
            String routeSignature,
            String movementClass,
            Cell source,
            Cell destination,
            boolean safeToCancel) {
        Edge {
            routeSignature = Objects.requireNonNullElse(routeSignature, "").trim();
            movementClass = Objects.requireNonNullElse(movementClass, "unknown").trim();
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
        }

        private double length() {
            double dx = destination.x() - source.x();
            double dy = destination.y() - source.y();
            double dz = destination.z() - source.z();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    record Observation(Disposition disposition, String detail) {
        Observation {
            Objects.requireNonNull(disposition, "disposition");
            detail = Objects.requireNonNullElse(detail, "").trim();
        }

        static Observation safe() {
            return new Observation(Disposition.SAFE, "");
        }

        static Observation pendingBreak(String detail) {
            return new Observation(Disposition.PENDING_BREAK, detail);
        }

        static Observation hazard(String type) {
            return new Observation(Disposition.HAZARD, type);
        }

        static Observation unknown(String reason) {
            return new Observation(Disposition.UNKNOWN, reason);
        }
    }

    record Landing(Cell cell, Observation observation) {
        Landing {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(observation, "observation");
        }

        static Landing supported(Cell floor) {
            return new Landing(floor, Observation.safe());
        }

        static Landing pendingBreak(Cell obstructedCell, String detail) {
            return new Landing(obstructedCell, Observation.pendingBreak(detail));
        }

        static Landing unsupported(Cell cell, String reason) {
            return new Landing(cell, Observation.hazard(reason));
        }

        static Landing unknown(Cell cell, String reason) {
            return new Landing(cell, Observation.unknown(reason));
        }
    }

    record Finding(FindingKind kind, Cell cell, Observation observation) {
        Finding {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(observation, "observation");
        }
    }

    record Decision(Action action, int edgeOffset, Edge edge, Finding finding) {
        Decision {
            Objects.requireNonNull(action, "action");
            if (action != Action.ALLOW) {
                Objects.requireNonNull(edge, "edge");
                Objects.requireNonNull(finding, "finding");
            }
        }

        static Decision allow() {
            return new Decision(Action.ALLOW, -1, null, null);
        }

        boolean veto() {
            return action == Action.VETO_CURRENT || action == Action.VETO_LOOKAHEAD;
        }
    }

    @FunctionalInterface
    interface CellProbe {
        Observation inspect(Cell cell);
    }

    @FunctionalInterface
    interface LandingProbe {
        Landing inspect(Cell destinationFeet);
    }
}
