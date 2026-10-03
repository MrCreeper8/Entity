package dev.entity.client.baritone;

import dev.entity.client.autonomy.policy.AtomicFunctionalRouteStore;
import dev.entity.client.autonomy.policy.FunctionalRouteMemory;
import dev.entity.client.autonomy.workspace.MinecraftWorkspaceProbe;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Loaded-world adapter around {@link FunctionalRouteMemory}.
 *
 * <p>A route cell is credited only after the player physically occupies a loaded,
 * supported two-block body column. Replay exposes one freshly validated adjacent
 * waypoint at a time; an unloaded or changed edge abandons replay and returns
 * control to ordinary live pathfinding.</p>
 */
public final class MinecraftFunctionalRoutes {
    private static final long EDGE_TIMEOUT_MILLIS = 10_000L;
    private static final int MAXIMUM_ROUTE_ID_CHARS = 256;

    public enum Purpose {
        NONE,
        MINING_FORWARD,
        MINING_RETURN,
        HOME_RETURN
    }

    public enum BeginAction {
        RECORDING,
        REPLAYING,
        ORDINARY_PATHFINDING
    }

    public enum TickAction {
        NONE,
        ISSUE_WAYPOINT,
        WAITING_FOR_ARRIVAL,
        COMPLETE,
        FALLBACK
    }

    public enum MiningReturnAction {
        WAITING_FOR_STABLE_ENDPOINT,
        REPLAYING,
        NOT_REQUIRED,
        BLOCKED
    }

