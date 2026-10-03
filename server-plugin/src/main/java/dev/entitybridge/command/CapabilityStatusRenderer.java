package dev.entitybridge.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Fail-closed player rendering for the connected client's machine-readable frame. */
public final class CapabilityStatusRenderer {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_TECHNIQUES = 12;

    private CapabilityStatusRenderer() {
    }

    public static List<String> render(JsonObject frame) {
        if (frame == null) {
            return List.of("Entity has not reported a client capability frame yet.");
        }
        try {
            if (!text(frame, "type").equals("capabilities")
                    || integer(frame, "schema") != SCHEMA_VERSION) {
                return malformed();
            }
            String selection = text(frame, "selection");
            String reason = text(frame, "reason");
            JsonElement techniquesElement = frame.get("techniques");
            if (techniquesElement == null || !techniquesElement.isJsonArray()) {
                return malformed();
            }
            JsonArray techniques = techniquesElement.getAsJsonArray();
            if (techniques.isEmpty() || techniques.size() > MAX_TECHNIQUES) {
                return malformed();
            }
            ArrayList<String> lines = new ArrayList<>();
            lines.add("Selected fall technique: " + selection + " — " + reason);
            for (JsonElement element : techniques) {
                if (!element.isJsonObject()) return malformed();
                JsonObject technique = element.getAsJsonObject();
                String id = text(technique, "id");
                boolean supported = bool(technique, "supported");
                boolean available = bool(technique, "available");
                String blocker = textAllowEmpty(technique, "blocker");
                if (!supported && available) return malformed();
                if (available != blocker.isEmpty()) return malformed();
                String requirements = requirements(technique);
                String state = !supported
                        ? "unsupported"
                        : available ? "available" : "unavailable (" + blocker + ")";
                lines.add(id + ": " + state + "; requires " + requirements);
            }
            return List.copyOf(lines);
        } catch (IllegalArgumentException invalid) {
            return malformed();
        }
    }

    private static String requirements(JsonObject technique) {
        JsonElement element = technique.get("requirements");
        if (element == null || !element.isJsonArray()) throw new IllegalArgumentException();
        ArrayList<String> values = new ArrayList<>();
        for (JsonElement requirement : element.getAsJsonArray()) {
            if (!requirement.isJsonPrimitive()
                    || !requirement.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException();
            }
            String value = requirement.getAsString().trim();
            if (value.isEmpty() || value.length() > 96) throw new IllegalArgumentException();
            values.add(value);
        }
        if (values.isEmpty()) throw new IllegalArgumentException();
        return String.join(", ", values);
    }

    private static String text(JsonObject object, String key) {
        String value = textAllowEmpty(object, key);
        if (value.isEmpty()) throw new IllegalArgumentException();
        return value;
    }

    private static String textAllowEmpty(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException();
        }
        String value = element.getAsString().trim();
        if (value.length() > 512) throw new IllegalArgumentException();
        return value;
    }

    private static int integer(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException();
        }
        return element.getAsInt();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException();
        }
        return element.getAsBoolean();
    }

    private static List<String> malformed() {
        return List.of(
                "Entity client capability frame is malformed; treat all reported techniques as unavailable.");
    }
}
