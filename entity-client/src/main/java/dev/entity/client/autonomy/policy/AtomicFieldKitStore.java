package dev.entity.client.autonomy.policy;

import dev.entity.core.persistence.AtomicStoreRecovery;

import java.io.ByteArrayInputStream;
import java.io.BufferedOutputStream;
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

/** Crash-safe, dependency-free binary persistence for {@link FieldKitLedger}. */
public final class AtomicFieldKitStore implements FieldKitStore {
    static final int MAGIC = 0x4532464B; // E2FK
    static final int SCHEMA_VERSION = 1;
    private static final int MAXIMUM_ASSETS = 4096;

    private final Path path;
    private final Path temporaryPath;

    public AtomicFieldKitStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    @Override
    public synchronized FieldKitLedger.Snapshot load() throws IOException {
        return AtomicStoreRecovery.load(
                path,
                temporaryPath,
                "field-kit store",
                FieldKitLedger.Snapshot::empty,
                AtomicFieldKitStore::decodeStore);
    }

    private static AtomicStoreRecovery.Decoded<FieldKitLedger.Snapshot> decodeStore(
            byte[] encoded,
            Path source) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid field-kit file magic");
            int schema = input.readInt();
            if (schema != SCHEMA_VERSION) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "field-kit store", schema);
            }
            long revision = input.readLong();
            if (revision < 0) throw new IOException("negative field-kit revision");
            int count = input.readInt();
            if (count < 0 || count > MAXIMUM_ASSETS) {
                throw new IOException("invalid field-kit asset count " + count);
            }
            LinkedHashMap<String, FieldKitLedger.Asset> assets = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                FieldKitLedger.Asset asset = readAsset(input);
                if (assets.putIfAbsent(asset.id(), asset) != null) {
                    throw new IOException("duplicate field-kit asset " + asset.id());
                }
            }
            if (input.read() != -1) throw new IOException("trailing field-kit data");
            FieldKitLedger.Snapshot snapshot = new FieldKitLedger.Snapshot(revision, assets);
            return new AtomicStoreRecovery.Decoded<>(snapshot, snapshot.revision(), schema);
        } catch (EOFException error) {
            throw new IOException("truncated field-kit ledger " + source, error);
        } catch (IllegalArgumentException error) {
            throw new IOException("corrupt field-kit ledger " + source, error);
        }
    }

    @Override
    public synchronized void save(FieldKitLedger.Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.assets().size() > MAXIMUM_ASSETS) {
            throw new IOException("too many field-kit assets");
        }
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);

        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporaryPath, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)))) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            output.writeLong(snapshot.revision());
            output.writeInt(snapshot.assets().size());
            for (FieldKitLedger.Asset asset : snapshot.assets().values()) writeAsset(output, asset);
        }
        try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        AtomicFileCommit.replace(temporaryPath, path);
    }

    private static void writeAsset(DataOutputStream output, FieldKitLedger.Asset asset)
            throws IOException {
        output.writeUTF(asset.id());
        output.writeUTF(asset.kind().name());
        output.writeUTF(asset.state().name());
        output.writeUTF(asset.verification().name());
        output.writeBoolean(asset.lastKnownPosition() != null);
        if (asset.lastKnownPosition() != null) {
            output.writeUTF(asset.lastKnownPosition().dimension());
            output.writeInt(asset.lastKnownPosition().x());
            output.writeInt(asset.lastKnownPosition().y());
            output.writeInt(asset.lastKnownPosition().z());
        }
        output.writeLong(asset.updatedAtMillis());
        output.writeLong(asset.verifiedAtMillis());
        output.writeInt(asset.recoveryAttempts());
        output.writeUTF(asset.recoveryReason());
    }

    private static FieldKitLedger.Asset readAsset(DataInputStream input) throws IOException {
        String id = input.readUTF();
        FieldKitLedger.AssetKind kind = enumValue(
                FieldKitLedger.AssetKind.class, input.readUTF(), "asset kind");
        FieldKitLedger.AssetState state = enumValue(
                FieldKitLedger.AssetState.class, input.readUTF(), "asset state");
        FieldKitLedger.Verification verification = enumValue(
                FieldKitLedger.Verification.class, input.readUTF(), "verification");
        FieldKitLedger.Position position = null;
        if (input.readBoolean()) {
            position = new FieldKitLedger.Position(
                    input.readUTF(), input.readInt(), input.readInt(), input.readInt());
        }
        return new FieldKitLedger.Asset(id, kind, state, verification, position,
                input.readLong(), input.readLong(), input.readInt(), input.readUTF());
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field)
            throws IOException {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw new IOException("unknown " + field + " " + value, error);
        }
    }
}
