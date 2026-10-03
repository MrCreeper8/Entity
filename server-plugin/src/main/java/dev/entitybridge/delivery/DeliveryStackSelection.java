package dev.entitybridge.delivery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

/** Immutable pre-nonce selection. stackNbt is vanilla compressed ItemStack NBT, including DataVersion. */
public record DeliveryStackSelection(int slot, int stackCount, int count, String stackNbt) {
    public DeliveryStackSelection {
        if (slot < 0 || slot > 40 || stackCount < 1 || stackCount > 99 || count < 1 || count > stackCount)
            throw new IllegalArgumentException("invalid exact delivery stack/count");
        if (stackNbt == null || stackNbt.isBlank() || stackNbt.length() > 65_536)
            throw new IllegalArgumentException("exact stack payload missing or too large");
        Base64.getDecoder().decode(stackNbt);
    }

    public static List<DeliveryStackSelection> fromFrame(JsonElement value, int expectedCount) {
        if (value == null || value.isJsonNull()) return List.of(); // legacy prepared nonce
        if (!value.isJsonArray()) throw new IllegalArgumentException("exact selection must be an array");
        JsonArray array = value.getAsJsonArray();
        if (array.isEmpty() || array.size() > 41) throw new IllegalArgumentException("invalid exact selection size");
        List<DeliveryStackSelection> result = new ArrayList<>();
        for (JsonElement element : array) {
            var entry = element.getAsJsonObject();
            result.add(new DeliveryStackSelection(entry.get("slot").getAsInt(),
                    entry.get("stackCount").getAsInt(), entry.get("count").getAsInt(),
                    entry.get("stackNbt").getAsString()));
        }
        return validate(result, expectedCount);
    }

    static List<DeliveryStackSelection> validate(List<DeliveryStackSelection> input, int expectedCount) {
        if (input == null || input.isEmpty()) return List.of();
        var slots = new HashSet<Integer>();
        int total = 0, bytes = 0;
        for (DeliveryStackSelection selection : input) {
            if (!slots.add(selection.slot)) throw new IllegalArgumentException("duplicate selected slot");
            total = Math.addExact(total, selection.count);
            bytes = Math.addExact(bytes, selection.stackNbt.length());
        }
        if (total != expectedCount || bytes > 262_144)
            throw new IllegalArgumentException("exact selection does not match prepared handoff");
        return List.copyOf(input);
    }

    /** Direct commits always consume a prefix, so a partial receipt cannot select a new stack. */
    public static List<Remainder> remaining(List<DeliveryStackSelection> selections, int confirmedCount) {
        List<Remainder> result = new ArrayList<>();
        int skip = confirmedCount;
        for (DeliveryStackSelection selection : selections) {
            int committed = Math.min(skip, selection.count);
            skip -= committed;
            if (committed < selection.count)
                result.add(new Remainder(selection, selection.stackCount - committed, selection.count - committed));
        }
        if (skip != 0) throw new IllegalArgumentException("receipt exceeds selected stacks");
        return List.copyOf(result);
    }

    public record Remainder(DeliveryStackSelection original, int expectedStackCount, int count) { }
}
