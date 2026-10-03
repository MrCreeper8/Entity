package dev.entitybridge.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Read-only, program-owned facts. The caller supplies current observations; no item semantics live here. */
public final class CompanionFactAnswer {
    public enum Query { NONE, FOOD, INVENTORY, EQUIPMENT, PROGRESS, HOME, CAUSE, HUNGER, HEALTH, DELIVERY_ABILITY }

    private static final Pattern QUESTION = Pattern.compile("\\b(?:what|where|why|how|which|are you|do you|have you)\\b");
    private static final Pattern JOB = Pattern.compile("\\b(?:mission|job|task|progress)\\b");
    private static final Pattern FOOD = Pattern.compile("\\b(?:food|foods|meat|cooked|raw)\\b");
    private static final Pattern EQUIPMENT_QUESTION = Pattern.compile(
            "(?:what|which)(?: (?:gear|equipment|armou?r))? are you (?:wearing|holding|equipped with)"
                    + "(?: and (?:wearing|holding))?(?: (?:right )?now)?");
    private static final Pattern HEALTH_QUESTION = Pattern.compile(
            "(?:(?:how much|how many) (?:health(?: points?)?|hearts?|hp) "
                    + "(?:do you have|have you got|you have|you got)(?: (?:left|remaining))?"
                    + "|what is your (?:current )?(?:health(?: points?)?|hp))"
                    + "(?: (?:right )?now)?");

    private CompanionFactAnswer() { }

    /** A bounded question recognizer, not a command parser or permission source. */
    public static Query query(String currentRequest) {
        if (currentRequest == null) return Query.NONE;
        // These facts are deliberately not in this self snapshot. Their exact
        // native read action takes precedence over generic "doing/have" matches.
        if (AiExistingControlRequest.readQuery(currentRequest).isPresent()) return Query.NONE;
        // The actual "what do you have to eat?" turn asks for carried food,
        // not the generic inventory query selected by the word "have".
        // Resolve this exact whole query before the broader factual recognizer.
        if (AiDirectRequest.isFoodQuery(currentRequest)) return Query.FOOD;
        String request = currentRequest.toLowerCase(Locale.ROOT)
                .replaceAll("foo\\s*[,._-]\\s*d", "food")
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
        if (request.isEmpty()) return Query.NONE;
        if(request.matches("(?:why|why not|how come|what happened)"))return Query.CAUSE;
        if(request.matches(".*\\b(?:what if|suppose|hypothetically|would you|if i asked)\\b.*"))
            return request.matches(".*\\b(?:bring|give|fetch|deliver)\\b.*") ? Query.DELIVERY_ABILITY : Query.NONE;
        if(request.matches(".*\\bcould you\\b.*")) return Query.NONE;
        // Whole present-state questions only: capability, conditions, quotations
        // and orders must retain their existing conversation/action boundaries.
        if (!currentRequest.matches(".*[\"`“”‘’'].*")
                && EQUIPMENT_QUESTION.matcher(request).matches()) return Query.EQUIPMENT;
        // Health quantity is an observed self stat, not an inventory possession.
        // A whole question keeps health-potion items, orders and quoted text out.
        if (!currentRequest.matches(".*[\"`“”‘’'].*")
                && HEALTH_QUESTION.matcher(request).matches()) return Query.HEALTH;
        boolean question = QUESTION.matcher(request).find() || currentRequest.contains("?")
                || request.matches("^(?:you have|you got|ya have|ya got|you carrying)\\b.*")
                || (request.startsWith("no ") && request.contains("i mean") && request.contains("you have"));
        if (question && request.matches(".*\\b(?:home|base)\\b.*")
                && request.matches(".*\\b(?:where|coordinates?|coords|location|position)\\b.*")) return Query.HOME;
        if (request.matches(".*\\b(?:why|how come|what caused)\\b.*")
                && request.matches(".*\\b(?:cancel|cancelled|canceled|stop|stopped|failed|superseded|replaced)\\b.*")) return Query.CAUSE;
        if (request.matches(".*\\b(?:food|hunger) points?\\b.*") || request.contains("hunger meter")) return Query.HUNGER;
        boolean foodQuestion = question && FOOD.matcher(request).find();
        if (question && JOB.matcher(request).find()) return Query.PROGRESS;
        if (foodQuestion) return Query.FOOD;
        if (question && request.matches(".*\\b(?:hungry|hunger|full)\\b.*")) return Query.HUNGER;
        if (question && request.matches(".*\\b(?:doing|gathered|collected|delivered|handed over)\\b.*")) return Query.PROGRESS;
        if ((question && (request.matches(".*\\b(?:inventory|carrying|have|got)\\b.*")))
                || request.matches("(?:show|list|tell me)(?: me| your| my| the)* inventory")) return Query.INVENTORY;
        return Query.NONE;
    }

