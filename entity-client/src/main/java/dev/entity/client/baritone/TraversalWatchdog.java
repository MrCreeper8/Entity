package dev.entity.client.baritone;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Operation-level traversal watchdog.
 *
 * <p>Baritone may replace its executor, briefly expose no executor while installing a new path,
 * or alternate between two edge descriptions while the player's body remains in the same hole.
 * None of those bookkeeping changes is progress. Progress means that the body actually left its
 * local area, settled on a different level, got closer to the goal, or completed useful work.</p>
 */
final class TraversalWatchdog {
    static final double HORIZONTAL_PROGRESS = 0.70;
    static final double SETTLED_VERTICAL_PROGRESS = 0.75;
    static final double GOAL_PROGRESS = 1.0;

    private final long stallMillis;
    private final long initialCalculationMillis;
    private boolean armed;
    private HorizontalEnvelope observedMotionEnvelope;
    private HorizontalEnvelope creditedMotionEnvelope;
    private GroundedEnvelope observedGroundedEnvelope;
    private GroundedEnvelope creditedGroundedEnvelope;
    private double observedBestGoalDistance = Double.POSITIVE_INFINITY;
    private double creditedBestGoalDistance = Double.POSITIVE_INFINITY;
    private long lastAdvanceAt;
    private String lastRouteSignature = "";
    private int routeChangesWithoutProgress;
    private boolean executorObserved;

    TraversalWatchdog(long stallMillis) {
        this(stallMillis, stallMillis);
    }

    TraversalWatchdog(long stallMillis, long initialCalculationMillis) {
        if (stallMillis <= 0 || initialCalculationMillis <= 0) {
            throw new IllegalArgumentException("watchdog durations must be positive");
        }
        this.stallMillis = stallMillis;
        this.initialCalculationMillis = initialCalculationMillis;
    }

    Result observe(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        String signature = Objects.requireNonNullElse(observation.routeSignature(), "");
        if (!observation.expected() || observation.position() == null) {
            reset();
            return new Result(State.INACTIVE, 0, "", 0);
        }

        if (!armed) {
            arm(observation, signature);
            return new Result(State.ADVANCED, 0, lastRouteSignature, 0);
        }

        rememberRoute(signature);

        boolean bodyAdvanced = observeBodyFrontier(
                observation.position(), observation.grounded());
        boolean goalAdvanced = observeGoalHighWater(observation.goalDistance());
        if (observation.productiveWork() || bodyAdvanced || goalAdvanced) {
            lastAdvanceAt = observation.nowMillis();
            routeChangesWithoutProgress = 0;
            // Verified progress begins a new epoch. If Baritone next has to perform another
            // genuinely primary, executor-free calculation, it may spend the bounded long
            // calculation budget once again. Bookkeeping alone never reaches this branch.
            executorObserved = false;
            commitProgressHighWaters();
            return new Result(State.ADVANCED, 0, lastRouteSignature, 0);
        }

        // A brand-new primary calculation is intentionally motionless and can be materially more
        // expensive than traversing one edge. The retained production-mine trace needed 70.8s to
        // produce its first executable route, so it receives one bounded operation-level budget.
        // The first real executor consumes that privilege and starts the ordinary physical stall
        // clock. Later calculation gaps share that same clock and therefore cannot renew a stuck
        // executor forever.
        if (observation.executorPresent() && !executorObserved) {
            executorObserved = true;
            lastAdvanceAt = observation.nowMillis();
        }

        long stalledFor = Math.max(0, observation.nowMillis() - lastAdvanceAt);
        if (observation.calculating()) {
            long limit = executorObserved ? stallMillis : initialCalculationMillis;
            return stalledFor >= limit
                    ? new Result(State.STALLED, stalledFor, lastRouteSignature,
                    routeChangesWithoutProgress)
                    : new Result(State.CALCULATING, stalledFor, lastRouteSignature,
                    routeChangesWithoutProgress);
        }
        return stalledFor >= stallMillis
                ? new Result(State.STALLED, stalledFor, lastRouteSignature,
                routeChangesWithoutProgress)
                : new Result(State.MONITORING, stalledFor, lastRouteSignature,
                routeChangesWithoutProgress);
    }

