package dev.entity.client.protection;

import com.google.gson.JsonObject;
import dev.entity.client.bridge.ProtectedAreaPolicyProtocol;
import dev.entity.core.stewardship.ProtectedAreaHomePermit;
import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Client-side fail-closed view of the last authenticated Paper policy. */
public final class ProtectedAreaClientState {
    private final ProtectedAreaClientStore store;
    private ProtectedAreaPolicy.Snapshot snapshot;
    private boolean synchronizedPolicy;
    /** Scalar authority boundary keeps authenticated policy independent of Minecraft classes. */
    @FunctionalInterface
    public interface BlueprintAuthority {
        boolean includes(ProtectedAreaPolicy.Action action, String dimension, int x, int y, int z);
    }
    private BlueprintAuthority blueprintScope;

    public synchronized void blueprintScope(BlueprintAuthority scope) {
        blueprintScope = scope;
    }
    private boolean cacheFailClosed;
    private String warning;
    private Predicate<JsonObject> homePermitSender = ignored -> false;
    private final Map<String, PendingHomePermit> homePermits = new LinkedHashMap<>();

    public ProtectedAreaClientState(ProtectedAreaClientStore store) {
        this.store = Objects.requireNonNull(store, "store");
        ProtectedAreaClientStore.LoadResult loaded = store.load();
        this.snapshot = loaded.update() == null
                ? ProtectedAreaPolicy.empty()
                : loaded.update().snapshot();
        this.cacheFailClosed = loaded.failClosed();
        this.warning = loaded.warning();
        // A cached snapshot is diagnostic truth, never connection-scoped authority.
        this.synchronizedPolicy = false;
    }

    public synchronized Acceptance accept(ProtectedAreaPolicyProtocol.Update update) {
        Objects.requireNonNull(update, "update");
        ProtectedAreaPolicy.Snapshot incoming = update.snapshot();
        synchronizedPolicy = false;
        homePermits.clear();
        if (update.serverFailClosed()) {
            snapshot = incoming;
            warning = "Paper reported a fail-closed protected-area store";
            return Acceptance.rejected(warning);
        }
        if (!cacheFailClosed && incoming.revision() < snapshot.revision()) {
            warning = "Paper offered protected-area revision " + incoming.revision()
                    + " behind cached revision " + snapshot.revision();
            return Acceptance.rejected(warning);
        }
        if (!cacheFailClosed && incoming.revision() == snapshot.revision()
                && !incoming.digest().equals(snapshot.digest())) {
            warning = "Paper reused protected-area revision " + incoming.revision()
                    + " with a different digest";
            return Acceptance.rejected(warning);
        }
        try {
            store.save(update);
        } catch (IOException error) {
            cacheFailClosed = true;
            warning = "Could not durably install protected-area policy: " + error.getMessage();
            return Acceptance.rejected(warning);
        }
        snapshot = incoming;
        cacheFailClosed = false;
        warning = "";
        synchronizedPolicy = true;
        return Acceptance.accepted(
                ProtectedAreaPolicyProtocol.acknowledgement(incoming),
                "installed exact protected-area revision " + incoming.revision());
    }

    public synchronized void connectionChanged(boolean connected) {
        blueprintScope = null;
        // Both disconnect and a fresh authenticated session revoke the old
        // connection's acknowledgement until a policy frame is installed.
        synchronizedPolicy = false;
        homePermits.clear();
        if (!connected && warning.isBlank()) {
            warning = "Protected-area policy is waiting for an authenticated Paper connection";
        }
    }

    public synchronized ProtectedAreaPolicy.Decision decide(
            ProtectedAreaPolicy.Action action,
            String dimension,
            int x,
            int y,
            int z,
            boolean exactOwnedHomeAction) {
        if (synchronizedPolicy && !cacheFailClosed && blueprintScope != null
                && blueprintScope.includes(action,dimension,x,y,z)) {
            return new ProtectedAreaPolicy.Decision(true,"blueprint_project",
                    "Exact confirmed active construction cell","",dimension,x,y,z);
        }
        return ProtectedAreaPolicy.decide(
                snapshot,
                synchronizedPolicy && !cacheFailClosed,
                action,
                dimension,
                x,
                y,
                z,
                exactOwnedHomeAction);
    }

