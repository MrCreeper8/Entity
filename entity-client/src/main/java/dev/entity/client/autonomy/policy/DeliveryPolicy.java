package dev.entity.client.autonomy.policy;

import java.util.Locale;
import java.util.Objects;

/** Pure delivery invariants shared by the planner, inventory and Baritone adapters. */
public final class DeliveryPolicy {
    public static final double HANDOFF_RANGE = 2.25;

    private DeliveryPolicy() {
    }

    /**
     * Normalizes an item ID without ever adding a second Minecraft namespace.
     * The repeated-prefix repair keeps already-persisted malformed delivery
     * frames recoverable instead of making a real inventory stack invisible.
     */
    public static String normalizeItemId(String value) {
        String normalized = Objects.requireNonNullElse(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_');
        while (normalized.startsWith("minecraft:minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    public static boolean withinHandoffRange(double squaredDistance) {
        if (!Double.isFinite(squaredDistance) || squaredDistance < 0.0) return false;
        return squaredDistance <= HANDOFF_RANGE * HANDOFF_RANGE;
    }

    /**
     * Baritone's follow radius is integral. Keep a full-block margin inside
     * the exact three-dimensional handoff radius so it cannot legitimately
     * stop just beyond the point where an item may be dropped.
     */
    public static int followRadius(double handoffRange) {
        if (!Double.isFinite(handoffRange) || handoffRange <= 0.0) {
            throw new IllegalArgumentException("handoffRange must be positive and finite");
        }
        return Math.max(1, (int) Math.floor(handoffRange) - 1);
    }

    /**
     * Bring missions own acquisition, so each trip targets the largest legal
     * batch even when current inventory is one reserved ration short.
     */
    public static int bringBatchSize(int remaining, int itemStackLimit) {
        if (remaining <= 0 || itemStackLimit <= 0) {
            throw new IllegalArgumentException("remaining and itemStackLimit must be positive");
        }
        return Math.min(remaining, itemStackLimit);
    }

    /**
     * A bring batch which is already physically carried goes straight to the
     * handoff. Acquisition is a deficit operation; it must never replace or
     * duplicate cargo that is already visible in the live inventory.
     */
    public static boolean readyForHandoff(int carriedCount, int batchCount) {
        if (carriedCount < 0 || batchCount <= 0) {
            throw new IllegalArgumentException(
                    "carriedCount must be non-negative and batchCount must be positive");
        }
        return carriedCount >= batchCount;
    }
}
