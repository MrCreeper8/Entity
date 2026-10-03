package dev.entity.client.autonomy.policy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable memory of routes that Entity has actually occupied and can retrace.
 *
 * <p>This class deliberately does not produce a waypoint until the caller has presented the
 * corresponding edge for fresh, loaded-world validation. Recording is equally conservative: an
 * unloaded, unsafe, unoccupied, non-adjacent, or one-way observation earns no durable credit.</p>
 */
public final class FunctionalRouteMemory {
    public static final int MAXIMUM_ROUTES = 64;
    public static final int MAXIMUM_CELLS_PER_ROUTE = 4_096;
    public static final int MAXIMUM_TOTAL_CELLS = 16_384;
    public static final int MAXIMUM_FINGERPRINT_CHARS = 512;
    public static final int MAXIMUM_PENDING_REPLAY_EDGES = 2;

    /** Server scope plus the server's durable world identity; display names are not identities. */
    public record WorldIdentity(String serverIdentity, String worldIdentity) {
        public WorldIdentity {
            serverIdentity = requireText(serverIdentity, "serverIdentity", 512);
            worldIdentity = requireText(worldIdentity, "worldIdentity", 512);
        }
    }

    public record Coordinate(String dimension, int x, int y, int z) {
        public Coordinate {
            dimension = requireText(dimension, "dimension", 256);
        }

        public boolean adjacentTo(Coordinate other) {
            if (other == null || !dimension.equals(other.dimension)) return false;
            long dx = Math.abs((long) x - other.x);
            long dy = Math.abs((long) y - other.y);
            long dz = Math.abs((long) z - other.z);
            return (dx != 0L || dy != 0L || dz != 0L)
                    && dx <= 1L && dy <= 1L && dz <= 1L;
        }
    }

    /** A durable credit: this cell was loaded, safe, and physically occupied when recorded. */
    public record OccupiedSafeCell(
            Coordinate coordinate,
            String stableFingerprint,
            long observedAtMillis) {
        public OccupiedSafeCell {
            coordinate = Objects.requireNonNull(coordinate, "coordinate");
            stableFingerprint = requireText(
                    stableFingerprint, "stableFingerprint", MAXIMUM_FINGERPRINT_CHARS);
            if (observedAtMillis < 0L) {
                throw new IllegalArgumentException("observation time cannot be negative");
            }
        }
    }

    /** Fresh world evidence. A blank fingerprint is permitted only so blind input can be typed. */
    public record CellObservation(
            WorldIdentity worldIdentity,
            Coordinate coordinate,
            boolean loaded,
            boolean safe,
            boolean physicallyOccupied,
            String stableFingerprint) {
        public CellObservation {
            worldIdentity = Objects.requireNonNull(worldIdentity, "worldIdentity");
            coordinate = Objects.requireNonNull(coordinate, "coordinate");
            stableFingerprint = optionalText(
                    stableFingerprint, "stableFingerprint", MAXIMUM_FINGERPRINT_CHARS);
        }
    }

    /** Reverse-connected means the newly occupied cell can safely return to the previous cell. */
    public record AppendEvidence(
            CellObservation occupiedCell,
            boolean reverseConnectedToPrevious) {
        public AppendEvidence {
            occupiedCell = Objects.requireNonNull(occupiedCell, "occupiedCell");
        }
    }

