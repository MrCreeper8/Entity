package dev.entity.client.autonomy.policy;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Durable lifecycle for one Entity-owned reversible portal interaction. */
public final class DoorPassageSession {
    public static final int MAXIMUM_ACTION_ATTEMPTS = 3;
    public static final long ACTION_RESULT_TIMEOUT_MILLIS = 2_500L;
    public static final long CROSSING_TIMEOUT_MILLIS = 12_000L;
    public static final String RESTORE_AUTHORITY_TIMEOUT_DETAIL =
            "timed out waiting for exact door-restoration authority";

    /** Vanilla gate use may reverse facing; identity retains the axis and every other property. */
    public static boolean samePortalFingerprint(String expected, String actual) {
        return expected != null && actual != null
                && gateAxisFingerprint(expected).equals(gateAxisFingerprint(actual));
    }

    private static String gateAxisFingerprint(String value) {
        int separator = value.indexOf('|');
        if (separator < 0 || !value.substring(0, separator).endsWith("_fence_gate")) return value;
        return value.replaceAll("\\|facing=(east|west)(?=\\||$)", "|facing=x")
                .replaceAll("\\|facing=(north|south)(?=\\||$)", "|facing=z");
    }

    public static boolean samePortalNormal(String kind, int x, int y, int z, int otherX, int otherY, int otherZ) {
        // Preserve the saved signed normal/approachSign for crossing calculations.
        return "fence_gate".equals(kind)
                ? y == otherY && Math.abs(x) == Math.abs(otherX) && Math.abs(z) == Math.abs(otherZ)
                : x == otherX && y == otherY && z == otherZ;
    }

    public enum Phase {
        WAITING_OPEN_AUTHORITY,
        AWAITING_OPEN,
        CROSSING,
        WAITING_RESTORE_AUTHORITY,
        AWAITING_RESTORE,
        BLOCKED
    }

    public interface Store {
        Snapshot load() throws IOException;

        void save(Snapshot snapshot) throws IOException;
    }

