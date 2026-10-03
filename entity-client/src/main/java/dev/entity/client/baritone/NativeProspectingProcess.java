package dev.entity.client.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Finite native access to a prospecting depth before MineProcess resumes exploration.
 * Baritone retains movement, tools and excavation; this temporary owner never resets
 * the suspended miner. The caller owns admission, safety, progress liveness and Stop.
 */
public final class NativeProspectingProcess implements IBaritoneProcess {
    public enum Result { IDLE, RUNNING, REACHED, FAILED, CANCELED }

    private final Supplier<BlockPos> feet;
    private final Supplier<Goal> nativeGoal;
    private Goal interruptedGoal;
    private GoalYLevel goal;
    private Result result = Result.IDLE;
    private boolean active;
    private boolean issued;
    private boolean calculationFailed;
    private int targetY;

    public NativeProspectingProcess(IBaritone baritone) {
        this(Objects.requireNonNull(baritone, "baritone").getPathingControlManager()::registerProcess,
                () -> baritone.getPlayerContext().playerFeet(),
                () -> baritone.getPathingBehavior().getGoal());
    }

    NativeProspectingProcess(Consumer<IBaritoneProcess> register,
                             Supplier<BlockPos> feet, Supplier<Goal> nativeGoal) {
        this.feet = Objects.requireNonNull(feet, "feet");
        this.nativeGoal = Objects.requireNonNull(nativeGoal, "nativeGoal");
        Objects.requireNonNull(register, "register").accept(this);
    }

    /** Repeated admission cannot replace an active depth or clear its failure state. */
    public synchronized boolean begin(int targetY) {
        if (active) return false;
        interruptedGoal = nativeGoal.get();
        this.targetY = targetY;
        goal = new GoalYLevel(targetY);
        issued = false;
        calculationFailed = false;
        result = Result.RUNNING;
        active = true;
        return true;
    }

    public synchronized boolean active() { return active; }
    public synchronized Result result() { return result; }
    public synchronized int targetY() { return targetY; }

    /**
     * The enclosing owner also retires native controls when its lease is stopped.
     * Cancel consumes a reported failure before that owner selects a new approach.
     */
    public synchronized void cancel() {
        if (active || result == Result.FAILED) {
            active = false;
            result = Result.CANCELED;
        }
    }

    @Override public synchronized boolean isActive() { return active; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return DEFAULT_PRIORITY + 5.0; }
    @Override public synchronized void onLostControl() { cancel(); }
    @Override public synchronized String displayName0() {
        return "Entity prospecting access: " + result + " at Y=" + targetY;
    }

    @Override
    public synchronized PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!active) return new PathingCommand(null, PathingCommandType.DEFER);
        // A failure carried over from the interrupted native goal is not ours. Once
        // issued, retain a real calculation failure across an unsafe atomic movement.
        if (issued && calcFailed) calculationFailed = true;
        if (!isSafeToCancel) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        if (feet.get().getY() == targetY) return finish(Result.REACHED);
        if (calculationFailed) return finish(Result.FAILED);
        PathingCommandType type = issued ? PathingCommandType.SET_GOAL_AND_PATH
                : PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH;
        issued = true;
        return new PathingCommand(goal, type);
    }

    private PathingCommand finish(Result terminal) {
        active = false;
        result = terminal;
        // Cancel only this path. Native temporary-process arbitration preserves
        // the miner's discoveries/blacklist and lets it reclaim its original goal.
        return new PathingCommand(interruptedGoal, PathingCommandType.CANCEL_AND_SET_GOAL);
    }
}