    void reset() {
        armed = false;
        observedMotionEnvelope = null;
        creditedMotionEnvelope = null;
        observedGroundedEnvelope = null;
        creditedGroundedEnvelope = null;
        observedBestGoalDistance = Double.POSITIVE_INFINITY;
        creditedBestGoalDistance = Double.POSITIVE_INFINITY;
        lastAdvanceAt = 0;
        lastRouteSignature = "";
        routeChangesWithoutProgress = 0;
        executorObserved = false;
    }

    private void arm(Observation observation, String signature) {
        armed = true;
        observedMotionEnvelope = new HorizontalEnvelope(observation.position());
        creditedMotionEnvelope = new HorizontalEnvelope(observation.position());
        observedGroundedEnvelope = observation.grounded()
                ? new GroundedEnvelope(observation.position().y())
                : null;
        creditedGroundedEnvelope = observation.grounded()
                ? new GroundedEnvelope(observation.position().y())
                : null;
        observedBestGoalDistance = finiteGoal(observation.goalDistance());
        creditedBestGoalDistance = observedBestGoalDistance;
        lastAdvanceAt = observation.nowMillis();
        lastRouteSignature = signature;
        routeChangesWithoutProgress = 0;
        executorObserved = observation.executorPresent();
    }

    private void rememberRoute(String signature) {
        if (signature.isBlank() || signature.equals(lastRouteSignature)) return;
        lastRouteSignature = signature;
        routeChangesWithoutProgress++;
    }

    private boolean observeBodyFrontier(Position position, boolean grounded) {
        if (observedMotionEnvelope == null || creditedMotionEnvelope == null) return true;
        boolean horizontalAdvanced = creditedMotionEnvelope.distanceOutside(position)
                >= HORIZONTAL_PROGRESS;
        observedMotionEnvelope.include(position);

        boolean verticalAdvanced = false;
        if (grounded) {
            if (observedGroundedEnvelope == null || creditedGroundedEnvelope == null) {
                // The first supporting level learned after an airborne arm is a baseline, not a
                // successful climb. It will be committed with the next real progress event.
                observedGroundedEnvelope = new GroundedEnvelope(position.y());
                creditedGroundedEnvelope = new GroundedEnvelope(position.y());
            } else {
                verticalAdvanced = creditedGroundedEnvelope.distanceOutside(position.y())
                        >= SETTLED_VERTICAL_PROGRESS;
                observedGroundedEnvelope.include(position.y());
            }
        }
        return horizontalAdvanced || verticalAdvanced;
    }

    private boolean observeGoalHighWater(double distance) {
        double goal = finiteGoal(distance);
        if (goal < observedBestGoalDistance) observedBestGoalDistance = goal;
        if (!Double.isFinite(creditedBestGoalDistance)) {
            // Learning the first finite estimate after a primary calculation is a baseline, not
            // evidence that the body or goal moved.
            creditedBestGoalDistance = observedBestGoalDistance;
            return false;
        }
        return observedBestGoalDistance <= creditedBestGoalDistance - GOAL_PROGRESS;
    }

    private void commitProgressHighWaters() {
        creditedMotionEnvelope.copyFrom(observedMotionEnvelope);
        if (observedGroundedEnvelope != null) {
            if (creditedGroundedEnvelope == null) {
                creditedGroundedEnvelope = observedGroundedEnvelope.copy();
            } else {
                creditedGroundedEnvelope.copyFrom(observedGroundedEnvelope);
            }
        }
        creditedBestGoalDistance = observedBestGoalDistance;
    }

    private static double finiteGoal(double distance) {
        return Double.isFinite(distance) && distance >= 0
                ? distance
                : Double.POSITIVE_INFINITY;
    }

    /** Episode X/Z bounds: already-seen positions can never renew the watchdog again. */
    private static final class HorizontalEnvelope {
        private double minimumX;
        private double maximumX;
        private double minimumZ;
        private double maximumZ;

        private HorizontalEnvelope(Position position) {
            minimumX = maximumX = position.x();
            minimumZ = maximumZ = position.z();
        }

        private void include(Position position) {
            minimumX = Math.min(minimumX, position.x());
            maximumX = Math.max(maximumX, position.x());
            minimumZ = Math.min(minimumZ, position.z());
            maximumZ = Math.max(maximumZ, position.z());
        }

