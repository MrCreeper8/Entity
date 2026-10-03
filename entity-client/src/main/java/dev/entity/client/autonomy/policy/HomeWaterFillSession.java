package dev.entity.client.autonomy.policy;

import java.io.IOException;
import java.util.Objects;

/**
 * Durable, single-bucket transaction shared by Home upkeep and explicit missions.
 *
 * <p>The intent is committed before item use.  A restart therefore classifies
 * the exact before/after bucket counts and renewable-source geometry instead
 * of blindly using a second bucket.</p>
 */
public final class HomeWaterFillSession {
    public static final int MAXIMUM_ATTEMPTS = 3;

    public enum CallerKind { HOME, MISSION }

    public enum Phase {
        SELECTING_BUCKET,
        READY_TO_USE,
        AWAITING_RESULT,
        BLOCKED
    }

    public interface Store {
        Snapshot load() throws IOException;

        void save(Snapshot snapshot) throws IOException;
    }

    public record Intent(
            String id,
            long homeGeneration,
            String homeFingerprint,
            String dimension,
            int sourceX,
            int sourceY,
            int sourceZ,
            int renewableNeighborMask,
            int emptyBucketBefore,
            int waterBucketBefore,
            int hotbarSlot,
            int previousSelectedSlot,
            Phase phase,
            int attempts,
            long useIssuedAtMillis,
            String detail,
            long startedAtMillis,
            CallerKind callerKind,
            String missionId,
            String actionId,
            String sourceState,
            boolean fenced) {
        /** Schema-1/source compatibility: this constructor always means real Home upkeep. */
        public Intent(String id, long homeGeneration, String homeFingerprint, String dimension,
                int sourceX, int sourceY, int sourceZ, int renewableNeighborMask,
                int emptyBucketBefore, int waterBucketBefore, int hotbarSlot, int previousSelectedSlot,
                Phase phase, int attempts, long useIssuedAtMillis, String detail, long startedAtMillis) {
            this(id, homeGeneration, homeFingerprint, dimension, sourceX, sourceY, sourceZ,
                    renewableNeighborMask, emptyBucketBefore, waterBucketBefore, hotbarSlot,
                    previousSelectedSlot, phase, attempts, useIssuedAtMillis, detail, startedAtMillis,
                    CallerKind.HOME, "", "", "", false);
        }

        public Intent {
            id = requireText(id, "id", 256);
            callerKind = Objects.requireNonNull(callerKind, "callerKind");
            missionId = Objects.requireNonNullElse(missionId, "");
            actionId = Objects.requireNonNullElse(actionId, "");
            sourceState = Objects.requireNonNullElse(sourceState, "");
            if (callerKind == CallerKind.HOME) {
                if (homeGeneration <= 0L) throw new IllegalArgumentException("Home generation must be positive");
                homeFingerprint = requireText(homeFingerprint, "homeFingerprint", 128);
                if (!missionId.isEmpty() || !actionId.isEmpty() || fenced)
                    throw new IllegalArgumentException("Home fill cannot impersonate a mission caller");
            } else {
                if (homeGeneration != 0L || !Objects.requireNonNullElse(homeFingerprint, "").isEmpty())
                    throw new IllegalArgumentException("mission fill cannot invent Home authority");
                homeFingerprint = "";
                missionId = requireText(missionId, "missionId", 256);
                actionId = requireText(actionId, "actionId", 256);
                sourceState = requireText(sourceState, "sourceState", 2048);
            }
            dimension = requireText(dimension, "dimension", 256);
            if (renewableNeighborMask < 0 || renewableNeighborMask > 15
                    || callerKind == CallerKind.HOME && Integer.bitCount(renewableNeighborMask) < 2) {
                throw new IllegalArgumentException(
                        "renewable water proof needs at least two horizontal source neighbors");
            }
            if (emptyBucketBefore <= 0 || waterBucketBefore < 0) {
                throw new IllegalArgumentException("invalid bucket before-counts");
            }
            if (hotbarSlot < 0 || hotbarSlot > 8) {
                throw new IllegalArgumentException("hotbar slot must be 0..8");
            }
            if (previousSelectedSlot < 0 || previousSelectedSlot > 8) {
                throw new IllegalArgumentException("previous selected slot must be 0..8");
            }
            phase = Objects.requireNonNull(phase, "phase");
            if (attempts < 0 || attempts > MAXIMUM_ATTEMPTS) {
                throw new IllegalArgumentException("water-fill attempts are out of range");
            }
            if ((phase == Phase.AWAITING_RESULT) != (useIssuedAtMillis > 0L)) {
                throw new IllegalArgumentException(
                        "only an awaiting water fill may own a use timestamp");
            }
            detail = Objects.requireNonNullElse(detail, "").trim();
            if ((phase == Phase.BLOCKED) != !detail.isEmpty()) {
                throw new IllegalArgumentException(
                        "only a blocked water fill may own blocker detail");
            }
            if (startedAtMillis < 0L) {
                throw new IllegalArgumentException("start time cannot be negative");
            }
        }

        public boolean homeCaller() { return callerKind == CallerKind.HOME; }

        public boolean matchesMission(String planId, String frameId) {
            return callerKind == CallerKind.MISSION && missionId.equals(planId) && actionId.equals(frameId);
        }
    }

    public record Snapshot(long revision, Intent intent, long updatedAtMillis) {
        public Snapshot {
            if (revision < 0L || updatedAtMillis < 0L) {
                throw new IllegalArgumentException("revision/time cannot be negative");
            }
        }

        public static Snapshot empty() {
            return new Snapshot(0L, null, 0L);
        }
    }

    private final Store store;
    private Snapshot current;

