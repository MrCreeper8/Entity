package dev.entity.core.progress;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Progress-based watchdog. Long jobs are allowed indefinitely while they make
 * measurable progress; only a no-progress window requests a replan.
 */
public final class ProgressMonitor {
    private final long stuckWindowMillis;
    private final double movementThreshold;
    private final double distanceImprovementThreshold;
    private final Map<String, Episode> episodes = new HashMap<>();

    public ProgressMonitor() {
        this(8_000, 0.75, 0.5);
    }

    public ProgressMonitor(long stuckWindowMillis, double movementThreshold, double distanceImprovementThreshold) {
        if (stuckWindowMillis <= 0 || movementThreshold < 0 || distanceImprovementThreshold < 0) {
            throw new IllegalArgumentException("Invalid progress monitor thresholds");
        }
        this.stuckWindowMillis = stuckWindowMillis;
        this.movementThreshold = movementThreshold;
        this.distanceImprovementThreshold = distanceImprovementThreshold;
    }

    public synchronized Result observe(String missionId, Observation observation) {
        Objects.requireNonNull(missionId, "missionId");
        Objects.requireNonNull(observation, "observation");
        Episode episode = episodes.get(missionId);
        if (episode == null) {
            episodes.put(missionId, new Episode(observation));
            return new Result(Status.INITIALIZED, 0, "progress baseline established");
        }

        boolean spatialFrontierAdvanced = episode.observeSpatialFrontier(
                observation.position(), movementThreshold);
        boolean goalAdvanced = episode.observeGoalHighWater(
                observation.distanceRemaining(), distanceImprovementThreshold);
        boolean workAdvanced = episode.observeWorkHighWater(observation.completedWorkUnits());
        if (spatialFrontierAdvanced || goalAdvanced || workAdvanced) {
            // Commit every observed high-water at the same epoch boundary. This prevents a real
            // work/goal event from laundering already-seen sub-threshold motion into a second
            // progress event on the next sample.
            episode.commitProgress(observation.nowMillis());
            return new Result(Status.PROGRESS, 0, "mission made measurable progress");
        }

        long idleMillis = Math.max(0, observation.nowMillis() - episode.lastProgressAtMillis);
        if (idleMillis >= stuckWindowMillis) {
            // Core normally clears this episode while scheduling the replan. Retain its spatial
            // and semantic high-waters until then so another poll cannot re-credit A<->B motion.
            episode.lastProgressAtMillis = observation.nowMillis();
            return new Result(Status.STUCK, idleMillis, "no meaningful movement, distance gain, or completed work");
        }
        return new Result(Status.NO_CHANGE, idleMillis, "within no-progress grace window");
    }

    public synchronized void clear(String missionId) {
        episodes.remove(missionId);
    }

    public synchronized void clearAll() {
        episodes.clear();
    }

    public enum Status {
        INITIALIZED,
        PROGRESS,
        NO_CHANGE,
        STUCK
    }

    public record Result(Status status, long noProgressMillis, String reason) {
    }

    public record Position(double x, double y, double z) {
        public double distanceTo(Position other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public record Observation(
            long nowMillis,
            Position position,
            double distanceRemaining,
            long completedWorkUnits) {
        public Observation {
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * Mission-local high-water state. The observed envelope remembers sub-threshold samples; the
     * credited envelope moves only when some real progress starts a new no-progress epoch.
     */
    private static final class Episode {
        private long lastProgressAtMillis;
        private final SpatialEnvelope observedSpatial;
        private final SpatialEnvelope creditedSpatial;
        private double observedBestGoalDistance;
        private double creditedBestGoalDistance;
        private long observedCompletedWorkUnits;
        private long creditedCompletedWorkUnits;

        private Episode(Observation observation) {
            lastProgressAtMillis = observation.nowMillis();
            observedSpatial = new SpatialEnvelope(observation.position());
            creditedSpatial = new SpatialEnvelope(observation.position());
            observedBestGoalDistance = finiteGoal(observation.distanceRemaining());
            creditedBestGoalDistance = observedBestGoalDistance;
            observedCompletedWorkUnits = observation.completedWorkUnits();
            creditedCompletedWorkUnits = observedCompletedWorkUnits;
        }

        private boolean observeSpatialFrontier(Position position, double threshold) {
            double frontierDistance = creditedSpatial.distanceOutside(position);
            observedSpatial.include(position);
            return frontierDistance >= threshold;
        }

        private boolean observeGoalHighWater(double distance, double threshold) {
            double finiteDistance = finiteGoal(distance);
            if (finiteDistance < observedBestGoalDistance) {
                observedBestGoalDistance = finiteDistance;
            }
            if (!Double.isFinite(creditedBestGoalDistance)) {
                // The first finite estimate is a baseline, not evidence that the goal moved.
                creditedBestGoalDistance = observedBestGoalDistance;
                return false;
            }
            return observedBestGoalDistance <= creditedBestGoalDistance - threshold;
        }

        private boolean observeWorkHighWater(long completedWorkUnits) {
            if (completedWorkUnits > observedCompletedWorkUnits) {
                observedCompletedWorkUnits = completedWorkUnits;
            }
            return observedCompletedWorkUnits > creditedCompletedWorkUnits;
        }

        private void commitProgress(long nowMillis) {
            lastProgressAtMillis = nowMillis;
            creditedSpatial.copyFrom(observedSpatial);
            creditedBestGoalDistance = observedBestGoalDistance;
            creditedCompletedWorkUnits = observedCompletedWorkUnits;
        }
    }

    private static final class SpatialEnvelope {
        private double minimumX;
        private double maximumX;
        private double minimumY;
        private double maximumY;
        private double minimumZ;
        private double maximumZ;

        private SpatialEnvelope(Position position) {
            minimumX = maximumX = position.x();
            minimumY = maximumY = position.y();
            minimumZ = maximumZ = position.z();
        }

        private void include(Position position) {
            minimumX = Math.min(minimumX, position.x());
            maximumX = Math.max(maximumX, position.x());
            minimumY = Math.min(minimumY, position.y());
            maximumY = Math.max(maximumY, position.y());
            minimumZ = Math.min(minimumZ, position.z());
            maximumZ = Math.max(maximumZ, position.z());
        }

        private double distanceOutside(Position position) {
            double dx = outsideDistance(position.x(), minimumX, maximumX);
            double dy = outsideDistance(position.y(), minimumY, maximumY);
            double dz = outsideDistance(position.z(), minimumZ, maximumZ);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private void copyFrom(SpatialEnvelope source) {
            minimumX = source.minimumX;
            maximumX = source.maximumX;
            minimumY = source.minimumY;
            maximumY = source.maximumY;
            minimumZ = source.minimumZ;
            maximumZ = source.maximumZ;
        }

        private static double outsideDistance(double value, double minimum, double maximum) {
            if (value < minimum) return minimum - value;
            if (value > maximum) return value - maximum;
            return 0.0;
        }
    }

    private static double finiteGoal(double distance) {
        return Double.isFinite(distance) && distance >= 0.0
                ? distance
                : Double.POSITIVE_INFINITY;
    }
}
