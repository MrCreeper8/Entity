package dev.entitybridge.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.BiFunction;

/** Current text supplies authority; dialogue and model output cannot supply it. */
public final class CompanionTurnPolicy {
    public record Decision(boolean authorizedOrder, boolean preserveCurrent, boolean stopThenCome,
                           Optional<AiProposal> direct) { }

    private static final Decision CONVERSATION = new Decision(false, false, false, Optional.empty());
    private static final Set<String> ACQUIRE = Set.of("get", "collect", "obtain", "bring", "fetch", "give", "gimme", "mine", "dig", "make", "craft");
    private static final Set<String> NON_OBJECT = Set.of("me", "yourself", "for", "to", "a", "an", "some", "like", "about");
    private static final Pattern CONTEXT = Pattern.compile("(?:^|[\\s,;])(?:if|unless|when|would|could|should|might|hypothetically|suppose|imagine|no|not|never|don't|dont|cannot|can't|cant|won't|wont|is|are|was|were|said|says|means|asked)(?:$|[\\s,;])");
    private static final Pattern QUOTATION = Pattern.compile("[\"`“”«»]|(?:^|\\s)'[^']+'(?:$|[\\s.!?,;])");
    private static final Pattern POLITE = Pattern.compile("^(?:(?:please|just|alright|okay|ok|bruh)[ ,]+)+");
    private static final Pattern COURTESY = Pattern.compile("^(?:can|could|would) you (?:please )?");
    private static final Pattern QUANTITY = Pattern.compile("(?:^|\\s)(?:[1-9][0-9]{0,3}|a|an|another|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand)(?:$|\\s)");
    private static final Pattern CLAUSE = Pattern.compile("\\s*(?:;|,\\s*(?:then\\s+)?|\\s+(?:and then|and also|then|and)\\s+)\\s*");
    private static final Pattern PRESERVE = Pattern.compile("(?:keep|continue|finish) (?:(?:the|your|current|existing|same) )*(?:(?:food|bring) )?(?:mission|job|work|task)|(?:keep|continue) (?:bringing|fetching|collecting|mining)(?: me)? [a-z0-9_:-]+(?: [a-z0-9_:-]+){0,3}");
    private static final Pattern QUESTION = Pattern.compile("(?:what|where|why|how|who|when|is|are|do|does|can|tudsz|und)\\b.*|tell me (?:what|where|why|how|who)\\b.*");
    private static final Pattern PRESERVE_CONTEXT = Pattern.compile("(?:^|[\\s,;])(?:if|unless|would|could|should|no|not|never|don't|dont)(?:$|[\\s,;])");
    private static final Pattern REQUEST_REITERATION = Pattern.compile(
            "^(?:(?:yeah|yes|okay|ok|well)[ ,]+)?(?:but )?i (?:said|asked(?: you)?(?: to)?|told you to) ");
    private static final Pattern REQUEST_LEAD = Pattern.compile(
            "^(?:(?:then|yeah|yes|well|please|just|alright|okay|ok|bruh)[ ,]+(?:but[ ,]+)?)+");
    private static final Pattern REQUEST_VOCATIVE = Pattern.compile("^(?:motherfucker|dude|bro|mate)[ ,]+");
    private static final Pattern COLLECTIVE_REQUEST = Pattern.compile("^(?:let'?s|let us) ");
    private static final Pattern REQUEST_HEAD = Pattern.compile(
            "^(?:sleep|stop|come|go|goto|lie|lay|use|right[ -]?click|follow|home|stock|pause|resume|retry|skip|clear|cancel"
                    + "|get|collect|obtain|bring|fetch|give|gimme|hand|pass|mine|dig|make|craft|gear|equip|upgrade|run|farm|build|construct"
                    + "|tidy|clean|idle|enable|disable|turn|grab|gather|prepare|head|walk|rest|attack|combat|protect|set|switch)(?:$| )");
    private static final String BED = "(?:(?:the|your|a) )?(?:(?:fucking|damn|goddamn) )?bed";
    private static final Pattern SLEEP_CONTROL = Pattern.compile(
            "(?:sleep|go (?:to )?sleep|(?:go to|(?:lie|lay)(?: down)? (?:in|on)) " + BED
                    + "(?: and (?:right[ -]?click|use) (?:it|" + BED + "))?"
                    + "|(?:right[ -]?click|use) " + BED + ")");
    private static final Pattern CONTROL_COURTESY = Pattern.compile("(?: (?:now|already|please|pls|plz))+$");

