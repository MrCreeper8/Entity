package dev.entity.client.runtime;

import java.util.Objects;

/** Admission only. Home/Baritone retain every physical action and custody handoff. */
public final class IdleWorkCoordinator {
    public enum Job { NONE, DAY_STOCK, NIGHT_STOCK, SLEEP, FARM }
    public enum Action { NONE, DAY_STOCK, NIGHT_STOCK, SLEEP, FARM, CANCEL, YIELD }
    public record Settings(boolean enabled, long delayMillis, boolean allowOffline) {
        public Settings {
            if (delayMillis < 60_000L) throw new IllegalArgumentException("Idle delay must be at least one minute");
        }
    }
    public record Facts(boolean worldReady, boolean ownerOnline, boolean manualPending,
                        boolean externalBusy, boolean neutral, boolean night, long nightId,
                        boolean canSleep, boolean homeReady, String workKey, boolean needsStock,
                        boolean farmEligible, String farmWorkKey, String farmDetail,
                        boolean temporarySafety) {
        public Facts {
            workKey = Objects.requireNonNullElse(workKey, "");
            farmWorkKey = Objects.requireNonNullElse(farmWorkKey, "");
            farmDetail = Objects.requireNonNullElse(farmDetail, "");
        }

        /** Busy without an explicit temporary safety owner remains cancellation. */
        public Facts(boolean worldReady, boolean ownerOnline, boolean manualPending,
                     boolean externalBusy, boolean neutral, boolean night, long nightId,
                     boolean canSleep, boolean homeReady, String workKey, boolean needsStock,
                     boolean farmEligible, String farmWorkKey, String farmDetail) {
            this(worldReady, ownerOnline, manualPending, externalBusy, neutral, night,
                    nightId, canSleep, homeReady, workKey, needsStock,
                    farmEligible, farmWorkKey, farmDetail, false);
        }

        /** Source-compatible constructor for callers that do not offer farm work. */
        public Facts(boolean worldReady, boolean ownerOnline, boolean manualPending,
                     boolean externalBusy, boolean neutral, boolean night, long nightId,
                     boolean canSleep, boolean homeReady, String workKey,
                     boolean needsStock) {
            this(worldReady, ownerOnline, manualPending, externalBusy, neutral,
                    night, nightId, canSleep, homeReady, workKey, needsStock,
                    false, "", "");
        }
    }
    private static final long OBSERVATION_GAP_MILLIS = 15_000L;
    private static final long HANDOFF_SETTLE_MILLIS = 2_000L;
    private Job active = Job.NONE;
    private long quietSince = -1L;
    private long lastObserved = -1L;
    private long attemptedSleepNight = Long.MIN_VALUE;
    private long reconsiderAt;
    private String blockedWorkKey = "";
    private String activeWorkKey = "";
    private String detail = "OFF";

    public Job active() { return active; }
    public boolean ownsWork() { return active != Job.NONE; }
    public String detail() { return detail; }
    public long remainingMillis(Settings settings, long now) {
        return quietSince < 0 ? settings.delayMillis()
                : Math.max(0L, settings.delayMillis() - Math.max(0L, now - quietSince));
    }

