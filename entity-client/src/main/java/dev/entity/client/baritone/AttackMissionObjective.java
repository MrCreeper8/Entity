package dev.entity.client.baritone;

import dev.entity.core.port.BaritonePort;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.Optional;

/** One explicit Attack objective, independent of temporary actuator ownership. Not a controller. */
public final class AttackMissionObjective {
    private BaritonePort.Goal goal;
    private Object world;
    private Entity target;
    private BlockPos defeatedPosition;
    private long revision;
    private Snapshot snapshot = new Snapshot(0, "NONE", "", "", "", 0, 0);

    /** Only Runtime's current, live exact mission may authorize retention. */
    public synchronized void authorize(BaritonePort.Goal authorized, Object currentWorld, long now) {
        if (authorized == null || currentWorld == null
                || !authorized.kind().equalsIgnoreCase("attack")) {
            clear(now);
            return;
        }
        if (!matches(authorized, currentWorld)) {
            clear(now);
            goal = authorized;
            world = currentWorld;
            snapshot = new Snapshot(++revision, "AUTHORIZED", goal.missionId(), "", "", 0, 0);
        }
        observeDeath(currentWorld, now);
    }

    /** Actual existing-controller selection, never a same-type protection encounter. */
    public synchronized void selected(BaritonePort.Goal selectingGoal, Object currentWorld,
            Entity selected, long now) {
        if (!matches(selectingGoal, currentWorld) || selected == null
                || !selected.isAlive() || selected.isRemoved()) return;
        if (target != null && target.getUuid().equals(selected.getUuid())) return;
        target = selected;
        defeatedPosition = null;
        snapshot = new Snapshot(++revision, "SELECTED", goal.missionId(),
                selected.getUuidAsString(), EntityType.getId(selected.getType()).toString(), now, 0);
    }

    /** Native zero-health evidence is required; despawn, unload and removal are not death. */
    public synchronized void observeDeath(Object currentWorld, long now) {
        if (world != currentWorld || target == null || defeatedPosition != null) return;
        if (target instanceof LivingEntity living && living.getHealth() <= 0.0F
                && (!target.isRemoved() || target.getRemovalReason() == Entity.RemovalReason.KILLED)) {
            defeatedPosition = target.getBlockPos().toImmutable();
            snapshot = new Snapshot(++revision, "DEFEATED", goal.missionId(),
                    snapshot.targetUuid(), snapshot.targetType(), snapshot.selectedAtMillis(), now);
        }
    }

    public synchronized Optional<Entity> retainedTarget(BaritonePort.Goal requested, Object currentWorld) {
        if (!matches(requested, currentWorld) || target == null || defeatedPosition != null)
            return Optional.empty();
        if (!target.isAlive() || target.isRemoved()) {
            target = null;
            snapshot = new Snapshot(++revision, "UNAVAILABLE", goal.missionId(),
                    snapshot.targetUuid(), snapshot.targetType(), snapshot.selectedAtMillis(), 0);
            return Optional.empty();
        }
        return Optional.of(target);
    }

    public synchronized Optional<BlockPos> completed(BaritonePort.Goal requested, Object currentWorld) {
        return matches(requested, currentWorld) ? Optional.ofNullable(defeatedPosition) : Optional.empty();
    }

    public synchronized void clear(long now) {
        if (goal != null) snapshot = new Snapshot(++revision, "REVOKED", goal.missionId(),
                snapshot.targetUuid(), snapshot.targetType(), snapshot.selectedAtMillis(),
                snapshot.deathObservedAtMillis());
        goal = null;
        world = null;
        target = null;
        defeatedPosition = null;
    }

    public synchronized Snapshot snapshot() { return snapshot; }

    private boolean matches(BaritonePort.Goal requested, Object currentWorld) {
        return goal != null && world == currentWorld && goal.equals(requested);
    }

    public record Snapshot(long revision, String state, String missionId, String targetUuid,
            String targetType, long selectedAtMillis, long deathObservedAtMillis) {
        public Snapshot {
            Objects.requireNonNull(state); Objects.requireNonNull(missionId);
            Objects.requireNonNull(targetUuid); Objects.requireNonNull(targetType);
        }
    }
}
