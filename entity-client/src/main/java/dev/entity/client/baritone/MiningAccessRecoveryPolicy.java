package dev.entity.client.baritone;

/** Feedback and bounded retreat for native extraction, not a blanket tool prerequisite. */
public final class MiningAccessRecoveryPolicy {
    private MiningAccessRecoveryPolicy() { }

    public static boolean needsAccessTool(boolean observedAccessFailure,
                                          boolean nativeMining, boolean usableStoneTool) {
        return observedAccessFailure && nativeMining && !usableStoneTool;
    }

    /** One original-entrance retreat per actual target removal; walking cannot renew it. */
    public static final class RetreatBudget {
        private long attemptedAtMinedBlocks = -1;

        public boolean exhausted(long verifiedMinedBlocks) {
            return attemptedAtMinedBlocks >= 0 && verifiedMinedBlocks <= attemptedAtMinedBlocks;
        }

        public boolean consume(long verifiedMinedBlocks, boolean usableEntrance, boolean atEntrance) {
            if (!usableEntrance || atEntrance || verifiedMinedBlocks <= attemptedAtMinedBlocks) return false;
            attemptedAtMinedBlocks = verifiedMinedBlocks;
            return true;
        }
    }
}
