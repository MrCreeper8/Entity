package dev.entity.client.telemetry;

import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns a proven behavior invariant into one bounded, fail-closed product
 * outcome.  The detector/controller that owns a behavior may use at most two
 * local recovery attempts; once an invariant reaches this boundary, Runtime
 * neutralizes every physical actuator and durably blocks the exact work rather
 * than recursively opening a new recovery loop.
 */
public final class BehaviorCircuitBreaker {
    public static final int MAX_RECOVERY_ATTEMPTS = 2;
    private static final int MAX_RETAINED_TRIPS = 64;

    private final LinkedHashSet<String> trippedKeys = new LinkedHashSet<>();
    private final LinkedHashSet<String> fencedCombatTargets = new LinkedHashSet<>();
    private final LinkedHashSet<String> fencedNativeEscapeTargets = new LinkedHashSet<>();

    /** No new retry allowance: the failed body route stays fenced for this live encounter. */
    public void fenceCombatBody(Collection<String> observedTargetIds) {
        observedTargetIds.stream().filter(id -> id != null && !id.isBlank()).forEach(fencedCombatTargets::add);
    }

    public boolean combatBodyFenced(String targetId) { return fencedCombatTargets.contains(targetId); }

    public void fenceNativeProtectionEscape(String targetId) {
        if (targetId != null && !targetId.isBlank()) fencedNativeEscapeTargets.add(targetId);
    }

    public boolean nativeProtectionEscapeFenced(String targetId) {
        return fencedNativeEscapeTargets.contains(targetId);
    }

    public void retainCombatTargets(Collection<String> observedTargetIds) {
        fencedCombatTargets.retainAll(observedTargetIds);
        fencedNativeEscapeTargets.retainAll(observedTargetIds);
    }

    public void clearCombatTargets() { fencedCombatTargets.clear(); fencedNativeEscapeTargets.clear(); }

    public static boolean preservesUnrelatedMission(Scope scope, String missionKind) {
        return scope == Scope.COMBAT_AND_BODY && !"attack".equals(missionKind);
    }

    /** Returns a one-shot trip decision for the exact violation identity. */
    public Decision observe(BehaviorInvariantMonitor.Violation violation) {
        Objects.requireNonNull(violation, "violation");
        String key = key(violation);
        Scope scope = scope(violation.type());
        String reason = "behavior circuit breaker ["
                + violation.type().name().toLowerCase(Locale.ROOT) + ", scope="
                + scope.name().toLowerCase(Locale.ROOT) + "]: " + violation.evidence()
                + "; stopped with no further recovery after the bounded "
                + MAX_RECOVERY_ATTEMPTS + "-attempt ceiling";
        if (trippedKeys.contains(key)) {
            return new Decision(false, key, scope, MAX_RECOVERY_ATTEMPTS, reason);
        }
        while (trippedKeys.size() >= MAX_RETAINED_TRIPS) {
            var iterator = trippedKeys.iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        trippedKeys.add(key);
        return new Decision(true, key, scope, MAX_RECOVERY_ATTEMPTS, reason);
    }

    public int retainedTrips() {
        return trippedKeys.size();
    }

    private static Scope scope(BehaviorInvariantMonitor.ViolationType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case SCAFFOLD_PLACE_BREAK -> Scope.WORLD_MUTATION_AND_NAVIGATION;
            case MINE_RESTART_CHURN, VERTICAL_OSCILLATION -> Scope.NAVIGATION;
            case LAVA_ESCAPE_STALL, HAZARD_REENTRY, SUFFOCATION_ESCAPE_STALL ->
                    Scope.SURVIVAL_BODY;
            case PROTECTION_RETREAT_STALL, SURFACE_PROTECTION_LOOP ->
                    Scope.COMBAT_AND_BODY;
        };
    }

    private static String key(BehaviorInvariantMonitor.Violation violation) {
        return violation.type().name() + '|'
                + normalize(violation.missionId()) + '|'
                + normalize(violation.evidence());
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    public enum Scope {
        WORLD_MUTATION_AND_NAVIGATION,
        NAVIGATION,
        SURVIVAL_BODY,
        COMBAT_AND_BODY
    }

    public record Decision(
            boolean trip,
            String key,
            Scope scope,
            int maximumRecoveryAttempts,
            String reason) {
        public Decision {
            key = normalize(key);
            Objects.requireNonNull(scope, "scope");
            reason = normalize(reason);
            if (key.isBlank() || reason.isBlank()) {
                throw new IllegalArgumentException("circuit-breaker identity and reason are required");
            }
            if (maximumRecoveryAttempts < 0
                    || maximumRecoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
                throw new IllegalArgumentException("recovery ceiling escaped its hard bound");
            }
        }
    }
}
