package dev.entity.client.protection;

import dev.entity.client.baritone.CreeperTactics;
import java.util.Objects;

/**
 * Episode-local liveness policy for one distant ordinary attacker.
 *
 * <p>Unconfirmed target retention is not proof of continuing combat pressure; a trustworthy live
 * target binding is. Retained ordinary threats are therefore bounded separately: a target outside
 * body-blocking range that remains occluded, non-closing, and harmless for one fixed lease becomes
 * an avoidance obstacle while the interrupted mission resumes, even if a target binding stays
 * refreshed through solid terrain. Before that lease, measurable separation remains preferable.
 * Any near-term material approach or melee damage, projectile, second threat, or body block
 * reactivates raw protection immediately; distant ordinary pursuit and retained old melee damage
 * remain raw avoidance evidence while the mission resumes. The policy never deletes source
 * evidence.</p>
 *
 * <p>Low health and ordinary-melee visibility deliberately are not permanent vetoes. A visible
 * zombie or melee drowned tens of blocks away can otherwise retain protection forever while doing
 * no damage and never closing. Low health still contributes to the caller's modeled near-term
 * lethal-pressure fact, which is a fail-closed veto here.</p>
 */
public final class DistantThreatDisengagementPolicy {
    public static final long DEFAULT_QUIET_WINDOW_MILLIS = 2_000L;
    /** Sixty ordinary client ticks: stale target bindings cannot own the body beyond this lease. */
    public static final long DEFAULT_OCCLUDED_QUIET_LEASE_MILLIS = 3_000L;
    public static final double DEFAULT_REQUIRED_SEPARATION_GAIN = 2.0;
    public static final double DEFAULT_MINIMUM_CLEARANCE = 3.2;
    public static final double DEFAULT_CLOSING_TOLERANCE = 0.35;
    /** Outside this radius, a stationary melee hit estimate is not near-term pressure. */
    public static final double MELEE_NEAR_TERM_PRESSURE_RADIUS = 8.0;
    /** Target intent or bare line of sight beyond this radius is not an arrow in flight. */
    public static final double RANGED_NEAR_TERM_PRESSURE_RADIUS = 16.0;
    /** A fresh ordinary melee target beyond this radius is already separated. */
    public static final double MELEE_IMMEDIATE_AVOIDANCE_RADIUS = 12.0;

    private final long quietWindowMillis;
    private final long occludedQuietLeaseMillis;
    private final double requiredSeparationGain;
    private final double minimumClearance;
    private final double closingTolerance;

    private String episodeId = "";
    private String targetId = "";
    private TargetKind targetKind = TargetKind.OTHER;
    private State state = State.IDLE;
    private double separationAnchorDistance;
    private double separationHighWaterDistance;
    private double phaseHighWaterDistance;
    private long quietSinceMillis = -1L;
    private long occludedQuietSinceMillis = -1L;
    private double occludedDistanceHighWater;
    private boolean dangerLatched;

    public DistantThreatDisengagementPolicy() {
        this(DEFAULT_QUIET_WINDOW_MILLIS,
                DEFAULT_OCCLUDED_QUIET_LEASE_MILLIS,
                DEFAULT_REQUIRED_SEPARATION_GAIN,
                DEFAULT_MINIMUM_CLEARANCE,
                DEFAULT_CLOSING_TOLERANCE);
    }

    public DistantThreatDisengagementPolicy(
            long quietWindowMillis,
            double requiredSeparationGain,
            double minimumClearance,
            double closingTolerance) {
        this(quietWindowMillis, DEFAULT_OCCLUDED_QUIET_LEASE_MILLIS,
                requiredSeparationGain, minimumClearance, closingTolerance);
    }

