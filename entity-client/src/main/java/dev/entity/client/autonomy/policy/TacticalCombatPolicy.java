package dev.entity.client.autonomy.policy;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure tactical policy for short hostile encounters.
 *
 * <p>The policy does not press keys or select Minecraft slots.  It decides
 * whether an actuator should keep travelling, perform a bounded intercept,
 * shield, create tactical spacing, retreat, eat, or resume the interrupted activity.
 * All hostile pressure and escape-vector calculations include every relevant
 * loaded threat instead of considering only the nearest entity.</p>
 */
public final class TacticalCombatPolicy {
    private static final double NEAR_CANCELLATION_RATIO = 0.10;
    /** Vanilla natural regeneration is available at eighteen food or above. */
    private static final int NATURAL_REGEN_FOOD_LEVEL = 18;
    /** Full leather armor is seven points; anything below six is only incidental protection. */
    private static final int MINIMUM_PROTECTIVE_ARMOR_POINTS = 6;
    /** A hostile already inside ordinary melee reach is blocking movement, not a pursuit target. */
    public static final double ACTIVE_BODY_BLOCKER_RADIUS = 3.2;
    /** A closing creeper inside this margin can begin a lethal fuse before a held route expires. */
    public static final double IMMEDIATE_CREEPER_AVOIDANCE_RADIUS = 6.0;
    public static final Limits DEFAULT_LIMITS = new Limits(
            16.0,
            8.0,
            3.5,
            0.35,
            2.75,
            400L);

    private TacticalCombatPolicy() {
    }

    /** Stateless tactical decision for one observation. */
    public static Decision decide(Observation observation) {
        return decide(observation, DEFAULT_LIMITS);
    }

    public static Decision decide(Observation observation, Limits limits) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(limits, "limits");
        List<Threat> threats = relevantThreats(observation, limits);
        double aggregatePressure = aggregatePressure(threats);
        EscapeVector escape = escapeVector(threats);
        boolean lowHealth = observation.healthRatio() <= limits.lowHealthRatio();

        Optional<Threat> primedCreeper = threats.stream()
                .filter(threat -> threat.kind() == ThreatKind.CREEPER)
                .filter(Threat::primed)
                .filter(threat -> threat.distance() <= limits.primedCreeperRetreatRadius())
                .min(Comparator.comparingDouble(Threat::distance).thenComparing(Threat::id));
        if (primedCreeper.isPresent()) {
            Threat creeper = primedCreeper.orElseThrow();
            return decision(
                    Action.HARD_RETREAT,
                    preferredDefensiveLoadout(observation),
                    creeper,
                    escape,
                    aggregatePressure,
                    false,
                    lowHealth && observation.canEat(),
                    0L,
                    "primed creeper inside the hard-retreat radius; approaching is forbidden");
        }

        Optional<Threat> distantPrimedCreeper = threats.stream()
                .filter(threat -> threat.kind() == ThreatKind.CREEPER)
                .filter(Threat::primed)
                .min(Comparator.comparingDouble(Threat::distance).thenComparing(Threat::id));
        if (distantPrimedCreeper.isPresent()) {
            Threat creeper = distantPrimedCreeper.orElseThrow();
            return decision(
                    Action.RETREAT,
                    preferredDefensiveLoadout(observation),
                    creeper,
                    escape,
                    aggregatePressure,
                    false,
                    false,
                    0L,
                    "primed creeper remains a safety retreat outside the immediate hard-retreat radius");
        }

        Optional<Threat> playerAttacker = threats.stream()
                .filter(threat -> threat.kind() == ThreatKind.PLAYER)
                .max(Comparator.comparingDouble(TacticalCombatPolicy::pressure)
                        .thenComparing(Threat::id));
        if (playerAttacker.isPresent()) {
            Threat player = playerAttacker.orElseThrow();
            return decision(
                    Action.EVADE,
                    preferredDefensiveLoadout(observation),
                    player,
                    escape,
                    aggregatePressure,
                    false,
                    false,
                    0L,
                    "immediate player self-defense: keep separation and counterstrike only in reach; never pursue");
        }

        if (observation.stance() == CombatStance.AVOID && !threats.isEmpty()) {
            Threat primary = highestPressure(threats);
            return decision(
                    Action.EVADE,
                    preferredDefensiveLoadout(observation),
                    primary,
                    escape,
                    aggregatePressure,
                    false,
                    false,
                    0L,
                    "avoid stance requests short tactical spacing and never pursues combat");
        }

