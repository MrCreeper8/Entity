package dev.entity.client.baritone;

/** Only a physically grounded, observed failed ascent can abandon native atomic movement. */
public final class NativeStalledAscendHandoff {
    private NativeStalledAscendHandoff() { }

    public record Evidence(boolean validLease, boolean observedFailedAscend,
                           boolean onGround, boolean independentSupport,
                           boolean inFluid, boolean breaking, boolean nativeMiningActive) { }

    public static boolean mayAbortUncancelableMovement(Evidence evidence) {
        return evidence.validLease() && evidence.observedFailedAscend()
                && evidence.onGround() && evidence.independentSupport()
                && !evidence.inFluid() && !evidence.breaking() && !evidence.nativeMiningActive();
    }
}
