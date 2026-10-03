package dev.entity.client.baritone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Per-native-owner, immutable feedback for an actually failed cardinal ascent. */
public final class NativeFailedAscendEdges {
    public static final int MAX_ENTRIES = 8;
    public static final long TTL_MILLIS = 120_000L;
    private static final Pattern ROUTE = Pattern.compile(
            "^(?:db|MovementAscend):[0-9]+:(-?[0-9]+),(-?[0-9]+),(-?[0-9]+)->(-?[0-9]+),(-?[0-9]+),(-?[0-9]+)$");

    /** Implemented on the exact native Baritone instance, never a global blacklist. */
    public interface Owner {
        NativeFailedAscendEdges entity2$failedAscendEdges();
    }

    public record Scope(String operationId, long controlEpoch, String worldId, String dimension) {
        public Scope {
            requireText(operationId, "operation");
            requireText(worldId, "world");
            requireText(dimension, "dimension");
            if (controlEpoch < 0) throw new IllegalArgumentException("Negative control epoch");
        }
    }

    /** Executor index is intentionally absent: db:7 and db:0 name the same edge. */
    public record Edge(int sourceX, int sourceY, int sourceZ,
                       int destinationX, int destinationY, int destinationZ) {
        public Edge {
            long dx = (long) destinationX - sourceX, dz = (long) destinationZ - sourceZ;
            if ((long) destinationY - sourceY != 1 || Math.abs(dx) + Math.abs(dz) != 1)
                throw new IllegalArgumentException("Not a cardinal one-level ascent");
        }

        /** Only the complete existing movement signature is accepted, never embedded text. */
        public static Edge fromRouteSignature(String signature) {
            if (signature == null) return null;
            var match = ROUTE.matcher(signature);
            if (!match.matches()) return null;
            try {
                return new Edge(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                        Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)),
                        Integer.parseInt(match.group(5)), Integer.parseInt(match.group(6)));
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
    }

    /** Captured by the client owner only after persistent collision, not predicted failure. */
    public record Failure(Edge edge, String geometryFingerprint, long observedAtMillis) {
        public Failure {
            Objects.requireNonNull(edge, "edge");
            requireText(geometryFingerprint, "geometry fingerprint");
            if (observedAtMillis < 0) throw new IllegalArgumentException("Negative observation time");
        }
        boolean live(long nowMillis) {
            return nowMillis >= observedAtMillis && nowMillis - observedAtMillis < TTL_MILLIS;
        }
    }

    public record Snapshot(Scope scope, List<Failure> failures) {
        public Snapshot { failures = List.copyOf(Objects.requireNonNull(failures, "failures")); }
    }

    private final Object nativeOwner;
    private volatile Snapshot snapshot = new Snapshot(null, List.of());

    public NativeFailedAscendEdges(Object nativeOwner) {
        this.nativeOwner = Objects.requireNonNull(nativeOwner, "native owner");
    }

    /**
     * Caller validates the active operation/lease/world and removes changed or unavailable
     * geometry before publication. Repeated polls preserve observedAt, never renew the TTL.
     * No mutable world, arbiter or input state is read on native calculation threads.
     */
    public synchronized Snapshot publish(Scope scope, List<Failure> observed, long nowMillis) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(observed, "observed failures");
        if (nowMillis < 0) throw new IllegalArgumentException("Negative publication time");
        var ordered = new ArrayList<>(observed);
        ordered.forEach(failure -> Objects.requireNonNull(failure, "failure"));
        ordered.sort(Comparator.comparingLong(Failure::observedAtMillis).reversed());
        var exact = new LinkedHashMap<Edge, Failure>();
        for (Failure failure : ordered) {
            if (!failure.live(nowMillis) || exact.containsKey(failure.edge())) continue;
            exact.put(failure.edge(), failure);
            if (exact.size() == MAX_ENTRIES) break;
        }
        Snapshot next = new Snapshot(scope, List.copyOf(exact.values()));
        if (!next.equals(snapshot)) snapshot = next;
        return snapshot;
    }

    /** Optional client-thread pruning; missing/unloaded geometry is not a retained fact. */
    public synchronized Snapshot invalidateGeometry(Scope scope, Map<Edge, String> current, long nowMillis) {
        Objects.requireNonNull(current, "current geometry");
        Snapshot before = snapshot;
        List<Failure> retained = Objects.equals(scope, before.scope()) ? before.failures().stream()
                .filter(failure -> failure.geometryFingerprint().equals(current.get(failure.edge())))
                .toList() : List.of();
        return publish(scope, retained, nowMillis);
    }

    /** One immutable volatile read; no block, world, property or movement-family bans. */
    public boolean forbidden(Object candidateOwner, int x, int y, int z,
                             int destinationX, int destinationZ, long nowMillis) {
        if (candidateOwner != nativeOwner) return false;
        Snapshot current = snapshot;
        if (current.scope() == null || nowMillis < 0) return false;
        for (Failure failure : current.failures()) {
            Edge edge = failure.edge();
            if (failure.live(nowMillis) && edge.sourceX() == x && edge.sourceY() == y
                    && edge.sourceZ() == z && edge.destinationX() == destinationX
                    && edge.destinationZ() == destinationZ) return true;
        }
        return false;
    }

    public Snapshot snapshot() { return snapshot; }

    /** Stop, preemption, ended lease and world change must clear this owning instance. */
    public synchronized void clear() { snapshot = new Snapshot(null, List.of()); }

    private static void requireText(String text, String label) {
        if (text == null || text.isBlank() || text.length() > 4096)
            throw new IllegalArgumentException("Missing or excessive " + label);
    }
}
