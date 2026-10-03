package dev.entitybridge.blueprint;

import com.google.gson.JsonElement;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Read-only client catalog, shared by command discovery and clickable preview choices. */
public record BlueprintCatalogView(List<String> ids) {
    public static final BlueprintCatalogView EMPTY = new BlueprintCatalogView(List.of());

    public BlueprintCatalogView {
        if (ids == null || ids.size() > 1024) throw new IllegalArgumentException("Invalid blueprint catalog size");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,79}"))
                throw new IllegalArgumentException("Invalid blueprint catalog ID");
            unique.add(id);
        }
        ids = List.copyOf(unique);
    }

    public static BlueprintCatalogView parse(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonArray() || value.getAsJsonArray().size() > 1024)
            throw new IllegalArgumentException("Invalid blueprint catalog");
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Invalid blueprint catalog entry");
            ids.add(entry.getAsString());
        }
        return new BlueprintCatalogView(ids);
    }

    public List<String> complete(String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT).replace('_', '-');
        return ids.stream().filter(id -> id.replace('_', '-').startsWith(normalized)).toList();
    }

    public List<Component> choices() {
        return ids.stream().map(id -> Component.text("  [" + label(id) + "]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand("/e build select " + id))
                .hoverEvent(HoverEvent.showText(Component.text(
                        (id.startsWith("prefab-") ? "Brian Wuest/Prefab (MIT). Compatible architecture; empty containers/enclosures. " : "")
                        + "Click to fill a preview command. Construction still needs your confirmation."))))
                .map(component -> (Component) component).toList();
    }

    static String label(String id) {
        if (!id.startsWith("prefab-")) return id;
        return java.util.Arrays.stream(id.split("-"))
                .filter(word -> !word.isEmpty())
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