    public record Route(
            String id,
            WorldIdentity worldIdentity,
            String dimension,
            OccupiedSafeCell entrance,
            OccupiedSafeCell lastSafeCell,
            List<OccupiedSafeCell> cells,
            long createdAtMillis,
            long updatedAtMillis) {
        public Route {
            id = requireText(id, "id", 256);
            worldIdentity = Objects.requireNonNull(worldIdentity, "worldIdentity");
            dimension = requireText(dimension, "dimension", 256);
            entrance = Objects.requireNonNull(entrance, "entrance");
            lastSafeCell = Objects.requireNonNull(lastSafeCell, "lastSafeCell");
            Objects.requireNonNull(cells, "cells");
            if (cells.isEmpty() || cells.size() > MAXIMUM_CELLS_PER_ROUTE) {
                throw new IllegalArgumentException(
                        "route cell count must be 1.." + MAXIMUM_CELLS_PER_ROUTE);
            }
            ArrayList<OccupiedSafeCell> copy = new ArrayList<>(cells.size());
            HashSet<Coordinate> seen = new HashSet<>();
            OccupiedSafeCell previous = null;
            for (OccupiedSafeCell cell : cells) {
                OccupiedSafeCell checked = Objects.requireNonNull(cell, "route cell");
                if (!checked.coordinate().dimension().equals(dimension)) {
                    throw new IllegalArgumentException("route cell changed dimension");
                }
                if (!seen.add(checked.coordinate())) {
                    throw new IllegalArgumentException("route contains an uncollapsed loop");
                }
                if (previous != null
                        && !previous.coordinate().adjacentTo(checked.coordinate())) {
                    throw new IllegalArgumentException("route contains a non-adjacent edge");
                }
                copy.add(checked);
                previous = checked;
            }
            cells = List.copyOf(copy);
            if (!entrance.equals(cells.get(0))) {
                throw new IllegalArgumentException("route entrance is not the first cell");
            }
            if (!lastSafeCell.equals(cells.get(cells.size() - 1))) {
                throw new IllegalArgumentException("route last-safe cell is not the final cell");
            }
            if (!entrance.coordinate().dimension().equals(dimension)) {
                throw new IllegalArgumentException("route entrance changed dimension");
            }
            if (createdAtMillis < 0L || updatedAtMillis < createdAtMillis) {
                throw new IllegalArgumentException("route timestamps are invalid");
            }
            for (OccupiedSafeCell cell : cells) {
                if (cell.observedAtMillis() > updatedAtMillis) {
                    throw new IllegalArgumentException(
                            "cell observation is newer than the route snapshot");
                }
            }
        }
    }

    public record Snapshot(
            long revision,
            WorldIdentity worldIdentity,
            Map<String, Route> routes) {
        public Snapshot {
            if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
            Objects.requireNonNull(routes, "routes");
            if (routes.size() > MAXIMUM_ROUTES) {
                throw new IllegalArgumentException("too many functional routes");
            }
            LinkedHashMap<String, Route> copy = new LinkedHashMap<>();
            int totalCells = 0;
            List<Map.Entry<String, Route>> ordered = routes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            for (Map.Entry<String, Route> entry : ordered) {
                String id = requireText(entry.getKey(), "route map key", 256);
                Route route = Objects.requireNonNull(entry.getValue(), "route");
                if (!id.equals(route.id())) {
                    throw new IllegalArgumentException("route map key does not equal route id");
                }
                if (worldIdentity == null || !worldIdentity.equals(route.worldIdentity())) {
                    throw new IllegalArgumentException("route belongs to another world");
                }
                totalCells = Math.addExact(totalCells, route.cells().size());
                if (totalCells > MAXIMUM_TOTAL_CELLS) {
                    throw new IllegalArgumentException("too many total functional-route cells");
                }
                copy.put(id, route);
            }
            if (worldIdentity == null && !copy.isEmpty()) {
                throw new IllegalArgumentException("unbound snapshot cannot contain routes");
            }
            routes = Collections.unmodifiableMap(copy);
        }

        public static Snapshot empty() {
            return new Snapshot(0L, null, Map.of());
        }
    }

    public interface Store {
        Snapshot load() throws IOException;

        void save(Snapshot snapshot) throws IOException;
    }

    public static final class WorldIdentityMismatchException extends IOException {
        private static final long serialVersionUID = 1L;

