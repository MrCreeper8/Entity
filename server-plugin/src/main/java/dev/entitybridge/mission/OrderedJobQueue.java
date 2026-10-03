package dev.entitybridge.mission;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.entitybridge.bridge.BridgeCommand;
import dev.entitybridge.bridge.BridgeResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One finite sequence, not an executor: all physical work remains in existing missions/Home. */
public final class OrderedJobQueue {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    public static final int MAX_STEPS = 64;
    public enum Phase { RUNNING, BLOCKED, SKIPPING, SKIP_BLOCKED, CANCELLED, COMPLETE }

    public record Step(String action, String operation, JsonObject args, String requestedBy, String label) {
        public Step {
            Objects.requireNonNull(action); Objects.requireNonNull(requestedBy); Objects.requireNonNull(label);
            args = args == null ? new JsonObject() : args.deepCopy();
        }
        @Override public JsonObject args() { return args.deepCopy(); }
        public boolean mission() { return operation == null; }
    }

    /** index -1 means existing direct work, which is not duplicated in steps. */
    public record Active(String commandId, String missionId, Long missionSequence,
                         int attempt, int index, boolean external, Step activeStep, String retryCommandId) {
        public Active(String commandId, String missionId, Long missionSequence,
                      int attempt, int index, boolean external, Step activeStep) {
            this(commandId, missionId, missionSequence, attempt, index, external, activeStep, null);
        }
        Active withRetry(String retryId) {
            return new Active(commandId, missionId, missionSequence, attempt, index, external, activeStep, retryId);
        }
    }
    public record State(int schema, String worldId, String queueId, long generation,
                        Phase phase, int index, List<Step> steps, Active active, String reason) {
        public State { steps = List.copyOf(steps); }
    }
    public interface Port {
        JsonObject resolveArgs(Step step) throws IOException;
        boolean dispatch(BridgeCommand command, Mission superseded, Step step);
        /** Only delivers cancellation; it must not establish another work owner. */
        default boolean stop(BridgeCommand command) { return false; }
    }

    private final Path file;
    private final String worldId;
    private final MissionRegistry missions;
    private final Port port;
    private State state;
    private String loadFailure;

    public OrderedJobQueue(Path file, UUID worldId, MissionRegistry missions, Port port) {
        this.file = file;
        this.worldId = worldId.toString();
        this.missions = missions;
        this.port = port;
        if (file == null || !Files.exists(file)) return;
        try {
            State loaded = JSON.fromJson(Files.readString(file), State.class);
            if (loaded == null || loaded.schema() != 1 || !this.worldId.equals(loaded.worldId())
                    || loaded.phase() == null || loaded.steps().size() > MAX_STEPS
                    || loaded.index() < -1 || loaded.index() > loaded.steps().size()) {
                throw new IOException("Invalid or different-world queue journal");
            }
            state = loaded;
            if (state.phase() == Phase.SKIPPING) {
                save(copy(Phase.SKIP_BLOCKED, state.index(), state.active().withRetry(null),
                        "Restart interrupted skip; /e queue resume confirms cancellation before the next step"));
            }
            // A restart is not permission to repeat an uncertain physical operation.
            if (state.phase() == Phase.RUNNING) {
                Mission recorded = activeMission();
                if (recorded != null && recorded.status() == MissionStatus.SUCCEEDED) {
                    int next = state.index() + 1;
                    save(copy(next >= state.steps().size() ? Phase.COMPLETE : Phase.BLOCKED,
                            next, null, "Previous step completed; /e queue resume starts the next step"));
                } else {
                    save(copy(Phase.BLOCKED, state.index(), state.active(),
                            "Restart interrupted this step; /e queue retry reconciles current inventory"));
                }
            }
            if (state.phase() == Phase.BLOCKED) parkActive();
            if (state.phase() == Phase.CANCELLED || state.phase() == Phase.SKIP_BLOCKED) retireActive();
        } catch (IOException | RuntimeException failure) {
            loadFailure = "Queue unavailable: " + failure.getMessage() + "; /e queue cancel to discard it";
            // A corrupt queue may not release its still-replayable mission journal.
            try {
                for (Mission mission : missions.snapshot()) {
                    if (mission.args().has("queueId") && !mission.terminal())
                        missions.cancelStrict(mission.id(), mission.sequence(), "Queue journal could not be recovered");
                }
            } catch (IOException cannotFence) {
                throw new IllegalStateException("Cannot safely fence queue before bridge startup", cannotFence);
            }
        }
    }

