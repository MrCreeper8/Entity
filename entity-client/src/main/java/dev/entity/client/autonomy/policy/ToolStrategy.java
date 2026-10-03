package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Costed replacement-tool policy.
 *
 * <p>The old policy used fixed block-count thresholds. That made a carried
 * stack of diamonds invisible until an arbitrary threshold was crossed, even
 * when the alternative was a dangerous trip to the surface to bootstrap
 * wood, stone, iron, fuel, and a furnace. This policy prices the whole
 * replacement decision: exact remaining work, usable durability already
 * carried, consumed mission cargo, local materials, acquisition travel,
 * bootstrap actions, and danger.</p>
 *
 * <p>The compatibility method remains deliberately small for existing
 * planner callers. Live execution can use {@link #choose(ReplacementRequest)}
 * and supply measured travel/risk/durability facts.</p>
 */
public final class ToolStrategy {
    private static final List<PickaxeProfile> PICKAXES = List.of(
            new PickaxeProfile("wooden_pickaxe", ResourceCatalog.ToolTier.WOOD,
                    "oak_planks", 59, 2.0, 0.30, 4.0, 1),
            new PickaxeProfile("stone_pickaxe", ResourceCatalog.ToolTier.STONE,
                    "cobblestone", 131, 4.0, 0.55, 12.0, 2),
            new PickaxeProfile("iron_pickaxe", ResourceCatalog.ToolTier.IRON,
                    "iron_ingot", 250, 6.0, 3.0, 36.0, 5),
            new PickaxeProfile("diamond_pickaxe", ResourceCatalog.ToolTier.DIAMOND,
                    "diamond", 1561, 8.0, 8.0, 150.0, 8));

    private ToolStrategy() {
    }

    /**
     * Compatibility entry point. It still performs a cost comparison; it does
     * not restore the former 4/32/96-block thresholds.
     */
    public static String replacementPickaxe(
            ResourceCatalog.ToolTier required,
            int remainingBlocks,
            Map<String, Integer> availableItems) {
        return choose(ReplacementRequest.local(required, remainingBlocks, availableItems)).item();
    }

    /** Returns the lowest total-cost legal candidate with a deterministic tie break. */
    public static Choice choose(ReplacementRequest request) {
        Objects.requireNonNull(request, "request");
        ArrayList<Choice> choices = new ArrayList<>();
        for (PickaxeProfile profile : PICKAXES) {
            if (!profile.tier().satisfies(request.requiredTier())) continue;
            choices.add(price(profile, request));
        }
        return choices.stream()
                .min(Comparator.comparingDouble(Choice::totalCost)
                        .thenComparingInt(choice -> rank(choice.tier()))
                        .thenComparing(Choice::item))
                .orElseThrow(() -> new IllegalArgumentException(
                        "no pickaxe can satisfy " + request.requiredTier()));
    }

    /** Exposes all legal candidate prices for diagnostics and future telemetry. */
    public static List<Choice> choices(ReplacementRequest request) {
        Objects.requireNonNull(request, "request");
        ArrayList<Choice> result = new ArrayList<>();
        for (PickaxeProfile profile : PICKAXES) {
            if (profile.tier().satisfies(request.requiredTier())) result.add(price(profile, request));
        }
        result.sort(Comparator.comparingDouble(Choice::totalCost)
                .thenComparingInt(choice -> rank(choice.tier()))
                .thenComparing(Choice::item));
        return List.copyOf(result);
    }

    private static Choice price(PickaxeProfile profile, ReplacementRequest request) {
        int carriedDurability = Math.max(0,
                normalizedValue(request.usableDurabilityByPickaxe(), profile.item()));
        int baseWork = Math.max(1, request.remainingBlocks());
        int adjustedWork = baseWork;
        int craftCount = 0;

        // Cargo spent on the tool increases the exact guaranteed workload. A
        // short fixed-point loop handles a durability-boundary crossing.
        for (int iteration = 0; iteration < 4; iteration++) {
            int uncovered = Math.max(0, adjustedWork - carriedDurability);
            int nextCraftCount = ceilDiv(uncovered, profile.durability());
            int cargoConsumed = cargoMatches(request.cargoItem(), profile.material())
                    ? Math.multiplyExact(nextCraftCount, 3)
                    : 0;
            int nextWork = Math.addExact(baseWork, cargoConsumed);
            if (nextCraftCount == craftCount && nextWork == adjustedWork) break;
            craftCount = nextCraftCount;
            adjustedWork = nextWork;
        }

        int materialNeeded = Math.multiplyExact(craftCount, 3);
        int materialOnHand = materialCount(request.availableItems(), profile.material());
        int missingMaterial = Math.max(0, materialNeeded - materialOnHand);
        int sticksNeeded = Math.multiplyExact(craftCount, 2);
        int missingSticks = Math.max(0,
                sticksNeeded - materialCount(request.availableItems(), "stick"));

        double materialCost = materialNeeded * profile.opportunityCost();
        double miningCost = adjustedWork / profile.speed();
        double craftingCost = craftCount == 0 ? 0.0 : 1.5 * craftCount;

        double acquisitionCost = 0.0;
        if (missingMaterial > 0) {
            acquisitionCost += profile.missingBootstrapCost();
            acquisitionCost += missingMaterial * missingUnitCost(profile.material());
        }
        if (missingSticks > 0) {
            acquisitionCost += 2.0 + missingSticks * 0.75;
        }
        boolean remoteAcquisition = missingMaterial > 0 || missingSticks > 0;
        double travelCost = remoteAcquisition
                ? request.estimatedTravelBlocks() * 0.18
                * (1.0 + request.dangerFactor() * 0.60)
                : 0.0;
        // Stick acquisition is common to every pickaxe.  The previous model
        // charged a candidate's entire tier bootstrap whenever sticks alone
        // were missing.  That made a locally available stone head look more
        // expensive than a wooden head and sent Entity to the surface for a
        // disposable wooden pickaxe while carrying stacks of cobblestone.
        // Tier-specific bootstrap is payable only when that tier's head
        // material is actually missing; the shared live bootstrap estimate is
        // still charged for either missing component.
        double bootstrapActions = remoteAcquisition ? request.bootstrapActions() : 0.0;
        if (missingMaterial > 0) bootstrapActions += profile.intrinsicBootstrapActions();
        double bootstrapCost = bootstrapActions * 5.0
                * (1.0 + request.dangerFactor() * 0.35);

        int cargoConsumed = cargoMatches(request.cargoItem(), profile.material())
                ? materialNeeded
                : 0;
        // This is not a second arbitrary threshold: it prices the additional
        // guaranteed blocks created by consuming deliverable cargo.
        double cargoCost = cargoConsumed == 0 ? 0.0
                : cargoConsumed * (1.0 + Math.min(3.0,
                request.cargoDeficit() / (double) Math.max(1, request.remainingBlocks())));
        double total = materialCost + miningCost + craftingCost + acquisitionCost
                + travelCost + bootstrapCost + cargoCost;

        String reason = String.format(Locale.ROOT,
                "%s: %.2f total (work=%d, carriedDurability=%d, craft=%d, missingMaterial=%d, "
                        + "cargoConsumed=%d, travel=%.2f, bootstrap=%.2f)",
                profile.item(), total, adjustedWork, carriedDurability, craftCount,
                missingMaterial, cargoConsumed, travelCost, bootstrapCost);
        return new Choice(profile.item(), profile.tier(), total, adjustedWork,
                carriedDurability, craftCount, missingMaterial, cargoConsumed,
                remoteAcquisition, reason);
    }

    /** Rich live input; all counts are exact and all cost hints are non-negative. */
    public record ReplacementRequest(
            ResourceCatalog.ToolTier requiredTier,
            int remainingBlocks,
            int cargoDeficit,
            String cargoItem,
            Map<String, Integer> availableItems,
            Map<String, Integer> usableDurabilityByPickaxe,
            int estimatedTravelBlocks,
            int bootstrapActions,
            double dangerFactor) {
        public ReplacementRequest {
            requiredTier = Objects.requireNonNull(requiredTier, "requiredTier");
            if (remainingBlocks < 0) throw new IllegalArgumentException("remainingBlocks cannot be negative");
            if (cargoDeficit < 0) throw new IllegalArgumentException("cargoDeficit cannot be negative");
            cargoItem = normalizeOptional(cargoItem);
            availableItems = checkedCounts(availableItems, "availableItems");
            usableDurabilityByPickaxe = checkedCounts(
                    usableDurabilityByPickaxe, "usableDurabilityByPickaxe");
            if (estimatedTravelBlocks < 0) {
                throw new IllegalArgumentException("estimatedTravelBlocks cannot be negative");
            }
            if (bootstrapActions < 0) {
                throw new IllegalArgumentException("bootstrapActions cannot be negative");
            }
            if (!Double.isFinite(dangerFactor) || dangerFactor < 0.0 || dangerFactor > 10.0) {
                throw new IllegalArgumentException("dangerFactor must be finite and 0..10");
            }
        }

        public static ReplacementRequest local(
                ResourceCatalog.ToolTier required,
                int remainingBlocks,
                Map<String, Integer> availableItems) {
            return new ReplacementRequest(required, Math.max(1, remainingBlocks),
                    Math.max(1, remainingBlocks), "", availableItems, Map.of(),
                    0, 0, 0.0);
        }
    }

    /** Auditable result rather than only an opaque selected item ID. */
    public record Choice(
            String item,
            ResourceCatalog.ToolTier tier,
            double totalCost,
            int adjustedWorkload,
            int carriedDurability,
            int toolsToCraft,
            int missingMaterial,
            int cargoConsumed,
            boolean requiresRemoteAcquisition,
            String reason) {
        public Choice {
            item = requireText(item, "item");
            tier = Objects.requireNonNull(tier, "tier");
            if (!Double.isFinite(totalCost) || totalCost < 0.0) {
                throw new IllegalArgumentException("totalCost must be finite and non-negative");
            }
            reason = requireText(reason, "reason");
        }
    }

    private record PickaxeProfile(
            String item,
            ResourceCatalog.ToolTier tier,
            String material,
            int durability,
            double speed,
            double opportunityCost,
            double missingBootstrapCost,
            int intrinsicBootstrapActions) {
    }

    private static Map<String, Integer> checkedCounts(Map<String, Integer> raw, String field) {
        Objects.requireNonNull(raw, field);
        LinkedHashMap<String, Integer> checked = new LinkedHashMap<>();
        raw.forEach((item, count) -> {
            String normalized = normalize(item);
            if (count == null || count < 0) {
                throw new IllegalArgumentException(field + " contains a negative/null count for " + normalized);
            }
            checked.merge(normalized, count, Math::addExact);
        });
        return Map.copyOf(checked);
    }

    private static int materialCount(Map<String, Integer> items, String material) {
        if (material.equals("oak_planks")) {
            return items.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith("_planks"))
                    .mapToInt(Map.Entry::getValue).sum();
        }
        if (material.equals("cobblestone")) {
            return sum(items, "cobblestone", "cobbled_deepslate", "blackstone");
        }
        return normalizedValue(items, material);
    }

    private static int sum(Map<String, Integer> items, String... ids) {
        int total = 0;
        for (String id : ids) total = Math.addExact(total, normalizedValue(items, id));
        return total;
    }

    private static int normalizedValue(Map<String, Integer> values, String item) {
        return Math.max(0, values.getOrDefault(normalize(item), 0));
    }

    private static boolean cargoMatches(String cargo, String material) {
        return !cargo.isEmpty() && cargo.equals(normalize(material));
    }

    private static double missingUnitCost(String material) {
        return switch (material) {
            case "oak_planks" -> 2.0;
            case "cobblestone" -> 3.0;
            case "iron_ingot" -> 14.0;
            case "diamond" -> 40.0;
            default -> 10.0;
        };
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? "" : normalize(value);
    }

    private static String normalize(String value) {
        String normalized = requireText(value, "item").toLowerCase(Locale.ROOT).replace(' ', '_');
        while (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }

    private static int ceilDiv(int value, int divisor) {
        if (value <= 0) return 0;
        return 1 + (value - 1) / divisor;
    }

    private static int rank(ResourceCatalog.ToolTier tier) {
        return switch (tier) {
            case NONE -> 0;
            case WOOD -> 1;
            case STONE -> 2;
            case IRON -> 3;
            case DIAMOND -> 4;
        };
    }
}
