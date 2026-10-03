package dev.entity.client.protection;

import dev.entity.client.autonomy.policy.TacticalCombatPolicy;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure arbitration bridge between the core protection lease and the tactical
 * combat policy.  The core still decides whether protection owns the body;
 * this class only chooses which already-existing live actuator should execute
 * that lease.
 */
public final class TacticalProtectionPlan {
    private TacticalProtectionPlan() {
    }

    public static Plan merge(
            String coreAction,
            TacticalCombatPolicy.Decision tactical) {
        Objects.requireNonNull(tactical, "tactical");
        String normalizedCore = Objects.requireNonNullElse(coreAction, "")
                .trim().toUpperCase(Locale.ROOT);
        if (!normalizedCore.equals("INTERCEPT") && !normalizedCore.equals("RETREAT")) {
            throw new IllegalArgumentException("unsupported core protection action: " + coreAction);
        }
        EngagementIntent intent = switch (tactical.action()) {
            case INTERCEPT, FIGHT, SHIELDED_INTERCEPT ->
                    EngagementIntent.NEUTRALIZE_BLOCKER;
            case RAISE_SHIELD, SHIELD_AND_COVER, SEEK_COVER, HOLD_RETREAT ->
                    EngagementIntent.TACTICAL_SPACING;
            case BOUNDED_RETREAT -> EngagementIntent.BOUNDED_RETREAT;
            case EVADE, HARD_RETREAT, RETREAT -> EngagementIntent.SAFETY_RETREAT;
            // A target-bearing quiet decision comes from CombatTargetSession:
            // the authenticated core target is intentionally retained, while
            // the complete loaded tactical view says it needs no body action.
            // A targetless CONTINUE may instead reflect missing tactical facts,
            // so it must not downgrade a coarse core safety retreat.
            case CONTINUE, RESUME -> tactical.targetId().isPresent()
                    ? EngagementIntent.OBSERVE_ONLY
                    : normalizedCore.equals("RETREAT")
                    ? EngagementIntent.SAFETY_RETREAT
                    : EngagementIntent.NEUTRALIZE_BLOCKER;
            case EAT -> normalizedCore.equals("RETREAT")
                    ? EngagementIntent.SAFETY_RETREAT
                    : EngagementIntent.NEUTRALIZE_BLOCKER;
        };
        Execution execution = switch (intent) {
            case NEUTRALIZE_BLOCKER -> Execution.INTERCEPT;
            case OBSERVE_ONLY -> Execution.OBSERVE;
            default -> Execution.DIRECT_RETREAT;
        };
        return new Plan(
                execution,
                intent,
                tactical.approachAllowed(),
                tactical.loadout(),
                tactical.targetId(),
                tactical.escape(),
                tactical.action(),
                tactical.detail());
    }

    /**
     * Biases a safe hostile-escape vector toward the interrupted mission without
     * running directly back through the pressure that caused protection. An
     * aligned objective becomes the corridor. An orthogonal or opposed objective
     * supplies the tangent around the pressure, so a retreat circles toward useful
     * ground instead of running indefinitely away from its retained mission.
     */
    public static TacticalCombatPolicy.EscapeVector biasEscapeTowardObjective(
            TacticalCombatPolicy.EscapeVector escape,
            double objectiveX,
            double objectiveZ) {
        Objects.requireNonNull(escape, "escape");
        double objectiveLength = Math.hypot(objectiveX, objectiveZ);
        if (escape.equals(TacticalCombatPolicy.EscapeVector.NONE)
                || !Double.isFinite(objectiveLength)
                || objectiveLength < 0.001) {
            return escape;
        }
        double normalizedX = objectiveX / objectiveLength;
        double normalizedZ = objectiveZ / objectiveLength;
        double alignment = escape.x() * normalizedX + escape.z() * normalizedZ;
        double x;
        double z;
        if (alignment >= 0.25) {
            final double objectiveWeight = 0.70;
            x = escape.x() * (1.0 - objectiveWeight)
                    + normalizedX * objectiveWeight;
            z = escape.z() * (1.0 - objectiveWeight)
                    + normalizedZ * objectiveWeight;
        } else {
            // Remove the unsafe component that points through the pressure and
            // retain the mission's lateral component. Exact head-on opposition
            // has no geometric preference, so choose one deterministic side;
            // the rolling corridor below can complete the arc on later segments.
            double tangentX = normalizedX - escape.x() * alignment;
            double tangentZ = normalizedZ - escape.z() * alignment;
            double tangentLength = Math.hypot(tangentX, tangentZ);
            if (tangentLength < 0.001) {
                tangentX = -escape.z();
                tangentZ = escape.x();
                tangentLength = 1.0;
            }
            tangentX /= tangentLength;
            tangentZ /= tangentLength;
            if (alignment < 0.0) {
                x = tangentX;
                z = tangentZ;
            } else {
                final double retainedClearance = 0.25;
                x = escape.x() * retainedClearance
                        + tangentX * (1.0 - retainedClearance);
                z = escape.z() * retainedClearance
                        + tangentZ * (1.0 - retainedClearance);
            }
        }
        double length = Math.hypot(x, z);
        if (length < 0.001) return escape;
        return new TacticalCombatPolicy.EscapeVector(x / length, z / length);
    }