    public DistantThreatDisengagementPolicy(
            long quietWindowMillis,
            long occludedQuietLeaseMillis,
            double requiredSeparationGain,
            double minimumClearance,
            double closingTolerance) {
        if (quietWindowMillis <= 0L
                || occludedQuietLeaseMillis <= 0L
                || !Double.isFinite(requiredSeparationGain)
                || requiredSeparationGain <= 0.0
                || !Double.isFinite(minimumClearance)
                || minimumClearance < 0.0
                || !Double.isFinite(closingTolerance)
                || closingTolerance <= 0.0) {
            throw new IllegalArgumentException("invalid distant-threat disengagement limits");
        }
        this.quietWindowMillis = quietWindowMillis;
        this.occludedQuietLeaseMillis = occludedQuietLeaseMillis;
        this.requiredSeparationGain = requiredSeparationGain;
        this.minimumClearance = minimumClearance;
        this.closingTolerance = closingTolerance;
    }

    public Decision observe(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (!observation.targetPresent()) {
            if (!sameBoundary(observation)) {
                clear();
                return new Decision(
                        State.IDLE, Action.PASS_THROUGH, 0L, 0.0,
                        "no retained exact threat is present");
            }
            return observeMissingTarget(observation);
        }
        if (observation.targetKind() == TargetKind.CREEPER) {
            return observeSeparatedCreeper(observation);
        }
        if (!ordinary(observation.targetKind())) {
            clear();
            return new Decision(
                    State.IDLE, Action.PROTECT, 0L, 0.0,
                    "players and unclassified threats never downgrade");
        }

        boolean newIdentity = !sameIdentity(observation);
        if (newIdentity) {
            begin(observation);
        }
        separationHighWaterDistance = Math.max(
                separationHighWaterDistance, observation.distance());

        if (newIdentity && immediatelyAvoidableDistantMelee(observation)) {
            // A zombie acquiring a sprinting mission from fifteen blocks away is not a reason to
            // turn the whole body around. Keep the exact UUID in the avoidance view and let raw
            // distance/approach evidence rearm protection if it actually reaches near-term range.
            state = State.AVOIDING;
            quietSinceMillis = observation.nowMillis();
            phaseHighWaterDistance = observation.distance();
            return decision(
                    observation,
                    Action.AVOID_AND_RESUME,
                    0L,
                    "fresh distant ordinary melee target is already outside near-term pressure");
        }

        OccludedQuiet occludedQuiet = observeOccludedQuiet(observation);
        if (occludedQuiet.approaching()) {
            dangerLatched = true;
            resetSeparation(observation.distance());
            return decision(
                    observation, Action.PROTECT, 0L,
                    "occluded ordinary threat materially approached");
        }
        if (occludedQuiet.expired()) {
            dangerLatched = false;
            state = State.AVOIDING;
            quietSinceMillis = occludedQuietSinceMillis;
            phaseHighWaterDistance = Math.max(
                    occludedDistanceHighWater, observation.distance());
            return decision(
                    observation, Action.AVOID_AND_RESUME, occludedQuiet.quietForMillis(),
                    "occluded ordinary target binding exceeded its fixed quiet lease");
        }

        String danger = failClosedDanger(observation);
        if (!danger.isEmpty()) {
            // Reset once on entry to a danger episode. A persistent Paper/LOS/damage fact must not
            // slide the anchor every tick, but separation created after that fact began is real.
            if (!dangerLatched) resetSeparation(observation.distance());
            dangerLatched = true;
            quietSinceMillis = -1L;
            return decision(observation, Action.PROTECT, 0L, danger);
        }
        dangerLatched = false;

        if (observation.survivalActive()) {
            return observeWhileSurvivalOwnsBody(observation);
        }

        return switch (state) {
            case IDLE -> throw new IllegalStateException("present ordinary target was not armed");
            case SEPARATING -> observeSeparation(observation);
            case VERIFYING_QUIET -> observeQuietWindow(observation);
            case AVOIDING -> observeAvoidance(observation);
        };
    }

