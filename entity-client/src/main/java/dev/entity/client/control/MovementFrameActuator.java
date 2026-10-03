package dev.entity.client.control;

import dev.entity.core.control.ControlLease;

import java.util.Objects;
import java.util.Optional;

/**
 * Lease-safe ownership and heartbeat boundary for complete movement frames.
 *
 * <p>The actuator validates the exact outer and inner capabilities both when a
 * controller submits a frame and again when Minecraft samples it. Owner
 * changes and cleanup emit one explicit neutral input sample. Stale actions
 * cannot overwrite or cancel a newer generation.</p>
 */
public final class MovementFrameActuator {
    public static final long MAX_HEARTBEAT_AGE_TICKS = 2L;

    private Claim active;
    private boolean neutralPending;
    private long actuatorGeneration;
    private long injectionSequence;
    private long lastIssuedSequence;
    private Observation observation = Observation.idle();

    public synchronized Submission submit(
            ExecutionKernel authority,
            ControlLease parent,
            ActionLease action,
            MovementFrame frame,
            String dimension,
            long clientTick,
            long playerAge,
            long nowMillis) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(frame, "frame");
        String world = normalizeDimension(dimension);
        requireNonNegative(clientTick, "clientTick");
        requireNonNegative(playerAge, "playerAge");

        // This is not merely a structural comparison. The token must still be
        // the active generation in the kernel and its exact parent must live.
        authority.requireValid(action, parent, clientTick, nowMillis);

        boolean replacement = active != null && !sameAction(active.action, action);
        if (active == null || replacement) {
            active = new Claim(
                    authority, parent, action, frame, world, clientTick,
                    playerAge, nowMillis, ++actuatorGeneration);
            if (replacement) neutralPending = true;
        } else {
            active = new Claim(
                    authority, parent, action, frame, world, clientTick,
                    playerAge, nowMillis, active.actuatorGeneration);
        }

