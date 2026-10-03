package dev.entity.client.runtime;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Bounded mission-scoped circuit breaker for automatic death recovery.
 *
 * <p>Automatic respawn remains available, but persistence is not permission to repeat an
 * unchanged lethal route forever. Three deaths in one mission window, or two equivalent deaths
 * at the same site in quick succession, require an explicit operator decision.</p>
 */
public final class DeathLoopGuard {
    public static final long DEFAULT_WINDOW_MILLIS = 10 * 60_000L;
    public static final long DEFAULT_REPEAT_WINDOW_MILLIS = 90_000L;
    public static final int DEFAULT_MAXIMUM_DEATHS = 3;
    public static final double DEFAULT_REPEAT_RADIUS = 16.0;
    public static final int MAXIMUM_RETAINED_OBSERVATIONS = 16;

    private final long windowMillis;
    private final long repeatWindowMillis;
    private final int maximumDeaths;
    private final double repeatRadiusSquared;
    private final double repeatVerticalDistance;
    private final ArrayDeque<Observation> observations = new ArrayDeque<>();

    public DeathLoopGuard() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_REPEAT_WINDOW_MILLIS,
                DEFAULT_MAXIMUM_DEATHS, DEFAULT_REPEAT_RADIUS);
    }

    DeathLoopGuard(
            long windowMillis,
            long repeatWindowMillis,
            int maximumDeaths,
            double repeatRadius) {
        if (windowMillis < 1L || repeatWindowMillis < 1L || maximumDeaths < 2
                || !Double.isFinite(repeatRadius) || repeatRadius < 0.0) {
            throw new IllegalArgumentException("invalid death-loop thresholds");
        }
        this.windowMillis = windowMillis;
        this.repeatWindowMillis = repeatWindowMillis;
        this.maximumDeaths = maximumDeaths;
        this.repeatRadiusSquared = repeatRadius * repeatRadius;
        this.repeatVerticalDistance = repeatRadius;
    }

    public synchronized Decision observe(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        long now = Math.max(0L, observation.nowMillis());
        prune(now);

        int missionDeaths = 1;
        boolean repeatedSiteAndCause = false;
        for (Observation prior : observations) {
            if (!prior.missionId().equals(observation.missionId())) continue;
            missionDeaths++;
            long age = Math.max(0L, now - prior.nowMillis());
            if (age <= repeatWindowMillis
                    && prior.dimension().equals(observation.dimension())
                    && causeFamily(prior.cause()).equals(causeFamily(observation.cause()))
                    && horizontalDistanceSquared(prior, observation) <= repeatRadiusSquared
                    && Math.abs(prior.y() - observation.y()) <= repeatVerticalDistance) {
                repeatedSiteAndCause = true;
            }
        }
        observations.addLast(observation.normalized(now));
        while (observations.size() > MAXIMUM_RETAINED_OBSERVATIONS) observations.removeFirst();

        boolean halt = missionDeaths >= maximumDeaths || repeatedSiteAndCause;
        String reason = halt
                ? "automatic recovery stopped after " + missionDeaths + " death(s) in mission "
                        + shortId(observation.missionId())
                        + (repeatedSiteAndCause
                        ? "; the same lethal cause repeated near the prior death site"
                        : "; the bounded death-recovery budget was exhausted")
                : "automatic recovery remains within the bounded death budget ("
                        + missionDeaths + "/" + maximumDeaths + ")";
        return new Decision(halt, missionDeaths, repeatedSiteAndCause, reason);
    }

    public synchronized void clear(String missionId) {
        if (missionId == null || missionId.isBlank()) return;
        observations.removeIf(observation -> observation.missionId().equals(missionId.trim()));
    }

    public synchronized void clearAll() {
        observations.clear();
    }

    /** Returns a checked, bounded copy suitable for crash-safe persistence. */
    public synchronized Snapshot snapshot(long nowMillis) {
        if (nowMillis < 0L) throw new IllegalArgumentException("snapshot time must be non-negative");
        prune(nowMillis);
        return new Snapshot(List.copyOf(observations));
    }

    /**
     * Replaces in-memory history from a checked durable snapshot. Future and expired records are
     * discarded so a clock rollback or stale file cannot poison a new recovery episode.
     */
    public synchronized void restore(Snapshot snapshot, long nowMillis) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (nowMillis < 0L) throw new IllegalArgumentException("restore time must be non-negative");
        observations.clear();
        observations.addAll(snapshot.observations());
        prune(nowMillis);
    }

    private void prune(long now) {
        Iterator<Observation> iterator = observations.iterator();
        while (iterator.hasNext()) {
            Observation observation = iterator.next();
            if (now - observation.nowMillis() > windowMillis || now < observation.nowMillis()) {
                iterator.remove();
            }
        }
    }

    private static double horizontalDistanceSquared(Observation first, Observation second) {
        double dx = first.x() - second.x();
        double dz = first.z() - second.z();
        return dx * dx + dz * dz;
    }

    private static String causeFamily(String raw) {
        String cause = Objects.requireNonNullElse(raw, "unknown").toLowerCase(Locale.ROOT);
        if (cause.contains("lava") || cause.contains("fire")) return "lava_fire";
        if (cause.contains("suffocat") || cause.contains("wall")) return "suffocation";
        if (cause.contains("drown") || cause.contains("air")) return "drowning";
        if (cause.contains("fall") || cause.contains("ground")) return "fall";
        if (cause.contains("shot") || cause.contains("slain") || cause.contains("killed")) {
            return "combat";
        }
        return cause.trim();
    }

    private static String shortId(String missionId) {
        String value = Objects.requireNonNullElse(missionId, "").trim();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    public record Observation(
            String missionId,
            String dimension,
            double x,
            double y,
            double z,
            String cause,
            long nowMillis) {
        public Observation {
            missionId = Objects.requireNonNullElse(missionId, "").trim();
            dimension = Objects.requireNonNullElse(dimension, "").trim();
            cause = Objects.requireNonNullElse(cause, "unknown").trim();
            if (missionId.isEmpty() || dimension.isEmpty()
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || nowMillis < 0L) {
                throw new IllegalArgumentException("death observation requires a mission, world, and finite position");
            }
        }

        private Observation normalized(long normalizedNow) {
            return new Observation(missionId, dimension, x, y, z, cause, normalizedNow);
        }
    }

    public record Decision(
            boolean haltAutomaticRecovery,
            int missionDeaths,
            boolean repeatedSiteAndCause,
            String reason) {
        public Decision {
            reason = Objects.requireNonNullElse(reason, "");
            if (missionDeaths < 0 || missionDeaths > MAXIMUM_RETAINED_OBSERVATIONS + 1
                    || repeatedSiteAndCause && missionDeaths < 2) {
                throw new IllegalArgumentException("invalid death-loop decision counters");
            }
        }
    }

    public record Snapshot(List<Observation> observations) {
        public Snapshot {
            observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
            if (observations.size() > MAXIMUM_RETAINED_OBSERVATIONS
                    || observations.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("death-loop snapshot exceeds its bounded history");
            }
        }
    }
}
