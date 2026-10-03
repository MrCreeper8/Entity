package dev.entitybridge.command;

import dev.entitybridge.config.BridgeSettings;

import java.util.Objects;
import java.util.Set;

/** Pure authorization policy for the primary owner and explicitly trusted controllers. */
final class ControllerAccessPolicy {
    private ControllerAccessPolicy() {
    }

    static Decision evaluate(BridgeSettings settings, String playerName, boolean hasOwnerPermission) {
        return evaluate(settings, playerName, hasOwnerPermission, settings.controllerNames(), false);
    }

    static Decision evaluate(
            BridgeSettings settings,
            String playerName,
            boolean hasOwnerPermission,
            Set<String> trustedControllers,
            boolean controllersLocked) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(trustedControllers, "trustedControllers");
        if (!settings.ownerConfigured()) return Decision.OWNER_NOT_CONFIGURED;
        if (settings.isPrimaryOwner(playerName)) {
            return hasOwnerPermission ? Decision.ALLOWED : Decision.OWNER_PERMISSION_REQUIRED;
        }
        if (controllersLocked) return Decision.CONTROLLERS_LOCKED;
        return trustedControllers.stream().anyMatch(playerName::equalsIgnoreCase)
                ? Decision.ALLOWED
                : Decision.NOT_TRUSTED;
    }

    enum Decision {
        ALLOWED,
        OWNER_NOT_CONFIGURED,
        OWNER_PERMISSION_REQUIRED,
        CONTROLLERS_LOCKED,
        NOT_TRUSTED
    }
}
