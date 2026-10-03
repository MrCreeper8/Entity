package dev.entity.client.baritone;

import java.util.Objects;

/**
 * Pure, bounded close-combat policy.
 *
 * <p>Baritone keeps ownership while a target still needs a real route. This policy takes the body
 * only inside a loaded, visible engagement envelope where Minecraft-legal movement, attack
 * cooldowns, and shield use matter more than path search. Unsafe footing and low health never
 * produce a blind charge.</p>
 */
public final class CombatEngagementPolicy {
    public static final double STRIKE_DISTANCE = 3.2;
    public static final double MANUAL_MELEE_ENVELOPE = 3.75;
    public static final double SHIELDED_ADVANCE_ENVELOPE = 6.0;
    public static final double LOW_HEALTH_FRACTION = 0.30;
    private static final double CLOSE_SPACING_DISTANCE = 2.45;

    private CombatEngagementPolicy() {
    }

    /**
     * Authorizes the exceptional low-health close only when the operation requested it and a
     * shield is already usable in the offhand. A merely carried/hotbar shield is not sufficient:
     * ranged route shielding cannot equip it and steer the route atomically.
     */
    public static boolean authorizeBoundedLowHealthResolution(
            boolean operationRequested,
            boolean usableOffhandShield) {
        return operationRequested && usableOffhandShield;
    }

    /**
     * Narrows the aggregate-ranged advisory to the one ordinary action where it is useful and
     * bounded: an in-reach cooldown YIELD. A ready strike always wins in {@link #decide(Input)}
     * before YIELD is produced, and all non-ranged ordinary combat remains unchanged.
     */
    public static boolean shouldShieldCooldownYield(
            Action action,
            double exactTargetDistance,
            boolean cooldownReady,
            boolean visibleRangedPressure,
            boolean usableOffhandShield) {
        Objects.requireNonNull(action, "action");
        if (!Double.isFinite(exactTargetDistance) || exactTargetDistance < 0.0) {
            throw new IllegalArgumentException(
                    "exactTargetDistance must be finite and non-negative");
        }
        return action == Action.YIELD
                && exactTargetDistance <= STRIKE_DISTANCE
                && !cooldownReady
                && visibleRangedPressure
                && usableOffhandShield;
    }

    /**
     * The shield-ready no-safe-cell result for an exact melee target is explicitly classified so
     * the executor may face a different visible shooter without interpreting status text. Other
     * BLOCK actions (ranged target, creeper, safety) are deliberately excluded.
     */
    public static boolean shouldShieldMeleeCooldownBlock(
            Decision decision,
            double exactTargetDistance,
            boolean cooldownReady,
            boolean visibleRangedPressure,
            boolean usableOffhandShield) {
        Objects.requireNonNull(decision, "decision");
        if (!Double.isFinite(exactTargetDistance) || exactTargetDistance < 0.0) {
            throw new IllegalArgumentException(
                    "exactTargetDistance must be finite and non-negative");
        }
        return decision.action() == Action.BLOCK
                && decision.context() == DecisionContext.MELEE_COOLDOWN_HOLD
                && exactTargetDistance <= STRIKE_DISTANCE
                && !cooldownReady
                && visibleRangedPressure
                && usableOffhandShield;
    }

    /** Keeps one fixed shield-start attempt across adjacent cooldown hold frames only. */
    public static boolean retainsRangedPressureShieldAttempt(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        return decision.action() == Action.YIELD
                || (decision.action() == Action.BLOCK
                && decision.context() == DecisionContext.MELEE_COOLDOWN_HOLD);
    }

    public static Decision decide(Input input) {
        return decide(input, true);
    }

