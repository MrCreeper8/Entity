package dev.entity.client.autonomy;

import dev.entity.client.autonomy.policy.AtomicBlockBreakPolicy;
import dev.entity.client.autonomy.policy.AtomicBlockBreakPolicy.Decision;
import dev.entity.client.autonomy.policy.AtomicBlockBreakPolicy.Observation;
import dev.entity.client.autonomy.policy.AtomicBlockBreakPolicy.Request;
import dev.entity.client.control.MinecraftActuatorGateway;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

/**
 * Owns one physical block-breaking transaction at a time.
 *
 * <p>The original world state, target, selected tool, and operation generation
 * are pinned until the block changes or the transaction fails honestly. Local
 * crack animation is advisory. The controller advances a pinned interaction
 * through {@link MinecraftActuatorGateway}; it never simulates a held mouse
 * button. A transient loss of {@code isBreakingBlock()} therefore remains
 * bound to the same target and the original deadline.</p>
 */
public final class AtomicBlockBreakController {
    private static final double DIRECT_REACH_SQUARED = 4.25D * 4.25D;
    private static final long MAXIMUM_REJECTION_TRANSIT_MILLIS = 10_000L;

    private final MinecraftClient client;
    private final MinecraftActuatorGateway actuators;
    private final AtomicBlockBreakPolicy policy = new AtomicBlockBreakPolicy();
    private long nextGeneration;
    private BoundRejection authoritativeRejection;
    private Session session;

    public AtomicBlockBreakController(MinecraftClient client) {
        this(client, MinecraftActuatorGateway.shared(client));
    }

    public AtomicBlockBreakController(
            MinecraftClient client,
            MinecraftActuatorGateway actuators) {
        this.client = Objects.requireNonNull(client, "client");
        this.actuators = Objects.requireNonNull(actuators, "actuators");
    }

    public Result tick(
            String operationId,
            BlockPos target,
            BlockHitResult hit,
            long nowMillis) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Objects.requireNonNull(target, "target");
        if (client.player == null || client.world == null || client.interactionManager == null) {
            // A world/disconnect transition must relinquish any pinned action
            // before another screen or world can inherit it.
            cancelPhysicalBreak();
            return new Result(State.WAITING, "waiting for a loaded player interaction context", 0);
        }
        String dimension = currentDimension();
        if (session == null || !session.matches(operationId, dimension, target)) {
            cancel();
            BlockState original = client.world.getBlockState(target);
            if (original.isAir()) {
                return new Result(State.COMPLETE, "world already confirms the target is clear", 0);
            }
            String tool = toolFingerprint();
            session = new Session(
                    ++nextGeneration,
                    operationId,
                    dimension,
                    target.toImmutable(),
                    original,
                    tool,
                    expectedBreakMillis(original, target));
        }

        BlockState current = client.world.getBlockState(target);
        // Property-only changes (waterlogging, orientation, lit state, etc.) do
        // not prove that the pinned block was mined. Completion requires the
        // original block type itself to be gone.
        boolean blockPresent = current.isOf(session.originalState.getBlock());
        boolean inReach = client.player.squaredDistanceTo(Vec3d.ofCenter(target))
                <= DIRECT_REACH_SQUARED;
        boolean sightline = hit != null && hit.getBlockPos().equals(target);
        if (sightline && session.breakSide == null) {
            // The block face is part of the actuator lease identity. Pin the
            // first verified face for the whole transaction so tiny raycast
            // changes around an edge cannot replace the generation and reset
            // real server-side break progress.
            session.breakSide = hit.getSide();
        }
        boolean serverRejected = wasAuthoritativelyRejected(session, nowMillis);
        String currentTool = toolFingerprint();
        Request request = session.request();

        // Terminal facts are evaluated before sending another physical action.
        if (!blockPresent || serverRejected || !session.toolFingerprint.equals(currentTool)
                || !inReach || !sightline) {
            Decision decision = policy.observe(request, observation(
                    blockPresent,
                    inReach,
                    sightline,
                    true,
                     client.interactionManager.isBreakingBlock(),
                     serverRejected,
                     currentTool,
                     client.interactionManager.getBlockBreakingProgress(),
                     nowMillis));
            if (terminal(decision.state())) cancelPhysicalBreak();
            return result(decision);
        }

        if (!policy.snapshot().started()) {
            MinecraftActuatorGateway.BreakFrame frame = actuators.tickBlockBreak(
                    operationId, target, session.breakSide);
            session.actuatorGeneration = frame.generation();
            Decision decision = policy.observe(request, observation(
                    true,
                    true,
                    true,
                    frame.accepted(),
                    frame.stillBreaking(),
                    false,
                    currentTool,
                    frame.progress(),
                    nowMillis));
            if (frame.accepted()) {
                recordFirstAcceptedAction(nowMillis);
            }
            if (terminal(decision.state())) cancelPhysicalBreak();
            return result(decision);
        }