    private CompanionTurnPolicy() { }

    /**
     * Bounded admission, not a natural-language planner. Recognised imperatives
     * still need the existing typed proposal validation and request fence.
     * Everything unfamiliar can only become an expiring confirmation offer.
     */
    public static Decision classify(String request) {
        return classify(request, (action, target) -> Optional.empty());
    }

    /** Same whole-request authority fences; the native action resolver can type finite counted clauses. */
    public static Decision classify(String request, BiFunction<String, String, Optional<String>> targetResolver) {
        if (request == null || request.isBlank() || request.length() > 4096) return CONVERSATION;
        String text = request.strip().toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ");
        if (QUOTATION.matcher(text).find()) return CONVERSATION;
        text = text.replaceFirst("[.!]+$", "").stripTrailing();

        // Exact present-state aliases use the existing native read actions before
        // the generic copula/question fence. They never become mission authority.
        var nativeQuery = AiExistingControlRequest.readQuery(text);
        if (nativeQuery.isPresent()) return new Decision(false, false, false, nativeQuery);

        // Exact whole Pause/Resume aliases contain factual words such as
        // "what"/"were". Resolve their typed current request before generic
        // fact/preserve/copula handling, without removing any context fence.
        var missionControl = AiExistingControlRequest.currentMissionControl(text);
        if (missionControl.isPresent()) return new Decision(true, false, false, missionControl);

        // Preserve-current is deliberately not a new order, including when a
        // factual question shares the turn. Never reconstruct the old job.
        if (preservesCurrent(text)) return new Decision(false, true, false, Optional.empty());
        if (AiDirectRequest.isFoodQuery(text)) return new Decision(false, false, false,
                AiDirectRequest.interpret(text));
        text = currentRequestBody(text);
        // A bare ability question supplies no bedtime objective. Explicit
        // "please sleep" / "go to sleep" requests still use the existing control.
        if(text.matches("(?:can|could) you sleep[?]?")) return new Decision(false,false,false,Optional.of(new AiProposal(List.of(),
                "Yes—Sleep uses a registered Home bed at night when it's safe. I haven't started it.")));
        var courtesy = COURTESY.matcher(text);
        if (courtesy.find()) {
            boolean explicitPlease = courtesy.group().contains("please");
            String requested = courtesy.replaceFirst("").replaceFirst("[?]+$", "").strip();
            // Ask for a missing amount only after this whole clause is a real
            // request. Bare "can you get diamonds?" asks about capability.
            if (!clearCourtesyRequest(requested, explicitPlease)) return CONVERSATION;
            if (!CONTEXT.matcher(requested).find() && supportedOrder(requested) && missingQuantity(requested))
                return quantityQuestion();
            text = requested;
        }
        if (text.indexOf('?') >= 0 || CONTEXT.matcher(text).find()) return CONVERSATION;
        text = POLITE.matcher(text).replaceFirst("");
        Optional<AiProposal> direct = AiDirectRequest.interpret(text, targetResolver);
        if (direct.isPresent()) {
            boolean order = !AiAbilityCatalog.query(direct.get().steps().getFirst().action());
            return new Decision(order, false, false, direct);
        }
        // Saved-project controls and read-only queries are not a request to
        // choose a new construction site. The existing explicit preview still
        // owns genuinely new builds, after whole-clause controls are resolved.
        if(text.matches("(?:build|construct) (?:me )?.+"))
            return new Decision(false,false,false,Optional.of(new AiProposal(List.of(),
                    "Use /e build to choose a design and preview its space, then confirm it. I haven't changed your current work.")));
        String control = CONTROL_COURTESY.matcher(text).replaceFirst("");
        if (control.equals("stop") || SLEEP_CONTROL.matcher(control).matches()) {
            String action = control.equals("stop") ? "stop" : "sleep";
            return new Decision(true, false, false, Optional.of(new AiProposal(
                    List.of(new AiProposal.Step(action, "", 0)), "")));
        }

        String[] clauses = CLAUSE.split(text, -1);
        if (clauses.length == 2 && clauses[0].equals("stop")) {
            String come = clauses[1].replaceFirst(" then$", "");
            if (AiDirectRequest.interpret(come).filter(p -> p.steps().getFirst().action().equals("come")).isPresent())
                return new Decision(true, false, true, Optional.empty());
        }
        if (clauses.length > 8) return CONVERSATION;
        List<AiProposal.Step> typedClauses = new ArrayList<>();
        for (String clause : clauses) {
            if (!supportedOrder(clause)) return CONVERSATION;
            if (clauses.length > 1 && !finiteOrder(clause)) return CONVERSATION;
            var typed = AiDirectRequest.interpret(clause, targetResolver);
            if (typed.isPresent()) typedClauses.addAll(typed.get().steps());
        }
        if (clauses.length > 1 && typedClauses.size() == clauses.length) {
            try {
                for (var step : typedClauses) AiAbilityCatalog.validate(step, true);
                return new Decision(true, false, false, Optional.of(new AiProposal(typedClauses, "")));
            } catch (IllegalArgumentException invalid) { return CONVERSATION; }
        }
        if(missingQuantity(text)) return quantityQuestion();
        return new Decision(true, false, false, Optional.empty());
    }

