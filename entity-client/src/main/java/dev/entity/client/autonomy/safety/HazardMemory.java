package dev.entity.client.autonomy.safety;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Small, pure memory of recently observed dangerous world cells.
 *
 * <p>Every key includes its dimension, every observation expires, and the
 * total entry count has a hard bound.  The class deliberately contains no
 * Minecraft types so collection, death recovery, navigation and survival can
 * share one deterministic policy without loading a client in their tests.</p>
 */
public final class HazardMemory {
    public static final int DEFAULT_MAXIMUM_ENTRIES = 2_048;
    public static final long DEFAULT_MAXIMUM_TTL_MILLIS = 5 * 60_000L;

    private static final Comparator<Entry> EVICTION_ORDER = Comparator
            .comparingLong(Entry::expiresAtMillis)
            .thenComparingLong(Entry::lastObservedAtMillis)
            .thenComparing(entry -> entry.key().dimension())
            .thenComparingInt(entry -> entry.key().cell().x())
            .thenComparingInt(entry -> entry.key().cell().y())
            .thenComparingInt(entry -> entry.key().cell().z())
            .thenComparing(entry -> entry.key().kind());

    private static final Comparator<Entry> QUERY_ORDER = Comparator
            .comparing(Entry::severity).reversed()
            .thenComparing(Comparator.comparingLong(Entry::lastObservedAtMillis).reversed())
            .thenComparing(entry -> entry.key().kind())
            .thenComparingInt(entry -> entry.key().cell().x())
            .thenComparingInt(entry -> entry.key().cell().y())
            .thenComparingInt(entry -> entry.key().cell().z());

    private final int maximumEntries;
    private final long maximumTtlMillis;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();

    public HazardMemory() {
        this(DEFAULT_MAXIMUM_ENTRIES, DEFAULT_MAXIMUM_TTL_MILLIS);
    }

    public HazardMemory(int maximumEntries, long maximumTtlMillis) {
        if (maximumEntries <= 0) throw new IllegalArgumentException("maximumEntries must be positive");
        if (maximumTtlMillis <= 0L) {
            throw new IllegalArgumentException("maximumTtlMillis must be positive");
        }
        this.maximumEntries = maximumEntries;
        this.maximumTtlMillis = maximumTtlMillis;
    }

    /**
     * Records or refreshes one hazard.  A caller cannot create an unbounded
     * lifetime: requested TTLs are clamped to this memory's configured cap.
     */
    public synchronized Entry remember(
            String dimension,
            Cell cell,
            Kind kind,
            Severity severity,
            long nowMillis,
            long requestedTtlMillis) {
        if (nowMillis < 0L) throw new IllegalArgumentException("nowMillis cannot be negative");
        if (requestedTtlMillis <= 0L) {
            throw new IllegalArgumentException("requestedTtlMillis must be positive");
        }
        prune(nowMillis);
        Key key = new Key(dimension, cell, kind);
        long ttl = Math.min(requestedTtlMillis, maximumTtlMillis);
        long expiresAt = saturatedAdd(nowMillis, ttl);
        Entry previous = entries.remove(key);
        Entry replacement = previous == null
                ? new Entry(key, severity, nowMillis, nowMillis, expiresAt, 1)
                : new Entry(
                        key,
                        strongest(previous.severity(), severity),
                        previous.firstObservedAtMillis(),
                        nowMillis,
                        Math.max(previous.expiresAtMillis(), expiresAt),
                        Math.addExact(previous.observationCount(), 1));
        entries.put(key, replacement);
        while (entries.size() > maximumEntries) evictOne();
        return replacement;
    }

    public synchronized boolean contains(
            String dimension,
            Cell cell,
            Kind kind,
            long nowMillis) {
        prune(nowMillis);
        return entries.containsKey(new Key(dimension, cell, kind));
    }

    /** Returns the strongest active hazard at exactly one dimension/cell. */
    public synchronized Optional<Entry> strongestAt(
            String dimension,
            Cell cell,
            long nowMillis) {
        String canonicalDimension = canonicalDimension(dimension);
        Cell requiredCell = Objects.requireNonNull(cell, "cell");
        prune(nowMillis);
        return entries.values().stream()
                .filter(entry -> entry.key().dimension().equals(canonicalDimension))
                .filter(entry -> entry.key().cell().equals(requiredCell))
                .sorted(QUERY_ORDER)
                .findFirst();
    }

