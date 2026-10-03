package dev.entity.client.blueprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Material batches for the existing planner; this policy never places or gathers a block. */
public final class BlueprintMaterialSupplyPolicy {
    private static final int PRODUCTION_SLOTS = 2;
    private BlueprintMaterialSupplyPolicy() {}

    /** One receiving slot in addition to the workspace retained by batch(). */
    public static int minimumEmptySlotsForBatch() { return PRODUCTION_SLOTS + 1; }

    /** Keep already-carried project cargo while the existing capacity owner makes room. */
    public static Map<String,Integer> carriedProjectMaterials(Map<String,Integer> remaining,
            Map<String,Integer> carried,boolean supportsNeeded) {
        Map<String,Integer> inventory=canonical(carried), required=canonical(remaining), keep=new TreeMap<>();
        if(supportsNeeded&&!required.isEmpty()) {
            String support=inventory.getOrDefault("minecraft:dirt",0)>0?"minecraft:dirt":"minecraft:cobblestone";
            required.merge(support,64,Math::max);
        }
        required.forEach((item,count)->{
            int physical=Math.min(count,inventory.getOrDefault(item,0));
            if(physical>0)keep.put(item,physical);
        });
        return Map.copyOf(keep);
    }

    /** Native bottom-up work is authoritative; an empty observed layer never falls through. */
    public static Map<String,Integer> currentStage(Map<String,Integer> remaining,
            java.util.Optional<Map<String,Integer>> nativeLayer) {
        return Map.copyOf(canonical(nativeLayer.orElse(remaining)));
    }
    /** One ordinary working stack; any carried dirt/cobble can seed native access. */
    public static Map<String,Integer> withSupportReserve(Map<String,Integer> remaining,Map<String,Integer> carried,boolean needed) {
        Map<String,Integer> result=canonical(remaining), inventory=canonical(carried);
        if(needed&&!result.isEmpty()&&inventory.getOrDefault("minecraft:dirt",0)==0
                &&inventory.getOrDefault("minecraft:cobblestone",0)==0)result.merge("minecraft:dirt",64,Math::max);
        return Map.copyOf(result);
    }

    /** Native construction can begin with partial supplies, including an air-only clearing job. */
    public static boolean canStart(Map<String, Integer> remaining, Map<String, Integer> carried) {
        Map<String, Integer> inventory = canonical(carried);
        return remaining.isEmpty() || canonical(remaining).keySet().stream()
                .anyMatch(item -> inventory.getOrDefault(item, 0) > 0);
    }

    /**
     * A bulk deficit is not proof that a paused builder needs supplies: 64 carried cobblestone
     * can still build part of a 2,000-block wall. Refill only exhausted material kinds. If every
     * kind is carried, the native blockage is access/placement, not permission to mine more.
     */
    public static Map<String, Integer> exhausted(
            Map<String, Integer> remaining, Map<String, Integer> carried) {
        Map<String, Integer> inventory = canonical(carried);
        Map<String, Integer> absent = new TreeMap<>();
        canonical(remaining).forEach((item, count) -> {
            if (inventory.getOrDefault(item, 0) == 0) absent.put(item, count);
        });
        return Map.copyOf(absent);
    }

    /**
     * Allocate whole inventory slots round-robin across exhausted material kinds. Two actual
     * empty slots remain available for production intermediates/workstations. The batch is an
     * absolute carried target, as required by root.acquire/root.batch.acquire, not an added count.
     */
    public static Map<String, Integer> batch(
            Map<String, Integer> remaining, Map<String, Integer> carried,
            Map<String, Integer> maximumStackSizes, int emptyMainSlots) {
        Map<String, Integer> missing = new TreeMap<>(exhausted(remaining, carried));
        Map<String, Integer> stackSizes = canonical(maximumStackSizes);
        Map<String, Integer> result = new LinkedHashMap<>();
        int slots = Math.max(0, Math.min(36, emptyMainSlots) - PRODUCTION_SLOTS);
        while (slots > 0) {
            boolean allocated = false;
            for (var need : missing.entrySet()) {
                int current = result.getOrDefault(need.getKey(), 0);
                if (current >= need.getValue()) continue;
                int maximum = Math.max(1, Math.min(64, stackSizes.getOrDefault(need.getKey(), 64)));
                result.put(need.getKey(), current + Math.min(maximum, need.getValue() - current));
                slots--;
                allocated = true;
                if (slots == 0) break;
            }
            if (!allocated) break;
        }
        return Map.copyOf(result);
    }

    public static boolean supplied(Map<String, Integer> targets, Map<String, Integer> carried) {
        Map<String, Integer> inventory = canonical(carried);
        return canonical(targets).entrySet().stream()
                .allMatch(target -> inventory.getOrDefault(target.getKey(), 0) >= target.getValue());
    }

    public static String fingerprint(Map<String, Integer> remaining, Map<String, Integer> carried) {
        try {
            Map<String, Integer> relevantInventory = canonical(carried);
            relevantInventory.keySet().retainAll(canonical(remaining).keySet());
            String observed = encode(remaining) + "\n" + encode(relevantInventory);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(observed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String encode(Map<String, Integer> counts) {
        return canonical(counts).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    public static Map<String, Integer> decode(String encoded) {
        Map<String, Integer> result = new TreeMap<>();
        for (String entry : encoded.split(",")) {
            String[] parts = entry.split("=", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid saved blueprint supply batch");
            int count = Integer.parseInt(parts[1]);
            if (count <= 0 || count > 2304 || result.putIfAbsent(parts[0], count) != null)
                throw new IllegalArgumentException("Invalid saved blueprint supply target");
        }
        return canonical(result);
    }

    private static Map<String, Integer> canonical(Map<String, Integer> source) {
        Map<String, Integer> result = new TreeMap<>();
        source.forEach((item, count) -> {
            if (count != null && count > 0) {
                String id = item.contains(":") ? item : "minecraft:" + item;
                result.merge(id, count, Math::max);
            }
        });
        return result;
    }
}
