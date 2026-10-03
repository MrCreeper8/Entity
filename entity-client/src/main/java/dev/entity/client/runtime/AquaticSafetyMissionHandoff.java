package dev.entity.client.runtime;

import java.util.Objects;

/**
 * Remembers a mission that first became visible while the drowning reflex already owned movement.
 *
 * <p>A mission that existed when surfacing began has the normal Fabric suspended-route snapshot.
 * A mission created later has no prior operation to suspend, so its first post-breath start needs
 * an explicit one-shot conservative-resume hint instead. Identity is retained across the runtime
 * action-owner neutralization tick and consumed only by that exact mission.</p>
 */
public final class AquaticSafetyMissionHandoff {
    private boolean surfacing;
    private String missionAtEntry = "";
    private String lastSurfaceMission = "";
    private String pendingMission = "";

    /** Observes each core decision before any Runtime layer may return early. */
    public void observe(boolean surfaceForAir, String missionId) {
        String mission = normalize(missionId);
        if (!surfaceForAir) {
            surfacing = false;
            missionAtEntry = "";
            lastSurfaceMission = "";
            return;
        }

        if (!surfacing) {
            surfacing = true;
            missionAtEntry = mission;
            lastSurfaceMission = mission;
            if (!pendingMission.isEmpty() && !pendingMission.equals(mission)) {
                pendingMission = "";
            }
            return;
        }

        if (mission.equals(lastSurfaceMission)) return;
        if (mission.isEmpty()) {
            if (pendingMission.equals(lastSurfaceMission)) pendingMission = "";
        } else if (!mission.equals(missionAtEntry)) {
            pendingMission = mission;
        }
        lastSurfaceMission = mission;
    }

    /** Consumes the conservative first-start hint only for its exact mission identity. */
    public boolean consumeForStart(String missionId) {
        String mission = normalize(missionId);
        if (mission.isEmpty() || !mission.equals(pendingMission)) return false;
        pendingMission = "";
        return true;
    }

    public void clear() {
        surfacing = false;
        missionAtEntry = "";
        lastSurfaceMission = "";
        pendingMission = "";
    }

    private static String normalize(String missionId) {
        return Objects.requireNonNullElse(missionId, "").trim();
    }
}
