package dev.entity.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.entity.core.build.BuildIdentity;

import java.math.BigDecimal;
import java.util.Objects;

/** Protocol fields and exact pair validation for the client/server build identity fence. */
final class BridgeBuildIdentity {
    static final String MISMATCH_CODE = "build_identity_mismatch";

    private BridgeBuildIdentity() { }

    static void addClientIdentity(JsonObject hello, BuildIdentity identity) {
        Objects.requireNonNull(hello, "hello");
        Objects.requireNonNull(identity, "identity");
        hello.addProperty("clientIdentitySchemaVersion", identity.schemaVersion());
        hello.addProperty("clientVersion", identity.version());
        hello.addProperty("clientSourceCommit", identity.sourceCommit());
        hello.addProperty("clientBuildId", identity.buildId());
        hello.addProperty("clientSourceState", identity.sourceState());
        hello.addProperty("clientGeneratedAtUtc", identity.generatedAt().toString());
    }

    static BuildIdentity requireServerIdentity(JsonObject acknowledgement) {
        Objects.requireNonNull(acknowledgement, "acknowledgement");
        return BuildIdentity.of(
                requiredInt(acknowledgement, "serverIdentitySchemaVersion"),
                requiredString(acknowledgement, "serverVersion"),
                requiredString(acknowledgement, "serverSourceCommit"),
                requiredString(acknowledgement, "serverBuildId"),
                requiredString(acknowledgement, "serverSourceState"),
                requiredString(acknowledgement, "serverGeneratedAtUtc"));
    }

    static BuildIdentity requireExactServerIdentity(
            JsonObject acknowledgement,
            BuildIdentity local) {
        BuildIdentity server = requireServerIdentity(acknowledgement);
        local.requireExact(server, "server");
        return server;
    }

    static boolean isMismatchError(JsonObject frame) {
        JsonElement code = frame == null ? null : frame.get("code");
        return code != null && code.isJsonPrimitive() && MISMATCH_CODE.equals(code.getAsString());
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("hello_ack is missing required " + field);
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("hello_ack is missing required " + field);
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new IllegalArgumentException("hello_ack has non-numeric " + field);
        }
        try {
            return new BigDecimal(primitive.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("hello_ack has invalid " + field, invalid);
        }
    }
}
