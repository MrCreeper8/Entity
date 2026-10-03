package dev.entity.client.autonomy.policy;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Deterministic selection policy for one usable workstation asset. */
public final class FieldKitAssetPolicy {
    private FieldKitAssetPolicy() {
    }

    /**
     * Selects a durable asset without mutating the ledger. When the matching
     * workstation is physically present in inventory, a carried record wins;
     * remote cleanup records retain their ordinary urgency otherwise.
     */
    public static Optional<FieldKitLedger.Asset> select(
            List<FieldKitLedger.Asset> assets,
            String currentDimension,
            boolean workstationInInventory) {
        return select(assets, currentDimension, workstationInInventory, ignored -> true);
    }

    /** Ordinary mission workstations must never borrow or reclaim pinned home assets. */
    public static Optional<FieldKitLedger.Asset> selectOrdinary(
            List<FieldKitLedger.Asset> assets,
            String currentDimension,
            boolean workstationInInventory) {
        return select(assets, currentDimension, workstationInInventory,
                asset -> !isHomeAsset(asset.id()));
    }

    public static boolean isHomeAsset(String assetId) {
        return assetId != null && assetId.startsWith("home:");
    }

    /** Only states which still own an exact verified physical block are station capabilities. */
    public static boolean isReusablePlacement(FieldKitLedger.Asset asset) {
        Objects.requireNonNull(asset, "asset");
        return asset.lastKnownPosition() != null
                && asset.verification() != FieldKitLedger.Verification.NONE
                && (asset.state() == FieldKitLedger.AssetState.PLACED
                // Reclaim is deferred, but the exact block remains usable until that transaction
                // actually starts and moves it back to inventory.
                || asset.state() == FieldKitLedger.AssetState.RECLAIM_PENDING);
    }

    private static Optional<FieldKitLedger.Asset> select(
            List<FieldKitLedger.Asset> assets,
            String currentDimension,
            boolean workstationInInventory,
            Predicate<FieldKitLedger.Asset> scope) {
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(scope, "scope");
        String current = Objects.requireNonNullElse(currentDimension, "");
        return assets.stream()
                .filter(Objects::nonNull)
                .filter(scope)
                .sorted(Comparator
                        .comparingInt((FieldKitLedger.Asset asset) ->
                                priority(asset.state(), workstationInInventory))
                        .thenComparingInt(asset -> asset.lastKnownPosition() != null
                                && asset.lastKnownPosition().dimension().equals(current) ? 0 : 1)
                        .thenComparing(FieldKitLedger.Asset::id))
                .findFirst();
    }

    private static int priority(
            FieldKitLedger.AssetState state,
            boolean workstationInInventory) {
        if (workstationInInventory && state == FieldKitLedger.AssetState.CARRIED) return -1;
        return switch (state) {
            case RECLAIM_PENDING -> 0;
            case PLACED -> 1;
            case RECOVERY_REQUIRED -> 2;
            case LOST -> 3;
            case CARRIED -> 4;
        };
    }
}
