package dev.entitybridge.ai;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;

/** Bounded atomic dashboard heartbeat; no inference, environment reads or provider probes. */
public final class AiStatusReceipt {
    private final Path file;
    private final long processId;
    private final Clock clock;
    private boolean stopped;
    private AiStatus.Provider provider = AiStatus.Provider.NONE;

    public AiStatusReceipt(Path pluginData) {
        this(pluginData, ProcessHandle.current().pid(), Clock.systemUTC());
    }
    AiStatusReceipt(Path pluginData, long processId, Clock clock) {
        this.file = pluginData.resolve("ai-status.json");
        this.processId = processId;
        this.clock = clock;
    }
    public synchronized void publish(AiStatus.Observation observation) throws IOException {
        if (stopped) return;
        provider = observation.backend().provider();
        write(observation);
    }
    /** Fences any racing periodic writer before writing the final stopped observation. */
    public synchronized void stop() throws IOException {
        stopped = true;
        write(new AiStatus.Observation(false, new AiStatus.Snapshot(provider, AiStatus.State.STOPPED, AiStatus.Detail.PLUGIN_STOPPED)));
    }
    private void write(AiStatus.Observation observation) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("processId", processId);
        json.addProperty("observedAtUtc", clock.instant().toString());
        json.addProperty("enabled", observation.enabled());
        json.addProperty("provider", observation.backend().providerName());
        json.addProperty("state", observation.backend().stateName());
        json.addProperty("detail", observation.backend().detail().text());
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2048) throw new IOException("AI status exceeded its safe receipt limit.");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".ai-status-", ".tmp");
        try {
            Files.write(temporary, bytes);
            // Fail closed on filesystems without atomic replacement; never expose a partial receipt.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
