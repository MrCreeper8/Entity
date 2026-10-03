package dev.entitybridge.ai;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Exact short command equivalents, not a second natural-language planner. */
final class AiDirectRequest {
    private static final List<String> COUNT_WORDS = List.of("one", "two", "three", "four", "five", "six",
            "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen", "twenty");
    private static final String QUANTITY = "(?:[1-9][0-9]{0,3}|a single|a|an|single|" + String.join("|", COUNT_WORDS)
            + "|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand)";
    private static final Pattern COUNT_FIRST = Pattern.compile("^(" + QUANTITY + ") (.+)$");
    private static final Pattern COUNT_LAST = Pattern.compile("^(.+) (" + QUANTITY + ")$");
    private static final Pattern ITEM_ORDER = Pattern.compile(
            "^(bring|fetch|give|gimme|get|collect|obtain|make|craft|mine|dig) (.+)$");
    private static final Pattern GOTO = Pattern.compile(
            "^(?:goto|go to) ([+-]?[0-9]{1,10}) ([+-]?[0-9]{1,10}) ([+-]?[0-9]{1,10})$");
    private static final Pattern RECIPIENT_PREFIX = Pattern.compile("^(me|yourself|for me|for yourself) (.+)$");
    private static final Pattern RECIPIENT_SUFFIX = Pattern.compile("^(.+) (for me|to me|for yourself)$");
    private static final Pattern CARRIED_SUFFIX = Pattern.compile(
            "^(.+?) (?:already on you|from your inventory|already carried|(?:that )?you already have)$");
    private static final Pattern CARRIED_HANDOFF = Pattern.compile(
            "^(hand|pass|give|gimme) me (" + QUANTITY + ") (spare )?(.+?)"
                    + "(,? (?:just |only )?(?:what(?:'s|s| is) already on you|already on you|already carried"
                    + "|from your inventory|(?:that )?you already have))?$");
    private static final String GEAR_TIER = "(?:wood|wooden|stone|iron|diamond|best)";
    private static final Pattern GEAR_OUTCOME = Pattern.compile(
            "^(?:(?:get|obtain|make|craft)(?: yourself| for yourself)? (?:(?:a|an|the) )?(?:set of )?("
                    + GEAR_TIER + ") (?:gear|kit|loadout|equipment)(?: for yourself)?"
                    + "|equip(?: yourself)?(?: with)? (" + GEAR_TIER + ") (?:gear|kit|loadout|equipment)"
                    + "|upgrade your (?:gear|kit|loadout|equipment) to (" + GEAR_TIER + "))$");
    private static final Set<String> FOOD_QUERIES = Set.of("what do you have to eat", "what have you got to eat",
            "what are you carrying to eat", "do you have anything to eat", "have you got anything to eat",
            "what food do you have", "what food are you carrying", "show your food", "show food", "food inventory");
    // These are grammar/context fences, never an alternate Minecraft item vocabulary.
    private static final Pattern UNSAFE_ITEM = Pattern.compile(
            "(?:^| )(?:and|or|but|then|if|unless|when|whether|no|not|never|dont|cannot|cant|wont"
                    + "|please|yourself|me|you|for|to|already|some|another|instead|again|only|all"
                    + "|about|like|said|says|means|asked|tomorrow|later|now)(?:$| )");

    private AiDirectRequest() { }

    static Optional<AiProposal> interpret(String request) {
        return interpret(request, item -> Optional.empty());
    }

    /** The existing command resolver owns item identity; unknown or complex text stays with the adapter. */
    static Optional<AiProposal> interpret(String request, Function<String, Optional<String>> targetResolver) {
        return interpret(request, (action, target) -> targetResolver.apply(target));
    }