    /**
     * A failed retreat is not a permanent creeper lease. An observed uncharged,
     * unprimed SELF creeper outside the actuator's existing safe 3D clearance
     * can remain a Baritone avoidance obstacle while the original mission runs.
     * Horizontal damage estimates/old target intent are not blast pressure eight
     * blocks down a cliff. Unknown fuse/charge, actual approach, nearby blast,
     * fresh damage or another eligible threat immediately restores raw protection.
     */
    private Decision observeSeparatedCreeper(Observation observation) {
        if (!observation.explosiveFactsKnown()) {
            clear();
            return new Decision(State.IDLE, Action.PROTECT, 0L, 0.0,
                    "creeper fuse/charge is unobserved; protection remains authoritative");
        }
        if (!sameIdentity(observation)) begin(observation);
        separationHighWaterDistance = Math.max(separationHighWaterDistance, observation.distance());
        boolean unsafe = !observation.explosiveFactsKnown()
                || observation.explosivePrimed() || observation.explosiveCharged()
                || observation.distance() <= CreeperTactics.NORMAL_SAFE_DISTANCE
                || observation.freshDamageEvidence() || observation.projectilePressure()
                || observation.otherEligibleThreat() || observation.bodyBlocker();
        boolean approaching = quietSinceMillis >= 0L && materiallyClosing(observation.distance());
        if (unsafe || approaching) {
            state = State.SEPARATING;
            quietSinceMillis = -1L;
            phaseHighWaterDistance = observation.distance();
            return decision(observation, Action.PROTECT, 0L,
                    unsafe ? "creeper clearance/fuse or fresh pressure requires protection"
                            : "separated creeper materially approached; protection rearmed");
        }
        if (quietSinceMillis < 0L || observation.nowMillis() < quietSinceMillis) {
            quietSinceMillis = observation.nowMillis();
            phaseHighWaterDistance = observation.distance();
        }
        phaseHighWaterDistance = Math.max(phaseHighWaterDistance, observation.distance());
        long quietFor = observation.nowMillis() - quietSinceMillis;
        if (quietFor < quietWindowMillis) {
            state = State.VERIFYING_QUIET;
            return decision(observation, Action.PROTECT, quietFor,
                    "safe unprimed creeper clearance is inside the fixed quiet window");
        }
        state = State.AVOIDING;
        return decision(observation,
                observation.survivalActive() ? Action.DEFER_TO_SURVIVAL : Action.AVOID_AND_RESUME,
                quietFor, "safe unprimed creeper remains a raw avoidance obstacle; original work may resume");
    }

    /**
     * Survival movement and combat liveness are independent facts. Surfacing or swimming keeps
     * ownership of the body, but it must not freeze a quiet, separated threat lease forever. Raw
     * damage, approach, projectile, crowd, and body-block evidence already fail closed above. In
     * their absence, continue the same fixed separation/quiet clock and omit the exact UUID once
     * it earns avoidance; until then, leave the raw threat in core's view while survival moves.
     */
    private Decision observeWhileSurvivalOwnsBody(Observation observation) {
        Decision progress = switch (state) {
            case IDLE -> throw new IllegalStateException("present ordinary target was not armed");
            case SEPARATING -> observeSeparation(observation);
            case VERIFYING_QUIET -> observeQuietWindow(observation);
            case AVOIDING -> observeAvoidance(observation);
        };
        if (progress.action() == Action.AVOID_AND_RESUME) return progress;
        return new Decision(
                progress.state(),
                Action.DEFER_TO_SURVIVAL,
                progress.quietForMillis(),
                progress.separationGain(),
                "higher-priority survival control is active; " + progress.detail());
    }

    public void clear() {
        episodeId = "";
        targetId = "";
        targetKind = TargetKind.OTHER;
        state = State.IDLE;
        separationAnchorDistance = 0.0;
        separationHighWaterDistance = 0.0;
        phaseHighWaterDistance = 0.0;
        quietSinceMillis = -1L;
        occludedQuietSinceMillis = -1L;
        occludedDistanceHighWater = 0.0;
        dangerLatched = false;
    }

    public State state() {
        return state;
    }

    public String episodeId() {
        return episodeId;
    }

    public String targetId() {
        return targetId;
    }

    public TargetKind targetKind() {
        return targetKind;
    }

