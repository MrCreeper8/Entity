package dev.entity.client.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;

/** Authenticated Paper facts, not client player visibility. No body or resource observation. */
public record IdleContext(String worldId, String ownerName, boolean ownerOnline,
                          boolean manualWorkPending, long observedAtMillis, long receivedAtMillis) {
    public static final long MAX_AGE_MILLIS = 3_000L;
    public IdleContext {
        worldId = WorldStateScope.parse(worldId).key();
        if (ownerName == null || !ownerName.matches("[A-Za-z0-9_]{1,16}"))
            throw new IllegalArgumentException("Invalid idle context owner");
        if (observedAtMillis < 0 || receivedAtMillis < 0) throw new IllegalArgumentException("Invalid idle context time");
    }

    public static IdleContext parse(JsonObject frame, WorldStateScope scope) {
        return parse(frame, scope, System.currentTimeMillis());
    }
    public static IdleContext parse(JsonObject frame, WorldStateScope scope, long receivedAtMillis) {
        Objects.requireNonNull(scope, "authenticated world scope");
        if (frame == null || !"idle_context".equals(text(frame, "type")))
            throw new IllegalArgumentException("Expected idle_context frame");
        String world = text(frame, "worldId");
        if (!scope.key().equals(world)) throw new IllegalArgumentException("Idle context belongs to another world");
        return new IdleContext(world, text(frame, "ownerName"), bool(frame, "ownerOnline"),
                bool(frame, "manualWorkPending"), number(frame, "observedAtMillis"), receivedAtMillis);
    }

    /** Local receipt age; server wall-clock skew cannot authorize/reject idle. */
    public boolean fresh(long nowMillis) {
        return nowMillis >= receivedAtMillis && nowMillis - receivedAtMillis <= MAX_AGE_MILLIS;
    }
    /** Runtime clears context on disconnect and supplies its current local authentication barrier. */
    public boolean fresh(long nowMillis, long authenticatedAtMillis) {
        return authenticatedAtMillis >= 0 && receivedAtMillis >= authenticatedAtMillis && fresh(nowMillis);
    }

    private static String text(JsonObject frame, String key) {
        JsonElement value = frame.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected string " + key);
        return value.getAsString();
    }
    private static boolean bool(JsonObject frame, String key) {
        JsonElement value = frame.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Expected boolean " + key);
        return value.getAsBoolean();
    }
    private static long number(JsonObject frame, String key) {
        JsonElement value = frame.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected integer " + key);
        try { return value.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Expected integer " + key, invalid); }
    }
}
