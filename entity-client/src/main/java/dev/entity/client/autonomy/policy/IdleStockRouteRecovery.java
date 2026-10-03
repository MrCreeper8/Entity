package dev.entity.client.autonomy.policy;

import dev.entity.core.persistence.AtomicStoreRecovery;
import dev.entity.core.persistence.SimpleJson;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Exact receipt for an automatic Stock return failure; it grants no actuator or owner authority. */
public final class IdleStockRouteRecovery {
    public static final long RETRY_DELAY_MILLIS = 60_000L;
    private final Path path;
    private final String worldId;
    private long storeRevision;
    private Receipt receipt;
    private HomeEconomySession.Snapshot admitted;
    private String problem = "";

    private record Receipt(long generation, String home, long revision,
                           HomeEconomySession.Phase phase, HomeEconomySession.Block block,
                           long retryAt) { }
    private record Saved(long revision, Receipt receipt) { }

    public IdleStockRouteRecovery(Path directory, String worldId) {
        this.worldId = Objects.requireNonNullElse(worldId, "");
        path = this.worldId.isBlank() ? null : directory.resolve("idle-stock-route-recovery.json");
        if (path == null) { problem = "Automatic route recovery awaits authenticated world binding"; return; }
        try {
            UUID.fromString(worldId);
            Saved saved = AtomicStoreRecovery.load(path, temporary(), "idle Stock route recovery",
                    () -> new Saved(0, null), this::decode);
            storeRevision = saved.revision(); receipt = saved.receipt();
        } catch (IOException | RuntimeException error) {
            problem = "Automatic route recovery receipt is unreadable";
        }
    }

    public String problem() { return problem; }

    /** Called only after the ordinary automatic admission gates have passed. */
    public void begin(HomeEconomySession.Snapshot snapshot) throws IOException {
        clear();
        if (problem.isBlank() && snapshot != null && snapshot.enabled() && snapshot.home() != null
                && snapshot.block() == null && noDebt(snapshot)) admitted = snapshot;
    }

    /** The caller identifies its still-live automatic cycle before clearing that marker. */
    public void failed(HomeEconomySession.Snapshot snapshot, long now) throws IOException {
        if (admitted == null || snapshot == null || !sameHome(admitted, snapshot)
                || snapshot.revision() <= admitted.revision() || !eligibleBlock(snapshot)) {
            clear();
            return;
        }
        save(new Receipt(snapshot.generation(), snapshot.home().fingerprint(), snapshot.revision(),
                snapshot.phase(), snapshot.block(), Math.addExact(now, RETRY_DELAY_MILLIS)));
        admitted = null;
    }

    public boolean mayReobserve(HomeEconomySession.Snapshot snapshot, boolean settled, long now) {
        return problem.isBlank() && settled && matches(snapshot) && now >= receipt.retryAt();
    }

    /** Changes once when this exact failure becomes retryable, not for every poll. */
    public String workKey(HomeEconomySession.Snapshot snapshot, boolean settled, long now) {
        return mayReobserve(snapshot, settled, now)
                ? "automatic-home-route:" + receipt.generation() + ':' + receipt.revision() + ':' + receipt.retryAt()
                : "";
    }

    /** Preserve only this invocation's own BLOCKED -> PAUSED wrapper, never arbitrary revision changes. */
    public void paused(HomeEconomySession.Snapshot before, HomeEconomySession.Snapshot after) throws IOException {
        admitted = null;
        if (!matches(before)) { clear(); return; }
        if (before.equals(after)) return;
        if (after == null || after.revision() != before.revision() + 1 || !sameHome(before, after)
                || after.phase() != HomeEconomySession.Phase.PAUSED || after.pause() == null
                || after.pause().resumePhase() != HomeEconomySession.Phase.BLOCKED
                || !Objects.equals(before.block(), after.block()) || !noDebt(after)
                || !before.pinnedAssets().equals(after.pinnedAssets())
                || !before.storageRegistrations().equals(after.storageRegistrations())
                || before.storageExplicitlyMissing() != after.storageExplicitlyMissing()) {
            clear();
            return;
        }
        save(new Receipt(receipt.generation(), receipt.home(), after.revision(), after.phase(),
                receipt.block(), receipt.retryAt()));
    }

    /** A tombstone is saved, so a restart cannot recover an older cancelled receipt. */
    public void clear() throws IOException {
        admitted = null;
        if (receipt != null) save(null);
    }

