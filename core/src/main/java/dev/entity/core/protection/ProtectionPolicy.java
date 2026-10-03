package dev.entity.core.protection;

import dev.entity.core.control.BodyChannel;
import dev.entity.core.control.ControlPriority;
import dev.entity.core.survival.WorldSnapshot;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** User-configurable combat protection, deliberately separate from survival. */
public final class ProtectionPolicy {
    static final long ACTIVE_THREAT_RELEASE_GRACE_MILLIS = 3_000L;
    private volatile Settings settings;
    private boolean retreatLatched;
    private String engagedThreatKey = "";
    private long engagedThreatLastDangerAt = Long.MIN_VALUE;

    public ProtectionPolicy(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public synchronized void update(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        retreatLatched = false;
        clearEngagement();
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Resolves the protected owner without letting unrelated trusted policy
     * commands silently transfer ownership.  Only an explicit owner-name or
     * an enabled owner/me scope is ownership intent; self toggles and combat
     * stance changes preserve the current owner.
     */
    public static String resolveOwnerName(
            String currentOwner,
            String requestedBy,
            String scope,
            boolean enabled,
            String explicitOwner) {
        String current = Objects.requireNonNullElse(currentOwner, "").trim();
        String explicit = Objects.requireNonNullElse(explicitOwner, "").trim();
        if (!explicit.isEmpty()) return explicit;
        String normalizedScope = Objects.requireNonNullElse(scope, "").trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (enabled && (normalizedScope.equals("owner") || normalizedScope.equals("me"))) {
            String requester = Objects.requireNonNullElse(requestedBy, "").trim();
            return requester.isEmpty() ? current : requester;
        }
        return current;
    }

    /**
     * Backwards-compatible default for callers that do not yet classify the
     * current activity. With no supplied work context, Entity behaves as an
     * idle player and may proactively intercept a nearby enabled threat.
     */
    public synchronized Optional<ProtectionDirective> evaluate(WorldSnapshot world) {
        return evaluate(world, ActivityContext.IDLE);
    }

    /**
     * Compatibility bridge for the pre-2.4 API. An ordinary active mission
     * historically evaded self-targeting attackers, which is the TRAVEL
     * policy; no active mission maps to IDLE.
     */
    public synchronized Optional<ProtectionDirective> evaluate(
            WorldSnapshot world,
            boolean ordinaryMissionActive) {
        return evaluate(world, ordinaryMissionActive ? ActivityContext.TRAVEL : ActivityContext.IDLE);
    }

    public synchronized Optional<ProtectionDirective> evaluate(
            WorldSnapshot world,
            ActivityContext activityContext) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(activityContext, "activityContext");
        Settings current = settings;
        double healthFraction = world.health() / world.maximumHealth();
        double retreatExitFraction = Math.min(1.0,
                Math.max(0.5, current.retreatHealthFraction() + 0.15));
        if (retreatLatched && healthFraction >= retreatExitFraction) {
            retreatLatched = false;
        }

        Optional<WorldSnapshot.Threat> challenger = world.threats().stream()
                .filter(threat -> threat.activelyAttacking() || allowsProximityThreat(threat.entityType()))
                // An idle bot should not wait to take the first hit from a nearby
                // hostile. During travel or stationary work, only a threat that is
                // already attacking is allowed to interrupt the current activity.
                .filter(threat -> threat.activelyAttacking()
                        || activityContext == ActivityContext.IDLE
                        || current.combatMode() == CombatMode.AGGRESSIVE)
                .filter(threat -> targetEnabled(threat, current))
                // Fresh acquisition remains range-bounded. The separate retained
                // candidate below owns an already-active attacker across brief
                // target/range sampling changes.
                .filter(threat -> threat.distance() <= current.maximumEngageDistance())
                .max(Comparator
                        .comparingDouble(WorldSnapshot.Threat::estimatedDamage)
                        .thenComparing(threat -> -threat.distance()));

        Optional<WorldSnapshot.Threat> retained = world.threats().stream()
                .filter(threat -> threatKey(threat).equals(engagedThreatKey))
                .filter(threat -> targetEnabled(threat, current))
                .filter(threat -> threat.activelyAttacking()
                        || (threat.distance() <= current.maximumEngageDistance()
                        && withinReleaseGrace(world.nowMillis())))
                .findFirst();

        Optional<WorldSnapshot.Threat> selected = retained;
        if (retained.isEmpty()) {
            selected = challenger;
        } else if (challenger.isPresent()
                && !threatKey(challenger.orElseThrow()).equals(engagedThreatKey)) {
            WorldSnapshot.Threat held = retained.orElseThrow();
            WorldSnapshot.Threat next = challenger.orElseThrow();
            // Do not bounce between equally urgent mobs, but never retain a
            // quiet/less-dangerous attacker in front of a new immediate hazard.
            if ((!held.activelyAttacking() && next.activelyAttacking())
                    || next.estimatedDamage() >= held.estimatedDamage() + 3.0) {
                selected = challenger;
            }
        }
        if (selected.isEmpty()) {
            retreatLatched = false;
            clearEngagement();
            return Optional.empty();
        }
        WorldSnapshot.Threat threat = selected.orElseThrow();
        String selectedKey = threatKey(threat);
        if (threat.activelyAttacking()) {
            engagedThreatKey = selectedKey;
            engagedThreatLastDangerAt = world.nowMillis();
        }
        if (!retreatLatched && healthFraction <= current.retreatHealthFraction()) {
            retreatLatched = true;
        }
        boolean avoidCombat = current.combatMode() == CombatMode.AVOID;
        boolean defensiveTravel = current.combatMode() == CombatMode.DEFENSIVE
                && activityContext == ActivityContext.TRAVEL;
        ProtectionAction action = retreatLatched || avoidCombat || defensiveTravel
                ? ProtectionAction.RETREAT
                : ProtectionAction.INTERCEPT;
        String reason = retreatLatched
                ? "retreat from " + threat.entityType() + " until health recovers"
                : avoidCombat
                ? "avoid " + threat.entityType() + " and resume the current mission"
                : defensiveTravel
                ? "evade " + threat.entityType() + " while travelling, then resume the current mission"
                : !threat.activelyAttacking()
                ? "intercept nearby " + threat.entityType() + " before it attacks"
                : "intercept " + threat.entityType() + " attacking "
                + threat.target().name().toLowerCase();
        return Optional.of(new ProtectionDirective(
                action, threat, ControlPriority.PROTECTION, reason,
                EnumSet.allOf(BodyChannel.class)));
    }

    private static String threatKey(WorldSnapshot.Threat threat) {
        return threat.entityId() + '|' + threat.target().name();
    }

    /** Neutral Endermen require actual target/damage evidence, even in aggressive protection mode. */
    public static boolean allowsProximityThreat(String entityType) {
        String type = Objects.requireNonNullElse(entityType, "").toLowerCase(java.util.Locale.ROOT);
        return !type.equals("enderman") && !type.equals("minecraft:enderman");
    }

    private boolean withinReleaseGrace(long nowMillis) {
        return engagedThreatLastDangerAt != Long.MIN_VALUE
                && Math.max(0L, nowMillis - engagedThreatLastDangerAt)
                < ACTIVE_THREAT_RELEASE_GRACE_MILLIS;
    }

    private void clearEngagement() {
        engagedThreatKey = "";
        engagedThreatLastDangerAt = Long.MIN_VALUE;
    }

    private static boolean targetEnabled(WorldSnapshot.Threat threat, Settings settings) {
        return switch (threat.target()) {
            case SELF -> settings.protectSelf();
            case OWNER -> settings.protectOwner();
            case OTHER -> false;
        };
    }

    public record Settings(
            boolean protectSelf,
            boolean protectOwner,
            String ownerName,
            double maximumEngageDistance,
            double retreatHealthFraction,
            CombatMode combatMode) {
        public Settings {
            ownerName = Objects.requireNonNullElse(ownerName, "");
            combatMode = Objects.requireNonNullElse(combatMode, CombatMode.DEFENSIVE);
            if (maximumEngageDistance <= 0 || retreatHealthFraction < 0 || retreatHealthFraction > 1) {
                throw new IllegalArgumentException("Invalid protection settings");
            }
        }

        /** Source-compatible constructor for pre-2.17 callers and settings. */
        public Settings(
                boolean protectSelf,
                boolean protectOwner,
                String ownerName,
                double maximumEngageDistance,
                double retreatHealthFraction) {
            this(protectSelf, protectOwner, ownerName, maximumEngageDistance,
                    retreatHealthFraction, CombatMode.DEFENSIVE);
        }

        public static Settings disabled() {
            return new Settings(false, false, "", 16, 0.3, CombatMode.DEFENSIVE);
        }

        /**
         * Safe first-run behavior for an autonomous survival player. Entity
         * protects itself out of the box, but never opts its owner into combat
         * protection without an explicit command. Both toggles remain fully
         * user-controlled after the settings file has been created.
         */
        public static Settings firstRunDefaults() {
            return new Settings(true, false, "", 16, 0.3, CombatMode.DEFENSIVE);
        }
    }

    /** User-visible combat stance. Survival emergencies still outrank every stance. */
    public enum CombatMode {
        AVOID,
        DEFENSIVE,
        AGGRESSIVE;

        public static CombatMode parse(String value) {
            if (value == null || value.isBlank()) return DEFENSIVE;
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    public enum ProtectionAction {
        INTERCEPT,
        RETREAT
    }

    /** What Entity is doing when combat protection evaluates a threat. */
    public enum ActivityContext {
        IDLE,
        TRAVEL,
        STATIONARY_WORK
    }

    public record ProtectionDirective(
            ProtectionAction action,
            WorldSnapshot.Threat threat,
            int priority,
            String reason,
            Set<BodyChannel> channels) {
        public ProtectionDirective {
            Objects.requireNonNull(action, "action");
            channels = Set.copyOf(channels);
        }
    }
}
