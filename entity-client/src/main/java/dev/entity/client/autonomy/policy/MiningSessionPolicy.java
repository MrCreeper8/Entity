package dev.entity.client.autonomy.policy;

import java.util.Objects;

/** Pure transition and progress policy for {@link MiningSession}. */
public final class MiningSessionPolicy {
    /**
     * A probabilistic source must be sampled in finite chunks.  This keeps a poor-luck apple
     * harvest from becoming one enormous immutable Baritone operation while still allowing the
     * logical objective to continue until the requested items are actually observed.
     */
    public static final int MAX_PROBABILISTIC_SEGMENT_BLOCKS = 128;

    public enum Action {
        CONTINUE_MINING,
        CONTINUE_PICKUP,
        COMPLETE
    }

    /** One observation; the returned session is always safe to retain. */
    public record Decision(
            Action action,
            MiningSession session,
            int observedItemCount,
            int acquiredItemCount,
            int remainingItemCount) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            session = Objects.requireNonNull(session, "session");
            if (observedItemCount < 0 || acquiredItemCount < 0 || remainingItemCount < 0) {
                throw new IllegalArgumentException("mining progress counts cannot be negative");
            }
        }

        public boolean complete() {
            return action == Action.COMPLETE;
        }
    }

    private MiningSessionPolicy() {
    }

    /** Starts a fresh objective whose mission baseline is the current inventory. */
    public static MiningSession start(
            String sessionId,
            int currentItemCount,
            int requestedItemGain,
            int minimumDropsPerBlock) {
        return start(
                sessionId,
                currentItemCount,
                requestedItemGain,
                minimumDropsPerBlock,
                1,
                false);
    }

    /** Starts an objective backed by a probabilistic block source such as apples from leaves. */
    public static MiningSession start(
            String sessionId,
            int currentItemCount,
            int requestedItemGain,
            int minimumDropsPerBlock,
            int estimatedBlocksPerDrop,
            boolean probabilistic) {
        return startFromMissionBaseline(
                sessionId,
                currentItemCount,
                currentItemCount,
                requestedItemGain,
                minimumDropsPerBlock,
                estimatedBlocksPerDrop,
                probabilistic);
    }

    /**
     * Adopts a pre-session (legacy) mining frame without changing its accounting.
     *
     * <p>Older plans already persist a mission baseline and may have gained
     * items before this policy first sees them. The first fixed segment is
     * therefore based on the old remaining-block calculation. From that point
     * onward its goal remains stable until an explicit pickup/rebase.</p>
     */
    public static MiningSession startFromMissionBaseline(
            String sessionId,
            int missionBaselineItemCount,
            int currentItemCount,
            int requestedItemGain,
            int minimumDropsPerBlock) {
        return startFromMissionBaseline(
                sessionId,
                missionBaselineItemCount,
                currentItemCount,
                requestedItemGain,
                minimumDropsPerBlock,
                1,
                false);
    }

    /**
     * Adopts a frame while retaining both its exact item objective and its source-yield model.
     * The yield estimate sizes work only; it is never accepted as proof that an item exists.
     */
    public static MiningSession startFromMissionBaseline(
            String sessionId,
            int missionBaselineItemCount,
            int currentItemCount,
            int requestedItemGain,
            int minimumDropsPerBlock,
            int estimatedBlocksPerDrop,
            boolean probabilistic) {
        validateCounts(
                missionBaselineItemCount,
                currentItemCount,
                requestedItemGain,
                minimumDropsPerBlock,
                estimatedBlocksPerDrop,
                probabilistic);
        int absoluteTarget = absoluteTarget(missionBaselineItemCount, requestedItemGain);
        int remaining = Math.max(0, absoluteTarget - currentItemCount);
        if (remaining == 0) {
            return new MiningSession(
                    sessionId,
                    missionBaselineItemCount,
                    absoluteTarget,
                    minimumDropsPerBlock,
                    estimatedBlocksPerDrop,
                    probabilistic,
                    MiningSession.Segment.COMPLETE,
                    0,
                    currentItemCount,
                    0);
        }
        return new MiningSession(
                sessionId,
                missionBaselineItemCount,
                absoluteTarget,
                minimumDropsPerBlock,
                estimatedBlocksPerDrop,
                probabilistic,
                MiningSession.Segment.MINING,
                0,
                currentItemCount,
                segmentBlockCount(
                        remaining,
                        minimumDropsPerBlock,
                        estimatedBlocksPerDrop,
                        probabilistic));
    }

    /**
     * Observes progress without rebasing or modifying a live segment goal.
     * Completion is the only automatic state transition.
     */
    public static Decision observe(MiningSession session, int currentItemCount) {
        Objects.requireNonNull(session, "session");
        if (currentItemCount < 0) {
            throw new IllegalArgumentException("current item count cannot be negative");
        }
        int acquired = Math.max(0, currentItemCount - session.missionBaselineItemCount());
        int remaining = Math.max(0, session.absoluteTargetItemCount() - currentItemCount);
        if (remaining == 0 || session.segment() == MiningSession.Segment.COMPLETE) {
            MiningSession completed = session.segment() == MiningSession.Segment.COMPLETE
                    ? session
                    : new MiningSession(
                            session.sessionId(),
                            session.missionBaselineItemCount(),
                            session.absoluteTargetItemCount(),
                            session.minimumDropsPerBlock(),
                            session.estimatedBlocksPerDrop(),
                            session.probabilistic(),
                            MiningSession.Segment.COMPLETE,
                            session.segmentGeneration() + 1,
                            currentItemCount,
                            0);
            return new Decision(Action.COMPLETE, completed, currentItemCount, acquired, 0);
        }
        Action action = session.segment() == MiningSession.Segment.MINING
                ? Action.CONTINUE_MINING : Action.CONTINUE_PICKUP;
        return new Decision(action, session, currentItemCount, acquired, remaining);
    }

    /**
     * Inventory proves the requested quantity, but it may not revoke an exact
     * resource actuator that still owns a larger atomic commitment (for
     * example, the remaining logs of one initially classified natural tree).
     */
    public static boolean mayCommitItemCompletion(
            Decision observation,
            boolean unfinishedScopedResourceCustody) {
        Objects.requireNonNull(observation, "observation");
        return observation.complete() && !unfinishedScopedResourceCustody;
    }

    /**
     * Ends deterministic mining as soon as one unit of real work is awaiting reconciliation.
     * A loaded expected drop is stronger evidence than inventory because Minecraft can expose
     * the item entity before pickup. Fencing at either boundary prevents MineProcess from
     * breaking the next one-drop block while the previous drop is still in flight.
     */
    public static boolean shouldFenceDeterministicDrop(
            MiningSession session,
            int currentItemCount,
            boolean loadedExpectedDrop) {
        return shouldFenceDeterministicDrop(
                session, currentItemCount, loadedExpectedDrop, false);
    }

    /**
     * Keeps the generic pickup handoff outside a typed actuator's exact custody.
     * That actuator must finish crop replant acknowledgement and settle its own
     * drop before the planner is allowed to start another controller.
     */
    public static boolean shouldFenceDeterministicDrop(
            MiningSession session,
            int currentItemCount,
            boolean loadedExpectedDrop,
            boolean scopedResourceActuatorOwnsCustody) {
        Objects.requireNonNull(session, "session");
        if (currentItemCount < 0) {
            throw new IllegalArgumentException("current item count cannot be negative");
        }
        return !scopedResourceActuatorOwnsCustody
                && session.segment() == MiningSession.Segment.MINING
                && !session.probabilistic()
                && currentItemCount < session.absoluteTargetItemCount()
                && (loadedExpectedDrop
                || currentItemCount > session.segmentBaselineItemCount());
    }

    /** Explicitly hands actuator ownership from mining to ground-item pickup. */
    public static Decision enterPickup(MiningSession session, int currentItemCount) {
        Decision observed = observe(session, currentItemCount);
        if (observed.complete() || session.segment() == MiningSession.Segment.PICKUP) {
            return observed;
        }
        MiningSession pickup = new MiningSession(
                session.sessionId(),
                session.missionBaselineItemCount(),
                session.absoluteTargetItemCount(),
                session.minimumDropsPerBlock(),
                session.estimatedBlocksPerDrop(),
                session.probabilistic(),
                MiningSession.Segment.PICKUP,
                session.segmentGeneration() + 1,
                currentItemCount,
                0);
        return progress(Action.CONTINUE_PICKUP, pickup, currentItemCount);
    }

    /**
     * Explicitly starts a new fixed mining segment after pickup has released
     * actuator ownership. Calling this while already mining is idempotent and
     * cannot accidentally churn the running goal.
     */
    public static Decision resumeMining(MiningSession session, int currentItemCount) {
        Decision observed = observe(session, currentItemCount);
        if (observed.complete() || session.segment() == MiningSession.Segment.MINING) {
            return observed;
        }
        int remaining = observed.remainingItemCount();
        MiningSession mining = new MiningSession(
                session.sessionId(),
                session.missionBaselineItemCount(),
                session.absoluteTargetItemCount(),
                session.minimumDropsPerBlock(),
                session.estimatedBlocksPerDrop(),
                session.probabilistic(),
                MiningSession.Segment.MINING,
                session.segmentGeneration() + 1,
                currentItemCount,
                segmentBlockCount(
                        remaining,
                        session.minimumDropsPerBlock(),
                        session.estimatedBlocksPerDrop(),
                        session.probabilistic()));
        return progress(Action.CONTINUE_MINING, mining, currentItemCount);
    }

    /**
     * Shrinks a not-yet-owned segment to one exact tool stack's safe work boundary. The logical
     * item objective and operation generation stay unchanged; callers must invoke this only
     * before handing the goal identity to Baritone.
     */
    public static MiningSession boundUnownedSegment(
            MiningSession session,
            int maximumBlocks) {
        Objects.requireNonNull(session, "session");
        if (maximumBlocks <= 0) {
            throw new IllegalArgumentException("maximum segment blocks must be positive");
        }
        if (session.segment() != MiningSession.Segment.MINING
                || session.goalBlockCount() <= maximumBlocks) return session;
        return new MiningSession(
                session.sessionId(),
                session.missionBaselineItemCount(),
                session.absoluteTargetItemCount(),
                session.minimumDropsPerBlock(),
                session.estimatedBlocksPerDrop(),
                session.probabilistic(),
                session.segment(),
                session.segmentGeneration(),
                session.segmentBaselineItemCount(),
                maximumBlocks);
    }

    /** The pre-session executor's initial remaining-block formula. */
    public static int legacyRemainingBlockCount(
            int missionBaselineItemCount,
            int currentItemCount,
            int requestedItemGain,
            int minimumDropsPerBlock) {
        validateCounts(
                missionBaselineItemCount,
                currentItemCount,
                requestedItemGain,
                minimumDropsPerBlock,
                1,
                false);
        int absoluteTarget = absoluteTarget(missionBaselineItemCount, requestedItemGain);
        return ceilDiv(Math.max(0, absoluteTarget - currentItemCount), minimumDropsPerBlock);
    }

    private static int absoluteTarget(int baseline, int requestedGain) {
        try {
            return Math.addExact(baseline, requestedGain);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("absolute mining target exceeds integer capacity", overflow);
        }
    }

    private static Decision progress(
            Action action,
            MiningSession session,
            int currentItemCount) {
        return new Decision(
                action,
                session,
                currentItemCount,
                Math.max(0, currentItemCount - session.missionBaselineItemCount()),
                Math.max(0, session.absoluteTargetItemCount() - currentItemCount));
    }

    private static void validateCounts(
            int missionBaselineItemCount,
            int currentItemCount,
            int requestedItemGain,
            int minimumDropsPerBlock,
            int estimatedBlocksPerDrop,
            boolean probabilistic) {
        if (missionBaselineItemCount < 0 || currentItemCount < 0) {
            throw new IllegalArgumentException("inventory counts cannot be negative");
        }
        if (requestedItemGain <= 0) {
            throw new IllegalArgumentException("requested item gain must be positive");
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
    }

    private static int segmentBlockCount(
            int remainingItems,
            int minimumDropsPerBlock,
            int estimatedBlocksPerDrop,
            boolean probabilistic) {
        int dropUnits = ceilDiv(remainingItems, minimumDropsPerBlock);
        if (!probabilistic) return dropUnits;
        long estimatedBlocks = (long) dropUnits * estimatedBlocksPerDrop;
        return (int) Math.max(
                1L,
                Math.min(estimatedBlocks, MAX_PROBABILISTIC_SEGMENT_BLOCKS));
    }

    private static int ceilDiv(int numerator, int denominator) {
        if (numerator <= 0) return 0;
        return 1 + ((numerator - 1) / denominator);
    }
}
