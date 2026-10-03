package dev.entity.client.autonomy.policy;

import java.util.Locale;
import java.util.Objects;

/**
 * Pure fail-closed gate for an explicit {@code /e stock run} recovery.
 * It authorizes only observation/retirement; it never authorizes a click.
 */
public final class HomeManualRecoveryPolicy {
    public enum Action {
        NOT_APPLICABLE,
        WAIT_CURSOR_EMPTY,
        WAIT_TRANSACTION_OWNER,
        REOBSERVE_TRANSFER,
        RETRY_ACQUISITION_RETURN,
        RETIRE_ACQUISITION,
        REJECT_UNSAFE_STATE
    }

    public record Observation(
            String blockCode,
            boolean pendingTransfer,
            boolean pendingAcquisition,
            boolean pendingAcquisitionReturn,
            HomeEconomyPolicy.AcquisitionTruth acquisitionTruth,
            boolean cursorEmpty,
            String pendingClickOwner,
            String cursorOwner,
            int pendingRemovals) {
        public Observation {
            blockCode = Objects.requireNonNullElse(blockCode, "")
                    .trim().toLowerCase(Locale.ROOT);
            acquisitionTruth = Objects.requireNonNull(
                    acquisitionTruth, "acquisitionTruth");
            pendingClickOwner = Objects.requireNonNullElse(pendingClickOwner, "").trim();
            cursorOwner = Objects.requireNonNullElse(cursorOwner, "").trim();
            if (pendingRemovals < 0) {
                throw new IllegalArgumentException("pendingRemovals cannot be negative");
            }
        }
    }

    public record Decision(Action action, String detail) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            detail = Objects.requireNonNullElse(detail, "").trim();
        }
    }

    private HomeManualRecoveryPolicy() {
    }

    public static Decision decide(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        boolean recoverableTransfer = observation.pendingTransfer()
                && switch (observation.blockCode()) {
            case "home_cursor_not_owned", "home_transfer_counts_diverged",
                    "transfer_counts_diverged", "asset_identity_mismatch",
                    "home_binding_changed", "home_settings_missing",
                    "pending_transfer_chest_unverified" -> true;
            default -> false;
        };
        boolean recoverableAcquisition = observation.pendingAcquisition()
                && !observation.pendingAcquisitionReturn()
                && switch (observation.acquisitionTruth()) {
            case NONE, COMPLETED, FAILED, DIVERGED -> true;
            case ACTIVE -> false;
        };
        boolean recoverableReturn = observation.pendingAcquisition()
                && observation.pendingAcquisitionReturn();
        if (!recoverableTransfer && !recoverableAcquisition && !recoverableReturn) {
            return new Decision(Action.NOT_APPLICABLE,
                    "the blocked state has no safe explicit recovery transition");
        }
        if (!observation.cursorEmpty()) {
            return new Decision(Action.WAIT_CURSOR_EMPTY,
                    "Empty the manual/foreign cursor; Entity will issue zero clicks");
        }
        if (!observation.pendingClickOwner().isBlank()
                || !observation.cursorOwner().isBlank()
                || observation.pendingRemovals() > 0) {
            return new Decision(Action.WAIT_TRANSACTION_OWNER,
                    "Wait for the existing inventory transaction owner to finish");
        }
        if (recoverableTransfer) {
            return new Decision(Action.REOBSERVE_TRANSFER,
                    "Reopen the exact old chest and classify final, unchanged, partial, or divergent counts before any click");
        }
        if (recoverableReturn) {
            return new Decision(Action.RETRY_ACQUISITION_RETURN,
                    "Retry the latched exact-anchor return without clearing planner ownership");
        }
        return new Decision(Action.RETIRE_ACQUISITION,
                "Retire the terminal/missing acquisition without claiming its goals and reobserve stock");
    }
}
