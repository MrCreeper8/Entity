package dev.entity.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict decoder for Paper's full snapshot of currently loaded foreign items. */
public final class ForeignItemClaimProtocol {
    public static final String FEATURE = "foreign_item_claims_v1";
    public static final String SNAPSHOT_TYPE = "foreign_item_claims";
    public static final int SCHEMA = 1;
    public static final int MAXIMUM_LIVE_CLAIMS = 4_096;
    private static final int MAXIMUM_DECLARED_CLAIMS = 1_000_000;
    private static final Pattern RESOURCE_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private ForeignItemClaimProtocol() {
    }

    public static Snapshot decode(JsonObject frame) {
        Objects.requireNonNull(frame, "frame");
        if (!SNAPSHOT_TYPE.equals(text(frame, "type"))) {
            throw new IllegalArgumentException("expected foreign-item claim snapshot");
        }
        if (exactInt(frame, "schema") != SCHEMA) {
            throw new IllegalArgumentException("unsupported foreign-item claim schema");
        }
        long revision = exactLong(frame, "revision");
        if (revision < 0L) throw new IllegalArgumentException("negative claim revision");
        boolean complete = exactBoolean(frame, "complete");
        int totalClaimCount = exactInt(frame, "totalClaimCount");
        if (totalClaimCount < 0 || totalClaimCount > MAXIMUM_DECLARED_CLAIMS) {
            throw new IllegalArgumentException("invalid total claim count");
        }
        JsonElement claimsElement = frame.get("claims");
        if (claimsElement == null || !claimsElement.isJsonArray()) {
            throw new IllegalArgumentException("claims must be an array");
        }
        JsonArray encodedClaims = claimsElement.getAsJsonArray();
        if (encodedClaims.size() > MAXIMUM_LIVE_CLAIMS) {
            throw new IllegalArgumentException("too many published foreign-item claims");
        }
        if (!complete && !encodedClaims.isEmpty()) {
            throw new IllegalArgumentException("incomplete claim snapshot must publish no claims");
        }
        if (complete && totalClaimCount != encodedClaims.size()) {
            throw new IllegalArgumentException("complete claim count mismatch");
        }

        ArrayList<Claim> claims = new ArrayList<>();
        HashSet<UUID> entityIds = new HashSet<>();
        HashSet<Key> keys = new HashSet<>();
        for (JsonElement encoded : encodedClaims) {
            if (!encoded.isJsonObject()) {
                throw new IllegalArgumentException("foreign-item claim must be an object");
            }
            JsonObject value = encoded.getAsJsonObject();
            Claim claim = new Claim(
                    uuid(value, "entityUuid"),
                    uuid(value, "worldUuid"),
                    resourceId(value, "dimension"),
                    uuid(value, "ownerUuid"),
                    playerName(value, "ownerName"),
                    resourceId(value, "itemId"),
                    exactLong(value, "claimedAtMillis"));
            if (!entityIds.add(claim.entityId()) || !keys.add(claim.key())) {
                throw new IllegalArgumentException("duplicate foreign-item claim identity");
            }
            claims.add(claim);
        }
        return new Snapshot(revision, complete, totalClaimCount, List.copyOf(claims));
    }

    private static UUID uuid(JsonObject value, String field) {
        try {
            return UUID.fromString(text(value, field));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid " + field, invalid);
        }
    }

    private static String resourceId(JsonObject value, String field) {
        String id = text(value, field);
        if (!RESOURCE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return id;
    }

    private static String playerName(JsonObject value, String field) {
        String name = text(value, field);
        if (!PLAYER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return name;
    }

    private static String text(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing or non-text " + field);
        }
        return value.getAsString();
    }

    private static boolean exactBoolean(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("missing or non-boolean " + field);
        }
        return value.getAsBoolean();
    }

    private static int exactInt(JsonObject frame, String field) {
        long value = exactLong(frame, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("out-of-range " + field);
        }
        return (int) value;
    }

    private static long exactLong(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing or non-numeric " + field);
        }
        try {
            long parsed = value.getAsLong();
            if (value.getAsDouble() != parsed) {
                throw new IllegalArgumentException("fractional " + field);
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid " + field, invalid);
        }
    }

    public record Claim(
            UUID entityId,
            UUID worldId,
            String dimension,
            UUID ownerId,
            String ownerName,
            String itemId,
            long claimedAtMillis) {
        public Claim {
            entityId = Objects.requireNonNull(entityId, "entityId");
            worldId = Objects.requireNonNull(worldId, "worldId");
            dimension = Objects.requireNonNull(dimension, "dimension");
            ownerId = Objects.requireNonNull(ownerId, "ownerId");
            ownerName = Objects.requireNonNull(ownerName, "ownerName");
            itemId = Objects.requireNonNull(itemId, "itemId");
            if (claimedAtMillis < 0L) {
                throw new IllegalArgumentException("negative claim time");
            }
        }

        public Key key() {
            return new Key(worldId, entityId);
        }
    }

    public record Key(UUID worldId, UUID entityId) {
        public Key {
            worldId = Objects.requireNonNull(worldId, "worldId");
            entityId = Objects.requireNonNull(entityId, "entityId");
        }
    }

    public record Snapshot(
            long revision,
            boolean complete,
            int totalClaimCount,
            List<Claim> claims) {
        public Snapshot {
            if (revision < 0L || totalClaimCount < 0) {
                throw new IllegalArgumentException("invalid foreign-item snapshot counts");
            }
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        }
    }
}
