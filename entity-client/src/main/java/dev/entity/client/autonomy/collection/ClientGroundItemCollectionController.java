package dev.entity.client.autonomy.collection;

import dev.entity.core.control.ControlLease;
import dev.entity.core.port.BaritonePort;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Main-thread Minecraft controller for the pure collection state machine.
 * It owns only short Baritone routes to loaded item entities; callers retain
 * mission/action orchestration and decide how LOST/BLOCKED states are retried.
 */
public final class ClientGroundItemCollectionController {
    private static final long NO_CONTROL_EPOCH = Long.MIN_VALUE;

    private final MinecraftGroundItemAdapter adapter;
    private final BaritonePort baritone;
    private final GroundItemCollectionPolicy.Session session =
            new GroundItemCollectionPolicy.Session();

    private GroundItemCollectionPolicy.Request activeRequest;
    private String routeOperationId = "";
    private String routedTargetId = "";
    private long routeControlEpoch = NO_CONTROL_EPOCH;
    private String pickupTargetId = "";
    private GroundItemCollectionPolicy.Point pickupApproachOrigin;
    private long contactAtMillis;
    private long microApproachAtMillis;
    private int passThroughAttempt;
    private boolean passThroughRouteRequired;
    private long activeLeaseEpoch = NO_CONTROL_EPOCH;
    private long boundedVerticalRecoveryUntilMillis;

