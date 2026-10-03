package dev.entity.client.autonomy.policy;

import java.util.Objects;

/** Fail-closed cross-dimension gate for coordinate goals. */
public final class DimensionRoutePolicy {
    public enum Action { ROUTE, PORTAL_UNAVAILABLE }

    public record Decision(Action action, String detail) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            detail = Objects.requireNonNullElse(detail, "").trim();
        }
    }

    private DimensionRoutePolicy() {
    }

    public static Decision decide(String currentDimension, String targetDimension) {
        String current = Objects.requireNonNullElse(currentDimension, "").trim();
        String target = Objects.requireNonNullElse(targetDimension, "").trim();
        if (target.isEmpty() || target.equals(current)) {
            return new Decision(Action.ROUTE, "coordinate goal is in the current dimension");
        }
        return new Decision(Action.PORTAL_UNAVAILABLE,
                "portal-unavailable: destination is in " + target
                        + " but Entity is in " + (current.isEmpty() ? "an unknown dimension" : current)
                        + "; travel through a portal, then retry the same checkpoint");
    }
}