    /**
     * True while the exact target is earning the policy's fixed occluded release lease.
     *
     * <p>This is deliberately narrower than "protection is active": fresh damage, projectile
     * pressure, another eligible threat, lethal modeled pressure, body blocking, or survival
     * ownership all reset the occluded clock before this method is observed. Runtime uses this
     * signal only to keep its shorter generic retreat-stall watchdog from pre-empting the policy's
     * already-bounded three-second liveness decision.</p>
     */
    public boolean awaitingOccludedRelease(long nowMillis) {
        if (nowMillis < 0L) throw new IllegalArgumentException("nowMillis must be non-negative");
        return state != State.IDLE
                && occludedQuietSinceMillis >= 0L
                && nowMillis >= occludedQuietSinceMillis
                && nowMillis - occludedQuietSinceMillis < occludedQuietLeaseMillis;
    }

    private Decision observeSeparation(Observation observation) {
        double requiredDistance = Math.max(
                minimumClearance, separationAnchorDistance + requiredSeparationGain);
        if (observation.distance() < requiredDistance) {
            return decision(
                    observation, Action.CREATE_SEPARATION, 0L,
                    "ordinary threat has not reached the separation high-water");
        }
        state = State.VERIFYING_QUIET;
        quietSinceMillis = observation.nowMillis();
        phaseHighWaterDistance = observation.distance();
        return decision(
                observation, Action.MAINTAIN_SEPARATION, 0L,
                "separation created; fixed quiet window started");
    }

    private Decision observeQuietWindow(Observation observation) {
        phaseHighWaterDistance = Math.max(phaseHighWaterDistance, observation.distance());
        if (materiallyClosingNearTerm(observation)) {
            resetSeparation(observation.distance());
            return decision(
                    observation, Action.CREATE_SEPARATION, 0L,
                    "ordinary threat materially closed during quiet verification");
        }
        if (quietSinceMillis < 0L) {
            quietSinceMillis = observation.nowMillis();
            phaseHighWaterDistance = observation.distance();
        }
        long quietFor = Math.max(0L, observation.nowMillis() - quietSinceMillis);
        if (quietFor < quietWindowMillis) {
            return decision(
                    observation, Action.MAINTAIN_SEPARATION, quietFor,
                    "separated ordinary threat is inside the fixed quiet window");
        }
        state = State.AVOIDING;
        phaseHighWaterDistance = Math.max(phaseHighWaterDistance, observation.distance());
        return decision(
                observation, Action.AVOID_AND_RESUME, quietFor,
                "separated ordinary threat stayed quiet and non-closing");
    }

    private Decision observeAvoidance(Observation observation) {
        phaseHighWaterDistance = Math.max(phaseHighWaterDistance, observation.distance());
        if (materiallyClosingNearTerm(observation)) {
            resetSeparation(observation.distance());
            return decision(
                    observation, Action.CREATE_SEPARATION, 0L,
                    "avoided ordinary threat started closing again");
        }
        return decision(
                observation, Action.AVOID_AND_RESUME,
                Math.max(quietWindowMillis,
                        Math.max(0L, observation.nowMillis() - quietSinceMillis)),
                "ordinary threat remains separated and non-closing");
    }

    /**
     * A one-frame unload is not evidence that a previously observed attacker died, and it must
     * not erase the quiet/separation history that decides how the same UUID is treated when it
     * becomes visible again. While the entity is unavailable, core naturally receives no threat
     * to execute against; this method retains only the bounded policy history. Three continuous
     * seconds of occlusion/unavailability earn avoidance exactly like a loaded occluded target.
     */
    private Decision observeMissingTarget(Observation observation) {
        long now = observation.nowMillis();
        if (occludedQuietSinceMillis < 0L || now < occludedQuietSinceMillis) {
            occludedQuietSinceMillis = now;
            occludedDistanceHighWater = Math.max(
                    phaseHighWaterDistance, separationHighWaterDistance);
        }
        long quietFor = Math.max(0L, now - occludedQuietSinceMillis);
        if (state == State.AVOIDING || quietFor >= occludedQuietLeaseMillis) {
            state = State.AVOIDING;
            dangerLatched = false;
            quietSinceMillis = occludedQuietSinceMillis;
            return decision(
                    observation,
                    Action.AVOID_AND_RESUME,
                    quietFor,
                    "exact ordinary threat remained occluded or unavailable for its fixed quiet lease");
        }
        return decision(
                observation,
                Action.PASS_THROUGH,
                quietFor,
                "exact ordinary threat is temporarily unavailable; retaining bounded quiet history");
    }

