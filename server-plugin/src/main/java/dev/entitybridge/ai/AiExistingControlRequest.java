package dev.entitybridge.ai;

import java.util.*;
import java.util.regex.Pattern;

/** Anchored controls for existing saved work. No world plan, site choice or implicit confirmation. */
final class AiExistingControlRequest {
    private static final Pattern NAMED_FARM = Pattern.compile(
            "^(?:(?:run )?farm(?: run)? ([a-z0-9_:-]{1,64})(?: once)?"
                    + "|run (?:the )?((?!the(?: |$))[a-z0-9_:-]{1,64}) farm(?: once)?)$");
    private static final Pattern CURRENT_MISSION_CONTROL = Pattern.compile(
            "(?:(?:please|just|alright|okay|ok|bruh)[ ,]+)*"
                    + "((?:can|could|would) you (?:please )?)?"
                    + "(pause|resume|continue) what (you(?:'re|re| are)|you were) doing"
                    + "(?: (?:now|please|pls|plz))*");
    private static final Pattern BUILD_PROGRESS_QUESTION = Pattern.compile(
            "how(?:'s|s| is) (?:the|your|our|my) (?:house|build) "
                    + "(?:coming along|going|doing|progressing)");
    private AiExistingControlRequest() {}

    /** Whole current-mission controls only; embedded copulas are part of this exact alias, not a relaxed context fence. */
    static Optional<AiProposal> currentMissionControl(String request) {
        if (request == null || request.isBlank() || request.length() > 4096) return Optional.empty();
        String text = request.strip().toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ");
        boolean question = text.indexOf('?') >= 0;
        var control = CURRENT_MISSION_CONTROL.matcher(text.replaceFirst("[.!?]+$", "").stripTrailing());
        if (!control.matches() || (question && control.group(1) == null)) return Optional.empty();
        String action = control.group(2);
        if (action.equals("pause") && control.group(3).equals("you were")) return Optional.empty();
        return Optional.of(new AiProposal(List.of(new AiProposal.Step(
                action.equals("pause") ? "pause" : "resume", "", 0)), ""));
    }

    /** Whole present questions for existing native facts, never work authority. */
    static Optional<AiProposal> readQuery(String request) {
        if (request == null) return Optional.empty();
        String text = request.strip().toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ")
                .replaceFirst("[.!?]+$", "").stripTrailing();
        // Whole present progress questions inspect the retained project; they
        // neither start/resume construction nor ask the model to invent a stage.
        String action = BUILD_PROGRESS_QUESTION.matcher(text).matches() ? "build_status" : switch (text) {
            case "stock status", "what is your home stock status", "what is the home stock status",
                 "how is your home stock", "how is your home stock doing" -> "stock_status";
            case "build status", "what is the status of your saved house project",
                 "what is the status of your saved build", "how is your saved house project going" -> "build_status";
            case "build materials", "what materials are missing for your saved house project",
                 "what materials are missing for your saved build", "what does your saved house project still need" -> "build_materials";
            default -> null;
        };
        return action == null ? Optional.empty()
                : Optional.of(new AiProposal(List.of(new AiProposal.Step(action, "", 0)), ""));
    }

    static Optional<AiProposal> interpret(String request) {
        var query = readQuery(request);
        if (query.isPresent()) return query;
        var missionControl = currentMissionControl(request);
        if (missionControl.isPresent()) return missionControl;
        // One existing, explicitly named finite pass. The native command validates
        // registration; this neither chooses a field nor enables an idle cycle.
        var farm = NAMED_FARM.matcher(request);
        if (farm.matches()) return Optional.of(new AiProposal(List.of(new AiProposal.Step(
                "farm", farm.group(1) != null ? farm.group(1) : farm.group(2), 0)), ""));
        String action = switch (request) {
            case "build resume", "resume the build", "continue the build", "finish the build",
                 "resume the house", "continue the house", "finish the house" -> "build_resume";
            case "tidy", "tidy carried", "tidy your inventory", "clean your inventory", "clean up your inventory" -> "tidy_carried";
            case "tidy home", "tidy your home", "tidy your chests", "clean up your chests" -> "tidy_home";
            case "tidy confirm", "confirm tidy", "confirm the tidy preview", "apply the tidy preview" -> "tidy_confirm";
            case "idle on", "turn idle on", "enable idle", "enable idle mode", "turn idle mode on" -> "idle_on";
            case "idle off", "turn idle off", "disable idle", "disable idle mode", "turn idle mode off" -> "idle_off";
            case "idle", "idle status" -> "idle_status";
            case "run stock once", "run home stock once", "stock once" -> "stock";
            case "skip the current order", "skip this order", "skip the current mission" -> "queue_skip";
            default -> request.matches("(?:resume|continue|finish) (?:building|constructing)(?: (?:the )?(?:same )?(?:good )?house(?: from last time)?)?")
                    ? "build_resume" : null;
        };
        return action == null ? Optional.empty()
                : Optional.of(new AiProposal(List.of(new AiProposal.Step(action, "", 0)), ""));
    }
}