        Optional<Threat> lowHealthCreeper = threats.stream()
                .filter(threat -> threat.kind() == ThreatKind.CREEPER)
                .filter(threat -> !threat.primed())
                .min(Comparator.comparingDouble(Threat::distance).thenComparing(Threat::id));
        if (lowHealth && lowHealthCreeper.isPresent()) {
            Threat creeper = lowHealthCreeper.orElseThrow();
            return decision(
                    Action.RETREAT,
                    preferredDefensiveLoadout(observation),
                    creeper,
                    escape,
                    aggregatePressure,
                    false,
                    false,
                    0L,
                    "low health makes even an unprimed creeper a safety retreat; sealed ordinary-mob interception is forbidden");
        }

        List<Threat> unprimedCreepers = threats.stream()
                .filter(threat -> threat.kind() == ThreatKind.CREEPER)
                .filter(threat -> !threat.primed())
                .toList();
        Optional<Threat> nearestUnprimedCreeper = unprimedCreepers.stream()
                .min(Comparator.comparingDouble(Threat::distance).thenComparing(Threat::id));
        Optional<Threat> activeBodyBlocker = threats.stream()
                .filter(TacticalCombatPolicy::isActiveBodyBlocker)
                .max(Comparator.comparingDouble(TacticalCombatPolicy::pressure)
                        .thenComparing(Threat::id));
        boolean usableCombatLoadout = hasUsableCombatLoadout(observation, threats);
        // Do not flee an attacker already striking us merely because an unfused
        // creeper is visible farther away. This grants only the existing exact
        // in-reach intercept; nearby/fusing creepers retain their safety priority.
        boolean immediateEquippedResponse = activeBodyBlocker.isPresent() && usableCombatLoadout
                && nearestUnprimedCreeper.filter(creeper ->
                        creeper.distance() <= IMMEDIATE_CREEPER_AVOIDANCE_RADIUS).isEmpty();
        if (observation.stance() != CombatStance.AGGRESSIVE
                && nearestUnprimedCreeper.isPresent()
                && !immediateEquippedResponse
                && (unprimedCreepers.size() > 1
                || nearestUnprimedCreeper.orElseThrow().distance()
                > IMMEDIATE_CREEPER_AVOIDANCE_RADIUS)) {
            Threat creeper = nearestUnprimedCreeper.orElseThrow();
            return decision(
                    Action.RETREAT,
                    preferredDefensiveLoadout(observation),
                    creeper,
                    escape,
                    aggregatePressure,
                    false,
                    false,
                    0L,
                    "defensive mode will not pursue a distant or grouped creeper; withdraw along the retained mission corridor and reassess only if one enters immediate reach");
        }

        boolean manageableUndergearedBlocker = activeBodyBlocker
                .filter(blocker -> manageableUndergearedBodyBlocker(
                        observation, threats, blocker))
                .isPresent();
        if (activeBodyBlocker.isPresent()
                && (usableCombatLoadout || manageableUndergearedBlocker)) {
            Threat blocker = activeBodyBlocker.orElseThrow();
            Action action = lowHealth
                    ? Action.INTERCEPT
                    : observation.stance() == CombatStance.DEFENSIVE
                    && observation.activity() != Activity.IDLE
                    ? Action.INTERCEPT
                    : observation.activity() == Activity.STATIONARY_WORK
                            ? Action.INTERCEPT
                            : Action.FIGHT;
            return decision(
                    action,
                    preferredCombatLoadout(observation),
                    blocker,
                    escape,
                    aggregatePressure,
                    true,
                    false,
                    0L,
                    manageableUndergearedBlocker
                            ? "one healthy ordinary attacker is already occupying melee space; clear this exact blocker with cooldown-timed strikes, then resume the mission without pursuing it after it leaves reach"
                            : lowHealth
                            ? "low-health active melee body blocker is already inside strike reach; make a cooldown-timed defensive strike, then reassess without pursuing"
                            : "active melee body blocker is already inside strike reach; neutralize it before pursuing ranged pressure or attempting withdrawal");
        }

        if (!threats.isEmpty() && !usableCombatLoadout) {
            Threat primary = highestPressure(threats);
            return decision(
                    Action.RETREAT,
                    preferredDefensiveLoadout(observation),
                    primary,
                    escape,
                    aggregatePressure,
                    false,
                    lowHealth && observation.canEat(),
                    0L,
                    "no usable weapon-plus-defense loadout; run the safest mission-directed corridor without pursuit and counterstrike only if an attacker occupies immediate reach");
        }

