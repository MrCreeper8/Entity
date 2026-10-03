package dev.entitybridge.stewardship;

import dev.entity.core.stewardship.ProtectedAreaHomePermit;
import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-side, short-lived exact Home capabilities.
 *
 * <p>Every lookup rechecks the current policy identity and synchronization
 * fence. Disconnects, revisions, expiry, action changes, and coordinate
 * changes therefore revoke authority without relying on cleanup timing.</p>
 */
public final class ProtectedAreaHomePermitRegistry {
    public static final long PERMIT_TTL_MILLIS = 3_000L;
    private static final int MAX_ACTIVE_PERMITS = 16;

    private final ProtectedAreaRegistry areas;
    private final ProtectedAreaSynchronization synchronization;
    private final LinkedHashMap<String, ActivePermit> active = new LinkedHashMap<>();

    public ProtectedAreaHomePermitRegistry(
            ProtectedAreaRegistry areas,
            ProtectedAreaSynchronization synchronization) {
        this.areas = Objects.requireNonNull(areas, "areas");
        this.synchronization = Objects.requireNonNull(
                synchronization, "synchronization");
    }

    public synchronized ProtectedAreaHomePermit.Result issue(
            ProtectedAreaHomePermit.Request request,
            long nowMillis) {
        Objects.requireNonNull(request, "request");
        purge(nowMillis);
        ProtectedAreaPolicy.Snapshot snapshot = areas.snapshot();
        if (!synchronization.mutationAuthorityReady()) {
            return rejected(request, snapshot, "policy_unsynchronized",
                    "protected-area policy is not exactly synchronized");
        }
        if (request.revision() != snapshot.revision()
                || !request.digest().equals(snapshot.digest())) {
            return rejected(request, snapshot, "policy_changed",
                    "Home permit requested against a stale protected-area policy");
        }
        for (ProtectedAreaHomePermit.Target target : request.targets()) {
            if (snapshot.firstContaining(target.dimension(), target.x(), target.z()).isEmpty()) {
                return rejected(request, snapshot,
                        request.purpose() == ProtectedAreaHomePermit.Purpose.HOME_MAINTENANCE
                                ? "home_outside_property_required" : "outside_protected_area",
                        "Home permit is unnecessary outside protected property");
            }
            if (request.purpose() == ProtectedAreaHomePermit.Purpose.HOME_MAINTENANCE
                    && (!request.authorityId().startsWith("home-asset:home:")
                    || !ProtectedAreaPolicy.decide(snapshot, true, request.action(),
                    target.dimension(), target.x(), target.y(), target.z(), true).allowed())) {
                return rejected(request, snapshot, "home_outside_property_required",
                        "Only exact registered furniture in an Entity base is eligible; "
                                + "strict player property and fluids remain denied");
            }
        }
        active.values().removeIf(permit ->
                permit.request().operationId().equals(request.operationId()));
        while (active.size() >= MAX_ACTIVE_PERMITS) {
            Iterator<String> oldest = active.keySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
        long expiresAt = Math.addExact(Math.max(0L, nowMillis), PERMIT_TTL_MILLIS);
        active.put(request.permitId(), new ActivePermit(request, expiresAt));
        return new ProtectedAreaHomePermit.Result(
                request.permitId(), true, snapshot.revision(), snapshot.digest(),
                expiresAt, "accepted", "exact "
                + request.purpose().name().toLowerCase() + " operation authorized");
    }

    /** Returns a matching Home-maintenance permit without consuming it. */
    public synchronized Optional<Match> peek(
            ProtectedAreaPolicy.Action action,
            Collection<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        return match(ProtectedAreaHomePermit.Purpose.HOME_MAINTENANCE,
                action, targets, nowMillis, false);
    }

    /** Consumes one matching Home-maintenance permit at the final effect boundary. */
    public synchronized Optional<Match> consume(
            ProtectedAreaPolicy.Action action,
            Collection<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        return match(ProtectedAreaHomePermit.Purpose.HOME_MAINTENANCE,
                action, targets, nowMillis, true);
    }

    public synchronized Optional<Match> peek(
            ProtectedAreaHomePermit.Purpose purpose,
            ProtectedAreaPolicy.Action action,
            Collection<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        return match(purpose, action, targets, nowMillis, false);
    }

    public synchronized Optional<Match> consume(
            ProtectedAreaHomePermit.Purpose purpose,
            ProtectedAreaPolicy.Action action,
            Collection<ProtectedAreaHomePermit.Target> targets,
            long nowMillis) {
        return match(purpose, action, targets, nowMillis, true);
    }

    public synchronized void clear() {
        active.clear();
    }

    public synchronized int activeCount(long nowMillis) {
        purge(nowMillis);
        return active.size();
    }

    private Optional<Match> match(
            ProtectedAreaHomePermit.Purpose purpose,
            ProtectedAreaPolicy.Action action,
            Collection<ProtectedAreaHomePermit.Target> targets,
            long nowMillis,
            boolean consume) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(action, "action");
        List<ProtectedAreaHomePermit.Target> exactTargets = List.copyOf(targets);
        if (exactTargets.isEmpty() || !synchronization.mutationAuthorityReady()) {
            return Optional.empty();
        }
        purge(nowMillis);
        ProtectedAreaPolicy.Snapshot snapshot = areas.snapshot();
        List<ProtectedAreaHomePermit.Target> protectedTargets = exactTargets.stream()
                .filter(target -> snapshot.firstContaining(
                        target.dimension(), target.x(), target.z()).isPresent())
                .toList();
        if (protectedTargets.isEmpty()) return Optional.empty();
        Iterator<Map.Entry<String, ActivePermit>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ActivePermit> entry = iterator.next();
            ActivePermit candidate = entry.getValue();
            ProtectedAreaHomePermit.Request request = candidate.request();
            if (request.revision() != snapshot.revision()
                    || !request.digest().equals(snapshot.digest())) {
                iterator.remove();
                continue;
            }
            if (request.purpose() != purpose
                    || request.action() != action
                    || !request.targets().containsAll(protectedTargets)) continue;
            Match result = new Match(
                    request.permitId(), request.operationId(), request.authorityId(),
                    request.purpose(), request.action(), request.targets(),
                    candidate.expiresAtMillis());
            if (consume) iterator.remove();
            return Optional.of(result);
        }
        return Optional.empty();
    }

    private void purge(long nowMillis) {
        ProtectedAreaPolicy.Snapshot snapshot = areas.snapshot();
        active.values().removeIf(permit -> permit.expiresAtMillis() < nowMillis
                || permit.request().revision() != snapshot.revision()
                || !permit.request().digest().equals(snapshot.digest()));
    }

    private static ProtectedAreaHomePermit.Result rejected(
            ProtectedAreaHomePermit.Request request,
            ProtectedAreaPolicy.Snapshot snapshot,
            String code,
            String detail) {
        return new ProtectedAreaHomePermit.Result(
                request.permitId(), false, snapshot.revision(), snapshot.digest(),
                0L, code, detail);
    }

    private record ActivePermit(
            ProtectedAreaHomePermit.Request request,
            long expiresAtMillis) {
    }

    public record Match(
            String permitId,
            String operationId,
            String authorityId,
            ProtectedAreaHomePermit.Purpose purpose,
            ProtectedAreaPolicy.Action action,
            List<ProtectedAreaHomePermit.Target> targets,
            long expiresAtMillis) {
        public Match {
            targets = List.copyOf(new ArrayList<>(targets));
        }
    }
}
