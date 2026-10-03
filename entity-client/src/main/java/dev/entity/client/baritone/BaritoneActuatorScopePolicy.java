package dev.entity.client.baritone;

import java.util.Objects;

/** Deterministic ownership matrix for Baritone's passive hotbar actuators. */
public final class BaritoneActuatorScopePolicy {
    private BaritoneActuatorScopePolicy() {
    }

    public static Scope forOwner(Owner owner) {
        Objects.requireNonNull(owner, "owner");
        return owner == Owner.ROUTE
                ? new Scope(true, true)
                : new Scope(false, false);
    }

    /**
     * Keeps Baritone's passive hotbar actuators fenced while an exact combat-hand click is
     * awaiting acknowledgement. A moving target may briefly leave attack reach during that
     * fence; releasing the hand then lets autoTool undo the optimistic weapon SWAP before it
     * can be confirmed.
     */
    public static boolean shouldOwnCombatHand(
            boolean operationMatches,
            boolean targetAlive,
            boolean inAttackReach,
            boolean insideClosePursuitEnvelope,
            boolean combatHandPrepared,
            boolean exactWeaponTransactionPending) {
        return operationMatches
                && targetAlive
                && (inAttackReach
                || (combatHandPrepared
                && (insideClosePursuitEnvelope || exactWeaponTransactionPending)));
    }

    /**
     * A functional-route replay is mining work owned by the current operation even during the
     * short handoff between issuing its waypoint and Baritone beginning path calculation.
     */
    public static boolean hasLiveMiningWork(
            boolean functionalRouteReplay,
            boolean mineProcessActive,
            boolean pathing,
            boolean calculationInProgress,
            boolean breakingBlock) {
        return hasLiveMiningWork(functionalRouteReplay, false, mineProcessActive,
                pathing, calculationInProgress, breakingBlock);
    }

    /** A door transaction deliberately pauses native processes without ending their mining leaf. */
    public static boolean hasLiveMiningWork(
            boolean functionalRouteReplay,
            boolean doorPassagePending,
            boolean mineProcessActive,
            boolean pathing,
            boolean calculationInProgress,
            boolean breakingBlock) {
        return functionalRouteReplay
                || doorPassagePending
                || mineProcessActive
                || pathing
                || calculationInProgress
                || breakingBlock;
    }

    /** A paused actuator resumes its current phase, not the mission's original verb. */
    public static ResumePhase resumePhase(boolean miningReturning, boolean nativeMiningReturn) {
        if (nativeMiningReturn) return ResumePhase.RESUME_MINING_RETURN;
        if (miningReturning) return ResumePhase.BEGIN_MINING_RETURN;
        return ResumePhase.ORIGINAL_OPERATION;
    }

    public enum ResumePhase {
        ORIGINAL_OPERATION,
        BEGIN_MINING_RETURN,
        RESUME_MINING_RETURN
    }

    public enum Owner {
        IDLE,
        ROUTE,
        DIRECT_ACTION,
        COMBAT_HAND
    }

    public record Scope(boolean allowInventory, boolean autoTool) {
    }
}