    /** Empty only for NONE or a missing Home snapshot, which asks the caller to use its Home query boundary. */
    public static Optional<String> answer(Query query, JsonObject facts) {
        if (query == null || query == Query.NONE) return Optional.empty();
        JsonObject snapshot = facts == null ? new JsonObject() : facts;
        return switch (query) {
            case FOOD -> Optional.of(food(snapshot));
            case INVENTORY -> Optional.of(inventory(snapshot));
            case EQUIPMENT -> Optional.of(equipment(snapshot));
            case PROGRESS -> Optional.of(progress(snapshot));
            case HOME -> home(snapshot);
            case CAUSE -> Optional.of(cause(snapshot));
            case HUNGER -> Optional.of(hunger(snapshot));
            case HEALTH -> Optional.of(health(snapshot));
            case DELIVERY_ABILITY -> Optional.of("A Bring mission can obtain and deliver supplies while keeping my own kit. This question hasn't started another order.");
            case NONE -> Optional.empty();
        };
    }

    private static String food(JsonObject facts) {
        JsonObject summary = object(facts, "food_summary");
        if (!live(facts) || !observed(summary)) return "My carried food counts are not observed right now.";
        Long cooked = count(summary, "cooked_count"), raw = count(summary, "raw_count"), other = count(summary, "other_edible_count");
        List<String> amounts = new ArrayList<>();
        amounts.add(cooked == null ? "cooked food: not observed" : cooked + " cooked food items");
        amounts.add(raw == null ? "raw food: not observed" : raw + " raw");
        amounts.add(other == null ? "other edible food: not observed" : other + " other edible items");
        String possession = cooked != null && raw != null && other != null
                ? "I've got " + amounts.get(0) + ", " + amounts.get(1) + " and " + amounts.get(2) + ". "
                : "Food on me: " + String.join("; ", amounts) + ". ";
        Long reserved = count(summary, "reserved_count"), deliverable = count(summary, "deliverable_count");
        return possession + (reserved == null ? "Kit reserve: not observed" : reserved + " reserved for my kit")
                + "; " + (deliverable == null ? "available to deliver: not observed" : deliverable + " available to deliver") + ".";
    }

    private record Item(String name, long count) { }

    private static String equipment(JsonObject facts) {
        String unavailable = "My equipped gear isn't observed reliably right now.";
        JsonObject slots = object(facts, "equipped_subset_NOT_extra_items");
        if (!Boolean.TRUE.equals(bool(facts, "connected")) || !reliableEquipment(facts)
                || !reliableEquipment(slots)) return unavailable;
        String main = equipmentItem(slots, "main_hand"), off = equipmentItem(slots, "off_hand");
        // Native Paper snapshots always include both hands, including explicit
        // air, and omit only empty armor slots. A partial hand frame is unknown.
        if (main == null || off == null) return unavailable;
        List<String> worn = new ArrayList<>();
        for (String slot : List.of("helmet", "chestplate", "leggings", "boots")) {
            if (!slots.has(slot)) continue;
            String item = equipmentItem(slots, slot);
            if (item == null) return unavailable;
            if (!item.equals("air")) worn.add(itemName(item));
        }
        String armor = worn.isEmpty() ? "I'm not wearing armor. "
                : "Wearing " + (worn.size() == 1 ? worn.getFirst()
                : String.join(", ", worn.subList(0, worn.size() - 1)) + " and " + worn.getLast()) + ". ";
        return armor + "Main hand: " + (main.equals("air") ? "empty" : itemName(main))
                + "; off-hand: " + (off.equals("air") ? "empty" : itemName(off)) + ".";
    }

    private static boolean reliableEquipment(JsonObject facts) {
        if (facts == null) return false;
        return (!facts.has("observed") || Boolean.TRUE.equals(bool(facts, "observed")))
                && (!facts.has("stale") || Boolean.FALSE.equals(bool(facts, "stale")));
    }

    private static String equipmentItem(JsonObject slots, String key) {
        JsonElement value = slots.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String item = value.getAsString();
        // Native registry keys only; never render arbitrary snapshot prose as
        // a gear claim or infer equipment from the carried totals.
        if (!item.matches("(?:minecraft:)?[a-z0-9]+(?:_[a-z0-9]+)*") || item.length() > 64) return null;
        return item.startsWith("minecraft:") ? item.substring(10) : item;
    }