    /**
     * Produces the non-negotiable local escape axis for a threat that has
     * crossed an immediate hazard margin. Mission bias and crowd smoothing may
     * choose a useful tangent outside that margin; inside it they must not turn
     * the body back through the exact hazard.
     */
    public static TacticalCombatPolicy.EscapeVector directEscapeFromThreat(
            double threatRelativeX,
            double threatRelativeZ) {
        if (!Double.isFinite(threatRelativeX) || !Double.isFinite(threatRelativeZ)) {
            throw new IllegalArgumentException("threat coordinates must be finite");
        }
        double length = Math.hypot(threatRelativeX, threatRelativeZ);
        if (length < 0.001) {
            return new TacticalCombatPolicy.EscapeVector(1.0, 0.0);
        }
        return new TacticalCombatPolicy.EscapeVector(
                -threatRelativeX / length,
                -threatRelativeZ / length);
    }

    /**
     * Keeps immediate-creeper movement aware of the complete loaded crowd. The
     * aggregate vector is preferred when it does not approach the exact closing
     * creeper; otherwise the exact direct-away axis remains the hard fallback.
     * This prevents two nearby creepers from alternately reversing the body as
     * primary-target selection changes between them.
     */
    public static TacticalCombatPolicy.EscapeVector immediateHazardEscape(
            TacticalCombatPolicy.EscapeVector aggregateEscape,
            double threatRelativeX,
            double threatRelativeZ) {
        Objects.requireNonNull(aggregateEscape, "aggregateEscape");
        TacticalCombatPolicy.EscapeVector direct = directEscapeFromThreat(
                threatRelativeX, threatRelativeZ);
        if (aggregateEscape.equals(TacticalCombatPolicy.EscapeVector.NONE)) {
            return direct;
        }
        double alignment = aggregateEscape.x() * direct.x()
                + aggregateEscape.z() * direct.z();
        return alignment >= -0.000_001 ? aggregateEscape : direct;
    }

    /**
     * Retains the aggregate escape direction for one contiguous protection
     * episode. The tactical primary target is allowed to change as individual
     * mobs move, but a one-frame spider/drowned winner change must not reverse
     * the movement owner or discard the pressure contributed by the other mob.
     */
    public static final class RetreatIntentSession {
        static final long PRIMARY_CHURN_HYSTERESIS_MILLIS = 600L;
        static final long ESCAPE_CORRIDOR_HOLD_MILLIS = 3_000L;
        private String episodeKey = "";
        private String primaryTargetKey = "";
        private String pendingPrimaryTargetKey = "";
        private long pendingSinceMillis = -1L;
        private long committedAtMillis = -1L;
        private TacticalCombatPolicy.EscapeVector committed =
                TacticalCombatPolicy.EscapeVector.NONE;
        private TacticalCombatPolicy.EscapeVector clearedCorridorAxis =
                TacticalCombatPolicy.EscapeVector.NONE;
        private boolean strategicEscapeLocked;
        private boolean corridorEmergencyTurnConsumed;

