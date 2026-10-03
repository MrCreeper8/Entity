package dev.entity.client.autonomy.policy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Durable log/flower search intent only. The ordinary goto leaf owns every movement.
 * Legacy class and serialized prefix are retained so saved searches resume unchanged. */
public final class FlowerSearchContinuation {
    public static final String PREFIX = "flowerSearch";
    public static final int MAX_AREAS = 16;
    private FlowerSearchContinuation() { }

    public static boolean pending(Map<String, String> facts) {
        return facts.containsKey(PREFIX + "PendingX");
    }
    public static Set<String> visited(Map<String, String> facts) {
        String encoded = facts.getOrDefault(PREFIX + "Visited", "");
        return encoded.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(encoded.split(",")));
    }
    public static int number(Map<String, String> facts, String key, int fallback) {
        return facts.containsKey(PREFIX + key) ? Integer.parseInt(facts.get(PREFIX + key)) : fallback;
    }
    public static boolean exhausted(Map<String, String> facts) {
        return number(facts, "Count", 0) >= MAX_AREAS;
    }
    public static Map<String, String> begin(Map<String, String> facts, String dimension,
            int fromX, int fromY, int fromZ, String areaId, String observedItem,
            int x, int y, int z) {
        if (pending(facts) || exhausted(facts) || areaId.isBlank() || areaId.contains(",")
                || visited(facts).contains(areaId)) throw new IllegalArgumentException("Repeated harvest search area");
        var next = new LinkedHashMap<>(facts);
        next.putIfAbsent(PREFIX + "OriginX", Integer.toString(fromX));
        next.putIfAbsent(PREFIX + "OriginZ", Integer.toString(fromZ));
        next.put(PREFIX + "Dimension", dimension);
        next.put(PREFIX + "FromX", Integer.toString(fromX));
        next.put(PREFIX + "FromZ", Integer.toString(fromZ));
        next.put(PREFIX + "PendingX", Integer.toString(x));
        next.put(PREFIX + "PendingY", Integer.toString(y));
        next.put(PREFIX + "PendingZ", Integer.toString(z));
        next.put(PREFIX + "Item", observedItem);
        next.put(PREFIX + "Count", Integer.toString(number(facts, "Count", 0) + 1));
        var visited = new LinkedHashSet<>(visited(facts));
        visited.add(areaId);
        next.put(PREFIX + "Visited", String.join(",", visited));
        return Map.copyOf(next);
    }
    public static boolean arrived(Map<String, String> facts, String dimension, int x, int y, int z) {
        if (!pending(facts) || !dimension.equals(facts.get(PREFIX + "Dimension"))) return false;
        long dx = (long)x - number(facts, "PendingX", x);
        long dy = (long)y - number(facts, "PendingY", y);
        long dz = (long)z - number(facts, "PendingZ", z);
        if (dx * dx + dy * dy + dz * dz > 9) return false;
        if (!facts.getOrDefault(PREFIX + "Item", "").isBlank()) return true;
        long movedX = (long)x - number(facts, "FromX", x);
        long movedZ = (long)z - number(facts, "FromZ", z);
        return movedX * movedX + movedZ * movedZ >= 64;
    }
    public static Map<String, String> complete(Map<String, String> facts, String dimension, int x, int y, int z) {
        if (!arrived(facts, dimension, x, y, z)) throw new IllegalArgumentException("Harvest search has not physically arrived");
        var next = new LinkedHashMap<>(facts);
        String observed = facts.getOrDefault(PREFIX + "Item", "");
        var absent = new LinkedHashSet<>(UnavailableHarvestSources.read(facts));
        // An observed source disproves only its own old absence; an actually new
        // search area invalidates the previous area's negative observations.
        if (observed.isBlank()) absent.clear();
        else absent.remove(InventoryReservationLedger.normalizeItem(observed));
        next.put(UnavailableHarvestSources.KEY, absent.stream().sorted().collect(Collectors.joining(",")));
        next.remove(PREFIX + "PendingX");
        next.remove(PREFIX + "PendingY");
        next.remove(PREFIX + "PendingZ");
        return Map.copyOf(next);
    }
}
