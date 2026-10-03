package dev.entitybridge.ai;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Read-only capability questions use the real command vocabulary, never generated work claims. */
public final class CompanionCapabilityAnswer {
    private static final Pattern QUESTION = Pattern.compile("^(?:can|could) you (.+?)[.!?]*$");
    private static final Map<String, String> RESOURCE_ACTIONS = Map.ofEntries(
            Map.entry("get", "get"), Map.entry("collect", "get"), Map.entry("obtain", "get"),
            Map.entry("make", "get"), Map.entry("craft", "get"), Map.entry("bring", "bring"),
            Map.entry("fetch", "bring"), Map.entry("give", "give"), Map.entry("mine", "mine"), Map.entry("dig", "mine"));
    private static final String UNCHANGED = " I haven't started or changed a job.";

    private CompanionCapabilityAnswer() { }

    /** Empty for orders, clarifications, native queries, and ordinary conversation. */
    public static Optional<String> answer(String request, BiFunction<String, String, Optional<String>> resolver) {
        if (request == null || request.length() > 1000) return Optional.empty();
        var authority = CompanionTurnPolicy.classify(request);
        if (authority.authorizedOrder() || authority.preserveCurrent() || authority.direct().isPresent()
                || CompanionTurnPolicy.maySuggest(request)) return Optional.empty();
        String text = request.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        var question = QUESTION.matcher(text);
        if (!question.matches()) return Optional.empty();
        String body = question.group(1).strip();
        // Reuse the direct adapter for typed loadouts/controls. This proposal is
        // inspected as catalog data only; it is never returned to the dispatcher.
        var typed = AiDirectRequest.interpret(body, resolver);
        if (typed.isPresent() && typed.get().steps().size() == 1) {
            var step = typed.get().steps().getFirst();
            var ability = ability(step.action());
            if (ability.isPresent() && !AiAbilityCatalog.query(step.action())) {
                if (ability.get().target() == AiAbilityCatalog.Target.GEAR)
                    return Optional.of("I can work toward that " + step.target() + " loadout, gathering missing supplies first." + UNCHANGED);
                if (ability.get().target() == AiAbilityCatalog.Target.NONE)
                    return Optional.of("I can " + ability.get().outcome() + "." + UNCHANGED);
            }
        }
        int space = body.indexOf(' ');
        String action = RESOURCE_ACTIONS.get(space < 0 ? body : body.substring(0, space));
        if (action == null) {
            // Multi-word conversation requests (e.g. asking for an explanation
            // or a language) are not unknown gameplay commands. Leave those to
            // conversation; exact unknown skill names remain honest catalog reads.
            String head = space < 0 ? body : body.substring(0, space);
            boolean catalogHead = AiAbilityCatalog.entries().stream().anyMatch(a -> a.commandPrefix().getFirst().equals(head));
            if (space >= 0 && !catalogHead) return Optional.empty();
            return Optional.of("That isn't a skill in my current command list." + UNCHANGED);
        }
        if (ability(action).filter(a -> a.target() == AiAbilityCatalog.Target.ITEM_REQUIRED).isEmpty())
            return Optional.of("That isn't a skill in my current command list." + UNCHANGED);
        String target = space < 0 ? "" : body.substring(space + 1);
        Optional<String> resolved;
        try { resolved = resolver.apply(action, target); }
        catch (RuntimeException unavailable) { resolved = Optional.empty(); }
        if (resolved == null || resolved.isEmpty())
            return Optional.of("I don't recognize that as a supported " + action + " target." + UNCHANGED);
        // Registry recognition admits a target, not proof of a viable plan or
        // possession. Keep source/tool/material conditions explicit; never say
        // every registered Minecraft item is obtainable or currently available.
        String label = target.replaceFirst("^minecraft:", "").replace('_', ' ');
        return Optional.of(switch (action) {
            case "mine" -> "I can try to mine " + label + " if I can reach it with suitable tools." + UNCHANGED;
            case "give" -> "I can hand over spare " + label + " already in my inventory, keeping my own kit." + UNCHANGED;
            case "bring" -> "I can try to bring " + label + ", keeping my own kit. I'd need a reachable source and suitable tools or materials." + UNCHANGED;
            default -> "I can try to get " + label + ". I'd need a reachable source and suitable tools or materials." + UNCHANGED;
        });
    }

    private static Optional<AiAbilityCatalog.Ability> ability(String action) {
        return AiAbilityCatalog.entries().stream().filter(a -> a.action().equals(action)).findFirst();
    }
}
