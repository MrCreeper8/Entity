package dev.entity.core.action;

import dev.entity.core.progress.ProgressMonitor;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Supervises one semantic action at a time without confusing standing still
 * with being stuck. Callers report an action phase plus an evidence token that
 * changes only when useful work is observed (block damage, inventory changes,
 * a new screen, recipe output, and so on).
 *
 * <p>Recoverable failures are counted by stable failure code. The supervisor
 * requests a bounded local recovery first and blocks only after the action has
 * exhausted its policy. It never silently declares work complete and never
 * asks a caller to repeat the same failure forever.</p>
 */
public final class ActionSupervisor {
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public synchronized Decision observe(String actionId, Observation observation, Policy policy) {
        return observe(actionId, observation, policy, false, null);
    }

    /**
     * Construction evidence is the verified permanent-work high-water, never a
     * body/inventory fingerprint. Only a new maximum renews the recovery episode;
     * losing and replacing a cell, phase changes and native restarts do not.
     */
    public synchronized Decision observeBuild(String actionId, Observation observation, Policy policy) {
        return observeBuild(actionId, observation, policy, null);
    }

    /** Native traversal frontier may keep the clock live, but never renews the work/recovery episode. */
    public synchronized Decision observeBuild(String actionId, Observation observation, Policy policy,
            ProgressMonitor.Position position) {
        return observe(actionId, observation, policy, true, position);
    }

    private Decision observe(String actionId, Observation observation, Policy policy, boolean build,
            ProgressMonitor.Position position) {
        String id = requireText(actionId, "actionId");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(policy, "policy");

        Session session = sessions.computeIfAbsent(id, ignored -> Session.start(observation));
        if (observation.nowMillis() < session.lastObservedAt) {
            throw new IllegalArgumentException("action observations must be chronological");
        }
        if (session.suspendedAt >= 0) {
            long suspendedFor = Math.max(0, observation.nowMillis() - session.suspendedAt);
            session.lastProgressAt = Math.addExact(session.lastProgressAt, suspendedFor);
            session.suspendedAt = -1;
        }
        session.lastObservedAt = observation.nowMillis();

        if (observation.signal() == Signal.SUCCEEDED) {
            sessions.remove(id);
            return new Decision(State.SUCCEEDED, 0, session.totalRecoveries,
                    observation.detail(), observation.phase());
        }
        if (observation.signal() == Signal.BLOCKED) {
            sessions.remove(id);
            return new Decision(State.BLOCKED, session.sameFailureCount, session.totalRecoveries,
                    observation.detail(), observation.phase());
        }

        boolean phaseAdvanced = !build && !session.phase.equals(observation.phase());
        boolean evidenceAdvanced = build
                ? observation.evidenceToken() > session.evidence
                : session.evidence != observation.evidenceToken();
        boolean explicitProgress = !build && observation.signal() == Signal.PROGRESS;
        if (phaseAdvanced || evidenceAdvanced || explicitProgress) {
            session.phase = observation.phase();
            session.evidence = observation.evidenceToken();
            session.lastProgressAt = observation.nowMillis();
            session.lastFailureCode = "";
            session.sameFailureCount = 0;
            if (build) {
                session.totalRecoveries = 0;
                session.buildRouteLiveness = null;
                session.observeBuildRoute(observation, position);
            }
            return new Decision(State.PROGRESS, 0, session.totalRecoveries,
                    observation.detail(), observation.phase());
        }

        if (observation.signal() == Signal.RECOVERABLE_FAILURE) {
            return recover(id, session, observation.failureCode(), observation.detail(), observation.phase(),
                    observation.nowMillis(), policy);
        }

        if (build && session.observeBuildRoute(observation, position)) {
            // Reuse the global monitor's monotonic spatial envelope, not raw
            // movement or distance text. It has no timeout authority here.
            // Preserve both recovery counters and the permanent-work high-water.
            session.lastProgressAt = observation.nowMillis();
        }
        long stalledFor = Math.max(0, observation.nowMillis() - session.lastProgressAt);
        if (stalledFor >= policy.stallTimeoutMillis()) {
            String code = "stall:" + normalizeCode(observation.phase());
            String detail = observation.detail().isBlank()
                    ? "action made no verified progress for " + stalledFor + " ms"
                    : observation.detail() + "; no verified progress for " + stalledFor + " ms";
            return recover(id, session, code, detail, observation.phase(), observation.nowMillis(), policy);
        }
        return new Decision(State.CONTINUE, session.sameFailureCount, session.totalRecoveries,
                observation.detail(), observation.phase());
    }

    public synchronized void clear(String actionId) {
        if (actionId != null) sessions.remove(actionId);
    }

    public synchronized void clearAll() {
        sessions.clear();
    }

    /** Pause a parent for its prerequisite without erasing physical high-water or attempts. */
    public synchronized void suspend(String actionId, long nowMillis) {
        if (nowMillis < 0) throw new IllegalArgumentException("nowMillis cannot be negative");
        Session session = sessions.get(actionId);
        if (session != null && session.suspendedAt < 0) session.suspendedAt = nowMillis;
    }

    /** Excludes an external safety/owner preemption from every action's stall clock. */
    public synchronized void suspendAll(long nowMillis) {
        if (nowMillis < 0) throw new IllegalArgumentException("nowMillis cannot be negative");
        for (Session session : sessions.values()) {
            if (session.suspendedAt < 0) session.suspendedAt = nowMillis;
        }
    }

