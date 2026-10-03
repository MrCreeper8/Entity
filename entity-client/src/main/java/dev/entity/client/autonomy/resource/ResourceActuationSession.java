package dev.entity.client.autonomy.resource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Operation-scoped custody for one bounded Resource Economy actuator.
 *
 * <p>The session deliberately separates candidate discovery from physical
 * mutation. Discovery may inspect several loaded candidates, but the final
 * boundary receives exactly one immutable coordinate. A crop keeps that
 * coordinate in custody until the expected drop is settled. A crop additionally
 * retains custody until the replacement plant is observed, so another target can
 * never begin between mutation, replant acknowledgement, and collection.</p>
 */
public final class ResourceActuationSession {
    /**
     * One stable discovery snapshot, paged only to bound classification cost.
     * Entries are hints, never retained admission: callers re-observe live state
     * and authority when each page is classified. A new operation owns a new scan.
     */
    public static final class CandidateScan<T> {
        private final List<T> ranked;
        private int cursor;

        public CandidateScan(Collection<T> ranked) {
            this.ranked = List.copyOf(Objects.requireNonNull(ranked, "ranked"));
        }

        public List<T> nextPage(int classificationBudget, java.util.function.Predicate<T> eligible) {
            if (classificationBudget < 1) throw new IllegalArgumentException("classification budget must be positive");
            Objects.requireNonNull(eligible, "eligible");
            ArrayList<T> page = new ArrayList<>();
            while (cursor < ranked.size() && page.size() < classificationBudget) {
                T candidate = ranked.get(cursor++);
                if (eligible.test(candidate)) page.add(candidate);
            }
            return List.copyOf(page);
        }

        public boolean pending() { return cursor < ranked.size(); }
        public int inspected() { return cursor; }
        public int total() { return ranked.size(); }
    }

    public enum SourceKind {
        MINE,
        HARVEST;

