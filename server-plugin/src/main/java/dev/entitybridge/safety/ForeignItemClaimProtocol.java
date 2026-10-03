package dev.entitybridge.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded full snapshots of Paper-authoritative live foreign item claims. */
public final class ForeignItemClaimProtocol {
    public static final String FEATURE = "foreign_item_claims_v1";
    public static final String SNAPSHOT_TYPE = "foreign_item_claims";
    public static final int SCHEMA = 1;
    public static final int MAXIMUM_LIVE_CLAIMS = 4_096;

    private ForeignItemClaimProtocol() {
    }

    public static JsonObject snapshotFrame(
            long revision,
            Collection<ForeignItemClaim> claims,
            boolean sourceComplete) {
        if (revision < 0L) throw new IllegalArgumentException("negative claim revision");
        Collection<ForeignItemClaim> checked = Objects.requireNonNull(claims, "claims");
        int totalClaimCount = checked.size();
        boolean complete = sourceComplete && totalClaimCount <= MAXIMUM_LIVE_CLAIMS;
        List<ForeignItemClaim> published = complete
                ? checked.stream().sorted(CLAIM_ORDER).toList()
                : List.of();

        JsonObject frame = new JsonObject();
        frame.addProperty("type", SNAPSHOT_TYPE);
        frame.addProperty("schema", SCHEMA);
        frame.addProperty("revision", revision);
        frame.addProperty("complete", complete);
        frame.addProperty("totalClaimCount", totalClaimCount);
        JsonArray encoded = new JsonArray();
        for (ForeignItemClaim claim : published) encoded.add(encode(claim));
        frame.add("claims", encoded);
        frame.addProperty("timestamp", System.currentTimeMillis());
        return frame;
    }

    private static JsonObject encode(ForeignItemClaim claim) {
        ForeignItemClaim checked = Objects.requireNonNull(claim, "claim");
        JsonObject encoded = new JsonObject();
        encoded.addProperty("entityUuid", checked.entityId().toString());
        encoded.addProperty("worldUuid", checked.worldId().toString());
        encoded.addProperty("dimension", checked.dimension());
        encoded.addProperty("ownerUuid", checked.ownerId().toString());
        encoded.addProperty("ownerName", checked.ownerName());
        encoded.addProperty("itemId", checked.itemId());
        encoded.addProperty("claimedAtMillis", checked.claimedAtMillis());
        return encoded;
    }

    private static final Comparator<ForeignItemClaim> CLAIM_ORDER = Comparator
            .comparing((ForeignItemClaim claim) -> claim.worldId().toString())
            .thenComparing(claim -> claim.entityId().toString());
}
