package dev.entitybridge.bridge;

import com.google.gson.JsonObject;
import dev.entitybridge.mission.Mission;

import java.util.Objects;
import java.util.UUID;

/**
 * Wire command retaining every v1 field. Protocol-2 mission metadata is additive,
 * so a legacy controller can safely ignore it.
 */
public record BridgeCommand(
        String id,
        String action,
        JsonObject args,
        String requestedBy,
        long timestamp,
        String missionId,
        Long sequence,
        String operation,
        boolean durable
) {
    public BridgeCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(requestedBy, "requestedBy");
        args = args == null ? new JsonObject() : args.deepCopy();
    }

    /** Source-compatible legacy constructor. */
    public BridgeCommand(String id, String action, JsonObject args, String requestedBy, long timestamp) {
        this(id, action, args, requestedBy, timestamp, null, null, null, false);
    }

    public static BridgeCommand create(String action, JsonObject args, String requestedBy) {
        return new BridgeCommand(
                UUID.randomUUID().toString(), action, args, requestedBy,
                System.currentTimeMillis(), null, null, null, false
        );
    }

    public static BridgeCommand operation(
            String action,
            String operation,
            JsonObject payload,
            String requestedBy
    ) {
        return new BridgeCommand(
                UUID.randomUUID().toString(), action, payload, requestedBy,
                System.currentTimeMillis(), null, null, operation, false
        );
    }

    public static BridgeCommand start(Mission mission) {
        return new BridgeCommand(
                mission.commandId(),
                mission.action(),
                mission.args(),
                mission.requestedBy(),
                System.currentTimeMillis(),
                mission.id(),
                mission.sequence(),
                "mission.submit",
                true
        );
    }

    public static BridgeCommand lifecycle(Mission mission, String operation, String requestedBy) {
        JsonObject args = new JsonObject();
        if (operation.equals("cancel")) args.addProperty("missionScoped", true);
        args.addProperty("missionId", mission.id());
        args.addProperty("sequence", mission.sequence());
        args.addProperty("attempt", mission.attempt());
        for (String key : java.util.List.of("queueId", "queueGeneration", "queueStep", "queueAttempt")) {
            if (mission.args().has(key)) args.add(key, mission.args().get(key).deepCopy());
        }
        return new BridgeCommand(
                UUID.randomUUID().toString(),
                operation,
                args,
                requestedBy,
                System.currentTimeMillis(),
                mission.id(),
                mission.sequence(),
                "mission." + operation,
                true
        );
    }

    /**
     * Global owner stop.  When a durable mission exists it is carried as the
     * cancellation subject, while the wire action remains {@code stop} so the
     * pending queue also discards unrelated transient/background work.
     */
    public static BridgeCommand stop(Mission mission, String requestedBy) {
        JsonObject args = new JsonObject();
        if (mission != null) {
            args.addProperty("missionId", mission.id());
            args.addProperty("sequence", mission.sequence());
            args.addProperty("attempt", mission.attempt());
        }
        return new BridgeCommand(
                UUID.randomUUID().toString(),
                "stop",
                args,
                requestedBy,
                System.currentTimeMillis(),
                mission == null ? null : mission.id(),
                mission == null ? null : mission.sequence(),
                mission == null ? null : "mission.cancel",
                mission != null
        );
    }

    /** Body handoff for a new direct command is not the owner's persistent idle OFF preference. */
    public static BridgeCommand handoffStop(String requestedBy) {
        return handoffStop(null, requestedBy);
    }

    /** Retires one exact mission while retaining the owner's standing Idle preference. */
    public static BridgeCommand handoffStop(Mission mission, String requestedBy) {
        BridgeCommand command = stop(mission, requestedBy);
        command.args().addProperty("commandHandoff", true);
        return command;
    }

    public JsonObject toFrame() {
        return toFrame(2);
    }

    public JsonObject toFrame(int protocol) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "command");
        frame.addProperty("id", id);
        String legacyAction = switch (action) {
            case "get" -> "fetch";
            case "cancel" -> "stop";
            case "retry" -> "resume";
            default -> action;
        };
        frame.addProperty("action", protocol <= 1 ? legacyAction : action);
        frame.add("args", args.deepCopy());
        frame.addProperty("requestedBy", requestedBy);
        frame.addProperty("timestamp", timestamp);
        frame.addProperty("issuedAt", timestamp);
        if (protocol >= 2 && operation != null) {
            frame.addProperty("operation", operation);
            JsonObject payload = args.deepCopy();
            if (operation.equals("mission.submit")) {
                payload.addProperty("kind", action);
                if (payload.has("query") && !payload.has("resource")) {
                    payload.addProperty("resource", payload.get("query").getAsString());
                }
            }
            if (missionId != null) {
                payload.addProperty("missionId", missionId);
                payload.addProperty("sequence", sequence);
            }
            frame.add("payload", payload);
        }
        if (protocol >= 2 && missionId != null) {
            frame.addProperty("protocol", 2);
            frame.addProperty("durable", durable);
            frame.addProperty("missionId", missionId);
            frame.addProperty("sequence", sequence);

            JsonObject mission = new JsonObject();
            mission.addProperty("id", missionId);
            mission.addProperty("sequence", sequence);
            mission.addProperty("operation", operation);
            mission.addProperty("action", action);
            frame.add("mission", mission);
        }
        return frame;
    }
}