        public static SourceKind parse(String value) {
            String normalized = Objects.requireNonNullElse(value, "")
                    .trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("EXTRACTION")) return MINE;
            try {
                return valueOf(normalized);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(
                        "worldSourceKind must be EXTRACTION/MINE or HARVEST", invalid);
            }
        }
    }

    public enum TargetKind {
        MINE,
        NATURAL_LOG,
        MATURE_CROP,
        FLOWER
    }

    public enum Phase {
        SEARCHING,
        ROUTING,
        BREAKING,
        REPLANTING,
        VERIFYING_REPLANT,
        AWAITING_DROP,
        BLOCKED
    }

    public enum BreakDecision {
        EXACT_TARGET,
        MINING_ROUTE,
        DENY
    }

    public record Coordinate(String dimension, int x, int y, int z) {
        public Coordinate {
            dimension = Objects.requireNonNullElse(dimension, "").trim();
            if (dimension.isBlank()) {
                throw new IllegalArgumentException("dimension is required");
            }
        }
    }

    public record Candidate(
            Coordinate coordinate,
            TargetKind kind,
            String blockId,
            Optional<String> requiredReplantItem,
            Set<Coordinate> connectedNaturalTreeLogs,
            Map<Coordinate, String> connectedNaturalTreeAccessBreaks,
            Set<Coordinate> connectedNaturalTreeAccessObjectives,
            boolean admitted,
            double distanceSquared,
            String detail) {
        public Candidate {
            coordinate = Objects.requireNonNull(coordinate, "coordinate");
            kind = Objects.requireNonNull(kind, "kind");
            blockId = normalizeId(blockId);
            requiredReplantItem = Objects.requireNonNull(
                    requiredReplantItem, "requiredReplantItem")
                    .map(ResourceActuationSession::normalizeId);
            connectedNaturalTreeLogs = Set.copyOf(Objects.requireNonNull(
                    connectedNaturalTreeLogs, "connectedNaturalTreeLogs"));
            connectedNaturalTreeAccessBreaks = normalizeCoordinateIds(
                    connectedNaturalTreeAccessBreaks);
            connectedNaturalTreeAccessObjectives = Set.copyOf(Objects.requireNonNull(
                    connectedNaturalTreeAccessObjectives,
                    "connectedNaturalTreeAccessObjectives"));
            detail = Objects.requireNonNullElse(detail, "").trim();
            if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0) {
                throw new IllegalArgumentException(
                        "candidate distance must be finite and nonnegative");
            }
            if ((kind == TargetKind.MATURE_CROP) != requiredReplantItem.isPresent()) {
                throw new IllegalArgumentException(
                        "only a mature crop carries an exact replant item");
            }
            if (kind == TargetKind.NATURAL_LOG) {
                if (admitted && (connectedNaturalTreeLogs.isEmpty()
                        || !connectedNaturalTreeLogs.contains(coordinate))) {
                    throw new IllegalArgumentException(
                            "an admitted natural-log candidate requires its exact connected tree");
                }
                if (!connectedNaturalTreeLogs.isEmpty()
                        && !connectedNaturalTreeLogs.contains(coordinate)) {
                    throw new IllegalArgumentException(
                            "a retained connected tree must contain its candidate target");
                }
                for (Coordinate treeLog : connectedNaturalTreeLogs) {
                    if (!treeLog.dimension().equals(coordinate.dimension())) {
                        throw new IllegalArgumentException(
                                "a connected tree cannot cross dimensions");
                    }
                }
                for (Coordinate access : connectedNaturalTreeAccessBreaks.keySet()) {
                    if (!access.dimension().equals(coordinate.dimension())) {
                        throw new IllegalArgumentException(
                                "a connected tree access budget cannot cross dimensions");
                    }
                    if (connectedNaturalTreeLogs.contains(access)) {
                        throw new IllegalArgumentException(
                                "tree access cells cannot overlap committed logs");
                    }
                }
                if (!connectedNaturalTreeAccessBreaks.keySet().containsAll(
                        connectedNaturalTreeAccessObjectives)) {
                    throw new IllegalArgumentException(
                            "tree access objectives must be a subset of its exact canopy authority");
                }
            } else if (!connectedNaturalTreeLogs.isEmpty()
                    || !connectedNaturalTreeAccessBreaks.isEmpty()
                    || !connectedNaturalTreeAccessObjectives.isEmpty()) {
                throw new IllegalArgumentException(
                        "only a natural-log candidate may retain a connected tree job");
            }
        }

        public Candidate(
                Coordinate coordinate,
                TargetKind kind,
                String blockId,
                Optional<String> requiredReplantItem,
                Set<Coordinate> connectedNaturalTreeLogs,
                Map<Coordinate, String> connectedNaturalTreeAccessBreaks,
                boolean admitted,
                double distanceSquared,
                String detail) {
            this(coordinate, kind, blockId, requiredReplantItem,
                    connectedNaturalTreeLogs, connectedNaturalTreeAccessBreaks,
                    connectedNaturalTreeAccessBreaks.keySet(), admitted,
                    distanceSquared, detail);
        }

        public Candidate(
                Coordinate coordinate,
                TargetKind kind,
                String blockId,
                Optional<String> requiredReplantItem,
                Set<Coordinate> connectedNaturalTreeLogs,
                boolean admitted,
                double distanceSquared,
                String detail) {
            this(coordinate, kind, blockId, requiredReplantItem,
                    connectedNaturalTreeLogs, Map.of(), Set.of(), admitted,
                    distanceSquared, detail);
        }
    }

    public record Target(
            Coordinate coordinate,
            TargetKind kind,
            String blockId,
            Optional<String> requiredReplantItem,
            boolean retainedNaturalTreeProof) {
        public Target {
            coordinate = Objects.requireNonNull(coordinate, "coordinate");
            kind = Objects.requireNonNull(kind, "kind");
            blockId = normalizeId(blockId);
            requiredReplantItem = Objects.requireNonNull(
                    requiredReplantItem, "requiredReplantItem")
                    .map(ResourceActuationSession::normalizeId);
            if ((kind == TargetKind.MATURE_CROP) != requiredReplantItem.isPresent()) {
                throw new IllegalArgumentException(
                        "only a mature crop carries an exact replant item");
            }
            if (retainedNaturalTreeProof && kind != TargetKind.NATURAL_LOG) {
                throw new IllegalArgumentException(
                        "only a committed natural-tree log may retain tree proof");
            }
        }
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparingDouble(Candidate::distanceSquared)
                    .thenComparing(candidate -> candidate.coordinate().dimension())
                    .thenComparingInt(candidate -> candidate.coordinate().x())
                    .thenComparingInt(candidate -> candidate.coordinate().y())
                    .thenComparingInt(candidate -> candidate.coordinate().z());
    private static final Comparator<Coordinate> TREE_LOG_ORDER =
            Comparator.comparingInt(Coordinate::y)
                    .thenComparingInt(Coordinate::x)
                    .thenComparingInt(Coordinate::z)
                    .thenComparing(Coordinate::dimension);

    private final String operationId;
    private final SourceKind sourceKind;
    private final Set<String> objectiveBlockIds;
    private final Set<String> expectedItemIds;
    private final Optional<String> reservedReplantItem;
    private Phase phase = Phase.SEARCHING;
    private Target target;
    private final ArrayDeque<Coordinate> committedTreeTargets = new ArrayDeque<>();
    private Map<Coordinate, String> committedTreeAccessBreaks = Map.of();
    private Set<Coordinate> committedTreeAccessObjectives = Set.of();
    private String committedTreeBlockId = "";
    private String blockedDetail = "";
    private int completedTargets;

    public ResourceActuationSession(
            String operationId,
            SourceKind sourceKind,
            Collection<String> objectiveBlockIds,
            Collection<String> expectedItemIds,
            String reservedReplantItem) {
        this.operationId = requiredText(operationId, "operation id");
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        this.objectiveBlockIds = normalizedIds(objectiveBlockIds);
        if (this.objectiveBlockIds.isEmpty()) {
            throw new IllegalArgumentException("resource operation requires objective blocks");
        }
        this.expectedItemIds = normalizedIds(expectedItemIds);
        String normalizedReplant = Objects.requireNonNullElse(
                reservedReplantItem, "").trim();
        this.reservedReplantItem = normalizedReplant.isEmpty()
                ? Optional.empty()
                : Optional.of(normalizeId(normalizedReplant));
    }

    /** Selects the nearest admitted loaded candidate and pins it immutably. */
    public synchronized Optional<Target> selectCandidate(Collection<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (phase != Phase.SEARCHING || target != null) return Optional.empty();
        Optional<Candidate> selected = candidates.stream()
                .map(candidate -> Objects.requireNonNull(candidate, "candidate"))
                .filter(Candidate::admitted)
                .filter(this::candidateMatchesOperation)
                .min(CANDIDATE_ORDER);
        if (selected.isEmpty()) return Optional.empty();
        Candidate candidate = selected.orElseThrow();
        target = new Target(
                candidate.coordinate(), candidate.kind(), candidate.blockId(),
                candidate.requiredReplantItem(), false);
        if (candidate.kind() == TargetKind.NATURAL_LOG) {
            committedTreeBlockId = candidate.blockId();
            committedTreeAccessBreaks = candidate.connectedNaturalTreeAccessBreaks();
            committedTreeAccessObjectives =
                    candidate.connectedNaturalTreeAccessObjectives();
            candidate.connectedNaturalTreeLogs().stream()
                    .filter(coordinate -> !coordinate.equals(candidate.coordinate()))
                    .sorted(TREE_LOG_ORDER)
                    .forEach(committedTreeTargets::addLast);
        }
        phase = Phase.ROUTING;
        return Optional.of(target);
    }

    /** Exact final-boundary identity check; mining alone may excavate an admitted route. */
    public synchronized BreakDecision beforeBreak(
            Coordinate coordinate,
            String blockId) {
        Objects.requireNonNull(coordinate, "coordinate");
        String normalizedBlock = normalizeId(blockId);
        if (phase == Phase.BLOCKED || target == null) return BreakDecision.DENY;
        if (target.coordinate().equals(coordinate)
                && target.blockId().equals(normalizedBlock)
                && (phase == Phase.ROUTING || phase == Phase.BREAKING)) {
            return BreakDecision.EXACT_TARGET;
        }
        return sourceKind == SourceKind.MINE
                && (phase == Phase.ROUTING || phase == Phase.BREAKING)
                ? BreakDecision.MINING_ROUTE
                : BreakDecision.DENY;
    }

    public synchronized void beginBreak() {
        requirePhase(Phase.ROUTING, Phase.BREAKING);
        phase = Phase.BREAKING;
    }

    /**
     * Releases an unchanged target that became unsafe while routing or aiming.
     * This is not completion: no work or drop credit is granted, and the caller
     * must exclude the rejected coordinate before selecting again.
     */
    public synchronized Target rejectUnchangedTarget() {
        requirePhase(Phase.ROUTING, Phase.BREAKING);
        if (target == null) throw new IllegalStateException("resource target is absent");
        Target rejected = target;
        advanceAfterTarget(rejected);
        return rejected;
    }

    /** Records the exact solid-to-air transition, retaining custody through pickup. */
    public synchronized void observeTargetRemoved() {
        requirePhase(Phase.ROUTING, Phase.BREAKING);
        if (target == null) throw new IllegalStateException("resource target is absent");
        if (target.kind() == TargetKind.MATURE_CROP) {
            phase = Phase.REPLANTING;
            return;
        }
        phase = Phase.AWAITING_DROP;
    }

    public synchronized void observeReplantIssued(String itemId) {
        requirePhase(Phase.REPLANTING);
        String exact = normalizeId(itemId);
        if (target == null
                || target.requiredReplantItem().isEmpty()
                || !target.requiredReplantItem().orElseThrow().equals(exact)
                || reservedReplantItem.isEmpty()
                || !reservedReplantItem.orElseThrow().equals(exact)) {
            block("crop replant did not use the exact reserved item");
            return;
        }
        phase = Phase.VERIFYING_REPLANT;
    }

    /** Advances crop custody only after the loaded replacement is observed. */
    public synchronized boolean observeReplacement(
            Coordinate coordinate,
            boolean supportedCrop,
            String requiredReplantItem) {
        requirePhase(Phase.VERIFYING_REPLANT);
        String exact = normalizeId(requiredReplantItem);
        if (target == null || !target.coordinate().equals(coordinate)
                || !supportedCrop
                || target.requiredReplantItem().isEmpty()
                || !target.requiredReplantItem().orElseThrow().equals(exact)) {
            return false;
        }
        phase = Phase.AWAITING_DROP;
        return true;
    }

    /** Releases the exact target only after its expected drop has been settled. */
    public synchronized void observeDropSettled() {
        requirePhase(Phase.AWAITING_DROP);
        completeTarget();
    }

    public synchronized void block(String detail) {
        blockedDetail = requiredText(detail, "blocked detail");
        phase = Phase.BLOCKED;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized Optional<Target> target() {
        return Optional.ofNullable(target);
    }

    public synchronized int completedTargets() {
        return completedTargets;
    }

    /** Remaining exact logs from the one initially classified natural tree. */
    public synchronized int remainingCommittedTreeTargets() {
        return committedTreeTargets.size();
    }

    /**
     * The complete immutable tree handed to Baritone for one physical job.
     *
     * <p>Entity owns classification and authority, while Baritone owns the
     * route and block-breaking sequence. Exposing the whole commitment avoids
     * turning one tree into a competing series of Entity-authored stance and
     * break operations.</p>
     */
    public synchronized Set<Coordinate> committedNaturalTreeTargets() {
        if (target == null || target.kind() != TargetKind.NATURAL_LOG) {
            return Set.of();
        }
        LinkedHashSet<Coordinate> committed = new LinkedHashSet<>();
        committed.add(target.coordinate());
        committed.addAll(committedTreeTargets);
        return Set.copyOf(committed);
    }

    /** Exact natural canopy cells admitted only as access for this tree job. */
    public synchronized Map<Coordinate, String> committedNaturalTreeAccessBreaks() {
        if (target == null || target.kind() != TargetKind.NATURAL_LOG) {
            return Map.of();
        }
        return committedTreeAccessBreaks;
    }

    /** Minimal canopy cells made explicit goals; the wider map is authority only. */
    public synchronized Set<Coordinate> committedNaturalTreeAccessObjectives() {
        if (target == null || target.kind() != TargetKind.NATURAL_LOG) {
            return Set.of();
        }
        return committedTreeAccessObjectives;
    }

    /** Retains the whole tree through physical drop collection. */
    public synchronized void observeCommittedNaturalTreeRemoved() {
        requirePhase(Phase.ROUTING, Phase.BREAKING);
        if (target == null || target.kind() != TargetKind.NATURAL_LOG) {
            throw new IllegalStateException(
                    "only a committed natural tree can complete as one Baritone job");
        }
        phase = Phase.AWAITING_DROP;
    }

    /** Releases the whole tree exactly once after all of its drops settle. */
    public synchronized void observeCommittedNaturalTreeDropsSettled() {
        requirePhase(Phase.AWAITING_DROP);
        if (target == null || target.kind() != TargetKind.NATURAL_LOG) {
            throw new IllegalStateException(
                    "only a committed natural tree can settle as one Baritone job");
        }
        completedTargets += 1 + committedTreeTargets.size();
        target = null;
        committedTreeTargets.clear();
        committedTreeAccessBreaks = Map.of();
        committedTreeAccessObjectives = Set.of();
        committedTreeBlockId = "";
        phase = Phase.SEARCHING;
    }

    public synchronized String blockedDetail() {
        return blockedDetail;
    }

    public String operationId() {
        return operationId;
    }

    public SourceKind sourceKind() {
        return sourceKind;
    }

    public Set<String> objectiveBlockIds() {
        return objectiveBlockIds;
    }

    public Set<String> expectedItemIds() {
        return expectedItemIds;
    }

    public Optional<String> reservedReplantItem() {
        return reservedReplantItem;
    }

    private boolean candidateMatchesOperation(Candidate candidate) {
        if (!objectiveBlockIds.contains(candidate.blockId())) return false;
        if (sourceKind == SourceKind.MINE) {
            return candidate.kind() == TargetKind.MINE;
        }
        if (candidate.kind() == TargetKind.NATURAL_LOG || candidate.kind() == TargetKind.FLOWER) return true;
        return candidate.kind() == TargetKind.MATURE_CROP
                && reservedReplantItem.isPresent()
                && candidate.requiredReplantItem().equals(reservedReplantItem);
    }

    private void completeTarget() {
        completedTargets++;
        advanceAfterTarget(target);
    }

    private void advanceAfterTarget(Target completedOrRejected) {
        if (completedOrRejected != null
                && completedOrRejected.kind() == TargetKind.NATURAL_LOG
                && !committedTreeTargets.isEmpty()) {
            target = new Target(
                    committedTreeTargets.removeFirst(),
                    TargetKind.NATURAL_LOG,
                    committedTreeBlockId,
                    Optional.empty(),
                    true);
            phase = Phase.ROUTING;
            return;
        }
        target = null;
        committedTreeTargets.clear();
        committedTreeAccessBreaks = Map.of();
        committedTreeAccessObjectives = Set.of();
        committedTreeBlockId = "";
        phase = Phase.SEARCHING;
    }

    private void requirePhase(Phase... allowed) {
        for (Phase candidate : allowed) {
            if (phase == candidate) return;
        }
        throw new IllegalStateException(
                "resource transition is invalid from " + phase);
    }

    private static Set<String> normalizedIds(Collection<String> values) {
        Objects.requireNonNull(values, "values");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) normalized.add(normalizeId(value));
        return Set.copyOf(normalized);
    }

    public static String normalizeId(String value) {
        String normalized = requiredText(value, "registry id")
                .toLowerCase(Locale.ROOT);
        int namespace = normalized.indexOf(':');
        return namespace >= 0 ? normalized.substring(namespace + 1) : normalized;
    }

    private static Map<Coordinate, String> normalizeCoordinateIds(
            Map<Coordinate, String> values) {
        Objects.requireNonNull(values, "connectedNaturalTreeAccessBreaks");
        LinkedHashMap<Coordinate, String> normalized = new LinkedHashMap<>();
        values.forEach((coordinate, blockId) -> normalized.put(
                Objects.requireNonNull(coordinate, "tree access coordinate"),
                normalizeId(blockId)));
        return Map.copyOf(normalized);
    }

    private static String requiredText(String value, String label) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return normalized;
    }
}
