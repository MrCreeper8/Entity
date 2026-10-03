package dev.entitybridge.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Objects;

/** Authenticated server-to-client owner/controller relationship snapshot. */
public final class EntityRelationshipProtocol {
    public static final String FEATURE = "entity_relationship_policy_v1";
    public static final String POLICY_TYPE = "entity_relationship_policy";

    private EntityRelationshipProtocol() {
    }

    public static JsonObject policyFrame(TrustedIdentityRegistry.Snapshot snapshot) {
        TrustedIdentityRegistry.Snapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        JsonObject frame = new JsonObject();
        frame.addProperty("type", POLICY_TYPE);
        frame.addProperty("revision", checked.revision());
        frame.addProperty("owner", checked.ownerName());
        frame.addProperty("controllersLocked", checked.controllersLocked());
        JsonArray protectedNames = new JsonArray();
        checked.protectedPlayerNames().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(protectedNames::add);
        frame.add("protectedPlayerNames", protectedNames);
        frame.addProperty("timestamp", System.currentTimeMillis());
        return frame;
    }
}
