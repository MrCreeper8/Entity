package dev.entity.client.platform;

import dev.entity.client.autonomy.policy.TacticalCombatPolicy;
import dev.entity.client.bridge.ServerDamageEvent;
import dev.entity.client.bridge.ServerThreatTargetEvent;
import dev.entity.client.control.DirectBodyController;
import dev.entity.client.control.MinecraftEntityAttackGateway;
import dev.entity.client.protection.AuthoritativeThreatLedger;
import dev.entity.client.protection.DistantThreatEpisodeRetention;
import dev.entity.client.protection.DistantThreatDisengagementPolicy;
import dev.entity.client.protection.DistantThreatObservationFactory;
import dev.entity.client.protection.ProtectionTargetSelector;
import dev.entity.client.protection.CombatTargetSession;
import dev.entity.client.protection.SoleTargetSuppressionSession;
import dev.entity.client.protection.TacticalProtectionPlan;
import dev.entity.client.protection.VisibleRangedPressurePolicy;
import dev.entity.client.protection.ThreatEvidenceSources;
import dev.entity.core.control.ControlLease;
import dev.entity.core.port.PlatformPort;
import dev.entity.core.protection.ProtectionPolicy;
import dev.entity.core.survival.SurvivalAction;
import dev.entity.core.survival.WorldSnapshot;
import dev.entity.core.stewardship.EntityDispositionPolicy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.Items;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Yarn/Fabric observation and direct-input boundary for the pure Java core. */
public final class FabricPlatformPort implements PlatformPort {
    private static final long RECENT_ATTACK_MILLIS = 10_000;
    private static final double CLOSE_HOSTILE_DISTANCE_SQUARED = 6.0 * 6.0;
    private static final double PROJECTILE_PRESSURE_DISTANCE_SQUARED = 32.0 * 32.0;
    private static final double MOVING_PROJECTILE_SPEED_SQUARED = 0.01;

    private final MinecraftClient client;
    private final DirectBodyController body;
    private final MinecraftEntityAttackGateway entityAttacks;
    private final Supplier<ProtectionPolicy.Settings> protectionSettings;
    private final Logger logger;
    private final ThreatEvidenceSources<RecentThreat> threatEvidence =
            new ThreatEvidenceSources<>();
    private final Map<String, Integer> lastDamageStamps = new HashMap<>();
    private long latestLocalProtectedDamageAt = Long.MIN_VALUE;
    private long latestAuthoritativeProtectedDamageAt = Long.MIN_VALUE;

    private String publishedStatus = "starting";
    private List<WorldSnapshot.Threat> observedThreats = List.of();
    private Map<String, Entity> observedThreatEntities = Map.of();
    private final CombatTargetSession combatTargets = new CombatTargetSession();
    private final DistantThreatEpisodeRetention distantThreatRetention =
            new DistantThreatEpisodeRetention();

    public FabricPlatformPort(
            MinecraftClient client,
            DirectBodyController body,
            Supplier<ProtectionPolicy.Settings> protectionSettings,
            Logger logger) {
        this.client = Objects.requireNonNull(client, "client");
        this.body = Objects.requireNonNull(body, "body");
        this.entityAttacks = MinecraftEntityAttackGateway.shared(client);
        this.protectionSettings = Objects.requireNonNull(protectionSettings, "protectionSettings");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public WorldSnapshot observe(long nowMillis) {
        var player = client.player;
        if (player == null) {
            observedThreats = List.of();
            observedThreatEntities = Map.of();
            distantThreatRetention.clear();
            return WorldSnapshot.safe(nowMillis);
        }
        List<WorldSnapshot.Threat> threats = observeThreats(
                player, protectionSettings.get(), nowMillis);
        return new WorldSnapshot(
                nowMillis,
                Math.max(0.0, player.getHealth()),
                Math.max(1.0, player.getMaxHealth()),
                Math.max(0, player.getAir()),
                Math.max(1, player.getMaxAir()),
                Math.max(0, Math.min(20, player.getHungerManager().getFoodLevel())),
                body.hasConsumableFood(),
                player.isTouchingWater(),
                player.isSubmergedInWater(),
                player.isInLava(),
                player.isOnFire(),
                player.isInsideWall(),
                Math.max(0.0, player.fallDistance),
                player.isOnGround(),
                hasWaterBucket(),
                player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE),
                body.estimatedAirTicksToBreathableSpace(nowMillis),
                threats);
    }

