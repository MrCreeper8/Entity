package dev.entity.core.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One durable objective or prerequisite in a {@link TaskPlan}. */
public final class PlanFrame {
    /** Portable description used when a planner creates or replaces a frame. */
    public record Spec(String kind, String target, long count, Map<String, String> parameters) {
        public Spec {
            kind = requireText(kind, "kind");
            target = Objects.requireNonNullElse(target, "").trim();
            if (count < 1) {
                throw new IllegalArgumentException("count must be at least one");
            }
            parameters = immutableParameters(parameters);
        }

        public Spec(String kind, String target, long count) {
            this(kind, target, count, Map.of());
        }
    }

    private final String id;
    private final String kind;
    private final String target;
    private final long count;
    private final Map<String, String> parameters;
    private final PlanFrameState state;
    private final String detail;
    private final long createdAtMillis;
    private final long updatedAtMillis;

    private PlanFrame(
            String id,
            String kind,
            String target,
            long count,
            Map<String, String> parameters,
            PlanFrameState state,
            String detail,
            long createdAtMillis,
            long updatedAtMillis) {
        this.id = requireText(id, "id");
        this.kind = requireText(kind, "kind");
        this.target = Objects.requireNonNullElse(target, "").trim();
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least one");
        }
        this.count = count;
        this.parameters = immutableParameters(parameters);
        this.state = Objects.requireNonNull(state, "state");
        this.detail = Objects.requireNonNullElse(detail, "");
        if (createdAtMillis < 0 || updatedAtMillis < createdAtMillis) {
            throw new IllegalArgumentException("invalid frame timestamps");
        }
        this.createdAtMillis = createdAtMillis;
        this.updatedAtMillis = updatedAtMillis;
    }

    static PlanFrame create(Spec spec, long nowMillis) {
        Objects.requireNonNull(spec, "spec");
        requireTimestamp(nowMillis);
        return new PlanFrame(
                UUID.randomUUID().toString(),
                spec.kind(),
                spec.target(),
                spec.count(),
                spec.parameters(),
                PlanFrameState.PENDING,
                "queued",
                nowMillis,
                nowMillis);
    }

    /** Low-level restore factory used by durable stores. */
    public static PlanFrame restore(
            String id,
            String kind,
            String target,
            long count,
            Map<String, String> parameters,
            PlanFrameState state,
            String detail,
            long createdAtMillis,
            long updatedAtMillis) {
        return new PlanFrame(
                id, kind, target, count, parameters, state, detail,
                createdAtMillis, updatedAtMillis);
    }

    PlanFrame transition(PlanFrameState next, String nextDetail, long nowMillis) {
        requireTimestamp(nowMillis);
        return new PlanFrame(
                id,
                kind,
                target,
                count,
                parameters,
                Objects.requireNonNull(next, "next"),
                Objects.requireNonNullElse(nextDetail, ""),
                createdAtMillis,
                Math.max(updatedAtMillis, nowMillis));
    }

    /** Persists executor progress without changing this frame's identity or state. */
    PlanFrame checkpoint(Map<String, String> nextParameters, String nextDetail, long nowMillis) {
        requireTimestamp(nowMillis);
        return new PlanFrame(
                id,
                kind,
                target,
                count,
                nextParameters,
                state,
                Objects.requireNonNullElse(nextDetail, detail),
                createdAtMillis,
                Math.max(updatedAtMillis, nowMillis));
    }

    public Spec spec() {
        return new Spec(kind, target, count, parameters);
    }

    public String displayName() {
        String subject = target.isBlank() ? kind : kind + " " + target;
        return count == 1 ? subject : subject + " x" + count;
    }

    public String id() { return id; }
    public String kind() { return kind; }
    public String target() { return target; }
    public long count() { return count; }
    public Map<String, String> parameters() { return parameters; }
    public PlanFrameState state() { return state; }
    public String detail() { return detail; }
    public long createdAtMillis() { return createdAtMillis; }
    public long updatedAtMillis() { return updatedAtMillis; }

    private static Map<String, String> immutableParameters(Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            String checkedKey = requireText(key, "parameter key");
            copy.put(checkedKey, Objects.requireNonNull(value, "parameter " + checkedKey));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    static void requireTimestamp(long nowMillis) {
        if (nowMillis < 0) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
    }
}
