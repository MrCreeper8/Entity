package dev.entity.client.autonomy.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Cost gate for combat equipment; keeps a small hunt from becoming a lumber expedition. */
public final class CombatLoadoutPolicy {
    private static final int WEAPON_PROVISION_KILL_THRESHOLD = 4;
    private static final List<String> CARRIED_WEAPON_ORDER = List.of(
            "netherite_sword", "diamond_sword", "iron_sword", "stone_sword", "wooden_sword",
            "netherite_axe", "diamond_axe", "iron_axe", "stone_axe", "wooden_axe");

    public enum Action {
        READY,
        EQUIP,
        ACQUIRE,
        NONE
    }

    public record Decision(Action action, String item, String reason) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            item = item == null ? "" : simple(item);
            reason = Objects.requireNonNullElse(reason, "");
            if ((action == Action.EQUIP || action == Action.ACQUIRE) && item.isBlank()) {
                throw new IllegalArgumentException(action + " decisions require an item");
            }
        }
    }

    private CombatLoadoutPolicy() {
    }

    public static Decision passiveHunt(
            int remainingKills,
            Map<String, Integer> rawCounts,
            Set<String> rawEquippedItems) {
        if (remainingKills < 0) throw new IllegalArgumentException("remainingKills cannot be negative");
        Map<String, Integer> counts = normalizedCounts(rawCounts);
        Set<String> equipped = rawEquippedItems.stream()
                .map(CombatLoadoutPolicy::simple)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String weapon : CARRIED_WEAPON_ORDER) {
            if (equipped.contains(weapon)) {
                return new Decision(Action.READY, weapon, "best carried weapon is already equipped");
            }
            if (counts.getOrDefault(weapon, 0) > 0) {
                return new Decision(Action.EQUIP, weapon, "equip a carried weapon before hunting");
            }
        }
        if (remainingKills < WEAPON_PROVISION_KILL_THRESHOLD) {
            return new Decision(Action.NONE, "", "too few targets to repay a crafting detour");
        }

        boolean table = counts.getOrDefault("crafting_table", 0) > 0;
        int planks = counts.getOrDefault("oak_planks", 0)
                + Math.multiplyExact(counts.getOrDefault("oak_log", 0), 4);
        int sharedWoodCost = (table ? 0 : 4)
                + (counts.getOrDefault("stick", 0) > 0 ? 0 : 2);
        for (Material material : List.of(
                new Material("diamond_sword", "diamond", 2, 0),
                new Material("iron_sword", "iron_ingot", 2, 0),
                new Material("stone_sword", "cobblestone", 2, 0),
                new Material("wooden_sword", "oak_planks", 0, 2))) {
            if (counts.getOrDefault(material.item(), 0) < material.items()) continue;
            if (planks < sharedWoodCost + material.planks()) continue;
            return new Decision(Action.ACQUIRE, material.weapon(),
                    "repeated hunt justifies a weapon using only carried materials");
        }
        return new Decision(Action.NONE, "",
                "no carried-material weapon is affordable without a gathering detour");
    }

    /** Shields are useful in dangerous combat, not while chasing passive animals. */
    public static Decision carriedShield(
            boolean dangerousCombat,
            Map<String, Integer> rawCounts,
            Set<String> rawEquippedItems) {
        Map<String, Integer> counts = normalizedCounts(rawCounts);
        boolean equipped = rawEquippedItems.stream().map(CombatLoadoutPolicy::simple)
                .anyMatch("shield"::equals);
        if (equipped) return new Decision(Action.READY, "shield", "shield is already equipped");
        if (dangerousCombat && counts.getOrDefault("shield", 0) > 0) {
            return new Decision(Action.EQUIP, "shield", "dangerous combat merits the carried shield");
        }
        return new Decision(Action.NONE, "",
                dangerousCombat ? "no carried shield" : "passive hunt does not justify shield handling");
    }

    private static Map<String, Integer> normalizedCounts(Map<String, Integer> rawCounts) {
        Objects.requireNonNull(rawCounts, "rawCounts");
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        rawCounts.forEach((item, count) -> {
            if (count == null || count < 0) {
                throw new IllegalArgumentException("inventory counts cannot be negative");
            }
            if (count > 0) normalized.merge(simple(item), count, Math::addExact);
        });
        return normalized;
    }

    private static String simple(String raw) {
        String value = Objects.requireNonNullElse(raw, "").trim().toLowerCase(Locale.ROOT)
                .replace(' ', '_');
        while (value.startsWith("minecraft:")) value = value.substring("minecraft:".length());
        return value;
    }

    private record Material(String weapon, String item, int items, int planks) {
    }
}
