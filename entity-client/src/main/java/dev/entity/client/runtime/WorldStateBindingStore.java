package dev.entity.client.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Atomic active-save pointer plus non-destructive preservation of legacy global state. */
public final class WorldStateBindingStore {
    public static final String ACTIVE_FILE = "active-world";
    public static final String UNBOUND_DIRECTORY = "unbound";
    private static final String LEGACY_DIRECTORY = "legacy-unscoped";
    private static final String LEGACY_MANIFEST = "preserved-files.txt";
    private static final List<String> LEGACY_WORLD_FILES = List.of(
            "missions.json",
            "task-plans.json",
            "autonomy.json",
            "home-economy.bin",
            "home-water-fill.bin",
            "field-kit.bin",
            "death-recovery.json",
            "protected-areas.json",
            "door-passage.bin");

    private final Path installationDirectory;
    private final Path worldStateRoot;
    private final Path activePath;
    private final Path temporaryPath;

    public WorldStateBindingStore(Path installationDirectory) {
        this.installationDirectory = Objects.requireNonNull(
                installationDirectory, "installationDirectory").toAbsolutePath().normalize();
        this.worldStateRoot = this.installationDirectory.resolve(
                WorldStateScope.DIRECTORY_NAME).normalize();
        this.activePath = worldStateRoot.resolve(ACTIVE_FILE);
        this.temporaryPath = worldStateRoot.resolve(ACTIVE_FILE + ".tmp");
    }

    public synchronized LoadResult load() {
        if (!Files.exists(activePath)) return new LoadResult(Optional.empty(), "");
        try {
            long size = Files.size(activePath);
            if (size < 1L || size > 128L) {
                throw new IOException("active world pointer has an invalid size");
            }
            WorldStateScope scope = WorldStateScope.parse(
                    Files.readString(activePath, StandardCharsets.UTF_8).trim());
            return new LoadResult(Optional.of(scope), "");
        } catch (IOException | IllegalArgumentException failure) {
            return new LoadResult(
                    Optional.empty(),
                    "Ignoring an unreadable active-world pointer; no world state will load: "
                            + Objects.requireNonNullElse(
                            failure.getMessage(), failure.getClass().getSimpleName()));
        }
    }

    public Path unboundDirectory() {
        return worldStateRoot.resolve(UNBOUND_DIRECTORY).normalize();
    }

    /**
     * Activates a namespace only after its directory and a legacy backup exist.
     * Legacy files remain at their original locations as well, so this upgrade
     * never guesses their world and never destroys the only copy.
     */
    public synchronized Path activate(WorldStateScope scope) throws IOException {
        Objects.requireNonNull(scope, "scope");
        Files.createDirectories(worldStateRoot);
        preserveLegacyStateOnce();
        Path directory = scope.directory(installationDirectory);
        Files.createDirectories(directory);
        Files.writeString(
                temporaryPath,
                scope.key() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(
                    temporaryPath,
                    activePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporaryPath, activePath, StandardCopyOption.REPLACE_EXISTING);
        }
        return directory;
    }

    private void preserveLegacyStateOnce() throws IOException {
        Path backup = worldStateRoot.resolve(LEGACY_DIRECTORY);
        Path manifest = backup.resolve(LEGACY_MANIFEST);
        if (Files.isRegularFile(manifest)) return;
        Files.createDirectories(backup);
        ArrayList<String> preserved = new ArrayList<>();
        try (var children = Files.list(installationDirectory)) {
            for (Path source : children.sorted().toList()) {
                if (!Files.isRegularFile(source)) continue;
                String name = source.getFileName().toString();
                if (!legacyWorldFile(name)) continue;
                Files.copy(source, backup.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                preserved.add(name);
            }
        }
        Files.writeString(
                manifest,
                String.join(System.lineSeparator(), preserved) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static boolean legacyWorldFile(String name) {
        for (String base : LEGACY_WORLD_FILES) {
            if (name.equals(base) || name.equals(base + ".tmp")) return true;
        }
        return name.startsWith("functional-routes-")
                && (name.endsWith(".bin") || name.endsWith(".bin.tmp"));
    }

    public record LoadResult(Optional<WorldStateScope> scope, String warning) {
        public LoadResult {
            scope = Objects.requireNonNull(scope, "scope");
            warning = Objects.requireNonNullElse(warning, "");
            if (scope.isPresent() && !warning.isBlank()) {
                throw new IllegalArgumentException("a valid binding cannot carry a warning");
            }
        }
    }
}
