package dev.entity.client.baritone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Observes the lifetime of temporary blocks placed for route traversal.
 *
 * <p>Route support is ordinary survival terrain, not property authority. Entity
 * may pillar with it and may deliberately remove it even while standing on it.
 * The lifecycle survives operation changes only so telemetry can distinguish a
 * real placement rollback from normal route mutation; it never vetoes a break.</p>
 *
 * <p>This class is deliberately Minecraft-free.  The live adapter supplies
 * exact block coordinates and collision-derived support contacts, while the
 * deterministic harness can replay the production place/climb/break/fall
 * history without launching a game.</p>
 */
public final class RouteSupportCustody {
    public static final int MAX_TRACKED_SUPPORTS = 64;
    public static final int MAX_PLACEMENT_ROLLBACKS = 2;
    public static final long PLACEMENT_CONFIRMATION_TICKS = 20L;

    private final LinkedHashMap<Coordinate, Entry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<RollbackKey, Integer> rollbackAttempts = new LinkedHashMap<>();
    private long placements;
    private long dependentContacts;
    private long safeReleases;
    private long deniedBreaks;
    private long placementRollbacks;
    private long prematureRemovals;
    private long capacityTrips;

    /** Registers one observed air-to-solid route-support transition. */
    public PlacementResult placed(
            Coordinate coordinate,
            String operationId,
            long clientTick,
            double bodyY) {
        Objects.requireNonNull(coordinate, "coordinate");
        String owner = normalize(operationId);
        requireTick(clientTick);
        requireFinite(bodyY, "bodyY");

        entries.remove(coordinate);
        while (entries.size() >= MAX_TRACKED_SUPPORTS) {
            var eldest = entries.keySet().iterator();
            if (!eldest.hasNext()) break;
            eldest.next();
            eldest.remove();
            capacityTrips++;
        }
        entries.put(coordinate, new Entry(coordinate, owner, clientTick, bodyY));
        placements++;
        return new PlacementResult(
                true,
                "observed route support " + coordinate
                        + (capacityTrips == 0L ? "" : " with bounded telemetry rotation"));
    }