    private static String inventory(JsonObject facts) {
        JsonObject inventory = object(facts, "my_carried_total_including_equipped");
        if (!live(facts) || !observed(inventory)) return "My carried inventory is not observed right now.";
        List<Item> items = new ArrayList<>();
        long total = 0;
        for (var entry : inventory.entrySet()) {
            Long amount = count(inventory, entry.getKey());
            if (amount == null || amount == 0) continue;
            // Counts are exact; a corrupt overflowing snapshot is unavailable, not a wrapped total.
            try { total = Math.addExact(total, amount); }
            catch (ArithmeticException invalid) { return "My carried inventory total is not observed reliably right now."; }
            items.add(new Item(entry.getKey(), amount));
        }
        if (items.isEmpty()) return "I'm carrying no items (including equipped slots).";
        items.sort(Comparator.comparingLong(Item::count).reversed().thenComparing(Item::name));
        List<String> descriptions = items.stream().limit(8)
                .map(item -> item.count + " " + itemName(item.name)).toList();
        return "I'm carrying " + String.join(", ", descriptions)
                + (items.size() > 8 ? ", plus " + (items.size()-8) + " more item types" : "")
                + ". " + total + " items total, including equipped slots.";
    }

    private static String hunger(JsonObject facts) {
        Long hunger = count(facts, "hunger_points_out_of_20");
        if (!live(facts) || hunger == null || hunger > 20) return "My hunger meter is not observed right now; it isn't a count of carried food.";
        String icons = BigDecimal.valueOf(hunger).divide(BigDecimal.valueOf(2)).stripTrailingZeros().toPlainString();
        return "My hunger meter is " + hunger + "/20 (" + icons + " hunger icons), not a count of food I'm carrying.";
    }

    private static String health(JsonObject facts) {
        String unavailable = "My health is not observed right now.";
        JsonElement value = facts.get("health_points_out_of_20");
        if (!live(facts) || value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) return unavailable;
        try {
            BigDecimal points = value.getAsBigDecimal();
            if (points.signum() < 0 || points.compareTo(BigDecimal.valueOf(20)) > 0) return unavailable;
            return "My health is " + points.stripTrailingZeros().toPlainString() + "/20 health points ("
                    + points.divide(BigDecimal.valueOf(2)).stripTrailingZeros().toPlainString() + " hearts).";
        } catch (NumberFormatException | ArithmeticException invalid) { return unavailable; }
    }

    private static String progress(JsonObject facts) {
        if (!observed(facts)) return "My current mission progress is not observed right now.";
        JsonElement current = facts.get("current_job");
        if (current != null && current.isJsonPrimitive() && current.getAsJsonPrimitive().isString()
                && current.getAsString().equals("none")) return "No current mission is recorded.";
        JsonObject job = object(facts, "current_job");
        if (!observed(job)) return "My current mission progress is not observed right now.";
        Long number = count(job, "number");
        String objective = text(job, "objective", 90), status = text(job, "status", 30);
        String action = text(job, "action", 30).toLowerCase(Locale.ROOT);
        String work = objective.isBlank() ? "that job" + (number == null ? "" : " (#" + number + ")") : objective;
        // These are the native objective prefixes, not a language-to-command parser.
        // The raw phase "executing" adds no observed Minecraft stage.
        if (work.startsWith("obtain own ")) work = "my " + work.substring(11);
        else if (work.startsWith("obtain and keep ")) work = "getting and keeping " + work.substring(16);
        else if (work.startsWith("obtain and deliver ")) work = "getting and delivering " + work.substring(19);
        else if (work.startsWith("hand over ")) work = "handing over " + work.substring(10);
        String reply = switch (status.toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "I'm working on " + work + ".";
            case "PAUSED" -> "I've paused work on " + work + ".";
            case "QUEUED" -> "I've queued " + work + "; I haven't started it yet.";
            case "RETRYING" -> "I'm retrying " + work + ".";
            case "BLOCKED" -> "I'm blocked on " + work + ".";
            case "FAILED" -> "I couldn't finish " + work + ".";
            case "CANCELLED" -> "I've stopped work on " + work + ".";
            case "SUCCEEDED" -> "I've finished " + work + ".";
            default -> "I've got " + work + " recorded, but its current state is not observed.";
        };
        // Action is supplied by the mission owner. Never infer quantity applicability
        // (or amounts) from a prose objective such as "obtain own iron kit".
        boolean acquisition = action.equals("get") || action.equals("bring") || action.equals("give");
        boolean legacyCounts = action.isBlank() && (job.has("requested_count") || job.has("carried_count"));
        if (acquisition || legacyCounts) reply += " " + carriedProgress(job);
        boolean delivery = action.equals("bring") || action.equals("give");
        if (delivery || action.isBlank() && (job.has("deliverable_count") || job.has("delivered_count"))) {
            Long available = count(job, "deliverable_count"), delivered = count(job, "delivered_count");
            reply += available == null ? " I can't check what's available to deliver." : " I have " + available + " available to deliver.";
            reply += delivered == null ? " I can't check how many I've handed over." : " I've handed over " + delivered + ".";
        }
        if (FOOD.matcher(objective.toLowerCase(Locale.ROOT)).find() && live(facts)) {
            JsonObject summary = object(facts, "food_summary");
            if (observed(summary)) reply += " Food on me: " + quantity(summary, "cooked_count", "cooked") + ", "
                    + quantity(summary, "raw_count", "raw") + ", " + quantity(summary, "other_edible_count", "other edible")
                    + "; " + quantity(summary, "reserved_count", "reserved for my kit") + ".";
        }
        return reply;
    }

