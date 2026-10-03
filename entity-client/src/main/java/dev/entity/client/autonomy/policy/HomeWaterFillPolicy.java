package dev.entity.client.autonomy.policy;

import java.util.Objects;

/** Pure reconciliation policy for one persisted empty-bucket fill. */
public final class HomeWaterFillPolicy {
    public static final long RESULT_TIMEOUT_MILLIS = 2_500L;
    public static final long NATIVE_SETTLE_LIMIT_MILLIS = 1_000L;
    public static final double NATIVE_GOAL_CELL_OFFSET = 0.49;
    private static final int HORIZONTAL_MASK = 0b1111;

    public enum Action {
        SELECT_BUCKET,
        ROUTE_TO_SOURCE,
        ISSUE_USE,
        WAIT_ACKNOWLEDGEMENT,
        WAIT_RESULT,
        RETRY_UNCHANGED,
        COMMIT_FILLED,
        BLOCK
    }

    public record Observation(
            boolean bindingMatches,
            boolean cursorEmpty,
            boolean pendingInventoryClick,
            int emptyBucketCount,
            int waterBucketCount,
            boolean exactSelectedBucket,
            boolean sourceReachable,
            boolean sourceRenewable,
            int renewableNeighborMask,
            long nowMillis) {
        public Observation {
            if (emptyBucketCount < 0 || waterBucketCount < 0 || nowMillis < 0L) {
                throw new IllegalArgumentException("water-fill observation counts/time are invalid");
            }
        }
    }

