package dev.entitybridge.mission;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

public record Mission(
        String id,
        String commandId,
        long sequence,
        String action,
        JsonObject args,
        String requestedBy,
        long createdAt,
        long updatedAt,
        MissionStatus status,
        int attempt,
        String step,
        String reason
) {
    public Mission {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(status, "status");
        args = args == null ? new JsonObject() : args.deepCopy();
        attempt = Math.max(1, attempt);
    }

    public boolean terminal() {
        return status.terminal();
    }

    public String shortId() {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public Mission update(MissionStatus nextStatus, String nextStep, String nextReason, long now) {
        return new Mission(
                id, commandId, sequence, action, args, requestedBy, createdAt, now,
                nextStatus, attempt, nextStep, nextReason
        );
    }

    public Mission retry(long now) {
        return new Mission(
                id, commandId, sequence, action, args, requestedBy, createdAt, now,
                MissionStatus.RETRYING, attempt + 1, "replanning", "Retry requested by owner"
        );
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("commandId", commandId);
        json.addProperty("sequence", sequence);
        json.addProperty("action", action);
        json.add("args", args.deepCopy());
        json.addProperty("requestedBy", requestedBy);
        json.addProperty("createdAt", createdAt);
        json.addProperty("updatedAt", updatedAt);
        json.addProperty("status", status.name().toLowerCase());
        json.addProperty("attempt", attempt);
        if (step != null) {
            json.addProperty("step", step);
        }
        if (reason != null) {
            json.addProperty("reason", reason);
        }
        return json;
    }

    static Mission fromJson(JsonObject json) {
        String id = requiredText(json, "id");
        String action = requiredText(json, "action");
        String requestedBy = text(json, "requestedBy", "unknown");
        long createdAt = number(json, "createdAt", System.currentTimeMillis());
        return new Mission(
                id,
                text(json, "commandId", id),
                Math.max(1L, number(json, "sequence", 1L)),
                action,
                object(json.get("args")),
                requestedBy,
                createdAt,
                number(json, "updatedAt", createdAt),
                MissionStatus.parse(text(json, "status", null), MissionStatus.QUEUED),
                (int) Math.max(1L, number(json, "attempt", 1L)),
                text(json, "step", null),
                text(json, "reason", null)
        );
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String requiredText(JsonObject object, String key) {
        String value = text(object, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("mission." + key + " is required");
        }
        return value;
    }

    private static String text(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long number(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
