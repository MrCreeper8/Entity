package dev.entity.client.baritone;

import dev.entity.client.control.GridEscapePlanner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minecraft-independent verification used at the water/world adapter boundary.
 *
 * <p>A center-column depth sample is not enough to descend when the player's hitbox is still
 * supported by a shelf. Likewise, retaining a destination across a breathing interruption is not
 * evidence that another underwater leg fits inside the air budget. This policy turns the loaded
 * block facts supplied by an adapter into those two explicit proofs.</p>
 */
public final class AquaticRouteEnvironmentPolicy {
    static final int AIR_TICKS_PER_ROUTE_STEP = 8;
    static final int REDIVE_AIR_RESERVE_TICKS = 60;
    static final int REDIVE_RECOVERY_MARGIN_TICKS = 20;
    static final double REDIVE_CORRIDOR_HALF_WIDTH = 0.76;
    static final double REDIVE_MINIMUM_FORWARD_ENDPOINT = 0.65;

    public enum RedivePermission {
        NOT_RESUMING,
        WAIT_FOR_AIR,
        AIR_BUDGETED_ENDPOINT,
        VERIFIED_RETURN_ROUTE,
        ROUTE_BLOCKED
    }

    /** One potential body-centered deep-water column observed by the world adapter. */
    public record DeepWaterCandidate(
            int blockX,
            int blockY,
            int blockZ,
            boolean loaded,
            boolean waterAtFeet,
            boolean waterBelowFeet,
            boolean belowClear,
            boolean feetClear,
            boolean headClear) {
        boolean verified() {
            return loaded && waterAtFeet && waterBelowFeet
                    && belowClear && feetClear && headClear;
        }
    }

