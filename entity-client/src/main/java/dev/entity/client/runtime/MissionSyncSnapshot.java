package dev.entity.client.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.entity.client.command.IncomingCommand;
import dev.entity.core.EntityCore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validated, dependency-light view of Paper's protocol-2 durable mission sync. */
public record MissionSyncSnapshot(String currentMissionId, List<RemoteMission> missions) {
    public MissionSyncSnapshot {
        currentMissionId = currentMissionId == null ? "" : currentMissionId;
        missions = List.copyOf(missions);
    }

    public static MissionSyncSnapshot parse(JsonObject frame) {
        if (!"mission_sync".equals(text(frame, "type", "")) || integer(frame, "protocol", 0) != 2) {
            throw new IllegalArgumentException("Expected protocol-2 mission_sync");
        }
        JsonElement rawMissions = frame.get("missions");
        if (rawMissions == null || !rawMissions.isJsonArray()) {
            throw new IllegalArgumentException("mission_sync.missions must be an array");
        }
        Set<String> ids = new HashSet<>();
        Set<String> commandIds = new HashSet<>();
        Set<Long> sequences = new HashSet<>();
        ArrayList<RemoteMission> missions = new ArrayList<>();
        rawMissions.getAsJsonArray().forEach(element -> {
            if (!element.isJsonObject()) throw new IllegalArgumentException("synced mission must be an object");
            JsonObject value = element.getAsJsonObject();
            String id = required(value, "id");
            String commandId = required(value, "commandId");
            long sequence = positiveLong(value, "sequence");
            String action = required(value, "action");
            String requestedBy = required(value, "requestedBy");
            Status status = Status.parse(required(value, "status"));
            if (!id.equals(EntityCore.missionIdForCommand(commandId))) {
                throw new IllegalArgumentException("synced mission id does not match command id");
            }
            if (!ids.add(id) || !commandIds.add(commandId) || !sequences.add(sequence)) {
                throw new IllegalArgumentException("mission_sync contains duplicate identity or sequence");
            }
            JsonElement args = value.get("args");
            if (args != null && !args.isJsonObject()) throw new IllegalArgumentException("synced args must be an object");
            missions.add(new RemoteMission(
                    id, commandId, sequence, action,
                    args == null ? new JsonObject() : args.getAsJsonObject().deepCopy(),
                    requestedBy, status,
                    text(value, "step", ""), text(value, "reason", ""),
                    longValue(value, "createdAt", System.currentTimeMillis())));
        });
        missions.sort(Comparator.comparingLong(RemoteMission::sequence));
        String current = text(frame, "currentMissionId", "");
        if (!current.isBlank() && missions.stream().noneMatch(mission -> mission.id().equals(current))) {
            throw new IllegalArgumentException("currentMissionId is not present in mission_sync.missions");
        }
        return new MissionSyncSnapshot(current, missions);
    }

    public record RemoteMission(
            String id, String commandId, long sequence, String action, JsonObject args,
            String requestedBy, Status status, String step, String reason, long createdAt) {
        public IncomingCommand command() {
            JsonObject payload = args.deepCopy();
            payload.addProperty("missionId", id);
            payload.addProperty("sequence", sequence);
            return new IncomingCommand(commandId, action, payload, requestedBy, createdAt, 2);
        }

        /** Queue restart parking must precede a client's automatic recovery. */
        public boolean queuePauseOwnsRecovery(Map<String, String> localParameters) {
            return status == Status.PAUSED
                    && ("queue_paused".equals(step) || localParameters.containsKey("queueId"));
        }
    }

    public enum Status {
        QUEUED, ACTIVE, PAUSED, BLOCKED, RETRYING;

        static Status parse(String raw) {
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("unsupported synced mission status: " + raw);
            }
        }
    }

    private static String required(JsonObject object, String field) {
        String value = text(object, field, "").trim();
        if (value.isBlank()) throw new IllegalArgumentException("synced mission." + field + " is required");
        return value;
    }

    private static int integer(JsonObject object, String field, int fallback) {
        return (int) longValue(object, field, fallback);
    }

    private static long positiveLong(JsonObject object, String field) {
        long value = longValue(object, field, 0);
        if (value <= 0) throw new IllegalArgumentException("synced mission." + field + " must be positive");
        return value;
    }

    private static long longValue(JsonObject object, String field, long fallback) {
        try {
            return object.has(field) ? object.get(field).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String text(JsonObject object, String field, String fallback) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}
