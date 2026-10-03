package dev.entity.client.telemetry;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;

/**
 * Per-client-tick detector and bounded delivery outbox for facts that are too
 * short-lived to entrust to the periodic black-box sample.
 *
 * <p>Observation state advances when Minecraft is observed, while the delivery
 * cursor advances only after the recorder accepts the corresponding typed
 * event. A full recorder queue therefore causes a retry of the same immutable
 * domain event rather than either losing it or manufacturing a duplicate.</p>
 */
public final class CriticalBlackBoxEvents {
    static final int DEFAULT_PENDING_CAPACITY = 64;
    static final int DEFAULT_DAMAGE_EVIDENCE_CAPACITY = 32;
    static final long DAMAGE_EVIDENCE_RETENTION_MILLIS = 2_500L;
    static final int MAX_ADMISSIONS_PER_FLUSH = 8;
    private static final double HEALTH_EPSILON = 0.000_1;
    private static final double HEALTH_BEFORE_MATCH_TOLERANCE = 0.51;

    private final int pendingCapacity;
    private final int damageEvidenceCapacity;
    private final Deque<PendingEvent> pending = new ArrayDeque<>();
    private final Deque<DamageEvidence> damageEvidence = new ArrayDeque<>();

    private HazardState previousHazards;
    private double previousHealth;
    private boolean observationAvailable;
    private long nextHazardSequence;
    private long nextDamageSequence;
    private long acceptedHazardSequence;
    private long acceptedDamageSequence;
    private long droppedCriticalEvents;

    public CriticalBlackBoxEvents() {
        this(DEFAULT_PENDING_CAPACITY, DEFAULT_DAMAGE_EVIDENCE_CAPACITY);
    }

    CriticalBlackBoxEvents(int pendingCapacity, int damageEvidenceCapacity) {
        if (pendingCapacity < 1) {
            throw new IllegalArgumentException("pendingCapacity must be positive");
        }
        if (damageEvidenceCapacity < 1) {
            throw new IllegalArgumentException("damageEvidenceCapacity must be positive");
        }
        this.pendingCapacity = pendingCapacity;
        this.damageEvidenceCapacity = damageEvidenceCapacity;
    }

    /** Captures all transitions since the previous available client-tick observation. */
    public void observe(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        pruneDamageEvidence(observation.timestamp());

        if (!observationAvailable) {
            observationAvailable = true;
            previousHazards = observation.hazards();
            previousHealth = observation.health();
            // Starting while already in contact is itself important evidence;
            // an all-safe initial baseline remains silent.
            for (Hazard hazard : Hazard.values()) {
                if (hazard.active(observation.hazards())) {
                    appendHazardTransition(observation, hazard, true);
                }
            }
            return;
        }

        for (Hazard hazard : Hazard.values()) {
            boolean wasActive = hazard.active(previousHazards);
            boolean active = hazard.active(observation.hazards());
            if (wasActive != active) {
                appendHazardTransition(observation, hazard, active);
            }
        }

        if (observation.health() + HEALTH_EPSILON < previousHealth) {
            appendIncomingDamage(observation, previousHealth);
        }
        previousHazards = observation.hazards();
        previousHealth = observation.health();
    }

    /**
     * Ends the current player/world observation session without discarding
     * already-retained events. The next available player establishes a fresh
     * baseline, avoiding a false exit or damage record across respawn/join.
     */
    public void observationUnavailable() {
        observationAvailable = false;
        previousHazards = null;
        damageEvidence.clear();
    }

    /** Retains bounded Paper-authoritative evidence for the next matching health loss. */
    public void acceptDamageEvidence(DamageEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        while (damageEvidence.size() >= damageEvidenceCapacity) {
            damageEvidence.removeFirst();
        }
        damageEvidence.addLast(evidence);
    }