        public WorldIdentityMismatchException(
                WorldIdentity expected,
                WorldIdentity persisted) {
            super("functional-route store belongs to " + persisted
                    + ", not the active world " + expected);
        }
    }

    public enum RecordStatus {
        CREATED,
        APPENDED,
        LOOP_COLLAPSED,
        REFRESHED_LAST,
        NO_CHANGE,
        FORGOTTEN,
        UNKNOWN_ROUTE,
        ALREADY_EXISTS,
        CAPACITY_REACHED,
        REJECTED_WORLD,
        REJECTED_DIMENSION,
        REJECTED_UNLOADED,
        REJECTED_BLIND,
        REJECTED_UNSAFE,
        REJECTED_NOT_OCCUPIED,
        REJECTED_NOT_ADJACENT,
        REJECTED_NOT_REVERSE_CONNECTED
    }

    public record RecordResult(RecordStatus status, Route route, String detail) {
        public RecordResult {
            status = Objects.requireNonNull(status, "status");
            detail = Objects.requireNonNullElse(detail, "").trim();
        }

        public boolean credited() {
            return switch (status) {
                case CREATED, APPENDED, LOOP_COLLAPSED, REFRESHED_LAST -> true;
                default -> false;
            };
        }

        public boolean changedDurableState() {
            return credited() || status == RecordStatus.FORGOTTEN;
        }
    }

    public enum Direction {
        FORWARD,
        REVERSE
    }

    public enum ReplayStatus {
        READY,
        EDGE_ACCEPTED,
        COMPLETE,
        FALLBACK_UNLOADED,
        INVALID_WORLD,
        INVALID_DIMENSION,
        INVALID_ENDPOINT,
        INVALID_COORDINATES,
        INVALID_BLIND,
        INVALID_CHANGED,
        INVALID_UNSAFE,
        INVALID_NOT_OCCUPIED,
        INVALID_DISCONNECTED,
        INVALID_SEQUENCE,
        UNKNOWN_ROUTE
    }

    public record InspectionRequest(
            String routeId,
            Direction direction,
            int edgeOrdinal,
            OccupiedSafeCell from,
            OccupiedSafeCell to) {
        public InspectionRequest {
            routeId = requireText(routeId, "routeId", 256);
            direction = Objects.requireNonNull(direction, "direction");
            if (edgeOrdinal < 0) throw new IllegalArgumentException("edgeOrdinal is negative");
            from = Objects.requireNonNull(from, "from");
            to = Objects.requireNonNull(to, "to");
            if (!from.coordinate().adjacentTo(to.coordinate())) {
                throw new IllegalArgumentException("inspection request edge is not adjacent");
            }
        }
    }

    public record EdgeInspection(
            CellObservation from,
            CellObservation to,
            boolean reverseConnected) {
        public EdgeInspection {
            from = Objects.requireNonNull(from, "from");
            to = Objects.requireNonNull(to, "to");
        }
    }

    /** The only replay value intended to become a movement input. */
    public record TraversalPermit(
            String routeId,
            Direction direction,
            int edgeOrdinal,
            Coordinate target) {
        public TraversalPermit {
            routeId = requireText(routeId, "routeId", 256);
            direction = Objects.requireNonNull(direction, "direction");
            if (edgeOrdinal < 0) throw new IllegalArgumentException("edgeOrdinal is negative");
            target = Objects.requireNonNull(target, "target");
        }
    }

    public record ReplayDecision(
            ReplayStatus status,
            TraversalPermit permit,
            int acceptedEdges,
            String detail) {
        public ReplayDecision {
            status = Objects.requireNonNull(status, "status");
            detail = Objects.requireNonNullElse(detail, "").trim();
            if ((status == ReplayStatus.READY) != (permit != null)) {
                throw new IllegalArgumentException(
                        "only a ready edge decision may issue a traversal permit");
            }
            if (acceptedEdges < 0 || acceptedEdges > MAXIMUM_PENDING_REPLAY_EDGES) {
                throw new IllegalArgumentException("accepted replay-edge count is out of bounds");
            }
            if (acceptedEdges > 0
                    && status != ReplayStatus.EDGE_ACCEPTED
                    && status != ReplayStatus.COMPLETE) {
                throw new IllegalArgumentException(
                        "only an observed arrival may accept replay edges");
            }
        }
    }

