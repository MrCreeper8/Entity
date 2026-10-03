package dev.entity.client.control;

import dev.entity.core.control.BodyArbiter;
import dev.entity.core.control.BodyChannel;
import dev.entity.core.control.ControlLease;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Exclusive inner-body arbitration beneath the core's layer lease.
 *
 * <p>The core arbitrates survival, protection, and missions. A mission can in
 * turn contain Baritone routing, direct combat, inventory work, or workstation
 * interaction. Those components must not share the body merely because they
 * share a mission lease. This kernel derives exactly one action lease from the
 * current parent lease and inserts an explicit neutralization boundary between
 * different action owners.</p>
 *
 * <p>A caller requests an owner. A new or changed owner first receives
 * {@link State#NEUTRALIZE}; the caller must release physical inputs and call
 * {@link #confirmNeutralized(TransitionTicket, long)}. The requested owner can
 * only receive a lease on a later client tick. Consequently an old and a new
 * actuator can never both act during the transition tick.</p>
 */
public final class ExecutionKernel {
    private static final EnumSet<BodyChannel> EXCLUSIVE_BODY =
            EnumSet.allOf(BodyChannel.class);

    private final BodyArbiter parentArbiter;
    private ActionLease active;
    private Transition transition;
    private long generation;

    public ExecutionKernel(BodyArbiter parentArbiter) {
        this.parentArbiter = Objects.requireNonNull(parentArbiter, "parentArbiter");
    }

    /**
     * Requests exclusive control for one actuator and operation.
     *
     * <p>Repeated requests for the active owner and operation are idempotent.
     * Every other request revokes the old action token immediately and enters
     * the explicit neutralization handshake.</p>
     */
    public synchronized Decision request(
            ControlLease parent,
            ActionOwner owner,
            String operationId,
            long clientTick,
            long nowMillis) {
        Objects.requireNonNull(owner, "owner");
        String operation = normalizeOperation(operationId);
        requireNonNegativeTick(clientTick);

        if (!usableParent(parent, nowMillis)) {
            Optional<ActionOwner> revoked = Optional.ofNullable(active).map(ActionLease::owner);
            beginTransition(null, clientTick, "parent control lease is stale or incomplete");
            return Decision.parentInvalid(
                    transition.ticket,
                    revoked,
                    "parent control lease is stale, expired, invalidated, or does not own the whole body");
        }

        Pending requested = Pending.from(parent, owner, operation);
        if (active != null) {
            if (matches(active, requested)
                    && isValidInternal(active, parent, clientTick, nowMillis)) {
                return Decision.withLease(State.ACTIVE, active, "action owner remains active");
            }
            ActionOwner revoked = active.owner();
            beginTransition(requested, clientTick,
                    "switching from " + revoked + " to " + owner);
            return Decision.neutralize(
                    transition.ticket,
                    Optional.of(revoked),
                    "revoked " + revoked + "; neutralize physical controls before granting " + owner);
        }

        if (transition == null) {
            beginTransition(requested, clientTick, "initial action owner requires a neutral boundary");
            return Decision.neutralize(
                    transition.ticket,
                    Optional.empty(),
                    "neutralize physical controls before granting " + owner);
        }

        if (transition.pending == null) {
            transition.pending = requested;
        } else if (!transition.pending.equals(requested)) {
            beginTransition(requested, clientTick, "pending action owner changed before grant");
            return Decision.neutralize(
                    transition.ticket,
                    Optional.empty(),
                    "pending owner changed; repeat the neutralization boundary before granting " + owner);
        }

        if (!transition.neutralized) {
            return Decision.neutralize(
                    transition.ticket,
                    Optional.empty(),
                    "waiting for explicit physical-control neutralization");
        }
        if (clientTick <= transition.neutralizedAtTick) {
            return Decision.transition(
                    State.WAIT_NEXT_TICK,
                    transition.ticket,
                    "neutralization confirmed; the new owner cannot act until the next client tick");
        }

        // The parent can expire or be invalidated during a multi-tick handoff.
        if (!usableParent(parent, nowMillis) || !transition.pending.matchesParent(parent)) {
            beginTransition(null, clientTick, "parent changed while an action grant was pending");
            return Decision.parentInvalid(
                    transition.ticket,
                    Optional.empty(),
                    "parent control lease changed before the pending action could be granted");
        }

        active = new ActionLease(
                UUID.randomUUID(),
                ++generation,
                owner,
                operation,
                parent.token(),
                parent.epoch(),
                parent.owner(),
                parent.channels(),
                clientTick);
        transition = null;
        return Decision.withLease(State.GRANTED, active, "exclusive action owner granted");
    }

    /** Records that both Baritone and direct Minecraft inputs were neutralized. */
    public synchronized boolean confirmNeutralized(TransitionTicket ticket, long clientTick) {
        Objects.requireNonNull(ticket, "ticket");
        requireNonNegativeTick(clientTick);
        if (transition == null || !transition.ticket.equals(ticket)
                || clientTick < ticket.requestedAtTick()) {
            return false;
        }
        transition.neutralized = true;
        transition.neutralizedAtTick = clientTick;
        return true;
    }

    /**
     * Releases the exact live action token. A stale token cannot release a
     * newer action, even if owner and operation text happen to match.
     */
    public synchronized Decision release(ActionLease lease, long clientTick, String reason) {
        requireNonNegativeTick(clientTick);
        if (lease == null || active == null || !active.equals(lease)) {
            return Decision.simple(State.STALE_TOKEN,
                    "stale action token cannot release the current owner");
        }
        ActionOwner revoked = active.owner();
        beginTransition(null, clientTick, Objects.requireNonNullElse(reason, "action released"));
        return Decision.neutralize(
                transition.ticket,
                Optional.of(revoked),
                "released " + revoked + "; neutralize physical controls");
    }

    /** Emergency, death, reconnect, or parent-layer cancellation boundary. */
    public synchronized TransitionTicket invalidateAll(long clientTick, String reason) {
        requireNonNegativeTick(clientTick);
        beginTransition(null, clientTick, Objects.requireNonNullElse(reason, "all actions invalidated"));
        return transition.ticket;
    }

    /** Validates the exact derived token against both the kernel and its live parent. */
    public synchronized boolean isValid(
            ActionLease lease,
            ControlLease parent,
            long clientTick,
            long nowMillis) {
        return isValidInternal(lease, parent, clientTick, nowMillis);
    }

    /** Fails closed at the final Minecraft-write boundary. */
    public synchronized void requireValid(
            ActionLease lease,
            ControlLease parent,
            long clientTick,
            long nowMillis) {
        if (!isValidInternal(lease, parent, clientTick, nowMillis)) {
            throw new IllegalStateException("Rejected Minecraft write with a stale action lease");
        }
    }

    public synchronized Optional<ActionLease> activeLease() {
        return Optional.ofNullable(active);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                Optional.ofNullable(active),
                transition == null ? Optional.empty() : Optional.of(transition.ticket),
                transition != null && transition.neutralized,
                transition == null ? Long.MIN_VALUE : transition.neutralizedAtTick,
                transition == null || transition.pending == null
                        ? Optional.empty() : Optional.of(transition.pending.owner),
                transition == null ? "" : transition.reason);
    }

    private boolean isValidInternal(
            ActionLease lease,
            ControlLease parent,
            long clientTick,
            long nowMillis) {
        if (lease == null || parent == null || active == null || transition != null) return false;
        if (!active.equals(lease) || clientTick < lease.grantedAtTick()) return false;
        return usableParent(parent, nowMillis)
                && lease.parentToken().equals(parent.token())
                && lease.parentEpoch() == parent.epoch()
                && lease.parentOwner().equals(parent.owner())
                && lease.parentChannels().equals(parent.channels());
    }

    private boolean usableParent(ControlLease parent, long nowMillis) {
        return parent != null
                && parent.channels().containsAll(EXCLUSIVE_BODY)
                && parentArbiter.isValid(parent, nowMillis);
    }

    private void beginTransition(Pending pending, long clientTick, String reason) {
        active = null;
        transition = new Transition(
                new TransitionTicket(UUID.randomUUID(), ++generation, clientTick),
                pending,
                Objects.requireNonNullElse(reason, "action owner transition"));
    }

    private static boolean matches(ActionLease lease, Pending pending) {
        return lease.owner() == pending.owner
                && lease.operationId().equals(pending.operationId)
                && lease.parentToken().equals(pending.parentToken)
                && lease.parentEpoch() == pending.parentEpoch
                && lease.parentOwner().equals(pending.parentOwner)
                && lease.parentChannels().equals(pending.parentChannels);
    }

    private static String normalizeOperation(String operationId) {
        String normalized = Objects.requireNonNull(operationId, "operationId").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("operationId cannot be blank");
        return normalized;
    }

    private static void requireNonNegativeTick(long clientTick) {
        if (clientTick < 0) throw new IllegalArgumentException("clientTick cannot be negative");
    }

    public enum State {
        ACTIVE,
        GRANTED,
        NEUTRALIZE,
        WAIT_NEXT_TICK,
        PARENT_INVALID,
        STALE_TOKEN
    }

    public record TransitionTicket(UUID token, long generation, long requestedAtTick) {
        public TransitionTicket {
            Objects.requireNonNull(token, "token");
            if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
            if (requestedAtTick < 0) throw new IllegalArgumentException("requestedAtTick cannot be negative");
        }
    }

    public record Decision(
            State state,
            Optional<ActionLease> lease,
            Optional<TransitionTicket> transition,
            Optional<ActionOwner> revokedOwner,
            String detail) {
        public Decision {
            Objects.requireNonNull(state, "state");
            lease = Objects.requireNonNull(lease, "lease");
            transition = Objects.requireNonNull(transition, "transition");
            revokedOwner = Objects.requireNonNull(revokedOwner, "revokedOwner");
            detail = Objects.requireNonNullElse(detail, "");
        }

        private static Decision withLease(State state, ActionLease lease, String detail) {
            return new Decision(state, Optional.of(lease), Optional.empty(), Optional.empty(), detail);
        }

        private static Decision neutralize(
                TransitionTicket ticket,
                Optional<ActionOwner> revoked,
                String detail) {
            return new Decision(
                    State.NEUTRALIZE, Optional.empty(), Optional.of(ticket), revoked, detail);
        }

        private static Decision transition(State state, TransitionTicket ticket, String detail) {
            return new Decision(
                    state, Optional.empty(), Optional.of(ticket), Optional.empty(), detail);
        }

        private static Decision parentInvalid(
                TransitionTicket ticket,
                Optional<ActionOwner> revoked,
                String detail) {
            return new Decision(
                    State.PARENT_INVALID, Optional.empty(), Optional.of(ticket), revoked, detail);
        }

        private static Decision simple(State state, String detail) {
            return new Decision(state, Optional.empty(), Optional.empty(), Optional.empty(), detail);
        }
    }

    public record Snapshot(
            Optional<ActionLease> active,
            Optional<TransitionTicket> transition,
            boolean neutralized,
            long neutralizedAtTick,
            Optional<ActionOwner> pendingOwner,
            String reason) {
        public Snapshot {
            active = Objects.requireNonNull(active, "active");
            transition = Objects.requireNonNull(transition, "transition");
            pendingOwner = Objects.requireNonNull(pendingOwner, "pendingOwner");
            reason = Objects.requireNonNullElse(reason, "");
        }
    }

    private record Pending(
            ActionOwner owner,
            String operationId,
            UUID parentToken,
            long parentEpoch,
            String parentOwner,
            java.util.Set<BodyChannel> parentChannels) {
        private static Pending from(ControlLease parent, ActionOwner owner, String operationId) {
            return new Pending(
                    owner,
                    operationId,
                    parent.token(),
                    parent.epoch(),
                    parent.owner(),
                    parent.channels());
        }

        private boolean matchesParent(ControlLease parent) {
            return parent != null
                    && parentToken.equals(parent.token())
                    && parentEpoch == parent.epoch()
                    && parentOwner.equals(parent.owner())
                    && parentChannels.equals(parent.channels());
        }
    }

    private static final class Transition {
        private final TransitionTicket ticket;
        private Pending pending;
        private final String reason;
        private boolean neutralized;
        private long neutralizedAtTick = Long.MIN_VALUE;

        private Transition(TransitionTicket ticket, Pending pending, String reason) {
            this.ticket = ticket;
            this.pending = pending;
            this.reason = reason;
        }
    }
}
