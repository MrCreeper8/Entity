package dev.entity.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Strict DTO for Paper-authoritative self/owner damage frames. */
public record ServerDamageEvent(
        UUID eventId,
        Target target,
        UUID targetUuid,
        String targetName,
        String cause,
        double damage,
        double healthBefore,
        long timestamp,
        Optional<Attacker> attacker
) {
    public enum Target {
        SELF,
        OWNER
    }

    public record Attacker(
            UUID uuid,
            String type,
            String name,
            double distance,
            boolean projectile,
            String projectileType
    ) {
        public Attacker {
            uuid = Objects.requireNonNull(uuid, "uuid");
            type = required(type, "attacker.type");
            name = required(name, "attacker.name");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("attacker.distance must be finite and non-negative");
            }
            projectileType = Objects.requireNonNullElse(projectileType, "").trim();
            if (projectile && projectileType.isEmpty()) {
                throw new IllegalArgumentException("attacker.projectileType is required");
            }
        }
    }

    public ServerDamageEvent {
        eventId = Objects.requireNonNull(eventId, "eventId");
        target = Objects.requireNonNull(target, "target");
        targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
        targetName = required(targetName, "targetName");
        cause = required(cause, "cause").toLowerCase(Locale.ROOT);
        if (!Double.isFinite(damage) || damage < 0.0
                || !Double.isFinite(healthBefore) || healthBefore < 0.0) {
            throw new IllegalArgumentException("damage and healthBefore must be finite and non-negative");
        }
        attacker = Objects.requireNonNull(attacker, "attacker");
    }

    public static ServerDamageEvent parse(JsonObject frame) {
        Objects.requireNonNull(frame, "frame");
        if (!"server_damage".equals(text(frame, "type"))) {
            throw new IllegalArgumentException("not a server_damage frame");
        }
        Target target;
        try {
            target = Target.valueOf(required(text(frame, "target"), "target")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException malformedTarget) {
            throw new IllegalArgumentException("target must be self or owner", malformedTarget);
        }
        JsonObject encodedAttacker = object(frame, "attacker");
        Optional<Attacker> attacker = encodedAttacker == null
                ? Optional.empty()
                : Optional.of(new Attacker(
                        uuid(encodedAttacker, "uuid"),
                        text(encodedAttacker, "type"),
                        text(encodedAttacker, "name"),
                        number(encodedAttacker, "distance"),
                        bool(encodedAttacker, "projectile"),
                        text(encodedAttacker, "projectileType")
                ));
        return new ServerDamageEvent(
                uuid(frame, "eventId"),
                target,
                uuid(frame, "targetUuid"),
                text(frame, "targetName"),
                text(frame, "cause"),
                number(frame, "damage"),
                number(frame, "healthBefore"),
                integer(frame, "timestamp"),
                attacker
        );
    }

    private static JsonObject object(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonObject()) throw new IllegalArgumentException(field + " must be an object");
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
        return value.getAsDouble();
    }

    private static long integer(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return value.getAsLong();
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
