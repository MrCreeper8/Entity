package dev.entitybridge.bridge;

public record BridgeResult(
        String id,
        boolean ok,
        String message,
        String missionId,
        Long sequence,
        String status,
        String queueId,
        Long queueGeneration,
        Long queueStep,
        Long queueAttempt,
        dev.entitybridge.blueprint.BlueprintPreview blueprintPreview,
        dev.entitybridge.blueprint.BlueprintCatalogView blueprintCatalog
) {
    public BridgeResult(String id, boolean ok, String message, String missionId, Long sequence, String status,
                        String queueId, Long queueGeneration, Long queueStep, Long queueAttempt,
                        dev.entitybridge.blueprint.BlueprintPreview blueprintPreview) {
        this(id, ok, message, missionId, sequence, status, queueId, queueGeneration, queueStep, queueAttempt,
                blueprintPreview, null);
    }
    public BridgeResult(String id, boolean ok, String message, String missionId, Long sequence, String status,
                        String queueId, Long queueGeneration, Long queueStep, Long queueAttempt) {
        this(id, ok, message, missionId, sequence, status, queueId, queueGeneration, queueStep, queueAttempt, null);
    }
    public BridgeResult(String id, boolean ok, String message, String missionId, Long sequence, String status) {
        this(id, ok, message, missionId, sequence, status, null, null, null, null);
    }
    public BridgeResult(String id, boolean ok, String message) {
        this(id, ok, message, null, null, null);
    }

    public boolean terminal() {
        if (!ok || status == null) return true;
        dev.entitybridge.mission.MissionStatus parsed = dev.entitybridge.mission.MissionStatus.parse(status, null);
        return parsed == null || parsed.terminal() || parsed == dev.entitybridge.mission.MissionStatus.BLOCKED;
    }
}
