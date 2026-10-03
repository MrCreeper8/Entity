package dev.entity.client.protection;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Connection-scoped Paper evidence for hostile targeting and recent damage.
 * Targeting and damage have independent clocks, so clearing one cannot erase
 * the other. Expiry is based on signed server time but capped at local receipt.
 */
public final class AuthoritativeThreatLedger {
    public static final long DAMAGE_EVIDENCE_MILLIS = 10_000L;
    public static final long TARGET_EVIDENCE_MILLIS = 5_000L;

    public enum Target {
        SELF,
        OWNER
    }

    public record Facts(
            UUID attackerUuid,
            String entityType,
            Target target,
            UUID targetUuid,
            String targetName
    ) {
        public Facts {
            attackerUuid = Objects.requireNonNull(attackerUuid, "attackerUuid");
            entityType = required(entityType, "entityType");
            target = Objects.requireNonNull(target, "target");
            targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
            targetName = required(targetName, "targetName");
        }
    }

    public record Evidence(
            UUID attackerUuid,
            String entityType,
            double damage,
            long expiresAtMillis,
            Target target,
            UUID targetUuid,
            String targetName,
            boolean targeting
    ) {
        public Evidence {
            attackerUuid = Objects.requireNonNull(attackerUuid, "attackerUuid");
            entityType = required(entityType, "entityType");
            if (!Double.isFinite(damage) || damage < 0.0) {
                throw new IllegalArgumentException("damage must be finite and non-negative");
            }
            if (expiresAtMillis < 0L) {
                throw new IllegalArgumentException("expiresAtMillis must be non-negative");
            }
            target = Objects.requireNonNull(target, "target");
            targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
            targetName = required(targetName, "targetName");
        }
    }

    private final Map<Key, State> states = new HashMap<>();
    // Connection-scoped life boundaries must outlive evidence expiry: a delayed old packet
    // must not make a respawned player inherit hostility from the previous life.
    private final Map<UUID, Long> invalidatedThrough = new HashMap<>();

    public void invalidateAttacker(UUID attackerUuid, long timestamp) {
        Objects.requireNonNull(attackerUuid, "attackerUuid");
        if (timestamp < 0L) throw new IllegalArgumentException("timestamp must be non-negative");
        invalidatedThrough.merge(attackerUuid, timestamp, Math::max);
        long boundary = invalidatedThrough.get(attackerUuid);
        states.entrySet().removeIf(entry -> entry.getKey().attackerUuid().equals(attackerUuid)
                && entry.getValue().latestEventTimestamp <= boundary);
    }

    /** Confirms recent damage without modifying the independent targeting clock. */
    public void confirmDamage(
            Facts facts,
            double damage,
            long eventTimestamp,
            long receivedAtMillis) {
        Objects.requireNonNull(facts, "facts");
        if (!Double.isFinite(damage) || damage < 0.0) {
            throw new IllegalArgumentException("damage must be finite and non-negative");
        }
        if (eventTimestamp < 0L || receivedAtMillis < 0L) {
            throw new IllegalArgumentException("timestamps must be non-negative");
        }
        if (eventTimestamp <= invalidatedThrough.getOrDefault(facts.attackerUuid(), -1L)) return;
        Key key = new Key(facts.attackerUuid(), facts.target());
        State state = states.get(key);
        if (state == null || !state.sameBinding(facts)) {
            state = new State(facts);
            states.put(key, state);
        } else {
            state.facts = facts;
        }
        state.damage = Math.max(state.damage, damage);
        state.latestEventTimestamp = Math.max(state.latestEventTimestamp, eventTimestamp);
        state.damageExpiresAtMillis = Math.max(
                state.damageExpiresAtMillis,
                boundedExpiry(eventTimestamp, receivedAtMillis, DAMAGE_EVIDENCE_MILLIS));
    }

