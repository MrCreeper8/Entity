package dev.entity.client.autonomy.collection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure state machine for collecting one action's expected ground-item drops.
 * Item-entity disappearance is never proof of collection: only a positive
 * inventory delta can confirm progress or completion.
 */
public final class GroundItemCollectionPolicy {
    public static final double DEFAULT_ORIGIN_RADIUS = 12.0;
    public static final long DEFAULT_MAXIMUM_AGE_MILLIS = 60_000L;
    public static final long DEFAULT_BLACKLIST_MILLIS = 5_000L;
    public static final int DEFAULT_MAXIMUM_TARGET_FAILURES = 3;

    private GroundItemCollectionPolicy() {
    }

    public enum ApproachKind { STEERING, NATIVE_ROUTE, REJECTED }

    public record Approach(ApproachKind kind, String detail) {
        public boolean steering() { return kind == ApproachKind.STEERING; }
        public boolean needsNativeRoute() { return kind == ApproachKind.NATIVE_ROUTE; }
    }

    /** A safe item beyond a ledge needs native pathfinding, not an item blacklist. */
    public static Approach assessDirectApproach(Set<Hazard> itemHazards, Set<Hazard> sweepHazards) {
        if (!itemHazards.isEmpty()) return new Approach(ApproachKind.REJECTED, "item cell hazards: " + itemHazards);
        if (!sweepHazards.isEmpty()) return new Approach(ApproachKind.NATIVE_ROUTE, "straight pickup sweep hazards: " + sweepHazards);
        return new Approach(ApproachKind.STEERING, "safe exact item approach");
    }

    /**
     * Geometry for the final few blocks of an item pickup. A Baritone goal is
     * block based, while an item entity can sit at any sub-block coordinate;
     * consequently reaching the item's block is only an approach, never proof
     * of contact. The controller uses this policy to walk onto the exact entity
     * position and, after a bounded failed contact, route through it from a
     * different side.
     */
    public static final class PickupSweep {
        public static final double MICRO_APPROACH_HORIZONTAL_RADIUS = 2.25;
        public static final double MICRO_APPROACH_VERTICAL_RADIUS = 2.0;
        public static final double CONTACT_HORIZONTAL_RADIUS = 0.32;
        public static final double CONTACT_VERTICAL_RADIUS = 0.75;
        public static final long CONTACT_CONFIRM_MILLIS = 750L;
        public static final long MICRO_APPROACH_TIMEOUT_MILLIS = 2_000L;
        /** Lets gravity finish after the controller releases an intentional pickup hop. */
        public static final long VERTICAL_SETTLE_MILLIS = 1_000L;
        public static final int MAXIMUM_PASS_THROUGH_ATTEMPTS = 8;

        private PickupSweep() {
        }

        public static boolean shouldMicroApproach(Point collector, Point item) {
            Objects.requireNonNull(collector, "collector");
            Objects.requireNonNull(item, "item");
            double dx = collector.x() - item.x();
            double dz = collector.z() - item.z();
            return dx * dx + dz * dz
                    <= MICRO_APPROACH_HORIZONTAL_RADIUS * MICRO_APPROACH_HORIZONTAL_RADIUS
                    && Math.abs(collector.y() - item.y()) <= MICRO_APPROACH_VERTICAL_RADIUS;
        }

        public static boolean hasPhysicalContact(Point collector, Point item) {
            Objects.requireNonNull(collector, "collector");
            Objects.requireNonNull(item, "item");
            double dx = collector.x() - item.x();
            double dz = collector.z() - item.z();
            return dx * dx + dz * dz <= CONTACT_HORIZONTAL_RADIUS * CONTACT_HORIZONTAL_RADIUS
                    && Math.abs(collector.y() - item.y()) <= CONTACT_VERTICAL_RADIUS;
        }

        /**
         * Extends, but never shortens, the movement owner's bounded vertical lease.
         * The lease is renewed only after a live-safe direct pickup sweep actually
         * owns the movement keys.
         */
        public static long extendVerticalRecoveryUntil(long currentUntilMillis, long nowMillis) {
            long proposed = nowMillis > Long.MAX_VALUE - VERTICAL_SETTLE_MILLIS
                    ? Long.MAX_VALUE
                    : nowMillis + VERTICAL_SETTLE_MILLIS;
            return Math.max(currentUntilMillis, proposed);
        }

