package dev.entity.client.protection;

import dev.entity.client.autonomy.policy.TacticalCombatPolicy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Episode-scoped target ownership for mission protection combat.
 *
 * <p>The tactical policy remains authoritative for safety and for initial target
 * priority. Once ordinary combat starts, this session keeps the exact UUID so
 * distance jitter cannot restart the pursuit every tick. A retained target gets
 * its own freshly evaluated action/loadout; aggregate crowd pressure and escape
 * remain those of the complete observation.</p>
 */
public final class CombatTargetSession {
    public static final long UNREACHABLE_GRACE_MILLIS = 1_500L;
    public static final long REJECTED_TARGET_COOLDOWN_MILLIS = 5_000L;

    private String lockedTargetId = "";
    private boolean lockedAsBodyBlocker;
    private String unreachableTargetId = "";
    private long unreachableSinceMillis = -1L;
    private String rejectedTargetId = "";
    private long rejectedUntilMillis;

    public TacticalCombatPolicy.Decision decide(
            TacticalCombatPolicy.Observation observation) {
        Objects.requireNonNull(observation, "observation");
        long now = observation.nowMillis();
        if (now >= rejectedUntilMillis) {
            rejectedTargetId = "";
            rejectedUntilMillis = 0L;
        }

        Optional<TacticalCombatPolicy.Threat> retained = find(
                observation.threats(), lockedTargetId);
        if (retained.isEmpty()) {
            clearUnavailableState();
            return acquire(observation, preferred(observation, true));
        }
        // Body-blocker status is geometric, not an acquire-time identity property. Refresh it from
        // the retained mob every sample so ordinary knockback can admit a new current blocker or
        // a mandatory aggregate retreat without erasing exact UUID hysteresis while still close.
        lockedAsBodyBlocker = TacticalCombatPolicy.isActiveBodyBlocker(
                retained.orElseThrow());

        if (lockedTargetId.equals(unreachableTargetId)
                && Math.max(0L, now - unreachableSinceMillis)
                >= UNREACHABLE_GRACE_MILLIS) {
            TacticalCombatPolicy.Decision alternative = preferred(observation, true);
            if (alternative.targetId().isPresent()
                    && !alternative.targetId().orElseThrow().equals(lockedTargetId)) {
                rejectedTargetId = lockedTargetId;
                rejectedUntilMillis = saturatedAdd(now, REJECTED_TARGET_COOLDOWN_MILLIS);
                clearUnavailableState();
                return acquire(observation, alternative);
            }
        }

        TacticalCombatPolicy.Decision aggregate = preferred(observation, false);
        String preferredId = aggregate.targetId().orElse(lockedTargetId);
        if (!preferredId.equals(lockedTargetId)
                && urgentSwitch(aggregate, observation.threats(), preferredId)) {
            clearUnavailableState();
            return acquire(observation, aggregate);
        }
        return focus(observation, aggregate, retained.orElseThrow());
    }

    /** Starts the bounded failover clock for the exact live lock. */
    public void markUnreachable(String targetId, long nowMillis) {
        String normalized = normalize(targetId);
        if (normalized.isEmpty() || !normalized.equals(lockedTargetId)) return;
        if (!normalized.equals(unreachableTargetId)) {
            unreachableTargetId = normalized;
            unreachableSinceMillis = nowMillis;
        }
    }

    /**
     * Clears an earlier no-resolution mark only when the exact live lock has
     * since made authoritative resolution progress.
     *
     * <p>This deliberately preserves both target ownership and the independent
     * cooldown for a target that was already rejected. A successful strike or
     * real closing progress rehabilitates this lock; it is not a new episode.</p>
     */
    public void markResolutionProgress(String targetId) {
        String normalized = normalize(targetId);
        if (normalized.isEmpty()
                || !normalized.equals(lockedTargetId)
                || !normalized.equals(unreachableTargetId)) return;
        clearUnavailableState();
    }

    public String lockedTargetId() {
        return lockedTargetId;
    }

    public void clear() {
        lockedTargetId = "";
        lockedAsBodyBlocker = false;
        clearUnavailableState();
        rejectedTargetId = "";
        rejectedUntilMillis = 0L;
    }

    private TacticalCombatPolicy.Decision acquire(
            TacticalCombatPolicy.Observation observation,
            TacticalCombatPolicy.Decision preferred) {
        lockedTargetId = preferred.targetId().orElse("");
        lockedAsBodyBlocker = find(observation.threats(), lockedTargetId)
                .map(TacticalCombatPolicy::isActiveBodyBlocker)
                .orElse(false);
        clearUnavailableState();
        return preferred;
    }

    private TacticalCombatPolicy.Decision preferred(
            TacticalCombatPolicy.Observation observation,
            boolean omitUnavailable) {
        List<TacticalCombatPolicy.Threat> eligible = observation.threats().stream()
                .filter(threat -> !threat.id().equals(rejectedTargetId))
                .filter(threat -> !omitUnavailable
                        || !threat.id().equals(unreachableTargetId))
                .toList();
        // An empty alternative set is real liveness information. Falling back to the complete
        // list here silently selects the same unreachable/rejected UUID again and makes Runtime
        // unable to distinguish "sole target" from a legitimate failover. The retained-target
        // path above still owns its bounded defensive cooldown and one retry explicitly.
        return TacticalCombatPolicy.decide(copyWithThreats(observation, eligible));
    }

