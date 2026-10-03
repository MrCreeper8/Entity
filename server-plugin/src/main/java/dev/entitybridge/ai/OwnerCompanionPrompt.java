package dev.entitybridge.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Owner-authored text, kept separate from program-owned action and output contracts. */
public final class OwnerCompanionPrompt {
    private OwnerCompanionPrompt() { }
    private static final class Content {
        static final String TEXT = load();
    }
    public static String text() { return Content.TEXT; }
    private static String load() {
        try (var input = OwnerCompanionPrompt.class.getResourceAsStream("/entity-companion-owner.txt")) {
            if (input == null) throw new IllegalStateException("Owner companion prompt resource is missing");
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            // The resource's final file newline is not part of the owner's chat text.
            return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot read owner companion prompt", failed);
        }
    }
}