        private double distanceOutside(Position position) {
            double dx = outsideDistance(position.x(), minimumX, maximumX);
            double dz = outsideDistance(position.z(), minimumZ, maximumZ);
            return Math.sqrt(dx * dx + dz * dz);
        }

        private void copyFrom(HorizontalEnvelope source) {
            minimumX = source.minimumX;
            maximumX = source.maximumX;
            minimumZ = source.minimumZ;
            maximumZ = source.maximumZ;
        }
    }

    /** Only supporting-level samples enter the vertical episode envelope. */
    private static final class GroundedEnvelope {
        private double minimumY;
        private double maximumY;

        private GroundedEnvelope(double y) {
            minimumY = maximumY = y;
        }

        private void include(double y) {
            minimumY = Math.min(minimumY, y);
            maximumY = Math.max(maximumY, y);
        }

        private double distanceOutside(double y) {
            return outsideDistance(y, minimumY, maximumY);
        }

        private GroundedEnvelope copy() {
            GroundedEnvelope copy = new GroundedEnvelope(minimumY);
            copy.maximumY = maximumY;
            return copy;
        }

        private void copyFrom(GroundedEnvelope source) {
            minimumY = source.minimumY;
            maximumY = source.maximumY;
        }
    }

    private static double outsideDistance(double value, double minimum, double maximum) {
        if (value < minimum) return minimum - value;
        if (value > maximum) return value - maximum;
        return 0.0;
    }

    enum State {
        INACTIVE,
        CALCULATING,
        ADVANCED,
        MONITORING,
        STALLED
    }

    record Position(double x, double y, double z) {
    }

    record Observation(
            String routeSignature,
            boolean expected,
            boolean calculating,
            boolean executorPresent,
            boolean productiveWork,
            boolean grounded,
            Position position,
            double goalDistance,
            long nowMillis) {
    }

    record Result(
            State state,
            long stalledForMillis,
            String failedRouteSignature,
            int routeChangesWithoutProgress) {
    }

    /** Bounded, expiring mission and failed-edge memory shared across operation recreation. */
    static final class RetryBudget {
        private final int maximumFailures;
        private final long windowMillis;
        private final Map<String, ArrayDeque<Failure>> failures = new HashMap<>();

        RetryBudget(int maximumFailures, long windowMillis) {
            if (maximumFailures < 1 || windowMillis <= 0) {
                throw new IllegalArgumentException("invalid traversal retry budget");
            }
            this.maximumFailures = maximumFailures;
            this.windowMillis = windowMillis;
        }

        int recentFailures(String missionId, long nowMillis) {
            ArrayDeque<Failure> history = history(missionId, nowMillis);
            return history == null ? 0 : history.size();
        }

        boolean shouldAvoidParkour(String missionId, long nowMillis) {
            return recentFailures(missionId, nowMillis) > 0;
        }

        RetryPolicy retryPolicy(String missionId, long nowMillis) {
            ArrayDeque<Failure> history = history(missionId, nowMillis);
            if (history == null) return RetryPolicy.MAXIMUM_MOVEMENT;
            boolean avoidDownward = history.stream()
                    .anyMatch(failure -> failure.movementClass() == FailedMovementClass.DOWNWARD);
            boolean avoidDiagonalDescend = history.stream()
                    .anyMatch(failure -> failure.movementClass()
                            == FailedMovementClass.DIAGONAL_DESCEND);
            boolean avoidDiagonalAscend = history.stream()
                    .anyMatch(failure -> failure.movementClass()
                            == FailedMovementClass.DIAGONAL_ASCEND);
            boolean conservativeAscend = history.stream()
                    .anyMatch(failure -> failure.movementClass() == FailedMovementClass.ASCEND
                            || failure.movementClass() == FailedMovementClass.DIAGONAL_ASCEND);
            return new RetryPolicy(
                    true,
                    avoidDownward,
                    avoidDiagonalDescend,
                    avoidDownward || avoidDiagonalDescend,
                    avoidDiagonalAscend,
                    conservativeAscend);
        }

