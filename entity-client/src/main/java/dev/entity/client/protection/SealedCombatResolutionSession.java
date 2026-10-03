package dev.entity.client.protection;

import java.util.Objects;

/**
 * Exact-target lifecycle for low-health resolution after direct retreat has no safe route.
 *
 * <p>Each active attempt is bounded separately by {@link CombatTargetResolutionWatchdog}. When
 * that watchdog proves no progress, this session enforces a non-sliding defensive grace before
 * the same sole ordinary target may receive another attempt. A real target change clears the old
 * identity; retaining the same identity across a temporary survival preemption changes nothing.</p>
 */
public final class SealedCombatResolutionSession {
    public static final long REARM_GRACE_MILLIS = 2_000L;

    private String targetId = "";
    private State state = State.NONE;
    private long rearmAtMillis;

    /**
     * Begins an initial or grace-complete retry. Returns false for an already-active attempt or an
     * exhausted same-target attempt whose fixed rearm deadline has not elapsed.
     */
    public boolean begin(String nextTargetId, long nowMillis) {
        String normalized = requireTarget(nextTargetId);
        if (normalized.equals(targetId)) {
            if (state == State.ACTIVE) return false;
            if (state == State.EXHAUSTED && nowMillis < rearmAtMillis) return false;
        }
        targetId = normalized;
        state = State.ACTIVE;
        rearmAtMillis = 0L;
        return true;
    }

    /**
     * Exhausts exactly one live attempt. Repeated failure reports cannot slide its rearm deadline.
     */
    public boolean exhaust(String exactTargetId, long nowMillis) {
        String normalized = normalize(exactTargetId);
        if (state != State.ACTIVE || !targetId.equals(normalized)) return false;
        state = State.EXHAUSTED;
        rearmAtMillis = saturatedAdd(nowMillis, REARM_GRACE_MILLIS);
        return true;
    }

    /**
     * Retains state for the same real UUID and clears it only for a different real UUID.
     * Progress-monitor resets and temporary layer changes are intentionally not inputs.
     */
    public void retainTarget(String exactTargetId) {
        if (state == State.NONE) return;
        if (!targetId.equals(normalize(exactTargetId))) clear();
    }

    public boolean activeFor(String exactTargetId) {
        return state == State.ACTIVE && targetId.equals(normalize(exactTargetId));
    }

    public boolean exhaustedFor(String exactTargetId) {
        return state == State.EXHAUSTED && targetId.equals(normalize(exactTargetId));
    }

    public boolean rearmReadyFor(String exactTargetId, long nowMillis) {
        return exhaustedFor(exactTargetId) && nowMillis >= rearmAtMillis;
    }

    public long rearmAtMillis() {
        return rearmAtMillis;
    }

    public State state() {
        return state;
    }

    public String targetId() {
        return targetId;
    }

    public void clear() {
        targetId = "";
        state = State.NONE;
        rearmAtMillis = 0L;
    }

    public enum State {
        NONE,
        ACTIVE,
        EXHAUSTED
    }

    private static String requireTarget(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("sealed resolution requires an exact target UUID");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
