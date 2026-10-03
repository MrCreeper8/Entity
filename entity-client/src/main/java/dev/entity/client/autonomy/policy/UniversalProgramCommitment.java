package dev.entity.client.autonomy.policy;

import dev.entity.client.autonomy.policy.AcquisitionRequest.ItemGoal;
import dev.entity.client.autonomy.policy.ResourcePlanner.Action;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable commitment to one complete universal-acquisition program.
 *
 * <p>The compiler is allowed to be expensive and globally reason about later
 * work. Once that compilation is accepted, ordinary ticks must not silently
 * replace it with a fresh first action. A committed program advances only from
 * verified completion evidence for its current action. Recompilation becomes
 * legal only after an explicit, typed divergence such as lost inventory or a
 * lost workstation.</p>
 *
 * <p>This class is deliberately independent of Minecraft and the executor. It
 * is the durable state-machine contract that a later runtime integration can
 * serialize in the mission root.</p>
 */
public final class UniversalProgramCommitment {
    public enum Status {
        ACTIVE,
        COMPLETE,
        INVALIDATED
    }

    public enum Evidence {
        STILL_RUNNING,
        VERIFIED_COMPLETE,
        EXPLICIT_DIVERGENCE
    }

    public enum DivergenceCause {
        INVENTORY_LOSS,
        TOOL_LOSS,
        WORKSTATION_LOSS,
        RECIPE_CATALOG_CHANGED,
        VERIFIED_ACTION_FAILURE,
        EXTERNAL_WORLD_CHANGE
    }

    public enum Disposition {
        STABLE,
        ADVANCED,
        COMPLETED,
        INVALIDATED,
        STALE_EVIDENCE,
        TERMINAL
    }

    /** Every planning input that can change a selected production strategy. */
    public record PlanningBaseline(
            Map<String, Integer> itemCounts,
            Set<String> equippedItems,
            Set<String> locallyAvailableItems,
            Set<String> availableStations,
            Map<String, Integer> usableToolDurability) {
        public PlanningBaseline {
            itemCounts = normalizedCounts(itemCounts, "itemCounts");
            equippedItems = normalizedItems(equippedItems, "equippedItems");
            locallyAvailableItems = normalizedItems(
                    locallyAvailableItems, "locallyAvailableItems");
            availableStations = normalizedItems(
                    availableStations, "availableStations");
            usableToolDurability = normalizedCounts(
                    usableToolDurability, "usableToolDurability");
        }

        public PlanningBaseline(Map<String, Integer> itemCounts) {
            this(itemCounts, Set.of(), Set.of(), Set.of(), Map.of());
        }

        public PlanningBaseline(
                Map<String, Integer> itemCounts,
                Set<String> equippedItems,
                Set<String> locallyAvailableItems,
                Map<String, Integer> usableToolDurability) {
            this(itemCounts, equippedItems, locallyAvailableItems,
                    Set.of(), usableToolDurability);
        }

        public static PlanningBaseline from(
                ResourcePlanner.InventoryView inventory,
                Map<String, Integer> usableToolDurability) {
            Objects.requireNonNull(inventory, "inventory");
            return new PlanningBaseline(
                    inventory.itemCounts(), inventory.equippedItems(),
                    inventory.locallyAvailableItems(), inventory.availableStations(),
                    usableToolDurability);
        }
    }

    /** Auditable identity of the exact accepted planning decision. */
    public record Identity(
            String catalogFingerprint,
            String goalsFingerprint,
            String baselineFingerprint,
            String actionsFingerprint,
            String commitmentFingerprint) {
        public Identity {
            catalogFingerprint = requireText(catalogFingerprint, "catalogFingerprint");
            goalsFingerprint = requireDigest(goalsFingerprint, "goalsFingerprint");
            baselineFingerprint = requireDigest(baselineFingerprint, "baselineFingerprint");
            actionsFingerprint = requireDigest(actionsFingerprint, "actionsFingerprint");
            commitmentFingerprint = requireDigest(
                    commitmentFingerprint, "commitmentFingerprint");
        }
    }