        boolean hasFailedRoute(String missionId, String routeSignature, long nowMillis) {
            String route = normalizeRoute(routeSignature);
            if (route.isBlank()) return false;
            ArrayDeque<Failure> history = history(missionId, nowMillis);
            if (history == null) return false;
            return history.stream().anyMatch(failure -> failure.routeSignature().equals(route));
        }

        Attempt recordFailure(String missionId, String routeSignature, long nowMillis) {
            Objects.requireNonNull(missionId, "missionId");
            ArrayDeque<Failure> history = failures.computeIfAbsent(missionId,
                    ignored -> new ArrayDeque<>());
            prune(history, nowMillis);
            String route = normalizeRoute(routeSignature);
            FailedMovementClass movementClass = classifyFailedMovement(route);
            history.addLast(new Failure(nowMillis, route, movementClass));
            int count = history.size();
            long sameRouteFailures = route.isBlank() ? 0 : history.stream()
                    .filter(failure -> failure.routeSignature().equals(route))
                    .count();
            return new Attempt(count, maximumFailures, count >= maximumFailures,
                    (int) sameRouteFailures, route, movementClass);
        }

        void clear(String missionId) {
            failures.remove(missionId);
        }

        private ArrayDeque<Failure> history(String missionId, long nowMillis) {
            ArrayDeque<Failure> history = failures.get(missionId);
            if (history == null) return null;
            prune(history, nowMillis);
            if (history.isEmpty()) {
                failures.remove(missionId);
                return null;
            }
            return history;
        }

        private void prune(ArrayDeque<Failure> history, long nowMillis) {
            long cutoff = nowMillis - windowMillis;
            while (!history.isEmpty() && history.peekFirst().atMillis() < cutoff) {
                history.removeFirst();
            }
        }

        private static String normalizeRoute(String routeSignature) {
            return Objects.requireNonNullElse(routeSignature, "").trim();
        }

        private static FailedMovementClass classifyFailedMovement(String routeSignature) {
            int coordinateSeparator = routeSignature.lastIndexOf(':');
            if (coordinateSeparator < 0 || coordinateSeparator + 1 >= routeSignature.length()) {
                return FailedMovementClass.OTHER;
            }
            String[] endpoints = routeSignature.substring(coordinateSeparator + 1)
                    .split("->", -1);
            if (endpoints.length != 2) return FailedMovementClass.OTHER;
            int[] source = coordinates(endpoints[0]);
            int[] destination = coordinates(endpoints[1]);
            if (source == null || destination == null) {
                return FailedMovementClass.OTHER;
            }
            boolean diagonal = destination[0] != source[0]
                    && destination[2] != source[2];
            if (destination[1] < source[1]) {
                return diagonal
                        ? FailedMovementClass.DIAGONAL_DESCEND
                        : FailedMovementClass.DOWNWARD;
            }
            if (destination[1] > source[1]) {
                return diagonal
                        ? FailedMovementClass.DIAGONAL_ASCEND
                        : FailedMovementClass.ASCEND;
            }
            return FailedMovementClass.OTHER;
        }

        private static int[] coordinates(String encoded) {
            String[] values = encoded.split(",", -1);
            if (values.length != 3) return null;
            try {
                return new int[]{
                        Integer.parseInt(values[0]),
                        Integer.parseInt(values[1]),
                        Integer.parseInt(values[2])};
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private record Failure(
                long atMillis,
                String routeSignature,
                FailedMovementClass movementClass) {
        }
    }

    enum FailedMovementClass {
        OTHER,
        DOWNWARD,
        DIAGONAL_DESCEND,
        ASCEND,
        DIAGONAL_ASCEND
    }

    record RetryPolicy(
            boolean avoidParkour,
            boolean avoidDownward,
            boolean avoidDiagonalDescend,
            boolean avoidOvershootDiagonalDescend,
            boolean avoidDiagonalAscend,
            boolean conservativeAscend) {
        private static final RetryPolicy MAXIMUM_MOVEMENT =
                new RetryPolicy(false, false, false, false, false, false);
    }

    record Attempt(
            int number,
            int maximum,
            boolean exhausted,
            int sameRouteFailures,
            String routeSignature,
            FailedMovementClass movementClass) {
    }
}
