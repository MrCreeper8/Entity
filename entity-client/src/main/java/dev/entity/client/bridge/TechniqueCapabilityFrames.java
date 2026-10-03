package dev.entity.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.entity.client.technique.InventoryTechniquePolicy.CapabilityInventory;

import java.util.Locale;
import java.util.Objects;

/** Stable machine-readable capability frame produced from the execution decision. */
public final class TechniqueCapabilityFrames {
    public static final int SCHEMA_VERSION = 1;

    private TechniqueCapabilityFrames() {
    }

    public static JsonObject snapshot(
            CapabilityInventory inventory,
            long timestampMillis) {
        Objects.requireNonNull(inventory, "inventory");
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "capabilities");
        frame.addProperty("schema", SCHEMA_VERSION);
        frame.addProperty("timestamp", timestampMillis);
        frame.addProperty("selection",
                inventory.selection().name().toLowerCase(Locale.ROOT));
        frame.addProperty("reason", inventory.reason());
        JsonArray techniques = new JsonArray();
        inventory.capabilities().forEach(capability -> {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("id", capability.id());
            encoded.addProperty("supported", capability.supported());
            encoded.addProperty("available", capability.available());
            encoded.addProperty("blocker", capability.blocker());
            JsonArray requirements = new JsonArray();
            capability.requirements().forEach(requirements::add);
            encoded.add("requirements", requirements);
            techniques.add(encoded);
        });
        frame.add("techniques", techniques);
        return frame;
    }
}
