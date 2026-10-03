package dev.entity.client.blueprint;

import baritone.api.BaritoneAPI;
import baritone.api.schematic.ISchematicSystem;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Author-credited static assets: no network, world writes or downloaded executable code. */
final class BlueprintBundledLibrary {
    static final String RESOURCE_ROOT = "/entity2/blueprints/prefab/";
    private static final Map<String, JsonObject> ENTRIES = readManifest();
    private static final Map<String, BlueprintDesign> VALIDATED = new LinkedHashMap<>();

    private BlueprintBundledLibrary() { }

    static List<String> ids() { return List.copyOf(ENTRIES.keySet()); }

    static String description(String id) {
        JsonObject entry = ENTRIES.get(id);
        return entry == null ? id : entry.get("name").getAsString() + " (Brian Wuest/Prefab, MIT). "
                + entry.get("disclosure").getAsString();
    }

    static String canonicalId(String requested) {
        if (ENTRIES.containsKey(requested)) return requested;
        for (var entry : ENTRIES.entrySet()) {
            for (var alias : entry.getValue().getAsJsonArray("aliases")) {
                if (alias.getAsString().equals(requested)) return entry.getKey();
            }
        }
        return null;
    }

    static BlueprintDesign load(String id) throws IOException {
        return load(id, () -> BaritoneAPI.getProvider().getSchematicSystem());
    }

    static synchronized BlueprintDesign load(String id, Supplier<ISchematicSystem> formats) throws IOException {
        if (VALIDATED.containsKey(id)) return VALIDATED.get(id);
        JsonObject entry = ENTRIES.get(id);
        if (entry == null) throw new IOException("Unknown packaged blueprint: " + id);
        byte[] bytes = resource(entry.get("nativeFile").getAsString(), BlueprintImport.MAX_COMPRESSED);
        if (!hash(bytes).equals(entry.get("nativeSha256").getAsString()))
            throw new IOException("Packaged blueprint file hash mismatch: " + id);
        String source = "Prefab by Brian Wuest and contributors (MIT); "
                + entry.get("source").getAsString() + "; " + entry.get("disclosure").getAsString();
        BlueprintDesign parsed = BlueprintImport.parse(bytes, "schem", source,
                entry.get("name").getAsString(), formats);
        if (!parsed.sha256().equals(entry.get("designSha256").getAsString())
                || parsed.width() != entry.get("width").getAsInt()
                || parsed.height() != entry.get("height").getAsInt()
                || parsed.length() != entry.get("length").getAsInt())
            throw new IOException("Packaged blueprint geometry hash mismatch: " + id);
        Map<String, Integer> expectedMaterials = new LinkedHashMap<>();
        entry.getAsJsonObject("materials").entrySet().forEach(value ->
                expectedMaterials.put(value.getKey(), value.getValue().getAsInt()));
        if (!parsed.materials().equals(expectedMaterials))
            throw new IOException("Packaged blueprint material manifest mismatch: " + id);
        BlueprintDesign named = new BlueprintDesign(id, entry.get("name").getAsString(), source,
                parsed.width(), parsed.height(), parsed.length(), parsed.cells());
        VALIDATED.put(id, named);
        return named;
    }

    static byte[] resource(String file, int limit) throws IOException {
        if (!file.matches("[a-zA-Z0-9_./-]+") || file.contains("..") || file.startsWith("/"))
            throw new IOException("Invalid packaged blueprint resource");
        try (InputStream input = BlueprintBundledLibrary.class.getResourceAsStream(RESOURCE_ROOT + file)) {
            if (input == null) throw new IOException("Packaged blueprint resource is missing: " + file);
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Packaged blueprint resource is oversized: " + file);
            return bytes;
        }
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Map<String, JsonObject> readManifest() {
        try {
            JsonObject manifest = JsonParser.parseString(new String(resource("manifest.json", 1024 * 1024),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (manifest.get("schemaVersion").getAsInt() != 1) throw new IOException("Unsupported bundled library manifest");
            JsonArray entries = manifest.getAsJsonArray("entries");
            Map<String, JsonObject> result = new LinkedHashMap<>();
            for (var value : entries) {
                JsonObject entry = value.getAsJsonObject();
                String id = entry.get("id").getAsString();
                if (!id.matches("prefab-[a-z0-9-]{1,70}") || result.put(id, entry) != null)
                    throw new IOException("Invalid or duplicate packaged blueprint ID");
            }
            return java.util.Collections.unmodifiableMap(result);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("Offline blueprint library manifest is invalid", invalid);
        }
    }
}
