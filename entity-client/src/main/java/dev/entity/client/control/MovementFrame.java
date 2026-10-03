package dev.entity.client.control;

/**
 * One complete, immutable Minecraft movement sample.
 *
 * <p>Every button and both look axes travel through the actuator together. A
 * controller cannot accidentally leave a jump, sneak, or sprint bit behind
 * from an older owner.</p>
 */
public record MovementFrame(
        boolean forward,
        boolean backward,
        boolean left,
        boolean right,
        boolean jump,
        boolean sneak,
        boolean sprint,
        float yaw,
        float pitch) {

    public MovementFrame {
        if (!Float.isFinite(yaw)) {
            throw new IllegalArgumentException("yaw must be finite");
        }
        if (!Float.isFinite(pitch) || pitch < -90.0F || pitch > 90.0F) {
            throw new IllegalArgumentException("pitch must be finite and within [-90, 90]");
        }
    }

    /** A true no-input frame that preserves the player's current look. */
    public static MovementFrame neutral(float currentYaw, float currentPitch) {
        return new MovementFrame(
                false, false, false, false, false, false, false,
                currentYaw, currentPitch);
    }
}
