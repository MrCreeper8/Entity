package dev.entity.client.autonomy.policy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

/** Strict checksummed codec for the top real mission root's {@code homeSupplyV1}. */
public final class HomeMissionSupplyCodec {
    private static final int MAGIC = 0x45324853; // E2HS
    private static final int SCHEMA = 2;
    private static final int MAXIMUM_ENTRIES = 256;
    private static final int MAXIMUM_BYTES = 1_048_576;
    private static final int MAXIMUM_HANDLER_SLOT = 255;
    private static final int MAXIMUM_STACK_COUNT = 64;

    public enum Phase {
        BOUND,
        ROUTING,
        OBSERVING,
        ALLOCATED,
        TRANSFERRING,
        CLOSING,
        READY,
        SKIPPED,
        BLOCKED
    }

    public record Withdrawal(
            String stackId,
            int sourceSlot,
            String item,
            int totalCount,
            int remainingCount) {
        public Withdrawal {
            stackId = requireText(stackId, "stackId", 256);
            item = normalizeItem(item);
            if (sourceSlot < 0 || sourceSlot > MAXIMUM_HANDLER_SLOT || totalCount <= 0
                    || remainingCount < 0 || remainingCount > totalCount) {
                throw new IllegalArgumentException("invalid Home mission withdrawal");
            }
        }

        public Withdrawal withRemaining(int remaining) {
            return new Withdrawal(stackId, sourceSlot, item, totalCount, remaining);
        }
    }

    /** Persisted before the first click of one root-owned chest transfer. */
    public record PendingTransfer(
            String id,
            String stableOwner,
            String stackId,
            String item,
            int count,
            int syncId,
            int sourceSlot,
            int destinationSlot,
            int destinationCountBefore,
            int playerCountBefore,
            int chestCountBefore,
            long startedAtMillis,
            HomeEconomySession.ContainerIdentity containerIdentity) {
        /** Schema 1 callers/saves have unknown geometry, never an inferred combined chest. */
        public PendingTransfer(String id, String stableOwner, String stackId, String item,
                int count, int syncId, int sourceSlot, int destinationSlot,
                int destinationCountBefore, int playerCountBefore, int chestCountBefore,
                long startedAtMillis) {
            this(id, stableOwner, stackId, item, count, syncId, sourceSlot, destinationSlot,
                    destinationCountBefore, playerCountBefore, chestCountBefore, startedAtMillis, null);
        }

        public PendingTransfer {
            id = requireText(id, "id", 256);
            stableOwner = requireText(stableOwner, "stableOwner", 256);
            stackId = requireText(stackId, "stackId", 256);
            item = normalizeItem(item);
            if (count <= 0 || count > MAXIMUM_STACK_COUNT || syncId < 0
                    || sourceSlot < 0 || sourceSlot > MAXIMUM_HANDLER_SLOT
                    || destinationSlot < 0 || destinationSlot > MAXIMUM_HANDLER_SLOT
                    || destinationCountBefore < 0
                    || destinationCountBefore > MAXIMUM_STACK_COUNT
                    || playerCountBefore < 0
                    || chestCountBefore < count || startedAtMillis < 0L) {
                throw new IllegalArgumentException("invalid Home mission pending transfer");
            }
            if (containerIdentity != null) {
                int slots = containerIdentity.slotCount();
                if (sourceSlot >= slots || destinationSlot < slots || destinationSlot >= slots + 36) {
                    throw new IllegalArgumentException("mission transfer slots do not match the exact container layout");
                }
            }
        }
    }