        // Carried food is not immediate protection-layer recovery: no protected
        // eating actuator exists while a live attacker owns the body. Only the
        // vanilla natural-regeneration threshold can currently recover health
        // without first resolving the combat episode.
        boolean naturalRecoveryAvailable =
                observation.foodLevel() >= NATURAL_REGEN_FOOD_LEVEL;

        if (lowHealth && !threats.isEmpty()) {
            Threat primary = highestPressure(threats);
            return decision(
                    naturalRecoveryAvailable ? Action.RETREAT : Action.BOUNDED_RETREAT,
                    preferredDefensiveLoadout(observation),
                    primary,
                    escape,
                    aggregatePressure,
                    false,
                    observation.canEat(),
                    0L,
                    naturalRecoveryAvailable
                            ? "health reserve is below the fight threshold; retreat until eating or natural regeneration can recover health"
                            : "health reserve is below the fight threshold and cannot recover during protection; seek separation first, then use one exact-target shielded resolution attempt bounded by six seconds without combat progress if no safe retreat route exists");
        }

        double modeledIncomingDamage = modeledNearTermDamage(threats);
        if (!threats.isEmpty() && modeledIncomingDamage >= observation.health()) {
            Threat primary = highestPressure(threats);
            return decision(
                    observation.foodLevel() >= NATURAL_REGEN_FOOD_LEVEL
                            ? Action.RETREAT
                            : Action.BOUNDED_RETREAT,
                    preferredDefensiveLoadout(observation),
                    primary,
                    escape,
                    aggregatePressure,
                    false,
                    observation.canEat(),
                    0L,
                    observation.foodLevel() >= NATURAL_REGEN_FOOD_LEVEL
                            ? "modeled near-term exposure can exhaust current health; safety retreat is mandatory while natural recovery remains available"
                            : "modeled near-term exposure can exhaust current health and cannot recover during protection; seek separation first, then use one exact-target shielded resolution attempt bounded by six seconds without combat progress if no safe retreat route exists");
        }

        Optional<Threat> unprimedCreeper = threats.stream()
                .filter(threat -> threat.kind() == ThreatKind.CREEPER)
                .filter(threat -> !threat.primed())
                .min(Comparator.comparingDouble(Threat::distance).thenComparing(Threat::id));
        if (unprimedCreeper.isPresent()) {
            Threat creeper = unprimedCreeper.orElseThrow();
            return decision(
                    Action.INTERCEPT,
                    preferredCombatLoadout(observation),
                    creeper,
                    escape,
                    aggregatePressure,
                    true,
                    false,
                    0L,
                    "close on the unprimed creeper so the bounded strike-and-retreat executor can neutralize it");
        }

        Optional<Threat> skeleton = threats.stream()
                .filter(threat -> threat.kind() == ThreatKind.SKELETON)
                .filter(threat -> threat.targetingEntity() || threat.distance() <= 8.0)
                .max(Comparator.comparingDouble(TacticalCombatPolicy::pressure)
                        .thenComparing(Threat::id));
        if (skeleton.isPresent()) {
            Threat ranged = skeleton.orElseThrow();
            if (observation.stance() == CombatStance.AVOID) {
                return decision(
                        Action.EVADE,
                        preferredDefensiveLoadout(observation),
                        ranged,
                        escape,
                        aggregatePressure,
                        false,
                        false,
                        0L,
                        "avoid stance requests short lateral spacing from ranged fire without approaching");
            }
            if (observation.hasShield() && observation.shieldReady()) {
                return decision(
                        Action.SHIELDED_INTERCEPT,
                        observation.hasMeleeWeapon()
                                ? LoadoutIntent.WEAPON_AND_SHIELD
                                : LoadoutIntent.SHIELD,
                        ranged,
                        escape,
                        aggregatePressure,
                        true,
                        false,
                        0L,
                        "advance behind the equipped shield, release only for cooldown-timed strikes");
            }
            if (observation.hasShield()) {
                return decision(
                        Action.RAISE_SHIELD,
                        observation.hasMeleeWeapon()
                                ? LoadoutIntent.WEAPON_AND_SHIELD
                                : LoadoutIntent.SHIELD,
                        ranged,
                        escape,
                        aggregatePressure,
                        false,
                        false,
                        0L,
                        "shield is carried but not ready; equip it before approaching the skeleton");
            }
            return decision(
                    Action.INTERCEPT,
                    preferredCombatLoadout(observation),
                    ranged,
                    escape,
                    aggregatePressure,
                    true,
                    false,
                    0L,
                    "no usable shield; route-close with lateral spacing and bounded cooldown-timed strikes");
        }