    public synchronized ProtectedAreaPolicy.Decision decideReversiblePassage(
            String dimension,
            int x,
            int y,
            int z) {
        return ProtectedAreaPolicy.decideReversiblePassage(
                snapshot,
                synchronizedPolicy && !cacheFailClosed,
                ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                dimension, x, y, z);
    }

    /** Installs the authenticated bridge sender used for exact Home authority. */
    public synchronized void installHomePermitSender(Predicate<JsonObject> sender) {
        homePermitSender = Objects.requireNonNull(sender, "sender");
        homePermits.clear();
    }

    /**
     * Requests or observes one short-lived exact Home capability. Outside an
     * active area no wire permit is needed; inside, actuation waits for Paper's
     * exact revision/action/coordinate acknowledgement.
     */
    public synchronized HomeAuthorization requestExactHomeAuthorization(
            String operationId,
            String authorityId,
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        return requestExactAuthorization(
                operationId,
                authorityId,
                ProtectedAreaHomePermit.Purpose.HOME_MAINTENANCE,
                action,
                targets,
                nowMillis);
    }

    /** Requests one exact reversible portal interaction and nothing else. */
    public synchronized HomeAuthorization requestExactPassageAuthorization(
            String operationId,
            String authorityId,
            List<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        return requestExactAuthorization(
                operationId,
                authorityId,
                ProtectedAreaHomePermit.Purpose.REVERSIBLE_PASSAGE,
                ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                targets,
                nowMillis);
    }

    private HomeAuthorization requestExactAuthorization(
            String operationId,
            String authorityId,
            ProtectedAreaHomePermit.Purpose purpose,
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        List<ProtectedAreaHomePermit.Target> exactTargets = List.copyOf(targets);
        if (exactTargets.isEmpty()) {
            return HomeAuthorization.denied("exact authority needs a target");
        }
        List<ProtectedAreaHomePermit.Target> protectedTargets = new ArrayList<>();
        for (ProtectedAreaHomePermit.Target target : exactTargets) {
            ProtectedAreaPolicy.Decision decision = purpose
                    == ProtectedAreaHomePermit.Purpose.REVERSIBLE_PASSAGE
                    ? ProtectedAreaPolicy.decideReversiblePassage(
                            snapshot,
                            synchronizedPolicy && !cacheFailClosed,
                            action,
                            target.dimension(), target.x(), target.y(), target.z())
                    : ProtectedAreaPolicy.decide(
                            snapshot,
                            synchronizedPolicy && !cacheFailClosed,
                            action,
                            target.dimension(), target.x(), target.y(), target.z(),
                            purpose == ProtectedAreaHomePermit.Purpose.HOME_MAINTENANCE
                                    && authorityId != null
                                    && authorityId.startsWith("home-asset:home:"));
            if (!decision.allowed()) return HomeAuthorization.denied(decision.detail());
            if (snapshot.firstContaining(
                    target.dimension(), target.x(), target.z()).isPresent()) {
                protectedTargets.add(target);
            }
        }
        if (protectedTargets.isEmpty()) {
            return HomeAuthorization.granted("outside protected property; no permit needed");
        }
        List<ProtectedAreaHomePermit.Target> permitTargets = List.copyOf(protectedTargets);

        String operation = Objects.requireNonNullElse(operationId, "").trim();
        PendingHomePermit pending = homePermits.get(operation);
        if (pending != null && pending.matches(authorityId, purpose, action, permitTargets,
                snapshot.revision(), snapshot.digest())) {
            if (pending.expiresAtMillis() >= nowMillis) {
                return switch (pending.state()) {
                    case WAITING -> HomeAuthorization.waiting(
                            "waiting for Paper's exact permit");
                    case GRANTED -> HomeAuthorization.granted(
                            "Paper granted exact authority");
                    case DENIED -> HomeAuthorization.denied(pending.detail());
                };
            }
            homePermits.remove(operation);
        } else if (pending != null) {
            homePermits.remove(operation);
        }

        ProtectedAreaHomePermit.Request request;
        try {
            request = new ProtectedAreaHomePermit.Request(
                    UUID.randomUUID().toString(),
                    operation,
                    authorityId,
                    purpose,
                    action,
                    snapshot.revision(),
                    snapshot.digest(),
                    permitTargets);
        } catch (IllegalArgumentException invalid) {
            return HomeAuthorization.denied(invalid.getMessage());
        }
        PendingHomePermit waiting = new PendingHomePermit(
                request, PermitState.WAITING, nowMillis + 3_000L,
                "waiting for Paper's exact permit");
        homePermits.put(operation, waiting);
        if (!homePermitSender.test(
                ProtectedAreaPolicyProtocol.homePermitRequest(request))) {
            homePermits.remove(operation);
            return HomeAuthorization.denied(
                    "could not send exact permit request to Paper");
        }
        return HomeAuthorization.waiting("requested exact authority from Paper");
    }

