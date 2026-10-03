package dev.entitybridge.state;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Resolves Paper-owned gameplay state from the canonical Overworld identity. */
public final class WorldStatePathResolver {
    public static final String CANONICAL_OVERWORLD_KEY = "minecraft:overworld";

    private WorldStatePathResolver() {
    }

    public static WorldStatePaths resolve(
            Path pluginDataFolder,
            Map<String, UUID> authoritativeWorldIdentities
    ) {
        Path dataFolder = Objects.requireNonNull(pluginDataFolder, "pluginDataFolder")
                .toAbsolutePath()
                .normalize();
        Map<String, UUID> identities = Objects.requireNonNull(
                authoritativeWorldIdentities, "authoritativeWorldIdentities");
        UUID overworldId = identities.get(CANONICAL_OVERWORLD_KEY);
        if (overworldId == null) {
            throw new IllegalStateException(
                    "Paper did not expose an authoritative minecraft:overworld UUID; "
                            + "world-owned Entity state will not be opened");
        }

        Path directory = dataFolder.resolve("worlds").resolve(overworldId.toString()).normalize();
        if (!directory.startsWith(dataFolder)) {
            throw new IllegalStateException(
                    "Resolved world-state directory escaped the plugin data folder");
        }
        return new WorldStatePaths(
                overworldId,
                directory,
                directory.resolve("missions.json"),
                directory.resolve("delivery-transactions.json"),
                directory.resolve("protected-areas.json"));
    }

    public record WorldStatePaths(
            UUID overworldId,
            Path directory,
            Path missions,
            Path deliveries,
            Path protectedAreas
    ) {
        public WorldStatePaths {
            Objects.requireNonNull(overworldId, "overworldId");
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(missions, "missions");
            Objects.requireNonNull(deliveries, "deliveries");
            Objects.requireNonNull(protectedAreas, "protectedAreas");
        }
    }
}
