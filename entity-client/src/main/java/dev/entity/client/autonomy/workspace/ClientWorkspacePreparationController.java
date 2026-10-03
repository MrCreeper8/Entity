package dev.entity.client.autonomy.workspace;

import dev.entity.client.autonomy.ClientInventoryController;
import dev.entity.client.autonomy.AtomicBlockBreakController;
import dev.entity.client.autonomy.BlockInteractionRaycaster;
import dev.entity.client.autonomy.policy.WorkspacePreparationPlanner;
import dev.entity.client.autonomy.policy.WorkspacePreparationPlanner.FailureKind;
import dev.entity.client.autonomy.policy.WorkspacePreparationPlanner.PreparationStep;
import dev.entity.client.autonomy.policy.WorkspacePreparationPlanner.Recovery;
import dev.entity.client.autonomy.policy.WorkspacePreparationPlanner.StepKind;
import dev.entity.client.autonomy.policy.WorkspacePreparationPlanner.WorkspacePlan;
import dev.entity.client.autonomy.policy.WorkspaceWorldProbe.Cell;
import dev.entity.client.baritone.FabricBaritonePort;
import dev.entity.core.control.ControlLease;
import dev.entity.core.port.BaritonePort;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Executes one bounded plan from {@link WorkspacePreparationPlanner}. */
public final class ClientWorkspacePreparationController {
    private static final double DIRECT_REACH_SQUARED = 4.25 * 4.25;
    private static final int TOOL_HOTBAR_SLOT = 6;
    private final MinecraftClient client;
    private final ClientInventoryController inventory;
    private final FabricBaritonePort baritone;
    private final AtomicBlockBreakController atomicBreak;
    private final WorkspacePreparationPlanner planner = new WorkspacePreparationPlanner();
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public ClientWorkspacePreparationController(
            MinecraftClient client,
            ClientInventoryController inventory,
            FabricBaritonePort baritone) {
        this(client, inventory, baritone, new AtomicBlockBreakController(client));
    }

    public ClientWorkspacePreparationController(
            MinecraftClient client,
            ClientInventoryController inventory,
            FabricBaritonePort baritone,
            AtomicBlockBreakController atomicBreak) {
        this.client = Objects.requireNonNull(client, "client");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.baritone = Objects.requireNonNull(baritone, "baritone");
        this.atomicBreak = Objects.requireNonNull(atomicBreak, "atomicBreak");
    }

    public Result tick(String operationId, ControlLease lease, long nowMillis) {
        return tick(operationId, lease, nowMillis, false);
    }

