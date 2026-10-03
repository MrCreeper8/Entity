package dev.entity.client.recipe;

import dev.entity.core.recipe.RecipeSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

/**
 * Connection-scoped runtime cache. Tick it on the client thread after world
 * join; disconnects and server changes synchronously discard the old graph.
 */
public final class MinecraftRecipeSnapshotService {
    private static final int REFRESH_INTERVAL_TICKS = 20;

    private final MinecraftRecipeSnapshotAdapter adapter = new MinecraftRecipeSnapshotAdapter();
    private MinecraftRecipeSnapshotAdapter.Capture current =
            MinecraftRecipeSnapshotAdapter.Capture.unavailable("client has not joined a world");
    private ClientPlayNetworkHandler connection;
    private int ticksUntilRefresh;

    public void tick(MinecraftClient minecraft) {
        ClientPlayNetworkHandler observed = minecraft == null ? null : minecraft.getNetworkHandler();
        boolean joined = minecraft != null && minecraft.player != null && minecraft.world != null
                && observed != null;
        if (!joined) {
            reset("client disconnected");
            return;
        }
        if (observed != connection) {
            connection = observed;
            current = MinecraftRecipeSnapshotAdapter.Capture.unavailable(
                    "new connection is waiting for recipe synchronization");
            ticksUntilRefresh = 0;
        }
        if (ticksUntilRefresh-- > 0) return;
        ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
        current = adapter.capture(minecraft);
    }

    public RecipeSnapshot currentSnapshot() {
        return current.snapshot();
    }

    public MinecraftRecipeSnapshotAdapter.Capture currentCapture() {
        return current;
    }

    public void reset(String reason) {
        connection = null;
        ticksUntilRefresh = 0;
        current = MinecraftRecipeSnapshotAdapter.Capture.unavailable(reason);
    }
}
