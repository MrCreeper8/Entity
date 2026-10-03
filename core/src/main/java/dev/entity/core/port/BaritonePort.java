package dev.entity.core.port;

import dev.entity.core.control.ControlLease;

import java.util.Map;
import java.util.Objects;

/** Adapter over whichever supported Baritone API is present in Entity's client. */
public interface BaritonePort {
    void start(Goal goal, ControlLease lease);

    Status poll(String missionId);

    void cancel(long invalidatedEpoch, String reason);

    /**
     * Read-only, bounded flower observation. A nonempty item names an admitted
     * loaded flower; an empty item names an unvisited surface search waypoint.
     * The ordinary goto owner must perform any movement. Empty is not global
     * resource absence. Implementations must preserve the original search bound.
     */
    default java.util.Optional<HarvestSearchGoal> findFlowerSearchGoal(
            java.util.Set<String> items, int originX, int originZ,
            java.util.Set<String> visitedAreas) {
        return java.util.Optional.empty();
    }

    /** Same read-only search contract for supported natural logs and flowers. */
    default java.util.Optional<HarvestSearchGoal> findHarvestSearchGoal(
            java.util.Set<String> items, int originX, int originZ,
            java.util.Set<String> visitedAreas) {
        return findFlowerSearchGoal(items, originX, originZ, visitedAreas);
    }

    record HarvestSearchGoal(String areaId, String item, int x, int y, int z) {
        public HarvestSearchGoal {
            areaId = Objects.requireNonNull(areaId, "areaId");
            item = Objects.requireNonNull(item, "item");
            if (areaId.isBlank()) throw new IllegalArgumentException("search area cannot be blank");
        }
    }

    /**
     * Exact next native construction layer, including requested temporary supports.
     * An observed empty map means access/clearing, not permission to gather future
     * roof materials. Empty Optional means this adapter has no layer observation.
     */
    default java.util.Optional<Map<String, Integer>> blueprintLayerMaterials(
            String operationId, long controlEpoch) {
        return java.util.Optional.empty();
    }

    /**
     * Persists the exact final grounded cell of the current mining route before
     * the enclosing mining action is completed.
     *
     * <p>Mining may hand the body to a short direct item-pickup controller after
     * Baritone breaks the target block. That final movement is still part of the
     * usable route back out of the mine, so the Minecraft adapter gets one
     * explicit completion boundary at which to credit it. Non-Minecraft and
     * test adapters have no durable route state and may safely use this default.</p>
     */
    default void finishFunctionalMiningRoute(long nowMillis) {
    }

    /**
     * Cancels only the exact operation started under the supplied control epoch.
     *
     * <p>The default implementation is safe for the single-threaded adapters used by the
     * core: it verifies both identity and epoch before delegating to the legacy global cancel.
     * Stateful adapters should override this method atomically so a new operation cannot be
     * installed between the ownership check and cancellation.</p>
     *
     * @return {@code true} only when the matching owned operation was cancelled
     */
    default boolean cancelIfOwned(String operationId, long controlEpoch, String reason) {
        String expectedOperation = Objects.requireNonNull(operationId, "operationId").trim();
        if (expectedOperation.isEmpty()) {
            throw new IllegalArgumentException("operationId cannot be blank");
        }
        Status status = poll(expectedOperation);
        if (status.state() == State.IDLE || status.controlEpoch() != controlEpoch) return false;
        cancel(controlEpoch, Objects.requireNonNullElse(reason, "owned Baritone operation cancelled"));
        return true;
    }

    /**
     * True while an exact typed resource actuator owns mutation, replant, and
     * drop-settlement custody for this operation. The planner must not hand a
     * loaded drop to its generic pickup controller during that interval.
     */
    default boolean ownsScopedResourceActuation(
            String operationId,
            long controlEpoch) {
        return false;
    }

    record Goal(String missionId, String kind, Map<String, String> arguments) {
        public Goal {
            arguments = Map.copyOf(arguments);
        }
    }

    record Status(
            State state,
            String detail,
            double distanceRemaining,
            long completedWorkUnits,
            long controlEpoch,
            boolean progressExpected,
            FailureCause failureCause) {
        public Status(
                State state,
                String detail,
                double distanceRemaining,
                long completedWorkUnits,
                long controlEpoch) {
            this(state, detail, distanceRemaining, completedWorkUnits, controlEpoch,
                    true, FailureCause.NONE);
        }

        public Status(
                State state,
                String detail,
                double distanceRemaining,
                long completedWorkUnits,
                long controlEpoch,
                boolean progressExpected) {
            this(state, detail, distanceRemaining, completedWorkUnits, controlEpoch,
                    progressExpected, FailureCause.NONE);
        }

        public Status {
            state = Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
            failureCause = Objects.requireNonNull(failureCause, "failureCause");
        }
    }

    /** Internal typed failure edge; ordinary status transitions carry {@link #NONE}. */
    enum FailureCause {
        NONE,
        TARGET_ROUTE_EXHAUSTED,
        WORLD_POLICY_DENIED,
        /** The bounded loaded harvest scan found no matching source block. */
        LOADED_HARVEST_SOURCE_ABSENT,
        /** An observed native mining access failure needs a usable stone-breaking tool. */
        ACCESS_TOOL_REQUIRED,
        /** The local build watchdog needs a changed prerequisite, not an identical native restart. */
        CONSTRUCTION_STALLED,
        /** No permanent work renewed the finite construction diagnosis/recovery episode. */
        CONSTRUCTION_RECOVERY_EXHAUSTED,
        /** A physical output/pickup needs receiving room, not a replacement plan. */
        INVENTORY_CAPACITY
    }

    enum State {
        IDLE,
        CALCULATING,
        EXECUTING,
        COMPLETE,
        TRANSIENT_FAILURE,
        BLOCKED
    }
}
