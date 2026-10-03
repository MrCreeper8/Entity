package dev.entity.client.telemetry;

import dev.entity.client.control.PlanarProgressGeometry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, bounded detector for movement and work loops that look busy but make no
 * mission progress. The monitor owns no Minecraft objects and performs no I/O,
 * so runtime adapters can feed it one immutable observation per client tick.
 */
public final class BehaviorInvariantMonitor {
    private static final long UNSET = Long.MIN_VALUE;
    /**
     * One ordinary Minecraft ledge retry can produce down/up/down samples in
     * well under a second. Require reversals to persist across multiple jump
     * cycles before classifying them as an unproductive vertical loop.
     */
    private static final long MIN_VERTICAL_OSCILLATION_SPAN_MILLIS = 2_000L;
    /**
     * Inventory and block acknowledgements can arrive a few client ticks after
     * the physical reversal that produced them. Arm the detector at the
     * sustained-loop threshold, then require the condition to remain true long
     * enough for imminent verified work to clear it.
     */
    private static final long VERTICAL_OSCILLATION_CONFIRMATION_MILLIS = 1_000L;
    /**
     * Crossing several real blocks through an earlier retreat trail is still
     * movement, not the tight stationary/corner failure caught by the normal
     * two-second bound. Give that larger loop enough time to prove repetition;
     * DirectBody's three 1.5-second route failures remain the earlier actuator
     * fallback. A continued large loop is still reported after this bound.
     */
    private static final long MOVING_RETREAT_LOOP_CONFIRMATION_MILLIS = 5_000L;

    private final Settings settings;
    private final LinkedHashMap<BlockCoordinate, SupportCycle> supportCycles = new LinkedHashMap<>();
    private final Deque<Long> mineRestartTimes = new ArrayDeque<>();
    private final Deque<Long> verticalReversalTimes = new ArrayDeque<>();

    private long lastTimestamp = UNSET;
    private String activeMission;
    private long objectiveHighWater = UNSET;
    private long workHighWater = UNSET;

    private String mineKey;
    private long mineGeneration = UNSET;
    private boolean mineChurnLatched;

    private String verticalDimension;
    private double verticalColumnX;
    private double verticalColumnZ;
    private double verticalAnchorY;
    private double verticalExtremeY;
    private int verticalDirection;
    private long lastVerticalSampleAt = UNSET;
    private long verticalConfirmationStartedAt = UNSET;
    private boolean verticalOscillationLatched;

    private boolean previouslyInLava;
    private LavaExposure lavaExposure;
    private LavaExit lastLavaExit;
    private boolean lavaStallLatched;

    private ProtectionRetreatEpisode protectionRetreat;
    private boolean protectionRetreatStallLatched;
    private SurfaceProtectionLoop surfaceProtectionLoop;
    private boolean surfaceProtectionLoopLatched;
    private SuffocationEpisode suffocationEpisode;
    private boolean suffocationStallLatched;

    public BehaviorInvariantMonitor() {
        this(Settings.defaults());
    }

