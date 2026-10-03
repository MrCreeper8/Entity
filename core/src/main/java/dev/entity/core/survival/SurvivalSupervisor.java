package dev.entity.core.survival;

import dev.entity.core.control.BodyChannel;
import dev.entity.core.control.ControlPriority;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Non-optional reflex layer. Protection settings never disable environmental
 * survival. Exit hysteresis prevents controls oscillating at water/lava edges.
 */
public final class SurvivalSupervisor {
    public static final int DEFAULT_DROWNING_ENTER_AIR = 240;
    public static final int DEFAULT_DROWNING_EXIT_AIR = 280;
    public static final int DROWNING_ROUTE_RESERVE_TICKS = 40;
    public static final int MINIMUM_DROWNING_EMERGENCY_AIR = 60;
    public static final double FALL_ENTER_DISTANCE = 3.5;
    /**
     * Begin eating with enough health to finish vanilla's 1.6-second use cycle
     * before one more ordinary hostile hit becomes fatal. The retained natural
     * run proved that entering at thirty percent was one hit too late.
     */
    public static final double HEALTH_RECOVERY_ENTER_FRACTION = 0.40;
    public static final double HEALTH_RECOVERY_EXIT_FRACTION = 0.60;
    public static final int NATURAL_REGEN_FOOD_LEVEL = 18;

    private static final int HUNGER_RECOVERY_ENTER_LEVEL = 14;
    private static final int HUNGER_RECOVERY_EXIT_LEVEL = 19;
    private static final int FULL_HUNGER_LEVEL = 20;

    private final long clearHoldMillis;
    private final int drowningEnterAir;
    private final int drowningExitAir;
    private final double fallEnterDistance;
    private final Map<SurvivalAction, Latch> latches = new EnumMap<>(SurvivalAction.class);

    public SurvivalSupervisor() {
        this(1_000, DEFAULT_DROWNING_ENTER_AIR, DEFAULT_DROWNING_EXIT_AIR, FALL_ENTER_DISTANCE);
    }

    public SurvivalSupervisor(long clearHoldMillis) {
        this(clearHoldMillis, DEFAULT_DROWNING_ENTER_AIR, DEFAULT_DROWNING_EXIT_AIR, FALL_ENTER_DISTANCE);
    }

    public SurvivalSupervisor(
            long clearHoldMillis,
            int drowningEnterAir,
            int drowningExitAir,
            double fallEnterDistance) {
        if (clearHoldMillis < 0 || drowningEnterAir < 0 || drowningExitAir <= drowningEnterAir
                || fallEnterDistance <= 0) {
            throw new IllegalArgumentException("Invalid survival thresholds");
        }
        this.clearHoldMillis = clearHoldMillis;
        this.drowningEnterAir = drowningEnterAir;
        this.drowningExitAir = drowningExitAir;
        this.fallEnterDistance = fallEnterDistance;
        for (SurvivalAction action : SurvivalAction.values()) {
            if (action != SurvivalAction.NONE) {
                latches.put(action, new Latch());
            }
        }
    }

