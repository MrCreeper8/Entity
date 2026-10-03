package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A forecast for one already-bounded Stock cycle, not another acquisition owner.
 * Recipe quantities come only from the existing compiler's consumed totals.
 * Generic food may contribute its existing runtime fuel budget separately;
 * callers must not count the same cooking work in both inputs.
 */
public final class HomeStockDemandForecast {
    private static final Set<String> MATERIAL_CATEGORIES = Set.of("fuel", "logs");

    private HomeStockDemandForecast() {}

    public record Demand(int retainedFloor, int knownConsumption, int carried, int availableHome,
                         int temporaryTarget, int withdraw, int acquire, int deferredForCapacity) {}

    public record Forecast(List<HomeStockPolicy.Target> targets, Map<String, Demand> materials) {
        public Forecast {
            targets = List.copyOf(targets);
            materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        }
    }

    /**
     * Non-food goals for a forecast-only compilation. Existing Home analysis has
     * already deducted observed available storage. The merged inventory is used
     * only for planning; this never claims an unperformed withdrawal occurred.
     * Food remains with its current family selector and furnace-session budget.
     */
    public static List<AcquisitionRequest.ItemGoal> recipeGoals(
            HomeStockPolicy.Analysis base, Map<String, Integer> carriedAndAvailableHome) {
        Objects.requireNonNull(base, "base");
        LinkedHashMap<String, Integer> missing = new LinkedHashMap<>();
        for (HomeStockPolicy.AcquisitionNeed need : base.acquisitions()) {
            if (!need.categoryId().equals("food")) {
                missing.merge(need.item(), need.count(), Math::addExact);
            }
        }
        return HomeStockPolicy.absoluteAcquisitionGoals(missing, carriedAndAvailableHome).entrySet().stream()
                .map(entry -> new AcquisitionRequest.ItemGoal(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * Use one complete compiler plan for the remaining non-food work, or null
     * when there are no such goals. Runtime consumption is exact already-planned
     * work (e.g. requiredRegularFoodFuel), never a guessed future hunt/recipe.
     */
    public static Map<String, Integer> knownConsumption(
            AcquisitionPlan recipePlan, Map<String, Integer> runtimeConsumption) {
        LinkedHashMap<String, Integer> consumed = new LinkedHashMap<>(counts(runtimeConsumption));
        if (recipePlan != null) {
            counts(recipePlan.totals().consumed()).forEach((item, count) ->
                    consumed.merge(item, count, Math::addExact));
        }
        return Collections.unmodifiableMap(consumed);
    }

    /**
     * Raise only fuel/log carried targets by known spending, capped at physical
     * receiving room. Capacity is additional item count in the ordinary player
     * inventory for that category, not total inventory size or storage capacity.
     * Unknown capacity defaults to zero. Existing carried above-floor material
     * can still be retained with no free slot.
     * The caller must supply currently verified available Home counts; an
     * unloaded/inaccessible/unobserved container is not an empty map. Defer this
     * optimization when the storage observation needed for it is incomplete.
     *
     * <p>Do not merge the resulting acquisitions: pass {@code targets} to the
     * existing HomeStockPolicy.analyze and retain its one-category plannerGoals,
     * full-inventory deposit escape and durable return/reobserve checkpoints.
     * Recompute after real consumption or a completed transfer/acquisition; do
     * not accumulate yesterday's forecast into today's retained profile.</p>
     * These targets affect outbound Home accounting only. They must never hide
     * the very fuel/logs being funded from the native recipe/mining executor.
     */
    public static Forecast forecast(
            List<HomeStockPolicy.Target> retainedProfile,
            Map<String, Integer> carried,
            Map<String, Integer> availableHome,
            Map<String, Integer> knownConsumption,
            Map<String, Integer> additionalCapacityByCategory) {
        Objects.requireNonNull(retainedProfile, "retainedProfile");
        Map<String, Integer> player = counts(carried);
        Map<String, Integer> home = counts(availableHome);
        Map<String, Integer> consumed = counts(knownConsumption);
        Map<String, Integer> capacity = counts(additionalCapacityByCategory);
        ArrayList<HomeStockPolicy.Target> targets = new ArrayList<>();
        LinkedHashMap<String, Demand> materials = new LinkedHashMap<>();
        for (HomeStockPolicy.Target retained : retainedProfile) {
            if (!MATERIAL_CATEGORIES.contains(retained.id())) {
                targets.add(retained);
                continue;
            }
            int spending = count(consumed, retained.acceptedItems());
            int playerCount = count(player, retained.acceptedItems());
            int homeCount = count(home, retained.acceptedItems());
            int floor = retained.desiredCount();
            long receivableTotal = (long) playerCount + capacity.getOrDefault(retained.id(), 0);
            int accommodatedSpending = (int) Math.min(spending, Math.max(0L, receivableTotal - floor));
            int temporaryTarget = Math.addExact(floor, accommodatedSpending);
            int shortfall = Math.max(0, temporaryTarget - playerCount);
            int withdrawal = Math.min(shortfall, homeCount);
            int acquisition = shortfall - withdrawal;
            materials.put(retained.id(), new Demand(floor, spending, playerCount, homeCount,
                    temporaryTarget, withdrawal, acquisition, spending - accommodatedSpending));
            targets.add(temporaryTarget == floor ? retained : new HomeStockPolicy.Target(
                    retained.id(), temporaryTarget, retained.acquisitionItem(), retained.acceptedItems(),
                    retained.conversionPrecursors(), retained.completionRequirement(),
                    retained.reason() + "; retain " + accommodatedSpending + " for this cycle's known consumption"));
        }
        return new Forecast(targets, materials);
    }

    private static int count(Map<String, Integer> counts, List<String> accepted) {
        int result = 0;
        for (String item : accepted) result = Math.addExact(result, counts.getOrDefault(item, 0));
        return result;
    }

    private static Map<String, Integer> counts(Map<String, Integer> source) {
        Objects.requireNonNull(source, "counts");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        source.forEach((raw, value) -> {
            String item = Objects.requireNonNull(raw, "item").trim().toLowerCase(Locale.ROOT);
            if (item.startsWith("minecraft:")) item = item.substring("minecraft:".length());
            if (item.isBlank() || value == null || value < 0) throw new IllegalArgumentException("invalid forecast counts");
            if (value > 0) result.merge(item, value, Math::addExact);
        });
        return Collections.unmodifiableMap(result);
    }
}
