package dev.entitybridge.safety;

import dev.entitybridge.config.BridgeSettings;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Live owner/controller identity shared by commands and safety listeners. */
public final class TrustedIdentityRegistry {
    private final String ownerName;
    private final LinkedHashMap<String, String> controllers = new LinkedHashMap<>();
    private long revision = 1L;
    private boolean controllersLocked;

    public TrustedIdentityRegistry(BridgeSettings settings, boolean controllersLocked) {
        BridgeSettings checked = Objects.requireNonNull(settings, "settings");
        ownerName = checked.ownerConfigured() ? checked.ownerName() : "";
        checked.controllerNames().forEach(name ->
                controllers.put(normalize(name), name.trim()));
        this.controllersLocked = controllersLocked;
    }

    public synchronized Snapshot snapshot() {
        LinkedHashSet<String> protectedNames = new LinkedHashSet<>();
        if (!ownerName.isBlank()) protectedNames.add(ownerName);
        protectedNames.addAll(controllers.values());
        return new Snapshot(
                revision,
                ownerName,
                Set.copyOf(controllers.values()),
                Set.copyOf(protectedNames),
                controllersLocked);
    }

    public synchronized boolean isProtected(String playerName) {
        String key = normalize(playerName);
        return !key.isEmpty()
                && (key.equals(normalize(ownerName)) || controllers.containsKey(key));
    }

    public synchronized boolean allowController(String playerName) {
        String name = requireName(playerName);
        String previous = controllers.put(normalize(name), name);
        if (name.equals(previous)) return false;
        revision++;
        return true;
    }

    public synchronized String denyController(String playerName) {
        String removed = controllers.remove(normalize(playerName));
        if (removed != null) revision++;
        return removed;
    }

    public synchronized boolean setControllersLocked(boolean locked) {
        if (controllersLocked == locked) return false;
        controllersLocked = locked;
        revision++;
        return true;
    }

    private static String requireName(String playerName) {
        String value = Objects.requireNonNullElse(playerName, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("player name is required");
        return value;
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT);
    }

    public record Snapshot(
            long revision,
            String ownerName,
            Set<String> controllers,
            Set<String> protectedPlayerNames,
            boolean controllersLocked) {
        public Snapshot {
            ownerName = Objects.requireNonNullElse(ownerName, "");
            controllers = Set.copyOf(Objects.requireNonNull(controllers, "controllers"));
            protectedPlayerNames = Set.copyOf(
                    Objects.requireNonNull(protectedPlayerNames, "protectedPlayerNames"));
        }
    }
}
