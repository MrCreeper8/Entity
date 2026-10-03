package dev.entitybridge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.entity.core.build.BuildIdentity;
import dev.entitybridge.config.BridgeSettings;
import dev.entitybridge.delivery.DeliveryTransactionRegistry;
import dev.entitybridge.delivery.DeliveryCommitRequest;
import dev.entitybridge.delivery.DeliveryCommitResult;
import dev.entitybridge.mission.Mission;
import dev.entitybridge.mission.MissionRegistry;
import dev.entitybridge.mission.MissionStatus;
import dev.entitybridge.recipe.RecipeCatalogSnapshot;
import dev.entitybridge.safety.EntityRelationshipProtocol;
import dev.entitybridge.recipe.RecipeDiscoveryResult;
import dev.entitybridge.stewardship.ProtectedAreaProtocol;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BridgeServer implements AutoCloseable {
    public static final int LEGACY_PROTOCOL_VERSION = 1;
    public static final int PROTOCOL_VERSION = 2;

    private final Logger logger;
    private final BuildIdentity buildIdentity;
    private final BridgeSettings settings;
    private final BridgeListener listener;
    private final MissionRegistry missions;
    private final DeliveryTransactionRegistry deliveries;
    private final PendingCommandQueue pendingCommands;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<ClientSession> activeSession = new AtomicReference<>();
    private final AtomicReference<TelemetrySnapshot> telemetry = new AtomicReference<>();
    private final AtomicReference<BridgeResult> latestResult = new AtomicReference<>();
    private final AtomicReference<JsonObject> capabilities = new AtomicReference<>();
    private final AtomicReference<dev.entitybridge.blueprint.BlueprintCatalogView> blueprintCatalog =
            new AtomicReference<>(dev.entitybridge.blueprint.BlueprintCatalogView.EMPTY);
    private final AtomicReference<RecipeCatalogSnapshot> recipeCatalog = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> worldIdentities =
            new AtomicReference<>(Map.of());
    private final ExecutorService clientExecutor;
    private final Object dispatchLock = new Object();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    public BridgeServer(Logger logger, BridgeSettings settings, BridgeListener listener) {
        this(logger, settings, listener, new MissionRegistry(settings.defaultMode()),
                new DeliveryTransactionRegistry());
    }

    public BridgeServer(
            Logger logger,
            BridgeSettings settings,
            BridgeListener listener,
            MissionRegistry missions
    ) {
        this(logger, settings, listener, missions, new DeliveryTransactionRegistry());
    }

    public BridgeServer(
            Logger logger,
            BridgeSettings settings,
            BridgeListener listener,
            MissionRegistry missions,
            DeliveryTransactionRegistry deliveries
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.buildIdentity = BuildIdentity.current();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.missions = Objects.requireNonNull(missions, "missions");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.pendingCommands = new PendingCommandQueue(
                settings.pendingCommandLimit(),
                settings.pendingCommandTtlMillis()
        );
        this.clientExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory("EntityBridge-client-"));
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        InetAddress address = InetAddress.getByName(settings.host());
        if (!address.isLoopbackAddress()) {
            throw new IOException("Refusing non-loopback bridge address: " + address.getHostAddress());
        }
        ServerSocket listenerSocket = new ServerSocket();
        try {
            listenerSocket.setReuseAddress(true);
            listenerSocket.bind(new InetSocketAddress(address, settings.port()), 4);
        } catch (IOException exception) {
            try {
                listenerSocket.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
        serverSocket = listenerSocket;
        running.set(true);

        Thread thread = new Thread(this::acceptLoop, "EntityBridge-accept");
        thread.setDaemon(true);
        acceptThread = thread;
        thread.start();
    }

    /** Installs main-thread-observed Bukkit world UUIDs for exact client route scoping. */
    public void installWorldIdentities(Map<String, String> identities) {
        Objects.requireNonNull(identities, "identities");
        LinkedHashMap<String, String> checked = new LinkedHashMap<>();
        identities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String key = Objects.requireNonNullElse(entry.getKey(), "").trim();
                    String identity = Objects.requireNonNullElse(entry.getValue(), "").trim();
                    if (key.isEmpty() || identity.isEmpty()
                            || key.length() > 256 || identity.length() > 128) {
                        throw new IllegalArgumentException("invalid world identity entry");
                    }
                    checked.put(key, identity);
                });
        worldIdentities.set(Map.copyOf(checked));
    }

    public Dispatch submit(BridgeCommand command) {
        ClientSession session;
        boolean delivered;
        synchronized (dispatchLock) {
            boolean cancellation = isCancellation(command);
            if (cancellation && command.missionId() != null) {
                pendingCommands.removeMission(command.missionId());
            }
            if (!pendingCommands.offer(command)) {
                return new Dispatch(false, false, "Pending command queue is full");
            }
            session = activeSession.get();
            delivered = session != null && (cancellation
                    ? session.enqueueStop(command.toFrame(session.protocol))
                    : session.enqueue(command.toFrame(session.protocol)));
        }
        if (session != null && !delivered) {
            disconnect(session);
        }
        return new Dispatch(true, delivered, delivered ? "sent" : "queued until Entity connects");
    }

    static boolean isCancellation(BridgeCommand command) {
        return "stop".equals(command.action())
                || "cancel".equals(command.action())
                || "mission.cancel".equals(command.operation());
    }

    /**
     * Atomically preserves transport ordering for primary-mission preemption:
     * the prior mission cancellation is always queued before the replacement
     * submit. Cancellation is intentional because the current Entity 2 client
     * keeps a paused mission in its active slot.
     */
    public SupersessionDispatch submitSuperseding(
            BridgeCommand cancelPrior,
            BridgeCommand submitReplacement
    ) {
        Objects.requireNonNull(cancelPrior, "cancelPrior");
        Objects.requireNonNull(submitReplacement, "submitReplacement");
        if (!"mission.cancel".equals(cancelPrior.operation())) {
            throw new IllegalArgumentException("cancelPrior must be a mission.cancel operation");
        }
        if (!"mission.submit".equals(submitReplacement.operation())) {
            throw new IllegalArgumentException("submitReplacement must be a mission.submit operation");
        }

        ClientSession session;
        boolean delivered;
        synchronized (dispatchLock) {
            if (!pendingCommands.offerAll(List.of(cancelPrior, submitReplacement))) {
                Dispatch rejected = new Dispatch(false, false, "Pending command queue has fewer than two free slots");
                return new SupersessionDispatch(rejected, rejected);
            }
            session = activeSession.get();
            delivered = session != null && session.enqueueBatch(List.of(
                    cancelPrior.toFrame(session.protocol),
                    submitReplacement.toFrame(session.protocol)
            ));
        }
        if (session != null && !delivered) {
            disconnect(session);
        }
        Dispatch dispatch = new Dispatch(
                true,
                delivered,
                delivered ? "preempted and sent" : "preemption queued until Entity connects"
        );
        return new SupersessionDispatch(dispatch, dispatch);
    }

    public boolean sendTargetUpdate(JsonObject frame) {
        ClientSession session = activeSession.get();
        return session != null && session.replaceTargetUpdate(frame);
    }

    /** Sends one transient server-originated frame without adding it to the command replay queue. */
    public boolean sendFrame(JsonObject frame) {
        Objects.requireNonNull(frame, "frame");
        ClientSession session;
        boolean delivered;
        synchronized (dispatchLock) {
            session = activeSession.get();
            delivered = session != null && session.enqueue(frame);
        }
        if (session != null && !delivered) {
            disconnect(session);
        }
        return delivered;
    }

    public boolean isConnected() {
        return activeSession.get() != null;
    }

    public String connectedClient() {
        ClientSession session = activeSession.get();
        return session == null ? null : session.client;
    }

    /** Exact authenticated connection identity; reconnect invalidates pending AI work. */
    public String connectedSessionId() {
        ClientSession session = activeSession.get();
        return session == null ? null : session.sessionId;
    }

    public Integer connectedProtocol() {
        ClientSession session = activeSession.get();
        return session == null ? null : session.protocol;
    }

    public BuildIdentity buildIdentity() {
        return buildIdentity;
    }

    public BuildIdentity connectedBuildIdentity() {
        ClientSession session = activeSession.get();
        return session == null ? null : session.buildIdentity;
    }

    public int pendingCount() {
        return pendingCommands.size();
    }

    public TelemetrySnapshot latestTelemetry() {
        return telemetry.get();
    }

    public dev.entitybridge.blueprint.BlueprintCatalogView blueprintCatalog() {
        return activeSession.get() == null ? dev.entitybridge.blueprint.BlueprintCatalogView.EMPTY : blueprintCatalog.get();
    }

    public BridgeResult latestResult() {
        return latestResult.get();
    }

    public JsonObject latestCapabilities() {
        JsonObject value = capabilities.get();
        return value == null ? null : value.deepCopy();
    }

    /** Installs an immutable primary-thread Paper recipe snapshot before clients request it. */
    public void installRecipeCatalog(RecipeCatalogSnapshot catalog) {
        recipeCatalog.set(Objects.requireNonNull(catalog, "catalog"));
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(settings.handshakeTimeoutSeconds() * 1_000);
                clientExecutor.execute(() -> handleClient(socket));
            } catch (SocketException exception) {
                if (running.get()) {
                    logger.log(Level.WARNING, "Bridge listener socket failed", exception);
                }
            } catch (IOException exception) {
                if (running.get()) {
                    logger.log(Level.WARNING, "Could not accept bridge connection", exception);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        ClientSession session = null;
        try (socket;
             FramedJsonChannel channel = new FramedJsonChannel(
                     socket.getInputStream(), socket.getOutputStream(), settings.maxFrameBytes()
             )) {
            JsonObject hello = channel.read();
            Handshake handshake;
            try {
                handshake = authenticate(hello);
            } catch (BuildIdentityMismatch mismatch) {
                writeError(channel, "build_identity_mismatch", mismatch.getMessage());
                logger.warning("Rejected controller before activation: " + mismatch.getMessage());
                return;
            }
            if (handshake == null) {
                writeError(channel, "authentication_failed", "Invalid bridge handshake");
                return;
            }

            session = new ClientSession(
                    socket,
                    channel,
                    handshake.client(),
                    handshake.protocol(),
                    handshake.buildIdentity(),
                    UUID.randomUUID().toString(),
                    settings.pendingCommandLimit() + 256
            );
            if (!session.enqueue(helloAck(session))) {
                throw new IOException("Could not queue bridge handshake response");
            }

            ClientSession prior;
            synchronized (dispatchLock) {
                prior = activeSession.getAndSet(session);
                blueprintCatalog.set(dev.entitybridge.blueprint.BlueprintCatalogView.EMPTY);
                if (session.protocol >= PROTOCOL_VERSION && !session.enqueue(missions.syncFrame())) {
                    activeSession.compareAndSet(session, null);
                    throw new IOException("Could not queue durable mission sync");
                }
                if (session.protocol >= PROTOCOL_VERSION) {
                    for (JsonObject receipt : deliveries.pendingReceiptFrames()) {
                        if (!session.enqueue(receipt)) {
                            activeSession.compareAndSet(session, null);
                            throw new IOException("Could not replay durable delivery receipt");
                        }
                    }
                    for (JsonObject returned : deliveries.pendingReturnFrames()) {
                        if (!session.enqueue(returned)) {
                            activeSession.compareAndSet(session, null);
                            throw new IOException("Could not replay durable returned-delivery event");
                        }
                    }
                }
                List<BridgeCommand> pending = pendingCommands.snapshot();
                Mission current = missions.current();
                if (session.protocol >= PROTOCOL_VERSION && current != null) {
                    for (Mission retired : missions.snapshot()) {
                        boolean cancellationAlreadyPending = pending.stream().anyMatch(command ->
                                retired.id().equals(command.missionId())
                                        && "mission.cancel".equals(command.operation())
                        );
                        if (retired.sequence() < current.sequence()
                                && retired.status() == MissionStatus.CANCELLED
                                && "superseded".equals(retired.step())
                                && !cancellationAlreadyPending
                                && !session.enqueue(BridgeCommand.lifecycle(
                                        retired, "cancel", retired.requestedBy()
                                ).toFrame(session.protocol))) {
                            activeSession.compareAndSet(session, null);
                            throw new IOException("Could not queue durable supersession replay");
                        }
                    }
                }
                boolean currentSubmitAlreadyPending = current != null && pending.stream().anyMatch(command ->
                        current.id().equals(command.missionId())
                                && "mission.submit".equals(command.operation())
                );
                if (session.protocol >= PROTOCOL_VERSION
                        && replayable(current)
                        && !currentSubmitAlreadyPending
                        && !session.enqueue(BridgeCommand.start(current).toFrame(session.protocol))) {
                    activeSession.compareAndSet(session, null);
                    throw new IOException("Could not queue durable current-mission replay");
                }
                for (BridgeCommand command : pending) {
                    if (!session.enqueue(command.toFrame(session.protocol))) {
                        activeSession.compareAndSet(session, null);
                        throw new IOException("Controller outbound queue filled during replay");
                    }
                }
            }
            if (prior != null) {
                prior.closeQuietly();
            }
            ClientSession authenticatedSession = session;
            session.startWriter(() -> disconnect(authenticatedSession));
            listener.onConnectionChanged(true, session.client + " (protocol " + session.protocol + ")");
            logger.info("Controller authenticated with exact build identity "
                    + session.buildIdentity.display());

            socket.setSoTimeout(0);
            while (running.get() && activeSession.get() == session) {
                handleIncoming(session, channel.read());
            }
        } catch (EOFException ignored) {
            // Normal controller disconnect.
        } catch (ProtocolException exception) {
            logger.warning("Closing controller connection: " + exception.getMessage());
        } catch (IOException exception) {
            if (running.get() && session != null && activeSession.get() == session) {
                logger.fine("Controller connection closed: " + exception.getMessage());
            }
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Unexpected bridge client error", exception);
        } finally {
            if (session != null) {
                boolean wasActive = activeSession.compareAndSet(session, null);
                session.closeQuietly();
                if (wasActive) {
                    listener.onConnectionChanged(false, session.client);
                }
            }
        }
    }

    private Handshake authenticate(JsonObject hello) throws ProtocolException, BuildIdentityMismatch {
        String type = requiredString(hello, "type", 32);
        String client = requiredString(hello, "client", 64);
        JsonElement protocolElement = hello.get("protocol");
        JsonElement tokenElement = hello.get("token");
        if (!"hello".equals(type)
                || protocolElement == null
                || !protocolElement.isJsonPrimitive()
                || tokenElement == null
                || !tokenElement.isJsonPrimitive()) {
            return null;
        }
        int protocol;
        try {
            protocol = protocolElement.getAsInt();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (protocol < LEGACY_PROTOCOL_VERSION || protocol > PROTOCOL_VERSION) {
            return null;
        }
        byte[] expected = settings.authToken().getBytes(StandardCharsets.UTF_8);
        byte[] presented = tokenElement.getAsString().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, presented)) return null;

        BuildIdentity clientIdentity;
        try {
            clientIdentity = BuildIdentity.of(
                    requiredInt(hello, "clientIdentitySchemaVersion"),
                    requiredString(hello, "clientVersion", 64),
                    requiredString(hello, "clientSourceCommit", 64),
                    requiredString(hello, "clientBuildId", 128),
                    requiredString(hello, "clientSourceState", 80),
                    requiredString(hello, "clientGeneratedAtUtc", 64));
            buildIdentity.requireExact(clientIdentity, "client");
        } catch (ProtocolException | IllegalArgumentException invalid) {
            throw new BuildIdentityMismatch(invalid.getMessage(), invalid);
        }
        return new Handshake(client, protocol, clientIdentity);
    }

    private JsonObject helloAck(ClientSession session) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "hello_ack");
        frame.addProperty("protocol", session.protocol);
        frame.addProperty("protocolMin", LEGACY_PROTOCOL_VERSION);
        frame.addProperty("protocolMax", PROTOCOL_VERSION);
        frame.addProperty("server", "EntityBridge");
        frame.addProperty("serverIdentitySchemaVersion", buildIdentity.schemaVersion());
        frame.addProperty("serverVersion", buildIdentity.version());
        frame.addProperty("serverSourceCommit", buildIdentity.sourceCommit());
        frame.addProperty("serverBuildId", buildIdentity.buildId());
        frame.addProperty("serverSourceState", buildIdentity.sourceState());
        frame.addProperty("serverGeneratedAtUtc", buildIdentity.generatedAt().toString());
        frame.addProperty("session", session.sessionId);
        frame.addProperty("pending", pendingCommands.size());
        frame.addProperty("mode", missions.mode());
        if (session.protocol >= PROTOCOL_VERSION) {
            JsonArray features = new JsonArray();
            for (String feature : new String[]{
                    "durable_missions", "mission_sync", "mission_lifecycle",
                    "target_sequence", "survival_telemetry", "baritone_telemetry",
                    "item_missions", "plan_query", "stock_policy",
                    "delivery_transactions", "delivery_receipts", "delivery_inventory_commit", "delivery_exact_inventory_commit",
                    "server_damage", "server_damage_target_identity", "server_threat_target",
                    "block_break_rejected", "contextual_protection", "food_hunting",
                    "authoritative_world_identity",
                    EntityRelationshipProtocol.FEATURE,
                    ProtectedAreaProtocol.FEATURE,
                    ProtectedAreaProtocol.HOME_PERMIT_FEATURE
            }) {
                features.add(feature);
            }
            if (recipeCatalog.get() != null) {
                features.add("paper_recipe_catalog_v1");
            }
            frame.add("features", features);
            JsonObject worlds = new JsonObject();
            worldIdentities.get().forEach(worlds::addProperty);
            frame.add("worldIdentities", worlds);
        }
        frame.addProperty("timestamp", System.currentTimeMillis());
        return frame;
    }

    private void handleIncoming(ClientSession session, JsonObject frame) throws IOException {
        String type = requiredString(frame, "type", 32);
        switch (type) {
            case "result" -> handleResult(frame);
            case "ack", "command_ack" -> handleAcknowledgement(frame);
            case "mission_update", "mission_status" -> handleMissionUpdate(frame);
            case "telemetry" -> handleTelemetry(frame);
            case "capabilities", "capabilities_result" -> capabilities.set(frame.deepCopy());
            case "recipe_catalog_request" -> handleRecipeCatalogRequest(session, frame);
            case "delivery_prepare" -> handleDeliveryPrepare(session, frame);
            case "delivery_commit" -> handleDeliveryCommit(session, frame);
            case "delivery_receipt_ack" -> deliveries.acknowledge(
                    requiredString(frame, "receiptId", 256));
            case "delivery_returned_ack" -> deliveries.acknowledgeReturn(
                    requiredString(frame, "returnId", 256));
            case ProtectedAreaProtocol.ACK_TYPE -> {
                ProtectedAreaProtocol.Acknowledgement acknowledgement;
                try {
                    acknowledgement = ProtectedAreaProtocol.acknowledgement(frame);
                } catch (IllegalArgumentException invalid) {
                    throw new ProtocolException(invalid.getMessage());
                }
                listener.onProtectedAreaPolicyAcknowledged(
                        acknowledgement.revision(), acknowledgement.digest());
            }
            case ProtectedAreaProtocol.HOME_PERMIT_REQUEST_TYPE -> {
                try {
                    listener.onProtectedAreaHomePermitRequested(
                            ProtectedAreaProtocol.homePermitRequest(frame));
                } catch (IllegalArgumentException invalid) {
                    throw new ProtocolException(invalid.getMessage());
                }
            }
            case "ping" -> {
                JsonObject pong = new JsonObject();
                pong.addProperty("type", "pong");
                pong.addProperty("timestamp", System.currentTimeMillis());
                if (!session.enqueue(pong)) {
                    disconnect(session);
                }
            }
            case "pong" -> {
                // Reserved for server-side heartbeat support.
            }
            default -> {
                if (!session.enqueue(errorFrame("unsupported_type", "Unsupported frame type: " + type))) {
                    disconnect(session);
                }
            }
        }
    }

    private void handleRecipeCatalogRequest(ClientSession session, JsonObject frame)
            throws ProtocolException {
        String requestId = requiredString(frame, "requestId", 128);
        if (session.protocol < PROTOCOL_VERSION) {
            enqueueRecipeCatalogError(
                    session, requestId, "unsupported_protocol",
                    "Paper recipe catalogs require bridge protocol 2");
            return;
        }
        Long requestedSchema = optionalLong(frame, "schema");
        if (requestedSchema != null && requestedSchema != RecipeCatalogSnapshot.SCHEMA_VERSION) {
            enqueueRecipeCatalogError(
                    session, requestId, "unsupported_schema",
                    "Supported recipe catalog schema is " + RecipeCatalogSnapshot.SCHEMA_VERSION);
            return;
        }
        RecipeCatalogSnapshot catalog = recipeCatalog.get();
        if (catalog == null) {
            enqueueRecipeCatalogError(
                    session, requestId, "catalog_unavailable",
                    "Paper recipe catalog is not available on this server session");
            return;
        }

        final boolean unlockForEntity;
        final String entityPlayer;
        try {
            unlockForEntity = optionalBoolean(frame, "unlockForEntity", false);
            String requestedPlayer = optionalString(frame, "entityPlayer", 16);
            entityPlayer = requestedPlayer == null ? "Entity" : requestedPlayer;
            if (!entityPlayer.matches("[A-Za-z0-9_]{1,16}")) {
                throw new ProtocolException(
                        "entityPlayer must be a valid Minecraft player name");
            }
        } catch (ProtocolException malformed) {
            enqueueRecipeCatalogError(
                    session, requestId, "invalid_request", malformed.getMessage());
            return;
        }

        if (!unlockForEntity) {
            publishRecipeCatalog(session, requestId, catalog, null);
            return;
        }

        AtomicBoolean completed = new AtomicBoolean();
        try {
            listener.onRecipeDiscovery(entityPlayer, catalog.recipeIds(), discovery -> {
                if (!completed.compareAndSet(false, true)) return;
                RecipeDiscoveryResult safeDiscovery = discovery;
                if (safeDiscovery == null) {
                    safeDiscovery = RecipeDiscoveryResult.unavailable(
                            entityPlayer, catalog.exported(), "discovery_failed",
                            "Paper returned no recipe discovery result");
                }
                if (activeSession.get() == session) {
                    publishRecipeCatalog(session, requestId, catalog, safeDiscovery);
                }
            });
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "Could not schedule Paper recipe discovery", failure);
            if (completed.compareAndSet(false, true) && activeSession.get() == session) {
                publishRecipeCatalog(session, requestId, catalog,
                        RecipeDiscoveryResult.unavailable(
                                entityPlayer, catalog.exported(), "discovery_failed",
                                "Paper could not schedule recipe discovery"));
            }
        }
    }

    private void publishRecipeCatalog(
            ClientSession session,
            String requestId,
            RecipeCatalogSnapshot catalog,
            RecipeDiscoveryResult discovery
    ) {
        final List<JsonObject> frames;
        try {
            frames = catalog.frames(requestId, settings.maxFrameBytes(), discovery);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            logger.log(Level.WARNING, "Could not frame Paper recipe catalog", failure);
            enqueueRecipeCatalogError(
                    session, requestId, "catalog_too_large",
                    "Paper recipe catalog cannot fit the configured bridge frame size");
            return;
        }
        if (!session.enqueueBatch(frames)) disconnect(session);
    }

    private void enqueueRecipeCatalogError(
            ClientSession session, String requestId, String code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "recipe_catalog_error");
        response.addProperty("schema", RecipeCatalogSnapshot.SCHEMA_VERSION);
        response.addProperty("requestId", requestId);
        response.addProperty("code", code);
        response.addProperty("message", message);
        if (!session.enqueue(response)) disconnect(session);
    }

    private void handleDeliveryPrepare(ClientSession session, JsonObject frame) throws IOException {
        DeliveryTransactionRegistry.Preparation preparation;
        DeliveryTransactionRegistry.PrepareResult result;
        try {
            preparation = DeliveryTransactionRegistry.Preparation.fromFrame(frame);
            result = deliveries.prepare(preparation);
        } catch (IllegalArgumentException error) {
            String nonce = optionalString(frame, "nonce", 128);
            JsonObject rejected = deliveryPreparedFrame(
                    optionalString(frame, "missionId", 128), nonce, false, error.getMessage());
            if (!session.enqueue(rejected)) disconnect(session);
            return;
        }
        JsonObject response = deliveryPreparedFrame(
                preparation.missionId(), preparation.nonce(), result.accepted(), result.reason());
        if (!session.enqueue(response)) disconnect(session);
    }

    private void handleDeliveryCommit(ClientSession session, JsonObject frame) {
        String missionId;
        String nonce;
        try {
            missionId = requiredString(frame, "missionId", 128);
            nonce = requiredString(frame, "nonce", 128);
        } catch (ProtocolException error) {
            publishDeliveryCommitResult(session, DeliveryCommitResult.rejected(
                    safeString(frame, "missionId"), safeString(frame, "nonce"),
                    false, "invalid_request", error.getMessage()));
            return;
        }

        DeliveryTransactionRegistry.CommitAuthorization authorization =
                deliveries.authorizeDirectCommit(missionId, nonce, System.currentTimeMillis());
        if (!authorization.accepted()) {
            publishDeliveryCommitResult(session, DeliveryCommitResult.rejected(
                    missionId, nonce, false, authorization.code(), authorization.reason()));
            return;
        }
        DeliveryCommitRequest request = authorization.request();
        AtomicBoolean completed = new AtomicBoolean();
        try {
            listener.onDeliveryCommit(request, result -> {
                if (!completed.compareAndSet(false, true)) return;
                DeliveryCommitResult checked = result == null
                        ? DeliveryCommitResult.rejected(
                                request, true, "internal_error",
                                "Paper delivery handler returned no result")
                        : result;
                publishDeliveryCommitResult(session, checked);
            });
        } catch (RuntimeException error) {
            if (completed.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Paper delivery handler rejected a commit", error);
                publishDeliveryCommitResult(session, DeliveryCommitResult.rejected(
                        request, true, "internal_error",
                        "Paper delivery handler failed safely"));
            }
        }
    }

    private void publishDeliveryCommitResult(
            ClientSession requestingSession,
            DeliveryCommitResult result) {
        ClientSession current = activeSession.get();
        if (current == null) return;
        // A reconnect may replace the requesting socket while Bukkit waits for
        // its next tick. Send cumulative state to the authenticated current
        // controller; any receipt is also durable and replayed on reconnect.
        ClientSession destination = current == requestingSession ? requestingSession : current;
        if (!destination.enqueue(result.toFrame())) {
            disconnect(destination);
            return;
        }
        if (result.receipt() != null && !destination.enqueue(result.receipt().toFrame())) {
            disconnect(destination);
        }
    }

    private static JsonObject deliveryPreparedFrame(
            String missionId, String nonce, boolean accepted, String reason) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "delivery_prepared");
        frame.addProperty("missionId", Objects.requireNonNullElse(missionId, ""));
        frame.addProperty("nonce", Objects.requireNonNullElse(nonce, ""));
        frame.addProperty("accepted", accepted);
        frame.addProperty("reason", Objects.requireNonNullElse(reason, ""));
        frame.addProperty("timestamp", System.currentTimeMillis());
        return frame;
    }

    private static String safeString(JsonObject frame, String key) {
        JsonElement element = frame.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) return "";
        String value = element.getAsString();
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private void handleAcknowledgement(JsonObject frame) throws ProtocolException {
        pendingCommands.complete(requiredString(frame, "id", 128));
    }

    private void handleResult(JsonObject frame) throws ProtocolException {
        String id = requiredString(frame, "id", 128);
        JsonElement okElement = frame.get("ok");
        if (okElement == null || !okElement.isJsonPrimitive()
                || !okElement.getAsJsonPrimitive().isBoolean()) {
            throw new ProtocolException("result.ok must be a boolean");
        }
        boolean ok = okElement.getAsBoolean();
        String message = optionalString(frame, "message", 512);
        BridgeCommand completed = pendingCommands.complete(id);

        String missionId = optionalString(frame, "missionId", 128);
        if (missionId == null && completed != null) {
            missionId = completed.missionId();
        }
        Long sequence = optionalLong(frame, "sequence");
        if (sequence == null && completed != null) {
            sequence = completed.sequence();
        }
        String statusText = optionalString(frame, "status", 64);
        String phase = optionalString(frame, "phase", 64);

        if (missionId != null) {
            MissionStatus status = MissionStatus.parse(statusText, null);
            if (status == null && completed != null) {
                status = inferredResultStatus(completed.operation(), ok);
            } else if (status == null) {
                status = MissionStatus.parse(phase, ok ? MissionStatus.ACTIVE : MissionStatus.BLOCKED);
            }
            Mission recorded = missions.resolve(missionId).orElse(null);
            if (queueEnvelopeMatches(recorded, sequence, frame, null)
                    && (status != MissionStatus.SUCCEEDED || !queueOwned(recorded) || id.equals(recorded.commandId()))) {
                OptionalMissionUpdate update = updateMission(missionId, sequence, status, null, message);
                if (update.mission() != null) listener.onMissionUpdated(update.mission());
            }
        }

        dev.entitybridge.blueprint.BlueprintPreview blueprintPreview;
        try { blueprintPreview = dev.entitybridge.blueprint.BlueprintPreview.parse(frame.get("blueprintPreview")); }
        catch (IllegalArgumentException invalid) { throw new ProtocolException("Invalid blueprint preview", invalid); }
        dev.entitybridge.blueprint.BlueprintCatalogView catalog;
        try { catalog = dev.entitybridge.blueprint.BlueprintCatalogView.parse(frame.get("blueprintCatalog")); }
        catch (IllegalArgumentException invalid) { throw new ProtocolException("Invalid blueprint catalog", invalid); }
        if (catalog != null) blueprintCatalog.set(catalog);
        BridgeResult result = new BridgeResult(
                id, ok, message, missionId, sequence, statusText == null ? phase : statusText,
                optionalString(frame, "queueId", 128), optionalLong(frame, "queueGeneration"),
                optionalLong(frame, "queueStep"), optionalLong(frame, "queueAttempt"), blueprintPreview, catalog
        );
        latestResult.set(result);
        listener.onResult(result);
    }

    private static MissionStatus inferredResultStatus(String operation, boolean ok) {
        if (!ok) {
            return MissionStatus.BLOCKED;
        }
        String normalized = operation == null ? "mission.submit" : operation;
        return switch (normalized) {
            case "mission.pause", "pause" -> MissionStatus.PAUSED;
            case "mission.cancel", "cancel", "stop" -> MissionStatus.CANCELLED;
            case "mission.retry", "retry" -> MissionStatus.RETRYING;
            case "mission.resume", "resume", "mission.submit", "start" -> MissionStatus.ACTIVE;
            default -> MissionStatus.ACTIVE;
        };
    }

    private static boolean replayable(Mission mission) {
        if (mission == null) {
            return false;
        }
        return mission.status() == MissionStatus.QUEUED
                || mission.status() == MissionStatus.ACTIVE
                || mission.status() == MissionStatus.RETRYING;
    }

    private void handleMissionUpdate(JsonObject frame) throws ProtocolException {
        String id = optionalString(frame, "missionId", 128);
        JsonObject nested = frame.has("mission") && frame.get("mission").isJsonObject()
                ? frame.getAsJsonObject("mission")
                : null;
        if (id == null && nested != null) {
            id = optionalString(nested, "id", 128);
        }
        if (id == null) {
            throw new ProtocolException("mission_update.missionId is required");
        }
        Long sequence = optionalLong(frame, "sequence");
        if (sequence == null && nested != null) {
            sequence = optionalLong(nested, "sequence");
        }
        String statusText = optionalString(frame, "status", 64);
        if (statusText == null && nested != null) {
            statusText = optionalString(nested, "status", 64);
        }
        String step = optionalString(frame, "step", 512);
        if (step == null && nested != null) {
            step = firstOptionalString(nested, 512, "step", "currentStep");
        }
        String reason = firstOptionalString(frame, 512, "reason", "message", "why");
        if (reason == null && nested != null) {
            reason = firstOptionalString(nested, 512, "reason", "message", "why");
        }
        MissionStatus status = MissionStatus.parse(statusText, null);
        if (!queueEnvelopeMatches(missions.resolve(id).orElse(null), sequence, frame, nested)) return;
        Mission updated = updateMission(id, sequence, status, step, reason).mission();
        if (updated != null) {
            listener.onMissionUpdated(updated);
        }
    }

    private void handleTelemetry(JsonObject frame) throws ProtocolException {
        try {
            var catalog = dev.entitybridge.blueprint.BlueprintCatalogView.parse(frame.get("blueprintCatalog"));
            if (catalog != null) blueprintCatalog.set(catalog);
        } catch (IllegalArgumentException invalid) { throw new ProtocolException("Invalid blueprint catalog", invalid); }
        TelemetrySnapshot snapshot = TelemetrySnapshot.parse(frame);
        telemetry.set(snapshot);
        TelemetrySnapshot.MissionInfo mission = snapshot.mission();
        if (mission != null && mission.id() != null
                && !queueOwned(missions.resolve(mission.id()).orElse(null))) {
            MissionStatus status = MissionStatus.parse(mission.status(), null);
            Mission updated = updateMission(
                    mission.id(), mission.sequence(), status, mission.step(), mission.reason()
            ).mission();
            if (updated != null) {
                listener.onMissionUpdated(updated);
            }
        }
        listener.onTelemetry(snapshot);
    }

    private static boolean queueOwned(Mission mission) {
        return mission != null && mission.args().has("queueId");
    }

    /** A stale result must not become valid by being read back from unchanged journal args. */
    private static boolean queueEnvelopeMatches(Mission mission, Long sequence, JsonObject frame, JsonObject nested) {
        if (!queueOwned(mission)) return true;
        if (sequence == null || sequence != mission.sequence()) return false;
        for (String key : List.of("queueId", "queueGeneration", "queueStep", "queueAttempt")) {
            JsonElement value = frame.get(key);
            if (value == null && nested != null) value = nested.get(key);
            if (value == null || !value.equals(mission.args().get(key))) return false;
        }
        return true;
    }

    private OptionalMissionUpdate updateMission(
            String id,
            Long sequence,
            MissionStatus status,
            String step,
            String reason
    ) {
        return new OptionalMissionUpdate(missions.update(id, sequence, status, step, reason).orElse(null));
    }

    private void disconnect(ClientSession session) {
        if (activeSession.compareAndSet(session, null)) {
            blueprintCatalog.set(dev.entitybridge.blueprint.BlueprintCatalogView.EMPTY);
            session.closeQuietly();
            listener.onConnectionChanged(false, session.client);
        }
    }

    private static void writeError(FramedJsonChannel channel, String code, String message) throws IOException {
        channel.write(errorFrame(code, message));
    }

    private static JsonObject errorFrame(String code, String message) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "error");
        frame.addProperty("code", code);
        frame.addProperty("message", message);
        return frame;
    }

    private static String requiredString(JsonObject object, String key, int maxLength) throws ProtocolException {
        String value = optionalString(object, key, maxLength);
        if (value == null || value.isBlank()) {
            throw new ProtocolException(key + " must be a non-empty string");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String key, int maxLength) throws ProtocolException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolException(key + " must be a string");
        }
        String value = element.getAsString();
        if (value.length() > maxLength) {
            throw new ProtocolException(key + " is too long");
        }
        return value;
    }

    private static String firstOptionalString(JsonObject object, int maxLength, String... keys)
            throws ProtocolException {
        for (String key : keys) {
            String value = optionalString(object, key, maxLength);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long optionalLong(JsonObject object, String key) throws ProtocolException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ProtocolException(key + " must be a number");
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException exception) {
            throw new ProtocolException(key + " must be an integer", exception);
        }
    }

    private static int requiredInt(JsonObject object, String key) throws ProtocolException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new ProtocolException(key + " must be an integer");
        }
        try {
            return new BigDecimal(element.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new ProtocolException(key + " must be an integer", invalid);
        }
    }

    private static boolean optionalBoolean(
            JsonObject object, String key, boolean defaultValue) throws ProtocolException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return defaultValue;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new ProtocolException(key + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    @Override
    public synchronized void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        ClientSession session = activeSession.getAndSet(null);
        if (session != null) {
            session.closeQuietly();
            listener.onConnectionChanged(false, session.client);
        }
        ServerSocket listenerSocket = serverSocket;
        if (listenerSocket != null) {
            try {
                listenerSocket.close();
            } catch (IOException ignored) {
            }
        }
        clientExecutor.shutdownNow();
        Thread thread = acceptThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public record Dispatch(boolean accepted, boolean delivered, String message) {
    }

    public record SupersessionDispatch(Dispatch preemption, Dispatch replacement) {
        public boolean accepted() {
            return preemption.accepted() && replacement != null && replacement.accepted();
        }

        public boolean delivered() {
            return accepted() && preemption.delivered() && replacement.delivered();
        }

        public String message() {
            if (!preemption.accepted()) {
                return "Could not queue prior mission cancellation: " + preemption.message();
            }
            if (replacement == null || !replacement.accepted()) {
                return "Prior mission cancellation was queued, but replacement could not be queued: "
                        + (replacement == null ? "unknown failure" : replacement.message());
            }
            return delivered() ? "preempted and sent" : "preemption queued until Entity connects";
        }
    }

    private record Handshake(
            String client,
            int protocol,
            BuildIdentity buildIdentity) {
    }

    private static final class BuildIdentityMismatch extends IOException {
        private static final long serialVersionUID = 1L;

        private BuildIdentityMismatch(String message, Throwable cause) {
            super("Exact Entity2 client/server build identity required: " + message, cause);
        }
    }

    private record OptionalMissionUpdate(Mission mission) {
    }

    private static final class ClientSession {
        private final Socket socket;
        private final FramedJsonChannel channel;
        private final String client;
        private final int protocol;
        private final BuildIdentity buildIdentity;
        private final String sessionId;
        private final LinkedBlockingDeque<JsonObject> outbound;
        private final AtomicReference<JsonObject> latestTargetUpdate = new AtomicReference<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private volatile Thread writerThread;

        private ClientSession(
                Socket socket,
                FramedJsonChannel channel,
                String client,
                int protocol,
                BuildIdentity buildIdentity,
                String sessionId,
                int outboundCapacity
        ) {
            this.socket = socket;
            this.channel = channel;
            this.client = client;
            this.protocol = protocol;
            this.buildIdentity = Objects.requireNonNull(buildIdentity, "buildIdentity");
            this.sessionId = sessionId;
            this.outbound = new LinkedBlockingDeque<>(outboundCapacity);
        }

        private synchronized boolean enqueue(JsonObject frame) {
            return open.get() && outbound.offerLast(frame.deepCopy());
        }

        private synchronized boolean enqueueBatch(List<JsonObject> frames) {
            if (!open.get() || outbound.remainingCapacity() < frames.size()) {
                return false;
            }
            for (JsonObject frame : frames) {
                if (!outbound.offerLast(frame.deepCopy())) {
                    return false;
                }
            }
            return true;
        }

        private synchronized boolean enqueueStop(JsonObject frame) {
            if (!open.get()) {
                return false;
            }
            outbound.removeIf(ClientSession::isCommandFrame);
            return outbound.offerLast(frame.deepCopy());
        }

        private boolean replaceTargetUpdate(JsonObject frame) {
            if (!open.get()) {
                return false;
            }
            latestTargetUpdate.set(frame.deepCopy());
            return true;
        }

        private synchronized void startWriter(Runnable onFailure) {
            if (writerThread != null) {
                return;
            }
            Thread thread = new Thread(
                    () -> writeLoop(onFailure),
                    "EntityBridge-writer-" + sessionId.substring(0, 8)
            );
            thread.setDaemon(true);
            writerThread = thread;
            thread.start();
        }

        private void writeLoop(Runnable onFailure) {
            try {
                while (open.get()) {
                    JsonObject frame = outbound.pollFirst(100L, TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        frame = latestTargetUpdate.getAndSet(null);
                    }
                    if (frame == null) {
                        continue;
                    }
                    channel.write(frame);
                    if (outbound.isEmpty()) {
                        JsonObject targetUpdate = latestTargetUpdate.getAndSet(null);
                        if (targetUpdate != null) {
                            channel.write(targetUpdate);
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                if (open.getAndSet(false)) {
                    onFailure.run();
                }
            }
        }

        private static boolean isCommandFrame(JsonObject frame) {
            JsonElement type = frame.get("type");
            return type != null && type.isJsonPrimitive() && "command".equals(type.getAsString());
        }

        private void closeQuietly() {
            open.set(false);
            outbound.clear();
            latestTargetUpdate.set(null);
            Thread thread = writerThread;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        private final String prefix;

        private DaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