    @Override
    public void execute(ControlLease lease, BodyIntent intent) {
        if (!"survival".equalsIgnoreCase(intent.type())) return;
        String rawAction = intent.arguments().getOrDefault("action", "NONE");
        SurvivalAction action;
        try {
            action = SurvivalAction.valueOf(rawAction.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            action = SurvivalAction.NONE;
        }
        body.executeSurvival(lease, action);
    }

    public void executeSurvival(ControlLease lease, SurvivalAction action) {
        body.executeSurvival(lease, action);
    }

    @Override
    public void cancelControls(long invalidatedEpoch, String reason) {
        body.cancelControls(invalidatedEpoch, reason);
    }

    @Override
    public void publishStatus(String humanReadableStatus) {
        if (Objects.equals(publishedStatus, humanReadableStatus)) return;
        publishedStatus = humanReadableStatus;
        logger.info("Entity state: {}", humanReadableStatus);
    }

    public String publishedStatus() {
        return publishedStatus;
    }

    /** Adds one Paper-confirmed self/owner attacker to the next protection snapshot. */
    public void acceptServerDamage(ServerDamageEvent damage) {
        Objects.requireNonNull(damage, "damage");
        latestAuthoritativeProtectedDamageAt = System.currentTimeMillis();
        damage.attacker().ifPresent(attacker -> {
            threatEvidence.authoritative().confirmDamage(
                    authoritativeFacts(
                            attacker.uuid(),
                            attacker.type(),
                            damage.target() == ServerDamageEvent.Target.OWNER,
                            damage.targetUuid(),
                            damage.targetName()),
                    damage.damage(),
                    damage.timestamp(),
                    System.currentTimeMillis());
        });
    }

    /** Applies one Paper-confirmed hostile target acquisition or exact clear. */
    public void acceptServerThreatTarget(ServerThreatTargetEvent event) {
        Objects.requireNonNull(event, "event");
        WorldSnapshot.ThreatTarget target = event.target() == ServerThreatTargetEvent.Target.OWNER
                ? WorldSnapshot.ThreatTarget.OWNER
                : WorldSnapshot.ThreatTarget.SELF;
        threatEvidence.authoritative().setTargeting(
                authoritativeFacts(
                        event.attacker().uuid(),
                        event.attacker().type(),
                        target == WorldSnapshot.ThreatTarget.OWNER,
                        event.targetUuid(),
                        event.targetName()),
                event.active(),
                event.timestamp(),
                System.currentTimeMillis());
    }

    /** Revokes all state derived from the previous authenticated Paper connection. */
    public void resetServerThreats() {
        threatEvidence.resetAuthoritative();
        latestAuthoritativeProtectedDamageAt = Long.MIN_VALUE;
        observedThreats = List.of();
        observedThreatEntities = Map.of();
    }

    public void invalidatePlayerAttacker(java.util.UUID attackerUuid, long serverTimestamp) {
        threatEvidence.authoritative().invalidateAttacker(attackerUuid, serverTimestamp);
        threatEvidence.removeLocalIf(recent -> recent.entity().getUuid().equals(attackerUuid));
        observedThreats = observedThreats.stream()
                .filter(threat -> !threat.entityId().equals(attackerUuid.toString())).toList();
        // Rebuild the entity projection on the next ordinary observation; no old-life object
        // may be selected during the packet-to-next-tick boundary.
        java.util.HashMap<String, Entity> remaining = new java.util.HashMap<>(observedThreatEntities);
        remaining.remove(attackerUuid.toString());
        observedThreatEntities = Map.copyOf(remaining);
    }

    public Optional<ProtectionTarget> selectedProtectionTarget(ProtectionPolicy.Settings settings) {
        return selectedProtectionTarget(settings, ProtectionPolicy.ActivityContext.TRAVEL);
    }

    public Optional<ProtectionTarget> selectedProtectionTarget(
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext) {
        return selectedProtectionTarget(settings, activityContext, "");
    }

    public Optional<ProtectionTarget> selectedProtectionTarget(
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext,
            String retainedThreatKey) {
        return ProtectionTargetSelector.select(
                        observedThreats, settings, activityContext, retainedThreatKey)
                .flatMap(threat -> {
                    Entity entity = observedThreatEntities.get(threat.entityId());
                    return entity != null && entity.isAlive() && !entity.isRemoved()
                            ? Optional.of(new ProtectionTarget(entity, threat))
                            : Optional.empty();
                });
    }

    /** Resolves a one-tick core-authorized exact UUID against the unmodified raw observation. */
    public Optional<ProtectionTarget> selectedProtectionDecision(
            ProtectionPolicy.Settings settings, WorldSnapshot.Threat decisionThreat) {
        return ProtectionTargetSelector.resolveDecision(observedThreats, settings, decisionThreat)
                .flatMap(threat -> {
                    Entity entity = observedThreatEntities.get(threat.entityId());
                    return entity != null && entity.isAlive() && !entity.isRemoved()
                            ? Optional.of(new ProtectionTarget(entity, threat)) : Optional.empty();
                });
    }

    /** Resolves a one-tick core-authorized exact UUID against the unmodified raw observation. */
    public Optional<ProtectionTarget> selectedProtectionTargetExact(
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext,
            String exactEntityId) {
        return selectedProtectionTargetExact(settings, activityContext, exactEntityId, "");
    }

    public Optional<ProtectionTarget> selectedProtectionTargetExact(
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext,
            String exactEntityId,
            String retainedThreatKey) {
        return ProtectionTargetSelector.selectReactivated(
                        observedThreats, settings, activityContext, exactEntityId, retainedThreatKey)
                .flatMap(threat -> {
                    Entity entity = observedThreatEntities.get(threat.entityId());
                    return entity != null && entity.isAlive() && !entity.isRemoved()
                            ? Optional.of(new ProtectionTarget(entity, threat))
                            : Optional.empty();
                });
    }

    /**
     * Builds one tactical observation from the same loaded, policy-eligible
     * hostile set used by core protection. Duplicate SELF/OWNER observations
     * of one physical mob are collapsed before aggregate pressure is counted.
     */
    public Optional<TacticalProtection> tacticalProtection(
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext,
            String retainedThreatKey,
            long nowMillis) {
        var player = client.player;
        if (player == null) return Optional.empty();

        LinkedHashMap<String, WorldSnapshot.Threat> eligibleByEntity = new LinkedHashMap<>();
        for (WorldSnapshot.Threat threat : ProtectionTargetSelector.eligibleForTactics(
                observedThreats, settings, activityContext, retainedThreatKey)) {
            eligibleByEntity.merge(threat.entityId(), threat, FabricPlatformPort::strongerObservation);
        }
        if (eligibleByEntity.isEmpty()) return Optional.empty();

        ArrayList<TacticalCombatPolicy.Threat> tacticalThreats = new ArrayList<>();
        ArrayList<VisibleRangedPressurePolicy.Candidate> rangedPressureCandidates =
                new ArrayList<>();
        boolean coverAvailable = false;
        for (WorldSnapshot.Threat threat : eligibleByEntity.values()) {
            Entity entity = observedThreatEntities.get(threat.entityId());
            if (entity == null || !entity.isAlive() || entity.isRemoved()) continue;
            TacticalCombatPolicy.ThreatKind kind =
                    TacticalProtectionPlan.threatKind(threat.entityType());
            boolean primed = entity instanceof CreeperEntity creeper
                    && creeper.getFuseSpeed() > 0;
            tacticalThreats.add(new TacticalCombatPolicy.Threat(
                    threat.entityId(),
                    kind,
                    entity.getX() - player.getX(),
                    entity.getZ() - player.getZ(),
                    threat.estimatedDamage(),
                    threat.activelyAttacking(),
                    primed,
                    primed ? 0 : -1));
            rangedPressureCandidates.add(new VisibleRangedPressurePolicy.Candidate(
                    threat.entityId(),
                    true,
                    entity instanceof AbstractSkeletonEntity,
                    entity.isAlive() && !entity.isRemoved(),
                    player.canSee(entity),
                    threat.activelyAttacking(),
                    threat.estimatedDamage(),
                    threat.distance()));
            if (kind == TacticalCombatPolicy.ThreatKind.SKELETON
                    && !player.canSee(entity)) {
                coverAvailable = true;
            }
        }
        if (tacticalThreats.isEmpty()) return Optional.empty();

        boolean hasShield = hasCarriedShield();
        boolean shieldReady = player.getOffHandStack().isOf(Items.SHIELD)
                && !player.getItemCooldownManager().isCoolingDown(player.getOffHandStack());
        TacticalCombatPolicy.Observation observation = new TacticalCombatPolicy.Observation(
                nowMillis,
                switch (activityContext) {
                    case IDLE -> TacticalCombatPolicy.Activity.IDLE;
                    case TRAVEL -> TacticalCombatPolicy.Activity.TRAVEL;
                    case STATIONARY_WORK -> TacticalCombatPolicy.Activity.STATIONARY_WORK;
                },
                Math.max(0.0, player.getHealth()),
                Math.max(1.0, player.getMaxHealth()),
                Math.max(0, Math.min(20, player.getHungerManager().getFoodLevel())),
                body.hasConsumableFood(),
                hasCarriedMeleeWeapon(),
                hasShield,
                shieldReady,
                Math.max(0, player.getArmor()),
                coverAvailable,
                switch (settings.combatMode()) {
                    case AVOID -> TacticalCombatPolicy.CombatStance.AVOID;
                    case DEFENSIVE -> TacticalCombatPolicy.CombatStance.DEFENSIVE;
                    case AGGRESSIVE -> TacticalCombatPolicy.CombatStance.AGGRESSIVE;
                },
                tacticalThreats);
        TacticalCombatPolicy.Decision decision = combatTargets.decide(observation);
        Optional<ProtectionTarget> target = decision.targetId().flatMap(id -> {
            Entity entity = observedThreatEntities.get(id);
            WorldSnapshot.Threat threat = eligibleByEntity.get(id);
            return entity != null && threat != null && entity.isAlive() && !entity.isRemoved()
                    ? Optional.of(new ProtectionTarget(entity, threat))
                    : Optional.empty();
        });
        Optional<ProtectionTarget> visibleRangedPressure =
                VisibleRangedPressurePolicy.select(rangedPressureCandidates).flatMap(id -> {
                    Entity entity = observedThreatEntities.get(id);
                    WorldSnapshot.Threat threat = eligibleByEntity.get(id);
                    return entity != null && threat != null && entity.isAlive() && !entity.isRemoved()
                            && player.canSee(entity)
                            ? Optional.of(new ProtectionTarget(entity, threat))
                            : Optional.empty();
                });
        return Optional.of(new TacticalProtection(
                decision, target, visibleRangedPressure, tacticalThreats.size()));
    }

    /** Reports one watchdog-proven route failure without immediately abandoning the exact mob. */
    public void markTacticalProtectionTargetUnreachable(String targetId, long nowMillis) {
        combatTargets.markUnreachable(targetId, nowMillis);
    }

    /** Clears a stale unreachable mark after the exact retained target makes progress. */
    public void markTacticalProtectionTargetProgress(String targetId) {
        combatTargets.markResolutionProgress(targetId);
    }

    /** Ends all target ownership at the protection episode boundary. */
    public void clearTacticalProtectionTargetSession() {
        combatTargets.clear();
    }

    /** Ends exact loaded-world retention at the same lifecycle boundary as Runtime's policy. */
    public void clearDistantThreatRetention() {
        distantThreatRetention.clear();
    }

    /**
     * Builds one raw-fact observation for the episode-local distant-threat state machine.
     *
     * <p>The first sample follows the same selector used by core protection. Later samples retain
     * the exact UUID even outside fresh-acquisition range. Nothing here mutates observed threats,
     * tactical target ownership, or telemetry; Runtime alone decides whether to omit that UUID
     * from its immutable core arbitration copy.</p>
     */
    public Optional<DistantThreatDisengagementPolicy.Observation>
            distantThreatDisengagementObservation(
            String episodeId,
            String exactTargetId,
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext,
            String retainedThreatKey,
            long nowMillis,
            boolean survivalActive) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(activityContext, "activityContext");
        String requestedTarget = Objects.requireNonNullElse(exactTargetId, "").trim();
        if (!requestedTarget.isEmpty()
                && !distantThreatRetention.matchesBoundary(
                episodeId, requestedTarget, settings)) {
            // A policy identity from another mission/settings boundary cannot bypass fresh range
            // acquisition merely because its UUID was present in the preceding raw snapshot.
            requestedTarget = "";
        }
        WorldSnapshot.Threat exactThreat = requestedTarget.isEmpty()
                ? ProtectionTargetSelector.select(
                observedThreats, settings, activityContext, retainedThreatKey).orElse(null)
                : ProtectionTargetSelector.selectExactEntity(
                observedThreats, settings, requestedTarget)
                .orElse(null);
        if (exactThreat == null && requestedTarget.isEmpty()) return Optional.empty();

        String targetId = exactThreat == null ? requestedTarget : exactThreat.entityId();
        Entity entity = observedThreatEntities.get(targetId);
        var player = client.player;
        boolean present = player != null && entity != null && exactThreat != null
                && entity.isAlive() && !entity.isRemoved();
        if (!present) {
            return Optional.of(DistantThreatObservationFactory.from(
                    new DistantThreatObservationFactory.Facts(
                            nowMillis,
                            episodeId,
                            targetId,
                            DistantThreatObservationFactory.CombatShape.OTHER,
                            false,
                            0.0,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            player == null ? 0.0 : Math.max(0.0F, player.getHealth()),
                            player == null ? 1.0 : Math.max(1.0F, player.getMaxHealth()),
                            0.0,
                            survivalActive)));
        }

        String exactRetainedKey = ProtectionTargetSelector.threatKey(exactThreat);
        LinkedHashMap<String, WorldSnapshot.Threat> eligibleByEntity = new LinkedHashMap<>();
        for (WorldSnapshot.Threat threat : ProtectionTargetSelector.eligibleForTactics(
                observedThreats, settings, activityContext, exactRetainedKey)) {
            eligibleByEntity.merge(
                    threat.entityId(), threat, FabricPlatformPort::strongerObservation);
        }
        boolean otherEligibleThreat = eligibleByEntity.keySet().stream()
                .anyMatch(id -> !id.equals(targetId));
        ArrayList<TacticalCombatPolicy.Threat> modeledThreats = new ArrayList<>();
        for (WorldSnapshot.Threat threat : eligibleByEntity.values()) {
            Entity candidate = observedThreatEntities.get(threat.entityId());
            if (candidate == null || !candidate.isAlive() || candidate.isRemoved()) continue;
            TacticalCombatPolicy.ThreatKind kind =
                    TacticalProtectionPlan.threatKind(threat.entityType());
            boolean primed = candidate instanceof CreeperEntity creeper
                    && creeper.getFuseSpeed() > 0;
            modeledThreats.add(new TacticalCombatPolicy.Threat(
                    threat.entityId(),
                    kind,
                    candidate.getX() - player.getX(),
                    candidate.getZ() - player.getZ(),
                    threat.estimatedDamage(),
                    threat.activelyAttacking(),
                    primed,
                    primed ? 0 : -1));
        }

        double distance = Math.sqrt(player.squaredDistanceTo(entity));
        boolean projectilePressure = hasProjectilePressure(entity, exactThreat, settings);
        DistantThreatObservationFactory.CombatShape combatShape =
                DistantThreatObservationFactory.scopedCombatShape(
                        disengagementCombatShape(exactThreat, entity, projectilePressure),
                        exactThreat.target());
        distantThreatRetention.arm(
                episodeId,
                targetId,
                exactThreat.target(),
                combatShape,
                settings);
        boolean trustedLocalTargeting =
                DistantThreatObservationFactory.trustedLocalTargetingEvidence(
                        hasTrustedLocalTargetingEvidence(entity, settings),
                        combatShape,
                        distance);
        return Optional.of(DistantThreatObservationFactory.from(
                new DistantThreatObservationFactory.Facts(
                        nowMillis,
                        episodeId,
                        targetId,
                        combatShape,
                        true,
                        distance,
                        trustedLocalTargeting,
                        hasAuthoritativeAttackerEvidence(entity, nowMillis),
                        hasFreshIncomingDamageEvidence(entity, nowMillis),
                        projectilePressure,
                        player.canSee(entity),
                        otherEligibleThreat,
                        Math.max(0.0F, player.getHealth()),
                        Math.max(1.0F, player.getMaxHealth()),
                        TacticalCombatPolicy.modeledNearTermDamage(modeledThreats),
                        survivalActive,
                        entity instanceof CreeperEntity,
                        entity instanceof CreeperEntity creeper
                                && (creeper.getFuseSpeed() > 0 || creeper.getLerpedFuseTime(1.0F) > 0.01F),
                        entity instanceof CreeperEntity creeper && creeper.isCharged())));
    }

