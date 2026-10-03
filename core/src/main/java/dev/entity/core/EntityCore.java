package dev.entity.core;

import dev.entity.core.control.BodyArbiter;
import dev.entity.core.control.BodyChannel;
import dev.entity.core.control.ControlLease;
import dev.entity.core.control.ControlPriority;
import dev.entity.core.model.Mission;
import dev.entity.core.model.MissionState;
import dev.entity.core.persistence.MissionStore;
import dev.entity.core.progress.ProgressMonitor;
import dev.entity.core.progress.BlockedReconciliationPolicy;
import dev.entity.core.progress.RetryPolicy;
import dev.entity.core.protection.ProtectionPolicy;
import dev.entity.core.protection.ProtectionPolicy.ActivityContext;
import dev.entity.core.protection.ProtectionPolicy.ProtectionDirective;
import dev.entity.core.survival.SafetyDirective;
import dev.entity.core.survival.IdleFlotationLatch;
import dev.entity.core.survival.SurvivalAction;
import dev.entity.core.survival.SurvivalSupervisor;
import dev.entity.core.survival.WorldSnapshot;
import dev.entity.core.trace.DecisionTrace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic brain shared by the Fabric adapter, command bridge, and tests.
 * Minecraft actions are outputs; durable mission truth lives here.
 */
public final class EntityCore {
    // A lease is still invalidated immediately by a higher-priority owner or
    // emergency epoch change. This longer expiry is only scheduling headroom:
    // large client chunk/entity scans can occasionally make one render tick
    // exceed a second, and must not turn a valid mission into a stale-lease
    // exception between core.tick() and the platform adapter using the lease.
    private static final long LEASE_TTL_MILLIS = 10_000;
    private static final Set<BodyChannel> MISSION_CHANNELS = EnumSet.allOf(BodyChannel.class);

    private final MissionStore store;
    private final LinkedHashMap<String, Mission> missions = new LinkedHashMap<>();
    private final Map<String, String> commandIndex = new LinkedHashMap<>();
    private final BodyArbiter arbiter;
    private final SurvivalSupervisor survival;
    private final IdleFlotationLatch idleFlotation = new IdleFlotationLatch();
    private final ProtectionPolicy protection;
    private final ProgressMonitor progress;
    private final RetryPolicy retryPolicy;
    private final BlockedReconciliationPolicy blockedReconciliation = new BlockedReconciliationPolicy();
    private final DecisionTrace trace;

    private String activeMissionId;
    private ControlLease activeLease;
    /** Retained safe/terminal mutation which must reach MissionStore before mission execution. */
    private boolean persistenceDirty;
    private String lastDecisionKey = "";
    private TickDecision currentDecision;

    public EntityCore(MissionStore store, ProtectionPolicy.Settings protectionSettings) throws IOException {
        this(
                store,
                protectionSettings,
                new BodyArbiter(),
                new SurvivalSupervisor(),
                new ProgressMonitor(),
                new RetryPolicy(),
                new DecisionTrace(256));
    }

    public EntityCore(
            MissionStore store,
            ProtectionPolicy.Settings protectionSettings,
            SurvivalSupervisor survival) throws IOException {
        this(
                store,
                protectionSettings,
                new BodyArbiter(),
                survival,
                new ProgressMonitor(),
                new RetryPolicy(),
                new DecisionTrace(256));
    }

