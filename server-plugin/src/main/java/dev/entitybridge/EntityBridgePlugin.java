package dev.entitybridge;

import dev.entitybridge.bridge.BridgeListener;
import dev.entitybridge.bridge.BridgeResult;
import dev.entitybridge.bridge.BridgeServer;
import dev.entitybridge.bridge.TelemetrySnapshot;
import dev.entitybridge.command.EntityCommand;
import dev.entitybridge.blueprint.BlueprintBuildAuthority;
import dev.entitybridge.config.BridgeSettings;
import dev.entitybridge.delivery.DeliveryReceiptListener;
import dev.entitybridge.delivery.DeliveryCommitRequest;
import dev.entitybridge.delivery.DeliveryCommitResult;
import dev.entitybridge.delivery.PaperDeliveryCommitter;
import dev.entitybridge.delivery.DeliveryTransactionRegistry;
import dev.entitybridge.mission.MissionRegistry;
import dev.entitybridge.mission.Mission;
import dev.entitybridge.recipe.PaperRecipeCatalogExporter;
import dev.entitybridge.recipe.RecipeCatalogSnapshot;
import dev.entitybridge.recipe.RecipeDiscoveryResult;
import dev.entitybridge.safety.EntityRelationshipSynchronization;
import dev.entitybridge.safety.FriendlyDamageGuardListener;
import dev.entitybridge.safety.TrustedIdentityRegistry;
import dev.entitybridge.state.WorldStatePathResolver;
import dev.entitybridge.stewardship.ProtectedAreaRegistry;
import dev.entitybridge.stewardship.ProtectedAreaHomePermitRegistry;
import dev.entitybridge.stewardship.ProtectedAreaMutationListener;
import dev.entitybridge.stewardship.ProtectedAreaProtocol;
import dev.entitybridge.stewardship.ProtectedAreaSelection;
import dev.entitybridge.stewardship.ProtectedAreaSelectionListener;
import dev.entitybridge.stewardship.ProtectedAreaStore;
import dev.entitybridge.stewardship.ProtectedAreaSynchronization;
import dev.entity.core.stewardship.ProtectedAreaHomePermit;
import dev.entitybridge.tracking.TargetTracker;
import dev.entitybridge.tracking.AuthoritativeEventListener;
import dev.entitybridge.tracking.AuthoritativeThreatTargetListener;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class EntityBridgePlugin extends JavaPlugin implements BridgeListener, Listener {
    private BridgeSettings settings;
    private BridgeServer bridge;
    private MissionRegistry missions;
    private TargetTracker targetTracker;
    private AuthoritativeThreatTargetListener threatTargetListener;
    private EntityCommand entityCommand;
    private DeliveryTransactionRegistry deliveries;
    private PaperDeliveryCommitter deliveryCommitter;
    private ProtectedAreaRegistry protectedAreas;
    private ProtectedAreaSelection areaSelection;
    private ProtectedAreaSynchronization areaSynchronization;
    private ProtectedAreaHomePermitRegistry areaHomePermits;
    private BlueprintBuildAuthority blueprintAuthority;
    private TrustedIdentityRegistry trustedIdentities;
    private EntityRelationshipSynchronization relationshipSynchronization;
    private BukkitTask bridgeRetryTask;
    private BukkitTask idleContextTask;
    private BukkitTask aiStatusTask;
    private dev.entitybridge.ai.AiStatusReceipt aiStatusReceipt;
    private boolean aiStatusWriteFailure;
    private int bridgeStartFailures;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = BridgeSettings.load(this);
        if (!settings.ownerConfigured()) {
            getLogger().severe(
                    "owner-name is not configured. /entity remains locked until config.yml is updated and Paper restarts."
            );
        }

        WorldStatePathResolver.WorldStatePaths worldState;
        try {
            worldState = WorldStatePathResolver.resolve(
                    getDataFolder().toPath(), authoritativeWorldIdentities());
        } catch (IllegalStateException failure) {
            getLogger().severe("EntityBridge startup refused: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        missions = new MissionRegistry(
                worldState.missions(),
                getLogger(),
                settings.defaultMode()
        );
        deliveries = new DeliveryTransactionRegistry(
                worldState.deliveries(), getLogger());
        deliveryCommitter = new PaperDeliveryCommitter(getServer(), deliveries);
        protectedAreas = new ProtectedAreaRegistry(new ProtectedAreaStore(
                worldState.protectedAreas()));
        getLogger().info("Entity world state selected for Overworld UUID "
                + worldState.overworldId());
        areaSelection = new ProtectedAreaSelection(protectedAreas);
        if (!protectedAreas.warning().isBlank()) {
            if (protectedAreas.failClosed()) {
                getLogger().severe(protectedAreas.warning());
            } else {
                getLogger().warning(protectedAreas.warning());
            }
        }
        bridge = new BridgeServer(getLogger(), settings, this, missions, deliveries);
        trustedIdentities = new TrustedIdentityRegistry(
                settings, getConfig().getBoolean("controllers-locked", false));
        relationshipSynchronization = new EntityRelationshipSynchronization(
                trustedIdentities, bridge::sendFrame);
        refreshWorldIdentities();
        areaSynchronization = new ProtectedAreaSynchronization(
                protectedAreas, bridge::sendFrame);
        areaHomePermits = new ProtectedAreaHomePermitRegistry(
                protectedAreas, areaSynchronization);
        blueprintAuthority = new BlueprintBuildAuthority(missions::current, protectedAreas, areaSynchronization,
                worldState.directory().resolve("blueprint-supports.json"));
        refreshRecipeCatalog();
        getServer().getPluginManager().registerEvents(this, this);
        targetTracker = new TargetTracker(this, bridge);
        targetTracker.start(settings.targetUpdateTicks());
        getServer().getPluginManager().registerEvents(
                new DeliveryReceiptListener(this, deliveries, bridge),
                this
        );
        getServer().getPluginManager().registerEvents(
                new AuthoritativeEventListener(bridge, settings), this);
        getServer().getPluginManager().registerEvents(
                new FriendlyDamageGuardListener(bridge, trustedIdentities, getLogger()), this);
        getServer().getPluginManager().registerEvents(
                new ProtectedAreaMutationListener(
                        protectedAreas, areaSynchronization, areaHomePermits, blueprintAuthority), this);
        getServer().getPluginManager().registerEvents(
                new ProtectedAreaSelectionListener(areaSelection), this);
        threatTargetListener = new AuthoritativeThreatTargetListener(this, bridge, settings);
        getServer().getPluginManager().registerEvents(threatTargetListener, this);
        threatTargetListener.start();
        entityCommand = new EntityCommand(
                settings,
                bridge,
                missions,
                targetTracker,
                protectedAreas,
                areaSelection,
                areaSynchronization,
                trustedIdentities,
                relationshipSynchronization,
                this);

        entityCommand.initializeQueue(worldState.directory().resolve("orders.json"), worldState.overworldId());
        entityCommand.initializeBlueprintAuthority(blueprintAuthority);
        entityCommand.initializeAi();
        aiStatusReceipt = new dev.entitybridge.ai.AiStatusReceipt(getDataFolder().toPath());
        aiStatusTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                aiStatusReceipt.publish(entityCommand.aiStatus());
                aiStatusWriteFailure = false;
            } catch (IOException failure) {
                if (!aiStatusWriteFailure) getLogger().warning("AI dashboard receipt could not be saved; AI behavior is unchanged.");
                aiStatusWriteFailure = true;
            }
        }, 0L, 60L);
        getServer().getPluginManager().registerEvents(
                new dev.entitybridge.tracking.CompanionIncidentListener(entityCommand), this);
        getServer().getPluginManager().registerEvents(new dev.entitybridge.ai.AiChatListener(new dev.entitybridge.ai.AiChatListener.Port() {
            @Override public long revision() { return entityCommand.aiChatRevision(); }
            @Override public void mainThread(Runnable callback) {
                if (isEnabled()) getServer().getScheduler().runTask(EntityBridgePlugin.this, () -> { if (isEnabled()) callback.run(); });
            }
            @Override public void accept(Player player, String request, long revision) {
                entityCommand.acceptAiChat(player, request, revision);
            }
        }), this);
        idleContextTask = getServer().getScheduler().runTaskTimer(
                this, entityCommand::publishIdleContext, 20L, 20L);
        PluginCommand command = getCommand("entity");
        if (command == null) {
            getLogger().severe("plugin.yml did not register /entity");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(entityCommand);
        command.setTabCompleter(entityCommand);
        entityCommand.restoreTargetTracking();
        startBridgeWithRecovery();
    }

    /** Refreshes after every normal load/reload, once all plugins and datapacks are registered. */
    @EventHandler
    public void onServerLoad(ServerLoadEvent ignored) {
        refreshWorldIdentities();
        refreshRecipeCatalog();
    }

    @EventHandler public void onAiRequesterQuit(PlayerQuitEvent event) {
        if (entityCommand != null) entityCommand.invalidateAiPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler public void onAiRequesterWorldChanged(PlayerChangedWorldEvent event) {
        if (entityCommand != null) entityCommand.invalidateAiPlayer(event.getPlayer().getUniqueId());
    }

    private void refreshWorldIdentities() {
        Map<String, String> identities = new LinkedHashMap<>();
        getServer().getWorlds().forEach(world -> identities.put(
                world.getKey().toString(), world.getUID().toString()));
        bridge.installWorldIdentities(identities);
    }

    private Map<String, UUID> authoritativeWorldIdentities() {
        Map<String, UUID> identities = new LinkedHashMap<>();
        getServer().getWorlds().forEach(world -> identities.put(
                world.getKey().toString(), world.getUID()));
        return identities;
    }

    private void refreshRecipeCatalog() {
        try {
            RecipeCatalogSnapshot recipeCatalog = PaperRecipeCatalogExporter.export(getServer());
            bridge.installRecipeCatalog(recipeCatalog);
            getLogger().info("Paper recipe catalog ready: " + recipeCatalog.exported()
                    + " exported, " + recipeCatalog.excluded() + " excluded from "
                    + recipeCatalog.scanned() + " registered recipes; exclusions="
                    + recipeCatalog.excludedByReason());
        } catch (RuntimeException failure) {
            getLogger().warning("Paper recipe catalog refresh failed; keeping the last valid snapshot: "
                    + failure.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (aiStatusTask != null) { aiStatusTask.cancel(); aiStatusTask = null; }
        if (entityCommand != null) entityCommand.closeAi();
        if (aiStatusReceipt != null) {
            try { aiStatusReceipt.stop(); }
            catch (IOException failure) { getLogger().warning("Final AI dashboard stopped receipt could not be saved."); }
        }
        if (idleContextTask != null) { idleContextTask.cancel(); idleContextTask = null; }
        if (targetTracker != null) {
            targetTracker.stop();
        }
        if (threatTargetListener != null) {
            threatTargetListener.stop();
        }
        if (bridgeRetryTask != null) {
            bridgeRetryTask.cancel();
            bridgeRetryTask = null;
        }
        if (bridge != null) {
            bridge.close();
        }
    }

    private void startBridgeWithRecovery() {
        if (tryStartBridge()) {
            return;
        }
        getLogger().severe("EntityBridge remains enabled in transport-degraded mode: /e commands and the "
                + "durable mission journal are still available. Retrying the configured bridge port every 5 seconds.");
        bridgeRetryTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (tryStartBridge() && bridgeRetryTask != null) {
                bridgeRetryTask.cancel();
                bridgeRetryTask = null;
            }
        }, 100L, 100L);
    }

    private boolean tryStartBridge() {
        try {
            bridge.start();
            bridgeStartFailures = 0;
            getLogger().info("EntityBridge 2 listening on " + settings.host() + ":" + settings.port()
                    + " with " + missions.snapshot().size() + " journaled missions; "
                    + bridge.buildIdentity().display());
            return true;
        } catch (IOException exception) {
            bridgeStartFailures++;
            if (bridgeStartFailures == 1 || bridgeStartFailures % 12 == 0) {
                getLogger().warning("Could not bind Entity bridge to " + settings.host() + ":" + settings.port()
                        + " (attempt " + bridgeStartFailures + "): " + exception.getMessage());
            }
            return false;
        }
    }

    @Override
    public void onConnectionChanged(boolean connected, String client) {
        if (areaHomePermits != null) areaHomePermits.clear();
        if (blueprintAuthority != null) blueprintAuthority.clear();
        if (areaSynchronization != null) {
            boolean sent = areaSynchronization.connectionChanged(connected);
            if (connected && !sent) {
                getLogger().warning(
                        "Could not send the protected-area policy; mutation authority remains disabled");
            }
        }
        if (relationshipSynchronization != null
                && connected
                && !relationshipSynchronization.connectionChanged(true)) {
            getLogger().warning("Could not send Entity relationship policy; friendly PvP remains fail-closed");
        } else if (relationshipSynchronization != null && !connected) {
            relationshipSynchronization.connectionChanged(false);
        }
        getLogger().info("Entity controller " + (connected ? "connected: " : "disconnected: ") + client);
        if (connected && isEnabled() && entityCommand != null) {
            getServer().getScheduler().runTask(this, entityCommand::publishIdleContext);
        }
    }

    @Override
    public void onProtectedAreaPolicyAcknowledged(long revision, String digest) {
        if (areaSynchronization == null) return;
        ProtectedAreaSynchronization.AcknowledgementResult result =
                areaSynchronization.acknowledge(revision, digest);
        if (result.accepted()) {
            getLogger().info("Entity acknowledged protected-area policy revision " + revision);
        } else {
            getLogger().warning("Rejected protected-area policy acknowledgement: "
                    + result.detail());
        }
    }

    @Override
    public void onProtectedAreaHomePermitRequested(
            ProtectedAreaHomePermit.Request request) {
        if (areaHomePermits == null || bridge == null) return;
        ProtectedAreaHomePermit.Result result = areaHomePermits.issue(
                request, System.currentTimeMillis());
        if (!bridge.sendFrame(ProtectedAreaProtocol.homePermitResult(result))) {
            getLogger().warning("Could not return exact Home permit result for "
                    + request.operationId());
        }
    }

    @Override
    public void onResult(BridgeResult result) {
        if (!isEnabled() || entityCommand == null) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> entityCommand.handleResult(result));
    }

    @Override
    public void onTelemetry(TelemetrySnapshot telemetry) {
        // BridgeServer caches the full tolerant snapshot for /e status and /e why.
    }

    @Override
    public void onMissionUpdated(Mission mission) {
        if (!isEnabled() || entityCommand == null) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> entityCommand.handleMissionUpdated(mission));
    }

    @Override
    public void onRecipeDiscovery(
            String playerName,
            List<String> exportedRecipeIds,
            Consumer<RecipeDiscoveryResult> completion
    ) {
        if (!isEnabled()) {
            completion.accept(RecipeDiscoveryResult.unavailable(
                    playerName, exportedRecipeIds.size(), "plugin_unavailable",
                    "EntityBridge is not currently enabled"));
            return;
        }
        // Bridge requests arrive on a socket worker. Recipe registry and
        // player recipe-book access are deliberately confined to Paper's main thread.
        getServer().getScheduler().runTask(this, () -> {
            ArrayList<NamespacedKey> discoverable = new ArrayList<>();
            ArrayList<String> undiscoverable = new ArrayList<>();
            for (String recipeId : exportedRecipeIds) {
                NamespacedKey key = NamespacedKey.fromString(recipeId);
                if (key == null || getServer().getRecipe(key) == null) {
                    undiscoverable.add(recipeId);
                } else {
                    discoverable.add(key);
                }
            }
            List<String> sample = undiscoverable.stream().limit(16).toList();
            Player player = getServer().getPlayerExact(playerName);
            if (player == null || !player.isOnline()) {
                completion.accept(new RecipeDiscoveryResult(
                        false, playerName, exportedRecipeIds.size(), discoverable.size(), 0,
                        undiscoverable.size(), sample, "player_offline",
                        "The requested Entity player is not online"));
                return;
            }
            try {
                int newlyDiscovered = player.discoverRecipes(discoverable);
                completion.accept(new RecipeDiscoveryResult(
                        true, playerName, exportedRecipeIds.size(), discoverable.size(),
                        newlyDiscovered, undiscoverable.size(), sample, "ok",
                        undiscoverable.isEmpty()
                                ? "Exported recipe keys are available in Entity's recipe book"
                                : "Some exported recipe keys are no longer registered"));
            } catch (RuntimeException failure) {
                getLogger().warning("Paper recipe discovery failed safely: " + failure.getMessage());
                completion.accept(new RecipeDiscoveryResult(
                        false, playerName, exportedRecipeIds.size(), discoverable.size(), 0,
                        undiscoverable.size(), sample, "discovery_failed",
                        "Paper could not update Entity's recipe book"));
            }
        });
    }

    @Override
    public void onDeliveryCommit(
            DeliveryCommitRequest request,
            Consumer<DeliveryCommitResult> completion) {
        if (!isEnabled() || deliveryCommitter == null) {
            completion.accept(DeliveryCommitResult.rejected(
                    request, true, "plugin_unavailable",
                    "EntityBridge is not currently enabled"));
            return;
        }
        // Bridge frames arrive on a socket worker. Bukkit inventory access is
        // deliberately scheduled onto Paper's primary thread.
        getServer().getScheduler().runTask(this, () -> {
            DeliveryCommitResult result;
            try {
                result = deliveryCommitter.commit(request);
            } catch (RuntimeException failure) {
                getLogger().warning("Paper delivery commit failed safely: " + failure.getMessage());
                result = DeliveryCommitResult.rejected(
                        request, true, "internal_error",
                        "Paper could not execute the inventory handoff");
            }
            completion.accept(result);
        });
    }
}
