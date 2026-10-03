package dev.entity.client.blueprint;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Locale;

/** User-selected local files, validated by the same native parser as URL imports. Never starts construction. */
public final class LocalBlueprintInbox {
    private final Path inbox, catalog;
    private int ticks;
    public LocalBlueprintInbox(Path data) {
        inbox = data.resolve("blueprint-file-inbox"); catalog = data.resolve("blueprints");
    }
    public void tick(MinecraftClient client) {
        if (++ticks % 100 != 0 || client.player == null || client.world == null || !Files.isDirectory(inbox)) return;
        try (var files = Files.list(inbox)) {
            var pending = files.filter(Files::isRegularFile).filter(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".schem") || name.endsWith(".litematic");
            }).sorted().findFirst();
            if (pending.isEmpty()) return;
            Path file = pending.orElseThrow(); String name = file.getFileName().toString();
            JsonObject result = new JsonObject(); result.addProperty("request", name.split("--", 2)[0]);
            try {
                if (Files.isSymbolicLink(file) || Files.size(file) > BlueprintImport.MAX_COMPRESSED) throw new IOException("File exceeds the import size limit.");
                String friendly = name.contains("--") ? name.substring(name.indexOf("--") + 2) : name;
                friendly = friendly.substring(0, friendly.lastIndexOf('.'));
                var parsed = BlueprintImport.parse(Files.readAllBytes(file), null, "local-file", friendly);
                var saved = BlueprintImport.save(catalog, parsed);
                result.addProperty("accepted", true); result.addProperty("name", saved.name()); result.addProperty("id", saved.id());
                result.addProperty("message", "Added to /e build list. Selection and confirmation are still required.");
            } catch (IOException | RuntimeException failure) {
                result.addProperty("accepted", false);
                result.addProperty("message", "Unsupported schematic. Use Sponge .schem v1/v2 or Litematica v7 with supported blocks; no build was started.");
            }
            Files.writeString(inbox.resolve(name + ".result.json"), result.toString());
            // Retain the owner's copied input without repeatedly retrying rejected files.
            Files.move(file, inbox.resolve(name + ".processed"));
        } catch (IOException unavailable) {
            org.slf4j.LoggerFactory.getLogger(LocalBlueprintInbox.class).warn("Local blueprint import storage is unavailable; no construction was started.");
        }
    }
}
