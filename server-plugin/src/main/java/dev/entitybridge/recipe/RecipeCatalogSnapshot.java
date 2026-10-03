package dev.entitybridge.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable deterministic recipe catalog plus bounded bridge framing. */
public final class RecipeCatalogSnapshot {
    public static final int SCHEMA_VERSION = 1;

    private final List<RecipeCatalogEntry> recipes;
    private final Map<String, Integer> exportedByType;
    private final Map<String, Integer> excludedByReason;
    private final int scanned;
    private final String catalogId;

    public RecipeCatalogSnapshot(
            List<RecipeCatalogEntry> recipes,
            Map<String, Integer> exportedByType,
            Map<String, Integer> excludedByReason,
            int scanned
    ) {
        Objects.requireNonNull(recipes, "recipes");
        if (recipes.size() > 100_000) {
            throw new IllegalArgumentException("recipe catalog exceeds 100000 entries");
        }
        ArrayList<RecipeCatalogEntry> ordered = new ArrayList<>(recipes);
        ordered.sort(Comparator.comparing(RecipeCatalogEntry::id)
                .thenComparing(RecipeCatalogEntry::canonicalJson));
        HashSet<String> identifiers = new HashSet<>();
        for (RecipeCatalogEntry recipe : ordered) {
            if (!identifiers.add(recipe.id())) {
                throw new IllegalArgumentException("duplicate recipe id: " + recipe.id());
            }
        }
        this.recipes = List.copyOf(ordered);
        this.exportedByType = validatedCounts(exportedByType, "exported type");
        this.excludedByReason = validatedCounts(excludedByReason, "exclusion reason");
        int exported = sum(this.exportedByType);
        int excluded = sum(this.excludedByReason);
        if (exported != this.recipes.size()) {
            throw new IllegalArgumentException("exported type counts do not match recipes");
        }
        if (scanned < 0 || scanned != exported + excluded) {
            throw new IllegalArgumentException("scanned count must equal exported plus excluded");
        }
        this.scanned = scanned;
        this.catalogId = digest(canonicalCatalog());
    }

    public List<RecipeCatalogEntry> recipes() {
        return recipes;
    }

    public Map<String, Integer> exportedByType() {
        return exportedByType;
    }

    public Map<String, Integer> excludedByReason() {
        return excludedByReason;
    }

    public int scanned() {
        return scanned;
    }

    public int exported() {
        return recipes.size();
    }

    public int excluded() {
        return sum(excludedByReason);
    }

    public String catalogId() {
        return catalogId;
    }

    public List<String> recipeIds() {
        return recipes.stream().map(RecipeCatalogEntry::id).toList();
    }

    /**
     * Splits the snapshot into deterministic response frames. Every returned
     * UTF-8 JSON object is guaranteed not to exceed {@code maxFrameBytes}.
     */
    public List<JsonObject> frames(String requestId, int maxFrameBytes) {
        return frames(requestId, maxFrameBytes, null);
    }

    public List<JsonObject> frames(
            String requestId,
            int maxFrameBytes,
            RecipeDiscoveryResult discovery
    ) {
        String checkedRequestId = Objects.requireNonNull(requestId, "requestId").trim();
        if (checkedRequestId.isEmpty() || checkedRequestId.length() > 128) {
            throw new IllegalArgumentException("requestId must contain 1 through 128 characters");
        }
        if (maxFrameBytes < 512 || maxFrameBytes > 16_777_216) {
            throw new IllegalArgumentException("maxFrameBytes is outside supported bounds");
        }

        ArrayList<List<RecipeCatalogEntry>> chunks = new ArrayList<>();
        ArrayList<RecipeCatalogEntry> current = new ArrayList<>();
        if (recipes.isEmpty()) chunks.add(List.of());
        for (RecipeCatalogEntry recipe : recipes) {
            ArrayList<RecipeCatalogEntry> candidate = new ArrayList<>(current);
            candidate.add(recipe);
            JsonObject worstCase = frame(
                    checkedRequestId, Integer.MAX_VALUE, Integer.MAX_VALUE, false,
                    candidate, discovery);
            if (utf8Size(worstCase) <= maxFrameBytes) {
                current.add(recipe);
                continue;
            }
            if (current.isEmpty()) {
                throw new IllegalArgumentException(
                        "one recipe exceeds the configured bridge frame size: " + recipe.id());
            }
            chunks.add(List.copyOf(current));
            current.clear();
            JsonObject single = frame(
                    checkedRequestId, Integer.MAX_VALUE, Integer.MAX_VALUE, false,
                    List.of(recipe), discovery);
            if (utf8Size(single) > maxFrameBytes) {
                throw new IllegalArgumentException(
                        "one recipe exceeds the configured bridge frame size: " + recipe.id());
            }
            current.add(recipe);
        }
        if (!current.isEmpty()) chunks.add(List.copyOf(current));

        ArrayList<JsonObject> result = new ArrayList<>(chunks.size());
        for (int part = 0; part < chunks.size(); part++) {
            JsonObject encoded = frame(
                    checkedRequestId, part, chunks.size(), part == chunks.size() - 1,
                    chunks.get(part), discovery);
            if (utf8Size(encoded) > maxFrameBytes) {
                throw new IllegalStateException("recipe catalog frame sizing invariant failed");
            }
            result.add(encoded);
        }
        return List.copyOf(result);
    }

    private JsonObject frame(
            String requestId,
            int part,
            int parts,
            boolean complete,
            List<RecipeCatalogEntry> contents,
            RecipeDiscoveryResult discovery
    ) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "recipe_catalog");
        frame.addProperty("schema", SCHEMA_VERSION);
        frame.addProperty("requestId", requestId);
        frame.addProperty("catalogId", catalogId);
        frame.addProperty("part", part);
        frame.addProperty("parts", parts);
        frame.addProperty("complete", complete);
        frame.add("summary", summary());
        if (discovery != null) frame.add("discovery", discovery.toJson());
        JsonArray encoded = new JsonArray();
        contents.forEach(recipe -> encoded.add(recipe.toJson()));
        frame.add("recipes", encoded);
        return frame;
    }

    private JsonObject canonicalCatalog() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        root.add("summary", summary());
        JsonArray encoded = new JsonArray();
        recipes.forEach(recipe -> encoded.add(recipe.toJson()));
        root.add("recipes", encoded);
        return root;
    }

    private JsonObject summary() {
        JsonObject summary = new JsonObject();
        summary.addProperty("scanned", scanned);
        summary.addProperty("exported", exported());
        summary.addProperty("excluded", excluded());
        summary.add("exportedByType", countObject(exportedByType));
        summary.add("excludedByReason", countObject(excludedByReason));
        return summary;
    }

    private static JsonObject countObject(Map<String, Integer> counts) {
        JsonObject encoded = new JsonObject();
        counts.forEach(encoded::addProperty);
        return encoded;
    }

    private static Map<String, Integer> validatedCounts(Map<String, Integer> counts, String label) {
        TreeMap<String, Integer> ordered = new TreeMap<>();
        if (counts == null) return Map.of();
        counts.forEach((key, value) -> {
            if (key == null || !key.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException(label + " key is invalid");
            }
            if (value == null || value < 0) {
                throw new IllegalArgumentException(label + " count is invalid");
            }
            if (value > 0) ordered.put(key, value);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private static int sum(Map<String, Integer> values) {
        int total = 0;
        for (int value : values.values()) total = Math.addExact(total, value);
        return total;
    }

    private static int utf8Size(JsonObject frame) {
        return frame.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static String digest(JsonObject catalog) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(catalog.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) encoded.append(String.format("%02x", value & 0xff));
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
