package dev.entitybridge.delivery;

import com.google.gson.JsonObject;

import java.util.Objects;

/** Result of one main-thread, Paper-authoritative inventory handoff attempt. */
public record DeliveryCommitResult(
        String missionId,
        String nonce,
        boolean accepted,
        boolean retryable,
        String code,
        String message,
        String recipient,
        String itemId,
        int movedCount,
        int confirmedCount,
        int expectedCount,
        int remainingCount,
        long timestamp,
        DeliveryTransactionRegistry.Receipt receipt) {

    public DeliveryCommitResult {
        missionId = Objects.requireNonNullElse(missionId, "");
        nonce = Objects.requireNonNullElse(nonce, "");
        code = Objects.requireNonNullElse(code, "internal_error");
        message = Objects.requireNonNullElse(message, "");
        recipient = Objects.requireNonNullElse(recipient, "");
        itemId = Objects.requireNonNullElse(itemId, "");
        movedCount = Math.max(0, movedCount);
        confirmedCount = Math.max(0, confirmedCount);
        expectedCount = Math.max(0, expectedCount);
        remainingCount = Math.max(0, remainingCount);
    }

    public static DeliveryCommitResult rejected(
            String missionId,
            String nonce,
            boolean retryable,
            String code,
            String message) {
        return new DeliveryCommitResult(
                missionId, nonce, false, retryable, code, message,
                "", "", 0, 0, 0, 0, System.currentTimeMillis(), null);
    }

    public static DeliveryCommitResult rejected(
            DeliveryCommitRequest request,
            boolean retryable,
            String code,
            String message) {
        return new DeliveryCommitResult(
                request.missionId(), request.nonce(), false, retryable, code, message,
                request.recipient(), request.itemId(), 0, request.confirmedCount(),
                request.expectedCount(), request.remainingCount(),
                System.currentTimeMillis(), null);
    }

    public JsonObject toFrame() {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "delivery_commit_result");
        frame.addProperty("missionId", missionId);
        frame.addProperty("nonce", nonce);
        frame.addProperty("accepted", accepted);
        frame.addProperty("retryable", retryable);
        frame.addProperty("code", code);
        frame.addProperty("message", message);
        frame.addProperty("recipient", recipient);
        frame.addProperty("item", itemId);
        frame.addProperty("movedCount", movedCount);
        frame.addProperty("confirmedCount", confirmedCount);
        frame.addProperty("expectedCount", expectedCount);
        frame.addProperty("remainingCount", remainingCount);
        frame.addProperty("complete", expectedCount > 0 && confirmedCount >= expectedCount);
        frame.addProperty("timestamp", timestamp);
        return frame;
    }
}