    private boolean materiallyClosing(double distance) {
        return phaseHighWaterDistance - distance >= closingTolerance;
    }

    private boolean materiallyClosingNearTerm(Observation observation) {
        return materiallyClosing(observation.distance()) && closingIsNearTerm(observation);
    }

    private boolean immediatelyAvoidableDistantMelee(Observation observation) {
        return observation.targetKind() == TargetKind.ORDINARY_MELEE
                && observation.distance() > MELEE_IMMEDIATE_AVOIDANCE_RADIUS
                && (observation.paperAuthoritativeAttacker()
                || observation.trustedLocalTargetingEvidence())
                && !observation.freshDamageEvidence()
                && !observation.projectilePressure()
                && !observation.otherEligibleThreat()
                && !observation.bodyBlocker()
                && !observation.survivalActive()
                && !modeledLethalPressureIsNearTerm(observation);
    }

    /**
     * A harmless ordinary mob following at tens of blocks is not immediate combat ownership. Raw
     * observation remains active and will rearm protection when it reaches melee pressure range;
     * modeled-lethal closing remains conservative at any distance.
     */
    private boolean closingIsNearTerm(Observation observation) {
        double nearTermRadius = observation.targetKind() == TargetKind.ORDINARY_RANGED
                ? RANGED_NEAR_TERM_PRESSURE_RADIUS
                : MELEE_NEAR_TERM_PRESSURE_RADIUS;
        return observation.distance() <= nearTermRadius
                || modeledLethalPressureIsNearTerm(observation);
    }

    private OccludedQuiet observeOccludedQuiet(Observation observation) {
        boolean eligible = !observation.lineOfSight()
                && !observation.freshDamageEvidence()
                && !observation.projectilePressure()
                && !observation.otherEligibleThreat()
                && !modeledLethalPressureIsNearTerm(observation)
                && !observation.bodyBlocker()
                && !observation.survivalActive()
                && observation.distance() > minimumClearance;
        if (!eligible) {
            resetOccludedQuiet();
            return OccludedQuiet.INACTIVE;
        }

        if (occludedQuietSinceMillis < 0L
                || observation.nowMillis() < occludedQuietSinceMillis) {
            occludedQuietSinceMillis = observation.nowMillis();
            occludedDistanceHighWater = observation.distance();
            return new OccludedQuiet(false, false, 0L);
        }

        if (occludedDistanceHighWater - observation.distance() >= closingTolerance
                && closingIsNearTerm(observation)) {
            occludedQuietSinceMillis = observation.nowMillis();
            occludedDistanceHighWater = observation.distance();
            return new OccludedQuiet(true, false, 0L);
        }

        occludedDistanceHighWater = Math.max(
                occludedDistanceHighWater, observation.distance());
        long quietFor = Math.max(
                0L, observation.nowMillis() - occludedQuietSinceMillis);
        return new OccludedQuiet(
                false, quietFor >= occludedQuietLeaseMillis, quietFor);
    }

    private void resetOccludedQuiet() {
        occludedQuietSinceMillis = -1L;
        occludedDistanceHighWater = 0.0;
    }

    private void begin(Observation observation) {
        episodeId = observation.episodeId();
        targetId = observation.targetId();
        targetKind = observation.targetKind();
        dangerLatched = false;
        resetOccludedQuiet();
        resetSeparation(observation.distance());
    }

    private void resetSeparation(double distance) {
        state = State.SEPARATING;
        separationAnchorDistance = distance;
        separationHighWaterDistance = distance;
        phaseHighWaterDistance = distance;
        quietSinceMillis = -1L;
    }