    /**
     * Attempts bounded in-order delivery. A rejected head stays at the head,
     * and no accepted domain cursor advances until a later retry succeeds.
     */
    public int flush(EventSink sink) {
        Objects.requireNonNull(sink, "sink");
        int accepted = 0;
        while (accepted < MAX_ADMISSIONS_PER_FLUSH) {
            PendingEvent event = pending.peekFirst();
            if (event == null) break;
            boolean admitted;
            try {
                admitted = sink.event(event.timestamp(), event.eventType(), event.body());
            } catch (RuntimeException ignored) {
                admitted = false;
            }
            if (!admitted) break;
            pending.removeFirst();
            if (event.eventType().equals("hazard_contact")) {
                acceptedHazardSequence = event.domainSequence();
            } else if (event.eventType().equals("incoming_damage")) {
                acceptedDamageSequence = event.domainSequence();
            }
            accepted++;
        }
        return accepted;
    }

    private void appendHazardTransition(
            Observation observation,
            Hazard hazard,
            boolean active) {
        long sequence = ++nextHazardSequence;
        JsonObject event = commonEvent(observation);
        event.addProperty("hazardSequence", sequence);
        event.addProperty("hazard", hazard.wireName);
        event.addProperty("transition", active ? "entered" : "exited");
        event.addProperty("active", active);
        addHazardSnapshot(event, observation.hazards());
        append(new PendingEvent(
                "hazard_contact", observation.timestamp(), sequence, event));
    }

    private void appendIncomingDamage(Observation observation, double healthBefore) {
        long sequence = ++nextDamageSequence;
        double delta = Math.max(0.0, healthBefore - observation.health());
        DamageEvidence evidence = selectDamageEvidence(
                observation.timestamp(), healthBefore);

        JsonObject event = commonEvent(observation);
        event.addProperty("damageSequence", sequence);
        event.addProperty("healthBefore", healthBefore);
        event.addProperty("healthAfter", observation.health());
        event.addProperty("healthDelta", delta);
        event.add("damageEvidence", encodeDamageEvidence(evidence, delta));
        append(new PendingEvent(
                "incoming_damage", observation.timestamp(), sequence, event));
    }

    private void append(PendingEvent event) {
        if (pending.size() >= pendingCapacity) {
            droppedCriticalEvents = saturatingIncrement(droppedCriticalEvents);
            return;
        }
        if (droppedCriticalEvents > 0L) {
            event.body().addProperty("criticalEventsDroppedBefore", droppedCriticalEvents);
            droppedCriticalEvents = 0L;
        }
        pending.addLast(event);
    }

    private DamageEvidence selectDamageEvidence(long observedAtMillis, double healthBefore) {
        pruneDamageEvidence(observedAtMillis);
        DamageEvidence match = damageEvidence.stream()
                .filter(evidence -> evidence.receivedAtMillis() <= observedAtMillis)
                .filter(evidence -> Math.abs(evidence.healthBefore() - healthBefore)
                        <= HEALTH_BEFORE_MATCH_TOLERANCE)
                .min(Comparator
                        .comparingDouble((DamageEvidence evidence) ->
                                Math.abs(evidence.healthBefore() - healthBefore))
                        .thenComparingLong(evidence ->
                                Math.abs(observedAtMillis - evidence.receivedAtMillis())))
                .orElse(null);
        if (match != null) damageEvidence.remove(match);
        return match;
    }

    private void pruneDamageEvidence(long nowMillis) {
        long oldest = nowMillis - DAMAGE_EVIDENCE_RETENTION_MILLIS;
        damageEvidence.removeIf(evidence -> evidence.receivedAtMillis() < oldest);
    }

    private static JsonObject commonEvent(Observation observation) {
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", observation.timestamp());
        event.addProperty("clientTick", observation.clientTick());
        event.addProperty("health", observation.health());
        event.addProperty("air", observation.air());
        event.addProperty("maximumAir", observation.maximumAir());
        event.add("position", encodePosition(observation.position()));
        event.add("mission", encodeMission(observation.mission()));
        event.add("decision", encodeDecision(observation.decision()));
        return event;
    }