    /**
     * Applies one exact target transition. Older target events cannot undo a
     * newer refresh; equal timestamps retain ordered socket arrival semantics.
     */
    public boolean setTargeting(
            Facts facts,
            boolean active,
            long eventTimestamp,
            long receivedAtMillis) {
        Objects.requireNonNull(facts, "facts");
        if (eventTimestamp < 0L || receivedAtMillis < 0L) {
            throw new IllegalArgumentException("timestamps must be non-negative");
        }
        if (eventTimestamp <= invalidatedThrough.getOrDefault(facts.attackerUuid(), -1L)) return false;
        Key key = new Key(facts.attackerUuid(), facts.target());
        State state = states.get(key);
        if (state == null) {
            state = new State(facts);
            states.put(key, state);
        } else if (!state.sameBinding(facts)) {
            // A clear for another protected identity must never revoke this one.
            if (!active) return false;
            if (eventTimestamp < state.targetRevisionTimestamp) return false;
            state = new State(facts);
            states.put(key, state);
        } else if (eventTimestamp < state.targetRevisionTimestamp) {
            return false;
        } else {
            state.facts = facts;
        }

        state.targetRevisionTimestamp = eventTimestamp;
        state.latestEventTimestamp = Math.max(state.latestEventTimestamp, eventTimestamp);
        state.targetRevisionRetainUntilMillis = saturatingAdd(
                receivedAtMillis, TARGET_EVIDENCE_MILLIS);
        state.targetExpiresAtMillis = active
                ? boundedExpiry(eventTimestamp, receivedAtMillis, TARGET_EVIDENCE_MILLIS)
                : 0L;
        return true;
    }

    public Optional<Evidence> find(UUID attackerUuid, Target target, long nowMillis) {
        Objects.requireNonNull(attackerUuid, "attackerUuid");
        Objects.requireNonNull(target, "target");
        Key key = new Key(attackerUuid, target);
        State state = states.get(key);
        if (state == null) return Optional.empty();
        expireSources(state, nowMillis);
        if (!state.hasEvidence()) {
            if (nowMillis >= state.targetRevisionRetainUntilMillis) states.remove(key);
            return Optional.empty();
        }
        return Optional.of(state.evidence(nowMillis));
    }

    public void prune(long nowMillis) {
        states.entrySet().removeIf(entry -> {
            State state = entry.getValue();
            expireSources(state, nowMillis);
            return !state.hasEvidence() && nowMillis >= state.targetRevisionRetainUntilMillis;
        });
    }

    /** Revokes every fact and ordering tombstone from the prior bridge connection. */
    public void reset() {
        states.clear();
        invalidatedThrough.clear();
    }

    int storedStateCount() {
        return states.size();
    }

    private static void expireSources(State state, long nowMillis) {
        if (nowMillis >= state.damageExpiresAtMillis) {
            state.damage = 0.0;
            state.damageExpiresAtMillis = 0L;
        }
        if (nowMillis >= state.targetExpiresAtMillis) {
            state.targetExpiresAtMillis = 0L;
        }
    }

    private static long boundedExpiry(
            long eventTimestamp,
            long receivedAtMillis,
            long lifetimeMillis) {
        if (eventTimestamp < 0L || receivedAtMillis < 0L) {
            throw new IllegalArgumentException("timestamps must be non-negative");
        }
        long eventExpiry = saturatingAdd(eventTimestamp, lifetimeMillis);
        long receiptCap = saturatingAdd(receivedAtMillis, lifetimeMillis);
        return Math.min(eventExpiry, receiptCap);
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private record Key(UUID attackerUuid, Target target) {
    }

    private static final class State {
        private Facts facts;
        private double damage;
        private long damageExpiresAtMillis;
        private long targetExpiresAtMillis;
        private long targetRevisionTimestamp = -1L;
        private long latestEventTimestamp = -1L;
        private long targetRevisionRetainUntilMillis;

        private State(Facts facts) {
            this.facts = Objects.requireNonNull(facts, "facts");
        }

        private boolean sameBinding(Facts other) {
            return facts.targetUuid().equals(other.targetUuid())
                    && facts.targetName().equalsIgnoreCase(other.targetName());
        }

        private boolean hasEvidence() {
            return damageExpiresAtMillis > 0L || targetExpiresAtMillis > 0L;
        }

        private Evidence evidence(long nowMillis) {
            boolean targeting = targetExpiresAtMillis > nowMillis;
            return new Evidence(
                    facts.attackerUuid(),
                    facts.entityType(),
                    damageExpiresAtMillis > nowMillis ? damage : 0.0,
                    Math.max(damageExpiresAtMillis, targetExpiresAtMillis),
                    facts.target(),
                    facts.targetUuid(),
                    facts.targetName(),
                    targeting);
        }
    }
}
