package dev.entity.client.protection;

import dev.entity.core.survival.WorldSnapshot;

import java.util.List;
import java.util.Objects;

/** Immutable threat projection used only for one core arbitration call. */
public final class ProtectionArbitrationView {
    private ProtectionArbitrationView() {
    }

    public static WorldSnapshot omitExact(WorldSnapshot raw, String exactTargetId) {
        Objects.requireNonNull(raw, "raw");
        String targetId = required(exactTargetId);
        List<WorldSnapshot.Threat> filtered = raw.threats().stream()
                .filter(threat -> !threat.entityId().equals(targetId))
                .toList();
        return filtered.size() == raw.threats().size()
                ? raw
                : copyWithThreats(raw, filtered);
    }

    /** Removes all discretionary attackers while retaining every environmental survival fact. */
    public static WorldSnapshot omitAll(WorldSnapshot raw) {
        Objects.requireNonNull(raw, "raw");
        return raw.threats().isEmpty() ? raw : copyWithThreats(raw, List.of());
    }

    /**
     * Makes one materially reactivated exact UUID eligible to core's TRAVEL policy this tick.
     * The raw snapshot is never modified, and unrelated threats retain their sampled authority.
     */
    public static WorldSnapshot reactivateExact(WorldSnapshot raw, String exactTargetId) {
        Objects.requireNonNull(raw, "raw");
        String targetId = required(exactTargetId);
        boolean found = raw.threats().stream()
                .anyMatch(threat -> threat.entityId().equals(targetId));
        if (!found) return raw;
        List<WorldSnapshot.Threat> projected = raw.threats().stream()
                .map(threat -> threat.entityId().equals(targetId)
                        ? new WorldSnapshot.Threat(
                        threat.entityId(),
                        threat.entityType(),
                        threat.target(),
                        threat.distance(),
                        threat.estimatedDamage(),
                        true)
                        : threat)
                .toList();
        return copyWithThreats(raw, projected);
    }

    private static WorldSnapshot copyWithThreats(
            WorldSnapshot source,
            List<WorldSnapshot.Threat> threats) {
        return new WorldSnapshot(
                source.nowMillis(),
                source.health(),
                source.maximumHealth(),
                source.remainingAir(),
                source.maximumAir(),
                source.foodLevel(),
                source.consumableFoodAvailable(),
                source.inWater(),
                source.headSubmerged(),
                source.inLava(),
                source.onFire(),
                source.insideWall(),
                source.fallDistance(),
                source.onGround(),
                source.waterBucketAvailable(),
                source.fireResistanceActive(),
                source.estimatedAirTicksToBreathableSpace(),
                threats);
    }

    private static String required(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("exactTargetId cannot be blank");
        }
        return normalized;
    }
}
