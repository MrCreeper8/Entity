package dev.entity.client.runtime;

import dev.entity.core.model.Mission;
import dev.entity.core.model.MissionState;
import java.util.Objects;

/** A single parked mission, not a queue. Only verified waking can consume its return capability. */
public final class ExplicitSleepContinuation {
    private Token token;

    public static boolean mayPark(Mission mission) {
        return mayResumeAfterSleep(mission) || mission != null
                && (mission.state() == MissionState.BLOCKED || mission.state() == MissionState.RETRY_WAIT);
    }

    /** Waking proves only sleep, never that a pre-existing work failure was resolved. */
    public static boolean mayResumeAfterSleep(Mission mission) {
        return mission != null && switch (mission.state()) {
            case QUEUED, RUNNING, PAUSED_BY_PROTECTION, PAUSED_BY_SAFETY -> true;
            default -> false;
        };
    }

    public void retain(Mission paused, long stopGeneration, String identity, long sleepCycles) {
        if (paused.state() != MissionState.PAUSED_BY_OWNER || identity.isBlank()) {
            throw new IllegalArgumentException("sleep return requires an exact parked mission and world");
        }
        token = new Token(paused.id(), paused.revision(), stopGeneration, identity, sleepCycles);
    }

    public String missionId() { return token == null ? "" : token.missionId(); }
    public void clear() { token = null; }

    public boolean ready(Mission selected, long stopGeneration, String identity,
                         long sleepCycles, boolean sleepRequested, boolean neutral) {
        if (token == null) return false;
        if (selected == null || !token.missionId().equals(selected.id())
                || selected.state() != MissionState.PAUSED_BY_OWNER
                || token.revision() != selected.revision()
                || token.stopGeneration() != stopGeneration
                || !token.identity().equals(Objects.requireNonNullElse(identity, ""))) {
            clear();
            return false;
        }
        if (sleepCycles <= token.sleepCycles()) {
            // Rejection, night expiry and a blocked bed are not a successful sleep.
            if (!sleepRequested) clear();
            return false;
        }
        return !sleepRequested && neutral;
    }

    private record Token(String missionId, long revision, long stopGeneration,
                         String identity, long sleepCycles) {}
}