    /** Explicit reason that makes a new compilation legal. */
    public record Invalidation(
            DivergenceCause cause,
            int actionIndex,
            String actionToken,
            String detail) {
        public Invalidation {
            cause = Objects.requireNonNull(cause, "cause");
            if (actionIndex < 0) throw new IllegalArgumentException("actionIndex cannot be negative");
            actionToken = requireDigest(actionToken, "actionToken");
            detail = requireText(detail, "detail");
        }
    }

    /** Evidence must be bound to the action token that produced it. */
    public record Observation(
            String actionToken,
            Evidence evidence,
            DivergenceCause divergenceCause,
            String detail) {
        public Observation {
            actionToken = requireDigest(actionToken, "actionToken");
            evidence = Objects.requireNonNull(evidence, "evidence");
            detail = requireText(detail, "detail");
            if (evidence == Evidence.EXPLICIT_DIVERGENCE) {
                divergenceCause = Objects.requireNonNull(
                        divergenceCause, "divergenceCause");
            } else if (divergenceCause != null) {
                throw new IllegalArgumentException(
                        "only explicit divergence evidence may name a cause");
            }
        }

        public static Observation running(String actionToken, String detail) {
            return new Observation(actionToken, Evidence.STILL_RUNNING, null, detail);
        }

        public static Observation verifiedComplete(String actionToken, String detail) {
            return new Observation(actionToken, Evidence.VERIFIED_COMPLETE, null, detail);
        }

        public static Observation diverged(
                String actionToken,
                DivergenceCause cause,
                String detail) {
            return new Observation(
                    actionToken, Evidence.EXPLICIT_DIVERGENCE, cause, detail);
        }
    }

    public record Decision(Program program, Disposition disposition, String detail) {
        public Decision {
            program = Objects.requireNonNull(program, "program");
            disposition = Objects.requireNonNull(disposition, "disposition");
            detail = requireText(detail, "detail");
        }

        /** True only after explicit divergence has invalidated the commitment. */
        public boolean recompilePermitted() {
            return program.requiresRecompile();
        }
    }

    public static final class Program {
        private final Identity identity;
        private final List<ItemGoal> goals;
        private final PlanningBaseline baseline;
        private final List<Action> actions;
        private final int cursor;
        private final Status status;
        private final Invalidation invalidation;

        private Program(
                Identity identity,
                List<ItemGoal> goals,
                PlanningBaseline baseline,
                List<Action> actions,
                int cursor,
                Status status,
                Invalidation invalidation) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.goals = checkedGoals(goals);
            this.baseline = Objects.requireNonNull(baseline, "baseline");
            this.actions = checkedActions(actions);
            this.cursor = cursor;
            this.status = Objects.requireNonNull(status, "status");
            this.invalidation = invalidation;
            validateState();
        }

        public Identity identity() {
            return identity;
        }

        public List<ItemGoal> goals() {
            return goals;
        }

        public PlanningBaseline baseline() {
            return baseline;
        }

        public List<Action> actions() {
            return actions;
        }

        public int cursor() {
            return cursor;
        }

        public Status status() {
            return status;
        }

        public Optional<Invalidation> invalidation() {
            return Optional.ofNullable(invalidation);
        }

        public Optional<Action> currentAction() {
            return status == Status.ACTIVE
                    ? Optional.of(actions.get(cursor)) : Optional.empty();
        }

        public Optional<String> currentActionToken() {
            return currentAction().map(action -> actionToken(
                    identity.commitmentFingerprint(), cursor, action));
        }

        public List<Action> remainingActions() {
            return status == Status.ACTIVE
                    ? List.copyOf(actions.subList(cursor, actions.size())) : List.of();
        }

        public boolean requiresRecompile() {
            return status == Status.INVALIDATED;
        }

