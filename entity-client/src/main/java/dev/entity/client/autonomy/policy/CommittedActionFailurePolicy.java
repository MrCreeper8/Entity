package dev.entity.client.autonomy.policy;

import dev.entity.core.port.BaritonePort;

import java.util.Objects;

/** Decides whether a failed committed action may discard and rebuild its program. */
public final class CommittedActionFailurePolicy {
    private CommittedActionFailurePolicy() {
    }

    /**
     * Only an ordinary verified action failure permits a new program. Route
     * exhaustion and owner-selected world policy are terminal until the world
     * or request changes; recompiling either would reproduce the same action
     * forever.
     */
    public static boolean shouldRecompile(BaritonePort.FailureCause cause) {
        return shouldRecompile(false, cause);
    }

    /**
     * A planner-authored mining/harvesting action is already bound to the
     * owner's acknowledged resource geometry.  Its actuator searches every
     * eligible loaded target in that fixed scope before it reports BLOCKED, so
     * rebuilding the same program against unchanged inventory cannot discover
     * another target.  Keep that exact child terminal until world authority or
     * the request changes instead of creating a new UUID every client tick.
     */
    public static boolean shouldRecompile(
            boolean fixedWorldScope,
            BaritonePort.FailureCause cause) {
        return !fixedWorldScope
                && Objects.requireNonNull(cause, "cause")
                == BaritonePort.FailureCause.NONE;
    }
}
