package dev.entity.client;

import dev.entity.client.config.EntityClientConfig;
import dev.entity.client.recipe.MinecraftRecipeSnapshotService;
import dev.entity.client.runtime.EntityRuntime;
import dev.entity.client.runtime.HeadlessRuntimeController;
import dev.entity.client.runtime.HeadlessRuntimeMode;
import dev.entity.client.runtime.WorldStateBindingStore;
import dev.entity.client.runtime.WorldStateScope;
import dev.entity.core.build.BuildIdentity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Client-only entrypoint. No server or owner-side mod is required. */
public final class EntityClientMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Entity2");
    private static final String BACKGROUND_INPUT_ISOLATION_MARKER =
            "background-input-isolation";
    // Mouse.lockCursor can run before Fabric invokes this entrypoint. Resolve
    // the already-staged marker when this class is first reached by the mixin,
    // so even Minecraft's first grab attempt is rejected instead of producing
    // one visible desktop-cursor jump during startup.
    private static volatile HeadlessRuntimeMode runtimeMode = detectRuntimeMode();
    private static volatile boolean backgroundInputIsolationEnabled =
            runtimeMode.background() || detectBackgroundInputIsolation();

    private EntityRuntime runtime;
    private MinecraftRecipeSnapshotService recipeSnapshots;
    private HeadlessRuntimeController headlessRuntime;
    private Path dataDirectory;
    private EntityClientConfig config;
    private WorldStateBindingStore worldStateBindings;
    private WorldStateScope activeWorldScope;

    @Override
    public void onInitializeClient() {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        // This is a dedicated bot client. Opening the pause screen whenever its
        // window loses focus hides live diagnostics and can interrupt vanilla
        // input handling. Persist the dedicated-instance setting up front.
        if (minecraft.options != null && minecraft.options.pauseOnLostFocus) {
            minecraft.options.pauseOnLostFocus = false;
            minecraft.options.write();
            LOGGER.info("Disabled pause-on-lost-focus for the dedicated Entity client");
        }
        dataDirectory = FabricLoader.getInstance().getConfigDir().resolve("entity2");
        runtimeMode = detectRuntimeMode();
        backgroundInputIsolationEnabled = backgroundInputIsolationEnabled
                || runtimeMode.background()
                || Files.isRegularFile(dataDirectory.resolve(
                        BACKGROUND_INPUT_ISOLATION_MARKER));
        boolean backgroundInputIsolation = backgroundInputIsolationEnabled;
        backgroundInputIsolationEnabled = backgroundInputIsolation;
        if (backgroundInputIsolation) {
            maintainBackgroundInputIsolation(minecraft);
            ClientTickEvents.END_CLIENT_TICK.register(
                    EntityClientMod::maintainBackgroundInputIsolation);
            LOGGER.info("Enabled background input isolation for the disposable Entity client");
        }
        config = EntityClientConfig.load(dataDirectory, LOGGER);
        worldStateBindings = new WorldStateBindingStore(dataDirectory);
        WorldStateBindingStore.LoadResult binding = worldStateBindings.load();
        if (!binding.warning().isBlank()) LOGGER.error(binding.warning());
        activeWorldScope = binding.scope().orElse(null);
        try {
            runtime = createRuntime(minecraft, activeWorldScope);
        } catch (IOException error) {
            LOGGER.error("Entity 2 could not restore its durable mission store; client adapter is disabled", error);
            return;
        }
        headlessRuntime = new HeadlessRuntimeController(
                minecraft,
                dataDirectory,
                runtimeMode,
                () -> runtime != null && runtime.bridgeConnected(),
                LOGGER);
        headlessRuntime.start();
        recipeSnapshots = new MinecraftRecipeSnapshotService();
        var localImports = new dev.entity.client.blueprint.LocalBlueprintInbox(dataDirectory);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            recipeSnapshots.tick(client);
            localImports.tick(client);
            rebindWorldRuntime(client);
            if (runtime != null) runtime.tick();
            headlessRuntime.tick();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            recipeSnapshots.reset("client stopping");
            headlessRuntime.close();
            if (runtime != null) runtime.close();
        });
        runtime.start();
        LOGGER.info("Entity 2 initialized: persistent core, Baritone adapter, and survival supervisor are active; {}",
                BuildIdentity.current().display());
    }

    private EntityRuntime createRuntime(
            MinecraftClient minecraft,
            WorldStateScope scope) throws IOException {
        Path stateDirectory = scope == null
                ? worldStateBindings.unboundDirectory()
                : scope.directory(dataDirectory);
        Files.createDirectories(stateDirectory);
        return new EntityRuntime(
                minecraft,
                config,
                dataDirectory,
                stateDirectory,
                scope,
                LOGGER);
    }

    /** Reconstructs all world-owned stores on the Minecraft main thread. */
    private void rebindWorldRuntime(MinecraftClient minecraft) {
        EntityRuntime current = runtime;
        if (current == null) return;
        Optional<WorldStateScope> requested = current.requestedWorldScope();
        if (requested.isEmpty() || requested.get().equals(activeWorldScope)) return;
        WorldStateScope next = requested.orElseThrow();
        try {
            worldStateBindings.activate(next);
        } catch (IOException failure) {
            LOGGER.error("Entity 2 could not bind state to Paper world {}; execution remains neutral",
                    next.key(), failure);
            return;
        }

        current.close();
        runtime = null;
        try {
            EntityRuntime replacement = createRuntime(minecraft, next);
            activeWorldScope = next;
            runtime = replacement;
            replacement.start();
            LOGGER.info("Entity 2 rebound every world-owned store to Paper world {}", next.key());
        } catch (IOException failure) {
            LOGGER.error("Entity 2 could not open the state namespace for Paper world {}; "
                    + "the client remains disabled until restart", next.key(), failure);
        }
    }

    /** Used by the mouse mixin before vanilla can reacquire the desktop cursor. */
    public static boolean isBackgroundInputIsolationEnabled() {
        return backgroundInputIsolationEnabled;
    }

    /** Used by the early Window mixin before GLFW creates a visible window. */
    public static boolean isBackgroundWindowEnabled() {
        return runtimeMode.background();
    }

    private static boolean detectBackgroundInputIsolation() {
        try {
            return Files.isRegularFile(FabricLoader.getInstance().getConfigDir()
                    .resolve("entity2")
                    .resolve(BACKGROUND_INPUT_ISOLATION_MARKER));
        } catch (RuntimeException unavailableDuringBootstrap) {
            // onInitializeClient performs the same check once Fabric's client
            // lifecycle is fully established.
            return false;
        }
    }

    private static HeadlessRuntimeMode detectRuntimeMode() {
        String fileValue = null;
        try {
            Path path = FabricLoader.getInstance().getConfigDir()
                    .resolve("entity2")
                    .resolve(HeadlessRuntimeController.MODE_FILE);
            if (Files.isRegularFile(path)) fileValue = Files.readString(path);
        } catch (IOException | RuntimeException unavailableDuringBootstrap) {
            // onInitializeClient performs the same check after Fabric boot.
        }
        return HeadlessRuntimeMode.resolve(
                System.getProperty("entity2.runtimeMode"),
                System.getenv("ENTITY2_RUNTIME_MODE"),
                fileValue);
    }

    private static void maintainBackgroundInputIsolation(MinecraftClient minecraft) {
        if (minecraft.mouse != null && minecraft.mouse.isCursorLocked()) {
            minecraft.mouse.unlockCursor();
        }
        if (minecraft.isWindowFocused()) {
            minecraft.onWindowFocusChanged(false);
        }
    }
}
