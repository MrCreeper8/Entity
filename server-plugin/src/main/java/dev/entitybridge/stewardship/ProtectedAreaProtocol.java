package dev.entitybridge.stewardship;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.entity.core.stewardship.ProtectedAreaHomePermit;
import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Wire representation of the canonical Paper-owned protected-area policy. */
public final class ProtectedAreaProtocol {
    public static final String FEATURE = "typed_area_policy_v2";
    public static final String POLICY_TYPE = "protected_area_policy";
    public static final String ACK_TYPE = "protected_area_policy_ack";
    public static final String HOME_PERMIT_FEATURE = "protected_area_exact_permit_v2";
    public static final String HOME_PERMIT_REQUEST_TYPE =
            "protected_home_permit_request";
    public static final String HOME_PERMIT_RESULT_TYPE =
            "protected_home_permit_result";

    private ProtectedAreaProtocol() {
    }

    public static JsonObject policyFrame(
            ProtectedAreaPolicy.Snapshot snapshot,
            boolean serverFailClosed) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", POLICY_TYPE);
        frame.addProperty("schemaVersion", snapshot.schemaVersion());
        frame.addProperty("revision", snapshot.revision());
        frame.addProperty("digest", snapshot.digest());
        frame.addProperty("serverFailClosed", serverFailClosed);
        JsonArray areas = new JsonArray();
        for (ProtectedAreaPolicy.Area area : snapshot.areas()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("name", area.name());
            encoded.addProperty("kind", area.kind().wireName());
            encoded.addProperty("dimension", area.dimension());
            encoded.addProperty("minX", area.minX());
            encoded.addProperty("maxX", area.maxX());
            encoded.addProperty("minZ", area.minZ());
            encoded.addProperty("maxZ", area.maxZ());
            if (area.kind() == ProtectedAreaPolicy.AreaKind.MINING) {
                encoded.addProperty("minY", area.minY());
                encoded.addProperty("maxY", area.maxY());
                encoded.addProperty("entranceX", area.entranceX());
                encoded.addProperty("entranceY", area.entranceY());
                encoded.addProperty("entranceZ", area.entranceZ());
            }
            areas.add(encoded);
        }
        frame.add("areas", areas);
        frame.addProperty("timestamp", System.currentTimeMillis());
        return frame;
    }

    public static Acknowledgement acknowledgement(JsonObject frame) {
        if (!ACK_TYPE.equals(text(frame, "type"))) {
            throw new IllegalArgumentException("expected protected-area policy acknowledgement");
        }
        JsonElement revision = frame.get("revision");
        if (revision == null || !revision.isJsonPrimitive()) {
            throw new IllegalArgumentException("protected-area acknowledgement revision is required");
        }
        long checkedRevision;
        try {
            checkedRevision = revision.getAsLong();
            if (checkedRevision < 0L || revision.getAsDouble() != checkedRevision) {
                throw new IllegalArgumentException(
                        "protected-area acknowledgement revision is invalid");
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "protected-area acknowledgement revision is invalid", error);
        }
        String digest = text(frame, "digest").trim().toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "protected-area acknowledgement digest is invalid");
        }
        return new Acknowledgement(checkedRevision, digest);
    }

    public static ProtectedAreaHomePermit.Request homePermitRequest(JsonObject frame) {
        if (!HOME_PERMIT_REQUEST_TYPE.equals(text(frame, "type"))) {
            throw new IllegalArgumentException("expected protected-area Home permit request");
        }
        JsonElement encodedTargets = frame.get("targets");
        if (encodedTargets == null || !encodedTargets.isJsonArray()) {
            throw new IllegalArgumentException("Home permit targets must be an array");
        }
        List<ProtectedAreaHomePermit.Target> targets = new ArrayList<>();
        for (JsonElement element : encodedTargets.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Home permit target must be an object");
            }
            JsonObject target = element.getAsJsonObject();
            targets.add(new ProtectedAreaHomePermit.Target(
                    text(target, "dimension"),
                    integer(target, "x"),
                    integer(target, "y"),
                    integer(target, "z")));
        }
        ProtectedAreaPolicy.Action action;
        ProtectedAreaHomePermit.Purpose purpose;
        try {
            purpose = ProtectedAreaHomePermit.Purpose.valueOf(
                    text(frame, "purpose").trim().toUpperCase(Locale.ROOT));
            action = ProtectedAreaPolicy.Action.valueOf(
                    text(frame, "action").trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("exact permit purpose/action is invalid", error);
        }
        return new ProtectedAreaHomePermit.Request(
                text(frame, "permitId"),
                text(frame, "operationId"),
                text(frame, "authorityId"),
                purpose,
                action,
                longValue(frame, "revision"),
                text(frame, "digest"),
                targets);
    }

    public static JsonObject homePermitResult(ProtectedAreaHomePermit.Result result) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", HOME_PERMIT_RESULT_TYPE);
        frame.addProperty("permitId", result.permitId());
        frame.addProperty("accepted", result.accepted());
        frame.addProperty("revision", result.revision());
        frame.addProperty("digest", result.digest());
        frame.addProperty("expiresAtMillis", result.expiresAtMillis());
        frame.addProperty("code", result.code());
        frame.addProperty("detail", result.detail());
        frame.addProperty("timestamp", System.currentTimeMillis());
        return frame;
    }

    private static int integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            int result = value.getAsInt();
            if (value.getAsDouble() != result) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
            return result;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(field + " must be an integer", error);
        }
    }

    private static long longValue(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            long result = value.getAsLong();
            if (result < 0L || value.getAsDouble() != result) {
                throw new IllegalArgumentException(field + " must be a non-negative integer");
            }
            return result;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    field + " must be a non-negative integer", error);
        }
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) return "";
        return value.getAsString();
    }

    public record Acknowledgement(long revision, String digest) {
    }
}