        public TacticalCombatPolicy.EscapeVector resolve(
                String nextEpisodeKey,
                String nextPrimaryTargetKey,
                long nowMillis,
                TacticalCombatPolicy.EscapeVector aggregateEscape) {
            return resolve(
                    nextEpisodeKey,
                    nextPrimaryTargetKey,
                    nowMillis,
                    aggregateEscape,
                    false,
                    false);
        }

        public TacticalCombatPolicy.EscapeVector resolve(
                String nextEpisodeKey,
                String nextPrimaryTargetKey,
                long nowMillis,
                TacticalCombatPolicy.EscapeVector aggregateEscape,
                boolean immediateHazardAvoidance) {
            return resolve(
                    nextEpisodeKey,
                    nextPrimaryTargetKey,
                    nowMillis,
                    aggregateEscape,
                    immediateHazardAvoidance,
                    false);
        }

        public TacticalCombatPolicy.EscapeVector resolve(
                String nextEpisodeKey,
                String nextPrimaryTargetKey,
                long nowMillis,
                TacticalCombatPolicy.EscapeVector aggregateEscape,
                boolean immediateHazardAvoidance,
                boolean strategicEscape) {
            return resolve(
                    nextEpisodeKey,
                    nextPrimaryTargetKey,
                    nowMillis,
                    aggregateEscape,
                    immediateHazardAvoidance,
                    strategicEscape,
                    TacticalCombatPolicy.EscapeVector.NONE,
                    false);
        }

