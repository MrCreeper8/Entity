package dev.entity.client.autonomy.policy;

import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.Cell;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.Outcome;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.PortalEdge;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.PortalKind;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.Region;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.RegionKind;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.Scan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Selects a real, nearby Home room as temporary shelter from exterior pressure.
 *
 * <p>This policy consumes only the bounded topology already produced by
 * {@link HomeInteriorGeometryProbe}. It never searches for a house, invents an
 * unloaded doorway, or treats a roof as a room. Entry is admitted only through
 * a closed wooden door connecting Entity's exact exterior component to the
 * owner-selected Home room. Every loaded hostile contributes to route
 * clearance, not merely the target that happened to win combat arbitration.</p>
 */
public final class HomeThreatShelterPolicy {
    public static final double MINIMUM_ROUTE_THREAT_CLEARANCE = 4.25;
    public static final double MAXIMUM_PLAYER_HOME_DISTANCE = 12.0;
    public static final double MAXIMUM_PRIMARY_THREAT_HOME_DISTANCE = 16.0;
    public static final double MAXIMUM_PRIMARY_THREAT_VERTICAL_DELTA = 4.0;
    private static final double CELL_MATCH_DISTANCE = 1.55;
    private static final double EPSILON = 1.0e-6;

    private static final List<int[]> HORIZONTAL_DIRECTIONS = List.of(
            new int[]{-1, 0}, new int[]{0, -1},
            new int[]{0, 1}, new int[]{1, 0});

    private HomeThreatShelterPolicy() {
    }

