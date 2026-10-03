package dev.entity.client.autonomy.policy;

import java.util.Objects;

/** Pure safety and acknowledgement contract for one owned-Home sleep cycle. */
public final class HomeSleepPolicy {
    private static final double SERVER_BED_HORIZONTAL_RANGE = 3.0D;
    private static final double SERVER_BED_VERTICAL_RANGE = 2.0D;

    public enum Action {
        INACTIVE,
        WAIT_UNSAFE,
        INTERACT,
        WAIT_FOR_SLEEP,
        OBSERVE_SLEEPING,
        WAIT_SLEEPING,
        VERIFIED
    }

    public enum BedAccess { MISSING, WRONG_DIMENSION, LOAD, INVALID, VERIFY_ACCESS }

    /** Registration authorizes exact furniture use; Stock separately authorizes provisioning. */
    public static boolean hasRegisteredBed(HomeEconomySession.Snapshot snapshot) {
        return snapshot != null && snapshot.home() != null
                && snapshot.pinnedAssets().containsKey(HomeEconomySession.AssetRole.BED);
    }

    /** Shared by the body-lease adapter and its execution gate; no request means no work. */
    public static boolean hasWorkAuthority(HomeEconomySession.Snapshot snapshot,
            boolean stockRequested, boolean sleepRequested) {
        return snapshot != null && (snapshot.enabled() && stockRequested
                || sleepRequested && hasRegisteredBed(snapshot));
    }

    /**
     * A new owner request reobserves external geometry, not stale stock status.
     * A completed Home-return failure belongs to that route, not to the exact
     * bed's independent access/property checks. A settled full chest blocks
     * surplus storage, not use of the separately registered bed. Outstanding
     * transfer debt still reconciles before bed custody. Identity/accounting failures
     * without an outstanding recovery owner remain fenced.
     */
    public static boolean mayReobserveBlock(String code, boolean retryable, boolean pendingDebt) {
        return mayReobserveBlock(code, retryable, pendingDebt, true);
    }

    public static boolean mayReobserveBlock(
            String code, boolean retryable, boolean pendingDebt, boolean explicitOwnerRequest) {
        return pendingDebt || retryable
                && ("unsafe_home_workspace".equals(code) || "home_sleep_blocked".equals(code)
                || "home_chest_full".equals(code)
                || explicitOwnerRequest && "home_route_blocked".equals(code));
    }

    /** No unrelated furniture or crafting-anchor condition belongs to bed custody. */
    public static boolean bedCustodyReady(
            boolean pendingTransfer,
            boolean pendingAcquisition,
            boolean pendingWaterFill,
            boolean cursorEmpty,
            boolean pendingClick) {
        return !pendingTransfer && !pendingAcquisition && !pendingWaterFill
                && cursorEmpty && !pendingClick;
    }

    /** Unloaded is unknown, while a loaded invalid exact bed is a real failure. */
    public static BedAccess bedAccess(
            boolean ownedPlacement, boolean correctDimension, boolean loaded, boolean verified) {
        if (!ownedPlacement) return BedAccess.MISSING;
        if (!correctDimension) return BedAccess.WRONG_DIMENSION;
        if (!loaded) return BedAccess.LOAD;
        return verified ? BedAccess.VERIFY_ACCESS : BedAccess.INVALID;
    }

    public record Cursor(
            boolean interactionPending,
            boolean sleepingObserved,
            long sleepingObservedWorldTime) {
        public Cursor {
            if (!sleepingObserved && sleepingObservedWorldTime != -1L) {
                throw new IllegalArgumentException(
                        "a sleep clock requires observed sleeping state");
            }
            if (sleepingObservedWorldTime < -1L
                    || sleepingObserved && sleepingObservedWorldTime < 0L) {
                throw new IllegalArgumentException("observed sleep requires a valid world clock");
            }
        }

        public static Cursor empty() {
            return new Cursor(false, false, -1L);
        }

        public Cursor withoutPendingInteraction() {
            return new Cursor(false, sleepingObserved, sleepingObservedWorldTime);
        }
    }

    public record Observation(
            boolean overworld,
            boolean naturalNight,
            boolean immediateHostilePressure,
            boolean sleeping,
            long worldTime) {
        public Observation {
            if (worldTime < 0L) {
                throw new IllegalArgumentException("world time cannot be negative");
            }
        }

        public long day() {
            return Math.floorDiv(worldTime, 24_000L);
        }
    }

