package dev.entity.client.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Set;

/** Normalized view of both the existing command envelope and protocol v2. */
public record IncomingCommand(
        String id,
        String action,
        JsonObject arguments,
        String requestedBy,
        long issuedAt,
        int protocol) {

    private static final Set<String> ACTIONS = Set.of(
            "goto", "follow", "come", "mine", "attack", "guard", "protect",
            "get", "give", "bring", "gear", "farm", "farm_policy", "build", "blueprint_policy", "plan", "inventory", "home", "stock", "tidy", "visuals", "perception", "idle",
            "stop", "cancel", "pause", "resume", "retry", "status", "why");

    public static IncomingCommand parse(JsonObject frame, int protocol) {
        if (!"command".equals(text(frame, "type", ""))) {
            throw new IllegalArgumentException("Expected command frame");
        }
        String id = text(frame, "id", "").trim();
        if (id.isEmpty() || id.length() > 128) {
            throw new IllegalArgumentException("command.id is missing or too long");
        }

        String operation = text(frame, "operation", "").trim().toLowerCase(Locale.ROOT);
        String action;
        JsonObject arguments;
        if (!operation.isEmpty()) {
            arguments = object(frame, "payload");
            action = switch (operation) {
                case "mission.submit" -> text(arguments, "kind", text(frame, "action", ""));
                case "mission.pause" -> "pause";
                case "mission.resume" -> "resume";
                case "mission.retry" -> "retry";
                case "mission.cancel" -> "stop".equals(text(frame, "action", "cancel"))
                        && !scopedCancellation(arguments) ? "stop" : "cancel";
                case "query.status" -> "status";
                case "query.why" -> "why";
                case "query.plan" -> "plan";
                case "query.inventory" -> "inventory";
                case "policy.set" -> "protect";
                case "policy.home" -> "home";
                case "policy.stock" -> "stock";
                case "policy.farm" -> "farm_policy";
                case "policy.blueprint" -> "blueprint_policy";
                case "policy.tidy" -> "tidy";
                case "policy.visuals" -> "visuals";
                case "policy.perception" -> "perception";
                case "policy.idle" -> "idle";
                default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
            };
        } else {
            action = text(frame, "action", "");
            arguments = object(frame, "args");
        }

        action = canonicalAction(action);
        if (action.equals("stop") && scopedCancellation(arguments)) action = "cancel";
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Unsupported action: " + action);
        String requestedBy = requester(frame.get("requestedBy"));
        long issuedAt = frame.has("issuedAt") ? frame.get("issuedAt").getAsLong()
                : frame.has("timestamp") ? frame.get("timestamp").getAsLong()
                : System.currentTimeMillis();
        return new IncomingCommand(id, action, arguments.deepCopy(), requestedBy, issuedAt, protocol);
    }

    private static String canonicalAction(String input) {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "go" -> "goto";
            case "recall" -> "come";
            case "kill", "hunt" -> "attack";
            case "defend" -> "guard";
            case "fetch", "acquire" -> "get";
            default -> value;
        };
    }

    private static boolean scopedCancellation(JsonObject args) {
        JsonElement value = args.get("missionScoped");
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static String requester(JsonElement value) {
        if (value == null || value.isJsonNull()) return "owner";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (value.isJsonObject()) return text(value.getAsJsonObject(), "name", "owner");
        return "owner";
    }

    private static JsonObject object(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String text(JsonObject object, String field, String fallback) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}
