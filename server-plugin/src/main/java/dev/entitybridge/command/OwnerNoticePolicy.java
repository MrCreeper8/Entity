package dev.entitybridge.command;

import dev.entitybridge.bridge.BridgeResult;
import dev.entitybridge.mission.Mission;
import dev.entitybridge.mission.MissionStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** De-duplicates owner-visible state notices independently of transport retries. */
final class OwnerNoticePolicy {
    private static final int MAX_TRACKED_MISSIONS = 256;

    private final Map<String, MissionStatus> lastStates = new LinkedHashMap<>();

    Optional<Notice> observe(Mission mission) {
        MissionStatus previous = lastStates.put(mission.id(), mission.status());
        trim();
        if (previous == mission.status()) {
            return Optional.empty();
        }

        String detail = firstPresent(mission.reason(), mission.step(), "no reason reported");
        return switch (mission.status()) {
            case BLOCKED -> Optional.of(new Notice(
                    true,
                    "Mission #" + mission.sequence() + " " + mission.action()
                            + " blocked: " + detail + ". Use /e why or /e retry."
            ));
            case RETRYING -> Optional.of(new Notice(
                    false,
                    "Mission #" + mission.sequence() + " " + mission.action()
                            + " is retrying: " + detail + "."
            ));
            default -> Optional.empty();
        };
    }

    static String retainedFailureSuffix(BridgeResult result) {
        return result.missionId() == null || result.missionId().isBlank()
                ? ""
                : " - mission was retained";
    }

    private void trim() {
        while (lastStates.size() > MAX_TRACKED_MISSIONS) {
            String oldest = lastStates.keySet().iterator().next();
            lastStates.remove(oldest);
        }
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown";
    }

    record Notice(boolean error, String message) {
    }
}
