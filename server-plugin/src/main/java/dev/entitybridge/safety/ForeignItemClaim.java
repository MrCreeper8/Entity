package dev.entitybridge.safety;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Authenticated Paper-side provenance attached to one live dropped item entity. */
public record ForeignItemClaim(
        UUID entityId,
        UUID worldId,
        String dimension,
        UUID ownerId,
        String ownerName,
        String itemId,
        long claimedAtMillis) {
    private static final Pattern RESOURCE_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public ForeignItemClaim {
        entityId = Objects.requireNonNull(entityId, "entityId");
        worldId = Objects.requireNonNull(worldId, "worldId");
        dimension = checkedResourceId(dimension, "dimension");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        ownerName = Objects.requireNonNullElse(ownerName, "").trim();
        if (!PLAYER_NAME.matcher(ownerName).matches()) {
            throw new IllegalArgumentException("invalid foreign-item owner name");
        }
        itemId = checkedResourceId(itemId, "itemId");
        if (claimedAtMillis < 0L) {
            throw new IllegalArgumentException("negative foreign-item claim time");
        }
    }

    public Key key() {
        return new Key(worldId, entityId);
    }

    private static String checkedResourceId(String value, String field) {
        String checked = Objects.requireNonNullElse(value, "").trim();
        if (!RESOURCE_ID.matcher(checked).matches()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return checked;
    }

    public record Key(UUID worldId, UUID entityId) {
        public Key {
            worldId = Objects.requireNonNull(worldId, "worldId");
            entityId = Objects.requireNonNull(entityId, "entityId");
        }
    }
}
