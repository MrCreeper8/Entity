package dev.entity.client.runtime;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Paper-authoritative identity and filesystem namespace for one Minecraft save. */
public record WorldStateScope(UUID overworldIdentity) {
    public static final String OVERWORLD_DIMENSION = "minecraft:overworld";
    public static final String DIRECTORY_NAME = "world-state";

    public WorldStateScope {
        overworldIdentity = Objects.requireNonNull(overworldIdentity, "overworldIdentity");
    }

    public static WorldStateScope parse(String identity) {
        String value = Objects.requireNonNullElse(identity, "").trim();
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("world identity is not a canonical UUID");
        }
        return new WorldStateScope(parsed);
    }

    public static Optional<WorldStateScope> from(Map<String, String> worldIdentities) {
        Objects.requireNonNull(worldIdentities, "worldIdentities");
        String identity = worldIdentities.get(OVERWORLD_DIMENSION);
        if (identity == null || identity.isBlank()) return Optional.empty();
        try {
            return Optional.of(parse(identity));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public String key() {
        return overworldIdentity.toString();
    }

    public Path directory(Path installationDirectory) {
        Path root = Objects.requireNonNull(installationDirectory, "installationDirectory")
                .toAbsolutePath().normalize();
        Path directory = root.resolve(DIRECTORY_NAME).resolve(key()).normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("world state directory escaped the installation root");
        }
        return directory;
    }
}
