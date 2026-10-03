package dev.entity.client.autonomy.resource;

import java.util.Objects;

/**
 * Bounded movement contract for crossing one already-open farm-boundary gate.
 *
 * <p>Remote and ordinary land travel remain Baritone-owned. This policy exists
 * only for the short, fully loaded corridor between a named farm's exterior
 * approach and the first supported interior cell. It never opens a gate or
 * authorizes a block interaction.</p>
 */
public final class ManagedFarmIngressPolicy {
    public static final long MAXIMUM_CROSSING_MILLIS = 8_000L;
    public static final double COMPLETION_MARGIN = 0.55D;

    private ManagedFarmIngressPolicy() {
    }

    public static Session begin(
            String dimension,
            int gateX,
            int gateY,
            int gateZ,
            String blockId,
            String stableFingerprint,
            int axisX,
            int axisZ,
            int outsideSign,
            long nowMillis) {
        return new Session(
                required(dimension, "dimension"),
                gateX,
                gateY,
                gateZ,
                required(blockId, "blockId"),
                required(stableFingerprint, "stableFingerprint"),
                axisX,
                axisZ,
                outsideSign,
                nowMillis);
    }

    public static Decision decide(Session session, Observation observation) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(observation, "observation");
        if (!session.dimension().equals(observation.dimension())) {
            return Decision.blocked("farm ingress changed dimension before crossing completed");
        }
        if (!observation.contextReady()) {
            return Decision.waiting("waiting for the loaded farm gate corridor");
        }
        if (!observation.sameGate()) {
            return Decision.blocked("the selected farm entrance changed before crossing completed");
        }
        if (!observation.gateOpen()) {
            return Decision.blocked("the selected farm gate closed before crossing completed");
        }
        double signedDistance = session.signedDistance(
                observation.playerX(), observation.playerZ());
        if (signedDistance * session.outsideSign() <= -COMPLETION_MARGIN) {
            return Decision.complete("crossed the open selected-farm gate without changing it");
        }
        if (!observation.corridorSafe()) {
            return Decision.blocked("the loaded farm entrance corridor is no longer clear and supported");
        }
        if (observation.horizontalCollision()) {
            return Decision.blocked("physical collision appeared in the verified farm entrance corridor");
        }
        if (observation.nowMillis() - session.startedAtMillis()
                > MAXIMUM_CROSSING_MILLIS) {
            return Decision.blocked("timed out while crossing the verified open farm gate");
        }
        if (!observation.movementAuthorityValid()) {
            return Decision.waiting("waiting for the farm mission's exact movement authority");
        }

        double targetX = session.gateX() + 0.5D
                - session.outsideSign() * session.axisX();
        double targetZ = session.gateZ() + 0.5D
                - session.outsideSign() * session.axisZ();
        double dx = targetX - observation.playerX();
        double dz = targetZ - observation.playerZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        return Decision.move(targetX, session.gateY(), targetZ, yaw,
                "walking through the already-open selected-farm gate");
    }

    private static String required(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " cannot be blank");
        return normalized;
    }

    public record Session(
            String dimension,
            int gateX,
            int gateY,
            int gateZ,
            String blockId,
            String stableFingerprint,
            int axisX,
            int axisZ,
            int outsideSign,
            long startedAtMillis) {
        public Session {
            dimension = required(dimension, "dimension");
            blockId = required(blockId, "blockId");
            stableFingerprint = required(stableFingerprint, "stableFingerprint");
            if (Math.abs(axisX) + Math.abs(axisZ) != 1) {
                throw new IllegalArgumentException("farm gate axis must be one horizontal cardinal direction");
            }
            if (outsideSign != -1 && outsideSign != 1) {
                throw new IllegalArgumentException("outsideSign must be -1 or 1");
            }
        }

        public double signedDistance(double playerX, double playerZ) {
            return (playerX - (gateX + 0.5D)) * axisX
                    + (playerZ - (gateZ + 0.5D)) * axisZ;
        }

        public int insideX() {
            return gateX - outsideSign * axisX;
        }

        public int insideZ() {
            return gateZ - outsideSign * axisZ;
        }
    }

    public record Observation(
            String dimension,
            boolean contextReady,
            boolean sameGate,
            boolean gateOpen,
            boolean corridorSafe,
            boolean movementAuthorityValid,
            boolean horizontalCollision,
            double playerX,
            double playerZ,
            long nowMillis) {
        public Observation {
            dimension = required(dimension, "dimension");
            if (!Double.isFinite(playerX) || !Double.isFinite(playerZ)) {
                throw new IllegalArgumentException("player position must be finite");
            }
        }
    }

    public enum Action {
        WAIT,
        MOVE,
        COMPLETE,
        BLOCKED
    }

    public record Decision(
            Action action,
            double targetX,
            double targetY,
            double targetZ,
            float yaw,
            String detail) {
        public Decision {
            Objects.requireNonNull(action, "action");
            detail = Objects.requireNonNullElse(detail, "");
        }

        private static Decision waiting(String detail) {
            return new Decision(Action.WAIT, Double.NaN, Double.NaN, Double.NaN,
                    Float.NaN, detail);
        }

        private static Decision move(
                double targetX,
                double targetY,
                double targetZ,
                float yaw,
                String detail) {
            return new Decision(Action.MOVE, targetX, targetY, targetZ, yaw, detail);
        }

        private static Decision complete(String detail) {
            return new Decision(Action.COMPLETE, Double.NaN, Double.NaN, Double.NaN,
                    Float.NaN, detail);
        }

        private static Decision blocked(String detail) {
            return new Decision(Action.BLOCKED, Double.NaN, Double.NaN, Double.NaN,
                    Float.NaN, detail);
        }
    }
}
