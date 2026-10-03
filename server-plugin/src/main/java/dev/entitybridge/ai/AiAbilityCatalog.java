package dev.entitybridge.ai;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/** The existing language-to-command surface, not a gameplay planner or new permission source. */
public final class AiAbilityCatalog {
    public static final int MAX_STEPS = 8, MAX_COUNT = 4096, MAX_TARGET_LENGTH = 64;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_:-]{0," + (MAX_TARGET_LENGTH - 1) + "}");
    private static final List<String> GEAR_TIERS = List.of("wood", "stone", "iron", "diamond", "best");
    private static final List<String> COMBAT_MODES = List.of("avoid", "defensive", "aggressive");
    private static final String MOB_TARGET = "^minecraft:(?!(?:player|hostile|mob|monster|nearest(?:_.*)?|closest(?:_.*)?)$)[a-z0-9_]+$";
    private static final String COORDINATE_TARGET = "^-?(?:0|[1-9][0-9]{0,9})(?: -?(?:0|[1-9][0-9]{0,9})){2}$";

    public enum Target {
        NONE("empty"), ITEM_OPTIONAL("empty for ALL carried items, or one item/group"),
        ITEM_REQUIRED("one Minecraft snake_case item/block/group"),
        PLAYER_OPTIONAL("empty for requester, or exact player name"),
        COORDINATES("exact signed integer x y z from the current request, in the requester's current world; never invent coordinates"),
        GEAR("supported gear tier"), FARM("exact registered farm name"),
        MOB("exact minecraft:mob_type; no player or generic/nearest target"), COMBAT("avoid/defensive/aggressive");
        private final String guidance;
        Target(String guidance) { this.guidance = guidance; }
        private String guidance() { return this == GEAR ? String.join("/", GEAR_TIERS) : guidance; }
    }

    public record Ability(String action, String companionAction, String outcome, Target target,
                          boolean counted, boolean finiteQueue, List<String> commandPrefix) {
        public Ability { commandPrefix = List.copyOf(commandPrefix); }
        public String name(boolean companion) { return companion ? companionAction : action; }
        public String targetScope() { return target.guidance(); }
    }

    private static Ability ability(String action, String outcome, Target target, boolean counted,
                                   boolean finiteQueue, String... command) {
        return ability(action, action, outcome, target, counted, finiteQueue, command);
    }
    private static Ability ability(String action, String companion, String outcome, Target target,
                                   boolean counted, boolean finiteQueue, String... command) {
        return new Ability(action, companion, outcome, target, counted, finiteQueue, List.of(command));
    }

    private static final List<Ability> ABILITIES = List.of(
            ability("inventory", "query all carried items or one item", Target.ITEM_OPTIONAL, false, false, "inventory"),
            ability("equipment", "query worn/equipped gear", Target.NONE, false, false, "gear", "status"),
            ability("status", "query current work/health/food", Target.NONE, false, false, "status"),
            ability("why", "query actual blocker/progress", Target.NONE, false, false, "why"),
            ability("capabilities", "query existing abilities", Target.NONE, false, false, "capabilities"),
            ability("home_status", "query registered Home", Target.NONE, false, false, "home", "status"),
            ability("stock_status", "query Home stock", Target.NONE, false, false, "stock", "status"),
            ability("queue_status", "query existing orders", Target.NONE, false, false, "queue", "status"),
            ability("come", "approach requester once", Target.NONE, false, true, "come"),
            ability("goto", "travel once to the requested coordinates", Target.COORDINATES, false, true, "goto"),
            ability("follow", "continuously follow requester or named player", Target.PLAYER_OPTIONAL, false, false, "follow"),
            ability("home", "go to registered Home", Target.NONE, false, true, "home", "go"),
            ability("sleep", "use registered Home bed", Target.NONE, false, false, "sleep"),
            ability("get", "get_for_myself", "obtain and KEEP the requested total; carried/Home stock may already suffice",
                    Target.ITEM_REQUIRED, true, true, "get"),
            ability("bring", "bring_to_player", "obtain a SPARE and DELIVER to requester, retaining my own equipment/food",
                    Target.ITEM_REQUIRED, true, true, "bring"),
            ability("give", "give_carried_surplus", "hand over ONLY explicitly requested already-carried surplus; no acquisition",
                    Target.ITEM_REQUIRED, true, true, "give"),
            ability("mine", "explicitly mine/dig new blocks, even if already carrying them",
                    Target.ITEM_REQUIRED, true, true, "mine"),
            ability("gear", "obtain/equip requested loadout", Target.GEAR, false, true, "gear"),
            ability("stock", "run one Home stock pass", Target.NONE, false, true, "stock", "run"),
            ability("farm", "run one registered farm pass", Target.FARM, false, false, "farm", "run"),
            ability("stop", "stop current work and orders", Target.NONE, false, false, "stop"),
            ability("pause", "pause current work", Target.NONE, false, false, "pause"),
            ability("resume", "resume current work", Target.NONE, false, false, "resume"),
            ability("retry", "retry current work", Target.NONE, false, false, "retry"),
            ability("queue_skip", "skip current order step", Target.NONE, false, false, "queue", "skip"),
            ability("queue_clear", "clear waiting steps only, preserving active work", Target.NONE, false, false, "queue", "clear"),
            ability("queue_cancel", "cancel current and waiting orders", Target.NONE, false, false, "queue", "cancel"),
            ability("build_resume", "continue the existing confirmed project at its saved site; never select a new design/site",
                    Target.NONE, false, false, "build", "resume"),
            ability("build_status", "query the existing saved project", Target.NONE, false, false, "build", "status"),
            ability("build_materials", "query exact materials for the existing saved project", Target.NONE, false, false, "build", "materials"),
            ability("tidy_carried", "preview carried junk cleanup; nothing is discarded until explicit confirmation",
                    Target.NONE, false, false, "tidy"),
            ability("tidy_home", "preview registered Home junk cleanup; nothing is discarded until explicit confirmation",
                    Target.NONE, false, false, "tidy", "home"),
            ability("tidy_confirm", "apply the current reviewed Tidy preview; requires explicit player confirmation",
                    Target.NONE, false, false, "tidy", "confirm"),
            ability("idle_on", "enable existing opt-in productive Idle after its configured quiet delay",
                    Target.NONE, false, false, "idle", "on"),
            ability("idle_off", "disable existing automatic Idle", Target.NONE, false, false, "idle", "off"),
            ability("idle_status", "query existing Idle preferences and readiness", Target.NONE, false, false, "idle"),
            ability("attack", "explicitly attack the requested exact mob type, using existing combat safeguards",
                    Target.MOB, false, false, "attack"),
            ability("combat_mode", "set existing standing combat policy; not an immediate attack order",
                    Target.COMBAT, false, false, "combat"),
            ability("combat_status", "query existing standing combat policy", Target.NONE, false, false, "combat", "status"),
            ability("protect_requester_on", "enable existing protection for the authenticated requester, not an area",
                    Target.NONE, false, false, "protect", "me", "on"),
            ability("protect_requester_off", "disable existing protection for the authenticated requester",
                    Target.NONE, false, false, "protect", "me", "off"),
            ability("protect_requester_status", "query existing requester protection", Target.NONE, false, false, "protect", "me", "status"));

    private AiAbilityCatalog() {}
    public static List<Ability> entries() { return ABILITIES; }
    public static boolean query(String action) {
        return Set.of("inventory", "equipment", "status", "why", "capabilities", "home_status", "stock_status",
                "queue_status", "build_status", "build_materials", "idle_status", "combat_status",
                "protect_requester_status").contains(action);
    }
    public static List<String> actions(boolean companion) {
        return ABILITIES.stream().map(a -> a.name(companion)).toList();
    }
    private static Ability find(String action) {
        return ABILITIES.stream().filter(a -> a.action().equals(action)).findFirst().orElse(null);
    }
    public static String actionName(String action, boolean companion) {
        Ability ability = find(action);
        return ability == null ? action : ability.name(companion);
    }
    public static String canonicalAction(String action) {
        return ABILITIES.stream().filter(a -> a.companionAction().equals(action))
                .map(Ability::action).findFirst().orElse(action);
    }

    /** Retain the parser's exact token, target, quantity and finite-order fences. */
    public static void validate(AiProposal.Step step, boolean queued) {
        Ability ability = find(step.action());
        String target = step.target();
        if (ability == null || step.count() < 0 || step.count() > MAX_COUNT
                || (ability.target() != Target.COORDINATES && !target.isEmpty() && !TOKEN.matcher(target).matches())) throw new IllegalArgumentException();
        if (ability.counted() ? step.count() < 1 : step.count() != 0) throw new IllegalArgumentException();
        boolean validTarget = switch (ability.target()) {
            case NONE -> target.isEmpty();
            case ITEM_OPTIONAL -> true;
            case ITEM_REQUIRED, FARM -> !target.isEmpty();
            case PLAYER_OPTIONAL -> target.isEmpty() || target.matches("[A-Za-z0-9_]{1,16}");
            case COORDINATES -> validCoordinates(target);
            case GEAR -> GEAR_TIERS.contains(target);
            case MOB -> target.matches(MOB_TARGET);
            case COMBAT -> COMBAT_MODES.contains(target);
        };
        if (!validTarget || (queued && !ability.finiteQueue())) throw new IllegalArgumentException();
    }

    private static boolean validCoordinates(String target) {
        if (target.length() > MAX_TARGET_LENGTH || !target.matches(COORDINATE_TARGET)) return false;
        try { for (String coordinate : target.split(" ")) Integer.parseInt(coordinate); return true; }
        catch (NumberFormatException invalid) { return false; }
    }

    public static String[] command(AiProposal.Step step) {
        Ability ability = find(step.action());
        // Step's public constructor historically did not validate; parse remains the dispatch fence.
        if (ability == null) return new String[]{step.action()};
        List<String> result = new ArrayList<>(ability.commandPrefix());
        if (ability.target() == Target.COORDINATES) {
            validate(step, false);
            result.addAll(List.of(step.target().split(" ")));
            return result.toArray(String[]::new);
        }
        if (ability.target() != Target.NONE && (!step.target().isEmpty()
                || ability.target() == Target.GEAR || ability.target() == Target.FARM || ability.counted()))
            result.add(step.target());
        if (ability.counted()) result.add(Integer.toString(step.count()));
        return result.toArray(String[]::new);
    }

    /** Shared bounded grammar shape; per-entry argument/queue checks remain in validate. */
    public static JsonObject stepSchema(boolean companion) {
        JsonArray alternatives = new JsonArray();
        for (Target target : Target.values()) {
            List<Ability> group = ABILITIES.stream().filter(a -> a.target() == target).toList();
            if (group.isEmpty()) continue;
            JsonObject schema = argumentSchema();
            JsonObject properties = schema.getAsJsonObject("properties");
            JsonArray names = new JsonArray(); group.forEach(a -> names.add(a.name(companion)));
            properties.getAsJsonObject("action").add("enum", names);
            JsonObject count = properties.getAsJsonObject("count");
            if (group.getFirst().counted()) {
                count.addProperty("minimum",1); count.addProperty("maximum",MAX_COUNT);
            } else count.addProperty("const",0);
            JsonObject argument = properties.getAsJsonObject("target");
            switch(target) {
                case NONE -> argument.addProperty("const","");
                case GEAR -> {
                    JsonArray tiers = new JsonArray(); GEAR_TIERS.forEach(tiers::add); argument.add("enum",tiers);
                }
                case COMBAT -> {
                    JsonArray modes = new JsonArray(); COMBAT_MODES.forEach(modes::add); argument.add("enum",modes);
                }
                case MOB -> argument.addProperty("pattern", MOB_TARGET);
                case PLAYER_OPTIONAL -> argument.addProperty("pattern","^(?:[A-Za-z0-9_]{1,16})?$");
                case COORDINATES -> argument.addProperty("pattern", COORDINATE_TARGET);
                case ITEM_OPTIONAL -> argument.addProperty("pattern","^(?:[A-Za-z0-9_][A-Za-z0-9_:-]{0,63})?$");
                case ITEM_REQUIRED, FARM -> argument.addProperty("pattern","^[A-Za-z0-9_][A-Za-z0-9_:-]{0,63}$");
            }
            alternatives.add(schema);
        }
        JsonObject union = new JsonObject(); union.add("oneOf",alternatives); return union;
    }
    private static JsonObject argumentSchema() {
        JsonObject schema = JsonParser.parseString("""
                {"type":"object","additionalProperties":false,"required":["action","target","count"],
                 "properties":{"action":{"type":"string"},"target":{"type":"string"},
                 "count":{"type":"integer","minimum":0}}}
                """).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.getAsJsonObject("target").addProperty("maxLength", MAX_TARGET_LENGTH);
        return schema;
    }

    /** Render maintained outcomes/arguments for either adapter; social lanes do not need this catalog. */
    public static String guidance(boolean companion) {
        StringBuilder text = new StringBuilder("Outcome skills (existing commands, not a resource plan). No-argument skills use target empty and count0.\n");
        for (Ability ability : ABILITIES) {
            text.append(ability.name(companion)).append(" = ").append(ability.outcome())
                    .append(ability.target() == Target.NONE ? "; no args" : "; target: " + ability.target().guidance())
                    .append(ability.target() == Target.NONE ? "" : "; count:" + (ability.counted() ? "1.." + MAX_COUNT : "0"))
                    .append(ability.finiteQueue() ? "; finite queue allowed.\n" : "; single-action only.\n");
        }
        String get = actionName("get", companion), bring = actionName("bring", companion),
                give = actionName("give", companion);
        text.append("Choose the requested OUTCOME, not a guess at how Minecraft obtains the item.\n")
                .append("The existing acquisition controller chooses mining, crafting, cooking or another method.\n")
                .append("Never change get/collect/obtain into mine just because the item comes from mining.\n")
                .append("Mine is ONLY for an explicit request to mine/dig new blocks. Missing supplies are not a refusal.\n")
                .append("A request to get something FOR the player, fetch it, make it for them, or get it then give it to them is ")
                .append(bring).append(". Bring already includes handing over; never append ").append(give).append(".\n")
                .append("Use ").append(give).append(" only for explicitly ALREADY carried surplus. Ordinary 'give me'/'gimme' is ")
                .append(bring).append(". 'me' means the player; 'yourself' means Entity.\n")
                .append("'get 8 food then come here and give it to me' means ").append(bring).append(" food 8.\n")
                .append("'get 8 food then come' without delivery still means ").append(get).append(" food 8, come.\n")
                .append("Food is the supported food group; do not replace it with guessed bread.\n")
                .append("Count is required for quantity-bearing skills; ask for quantity if absent. 'an/a' means 1.\n")
                .append("Use one Minecraft item/block/group token such as food, logs, iron_ingot, coal or water_bucket.\n")
                .append("Do not pass player names as items. Delivery to OTHER players is not supported by this adapter.\n")
                .append("Queries fetch actual facts; never invent carried counts, chest contents or completion.\n")
                .append("'What are you carrying?', 'What's in your inventory?' and 'What do you have?' mean inventory, target empty, count:0.\n")
                .append("Multiple steps only for an explicit ordered request, at most ").append(MAX_STEPS)
                .append(" finite actions: ")
                .append(String.join(",", ABILITIES.stream().filter(Ability::finiteQueue).map(a -> a.name(companion)).toList()))
                .append(". This uses the existing queue, appending after current work.\n")
                .append("No open-ended loops, unsolicited steps, inferred future work, unsolicited attacks or permissions.\n")
                .append("Attack requires an explicit current order for one exact minecraft:mob_type; never infer retaliation or use generic/nearest targets.\n")
                .append("Player-only attacks are not supported by this adapter: the existing attack command can fall back from an offline name to a mob type.\n")
                .append("Combat mode is a standing policy, not a synonym for attack. Protect requester controls never assign a protected area.\n")
                .append("Resume building means build_resume for the CURRENT saved confirmed project, not new site/design selection.\n")
                .append("Tidy first previews its exact discard list. tidy_confirm requires a new explicit confirmation of that preview; never infer it.\n")
                .append("Idle on is explicit opt-in, not permission to start while an assigned/paused/queued job owns work.\n")
                .append("Natural-language building/site/permission changes still require normal explicit preview/confirmation.\n")
                .append("Building: choose and preview a design with /e build; confirmation stays the player's.\n")
                .append("Home assignment: position Entity, then use /e home set; relocation confirmation stays the player's.\n")
                .append("Property: /e area begin protected <name>, finish, then confirm the player's selection.\n")
                .append("Unsupported selection requests explain that next UI step; never claim a selection was made.\n");
        return text.toString();
    }
}
