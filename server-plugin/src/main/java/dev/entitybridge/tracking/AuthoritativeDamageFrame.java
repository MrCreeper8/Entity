package dev.entitybridge.tracking;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure, deterministic wire encoder for Paper-authoritative damage facts. */
public final class AuthoritativeDamageFrame {
    private AuthoritativeDamageFrame() {
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

    /** Classifies only Entity and the configured primary owner; all other players are ignored. */
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
            uuid = Objects.requireNonNull(uuid, "uuid");
            name = requiredText(name, "target name");
        }
    }

    public record AttackerIdentity(
            UUID uuid,
            String type,
            String name,
            double distance,
            boolean projectile,
            String projectileType
    ) {
        public AttackerIdentity {
            uuid = Objects.requireNonNull(uuid, "uuid");
            type = requiredText(type, "attacker type");
            name = requiredText(name, "attacker name");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("attacker distance must be finite and non-negative");
            }
            projectileType = projectileType == null ? "" : projectileType.trim();
            if (projectile && projectileType.isEmpty()) {
                throw new IllegalArgumentException("projectile type is required for projectile damage");
            }
        }
    }

    public static JsonObject encode(
            UUID eventId,
            TargetIdentity target,
            String cause,
            double damage,
            double healthBefore,
            long timestamp,
            AttackerIdentity attacker
    ) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(target, "target");
        String normalizedCause = requiredText(cause, "cause").toLowerCase(Locale.ROOT);
        if (!Double.isFinite(damage) || !Double.isFinite(healthBefore)) {
            throw new IllegalArgumentException("damage and health must be finite");
        }

        JsonObject frame = new JsonObject();
        frame.addProperty("type", "server_damage");
        frame.addProperty("eventId", eventId.toString());
        frame.addProperty("target", target.target().wireName());
        frame.addProperty("targetUuid", target.uuid().toString());
        frame.addProperty("targetName", target.name());
        frame.addProperty("cause", normalizedCause);
        frame.addProperty("damage", Math.max(0.0, damage));
        frame.addProperty("healthBefore", Math.max(0.0, healthBefore));
        frame.addProperty("timestamp", timestamp);

        if (attacker != null) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("uuid", attacker.uuid().toString());
            encoded.addProperty("type", attacker.type());
            encoded.addProperty("name", attacker.name());
            encoded.addProperty("distance", attacker.distance());
            encoded.addProperty("projectile", attacker.projectile());
            if (attacker.projectile()) {
                encoded.addProperty("projectileType", attacker.projectileType());
            }
            frame.add("attacker", encoded);
        }
        return frame;
    }

    private static String requiredText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return normalized;
    }
}