    /**
     * Builds a fresh fail-closed safety view for an episode-local suppression candidate.
     *
     * <p>This reads the raw observation retained by {@link #observe(long)}; it never removes facts
     * from platform state. Runtime may use the result to construct a filtered copy solely for the
     * core arbitration call, while tactical decisions and black-box observation keep the complete
     * world truth.</p>
     */
    public SoleTargetSuppressionSession.SafetyObservation soleTargetSuppressionObservation(
            String episodeId,
            String exactTargetId,
            ProtectionPolicy.Settings settings,
            ProtectionPolicy.ActivityContext activityContext,
            long nowMillis,
            boolean survivalActive) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(activityContext, "activityContext");
        Entity entity = observedThreatEntities.get(exactTargetId);
        var player = client.player;
        WorldSnapshot.Threat exactThreat = observedThreats.stream()
                .filter(threat -> threat.entityId().equals(exactTargetId))
                .reduce(FabricPlatformPort::strongerObservation)
                .orElse(null);
        boolean present = player != null && entity != null && exactThreat != null
                && entity.isAlive() && !entity.isRemoved();
        if (!present) {
            return new SoleTargetSuppressionSession.SafetyObservation(
                    episodeId,
                    exactTargetId,
                    SoleTargetSuppressionSession.TargetKind.OTHER,
                    false,
                    0.0,
                    false,
                    false,
                    false,
                    false,
                    settings.combatMode() == ProtectionPolicy.CombatMode.AVOID,
                    true,
                    true,
                    false,
                    false,
                    survivalActive);
        }