    public EntityCore(
            MissionStore store,
            ProtectionPolicy.Settings protectionSettings,
            BodyArbiter arbiter,
            SurvivalSupervisor survival,
            ProgressMonitor progress,
            RetryPolicy retryPolicy,
            DecisionTrace trace) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        this.protection = new ProtectionPolicy(Objects.requireNonNull(protectionSettings, "protectionSettings"));
        this.arbiter = Objects.requireNonNull(arbiter, "arbiter");
        this.survival = Objects.requireNonNull(survival, "survival");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.trace = Objects.requireNonNull(trace, "trace");
        restore(store.load());
    }

    public synchronized Submission submit(Command command, long nowMillis) throws IOException {
        Objects.requireNonNull(command, "command");
        command.validate();
        String existingId = commandIndex.get(command.commandId());
        if (existingId != null) {
            Mission existing = missions.get(existingId);
            trace.add(
                    nowMillis, "command", "duplicate ignored",
                    "command id was already accepted", existingId,
                    Map.of("commandId", command.commandId()));
            return new Submission(existing.copy(), true);
        }

        String missionId = missionIdForCommand(command.commandId());
        Mission mission = new Mission(
                missionId,
                command.commandId(),
                command.issuedBy(),
                command.kind(),
                command.arguments(),
                nowMillis);

        String priorActiveMissionId = activeMissionId;
        Mission superseded = selectedMissionForSupersession();
        Mission supersededBefore = null;
        long invalidatedEpoch = -1L;
        if (superseded != null && superseded.state() != MissionState.PAUSED_BY_OWNER
                && superseded.state() != MissionState.BLOCKED) {
            supersededBefore = superseded.copy();
            superseded.pauseByOwner("Superseded by newer owner mission", nowMillis);
            progress.clear(superseded.id());
            invalidatedEpoch = arbiter.invalidateAll();
            activeLease = null;
        }
        missions.put(mission.id(), mission);
        commandIndex.put(mission.commandId(), mission.id());
        activeMissionId = mission.id();
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            missions.remove(mission.id());
            commandIndex.remove(mission.commandId(), mission.id());
            if (supersededBefore != null) {
                missions.put(supersededBefore.id(), supersededBefore);
            }
            activeMissionId = priorActiveMissionId;
            throw failure;
        }
        if (supersededBefore != null) {
            trace.add(
                    nowMillis, "mission", "mission superseded",
                    "paused for newer owner mission", superseded.id(),
                    Map.of("epoch", Long.toString(invalidatedEpoch)));
        }
        trace.add(
                nowMillis, "command", "mission accepted",
                "durably queued " + mission.kind(), mission.id(),
                Map.of("commandId", mission.commandId()));
        return new Submission(mission.copy(), false);
    }

    /** One deterministic arbitration cycle: survival, protection, mission, idle. */
    public synchronized TickDecision tick(WorldSnapshot world) throws IOException {
        Objects.requireNonNull(world, "world");
        flushPersistenceFence();
        ActivityContext inferred = missionOccupiesExecution(
                selectActiveMission(), world.nowMillis())
                ? ActivityContext.TRAVEL
                : ActivityContext.IDLE;
        return tick(world, inferred);
    }

    /**
     * Activity-aware arbitration cycle. Callers that know a mission is doing
     * stationary GUI/block work can prevent an ordinary attacker from being
     * mistaken for a travel obstacle.
     */
    public synchronized TickDecision tick(
            WorldSnapshot world,
            ActivityContext activityContext) throws IOException {
        return tick(world, activityContext, false);
    }

    /**
     * Includes a body-owning execution path that is intentionally outside the
     * direct mission journal, such as Home economy's retained return route.
     * Parked owner-paused history remains visible but cannot prevent an admitted
     * external owner from executing. Survival/protection and runnable missions
     * still take precedence; critical-air survival is always evaluated.
     */
    public synchronized TickDecision tick(
            WorldSnapshot world,
            ActivityContext activityContext,
            boolean externalBodyExecutionActive) throws IOException {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(activityContext, "activityContext");
        flushPersistenceFence();
        long now = world.nowMillis();
        Mission mission = selectActiveMission();

        SafetyDirective safety = survival.evaluate(world);
        boolean idleFlotationActive = idleFlotation.update(
                missionOccupiesExecution(mission, now) || externalBodyExecutionActive,
                world.inWater(), world.headSubmerged(), now);
        if (!safety.active() && idleFlotationActive) {
            safety = new SafetyDirective(
                    SurvivalAction.SURFACE_FOR_AIR,
                    ControlPriority.SURVIVAL_DROWNING,
                    "idle in water; stay afloat instead of sinking",
                    EnumSet.allOf(BodyChannel.class));
        }
        Optional<ProtectionDirective> protectionDecision = protection.evaluate(world, activityContext);
        // Ordinary hunger must not make Entity put its weapon away in front of an
        // attacker. Critical recovery is different: the retained natural-world
        // failure reached 8 -> 5 -> 2 health while eight cooked meals remained
        // carried because this arbitration unconditionally discarded RECOVER_HEALTH.
        // Grant exactly one useful eating window while hunger can still accept a
        // meal. Once food is full enough, protection immediately regains the body
        // and retreats while vanilla regeneration runs.
        double healthFraction = world.health() / world.maximumHealth();
        boolean criticalRecoveryNeedsEating = safety.active()
                && safety.action() == SurvivalAction.RECOVER_HEALTH
                && healthFraction <= SurvivalSupervisor.HEALTH_RECOVERY_ENTER_FRACTION
                && world.consumableFoodAvailable()
                && world.foodLevel() < 19;
        boolean protectionPreemptsEating = safety.active()
                && safety.action() == SurvivalAction.RECOVER_HEALTH
                && protectionDecision.isPresent()
                && !criticalRecoveryNeedsEating;
        boolean protectionPreemptsIdleFlotation = safety.active()
                && safety.action() == SurvivalAction.SURFACE_FOR_AIR
                && safety.reason().startsWith("idle in water")
                && protectionDecision.isPresent();
        if (safety.active() && !protectionPreemptsEating && !protectionPreemptsIdleFlotation) {
            boolean changed = pauseForSafety(mission, safety.reason(), now);
            ControlLease lease = requireLease(
                    "survival:" + safety.action(), safety.priority(), safety.channels(), now);
            if (changed) {
                persistRetainingMutationOnFailure();
            }
            traceDecisionOnce(
                    "survival:" + safety.action(), now, "survival", safety.action().name(),
                    safety.reason(), mission, Map.of("priority", Integer.toString(safety.priority())));
            return remember(new TickDecision(
                    Layer.SURVIVAL,
                    safety.action().name(),
                    safety.reason(),
                    copyOrNull(mission),
                    Optional.of(lease),
                    arbiter.epoch(),
                    safety.action() == SurvivalAction.RECOVER_HEALTH
                            ? protectionDecision.map(ProtectionDirective::threat).orElse(null)
                            : null));
        }
        releaseLeaseOwnedByPrefix("survival:");

        if (protectionDecision.isPresent()) {
            ProtectionDirective directive = protectionDecision.get();
            boolean changed = pauseForProtection(mission, directive.reason(), now);
            ControlLease lease = requireLease("protection", directive.priority(), directive.channels(), now);
            if (changed) {
                persistRetainingMutationOnFailure();
            }
            traceDecisionOnce(
                    "protection:" + directive.action() + ':' + directive.threat().entityId(),
                    now, "protection", directive.action().name(),
                    directive.reason(), mission,
                    Map.of("threat", directive.threat().entityId()));
            return remember(new TickDecision(
                    Layer.PROTECTION,
                    directive.action().name(),
                    directive.reason(),
                    copyOrNull(mission),
                    Optional.of(lease),
                    arbiter.epoch(),
                    directive.threat()));
        }
        releaseLeaseOwnedByPrefix("protection");

        mission = selectActiveMission();
        if (mission == null) {
            releaseActiveLease();
            traceDecisionOnce("idle", now, "mission", "IDLE", "no active mission", null, Map.of());
            return remember(new TickDecision(
                    Layer.IDLE, "IDLE", "no active mission", null, Optional.empty(), arbiter.epoch()));
        }
        if (externalBodyExecutionActive && mission.state() == MissionState.PAUSED_BY_OWNER) {
            releaseActiveLease();
            String reason = "external work owns execution; saved mission remains paused";
            traceDecisionOnce("external-with-parked:" + mission.id(), now,
                    "mission", "IDLE", reason, mission, Map.of());
            return remember(new TickDecision(
                    Layer.IDLE, "IDLE", reason, null, Optional.empty(), arbiter.epoch()));
        }
        if (mission.state() == MissionState.RETRY_WAIT && now < mission.nextRetryAtMillis()) {
            releaseActiveLease();
            String reason = "waiting for retry at " + mission.nextRetryAtMillis();
            traceDecisionOnce("retry-wait:" + mission.id(), now, "mission", "RETRY_WAIT", reason, mission, Map.of());
            return remember(new TickDecision(
                    Layer.WAITING, "RETRY_WAIT", reason, mission.copy(), Optional.empty(), arbiter.epoch()));
        }
        if (mission.state() == MissionState.BLOCKED) {
            if (mission.nextRetryAtMillis() > 0 && now >= mission.nextRetryAtMillis()) {
                String blockedReason = mission.lastError();
                Mission before = mission.copy();
                String priorActiveMissionId = activeMissionId;
                mission.start(now);
                try {
                    persist();
                } catch (IOException | RuntimeException failure) {
                    missions.put(mission.id(), before);
                    activeMissionId = priorActiveMissionId;
                    throw failure;
                }
                trace.add(
                        now, "recovery", "blocked mission reconciliation",
                        "one automatic reconciliation attempt after bounded backoff",
                        mission.id(), Map.of(
                                "blockedReason", blockedReason,
                                "attempt", Integer.toString(mission.transientFailures())));
            } else {
                releaseActiveLease();
                String reason = mission.nextRetryAtMillis() > 0
                        ? mission.lastError() + "; automatic reconciliation at " + mission.nextRetryAtMillis()
                        : mission.lastError();
                traceDecisionOnce(
                        "blocked:" + mission.id() + ':' + mission.nextRetryAtMillis(),
                        now, "mission", "BLOCKED", reason, mission, Map.of());
                return remember(new TickDecision(
                        Layer.WAITING, "BLOCKED", reason,
                        mission.copy(), Optional.empty(), arbiter.epoch()));
            }
        }
        if (mission.state() == MissionState.PAUSED_BY_OWNER) {
            releaseActiveLease();
            String reason = mission.pauseReason().isBlank() ? "paused by owner" : mission.pauseReason();
            traceDecisionOnce(
                    "owner-pause:" + mission.id(), now, "mission", "PAUSED", reason, mission, Map.of());
            return remember(new TickDecision(
                    Layer.WAITING, "PAUSED", reason, mission.copy(), Optional.empty(), arbiter.epoch()));
        }

        boolean resumed = mission.state() != MissionState.RUNNING;
        if (resumed) {
            MissionState oldState = mission.state();
            Mission before = mission.copy();
            String priorActiveMissionId = activeMissionId;
            mission.start(now);
            try {
                persist();
            } catch (IOException | RuntimeException failure) {
                missions.put(mission.id(), before);
                activeMissionId = priorActiveMissionId;
                throw failure;
            }
            trace.add(
                    now, "mission", "mission resumed",
                    "resumed from " + oldState, mission.id(), Map.of());
        }
        ControlLease lease = requireLease("mission:" + mission.id(), ControlPriority.MISSION, MISSION_CHANNELS, now);
        traceDecisionOnce(
                "mission:" + mission.id(), now, "mission", "EXECUTE", mission.kind(), mission,
                Map.of("phase", mission.phase()));
        return remember(new TickDecision(
                Layer.MISSION,
                "EXECUTE",
                mission.kind(),
                mission.copy(),
                Optional.of(lease),
                arbiter.epoch()));
    }

    public synchronized void reportTransientFailure(String missionId, String error, long nowMillis) throws IOException {
        Mission mission = requireMission(missionId);
        if (mission.state().terminal()) {
            return;
        }
        int nextFailure = mission.transientFailures() + 1;
        long retryAt = Math.addExact(nowMillis, retryPolicy.delayForFailure(nextFailure));
        mission.retryLater(error, retryAt, nowMillis);
        progress.clear(missionId);
        long invalidatedEpoch = arbiter.epoch();
        if (mission.id().equals(activeMissionId)) {
            invalidatedEpoch = arbiter.invalidateAll();
            activeLease = null;
        }
        persistRetainingMutationOnFailure();
        trace.add(
                nowMillis, "recovery", "replan scheduled",
                Objects.requireNonNullElse(error, "transient failure"), mission.id(),
                Map.of(
                        "attempt", Integer.toString(nextFailure),
                        "retryAt", Long.toString(retryAt),
                        "epoch", Long.toString(invalidatedEpoch)));
    }

    /** Rejects late callbacks from a path/combat action that has already been preempted. */
    public synchronized boolean reportActionFailure(
            String missionId,
            ControlLease actionLease,
            String error,
            long nowMillis) throws IOException {
        if (!isCurrentMissionLease(missionId, actionLease, nowMillis)) {
            trace.add(
                    nowMillis, "control", "stale failure ignored",
                    Objects.requireNonNullElse(error, "late adapter failure"), missionId,
                    Map.of("callbackEpoch", actionLease == null ? "none" : Long.toString(actionLease.epoch())));
            return false;
        }
        reportTransientFailure(missionId, error, nowMillis);
        return true;
    }

    /** Completes only if the callback still owns the live mission controls. */
    public synchronized boolean completeAction(
            String missionId,
            ControlLease actionLease,
            long nowMillis) throws IOException {
        if (!isCurrentMissionLease(missionId, actionLease, nowMillis)) {
            trace.add(
                    nowMillis, "control", "stale completion ignored",
                    "adapter callback no longer owns controls", missionId,
                    Map.of("callbackEpoch", actionLease == null ? "none" : Long.toString(actionLease.epoch())));
            return false;
        }
        complete(missionId, nowMillis);
        return true;
    }

    public synchronized ProgressMonitor.Result reportProgress(
            String missionId,
            ProgressMonitor.Observation observation) throws IOException {
        Mission mission = requireMission(missionId);
        ProgressMonitor.Result result = progress.observe(missionId, observation);
        if (result.status() == ProgressMonitor.Status.STUCK && !mission.state().terminal()) {
            reportTransientFailure(missionId, "stuck: " + result.reason(), observation.nowMillis());
        }
        return result;
    }

    /** For intentional waits: the next active observation starts a fresh watchdog window. */
    public synchronized void resetProgressBaseline(String missionId) {
        if (missions.containsKey(missionId)) {
            progress.clear(missionId);
        }
    }

    public synchronized void complete(String missionId, long nowMillis) throws IOException {
        Mission mission = requireMission(missionId);
        if (mission.state().terminal()) {
            return;
        }
        mission.complete(nowMillis);
        progress.clear(missionId);
        if (mission.id().equals(activeMissionId)) {
            releaseActiveLease();
            activeMissionId = null;
        }
        // Completion can follow an external effect. Do not roll it back to RUNNING on a rejected
        // save: retain the terminal snapshot and fence all later mission ticks until it is durable.
        persistRetainingMutationOnFailure();
        trace.add(nowMillis, "mission", "mission complete", mission.kind(), mission.id(), Map.of());
    }

    public synchronized void pause(String missionId, String reason, long nowMillis) throws IOException {
        Mission mission = requireMission(missionId);
        if (mission.state().terminal() || mission.state() == MissionState.PAUSED_BY_OWNER) {
            return;
        }
        Mission before = mission.copy();
        String priorActiveMissionId = activeMissionId;
        mission.pauseByOwner(reason, nowMillis);
        if (mission.id().equals(activeMissionId)) {
            releaseActiveLease();
        }
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            missions.put(mission.id(), before);
            activeMissionId = priorActiveMissionId;
            throw failure;
        }
        trace.add(nowMillis, "mission", "mission paused", mission.pauseReason(), mission.id(), Map.of());
    }

    public synchronized void resume(String missionId, long nowMillis) throws IOException {
        Mission mission = requireMission(missionId);
        if (mission.state() != MissionState.PAUSED_BY_OWNER && mission.state() != MissionState.BLOCKED) {
            return;
        }
        Mission before = mission.copy();
        String priorActiveMissionId = activeMissionId;
        mission.start(nowMillis);
        activeMissionId = mission.id();
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            missions.put(mission.id(), before);
            activeMissionId = priorActiveMissionId;
            throw failure;
        }
        trace.add(nowMillis, "mission", "mission resumed by owner", mission.kind(), mission.id(), Map.of());
    }

    /**
     * Selects the exact runnable mission named by the durable server journal.
     * This is intentionally separate from resume: a queued/running mission may
     * already have the correct state while another runnable mission currently
     * owns the body.
     */
    public synchronized boolean focus(String missionId, long nowMillis) {
        Mission mission = requireMission(missionId);
        if (!selectableForExecution(mission)) {
            return false;
        }
        if (mission.id().equals(activeMissionId)) {
            return true;
        }
        long newEpoch = arbiter.invalidateAll();
        activeLease = null;
        activeMissionId = mission.id();
        progress.clear(mission.id());
        trace.add(
                nowMillis, "mission", "server selected mission",
                "focused the exact mission from durable synchronization", mission.id(),
                Map.of("epoch", Long.toString(newEpoch)));
        return true;
    }

    /**
     * Explicit operator retry. Unlike automatic retry, this deliberately skips
     * backoff and may select a different queued mission as the active one.
     */
    public synchronized boolean retryNow(String missionId, long nowMillis) throws IOException {
        Mission mission = requireMission(missionId);
        boolean retryable = switch (mission.state()) {
            case BLOCKED, RETRY_WAIT, PAUSED_BY_OWNER, PAUSED_BY_SAFETY, PAUSED_BY_PROTECTION -> true;
            default -> false;
        };
        if (!retryable) {
            return false;
        }
        Mission before = mission.copy();
        String priorActiveMissionId = activeMissionId;
        long newEpoch = arbiter.invalidateAll();
        activeLease = null;
        mission.start(nowMillis);
        activeMissionId = mission.id();
        progress.clear(mission.id());
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            missions.put(mission.id(), before);
            activeMissionId = priorActiveMissionId;
            throw failure;
        }
        trace.add(
                nowMillis, "mission", "mission retry forced",
                "operator skipped retry wait and requested an immediate replan", mission.id(),
                Map.of("epoch", Long.toString(newEpoch)));
        return true;
    }

    /**
     * Resumes one mission after the persisted death/respawn safety boundary.
     * This is deliberately narrower than {@link #retryNow}: an automatic
     * recovery may never skip a BLOCKED/RETRY_WAIT decision or masquerade as
     * an operator-forced retry in the audit trace.
     */
    public synchronized boolean resumeAfterRespawn(
            String missionId,
            String reason,
            long nowMillis) throws IOException {
        Mission mission = requireMission(missionId);
        if (mission.state() != MissionState.PAUSED_BY_SAFETY
                || !mission.pauseReason().startsWith("death interrupted mission:")) {
            return false;
        }
        Mission before = mission.copy();
        String priorActiveMissionId = activeMissionId;
        long newEpoch = arbiter.invalidateAll();
        activeLease = null;
        mission.start(nowMillis);
        activeMissionId = mission.id();
        progress.clear(mission.id());
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            missions.put(mission.id(), before);
            activeMissionId = priorActiveMissionId;
            throw failure;
        }
        trace.add(
                nowMillis, "recovery", "mission resumed after respawn",
                Objects.requireNonNullElse(reason, "respawn recovery completed"),
                mission.id(), Map.of(
                        "epoch", Long.toString(newEpoch),
                        "interruptions", Integer.toString(mission.interruptions())));
        return true;
    }

    public synchronized void cancel(String missionId, String reason, long nowMillis) throws IOException {
        Mission mission = requireMission(missionId);
        if (mission.state().terminal()) {
            return;
        }
        Mission before = mission.copy();
        String priorActiveMissionId = activeMissionId;
        mission.cancel(reason, nowMillis);
        progress.clear(missionId);
        if (mission.id().equals(activeMissionId)) {
            releaseActiveLease();
            activeMissionId = null;
        }
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            missions.put(mission.id(), before);
            activeMissionId = priorActiveMissionId;
            throw failure;
        }
        trace.add(nowMillis, "mission", "mission cancelled", reason, mission.id(), Map.of());
    }

    public synchronized void block(String missionId, String reason, long nowMillis) throws IOException {
        Mission mission = requireMission(missionId);
        if (mission.state().terminal()) {
            return;
        }
        Mission before = mission.copy();
        String priorActiveMissionId = activeMissionId;
        mission.block(reason, nowMillis);
        if (mission.id().equals(activeMissionId)) {
            releaseActiveLease();
        }
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            missions.put(mission.id(), before);
            activeMissionId = priorActiveMissionId;
            throw failure;
        }
        trace.add(nowMillis, "mission", "mission blocked", reason, mission.id(), Map.of());
    }

    /**
     * Blocks honestly, but schedules one later execution attempt because the
     * missing fact can be repaired outside this process. If that attempt sees
     * the same block again, its caller schedules the next attempt with
     * exponential backoff capped at one minute.
     */
    public synchronized void blockForReconciliation(
            String missionId,
            String reason,
            long nowMillis) throws IOException {
        Mission mission = requireMission(missionId);
        if (mission.state().terminal()) return;
        int nextAttempt = mission.transientFailures() + 1;
        long delay = blockedReconciliation.delayForAttempt(nextAttempt);
        long retryAt;
        try {
            retryAt = Math.addExact(nowMillis, delay);
        } catch (ArithmeticException overflow) {
            retryAt = Long.MAX_VALUE;
        }
        mission.blockForReconciliation(reason, retryAt, nowMillis);
        progress.clear(missionId);
        if (mission.id().equals(activeMissionId)) releaseActiveLease();
        persistRetainingMutationOnFailure();
        trace.add(
                nowMillis, "mission", "mission blocked pending reconciliation",
                reason, mission.id(), Map.of(
                        "attempt", Integer.toString(nextAttempt),
                        "retryAt", Long.toString(retryAt)));
    }

    public synchronized long emergencyStop(String reason, long nowMillis) {
        activeLease = null;
        long epoch = arbiter.invalidateAll();
        trace.add(nowMillis, "control", "all controls invalidated", reason, activeMissionId, Map.of(
                "epoch", Long.toString(epoch)));
        return epoch;
    }

    public synchronized long prepareForRespawn(
            String interruptedMissionId,
            String reason,
            long nowMillis) throws IOException {
        Mission interrupted = interruptedMissionId == null || interruptedMissionId.isBlank()
                ? null
                : missions.get(interruptedMissionId);
        boolean interruptionRecorded = interrupted != null && !interrupted.state().terminal();
        Mission before = interruptionRecorded ? interrupted.copy() : null;
        String priorActiveMissionId = activeMissionId;
        if (interruptionRecorded) {
            MissionState priorState = interrupted.state();
            interrupted.recordInterruption(nowMillis);
            boolean retainParkedState = priorState == MissionState.PAUSED_BY_OWNER
                    || priorState == MissionState.RETRY_WAIT
                    || priorState == MissionState.BLOCKED;
            if (!retainParkedState) {
                interrupted.pauseForSafety(reason, nowMillis);
                activeMissionId = interrupted.id();
            }
        }
        survival.reset();
        idleFlotation.reset();
        progress.clearAll();
        activeLease = null;
        long epoch = arbiter.invalidateAll();
        if (interruptionRecorded) {
            try {
                persist();
            } catch (IOException | RuntimeException failure) {
                missions.put(interrupted.id(), before);
                activeMissionId = priorActiveMissionId;
                throw failure;
            }
        }
        trace.add(
                nowMillis, "recovery", "respawn interruption retained",
                reason, interruptedMissionId, Map.of(
                        "epoch", Long.toString(epoch),
                        "interruptions", interrupted == null
                                ? "0" : Integer.toString(interrupted.interruptions())));
        return epoch;
    }

    public void updateProtection(ProtectionPolicy.Settings settings) {
        protection.update(settings);
    }

    public ProtectionPolicy.Settings protectionSettings() {
        return protection.settings();
    }

    public synchronized Optional<Mission> mission(String missionId) {
        Mission mission = missions.get(missionId);
        return mission == null ? Optional.empty() : Optional.of(mission.copy());
    }

    public synchronized List<Mission> missions() {
        return missions.values().stream().map(Mission::copy).toList();
    }

    public synchronized Optional<Mission> activeMission() {
        Mission mission = selectActiveMission();
        return mission == null ? Optional.empty() : Optional.of(mission.copy());
    }

    /** Display/resume selection is not necessarily an executing owner. Only an
     * explicitly owner-paused record may yield without changing its lifecycle;
     * queued/running/retrying, blocked and safety-paused work retain authority. */
    public synchronized boolean canYieldToExternalWork() {
        Mission mission = selectActiveMission();
        return mission == null || mission.state() == MissionState.PAUSED_BY_OWNER;
    }

    public synchronized Optional<TickDecision> currentDecision() {
        return Optional.ofNullable(currentDecision);
    }

    public BodyArbiter arbiter() {
        return arbiter;
    }

    public static String missionIdForCommand(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        return UUID.nameUUIDFromBytes(
                ("entity2-mission:" + commandId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public DecisionTrace trace() {
        return trace;
    }

    private void restore(List<Mission> restored) throws IOException {
        for (Mission mission : restored) {
            mission.validate();
            if (missions.putIfAbsent(mission.id(), mission.copy()) != null) {
                throw new IOException("Duplicate mission id in store: " + mission.id());
            }
            String prior = commandIndex.putIfAbsent(mission.commandId(), mission.id());
            if (prior != null) {
                throw new IOException("Duplicate command id in store: " + mission.commandId());
            }
        }
    }

    private Mission selectActiveMission() {
        Mission selected = activeMissionId == null ? null : missions.get(activeMissionId);
        if (selected != null && selectableForExecution(selected)) {
            return selected;
        }

        // Owner-paused and blocked missions stay durable, but must never starve a newer
        // queued/running/retrying mission. Iterate newest-first because chat commands are
        // interactive replacements, not a FIFO job queue.
        activeMissionId = null;
        List<Mission> newestFirst = new ArrayList<>(missions.values());
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            Mission candidate = newestFirst.get(index);
            if (selectableForExecution(candidate)) {
                activeMissionId = candidate.id();
                return candidate;
            }
        }


        // If everything is parked, retain the newest parked objective as the visible
        // WAITING mission so /why and explicit resume/retry still have a stable target.
        if (selected != null && !selected.state().terminal()) {
            activeMissionId = selected.id();
            return selected;
        }
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            Mission candidate = newestFirst.get(index);
            if (!candidate.state().terminal()) {
                activeMissionId = candidate.id();
                return candidate;
            }
        }
        return null;
    }

    private Mission selectedMissionForSupersession() {
        Mission selected = activeMissionId == null ? null : missions.get(activeMissionId);
        if (selected != null && !selected.state().terminal()) {
            return selected;
        }
        List<Mission> newestFirst = new ArrayList<>(missions.values());
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            Mission candidate = newestFirst.get(index);
            if (selectableForExecution(candidate)) return candidate;
        }
        return null;
    }

    private static boolean selectableForExecution(Mission mission) {
        return mission != null
                && !mission.state().terminal()
                && mission.state() != MissionState.PAUSED_BY_OWNER
                && mission.state() != MissionState.BLOCKED;
    }

    private static boolean missionOccupiesExecution(Mission mission, long nowMillis) {
        if (mission == null || mission.state().terminal()) return false;
        return switch (mission.state()) {
            case QUEUED, RUNNING, PAUSED_BY_SAFETY, PAUSED_BY_PROTECTION -> true;
            case RETRY_WAIT -> nowMillis >= mission.nextRetryAtMillis();
            case BLOCKED -> mission.nextRetryAtMillis() > 0L
                    && nowMillis >= mission.nextRetryAtMillis();
            case PAUSED_BY_OWNER, COMPLETED, CANCELLED -> false;
        };
    }

    private boolean pauseForSafety(Mission mission, String reason, long nowMillis) {
        if (mission == null || mission.state().terminal() || mission.state() == MissionState.RETRY_WAIT
                || mission.state() == MissionState.BLOCKED || mission.state() == MissionState.PAUSED_BY_OWNER
                || mission.state() == MissionState.PAUSED_BY_SAFETY) {
            return false;
        }
        mission.pauseForSafety(reason, nowMillis);
        progress.clear(mission.id());
        return true;
    }

    private boolean pauseForProtection(Mission mission, String reason, long nowMillis) {
        if (mission == null || mission.state().terminal() || mission.state() == MissionState.RETRY_WAIT
                || mission.state() == MissionState.BLOCKED || mission.state() == MissionState.PAUSED_BY_OWNER
                || mission.state() == MissionState.PAUSED_BY_PROTECTION) {
            return false;
        }
        mission.pauseForProtection(reason, nowMillis);
        progress.clear(mission.id());
        return true;
    }

    private ControlLease requireLease(
            String owner,
            int priority,
            Set<BodyChannel> channels,
            long nowMillis) {
        if (activeLease != null
                && activeLease.owner().equals(owner)
                && activeLease.priority() == priority
                && activeLease.channels().equals(channels)
                && activeLease.epoch() == arbiter.epoch()) {
            Optional<ControlLease> renewed = arbiter.renew(
                    activeLease, nowMillis, LEASE_TTL_MILLIS);
            if (renewed.isPresent()) {
                activeLease = renewed.orElseThrow();
                return activeLease;
            }
        }
        releaseActiveLease();
        activeLease = arbiter.acquire(owner, priority, channels, nowMillis, LEASE_TTL_MILLIS)
                .orElseThrow(() -> new IllegalStateException("Control denied to " + owner));
        return activeLease;
    }

    private void releaseLeaseOwnedByPrefix(String prefix) {
        if (activeLease != null && activeLease.owner().startsWith(prefix)) {
            arbiter.release(activeLease);
            activeLease = null;
        }
    }

    private void releaseActiveLease() {
        if (activeLease != null) {
            arbiter.release(activeLease);
            activeLease = null;
        }
    }

    private Mission requireMission(String missionId) {
        Mission mission = missions.get(missionId);
        if (mission == null) {
            throw new IllegalArgumentException("Unknown mission: " + missionId);
        }
        return mission;
    }

    private boolean isCurrentMissionLease(String missionId, ControlLease lease, long nowMillis) {
        return lease != null
                && lease.owner().equals("mission:" + missionId)
                && arbiter.isValid(lease, nowMillis);
    }

    private void persist() throws IOException {
        store.save(new ArrayList<>(missions.values()));
        persistenceDirty = false;
    }

    /**
     * Retains a conservative parked/terminal mutation when its save is rejected. A later tick
     * retries this exact in-memory mission set before selecting or granting mission controls.
     */
    private void persistRetainingMutationOnFailure() throws IOException {
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            persistenceDirty = true;
            throw failure;
        }
    }

    private void flushPersistenceFence() throws IOException {
        if (!persistenceDirty) return;
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            persistenceDirty = true;
            throw failure;
        }
    }

    private void traceDecisionOnce(
            String key,
            long nowMillis,
            String category,
            String decision,
            String reason,
            Mission mission,
            Map<String, String> details) {
        if (key.equals(lastDecisionKey)) {
            return;
        }
        lastDecisionKey = key;
        trace.add(nowMillis, category, decision, reason, mission == null ? "" : mission.id(), details);
    }

    private static Mission copyOrNull(Mission mission) {
        return mission == null ? null : mission.copy();
    }

    private TickDecision remember(TickDecision decision) {
        currentDecision = decision;
        return decision;
    }

    public record Command(
            String commandId,
            String issuedBy,
            String kind,
            Map<String, String> arguments) {
        public Command {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }

        private void validate() {
            if (commandId == null || commandId.isBlank()
                    || issuedBy == null || issuedBy.isBlank()
                    || kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("commandId, issuedBy, and kind are required");
            }
        }
    }

    public record Submission(Mission mission, boolean duplicate) {
    }

    public enum Layer {
        SURVIVAL,
        PROTECTION,
        MISSION,
        WAITING,
        IDLE
    }

    public record TickDecision(
            Layer layer,
            String action,
            String reason,
            Mission mission,
            Optional<ControlLease> lease,
            long controlEpoch,
            WorldSnapshot.Threat protectionThreat) {
        /** Compatibility callers have no target authority. */
        public TickDecision(Layer layer, String action, String reason, Mission mission,
                Optional<ControlLease> lease, long controlEpoch) {
            this(layer, action, reason, mission, lease, controlEpoch, null);
        }

        public TickDecision {
            if (protectionThreat != null && layer != Layer.PROTECTION
                    && !(layer == Layer.SURVIVAL && SurvivalAction.RECOVER_HEALTH.name().equals(action)))
                throw new IllegalArgumentException("Only protection and its eating handoff carry a threat");
        }
    }
}
