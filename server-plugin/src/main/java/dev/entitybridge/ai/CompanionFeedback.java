package dev.entitybridge.ai;

import java.util.*;

/** Main-thread correlation only. It cannot dispatch work or turn an old result into permission. */
public final class CompanionFeedback {
    public record Owner(UUID player, UUID world, String session) { }
    private record Origin(Owner owner, boolean readQuery) { }
    private final LinkedHashMap<String,Origin> origins = new LinkedHashMap<>();
    private final LinkedHashSet<String> announced = new LinkedHashSet<>();
    public void bind(String key, Owner owner) {
        bind(key, owner, false);
    }
    public void bind(String key, Owner owner, boolean readQuery) {
        if (key == null || owner == null) return;
        origins.put(key,new Origin(owner, readQuery));
        while(origins.size()>512) origins.remove(origins.keySet().iterator().next());
    }
    public Owner owner(String key) {
        Origin origin = key == null ? null : origins.get(key);
        return origin == null ? null : origin.owner();
    }
    public boolean readQuery(String key) {
        Origin origin = key == null ? null : origins.get(key);
        return origin != null && origin.readQuery();
    }
    public boolean first(String identity,String state) {
        boolean fresh=announced.add(identity+":"+state);
        while(announced.size()>512) announced.remove(announced.iterator().next());
        return fresh;
    }
    /** A durable mission observer owns its result; transport result callbacks only add facts. */
    public boolean resultNotice(String commandId,String missionId,boolean terminal) {
        return missionId==null && terminal && first("command:"+commandId,"result");
    }
    public boolean missionNotice(String missionId,String status,int attempt,boolean significant) {
        return significant && first("mission:"+missionId,status+":"+attempt);
    }
    public void forget(UUID player) { origins.values().removeIf(o -> o.owner().player().equals(player)); }
    public void clear() { origins.clear();announced.clear(); }
}
