package dev.entity.client.autonomy.policy;

import java.util.Objects;

/**
 * Immutable identity and accounting for one logical mining objective.
 *
 * <p>A session may contain several intentional actuator segments, but a
 * {@link Segment#MINING} segment always owns one fixed Baritone goal identity
 * and block count. Inventory observations never rewrite those fields. This is
 * important because a mined drop, a temporarily placed scaffold, or an
 * unrelated inventory mutation must not silently turn one running MineProcess
 * into a different operation.</p>
 */
public record MiningSession(
        String sessionId,
        int missionBaselineItemCount,
        int absoluteTargetItemCount,
        int minimumDropsPerBlock,
        int estimatedBlocksPerDrop,
        boolean probabilistic,
        Segment segment,
        int segmentGeneration,
        int segmentBaselineItemCount,
        int goalBlockCount) {

    public enum Segment {
        MINING,
        PICKUP,
        COMPLETE
    }

    public MiningSession {
        sessionId = requireText(sessionId, "sessionId");
        if (missionBaselineItemCount < 0) {
            throw new IllegalArgumentException("mission baseline cannot be negative");
        }
        if (absoluteTargetItemCount < missionBaselineItemCount) {
            throw new IllegalArgumentException("absolute target cannot precede the mission baseline");
        }
        if (minimumDropsPerBlock <= 0) {
            throw new IllegalArgumentException("minimum drops per block must be positive");
        }
        if (estimatedBlocksPerDrop <= 0) {
            throw new IllegalArgumentException("estimated blocks per drop must be positive");
        }
        if (!probabilistic && estimatedBlocksPerDrop != 1) {
            throw new IllegalArgumentException(
                    "deterministic mining cannot use a probabilistic block estimate");
        }
        segment = Objects.requireNonNull(segment, "segment");
        if (segmentGeneration < 0) {
            throw new IllegalArgumentException("segment generation cannot be negative");
        }
        if (segmentBaselineItemCount < 0) {
            throw new IllegalArgumentException("segment baseline cannot be negative");
        }
        if (segment == Segment.MINING && goalBlockCount <= 0) {
            throw new IllegalArgumentException("a mining segment requires a positive fixed goal count");
        }
        if (segment != Segment.MINING && goalBlockCount != 0) {
            throw new IllegalArgumentException("only a mining segment may own a block goal");
        }
    }

    /** The exact net item gain promised by this logical mining objective. */
    public int requestedItemGain() {
        return absoluteTargetItemCount - missionBaselineItemCount;
    }

    /**
     * Stable operation identity for the current mining segment.
     *
     * <p>The generation changes only after an explicit segment transition;
     * live inventory observations cannot change this value.</p>
     */
    public String goalIdentity() {
        if (segment != Segment.MINING) {
            throw new IllegalStateException("the current segment does not own a mining goal");
        }
        return sessionId + "/mining/" + segmentGeneration;
    }

    public boolean hasMiningGoal() {
        return segment == Segment.MINING;
    }

    /**
     * Immutable item quota owned by the current fixed mining segment.
     *
     * <p>This deliberately differs from the logical objective's live remaining
     * count.  A collected drop must advance accounting without mutating the
     * {@code BaritonePort.Goal} arguments and therefore restarting the same
     * MineProcess once per block.</p>
     */
    public int goalItemCount() {
        if (segment != Segment.MINING) {
            throw new IllegalStateException("the current segment does not own a mining goal");
        }
        int count = absoluteTargetItemCount - segmentBaselineItemCount;
        if (count <= 0) {
            throw new IllegalStateException("a mining segment requires a positive item quota");
        }
        return count;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return result;
    }
}
