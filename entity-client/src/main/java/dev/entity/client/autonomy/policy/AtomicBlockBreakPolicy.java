package dev.entity.client.autonomy.policy;

import java.util.Objects;

/**
 * Pure state machine for one honest block-breaking transaction.
 *
 * <p>A transaction pins both the concrete block target and the held-tool
 * identity. A changed tool is never interpreted as server protection, and a
 * missing client crack animation is never authoritative. Only an explicit
 * server rejection may produce {@link State#SERVER_REJECTED}; an elapsed
 * deadline produces the recoverable {@link State#STALLED} state. In
 * particular, {@code ClientPlayerInteractionManager.isBreakingBlock()} is an
 * observation, not ownership: Minecraft can report it false for a tick while
 * the held attack actuator is being transferred or restarted.</p>
 */
public final class AtomicBlockBreakPolicy {
    private static final long MINIMUM_DEADLINE_MILLIS = 5_000L;
    private static final long MAXIMUM_DEADLINE_MILLIS = 45_000L;
    private Request request;
    private long startedAtMillis;
    private long lastProgressAtMillis;
    private int highestStage = -1;
    private int restarts;
    private boolean started;

    public Decision observe(Request next, Observation observation) {
        Objects.requireNonNull(next, "request");
        Objects.requireNonNull(observation, "observation");
        if (request == null || !request.equals(next)) {
            reset(next, observation.nowMillis());
        }

        if (!observation.blockPresent()) {
            return decision(State.COMPLETE, "world confirmed the pinned block changed");
        }
        if (observation.serverRejected()) {
            return decision(State.SERVER_REJECTED,
                    "Paper rejected the exact pinned block-break transaction");
        }
        if (!request.toolFingerprint().equals(observation.toolFingerprint())) {
            return decision(State.TOOL_PREEMPTED,
                    "held tool changed during the pinned block-break transaction");
        }
        if (!observation.inReach()) {
            return decision(State.OUT_OF_REACH, "pinned block moved outside legitimate reach");
        }
        if (!observation.sightline()) {
            return decision(State.SIGHTLINE_LOST, "pinned block lost its direct sightline");
        }
        int stage = observation.progressStage();
        if (stage >= 0 && stage > highestStage) {
            highestStage = stage;
            lastProgressAtMillis = observation.nowMillis();
        }

        if (!started) {
            started = true;
            startedAtMillis = observation.nowMillis();
            lastProgressAtMillis = observation.nowMillis();
            return decision(State.STARTED, "started one pinned block-break transaction");
        }

        long deadline = deadlineMillis(request.expectedBreakMillis());
        long elapsed = observation.nowMillis() - startedAtMillis;
        long sinceProgress = observation.nowMillis() - lastProgressAtMillis;
        if (elapsed >= deadline && sinceProgress >= Math.min(deadline, 8_000L)) {
            return decision(State.STALLED,
                    "pinned break exceeded its tool-aware completion deadline without a world change");
        }
        if (!observation.clientStillBreaking()) {
            return decision(State.CONTINUE,
                    observation.actionAccepted()
                            ? "holding attack while Minecraft reacquires the same pinned block"
                            : "holding attack after a transient local action refusal");
        }
        return decision(State.CONTINUE, "continuing the same pinned block-break transaction");
    }

    public void clear() {
        request = null;
        started = false;
        highestStage = -1;
        restarts = 0;
        startedAtMillis = 0L;
        lastProgressAtMillis = 0L;
    }

    public Snapshot snapshot() {
        return new Snapshot(request, started, highestStage, restarts,
                startedAtMillis, lastProgressAtMillis);
    }

    static long deadlineMillis(long expectedBreakMillis) {
        long expected = Math.max(250L, expectedBreakMillis);
        long multiplied;
        try {
            multiplied = Math.addExact(Math.multiplyExact(expected, 4L), 1_500L);
        } catch (ArithmeticException ignored) {
            multiplied = Long.MAX_VALUE;
        }
        return Math.max(MINIMUM_DEADLINE_MILLIS,
                Math.min(MAXIMUM_DEADLINE_MILLIS, multiplied));
    }

    private void reset(Request next, long nowMillis) {
        request = next;
        started = false;
        highestStage = -1;
        restarts = 0;
        startedAtMillis = nowMillis;
        lastProgressAtMillis = nowMillis;
    }

    private Decision decision(State state, String detail) {
        return new Decision(state, detail, restarts, highestStage);
    }

    public record Request(
            String operationId,
            String targetKey,
            String toolFingerprint,
            long expectedBreakMillis) {
        public Request {
            operationId = requireText(operationId, "operationId");
            targetKey = requireText(targetKey, "targetKey");
            toolFingerprint = requireText(toolFingerprint, "toolFingerprint");
            if (expectedBreakMillis < 0) {
                throw new IllegalArgumentException("expectedBreakMillis cannot be negative");
            }
        }
    }

    public record Observation(
            boolean blockPresent,
            boolean inReach,
            boolean sightline,
            boolean actionAccepted,
            boolean clientStillBreaking,
            boolean serverRejected,
            String toolFingerprint,
            int progressStage,
            long nowMillis) {
        public Observation {
            toolFingerprint = requireText(toolFingerprint, "toolFingerprint");
            if (progressStage < -1) throw new IllegalArgumentException("invalid break stage");
            if (nowMillis < 0) throw new IllegalArgumentException("nowMillis cannot be negative");
        }
    }

    public record Decision(State state, String detail, int restarts, int highestStage) {
        public Decision {
            Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
            if (restarts < 0 || highestStage < -1) {
                throw new IllegalArgumentException("invalid atomic break counters");
            }
        }
    }

    public record Snapshot(
            Request request,
            boolean started,
            int highestStage,
            int restarts,
            long startedAtMillis,
            long lastProgressAtMillis) {
    }

    public enum State {
        STARTED,
        CONTINUE,
        RESTART_REQUIRED,
        COMPLETE,
        TOOL_PREEMPTED,
        OUT_OF_REACH,
        SIGHTLINE_LOST,
        ACTION_REJECTED,
        SERVER_REJECTED,
        STALLED
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNullElse(value, "").trim();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " cannot be blank");
        return result;
    }
}