    public static Decision decide(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (!observation.topologyAvailable()
                || observation.scan().outcome() != Outcome.COMPLETE) {
            return Decision.reject(
                    Code.TOPOLOGY_UNAVAILABLE,
                    "bounded Home room topology is unavailable or incomplete");
        }

        Threat primary = observation.threats().stream()
                .filter(Threat::active)
                .filter(threat -> threat.id().equals(observation.primaryThreatId()))
                .findFirst()
                .orElse(null);
        if (primary == null) {
            return Decision.reject(Code.NO_ACTIVE_PRESSURE,
                    "the selected protection threat is no longer active");
        }

        Scan scan = observation.scan();
        if (scan.homeRegionId().isEmpty()) {
            return Decision.reject(Code.NO_QUALIFIED_HOME_ROOM,
                    "the owner-selected Home cell is not in a proved room");
        }
        int homeRegionId = scan.homeRegionId().orElseThrow();
        Map<Integer, Region> regions = indexRegions(scan.regions());
        Region home = regions.get(homeRegionId);
        if (home == null || home.kind() != RegionKind.INTERIOR_ROOM) {
            return Decision.reject(Code.NO_QUALIFIED_HOME_ROOM,
                    "the owner-selected Home region is not a proved enclosed room");
        }

        Map<Cell, Integer> regionByCell = indexCells(scan.regions());
        Cell playerCell = nearestObservedCell(
                observation.playerX(), observation.playerY(), observation.playerZ(),
                regionByCell.keySet()).orElse(null);
        Integer playerRegionId = playerCell == null ? null : regionByCell.get(playerCell);
        if (playerRegionId == null) {
            return Decision.reject(Code.PLAYER_OUTSIDE_OBSERVED_TOPOLOGY,
                    "Entity is not standing in the bounded observed Home topology");
        }

        List<Threat> activeThreats = observation.threats().stream()
                .filter(Threat::active)
                .toList();
        boolean hostileInsideHome = activeThreats.stream()
                .map(threat -> nearestObservedCell(
                        threat.x(), threat.y(), threat.z(), regionByCell.keySet())
                        .map(regionByCell::get).orElse(null))
                .anyMatch(regionId -> regionId != null && regionId == homeRegionId);
        if (hostileInsideHome) {
            return Decision.reject(Code.THREAT_INSIDE_HOME_ROOM,
                    "a loaded hostile is already inside the selected Home room");
        }

        double playerHomeDistance = distanceToRegion(
                observation.playerX(), observation.playerY(), observation.playerZ(), home);
        double threatHomeDistance = distanceToRegion(
                primary.x(), primary.y(), primary.z(), home);
        if (playerHomeDistance > MAXIMUM_PLAYER_HOME_DISTANCE + EPSILON
                || threatHomeDistance > MAXIMUM_PRIMARY_THREAT_HOME_DISTANCE + EPSILON
                || Math.abs(primary.y() - observation.playerY())
                > MAXIMUM_PRIMARY_THREAT_VERTICAL_DELTA + EPSILON) {
            return Decision.reject(Code.PRESSURE_NOT_LOCAL_TO_HOME,
                    "Home shelter is local safety, not an automatic cross-world or distant retreat");
        }

        List<PortalEdge> homeBoundaryPortals = scan.portals().stream()
                .filter(portal -> portal.regionA() == homeRegionId
                        || portal.regionB() == homeRegionId)
                .toList();
        if (homeBoundaryPortals.isEmpty()) {
            return Decision.reject(Code.NO_SAFE_WOODEN_EGRESS,
                    "the proved Home room has no observed wooden-door egress");
        }
        if (homeBoundaryPortals.stream()
                .anyMatch(portal -> portal.kind() != PortalKind.WOODEN_DOOR)) {
            return Decision.reject(Code.HOME_BOUNDARY_NOT_SEALABLE,
                    "the selected Home room has an observed non-door portal and cannot be sealed");
        }
        List<PortalEdge> homeDoors = homeBoundaryPortals;
        boolean completeBoundaryClosed = homeDoors.stream()
                .allMatch(portal -> observation.closedWoodenDoors()
                        .contains(portal.portalCell()));

        if (playerRegionId == homeRegionId) {
            PortalEdge nearestDoor = homeDoors.stream()
                    .min(Comparator
                            .comparingDouble((PortalEdge portal) -> distance(
                                    observation.playerX(), observation.playerY(),
                                    observation.playerZ(), portal.portalCell()))
                            .thenComparing(PortalEdge::portalCell))
                    .orElseThrow();
            return new Decision(
                    Code.HOLD_INSIDE_HOME_ROOM,
                    Optional.of(playerCell), Optional.of(nearestDoor), List.of(playerCell),
                    completeBoundaryClosed,
                    completeBoundaryClosed
                            ? "holding inside the sealed observed Home room while exterior hostile pressure remains"
                            : "inside the observed Home room while its wooden boundary is still being restored");
        }

        Region playerRegion = regions.get(playerRegionId);
        if (playerRegion == null || playerRegion.kind() != RegionKind.EXTERIOR) {
            return Decision.reject(Code.PLAYER_NOT_IN_HOME_EXTERIOR,
                    "Entity is not in the same proved exterior component as a Home egress");
        }
        if (!completeBoundaryClosed) {
            return Decision.reject(Code.HOME_BOUNDARY_NOT_SEALABLE,
                    "every observed wooden boundary of the Home room must begin closed");
        }

        double startClearance = minimumThreatDistance(
                observation.playerX(), observation.playerY(), observation.playerZ(),
                activeThreats);
        double requiredClearance = Math.min(
                MINIMUM_ROUTE_THREAT_CLEARANCE, startClearance);
        Set<Cell> exteriorCells = new LinkedHashSet<>(playerRegion.cells());
        List<Candidate> candidates = new ArrayList<>();
        for (PortalEdge portal : homeDoors) {
            if (!observation.closedWoodenDoors().contains(portal.portalCell())) continue;
            Endpoint endpoints = endpoints(portal, homeRegionId, playerRegionId);
            if (endpoints == null) continue;
            List<Cell> approach = shortestSafePath(
                    playerCell, endpoints.exterior(), exteriorCells,
                    activeThreats, requiredClearance);
            if (approach.isEmpty()) continue;
            Cell target = safestInteriorTarget(home, activeThreats, endpoints.interior());
            double routeClearance = approach.stream()
                    .mapToDouble(cell -> minimumThreatDistance(cell, activeThreats))
                    .min().orElse(Double.POSITIVE_INFINITY);
            candidates.add(new Candidate(
                    portal, endpoints.exterior(), target,
                    approach, routeClearance));
        }
        Candidate selected = candidates.stream()
                .min(Comparator
                        .comparingInt((Candidate candidate) -> candidate.approach().size())
                        .thenComparing(Comparator.comparingDouble(
                                Candidate::minimumThreatClearance).reversed())
                        .thenComparing(candidate -> candidate.portal().portalCell()))
                .orElse(null);
        if (selected == null) {
            return Decision.reject(Code.NO_SAFE_WOODEN_EGRESS,
                    "no closed wooden Home door has a loaded exterior approach clear of all hostiles");
        }

        return new Decision(
                Code.ENTER_HOME_ROOM,
                Optional.of(selected.target()), Optional.of(selected.portal()),
                selected.approach(), true,
                "entering the observed Home room through "
                        + coordinate(selected.portal().portalCell())
                        + "; selected exterior route clearance="
                        + decimal(selected.minimumThreatClearance()));
    }