        /**
         * Explicitly invalidates either the current action or the completed
         * program boundary. The latter covers verified cargo loss after the
         * final production action (for example, unrecoverable death drops).
         */
        public Decision invalidate(DivergenceCause cause, String detail) {
            Objects.requireNonNull(cause, "cause");
            String checkedDetail = requireText(detail, "detail");
            if (status == Status.INVALIDATED) {
                return new Decision(this, Disposition.TERMINAL,
                        "committed program is already invalidated");
            }
            String token = status == Status.ACTIVE
                    ? currentActionToken().orElseThrow()
                    : completionToken(identity.commitmentFingerprint(), actions.size());
            Invalidation reason = new Invalidation(cause, cursor, token, checkedDetail);
            Program next = new Program(
                    identity, goals, baseline, actions, cursor,
                    Status.INVALIDATED, reason);
            return new Decision(
                    next, Disposition.INVALIDATED,
                    "explicit divergence permits recompilation: " + checkedDetail);
        }

        /** Includes the current action and every later committed action. */
        public int remainingWorkstationUses(String workstation) {
            if (status != Status.ACTIVE) return 0;
            String wanted = normalizeItem(workstation);
            int uses = 0;
            for (int index = cursor; index < actions.size(); index++) {
                if (wanted.equals(actionWorkstation(actions.get(index)))) uses++;
            }
            return uses;
        }

        /** Baseline capabilities which still have a current/future non-consumable consumer. */
        public Set<String> remainingRequiredStations() {
            if (status != Status.ACTIVE) return Set.of();
            LinkedHashSet<String> required = new LinkedHashSet<>();
            for (int index = cursor; index < actions.size(); index++) {
                String workstation = actionWorkstation(actions.get(index));
                if (workstation != null && baseline.availableStations().contains(workstation)) {
                    required.add(workstation);
                }
            }
            return Collections.unmodifiableSet(required);
        }

        /** Excludes the current action and searches the full non-adjacent future. */
        public boolean hasFutureWorkstationUse(String workstation) {
            if (status != Status.ACTIVE) return false;
            String wanted = normalizeItem(workstation);
            for (int index = cursor + 1; index < actions.size(); index++) {
                if (wanted.equals(actionWorkstation(actions.get(index)))) return true;
            }
            return false;
        }

        /** Whether the current production leaf must leave its station deployed. */
        public boolean retainCurrentWorkstation() {
            if (status != Status.ACTIVE) return false;
            String station = actionWorkstation(actions.get(cursor));
            return station != null && hasFutureWorkstationUse(station);
        }

        public Decision observe(Observation observation) {
            Objects.requireNonNull(observation, "observation");
            if (status != Status.ACTIVE) {
                return new Decision(this, Disposition.TERMINAL,
                        "committed program is already " + status.name().toLowerCase(Locale.ROOT));
            }

            String expected = currentActionToken().orElseThrow();
            if (!expected.equals(observation.actionToken())) {
                return new Decision(this, Disposition.STALE_EVIDENCE,
                        "evidence belongs to a different committed action");
            }

            return switch (observation.evidence()) {
                case STILL_RUNNING -> new Decision(
                        this, Disposition.STABLE,
                        "current action remains committed: " + observation.detail());
                case VERIFIED_COMPLETE -> advance(observation.detail());
                case EXPLICIT_DIVERGENCE -> invalidate(
                        observation.divergenceCause(), expected, observation.detail());
            };
        }

        private Decision advance(String detail) {
            int nextCursor = Math.addExact(cursor, 1);
            Status nextStatus = nextCursor == actions.size() ? Status.COMPLETE : Status.ACTIVE;
            Program next = new Program(
                    identity, goals, baseline, actions, nextCursor, nextStatus, null);
            return new Decision(
                    next,
                    nextStatus == Status.COMPLETE
                            ? Disposition.COMPLETED : Disposition.ADVANCED,
                    "verified action " + cursor + " completed: " + detail);
        }

        private Decision invalidate(
                DivergenceCause cause,
                String actionToken,
                String detail) {
            Invalidation reason = new Invalidation(cause, cursor, actionToken, detail);
            Program next = new Program(
                    identity, goals, baseline, actions, cursor,
                    Status.INVALIDATED, reason);
            return new Decision(
                    next, Disposition.INVALIDATED,
                    "explicit divergence permits recompilation: " + detail);
        }

