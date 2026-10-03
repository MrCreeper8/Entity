package dev.entity.client.runtime;

import java.util.Objects;
import java.util.Optional;

/**
 * Fences every world-owned actuator until the authenticated Paper save matches
 * the directory used to construct the runtime.
 */
public final class WorldScopeGate {
    private final WorldStateScope expected;
    private WorldStateScope requested;
    private boolean verified;
    private String detail = "waiting for Paper's authoritative Overworld UUID";

    public WorldScopeGate(WorldStateScope expected) {
        this.expected = expected;
    }

    public synchronized Observation observe(String overworldIdentity) {
        final WorldStateScope observed;
        try {
            observed = WorldStateScope.parse(overworldIdentity);
        } catch (IllegalArgumentException invalid) {
            verified = false;
            requested = null;
            detail = "Paper did not provide a valid authoritative Overworld UUID";
            return new Observation(Action.HOLD, null, detail);
        }
        if (observed.equals(expected)) {
            verified = true;
            requested = null;
            detail = "world state verified for " + observed.key();
            return new Observation(Action.VERIFIED, observed, detail);
        }
        verified = false;
        requested = observed;
        detail = expected == null
                ? "binding Entity to Paper world " + observed.key()
                : "Paper world changed from " + expected.key() + " to " + observed.key();
        return new Observation(Action.REBIND, observed, detail);
    }

    public synchronized void disconnected() {
        verified = false;
        requested = null;
        detail = "waiting for an authenticated Paper world identity";
    }

    public synchronized boolean verified() {
        return verified;
    }

    /** Current authenticated runtime scope; unlike requestedScope this is not a pending rebind. */
    public synchronized Optional<WorldStateScope> verifiedScope() {
        return verified ? Optional.ofNullable(expected) : Optional.empty();
    }

    /** A different world requiring runtime reconstruction; deliberately empty after successful binding. */
    public synchronized Optional<WorldStateScope> requestedScope() {
        return Optional.ofNullable(requested);
    }

    public synchronized String detail() {
        return detail;
    }

    public enum Action {
        HOLD,
        VERIFIED,
        REBIND
    }

    public record Observation(Action action, WorldStateScope scope, String detail) {
        public Observation {
            action = Objects.requireNonNull(action, "action");
            detail = Objects.requireNonNullElse(detail, "");
            if ((action == Action.VERIFIED || action == Action.REBIND) && scope == null) {
                throw new IllegalArgumentException("verified/rebind observations require a scope");
            }
        }
    }
}