    public synchronized SafetyDirective evaluate(WorldSnapshot world) {
        long now = world.nowMillis();
        update(
                SurvivalAction.FALL_CLUTCH,
                !world.onGround() && world.fallDistance() >= fallEnterDistance,
                world.onGround() || world.fallDistance() < 0.5,
                now);
        update(
                SurvivalAction.ESCAPE_SUFFOCATION,
                world.insideWall(),
                !world.insideWall(),
                now);
        update(
                SurvivalAction.SURFACE_FOR_AIR,
                drowningDanger(world),
                !world.headSubmerged() && world.remainingAir() >= drowningExitAir,
                now);
        update(SurvivalAction.ESCAPE_LAVA, world.inLava(), !world.inLava(), now);
        update(SurvivalAction.EXTINGUISH_FIRE, world.onFire(), !world.onFire(), now);
        updateRecovery(world, now);

        if (active(SurvivalAction.FALL_CLUTCH)) {
            String method = world.waterBucketAvailable() ? "attempt water-bucket clutch" : "steer toward survivable landing";
            return directive(SurvivalAction.FALL_CLUTCH, ControlPriority.SURVIVAL_FALL, method);
        }
        if (active(SurvivalAction.ESCAPE_SUFFOCATION)) {
            return directive(
                    SurvivalAction.ESCAPE_SUFFOCATION,
                    ControlPriority.SURVIVAL_SUFFOCATION,
                    "inside a solid block; break or move into verified open space immediately");
        }
        if (active(SurvivalAction.SURFACE_FOR_AIR)) {
            return directive(
                    SurvivalAction.SURFACE_FOR_AIR,
                    ControlPriority.SURVIVAL_DROWNING,
                    "air is critically low; reach breathable space");
        }
        // Keep the lava latch for edge hysteresis, but once the body is physically dry do not
        // spend that hold window executing an already-finished lava action while fire keeps
        // dealing damage. The real-world failure exited lava at four health, then burned while
        // the stale lava latch still outranked the extinguish controller.
        if (active(SurvivalAction.ESCAPE_LAVA) && world.inLava()) {
            String reason = world.fireResistanceActive()
                    ? "leave lava before fire resistance expires"
                    : "escape lava and use the fastest available extinguish method";
            return directive(SurvivalAction.ESCAPE_LAVA, ControlPriority.SURVIVAL_LAVA, reason);
        }
        if (active(SurvivalAction.EXTINGUISH_FIRE)) {
            return directive(
                    SurvivalAction.EXTINGUISH_FIRE,
                    ControlPriority.SURVIVAL_FIRE,
                    "extinguish fire or reach a nonflammable safe area");
        }
        if (active(SurvivalAction.ESCAPE_LAVA)) {
            return directive(
                    SurvivalAction.ESCAPE_LAVA,
                    ControlPriority.SURVIVAL_LAVA,
                    "hold the verified dry lava exit until edge re-entry risk clears");
        }
        if (active(SurvivalAction.RECOVER_HEALTH)) {
            boolean healthRecovery = latches.get(SurvivalAction.RECOVER_HEALTH).healthRecovery;
            return directive(
                    SurvivalAction.RECOVER_HEALTH,
                    ControlPriority.SURVIVAL_RECOVERY,
                    healthRecovery
                            ? "health is critically low; eat or wait for natural regeneration before resuming"
                            : "eat available food before hunger prevents healing and sprinting");
        }
        return SafetyDirective.none();
    }

    public synchronized void reset() {
        for (Latch latch : latches.values()) {
            latch.active = false;
            latch.safeSinceMillis = -1;
            latch.healthRecovery = false;
        }
    }