    private boolean matches(HomeEconomySession.Snapshot snapshot) {
        return receipt != null && snapshot != null && snapshot.enabled() && snapshot.home() != null
                && snapshot.generation() == receipt.generation()
                && snapshot.home().fingerprint().equals(receipt.home())
                && snapshot.revision() == receipt.revision() && snapshot.phase() == receipt.phase()
                && Objects.equals(snapshot.block(), receipt.block()) && eligibleBlock(snapshot);
    }

    private static boolean eligibleBlock(HomeEconomySession.Snapshot snapshot) {
        return snapshot.block() != null && snapshot.block().retryable()
                && snapshot.block().code().equals("home_route_blocked")
                && snapshot.block().retryPhase() == HomeEconomySession.Phase.RETURNING_HOME
                && (snapshot.phase() == HomeEconomySession.Phase.BLOCKED
                    || snapshot.phase() == HomeEconomySession.Phase.PAUSED && snapshot.pause() != null
                    && snapshot.pause().resumePhase() == HomeEconomySession.Phase.BLOCKED)
                && noDebt(snapshot);
    }

    private static boolean noDebt(HomeEconomySession.Snapshot snapshot) {
        return snapshot.pendingTransfer() == null && snapshot.pendingAcquisition() == null;
    }

    private static boolean sameHome(HomeEconomySession.Snapshot a, HomeEconomySession.Snapshot b) {
        return b.enabled() && b.home() != null && a.generation() == b.generation()
                && a.home().fingerprint().equals(b.home().fingerprint());
    }

    private Path temporary() { return path.resolveSibling(path.getFileName() + ".tmp"); }

    private void save(Receipt next) throws IOException {
        // Invalidate in memory even if disk failure prevents the tombstone. No recovery runs on error.
        if (next == null) receipt = null;
        if (path == null || !problem.isBlank()) return;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", 1); root.put("worldId", worldId); root.put("revision", storeRevision + 1);
        root.put("failure", next == null ? null : Map.of(
                "generation", next.generation(), "home", next.home(), "revision", next.revision(),
                "phase", next.phase().name(), "retryAt", next.retryAt(),
                "blockedAt", next.block().blockedAtMillis(), "detail", next.block().detail()));
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary(), SimpleJson.stringify(root), StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary(), StandardOpenOption.WRITE)) { channel.force(true); }
            AtomicFileCommit.replace(temporary(), path);
            receipt = next; storeRevision++;
        } catch (IOException error) {
            problem = "Automatic route recovery receipt could not be saved";
            throw error;
        }
    }

    private AtomicStoreRecovery.Decoded<Saved> decode(byte[] bytes, Path source) throws IOException {
        try {
            if (bytes.length > 32768 || !(SimpleJson.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof Map<?, ?> root))
                throw new IllegalArgumentException("Invalid recovery receipt");
            if (number(root, "schema") != 1)
                throw new AtomicStoreRecovery.UnsupportedSchemaException("idle Stock route recovery", 0);
            if (!worldId.equals(root.get("worldId"))) throw new IllegalArgumentException("Wrong world");
            long revision = number(root, "revision");
            Receipt restored = null;
            if (root.get("failure") != null) {
                if (!(root.get("failure") instanceof Map<?, ?> row)) throw new IllegalArgumentException("Invalid failure");
                HomeEconomySession.Phase phase = HomeEconomySession.Phase.valueOf((String) row.get("phase"));
                if (phase != HomeEconomySession.Phase.BLOCKED && phase != HomeEconomySession.Phase.PAUSED)
                    throw new IllegalArgumentException("Invalid failure phase");
                String home = (String) row.get("home");
                if (home == null || home.isBlank() || home.length() > 1024) throw new IllegalArgumentException("Invalid Home");
                var block = new HomeEconomySession.Block(HomeEconomySession.Phase.RETURNING_HOME,
                        "home_route_blocked", (String) row.get("detail"), true, number(row, "blockedAt"));
                long retryAt = number(row, "retryAt");
                if (retryAt < block.blockedAtMillis() || retryAt - block.blockedAtMillis() < RETRY_DELAY_MILLIS)
                    throw new IllegalArgumentException("Invalid retry interval");
                restored = new Receipt(number(row, "generation"), home, number(row, "revision"), phase, block, retryAt);
            }
            return new AtomicStoreRecovery.Decoded<>(new Saved(revision, restored), revision, 1);
        } catch (RuntimeException error) { throw new IOException("Invalid idle Stock recovery receipt: " + source, error); }
    }

    private static long number(Map<?, ?> row, String key) {
        if (!(row.get(key) instanceof Number value) || value.longValue() < 0
                || value.doubleValue() != (double) value.longValue()) throw new IllegalArgumentException("Invalid " + key);
        return value.longValue();
    }
}
