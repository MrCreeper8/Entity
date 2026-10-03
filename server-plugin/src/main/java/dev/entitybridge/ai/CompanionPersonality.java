package dev.entitybridge.ai;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;

/** Owner-selected conversational style only; never gameplay authority or chat memory. */
public final class CompanionPersonality {
    private static final Map<String, String> PRESETS = Map.of(
            "chaotic", "Chaotic, blunt, profane and expressive: a reactive gaming buddy. Use strong language freely "
                    + "when it fits; playful insults and absurd opinions are welcome. Not British dry humour, "
                    + "canned punchlines or a joke on every reply. Stay grounded in what actually happened.",
            "friendly", "Friendly, warm and relaxed: a supportive gaming buddy. Be concise, react naturally to real "
                    + "events and use light humour only when it fits. No forced jokes or routine assistant disclaimers.",
            "neutral", "Neutral, calm and matter-of-fact. Be concise and direct, acknowledge real events without "
                    + "theatrics, and avoid forced jokes or routine assistant disclaimers.");
    private final String preset;
    private final String description;

    private CompanionPersonality(String preset, String description) {
        this.preset = preset;
        this.description = description;
    }

    public static CompanionPersonality select(String preset, String custom) {
        String selected = preset == null || preset.isBlank() ? "chaotic" : preset.strip().toLowerCase(Locale.ROOT);
        if (selected.equals("owner")) {
            if (custom != null && !custom.isBlank()) throw new IllegalArgumentException("owner prompt cannot have a replacement description");
            return new CompanionPersonality(selected, OwnerCompanionPrompt.text());
        }
        if (selected.equals("custom")) {
            if (custom == null || custom.isBlank() || custom.length() > 600
                    || custom.codePoints().anyMatch(c -> c == 167 || Character.getType(c) == Character.CONTROL
                    || Character.getType(c) == Character.FORMAT))
                throw new IllegalArgumentException("personality description must be 1-600 characters without control or formatting characters");
            return new CompanionPersonality(selected, custom.strip());
        }
        if (!PRESETS.containsKey(selected)) throw new IllegalArgumentException("unknown personality preset");
        if (custom != null && !custom.isBlank()) throw new IllegalArgumentException("custom description requires custom preset");
        return new CompanionPersonality(selected, PRESETS.get(selected));
    }

    public String preset() { return preset; }
    public String description() { return description; }
    public JsonObject facts() {
        JsonObject result = new JsonObject();
        result.addProperty("preset", preset);
        result.addProperty("description", description);
        return result;
    }
}
