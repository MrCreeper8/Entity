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
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.zip.CRC32;

/** Checksummed sibling-temporary atomic persistence for {@link FunctionalRouteMemory}. */
public final class AtomicFunctionalRouteStore implements FunctionalRouteMemory.Store {
    static final int MAGIC = 0x45324652; // E2FR
    static final int SCHEMA_VERSION = 1;
    private static final int HEADER_AND_CHECKSUM_BYTES = 20;
    private static final int MAXIMUM_FILE_BYTES = 32 * 1_024 * 1_024;

    private final Path path;
    private final Path temporaryPath;

    public AtomicFunctionalRouteStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    @Override
    public synchronized FunctionalRouteMemory.Snapshot load() throws IOException {
        return AtomicStoreRecovery.load(
                path,
                temporaryPath,
                "functional-route store",
                FunctionalRouteMemory.Snapshot::empty,
                AtomicFunctionalRouteStore::decodeStore);
    }

    @Override
    public synchronized void save(FunctionalRouteMemory.Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        // Reconstructing the snapshot validates all public-record invariants before disk credit.
        FunctionalRouteMemory.Snapshot checked = new FunctionalRouteMemory.Snapshot(
                snapshot.revision(), snapshot.worldIdentity(), snapshot.routes());
        byte[] payload = encode(checked);
        if (payload.length + HEADER_AND_CHECKSUM_BYTES > MAXIMUM_FILE_BYTES) {
            throw new IOException("functional-route store exceeds bounded file size");
        }
        CRC32 crc = new CRC32();
        crc.update(payload);
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(
                        temporaryPath,
                        StandardOpenOption.CREATE,
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

    private static AtomicStoreRecovery.Decoded<FunctionalRouteMemory.Snapshot> decodeStore(
            byte[] encoded,
            Path source) throws IOException {
        if (encoded.length < HEADER_AND_CHECKSUM_BYTES
                || encoded.length > MAXIMUM_FILE_BYTES) {
            throw new IOException("invalid functional-route file size " + encoded.length);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("invalid functional-route file magic");
            }
            int schema = input.readInt();
            if (schema != SCHEMA_VERSION) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "functional-route store", schema);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0
                    || payloadLength != encoded.length - HEADER_AND_CHECKSUM_BYTES) {
                throw new IOException("invalid functional-route payload length");
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength) throw new EOFException("truncated payload");
            long expectedChecksum = input.readLong();
            if (input.read() != -1) throw new IOException("trailing functional-route bytes");
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != expectedChecksum) {
                throw new IOException("functional-route checksum mismatch");
            }
            FunctionalRouteMemory.Snapshot snapshot = decode(payload);
            return new AtomicStoreRecovery.Decoded<>(
                    snapshot, snapshot.revision(), schema);
        } catch (EOFException error) {
            throw new IOException("truncated functional-route store " + source, error);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("corrupt functional-route store " + source, error);
        }
    }

    private static byte[] encode(FunctionalRouteMemory.Snapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(snapshot.revision());
            output.writeBoolean(snapshot.worldIdentity() != null);
            if (snapshot.worldIdentity() != null) {
                writeWorld(output, snapshot.worldIdentity());
            }
            output.writeInt(snapshot.routes().size());
            for (FunctionalRouteMemory.Route route : snapshot.routes().values()) {
                output.writeUTF(route.id());
                writeWorld(output, route.worldIdentity());
                output.writeUTF(route.dimension());
                writeCell(output, route.entrance());
                writeCell(output, route.lastSafeCell());
                output.writeLong(route.createdAtMillis());
                output.writeLong(route.updatedAtMillis());
                output.writeInt(route.cells().size());
                for (FunctionalRouteMemory.OccupiedSafeCell cell : route.cells()) {
                    writeCell(output, cell);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static FunctionalRouteMemory.Snapshot decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long revision = input.readLong();
            if (revision < 0L) throw new IOException("negative functional-route revision");
            FunctionalRouteMemory.WorldIdentity snapshotWorld = input.readBoolean()
                    ? readWorld(input) : null;
            int routeCount = input.readInt();
            if (routeCount < 0 || routeCount > FunctionalRouteMemory.MAXIMUM_ROUTES) {
                throw new IOException("invalid functional-route count " + routeCount);
            }
            LinkedHashMap<String, FunctionalRouteMemory.Route> routes = new LinkedHashMap<>();
            int totalCells = 0;
            for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) {
                String id = input.readUTF();
                FunctionalRouteMemory.WorldIdentity world = readWorld(input);
                String dimension = input.readUTF();
                FunctionalRouteMemory.OccupiedSafeCell entrance = readCell(input);
                FunctionalRouteMemory.OccupiedSafeCell lastSafe = readCell(input);
                long createdAtMillis = input.readLong();
                long updatedAtMillis = input.readLong();
                int cellCount = input.readInt();
                if (cellCount < 1
                        || cellCount > FunctionalRouteMemory.MAXIMUM_CELLS_PER_ROUTE) {
                    throw new IOException("invalid route cell count " + cellCount);
                }
                totalCells = Math.addExact(totalCells, cellCount);
                if (totalCells > FunctionalRouteMemory.MAXIMUM_TOTAL_CELLS) {
                    throw new IOException("too many total functional-route cells");
                }
                java.util.ArrayList<FunctionalRouteMemory.OccupiedSafeCell> cells =
                        new java.util.ArrayList<>(cellCount);
                for (int cellIndex = 0; cellIndex < cellCount; cellIndex++) {
                    cells.add(readCell(input));
                }
                FunctionalRouteMemory.Route route = new FunctionalRouteMemory.Route(
                        id, world, dimension, entrance, lastSafe, cells,
                        createdAtMillis, updatedAtMillis);
                if (routes.putIfAbsent(route.id(), route) != null) {
                    throw new IOException("duplicate functional route " + route.id());
                }
            }
            if (input.read() != -1) {
                throw new IOException("trailing functional-route payload");
            }
            return new FunctionalRouteMemory.Snapshot(revision, snapshotWorld, routes);
        }
    }

    private static void writeWorld(
            DataOutputStream output,
            FunctionalRouteMemory.WorldIdentity world) throws IOException {
        output.writeUTF(world.serverIdentity());
        output.writeUTF(world.worldIdentity());
    }

    private static FunctionalRouteMemory.WorldIdentity readWorld(DataInputStream input)
            throws IOException {
        return new FunctionalRouteMemory.WorldIdentity(input.readUTF(), input.readUTF());
    }

    private static void writeCell(
            DataOutputStream output,
            FunctionalRouteMemory.OccupiedSafeCell cell) throws IOException {
        output.writeUTF(cell.coordinate().dimension());
        output.writeInt(cell.coordinate().x());
        output.writeInt(cell.coordinate().y());
        output.writeInt(cell.coordinate().z());
        output.writeUTF(cell.stableFingerprint());
        output.writeLong(cell.observedAtMillis());
    }

    private static FunctionalRouteMemory.OccupiedSafeCell readCell(DataInputStream input)
            throws IOException {
        FunctionalRouteMemory.Coordinate coordinate = new FunctionalRouteMemory.Coordinate(
                input.readUTF(), input.readInt(), input.readInt(), input.readInt());
        return new FunctionalRouteMemory.OccupiedSafeCell(
                coordinate, input.readUTF(), input.readLong());
    }
}
