package dev.entitybridge.command;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Small pure presentation decisions shared by routing and regression tests. */
final class CommandPresentation {
    static String[] withGroupDefault(String[] args) {
        if (args.length != 1) return args;
        String action = CommandInput.canonicalAction(args[0]);
        if (Set.of("home", "farm", "build", "stock", "idle", "visuals", "perception", "combat", "protect").contains(action))
            return new String[]{action, "status"};
        if (action.equals("area")) return new String[]{action, "list"};
        return args; // Preserve legacy bare Gear (iron) and Tidy (preview).
    }

    static boolean replacesWork(String operation, JsonObject args) {
        if (!args.has("operation")) return false;
        String action = args.get("operation").getAsString();
        return "policy.stock".equals(operation) && action.equals("run")
            || "policy.home".equals(operation) && Set.of("go", "establish", "setup").contains(action)
            || "policy.tidy".equals(operation) && action.equals("confirm");
    }

    static String[] queueCompletionSegment(String[] args) {
        if (args.length < 2) return new String[]{""};
        String joined = String.join(" ", Arrays.copyOfRange(args, args[1].equalsIgnoreCase("add") ? 2 : 1, args.length));
        String segment = joined.substring(joined.lastIndexOf(';') + 1).stripLeading();
        return segment.split(" +", -1);
    }

    static List<String> queueCompletionReplacements(String[] args, List<String> suggestions) {
        String token = args[args.length - 1];
        int separator = token.lastIndexOf(';');
        if (separator < 0) return suggestions;
        String prefix = token.substring(0, separator + 1);
        return suggestions.stream().map(value -> prefix + value).toList();
    }
}
