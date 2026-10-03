package dev.entity.client.autonomy.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Durable transaction boundary for Entity's home maintenance loop.
 *
 * <p>The snapshot is independent of a user mission. It survives plan rebases,
 * death, reconnect, and restart. Every mutation is written before publication,
 * so a disk failure leaves the last in-memory and on-disk state unchanged.</p>
 */
public final class HomeEconomySession {
    public enum Phase {
        DISABLED,
        NEEDS_HOME,
        ESTABLISHING_HOME,
        PROVISIONING_ASSETS,
        VERIFYING_ASSETS,
        OBSERVING_STOCK,
        RETRIEVING,
        ACQUIRING,
        RETURNING_HOME,
        DEPOSITING,
        RECONCILING_TRANSFER,
        READY,
        DEGRADED_READY,
        PAUSED,
        BLOCKED
    }

    public enum AssetRole {
        CRAFTING_TABLE("crafting_table", FieldKitLedger.AssetKind.CRAFTING_TABLE),
        FURNACE("furnace", FieldKitLedger.AssetKind.FURNACE),
        CHEST("chest", FieldKitLedger.AssetKind.CHEST),
        BED("white_bed", FieldKitLedger.AssetKind.WHITE_BED);

        private final String item;
        private final FieldKitLedger.AssetKind ledgerKind;

        AssetRole(String item, FieldKitLedger.AssetKind ledgerKind) {
            this.item = item;
            this.ledgerKind = ledgerKind;
        }

        public String item() {
            return item;
        }

        public FieldKitLedger.AssetKind ledgerKind() {
            return ledgerKind;
        }
    }

    /** Exact settings-store home identity, authenticated by a deterministic fingerprint. */
    public record HomeAnchor(String dimension, int x, int y, int z, String fingerprint) {
        public HomeAnchor {
            dimension = requireText(dimension, "dimension", 256);
            fingerprint = requireText(fingerprint, "fingerprint", 128)
                    .toLowerCase(Locale.ROOT);
            String expected = HomeEconomySession.fingerprint(dimension, x, y, z);
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("home fingerprint does not match its anchor");
            }
        }