    public record Decision(Action action, Cursor cursor, String detail) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            cursor = Objects.requireNonNull(cursor, "cursor");
            detail = Objects.requireNonNullElse(detail, "").trim();
        }
    }

    /** Night and bed ownership are facts, not authority; only an explicit idle request may start. */
    public static boolean mayStart(
            boolean explicitRequest,
            boolean idleAuthority,
            boolean overworld,
            boolean naturalNight) {
        return explicitRequest && idleAuthority && overworld && naturalNight;
    }

    public static Decision decide(Cursor cursor, Observation observation) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(observation, "observation");
        if (!observation.overworld()) {
            return decision(Action.INACTIVE, Cursor.empty(),
                    "sleep is restricted to the natural Overworld night");
        }
        if (cursor.sleepingObserved()
                && !observation.sleeping()
                && !observation.naturalNight()
                // Native dawn can wake the player before the calendar day rolls
                // over. A forward clock plus native daytime is the actual proof.
                && observation.worldTime() > cursor.sleepingObservedWorldTime()) {
            return decision(Action.VERIFIED, Cursor.empty(),
                    "observed sleeping and the natural night advancing to morning");
        }
        if (observation.immediateHostilePressure()) {
            return decision(Action.WAIT_UNSAFE, cursor.withoutPendingInteraction(),
                    "hostile pressure makes the owned bed unsafe");
        }
        if (observation.sleeping()) {
            Cursor observed = cursor.sleepingObserved()
                    ? cursor.withoutPendingInteraction()
                    : new Cursor(false, true, observation.worldTime());
            return decision(cursor.sleepingObserved()
                            ? Action.WAIT_SLEEPING : Action.OBSERVE_SLEEPING,
                    observed,
                    cursor.sleepingObserved()
                            ? "sleeping in the exact owned Home bed"
                            : "observed the player enter sleeping state");
        }
        if (!observation.naturalNight()) {
            return decision(Action.INACTIVE, Cursor.empty(),
                    "the natural world is not currently night");
        }
        if (cursor.interactionPending()) {
            return decision(Action.WAIT_FOR_SLEEP, cursor,
                    "bed interaction accepted; waiting to observe sleeping state");
        }
        return decision(Action.INTERACT,
                new Cursor(true, cursor.sleepingObserved(), cursor.sleepingObservedWorldTime()),
                "interact with the exact owned Home bed");
    }

    /** Mirrors the server's independent foot-or-head proximity gate for sleeping. */
    public static boolean withinServerBedRange(
            double playerX,
            double playerY,
            double playerZ,
            int footX,
            int footY,
            int footZ,
            int headX,
            int headY,
            int headZ) {
        if (!Double.isFinite(playerX) || !Double.isFinite(playerY)
                || !Double.isFinite(playerZ)) {
            return false;
        }
        return withinServerBedPartRange(playerX, playerY, playerZ, footX, footY, footZ)
                || withinServerBedPartRange(
                playerX, playerY, playerZ, headX, headY, headZ);
    }

    /**
     * Requires every horizontal position Baritone may occupy inside one goal
     * block to pass the server's bed gate. Checking only the block center is
     * unsafe at the inclusive three-block boundary because path completion may
     * leave the player elsewhere inside that block.
     */
    public static boolean standBlockWithinServerBedRange(
            int standX,
            int standY,
            int standZ,
            int footX,
            int footY,
            int footZ,
            int headX,
            int headY,
            int headZ) {
        return withinServerBedRange(
                standX, standY, standZ,
                footX, footY, footZ, headX, headY, headZ)
                && withinServerBedRange(
                standX + 1.0D, standY, standZ,
                footX, footY, footZ, headX, headY, headZ)
                && withinServerBedRange(
                standX, standY, standZ + 1.0D,
                footX, footY, footZ, headX, headY, headZ)
                && withinServerBedRange(
                standX + 1.0D, standY, standZ + 1.0D,
                footX, footY, footZ, headX, headY, headZ);
    }

    private static boolean withinServerBedPartRange(
            double playerX,
            double playerY,
            double playerZ,
            int blockX,
            int blockY,
            int blockZ) {
        return Math.abs(playerX - (blockX + 0.5D)) <= SERVER_BED_HORIZONTAL_RANGE
                && Math.abs(playerY - blockY) <= SERVER_BED_VERTICAL_RANGE
                && Math.abs(playerZ - (blockZ + 0.5D)) <= SERVER_BED_HORIZONTAL_RANGE;
    }

    private static Decision decision(Action action, Cursor cursor, String detail) {
        return new Decision(action, cursor, detail);
    }

    private HomeSleepPolicy() {
    }
}
