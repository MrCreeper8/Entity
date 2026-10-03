package dev.entity.client.autonomy.policy;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One pre-route inventory preparation, never a second native tree controller. */
public final class HarvestToolAccessPolicy {
    private HarvestToolAccessPolicy() { }

    public static boolean shouldPrepare(
            boolean nativeWorkOwned, String sourceKind, String blockAlternatives) {
        if (nativeWorkOwned || !"HARVEST".equals(sourceKind)
                || blockAlternatives == null || blockAlternatives.isBlank()) return false;
        return isWoodTarget(blockAlternatives);
    }

    /** Completion must agree with native work, including older direct-command checkpoints. */
    public static boolean requiresExtractionReturn(String sourceKind, String blockAlternatives) {
        return !"HARVEST".equals(sourceKind) && !isWoodTarget(blockAlternatives);
    }

    /** Classifies the direct Paper logs group without changing its item/count objective. */
    public static boolean isWoodTarget(String blockAlternatives) {
        if (blockAlternatives == null || blockAlternatives.isBlank()) return false;
        for (String raw : blockAlternatives.split(",", -1)) {
            String block = normalize(raw);
            if (block.equals("bamboo_block")) continue;
            if (block.endsWith("_wood")) {
                block = block.substring(0, block.length() - "_wood".length()) + "_log";
            } else if (block.endsWith("_hyphae")) {
                block = block.substring(0, block.length() - "_hyphae".length()) + "_stem";
            }
            if (!WoodLogFamilyPolicy.members().contains(block)) return false;
        }
        return true;
    }

    /**
     * Baritone 1.15 autoTool sees only slots 0..8. Its inventory behavior reserves 0 for
     * the stone-mining tool and 8 for throwaway blocks, so an axe must remain in 1..7.
     * A normal SWAP retains whatever previously occupied that destination in the source slot.
     */
    public static Optional<Preparation> choose(
            List<Candidate> candidates, Set<Integer> emptyHotbarSlots, int selectedSlot) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(emptyHotbarSlots, "emptyHotbarSlots");
        Optional<Candidate> best = candidates.stream()
                .filter(candidate -> candidate.remainingDurability() > 1 && candidate.miningSpeed() > 1.0)
                .min(Comparator.comparingDouble(Candidate::miningSpeed).reversed()
                        .thenComparing(Comparator.comparingInt(Candidate::remainingDurability).reversed())
                        .thenComparingInt(Candidate::inventorySlot));
        if (best.isEmpty()) return Optional.empty();
        Candidate tool = best.orElseThrow();
        int destination = tool.inventorySlot();
        if (!isStableToolSlot(destination)) {
            destination = emptyHotbarSlots.stream().filter(HarvestToolAccessPolicy::isStableToolSlot)
                    .min(Integer::compareTo).orElse(isStableToolSlot(selectedSlot) ? selectedSlot : 1);
        }
        return Optional.of(new Preparation(tool.item(), destination, 2));
    }

    public static boolean isStableToolSlot(int slot) { return slot >= 1 && slot <= 7; }

    public record Candidate(String item, int inventorySlot, int remainingDurability, double miningSpeed) {
        public Candidate {
            item = normalize(Objects.requireNonNull(item, "item"));
            if (item.isBlank() || inventorySlot < 0 || inventorySlot >= 36
                    || remainingDurability < 0 || !Double.isFinite(miningSpeed) || miningSpeed < 0) {
                throw new IllegalArgumentException("invalid carried harvest tool fact");
            }
        }
    }

    public record Preparation(String item, int hotbarSlot, int minimumDurability) { }

    private static String normalize(String item) {
        String value = item.trim();
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }
}