        public static HomeAnchor at(String dimension, int x, int y, int z) {
            String normalizedDimension = requireText(dimension, "dimension", 256);
            return new HomeAnchor(normalizedDimension, x, y, z,
                    HomeEconomySession.fingerprint(normalizedDimension, x, y, z));
        }
    }

    /** A role may only point at an asset already owned by the shared FieldKit ledger. */
    public record PinnedAsset(AssetRole role, String ledgerAssetId, long pinnedAtMillis) {
        public PinnedAsset {
            role = Objects.requireNonNull(role, "role");
            ledgerAssetId = requireText(ledgerAssetId, "ledgerAssetId", 256);
            checkedTime(pinnedAtMillis);
        }
    }

    /** Explicit membership only; the shared FieldKit ledger still owns block position/truth. */
    public record StorageRegistration(String id, String ledgerAssetId, long registeredAtMillis) {
        public StorageRegistration {
            id = requireId(id, "storageId");
            ledgerAssetId = requireText(ledgerAssetId, "ledgerAssetId", 256);
            checkedTime(registeredAtMillis);
        }
    }

    /** One observed physical half. Order in ContainerIdentity is vanilla inventory slot order. */
    public record ChestHalf(FieldKitLedger.Position position, String blockId,
                            String facing, String chestType) {
        public ChestHalf {
            Objects.requireNonNull(position, "position");
            blockId = requireText(blockId, "blockId", 256);
            facing = requireId(facing, "facing");
            chestType = requireId(chestType, "chestType");
            if (!blockId.equals("minecraft:chest") && !blockId.equals("minecraft:trapped_chest")) {
                throw new IllegalArgumentException("storage half must be a chest block");
            }
            if (!List.of("north", "south", "east", "west").contains(facing)
                    || !List.of("single", "left", "right").contains(chestType)) {
                throw new IllegalArgumentException("invalid chest facing or connection type");
            }
        }
    }

    /** Exact connected container/layout, never aggregate storage counts or nearby geometry. */
    public record ContainerIdentity(String storageId, List<ChestHalf> halves) {
        public ContainerIdentity {
            storageId = requireId(storageId, "storageId");
            halves = List.copyOf(Objects.requireNonNull(halves, "halves"));
            if (halves.size() < 1 || halves.size() > 2) {
                throw new IllegalArgumentException("a chest inventory needs one or two exact halves");
            }
            ChestHalf first = halves.get(0);
            if (halves.size() == 1) {
                if (!first.chestType().equals("single")) {
                    throw new IllegalArgumentException("single storage identity must be unconnected");
                }
            } else {
                ChestHalf second = halves.get(1);
                FieldKitLedger.Position a = first.position();
                FieldKitLedger.Position b = second.position();
                if (!a.dimension().equals(b.dimension()) || a.y() != b.y()
                        || Math.abs((long) a.x() - b.x()) + Math.abs((long) a.z() - b.z()) != 1
                        || (List.of("north", "south").contains(first.facing()) ? a.z() != b.z() : a.x() != b.x())
                        || !first.blockId().equals(second.blockId())
                        || !first.facing().equals(second.facing())
                        || !new HashSet<>(List.of(first.chestType(), second.chestType()))
                        .equals(java.util.Set.of("left", "right"))) {
                    throw new IllegalArgumentException("double storage identity needs matching adjacent halves");
                }
            }
        }

        public int slotCount() { return halves.size() * 27; }
    }

    /** Persisted before the inventory click that it describes. */
    public record PendingTransfer(
            String id,
            HomeStockPolicy.Direction direction,
            String categoryId,
            String item,
            int count,
            int syncId,
            int sourceSlot,
            int destinationSlot,
            int destinationCountBefore,
            int playerCountBefore,
            int chestCountBefore,
            long startedAtMillis,
            ContainerIdentity containerIdentity) {
        /** Legacy saves/callers retain unknown identity until exact runtime reconciliation. */
        public PendingTransfer(String id, HomeStockPolicy.Direction direction, String categoryId,
                String item, int count, int syncId, int sourceSlot, int destinationSlot,
                int destinationCountBefore, int playerCountBefore, int chestCountBefore,
                long startedAtMillis) {
            this(id, direction, categoryId, item, count, syncId, sourceSlot, destinationSlot,
                    destinationCountBefore, playerCountBefore, chestCountBefore, startedAtMillis, null);
        }

        public PendingTransfer {
            id = requireText(id, "id", 256);
            direction = Objects.requireNonNull(direction, "direction");
            categoryId = requireId(categoryId, "categoryId");
            item = normalizeItem(item);
            if (count <= 0) throw new IllegalArgumentException("transfer count must be positive");
            if (syncId < 0 || sourceSlot < 0 || destinationSlot < 0
                    || destinationCountBefore < 0) {
                throw new IllegalArgumentException("handler and slot IDs cannot be negative");
            }
            if (playerCountBefore < 0 || chestCountBefore < 0) {
                throw new IllegalArgumentException("before-counts cannot be negative");
            }
            if (direction == HomeStockPolicy.Direction.WITHDRAW
                    && chestCountBefore < count) {
                throw new IllegalArgumentException("withdraw intent exceeds observed chest count");
            }
            if (direction == HomeStockPolicy.Direction.DEPOSIT
                    && playerCountBefore < count) {
                throw new IllegalArgumentException("deposit intent exceeds observed player count");
            }
            if (containerIdentity != null) {
                int containerSlot = direction == HomeStockPolicy.Direction.DEPOSIT ? destinationSlot : sourceSlot;
                int playerSlot = direction == HomeStockPolicy.Direction.DEPOSIT ? sourceSlot : destinationSlot;
                int slots = containerIdentity.slotCount();
                if (containerSlot >= slots || playerSlot < slots || playerSlot >= slots + 36) {
                    throw new IllegalArgumentException("transfer slots do not match the connected container layout");
                }
            }
            checkedTime(startedAtMillis);
        }
    }

    /** Identity and absolute additional goals of one universal-planner program. */
    public record PendingAcquisition(
            String planId,
            Map<String, Integer> goals,
            long startedAtMillis) {
        public PendingAcquisition {
            planId = requireText(planId, "planId", 256);
            Objects.requireNonNull(goals, "goals");
            if (goals.isEmpty()) throw new IllegalArgumentException("acquisition goals cannot be empty");
            LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
            goals.forEach((rawItem, rawCount) -> {
                if (rawCount == null || rawCount <= 0 || rawCount > 4096) {
                    throw new IllegalArgumentException("acquisition counts must be 1..4096");
                }
                String item = normalizeItem(rawItem);
                if (normalized.putIfAbsent(item, rawCount) != null) {
                    throw new IllegalArgumentException("duplicate acquisition goal " + item);
                }
            });
            goals = Collections.unmodifiableMap(normalized);
            checkedTime(startedAtMillis);
        }
    }

    public record Pause(Phase resumePhase, String reason, long pausedAtMillis) {
        public Pause {
            resumePhase = Objects.requireNonNull(resumePhase, "resumePhase");
            if (resumePhase == Phase.PAUSED || resumePhase == Phase.DISABLED) {
                throw new IllegalArgumentException("invalid paused resume phase " + resumePhase);
            }
            reason = requireText(reason, "reason", 1024);
            checkedTime(pausedAtMillis);
        }
    }

    public record Block(
            Phase retryPhase,
            String code,
            String detail,
            boolean retryable,
            long blockedAtMillis) {
        public Block {
            retryPhase = Objects.requireNonNull(retryPhase, "retryPhase");
            if (retryPhase == Phase.PAUSED || retryPhase == Phase.BLOCKED
                    || retryPhase == Phase.DISABLED) {
                throw new IllegalArgumentException("invalid blocked retry phase " + retryPhase);
            }
            code = requireId(code, "code");
            detail = requireText(detail, "detail", 4096);
            checkedTime(blockedAtMillis);
        }
    }

    public record Snapshot(
            long revision,
            boolean enabled,
            long generation,
            Phase phase,
            HomeAnchor home,
            Map<AssetRole, PinnedAsset> pinnedAssets,
            PendingTransfer pendingTransfer,
            PendingAcquisition pendingAcquisition,
            Pause pause,
            Block block,
            long updatedAtMillis,
            Map<String, StorageRegistration> storageRegistrations,
            boolean storageExplicitlyMissing) {
        /** Source/schema compatibility: an existing single pin becomes the sole primary registration. */
        public Snapshot(long revision, boolean enabled, long generation, Phase phase, HomeAnchor home,
                Map<AssetRole, PinnedAsset> pinnedAssets, PendingTransfer pendingTransfer,
                PendingAcquisition pendingAcquisition, Pause pause, Block block, long updatedAtMillis) {
            this(revision, enabled, generation, phase, home, pinnedAssets, pendingTransfer,
                    pendingAcquisition, pause, block, updatedAtMillis, legacyStorage(pinnedAssets), false);
        }

        public Snapshot {
            if (revision < 0 || generation < 0 || updatedAtMillis < 0) {
                throw new IllegalArgumentException("revision, generation, and time cannot be negative");
            }
            phase = Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(pinnedAssets, "pinnedAssets");
            EnumMap<AssetRole, PinnedAsset> pins = new EnumMap<>(AssetRole.class);
            pinnedAssets.forEach((role, asset) -> {
                Objects.requireNonNull(role, "pinned asset role");
                Objects.requireNonNull(asset, "pinned asset");
                if (asset.role() != role) {
                    throw new IllegalArgumentException("pinned asset map key does not match role");
                }
                if (pins.putIfAbsent(role, asset) != null) {
                    throw new IllegalArgumentException("duplicate pinned home asset " + role);
                }
                String prefix = assetIdPrefix(generation, role);
                if (!asset.ledgerAssetId().startsWith(prefix)) {
                    throw new IllegalArgumentException(
                            "home asset is outside this generation: " + asset.ledgerAssetId());
                }
            });
            pinnedAssets = Collections.unmodifiableMap(pins);
            Objects.requireNonNull(storageRegistrations, "storageRegistrations");
            LinkedHashMap<String, StorageRegistration> storage = new LinkedHashMap<>();
            HashSet<String> registeredAssets = new HashSet<>();
            storageRegistrations.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                        StorageRegistration registration = Objects.requireNonNull(entry.getValue());
                        if (!entry.getKey().equals(registration.id())
                                || !registration.ledgerAssetId().startsWith(assetIdPrefix(generation, AssetRole.CHEST))
                                || !registeredAssets.add(registration.ledgerAssetId())) {
                            throw new IllegalArgumentException("invalid or duplicate Home storage registration");
                        }
                        storage.put(entry.getKey(), registration);
                    });
            storageRegistrations = Collections.unmodifiableMap(storage);
            PinnedAsset primary = pins.get(AssetRole.CHEST);
            if ((primary == null) != storage.isEmpty()
                    || primary != null && !registeredAssets.contains(primary.ledgerAssetId())) {
                throw new IllegalArgumentException("primary chest pin must name exactly one registered storage");
            }
            if (storageExplicitlyMissing && (home == null || !storage.isEmpty())) {
                throw new IllegalArgumentException("explicit missing storage requires an existing Home and no registration");
            }

            if (enabled == (phase == Phase.DISABLED)) {
                throw new IllegalArgumentException(
                        "enabled state and disabled phase must agree");
            }
            if (home == null && !pinnedAssets.isEmpty()) {
                throw new IllegalArgumentException("assets cannot be pinned without a home");
            }
            if (home != null && generation == 0) {
                throw new IllegalArgumentException("a home requires a positive generation");
            }
            if ((phase == Phase.PAUSED) != (pause != null)) {
                throw new IllegalArgumentException("pause metadata must exactly match PAUSED phase");
            }
            boolean visibleBlock = phase == Phase.BLOCKED
                    || (phase == Phase.PAUSED && pause.resumePhase() == Phase.BLOCKED);
            if (visibleBlock != (block != null)) {
                throw new IllegalArgumentException("block metadata must remain attached to BLOCKED state");
            }
            if (pendingTransfer != null && pendingAcquisition != null) {
                throw new IllegalArgumentException("transfer and acquisition cannot be pending together");
            }
            if (pendingTransfer != null && pendingTransfer.containerIdentity() != null) {
                ContainerIdentity identity = pendingTransfer.containerIdentity();
                if (!storage.containsKey(identity.storageId()) || home == null
                        || !identity.halves().get(0).position().dimension().equals(home.dimension())) {
                    throw new IllegalArgumentException("transfer container is not registered in this Home");
                }
            }
            if (pendingTransfer != null && !HomeEconomySession.ownsUnderlyingPhase(
                    phase, pause, block, Phase.RECONCILING_TRANSFER)) {
                throw new IllegalArgumentException(
                        "pending transfer requires the reconciliation phase");
            }
            if (pendingAcquisition != null
                    && !HomeEconomySession.ownsUnderlyingPhase(
                    phase, pause, block, Phase.ACQUIRING)
                    && !HomeEconomySession.ownsUnderlyingPhase(
                    phase, pause, block, Phase.RETURNING_HOME)) {
                throw new IllegalArgumentException(
                        "pending acquisition requires acquisition or exact Home return");
            }
            if ((phase == Phase.READY || phase == Phase.DEGRADED_READY) && (home == null
                    || pinnedAssets.size() != AssetRole.values().length
                    || pendingTransfer != null || pendingAcquisition != null)) {
                throw new IllegalArgumentException(
                        "ready/degraded-ready requires a complete idle home snapshot");
            }
        }

        public static Snapshot empty() {
            return new Snapshot(0, false, 0, Phase.DISABLED, null, Map.of(),
                    null, null, null, null, 0);
        }

        public boolean hasAllPinnedAssets() {
            return pinnedAssets.size() == AssetRole.values().length;
        }

        public StorageRegistration primaryStorage() {
            PinnedAsset primary = pinnedAssets.get(AssetRole.CHEST);
            if (primary == null) return null;
            return storageRegistrations.values().stream()
                    .filter(registration -> registration.ledgerAssetId().equals(primary.ledgerAssetId()))
                    .findFirst().orElseThrow();
        }

        public Phase resumablePhase() {
            return phase == Phase.PAUSED ? pause.resumePhase() : phase;
        }

        /** True for the live phase and for the exact phase hidden by pause/block wrappers. */
        public boolean ownsUnderlyingPhase(Phase expected) {
            return HomeEconomySession.ownsUnderlyingPhase(
                    phase, pause, block, Objects.requireNonNull(expected, "expected"));
        }
    }

    private final HomeEconomyStore store;
    private Snapshot current;

    public HomeEconomySession(HomeEconomyStore store) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        this.current = Objects.requireNonNull(store.load(), "store returned null snapshot");
    }

    public synchronized Snapshot snapshot() {
        return current;
    }

    public synchronized Snapshot enable(long nowMillis) throws IOException {
        if (current.enabled()) return current;
        if (current.storageExplicitlyMissing()) {
            return commit(true, current.generation(), Phase.BLOCKED, current.home(),
                    current.pinnedAssets(), null, null, null, missingStorageBlock(nowMillis), nowMillis);
        }
        Phase next = current.home() == null ? Phase.NEEDS_HOME : Phase.VERIFYING_ASSETS;
        return commit(true, current.generation(), next, current.home(),
                current.pinnedAssets(), null, null, null, null, nowMillis);
    }

    public synchronized Snapshot disable(long nowMillis) throws IOException {
        if (!current.enabled()) return current;
        requireNoPendingOperation("disable");
        return commit(false, current.generation(), Phase.DISABLED, current.home(),
                current.pinnedAssets(), null, null, null, null, nowMillis);
    }

    public synchronized Snapshot beginHomeEstablishment(long nowMillis) throws IOException {
        requireEnabled();
        if (current.home() != null) {
            throw new IllegalStateException("clear the current home before establishing another");
        }
        return commit(true, current.generation(), Phase.ESTABLISHING_HOME, null,
                Map.of(), null, null, null, null, nowMillis);
    }

    public synchronized Snapshot establishHome(HomeAnchor home, long nowMillis) throws IOException {
        requireEnabled();
        Objects.requireNonNull(home, "home");
        if (current.home() != null) {
            throw new IllegalStateException("clear the current home before establishing another");
        }
        long generation = Math.incrementExact(current.generation());
        return commit(true, generation, Phase.PROVISIONING_ASSETS, home,
                Map.of(), null, null, null, null, nowMillis);
    }

    /** Forgetting a home never authorizes breaking its old blocks or moving its chest. */
    public synchronized Snapshot clearHome(long nowMillis) throws IOException {
        requireNoPendingOperation("clear the home");
        long generation = Math.incrementExact(current.generation());
        return commit(current.enabled(), generation,
                current.enabled() ? Phase.NEEDS_HOME : Phase.DISABLED,
                null, Map.of(), null, null, null, null, nowMillis);
    }

    /**
     * Primary-owner generation fence for a confirmed relocation.
     *
     * <p>This intentionally retires the old durable cursor without claiming
     * that a transfer, acquisition, repair, or fill succeeded. Any physical
     * assets and chest contents remain untouched at the old Home. The caller
     * must neutralize live actuators before publishing this boundary.</p>
     */
    public synchronized Snapshot ownerRelocateHome(
            HomeAnchor replacement,
            long nowMillis) throws IOException {
        Objects.requireNonNull(replacement, "replacement");
        long generation = Math.incrementExact(current.generation());
        return commit(true, generation, Phase.PROVISIONING_ASSETS,
                replacement, Map.of(), null, null, null, null, nowMillis);
    }

    /** Registers an anchor only. Registration grants no provisioning or Stock authority. */
    public synchronized Snapshot ownerRegisterHome(HomeAnchor anchor, long nowMillis) throws IOException {
        Objects.requireNonNull(anchor, "anchor");
        long generation = Math.incrementExact(current.generation());
        return commit(false, generation, Phase.DISABLED,
                anchor, Map.of(), null, null, null, null, nowMillis);
    }

    /**
     * Primary-owner generation fence for confirmed clear. It is deliberately
     * available in every phase and never mutates old physical Home assets.
     */
    public synchronized Snapshot ownerClearHome(long nowMillis) throws IOException {
        long generation = Math.incrementExact(current.generation());
        return commit(false, generation, Phase.DISABLED,
                null, Map.of(), null, null, null, null, nowMillis);
    }

    /** Owner-authorized furniture change; Home anchor/generation and other pins are retained. */
    public synchronized Snapshot ownerRebindAsset(AssetRole role, FieldKitLedger.Asset observed,
                                                 long nowMillis) throws IOException {
        return ownerRebindAssets(Map.of(role, observed), nowMillis);
    }

    /** One durable pin commit for a confirmed adoption; partial roles never become a live batch. */
    public synchronized Snapshot ownerRebindAssets(Map<AssetRole, FieldKitLedger.Asset> observedAssets,
                                                  long nowMillis) throws IOException {
        Objects.requireNonNull(observedAssets, "observed assets");
        if (observedAssets.isEmpty()) throw new IllegalArgumentException("No furniture was selected");
        if (current.home() == null) throw new IllegalStateException("Home is unset");
        if (current.pendingTransfer() != null) {
            throw new IllegalStateException("Reconcile the exact pending Home transfer before rebinding furniture");
        }
        for (var entry : observedAssets.entrySet()) {
            AssetRole role = Objects.requireNonNull(entry.getKey(), "role");
            FieldKitLedger.Asset observed = Objects.requireNonNull(entry.getValue(), "observed asset");
            if (observed.kind() != role.ledgerKind()
                || !observed.id().startsWith(assetIdPrefix(current.generation(), role))
                || observed.state() != FieldKitLedger.AssetState.PLACED
                || observed.verification() == FieldKitLedger.Verification.NONE
                || !observed.lastKnownPosition().dimension().equals(current.home().dimension())) {
                throw new IllegalArgumentException("Furniture observation does not match this exact Home role/generation");
            }
        }
        EnumMap<AssetRole, PinnedAsset> pins = new EnumMap<>(AssetRole.class);
        pins.putAll(current.pinnedAssets());
        observedAssets.forEach((role, observed) -> pins.put(role,
                new PinnedAsset(role, observed.id(), checkedTime(nowMillis))));
        if (observedAssets.containsKey(AssetRole.CHEST)) {
            LinkedHashMap<String, StorageRegistration> storage = new LinkedHashMap<>(current.storageRegistrations());
            StorageRegistration primary = current.primaryStorage();
            String id = primary == null ? "primary" : primary.id();
            storage.put(id, new StorageRegistration(id, observedAssets.get(AssetRole.CHEST).id(), nowMillis));
            return commit(current.enabled(), current.generation(),
                    current.enabled() ? Phase.VERIFYING_ASSETS : Phase.DISABLED,
                    current.home(), pins, null, null, null, null, nowMillis, storage, false);
        }
        return commit(current.enabled(), current.generation(),
                current.enabled() ? Phase.VERIFYING_ASSETS : Phase.DISABLED,
                current.home(), pins, null, null, null, null, nowMillis);
    }

    /** Read-only admission shared with the command boundary, before any work is stopped. */
    public synchronized void validateOwnerStorageMutation(String operation) {
        if (current.home() == null) throw new IllegalStateException("Home is unset");
        requireNoPendingOperation(operation);
    }

    /** Adds only an explicitly confirmed, already owned chest. Geometry/property checks belong to the caller. */
    public synchronized Snapshot ownerAddStorage(String id, FieldKitLedger.Asset observed,
                                                  long nowMillis) throws IOException {
        validateOwnerStorageMutation("register storage");
        Objects.requireNonNull(observed, "observed asset");
        if (observed.kind() != AssetRole.CHEST.ledgerKind()
                || !observed.id().startsWith(assetIdPrefix(current.generation(), AssetRole.CHEST))
                || observed.state() != FieldKitLedger.AssetState.PLACED
                || observed.verification() == FieldKitLedger.Verification.NONE
                || observed.lastKnownPosition() == null
                || !observed.lastKnownPosition().dimension().equals(current.home().dimension())) {
            throw new IllegalArgumentException("Storage observation does not match this exact Home generation");
        }
        StorageRegistration registration = new StorageRegistration(id, observed.id(), nowMillis);
        LinkedHashMap<String, StorageRegistration> storage = new LinkedHashMap<>(current.storageRegistrations());
        if (storage.putIfAbsent(registration.id(), registration) != null
                || current.storageRegistrations().values().stream()
                .anyMatch(existing -> existing.ledgerAssetId().equals(observed.id()))) {
            throw new IllegalArgumentException("Storage ID or exact ledger asset is already registered");
        }
        EnumMap<AssetRole, PinnedAsset> pins = new EnumMap<>(AssetRole.class);
        pins.putAll(current.pinnedAssets());
        pins.putIfAbsent(AssetRole.CHEST, new PinnedAsset(AssetRole.CHEST, observed.id(), nowMillis));
        return commit(current.enabled(), current.generation(),
                current.enabled() ? Phase.VERIFYING_ASSETS : Phase.DISABLED,
                current.home(), pins, null, null, null, null, nowMillis, storage, false);
    }

    /** Removes membership, never the FieldKit record, physical blocks or contents. */
    public synchronized Snapshot ownerRemoveStorage(String id, long nowMillis) throws IOException {
        validateOwnerStorageMutation("remove storage");
        String wanted = requireId(id, "storageId");
        LinkedHashMap<String, StorageRegistration> storage = new LinkedHashMap<>(current.storageRegistrations());
        StorageRegistration removed = storage.remove(wanted);
        if (removed == null) throw new IllegalArgumentException("Unknown registered storage " + wanted);
        EnumMap<AssetRole, PinnedAsset> pins = new EnumMap<>(AssetRole.class);
        pins.putAll(current.pinnedAssets());
        if (current.primaryStorage().id().equals(wanted)) {
            pins.remove(AssetRole.CHEST);
            storage.values().stream().min(Comparator.comparingLong(StorageRegistration::registeredAtMillis)
                    .thenComparing(StorageRegistration::id)).ifPresent(next ->
                    pins.put(AssetRole.CHEST, new PinnedAsset(AssetRole.CHEST,
                            next.ledgerAssetId(), nowMillis)));
        }
        boolean missing = storage.isEmpty();
        Block block = missing && current.enabled() ? missingStorageBlock(nowMillis) : null;
        Phase phase = !current.enabled() ? Phase.DISABLED : missing ? Phase.BLOCKED : Phase.VERIFYING_ASSETS;
        return commit(current.enabled(), current.generation(), phase, current.home(), pins,
                null, null, null, block, nowMillis, storage, missing);
    }

    /**
     * Pins only an already-observed FieldKit asset with this home generation's
     * reserved ID. A nearby world block is therefore never adoptable here.
     */
    public synchronized Snapshot pinOwnedAsset(
            AssetRole role,
            FieldKitLedger.Asset ownedAsset,
            long nowMillis) throws IOException {
        requireEnabledHome();
        requireActive();
        requireNoPendingOperation("pin a home asset");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(ownedAsset, "ownedAsset");
        if (role == AssetRole.CHEST && current.storageExplicitlyMissing()) {
            throw new IllegalStateException("Storage was explicitly removed; register or bind an exact chest");
        }
        if (ownedAsset.kind() != role.ledgerKind()) {
            throw new IllegalArgumentException("field-kit kind does not match home role " + role);
        }
        if (ownedAsset.verification() == FieldKitLedger.Verification.NONE
                || ownedAsset.state() == FieldKitLedger.AssetState.LOST) {
            throw new IllegalArgumentException("home asset must have current ownership evidence");
        }
        String prefix = assetIdPrefix(current.generation(), role);
        if (!ownedAsset.id().startsWith(prefix)) {
            throw new IllegalArgumentException("asset ID is not reserved for this home generation");
        }
        PinnedAsset existing = current.pinnedAssets().get(role);
        if (existing != null && !existing.ledgerAssetId().equals(ownedAsset.id())) {
            throw new IllegalStateException("mark the old home asset lost before replacing it");
        }
        EnumMap<AssetRole, PinnedAsset> pins = new EnumMap<>(AssetRole.class);
        pins.putAll(current.pinnedAssets());
        pins.put(role, new PinnedAsset(role, ownedAsset.id(), checkedTime(nowMillis)));
        Phase next = pins.size() == AssetRole.values().length
                ? Phase.VERIFYING_ASSETS : Phase.PROVISIONING_ASSETS;
        if (role == AssetRole.CHEST && current.primaryStorage() == null) {
            return commit(true, current.generation(), next, current.home(), pins,
                    null, null, null, null, nowMillis, legacyStorage(pins), false);
        }
        return commit(true, current.generation(), next, current.home(), pins,
                null, null, null, null, nowMillis);
    }

    /** Removes a pin only after the shared ownership ledger has proved loss. */
    public synchronized Snapshot unpinLostAsset(
            AssetRole role,
            FieldKitLedger.Asset lostAsset,
            long nowMillis) throws IOException {
        requireEnabledHome();
        requireActive();
        requireNoPendingOperation("unpin a lost home asset");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(lostAsset, "lostAsset");
        PinnedAsset pinned = current.pinnedAssets().get(role);
        if (pinned == null || !pinned.ledgerAssetId().equals(lostAsset.id())) {
            throw new IllegalArgumentException("loss proof does not match the pinned home asset");
        }
        if (lostAsset.kind() != role.ledgerKind()
                || lostAsset.state() != FieldKitLedger.AssetState.LOST) {
            throw new IllegalArgumentException("field-kit ledger has not proved this asset lost");
        }
        if (role == AssetRole.CHEST) {
            return commit(true, current.generation(), Phase.BLOCKED, current.home(), current.pinnedAssets(),
                    null, null, null, new Block(Phase.VERIFYING_ASSETS, "home_storage_missing",
                            "Registered storage is missing; restore, remove or rebind its exact registration",
                            true, checkedTime(nowMillis)), nowMillis);
        }
        EnumMap<AssetRole, PinnedAsset> pins = new EnumMap<>(AssetRole.class);
        pins.putAll(current.pinnedAssets());
        pins.remove(role);
        return commit(true, current.generation(), Phase.PROVISIONING_ASSETS,
                current.home(), pins, null, null, null, null, nowMillis);
    }

    public synchronized Snapshot advance(Phase nextPhase, long nowMillis) throws IOException {
        requireEnabled();
        Objects.requireNonNull(nextPhase, "nextPhase");
        if (nextPhase == Phase.DISABLED || nextPhase == Phase.PAUSED
                || nextPhase == Phase.BLOCKED || nextPhase == Phase.RECONCILING_TRANSFER
                || nextPhase == Phase.ACQUIRING) {
            throw new IllegalArgumentException("phase requires its dedicated transition: " + nextPhase);
        }
        if (current.phase() == Phase.PAUSED || current.phase() == Phase.BLOCKED) {
            throw new IllegalStateException("resume or retry before advancing");
        }
        if (current.pendingAcquisition() != null
                && nextPhase == Phase.RETURNING_HOME) {
            throw new IllegalStateException(
                    "use beginAcquisitionReturn to preserve the durable acquisition cursor");
        }
        if ((nextPhase == Phase.READY || nextPhase == Phase.DEGRADED_READY)
                && !current.hasAllPinnedAssets()) {
            throw new IllegalStateException("cannot become ready without all home assets");
        }
        return commit(true, current.generation(), nextPhase, current.home(),
                current.pinnedAssets(), current.pendingTransfer(), current.pendingAcquisition(),
                null, null, nowMillis);
    }

    /** Must be committed before the matching inventory click is issued. */
    public synchronized Snapshot beginTransfer(
            PendingTransfer transfer,
            long nowMillis) throws IOException {
        requireEnabledHome();
        Objects.requireNonNull(transfer, "transfer");
        requireActive();
        if (current.pendingTransfer() != null || current.pendingAcquisition() != null) {
            throw new IllegalStateException("another home operation is already pending");
        }
        return commit(true, current.generation(), Phase.RECONCILING_TRANSFER,
                current.home(), current.pinnedAssets(), transfer, null,
                null, null, nowMillis);
    }

    public synchronized Snapshot completeTransfer(String transferId, long nowMillis)
            throws IOException {
        requirePendingTransfer(transferId);
        requireActive();
        return commit(true, current.generation(), Phase.OBSERVING_STOCK,
                current.home(), current.pinnedAssets(), null, null,
                null, null, nowMillis);
    }

    /** Commits exact transfer truth while a higher-priority owner remains active. */
    public synchronized Snapshot completeTransferWhilePaused(
            String transferId,
            long nowMillis) throws IOException {
        requirePendingTransfer(transferId);
        if (current.phase() != Phase.PAUSED) {
            throw new IllegalStateException("paused transfer completion requires PAUSED state");
        }
        Pause pause = new Pause(
                Phase.OBSERVING_STOCK, current.pause().reason(), current.pause().pausedAtMillis());
        return commit(true, current.generation(), Phase.PAUSED,
                current.home(), current.pinnedAssets(), null, null,
                pause, null, nowMillis);
    }

    /**
     * Explicitly retires an unchanged intent after its empty-cursor before
     * state was reobserved. This is abandonment, never a successful transfer.
     */
    public synchronized Snapshot abandonTransfer(String transferId, long nowMillis)
            throws IOException {
        requirePendingTransfer(transferId);
        requireEnabled();
        return commit(true, current.generation(), Phase.OBSERVING_STOCK,
                current.home(), current.pinnedAssets(), null, null,
                null, null, nowMillis);
    }

    /**
     * Atomically replaces an unresolved intent after reopening the same owned
     * chest or observing a conserved partial acknowledgement.  The replacement
     * is persisted before its slot/sync IDs may be clicked.
     */
    public synchronized Snapshot rebaseTransfer(
            String transferId,
            PendingTransfer replacement,
            long nowMillis) throws IOException {
        requirePendingTransfer(transferId);
        requireActive();
        validateTransferReplacement(replacement);
        return commit(true, current.generation(), Phase.RECONCILING_TRANSFER,
                current.home(), current.pinnedAssets(), replacement, null,
                null, null, nowMillis);
    }

    /** Persists a conserved/rebound remainder without resuming idle work. */
    public synchronized Snapshot rebaseTransferWhilePaused(
            String transferId,
            PendingTransfer replacement,
            long nowMillis) throws IOException {
        requirePendingTransfer(transferId);
        if (current.phase() != Phase.PAUSED) {
            throw new IllegalStateException("paused transfer rebase requires PAUSED state");
        }
        validateTransferReplacement(replacement);
        Pause pause = new Pause(
                Phase.RECONCILING_TRANSFER,
                current.pause().reason(), current.pause().pausedAtMillis());
        return commit(true, current.generation(), Phase.PAUSED,
                current.home(), current.pinnedAssets(), replacement, null,
                pause, null, nowMillis);
    }

    /** Records an honest terminal transfer problem while retaining preemption. */
    public synchronized Snapshot blockTransferWhilePaused(
            String code,
            String detail,
            boolean retryable,
            long nowMillis) throws IOException {
        if (current.phase() != Phase.PAUSED || current.pendingTransfer() == null) {
            throw new IllegalStateException("paused transfer block requires a pending transfer");
        }
        Block block = new Block(
                Phase.RECONCILING_TRANSFER, code, detail, retryable, checkedTime(nowMillis));
        Pause pause = new Pause(
                Phase.BLOCKED, current.pause().reason(), current.pause().pausedAtMillis());
        return commit(true, current.generation(), Phase.PAUSED,
                current.home(), current.pinnedAssets(), current.pendingTransfer(), null,
                pause, block, nowMillis);
    }

    public synchronized Snapshot beginAcquisition(
            PendingAcquisition acquisition,
            long nowMillis) throws IOException {
        requireEnabledHome();
        Objects.requireNonNull(acquisition, "acquisition");
        requireActive();
        if (current.pendingTransfer() != null || current.pendingAcquisition() != null) {
            throw new IllegalStateException("another home operation is already pending");
        }
        return commit(true, current.generation(), Phase.ACQUIRING,
                current.home(), current.pinnedAssets(), null, acquisition,
                null, null, nowMillis);
    }

    /** Pairs the Home phase cursor with the separately checksummed water intent. */
    public synchronized Snapshot beginWaterFill(long nowMillis) throws IOException {
        requireEnabledHome();
        requireActive();
        requireNoPendingOperation("fill the Home water bucket");
        return commit(true, current.generation(), Phase.ACQUIRING,
                current.home(), current.pinnedAssets(), null, null,
                null, null, nowMillis);
    }

    public synchronized Snapshot completeWaterFill(long nowMillis) throws IOException {
        requireEnabledHome();
        requireActive();
        if (current.phase() != Phase.ACQUIRING || current.pendingAcquisition() != null) {
            throw new IllegalStateException("Home is not reconciling a water fill");
        }
        return commit(true, current.generation(), Phase.OBSERVING_STOCK,
                current.home(), current.pinnedAssets(), null, null,
                null, null, nowMillis);
    }

    /** Retires a proved unchanged fill intent without claiming a water bucket. */
    public synchronized Snapshot abandonWaterFill(long nowMillis) throws IOException {
        requireEnabledHome();
        requireActive();
        if (current.phase() != Phase.ACQUIRING || current.pendingAcquisition() != null) {
            throw new IllegalStateException("Home is not reconciling a water fill");
        }
        return commit(true, current.generation(), Phase.OBSERVING_STOCK,
                current.home(), current.pinnedAssets(), null, null,
                null, null, nowMillis);
    }

    public synchronized Snapshot completeWaterFillWhilePaused(long nowMillis)
            throws IOException {
        requireEnabledHome();
        if (current.phase() != Phase.PAUSED || current.pendingAcquisition() != null
                || current.pause().resumePhase() != Phase.ACQUIRING) {
            throw new IllegalStateException("paused Home is not reconciling a water fill");
        }
        Pause pause = new Pause(
                Phase.OBSERVING_STOCK,
                current.pause().reason(),
                current.pause().pausedAtMillis());
        return commit(true, current.generation(), Phase.PAUSED,
                current.home(), current.pinnedAssets(), null, null,
                pause, null, nowMillis);
    }

    public synchronized Snapshot blockWaterFillWhilePaused(
            String code,
            String detail,
            boolean retryable,
            long nowMillis) throws IOException {
        requireEnabledHome();
        if (current.phase() != Phase.PAUSED || current.pendingTransfer() != null
                || current.pendingAcquisition() != null
                || current.pause().resumePhase() != Phase.ACQUIRING) {
            throw new IllegalStateException("paused Home is not reconciling a water fill");
        }
        Block block = new Block(
                Phase.ACQUIRING, code, detail, retryable, checkedTime(nowMillis));
        Pause pause = new Pause(
                Phase.BLOCKED, current.pause().reason(), current.pause().pausedAtMillis());
        return commit(true, current.generation(), Phase.PAUSED,
                current.home(), current.pinnedAssets(), null, null,
                pause, block, nowMillis);
    }

    public synchronized Snapshot completeAcquisition(String planId, long nowMillis)
            throws IOException {
        requirePendingAcquisition(planId);
        requireActive();
        if (current.phase() != Phase.RETURNING_HOME) {
            throw new IllegalStateException(
                    "acquisition completion requires its durable exact-Home return phase");
        }
        return commit(true, current.generation(), Phase.RETURNING_HOME,
                current.home(), current.pinnedAssets(), null, null,
                null, null, nowMillis);
    }

    /**
     * Persists the postcondition-proved return leg without clearing the exact
     * planner identity or its inventory goals.
     */
    public synchronized Snapshot beginAcquisitionReturn(
            String planId,
            long nowMillis) throws IOException {
        requirePendingAcquisition(planId);
        requireActive();
        if (current.phase() == Phase.RETURNING_HOME) return current;
        if (current.phase() != Phase.ACQUIRING) {
            throw new IllegalStateException(
                    "acquisition return requires the active acquisition phase");
        }
        return commit(true, current.generation(), Phase.RETURNING_HOME,
                current.home(), current.pinnedAssets(), null, current.pendingAcquisition(),
                null, null, nowMillis);
    }

    /**
     * Retires a terminal, cleared, or missing planner checkpoint without
     * asserting that its goals were met. Current inventory/chest truth is
     * reobserved and a later cycle may compile a fresh deficit.
     */
    public synchronized Snapshot retireAcquisition(String planId, long nowMillis)
            throws IOException {
        requirePendingAcquisition(planId);
        requireEnabled();
        return commit(true, current.generation(), Phase.RETURNING_HOME,
                current.home(), current.pinnedAssets(), null, null,
                null, null, nowMillis);
    }

    /**
     * Explicit foreground work may retire an acquisition that has not issued
     * an inventory transaction. This records abandonment and re-observation;
     * it never claims the resource goals succeeded.
     */
    public synchronized Snapshot abandonAcquisitionForForeground(
            String planId,
            long nowMillis) throws IOException {
        requirePendingAcquisition(planId);
        requireEnabled();
        return commit(true, current.generation(), Phase.OBSERVING_STOCK,
                current.home(), current.pinnedAssets(), null, null,
                null, null, nowMillis);
    }

    /** Operator-authorized retry which still preserves every durable intent. */
    public synchronized Snapshot retryBlockedForReobservation(long nowMillis)
            throws IOException {
        requireEnabled();
        if (current.phase() != Phase.BLOCKED) return current;
        return commit(true, current.generation(), current.block().retryPhase(), current.home(),
                current.pinnedAssets(), current.pendingTransfer(), current.pendingAcquisition(),
                null, null, nowMillis);
    }

    /**
     * Explicit sleep can reobserve a transient workspace/bed/return failure through
     * either visible state. This never claims the bed is usable or retires a
     * transaction: the live bed controller and custody drain must prove those.
     */
    public synchronized Snapshot reobserveForExplicitSleep(boolean pendingWaterFill, long nowMillis)
            throws IOException {
        if (!current.enabled()) {
            if (!HomeSleepPolicy.hasRegisteredBed(current))
                throw new IllegalStateException("Register Home and adopt its existing bed before sleeping");
            if (pendingWaterFill)
                throw new IllegalStateException("Home sleep must wait for the existing water-fill custody");
            // DISABLED is the Stock lifecycle, not a ban on using an adopted bed.
            // Keep the exact persisted snapshot: no resume/enable/provisioning phase.
            return current;
        }
        Block block = current.block();
        boolean pendingDebt = current.pendingTransfer() != null
                || current.pendingAcquisition() != null || pendingWaterFill;
        if (block != null && !HomeSleepPolicy.mayReobserveBlock(
                block.code(), block.retryable(), pendingDebt)) {
            throw new IllegalStateException("Home is blocked: " + block.detail());
        }
        if (current.phase() == Phase.PAUSED) resume(nowMillis);
        if (current.phase() == Phase.BLOCKED) retryBlockedForReobservation(nowMillis);
        return current;
    }

    /** Higher-priority work preserves every operation and its exact resume phase. */
    public synchronized Snapshot pause(String reason, long nowMillis) throws IOException {
        requireEnabled();
        if (current.phase() == Phase.PAUSED) return current;
        Phase resume = current.phase();
        Pause pause = new Pause(resume, reason, checkedTime(nowMillis));
        return commit(true, current.generation(), Phase.PAUSED, current.home(),
                current.pinnedAssets(), current.pendingTransfer(), current.pendingAcquisition(),
                pause, current.block(), nowMillis);
    }

    public synchronized Snapshot resume(long nowMillis) throws IOException {
        requireEnabled();
        if (current.phase() != Phase.PAUSED) return current;
        Phase next = current.pause().resumePhase();
        return commit(true, current.generation(), next, current.home(),
                current.pinnedAssets(), current.pendingTransfer(), current.pendingAcquisition(),
                null, current.block(), nowMillis);
    }

    /**
     * Converts an already-paused Home operation into a durable blocked resume
     * cursor without discarding its pending transfer/acquisition.  This is the
     * generic safety equivalent of the narrower transfer/water reconciliation
     * helpers: higher-priority protection remains the visible owner, while a
     * later resume lands on the exact blocked result instead of re-entering the
     * unsafe phase.
     */
    public synchronized Snapshot blockWhilePaused(
            String code,
            String detail,
            boolean retryable,
            long nowMillis) throws IOException {
        requireEnabled();
        if (current.phase() != Phase.PAUSED
                || current.pause().resumePhase() == Phase.BLOCKED) {
            throw new IllegalStateException("generic paused block requires one active resume phase");
        }
        Phase retryPhase = current.pause().resumePhase();
        Block block = new Block(
                retryPhase, code, detail, retryable, checkedTime(nowMillis));
        Pause pause = new Pause(
                Phase.BLOCKED,
                current.pause().reason(),
                current.pause().pausedAtMillis());
        return commit(true, current.generation(), Phase.PAUSED,
                current.home(), current.pinnedAssets(), current.pendingTransfer(),
                current.pendingAcquisition(), pause, block, nowMillis);
    }

    public synchronized Snapshot block(
            String code,
            String detail,
            boolean retryable,
            long nowMillis) throws IOException {
        requireEnabled();
        requireActive();
        Phase retry = current.phase();
        Block block = new Block(retry, code, detail, retryable, checkedTime(nowMillis));
        return commit(true, current.generation(), Phase.BLOCKED, current.home(),
                current.pinnedAssets(), current.pendingTransfer(), current.pendingAcquisition(),
                null, block, nowMillis);
    }

    public synchronized Snapshot retryBlocked(long nowMillis) throws IOException {
        requireEnabled();
        if (current.phase() != Phase.BLOCKED) return current;
        if (!current.block().retryable()) {
            throw new IllegalStateException("home economy block is not retryable");
        }
        return commit(true, current.generation(), current.block().retryPhase(), current.home(),
                current.pinnedAssets(), current.pendingTransfer(), current.pendingAcquisition(),
                null, null, nowMillis);
    }

    /** Death invalidates live GUI/route state but never discards a durable intent. */
    public synchronized Snapshot pauseForDeath(long nowMillis) throws IOException {
        requireEnabled();
        if (current.phase() == Phase.PAUSED) return current;
        Phase resume = current.phase() == Phase.BLOCKED
                ? Phase.BLOCKED
                : current.pendingTransfer() != null
                ? Phase.RECONCILING_TRANSFER
                : current.pendingAcquisition() != null
                ? (current.phase() == Phase.RETURNING_HOME
                        ? Phase.RETURNING_HOME : Phase.ACQUIRING)
                : current.phase();
        Pause pause = new Pause(resume, "death boundary; reobserve world and inventory",
                checkedTime(nowMillis));
        return commit(true, current.generation(), Phase.PAUSED, current.home(),
                current.pinnedAssets(), current.pendingTransfer(), current.pendingAcquisition(),
                pause, current.block(), nowMillis);
    }

    public static String assetIdPrefix(long generation, AssetRole role) {
        if (generation <= 0) throw new IllegalArgumentException("home generation must be positive");
        Objects.requireNonNull(role, "role");
        return "home:" + generation + ':' + role.item() + ':';
    }

    public static String fingerprint(String dimension, int x, int y, int z) {
        String identity = requireText(dimension, "dimension", 256)
                + '\n' + x + '\n' + y + '\n' + z;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                encoded.append(Character.forDigit((digest[index] >>> 4) & 0x0f, 16));
                encoded.append(Character.forDigit(digest[index] & 0x0f, 16));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private Snapshot commit(
            boolean enabled,
            long generation,
            Phase phase,
            HomeAnchor home,
            Map<AssetRole, PinnedAsset> pinnedAssets,
            PendingTransfer pendingTransfer,
            PendingAcquisition pendingAcquisition,
            Pause pause,
            Block block,
            long nowMillis) throws IOException {
        boolean sameHome = home != null && home.equals(current.home()) && generation == current.generation();
        return commit(enabled, generation, phase, home, pinnedAssets, pendingTransfer,
                pendingAcquisition, pause, block, nowMillis,
                sameHome ? current.storageRegistrations() : legacyStorage(pinnedAssets),
                sameHome && current.storageExplicitlyMissing());
    }

    private Snapshot commit(boolean enabled, long generation, Phase phase, HomeAnchor home,
            Map<AssetRole, PinnedAsset> pinnedAssets, PendingTransfer pendingTransfer,
            PendingAcquisition pendingAcquisition, Pause pause, Block block, long nowMillis,
            Map<String, StorageRegistration> storage, boolean explicitlyMissing) throws IOException {
        Snapshot candidate = new Snapshot(Math.incrementExact(current.revision()),
                enabled, generation, phase, home, pinnedAssets, pendingTransfer,
                pendingAcquisition, pause, block, checkedTime(nowMillis), storage, explicitlyMissing);
        store.save(candidate);
        current = candidate;
        return candidate;
    }

    private void requireEnabled() {
        if (!current.enabled()) throw new IllegalStateException("home economy is disabled");
    }

    private void requireEnabledHome() {
        requireEnabled();
        if (current.home() == null) throw new IllegalStateException("home is not established");
    }

    private void requireActive() {
        if (current.phase() == Phase.PAUSED || current.phase() == Phase.BLOCKED) {
            throw new IllegalStateException("home economy is not active");
        }
    }

    private void requireNoPendingOperation(String operation) {
        if (current.pendingTransfer() != null || current.pendingAcquisition() != null) {
            throw new IllegalStateException("cannot " + operation
                    + " while a durable home operation is pending");
        }
    }

    private void requirePendingTransfer(String id) {
        String expected = requireText(id, "transferId", 256);
        if (current.pendingTransfer() == null
                || !current.pendingTransfer().id().equals(expected)) {
            throw new IllegalArgumentException("transfer ID does not match the durable intent");
        }
    }

    private void requirePendingAcquisition(String id) {
        String expected = requireText(id, "planId", 256);
        if (current.pendingAcquisition() == null
                || !current.pendingAcquisition().planId().equals(expected)) {
            throw new IllegalArgumentException("plan ID does not match the durable acquisition");
        }
    }

    private void validateTransferReplacement(PendingTransfer replacement) {
        Objects.requireNonNull(replacement, "replacement");
        PendingTransfer previous = current.pendingTransfer();
        if (replacement.direction() != previous.direction()
                || !replacement.categoryId().equals(previous.categoryId())
                || !replacement.item().equals(previous.item())) {
            throw new IllegalArgumentException(
                    "rebased transfer must preserve direction, category, and exact item");
        }
        if (replacement.count() > previous.count()) {
            throw new IllegalArgumentException(
                    "rebased transfer cannot exceed the unresolved item count");
        }
        if (previous.containerIdentity() != null
                && !previous.containerIdentity().equals(replacement.containerIdentity())) {
            throw new IllegalArgumentException("rebased transfer must preserve the exact connected container and slot layout");
        }
        if (previous.containerIdentity() == null && replacement.containerIdentity() != null) {
            StorageRegistration primary = current.primaryStorage();
            if (primary == null || !primary.id().equals(replacement.containerIdentity().storageId())
                    || replacement.containerIdentity().halves().size() != 1) {
                throw new IllegalArgumentException("legacy transfer can only bind the existing single primary chest after reconciliation");
            }
        }
    }

    private static Map<String, StorageRegistration> legacyStorage(Map<AssetRole, PinnedAsset> pins) {
        PinnedAsset chest = Objects.requireNonNull(pins, "pinnedAssets").get(AssetRole.CHEST);
        return chest == null ? Map.of() : Map.of("primary", new StorageRegistration(
                "primary", chest.ledgerAssetId(), chest.pinnedAtMillis()));
    }

    private static Block missingStorageBlock(long nowMillis) {
        return new Block(Phase.VERIFYING_ASSETS, "home_storage_missing",
                "All storage registrations were explicitly removed; register or bind a chest",
                false, checkedTime(nowMillis));
    }

    private static boolean ownsUnderlyingPhase(
            Phase phase,
            Pause pause,
            Block block,
            Phase expected) {
        if (phase == expected) return true;
        if (phase == Phase.PAUSED) {
            Phase resumed = pause.resumePhase();
            if (resumed == expected) return true;
            return resumed == Phase.BLOCKED && block != null && block.retryPhase() == expected;
        }
        return phase == Phase.BLOCKED && block != null && block.retryPhase() == expected;
    }

    private static long checkedTime(long value) {
        if (value < 0) throw new IllegalArgumentException("time cannot be negative");
        return value;
    }

    private static String normalizeItem(String value) {
        String item = requireText(value, "item", 256)
                .toLowerCase(Locale.ROOT).replace(' ', '_');
        return item.startsWith("minecraft:")
                ? item.substring("minecraft:".length()) : item;
    }

    private static String requireId(String value, String field) {
        String id = requireText(value, field, 128)
                .toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!id.matches("[a-z0-9_.:-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return id;
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
