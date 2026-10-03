package dev.entity.client.runtime;

import java.util.Locale;

/** User-selected presentation mode for the dedicated Entity Minecraft client. */
public enum HeadlessRuntimeMode {
    VISIBLE,
    BACKGROUND;

    public boolean background() {
        return this == BACKGROUND;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves the early boot mode without allowing a malformed source to
     * silently select background operation. Explicit JVM and environment
     * overrides precede the durable instance file.
     */
    public static HeadlessRuntimeMode resolve(
            String systemProperty,
            String environment,
            String instanceFile) {
        for (String candidate : new String[]{systemProperty, environment, instanceFile}) {
            if (candidate == null || candidate.isBlank()) continue;
            String normalized = candidate.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("background") || normalized.equals("headless")) {
                return BACKGROUND;
            }
            if (normalized.equals("visible")) {
                return VISIBLE;
            }
        }
        return VISIBLE;
    }
}
