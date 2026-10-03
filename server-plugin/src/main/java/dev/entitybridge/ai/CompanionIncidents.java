package dev.entitybridge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/** Paper facts for brief social context, never combat authority or durable memory. */
public final class CompanionIncidents {
    private record Scope(UUID world, String session) { }
    private record Strikes(long lastAt, int count) { }
    private Scope scope;
    private final ArrayDeque<JsonObject> events = new ArrayDeque<>();
    private final LinkedHashMap<UUID, Strikes> strikes = new LinkedHashMap<>();
    private long spokenAt = Long.MIN_VALUE;
    private long sequence;
    public static final long MEMORY_MILLIS = 10 * 60_000L;

    public JsonObject hit(UUID world, String session, UUID actor, String name, boolean owner,
                          double damage, long now) {
        if (!Double.isFinite(damage) || damage <= 0) return null;
        select(world, session); prune(now);
        Strikes old = strikes.get(actor);
        int count = old != null && now - old.lastAt <= 15_000 ? old.count + 1 : 1;
        strikes.put(actor, new Strikes(now, count));
        while (strikes.size() > 16) strikes.remove(strikes.keySet().iterator().next());
        JsonObject fact = event("player_hit", now);
        fact.addProperty("actor_uuid", actor.toString()); fact.addProperty("actor_name", clean(name, 32));
        fact.addProperty("actor_is_owner", owner); fact.addProperty("hit_count_recent", count);
        fact.addProperty("damage_points", damage);
        // Coalesce a burst rather than filling the model context with every punch.
        if (!events.isEmpty() && events.peekLast().get("kind").getAsString().equals("player_hit")
                && events.peekLast().get("actor_uuid").getAsString().equals(actor.toString())) events.removeLast();
        remember(fact); return fact.deepCopy();
    }

    public JsonObject death(UUID world, String session, String cause, String actorName, String actorType,
                            boolean owner, long now) {
        select(world, session); prune(now); strikes.clear();
        JsonObject fact = event("death", now); fact.addProperty("cause", clean(cause, 64));
        if (actorName != null && !actorName.isBlank()) {
            fact.addProperty("actor_name", clean(actorName, 32)); fact.addProperty("actor_type", clean(actorType, 64));
            fact.addProperty("actor_is_owner", owner);
        } else fact.addProperty("actor_unknown", true);
        remember(fact); return fact.deepCopy();
    }

    public JsonArray snapshot(UUID world, String session, long now) {
        JsonArray result = new JsonArray();
        if (!Objects.equals(scope, new Scope(world, session))) return result;
        prune(now); events.forEach(f -> result.add(f.deepCopy())); return result;
    }
    public boolean maySpeak(long now) { return spokenAt == Long.MIN_VALUE || now - spokenAt >= 45_000; }
    public void spoken(long now) { spokenAt = now; }
    public void clear() { scope = null; events.clear(); strikes.clear(); spokenAt = Long.MIN_VALUE; }
    private void select(UUID world, String session) {
        Scope wanted = new Scope(Objects.requireNonNull(world), Objects.requireNonNull(session));
        if (!wanted.equals(scope)) { clear(); scope = wanted; }
    }
    private JsonObject event(String kind, long now) {
        JsonObject fact = new JsonObject(); fact.addProperty("kind", kind);
        fact.addProperty("event_id", ++sequence); fact.addProperty("observed_at_millis", now); return fact;
    }
    private void remember(JsonObject fact) { events.addLast(fact); while (events.size() > 8) events.removeFirst(); }
    private void prune(long now) {
        events.removeIf(e -> now - e.get("observed_at_millis").getAsLong() > MEMORY_MILLIS);
        strikes.values().removeIf(s -> now - s.lastAt > 15_000);
    }
    private static String clean(String value, int limit) {
        if (value == null) return "";
        String safe = value.replaceAll("[\\p{Cntrl}§]", "");
        return safe.substring(0, Math.min(limit, safe.length()));
    }
}