        public TacticalCombatPolicy.EscapeVector resolve(
                String nextEpisodeKey,
                String nextPrimaryTargetKey,
                long nowMillis,
                TacticalCombatPolicy.EscapeVector aggregateEscape,
                boolean immediateHazardAvoidance,
                boolean strategicEscape,
                TacticalCombatPolicy.EscapeVector exactPrimaryEscape,
                boolean nearTermPrimaryPressure) {
            String normalizedEpisode = normalize(nextEpisodeKey);
            String normalizedPrimary = normalize(nextPrimaryTargetKey);
            TacticalCombatPolicy.EscapeVector candidate =
                    Objects.requireNonNull(aggregateEscape, "aggregateEscape");
            TacticalCombatPolicy.EscapeVector primaryEscape =
                    Objects.requireNonNull(exactPrimaryEscape, "exactPrimaryEscape");
            if (normalizedEpisode.isEmpty()) {
                clear();
                return candidate;
            }
            if (!normalizedEpisode.equals(episodeKey)) {
                episodeKey = normalizedEpisode;
                primaryTargetKey = normalizedPrimary;
                pendingPrimaryTargetKey = "";
                pendingSinceMillis = -1L;
                committed = candidate;
                clearedCorridorAxis = candidate;
                committedAtMillis = nowMillis;
                strategicEscapeLocked = strategicEscape;
                corridorEmergencyTurnConsumed = false;
                return committed;
            }

            if (immediateHazardAvoidance
                    && !candidate.equals(TacticalCombatPolicy.EscapeVector.NONE)) {
                // The retained three-second corridor is a stability tool, not
                // permission to keep approaching a creeper whose fuse can end
                // sooner than that corridor. Replace both the body direction
                // and its cleared-axis guard immediately with the exact local
                // hazard escape; ordinary target churn remains smoothed.
                primaryTargetKey = normalizedPrimary;
                pendingPrimaryTargetKey = "";
                pendingSinceMillis = -1L;
                committed = candidate;
                clearedCorridorAxis = candidate;
                committedAtMillis = nowMillis;
                corridorEmergencyTurnConsumed = true;
                return committed;
            }

            if (candidate.equals(TacticalCombatPolicy.EscapeVector.NONE)) {
                // A brief threat-sampling gap inside the same episode is not a
                // direction change. Keep the last aggregate escape intent.
                return committed;
            }
            if (committed.equals(TacticalCombatPolicy.EscapeVector.NONE)) {
                // The episode may begin on a core-only frame before the first
                // complete tactical crowd sample. Adopt that first real vector
                // immediately instead of retreating from only the primary mob
                // for an arbitrary corridor interval.
                primaryTargetKey = normalizedPrimary;
                pendingPrimaryTargetKey = "";
                pendingSinceMillis = -1L;
                committed = candidate;
                clearedCorridorAxis = candidate;
                committedAtMillis = nowMillis;
                strategicEscapeLocked = strategicEscape;
                corridorEmergencyTurnConsumed = false;
                return committed;
            }
            if (strategicEscape) {
                if (!strategicEscapeLocked) {
                    // Entering a no-approach survival withdrawal is a strategic
                    // state transition. Choose one current crowd- and mission-
                    // informed corridor, then keep running it instead of taking
                    // a fresh three-second vote from every newly nearest mob.
                    // A direction that would reverse through the corridor just
                    // cleared is converted to the same safe lateral turn used
                    // by an ordinary pinch.
                    committed = preserveClearedCorridor(
                            committed, candidate, clearedCorridorAxis,
                            normalizedEpisode);
                    clearedCorridorAxis = committed;
                    committedAtMillis = nowMillis;
                    strategicEscapeLocked = true;
                    primaryTargetKey = normalizedPrimary;
                    pendingPrimaryTargetKey = "";
                    pendingSinceMillis = -1L;
                    corridorEmergencyTurnConsumed = false;
                }
                boolean replacementCutsOffCorridor = nearTermPrimaryPressure
                        && !normalizedPrimary.isEmpty()
                        && !normalizedPrimary.equals(primaryTargetKey)
                        && materiallyOpposed(committed, primaryEscape);
                if (replacementCutsOffCorridor) {
                    if (Math.max(0L, nowMillis - committedAtMillis)
                            >= ESCAPE_CORRIDOR_HOLD_MILLIS) {
                        // One completed displacement corridor earns one new
                        // obstruction response. This permits a deliberate arc
                        // through a changing real crowd without reopening
                        // frame-by-frame primary-target steering.
                        corridorEmergencyTurnConsumed = false;
                    }
                    if (!corridorEmergencyTurnConsumed) {
                        TacticalCombatPolicy.EscapeVector turnCandidate =
                                materiallyOpposed(committed, candidate)
                                        ? candidate
                                        : primaryEscape;
                        committed = preserveClearedCorridor(
                                committed, turnCandidate, clearedCorridorAxis,
                                normalizedEpisode);
                        clearedCorridorAxis = committed;
                        committedAtMillis = nowMillis;
                        corridorEmergencyTurnConsumed = true;
                    }
                    primaryTargetKey = normalizedPrimary;
                    pendingPrimaryTargetKey = "";
                    pendingSinceMillis = -1L;
                }
                return committed;
            }
            if (strategicEscapeLocked) {
                // Recovery ends the evacuation lock, but retains its last safe
                // heading for one ordinary corridor interval. This prevents a
                // health-threshold frame from snapping the body straight back
                // toward the crowd it just escaped.
                strategicEscapeLocked = false;
                committedAtMillis = nowMillis;
            }
            if (Math.max(0L, nowMillis - committedAtMillis)
                    >= ESCAPE_CORRIDOR_HOLD_MILLIS) {
                // Retreat is a short strategic run, not a frame-by-frame vote.
                // Refresh from the complete crowd only after the body has had
                // time to traverse a useful corridor segment. A crowd that is
                // now behind the body still cannot make it run straight back
                // through the corridor it just cleared; hand that reversal off
                // as a stable lateral turn instead.
                primaryTargetKey = normalizedPrimary;
                pendingPrimaryTargetKey = "";
                pendingSinceMillis = -1L;
                committed = preserveClearedCorridor(
                        committed, candidate, clearedCorridorAxis,
                        normalizedEpisode);
                // The guard is one completed strategic segment, not the whole
                // encounter's first heading. Rolling it forward permits a real
                // player-like arc around pressure while still forbidding an
                // instantaneous 180-degree retreat reversal.
                clearedCorridorAxis = committed;
                committedAtMillis = nowMillis;
                corridorEmergencyTurnConsumed = false;
                return committed;
            }
            if (primaryTargetKey.isEmpty() || normalizedPrimary.equals(primaryTargetKey)) {
                primaryTargetKey = normalizedPrimary;
                pendingPrimaryTargetKey = "";
                pendingSinceMillis = -1L;
                return committed;
            }

            if (!normalizedPrimary.equals(pendingPrimaryTargetKey)) {
                pendingPrimaryTargetKey = normalizedPrimary;
                pendingSinceMillis = nowMillis;
                if (!corridorEmergencyTurnConsumed
                        && materiallyOpposed(committed, candidate)) {
                    // A new primary plus an opposed complete-crowd vector means
                    // the current lane is cut off now. Continuing for target
                    // hysteresis walks into the new blocker; accepting the raw
                    // opposite vector runs back into the mobs already escaped.
                    // Turn across the pinch immediately while retaining a small
                    // outward component, then hold that new corridor.
                    primaryTargetKey = normalizedPrimary;
                    pendingPrimaryTargetKey = "";
                    pendingSinceMillis = -1L;
                    committed = preserveClearedCorridor(
                            committed, candidate, clearedCorridorAxis,
                            normalizedEpisode);
                    committedAtMillis = nowMillis;
                    corridorEmergencyTurnConsumed = true;
                }
                return committed;
            }
            if (Math.max(0L, nowMillis - pendingSinceMillis)
                    < PRIMARY_CHURN_HYSTERESIS_MILLIS) {
                return committed;
            }
            primaryTargetKey = normalizedPrimary;
            pendingPrimaryTargetKey = "";
            pendingSinceMillis = -1L;
            if (!corridorEmergencyTurnConsumed
                    && materiallyOpposed(committed, candidate)) {
                committed = preserveClearedCorridor(
                        committed, candidate, clearedCorridorAxis,
                        normalizedEpisode);
                committedAtMillis = nowMillis;
                corridorEmergencyTurnConsumed = true;
            }
            // A stable target replacement changes who protection watches, but
            // it does not turn the body around mid-corridor. The aggregate
            // crowd vector is otherwise sampled again at the bounded corridor
            // boundary.
            return committed;
        }

