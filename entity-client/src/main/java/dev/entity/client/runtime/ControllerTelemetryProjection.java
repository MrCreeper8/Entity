package dev.entity.client.runtime;

/**
 * Projects actual runtime ownership onto bridge telemetry. The core decision
 * layer alone cannot distinguish missionless Home work or an operator-stop
 * latch because both execute through the idle layer.
 */
public final class ControllerTelemetryProjection {
    private ControllerTelemetryProjection() {
    }

    public static Status from(String controllerStatus, String publishedStatus) {
        String controller = controllerStatus == null ? "" : controllerStatus.trim();
        String detail = publishedStatus == null ? "" : publishedStatus.trim();
        return new Status(
                controller.isEmpty() ? "starting" : controller,
                detail.isEmpty() ? "Starting Entity 2" : detail);
    }

    public record Status(String state, String message) {
    }
}