    public record ReplaySelection(
            ReplayStatus status,
            ReplayCursor cursor,
            OccupiedSafeCell startingEndpoint,
            OccupiedSafeCell destination,
            String detail) {
        public ReplaySelection {
            status = Objects.requireNonNull(status, "status");
            detail = Objects.requireNonNullElse(detail, "").trim();
            boolean selected = status == ReplayStatus.READY || status == ReplayStatus.COMPLETE;
            if (selected != (cursor != null)) {
                throw new IllegalArgumentException("selected replay must own a cursor");
            }
            if (selected != (startingEndpoint != null && destination != null)) {
                throw new IllegalArgumentException("selected replay must own both endpoints");
            }
        }
    }

    /**
     * Ephemeral replay cursor. It reveals inspection requests first and releases at most a
     * two-edge movement window after exact live validation. The second permit contains normal
     * Minecraft stair momentum; arrival at either permitted cell must still be physically
     * observed.
     */
    public static final class ReplayCursor {
        private final String routeId;
        private final WorldIdentity worldIdentity;
        private final Direction direction;
        private final List<OccupiedSafeCell> cells;
        private int currentIndex;
        private final ArrayList<TraversalPermit> pendingPermits = new ArrayList<>(
                MAXIMUM_PENDING_REPLAY_EDGES);
        private ReplayStatus terminalStatus;

        private ReplayCursor(Route route, Direction direction) {
            routeId = route.id();
            worldIdentity = route.worldIdentity();
            this.direction = direction;
            cells = route.cells();
            currentIndex = direction == Direction.FORWARD ? 0 : cells.size() - 1;
        }

        public synchronized boolean complete() {
            return terminalStatus == null && pendingPermits.isEmpty()
                    && nextUnpermittedIndex() < 0;
        }

        public synchronized Optional<InspectionRequest> inspectionRequest() {
            if (terminalStatus != null
                    || pendingPermits.size() >= MAXIMUM_PENDING_REPLAY_EDGES) {
                return Optional.empty();
            }
            int next = nextUnpermittedIndex();
            if (next < 0) return Optional.empty();
            int fromIndex = next - directionStep();
            return Optional.of(new InspectionRequest(
                    routeId,
                    direction,
                    edgeOrdinal(fromIndex),
                    cells.get(fromIndex),
                    cells.get(next)));
        }

        public synchronized ReplayDecision inspectNext(EdgeInspection inspection) {
            Objects.requireNonNull(inspection, "inspection");
            if (terminalStatus != null) {
                return decision(terminalStatus, "replay is already terminal");
            }
            if (pendingPermits.size() >= MAXIMUM_PENDING_REPLAY_EDGES) {
                return decision(ReplayStatus.INVALID_SEQUENCE,
                        "physical arrival is required before extending the replay window");
            }
            int next = nextUnpermittedIndex();
            if (next < 0) {
                return decision(ReplayStatus.COMPLETE, "route endpoint is already occupied");
            }
            int fromIndex = next - directionStep();
            OccupiedSafeCell from = cells.get(fromIndex);
            OccupiedSafeCell to = cells.get(next);
            boolean requireFromOccupied = pendingPermits.isEmpty();
            ReplayStatus failure = validateObservation(
                    inspection.from(), worldIdentity, from, requireFromOccupied, false);
            if (failure == null) {
                failure = validateObservation(
                        inspection.to(), worldIdentity, to, false, false);
            }
            if (failure == null && !inspection.reverseConnected()) {
                failure = ReplayStatus.INVALID_DISCONNECTED;
            }
            if (failure != null) {
                terminalStatus = failure;
                return decision(failure, replayFailureDetail(failure));
            }
            TraversalPermit permit = new TraversalPermit(
                    routeId, direction, edgeOrdinal(fromIndex), to.coordinate());
            pendingPermits.add(permit);
            return new ReplayDecision(ReplayStatus.READY, permit, 0,
                    "edge was freshly loaded, unchanged, safe, and reverse-connected");
        }