    public synchronized boolean acceptHomePermitResult(
            ProtectedAreaHomePermit.Result result,
            long nowMillis) {
        Objects.requireNonNull(result, "result");
        Map.Entry<String, PendingHomePermit> found = homePermits.entrySet().stream()
                .filter(entry -> entry.getValue().request().permitId()
                        .equals(result.permitId()))
                .findFirst().orElse(null);
        if (found == null) return false;
        PendingHomePermit pending = found.getValue();
        if (pending.request().revision() != result.revision()
                || !pending.request().digest().equals(result.digest())
                || result.revision() != snapshot.revision()
                || !result.digest().equals(snapshot.digest())) {
            homePermits.remove(found.getKey());
            return false;
        }
        if (!result.accepted() || result.expiresAtMillis() <= nowMillis) {
            homePermits.put(found.getKey(), new PendingHomePermit(
                    pending.request(), PermitState.DENIED,
                    Math.max(nowMillis + 1_000L, result.expiresAtMillis()),
                    result.detail().isBlank() ? result.code() : result.detail()));
            return true;
        }
        homePermits.put(found.getKey(), new PendingHomePermit(
                pending.request(), PermitState.GRANTED,
                result.expiresAtMillis(), result.detail()));
        return true;
    }

    /** Called by the interaction mixin immediately before the physical packet. */
    public synchronized boolean hasGrantedExactHomeAuthorization(
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        return grantedExactHomeCapability(action, targets, nowMillis).isPresent();
    }

    /**
     * Captures the acknowledged permit as an immutable capability for one
     * synchronous Minecraft call. This avoids re-discovering mutable permit
     * state from inside nested interaction-manager mixins.
     */
    public synchronized Optional<ExactHomeCapability> grantedExactHomeCapability(
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        return grantedExactCapability(
                ProtectedAreaHomePermit.Purpose.HOME_MAINTENANCE,
                action,
                targets,
                nowMillis);
    }

    /** Captures only a reversible-passage permit, never a Home permit. */
    public synchronized Optional<ExactHomeCapability> grantedExactPassageCapability(
            List<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        return grantedExactCapability(
                ProtectedAreaHomePermit.Purpose.REVERSIBLE_PASSAGE,
                ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                targets,
                nowMillis);
    }

