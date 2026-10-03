package dev.entity.core.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded, queryable explanation trail suitable for the future /e why command. */
public final class DecisionTrace {
    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long nextSequence = 1;

    public DecisionTrace(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized Entry add(
            long nowMillis,
            String category,
            String decision,
            String reason,
            String missionId,
            Map<String, String> details) {
        Entry entry = new Entry(
                nextSequence++,
                nowMillis,
                category,
                decision,
                reason,
                Objects.requireNonNullElse(missionId, ""),
                details == null ? Map.of() : Map.copyOf(details));
        entries.addLast(entry);
        while (entries.size() > capacity) {
            entries.removeFirst();
        }
        return entry;
    }

    public synchronized List<Entry> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Entry> all = new ArrayList<>(entries);
        return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    public synchronized String explainLatest() {
        Entry latest = entries.peekLast();
        if (latest == null) {
            return "No decisions have been made yet.";
        }
        String mission = latest.missionId().isBlank() ? "" : " [mission " + latest.missionId() + "]";
        return latest.decision() + mission + ": " + latest.reason();
    }

    public record Entry(
            long sequence,
            long timestampMillis,
            String category,
            String decision,
            String reason,
            String missionId,
            Map<String, String> details) {
    }
}
