package dev.entitybridge.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Tolerant union of v1 telemetry and Entity 2's richer status. Unknown fields are
 * retained in raw so protocol additions never make the Paper bridge brittle.
 */
public record TelemetrySnapshot(
        long receivedAt,
        String state,
        Position position,
        Double health,
        Integer food,
        String target,
        String task,
        String message,
        MissionInfo mission,
        SurvivalInfo survival,
        BaritoneInfo baritone,
        Integer air,
        Boolean shield,
        JsonObject raw
) {
    private static final int MAX_TEXT_LENGTH = 512;

    public TelemetrySnapshot(
            long receivedAt,
            String state,
            Position position,
            Double health,
            Integer food,
            String target,
            String task,
            String message
    ) {
        this(receivedAt, state, position, health, food, target, task, message,
                null, null, null, null, null, new JsonObject());
    }

    public TelemetrySnapshot {
        raw = raw == null ? new JsonObject() : raw.deepCopy();
    }

    public static TelemetrySnapshot parse(JsonObject frame) throws ProtocolException {
        try {
            Double health = decimal(frame, "health");
            Integer food = integer(frame, "food");
            MissionInfo mission = mission(frame);
            SurvivalInfo survival = survival(frame, health, food);
            Integer air = firstInteger(frame, "air", "airSupply", "oxygen");
            if (air == null && survival != null) {
                air = survival.air();
            }
            Boolean shield = firstBoolean(frame, "shield", "shieldRaised", "blocking");
            if (shield == null && survival != null) {
                shield = survival.shield();
            }
            return new TelemetrySnapshot(
                    System.currentTimeMillis(),
                    text(frame, "state", "unknown"),
                    position(frame.get("position")),
                    health,
                    food,
                    text(frame, "target", null),
                    text(frame, "task", mission == null ? null : mission.action()),
                    text(frame, "message", null),
                    mission,
                    survival,
                    baritone(frame),
                    air,
                    shield,
                    frame
            );
        } catch (ProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProtocolException("Invalid telemetry payload", exception);
        }
    }

    private static MissionInfo mission(JsonObject frame) {
        JsonObject nested = object(frame.get("mission"));
        if (nested == null && frame.get("mission") != null && frame.get("mission").isJsonPrimitive()) {
            nested = new JsonObject();
            nested.addProperty("objective", safeText(frame.get("mission"), null));
        }
        String id = firstText(nested, "id", "missionId", "mission_id");
        if (id == null) {
            id = firstText(frame, "missionId", "mission_id");
        }
        Long sequence = firstLong(nested, "sequence", "seq");
        if (sequence == null) {
            sequence = firstLong(frame, "missionSequence", "mission_sequence", "sequence");
        }
        String status = firstText(nested, "status", "state");
        if (status == null) {
            status = firstText(frame, "missionStatus", "mission_status");
        }
        String action = firstText(nested, "action", "task", "kind");
        if (action == null) {
            action = text(frame, "task", null);
        }
        String objective = firstText(nested, "objective", "goal", "description");
        String step = firstText(nested, "step", "currentStep", "current_step", "phase");
        String reason = firstText(nested, "reason", "why", "message", "failure");
        Integer attempt = firstInteger(nested, "attempt", "retry", "retryCount");
        if (nested == null && id == null && sequence == null && status == null
                && action == null && objective == null && step == null && reason == null) {
            return null;
        }
        return new MissionInfo(id, sequence, status, action, objective, step, reason, attempt,
                nested == null ? new JsonObject() : nested);
    }

    private static SurvivalInfo survival(JsonObject frame, Double health, Integer food) {
        JsonObject nested = object(frame.get("survival"));
        String state = firstText(nested, "state", "status");
        String hazard = firstText(nested, "hazard", "danger", "threat");
        String action = firstText(nested, "action", "response", "reflex");
        String reason = firstText(nested, "reason", "why", "message");
        Boolean emergency = firstBoolean(nested, "emergency", "active", "overriding");
        Double nestedHealth = firstDecimal(nested, "health");
        Integer nestedFood = firstInteger(nested, "food", "hunger");
        Integer air = firstInteger(nested, "air", "airSupply", "oxygen");
        Boolean shield = firstBoolean(nested, "shield", "shieldRaised", "blocking");
        if (nested == null && state == null && hazard == null && action == null && reason == null
                && emergency == null && air == null && shield == null) {
            return null;
        }
        return new SurvivalInfo(
                state, hazard, action, reason, emergency,
                nestedHealth == null ? health : nestedHealth,
                nestedFood == null ? food : nestedFood,
                air, shield,
                nested == null ? new JsonObject() : nested
        );
    }

    private static BaritoneInfo baritone(JsonObject frame) {
        JsonElement value = frame.get("baritone");
        JsonObject nested = object(value);
        String state = firstText(nested, "state", "status");
        if (state == null && value != null && value.isJsonPrimitive()) {
            state = safeText(value, null);
        }
        String goal = firstText(nested, "goal", "target", "objective");
        String process = firstText(nested, "process", "processType", "behavior", "operation");
        String pathStatus = firstText(nested, "pathStatus", "path_status", "pathEvent", "path_event", "pathing", "path");
        String failure = firstText(nested, "failure", "error", "reason");
        Long nodes = firstLong(nested, "nodes", "nodesExpanded", "nodes_expanded");
        Double progress = firstDecimal(nested, "progress", "completion");
        if (nested == null && state == null && goal == null && process == null
                && pathStatus == null && failure == null && nodes == null && progress == null) {
            return null;
        }
        return new BaritoneInfo(
                state, goal, process, pathStatus, failure, nodes, progress,
                nested == null ? new JsonObject() : nested
        );
    }

    private static Position position(JsonElement value) throws ProtocolException {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw new ProtocolException("telemetry.position must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        return new Position(
                requiredFiniteDouble(object, "x"),
                requiredFiniteDouble(object, "y"),
                requiredFiniteDouble(object, "z"),
                text(object, "dimension", "unknown")
        );
    }

    private static double requiredFiniteDouble(JsonObject object, String key) throws ProtocolException {
        Double result = decimal(object, key);
        if (result == null) {
            throw new ProtocolException("telemetry.position." + key + " must be a finite number");
        }
        return result;
    }

    private static Double decimal(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            double result = value.getAsDouble();
            return Double.isFinite(result) ? result : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Integer integer(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Long longNumber(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Boolean bool(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsInt() != 0;
        }
        String text = primitive.getAsString().trim().toLowerCase();
        return switch (text) {
            case "true", "yes", "on", "up", "raised", "blocking", "active" -> true;
            case "false", "no", "off", "down", "lowered", "inactive" -> false;
            default -> null;
        };
    }

    private static String text(JsonObject object, String key, String fallback) {
        if (object == null) {
            return fallback;
        }
        return safeText(object.get(key), fallback);
    }

    private static String safeText(JsonElement value, String fallback) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            String result = value.getAsString();
            return result.length() > MAX_TEXT_LENGTH ? result.substring(0, MAX_TEXT_LENGTH) : result;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String firstText(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = text(object, key, null);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer firstInteger(JsonObject object, String... keys) {
        for (String key : keys) {
            Integer value = integer(object, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long firstLong(JsonObject object, String... keys) {
        for (String key : keys) {
            Long value = longNumber(object, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double firstDecimal(JsonObject object, String... keys) {
        for (String key : keys) {
            Double value = decimal(object, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Boolean firstBoolean(JsonObject object, String... keys) {
        for (String key : keys) {
            Boolean value = bool(object, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public record Position(double x, double y, double z, String dimension) {
    }

    public record MissionInfo(
            String id,
            Long sequence,
            String status,
            String action,
            String objective,
            String step,
            String reason,
            Integer attempt,
            JsonObject raw
    ) {
        public MissionInfo {
            raw = raw == null ? new JsonObject() : raw.deepCopy();
        }
    }

    public record SurvivalInfo(
            String state,
            String hazard,
            String action,
            String reason,
            Boolean emergency,
            Double health,
            Integer food,
            Integer air,
            Boolean shield,
            JsonObject raw
    ) {
        public SurvivalInfo {
            raw = raw == null ? new JsonObject() : raw.deepCopy();
        }
    }

    public record BaritoneInfo(
            String state,
            String goal,
            String process,
            String pathStatus,
            String failure,
            Long nodes,
            Double progress,
            JsonObject raw
    ) {
        public BaritoneInfo {
            raw = raw == null ? new JsonObject() : raw.deepCopy();
        }
    }
}
