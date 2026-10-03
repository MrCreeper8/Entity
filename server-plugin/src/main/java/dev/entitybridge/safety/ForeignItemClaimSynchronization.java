package dev.entitybridge.safety;

import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Connection-scoped mirror of currently loaded, Paper-tagged item entities. */
public final class ForeignItemClaimSynchronization {
    private final Predicate<JsonObject> sender;
    private final LinkedHashMap<ForeignItemClaim.Key, ForeignItemClaim> claims =
            new LinkedHashMap<>();
    private boolean connected;
    private boolean sourceComplete = true;
    private long revision;

    public ForeignItemClaimSynchronization(Predicate<JsonObject> sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public synchronized void disconnect() {
        connected = false;
        claims.clear();
        sourceComplete = false;
    }

    public synchronized boolean connect(
            Collection<ForeignItemClaim> loadedClaims,
            boolean complete) {
        connected = true;
        replace(loadedClaims, complete);
        return publishCurrent();
    }

    public synchronized boolean replaceLoaded(
            Collection<ForeignItemClaim> loadedClaims,
            boolean complete) {
        boolean changed = replace(loadedClaims, complete);
        return !connected || !changed || publishCurrent();
    }

    public synchronized boolean upsert(ForeignItemClaim claim) {
        ForeignItemClaim checked = Objects.requireNonNull(claim, "claim");
        ForeignItemClaim prior = claims.put(checked.key(), checked);
        if (checked.equals(prior)) return true;
        revision = nextRevision(revision);
        return !connected || publishCurrent();
    }

    public synchronized boolean markSourceIncomplete() {
        if (!sourceComplete) return true;
        sourceComplete = false;
        revision = nextRevision(revision);
        return !connected || publishCurrent();
    }

    public synchronized boolean remove(ForeignItemClaim.Key key) {
        if (claims.remove(Objects.requireNonNull(key, "key")) == null) return true;
        revision = nextRevision(revision);
        return !connected || publishCurrent();
    }

    public synchronized Status status() {
        return new Status(connected, sourceComplete, revision, Map.copyOf(claims));
    }

    private boolean replace(Collection<ForeignItemClaim> loadedClaims, boolean complete) {
        LinkedHashMap<ForeignItemClaim.Key, ForeignItemClaim> replacement =
                new LinkedHashMap<>();
        for (ForeignItemClaim claim : Objects.requireNonNull(loadedClaims, "loadedClaims")) {
            ForeignItemClaim previous = replacement.put(claim.key(), claim);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate foreign-item claim key");
            }
        }
        if (claims.equals(replacement) && sourceComplete == complete) return false;
        claims.clear();
        claims.putAll(replacement);
        sourceComplete = complete;
        revision = nextRevision(revision);
        return true;
    }

    private boolean publishCurrent() {
        return sender.test(ForeignItemClaimProtocol.snapshotFrame(
                revision, claims.values(), sourceComplete));
    }

    private static long nextRevision(long revision) {
        return revision == Long.MAX_VALUE ? 0L : revision + 1L;
    }

    public record Status(
            boolean connected,
            boolean sourceComplete,
            long revision,
            Map<ForeignItemClaim.Key, ForeignItemClaim> claims) {
        public Status {
            claims = Map.copyOf(Objects.requireNonNull(claims, "claims"));
        }
    }
}
