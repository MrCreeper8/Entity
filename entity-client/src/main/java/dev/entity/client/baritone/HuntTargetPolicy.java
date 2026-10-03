package dev.entity.client.baritone;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Cost-and-hysteresis policy for replacing a live hunt target without target thrashing. */
final class HuntTargetPolicy {
    static final long RECONSIDER_INTERVAL_MILLIS = 2_000;
    static final long SWITCH_COOLDOWN_MILLIS = 4_000;
    static final double REQUIRED_COST_RATIO = 0.65;
    static final double REQUIRED_ABSOLUTE_SAVING = 6.0;

    record RouteFeatures(
            double horizontalDistance,
            double verticalDistance,
            int waterSamples,
            int steepRise,
            int blockedSamples,
            int unloadedSamples,
            boolean lineOfSight,
            boolean mediumTransition) {
        RouteFeatures {
            if (!Double.isFinite(horizontalDistance) || horizontalDistance < 0.0
                    || !Double.isFinite(verticalDistance) || verticalDistance < 0.0
                    || waterSamples < 0 || steepRise < 0 || blockedSamples < 0
                    || unloadedSamples < 0) {
                throw new IllegalArgumentException("invalid hunt route features");
            }
        }
    }

    /** Keeps both newly discovered and already pinned animals inside the bounded hunt area. */
    static boolean insideSearchBoundary(
            double candidateX,
            double candidateZ,
            double originX,
            double originZ,
            double radius) {
        if (!Double.isFinite(candidateX) || !Double.isFinite(candidateZ)
                || !Double.isFinite(originX) || !Double.isFinite(originZ)
                || !Double.isFinite(radius) || radius < 0.0) {
            return false;
        }
        double dx = candidateX - originX;
        double dz = candidateZ - originZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** A practical loaded-world estimate; Baritone remains the authoritative pathfinder. */
    static double pursuitCost(RouteFeatures route) {
        Objects.requireNonNull(route, "route");
        return route.horizontalDistance()
                + route.verticalDistance() * 2.5
                + route.waterSamples() * 4.0
                + route.steepRise() * 3.0
                + route.blockedSamples() * 2.0
                + route.unloadedSamples() * 10.0
                + (route.lineOfSight() ? 0.0 : 4.0)
                + (route.mediumTransition() ? 8.0 : 0.0);
    }

    /** Passive food hunts do not commit to an open-ocean crossing for one loaded animal. */
    static boolean passiveRouteAllowed(double routeWaterFraction, boolean escapingWater) {
        if (!Double.isFinite(routeWaterFraction)
                || routeWaterFraction < 0.0 || routeWaterFraction > 1.0) return false;
        return escapingWater || routeWaterFraction <= 0.40;
    }

    record Candidate(String id, double cost) {
        Candidate {
            id = Objects.requireNonNull(id, "id");
            if (id.isBlank() || !Double.isFinite(cost) || cost < 0.0) {
                throw new IllegalArgumentException("invalid hunt candidate");
            }
        }
    }

    record Decision(boolean switchTarget, String reason) {
        private static Decision keep(String reason) {
            return new Decision(false, reason);
        }

        private static Decision switchToBetter() {
            return new Decision(true, "a substantially cheaper loaded target appeared");
        }
    }

    static final class Session {
        private long lastReconsideredAt = Long.MIN_VALUE;
        private long lastSwitchedAt = Long.MIN_VALUE;

        Decision evaluate(
                Candidate current,
                Candidate best,
                boolean currentEngagementCommitted,
                long nowMillis) {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(best, "best");
            if (!elapsed(lastReconsideredAt, RECONSIDER_INTERVAL_MILLIS, nowMillis)) {
                return Decision.keep("waiting for the next target reconsideration window");
            }
            lastReconsideredAt = nowMillis;
            if (current.id().equals(best.id())) {
                return Decision.keep("current target remains cheapest");
            }
            if (currentEngagementCommitted) {
                return Decision.keep("current target is already inside the visible engagement envelope");
            }
            if (!elapsed(lastSwitchedAt, SWITCH_COOLDOWN_MILLIS, nowMillis)) {
                return Decision.keep("recent target switch is inside the hysteresis cooldown");
            }

            double saving = current.cost() - best.cost();
            boolean substantiallyCheaper = saving >= REQUIRED_ABSOLUTE_SAVING
                    && best.cost() <= current.cost() * REQUIRED_COST_RATIO;
            if (!substantiallyCheaper) {
                return Decision.keep("challenger is not sufficiently cheaper than the pinned target");
            }
            lastSwitchedAt = nowMillis;
            return Decision.switchToBetter();
        }

        boolean reconsiderationDue(long nowMillis) {
            return elapsed(lastReconsideredAt, RECONSIDER_INTERVAL_MILLIS, nowMillis);
        }

        void selected(long nowMillis) {
            lastSwitchedAt = nowMillis;
            lastReconsideredAt = nowMillis;
        }

        void reset() {
            lastReconsideredAt = Long.MIN_VALUE;
            lastSwitchedAt = Long.MIN_VALUE;
        }

        private static boolean elapsed(long since, long interval, long nowMillis) {
            return since == Long.MIN_VALUE || nowMillis - since >= interval;
        }
    }

    /** Bounded, expiring memory of entities whose actual Baritone route failed. */
    static final class RejectionMemory {
        private final long rejectionMillis;
        private final int maximumEntries;
        private final LinkedHashMap<String, Rejection> rejected = new LinkedHashMap<>();

        RejectionMemory(long rejectionMillis, int maximumEntries) {
            if (rejectionMillis <= 0 || maximumEntries < 1) {
                throw new IllegalArgumentException("invalid hunt rejection memory");
            }
            this.rejectionMillis = rejectionMillis;
            this.maximumEntries = maximumEntries;
        }

        void reject(String targetId, long nowMillis, String reason) {
            String id = normalizedId(targetId);
            prune(nowMillis);
            rejected.remove(id);
            rejected.put(id, new Rejection(nowMillis + rejectionMillis,
                    Objects.requireNonNullElse(reason, "route rejected")));
            while (rejected.size() > maximumEntries) {
                Iterator<Map.Entry<String, Rejection>> iterator = rejected.entrySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }

        boolean isRejected(String targetId, long nowMillis) {
            String id = normalizedId(targetId);
            Rejection rejection = rejected.get(id);
            if (rejection == null) return false;
            if (nowMillis >= rejection.expiresAtMillis()) {
                rejected.remove(id);
                return false;
            }
            return true;
        }

        String reason(String targetId, long nowMillis) {
            String id = normalizedId(targetId);
            return isRejected(id, nowMillis) ? rejected.get(id).reason() : "";
        }

        int size(long nowMillis) {
            prune(nowMillis);
            return rejected.size();
        }

        private void prune(long nowMillis) {
            rejected.entrySet().removeIf(entry -> nowMillis >= entry.getValue().expiresAtMillis());
        }

        private static String normalizedId(String targetId) {
            String id = Objects.requireNonNull(targetId, "targetId").trim();
            if (id.isEmpty()) throw new IllegalArgumentException("targetId cannot be blank");
            return id;
        }

        private record Rejection(long expiresAtMillis, String reason) {
        }
    }
}
