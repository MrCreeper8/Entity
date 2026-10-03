package dev.entity.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.entity.core.stewardship.ProtectedAreaHomePermit;
import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.util.ArrayList;
import java.util.List;

/** Strict client decoder and acknowledgement encoder for Paper area policy. */
public final class ProtectedAreaPolicyProtocol {
    public static final String FEATURE = "typed_area_policy_v2";
    public static final String POLICY_TYPE = "protected_area_policy";
    public static final String ACK_TYPE = "protected_area_policy_ack";
    public static final String HOME_PERMIT_FEATURE = "protected_area_exact_permit_v2";
    public static final String HOME_PERMIT_REQUEST_TYPE =
            "protected_home_permit_request";
    public static final String HOME_PERMIT_RESULT_TYPE =
            "protected_home_permit_result";

    private ProtectedAreaPolicyProtocol() {
    }

    public static Update decode(JsonObject frame) {
        if (!POLICY_TYPE.equals(text(frame, "type"))) {
            throw new IllegalArgumentException("expected protected-area policy frame");
        }
        int schema = integer(frame, "schemaVersion");
        long revision = longValue(frame, "revision");
        String digest = text(frame, "digest");
        boolean serverFailClosed = bool(frame, "serverFailClosed");
        JsonElement encodedAreas = frame.get("areas");
        if (encodedAreas == null || !encodedAreas.isJsonArray()) {
            throw new IllegalArgumentException("protected-area policy areas must be an array");
        }
        List<ProtectedAreaPolicy.Area> areas = new ArrayList<>();
        for (JsonElement element : encodedAreas.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("protected area must be an object");
            }
            JsonObject area = element.getAsJsonObject();
            areas.add(decodeArea(schema, area));
        }
        ProtectedAreaPolicy.Snapshot snapshot = schema == ProtectedAreaPolicy.LEGACY_SCHEMA_VERSION
                ? ProtectedAreaPolicy.migrateLegacySnapshot(revision, digest, areas)
                : new ProtectedAreaPolicy.Snapshot(schema, revision, digest, areas);
        return new Update(
                snapshot, serverFailClosed);
    }

    public static JsonObject acknowledgement(ProtectedAreaPolicy.Snapshot snapshot) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", ACK_TYPE);
        frame.addProperty("revision", snapshot.revision());
        frame.addProperty("digest", snapshot.digest());
        frame.addProperty("timestamp", System.currentTimeMillis());
        return frame;
    }

    public static JsonObject homePermitRequest(ProtectedAreaHomePermit.Request request) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", HOME_PERMIT_REQUEST_TYPE);
        frame.addProperty("permitId", request.permitId());
        frame.addProperty("operationId", request.operationId());
        frame.addProperty("authorityId", request.authorityId());
        frame.addProperty("purpose", request.purpose().name().toLowerCase());
        frame.addProperty("action", request.action().name().toLowerCase());
        frame.addProperty("revision", request.revision());
        frame.addProperty("digest", request.digest());
        JsonArray targets = new JsonArray();
        for (ProtectedAreaHomePermit.Target target : request.targets()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("dimension", target.dimension());
            encoded.addProperty("x", target.x());
            encoded.addProperty("y", target.y());
            encoded.addProperty("z", target.z());
            targets.add(encoded);
        }
        frame.add("targets", targets);
        frame.addProperty("timestamp", System.currentTimeMillis());
        return frame;
    }

    public static ProtectedAreaHomePermit.Result homePermitResult(JsonObject frame) {
        if (!HOME_PERMIT_RESULT_TYPE.equals(text(frame, "type"))) {
            throw new IllegalArgumentException("expected protected-area Home permit result");
        }
        return new ProtectedAreaHomePermit.Result(
                text(frame, "permitId"),
                bool(frame, "accepted"),
                longValue(frame, "revision"),
                text(frame, "digest"),
                longValue(frame, "expiresAtMillis"),
                text(frame, "code"),
                text(frame, "detail"));
    }

    public static JsonObject encodeForStore(Update update) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", POLICY_TYPE);
        frame.addProperty("schemaVersion", update.snapshot().schemaVersion());
        frame.addProperty("revision", update.snapshot().revision());
        frame.addProperty("digest", update.snapshot().digest());
        frame.addProperty("serverFailClosed", update.serverFailClosed());
        JsonArray areas = new JsonArray();
        for (ProtectedAreaPolicy.Area area : update.snapshot().areas()) {
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
            throw new IllegalArgumentException(field + " must be a non-negative integer", error);
        }
    }

    private static boolean bool(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.getAsBoolean();
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return value.getAsString();
    }

    private static ProtectedAreaPolicy.Area decodeArea(int schema, JsonObject area) {
        String name = text(area, "name");
        String dimension = text(area, "dimension");
        int minX = integer(area, "minX");
        int maxX = integer(area, "maxX");
        int minZ = integer(area, "minZ");
        int maxZ = integer(area, "maxZ");
        if (schema == ProtectedAreaPolicy.LEGACY_SCHEMA_VERSION) {
            return new ProtectedAreaPolicy.Area(
                    name, dimension, minX, maxX, minZ, maxZ);
        }
        ProtectedAreaPolicy.AreaKind kind = ProtectedAreaPolicy.AreaKind.parse(
                text(area, "kind"));
        if (kind == ProtectedAreaPolicy.AreaKind.MINING) {
            return new ProtectedAreaPolicy.Area(
                    name, kind, dimension,
                    minX, maxX,
                    integer(area, "minY"), integer(area, "maxY"),
                    minZ, maxZ,
                    integer(area, "entranceX"), integer(area, "entranceY"),
                    integer(area, "entranceZ"));
        }
        return new ProtectedAreaPolicy.Area(
                name, kind, dimension,
                minX, maxX, 0, 0, minZ, maxZ, 0, 0, 0);
    }

    public record Update(
            ProtectedAreaPolicy.Snapshot snapshot,
            boolean serverFailClosed) {
        public Update {
            if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        }
    }
}
