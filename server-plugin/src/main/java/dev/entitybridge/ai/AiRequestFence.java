package dev.entitybridge.ai;

import java.util.Objects;
import java.util.UUID;

/** Admission state belongs to the server thread, never to an inference worker. */
public final class AiRequestFence {
    public record Ticket(UUID id, UUID requester, UUID world, String session, long generation, long expiresAt) {}
    private long generation;
    private Ticket pending;

    public Ticket begin(UUID requester, UUID world, String session, long now) {
        invalidate();
        pending = new Ticket(UUID.randomUUID(), Objects.requireNonNull(requester),
                Objects.requireNonNull(world), Objects.requireNonNull(session), generation, now + 90_000);
        return pending;
    }

    public void invalidate() { generation++; pending = null; }

    public boolean consume(Ticket ticket, UUID requester, UUID world, String session,
                           boolean authorized, long now) {
        boolean valid = pending != null && pending.equals(ticket) && ticket.generation() == generation
                && ticket.requester().equals(requester) && ticket.world().equals(world)
                && ticket.session().equals(session) && authorized && now <= ticket.expiresAt();
        // An old callback must not consume a newer pending request.
        if (Objects.equals(pending, ticket)) pending = null;
        return valid;
    }
}