    private boolean sameIdentity(Observation observation) {
        return state != State.IDLE
                && episodeId.equals(observation.episodeId())
                && targetId.equals(observation.targetId())
                && targetKind == observation.targetKind();
    }

    private boolean sameBoundary(Observation observation) {
        return state != State.IDLE
                && episodeId.equals(observation.episodeId())
                && targetId.equals(observation.targetId());
    }

    private Decision decision(
            Observation observation,
            Action action,
            long quietForMillis,
            String detail) {
        return new Decision(
                state,
                action,
                quietForMillis,
                Math.max(0.0, separationHighWaterDistance - separationAnchorDistance),
                detail);
    }

    private String failClosedDanger(Observation observation) {
        boolean distantOrdinaryMelee = observation.targetKind() == TargetKind.ORDINARY_MELEE
                && observation.distance() > MELEE_NEAR_TERM_PRESSURE_RADIUS;
        boolean distantOrdinaryRanged = observation.targetKind() == TargetKind.ORDINARY_RANGED
                && observation.distance() > RANGED_NEAR_TERM_PRESSURE_RADIUS;
        boolean distantTargetIntent = distantOrdinaryMelee || distantOrdinaryRanged;
        if (observation.paperAuthoritativeAttacker() && !distantTargetIntent) {
            return "Paper-authoritative attacker evidence remains active";
        }
        if (observation.trustedLocalTargetingEvidence() && !distantTargetIntent) {
            return "trusted local target binding remains active";
        }
        // The authoritative ledger intentionally retains a hit for ten seconds. Once an ordinary
        // melee attacker is beyond near-term range, that historical hit cannot keep the body for
        // the rest of the lease; renewed closing/body range re-arms protection below. Ranged hits
        // remain fail-closed because another projectile can cross the same distance immediately.
        if (observation.freshDamageEvidence() && !distantOrdinaryMelee) {
            return "fresh protected-target damage remains active";
        }
        if (observation.projectilePressure()) {
            return "projectile or visible ranged pressure remains active";
        }
        if (observation.targetKind() == TargetKind.ORDINARY_RANGED
                && observation.lineOfSight()
                && observation.distance() <= RANGED_NEAR_TERM_PRESSURE_RADIUS) {
            return "ranged attacker retains line of sight";
        }
        if (observation.otherEligibleThreat()) {
            return "another eligible threat prevents sole-target downgrade";
        }
        if (modeledLethalPressureIsNearTerm(observation)) {
            return "modeled near-term pressure is lethal";
        }
        if (observation.bodyBlocker() || observation.distance() <= minimumClearance) {
            return "ordinary threat is inside body-blocking clearance";
        }
        // Low health, unconfirmed target retention, and melee LOS are intentionally absent. Those
        // facts alone caused the retained 3.5/20 HP distant drowned episode to protect forever.
        return "";
    }

    private boolean modeledLethalPressureIsNearTerm(Observation observation) {
        if (!observation.modeledLethalPressure()) return false;
        double nearTermRadius = observation.targetKind() == TargetKind.ORDINARY_RANGED
                ? RANGED_NEAR_TERM_PRESSURE_RADIUS
                : MELEE_NEAR_TERM_PRESSURE_RADIUS;
        if (observation.distance() <= nearTermRadius) return true;
        // A distant skeleton retaining its AI target and bare line of sight is
        // not itself near-term damage. Actual damage or an arrow in flight was
        // already rejected fail-closed above. Let an avoided ranged target
        // remain an obstacle until it enters real engagement range.
        if (observation.targetKind() == TargetKind.ORDINARY_RANGED) return false;
        // The legacy aggregate ignores distance. It becomes near-term at long range only when
        // stateful evidence says a melee target is materially closing. A stationary 15-33m
        // melee estimate cannot latch low-HP defense.
        return (state == State.VERIFYING_QUIET || state == State.AVOIDING)
                && materiallyClosing(observation.distance());
    }