    private Optional<ExactHomeCapability> grantedExactCapability(
            ProtectedAreaHomePermit.Purpose purpose,
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targets, "targets");
        if (!synchronizedPolicy || cacheFailClosed) return Optional.empty();
        List<ProtectedAreaHomePermit.Target> exactTargets = targets.stream()
                .map(target -> Objects.requireNonNull(target, "target"))
                .filter(target -> snapshot.firstContaining(
                        target.dimension(), target.x(), target.z()).isPresent())
                .toList();
        if (exactTargets.isEmpty()) return Optional.empty();
        homePermits.values().removeIf(pending ->
                pending.expiresAtMillis() < nowMillis
                        || pending.request().revision() != snapshot.revision()
                        || !pending.request().digest().equals(snapshot.digest()));
        return homePermits.values().stream().filter(pending ->
                pending.state() == PermitState.GRANTED
                        && pending.request().purpose() == purpose
                        && pending.request().action() == action
                        && pending.request().targets().containsAll(exactTargets))
                .map(pending -> new ExactHomeCapability(
                        pending.request().permitId(),
                        pending.request().purpose(),
                        action,
                        exactTargets,
                        snapshot.revision(),
                        snapshot.digest(),
                        pending.expiresAtMillis()))
                .findFirst();
    }

    /** Drops client-side authority immediately after the one physical attempt. */
    public synchronized void completeExactHomeAuthorization(String operationId) {
        homePermits.remove(Objects.requireNonNullElse(operationId, "").trim());
    }

    public synchronized Status status() {
        return new Status(
                synchronizedPolicy && !cacheFailClosed,
                snapshot.revision(),
                snapshot.digest(),
                snapshot.protectedAreas().size(),
                snapshot.areasOfKind(ProtectedAreaPolicy.AreaKind.MINING).size(),
                snapshot.areasOfKind(ProtectedAreaPolicy.AreaKind.HARVESTING).size(),
                cacheFailClosed,
                warning);
    }

    /**
     * Typed, fail-closed geometry query for a later mining/harvesting classifier.
     * It does not turn a resource footprint into ordinary mutation authority.
     */
    public synchronized ProtectedAreaPolicy.ResourceAuthority observeResourceAuthority(
            ProtectedAreaPolicy.AreaKind kind,
            String dimension,
            int x,
            int y,
            int z) {
        return ProtectedAreaPolicy.decideResourceAuthority(
                snapshot,
                synchronizedPolicy && !cacheFailClosed,
                kind,
                dimension,
                x,
                y,
                z);
    }

    /** Immutable typed geometry for planners; availability still fences actuation. */
    public synchronized ResourceAreasObservation observeResourceAreas(
            ProtectedAreaPolicy.AreaKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.resource()) {
            throw new IllegalArgumentException("resource area kind is required");
        }
        boolean available = synchronizedPolicy && !cacheFailClosed;
        String detail = available
                ? "authenticated area-policy revision " + snapshot.revision()
                : warning.isBlank()
                ? "area policy is not synchronized"
                : warning;
        return new ResourceAreasObservation(
                available,
                snapshot.revision(),
                snapshot.digest(),
                kind,
                snapshot.areasOfKind(kind),
                detail);
    }

    /** Exact authenticated resource-area binding for a named finite operation. */
    public synchronized NamedResourceAreaObservation observeNamedResourceArea(
            ProtectedAreaPolicy.AreaKind kind,
            String name) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.resource()) {
            throw new IllegalArgumentException("resource area kind is required");
        }
        String normalized = Objects.requireNonNullElse(name, "")
                .trim().toLowerCase(java.util.Locale.ROOT);
        boolean available = synchronizedPolicy && !cacheFailClosed;
        Optional<ProtectedAreaPolicy.Area> area = available
                ? snapshot.named(normalized).filter(candidate -> candidate.kind() == kind)
                : Optional.empty();
        String detail = !available
                ? warning.isBlank() ? "area policy is not synchronized" : warning
                : area.isPresent()
                ? "authenticated " + kind.label() + " area '" + normalized + "'"
                : "no " + kind.label() + " area named '" + normalized + "'";
        return new NamedResourceAreaObservation(
                available, snapshot.revision(), snapshot.digest(), kind,
                normalized, area, detail);
    }

    /** Read-only loaded-session property fact for non-actuating safety policy. */
    public synchronized PropertyObservation observeProperty(
            String dimension,
            int x,
            int z) {
        String observedDimension = Objects.requireNonNullElse(dimension, "").trim();
        if (!synchronizedPolicy || cacheFailClosed) {
            String detail = warning.isBlank()
                    ? "protected-area policy is not synchronized"
                    : warning;
            return new PropertyObservation(false, false, "", detail);
        }
        Optional<ProtectedAreaPolicy.Area> containing = snapshot.firstContaining(
                observedDimension, x, z);
        return containing
                .map(area -> new PropertyObservation(
                        true, true, area.name(),
                        "inside protected area '" + area.name() + "'"))
                .orElseGet(() -> new PropertyObservation(
                        true, false, "", "outside named protected property"));
    }

    /**
     * Returns immutable area geometry plus whether it belongs to this authenticated
     * connection. Cached rectangles may guide a conservative retreat while unavailable,
     * but callers must not treat them as permission to approach or attack.
     */
    public synchronized PropertyRegionsObservation observePropertyRegions() {
        boolean available = synchronizedPolicy && !cacheFailClosed;
        String detail = available
                ? "authenticated protected-area revision " + snapshot.revision()
                : warning.isBlank()
                ? "protected-area policy is not synchronized"
                : warning;
        return new PropertyRegionsObservation(
                available,
                snapshot.revision(),
                snapshot.digest(),
                snapshot.protectedAreas(),
                detail);
    }

    public record Acceptance(boolean accepted, JsonObject acknowledgement, String detail) {
        private static Acceptance accepted(JsonObject acknowledgement, String detail) {
            return new Acceptance(true, acknowledgement.deepCopy(), detail);
        }

        private static Acceptance rejected(String detail) {
            return new Acceptance(false, null, detail);
        }
    }

    public record Status(
            boolean synchronizedPolicy,
            long revision,
            String digest,
            int protectedAreaCount,
            int miningAreaCount,
            int harvestingAreaCount,
            boolean cacheFailClosed,
            String warning) {
        /** Source-compatible constructor for released protected-only callers. */
        public Status(
                boolean synchronizedPolicy,
                long revision,
                String digest,
                int areaCount,
                boolean cacheFailClosed,
                String warning) {
            this(
                    synchronizedPolicy, revision, digest,
                    areaCount, 0, 0, cacheFailClosed, warning);
        }

        /** Released name retained: ordinary mutation gates count PROTECTED only. */
        public int areaCount() {
            return protectedAreaCount;
        }

        public int totalAreaCount() {
            return protectedAreaCount + miningAreaCount + harvestingAreaCount;
        }
    }

    public record PropertyObservation(
            boolean available,
            boolean protectedProperty,
            String areaName,
            String detail) {
        public PropertyObservation {
            areaName = Objects.requireNonNullElse(areaName, "");
            detail = Objects.requireNonNullElse(detail, "");
            if (!available && protectedProperty) {
                throw new IllegalArgumentException(
                        "unavailable property observation cannot claim containment");
            }
        }
    }

    public record PropertyRegionsObservation(
            boolean available,
            long revision,
            String digest,
            List<ProtectedAreaPolicy.Area> areas,
            String detail) {
        public PropertyRegionsObservation {
            if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
            digest = Objects.requireNonNullElse(digest, "");
            areas = List.copyOf(Objects.requireNonNull(areas, "areas"));
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record ResourceAreasObservation(
            boolean available,
            long revision,
            String digest,
            ProtectedAreaPolicy.AreaKind kind,
            List<ProtectedAreaPolicy.Area> areas,
            String detail) {
        public ResourceAreasObservation {
            if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
            digest = Objects.requireNonNullElse(digest, "");
            kind = Objects.requireNonNull(kind, "kind");
            if (!kind.resource()) throw new IllegalArgumentException("resource kind is required");
            areas = List.copyOf(Objects.requireNonNull(areas, "areas"));
            for (ProtectedAreaPolicy.Area area : areas) {
                if (area.kind() != kind) {
                    throw new IllegalArgumentException(
                            "resource geometry contains another area kind");
                }
            }
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record NamedResourceAreaObservation(
            boolean available,
            long revision,
            String digest,
            ProtectedAreaPolicy.AreaKind kind,
            String name,
            Optional<ProtectedAreaPolicy.Area> area,
            String detail) {
        public NamedResourceAreaObservation {
            if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
            digest = Objects.requireNonNullElse(digest, "");
            kind = Objects.requireNonNull(kind, "kind");
            if (!kind.resource()) throw new IllegalArgumentException("resource kind is required");
            name = Objects.requireNonNullElse(name, "");
            area = Objects.requireNonNull(area, "area");
            if (area.isPresent() && (area.orElseThrow().kind() != kind
                    || !area.orElseThrow().name().equals(name))) {
                throw new IllegalArgumentException("named resource area identity mismatch");
            }
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record ExactHomeCapability(
            String permitId,
            ProtectedAreaHomePermit.Purpose purpose,
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            long revision,
            String digest,
            long expiresAtMillis) {
        public ExactHomeCapability {
            permitId = Objects.requireNonNull(permitId, "permitId");
            purpose = Objects.requireNonNull(purpose, "purpose");
            action = Objects.requireNonNull(action, "action");
            targets = List.copyOf(targets);
            digest = Objects.requireNonNull(digest, "digest");
        }

        public boolean permits(
                ProtectedAreaPolicy.Action attemptedAction,
                List<ProtectedAreaHomePermit.Target> attemptedTargets,
                Status status,
                long nowMillis) {
            return mismatch(attemptedAction, attemptedTargets, status, nowMillis).isEmpty();
        }

        /** A bounded reason for a fail-closed nested interaction rejection. */
        public Optional<String> mismatch(
                ProtectedAreaPolicy.Action attemptedAction,
                List<ProtectedAreaHomePermit.Target> attemptedTargets,
                Status status,
                long nowMillis) {
            if (action != attemptedAction) {
                return Optional.of("action expected=" + action + " actual=" + attemptedAction);
            }
            if (attemptedTargets == null || attemptedTargets.isEmpty()) {
                return Optional.of("no exact attempted target");
            }
            if (!targets.containsAll(attemptedTargets)) {
                return Optional.of("target expected=" + targets + " actual=" + attemptedTargets);
            }
            if (status == null || !status.synchronizedPolicy()) {
                return Optional.of("policy became unsynchronized");
            }
            if (status.revision() != revision || !status.digest().equals(digest)) {
                return Optional.of("policy identity changed from revision " + revision
                        + " to " + status.revision());
            }
            if (expiresAtMillis < nowMillis) {
                return Optional.of("capability expired " + (nowMillis - expiresAtMillis)
                        + " ms before use");
            }
            return Optional.empty();
        }
    }

    public enum HomeAuthorizationState {
        WAITING,
        GRANTED,
        DENIED
    }

    public record HomeAuthorization(HomeAuthorizationState state, String detail) {
        public HomeAuthorization {
            state = Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
        }

        private static HomeAuthorization waiting(String detail) {
            return new HomeAuthorization(HomeAuthorizationState.WAITING, detail);
        }

        private static HomeAuthorization granted(String detail) {
            return new HomeAuthorization(HomeAuthorizationState.GRANTED, detail);
        }

        private static HomeAuthorization denied(String detail) {
            return new HomeAuthorization(HomeAuthorizationState.DENIED, detail);
        }
    }

    private enum PermitState {
        WAITING,
        GRANTED,
        DENIED
    }

    private record PendingHomePermit(
            ProtectedAreaHomePermit.Request request,
            PermitState state,
            long expiresAtMillis,
            String detail) {
        private boolean matches(
                String authorityId,
                ProtectedAreaHomePermit.Purpose purpose,
                ProtectedAreaPolicy.Action action,
                List<ProtectedAreaHomePermit.Target> targets,
                long revision,
                String digest) {
            return request.authorityId().equals(
                    Objects.requireNonNullElse(authorityId, "").trim())
                    && request.purpose() == purpose
                    && request.action() == action
                    && request.targets().equals(targets)
                    && request.revision() == revision
                    && request.digest().equals(digest);
        }
    }
}