    public record Intent(
            String id,
            String missionId,
            String dimension,
            int x,
            int y,
            int z,
            String kind,
            String blockId,
            String stableFingerprint,
            int normalX,
            int normalY,
            int normalZ,
            int height,
            int approachSign,
            boolean restorationDue,
            Phase phase,
            int openAttempts,
            int restoreAttempts,
            long actionIssuedAtMillis,
            long startedAtMillis,
            String detail) {
        public Intent {
            id = text(id, "id", 256);
            missionId = text(missionId, "missionId", 256);
            dimension = text(dimension, "dimension", 256);
            kind = text(kind, "kind", 32);
            blockId = text(blockId, "blockId", 256);
            stableFingerprint = text(stableFingerprint, "stableFingerprint", 2048);
            int normalMagnitude = Math.abs(normalX) + Math.abs(normalY) + Math.abs(normalZ);
            if (normalMagnitude != 1) {
                throw new IllegalArgumentException("portal normal must be one cardinal axis");
            }
            if (height < 1 || height > 2) {
                throw new IllegalArgumentException("portal height must be 1..2");
            }
            if (approachSign != -1 && approachSign != 1) {
                throw new IllegalArgumentException("approach sign must be -1 or 1");
            }
            phase = Objects.requireNonNull(phase, "phase");
            boolean opening = phase == Phase.WAITING_OPEN_AUTHORITY
                    || phase == Phase.AWAITING_OPEN;
            boolean restoring = phase == Phase.CROSSING
                    || phase == Phase.WAITING_RESTORE_AUTHORITY
                    || phase == Phase.AWAITING_RESTORE;
            if (opening && restorationDue) {
                throw new IllegalArgumentException(
                        "an unopened portal cannot own restoration debt");
            }
            if (restoring && !restorationDue) {
                throw new IllegalArgumentException(
                        "an opened portal must retain restoration debt");
            }
            if (openAttempts < 0 || openAttempts > MAXIMUM_ACTION_ATTEMPTS
                    || restoreAttempts < 0
                    || restoreAttempts > MAXIMUM_ACTION_ATTEMPTS) {
                throw new IllegalArgumentException("portal attempts are out of range");
            }
            boolean awaiting = phase == Phase.AWAITING_OPEN
                    || phase == Phase.AWAITING_RESTORE;
            if (awaiting != (actionIssuedAtMillis > 0L)) {
                throw new IllegalArgumentException(
                        "only an issued portal action may own an issue timestamp");
            }
            if (startedAtMillis < 0L) {
                throw new IllegalArgumentException("portal start time cannot be negative");
            }
            detail = Objects.requireNonNullElse(detail, "").trim();
            if ((phase == Phase.BLOCKED) != !detail.isEmpty()) {
                throw new IllegalArgumentException(
                        "only a blocked portal transaction may own blocker detail");
            }
        }

        public double signedDistance(double bodyX, double bodyY, double bodyZ) {
            return (bodyX - (x + 0.5D)) * normalX
                    + (bodyY - (y + 0.5D)) * normalY
                    + (bodyZ - (z + 0.5D)) * normalZ;
        }

        public boolean crossed(double bodyX, double bodyY, double bodyZ) {
            double signed = signedDistance(bodyX, bodyY, bodyZ);
            return Math.signum(signed) == -approachSign && Math.abs(signed) >= 0.55D;
        }

        /** The same closure plane used by the live living-body safety check. */
        public ClosureBounds closureBounds() {
            return new ClosureBounds(
                    x + (normalX == 0 ? 0 : 0.42D),
                    y + (normalY == 0 ? 0 : 0.42D),
                    z + (normalZ == 0 ? 0 : 0.42D),
                    x + (normalX == 0 ? 1 : 0.58D),
                    y + (normalY == 0 ? height : 0.58D),
                    z + (normalZ == 0 ? 1 : 0.58D));
        }

        /** A route may be handed off while slab-clear feet still occupy the door itself. */
        public boolean occupiesDoorwayCell(double bodyX, double bodyY, double bodyZ) {
            return kind.equals("door") && Math.floor(bodyX) == x && Math.floor(bodyZ) == z
                    && bodyY >= y && bodyY < y + height;
        }

        /** Nearby level feet cells, current side first; the adapter must prove live safety. */
        public List<ClearanceCell> clearanceCells(double bodyX, double bodyY, double bodyZ) {
            if (normalY != 0) return List.of(); // No invented vertical trapdoor controller.
            int side = signedDistance(bodyX, bodyY, bodyZ) < 0 ? -1 : 1;
            int feetY = (int) Math.floor(bodyY);
            return List.of(new ClearanceCell(x + normalX * side, feetY, z + normalZ * side),
                    new ClearanceCell(x + normalX * side * 2, feetY, z + normalZ * side * 2),
                    new ClearanceCell(x - normalX * side, feetY, z - normalZ * side),
                    new ClearanceCell(x - normalX * side * 2, feetY, z - normalZ * side * 2));
        }
    }

    public record ClearanceCell(int x, int y, int z) { }
    public record ClosureBounds(double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ) {
        public boolean intersects(double x0, double y0, double z0, double x1, double y1, double z1) {
            return x0 < maxX && x1 > minX && y0 < maxY && y1 > minY && z0 < maxZ && z1 > minZ;
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

    public DoorPassageSession(Store store) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        current = Objects.requireNonNull(store.load(), "store returned null snapshot");
    }

    public synchronized Snapshot snapshot() {
        return current;
    }

    public synchronized Snapshot begin(Intent intent, long nowMillis) throws IOException {
        Objects.requireNonNull(intent, "intent");
        if (current.intent() != null) {
            throw new IllegalStateException("another portal transaction is unresolved");
        }
        if (intent.phase() != Phase.WAITING_OPEN_AUTHORITY
                || intent.openAttempts() != 0 || intent.restoreAttempts() != 0
                || intent.restorationDue()) {
            throw new IllegalArgumentException("new portal intent has an invalid phase/attempts");
        }
        return commit(intent, nowMillis);
    }

