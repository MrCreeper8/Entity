package dev.entity.client.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.entity.client.autonomy.policy.DeliveryPolicy;

import java.util.Objects;

/** Pure frame codec for Paper-authoritative handoff transactions. */
public final class DeliveryProtocol {
    private DeliveryProtocol() {
    }

    public static JsonObject prepare(
            String missionId,
            String nonce,
            String recipient,
            String resolvedItem,
            int count,
            long timestamp) {
        if (count <= 0) throw new IllegalArgumentException("handoff count must be positive");
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "delivery_prepare");
        frame.addProperty("missionId", required(missionId, "missionId"));
        frame.addProperty("nonce", required(nonce, "nonce"));
        frame.addProperty("recipient", required(recipient, "recipient"));
        frame.addProperty("item", DeliveryPolicy.normalizeItemId(resolvedItem));
        frame.addProperty("count", count);
        frame.addProperty("timestamp", timestamp);
        return frame;
    }

    public static Prepared prepared(JsonObject frame) {
        return new Prepared(
                text(frame, "missionId"),
                text(frame, "nonce"),
                bool(frame, "accepted"),
                text(frame, "reason"));
    }

    public static JsonObject prepare(String missionId, String nonce, String recipient,
            String resolvedItem, int count, long timestamp, String exactSelections) {
        JsonObject frame = prepare(missionId, nonce, recipient, resolvedItem, count, timestamp);
        if (exactSelections != null && !exactSelections.isBlank()) {
            frame.add("selections", JsonParser.parseString(exactSelections).getAsJsonArray());
        }
        return frame;
    }

    public static JsonObject commit(String missionId, String nonce, long timestamp) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "delivery_commit");
        frame.addProperty("missionId", required(missionId, "missionId"));
        frame.addProperty("nonce", required(nonce, "nonce"));
        frame.addProperty("timestamp", timestamp);
        return frame;
    }

    public static CommitResult commitResult(JsonObject frame) {
        String rawItem = text(frame, "item");
        return new CommitResult(
                text(frame, "missionId"), text(frame, "nonce"),
                bool(frame, "accepted"), bool(frame, "retryable"),
                text(frame, "code"), text(frame, "message"),
                text(frame, "recipient"), rawItem.isBlank()
                ? "" : DeliveryPolicy.normalizeItemId(rawItem),
                integer(frame, "movedCount"), integer(frame, "confirmedCount"),
                integer(frame, "expectedCount"), integer(frame, "remainingCount"),
                bool(frame, "complete"));
    }

    public static Receipt receipt(JsonObject frame) {
        return new Receipt(
                text(frame, "receiptId"),
                text(frame, "missionId"),
                text(frame, "nonce"),
                text(frame, "recipient"),
                DeliveryPolicy.normalizeItemId(text(frame, "item")),
                integer(frame, "countDelta"),
                integer(frame, "confirmedCount"),
                integer(frame, "expectedCount"));
    }

    public static JsonObject acknowledge(String receiptId) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "delivery_receipt_ack");
        frame.addProperty("receiptId", required(receiptId, "receiptId"));
        return frame;
    }

    public static Returned returned(JsonObject frame) {
        return new Returned(
                text(frame, "returnId"), text(frame, "missionId"), text(frame, "nonce"),
                text(frame, "recipient"), DeliveryPolicy.normalizeItemId(text(frame, "item")),
                integer(frame, "confirmedCount"), integer(frame, "expectedCount"),
                integer(frame, "remainingCount"));
    }

    public static JsonObject acknowledgeReturned(String returnId) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "delivery_returned_ack");
        frame.addProperty("returnId", required(returnId, "returnId"));
        return frame;
    }

    public record Prepared(String missionId, String nonce, boolean accepted, String reason) {
        public Prepared {
            missionId = required(missionId, "missionId");
            nonce = required(nonce, "nonce");
            reason = Objects.requireNonNullElse(reason, "");
        }
    }

    public record CommitResult(
            String missionId,
            String nonce,
            boolean accepted,
            boolean retryable,
            String code,
            String message,
            String recipient,
            String item,
            int movedCount,
            int confirmedCount,
            int expectedCount,
            int remainingCount,
            boolean complete) {
        public CommitResult {
            missionId = required(missionId, "missionId");
            nonce = required(nonce, "nonce");
            code = required(code, "code");
            message = Objects.requireNonNullElse(message, "");
            recipient = Objects.requireNonNullElse(recipient, "").trim();
            item = Objects.requireNonNullElse(item, "").trim();
            if (movedCount < 0 || confirmedCount < 0 || expectedCount < 0
                    || remainingCount < 0 || confirmedCount > expectedCount
                    || expectedCount > 0 && confirmedCount + remainingCount != expectedCount) {
                throw new IllegalArgumentException("invalid delivery commit-result counts");
            }
        }
    }

    public record Receipt(
            String receiptId,
            String missionId,
            String nonce,
            String recipient,
            String item,
            int countDelta,
            int confirmedCount,
            int expectedCount) {
        public Receipt {
            receiptId = required(receiptId, "receiptId");
            missionId = required(missionId, "missionId");
            nonce = required(nonce, "nonce");
            recipient = required(recipient, "recipient");
            item = DeliveryPolicy.normalizeItemId(item);
            if (countDelta <= 0 || confirmedCount <= 0 || expectedCount <= 0
                    || confirmedCount > expectedCount) {
                throw new IllegalArgumentException("invalid delivery receipt counts");
            }
        }
    }

    public record Returned(
            String returnId,
            String missionId,
            String nonce,
            String recipient,
            String item,
            int confirmedCount,
            int expectedCount,
            int remainingCount) {
        public Returned {
            returnId = required(returnId, "returnId");
            missionId = required(missionId, "missionId");
            nonce = required(nonce, "nonce");
            recipient = required(recipient, "recipient");
            item = DeliveryPolicy.normalizeItemId(item);
            if (confirmedCount < 0 || expectedCount <= 0 || remainingCount <= 0
                    || confirmedCount + remainingCount != expectedCount) {
                throw new IllegalArgumentException("invalid returned delivery counts");
            }
        }
    }

    private static String text(JsonObject frame, String field) {
        return frame.has(field) && frame.get(field).isJsonPrimitive()
                ? frame.get(field).getAsString().trim() : "";
    }

    private static int integer(JsonObject frame, String field) {
        return frame.has(field) && frame.get(field).isJsonPrimitive()
                ? frame.get(field).getAsInt() : 0;
    }

    private static boolean bool(JsonObject frame, String field) {
        return frame.has(field) && frame.get(field).isJsonPrimitive()
                && frame.get(field).getAsBoolean();
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
