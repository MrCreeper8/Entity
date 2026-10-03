package dev.entity.client.bridge;

import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.UUID;

/** One authoritative player-life boundary, ordered in the server damage clock. */
public record ServerAttackerResetEvent(UUID attackerUuid, long timestamp, String reason) {
    public ServerAttackerResetEvent {
        Objects.requireNonNull(attackerUuid, "attackerUuid");
        if (timestamp < 0L) throw new IllegalArgumentException("negative timestamp");
        if (!"death".equals(reason) && !"respawn".equals(reason)) {
            throw new IllegalArgumentException("unsupported attacker life boundary");
        }
    }

    public static ServerAttackerResetEvent parse(JsonObject frame) {
        try {
            if (!frame.get("type").getAsString().equals("server_attacker_reset")
                    || frame.get("schema").getAsBigDecimal().intValueExact() != 1) {
                throw new IllegalArgumentException("unsupported attacker reset frame");
            }
            return new ServerAttackerResetEvent(
                    UUID.fromString(frame.get("attackerUuid").getAsString()),
                    frame.get("timestamp").getAsBigDecimal().longValueExact(),
                    frame.get("reason").getAsString());
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("malformed attacker reset frame", malformed);
        }
    }
}