        private void validateState() {
            if (cursor < 0 || cursor > actions.size()) {
                throw new IllegalArgumentException("cursor is outside the action program");
            }
            switch (status) {
                case ACTIVE -> {
                    if (cursor >= actions.size() || invalidation != null) {
                        throw new IllegalArgumentException(
                                "active program needs a current action and no invalidation");
                    }
                }
                case COMPLETE -> {
                    if (cursor != actions.size() || invalidation != null) {
                        throw new IllegalArgumentException(
                                "complete program must end at the action boundary");
                    }
                }
                case INVALIDATED -> {
                    if (cursor > actions.size() || invalidation == null
                            || invalidation.actionIndex() != cursor) {
                        throw new IllegalArgumentException(
                                "invalidated program needs a matching action/boundary reason");
                    }
                    String expectedToken = cursor == actions.size()
                            ? completionToken(identity.commitmentFingerprint(), cursor)
                            : actionToken(
                            identity.commitmentFingerprint(), cursor, actions.get(cursor));
                    if (!expectedToken.equals(invalidation.actionToken())) {
                        throw new IllegalArgumentException(
                                "invalidated program reason belongs to another action");
                    }
                }
            }
        }
    }

    private UniversalProgramCommitment() {
    }

    public static Program commit(
            String catalogFingerprint,
            AcquisitionPlan plan,
            PlanningBaseline baseline) {
        Objects.requireNonNull(plan, "plan");
        return commit(catalogFingerprint, plan.goals(), baseline, plan.actions());
    }

    public static Program commit(
            String catalogFingerprint,
            List<ItemGoal> goals,
            PlanningBaseline baseline,
            List<Action> actions) {
        String catalog = requireText(catalogFingerprint, "catalogFingerprint");
        List<ItemGoal> checkedGoals = checkedGoals(goals);
        PlanningBaseline checkedBaseline = Objects.requireNonNull(baseline, "baseline");
        List<Action> checkedActions = checkedActions(actions);

        String goalsFingerprint = goalsFingerprint(checkedGoals);
        String baselineFingerprint = baselineFingerprint(checkedBaseline);
        String actionsFingerprint = actionsFingerprint(checkedActions);
        String commitmentFingerprint = commitmentFingerprint(
                catalog, goalsFingerprint, baselineFingerprint, actionsFingerprint);
        Identity identity = new Identity(
                catalog, goalsFingerprint, baselineFingerprint,
                actionsFingerprint, commitmentFingerprint);
        Status status = checkedActions.isEmpty() ? Status.COMPLETE : Status.ACTIVE;
        return new Program(
                identity, checkedGoals, checkedBaseline, checkedActions,
                0, status, null);
    }

    /**
     * Restores a durably encoded program without trusting its encoded identity.
     * Every fingerprint is recomputed from the executable inputs and compared
     * with the commitment accepted before the crash/reconnect boundary.
     */
    public static Program restore(
            String catalogFingerprint,
            List<ItemGoal> goals,
            PlanningBaseline baseline,
            List<Action> actions,
            int cursor,
            Status status,
            Invalidation invalidation,
            String expectedCommitmentFingerprint) {
        Program committed = commit(catalogFingerprint, goals, baseline, actions);
        String expected = requireDigest(
                expectedCommitmentFingerprint, "expectedCommitmentFingerprint");
        if (!committed.identity().commitmentFingerprint().equals(expected)) {
            throw new IllegalArgumentException(
                    "durable universal program fingerprint does not match its executable payload");
        }
        return new Program(
                committed.identity(), committed.goals(), committed.baseline(),
                committed.actions(), cursor, Objects.requireNonNull(status, "status"),
                invalidation);
    }

    /**
     * Authenticates the released E2PG-v1 identity, then migrates it to the v2 baseline identity
     * with an empty non-consumable-station set. Cursor is preserved, but every legacy program is
     * returned typed INVALIDATED so plans compiled under the old synthetic-station semantics can
     * never resume actuation. An old invalidation reason is retokened only after its legacy token
     * has been verified.
     */
    static Program restoreLegacyV1(
            String catalogFingerprint,
            List<ItemGoal> goals,
            PlanningBaseline baseline,
            List<Action> actions,
            int cursor,
            Status status,
            Invalidation invalidation,
            String expectedCommitmentFingerprint) {
        String catalog = requireText(catalogFingerprint, "catalogFingerprint");
        List<ItemGoal> checkedGoals = checkedGoals(goals);
        PlanningBaseline checkedBaseline = Objects.requireNonNull(baseline, "baseline");
        if (!checkedBaseline.availableStations().isEmpty()) {
            throw new IllegalArgumentException(
                    "legacy universal baseline cannot contain available stations");
        }
        List<Action> checkedActions = checkedActions(actions);
        String goalsFingerprint = goalsFingerprint(checkedGoals);
        String baselineFingerprint = legacyBaselineFingerprint(checkedBaseline);
        String actionsFingerprint = actionsFingerprint(checkedActions);
        String legacyCommitment = commitmentFingerprint(
                catalog, goalsFingerprint, baselineFingerprint, actionsFingerprint);
        if (!legacyCommitment.equals(requireDigest(
                expectedCommitmentFingerprint, "expectedCommitmentFingerprint"))) {
            throw new IllegalArgumentException(
                    "legacy durable universal program fingerprint does not match its executable payload");
        }

        Identity legacyIdentity = new Identity(
                catalog, goalsFingerprint, baselineFingerprint,
                actionsFingerprint, legacyCommitment);
        // Construction authenticates the old current/completion token before any migration.
        new Program(
                legacyIdentity, checkedGoals, checkedBaseline, checkedActions,
                cursor, Objects.requireNonNull(status, "status"), invalidation);

        Program migratedIdentity = commit(
                catalog, checkedGoals, checkedBaseline, checkedActions);
        String token = cursor == checkedActions.size()
                ? completionToken(
                migratedIdentity.identity().commitmentFingerprint(), cursor)
                : actionToken(
                migratedIdentity.identity().commitmentFingerprint(), cursor,
                checkedActions.get(cursor));
        Invalidation migratedInvalidation = status == Status.INVALIDATED
                ? new Invalidation(
                Objects.requireNonNull(invalidation, "invalidation").cause(), cursor, token,
                invalidation.detail())
                : new Invalidation(
                DivergenceCause.EXTERNAL_WORLD_CHANGE, cursor, token,
                "authenticated E2PG-v1 program requires one v2 recompile");
        return new Program(
                migratedIdentity.identity(), checkedGoals, checkedBaseline, checkedActions,
                cursor, Status.INVALIDATED, migratedInvalidation);
    }

    private static String goalsFingerprint(List<ItemGoal> goals) {
        return digest(writer -> {
            writer.put("goals-v1");
            writer.put(goals.size());
            for (ItemGoal goal : goals) {
                writer.put(normalizeItem(goal.item()));
                writer.put(goal.count());
            }
        });
    }

    private static String baselineFingerprint(PlanningBaseline baseline) {
        return digest(writer -> {
            writer.put("baseline-v2");
            putCounts(writer, baseline.itemCounts());
            putItems(writer, baseline.equippedItems());
            putItems(writer, baseline.locallyAvailableItems());
            putItems(writer, baseline.availableStations());
            putCounts(writer, baseline.usableToolDurability());
        });
    }

    private static String legacyBaselineFingerprint(PlanningBaseline baseline) {
        return digest(writer -> {
            writer.put("baseline-v1");
            putCounts(writer, baseline.itemCounts());
            putItems(writer, baseline.equippedItems());
            putItems(writer, baseline.locallyAvailableItems());
            putCounts(writer, baseline.usableToolDurability());
        });
    }

    private static String commitmentFingerprint(
            String catalog,
            String goalsFingerprint,
            String baselineFingerprint,
            String actionsFingerprint) {
        return digest(writer -> {
            writer.put("entity2-universal-program-v1");
            writer.put(catalog);
            writer.put(goalsFingerprint);
            writer.put(baselineFingerprint);
            writer.put(actionsFingerprint);
        });
    }

    private static String actionsFingerprint(List<Action> actions) {
        return digest(writer -> {
            writer.put("actions-v1");
            writer.put(actions.size());
            for (Action action : actions) putAction(writer, action);
        });
    }

    private static String actionToken(
            String commitmentFingerprint,
            int index,
            Action action) {
        return digest(writer -> {
            writer.put("action-token-v1");
            writer.put(commitmentFingerprint);
            writer.put(index);
            putAction(writer, action);
        });
    }

    private static String completionToken(String commitmentFingerprint, int actionCount) {
        return digest(writer -> {
            writer.put("completion-token-v1");
            writer.put(commitmentFingerprint);
            writer.put(actionCount);
        });
    }

    private static void putAction(DigestWriter writer, Action action) {
        writer.put(action.kind().name());
        writer.put(normalizeItem(action.target()));
        writer.put(action.count());
        TreeMap<String, String> parameters = new TreeMap<>(action.parameters());
        writer.put(parameters.size());
        parameters.forEach((key, value) -> {
            writer.put(key);
            writer.put(value);
        });
        // Description is deliberately excluded: changing user-facing prose is
        // not a different executable program.
    }

    private static void putCounts(DigestWriter writer, Map<String, Integer> counts) {
        TreeMap<String, Integer> sorted = new TreeMap<>(counts);
        writer.put(sorted.size());
        sorted.forEach((item, count) -> {
            writer.put(item);
            writer.put(count);
        });
    }

    private static void putItems(DigestWriter writer, Set<String> items) {
        TreeSet<String> sorted = new TreeSet<>(items);
        writer.put(sorted.size());
        sorted.forEach(writer::put);
    }

    private static String actionWorkstation(Action action) {
        String raw = action.parameters().get("workstation");
        return raw == null || raw.isBlank() ? null : normalizeItem(raw);
    }

    private static List<ItemGoal> checkedGoals(List<ItemGoal> source) {
        Objects.requireNonNull(source, "goals");
        if (source.isEmpty()) throw new IllegalArgumentException("goals cannot be empty");
        ArrayList<ItemGoal> result = new ArrayList<>(source.size());
        for (ItemGoal goal : source) result.add(Objects.requireNonNull(goal, "goal"));
        return List.copyOf(result);
    }

    private static List<Action> checkedActions(List<Action> source) {
        Objects.requireNonNull(source, "actions");
        ArrayList<Action> result = new ArrayList<>(source.size());
        for (Action action : source) result.add(Objects.requireNonNull(action, "action"));
        return List.copyOf(result);
    }

    private static Map<String, Integer> normalizedCounts(
            Map<String, Integer> source,
            String field) {
        Objects.requireNonNull(source, field);
        TreeMap<String, Integer> sorted = new TreeMap<>();
        source.forEach((raw, count) -> {
            String item = normalizeItem(raw);
            if (count == null || count < 0) {
                throw new IllegalArgumentException(
                        field + " contains a negative/null count for " + item);
            }
            if (count > 0) sorted.merge(item, count, Math::addExact);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static Set<String> normalizedItems(Set<String> source, String field) {
        Objects.requireNonNull(source, field);
        TreeSet<String> sorted = new TreeSet<>();
        for (String item : source) sorted.add(normalizeItem(item));
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static String normalizeItem(String raw) {
        return InventoryReservationLedger.normalizeItem(raw);
    }

    private static String digest(java.util.function.Consumer<DigestWriter> input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input.accept(new DigestWriter(digest));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireDigest(String value, String field) {
        String digest = requireText(value, field).toLowerCase(Locale.ROOT);
        if (digest.length() != 64 || !digest.chars().allMatch(character ->
                character >= '0' && character <= '9'
                        || character >= 'a' && character <= 'f')) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return digest;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }

    private static final class DigestWriter {
        private final MessageDigest digest;

        private DigestWriter(MessageDigest digest) {
            this.digest = digest;
        }

        private void put(int value) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
        }

        private void put(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            put(bytes.length);
            digest.update(bytes);
        }
    }
}