    private void updateRecovery(WorldSnapshot world, long nowMillis) {
        Latch latch = latches.get(SurvivalAction.RECOVER_HEALTH);
        double healthFraction = world.health() / world.maximumHealth();
        boolean hungerAllowsRegeneration = world.foodLevel() >= NATURAL_REGEN_FOOD_LEVEL;
        // Passive regeneration does not need exclusive body ownership, and taking that
        // ownership while submerged can pin Entity at one breathing pocket forever:
        // RECOVER_HEALTH releases swim movement, Entity sinks, SURFACE_FOR_AIR raises it,
        // then RECOVER_HEALTH parks it again. Keep swimming/mission/protection ownership
        // until the body is out of water; merely reaching a breathable waterline still sinks
        // as soon as movement is released. Carried food remains an explicit, bounded reason
        // to stop and eat; critical air still independently activates SURFACE_FOR_AIR.
        boolean passiveRegenerationCanPause = hungerAllowsRegeneration
                && !world.inWater() && world.onGround();
        boolean lowHealthRecovery = healthFraction <= HEALTH_RECOVERY_ENTER_FRACTION
                && (world.consumableFoodAvailable() || passiveRegenerationCanPause);
        boolean ordinaryHungerRecovery = world.consumableFoodAvailable()
                && world.foodLevel() <= HUNGER_RECOVERY_ENTER_LEVEL;

        if (world.inWater() && !world.consumableFoodAvailable()) {
            // A passive-healing latch can be armed during the one airborne tick of a
            // surface-swim bob. It must not survive the observed return to water and
            // repeatedly cancel the aquatic movement owner.
            latch.active = false;
            latch.safeSinceMillis = -1;
            latch.healthRecovery = false;
            return;
        }

        if (lowHealthRecovery) {
            latch.healthRecovery = true;
        }
        if (lowHealthRecovery || ordinaryHungerRecovery) {
            latch.active = true;
            latch.safeSinceMillis = -1;
            return;
        }
        if (!latch.active) {
            latch.healthRecovery = false;
            return;
        }

        boolean safelyClear;
        if (latch.healthRecovery) {
            boolean healthRecovered = healthFraction >= HEALTH_RECOVERY_EXIT_FRACTION;
            boolean hungerFullyRestored = healthFraction > HEALTH_RECOVERY_ENTER_FRACTION
                    && world.foodLevel() >= FULL_HUNGER_LEVEL;
            boolean noRecoveryAvailable = !world.consumableFoodAvailable()
                    && !hungerAllowsRegeneration;
            safelyClear = healthRecovered || hungerFullyRestored || noRecoveryAvailable;
        } else {
            // Preserve the original hunger-only entry and exit thresholds.
            safelyClear = !world.consumableFoodAvailable()
                    || world.foodLevel() >= HUNGER_RECOVERY_EXIT_LEVEL;
        }
        clearWhenSafe(latch, safelyClear, nowMillis);
    }

    /**
     * Starts the air run from a verified route budget when the client can supply one.  The
     * configured threshold remains the fail-safe for an unknown/trapped route; a one-block air
     * pocket no longer causes a needless interruption at 240/300 air.
     */
    private boolean drowningDanger(WorldSnapshot world) {
        if (!world.headSubmerged()) return false;
        int routeTicks = world.estimatedAirTicksToBreathableSpace();
        if (routeTicks < 0) return world.remainingAir() <= drowningEnterAir;
        long budget = (long) routeTicks + DROWNING_ROUTE_RESERVE_TICKS;
        int routeAwareThreshold = (int) Math.max(
                MINIMUM_DROWNING_EMERGENCY_AIR,
                Math.min((long) drowningEnterAir, budget));
        return world.remainingAir() <= routeAwareThreshold;
    }

    private void update(SurvivalAction action, boolean danger, boolean safelyClear, long nowMillis) {
        Latch latch = latches.get(action);
        if (danger) {
            latch.active = true;
            latch.safeSinceMillis = -1;
            return;
        }
        if (!latch.active) {
            return;
        }
        clearWhenSafe(latch, safelyClear, nowMillis);
    }

    private void clearWhenSafe(Latch latch, boolean safelyClear, long nowMillis) {
        if (!safelyClear) {
            latch.safeSinceMillis = -1;
            return;
        }
        if (latch.safeSinceMillis < 0) {
            latch.safeSinceMillis = nowMillis;
        }
        if (nowMillis - latch.safeSinceMillis >= clearHoldMillis) {
            latch.active = false;
            latch.safeSinceMillis = -1;
            latch.healthRecovery = false;
        }
    }

    private boolean active(SurvivalAction action) {
        return latches.get(action).active;
    }

    private static SafetyDirective directive(SurvivalAction action, int priority, String reason) {
        return new SafetyDirective(
                action,
                priority,
                reason,
                EnumSet.allOf(BodyChannel.class));
    }

    private static final class Latch {
        private boolean active;
        private long safeSinceMillis = -1;
        private boolean healthRecovery;
    }
}