    public HomeWaterFillSession(Store store) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        this.current = Objects.requireNonNull(store.load(), "store returned null snapshot");
    }

    public synchronized Snapshot snapshot() {
        return current;
    }

    public synchronized Snapshot begin(Intent intent, long nowMillis) throws IOException {
        Objects.requireNonNull(intent, "intent");
        if (current.intent() != null) {
            throw new IllegalStateException("another water-fill intent is unresolved");
        }
        if (intent.phase() != Phase.SELECTING_BUCKET || intent.attempts() != 0) {
            throw new IllegalArgumentException("new water fill must begin in SELECTING_BUCKET");
        }
        return commit(intent, nowMillis);
    }

    public synchronized Snapshot selected(String id, long nowMillis) throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.SELECTING_BUCKET
                && intent.phase() != Phase.READY_TO_USE) {
            throw new IllegalStateException("water fill is not selecting its exact bucket");
        }
        return commit(copy(intent, Phase.READY_TO_USE, intent.attempts(), 0L, ""), nowMillis);
    }

    /** Must be persisted before the exact block-use packet is sent. */
    public synchronized Snapshot markUseIssued(String id, long nowMillis) throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.READY_TO_USE || intent.fenced()) {
            throw new IllegalStateException("water fill is not ready to issue use");
        }
        int attempts = Math.incrementExact(intent.attempts());
        if (attempts > MAXIMUM_ATTEMPTS) {
            throw new IllegalStateException("water fill exceeded its bounded attempts");
        }
        return commit(copy(intent, Phase.AWAITING_RESULT, attempts, checkedTime(nowMillis), ""),
                nowMillis);
    }

    public synchronized Snapshot retryUnchanged(String id, long nowMillis) throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.AWAITING_RESULT || intent.fenced()) {
            throw new IllegalStateException("only an issued water fill may retry");
        }
        if (intent.attempts() >= MAXIMUM_ATTEMPTS) {
            throw new IllegalStateException("water fill has no bounded retry remaining");
        }
        return commit(copy(intent, Phase.READY_TO_USE, intent.attempts(), 0L, ""), nowMillis);
    }

    public synchronized Snapshot block(String id, String detail, long nowMillis)
            throws IOException {
        Intent intent = requireIntent(id);
        return commit(copy(intent, Phase.BLOCKED, intent.attempts(), 0L,
                requireText(detail, "detail", 4096)), nowMillis);
    }

    public synchronized Snapshot complete(String id, long nowMillis) throws IOException {
        requireIntent(id);
        return commit(null, nowMillis);
    }

    /** Clears only a proved unchanged intent; this never claims a filled bucket. */
    public synchronized Snapshot abandonUnchanged(String id, long nowMillis) throws IOException {
        requireIntent(id);
        return commit(null, nowMillis);
    }

    /**
     * Confirmed owner Home replacement/clear fences future use packets without
     * claiming either the before-state or a successful fill. A packet already
     * accepted by vanilla may still resolve physically and is later observed as
     * ordinary inventory truth.
     */
    public synchronized Snapshot ownerFence(long nowMillis) throws IOException {
        if (current.intent() == null) return current;
        if (!current.intent().homeCaller()) return fenceMission(nowMillis);
        return commit(null, nowMillis);
    }

    /** Stop/restart may observe an issued fill, but never replay its unconfirmed packet. */
    public synchronized Snapshot fenceMission(long nowMillis) throws IOException {
        Intent intent = current.intent();
        if (intent == null || intent.homeCaller() || intent.fenced()) return current;
        if (intent.attempts() == 0) return commit(null, nowMillis);
        return commit(new Intent(intent.id(), 0L, "", intent.dimension(), intent.sourceX(),
                intent.sourceY(), intent.sourceZ(), intent.renewableNeighborMask(),
                intent.emptyBucketBefore(), intent.waterBucketBefore(), intent.hotbarSlot(),
                intent.previousSelectedSlot(), intent.phase(), intent.attempts(),
                intent.useIssuedAtMillis(), intent.detail(), intent.startedAtMillis(),
                intent.callerKind(), intent.missionId(), intent.actionId(), intent.sourceState(), true), nowMillis);
    }

    private Intent requireIntent(String id) {
        String expected = requireText(id, "id", 256);
        if (current.intent() == null || !current.intent().id().equals(expected)) {
            throw new IllegalArgumentException("water-fill ID does not match durable intent");
        }
        return current.intent();
    }

    private Snapshot commit(Intent intent, long nowMillis) throws IOException {
        Snapshot candidate = new Snapshot(
                Math.incrementExact(current.revision()), intent, checkedTime(nowMillis));
        store.save(candidate);
        current = candidate;
        return candidate;
    }

    private static Intent copy(
            Intent source,
            Phase phase,
            int attempts,
            long useIssuedAtMillis,
            String detail) {
        return new Intent(
                source.id(), source.homeGeneration(), source.homeFingerprint(),
                source.dimension(), source.sourceX(), source.sourceY(), source.sourceZ(),
                source.renewableNeighborMask(), source.emptyBucketBefore(),
                source.waterBucketBefore(), source.hotbarSlot(),
                source.previousSelectedSlot(), phase, attempts,
                useIssuedAtMillis, detail, source.startedAtMillis(), source.callerKind(),
                source.missionId(), source.actionId(), source.sourceState(), source.fenced());
    }

    private static long checkedTime(long value) {
        if (value < 0L) throw new IllegalArgumentException("time cannot be negative");
        return value;
    }

    private static String requireText(String value, String field, int maximumLength) {
        String text = Objects.requireNonNull(value, field).trim();
        if (text.isEmpty() || text.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be 1.." + maximumLength + " chars");
        }
        return text;
    }
}
