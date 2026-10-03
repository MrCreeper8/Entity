package dev.entitybridge.stewardship;

import java.util.Locale;
import java.util.Objects;

/** Distinguishes the player's own handler from a mutable world inventory. */
final class ProtectedInventorySurfacePolicy {
    private ProtectedInventorySurfacePolicy() {
    }

    static boolean isPlayerLocal(String inventoryType) {
        String normalized = Objects.requireNonNullElse(inventoryType, "")
                .trim().toUpperCase(Locale.ROOT);
        return normalized.equals("CRAFTING") || normalized.equals("PLAYER");
    }
}
