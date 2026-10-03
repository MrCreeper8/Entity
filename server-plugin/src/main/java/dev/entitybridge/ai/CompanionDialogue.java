package dev.entitybridge.ai;

import com.google.gson.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Session-local conversation, never game authority or an inventory cache. */
public final class CompanionDialogue {
    private static final long TURN_LIFETIME_MILLIS = 10 * 60 * 1000L;
    private record Key(UUID player, UUID world, String session) { }
    private record Turn(long addedAtMillis, JsonObject message, boolean social) { }
    private final LinkedHashMap<Key, ArrayDeque<Turn>> histories = new LinkedHashMap<>();
    private final LongSupplier clock;
    public CompanionDialogue() { this(System::currentTimeMillis); }
    public CompanionDialogue(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public void add(UUID player, UUID world, String session, String role, String text) {
        add(player,world,session,role,text,true);
    }
    public void addFact(UUID player, UUID world, String session, String role, String text) {
        add(player,world,session,role,text,false);
    }
    private void add(UUID player, UUID world, String session, String role, String text, boolean social) {
        if (!Set.of("user", "assistant").contains(role)) throw new IllegalArgumentException("dialogue role");
        long now = clock.getAsLong();
        expire(now);
        Key key = new Key(player, world, session);
        var history = histories.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        JsonObject turn = new JsonObject(); turn.addProperty("role", role);
        turn.addProperty("content", text.length() > 1600 ? text.substring(0, 1600) : text);
        history.addLast(new Turn(now, turn,social));
        while (history.size() > 12 || history.stream().mapToInt(t -> t.message().get("content").getAsString().length()).sum() > 4000) history.removeFirst();
        while (histories.size() > 32) histories.remove(histories.keySet().iterator().next());
    }
    public JsonArray messages(UUID player, UUID world, String session) {
        expire(clock.getAsLong());
        JsonArray result = new JsonArray();
        for (var turn : histories.getOrDefault(new Key(player, world, session), new ArrayDeque<>())) result.add(turn.message().deepCopy());
        return result;
    }
    /** One current order plus small quoted request/receipt references, never an inventory dossier. */
    public JsonArray requestMessages(UUID player,UUID world,String session,String current) {
        JsonArray reference=new JsonArray();
        if(current.toLowerCase(Locale.ROOT).matches("(?s).*\\b(it|them|another|that|thing|again)\\b.*")) {
            var history=messages(player,world,session);
            List<JsonElement> kept=new ArrayList<>();int chars=0;
            for(int i=history.size()-1;i>=0;i--) {
                JsonObject turn=history.get(i).getAsJsonObject();String content=turn.get("content").getAsString();
                if(content.equals(current))continue;
                boolean relevant=turn.get("role").getAsString().equals("user")
                        ? CompanionTurnPolicy.attemptsWork(content)
                        : content.startsWith("[Requested existing skill]");
                if(!relevant || chars+content.length()>600)continue;
                kept.add(turn.deepCopy());chars+=content.length();if(kept.size()==3)break;
            }
            for(int i=kept.size()-1;i>=0;i--)reference.add(kept.get(i));
        }
        JsonObject turn=new JsonObject();turn.addProperty("role","user");
        turn.addProperty("content","REFERENCE_ONLY: "+reference+"\nCURRENT_PLAYER_REQUEST: "+current);
        JsonArray result=new JsonArray();result.add(turn);return result;
    }
    /** One live player turn; bounded recent social exchanges are quoted reference data. */
    public JsonArray socialMessages(UUID player, UUID world, String session) {
        expire(clock.getAsLong());
        List<Turn> all=new ArrayList<>(histories.getOrDefault(new Key(player,world,session),new ArrayDeque<>()));
        JsonArray result=new JsonArray();
        List<JsonElement> kept=new ArrayList<>(); int characters=0; String current=null;
        for(int i=all.size()-1;i>=0;i--) {
            if(!all.get(i).social()) continue;
            JsonObject message=all.get(i).message().deepCopy(); String content=message.get("content").getAsString();
            if(content.startsWith("[Observed game result]") || content.startsWith("[Requested existing skill]")) continue;
            if(message.get("role").getAsString().equals("user")
                    && (CompanionTurnPolicy.classify(content).authorizedOrder()
                    || CompanionTurnPolicy.classify(content).preserveCurrent())) continue;
            if(current==null) {
                if(!message.get("role").getAsString().equals("user")) continue;
                current=content; continue;
            }
            if(kept.size()>=4 || characters+content.length()>1000) break;
            kept.add(message); characters+=content.length();
        }
        if(current==null) return result;
        JsonArray background=new JsonArray();
        // A self-contained new topic needs no previous answers to copy. Keep
        // short-term context for references/continuations, not unrelated bait.
        if(needsSocialReference(current))
            for(int i=kept.size()-1;i>=0;i--) background.add(kept.get(i));
        JsonObject live=new JsonObject(); live.addProperty("role","user");
        live.addProperty("content","RECENT_SOCIAL_TRANSCRIPT: "+background+"\nCURRENT_PLAYER_LINE: "+current);
        result.add(live);
        return result;
    }
    private static boolean needsSocialReference(String current) {
        String text=current.strip().toLowerCase(Locale.ROOT);
        return text.matches("(?s).*\\b(that|this|it|them|those|earlier|before|again|remember|nickname|called|calling|last|previous)\\b.*")
                || Set.of("yes","yeah","yep","no","nope","nice","okay","ok","why","how","really","same").contains(text);
    }
    private void expire(long now) {
        histories.values().removeIf(history -> {
            history.removeIf(turn -> now >= turn.addedAtMillis() && now - turn.addedAtMillis() >= TURN_LIFETIME_MILLIS);
            return history.isEmpty();
        });
    }
    public void forget(UUID player) { histories.keySet().removeIf(key -> key.player().equals(player)); }
    public void clear() { histories.clear(); }
}
