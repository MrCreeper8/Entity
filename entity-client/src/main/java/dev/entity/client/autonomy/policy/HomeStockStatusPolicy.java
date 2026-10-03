package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Pure truthful rendering for `/e stock status`. */
public final class HomeStockStatusPolicy {
    public enum ChestTruth {
        LIVE,
        STALE,
        UNOBSERVED,
        IN_PROGRESS
    }

    private HomeStockStatusPolicy() {
    }

    public static String render(
            boolean enabled,
            HomeEconomySession.Phase phase,
            Map<String, Integer> playerCounts,
            Map<String, Integer> chestCounts,
            ChestTruth chestTruth,
            long chestObservedAtMillis,
            long nowMillis,
            String blocker) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(playerCounts, "playerCounts");
        Objects.requireNonNull(chestCounts, "chestCounts");
        Objects.requireNonNull(chestTruth, "chestTruth");
        if (chestObservedAtMillis < -1L || nowMillis < 0L) {
            throw new IllegalArgumentException("stock status times are invalid");
        }
        if (chestTruth == ChestTruth.LIVE && chestObservedAtMillis < 0L) {
            throw new IllegalArgumentException("live chest truth requires an observation time");
        }
        if (chestTruth == ChestTruth.STALE && chestObservedAtMillis < 0L) {
            throw new IllegalArgumentException("stale chest truth requires an observation time");
        }
        Map<String, Integer> accountedChest = switch (chestTruth) {
            case LIVE, STALE -> chestCounts;
            case UNOBSERVED, IN_PROGRESS -> Map.of();
        };
        HomeStockPolicy.Analysis analysis = HomeStockPolicy.analyze(
                playerCounts, accountedChest, Map.of());
        String chestLabel = switch (chestTruth) {
            case LIVE -> "live";
            case STALE -> "stale(last-observed-age-ms="
                    + Math.max(0L, nowMillis - chestObservedAtMillis) + ')';
            case UNOBSERVED -> "unobserved";
            case IN_PROGRESS -> "in-progress(cursor/transfer unresolved)";
        };
        List<String> categories = new ArrayList<>();
        for (HomeStockPolicy.TargetStatus status : analysis.targets()) {
            String stored = switch (chestTruth) {
                case LIVE -> Integer.toString(status.chestCount());
                case STALE -> status.chestCount() + "(stale)";
                case UNOBSERVED -> "unobserved";
                case IN_PROGRESS -> "in-progress";
            };
            String deficit = chestTruth == ChestTruth.LIVE
                    ? Integer.toString(Math.max(0,
                    status.target().desiredCount()
                            - status.playerCount() - status.chestCount()))
                    : "unknown";
            categories.add(label(status.target().id())
                    + " carried=" + status.playerCount() + '/'
                    + status.target().desiredCount()
                    + " stored=" + stored
                    + " deficit=" + deficit);
        }
        String normalizedBlocker = Objects.requireNonNullElse(blocker, "").trim();
        return "stock=" + (enabled ? "on" : "off")
                + "; phase=" + phase.name().toLowerCase(Locale.ROOT)
                + "; chest=" + chestLabel
                + "; " + String.join("; ", categories)
                + (normalizedBlocker.isEmpty() ? "" : "; blocker=" + normalizedBlocker);
    }

    private static String label(String targetId) {
        return switch (targetId) {
            case "building_blocks" -> "blocks";
            case "filled_bucket" -> "water_bucket";
            default -> targetId;
        };
    }
}