    /** The exact center toward which a supported hitbox may safely step before descending. */
    public record DeepWaterEntry(
            boolean verified,
            int blockX,
            int blockY,
            int blockZ,
            double centerX,
            double centerZ,
            double steeringX,
            double steeringZ) {
        public static DeepWaterEntry none() {
            return new DeepWaterEntry(false, 0, 0, 0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** Conservative evidence attached to one post-breathing route frame. */
    public record RediveAssessment(
            RedivePermission permission,
            int requiredAirTicks,
            String reason,
            List<GridEscapePlanner.Point> committedPath) {
        public RediveAssessment(
                RedivePermission permission,
                int requiredAirTicks,
                String reason) {
            this(permission, requiredAirTicks, reason, List.of());
        }

        public RediveAssessment {
            permission = permission == null ? RedivePermission.NOT_RESUMING : permission;
            requiredAirTicks = Math.max(0, requiredAirTicks);
            reason = Objects.requireNonNullElse(reason, "no redive assessment");
            committedPath = committedPath == null ? List.of() : List.copyOf(committedPath);
        }

        public static RediveAssessment notResuming() {
            return new RediveAssessment(
                    RedivePermission.NOT_RESUMING, 0, "route is not resuming after breathing");
        }

        public static RediveAssessment blocked(String reason) {
            return new RediveAssessment(RedivePermission.ROUTE_BLOCKED, 0, reason);
        }

        public boolean permitsRedive() {
            return !committedPath.isEmpty()
                    && (permission == RedivePermission.AIR_BUDGETED_ENDPOINT
                    || permission == RedivePermission.VERIFIED_RETURN_ROUTE);
        }
    }

    /**
     * Retains the exact path that justified a redive until its breathable terminal is observed.
     * Fabric asks this object for the next waypoint; it cannot accidentally recover the unrelated
     * mission target merely because the policy returned an allow enum on an earlier frame.
     */
    public static final class RediveCommitment {
        private final ArrayDeque<GridEscapePlanner.Point> remaining = new ArrayDeque<>();
        private RediveAssessment assessment = RediveAssessment.notResuming();

        public void install(RediveAssessment next) {
            remaining.clear();
            assessment = next == null ? RediveAssessment.notResuming() : next;
            if (assessment.permitsRedive()) remaining.addAll(assessment.committedPath());
        }

        public void clear() {
            remaining.clear();
            assessment = RediveAssessment.notResuming();
        }

        public boolean active() {
            return !remaining.isEmpty();
        }

        public GridEscapePlanner.Point waypoint() {
            return remaining.peekFirst();
        }

        public RediveAssessment assessment() {
            return assessment;
        }

        public List<GridEscapePlanner.Point> remainingPath() {
            return List.copyOf(remaining);
        }

        public void observe(PathObservation observation) {
            if (observation == null) return;
            while (!remaining.isEmpty()) {
                GridEscapePlanner.Point waypoint = remaining.peekFirst();
                boolean terminal = remaining.size() == 1;
                boolean sameEyeBlock = observation.eyeBlockX() == waypoint.x()
                        && observation.eyeBlockY() == waypoint.y()
                        && observation.eyeBlockZ() == waypoint.z();
                if (terminal) {
                    if (!observation.headSubmerged() && sameEyeBlock) {
                        remaining.removeFirst();
                        assessment = RediveAssessment.notResuming();
                    }
                    break;
                }
                double deltaX = observation.eyeX() - (waypoint.x() + 0.5);
                double deltaY = observation.eyeY() - (waypoint.y() + 0.5);
                double deltaZ = observation.eyeZ() - (waypoint.z() + 0.5);
                double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
                if (!sameEyeBlock && distanceSquared > 0.45 * 0.45) break;
                remaining.removeFirst();
            }
        }
    }

    public record PathObservation(
            int eyeBlockX,
            int eyeBlockY,
            int eyeBlockZ,
            double eyeX,
            double eyeY,
            double eyeZ,
            boolean headSubmerged) { }

    /** Preserves the server-observed body support independently of center-cell water depth. */
    public static boolean supportingFloor(boolean onGround) {
        return onGround;
    }

    /**
     * Selects a verified adjacent (or current, when the hitbox straddles its edge) deep column.
     * Route alignment is preferred, then the shortest centering step. No unverified candidate can
     * win merely because its center-point depth looks favorable.
     */
    public static DeepWaterEntry selectDeepWaterEntry(
            double playerX,
            double playerZ,
            double routeX,
            double routeZ,
            List<DeepWaterCandidate> candidates) {
        if (!Double.isFinite(playerX) || !Double.isFinite(playerZ)
                || candidates == null || candidates.isEmpty()) {
            return DeepWaterEntry.none();
        }
        double routeLength = Math.hypot(routeX, routeZ);
        double routeUnitX = routeLength > 0.001 && Double.isFinite(routeLength)
                ? routeX / routeLength : 0.0;
        double routeUnitZ = routeLength > 0.001 && Double.isFinite(routeLength)
                ? routeZ / routeLength : 0.0;

        DeepWaterCandidate selected = null;
        double selectedAlignment = Double.NEGATIVE_INFINITY;
        double selectedDistance = Double.POSITIVE_INFINITY;
        for (DeepWaterCandidate candidate : candidates) {
            if (candidate == null || !candidate.verified()) continue;
            double centerX = candidate.blockX() + 0.5;
            double centerZ = candidate.blockZ() + 0.5;
            double dx = centerX - playerX;
            double dz = centerZ - playerZ;
            double distance = Math.hypot(dx, dz);
            double alignment = distance > 0.001
                    ? dx / distance * routeUnitX + dz / distance * routeUnitZ
                    : 1.0;
            if (selected == null
                    || alignment > selectedAlignment + 1.0e-6
                    || (Math.abs(alignment - selectedAlignment) <= 1.0e-6
                    && distance < selectedDistance)) {
                selected = candidate;
                selectedAlignment = alignment;
                selectedDistance = distance;
            }
        }
        if (selected == null) return DeepWaterEntry.none();
        double centerX = selected.blockX() + 0.5;
        double centerZ = selected.blockZ() + 0.5;
        return new DeepWaterEntry(
                true,
                selected.blockX(),
                selected.blockY(),
                selected.blockZ(),
                centerX,
                centerZ,
                centerX - playerX,
                centerZ - playerZ);
    }

    /**
     * Verifies a loaded underwater leg from a candidate entry column. The breathable endpoint and
     * every traversed cell must remain inside the imminent route's narrow forward corridor. The
     * returned path is an actuator commitment, not merely evidence that some unrelated pocket is
     * reachable elsewhere in the local water graph.
     */
    public static RediveAssessment assessRediveRoute(
            int air,
            int maximumAir,
            DeepWaterEntry entry,
            GridEscapePlanner.Point currentAirPocket,
            double routeX,
            double routeZ,
            GridEscapePlanner.Probe probe,
            GridEscapePlanner.Limits limits) {
        if (maximumAir <= 0 || air < Math.max(0, maximumAir - REDIVE_RECOVERY_MARGIN_TICKS)) {
            return new RediveAssessment(
                    RedivePermission.WAIT_FOR_AIR,
                    Math.max(0, maximumAir - REDIVE_RECOVERY_MARGIN_TICKS),
                    "remaining at the waterline until the air meter is restored");
        }
        if (entry == null || !entry.verified() || currentAirPocket == null
                || probe == null || limits == null) {
            return RediveAssessment.blocked(
                    "aquatic route blocked at air: no verified adjacent deep-water entry");
        }

        GridEscapePlanner.Point start = new GridEscapePlanner.Point(
                entry.blockX(), entry.blockY(), entry.blockZ());
        RouteCorridor corridor = RouteCorridor.create(start, routeX, routeZ, limits);
        if (corridor == null) {
            return RediveAssessment.blocked(
                    "aquatic route blocked at air: no finite forward route corridor can be verified");
        }
        GridEscapePlanner.Cell startCell = Objects.requireNonNull(
                probe.cell(start), "redive start cell");
        if (startCell != GridEscapePlanner.Cell.WATER) {
            return RediveAssessment.blocked(
                    "aquatic route blocked at air: the verified entry column changed");
        }

        GridEscapePlanner.Result endpoint = GridEscapePlanner.plan(
                start,
                point -> {
                    GridEscapePlanner.Cell cell = Objects.requireNonNull(
                            probe.cell(point), "redive probe cell");
                    if (!corridor.contains(point)) return GridEscapePlanner.Cell.BLOCKED;
                    if (cell == GridEscapePlanner.Cell.BREATHABLE
                            && (point.equals(currentAirPocket)
                            || !corridor.forwardEndpoint(point))) {
                        return GridEscapePlanner.Cell.BLOCKED;
                    }
                    return cell;
                },
                limits);
        ArrayList<GridEscapePlanner.Point> committedPath = new ArrayList<>();
        if (endpoint.found()) {
            committedPath.add(start);
            committedPath.addAll(endpoint.path());
        }
        RediveAssessment endpointAssessment = assessBudget(
                air,
                committedPath,
                RedivePermission.AIR_BUDGETED_ENDPOINT,
                "re-diving through the committed forward corridor to its air-budgeted breathable endpoint");
        if (endpointAssessment != null) return endpointAssessment;

        return RediveAssessment.blocked(
                "aquatic route blocked at air: the imminent forward corridor has no actuator-bound breathable endpoint within budget");
    }

    private static RediveAssessment assessBudget(
            int air,
            List<GridEscapePlanner.Point> committedPath,
            RedivePermission permission,
            String reason) {
        if (committedPath == null || committedPath.isEmpty()) return null;
        long routeTicks = (long) committedPath.size() * AIR_TICKS_PER_ROUTE_STEP;
        long required = routeTicks + REDIVE_AIR_RESERVE_TICKS;
        if (required > air || required > Integer.MAX_VALUE) return null;
        return new RediveAssessment(permission, (int) required, reason, committedPath);
    }

    private record RouteCorridor(
            GridEscapePlanner.Point start,
            double unitX,
            double unitZ,
            double maximumForward) {
        static RouteCorridor create(
                GridEscapePlanner.Point start,
                double routeX,
                double routeZ,
                GridEscapePlanner.Limits limits) {
            double routeLength = Math.hypot(routeX, routeZ);
            if (!Double.isFinite(routeLength) || routeLength < REDIVE_MINIMUM_FORWARD_ENDPOINT) {
                return null;
            }
            return new RouteCorridor(
                    start,
                    routeX / routeLength,
                    routeZ / routeLength,
                    Math.min(routeLength + 0.75, limits.horizontalRadius() + 0.75));
        }

        boolean contains(GridEscapePlanner.Point point) {
            double dx = point.x() - start.x();
            double dz = point.z() - start.z();
            double forward = dx * unitX + dz * unitZ;
            double lateral = Math.abs(dx * unitZ - dz * unitX);
            return forward >= -0.25
                    && forward <= maximumForward
                    && lateral <= REDIVE_CORRIDOR_HALF_WIDTH;
        }

        boolean forwardEndpoint(GridEscapePlanner.Point point) {
            double dx = point.x() - start.x();
            double dz = point.z() - start.z();
            return dx * unitX + dz * unitZ >= REDIVE_MINIMUM_FORWARD_ENDPOINT;
        }
    }

    private AquaticRouteEnvironmentPolicy() { }
}
