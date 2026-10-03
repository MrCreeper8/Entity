package dev.entity.core.stewardship;

import java.util.Objects;

/** Vanilla admission policy for loaded item entities. Provenance never blocks pickup. */
public final class ForeignItemProvenancePolicy {
    private ForeignItemProvenancePolicy() {
    }

    public enum Code {
        ALLOWED,
        OWNER_HANDOFF,
        POLICY_UNAVAILABLE,
        FOREIGN_PLAYER_DROP
    }

    public static Decision decide(boolean policyAvailable, boolean claimedByAnotherPlayer) {
        return decide(policyAvailable, claimedByAnotherPlayer, false);
    }

    public static Decision decide(
            boolean policyAvailable,
            boolean playerClaimPresent,
            boolean claimedByConfiguredOwner) {
        return new Decision(true, Code.ALLOWED,
                "ground items use vanilla Minecraft pickup behavior");
    }

    public record Decision(boolean allowed, Code code, String detail) {
        public Decision {
            code = Objects.requireNonNull(code, "code");
            detail = Objects.requireNonNullElse(detail, "");
            if (allowed != (code == Code.ALLOWED || code == Code.OWNER_HANDOFF)) {
                throw new IllegalArgumentException("foreign-item decision/code mismatch");
            }
        }
    }
}
