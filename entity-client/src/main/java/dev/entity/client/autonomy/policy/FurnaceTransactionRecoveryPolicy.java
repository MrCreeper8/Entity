package dev.entity.client.autonomy.policy;

import java.util.Locale;
import java.util.Objects;

/**
 * Decides whether local recovery may tear down an open furnace transaction.
 *
 * <p>A cursor or handler-layout failure can be a one-packet acknowledgement
 * race while Minecraft is moving mission input/fuel between slots. When the
 * open handler still contains recognizable mission cargo, closing it and
 * rejecting/reselecting the workstation can strand that cargo in the world.
 * The safe response is to keep the exact handler open and let the bounded
 * action supervisor retry in place. Persistent failures still become BLOCKED;
 * they simply cannot discard the furnace transaction on the way there.</p>
 */
public final class FurnaceTransactionRecoveryPolicy {
    private FurnaceTransactionRecoveryPolicy() {
    }

    public static boolean preserveOpenTransaction(
            String actionKind,
            String failureCode,
            Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!"smelt".equals(normalize(actionKind)) || !snapshot.furnaceOpen()) return false;
        String failure = normalize(failureCode);
        if (!failure.equals("cursor_occupied") && !failure.equals("slot_unavailable")) return false;
        return snapshot.hasMissionCargo();
    }

    public record Snapshot(
            boolean furnaceOpen,
            boolean expectedInputPresent,
            boolean expectedFuelPresent,
            boolean expectedOutputPresent,
            boolean expectedCursorPresent) {
        public boolean hasMissionCargo() {
            return expectedInputPresent || expectedFuelPresent
                    || expectedOutputPresent || expectedCursorPresent;
        }

        public static Snapshot closed() {
            return new Snapshot(false, false, false, false, false);
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_');
    }
}
