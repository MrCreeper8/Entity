package dev.entity.client.autonomy.policy;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable, mission-independent ownership of Entity's field assets.
 *
 * <p>An asset ID belongs to Entity, not to a task-plan frame. Replacing or
 * rebasing a leaf therefore cannot erase a known crafting table, furnace,
 * chest, or Home bed. Every mutation is persisted before it becomes visible in memory, so
 * a failed disk write also cannot partially advance ownership state.</p>
 */
public final class FieldKitLedger {
    public enum AssetKind {
        CRAFTING_TABLE("crafting_table"),
        FURNACE("furnace"),
        CHEST("chest"),
        WHITE_BED("white_bed");

        private final String item;

        AssetKind(String item) {
            this.item = item;
        }

        public String item() {
            return item;
        }
    }

    public enum AssetState {
        CARRIED,
        PLACED,
        RECLAIM_PENDING,
        RECOVERY_REQUIRED,
        LOST
    }

    /** How strongly the last location/inventory fact was observed. */
    public enum Verification {
        NONE,
        CLIENT_OBSERVED,
        SERVER_CONFIRMED
    }

    public record Position(String dimension, int x, int y, int z) {
        public Position {
            dimension = requireText(dimension, "dimension", 256);
        }
    }

    public record Asset(
            String id,
            AssetKind kind,
            AssetState state,
            Verification verification,
            Position lastKnownPosition,
            long updatedAtMillis,
            long verifiedAtMillis,
            int recoveryAttempts,
            String recoveryReason) {
        public Asset {
            id = requireText(id, "id", 256);
            kind = Objects.requireNonNull(kind, "kind");
            state = Objects.requireNonNull(state, "state");
            verification = Objects.requireNonNull(verification, "verification");
            if (updatedAtMillis < 0 || verifiedAtMillis < 0) {
                throw new IllegalArgumentException("timestamps cannot be negative");
            }
            if (recoveryAttempts < 0) {
                throw new IllegalArgumentException("recoveryAttempts cannot be negative");
            }
            recoveryReason = Objects.requireNonNullElse(recoveryReason, "").trim();
            if (recoveryReason.length() > 4096) {
                throw new IllegalArgumentException("recoveryReason is too long");
            }
            if (state == AssetState.CARRIED && lastKnownPosition != null) {
                throw new IllegalArgumentException("a carried asset cannot retain a placement");
            }
            if (state != AssetState.CARRIED && lastKnownPosition == null) {
                throw new IllegalArgumentException(state + " asset requires its last known placement");
            }
            if ((state == AssetState.PLACED || state == AssetState.RECLAIM_PENDING)
                    && verification == Verification.NONE) {
                throw new IllegalArgumentException(state + " asset must have a verified placement");
            }
            boolean recovering = state == AssetState.RECOVERY_REQUIRED || state == AssetState.LOST;
            if (recovering != (recoveryAttempts > 0 && !recoveryReason.isBlank())) {
                throw new IllegalArgumentException(
                        "recovery state, attempts, and reason must advance together");
            }
        }

        public boolean recoverable() {
            return state == AssetState.RECLAIM_PENDING
                    || state == AssetState.RECOVERY_REQUIRED
                    || state == AssetState.LOST;
        }
    }

