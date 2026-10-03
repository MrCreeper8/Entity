package dev.entitybridge.tracking;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure, deterministic wire model for Paper-authoritative hostile target facts. */
public final class AuthoritativeThreatTargetFrame {
    public static final int SCHEMA = 1;

    private AuthoritativeThreatTargetFrame() {
    }

    public enum Target {
        SELF("self"),
        OWNER("owner");

        private final String wireName;

        Target(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /** Entity is always self, even when an unusual configuration also names it as owner. */
    public static Optional<Target> classifyTarget(String playerName, String configuredOwnerName) {
        String player = playerName == null ? "" : playerName.trim();
        String owner = configuredOwnerName == null ? "" : configuredOwnerName.trim();
        if (player.equalsIgnoreCase("Entity")) return Optional.of(Target.SELF);
        if (!owner.isEmpty() && player.equalsIgnoreCase(owner)) return Optional.of(Target.OWNER);
        return Optional.empty();
    }

    public record TargetIdentity(Target target, UUID uuid, String name) {
        public TargetIdentity {
            target = Objects.requireNonNull(target, "target");
            uuid = Objects.requireNonNull(uuid, "target uuid");
            name = requiredText(name, "target name");
        }
    }

    public record AttackerIdentity(UUID uuid, String type, String name, double distance) {
        public AttackerIdentity {
            uuid = Objects.requireNonNull(uuid, "attacker uuid");
            type = requiredText(type, "attacker type");
            name = requiredText(name, "attacker name");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException(
                        "attacker distance must be finite and non-negative");
            }
        }
    }

    public static JsonObject encode(
            UUID eventId,
            boolean active,
            TargetIdentity target,
            long timestamp,
            AttackerIdentity attacker
    ) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attacker, "attacker");

        JsonObject frame = new JsonObject();
        frame.addProperty("type", "server_threat_target");
        frame.addProperty("schema", SCHEMA);
        frame.addProperty("eventId", eventId.toString());
        frame.addProperty("active", active);
        frame.addProperty("target", target.target().wireName());
        frame.addProperty("targetUuid", target.uuid().toString());
        frame.addProperty("targetName", target.name());
        frame.addProperty("timestamp", timestamp);

        JsonObject encodedAttacker = new JsonObject();
        encodedAttacker.addProperty("uuid", attacker.uuid().toString());
        encodedAttacker.addProperty("type", attacker.type());
        encodedAttacker.addProperty("name", attacker.name());
        encodedAttacker.addProperty("distance", attacker.distance());
        frame.add("attacker", encodedAttacker);
        return frame;
    }

    private static String requiredText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return normalized;
    }
}
