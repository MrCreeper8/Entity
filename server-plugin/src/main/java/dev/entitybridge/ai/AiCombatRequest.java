package dev.entitybridge.ai;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Whole explicit existing controls only. No target selection, retaliation or player-name fallback. */
final class AiCombatRequest {
    private static final Set<String> ACTIONS = Set.of("attack", "combat_mode", "combat_status",
            "protect_requester_on", "protect_requester_off", "protect_requester_status");
    // Bare ordinary types use the command's existing completion vocabulary plus common passive mobs.
    // Other exact types remain available with an explicit minecraft: prefix; arbitrary names are not guessed.
    private static final Set<String> MOB_WORDS = Set.of("zombie", "skeleton", "creeper", "spider", "witch",
            "pillager", "drowned", "husk", "slime", "phantom", "enderman", "warden", "piglin", "blaze",
            "ghast", "cow", "pig", "sheep", "chicken", "rabbit", "zombified_piglin", "cave_spider",
            "wither_skeleton", "magma_cube", "iron_golem");

    private AiCombatRequest() { }

    static Optional<AiProposal> interpret(String text) {
        if (text == null) return Optional.empty();
        String noArgument = switch (text) {
            case "combat status", "combat mode status", "show combat mode" -> "combat_status";
            case "protect me on", "protection for me on", "turn my protection on", "enable my protection" -> "protect_requester_on";
            case "protect me off", "protection for me off", "turn my protection off", "disable my protection" -> "protect_requester_off";
            case "protect me status", "my protection status", "show my protection" -> "protect_requester_status";
            default -> "";
        };
        if (!noArgument.isEmpty()) return proposal(noArgument, "");
        if (text.matches("(?:combat(?: mode)? |(?:set|switch) combat(?: mode)? (?:to )?)(?:avoid|defensive|aggressive)"))
            return proposal("combat_mode", text.substring(text.lastIndexOf(' ') + 1));
        if (text.startsWith("attack ")) {
            String target = text.substring(7).replaceFirst("^(?:a|an|the) ", "");
            String bare = target.replace(' ', '_');
            if (MOB_WORDS.contains(bare)) target = "minecraft:" + bare;
            return proposal("attack", target);
        }
        return Optional.empty();
    }

    static boolean contains(AiProposal proposal) {
        return proposal.steps().stream().anyMatch(step -> ACTIONS.contains(step.action()));
    }

    /** Model output and a confirmation of unrelated work cannot create combat authority. */
    static boolean permitted(AiProposal proposal, Optional<AiProposal> currentDirect) {
        return !contains(proposal) || currentDirect.filter(direct -> direct.steps().equals(proposal.steps())).isPresent();
    }

    static boolean requestFamily(String text) {
        return text.matches("(?:attack|combat|protect)(?: .*|)")
                || text.matches("(?:set|switch) combat(?: .*|)")
                || text.matches("(?:turn|enable|disable) my protection(?: .*|)")
                || text.matches("(?:my protection|protection for me)(?: .*|)");
    }

    private static Optional<AiProposal> proposal(String action, String target) {
        try {
            var step = new AiProposal.Step(action, target, 0);
            AiAbilityCatalog.validate(step, false);
            return Optional.of(new AiProposal(List.of(step), ""));
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
}
