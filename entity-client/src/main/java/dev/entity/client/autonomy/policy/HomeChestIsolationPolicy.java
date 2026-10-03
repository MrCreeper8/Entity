package dev.entity.client.autonomy.policy;

/** Exact ownership rule: a Home chest must remain a standalone container. */
public final class HomeChestIsolationPolicy {
    private HomeChestIsolationPolicy() {
    }

    public static boolean standalone(
            boolean northMergeCompatible,
            boolean southMergeCompatible,
            boolean eastMergeCompatible,
            boolean westMergeCompatible) {
        return !northMergeCompatible && !southMergeCompatible
                && !eastMergeCompatible && !westMergeCompatible;
    }
}
