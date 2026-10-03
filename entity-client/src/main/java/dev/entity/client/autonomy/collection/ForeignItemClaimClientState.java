package dev.entity.client.autonomy.collection;

import dev.entity.client.bridge.ForeignItemClaimProtocol;
import dev.entity.core.stewardship.ForeignItemProvenancePolicy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Optional audit mirror. Claim state never changes vanilla ground-item admission. */
public final class ForeignItemClaimClientState {
    private final LinkedHashMap<UUID, ForeignItemClaimProtocol.Claim> claims =
            new LinkedHashMap<>();
    private boolean available;
    private long revision = -1L;
    private String configuredOwnerName = "";

    public synchronized void connectionChanged(boolean connected) {
        revoke(true);
        configuredOwnerName = "";
    }

    public synchronized void installConfiguredOwner(String ownerName) {
        configuredOwnerName = Objects.requireNonNullElse(ownerName, "").trim();
    }

    public synchronized Acceptance accept(ForeignItemClaimProtocol.Snapshot snapshot) {
        ForeignItemClaimProtocol.Snapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        if (!checked.complete()) {
            revision = Math.max(revision, checked.revision());
            revoke(false);
            return new Acceptance(false,
                    "Paper reported an incomplete legacy item-claim snapshot; pickup remains vanilla");
        }
        if (checked.revision() < revision
                || (!available && revision >= 0L && checked.revision() == revision)) {
            revoke(false);
            return new Acceptance(false,
                    "legacy item-claim revision was stale; pickup remains vanilla");
        }

        LinkedHashMap<UUID, ForeignItemClaimProtocol.Claim> replacement =
                new LinkedHashMap<>();
        for (ForeignItemClaimProtocol.Claim claim : checked.claims()) {
            if (replacement.put(claim.entityId(), claim) != null) {
                revoke(false);
                return new Acceptance(false,
                        "duplicate legacy item entity identity; pickup remains vanilla");
            }
        }
        if (available && checked.revision() == revision && !claims.equals(replacement)) {
            revoke(false);
            return new Acceptance(false,
                    "conflicting legacy item snapshot revision; pickup remains vanilla");
        }
        claims.clear();
        claims.putAll(replacement);
        revision = checked.revision();
        available = true;
        return new Acceptance(true, "Installed foreign-item claim revision " + revision
                + " with " + claims.size() + " live claims");
    }

    public synchronized void invalidate() {
        revoke(false);
    }

    private void revoke(boolean resetRevision) {
        available = false;
        if (resetRevision) revision = -1L;
        claims.clear();
    }

    public synchronized Admission decide(UUID entityId) {
        ForeignItemClaimProtocol.Claim claim = claims.get(
                Objects.requireNonNull(entityId, "entityId"));
        boolean ownerHandoff = claim != null
                && !configuredOwnerName.isBlank()
                && claim.ownerName().equalsIgnoreCase(configuredOwnerName);
        ForeignItemProvenancePolicy.Decision decision =
                ForeignItemProvenancePolicy.decide(available, claim != null, ownerHandoff);
        return new Admission(decision, Optional.ofNullable(claim));
    }

    public synchronized Status status() {
        return new Status(available, revision, Map.copyOf(claims));
    }

    public record Acceptance(boolean accepted, String detail) {
        public Acceptance {
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record Admission(
            ForeignItemProvenancePolicy.Decision decision,
            Optional<ForeignItemClaimProtocol.Claim> claim) {
        public Admission {
            decision = Objects.requireNonNull(decision, "decision");
            claim = Objects.requireNonNull(claim, "claim");
        }

        public boolean allowed() {
            return decision.allowed();
        }
    }

    public record Status(
            boolean available,
            long revision,
            Map<UUID, ForeignItemClaimProtocol.Claim> claims) {
        public Status {
            claims = Map.copyOf(Objects.requireNonNull(claims, "claims"));
        }
    }
}