        public synchronized ReplayDecision arrived(
                TraversalPermit permit,
                CellObservation observation) {
            Objects.requireNonNull(permit, "permit");
            Objects.requireNonNull(observation, "observation");
            if (terminalStatus != null) {
                return decision(terminalStatus, "replay is already terminal");
            }
            int pendingIndex = pendingPermits.indexOf(permit);
            if (pendingIndex < 0) {
                return decision(ReplayStatus.INVALID_SEQUENCE,
                        "arrival does not match the bounded freshly validated replay window");
            }
            int arrivedIndex = currentIndex + directionStep() * (pendingIndex + 1);
            if (arrivedIndex < 0 || arrivedIndex >= cells.size()) {
                return decision(ReplayStatus.INVALID_SEQUENCE,
                        "arrival was reported after the route endpoint");
            }
            ReplayStatus failure = validateObservation(
                    observation, worldIdentity, cells.get(arrivedIndex), true, false);
            if (failure != null) {
                terminalStatus = failure;
                pendingPermits.clear();
                return decision(failure, replayFailureDetail(failure));
            }
            int acceptedEdges = pendingIndex + 1;
            currentIndex = arrivedIndex;
            pendingPermits.subList(0, acceptedEdges).clear();
            return complete()
                    ? new ReplayDecision(ReplayStatus.COMPLETE, null, acceptedEdges,
                    "route destination was physically occupied")
                    : new ReplayDecision(
                            ReplayStatus.EDGE_ACCEPTED, null, acceptedEdges,
                            acceptedEdges == 1
                                    ? "edge arrival was observed; inspect the next edge"
                                    : "bounded stair momentum occupied the validated lookahead cell");
        }

        private int nextUnpermittedIndex() {
            int next = currentIndex + directionStep() * (pendingPermits.size() + 1);
            return next >= 0 && next < cells.size() ? next : -1;
        }

        private int directionStep() {
            return direction == Direction.FORWARD ? 1 : -1;
        }

        private int edgeOrdinal(int fromIndex) {
            return direction == Direction.FORWARD
                    ? fromIndex
                    : cells.size() - 1 - fromIndex;
        }

        private static ReplayDecision decision(ReplayStatus status, String detail) {
            return new ReplayDecision(status, null, 0, detail);
        }
    }

    private final Store store;
    private final WorldIdentity activeWorld;
    private Snapshot current;

