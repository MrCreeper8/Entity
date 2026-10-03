package dev.entitybridge.ai;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns inference scheduling only; the Paper-thread port owns all game authority. */
public final class EntityAi implements AutoCloseable {
    public interface Backend extends AutoCloseable {
        String complete(String system, String request, JsonObject schema) throws Exception;
        default String label() { return "Local AI"; }
        default String completeConversation(String system, JsonArray messages, JsonObject schema, int maxTokens) throws Exception {
            return complete(system, messages.toString(), schema);
        }
        @Override void close();
    }
    public interface Port {
        void mainThread(Runnable callback);
        boolean stillAuthorized(UUID requester, UUID world, String session);
        void reply(UUID requester, String text, boolean error);
        void dispatch(UUID requester, String[] command);
        default void stopThenCome(UUID requester) {
            dispatch(requester,new String[]{"stop"}); dispatch(requester,new String[]{"come"});
        }
        default JsonObject companionFacts(UUID requester) { return new JsonObject(); }
        default Optional<String> resolveItem(String item) { return Optional.empty(); }
        default Optional<String> resolveTarget(String action, String target) {
            return action.equals("mine") ? Optional.empty() : resolveItem(target);
        }
        default void diagnostic(String stage, String type) { }
        default void timing(long queueMillis, long inferenceMillis, String lane) { }
        default long nowMillis() { return System.currentTimeMillis(); }
    }
    private final Backend backend;
    private final Port port;
    private final AiRequestFence fence = new AiRequestFence();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Entity-AI"); thread.setDaemon(true); return thread;
    });
    public static final long RESULT_DEADLINE_MILLIS = 5_000;
    private final long resultDeadlineMillis;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Entity-result-deadline"); thread.setDaemon(true); return thread;
    });
    private ScheduledFuture<?> resultDeadline;
    private final java.util.concurrent.atomic.AtomicBoolean inferenceBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private Future<?> running;
    private AiRequestFence.Ticket pendingTicket;
    private final CompanionDialogue dialogue = new CompanionDialogue();
    private record Turn(UUID player, UUID world, String session, String request, boolean resultOnly, boolean ambient, JsonObject incident, long queuedAt, String factualCore) {
        private Turn(UUID player, UUID world, String session, String request, boolean resultOnly, boolean ambient, JsonObject incident, long queuedAt) {
            this(player,world,session,request,resultOnly,ambient,incident,queuedAt,null);
        }
        boolean event() { return factualCore != null; }
    }
    private final ArrayDeque<Turn> turns = new ArrayDeque<>();
    private Turn activeTurn;
    private record Offer(AiProposal action,long expiresAt) { }
    private final LinkedHashMap<CompanionFeedback.Owner,Offer> offers=new LinkedHashMap<>();
    private final LinkedHashMap<CompanionFeedback.Owner,CompanionFactAnswer.Query> lastQueries=new LinkedHashMap<>();
    private record WorkNotice(String text,long observedAt) { }
    private final LinkedHashMap<CompanionFeedback.Owner,WorkNotice> lastWorkNotices=new LinkedHashMap<>();

    public EntityAi(Backend backend, Port port) { this(backend,port,RESULT_DEADLINE_MILLIS); }
    EntityAi(Backend backend, Port port, long resultDeadlineMillis) {
        this.backend = backend; this.port = port; this.resultDeadlineMillis = resultDeadlineMillis;
    }

    public void invalidate() {
        fence.invalidate();
        if (pendingTicket != null && !closed && (activeTurn == null || !activeTurn.ambient)) port.reply(pendingTicket.requester(), activeTurn == null
                ? "AI request superseded; no action was started." : "Cancelled that pending request.", false);
        pendingTicket = null;
        if(resultDeadline!=null) { resultDeadline.cancel(false); resultDeadline=null; }
        turns.clear(); activeTurn = null;
        offers.clear();
        if (running != null) { running.cancel(true); running = null; }
    }

    /** Conversation waits for the preceding turn; small talk never cancels an accepted job. */
    public void converse(UUID player, UUID world, String session, String request) {
        if (closed) throw new IllegalStateException(backend.label() + " is off.");
        if (request.isBlank() || request.length() > 1000 || request.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("Please keep that message under 1000 characters.");
        // Every fresh user turn, including a code-answered factual question, preempts optional wording.
        cancelAmbient();
        CompanionTurnPolicy.Decision decision=CompanionTurnPolicy.classify(request);
        // Asking whether a skill exists is a fact read, not a social draft of
        // an acceptance receipt. No inference, offer, or work mutation occurs.
        Optional<String> capability=CompanionCapabilityAnswer.answer(request,port::resolveTarget);
        if(capability.isPresent()) {
            if(!port.stillAuthorized(player,world,session)) return;
            Turn factTurn=new Turn(player,world,session,request,false,false,null,port.nowMillis());
            dialogue.addFact(player,world,session,"user",request);
            speakFact(factTurn,capability.get());
            port.timing(0,0,"capability-facts"); return;
        }
        // A facts question doesn't wait behind inference, cancel it, or replace an accepted job.
        CompanionFactAnswer.Query query=CompanionFactAnswer.query(request);
        var scope=new CompanionFeedback.Owner(player,world,session);
        if(query==CompanionFactAnswer.Query.NONE && request.strip().equalsIgnoreCase("TLDR"))
            query=lastQueries.getOrDefault(scope,CompanionFactAnswer.Query.NONE);
        if(!decision.authorizedOrder() && (query!=CompanionFactAnswer.Query.NONE || decision.preserveCurrent())) {
            if(!port.stillAuthorized(player,world,session)) return;
            Turn factTurn=new Turn(player,world,session,request,false,false,null,port.nowMillis());
            dialogue.addFact(player,world,session,"user",request);
            JsonObject facts=port.companionFacts(player).deepCopy();
            if(query!=CompanionFactAnswer.Query.NONE) {
                lastQueries.put(scope,query);
                while(lastQueries.size()>32) lastQueries.remove(lastQueries.keySet().iterator().next());
            }
            if(decision.preserveCurrent() && facts.has("current_job") && facts.get("current_job").isJsonObject()
                    && "PAUSED".equalsIgnoreCase(text(facts.getAsJsonObject("current_job"),"status")))
                port.dispatch(player,new String[]{"resume"});
            WorkNotice notice=lastWorkNotices.get(scope);
            Optional<String> answer=query==CompanionFactAnswer.Query.CAUSE && notice!=null
                    && port.nowMillis()-notice.observedAt()<=120_000
                    ?Optional.of("Last reported result: "+notice.text()) : CompanionFactAnswer.answer(query,facts);
            if(answer.isPresent()) speakFact(factTurn,answer.get());
            else if(query==CompanionFactAnswer.Query.HOME) port.dispatch(player,new String[]{"home","status"});
            else speakFact(factTurn,CompanionFactAnswer.answer(CompanionFactAnswer.Query.PROGRESS,facts).orElse("No current mission is recorded."));
            port.timing(0,0,"facts"); return;
        }
        if (isImmediate(request) || activeTurn != null && activeTurn.ambient) invalidate();
        if (turns.size() >= 8) { port.reply(player, "I'm still catching up with your messages—give me a moment.", false); return; }
        turns.add(new Turn(player,world,session,request,false,false,null,port.nowMillis()));
        nextTurn();
    }

    /** Structured observed result, not model-authored progress. Called on Paper thread. */
    public void observe(UUID player, UUID world, String session, JsonObject result, boolean announce) {
        dialogue.addFact(player,world,session,"assistant","[Observed game result] " + result);
        // Reading Stock/project facts cannot replace the last actual work cause.
        if (result != null && !text(result, "kind").equals("read_query_result")) CompanionResultAnswer.answer(result).ifPresent(core -> {
            lastWorkNotices.put(new CompanionFeedback.Owner(player,world,session),new WorkNotice(core,port.nowMillis()));
            while(lastWorkNotices.size()>32)lastWorkNotices.remove(lastWorkNotices.keySet().iterator().next());
        });
        if(!announce || closed || !port.stillAuthorized(player,world,session)) return;
        Optional<String> core=CompanionResultAnswer.answer(result);
        if(core.isEmpty()) return;
        // Acceptance, diagnostics and actionable dispatch errors are never a second AI acknowledgement.
        if(!CompanionResultAnswer.event(result) || activeTurn!=null || pendingTicket!=null || !turns.isEmpty() || inferenceBusy.get()) {
            port.reply(player,core.get(),false); return;
        }
        turns.add(new Turn(player,world,session,"Observed result: "+result
                +". The program will say this factual core: "+core.get()
                +" Give only an optional short expressive clause, not another factual result sentence.",
                true,true,null,port.nowMillis(),core.get()));
        nextTurn();
    }

    /** Low-priority one-line social reply. Never queues behind user work or starts skills. */
    public boolean react(UUID player, UUID world, String session, JsonObject incident) {
        if (closed || activeTurn != null || pendingTicket != null || !turns.isEmpty() || inferenceBusy.get()
                || !freshIncident(incident,port.nowMillis())) return false;
        observe(player, world, session, incident, false);
        turns.add(new Turn(player, world, session,
                "React to this observed incident, not a player order: " + incident,
                true, true, incident.deepCopy(),port.nowMillis()));
        nextTurn(); return true;
    }

    public void forget(UUID player) { dialogue.forget(player); offers.keySet().removeIf(key -> key.player().equals(player)); lastQueries.keySet().removeIf(key -> key.player().equals(player)); lastWorkNotices.keySet().removeIf(key -> key.player().equals(player)); }

    public void cancelAmbient() { if (activeTurn != null && activeTurn.ambient) invalidate(); }

    public void acknowledge(UUID player, UUID world, String session, JsonObject fact, String reply) {
        observe(player,world,session,fact,false);
        dialogue.addFact(player,world,session,"assistant",reply);
        port.reply(player,reply,false);
    }

    private static boolean isImmediate(String request) {
        var decision=CompanionTurnPolicy.classify(request);
        return decision.stopThenCome() || decision.authorizedOrder();
    }

    private static String text(JsonObject object,String key) {
        JsonElement value=object.get(key); return value!=null&&value.isJsonPrimitive()?value.getAsString():"";
    }

    private void nextTurn() {
        if (closed || activeTurn != null || turns.isEmpty()) return;
        Turn turn = turns.removeFirst(); activeTurn = turn;
        if (!port.stillAuthorized(turn.player,turn.world,turn.session)) { activeTurn=null; nextTurn(); return; }
        if (!turn.resultOnly) {
            if(CompanionTurnPolicy.classify(turn.request).authorizedOrder()) dialogue.addFact(turn.player,turn.world,turn.session,"user",turn.request);
            else dialogue.add(turn.player,turn.world,turn.session,"user",turn.request);
        }
        var ticket = fence.begin(turn.player,turn.world,turn.session,port.nowMillis()); pendingTicket=ticket;
        JsonObject facts = port.companionFacts(turn.player).deepCopy();
        // An automatic incident needs its observed fact, not stale delivery/planner dialogue.
        CompanionTurnPolicy.Decision authority=CompanionTurnPolicy.classify(turn.request,port::resolveTarget);
        boolean suggest=!turn.resultOnly && CompanionTurnPolicy.maySuggest(turn.request);
        boolean planning=!turn.resultOnly && (authority.authorizedOrder() || suggest);
        JsonArray messages = turn.resultOnly ? new JsonArray() : planning
                ? dialogue.requestMessages(turn.player,turn.world,turn.session,turn.request)
                : dialogue.socialMessages(turn.player,turn.world,turn.session);
        CompanionFeedback.Owner scope=new CompanionFeedback.Owner(turn.player,turn.world,turn.session);
        if(!turn.resultOnly) {
            Offer offer=offers.remove(scope);
            if(Set.of("yes","yes please","yeah","yep","sure","go ahead","do it","go do it","okay","ok").contains(turn.request.strip().toLowerCase(Locale.ROOT))) {
                CompanionProposal answer=offer!=null&&offer.expiresAt>port.nowMillis()
                        ?new CompanionProposal(offer.action,"")
                        :new CompanionProposal(new AiProposal(List.of(),""),"No pending action is awaiting confirmation. Tell me the action and target.");
                post(() -> finishTurn(ticket,turn,answer,true));return;
            }
        }
        if (turn.resultOnly) {
            JsonObject message=new JsonObject();message.addProperty("role","user");message.addProperty("content",turn.request);messages.add(message);
        }
        // Exact movement/sleep controls use existing skills immediately. Queries
        // deliberately use observed facts and natural speech, not raw query dumps.
        if (!turn.resultOnly && authority.stopThenCome()) {
            post(() -> {
                if(fence.consume(ticket,turn.player,turn.world,turn.session,port.stillAuthorized(turn.player,turn.world,turn.session),port.nowMillis())) {
                    pendingTicket=null;port.stopThenCome(turn.player);port.timing(0,0,"stop-come");
                }
                if(activeTurn==turn){activeTurn=null;nextTurn();}
            }); return;
        }
        if (!turn.resultOnly && authority.direct().isPresent()) {
            post(() -> {port.timing(0,0,"direct");finishTurn(ticket,turn,new CompanionProposal(authority.direct().get(),authority.direct().get().clarification()));});
            return;
        }
        if(!turn.resultOnly && authority.authorizedOrder()) {
            var direct=AiDirectRequest.interpret(CompanionTurnPolicy.currentRequestBody(turn.request.strip()
                    .toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").replaceFirst("[.!]+$","")),port::resolveTarget);
            if(direct.isPresent()) {post(() -> {port.timing(0,0,"direct");finishTurn(ticket,turn,new CompanionProposal(direct.get(),""));});return;}
        }
        if(!turn.resultOnly && !planning && CompanionTurnPolicy.attemptsWork(turn.request)) {
            post(() -> finishTurn(ticket,turn,new CompanionProposal(new AiProposal(List.of(),""),
                    "I haven't started that. Which action, item and amount do you mean?")));return;
        }
        long inferenceAt=port.nowMillis();
        String lane=turn.event()?"result":turn.ambient?"reaction":planning?"request":"social";
        if(turn.event()) resultDeadline=deadlines.schedule(() -> post(() -> finishEvent(ticket,turn,"",true)),
                Math.max(0,turn.queuedAt+resultDeadlineMillis-inferenceAt),TimeUnit.MILLISECONDS);
        running=worker.submit(() -> {
            inferenceBusy.set(true);
            try {
                String system=turn.event()?CompanionProposal.resultPrompt(facts)
                        :turn.ambient?CompanionProposal.reactionPrompt(facts,turn.incident)
                        :turn.resultOnly?CompanionProposal.resultPrompt(facts)
                        :planning?CompanionProposal.requestPrompt(suggest&&!authority.authorizedOrder()):CompanionProposal.socialPrompt(facts,turn.request);
                JsonObject schema=authority.authorizedOrder()&&!turn.resultOnly?CompanionProposal.requestSchema()
                        :suggest?CompanionProposal.suggestionSchema():turn.resultOnly
                        ?CompanionProposal.explanationSchema():CompanionProposal.socialSchema(facts,turn.request);
                String output=backend.completeConversation(system,messages,schema,
                        CompanionProposal.outputBudget(facts,turn.request,planning,turn.ambient));
                CompanionProposal proposal=CompanionProposal.parse(output);
                if(!planning&&!turn.resultOnly&&CompanionSpeech.unaskedIdentity(turn.request,proposal.reply())) {
                    port.diagnostic("speech","unasked identity draft; one replacement attempt");
                    output=backend.completeConversation(system+CompanionSpeech.repairContract(),messages,schema,
                            CompanionProposal.outputBudget(facts,turn.request,false,false));
                    proposal=CompanionProposal.parse(output);
                    if(CompanionSpeech.unaskedIdentity(turn.request,proposal.reply()))
                        throw new IllegalArgumentException("replacement still violated conversation delivery contract");
                }
                if(!authority.authorizedOrder() && !proposal.action().steps().isEmpty()
                        || (turn.resultOnly || !suggest&&!authority.authorizedOrder()) && !proposal.offer().steps().isEmpty())
                    throw new IllegalArgumentException("current turn did not authorize work");
                long inferenceMillis=port.nowMillis()-inferenceAt;
                CompanionProposal deliverable=proposal;
                post(() -> {port.timing(Math.max(0,inferenceAt-turn.queuedAt),inferenceMillis,lane);finishTurn(ticket,turn,deliverable);});
            } catch(Exception failure) {
                String type=failure.getClass().getSimpleName()+": "+Objects.toString(failure.getMessage(),"");
                post(() -> {
                    port.diagnostic("companion",type.substring(0,Math.min(type.length(),180)));
                    if(turn.event()) { finishEvent(ticket,turn,"",false); return; }
                    if (fence.consume(ticket,turn.player,turn.world,turn.session,port.stillAuthorized(turn.player,turn.world,turn.session),port.nowMillis())) {
                        pendingTicket=null;
                        if (!turn.ambient) speak(turn,"I couldn't make sense of that response. I haven't started anything new.",true);
                    }
                    if(activeTurn==turn){activeTurn=null;nextTurn();}
                });
            } finally { inferenceBusy.set(false); }
        });
    }

    private void finishTurn(AiRequestFence.Ticket ticket, Turn turn, CompanionProposal answer) {
        finishTurn(ticket,turn,answer,false);
    }
    private void finishTurn(AiRequestFence.Ticket ticket, Turn turn, CompanionProposal answer, boolean confirmedOffer) {
        if(turn.event()) { finishEvent(ticket,turn,answer.reply(),false); return; }
        boolean fresh=!turn.ambient || freshIncident(turn.incident,port.nowMillis());
        if (!fence.consume(ticket,turn.player,turn.world,turn.session,port.stillAuthorized(turn.player,turn.world,turn.session),port.nowMillis())) {
            if(ticket.equals(pendingTicket))pendingTicket=null;
            if(activeTurn==turn){activeTurn=null;nextTurn();} return;
        }
        pendingTicket=null;
        if(!fresh) { if(activeTurn==turn){activeTurn=null;nextTurn();} return; }
        // The model names intent; the existing Minecraft resolver names real
        // action targets. Mine uses native block groups, other acquisition uses
        // item aliases. Apply that same boundary to offers and dispatch,
        // never require a small language model to invent exact registry keys.
        answer=new CompanionProposal(resolveTargets(answer.action()),answer.reply(),resolveTargets(answer.offer()));
        var authority=CompanionTurnPolicy.classify(turn.request);
        if (!AiDirectRequest.permittedCoordinates(answer.action(),
                CompanionTurnPolicy.classify(turn.request, port::resolveTarget).direct())) {
            speakFact(turn, "I haven't started a new trip. Give me the exact x y z coordinates you want.");
            if(activeTurn==turn){activeTurn=null;nextTurn();} return;
        }
        if (answer.offer().steps().stream().anyMatch(step -> step.action().equals("goto")))
            answer = new CompanionProposal(answer.action(), answer.reply(), new AiProposal(List.of(), ""));
        if (!AiCombatRequest.permitted(answer.action(), authority.direct())) {
            port.diagnostic("authority", "combat proposal lacked exact current request");
            speakFact(turn, "I haven't started combat or changed protection. Use an explicit mob type or combat/protection control.");
            if(activeTurn==turn){activeTurn=null;nextTurn();} return;
        }
        if (AiCombatRequest.contains(answer.offer()))
            answer = new CompanionProposal(answer.action(), answer.reply(), new AiProposal(List.of(), ""));
        // The dispatcher checks authority again even if a backend ignored its action-free schema.
        if(!answer.action().steps().isEmpty() && !confirmedOffer && !authority.authorizedOrder()
                && authority.direct().isEmpty()) {
            port.diagnostic("authority","read-only turn attempted dispatch");
            speak(turn,"I haven't changed your current work.",false);
            if(activeTurn==turn){activeTurn=null;nextTurn();} return;
        }
        if(answer.action().steps().isEmpty()) {
            boolean uncertainRequest=!turn.resultOnly&&CompanionTurnPolicy.maySuggest(turn.request);
            if(uncertainRequest) {
                if(!answer.offer().steps().isEmpty()) {
                    offers.put(new CompanionFeedback.Owner(turn.player,turn.world,turn.session),new Offer(answer.offer(),port.nowMillis()+120_000));
                    while(offers.size()>32)offers.remove(offers.keySet().iterator().next());
                    speakFact(turn,confirmationReply(answer.offer()));
                } else speakFact(turn,"I haven't started that. Which action and target did you mean? /e help lists my commands.");
            } else if(!turn.resultOnly&&authority.authorizedOrder()) {
                // Empty steps are not an acknowledgement. Model prose cannot
                // claim that an admitted request started without real dispatch.
                speakFact(turn,"I couldn't match that request to an action. Nothing started; try the action and target, or /e help.");
            } else if(!turn.ambient || !answer.reply().isBlank())
                speak(turn,answer.reply().isBlank()?"Could you say that another way?":answer.reply(),false);
        }
        else {
            dialogue.addFact(turn.player,turn.world,turn.session,"assistant","[Requested existing skill] "+String.join(" ",answer.action().command())+"; await actual result.");
            port.dispatch(turn.player,answer.action().command());
        }
        if(activeTurn==turn){activeTurn=null;nextTurn();}
    }

    private AiProposal resolveTargets(AiProposal proposal) {
        return new AiProposal(proposal.steps().stream().map(step -> {
            if(!Set.of("get","bring","give","mine").contains(step.action())) return step;
            return new AiProposal.Step(step.action(),port.resolveTarget(step.action(),step.target().replace('_',' ')).orElse(step.target()),step.count());
        }).toList(),proposal.clarification());
    }

    private static String confirmationReply(AiProposal offer) {
        String plan=String.join("; then ",offer.steps().stream().map(step -> {
            var ability=AiAbilityCatalog.entries().stream().filter(a->a.action().equals(step.action())).findFirst().orElseThrow();
            String outcome=ability.outcome().split(";",2)[0].toLowerCase(Locale.ROOT);
            String arguments=(step.count()>0?step.count()+" ":"")+step.target().replace("minecraft:","").replace('_',' ');
            return outcome+(arguments.isBlank()?"":" ("+arguments+")");
        }).toList());
        return "Nothing has started. Proposed: "+plan+". Say yes to start.";
    }

    private void finishEvent(AiRequestFence.Ticket ticket, Turn turn, String expressive, boolean timedOut) {
        // A late deadline/response cannot consume the newer user turn or send a second notice.
        if(activeTurn!=turn || !ticket.equals(pendingTicket)) return;
        long now=port.nowMillis();
        boolean allowed=fence.consume(ticket,turn.player,turn.world,turn.session,
                port.stillAuthorized(turn.player,turn.world,turn.session),now);
        pendingTicket=null;
        if(resultDeadline!=null) { resultDeadline.cancel(false); resultDeadline=null; }
        boolean late=timedOut || now>=turn.queuedAt+resultDeadlineMillis;
        if(late && running!=null) running.cancel(true);
        if(allowed) {
            String clause=late?"":CompanionResultAnswer.expressiveClause(expressive);
            speakFact(turn,turn.factualCore+(clause.isBlank()?"":" "+clause));
            if(late) port.timing(0,Math.max(0,now-turn.queuedAt),"result-fallback");
        }
        activeTurn=null; nextTurn();
    }

    private static boolean freshIncident(JsonObject incident,long now) {
        if(incident==null) return true;
        if(incident.has("expires_at_millis")) return now<=incident.get("expires_at_millis").getAsLong();
        if(!incident.has("observed_at_millis")) return true;
        long age=now-incident.get("observed_at_millis").getAsLong();
        return age>=0 && age<=("death".equals(text(incident,"kind"))?30_000:15_000);
    }

    private void speak(Turn turn,String text,boolean error) {
        if (turn.ambient) text = CompanionProposal.reactionLine(text);
        if(turn.resultOnly) dialogue.addFact(turn.player,turn.world,turn.session,"assistant",text);
        else dialogue.add(turn.player,turn.world,turn.session,"assistant",text);
        port.reply(turn.player,text,error);
    }
    private void speakFact(Turn turn,String text) {
        dialogue.addFact(turn.player,turn.world,turn.session,"assistant",text);
        port.reply(turn.player,text,false);
    }

    /** Called only after player authentication, while on the Paper main thread. */
    public void ask(UUID requester, UUID world, String session, String request) {
        if (closed) throw new IllegalStateException(backend.label() + " is off.");
        invalidate();
        if (request.isBlank() || request.length() > 1000 || request.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("Use /e ask followed by a request of at most 1000 characters.");
        var ticket = fence.begin(requester, world, session, port.nowMillis());
        pendingTicket = ticket;
        var direct = AiDirectRequest.interpret(request,port::resolveTarget);
        if(direct.isEmpty()) {
            var authority=CompanionTurnPolicy.classify(request,port::resolveTarget);
            if(authority.authorizedOrder()) direct=authority.direct();
        }
        if (direct.isPresent()) {
            // Same main-thread authority/Stop fence, without loading or waiting
            // for a model to reinterpret an already unambiguous command.
            AiProposal directAnswer=direct.get();
            post(() -> accept(ticket, requester, world, session, request, directAnswer));
            return;
        }
        port.reply(requester, "Asking " + (backend.label().equals("Local AI") ? "local AI" : backend.label())
                + "… /e stop always takes priority.", false);
        running = worker.submit(() -> {
            try {
                AiProposal answer = AiProposal.parse(backend.complete(AiProposal.systemPrompt(), request, AiProposal.schema()));
                post(() -> accept(ticket, requester, world, session, request, answer));
            } catch (Exception failure) {
                post(() -> {
                    if (!fence.consume(ticket, requester, world, session,
                            port.stillAuthorized(requester, world, session), port.nowMillis())) {
                        if (ticket.equals(pendingTicket)) {
                            pendingTicket = null;
                            port.reply(requester, "AI request superseded; no action was started.", false);
                        }
                        return;
                    }
                    pendingTicket = null;
                    // Do not echo URLs, credentials, stack traces or generated content into public chat.
                    port.reply(requester, backend.label() + " could not interpret that request. Nothing was started. "
                            + "Use /e help or retry /e ask; normal commands still work.", true);
                });
            }
        });
    }
    private void accept(AiRequestFence.Ticket ticket, UUID requester, UUID world, String session, String request, AiProposal answer) {
        if (!fence.consume(ticket, requester, world, session,
                port.stillAuthorized(requester, world, session), port.nowMillis())) {
            if (ticket.equals(pendingTicket)) {
                pendingTicket = null;
                port.reply(requester, "AI request superseded; no action was started.", false);
            }
            return;
        }
        pendingTicket = null;
        answer=resolveTargets(answer);
        if (!AiDirectRequest.permittedCoordinates(answer, CompanionTurnPolicy.classify(request, port::resolveTarget).direct())) {
            port.reply(requester, "I haven't started a new trip. Give me the exact x y z coordinates you want.", false);
            return;
        }
        if (!AiCombatRequest.permitted(answer, CompanionTurnPolicy.classify(request).direct())) {
            port.reply(requester, "I haven't started combat or changed protection. Use an explicit mob type or combat/protection control.", false);
            return;
        }
        if (answer.steps().isEmpty()) {
            port.reply(requester, answer.clarification(), false);
            return;
        }
        String[] command = answer.command();
        port.reply(requester, "AI understood: /e " + String.join(" ", command), false);
        port.dispatch(requester, command);
    }
    private void post(Runnable callback) {
        if (!closed) port.mainThread(() -> { if (!closed) callback.run(); });
    }
    @Override public void close() {
        closed = true; invalidate(); dialogue.clear(); lastQueries.clear(); lastWorkNotices.clear(); worker.shutdownNow(); deadlines.shutdownNow();
        // Runtime shutdown/file cleanup never stalls a server tick.
        Thread cleanup = new Thread(backend::close, "Entity-AI-close");
        cleanup.setDaemon(true); cleanup.start();
    }
}
