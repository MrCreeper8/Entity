package dev.entity.core.plan;

import dev.entity.core.persistence.PlanStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Transactional in-memory index over a {@link PlanStore}. Every mutation is
 * durably saved before it replaces the visible plan snapshot.
 */
public final class TaskPlanCoordinator {
    public static final int DEFAULT_RETIRED_SUMMARY_LIMIT = 64;

    private final PlanStore store;
    private final Map<String, TaskPlan> plans = new LinkedHashMap<>();

    public TaskPlanCoordinator(PlanStore store) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        for (TaskPlan loaded : store.load()) {
            TaskPlan copy = loaded.copy();
            if (plans.putIfAbsent(copy.missionId(), copy) != null) {
                throw new IOException("Duplicate task plan for mission " + copy.missionId());
            }
        }
    }

    /** Creates and persists a plan, or returns the already-restored plan for that mission. */
    public synchronized TaskPlan createOrRestore(
            String missionId,
            PlanFrame.Spec root,
            long nowMillis) throws IOException {
        return createOrRestore(missionId, root, root.parameters(), nowMillis);
    }

    /** Creates the declarative root and its initial mutable checkpoint in one store commit. */
    public synchronized TaskPlan createOrRestore(
            String missionId,
            PlanFrame.Spec root,
            Map<String, String> initialRootParameters,
            long nowMillis) throws IOException {
        TaskPlan existing = plans.get(missionId);
        if (existing != null) {
            return existing.copy();
        }
        TaskPlan created = TaskPlan.create(
                missionId, root,
                Objects.requireNonNull(initialRootParameters, "initialRootParameters"),
                nowMillis);
        LinkedHashMap<String, TaskPlan> candidate = copyIndex();
        candidate.put(created.missionId(), created);
        commit(candidate);
        return created.copy();
    }

    /** Returns a defensive snapshot of a restored per-mission plan. */
    public synchronized Optional<TaskPlan> restore(String missionId) {
        TaskPlan plan = plans.get(missionId);
        return plan == null ? Optional.empty() : Optional.of(plan.copy());
    }

    public synchronized List<TaskPlan> plans() {
        return plans.values().stream().map(TaskPlan::copy).toList();
    }

    public TaskPlan activate(String missionId, String detail, long nowMillis) throws IOException {
        return mutate(missionId, plan -> plan.activate(detail, nowMillis));
    }

    public TaskPlan pushPrerequisite(
            String missionId,
            PlanFrame.Spec prerequisite,
            String reason,
            long nowMillis) throws IOException {
        return mutate(missionId, plan -> plan.pushPrerequisite(prerequisite, reason, nowMillis));
    }

    public TaskPlan completeCurrent(
            String missionId,
            String detail,
            long nowMillis) throws IOException {
        return mutate(missionId, plan -> plan.completeCurrent(detail, nowMillis));
    }

    /**
     * Durably completes an identity-fenced active child and checkpoints its
     * immediate parent in one store transaction.
     */
    public TaskPlan completeCurrentAndCheckpointParent(
            String missionId,
            String expectedChildFrameId,
            Map<String, String> parentParameters,
            String completionDetail,
            String parentDetail,
            long nowMillis) throws IOException {
        Map<String, String> snapshot = Map.copyOf(
                Objects.requireNonNull(parentParameters, "parentParameters"));
        return mutate(missionId, plan -> plan.completeCurrentAndCheckpointParent(
                expectedChildFrameId,
                snapshot,
                completionDetail,
                parentDetail,
                nowMillis));
    }

    public TaskPlan replaceCurrent(
            String missionId,
            PlanFrame.Spec replacement,
            String reason,
            long nowMillis) throws IOException {
        return mutate(missionId, plan -> plan.replaceCurrent(replacement, reason, nowMillis));
    }

    public TaskPlan checkpointCurrent(
            String missionId,
            Map<String, String> parameters,
            String detail,
            long nowMillis) throws IOException {
        Map<String, String> snapshot = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        return mutate(missionId, plan -> plan.checkpointCurrent(snapshot, detail, nowMillis));
    }

    public TaskPlan checkpointRootAndCurrent(
            String missionId,
            Map<String, String> rootParameters,
            Map<String, String> currentParameters,
            String detail,
            long nowMillis) throws IOException {
        Map<String, String> rootSnapshot = Map.copyOf(
                Objects.requireNonNull(rootParameters, "rootParameters"));
        Map<String, String> currentSnapshot = Map.copyOf(
                Objects.requireNonNull(currentParameters, "currentParameters"));
        return mutate(missionId, plan -> plan.checkpointRootAndCurrent(
                rootSnapshot, currentSnapshot, detail, nowMillis));
    }

    public TaskPlan checkpointFrames(
            String missionId,
            Map<String, Map<String, String>> parametersByFrameId,
            String detail,
            long nowMillis) throws IOException {
        Objects.requireNonNull(parametersByFrameId, "parametersByFrameId");
        LinkedHashMap<String, Map<String, String>> snapshot = new LinkedHashMap<>();
        parametersByFrameId.forEach((frameId, parameters) -> snapshot.put(
                Objects.requireNonNull(frameId, "frameId"),
                Map.copyOf(Objects.requireNonNull(parameters, "frame parameters"))));
        Map<String, Map<String, String>> immutable = Map.copyOf(snapshot);
        return mutate(missionId, plan -> plan.checkpointFrames(
                immutable, detail, nowMillis));
    }

    /**
     * Atomically starts a fresh root execution generation. The store is
     * committed before the rebased plan becomes visible in memory, so a
     * failed write leaves the previous root/child stack untouched.
     */
    public TaskPlan rebaseToRoot(
            String missionId,
            Map<String, String> confirmedRootFacts,
            String reason,
            long nowMillis) throws IOException {
        Map<String, String> snapshot = Map.copyOf(
                Objects.requireNonNull(confirmedRootFacts, "confirmedRootFacts"));
        return mutate(missionId, plan -> plan.rebaseToRoot(snapshot, reason, nowMillis));
    }

    /** Atomically rebuilds one nested frame generation without losing its parents. */
    public TaskPlan rebaseToFrame(
            String missionId,
            String expectedFrameId,
            Map<String, String> parameters,
            String reason,
            long nowMillis) throws IOException {
        Map<String, String> snapshot = Map.copyOf(
                Objects.requireNonNull(parameters, "parameters"));
        return mutate(missionId, plan -> plan.rebaseToFrame(
                expectedFrameId, snapshot, reason, nowMillis));
    }

    public TaskPlan clear(String missionId, String reason, long nowMillis) throws IOException {
        return mutate(missionId, plan -> plan.clear(reason, nowMillis));
    }

    /**
     * Retires a terminal plan only after its owning journal has committed the
     * outcome, then bounds retained evidence in the same PlanStore commit.
     *
     * <p>If persistence fails, the visible in-memory plan remains in its prior
     * terminal state. The owner journal may therefore safely retry retirement
     * without risking resurrection or loss of executable state.</p>
     */
    public TaskPlan retireTerminalAfterOwnerCommit(
            String missionId,
            String ownerReceipt,
            long nowMillis) throws IOException {
        return retireTerminalAfterOwnerCommit(
                missionId, ownerReceipt, nowMillis, DEFAULT_RETIRED_SUMMARY_LIMIT);
    }

    /** Exposed for deterministic retention-bound tests and explicit policy changes. */
    public synchronized TaskPlan retireTerminalAfterOwnerCommit(
            String missionId,
            String ownerReceipt,
            long nowMillis,
            int retainedSummaryLimit) throws IOException {
        if (retainedSummaryLimit < 1) {
            throw new IllegalArgumentException("retainedSummaryLimit must be positive");
        }
        TaskPlan candidatePlan = requirePlan(missionId).copy();
        candidatePlan.retireAfterOwnerCommit(ownerReceipt, nowMillis);

        LinkedHashMap<String, TaskPlan> candidate = copyIndex();
        candidate.put(missionId, candidatePlan);

        List<TaskPlan> otherRetired = candidate.values().stream()
                .filter(plan -> plan.state() == TaskPlanState.RETIRED)
                .filter(plan -> !plan.missionId().equals(missionId))
                .sorted(java.util.Comparator
                        .comparingLong(TaskPlan::updatedAtMillis).reversed()
                        .thenComparing(TaskPlan::missionId))
                .toList();
        int otherLimit = retainedSummaryLimit - 1;
        for (int index = otherLimit; index < otherRetired.size(); index++) {
            candidate.remove(otherRetired.get(index).missionId());
        }
        commit(candidate);
        return candidatePlan.copy();
    }

    public synchronized String summary(String missionId) {
        return requirePlan(missionId).summary();
    }

    private synchronized TaskPlan mutate(String missionId, Consumer<TaskPlan> operation) throws IOException {
        TaskPlan candidatePlan = requirePlan(missionId).copy();
        operation.accept(candidatePlan);
        LinkedHashMap<String, TaskPlan> candidate = copyIndex();
        candidate.put(missionId, candidatePlan);
        commit(candidate);
        return candidatePlan.copy();
    }

    private TaskPlan requirePlan(String missionId) {
        TaskPlan plan = plans.get(missionId);
        if (plan == null) {
            throw new IllegalArgumentException("Unknown mission plan " + missionId);
        }
        return plan;
    }

    private LinkedHashMap<String, TaskPlan> copyIndex() {
        LinkedHashMap<String, TaskPlan> copy = new LinkedHashMap<>();
        plans.forEach((missionId, plan) -> copy.put(missionId, plan.copy()));
        return copy;
    }

    private void commit(LinkedHashMap<String, TaskPlan> candidate) throws IOException {
        store.save(new ArrayList<>(candidate.values()));
        plans.clear();
        candidate.forEach((missionId, plan) -> plans.put(missionId, plan.copy()));
    }
}