    public ClientGroundItemCollectionController(
            MinecraftGroundItemAdapter adapter,
            BaritonePort baritone) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.baritone = Objects.requireNonNull(baritone, "baritone");
    }

    public Result tick(
            GroundItemCollectionPolicy.Request request,
            ControlLease lease,
            long nowMillis) {
        return tick(request, lease, nowMillis, true);
    }

    public Result tick(
            GroundItemCollectionPolicy.Request request,
            ControlLease lease,
            long nowMillis,
            boolean returnToOriginWhenNoCandidate) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(lease, "lease");
        boolean requestChanged = !request.equals(activeRequest);
        boolean leaseChanged = activeLeaseEpoch != NO_CONTROL_EPOCH
                && activeLeaseEpoch != lease.epoch();
        if (requestChanged || leaseChanged) {
            cancelRoute(requestChanged
                    ? "ground-item action scope changed"
                    : "ground-item control lease epoch changed");
            resetPickup();
        }
        if (requestChanged) {
            activeRequest = request;
        }
        activeLeaseEpoch = lease.epoch();

        Optional<MinecraftGroundItemAdapter.Snapshot> observed = adapter.observe(request, nowMillis);
        if (observed.isEmpty()) {
            return result(
                    State.WAITING_FOR_WORLD,
                    "waiting for a loaded Minecraft player/world",
                    Optional.empty(),
                    session.confirmedInventoryDelta(),
                    0L,
                    Double.NaN,
                    false);
        }

        MinecraftGroundItemAdapter.Snapshot snapshot = observed.orElseThrow();
        GroundItemCollectionPolicy.Decision decision =
                session.observe(request, snapshot.observation());
        if (decision.state() != GroundItemCollectionPolicy.State.TARGET_SELECTED) {
            if (decision.state() == GroundItemCollectionPolicy.State.NO_CANDIDATE
                    && returnToOriginWhenNoCandidate
                    && snapshot.observation().collectorPosition()
                    .squaredDistanceTo(request.origin()) > 4.0 * 4.0) {
                resetPickup();
                return routeToOrigin(request, lease, decision, nowMillis);
            }
            cancelRoute("ground-item collection state is " + decision.state());
            resetPickup();
            return fromPolicy(decision);
        }

        GroundItemCollectionPolicy.Candidate target = decision.target().orElseThrow();
        var entity = snapshot.entitiesByCandidateId().get(target.id());
        if (entity == null) {
            GroundItemCollectionPolicy.Decision lost = session.rejectCurrent(
                    nowMillis, "selected item entity left the loaded client snapshot");
            cancelRoute("ground-item target left the loaded snapshot");
            resetPickup();
            return fromPolicy(lost);
        }

        GroundItemCollectionPolicy.Point collector = snapshot.observation().collectorPosition();
        if (!target.id().equals(pickupTargetId)) {
            resetPickup();
            pickupTargetId = target.id();
            pickupApproachOrigin = collector;
        }

        if (GroundItemCollectionPolicy.PickupSweep.hasPhysicalContact(
                collector, target.position())) {
            cancelRoute("physically overlapping the ground item");
            adapter.stopPickupMovement();
            microApproachAtMillis = 0L;
            if (contactAtMillis == 0L) contactAtMillis = nowMillis;
            if (nowMillis - contactAtMillis
                    < GroundItemCollectionPolicy.PickupSweep.CONTACT_CONFIRM_MILLIS) {
                return result(
                        State.WAITING_FOR_PICKUP,
                        "physically overlapping the item; waiting for a server-confirmed inventory increase",
                        Optional.of(target),
                        decision.confirmedInventoryDelta(),
                        0L,
                        0.0,
                        false);
            }
            contactAtMillis = 0L;
            if (!advancePassThrough()) {
                GroundItemCollectionPolicy.Decision lost = session.rejectCurrent(
                        nowMillis,
                        "item remained visible after every bounded collision pass without an inventory increase");
                resetPickup();
                return fromPolicy(lost);
            }
        } else {
            contactAtMillis = 0L;
        }

        if (routeOperationId.isBlank()
                && !passThroughRouteRequired
                && GroundItemCollectionPolicy.PickupSweep.shouldMicroApproach(
                collector, target.position())) {
            if (microApproachAtMillis == 0L) microApproachAtMillis = nowMillis;
            if (nowMillis - microApproachAtMillis
                    < GroundItemCollectionPolicy.PickupSweep.MICRO_APPROACH_TIMEOUT_MILLIS) {
                var approach = adapter.approach(entity, nowMillis);
                if (!approach.steering()) return routeOrRejectApproach(approach, decision, nowMillis, false);
                renewBoundedVerticalRecovery(nowMillis);
                return result(
                        State.APPROACHING,
                        "walking onto the exact item-entity position",
                        Optional.of(target),
                        decision.confirmedInventoryDelta(),
                        0L,
                        Math.sqrt(target.position().squaredDistanceTo(collector)),
                        true);
            }
            adapter.stopPickupMovement();
            microApproachAtMillis = 0L;
            if (!advancePassThrough()) {
                GroundItemCollectionPolicy.Decision lost = session.rejectCurrent(
                        nowMillis,
                        "exact pickup approach remained blocked after every bounded collision pass");
                resetPickup();
                return fromPolicy(lost);
            }
        }

        adapter.stopPickupMovement();
        GroundItemCollectionPolicy.Point routePoint = passThroughAttempt == 0
                ? target.position()
                : GroundItemCollectionPolicy.PickupSweep.passThroughWaypoint(
                target, pickupApproachOrigin, passThroughAttempt);
        String operationId = request.scope().key() + ":ground-item:" + target.id()
                + ":pass:" + passThroughAttempt;
        if (!routedTargetId.isBlank() && !routedTargetId.equals(target.id())) {
            cancelRoute("switching ground-item target");
        }
        BaritonePort.Goal goal = new BaritonePort.Goal(
                operationId,
                "goto",
                pickupRouteArguments(request,
                        "x", Integer.toString((int) Math.floor(routePoint.x())),
                        "y", Integer.toString((int) Math.floor(routePoint.y())),
                        "z", Integer.toString((int) Math.floor(routePoint.z())),
                        "range", Integer.toString(request.itemRouteRange())));
        baritone.start(goal, lease);
        passThroughRouteRequired = false;
        routeOperationId = operationId;
        routeControlEpoch = lease.epoch();
        routedTargetId = target.id();
        BaritonePort.Status status = baritone.poll(operationId);
        if (status.controlEpoch() != lease.epoch()) {
            return result(
                    State.APPROACHING,
                    "ignoring stale ground-item route status",
                    Optional.of(target),
                    decision.confirmedInventoryDelta(),
                    0L,
                    status.distanceRemaining(),
                    false);
        }
        return switch (status.state()) {
            case COMPLETE -> {
                cancelRoute("ground-item route reached the final collision approach");
                microApproachAtMillis = nowMillis;
                var approach = adapter.approach(entity, nowMillis);
                if (!approach.steering()) yield routeOrRejectApproach(approach, decision, nowMillis, true);
                renewBoundedVerticalRecovery(nowMillis);
                yield result(
                        State.APPROACHING,
                        "route complete; walking from the reached block onto the exact item position",
                        Optional.of(target),
                        decision.confirmedInventoryDelta(),
                        0L,
                        Math.sqrt(target.position().squaredDistanceTo(collector)),
                        true);
            }
            case TRANSIENT_FAILURE, BLOCKED -> {
                cancelRoute(status.detail());
                if (advancePassThrough()) {
                    yield result(
                            State.APPROACHING,
                            "item remains visible; selecting collision pass " + passThroughAttempt
                                    + '/' + GroundItemCollectionPolicy.PickupSweep.MAXIMUM_PASS_THROUGH_ATTEMPTS,
                            Optional.of(target),
                            decision.confirmedInventoryDelta(),
                            0L,
                            status.distanceRemaining(),
                            false);
                }
                GroundItemCollectionPolicy.Decision rejected = session.rejectCurrent(
                        nowMillis,
                        "Baritone and every bounded collision pass could not reach the visible item: "
                                + status.detail());
                resetPickup();
                yield fromPolicy(rejected);
            }
            case IDLE, CALCULATING, EXECUTING -> result(
                    State.APPROACHING,
                    "approaching expected ground item; " + status.detail(),
                    Optional.of(target),
                    decision.confirmedInventoryDelta(),
                    0L,
                    status.distanceRemaining(),
                    status.progressExpected());
        };
    }

    /**
     * Clears the logical action scope. The epoch parameter remains for source compatibility;
     * any live route is fenced with the operation ID and epoch captured when that route started.
     */
    public void clear(long invalidatedEpoch, String reason) {
        cancelRoute(Objects.requireNonNullElse(reason, "ground-item action cleared"));
        session.clear();
        activeRequest = null;
        activeLeaseEpoch = NO_CONTROL_EPOCH;
        resetPickup();
    }

    /**
     * Relinquishes movement without destroying the collection transaction.
     * Safety/protection preemption may interrupt the body for several ticks;
     * the inventory baseline, selected entity UUID, blacklist and bounded
     * failure history must survive so a resume cannot double-count or forget
     * the drop it was already collecting.
     */
    public void suspend(String reason) {
        cancelRoute(Objects.requireNonNullElse(reason, "ground-item action suspended"));
        resetPickup();
        activeLeaseEpoch = NO_CONTROL_EPOCH;
    }

    public Optional<GroundItemCollectionPolicy.Scope> activeScope() {
        return session.scope();
    }

    /**
     * Read-only ownership signal for the generic movement watchdog. It remains
     * active briefly after direct keys are released so the resulting jump/fall
     * cannot be misclassified as an uncontrolled route oscillation.
     */
    public boolean boundedVerticalRecoveryActive(long nowMillis) {
        return GroundItemCollectionPolicy.PickupSweep.verticalRecoveryActive(
                boundedVerticalRecoveryUntilMillis, nowMillis);
    }

    /**
     * Read-only probe used before a combat action is (re)started. It deliberately
     * does not mutate the collection session or cancel the currently active body
     * owner; callers may then durably enter a collection phase before routing.
     */
    public Optional<GroundItemCollectionPolicy.Candidate> nearestLoadedCandidate(
            GroundItemCollectionPolicy.Request request,
            long nowMillis) {
        Objects.requireNonNull(request, "request");
        boolean sameActiveScope = session.scope()
                .map(request.scope()::equals)
                .orElse(false);
        return adapter.observe(request, nowMillis)
                .flatMap(snapshot -> GroundItemCollectionPolicy.matchingCandidates(
                                request, snapshot.observation().loadedCandidates()).stream()
                        .filter(candidate -> !sameActiveScope
                                || session.allowsCandidateProbe(candidate.id(), nowMillis))
                        .sorted(GroundItemCollectionPolicy.candidateOrder(
                                snapshot.observation().collectorPosition()))
                        .findFirst());
    }

    private Result routeToOrigin(
            GroundItemCollectionPolicy.Request request,
            ControlLease lease,
            GroundItemCollectionPolicy.Decision decision,
            long nowMillis) {
        String operationId = request.scope().key() + ":ground-item-origin";
        BaritonePort.Goal goal = new BaritonePort.Goal(
                operationId,
                "goto",
                pickupRouteArguments(request,
                        "x", Integer.toString((int) Math.floor(request.origin().x())),
                        "y", Integer.toString((int) Math.floor(request.origin().y())),
                        "z", Integer.toString((int) Math.floor(request.origin().z())),
                        "range", "2"));
        baritone.start(goal, lease);
        routeOperationId = operationId;
        routeControlEpoch = lease.epoch();
        routedTargetId = "@origin";
        BaritonePort.Status status = baritone.poll(operationId);
        if (status.controlEpoch() != lease.epoch()) {
            return result(
                    State.APPROACHING,
                    "ignoring stale ground-item origin route status",
                    Optional.empty(),
                    decision.confirmedInventoryDelta(),
                    0L,
                    status.distanceRemaining(),
                    false);
        }
        return switch (status.state()) {
            case COMPLETE -> {
                cancelRoute("ground-item action origin loaded");
                yield result(
                        State.SEARCHING,
                        "returned to the action origin; rescanning for the expected item",
                        Optional.empty(),
                        decision.confirmedInventoryDelta(),
                        0L,
                        0.0,
                        false);
            }
            case TRANSIENT_FAILURE, BLOCKED -> {
                cancelRoute("could not return to ground-item action origin");
                yield result(
                        status.state() == BaritonePort.State.BLOCKED ? State.BLOCKED : State.LOST,
                        "could not return to the item action origin: " + status.detail(),
                        Optional.empty(),
                        decision.confirmedInventoryDelta(),
                        nowMillis,
                        status.distanceRemaining(),
                        false);
            }
            case IDLE, CALCULATING, EXECUTING -> result(
                    State.APPROACHING,
                    "returning to the item action origin so its chunk and drops are loaded; "
                            + status.detail(),
                    Optional.empty(),
                    decision.confirmedInventoryDelta(),
                    0L,
                    status.distanceRemaining(),
                    status.progressExpected());
        };
    }

    /** One policy bit fences every Baritone world-mutation route capability. */
    private static Map<String, String> pickupRouteArguments(
            GroundItemCollectionPolicy.Request request,
            String xKey, String x,
            String yKey, String y,
            String zKey, String z,
            String rangeKey, String range) {
        String mutate = Boolean.toString(request.routeWorldMutationAllowed());
        return Map.of(
                xKey, x,
                yKey, y,
                zKey, z,
                rangeKey, range,
                "allowBreak", mutate,
                "allowPlace", mutate,
                "allowParkourPlace", mutate,
                "allowWaterBucketFall", mutate);
    }

    private Result routeOrRejectApproach(GroundItemCollectionPolicy.Approach approach,
            GroundItemCollectionPolicy.Decision decision, long nowMillis, boolean routeAlreadyReached) {
        adapter.stopPickupMovement();
        if (approach.needsNativeRoute() && (!routeAlreadyReached || advancePassThrough())) {
            passThroughRouteRequired = true;
            microApproachAtMillis = 0L;
            return result(State.APPROACHING, "item remains safe; native route required: " + approach.detail(),
                    decision.target(), decision.confirmedInventoryDelta(), 0L, Double.NaN, false);
        }
        var rejected = session.rejectCurrent(nowMillis, approach.detail()
                + (approach.needsNativeRoute() ? "; existing native collision passes exhausted" : ""));
        resetPickup();
        return fromPolicy(rejected);
    }

    private Result fromPolicy(GroundItemCollectionPolicy.Decision decision) {
        State mapped = switch (decision.state()) {
            case TARGET_SELECTED -> throw new IllegalArgumentException("target decisions require routing");
            case PROGRESS -> State.PROGRESS;
            case COLLECTED -> State.COLLECTED;
            case NO_CANDIDATE -> State.SEARCHING;
            case RECOVERING -> State.RECOVERING;
            case LOST -> State.LOST;
            case INVENTORY_FULL -> State.INVENTORY_FULL;
            case BLOCKED -> State.BLOCKED;
        };
        return result(
                mapped,
                decision.detail(),
                decision.target(),
                decision.confirmedInventoryDelta(),
                decision.retryAtMillis(),
                Double.NaN,
                false);
    }

    private void cancelRoute(String reason) {
        if (routeOperationId.isBlank()) return;
        baritone.cancelIfOwned(routeOperationId, routeControlEpoch, reason);
        routeOperationId = "";
        routedTargetId = "";
        routeControlEpoch = NO_CONTROL_EPOCH;
    }

    private boolean advancePassThrough() {
        if (passThroughAttempt
                >= GroundItemCollectionPolicy.PickupSweep.MAXIMUM_PASS_THROUGH_ATTEMPTS) {
            return false;
        }
        passThroughAttempt++;
        passThroughRouteRequired = true;
        microApproachAtMillis = 0L;
        contactAtMillis = 0L;
        return true;
    }

    private void renewBoundedVerticalRecovery(long nowMillis) {
        boundedVerticalRecoveryUntilMillis =
                GroundItemCollectionPolicy.PickupSweep.extendVerticalRecoveryUntil(
                        boundedVerticalRecoveryUntilMillis, nowMillis);
    }

    private void resetPickup() {
        adapter.stopPickupMovement();
        pickupTargetId = "";
        pickupApproachOrigin = null;
        contactAtMillis = 0L;
        microApproachAtMillis = 0L;
        passThroughAttempt = 0;
        passThroughRouteRequired = false;
    }

    private static Result result(
            State state,
            String detail,
            Optional<GroundItemCollectionPolicy.Candidate> target,
            int confirmedInventoryDelta,
            long retryAtMillis,
            double distanceRemaining,
            boolean progressExpected) {
        return new Result(
                state,
                detail,
                target,
                confirmedInventoryDelta,
                retryAtMillis,
                distanceRemaining,
                progressExpected);
    }

    public enum State {
        WAITING_FOR_WORLD,
        SEARCHING,
        APPROACHING,
        WAITING_FOR_PICKUP,
        PROGRESS,
        COLLECTED,
        LOST,
        INVENTORY_FULL,
        RECOVERING,
        BLOCKED
    }

    public record Result(
            State state,
            String detail,
            Optional<GroundItemCollectionPolicy.Candidate> target,
            int confirmedInventoryDelta,
            long retryAtMillis,
            double distanceRemaining,
            boolean progressExpected) {
        public Result {
            state = Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNull(detail, "detail");
            target = Objects.requireNonNull(target, "target");
            if (confirmedInventoryDelta < 0) {
                throw new IllegalArgumentException("confirmedInventoryDelta cannot be negative");
            }
            if (retryAtMillis < 0L) throw new IllegalArgumentException("retryAtMillis cannot be negative");
        }
    }
}
