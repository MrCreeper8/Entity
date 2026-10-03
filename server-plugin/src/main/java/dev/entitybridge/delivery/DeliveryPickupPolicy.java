package dev.entitybridge.delivery;

import java.util.Objects;

/** Pure authorization policy for a Paper-tagged handoff item. */
final class DeliveryPickupPolicy {
    private DeliveryPickupPolicy() {
    }

    static Decision decide(String intendedRecipient, String actualPlayer) {
        String intended = Objects.requireNonNullElse(intendedRecipient, "").trim();
        String actual = Objects.requireNonNullElse(actualPlayer, "").trim();
        if (intended.isBlank() || actual.isBlank()) return Decision.REJECT;
        if (actual.equalsIgnoreCase(intended)) return Decision.INTENDED_RECIPIENT;
        if (actual.equalsIgnoreCase("Entity")) return Decision.ENTITY_RETURN;
        return Decision.REJECT;
    }

    enum Decision {
        INTENDED_RECIPIENT,
        ENTITY_RETURN,
        REJECT
    }
}