    public record Snapshot(long revision, Map<String, Asset> assets) {
        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
            Objects.requireNonNull(assets, "assets");
            LinkedHashMap<String, Asset> copy = new LinkedHashMap<>();
            assets.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        Asset asset = Objects.requireNonNull(entry.getValue(), "asset");
                        if (!asset.id().equals(entry.getKey())) {
                            throw new IllegalArgumentException("asset map key must equal asset id");
                        }
                        if (copy.putIfAbsent(entry.getKey(), asset) != null) {
                            throw new IllegalArgumentException("duplicate asset " + entry.getKey());
                        }
                    });
            assets = Collections.unmodifiableMap(copy);
        }

        public static Snapshot empty() {
            return new Snapshot(0, Map.of());
        }
    }

    private final FieldKitStore store;
    private Snapshot current;

    public FieldKitLedger(FieldKitStore store) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        this.current = Objects.requireNonNull(store.load(), "store returned null snapshot");
    }

    public synchronized Snapshot snapshot() {
        return current;
    }

    public synchronized Asset asset(String id) {
        Asset result = current.assets().get(requireText(id, "id", 256));
        if (result == null) throw new IllegalArgumentException("unknown field-kit asset " + id);
        return result;
    }

    public synchronized List<Asset> assets(AssetKind kind) {
        Objects.requireNonNull(kind, "kind");
        return current.assets().values().stream()
                .filter(asset -> asset.kind() == kind)
                .sorted(Comparator.comparing(Asset::id))
                .toList();
    }

    public synchronized List<Asset> recoverableAssets() {
        return current.assets().values().stream()
                .filter(Asset::recoverable)
                .sorted(Comparator.comparing(Asset::id))
                .toList();
    }

    public synchronized Asset registerCarried(
            AssetKind kind,
            Verification verification,
            long nowMillis) throws IOException {
        return registerCarried(UUID.randomUUID().toString(), kind, verification, nowMillis);
    }

    public synchronized Asset registerCarried(
            String id,
            AssetKind kind,
            Verification verification,
            long nowMillis) throws IOException {
        String safeId = requireText(id, "id", 256);
        if (verification == null || verification == Verification.NONE) {
            throw new IllegalArgumentException("carried ownership must be observed");
        }
        if (current.assets().containsKey(safeId)) {
            throw new IllegalArgumentException("duplicate field-kit asset " + safeId);
        }
        Asset asset = new Asset(safeId, Objects.requireNonNull(kind, "kind"),
                AssetState.CARRIED, verification,
                null, checkedTime(nowMillis), verifiedTime(verification, nowMillis), 0, "");
        commit(asset);
        return asset;
    }

    public synchronized Asset markPlaced(
            String id,
            Position position,
            Verification verification,
            long nowMillis) throws IOException {
        if (verification == null || verification == Verification.NONE) {
            throw new IllegalArgumentException("placement must be observed before ownership is committed");
        }
        Asset previous = asset(id);
        Asset next = new Asset(previous.id(), previous.kind(), AssetState.PLACED,
                verification, Objects.requireNonNull(position, "position"), checkedTime(nowMillis),
                verifiedTime(verification, nowMillis), 0, "");
        commit(next);
        return next;
    }

    /** Explicit owner confirmation only: records an existing block, never moves its contents. */
    public synchronized Asset ownerRegisterPlaced(String id, AssetKind kind, Position position,
                                                   long nowMillis) throws IOException {
        String safeId = requireText(id, "id", 256);
        if (!FieldKitAssetPolicy.isHomeAsset(safeId) || current.assets().containsKey(safeId)) {
            throw new IllegalArgumentException("explicit furniture binding needs a fresh reserved Home identity");
        }
        Asset asset = new Asset(safeId, Objects.requireNonNull(kind), AssetState.PLACED,
                Verification.CLIENT_OBSERVED, Objects.requireNonNull(position), checkedTime(nowMillis),
                checkedTime(nowMillis), 0, "");
        // The owner is explicitly changing custody of this one exact block.
        // A former ordinary FieldKit identity must not later reclaim furniture
        // now pinned to Home. Retire only that coordinate's ordinary records;
        // previous Home records remain harmless, excluded historical identity.
        LinkedHashMap<String, Asset> candidate = new LinkedHashMap<>(current.assets());
        candidate.values().removeIf(previous -> !FieldKitAssetPolicy.isHomeAsset(previous.id())
                && position.equals(previous.lastKnownPosition()));
        candidate.put(safeId, asset);
        commit(candidate);
        return asset;
    }

    public synchronized Asset markReclaimPending(String id, long nowMillis) throws IOException {
        Asset previous = asset(id);
        if (previous.lastKnownPosition() == null) {
            throw new IllegalStateException("cannot reclaim an asset without a placement");
        }
        Asset next = new Asset(previous.id(), previous.kind(), AssetState.RECLAIM_PENDING,
                previous.verification(), previous.lastKnownPosition(), checkedTime(nowMillis),
                previous.verifiedAtMillis(), 0, "");
        commit(next);
        return next;
    }

    public synchronized Asset markRecoveryRequired(
            String id,
            String reason,
            long nowMillis) throws IOException {
        Asset previous = asset(id);
        if (previous.lastKnownPosition() == null) {
            throw new IllegalStateException("cannot recover an asset without a last known placement");
        }
        Asset next = new Asset(previous.id(), previous.kind(), AssetState.RECOVERY_REQUIRED,
                previous.verification(), previous.lastKnownPosition(), checkedTime(nowMillis),
                previous.verifiedAtMillis(), Math.addExact(previous.recoveryAttempts(), 1),
                requireText(reason, "reason", 4096));
        commit(next);
        return next;
    }

    public synchronized Asset markLost(
            String id,
            String reason,
            long nowMillis) throws IOException {
        Asset previous = asset(id);
        if (previous.lastKnownPosition() == null) {
            throw new IllegalStateException("cannot mark lost without a last known placement");
        }
        Asset next = new Asset(previous.id(), previous.kind(), AssetState.LOST,
                previous.verification(), previous.lastKnownPosition(), checkedTime(nowMillis),
                previous.verifiedAtMillis(), Math.addExact(previous.recoveryAttempts(), 1),
                requireText(reason, "reason", 4096));
        commit(next);
        return next;
    }

    public synchronized Asset markCarried(
            String id,
            Verification verification,
            long nowMillis) throws IOException {
        if (verification == null || verification == Verification.NONE) {
            throw new IllegalArgumentException("inventory recovery must be observed");
        }
        Asset previous = asset(id);
        Asset next = new Asset(previous.id(), previous.kind(), AssetState.CARRIED,
                verification, null, checkedTime(nowMillis), verifiedTime(verification, nowMillis),
                0, "");
        commit(next);
        return next;
    }

    /** Removes a deliberately consumed/destroyed asset; accidental loss uses markLost. */
    public synchronized void remove(String id) throws IOException {
        String safeId = requireText(id, "id", 256);
        if (!current.assets().containsKey(safeId)) return;
        LinkedHashMap<String, Asset> candidate = new LinkedHashMap<>(current.assets());
        candidate.remove(safeId);
        commit(candidate);
    }

    private void commit(Asset replacement) throws IOException {
        LinkedHashMap<String, Asset> candidate = new LinkedHashMap<>(current.assets());
        candidate.put(replacement.id(), replacement);
        commit(candidate);
    }

    private void commit(Map<String, Asset> candidateAssets) throws IOException {
        Snapshot candidate = new Snapshot(Math.incrementExact(current.revision()), candidateAssets);
        // Persistence is the transaction boundary. Do not expose candidate if save fails.
        store.save(candidate);
        current = candidate;
    }

    private static long checkedTime(long value) {
        if (value < 0) throw new IllegalArgumentException("time cannot be negative");
        return value;
    }

    private static long verifiedTime(Verification verification, long nowMillis) {
        return verification == Verification.NONE ? 0 : checkedTime(nowMillis);
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        if (trimmed.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return trimmed;
    }
}
