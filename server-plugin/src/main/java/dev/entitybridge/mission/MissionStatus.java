package dev.entitybridge.mission;

import java.util.Locale;

public enum MissionStatus {
    QUEUED,
    ACTIVE,
    PAUSED,
    BLOCKED,
    RETRYING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public static MissionStatus parse(String value, MissionStatus fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        switch (normalized) {
            case "pending", "created", "queued" -> { return QUEUED; }
            case "accepted", "persisted", "running", "executing", "in_progress" -> { return ACTIVE; }
            case "paused" -> { return PAUSED; }
            case "blocked", "waiting", "recovering" -> { return BLOCKED; }
            case "retry", "retrying", "replanning" -> { return RETRYING; }
            case "complete", "completed", "success", "succeeded", "done" -> { return SUCCEEDED; }
            case "failure", "failed", "error" -> { return FAILED; }
            case "cancel", "cancelled", "canceled", "stopped" -> { return CANCELLED; }
            default -> { }
        }
        try {
            return valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
