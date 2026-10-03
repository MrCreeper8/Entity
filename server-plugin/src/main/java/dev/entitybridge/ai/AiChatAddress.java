package dev.entitybridge.ai;

import java.util.Optional;
import java.util.regex.Pattern;

/** An explicit address, never a substring or a second intent parser. */
public final class AiChatAddress {
    private static final Pattern ADDRESS = Pattern.compile("(?i)^(?:entity|ent|e)(?:\\s*,\\s*|\\s+|$)(.*)$");
    private AiChatAddress() { }

    public static Optional<String> request(String message) {
        if (message == null || message.chars().anyMatch(c -> c < 32)) return Optional.empty();
        var match = ADDRESS.matcher(message.strip());
        return match.matches() ? Optional.of(match.group(1).strip()) : Optional.empty();
    }
}
