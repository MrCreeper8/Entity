package dev.entity.client.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A temporary native route, not a second movement controller. Baritone still owns every
 * movement, tool and block interaction, and retains its interrupted mining/follow process.
 * Callers own candidate safety, lease invalidation and the decision to attempt recovery.
 */
public final class NativeRouteRecoveryProcess implements IBaritoneProcess {
    static final long CANDIDATE_TIMEOUT_MILLIS = 15_000L;
    static final long MAX_CANDIDATE_TIMEOUT_MILLIS = 60_000L;

    public enum Result { IDLE, RUNNING, REACHED, EXHAUSTED, TIMED_OUT, REPLANNED, CANCELED }

    public record Snapshot(Result result, BlockPos candidate, int attemptedCandidates,
                           String detail) { }

    private final Supplier<BlockPos> feet;
    private final Supplier<Goal> nativeGoal;
    private final LongSupplier clock;
    private List<BlockPos> candidates = List.of();
    private Goal interruptedGoal;
    private int candidateIndex;
    private int attemptedCandidates;
    private long candidateStartedAt;
    private long candidateTimeoutMillis = CANDIDATE_TIMEOUT_MILLIS;
    private boolean active;
    private boolean issued;
    private int replanStage = -1;
    private String rejection;
    private Result result = Result.IDLE;
    private String detail = "no route recovery requested";

    public NativeRouteRecoveryProcess(IBaritone baritone) {
        this(Objects.requireNonNull(baritone, "baritone").getPathingControlManager()::registerProcess,
                () -> baritone.getPlayerContext().playerFeet(),
                () -> baritone.getPathingBehavior().getGoal(), System::currentTimeMillis);
    }

    NativeRouteRecoveryProcess(Consumer<IBaritoneProcess> register,
                               Supplier<BlockPos> feet, Supplier<Goal> nativeGoal,
                               LongSupplier clock) {
        this.feet = Objects.requireNonNull(feet, "feet");
        this.nativeGoal = Objects.requireNonNull(nativeGoal, "nativeGoal");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(register, "register").accept(this);
    }

    /** An active attempt cannot be refreshed into an endless retry by its caller. */
    public synchronized boolean begin(List<BlockPos> orderedCandidates, long nowMillis) {
        return begin(orderedCandidates, nowMillis, CANDIDATE_TIMEOUT_MILLIS);
    }

    /** A retained surface exit may be farther than a local detour, but still has one deadline. */
    public synchronized boolean begin(List<BlockPos> orderedCandidates, long nowMillis, long timeoutMillis) {
        if (active) return false;
        if (timeoutMillis < 1 || timeoutMillis > MAX_CANDIDATE_TIMEOUT_MILLIS)
            throw new IllegalArgumentException("Recovery timeout is outside the bounded native window");
        candidateTimeoutMillis = timeoutMillis;
        LinkedHashSet<BlockPos> unique = new LinkedHashSet<>();
        for (BlockPos candidate : Objects.requireNonNull(orderedCandidates, "candidates")) {
            unique.add(Objects.requireNonNull(candidate, "candidate").toImmutable());
        }
        candidates = List.copyOf(unique);
        candidateIndex = 0;
        attemptedCandidates = 0;
        replanStage = -1;
        issued = false;
        rejection = null;
        candidateStartedAt = nowMillis;
        interruptedGoal = nativeGoal.get();
        active = !candidates.isEmpty();
        result = active ? Result.RUNNING : Result.EXHAUSTED;
        detail = active ? "trying a different native route" : "no alternative candidate available";
        return active;
    }

    /** Cancels only the current path, never the native process or its search/blacklist. */
    public synchronized boolean requestReplan(long nowMillis) {
        if (active) return false;
        interruptedGoal = nativeGoal.get();
        if (interruptedGoal == null) return false;
        candidates = List.of();
        candidateIndex = attemptedCandidates = 0;
        rejection = null;
        issued = false;
        candidateStartedAt = nowMillis;
        replanStage = 0;
        active = true;
        result = Result.RUNNING;
        detail = "replanning the retained native goal without resetting its process";
        return true;
    }

    /** The world adapter can reject an observed unsafe edge; it never requeues this candidate. */
    public synchronized void rejectCurrent(String reason) {
        if (active && replanStage < 0 && issued && rejection == null) {
            rejection = Objects.requireNonNullElse(reason, "candidate route rejected");
        }
    }

    public synchronized boolean active() { return active; }

    public synchronized Snapshot snapshot() {
        BlockPos candidate = candidateIndex < candidates.size() ? candidates.get(candidateIndex) : null;
        return new Snapshot(result, candidate, attemptedCandidates, detail);
    }

    /** Caller also owns clearing native controls when the enclosing lease is canceled. */
    public synchronized void cancel() {
        if (active) {
            active = false;
            result = Result.CANCELED;
            detail = "route recovery canceled by its owner";
        }
    }

    @Override public synchronized boolean isActive() { return active; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return DEFAULT_PRIORITY + 10.0; }
    @Override public synchronized void onLostControl() { cancel(); }
    @Override public synchronized String displayName0() { return "Entity route recovery: " + detail; }

    @Override
    public synchronized PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!active) return command(null, PathingCommandType.DEFER);
        // Do not interrupt an atomic native fall/break/placement midway through execution.
        if (!isSafeToCancel) return command(null, PathingCommandType.REQUEST_PAUSE);
        long now = clock.getAsLong();
        if (replanStage >= 0) {
            if (replanStage++ == 0) {
                // FORCE_REVALIDATE alone preserves a stale path when its goal is unchanged.
                return command(interruptedGoal, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (replanStage == 2) {
                return command(interruptedGoal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
            }
            active = false;
            result = Result.REPLANNED;
            detail = "native goal replanned with process state retained";
            return command(null, PathingCommandType.DEFER);
        }

        BlockPos candidate = candidates.get(candidateIndex);
        if (candidate.equals(feet.get())) return finish(Result.REACHED, "alternative waypoint reached");
        boolean timedOut = issued && now - candidateStartedAt >= candidateTimeoutMillis;
        if (rejection != null || (issued && calcFailed) || timedOut) {
            String failure = rejection != null ? rejection
                    : timedOut ? "candidate route timed out" : "native calculation rejected candidate";
            rejection = null;
            if (++candidateIndex >= candidates.size()) {
                return finish(timedOut ? Result.TIMED_OUT : Result.EXHAUSTED, failure);
            }
            candidateStartedAt = now;
            issued = false;
            detail = failure + "; trying next distinct candidate";
            // Path-only cancellation makes the failed approach ineligible before the next goal.
            return command(new GoalBlock(candidates.get(candidateIndex)),
                    PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        PathingCommandType type = issued ? PathingCommandType.SET_GOAL_AND_PATH
                : PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH;
        if (!issued) {
            attemptedCandidates++;
            candidateStartedAt = now;
            issued = true;
        }
        return command(new GoalBlock(candidate), type);
    }

    private PathingCommand finish(Result terminal, String message) {
        active = false;
        result = terminal;
        detail = message;
        // Retire only our path. The next native tick resumes the still-active original process.
        return command(interruptedGoal, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private static PathingCommand command(Goal goal, PathingCommandType type) {
        return new PathingCommand(goal, type);
    }
}
