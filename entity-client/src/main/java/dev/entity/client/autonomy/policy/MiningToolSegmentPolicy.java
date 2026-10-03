package dev.entity.client.autonomy.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Pure ownership and provisioning policy for one fixed mining actuator segment. */
public final class MiningToolSegmentPolicy {
    public enum Action {
        START_SEGMENT,
        CONTINUE_OWNED_SEGMENT,
        REBIND_OWNED_SEGMENT,
        RELEASE_OWNED_SEGMENT,
        PROVISION_TOOL
    }

    private MiningToolSegmentPolicy() {
    }

    /**
     * An owned segment may continue while any legal pick remains usable. Total
     * capacity is evaluated only before an unowned fixed segment starts; doing
     * it on every durability point would preempt and reprice the same goal.
     */
    public static Action decide(
            boolean miningOwned,
            boolean hasUsableTierTool,
            boolean selectedToolMatches,
            boolean hasCarriedRouteSupport,
            boolean hasFixedSegmentCapacity) {
        if (miningOwned) {
            if (!hasUsableTierTool) return Action.RELEASE_OWNED_SEGMENT;
            // A live route may temporarily select support material and then
            // auto-select its pick. Selected-slot drift is not tool loss and
            // must not tear down/recreate the MineProcess.
            return Action.CONTINUE_OWNED_SEGMENT;
        }
        return hasFixedSegmentCapacity ? Action.START_SEGMENT : Action.PROVISION_TOOL;
    }

    /** Converts an additional craft count into ResourcePlanner's absolute objective. */
    public static int requiredInventoryCount(
            int currentlyCarried,
            ToolStrategy.Choice choice) {
        if (currentlyCarried < 0) {
            throw new IllegalArgumentException("currently carried tool count cannot be negative");
        }
        Objects.requireNonNull(choice, "choice");
        // A zero-craft choice means measured carried durability already covers the work.
        // Rebinding that existing stack is the state transition; inventing one extra craft
        // turns a stale exact-tool binding into an unbounded replacement loop.
        int additional = choice.toolsToCraft();
        try {
            return Math.addExact(currentlyCarried, additional);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("tool inventory objective exceeds integer capacity", overflow);
        }
    }

    /**
     * Atomically supersedes a lost legacy leaf's exact tool and its provisioning objective.
     * Runtime food/fuel prerequisites carry plannedTool without a committed-program token;
     * updating only the recovery fields leaves their old plannedTool authoritative forever.
     */
    public static Map<String, String> withReplacementCommitment(
            Map<String, String> parameters, String item, int count, int segmentBlocks) {
        Objects.requireNonNull(parameters, "parameters");
        if (item == null || item.isBlank() || count <= 0 || segmentBlocks <= 0) {
            throw new IllegalArgumentException("replacement requires a tool and positive count/work");
        }
        LinkedHashMap<String, String> checkpoint = new LinkedHashMap<>(parameters);
        if (checkpoint.containsKey("plannedTool")) checkpoint.put("plannedTool", item);
        checkpoint.put("miningToolCommitmentItem", item);
        checkpoint.put("miningToolCommitmentCount", Integer.toString(count));
        checkpoint.put("miningToolCommitmentSegmentBlocks", Integer.toString(segmentBlocks));
        return Map.copyOf(checkpoint);
    }

    /** Aggregate usable durability boundary for a not-yet-owned fixed segment. */
    public static boolean hasCapacity(int requiredBlocks, Iterable<Integer> usableDurabilities) {
        if (requiredBlocks <= 0) {
            throw new IllegalArgumentException("required blocks must be positive");
        }
        Objects.requireNonNull(usableDurabilities, "usableDurabilities");
        long total = 0L;
        for (Integer durability : usableDurabilities) {
            if (durability == null || durability < 0) {
                throw new IllegalArgumentException("usable durability cannot be null or negative");
            }
            total += durability;
            if (total >= requiredBlocks) return true;
        }
        return false;
    }

    /**
     * Converts raw Minecraft durability to work the planner may promise. The last point is kept
     * out of every plan because the physical mining actuator releases before breaking the tool.
     */
    public static int usableWorkDurability(int remainingDurability) {
        if (remainingDurability < 0) {
            throw new IllegalArgumentException("remaining durability cannot be negative");
        }
        return Math.max(0, remainingDurability - 1);
    }

    /**
     * Largest fixed segment one exact carried stack can execute without spending its final
     * durability point. Baritone may switch tools only after the bounded segment releases; an
     * aggregate total must never be mistaken for one physically selectable stack.
     */
    public static int maximumSafeSegmentWork(
            int requestedBlocks,
            Iterable<Integer> remainingDurabilities) {
        if (requestedBlocks <= 0) {
            throw new IllegalArgumentException("requested blocks must be positive");
        }
        Objects.requireNonNull(remainingDurabilities, "remainingDurabilities");
        int maximum = 0;
        for (Integer durability : remainingDurabilities) {
            if (durability == null || durability < 0) {
                throw new IllegalArgumentException(
                        "remaining durability cannot be null or negative");
            }
            maximum = Math.max(maximum, usableWorkDurability(durability));
        }
        return Math.min(requestedBlocks, maximum);
    }

    /** Exact stack threshold for the EQUIP leaf which starts the next bounded segment. */
    public static int minimumEquippedDurability(
            int requestedBlocks,
            Iterable<Integer> remainingDurabilities) {
        int work = maximumSafeSegmentWork(requestedBlocks, remainingDurabilities);
        // Two means one usable block plus the final protected durability point. When inventory
        // disappeared after commitment, the controller returns ITEM_MISSING and typed rebase
        // handles it instead of accepting a zero-durability tool.
        return work == 0 ? 2 : Math.addExact(work, 1);
    }
}