        String retainedThreatKey = ProtectionTargetSelector.threatKey(exactThreat);
        LinkedHashMap<String, WorldSnapshot.Threat> eligibleByEntity = new LinkedHashMap<>();
        for (WorldSnapshot.Threat threat : ProtectionTargetSelector.eligibleForTactics(
                observedThreats, settings, activityContext, retainedThreatKey)) {
            eligibleByEntity.merge(
                    threat.entityId(), threat, FabricPlatformPort::strongerObservation);
        }
        boolean otherEligibleThreat = eligibleByEntity.keySet().stream()
                .anyMatch(id -> !id.equals(exactTargetId));
        boolean visibleRangedPressure = eligibleByEntity.keySet().stream()
                .map(observedThreatEntities::get)
                .filter(Objects::nonNull)
                .anyMatch(candidate -> candidate instanceof AbstractSkeletonEntity
                        && candidate.isAlive() && !candidate.isRemoved()
                        && player.canSee(candidate));

        ArrayList<TacticalCombatPolicy.Threat> modeledThreats = new ArrayList<>();
        for (WorldSnapshot.Threat threat : eligibleByEntity.values()) {
            Entity candidate = observedThreatEntities.get(threat.entityId());
            if (candidate == null || !candidate.isAlive() || candidate.isRemoved()) continue;
            TacticalCombatPolicy.ThreatKind kind =
                    TacticalProtectionPlan.threatKind(threat.entityType());
            boolean primed = candidate instanceof CreeperEntity creeper
                    && creeper.getFuseSpeed() > 0;
            modeledThreats.add(new TacticalCombatPolicy.Threat(
                    threat.entityId(),
                    kind,
                    candidate.getX() - player.getX(),
                    candidate.getZ() - player.getZ(),
                    threat.estimatedDamage(),
                    threat.activelyAttacking(),
                    primed,
                    primed ? 0 : -1));
        }

