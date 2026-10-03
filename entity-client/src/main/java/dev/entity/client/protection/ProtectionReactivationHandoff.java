package dev.entity.client.protection;

import java.util.Objects;
import java.util.Optional;

/** Exact identity handoff retained until a projected PROTECTION decision reaches the adapter. */
public final class ProtectionReactivationHandoff {
    private String exactTargetId = "";

    public void arm(String targetId) {
        String normalized = Objects.requireNonNullElse(targetId, "").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("targetId cannot be blank");
        }
        exactTargetId = normalized;
    }

    /** Consumes the identity exactly once when the projected core decision reaches PROTECTION. */
    public Optional<String> consume() {
        String consumed = exactTargetId;
        exactTargetId = "";
        return consumed.isEmpty() ? Optional.empty() : Optional.of(consumed);
    }

    /** Consumes only the identity that actually acquired protection action ownership. */
    public boolean consumeIf(String selectedTargetId) {
        String selected = Objects.requireNonNullElse(selectedTargetId, "").trim();
        if (selected.isEmpty() || !selected.equals(exactTargetId)) return false;
        exactTargetId = "";
        return true;
    }

    public void clear() {
        exactTargetId = "";
    }

    public boolean armed() {
        return !exactTargetId.isEmpty();
    }

    public Optional<String> peek() {
        return exactTargetId.isEmpty() ? Optional.empty() : Optional.of(exactTargetId);
    }
}