    /**
     * Updates dependency from one coherent body/collision observation.
     *
     * @param stableSupportContacts exact collision cells currently supporting
     *                              the body; falling/unsafe contacts are omitted
     */
    public BodyResult observeBody(
            String dimension,
            boolean onGround,
            Set<Coordinate> stableSupportContacts,
            long clientTick,
            double bodyY) {
        String currentDimension = requireText(dimension, "dimension");
        Set<Coordinate> contacts = stableSupportContacts == null
                ? Set.of() : Set.copyOf(stableSupportContacts);
        requireTick(clientTick);
        requireFinite(bodyY, "bodyY");

        ArrayList<Coordinate> becameDependent = new ArrayList<>();
        ArrayList<Coordinate> becameIndependent = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (!entry.coordinate.dimension().equals(currentDimension)
                    || entry.state == State.INDEPENDENT) {
                continue;
            }
            entry.maximumBodyY = Math.max(entry.maximumBodyY, bodyY);
            boolean exactContact = onGround && contacts.contains(entry.coordinate);
            if (exactContact) {
                if (entry.state == State.ARMED) {
                    entry.state = State.DEPENDENT;
                    dependentContacts++;
                    becameDependent.add(entry.coordinate);
                }
                continue;
            }

            // Airborne motion, a missing collision sample, and a contactless
            // ledge transition cannot prove safety.  Release only after the
            // exact support was used and vanilla confirms grounded contact on
            // another stable coordinate.
            if (entry.state == State.DEPENDENT && onGround && !contacts.isEmpty()) {
                entry.state = State.INDEPENDENT;
                safeReleases++;
                becameIndependent.add(entry.coordinate);
            }
        }
        return new BodyResult(
                List.copyOf(becameDependent),
                List.copyOf(becameIndependent),
                snapshot());
    }

    /**
     * Records deliberate route-support removal intent without vetoing it.
     * Baritone and the survival controller own fall/clutch decisions; only the
     * player-selected protected-area policy may deny this world mutation.
     */
    public BreakDecision beforeBreak(Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        Entry entry = entries.get(coordinate);
        if (entry == null) {
            return new BreakDecision(Action.ALLOW, coordinate, "support is not body-dependent");
        }
        if (entry.state != State.INDEPENDENT) {
            entry.state = State.INDEPENDENT;
            safeReleases++;
        }
        return new BreakDecision(
                Action.ALLOW,
                coordinate,
                "authorized ordinary survival removal of route support " + coordinate);
    }

    /** Records the observed solid-to-air transition after the mutation boundary. */
    public RemovalResult removed(Coordinate coordinate, long clientTick) {
        Objects.requireNonNull(coordinate, "coordinate");
        requireTick(clientTick);
        Entry entry = entries.remove(coordinate);
        if (entry == null) {
            return RemovalResult.unknown(
                    coordinate, "removed block was not retained route support");
        }
        if (entry.state == State.INDEPENDENT) {
            RemovalResult result = RemovalResult.safe(
                    coordinate, entry.operationId,
                    "independent route support was removed safely");
            retireRollbackHistoryIfOwnerUnretained(entry.operationId);
            return result;
        }

        // Baritone can optimistically observe a just-placed pillar block before
        // the server accepts it.  If the authoritative block disappears during
        // that short acknowledgement window, the body has never touched it, and
        // the exact previous support is still directly below and carrying the
        // same operation, this is a recoverable placement rollback rather than
        // Entity breaking its own scaffold.  The previous support remains in
        // custody as the physical catch while the route is stopped and replanned.
        if (entry.state == State.ARMED) {
            Coordinate catchCoordinate = new Coordinate(
                    coordinate.dimension(), coordinate.x(), coordinate.y() - 1, coordinate.z());
            Entry catchEntry = entries.get(catchCoordinate);
            long ageTicks = clientTick - entry.placedAtTick;
            boolean timelyAuthoritativeRollback = ageTicks >= 0L
                    && ageTicks <= PLACEMENT_CONFIRMATION_TICKS;
            boolean exactDependentCatch = catchEntry != null
                    && catchEntry.state == State.DEPENDENT
                    && catchEntry.operationId.equals(entry.operationId);
            if (timelyAuthoritativeRollback && exactDependentCatch) {
                RollbackKey key = new RollbackKey(entry.operationId, coordinate);
                int attempt = rollbackAttempts.getOrDefault(key, 0) + 1;
                if (attempt <= MAX_PLACEMENT_ROLLBACKS) {
                    rollbackAttempts.put(key, attempt);
                    placementRollbacks++;
                    return RemovalResult.placementRollback(
                            coordinate,
                            catchCoordinate,
                            entry.operationId,
                            attempt,
                            MAX_PLACEMENT_ROLLBACKS,
                            "authoritative placement rollback for route support " + coordinate
                                    + " after " + ageTicks + " tick(s); retained exact catch "
                                    + catchCoordinate + " and stopped the route for bounded retry "
                                    + attempt + '/' + MAX_PLACEMENT_ROLLBACKS);
                }
                prematureRemovals++;
                RemovalResult result = RemovalResult.premature(
                        coordinate,
                        catchCoordinate,
                        entry.operationId,
                        attempt,
                        MAX_PLACEMENT_ROLLBACKS,
                        "route support " + coordinate
                                + " was rejected by the server again after the bounded "
                                + MAX_PLACEMENT_ROLLBACKS + "-attempt placement-recovery ceiling"
                                + " (owner=" + entry.operationId + ", exact catch="
                                + catchCoordinate + ')');
                retireRollbackHistoryIfOwnerUnretained(entry.operationId);
                return result;
            }
        }
        RemovalResult result = RemovalResult.safe(
                coordinate, entry.operationId,
                "ordinary survival route support was removed at " + coordinate
                        + " (owner=" + entry.operationId + ", observedState="
                        + entry.state.name().toLowerCase(Locale.ROOT) + ')');
        retireRollbackHistoryIfOwnerUnretained(entry.operationId);
        return result;
    }

    public boolean protects(Coordinate coordinate) {
        Entry entry = entries.get(Objects.requireNonNull(coordinate, "coordinate"));
        return entry != null && entry.state != State.INDEPENDENT;
    }

    public Snapshot snapshot() {
        int protectedCount = 0;
        int dependentCount = 0;
        double maximumRise = 0.0;
        ArrayList<String> protectedCoordinates = new ArrayList<>();
        for (Map.Entry<Coordinate, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            maximumRise = Math.max(maximumRise, entry.maximumBodyY - entry.startBodyY);
            if (entry.state == State.INDEPENDENT) continue;
            protectedCount++;
            if (entry.state == State.DEPENDENT) dependentCount++;
            protectedCoordinates.add(item.getKey().toString());
        }
        return new Snapshot(
                entries.size(),
                protectedCount,
                dependentCount,
                placements,
                dependentContacts,
                maximumRise,
                safeReleases,
                deniedBreaks,
                placementRollbacks,
                prematureRemovals,
                capacityTrips,
                List.copyOf(protectedCoordinates));
    }

    private void retireRollbackHistoryIfOwnerUnretained(String operationId) {
        boolean ownerStillRetained = entries.values().stream()
                .anyMatch(entry -> entry.operationId.equals(operationId));
        if (!ownerStillRetained) {
            rollbackAttempts.keySet().removeIf(key -> key.operationId().equals(operationId));
        }
    }

    private static void requireTick(long value) {
        if (value < 0L) throw new IllegalArgumentException("clientTick cannot be negative");
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    public enum State {
        ARMED,
        DEPENDENT,
        INDEPENDENT
    }

    public enum Action {
        ALLOW,
        BLOCK
    }

    public enum Removal {
        UNKNOWN,
        SAFE,
        PLACEMENT_ROLLBACK,
        PREMATURE
    }

    public record Coordinate(String dimension, int x, int y, int z) {
        public Coordinate {
            dimension = requireText(dimension, "dimension");
        }

        @Override
        public String toString() {
            return dimension + ' ' + x + ' ' + y + ' ' + z;
        }
    }

    public record PlacementResult(boolean accepted, String detail) {
        public PlacementResult {
            detail = normalize(detail);
        }
    }

    public record BodyResult(
            List<Coordinate> becameDependent,
            List<Coordinate> becameIndependent,
            Snapshot snapshot) {
        public BodyResult {
            becameDependent = List.copyOf(becameDependent);
            becameIndependent = List.copyOf(becameIndependent);
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public record BreakDecision(Action action, Coordinate coordinate, String detail) {
        public BreakDecision {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(coordinate, "coordinate");
            detail = normalize(detail);
        }
    }

    public record RemovalResult(
            Removal removal,
            Coordinate coordinate,
            Coordinate catchCoordinate,
            String operationId,
            int recoveryAttempt,
            int maximumRecoveryAttempts,
            String detail) {
        public RemovalResult {
            Objects.requireNonNull(removal, "removal");
            Objects.requireNonNull(coordinate, "coordinate");
            operationId = normalize(operationId);
            if (recoveryAttempt < 0) {
                throw new IllegalArgumentException("recoveryAttempt cannot be negative");
            }
            if (maximumRecoveryAttempts < 0) {
                throw new IllegalArgumentException("maximumRecoveryAttempts cannot be negative");
            }
            detail = normalize(detail);
        }

        private static RemovalResult unknown(Coordinate coordinate, String detail) {
            return new RemovalResult(
                    Removal.UNKNOWN, coordinate, null, "", 0, MAX_PLACEMENT_ROLLBACKS, detail);
        }

        private static RemovalResult safe(
                Coordinate coordinate, String operationId, String detail) {
            return new RemovalResult(
                    Removal.SAFE, coordinate, null, operationId,
                    0, MAX_PLACEMENT_ROLLBACKS, detail);
        }

        private static RemovalResult placementRollback(
                Coordinate coordinate,
                Coordinate catchCoordinate,
                String operationId,
                int recoveryAttempt,
                int maximumRecoveryAttempts,
                String detail) {
            return new RemovalResult(
                    Removal.PLACEMENT_ROLLBACK, coordinate, catchCoordinate, operationId,
                    recoveryAttempt, maximumRecoveryAttempts, detail);
        }

        private static RemovalResult premature(
                Coordinate coordinate,
                Coordinate catchCoordinate,
                String operationId,
                int recoveryAttempt,
                int maximumRecoveryAttempts,
                String detail) {
            return new RemovalResult(
                    Removal.PREMATURE, coordinate, catchCoordinate, operationId,
                    recoveryAttempt, maximumRecoveryAttempts, detail);
        }

        public boolean premature() {
            return removal == Removal.PREMATURE;
        }

        public boolean recoverable() {
            return removal == Removal.PLACEMENT_ROLLBACK;
        }
    }

    public record Snapshot(
            int tracked,
            int protectedCount,
            int dependentCount,
            long placements,
            long dependentContacts,
            double maximumBodyRise,
            long safeReleases,
            long deniedBreaks,
            long placementRollbacks,
            long prematureRemovals,
            long capacityTrips,
            List<String> protectedCoordinates) {
        public Snapshot {
            protectedCoordinates = List.copyOf(protectedCoordinates);
        }
    }

    private static final class Entry {
        private final Coordinate coordinate;
        private final String operationId;
        private final long placedAtTick;
        private final double startBodyY;
        private State state = State.ARMED;
        private double maximumBodyY;

        private Entry(
                Coordinate coordinate,
                String operationId,
                long placedAtTick,
                double startBodyY) {
            this.coordinate = coordinate;
            this.operationId = operationId;
            this.placedAtTick = placedAtTick;
            this.startBodyY = startBodyY;
            this.maximumBodyY = startBodyY;
        }
    }

    private record RollbackKey(String operationId, Coordinate coordinate) {
        private RollbackKey {
            operationId = normalize(operationId);
            Objects.requireNonNull(coordinate, "coordinate");
        }
    }
}