        public void clear() {
            episodeKey = "";
            primaryTargetKey = "";
            pendingPrimaryTargetKey = "";
            pendingSinceMillis = -1L;
            committedAtMillis = -1L;
            committed = TacticalCombatPolicy.EscapeVector.NONE;
            clearedCorridorAxis = TacticalCombatPolicy.EscapeVector.NONE;
            strategicEscapeLocked = false;
            corridorEmergencyTurnConsumed = false;
        }

        private static String normalize(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }

        private static boolean materiallyOpposed(
                TacticalCombatPolicy.EscapeVector current,
                TacticalCombatPolicy.EscapeVector candidate) {
            if (current.equals(TacticalCombatPolicy.EscapeVector.NONE)
                    || candidate.equals(TacticalCombatPolicy.EscapeVector.NONE)) {
                return false;
            }
            return current.x() * candidate.x() + current.z() * candidate.z() < -0.25;
        }

        /**
         * Caps an opposed crowd update at one non-reversing turn. The lateral
         * component follows the new pressure when it is meaningful; an exact
         * head-on pinch chooses one episode-stable side. A small retained
         * forward component keeps real displacement while the body crosses the
         * old corridor instead of pivoting in place.
         */
        private static TacticalCombatPolicy.EscapeVector preserveClearedCorridor(
                TacticalCombatPolicy.EscapeVector current,
                TacticalCombatPolicy.EscapeVector candidate,
                TacticalCombatPolicy.EscapeVector clearedAxis,
                String episodeKey) {
            TacticalCombatPolicy.EscapeVector guardAxis =
                    clearedAxis.equals(TacticalCombatPolicy.EscapeVector.NONE)
                            ? current
                            : clearedAxis;
            boolean returnsThroughClearedCorridor =
                    guardAxis.x() * candidate.x()
                            + guardAxis.z() * candidate.z() < -0.05;
            if (!returnsThroughClearedCorridor
                    && !materiallyOpposed(current, candidate)) {
                return candidate;
            }
            TacticalCombatPolicy.EscapeVector turnAxis =
                    returnsThroughClearedCorridor ? guardAxis : current;

            double alignment = turnAxis.x() * candidate.x()
                    + turnAxis.z() * candidate.z();
            double lateralX = candidate.x() - turnAxis.x() * alignment;
            double lateralZ = candidate.z() - turnAxis.z() * alignment;
            double lateralLength = Math.hypot(lateralX, lateralZ);
            if (lateralLength < 0.001) {
                boolean clockwise = (normalize(episodeKey).hashCode() & 1) == 0;
                lateralX = clockwise ? -turnAxis.z() : turnAxis.z();
                lateralZ = clockwise ? turnAxis.x() : -turnAxis.x();
                lateralLength = 1.0;
            }
            lateralX /= lateralLength;
            lateralZ /= lateralLength;

            final double retainedForward = 0.20;
            double x = lateralX + turnAxis.x() * retainedForward;
            double z = lateralZ + turnAxis.z() * retainedForward;
            double length = Math.hypot(x, z);
            return new TacticalCombatPolicy.EscapeVector(x / length, z / length);
        }
    }