        double distance = Math.sqrt(player.squaredDistanceTo(entity));
        boolean targetingEvidence = hasTargetingEvidence(entity, settings, nowMillis);
        boolean freshDamageEvidence = hasFreshIncomingDamageEvidence(entity, nowMillis);
        SoleTargetSuppressionSession.TargetKind targetKind = suppressionTargetKind(exactThreat);
        boolean lowHealth = player.getHealth() / Math.max(1.0F, player.getMaxHealth())
                <= TacticalCombatPolicy.DEFAULT_LIMITS.lowHealthRatio();
        boolean modeledLethal = TacticalCombatPolicy.modeledNearTermDamage(modeledThreats)
                >= Math.max(0.0F, player.getHealth());
        boolean bodyBlocker = targetKind == SoleTargetSuppressionSession.TargetKind.ZOMBIE_CLASS
                && targetingEvidence
                && distance <= TacticalCombatPolicy.ACTIVE_BODY_BLOCKER_RADIUS;
        return new SoleTargetSuppressionSession.SafetyObservation(
                episodeId,
                exactTargetId,
                targetKind,
                true,
                distance,
                player.canSee(entity),
                targetingEvidence,
                freshDamageEvidence,
                otherEligibleThreat,
                settings.combatMode() == ProtectionPolicy.CombatMode.AVOID,
                lowHealth,
                modeledLethal,
                bodyBlocker,
                visibleRangedPressure,
                survivalActive);
    }

    private static SoleTargetSuppressionSession.TargetKind suppressionTargetKind(
            WorldSnapshot.Threat threat) {
        return switch (TacticalProtectionPlan.threatKind(threat.entityType())) {
            case MELEE -> SoleTargetSuppressionSession.TargetKind.ZOMBIE_CLASS;
            case SKELETON -> SoleTargetSuppressionSession.TargetKind.SKELETON_CLASS;
            case PLAYER -> SoleTargetSuppressionSession.TargetKind.PLAYER;
            case CREEPER -> SoleTargetSuppressionSession.TargetKind.CREEPER;
            case OTHER -> SoleTargetSuppressionSession.TargetKind.OTHER;
        };
    }

    private static DistantThreatObservationFactory.CombatShape disengagementCombatShape(
            WorldSnapshot.Threat threat,
            Entity entity,
            boolean projectilePressure) {
        return switch (TacticalProtectionPlan.threatKind(threat.entityType())) {
            case MELEE -> DistantThreatObservationFactory.CombatShape.ORDINARY_MELEE;
            case SKELETON -> {
                // Drowned is semantically ranged in the broad tactical allowlist, but an actual
                // drowned without a trident or live projectile is an ordinary melee pursuer. The
                // retained 15-33m regression depends on this runtime combat shape, not its label.
                boolean meleeDrowned = threat.entityType().equals("drowned")
                        && entity instanceof LivingEntity living
                        && !living.getMainHandStack().isOf(Items.TRIDENT)
                        && !projectilePressure;
                yield meleeDrowned
                        ? DistantThreatObservationFactory.CombatShape.ORDINARY_MELEE
                        : DistantThreatObservationFactory.CombatShape.ORDINARY_RANGED;
            }
            case PLAYER -> DistantThreatObservationFactory.CombatShape.PLAYER;
            case CREEPER -> DistantThreatObservationFactory.CombatShape.CREEPER;
            case OTHER -> DistantThreatObservationFactory.CombatShape.OTHER;
        };
    }

    private boolean hasTrustedLocalTargetingEvidence(
            Entity entity,
            ProtectionPolicy.Settings settings) {
        var player = client.player;
        if (player == null) return false;
        LivingEntity owner = findOwner(settings.ownerName()).orElse(null);
        if (!(entity instanceof HostileEntity hostile)) return false;
        LivingEntity target = hostile.getTarget();
        return target == player || owner != null && target == owner;
    }

    private boolean hasAuthoritativeAttackerEvidence(Entity entity, long nowMillis) {
        return authoritativeEvidence(entity, AuthoritativeThreatLedger.Target.SELF, nowMillis)
                .map(evidence -> evidence.targeting() || evidence.damage() > 0.0)
                .orElse(false)
                || authoritativeEvidence(
                entity, AuthoritativeThreatLedger.Target.OWNER, nowMillis)
                .map(evidence -> evidence.targeting() || evidence.damage() > 0.0)
                .orElse(false);
    }

    private boolean hasProjectilePressure(
            Entity attacker,
            WorldSnapshot.Threat exactThreat,
            ProtectionPolicy.Settings settings) {
        var player = client.player;
        if (client.world == null || player == null) return false;
        LivingEntity protectedEntity = exactThreat.target() == WorldSnapshot.ThreatTarget.OWNER
                ? findOwner(settings.ownerName()).orElse(null)
                : player;
        for (Entity candidate : client.world.getEntities()) {
            if (!(candidate instanceof ProjectileEntity projectile)
                    || !candidate.isAlive()
                    || candidate.isRemoved()
                    || projectile.getOwner() != attacker
                    || candidate.getVelocity().lengthSquared()
                    < MOVING_PROJECTILE_SPEED_SQUARED) continue;
            // OWNER protection must not miss an inbound projectile merely because Entity stands
            // elsewhere. Self proximity is also retained as a conservative personal-safety edge.
            if (candidate.squaredDistanceTo(player) <= PROJECTILE_PRESSURE_DISTANCE_SQUARED
                    || protectedEntity != null
                    && candidate.squaredDistanceTo(protectedEntity)
                    <= PROJECTILE_PRESSURE_DISTANCE_SQUARED) return true;
        }
        return false;
    }

    private boolean hasTargetingEvidence(
            Entity entity,
            ProtectionPolicy.Settings settings,
            long nowMillis) {
        if (hasTrustedLocalTargetingEvidence(entity, settings)) return true;
        return authoritativeEvidence(entity, AuthoritativeThreatLedger.Target.SELF, nowMillis)
                .map(AuthoritativeThreatLedger.Evidence::targeting)
                .orElse(false)
                || authoritativeEvidence(
                entity, AuthoritativeThreatLedger.Target.OWNER, nowMillis)
                .map(AuthoritativeThreatLedger.Evidence::targeting)
                .orElse(false);
    }

    private boolean hasFreshIncomingDamageEvidence(Entity entity, long nowMillis) {
        // Suppression requires a globally quiet incoming-damage window. A shot from an off-range
        // or not-yet-eligible second attacker must restore the raw world even when the exact
        // candidate itself has not dealt damage.
        if (freshAt(latestLocalProtectedDamageAt, nowMillis)
                || freshAt(latestAuthoritativeProtectedDamageAt, nowMillis)) return true;
        boolean local = threatEvidence.localSnapshot().stream()
                .anyMatch(recent -> recent.entity().getUuid().equals(entity.getUuid())
                        && nowMillis < recent.expiresAtMillis());
        if (local) return true;
        return authoritativeEvidence(entity, AuthoritativeThreatLedger.Target.SELF, nowMillis)
                .map(evidence -> evidence.damage() > 0.0)
                .orElse(false)
                || authoritativeEvidence(
                entity, AuthoritativeThreatLedger.Target.OWNER, nowMillis)
                .map(evidence -> evidence.damage() > 0.0)
                .orElse(false);
    }

    private static boolean freshAt(long evidenceAtMillis, long nowMillis) {
        return evidenceAtMillis != Long.MIN_VALUE
                && Math.max(0L, nowMillis - evidenceAtMillis) < RECENT_ATTACK_MILLIS;
    }

    private Optional<AuthoritativeThreatLedger.Evidence> authoritativeEvidence(
            Entity entity,
            AuthoritativeThreatLedger.Target target,
            long nowMillis) {
        return threatEvidence.authoritative().find(entity.getUuid(), target, nowMillis);
    }

    private boolean hasCarriedShield() {
        var player = client.player;
        if (player == null) return false;
        if (player.getOffHandStack().isOf(Items.SHIELD)) return true;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(Items.SHIELD)) return true;
        }
        return false;
    }

    private boolean hasCarriedMeleeWeapon() {
        var player = client.player;
        if (player == null) return false;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            var id = Registries.ITEM.getId(stack.getItem());
            if (id != null && (id.getPath().endsWith("_sword")
                    || id.getPath().endsWith("_axe"))) return true;
        }
        return false;
    }

    private static WorldSnapshot.Threat strongerObservation(
            WorldSnapshot.Threat left,
            WorldSnapshot.Threat right) {
        if (left.activelyAttacking() != right.activelyAttacking()) {
            return right.activelyAttacking() ? right : left;
        }
        if (left.estimatedDamage() != right.estimatedDamage()) {
            return right.estimatedDamage() > left.estimatedDamage() ? right : left;
        }
        return right.distance() < left.distance() ? right : left;
    }

    private boolean hasWaterBucket() {
        var player = client.player;
        if (player == null) return false;
        if (player.getOffHandStack().isOf(Items.WATER_BUCKET)) return true;
        var inventory = player.getInventory();
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (inventory.getStack(slot).isOf(Items.WATER_BUCKET)) return true;
        }
        return false;
    }

    private List<WorldSnapshot.Threat> observeThreats(
            LivingEntity self,
            ProtectionPolicy.Settings settings,
            long nowMillis) {
        if (client.world == null) return List.of();
        LivingEntity owner = findOwner(settings.ownerName()).orElse(null);
        rememberFreshAttacker(self, WorldSnapshot.ThreatTarget.SELF, nowMillis);
        if (owner != null) {
            rememberFreshAttacker(owner, WorldSnapshot.ThreatTarget.OWNER, nowMillis);
        }

        threatEvidence.removeLocalIf(recent -> nowMillis >= recent.expiresAtMillis()
                || !recent.entity().isAlive() || recent.entity().isRemoved());
        threatEvidence.authoritative().prune(nowMillis);

        LinkedHashMap<String, ObservedThreat> candidates = new LinkedHashMap<>();
        for (RecentThreat recent : threatEvidence.localSnapshot()) {
            addCandidate(candidates, self, recent.entity(), recent.target(), true);
        }

        // Keep bridge-derived candidates separate from client-observed recent
        // attackers so a transport reset cannot erase genuine local evidence.
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)
                    || !entity.isAlive() || entity.isRemoved()) continue;
            for (WorldSnapshot.ThreatTarget target : WorldSnapshot.ThreatTarget.values()) {
                AuthoritativeThreatLedger.Evidence confirmed = threatEvidence.authoritative().find(
                        entity.getUuid(), ledgerTarget(target), nowMillis).orElse(null);
                if (confirmed == null
                        || !matchesProtectedTarget(confirmed, target, self, owner)) continue;
                addCandidate(candidates, self, living, target, true);
            }
        }

        double scanRadius = Math.max(8.0, settings.maximumEngageDistance());
        for (Entity entity : client.world.getOtherEntities(
                self,
                self.getBoundingBox().expand(scanRadius),
                candidate -> candidate instanceof HostileEntity && candidate.isAlive())) {
            HostileEntity hostile = (HostileEntity) entity;
            addHostileCandidates(candidates, self, owner, hostile);
        }
        addRetainedDistantThreatCandidate(candidates, self, owner, settings);

        ArrayList<WorldSnapshot.Threat> threats = new ArrayList<>();
        HashMap<String, Entity> entities = new HashMap<>();
        for (ObservedThreat candidate : candidates.values()) {
            if (candidate.entity() instanceof PlayerEntity
                    && !entityAttacks.permitsSelection(
                    candidate.entity(), EntityDispositionPolicy.Purpose.RETALIATION)) {
                // Player evidence is admitted only after a real hit/authoritative damage event.
                // The relationship boundary then removes the owner and trusted controllers before
                // they can become protection targets or trigger movement away from the owner.
                continue;
            }
            String id = candidate.entity().getUuidAsString();
            String type = EntityType.getId(candidate.entity().getType()).getPath();
            AuthoritativeThreatLedger.Evidence serverConfirmed = threatEvidence.authoritative().find(
                    candidate.entity().getUuid(), ledgerTarget(candidate.target()), nowMillis)
                    .orElse(null);
            threats.add(new WorldSnapshot.Threat(
                    id,
                    serverConfirmed == null
                            ? type
                            : simpleType(serverConfirmed.entityType()),
                    candidate.target(),
                    Math.sqrt(self.squaredDistanceTo(candidate.entity())),
                    Math.max(estimatedDamage(type, candidate.entity()),
                            serverConfirmed == null ? 0.0 : serverConfirmed.damage()),
                    candidate.activelyAttacking()));
            entities.put(id, candidate.entity());
        }
        observedThreats = List.copyOf(threats);
        observedThreatEntities = Map.copyOf(entities);
        return observedThreats;
    }

    /**
     * Resolves only the UUID already armed by the active SELF disengagement episode. This is not a
     * second acquisition scan: all type, target, projectile, distance, death, and unload facts are
     * recomputed from the current loaded entity before the candidate enters the raw snapshot.
     */
    private void addRetainedDistantThreatCandidate(
            Map<String, ObservedThreat> candidates,
            LivingEntity self,
            LivingEntity owner,
            ProtectionPolicy.Settings settings) {
        String retainedId = distantThreatRetention.targetId(settings).orElse("");
        if (retainedId.isBlank() || client.world == null) return;

        Entity retained = null;
        for (Entity entity : client.world.getEntities()) {
            if (entity.getUuidAsString().equals(retainedId)) {
                retained = entity;
                break;
            }
        }
        if (retained == null || !retained.isAlive() || retained.isRemoved()) {
            distantThreatRetention.resolve(
                    settings,
                    DistantThreatEpisodeRetention.LiveTarget.unavailable(retainedId));
            return;
        }

        String type = EntityType.getId(retained.getType()).getPath();
        double distance = Math.sqrt(self.squaredDistanceTo(retained));
        WorldSnapshot.Threat liveSelfThreat = new WorldSnapshot.Threat(
                retainedId,
                type,
                WorldSnapshot.ThreatTarget.SELF,
                distance,
                estimatedDamage(type, retained),
                retained instanceof HostileEntity hostile && hostile.getTarget() == self);
        boolean projectilePressure = hasProjectilePressure(retained, liveSelfThreat, settings);
        DistantThreatObservationFactory.CombatShape liveShape =
                disengagementCombatShape(liveSelfThreat, retained, projectilePressure);
        if (distantThreatRetention.resolve(
                settings,
                new DistantThreatEpisodeRetention.LiveTarget(
                        retainedId, true, true, liveShape, distance)).isEmpty()) return;
        if (!(retained instanceof HostileEntity hostile)) {
            distantThreatRetention.clear();
            return;
        }

        LivingEntity explicitTarget = hostile.getTarget();
        if (owner != null && explicitTarget == owner) {
            // The exact entity changed from SELF to OWNER. Keep that live danger raw and revoke
            // disengagement; Entity has no owner-relative separation actuator.
            distantThreatRetention.clear();
            addCandidate(
                    candidates,
                    self,
                    hostile,
                    WorldSnapshot.ThreatTarget.OWNER,
                    true);
            return;
        }
        if (explicitTarget != null && explicitTarget != self) {
            distantThreatRetention.clear();
            return;
        }
        addCandidate(
                candidates,
                self,
                hostile,
                WorldSnapshot.ThreatTarget.SELF,
                explicitTarget == self);
    }

    private Optional<LivingEntity> findOwner(String ownerName) {
        if (client.world == null || ownerName == null || ownerName.isBlank()) return Optional.empty();
        return client.world.getPlayers().stream()
                .filter(player -> player.getName().getString().equalsIgnoreCase(ownerName))
                .map(player -> (LivingEntity) player)
                .findFirst();
    }

    private void rememberFreshAttacker(
            LivingEntity protectedEntity,
            WorldSnapshot.ThreatTarget target,
            long nowMillis) {
        String protectedKey = target.name() + ':' + protectedEntity.getUuidAsString();
        int stamp = protectedEntity.getLastAttackedTime();
        Integer previous = lastDamageStamps.put(protectedKey, stamp);
        LivingEntity attacker = protectedEntity.getLastAttacker();
        boolean fresh = previous == null ? protectedEntity.hurtTime > 0 : stamp != previous;
        if (!fresh || attacker == null || attacker == protectedEntity
                || !attacker.isAlive() || attacker.isRemoved()) return;
        latestLocalProtectedDamageAt = nowMillis;
        String key = attacker.getUuidAsString() + '|' + target.name();
        threatEvidence.putLocal(key, new RecentThreat(
                attacker, target, nowMillis + RECENT_ATTACK_MILLIS));
    }

    private static void addHostileCandidates(
            Map<String, ObservedThreat> candidates,
            LivingEntity self,
            LivingEntity owner,
            HostileEntity hostile) {
        LivingEntity explicitTarget = hostile.getTarget();
        if (explicitTarget == self) {
            addCandidate(candidates, self, hostile, WorldSnapshot.ThreatTarget.SELF, true);
            return;
        }
        if (owner != null && explicitTarget == owner) {
            addCandidate(candidates, self, hostile, WorldSnapshot.ThreatTarget.OWNER, true);
            return;
        }
        if (explicitTarget != null) return;

        // HostileEntity is a class hierarchy, not aggression evidence. In particular,
        // walking near a neutral Enderman must not provoke it. Explicit targets above
        // and fresh local/Paper damage evidence remain admitted by their existing paths.
        if (!ProtectionPolicy.allowsProximityThreat(Registries.ENTITY_TYPE.getId(hostile.getType()).getPath())) return;

        double selfDistance = hostile.squaredDistanceTo(self);
        double ownerDistance = owner == null ? Double.POSITIVE_INFINITY : hostile.squaredDistanceTo(owner);
        // Keep both possible scopes. Choosing only the nearer player made an enabled owner-only
        // policy miss a hostile whenever Entity happened to be a fraction closer.
        if (selfDistance <= CLOSE_HOSTILE_DISTANCE_SQUARED) {
            addCandidate(candidates, self, hostile, WorldSnapshot.ThreatTarget.SELF, false);
        }
        if (ownerDistance <= CLOSE_HOSTILE_DISTANCE_SQUARED) {
            addCandidate(candidates, self, hostile, WorldSnapshot.ThreatTarget.OWNER, false);
        }
    }

    private static void addCandidate(
            Map<String, ObservedThreat> candidates,
            LivingEntity self,
            Entity entity,
            WorldSnapshot.ThreatTarget target,
            boolean activelyAttacking) {
        String key = entity.getUuidAsString() + '|' + target.name();
        ObservedThreat candidate = new ObservedThreat(entity, target, activelyAttacking);
        ObservedThreat existing = candidates.get(key);
        if (existing == null
                || activelyAttacking && !existing.activelyAttacking()
                || self.squaredDistanceTo(entity) < self.squaredDistanceTo(existing.entity())) {
            candidates.put(key, candidate);
        }
    }

    private static double estimatedDamage(String entityType, Entity entity) {
        return switch (entityType) {
            case "warden" -> 20;
            case "creeper" -> 12;
            case "wither", "ender_dragon", "ravager" -> 10;
            case "ghast", "blaze", "witch", "pillager", "skeleton" -> 6;
            default -> entity instanceof HostileEntity ? 4 : 3;
        };
    }

    private static String simpleType(String value) {
        int separator = value.indexOf(':');
        return separator >= 0 ? value.substring(separator + 1) : value;
    }

    private static AuthoritativeThreatLedger.Target ledgerTarget(
            WorldSnapshot.ThreatTarget target) {
        return target == WorldSnapshot.ThreatTarget.OWNER
                ? AuthoritativeThreatLedger.Target.OWNER
                : AuthoritativeThreatLedger.Target.SELF;
    }

    private static AuthoritativeThreatLedger.Facts authoritativeFacts(
            java.util.UUID attackerUuid,
            String attackerType,
            boolean owner,
            java.util.UUID targetUuid,
            String targetName) {
        return new AuthoritativeThreatLedger.Facts(
                attackerUuid,
                attackerType,
                owner ? AuthoritativeThreatLedger.Target.OWNER
                        : AuthoritativeThreatLedger.Target.SELF,
                targetUuid,
                targetName);
    }

    private static boolean matchesProtectedTarget(
            AuthoritativeThreatLedger.Evidence confirmed,
            WorldSnapshot.ThreatTarget target,
            LivingEntity self,
        LivingEntity owner) {
        LivingEntity protectedEntity = target == WorldSnapshot.ThreatTarget.SELF ? self : owner;
        if (protectedEntity == null || confirmed.target() != ledgerTarget(target)) return false;
        return protectedEntity.getUuid().equals(confirmed.targetUuid())
                && protectedEntity.getName().getString().equalsIgnoreCase(confirmed.targetName());
    }

    public record ProtectionTarget(Entity entity, WorldSnapshot.Threat threat) {
    }

    public record TacticalProtection(
            TacticalCombatPolicy.Decision decision,
            Optional<ProtectionTarget> target,
            Optional<ProtectionTarget> visibleRangedPressure,
            int eligibleThreatCount) {
        public TacticalProtection {
            Objects.requireNonNull(decision, "decision");
            target = Objects.requireNonNull(target, "target");
            visibleRangedPressure = Objects.requireNonNull(
                    visibleRangedPressure, "visibleRangedPressure");
            if (eligibleThreatCount < 1) {
                throw new IllegalArgumentException("eligibleThreatCount must be positive");
            }
        }
    }

    private record RecentThreat(
            LivingEntity entity,
            WorldSnapshot.ThreatTarget target,
            long expiresAtMillis) {
    }

    private record ObservedThreat(
            Entity entity,
            WorldSnapshot.ThreatTarget target,
            boolean activelyAttacking) {
    }
}
