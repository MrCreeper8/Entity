package dev.entity.client.baritone;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure route-lifecycle policy for persistent land-biased hunt scouting. */
final class LandHuntFrontierPolicy {
    static final double REACHED_DISTANCE = 5.0;
    static final double PROGRESS_DISTANCE = 1.25;
    static final long NO_PROGRESS_MILLIS = 15_000;
    static final long INACTIVE_GRACE_MILLIS = 2_500;
    static final long REJECTION_MILLIS = 90_000;

    enum RouteState { CALCULATING, MOVING, INACTIVE }
    enum Action { KEEP, SELECT, REACHED, REJECT }

    record Candidate(String id, int x, int y, int z, double distance,
                     double outwardDistance, double landFraction,
                     double routeWaterFraction, double loadedFraction,
                     double elevationChange) {
        Candidate {
            id = Objects.requireNonNull(id, "id").trim();
            if (id.isEmpty() || !nonNegative(distance) || !nonNegative(outwardDistance)
                    || !fraction(landFraction) || !fraction(routeWaterFraction)
                    || !fraction(loadedFraction) || !nonNegative(elevationChange)) {
                throw new IllegalArgumentException("invalid land frontier");
            }
        }

        private static boolean nonNegative(double value) {
            return Double.isFinite(value) && value >= 0.0;
        }

        private static boolean fraction(double value) {
            return nonNegative(value) && value <= 1.0;
        }
    }

    record Decision(Action action, String reason) { }

    static double score(Candidate candidate, boolean escapingWater) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.landFraction() < 0.60) return Double.POSITIVE_INFINITY;
        if (!escapingWater && candidate.routeWaterFraction() > 0.40) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(candidate.distance() - 56.0) * 0.35
                + candidate.distance() * 0.08
                - Math.min(256.0, candidate.outwardDistance()) * 0.10
                + (1.0 - candidate.landFraction()) * 90.0
                + candidate.routeWaterFraction() * (escapingWater ? 25.0 : 150.0)
                + Math.abs(candidate.loadedFraction() - 0.65) * 14.0
                + candidate.elevationChange() * 1.5;
    }

    static final class Session {
        private static final int MAX_REJECTIONS = 96;
        private final LinkedHashMap<String, Long> rejectedUntil = new LinkedHashMap<>();
        // Arrival means this chunk was searched, not that its route temporarily failed.
        // Retain that knowledge for this finite hunt; otherwise the ninety-second
        // failure cooldown sends scouting back around the same empty Home terrain.
        private final HashSet<String> searched = new HashSet<>();
        private Candidate current;
        private double bestDistance = Double.POSITIVE_INFINITY;
        private long selectedAt;
        private long lastProgressAt;
        private long inactiveSince;

        Optional<Candidate> select(List<Candidate> candidates, boolean escapingWater, long now) {
            rejectedUntil.entrySet().removeIf(entry -> now >= entry.getValue());
            Candidate best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (Candidate candidate : candidates) {
                if (candidate == null || searched.contains(candidate.id())
                        || rejectedUntil.containsKey(candidate.id())) continue;
                double candidateScore = score(candidate, escapingWater);
                if (candidateScore < bestScore || (candidateScore == bestScore && best != null
                        && candidate.id().compareTo(best.id()) < 0)) {
                    best = candidate;
                    bestScore = candidateScore;
                }
            }
            return Optional.ofNullable(best);
        }

        Decision evaluate(double distance, RouteState state, boolean valid, long now) {
            if (current == null) return new Decision(Action.SELECT, "no frontier is pinned");
            if (!valid) return new Decision(Action.REJECT, "frontier became unsafe");
            if (Double.isFinite(distance) && distance <= REACHED_DISTANCE) {
                return new Decision(Action.REACHED, "frontier reached");
            }
            if (state == RouteState.CALCULATING) {
                return new Decision(Action.KEEP, "preserving the in-progress calculation");
            }
            if (state == RouteState.MOVING) {
                inactiveSince = 0;
                if (Double.isFinite(distance) && distance <= bestDistance - PROGRESS_DISTANCE) {
                    bestDistance = distance;
                    lastProgressAt = now;
                }
                if (now - lastProgressAt >= NO_PROGRESS_MILLIS) {
                    return new Decision(Action.REJECT, "route made no net progress for 15 seconds");
                }
                return new Decision(Action.KEEP, "frontier route remains productive");
            }
            if (now - selectedAt < INACTIVE_GRACE_MILLIS) {
                return new Decision(Action.KEEP, "route is inside start grace");
            }
            if (inactiveSince == 0) inactiveSince = now;
            if (now - inactiveSince >= INACTIVE_GRACE_MILLIS) {
                return new Decision(Action.REJECT, "route stayed inactive after bounded grace");
            }
            return new Decision(Action.KEEP, "waiting for route activation");
        }

        void selected(Candidate candidate, double distance, long now) {
            current = Objects.requireNonNull(candidate, "candidate");
            bestDistance = Double.isFinite(distance) ? Math.max(0.0, distance) : Double.POSITIVE_INFINITY;
            selectedAt = now;
            lastProgressAt = now;
            inactiveSince = 0;
        }

        void reachedCurrent() {
            if (current != null) searched.add(current.id());
            clearCurrent();
        }

        void rejectCurrent(long now) {
            if (current != null) {
                rejectedUntil.remove(current.id());
                rejectedUntil.put(current.id(), now + REJECTION_MILLIS);
                while (rejectedUntil.size() > MAX_REJECTIONS) {
                    rejectedUntil.remove(rejectedUntil.keySet().iterator().next());
                }
            }
            clearCurrent();
        }

        Candidate current() { return current; }
        boolean hasCurrent() { return current != null; }

        void clearCurrent() {
            current = null;
            bestDistance = Double.POSITIVE_INFINITY;
            selectedAt = 0;
            lastProgressAt = 0;
            inactiveSince = 0;
        }
    }

    private LandHuntFrontierPolicy() { }
}
