package dev.entity.client.baritone;

/**
 * A complete close-combat movement frame.
 *
 * <p>Every tactical decision rewrites all six movement controls.  Use-item ownership is
 * intentionally absent: the generation-fenced actuator owns shield use independently.</p>
 */
final class CloseCombatMovementPolicy {
    record Frame(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump,
            boolean sprint) {
        static Frame neutral() {
            return new Frame(false, false, false, false, false, false);
        }
    }

    private CloseCombatMovementPolicy() { }

    static Frame frameFor(CombatEngagementPolicy.Action action) {
        return frameFor(action, false);
    }

    static Frame frameFor(CombatEngagementPolicy.Action action, boolean sprintCorridorVerified) {
        if (action == null) return Frame.neutral();
        return switch (action) {
            case ADVANCE -> new Frame(true, false, false, false, false, sprintCorridorVerified);
            case SHIELD_ADVANCE -> new Frame(true, false, false, false, false, false);
            case BACKSTEP -> new Frame(false, true, false, false, false, false);
            case STRAFE_LEFT -> new Frame(false, false, true, false, false, false);
            case STRAFE_RIGHT -> new Frame(false, false, false, true, false, false);
            default -> Frame.neutral();
        };
    }
}
