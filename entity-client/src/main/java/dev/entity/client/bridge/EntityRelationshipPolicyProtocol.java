package dev.entity.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict decoder for Paper's authenticated owner/controller safety snapshot. */
public final class EntityRelationshipPolicyProtocol {
    public static final String FEATURE = "entity_relationship_policy_v1";
    public static final String POLICY_TYPE = "entity_relationship_policy";
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int MAXIMUM_PROTECTED_PLAYERS = 256;

    private EntityRelationshipPolicyProtocol() {
    }

    public static Snapshot decode(JsonObject frame) {
        Objects.requireNonNull(frame, "frame");
        if (!POLICY_TYPE.equals(text(frame, "type"))) {
            throw new IllegalArgumentException("expected relationship policy frame");
        }
        long revision = exactLong(frame, "revision");
        if (revision < 0L) throw new IllegalArgumentException("negative relationship revision");
        String owner = optionalPlayerName(frame, "owner");
        JsonElement namesElement = frame.get("protectedPlayerNames");
        if (namesElement == null || !namesElement.isJsonArray()) {
            throw new IllegalArgumentException("protectedPlayerNames must be an array");
        }
        JsonArray names = namesElement.getAsJsonArray();
        if (names.size() > MAXIMUM_PROTECTED_PLAYERS) {
            throw new IllegalArgumentException("too many protected player names");
        }
        LinkedHashSet<String> protectedNames = new LinkedHashSet<>();
        for (JsonElement element : names) {
            if (!element.isJsonPrimitive()) {
                throw new IllegalArgumentException("protected player name must be text");
            }
            String name = element.getAsString().trim();
            if (!PLAYER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid protected player name");
            }
            if (!protectedNames.add(name)) {
                throw new IllegalArgumentException("duplicate protected player name");
            }
        }
        if (!owner.isBlank() && protectedNames.stream().noneMatch(owner::equalsIgnoreCase)) {
            throw new IllegalArgumentException("configured owner is absent from protected names");
        }
        boolean locked = frame.has("controllersLocked")
                && frame.get("controllersLocked").isJsonPrimitive()
                && frame.get("controllersLocked").getAsBoolean();
        return new Snapshot(revision, owner, Set.copyOf(protectedNames), locked);
    }

    private static String text(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return value.getAsString();
    }

    private static String optionalPlayerName(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        if (value == null || !value.isJsonPrimitive()) return "";
        String name = value.getAsString().trim();
        if (!name.isEmpty() && !PLAYER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return name;
    }

    private static long exactLong(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + field);
        }
        try {
            long parsed = value.getAsLong();
            if (value.getAsDouble() != parsed) throw new IllegalArgumentException("fractional " + field);
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid " + field, invalid);
        }
    }

    public record Snapshot(
            long revision,
            String ownerName,
            Set<String> protectedPlayerNames,
            boolean controllersLocked) {
        public Snapshot {
            if (revision < 0L) throw new IllegalArgumentException("negative revision");
            ownerName = Objects.requireNonNullElse(ownerName, "");
            protectedPlayerNames = Set.copyOf(
                    Objects.requireNonNull(protectedPlayerNames, "protectedPlayerNames"));
        }
    }
}
