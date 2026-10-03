package dev.entity.core.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Durable root-to-leaf prerequisite stack for one mission.
 *
 * <p>The root objective is index zero and the currently executable frame is
 * the last entry. Ancestors remain in {@link PlanFrameState#WAITING_ON_CHILD}
 * until their child completes, at which point the exact parent frame is
 * reactivated.</p>
 */
public final class TaskPlan {
    private final String missionId;
    private final PlanFrame.Spec rootSpec;
    private final List<PlanFrame> frames;
    private TaskPlanState state;
    private String detail;
    private final long createdAtMillis;
    private long updatedAtMillis;
    private long revision;

    private TaskPlan(
            String missionId,
            PlanFrame.Spec rootSpec,
            List<PlanFrame> frames,
            TaskPlanState state,
            String detail,
            long createdAtMillis,
            long updatedAtMillis,
            long revision) {
        this.missionId = requireText(missionId, "missionId");
        this.rootSpec = copySpec(rootSpec);
        this.frames = new ArrayList<>(Objects.requireNonNull(frames, "frames"));
        this.state = Objects.requireNonNull(state, "state");
        this.detail = Objects.requireNonNullElse(detail, "");
        this.createdAtMillis = createdAtMillis;
        this.updatedAtMillis = updatedAtMillis;
        this.revision = revision;
        validate();
    }

    public static TaskPlan create(String missionId, PlanFrame.Spec root, long nowMillis) {
        return create(missionId, root, root.parameters(), nowMillis);
    }

    /**
     * Creates a plan whose durable root frame may contain executor checkpoint facts in addition
     * to the immutable declarative root parameters. Construction and the first store save remain
     * one transaction, while later checkpoints may safely replace or remove those extra facts.
     */
    public static TaskPlan create(
            String missionId,
            PlanFrame.Spec root,
            Map<String, String> initialRootParameters,
            long nowMillis) {
        PlanFrame.requireTimestamp(nowMillis);
        PlanFrame initialRoot = PlanFrame.create(root, nowMillis).checkpoint(
                Objects.requireNonNull(initialRootParameters, "initialRootParameters"),
                "queued",
                nowMillis);
        return new TaskPlan(
                missionId,
                root,
                List.of(initialRoot),
                TaskPlanState.OPEN,
                "plan created",
                nowMillis,
                nowMillis,
                1);
    }

    /** Low-level restore factory used by durable stores. Frames are root first. */
    public static TaskPlan restore(
            String missionId,
            PlanFrame.Spec rootSpec,
            List<PlanFrame> frames,
            TaskPlanState state,
            String detail,
            long createdAtMillis,
            long updatedAtMillis,
            long revision) {
        return new TaskPlan(
                missionId, rootSpec, frames, state, detail,
                createdAtMillis, updatedAtMillis, revision);
    }

    public TaskPlan copy() {
        return new TaskPlan(
                missionId, rootSpec, frames, state, detail,
                createdAtMillis, updatedAtMillis, revision);
    }

    /** Activates the current pending frame. */
    public void activate(String frameDetail, long nowMillis) {
        requireOpen();
        int current = frames.size() - 1;
        PlanFrame frame = frames.get(current);
        if (frame.state() != PlanFrameState.PENDING) {
            throw new IllegalStateException("Current frame is already " + frame.state());
        }
        frames.set(current, frame.transition(
                PlanFrameState.ACTIVE,
                Objects.requireNonNullElse(frameDetail, "executing"),
                nowMillis));
        mutate("active: " + frame.displayName(), nowMillis);
    }

    /**
     * Suspends the active frame and pushes a pending prerequisite above it.
     * The caller explicitly activates the returned current frame when its
     * executor has accepted the work.
     */
    public void pushPrerequisite(PlanFrame.Spec child, String reason, long nowMillis) {
        requireOpen();
        int parentIndex = frames.size() - 1;
        PlanFrame parent = frames.get(parentIndex);
        if (parent.state() != PlanFrameState.ACTIVE) {
            throw new IllegalStateException("Only an active frame can request a prerequisite");
        }
        String waitingDetail = Objects.requireNonNullElse(reason, "waiting for prerequisite");
        frames.set(parentIndex, parent.transition(
                PlanFrameState.WAITING_ON_CHILD, waitingDetail, nowMillis));
        PlanFrame prerequisite = PlanFrame.create(child, nowMillis);
        frames.add(prerequisite);
        mutate("prerequisite: " + prerequisite.displayName(), nowMillis);
    }

    /**
     * Completes and pops the active frame. Its parent is reactivated verbatim;
     * completing the root marks the whole plan complete.
     */
    public void completeCurrent(String completionDetail, long nowMillis) {
        requireOpen();
        int current = frames.size() - 1;
        PlanFrame completed = frames.get(current);
        if (completed.state() != PlanFrameState.ACTIVE) {
            throw new IllegalStateException("Only an active frame can complete");
        }
        completed = completed.transition(
                PlanFrameState.COMPLETED,
                Objects.requireNonNullElse(completionDetail, "completed"),
                nowMillis);
        frames.remove(current);
        if (frames.isEmpty()) {
            state = TaskPlanState.COMPLETED;
            // The terminal frame is removed from the durable stack. Preserve
            // its exact result on the plan so a restart between this commit
            // and caller acknowledgement cannot turn a partial/failure result
            // into a generic success-looking completion.
            mutate("completed: " + completed.displayName()
                    + (completed.detail().isBlank() ? "" : "; " + completed.detail()),
                    nowMillis);
            return;
        }
        int parentIndex = frames.size() - 1;
        PlanFrame parent = frames.get(parentIndex);
        if (parent.state() != PlanFrameState.WAITING_ON_CHILD) {
            throw new IllegalStateException("Prerequisite parent is not waiting");
        }
        frames.set(parentIndex, parent.transition(
                PlanFrameState.ACTIVE,
                "resumed after " + completed.displayName(),
                nowMillis));
        mutate("resumed: " + parent.displayName(), nowMillis);
    }

    /**
     * Completes the exact active child while atomically committing its result
     * into the immediate parent. The child identity is an optimistic fence:
     * a late callback from an older prerequisite generation cannot complete a
     * newer leaf that happens to serve the same objective.
     *
     * <p>The parent checkpoint, child pop, and parent reactivation are one
     * logical plan mutation (one revision). All structural and parameter
     * validation is performed before this instance is changed.</p>
     */
    public void completeCurrentAndCheckpointParent(
            String expectedChildFrameId,
            Map<String, String> parentParameters,
            String completionDetail,
            String parentDetail,
            long nowMillis) {
        requireOpen();
        String expectedId = requireText(expectedChildFrameId, "expectedChildFrameId");
        Objects.requireNonNull(parentParameters, "parentParameters");
        if (frames.size() < 2) {
            throw new IllegalStateException("Completing a child requires an immediate parent");
        }

        int childIndex = frames.size() - 1;
        int parentIndex = childIndex - 1;
        PlanFrame child = frames.get(childIndex);
        if (child.state() != PlanFrameState.ACTIVE) {
            throw new IllegalStateException("Only an active child frame can complete");
        }
        if (!child.id().equals(expectedId)) {
            throw new IllegalStateException(
                    "Current child " + child.id() + " does not match expected child " + expectedId);
        }
        PlanFrame parent = frames.get(parentIndex);
        if (parent.state() != PlanFrameState.WAITING_ON_CHILD) {
            throw new IllegalStateException("Immediate prerequisite parent is not waiting");
        }

        String resumedDetail = parentDetail == null || parentDetail.isBlank()
                ? "resumed after " + child.displayName()
                : parentDetail;
        PlanFrame completed = child.transition(
                PlanFrameState.COMPLETED,
                Objects.requireNonNullElse(completionDetail, "completed"),
                nowMillis);
        PlanFrame resumedParent = parent
                .checkpoint(parentParameters, resumedDetail, nowMillis)
                .transition(PlanFrameState.ACTIVE, resumedDetail, nowMillis);

        // Validate the complete candidate stack before changing this object.
        List<PlanFrame> candidateFrames = new ArrayList<>(frames);
        candidateFrames.set(parentIndex, resumedParent);
        candidateFrames.remove(childIndex);
        new TaskPlan(
                missionId, rootSpec, candidateFrames, state, detail,
                createdAtMillis, Math.max(updatedAtMillis, nowMillis), revision);

        frames.set(parentIndex, resumedParent);
        frames.remove(childIndex);
        mutate("checkpointed and resumed: " + parent.displayName()
                + " after " + completed.displayName(), nowMillis);
    }

    /** Replaces only the current leaf while retaining every parent frame. */
    public void replaceCurrent(PlanFrame.Spec replacement, String reason, long nowMillis) {
        requireOpen();
        int current = frames.size() - 1;
        PlanFrame previous = frames.get(current);
        if (previous.state() != PlanFrameState.PENDING
                && previous.state() != PlanFrameState.ACTIVE) {
            throw new IllegalStateException("Current frame cannot be replanned from " + previous.state());
        }
        PlanFrame next = PlanFrame.create(replacement, nowMillis);
        frames.set(current, next);
        String explanation = Objects.requireNonNullElse(reason, "replacement selected");
        mutate("replanned " + previous.displayName() + " -> " + next.displayName()
                + ": " + explanation, nowMillis);
    }

    /**
     * Discards every execution frame and starts a fresh root generation from
     * the immutable mission objective. Only facts explicitly supplied by the
     * caller survive. This is the recovery boundary for discontinuities such
     * as death: inventory baselines, GUI state, drop origins, and child
     * prerequisites cannot leak into the new execution.
     *
     * <p>The returned root has a new frame identity, fencing late callbacks
     * from the discarded generation. Declarative root parameters cannot be
     * overwritten by a claimed fact.</p>
     */
    public void rebaseToRoot(
            Map<String, String> confirmedRootFacts,
            String reason,
            long nowMillis) {
        requireOpen();
        Objects.requireNonNull(confirmedRootFacts, "confirmedRootFacts");
        PlanFrame.requireTimestamp(nowMillis);

        LinkedHashMap<String, String> parameters = new LinkedHashMap<>(rootSpec.parameters());
        for (Map.Entry<String, String> entry : confirmedRootFacts.entrySet()) {
            String key = requireText(entry.getKey(), "confirmed fact key");
            String value = Objects.requireNonNull(entry.getValue(), "confirmed fact " + key);
            String declarative = parameters.get(key);
            if (declarative != null && !declarative.equals(value)) {
                throw new IllegalArgumentException(
                        "Confirmed fact " + key + " cannot overwrite declarative root intent");
            }
            parameters.put(key, value);
        }

        String explanation = reason == null || reason.isBlank()
                ? "execution state reconciled"
                : reason;
        PlanFrame.Spec rebasedSpec = new PlanFrame.Spec(
                rootSpec.kind(), rootSpec.target(), rootSpec.count(), parameters);
        PlanFrame rebased = PlanFrame.create(rebasedSpec, nowMillis)
                .transition(PlanFrameState.ACTIVE, explanation, nowMillis);
        frames.clear();
        frames.add(rebased);
        mutate("rebased to root: " + explanation, nowMillis);
    }

    /**
     * Discards every descendant of one live frame and starts a fresh execution
     * generation for that frame while preserving its outer prerequisite stack.
     *
     * <p>This is the nested equivalent of {@link #rebaseToRoot(Map, String, long)}.
     * It is used when a self-contained child planner explicitly invalidates its
     * executable program: the child must be rebuilt from current truth without
     * discarding the ordered batch or other parent objective which owns it.</p>
     */
    public void rebaseToFrame(
            String expectedFrameId,
            Map<String, String> parameters,
            String reason,
            long nowMillis) {
        requireOpen();
        String expectedId = requireText(expectedFrameId, "expectedFrameId");
        Objects.requireNonNull(parameters, "parameters");
        PlanFrame.requireTimestamp(nowMillis);

        int frameIndex = -1;
        for (int index = 0; index < frames.size(); index++) {
            if (frames.get(index).id().equals(expectedId)) {
                frameIndex = index;
                break;
            }
        }
        if (frameIndex < 0) {
            throw new IllegalArgumentException("Unknown live frame " + expectedId);
        }
        PlanFrame previous = frames.get(frameIndex);
        if (previous.state() != PlanFrameState.ACTIVE
                && previous.state() != PlanFrameState.WAITING_ON_CHILD) {
            throw new IllegalStateException(
                    "Frame cannot start a new generation from " + previous.state());
        }

        String explanation = reason == null || reason.isBlank()
                ? "nested execution state reconciled"
                : reason;
        PlanFrame.Spec rebasedSpec = new PlanFrame.Spec(
                previous.kind(), previous.target(), previous.count(), parameters);
        PlanFrame rebased = PlanFrame.create(rebasedSpec, nowMillis)
                .transition(PlanFrameState.ACTIVE, explanation, nowMillis);

        List<PlanFrame> candidateFrames = new ArrayList<>(frames.subList(0, frameIndex));
        candidateFrames.add(rebased);
        new TaskPlan(
                missionId, rootSpec, candidateFrames, state, detail,
                createdAtMillis, Math.max(updatedAtMillis, nowMillis), revision);

        frames.subList(frameIndex, frames.size()).clear();
        frames.add(rebased);
        mutate("rebased " + previous.displayName() + ": " + explanation, nowMillis);
    }

    /** Records leaf progress while preserving its stable identity and execution state. */
    public void checkpointCurrent(
            java.util.Map<String, String> parameters,
            String frameDetail,
            long nowMillis) {
        requireOpen();
        int current = frames.size() - 1;
        PlanFrame frame = frames.get(current);
        if (frame.state() != PlanFrameState.PENDING && frame.state() != PlanFrameState.ACTIVE) {
            throw new IllegalStateException("Current frame cannot checkpoint from " + frame.state());
        }
        frames.set(current, frame.checkpoint(parameters, frameDetail, nowMillis));
        mutate("checkpoint: " + frame.displayName(), nowMillis);
    }

    /** Atomically commits a server-confirmed fact to both root and active leaf. */
    public void checkpointRootAndCurrent(
            Map<String, String> rootParameters,
            Map<String, String> currentParameters,
            String frameDetail,
            long nowMillis) {
        requireOpen();
        if (frames.size() < 2) {
            throw new IllegalStateException("Root/current checkpoint requires an active child frame");
        }
        int current = frames.size() - 1;
        PlanFrame leaf = frames.get(current);
        if (leaf.state() != PlanFrameState.PENDING && leaf.state() != PlanFrameState.ACTIVE) {
            throw new IllegalStateException("Current frame cannot checkpoint from " + leaf.state());
        }
        PlanFrame root = frames.getFirst();
        frames.set(0, root.checkpoint(rootParameters, frameDetail, nowMillis));
        frames.set(current, leaf.checkpoint(currentParameters, frameDetail, nowMillis));
        mutate("root/leaf checkpoint: " + leaf.displayName(), nowMillis);
    }

    /** Atomically checkpoints any set of live frames in a nested plan. */
    public void checkpointFrames(
            Map<String, Map<String, String>> parametersByFrameId,
            String frameDetail,
            long nowMillis) {
        requireOpen();
        Objects.requireNonNull(parametersByFrameId, "parametersByFrameId");
        if (parametersByFrameId.isEmpty()) {
            throw new IllegalArgumentException("at least one frame checkpoint is required");
        }
        LinkedHashMap<String, Integer> indices = new LinkedHashMap<>();
        for (int index = 0; index < frames.size(); index++) {
            indices.put(frames.get(index).id(), index);
        }
        for (Map.Entry<String, Map<String, String>> update : parametersByFrameId.entrySet()) {
            Integer index = indices.get(update.getKey());
            if (index == null) {
                throw new IllegalArgumentException("Unknown live frame " + update.getKey());
            }
            PlanFrame frame = frames.get(index);
            frames.set(index, frame.checkpoint(
                    Objects.requireNonNull(update.getValue(), "frame parameters"),
                    frameDetail,
                    nowMillis));
        }
        mutate("multi-frame checkpoint: "
                + Objects.requireNonNullElse(frameDetail, "progress"), nowMillis);
    }

    /** Abandons all remaining frames without claiming mission success. */
    public void clear(String reason, long nowMillis) {
        requireOpen();
        frames.clear();
        state = TaskPlanState.CLEARED;
        mutate(Objects.requireNonNullElse(reason, "plan cleared"), nowMillis);
    }

    /**
     * Converts a terminal executable record into bounded evidence only.
     *
     * <p>The caller must first durably commit the terminal outcome to the
     * owning mission, Home, or recovery journal. Keeping this transition
     * separate makes a crash conservative: an unacknowledged terminal plan is
     * retained, while a retired plan can never be resumed as executable work.</p>
     */
    public void retireAfterOwnerCommit(String ownerReceipt, long nowMillis) {
        if (state == TaskPlanState.OPEN) {
            throw new IllegalStateException(
                    "Open plan for " + missionId + " cannot be retired");
        }
        if (state == TaskPlanState.RETIRED) return;
        String receipt = requireText(ownerReceipt, "ownerReceipt");
        TaskPlanState terminalState = state;
        String terminalDetail = detail;
        state = TaskPlanState.RETIRED;
        mutate("retired " + terminalState.name().toLowerCase()
                + " after owner commit " + receipt
                + (terminalDetail.isBlank() ? "" : "; " + terminalDetail), nowMillis);
    }

    public Optional<PlanFrame> currentFrame() {
        if (frames.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(frames.get(frames.size() - 1));
    }

    /** Finds the nearest live ancestor-or-self carrying a named durable fact. */
    public Optional<PlanFrame> nearestAncestorWithParameter(
            String descendantFrameId,
            String parameterKey) {
        String descendantId = requireText(descendantFrameId, "descendantFrameId");
        String key = requireText(parameterKey, "parameterKey");
        int descendantIndex = -1;
        for (int index = 0; index < frames.size(); index++) {
            if (frames.get(index).id().equals(descendantId)) {
                descendantIndex = index;
                break;
            }
        }
        if (descendantIndex < 0) {
            throw new IllegalArgumentException("Unknown live frame " + descendantId);
        }
        for (int index = descendantIndex; index >= 0; index--) {
            PlanFrame candidate = frames.get(index);
            if (candidate.parameters().containsKey(key)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Root-to-leaf immutable snapshot. */
    public List<PlanFrame> frames() {
        return Collections.unmodifiableList(new ArrayList<>(frames));
    }

    public String summary() {
        StringBuilder result = new StringBuilder();
        result.append("mission ").append(missionId)
                .append(" [").append(state.name().toLowerCase())
                .append(", revision ").append(revision).append(']');
        if (frames.isEmpty()) {
            if (!detail.isBlank()) {
                result.append(' ').append(detail);
            }
            return result.toString();
        }
        result.append(' ');
        for (int index = 0; index < frames.size(); index++) {
            if (index > 0) {
                result.append(" -> ");
            }
            PlanFrame frame = frames.get(index);
            result.append(frame.displayName())
                    .append(" (").append(frame.state().name().toLowerCase());
            if (!frame.detail().isBlank()) {
                result.append(": ").append(frame.detail());
            }
            result.append(')');
        }
        return result.toString();
    }

    public void validate() {
        Objects.requireNonNull(rootSpec, "rootSpec");
        if (createdAtMillis < 0 || updatedAtMillis < createdAtMillis || revision < 1) {
            throw new IllegalStateException("Invalid plan metadata for " + missionId);
        }
        if (state == TaskPlanState.OPEN && frames.isEmpty()) {
            throw new IllegalStateException("Open plan must contain a frame");
        }
        if (state.terminal() && !frames.isEmpty()) {
            throw new IllegalStateException("Terminal plan must not contain frames");
        }
        Set<String> frameIds = new HashSet<>();
        for (int index = 0; index < frames.size(); index++) {
            PlanFrame frame = Objects.requireNonNull(frames.get(index), "frame");
            if (!frameIds.add(frame.id())) {
                throw new IllegalStateException("Duplicate frame id " + frame.id());
            }
            boolean leaf = index == frames.size() - 1;
            if (!leaf && frame.state() != PlanFrameState.WAITING_ON_CHILD) {
                throw new IllegalStateException("Non-leaf frame must wait on its child");
            }
            if (leaf && frame.state() != PlanFrameState.PENDING
                    && frame.state() != PlanFrameState.ACTIVE) {
                throw new IllegalStateException("Leaf frame must be pending or active");
            }
        }
        if (!frames.isEmpty()) {
            PlanFrame root = frames.getFirst();
            if (!root.kind().equals(rootSpec.kind())
                    || !root.target().equals(rootSpec.target())
                    || root.count() != rootSpec.count()) {
                throw new IllegalStateException("Root frame diverged from its declarative objective");
            }
            for (Map.Entry<String, String> intent : rootSpec.parameters().entrySet()) {
                if (!intent.getValue().equals(root.parameters().get(intent.getKey()))) {
                    throw new IllegalStateException(
                            "Root frame changed declarative parameter " + intent.getKey());
                }
            }
        }
    }

    private void mutate(String nextDetail, long nowMillis) {
        PlanFrame.requireTimestamp(nowMillis);
        detail = Objects.requireNonNullElse(nextDetail, "");
        updatedAtMillis = Math.max(updatedAtMillis, nowMillis);
        revision++;
        validate();
    }

    private void requireOpen() {
        if (state != TaskPlanState.OPEN) {
            throw new IllegalStateException("Plan for " + missionId + " is already " + state);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static PlanFrame.Spec copySpec(PlanFrame.Spec spec) {
        Objects.requireNonNull(spec, "rootSpec");
        return new PlanFrame.Spec(spec.kind(), spec.target(), spec.count(), spec.parameters());
    }

    public String missionId() { return missionId; }
    public PlanFrame.Spec rootSpec() { return copySpec(rootSpec); }
    public TaskPlanState state() { return state; }
    public String detail() { return detail; }
    public long createdAtMillis() { return createdAtMillis; }
    public long updatedAtMillis() { return updatedAtMillis; }
    public long revision() { return revision; }
}