        return new Submission(
                active.actuatorGeneration,
                action.generation(),
                replacement,
                neutralPending,
                replacement ? "accepted new action generation; neutral handoff pending"
                        : "accepted complete movement heartbeat");
    }

    /**
     * Samples the frame at the final vanilla input boundary.
     *
     * <p>An empty result means Entity has no input ownership and vanilla input
     * must remain untouched. A neutral result is a deliberate one-tick cleanup
     * and must be installed just like any other complete frame.</p>
     */
    public synchronized Optional<IssuedFrame> sample(
            String dimension,
            long playerAge,
            long nowMillis,
            float currentYaw,
            float currentPitch,
            boolean alive) {
        String world = normalizeDimension(dimension);
        requireNonNegative(playerAge, "playerAge");
        MovementFrame neutral = MovementFrame.neutral(currentYaw, currentPitch);

        if (!alive) {
            clearOwnedFrame(Cleanup.DEATH);
        } else if (active != null && !fresh(active, world, playerAge)) {
            clearOwnedFrame(Cleanup.STALE_HEARTBEAT);
        } else if (active != null && !active.authority.isValid(
                active.action, active.parent, active.clientTick, nowMillis)) {
            clearOwnedFrame(Cleanup.INVALID_AUTHORITY);
        }

        if (neutralPending) {
            neutralPending = false;
            return Optional.of(issue(
                    neutral,
                    Kind.NEUTRAL_HANDOFF,
                    active == null ? null : active.action,
                    active == null ? 0L : active.actuatorGeneration,
                    "installed one neutral owner/cleanup boundary"));
        }
        if (active == null) return Optional.empty();
        return Optional.of(issue(
                active.frame,
                Kind.OWNED,
                active.action,
                active.actuatorGeneration,
                "installed current lease-owned movement frame"));
    }

    /** Releases only the exact inner action capability. */
    public synchronized boolean cancel(ActionLease action, Cleanup reason) {
        Objects.requireNonNull(reason, "reason");
        if (action == null || active == null || !sameAction(active.action, action)) {
            return false;
        }
        clearOwnedFrame(reason);
        return true;
    }

    /** Death, disconnect, or a global body cancellation boundary. */
    public synchronized boolean neutralizeAll(Cleanup reason) {
        Objects.requireNonNull(reason, "reason");
        if (active == null && !neutralPending) return false;
        clearOwnedFrame(reason);
        return true;
    }

    /** Correlates the actual post-mixin sample with the exact desired frame. */
    public synchronized boolean observeSampled(IssuedFrame issued, MovementFrame sampled) {
        Objects.requireNonNull(issued, "issued");
        Objects.requireNonNull(sampled, "sampled");
        if (issued.injectionSequence != lastIssuedSequence) return false;
        boolean exact = issued.desired.equals(sampled);
        observation = new Observation(
                issued.injectionSequence,
                issued.kind,
                Optional.ofNullable(issued.action),
                issued.actuatorGeneration,
                issued.desired,
                sampled,
                exact,
                exact ? "desired and sampled movement frames match"
                        : "desired and sampled movement frames differ");
        return true;
    }

    /**
     * Resolves Entity's sprint state immediately before vanilla updates the swimming pose.
     *
     * <p>This boundary intentionally reads the current claim rather than the previous input
     * observation. It therefore works on the first owned tick, but can never replay a stale
     * issued frame over a newer owner. A pending neutral handoff and a current non-sprint frame
     * explicitly clear sprint. Expired or invalid current authority is revoked atomically and
     * also clears sprint once; when Entity owns nothing, vanilla remains untouched.</p>
     */
    public synchronized Optional<SprintDirective> preSwimmingSprintDirective(
            String dimension,
            long playerAge,
            long nowMillis) {
        String world = normalizeDimension(dimension);
        requireNonNegative(playerAge, "playerAge");

        if (active != null && !fresh(active, world, playerAge)) {
            clearOwnedFrame(Cleanup.STALE_HEARTBEAT);
            return Optional.of(SprintDirective.CLEAR);
        }
        if (active != null && !active.authority.isValid(
                active.action, active.parent, active.clientTick, nowMillis)) {
            clearOwnedFrame(Cleanup.INVALID_AUTHORITY);
            return Optional.of(SprintDirective.CLEAR);
        }
        if (neutralPending) return Optional.of(SprintDirective.CLEAR);
        if (active == null) return Optional.empty();

        boolean forwardSprint = active.frame.forward()
                && !active.frame.backward()
                && active.frame.sprint();
        return Optional.of(forwardSprint ? SprintDirective.START : SprintDirective.CLEAR);
    }

    /**
     * Revalidates the exact sampled frame at the late vanilla sprint boundary.
     *
     * <p>This is intentionally separate from input installation. Baritone redirects vanilla's
     * {@code PlayerInput.sprint()} read later in {@code ClientPlayerEntity.tickMovement}, so the
     * sprint bit must be synchronized after that redirect but before superclass movement physics.
     * A stale issued object cannot affect the body: its sequence, observation, action generation,
     * heartbeat, dimension, and live kernel capability must all still describe the current claim.
     * Current owned non-sprint frames and neutral handoffs explicitly clear sprint.</p>
     */
    public synchronized Optional<SprintDirective> lateSprintDirective(
            IssuedFrame issued,
            String dimension,
            long playerAge,
            long nowMillis) {
        Objects.requireNonNull(issued, "issued");
        String world = normalizeDimension(dimension);
        requireNonNegative(playerAge, "playerAge");

        boolean currentExactSample = issued.injectionSequence == lastIssuedSequence
                && observation.injectionSequence == issued.injectionSequence
                && observation.kind == issued.kind
                && observation.actuatorGeneration == issued.actuatorGeneration
                && observation.exactMatch
                && observation.desired.equals(issued.desired)
                && observation.sampled.equals(issued.desired);
        if (!currentExactSample) return Optional.empty();

        if (issued.kind == Kind.NEUTRAL_HANDOFF) {
            // A replacement handoff embeds the owner that was current when neutral was sampled;
            // cleanup with no replacement embeds no action. A submit can occur between the input
            // and late-sprint hooks without issuing another sample, so sequence equality alone is
            // insufficient: never clear a newer owner that appeared during that narrow window.
            if (issued.action == null) {
                if (active != null) return Optional.empty();
            } else if (active == null
                    || !sameAction(active.action, issued.action)
                    || active.actuatorGeneration != issued.actuatorGeneration
                    || !fresh(active, world, playerAge)
                    || !active.authority.isValid(
                            active.action, active.parent, active.clientTick, nowMillis)) {
                return Optional.empty();
            }
            return Optional.of(SprintDirective.CLEAR);
        }
        if (active == null
                || issued.action == null
                || !sameAction(active.action, issued.action)
                || active.actuatorGeneration != issued.actuatorGeneration
                || !active.frame.equals(issued.desired)
                || !fresh(active, world, playerAge)
                || !active.authority.isValid(
                        active.action, active.parent, active.clientTick, nowMillis)) {
            return Optional.empty();
        }

        boolean forwardSprint = issued.desired.forward()
                && !issued.desired.backward()
                && issued.desired.sprint();
        return Optional.of(forwardSprint ? SprintDirective.START : SprintDirective.CLEAR);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                Optional.ofNullable(active).map(claim -> claim.action),
                active == null ? 0L : active.actuatorGeneration,
                active == null ? Long.MIN_VALUE : active.heartbeatPlayerAge,
                neutralPending,
                observation);
    }

    private IssuedFrame issue(
            MovementFrame desired,
            Kind kind,
            ActionLease action,
            long generation,
            String detail) {
        long sequence = ++injectionSequence;
        lastIssuedSequence = sequence;
        return new IssuedFrame(sequence, kind, action, generation, desired, detail);
    }

    private void clearOwnedFrame(Cleanup reason) {
        boolean hadOwnership = active != null || neutralPending;
        active = null;
        if (hadOwnership) neutralPending = true;
        // Observation is historical evidence of what vanilla actually sampled. Cleanup only
        // schedules a future neutral frame; rewriting the old observation here would manufacture
        // an exact neutral sample with the old injection sequence. Consumers use neutralPending
        // until sample() issues and observeSampled() confirms the real handoff.
    }

    private static boolean fresh(Claim claim, String dimension, long playerAge) {
        long ageDelta = playerAge - claim.heartbeatPlayerAge;
        return claim.dimension.equals(dimension)
                && ageDelta >= 0L
                && ageDelta <= MAX_HEARTBEAT_AGE_TICKS;
    }

    private static boolean sameAction(ActionLease left, ActionLease right) {
        return left.equals(right);
    }

    private static String normalizeDimension(String dimension) {
        String value = Objects.requireNonNull(dimension, "dimension").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("dimension cannot be blank");
        return value;
    }

    private static void requireNonNegative(long value, String label) {
        if (value < 0L) throw new IllegalArgumentException(label + " cannot be negative");
    }

    public enum Kind {
        OWNED,
        NEUTRAL_HANDOFF
    }

    public enum SprintDirective {
        START,
        CLEAR
    }

    public enum Cleanup {
        CANCEL,
        OWNER_TRANSITION,
        DEATH,
        DISCONNECT,
        STALE_HEARTBEAT,
        INVALID_AUTHORITY
    }

    public record Submission(
            long actuatorGeneration,
            long actionGeneration,
            boolean replacedPriorOwner,
            boolean neutralHandoffPending,
            String detail) {
    }

    public record IssuedFrame(
            long injectionSequence,
            Kind kind,
            ActionLease action,
            long actuatorGeneration,
            MovementFrame desired,
            String detail) {
        public IssuedFrame {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(desired, "desired");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record Observation(
            long injectionSequence,
            Kind kind,
            Optional<ActionLease> action,
            long actuatorGeneration,
            MovementFrame desired,
            MovementFrame sampled,
            boolean exactMatch,
            String detail) {
        public Observation {
            Objects.requireNonNull(kind, "kind");
            action = Objects.requireNonNull(action, "action");
            Objects.requireNonNull(desired, "desired");
            Objects.requireNonNull(sampled, "sampled");
            detail = Objects.requireNonNullElse(detail, "");
        }

        private static Observation idle() {
            MovementFrame neutral = MovementFrame.neutral(0.0F, 0.0F);
            return new Observation(
                    0L, Kind.NEUTRAL_HANDOFF, Optional.empty(), 0L,
                    neutral, neutral, true, "no movement frame sampled yet");
        }
    }

    public record Snapshot(
            Optional<ActionLease> activeAction,
            long actuatorGeneration,
            long heartbeatPlayerAge,
            boolean neutralPending,
            Observation observation) {
        public Snapshot {
            activeAction = Objects.requireNonNull(activeAction, "activeAction");
            Objects.requireNonNull(observation, "observation");
        }
    }

    private record Claim(
            ExecutionKernel authority,
            ControlLease parent,
            ActionLease action,
            MovementFrame frame,
            String dimension,
            long clientTick,
            long heartbeatPlayerAge,
            long heartbeatMillis,
            long actuatorGeneration) {
    }
}
