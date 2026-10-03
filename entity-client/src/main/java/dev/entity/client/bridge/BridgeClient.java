package dev.entity.client.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import dev.entity.client.config.EntityClientConfig;
import dev.entity.core.build.BuildIdentity;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Existing length-prefixed JSON transport with protocol-2-first negotiation. */
public final class BridgeClient implements AutoCloseable {
    public interface Listener {
        void onFrame(JsonObject frame);

        void onConnectionChanged(boolean connected, int protocol, String detail);
    }

    private final EntityClientConfig.Bridge config;
    private final Listener listener;
    private final Logger logger;
    private final BuildIdentity buildIdentity;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong lastReceivedAt = new AtomicLong();
    private final Object writeLock = new Object();
    private final BridgeClientHandshakeState handshakeState;

    private volatile Socket socket;
    private volatile DataOutputStream output;
    private volatile int negotiatedProtocol;
    private volatile Set<String> negotiatedFeatures = Set.of();
    private volatile Map<String, String> serverWorldIdentities = Map.of();
    private volatile BuildIdentity serverBuildIdentity;
    private volatile String lastHandshakeFailure = "";
    private volatile Thread worker;

    public BridgeClient(EntityClientConfig.Bridge config, Listener listener, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.buildIdentity = BuildIdentity.current();
        this.handshakeState = new BridgeClientHandshakeState(config.preferredProtocol());
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread thread = new Thread(this::connectionLoop, "Entity2-bridge");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    public boolean send(JsonObject frame) {
        synchronized (writeLock) {
            if (!handshakeState.ready() || output == null) return false;
            try {
                FrameCodec.write(output, frame, config.maxFrameBytes());
                return true;
            } catch (IOException exception) {
                logger.warn("EntityBridge send failed: {}", exception.getMessage());
                closeSocket();
                return false;
            }
        }
    }

    public boolean connected() {
        return handshakeState.ready();
    }

    public int negotiatedProtocol() {
        return negotiatedProtocol;
    }

    public boolean hasFeature(String feature) {
        return negotiatedFeatures.contains(Objects.requireNonNullElse(feature, ""));
    }

    public Set<String> negotiatedFeatures() {
        return negotiatedFeatures;
    }

    /** Paper-authoritative UUID for the exact loaded dimension, when negotiated. */
    public Optional<String> worldIdentity(String dimension) {
        return Optional.ofNullable(serverWorldIdentities.get(
                Objects.requireNonNullElse(dimension, "")));
    }

    public BuildIdentity buildIdentity() {
        return buildIdentity;
    }

    public BuildIdentity serverBuildIdentity() {
        return serverBuildIdentity;
    }

    public String lastHandshakeFailure() {
        return lastHandshakeFailure;
    }

    private void connectionLoop() {
        int retryDelay = config.reconnectInitialMillis();
        while (running.get()) {
            int attemptedProtocol = handshakeState.attemptedProtocol();
            try {
                runSession(attemptedProtocol);
                handshakeState.resetPreferredProtocol();
                retryDelay = config.reconnectInitialMillis();
            } catch (HandshakeFailure failure) {
                lastHandshakeFailure = failure.getMessage();
                BridgeClientHandshakeState.FailureAction action =
                        handshakeState.handshakeFailed(failure.legacyFallbackAllowed());
                if (action == BridgeClientHandshakeState.FailureAction.RETRY_PROTOCOL_1_NOW) {
                    logger.info("Bridge protocol {} was not accepted; retrying protocol 1", attemptedProtocol);
                    continue;
                }
                logger.warn("EntityBridge authentication/handshake failed: {}", failure.getMessage());
            } catch (EOFException ignored) {
                logger.info("EntityBridge connection closed");
            } catch (IOException exception) {
                if (running.get()) logger.info("EntityBridge unavailable: {}", exception.getMessage());
            } catch (RuntimeException exception) {
                logger.error("Unexpected EntityBridge client failure", exception);
            } finally {
                setDisconnected("connection closed");
                closeSocket();
            }

            if (!running.get()) break;
            sleepInterruptibly(retryDelay);
            retryDelay = Math.min(config.reconnectMaximumMillis(), Math.max(
                    config.reconnectInitialMillis(), (int) Math.ceil(retryDelay * 1.8)));
            handshakeState.resetPreferredProtocol();
        }
    }

    private void runSession(int protocol) throws IOException {
        Socket connectedSocket = new Socket();
        socket = connectedSocket;
        connectedSocket.setTcpNoDelay(true);
        connectedSocket.setKeepAlive(true);
        connectedSocket.connect(
                new InetSocketAddress(config.host(), config.port()),
                config.connectTimeoutMillis());
        connectedSocket.setSoTimeout(config.handshakeTimeoutMillis());

        DataInputStream input = new DataInputStream(connectedSocket.getInputStream());
        DataOutputStream connectedOutput = new DataOutputStream(connectedSocket.getOutputStream());
        synchronized (writeLock) {
            output = connectedOutput;
            FrameCodec.write(connectedOutput, hello(protocol), config.maxFrameBytes());
        }

        JsonObject response;
        try {
            response = FrameCodec.read(input, config.maxFrameBytes());
        } catch (IOException exception) {
            throw new HandshakeFailure(exception.getMessage(), exception, true);
        }
        String type = string(response, "type");
        if (!"hello_ack".equals(type) || response.has("ok") && !response.get("ok").getAsBoolean()) {
            throw new HandshakeFailure(response.has("message")
                    ? response.get("message").getAsString()
                    : "expected hello_ack, received " + type,
                    BridgeClientHandshakeState.legacyFallbackAllowed(response));
        }

        try {
            serverBuildIdentity = BridgeBuildIdentity.requireExactServerIdentity(response, buildIdentity);
        } catch (IllegalArgumentException mismatch) {
            throw new HandshakeFailure(mismatch.getMessage(), mismatch, false);
        }

        negotiatedProtocol = response.has("protocol") ? response.get("protocol").getAsInt() : protocol;
        LinkedHashSet<String> features = new LinkedHashSet<>();
        if (response.has("features") && response.get("features").isJsonArray()) {
            for (JsonElement feature : response.getAsJsonArray("features")) {
                if (feature.isJsonPrimitive()) features.add(feature.getAsString());
            }
        }
        negotiatedFeatures = Set.copyOf(features);
        serverWorldIdentities = BridgeWorldIdentities.parse(response, negotiatedFeatures);
        lastHandshakeFailure = "";
        handshakeState.authenticated();
        lastReceivedAt.set(System.currentTimeMillis());
        listener.onConnectionChanged(true, negotiatedProtocol, "authenticated");
        logger.info("EntityBridge authenticated with protocol {} and exact build identity {}",
                negotiatedProtocol, buildIdentity.display());

        connectedSocket.setSoTimeout(config.heartbeatIntervalMillis());
        while (running.get() && connectedSocket == socket && !connectedSocket.isClosed()) {
            try {
                JsonObject frame = FrameCodec.read(input, config.maxFrameBytes());
                lastReceivedAt.set(System.currentTimeMillis());
                handleFrame(frame);
            } catch (SocketTimeoutException timeout) {
                long age = System.currentTimeMillis() - lastReceivedAt.get();
                if (age > config.heartbeatTimeoutMillis()) {
                    throw new IOException("heartbeat timed out after " + age + "ms");
                }
                JsonObject ping = new JsonObject();
                ping.addProperty("type", "ping");
                ping.addProperty("timestamp", System.currentTimeMillis());
                send(ping);
            }
        }
    }

    private JsonObject hello(int protocol) {
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocol", protocol);
        hello.addProperty("token", config.token());
        hello.addProperty("client", "Entity2");
        BridgeBuildIdentity.addClientIdentity(hello, buildIdentity);
        return hello;
    }

    private void handleFrame(JsonObject frame) {
        String type = string(frame, "type");
        if ("ping".equals(type)) {
            JsonObject pong = new JsonObject();
            pong.addProperty("type", "pong");
            pong.addProperty("timestamp", frame.has("timestamp")
                    ? frame.get("timestamp").getAsLong()
                    : System.currentTimeMillis());
            send(pong);
            return;
        }
        if ("pong".equals(type) || "hello_ack".equals(type)) return;
        try {
            listener.onFrame(frame.deepCopy());
        } catch (RuntimeException exception) {
            logger.error("Bridge frame listener failed", exception);
        }
    }

    private void setDisconnected(String detail) {
        boolean wasReady = handshakeState.ready();
        handshakeState.disconnected();
        negotiatedProtocol = 0;
        negotiatedFeatures = Set.of();
        serverWorldIdentities = Map.of();
        serverBuildIdentity = null;
        if (wasReady) listener.onConnectionChanged(false, 0, detail);
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        synchronized (writeLock) {
            output = null;
        }
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void sleepInterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String string(JsonObject object, String field) {
        return object.has(field) && object.get(field).isJsonPrimitive()
                ? object.get(field).getAsString()
                : "";
    }

    @Override
    public void close() {
        running.set(false);
        handshakeState.disconnected();
        closeSocket();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    private static final class HandshakeFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final boolean legacyFallbackAllowed;

        private HandshakeFailure(String message, boolean legacyFallbackAllowed) {
            super(message);
            this.legacyFallbackAllowed = legacyFallbackAllowed;
        }

        private HandshakeFailure(String message, Throwable cause, boolean legacyFallbackAllowed) {
            super(message, cause);
            this.legacyFallbackAllowed = legacyFallbackAllowed;
        }

        private boolean legacyFallbackAllowed() {
            return legacyFallbackAllowed;
        }
    }
}
