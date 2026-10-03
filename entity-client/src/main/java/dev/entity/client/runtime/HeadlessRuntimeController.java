package dev.entity.client.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.entity.core.build.BuildIdentity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Maintains the product-owned background window boundary and publishes the
 * heartbeat consumed by the shipped runtime launcher.
 */
public final class HeadlessRuntimeController implements AutoCloseable {
    public static final String MODE_FILE = "runtime-mode";
    public static final String STATUS_FILE = "runtime-status.json";
    public static final String STOP_REQUEST_FILE = "stop-request";

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();
    private static final long HEARTBEAT_INTERVAL_MILLIS = 1_000L;

    private final MinecraftClient minecraft;
    private final Path dataDirectory;
    private final Path statusPath;
    private final Path stopRequestPath;
    private final HeadlessRuntimeMode mode;
    private final BooleanSupplier bridgeConnected;
    private final Logger logger;
    private final Instant startedAt = Instant.now();
    private final long processId = ProcessHandle.current().pid();
    private final Instant processStartedAt = ProcessHandle.current().info()
            .startInstant().orElse(startedAt);

    private long nextHeartbeatAt;
    private boolean stopRequested;
    private boolean statusFailureLogged;
    private boolean backgroundAudioMuted;
    private boolean closed;

    public HeadlessRuntimeController(
            MinecraftClient minecraft,
            Path dataDirectory,
            HeadlessRuntimeMode mode,
            BooleanSupplier bridgeConnected,
            Logger logger) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.statusPath = dataDirectory.resolve(STATUS_FILE);
        this.stopRequestPath = dataDirectory.resolve(STOP_REQUEST_FILE);
        this.mode = Objects.requireNonNull(mode, "mode");
        this.bridgeConnected = Objects.requireNonNull(bridgeConnected, "bridgeConnected");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void start() {
        if (mode.background()) maintainBackgroundBoundary();
        writeStatus("starting", false);
        logger.info("Entity2 runtime mode selected: {}", mode.wireName());
    }

    public void tick() {
        if (closed) return;
        if (mode.background()) maintainBackgroundBoundary();
        if (!stopRequested && Files.isRegularFile(stopRequestPath)) {
            stopRequested = true;
            writeStatus("stopping", true);
            logger.info("Entity2 runtime received a graceful stop request");
            minecraft.scheduleStop();
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= nextHeartbeatAt) {
            writeStatus("running", false);
            nextHeartbeatAt = now + HEARTBEAT_INTERVAL_MILLIS;
        }
    }

    public HeadlessRuntimeMode mode() {
        return mode;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        writeStatus("stopped", stopRequested);
    }

    private void maintainBackgroundBoundary() {
        var soundManager = minecraft.getSoundManager();
        if (soundManager != null) {
            if (!backgroundAudioMuted) {
                soundManager.stopAll();
            }
            soundManager.updateSoundVolume(SoundCategory.MASTER, 0.0F);
            backgroundAudioMuted = true;
        }
        if (minecraft.getWindow() != null) {
            long handle = minecraft.getWindow().getHandle();
            if (GlfwWindowBridge.visible(handle)) {
                GlfwWindowBridge.hide(handle);
            }
        }
        if (minecraft.mouse != null && minecraft.mouse.isCursorLocked()) {
            minecraft.mouse.unlockCursor();
        }
        if (minecraft.isWindowFocused()) {
            minecraft.onWindowFocusChanged(false);
        }
    }

    private void writeStatus(String phase, boolean acknowledgedStop) {
        try {
            Files.createDirectories(dataDirectory);
            BuildIdentity identity = BuildIdentity.current();
            JsonObject status = new JsonObject();
            status.addProperty("schemaVersion", 1);
            status.addProperty("mode", mode.wireName());
            status.addProperty("phase", phase);
            status.addProperty("processId", processId);
            status.addProperty("processStartedAtUtc", processStartedAt.toString());
            status.addProperty("startedAtUtc", startedAt.toString());
            status.addProperty("heartbeatAtUtc", Instant.now().toString());
            status.addProperty("version", identity.version());
            status.addProperty("sourceCommit", identity.sourceCommit());
            status.addProperty("buildId", identity.buildId());
            status.addProperty("sourceState", identity.sourceState());
            status.addProperty("windowVisible", windowVisible());
            status.addProperty("windowFocused", minecraft.isWindowFocused());
            status.addProperty("cursorLocked",
                    minecraft.mouse != null && minecraft.mouse.isCursorLocked());
            status.addProperty("audioMuted", mode.background() && backgroundAudioMuted);
            status.addProperty("gameReady",
                    minecraft.player != null && minecraft.world != null);
            status.addProperty("bridgeConnected", bridgeConnected.getAsBoolean());
            status.addProperty("stopRequestAcknowledged", acknowledgedStop);

            Path temporary = statusPath.resolveSibling(statusPath.getFileName() + ".tmp");
            Files.writeString(
                    temporary,
                    GSON.toJson(status) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, statusPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, statusPath, StandardCopyOption.REPLACE_EXISTING);
            }
            statusFailureLogged = false;
        } catch (IOException | RuntimeException error) {
            if (!statusFailureLogged) {
                logger.warn("Could not publish Entity2 runtime status at {}",
                        statusPath, error);
                statusFailureLogged = true;
            }
        }
    }

    private boolean windowVisible() {
        if (minecraft.getWindow() == null) return false;
        long handle = minecraft.getWindow().getHandle();
        return GlfwWindowBridge.visible(handle);
    }
}
