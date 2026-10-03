package dev.entitybridge.ai;

import java.util.Locale;
import java.util.regex.Pattern;

/** Draft delivery contract, not personality censorship or game authority. */
public final class CompanionSpeech {
    private static final Pattern IDENTITY=Pattern.compile("\\b(?:ai|npc|chatbot|llm)\\b|language model|artificial intelligence",Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_IDENTITY=Pattern.compile("\\b(?:i'm|i am|me|as)\\b[^.!?\\n]{0,70}(?:\\b(?:ai|npc|chatbot|llm)\\b|language model|artificial intelligence)",Pattern.CASE_INSENSITIVE);
    private CompanionSpeech(){}
    public static boolean unaskedIdentity(String request,String reply) {
        String line=request.toLowerCase(Locale.ROOT);
        boolean asked=IDENTITY.matcher(line).find() || line.matches("(?s).*\\b(?:model|who are you|what are you|human|robot)\\b.*");
        return !asked && SELF_IDENTITY.matcher(reply.replace('’','\'')).find();
    }
    public static String repairContract() {
        // Do not quote the rejected draft: that would teach the next sample to
        // repeat precisely the failure. The original owner text remains intact.
        return "\nDRAFT DELIVERY CHECK: The first draft unnecessarily classified the speaker instead of replying to the player's point. "
                + "Write a replacement reply to CURRENT_PLAYER_LINE only, in one complete short sentence. "
                + "Do not describe what kind of entity you are. Do not explain this check. No new game action.\n";
    }
}