    /**
     * Only an anchored current request may shed discourse, a vocative, or a
     * collective/reiteration wrapper. This is not substring command hunting:
     * the remaining whole clause still passes every semantic fence and the
     * existing command mapping. In particular, "you said sleep" is not a request.
     */
    static String currentRequestBody(String source) {
        String body = REQUEST_REITERATION.matcher(source).replaceFirst("");
        boolean historical = !body.equals(source);
        body = REQUEST_LEAD.matcher(body).replaceFirst("");
        body = REQUEST_VOCATIVE.matcher(body).replaceFirst("");
        body = REQUEST_LEAD.matcher(body).replaceFirst("");
        body = COLLECTIVE_REQUEST.matcher(body).replaceFirst("");
        body = AiDirectRequest.normalizeCarriedHandoff(body);
        // 'go' introduces an ordinary task, not a separate navigation objective.
        body = body.replaceFirst("^go (?=(?:get|collect|obtain|bring|fetch|give|gimme|mine|dig|make|craft|follow) )", "");
        if(REQUEST_HEAD.matcher(body).find()) {
            // Emphasis and politeness are lexical decoration of an already
            // anchored request. Never erase negation, conditions or quotations.
            body = body.replaceAll("\\b(?:fucking|damn|goddamn) +", "");
            body = CONTROL_COURTESY.matcher(body).replaceFirst("");
        }
        // Historical combat/current-mission statements are not renewed control authority,
        // even when a politeness wrapper occurs inside the historical statement.
        if (historical && (AiCombatRequest.requestFamily(body)
                || AiExistingControlRequest.currentMissionControl(body).isPresent())) return source;
        return REQUEST_HEAD.matcher(body).find() ? body : source;
    }
    /** Request-shaped language must clarify, never masquerade as action-free banter. */
    public static boolean attemptsWork(String request) {
        if(request==null || QUOTATION.matcher(request).find()) return false;
        String body=currentRequestBody(request.strip().toLowerCase(Locale.ROOT).replace('’','\'')
                .replaceAll("\\s+"," ").replaceFirst("[.!?]+$", ""));
        return REQUEST_HEAD.matcher(body).find() && !CONTEXT.matcher(body).find();
    }
    private static Decision quantityQuestion() {
        return new Decision(false,false,false,Optional.of(new AiProposal(List.of(),"How many should I get for that request?")));
    }
    private static boolean missingQuantity(String request) {
        String normalized=request.replaceAll("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])", " ");
        boolean previousCount=false;
        for(String source:CLAUSE.split(normalized,-1)) {
            String clause=POLITE.matcher(source).replaceFirst("").replaceFirst(" please$", "").strip();
            // The quantity gate applies to an unresolved acquisition outcome,
            // not to a typed loadout/control whose catalog count is already zero.
            if (AiDirectRequest.interpret(clause).isPresent()) continue;
            String head=clause.split(" ",2)[0];
            if(!ACQUIRE.contains(head)) continue;
            boolean counted=QUANTITY.matcher(clause).find() || clause.matches(".*\\ba single\\b.*");
            // An explicit new request can reference a real earlier objective;
            // the bounded interpreter must resolve its count, never invent it.
            if(clause.matches("(?:get|bring|fetch|give|make|craft)(?: me)? (?:it|them|the thing)")) continue;
            if(!counted && !(head.equals("give") && previousCount && clause.matches(".*\\b(?:it|them)\\b.*"))) return true;
            previousCount=counted || previousCount;
        }
        return false;
    }

