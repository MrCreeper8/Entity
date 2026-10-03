package dev.entity.client.runtime;

import dev.entity.core.persistence.AtomicStoreRecovery;
import dev.entity.core.persistence.SimpleJson;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Crash-safe, bounded persistence for automatic death-recovery circuit state. */
public final class DeathLoopRecoveryStore {
    public static final int SCHEMA_VERSION = 1;
    private static final long MAXIMUM_FILE_BYTES = 64 * 1024L;
    private static final int MAXIMUM_ID_LENGTH = 256;
    private static final int MAXIMUM_DIMENSION_LENGTH = 256;
    private static final int MAXIMUM_CAUSE_LENGTH = 256;
    private static final int MAXIMUM_REASON_LENGTH = 2_048;

    private final Path path;
    private final Path temporaryPath;

    public DeathLoopRecoveryStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    /**
     * A missing file is the compatible state for an installation upgraded from before this
     * journal existed. Existing but unreadable state is explicitly untrusted so callers can stop
     * automatic recovery instead of silently resetting its safety budget.
     */
    public synchronized LoadResult load() {
        boolean absent = Files.notExists(path) && Files.notExists(temporaryPath);
        try {
            State state = AtomicStoreRecovery.load(
                    path,
                    temporaryPath,
                    "death-recovery store",
                    State::empty,
                    DeathLoopRecoveryStore::decodeStore,
                    DeathLoopRecoveryStore::merge);
            return new LoadResult(state, absent, false, "");
        } catch (IOException | RuntimeException error) {
            return new LoadResult(
                    State.empty(),
                    false,
                    true,
                    "Could not trust death-recovery state from " + path + ": "
                            + Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName()));
        }
    }

    private static AtomicStoreRecovery.Decoded<State> decodeStore(
            byte[] encoded,
            Path source) throws IOException {
        long size = encoded.length;
        if (size < 1L || size > MAXIMUM_FILE_BYTES) {
            throw new IllegalArgumentException(
                    source.getFileName() + " size is outside the bounded range");
        }
        try {
            Map<String, Object> root = object(
                    SimpleJson.parse(StandardCharsets.UTF_8.newDecoder()
                            .decode(ByteBuffer.wrap(encoded)).toString()),
                    source.getFileName().toString());
            int schemaVersion = integer(root, "schemaVersion");
            if (schemaVersion != SCHEMA_VERSION) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "death-recovery store", schemaVersion);
            }
            return new AtomicStoreRecovery.Decoded<>(decode(root), 0L, schemaVersion);
        } catch (AtomicStoreRecovery.UnsupportedSchemaException unsupported) {
            throw unsupported;
        } catch (IllegalArgumentException error) {
            throw new IOException("Corrupt death-recovery store: " + source, error);
        }
    }

    /**
     * A sibling temporary file is the other half of the atomic replacement protocol. Both valid
     * halves are merged conservatively: bounded guard evidence is unioned, while any unfinished
     * recovery is retained. Two different unfinished boundaries are not safe to guess between.
     */
    private static State merge(State live, State temporary) {
        LinkedHashSet<DeathLoopGuard.Observation> observations = new LinkedHashSet<>();
        observations.addAll(live.guard().observations());
        observations.addAll(temporary.guard().observations());
        ArrayList<DeathLoopGuard.Observation> ordered = new ArrayList<>(observations);
        ordered.sort(Comparator.comparingLong(DeathLoopGuard.Observation::nowMillis));
        int first = Math.max(0, ordered.size() - DeathLoopGuard.MAXIMUM_RETAINED_OBSERVATIONS);
        List<DeathLoopGuard.Observation> bounded = List.copyOf(ordered.subList(first, ordered.size()));

        Optional<PendingRecovery> pending = mergePending(live.pending(), temporary.pending());
        return checked(new State(new DeathLoopGuard.Snapshot(bounded), pending));
    }

    private static Optional<PendingRecovery> mergePending(
            Optional<PendingRecovery> live,
            Optional<PendingRecovery> temporary) {
        if (live.isEmpty()) return temporary;
        if (temporary.isEmpty()) return live;
        PendingRecovery left = live.orElseThrow();
        PendingRecovery right = temporary.orElseThrow();
        if (!left.missionId().equals(right.missionId())
                || left.beganAtMillis() != right.beganAtMillis()
                || !left.decision().equals(right.decision())) {
            throw new IllegalArgumentException(
                    "live and temporary files contain conflicting pending recoveries");
        }
        RecoveryPhase phase = left.phase().ordinal() >= right.phase().ordinal()
                ? left.phase() : right.phase();
        return Optional.of(new PendingRecovery(
                left.missionId(), left.decision(), left.beganAtMillis(), phase));
    }

    /** Writes and flushes a sibling temporary file before atomically replacing the live state. */
    public synchronized void save(State state) throws IOException {
        State safe = checked(Objects.requireNonNull(state, "state"));
        String json = SimpleJson.stringify(encode(safe));
        if (json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_FILE_BYTES) {
            throw new IOException("death-recovery state exceeds its bounded file size");
        }
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(
                temporaryPath,
                json,
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

    private static State checked(State state) {
        for (DeathLoopGuard.Observation observation : state.guard().observations()) {
            checkedText(observation.missionId(), "missionId", MAXIMUM_ID_LENGTH, false);
            checkedText(observation.dimension(), "dimension", MAXIMUM_DIMENSION_LENGTH, false);
            checkedText(observation.cause(), "cause", MAXIMUM_CAUSE_LENGTH, false);
        }
        state.pending().ifPresent(pending -> {
            checkedText(pending.missionId(), "pending missionId", MAXIMUM_ID_LENGTH, false);
            checkedText(pending.decision().reason(), "pending reason", MAXIMUM_REASON_LENGTH,
                    !pending.decision().haltAutomaticRecovery());
            Objects.requireNonNull(pending.phase(), "pending phase");
        });
        return state;
    }

    private static Map<String, Object> encode(State state) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        List<Map<String, Object>> observations = new ArrayList<>();
        for (DeathLoopGuard.Observation observation : state.guard().observations()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("missionId", observation.missionId());
            encoded.put("dimension", observation.dimension());
            encoded.put("x", observation.x());
            encoded.put("y", observation.y());
            encoded.put("z", observation.z());
            encoded.put("causeKey", observation.cause());
            encoded.put("observedAt", observation.nowMillis());
            observations.add(encoded);
        }
        root.put("observations", observations);
        root.put("pendingRecovery", state.pending().<Object>map(pending -> {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("missionId", pending.missionId());
            encoded.put("beganAt", pending.beganAtMillis());
            encoded.put("decision", encodeDecision(pending.decision()));
            encoded.put("phase", pending.phase().wireName());
            return encoded;
        }).orElse(null));
        return root;
    }

    private static Map<String, Object> encodeDecision(DeathLoopGuard.Decision decision) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("haltAutomaticRecovery", decision.haltAutomaticRecovery());
        encoded.put("missionDeaths", decision.missionDeaths());
        encoded.put("repeatedSiteAndCause", decision.repeatedSiteAndCause());
        encoded.put("reason", decision.reason());
        return encoded;
    }

    private static State decode(Map<String, Object> root) {
        if (integer(root, "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schema version");
        }
        List<Object> encodedObservations = array(root.get("observations"), "observations");
        if (encodedObservations.size() > DeathLoopGuard.MAXIMUM_RETAINED_OBSERVATIONS) {
            throw new IllegalArgumentException("observation history exceeds its bound");
        }
        ArrayList<DeathLoopGuard.Observation> observations =
                new ArrayList<>(encodedObservations.size());
        for (Object value : encodedObservations) {
            Map<String, Object> encoded = object(value, "observation");
            observations.add(new DeathLoopGuard.Observation(
                    text(encoded, "missionId", MAXIMUM_ID_LENGTH, false),
                    text(encoded, "dimension", MAXIMUM_DIMENSION_LENGTH, false),
                    decimal(encoded, "x"),
                    decimal(encoded, "y"),
                    decimal(encoded, "z"),
                    text(encoded, "causeKey", MAXIMUM_CAUSE_LENGTH, false),
                    longInteger(encoded, "observedAt")));
        }

        Optional<PendingRecovery> pending = Optional.empty();
        Object pendingValue = root.get("pendingRecovery");
        if (pendingValue != null) {
            Map<String, Object> encoded = object(pendingValue, "pendingRecovery");
            pending = Optional.of(new PendingRecovery(
                    text(encoded, "missionId", MAXIMUM_ID_LENGTH, false),
                    decodeDecision(object(required(encoded, "decision"), "decision")),
                    longInteger(encoded, "beganAt"),
                    decodePhase(encoded)));
        }
        return checked(new State(new DeathLoopGuard.Snapshot(observations), pending));
    }

    private static RecoveryPhase decodePhase(Map<String, Object> encoded) {
        // Schema-v1 journals written before phased finalization are unfinished
        // work and therefore map conservatively to the original pending state.
        if (!encoded.containsKey("phase")) return RecoveryPhase.PENDING;
        String value = text(encoded, "phase", 32, false);
        for (RecoveryPhase phase : RecoveryPhase.values()) {
            if (phase.wireName().equals(value)) return phase;
        }
        throw new IllegalArgumentException("unsupported pending recovery phase");
    }

    private static DeathLoopGuard.Decision decodeDecision(Map<String, Object> encoded) {
        return new DeathLoopGuard.Decision(
                bool(encoded, "haltAutomaticRecovery"),
                integer(encoded, "missionDeaths"),
                bool(encoded, "repeatedSiteAndCause"),
                text(encoded, "reason", MAXIMUM_REASON_LENGTH, true));
    }

    private static Object required(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
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

    private static boolean bool(Map<String, Object> object, String field) {
        Object value = required(object, field);
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return result;
    }

    private static int integer(Map<String, Object> object, String field) {
        long value = longInteger(object, field);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is outside integer range");
        }
        return (int) value;
    }

    private static long longInteger(Map<String, Object> object, String field) {
        Object value = required(object, field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            long result = new BigDecimal(number.toString()).longValueExact();
            if (result < 0L) throw new IllegalArgumentException(field + " must be non-negative");
            return result;
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException(field + " must be an integer", error);
        }
    }

    private static double decimal(Map<String, Object> object, String field) {
        Object value = required(object, field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) throw new IllegalArgumentException(field + " must be finite");
        return result;
    }

    private static String text(
            Map<String, Object> object,
            String field,
            int maximumLength,
            boolean allowEmpty) {
        Object value = required(object, field);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return checkedText(text, field, maximumLength, allowEmpty);
    }

    private static String checkedText(
            String raw,
            String field,
            int maximumLength,
            boolean allowEmpty) {
        String value = Objects.requireNonNullElse(raw, "").trim();
        if ((!allowEmpty && value.isEmpty()) || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is outside its bounded text range");
        }
        return value;
    }

    public record State(
            DeathLoopGuard.Snapshot guard,
            Optional<PendingRecovery> pending) {
        public State {
            guard = Objects.requireNonNull(guard, "guard");
            pending = Objects.requireNonNull(pending, "pending");
        }

        public static State empty() {
            return new State(new DeathLoopGuard.Snapshot(List.of()), Optional.empty());
        }
    }

    public record PendingRecovery(
            String missionId,
            DeathLoopGuard.Decision decision,
            long beganAtMillis,
            RecoveryPhase phase) {
        public PendingRecovery(
                String missionId,
                DeathLoopGuard.Decision decision,
                long beganAtMillis) {
            this(missionId, decision, beganAtMillis, RecoveryPhase.PENDING);
        }

        public PendingRecovery {
            missionId = Objects.requireNonNullElse(missionId, "").trim();
            decision = Objects.requireNonNull(decision, "decision");
            phase = Objects.requireNonNull(phase, "phase");
            if (missionId.isEmpty() || beganAtMillis < 0L) {
                throw new IllegalArgumentException("pending death recovery requires a mission and time");
            }
        }
    }

    /** Durable recovery phases. Only FINALIZED is permitted to clear without replaying effects. */
    public enum RecoveryPhase {
        PENDING("pending"),
        EFFECTS_STARTED("effects_started"),
        FINALIZED("finalized");

        private final String wireName;

        RecoveryPhase(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public record LoadResult(
            State state,
            boolean absent,
            boolean failClosed,
            String warning) {
        public LoadResult {
            state = Objects.requireNonNull(state, "state");
            warning = Objects.requireNonNullElse(warning, "");
            if (absent && failClosed) throw new IllegalArgumentException("absent state is not corrupt state");
        }
    }
}
