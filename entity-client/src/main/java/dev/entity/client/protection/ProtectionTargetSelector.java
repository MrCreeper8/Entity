package dev.entity.client.protection;

import dev.entity.core.protection.ProtectionPolicy;
import dev.entity.core.survival.WorldSnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Pure mirror of the core protection eligibility rules for resolving a Minecraft entity. */
public final class ProtectionTargetSelector {
    /** TacticalCombatPolicy's close-ranged pressure boundary. */
    private static final double CLOSE_RANGED_PRESSURE_DISTANCE = 8.0;

    private ProtectionTargetSelector() {
    }

    public static Optional<WorldSnapshot.Threat> select(
            List<WorldSnapshot.Threat> threats,
            ProtectionPolicy.Settings settings) {
        // Preserve the pre-2.4 active-attacker-only contract for callers that
        // have not supplied an explicit activity context.
        return select(threats, settings, ProtectionPolicy.ActivityContext.TRAVEL);
    }

    public static Optional<WorldSnapshot.Threat> select(
            List<WorldSnapshot.Threat> threats,
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext) {
        return select(threats, settings, activityContext, "");
    }

    /**
     * Resolves the core policy's already-owned threat before applying fresh
     * acquisition filters. This prevents a one-frame activeAttacking=false
     * sample from making the adapter abandon an engagement the core retained.
     */
    public static Optional<WorldSnapshot.Threat> select(
            List<WorldSnapshot.Threat> threats,
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext,
            String retainedThreatKey) {
        String retained = retainedThreatKey == null ? "" : retainedThreatKey.trim();
        if (!retained.isEmpty()) {
            Optional<WorldSnapshot.Threat> exact = threats.stream()
                    .filter(threat -> threatKey(threat).equals(retained))
                    .filter(threat -> targetEnabled(threat, settings))
                    .findFirst();
            if (exact.isPresent()) return exact;
        }
        return threats.stream()
                .filter(threat -> threat.activelyAttacking()
                        || activityContext == ProtectionPolicy.ActivityContext.IDLE
                        || settings.combatMode() == ProtectionPolicy.CombatMode.AGGRESSIVE)
                .filter(threat -> threat.distance() <= settings.maximumEngageDistance())
                .filter(threat -> targetEnabled(threat, settings))
                .max(Comparator
                        .comparingDouble(WorldSnapshot.Threat::estimatedDamage)
                        .thenComparing(threat -> -threat.distance()));
    }

    /**
     * Resolves one core-authorized reactivation UUID from the raw snapshot without treating it as
     * a fresh ordinary TRAVEL acquisition. Target scope still must be enabled; the caller's exact
     * handoff supplies the separate authorization for activity/range projection.
     */
    public static Optional<WorldSnapshot.Threat> selectExactEntity(
            List<WorldSnapshot.Threat> threats,
            ProtectionPolicy.Settings settings,
            String exactEntityId) {
        String exact = exactEntityId == null ? "" : exactEntityId.trim();
        if (exact.isEmpty()) return Optional.empty();
        return threats.stream()
                .filter(threat -> threat.entityId().equals(exact))
                .filter(threat -> targetEnabled(threat, settings))
                .max(Comparator
                        .comparingDouble(WorldSnapshot.Threat::estimatedDamage)
                        .thenComparing(threat -> -threat.distance()));
    }

    /** Resolve the current core decision, not a second fresh range acquisition. */
    public static Optional<WorldSnapshot.Threat> resolveDecision(
            List<WorldSnapshot.Threat> rawThreats,
            ProtectionPolicy.Settings settings,
            WorldSnapshot.Threat decisionThreat) {
        if (decisionThreat == null) return Optional.empty();
        return rawThreats.stream()
                .filter(threat -> threat.entityId().equals(decisionThreat.entityId())
                        && threat.target() == decisionThreat.target())
                .filter(threat -> targetEnabled(threat, settings))
                .findFirst();
    }

    /**
     * Makes the exact UUID eligible without forcing it over a genuinely active, more dangerous
     * challenger. This mirrors core's retained-vs-challenger handoff for the raw adapter.
     */
    public static Optional<WorldSnapshot.Threat> selectReactivated(
            List<WorldSnapshot.Threat> threats,
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext,
            String exactEntityId) {
        return selectReactivated(threats, settings, activityContext, exactEntityId, "");
    }

