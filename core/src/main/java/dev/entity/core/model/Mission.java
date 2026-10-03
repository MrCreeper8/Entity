package dev.entity.core.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Durable mission aggregate. A recoverable execution failure changes state; it
 * never removes the mission.
 */
public final class Mission {
    private String id;
    private String commandId;
    private String issuedBy;
    private String kind;
    private Map<String, String> parameters;
    private MissionState state;
    private String phase;
    private String pauseReason;
    private String lastError;
    private long createdAtMillis;
    private long updatedAtMillis;
    private long nextRetryAtMillis;
    private int transientFailures;
    private int interruptions;
    private long revision;

    @SuppressWarnings("unused") // persistence restore factory uses this blank instance
    private Mission() {
    }

    public Mission(
            String id,
            String commandId,
            String issuedBy,
            String kind,
            Map<String, String> parameters,
            long nowMillis) {
        this.id = requireText(id, "id");
        this.commandId = requireText(commandId, "commandId");
        this.issuedBy = requireText(issuedBy, "issuedBy");
        this.kind = requireText(kind, "kind");
        this.parameters = new LinkedHashMap<>(Objects.requireNonNull(parameters, "parameters"));
        this.state = MissionState.QUEUED;
        this.phase = "accepted";
        this.pauseReason = "";
        this.lastError = "";
        this.createdAtMillis = nowMillis;
        this.updatedAtMillis = nowMillis;
        this.revision = 1;
    }

    public static Mission restore(
            String id,
            String commandId,
            String issuedBy,
            String kind,
            Map<String, String> parameters,
            MissionState state,
            String phase,
            String pauseReason,
            String lastError,
            long createdAtMillis,
            long updatedAtMillis,
            long nextRetryAtMillis,
            int transientFailures,
            long revision) {
        return restore(
                id, commandId, issuedBy, kind, parameters, state, phase,
                pauseReason, lastError, createdAtMillis, updatedAtMillis,
                nextRetryAtMillis, transientFailures, 0, revision);
    }

    public static Mission restore(
            String id,
            String commandId,
            String issuedBy,
            String kind,
            Map<String, String> parameters,
            MissionState state,
            String phase,
            String pauseReason,
            String lastError,
            long createdAtMillis,
            long updatedAtMillis,
            long nextRetryAtMillis,
            int transientFailures,
            int interruptions,
            long revision) {
        Mission mission = new Mission();
        mission.id = id;
        mission.commandId = commandId;
        mission.issuedBy = issuedBy;
        mission.kind = kind;
        mission.parameters = new LinkedHashMap<>(parameters);
        mission.state = state;
        mission.phase = Objects.requireNonNullElse(phase, "");
        mission.pauseReason = Objects.requireNonNullElse(pauseReason, "");
        mission.lastError = Objects.requireNonNullElse(lastError, "");
        mission.createdAtMillis = createdAtMillis;
        mission.updatedAtMillis = updatedAtMillis;
        mission.nextRetryAtMillis = nextRetryAtMillis;
        mission.transientFailures = transientFailures;
        mission.interruptions = interruptions;
        mission.revision = revision;
        mission.validate();
        return mission;
    }

    public Mission copy() {
        Mission copy = new Mission();
        copy.id = id;
        copy.commandId = commandId;
        copy.issuedBy = issuedBy;
        copy.kind = kind;
        copy.parameters = new LinkedHashMap<>(parameters);
        copy.state = state;
        copy.phase = phase;
        copy.pauseReason = pauseReason;
        copy.lastError = lastError;
        copy.createdAtMillis = createdAtMillis;
        copy.updatedAtMillis = updatedAtMillis;
        copy.nextRetryAtMillis = nextRetryAtMillis;
        copy.transientFailures = transientFailures;
        copy.interruptions = interruptions;
        copy.revision = revision;
        return copy;
    }

    public void validate() {
        requireText(id, "id");
        requireText(commandId, "commandId");
        requireText(issuedBy, "issuedBy");
        requireText(kind, "kind");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(state, "state");
        if (revision < 1 || transientFailures < 0 || interruptions < 0) {
            throw new IllegalStateException("Invalid mission counters for " + id);
        }
    }

