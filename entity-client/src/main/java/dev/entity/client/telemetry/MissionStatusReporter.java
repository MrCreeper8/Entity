package dev.entity.client.telemetry;

import com.google.gson.JsonObject;
import dev.entity.client.bridge.BridgeResultPolicy;
import dev.entity.core.model.Mission;
import dev.entity.core.model.MissionState;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Builds idempotent protocol-2 mission updates and retries a failed transport write. */
public final class MissionStatusReporter {
    private final Map<String, Long> deliveredRevisions = new HashMap<>();

    public synchronized boolean publish(Mission mission, String reason, Predicate<JsonObject> sender) {
        if (deliveredRevisions.getOrDefault(mission.id(), -1L) >= mission.revision()) return false;
        JsonObject frame = frame(mission, reason);
        if (!sender.test(frame)) return false;
        deliveredRevisions.put(mission.id(), mission.revision());
        return true;
    }

    public synchronized boolean delivered(Mission mission) {
        return deliveredRevisions.getOrDefault(mission.id(), -1L) >= mission.revision();
    }

    public synchronized void markDelivered(Mission mission) {
        deliveredRevisions.put(mission.id(), mission.revision());
    }

    public synchronized void newSession() {
        deliveredRevisions.clear();
    }

    public static JsonObject frame(Mission mission, String reason) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "mission_update");
        frame.addProperty("missionId", mission.id());
        sequence(mission).ifPresent(value -> frame.addProperty("sequence", value));
        appendQueueCorrelation(frame, mission.parameters());
        frame.addProperty("status", wireStatus(mission.state()));
        // Bound only the wire view. The durable mission and local diagnostics retain
        // the full failed action (including verbose pending blueprint cells).
        frame.addProperty("step", BridgeResultPolicy.boundedMessage(mission.phase()));
        frame.addProperty("reason", BridgeResultPolicy.boundedMessage(
                reason == null || reason.isBlank() ? defaultReason(mission) : reason));
        return frame;
    }

    public static String wireStatus(MissionState state) {
        return switch (state) {
            case QUEUED -> "queued";
            case RUNNING -> "active";
            case PAUSED_BY_OWNER, PAUSED_BY_SAFETY, PAUSED_BY_PROTECTION -> "paused";
            case RETRY_WAIT -> "retrying";
            case BLOCKED -> "blocked";
            case COMPLETED -> "succeeded";
            case CANCELLED -> "cancelled";
        };
    }

    public static java.util.OptionalLong sequence(Mission mission) {
        String raw = mission.parameters().get("sequence");
        if (raw == null) return java.util.OptionalLong.empty();
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? java.util.OptionalLong.of(value) : java.util.OptionalLong.empty();
        } catch (NumberFormatException ignored) {
            return java.util.OptionalLong.empty();
        }
    }

    public static boolean unregisteredQueuedHome(Mission mission) {
        return "true".equals(mission.parameters().get("homeGoCommand"))
                && mission.parameters().containsKey("queueId");
    }

    /** Preserve the exact dispatched attempt across command, mission and Home-go receipts. */
    public static Map<String, String> queueParameters(JsonObject arguments) {
        var values = new java.util.LinkedHashMap<String, String>();
        for (String key : java.util.List.of("queueId", "queueGeneration", "queueStep", "queueAttempt")) {
            if (arguments.has(key)) {
                if (!arguments.get(key).isJsonPrimitive())
                    throw new IllegalArgumentException("Invalid queue correlation " + key);
                values.put(key, arguments.get(key).getAsString());
            }
        }
        if (values.isEmpty()) return Map.of();
        validateQueueCorrelation(values);
        return Map.copyOf(values);
    }

    public static void appendQueueCorrelation(JsonObject frame, Map<String, String> values) {
        if (!values.containsKey("queueId")) return;
        validateQueueCorrelation(values);
        frame.addProperty("queueId", values.get("queueId"));
        frame.addProperty("queueGeneration", Long.parseLong(values.get("queueGeneration")));
        frame.addProperty("queueStep", Integer.parseInt(values.get("queueStep")));
        frame.addProperty("queueAttempt", Integer.parseInt(values.get("queueAttempt")));
    }

    private static void validateQueueCorrelation(Map<String, String> values) {
        try {
            String id = values.get("queueId");
            if (id == null || id.isBlank() || id.length() > 128
                    || Long.parseLong(values.get("queueGeneration")) < 1
                    || Integer.parseInt(values.get("queueStep")) < -1
                    || Integer.parseInt(values.get("queueAttempt")) < 1)
                throw new IllegalArgumentException("Invalid queue correlation");
        } catch (NullPointerException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Incomplete or invalid queue correlation", invalid);
        }
    }

    private static String defaultReason(Mission mission) {
        if (!mission.lastError().isBlank()) return mission.lastError();
        if (!mission.pauseReason().isBlank()) return mission.pauseReason();
        return mission.phase();
    }
}
