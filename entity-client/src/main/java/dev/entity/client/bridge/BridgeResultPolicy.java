package dev.entity.client.bridge;

/** Keeps outbound command results inside the bridge server's 512-character limit. */
public final class BridgeResultPolicy {
    public static final int MAX_MESSAGE_CHARACTERS = 500;

    private BridgeResultPolicy() {}

    public static String boundedMessage(String value) {
        String message = value == null ? "" : value;
        if (message.length() <= MAX_MESSAGE_CHARACTERS) return message;
        return message.substring(0, MAX_MESSAGE_CHARACTERS - 3) + "...";
    }
}