    public record MiningReturnResult(MiningReturnAction action, String detail) {
        public MiningReturnResult {
            action = Objects.requireNonNull(action, "action");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record BeginResult(BeginAction action, String detail) {
        public BeginResult {
            action = Objects.requireNonNull(action, "action");
            detail = Objects.requireNonNullElse(detail, "");
        }

        public boolean replaying() {
            return action == BeginAction.REPLAYING;
        }
    }

    public record TickResult(
            TickAction action,
            BlockPos waypoint,
            Purpose purpose,
            String detail) {
        public TickResult {
            action = Objects.requireNonNull(action, "action");
            purpose = Objects.requireNonNull(purpose, "purpose");
            detail = Objects.requireNonNullElse(detail, "");
            if ((action == TickAction.ISSUE_WAYPOINT) != (waypoint != null)) {
                throw new IllegalArgumentException(
                        "only a newly issued route permit may expose a waypoint");
            }
        }
    }

    public record Snapshot(
            long revision,
            int routeCount,
            int recordedCells,
            String activeRouteId,
            String replayPurpose,
            String replayStatus,
            long replayEdgesIssued,
            long replayEdgesAccepted,
            long replayRevalidations,
            long replayFallbacks,
            String lastDetail) {
        public Snapshot {
            activeRouteId = Objects.requireNonNullElse(activeRouteId, "");
            replayPurpose = Objects.requireNonNullElse(replayPurpose, "none");
            replayStatus = Objects.requireNonNullElse(replayStatus, "idle");
            lastDetail = Objects.requireNonNullElse(lastDetail, "");
        }
    }

    private final MinecraftClient client;
    private final Path dataDirectory;
    private final Logger logger;

    private FunctionalRouteMemory memory;
    private FunctionalRouteMemory.WorldIdentity boundWorld;
    private FunctionalRouteMemory.WorldIdentity authoritativeWorld;
    private String authoritativeDimension = "";
    private String recordingRouteId = "";
    private String recordingObjective = "";
    private ReplayState replay;
    private String replayStatus = "idle";
    private String lastDetail = "";
    private long replayEdgesIssued;
    private long replayEdgesAccepted;
    private long replayRevalidations;
    private long replayFallbacks;

    public MinecraftFunctionalRoutes(
            MinecraftClient client,
            Path dataDirectory,
            Logger logger) {
        this.client = Objects.requireNonNull(client, "client");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Binds Paper's exact world UUID before any durable route can load or replay. */
    public synchronized void installWorldIdentity(
            String dimension,
            String worldIdentity) {
        String checkedDimension = Objects.requireNonNullElse(dimension, "").trim();
        String checkedWorld = Objects.requireNonNullElse(worldIdentity, "").trim();
        if (checkedDimension.isEmpty() || checkedWorld.isEmpty()) {
            throw new IllegalArgumentException("authoritative world identity cannot be blank");
        }
        FunctionalRouteMemory.WorldIdentity next = new FunctionalRouteMemory.WorldIdentity(
                currentServerIdentity(), checkedWorld);
        if (checkedDimension.equals(authoritativeDimension)
                && next.equals(authoritativeWorld)) return;
        authoritativeDimension = checkedDimension;
        authoritativeWorld = next;
        memory = null;
        boundWorld = null;
        replay = null;
        recordingRouteId = "";
        recordingObjective = "";
        replayStatus = "world_identity_changed";
        lastDetail = "Paper-authoritative world identity changed; stale routes were fenced";
        // A restarted client can remain idle until its next command. Rebind the
        // checksummed route store as soon as the in-world Paper identity is
        // available instead of making a future route action discover it.
        ensureMemory();
    }

    public synchronized void clearWorldIdentity() {
        if (authoritativeWorld == null && authoritativeDimension.isBlank()) return;
        authoritativeDimension = "";
        authoritativeWorld = null;
        memory = null;
        boundWorld = null;
        replay = null;
        recordingRouteId = "";
        recordingObjective = "";
        replayStatus = "world_identity_unavailable";
        lastDetail = "Paper world identity is unavailable; durable route use is disabled";
    }

    /** Selects a proven forward route or starts a new physically observed route. */
    public synchronized BeginResult beginMining(String objective, long nowMillis) {
        String normalizedObjective = requiredObjective(objective);
        cancelReplay("starting mining route selection");
        recordingRouteId = "";
        recordingObjective = normalizedObjective;
        if (!ensureMemory()) {
            return ordinary("functional route memory is unavailable; using live pathfinding");
        }
        FunctionalRouteMemory.CellObservation current = observePlayerCell();
        if (!recordable(current)) {
            return ordinary("current body cell is not loaded, grounded, and safe enough to record");
        }

        String prefix = objectivePrefix(normalizedObjective);
        List<FunctionalRouteMemory.Route> candidates = memory.snapshot().routes().values().stream()
                .filter(route -> route.id().startsWith(prefix))
                .filter(route -> route.cells().size() > 1)
                .filter(route -> route.entrance().coordinate().equals(current.coordinate()))
                .sorted(Comparator.comparingLong(
                        FunctionalRouteMemory.Route::updatedAtMillis).reversed())
                .toList();
        for (FunctionalRouteMemory.Route candidate : candidates) {
            FunctionalRouteMemory.ReplaySelection selection = memory.selectReplay(
                    candidate.id(), FunctionalRouteMemory.Direction.FORWARD, current);
            if (selection.status() != FunctionalRouteMemory.ReplayStatus.READY) continue;
            replay = new ReplayState(
                    Purpose.MINING_FORWARD,
                    candidate.id(),
                    selection.cursor(),
                    normalizedObjective,
                    nowMillis);
            recordingRouteId = candidate.id();
            replayStatus = "selected_forward";
            lastDetail = selection.detail();
            return new BeginResult(BeginAction.REPLAYING, selection.detail());
        }

        FunctionalRouteMemory.Route sameEndpoint = memory.snapshot().routes().values().stream()
                .filter(route -> route.id().startsWith(prefix))
                .filter(route -> route.lastSafeCell().coordinate().equals(current.coordinate()))
                .max(Comparator.comparingLong(FunctionalRouteMemory.Route::updatedAtMillis))
                .orElse(null);
        if (sameEndpoint != null) {
            recordingRouteId = sameEndpoint.id();
            replayStatus = "recording";
            lastDetail = "continuing the physically occupied route endpoint";
            return new BeginResult(BeginAction.RECORDING, lastDetail);
        }

        try {
            String routeId = unusedRouteId(prefix, current.coordinate());
            FunctionalRouteMemory.RecordResult created = memory.beginRoute(
                    routeId, current, nowMillis);
            if (created.credited()) {
                recordingRouteId = routeId;
                replayStatus = "recording";
                lastDetail = created.detail();
                return new BeginResult(BeginAction.RECORDING, created.detail());
            }
            return ordinary(created.detail());
        } catch (IOException | RuntimeException error) {
            disableAfterPersistenceFailure("begin functional mining route", error);
            return ordinary(lastDetail);
        }
    }

    /** Selects a reverse route only when the body occupies its exact last-safe cell. */
    public synchronized BeginResult beginReturn(
            String dimension,
            BlockPos target,
            int range,
            long nowMillis) {
        Objects.requireNonNull(target, "target");
        cancelReplay("starting return-route selection");
        recordingRouteId = "";
        recordingObjective = "";
        if (!ensureMemory()) {
            return ordinary("functional route memory is unavailable; using live pathfinding");
        }
        FunctionalRouteMemory.CellObservation current = observePlayerCell();
        if (!recordable(current) || !current.coordinate().dimension().equals(dimension)) {
            return ordinary("the exact loaded return-route endpoint is not occupied");
        }
        int acceptedRange = Math.max(0, range);
        long maximumDistanceSquared = (long) acceptedRange * acceptedRange;
        List<FunctionalRouteMemory.Route> candidates = memory.snapshot().routes().values().stream()
                .filter(route -> route.dimension().equals(dimension))
                .filter(route -> route.cells().size() > 1)
                .filter(route -> route.lastSafeCell().coordinate().equals(current.coordinate()))
                .filter(route -> squaredDistance(route.entrance().coordinate(), target)
                        <= maximumDistanceSquared)
                .sorted(Comparator.comparingLong(
                        FunctionalRouteMemory.Route::updatedAtMillis).reversed())
                .toList();
        for (FunctionalRouteMemory.Route candidate : candidates) {
            FunctionalRouteMemory.ReplaySelection selection = memory.selectReplay(
                    candidate.id(), FunctionalRouteMemory.Direction.REVERSE, current);
            if (selection.status() != FunctionalRouteMemory.ReplayStatus.READY) continue;
            replay = new ReplayState(
                    Purpose.HOME_RETURN,
                    candidate.id(),
                    selection.cursor(),
                    "",
                    nowMillis);
            replayStatus = "selected_reverse";
            lastDetail = selection.detail();
            return new BeginResult(BeginAction.REPLAYING, selection.detail());
        }
        return ordinary("no proven route endpoint connects this body cell to the requested destination");
    }

    /**
     * Records at most one newly occupied mining cell. Persistence is completed
     * inside FunctionalRouteMemory before this method reports durable progress.
     */
    public synchronized void observeMining(String objective, long nowMillis) {
        String normalizedObjective = requiredObjective(objective);
        if (replay != null || !ensureMemory()) return;
        FunctionalRouteMemory.CellObservation current = observePlayerCell();
        if (!recordable(current)) return;
        if (!recordingObjective.equals(normalizedObjective)
                || recordingRouteId.isBlank()
                || memory.route(recordingRouteId).isEmpty()) {
            beginRecordingOnly(normalizedObjective, current, nowMillis);
            return;
        }
        try {
            FunctionalRouteMemory.Route route = memory.route(recordingRouteId).orElse(null);
            if (route == null) return;
            boolean reverseConnected = liveReverseConnected(
                    route.lastSafeCell().coordinate(), current.coordinate());
            FunctionalRouteMemory.RecordResult result = memory.append(
                    recordingRouteId,
                    new FunctionalRouteMemory.AppendEvidence(current, reverseConnected),
                    nowMillis);
            if (result.changedDurableState()) {
                replayStatus = "recording";
                lastDetail = result.detail();
            } else if (result.status() != FunctionalRouteMemory.RecordStatus.NO_CHANGE) {
                lastDetail = result.detail();
            }
        } catch (IOException | RuntimeException error) {
            disableAfterPersistenceFailure("append functional mining route", error);
        }
    }

    /**
     * Credits the final physically occupied mining cell at the enclosing action
     * boundary. The mined drop can pull Entity one safe cell beyond Baritone's
     * last poll, so relying only on pathing ticks would leave the durable route
     * ending behind the body and make its known exit unusable.
     */
    public synchronized void finishMiningRecording(long nowMillis) {
        String objective = recordingObjective;
        String routeId = recordingRouteId;
        if (replay != null || objective.isBlank() || routeId.isBlank()) return;

        observeMining(objective, nowMillis);
        recordingObjective = "";
        if (memory == null) return;

        FunctionalRouteMemory.CellObservation current = observePlayerCell();
        FunctionalRouteMemory.Route route = memory.route(routeId).orElse(null);
        if (route != null
                && recordable(current)
                && route.lastSafeCell().coordinate().equals(current.coordinate())) {
            replayStatus = "recorded";
            lastDetail = "final occupied mining cell was persisted before action completion";
        } else {
            replayStatus = "recording_endpoint_unproven";
            lastDetail = "final mining cell was not safe and adjacent; retained the prior proven endpoint";
        }
    }

    /**
     * Closes the physically observed mining route and selects its exact reverse
     * exit. A just-collected drop may leave the player briefly airborne; that
     * is a bounded settlement state, not permission to forget the entrance.
     */
    public synchronized MiningReturnResult beginMiningReturn(long nowMillis) {
        if (replay != null) {
            if (replay.purpose == Purpose.MINING_RETURN) {
                return new MiningReturnResult(
                        MiningReturnAction.REPLAYING,
                        "returning through the freshly revalidated mine route");
            }
            return new MiningReturnResult(
                    MiningReturnAction.BLOCKED,
                    "another functional route replay still owns the body");
        }
        if (!ensureMemory()) {
            return new MiningReturnResult(
                    MiningReturnAction.BLOCKED,
                    "functional route memory is unavailable; the mine exit cannot be proven");
        }
        String objective = recordingObjective;
        String routeId = recordingRouteId;
        if (objective.isBlank() || routeId.isBlank()) {
            return new MiningReturnResult(
                    MiningReturnAction.NOT_REQUIRED,
                    "mining did not leave a recorded access route");
        }

        FunctionalRouteMemory.CellObservation current = observePlayerCell();
        if (!recordable(current)) {
            replayStatus = "settling_endpoint";
            lastDetail = "waiting for one loaded grounded body cell before closing the mine route";
            return new MiningReturnResult(
                    MiningReturnAction.WAITING_FOR_STABLE_ENDPOINT,
                    lastDetail);
        }

        // Credit the final physically occupied cell only after it is grounded.
        // observeMining persists it before route selection can expose a replay.
        observeMining(objective, nowMillis);
        FunctionalRouteMemory.Route route = memory.route(routeId).orElse(null);
        if (route == null) {
            replayStatus = "return_blocked_missing_route";
            lastDetail = "the active mine route disappeared before return selection";
            return new MiningReturnResult(MiningReturnAction.BLOCKED, lastDetail);
        }
        current = observePlayerCell();
        if (!route.lastSafeCell().coordinate().equals(current.coordinate())) {
            replayStatus = "return_blocked_disconnected_endpoint";
            lastDetail = "the grounded mining endpoint is disconnected from the last proven route cell";
            return new MiningReturnResult(MiningReturnAction.BLOCKED, lastDetail);
        }

        recordingObjective = "";
        if (route.cells().size() <= 1) {
            replayStatus = "return_not_required";
            lastDetail = "mining ended at its entrance; no reverse traversal is required";
            return new MiningReturnResult(MiningReturnAction.NOT_REQUIRED, lastDetail);
        }
        FunctionalRouteMemory.ReplaySelection selection = memory.selectReplay(
                route.id(), FunctionalRouteMemory.Direction.REVERSE, current);
        if (selection.status() != FunctionalRouteMemory.ReplayStatus.READY) {
            replayStatus = "return_blocked_"
                    + selection.status().name().toLowerCase(Locale.ROOT);
            lastDetail = selection.detail();
            return new MiningReturnResult(MiningReturnAction.BLOCKED, lastDetail);
        }
        replay = new ReplayState(
                Purpose.MINING_RETURN,
                route.id(),
                selection.cursor(),
                "",
                nowMillis);
        replayStatus = "selected_mining_return";
        lastDetail = selection.detail();
        return new MiningReturnResult(MiningReturnAction.REPLAYING, lastDetail);
    }

    /** Advances a replay through a two-edge validated window that contains stair momentum. */
    public synchronized TickResult tick(long nowMillis) {
        ReplayState active = replay;
        if (active == null) return none();
        if (!ensureMemory() || replay == null) {
            return fallback(Purpose.NONE, "world identity changed during route replay", null);
        }

        if (active.pendingPermit != null) {
            FunctionalRouteMemory.CellObservation arrival = observe(
                    active.pendingPermit.target());
            FunctionalRouteMemory.ReplayDecision arrived = null;
            if (arrival.physicallyOccupied()) {
                arrived = active.cursor.arrived(active.pendingPermit, arrival);
            } else if (active.lookaheadPermit != null) {
                FunctionalRouteMemory.CellObservation actual = observePlayerCell();
                if (actual.physicallyOccupied()
                        && actual.coordinate().equals(active.lookaheadPermit.target())) {
                    arrived = active.cursor.arrived(active.lookaheadPermit, actual);
                }
            }
            if (arrived != null) {
                int accepted = arrived.acceptedEdges();
                if (accepted == 1) {
                    active.pendingPermit = active.lookaheadPermit;
                    active.lookaheadPermit = null;
                } else {
                    active.pendingPermit = null;
                    active.lookaheadPermit = null;
                }
                active.edgeDeadlineAt = 0L;
                if (arrived.status() == FunctionalRouteMemory.ReplayStatus.COMPLETE) {
                    replayEdgesAccepted += accepted;
                    return complete(active, arrived.detail());
                }
                if (arrived.status() != FunctionalRouteMemory.ReplayStatus.EDGE_ACCEPTED) {
                    return fallback(active.purpose, arrived.detail(), arrived.status());
                }
                replayEdgesAccepted += accepted;
                replayStatus = "edge_arrived";
                lastDetail = arrived.detail();
            } else if (nowMillis > active.edgeDeadlineAt) {
                return fallback(
                        active.purpose,
                        "functional route edge timed out before physical occupancy",
                        FunctionalRouteMemory.ReplayStatus.INVALID_NOT_OCCUPIED);
            } else {
                replayStatus = "moving";
                return new TickResult(
                        TickAction.WAITING_FOR_ARRIVAL,
                        null,
                        active.purpose,
                        "moving toward freshly revalidated route cell "
                                + coordinateText(active.pendingPermit.target()));
            }
        }

        if (active.cursor.complete()) {
            return complete(active, "functional route destination is physically occupied");
        }

        if (active.pendingPermit == null) {
            FunctionalRouteMemory.ReplayDecision inspected = inspectNextReplayEdge(active);
            if (inspected == null) {
                return fallback(
                        active.purpose,
                        "functional route cursor lost its next inspection request",
                        FunctionalRouteMemory.ReplayStatus.INVALID_SEQUENCE);
            }
            if (inspected.status() != FunctionalRouteMemory.ReplayStatus.READY) {
                return fallback(active.purpose, inspected.detail(), inspected.status());
            }
            active.pendingPermit = inspected.permit();
            replayEdgesIssued++;
        }

        if (active.lookaheadPermit == null) {
            FunctionalRouteMemory.ReplayDecision lookahead = inspectNextReplayEdge(active);
            if (lookahead != null) {
                if (lookahead.status() != FunctionalRouteMemory.ReplayStatus.READY) {
                    return fallback(active.purpose, lookahead.detail(), lookahead.status());
                }
                active.lookaheadPermit = lookahead.permit();
                replayEdgesIssued++;
            }
        }

        active.edgeDeadlineAt = Math.addExact(nowMillis, EDGE_TIMEOUT_MILLIS);
        replayStatus = "waypoint_issued";
        lastDetail = active.lookaheadPermit == null
                ? "next route edge was freshly validated"
                : "next route edge and its stair-momentum cell were freshly validated";
        FunctionalRouteMemory.Coordinate target = active.pendingPermit.target();
        return new TickResult(
                TickAction.ISSUE_WAYPOINT,
                new BlockPos(target.x(), target.y(), target.z()),
                active.purpose,
                lastDetail);
    }

    public synchronized void cancelReplay(String reason) {
        if (replay == null) return;
        replay = null;
        replayStatus = "cancelled";
        lastDetail = Objects.requireNonNullElse(reason, "functional route replay cancelled");
    }

    public synchronized boolean replayActive() {
        return replay != null;
    }

    public record KnownMiningAccess(String routeId, String dimension, BlockPos entrance, BlockPos endpoint) { }

    /** Admission evidence only: every retained edge must still be loaded and reversible. */
    public synchronized List<KnownMiningAccess> knownMiningAccesses() {
        if (!ensureMemory()) return List.of();
        return memory.snapshot().routes().values().stream()
                .filter(route -> route.id().startsWith(objectivePrefix(currentDimension() + "|mine-access")))
                .filter(route -> route.dimension().equals(currentDimension()) && route.cells().size() > 1)
                .filter(route -> {
                    FunctionalRouteMemory.Coordinate previous = null;
                    for (var saved : route.cells()) {
                        var current = observe(saved.coordinate());
                        if (!current.loaded() || !current.safe()
                                || !current.stableFingerprint().equals(saved.stableFingerprint())
                                || previous != null && !liveReverseConnected(previous, saved.coordinate())) return false;
                        previous = saved.coordinate();
                    }
                    return true;
                })
                .sorted(Comparator.comparingLong(FunctionalRouteMemory.Route::updatedAtMillis).reversed())
                .limit(8)
                .map(route -> new KnownMiningAccess(route.id(), route.dimension(),
                        point(route.entrance().coordinate()), point(route.lastSafeCell().coordinate())))
                .toList();
    }

    private static BlockPos point(FunctionalRouteMemory.Coordinate cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    public synchronized Snapshot snapshot() {
        FunctionalRouteMemory.Snapshot durable = memory == null
                ? FunctionalRouteMemory.Snapshot.empty()
                : memory.snapshot();
        int recordedCells = durable.routes().values().stream()
                .mapToInt(route -> route.cells().size())
                .sum();
        return new Snapshot(
                durable.revision(),
                durable.routes().size(),
                recordedCells,
                replay == null ? recordingRouteId : replay.routeId,
                replay == null ? Purpose.NONE.name().toLowerCase(Locale.ROOT)
                        : replay.purpose.name().toLowerCase(Locale.ROOT),
                replayStatus,
                replayEdgesIssued,
                replayEdgesAccepted,
                replayRevalidations,
                replayFallbacks,
                lastDetail);
    }

    private FunctionalRouteMemory.ReplayDecision inspectNextReplayEdge(ReplayState active) {
        FunctionalRouteMemory.InspectionRequest request =
                active.cursor.inspectionRequest().orElse(null);
        if (request == null) return null;
        FunctionalRouteMemory.CellObservation from = observe(request.from().coordinate());
        FunctionalRouteMemory.CellObservation to = observe(request.to().coordinate());
        boolean reverseConnected = liveReverseConnected(
                request.from().coordinate(), request.to().coordinate());
        FunctionalRouteMemory.ReplayDecision inspected = active.cursor.inspectNext(
                new FunctionalRouteMemory.EdgeInspection(from, to, reverseConnected));
        replayRevalidations++;
        return inspected;
    }

    private void beginRecordingOnly(
            String objective,
            FunctionalRouteMemory.CellObservation current,
            long nowMillis) {
        String prefix = objectivePrefix(objective);
        FunctionalRouteMemory.Route endpoint = memory.snapshot().routes().values().stream()
                .filter(route -> route.id().startsWith(prefix))
                .filter(route -> route.lastSafeCell().coordinate().equals(current.coordinate()))
                .max(Comparator.comparingLong(FunctionalRouteMemory.Route::updatedAtMillis))
                .orElse(null);
        if (endpoint != null) {
            recordingObjective = objective;
            recordingRouteId = endpoint.id();
            return;
        }
        try {
            String routeId = unusedRouteId(prefix, current.coordinate());
            FunctionalRouteMemory.RecordResult result = memory.beginRoute(
                    routeId, current, nowMillis);
            if (result.credited()) {
                recordingObjective = objective;
                recordingRouteId = routeId;
                replayStatus = "recording";
                lastDetail = result.detail();
            }
        } catch (IOException | RuntimeException error) {
            disableAfterPersistenceFailure("start replacement functional route", error);
        }
    }

    private TickResult complete(ReplayState completed, String detail) {
        Purpose purpose = completed.purpose;
        if (purpose == Purpose.MINING_FORWARD) {
            recordingObjective = completed.objective;
            recordingRouteId = completed.routeId;
        }
        replay = null;
        replayStatus = "complete";
        lastDetail = detail;
        return new TickResult(TickAction.COMPLETE, null, purpose, detail);
    }

    private TickResult fallback(
            Purpose purpose,
            String detail,
            FunctionalRouteMemory.ReplayStatus status) {
        ReplayState failed = replay;
        replay = null;
        replayFallbacks++;
        replayStatus = status == null
                ? "fallback"
                : "fallback_" + status.name().toLowerCase(Locale.ROOT);
        lastDetail = Objects.requireNonNullElse(detail, "functional route replay fell back");
        recordingRouteId = "";
        if (failed != null && invalidatesDurableRoute(status)) {
            try {
                if (memory != null) memory.forgetRoute(failed.routeId);
            } catch (IOException | RuntimeException error) {
                disableAfterPersistenceFailure("forget invalid functional route", error);
            }
        }
        return new TickResult(TickAction.FALLBACK, null, purpose, lastDetail);
    }

    private static boolean invalidatesDurableRoute(
            FunctionalRouteMemory.ReplayStatus status) {
        return status == FunctionalRouteMemory.ReplayStatus.INVALID_CHANGED
                || status == FunctionalRouteMemory.ReplayStatus.INVALID_UNSAFE
                || status == FunctionalRouteMemory.ReplayStatus.INVALID_DISCONNECTED;
    }

    private TickResult none() {
        return new TickResult(TickAction.NONE, null, Purpose.NONE, "");
    }

    private BeginResult ordinary(String detail) {
        replayStatus = "ordinary_pathfinding";
        lastDetail = Objects.requireNonNullElse(detail, "");
        return new BeginResult(BeginAction.ORDINARY_PATHFINDING, lastDetail);
    }

    private boolean ensureMemory() {
        if (client.world == null
                || authoritativeWorld == null
                || !authoritativeDimension.equals(currentDimension())) return false;
        FunctionalRouteMemory.WorldIdentity observed = authoritativeWorld;
        if (memory != null && observed.equals(boundWorld)) return true;
        replay = null;
        recordingRouteId = "";
        recordingObjective = "";
        try {
            String storeKey = shortHash(
                    observed.serverIdentity() + '\n' + observed.worldIdentity());
            memory = new FunctionalRouteMemory(
                    new AtomicFunctionalRouteStore(
                            dataDirectory.resolve("functional-routes-" + storeKey + ".bin")),
                    observed);
            boundWorld = observed;
            replayStatus = "loaded";
            lastDetail = "functional routes loaded for the exact server/world identity";
            return true;
        } catch (IOException | RuntimeException error) {
            disableAfterPersistenceFailure("load functional route memory", error);
            return false;
        }
    }

    private FunctionalRouteMemory.WorldIdentity observedWorldIdentity() {
        if (authoritativeWorld == null) {
            throw new IllegalStateException("Paper world identity is not installed");
        }
        return authoritativeWorld;
    }

    private String currentServerIdentity() {
        var server = client.getCurrentServerEntry();
        if (server != null && server.address != null && !server.address.isBlank()) {
            return server.address.trim().toLowerCase(Locale.ROOT);
        }
        return client.isInSingleplayer() ? "singleplayer" : "unlisted-server";
    }

    private FunctionalRouteMemory.CellObservation observePlayerCell() {
        if (client.player == null || client.world == null) {
            return unavailableObservation();
        }
        return observe(new FunctionalRouteMemory.Coordinate(
                currentDimension(),
                client.player.getBlockX(),
                client.player.getBlockY(),
                client.player.getBlockZ()));
    }

    private FunctionalRouteMemory.CellObservation observe(
            FunctionalRouteMemory.Coordinate coordinate) {
        FunctionalRouteMemory.WorldIdentity identity = boundWorld != null
                ? boundWorld : observedWorldIdentity();
        if (client.player == null || client.world == null
                || !coordinate.dimension().equals(currentDimension())) {
            return new FunctionalRouteMemory.CellObservation(
                    identity, coordinate, false, false, false, "");
        }
        BlockPos feet = new BlockPos(coordinate.x(), coordinate.y(), coordinate.z());
        BlockPos head = feet.up();
        BlockPos floor = feet.down();
        if (!loaded(feet) || !loaded(head) || !loaded(floor)) {
            return new FunctionalRouteMemory.CellObservation(
                    identity, coordinate, false, false, false, "");
        }
        BlockState feetState = client.world.getBlockState(feet);
        BlockState headState = client.world.getBlockState(head);
        BlockState floorState = client.world.getBlockState(floor);
        boolean safe = routeBodyClear(feetState, feet)
                && routeBodyClear(headState, head)
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && floorState.getFluidState().isEmpty()
                && !hazard(feetState)
                && !hazard(headState)
                && !hazard(floorState)
                // The support probe already checks that sand/gravel is anchored.
                // Rejecting its material again loses real grounded breadcrumbs.
                && MinecraftWorkspaceProbe.hasStableFullTopSupport(client, floor);
        boolean occupied = safe
                && client.player.isOnGround()
                && client.player.getBlockX() == coordinate.x()
                && client.player.getBlockY() == coordinate.y()
                && client.player.getBlockZ() == coordinate.z();
        return new FunctionalRouteMemory.CellObservation(
                identity,
                coordinate,
                true,
                safe,
                occupied,
                stableCellFingerprint(floorState, feetState, headState));
    }

    private FunctionalRouteMemory.CellObservation unavailableObservation() {
        FunctionalRouteMemory.WorldIdentity identity = boundWorld != null
                ? boundWorld
                : new FunctionalRouteMemory.WorldIdentity("unavailable", "unavailable");
        return new FunctionalRouteMemory.CellObservation(
                identity,
                new FunctionalRouteMemory.Coordinate("minecraft:unknown", 0, 0, 0),
                false,
                false,
                false,
                "");
    }

    private boolean liveReverseConnected(
            FunctionalRouteMemory.Coordinate from,
            FunctionalRouteMemory.Coordinate to) {
        if (!from.adjacentTo(to)) return false;
        FunctionalRouteMemory.CellObservation first = observe(from);
        FunctionalRouteMemory.CellObservation second = observe(to);
        if (!first.loaded() || !first.safe() || !second.loaded() || !second.safe()) return false;
        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        if (dx == 0 && dz == 0) return false;
        if (dx == 0 || dz == 0) return true;
        // A diagonal is reversible only when at least one complete L-shaped
        // intermediate column is also a safe standing cell.
        FunctionalRouteMemory.Coordinate xCorner = new FunctionalRouteMemory.Coordinate(
                from.dimension(), to.x(), to.y(), from.z());
        FunctionalRouteMemory.Coordinate zCorner = new FunctionalRouteMemory.Coordinate(
                from.dimension(), from.x(), to.y(), to.z());
        return observe(xCorner).safe() || observe(zCorner).safe();
    }

    private boolean loaded(BlockPos position) {
        return position.getY() >= client.world.getBottomY()
                && position.getY() <= client.world.getTopYInclusive()
                && client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4);
    }

    private boolean routeBodyClear(BlockState state, BlockPos position) {
        return state.getCollisionShape(client.world, position).isEmpty()
                || playerOperablePortal(state);
    }

    private static boolean playerOperablePortal(BlockState state) {
        String id = Registries.BLOCK.getId(state.getBlock()).getPath();
        if (state.getBlock() instanceof DoorBlock) return !id.equals("iron_door");
        if (state.getBlock() instanceof TrapdoorBlock) return !id.equals("iron_trapdoor");
        return state.getBlock() instanceof FenceGateBlock;
    }

    private static boolean hazard(BlockState state) {
        return state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.WITHER_ROSE);
    }

    private static String stableCellFingerprint(
            BlockState floor,
            BlockState feet,
            BlockState head) {
        return "floor=" + stableStateFingerprint(floor)
                + ";feet=" + stableBodyStateFingerprint(feet)
                + ";head=" + stableBodyStateFingerprint(head);
    }

    /** A route-owned torch changes light, not the usable body geometry. */
    private static String stableBodyStateFingerprint(BlockState state) {
        if (state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH)) {
            return Registries.BLOCK.getId(Blocks.AIR).toString();
        }
        return stableStateFingerprint(state);
    }