        public static boolean verticalRecoveryActive(long untilMillis, long nowMillis) {
            return untilMillis > 0L && nowMillis <= untilMillis;
        }

        /**
         * Returns the centre of a neighbouring block on a deterministic sweep.
         * Attempt one lies beyond the item from the initial approach origin, so
         * travelling there crosses the item instead of merely reaching its block.
         */
        public static Point passThroughWaypoint(
                Candidate target,
                Point approachOrigin,
                int attempt) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(approachOrigin, "approachOrigin");
            if (attempt < 1 || attempt > MAXIMUM_PASS_THROUGH_ATTEMPTS) {
                throw new IllegalArgumentException("pickup sweep attempt is outside the bounded range");
            }

            Point item = target.position();
            double dx = item.x() - approachOrigin.x();
            double dz = item.z() - approachOrigin.z();
            int forwardX;
            int forwardZ;
            if (Math.abs(dx) >= Math.abs(dz) && Math.abs(dx) > 0.001) {
                forwardX = dx >= 0.0 ? 1 : -1;
                forwardZ = 0;
            } else if (Math.abs(dz) > 0.001) {
                forwardX = 0;
                forwardZ = dz >= 0.0 ? 1 : -1;
            } else if ((target.id().hashCode() & 1) == 0) {
                forwardX = 1;
                forwardZ = 0;
            } else {
                forwardX = 0;
                forwardZ = 1;
            }
            int rightX = -forwardZ;
            int rightZ = forwardX;
            int[][] relative = {
                    {forwardX, forwardZ},
                    {-forwardX, -forwardZ},
                    {rightX, rightZ},
                    {-rightX, -rightZ},
                    {forwardX + rightX, forwardZ + rightZ},
                    {-forwardX - rightX, -forwardZ - rightZ},
                    {forwardX - rightX, forwardZ - rightZ},
                    {-forwardX + rightX, -forwardZ + rightZ}
            };
            int[] offset = relative[attempt - 1];
            return new Point(
                    Math.floor(item.x()) + 0.5 + offset[0],
                    Math.floor(item.y()),
                    Math.floor(item.z()) + 0.5 + offset[1]);
        }
    }

    public record Scope(String missionId, String actionId) {
        public Scope {
            missionId = requireText(missionId, "missionId");
            actionId = requireText(actionId, "actionId");
        }

        public String key() {
            return missionId + ':' + actionId;
        }
    }

    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("point coordinates must be finite");
            }
        }

        public double squaredDistanceTo(Point other) {
            Objects.requireNonNull(other, "other");
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    public record Request(
            Scope scope,
            Set<String> expectedCanonicalItemIds,
            Point origin,
            double originRadius,
            long maximumCandidateAgeMillis,
            int requiredInventoryDelta,
            long blacklistMillis,
            int maximumTargetFailures,
            boolean routeWorldMutationAllowed) {
        /**
         * Existing collection callers retain ordinary survival routing. Callers
         * collecting from a managed structure must opt into the explicit final
         * component and keep the approach non-destructive.
         */
        public Request(
                Scope scope,
                Set<String> expectedCanonicalItemIds,
                Point origin,
                double originRadius,
                long maximumCandidateAgeMillis,
                int requiredInventoryDelta,
                long blacklistMillis,
                int maximumTargetFailures) {
            this(scope, expectedCanonicalItemIds, origin, originRadius,
                    maximumCandidateAgeMillis, requiredInventoryDelta,
                    blacklistMillis, maximumTargetFailures, true);
        }

        public Request {
            scope = Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(expectedCanonicalItemIds, "expectedCanonicalItemIds");
            if (expectedCanonicalItemIds.isEmpty()) {
                throw new IllegalArgumentException("at least one expected canonical item ID is required");
            }
            ArrayList<String> ids = new ArrayList<>(expectedCanonicalItemIds.size());
            for (String raw : expectedCanonicalItemIds) ids.add(canonicalId(raw));
            ids.sort(String::compareTo);
            expectedCanonicalItemIds = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
            origin = Objects.requireNonNull(origin, "origin");
            if (!Double.isFinite(originRadius) || originRadius <= 0.0) {
                throw new IllegalArgumentException("originRadius must be finite and positive");
            }
            if (maximumCandidateAgeMillis < 0L) {
                throw new IllegalArgumentException("maximumCandidateAgeMillis cannot be negative");
            }
            if (requiredInventoryDelta <= 0) {
                throw new IllegalArgumentException("requiredInventoryDelta must be positive");
            }
            if (blacklistMillis <= 0L) {
                throw new IllegalArgumentException("blacklistMillis must be positive");
            }
            if (maximumTargetFailures <= 0) {
                throw new IllegalArgumentException("maximumTargetFailures must be positive");
            }
        }

        /**
         * A non-destructive route must stop beside an item resting in a crop or
         * other intentionally retained passable block. The existing micro-step
         * owns the final collision pickup from there.
         */
        public int itemRouteRange() {
            return routeWorldMutationAllowed ? 0 : 1;
        }

        public static Request defaults(
                Scope scope,
                Set<String> expectedCanonicalItemIds,
                Point origin,
                int requiredInventoryDelta) {
            return new Request(
                    scope,
                    expectedCanonicalItemIds,
                    origin,
                    DEFAULT_ORIGIN_RADIUS,
                    DEFAULT_MAXIMUM_AGE_MILLIS,
                    requiredInventoryDelta,
                    DEFAULT_BLACKLIST_MILLIS,
                    DEFAULT_MAXIMUM_TARGET_FAILURES,
                    true);
        }
    }

    public record Candidate(
            String id,
            String canonicalItemId,
            int itemCount,
            Point position,
            long ageMillis,
            Set<Hazard> hazards) {
        public Candidate {
            id = requireText(id, "candidate id");
            canonicalItemId = canonicalId(canonicalItemId);
            if (itemCount <= 0) throw new IllegalArgumentException("candidate itemCount must be positive");
            position = Objects.requireNonNull(position, "position");
            if (ageMillis < 0L) throw new IllegalArgumentException("candidate ageMillis cannot be negative");
            hazards = Collections.unmodifiableSet(new LinkedHashSet<>(
                    Objects.requireNonNull(hazards, "hazards")));
        }

        public Candidate(
                String id,
                String canonicalItemId,
                int itemCount,
                Point position,
                long ageMillis) {
            this(id, canonicalItemId, itemCount, position, ageMillis, Set.of());
        }
    }

    public enum Hazard {
        LAVA,
        NEAR_LAVA,
        ON_FIRE,
        NEAR_FIRE,
        UNSUPPORTED_FLOOR,
        UNLOADED_ENVIRONMENT,
        VOID
    }

    public record Observation(
            long nowMillis,
            Point collectorPosition,
            Map<String, Integer> canonicalInventoryCounts,
            List<Candidate> loadedCandidates,
            boolean inventoryFull) {
        public Observation {
            collectorPosition = Objects.requireNonNull(collectorPosition, "collectorPosition");
            Objects.requireNonNull(canonicalInventoryCounts, "canonicalInventoryCounts");
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
            canonicalInventoryCounts.forEach((raw, count) -> {
                String item = canonicalId(raw);
                if (count == null || count < 0) {
                    throw new IllegalArgumentException("inventory counts cannot be negative");
                }
                if (count > 0) counts.merge(item, count, Math::addExact);
            });
            canonicalInventoryCounts = Collections.unmodifiableMap(counts);
            loadedCandidates = List.copyOf(Objects.requireNonNull(loadedCandidates, "loadedCandidates"));
        }
    }

    public enum State {
        TARGET_SELECTED,
        PROGRESS,
        COLLECTED,
        NO_CANDIDATE,
        RECOVERING,
        LOST,
        INVENTORY_FULL,
        BLOCKED
    }

    public record Decision(
            State state,
            Optional<Candidate> target,
            int confirmedInventoryDelta,
            long retryAtMillis,
            String detail) {
        public Decision {
            state = Objects.requireNonNull(state, "state");
            target = Objects.requireNonNull(target, "target");
            if (confirmedInventoryDelta < 0) {
                throw new IllegalArgumentException("confirmedInventoryDelta cannot be negative");
            }
            if (retryAtMillis < 0L) throw new IllegalArgumentException("retryAtMillis cannot be negative");
            detail = requireText(detail, "detail");
            if (state == State.TARGET_SELECTED && target.isEmpty()) {
                throw new IllegalArgumentException("TARGET_SELECTED requires a target");
            }
        }
    }

    /** Mutable state is intentionally scoped to exactly one mission action. */
    public static final class Session {
        private Request request;
        private int lastExpectedInventoryCount;
        private int confirmedInventoryDelta;
        private Candidate currentTarget;
        private final LinkedHashMap<String, Long> blacklistedUntil = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> targetFailures = new LinkedHashMap<>();
        private final LinkedHashSet<String> exhaustedTargetIds = new LinkedHashSet<>();
        private boolean completed;

        public Decision observe(Request nextRequest, Observation observation) {
            Objects.requireNonNull(nextRequest, "nextRequest");
            Objects.requireNonNull(observation, "observation");
            int inventoryNow = expectedInventoryCount(nextRequest, observation.canonicalInventoryCounts());
            if (!nextRequest.equals(request)) {
                reset(nextRequest, inventoryNow);
            }

            int positiveDelta = Math.max(0, inventoryNow - lastExpectedInventoryCount);
            lastExpectedInventoryCount = inventoryNow;
            if (positiveDelta > 0) {
                confirmedInventoryDelta = Math.addExact(confirmedInventoryDelta, positiveDelta);
                Candidate collectedTarget = currentTarget;
                currentTarget = null;
                if (confirmedInventoryDelta >= request.requiredInventoryDelta()) {
                    completed = true;
                    return decision(State.COLLECTED, collectedTarget, 0L,
                            "confirmed " + confirmedInventoryDelta
                                    + "/" + request.requiredInventoryDelta() + " by inventory increase");
                }
                return decision(State.PROGRESS, collectedTarget, 0L,
                        "confirmed " + confirmedInventoryDelta
                                + "/" + request.requiredInventoryDelta() + " by inventory increase");
            }
            if (completed) {
                return decision(State.COLLECTED, null, 0L,
                        "collection already confirmed by inventory increase");
            }

            pruneBlacklist(observation.nowMillis());
            List<Candidate> matching = matchingCandidates(request, observation.loadedCandidates());
            List<Candidate> viable = matching.stream()
                    .filter(candidate -> !exhaustedTargetIds.contains(candidate.id()))
                    .toList();
            if (currentTarget != null) {
                Candidate refreshed = viable.stream()
                        .filter(candidate -> candidate.id().equals(currentTarget.id()))
                        .findFirst()
                        .orElse(null);
                if (refreshed == null) {
                    return rejectCurrent(observation.nowMillis(),
                            "target disappeared without a matching inventory increase");
                }
                currentTarget = refreshed;
            }

            if (observation.inventoryFull() && (currentTarget != null || !viable.isEmpty())) {
                return decision(State.INVENTORY_FULL, currentTarget, 0L,
                        "inventory has no slot that can accept an expected ground item");
            }
            if (currentTarget != null) {
                return decision(State.TARGET_SELECTED, currentTarget, 0L,
                        "continuing the selected ground-item target");
            }

            List<Candidate> eligible = viable.stream()
                    .filter(candidate -> !blacklistedUntil.containsKey(candidate.id()))
                    .sorted(candidateOrder(observation.collectorPosition()))
                    .toList();
            if (!eligible.isEmpty()) {
                currentTarget = eligible.getFirst();
                return decision(State.TARGET_SELECTED, currentTarget, 0L,
                        "selected the nearest deterministic expected ground-item target");
            }
            if (!viable.isEmpty()) {
                long retryAt = viable.stream()
                        .map(candidate -> blacklistedUntil.get(candidate.id()))
                        .filter(Objects::nonNull)
                        .min(Long::compareTo)
                        .orElse(0L);
                return decision(State.RECOVERING, null, retryAt,
                        "all matching ground-item targets are temporarily blacklisted");
            }
            return decision(State.NO_CANDIDATE, null, 0L,
                    matching.isEmpty()
                            ? "no fresh expected ground item exists inside the action origin radius"
                            : "all matching ground-item targets exhausted bounded recovery and remain ignored "
                            + "until the action scope is cleared");
        }

        /** Rejects the live target after a bounded routing/interact failure. */
        public Decision rejectCurrent(long nowMillis, String reason) {
            if (request == null) throw new IllegalStateException("collection session has not been observed");
            if (currentTarget == null) {
                return decision(State.NO_CANDIDATE, null, 0L,
                        "no selected ground-item target to reject");
            }
            Candidate rejected = currentTarget;
            currentTarget = null;
            int failures = targetFailures.merge(rejected.id(), 1, Math::addExact);
            String failure = requireText(reason, "reason") + " [target " + rejected.id() + ", attempt "
                    + failures + '/' + request.maximumTargetFailures() + ']';
            if (failures >= request.maximumTargetFailures()) {
                blacklistedUntil.remove(rejected.id());
                exhaustedTargetIds.add(rejected.id());
                return decision(State.BLOCKED, rejected, 0L,
                        failure + "; target permanently ignored for this action scope after bounded recovery");
            }
            long retryAt = Math.addExact(nowMillis, request.blacklistMillis());
            blacklistedUntil.put(rejected.id(), retryAt);
            return decision(State.LOST, rejected, retryAt,
                    failure + "; target blacklisted before retry");
        }

        public void clear() {
            request = null;
            lastExpectedInventoryCount = 0;
            confirmedInventoryDelta = 0;
            currentTarget = null;
            blacklistedUntil.clear();
            targetFailures.clear();
            exhaustedTargetIds.clear();
            completed = false;
        }

        public Optional<Scope> scope() {
            return request == null ? Optional.empty() : Optional.of(request.scope());
        }

        public int confirmedInventoryDelta() {
            return confirmedInventoryDelta;
        }

        public int blacklistedTargetCount() {
            return blacklistedUntil.size();
        }

        public int exhaustedTargetCount() {
            return exhaustedTargetIds.size();
        }

        public int targetFailureCount(String candidateId) {
            return targetFailures.getOrDefault(requireText(candidateId, "candidate id"), 0);
        }

        /**
         * Read-only eligibility for a caller's loaded-drop fence. A temporarily
         * blacklisted or permanently exhausted UUID must not immediately
         * preempt the action which just released collection ownership.
         */
        public boolean allowsCandidateProbe(String candidateId, long nowMillis) {
            String id = requireText(candidateId, "candidate id");
            if (nowMillis < 0L) throw new IllegalArgumentException("nowMillis cannot be negative");
            if (exhaustedTargetIds.contains(id)) return false;
            Long retryAt = blacklistedUntil.get(id);
            return retryAt == null || nowMillis >= retryAt;
        }

        private void reset(Request nextRequest, int inventoryNow) {
            boolean scopeChanged = request == null || !request.scope().equals(nextRequest.scope());
            request = nextRequest;
            lastExpectedInventoryCount = inventoryNow;
            confirmedInventoryDelta = 0;
            currentTarget = null;
            if (scopeChanged) {
                blacklistedUntil.clear();
                targetFailures.clear();
                exhaustedTargetIds.clear();
            }
            completed = false;
        }

        private Decision decision(
                State state,
                Candidate target,
                long retryAtMillis,
                String detail) {
            return new Decision(
                    state,
                    Optional.ofNullable(target),
                    confirmedInventoryDelta,
                    retryAtMillis,
                    detail);
        }

        private void pruneBlacklist(long nowMillis) {
            blacklistedUntil.entrySet().removeIf(entry -> nowMillis >= entry.getValue());
        }
    }

    public static List<Candidate> matchingCandidates(Request request, List<Candidate> candidates) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidates, "candidates");
        double radiusSquared = request.originRadius() * request.originRadius();
        return candidates.stream()
                .filter(candidate -> request.expectedCanonicalItemIds().contains(candidate.canonicalItemId()))
                .filter(candidate -> candidate.ageMillis() <= request.maximumCandidateAgeMillis())
                .filter(candidate -> candidate.position().squaredDistanceTo(request.origin()) <= radiusSquared)
                .filter(candidate -> candidate.hazards().isEmpty())
                .toList();
    }

    public static Comparator<Candidate> candidateOrder(Point collectorPosition) {
        Objects.requireNonNull(collectorPosition, "collectorPosition");
        return Comparator
                .comparingDouble((Candidate candidate) ->
                        candidate.position().squaredDistanceTo(collectorPosition))
                .thenComparing(Comparator.comparingInt(Candidate::itemCount).reversed())
                .thenComparingLong(Candidate::ageMillis)
                .thenComparing(Candidate::canonicalItemId)
                .thenComparing(Candidate::id);
    }

    private static int expectedInventoryCount(Request request, Map<String, Integer> inventory) {
        int count = 0;
        for (String item : request.expectedCanonicalItemIds()) {
            count = Math.addExact(count, inventory.getOrDefault(item, 0));
        }
        return count;
    }

    private static String canonicalId(String raw) {
        String normalized = requireText(raw, "canonical item ID")
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        while (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        if (normalized.isBlank() || normalized.indexOf(':') >= 0) {
            throw new IllegalArgumentException("canonical item IDs must be namespace-free: " + raw);
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }
}
