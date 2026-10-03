package dev.entity.client.autonomy.policy;

import java.util.Map;
import java.util.Optional;

/** Carries the existing world-scoped mining leaf origin through native-process retries. */
public final class MiningEntranceCheckpoint {
    private static final String GOAL_PREFIX = "miningEntrance";

    private MiningEntranceCheckpoint() { }

    public record Origin(String dimension, int x, int y, int z) {
        public boolean reached(String currentDimension, int feetX, int feetY, int feetZ,
                               boolean grounded) {
            return grounded && dimension.equals(currentDimension)
                    && x == feetX && y == feetY && z == feetZ;
        }
    }

    /** Inventory completion cannot discharge an unfulfilled durable return after cancellation. */
    public static boolean returnRequired(
            boolean itemsComplete, Map<String, String> goalArguments, String currentDimension,
            int feetX, int feetY, int feetZ, boolean grounded) {
        return itemsComplete && read(goalArguments, GOAL_PREFIX)
                .filter(origin -> !origin.reached(currentDimension, feetX, feetY, feetZ, grounded))
                .isPresent();
    }

    /** Copies a complete durable checkpoint only; never invents zero coordinates. */
    public static Map<String, String> goalArguments(
            Map<String, String> checkpoint, String prefix) {
        return read(checkpoint, prefix)
                .map(origin -> Map.of(
                        GOAL_PREFIX + "Dimension", origin.dimension(),
                        GOAL_PREFIX + "X", Integer.toString(origin.x()),
                        GOAL_PREFIX + "Y", Integer.toString(origin.y()),
                        GOAL_PREFIX + "Z", Integer.toString(origin.z())))
                .orElseGet(Map::of);
    }

    /** The enclosing plan is world-scoped; a dimension change must not reuse old coordinates. */
    public static Optional<Origin> fromGoal(
            Map<String, String> arguments, String currentDimension) {
        return read(arguments, GOAL_PREFIX)
                .filter(origin -> origin.dimension().equals(currentDimension));
    }

    private static Optional<Origin> read(Map<String, String> values, String prefix) {
        String dimension = values.getOrDefault(prefix + "Dimension", "").trim();
        if (dimension.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new Origin(dimension,
                    Integer.parseInt(values.get(prefix + "X")),
                    Integer.parseInt(values.get(prefix + "Y")),
                    Integer.parseInt(values.get(prefix + "Z"))));
        } catch (NumberFormatException invalidCheckpoint) {
            return Optional.empty();
        }
    }
}
