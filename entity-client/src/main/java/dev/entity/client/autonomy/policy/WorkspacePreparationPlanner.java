package dev.entity.client.autonomy.policy;

import dev.entity.client.autonomy.policy.WorkspaceWorldProbe.Cell;
import dev.entity.client.autonomy.policy.WorkspaceWorldProbe.Face;
import dev.entity.client.autonomy.policy.WorkspaceWorldProbe.Point;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded policy for finding or preparing a workstation-sized local workspace.
 *
 * <p>The planner first searches reachable open standing cells. In a cramped
 * shaft it next prefers a one-block wall niche, keeping the current position as
 * both stand and exit. Only when that cannot expose an honest interaction face
 * may it carve a bounded deeper side chamber. It never proposes clearing fluid,
 * falling, hazardous, unbreakable, protected, or unloaded cells.</p>
 *
 * <p>This class performs no Minecraft actions. An executor applies one returned
 * step, observes the world's new geometry revision, and asks the planner again.
 * Runtime failures are reported through {@link #recordFailure}; candidate and
 * operation budgets prevent identical recovery loops.</p>
 */
public final class WorkspacePreparationPlanner {
    private static final List<Direction> DIRECTIONS = List.of(
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST,
            Direction.NORTH);

    private final Policy policy;
    private final Map<CandidateKey, Integer> candidateFailures = new LinkedHashMap<>();
    private final Set<CandidateKey> rejectedCandidates = new HashSet<>();
    private final Map<String, Integer> operationFailures = new LinkedHashMap<>();
    private long geometryRevision = Long.MIN_VALUE;

    public WorkspacePreparationPlanner() {
        this(Policy.defaults());
    }

    public WorkspacePreparationPlanner(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Finds a bounded, deterministic candidate in the probe's current geometry. */
    public synchronized Decision plan(Request request, WorkspaceWorldProbe probe) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(probe, "probe");
        long revision = probe.geometryRevision();
        synchronizeGeometry(revision);

        int failures = operationFailures.getOrDefault(request.operationId(), 0);
        if (failures >= policy.maximumTotalFailures()) {
            return Decision.blocked(
                    FailureKind.ATTEMPT_BUDGET_EXHAUSTED,
                    "workspace preparation stopped after " + failures
                            + " failed attempts for " + request.operationId(),
                    0);
        }

        Search search = new Search(request, probe, revision);
        search.originSafe = safeStand(request.origin(), Set.of(), search, true);
        search.originRelocatable = !search.originSafe && relocatableOrigin(search);
        Optional<WorkspacePlan> open = searchOpenWorkspace(search);
        if (open.isPresent()) return Decision.selected(open.orElseThrow(), search.inspectedCandidates);

        Optional<WorkspacePlan> niche = searchWallNiche(search);
        if (niche.isPresent()) return Decision.selected(niche.orElseThrow(), search.inspectedCandidates);

        Optional<WorkspacePlan> chamber = searchSideChamber(search);
        if (chamber.isPresent()) return Decision.selected(chamber.orElseThrow(), search.inspectedCandidates);

        FailureKind failure = search.diagnostics.dominantFailure();
        String detail = search.diagnostics.detailFor(failure);
        if (detail.isBlank()) {
            detail = "no supported workspace with a safe stand position, retained exit, and sightline"
                    + " was found within the bounded search";
        }
        return Decision.blocked(failure, detail, search.inspectedCandidates);
    }

    /**
     * Records an executor failure for a previously selected plan. A changed
     * geometry revision always forces a fresh candidate search; it clears
     * candidate-local attempts but deliberately retains the operation-wide
     * failure budget.
     */
    public synchronized FailureDecision recordFailure(
            WorkspacePlan plan,
            FailureKind failure,
            long observedGeometryRevision) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(failure, "failure");
        if (!failure.reportableByExecutor()) {
            throw new IllegalArgumentException("Planner-only failure cannot be reported: " + failure);
        }

        boolean geometryChanged = failure == FailureKind.GEOMETRY_CHANGED
                || observedGeometryRevision != plan.geometryRevision()
                || observedGeometryRevision != geometryRevision;
        if (geometryChanged) {
            geometryRevision = observedGeometryRevision;
            candidateFailures.clear();
            rejectedCandidates.clear();
        }

        String operationId = plan.candidate().operationId();
        int total = operationFailures.merge(operationId, 1, Integer::sum);
        int remaining = Math.max(0, policy.maximumTotalFailures() - total);
        if (total >= policy.maximumTotalFailures()) {
            rejectedCandidates.add(plan.candidate());
            return new FailureDecision(
                    Recovery.BLOCKED,
                    candidateFailures.getOrDefault(plan.candidate(), 0),
                    total,
                    remaining,
                    "workspace preparation exhausted its total failure budget after " + failure);
        }
        if (geometryChanged) {
            return new FailureDecision(
                    Recovery.REPLAN,
                    0,
                    total,
                    remaining,
                    "workspace geometry changed; discard the old candidate and probe again");
        }

        int candidateCount = candidateFailures.merge(plan.candidate(), 1, Integer::sum);
        boolean abandon = failure.abandonsCandidate()
                || candidateCount >= policy.maximumAttemptsPerCandidate();
        if (abandon) {
            rejectedCandidates.add(plan.candidate());
            String why = failure.abandonsCandidate()
                    ? failure.name().toLowerCase().replace('_', ' ')
                    : "candidate attempt budget exhausted";
            return new FailureDecision(
                    Recovery.REPLAN,
                    candidateCount,
                    total,
                    remaining,
                    why + "; choose another bounded workspace candidate");
        }
        return new FailureDecision(
                Recovery.RETRY_CANDIDATE,
                candidateCount,
                total,
                remaining,
                "retry candidate " + candidateCount + '/' + policy.maximumAttemptsPerCandidate()
                        + " after " + failure.name().toLowerCase().replace('_', ' '));
    }

    /**
     * Acknowledges that the executor discarded only its cached live plan.
     * External recovery is not evidence against a candidate, so all bounded
     * failure and rejection accounting deliberately remains unchanged.
     */
    public synchronized void resetTransient(String operationId) {
        requireText(operationId, "operationId");
    }

    /** Clears retry state for one successfully prepared or explicitly cancelled operation. */
    public synchronized void resetOperation(String operationId) {
        String checked = requireText(operationId, "operationId");
        operationFailures.remove(checked);
        candidateFailures.keySet().removeIf(key -> key.operationId().equals(checked));
        rejectedCandidates.removeIf(key -> key.operationId().equals(checked));
    }

    public synchronized void resetAll() {
        candidateFailures.clear();
        rejectedCandidates.clear();
        operationFailures.clear();
        geometryRevision = Long.MIN_VALUE;
    }

    public Policy policy() {
        return policy;
    }

    private Optional<WorkspacePlan> searchOpenWorkspace(Search search) {
        Set<Point> visitedStands = new HashSet<>();
        ArrayDeque<StandNode> queue = new ArrayDeque<>();
        if (search.originSafe) {
            visitedStands.add(search.request.origin());
            queue.addLast(new StandNode(search.request.origin(), search.request.origin()));
        } else if (search.originRelocatable) {
            // The player can already occupy the origin, but its support is not
            // trustworthy. Never retain that cell as an exit: seed only
            // genuinely safe adjacent stands and move away from it first.
            visitedStands.add(search.request.origin());
            for (Direction direction : DIRECTIONS) {
                Point stand = direction.move(search.request.origin());
                if (visitedStands.add(stand)
                        && safeStand(stand, Set.of(), search, false)) {
                    queue.addLast(new StandNode(stand, stand));
                }
            }
        } else {
            return Optional.empty();
        }

        while (!queue.isEmpty() && search.inspectedCandidates < policy.maximumCandidates()) {
            StandNode node = queue.removeFirst();
            for (Direction direction : DIRECTIONS) {
                if (search.inspectedCandidates >= policy.maximumCandidates()) break;
                Point placement = direction.move(node.stand());
                if (placement.equals(node.exit())) continue;
                search.inspectedCandidates++;
                CandidateEvaluation evaluation = evaluateOpenCandidate(
                        search, node.stand(), node.exit(), placement, direction);
                if (evaluation.plan().isPresent()) return evaluation.plan();
                search.diagnostics.record(evaluation.failure(), evaluation.detail());
            }

            for (Direction direction : DIRECTIONS) {
                Point nextStand = direction.move(node.stand());
                if (!withinRadius(search.request.origin(), nextStand)
                        || !visitedStands.add(nextStand)) continue;
                if (safeStand(nextStand, Set.of(), search, false)) {
                    queue.addLast(new StandNode(nextStand, node.stand()));
                }
            }
        }
        if (search.inspectedCandidates >= policy.maximumCandidates()) {
            search.diagnostics.record(
                    FailureKind.SEARCH_BUDGET_EXHAUSTED,
                    "open-workspace search reached its " + policy.maximumCandidates() + " candidate limit");
        }
        return Optional.empty();
    }

    private CandidateEvaluation evaluateOpenCandidate(
            Search search,
            Point stand,
            Point exit,
            Point placement,
            Direction direction) {
        Cell placementCell = cell(search.probe, placement);
        FailureKind occupancyFailure = occupancyFailure(placementCell);
        if (!placementCell.passable()) {
            return CandidateEvaluation.rejected(
                    occupancyFailure,
                    "open placement " + placement + " is " + describe(placementCell));
        }
        FailureKind supportFailure = supportFailure(placement.down(), search.probe);
        if (supportFailure != null) {
            return CandidateEvaluation.rejected(
                    supportFailure,
                    "open placement " + placement + " has no stable supported floor");
        }
        FailureKind overheadFailure = overheadFailure(cell(search.probe, placement.up()));
        if (overheadFailure != null) {
            return CandidateEvaluation.rejected(
                    overheadFailure,
                    "open placement " + placement + " has unsafe overhead");
        }
        LinkedHashSet<Point> clears = new LinkedHashSet<>();
        if (search.request.placementOverheadMustBePassable()) {
            ClearResult lid = collectClear(placement.up(), clears, search.probe);
            if (!lid.allowed()) {
                return CandidateEvaluation.rejected(
                        lid.failure(),
                        "open placement " + placement
                                + " cannot clear the required container lid space: "
                                + lid.detail());
            }
            FailureKind exposure = exposureFailure(clears, search.probe);
            if (exposure != null) {
                return CandidateEvaluation.rejected(
                        exposure,
                        "clearing container lid space would expose "
                                + exposure.name().toLowerCase().replace('_', ' '));
            }
        }

        CandidateKey key = new CandidateKey(
                search.request.operationId(), Mode.OPEN_AREA, placement, stand);
        if (rejectedCandidates.contains(key)) {
            return CandidateEvaluation.rejected(
                    FailureKind.CANDIDATE_EXHAUSTED,
                    "open candidate " + placement + " already exhausted its retry budget");
        }
        if (!search.probe.hasSightline(stand, placement, direction.visibleFace(), clears)) {
            return CandidateEvaluation.rejected(
                    FailureKind.SIGHTLINE_LOST,
                    "open candidate " + placement + " has no honest interaction sightline from " + stand);
        }

        List<PreparationStep> steps;
        if (!stand.equals(search.request.origin())) {
            steps = List.of(new PreparationStep(
                    StepKind.MOVE_TO_STAND, stand, StepPurpose.POSITION_FOR_INTERACTION));
        } else if (clears.contains(placement.up())) {
            steps = List.of(new PreparationStep(
                    StepKind.BREAK_BLOCK, placement.up(), StepPurpose.CONTAINER_LID_SPACE));
        } else {
            steps = List.of();
        }
        return CandidateEvaluation.selected(new WorkspacePlan(
                key,
                placement,
                stand,
                exit,
                direction.visibleFace(),
                clears,
                steps,
                search.revision,
                candidateFailures.getOrDefault(key, 0) + 1,
                stand.equals(search.request.origin())
                        ? clears.isEmpty()
                        ? "supported open placement is reachable from the current stand position"
                        : "clear exact container lid space from the current stand position"
                        : "move through open space to a supported placement with a retained exit"));
    }

    /**
     * A one-block wall niche is the minimal usable answer in an ordinary 1x1
     * shaft: the player keeps the shaft as both stand and exit while only the
     * adjacent workstation cell is cleared. A deeper chamber remains the
     * fallback when the niche cannot expose a legitimate interaction face.
     */
    private Optional<WorkspacePlan> searchWallNiche(Search search) {
        if (!search.originSafe) return Optional.empty();
        for (Direction direction : DIRECTIONS) {
            if (search.inspectedCandidates >= policy.maximumCandidates()) {
                search.diagnostics.record(
                        FailureKind.SEARCH_BUDGET_EXHAUSTED,
                        "wall-niche search reached its candidate limit");
                return Optional.empty();
            }
            search.inspectedCandidates++;
            CandidateEvaluation evaluation = evaluateWallNiche(search, direction);
            if (evaluation.plan().isPresent()) return evaluation.plan();
            search.diagnostics.record(evaluation.failure(), evaluation.detail());
        }
        return Optional.empty();
    }

    private CandidateEvaluation evaluateWallNiche(Search search, Direction direction) {
        Point stand = search.request.origin();
        Point placement = direction.move(stand);
        LinkedHashSet<Point> clears = new LinkedHashSet<>();
        ClearResult station = collectClear(placement, clears, search.probe);
        if (!station.allowed()) return chamberRejection(station, placement);
        if (clears.isEmpty()) {
            return CandidateEvaluation.rejected(
                    FailureKind.NO_SAFE_CANDIDATE,
                    "wall niche " + placement + " is already open and was considered by open search");
        }

        FailureKind support = supportFailure(placement.down(), search.probe);
        if (support != null) {
            return CandidateEvaluation.rejected(
                    support,
                    "wall-niche placement " + placement + " lacks full stable top support");
        }
        FailureKind overhead = overheadFailure(cell(search.probe, placement.up()));
        if (overhead != null) {
            return CandidateEvaluation.rejected(
                    overhead,
                    "wall-niche placement " + placement + " has unsafe overhead");
        }

        // In a full-height 1x1 shaft, the adjacent head-level wall can occlude
        // the lower placement cell from the player's eye. Open that exact
        // disposable block first, then replan and clear the placement cell.
        // This remains a two-block niche, not permission to excavate arbitrary
        // terrain: both cells pass the same typed clearability and exposure
        // fences, and the upper block itself must be honestly visible now.
        if (!search.probe.hasSightline(
                stand, placement, direction.visibleFace(), Set.of())) {
            Point access = placement.up();
            ClearResult accessClear = collectClear(access, clears, search.probe);
            if (!accessClear.allowed() || !clears.contains(access)) {
                return CandidateEvaluation.rejected(
                        accessClear.allowed()
                                ? FailureKind.SIGHTLINE_LOST : accessClear.failure(),
                        "wall-niche placement " + placement
                                + " has no clearable head-level access sightline");
            }
            if (!search.probe.hasSightline(
                    stand, access, direction.visibleFace(), Set.of())) {
                return CandidateEvaluation.rejected(
                        FailureKind.SIGHTLINE_LOST,
                        "head-level access block " + access
                                + " is not honestly visible from " + stand);
            }
        }
        if (clears.size() > policy.maximumPreparationBreaks()) {
            return CandidateEvaluation.rejected(
                    FailureKind.NO_SAFE_CANDIDATE,
                    "wall niche needs " + clears.size() + " block breaks, above the limit of "
                            + policy.maximumPreparationBreaks());
        }
        FailureKind exposure = exposureFailure(clears, search.probe);
        if (exposure != null) {
            return CandidateEvaluation.rejected(
                    exposure,
                    "clearing the wall niche would expose "
                            + exposure.name().toLowerCase().replace('_', ' '));
        }

        CandidateKey key = new CandidateKey(
                search.request.operationId(), Mode.WALL_NICHE, placement, stand);
        if (rejectedCandidates.contains(key)) {
            return CandidateEvaluation.rejected(
                    FailureKind.CANDIDATE_EXHAUSTED,
                    "wall-niche candidate " + placement + " already exhausted its retry budget");
        }
        if (!search.probe.hasSightline(stand, placement, direction.visibleFace(), clears)) {
            return CandidateEvaluation.rejected(
                    FailureKind.SIGHTLINE_LOST,
                    "cleared wall niche would not expose the workstation face from " + stand);
        }

        Point access = placement.up();
        boolean clearsAccessFirst = clears.contains(access);
        return CandidateEvaluation.selected(new WorkspacePlan(
                key,
                placement,
                stand,
                stand,
                direction.visibleFace(),
                clears,
                List.of(new PreparationStep(
                        StepKind.BREAK_BLOCK,
                        clearsAccessFirst ? access : placement,
                        clearsAccessFirst ? StepPurpose.HEADROOM : StepPurpose.PLACEMENT_SPACE)),
                search.revision,
                candidateFailures.getOrDefault(key, 0) + 1,
                clearsAccessFirst
                        ? "open head-level access before clearing the natural-terrain wall niche"
                        : "clear one natural-terrain wall cell while retaining the shaft as stand and exit"));
    }

    private Optional<WorkspacePlan> searchSideChamber(Search search) {
        if (!search.originSafe && !search.originRelocatable) return Optional.empty();
        for (Direction direction : DIRECTIONS) {
            if (search.inspectedCandidates >= policy.maximumCandidates()) {
                search.diagnostics.record(
                        FailureKind.SEARCH_BUDGET_EXHAUSTED,
                        "side-chamber search reached its candidate limit");
                return Optional.empty();
            }
            search.inspectedCandidates++;
            CandidateEvaluation evaluation = evaluateSideChamber(
                    search, direction, !search.originSafe);
            if (evaluation.plan().isPresent()) return evaluation.plan();
            search.diagnostics.record(evaluation.failure(), evaluation.detail());
        }
        return Optional.empty();
    }

    private CandidateEvaluation evaluateSideChamber(
            Search search,
            Direction direction,
            boolean selfExtraction) {
        Point origin = search.request.origin();
        Point stand = direction.move(origin);
        Point exit = selfExtraction ? stand : origin;
        Point placement = direction.move(stand);
        LinkedHashSet<Point> clears = new LinkedHashSet<>();

        ClearResult standHead = collectClear(stand.up(), clears, search.probe);
        if (!standHead.allowed()) return chamberRejection(standHead, stand.up());
        ClearResult standFeet = collectClear(stand, clears, search.probe);
        if (!standFeet.allowed()) return chamberRejection(standFeet, stand);
        ClearResult station = collectClear(placement, clears, search.probe);
        if (!station.allowed()) return chamberRejection(station, placement);

        if (clears.size() > policy.maximumPreparationBreaks()) {
            return CandidateEvaluation.rejected(
                    FailureKind.NO_SAFE_CANDIDATE,
                    "side chamber needs " + clears.size() + " block breaks, above the limit of "
                            + policy.maximumPreparationBreaks());
        }
        FailureKind standSupport = supportFailure(stand.down(), search.probe);
        if (standSupport != null) {
            return CandidateEvaluation.rejected(
                    standSupport,
                    "side chamber stand " + stand + " is above an unsafe drop or unstable floor");
        }
        FailureKind stationSupport = supportFailure(placement.down(), search.probe);
        if (stationSupport != null) {
            return CandidateEvaluation.rejected(
                    stationSupport,
                    "side chamber placement " + placement + " lacks stable support");
        }
        FailureKind standOverhead = overheadFailure(cell(search.probe, stand.up(2)));
        if (standOverhead != null) {
            return CandidateEvaluation.rejected(
                    standOverhead,
                    "side chamber stand " + stand + " has unsafe overhead");
        }
        FailureKind stationOverhead = overheadFailure(cell(search.probe, placement.up()));
        if (stationOverhead != null) {
            return CandidateEvaluation.rejected(
                    stationOverhead,
                    "side chamber placement " + placement + " has unsafe overhead");
        }
        FailureKind exposure = exposureFailure(clears, search.probe);
        if (exposure != null) {
            return CandidateEvaluation.rejected(
                    exposure,
                    "clearing the side chamber would expose "
                            + exposure.name().toLowerCase().replace('_', ' '));
        }
        if (!search.probe.hasStandCollisionClearance(stand, clears)) {
            return CandidateEvaluation.rejected(
                    FailureKind.NO_SAFE_CANDIDATE,
                    "side chamber does not provide collision-free player clearance");
        }

        CandidateKey key = new CandidateKey(
                search.request.operationId(), Mode.SIDE_CHAMBER, placement, stand);
        if (rejectedCandidates.contains(key)) {
            return CandidateEvaluation.rejected(
                    FailureKind.CANDIDATE_EXHAUSTED,
                    "side-chamber candidate " + placement + " already exhausted its retry budget");
        }
        if (!search.probe.hasSightline(stand, placement, direction.visibleFace(), clears)) {
            return CandidateEvaluation.rejected(
                    FailureKind.SIGHTLINE_LOST,
                    "prepared chamber would not expose the workstation face from " + stand);
        }

        List<PreparationStep> steps = new ArrayList<>();
        if (clears.contains(stand.up())) {
            steps.add(new PreparationStep(StepKind.BREAK_BLOCK, stand.up(), StepPurpose.HEADROOM));
        }
        if (clears.contains(stand)) {
            steps.add(new PreparationStep(StepKind.BREAK_BLOCK, stand, StepPurpose.STAND_SPACE));
        }
        if (clears.contains(placement)) {
            steps.add(new PreparationStep(StepKind.BREAK_BLOCK, placement, StepPurpose.PLACEMENT_SPACE));
        }
        steps.add(new PreparationStep(
                StepKind.MOVE_TO_STAND, stand, StepPurpose.POSITION_FOR_INTERACTION));
        return CandidateEvaluation.selected(new WorkspacePlan(
                key,
                placement,
                stand,
                exit,
                direction.visibleFace(),
                clears,
                steps,
                search.revision,
                candidateFailures.getOrDefault(key, 0) + 1,
                selfExtraction
                        ? "prepare a bounded side chamber, leave the unsafe origin, and retain "
                        + stand + " as the safe exit"
                        : "prepare a minimal side chamber and retain " + exit + " as the exit"));
    }

    private CandidateEvaluation chamberRejection(ClearResult result, Point point) {
        return CandidateEvaluation.rejected(
                result.failure(),
                "side chamber cannot clear " + point + ": " + result.detail());
    }

    private boolean safeStand(
            Point feet,
            Set<Point> assumedOpen,
            Search search,
            boolean recordFailure) {
        Point head = feet.up();
        Cell feetCell = cell(search.probe, feet);
        Cell headCell = cell(search.probe, head);
        if (!assumedOpen.contains(feet) && !feetCell.passable()) {
            if (recordFailure) search.diagnostics.record(
                    occupancyFailure(feetCell),
                    "standing position " + feet + " is " + describe(feetCell));
            return false;
        }
        if (!assumedOpen.contains(head) && !headCell.passable()) {
            if (recordFailure) search.diagnostics.record(
                    occupancyFailure(headCell),
                    "standing headroom " + head + " is " + describe(headCell));
            return false;
        }
        if (!search.probe.hasStandCollisionClearance(feet, assumedOpen)) {
            if (recordFailure) search.diagnostics.record(
                    FailureKind.NO_SAFE_CANDIDATE,
                    "standing position " + feet + " is not collision-free");
            return false;
        }
        FailureKind support = supportFailure(feet.down(), search.probe);
        if (support != null) {
            if (recordFailure) search.diagnostics.record(
                    support,
                    "standing position " + feet + " is above an unsafe drop or unstable floor");
            return false;
        }
        FailureKind overhead = overheadFailure(cell(search.probe, feet.up(2)));
        if (overhead != null) {
            if (recordFailure) search.diagnostics.record(
                    overhead,
                    "standing position " + feet + " has unsafe overhead");
            return false;
        }
        return true;
    }

    /**
     * Allows recovery from a bad floor only when the player's occupied body
     * column is itself clear and not exposed to a dangerous overhead cell.
     * Support is intentionally ignored here: the whole purpose of the
     * resulting bounded plan is to leave that support behind.
     */
    private boolean relocatableOrigin(Search search) {
        Point feet = search.request.origin();
        Cell feetCell = cell(search.probe, feet);
        Cell headCell = cell(search.probe, feet.up());
        return feetCell.passable()
                && headCell.passable()
                && search.probe.hasStandCollisionClearance(feet, Set.of())
                && overheadFailure(cell(search.probe, feet.up(2))) == null;
    }

    private boolean withinRadius(Point origin, Point point) {
        return point.y() == origin.y()
                && origin.horizontalManhattanDistance(point) <= policy.horizontalSearchRadius();
    }

    private static ClearResult collectClear(
            Point point,
            Set<Point> clears,
            WorkspaceWorldProbe probe) {
        Cell cell = cell(probe, point);
        if (cell.passable()) return ClearResult.permitted();
        if (cell.clearable()) {
            clears.add(point);
            return ClearResult.permitted();
        }
        FailureKind failure = occupancyFailure(cell);
        return new ClearResult(false, failure, describe(cell));
    }

    private static FailureKind exposureFailure(
            Set<Point> clears,
            WorkspaceWorldProbe probe) {
        for (Point cleared : clears) {
            Point above = cleared.up();
            if (!clears.contains(above)) {
                FailureKind overhead = overheadFailure(cell(probe, above));
                if (overhead != null) return overhead;
            }
            for (Direction direction : DIRECTIONS) {
                Point neighbour = direction.move(cleared);
                if (!clears.contains(neighbour)) {
                    Cell adjacent = cell(probe, neighbour);
                    if (adjacent == Cell.FLUID) return FailureKind.FLUID;
                    if (adjacent == Cell.HAZARD) return FailureKind.HAZARD;
                    if (adjacent == Cell.UNLOADED) return FailureKind.UNLOADED;
                }
            }
        }
        return null;
    }

    private static FailureKind occupancyFailure(Cell cell) {
        return switch (cell) {
            case FLUID -> FailureKind.FLUID;
            case FALLING -> FailureKind.FALLING_BLOCK;
            case HAZARD -> FailureKind.HAZARD;
            case UNBREAKABLE -> FailureKind.UNBREAKABLE;
            case PROTECTED -> FailureKind.PROTECTED;
            case UNLOADED -> FailureKind.UNLOADED;
            case SOLID, OPEN, REPLACEABLE -> FailureKind.NO_SAFE_CANDIDATE;
        };
    }

    private static FailureKind supportFailure(
            Point supportPoint,
            WorkspaceWorldProbe probe) {
        Cell support = cell(probe, supportPoint);
        return switch (support) {
            case FLUID -> FailureKind.FLUID;
            // Sand, gravel, and concrete powder are safe standing surfaces
            // when the live probe proves their complete falling-block column
            // is anchored. They remain forbidden as excavation/overhead cells.
            case FALLING -> probe.hasFullTopSupport(supportPoint)
                    ? null
                    : FailureKind.FALLING_BLOCK;
            case HAZARD -> FailureKind.HAZARD;
            case UNLOADED -> FailureKind.UNLOADED;
            default -> probe.hasFullTopSupport(supportPoint)
                    ? null
                    : FailureKind.UNSAFE_DROP;
        };
    }

    private static FailureKind overheadFailure(Cell overhead) {
        return switch (overhead) {
            case FLUID -> FailureKind.FLUID;
            case FALLING -> FailureKind.FALLING_BLOCK;
            case HAZARD -> FailureKind.HAZARD;
            case UNLOADED -> FailureKind.UNLOADED;
            default -> null;
        };
    }

    private void synchronizeGeometry(long revision) {
        if (geometryRevision == revision) return;
        geometryRevision = revision;
        candidateFailures.clear();
        rejectedCandidates.clear();
    }

    private static Cell cell(WorkspaceWorldProbe probe, Point point) {
        return Objects.requireNonNull(probe.cell(point), "probe cell at " + point);
    }

    private static String describe(Cell cell) {
        return cell.name().toLowerCase().replace('_', ' ');
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public record Request(
            String operationId,
            Point origin,
            boolean placementOverheadMustBePassable) {
        public Request(String operationId, Point origin) {
            this(operationId, origin, false);
        }

        public Request {
            operationId = requireText(operationId, "operationId");
            Objects.requireNonNull(origin, "origin");
        }
    }

    public record Policy(
            int horizontalSearchRadius,
            int maximumCandidates,
            int maximumPreparationBreaks,
            int maximumAttemptsPerCandidate,
            int maximumTotalFailures) {
        public Policy {
            if (horizontalSearchRadius < 1
                    || maximumCandidates < 1
                    || maximumPreparationBreaks < 1
                    || maximumAttemptsPerCandidate < 1
                    || maximumTotalFailures < maximumAttemptsPerCandidate) {
                throw new IllegalArgumentException("invalid workspace preparation policy");
            }
        }

        public static Policy defaults() {
            return new Policy(3, 32, 3, 2, 8);
        }
    }

    public enum DecisionState {
        READY,
        PREPARE,
        BLOCKED
    }

    public enum Mode {
        OPEN_AREA,
        WALL_NICHE,
        SIDE_CHAMBER
    }

    public enum StepKind {
        BREAK_BLOCK,
        MOVE_TO_STAND
    }

    public enum StepPurpose {
        HEADROOM,
        STAND_SPACE,
        PLACEMENT_SPACE,
        CONTAINER_LID_SPACE,
        POSITION_FOR_INTERACTION
    }

    public enum Recovery {
        RETRY_CANDIDATE,
        REPLAN,
        BLOCKED
    }

    /** Typed planner and executor failures used for bounded recovery decisions. */
    public enum FailureKind {
        FLUID(true, true),
        FALLING_BLOCK(true, true),
        UNSAFE_DROP(true, true),
        UNBREAKABLE(true, true),
        PROTECTED(true, true),
        HAZARD(true, true),
        UNLOADED(true, true),
        SIGHTLINE_LOST(true, true),
        MOVE_FAILED(true, false),
        TOOL_PREEMPTED(true, false),
        BREAK_STALLED(true, false),
        BREAK_REJECTED(true, false),
        PLACE_REJECTED(true, false),
        INTERACTION_REJECTED(true, false),
        GEOMETRY_CHANGED(true, false),
        CANDIDATE_EXHAUSTED(false, true),
        SEARCH_BUDGET_EXHAUSTED(false, true),
        ATTEMPT_BUDGET_EXHAUSTED(false, true),
        NO_SAFE_CANDIDATE(false, true);

        private final boolean reportableByExecutor;
        private final boolean abandonsCandidate;

        FailureKind(boolean reportableByExecutor, boolean abandonsCandidate) {
            this.reportableByExecutor = reportableByExecutor;
            this.abandonsCandidate = abandonsCandidate;
        }

        public boolean reportableByExecutor() {
            return reportableByExecutor;
        }

        public boolean abandonsCandidate() {
            return abandonsCandidate;
        }
    }

    public record CandidateKey(
            String operationId,
            Mode mode,
            Point placement,
            Point stand) {
        public CandidateKey {
            operationId = requireText(operationId, "operationId");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(stand, "stand");
            if (placement.equals(stand)) {
                throw new IllegalArgumentException("placement and stand must differ");
            }
        }
    }

    public record PreparationStep(StepKind kind, Point position, StepPurpose purpose) {
        public PreparationStep {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(purpose, "purpose");
        }
    }

    public record WorkspacePlan(
            CandidateKey candidate,
            Point placement,
            Point stand,
            Point exit,
            Face interactionFace,
            Set<Point> plannedClears,
            List<PreparationStep> steps,
            long geometryRevision,
            int attemptNumber,
            String detail) {
        public WorkspacePlan {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(stand, "stand");
            Objects.requireNonNull(exit, "exit");
            Objects.requireNonNull(interactionFace, "interactionFace");
            plannedClears = Set.copyOf(Objects.requireNonNull(plannedClears, "plannedClears"));
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            if (!candidate.placement().equals(placement) || !candidate.stand().equals(stand)) {
                throw new IllegalArgumentException("candidate and plan geometry differ");
            }
            if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record Decision(
            DecisionState state,
            Optional<WorkspacePlan> plan,
            Optional<FailureKind> blockingFailure,
            String detail,
            int inspectedCandidates) {
        public Decision {
            Objects.requireNonNull(state, "state");
            plan = Objects.requireNonNull(plan, "plan");
            blockingFailure = Objects.requireNonNull(blockingFailure, "blockingFailure");
            detail = Objects.requireNonNullElse(detail, "");
            if (inspectedCandidates < 0) throw new IllegalArgumentException("negative candidate count");
            if (state == DecisionState.BLOCKED && blockingFailure.isEmpty()) {
                throw new IllegalArgumentException("blocked decision requires a failure");
            }
            if (state != DecisionState.BLOCKED && plan.isEmpty()) {
                throw new IllegalArgumentException("selected decision requires a plan");
            }
        }

        private static Decision selected(WorkspacePlan plan, int inspectedCandidates) {
            DecisionState state = plan.steps().isEmpty() ? DecisionState.READY : DecisionState.PREPARE;
            return new Decision(
                    state, Optional.of(plan), Optional.empty(), plan.detail(), inspectedCandidates);
        }

        private static Decision blocked(FailureKind failure, String detail, int inspectedCandidates) {
            return new Decision(
                    DecisionState.BLOCKED,
                    Optional.empty(),
                    Optional.of(failure),
                    detail,
                    inspectedCandidates);
        }
    }

    public record FailureDecision(
            Recovery recovery,
            int candidateFailures,
            int totalFailures,
            int remainingTotalFailures,
            String detail) {
        public FailureDecision {
            Objects.requireNonNull(recovery, "recovery");
            if (candidateFailures < 0 || totalFailures < 0 || remainingTotalFailures < 0) {
                throw new IllegalArgumentException("negative failure counter");
            }
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    private enum Direction {
        EAST(1, 0, Face.WEST),
        SOUTH(0, 1, Face.NORTH),
        WEST(-1, 0, Face.EAST),
        NORTH(0, -1, Face.SOUTH);

        private final int dx;
        private final int dz;
        private final Face visibleFace;

        Direction(int dx, int dz, Face visibleFace) {
            this.dx = dx;
            this.dz = dz;
            this.visibleFace = visibleFace;
        }

        private Point move(Point point) {
            return point.add(dx, 0, dz);
        }

        private Face visibleFace() {
            return visibleFace;
        }
    }

    private static final class Search {
        private final Request request;
        private final WorkspaceWorldProbe probe;
        private final long revision;
        private final SearchDiagnostics diagnostics = new SearchDiagnostics();
        private int inspectedCandidates;
        private boolean originSafe;
        private boolean originRelocatable;

        private Search(Request request, WorkspaceWorldProbe probe, long revision) {
            this.request = request;
            this.probe = probe;
            this.revision = revision;
        }
    }

    private record StandNode(Point stand, Point exit) {
    }

    private record CandidateEvaluation(
            Optional<WorkspacePlan> plan,
            FailureKind failure,
            String detail) {
        private static CandidateEvaluation selected(WorkspacePlan plan) {
            return new CandidateEvaluation(Optional.of(plan), null, "");
        }

        private static CandidateEvaluation rejected(FailureKind failure, String detail) {
            return new CandidateEvaluation(
                    Optional.empty(), Objects.requireNonNull(failure, "failure"), detail);
        }
    }

    private record ClearResult(boolean allowed, FailureKind failure, String detail) {
        private static ClearResult permitted() {
            return new ClearResult(true, null, "");
        }
    }

    private static final class SearchDiagnostics {
        private static final List<FailureKind> PRIORITY = List.of(
                FailureKind.PROTECTED,
                FailureKind.UNBREAKABLE,
                FailureKind.FLUID,
                FailureKind.FALLING_BLOCK,
                FailureKind.HAZARD,
                FailureKind.UNSAFE_DROP,
                FailureKind.UNLOADED,
                FailureKind.SIGHTLINE_LOST,
                FailureKind.CANDIDATE_EXHAUSTED,
                FailureKind.SEARCH_BUDGET_EXHAUSTED,
                FailureKind.NO_SAFE_CANDIDATE);

        private final Map<FailureKind, Integer> counts = new EnumMap<>(FailureKind.class);
        private final Map<FailureKind, String> firstDetails = new EnumMap<>(FailureKind.class);

        private void record(FailureKind failure, String detail) {
            if (failure == null) return;
            counts.merge(failure, 1, Integer::sum);
            firstDetails.putIfAbsent(failure, Objects.requireNonNullElse(detail, ""));
        }

        private FailureKind dominantFailure() {
            for (FailureKind candidate : PRIORITY) {
                if (counts.containsKey(candidate)) return candidate;
            }
            return FailureKind.NO_SAFE_CANDIDATE;
        }

        private String detailFor(FailureKind failure) {
            return firstDetails.getOrDefault(failure, "");
        }
    }
}
