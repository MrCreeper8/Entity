package dev.entity.client.baritone;

import java.util.Objects;
import java.util.Optional;

/** Episode/exact-target/combat-context identity for protection traversal failure memory. */
final class ProtectionTraversalBudgetScope {
    private String activeKey = "";

    Transition enter(String episodeId, String targetId, String combatContext) {
        String next = key(episodeId, targetId, combatContext);
        if (next.equals(activeKey)) return new Transition(activeKey, next, false);
        String previous = activeKey;
        activeKey = next;
        return new Transition(previous, next, true);
    }

    Optional<String> clear() {
        String previous = activeKey;
        activeKey = "";
        return previous.isEmpty() ? Optional.empty() : Optional.of(previous);
    }

    String activeKey() {
        return activeKey;
    }

    static String key(String episodeId, String targetId, String combatContext) {
        return "protection-scope:"
                + required(episodeId, "episodeId") + ':'
                + required(targetId, "targetId") + ':'
                + required(combatContext, "combatContext");
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return normalized;
    }

    record Transition(String previousKey, String currentKey, boolean changed) {
    }
}
