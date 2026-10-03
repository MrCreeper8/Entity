package dev.entity.client.recipe;

import dev.entity.core.recipe.RecipeSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.recipe.RecipeDisplayEntry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;

/** Captures the synchronized, unlocked portion of a joined client's recipe book. */
public final class MinecraftRecipeSnapshotAdapter {
    private static final String CLIENT_LIMIT =
            "Minecraft 1.21.8 synchronizes ordinary recipe displays only when they are added "
                    + "to this player's recipe book; this is not the server's complete recipe graph";

    public Capture capture(MinecraftClient minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.world == null) {
            return Capture.unavailable("client has not joined a world");
        }
        if (!minecraft.isOnThread()) {
            return Capture.unavailable("recipe capture must run on the Minecraft client thread");
        }
        ClientPlayNetworkHandler network = minecraft.getNetworkHandler();
        if (network == null || network.getRecipeManager() == null || network.getFuelRegistry() == null) {
            return Capture.unavailable("recipe/fuel synchronization is not ready");
        }

        try {
            TreeMap<Integer, RecipeDisplayEntry> uniqueEntries = new TreeMap<>();
            for (RecipeResultCollection result : minecraft.player.getRecipeBook().getOrderedResults()) {
                for (RecipeDisplayEntry entry : result.getAllRecipes()) {
                    uniqueEntries.putIfAbsent(entry.id().index(), entry);
                }
            }
            if (uniqueEntries.isEmpty()) {
                return Capture.unavailable(
                        "no unlocked recipe display entries have arrived for this player");
            }

            MinecraftRecipeDisplayDecoder.DecodeBatch decoded =
                    MinecraftRecipeDisplayDecoder.decodeEntries(uniqueEntries.values());
            if (decoded.recipes().isEmpty()) {
                return Capture.unavailable(
                        "unlocked recipe displays arrived, but none were supported ordinary recipes");
            }
            LinkedHashSet<String> limitations = new LinkedHashSet<>();
            limitations.add(CLIENT_LIMIT);
            limitations.add("packet-local NetworkRecipeId values are intentionally discarded");
            limitations.add("output data components are projected to stable item ID and count");
            limitations.addAll(decoded.limitations());
            RecipeSnapshot snapshot = RecipeSnapshot.partialUnlocked(
                    decoded.recipes(),
                    MinecraftRecipeDisplayDecoder.fuelBurnTicks(network.getFuelRegistry()),
                    "unlocked client recipe-book displays; " + decoded.unsupported()
                            + " unsupported display(s) skipped");
            return new Capture(
                    snapshot,
                    decoded.observed(),
                    decoded.recipes().size(),
                    decoded.unsupported(),
                    List.copyOf(limitations));
        } catch (RuntimeException failure) {
            return Capture.unavailable(
                    "client recipe extraction failed safely: " + failure.getClass().getSimpleName());
        }
    }

    public record Capture(
            RecipeSnapshot snapshot,
            int observedEntries,
            int convertedRecipes,
            int unsupportedEntries,
            List<String> limitations) {
        public Capture {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            limitations = List.copyOf(limitations);
            if (observedEntries < 0 || convertedRecipes < 0 || unsupportedEntries < 0) {
                throw new IllegalArgumentException("capture counts cannot be negative");
            }
        }

        public static Capture unavailable(String reason) {
            return new Capture(
                    RecipeSnapshot.unavailable(reason), 0, 0, 0, List.of(CLIENT_LIMIT));
        }
    }
}
