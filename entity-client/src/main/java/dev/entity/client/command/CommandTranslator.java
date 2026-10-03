package dev.entity.client.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.entity.core.EntityCore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CommandTranslator {
    private static final int MAX_ITEM_COUNT = 4096;
    private static final Map<String, String> FRIENDLY_ORES = Map.ofEntries(
            Map.entry("diamond", "diamond_ore,deepslate_diamond_ore"),
            Map.entry("diamonds", "diamond_ore,deepslate_diamond_ore"),
            Map.entry("iron", "iron_ore,deepslate_iron_ore"),
            Map.entry("gold", "gold_ore,deepslate_gold_ore,nether_gold_ore"),
            Map.entry("coal", "coal_ore,deepslate_coal_ore"),
            Map.entry("copper", "copper_ore,deepslate_copper_ore"),
            Map.entry("redstone", "redstone_ore,deepslate_redstone_ore"),
            Map.entry("lapis", "lapis_ore,deepslate_lapis_ore"),
            Map.entry("emerald", "emerald_ore,deepslate_emerald_ore"),
            Map.entry("netherite", "ancient_debris"),
            Map.entry("debris", "ancient_debris"));

    private CommandTranslator() {
    }

    public static EntityCore.Command toCore(IncomingCommand command) {
        Map<String, String> args = flatten(command.arguments());
        normalize(command.action(), args, command.requestedBy());
        return new EntityCore.Command(
                command.id(),
                command.requestedBy().isBlank() ? "owner" : command.requestedBy(),
                command.action(),
                args);
    }

    private static Map<String, String> flatten(JsonObject source) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (key.equals("kind") || value == null || value.isJsonNull()) continue;
            if (value.isJsonPrimitive()) {
                result.put(key, value.getAsString());
            } else if (value.isJsonArray()) {
                if (key.equals("items")) {
                    String encoded = encodeItemBatch(value);
                    if (!encoded.isBlank()) result.put("batchItems", encoded);
                    continue;
                }
                List<String> values = new ArrayList<>();
                value.getAsJsonArray().forEach(element -> {
                    if (element.isJsonPrimitive()) values.add(element.getAsString());
                });
                result.put(key, String.join(",", values));
            } else if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                if (key.equals("position")) {
                    copy(object, result, "x", "y", "z", "dimension");
                } else if (key.equals("resource")) {
                    String resource = first(object, "id", "name", "query", "block", "item");
                    if (!resource.isBlank()) result.put("resource", resource);
                } else if (key.equals("target")) {
                    String player = first(object, "player", "name");
                    String entityType = first(object, "entityType", "type");
                    if (!player.isBlank()) result.put("player", player);
                    if (!entityType.isBlank()) result.put("entityType", entityType);
                }
            }
        }
        return result;
    }

    private static void normalize(String action, Map<String, String> args, String requestedBy) {
        if (action.equals("come")) {
            args.putIfAbsent("player", requestedBy);
        }
        if (action.equals("get") || action.equals("give") || action.equals("bring")) {
            normalizeItemMission(action, args, requestedBy);
        }
        if (action.equals("mine")) {
            if (args.containsKey("quantity")) args.putIfAbsent("count", args.get("quantity"));
            if (args.containsKey("amount")) args.putIfAbsent("count", args.get("amount"));
            String raw = first(args, "blocks", "block", "resource", "item", "query");
            if (!raw.isBlank()) {
                String friendly = simple(raw);
                String blocks = FRIENDLY_ORES.getOrDefault(friendly, raw);
                if (!blocks.contains(",") && simpleIdentifier(blocks).equals("diamond")) {
                    blocks = FRIENDLY_ORES.get("diamond");
                }
                args.put("blocks", blocks);
            }
        }
        if (action.equals("goto")) {
            args.putIfAbsent("range", "1");
        }
        boolean guardsSelf = Boolean.parseBoolean(args.getOrDefault("self", "false"));
        if (action.equals("guard") && !guardsSelf
                && !args.containsKey("player") && !requestedBy.isBlank()) {
            args.put("player", requestedBy);
        }
    }

    private static void normalizeItemMission(
            String action,
            Map<String, String> args,
            String requestedBy) {
        if (args.containsKey("batchItems")) {
            if (args.containsKey("item") || args.containsKey("count")
                    || args.containsKey("amount") || args.containsKey("quantity")) {
                throw new IllegalArgumentException(
                        "batch mission cannot also contain a single-item objective");
            }
            args.put("batch", "true");
            if ((action.equals("give") || action.equals("bring"))
                    && !args.containsKey("player") && !requestedBy.isBlank()) {
                args.put("player", requestedBy);
            }
            return;
        }
        if (Boolean.parseBoolean(args.getOrDefault("batch", "false"))) {
            throw new IllegalArgumentException("batch marker requires an items array");
        }
        if (args.containsKey("quantity")) args.putIfAbsent("count", args.get("quantity"));
        if (args.containsKey("amount")) args.putIfAbsent("count", args.get("amount"));
        String item = first(args, "item", "resource", "query", "block");
        if (!item.isBlank()) args.putIfAbsent("item", item);
        args.putIfAbsent("count", "1");
        if ((action.equals("give") || action.equals("bring"))
                && !args.containsKey("player") && !requestedBy.isBlank()) {
            args.put("player", requestedBy);
        }
    }

    private static String encodeItemBatch(JsonElement value) {
        List<String> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("batch item must be an object");
            }
            JsonObject object = element.getAsJsonObject();
            String item = first(object, "item", "resource", "query").trim();
            JsonElement countElement = object.get("count");
            if (item.isBlank() || item.contains(",") || item.contains("=")
                    || countElement == null || !countElement.isJsonPrimitive()) {
                throw new IllegalArgumentException("batch item/count is invalid");
            }
            String encodedCount = countElement.getAsString().trim();
            if (!encodedCount.matches("[1-9]\\d*")) {
                throw new IllegalArgumentException("batch count is invalid");
            }
            try {
                int count = Integer.parseInt(encodedCount);
                if (count > MAX_ITEM_COUNT) {
                    throw new IllegalArgumentException(
                            "batch count exceeds " + MAX_ITEM_COUNT);
                }
                String identity = simpleIdentifier(item).toLowerCase(Locale.ROOT);
                if (!seen.add(identity)) {
                    throw new IllegalArgumentException("duplicate batch item " + item);
                }
                total = Math.addExact(total, count);
                entries.add(item + "=" + count);
            } catch (NumberFormatException | ArithmeticException error) {
                throw new IllegalArgumentException("batch count is invalid", error);
            }
        }
        if (entries.size() < 2) throw new IllegalArgumentException("batch requires at least two items");
        return String.join(",", entries);
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String first(JsonObject values, String... keys) {
        for (String key : keys) {
            JsonElement value = values.get(key);
            if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                return value.getAsString();
            }
        }
        return "";
    }

    private static void copy(JsonObject source, Map<String, String> target, String... keys) {
        for (String key : keys) {
            JsonElement value = source.get(key);
            if (value != null && value.isJsonPrimitive()) target.put(key, value.getAsString());
        }
    }

    private static String simple(String value) {
        return simpleIdentifier(value).replace("_ore", "").replace("deepslate_", "");
    }

    private static String simpleIdentifier(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return normalized.startsWith("minecraft:")
                ? normalized.substring("minecraft:".length())
                : normalized;
    }
}
