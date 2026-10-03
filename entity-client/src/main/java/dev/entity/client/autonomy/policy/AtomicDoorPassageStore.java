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

/** Checksummed atomic store for the single outstanding portal restoration debt. */
public final class AtomicDoorPassageStore implements DoorPassageSession.Store {
    static final int MAGIC = 0x45324450; // E2DP
    static final int SCHEMA_VERSION = 1;
    private static final int MAXIMUM_FILE_BYTES = 65_536;

    private final Path path;
    private final Path temporaryPath;

    public AtomicDoorPassageStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    @Override
    public synchronized DoorPassageSession.Snapshot load() throws IOException {
        return AtomicStoreRecovery.load(
                path, temporaryPath, "door-passage store",
                DoorPassageSession.Snapshot::empty,
                AtomicDoorPassageStore::decodeStore);
    }

    @Override
    public synchronized void save(DoorPassageSession.Snapshot snapshot) throws IOException {
        byte[] payload = encode(Objects.requireNonNull(snapshot, "snapshot"));
        CRC32 crc = new CRC32();
        crc.update(payload);
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporaryPath, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)))) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.writeLong(crc.getValue());
        }
        try (FileChannel channel = FileChannel.open(
                temporaryPath, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        AtomicFileCommit.replace(temporaryPath, path);
    }

    private static AtomicStoreRecovery.Decoded<DoorPassageSession.Snapshot> decodeStore(
            byte[] encoded,
            Path source) throws IOException {
        if (encoded.length <= 0 || encoded.length > MAXIMUM_FILE_BYTES) {
            throw new IOException("invalid door-passage file size " + encoded.length);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("invalid door-passage file magic");
            }
            int schema = input.readInt();
            if (schema != SCHEMA_VERSION) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "door-passage store", schema);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength != encoded.length - 20) {
                throw new IOException("invalid door-passage payload length");
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength) throw new EOFException("truncated payload");
            long expected = input.readLong();
            if (input.read() != -1) throw new IOException("trailing door-passage bytes");
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != expected) {
                throw new IOException("door-passage checksum mismatch");
            }
            DoorPassageSession.Snapshot snapshot = decode(payload);
            return new AtomicStoreRecovery.Decoded<>(snapshot, snapshot.revision(), schema);
        } catch (EOFException error) {
            throw new IOException("truncated door-passage store " + source, error);
        } catch (IllegalArgumentException error) {
            throw new IOException("corrupt door-passage store " + source, error);
        }
    }

    private static byte[] encode(DoorPassageSession.Snapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(snapshot.revision());
            DoorPassageSession.Intent intent = snapshot.intent();
            output.writeBoolean(intent != null);
            if (intent != null) {
                output.writeUTF(intent.id());
                output.writeUTF(intent.missionId());
                output.writeUTF(intent.dimension());
                output.writeInt(intent.x());
                output.writeInt(intent.y());
                output.writeInt(intent.z());
                output.writeUTF(intent.kind());
                output.writeUTF(intent.blockId());
                output.writeUTF(intent.stableFingerprint());
                output.writeInt(intent.normalX());
                output.writeInt(intent.normalY());
                output.writeInt(intent.normalZ());
                output.writeInt(intent.height());
                output.writeInt(intent.approachSign());
                output.writeBoolean(intent.restorationDue());
                output.writeUTF(intent.phase().name());
                output.writeInt(intent.openAttempts());
                output.writeInt(intent.restoreAttempts());
                output.writeLong(intent.actionIssuedAtMillis());
                output.writeLong(intent.startedAtMillis());
                output.writeUTF(intent.detail());
            }
            output.writeLong(snapshot.updatedAtMillis());
        }
        return bytes.toByteArray();
    }

    private static DoorPassageSession.Snapshot decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long revision = input.readLong();
            DoorPassageSession.Intent intent = null;
            if (input.readBoolean()) {
                String id = input.readUTF();
                String missionId = input.readUTF();
                String dimension = input.readUTF();
                int x = input.readInt();
                int y = input.readInt();
                int z = input.readInt();
                String kind = input.readUTF();
                String blockId = input.readUTF();
                String fingerprint = input.readUTF();
                int normalX = input.readInt();
                int normalY = input.readInt();
                int normalZ = input.readInt();
                int height = input.readInt();
                int approachSign = input.readInt();
                boolean restorationDue = input.readBoolean();
                DoorPassageSession.Phase phase;
                try {
                    phase = DoorPassageSession.Phase.valueOf(input.readUTF());
                } catch (IllegalArgumentException error) {
                    throw new IOException("unknown door-passage phase", error);
                }
                int openAttempts = input.readInt();
                int restoreAttempts = input.readInt();
                long issuedAt = input.readLong();
                long startedAt = input.readLong();
                String detail = input.readUTF();
                intent = new DoorPassageSession.Intent(
                        id, missionId, dimension, x, y, z, kind, blockId,
                        fingerprint, normalX, normalY, normalZ, height,
                        approachSign, restorationDue, phase,
                        openAttempts, restoreAttempts,
                        issuedAt, startedAt, detail);
            }
            long updatedAt = input.readLong();
            if (input.read() != -1) throw new IOException("trailing door-passage payload");
            return new DoorPassageSession.Snapshot(revision, intent, updatedAt);
        }
    }
}