    /** Ordinary ranged approach releases use between observed shots; sealed resolution does not. */
    public static Decision decide(Input input, boolean projectileThreat) {
        Objects.requireNonNull(input, "input");

        if (input.targetKind() == TargetKind.CREEPER) {
            CreeperTactics.Decision creeper = CreeperTactics.decide(
                    input.distance(),
                    input.explosiveActive(),
                    input.explosiveProgress(),
                    input.explosiveCharged(),
                    input.cooldownReady(),
                    input.retreating(),
                    input.shieldReady());
            return switch (creeper.action()) {
                case APPROACH -> new Decision(Action.ROUTE, creeper.detail());
                case STRIKE_AND_RETREAT ->
                        new Decision(Action.STRIKE_AND_RETREAT, creeper.detail());
                case RETREAT -> new Decision(Action.RETREAT, creeper.detail());
                case SHIELD -> new Decision(Action.BLOCK, creeper.detail());
            };
        }

        if (input.targetKind() == TargetKind.PASSIVE) {
            if (input.lineOfSight() && input.distance() <= STRIKE_DISTANCE
                    && input.cooldownReady()) {
                return new Decision(Action.STRIKE, "cooldown-ready passive target strike");
            }
            return new Decision(Action.ROUTE, "retain pursuit route to the moving passive target");
        }

        boolean lowHealth = input.healthFraction() <= LOW_HEALTH_FRACTION;
        boolean inReach = input.distance() <= STRIKE_DISTANCE;
        boolean manualBodySafe = input.bodyStable() && input.lineOfSight();
        boolean boundedLowHealthResolution = input.boundedLowHealthResolution()
                && input.shieldReady()
                && (input.targetKind() == TargetKind.MELEE
                || input.targetKind() == TargetKind.RANGED);

        // A target already touching the player still needs a bounded defensive response. Low
        // health suppresses approach, but does not forbid a ready hit that can create survival
        // space immediately.
        if (inReach && input.cooldownReady() && input.lineOfSight()) {
            return new Decision(Action.STRIKE,
                    lowHealth ? "low-health defensive strike to create space"
                            : "cooldown-ready strike");
        }

        // A transient Baritone jump outside attack reach must preserve its exact route regardless
        // of the health branch below.  YIELD is stationary close-body custody in the executor;
        // applying it for a single airborne frame cancels the jump route and cannot itself create
        // safety.  Once Entity is back on stable footing, the ordinary health/spacing policy can
        // make the next bounded decision.
        if (!input.bodyStable() && !inReach) {
            return new Decision(Action.ROUTE,
                    "transient unstable footing out of reach: retain the exact combat route");
        }

        if (lowHealth && !inReach && !boundedLowHealthResolution) {
            if (input.targetKind() == TargetKind.RANGED && input.shieldReady()
                    && input.lineOfSight()) {
                return new Decision(Action.BLOCK,
                        "low health: hold shield without advancing into ranged fire");
            }
            return new Decision(Action.YIELD,
                    "low health: yield close combat instead of charging");
        }

        // Losing sight is precisely when the obstacle-aware route owner is needed. YIELD takes
        // close-body custody and cancels FollowProcess, so using it here strands Entity on the
        // near side of a wall while the still-targeting hostile keeps the mission paused.
        if (!input.lineOfSight()) {
            return new Decision(Action.ROUTE,
                    "target is occluded: retain the exact hostile route until line of sight returns");
        }

        if (!manualBodySafe) {
            // Baritone can legitimately put the player airborne for one or more ticks while it
            // jumps, descends a step, or crosses uneven cave terrain.  Taking close-body custody
            // during that transient phase cancels the exact FollowProcess which was making the
            // jump and turns a viable route into a standstill.  Out of reach there is nothing
            // useful for the manual cooldown controller to do, so preserve route ownership.  An
            // attacker already in reach still uses the bounded no-safe-cell hold below rather
            // than letting pathing turn the cooldown window into a blind charge.
            return new Decision(Action.YIELD,
                    "unstable footing in reach: hold through the cooldown window");
        }

        if (input.targetKind() == TargetKind.RANGED) {
            if (input.distance() > SHIELDED_ADVANCE_ENVELOPE) {
                return new Decision(Action.ROUTE,
                        "ranged target is outside the loaded close-combat envelope");
            }
            if (!input.shieldReady()) {
                if (inReach) return spacingOrYield(input,
                        "cooldown recovery against ranged target without a shield");
                // A missing/disabled shield is not permission to freeze forever inside the
                // six-block engagement envelope.  Relinquish the direct body and let the
                // obstacle-aware route close the remaining gap; once in reach the cooldown
                // strike/spacing policy above takes over.  Low health was already rejected
                // earlier, so this is the bounded unshielded fallback rather than a blind
                // survival charge.
                return new Decision(Action.ROUTE,
                        "no ready shield: retain the bounded obstacle-aware combat route");
            }
            if (!inReach) {
                return input.safeForward()
                        ? new Decision(projectileThreat || boundedLowHealthResolution
                                ? Action.SHIELD_ADVANCE : Action.ADVANCE,
                        projectileThreat || boundedLowHealthResolution
                                ? "advance behind the shield through imminent ranged fire"
                                : "close the safe gap between observed ranged shots")
                        : new Decision(Action.ROUTE,
                        "direct forward cell is unsafe: retain the shielded obstacle-aware route");
            }
            if (input.distance() < CLOSE_SPACING_DISTANCE && input.safeBackward()) {
                return new Decision(Action.BACKSTEP,
                        "block and backstep while the attack cooldown recovers");
            }
            return new Decision(Action.BLOCK,
                    "hold shield through the ranged cooldown window");
        }

        if (input.distance() > MANUAL_MELEE_ENVELOPE) {
            return new Decision(Action.ROUTE,
                    "target is outside the close-melee envelope");
        }
        if (!inReach) {
            // The remaining gap is small, but Baritone still knows whether a step requires a jump,
            // door, or block interaction. Do not replace that knowledge with blind forward input.
            return new Decision(Action.ROUTE,
                    "retain Baritone for the final obstacle-aware approach");
        }
        return spacingOrYield(input,
                input.targetKind() == TargetKind.PLAYER
                        ? "PvP cooldown spacing"
                        : "melee cooldown spacing");
    }

