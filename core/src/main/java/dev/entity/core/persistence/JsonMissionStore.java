package dev.entity.core.persistence;

import dev.entity.core.model.Mission;
import dev.entity.core.model.MissionState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Crash-safe JSON store: write, flush, then atomically replace the live file. */
public final class JsonMissionStore implements MissionStore {
    public static final int SCHEMA_VERSION = 1;

    private final Path path;
    private final Path temporaryPath;

    public JsonMissionStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    @Override
    public synchronized List<Mission> load() throws IOException {
        return AtomicStoreRecovery.load(
                path,
                temporaryPath,
                "mission store",
                List::of,
                JsonMissionStore::decodeStore);
    }

    private static AtomicStoreRecovery.Decoded<List<Mission>> decodeStore(
            byte[] bytes,
            Path source) throws IOException {
        try {
            Object parsed = SimpleJson.parse(
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            Map<String, Object> root = object(parsed, "root");
            int schemaVersion = number(root.get("schemaVersion"), "schemaVersion").intValue();
            if (schemaVersion != SCHEMA_VERSION) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "mission store", schemaVersion);
            }
            List<Object> encodedMissions = array(root.get("missions"), "missions");
            List<Mission> result = new ArrayList<>(encodedMissions.size());
            for (Object encoded : encodedMissions) {
                result.add(decodeMission(object(encoded, "mission")));
            }
            // Collection membership can shrink deliberately, so individual mission revisions are
            // not a valid store-generation counter. Atomic-file ordering remains authoritative.
            return new AtomicStoreRecovery.Decoded<>(List.copyOf(result), 0L, schemaVersion);
        } catch (AtomicStoreRecovery.UnsupportedSchemaException unsupported) {
            throw unsupported;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Corrupt mission store: " + source, exception);
        }
    }

    @Override
    public synchronized void save(Collection<Mission> missions) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<Map<String, Object>> encoded = missions.stream()
                .map(Mission::copy)
                .map(JsonMissionStore::encodeMission)
                .toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("missions", encoded);
        Files.writeString(
                temporaryPath,
                SimpleJson.stringify(root),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(
                    temporaryPath,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, Object> encodeMission(Mission mission) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("id", mission.id());
        encoded.put("commandId", mission.commandId());
        encoded.put("issuedBy", mission.issuedBy());
        encoded.put("kind", mission.kind());
        encoded.put("parameters", mission.parameters());
        encoded.put("state", mission.state().name());
        encoded.put("phase", mission.phase());
        encoded.put("pauseReason", mission.pauseReason());
        encoded.put("lastError", mission.lastError());
        encoded.put("createdAtMillis", mission.createdAtMillis());
        encoded.put("updatedAtMillis", mission.updatedAtMillis());
        encoded.put("nextRetryAtMillis", mission.nextRetryAtMillis());
        encoded.put("transientFailures", mission.transientFailures());
        encoded.put("interruptions", mission.interruptions());
        encoded.put("revision", mission.revision());
        return encoded;
    }

    private static Mission decodeMission(Map<String, Object> encoded) {
        Map<String, Object> rawParameters = object(encoded.get("parameters"), "parameters");
        Map<String, String> parameters = new LinkedHashMap<>();
        rawParameters.forEach((key, value) -> parameters.put(key, text(value, "parameter " + key)));
        return Mission.restore(
                text(encoded.get("id"), "id"),
                text(encoded.get("commandId"), "commandId"),
                text(encoded.get("issuedBy"), "issuedBy"),
                text(encoded.get("kind"), "kind"),
                parameters,
                MissionState.valueOf(text(encoded.get("state"), "state")),
                text(encoded.get("phase"), "phase"),
                text(encoded.get("pauseReason"), "pauseReason"),
                text(encoded.get("lastError"), "lastError"),
                number(encoded.get("createdAtMillis"), "createdAtMillis").longValue(),
                number(encoded.get("updatedAtMillis"), "updatedAtMillis").longValue(),
                number(encoded.get("nextRetryAtMillis"), "nextRetryAtMillis").longValue(),
                number(encoded.get("transientFailures"), "transientFailures").intValue(),
                optionalNumber(encoded.get("interruptions"), 0, "interruptions").intValue(),
                number(encoded.get("revision"), "revision").longValue());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(name + " contains a non-string key");
            }
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String name) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return (List<Object>) list;
    }

    private static String text(Object value, String name) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return text;
    }

    private static Number number(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be numeric");
        }
        return number;
    }

    private static Number optionalNumber(Object value, Number fallback, String name) {
        return value == null ? fallback : number(value, name);
    }
}