    public Result tick(
            String operationId,
            ControlLease lease,
            long nowMillis,
            boolean placementOverheadMustBePassable) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Objects.requireNonNull(lease, "lease");
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new Result(State.WAITING, "waiting for the loaded world before preparing workspace",
                    null, null);
        }

        Session session = sessions.computeIfAbsent(operationId, ignored -> new Session());
        BlockPos feet = client.player.getBlockPos();
        BlockPos revisionOrigin = session.plan == null ? feet : session.plannedFrom;
        MinecraftWorkspaceProbe probe = new MinecraftWorkspaceProbe(
                client, revisionOrigin, session.serverRejectedBreaks);
        boolean executingMove = session.plan != null
                && session.stepIndex < session.plan.steps().size()
                && session.plan.steps().get(session.stepIndex).kind() == StepKind.MOVE_TO_STAND;
        if (session.plan != null) {
            boolean displaced = !executingMove && !feet.equals(session.plannedFrom);
            long observedRevision = probe.geometryRevision();
            boolean geometryChanged = observedRevision != session.plan.geometryRevision();
            if (displaced || geometryChanged) {
                if (!displaced && geometryChanged
                        && expectedBreakWasOnlyGeometryChange(session, session.plan, probe)) {
                    // The one mutation explicitly authorized by the current step succeeded.
                    // Re-probe without charging a failure; every other mutation is bounded.
                    stopDirectBreak(session);
                    session.plan = null;
                    session.stepIndex = 0;
                } else {
                    return fail(
                            operationId,
                            session,
                            FailureKind.GEOMETRY_CHANGED,
                            observedRevision,
                            displaced
                                    ? "workspace origin changed outside the planned movement step"
                                    : "unexpected local geometry changed during workspace preparation");
                }
            }
        }

        if (session.plan == null) {
            WorkspacePreparationPlanner.Decision decision = planner.plan(
                    new WorkspacePreparationPlanner.Request(
                            operationId,
                            MinecraftWorkspaceProbe.point(feet),
                            placementOverheadMustBePassable),
                    probe);
            if (decision.state() == WorkspacePreparationPlanner.DecisionState.BLOCKED) {
                return new Result(State.BLOCKED, decision.detail(), null, null);
            }
            session.plan = decision.plan().orElseThrow();
            session.plannedFrom = feet.toImmutable();
            session.stepIndex = 0;
            if (decision.state() == WorkspacePreparationPlanner.DecisionState.READY) {
                return ready(session.plan, "verified a safe workstation workspace");
            }
        }

        WorkspacePlan plan = session.plan;
        if (session.stepIndex >= plan.steps().size()) {
            return ready(plan, "verified all workspace preparation steps");
        }
        PreparationStep step = plan.steps().get(session.stepIndex);
        return step.kind() == StepKind.BREAK_BLOCK
                ? tickBreak(operationId, session, plan, step, probe, lease, nowMillis)
                : tickMove(operationId, session, plan, step, probe, lease, nowMillis);
    }

    /**
     * Discards only the live plan/actuator transaction after an external
     * supervisor recovery. This is not workspace failure evidence and must not
     * consume either the candidate or operation retry budget.
     */
    public void resetTransient(String operationId) {
        if (operationId == null || operationId.isBlank()) return;
        Session session = sessions.get(operationId);
        if (session == null) return;
        planner.resetTransient(operationId);
        stopDirectBreak(session);
        session.plan = null;
        session.plannedFrom = null;
        session.stepIndex = 0;
    }

    /**
     * Charges a workstation placement rejection to the exact workspace
     * candidate that was handed off as READY, then invalidates the cached
     * proof. The caller should surface the returned RETRY/BLOCKED state.
     */
    public Result reportPlacementRejected(
            String operationId,
            BlockPos placement,
            String detail) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Objects.requireNonNull(placement, "placement");
        Session session = sessions.get(operationId);
        if (session == null || session.plan == null) {
            return new Result(
                    State.RETRY,
                    Objects.requireNonNullElse(detail, "workspace placement was rejected")
                            + "; no cached workspace proof remained",
                    placement,
                    null);
        }
        BlockPos expected = MinecraftWorkspaceProbe.block(session.plan.placement());
        if (!expected.equals(placement)) {
            stopDirectBreak(session);
            session.plan = null;
            session.stepIndex = 0;
            return new Result(
                    State.RETRY,
                    Objects.requireNonNullElse(detail, "workspace placement was rejected")
                            + "; discarded a stale mismatched workspace handoff",
                    placement,
                    null);
        }

        long revision = session.plan.geometryRevision();
        if (client.world != null) {
            BlockPos origin = session.plannedFrom == null ? placement : session.plannedFrom;
            revision = new MinecraftWorkspaceProbe(client, origin).geometryRevision();
        }
        Result result = fail(
                operationId,
                session,
                FailureKind.PLACE_REJECTED,
                revision,
                Objects.requireNonNullElse(detail, "workspace placement was rejected"));
        return new Result(result.state(), result.detail(), placement, null);
    }

    public void clear(String operationId) {
        if (operationId == null) return;
        Session session = sessions.remove(operationId);
        if (session != null) stopDirectBreak(session);
        planner.resetOperation(operationId);
    }

    public void clearAll() {
        sessions.values().forEach(this::stopDirectBreak);
        sessions.clear();
        planner.resetAll();
    }

    /** Releases live break input but keeps the bounded transaction resumable. */
    public void suspendAll() {
        sessions.values().forEach(this::stopDirectBreak);
    }

    /** Injects Paper's exact block-break cancellation evidence. */
    public void reportServerBreakRejected(
            String dimension,
            int x,
            int y,
            int z,
            long nowMillis) {
        atomicBreak.reportServerRejected(dimension, x, y, z, nowMillis);
    }

    private Result tickBreak(
            String operationId,
            Session session,
            WorkspacePlan plan,
            PreparationStep step,
            MinecraftWorkspaceProbe probe,
            ControlLease lease,
            long nowMillis) {
        BlockPos target = MinecraftWorkspaceProbe.block(step.position());
        BlockState state = client.world.getBlockState(target);
        Cell liveCell = probe.cell(step.position());
        if (liveCell.passable()) {
            if (expectedBreakWasOnlyGeometryChange(session, plan, probe)) {
                stopDirectBreak(session);
                session.plan = null;
                return new Result(State.PREPARING,
                        "verified cleared " + step.purpose().name().toLowerCase().replace('_', ' ')
                                + "; recomputing workspace geometry",
                        target, null);
            }
            return fail(operationId, session, FailureKind.GEOMETRY_CHANGED, probe.geometryRevision(),
                    "workspace target became passable outside the expected direct break");
        }
        FailureKind unsafe = unsafeLiveFailure(liveCell);
        if (unsafe != null) {
            return fail(operationId, session, unsafe, probe.geometryRevision(),
                    "workspace target changed to unsafe "
                            + liveCell.name().toLowerCase().replace('_', ' '));
        }
        if (client.player.squaredDistanceTo(Vec3d.ofCenter(target)) > DIRECT_REACH_SQUARED) {
            return fail(operationId, session, FailureKind.SIGHTLINE_LOST, probe.geometryRevision(),
                    "workspace block moved outside legitimate interaction reach");
        }

        Optional<BlockHitResult> visible = visibleHit(target);
        if (visible.isEmpty()) {
            return fail(operationId, session, FailureKind.SIGHTLINE_LOST, probe.geometryRevision(),
                    "workspace block has no honest current sightline");
        }

        // Main-hand ownership must be transferred before any inventory click;
        // otherwise Baritone autoTool can race the selected slot.
        baritone.neutralizeActuators("workspace preparation owns direct tool selection");
        ToolState tool = prepareBestTool(state, nowMillis);
        if (tool == ToolState.WAITING) {
            return new Result(State.PREPARING, "preparing the best carried workspace tool", target, null);
        }
        if (!target.equals(session.breakingTarget)) {
            atomicBreak.cancel();
            session.breakingTarget = target.toImmutable();
            session.breakContextRevision = probe.geometryRevisionIgnoring(Set.of(step.position()));
            session.clearAim();
        }

        BlockHitResult hit = visible.orElseThrow();
        if (!aimReady(session, "workspace-break:" + target.asLong(), hit.getPos())) {
            return new Result(State.PREPARING, "aiming at workspace block " + coordinates(target),
                    target, null);
        }
        AtomicBlockBreakController.Result breaking = atomicBreak.tick(
                operationId + ":workspace-break",
                target,
                hit,
                nowMillis);
        return switch (breaking.state()) {
            case WAITING -> new Result(State.WAITING, breaking.detail(), target, null);
            case STARTED, CONTINUE, RESTART_REQUIRED -> new Result(
                    State.PREPARING,
                    "excavating " + step.purpose().name().toLowerCase().replace('_', ' ')
                            + " at " + coordinates(target)
                            + (breaking.restarts() > 0
                            ? " (pinned restart " + breaking.restarts() + ")" : ""),
                    target,
                    null);
            case COMPLETE -> new Result(
                    State.PREPARING,
                    "world confirmed the workspace block changed; verifying geometry",
                    target,
                    null);
            case TOOL_PREEMPTED -> fail(
                    operationId,
                    session,
                    FailureKind.TOOL_PREEMPTED,
                    probe.geometryRevision(),
                    "workspace break was preempted by a held-tool owner change");
            case OUT_OF_REACH, SIGHTLINE_LOST -> fail(
                    operationId,
                    session,
                    FailureKind.SIGHTLINE_LOST,
                    probe.geometryRevision(),
                    breaking.detail());
            case ACTION_REJECTED -> fail(
                    operationId,
                    session,
                    FailureKind.BREAK_REJECTED,
                    probe.geometryRevision(),
                    breaking.detail());
            case SERVER_REJECTED -> {
                session.serverRejectedBreaks.add(target.toImmutable());
                yield fail(
                        operationId,
                        session,
                        FailureKind.PROTECTED,
                        probe.geometryRevision(),
                        breaking.detail());
            }
            case STALLED -> fail(
                    operationId,
                    session,
                    FailureKind.BREAK_STALLED,
                    probe.geometryRevision(),
                    breaking.detail());
        };
    }

    private Result tickMove(
            String operationId,
            Session session,
            WorkspacePlan plan,
            PreparationStep step,
            MinecraftWorkspaceProbe probe,
            ControlLease lease,
            long nowMillis) {
        stopDirectBreak(session);
        BlockPos destination = MinecraftWorkspaceProbe.block(step.position());
        String goalId = operationId + ":workspace:" + destination.asLong();
        BaritonePort.Goal goal = new BaritonePort.Goal(
                goalId,
                "goto",
                Map.of(
                        "x", Integer.toString(destination.getX()),
                        "y", Integer.toString(destination.getY()),
                        "z", Integer.toString(destination.getZ()),
                        "range", "0",
                        "allowBreak", "true",
                        "allowPlace", "true",
                        "allowParkourPlace", "true"));
        baritone.start(goal, lease);
        BaritonePort.Status status = baritone.poll(goalId);
        return switch (status.state()) {
            case COMPLETE -> {
                session.plan = null;
                session.stepIndex = 0;
                yield new Result(State.PREPARING,
                        "reached prepared stand position; verifying final placement geometry",
                        null, destination);
            }
            case TRANSIENT_FAILURE, BLOCKED -> fail(
                    operationId,
                    session,
                    FailureKind.MOVE_FAILED,
                    probe.geometryRevision(),
                    "could not reach prepared workspace stand: " + status.detail());
            case IDLE, CALCULATING, EXECUTING -> new Result(
                    State.PREPARING,
                    "moving into prepared workspace; " + status.detail(),
                    null,
                    destination);
        };
    }

    private Result fail(
            String operationId,
            Session session,
            FailureKind failure,
            long geometryRevision,
            String detail) {
        if (session.plan == null) {
            return new Result(State.RETRY, detail, null, null);
        }
        WorkspacePreparationPlanner.FailureDecision decision = planner.recordFailure(
                session.plan, failure, geometryRevision);
        stopDirectBreak(session);
        session.plan = null;
        session.stepIndex = 0;
        if (decision.recovery() == Recovery.BLOCKED) {
            return new Result(State.BLOCKED, detail + "; " + decision.detail(), null, null);
        }
        return new Result(State.RETRY,
                detail + "; " + decision.detail() + " ("
                        + decision.remainingTotalFailures() + " workspace attempts remain)",
                null,
                null);
    }

    private boolean expectedBreakWasOnlyGeometryChange(
            Session session,
            WorkspacePlan plan,
            MinecraftWorkspaceProbe probe) {
        if (session.breakingTarget == null
                || session.breakContextRevision == Long.MIN_VALUE
                || session.stepIndex >= plan.steps().size()) return false;
        PreparationStep step = plan.steps().get(session.stepIndex);
        if (step.kind() != StepKind.BREAK_BLOCK
                || !session.breakingTarget.equals(MinecraftWorkspaceProbe.block(step.position()))
                || !probe.cell(step.position()).passable()) return false;
        return probe.geometryRevisionIgnoring(Set.of(step.position()))
                == session.breakContextRevision;
    }

    private static FailureKind unsafeLiveFailure(Cell cell) {
        return switch (cell) {
            case FLUID -> FailureKind.FLUID;
            case FALLING -> FailureKind.FALLING_BLOCK;
            case HAZARD -> FailureKind.HAZARD;
            case UNBREAKABLE -> FailureKind.UNBREAKABLE;
            case PROTECTED -> FailureKind.PROTECTED;
            case UNLOADED -> FailureKind.UNLOADED;
            case OPEN, REPLACEABLE, SOLID -> null;
        };
    }

    private ToolState prepareBestTool(BlockState target, long nowMillis) {
        int bestSlot = -1;
        double bestScore = 1.0;
        var main = client.player.getInventory().getMainStacks();
        for (int slot = 0; slot < main.size(); slot++) {
            ItemStack stack = main.get(slot);
            if (stack.isEmpty() || (stack.isDamageable()
                    && stack.getMaxDamage() - stack.getDamage() <= 1)) continue;
            double score = stack.getMiningSpeedMultiplier(target);
            if (stack.isSuitableFor(target)) score += 1_000.0;
            if (score > bestScore) {
                bestSlot = slot;
                bestScore = score;
            }
        }
        if (bestSlot < 0) return ToolState.READY;
        ClientInventoryController.ClickResult result =
                inventory.moveInventorySlotToHotbar(bestSlot, TOOL_HOTBAR_SLOT, nowMillis);
        return result == ClientInventoryController.ClickResult.ALREADY_DONE
                ? ToolState.READY : ToolState.WAITING;
    }

    private Optional<BlockHitResult> visibleHit(BlockPos target) {
        return BlockInteractionRaycaster.visibleNow(client, target);
    }

    private boolean aimReady(Session session, String action, Vec3d target) {
        Vec3d delta = target.subtract(client.player.getEyePos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        client.player.setYaw(MathHelper.wrapDegrees(yaw));
        client.player.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
        if (!action.equals(session.aimedAction)) {
            session.aimedAction = action;
            session.aimedAtPlayerAge = client.player.age;
            return false;
        }
        return client.player.age > session.aimedAtPlayerAge;
    }

    private Result ready(WorkspacePlan plan, String detail) {
        return new Result(
                State.READY,
                detail + ": " + plan.detail(),
                MinecraftWorkspaceProbe.block(plan.placement()),
                MinecraftWorkspaceProbe.block(plan.stand()));
    }

    private void stopDirectBreak(Session session) {
        atomicBreak.cancel();
        session.breakingTarget = null;
        session.breakContextRevision = Long.MIN_VALUE;
        session.clearAim();
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private enum ToolState {
        READY,
        WAITING
    }

    public enum State {
        WAITING,
        PREPARING,
        READY,
        RETRY,
        BLOCKED
    }

    public record Result(State state, String detail, BlockPos placement, BlockPos stand) {
        public Result {
            Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    private static final class Session {
        private WorkspacePlan plan;
        private BlockPos plannedFrom;
        private int stepIndex;
        private BlockPos breakingTarget;
        private long breakContextRevision = Long.MIN_VALUE;
        private final Set<BlockPos> serverRejectedBreaks = new java.util.LinkedHashSet<>();
        private String aimedAction = "";
        private int aimedAtPlayerAge = Integer.MIN_VALUE;

        private void clearAim() {
            aimedAction = "";
            aimedAtPlayerAge = Integer.MIN_VALUE;
        }
    }
}
