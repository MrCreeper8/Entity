package dev.entity.client.runtime;

import dev.entity.core.protection.ProtectionPolicy;

import java.util.Locale;
import java.util.Set;

/** Pure classification of whether protection should fight or merely evade. */
public final class ProtectionActivityClassifier {
    private static final Set<String> STATIONARY_LEAVES = Set.of(
            "mine", "craft", "smelt", "equip", "reclaim");
    private static final Set<String> TRAVEL_LEAVES = Set.of(
            "deliver", "goto", "follow");
    private static final double MEANINGFUL_TRAVEL_SPEED = 0.04;

    public static ProtectionPolicy.ActivityContext classify(
            boolean missionActive,
            boolean breakingBlock,
            boolean handledScreen,
            String planSummary,
            boolean navigationPathing,
            double horizontalSpeed) {
        if (!missionActive) return ProtectionPolicy.ActivityContext.IDLE;
        if (breakingBlock || handledScreen) {
            return ProtectionPolicy.ActivityContext.STATIONARY_WORK;
        }
        String leaf = currentLeaf(planSummary);
        boolean stationaryLeaf = STATIONARY_LEAVES.contains(leaf);
        boolean meaningfullyTravelling = navigationPathing
                && Double.isFinite(horizontalSpeed)
                && horizontalSpeed >= MEANINGFUL_TRAVEL_SPEED;
        if (stationaryLeaf && !meaningfullyTravelling) {
            return ProtectionPolicy.ActivityContext.STATIONARY_WORK;
        }
        if (TRAVEL_LEAVES.contains(leaf)) {
            return ProtectionPolicy.ActivityContext.TRAVEL;
        }
        // New or temporarily unlabelled leaves must not default to "travel"
        // while the body is visibly stationary and no navigator owns a path.
        // That old default made protection evade a zombie instead of
        // intercepting it during planner/workstation gaps.
        if (!navigationPathing
                && (!Double.isFinite(horizontalSpeed)
                || horizontalSpeed < MEANINGFUL_TRAVEL_SPEED)) {
            return ProtectionPolicy.ActivityContext.STATIONARY_WORK;
        }
        return ProtectionPolicy.ActivityContext.TRAVEL;
    }

    static String currentLeaf(String planSummary) {
        if (planSummary == null || planSummary.isBlank()) return "";
        String current = planSummary;
        int arrow = current.lastIndexOf(" -> ");
        if (arrow >= 0) current = current.substring(arrow + 4);
        else {
            int metadataEnd = current.indexOf("] ");
            if (metadataEnd >= 0) current = current.substring(metadataEnd + 2);
        }
        current = current.stripLeading().toLowerCase(Locale.ROOT);
        int end = 0;
        while (end < current.length() && Character.isLetter(current.charAt(end))) end++;
        return end == 0 ? "" : current.substring(0, end);
    }

    /**
     * Remembers what Entity was doing when one contiguous protection episode
     * began. Movement created by interception or retreat must not be fed back
     * into the next protection decision as if it were mission travel.
     *
     * <p>The latch deliberately stores activity rather than a combat action.
     * The core protection policy therefore remains free to escalate an
     * intercepted threat to low-health retreat, and to leave that safety latch
     * after health recovery. A temporary survival preemption may retain this
     * object; the runtime clears it only when normal mission/idle control
     * actually resumes.</p>
     */
    static final class ProtectionModeLatch {
        private ProtectionPolicy.ActivityContext activity;
        private String threatKey = "";
        private String missionKey = "";
        private long episodeSequence;

        /**
         * Reclassifies at an explicit mission identity boundary. A protection
         * or survival reflex may move the body without changing this identity,
         * but owner stop/replacement changes (or removes) it before the next
         * policy tick and must not retain the old TRAVEL/STATIONARY baseline.
         */
        void reclassifyForMission(String nextMissionKey) {
            String normalized = nextMissionKey == null ? "" : nextMissionKey.trim();
            if (normalized.equals(missionKey)) return;
            activity = null;
            threatKey = "";
            missionKey = normalized;
        }

        ProtectionPolicy.ActivityContext resolve(
                ProtectionPolicy.ActivityContext observedActivity) {
            if (observedActivity == null) {
                throw new IllegalArgumentException("observedActivity cannot be null");
            }
            return activity == null ? observedActivity : activity;
        }

        /**
         * Starts the episode once. The boolean is suitable for guarding the
         * one-time mission-control neutralization at protection entry.
         */
        boolean begin(ProtectionPolicy.ActivityContext initialActivity) {
            if (initialActivity == null) {
                throw new IllegalArgumentException("initialActivity cannot be null");
            }
            if (activity != null) return false;
            activity = initialActivity;
            episodeSequence++;
            return true;
        }

        void observeThreat(String nextThreatKey) {
            threatKey = nextThreatKey == null ? "" : nextThreatKey.trim();
        }

        boolean active() {
            return activity != null;
        }

        String threatKey() {
            return threatKey;
        }

        String episodeKey() {
            return activity == null ? "" : "protection-episode-" + episodeSequence;
        }

        void clear() {
            activity = null;
            threatKey = "";
            missionKey = "";
        }
    }

    private ProtectionActivityClassifier() { }
}
