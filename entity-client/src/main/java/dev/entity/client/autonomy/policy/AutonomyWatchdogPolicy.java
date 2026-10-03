package dev.entity.client.autonomy.policy;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Defines which executor owns no-progress detection for a hierarchical leaf. */
public final class AutonomyWatchdogPolicy {
    private static final Set<String> CONTROLLER_OWNED_LEAVES = Set.of(
            "recover_death_drops",
            "craft",
            "smelt",
            "equip",
            "deliver");

    public static boolean delegatesProgressToGlobalWatchdog(
            String leafKind,
            boolean adapterProgressExpected) {
        String normalized = Objects.requireNonNullElse(leafKind, "")
                .trim().toLowerCase(Locale.ROOT);
        return adapterProgressExpected && !CONTROLLER_OWNED_LEAVES.contains(normalized);
    }

    private AutonomyWatchdogPolicy() {
    }
}
