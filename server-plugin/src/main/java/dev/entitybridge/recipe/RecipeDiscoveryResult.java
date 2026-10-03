package dev.entitybridge.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

/** Main-thread Paper result for optionally unlocking exported recipes on Entity. */
public record RecipeDiscoveryResult(
        boolean applied,
        String player,
        int exportedKeyCount,
        int discoverableKeyCount,
        int newlyDiscovered,
        int undiscoverableCount,
        List<String> undiscoverableSample,
        String code,
        String message
) {
    public RecipeDiscoveryResult {
        player = Objects.requireNonNullElse(player, "");
        code = Objects.requireNonNullElse(code, "unknown");
        message = Objects.requireNonNullElse(message, "");
        undiscoverableSample = undiscoverableSample == null
                ? List.of()
                : List.copyOf(undiscoverableSample);
        if (exportedKeyCount < 0 || discoverableKeyCount < 0 || newlyDiscovered < 0
                || undiscoverableCount < 0 || discoverableKeyCount > exportedKeyCount
                || newlyDiscovered > discoverableKeyCount
                || undiscoverableCount != exportedKeyCount - discoverableKeyCount
                || undiscoverableSample.size() > Math.min(16, undiscoverableCount)) {
            throw new IllegalArgumentException("invalid recipe discovery counts");
        }
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("requested", true);
        json.addProperty("applied", applied);
        json.addProperty("player", player);
        json.addProperty("exportedKeyCount", exportedKeyCount);
        json.addProperty("discoverableKeyCount", discoverableKeyCount);
        json.addProperty("newlyDiscovered", newlyDiscovered);
        json.addProperty("undiscoverableCount", undiscoverableCount);
        json.addProperty("keyCoverageComplete", undiscoverableCount == 0);
        JsonArray sample = new JsonArray();
        undiscoverableSample.forEach(sample::add);
        json.add("undiscoverableSample", sample);
        json.addProperty("undiscoverableSampleTruncated",
                undiscoverableSample.size() < undiscoverableCount);
        json.addProperty("code", code);
        json.addProperty("message", message);
        return json;
    }

    public static RecipeDiscoveryResult unavailable(
            String player, int exportedKeyCount, String code, String message) {
        return new RecipeDiscoveryResult(
                false, player, exportedKeyCount, 0, 0, exportedKeyCount,
                List.of(), code, message);
    }
}
