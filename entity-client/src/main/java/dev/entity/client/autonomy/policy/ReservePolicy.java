package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable minimum inventory definitions for self-sufficient missions. */
public final class ReservePolicy {
    public record ReserveDefinition(
            String id,
            int desiredCount,
            String acquisitionItem,
            Set<String> acceptedItems,
            String reason
    ) {
        public ReserveDefinition {
            id = requireText(id, "id");
            if (desiredCount <= 0) throw new IllegalArgumentException("desiredCount must be positive");
            acquisitionItem = requireText(acquisitionItem, "acquisitionItem");
            Objects.requireNonNull(acceptedItems, "acceptedItems");
            if (acceptedItems.isEmpty()) throw new IllegalArgumentException("acceptedItems cannot be empty");
            acceptedItems = Collections.unmodifiableSet(new LinkedHashSet<>(acceptedItems));
            reason = requireText(reason, "reason");
        }
    }

    public record MissingReserve(
            ReserveDefinition definition,
            int availableCount,
            int missingCount
    ) {
        public MissingReserve {
            definition = Objects.requireNonNull(definition, "definition");
            if (availableCount < 0 || missingCount <= 0) {
                throw new IllegalArgumentException("invalid reserve shortage");
            }
        }

        public ResourcePlanner.Request acquisitionRequest() {
            return ResourcePlanner.Request.acquire(definition.acquisitionItem(), missingCount);
        }
    }

    private static final List<ReserveDefinition> DEFAULTS = List.of(
            reserve("building_blocks", 64, "cobblestone",
                    "Bridge, pillar, seal hazards and recover from blocked routes",
                    "cobblestone", "cobbled_deepslate", "dirt", "netherrack", "blackstone"),
            reserve("logs", 8, "oak_log",
                    "Recover crafting infrastructure without returning home", "oak_log"),
            reserve("fuel", 8, "coal",
                    "Run furnaces and craft torches", "coal"),
            reserve("food", 16, "bread",
                    "Maintain a survival food buffer", "bread", "cooked_beef", "cooked_porkchop",
                    "cooked_chicken", "cooked_mutton", "cooked_rabbit", "baked_potato", "golden_carrot"),
            reserve("pickaxe", 1, "stone_pickaxe",
                    "Avoid accepting mining/traversal work without a usable tool",
                    "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe"),
            reserve("weapon", 1, "stone_sword",
                    "Defend itself while a mission is active", "stone_sword", "iron_sword",
                    "diamond_sword", "netherite_sword", "stone_axe", "iron_axe", "diamond_axe", "netherite_axe"),
            reserve("shield", 1, "shield",
                    "Block skeleton arrows and high-damage melee hits", "shield"),
            reserve("bucket", 1, "bucket",
                    "Carry water or lava and support later clutch actions", "bucket", "water_bucket", "lava_bucket"),
            reserve("torches", 32, "torch",
                    "Light mines and mark routes", "torch")
    );

    private ReservePolicy() {
    }

    public static List<ReserveDefinition> defaults() {
        return DEFAULTS;
    }

    public static List<MissingReserve> missing(ResourcePlanner.InventoryView inventory) {
        return missing(inventory, ResourceCatalog.defaults(), DEFAULTS);
    }

    public static List<MissingReserve> missing(
            ResourcePlanner.InventoryView inventory,
            ResourceCatalog catalog,
            List<ReserveDefinition> definitions
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(definitions, "definitions");

        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        inventory.itemCounts().forEach((raw, count) -> {
            if (count > 0) normalized.merge(catalog.normalizeItem(raw), count, Math::addExact);
        });

        ArrayList<MissingReserve> missing = new ArrayList<>();
        for (ReserveDefinition definition : definitions) {
            int available = 0;
            LinkedHashSet<String> counted = new LinkedHashSet<>();
            for (String accepted : definition.acceptedItems()) {
                String item = catalog.normalizeItem(accepted);
                if (counted.add(item)) available = Math.addExact(available, normalized.getOrDefault(item, 0));
            }
            if (available < definition.desiredCount()) {
                missing.add(new MissingReserve(definition, available,
                        Math.subtractExact(definition.desiredCount(), available)));
            }
        }
        return List.copyOf(missing);
    }

    public static Map<String, Integer> desiredCounts() {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (ReserveDefinition definition : DEFAULTS) {
            result.put(definition.id(), definition.desiredCount());
        }
        return Collections.unmodifiableMap(result);
    }

    private static ReserveDefinition reserve(
            String id,
            int desiredCount,
            String acquisitionItem,
            String reason,
            String... acceptedItems
    ) {
        return new ReserveDefinition(id, desiredCount, acquisitionItem,
                Set.of(acceptedItems), reason);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }
}