        // Vanilla's focus/cursor-dependent handler is suppressed by the mixin
        // only while this exact transaction emits a fresh heartbeat. Advance
        // the pinned block directly once per Entity tick; never latch a
        // physical mouse key and never consult the global crosshair here.
        MinecraftActuatorGateway.BreakFrame frame = actuators.tickBlockBreak(
                operationId, target, session.breakSide);
        session.actuatorGeneration = frame.generation();
        Decision decision = policy.observe(request, observation(
                true,
                true,
                true,
                frame.accepted(),
                frame.stillBreaking(),
                false,
                currentTool,
                frame.progress(),
                nowMillis));
        if (frame.accepted()) recordFirstAcceptedAction(nowMillis);
        if (terminal(decision.state())) cancelPhysicalBreak();
        return result(decision);
    }

    /** Records the only evidence that may classify an exact block as protected. */
    public void reportServerRejected(String dimension, BlockPos target, long nowMillis) {
        if (dimension == null || dimension.isBlank() || target == null) return;
        Session active = session;
        long receivedAt = System.currentTimeMillis();
        if (active == null
                || active.firstAcceptedActionAtMillis < 0L
                || !active.dimension.equals(dimension.trim())
                || !active.target.equals(target)
                || nowMillis < active.firstAcceptedActionAtMillis
                || nowMillis > receivedAt + 2_000L
                || receivedAt - nowMillis > MAXIMUM_REJECTION_TRANSIT_MILLIS) return;
        // Bind the event to this exact local generation. It is never retained
        // as coordinate poison for a later attempt at the same block.
        authoritativeRejection = new BoundRejection(active.generation, nowMillis);
    }

    public void reportServerRejected(
            String dimension,
            int x,
            int y,
            int z,
            long nowMillis) {
        reportServerRejected(dimension, new BlockPos(x, y, z), nowMillis);
    }

    public void cancel() {
        cancelPhysicalBreak();
        policy.clear();
        session = null;
        authoritativeRejection = null;
    }

    public boolean activeFor(String operationId, BlockPos target) {
        return session != null
                && client.world != null
                && session.matches(operationId, currentDimension(), target);
    }

    private Observation observation(
            boolean blockPresent,
            boolean inReach,
            boolean sightline,
            boolean actionAccepted,
            boolean stillBreaking,
            boolean serverRejected,
            String tool,
            int breakProgress,
            long nowMillis) {
        return new Observation(
                blockPresent,
                inReach,
                sightline,
                actionAccepted,
                stillBreaking,
                serverRejected,
                tool,
                breakProgress,
                Math.max(0L, nowMillis));
    }

    private boolean wasAuthoritativelyRejected(Session current, long nowMillis) {
        BoundRejection rejection = authoritativeRejection;
        return rejection != null
                && rejection.generation == current.generation
                && nowMillis - rejection.eventAtMillis <= MAXIMUM_REJECTION_TRANSIT_MILLIS;
    }

    private long expectedBreakMillis(BlockState state, BlockPos target) {
        float perTick = state.calcBlockBreakingDelta(client.player, client.world, target);
        if (!Float.isFinite(perTick) || perTick <= 0.0F) return Long.MAX_VALUE;
        return Math.max(50L, (long) Math.ceil(1.0D / perTick) * 50L);
    }

    private String toolFingerprint() {
        int selected = client.player.getInventory().getSelectedSlot();
        ItemStack stack = client.player.getInventory().getStack(selected);
        String item = stack.isEmpty()
                ? "minecraft:air"
                : Registries.ITEM.getId(stack.getItem()).toString();
        return selected + ":" + item;
    }

    private String currentDimension() {
        return client.world.getRegistryKey().getValue().toString();
    }

    private void recordFirstAcceptedAction(long nowMillis) {
        if (session != null && session.firstAcceptedActionAtMillis < 0L) {
            session.firstAcceptedActionAtMillis = nowMillis;
        }
    }

    private void cancelPhysicalBreak() {
        Session active = session;
        if (active != null && active.actuatorGeneration > 0L) {
            actuators.cancelBlockBreak(active.operationId, active.actuatorGeneration);
            active.actuatorGeneration = 0L;
        }
    }

    private static boolean terminal(AtomicBlockBreakPolicy.State state) {
        return switch (state) {
            case COMPLETE, TOOL_PREEMPTED, OUT_OF_REACH, SIGHTLINE_LOST,
                    ACTION_REJECTED, SERVER_REJECTED, STALLED -> true;
            case STARTED, CONTINUE, RESTART_REQUIRED -> false;
        };
    }

    private static Result result(Decision decision) {
        return new Result(
                State.valueOf(decision.state().name()),
                decision.detail(),
                decision.restarts());
    }

    public enum State {
        WAITING,
        STARTED,
        CONTINUE,
        RESTART_REQUIRED,
        COMPLETE,
        TOOL_PREEMPTED,
        OUT_OF_REACH,
        SIGHTLINE_LOST,
        ACTION_REJECTED,
        SERVER_REJECTED,
        STALLED
    }

    public record Result(State state, String detail, int restarts) {
        public Result {
            Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
            if (restarts < 0) throw new IllegalArgumentException("restarts cannot be negative");
        }
    }

    private static final class Session {
        private final long generation;
        private final String operationId;
        private final String dimension;
        private final BlockPos target;
        private final BlockState originalState;
        private final String toolFingerprint;
        private final long expectedBreakMillis;
        private Direction breakSide;
        private long firstAcceptedActionAtMillis = -1L;
        private long actuatorGeneration;

        private Session(
                long generation,
            String operationId,
            String dimension,
            BlockPos target,
            BlockState originalState,
            String toolFingerprint,
                long expectedBreakMillis) {
            this.generation = generation;
            this.operationId = operationId;
            this.dimension = dimension;
            this.target = target;
            this.originalState = originalState;
            this.toolFingerprint = toolFingerprint;
            this.expectedBreakMillis = expectedBreakMillis;
        }

        private boolean matches(String operationId, String dimension, BlockPos target) {
            return this.operationId.equals(operationId)
                    && this.dimension.equals(dimension)
                    && this.target.equals(target);
        }

        private Request request() {
            return new Request(
                    operationId,
                    dimension + ":" + target.asLong(),
                    toolFingerprint,
                    expectedBreakMillis);
        }
    }

    private record BoundRejection(long generation, long eventAtMillis) {
    }
}
