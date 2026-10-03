package dev.entity.client.baritone;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Operation-level authority for Baritone world mutation.
 *
 * <p>Every active Baritone route may solve terrain by breaking or placing
 * blocks unless that exact technique explicitly opts out. This is capability,
 * not property authority: the loaded protected-area actuator remains the final
 * coordinate fence for every physical mutation.</p>
 */
public final class RouteMutationPolicy {
    private RouteMutationPolicy() {
    }

    public static Decision decide(
            String kind,
            Map<String, String> arguments,
            boolean usableClutchBucket) {
        String normalizedKind = Objects.requireNonNullElse(kind, "")
                .trim().toLowerCase(Locale.ROOT);
        Map<String, String> args = arguments == null ? Map.of() : arguments;
        if (normalizedKind.isEmpty()) {
            return Decision.NON_DESTRUCTIVE;
        }

        boolean allowBreak = parseBoolean(args.get("allowBreak"), true);
        boolean allowPlace = parseBoolean(args.get("allowPlace"), true);
        boolean allowParkourPlace = allowPlace
                && parseBoolean(args.get("allowParkourPlace"), true);
        boolean allowWaterBucketFall = usableClutchBucket
                && parseBoolean(args.get("allowWaterBucketFall"), true);
        return new Decision(
                allowBreak,
                allowPlace,
                allowParkourPlace,
                allowWaterBucketFall);
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    public record Decision(
            boolean allowBreak,
            boolean allowPlace,
            boolean allowParkourPlace,
            boolean allowWaterBucketFall) {
        private static final Decision NON_DESTRUCTIVE =
                new Decision(false, false, false, false);

        /**
         * Narrows one otherwise capable route without changing the mission's
         * ordinary wilderness authority. The caller may restore the original
         * decision for a later segment which no longer crosses property.
         */
        public Decision withoutWorldMutation() {
            return NON_DESTRUCTIVE;
        }

        /** Native per-cell costs fence property, not the whole approach from wilderness.
         * Bucket falls have no equivalent fluid-spread cost callback. */
        public Decision withPropertyCosts(boolean policyAvailable, boolean crossesProperty) {
            if (!policyAvailable) return NON_DESTRUCTIVE;
            return new Decision(allowBreak, allowPlace, allowParkourPlace,
                    allowWaterBucketFall && !crossesProperty);
        }
    }
}