    /** Resolve the typed action's target: Mine block groups are not acquisition item aliases. */
    static Optional<AiProposal> interpret(String request, BiFunction<String, String, Optional<String>> targetResolver) {
        // Whole utterance only: never strip negatives, conditions, quotation,
        // question marks or compound clauses to find a matching substring.
        if (request == null || request.isBlank() || request.length() > 4096) return Optional.empty();
        String text = request.strip().toLowerCase(Locale.ROOT).replaceAll(" +", " ");
        if (isFoodQuery(text)) return proposal("inventory", "food", 0);
        if (text.endsWith(".") || text.endsWith("!")) text = text.substring(0, text.length() - 1).stripTrailing();
        text = normalizeCarriedHandoff(text);
        var coordinates = GOTO.matcher(text);
        if (coordinates.matches()) {
            try {
                String target = Integer.parseInt(coordinates.group(1)) + " "
                        + Integer.parseInt(coordinates.group(2)) + " " + Integer.parseInt(coordinates.group(3));
                return proposal("goto", target, 0);
            } catch (NumberFormatException invalid) { return Optional.empty(); }
        }
        var combatControl = AiCombatRequest.interpret(text);
        if (combatControl.isPresent()) return combatControl;
        var existingControl = AiExistingControlRequest.interpret(text);
        if (existingControl.isPresent()) return existingControl;
        String action = switch (text) {
            case "come", "come here", "come to me", "please come", "please come here",
                    "please come to me", "come here please", "come to me please",
                    "come over here", "come over to me", "come over", "please come over here",
                    "come over here please" -> "come";
            case "inventory", "show inventory", "show your inventory", "what are you carrying",
                    "what do you have", "what do you have on you", "what you have on you" -> "inventory";
            case "equipment", "gear status" -> "equipment";
            case "status" -> "status";
            case "why" -> "why";
            case "capabilities" -> "capabilities";
            case "home status" -> "home_status";
            case "stock status" -> "stock_status";
            case "queue status" -> "queue_status";
            case "follow", "follow me" -> "follow";
            case "home", "home go", "go home", "return home", "go to your home" -> "home";
            case "sleep", "home sleep" -> "sleep";
            case "stock", "stock run", "run stock", "stock up", "stock up your home", "run home stock" -> "stock";
            case "stop" -> "stop";
            case "pause" -> "pause";
            case "resume" -> "resume";
            case "retry" -> "retry";
            case "queue skip", "skip the queue step" -> "queue_skip";
            case "queue clear", "clear the queue" -> "queue_clear";
            case "queue cancel", "cancel the queue" -> "queue_cancel";
            default -> "";
        };
        if (!action.isEmpty()) return proposal(action, "", 0);
        if (text.matches("follow [a-z0-9_]{1,16}") && !UNSAFE_ITEM.matcher(text.substring(7)).find()
                && !text.matches("follow (?:it|him|her|them|us|now|later|tomorrow)"))
            return proposal("follow", text.substring(7), 0);
        if (text.matches("gear [a-z0-9_:-]+")) return proposal("gear", text.substring(5), 0);
        // Resolve the whole requested outcome before considering acquisition
        // quantity. A loadout is one count-free Gear goal, not an item named gear.
        var gear = GEAR_OUTCOME.matcher(text);
        if (gear.matches()) {
            String tier = gear.group(1) != null ? gear.group(1) : gear.group(2) != null ? gear.group(2) : gear.group(3);
            return proposal("gear", tier.equals("wooden") ? "wood" : tier, 0);
        }
        return itemOrder(text, targetResolver);
    }

    /** A model/offer may not choose a destination absent from this exact current request. */
    static boolean permittedCoordinates(AiProposal proposal, Optional<AiProposal> direct) {
        return proposal.steps().stream().noneMatch(step -> step.action().equals("goto"))
                || direct.isPresent() && proposal.steps().equals(direct.get().steps());
    }

    /** Exact whole self-food queries only; punctuation cannot expose an embedded order. */
    static boolean isFoodQuery(String request) {
        if (request == null || request.length() > 4096) return false;
        String query = request.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                .replaceFirst("[.!?]+$", "").stripTrailing();
        return FOOD_QUERIES.contains(query);
    }

