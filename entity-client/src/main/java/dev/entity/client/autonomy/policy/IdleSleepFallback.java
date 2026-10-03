package dev.entity.client.autonomy.policy;

/** Transient authority to reobserve only the failure produced by one automatic bed attempt. */
public final class IdleSleepFallback {
    private long generation = -1;
    private long admittedRevision = -1;
    private String homeFingerprint = "";
    private long failedRevision = -1;
    private HomeEconomySession.Block failure;

    public void begin(HomeEconomySession.Snapshot snapshot) {
        clear();
        if (snapshot == null || !snapshot.enabled() || snapshot.home() == null || snapshot.block() != null) return;
        generation = snapshot.generation();
        admittedRevision = snapshot.revision();
        homeFingerprint = snapshot.home().fingerprint();
    }

    /** Called only with the durable result of this attempt's exact bed controller. */
    public void failed(HomeEconomySession.Snapshot snapshot) {
        if (!sameHome(snapshot) || snapshot.revision() <= admittedRevision
                || snapshot.phase() != HomeEconomySession.Phase.BLOCKED
                || snapshot.block() == null || !snapshot.block().retryable()
                || !snapshot.block().code().equals("home_sleep_blocked")) {
            clear();
            return;
        }
        failedRevision = snapshot.revision();
        failure = snapshot.block();
    }

    /** Settled is the existing Home stock observation, including water and GUI/receipt custody. */
    public boolean mayReobserve(HomeEconomySession.Snapshot snapshot, boolean settled) {
        return settled && sameHome(snapshot) && failure != null
                && snapshot.phase() == HomeEconomySession.Phase.BLOCKED
                && snapshot.revision() == failedRevision && failure.equals(snapshot.block())
                && snapshot.pendingTransfer() == null && snapshot.pendingAcquisition() == null;
    }

    public void clear() {
        generation = -1;
        admittedRevision = -1;
        homeFingerprint = "";
        failedRevision = -1;
        failure = null;
    }

    private boolean sameHome(HomeEconomySession.Snapshot snapshot) {
        return snapshot != null && snapshot.enabled() && snapshot.home() != null
                && snapshot.generation() == generation && snapshot.home().fingerprint().equals(homeFingerprint);
    }
}