    /** Persisted before the exact open interaction packet. */
    public synchronized Snapshot markOpenIssued(String id, long nowMillis) throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.WAITING_OPEN_AUTHORITY) {
            throw new IllegalStateException("portal is not waiting for open authority");
        }
        int attempts = Math.incrementExact(intent.openAttempts());
        if (attempts > MAXIMUM_ACTION_ATTEMPTS) {
            throw new IllegalStateException("portal open attempts are exhausted");
        }
        return commit(copy(intent, Phase.AWAITING_OPEN, attempts,
                intent.restoreAttempts(), checkedTime(nowMillis), ""), nowMillis);
    }

    public synchronized Snapshot opened(String id, long nowMillis) throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.AWAITING_OPEN) {
            throw new IllegalStateException("portal is not awaiting an open result");
        }
        return commit(copy(intent, Phase.CROSSING, intent.openAttempts(),
                intent.restoreAttempts(), 0L, "", true), nowMillis);
    }

    public synchronized Snapshot retryOpen(String id, long nowMillis) throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.AWAITING_OPEN
                || intent.openAttempts() >= MAXIMUM_ACTION_ATTEMPTS) {
            throw new IllegalStateException("portal open is not retryable");
        }
        return commit(copy(intent, Phase.WAITING_OPEN_AUTHORITY,
                intent.openAttempts(), intent.restoreAttempts(), 0L, ""), nowMillis);
    }

    /** A confirmed opening was externally closed: its old debt is physically settled. */
    public synchronized Snapshot reopenAfterExternalClosure(String id, long nowMillis) throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.CROSSING) {
            throw new IllegalStateException("only an observed opened crossing can be externally closed");
        }
        // This is a new observed obstacle, not an unacknowledged packet retry. Retain the exact
        // mission/portal and obtain fresh authority; Stop may release this unissued no-debt state.
        return commit(copy(intent, Phase.WAITING_OPEN_AUTHORITY, 0, 0, 0L, "", false), nowMillis);
    }

    public synchronized Snapshot crossed(String id, long nowMillis) throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.CROSSING) {
            throw new IllegalStateException("portal is not in crossing phase");
        }
        return commit(copy(intent, Phase.WAITING_RESTORE_AUTHORITY,
                intent.openAttempts(), intent.restoreAttempts(), 0L, ""), nowMillis);
    }

    /** Cancels the route after opening while retaining the obligation to restore the portal. */
    public synchronized Snapshot abandonCrossing(String id, long nowMillis) throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.CROSSING) {
            throw new IllegalStateException("portal is not in crossing phase");
        }
        return commit(copy(intent, Phase.WAITING_RESTORE_AUTHORITY,
                intent.openAttempts(), intent.restoreAttempts(), 0L, ""), nowMillis);
    }

    /** Persisted before the exact restore interaction packet. */
    public synchronized Snapshot markRestoreIssued(String id, long nowMillis)
            throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.WAITING_RESTORE_AUTHORITY) {
            throw new IllegalStateException("portal is not waiting for restore authority");
        }
        int attempts = Math.incrementExact(intent.restoreAttempts());
        if (attempts > MAXIMUM_ACTION_ATTEMPTS) {
            throw new IllegalStateException("portal restore attempts are exhausted");
        }
        return commit(copy(intent, Phase.AWAITING_RESTORE, intent.openAttempts(),
                attempts, checkedTime(nowMillis), ""), nowMillis);
    }

    public synchronized Snapshot retryRestore(String id, long nowMillis) throws IOException {
        Intent intent = requireIntent(id);
        if (intent.phase() != Phase.AWAITING_RESTORE
                || intent.restoreAttempts() >= MAXIMUM_ACTION_ATTEMPTS) {
            throw new IllegalStateException("portal restoration is not retryable");
        }
        return commit(copy(intent, Phase.WAITING_RESTORE_AUTHORITY,
                intent.openAttempts(), intent.restoreAttempts(), 0L, ""), nowMillis);
    }

    public synchronized Snapshot block(String id, String detail, long nowMillis)
            throws IOException {
        Intent intent = requireIntent(id);
        return commit(copy(intent, Phase.BLOCKED, intent.openAttempts(),
                intent.restoreAttempts(), 0L, text(detail, "detail", 4096)), nowMillis);
    }

    public synchronized Snapshot complete(String id, long nowMillis) throws IOException {
        requireIntent(id);
        return commit(null, nowMillis);
    }

    /**
     * Releases the route, not an unverified restoration obligation. A terminal
     * opening failure changed no door state and cannot hold later commands hostage.
     * An outstanding open packet is retained until its result has been observed.
     */
    public synchronized void releaseRoute(long nowMillis) throws IOException {
        Intent intent = current.intent();
        if (intent == null) return;
        if (intent.phase() == Phase.BLOCKED && !intent.restorationDue()
                || intent.phase() == Phase.WAITING_OPEN_AUTHORITY
                && intent.openAttempts() == 0) {
            complete(intent.id(), nowMillis);
        } else if (intent.phase() == Phase.CROSSING) {
            abandonCrossing(intent.id(), nowMillis);
        }
    }

    /** Reconciles legacy persisted failures without discarding genuine close debt. */
    public synchronized boolean resolveBlockedOpening(
            boolean portalOpen, boolean replacementRoute, long nowMillis) throws IOException {
        Intent intent = current.intent();
        if (intent == null || intent.phase() != Phase.BLOCKED || intent.restorationDue()
                || !portalOpen && !replacementRoute) return false;
        complete(intent.id(), nowMillis);
        return true;
    }

    /**
     * A fresh live route may retry an expired permission wait, never a denied
     * property decision or exhausted physical toggle. The adapter must first
     * reobserve the exact saved portal and allow at most one restart per route.
     * This retains the same restoration debt and every physical attempt count.
     */
    public synchronized boolean retryTimedOutRestoration(
            boolean freshRoute, long nowMillis) throws IOException {
        Intent intent = current.intent();
        if (!freshRoute || intent == null || intent.phase() != Phase.BLOCKED
                || !intent.restorationDue()
                || intent.restoreAttempts() >= MAXIMUM_ACTION_ATTEMPTS
                || !RESTORE_AUTHORITY_TIMEOUT_DETAIL.equals(intent.detail())) return false;
        commit(copy(intent, Phase.WAITING_RESTORE_AUTHORITY, intent.openAttempts(),
                intent.restoreAttempts(), 0L, ""), nowMillis);
        return true;
    }

    private Intent requireIntent(String id) {
        String expected = text(id, "id", 256);
        Intent intent = current.intent();
        if (intent == null || !intent.id().equals(expected)) {
            throw new IllegalArgumentException("portal transaction ID does not match");
        }
        return intent;
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
            int openAttempts,
            int restoreAttempts,
            long actionIssuedAtMillis,
            String detail) {
        return copy(source, phase, openAttempts, restoreAttempts,
                actionIssuedAtMillis, detail, source.restorationDue());
    }

    private static Intent copy(
            Intent source,
            Phase phase,
            int openAttempts,
            int restoreAttempts,
            long actionIssuedAtMillis,
            String detail,
            boolean restorationDue) {
        return new Intent(
                source.id(), source.missionId(), source.dimension(),
                source.x(), source.y(), source.z(), source.kind(), source.blockId(),
                source.stableFingerprint(), source.normalX(), source.normalY(),
                source.normalZ(), source.height(), source.approachSign(),
                restorationDue, phase,
                openAttempts, restoreAttempts, actionIssuedAtMillis,
                source.startedAtMillis(), detail);
    }

    private static long checkedTime(long value) {
        if (value < 0L) throw new IllegalArgumentException("time cannot be negative");
        return value;
    }

    private static String text(String value, String field, int maximumLength) {
        String checked = Objects.requireNonNullElse(value, "").trim();
        if (checked.isEmpty() || checked.length() > maximumLength
                || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " must be 1.." + maximumLength + " printable chars");
        }
        return checked;
    }
}
