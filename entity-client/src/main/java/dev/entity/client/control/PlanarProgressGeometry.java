package dev.entity.client.control;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded horizontal trail used to distinguish new movement geometry from
 * backtracking over an already-traversed escape corridor.
 *
 * <p>A point advances the trail only after it is far enough from the latest
 * accepted checkpoint, is not a previously accepted point, and the segment to
 * it does not cut back through an older checkpoint. This admits useful turns
 * around terrain while preventing A/B movement loops from continually renewing
 * a progress deadline.</p>
 */
public final class PlanarProgressGeometry {
    private static final int DEFAULT_MAXIMUM_CHECKPOINTS = 64;
    private static final double REVISIT_CORRIDOR_FRACTION = 0.50;
    private static final double EPSILON = 1.0e-9;

    private final int maximumCheckpoints;
    private final Deque<Point> accepted = new ArrayDeque<>();
    private Point checkpoint;
    private Point lastObservation;

    public PlanarProgressGeometry(double originX, double originZ) {
        this(originX, originZ, DEFAULT_MAXIMUM_CHECKPOINTS);
    }

    PlanarProgressGeometry(double originX, double originZ, int maximumCheckpoints) {
        if (!Double.isFinite(originX) || !Double.isFinite(originZ)) {
            throw new IllegalArgumentException("progress origin must be finite");
        }
        if (maximumCheckpoints < 3) {
            throw new IllegalArgumentException("progress trail must retain at least three checkpoints");
        }
        this.maximumCheckpoints = maximumCheckpoints;
        checkpoint = new Point(originX, originZ);
        lastObservation = checkpoint;
        accepted.addLast(checkpoint);
    }

    /** Returns horizontal distance from the most recent accepted checkpoint. */
    public double displacementFromCheckpoint(double x, double z) {
        return Math.hypot(x - checkpoint.x, z - checkpoint.z);
    }

    /**
     * Accepts a sufficiently large movement only when it enters new trail
     * geometry. Rejected observations leave the checkpoint unchanged.
     */
    public boolean advanceIfNovel(double x, double z, double minimumProgress) {
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(minimumProgress) || minimumProgress <= 0.0) {
            return false;
        }
        Point candidate = new Point(x, z);
        Point observedSegmentStart = lastObservation;
        lastObservation = candidate;
        if (distance(checkpoint, candidate) + EPSILON < minimumProgress) {
            return false;
        }

        Point latest = accepted.peekLast();
        double revisitCorridor = minimumProgress * REVISIT_CORRIDOR_FRACTION;
        for (Point older : accepted) {
            if (older == latest) continue;
            if (distance(older, candidate) + EPSILON < minimumProgress
                    || distanceToSegment(older, observedSegmentStart, candidate) + EPSILON
                            < revisitCorridor) {
                return false;
            }
        }

        checkpoint = candidate;
        accepted.addLast(candidate);
        while (accepted.size() > maximumCheckpoints) {
            accepted.removeFirst();
        }
        return true;
    }

    int retainedCheckpoints() {
        return accepted.size();
    }

    private static double distance(Point first, Point second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }

    private static double distanceToSegment(Point point, Point start, Point end) {
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared <= EPSILON) return distance(point, start);
        double projection = ((point.x - start.x) * dx + (point.z - start.z) * dz)
                / lengthSquared;
        double bounded = Math.max(0.0, Math.min(1.0, projection));
        return Math.hypot(
                point.x - (start.x + bounded * dx),
                point.z - (start.z + bounded * dz));
    }

    private record Point(double x, double z) {
    }
}