        if (!threats.isEmpty()) {
            Threat primary = highestPressure(threats);
            if (observation.stance() == CombatStance.DEFENSIVE
                    && observation.activity() != Activity.IDLE
                    && primary.targetingEntity()) {
                return decision(
                        Action.INTERCEPT,
                        preferredCombatLoadout(observation),
                        primary,
                        escape,
                        aggregatePressure,
                        true,
                        false,
                        0L,
                        "active melee attacker blocks the retained mission; perform a bounded intercept, then resume it");
            }
            if (observation.activity() == Activity.TRAVEL) {
                return decision(
                        Action.FIGHT,
                        preferredCombatLoadout(observation),
                        primary,
                        escape,
                        aggregatePressure,
                        true,
                        false,
                        0L,
                        "aggressive stance accepts a bounded fight before resuming travel");
            }
            if (observation.activity() == Activity.STATIONARY_WORK
                    && primary.distance() <= limits.stationaryInterceptRadius()) {
                return decision(
                        Action.INTERCEPT,
                        preferredCombatLoadout(observation),
                        primary,
                        escape,
                        aggregatePressure,
                        true,
                        false,
                        0L,
                        "stationary work cannot progress under melee pressure; perform a bounded intercept");
            }
            return decision(
                    Action.FIGHT,
                    preferredCombatLoadout(observation),
                    primary,
                    escape,
                    aggregatePressure,
                    true,
                    false,
                    0L,
                    "health and aggregate pressure permit a bounded melee engagement");
        }

