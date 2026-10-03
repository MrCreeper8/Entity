package dev.entitybridge.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.entitybridge.bridge.BridgeCommand;
import dev.entitybridge.bridge.BridgeResult;
import dev.entitybridge.bridge.BridgeServer;
import dev.entitybridge.bridge.TelemetrySnapshot;
import dev.entitybridge.ai.AiProposal;
import dev.entitybridge.ai.EntityAi;
import dev.entitybridge.ai.AiBackendFactory;
import dev.entitybridge.ai.CompanionFeedback;
import dev.entitybridge.ai.CompanionResultAnswer;
import dev.entitybridge.ai.CompanionPersonality;
import dev.entitybridge.ai.CompanionIncidents;
import dev.entitybridge.blueprint.BlueprintPreview;
import dev.entitybridge.blueprint.BlueprintBuildAuthority;
import dev.entitybridge.config.BridgeSettings;
import dev.entitybridge.mission.Mission;
import dev.entitybridge.mission.MissionRegistry;
import dev.entitybridge.mission.MissionStatus;
import dev.entitybridge.mission.OrderedJobQueue;
import dev.entitybridge.safety.EntityRelationshipSynchronization;
import dev.entitybridge.safety.FriendlyDamageGuardListener;
import dev.entitybridge.safety.TrustedIdentityRegistry;
import dev.entitybridge.stewardship.ProtectedAreaRegistry;
import dev.entitybridge.stewardship.ProtectedAreaSelection;
import dev.entitybridge.stewardship.ProtectedAreaSynchronization;
import dev.entitybridge.tracking.TargetTracker;
import dev.entity.core.stewardship.ProtectedAreaPolicy;
import dev.entity.core.farm.ManagedFarmPolicy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class EntityCommand implements CommandExecutor, TabCompleter {
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int MAX_AMOUNT = 4096;
    private static final List<String> SUBCOMMANDS = List.of(
            "help", "ask", "ai", "personality", "follow", "goto", "go", "mine", "get", "give", "bring", "gear", "farm", "build", "attack", "protect",
            "combat",
            "pause", "resume", "retry", "cancel", "queue", "why", "missions",
            "plan", "inventory", "access", "area", "home", "sleep", "stock", "tidy", "visuals", "perception", "idle", "capabilities", "status", "come", "stop",
            "collect", "kill"
    );
    private static final List<String> GEAR_TIERS = List.of("wood", "stone", "iron", "diamond");
    private static final List<String> MOB_SUGGESTIONS = List.of(
            "hostile", "zombie", "skeleton", "creeper", "spider", "witch",
            "pillager", "drowned", "husk", "slime", "phantom", "enderman",
            "warden", "piglin", "blaze", "ghast"
    );
    private static final Map<String, String> MOB_ALIASES = Map.ofEntries(
            Map.entry("zombies", "zombie"),
            Map.entry("skeletons", "skeleton"),
            Map.entry("creepers", "creeper"),
            Map.entry("spiders", "spider"),
            Map.entry("witches", "witch"),
            Map.entry("pillagers", "pillager"),
            Map.entry("endermen", "enderman"),
            Map.entry("pigmen", "zombified_piglin"),
            Map.entry("zombie_pigman", "zombified_piglin")
    );
    private static final List<String> WOOD_SPECIES = List.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped"
    );
    private static final List<String> ORE_TYPES = List.of(
            "coal", "iron", "copper", "gold", "redstone", "lapis",
            "diamond", "emerald", "quartz"
    );

    private final BridgeSettings settings;
    private final BridgeServer bridge;
    private final MissionRegistry missions;
    private final TargetTracker targetTracker;
    private final ProtectedAreaRegistry protectedAreas;
    private final ProtectedAreaSelection areaSelection;
    private final ProtectedAreaSynchronization areaSynchronization;
    private final HomeFurnitureSelection homeFurnitureSelection = new HomeFurnitureSelection();
    private final HomeFurnitureSelection homeStorageSelection = new HomeFurnitureSelection("/e home storage add");
    private final TidySpotSelection tidySpotSelection = new TidySpotSelection();
    private final JavaPlugin plugin;
    private final TrustedIdentityRegistry trustedIdentities;
    private final EntityRelationshipSynchronization relationshipSynchronization;
    private final OwnerNoticePolicy ownerNotices = new OwnerNoticePolicy();
    private final List<String> blockSuggestions;
    private final List<String> itemSuggestions;
    private final Map<String, Material> materialsBySimpleName;
    private final Map<String, List<String>> friendlyBlockGroups;
    /** Exact transient command requester; persistent mission notices retain their own routing. */
    private final LinkedHashMap<String, String> transientRequesters = new LinkedHashMap<>();
    private OrderedJobQueue orders;
    private BridgeCommand finiteTransient;
    private UUID idleWorldId;
    private BlueprintBuildAuthority blueprintAuthority;
    private String latestBlueprintPreviewRequest;
    private String latestBlueprintResumeRequest;
    private JsonObject pendingBlueprintResume;
    private EntityAi localAi;
    private AiBackendFactory.Provider aiBackend;
    private String aiLabel = "Local AI";
    private String localAiDescription = "Choose an AI provider in the app, then /e ai on; ordinary commands still work.";
    private final Map<UUID, Player> aiRequestPlayers = new LinkedHashMap<>();
    private final CompanionFeedback companionFeedback = new CompanionFeedback();
    private CompanionPersonality companionPersonality = CompanionPersonality.select("chaotic", "");
    private final CompanionIncidents companionIncidents = new CompanionIncidents();
    private JsonObject pendingSocialReaction;
    private UUID pendingSocialWorld;
    private String pendingSocialSession;
    private long pendingSocialExpiry;
    private CompanionFeedback.Owner aiDispatchOwner;
    private final List<String> aiDispatchNotices = new ArrayList<>();
    private boolean aiDispatchAccepted;
    private BridgeCommand aiDispatchCommand;
    private boolean aiDispatchReadQuery;
    private boolean aiDispatchError;
    private boolean aiDispatchQueueAppending;

    public EntityCommand(
            BridgeSettings settings,
            BridgeServer bridge,
            MissionRegistry missions,
            TargetTracker targetTracker,
            ProtectedAreaRegistry protectedAreas,
            ProtectedAreaSelection areaSelection,
            ProtectedAreaSynchronization areaSynchronization,
            TrustedIdentityRegistry trustedIdentities,
            EntityRelationshipSynchronization relationshipSynchronization,
            JavaPlugin plugin
    ) {
        this.settings = settings;
        this.bridge = bridge;
        this.missions = missions;
        this.targetTracker = targetTracker;
        this.protectedAreas = Objects.requireNonNull(protectedAreas, "protectedAreas");
        this.areaSelection = Objects.requireNonNull(areaSelection, "areaSelection");
        this.areaSynchronization = Objects.requireNonNull(
                areaSynchronization, "areaSynchronization");
        this.trustedIdentities = Objects.requireNonNull(
                trustedIdentities, "trustedIdentities");
        this.relationshipSynchronization = Objects.requireNonNull(
                relationshipSynchronization, "relationshipSynchronization");
        this.plugin = plugin;

        Map<String, Material> materials = new LinkedHashMap<>();
        for (Material material : Material.values()) {
            materials.put(material.getKey().getKey(), material);
        }
        materialsBySimpleName = Map.copyOf(materials);
        friendlyBlockGroups = buildFriendlyBlockGroups();

        LinkedHashSet<String> blocks = new LinkedHashSet<>(List.of(
                "wood", "logs", "tree", "oak", "spruce", "birch", "dark_oak",
                "iron", "coal", "copper", "gold", "diamond", "redstone",
                "emerald", "lapis", "ores", "leaves", "cobble", "debris"
        ));
        Arrays.stream(Material.values())
                .filter(Material::isBlock)
                .filter(material -> !material.isAir())
                .map(material -> material.getKey().getKey())
                .sorted()
                .forEach(blocks::add);
        blockSuggestions = List.copyOf(blocks);

        LinkedHashSet<String> items = new LinkedHashSet<>(List.of(
                "cobblestone", "oak_log", "oak_planks", "stick", "crafting_table",
                "furnace", "coal", "torch", "bread", "iron_ingot", "diamond",
                "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe",
                "iron_sword", "shield", "bucket", "water_bucket"
        ));
        Arrays.stream(Material.values())
                .filter(Material::isItem)
                .filter(material -> !material.isAir())
                .map(material -> material.getKey().getKey())
                .sorted()
                .forEach(items::add);
        itemSuggestions = List.copyOf(items);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            if (args.length > 0 && Set.of("ask", "ai").contains(args[0].toLowerCase(Locale.ROOT))) {
                error(sender, "Local AI requests must come from an authorized in-game player.");
                return true;
            }
            if (args.length > 0 && CommandInput.canonicalAction(args[0]).equals("access")) {
                if (localAi != null) localAi.invalidate();
                access(sender, args, true);
                return true;
            }
            Player owner = Bukkit.getPlayerExact(settings.ownerName());
            if (owner == null) {
                error(sender, "The configured owner must be online to run Entity gameplay commands from the console.");
                return true;
            }
            info(sender, "Executing Entity command as " + owner.getName() + ".");
            return onAuthorizedCommand(owner, args);
        }
        Player player = (Player) sender;
        if (!authorize(player, true)) {
            return true;
        }
        return onAuthorizedCommand(player, args);
    }

    private boolean onAuthorizedCommand(Player player, String[] args) {
        if(localAi!=null && aiDispatchOwner==null) localAi.cancelAmbient();
        if (args.length == 0) {
            help(player);
            return true;
        }

        args = CommandPresentation.withGroupDefault(args);
        String action = CommandInput.canonicalAction(args[0]);
        if (aiDispatchOwner == null && (AiProposal.invalidatesPending(args) || action.equals("ask")
                || action.equals("ai") && args.length == 2 && !args[1].equalsIgnoreCase("status"))) chatAdmission.invalidate();
        if (localAi != null && aiDispatchOwner == null && AiProposal.invalidatesPending(args)) localAi.invalidate();
        if (aiDispatchOwner == null && AiProposal.invalidatesPending(args)) pendingSocialReaction = null;
        try { switch (action) {
            case "ask" -> askAi(player, args);
            case "ai" -> configureAi(player, args);
            case "personality" -> configurePersonality(player, args);
            case "help" -> help(player, args.length == 1 ? "" : String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            case "follow" -> playerTargetMission(player, "follow", args, true);
            case "come" -> noArgumentMission(player, "come", args, player.getName());
            case "goto" -> goTo(player, args);
            case "mine" -> resourceMission(player, "mine", true, args);
            case "get" -> acquisitionMission(player, args);
            case "give", "bring" -> deliveryMission(player, action, args);
            case "gear" -> gearMission(player, args);
            case "attack" -> attackTarget(player, args);
            case "combat" -> combat(player, args);
            case "protect" -> protection(player, args);
            case "pause", "resume", "retry", "cancel" -> lifecycle(player, action, args);
            case "stop" -> stop(player, args);
            case "queue" -> queue(player, args);
            case "why" -> why(player, args);
            case "missions" -> listMissions(player, args);
            case "plan" -> plan(player, args);
            case "inventory" -> inventory(player, args);
            case "access" -> access(player, args, false);
            case "area" -> area(player, args);
            case "home" -> home(player, args);
            case "sleep", "bed" -> homeSleepAlias(player, action, args);
            case "stock" -> stock(player, args);
            case "tidy" -> tidy(player, args);
            case "visuals" -> visuals(player, args);
            case "perception" -> perception(player, args);
            case "idle" -> idle(player, args);
            case "farm" -> farm(player, args);
            case "build" -> build(player, args);
            case "capabilities" -> capabilities(player, args);
            case "mode" -> mode(player, args);
            case "status" -> {
                if (args.length == 1) {
                    status(player);
                } else if (args.length == 2 && args[1].equalsIgnoreCase("debug")) {
                    status(player, true);
                } else {
                    error(player, "Usage: /e status [debug]");
                }
            }
            default -> { error(player, "Unknown command '" + args[0] + "'. Use /e help or Tab."); help(player); }
        } } finally { publishIdleContext(); }
        return true;
    }

    public void initializeQueue(Path file, UUID worldId) {
        idleWorldId = Objects.requireNonNull(worldId, "worldId");
        orders = new OrderedJobQueue(file, worldId, missions, new OrderedJobQueue.Port() {
            @Override public JsonObject resolveArgs(OrderedJobQueue.Step step) throws IOException {
                JsonObject args = step.args();
                if (step.action().equals("goto")) {
                    Player requester = Bukkit.getPlayerExact(step.requestedBy());
                    if (requester == null) throw new IOException("Queue requester is offline; /e queue retry when online");
                    args.addProperty("dimension", requester.getWorld().getKey().toString());
                }
                return args;
            }
            @Override public boolean dispatch(BridgeCommand command, Mission superseded, OrderedJobQueue.Step step) {
                boolean accepted = superseded == null ? bridge.submit(command).accepted()
                        : bridge.submitSuperseding(BridgeCommand.lifecycle(superseded, "cancel", step.requestedBy()), command).accepted();
                if (!accepted) return false;
                rememberRequester(command);
                Mission retained = "mission.retry".equals(command.operation())
                        ? missions.resolve(command.missionId()).orElse(null) : null;
                BridgeCommand tracking = retained == null ? command : BridgeCommand.start(retained);
                String tracked = tracking.args().has("player") ? tracking.args().get("player").getAsString()
                        : tracking.action().equals("come") ? tracking.requestedBy() : null;
                if (tracked == null) targetTracker.clear(); else targetTracker.track(tracking, tracked);
                finiteTransient = step.mission() ? null : command;
                return true;
            }
            @Override public boolean stop(BridgeCommand command) {
                if (!bridge.submit(command).accepted()) return false;
                rememberRequester(command);
                targetTracker.clear();
                finiteTransient = null;
                return true;
            }
        });
    }

    public void initializeAi() {
        loadCompanionPersonality();
        if (plugin.getConfig().getBoolean("local-ai.enabled", false)) openAi();
    }

    private final dev.entitybridge.ai.AiChatAdmission chatAdmission = new dev.entitybridge.ai.AiChatAdmission();

    /** Safe to snapshot from the asynchronous chat event; authority is checked below on tick. */
    public long aiChatRevision() { return chatAdmission.revision(); }

    public void acceptAiChat(Player player, String request, long revision) {
        if (!player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player || !authorize(player, true)) return;
        if (request.isEmpty()) {
            info(player, localAi == null ? "I'm here. " + aiLabel + " is off; enable it once with /e ai on."
                    : "I'm here. Try: e come here | ent what do you have?");
            return;
        }
        if (!chatAdmission.accepts(request, revision)) {
            info(player, "Chat request superseded by a newer command; nothing started."); return;
        }
        // The same request handler as /e ask, without introducing a parallel mission path.
        try { askAi(player, new String[]{"ask", request}); }
        finally { publishIdleContext(); }
    }

    private void openAi() {
        closeAi();
        aiLabel = "AI";
        try {
            AiBackendFactory.Provider backend = AiBackendFactory.load(plugin.getDataFolder().toPath());
            aiLabel = backend.label();
            localAiDescription = backend.description();
            if (!backend.configured()) { backend.close(); return; }
            aiBackend = backend;
            localAi = new EntityAi(backend, new EntityAi.Port() {
                @Override public void mainThread(Runnable callback) {
                    if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, callback);
                }
                @Override public boolean stillAuthorized(UUID requester, UUID world, String session) {
                    Player current = Bukkit.getPlayer(requester);
                    return current != null && current == aiRequestPlayers.get(requester) && current.isOnline()
                            && current.getWorld().getUID().equals(world) && authorize(current, false)
                            && session.equals(bridge.connectedSessionId()) && aiClientReady();
                }
                @Override public void reply(UUID requester, String text, boolean failure) {
                    Player current = Bukkit.getPlayer(requester);
                    if (current != null && current == aiRequestPlayers.get(requester) && authorize(current, false)) {
                        if (failure) error(current, text); else info(current, text);
                    }
                }
                @Override public void dispatch(UUID requester, String[] command) {
                    Player current = Bukkit.getPlayer(requester);
                    if (current != null && current == aiRequestPlayers.get(requester) && authorize(current, true))
                        dispatchCompanion(current,command);
                }
                @Override public JsonObject companionFacts(UUID requester) { return snapshotCompanionFacts(requester); }
                @Override public java.util.Optional<String> resolveItem(String item) {
                    return resolveAiTarget("get",item);
                }
                @Override public java.util.Optional<String> resolveTarget(String action,String target) {
                    return resolveAiTarget(action,target);
                }
                @Override public void stopThenCome(UUID requester) {
                    Player current=Bukkit.getPlayer(requester);
                    if(current!=null && current==aiRequestPlayers.get(requester) && authorize(current,true)
                            && dispatchCompanion(current,new String[]{"stop"}))
                        dispatchCompanion(current,new String[]{"come"});
                }
                @Override public void timing(long queueMillis,long inferenceMillis,String lane) {
                    plugin.getLogger().info("[companion-turn-timing] lane="+lane+" queueMs="+queueMillis+" inferenceMs="+inferenceMillis);
                }
                @Override public void diagnostic(String stage,String type) {
                    plugin.getLogger().warning("AI " + stage + " rejected response: " + type);
                }
            });
        } catch (IOException | RuntimeException failure) {
            localAiDescription = failure instanceof AiBackendFactory.ConfigurationException
                    ? failure.getMessage() : "AI setup is unavailable; normal /e commands still work.";
            plugin.getLogger().warning("AI configuration could not be loaded (" + failure.getClass().getSimpleName() + ").");
        }
    }

    public void closeAi() {
        chatAdmission.invalidate();
        if (localAi != null) { localAi.close(); localAi = null; }
        aiBackend = null;
        aiRequestPlayers.clear();
        companionFeedback.clear();
        pendingSocialReaction = null;
        companionIncidents.clear();
    }

    public void invalidateAiPlayer(UUID requester) {
        chatAdmission.invalidate();
        if (localAi != null && aiRequestPlayers.containsKey(requester)) { localAi.invalidate(); localAi.forget(requester); }
        aiRequestPlayers.remove(requester);
        companionFeedback.forget(requester);
    }

    private void configureAi(Player player, String[] args) {
        String operation = args.length == 1 ? "status" : args[1].toLowerCase(Locale.ROOT);
        if (args.length > 2 || !Set.of("on", "off", "status").contains(operation)) {
            error(player, "Usage: /e ai on|off|status. Talk in chat: e <request>."); return;
        }
        if (!operation.equals("status")) {
            if (!settings.isPrimaryOwner(player.getName())) { error(player, "Only the owner can enable or disable AI."); return; }
            if (operation.equals("off")) closeAi(); else openAi();
            plugin.getConfig().set("local-ai.enabled", localAi != null);
            plugin.saveConfig();
        }
        info(player, localAi == null ? aiLabel + " OFF. " + (operation.equals("off") ? "Normal commands and existing work are unchanged." : localAiDescription)
                : aiLabel + " ON — " + aiBackend.description() + ". Talk in chat: e come here (or ent / Entity). No automatic fallback.");
    }

    private void configurePersonality(Player player, String[] args) {
        String operation = args.length == 1 ? "show" : args[1].toLowerCase(Locale.ROOT);
        if (operation.equals("show") && args.length <= 2) {
            info(player, "Personality: " + companionPersonality.preset() + " — " + (companionPersonality.preset().equals("owner")
                    ? "Your verbatim conversation prompt." : companionPersonality.description()));
            info(player, "/e personality set owner|chaotic|friendly|neutral | custom <description> | reset"); return;
        }
        if (!settings.isPrimaryOwner(player.getName())) { error(player, "Only the owner can change Entity's personality."); return; }
        CompanionPersonality selected;
        try {
            if (operation.equals("set") && args.length == 3) selected = CompanionPersonality.select(args[2], "");
            else if (operation.equals("custom") && args.length >= 3)
                selected = CompanionPersonality.select("custom", String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            else if (operation.equals("reset") && args.length == 2) selected = CompanionPersonality.select("owner", "");
            else { error(player, "Usage: /e personality set owner|chaotic|friendly|neutral | custom <description> | show | reset"); return; }
        } catch (IllegalArgumentException invalid) { error(player, invalid.getMessage()); return; }
        plugin.getConfig().set("local-ai.personality.preset", selected.preset());
        plugin.getConfig().set("local-ai.personality.custom", selected.preset().equals("custom") ? selected.description() : "");
        plugin.saveConfig(); companionPersonality = selected;
        chatAdmission.invalidate();
        if (localAi != null) localAi.invalidate(); pendingSocialReaction = null;
        success(player, "Personality set to " + selected.preset() + ". Current work is unchanged.");
    }

    private void loadCompanionPersonality() {
        try { companionPersonality = CompanionPersonality.select(
                plugin.getConfig().getString("local-ai.personality.preset", "owner"),
                plugin.getConfig().getString("local-ai.personality.custom", "")); }
        catch (IllegalArgumentException badProfile) {
            companionPersonality = CompanionPersonality.select("chaotic", "");
            plugin.getLogger().warning("Invalid personality configuration; using chaotic without changing saved config.");
        }
    }

    /** Main-thread MONITOR facts only. No combat or mission operation is dispatched here. */
    public void observeCompanionHit(UUID world, UUID actor, String actorName, double damage) {
        String session = bridge.connectedSessionId(); if (localAi == null || session == null) return;
        long now = System.currentTimeMillis();
        JsonObject incident = companionIncidents.hit(world, session, actor, actorName,
                settings.isPrimaryOwner(actorName), damage, now);
        if (incident != null && incident.get("hit_count_recent").getAsInt() >= 3 && companionIncidents.maySpeak(now))
            scheduleSocialReaction(world, session, incident, now + 15_000);
    }

    public void observeCompanionDeath(UUID world, String cause, String actorName, String actorType) {
        String session = bridge.connectedSessionId(); if (localAi == null || session == null) return;
        long now = System.currentTimeMillis();
        JsonObject incident = companionIncidents.death(world, session, cause, actorName, actorType,
                actorType.equals("minecraft:player") && settings.isPrimaryOwner(actorName), now);
        localAi.cancelAmbient(); // Don't finish an old punching joke after the life ended.
        scheduleSocialReaction(world, session, incident, now + 30_000);
    }

    private void scheduleSocialReaction(UUID world, String session, JsonObject fact, long expires) {
        fact.addProperty("expires_at_millis",expires);
        pendingSocialWorld = world; pendingSocialSession = session;
        pendingSocialReaction = fact; pendingSocialExpiry = expires;
    }

    private void publishCompanionReaction() {
        if (pendingSocialReaction == null) return;
        long now = System.currentTimeMillis();
        if (localAi == null || now > pendingSocialExpiry || !Objects.equals(pendingSocialSession, bridge.connectedSessionId())) {
            pendingSocialReaction = null; return;
        }
        if (!settings.ownerConfigured()) return;
        Player body = Bukkit.getPlayerExact("Entity"), owner = Bukkit.getPlayerExact(settings.ownerName());
        if (!aiClientReady() || body == null || body.isDead() || owner == null || !owner.isOnline()
                || !body.getWorld().getUID().equals(pendingSocialWorld) || !owner.getWorld().equals(body.getWorld())
                || owner.getLocation().distanceSquared(body.getLocation()) > 32 * 32 || !authorize(owner, false)) return;
        aiRequestPlayers.put(owner.getUniqueId(), owner);
        if (localAi.react(owner.getUniqueId(), pendingSocialWorld, pendingSocialSession, pendingSocialReaction)) {
            companionIncidents.spoken(now); pendingSocialReaction = null;
        }
    }

    private void askAi(Player player, String[] args) {
        if (args.length < 2) { info(player, "Talk in normal chat: e come here | ent what do you have? /e ask still works. /e help ai lists the supported scope."); return; }
        String request = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        // Stop must work immediately, including while the model is unavailable or loading.
        if (dev.entitybridge.ai.AiChatAdmission.isStop(request)) {
            chatAdmission.invalidate();
            if (localAi != null) localAi.invalidate();
            stop(player, new String[]{"stop"}); return;
        }
        if (localAi == null) { error(player, aiLabel + " is off. Use /e ai on; ordinary /e commands always work."); return; }
        String session = bridge.connectedSessionId();
        if (session == null || !aiClientReady()) {
            error(player, "Entity must be connected to this world before interpreting gameplay requests."); return;
        }
        aiRequestPlayers.put(player.getUniqueId(), player);
        while (aiRequestPlayers.size()>32) aiRequestPlayers.remove(aiRequestPlayers.keySet().iterator().next());
        try { localAi.converse(player.getUniqueId(), player.getWorld().getUID(), session, request); }
        catch (IllegalArgumentException failure) { error(player, failure.getMessage()); }
    }

    private boolean aiClientReady() {
        TelemetrySnapshot observation = bridge.latestTelemetry();
        return bridge.isConnected() && observation != null && observation.position() != null
                && System.currentTimeMillis() - observation.receivedAt() <= 5000;
    }

    /** Snapshot on Paper's main thread: self knowledge only, never hidden world resources. */
    private JsonObject snapshotCompanionFacts(UUID requester) {
        JsonObject facts=new JsonObject();
        facts.addProperty("self","Entity, the Minecraft companion; all inventory below is mine, not the player's");
        facts.addProperty("model",aiBackend == null ? localAiDescription : aiBackend.description());
        facts.add("personality", companionPersonality.facts());
        Player player=Bukkit.getPlayer(requester);
        if(player!=null) facts.addProperty("requesting_player",player.getName());
        facts.addProperty("connected",aiClientReady());
        TelemetrySnapshot status=bridge.latestTelemetry();
        if(aiClientReady() && status!=null) {
            facts.addProperty("current_activity",status.task());
            facts.addProperty("health_points_out_of_20",status.health());facts.addProperty("hunger_points_out_of_20",status.food());
            facts.addProperty("activity_detail",companionText(status.message(),500));
            if(status.position()!=null) {
                JsonObject position=new JsonObject();
                position.addProperty("x",Math.floor(status.position().x()));
                position.addProperty("y",Math.floor(status.position().y()));
                position.addProperty("z",Math.floor(status.position().z()));
                position.addProperty("dimension",status.position().dimension());facts.add("my_position",position);
            }
            // Entity is the existing configured game profile used by bridge receipt observers.
            Player body=Bukkit.getPlayerExact("Entity");
            if(body!=null && body.isOnline() && status.position()!=null
                    && body.getWorld().getKey().toString().equals(status.position().dimension())
                    && Math.abs(body.getLocation().getX()-status.position().x())<16
                    && Math.abs(body.getLocation().getZ()-status.position().z())<16) {
                JsonObject inventory=new JsonObject();
                for(var item:body.getInventory().getContents()) {
                    if(item==null || item.getType().isAir()) continue;
                    String key=item.getType().getKey().getKey();
                    inventory.addProperty(key,(inventory.has(key)?inventory.get(key).getAsInt():0)+item.getAmount());
                }
                facts.add("my_carried_total_including_equipped",inventory);
                JsonObject equipment=new JsonObject();
                String[] slots={"boots","leggings","chestplate","helmet"};
                var armor=body.getInventory().getArmorContents();
                for(int i=0;i<armor.length;i++) if(armor[i]!=null && !armor[i].getType().isAir())
                    equipment.addProperty(slots[i],armor[i].getType().getKey().getKey());
                equipment.addProperty("main_hand",body.getInventory().getItemInMainHand().getType().getKey().getKey());
                equipment.addProperty("off_hand",body.getInventory().getItemInOffHand().getType().getKey().getKey());
                facts.add("equipped_subset_NOT_extra_items",equipment);
                facts.addProperty("time_of_day",body.getWorld().getTime());
                facts.add("recent_events", companionIncidents.snapshot(body.getWorld().getUID(),
                        bridge.connectedSessionId(), System.currentTimeMillis()));
            }
        }
        Mission current=missions.current();
        if(current!=null) {
            JsonObject job=companionMission(current);
            if(current.args().has("count")) job.add("requested_count",current.args().get("count").deepCopy());
            facts.add("current_job",job);
        }
        else facts.addProperty("current_job","none");
        if(aiClientReady() && status!=null && status.raw().has("companionFacts")
                && status.raw().get("companionFacts").isJsonObject()) {
            JsonObject observed=status.raw().getAsJsonObject("companionFacts");
            long now=System.currentTimeMillis();
            long at=observed.has("observed_at_millis")?observed.get("observed_at_millis").getAsLong():0;
            if(at<=now+1000 && now-at<=5000) {
                for(String key:List.of("food_summary","home"))
                    if(observed.has(key)&&observed.get(key).isJsonObject()) facts.add(key,observed.get(key).deepCopy());
                if(current!=null && facts.has("my_carried_total_including_equipped") && current.args().has("item")) {
                    String item=current.args().get("item").getAsString().replaceFirst("^minecraft:","");
                    JsonObject job=facts.getAsJsonObject("current_job");
                    if(item.equals("food") && facts.has("food_summary")) {
                        JsonObject food=facts.getAsJsonObject("food_summary");
                        job.addProperty("carried_count",food.get("cooked_count").getAsInt()+food.get("other_edible_count").getAsInt());
                        if(observed.has("mission_id") && current.id().equals(observed.get("mission_id").getAsString()))
                            job.add("deliverable_count",food.get("deliverable_count").deepCopy());
                    } else {
                        JsonObject carried=facts.getAsJsonObject("my_carried_total_including_equipped");
                        job.addProperty("carried_count",carried.has(item)?carried.get(item).getAsInt():0);
                    }
                    if(observed.has("mission_id") && current.id().equals(observed.get("mission_id").getAsString()) && observed.has("delivered_count"))
                        job.add("delivered_count",observed.get("delivered_count").deepCopy());
                }
            }
        }
        if(orders!=null) facts.addProperty("queue",orders.describe());
        JsonArray recent=new JsonArray();
        List<Mission> own=missions.snapshot().stream().filter(m -> player!=null && m.requestedBy().equals(player.getName())).toList();
        for(int i=Math.max(0,own.size()-2);i<own.size();i++) recent.add(companionMission(own.get(i)));
        facts.add("recent_jobs_for_this_player",recent);
        own.stream().filter(m->m.status()==MissionStatus.CANCELLED || m.status()==MissionStatus.FAILED)
                .max(java.util.Comparator.comparingLong(Mission::updatedAt)).ifPresent(changed -> {
                    JsonObject change=new JsonObject(); change.addProperty("observed_at_millis",changed.updatedAt());
                    change.addProperty("kind",changed.status().name().toLowerCase(Locale.ROOT));
                    change.addProperty("detail","superseded".equals(changed.step())
                            ? "Mission #"+changed.sequence()+" was replaced by a newer request, not by Idle."
                            : "stop".equals(changed.step())?"Mission #"+changed.sequence()+" was stopped by your command."
                            : companionText(changed.reason(),240));
                    facts.add("latest_change",change);
                });
        facts.addProperty("home_and_chest_contents","Not observed here. Use home_status or stock_status if asked; never guess chest contents.");
        return facts;
    }

    private static JsonObject companionMission(Mission mission) {
        JsonObject value=new JsonObject();value.addProperty("number",mission.sequence());
        value.addProperty("action",mission.action());
        value.addProperty("objective",objectiveDescription(mission.action(),mission.args()));
        value.addProperty("status",mission.status().name());value.addProperty("phase",mission.step());
        value.addProperty("detail",companionText(mission.reason(),750));return value;
    }

    private static String companionText(String text,int limit) {
        return text==null?"":text.substring(0,Math.min(limit,text.length()));
    }

    private boolean dispatchCompanion(Player player,String[] command) {
        CompanionFeedback.Owner origin=new CompanionFeedback.Owner(player.getUniqueId(),player.getWorld().getUID(),bridge.connectedSessionId());
        Mission previousJob=missions.current();
        String previousMissionId=previousJob==null?null:previousJob.id();
        aiDispatchOwner=origin;aiDispatchNotices.clear();aiDispatchAccepted=false;aiDispatchError=false;
        aiDispatchCommand=null;
        aiDispatchReadQuery=CompanionResultAnswer.nativeReadCommand(command);
        try {
            plugin.getLogger().info("[companion-dispatch] player="+player.getName()+" command="+String.join(" ",command));
            onAuthorizedCommand(player,command);
        } catch(RuntimeException failure) {
            aiDispatchError=true;aiDispatchNotices.add("Command handling failed; current job state must be checked, not assumed completed.");
            plugin.getLogger().warning("Companion command handler failed: "+failure.getClass().getSimpleName());
        } finally {
            boolean readQuery=aiDispatchReadQuery;
            JsonObject fact=new JsonObject();fact.addProperty("kind",readQuery?"read_query_result":"dispatch_result");
            fact.addProperty("requested_skill",String.join(" ",command));
            fact.addProperty("status",aiDispatchError?"rejected":aiDispatchAccepted?"accepted_not_completed":"query_or_control_result");
            fact.addProperty("detail",companionText(String.join("\n",aiDispatchNotices),2000));
            boolean accepted=aiDispatchAccepted&&!aiDispatchError;
            BridgeCommand acceptedCommand=aiDispatchCommand;
            aiDispatchCommand=null;
            aiDispatchReadQuery=false;
            aiDispatchOwner=null;aiDispatchNotices.clear();
            // An accepted read submission is neither a started mission nor an
            // observed answer. Await its exact correlated native result instead.
            if(localAi!=null && !(accepted && readQuery)) {
                if(accepted) {
                    Mission acceptedJob=acceptedCommand==null || acceptedCommand.missionId()==null ? null
                            : missions.resolve(acceptedCommand.missionId()).orElse(null);
                    String acknowledgement=companionAcknowledgement(command,acceptedCommand,acceptedJob,previousMissionId);
                    localAi.acknowledge(origin.player(),origin.world(),origin.session(),fact,acknowledgement);
                }
                else localAi.observe(origin.player(),origin.world(),origin.session(),fact,true);
            }
        }
        return !aiDispatchError;
    }

    /** Admission is not physical completion, and a retained job is not a new submission. */
    static String companionAcknowledgement(String[] requested, BridgeCommand accepted, Mission mission,
                                            String previousMissionId) {
        String action=requested.length==0?"":CommandInput.canonicalAction(requested[0]);
        boolean queueControl=action.equals("queue") && requested.length==2
                && Set.of("resume","retry").contains(requested[1].toLowerCase(Locale.ROOT));
        if(queueControl) action=requested[1].toLowerCase(Locale.ROOT);
        boolean correlated=accepted!=null && mission!=null && mission.id().equals(accepted.missionId())
                && Objects.equals(accepted.sequence(),Long.valueOf(mission.sequence()));
        String control=switch(action) {
            case "pause" -> "Pausing";
            case "resume" -> "Resuming";
            case "retry" -> "Retrying";
            case "cancel" -> "Cancelling";
            default -> null;
        };
        if(control!=null) {
            // Queue Resume legitimately dispatches mission.retry for its retained step.
            boolean requestedControl=accepted!=null && (Objects.equals(accepted.operation(),"mission."+action)
                    && action.equals(accepted.action()) || action.equals("resume")
                    && Objects.equals(accepted.operation(),"mission.retry") && accepted.action().equals("retry"));
            if(correlated && requestedControl)
                return control+": "+objectiveDescription(mission.action(),mission.args())+".";
            return queueControl?"Queue control accepted; it isn't confirmed yet."
                    : "Control request accepted; it isn't confirmed yet.";
        }
        if(action.equals("queue")) return "Queue accepted; its steps aren't completed yet.";
        if(correlated && !mission.terminal() && !mission.id().equals(previousMissionId)
                && "mission.submit".equals(accepted.operation()) && action.equals(mission.action())
                && mission.commandId().equals(accepted.id()) && accepted.action().equals(mission.action())
                && accepted.args().equals(mission.args()))
            return "Starting: "+objectiveDescription(mission.action(),mission.args())+".";
        if(accepted!=null && "policy.home".equals(accepted.operation())
                && accepted.args().has("operation") && "sleep".equals(accepted.args().get("operation").getAsString()))
            return "I'm going to my Home bed. Sleeping isn't confirmed yet.";
        return "Request accepted; it isn't completed yet.";
    }

    private void rememberCompanion(BridgeCommand command) {
        rememberCompanion(command, null);
    }

    private void rememberCompanion(BridgeCommand command, CompanionFeedback.Owner inheritedOrigin) {
        String queue=command.args().has("queueId")?command.args().get("queueId").getAsString():null;
        String step=command.args().has("queueStep")?command.args().get("queueStep").getAsString():null;
        CompanionFeedback.Owner origin=inheritedOrigin!=null?inheritedOrigin:
                queue==null?aiDispatchOwner:companionFeedback.owner("queue:"+queue+":"+step);
        if(origin==null && aiDispatchQueueAppending) origin=aiDispatchOwner;
        // Normal slash-origin missions use the same exact command/mission correlation.
        // Transient help, status, previews and actionable errors retain their diagnostic UI.
        if(origin==null && command.missionId()!=null && localAi!=null) {
            String session=bridge.connectedSessionId();
            Player requester=Bukkit.getPlayerExact(command.requestedBy());
            if(session!=null && requester!=null && requester.isOnline() && authorize(requester,false)) {
                aiRequestPlayers.put(requester.getUniqueId(),requester);
                while(aiRequestPlayers.size()>32) aiRequestPlayers.remove(aiRequestPlayers.keySet().iterator().next());
                origin=new CompanionFeedback.Owner(requester.getUniqueId(),requester.getWorld().getUID(),session);
            }
        }
        if(origin==null) return;
        companionFeedback.bind("command:"+command.id(),origin,aiDispatchReadQuery && origin.equals(aiDispatchOwner));
        if(command.missionId()!=null) companionFeedback.bind("mission:"+command.missionId(),origin);
        if(queue!=null && step!=null) companionFeedback.bind("queue:"+queue+":"+step,origin);
        if(aiDispatchOwner!=null) {
            aiDispatchAccepted=true;
            if(aiDispatchOwner.equals(origin)) aiDispatchCommand=command;
        }
    }

    private void observedCompanion(CompanionFeedback.Owner origin,JsonObject fact,boolean announce) {
        if(localAi==null || origin==null || !Objects.equals(origin.session(),bridge.connectedSessionId())) return;
        Player player=Bukkit.getPlayer(origin.player());
        if(player==null || player!=aiRequestPlayers.get(origin.player()) || !player.getWorld().getUID().equals(origin.world())
                || !authorize(player,false)) return;
        localAi.observe(origin.player(),origin.world(),origin.session(),fact,announce);
    }

    public void initializeBlueprintAuthority(BlueprintBuildAuthority authority) {
        blueprintAuthority = Objects.requireNonNull(authority);
    }

    /** Bukkit main thread only; transient context is never added to the command replay queue. */
    public void publishIdleContext() {
        publishCompanionReaction();
        if (idleWorldId == null || !settings.ownerConfigured() || !bridge.isConnected()) return;
        Player owner = Bukkit.getPlayerExact(settings.ownerName());
        bridge.sendFrame(idleContextFrame(idleWorldId, settings.ownerName(), owner != null && owner.isOnline(),
                manualWorkPending(orders != null && orders.engaged(), missions.current(), finiteTransient),
                System.currentTimeMillis()));
    }

    static boolean manualWorkPending(boolean queueEngaged, Mission current, BridgeCommand finite) {
        return queueEngaged || current != null && !current.terminal() && !current.id().startsWith("idle:")
                || finite != null && !finite.id().startsWith("idle:")
                && (finite.missionId() == null || !finite.missionId().startsWith("idle:"));
    }

    static JsonObject idleContextFrame(UUID worldId, String ownerName, boolean ownerOnline,
                                       boolean manualWorkPending, long observedAtMillis) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "idle_context");
        frame.addProperty("worldId", worldId.toString());
        frame.addProperty("ownerName", ownerName);
        frame.addProperty("ownerOnline", ownerOnline);
        frame.addProperty("manualWorkPending", manualWorkPending);
        frame.addProperty("observedAtMillis", observedAtMillis);
        return frame;
    }

    private void queue(Player player, String[] args) {
        if (orders == null) { error(player, "Queue is unavailable for this world."); return; }
        try {
            if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("status")) {
                info(player, orders.describe()); return;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("clear")) {
                int removed = orders.clearPending();
                info(player, "Removed " + removed + " waiting queue step(s); current work unchanged. " + orders.describe());
                return;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
                if (orders.cancelAll("Queue cancelled by owner")) stop(player, new String[]{"stop"});
                info(player, orders.describe()); return;
            }
            if (args.length == 2 && Set.of("retry", "resume").contains(args[1].toLowerCase(Locale.ROOT))) {
                orders.retry(); info(player, orders.describe()); return;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("skip")) {
                orders.skip(); info(player, orders.describe()); return;
            }
            List<OrderedJobQueue.Step> steps = QueueInput.parse(args, player.getName(), MAX_AMOUNT,
                    this::normalizeItem, name -> normalizePlayer(name, player), this::queueBlockArguments);
            OrderedJobQueue.Active existing = null;
            if (!orders.engaged()) {
                Mission current = missions.current();
                if (finiteTransient != null) existing = OrderedJobQueue.external(finiteTransient,
                        finiteTransient.action() + " " + finiteTransient.args().get("operation").getAsString());
                else if (current != null && !current.terminal()) {
                    if (!OrderedJobQueue.finiteMission(current.action()))
                        throw new IllegalArgumentException("Current " + current.action() + " is open-ended; stop it before queueing");
                    existing = OrderedJobQueue.external(current);
                }
            }
            String oldQueue=orders.state()==null?null:orders.state().queueId();
            int oldSize=orders.state()==null?0:orders.state().steps().size();
            aiDispatchQueueAppending=aiDispatchOwner!=null;
            try { orders.append(steps, existing); }
            finally { aiDispatchQueueAppending=false; }
            if(aiDispatchOwner!=null && orders.state()!=null) {
                int first=Objects.equals(oldQueue,orders.state().queueId())?oldSize:0;
                for(int i=first;i<orders.state().steps().size();i++)
                    companionFeedback.bind("queue:"+orders.state().queueId()+":"+i,aiDispatchOwner);
                aiDispatchAccepted=true;
            }
            success(player, "Queue accepted. " + orders.describe());
        } catch (IOException | IllegalArgumentException failure) {
            error(player, "Queue rejected: " + failure.getMessage());
        }
    }

    private JsonObject queueBlockArguments(CommandInput.ResourceQuery request) {
        String query = singularAlias(request.query());
        Material material = materialsBySimpleName.get(query);
        JsonObject args = new JsonObject(); args.addProperty("query", query); args.addProperty("amount", request.amount());
        if (friendlyBlockGroups.containsKey(query)) {
            JsonArray blocks = new JsonArray(); friendlyBlockGroups.get(query).forEach(blocks::add); args.add("blocks", blocks);
        } else if (material != null && !material.isAir() && material.isBlock()) args.addProperty("block", material.getKey().toString());
        else throw new IllegalArgumentException("Unknown mineable block: " + query);
        return args;
    }

    /** The AI shares native command identity, never an independent resource vocabulary. */
    java.util.Optional<String> resolveAiTarget(String action,String target) {
        if(action.equals("mine")) {
            String query=CommandInput.identifier(target);
            if(query==null)return java.util.Optional.empty();
            try {
                JsonObject args=queueBlockArguments(new CommandInput.ResourceQuery(query,1));
                return java.util.Optional.of(args.has("block")?args.get("block").getAsString():args.get("query").getAsString());
            } catch(IllegalArgumentException unknown) { return java.util.Optional.empty(); }
        }
        String name=CommandInput.identifier(target);
        if(name==null)return java.util.Optional.empty();
        String resolved=normalizeItem(name);
        if(resolved==null && name.endsWith("_stair"))resolved=normalizeItem(name+"s");
        return java.util.Optional.ofNullable(resolved);
    }

    private boolean replaceQueue(Player player, String reason) {
        if (orders == null) return true;
        try { orders.cancel(reason); return true; }
        catch (IOException failure) { error(player, "Cannot save queue cancellation: " + failure.getMessage()); return false; }
    }

    private void rememberRequester(BridgeCommand command) {
        rememberCompanion(command);
        transientRequesters.put(command.id(), command.requestedBy());
        while (transientRequesters.size() > 512) transientRequesters.remove(transientRequesters.keySet().iterator().next());
    }

    private void access(CommandSender sender, String[] args, boolean console) {
        if (!console) {
            Player player = (Player) sender;
            if (!settings.isPrimaryOwner(player.getName())) {
                error(sender, "Only the primary owner can change Entity controller access.");
                return;
            }
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            if (args.length > 2) { error(sender, "Usage: /e access list"); return; }
            TrustedIdentityRegistry.Snapshot access = trustedIdentities.snapshot();
            String names = access.controllers().isEmpty()
                    ? "none"
                    : String.join(", ", access.controllers());
            info(sender, "Entity controllers=" + names + ", lock="
                    + (access.controllersLocked() ? "on" : "off"));
            return;
        }
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (operation.equals("lock") || operation.equals("unlock")) {
            if (args.length != 2) {
                error(sender, "Usage: /e access lock|unlock");
                return;
            }
            boolean controllersLocked = operation.equals("lock");
            trustedIdentities.setControllersLocked(controllersLocked);
            persistControllerAccess();
            relationshipSynchronization.policyChanged();
            success(sender, controllersLocked
                    ? "Trusted controllers are locked out; only the owner and console can control Entity."
                    : "Trusted controllers can control Entity again.");
            return;
        }
        if (!(operation.equals("allow") || operation.equals("deny")) || args.length != 3) {
            error(sender, "Usage: /e access list|allow <player>|deny <player>|lock|unlock");
            return;
        }
        String name = args[2];
        if (!PLAYER_NAME.matcher(name).matches()) {
            error(sender, "Player names can contain only letters, numbers, and underscores.");
            return;
        }
        if (settings.isPrimaryOwner(name)) {
            error(sender, "The primary owner always has access and cannot be added or denied here.");
            return;
        }
        if (operation.equals("allow")) {
            trustedIdentities.allowController(name);
            persistControllerAccess();
            relationshipSynchronization.policyChanged();
            success(sender, name + " can now control Entity without OP.");
        } else {
            String removed = trustedIdentities.denyController(name);
            persistControllerAccess();
            relationshipSynchronization.policyChanged();
            success(sender, removed == null
                    ? name + " was not an allowed controller."
                    : removed + " can no longer control Entity.");
        }
    }

    private void persistControllerAccess() {
        TrustedIdentityRegistry.Snapshot access = trustedIdentities.snapshot();
        plugin.getConfig().set("controller-names", new ArrayList<>(access.controllers()));
        plugin.getConfig().set("controllers-locked", access.controllersLocked());
        plugin.saveConfig();
    }

    private void playerTargetMission(Player player, String action, String[] args, boolean defaultsToOwner) {
        if (args.length > 2 || (!defaultsToOwner && args.length != 2)) {
            error(player, "Usage: /e " + action + (defaultsToOwner ? " [player]" : " <player>"));
            return;
        }
        String target = args.length == 1 || args[1].equalsIgnoreCase("me")
                ? player.getName()
                : args[1];
        if (!PLAYER_NAME.matcher(target).matches()) {
            error(player, "Player names can contain only letters, numbers, and underscores.");
            return;
        }
        JsonObject commandArgs = new JsonObject();
        commandArgs.addProperty("player", target);
        dispatchMission(player, action, commandArgs, target);
    }

    private void goTo(Player player, String[] args) {
        CommandInput.GoToRequest request;
        try {
            request = CommandInput.goTo(args);
        } catch (IllegalArgumentException exception) {
            if ("coordinates".equals(exception.getMessage())) {
                error(player, "Coordinates must be finite numbers.");
            } else if ("player".equals(exception.getMessage())) {
                error(player, "Player names can contain only letters, numbers, and underscores.");
            } else {
                error(player, "Usage: /e goto <player> | /e goto <x> <y> <z>");
            }
            return;
        }
        if (request.targetsPlayer()) {
            String submitted = request.player();
            String target = submitted.equalsIgnoreCase("me")
                    ? player.getName()
                    : Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.equalsIgnoreCase(submitted))
                    .findFirst()
                    .orElse(submitted);
            JsonObject commandArgs = new JsonObject();
            commandArgs.addProperty("player", target);
            // `come` is the existing finite player-approach mission. `follow`
            // would silently turn this spelling into an unbounded pursuit.
            dispatchMission(player, "come", commandArgs, target);
            return;
        }
        JsonObject commandArgs = new JsonObject();
        commandArgs.addProperty("x", request.x());
        commandArgs.addProperty("y", request.y());
        commandArgs.addProperty("z", request.z());
        commandArgs.addProperty("dimension", player.getWorld().getKey().toString());
        dispatchMission(player, "goto", commandArgs, null);
    }

    private void resourceMission(Player player, String action, boolean requireBlock, String[] args) {
        CommandInput.ResourceQuery request;
        try {
            request = CommandInput.resource(args, 1, MAX_AMOUNT);
        } catch (IllegalArgumentException exception) {
            if ("amount".equals(exception.getMessage())) {
                error(player, "Amount must be between 1 and " + MAX_AMOUNT + ".");
            } else {
                error(player, "Usage: /e " + action + " <resource> [amount]");
            }
            return;
        }

        String query = singularAlias(request.query());
        Material material = materialsBySimpleName.get(query);
        JsonObject commandArgs = new JsonObject();
        commandArgs.addProperty("query", query);
        commandArgs.addProperty("amount", request.amount());

        if (friendlyBlockGroups.containsKey(query)) {
            JsonArray blocks = new JsonArray();
            friendlyBlockGroups.get(query).forEach(blocks::add);
            commandArgs.add("blocks", blocks);
        } else if (material != null && !material.isAir() && material.isBlock()) {
            commandArgs.addProperty("block", material.getKey().toString());
        } else {
            if (requireBlock) {
                error(player, "Unknown block or supported block group: " + query.replace('_', ' '));
            } else {
                error(player, "Experimental /e get currently accepts only mineable blocks. "
                        + "Arbitrary items and crafting are not implemented; use /e mine <block>.");
            }
            return;
        }
        dispatchMission(player, action, commandArgs, null);
    }

    private void acquisitionMission(Player player, String[] args) {
        List<CommandInput.ItemAmount> batch;
        try {
            batch = CommandInput.itemBatch(args, 1, MAX_AMOUNT, this::normalizeItem);
        } catch (IllegalArgumentException exception) {
            itemUsageError(player, "get", exception);
            return;
        }
        if (batch != null) {
            dispatchMission(player, "get", ItemBatchArguments.encode(batch, null), null);
            return;
        }

        CommandInput.ResourceQuery request;
        try {
            request = CommandInput.resource(args, 1, MAX_AMOUNT);
        } catch (IllegalArgumentException exception) {
            itemUsageError(player, "get", exception);
            return;
        }
        String item = normalizeItem(request.query());
        if (item == null) {
            error(player, "Unknown obtainable item: " + request.query().replace('_', ' '));
            return;
        }

        JsonObject commandArgs = itemArguments(item, request.amount());
        // Protocol-1 compatibility: old consumers used query/amount. The 2.2
        // client consumes the canonical item/count pair and does not turn get
        // into a mining alias.
        commandArgs.addProperty("query", simpleIdentifier(item));
        commandArgs.addProperty("amount", request.amount());
        dispatchMission(player, "get", commandArgs, null);
    }

    private void deliveryMission(Player player, String action, String[] args) {
        CommandInput.DeliveryBatch batch;
        try {
            batch = CommandInput.deliveryBatch(
                    args,
                    1,
                    MAX_AMOUNT,
                    player.getName(),
                    this::normalizeItem,
                    value -> normalizePlayer(value, player));
        } catch (IllegalArgumentException exception) {
            itemUsageError(player, action, exception);
            return;
        }
        if (batch != null) {
            JsonObject commandArgs = ItemBatchArguments.encode(batch.items(), batch.player());
            commandArgs.addProperty("useReserves", batch.useReserves());
            dispatchMission(player, action, commandArgs, batch.player());
            return;
        }

        CommandInput.DeliveryQuery request;
        try {
            request = CommandInput.delivery(
                    args,
                    1,
                    MAX_AMOUNT,
                    player.getName(),
                    this::normalizeItem,
                    value -> normalizePlayer(value, player)
            );
        } catch (IllegalArgumentException exception) {
            itemUsageError(player, action, exception);
            return;
        }

        JsonObject commandArgs = itemArguments(request.item(), request.count());
        commandArgs.addProperty("player", request.player());
        commandArgs.addProperty("useReserves", request.useReserves());
        if (request.all()) {
            if (!action.equals("give")) {
                error(player, "Use all only with /e give; /e bring needs a target count.");
                return;
            }
            commandArgs.addProperty("all", true);
        }
        dispatchMission(player, action, commandArgs, request.player());
    }

    private void gearMission(Player player, String[] args) {
        CommandInput.GearRequest request;
        try {
            request = CommandInput.gear(args);
        } catch (IllegalArgumentException ignored) {
            error(player, "Usage: /e gear [best|wood|stone|iron|diamond|status]");
            return;
        }
        if (request.operation().equals("status")) {
            JsonObject query = new JsonObject();
            query.addProperty("equipment", true);
            dispatchTransient(player, "gear", "inventory", "query.inventory", query);
            return;
        }
        JsonObject commandArgs = new JsonObject();
        commandArgs.addProperty("tier", request.tier());
        dispatchMission(player, "gear", commandArgs, null);
    }

    private void plan(Player player, String[] args) {
        if (args.length != 1) {
            error(player, "Usage: /e plan");
            return;
        }
        JsonObject query = new JsonObject();
        Mission current = missions.current();
        if (current != null) {
            query.addProperty("missionId", current.id());
            query.addProperty("sequence", current.sequence());
        }
        dispatchTransient(player, "plan", "query.plan", query);
    }

    private void inventory(Player player, String[] args) {
        JsonObject query = new JsonObject();
        if (args.length >= 2) {
            String requested = String.join("_", Arrays.copyOfRange(args, 1, args.length)).toLowerCase(Locale.ROOT).replace('-', '_');
            if (requested.startsWith("minecraft:")) requested = requested.substring(10);
            String item = normalizeItem(requested);
            if (item == null) {
                error(player, "Unknown item: " + requested.replace('_', ' ') + ". Use Tab for item names.");
                return;
            }
            // Inventory groups describe all observed members, not the crafting default species.
            query.addProperty("item", switch (requested) {
                case "log", "logs", "tree", "trees", "wood" -> "logs";
                case "plank", "planks" -> "planks";
                default -> item;
            });
        }
        dispatchTransient(player, "inventory", "query.inventory", query);
    }

    private void stock(Player player, String[] args) {
        CommandInput.StockRequest request;
        try {
            request = CommandInput.stock(args);
        } catch (IllegalArgumentException ignored) {
            error(player, "Usage: /e stock on|off|run|status");
            return;
        }
        JsonObject policy = new JsonObject();
        policy.addProperty("operation", request.operation());
        if (request.enabled() != null) {
            policy.addProperty("enabled", request.enabled());
        }
        dispatchTransient(player, "stock", "policy.stock", policy);
    }

    private void farm(Player player, String[] args) {
        CommandInput.FarmRequest request;
        try {
            request = CommandInput.farm(args);
        } catch (IllegalArgumentException invalid) {
            error(player, "Usage: /e farm run <harvesting-area> | auto <area> on|off | status [area]");
            return;
        }
        if (request.operation().equals("status")) {
            List<ProtectedAreaPolicy.Area> fields = protectedAreas.snapshot()
                    .areasOfKind(ProtectedAreaPolicy.AreaKind.HARVESTING);
            if (request.area() != null) {
                fields = fields.stream().filter(area -> area.name().equals(request.area())).toList();
                if (fields.isEmpty()) {
                    error(player, "No harvesting area named '" + request.area() + "' exists.");
                    return;
                }
            }
            if (fields.isEmpty()) {
                info(player, "No managed farm is selected. Use /e area begin harvesting <name>.");
                return;
            }
            if (idleWorldId == null || !bridge.isConnected()) {
                for (ProtectedAreaPolicy.Area field : fields) {
                    info(player, "Farm '" + field.name() + "': "
                            + field.width() + "x" + field.depth() + " in "
                            + field.dimension() + "; Entity offline, so automatic preference, "
                            + "live crops, supplies and storage cannot be read.");
                }
                return;
            }
            JsonObject policy = new JsonObject();
            policy.addProperty("operation", "status");
            policy.addProperty("worldId", idleWorldId.toString());
            if (request.area() != null) policy.addProperty("area", request.area());
            dispatchTransient(player, "farm status", "policy.farm", policy);
            return;
        }

        ProtectedAreaPolicy.Area field = protectedAreas.named(request.area()).orElse(null);
        if (field == null || field.kind() != ProtectedAreaPolicy.AreaKind.HARVESTING) {
            error(player, "'" + request.area() + "' is not a confirmed harvesting area.");
            return;
        }
        if (request.operation().equals("auto")) {
            if (!settings.isPrimaryOwner(player.getName())) {
                error(player, "Only the primary owner can change automatic farm preferences.");
                return;
            }
            if (idleWorldId == null || !bridge.isConnected()) {
                error(player, "Entity must be connected to change world-scoped farm preferences.");
                return;
            }
            JsonObject policy = new JsonObject();
            policy.addProperty("operation", "auto");
            policy.addProperty("area", field.name());
            policy.addProperty("areaFingerprint", ManagedFarmPolicy.areaFingerprint(field));
            policy.addProperty("enabled", request.enabled());
            policy.addProperty("worldId", idleWorldId.toString());
            dispatchTransient(player, "farm auto", "policy.farm", policy);
            return;
        }

        JsonObject mission = new JsonObject();
        mission.addProperty("area", field.name());
        mission.addProperty("areaFingerprint", ManagedFarmPolicy.areaFingerprint(field));
        dispatchMission(player, "farm", mission, null);
    }

    private void build(Player player, String[] args) {
        CommandInput.BuildRequest request;
        try { request = CommandInput.build(args); }
        catch (IllegalArgumentException invalid) {
            error(player, "Usage: /e build list|import <HTTPS-link or Abfielder ID>|select <id>|here|rotate <90|180|270>"
                    + "|show|materials|confirm [gather]|status|resume|clear [confirm]");
            return;
        }
        if (request.ownerOnly() && !settings.isPrimaryOwner(player.getName())) {
            error(player, "Only the primary owner can change or authorize a building project.");
            return;
        }
        if (idleWorldId == null || !bridge.isConnected()) {
            error(player, "Entity must be connected to inspect or change this world's building project.");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("operation", request.operation());
        payload.addProperty("worldId", idleWorldId.toString());
        payload.addProperty("dimension", player.getWorld().getKey().toString());
        Location site = player.getLocation();
        payload.addProperty("x", site.getBlockX());
        payload.addProperty("y", site.getBlockY());
        payload.addProperty("z", site.getBlockZ());
        payload.addProperty("yaw", site.getYaw());
        payload.addProperty("front", player.getFacing().name().toLowerCase(Locale.ROOT));
        payload.addProperty("owner", player.getName());
        if (request.source() != null) payload.addProperty("source", request.source());
        if (request.design() != null) payload.addProperty("design", request.design());
        if (request.rotation() != null) payload.addProperty("rotation", request.rotation());
        if (request.mission()) {
            payload.addProperty("policyRevision",protectedAreas.snapshot().revision());
            payload.addProperty("policyDigest",protectedAreas.snapshot().digest());
            if(request.operation().equals("resume")) {
                // The durable client project owns confirmation; recover this session's
                // exact server preview before dispatching the same owner's mission.
                dispatchTransient(player,"build resume","blueprint_policy","policy.blueprint",payload);
                return;
            }
            BlueprintPreview preview = blueprintAuthority == null ? null : blueprintAuthority.previewFor(
                    player.getName(), idleWorldId.toString(), player.getWorld().getKey().toString());
            if (preview == null) {
                error(player, "Preview the exact site with /e build show before confirming.");
                return;
            }
            payload.addProperty("projectId", preview.projectId());
            payload.addProperty("digest", preview.digest());
            if (request.operation().equals("confirm")) payload.addProperty("gather", request.gather());
            dispatchMission(player, "build", payload, null);
        } else {
            if (Set.of("import", "select", "here", "rotate", "clear_confirm").contains(request.operation())) {
                if (blueprintAuthority != null) blueprintAuthority.clear();
                retireBlueprintResume();
                latestBlueprintPreviewRequest = null;
            }
            dispatchTransient(player, "build " + request.operation(), "blueprint_policy", "policy.blueprint", payload);
        }
    }

    private void area(Player player, String[] args) {
        area(player, args, false);
    }

    private void area(Player player, String[] args, boolean homeProtection) {
        CommandInput.AreaRequest request;
        try {
            request = homeProtection ? CommandInput.homeProtect(args) : CommandInput.area(args);
        } catch (IllegalArgumentException ignored) {
            if (homeProtection) {
                error(player, "Usage: /e home protect begin <name>|finish|confirm|cancel|show [name]|remove <name> [confirm]");
                return;
            }
            error(player, "Usage: /e area begin protected|mining|harvesting <name> "
                    + "| finish | confirm | cancel | list | "
                    + "show <name>|remove <name> [confirm]");
            return;
        }
        boolean ownerOnly = Set.of(
                "begin", "finish", "confirm", "cancel", "remove", "remove_confirm")
                .contains(request.operation());
        if (ownerOnly && !settings.isPrimaryOwner(player.getName())) {
            error(player, "Only the primary owner can change property and resource areas.");
            return;
        }
        if (protectedAreas.failClosed() && !request.operation().equals("cancel")) {
            error(player, protectedAreas.warning());
            return;
        }

        long now = System.currentTimeMillis();
        String dimension = player.getWorld().getKey().toString();
        String areaCommand = homeProtection ? "/e home protect" : "/e area";
        try {
            ProtectedAreaSelection.PendingSelection selected = areaSelection.selection(now);
            if (Set.of("finish", "confirm", "cancel").contains(request.operation())
                    && selected != null
                    && (selected.kind() == ProtectedAreaPolicy.AreaKind.ENTITY_BASE) != homeProtection) {
                throw new IllegalArgumentException("Continue this selection with "
                        + (selected.kind() == ProtectedAreaPolicy.AreaKind.ENTITY_BASE
                        ? "/e home protect" : "/e area"));
            }
            if (Set.of("show", "remove", "remove_confirm").contains(request.operation())) {
                ProtectedAreaPolicy.Area named = protectedAreas.named(request.name()).orElseThrow(() ->
                        new IllegalArgumentException("No area named '" + request.name() + "' exists"));
                if ((named.kind() == ProtectedAreaPolicy.AreaKind.ENTITY_BASE) != homeProtection) {
                    throw new IllegalArgumentException("Use "
                            + (named.kind() == ProtectedAreaPolicy.AreaKind.ENTITY_BASE
                            ? "/e home protect" : "/e area") + " for this area");
                }
            }
            switch (request.operation()) {
                case "begin" -> {
                    Location feet = selectionFeet(player, request.kind());
                    ProtectedAreaSelection.PendingSelection begun = areaSelection.begin(
                            player.getName(),
                            request.kind(),
                            request.name(),
                            feet.getWorld().getKey().toString(),
                            feet.getBlockX(),
                            feet.getBlockY(),
                            feet.getBlockZ(),
                            now);
                    String point = begun.kind() == ProtectedAreaPolicy.AreaKind.MINING
                            ? begun.firstX() + " " + begun.firstY() + " " + begun.firstZ()
                            : begun.firstX() + " " + begun.firstZ();
                    success(player, begun.kind().label() + " area '" + begun.name()
                            + "' began at " + point + " in " + begun.dimension() + ". "
                            + (begun.kind() == ProtectedAreaPolicy.AreaKind.MINING
                            ? "This is the retained mine entrance. Move Entity to the opposite 3D corner"
                            : "Walk to the opposite X/Z corner")
                            + " and use " + areaCommand + " finish.");
                }
                case "finish" -> {
                    ProtectedAreaSelection.PendingSelection current = areaSelection.selection(now);
                    ProtectedAreaPolicy.AreaKind kind = current == null
                            ? ProtectedAreaPolicy.AreaKind.PROTECTED
                            : current.kind();
                    Location feet = selectionFeet(player, kind);
                    ProtectedAreaSelection.PendingSelection finished = areaSelection.finish(
                            player.getName(),
                            feet.getWorld().getKey().toString(),
                            feet.getBlockX(),
                            feet.getBlockY(),
                            feet.getBlockZ(),
                            now);
                    ProtectedAreaPolicy.Area preview = finished.preview();
                    showAreaPreview(player, preview);
                    info(player, describeArea(preview) + ". Preview only; use " + areaCommand
                            + " confirm to activate it or " + areaCommand + " cancel.");
                }
                case "confirm" -> {
                    ProtectedAreaPolicy.Snapshot snapshot = areaSelection.confirm(
                            player.getName(), dimension, now);
                    boolean delivered = areaSynchronization.policyChanged();
                    success(player, "Typed area policy saved at revision "
                            + snapshot.revision() + " (" + shortDigest(snapshot.digest())
                            + "). " + (delivered
                            ? "Sent to Entity; mutation authority remains fail-closed until its exact acknowledgement."
                            : "Entity is offline; mutation authority remains fail-closed until it reconnects and acknowledges."));
                }
                case "cancel" -> {
                    if (areaSelection.cancel(player.getName())) {
                        success(player, "Cancelled the area preview; active policy was unchanged.");
                    } else {
                        info(player, "There is no active area preview to cancel.");
                    }
                }
                case "list" -> {
                    if (homeProtection) {
                        List<ProtectedAreaPolicy.Area> bases = protectedAreas.snapshot()
                                .areasOfKind(ProtectedAreaPolicy.AreaKind.ENTITY_BASE);
                        if (bases.isEmpty()) info(player, "No Entity-base protection is selected.");
                        for (ProtectedAreaPolicy.Area base : bases) {
                            info(player, describeArea(base));
                            showAreaPreview(player, base);
                        }
                    } else listAreas(player);
                }
                case "show" -> {
                    ProtectedAreaPolicy.Area area = protectedAreas.named(request.name())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "No area named '" + request.name() + "' exists"));
                    info(player, describeArea(area) + "; policy revision "
                            + protectedAreas.snapshot().revision() + " ("
                            + shortDigest(protectedAreas.snapshot().digest()) + ").");
                    if (settings.isPrimaryOwner(player.getName())
                            && area.dimension().equals(dimension)) {
                        showAreaPreview(player, area);
                    }
                }
                case "remove" -> {
                    ProtectedAreaSelection.PendingRemoval pending = areaSelection.beginRemoval(
                            player.getName(), request.name(), now);
                    info(player, "Remove " + describeArea(pending.area())
                            + "? Use " + areaCommand + " remove " + pending.area().name()
                            + " confirm within 30 seconds.");
                }
                case "remove_confirm" -> {
                    ProtectedAreaPolicy.Snapshot snapshot = areaSelection.confirmRemoval(
                            player.getName(), request.name(), now);
                    boolean delivered = areaSynchronization.policyChanged();
                    success(player, "Removed area '" + request.name()
                            + "'; policy revision " + snapshot.revision() + " ("
                            + shortDigest(snapshot.digest()) + "). " + (delivered
                            ? "Sent to Entity; mutation authority remains fail-closed until its exact acknowledgement."
                            : "Entity is offline; mutation authority remains fail-closed until it reconnects and acknowledges."));
                }
                default -> throw new IllegalArgumentException("unsupported area operation");
            }
        } catch (IllegalArgumentException error) {
            error(player, error.getMessage());
        } catch (IOException error) {
            plugin.getLogger().warning("Could not persist typed area policy: "
                    + error.getMessage());
            error(player, "Area policy was not changed because Paper could not save it.");
        }
    }

    private static Location selectionFeet(
            Player owner,
            ProtectedAreaPolicy.AreaKind kind) {
        if (kind != ProtectedAreaPolicy.AreaKind.MINING) return owner.getLocation();
        Player entity = Bukkit.getPlayerExact(FriendlyDamageGuardListener.ENTITY_PLAYER_NAME);
        if (entity == null || !entity.isOnline()) {
            throw new IllegalArgumentException(
                    "Entity must be online to mark a mining entrance or 3D corner");
        }
        if (!entity.getWorld().equals(owner.getWorld())) {
            throw new IllegalArgumentException(
                    "Owner and Entity must be in the same dimension to select a mining area");
        }
        return entity.getLocation();
    }

    private void listAreas(Player player) {
        ProtectedAreaPolicy.Snapshot snapshot = protectedAreas.snapshot();
        if (snapshot.areas().isEmpty()) {
            info(player, "No property or resource areas are active; policy revision "
                    + snapshot.revision() + " (" + shortDigest(snapshot.digest()) + ").");
            return;
        }
        info(player, "Areas at revision " + snapshot.revision() + " ("
                + shortDigest(snapshot.digest()) + "): "
                + String.join(", ", snapshot.areas().stream()
                        .map(area -> area.kind().wireName() + ":" + area.name()).toList()));
    }

    private void showAreaPreview(Player player, ProtectedAreaPolicy.Area area) {
        if (!player.getWorld().getKey().toString().equals(area.dimension())) return;
        new BukkitRunnable() {
            private int repetitions;

            @Override
            public void run() {
                if (!player.isOnline()
                        || !player.getWorld().getKey().toString().equals(area.dimension())
                        || repetitions++ >= 5) {
                    cancel();
                    return;
                }
                int step = Math.max(1, Math.max(area.width(), area.depth()) / 64);
                if (area.kind() == ProtectedAreaPolicy.AreaKind.MINING) {
                    previewHorizontalOutline(player, area, area.minY() + 0.35D, step);
                    if (area.maxY() != area.minY()) {
                        previewHorizontalOutline(player, area, area.maxY() + 0.35D, step);
                    }
                    int verticalStep = Math.max(1, area.height() / 64);
                    for (int y = area.minY(); y <= area.maxY(); y += verticalStep) {
                        previewParticle(player, area.minX(), y + 0.35D, area.minZ());
                        previewParticle(player, area.minX(), y + 0.35D, area.maxZ());
                        previewParticle(player, area.maxX(), y + 0.35D, area.minZ());
                        previewParticle(player, area.maxX(), y + 0.35D, area.maxZ());
                    }
                    player.spawnParticle(
                            Particle.FLAME,
                            new Location(
                                    player.getWorld(),
                                    area.entranceX() + 0.5D,
                                    area.entranceY() + 1.0D,
                                    area.entranceZ() + 0.5D),
                            8, 0.25D, 0.5D, 0.25D, 0.0D);
                } else {
                    previewHorizontalOutline(
                            player, area, player.getLocation().getY() + 0.35D, step);
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private static void previewHorizontalOutline(
            Player player,
            ProtectedAreaPolicy.Area area,
            double y,
            int step) {
        for (int x = area.minX(); x <= area.maxX(); x += step) {
            previewParticle(player, x, y, area.minZ());
            previewParticle(player, x, y, area.maxZ());
        }
        for (int z = area.minZ(); z <= area.maxZ(); z += step) {
            previewParticle(player, area.minX(), y, z);
            previewParticle(player, area.maxX(), y, z);
        }
    }

    private static void previewParticle(Player player, int x, double y, int z) {
        player.spawnParticle(
                Particle.END_ROD,
                new Location(player.getWorld(), x + 0.5D, y, z + 0.5D),
                1,
                0.0D,
                0.0D,
                0.0D,
                0.0D);
    }

    private static String describeArea(ProtectedAreaPolicy.Area area) {
        String horizontal = area.kind().label() + " area '" + area.name() + "' in "
                + area.dimension() + " x=" + area.minX() + ".." + area.maxX()
                + ", z=" + area.minZ() + ".." + area.maxZ();
        return switch (area.kind()) {
            case PROTECTED, ENTITY_BASE -> horizontal + " (" + area.width() + "x" + area.depth()
                    + ", protected at all Y)";
            case HARVESTING -> horizontal + " (" + area.width() + "x" + area.depth()
                    + ", classification-only mature crop/natural tree authority)";
            case MINING -> horizontal + ", y=" + area.minY() + ".." + area.maxY()
                    + " (" + area.width() + "x" + area.height() + "x" + area.depth()
                    + ", entrance=" + area.entranceX() + " " + area.entranceY()
                    + " " + area.entranceZ() + ")";
        };
    }

    private static String shortDigest(String digest) {
        return digest.length() <= 12 ? digest : digest.substring(0, 12);
    }

    private void home(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("protect")) {
            area(player, args, true);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("bind")) {
            bindHomeFurniture(player, args);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("storage")) {
            homeStorage(player, args);
            return;
        }
        CommandInput.HomeRequest request;
        try {
            request = CommandInput.home(args);
        } catch (IllegalArgumentException ignored) {
            error(player, "Use /e help home. Home: set [confirm], adopt [confirm], setup, status, show, go, sleep, clear [confirm].");
            return;
        }
        if (Set.of("set", "set_confirm", "establish", "setup", "adopt", "adopt_confirm", "clear_confirm")
                .contains(request.operation())
                && !settings.isPrimaryOwner(player.getName())) {
            error(player, "Only the primary owner can set, relocate, or confirm clearing Home.");
            return;
        }
        JsonObject policy = new JsonObject();
        policy.addProperty("operation", request.operation());
        if (request.operation().equals("show")) {
            var bases = protectedAreas.snapshot().areasOfKind(ProtectedAreaPolicy.AreaKind.ENTITY_BASE);
            int shown = 0;
            for (var base : bases) {
                if (base.dimension().equals(player.getWorld().getKey().toString())) {
                    showAreaPreview(player, base);
                    shown++;
                }
            }
            info(player, shown == 0 ? "No Entity-house protection outline in this dimension. Home does not create protection; /e help area."
                    : "Showing " + shown + " Entity-house protection outline(s); /e home status is text only.");
        }
        dispatchTransient(player, "home", "policy.home", policy);
    }

    private void bindHomeFurniture(Player player, String[] args) {
        if (!settings.isPrimaryOwner(player.getName())) {
            error(player, "Only the primary owner can bind Home furniture.");
            return;
        }
        if (args.length != 3) {
            error(player, "Usage: /e home bind <table|furnace|chest|bed|confirm>");
            return;
        }
        try {
            boolean confirm = args[2].equalsIgnoreCase("confirm");
            var preview = selectHomeFurniture(player, homeFurnitureSelection,
                    args[2].toLowerCase(Locale.ROOT), confirm);
            JsonObject policy = HomeFurnitureSelection.policy(confirm ? "bind_confirm" : "bind_preview", preview);
            dispatchTransient(player, "home bind", "policy.home", policy);
        } catch (IllegalArgumentException invalid) {
            error(player, invalid.getMessage());
        }
    }

    private void homeStorage(Player player, String[] args) {
        CommandInput.HomeStorageRequest request;
        try {
            request = CommandInput.homeStorage(args);
        } catch (IllegalArgumentException invalid) {
            error(player, "Usage: /e home storage add|confirm|list|remove <id>");
            return;
        }
        if (!request.operation().equals("list") && !settings.isPrimaryOwner(player.getName())) {
            error(player, "Only the primary owner can add or remove Home storage registrations.");
            return;
        }
        try {
            JsonObject policy;
            if (request.operation().equals("add") || request.operation().equals("confirm")) {
                boolean confirm = request.operation().equals("confirm");
                var preview = selectHomeFurniture(player, homeStorageSelection, "chest", confirm);
                policy = HomeFurnitureSelection.policy(confirm ? "storage_confirm" : "storage_preview", preview);
            } else {
                policy = new JsonObject();
                policy.addProperty("operation", "storage_" + request.operation());
                if (request.storageId() != null) policy.addProperty("storageId", request.storageId());
            }
            dispatchTransient(player, "home storage", "policy.home", policy);
        } catch (IllegalArgumentException invalid) {
            error(player, invalid.getMessage());
        }
    }

    private HomeFurnitureSelection.Preview selectHomeFurniture(Player player,
            HomeFurnitureSelection selection, String role, boolean confirm) {
        if (!bridge.isConnected() || !areaSynchronization.mutationAuthorityReady()) {
            throw new IllegalArgumentException("Furniture selection requires Entity online with synchronized area policy");
        }
        long now = System.currentTimeMillis();
        ProtectedAreaPolicy.Snapshot areas = protectedAreas.snapshot();
        if (confirm) {
            var preview = selection.pending(player.getName(), now);
            var selected = preview.candidate();
            if (!player.getWorld().getKey().toString().equals(selected.dimension())) {
                throw new IllegalArgumentException("Return to the selected furniture's dimension and select it again");
            }
            if (!player.getWorld().isChunkLoaded(selected.x() >> 4, selected.z() >> 4)) {
                throw new IllegalArgumentException("Selected furniture chunk is not loaded");
            }
            Block block = player.getWorld().getBlockAt(selected.x(), selected.y(), selected.z());
            if (player.getEyeLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > 49) {
                throw new IllegalArgumentException("Remain within seven blocks of the selected furniture to confirm");
            }
            return selection.confirm(player.getName(), observeHomeFurniture(block, selected.role()),
                    areas.revision(), areas.digest(), now);
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null) throw new IllegalArgumentException("Look directly at the furniture within six blocks");
        return selection.begin(player.getName(), observeHomeFurniture(block, role),
                areas.revision(), areas.digest(), now);
    }

    private HomeFurnitureSelection.Candidate observeHomeFurniture(Block selected, String role) {
        Block block = selected;
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            throw new IllegalArgumentException("Selected furniture chunk is not loaded");
        }
        if (role.equals("bed") && block.getBlockData() instanceof Bed bed
                && bed.getPart() == Bed.Part.HEAD) {
            block = block.getRelative(bed.getFacing().getOppositeFace());
        }
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            throw new IllegalArgumentException("Both bed halves must be loaded");
        }
        String dimension = block.getWorld().getKey().toString();
        requireFurnitureProperty(dimension, block);
        String facing = block.getBlockData() instanceof Directional directional
                ? directional.getFacing().name().toLowerCase(Locale.ROOT) : "";
        if (role.equals("bed")) {
            if (!(block.getBlockData() instanceof Bed bed) || bed.getPart() != Bed.Part.FOOT) {
                throw new IllegalArgumentException("Select a complete vanilla bed");
            }
            Block head = block.getRelative(bed.getFacing());
            if (!head.getWorld().isChunkLoaded(head.getX() >> 4, head.getZ() >> 4)) {
                throw new IllegalArgumentException("Both bed halves must be loaded");
            }
            requireFurnitureProperty(dimension, head);
            if (head.getType() != block.getType() || !(head.getBlockData() instanceof Bed other)
                    || other.getPart() != Bed.Part.HEAD || other.getFacing() != bed.getFacing()) {
                throw new IllegalArgumentException("Selected bed does not have its matching head/foot");
            }
        }
        if (role.equals("chest") && block.getBlockData() instanceof Chest chest) {
            String chestType = chest.getType().name().toLowerCase(Locale.ROOT);
            HomeFurnitureSelection.ConnectedChest connected = null;
            if (chest.getType() != Chest.Type.SINGLE) {
                var offset = HomeFurnitureSelection.chestConnection(facing, chestType);
                int otherX = block.getX() + offset.x();
                int otherZ = block.getZ() + offset.z();
                if (!block.getWorld().isChunkLoaded(otherX >> 4, otherZ >> 4)) {
                    throw new IllegalArgumentException("Both connected chest halves must be loaded");
                }
                Block other = block.getWorld().getBlockAt(otherX, block.getY(), otherZ);
                requireFurnitureProperty(dimension, other);
                if (!(other.getBlockData() instanceof Chest otherChest)) {
                    throw new IllegalArgumentException("Selected double chest has no matching connected half");
                }
                connected = new HomeFurnitureSelection.ConnectedChest(other.getX(), other.getY(), other.getZ(),
                        other.getType().getKey().toString(), otherChest.getFacing().name().toLowerCase(Locale.ROOT),
                        otherChest.getType().name().toLowerCase(Locale.ROOT));
            }
            return new HomeFurnitureSelection.Candidate(role, dimension, block.getX(), block.getY(), block.getZ(),
                    block.getType().getKey().toString(), facing, chestType, connected);
        }
        return new HomeFurnitureSelection.Candidate(role, dimension,
                block.getX(), block.getY(), block.getZ(), block.getType().getKey().toString(), facing);
    }

    private void requireFurnitureProperty(String dimension, Block block) {
        var decision = ProtectedAreaPolicy.decide(protectedAreas.snapshot(),
                areaSynchronization.mutationAuthorityReady(), ProtectedAreaPolicy.Action.BLOCK_INTERACT,
                dimension, block.getX(), block.getY(), block.getZ(), true);
        if (!decision.allowed()) throw new IllegalArgumentException(decision.detail());
    }

    private void homeSleepAlias(Player player, String alias, String[] args) {
        if (args.length != 1) {
            error(player, "Usage: /e " + alias + "  (alias for /e home sleep)");
            return;
        }
        info(player, "/e " + alias + " is the explicit alias for /e home sleep.");
        home(player, new String[]{"home", "sleep"});
    }

    private void tidy(Player player, String[] args) {
        CommandInput.TidyRequest request;
        try {
            request = CommandInput.tidy(args, this::normalizeExactItem);
        } catch (IllegalArgumentException invalid) {
            error(player, invalid.getMessage().equals("tidy exact item")
                    ? "Use an exact item ID, e.g. /e tidy keep iron_pickaxe; groups such as food or tools are not cleanup rules."
                    : "Usage: /e tidy [home|confirm|status] | keep <item> | junk <item> | auto on|off | spot [confirm]");
            return;
        }
        if (!Set.of("preview", "status").contains(request.operation())
                && !settings.isPrimaryOwner(player.getName())) {
            error(player, "Only the primary owner can confirm cleanup or change its rules and drop spot.");
            return;
        }
        try {
            if (!bridge.isConnected()) throw new IllegalArgumentException("Tidy requires Entity online in this world");
            JsonObject policy;
            if (request.operation().startsWith("spot_")) {
                if (!areaSynchronization.mutationAuthorityReady()) {
                    throw new IllegalArgumentException("Drop-spot selection requires synchronized area policy");
                }
                var areas = protectedAreas.snapshot();
                long now = System.currentTimeMillis();
                TidySpotSelection.Preview preview;
                if (request.operation().equals("spot_confirm")) {
                    var previous = tidySpotSelection.pending(player.getName(), now);
                    var selected = previous.candidate();
                    if (!player.getWorld().getUID().toString().equals(selected.worldId())
                            || !player.getWorld().getKey().toString().equals(selected.dimension())) {
                        throw new IllegalArgumentException("Return to the selected drop spot's world and select it again");
                    }
                    if (!player.getWorld().isChunkLoaded(selected.x() >> 4, selected.z() >> 4)) {
                        throw new IllegalArgumentException("Selected drop-spot chunk is not loaded");
                    }
                    Block block = player.getWorld().getBlockAt(selected.x(), selected.y(), selected.z());
                    if (player.getEyeLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > 49) {
                        throw new IllegalArgumentException("Remain within seven blocks of the drop spot to confirm");
                    }
                    preview = tidySpotSelection.confirm(player.getName(), observeTidySpot(block),
                            areas.revision(), areas.digest(), now);
                } else {
                    Block block = player.getTargetBlockExact(6, FluidCollisionMode.ALWAYS);
                    if (block == null) throw new IllegalArgumentException("Look at the landing block or water within six blocks");
                    preview = tidySpotSelection.begin(player.getName(), observeTidySpot(block),
                            areas.revision(), areas.digest(), now);
                    var spot = preview.candidate();
                    info(player, "Drop spot selected at " + spot.x() + ", " + spot.y() + ", " + spot.z()
                            + ". Drops remain ordinary items, not deleted. Confirm with /e tidy spot confirm.");
                }
                policy = TidySpotSelection.policy(request.operation(), preview);
            } else {
                policy = new JsonObject();
                policy.addProperty("operation", request.operation());
                if (request.operation().equals("preview")) policy.addProperty("includeStorage", request.includeStorage());
                if (request.item() != null) policy.addProperty("item", request.item());
                if (request.enabled() != null) policy.addProperty("enabled", request.enabled());
                policy.addProperty("dimension", player.getWorld().getKey().toString());
                policy.addProperty("worldId", player.getWorld().getUID().toString());
            }
            dispatchTransient(player, "tidy", "policy.tidy", policy);
        } catch (IllegalArgumentException invalid) {
            error(player, invalid.getMessage());
        }
    }

    private TidySpotSelection.Candidate observeTidySpot(Block block) {
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            throw new IllegalArgumentException("Selected drop-spot chunk is not loaded");
        }
        boolean water = block.getType() == Material.WATER || block.getType() == Material.BUBBLE_COLUMN;
        var surfaces = water ? List.<TidySpotSelection.Surface>of() : block.getCollisionShape().getBoundingBoxes().stream()
                .map(box -> new TidySpotSelection.Surface(box.getMinX(), box.getMinZ(), box.getMaxX(), box.getMaxZ(), box.getMaxY()))
                .toList();
        return new TidySpotSelection.Candidate(block.getWorld().getKey().toString(), block.getWorld().getUID().toString(),
                block.getX(), block.getY(), block.getZ(), block.getType().getKey().toString(), block.getBlockData().getAsString(),
                block.getX() + 0.5, TidySpotSelection.landingY(block.getY(), water, surfaces), block.getZ() + 0.5);
    }

    private void visuals(Player player, String[] args) {
        CommandInput.ToggleRequest request;
        try {
            request = CommandInput.toggle(args);
        } catch (IllegalArgumentException ignored) {
            error(player, "Usage: /e visuals on|off|status");
            return;
        }
        JsonObject policy = new JsonObject();
        policy.addProperty("operation", request.operation());
        if (request.enabled() != null) {
            policy.addProperty("enabled", request.enabled());
        }
        dispatchTransient(player, "visuals", "policy.visuals", policy);
    }

    private void perception(Player player, String[] args) {
        CommandInput.PerceptionRequest request;
        try {
            request = CommandInput.perception(args);
        } catch (IllegalArgumentException ignored) {
            error(player, "Usage: /e perception normal|legit|status");
            return;
        }
        JsonObject policy = new JsonObject();
        policy.addProperty("operation", request.operation());
        if (request.mode() != null) policy.addProperty("mode", request.mode());
        dispatchTransient(player, "perception", "policy.perception", policy);
    }

    private void idle(Player player, String[] args) {
        CommandInput.IdleRequest request;
        try { request = CommandInput.idle(args); }
        catch (IllegalArgumentException invalid) {
            error(player, "Usage: /e idle on|off|status | delay <1..1440 minutes> | offline on|off | target <food|fuel|logs|iron> <0..4096>");
            return;
        }
        if (!request.operation().equals("status") && !settings.isPrimaryOwner(player.getName())) {
            error(player, "Only the primary owner can change automatic idle-work preferences."); return;
        }
        if (idleWorldId == null || !bridge.isConnected()) {
            error(player, "Entity must be connected to read or change world-scoped idle preferences."); return;
        }
        JsonObject policy = new JsonObject();
        policy.addProperty("operation", request.operation());
        policy.addProperty("worldId", idleWorldId.toString());
        if (request.enabled() != null) policy.addProperty("enabled", request.enabled());
        if (request.delayMillis() != null) policy.addProperty("delayMillis", request.delayMillis());
        if (request.category() != null) policy.addProperty("category", request.category());
        if (request.count() != null) policy.addProperty("count", request.count());
        // Settings alone neither replace manual orders nor create a finite body operation.
        dispatchTransient(player, "idle", "policy.idle", policy);
    }

    private void itemUsageError(Player player, String action, IllegalArgumentException exception) {
        if ("amount".equals(exception.getMessage())) {
            error(player, "Count must be between 1 and " + MAX_AMOUNT + ".");
        } else if ("duplicate".equals(exception.getMessage())) {
            error(player, "Each item may appear only once in a multi-item mission.");
        } else if (action.equals("give") || action.equals("bring")) {
            error(player, "Usage: /e " + action + " [player] <item> [count"
                    + (action.equals("give") ? "|all" : "")
                    + "] [<item> <count> ...] [--use-reserves]");
        } else {
            error(player, "Usage: /e get <item> [count] [<item> <count> ...]");
        }
    }

    private static JsonObject itemArguments(String item, int count) {
        JsonObject args = new JsonObject();
        args.addProperty("item", item);
        args.addProperty("count", count);
        return args;
    }

    private void attackTarget(Player player, String[] args) {
        if (args.length == 1) {
            JsonObject commandArgs = new JsonObject();
            commandArgs.addProperty("hostile", true);
            dispatchMission(player, "attack", commandArgs, null);
            return;
        }
        if (args.length == 2) {
            Player onlineTarget = Bukkit.getOnlinePlayers().stream()
                    .filter(candidate -> candidate.getName().equalsIgnoreCase(args[1]))
                    .findFirst()
                    .orElse(null);
            if (onlineTarget != null) {
                if (onlineTarget.getName().equalsIgnoreCase(
                        FriendlyDamageGuardListener.ENTITY_PLAYER_NAME)
                        || trustedIdentities.isProtected(onlineTarget.getName())) {
                    error(player, onlineTarget.getName()
                            + " is Entity, the owner, or a trusted controller and cannot be attacked.");
                    return;
                }
                JsonObject commandArgs = new JsonObject();
                commandArgs.addProperty("player", onlineTarget.getName());
                dispatchMission(player, "attack", commandArgs, onlineTarget.getName());
                return;
            }
        }

        String joined = String.join("_", Arrays.copyOfRange(args, 1, args.length));
        String entityType = CommandInput.identifier(joined);
        if (entityType == null) {
            error(player, "Usage: /e attack [player or mob]");
            return;
        }
        if (Set.of("nearest", "closest", "hostile", "mob", "monster").contains(entityType)) {
            JsonObject commandArgs = new JsonObject();
            commandArgs.addProperty("hostile", true);
            dispatchMission(player, "attack", commandArgs, null);
            return;
        }
        if (entityType.startsWith("nearest_") || entityType.startsWith("closest_")) {
            entityType = entityType.substring(entityType.indexOf('_') + 1);
        }
        entityType = MOB_ALIASES.getOrDefault(entityType, entityType);
        JsonObject commandArgs = new JsonObject();
        commandArgs.addProperty("entityType", entityType);
        dispatchMission(player, "attack", commandArgs, null);
    }

    private void combat(Player player, String[] args) {
        if (args.length == 1) {
            info(player, "/e combat avoid|defensive|aggressive|status");
            return;
        }
        CommandInput.CombatRequest request;
        try {
            request = CommandInput.combat(args);
        } catch (IllegalArgumentException ignored) {
            error(player, "Usage: /e combat avoid|defensive|aggressive|status");
            return;
        }
        JsonObject commandArgs = new JsonObject();
        commandArgs.addProperty("operation", request.operation());
        if (request.combatMode() != null) {
            commandArgs.addProperty("combatMode", request.combatMode());
        }
        // policy.set already crosses both protocol generations as `protect`.
        // Combat mode is an additive policy field, not a new durable mission.
        dispatchTransient(player, "combat", "protect", "policy.set", commandArgs);
    }

    private void protection(Player player, String[] args) {
        if (args.length == 1) {
            info(player, "/e protect status | protect self|me on|off|status");
            return;
        }
        CommandInput.ProtectionRequest request;
        try {
            request = CommandInput.protection(args);
        } catch (IllegalArgumentException ignored) {
            error(player, "Use /e protect status or choose self/me, then on/off/status.");
            return;
        }
        JsonObject commandArgs = new JsonObject();
        commandArgs.addProperty("operation", request.operation());
        if ("set".equals(request.operation())) {
            commandArgs.addProperty("scope", request.scope());
            commandArgs.addProperty("enabled", request.enabled());
        } else if (request.scope() != null) {
            commandArgs.addProperty("scope", request.scope());
        }
        dispatchTransient(player, "protect", "policy.set", commandArgs);
    }

    private void noArgumentMission(Player player, String action, String[] args, String trackedPlayer) {
        if (args.length != 1) {
            error(player, "Usage: /e " + action);
            return;
        }
        dispatchMission(player, action, new JsonObject(), trackedPlayer);
    }

    private void dispatchMission(Player player, String action, JsonObject args, String trackedPlayer) {
        dispatchMission(player, action, args, trackedPlayer, null);
    }

    private void dispatchMission(Player player, String action, JsonObject args, String trackedPlayer,
                                 CompanionFeedback.Owner inheritedOrigin) {
        retireBlueprintResume();
        Mission queuedPrior = orders == null ? null : orders.missionForReplacement();
        if (!replaceQueue(player, "Replaced by direct " + action)) return;
        finiteTransient = null;
        MissionRegistry.Creation creation = missions.createWithSupersession(action, args, player.getName());
        Mission mission = creation.mission();
        BridgeCommand command = BridgeCommand.start(mission);
        Mission replaced = creation.superseded()!=null ? creation.superseded() : queuedPrior;
        BridgeServer.Dispatch dispatch;
        if (replaced != null) {
            BridgeCommand cancel = BridgeCommand.lifecycle(
                    replaced, "cancel", player.getName()
            );
            BridgeServer.SupersessionDispatch supersession = bridge.submitSuperseding(cancel, command);
            dispatch = supersession.replacement();
            if (!supersession.accepted()) {
                missions.rejectCreation(creation, supersession.message());
                missionDispatchNotice(player, inheritedOrigin, false, "Could not preempt mission #" + replaced.sequence()
                        + "; it remains current. " + supersession.message());
                return;
            }
        } else {
            dispatch = bridge.submit(command);
        }
        if (!dispatch.accepted()) {
            missions.rejectCreation(creation, dispatch.message());
            missionDispatchNotice(player, inheritedOrigin, false, "Mission #" + mission.sequence() + " saved but blocked: " + dispatch.message());
            return;
        }
        rememberCompanion(command, inheritedOrigin);
        // A cancelled queue's parked step is no longer a resumable owner pause.
        // Retirement follows accepted cancel+replacement, not before bridge admission.
        if (orders!=null) try { orders.retireCancelledStep(); }
        catch (IOException failure) { plugin.getLogger().severe("Could not retire replaced queue step: "+failure.getMessage()); }
        if (trackedPlayer != null) {
            targetTracker.track(command, trackedPlayer);
        } else {
            targetTracker.clear();
        }
        String state;
        if (creation.superseded() != null) {
            state = dispatch.delivered()
                    ? "started after superseding #" + creation.superseded().sequence()
                    : "saved behind an ordered cancellation of #" + creation.superseded().sequence();
        } else {
            state = dispatch.delivered() ? "started" : "saved; waiting for Entity";
        }
        missionDispatchNotice(player, inheritedOrigin, true, "Mission #" + mission.sequence() + " " + state + " [" + mission.shortId() + "]: "
                + objectiveDescription(action, args) + ".");
    }

    private void missionDispatchNotice(Player player, CompanionFeedback.Owner origin, boolean accepted, String message) {
        if (origin == null) {
            if (accepted) success(player, message); else error(player, message);
            return;
        }
        JsonObject fact = new JsonObject(); fact.addProperty("kind", "dispatch_result");
        fact.addProperty("status", accepted ? "accepted_not_completed" : "rejected");
        fact.addProperty("detail", message);
        observedCompanion(origin, fact, true);
    }

    /** A successor also retires the old resume preview, not just its dispatch payload. */
    private void retireBlueprintResume() {
        if (Objects.equals(latestBlueprintPreviewRequest, latestBlueprintResumeRequest)) latestBlueprintPreviewRequest = null;
        latestBlueprintResumeRequest = null; pendingBlueprintResume = null;
    }

    private void dispatchTransient(Player player, String action, JsonObject args) {
        dispatchTransient(player, action, null, args);
    }

    private void dispatchTransient(Player player, String action, String operation, JsonObject args) {
        dispatchTransient(player, action, action, operation, args);
    }

    private void dispatchTransient(
            Player player,
            String displayAction,
            String wireAction,
            String operation,
            JsonObject args) {
        boolean finiteHome = ("policy.stock".equals(operation) && args.has("operation")
                && "run".equals(args.get("operation").getAsString()))
                || ("policy.home".equals(operation) && args.has("operation")
                && "go".equals(args.get("operation").getAsString()));
        boolean directWork = CommandPresentation.replacesWork(operation, args)
                || "policy.home".equals(operation) && args.has("operation")
                && "sleep".equals(args.get("operation").getAsString());
        // Sleep temporarily parks the exact client mission; canceling the queue or sending
        // handoffStop here would destroy that continuation before admission reaches the client.
        boolean sleepInterrupt = "policy.home".equals(operation) && args.has("operation")
                && "sleep".equals(args.get("operation").getAsString());
        boolean replacingWork = directWork && !sleepInterrupt;
        boolean replacingQueueWork = replacingWork && orders != null && orders.engaged();
        if (replacingWork && !replaceQueue(player, "Replaced by direct " + displayAction)) return;
        if (replacingQueueWork) {
            try { orders.retireCancelledStep(); }
            catch (IOException failure) {
                error(player, "Cannot retire the previous queue job: " + failure.getMessage()); return;
            }
            // Home uses its existing idle executor. Cancel the former body's
            // mission before accepting that executor's new finite request.
            BridgeServer.Dispatch stop = bridge.submit(BridgeCommand.handoffStop(player.getName()));
            if (!stop.accepted()) { error(player, "Could not stop previous queue job: " + stop.message()); return; }
            targetTracker.clear();
            finiteTransient = null;
        }
        BridgeCommand command = operation == null
                ? BridgeCommand.create(wireAction, args, player.getName())
                : BridgeCommand.operation(wireAction, operation, args, player.getName());
        BridgeServer.Dispatch dispatch = bridge.submit(command);
        if (!dispatch.accepted()) {
            error(player, dispatch.message());
        } else {
            rememberCompanion(command);
            if (finiteHome) finiteTransient = command;
            if ("policy.blueprint".equals(operation) && args.has("operation")
                    && Set.of("select", "here", "rotate", "show", "resume").contains(args.get("operation").getAsString())) {
                latestBlueprintPreviewRequest = command.id();
                latestBlueprintResumeRequest = args.has("operation")&&"resume".equals(args.get("operation").getAsString())
                        ? command.id() : null;
                pendingBlueprintResume=latestBlueprintResumeRequest==null?null:args.deepCopy();
            } else if(directWork) { retireBlueprintResume(); }
            transientRequesters.put(command.id(), player.getName());
            while (transientRequesters.size() > 512) {
                transientRequesters.remove(transientRequesters.keySet().iterator().next());
            }
            if (dispatch.delivered()) {
                success(player, "Sent " + displayAction + " [" + shortId(command.id()) + "].");
            } else {
                info(player, "Entity offline; queued " + displayAction + " [" + shortId(command.id()) + "].");
            }
        }
    }

    private void lifecycle(Player player, String operation, String[] args) {
        if (args.length > 2) {
            error(player, "Usage: /e " + operation + " [mission id]");
            return;
        }
        String reference = args.length == 2 ? args[1] : "current";
        Mission mission = missions.resolve(reference).orElse(null);
        String referenceCurrentMissionId = missions.current() == null ? null : missions.current().id();
        if (invalidExplicitMissionReference(args, mission)) {
            error(player, "No unique mission matches '" + reference + "'; nothing changed. Use /e missions.");
            return;
        }
        retireBlueprintResume();
        boolean currentReference = args.length == 1 || reference.equalsIgnoreCase("current");
        boolean transientQueue = currentReference && orders != null && orders.engaged()
                && orders.state().active() != null && !orders.state().active().activeStep().mission();
        if (operation.equals("pause") && orders != null && orders.engaged()
                && (transientQueue || mission == null || orders.owns(mission.id()))
                && (orders.state().phase() == OrderedJobQueue.Phase.SKIPPING
                || orders.state().phase() == OrderedJobQueue.Phase.SKIP_BLOCKED)) {
            try { orders.pauseByOwner(); info(player, orders.describe()); }
            catch (IOException failure) { error(player, "Cannot save queue pause: " + failure.getMessage()); }
            return;
        }
        if (transientQueue && !Set.of("retry", "resume").contains(operation)) {
            error(player, orders.state().active().activeStep().label() + " does not support " + operation
                    + ". Use /e queue skip to skip it, or /e queue cancel to stop the chain and turn Idle OFF.");
            return;
        }
        if (currentReference && finiteTransient != null && !transientQueue) {
            String restart = "/e " + finiteTransient.action() + " "
                    + finiteTransient.args().get("operation").getAsString();
            error(player, "Current " + restart + " work has no mission " + operation
                    + " control. Use /e stop to cancel it, then " + restart + " to start again; /e stop turns Idle OFF.");
            return;
        }
        if (orders != null && orders.engaged() && (transientQueue || mission == null || orders.owns(mission.id()))
                && Set.of("retry", "resume").contains(operation)) {
            queue(player, new String[]{"queue", operation});
            return;
        }
        if (mission == null) {
            error(player, "No unique mission matches '" + reference + "'. Use /e missions.");
            return;
        }
        if (mission.status() == MissionStatus.CANCELLED || mission.status() == MissionStatus.SUCCEEDED) {
            error(player, "Mission #" + mission.sequence() + " is already "
                    + mission.status().name().toLowerCase(Locale.ROOT)
                    + "; issue a new objective instead.");
            return;
        }
        if (operation.equals("retry")) {
            mission = missions.retry(mission.id()).orElseThrow();
        } else {
            MissionStatus status = switch (operation) {
                case "pause" -> MissionStatus.PAUSED;
                case "resume" -> MissionStatus.ACTIVE;
                case "cancel" -> MissionStatus.CANCELLED;
                default -> throw new IllegalStateException("Unexpected operation " + operation);
            };
            String reason = switch (operation) {
                case "pause" -> "Paused by owner";
                case "resume" -> "Resumed by owner";
                default -> "Cancelled by owner";
            };
            mission = missions.update(
                    mission.id(), mission.sequence(), status, operation, reason
            ).orElse(mission);
        }

        if (orders != null && orders.owns(mission.id())) {
            try {
                if (operation.equals("pause")) orders.pauseByOwner();
                else orders.missionUpdated(mission);
            }
            catch (IOException failure) { error(player, "Queue lifecycle could not be saved: " + failure.getMessage()); return; }
        }

        BridgeCommand command = BridgeCommand.lifecycle(mission, operation, player.getName());
        BridgeServer.Dispatch dispatch = bridge.submit(command);
        if (!dispatch.accepted()) {
            error(player, "Mission journal updated, but control delivery failed: " + dispatch.message());
            return;
        }
        rememberRequester(command);
        if (operation.equals("cancel")) {
            if (mission.id().equals(referenceCurrentMissionId)) targetTracker.clear();
        } else {
            String tracked = mission.args().has("player") ? mission.args().get("player").getAsString() : null;
            if (tracked != null) {
                targetTracker.track(command, tracked);
            }
        }
        String delivery = dispatch.delivered() ? "sent" : "saved for reconnect";
        success(player, operation + " " + delivery + " for mission #" + mission.sequence()
                + " [" + mission.shortId() + "].");
    }

    static boolean invalidExplicitMissionReference(String[] args, Mission mission) {
        return args.length == 2 && !args[1].equalsIgnoreCase("current") && mission == null;
    }

    private void stop(Player player, String[] args) {
        retireBlueprintResume();
        if (args.length != 1) {
            error(player, "Usage: /e stop");
            return;
        }
        pendingSocialReaction = null;

        // Stop still reaches the live actuator if persistence reports a failure.
        // OrderedJobQueue.cancel also fences its in-memory generation on that failure.
        replaceQueue(player, "Stopped by owner");
        finiteTransient = null;

        Mission current = missions.current();
        Mission stopped = null;
        if (current != null && !current.terminal()) {
            stopped = missions.update(
                    current.id(),
                    current.sequence(),
                    MissionStatus.CANCELLED,
                    "stop",
                    "Stopped by owner"
            ).orElse(current);
        }

        BridgeCommand command = BridgeCommand.stop(stopped, player.getName());
        BridgeServer.Dispatch dispatch = bridge.submit(command);
        targetTracker.clear();
        if (!dispatch.accepted()) {
            error(player, stopped == null
                    ? "Live stop delivery failed: " + dispatch.message()
                    : "Mission journal stopped, but live stop delivery failed: "
                            + dispatch.message());
            return;
        }
        transientRequesters.put(command.id(), player.getName());
        while (transientRequesters.size() > 512) {
            transientRequesters.remove(transientRequesters.keySet().iterator().next());
        }
        String delivery = dispatch.delivered() ? "sent" : "queued until Entity connects";
        success(player, "Global stop " + delivery
                + "; current work and queue cancelled; automatic Idle OFF; Home/background movement included ["
                + shortId(command.id()) + "].");
    }

    private void why(Player player, String[] args) {
        if (args.length > 2) {
            error(player, "Usage: /e why [mission id]");
            return;
        }
        String reference = args.length == 2 ? args[1] : "current";
        Mission mission = missions.resolve(reference).orElse(null);
        if (mission == null) {
            if (args.length == 2 && !reference.equalsIgnoreCase("current")) {
                error(player, "No unique mission matches '" + reference + "'. Use /e missions.");
                return;
            }
            if (orders != null && orders.engaged()) info(player, orders.describe());
            status(player);
            dispatchTransient(player, "why", "query.why", new JsonObject());
            return;
        }
        info(player, describeMission(mission));
        if (mission.step() != null) {
            info(player, "Step: " + mission.step());
        }
        if (mission.reason() != null) {
            info(player, "Why: " + mission.reason());
        }
        TelemetrySnapshot telemetry = bridge.latestTelemetry();
        if (telemetry != null && telemetry.mission() != null
                && mission.id().equals(telemetry.mission().id())) {
            if (telemetry.mission().step() != null) {
                info(player, "Entity reports: " + telemetry.mission().step());
            }
            if (telemetry.mission().reason() != null) {
                info(player, "Entity reason: " + telemetry.mission().reason());
            }
            if (telemetry.baritone() != null && telemetry.baritone().failure() != null) {
                info(player, "Baritone: " + telemetry.baritone().failure());
            }
            if (telemetry.survival() != null && telemetry.survival().action() != null) {
                info(player, "Survival override: " + telemetry.survival().action());
            }
        }
        JsonObject query = new JsonObject();
        query.addProperty("missionId", mission.id());
        query.addProperty("sequence", mission.sequence());
        dispatchTransient(player, "why", "query.why", query);
    }

    private void listMissions(Player player, String[] args) {
        int page = 1;
        try {
            if (args.length > 2) throw new IllegalArgumentException();
            if (args.length == 2) page = Integer.parseInt(args[1]);
            if (page < 1) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            error(player, "Usage: /e missions [page >= 1]");
            return;
        }
        List<Mission> all = missions.snapshot();
        if (all.isEmpty()) {
            info(player, "No missions have been created yet.");
            return;
        }
        int pages = (all.size() + 9) / 10;
        if (page > pages) { error(player, "No page " + page + "; use /e missions 1.." + pages); return; }
        info(player, "Missions page " + page + "/" + pages + " (newest first; use #number or short id with pause/resume/retry/cancel/why):");
        int shown = 0;
        for (int index = all.size() - 1 - (page - 1) * 10; index >= 0 && shown < 10; index--, shown++) {
            info(player, describeMission(all.get(index)));
        }
        if (page < pages) info(player, "Older missions: /e missions " + (page + 1));
    }

    private void capabilities(Player player, String[] args) {
        if (args.length == 1) {
            info(player, "Entity can travel/follow, acquire/craft/deliver, equip/defend, maintain Home, farm and build confirmed blueprints.");
            info(player, "Automatic work is opt-in (/e idle). /e help <topic> lists actual commands; /e capabilities techniques shows live fall-technique readiness.");
            return;
        }
        if (args.length != 2 || !args[1].equalsIgnoreCase("techniques")) {
            error(player, "Usage: /e capabilities [techniques]");
            return;
        }
        info(player, "Inventory-aware fall techniques (latest client observation):");
        CapabilityStatusRenderer.render(bridge.latestCapabilities())
                .forEach(line -> info(player, line));
    }

    private void mode(Player player, String[] args) {
        if (args.length > 2 || args.length == 2 && !Set.of("player", "oracle").contains(args[1].toLowerCase(Locale.ROOT))) {
            error(player, "Resource information is configured with /e perception normal|legit|status.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("player")) missions.setMode("player");
        info(player, "Entity uses the player runtime. Oracle is not implemented and cannot be enabled. Use /e perception normal|legit|status for resource information.");
    }

    private void status(Player player) {
        status(player, false);
    }

    private void status(Player player, boolean debug) {
        if (!debug) {
            TelemetrySnapshot live = bridge.latestTelemetry();
            info(player, !bridge.isConnected() ? "Entity is offline; start its client to execute work."
                    : "Entity is connected" + (live == null ? "; waiting for its first observation." : ": " + live.state() + "."));
            Mission current = missions.current();
            if (current != null) info(player, describeMission(current));
            if (orders != null && orders.engaged()) info(player, orders.describe());
            if (live != null) {
                String activity = firstPresent(live.message(), current == null ? "No foreground mission." : current.reason(), "Waiting.");
                info(player, activity.length() <= 320 ? activity : activity.substring(0, 317) + "...");
                info(player, "Health " + live.health() + "; food " + live.food() + "; observation "
                        + Math.max(0L, (System.currentTimeMillis() - live.receivedAt()) / 1000L) + "s old.");
            }
            info(player, "/e why explains work; /e status debug shows build/path diagnostics; /e help lists commands.");
            return;
        }
        String connection = bridge.isConnected()
                ? "connected (" + bridge.connectedClient() + ", protocol " + bridge.connectedProtocol() + ")"
                : "offline";
        info(player, "Bridge: " + connection + "; runtime=player"
                + "; pending frames=" + bridge.pendingCount() + ".");
        String pairedIdentity = bridge.connectedBuildIdentity() == null
                ? "none (no exact client pair connected)"
                : bridge.connectedBuildIdentity().display();
        info(player, "Build: server=" + bridge.buildIdentity().display()
                + "; client=" + pairedIdentity + ".");

        Mission current = missions.current();
        if (current == null) {
            info(player, "Mission: none active.");
        } else {
            info(player, describeMission(current));
            if (current.reason() != null) {
                info(player, "Mission reason: " + current.reason());
            }
        }

        TelemetrySnapshot telemetry = bridge.latestTelemetry();
        if (telemetry == null) {
            info(player, "Telemetry: none received yet.");
        } else {
            long age = Math.max(0L, (System.currentTimeMillis() - telemetry.receivedAt()) / 1_000L);
            StringBuilder line = new StringBuilder("Entity: state=").append(telemetry.state());
            if (telemetry.health() != null) {
                line.append(", health=").append(String.format(Locale.ROOT, "%.1f", telemetry.health()));
            }
            if (telemetry.food() != null) {
                line.append(", food=").append(telemetry.food());
            }
            if (telemetry.air() != null) {
                line.append(", air=").append(telemetry.air());
            }
            if (telemetry.shield() != null) {
                line.append(", shield=").append(telemetry.shield() ? "raised" : "down");
            }
            line.append(" (").append(age).append("s old)");
            info(player, line.toString());

            if (telemetry.position() != null) {
                TelemetrySnapshot.Position position = telemetry.position();
                info(player, String.format(
                        Locale.ROOT, "Position: %.1f %.1f %.1f in %s.",
                        position.x(), position.y(), position.z(), position.dimension()
                ));
            }
            if (telemetry.survival() != null) {
                TelemetrySnapshot.SurvivalInfo survival = telemetry.survival();
                info(player, "Survival: " + firstPresent(
                        survival.action(), survival.hazard(), survival.state(), "monitoring"
                ) + (Boolean.TRUE.equals(survival.emergency()) ? " (EMERGENCY)" : ""));
            }
            if (telemetry.baritone() != null) {
                TelemetrySnapshot.BaritoneInfo baritone = telemetry.baritone();
                info(player, "Baritone: " + firstPresent(
                        baritone.failure(), baritone.pathStatus(), baritone.goal(), baritone.state(), "idle"
                ));
            }
            if (telemetry.message() != null && !telemetry.message().isBlank()) {
                info(player, "Controller: " + telemetry.message());
            }
        }

        BridgeResult result = bridge.latestResult();
        if (result != null) {
            info(player, "Last result [" + shortId(result.id()) + "]: "
                    + (result.ok() ? "ok" : "blocked") + " - "
                    + (result.message() == null ? "no message" : result.message()));
        }
    }

    public void handleResult(BridgeResult result) {
        String requestedBy = result.terminal() ? transientRequesters.remove(result.id()) : transientRequesters.get(result.id());
        if (requestedBy == null && orders != null) requestedBy = orders.requester(result.id());
        if (requestedBy == null && result.missionId() != null) {
            Mission recorded = missions.resolve(result.missionId()).orElse(null);
            if (recorded != null) requestedBy = recorded.requestedBy();
        }
        if (finiteTransient != null && finiteTransient.id().equals(result.id()) && result.terminal()) finiteTransient = null;
        if (orders != null) {
            try { orders.result(result); }
            catch (IOException failure) { plugin.getLogger().severe("Queue result could not be saved: " + failure.getMessage()); }
        }
        publishIdleContext();
        CompanionFeedback.Owner aiOrigin=companionFeedback.owner("command:"+result.id());
        if(aiOrigin==null && result.missionId()!=null) aiOrigin=companionFeedback.owner("mission:"+result.missionId());
        Player recipient = requestedBy == null
                ? Bukkit.getPlayerExact(settings.ownerName())
                : Bukkit.getPlayerExact(requestedBy);
        // Functional handoff precedes presentation. The correlated saved-project reply is
        // consumed once, even when authorization fails; AI feedback cannot swallow it.
        if (result.terminal() && result.id().equals(latestBlueprintPreviewRequest)
                && result.id().equals(latestBlueprintResumeRequest) && pendingBlueprintResume != null) {
            JsonObject resume = pendingBlueprintResume;
            retireBlueprintResume();
            if (result.ok()) {
                if (aiOrigin != null) companionFeedback.resultNotice(result.id(), result.missionId(), true);
                try {
                    BlueprintPreview preview = result.blueprintPreview();
                    if (requestedBy == null || recipient == null || !recipient.isOnline()
                            || !settings.isPrimaryOwner(requestedBy) || !authorize(recipient, false)
                            || !requestedBy.equalsIgnoreCase(resume.get("owner").getAsString())
                            || preview == null || idleWorldId == null || !preview.worldId().equals(idleWorldId.toString())
                            || !recipient.getWorld().getKey().toString().equals(preview.dimension())
                            || aiOrigin != null && (!recipient.getUniqueId().equals(aiOrigin.player())
                                || recipient != aiRequestPlayers.get(aiOrigin.player())
                                || !recipient.getWorld().getUID().equals(aiOrigin.world())
                                || aiOrigin.session() == null || !aiOrigin.session().equals(bridge.connectedSessionId()))
                            || blueprintAuthority == null
                            || !blueprintAuthority.reconcileResume(requestedBy, preview,
                                resume.get("worldId").getAsString(), resume.get("dimension").getAsString(),
                                resume.get("policyRevision").getAsLong(), resume.get("policyDigest").getAsString()))
                        throw new IllegalArgumentException("Owner, world or property policy changed during build resume");
                    validateBlueprintPreview(recipient, preview);
                    resume.addProperty("projectId", preview.projectId()); resume.addProperty("digest", preview.digest());
                    dispatchMission(recipient, "build", resume, null, aiOrigin);
                } catch (IllegalArgumentException invalid) {
                    if (blueprintAuthority != null) blueprintAuthority.clear();
                    String message = "Building preview cannot be authorized: " + invalid.getMessage();
                    if (recipient != null) missionDispatchNotice(recipient, aiOrigin, false, message);
                }
                return;
            }
        }
        if(aiOrigin!=null) {
            JsonObject fact=new JsonObject();fact.addProperty("kind",
                    companionFeedback.readQuery("command:"+result.id()) ? "read_query_result" : "game_result");
            fact.addProperty("status",result.status());fact.addProperty("ok",result.ok());
            fact.addProperty("detail",companionText(result.message(),2000));
            // Durable mission transitions have one other canonical observer below.
            boolean announce=companionFeedback.resultNotice(result.id(),result.missionId(),result.terminal());
            observedCompanion(aiOrigin,fact,announce);
            return;
        }
        if (requestedBy == null && (recipient == null || !recipient.isOnline())) {
            recipient = Bukkit.getPlayerExact(settings.ownerName());
        }
        if (recipient == null || !recipient.isOnline()) {
            return;
        }
        if (result.ok() && result.blueprintCatalog() != null && requestedBy != null) {
            info(recipient, "Blueprints — click a name to prepare its preview command:");
            result.blueprintCatalog().choices().forEach(recipient::sendMessage);
        }
        if (result.ok() && result.blueprintPreview() != null && requestedBy != null
                && result.id().equals(latestBlueprintPreviewRequest) && !result.id().equals(latestBlueprintResumeRequest)) {
            BlueprintPreview preview = result.blueprintPreview();
            if (idleWorldId != null && preview.worldId().equals(idleWorldId.toString())
                    && recipient.getWorld().getKey().toString().equals(preview.dimension())) {
                try {
                    validateBlueprintPreview(recipient, preview);
                    if (blueprintAuthority != null && settings.isPrimaryOwner(requestedBy))
                        blueprintAuthority.preview(requestedBy, preview);
                    showBlueprintPreview(recipient, preview);
                } catch (IllegalArgumentException invalid) {
                    if (blueprintAuthority != null) blueprintAuthority.clear();
                    error(recipient, "Building preview cannot be authorized: " + invalid.getMessage());
                    return;
                }
            }
        }
        String message = result.message() == null || result.message().isBlank()
                ? (result.ok() ? "Command accepted." : "Command blocked.")
                : result.message();
        if (result.ok()) {
            success(recipient, message + " [" + shortId(result.id()) + "]");
        } else {
            error(recipient, message + " [" + shortId(result.id()) + "]"
                    + OwnerNoticePolicy.retainedFailureSuffix(result));
        }
    }

    private static void validateBlueprintPreview(Player player, BlueprintPreview preview) {
        if (preview.minY() < player.getWorld().getMinHeight() || preview.maxY() >= player.getWorld().getMaxHeight())
            throw new IllegalArgumentException("outside this dimension's build height");
        for (BlueprintPreview.Cell cell : preview.cells()) {
            Bukkit.createBlockData(cell.before());
            Bukkit.createBlockData(cell.state());
        }
    }

    private void showBlueprintPreview(Player player, BlueprintPreview preview) {
        new BukkitRunnable() {
            private int repetitions;
            @Override public void run() {
                if (!player.isOnline() || !player.getWorld().getKey().toString().equals(preview.dimension())
                        || repetitions++ >= 10) { cancel(); return; }
                int step = Math.max(1, Math.max(preview.maxX() - preview.minX(), preview.maxZ() - preview.minZ()) / 48);
                for (int x = preview.minX(); x <= preview.maxX() + 1; x += step) {
                    outline(x, preview.minZ()); outline(x, preview.maxZ() + 1);
                }
                for (int z = preview.minZ(); z <= preview.maxZ() + 1; z += step) {
                    outline(preview.minX(), z); outline(preview.maxX() + 1, z);
                }
                int vertical = Math.max(1, (preview.maxY() - preview.minY()) / 48);
                for (int y = preview.minY(); y <= preview.maxY() + 1; y += vertical) {
                    particle(preview.minX(), y, preview.minZ()); particle(preview.maxX() + 1, y, preview.minZ());
                    particle(preview.minX(), y, preview.maxZ() + 1); particle(preview.maxX() + 1, y, preview.maxZ() + 1);
                }
            }
            private void outline(int x, int z) {
                particle(x, preview.minY(), z); particle(x, preview.maxY() + 1, z);
            }
            private void particle(int x, int y, int z) {
                player.spawnParticle(Particle.END_ROD, new Location(player.getWorld(), x, y, z), 1, 0, 0, 0, 0);
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    /** Called on the Bukkit thread for client result, telemetry, and mission-update reconciliation. */
    public void handleMissionUpdated(Mission mission) {
        refreshDeliveryTracking(mission);
        if (orders != null) {
            try { orders.missionUpdated(mission); }
            catch (IOException failure) { plugin.getLogger().severe("Queue transition could not be saved: " + failure.getMessage()); }
        }
        publishIdleContext();
        OwnerNoticePolicy.Notice notice = ownerNotices.observe(mission).orElse(null);
        CompanionFeedback.Owner aiOrigin=companionFeedback.owner("mission:"+mission.id());
        if(aiOrigin!=null) {
            boolean significant=mission.terminal() || mission.status()==MissionStatus.BLOCKED;
            if(companionFeedback.missionNotice(mission.id(),mission.status().name(),mission.attempt(),significant))
                observedCompanion(aiOrigin,companionMission(mission),true);
            return;
        }
        if (notice == null) {
            return;
        }
        Player owner = Bukkit.getPlayerExact(settings.ownerName());
        if (owner == null || !owner.isOnline()) {
            return;
        }
        if (notice.error()) {
            error(owner, notice.message());
        } else {
            info(owner, notice.message());
        }
    }

    /** Re-arms Paper waypoints after the durable journal is restored at startup. */
    public void restoreTargetTracking() {
        Mission current = missions.current();
        if (current != null) {
            targetTracker.trackRestoredDelivery(current);
        }
    }

    private void refreshDeliveryTracking(Mission updated) {
        if (updated.terminal()) {
            targetTracker.clearMission(updated.id());
        }
        Mission current = missions.current();
        if (current != null) {
            targetTracker.trackRestoredDelivery(current);
        }
    }

    private static String describeMission(Mission mission) {
        return "#" + mission.sequence() + " " + mission.action()
                + batchObjective(mission.args()) + " "
                + mission.status().name().toLowerCase(Locale.ROOT)
                + " attempt " + mission.attempt() + " [" + mission.shortId() + "]";
    }

    /** Compact durable objective shown by status, missions, and why. */
    private static String objectiveDescription(String action, JsonObject args) {
        String batch = batchObjective(args);
        String items = !batch.isEmpty() ? batch : args.has("item")
                ? (args.has("all") && args.get("all").getAsBoolean() ? "all carried surplus of "
                    : (args.has("count") ? args.get("count").getAsString() : "1") + " ")
                    + simpleIdentifier(args.get("item").getAsString()).replace('_', ' ') : "";
        String recipient = args.has("player") ? args.get("player").getAsString() : "you";
        String override = args.has("useReserves") && args.get("useReserves").getAsBoolean()
                ? " (personal supplies explicitly allowed)" : "";
        return switch (action) {
            case "get" -> "obtain and keep " + items;
            case "bring" -> "obtain and deliver " + items + " to " + recipient + override;
            case "give" -> "hand over " + items + " to " + recipient + override;
            case "gear" -> "obtain own " + args.get("tier").getAsString() + " kit";
            case "come" -> "come to you once";
            case "follow" -> "keep following " + recipient;
            default -> action;
        };
    }

    private static String batchObjective(JsonObject args) {
        JsonElement value = args.get("items");
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() < 2) {
            return "";
        }
        ArrayList<String> entries = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonElement id = item.get("item");
            JsonElement count = item.get("count");
            if (id == null || count == null || !id.isJsonPrimitive() || !count.isJsonPrimitive()) {
                continue;
            }
            String label = simpleIdentifier(id.getAsString()).replace('_', ' ');
            entries.add(label + " x" + count.getAsString());
        }
        if (entries.size() < 2) return "";
        String recipient = args.has("player") && args.get("player").isJsonPrimitive()
                ? " -> " + args.get("player").getAsString()
                : "";
        return " {" + String.join(", ", entries) + recipient + "}";
    }

    private static String singularAlias(String query) {
        return switch (query) {
            case "diamonds" -> "diamond";
            case "torches" -> "torch";
            case "arrows" -> "arrow";
            case "pickaxes" -> "pickaxe";
            case "swords" -> "sword";
            default -> query;
        };
    }

    private String normalizeItem(String query) {
        String simple = singularAlias(query);
        if (simple.equals("food") || simple.equals("meal") || simple.equals("meals")) {
            return "food";
        }
        simple = switch (simple) {
            case "cobble" -> "cobblestone";
            case "log", "logs", "tree", "trees", "wood" -> "oak_log";
            case "plank", "planks" -> "oak_planks";
            case "iron" -> "iron_ingot";
            case "gold" -> "gold_ingot";
            case "copper" -> "copper_ingot";
            case "lapis" -> "lapis_lazuli";
            case "debris" -> "ancient_debris";
            case "netherite" -> "netherite_ingot";
            default -> simple;
        };
        Material material = materialsBySimpleName.get(simple);
        return material != null && material.isItem() && !material.isAir()
                ? material.getKey().toString()
                : null;
    }

    private String normalizeExactItem(String simple) {
        Material material = materialsBySimpleName.get(simple);
        return material != null && material.isItem() && !material.isAir() ? material.getKey().toString() : null;
    }

    private static String normalizePlayer(String value, Player owner) {
        if (value.equalsIgnoreCase("me") || value.equalsIgnoreCase("owner")) {
            return owner.getName();
        }
        if (!PLAYER_NAME.matcher(value).matches()) {
            return null;
        }
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.equalsIgnoreCase(value))
                .findFirst()
                .orElse(value);
    }

    private static String simpleIdentifier(String namespaced) {
        return namespaced.startsWith("minecraft:")
                ? namespaced.substring("minecraft:".length())
                : namespaced;
    }

    private static Map<String, List<String>> buildFriendlyBlockGroups() {
        Map<String, String> available = new LinkedHashMap<>();
        for (Material material : Material.values()) {
            if (material.isBlock() && !material.isAir()) {
                available.put(material.getKey().getKey(), material.getKey().toString());
            }
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        LinkedHashSet<String> everyWood = new LinkedHashSet<>();
        for (String species : WOOD_SPECIES) {
            List<String> candidates = switch (species) {
                case "bamboo" -> existing(available, "bamboo_block");
                case "crimson", "warped" -> existing(available, species + "_stem", species + "_hyphae");
                default -> existing(available, species + "_log", species + "_wood");
            };
            if (!candidates.isEmpty()) {
                groups.put(species, candidates);
                everyWood.addAll(candidates);
            }
        }
        List<String> wood = List.copyOf(everyWood);
        for (String alias : List.of("wood", "log", "logs", "tree", "trees")) {
            groups.put(alias, wood);
        }
        List<String> leaves = available.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("_leaves"))
                .map(Map.Entry::getValue).sorted().toList();
        groups.put("leaf", leaves);
        groups.put("leaves", leaves);
        List<String> ores = available.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("_ore"))
                .map(Map.Entry::getValue).sorted().toList();
        groups.put("ore", ores);
        groups.put("ores", ores);
        for (String ore : ORE_TYPES) {
            List<String> candidates = available.entrySet().stream()
                    .filter(entry -> entry.getKey().equals(ore + "_ore")
                            || entry.getKey().equals("deepslate_" + ore + "_ore")
                            || entry.getKey().equals("nether_" + ore + "_ore"))
                    .map(Map.Entry::getValue).sorted().toList();
            if (!candidates.isEmpty()) {
                groups.put(ore, candidates);
            }
        }
        groups.put("cobble", existing(available, "cobblestone"));
        groups.put("debris", existing(available, "ancient_debris"));
        groups.put("netherite", existing(available, "ancient_debris"));
        groups.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return Map.copyOf(groups);
    }

    private static List<String> existing(Map<String, String> available, String... keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            String namespaced = available.get(key);
            if (namespaced != null) {
                result.add(namespaced);
            }
        }
        return List.copyOf(result);
    }

    private boolean authorize(Player player, boolean explain) {
        TrustedIdentityRegistry.Snapshot access = trustedIdentities.snapshot();
        ControllerAccessPolicy.Decision decision = ControllerAccessPolicy.evaluate(
                settings,
                player.getName(),
                player.hasPermission(settings.requiredPermission()),
                access.controllers(),
                access.controllersLocked());
        if (decision == ControllerAccessPolicy.Decision.ALLOWED) return true;
        if (!explain) return false;
        switch (decision) {
            case OWNER_NOT_CONFIGURED -> error(player, "EntityBridge owner-name is not configured.");
            case OWNER_PERMISSION_REQUIRED -> error(
                    player, "You also need permission " + settings.requiredPermission() + ".");
            case CONTROLLERS_LOCKED -> error(
                    player, "Entity controller access is currently locked by the owner.");
            case NOT_TRUSTED -> error(
                    player, "Only the configured owner or a trusted controller can control Entity.");
            case ALLOWED -> throw new IllegalStateException("Allowed access was handled above");
        }
        return false;
    }

    private void help(Player player) {
        help(player, "");
    }

    private void help(Player player, String topic) {
        (topic.isBlank() ? CommandHelp.overview() : CommandHelp.page(topic)).forEach(line -> info(player, line));
        if (topic.isBlank()) {
            Component choices = Component.text("Help: ", NamedTextColor.GRAY);
            for (String name : CommandHelp.TOPICS) choices = choices.append(Component.text("[" + name + "] ", NamedTextColor.AQUA)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/e help " + name)));
            player.sendMessage(choices);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !authorize(player, false)) {
            return List.of();
        }
        if (args.length == 1) {
            return complete(SUBCOMMANDS, args[0], 100);
        }
        String action = CommandInput.canonicalAction(args[0]);
        if (action.equals("help") && args.length == 2) return complete(CommandHelp.TOPICS, args[1], 30);
        if (action.equals("ai") && args.length == 2) return complete(List.of("on", "off", "status"), args[1], 3);
        if (action.equals("personality") && args.length == 2)
            return complete(List.of("show", "set", "custom", "reset"), args[1], 4);
        if (action.equals("personality") && args.length == 3 && args[1].equalsIgnoreCase("set"))
            return complete(List.of("owner", "chaotic", "friendly", "neutral"), args[2], 3);
        if (action.equals("status") && args.length == 2) return complete(List.of("debug"), args[1], 1);
        if (action.equals("capabilities") && args.length == 2) return complete(List.of("techniques"), args[1], 1);
        if (action.equals("queue") && args.length == 2) {
            return complete(List.of("add", "status", "retry", "resume", "skip", "clear", "cancel", "get", "come", "bring", "give", "mine", "gear", "goto", "home", "stock"), args[1], 30);
        }
        if (action.equals("queue") && args.length > 2) {
            String[] segment = CommandPresentation.queueCompletionSegment(args);
            if (segment.length == 1) return CommandPresentation.queueCompletionReplacements(args,
                    complete(List.of("get", "come", "bring", "give", "mine", "gear", "goto", "home", "stock"), segment[0], 20));
            if (!Set.of("get", "come", "bring", "give", "mine", "gear", "goto", "go", "home", "stock", "fetch", "collect").contains(segment[0].toLowerCase(Locale.ROOT))) return List.of();
            if (segment[0].equalsIgnoreCase("home") && segment.length == 2) return complete(List.of("go"), segment[1], 1);
            if (segment[0].equalsIgnoreCase("stock") && segment.length == 2) return complete(List.of("run"), segment[1], 1);
            return onTabComplete(sender, command, alias, segment);
        }
        if (action.equals("protect") && args.length == 2) {
            return complete(List.of("self", "me", "status"), args[1], 10);
        }
        if (action.equals("access") && args.length == 2 && settings.isPrimaryOwner(player.getName())) {
            return complete(List.of("list", "allow", "deny", "lock", "unlock"), args[1], 10);
        }
        if (action.equals("access") && args.length == 3 && settings.isPrimaryOwner(player.getName())) {
            List<String> targets = new ArrayList<>();
            Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> !settings.isPrimaryOwner(name))
                    .sorted(String.CASE_INSENSITIVE_ORDER).forEach(targets::add);
            return complete(targets, args[2], 100);
        }
        if (action.equals("area") && args.length == 2) {
            List<String> operations = settings.isPrimaryOwner(player.getName())
                    ? List.of("begin", "finish", "confirm", "cancel", "list", "show", "remove")
                    : List.of("list", "show");
            return complete(operations, args[1], 10);
        }
        if (action.equals("area") && args.length == 3
                && args[1].equalsIgnoreCase("begin")
                && settings.isPrimaryOwner(player.getName())) {
            return complete(List.of("protected", "mining", "harvesting"), args[2], 10);
        }
        if (action.equals("area") && args.length == 3
                && (args[1].equalsIgnoreCase("show")
                || args[1].equalsIgnoreCase("remove")
                && settings.isPrimaryOwner(player.getName()))) {
            return complete(protectedAreas.snapshot().areas().stream()
                    .map(ProtectedAreaPolicy.Area::name).toList(), args[2], 100);
        }
        if (action.equals("area") && args.length == 4
                && args[1].equalsIgnoreCase("begin")
                && settings.isPrimaryOwner(player.getName())) {
            List<String> names = switch (args[2].toLowerCase(Locale.ROOT)) {
                case "protected" -> List.of("house", "base", "farm");
                case "mining" -> List.of("mine", "quarry", "cave");
                case "harvesting" -> List.of("field", "forest", "orchard");
                default -> List.of();
            };
            return complete(names, args[3], 10);
        }
        if (action.equals("area") && args.length == 4
                && args[1].equalsIgnoreCase("remove")
                && settings.isPrimaryOwner(player.getName())) {
            return complete(List.of("confirm"), args[3], 10);
        }
        if (action.equals("protect") && args.length == 3) {
            return complete(List.of("on", "off", "status"), args[2], 10);
        }
        if (action.equals("follow") && args.length == 2) {
            List<String> targets = new ArrayList<>(List.of("me"));
            Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER).forEach(targets::add);
            return complete(targets, args[1], 100);
        }
        if (action.equals("inventory") && args.length == 2) {
            return complete(itemSuggestions, args[1], 100);
        }
        if (action.equals("attack") && args.length == 2) {
            List<String> targets = new ArrayList<>(MOB_SUGGESTIONS);
            Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER).forEach(targets::add);
            return complete(targets, args[1], 100);
        }
        if (action.equals("combat") && args.length == 2) {
            return complete(List.of("avoid", "defensive", "aggressive", "status"), args[1], 10);
        }
        if (action.equals("mine") && args.length == 2) {
            return complete(blockSuggestions, args[1], 100);
        }
        if (action.equals("get") && args.length == 2) {
            List<String> obtainable = new ArrayList<>(itemSuggestions);
            obtainable.add("food");
            return complete(obtainable, args[1], 100);
        }
        if (action.equals("get") && args.length >= 3
                && args[args.length - 2].matches("[1-9]\\d*")) {
            List<String> obtainable = new ArrayList<>(itemSuggestions);
            obtainable.add("food");
            return complete(obtainable, args[args.length - 1], 100);
        }
        if (Set.of("mine", "get").contains(action) && args.length >= 3) {
            return complete(List.of("1", "8", "16", "32", "64", "256", "1024", "4096"),
                    args[args.length - 1], 10);
        }
        if (Set.of("give", "bring").contains(action)) {
            if (args[args.length - 1].startsWith("--"))
                return complete(List.of("--use-reserves"), args[args.length - 1], 1);
            if (args.length == 2) {
                List<String> candidates = new ArrayList<>(List.of("me"));
                Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .sorted(String.CASE_INSENSITIVE_ORDER).forEach(candidates::add);
                candidates.addAll(itemSuggestions);
                return complete(candidates, args[1], 100);
            }
            boolean firstIsItem = normalizeItem(args[1]) != null;
            if (args.length >= 3 && args[args.length - 2].matches("[1-9]\\d*")) {
                return complete(itemSuggestions, args[args.length - 1], 100);
            }
            if (!firstIsItem && args.length == 3) {
                return complete(itemSuggestions, args[2], 100);
            }
            if ((firstIsItem && args.length >= 3) || (!firstIsItem && args.length >= 4)) {
                List<String> counts = action.equals("give")
                        ? List.of("all", "1", "8", "16", "32", "64", "256", "1024", "4096")
                        : List.of("1", "8", "16", "32", "64", "256", "1024", "4096");
                return complete(counts,
                        args[args.length - 1], 10);
            }
        }
        if (action.equals("gear") && args.length == 2) {
            List<String> modes = new ArrayList<>();
            modes.addAll(GEAR_TIERS);
            modes.add("status");
            return complete(modes, args[1], 10);
        }
        if (action.equals("stock") && args.length == 2) {
            return complete(List.of("on", "off", "run", "status"), args[1], 10);
        }
        if (action.equals("farm") && args.length == 2) {
            return complete(List.of("run", "auto", "status"), args[1], 10);
        }
        if (action.equals("build") && args.length == 2) {
            return complete(CommandInput.BUILD_OPERATIONS, args[1], 20);
        }
        if (action.equals("build") && args.length == 3) {
            if (args[1].equalsIgnoreCase("select")) return bridge.blueprintCatalog().complete(args[2]);
            if (args[1].equalsIgnoreCase("rotate")) return complete(List.of("90", "180", "270"), args[2], 3);
            if (args[1].equalsIgnoreCase("confirm")) return complete(List.of("gather"), args[2], 1);
            if (args[1].equalsIgnoreCase("clear")) return complete(List.of("confirm"), args[2], 1);
        }
        if (action.equals("farm") && args.length == 3) {
            return complete(protectedAreas.snapshot()
                    .areasOfKind(ProtectedAreaPolicy.AreaKind.HARVESTING).stream()
                    .map(ProtectedAreaPolicy.Area::name).toList(), args[2], 100);
        }
        if (action.equals("farm") && args.length == 4
                && args[1].equalsIgnoreCase("auto")) {
            return complete(List.of("on", "off"), args[3], 10);
        }
        if (action.equals("tidy") && args.length == 2) {
            return complete(CommandInput.TIDY_OPERATIONS, args[1], 10);
        }
        if (action.equals("tidy") && args.length == 3) {
            if (args[1].equalsIgnoreCase("auto")) return complete(List.of("on", "off"), args[2], 10);
            if (args[1].equalsIgnoreCase("spot")) return complete(List.of("confirm"), args[2], 10);
            if (args[1].equalsIgnoreCase("keep") || args[1].equalsIgnoreCase("junk")) {
                return complete(itemSuggestions, args[2], 100);
            }
        }
        if (action.equals("home") && args.length == 2) {
            return complete(List.of("set", "adopt", "setup", "status", "show", "go", "sleep", "clear", "protect", "bind", "storage"), args[1], 16);
        }
        if (action.equals("home") && args.length == 3 && args[1].equalsIgnoreCase("bind")) {
            return complete(List.of("table", "furnace", "chest", "bed", "confirm"), args[2], 10);
        }
        if (action.equals("home") && args.length == 3 && args[1].equalsIgnoreCase("storage")) {
            return complete(CommandInput.HOME_STORAGE_OPERATIONS, args[2], 10);
        }
        if (action.equals("home") && args.length == 3 && args[1].equalsIgnoreCase("protect")) {
            return complete(List.of("begin", "finish", "confirm", "cancel", "show", "remove"), args[2], 10);
        }
        if (action.equals("home") && args.length == 4 && args[1].equalsIgnoreCase("protect")
                && (args[2].equalsIgnoreCase("show") || args[2].equalsIgnoreCase("remove"))) {
            return complete(protectedAreas.snapshot().areasOfKind(ProtectedAreaPolicy.AreaKind.ENTITY_BASE)
                    .stream().map(ProtectedAreaPolicy.Area::name).toList(), args[3], 20);
        }
        if (action.equals("home") && args.length == 5 && args[1].equalsIgnoreCase("protect")
                && args[2].equalsIgnoreCase("remove")) return complete(List.of("confirm"), args[4], 10);
        if (action.equals("home") && args.length == 3
                && (args[1].equalsIgnoreCase("set")
                || args[1].equalsIgnoreCase("adopt") || args[1].equalsIgnoreCase("clear"))) {
            return complete(List.of("confirm"), args[2], 10);
        }
        if (action.equals("visuals") && args.length == 2) {
            return complete(List.of("on", "off", "status"), args[1], 10);
        }
        if (action.equals("perception") && args.length == 2) {
            return complete(List.of("normal", "legit", "status"), args[1], 10);
        }
        if (action.equals("idle") && args.length == 2)
            return complete(List.of("on", "off", "status", "delay", "offline", "target"), args[1], 10);
        if (action.equals("idle") && args.length == 3) {
            if (args[1].equalsIgnoreCase("offline")) return complete(List.of("on", "off"), args[2], 10);
            if (args[1].equalsIgnoreCase("target")) return complete(List.of("food", "fuel", "logs", "iron"), args[2], 10);
            if (args[1].equalsIgnoreCase("delay")) return complete(List.of("5", "1", "10", "30"), args[2], 10);
        }
        if (Set.of("pause", "resume", "retry", "cancel", "why").contains(action) && args.length == 2) {
            List<String> ids = missions.snapshot().stream().map(Mission::shortId).toList();
            return complete(ids, args[1], 100);
        }
        if (action.equals("mode") && args.length == 2) {
            return complete(List.of("player"), args[1], 1);
        }
        if (action.equals("goto") && args.length == 2) {
            List<String> targets = new ArrayList<>(List.of("me"));
            Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER).forEach(targets::add);
            targets.add(Integer.toString(player.getLocation().getBlockX()));
            return complete(targets, args[1], 100);
        }
        if (action.equals("goto") && args.length >= 3 && args.length <= 4) {
            int value = switch (args.length - 2) {
                case 1 -> player.getLocation().getBlockY();
                default -> player.getLocation().getBlockZ();
            };
            return complete(List.of(Integer.toString(value)), args[args.length - 1], 10);
        }
        return List.of();
    }

    private static List<String> complete(List<String> candidates, String prefix, int limit) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                matches.add(candidate);
                if (matches.size() >= limit) {
                    break;
                }
            }
        }
        return matches;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown";
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private void success(CommandSender sender, String message) {
        send(sender, message, NamedTextColor.GREEN);
    }

    private void info(CommandSender sender, String message) {
        send(sender, message, NamedTextColor.GRAY);
    }

    private void error(CommandSender sender, String message) {
        if(aiDispatchOwner!=null) aiDispatchError=true;
        send(sender, message, NamedTextColor.RED);
    }

    private void send(CommandSender sender, String message, NamedTextColor color) {
        if(aiDispatchOwner!=null && sender instanceof Player player && player.getUniqueId().equals(aiDispatchOwner.player())) {
            if(aiDispatchNotices.size()<12) aiDispatchNotices.add(message);
            return;
        }
        sender.sendMessage(Component.text("[Entity] ", NamedTextColor.AQUA)
                .append(Component.text(message, color)));
    }
}