    /** Whole counted handoff only. "Spare/already carried" never becomes permission to acquire a replacement. */
    static String normalizeCarriedHandoff(String request) {
        var handoff = CARRIED_HANDOFF.matcher(request);
        if (!handoff.matches() || (Set.of("give", "gimme").contains(handoff.group(1))
                && handoff.group(3) == null && handoff.group(5) == null)) return request;
        String item = handoff.group(4);
        if (!item.matches("[a-z0-9_:-]+(?: [a-z0-9_:-]+){0,4}") || UNSAFE_ITEM.matcher(item).find()) return request;
        return "give me " + handoff.group(2) + " " + item + " already on you";
    }

    private static Optional<AiProposal> itemOrder(String text, BiFunction<String, String, Optional<String>> resolver) {
        var order = ITEM_ORDER.matcher(text);
        if (!order.matches() || resolver == null) return Optional.empty();
        String verb = order.group(1), body = order.group(2), recipient = "";
        boolean carried = false;
        var carriedSuffix = CARRIED_SUFFIX.matcher(body);
        if (carriedSuffix.matches()) {
            if (!verb.equals("give") && !verb.equals("gimme")) return Optional.empty();
            carried = true;
            body = carriedSuffix.group(1);
        }
        var suffix = RECIPIENT_SUFFIX.matcher(body);
        if (suffix.matches()) {
            recipient = suffix.group(2).endsWith("yourself") ? "self" : "player";
            body = suffix.group(1);
        }
        var prefix = RECIPIENT_PREFIX.matcher(body);
        if (prefix.matches()) {
            String explicit = prefix.group(1).endsWith("yourself") ? "self" : "player";
            if (!recipient.isEmpty() && !recipient.equals(explicit)) return Optional.empty();
            recipient = explicit;
            body = prefix.group(2);
        }
        var first = COUNT_FIRST.matcher(body);
        var last = COUNT_LAST.matcher(body);
        String item, quantity;
        if (first.matches()) {
            quantity = first.group(1); item = first.group(2);
        } else if (last.matches()) {
            quantity = last.group(2); item = last.group(1);
        } else return Optional.empty();
        if (!item.matches("[a-z0-9_:-]+(?: [a-z0-9_:-]+)*") || UNSAFE_ITEM.matcher(item).find()
                || COUNT_FIRST.matcher(item).matches() || COUNT_LAST.matcher(item).matches()
                || item.matches(".*(?:^| )[0-9]+(?: |$).*")) return Optional.empty();

        String action;
        if (verb.equals("mine") || verb.equals("dig")) {
            if (recipient.equals("player")) return Optional.empty();
            action = "mine";
        } else if (verb.equals("give") || verb.equals("gimme")) {
            if (recipient.equals("self")) return Optional.empty();
            action = carried ? "give" : "bring";
        } else if (verb.equals("bring")) {
            if (recipient.equals("self")) return Optional.empty();
            action = "bring";
        } else if (recipient.equals("player") || verb.equals("fetch") && !recipient.equals("self")) {
            action = "bring";
        } else action = "get";
        try {
            Optional<String> target = resolver.apply(action, item);
            return target == null ? Optional.empty() : target.flatMap(value -> proposal(action, value, count(quantity)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static int count(String quantity) {
        if (quantity.matches("[1-9][0-9]{0,3}")) return Integer.parseInt(quantity);
        int word = COUNT_WORDS.indexOf(quantity);
        if (word >= 0) return word + 1;
        return switch (quantity) {
            case "a", "an", "single", "a single" -> 1;
            case "thirty" -> 30;
            case "forty" -> 40;
            case "fifty" -> 50;
            case "sixty" -> 60;
            case "seventy" -> 70;
            case "eighty" -> 80;
            case "ninety" -> 90;
            case "hundred" -> 100;
            case "thousand" -> 1000;
            default -> throw new IllegalArgumentException();
        };
    }

    private static Optional<AiProposal> proposal(String action, String target, int count) {
        try {
            var step = new AiProposal.Step(action, target, count);
            AiAbilityCatalog.validate(step, false);
            return Optional.of(new AiProposal(List.of(step), ""));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
