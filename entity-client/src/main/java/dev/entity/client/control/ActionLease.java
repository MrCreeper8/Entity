package dev.entity.client.control;

import dev.entity.core.control.BodyChannel;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Unforgeable authority for one client actuator.
 *
 * <p>An action lease is bound to the stable identity of its parent core lease:
 * token, epoch, owner, and channels. A normal same-token TTL renewal remains
 * transparent, while replacement or invalidation of the parent immediately
 * makes this capability stale. The current parent is still checked at every
 * final write boundary, so this token cannot outlive that parent.</p>
 */
public record ActionLease(
        UUID token,
        long generation,
        ActionOwner owner,
        String operationId,
        UUID parentToken,
        long parentEpoch,
        String parentOwner,
        Set<BodyChannel> parentChannels,
        long grantedAtTick) {

    public ActionLease {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(parentToken, "parentToken");
        operationId = Objects.requireNonNull(operationId, "operationId").trim();
        parentOwner = Objects.requireNonNull(parentOwner, "parentOwner").trim();
        parentChannels = Set.copyOf(Objects.requireNonNull(parentChannels, "parentChannels"));
        if (operationId.isEmpty()) {
            throw new IllegalArgumentException("operationId cannot be blank");
        }
        if (parentOwner.isEmpty()) {
            throw new IllegalArgumentException("parentOwner cannot be blank");
        }
        if (parentChannels.isEmpty()) {
            throw new IllegalArgumentException("parentChannels cannot be empty");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (grantedAtTick < 0) {
            throw new IllegalArgumentException("grantedAtTick cannot be negative");
        }
    }
}