    public record State(
            Phase phase,
            String cycleFingerprint,
            String catalogFingerprint,
            long homeGeneration,
            String homeFingerprint,
            String dimension,
            int homeX,
            int homeY,
            int homeZ,
            String chestLedgerId,
            String tableLedgerId,
            String furnaceLedgerId,
            boolean pinnedStationsAllowed,
            Map<String, Integer> requestedCounts,
            Map<String, Integer> allocatedCounts,
            List<Withdrawal> withdrawals,
            Map<String, Integer> withdrawnCounts,
            PendingTransfer pendingTransfer,
            int resolvedAllCount,
            String detail,
            List<String> visitedStorageLedgerIds) {
        /** Schema 1 compatibility: the original pinned chest has no completed predecessors. */
        public State(Phase phase, String cycleFingerprint, String catalogFingerprint,
                long homeGeneration, String homeFingerprint, String dimension,
                int homeX, int homeY, int homeZ, String chestLedgerId,
                String tableLedgerId, String furnaceLedgerId, boolean pinnedStationsAllowed,
                Map<String, Integer> requestedCounts, Map<String, Integer> allocatedCounts,
                List<Withdrawal> withdrawals, Map<String, Integer> withdrawnCounts,
                PendingTransfer pendingTransfer, int resolvedAllCount, String detail) {
            this(phase, cycleFingerprint, catalogFingerprint, homeGeneration, homeFingerprint,
                    dimension, homeX, homeY, homeZ, chestLedgerId, tableLedgerId, furnaceLedgerId,
                    pinnedStationsAllowed, requestedCounts, allocatedCounts, withdrawals,
                    withdrawnCounts, pendingTransfer, resolvedAllCount, detail, List.of());
        }

        public State {
            phase = Objects.requireNonNull(phase, "phase");
            cycleFingerprint = requireText(cycleFingerprint, "cycleFingerprint", 256);
            catalogFingerprint = Objects.requireNonNullElse(catalogFingerprint, "").trim();
            homeFingerprint = Objects.requireNonNullElse(homeFingerprint, "").trim();
            dimension = Objects.requireNonNullElse(dimension, "").trim();
            chestLedgerId = Objects.requireNonNullElse(chestLedgerId, "").trim();
            tableLedgerId = Objects.requireNonNullElse(tableLedgerId, "").trim();
            furnaceLedgerId = Objects.requireNonNullElse(furnaceLedgerId, "").trim();
            requestedCounts = immutableCounts(requestedCounts, "requestedCounts");
            allocatedCounts = immutableCounts(allocatedCounts, "allocatedCounts");
            withdrawals = List.copyOf(Objects.requireNonNull(withdrawals, "withdrawals"));
            withdrawnCounts = immutableCounts(withdrawnCounts, "withdrawnCounts");
            detail = Objects.requireNonNullElse(detail, "").trim();
            visitedStorageLedgerIds = immutableLedgerIds(visitedStorageLedgerIds);
            if (visitedStorageLedgerIds.contains(chestLedgerId)) {
                throw new IllegalArgumentException("current storage cannot already be completed");
            }
            if (resolvedAllCount < -1) {
                throw new IllegalArgumentException("resolvedAllCount must be -1 or nonnegative");
            }
            boolean bound = phase != Phase.SKIPPED;
            if (bound && (homeGeneration <= 0L || homeFingerprint.isEmpty()
                    || dimension.isEmpty() || chestLedgerId.isEmpty())) {
                throw new IllegalArgumentException(
                        "bound Home supply needs exact Home and chest identity");
            }
            if (bound && pinnedStationsAllowed
                    && tableLedgerId.isEmpty() && furnaceLedgerId.isEmpty()) {
                throw new IllegalArgumentException(
                        "pinned Home stations need at least one exact ledger identity");
            }
            if (!bound && homeGeneration != 0L) {
                throw new IllegalArgumentException("skipped Home supply cannot bind a generation");
            }
            if (pendingTransfer != null
                    && phase != Phase.TRANSFERRING && phase != Phase.BLOCKED) {
                throw new IllegalArgumentException(
                        "pending transfer requires TRANSFERRING or BLOCKED phase");
            }
            if (phase == Phase.TRANSFERRING && pendingTransfer == null) {
                throw new IllegalArgumentException("TRANSFERRING requires a pending transfer");
            }
            if (pendingTransfer != null && pendingTransfer.containerIdentity() != null) {
                for (HomeEconomySession.ChestHalf half : pendingTransfer.containerIdentity().halves()) {
                    if (!dimension.equals(half.position().dimension())) {
                        throw new IllegalArgumentException("mission transfer container belongs to another dimension");
                    }
                }
            }
            if ((phase == Phase.BLOCKED) != !detail.isEmpty()) {
                throw new IllegalArgumentException("only BLOCKED may carry detail");
            }
            validateAllocationTruth(
                    allocatedCounts, withdrawals, withdrawnCounts, pendingTransfer);
            if ((phase == Phase.CLOSING || phase == Phase.READY)
                    && (!withdrawnCounts.equals(allocatedCounts)
                    || withdrawals.stream().anyMatch(withdrawal ->
                    withdrawal.remainingCount() > 0))) {
                throw new IllegalArgumentException(
                        "closing/ready supply must prove its complete allocation");
            }
        }

        public static State skipped(String cycleFingerprint, int resolvedAllCount) {
            return new State(
                    Phase.SKIPPED, cycleFingerprint, "", 0L, "", "",
                    0, 0, 0, "", "", "", false,
                    Map.of(), Map.of(), List.of(), Map.of(), null,
                    resolvedAllCount, "");
        }

        public State withPhase(Phase next) {
            if (pendingTransfer != null) throw new IllegalStateException("phase change cannot erase pending transfer truth");
            return new State(
                    next, cycleFingerprint, catalogFingerprint, homeGeneration,
                    homeFingerprint, dimension, homeX, homeY, homeZ,
                    chestLedgerId, tableLedgerId, furnaceLedgerId,
                    pinnedStationsAllowed, requestedCounts, allocatedCounts,
                    withdrawals, withdrawnCounts, null, resolvedAllCount, "", visitedStorageLedgerIds);
        }

        public State withAllocation(
                Map<String, Integer> requested,
                Map<String, Integer> allocated,
                List<Withdrawal> selected,
                boolean allowPins,
                int resolvedAll) {
            if (pendingTransfer != null) throw new IllegalStateException("allocation cannot erase pending transfer truth");
            return new State(
                    selected.isEmpty() ? Phase.CLOSING : Phase.ALLOCATED,
                    cycleFingerprint, catalogFingerprint, homeGeneration,
                    homeFingerprint, dimension, homeX, homeY, homeZ,
                    chestLedgerId, tableLedgerId, furnaceLedgerId,
                    allowPins, requested, allocated, selected, Map.of(), null,
                    resolvedAll, "", visitedStorageLedgerIds);
        }

        public State beginTransfer(PendingTransfer transfer) {
            if (pendingTransfer != null) {
                throw new IllegalStateException("pending transfer must be reconciled or explicitly rebased");
            }
            return new State(
                    Phase.TRANSFERRING, cycleFingerprint, catalogFingerprint,
                    homeGeneration, homeFingerprint, dimension, homeX, homeY, homeZ,
                    chestLedgerId, tableLedgerId, furnaceLedgerId,
                    pinnedStationsAllowed, requestedCounts, allocatedCounts,
                    withdrawals, withdrawnCounts, transfer, resolvedAllCount, "", visitedStorageLedgerIds);
        }

        /**
         * Retire a stale allocation only after the caller has settled its exact cursor/click
         * custody. External chest edits cannot prove a withdrawal; keep previously acknowledged
         * quantities unchanged and let the next allocation use fresh physical carried counts.
         */
        public State reobserveSettledContents(boolean cursorAndClicksSettled) {
            if (!cursorAndClicksSettled) {
                throw new IllegalStateException("changed Home contents still have unresolved custody");
            }
            if (phase != Phase.ALLOCATED && phase != Phase.TRANSFERRING
                    && phase != Phase.BLOCKED) {
                throw new IllegalStateException("no active Home allocation to reobserve");
            }
            return new State(Phase.OBSERVING, cycleFingerprint, catalogFingerprint,
                    homeGeneration, homeFingerprint, dimension, homeX, homeY, homeZ,
                    chestLedgerId, tableLedgerId, furnaceLedgerId, pinnedStationsAllowed,
                    requestedCounts, allocatedCounts, withdrawals, withdrawnCounts, null,
                    resolvedAllCount, "", visitedStorageLedgerIds);
        }

        public State commitTransfer(int moved) {
            if (pendingTransfer == null || moved <= 0 || moved > pendingTransfer.count()) {
                throw new IllegalArgumentException("invalid committed Home mission transfer count");
            }
            ArrayList<Withdrawal> remaining = new ArrayList<>(withdrawals.size());
            boolean matched = false;
            for (Withdrawal withdrawal : withdrawals) {
                if (!matched && withdrawal.stackId().equals(pendingTransfer.stackId())) {
                    if (moved > withdrawal.remainingCount()) {
                        throw new IllegalArgumentException("transfer exceeds stack withdrawal remainder");
                    }
                    remaining.add(withdrawal.withRemaining(
                            withdrawal.remainingCount() - moved));
                    matched = true;
                } else {
                    remaining.add(withdrawal);
                }
            }
            if (!matched) throw new IllegalArgumentException("pending transfer stack is unknown");
            LinkedHashMap<String, Integer> withdrawn = new LinkedHashMap<>(withdrawnCounts);
            withdrawn.merge(pendingTransfer.item(), moved, Math::addExact);
            boolean complete = remaining.stream().noneMatch(value -> value.remainingCount() > 0);
            return new State(
                    complete ? Phase.CLOSING : Phase.ALLOCATED,
                    cycleFingerprint, catalogFingerprint, homeGeneration,
                    homeFingerprint, dimension, homeX, homeY, homeZ,
                    chestLedgerId, tableLedgerId, furnaceLedgerId,
                    pinnedStationsAllowed, requestedCounts, allocatedCounts,
                    remaining, withdrawn, null, resolvedAllCount, "", visitedStorageLedgerIds);
        }

        /**
         * Clears an unobserved intent only after the runtime has proved the exact before-counts,
         * an empty cursor, and a newly opened handler. No allocated or withdrawn quantity moves
         * across this restart/reopen boundary.
         */
        public State rebindUnchangedTransfer() {
            if (pendingTransfer == null) {
                throw new IllegalStateException("no Home mission transfer is pending");
            }
            if (pendingTransfer.containerIdentity() != null) {
                throw new IllegalStateException("known container identity must survive an exact transfer rebase");
            }
            return new State(
                    Phase.ALLOCATED, cycleFingerprint, catalogFingerprint,
                    homeGeneration, homeFingerprint, dimension, homeX, homeY, homeZ,
                    chestLedgerId, tableLedgerId, furnaceLedgerId,
                    pinnedStationsAllowed, requestedCounts, allocatedCounts,
                    withdrawals, withdrawnCounts, null, resolvedAllCount, "", visitedStorageLedgerIds);
        }

        /**
         * Reopen only after the caller proves unchanged raw counts and an empty/unowned cursor.
         * A new handler is not permission to switch storage or reinterpret single/double slots.
         */
        public State rebaseTransfer(PendingTransfer replacement) {
            Objects.requireNonNull(replacement, "replacement");
            if (pendingTransfer == null) throw new IllegalStateException("no Home mission transfer is pending");
            PendingTransfer previous = pendingTransfer;
            if (!previous.id().equals(replacement.id())
                    || !previous.stableOwner().equals(replacement.stableOwner())
                    || !previous.stackId().equals(replacement.stackId())
                    || !previous.item().equals(replacement.item())
                    || previous.sourceSlot() != replacement.sourceSlot()
                    || replacement.count() > previous.count()
                    || previous.playerCountBefore() != replacement.playerCountBefore()
                    || previous.chestCountBefore() != replacement.chestCountBefore()) {
                throw new IllegalArgumentException("rebased mission transfer must preserve exact unresolved withdrawal truth");
            }
            if (previous.containerIdentity() != null
                    && !previous.containerIdentity().equals(replacement.containerIdentity())) {
                throw new IllegalArgumentException("rebased mission transfer cannot change the known container");
            }
            if (previous.containerIdentity() == null && replacement.containerIdentity() != null
                    && replacement.containerIdentity().halves().size() != 1) {
                throw new IllegalArgumentException("legacy mission transfer needs its original single chest reconciliation");
            }
            return new State(Phase.TRANSFERRING, cycleFingerprint, catalogFingerprint,
                    homeGeneration, homeFingerprint, dimension, homeX, homeY, homeZ,
                    chestLedgerId, tableLedgerId, furnaceLedgerId, pinnedStationsAllowed,
                    requestedCounts, allocatedCounts, withdrawals, withdrawnCounts, replacement,
                    resolvedAllCount, "", visitedStorageLedgerIds);
        }

        /**
         * Caller must first observe the prior handler closed and cursor/custody settled.
         * CLOSING itself proves every prior withdrawal; counts never aggregate across chests.
         * The next allocation uses fresh carried inventory and a fresh exact chest observation.
         */
        public State nextStorage(String ledgerId) {
            String next = requireText(ledgerId, "next storage ledger ID", 256);
            if (phase != Phase.CLOSING || pendingTransfer != null) {
                throw new IllegalStateException("next storage requires a settled closing allocation");
            }
            if (next.equals(chestLedgerId) || visitedStorageLedgerIds.contains(next)) {
                throw new IllegalArgumentException("storage was already visited in this mission supply cycle");
            }
            ArrayList<String> completed = new ArrayList<>(visitedStorageLedgerIds);
            completed.add(chestLedgerId);
            return new State(Phase.BOUND, cycleFingerprint, catalogFingerprint,
                    homeGeneration, homeFingerprint, dimension, homeX, homeY, homeZ,
                    next, tableLedgerId, furnaceLedgerId, pinnedStationsAllowed, requestedCounts,
                    Map.of(), List.of(), Map.of(), null, resolvedAllCount, "", completed);
        }

        public State block(String blocker) {
            return new State(
                    Phase.BLOCKED, cycleFingerprint, catalogFingerprint,
                    homeGeneration, homeFingerprint, dimension, homeX, homeY, homeZ,
                    chestLedgerId, tableLedgerId, furnaceLedgerId,
                    pinnedStationsAllowed, requestedCounts, allocatedCounts,
                    withdrawals, withdrawnCounts, pendingTransfer, resolvedAllCount,
                    requireText(blocker, "detail", 4096), visitedStorageLedgerIds);
        }
    }

