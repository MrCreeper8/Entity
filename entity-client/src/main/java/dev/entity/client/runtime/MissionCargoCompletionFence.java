package dev.entity.client.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps the last mission-owned food snapshot sealed during its success receipt window.
 *
 * <p>The core commits a mission before the next client tick. Without this boundary,
 * ordinary hunger recovery can consume the just-promised cargo before the owner or
 * release verifier can observe the terminal result. The fence is deliberately short:
 * it is not a new inventory owner, and critical survival remains governed by the
 * direct body's existing reserved-food fallback.</p>
 */
final class MissionCargoCompletionFence {
    private final long holdMillis;
    private String missionId = "";
    private Map<String, Integer> retained = Map.of();
    private boolean completionObserved;
    private long releaseAtMillis;

    MissionCargoCompletionFence(long holdMillis) {
        if (holdMillis < 1L) throw new IllegalArgumentException("holdMillis must be positive");
        this.holdMillis = holdMillis;
    }

    Map<String, Integer> observeActive(
            String activeMissionId,
            Map<String, Integer> reservations,
            long nowMillis) {
        requireTime(nowMillis);
        String normalizedMissionId = requireMissionId(activeMissionId);
        if (!normalizedMissionId.equals(missionId)) {
            missionId = normalizedMissionId;
            completionObserved = false;
            releaseAtMillis = 0L;
        }
        retained = copyReservations(reservations);
        return retained;
    }

    Map<String, Integer> observeCompleted(String completedMissionId, long nowMillis) {
        requireTime(nowMillis);
        String normalizedMissionId = requireMissionId(completedMissionId);
        if (!normalizedMissionId.equals(missionId)) return Map.of();
        if (!completionObserved) {
            completionObserved = true;
            releaseAtMillis = saturatedAdd(nowMillis, holdMillis);
        }
        if (nowMillis < releaseAtMillis) return retained;
        retained = Map.of();
        return retained;
    }

    void release(String terminalMissionId) {
        if (terminalMissionId == null || terminalMissionId.isBlank()
                || terminalMissionId.trim().equals(missionId)) {
            clear();
        }
    }

    private void clear() {
        missionId = "";
        retained = Map.of();
        completionObserved = false;
        releaseAtMillis = 0L;
    }

    private static Map<String, Integer> copyReservations(Map<String, Integer> reservations) {
        Objects.requireNonNull(reservations, "reservations");
        LinkedHashMap<String, Integer> copied = new LinkedHashMap<>();
        reservations.forEach((item, count) -> {
            if (item == null || item.isBlank() || count == null || count <= 0) return;
            copied.merge(item, count, Math::addExact);
        });
        return Map.copyOf(copied);
    }

    private static String requireMissionId(String missionId) {
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("missionId must not be blank");
        }
        return missionId.trim();
    }

    private static void requireTime(long nowMillis) {
        if (nowMillis < 0L) throw new IllegalArgumentException("nowMillis cannot be negative");
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
