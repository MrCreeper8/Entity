package dev.entity.client.autonomy.policy;

import dev.entity.core.persistence.AtomicStoreRecovery;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.zip.CRC32;

/** Checksummed atomic store for the single outstanding Home water-fill intent. */
public final class AtomicHomeWaterFillStore implements HomeWaterFillSession.Store {
    static final int MAGIC = 0x45324857; // E2HW
    static final int SCHEMA_VERSION = 2;
    private static final int MAXIMUM_FILE_BYTES = 65_536;

    private final Path path;
    private final Path temporaryPath;

    public AtomicHomeWaterFillStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    @Override
    public synchronized HomeWaterFillSession.Snapshot load() throws IOException {
        return AtomicStoreRecovery.load(
                path,
                temporaryPath,
                "Home water-fill store",
                HomeWaterFillSession.Snapshot::empty,
                AtomicHomeWaterFillStore::decodeStore);
    }

    private static AtomicStoreRecovery.Decoded<HomeWaterFillSession.Snapshot> decodeStore(
            byte[] encoded,
            Path source) throws IOException {
        long size = encoded.length;
        if (size <= 0L || size > MAXIMUM_FILE_BYTES) {
            throw new IOException("invalid Home water-fill file size " + size);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid water-fill file magic");
            int schema = input.readInt();
            if (schema < 1 || schema > SCHEMA_VERSION) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "Home water-fill store", schema);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength != encoded.length - 20) {
                throw new IOException("invalid Home water-fill payload length");
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength) throw new EOFException("truncated payload");
            long expected = input.readLong();
            if (input.read() != -1) throw new IOException("trailing Home water-fill bytes");
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != expected) {
                throw new IOException("Home water-fill checksum mismatch");
            }
            HomeWaterFillSession.Snapshot snapshot = decode(payload, schema);
            return new AtomicStoreRecovery.Decoded<>(snapshot, snapshot.revision(), schema);
        } catch (EOFException error) {
            throw new IOException("truncated Home water-fill store " + source, error);
        } catch (IllegalArgumentException error) {
            throw new IOException("corrupt Home water-fill store " + source, error);
        }
    }

    @Override
    public synchronized void save(HomeWaterFillSession.Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        byte[] payload = encode(snapshot);
        CRC32 crc = new CRC32();
        crc.update(payload);
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporaryPath, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)))) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.writeLong(crc.getValue());
        }
        try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        AtomicFileCommit.replace(temporaryPath, path);
    }

    private static byte[] encode(HomeWaterFillSession.Snapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(snapshot.revision());
            HomeWaterFillSession.Intent intent = snapshot.intent();
            output.writeBoolean(intent != null);
            if (intent != null) {
                output.writeUTF(intent.id());
                output.writeLong(intent.homeGeneration());
                output.writeUTF(intent.homeFingerprint());
                output.writeUTF(intent.dimension());
                output.writeInt(intent.sourceX());
                output.writeInt(intent.sourceY());
                output.writeInt(intent.sourceZ());
                output.writeInt(intent.renewableNeighborMask());
                output.writeInt(intent.emptyBucketBefore());
                output.writeInt(intent.waterBucketBefore());
                output.writeInt(intent.hotbarSlot());
                output.writeInt(intent.previousSelectedSlot());
                output.writeUTF(intent.phase().name());
                output.writeInt(intent.attempts());
                output.writeLong(intent.useIssuedAtMillis());
                output.writeUTF(intent.detail());
                output.writeLong(intent.startedAtMillis());
                output.writeUTF(intent.callerKind().name());
                output.writeUTF(intent.missionId());
                output.writeUTF(intent.actionId());
                output.writeUTF(intent.sourceState());
                output.writeBoolean(intent.fenced());
            }
            output.writeLong(snapshot.updatedAtMillis());
        }
        return bytes.toByteArray();
    }

    private static HomeWaterFillSession.Snapshot decode(byte[] payload, int schema) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long revision = input.readLong();
            HomeWaterFillSession.Intent intent = null;
            if (input.readBoolean()) {
                String id = input.readUTF();
                long generation = input.readLong();
                String fingerprint = input.readUTF();
                String dimension = input.readUTF();
                int x = input.readInt();
                int y = input.readInt();
                int z = input.readInt();
                int mask = input.readInt();
                int emptyBefore = input.readInt();
                int waterBefore = input.readInt();
                int slot = input.readInt();
                int previousSelectedSlot = input.readInt();
                HomeWaterFillSession.Phase phase;
                try {
                    phase = HomeWaterFillSession.Phase.valueOf(input.readUTF());
                } catch (IllegalArgumentException error) {
                    throw new IOException("unknown Home water-fill phase", error);
                }
                int attempts = input.readInt();
                long issuedAt = input.readLong();
                String detail = input.readUTF();
                long startedAt = input.readLong();
                HomeWaterFillSession.CallerKind caller = schema >= 2
                        ? HomeWaterFillSession.CallerKind.valueOf(input.readUTF()) : HomeWaterFillSession.CallerKind.HOME;
                String missionId = schema >= 2 ? input.readUTF() : "";
                String actionId = schema >= 2 ? input.readUTF() : "";
                String sourceState = schema >= 2 ? input.readUTF() : "";
                boolean fenced = schema >= 2 && input.readBoolean();
                intent = new HomeWaterFillSession.Intent(
                        id, generation, fingerprint, dimension, x, y, z, mask,
                        emptyBefore, waterBefore, slot, previousSelectedSlot,
                        phase, attempts, issuedAt,
                        detail, startedAt, caller, missionId, actionId, sourceState, fenced);
            }
            long updatedAt = input.readLong();
            if (input.read() != -1) throw new IOException("trailing water-fill payload");
            return new HomeWaterFillSession.Snapshot(revision, intent, updatedAt);
        }
    }
}