        if (lowHealth && observation.canEat()) {
            return new Decision(
                    Action.EAT,
                    LoadoutIntent.FOOD,
                    Optional.empty(),
                    EscapeVector.NONE,
                    0.0,
                    false,
                    false,
                    0L,
                    "no relevant hostile remains; eat before resuming exposed work");
        }
        return new Decision(
                Action.CONTINUE,
                LoadoutIntent.NONE,
                Optional.empty(),
                EscapeVector.NONE,
                0.0,
                false,
                false,
                0L,
                "no tactical intervention is required");
    }

    private static List<Threat> relevantThreats(Observation observation, Limits limits) {
        return observation.threats().stream()
                .filter(threat -> threat.distance() <= limits.awarenessRadius())
                .filter(threat -> threat.estimatedDamage() > 0.0 || threat.primed())
                .toList();
    }

    public static double aggregatePressure(List<Threat> threats) {
        Objects.requireNonNull(threats, "threats");
        double total = 0.0;
        for (Threat threat : threats) total += pressure(threat);
        return total;
    }

    /**
     * Models the damage likely to arrive inside one reaction/cooldown window, rather than adding
     * one hypothetical simultaneous hit from every loaded hostile. The old raw sum made an
     * ordinary three-zombie-plus-skeleton fight flip from engagement to permanent retreat after
     * a single chip hit. One strongest hit remains fully reserved; other active attackers
     * contribute half a hit because they can overlap the response window without all landing on
     * the same tick.
     */
    public static double modeledNearTermDamage(List<Threat> threats) {
        Objects.requireNonNull(threats, "threats");
        double strongest = 0.0;
        double remainder = 0.0;
        for (Threat threat : threats) {
            if (threat.kind() == ThreatKind.CREEPER) continue;
            double damage = Math.max(0.0, threat.estimatedDamage());
            if (damage > strongest) {
                remainder += strongest;
                strongest = damage;
            } else {
                remainder += damage;
            }
        }
        return strongest + remainder * 0.5;
    }

    /**
     * True when an ordinary hostile is actively occupying immediate melee space.
     * Ranged mobs, players and creepers retain their dedicated safety/engagement rules.
     */
    public static boolean isActiveBodyBlocker(Threat threat) {
        Objects.requireNonNull(threat, "threat");
        return (threat.kind() == ThreatKind.MELEE || threat.kind() == ThreatKind.OTHER)
                && threat.targetingEntity()
                && threat.distance() <= ACTIVE_BODY_BLOCKER_RADIUS;
    }

    /**
     * A healthy traveller may finish one ordinary attacker that is physically
     * occupying melee space even when no weapon has been acquired yet. This is
     * deliberately narrower than "fight while unarmed": it requires natural
     * regeneration, rejects a second close attacker and nearby ranged/creeper
     * pressure, and stops qualifying as soon as the exact blocker leaves reach.
     */
    static boolean manageableUndergearedBodyBlocker(
            Observation observation,
            List<Threat> threats,
            Threat blocker) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(threats, "threats");
        Objects.requireNonNull(blocker, "blocker");
        if (observation.activity() != Activity.TRAVEL
                || hasUsableCombatLoadout(observation, threats)
                || observation.healthRatio() < 0.75
                || observation.foodLevel() < NATURAL_REGEN_FOOD_LEVEL
                || !isActiveBodyBlocker(blocker)) {
            return false;
        }
        long closeAttackers = threats.stream()
                .filter(Threat::targetingEntity)
                .filter(threat -> threat.distance() <= 5.0)
                .count();
        if (closeAttackers != 1L) return false;
        return threats.stream().noneMatch(threat ->
                (threat.kind() == ThreatKind.SKELETON
                        && threat.targetingEntity()
                        && threat.distance() <= 8.0)
                        || (threat.kind() == ThreatKind.CREEPER
                        && threat.distance() <= IMMEDIATE_CREEPER_AVOIDANCE_RADIUS));
    }

    private static double pressure(Threat threat) {
        double distance = Math.max(0.75, threat.distance());
        double score = Math.max(0.25, threat.estimatedDamage()) / (distance * distance);
        if (threat.targetingEntity()) score *= 1.6;
        if (threat.kind() == ThreatKind.SKELETON) score *= 1.25;
        if (threat.kind() == ThreatKind.CREEPER) score *= threat.primed() ? 12.0 : 2.0;
        return score;
    }

    public static EscapeVector escapeVector(List<Threat> threats) {
        Objects.requireNonNull(threats, "threats");
        double x = 0.0;
        double z = 0.0;
        double totalWeight = 0.0;
        for (Threat threat : threats) {
            double distance = threat.distance();
            double weight = pressure(threat);
            totalWeight += weight;
            if (distance > 0.001) {
                x -= threat.relativeX() / distance * weight;
                z -= threat.relativeZ() / distance * weight;
            } else if ((threat.id().hashCode() & 1) == 0) {
                x += weight;
            } else {
                z += weight;
            }
        }
        double length = Math.sqrt(x * x + z * z);
        // Normalizing a tiny residual turns harmless sensor/pressure jitter between
        // opposing mobs into a full-speed charge toward one of them. Treat cancellation
        // relative to the total hostile pressure, not as a bit-exact zero vector.
        if (length <= Math.max(0.000_001, totalWeight * NEAR_CANCELLATION_RATIO)) {
            return cancellationEscape(threats);
        }
        return new EscapeVector(x / length, z / length);
    }

    /**
     * Near-opposing pressure has no meaningful "primary" threat. Falling
     * back to whichever UUID happens to win that tick reverses the route when
     * the winner churns. Choose the deterministic sampled direction that
     * maximizes the worst pressure-weighted clearance after one block instead.
     */
    private static EscapeVector cancellationEscape(List<Threat> threats) {
        if (threats.isEmpty()) return EscapeVector.NONE;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestX = 0.0;
        double bestZ = 0.0;
        for (int index = 0; index < 16; index++) {
            double angle = (Math.PI * 2.0 * index) / 16.0;
            double candidateX = Math.cos(angle);
            double candidateZ = Math.sin(angle);
            double minimumClearance = Double.POSITIVE_INFINITY;
            for (Threat threat : threats) {
                double futureX = threat.relativeX() - candidateX;
                double futureZ = threat.relativeZ() - candidateZ;
                double weightedClearance = Math.hypot(futureX, futureZ)
                        / Math.sqrt(Math.max(0.000_001, pressure(threat)));
                minimumClearance = Math.min(minimumClearance, weightedClearance);
            }
            if (minimumClearance > bestScore + 0.000_001) {
                bestScore = minimumClearance;
                bestX = candidateX;
                bestZ = candidateZ;
            }
        }
        return new EscapeVector(bestX, bestZ);
    }

    private static Threat highestPressure(List<Threat> threats) {
        return threats.stream()
                .max(Comparator.comparingDouble(TacticalCombatPolicy::pressure)
                        .thenComparing(Threat::id))
                .orElseThrow();
    }

    private static LoadoutIntent preferredCombatLoadout(Observation observation) {
        if (observation.hasMeleeWeapon() && observation.hasShield()) {
            return LoadoutIntent.WEAPON_AND_SHIELD;
        }
        if (observation.hasMeleeWeapon()) return LoadoutIntent.WEAPON;
        if (observation.hasShield()) return LoadoutIntent.SHIELD;
        return LoadoutIntent.NONE;
    }

    private static LoadoutIntent preferredDefensiveLoadout(Observation observation) {
        if (observation.hasShield()) {
            return observation.hasMeleeWeapon()
                    ? LoadoutIntent.WEAPON_AND_SHIELD
                    : LoadoutIntent.SHIELD;
        }
        return observation.hasMeleeWeapon() ? LoadoutIntent.WEAPON : LoadoutIntent.NONE;
    }

    /**
     * Natural survival combat is optional until Entity is actually equipped to
     * finish it. A melee weapon plus a shield is always adequate; ordinary
     * melee may also be faced with meaningful equipped armor. Any active ranged
     * pressure still requires the shield because armor does not stop arrows or
     * make an unshielded approach safe.
     */
    private static boolean hasUsableCombatLoadout(
            Observation observation,
            List<Threat> threats) {
        if (!observation.hasMeleeWeapon()) return false;
        boolean rangedPressure = threats.stream().anyMatch(threat ->
                threat.kind() == ThreatKind.SKELETON
                        && (threat.targetingEntity() || threat.distance() <= 8.0));
        if (rangedPressure) return observation.hasShield();
        return observation.hasShield()
                || observation.armorPoints() >= MINIMUM_PROTECTIVE_ARMOR_POINTS;
    }

    private static Decision decision(
            Action action,
            LoadoutIntent loadout,
            Threat target,
            EscapeVector escape,
            double aggregatePressure,
            boolean approachAllowed,
            boolean eatWhenSafe,
            long resumeAtMillis,
            String detail) {
        return new Decision(
                action,
                loadout,
                Optional.of(target.id()),
                escape,
                aggregatePressure,
                approachAllowed,
                eatWhenSafe,
                resumeAtMillis,
                detail);
    }

    /**
     * Adds a bounded, explicit resume edge to the stateless decisions.  The
     * interrupted activity is retained while danger exists, held for one short
     * clear window, resumed exactly once, and then returns to CONTINUE.
     */
    public static final class Session {
        private final Limits limits;
        private boolean interrupted;
        private long safeSinceMillis = -1L;
        private EscapeVector lastEscape = EscapeVector.NONE;

        public Session() {
            this(DEFAULT_LIMITS);
        }

        public Session(Limits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
        }

        public Decision observe(Observation observation) {
            Objects.requireNonNull(observation, "observation");
            Decision immediate = decide(observation, limits);
            if (isInterruption(immediate.action())) {
                interrupted = true;
                safeSinceMillis = -1L;
                if (!immediate.escape().equals(EscapeVector.NONE)) {
                    lastEscape = immediate.escape();
                }
                return immediate;
            }
            if (immediate.action() == Action.EAT) return immediate;
            if (!interrupted) return immediate;

            if (safeSinceMillis < 0L) safeSinceMillis = observation.nowMillis();
            long resumeAt = saturatedAdd(safeSinceMillis, limits.safeResumeDelayMillis());
            if (observation.nowMillis() < resumeAt) {
                return new Decision(
                        Action.HOLD_RETREAT,
                        preferredDefensiveLoadout(observation),
                        Optional.empty(),
                        lastEscape,
                        0.0,
                        false,
                        false,
                        resumeAt,
                        "hostiles cleared; holding the last safe vector for a bounded resume window");
            }
            interrupted = false;
            safeSinceMillis = -1L;
            lastEscape = EscapeVector.NONE;
            return new Decision(
                    Action.RESUME,
                    LoadoutIntent.NONE,
                    Optional.empty(),
                    EscapeVector.NONE,
                    0.0,
                    false,
                    false,
                    0L,
                    "bounded clear window elapsed; resume the interrupted activity");
        }

        public void clear() {
            interrupted = false;
            safeSinceMillis = -1L;
            lastEscape = EscapeVector.NONE;
        }

        public boolean interrupted() {
            return interrupted;
        }

        private static boolean isInterruption(Action action) {
            return switch (action) {
                case EVADE, INTERCEPT, FIGHT, SHIELDED_INTERCEPT, HARD_RETREAT,
                        RETREAT, BOUNDED_RETREAT,
                        RAISE_SHIELD, SHIELD_AND_COVER, SEEK_COVER -> true;
                case CONTINUE, HOLD_RETREAT, EAT, RESUME -> false;
            };
        }
    }

    public enum Activity {
        STATIONARY_WORK,
        TRAVEL,
        IDLE
    }

    public enum ThreatKind {
        CREEPER,
        SKELETON,
        PLAYER,
        MELEE,
        OTHER
    }

    public enum Action {
        CONTINUE,
        EVADE,
        INTERCEPT,
        FIGHT,
        SHIELDED_INTERCEPT,
        HARD_RETREAT,
        RETREAT,
        BOUNDED_RETREAT,
        RAISE_SHIELD,
        SHIELD_AND_COVER,
        SEEK_COVER,
        HOLD_RETREAT,
        EAT,
        RESUME
    }

    public enum LoadoutIntent {
        NONE,
        WEAPON,
        SHIELD,
        WEAPON_AND_SHIELD,
        FOOD
    }

    public enum CombatStance {
        AVOID,
        DEFENSIVE,
        AGGRESSIVE
    }

    public record Limits(
            double awarenessRadius,
            double primedCreeperRetreatRadius,
            double stationaryInterceptRadius,
            double lowHealthRatio,
            double retreatPressure,
            long safeResumeDelayMillis) {
        public Limits {
            if (!Double.isFinite(awarenessRadius) || awarenessRadius <= 0.0) {
                throw new IllegalArgumentException("awarenessRadius must be finite and positive");
            }
            if (!Double.isFinite(primedCreeperRetreatRadius)
                    || primedCreeperRetreatRadius <= 0.0
                    || primedCreeperRetreatRadius > awarenessRadius) {
                throw new IllegalArgumentException("primedCreeperRetreatRadius is invalid");
            }
            if (!Double.isFinite(stationaryInterceptRadius)
                    || stationaryInterceptRadius <= 0.0
                    || stationaryInterceptRadius > awarenessRadius) {
                throw new IllegalArgumentException("stationaryInterceptRadius is invalid");
            }
            if (!Double.isFinite(lowHealthRatio)
                    || lowHealthRatio <= 0.0
                    || lowHealthRatio >= 1.0) {
                throw new IllegalArgumentException("lowHealthRatio must lie between zero and one");
            }
            if (!Double.isFinite(retreatPressure) || retreatPressure <= 0.0) {
                throw new IllegalArgumentException("retreatPressure must be finite and positive");
            }
            if (safeResumeDelayMillis <= 0L) {
                throw new IllegalArgumentException("safeResumeDelayMillis must be positive");
            }
        }
    }

    public record Threat(
            String id,
            ThreatKind kind,
            double relativeX,
            double relativeZ,
            double estimatedDamage,
            boolean targetingEntity,
            boolean primed,
            int fuseTicksRemaining) {
        public Threat {
            Objects.requireNonNull(id, "id");
            id = id.trim();
            if (id.isEmpty()) throw new IllegalArgumentException("threat id cannot be blank");
            kind = Objects.requireNonNull(kind, "kind");
            if (!Double.isFinite(relativeX) || !Double.isFinite(relativeZ)) {
                throw new IllegalArgumentException("threat coordinates must be finite");
            }
            if (!Double.isFinite(estimatedDamage) || estimatedDamage < 0.0) {
                throw new IllegalArgumentException("estimatedDamage must be finite and non-negative");
            }
            if (fuseTicksRemaining < -1) {
                throw new IllegalArgumentException("fuseTicksRemaining cannot be below -1");
            }
            if ((primed || fuseTicksRemaining >= 0) && kind != ThreatKind.CREEPER) {
                throw new IllegalArgumentException("only creepers can carry fuse state");
            }
            primed = primed || fuseTicksRemaining >= 0;
        }

        public double distance() {
            return Math.sqrt(relativeX * relativeX + relativeZ * relativeZ);
        }
    }

    /**
     * One tactical sample. During mission work {@code threats} is the
     * protection layer's policy-eligible attacker set, including its retained
     * attacker across brief target-sampling gaps; this policy must not redo
     * acquisition and accidentally abandon that engagement.
     */
    public record Observation(
            long nowMillis,
            Activity activity,
            double health,
            double maximumHealth,
            int foodLevel,
            boolean hasFood,
            boolean hasMeleeWeapon,
            boolean hasShield,
            boolean shieldReady,
            int armorPoints,
            boolean coverAvailable,
            CombatStance stance,
            List<Threat> threats) {
        public Observation {
            if (nowMillis < 0L) throw new IllegalArgumentException("nowMillis cannot be negative");
            activity = Objects.requireNonNull(activity, "activity");
            if (!Double.isFinite(maximumHealth) || maximumHealth <= 0.0) {
                throw new IllegalArgumentException("maximumHealth must be finite and positive");
            }
            if (!Double.isFinite(health) || health < 0.0 || health > maximumHealth) {
                throw new IllegalArgumentException("health must be inside [0, maximumHealth]");
            }
            if (foodLevel < 0 || foodLevel > 20) {
                throw new IllegalArgumentException("foodLevel must be inside [0, 20]");
            }
            if (shieldReady && !hasShield) {
                throw new IllegalArgumentException("a ready shield requires an available shield");
            }
            if (armorPoints < 0 || armorPoints > 30) {
                throw new IllegalArgumentException("armorPoints must be inside [0, 30]");
            }
            stance = Objects.requireNonNullElse(stance, CombatStance.DEFENSIVE);
            threats = List.copyOf(Objects.requireNonNull(threats, "threats"));
        }

        /** Source-compatible constructor for observations captured before armor readiness. */
        public Observation(
                long nowMillis,
                Activity activity,
                double health,
                double maximumHealth,
                int foodLevel,
                boolean hasFood,
                boolean hasMeleeWeapon,
                boolean hasShield,
                boolean shieldReady,
                boolean coverAvailable,
                CombatStance stance,
                List<Threat> threats) {
            this(nowMillis, activity, health, maximumHealth, foodLevel, hasFood,
                    hasMeleeWeapon, hasShield, shieldReady, 0, coverAvailable,
                    stance, threats);
        }

        /** Source-compatible constructor for the pre-2.17 tactical observation. */
        public Observation(
                long nowMillis,
                Activity activity,
                double health,
                double maximumHealth,
                int foodLevel,
                boolean hasFood,
                boolean hasMeleeWeapon,
                boolean hasShield,
                boolean shieldReady,
                boolean coverAvailable,
                List<Threat> threats) {
            this(nowMillis, activity, health, maximumHealth, foodLevel, hasFood,
                    hasMeleeWeapon, hasShield, shieldReady, 0, coverAvailable,
                    CombatStance.DEFENSIVE, threats);
        }

        public double healthRatio() {
            return health / maximumHealth;
        }

        public boolean canEat() {
            return hasFood && foodLevel < 20;
        }
    }

    public record EscapeVector(double x, double z) {
        public static final EscapeVector NONE = new EscapeVector(0.0, 0.0);

        public EscapeVector {
            if (!Double.isFinite(x) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("escape vector must be finite");
            }
            double length = Math.sqrt(x * x + z * z);
            if (length > 1.000_001) {
                throw new IllegalArgumentException("escape vector cannot exceed unit length");
            }
        }
    }

    public record Decision(
            Action action,
            LoadoutIntent loadout,
            Optional<String> targetId,
            EscapeVector escape,
            double aggregatePressure,
            boolean approachAllowed,
            boolean eatWhenSafe,
            long resumeAtMillis,
            String detail) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            loadout = Objects.requireNonNull(loadout, "loadout");
            targetId = Objects.requireNonNull(targetId, "targetId");
            escape = Objects.requireNonNull(escape, "escape");
            if (!Double.isFinite(aggregatePressure) || aggregatePressure < 0.0) {
                throw new IllegalArgumentException("aggregatePressure must be finite and non-negative");
            }
            if (resumeAtMillis < 0L) throw new IllegalArgumentException("resumeAtMillis cannot be negative");
            detail = Objects.requireNonNull(detail, "detail").trim();
            if (detail.isEmpty()) throw new IllegalArgumentException("detail cannot be blank");
            if (approachAllowed && (action == Action.HARD_RETREAT
                    || action == Action.RETREAT
                    || action == Action.BOUNDED_RETREAT)) {
                throw new IllegalArgumentException("retreat decisions cannot permit approach");
            }
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
