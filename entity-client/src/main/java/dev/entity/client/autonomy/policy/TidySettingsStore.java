package dev.entity.client.autonomy.policy;

import dev.entity.core.persistence.AtomicStoreRecovery;

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
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.CRC32;

/** World-scoped cleanup preferences only. No preview, work queue or issued action is persisted here. */
public final class TidySettingsStore {
    static final int MAGIC = 0x45325459; // E2TY
    static final int SCHEMA_VERSION = 2;
    private static final int MAXIMUM_FILE_BYTES = 131_072;
    private static final int MAXIMUM_RULES = 512;

    /** The owner-confirmed support/fluid and exact landing point, not a permission to modify it. */
    public record DisposalSpot(String worldId, String homeFingerprint, String dimension,
                               int x, int y, int z, String blockId, String blockState,
                               double landingX, double landingY, double landingZ) {
        public DisposalSpot {
            worldId = world(worldId);
            homeFingerprint = text(homeFingerprint, "homeFingerprint", 256);
            dimension = text(dimension, "dimension", 256);
            blockId = exactItem(blockId);
            blockState = text(blockState, "blockState", 16_384);
            if (!Double.isFinite(landingX) || !Double.isFinite(landingY) || !Double.isFinite(landingZ)) {
                throw new IllegalArgumentException("disposal landing must be finite");
            }
        }

        public boolean belongsTo(String currentWorldId, String currentHomeFingerprint) {
            return worldId.equals(currentWorldId) && homeFingerprint.equals(currentHomeFingerprint);
        }
    }

    public record Settings(String worldId, long revision, Set<String> junkItems,
                           Set<String> keepItems, boolean automatic, DisposalSpot spot, int acknowledgedPolicyVersion) {
        public Settings(String worldId, long revision, Set<String> junkItems,
                        Set<String> keepItems, boolean automatic, DisposalSpot spot) {
            this(worldId, revision, junkItems, keepItems, automatic, spot, PersonalSuppliesPolicy.CLEANUP_POLICY_VERSION);
        }
        public Settings {
            worldId = world(worldId);
            if (revision < 0) throw new IllegalArgumentException("negative settings revision");
            if (acknowledgedPolicyVersion < 0 || acknowledgedPolicyVersion > PersonalSuppliesPolicy.CLEANUP_POLICY_VERSION) {
                throw new IllegalArgumentException("unsupported cleanup policy acknowledgement");
            }
            junkItems = rules(junkItems);
            keepItems = rules(keepItems);
            if (!Collections.disjoint(junkItems, keepItems)) {
                throw new IllegalArgumentException("keep and junk rules overlap");
            }
            if (spot != null && !worldId.equals(spot.worldId())) {
                throw new IllegalArgumentException("disposal spot belongs to another world");
            }
        }

        public static Settings defaults(String worldId) {
            return new Settings(worldId, 0, PersonalSuppliesPolicy.DEFAULT_JUNK_ITEMS, Set.of(), false, null);
        }

        public Settings keep(String item) { return withRule(item, false); }
        public Settings junk(String item) { return withRule(item, true); }

        private Settings withRule(String item, boolean junk) {
            String exact = exactItem(item);
            Set<String> newJunk = new TreeSet<>(junkItems);
            Set<String> newKeep = new TreeSet<>(keepItems);
            if (junk) { newJunk.add(exact); newKeep.remove(exact); }
            else { newKeep.add(exact); newJunk.remove(exact); }
            if (newJunk.equals(junkItems) && newKeep.equals(keepItems)) return this;
            return new Settings(worldId, Math.incrementExact(revision), newJunk, newKeep, automatic, spot, acknowledgedPolicyVersion);
        }

        public Settings withAutomatic(boolean enabled) {
            int acknowledgement = enabled ? PersonalSuppliesPolicy.CLEANUP_POLICY_VERSION : acknowledgedPolicyVersion;
            return automatic == enabled && acknowledgedPolicyVersion == acknowledgement ? this
                    : new Settings(worldId, Math.incrementExact(revision), junkItems, keepItems, enabled, spot, acknowledgement);
        }

        /** Call only after the physical spot preview is confirmed by its selecting owner. */
        public Settings withConfirmedSpot(DisposalSpot confirmed) {
            return Objects.equals(spot, confirmed) ? this : new Settings(worldId, Math.incrementExact(revision),
                    junkItems, keepItems, automatic, confirmed, acknowledgedPolicyVersion);
        }

        public boolean hasSpot(String currentWorldId, String currentHomeFingerprint) {
            return spot != null && spot.belongsTo(currentWorldId, currentHomeFingerprint);
        }

        /** Permission only; the caller still owns the safe Home/Stock boundary and command lease. */
        public boolean permitsAutomatic(String currentWorldId, String currentHomeFingerprint) {
            return automatic && !needsPolicyAcknowledgement() && hasSpot(currentWorldId, currentHomeFingerprint);
        }

        public boolean needsPolicyAcknowledgement() {
            return acknowledgedPolicyVersion < PersonalSuppliesPolicy.CLEANUP_POLICY_VERSION;
        }
    }

    /** On failure the defaults are disabled and the diagnostic must be shown; do not silently save it. */
    public record LoadResult(Settings settings, String problem) {
        public boolean healthy() { return problem.isEmpty(); }
    }

    private final Path path;
    private final Path temporaryPath;
    private final String worldId;

