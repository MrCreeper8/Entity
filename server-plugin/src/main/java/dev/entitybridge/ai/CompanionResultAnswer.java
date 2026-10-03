package dev.entitybridge.ai;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;
import java.util.Arrays;
import java.util.Set;

/** The program owns result facts; optional model wording cannot replace this core. */
public final class CompanionResultAnswer {
    private CompanionResultAnswer() { }

    /** Only the bounded existing native query commands share this presentation lane. */
    public static boolean nativeReadCommand(String[] command) {
        if (command == null) return false;
        return AiAbilityCatalog.entries().stream()
                .filter(a -> Set.of("stock_status", "build_status", "build_materials").contains(a.action()))
                .anyMatch(a -> a.commandPrefix().equals(Arrays.asList(command)));
    }

    public static Optional<String> answer(JsonObject fact) {
        if(fact==null || !fact.has("status")) return Optional.empty();
        String status=value(fact,"status").toLowerCase(Locale.ROOT);
        String detail=value(fact,"detail").replaceAll("\\s+"," ").strip();
        // The bridge already bounds this native observation. Do not cut its late
        // material counts again or let optional social wording replace them.
        if (value(fact,"kind").equals("read_query_result"))
            return Optional.of(detail.isBlank() ? "That query returned no observed details." : detail);
        if(value(fact,"kind").equals("dispatch_result") && status.equals("accepted")
                && detail.equals("explicit owned-Home sleep requested"))
            return Optional.of("I'm going to my Home bed. Sleeping isn't confirmed yet.");
        if(fact.has("number")) {
            String label="mission #"+value(fact,"number");
            String objective=shorten(value(fact,"objective").replaceAll("\\s+"," ").strip());
            String work=objective.isBlank()?label:objective;
            if(status.equals("cancelled") && value(fact,"phase").equals("superseded"))
                return Optional.of("I've stopped "+work+" because you gave me a newer request.");
            if(status.equals("cancelled") && value(fact,"phase").equals("stop"))
                return Optional.of("I've stopped "+work+" because you told me to stop.");
            if(status.equals("succeeded")) return Optional.of(completed(work));
            if(status.equals("blocked")) return Optional.of("I'm blocked on "+work+": "+obstacle(detail));
            if(status.equals("failed")) return Optional.of("I couldn't complete "+work+": "+obstacle(detail));
            if(status.equals("cancelled")) return Optional.of("I've stopped "+work+": "+obstacle(detail));
        }
        if(!detail.isBlank() && (value(fact,"kind").equals("dispatch_result") || value(fact,"kind").equals("game_result")))
            return Optional.of(shorten(detail));
        return Optional.empty();
    }
    private static String completed(String objective) {
        if(objective.startsWith("obtain and deliver ")) return "I've delivered "+objective.substring(19)+".";
        if(objective.startsWith("obtain and keep ")) return "I've obtained and kept "+objective.substring(16)+".";
        if(objective.startsWith("hand over ")) return "I've handed over "+objective.substring(10)+".";
        if(objective.startsWith("obtain own ")) return "I've equipped my own "+objective.substring(11)+".";
        return switch(objective) {
            case "come to you once" -> "I'm here.";
            case "home" -> "I'm back home.";
            case "sleep" -> "I've slept.";
            case "stock" -> "I've completed the stock run.";
            default -> "I've completed "+objective+".";
        };
    }
    public static boolean event(JsonObject fact) {
        if(fact==null || Set.of("dispatch_result", "read_query_result").contains(value(fact,"kind"))) return false;
        return switch(value(fact,"status").toLowerCase(Locale.ROOT)) {
            case "succeeded","completed","blocked","failed","cancelled" -> true;
            default -> false;
        };
    }
    /** Keep the optional clause expressive: no amounts, actors, actions or repeated outcome. */
    public static String expressiveClause(String reply) {
        if(reply==null) return "";
        String clause=reply.replaceAll("\\s+"," ").strip();
        if(clause.length()>90 || clause.split("\\s+").length>12 || clause.matches(".*[0-9?;:].*")) return "";
        String lower=clause.toLowerCase(Locale.ROOT);
        if(lower.matches(".*\\b(complete[ds]?|finish(ed)?|done|block(ed)?|fail(ed)?|cancel(led)?|stop(ped)?|"
                +"deliver(ed)?|brought|bring|got|have|had|get|gave|give|obtain(ed)?|mine[ds]?|craft(ed)?|"
                +"kill(ed)?|attack(ed)?|hit|die[ds]?|died|lost|found|went|go|come|sleep|slept|"
                +"mission|item|food|sword|wood|iron|stone|home|player|owner|you|he|she|they|we)\\b.*")) return "";
        // Multiple sentences are a restatement, not a compact expressive clause.
        if(clause.replaceAll("[.!]+$","").matches(".*[.!].*")) return "";
        return clause;
    }
    private static String obstacle(String detail) {
        return detail.isBlank()?"no specific cause was reported.":shorten(detail);
    }
    private static String value(JsonObject fact,String key) {
        return fact.has(key)&&fact.get(key).isJsonPrimitive()?fact.get(key).getAsString():"";
    }
    private static String shorten(String text) {
        if(text.length()<=280) return text;
        int space=text.lastIndexOf(' ',277);return text.substring(0,space>0?space:277)+"…";
    }
}
