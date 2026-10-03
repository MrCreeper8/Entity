package dev.entity.client.autonomy.resource;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded observation memory; hidden moving entities never refresh their remembered position. */
public final class ResourcePerceptionMemory {
    public record Point(double x, double y, double z) { }
    private final int capacity;
    private final LinkedHashMap<Long, String> blocks = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, Point> entities = new LinkedHashMap<>(16, 0.75f, true);
    private record Produced(Point origin, Set<String> items, long atMillis) { }
    private final LinkedHashMap<String, Produced> produced = new LinkedHashMap<>();
    private Object world;
    private String dimension = "";
    private boolean legitimate;
    private long revision;

    public ResourcePerceptionMemory(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public boolean legitimate() { return legitimate; }
    public long revision() { return revision; }
    public void setLegitimate(boolean value) {
        if (legitimate == value) return;
        legitimate = value;
        clear();
    }

    public void scope(Object actualWorld, String actualDimension) {
        String next = Objects.requireNonNullElse(actualDimension, "");
        if (world == actualWorld && dimension.equals(next)) return;
        world = actualWorld;
        dimension = next;
        clear();
    }

    public boolean block(long position, String blockId, boolean observed) {
        if (!legitimate) return true;
        if (observed) {
            blocks.put(position, blockId);
            trim(blocks);
            return true;
        }
        return Objects.equals(blocks.get(position), blockId);
    }

    public Optional<Point> entity(String identity, Point current, boolean observed) {
        if (!legitimate) return Optional.of(current);
        if (observed) {
            entities.put(identity, current);
            trim(entities);
        }
        return Optional.ofNullable(entities.get(identity));
    }

    public void forgetEntity(String identity) { entities.remove(identity); }

    public void produced(String action, Point origin, Set<String> items, long nowMillis) {
        if (!legitimate || items.isEmpty()) return;
        produced.put(action, new Produced(origin, Set.copyOf(items), nowMillis));
        while (produced.size() > Math.min(128, capacity)) produced.remove(produced.keySet().iterator().next());
    }

    /** Fresh matching drops near a verified action result are causal knowledge, not a world scan. */
    public boolean producedItem(String item, Point position, long ageMillis, long nowMillis) {
        produced.values().removeIf(event -> nowMillis - event.atMillis() > 30_000L);
        for (Produced event : produced.values()) {
            long elapsed = nowMillis - event.atMillis();
            double dx = position.x() - event.origin().x();
            double dy = position.y() - event.origin().y();
            double dz = position.z() - event.origin().z();
            if (elapsed >= 0 && ageMillis <= elapsed + 100L && event.items().contains(item)
                    && dx * dx + dy * dy + dz * dz <= 64.0) return true;
        }
        return false;
    }

    private void clear() { blocks.clear(); entities.clear(); produced.clear(); revision++; }
    private <K, V> void trim(LinkedHashMap<K, V> values) {
        while (values.size() > capacity) values.remove(values.keySet().iterator().next());
    }
}