    public TidySettingsStore(Path path, String worldId) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
        this.worldId = world(worldId);
    }

    public synchronized Settings load() throws IOException {
        return AtomicStoreRecovery.load(path, temporaryPath, "tidy settings", () -> Settings.defaults(worldId),
                (bytes, source) -> decodeStore(bytes, source, worldId));
    }

    public synchronized LoadResult loadSafely() {
        try {
            return new LoadResult(load(), "");
        } catch (IOException failure) {
            return new LoadResult(Settings.defaults(worldId), "Tidy settings unavailable; automatic cleanup is off: "
                    + Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName()));
        }
    }

    public synchronized void save(Settings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        if (!worldId.equals(settings.worldId())) throw new IllegalArgumentException("settings belong to another world");
        byte[] payload = encode(settings);
        if (payload.length > MAXIMUM_FILE_BYTES - 20) throw new IOException("tidy settings are too large");
        CRC32 crc = new CRC32();
        crc.update(payload);
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporaryPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
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

    private static byte[] encode(Settings settings) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(settings.worldId());
            out.writeLong(settings.revision());
            writeRules(out, settings.junkItems());
            writeRules(out, settings.keepItems());
            out.writeBoolean(settings.automatic());
            DisposalSpot spot = settings.spot();
            out.writeBoolean(spot != null);
            if (spot != null) {
                out.writeUTF(spot.worldId());
                out.writeUTF(spot.homeFingerprint());
                out.writeUTF(spot.dimension());
                out.writeInt(spot.x()); out.writeInt(spot.y()); out.writeInt(spot.z());
                out.writeUTF(spot.blockId()); out.writeUTF(spot.blockState());
                out.writeDouble(spot.landingX()); out.writeDouble(spot.landingY()); out.writeDouble(spot.landingZ());
            }
            out.writeInt(settings.acknowledgedPolicyVersion());
        }
        return bytes.toByteArray();
    }

    private static AtomicStoreRecovery.Decoded<Settings> decodeStore(byte[] encoded, Path source,
                                                                    String expectedWorld) throws IOException {
        if (encoded.length < 20 || encoded.length > MAXIMUM_FILE_BYTES) throw new IOException("invalid tidy file size");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != MAGIC) throw new IOException("invalid tidy file magic");
            int schema = in.readInt();
            if (schema != 1 && schema != SCHEMA_VERSION) throw new AtomicStoreRecovery.UnsupportedSchemaException("tidy settings", schema);
            int length = in.readInt();
            if (length != encoded.length - 20) throw new IOException("invalid tidy payload length");
            byte[] payload = in.readNBytes(length);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (in.readLong() != crc.getValue()) throw new IOException("tidy checksum mismatch");
            Settings settings = decode(payload, schema);
            if (!expectedWorld.equals(settings.worldId())) throw new IOException("tidy settings world UUID mismatch");
            return new AtomicStoreRecovery.Decoded<>(settings, settings.revision(), schema);
        } catch (EOFException | IllegalArgumentException error) {
            throw new IOException("corrupt tidy settings " + source, error);
        }
    }

    private static Settings decode(byte[] payload, int schema) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            String worldId = in.readUTF();
            long revision = in.readLong();
            Set<String> junk = readRules(in);
            Set<String> keep = readRules(in);
            boolean automatic = in.readBoolean();
            DisposalSpot spot = in.readBoolean() ? new DisposalSpot(in.readUTF(), in.readUTF(), in.readUTF(),
                    in.readInt(), in.readInt(), in.readInt(), in.readUTF(), in.readUTF(),
                    in.readDouble(), in.readDouble(), in.readDouble()) : null;
            int acknowledgedPolicy = schema >= 2 ? in.readInt() : 0;
            if (schema == 1) {
                // v1 stored the effective list, with explicit keep rules separate.
                // Merge additions without overwriting either owner's override set.
                junk.addAll(PersonalSuppliesPolicy.DEFAULT_JUNK_ITEMS);
                junk.removeAll(keep);
            }
            if (in.read() != -1) throw new IOException("trailing tidy payload bytes");
            return new Settings(worldId, revision, junk, keep, automatic, spot, acknowledgedPolicy);
        }
    }

    private static void writeRules(DataOutputStream out, Set<String> items) throws IOException {
        out.writeInt(items.size());
        for (String item : new TreeSet<>(items)) out.writeUTF(item);
    }

    private static Set<String> readRules(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAXIMUM_RULES) throw new IOException("invalid tidy rule count");
        Set<String> items = new TreeSet<>();
        for (int index = 0; index < count; index++) {
            if (!items.add(exactItem(in.readUTF()))) throw new IOException("duplicate tidy rule");
        }
        return items;
    }

    private static Set<String> rules(Set<String> items) {
        Objects.requireNonNull(items, "items");
        if (items.size() > MAXIMUM_RULES) throw new IllegalArgumentException("too many tidy rules");
        Set<String> normalized = new TreeSet<>();
        for (String item : items) normalized.add(exactItem(item));
        return Collections.unmodifiableSet(normalized);
    }

    static String world(String value) {
        String normalized = text(value, "worldId", 36);
        String canonical = UUID.fromString(normalized).toString();
        if (!canonical.equalsIgnoreCase(normalized)) throw new IllegalArgumentException("world ID must be a UUID");
        return canonical;
    }

    static String text(String value, String field, int maximum) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) throw new IllegalArgumentException("invalid " + field);
        return normalized;
    }

    private static String exactItem(String value) {
        String normalized = DeliveryPolicy.normalizeItemId(text(value, "item", 256));
        if (!normalized.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("exact item ID required");
        return normalized;
    }
}
