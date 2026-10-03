package dev.entity.client.baritone;

import dev.entity.core.stewardship.ProtectedAreaPolicy;
import java.util.Objects;

/** A packet veto is not necessarily failure of the controller observing it. */
public final class NativeRouteInteractionPolicy {
    private NativeRouteInteractionPolicy() {}

    public static boolean failsRoute(ProtectedAreaPolicy.Action action) {
        // Navigation does not own use transactions. Home furniture and portal
        // controllers consume their synchronous rejection and request exact
        // authority. A stray use on furniture or the wall beside a door is still
        // vetoed before sending a packet, but is not evidence the route failed.
        // Break/place/fluid mutations retain the native route's failure boundary.
        return switch (Objects.requireNonNull(action)) {
            case CONTAINER, BLOCK_INTERACT -> false;
            default -> true;
        };
    }
}