    private static Decision spacingOrYield(Input input, String prefix) {
        if (input.distance() < CLOSE_SPACING_DISTANCE && input.safeBackward()) {
            return new Decision(Action.BACKSTEP, prefix + ": create distance");
        }
        if (input.preferLeft() && input.safeLeft()) {
            return new Decision(Action.STRAFE_LEFT, prefix + ": strafe left");
        }
        if (input.safeRight()) {
            return new Decision(Action.STRAFE_RIGHT, prefix + ": strafe right");
        }
        if (input.safeLeft()) {
            return new Decision(Action.STRAFE_LEFT, prefix + ": strafe left");
        }
        if (input.safeBackward()) {
            return new Decision(Action.BACKSTEP, prefix + ": backstep");
        }
        if (input.shieldReady()) {
            return new Decision(Action.BLOCK,
                    prefix + ": no safe movement cell, hold shield in place",
                    input.targetKind() == TargetKind.MELEE
                            ? DecisionContext.MELEE_COOLDOWN_HOLD
                            : DecisionContext.STANDARD);
        }
        return new Decision(Action.YIELD,
                prefix + ": no safe movement cell, yield instead of charging");
    }

    public enum TargetKind {
        PASSIVE,
        MELEE,
        RANGED,
        PLAYER,
        CREEPER
    }

    public enum Action {
        ROUTE,
        ADVANCE,
        SHIELD_ADVANCE,
        STRIKE,
        STRIKE_AND_RETREAT,
        BLOCK,
        BACKSTEP,
        STRAFE_LEFT,
        STRAFE_RIGHT,
        RETREAT,
        YIELD
    }

    public enum DecisionContext {
        STANDARD,
        /** Exact ordinary melee target is in reach while its attack cooldown is not ready. */
        MELEE_COOLDOWN_HOLD
    }

    public record Input(
            TargetKind targetKind,
            double distance,
            double healthFraction,
            boolean cooldownReady,
            boolean shieldReady,
            boolean bodyStable,
            boolean lineOfSight,
            boolean safeForward,
            boolean safeBackward,
            boolean safeLeft,
            boolean safeRight,
            boolean preferLeft,
            boolean explosiveActive,
            float explosiveProgress,
            boolean explosiveCharged,
            boolean retreating,
            boolean boundedLowHealthResolution) {
        /**
         * Compatibility constructor for ordinary combat. Only the protection runtime's sealed
         * resolution operation may set the final capability bit.
         */
        public Input(
                TargetKind targetKind,
                double distance,
                double healthFraction,
                boolean cooldownReady,
                boolean shieldReady,
                boolean bodyStable,
                boolean lineOfSight,
                boolean safeForward,
                boolean safeBackward,
                boolean safeLeft,
                boolean safeRight,
                boolean preferLeft,
                boolean explosiveActive,
                float explosiveProgress,
                boolean explosiveCharged,
                boolean retreating) {
            this(targetKind, distance, healthFraction, cooldownReady, shieldReady,
                    bodyStable, lineOfSight, safeForward, safeBackward, safeLeft, safeRight,
                    preferLeft, explosiveActive, explosiveProgress, explosiveCharged,
                    retreating, false);
        }

        public Input {
            Objects.requireNonNull(targetKind, "targetKind");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("distance must be finite and non-negative");
            }
            if (!Double.isFinite(healthFraction)
                    || healthFraction < 0.0 || healthFraction > 1.0) {
                throw new IllegalArgumentException("healthFraction must be in [0,1]");
            }
            if (!Float.isFinite(explosiveProgress) || explosiveProgress < 0.0F) {
                throw new IllegalArgumentException(
                        "explosiveProgress must be finite and non-negative");
            }
        }
    }

    public record Decision(Action action, String detail, DecisionContext context) {
        public Decision(Action action, String detail) {
            this(action, detail, DecisionContext.STANDARD);
        }

        public Decision {
            Objects.requireNonNull(action, "action");
            detail = detail == null ? "" : detail;
            context = Objects.requireNonNull(context, "context");
        }
    }
}