    /**
     * Retains only an authoritative no-approach safety decision across brief
     * tactical-sampling gaps for the same live protection episode and target.
     * Missing observation is never allowed to downgrade withdrawal into the
     * core policy's coarser INTERCEPT fallback. A positive tactical observation,
     * target change, or episode change remains authoritative and clears it.
     */
    public static final class SafetyRetreatLatch {
        private String episodeKey = "";
        private String targetKey = "";
        private Plan retained;

        public Optional<Plan> resolve(
                String nextEpisodeKey,
                String nextTargetKey,
                Optional<Plan> observed) {
            String episode = normalize(nextEpisodeKey);
            String target = normalize(nextTargetKey);
            Optional<Plan> sample = Objects.requireNonNull(observed, "observed");
            if (episode.isEmpty() || target.isEmpty()) {
                clear();
                return sample;
            }
            if (sample.isPresent()) {
                Plan plan = sample.orElseThrow();
                if (plan.engagementIntent() == EngagementIntent.SAFETY_RETREAT) {
                    episodeKey = episode;
                    targetKey = target;
                    retained = plan;
                } else {
                    clear();
                }
                return sample;
            }
            if (retained != null
                    && episode.equals(episodeKey)
                    && target.equals(targetKey)) {
                return Optional.of(retained);
            }
            clear();
            return Optional.empty();
        }

        public void clear() {
            episodeKey = "";
            targetKey = "";
            retained = null;
        }

        private static String normalize(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }
    }

    /** Maps Minecraft entity type paths without importing Minecraft classes. */
    public static TacticalCombatPolicy.ThreatKind threatKind(String rawType) {
        String type = Objects.requireNonNullElse(rawType, "")
                .trim().toLowerCase(Locale.ROOT);
        int separator = type.indexOf(':');
        if (separator >= 0) type = type.substring(separator + 1);
        return switch (type) {
            case "creeper" -> TacticalCombatPolicy.ThreatKind.CREEPER;
            case "skeleton", "stray", "bogged", "pillager", "drowned" ->
                    TacticalCombatPolicy.ThreatKind.SKELETON;
            case "player" -> TacticalCombatPolicy.ThreatKind.PLAYER;
            case "zombie", "husk", "zombie_villager", "spider", "cave_spider",
                    "silverfish", "endermite", "vindicator", "piglin_brute",
                    "zombified_piglin", "warden", "ravager" ->
                    TacticalCombatPolicy.ThreatKind.MELEE;
            default -> TacticalCombatPolicy.ThreatKind.OTHER;
        };
    }