    private static String carriedProgress(JsonObject job) {
        Long requested = count(job, "requested_count"), carried = count(job, "carried_count");
        if (requested != null && carried != null) return "I've got " + carried + " on me; you asked for " + requested + ".";
        if (requested != null) return "You asked for " + requested + "; I can't check how many I'm carrying.";
        if (carried != null) return "I've got " + carried + " on me; the requested amount isn't recorded.";
        return "I can't check the requested or carried amount right now.";
    }

    private static Optional<String> home(JsonObject facts) {
        JsonObject home = object(facts, "home");
        if (home == null) return Optional.empty();
        if (!observed(facts) || !observed(home)) return Optional.of("My registered Home details are not observed right now.");
        Boolean registered = bool(home, "registered");
        if (registered == null) return Optional.of("My Home registration is not observed right now.");
        if (!registered) return Optional.of("I don't have a registered Home.");
        String x = coordinate(home, "x"), y = coordinate(home, "y"), z = coordinate(home, "z"), dimension = text(home, "dimension", 100);
        if (x == null || y == null || z == null || dimension.isBlank()) return Optional.of("I have a registered Home, but its coordinates and dimension are not observed completely.");
        return Optional.of("My registered Home is at " + x + ", " + y + ", " + z + " in " + dimension + ".");
    }

    private static String cause(JsonObject facts) {
        JsonObject change = object(facts, "latest_change");
        if (!observed(facts) || !observed(change)) return "The cause of that mission change is not observed here; I won't guess.";
        String kind = text(change, "kind", 50), detail = text(change, "detail", 240);
        if (detail.isBlank()) return "The recorded change is " + (kind.isBlank() ? "unknown" : kind.toLowerCase(Locale.ROOT)) + ", but its cause was not observed.";
        return "Latest recorded change" + (kind.isBlank() ? "" : " (" + kind.toLowerCase(Locale.ROOT) + ")") + ": " + detail
                + (detail.matches(".*[.!?]$") ? "" : ".");
    }

    private static String quantity(JsonObject facts, String key, String label) {
        Long amount = count(facts, key);
        return amount == null ? label + ": not observed" : amount + " " + label;
    }

    private static String itemName(String name) {
        String value = name.startsWith("minecraft:") ? name.substring(10) : name;
        return value.replace('_', ' ').substring(0, Math.min(42, value.length()));
    }

    private static JsonObject object(JsonObject facts, String key) {
        JsonElement value = facts.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static Boolean bool(JsonObject facts, String key) {
        JsonElement value = facts.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
    }

    private static boolean observed(JsonObject facts) {
        return facts != null && !Boolean.TRUE.equals(bool(facts, "stale")) && !Boolean.FALSE.equals(bool(facts, "observed"));
    }

    private static boolean live(JsonObject facts) {
        return observed(facts) && !Boolean.FALSE.equals(bool(facts, "connected"));
    }

    private static Long count(JsonObject facts, String key) {
        JsonElement value = facts.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            long result = value.getAsBigDecimal().longValueExact();
            return result < 0 ? null : result;
        } catch (NumberFormatException | ArithmeticException invalid) { return null; }
    }

    private static String coordinate(JsonObject facts, String key) {
        JsonElement value = facts.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try { return value.getAsBigDecimal().stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static String text(JsonObject facts, String key, int limit) {
        JsonElement value = facts.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "";
        String result = value.getAsString().replaceAll("\\s+", " ").trim();
        return result.substring(0, Math.min(limit, result.length()));
    }
}