    private static String stableStateFingerprint(BlockState state) {
        StringBuilder fingerprint = new StringBuilder(
                Registries.BLOCK.getId(state.getBlock()).toString());
        state.getEntries().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(Properties.OPEN))
                .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
                .forEach(entry -> fingerprint.append('|')
                        .append(entry.getKey().getName()).append('=')
                        .append(entry.getValue()));
        return fingerprint.toString();
    }

    private String unusedRouteId(
            String prefix,
            FunctionalRouteMemory.Coordinate entrance) {
        String base = prefix + entrance.x() + "," + entrance.y() + "," + entrance.z();
        String candidate = trimRouteId(base);
        int suffix = 1;
        while (memory.route(candidate).isPresent()) {
            candidate = trimRouteId(base + ':' + suffix++);
        }
        return candidate;
    }

    private static String objectivePrefix(String objective) {
        return "resource:" + shortHash(objective) + ':';
    }

    private static String requiredObjective(String objective) {
        String normalized = Objects.requireNonNullElse(objective, "").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("functional route objective cannot be blank");
        }
        return normalized;
    }

    private static String trimRouteId(String value) {
        return value.length() <= MAXIMUM_ROUTE_ID_CHARS
                ? value
                : value.substring(0, MAXIMUM_ROUTE_ID_CHARS);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean recordable(FunctionalRouteMemory.CellObservation observation) {
        return observation != null
                && observation.loaded()
                && observation.safe()
                && observation.physicallyOccupied()
                && !observation.stableFingerprint().isBlank();
    }

    private static long squaredDistance(
            FunctionalRouteMemory.Coordinate coordinate,
            BlockPos target) {
        long dx = (long) coordinate.x() - target.getX();
        long dy = (long) coordinate.y() - target.getY();
        long dz = (long) coordinate.z() - target.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private String currentDimension() {
        return client.world == null
                ? "minecraft:unknown"
                : client.world.getRegistryKey().getValue().toString();
    }

    private void disableAfterPersistenceFailure(String action, Throwable error) {
        logger.warn("Could not {}: {}", action, error.getMessage());
        memory = null;
        boundWorld = null;
        replay = null;
        recordingRouteId = "";
        recordingObjective = "";
        replayStatus = "persistence_failure";
        lastDetail = action + " failed; using ordinary live pathfinding";
    }

    private static String coordinateText(FunctionalRouteMemory.Coordinate coordinate) {
        return coordinate.x() + " " + coordinate.y() + " " + coordinate.z();
    }

    private static final class ReplayState {
        private final Purpose purpose;
        private final String routeId;
        private final FunctionalRouteMemory.ReplayCursor cursor;
        private final String objective;
        @SuppressWarnings("unused")
        private final long selectedAt;
        private FunctionalRouteMemory.TraversalPermit pendingPermit;
        private FunctionalRouteMemory.TraversalPermit lookaheadPermit;
        private long edgeDeadlineAt;

        private ReplayState(
                Purpose purpose,
                String routeId,
                FunctionalRouteMemory.ReplayCursor cursor,
                String objective,
                long selectedAt) {
            this.purpose = Objects.requireNonNull(purpose, "purpose");
            this.routeId = Objects.requireNonNull(routeId, "routeId");
            this.cursor = Objects.requireNonNull(cursor, "cursor");
            this.objective = Objects.requireNonNullElse(objective, "");
            this.selectedAt = selectedAt;
        }
    }
}
