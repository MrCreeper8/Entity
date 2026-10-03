package dev.entity.client.autonomy.inventory;

import dev.entity.client.autonomy.policy.FoodFamilyPolicy;
import dev.entity.client.autonomy.policy.WoodLogFamilyPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Read-only presentation of observed registry IDs, never recipe substitutions. */
public final class InventoryReport {
    // Leaves room for the heading and page numbers inside the existing 500-character result.
    private static final int PAGE_BODY_CHARACTERS = 400;
    private static final String SCOPE = " (includes personal reserves; not Home storage)";

    private InventoryReport() {}

    public static List<String> pages(Map<String, Integer> observed, String requestedItem) {
        TreeMap<String, Integer> exact = new TreeMap<>();
        observed.forEach((item, count) -> {
            if (count != null && count > 0) exact.merge(identifier(item), count, Math::addExact);
        });
        String requested = identifier(requestedItem);
        if (!requested.isBlank()) {
            boolean family = requested.equals("logs") || requested.equals("planks") || requested.equals("food");
            if (!family) {
                return paginate("Carried", exact.getOrDefault(requested, 0) + " " + requested + SCOPE + ".");
            }
            exact.entrySet().removeIf(entry -> !memberOf(requested, entry.getKey()));
            int total = exact.values().stream().reduce(0, Math::addExact);
            String members = exact.isEmpty() ? "none" : entries(exact);
            return paginate("Carried", total + " " + requested + SCOPE + ". Exact items: " + members);
        }
        if (exact.isEmpty()) return List.of("Entity's inventory is empty");
        return paginate("Carried inventory" + SCOPE, entries(exact));
    }

    /** Intermediate pages must retain the existing requester until the final result. */
    public static String phase(int pageIndex, int pageCount) {
        if (pageIndex < 0 || pageIndex >= pageCount) throw new IllegalArgumentException("Invalid inventory page");
        return pageIndex + 1 == pageCount ? "completed" : "active";
    }

    private static boolean memberOf(String family, String item) {
        if (family.equals("logs")) return WoodLogFamilyPolicy.members().contains(item);
        if (family.equals("food")) return FoodFamilyPolicy.members().stream().anyMatch(member -> member.readyItem().equals(item));
        // Registry namespace must be Minecraft; a mod's similarly named item is not this group.
        return !item.contains(":") && item.endsWith("_planks");
    }

    private static String identifier(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    private static String entries(Map<String, Integer> exact) {
        return String.join(", ", exact.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toList());
    }

    private static List<String> paginate(String heading, String body) {
        List<String> parts = new ArrayList<>();
        while (body.length() > PAGE_BODY_CHARACTERS) {
            int separator = body.lastIndexOf(", ", PAGE_BODY_CHARACTERS);
            int end = separator > 0 ? separator : PAGE_BODY_CHARACTERS;
            parts.add(body.substring(0, end));
            body = body.substring(separator > 0 ? end + 2 : end);
        }
        parts.add(body);
        List<String> pages = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            String label = parts.size() == 1 ? "" : " [" + (index + 1) + "/" + parts.size() + "]";
            pages.add(heading + label + ": " + parts.get(index));
        }
        return List.copyOf(pages);
    }
}
