package dev.entity.core.control;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Atomic control ownership with a global cancellation epoch. A higher-priority
 * claimant invalidates every older lease, so a preempted task cannot keep
 * pressing a stale key through another channel.
 */
public final class BodyArbiter {
    private final Map<BodyChannel, UUID> owners = new EnumMap<>(BodyChannel.class);
    private final Map<UUID, ControlLease> leases = new HashMap<>();
    private long epoch = 1;

    public synchronized Optional<ControlLease> acquire(
            String owner,
            int priority,
            Set<BodyChannel> requestedChannels,
            long nowMillis,
            long ttlMillis) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank() || requestedChannels == null || requestedChannels.isEmpty() || ttlMillis <= 0) {
            throw new IllegalArgumentException("Invalid control lease request");
        }
        purgeExpired(nowMillis);
        EnumSet<BodyChannel> requested = EnumSet.copyOf(requestedChannels);

        int strongestConflict = Integer.MIN_VALUE;
        boolean foreignConflict = false;
        for (BodyChannel channel : requested) {
            ControlLease existing = leaseFor(channel);
            if (existing != null && !existing.owner().equals(owner)) {
                foreignConflict = true;
                strongestConflict = Math.max(strongestConflict, existing.priority());
            }
        }
        if (foreignConflict && priority <= strongestConflict) {
            return Optional.empty();
        }
        if (foreignConflict) {
            invalidateAll();
        } else {
            releaseOwnedBy(owner);
        }

        ControlLease lease = new ControlLease(
                UUID.randomUUID(),
                epoch,
                owner,
                priority,
                requested,
                nowMillis,
                Math.addExact(nowMillis, ttlMillis));
        leases.put(lease.token(), lease);
        for (BodyChannel channel : requested) {
            owners.put(channel, lease.token());
        }
        return Optional.of(lease);
    }

    /**
     * Atomically extends a lease that still owns exactly the channels recorded
     * in the supplied capability. The renewed capability keeps its token and
     * epoch, but replaces the stored value so every older copy immediately
     * becomes stale.
     *
     * <p>Renewal deliberately fails at (rather than after) the expiry
     * boundary. A caller that missed its window must acquire again, which
     * re-runs conflict arbitration instead of reviving ownership that may
     * already have been granted elsewhere.</p>
     */
    public synchronized Optional<ControlLease> renew(
            ControlLease lease,
            long nowMillis,
            long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("Lease renewal TTL must be positive");
        }
        if (lease == null) {
            return Optional.empty();
        }
        purgeExpired(nowMillis);
        if (lease.epoch() != epoch) {
            return Optional.empty();
        }
        ControlLease current = leases.get(lease.token());
        if (!lease.equals(current)
                || !lease.channels().stream()
                .allMatch(channel -> lease.token().equals(owners.get(channel)))) {
            return Optional.empty();
        }

        ControlLease renewed = new ControlLease(
                lease.token(),
                lease.epoch(),
                lease.owner(),
                lease.priority(),
                lease.channels(),
                nowMillis,
                Math.addExact(nowMillis, ttlMillis));
        leases.put(renewed.token(), renewed);
        return Optional.of(renewed);
    }

    public synchronized boolean isValid(ControlLease lease, long nowMillis) {
        if (lease == null || lease.epoch() != epoch || nowMillis >= lease.expiresAtMillis()) {
            return false;
        }
        ControlLease current = leases.get(lease.token());
        if (!lease.equals(current)) {
            return false;
        }
        return lease.channels().stream().allMatch(channel -> lease.token().equals(owners.get(channel)));
    }

    public synchronized boolean release(ControlLease lease) {
        if (lease == null || !lease.equals(leases.get(lease.token()))) {
            return false;
        }
        leases.remove(lease.token());
        owners.entrySet().removeIf(entry -> entry.getValue().equals(lease.token()));
        return true;
    }

    /** Emergency stop/reconnect boundary. All callers must obtain fresh leases. */
    public synchronized long invalidateAll() {
        owners.clear();
        leases.clear();
        return ++epoch;
    }

    public synchronized long epoch() {
        return epoch;
    }

    private ControlLease leaseFor(BodyChannel channel) {
        UUID token = owners.get(channel);
        return token == null ? null : leases.get(token);
    }

    private void purgeExpired(long nowMillis) {
        leases.values().removeIf(lease -> nowMillis >= lease.expiresAtMillis());
        owners.entrySet().removeIf(entry -> !leases.containsKey(entry.getValue()));
    }

    private void releaseOwnedBy(String owner) {
        leases.values().removeIf(lease -> lease.owner().equals(owner));
        owners.entrySet().removeIf(entry -> !leases.containsKey(entry.getValue()));
    }
}