    private static JsonObject encodePosition(Position position) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("x", position.x());
        encoded.addProperty("y", position.y());
        encoded.addProperty("z", position.z());
        encoded.addProperty("dimension", position.dimension());
        return encoded;
    }

    private static JsonObject encodeMission(MissionContext mission) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("present", mission.present());
        if (mission.present()) {
            encoded.addProperty("id", mission.id());
            encoded.addProperty("kind", mission.kind());
            encoded.addProperty("state", mission.state());
            encoded.addProperty("phase", mission.phase());
        }
        return encoded;
    }

    private static JsonObject encodeDecision(DecisionContext decision) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("available", decision.available());
        if (decision.available()) {
            encoded.addProperty("layer", decision.layer());
            encoded.addProperty("action", decision.action());
            encoded.addProperty("reason", decision.reason());
            encoded.addProperty("controlEpoch", decision.controlEpoch());
        }
        return encoded;
    }

    private static void addHazardSnapshot(JsonObject event, HazardState hazards) {
        event.addProperty("inLava", hazards.inLava());
        event.addProperty("onFire", hazards.onFire());
        event.addProperty("insideWall", hazards.insideWall());
        event.addProperty("headSubmerged", hazards.headSubmerged());
    }

    private static JsonObject encodeDamageEvidence(DamageEvidence evidence, double healthDelta) {
        JsonObject encoded = new JsonObject();
        JsonObject attacker = new JsonObject();
        if (evidence == null) {
            encoded.addProperty("source", "unknown");
            encoded.addProperty("association", "none");
            encoded.addProperty("cause", "unknown");
            attacker.addProperty("available", false);
            attacker.addProperty("evidence", "unknown");
            encoded.add("attacker", attacker);
            return encoded;
        }

        encoded.addProperty("source", "paper_server_damage");
        encoded.addProperty("association", "health_before_match");
        encoded.addProperty("eventId", evidence.eventId());
        encoded.addProperty("cause", evidence.cause());
        encoded.addProperty("reportedDamage", evidence.damage());
        encoded.addProperty("reportedHealthBefore", evidence.healthBefore());
        encoded.addProperty("reportedAtMillis", evidence.reportedAtMillis());
        encoded.addProperty("receivedAtMillis", evidence.receivedAtMillis());
        encoded.addProperty("healthDeltaMatchesReportedDamage",
                Math.abs(healthDelta - evidence.damage()) <= HEALTH_BEFORE_MATCH_TOLERANCE);
        if (evidence.attacker() == null) {
            attacker.addProperty("available", false);
            attacker.addProperty("evidence", "paper_not_attributed");
        } else {
            AttackerEvidence facts = evidence.attacker();
            attacker.addProperty("available", true);
            attacker.addProperty("evidence", "paper_attributed");
            attacker.addProperty("uuid", facts.uuid());
            attacker.addProperty("type", facts.type());
            attacker.addProperty("name", facts.name());
            attacker.addProperty("distance", facts.distance());
            attacker.addProperty("projectile", facts.projectile());
            if (facts.projectile()) {
                attacker.addProperty("projectileType", facts.projectileType());
            }
        }
        encoded.add("attacker", attacker);
        return encoded;
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    int pendingCount() {
        return pending.size();
    }

    long acceptedHazardSequence() {
        return acceptedHazardSequence;
    }

    long acceptedDamageSequence() {
        return acceptedDamageSequence;
    }

    long droppedCriticalEvents() {
        return droppedCriticalEvents;
    }

    @FunctionalInterface
    public interface EventSink {
        boolean event(long timestamp, String eventType, JsonObject body);
    }

    private enum Hazard {
        IN_LAVA("in_lava") {
            @Override boolean active(HazardState state) { return state.inLava(); }
        },
        ON_FIRE("on_fire") {
            @Override boolean active(HazardState state) { return state.onFire(); }
        },
        INSIDE_WALL("inside_wall") {
            @Override boolean active(HazardState state) { return state.insideWall(); }
        },
        HEAD_SUBMERGED("head_submerged") {
            @Override boolean active(HazardState state) { return state.headSubmerged(); }
        };

        private final String wireName;

        Hazard(String wireName) {
            this.wireName = wireName;
        }

        abstract boolean active(HazardState state);
    }

    private record PendingEvent(
            String eventType,
            long timestamp,
            long domainSequence,
            JsonObject body) {
    }

    public record HazardState(
            boolean inLava,
            boolean onFire,
            boolean insideWall,
            boolean headSubmerged) {
        public static HazardState safe() {
            return new HazardState(false, false, false, false);
        }
    }

    public record Position(double x, double y, double z, String dimension) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("position must be finite");
            }
            dimension = required(dimension, "dimension");
        }
    }

    public record MissionContext(
            boolean present,
            String id,
            String kind,
            String state,
            String phase) {
        public MissionContext {
            id = normalized(id);
            kind = normalized(kind);
            state = normalized(state).toLowerCase(Locale.ROOT);
            phase = normalized(phase);
            if (present && (id.isEmpty() || kind.isEmpty() || state.isEmpty())) {
                throw new IllegalArgumentException(
                        "present mission requires id, kind, and state");
            }
        }

        public static MissionContext none() {
            return new MissionContext(false, "", "", "", "");
        }
    }

    public record DecisionContext(
            boolean available,
            String layer,
            String action,
            String reason,
            long controlEpoch) {
        public DecisionContext {
            layer = normalized(layer).toLowerCase(Locale.ROOT);
            action = normalized(action);
            reason = normalized(reason);
            if (available && (layer.isEmpty() || action.isEmpty())) {
                throw new IllegalArgumentException(
                        "available decision requires layer and action");
            }
        }

        public static DecisionContext unavailable() {
            return new DecisionContext(false, "", "", "", 0L);
        }
    }

    public record Observation(
            long timestamp,
            long clientTick,
            Position position,
            double health,
            int air,
            int maximumAir,
            HazardState hazards,
            MissionContext mission,
            DecisionContext decision) {
        public Observation {
            if (timestamp < 0L || clientTick < 0L) {
                throw new IllegalArgumentException(
                        "timestamp and clientTick must be non-negative");
            }
            position = Objects.requireNonNull(position, "position");
            if (!Double.isFinite(health) || health < 0.0) {
                throw new IllegalArgumentException("health must be finite and non-negative");
            }
            if (maximumAir < 0) {
                throw new IllegalArgumentException("maximumAir must be non-negative");
            }
            hazards = Objects.requireNonNull(hazards, "hazards");
            mission = Objects.requireNonNullElseGet(mission, MissionContext::none);
            decision = Objects.requireNonNullElseGet(decision, DecisionContext::unavailable);
        }
    }

    public record AttackerEvidence(
            String uuid,
            String type,
            String name,
            double distance,
            boolean projectile,
            String projectileType) {
        public AttackerEvidence {
            uuid = required(uuid, "attacker uuid");
            type = required(type, "attacker type");
            name = required(name, "attacker name");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException(
                        "attacker distance must be finite and non-negative");
            }
            projectileType = normalized(projectileType);
            if (projectile && projectileType.isEmpty()) {
                throw new IllegalArgumentException(
                        "projectile type is required for projectile evidence");
            }
        }
    }

    public record DamageEvidence(
            String eventId,
            String cause,
            double damage,
            double healthBefore,
            long reportedAtMillis,
            long receivedAtMillis,
            AttackerEvidence attacker) {
        public DamageEvidence {
            eventId = required(eventId, "damage event id");
            cause = required(cause, "damage cause").toLowerCase(Locale.ROOT);
            if (!Double.isFinite(damage) || damage < 0.0
                    || !Double.isFinite(healthBefore) || healthBefore < 0.0) {
                throw new IllegalArgumentException(
                        "damage and healthBefore must be finite and non-negative");
            }
            if (reportedAtMillis < 0L || receivedAtMillis < 0L) {
                throw new IllegalArgumentException("damage timestamps must be non-negative");
            }
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static String required(String value, String label) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }
}
