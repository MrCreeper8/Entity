package dev.entity.client.baritone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Process-lifetime, bounded evidence of attacks issued by Entity's direct close-combat executor.
 *
 * <p>The journal deliberately outlives an individual Baritone operation. A telemetry sampler can
 * therefore observe a terminal strike after the target dies and the active operation is cleared.
 * The sequence is monotonic for the lifetime of this client process and is the deduplication
 * authority; the ring is only bounded retention, never a counter reset.</p>
 */
public final class CombatAttackJournal {
    private final int capacity;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long sequence;

    public CombatAttackJournal(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** Appends one event after Minecraft's attack call has actually been issued. */
    public synchronized Event append(Draft draft) {
        Objects.requireNonNull(draft, "draft");
        sequence = Math.incrementExact(sequence);
        Event event = new Event(
                sequence,
                draft.issuedAtMillis(),
                draft.clientTick(),
                draft.source(),
                draft.operationMissionId(),
                draft.operationGeneration(),
                draft.operationStartedAtMillis(),
                draft.controlEpoch(),
                draft.targetUuid(),
                draft.targetType(),
                draft.weaponId(),
                draft.cooldownProgress(),
                draft.distance());
        events.addLast(event);
        while (events.size() > capacity) events.removeFirst();
        return event;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(sequence, new ArrayList<>(events));
    }

    public record Draft(
            long issuedAtMillis,
            long clientTick,
            String source,
            String operationMissionId,
            long operationGeneration,
            long operationStartedAtMillis,
            long controlEpoch,
            String targetUuid,
            String targetType,
            String weaponId,
            double cooldownProgress,
            double distance) {
        public Draft {
            source = required(source, "source");
            operationMissionId = required(operationMissionId, "operationMissionId");
            targetUuid = required(targetUuid, "targetUuid");
            targetType = required(targetType, "targetType");
            weaponId = required(weaponId, "weaponId");
            requireFiniteUnit(cooldownProgress, "cooldownProgress");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("distance must be finite and non-negative");
            }
        }
    }

    public record Event(
            long sequence,
            long issuedAtMillis,
            long clientTick,
            String source,
            String operationMissionId,
            long operationGeneration,
            long operationStartedAtMillis,
            long controlEpoch,
            String targetUuid,
            String targetType,
            String weaponId,
            double cooldownProgress,
            double distance) {
        public Event {
            if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
            source = required(source, "source");
            operationMissionId = required(operationMissionId, "operationMissionId");
            targetUuid = required(targetUuid, "targetUuid");
            targetType = required(targetType, "targetType");
            weaponId = required(weaponId, "weaponId");
            requireFiniteUnit(cooldownProgress, "cooldownProgress");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("distance must be finite and non-negative");
            }
        }
    }

    public record Snapshot(long sequence, List<Event> events) {
        public Snapshot {
            if (sequence < 0) throw new IllegalArgumentException("sequence cannot be negative");
            events = List.copyOf(Objects.requireNonNull(events, "events"));
        }
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return normalized;
    }

    private static void requireFiniteUnit(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be finite and in [0,1]");
        }
    }
}
