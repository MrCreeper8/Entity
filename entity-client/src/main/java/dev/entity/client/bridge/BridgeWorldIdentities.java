package dev.entity.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict parser for Paper-authoritative dimension identities negotiated at handshake. */
public final class BridgeWorldIdentities {
    public static final String FEATURE = "authoritative_world_identity";
    private static final int MAX_WORLDS = 64;
    private static final Pattern DIMENSION = Pattern.compile(
            "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,191}");

    private BridgeWorldIdentities() {
    }

    public static Map<String, String> parse(JsonObject acknowledgement, Set<String> features) {
        Objects.requireNonNull(acknowledgement, "acknowledgement");
        Objects.requireNonNull(features, "features");
        if (!features.contains(FEATURE)
                || !acknowledgement.has("worldIdentities")
                || !acknowledgement.get("worldIdentities").isJsonObject()) {
            return Map.of();
        }

        LinkedHashMap<String, String> accepted = new LinkedHashMap<>();
        for (var entry : acknowledgement.getAsJsonObject("worldIdentities").entrySet()) {
            if (accepted.size() >= MAX_WORLDS) break;
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive()) continue;
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (!primitive.isString()) continue;

            String dimension = entry.getKey().trim();
            String claimedIdentity = primitive.getAsString().trim();
            if (!DIMENSION.matcher(dimension).matches()) continue;
            try {
                String canonicalIdentity = UUID.fromString(claimedIdentity).toString();
                if (!canonicalIdentity.equalsIgnoreCase(claimedIdentity)) continue;
                accepted.put(dimension, canonicalIdentity);
            } catch (IllegalArgumentException ignored) {
                // A malformed server claim grants no durable route identity.
            }
        }
        return Map.copyOf(accepted);
    }
}