    public record Decision(Action action, String code, String detail) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            code = Objects.requireNonNullElse(code, "").trim();
            detail = Objects.requireNonNullElse(detail, "").trim();
        }
    }

    private HomeWaterFillPolicy() {
    }

    public enum ApproachAction { WAIT_FOR_NATIVE, ISSUE_USE, TRY_ALTERNATE, BLOCK }

    /** Goal-cell entry can precede the native executor's physical stopping point. */
    public static ApproachAction completedApproach(
            boolean actualSourceRayHits, boolean bodySettled, long elapsedMillis,
            boolean alternateAlreadyTried) {
        if (actualSourceRayHits) return ApproachAction.ISSUE_USE;
        long elapsed = Math.max(0, elapsedMillis);
        if (elapsed < 150 || (!bodySettled && elapsed < NATIVE_SETTLE_LIMIT_MILLIS)) {
            return ApproachAction.WAIT_FOR_NATIVE;
        }
        if (!bodySettled || alternateAlreadyTried) return ApproachAction.BLOCK;
        return ApproachAction.TRY_ALTERNATE;
    }

    /** Native arrival is not bucket reach; a completed unusable approach cannot loop. */
    public static Decision afterCompletedApproach(boolean actualSourceRayHits) {
        return actualSourceRayHits
                ? decision(Action.ISSUE_USE, "", "native approach completed with an actual source-fluid ray")
                : block("water_interaction_unreachable",
                        "native route reached the water stand, but the actual bucket ray cannot reach its source");
    }

    /**
     * Conservative precondition for taking one water source without degrading
     * the world. Vanilla can create a source from two horizontal source
     * neighbours and stable support, but an exposed edge is a poor Home water
     * reserve: the source can spread away from the intended cell before the
     * refill settles. Entity therefore accepts either an interior source or a
     * conventional basin whose non-water edges have full lateral containment.
     */
    public static boolean regenerationSafeGeometry(
            int sourceNeighborMask,
            int containedNeighborMask,
            boolean stableSupport) {
        int sources = sourceNeighborMask & HORIZONTAL_MASK;
        int contained = containedNeighborMask & HORIZONTAL_MASK;
        if (!stableSupport || Integer.bitCount(sources) < 2) return false;
        int nonSourceEdges = HORIZONTAL_MASK & ~sources;
        return (contained & nonSourceEdges) == nonSourceEdges;
    }

    public static Decision decide(
            HomeWaterFillSession.Intent intent,
            Observation observation) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(observation, "observation");
        if (!observation.bindingMatches()) {
            return block(intent.homeCaller() ? "home_binding_changed" : "water_caller_changed",
                    "Exact water-fill caller changed while its intent was unresolved");
        }
        if (!observation.cursorEmpty()) {
            return block("water_fill_cursor_occupied",
                    "manual/foreign cursor must be cleared; Entity issued zero clicks");
        }
        if (observation.pendingInventoryClick()) {
            return decision(Action.WAIT_ACKNOWLEDGEMENT, "",
                    "waiting for the exact bucket-selection acknowledgement");
        }
        int emptyDelta = intent.emptyBucketBefore() - observation.emptyBucketCount();
        int waterDelta = observation.waterBucketCount() - intent.waterBucketBefore();
        if (emptyDelta == 1 && waterDelta == 1) {
            if (!intent.homeCaller()) {
                return intent.attempts() > 0
                        ? decision(Action.COMMIT_FILLED, "",
                        "verified one issued source use: bucket -1 and water_bucket +1")
                        : block("water_fill_unissued_delta", "bucket counts changed before Entity issued its exact fill");
            }
            if (observation.sourceRenewable()
                    && observation.renewableNeighborMask()
                    == intent.renewableNeighborMask()) {
                return decision(Action.COMMIT_FILLED, "",
                        "verified bucket -1, water_bucket +1, and unchanged renewable source");
            }
            if (intent.phase() == HomeWaterFillSession.Phase.AWAITING_RESULT
                    && observation.nowMillis() - intent.useIssuedAtMillis()
                    >= RESULT_TIMEOUT_MILLIS) {
                return block("water_source_not_restored",
                        "filled bucket is present, but the exact renewable source geometry "
                                + "did not restore before the bounded deadline"
                                + geometryDetail(intent, observation));
            }
            return decision(Action.WAIT_RESULT, "",
                    "bucket counts committed; waiting for exact renewable source geometry"
                            + geometryDetail(intent, observation));
        }
        if (emptyDelta != 0 || waterDelta != 0) {
            return block("water_fill_counts_diverged",
                    "bucket counts changed without the exact conserved -1/+1 fill result");
        }
        if (intent.fenced()) {
            if (intent.phase() == HomeWaterFillSession.Phase.AWAITING_RESULT
                    && observation.nowMillis() - intent.useIssuedAtMillis() < RESULT_TIMEOUT_MILLIS) {
                return decision(Action.WAIT_RESULT, "", "observing the fenced issued fill; no new use permitted");
            }
            return block("water_fill_fenced", "issued fill settled unchanged after Stop/restart; explicit retry required");
        }
        return switch (intent.phase()) {
            case SELECTING_BUCKET -> observation.exactSelectedBucket()
                    ? decision(Action.ISSUE_USE, "", "exact empty bucket is selected")
                    : decision(Action.SELECT_BUCKET, "", "select the exact empty bucket");
            case READY_TO_USE -> {
                if (!observation.exactSelectedBucket()) {
                    yield decision(Action.SELECT_BUCKET, "",
                            "restore the exact empty-bucket selection before use");
                }
                if (!observation.sourceRenewable()
                        || observation.renewableNeighborMask()
                        != intent.renewableNeighborMask()) {
                    yield block("water_source_changed",
                            "exact loaded/reachable renewable water proof changed before use");
                }
                if (!observation.sourceReachable()) {
                    yield decision(Action.ROUTE_TO_SOURCE, "",
                            "route to the verified safe water interaction stand");
                }
                yield decision(Action.ISSUE_USE, "",
                        "persist use intent, then use the exact renewable source once");
            }
            case AWAITING_RESULT -> {
                long elapsed = Math.max(0L,
                        observation.nowMillis() - intent.useIssuedAtMillis());
                if (elapsed < RESULT_TIMEOUT_MILLIS) {
                    yield decision(Action.WAIT_RESULT, "",
                            "waiting for the persisted water-fill result");
                }
                if (intent.attempts() < HomeWaterFillSession.MAXIMUM_ATTEMPTS) {
                    yield decision(Action.RETRY_UNCHANGED, "",
                            "exact before-counts remained unchanged; one bounded retry is safe");
                }
                yield block("water_fill_unacknowledged",
                        "server never acknowledged the bounded water-fill attempts");
            }
            case BLOCKED -> block("water_fill_blocked", intent.detail());
        };
    }

    private static Decision block(String code, String detail) {
        return new Decision(Action.BLOCK, code, detail);
    }

    private static String geometryDetail(
            HomeWaterFillSession.Intent intent,
            Observation observation) {
        return " (expectedMask=" + intent.renewableNeighborMask()
                + ", observedMask=" + observation.renewableNeighborMask()
                + ", renewable=" + observation.sourceRenewable() + ')';
    }

    private static Decision decision(Action action, String code, String detail) {
        return new Decision(action, code, detail);
    }
}