    public void start(long nowMillis) {
        requireNonTerminal();
        transition(MissionState.RUNNING, "executing", "", nowMillis);
        nextRetryAtMillis = 0;
    }

    public void pauseForSafety(String reason, long nowMillis) {
        requireNonTerminal();
        transition(MissionState.PAUSED_BY_SAFETY, phase, reason, nowMillis);
    }

    /** Counts a safety interruption without feeding pathfinding retry backoff. */
    public void recordInterruption(long nowMillis) {
        requireNonTerminal();
        interruptions++;
        touch(nowMillis);
    }

    public void pauseByOwner(String reason, long nowMillis) {
        requireNonTerminal();
        transition(
                MissionState.PAUSED_BY_OWNER,
                phase,
                Objects.requireNonNullElse(reason, "paused by owner"),
                nowMillis);
    }

    public void pauseForProtection(String reason, long nowMillis) {
        requireNonTerminal();
        transition(MissionState.PAUSED_BY_PROTECTION, phase, reason, nowMillis);
    }

    public void retryLater(String error, long retryAtMillis, long nowMillis) {
        requireNonTerminal();
        transientFailures++;
        lastError = Objects.requireNonNullElse(error, "transient failure");
        nextRetryAtMillis = Math.max(nowMillis, retryAtMillis);
        transition(MissionState.RETRY_WAIT, "replanning", lastError, nowMillis);
    }

    public void block(String reason, long nowMillis) {
        requireNonTerminal();
        lastError = Objects.requireNonNullElse(reason, "blocked");
        nextRetryAtMillis = 0;
        transition(MissionState.BLOCKED, "needs intervention", lastError, nowMillis);
    }

    /**
     * Parks on an externally mutable fact while retaining one durable,
     * backoff-bounded reconciliation deadline.
     */
    public void blockForReconciliation(String reason, long retryAtMillis, long nowMillis) {
        requireNonTerminal();
        transientFailures++;
        lastError = Objects.requireNonNullElse(reason, "blocked pending reconciliation");
        nextRetryAtMillis = Math.max(nowMillis, retryAtMillis);
        transition(MissionState.BLOCKED, "awaiting reconciliation", lastError, nowMillis);
    }

    public void updatePhase(String phase, long nowMillis) {
        requireNonTerminal();
        this.phase = requireText(phase, "phase");
        touch(nowMillis);
    }

    public void complete(long nowMillis) {
        requireNonTerminal();
        transition(MissionState.COMPLETED, "complete", "", nowMillis);
    }

    public void cancel(String reason, long nowMillis) {
        requireNonTerminal();
        transition(MissionState.CANCELLED, "cancelled", Objects.requireNonNullElse(reason, "cancelled"), nowMillis);
    }

    private void transition(MissionState next, String nextPhase, String reason, long nowMillis) {
        state = next;
        phase = nextPhase;
        pauseReason = Objects.requireNonNullElse(reason, "");
        touch(nowMillis);
    }

    private void touch(long nowMillis) {
        updatedAtMillis = Math.max(updatedAtMillis, nowMillis);
        revision++;
    }

    private void requireNonTerminal() {
        if (state.terminal()) {
            throw new IllegalStateException("Mission " + id + " is already " + state);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String id() { return id; }
    public String commandId() { return commandId; }
    public String issuedBy() { return issuedBy; }
    public String kind() { return kind; }
    public Map<String, String> parameters() { return Map.copyOf(parameters); }
    public MissionState state() { return state; }
    public String phase() { return phase; }
    public String pauseReason() { return pauseReason; }
    public String lastError() { return lastError; }
    public long createdAtMillis() { return createdAtMillis; }
    public long updatedAtMillis() { return updatedAtMillis; }
    public long nextRetryAtMillis() { return nextRetryAtMillis; }
    public int transientFailures() { return transientFailures; }
    public int interruptions() { return interruptions; }
    public long revision() { return revision; }
}