    /**
     * Selects from the same projected threat list core saw, including its retained-vs-challenger
     * hysteresis, then resolves the chosen identity back to the unmodified raw observation.
     */
    public static Optional<WorldSnapshot.Threat> selectReactivated(
            List<WorldSnapshot.Threat> threats,
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext,
            String exactEntityId,
            String retainedThreatKey) {
        Optional<WorldSnapshot.Threat> exact = selectExactEntity(
                threats, settings, exactEntityId);
        if (exact.isEmpty()) return Optional.empty();
        List<WorldSnapshot.Threat> projected = threats.stream()
                .map(threat -> threat.entityId().equals(exactEntityId)
                        ? new WorldSnapshot.Threat(
                        threat.entityId(), threat.entityType(), threat.target(),
                        // Match the core-only projection used to produce this exact handoff. Raw
                        // target selection below returns the real uncapped distance unchanged.
                        Math.min(threat.distance(), settings.maximumEngageDistance()),
                        threat.estimatedDamage(), true)
                        : threat)
                .toList();
        String retained = retainedThreatKey == null ? "" : retainedThreatKey.trim();
        Optional<WorldSnapshot.Threat> challenger = projected.stream()
                .filter(threat -> threat.activelyAttacking()
                        || activityContext == ProtectionPolicy.ActivityContext.IDLE
                        || settings.combatMode() == ProtectionPolicy.CombatMode.AGGRESSIVE)
                .filter(threat -> threat.distance() <= settings.maximumEngageDistance())
                .filter(threat -> targetEnabled(threat, settings))
                .max(Comparator.comparingDouble(WorldSnapshot.Threat::estimatedDamage)
                        .thenComparing(threat -> -threat.distance()));
        Optional<WorldSnapshot.Threat> held = retained.isEmpty()
                ? Optional.empty()
                : projected.stream()
                .filter(threat -> threatKey(threat).equals(retained))
                .filter(threat -> targetEnabled(threat, settings))
                .findFirst();
        WorldSnapshot.Threat selected = held.orElseGet(() -> challenger.orElse(null));
        if (selected == null) return Optional.empty();
        if (held.isPresent() && challenger.isPresent()
                && !threatKey(challenger.orElseThrow()).equals(retained)) {
            WorldSnapshot.Threat next = challenger.orElseThrow();
            if ((!selected.activelyAttacking() && next.activelyAttacking())
                    || next.estimatedDamage() >= selected.estimatedDamage() + 3.0) {
                selected = next;
            }
        }
        String selectedId = selected.entityId();
        WorldSnapshot.ThreatTarget selectedTarget = selected.target();
        return threats.stream()
                .filter(threat -> threat.entityId().equals(selectedId)
                        && threat.target() == selectedTarget)
                .max(Comparator.comparingDouble(WorldSnapshot.Threat::estimatedDamage)
                        .thenComparing(threat -> -threat.distance()));
    }

    /**
     * All loaded threats that may influence the tactical decision for the
     * current protection episode. The retained threat remains eligible across
     * a brief target/range sampling gap, exactly like {@link #select}.
     */
    public static List<WorldSnapshot.Threat> eligibleForTactics(
            List<WorldSnapshot.Threat> threats,
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext,
            String retainedThreatKey) {
        String retained = retainedThreatKey == null ? "" : retainedThreatKey.trim();
        return threats.stream()
                .filter(threat -> targetEnabled(threat, settings))
                .filter(threat -> !retained.isEmpty() && threatKey(threat).equals(retained)
                        || ((threat.activelyAttacking()
                        || activityContext == ProtectionPolicy.ActivityContext.IDLE
                        || settings.combatMode() == ProtectionPolicy.CombatMode.AGGRESSIVE
                        // Once protection already owns the body, a skeleton-class hostile inside
                        // its eight-block firing boundary is immediate aggregate pressure even
                        // before Paper attributes the first arrow. Fresh travel/stationary work
                        // remains attacker-only; this cannot start a new protection episode.
                        || (!retained.isEmpty() && closeRangedPressure(threat)))
                        && threat.distance() <= settings.maximumEngageDistance()))
                .toList();
    }

    private static boolean closeRangedPressure(WorldSnapshot.Threat threat) {
        if (threat.distance() > CLOSE_RANGED_PRESSURE_DISTANCE) return false;
        String type = threat.entityType().trim().toLowerCase(Locale.ROOT);
        int separator = type.indexOf(':');
        if (separator >= 0) type = type.substring(separator + 1);
        return switch (type) {
            case "skeleton", "stray", "bogged", "pillager", "drowned" -> true;
            default -> false;
        };
    }

    public static String threatKey(WorldSnapshot.Threat threat) {
        return threat.entityId() + '|' + threat.target().name();
    }

    private static boolean targetEnabled(
            WorldSnapshot.Threat threat,
            ProtectionPolicy.Settings settings) {
        return switch (threat.target()) {
            case SELF -> settings.protectSelf();
            case OWNER -> settings.protectOwner();
            case OTHER -> false;
        };
    }
}