    public static Active external(Mission mission) {
        return new Active(mission.commandId(), mission.id(), mission.sequence(), mission.attempt(), -1, true,
                new Step(mission.action(), null, mission.args(), mission.requestedBy(), mission.action()));
    }
    public static Active external(BridgeCommand command, String label) {
        return new Active(command.id(), command.missionId(), command.sequence(), 1, -1, true,
                new Step(command.action(), command.operation(), command.args(), command.requestedBy(), label));
    }
    public static boolean finiteMission(String action) {
        return List.of("get", "bring", "give", "mine", "gear", "come", "goto", "farm").contains(action);
    }
    public synchronized State state() { return state; }
    public synchronized JsonObject snapshot() { return state == null ? new JsonObject() : JSON.toJsonTree(state).getAsJsonObject(); }
    public synchronized boolean engaged() {
        return state != null && state.phase() != Phase.CANCELLED && state.phase() != Phase.COMPLETE;
    }
    public synchronized boolean owns(String missionId) {
        return engaged() && state.active() != null && Objects.equals(state.active().missionId(), missionId);
    }
    public synchronized String requester(String commandId) {
        return state != null && state.active() != null
                && (state.active().commandId().equals(commandId) || Objects.equals(state.active().retryCommandId(), commandId))
                ? state.active().activeStep().requestedBy() : null;
    }

    public synchronized void append(List<Step> steps, Active existing) throws IOException {
        healthy();
        if (steps.isEmpty()) throw new IOException("No finite steps supplied");
        if (engaged()) {
            ArrayList<Step> combined = new ArrayList<>(state.steps()); combined.addAll(steps);
            if (combined.size() > MAX_STEPS) throw new IOException("Queue supports at most " + MAX_STEPS + " steps");
            save(new State(1, worldId, state.queueId(), state.generation(), state.phase(), state.index(),
                    combined, state.active(), state.reason()));
            return;
        }
        if (steps.size() > MAX_STEPS) throw new IOException("Queue supports at most " + MAX_STEPS + " steps");
        long generation = state == null ? 1 : state.generation() + 1;
        save(new State(1, worldId, UUID.randomUUID().toString(), generation, Phase.RUNNING,
                existing == null ? 0 : -1, steps, existing, ""));
        if (existing == null) dispatchCurrent(1);
        else {
            Mission mission = activeMission();
            if (mission != null) finish(mission.status(), mission.reason());
        }
    }

    /** Cancellation is durable before any registry or physical cancellation is attempted. */
    public synchronized boolean cancel(String reason) throws IOException {
        healthy();
        if (!engaged()) return false;
        State cancelled = copy(Phase.CANCELLED, state.index(), state.active(), reason);
        try { save(cancelled); }
        catch (IOException failure) {
            // Never let a late success advance after a Stop whose disk write failed.
            // Startup already blocks an uncertain RUNNING journal before bridge sync.
            state = cancelled; loadFailure = "Queue disabled after cancellation persistence failed";
            throw failure;
        }
        return true;
    }

