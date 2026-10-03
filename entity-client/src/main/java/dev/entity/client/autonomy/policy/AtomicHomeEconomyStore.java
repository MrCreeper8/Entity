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
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/** Checksummed, crash-safe binary persistence for {@link HomeEconomySession}. */
public final class AtomicHomeEconomyStore implements HomeEconomyStore {
    static final int MAGIC = 0x45324845; // E2HE
    static final int LEGACY_SCHEMA_VERSION = 1;
    static final int SCHEMA_VERSION = 3;
    private static final int MAXIMUM_FILE_BYTES = 1_048_576;
    private static final int MAXIMUM_GOALS = 64;
    private static final int MAXIMUM_STORAGE_REGISTRATIONS = 4096;

    private final Path path;
    private final Path temporaryPath;

    public AtomicHomeEconomyStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    }

    @Override
    public synchronized HomeEconomySession.Snapshot load() throws IOException {
        return AtomicStoreRecovery.load(
                path,
                temporaryPath,
                "home-economy store",
                HomeEconomySession.Snapshot::empty,
                AtomicHomeEconomyStore::decodeStore);
    }

    private static AtomicStoreRecovery.Decoded<HomeEconomySession.Snapshot> decodeStore(
            byte[] encoded,
            Path source) throws IOException {
        long size = encoded.length;
        if (size <= 0 || size > MAXIMUM_FILE_BYTES) {
            throw new IOException("invalid home-economy file size " + size);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid home-economy file magic");
            int schema = input.readInt();
            if (schema < LEGACY_SCHEMA_VERSION || schema > SCHEMA_VERSION) {
                throw new AtomicStoreRecovery.UnsupportedSchemaException(
                        "home-economy store", schema);
            }
            int payloadLength = input.readInt();
            int remainingForPayload = encoded.length - 4 - 4 - 4 - 8;
            if (payloadLength < 0 || payloadLength != remainingForPayload) {
                throw new IOException("invalid home-economy payload length " + payloadLength);
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength) throw new EOFException("truncated payload");
            long expectedChecksum = input.readLong();
            if (input.read() != -1) throw new IOException("trailing home-economy data");
            CRC32 checksum = new CRC32();
            checksum.update(payload);
            if (checksum.getValue() != expectedChecksum) {
                throw new IOException("home-economy checksum mismatch");
            }
            HomeEconomySession.Snapshot snapshot = readSnapshot(payload, schema);
            return new AtomicStoreRecovery.Decoded<>(snapshot, snapshot.revision(), schema);
        } catch (EOFException error) {
            throw new IOException("truncated home-economy store " + source, error);
        } catch (IllegalArgumentException error) {
            throw new IOException("corrupt home-economy store " + source, error);
        }
    }

    @Override
    public synchronized void save(HomeEconomySession.Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.storageRegistrations().size() > MAXIMUM_STORAGE_REGISTRATIONS) {
            throw new IOException("too many Home storage registrations");
        }
        byte[] payload = writeSnapshot(snapshot);
        if (payload.length > MAXIMUM_FILE_BYTES - 20) {
            throw new IOException("home-economy snapshot is too large");
        }
        CRC32 checksum = new CRC32();
        checksum.update(payload);

        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporaryPath, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)))) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.writeLong(checksum.getValue());
        }
        try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        AtomicFileCommit.replace(temporaryPath, path);
    }

    private static byte[] writeSnapshot(HomeEconomySession.Snapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(snapshot.revision());
            output.writeBoolean(snapshot.enabled());
            output.writeLong(snapshot.generation());
            output.writeUTF(snapshot.phase().name());
            writeHome(output, snapshot.home());

            output.writeInt(snapshot.pinnedAssets().size());
            for (HomeEconomySession.AssetRole role : HomeEconomySession.AssetRole.values()) {
                HomeEconomySession.PinnedAsset asset = snapshot.pinnedAssets().get(role);
                if (asset == null) continue;
                output.writeUTF(role.name());
                output.writeUTF(asset.ledgerAssetId());
                output.writeLong(asset.pinnedAtMillis());
            }
            writeTransfer(output, snapshot.pendingTransfer());
            writeAcquisition(output, snapshot.pendingAcquisition());
            writePause(output, snapshot.pause());
            writeBlock(output, snapshot.block());
            output.writeLong(snapshot.updatedAtMillis());
            output.writeInt(snapshot.storageRegistrations().size());
            for (HomeEconomySession.StorageRegistration registration : snapshot.storageRegistrations().values()) {
                output.writeUTF(registration.id());
                output.writeUTF(registration.ledgerAssetId());
                output.writeLong(registration.registeredAtMillis());
            }
            output.writeBoolean(snapshot.storageExplicitlyMissing());
        }
        return bytes.toByteArray();
    }

    private static HomeEconomySession.Snapshot readSnapshot(byte[] payload, int schema)
            throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long revision = input.readLong();
            boolean enabled = input.readBoolean();
            long generation = input.readLong();
            HomeEconomySession.Phase phase = enumValue(
                    HomeEconomySession.Phase.class, input.readUTF(), "phase");
            HomeEconomySession.HomeAnchor home = readHome(input);

            int assetCount = input.readInt();
            if (assetCount < 0 || assetCount > HomeEconomySession.AssetRole.values().length) {
                throw new IOException("invalid pinned home asset count " + assetCount);
            }
            EnumMap<HomeEconomySession.AssetRole, HomeEconomySession.PinnedAsset> assets =
                    new EnumMap<>(HomeEconomySession.AssetRole.class);
            for (int index = 0; index < assetCount; index++) {
                HomeEconomySession.AssetRole role = enumValue(
                        HomeEconomySession.AssetRole.class, input.readUTF(), "asset role");
                HomeEconomySession.PinnedAsset asset = new HomeEconomySession.PinnedAsset(
                        role, input.readUTF(), input.readLong());
                if (assets.putIfAbsent(role, asset) != null) {
                    throw new IOException("duplicate pinned home asset " + role);
                }
            }
            HomeEconomySession.PendingTransfer transfer = readTransfer(input, schema);
            HomeEconomySession.PendingAcquisition acquisition = readAcquisition(input);
            HomeEconomySession.Pause pause = readPause(input);
            HomeEconomySession.Block block = readBlock(input);
            long updatedAtMillis = input.readLong();
            Map<String, HomeEconomySession.StorageRegistration> storage = null;
            boolean storageExplicitlyMissing = false;
            if (schema >= 3) {
                int count = input.readInt();
                if (count < 0 || count > MAXIMUM_STORAGE_REGISTRATIONS) {
                    throw new IOException("invalid Home storage registration count " + count);
                }
                storage = new LinkedHashMap<>();
                for (int index = 0; index < count; index++) {
                    var registration = new HomeEconomySession.StorageRegistration(
                            input.readUTF(), input.readUTF(), input.readLong());
                    if (storage.putIfAbsent(registration.id(), registration) != null) {
                        throw new IOException("duplicate Home storage ID " + registration.id());
                    }
                }
                storageExplicitlyMissing = input.readBoolean();
            }
            if (input.read() != -1) throw new IOException("trailing snapshot payload");
            if (schema == LEGACY_SCHEMA_VERSION
                    && enabled && home != null
                    && !assets.containsKey(HomeEconomySession.AssetRole.BED)) {
                phase = migrateLegacyReadyPhase(phase);
                pause = migrateLegacyReadyPause(pause);
                block = migrateLegacyReadyBlock(block);
            }
            return schema < 3
                    ? new HomeEconomySession.Snapshot(revision, enabled, generation, phase,
                    home, assets, transfer, acquisition, pause, block, updatedAtMillis)
                    : new HomeEconomySession.Snapshot(revision, enabled, generation, phase,
                    home, assets, transfer, acquisition, pause, block, updatedAtMillis,
                    storage, storageExplicitlyMissing);
        }
    }

    /**
     * Version 1 considered table/furnace/chest a complete Home. Version 2 adds
     * an owned bed, so an idle legacy Home must provision that fourth asset
     * instead of failing to load or falsely remaining READY.
     */
    static HomeEconomySession.Phase migrateLegacyReadyPhase(
            HomeEconomySession.Phase phase) {
        return phase == HomeEconomySession.Phase.READY
                || phase == HomeEconomySession.Phase.DEGRADED_READY
                ? HomeEconomySession.Phase.PROVISIONING_ASSETS : phase;
    }

    static HomeEconomySession.Pause migrateLegacyReadyPause(
            HomeEconomySession.Pause pause) {
        if (pause == null) return null;
        HomeEconomySession.Phase resume = migrateLegacyReadyPhase(pause.resumePhase());
        return resume == pause.resumePhase() ? pause
                : new HomeEconomySession.Pause(
                        resume, pause.reason(), pause.pausedAtMillis());
    }

    static HomeEconomySession.Block migrateLegacyReadyBlock(
            HomeEconomySession.Block block) {
        if (block == null) return null;
        HomeEconomySession.Phase retry = migrateLegacyReadyPhase(block.retryPhase());
        return retry == block.retryPhase() ? block
                : new HomeEconomySession.Block(
                        retry, block.code(), block.detail(), block.retryable(),
                        block.blockedAtMillis());
    }

    private static void writeHome(
            DataOutputStream output,
            HomeEconomySession.HomeAnchor home) throws IOException {
        output.writeBoolean(home != null);
        if (home == null) return;
        output.writeUTF(home.dimension());
        output.writeInt(home.x());
        output.writeInt(home.y());
        output.writeInt(home.z());
        output.writeUTF(home.fingerprint());
    }

    private static HomeEconomySession.HomeAnchor readHome(DataInputStream input)
            throws IOException {
        if (!input.readBoolean()) return null;
        return new HomeEconomySession.HomeAnchor(input.readUTF(), input.readInt(),
                input.readInt(), input.readInt(), input.readUTF());
    }

    private static void writeTransfer(
            DataOutputStream output,
            HomeEconomySession.PendingTransfer transfer) throws IOException {
        output.writeBoolean(transfer != null);
        if (transfer == null) return;
        output.writeUTF(transfer.id());
        output.writeUTF(transfer.direction().name());
        output.writeUTF(transfer.categoryId());
        output.writeUTF(transfer.item());
        output.writeInt(transfer.count());
        output.writeInt(transfer.syncId());
        output.writeInt(transfer.sourceSlot());
        output.writeInt(transfer.destinationSlot());
        output.writeInt(transfer.destinationCountBefore());
        output.writeInt(transfer.playerCountBefore());
        output.writeInt(transfer.chestCountBefore());
        output.writeLong(transfer.startedAtMillis());
        writeContainerIdentity(output, transfer.containerIdentity());
    }

    private static HomeEconomySession.PendingTransfer readTransfer(DataInputStream input, int schema)
            throws IOException {
        if (!input.readBoolean()) return null;
        return new HomeEconomySession.PendingTransfer(
                input.readUTF(),
                enumValue(HomeStockPolicy.Direction.class, input.readUTF(), "transfer direction"),
                input.readUTF(), input.readUTF(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.readLong(), schema >= 3 ? readContainerIdentity(input) : null);
    }

    private static void writeContainerIdentity(DataOutputStream output,
            HomeEconomySession.ContainerIdentity identity) throws IOException {
        output.writeBoolean(identity != null);
        if (identity == null) return;
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

    private static HomeEconomySession.ContainerIdentity readContainerIdentity(DataInputStream input)
            throws IOException {
        if (!input.readBoolean()) return null;
        String storageId = input.readUTF();
        int count = input.readInt();
        if (count < 1 || count > 2) throw new IOException("invalid connected chest half count " + count);
        ArrayList<HomeEconomySession.ChestHalf> halves = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            halves.add(new HomeEconomySession.ChestHalf(new FieldKitLedger.Position(
                    input.readUTF(), input.readInt(), input.readInt(), input.readInt()),
                    input.readUTF(), input.readUTF(), input.readUTF()));
        }
        return new HomeEconomySession.ContainerIdentity(storageId, halves);
    }

    private static void writeAcquisition(
            DataOutputStream output,
            HomeEconomySession.PendingAcquisition acquisition) throws IOException {
        output.writeBoolean(acquisition != null);
        if (acquisition == null) return;
        output.writeUTF(acquisition.planId());
        output.writeInt(acquisition.goals().size());
        for (Map.Entry<String, Integer> goal : acquisition.goals().entrySet()) {
            output.writeUTF(goal.getKey());
            output.writeInt(goal.getValue());
        }
        output.writeLong(acquisition.startedAtMillis());
    }

    private static HomeEconomySession.PendingAcquisition readAcquisition(DataInputStream input)
            throws IOException {
        if (!input.readBoolean()) return null;
        String planId = input.readUTF();
        int count = input.readInt();
        if (count <= 0 || count > MAXIMUM_GOALS) {
            throw new IOException("invalid home acquisition goal count " + count);
        }
        LinkedHashMap<String, Integer> goals = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String item = input.readUTF();
            int quantity = input.readInt();
            if (goals.putIfAbsent(item, quantity) != null) {
                throw new IOException("duplicate home acquisition goal " + item);
            }
        }
        return new HomeEconomySession.PendingAcquisition(planId, goals, input.readLong());
    }

    private static void writePause(
            DataOutputStream output,
            HomeEconomySession.Pause pause) throws IOException {
        output.writeBoolean(pause != null);
        if (pause == null) return;
        output.writeUTF(pause.resumePhase().name());
        output.writeUTF(pause.reason());
        output.writeLong(pause.pausedAtMillis());
    }

    private static HomeEconomySession.Pause readPause(DataInputStream input) throws IOException {
        if (!input.readBoolean()) return null;
        return new HomeEconomySession.Pause(
                enumValue(HomeEconomySession.Phase.class, input.readUTF(), "resume phase"),
                input.readUTF(), input.readLong());
    }

    private static void writeBlock(
            DataOutputStream output,
            HomeEconomySession.Block block) throws IOException {
        output.writeBoolean(block != null);
        if (block == null) return;
        output.writeUTF(block.retryPhase().name());
        output.writeUTF(block.code());
        output.writeUTF(block.detail());
        output.writeBoolean(block.retryable());
        output.writeLong(block.blockedAtMillis());
    }

    private static HomeEconomySession.Block readBlock(DataInputStream input) throws IOException {
        if (!input.readBoolean()) return null;
        return new HomeEconomySession.Block(
                enumValue(HomeEconomySession.Phase.class, input.readUTF(), "block retry phase"),
                input.readUTF(), input.readUTF(), input.readBoolean(), input.readLong());
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value,
            String field) throws IOException {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw new IOException("unknown " + field + ' ' + value, error);
        }
    }
}
