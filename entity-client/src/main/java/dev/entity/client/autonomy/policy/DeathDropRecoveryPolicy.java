package dev.entity.client.autonomy.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Pure, bounded policy for deciding whether a post-death item recovery is still truthful. */
public final class DeathDropRecoveryPolicy {
    /** Vanilla item entities despawn after five loaded minutes; leave a small routing margin. */
    public static final long MAXIMUM_RECOVERY_MILLIS = 285_000L;
    public static final long ORIGIN_SEARCH_GRACE_MILLIS = 15_000L;
    /**
     * Minecraft can clear the local player inventory before the first tick that reports death.
     * Retain only the immediately preceding alive observation; anything older is too ambiguous
     * to prove that the stacks were still carried when this death occurred.
     */
    public static final long MAXIMUM_PREDEATH_SNAPSHOT_AGE_MILLIS = 2_000L;
    public static final int MAXIMUM_ROUTE_FAILURES = 3;

    private DeathDropRecoveryPolicy() {
    }

    public record Capture(
            String dimension,
            int x,
            int y,
            int z,
            long diedAtMillis,
            Map<String, Integer> inventory) {
        public Capture {
            dimension = Objects.requireNonNullElse(dimension, "").trim().toLowerCase(Locale.ROOT);
            if (diedAtMillis < 0L) throw new IllegalArgumentException("death timestamp cannot be negative");
            inventory = normalizedCounts(inventory);
        }

        public long deadlineMillis() {
            return Math.addExact(diedAtMillis, MAXIMUM_RECOVERY_MILLIS);
        }
    }

    public enum Action {
        RECOVER,
        NONE_MISSING,
        WRONG_DIMENSION,
        EXPIRED
    }

    public record Decision(
            Action action,
            Map<String, Integer> missing,
            int missingCount,
            long remainingMillis,
            String detail) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            missing = normalizedCounts(missing);
            if (missingCount < 0) throw new IllegalArgumentException("missing count cannot be negative");
            if (remainingMillis < 0L) throw new IllegalArgumentException("remaining time cannot be negative");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public static Decision assess(
            Capture capture,
            Map<String, Integer> currentInventory,
            String currentDimension,
            long nowMillis) {
        Objects.requireNonNull(capture, "capture");
        Map<String, Integer> missing = missing(capture.inventory(), currentInventory);
        int missingCount = total(missing);
        if (missingCount == 0) {
            return new Decision(Action.NONE_MISSING, Map.of(), 0, 0L,
                    "no captured inventory is missing after respawn (keepInventory or no cargo loss)");
        }
        String dimension = Objects.requireNonNullElse(currentDimension, "")
                .trim().toLowerCase(Locale.ROOT);
        if (capture.dimension().isBlank() || dimension.isBlank()
                || !capture.dimension().equals(dimension)) {
            return new Decision(Action.WRONG_DIMENSION, missing, missingCount, 0L,
                    "death coordinates belong to a different or unknown dimension");
        }
        long remaining = Math.max(0L, capture.deadlineMillis() - nowMillis);
        if (remaining == 0L) {
            return new Decision(Action.EXPIRED, missing, missingCount, 0L,
                    "the bounded death-drop recovery window expired");
        }
        return new Decision(Action.RECOVER, missing, missingCount, remaining,
                "recover " + missingCount + " missing carried item(s) from the death site");
    }

    /**
     * Selects the inventory fact that may be bound to a new death checkpoint. A nonempty
     * death-boundary observation is authoritative. Vanilla commonly exposes an empty inventory
     * on that boundary, in which case only a fresh, causally earlier alive observation may be
     * used. Partial observations are never merged and stale/future observations fail closed.
     */
    public static Map<String, Integer> selectCapturedInventory(
            Map<String, Integer> deathBoundaryInventory,
            Map<String, Integer> lastAliveInventory,
            long lastAliveObservedAtMillis,
            long diedAtMillis) {
        if (diedAtMillis < 0L) {
            throw new IllegalArgumentException("death timestamp cannot be negative");
        }
        Map<String, Integer> current = normalizedCounts(deathBoundaryInventory);
        if (!current.isEmpty()) return current;

        Map<String, Integer> alive = normalizedCounts(lastAliveInventory);
        if (alive.isEmpty()
                || lastAliveObservedAtMillis < 0L
                || lastAliveObservedAtMillis > diedAtMillis
                || diedAtMillis - lastAliveObservedAtMillis
                > MAXIMUM_PREDEATH_SNAPSHOT_AGE_MILLIS) {
            return current;
        }
        return alive;
    }

    public static Map<String, Integer> missing(
            Map<String, Integer> captured,
            Map<String, Integer> current) {
        Map<String, Integer> before = normalizedCounts(captured);
        Map<String, Integer> now = normalizedCounts(current);
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        before.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            int deficit = entry.getValue() - now.getOrDefault(entry.getKey(), 0);
            if (deficit > 0) result.put(entry.getKey(), deficit);
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * A stable request threshold is essential: changing it every tick would reset the collection
     * scope and restart its route. The death-origin radius and exact missing IDs provide the
     * ownership correlation; the wall-clock recovery deadline bounds the remaining ambiguity.
     */
    public static long maximumCandidateAgeMillis(Capture capture, long nowMillis) {
        Objects.requireNonNull(capture, "capture");
        if (nowMillis < 0L) throw new IllegalArgumentException("current timestamp cannot be negative");
        return GroundItemAge.MAXIMUM_ITEM_AGE_MILLIS;
    }

    public static boolean originSearchExpired(long noCandidateSinceMillis, long nowMillis) {
        return noCandidateSinceMillis > 0L
                && nowMillis - noCandidateSinceMillis >= ORIGIN_SEARCH_GRACE_MILLIS;
    }

    public static String encodeCounts(Map<String, Integer> counts) {
        return normalizedCounts(counts).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + '=' + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    public static Map<String, Integer> decodeCounts(String encoded) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return Map.of();
        for (String part : encoded.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) continue;
            try {
                int count = Integer.parseInt(pair[1]);
                if (count > 0) result.merge(canonical(pair[0]), count, Math::addExact);
            } catch (NumberFormatException ignored) {
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public static int total(Map<String, Integer> counts) {
        int total = 0;
        for (int count : normalizedCounts(counts).values()) total = Math.addExact(total, count);
        return total;
    }

    private static Map<String, Integer> normalizedCounts(Map<String, Integer> raw) {
        Objects.requireNonNull(raw, "inventory counts");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        raw.forEach((item, count) -> {
            if (count == null || count < 0) {
                throw new IllegalArgumentException("inventory counts cannot be negative");
            }
            if (count > 0) result.merge(canonical(item), count, Math::addExact);
        });
        return Collections.unmodifiableMap(result);
    }

    private static String canonical(String value) {
        String result = Objects.requireNonNullElse(value, "")
                .trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        while (result.startsWith("minecraft:")) {
            result = result.substring("minecraft:".length());
        }
        if (result.isBlank() || result.indexOf(',') >= 0 || result.indexOf('=') >= 0) {
            throw new IllegalArgumentException("invalid canonical item ID");
        }
        return result;
    }

    private static final class GroundItemAge {
        private static final long MAXIMUM_ITEM_AGE_MILLIS = 300_000L;

        private GroundItemAge() {
        }
    }
}
