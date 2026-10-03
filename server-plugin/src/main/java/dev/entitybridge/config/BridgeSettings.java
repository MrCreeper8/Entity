package dev.entitybridge.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record BridgeSettings(
        String ownerName,
        String requiredPermission,
        String host,
        int port,
        String authToken,
        int maxFrameBytes,
        int handshakeTimeoutSeconds,
        int pendingCommandLimit,
        long pendingCommandTtlMillis,
        long targetUpdateTicks,
        String defaultMode,
        Set<String> controllerNames
) {
    public static final int ABSOLUTE_MAX_FRAME_BYTES = 2 * 1024 * 1024;

    public BridgeSettings {
        LinkedHashSet<String> normalizedControllers = new LinkedHashSet<>();
        if (controllerNames != null) {
            for (String controller : controllerNames) {
                if (controller == null) continue;
                String normalized = controller.trim();
                if (!normalized.isEmpty()) normalizedControllers.add(normalized);
            }
        }
        controllerNames = Set.copyOf(normalizedControllers);
    }

    /** Source-compatible constructor for callers created before trusted controllers existed. */
    public BridgeSettings(
            String ownerName,
            String requiredPermission,
            String host,
            int port,
            String authToken,
            int maxFrameBytes,
            int handshakeTimeoutSeconds,
            int pendingCommandLimit,
            long pendingCommandTtlMillis,
            long targetUpdateTicks,
            String defaultMode
    ) {
        this(
                ownerName, requiredPermission, host, port, authToken, maxFrameBytes,
                handshakeTimeoutSeconds, pendingCommandLimit, pendingCommandTtlMillis,
                targetUpdateTicks, defaultMode, Set.of()
        );
    }

    /** Source-compatible constructor for protocol-1 tests and embedders. */
    public BridgeSettings(
            String ownerName,
            String requiredPermission,
            String host,
            int port,
            String authToken,
            int maxFrameBytes,
            int handshakeTimeoutSeconds,
            int pendingCommandLimit,
            long pendingCommandTtlMillis,
            long targetUpdateTicks
    ) {
        this(
                ownerName, requiredPermission, host, port, authToken, maxFrameBytes,
                handshakeTimeoutSeconds, pendingCommandLimit, pendingCommandTtlMillis,
                targetUpdateTicks, "player", Set.of()
        );
    }

    public static BridgeSettings load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        String token = config.getString("bridge.auth-token", "").trim();
        if (token.isEmpty()) {
            token = generateToken();
            config.set("bridge.auth-token", token);
            plugin.saveConfig();
            plugin.getLogger().warning(
                    "Generated bridge.auth-token in config.yml. Copy it to Entity's client configuration."
            );
        }

        String owner = config.getString("owner-name", "CHANGE_ME").trim();
        Set<String> controllers = new LinkedHashSet<>(config.getStringList("controller-names"));
        String permission = config.getString("required-permission", "entitybridge.control").trim();
        String host = config.getString("bridge.host", "127.0.0.1").trim();
        int port = clamp(config.getInt("bridge.port", 8765), 1, 65_535);
        int maxFrameBytes = compatibleFrameLimit(config.getInt("bridge.max-frame-bytes", ABSOLUTE_MAX_FRAME_BYTES));
        int handshakeTimeout = clamp(config.getInt("bridge.handshake-timeout-seconds", 10), 1, 60);
        int pendingLimit = clamp(config.getInt("bridge.pending-command-limit", 512), 1, 4_096);
        long pendingTtlSeconds = clamp(config.getLong("bridge.pending-command-ttl-seconds", 30L), 1L, 3_600L);
        long targetUpdateTicks = clamp(config.getLong("target-update-ticks", 5L), 1L, 1_200L);
        String mode = config.getString("default-mode", "player").trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("oracle")) {
            mode = "player";
        }
        if (permission.isEmpty()) {
            permission = "entitybridge.control";
        }
        return new BridgeSettings(
                owner, permission, host, port, token, maxFrameBytes, handshakeTimeout,
                pendingLimit, pendingTtlSeconds * 1_000L, targetUpdateTicks, mode, controllers
        );
    }

    public boolean ownerConfigured() {
        return !ownerName.isBlank() && !ownerName.equalsIgnoreCase("CHANGE_ME");
    }

    /** Promote only the old shipped default, retaining deliberately smaller custom limits. */
    public static int compatibleFrameLimit(int configured) {
        return clamp(configured == 1_048_576 ? ABSOLUTE_MAX_FRAME_BYTES : configured,
                1_024, ABSOLUTE_MAX_FRAME_BYTES);
    }

    public boolean isPrimaryOwner(String playerName) {
        return playerName != null && playerName.equalsIgnoreCase(ownerName);
    }

    public boolean isTrustedController(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        return controllerNames.stream().anyMatch(playerName::equalsIgnoreCase);
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