    public synchronized void retry() throws IOException {
        healthy();
        if (state != null && state.phase() == Phase.SKIP_BLOCKED) { skip(); return; }
        if (state == null || state.phase() != Phase.BLOCKED) throw new IOException("No blocked queue step to retry");
        if (state.active() != null && state.active().activeStep().mission()) {
            Mission existing = activeMission();
            if (existing == null) throw new IOException("Original queue mission is missing; /e queue cancel before a new objective");
            if (existing.status() == MissionStatus.SUCCEEDED) {
                finish(MissionStatus.SUCCEEDED, existing.reason());
                return;
            }
            if (existing.status() == MissionStatus.CANCELLED)
                throw new IOException("Original queue mission was cancelled; /e queue skip or /e queue cancel");
            // Retrying a quantity objective is not a new order. Its existing
            // client plan owns mined progress, deliveredTotal and the exact nonce.
            BridgeCommand retry = BridgeCommand.lifecycle(existing.retry(System.currentTimeMillis()),
                    "retry", state.active().activeStep().requestedBy());
            save(copy(Phase.RUNNING, state.index(), state.active().withRetry(retry.id()), ""));
            try {
                missions.retryStrict(existing.id(), existing.sequence());
                if (!port.dispatch(retry, null, state.active().activeStep()))
                    throw new IOException("Bridge refused retained mission retry; /e queue retry");
            } catch (IOException | RuntimeException failure) {
                save(copy(Phase.BLOCKED, state.index(), state.active().withRetry(null), failure.getMessage()));
                parkActive();
                throw failure instanceof IOException io ? io : new IOException(failure);
            }
            return;
        }
        int attempt = state.active() == null ? 1 : state.active().attempt() + 1;
        dispatchCurrent(attempt);
    }

