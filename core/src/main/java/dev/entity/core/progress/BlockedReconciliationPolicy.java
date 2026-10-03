package dev.entity.core.progress;

import java.util.Locale;

/**
 * Bounded retry policy for a BLOCKED mission whose missing fact may be
 * changed outside the executor (for example, an owned crafting table was
 * manually broken and returned to Entity's inventory).
 *
 * <p>This is deliberately an allow-list. Unknown/semantic failures stay
 * honestly blocked until an owner explicitly retries them; only known
 * physical-world or inventory conditions receive periodic reconciliation
 * attempts.</p>
 */
public final class BlockedReconciliationPolicy {
    private static final RetryPolicy BACKOFF = new RetryPolicy(5_000, 60_000);

    public long delayForAttempt(int attempt) {
        return BACKOFF.delayForFailure(attempt);
    }

    public boolean permitsAutomaticReconciliation(String reason) {
        if (reason == null || reason.isBlank()) return false;
        String normalized = reason.toLowerCase(Locale.ROOT);

        // Semantic/protocol errors cannot be repaired by waiting for a changed
        // Minecraft fact. Keep these parked instead of producing retry spam.
        if (containsAny(normalized,
                "unsupported plan", "unsupported prerequisite", "recipe unknown",
                "no recipe", "did not match the prepared recipient/item",
                "returned-handoff frame did not match", "rejected handoff preparation",
                "task plan is failed", "invalid command")) {
            return false;
        }

        return containsAny(normalized,
                // Owned workstation state may have changed while the mission was parked.
                "reclaim", "owned crafting table", "owned furnace", "workstation",
                // Placement/sightline geometry can be changed by a player or world update.
                "no reachable loaded placement", "placement rejected",
                "server rejected crafting table placement", "server rejected furnace placement",
                "no reachable sightline", "could not place crafting table", "could not place furnace",
                // Inventory or dropped-item facts can change without a new command.
                "inventory is full", "inventory may be full", "slot_unavailable",
                "slot unavailable", "cursor not empty", "could not pick up", "pickup proof did not arrive",
                "no longer has enough", "give needs");
    }

    private static boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (text.contains(fragment)) return true;
        }
        return false;
    }
}