    private boolean urgentSwitch(
            TacticalCombatPolicy.Decision aggregate,
            List<TacticalCombatPolicy.Threat> threats,
            String preferredId) {
        TacticalCombatPolicy.Threat challenger = find(threats, preferredId).orElse(null);
        if (challenger == null) return false;
        if (challenger.kind() == TacticalCombatPolicy.ThreatKind.CREEPER
                || challenger.kind() == TacticalCombatPolicy.ThreatKind.PLAYER) return true;
        // A current aggregate recovery/safety retreat outranks an acquire-time body-blocker
        // latch. The retained mob may have moved out of strike reach since acquisition; focusing
        // it alone must not downgrade a newly mandatory crowd retreat into FIGHT.
        if (aggregate.action() == TacticalCombatPolicy.Action.HARD_RETREAT
                || aggregate.action() == TacticalCombatPolicy.Action.RETREAT) return true;
        if (TacticalCombatPolicy.isActiveBodyBlocker(challenger)
                && !lockedAsBodyBlocker) return true;
        if (lockedAsBodyBlocker) {
            // Once immediate melee has been selected, ordinary distance/pressure
            // churn is not a reason to turn away. Death/removal and the explicit
            // unreachable watchdog are the bounded replacement edges.
            return false;
        }
        // HARD_RETREAT/RETREAT still represent immediate safety escalation. BOUNDED_RETREAT is
        // different: it authorizes retreat first and one exact-target sealed resolution attempt,
        // so ordinary pressure jitter must not cancel that attempt or restart its watchdog.
        return false;
    }

    private static TacticalCombatPolicy.Decision focus(
            TacticalCombatPolicy.Observation observation,
            TacticalCombatPolicy.Decision aggregate,
            TacticalCombatPolicy.Threat retained) {
        if (aggregate.targetId().orElse("").equals(retained.id())) return aggregate;
        TacticalCombatPolicy.Decision focused = TacticalCombatPolicy.decide(
                copyWithThreats(observation, List.of(retained)));
        boolean retainAggregateBoundedRetreat =
                aggregate.action() == TacticalCombatPolicy.Action.BOUNDED_RETREAT;
        boolean retainedHardSafetyKind =
                retained.kind() == TacticalCombatPolicy.ThreatKind.CREEPER
                        || retained.kind() == TacticalCombatPolicy.ThreatKind.PLAYER;
        if (retainAggregateBoundedRetreat && retainedHardSafetyKind) {
            // A retained special-kind target may sit outside tactical awareness while nearer
            // ordinary mobs produce the aggregate bounded intent. Never project that ordinary-mob
            // interception capability onto creeper/player executors; retain the exact UUID only.
            return new TacticalCombatPolicy.Decision(
                    TacticalCombatPolicy.Action.RETREAT,
                    observation.hasShield()
                            ? TacticalCombatPolicy.LoadoutIntent.WEAPON_AND_SHIELD
                            : observation.hasMeleeWeapon()
                            ? TacticalCombatPolicy.LoadoutIntent.WEAPON
                            : TacticalCombatPolicy.LoadoutIntent.NONE,
                    Optional.of(retained.id()),
                    aggregate.escape(),
                    aggregate.aggregatePressure(),
                    false,
                    false,
                    0L,
                    "retaining hard-safety target " + retained.id()
                            + "; ordinary bounded resolution cannot be projected onto "
                            + retained.kind().name().toLowerCase());
        }
        return new TacticalCombatPolicy.Decision(
                retainAggregateBoundedRetreat ? aggregate.action() : focused.action(),
                retainAggregateBoundedRetreat ? aggregate.loadout() : focused.loadout(),
                Optional.of(retained.id()),
                aggregate.escape(),
                aggregate.aggregatePressure(),
                retainAggregateBoundedRetreat
                        ? aggregate.approachAllowed()
                        : focused.approachAllowed(),
                retainAggregateBoundedRetreat
                        ? aggregate.eatWhenSafe()
                        : focused.eatWhenSafe(),
                retainAggregateBoundedRetreat
                        ? aggregate.resumeAtMillis()
                        : focused.resumeAtMillis(),
                "retaining combat target " + retained.id() + "; "
                        + (retainAggregateBoundedRetreat
                        ? aggregate.detail()
                        : focused.detail()));
    }

    private static TacticalCombatPolicy.Observation copyWithThreats(
            TacticalCombatPolicy.Observation source,
            List<TacticalCombatPolicy.Threat> threats) {
        return new TacticalCombatPolicy.Observation(
                source.nowMillis(), source.activity(), source.health(), source.maximumHealth(),
                source.foodLevel(), source.hasFood(), source.hasMeleeWeapon(), source.hasShield(),
                source.shieldReady(), source.armorPoints(), source.coverAvailable(), source.stance(),
                threats);
    }

    private static Optional<TacticalCombatPolicy.Threat> find(
            List<TacticalCombatPolicy.Threat> threats,
            String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return threats.stream().filter(threat -> threat.id().equals(id)).findFirst();
    }

    private void clearUnavailableState() {
        unreachableTargetId = "";
        unreachableSinceMillis = -1L;
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