    /** Owner-authorized retirement, never a second quantity objective or an automatic skip. */
    public synchronized void skip() throws IOException {
        healthy();
        if (state != null && state.phase() == Phase.SKIPPING) return;
        if (!engaged() || state.active() == null)
            throw new IOException("No current queue step to skip");
        Active active = state.active();
        Mission mission = activeMission();
        if (active.missionId() != null && mission == null)
            throw new IOException("Original queue mission is missing; /e queue cancel safely stops the queue");
        BridgeCommand stop = BridgeCommand.handoffStop(mission, active.activeStep().requestedBy());
        // retryCommandId is the exact lifecycle-control receipt, never a new work attempt.
        save(copy(Phase.SKIPPING, state.index(), active.withRetry(stop.id()),
                "Skipping current step; waiting for its physical Stop acknowledgement"));
        try {
            retireActive();
            if (!port.stop(stop)) throw new IOException("Bridge refused skip cancellation; /e queue resume");
        } catch (IOException | RuntimeException failure) {
            save(copy(Phase.SKIP_BLOCKED, state.index(), active.withRetry(null),
                    "Skip awaits cancellation: " + failure.getMessage()));
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
    }

    /** Removes only unstarted steps; active identity, phase and receipt fencing stay unchanged. */
    public synchronized int clearPending() throws IOException {
        healthy();
        if (!engaged()) return 0;
        int keep = Math.max(0, state.index() + (state.active() == null ? 0 : 1));
        int removed = state.steps().size() - keep;
        save(new State(1, worldId, state.queueId(), state.generation(),
                state.active() == null ? Phase.COMPLETE : state.phase(), state.index(),
                state.steps().subList(0, keep), state.active(), state.reason()));
        return removed;
    }

    /** Explicit owner cancellation/recovery; never silently replaces a corrupt journal. */
    public synchronized boolean cancelAll(String reason) throws IOException {
        if (loadFailure == null) return cancel(reason);
        save(new State(1, worldId, UUID.randomUUID().toString(), state == null ? 1 : state.generation() + 1,
                Phase.CANCELLED, 0, List.of(), null, reason));
        loadFailure = null;
        return true;
    }

    public synchronized void pauseByOwner() throws IOException {
        healthy();
        if (state != null && state.phase() == Phase.SKIPPING) {
            save(copy(Phase.SKIP_BLOCKED, state.index(), state.active().withRetry(null),
                    "Paused by owner during skip; /e queue resume confirms cancellation first"));
            return;
        }
        if (state != null && state.phase() == Phase.RUNNING)
            save(copy(Phase.BLOCKED, state.index(), state.active(), "Paused by owner; /e queue resume"));
    }

    public synchronized void missionUpdated(Mission mission) throws IOException {
        if (!matchesMission(mission)) return;
        finish(mission.status(), mission.reason());
    }

    public synchronized void result(BridgeResult result) throws IOException {
        if (state != null && state.phase() == Phase.SKIPPING && state.active() != null) {
            Active active = state.active();
            if (!Objects.equals(active.retryCommandId(), result.id())) return;
            if (active.missionId() != null && (!Objects.equals(active.missionId(), result.missionId())
                    || !Objects.equals(active.missionSequence(), result.sequence()))) return;
            MissionStatus status = MissionStatus.parse(result.status(), null);
            if (result.ok() && (status == MissionStatus.CANCELLED || status == MissionStatus.SUCCEEDED)) {
                // Stop has run on the client. Its existing physical-custody drain
                // still gates the successor's actuator; no transaction is erased here.
                // A goal that completed just before Stop stays completed on the client.
                advance("Previous step explicitly skipped by owner");
            } else if (!result.ok() || status == MissionStatus.FAILED || status == MissionStatus.BLOCKED) {
                save(copy(Phase.SKIP_BLOCKED, state.index(), active.withRetry(null),
                        "Skip cancellation was not confirmed: " + result.message() + "; /e queue resume"));
            }
            return;
        }
        if (!engaged() || state.phase() != Phase.RUNNING || state.active() == null) return;
        Active active = state.active();
        boolean retryAcknowledgement = Objects.equals(active.retryCommandId(), result.id());
        if (!active.commandId().equals(result.id()) && !retryAcknowledgement) return;
        if (!active.external() && (!Objects.equals(state.queueId(), result.queueId())
                || !Objects.equals(state.generation(), result.queueGeneration())
                || !Objects.equals((long) active.index(), result.queueStep())
                || !Objects.equals((long) active.attempt(), result.queueAttempt()))) return;
        if (active.missionId() != null && (!Objects.equals(active.missionId(), result.missionId())
                || !Objects.equals(active.missionSequence(), result.sequence()))) return;
        MissionStatus status = MissionStatus.parse(result.status(), result.ok() ? null : MissionStatus.BLOCKED);
        if (retryAcknowledgement) {
            save(copy(state.phase(), state.index(), active.withRetry(null), state.reason()));
        } else if (active.retryCommandId() != null && (status == MissionStatus.BLOCKED || status == MissionStatus.FAILED)) {
            return;
        }
        if (status != null) finish(status, result.message());
    }

    private boolean matchesMission(Mission mission) {
        if (state == null || state.phase() != Phase.RUNNING || state.active() == null || mission == null) return false;
        Active active = state.active();
        if (!Objects.equals(active.missionId(), mission.id()) || !Objects.equals(active.commandId(), mission.commandId())
                || !Objects.equals(active.missionSequence(), mission.sequence())) return false;
        if (active.retryCommandId() != null && (mission.status() == MissionStatus.BLOCKED || mission.status() == MissionStatus.FAILED)) return false;
        if (active.external()) return true;
        JsonObject args = mission.args();
        return args.has("queueId") && state.queueId().equals(args.get("queueId").getAsString())
                && args.has("queueGeneration") && state.generation() == args.get("queueGeneration").getAsLong()
                && args.has("queueStep") && active.index() == args.get("queueStep").getAsInt()
                && args.has("queueAttempt") && active.attempt() == args.get("queueAttempt").getAsInt();
    }

    private void finish(MissionStatus status, String reason) throws IOException {
        if (status == MissionStatus.SUCCEEDED) {
            advance("");
        } else if (status == MissionStatus.BLOCKED || status == MissionStatus.FAILED
                || status == MissionStatus.CANCELLED) {
            save(copy(Phase.BLOCKED, state.index(), state.active(), Objects.requireNonNullElse(reason, "Step blocked")));
        }
    }

    private void advance(String reason) throws IOException {
        int next = state.index() + 1;
        save(copy(next == state.steps().size() ? Phase.COMPLETE : Phase.RUNNING, next, null, reason));
        if (state.phase() == Phase.RUNNING) dispatchCurrent(1);
    }

    private void dispatchCurrent(int attempt) throws IOException {
        Step step = state.index() == -1 ? state.active().activeStep() : state.steps().get(state.index());
        Mission oldAttempt = activeMission();
        try {
            JsonObject args = port.resolveArgs(step);
            args.addProperty("queueId", state.queueId()); args.addProperty("queueGeneration", state.generation());
            args.addProperty("queueStep", state.index()); args.addProperty("queueAttempt", attempt);
            Mission prepared = step.mission() ? missions.prepare(step.action(), args, step.requestedBy()) : null;
            BridgeCommand command = prepared == null
                    ? new BridgeCommand(UUID.randomUUID().toString(), step.action(), args, step.requestedBy(),
                    System.currentTimeMillis(), null, null, step.operation(), false)
                    : BridgeCommand.start(prepared);
            Active binding = new Active(command.id(), command.missionId(), command.sequence(), attempt,
                    state.index(), false, step);
            save(copy(Phase.RUNNING, state.index(), binding, ""));
            MissionRegistry.Creation creation = prepared == null ? null : missions.registerPrepared(prepared);
            Mission superseded = creation == null ? oldAttempt
                    : creation.superseded() == null ? oldAttempt : creation.superseded();
            if (!port.dispatch(command, superseded, step)) {
                throw new IOException("Bridge refused ordered step; /e queue retry");
            }
        } catch (IOException | RuntimeException failure) {
            save(copy(Phase.BLOCKED, state.index(), state.active(), failure.getMessage()));
            parkActive();
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
    }

    private Mission activeMission() {
        if (state.active() == null || state.active().missionId() == null) return null;
        Mission mission = missions.resolve(state.active().missionId()).orElse(null);
        return mission != null && Objects.equals(state.active().missionSequence(), mission.sequence()) ? mission : null;
    }
    private void retireActive() throws IOException {
        Mission mission = activeMission();
        if (mission != null) missions.cancelStrict(mission.id(), mission.sequence(), "Queue fenced");
    }
    private void parkActive() throws IOException {
        Mission mission = activeMission();
        if (mission != null && !mission.terminal())
            missions.pauseStrict(mission.id(), mission.sequence(), "Queue awaits explicit retry; retained plan must not auto-run");
    }
    public synchronized void retireCancelledStep() throws IOException {
        healthy();
        if (state != null && state.phase() == Phase.CANCELLED) retireActive();
    }
    /** Unlike an unrelated owner-paused job, this queued step must be explicitly cancelled on replacement. */
    public synchronized Mission missionForReplacement() {
        Mission mission=engaged()?activeMission():null;
        return mission!=null && !mission.terminal()?mission:null;
    }
    private State copy(Phase phase, int index, Active active, String reason) {
        return new State(1, worldId, state.queueId(), state.generation(), phase, index, state.steps(), active, reason);
    }
    private void healthy() throws IOException { if (loadFailure != null) throw new IOException(loadFailure); }
    private void save(State candidate) throws IOException {
        if (file != null) MissionRegistry.persistJournal(file, JSON.toJson(candidate), MissionRegistry::moveReplacing, Thread::sleep, 8);
        state = candidate;
    }
    public synchronized String describe() {
        if (loadFailure != null) return loadFailure;
        if (state == null) return "Queue: empty.";
        boolean inactive = state.phase() == Phase.CANCELLED || state.phase() == Phase.COMPLETE;
        String current = inactive || state.active() == null ? "none" : state.active().activeStep().label();
        int pending = inactive ? 0 : Math.max(0, state.steps().size() - state.index()
                - (state.active() == null ? 0 : 1));
        return "Queue " + state.phase().name().toLowerCase() + ": current=" + current + "; pending=" + pending
                + (state.reason() == null || state.reason().isBlank() ? "." : ". " + state.reason());
    }
}
