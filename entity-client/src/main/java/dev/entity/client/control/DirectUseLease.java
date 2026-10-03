package dev.entity.client.control;

import java.util.Objects;

/** Pure ownership and heartbeat state for one continuously held use action. */
public final class DirectUseLease {
    private Active active;
    private long generation;

    public synchronized Claim claim(
            String operationId,
            String dimension,
            long playerAge) {
        Key key = new Key(operationId, dimension);
        if (active != null && active.key.equals(key)) {
            active = new Active(active.generation, key, playerAge);
            return new Claim(active.generation, false, false);
        }
        boolean replaced = active != null;
        active = new Active(++generation, key, playerAge);
        return new Claim(active.generation, true, replaced);
    }

    public synchronized boolean release(String operationId) {
        String normalized = normalize(operationId, "operationId");
        if (active == null || !active.key.operationId.equals(normalized)) return false;
        active = null;
        return true;
    }

    /**
     * Releases a capability only while the caller still owns its exact generation.
     * A late cleanup from an earlier incarnation of the same operation id must not
     * stop a replacement hold.
     */
    public synchronized boolean release(String operationId, long expectedGeneration) {
        String normalized = normalize(operationId, "operationId");
        if (expectedGeneration <= 0L
                || active == null
                || active.generation != expectedGeneration
                || !active.key.operationId.equals(normalized)) return false;
        active = null;
        return true;
    }

    public synchronized boolean clear() {
        boolean hadActive = active != null;
        active = null;
        return hadActive;
    }

    public synchronized boolean freshFor(String dimension, long playerAge, long maximumAgeDelta) {
        if (maximumAgeDelta < 0) throw new IllegalArgumentException("maximumAgeDelta cannot be negative");
        if (active == null || !active.key.dimension.equals(normalize(dimension, "dimension"))) return false;
        long delta = playerAge - active.heartbeatPlayerAge;
        return delta >= 0 && delta <= maximumAgeDelta;
    }

    /**
     * Atomically consumes stale ownership so the actuator boundary can unlatch
     * the physical use key before permitting vanilla input again.
     */
    public synchronized Freshness expireUnlessFresh(
            String dimension,
            long playerAge,
            long maximumAgeDelta) {
        if (maximumAgeDelta < 0) {
            throw new IllegalArgumentException("maximumAgeDelta cannot be negative");
        }
        if (active == null) return Freshness.IDLE;
        String normalizedDimension = normalize(dimension, "dimension");
        long delta = playerAge - active.heartbeatPlayerAge;
        if (active.key.dimension.equals(normalizedDimension)
                && delta >= 0
                && delta <= maximumAgeDelta) return Freshness.FRESH;
        active = null;
        return Freshness.EXPIRED;
    }

    public synchronized Snapshot snapshot() {
        if (active == null) return Snapshot.idle();
        return new Snapshot(
                true,
                active.generation,
                active.key.operationId,
                active.key.dimension,
                active.heartbeatPlayerAge);
    }

    private static String normalize(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " cannot be blank");
        return normalized;
    }

    private record Key(String operationId, String dimension) {
        private Key {
            operationId = normalize(operationId, "operationId");
            dimension = normalize(dimension, "dimension");
        }
    }

    private record Active(long generation, Key key, long heartbeatPlayerAge) {
    }

    public record Claim(long generation, boolean started, boolean replacedPriorOwner) {
    }

    public enum Freshness {
        IDLE,
        FRESH,
        EXPIRED
    }

    public record Snapshot(
            boolean active,
            long generation,
            String operationId,
            String dimension,
            long heartbeatPlayerAge) {
        private static Snapshot idle() {
            return new Snapshot(false, 0L, "", "", Long.MIN_VALUE);
        }
    }
}
