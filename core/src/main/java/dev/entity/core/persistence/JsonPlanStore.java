package dev.entity.core.persistence;

import dev.entity.core.plan.PlanFrame;
import dev.entity.core.plan.PlanFrameState;
import dev.entity.core.plan.TaskPlan;
import dev.entity.core.plan.TaskPlanState;

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

/** Crash-safe task-plan store: write and flush a temporary JSON file, then replace atomically. */
public final class JsonPlanStore implements PlanStore {
    public static final int SCHEMA_VERSION = 2;
    private static final int LEGACY_SCHEMA_VERSION = 1;

    private final Path path;
    private final Path temporaryPath;

    public JsonPlanStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    @Override
    public synchronized List<TaskPlan> load() throws IOException {
        return AtomicStoreRecovery.load(
                path,
                temporaryPath,
                "task-plan store",
                List::of,
                JsonPlanStore::decodeStore);
    }

    private static AtomicStoreRecovery.Decoded<List<TaskPlan>> decodeStore(
            byte[] bytes,
            Path source) throws IOException {
        try {
            Object parsed = SimpleJson.parse(
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            Map<String, Object> root = object(parsed, "root");
            int schemaVersion = number(root.get("schemaVersion"), "schemaVersion").intValue();
            if (schemaVersion != SCHEMA_VERSION && schemaVersion != LEGACY_SCHEMA_VERSION) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "task-plan store", schemaVersion);
            }
            List<TaskPlan> result = new ArrayList<>();
            for (Object encoded : array(root.get("plans"), "plans")) {
                result.add(decodePlan(object(encoded, "plan"), schemaVersion));
            }
            // Plans may be retired from the collection, so a maximum per-plan revision is not a
            // monotonic store generation. Atomic-file ordering remains authoritative.
            return new AtomicStoreRecovery.Decoded<>(List.copyOf(result), 0L, schemaVersion);
        } catch (AtomicStoreRecovery.UnsupportedSchemaException unsupported) {
            throw unsupported;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new IOException("Corrupt task-plan store: " + source, exception);
        }
    }

    @Override
    public synchronized void save(Collection<TaskPlan> plans) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<Map<String, Object>> encodedPlans = plans.stream()
                .map(TaskPlan::copy)
                .map(JsonPlanStore::encodePlan)
                .toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("plans", encodedPlans);
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

    private static Map<String, Object> encodePlan(TaskPlan plan) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("missionId", plan.missionId());
        encoded.put("state", plan.state().name());
        encoded.put("detail", plan.detail());
        encoded.put("createdAtMillis", plan.createdAtMillis());
        encoded.put("updatedAtMillis", plan.updatedAtMillis());
        encoded.put("revision", plan.revision());
        encoded.put("rootSpec", encodeSpec(plan.rootSpec()));
        encoded.put("frames", plan.frames().stream().map(JsonPlanStore::encodeFrame).toList());
        return encoded;
    }

    private static Map<String, Object> encodeSpec(PlanFrame.Spec spec) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("kind", spec.kind());
        encoded.put("target", spec.target());
        encoded.put("count", spec.count());
        encoded.put("parameters", spec.parameters());
        return encoded;
    }

    private static Map<String, Object> encodeFrame(PlanFrame frame) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("id", frame.id());
        encoded.put("kind", frame.kind());
        encoded.put("target", frame.target());
        encoded.put("count", frame.count());
        encoded.put("parameters", frame.parameters());
        encoded.put("state", frame.state().name());
        encoded.put("detail", frame.detail());
        encoded.put("createdAtMillis", frame.createdAtMillis());
        encoded.put("updatedAtMillis", frame.updatedAtMillis());
        return encoded;
    }

    private static TaskPlan decodePlan(Map<String, Object> encoded, int schemaVersion) {
        List<PlanFrame> frames = new ArrayList<>();
        for (Object value : array(encoded.get("frames"), "frames")) {
            frames.add(decodeFrame(object(value, "frame")));
        }
        String missionId = text(encoded.get("missionId"), "missionId");
        TaskPlanState state = TaskPlanState.valueOf(text(encoded.get("state"), "state"));
        PlanFrame.Spec rootSpec = schemaVersion == SCHEMA_VERSION
                ? decodeSpec(object(encoded.get("rootSpec"), "rootSpec"))
                : inferLegacyRootSpec(missionId, state, frames);
        return TaskPlan.restore(
                missionId,
                rootSpec,
                frames,
                state,
                text(encoded.get("detail"), "detail"),
                number(encoded.get("createdAtMillis"), "createdAtMillis").longValue(),
                number(encoded.get("updatedAtMillis"), "updatedAtMillis").longValue(),
                number(encoded.get("revision"), "revision").longValue());
    }

    private static PlanFrame.Spec decodeSpec(Map<String, Object> encoded) {
        Map<String, String> parameters = decodeParameters(encoded.get("parameters"));
        return new PlanFrame.Spec(
                text(encoded.get("kind"), "kind"),
                text(encoded.get("target"), "target"),
                number(encoded.get("count"), "count").longValue(),
                parameters);
    }

    /**
     * Version 1 stored only the mutable root frame. Recover its declarative
     * objective while excluding execution bookkeeping that must never be
     * resurrected after a discontinuity. Terminal version-1 plans no longer
     * contain a root frame, so they receive archival metadata only; they cannot
     * legally be rebased.
     */
    private static PlanFrame.Spec inferLegacyRootSpec(
            String missionId,
            TaskPlanState state,
            List<PlanFrame> frames) {
        if (frames.isEmpty()) {
            return new PlanFrame.Spec(
                    "legacy.archived", missionId, 1, Map.of("legacyState", state.name()));
        }
        PlanFrame root = frames.getFirst();
        Map<String, String> declarative = new LinkedHashMap<>(root.parameters());
        declarative.keySet().removeIf(JsonPlanStore::isLegacyTransientRootParameter);
        return new PlanFrame.Spec(root.kind(), root.target(), root.count(), declarative);
    }

    private static boolean isLegacyTransientRootParameter(String key) {
        return key.equals("startingCount")
                || key.equals("deliveryBatch")
                || key.equals("deliveredTotal")
                || key.equals("dropPending")
                || key.equals("dropBaselineCount")
                || key.equals("dropExpectedCount")
                || key.equals("dropPreparedAt")
                || key.startsWith("legacyMiningDropOrigin")
                || key.startsWith("ownedWorkstation");
    }

    private static PlanFrame decodeFrame(Map<String, Object> encoded) {
        Map<String, String> parameters = decodeParameters(encoded.get("parameters"));
        return PlanFrame.restore(
                text(encoded.get("id"), "id"),
                text(encoded.get("kind"), "kind"),
                text(encoded.get("target"), "target"),
                number(encoded.get("count"), "count").longValue(),
                parameters,
                PlanFrameState.valueOf(text(encoded.get("state"), "state")),
                text(encoded.get("detail"), "detail"),
                number(encoded.get("createdAtMillis"), "createdAtMillis").longValue(),
                number(encoded.get("updatedAtMillis"), "updatedAtMillis").longValue());
    }

    private static Map<String, String> decodeParameters(Object encoded) {
        Map<String, String> parameters = new LinkedHashMap<>();
        object(encoded, "parameters")
                .forEach((key, value) -> parameters.put(key, text(value, "parameter " + key)));
        return parameters;
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
}
