package dev.entity.client.control;

import java.util.Objects;

/**
 * Pure ownership state for one focus-independent block-breaking transaction.
 * Minecraft packets and input are deliberately kept outside this class so the
 * generation and heartbeat rules can be tested without launching the game.
 */
public final class DirectBlockBreakLease {
    private Active active;
    private long generation;

    public synchronized Claim claim(
            String operationId,
            String dimension,
            long target,
            String side,
            long playerAge) {
        Key key = new Key(operationId, dimension, target, side);
        if (active != null && active.key.equals(key)) {
            active = new Active(active.generation, key, playerAge);
            return new Claim(active.generation, false, false);
        }
        boolean replaced = active != null;
        active = new Active(++generation, key, playerAge);
        return new Claim(active.generation, true, replaced);
    }

    public synchronized boolean heartbeat(
            String operationId,
            String dimension,
            long target,
            String side,
            long playerAge) {
        if (active == null || !active.key.equals(new Key(operationId, dimension, target, side))) {
            return false;
        }
        active = new Active(active.generation, active.key, playerAge);
        return true;
    }

    public synchronized boolean release(String operationId) {
        String normalized = normalize(operationId, "operationId");
        if (active == null || !active.key.operationId.equals(normalized)) return false;
        active = null;
        return true;
    }

    /** Prevents a stale same-id controller from cancelling a replacement generation. */
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
     * Atomically consumes a stale transaction so its exact Minecraft break can
     * be cancelled before vanilla resumes crosshair-driven breaking.
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
                active.key.target,
                active.key.side,
                active.heartbeatPlayerAge);
    }

    private static String normalize(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " cannot be blank");
        return normalized;
    }

    private record Key(String operationId, String dimension, long target, String side) {
        private Key {
            operationId = normalize(operationId, "operationId");
            dimension = normalize(dimension, "dimension");
            side = normalize(side, "side");
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
            long target,
            String side,
            long heartbeatPlayerAge) {
        private static Snapshot idle() {
            return new Snapshot(false, 0, "", "", 0, "", Long.MIN_VALUE);
        }
    }
}