    private static Map<Integer, Region> indexRegions(List<Region> regions) {
        Map<Integer, Region> result = new LinkedHashMap<>();
        for (Region region : regions) result.put(region.id(), region);
        return Map.copyOf(result);
    }

    private static Map<Cell, Integer> indexCells(List<Region> regions) {
        Map<Cell, Integer> result = new LinkedHashMap<>();
        for (Region region : regions) {
            for (Cell cell : region.cells()) result.put(cell, region.id());
        }
        return Map.copyOf(result);
    }

    private static Optional<Cell> nearestObservedCell(
            double x, double y, double z, Set<Cell> cells) {
        Cell exact = new Cell(floor(x), floor(y), floor(z));
        if (cells.contains(exact)) return Optional.of(exact);
        return cells.stream()
                .filter(cell -> Math.abs((cell.y() + 0.01) - y) <= 1.05)
                .filter(cell -> distance(x, y, z, cell) <= CELL_MATCH_DISTANCE)
                .min(Comparator
                        .comparingDouble((Cell cell) -> distance(x, y, z, cell))
                        .thenComparing(Cell::compareTo));
    }

    private static List<Cell> shortestSafePath(
            Cell start,
            Cell destination,
            Set<Cell> allowed,
            List<Threat> threats,
            double minimumClearance) {
        if (!allowed.contains(start) || !allowed.contains(destination)) return List.of();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        Set<Cell> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            if (current.equals(destination)) return reconstruct(previous, current);
            for (int[] direction : HORIZONTAL_DIRECTIONS) {
                Cell next = current.offset(direction[0], 0, direction[1]);
                if (!allowed.contains(next) || !visited.add(next)) continue;
                if (!next.equals(start)
                        && minimumThreatDistance(next, threats)
                        + EPSILON < minimumClearance) {
                    continue;
                }
                previous.put(next, current);
                queue.addLast(next);
            }
        }
        return List.of();
    }

    private static List<Cell> reconstruct(Map<Cell, Cell> previous, Cell end) {
        ArrayList<Cell> reversed = new ArrayList<>();
        Cell current = end;
        while (current != null) {
            reversed.add(current);
            current = previous.get(current);
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static Endpoint endpoints(
            PortalEdge portal, int homeRegionId, int exteriorRegionId) {
        if (portal.regionA() == homeRegionId && portal.regionB() == exteriorRegionId) {
            return new Endpoint(portal.endpointB(), portal.endpointA());
        }
        if (portal.regionB() == homeRegionId && portal.regionA() == exteriorRegionId) {
            return new Endpoint(portal.endpointA(), portal.endpointB());
        }
        return null;
    }

    private static Cell safestInteriorTarget(
            Region home,
            List<Threat> threats,
            Cell interiorEndpoint) {
        return home.cells().stream()
                .max(Comparator
                        .comparingDouble((Cell cell) -> minimumThreatDistance(cell, threats))
                        .thenComparingDouble(cell -> planarDistance(cell, interiorEndpoint))
                        .thenComparing(Cell::compareTo))
                .orElse(interiorEndpoint);
    }

    private static double distanceToRegion(double x, double y, double z, Region region) {
        return region.cells().stream()
                .mapToDouble(cell -> distance(x, y, z, cell))
                .min().orElse(Double.POSITIVE_INFINITY);
    }

    private static double minimumThreatDistance(Cell cell, List<Threat> threats) {
        return minimumThreatDistance(
                cell.x() + 0.5, cell.y(), cell.z() + 0.5, threats);
    }

    private static double minimumThreatDistance(
            double x, double y, double z, List<Threat> threats) {
        return threats.stream()
                .mapToDouble(threat -> Math.sqrt(
                        square(x - threat.x())
                                + square(y - threat.y())
                                + square(z - threat.z())))
                .min().orElse(Double.POSITIVE_INFINITY);
    }

    private static double distance(double x, double y, double z, Cell cell) {
        return Math.sqrt(
                square(x - (cell.x() + 0.5))
                        + square(y - cell.y())
                        + square(z - (cell.z() + 0.5)));
    }

    private static double planarDistance(Cell first, Cell second) {
        return Math.hypot(first.x() - second.x(), first.z() - second.z());
    }

    private static int floor(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("position must be finite");
        }
        double floored = Math.floor(value);
        if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("position is outside integer world bounds");
        }
        return (int) floored;
    }

    private static double square(double value) {
        return value * value;
    }

    private static String coordinate(Cell cell) {
        return cell.x() + " " + cell.y() + " " + cell.z();
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    public enum Code {
        ENTER_HOME_ROOM,
        HOLD_INSIDE_HOME_ROOM,
        TOPOLOGY_UNAVAILABLE,
        NO_ACTIVE_PRESSURE,
        NO_QUALIFIED_HOME_ROOM,
        THREAT_INSIDE_HOME_ROOM,
        PRESSURE_NOT_LOCAL_TO_HOME,
        PLAYER_OUTSIDE_OBSERVED_TOPOLOGY,
        PLAYER_NOT_IN_HOME_EXTERIOR,
        HOME_BOUNDARY_NOT_SEALABLE,
        NO_SAFE_WOODEN_EGRESS
    }

    public record Threat(
            String id,
            double x,
            double y,
            double z,
            boolean active) {
        public Threat {
            id = Objects.requireNonNullElse(id, "").trim();
            if (id.isEmpty()) throw new IllegalArgumentException("threat id is required");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("threat position must be finite");
            }
        }
    }

    public record Observation(
            boolean topologyAvailable,
            Scan scan,
            double playerX,
            double playerY,
            double playerZ,
            String primaryThreatId,
            List<Threat> threats,
            Set<Cell> closedWoodenDoors) {
        public Observation {
            scan = Objects.requireNonNull(scan, "scan");
            primaryThreatId = Objects.requireNonNullElse(primaryThreatId, "").trim();
            threats = List.copyOf(Objects.requireNonNull(threats, "threats"));
            closedWoodenDoors = Set.copyOf(
                    Objects.requireNonNull(closedWoodenDoors, "closedWoodenDoors"));
            if (!Double.isFinite(playerX)
                    || !Double.isFinite(playerY)
                    || !Double.isFinite(playerZ)) {
                throw new IllegalArgumentException("player position must be finite");
            }
        }
    }

    public record Decision(
            Code code,
            Optional<Cell> target,
            Optional<PortalEdge> door,
            List<Cell> exteriorApproach,
            boolean doorInitiallyClosed,
            String detail) {
        public Decision {
            code = Objects.requireNonNull(code, "code");
            target = Objects.requireNonNull(target, "target");
            door = Objects.requireNonNull(door, "door");
            exteriorApproach = List.copyOf(
                    Objects.requireNonNull(exteriorApproach, "exteriorApproach"));
            detail = Objects.requireNonNullElse(detail, "").trim();
            boolean admitted = code == Code.ENTER_HOME_ROOM
                    || code == Code.HOLD_INSIDE_HOME_ROOM;
            if (admitted != (target.isPresent() && door.isPresent()
                    && !exteriorApproach.isEmpty())) {
                throw new IllegalArgumentException(
                        "only admitted shelter decisions may own a route and door");
            }
            if (code == Code.ENTER_HOME_ROOM && !doorInitiallyClosed) {
                throw new IllegalArgumentException(
                        "new shelter entry requires a closed restorable wooden door");
            }
            if (detail.isEmpty()) throw new IllegalArgumentException("decision detail is required");
        }

        public boolean admitted() {
            return code == Code.ENTER_HOME_ROOM || code == Code.HOLD_INSIDE_HOME_ROOM;
        }

        public boolean hold() {
            return code == Code.HOLD_INSIDE_HOME_ROOM;
        }

        private static Decision reject(Code code, String detail) {
            return new Decision(
                    code, Optional.empty(), Optional.empty(), List.of(), false, detail);
        }
    }

    private record Endpoint(Cell exterior, Cell interior) {
    }

    private record Candidate(
            PortalEdge portal,
            Cell exterior,
            Cell target,
            List<Cell> approach,
            double minimumThreatClearance) {
    }
}
