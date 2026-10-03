package dev.entity.core.port;

import dev.entity.core.control.ControlLease;
import dev.entity.core.survival.WorldSnapshot;

import java.util.Map;

/** Fabric/Minecraft implementation boundary. The core imports no game classes. */
public interface PlatformPort {
    WorldSnapshot observe(long nowMillis);

    void execute(ControlLease lease, BodyIntent intent);

    void cancelControls(long invalidatedEpoch, String reason);

    void publishStatus(String humanReadableStatus);

    record BodyIntent(String type, Map<String, String> arguments) {
        public BodyIntent {
            arguments = Map.copyOf(arguments);
        }
    }
}
