package dev.entitybridge.safety;

/** Compatibility policy: item provenance never changes vanilla gameplay. */
public final class ForeignItemClaimPolicy {
    private ForeignItemClaimPolicy() {
    }

    public enum Decision {
        ALLOW,
        CLAIM_PLAYER_DROP,
        REJECT_MERGE,
        REJECT_ENTITY_PICKUP
    }

    public static Decision playerDrop(String dropperName) {
        return Decision.ALLOW;
    }

    public static Decision merge(boolean sourceClaimed, boolean targetClaimed) {
        return Decision.ALLOW;
    }

    public static Decision pickup(
            String collectorName,
            boolean foreignClaimed,
            String claimOwnerName,
            String configuredOwnerName) {
        return Decision.ALLOW;
    }
}