    /** An explicit but unrecognised request can propose work, never execute it. */
    public static boolean maySuggest(String request) {
        Decision decision = classify(request);
        if (decision.authorizedOrder() || decision.preserveCurrent() || decision.direct().isPresent()) return false;
        if (request == null || request.isBlank() || request.length() > 4096) return false;
        String text = request.strip().toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ");
        if (QUOTATION.matcher(text).find()) return false;
        text = text.replaceFirst("[.!?]+$", "").stripTrailing();
        text = currentRequestBody(text);
        // Bare 'would you...' may be hypothetical; an explicit please is the
        // conservative request signal. This still permits only a confirmed offer.
        var courtesy = COURTESY.matcher(text);
        if (courtesy.find()) {
            boolean explicitPlease = courtesy.group().contains("please");
            String requested = courtesy.replaceFirst("").strip();
            // A question must not regain request intent through the offer lane.
            // Unfamiliar but explicit requests still get the existing confirmed offer.
            boolean countedAcquisitionOffer = requested.matches("(?:grab|gather|prepare) .+")
                    && QUANTITY.matcher(requested).find();
            if (!clearCourtesyRequest(requested, explicitPlease) && !explicitPlease
                    && !courtesyRecipient(requested) && !countedAcquisitionOffer) return false;
            text = requested;
        }
        if (CONTEXT.matcher(text).find() || text.indexOf('?') >= 0) return false;
        text = POLITE.matcher(text).replaceFirst("");
        if (AiCombatRequest.requestFamily(text)) return false; // No inferred combat offers from capability questions.
        if (supportedOrder(text)) return true;
        // Item correction/list grammar belongs to interpretation, not the
        // admission gate. Only a typed confirmed offer can execute this shape.
        if(text.matches("(?:get|bring|fetch|give|make|craft) .{1,180}"))
            return !text.matches(".*\\b(?:yesterday|tomorrow|later|someday)\\b.*");
        return text.matches("(?:grab|gather|prepare|head|walk|rest|lie|lay|use) [a-z0-9_:-]+(?: [a-z0-9_:-]+){0,5}");
    }

    private static boolean preservesCurrent(String text) {
        // Questions may contain ordinary copulas; only imperatives need the
        // stricter order context fence. Conditions/negation still cannot preserve.
        if (PRESERVE_CONTEXT.matcher(text).find()) return false;
        String[] clauses = CLAUSE.split(text.replaceFirst("[?]+$", ""), -1);
        boolean preserve = false;
        for (String part : clauses) {
            String clause = POLITE.matcher(part).replaceFirst("").strip();
            if (PRESERVE.matcher(clause).matches() || Set.of("continue", "keep going", "keep working",
                    "finish up", "keep doing that", "continue what you were doing").contains(clause)) preserve = true;
            else if (!QUESTION.matcher(clause).matches()) return false;
        }
        return preserve;
    }

    private static boolean finiteOrder(String source) {
        String clause = POLITE.matcher(source).replaceFirst("");
        var typed = AiDirectRequest.interpret(clause);
        if (typed.isPresent()) {
            try {
                AiAbilityCatalog.validate(typed.get().steps().getFirst(), true);
                return true;
            } catch (IllegalArgumentException invalid) { return false; }
        }
        String head = clause.split(" ", 2)[0];
        return ACQUIRE.contains(head) || Set.of("come", "home", "go", "return", "gear", "stock", "run").contains(head)
                && !clause.matches("(?:run )?farm(?: .*|)");
    }

