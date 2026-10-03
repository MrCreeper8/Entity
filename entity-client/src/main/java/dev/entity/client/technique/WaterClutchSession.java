package dev.entity.client.technique;

import java.util.Objects;

/**
 * One pinned request/ack/result transaction for a water-bucket clutch.
 * Accepted clicks are never interpreted as placed water or a safe landing.
 */
public final class WaterClutchSession {
    public static final long PLACEMENT_ACK_TIMEOUT_MILLIS = 1_000L;
    public static final long LANDING_ACK_TIMEOUT_MILLIS = 4_000L;
    public static final long RECOVERY_ACK_TIMEOUT_MILLIS = 1_500L;

    private Phase phase = Phase.IDLE;
    private String targetKey = "";
    private String detail = "idle";
    private long phaseStartedAt;
    private int placementRequests;
    private int recoveryRequests;
    private boolean recovered;
    private Snapshot lastEpisode = new Snapshot(
            Phase.IDLE, "", "no retained clutch episode", 0, 0, false);

    public boolean begin(String targetKey, long nowMillis) {
        String key = requireText(targetKey, "targetKey");
        if (phase != Phase.IDLE) return this.targetKey.equals(key) && !terminal();
        this.targetKey = key;
        phase = Phase.AIMING;
        phaseStartedAt = nowMillis;
        detail = "aiming at pinned clutch target " + key;
        return true;
    }

    public Action advance(long nowMillis, Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (phase == Phase.IDLE) return Action.WAIT;
        if (terminal()) return phase == Phase.COMPLETE ? Action.COMPLETE : Action.FAILED;
        if (!observation.alive()) return fail("Entity died before clutch acknowledgement");

        if (phase == Phase.AIMING) {
            if (!observation.targetStillValid()) {
                return fail("pinned clutch geometry changed before use");
            }
            return placementRequests == 0 ? Action.PLACE_WATER : Action.WAIT;
        }
        if (phase == Phase.PLACEMENT_REQUESTED) {
            if (observation.waterPlaced()) {
                phase = Phase.WATER_PLACED;
                phaseStartedAt = nowMillis;
                detail = "world acknowledged the pinned water source";
            } else if (!observation.targetStillValid()) {
                return fail("pinned clutch geometry changed without water acknowledgement");
            } else if (nowMillis - phaseStartedAt > PLACEMENT_ACK_TIMEOUT_MILLIS) {
                return fail("accepted bucket use produced no water acknowledgement");
            } else {
                return Action.WAIT;
            }
        }
        if (phase == Phase.WATER_PLACED) {
            if (!observation.placedWaterPresent() && !observation.safelyLanded()) {
                return fail("placed clutch water disappeared before a safe landing");
            }
            if (observation.safelyLanded()) {
                phase = Phase.LANDED;
                phaseStartedAt = nowMillis;
                detail = "safe landing acknowledged";
            } else if (nowMillis - phaseStartedAt > LANDING_ACK_TIMEOUT_MILLIS) {
                return fail("water was placed but a safe landing was not acknowledged");
            } else {
                return Action.WAIT;
            }
        }
        if (phase == Phase.LANDED) {
            if (observation.waterBucketRecovered()) {
                return complete(true, "safe landing and water-bucket recovery acknowledged");
            }
            if (!observation.placedWaterPresent() || !observation.recoveryReachable()) {
                return complete(false,
                        "safe landing acknowledged; placed source is not safely recoverable");
            }
            return recoveryRequests == 0 ? Action.RECOVER_WATER : Action.WAIT;
        }
        if (phase == Phase.RECOVERY_REQUESTED) {
            if (observation.waterBucketRecovered()) {
                return complete(true, "safe landing and water-bucket recovery acknowledged");
            }
            if (nowMillis - phaseStartedAt > RECOVERY_ACK_TIMEOUT_MILLIS
                    || !observation.placedWaterPresent()
                    || !observation.recoveryReachable()) {
                return complete(false,
                        "safe landing acknowledged; bucket recovery could not be verified");
            }
            return Action.WAIT;
        }
        throw new IllegalStateException("unhandled clutch phase " + phase);
    }

    public void recordPlacementRequest(boolean accepted, long nowMillis) {
        if (phase != Phase.AIMING || placementRequests != 0) {
            throw new IllegalStateException("water placement is not requestable in " + phase);
        }
        placementRequests++;
        phaseStartedAt = nowMillis;
        if (!accepted) {
            fail("Minecraft rejected the one pinned water placement request");
            return;
        }
        phase = Phase.PLACEMENT_REQUESTED;
        detail = "placement request accepted; waiting for world acknowledgement";
    }

    public void recordRecoveryRequest(boolean accepted, long nowMillis) {
        if (phase != Phase.LANDED || recoveryRequests != 0) {
            throw new IllegalStateException("water recovery is not requestable in " + phase);
        }
        recoveryRequests++;
        phaseStartedAt = nowMillis;
        if (!accepted) {
            complete(false, "Minecraft rejected the one water-recovery request");
            return;
        }
        phase = Phase.RECOVERY_REQUESTED;
        detail = "recovery request accepted; waiting for bucket acknowledgement";
    }

    public Snapshot snapshot() {
        return new Snapshot(
                phase, targetKey, detail, placementRequests, recoveryRequests, recovered);
    }

    /**
     * Keeps the exact last request/ack/result after runtime custody resets the active session.
     * Live evidence must not depend on sampling the single tick before neutralization.
     */
    public Snapshot evidenceSnapshot() {
        return phase == Phase.IDLE ? lastEpisode : snapshot();
    }

    public boolean terminal() {
        return phase == Phase.COMPLETE || phase == Phase.FAILED;
    }

    /** Latches one externally observed actuator mismatch without reopening the episode. */
    public void abort(String detail) {
        if (!terminal()) fail(requireText(detail, "detail"));
    }

    public void reset() {
        if (phase != Phase.IDLE) lastEpisode = snapshot();
        phase = Phase.IDLE;
        targetKey = "";
        detail = "idle";
        phaseStartedAt = 0L;
        placementRequests = 0;
        recoveryRequests = 0;
        recovered = false;
    }

    private Action complete(boolean recovered, String detail) {
        phase = Phase.COMPLETE;
        this.recovered = recovered;
        this.detail = detail;
        return Action.COMPLETE;
    }

    private Action fail(String detail) {
        phase = Phase.FAILED;
        this.detail = detail;
        return Action.FAILED;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }

    public enum Phase {
        IDLE,
        AIMING,
        PLACEMENT_REQUESTED,
        WATER_PLACED,
        LANDED,
        RECOVERY_REQUESTED,
        COMPLETE,
        FAILED
    }

    public enum Action {
        WAIT,
        PLACE_WATER,
        RECOVER_WATER,
        COMPLETE,
        FAILED
    }

    public record Observation(
            boolean alive,
            boolean targetStillValid,
            boolean waterPlaced,
            boolean placedWaterPresent,
            boolean safelyLanded,
            boolean recoveryReachable,
            boolean waterBucketRecovered) {
    }

    public record Snapshot(
            Phase phase,
            String targetKey,
            String detail,
            int placementRequests,
            int recoveryRequests,
            boolean recovered) {
    }
}