    /**
     * Returns active hazards inside a Chebyshev cube, strongest first.  This
     * matches block-neighbour safety checks and is deterministic for telemetry
     * and tests.
     */
    public synchronized List<Entry> near(
            String dimension,
            Cell centre,
            int radius,
            long nowMillis) {
        if (radius < 0) throw new IllegalArgumentException("radius cannot be negative");
        String canonicalDimension = canonicalDimension(dimension);
        Cell requiredCentre = Objects.requireNonNull(centre, "centre");
        prune(nowMillis);
        return entries.values().stream()
                .filter(entry -> entry.key().dimension().equals(canonicalDimension))
                .filter(entry -> entry.key().cell().chebyshevDistanceTo(requiredCentre) <= radius)
                .sorted(QUERY_ORDER)
                .toList();
    }

    public synchronized List<Entry> snapshot(String dimension, long nowMillis) {
        String canonicalDimension = canonicalDimension(dimension);
        prune(nowMillis);
        return entries.values().stream()
                .filter(entry -> entry.key().dimension().equals(canonicalDimension))
                .sorted(QUERY_ORDER)
                .toList();
    }

    public synchronized int size(long nowMillis) {
        prune(nowMillis);
        return entries.size();
    }

    public synchronized void clearDimension(String dimension) {
        String canonicalDimension = canonicalDimension(dimension);
        entries.entrySet().removeIf(entry -> entry.getKey().dimension().equals(canonicalDimension));
    }

    public synchronized void clear() {
        entries.clear();
    }

    public int maximumEntries() {
        return maximumEntries;
    }

    public long maximumTtlMillis() {
        return maximumTtlMillis;
    }

    private void prune(long nowMillis) {
        if (nowMillis < 0L) throw new IllegalArgumentException("nowMillis cannot be negative");
        entries.entrySet().removeIf(entry -> !entry.getValue().activeAt(nowMillis));
    }

    private void evictOne() {
        Entry victim = entries.values().stream().min(EVICTION_ORDER).orElseThrow();
        entries.remove(victim.key());
    }

    private static Severity strongest(Severity first, Severity second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static String canonicalDimension(String raw) {
        Objects.requireNonNull(raw, "dimension");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("dimension cannot be blank");
        return normalized;
    }

    public enum Kind {
        LAVA,
        FIRE,
        UNSUPPORTED_FLOOR,
        VOID,
        HOSTILE,
        TRAVERSAL_FAILURE,
        OTHER
    }

    public enum Severity {
        CAUTION,
        DANGEROUS,
        LETHAL
    }

    public record Cell(int x, int y, int z) {
        public long chebyshevDistanceTo(Cell other) {
            Objects.requireNonNull(other, "other");
            long dx = Math.abs((long) x - other.x);
            long dy = Math.abs((long) y - other.y);
            long dz = Math.abs((long) z - other.z);
            return Math.max(dx, Math.max(dy, dz));
        }
    }

    public record Key(String dimension, Cell cell, Kind kind) {
        public Key {
            dimension = canonicalDimension(dimension);
            cell = Objects.requireNonNull(cell, "cell");
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    public record Entry(
            Key key,
            Severity severity,
            long firstObservedAtMillis,
            long lastObservedAtMillis,
            long expiresAtMillis,
            int observationCount) {
        public Entry {
            key = Objects.requireNonNull(key, "key");
            severity = Objects.requireNonNull(severity, "severity");
            if (firstObservedAtMillis < 0L || lastObservedAtMillis < firstObservedAtMillis) {
                throw new IllegalArgumentException("hazard observation timestamps are invalid");
            }
            if (expiresAtMillis <= lastObservedAtMillis) {
                throw new IllegalArgumentException("hazard expiration must follow its observation");
            }
            if (observationCount <= 0) {
                throw new IllegalArgumentException("observationCount must be positive");
            }
        }

        public boolean activeAt(long nowMillis) {
            return nowMillis >= 0L && nowMillis < expiresAtMillis;
        }
    }
}