    private static boolean ordinary(TargetKind kind) {
        return kind == TargetKind.ORDINARY_MELEE || kind == TargetKind.ORDINARY_RANGED;
    }

    private record OccludedQuiet(
            boolean approaching,
            boolean expired,
            long quietForMillis) {
        private static final OccludedQuiet INACTIVE =
                new OccludedQuiet(false, false, 0L);
    }

    public enum State {
        IDLE,
        SEPARATING,
        VERIFYING_QUIET,
        AVOIDING
    }

    public enum Action {
        PASS_THROUGH,
        PROTECT,
        CREATE_SEPARATION,
        MAINTAIN_SEPARATION,
        AVOID_AND_RESUME,
        DEFER_TO_SURVIVAL
    }

    public enum TargetKind {
        ORDINARY_MELEE,
        ORDINARY_RANGED,
        PLAYER,
        CREEPER,
        OTHER
    }

    public record Decision(
            State state,
            Action action,
            long quietForMillis,
            double separationGain,
            String detail) {
        public Decision {
            state = Objects.requireNonNull(state, "state");
            action = Objects.requireNonNull(action, "action");
            detail = Objects.requireNonNullElse(detail, "");
        }

        /**
         * True while this retained ordinary-threat episode must keep moving defensively away.
         * A near-term damage/targeting fact still owns PROTECTION, but it is not permission to
         * start an attack route that destroys separation while that bounded fact ages out. Close
         * threats, players, creepers, and ranged damage remain normal combat decisions.
         */
        public boolean requiresDefensiveSeparation(
                double targetDistance,
                TargetKind targetKind) {
            if (!Double.isFinite(targetDistance) || targetDistance < 0.0) {
                throw new IllegalArgumentException("targetDistance must be finite and non-negative");
            }
            Objects.requireNonNull(targetKind, "targetKind");
            return action == Action.CREATE_SEPARATION
                    || action == Action.MAINTAIN_SEPARATION
                    || action == Action.PROTECT
                    && state != State.IDLE
                    && targetKind == TargetKind.ORDINARY_MELEE
                    && targetDistance > DEFAULT_MINIMUM_CLEARANCE;
        }
    }

    public record Observation(
            long nowMillis,
            String episodeId,
            String targetId,
            TargetKind targetKind,
            boolean targetPresent,
            double distance,
            boolean trustedLocalTargetingEvidence,
            boolean paperAuthoritativeAttacker,
            boolean freshDamageEvidence,
            boolean projectilePressure,
            boolean lineOfSight,
            boolean otherEligibleThreat,
            boolean lowHealth,
            boolean modeledLethalPressure,
            boolean bodyBlocker,
            boolean survivalActive,
            boolean explosiveFactsKnown,
            boolean explosivePrimed,
            boolean explosiveCharged) {
        /** Older observations never prove that a creeper is safe. */
        public Observation(long nowMillis, String episodeId, String targetId, TargetKind targetKind,
                boolean targetPresent, double distance, boolean trustedLocalTargetingEvidence,
                boolean paperAuthoritativeAttacker, boolean freshDamageEvidence,
                boolean projectilePressure, boolean lineOfSight, boolean otherEligibleThreat,
                boolean lowHealth, boolean modeledLethalPressure, boolean bodyBlocker, boolean survivalActive) {
            this(nowMillis, episodeId, targetId, targetKind, targetPresent, distance,
                    trustedLocalTargetingEvidence, paperAuthoritativeAttacker, freshDamageEvidence,
                    projectilePressure, lineOfSight, otherEligibleThreat, lowHealth,
                    modeledLethalPressure, bodyBlocker, survivalActive, false, false, false);
        }
        public Observation {
            if (nowMillis < 0L) throw new IllegalArgumentException("nowMillis must be non-negative");
            episodeId = required(episodeId, "episodeId");
            targetId = required(targetId, "targetId");
            targetKind = Objects.requireNonNull(targetKind, "targetKind");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("distance must be finite and non-negative");
            }
        }
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