    /**
     * A distant-threat separation lease may temporarily override a richer
     * tactical combat decision. If verified local escape geometry then stalls,
     * only an ordinary melee blocker that the current tactical observation says
     * is safe to approach may receive the existing bounded resolution window.
     * Ranged mobs, creepers, genuine safety retreats, and incomplete observations
     * remain no-approach.
     */
    public static boolean stalledSeparationMayNeutralize(
            boolean defensiveSeparationRequired,
            String entityType,
            Plan tacticalPlan) {
        return defensiveSeparationRequired
                && stalledSeparationResolutionMayContinue(entityType, tacticalPlan);
    }

    /**
     * Once a failed escape has granted one bounded exact-target resolution,
     * retain it across the melee-clearance boundary. A hit can knock a zombie
     * from 3.1 to 3.3 blocks; that must not flip the body from combat back to
     * retreat mid-fight. New ranged/creeper pressure or a genuine safety plan
     * still revokes the capability immediately.
     */
    public static boolean stalledSeparationResolutionMayContinue(
            String entityType,
            Plan tacticalPlan) {
        return tacticalPlan != null
                && threatKind(entityType) == TacticalCombatPolicy.ThreatKind.MELEE
                && tacticalPlan.engagementIntent() == EngagementIntent.NEUTRALIZE_BLOCKER
                && tacticalPlan.approachAllowed();
    }

    public enum Execution {
        DIRECT_RETREAT,
        INTERCEPT,
        OBSERVE
    }

    /**
     * Authoritative protection intent after reconciling the coarse core lease
     * with the richer tactical observation. Runtime code should branch on this
     * value rather than re-reading the coarse core action.
     */
    public enum EngagementIntent {
        /** A healthy reachable attacker blocks the retained mission. */
        NEUTRALIZE_BLOCKER,
        /** A short movement-away action, not a new mission mode. */
        TACTICAL_SPACING,
        /** Ordinary mobs permit one no-progress-bounded shielded resolution after retreat fails. */
        BOUNDED_RETREAT,
        /** A safety condition forbids converting withdrawal into approach. */
        SAFETY_RETREAT,
        /** The retained core target currently requires no movement or attack. */
        OBSERVE_ONLY
    }

    public record Plan(
            Execution execution,
            EngagementIntent engagementIntent,
            boolean approachAllowed,
            TacticalCombatPolicy.LoadoutIntent loadout,
            Optional<String> targetId,
            TacticalCombatPolicy.EscapeVector escape,
            TacticalCombatPolicy.Action tacticalAction,
            String detail) {
        public Plan {
            execution = Objects.requireNonNull(execution, "execution");
            engagementIntent = Objects.requireNonNull(
                    engagementIntent, "engagementIntent");
            loadout = Objects.requireNonNull(loadout, "loadout");
            targetId = Objects.requireNonNull(targetId, "targetId");
            escape = Objects.requireNonNull(escape, "escape");
            tacticalAction = Objects.requireNonNull(tacticalAction, "tacticalAction");
            detail = Objects.requireNonNullElse(detail, "").trim();
            Execution expected = switch (engagementIntent) {
                case NEUTRALIZE_BLOCKER -> Execution.INTERCEPT;
                case OBSERVE_ONLY -> Execution.OBSERVE;
                default -> Execution.DIRECT_RETREAT;
            };
            if (execution != expected) {
                throw new IllegalArgumentException(
                        "execution must be the compatibility projection of engagementIntent");
            }
            if (engagementIntent != EngagementIntent.NEUTRALIZE_BLOCKER
                    && approachAllowed) {
                throw new IllegalArgumentException(
                        "spacing and safety-retreat plans cannot permit approach");
            }
        }

        /**
         * Every direct retreat may traverse a verified one-block step. The
         * actuator still proves the obstacle, landing body cells, headroom,
         * loaded chunk, and hazard margin; this is not arbitrary climbing or
         * permission to enter unverified fall exposure.
         */
        public boolean retreatAscentAllowed() {
            return execution == Execution.DIRECT_RETREAT;
        }
    }
}