    public FunctionalRouteMemory(Store store, WorldIdentity activeWorld) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        this.activeWorld = Objects.requireNonNull(activeWorld, "activeWorld");
        Snapshot loaded = Objects.requireNonNull(store.load(), "store returned null snapshot");
        if (loaded.worldIdentity() != null
                && !loaded.worldIdentity().equals(activeWorld)) {
            throw new WorldIdentityMismatchException(activeWorld, loaded.worldIdentity());
        }
        current = loaded;
    }

    public synchronized Snapshot snapshot() {
        return current;
    }

    public synchronized Optional<Route> route(String routeId) {
        return Optional.ofNullable(current.routes().get(requireText(routeId, "routeId", 256)));
    }

    public synchronized RecordResult beginRoute(
            String routeId,
            CellObservation entrance,
            long nowMillis) throws IOException {
        String id = requireText(routeId, "routeId", 256);
        Objects.requireNonNull(entrance, "entrance");
        if (current.routes().containsKey(id)) {
            return record(RecordStatus.ALREADY_EXISTS, current.routes().get(id),
                    "route id already exists");
        }
        if (current.routes().size() >= MAXIMUM_ROUTES
                || totalCells(current) >= MAXIMUM_TOTAL_CELLS) {
            return record(RecordStatus.CAPACITY_REACHED, null,
                    "functional-route capacity is exhausted");
        }
        RecordStatus invalid = validateRecordingObservation(entrance, activeWorld, null);
        if (invalid != null) return record(invalid, null, recordFailureDetail(invalid));

        long now = checkedTime(nowMillis);
        OccupiedSafeCell cell = creditedCell(entrance, now);
        Route route = new Route(
                id, activeWorld, entrance.coordinate().dimension(), cell, cell,
                List.of(cell), now, now);
        LinkedHashMap<String, Route> candidate = new LinkedHashMap<>(current.routes());
        candidate.put(id, route);
        commit(candidate);
        return record(RecordStatus.CREATED, route, "loaded safe entrance was persisted");
    }

    public synchronized RecordResult append(
            String routeId,
            AppendEvidence evidence,
            long nowMillis) throws IOException {
        String id = requireText(routeId, "routeId", 256);
        Objects.requireNonNull(evidence, "evidence");
        Route route = current.routes().get(id);
        if (route == null) {
            return record(RecordStatus.UNKNOWN_ROUTE, null, "route id is not known");
        }
        CellObservation observation = evidence.occupiedCell();
        RecordStatus invalid = validateRecordingObservation(
                observation, activeWorld, route.dimension());
        if (invalid != null) return record(invalid, route, recordFailureDetail(invalid));

        OccupiedSafeCell previous = route.lastSafeCell();
        boolean sameCell = previous.coordinate().equals(observation.coordinate());
        if (!sameCell && !previous.coordinate().adjacentTo(observation.coordinate())) {
            return record(RecordStatus.REJECTED_NOT_ADJACENT, route,
                    "new occupied cell is not adjacent to the last safe cell");
        }
        if (!sameCell && !evidence.reverseConnectedToPrevious()) {
            return record(RecordStatus.REJECTED_NOT_REVERSE_CONNECTED, route,
                    "new occupied cell has no proven safe return edge");
        }

        long now = Math.max(checkedTime(nowMillis), route.updatedAtMillis());
        OccupiedSafeCell cell = creditedCell(observation, now);
        ArrayList<OccupiedSafeCell> cells = new ArrayList<>(route.cells());
        if (sameCell) {
            if (previous.stableFingerprint().equals(cell.stableFingerprint())) {
                return record(RecordStatus.NO_CHANGE, route,
                        "last safe cell was already credited with this fingerprint");
            }
            cells.set(cells.size() - 1, cell);
            Route refreshed = rebuilt(route, cells, now);
            commitReplacement(refreshed);
            return record(RecordStatus.REFRESHED_LAST, refreshed,
                    "last occupied cell fingerprint was refreshed");
        }

        int loopIndex = indexOf(cells, cell.coordinate());
        if (loopIndex >= 0) {
            cells.subList(loopIndex, cells.size()).clear();
            cells.add(cell);
            Route collapsed = rebuilt(route, cells, now);
            commitReplacement(collapsed);
            return record(RecordStatus.LOOP_COLLAPSED, collapsed,
                    "returned loop was collapsed at the reoccupied safe cell");
        }

        if (cells.size() >= MAXIMUM_CELLS_PER_ROUTE
                || totalCells(current) >= MAXIMUM_TOTAL_CELLS) {
            return record(RecordStatus.CAPACITY_REACHED, route,
                    "functional-route cell capacity is exhausted");
        }
        cells.add(cell);
        Route appended = rebuilt(route, cells, now);
        commitReplacement(appended);
        return record(RecordStatus.APPENDED, appended,
                "adjacent occupied reverse-connected safe cell was persisted");
    }

    public synchronized RecordResult forgetRoute(String routeId) throws IOException {
        String id = requireText(routeId, "routeId", 256);
        Route route = current.routes().get(id);
        if (route == null) {
            return record(RecordStatus.UNKNOWN_ROUTE, null, "route id is not known");
        }
        LinkedHashMap<String, Route> candidate = new LinkedHashMap<>(current.routes());
        candidate.remove(id);
        commit(candidate);
        return record(RecordStatus.FORGOTTEN, route,
                "route was explicitly removed; world binding was retained");
    }

    public synchronized ReplaySelection selectReplay(
            String routeId,
            Direction direction,
            CellObservation endpointObservation) {
        String id = requireText(routeId, "routeId", 256);
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(endpointObservation, "endpointObservation");
        Route route = current.routes().get(id);
        if (route == null) {
            return selection(ReplayStatus.UNKNOWN_ROUTE,
                    "route id is not known");
        }
        OccupiedSafeCell start = direction == Direction.FORWARD
                ? route.entrance() : route.lastSafeCell();
        OccupiedSafeCell destination = direction == Direction.FORWARD
                ? route.lastSafeCell() : route.entrance();
        ReplayStatus failure = validateObservation(
                endpointObservation, activeWorld, start, true, true);
        if (failure != null) {
            return selection(failure, replayFailureDetail(failure));
        }
        ReplayCursor cursor = new ReplayCursor(route, direction);
        ReplayStatus status = cursor.complete() ? ReplayStatus.COMPLETE : ReplayStatus.READY;
        return new ReplaySelection(status, cursor, start, destination,
                direction == Direction.FORWARD
                        ? "forward replay selected from the exact entrance"
                        : "reverse replay selected from the exact last safe cell");
    }

    private void commitReplacement(Route replacement) throws IOException {
        LinkedHashMap<String, Route> candidate = new LinkedHashMap<>(current.routes());
        candidate.put(replacement.id(), replacement);
        commit(candidate);
    }

    private void commit(Map<String, Route> routes) throws IOException {
        Snapshot candidate = new Snapshot(
                Math.incrementExact(current.revision()), activeWorld, routes);
        // Persistence is the credit boundary. Failed saves leave current untouched.
        store.save(candidate);
        current = candidate;
    }

    private static Route rebuilt(
            Route route,
            List<OccupiedSafeCell> cells,
            long updatedAtMillis) {
        return new Route(
                route.id(), route.worldIdentity(), route.dimension(),
                cells.get(0), cells.get(cells.size() - 1), cells,
                route.createdAtMillis(), updatedAtMillis);
    }

    private static int indexOf(List<OccupiedSafeCell> cells, Coordinate coordinate) {
        for (int index = 0; index < cells.size(); index++) {
            if (cells.get(index).coordinate().equals(coordinate)) return index;
        }
        return -1;
    }

    private static int totalCells(Snapshot snapshot) {
        int total = 0;
        for (Route route : snapshot.routes().values()) {
            total = Math.addExact(total, route.cells().size());
        }
        return total;
    }

    private static OccupiedSafeCell creditedCell(
            CellObservation observation,
            long nowMillis) {
        return new OccupiedSafeCell(
                observation.coordinate(), observation.stableFingerprint(), nowMillis);
    }

    private static RecordStatus validateRecordingObservation(
            CellObservation observation,
            WorldIdentity expectedWorld,
            String expectedDimension) {
        if (!observation.worldIdentity().equals(expectedWorld)) {
            return RecordStatus.REJECTED_WORLD;
        }
        if (expectedDimension != null
                && !observation.coordinate().dimension().equals(expectedDimension)) {
            return RecordStatus.REJECTED_DIMENSION;
        }
        if (!observation.loaded()) return RecordStatus.REJECTED_UNLOADED;
        if (observation.stableFingerprint().isEmpty()) return RecordStatus.REJECTED_BLIND;
        if (!observation.safe()) return RecordStatus.REJECTED_UNSAFE;
        if (!observation.physicallyOccupied()) {
            return RecordStatus.REJECTED_NOT_OCCUPIED;
        }
        return null;
    }

    private static ReplayStatus validateObservation(
            CellObservation observation,
            WorldIdentity expectedWorld,
            OccupiedSafeCell expectedCell,
            boolean requireOccupied,
            boolean endpoint) {
        if (!observation.worldIdentity().equals(expectedWorld)) {
            return ReplayStatus.INVALID_WORLD;
        }
        if (!observation.coordinate().dimension()
                .equals(expectedCell.coordinate().dimension())) {
            return ReplayStatus.INVALID_DIMENSION;
        }
        if (!observation.coordinate().equals(expectedCell.coordinate())) {
            return endpoint ? ReplayStatus.INVALID_ENDPOINT : ReplayStatus.INVALID_COORDINATES;
        }
        if (!observation.loaded()) return ReplayStatus.FALLBACK_UNLOADED;
        if (observation.stableFingerprint().isEmpty()) return ReplayStatus.INVALID_BLIND;
        if (!observation.stableFingerprint().equals(expectedCell.stableFingerprint())) {
            return ReplayStatus.INVALID_CHANGED;
        }
        if (!observation.safe()) return ReplayStatus.INVALID_UNSAFE;
        if (requireOccupied && !observation.physicallyOccupied()) {
            return ReplayStatus.INVALID_NOT_OCCUPIED;
        }
        return null;
    }

    private static RecordResult record(RecordStatus status, Route route, String detail) {
        return new RecordResult(status, route, detail);
    }

    private static ReplaySelection selection(ReplayStatus status, String detail) {
        return new ReplaySelection(status, null, null, null, detail);
    }

    private static String recordFailureDetail(RecordStatus status) {
        return switch (status) {
            case REJECTED_WORLD -> "observation belongs to another durable world identity";
            case REJECTED_DIMENSION -> "observation changed dimension";
            case REJECTED_UNLOADED -> "unloaded cells cannot earn route credit";
            case REJECTED_BLIND -> "loaded route evidence requires a stable fingerprint";
            case REJECTED_UNSAFE -> "unsafe cells cannot earn route credit";
            case REJECTED_NOT_OCCUPIED -> "route credit requires physical body occupancy";
            default -> "route observation was rejected";
        };
    }

    private static String replayFailureDetail(ReplayStatus status) {
        return switch (status) {
            case FALLBACK_UNLOADED ->
                    "edge is unloaded; abandon replay and use ordinary live pathfinding";
            case INVALID_WORLD -> "replay evidence belongs to another durable world";
            case INVALID_DIMENSION -> "replay evidence changed dimension";
            case INVALID_ENDPOINT -> "body is not physically occupying the selected endpoint";
            case INVALID_COORDINATES -> "edge evidence does not match the requested cells";
            case INVALID_BLIND -> "loaded edge evidence omitted its stable fingerprint";
            case INVALID_CHANGED -> "route geometry changed since it was recorded";
            case INVALID_UNSAFE -> "route edge is no longer safe";
            case INVALID_NOT_OCCUPIED -> "current route cell is not physically occupied";
            case INVALID_DISCONNECTED -> "edge no longer has a safe reverse connection";
            default -> "route replay is invalid";
        };
    }

    private static long checkedTime(long value) {
        if (value < 0L) throw new IllegalArgumentException("time cannot be negative");
        return value;
    }

    private static String requireText(String value, String field, int maximumLength) {
        String checked = optionalText(value, field, maximumLength);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return checked;
    }

    private static String optionalText(String value, String field, int maximumLength) {
        String checked = Objects.requireNonNullElse(value, "").trim();
        if (checked.length() > maximumLength
                || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " must be at most " + maximumLength + " printable chars");
        }
        return checked;
    }
}
