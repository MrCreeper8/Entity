package dev.entity.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public record EntityClientConfig(Bridge bridge, Survival survival, int telemetryIntervalTicks) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public EntityClientConfig {
        bridge = bridge == null ? Bridge.defaults() : bridge.validated();
        survival = survival == null ? Survival.defaults() : survival.validated();
        telemetryIntervalTicks = telemetryIntervalTicks < 1 ? 20 : telemetryIntervalTicks;
    }

    public static EntityClientConfig load(Path configDirectory, Logger logger) {
        Path path = configDirectory.resolve("client.json");
        try {
            Files.createDirectories(configDirectory);
            if (!Files.exists(path)) {
                EntityClientConfig defaults = defaults();
                try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaults, writer);
                }
                logger.warn("Created {}. Set bridge.token before Entity can receive commands.", path);
                return defaults.withOverrides();
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                EntityClientConfig value = GSON.fromJson(reader, EntityClientConfig.class);
                return (value == null ? defaults() : value).validated().withOverrides();
            }
        } catch (IOException | RuntimeException error) {
            logger.error("Could not load {}; using safe defaults", path, error);
            return defaults().withOverrides();
        }
    }

    public EntityClientConfig validated() {
        return new EntityClientConfig(bridge, survival, telemetryIntervalTicks);
    }

    private EntityClientConfig withOverrides() {
        String token = firstNonBlank(
                System.getProperty("entity2.bridgeToken"),
                System.getenv("ENTITY_BRIDGE_TOKEN"),
                bridge.token());
        return new EntityClientConfig(bridge.withToken(token), survival, telemetryIntervalTicks);
    }

    public boolean hasUsableToken() {
        return !bridge.token().isBlank() && !bridge.token().equals("REPLACE_ME");
    }

    public static EntityClientConfig defaults() {
        return new EntityClientConfig(Bridge.defaults(), Survival.defaults(), 20);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public record Bridge(
            String host,
            int port,
            String token,
            int preferredProtocol,
            int maxFrameBytes,
            int connectTimeoutMillis,
            int handshakeTimeoutMillis,
            int heartbeatIntervalMillis,
            int heartbeatTimeoutMillis,
            int reconnectInitialMillis,
            int reconnectMaximumMillis) {

        public static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;

        public Bridge {
            host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
            token = token == null ? "" : token;
        }

        private Bridge validated() {
            try {
                if (!InetAddress.getByName(host).isLoopbackAddress()) {
                    throw new IllegalArgumentException("EntityBridge host must be loopback");
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("Invalid EntityBridge host", exception);
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid bridge port");
            if (preferredProtocol < 1 || preferredProtocol > 2) {
                throw new IllegalArgumentException("preferredProtocol must be 1 or 2");
            }
            if (maxFrameBytes < 1024 || maxFrameBytes > MAX_FRAME_BYTES) {
                throw new IllegalArgumentException("Invalid maxFrameBytes");
            }
            return new Bridge(
                    host,
                    port,
                    token,
                    preferredProtocol,
                    // Compatibility migration of the exact prior shipped default only.
                    maxFrameBytes == 1_048_576 ? MAX_FRAME_BYTES : maxFrameBytes,
                    positive(connectTimeoutMillis, 3_000),
                    positive(handshakeTimeoutMillis, 5_000),
                    positive(heartbeatIntervalMillis, 5_000),
                    positive(heartbeatTimeoutMillis, 15_000),
                    positive(reconnectInitialMillis, 1_000),
                    positive(reconnectMaximumMillis, 30_000));
        }

        public Bridge withToken(String replacement) {
            return new Bridge(
                    host, port, replacement, preferredProtocol, maxFrameBytes,
                    connectTimeoutMillis, handshakeTimeoutMillis,
                    heartbeatIntervalMillis, heartbeatTimeoutMillis,
                    reconnectInitialMillis, reconnectMaximumMillis);
        }

        public static Bridge defaults() {
            return new Bridge(
                    "127.0.0.1", 8765, "REPLACE_ME", 2, MAX_FRAME_BYTES,
                    3_000, 5_000, 5_000, 15_000, 1_000, 30_000);
        }
    }

    public record Survival(int drowningAirThreshold, int safeTicksBeforeResume) {
        private Survival validated() {
            return new Survival(
                    Math.max(240, Math.min(280, drowningAirThreshold)),
                    Math.max(1, Math.min(200, safeTicksBeforeResume)));
        }

        public static Survival defaults() {
            return new Survival(240, 20);
        }
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
