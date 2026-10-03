package dev.entity.client.autonomy.policy;

import java.util.Objects;

/** Pure ownership gate for draining Home before another inventory actor runs. */
public final class HomePreemptionDrainPolicy {
    public enum Action {
        ADVANCE_EXACT_TRANSFER,
        WAIT_EXACT_CHEST_OBSERVATION,
        WAIT_ACKNOWLEDGEMENT,
        REQUIRE_MANUAL_CURSOR_CLEAR,
        SAFE_CLOSE
    }

    public record Observation(
            boolean pendingTransfer,
            boolean exactPinnedChestOpen,
            boolean cursorEmpty,
            boolean exactCursorCustody,
            boolean pendingClick) {
    }

    public record Decision(Action action, boolean terminal, String detail) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            detail = Objects.requireNonNullElse(detail, "").trim();
        }
    }

    private HomePreemptionDrainPolicy() {
    }

    public static Decision decide(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (observation.pendingTransfer()) {
            if (!observation.cursorEmpty()) {
                if (!observation.exactPinnedChestOpen()
                        || !observation.exactCursorCustody()) {
                    return decision(Action.REQUIRE_MANUAL_CURSOR_CLEAR, false,
                            "foreign/manual cursor is not Home custody; issue zero clicks");
                }
                return decision(Action.ADVANCE_EXACT_TRANSFER, false,
                        "finish the persisted count and return its source-stack remainder");
            }
            if (!observation.exactPinnedChestOpen()) {
                return decision(Action.WAIT_EXACT_CHEST_OBSERVATION, false,
                        "pending cargo needs exact old-chest count truth before inventory handoff");
            }
            return decision(Action.ADVANCE_EXACT_TRANSFER, false,
                    "classify and advance the exact pending transfer");
        }
        if (!observation.cursorEmpty()) {
            return decision(Action.REQUIRE_MANUAL_CURSOR_CLEAR, false,
                    "non-transfer cursor cannot be adopted or generically dumped");
        }
        if (observation.pendingClick()) {
            return decision(Action.WAIT_ACKNOWLEDGEMENT, false,
                    "wait for the accepted inventory click before closing");
        }
        return decision(Action.SAFE_CLOSE, true,
                "cursor is empty and no inventory click remains in flight");
    }

    private static Decision decision(Action action, boolean terminal, String detail) {
        return new Decision(action, terminal, detail);
    }
}
