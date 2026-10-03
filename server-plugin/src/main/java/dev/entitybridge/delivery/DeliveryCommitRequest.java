package dev.entitybridge.delivery;

/**
 * Paper-authoritative request resolved from an already prepared delivery nonce.
 * The client supplies only the mission id and nonce; every item/recipient field
 * in this value comes from the durable server ledger.
 */
public record DeliveryCommitRequest(
        String missionId,
        String nonce,
        String recipient,
        String itemId,
        int expectedCount,
        int confirmedCount,
        int remainingCount,
        long timestamp,
        java.util.List<DeliveryStackSelection> selections) {
    public DeliveryCommitRequest {
        selections = selections == null ? java.util.List.of() : java.util.List.copyOf(selections);
    }
    public DeliveryCommitRequest(String missionId, String nonce, String recipient, String itemId,
            int expectedCount, int confirmedCount, int remainingCount, long timestamp) {
        this(missionId, nonce, recipient, itemId, expectedCount, confirmedCount, remainingCount, timestamp,
                java.util.List.of());
    }
}
