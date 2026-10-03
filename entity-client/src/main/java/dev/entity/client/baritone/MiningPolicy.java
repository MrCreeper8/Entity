package dev.entity.client.baritone;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Pure policy for Baritone prospecting height and observable drop postconditions. */
public final class MiningPolicy {
    private MiningPolicy() {
    }

    public static Plan plan(String[] rawBlocks, String dimension, int currentY) {
        String[] blocks = Arrays.stream(rawBlocks)
                .map(MiningPolicy::simpleIdentifier)
                .toArray(String[]::new);
        Set<String> ids = new LinkedHashSet<>(Arrays.asList(blocks));
        boolean undergroundOre = ids.stream().anyMatch(MiningPolicy::isUndergroundOre);
        int targetY = preferredY(ids, dimension, currentY);
        return new Plan(undergroundOre, targetY, expectedDropIds(ids));
    }

    /**
     * An ore's statistical peak is not proof that terrain exists at that height.
     * In legitimate exploration, keep an above-ground peak below the locally
     * loaded surface instead of asking the native miner to pillar into air.
     * Known visible veins remain MineProcess targets regardless of this search Y.
     */
    public static int legitimateSearchY(Plan plan, int localSurfaceY, int worldBottomY) {
        if (!plan.prospecting() || localSurfaceY <= worldBottomY + 8
                || plan.targetY() < localSurfaceY) return plan.targetY();
        return Math.max(worldBottomY + 2, localSurfaceY - 8);
    }

    private static int preferredY(Set<String> ids, String dimension, int currentY) {
        if (dimension != null && dimension.contains("the_nether")) return 15;
        if (containsAny(ids, "diamond_ore", "deepslate_diamond_ore",
                "redstone_ore", "deepslate_redstone_ore")) return -54;
        if (containsAny(ids, "lapis_ore", "deepslate_lapis_ore")) return 0;
        if (containsAny(ids, "gold_ore", "deepslate_gold_ore")) return -16;
        if (containsAny(ids, "iron_ore", "deepslate_iron_ore")) return 16;
        if (containsAny(ids, "copper_ore", "deepslate_copper_ore")) return 48;
        if (containsAny(ids, "coal_ore", "deepslate_coal_ore")) return 96;
        if (containsAny(ids, "emerald_ore", "deepslate_emerald_ore")) return 128;
        return currentY;
    }

    private static Set<String> expectedDropIds(Set<String> blocks) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        for (String block : blocks) {
            items.add(block); // Silk Touch and blocks that drop themselves.
            switch (block) {
                case "diamond_ore", "deepslate_diamond_ore" -> items.add("diamond");
                case "iron_ore", "deepslate_iron_ore" -> items.add("raw_iron");
                case "gold_ore", "deepslate_gold_ore" -> items.add("raw_gold");
                case "nether_gold_ore" -> items.add("gold_nugget");
                case "nether_quartz_ore" -> items.add("quartz");
                case "copper_ore", "deepslate_copper_ore" -> items.add("raw_copper");
                case "redstone_ore", "deepslate_redstone_ore" -> items.add("redstone");
                case "lapis_ore", "deepslate_lapis_ore" -> items.add("lapis_lazuli");
                case "coal_ore", "deepslate_coal_ore" -> items.add("coal");
                case "emerald_ore", "deepslate_emerald_ore" -> items.add("emerald");
                case "stone" -> items.add("cobblestone");
                case "deepslate" -> items.add("cobbled_deepslate");
                case "grass_block", "podzol", "mycelium", "dirt_path", "farmland" -> items.add("dirt");
                case "clay" -> items.add("clay_ball");
                case "wheat" -> items.add("wheat_seeds");
                case "carrots" -> items.add("carrot");
                case "potatoes" -> items.add("potato");
                case "beetroots" -> {
                    items.add("beetroot");
                    items.add("beetroot_seeds");
                }
                case "gravel" -> items.add("flint");
                case "glowstone" -> items.add("glowstone_dust");
                case "sea_lantern" -> items.add("prismarine_crystals");
                case "amethyst_cluster" -> items.add("amethyst_shard");
                default -> {
                }
            }
        }
        return Set.copyOf(items);
    }

    private static boolean isUndergroundOre(String block) {
        return block.endsWith("_ore") || block.equals("ancient_debris");
    }

    private static boolean containsAny(Set<String> values, String... candidates) {
        for (String candidate : candidates) {
            if (values.contains(candidate)) return true;
        }
        return false;
    }

    private static String simpleIdentifier(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return normalized.startsWith("minecraft:")
                ? normalized.substring("minecraft:".length())
                : normalized;
    }

    public record Plan(boolean prospecting, int targetY, Set<String> expectedItemIds) {
        public Plan {
            expectedItemIds = Set.copyOf(expectedItemIds);
        }
    }
}
