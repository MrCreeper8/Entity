package dev.entity.client.control;

import java.util.Objects;

/**
 * Minecraft-independent projection of a {@link MovementFrame} into the exact
 * sampled buttons and normalized movement vector vanilla expects.
 */
public record MovementInputSnapshot(
        boolean forward,
        boolean backward,
        boolean left,
        boolean right,
        boolean jump,
        boolean sneak,
        boolean sprint,
        float sideways,
        float ahead) {

    public static MovementInputSnapshot from(MovementFrame frame) {
        Objects.requireNonNull(frame, "frame");
        float ahead = axis(frame.forward(), frame.backward());
        float sideways = axis(frame.left(), frame.right());
        float length = (float) Math.sqrt(sideways * sideways + ahead * ahead);
        if (length > 0.0F) {
            sideways /= length;
            ahead /= length;
        }
        return new MovementInputSnapshot(
                frame.forward(), frame.backward(), frame.left(), frame.right(),
                frame.jump(), frame.sneak(), frame.sprint(), sideways, ahead);
    }

    private static float axis(boolean positive, boolean negative) {
        if (positive == negative) return 0.0F;
        return positive ? 1.0F : -1.0F;
    }
}
