package dev.entity.core.survival;

import dev.entity.core.control.BodyChannel;

import java.util.Set;

public record SafetyDirective(
        SurvivalAction action,
        int priority,
        String reason,
        Set<BodyChannel> channels) {

    public SafetyDirective {
        channels = Set.copyOf(channels);
    }

    public boolean active() {
        return action != SurvivalAction.NONE;
    }

    public static SafetyDirective none() {
        return new SafetyDirective(SurvivalAction.NONE, 0, "no immediate survival hazard", Set.of());
    }
}
