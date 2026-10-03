package dev.entity.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Strict DTO for Paper-authoritative hostile target acquisition and clear frames. */
public record ServerThreatTargetEvent(
        UUID eventId,
        boolean active,
        Target target,
        UUID targetUuid,
        String targetName,
        long timestamp,
        Attacker attacker
) {
    public static final int SCHEMA = 1;

    public enum Target {
        SELF,
        OWNER
    }

    public record Attacker(
            UUID uuid,
            String type,
            String name,
            double distance
    ) {
        public Attacker {
            uuid = Objects.requireNonNull(uuid, "uuid");
            type = required(type, "attacker.type");
            name = required(name, "attacker.name");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException(
                        "attacker.distance must be finite and non-negative");
            }
        }
    }

    public ServerThreatTargetEvent {
        eventId = Objects.requireNonNull(eventId, "eventId");
        target = Objects.requireNonNull(target, "target");
        targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
        targetName = required(targetName, "targetName");
        if (timestamp < 0L) throw new IllegalArgumentException("timestamp must be non-negative");
        attacker = Objects.requireNonNull(attacker, "attacker");
    }

    public static ServerThreatTargetEvent parse(JsonObject frame) {
        Objects.requireNonNull(frame, "frame");
        if (!"server_threat_target".equals(text(frame, "type"))) {
            throw new IllegalArgumentException("not a server_threat_target frame");
        }
        if (integer(frame, "schema") != SCHEMA) {
            throw new IllegalArgumentException("unsupported server_threat_target schema");
        }

        Target target;
        try {
            target = Target.valueOf(required(text(frame, "target"), "target")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException malformedTarget) {
            throw new IllegalArgumentException("target must be self or owner", malformedTarget);
        }

        JsonObject encodedAttacker = object(frame, "attacker");
        return new ServerThreatTargetEvent(
                uuid(frame, "eventId"),
                bool(frame, "active"),
                target,
                uuid(frame, "targetUuid"),
                text(frame, "targetName"),
                integer(frame, "timestamp"),
                new Attacker(
                        uuid(encodedAttacker, "uuid"),
                        text(encodedAttacker, "type"),
                        text(encodedAttacker, "name"),
                        number(encodedAttacker, "distance")));
    }

    private static JsonObject object(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static UUID uuid(JsonObject source, String field) {
        try {
            return UUID.fromString(required(text(source, field), field));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(field + " must be a UUID", malformed);
        }
    }

    private static String text(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) return "";
        return value.getAsString().trim();
    }

    private static double number(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        try {
            return value.getAsDouble();
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(field + " must be numeric", malformed);
        }
    }

    private static long integer(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            BigDecimal number = value.getAsBigDecimal().stripTrailingZeros();
            if (number.scale() > 0) throw new ArithmeticException("fractional value");
            return number.longValueExact();
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(field + " must be an integer", malformed);
        }
    }

    private static boolean bool(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.getAsBoolean();
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
