package dev.entitybridge.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Player intent documentation; showing it must never submit work. */
final class CommandHelp {
    static final List<String> TOPICS = List.of("items", "movement", "combat", "home", "storage",
            "area", "farm", "stock", "tidy", "idle", "build", "controls", "queue", "settings", "access", "status", "ai");
    private static final Map<String, List<String>> PAGES = Map.ofEntries(
        Map.entry("ai", List.of(
            "Talk in normal chat: e <request>, ent <request> or Entity, <request>. /e ai on|off|status controls local AI.",
            "Clear requests use existing commands and item reserves. Queries return actual live information, not invented model facts.",
            "Supports inventory/gear/status/why, travel/Home/sleep, counted get/bring/give/mine, gear tiers, Stock and named Farm passes.",
            "Explicit finite sequences use the normal queue. Farm/Sleep/Follow are single requests. Delivery is to you.",
            "Build/property/Home registration still use their existing preview and confirmation commands; AI cannot authorize them.",
            "/e stop acts immediately and discards delayed AI answers. New direct work supersedes pending interpretation.",
            "/e personality set chaotic|friendly|neutral changes style; /e personality custom <description> sets your own. It never changes combat or missions.",
            "Brief chat and hit/death context expire after ten minutes; AI OFF silences reactions. No disk memory. Normal /e commands always work.")),
        Map.entry("items", List.of(
            "Get keeps items; Bring gathers missing items and delivers; Give only hands over carried items.",
            "/e get iron 16 | /e bring [player] iron 16 | /e give [player] iron 16",
            "/e give [player] iron all sends carried surplus. --use-reserves explicitly allows personal supplies.",
            "Multiple items: /e get food 8 coal 16. Counts default to 1; Get targets carried totals, Bring/Give deliver quantities.",
            "/e mine wood 16 mines a block group. Get wood means oak logs; use spruce_log etc. for an exact species.",
            "/e gear iron obtains Entity's own kit (wood/stone/iron/diamond). Bare gear means iron; legacy best means diamond.",
            "/e gear status shows equipped gear; /e inventory [item] shows carried items, not Home storage.")),
        Map.entry("movement", List.of(
            "/e come approaches you once; /e follow [player] keeps following.",
            "/e goto <player> or /e goto <x> <y> <z> travels once in your current dimension.",
            "/e home go returns to registered Home. /e stop cancels work; /e help controls explains pausing.")),
        Map.entry("combat", List.of(
            "/e attack [player/mob] is an explicit attack order; omitted target means hostile mobs.",
            "/e combat avoid|defensive|aggressive sets the standing response; /e combat shows it.",
            "/e protect self on|off|status controls self-defense; /e protect me on|off|status controls owner defense.",
            "/e protect status shows both. Property protection is separate: /e help area.")),
        Map.entry("home", List.of(
            "Existing house: bring Entity inside, then /e home set. Uses Entity's stationary feet; does not place furniture.",
            "/e home adopt previews existing nearby furniture; /e home adopt confirm registers the exact reviewed assets.",
            "Missing/ambiguous furniture: look at it and /e home bind bed|chest|table|furnace, then /e home bind confirm.",
            "/e home setup explicitly provisions missing facilities. Legacy home establish creates AND provisions a new Home.",
            "/e home set confirm confirms relocation. Old blocks/items stay; furniture must be adopted/rebound.",
            "/e home go returns; /e sleep sleeps then continues eligible interrupted work. /e home status reports; show also outlines house protection.",
            "/e home clear explains forgetting Home; /e home clear confirm explicitly forgets it without demolishing anything.",
            "Home registration and house protection are separate. /e help storage or /e help area for those workflows.")),
        Map.entry("storage", List.of(
            "Use existing furniture: look at it, /e home bind table|furnace|chest|bed, then /e home bind confirm.",
            "Add an existing chest: /e home storage add, review, then /e home storage confirm.",
            "/e home storage list shows IDs; /e home storage remove <id> unregisters only, leaving blocks and contents.",
            "Confirmed registration changes stop current work. Preview/validation never discard items or start work.",
            "Furniture must pass Home's displayed range/property checks. Additional storage is not a request to place a chest.")),
        Map.entry("area", List.of(
            "Your property: /e area begin protected <name> at your first corner; /e area finish at your second; /e area confirm.",
            "Entity's house: /e home protect begin <name>, then finish and confirm in the same command group.",
            "Both use your feet and protect the X/Z rectangle at all heights. Entity-house protection permits its registered furniture.",
            "/e area begin harvesting <name> selects a resource/farm area at YOUR feet.",
            "/e area begin mining <name> uses ENTITY's feet as entrance/first corner; move Entity for area finish (3D volume).",
            "/e area list | /e area show <name> | /e home protect show [name] displays regions.",
            "In either group: cancel abandons pending selection/removal; remove <name>, then remove <name> confirm removes policy only.",
            "Names: letters, numbers, - or _, no spaces. Protection never automatically assigns Home or adopts furniture.")),
        Map.entry("farm", List.of(
            "Select a field first: /e area begin harvesting <name>, then area finish and area confirm.",
            "/e farm run <name> performs one harvest/replant pass; it does not construct a farm.",
            "Managed crops: wheat, carrots, potatoes, beetroot. /e farm status [name] shows readiness.",
            "/e farm auto <name> on|off permits automatic passes; also requires /e idle on, quiet time and actual crop/supply readiness.")),
        Map.entry("stock", List.of(
            "/e stock run runs one Home supply/storage maintenance cycle; Home and Stock must be enabled.",
            "/e stock on enables Home maintenance and requests a cycle; /e stock off disables it after item custody can settle.",
            "/e stock status reports shortages and pending work. /e idle on separately permits automatic productive work.",
            "Stock is not Tidy: /e tidy home previews junk cleanup. /e help home explains assigning an existing furnished house.")),
        Map.entry("tidy", List.of(
            "/e tidy previews carried cleanup; /e tidy home inspects registered chests when free. Busy inspection is refused, not substituted for work.",
            "/e tidy confirm applies that reviewed cleanup; /e tidy status reports progress/settings.",
            "/e tidy keep <exact_item> or /e tidy junk <exact_item> changes classification, not immediate disposal.",
            "Look at a drop location: /e tidy spot, then /e tidy spot confirm. These are ordinary dropped items, not guaranteed destruction.",
            "/e tidy auto on|off controls cleanup after Stock cycles, not an independent endless tidy job.",
            "Required kit, cargo and item transactions remain protected; previews disclose what will leave inventory.")),
        Map.entry("idle", List.of(
            "/e idle on|off enables/disables automatic productive work while unassigned; /e idle shows current readiness.",
            "/e idle delay <minutes> sets quiet time (1-1440). Manual/queued/paused work is not idle.",
            "/e idle offline on|off permits work while YOU are offline; the server and Entity must still run.",
            "/e idle target food|fuel|logs|iron <count> sets surplus targets (0-4096), not removal of personal kit requirements.",
            "/e stop cancels work and saves Idle OFF. Use /e idle on when you want automatic work again.")),
        Map.entry("build", List.of(
            "/e build list offers clickable names; /e build select <name> previews a design at YOUR position and facing.",
            "/e build here moves the preview to your feet; /e build rotate 90|180|270 adds that rotation.",
            "/e build show displays the site; /e build materials lists requirements.",
            "/e build confirm uses available supplies; /e build confirm gather additionally permits gathering missing materials.",
            "/e build status reports the project; /e build resume continues it without a separate show command.",
            "/e build import <supported HTTPS link> or abfielder:<id> imports a design. Not every website/download page is supported.",
            "/e build clear then /e build clear confirm forgets the project, NOT its blocks. /e stop preserves the project for resume.",
            "A finished house is not automatically Home. Bring Entity inside and /e help home to register its existing furniture.")),
        Map.entry("controls", List.of(
            "/e pause [id] preserves a mission; /e resume [id] continues; /e retry [id] reattempts retained blocked work.",
            "/e cancel [id] ends the selected mission; /e stop cancels all work/queue and saves Idle OFF.",
            "Omitted id means current; use the displayed #number or unique short ID. Invalid IDs never act on another job.",
            "/e missions [page] lists jobs; /e why [id] explains work. Home/Stock controls report their applicable recovery action.",
            "/e queue skip abandons one queued step; /e queue cancel stops the entire sequence.")),
        Map.entry("queue", List.of(
            "/e queue get food 8 ; home go appends ordered steps. /e queue add ... is the explicit append form.",
            "Supported steps: get, give, bring, mine, gear, goto, come, home go, stock run. Follow/Build/Tidy/Farm/Sleep are not step syntax.",
            "/e queue status shows progress; /e queue retry or resume reattempts its retained blocked step.",
            "/e queue skip stops one step then advances; /e queue clear removes waiting steps and keeps the active one.",
            "/e queue cancel cancels active and waiting work. Direct work replaces a queue; help/status/preview never do.",
            "Example: /e queue get coal 16 ; bring food 8 ; home go. Counts/progress are retained on retry.")),
        Map.entry("settings", List.of(
            "/e perception normal|legit|status controls resource discovery. Stop work before changing it.",
            "Normal can use loaded hidden-resource knowledge; Legit uses observed/remembered resources. Neither is conversational AI.",
            "/e visuals on|off|status controls path/goal overlays in ENTITY's bot window.",
            "Oracle is not an implemented execution mode. Use Perception for the actual supported information choice.",
            "/e help idle, /e help combat and /e help access explain other independent settings.")),
        Map.entry("access", List.of(
            "/e access list shows trusted controllers (owner/console only).",
            "/e access allow <player> grants control; /e access deny <player> removes it.",
            "/e access lock means owner-only control; /e access unlock enables trusted controllers again.",
            "The owner keeps authority over property, Home registration and protected settings.")),
        Map.entry("status", List.of(
            "/e status shows current activity; /e status debug includes connection/build/path diagnostics.",
            "/e why [id] explains current work; /e plan gives the detailed task plan; /e missions [page] lists history.",
            "/e inventory [item] shows carried counts, not Home storage; /e gear status shows equipped gear.",
            "/e capabilities lists feature groups; /e capabilities techniques shows inventory-aware fall techniques.",
            "Use /e help <topic> or Tab to find commands. /entity also works. Queries do not issue new work.")));

    static List<String> overview() {
        return List.of("Entity — /e help <topic> for examples (Tab completes topics).",
            "Work: come, follow, get, bring, give, mine, gear, farm, build.",
            "Home: set/adopt existing furniture; setup explicitly creates missing facilities.",
            "Control: stop, pause, resume, retry; queue for ordered jobs; why explains activity.",
            "Topics: " + String.join(", ", TOPICS));
    }

    static List<String> page(String topic) {
        String normalized = topic.trim().toLowerCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "ask", "personality" -> "ai";
            case "get", "bring", "give", "mine", "gear", "inventory", "fetch", "collect" -> "items";
            case "come", "follow", "goto", "go" -> "movement";
            case "attack", "kill", "protect" -> "combat";
            case "home storage", "home bind" -> "storage";
            case "home protect", "property" -> "area";
            case "stop", "pause", "resume", "retry", "cancel" -> "controls";
            case "perception", "visuals", "mode" -> "settings";
            case "why", "plan", "missions", "capabilities", "diagnostics" -> "status";
            case "sleep", "bed" -> "home";
            default -> normalized;
        };
        return PAGES.getOrDefault(normalized, overview());
    }
}