    /** Restores custody for one durable automatic mission before its first tick. */
    public void restoreFarm(String workKey) {
        String key = Objects.requireNonNullElse(workKey, "").trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("restored farm work key cannot be blank");
        }
        active = Job.FARM;
        activeWorkKey = "farm:" + key;
        quietSince = -1L;
        lastObserved = -1L;
        reconsiderAt = 0L;
        detail = "Restored an unfinished automatic managed-farm pass";
    }

    /** Explicit rearming and command/reconnect boundaries start a fresh quiet period. */
    public void reset(String reason) {
        active = Job.NONE;
        quietSince = -1L;
        lastObserved = -1L;
        reconsiderAt = 0L;
        blockedWorkKey = "";
        activeWorkKey = "";
        attemptedSleepNight = Long.MIN_VALUE;
        detail = reason;
    }

    public Action evaluate(Settings settings, Facts facts, long now) {
        boolean clockDiscontinuity = lastObserved >= 0
                && (now < lastObserved || now - lastObserved > OBSERVATION_GAP_MILLIS);
        lastObserved = now;
        String hold = !settings.enabled() ? "OFF"
                : !facts.worldReady() ? "Waiting for authenticated world and owner context"
                : !settings.allowOffline() && !facts.ownerOnline() ? "Owner offline; automatic work disabled offline"
                : facts.manualPending() ? "Manual work owns Entity"
                : clockDiscontinuity ? "Fresh quiet period after observation gap" : "";
        if (!hold.isEmpty()) {
            boolean cancel = ownsWork();
            active = Job.NONE;
            quietSince = -1L;
            detail = hold;
            return cancel ? Action.CANCEL : Action.NONE;
        }
        // Quiet time measures player inactivity. Eating, protection and their
        // physical cleanup revoke actuator ownership, not standing Idle authority.
        // The hard authority/context fences above still discard an old quiet period.
        if (quietSince < 0L) quietSince = now;
        if (facts.externalBusy()) {
            detail = "Survival/recovery owns Entity; automatic work resumes when settled";
            // Stock/Sleep's Home owner already pauses/drains in executeDecision.
            // Keep this admission marker so a temporary safety borrow cannot
            // retire an exact acquisition or clear the same owned-bed request.
            if (facts.temporarySafety() && (active == Job.DAY_STOCK || active == Job.NIGHT_STOCK
                    || active == Job.SLEEP)) {
                return Action.NONE;
            }
            boolean cancel = ownsWork();
            active = Job.NONE;
            return cancel ? Action.YIELD : Action.NONE;
        }
        // Night changes only automatic work; it never prohibits a player's mission.
        if ((active == Job.DAY_STOCK || active == Job.FARM) && facts.night()
                || active == Job.NIGHT_STOCK && !facts.night()) {
            active = Job.NONE;
            detail = "Changing automatic work for time of day";
            reconsiderAt = 0L;
            return Action.YIELD;
        }
        if (ownsWork()) {
            if (active == Job.DAY_STOCK) detail = "Automatic resource work";
            else if (active == Job.NIGHT_STOCK) detail = "Checking feasible underground work";
            else if (active == Job.SLEEP) detail = "Automatic Home sleep";
            return Action.NONE;
        }
        if (!facts.neutral()) {
            detail = "Waiting for existing controls and inventory custody to settle";
            return Action.NONE;
        }
        if (remainingMillis(settings, now) > 0L) {
            detail = "Quiet delay";
            return Action.NONE;
        }
        if (facts.night() && facts.canSleep() && attemptedSleepNight != facts.nightId()) {
            attemptedSleepNight = facts.nightId();
            active = Job.SLEEP;
            activeWorkKey = facts.workKey();
            detail = "Automatic Home sleep";
            return Action.SLEEP;
        }
        if (!facts.homeReady()) {
            detail = "Resting: select and establish Home storage before automatic resource work";
            return Action.NONE;
        }
        if (facts.needsStock()) {
            String key = (facts.night() ? "night:" : "day:") + facts.workKey();
            if (key.equals(blockedWorkKey)) return Action.NONE;
            if (now < reconsiderAt) return Action.NONE;
            activeWorkKey = key;
            active = facts.night() ? Job.NIGHT_STOCK : Job.DAY_STOCK;
            detail = facts.night() ? "Checking feasible underground work" : "Automatic resource work";
            return facts.night() ? Action.NIGHT_STOCK : Action.DAY_STOCK;
        }
        if (!facts.night() && facts.farmEligible()) {
            String key = "farm:" + facts.farmWorkKey();
            if (key.equals(blockedWorkKey)) return Action.NONE;
            if (now < reconsiderAt) return Action.NONE;
            activeWorkKey = key;
            active = Job.FARM;
            detail = "Automatic managed-farm pass";
            return Action.FARM;
        }
        detail = facts.night() && facts.farmEligible()
                ? "Resting: managed farms wait for daylight"
                : facts.farmDetail().isBlank()
                ? "Resting: supplies and stored targets satisfied"
                : facts.farmDetail();
        return Action.NONE;
    }

    /** Called only after the executor has released its request and acknowledged custody. */
    public void finished(boolean success, String result, long now) {
        finished(success, result, now, null);
    }

    public void finished(boolean success, String result, long now, String observedWorkKey) {
        if (active == Job.DAY_STOCK || active == Job.NIGHT_STOCK) {
            if (observedWorkKey != null) activeWorkKey = (active == Job.NIGHT_STOCK ? "night:" : "day:") + observedWorkKey;
            if (!success) blockedWorkKey = activeWorkKey;
            reconsiderAt = now + HANDOFF_SETTLE_MILLIS;
        } else if (active == Job.FARM) {
            if (observedWorkKey != null) activeWorkKey = "farm:" + observedWorkKey;
            // A successful pass must also rest until a different maturity
            // observation appears; failure uses the same bounded no-loop fence.
            blockedWorkKey = activeWorkKey;
            reconsiderAt = now + HANDOFF_SETTLE_MILLIS;
        }
        active = Job.NONE;
        detail = Objects.requireNonNullElse(result, "Resting");
    }
}