    public synchronized Optional<Snapshot> snapshot(String actionId) {
        Session session = sessions.get(actionId);
        return session == null ? Optional.empty() : Optional.of(new Snapshot(
                session.phase,
                session.evidence,
                session.startedAt,
                session.lastProgressAt,
                session.lastFailureCode,
                session.sameFailureCount,
                session.totalRecoveries));
    }

    private Decision recover(
            String actionId,
            Session session,
            String rawFailureCode,
            String detail,
            String phase,
            long nowMillis,
            Policy policy) {
        String failureCode = normalizeCode(rawFailureCode);
        if (failureCode.equals(session.lastFailureCode)) {
            session.sameFailureCount++;
        } else {
            session.lastFailureCode = failureCode;
            session.sameFailureCount = 1;
        }
        session.totalRecoveries++;
        session.lastProgressAt = nowMillis;

        if (session.sameFailureCount > policy.maximumSameFailureRecoveries()
                || session.totalRecoveries > policy.maximumTotalRecoveries()) {
            sessions.remove(actionId);
            String reason = detail + "; stopped after " + session.totalRecoveries
                    + " bounded recovery attempt(s), including " + session.sameFailureCount
                    + " for " + failureCode;
            return new Decision(State.BLOCKED, session.sameFailureCount, session.totalRecoveries, reason, phase);
        }
        return new Decision(State.RECOVER, session.sameFailureCount, session.totalRecoveries,
                detail, phase);
    }

    public enum Signal {
        RUNNING,
        PROGRESS,
        RECOVERABLE_FAILURE,
        SUCCEEDED,
        BLOCKED
    }

    public enum State {
        CONTINUE,
        PROGRESS,
        RECOVER,
        SUCCEEDED,
        BLOCKED
    }

    public record Observation(
            long nowMillis,
            String phase,
            long evidenceToken,
            Signal signal,
            String failureCode,
            String detail) {
        public Observation {
            if (nowMillis < 0) throw new IllegalArgumentException("nowMillis cannot be negative");
            phase = requireText(phase, "phase");
            signal = Objects.requireNonNull(signal, "signal");
            failureCode = signal == Signal.RECOVERABLE_FAILURE
                    ? requireText(failureCode, "failureCode")
                    : Objects.requireNonNullElse(failureCode, "");
            detail = Objects.requireNonNullElse(detail, "");
        }

        public static Observation running(long now, String phase, long evidence, String detail) {
            return new Observation(now, phase, evidence, Signal.RUNNING, "", detail);
        }

        public static Observation progress(long now, String phase, long evidence, String detail) {
            return new Observation(now, phase, evidence, Signal.PROGRESS, "", detail);
        }

        public static Observation failure(
                long now, String phase, long evidence, String code, String detail) {
            return new Observation(now, phase, evidence, Signal.RECOVERABLE_FAILURE, code, detail);
        }
    }

    public record Policy(
            long stallTimeoutMillis,
            int maximumSameFailureRecoveries,
            int maximumTotalRecoveries) {
        public Policy {
            if (stallTimeoutMillis <= 0 || maximumSameFailureRecoveries < 0
                    || maximumTotalRecoveries < maximumSameFailureRecoveries) {
                throw new IllegalArgumentException("invalid action recovery policy");
            }
        }
    }

    public record Decision(
            State state,
            int sameFailureAttempt,
            int totalRecoveryAttempt,
            String detail,
            String phase) {
        public Decision {
            state = Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
            phase = requireText(phase, "phase");
        }
    }

    public record Snapshot(
            String phase,
            long evidenceToken,
            long startedAtMillis,
            long lastProgressAtMillis,
            String lastFailureCode,
            int sameFailureCount,
            int totalRecoveries) {
    }

    private static final class Session {
        private String phase;
        private long evidence;
        private final long startedAt;
        private long lastProgressAt;
        private long lastObservedAt;
        private String lastFailureCode = "";
        private int sameFailureCount;
        private int totalRecoveries;
        private long suspendedAt = -1;
        private ProgressMonitor buildRouteLiveness;

        private boolean observeBuildRoute(Observation observation, ProgressMonitor.Position position) {
            if (position == null || observation.signal() != Signal.RUNNING) return false;
            if (buildRouteLiveness == null)
                buildRouteLiveness = new ProgressMonitor(Long.MAX_VALUE, 0.75, 0.5);
            return buildRouteLiveness.observe("native-build-route", new ProgressMonitor.Observation(
                    observation.nowMillis(), position, Double.NaN, observation.evidenceToken())).status()
                    == ProgressMonitor.Status.PROGRESS;
        }

        private Session(Observation observation) {
            phase = observation.phase();
            evidence = observation.evidenceToken();
            startedAt = observation.nowMillis();
            lastProgressAt = observation.nowMillis();
            lastObservedAt = observation.nowMillis();
        }

        private static Session start(Observation observation) {
            return new Session(observation);
        }
    }

    private static String normalizeCode(String value) {
        String normalized = requireText(value, "failureCode").trim().toLowerCase(Locale.ROOT)
                .replace(' ', '_');
        while (normalized.contains("__")) normalized = normalized.replace("__", "_");
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