    public BehaviorInvariantMonitor(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Evaluates one chronological observation and returns only newly detected violations. */
    public List<Violation> observe(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        long now = observation.monotonicMillis();
        if (lastTimestamp != UNSET && now < lastTimestamp) {
            resetAll();
        }
        lastTimestamp = now;

        String mission = observation.missionId();
        if (!Objects.equals(activeMission, mission)) {
            resetMissionState();
            activeMission = mission;
            objectiveHighWater = observation.objectiveHighWater();
            workHighWater = observation.workHighWater();
        }

        boolean advanced = observation.objectiveHighWater() > objectiveHighWater
                || observation.workHighWater() > workHighWater;
        objectiveHighWater = Math.max(objectiveHighWater, observation.objectiveHighWater());
        workHighWater = Math.max(workHighWater, observation.workHighWater());
        if (advanced) {
            supportCycles.clear();
            mineRestartTimes.clear();
            mineChurnLatched = false;
            resetVertical(observation);
        }

        List<Violation> violations = new ArrayList<>(6);
        inspectSupportCycles(observation, violations);
        inspectMiningRestarts(observation, advanced, violations);
        inspectVerticalMotion(observation, advanced, violations);
        inspectLava(observation, violations);
        inspectProtectionLoops(observation, violations);
        inspectSuffocation(observation, violations);
        return List.copyOf(violations);
    }

    /** Small observable state surface used to prove histories remain bounded. */
    public StateSnapshot stateSnapshot() {
        return new StateSnapshot(
                supportCycles.size(),
                mineRestartTimes.size(),
                verticalReversalTimes.size(),
                lavaExposure != null,
                lastLavaExit != null,
                protectionRetreat != null,
                surfaceProtectionLoop == null ? 0 : surfaceProtectionLoop.transitions,
                suffocationEpisode != null);
    }

    /**
     * Ends every physical episode at an external observation boundary (for
     * example, disconnect, respawned world replacement, or an unloaded player).
     * The next observation is treated as the beginning of a new episode even
     * when the retained mission id is unchanged.
     */
    public void reset() {
        lastTimestamp = UNSET;
        resetAll();
    }

    private void inspectSuffocation(
            Observation observation,
            List<Violation> violations) {
        long now = observation.monotonicMillis();
        if (!observation.insideWall()) {
            suffocationEpisode = null;
            suffocationStallLatched = false;
            return;
        }
        if (suffocationEpisode == null
                || !suffocationEpisode.dimension.equals(observation.dimension())) {
            suffocationEpisode = new SuffocationEpisode(
                    now, observation.dimension(), observation.position());
            suffocationStallLatched = false;
        }
        suffocationEpisode.progress.observe(
                now,
                observation.position(),
                settings.suffocationMinimumEscapeDistance(),
                false);
        if (!suffocationStallLatched
                && suffocationEpisode.progress.stalledFor(now)
                        >= settings.suffocationStallMillis()) {
            suffocationStallLatched = true;
            String target = observation.suffocationTarget().isBlank()
                    ? "no pinned collision"
                    : "target " + observation.suffocationTarget();
            violations.add(new Violation(
                    ViolationType.SUFFOCATION_ESCAPE_STALL,
                    now,
                    observation.clientTick(),
                    observation.missionId(),
                    "remained inside a solid block for "
                            + suffocationEpisode.progress.stalledFor(now)
                            + "ms since the last escape checkpoint with only "
                            + String.format(Locale.ROOT, "%.2f",
                                    suffocationEpisode.progress.maximumDisplacement)
                            + " blocks of movement; " + target));
        }
    }

    private void inspectSupportCycles(Observation observation, List<Violation> violations) {
        long now = observation.monotonicMillis();
        Iterator<Map.Entry<BlockCoordinate, SupportCycle>> iterator = supportCycles.entrySet().iterator();
        while (iterator.hasNext()) {
            SupportCycle cycle = iterator.next().getValue();
            if (now - cycle.placedAt > settings.scaffoldWindowMillis()) {
                iterator.remove();
                continue;
            }
            if (cycle.coordinate.dimension().equals(observation.dimension())) {
                cycle.maxBodyY = Math.max(cycle.maxBodyY, observation.position().y());
            }
        }

        for (BlockChange change : observation.blockChanges()) {
            if (change.verifiedMilestone()) {
                supportCycles.remove(change.coordinate());
                continue;
            }
            if (change.kind() == BlockChangeKind.PLACED
                    && change.purpose() == BlockPurpose.ROUTE_SUPPORT) {
                putBoundedSupport(new SupportCycle(
                        change.coordinate(),
                        now,
                        observation.position().y(),
                        observation.position().y()));
            } else if (change.kind() == BlockChangeKind.BROKEN) {
                SupportCycle cycle = supportCycles.get(change.coordinate());
                if (cycle != null) cycle.brokenAt = now;
            }
        }

        iterator = supportCycles.entrySet().iterator();
        while (iterator.hasNext()) {
            SupportCycle cycle = iterator.next().getValue();
            boolean rose = cycle.maxBodyY >= cycle.startBodyY + settings.scaffoldMinimumRise();
            boolean returned = observation.position().y()
                    <= cycle.startBodyY + settings.scaffoldReturnTolerance();
            if (cycle.brokenAt != UNSET && rose && returned) {
                // A player may deliberately pillar, reclaim the block beneath
                // themselves and descend or clutch. This is ordinary survival
                // movement, not sufficient evidence for a circuit breaker.
                iterator.remove();
            }
        }
    }

    private void putBoundedSupport(SupportCycle cycle) {
        supportCycles.remove(cycle.coordinate);
        while (supportCycles.size() >= settings.maxTrackedSupports()) {
            Iterator<BlockCoordinate> eldest = supportCycles.keySet().iterator();
            if (!eldest.hasNext()) break;
            eldest.next();
            eldest.remove();
        }
        supportCycles.put(cycle.coordinate, cycle);
    }

    private void inspectMiningRestarts(
            Observation observation,
            boolean advanced,
            List<Violation> violations) {
        String nextKey = miningKey(observation);
        if (nextKey == null) {
            mineKey = null;
            mineGeneration = UNSET;
            mineRestartTimes.clear();
            mineChurnLatched = false;
            return;
        }
        if (!Objects.equals(mineKey, nextKey)) {
            mineKey = nextKey;
            mineGeneration = observation.operationGeneration();
            mineRestartTimes.clear();
            mineChurnLatched = false;
            return;
        }
        if (advanced) {
            mineGeneration = observation.operationGeneration();
            return;
        }

        long now = observation.monotonicMillis();
        pruneTimes(mineRestartTimes, now - settings.mineRestartWindowMillis());
        if (mineRestartTimes.size() <= settings.maxMineRestarts()) mineChurnLatched = false;

        long generation = observation.operationGeneration();
        if (generation >= 0L && mineGeneration >= 0L && generation != mineGeneration) {
            mineRestartTimes.addLast(now);
            trimOldest(mineRestartTimes, settings.maxMineRestartEvents());
        }
        mineGeneration = generation;

        if (mineRestartTimes.size() > settings.maxMineRestarts() && !mineChurnLatched) {
            mineChurnLatched = true;
            violations.add(new Violation(
                    ViolationType.MINE_RESTART_CHURN,
                    now,
                    observation.clientTick(),
                    observation.missionId(),
                    mineRestartTimes.size() + " mining operation restarts inside "
                            + settings.mineRestartWindowMillis() + "ms without work progress"));
        }
    }

    private static String miningKey(Observation observation) {
        String operation = observation.operation().toLowerCase(Locale.ROOT);
        if (!operation.equals("mine")
                && !operation.equals("mining")
                && !operation.equals("acquire")
                && !operation.equals("fetch")
                && !operation.equals("get")) {
            return null;
        }
        if (!observation.operationIdentity().isBlank()) {
            return observation.missionId() + "|operation:"
                    + observation.operationIdentity().toLowerCase(Locale.ROOT);
        }
        String leaf = !observation.planLeaf().isBlank()
                ? observation.planLeaf()
                : (!observation.phase().isBlank() ? observation.phase() : observation.operation());
        return observation.missionId() + "|leaf:" + leaf.toLowerCase(Locale.ROOT);
    }

    private void inspectVerticalMotion(
            Observation observation,
            boolean advanced,
            List<Violation> violations) {
        if (advanced) return;
        if (observation.controlLayer().equalsIgnoreCase("PROTECTION")) {
            // The mission stays admitted while DirectBody borrows its controls.
            // Combat jumps/knockback are not motion of the paused mining route.
            // Clear history so it cannot trip that route when protection releases;
            // the independent protection-loop monitors still evaluate this tick.
            // A retained threat or latch without actual ownership is no exemption.
            resetVertical(observation);
            return;
        }
        if (observation.boundedVerticalRecovery() || observation.touchingWater()) {
            // Breathing at a verified air pocket and acquiring an underwater swim pose both
            // deliberately reverse Y direction in one column. Ordinary buoyancy at a shore lip
            // does the same while feet cross the waterline. AquaticTravelPolicy owns a bounded
            // planar stall/recovery path for those samples, so they are not evidence of the dry
            // route/scaffold loop detected here. Reset instead of merely skipping the sample so
            // water motion cannot contaminate the resumed land or mining route.
            resetVertical(observation);
            return;
        }
        long now = observation.monotonicMillis();
        Vec3 position = observation.position();
        if (verticalDimension == null
                || !verticalDimension.equals(observation.dimension())
                || lastVerticalSampleAt == UNSET
                || now - lastVerticalSampleAt > settings.verticalWindowMillis()
                || planarDistance(verticalColumnX, verticalColumnZ, position.x(), position.z())
                        > settings.verticalColumnRadius()) {
            resetVertical(observation);
            return;
        }
        lastVerticalSampleAt = now;
        pruneTimes(verticalReversalTimes, now - settings.verticalWindowMillis());
        if (verticalReversalTimes.size() < settings.maxVerticalReversals()) {
            verticalOscillationLatched = false;
        }

        double y = position.y();
        double step = settings.verticalMinimumStep();
        if (verticalDirection == 0) {
            if (y >= verticalAnchorY + step) {
                verticalDirection = 1;
                verticalExtremeY = y;
            } else if (y <= verticalAnchorY - step) {
                verticalDirection = -1;
                verticalExtremeY = y;
            }
        } else if (verticalDirection > 0) {
            if (y > verticalExtremeY) {
                verticalExtremeY = y;
            } else if (y <= verticalExtremeY - step) {
                verticalDirection = -1;
                verticalExtremeY = y;
                recordVerticalReversal(now);
            }
        } else if (y < verticalExtremeY) {
            verticalExtremeY = y;
        } else if (y >= verticalExtremeY + step) {
            verticalDirection = 1;
            verticalExtremeY = y;
            recordVerticalReversal(now);
        }

        boolean sustainedOscillation = verticalReversalTimes.size()
                        >= settings.maxVerticalReversals()
                && verticalReversalTimes.peekFirst() != null
                && verticalReversalTimes.peekLast() != null
                && verticalReversalTimes.peekLast() - verticalReversalTimes.peekFirst()
                        >= MIN_VERTICAL_OSCILLATION_SPAN_MILLIS;
        if (!sustainedOscillation) {
            verticalConfirmationStartedAt = UNSET;
            return;
        }
        if (verticalConfirmationStartedAt == UNSET) {
            verticalConfirmationStartedAt = now;
            return;
        }
        if (!verticalOscillationLatched
                && now - verticalConfirmationStartedAt
                        >= VERTICAL_OSCILLATION_CONFIRMATION_MILLIS) {
            verticalOscillationLatched = true;
            violations.add(new Violation(
                    ViolationType.VERTICAL_OSCILLATION,
                    now,
                    observation.clientTick(),
                    observation.missionId(),
                    verticalReversalTimes.size() + " vertical direction reversals spanning "
                            + (verticalReversalTimes.peekLast() - verticalReversalTimes.peekFirst())
                            + "ms inside " + settings.verticalWindowMillis()
                            + "ms in one column without work progress; condition remained "
                            + "unproductive for " + (now - verticalConfirmationStartedAt)
                            + "ms after arming"));
        }
    }

    private void recordVerticalReversal(long now) {
        verticalReversalTimes.addLast(now);
        trimOldest(verticalReversalTimes, settings.maxVerticalEvents());
    }

    private void resetVertical(Observation observation) {
        verticalDimension = observation.dimension();
        verticalColumnX = observation.position().x();
        verticalColumnZ = observation.position().z();
        verticalAnchorY = observation.position().y();
        verticalExtremeY = verticalAnchorY;
        verticalDirection = 0;
        lastVerticalSampleAt = observation.monotonicMillis();
        verticalReversalTimes.clear();
        verticalConfirmationStartedAt = UNSET;
        verticalOscillationLatched = false;
    }

    private void inspectLava(Observation observation, List<Violation> violations) {
        long now = observation.monotonicMillis();
        if (lastLavaExit != null && now - lastLavaExit.exitedAt > settings.lavaReentryWindowMillis()) {
            lastLavaExit = null;
        }

        if (observation.inLava()) {
            if (!previouslyInLava) {
                if (lastLavaExit != null
                        && observation.hazardRecovery()
                        && lastLavaExit.dimension.equals(observation.dimension())
                        && now - lastLavaExit.exitedAt <= settings.lavaReentryWindowMillis()
                        && distance(lastLavaExit.position, observation.position())
                                <= settings.lavaReentryRadius()) {
                    violations.add(new Violation(
                            ViolationType.HAZARD_REENTRY,
                            now,
                            observation.clientTick(),
                            observation.missionId(),
                            "re-entered the recently escaped lava cluster during hazard recovery"));
                    lastLavaExit = null;
                }
                lavaExposure = new LavaExposure(now, observation.dimension(), observation.position());
                lavaStallLatched = false;
            }
            if (lavaExposure == null || !lavaExposure.dimension.equals(observation.dimension())) {
                lavaExposure = new LavaExposure(now, observation.dimension(), observation.position());
                lavaStallLatched = false;
            }
            if (observation.hazardEscapeMilestone()) {
                lavaExposure = new LavaExposure(now, observation.dimension(), observation.position());
                lavaStallLatched = false;
            }
            lavaExposure.progress.observe(
                    now,
                    observation.position(),
                    settings.lavaMinimumPlanarEscape(),
                    true);
            if (!lavaStallLatched
                    && lavaExposure.progress.stalledFor(now) >= settings.lavaStallMillis()) {
                lavaStallLatched = true;
                violations.add(new Violation(
                        ViolationType.LAVA_ESCAPE_STALL,
                        now,
                    observation.clientTick(),
                    observation.missionId(),
                    "remained in lava for " + lavaExposure.progress.stalledFor(now)
                            + "ms since the last horizontal escape checkpoint"));
            }
        } else if (previouslyInLava) {
            lastLavaExit = new LavaExit(now, observation.dimension(), observation.position());
            lavaExposure = null;
            lavaStallLatched = false;
        }
        previouslyInLava = observation.inLava();
    }

    /**
     * Tracks one hostile encounter across protection and survival ownership.
     * A retreat that is temporarily preempted to breathe is still the same
     * physical escape attempt, so its spatial trail survives the hand-off.
     * The retreat deadline itself advances only while protection owns the
     * body: a legitimate vertical ascent or air-pocket hold is supervised by
     * the aquatic watchdog and must not be mislabeled as a planar retreat
     * stall.
     */
    private void inspectProtectionLoops(
            Observation observation,
            List<Violation> violations) {
        long now = observation.monotonicMillis();
        ControlPhase phase = controlPhase(observation);
        if (!observation.liveThreat()) {
            clearProtectionLoops();
            return;
        }

        // RETREAT is the Core ownership label for both physical separation and the
        // bounded shielded tactical-attack path.  The latter deliberately advances,
        // blocks, takes knockback, and re-approaches; treating its attack operation
        // as a monotonic escape produces a false stall even while it wins the fight.
        boolean physicalRetreatOperation = !observation.operation().equalsIgnoreCase("attack")
                && !observation.operation().equalsIgnoreCase("protection_escape");
        boolean retreatEpisodePhase = (phase == ControlPhase.PROTECTION_RETREAT
                && physicalRetreatOperation)
                || (phase == ControlPhase.SURFACE_FOR_AIR && protectionRetreat != null);
        if (phase == ControlPhase.PROTECTION_RETREAT
                && !physicalRetreatOperation
                && protectionRetreat != null) {
            // These exact native operations are bounded by their own physical/goal/work
            // watchdog. Calculation and legitimate stone breaking are not a two-second
            // DirectBody retreat stall; frozen native work still exhausts its route budget.
            protectionRetreat.lastObservedAt = now;
            protectionRetreat.progress.pauseStallClock(now);
        }
        if (retreatEpisodePhase) {
            if (protectionRetreat == null
                    || !protectionRetreat.dimension.equals(observation.dimension())
                    || now - protectionRetreat.lastObservedAt
                            > settings.protectionRetreatContinuityMillis()) {
                protectionRetreat = new ProtectionRetreatEpisode(
                        now, observation.dimension(), observation.position(), observation.health());
                protectionRetreatStallLatched = false;
            }
            protectionRetreat.lastObservedAt = now;
            protectionRetreat.minimumHealth = Math.min(
                    protectionRetreat.minimumHealth, observation.health());
            protectionRetreat.progress.observe(
                    now,
                    observation.position(),
                    settings.protectionRetreatMinimumPlanarEscape());
            if (phase == ControlPhase.SURFACE_FOR_AIR
                    || observation.verifiedProtectionCoverHold()
                    || observation.boundedProtectionReleaseWait()
                    || observation.verifiedProtectionCounterattackWork()) {
                // Explicit non-translation work has its own bounded owner. A fixed
                // occluded-threat release lease is supervised by the distant-threat
                // state machine, and each accepted DirectBody counterattack is a
                // fresh combat-work checkpoint. Charging either to this generic
                // two-second escape alarm makes it abort the real owner first.
                // Pause only this physical-retreat clock; a release lease ends on
                // danger, while a counterattack grants only one fresh stall window.
                protectionRetreat.progress.pauseStallClock(now);
            } else if (!protectionRetreatStallLatched
                    && protectionRetreat.progress.stalledFor(now)
                            >= protectionRetreatStallDeadline(
                                    protectionRetreat.progress.maximumDisplacement)) {
                protectionRetreatStallLatched = true;
                violations.add(new Violation(
                        ViolationType.PROTECTION_RETREAT_STALL,
                        now,
                    observation.clientTick(),
                    observation.missionId(),
                    "protection retreat remained within "
                                + String.format(Locale.ROOT, "%.2f",
                                        protectionRetreat.progress.maximumDisplacement)
                                + " blocks for " + protectionRetreat.progress.stalledFor(now)
                                + "ms since its last checkpoint while a live threat retained control; health "
                                + String.format(Locale.ROOT, "%.1f", protectionRetreat.initialHealth)
                                + " -> "
                                + String.format(Locale.ROOT, "%.1f", protectionRetreat.minimumHealth)));
            }
        } else if (protectionRetreat != null
                && now - protectionRetreat.lastObservedAt
                        > settings.protectionRetreatContinuityMillis()) {
            protectionRetreat = null;
            protectionRetreatStallLatched = false;
        }

        if (phase != ControlPhase.PROTECTION && phase != ControlPhase.PROTECTION_RETREAT
                && phase != ControlPhase.SURFACE_FOR_AIR) {
            if (surfaceProtectionLoop != null
                    && now - surfaceProtectionLoop.lastObservedAt
                            > settings.surfaceProtectionWindowMillis()) {
                surfaceProtectionLoop = null;
                surfaceProtectionLoopLatched = false;
            }
            return;
        }

        ControlPhase loopPhase = phase == ControlPhase.SURFACE_FOR_AIR
                ? ControlPhase.SURFACE_FOR_AIR : ControlPhase.PROTECTION;
        if (surfaceProtectionLoop == null
                || now - surfaceProtectionLoop.lastObservedAt
                        > settings.surfaceProtectionWindowMillis()) {
            surfaceProtectionLoop = new SurfaceProtectionLoop(
                    now, loopPhase, observation.health());
            surfaceProtectionLoopLatched = false;
        } else if (surfaceProtectionLoop.lastPhase != loopPhase) {
            surfaceProtectionLoop.transitions++;
            surfaceProtectionLoop.lastPhase = loopPhase;
        }
        surfaceProtectionLoop.lastObservedAt = now;
        surfaceProtectionLoop.minimumHealth = Math.min(
                surfaceProtectionLoop.minimumHealth, observation.health());
        double healthLoss = surfaceProtectionLoop.initialHealth
                - surfaceProtectionLoop.minimumHealth;
        if (!surfaceProtectionLoopLatched
                && surfaceProtectionLoop.transitions
                        >= settings.minimumSurfaceProtectionTransitions()
                && healthLoss >= settings.minimumSurfaceProtectionHealthLoss()) {
            surfaceProtectionLoopLatched = true;
            violations.add(new Violation(
                    ViolationType.SURFACE_PROTECTION_LOOP,
                    now,
                    observation.clientTick(),
                    observation.missionId(),
                    surfaceProtectionLoop.transitions
                            + " SURFACE_FOR_AIR/protection hand-offs in "
                            + (now - surfaceProtectionLoop.startedAt)
                            + "ms with a live threat; health "
                            + String.format(Locale.ROOT, "%.1f", surfaceProtectionLoop.initialHealth)
                            + " -> "
                            + String.format(Locale.ROOT, "%.1f", surfaceProtectionLoop.minimumHealth)));
        }
    }

    private long protectionRetreatStallDeadline(double maximumDisplacement) {
        double movingThreshold = settings.protectionRetreatMinimumPlanarEscape() * 2.0;
        if (maximumDisplacement < movingThreshold) {
            return settings.protectionRetreatStallMillis();
        }
        return Math.max(
                settings.protectionRetreatStallMillis(),
                MOVING_RETREAT_LOOP_CONFIRMATION_MILLIS);
    }

    private static ControlPhase controlPhase(Observation observation) {
        String layer = observation.controlLayer().toLowerCase(Locale.ROOT);
        String action = observation.controlAction().toUpperCase(Locale.ROOT);
        if (layer.equals("survival") && action.equals("SURFACE_FOR_AIR")) {
            return ControlPhase.SURFACE_FOR_AIR;
        }
        if (layer.equals("protection")) {
            return action.equals("RETREAT")
                    ? ControlPhase.PROTECTION_RETREAT : ControlPhase.PROTECTION;
        }
        return ControlPhase.OTHER;
    }

    private void clearProtectionLoops() {
        protectionRetreat = null;
        protectionRetreatStallLatched = false;
        surfaceProtectionLoop = null;
        surfaceProtectionLoopLatched = false;
    }

    private void resetMissionState() {
        supportCycles.clear();
        mineRestartTimes.clear();
        mineKey = null;
        mineGeneration = UNSET;
        mineChurnLatched = false;
        verticalDimension = null;
        verticalReversalTimes.clear();
        lastVerticalSampleAt = UNSET;
        verticalConfirmationStartedAt = UNSET;
        verticalOscillationLatched = false;
        previouslyInLava = false;
        lavaExposure = null;
        lastLavaExit = null;
        lavaStallLatched = false;
        clearProtectionLoops();
        suffocationEpisode = null;
        suffocationStallLatched = false;
    }

    private void resetAll() {
        activeMission = null;
        objectiveHighWater = UNSET;
        workHighWater = UNSET;
        resetMissionState();
    }

    private static void pruneTimes(Deque<Long> times, long earliestInclusive) {
        while (!times.isEmpty() && times.peekFirst() < earliestInclusive) times.removeFirst();
    }

    private static void trimOldest(Deque<Long> times, int maximum) {
        while (times.size() > maximum) times.removeFirst();
    }

    private static double planarDistance(double ax, double az, double bx, double bz) {
        return Math.hypot(ax - bx, az - bz);
    }

    private static double distance(Vec3 left, Vec3 right) {
        double dx = left.x() - right.x();
        double dy = left.y() - right.y();
        double dz = left.z() - right.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public enum ViolationType {
        SCAFFOLD_PLACE_BREAK,
        MINE_RESTART_CHURN,
        VERTICAL_OSCILLATION,
        LAVA_ESCAPE_STALL,
        HAZARD_REENTRY,
        PROTECTION_RETREAT_STALL,
        SURFACE_PROTECTION_LOOP,
        SUFFOCATION_ESCAPE_STALL
    }

    public enum BlockChangeKind {
        PLACED,
        BROKEN
    }

    public enum BlockPurpose {
        ROUTE_SUPPORT,
        WORKSTATION,
        OTHER
    }

    public record Vec3(double x, double y, double z) {
        public Vec3 {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("position coordinates must be finite");
            }
        }
    }

    public record BlockCoordinate(String dimension, int x, int y, int z) {
        public BlockCoordinate {
            dimension = normalize(dimension);
            if (dimension.isBlank()) throw new IllegalArgumentException("dimension is required");
        }
    }

    public record BlockChange(
            BlockCoordinate coordinate,
            BlockChangeKind kind,
            BlockPurpose purpose,
            boolean verifiedMilestone) {
        public BlockChange {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(purpose, "purpose");
        }
    }

    public record Observation(
            long monotonicMillis,
            long clientTick,
            String missionId,
            String dimension,
            Vec3 position,
            String phase,
            String planLeaf,
            String operation,
            String operationIdentity,
            long operationGeneration,
            long objectiveHighWater,
            long workHighWater,
            boolean inLava,
            boolean hazardRecovery,
            boolean hazardEscapeMilestone,
            boolean boundedVerticalRecovery,
            boolean verifiedProtectionCoverHold,
            boolean boundedProtectionReleaseWait,
            boolean verifiedProtectionCounterattackWork,
            String controlLayer,
            String controlAction,
            boolean liveThreat,
            double health,
            boolean touchingWater,
            double horizontalSpeed,
            boolean insideWall,
            String suffocationTarget,
            List<BlockChange> blockChanges) {
        public Observation {
            if (monotonicMillis < 0L) throw new IllegalArgumentException("monotonicMillis must be non-negative");
            if (clientTick < 0L) throw new IllegalArgumentException("clientTick must be non-negative");
            missionId = normalize(missionId);
            dimension = normalize(dimension);
            phase = normalize(phase);
            planLeaf = normalize(planLeaf);
            operation = normalize(operation);
            operationIdentity = normalize(operationIdentity);
            controlLayer = normalize(controlLayer);
            controlAction = normalize(controlAction);
            suffocationTarget = normalize(suffocationTarget);
            Objects.requireNonNull(position, "position");
            if (dimension.isBlank()) throw new IllegalArgumentException("dimension is required");
            if (!Double.isFinite(health) || health < 0.0) {
                throw new IllegalArgumentException("health must be finite and non-negative");
            }
            if (!Double.isFinite(horizontalSpeed) || horizontalSpeed < 0.0) {
                throw new IllegalArgumentException("horizontalSpeed must be finite and non-negative");
            }
            blockChanges = blockChanges == null ? List.of() : List.copyOf(blockChanges);
        }

        /** Compatibility constructor for observations created before suffocation telemetry. */
        public Observation(
                long monotonicMillis,
                long clientTick,
                String missionId,
                String dimension,
                Vec3 position,
                String phase,
                String planLeaf,
                String operation,
                String operationIdentity,
                long operationGeneration,
                long objectiveHighWater,
                long workHighWater,
                boolean inLava,
                boolean hazardRecovery,
                boolean hazardEscapeMilestone,
                boolean boundedVerticalRecovery,
                String controlLayer,
                String controlAction,
                boolean liveThreat,
                double health,
                boolean touchingWater,
                double horizontalSpeed,
                List<BlockChange> blockChanges) {
            this(monotonicMillis, clientTick, missionId, dimension, position,
                    phase, planLeaf, operation, operationIdentity, operationGeneration,
                    objectiveHighWater, workHighWater, inLava, hazardRecovery,
                    hazardEscapeMilestone, boundedVerticalRecovery, false, false, false,
                    controlLayer, controlAction, liveThreat, health, touchingWater, horizontalSpeed,
                    false, "", blockChanges);
        }
    }

    public record Violation(
            ViolationType type,
            long detectedAtMillis,
            long clientTick,
            String missionId,
            String evidence) {
        public Violation {
            Objects.requireNonNull(type, "type");
            missionId = normalize(missionId);
            evidence = normalize(evidence);
        }
    }

    /**
     * Bounded acknowledgement outbox for one-shot invariant incidents.
     * Detection transfers one pending incident per violation type into this
     * outbox; only successful recorder admission acknowledges it and starts the
     * per-type cooldown.  A full diagnostic queue therefore delays an incident
     * instead of consuming the detector's one-shot edge.
     */
    public static final class IncidentOutbox {
        private final long cooldownMillis;
        private final EnumMap<ViolationType, Violation> pending =
                new EnumMap<>(ViolationType.class);
        private final EnumMap<ViolationType, Long> acceptedAt =
                new EnumMap<>(ViolationType.class);

        public IncidentOutbox(long cooldownMillis) {
            if (cooldownMillis < 0L) {
                throw new IllegalArgumentException("cooldownMillis cannot be negative");
            }
            this.cooldownMillis = cooldownMillis;
        }

        /** Retains the first undelivered incident of a type unless it is in accepted cooldown. */
        public boolean retain(Violation violation, long nowMillis) {
            Objects.requireNonNull(violation, "violation");
            Long previous = acceptedAt.get(violation.type());
            if (previous != null && nowMillis >= previous
                    && nowMillis - previous < cooldownMillis) {
                return false;
            }
            if (previous != null && nowMillis < previous) {
                acceptedAt.remove(violation.type());
            }
            if (pending.containsKey(violation.type())) return false;
            pending.put(violation.type(), violation);
            return true;
        }

        public List<Violation> pending() {
            return List.copyOf(pending.values());
        }

        /** Acknowledges only the exact retained incident after recorder admission succeeds. */
        public boolean acknowledge(Violation violation, long nowMillis) {
            Objects.requireNonNull(violation, "violation");
            if (!pending.remove(violation.type(), violation)) return false;
            acceptedAt.put(violation.type(), nowMillis);
            return true;
        }

        /** A new world session receives a fresh cooldown while retaining undelivered evidence. */
        public void resetCooldowns() {
            acceptedAt.clear();
        }

        public int pendingCount() {
            return pending.size();
        }
    }

    public record StateSnapshot(
            int trackedSupportCycles,
            int mineRestartEvents,
            int verticalReversalEvents,
            boolean lavaExposureActive,
            boolean recentLavaExitRetained,
            boolean protectionRetreatActive,
            int surfaceProtectionTransitions,
            boolean suffocationEscapeActive) {
    }

    public record Settings(
            long scaffoldWindowMillis,
            double scaffoldMinimumRise,
            double scaffoldReturnTolerance,
            int maxTrackedSupports,
            long mineRestartWindowMillis,
            int maxMineRestarts,
            int maxMineRestartEvents,
            long verticalWindowMillis,
            int maxVerticalReversals,
            double verticalMinimumStep,
            double verticalColumnRadius,
            int maxVerticalEvents,
            long lavaStallMillis,
            double lavaMinimumPlanarEscape,
            long lavaReentryWindowMillis,
            double lavaReentryRadius,
            long protectionRetreatStallMillis,
            double protectionRetreatMinimumPlanarEscape,
            long protectionRetreatContinuityMillis,
            long surfaceProtectionWindowMillis,
            int minimumSurfaceProtectionTransitions,
            double minimumSurfaceProtectionHealthLoss,
            long suffocationStallMillis,
            double suffocationMinimumEscapeDistance) {
        public Settings {
            if (scaffoldWindowMillis <= 0L
                    || scaffoldMinimumRise <= 0.0
                    || scaffoldReturnTolerance < 0.0
                    || maxTrackedSupports <= 0
                    || mineRestartWindowMillis <= 0L
                    || maxMineRestarts <= 0
                    || maxMineRestartEvents <= maxMineRestarts
                    || verticalWindowMillis <= 0L
                    || maxVerticalReversals <= 0
                    || verticalMinimumStep <= 0.0
                    || verticalColumnRadius <= 0.0
                    || maxVerticalEvents < maxVerticalReversals
                    || lavaStallMillis <= 0L
                    || lavaMinimumPlanarEscape <= 0.0
                    || lavaReentryWindowMillis <= 0L
                    || lavaReentryRadius <= 0.0
                    || protectionRetreatStallMillis <= 0L
                    || protectionRetreatMinimumPlanarEscape <= 0.0
                    || protectionRetreatContinuityMillis <= 0L
                    || surfaceProtectionWindowMillis <= 0L
                    || minimumSurfaceProtectionTransitions <= 0
                    || minimumSurfaceProtectionHealthLoss <= 0.0
                    || suffocationStallMillis <= 0L
                    || suffocationMinimumEscapeDistance <= 0.0) {
                throw new IllegalArgumentException("behavior invariant settings must be positive and bounded");
            }
        }

        /** Compatibility constructor for settings created before suffocation monitoring. */
        public Settings(
                long scaffoldWindowMillis,
                double scaffoldMinimumRise,
                double scaffoldReturnTolerance,
                int maxTrackedSupports,
                long mineRestartWindowMillis,
                int maxMineRestarts,
                int maxMineRestartEvents,
                long verticalWindowMillis,
                int maxVerticalReversals,
                double verticalMinimumStep,
                double verticalColumnRadius,
                int maxVerticalEvents,
                long lavaStallMillis,
                double lavaMinimumPlanarEscape,
                long lavaReentryWindowMillis,
                double lavaReentryRadius,
                long protectionRetreatStallMillis,
                double protectionRetreatMinimumPlanarEscape,
                long protectionRetreatContinuityMillis,
                long surfaceProtectionWindowMillis,
                int minimumSurfaceProtectionTransitions,
                double minimumSurfaceProtectionHealthLoss) {
            this(scaffoldWindowMillis, scaffoldMinimumRise, scaffoldReturnTolerance,
                    maxTrackedSupports, mineRestartWindowMillis, maxMineRestarts,
                    maxMineRestartEvents, verticalWindowMillis, maxVerticalReversals,
                    verticalMinimumStep, verticalColumnRadius, maxVerticalEvents,
                    lavaStallMillis, lavaMinimumPlanarEscape, lavaReentryWindowMillis,
                    lavaReentryRadius, protectionRetreatStallMillis,
                    protectionRetreatMinimumPlanarEscape, protectionRetreatContinuityMillis,
                    surfaceProtectionWindowMillis, minimumSurfaceProtectionTransitions,
                    minimumSurfaceProtectionHealthLoss, 1_200L, 0.20);
        }

        public static Settings defaults() {
            return new Settings(
                    5_000L,
                    0.50,
                    0.30,
                    64,
                    10_000L,
                    3,
                    32,
                    8_000L,
                    3,
                    0.45,
                    0.75,
                    32,
                    1_500L,
                    0.75,
                    60_000L,
                    8.0,
                    // DirectBody's route owner gets 1.5s to reject one blocked
                    // segment and choose another. The invariant must observe
                    // that recovery outcome, not terminate it mid-failover.
                    2_000L,
                    0.50,
                    2_000L,
                    45_000L,
                    2,
                    2.0,
                    1_200L,
                    0.20);
        }
    }

    private enum ControlPhase {
        OTHER,
        PROTECTION,
        PROTECTION_RETREAT,
        SURFACE_FOR_AIR
    }

    private static final class ProtectionRetreatEpisode {
        private final String dimension;
        private final double initialHealth;
        private final NovelPlanarProgressWindow progress;
        private long lastObservedAt;
        private double minimumHealth;

        private ProtectionRetreatEpisode(
                long startedAt,
                String dimension,
                Vec3 origin,
                double initialHealth) {
            this.dimension = dimension;
            this.initialHealth = initialHealth;
            this.progress = new NovelPlanarProgressWindow(startedAt, origin);
            this.lastObservedAt = startedAt;
            this.minimumHealth = initialHealth;
        }
    }

    private static final class SurfaceProtectionLoop {
        private final long startedAt;
        private final double initialHealth;
        private long lastObservedAt;
        private ControlPhase lastPhase;
        private int transitions;
        private double minimumHealth;

        private SurfaceProtectionLoop(
                long startedAt,
                ControlPhase firstPhase,
                double initialHealth) {
            this.startedAt = startedAt;
            this.lastObservedAt = startedAt;
            this.lastPhase = firstPhase;
            this.initialHealth = initialHealth;
            this.minimumHealth = initialHealth;
        }
    }

    private static final class SuffocationEpisode {
        private final String dimension;
        private final ProgressWindow progress;

        private SuffocationEpisode(long startedAt, String dimension, Vec3 origin) {
            this.dimension = dimension;
            this.progress = new ProgressWindow(startedAt, origin);
        }
    }

    private static final class SupportCycle {
        private final BlockCoordinate coordinate;
        private final long placedAt;
        private final double startBodyY;
        private double maxBodyY;
        private long brokenAt = UNSET;

        private SupportCycle(
                BlockCoordinate coordinate,
                long placedAt,
                double startBodyY,
                double maxBodyY) {
            this.coordinate = coordinate;
            this.placedAt = placedAt;
            this.startBodyY = startBodyY;
            this.maxBodyY = maxBodyY;
        }
    }

    private static final class LavaExposure {
        private final String dimension;
        private final ProgressWindow progress;

        private LavaExposure(long enteredAt, String dimension, Vec3 origin) {
            this.dimension = dimension;
            this.progress = new ProgressWindow(enteredAt, origin);
        }
    }

    /**
     * Monotonic spatial envelope: only expansion beyond the episode high-water opens a fresh
     * deadline. Returning between two already-visited points cannot manufacture progress, while
     * an early real escape move no longer suppresses a later stall forever.
     */
    private static final class ProgressWindow {
        private long checkpointAt;
        private final Vec3 origin;
        private double progressHighWater;
        private double maximumDisplacement;

        private ProgressWindow(long checkpointAt, Vec3 origin) {
            this.checkpointAt = checkpointAt;
            this.origin = origin;
        }

        private void observe(
                long now,
                Vec3 position,
                double minimumProgress,
                boolean planar) {
            double displacement = planar
                    ? planarDistance(
                            origin.x(), origin.z(), position.x(), position.z())
                    : distance(origin, position);
            double expansion = Math.max(0.0, displacement - progressHighWater);
            maximumDisplacement = Math.max(maximumDisplacement, expansion);
            if (expansion >= minimumProgress) {
                checkpointAt = now;
                progressHighWater = displacement;
                maximumDisplacement = 0.0;
            }
        }

        private long stalledFor(long now) {
            return Math.max(0L, now - checkpointAt);
        }

    }

    /**
     * Planar progress which accepts a real turn but not a return through the
     * trail already covered by this retreat episode.
     */
    private static final class NovelPlanarProgressWindow {
        private long checkpointAt;
        private final PlanarProgressGeometry geometry;
        private double maximumDisplacement;

        private NovelPlanarProgressWindow(long checkpointAt, Vec3 origin) {
            this.checkpointAt = checkpointAt;
            this.geometry = new PlanarProgressGeometry(origin.x(), origin.z());
        }

        private void observe(
                long now,
                Vec3 position,
                double minimumProgress) {
            maximumDisplacement = Math.max(
                    maximumDisplacement,
                    geometry.displacementFromCheckpoint(position.x(), position.z()));
            if (geometry.advanceIfNovel(position.x(), position.z(), minimumProgress)) {
                checkpointAt = now;
                maximumDisplacement = 0.0;
            }
        }

        private long stalledFor(long now) {
            return Math.max(0L, now - checkpointAt);
        }

        private void pauseStallClock(long now) {
            checkpointAt = now;
        }
    }

    private record LavaExit(long exitedAt, String dimension, Vec3 position) {
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
