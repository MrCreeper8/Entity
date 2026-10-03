package dev.entity.client.autonomy.policy;

/** Exact in-memory automatic ownership fence; restart never resumes this cancelled batch. */
public record IdleStockCancellation(long homeGeneration, String acquisitionId, String transferId) {
    public IdleStockCancellation {
        java.util.Objects.requireNonNull(acquisitionId);
        java.util.Objects.requireNonNull(transferId);
    }
    public String acquisitionToRetire(HomeEconomySession.Snapshot snapshot) {
        return snapshot.generation() == homeGeneration && snapshot.pendingAcquisition() != null
                && acquisitionId.equals(snapshot.pendingAcquisition().planId()) ? acquisitionId : "";
    }
    public String transferToRetire(HomeEconomySession.Snapshot snapshot) {
        return snapshot.generation() == homeGeneration && snapshot.pendingTransfer() != null
                && transferId.equals(snapshot.pendingTransfer().id()) ? transferId : "";
    }
}
