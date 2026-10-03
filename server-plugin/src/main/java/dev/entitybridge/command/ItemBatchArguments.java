package dev.entitybridge.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** Canonical wire and journal encoding for a multi-item mission objective. */
final class ItemBatchArguments {
    private ItemBatchArguments() {
    }

    static JsonObject encode(List<CommandInput.ItemAmount> items, String player) {
        JsonObject args = new JsonObject();
        JsonArray encoded = new JsonArray();
        for (CommandInput.ItemAmount item : items) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", item.item());
            entry.addProperty("count", item.count());
            encoded.add(entry);
        }
        args.add("items", encoded);
        args.addProperty("batch", true);
        if (player != null && !player.isBlank()) args.addProperty("player", player);
        return args;
    }
}