    private HomeMissionSupplyCodec() {
    }

    public static String encode(State state) {
        Objects.requireNonNull(state, "state");
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
                output.writeInt(MAGIC);
                output.writeInt(SCHEMA);
                output.writeUTF(state.phase().name());
                output.writeUTF(state.cycleFingerprint());
                output.writeUTF(state.catalogFingerprint());
                output.writeLong(state.homeGeneration());
                output.writeUTF(state.homeFingerprint());
                output.writeUTF(state.dimension());
                output.writeInt(state.homeX());
                output.writeInt(state.homeY());
                output.writeInt(state.homeZ());
                output.writeUTF(state.chestLedgerId());
                output.writeUTF(state.tableLedgerId());
                output.writeUTF(state.furnaceLedgerId());
                output.writeBoolean(state.pinnedStationsAllowed());
                writeCounts(output, state.requestedCounts());
                writeCounts(output, state.allocatedCounts());
                output.writeInt(state.withdrawals().size());
                for (Withdrawal withdrawal : state.withdrawals()) {
                    output.writeUTF(withdrawal.stackId());
                    output.writeInt(withdrawal.sourceSlot());
                    output.writeUTF(withdrawal.item());
                    output.writeInt(withdrawal.totalCount());
                    output.writeInt(withdrawal.remainingCount());
                }
                writeCounts(output, state.withdrawnCounts());
                output.writeBoolean(state.pendingTransfer() != null);
                if (state.pendingTransfer() != null) writeTransfer(output, state.pendingTransfer());
                output.writeInt(state.resolvedAllCount());
                output.writeUTF(state.detail());
                output.writeInt(state.visitedStorageLedgerIds().size());
                for (String ledgerId : state.visitedStorageLedgerIds()) output.writeUTF(ledgerId);
            }
            byte[] payload = payloadBytes.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(payload);
            ByteArrayOutputStream envelope = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(envelope)) {
                output.writeInt(payload.length);
                output.write(payload);
                output.writeLong(crc.getValue());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory Home supply encoding failed", impossible);
        }
    }

    public static State decode(String encoded) {
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(requireText(
                    encoded, "homeSupplyV1", MAXIMUM_BYTES * 2));
            if (envelope.length > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("homeSupplyV1 exceeds maximum size");
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope))) {
                int length = input.readInt();
                if (length < 0 || length != envelope.length - 12) {
                    throw new IllegalArgumentException("invalid Home supply payload length");
                }
                byte[] payload = input.readNBytes(length);
                long expected = input.readLong();
                if (input.read() != -1) throw new IllegalArgumentException("trailing Home supply data");
                CRC32 crc = new CRC32();
                crc.update(payload);
                if (crc.getValue() != expected) {
                    throw new IllegalArgumentException("Home supply checksum mismatch");
                }
                return decodePayload(payload);
            }
        } catch (EOFException error) {
            throw new IllegalArgumentException("truncated homeSupplyV1", error);
        } catch (IOException | IllegalArgumentException error) {
            if (error instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("invalid homeSupplyV1", error);
        }
    }

    private static State decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("unsupported Home supply schema");
            }
            int schema = input.readInt();
            if (schema < 1 || schema > SCHEMA) throw new IllegalArgumentException("unsupported Home supply schema");
            Phase phase = Phase.valueOf(input.readUTF());
            String cycle = input.readUTF();
            String catalog = input.readUTF();
            long generation = input.readLong();
            String homeFingerprint = input.readUTF();
            String dimension = input.readUTF();
            int x = input.readInt();
            int y = input.readInt();
            int z = input.readInt();
            String chest = input.readUTF();
            String table = input.readUTF();
            String furnace = input.readUTF();
            boolean pins = input.readBoolean();
            Map<String, Integer> requested = readCounts(input);
            Map<String, Integer> allocated = readCounts(input);
            int withdrawalCount = boundedCount(input.readInt(), "withdrawal");
            ArrayList<Withdrawal> withdrawals = new ArrayList<>();
            for (int index = 0; index < withdrawalCount; index++) {
                withdrawals.add(new Withdrawal(
                        input.readUTF(), input.readInt(), input.readUTF(),
                        input.readInt(), input.readInt()));
            }
            Map<String, Integer> withdrawn = readCounts(input);
            PendingTransfer transfer = input.readBoolean() ? readTransfer(input, schema) : null;
            int resolvedAll = input.readInt();
            String detail = input.readUTF();
            ArrayList<String> visited = new ArrayList<>();
            if (schema >= 2) {
                int count = boundedCount(input.readInt(), "visited storage");
                for (int index = 0; index < count; index++) visited.add(input.readUTF());
            }
            if (input.read() != -1) throw new IllegalArgumentException("trailing Home supply payload");
            return new State(
                    phase, cycle, catalog, generation, homeFingerprint, dimension,
                    x, y, z, chest, table, furnace, pins, requested, allocated,
                    withdrawals, withdrawn, transfer, resolvedAll, detail, visited);
        }
    }

    private static void writeTransfer(DataOutputStream output, PendingTransfer transfer)
            throws IOException {
        output.writeUTF(transfer.id());
        output.writeUTF(transfer.stableOwner());
        output.writeUTF(transfer.stackId());
        output.writeUTF(transfer.item());
        output.writeInt(transfer.count());
        output.writeInt(transfer.syncId());
        output.writeInt(transfer.sourceSlot());
        output.writeInt(transfer.destinationSlot());
        output.writeInt(transfer.destinationCountBefore());
        output.writeInt(transfer.playerCountBefore());
        output.writeInt(transfer.chestCountBefore());
        output.writeLong(transfer.startedAtMillis());
        HomeEconomySession.ContainerIdentity identity = transfer.containerIdentity();
        output.writeBoolean(identity != null);
        if (identity != null) {
            output.writeUTF(identity.storageId());
            output.writeInt(identity.halves().size());
            for (HomeEconomySession.ChestHalf half : identity.halves()) {
                output.writeUTF(half.position().dimension());
                output.writeInt(half.position().x());
                output.writeInt(half.position().y());
                output.writeInt(half.position().z());
                output.writeUTF(half.blockId());
                output.writeUTF(half.facing());
                output.writeUTF(half.chestType());
            }
        }
    }

    private static PendingTransfer readTransfer(DataInputStream input, int schema) throws IOException {
        return new PendingTransfer(
                input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt(), input.readLong(),
                schema >= 2 ? readContainerIdentity(input) : null);
    }

    private static HomeEconomySession.ContainerIdentity readContainerIdentity(DataInputStream input)
            throws IOException {
        if (!input.readBoolean()) return null;
        String storageId = input.readUTF();
        int count = input.readInt();
        if (count < 1 || count > 2) throw new IllegalArgumentException("invalid mission container half count");
        ArrayList<HomeEconomySession.ChestHalf> halves = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            halves.add(new HomeEconomySession.ChestHalf(new FieldKitLedger.Position(
                    input.readUTF(), input.readInt(), input.readInt(), input.readInt()),
                    input.readUTF(), input.readUTF(), input.readUTF()));
        }
        return new HomeEconomySession.ContainerIdentity(storageId, halves);
    }

    private static List<String> immutableLedgerIds(List<String> source) {
        Objects.requireNonNull(source, "visitedStorageLedgerIds");
        boundedCount(source.size(), "visited storage");
        ArrayList<String> result = new ArrayList<>();
        HashSet<String> unique = new HashSet<>();
        for (String raw : source) {
            String id = requireText(raw, "visited storage ledger ID", 256);
            if (!unique.add(id)) throw new IllegalArgumentException("duplicate visited storage ledger ID");
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static void writeCounts(DataOutputStream output, Map<String, Integer> counts)
            throws IOException {
        output.writeInt(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            output.writeUTF(entry.getKey());
            output.writeInt(entry.getValue());
        }
    }

    private static Map<String, Integer> readCounts(DataInputStream input) throws IOException {
        int size = boundedCount(input.readInt(), "count map");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            String item = input.readUTF();
            int count = input.readInt();
            if (result.putIfAbsent(item, count) != null) {
                throw new IllegalArgumentException("duplicate Home supply count " + item);
            }
        }
        return immutableCounts(result, "decodedCounts");
    }

    private static int boundedCount(int count, String field) {
        if (count < 0 || count > MAXIMUM_ENTRIES) {
            throw new IllegalArgumentException("invalid " + field + " size " + count);
        }
        return count;
    }

    private static Map<String, Integer> immutableCounts(
            Map<String, Integer> source,
            String field) {
        Objects.requireNonNull(source, field);
        if (source.size() > MAXIMUM_ENTRIES) {
            throw new IllegalArgumentException(field + " exceeds maximum entries");
        }
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        source.forEach((raw, count) -> {
            String item = normalizeItem(raw);
            if (count == null || count <= 0 || count > 1_000_000) {
                throw new IllegalArgumentException(field + " contains invalid count");
            }
            if (result.putIfAbsent(item, count) != null) {
                throw new IllegalArgumentException(field + " contains duplicate " + item);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static void validateAllocationTruth(
            Map<String, Integer> allocated,
            List<Withdrawal> withdrawals,
            Map<String, Integer> withdrawn,
            PendingTransfer pending) {
        HashSet<String> stackIds = new HashSet<>();
        HashSet<Integer> sourceSlots = new HashSet<>();
        LinkedHashMap<String, Integer> totals = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> remaining = new LinkedHashMap<>();
        Withdrawal pendingWithdrawal = null;
        for (Withdrawal withdrawal : withdrawals) {
            if (!stackIds.add(withdrawal.stackId())) {
                throw new IllegalArgumentException(
                        "duplicate Home supply withdrawal stack " + withdrawal.stackId());
            }
            if (!sourceSlots.add(withdrawal.sourceSlot())) {
                throw new IllegalArgumentException(
                        "duplicate Home supply withdrawal source slot "
                                + withdrawal.sourceSlot());
            }
            totals.merge(withdrawal.item(), withdrawal.totalCount(), Math::addExact);
            if (withdrawal.remainingCount() > 0) {
                remaining.merge(
                        withdrawal.item(), withdrawal.remainingCount(), Math::addExact);
            }
            if (pending != null && pending.stackId().equals(withdrawal.stackId())) {
                pendingWithdrawal = withdrawal;
            }
        }
        if (!totals.equals(allocated)) {
            throw new IllegalArgumentException(
                    "Home supply allocation does not equal immutable withdrawal totals");
        }
        LinkedHashMap<String, Integer> conserved = new LinkedHashMap<>();
        Set<String> items = new HashSet<>();
        items.addAll(remaining.keySet());
        items.addAll(withdrawn.keySet());
        for (String item : items) {
            int total = Math.addExact(
                    remaining.getOrDefault(item, 0), withdrawn.getOrDefault(item, 0));
            if (total > 0) conserved.put(item, total);
        }
        if (!conserved.equals(allocated)) {
            throw new IllegalArgumentException(
                    "Home supply allocation is not conserved by withdrawn plus remaining counts");
        }
        if (pending != null) {
            if (pendingWithdrawal == null
                    || !pendingWithdrawal.item().equals(pending.item())
                    || pendingWithdrawal.sourceSlot() != pending.sourceSlot()
                    || pending.count() > pendingWithdrawal.remainingCount()) {
                throw new IllegalArgumentException(
                        "Home supply pending transfer does not match a remaining withdrawal");
            }
        }
    }

    private static String normalizeItem(String value) {
        String item = requireText(value, "item", 256).toLowerCase(java.util.Locale.ROOT)
                .replace(' ', '_');
        return item.startsWith("minecraft:") ? item.substring(10) : item;
    }

    private static String requireText(String value, String field, int maximumLength) {
        String text = Objects.requireNonNull(value, field).trim();
        if (text.isEmpty() || text.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be 1.." + maximumLength + " chars");
        }
        return text;
    }
}