    private static boolean supportedOrder(String source) {
        String clause = POLITE.matcher(source).replaceFirst("").replaceFirst(" please$", "").strip();
        if (clause.isEmpty()) return false;
        if (AiCombatRequest.interpret(clause).isPresent()) return true;
        if (AiExistingControlRequest.interpret(clause).isPresent()) return true;
        if (Set.of("follow", "follow me", "go home", "return home", "go to your home", "home",
                "stock", "stock up", "run stock", "run home stock", "pause", "resume", "retry",
                "skip the queue step", "clear the queue", "cancel the queue").contains(clause)) return true;
        if (AiDirectRequest.interpret(clause).filter(p -> Set.of("come", "gear", "goto", "stock")
                .contains(p.steps().getFirst().action())).isPresent()) return true;
        if (clause.matches("follow [a-z0-9_]{1,16}|gear (?:wood|stone|iron|diamond|best)|(?:run )?farm [a-z0-9_:-]{1,64}")) return true;
        int space = clause.indexOf(' ');
        if (space < 0 || !ACQUIRE.contains(clause.substring(0, space))) return false;
        String object = clause.substring(space + 1)
                .replaceFirst("(?:,? ?(?:i'?m ?hungry|imhungry|i am hungry))$", "")
                .replaceAll("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])", " ")
                .replaceFirst(" (?:for me|to me|for yourself|already on you|from your inventory)$", "")
                .replaceFirst("^(?:me|yourself|for me) ", "")
                .replaceFirst("^(?:(?:like|about) )?(?:[1-9][0-9]{0,3}|a|an|some) ", "")
                .replaceFirst(" [1-9][0-9]{0,3}$", "")
                .strip();
        // 'Another' itself is a requested new object; history may identify it
        // only after this current-turn authority decision.
        if (object.equals("another")) return true;
        if (!object.matches("[a-z0-9_:-]+(?: [a-z0-9_:-]+){0,4}")) return false;
        for (String token : object.split(" ")) {
            if (NON_OBJECT.contains(token) || token.matches("[0-9]+")) return false;
        }
        return true;
    }

    private static boolean clearCourtesyRequest(String request, boolean explicitPlease) {
        if (request.indexOf('?') >= 0 || CONTEXT.matcher(request).find()) return false;
        if (AiCombatRequest.requestFamily(request)) return explicitPlease && AiCombatRequest.interpret(request).isPresent();
        if (AiExistingControlRequest.interpret(request).filter(p -> !AiAbilityCatalog.query(p.steps().getFirst().action())).isPresent()) return true;
        if (Set.of("come", "come here", "come to me", "stop", "sleep", "go to sleep", "go home",
                "return home", "follow me", "pause", "resume", "retry", "stock", "run stock").contains(request)) return true;
        if (request.matches("gear (?:wood|stone|iron|diamond|best)")) return true;
        String normalized = request.replaceAll("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])", " ");
        for (String clause : CLAUSE.split(normalized, -1)) {
            var typed = AiDirectRequest.interpret(clause);
            if (typed.filter(p -> Set.of("goto", "stock").contains(p.steps().getFirst().action())).isPresent()) {
                if (explicitPlease) continue;
                return false; // Bare Can/Could-you is capability, not coordinate or Home-stock permission.
            }
            if (typed.filter(p -> p.steps().getFirst().action().equals("come")).isPresent()
                    || Set.of("home", "go home", "return home").contains(clause)) continue;
            if (typed.filter(p -> p.steps().getFirst().action().equals("gear")).isPresent()) {
                if (explicitPlease || clause.startsWith("gear ") || clause.contains("yourself")) continue;
                return false; // An article in a loadout name is not acquisition quantity or capability permission.
            }
            String head = clause.split(" ", 2)[0];
            if (!ACQUIRE.contains(head) || !supportedOrder(clause)) return false;
            boolean recipient = courtesyRecipient(clause);
            if (!explicitPlease && !recipient && !Pattern.compile("(?:^|\\s)(?:[1-9][0-9]{0,3}|a|an|one)(?:$|\\s)")
                    .matcher(clause).find()) return false;
            // A naked capability question about crafting an item is not an order for it.
            if (Set.of("make", "craft").contains(head)
                    && !explicitPlease && !recipient) return false;
        }
        return true;
    }

    private static boolean courtesyRecipient(String request) {
        return request.matches(".*\\b(?:me|yourself)\\b.*");
    }
}
