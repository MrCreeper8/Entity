package dev.entity.client.autonomy.policy;

import dev.entity.client.autonomy.policy.AcquisitionRequest.ItemGoal;
import dev.entity.client.autonomy.policy.ResourcePlanner.Action;
import dev.entity.client.autonomy.policy.ResourcePlanner.ActionKind;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment.DivergenceCause;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment.Invalidation;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment.PlanningBaseline;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment.Program;
import dev.entity.client.autonomy.policy.UniversalProgramCommitment.Status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Bounded, versioned durable encoding for a complete universal program.
 *
 * <p>The payload lives as one TaskPlan root parameter, so the program and its
 * cursor participate in the same atomic plan-store replacement. Decoding does
 * not trust the wire identity: {@link UniversalProgramCommitment#restore}
 * recomputes every executable fingerprint before accepting it.</p>
 */
public final class UniversalProgramCodec {
    private static final int MAGIC = 0x45325047; // E2PG
    private static final int LEGACY_VERSION = 1;
    private static final int VERSION = 2;
    private static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 65_536;
    private static final int MAX_STRING_BYTES = 1024 * 1024;

    private UniversalProgramCodec() {
    }

    public static String encode(Program program) {
        Objects.requireNonNull(program, "program");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                writeString(output, program.identity().catalogFingerprint());
                writeString(output, program.identity().commitmentFingerprint());
                writeGoals(output, program.goals());
                writeBaseline(output, program.baseline());
                writeActions(output, program.actions());
                output.writeInt(program.cursor());
                writeString(output, program.status().name());
                output.writeBoolean(program.invalidation().isPresent());
                if (program.invalidation().isPresent()) {
                    Invalidation invalidation = program.invalidation().orElseThrow();
                    writeString(output, invalidation.cause().name());
                    output.writeInt(invalidation.actionIndex());
                    writeString(output, invalidation.actionToken());
                    writeString(output, invalidation.detail());
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("universal program exceeds durable payload bound");
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory universal program encoding failed", impossible);
        }
    }

    public static Program decode(String payload) {
        Objects.requireNonNull(payload, "payload");
        final byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("universal program is not valid base64url", invalidBase64);
        }
        if (bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("universal program payload length is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("universal program magic is invalid");
            }
            int version = input.readInt();
            if (version != LEGACY_VERSION && version != VERSION) {
                throw new IllegalArgumentException("unsupported universal program version " + version);
            }
            String catalog = readString(input);
            String commitment = readString(input);
            List<ItemGoal> goals = readGoals(input);
            PlanningBaseline baseline = version == LEGACY_VERSION
                    ? readLegacyBaseline(input) : readBaseline(input);
            List<Action> actions = readActions(input);
            int cursor = input.readInt();
            Status status = enumValue(Status.class, readString(input), "status");
            Invalidation invalidation = null;
            if (input.readBoolean()) {
                DivergenceCause cause = enumValue(
                        DivergenceCause.class, readString(input), "divergence cause");
                int actionIndex = input.readInt();
                String actionToken = readString(input);
                String detail = readString(input);
                invalidation = new Invalidation(cause, actionIndex, actionToken, detail);
            }
            if (input.read() != -1) {
                throw new IllegalArgumentException("universal program has trailing data");
            }
            return version == LEGACY_VERSION
                    ? UniversalProgramCommitment.restoreLegacyV1(
                    catalog, goals, baseline, actions, cursor, status,
                    invalidation, commitment)
                    : UniversalProgramCommitment.restore(
                    catalog, goals, baseline, actions, cursor, status,
                    invalidation, commitment);
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("universal program is truncated", truncated);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("universal program could not be decoded", invalid);
        }
    }

    private static void writeGoals(DataOutputStream output, List<ItemGoal> goals) throws IOException {
        writeSize(output, goals.size());
        for (ItemGoal goal : goals) {
            writeString(output, goal.item());
            output.writeInt(goal.count());
        }
    }

    private static List<ItemGoal> readGoals(DataInputStream input) throws IOException {
        int size = readSize(input);
        ArrayList<ItemGoal> goals = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            goals.add(new ItemGoal(readString(input), input.readInt()));
        }
        return List.copyOf(goals);
    }

    private static void writeBaseline(
            DataOutputStream output,
            PlanningBaseline baseline) throws IOException {
        writeCounts(output, baseline.itemCounts());
        writeItems(output, baseline.equippedItems());
        writeItems(output, baseline.locallyAvailableItems());
        writeItems(output, baseline.availableStations());
        writeCounts(output, baseline.usableToolDurability());
    }

    private static PlanningBaseline readBaseline(DataInputStream input) throws IOException {
        return new PlanningBaseline(
                readCounts(input), readItems(input), readItems(input), readItems(input),
                readCounts(input));
    }

    private static PlanningBaseline readLegacyBaseline(DataInputStream input) throws IOException {
        return new PlanningBaseline(
                readCounts(input), readItems(input), readItems(input), Set.of(),
                readCounts(input));
    }

    private static void writeActions(DataOutputStream output, List<Action> actions) throws IOException {
        writeSize(output, actions.size());
        for (Action action : actions) {
            writeString(output, action.kind().name());
            writeString(output, action.target());
            output.writeInt(action.count());
            TreeMap<String, String> parameters = new TreeMap<>(action.parameters());
            writeSize(output, parameters.size());
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                writeString(output, entry.getKey());
                writeString(output, entry.getValue());
            }
            writeString(output, action.description());
        }
    }

    private static List<Action> readActions(DataInputStream input) throws IOException {
        int size = readSize(input);
        ArrayList<Action> actions = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            ActionKind kind = enumValue(ActionKind.class, readString(input), "action kind");
            String target = readString(input);
            int count = input.readInt();
            int parameterCount = readSize(input);
            LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
            for (int parameter = 0; parameter < parameterCount; parameter++) {
                String previous = parameters.put(readString(input), readString(input));
                if (previous != null) {
                    throw new IllegalArgumentException("universal action contains a duplicate parameter");
                }
            }
            actions.add(new Action(kind, target, count, parameters, readString(input)));
        }
        return List.copyOf(actions);
    }

    private static void writeCounts(
            DataOutputStream output,
            Map<String, Integer> counts) throws IOException {
        TreeMap<String, Integer> sorted = new TreeMap<>(counts);
        writeSize(output, sorted.size());
        for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
            writeString(output, entry.getKey());
            output.writeInt(entry.getValue());
        }
    }

    private static Map<String, Integer> readCounts(DataInputStream input) throws IOException {
        int size = readSize(input);
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            String item = readString(input);
            int count = input.readInt();
            if (counts.put(item, count) != null) {
                throw new IllegalArgumentException("universal program contains a duplicate count item");
            }
        }
        return counts;
    }

    private static void writeItems(DataOutputStream output, Set<String> items) throws IOException {
        TreeSet<String> sorted = new TreeSet<>(items);
        writeSize(output, sorted.size());
        for (String item : sorted) writeString(output, item);
    }

    private static Set<String> readItems(DataInputStream input) throws IOException {
        int size = readSize(input);
        LinkedHashSet<String> items = new LinkedHashSet<>();
        for (int index = 0; index < size; index++) {
            if (!items.add(readString(input))) {
                throw new IllegalArgumentException("universal program contains a duplicate item");
            }
        }
        return items;
    }

    private static void writeSize(DataOutputStream output, int size) throws IOException {
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("universal program collection size is invalid");
        }
        output.writeInt(size);
    }

    private static int readSize(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("universal program collection size is invalid");
        }
        return size;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("universal program string exceeds durable bound");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("universal program string length is invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated universal program string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value,
            String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid universal program " + field, invalid);
        }
    }
}
