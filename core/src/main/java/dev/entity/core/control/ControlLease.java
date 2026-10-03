package dev.entity.core.control;

import java.util.Set;
import java.util.UUID;

/** Capability token required before an adapter may touch Minecraft controls. */
public record ControlLease(
        UUID token,
        long epoch,
        String owner,
        int priority,
        Set<BodyChannel> channels,
        long issuedAtMillis,
        long expiresAtMillis) {

    public ControlLease {
        channels = Set.copyOf(channels);
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("A lease must own at least one body channel");
        }
        if (expiresAtMillis <= issuedAtMillis) {
            throw new IllegalArgumentException("A lease must expire after it is issued");
        }
    }
}
