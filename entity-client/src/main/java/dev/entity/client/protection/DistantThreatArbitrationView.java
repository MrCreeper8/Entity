package dev.entity.client.protection;

import dev.entity.core.survival.WorldSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies a disengagement decision only to the immutable view passed into core arbitration. */
public final class DistantThreatArbitrationView {
    private DistantThreatArbitrationView() {
    }

    public static WorldSnapshot apply(
            WorldSnapshot rawWorld,
            WorldSnapshot currentArbitrationView,
            String exactTargetId,
            DistantThreatDisengagementPolicy.Decision decision) {
        return apply(
                rawWorld,
                currentArbitrationView,
                exactTargetId,
                decision,
                Double.POSITIVE_INFINITY);
    }

    /**
     * Protection-like decisions cap only the immutable core projection at the configured fresh
     * acquisition boundary. The policy already revalidated the actual raw distance, and the raw
     * tactical/telemetry view remains unchanged; this lets an already-owned 17-33m UUID regain
     * core protection after renewed danger without laundering its platform observation.
     */
    public static WorldSnapshot apply(
            WorldSnapshot rawWorld,
            WorldSnapshot currentArbitrationView,
            String exactTargetId,
            DistantThreatDisengagementPolicy.Decision decision,
            double maximumEngageDistance) {
        Objects.requireNonNull(rawWorld, "rawWorld");
        Objects.requireNonNull(currentArbitrationView, "currentArbitrationView");
        Objects.requireNonNull(decision, "decision");
        if (Double.isNaN(maximumEngageDistance) || maximumEngageDistance <= 0.0) {
            throw new IllegalArgumentException("maximumEngageDistance must be positive");
        }
        String targetId = required(exactTargetId);
        boolean rawTargetPresent = rawWorld.threats().stream()
                .anyMatch(threat -> threat.entityId().equals(targetId));
        if (!rawTargetPresent) return currentArbitrationView;
        return switch (decision.action()) {
            case AVOID_AND_RESUME ->
                    // A preceding sole-target suppression view may already have omitted the same
                    // UUID. Reuse that immutable copy; never rebuild from raw and reintroduce it.
                    ProtectionArbitrationView.omitExact(currentArbitrationView, targetId);
            case PROTECT, CREATE_SEPARATION, MAINTAIN_SEPARATION ->
                    reactivateExactFromRaw(
                            rawWorld,
                            currentArbitrationView,
                            targetId,
                            maximumEngageDistance);
            case PASS_THROUGH, DEFER_TO_SURVIVAL -> currentArbitrationView;
        };
    }

    private static WorldSnapshot reactivateExactFromRaw(
            WorldSnapshot rawWorld,
            WorldSnapshot currentView,
            String targetId,
            double maximumEngageDistance) {
        List<WorldSnapshot.Threat> activeRawExact = rawWorld.threats().stream()
                .filter(threat -> threat.entityId().equals(targetId))
                // A UUID may have distinct SELF and OWNER observations. Preserve every enabled
                // scope instead of collapsing the projection to whichever raw entry came first.
                .map(threat -> new WorldSnapshot.Threat(
                        threat.entityId(),
                        threat.entityType(),
                        threat.target(),
                        Math.min(threat.distance(), maximumEngageDistance),
                        threat.estimatedDamage(),
                        true))
                .toList();
        if (activeRawExact.isEmpty()) return currentView;
        List<WorldSnapshot.Threat> currentExact = currentView.threats().stream()
                .filter(threat -> threat.entityId().equals(targetId))
                .toList();
        if (currentExact.equals(activeRawExact)) return currentView;

        ArrayList<WorldSnapshot.Threat> projected = new ArrayList<>(
                currentView.threats().size() + activeRawExact.size());
        boolean inserted = false;
        for (WorldSnapshot.Threat threat : currentView.threats()) {
            if (threat.entityId().equals(targetId)) {
                if (!inserted) projected.addAll(activeRawExact);
                inserted = true;
            } else {
                projected.add(threat);
            }
        }
        if (!inserted) projected.addAll(activeRawExact);
        return copyWithThreats(currentView, List.copyOf(projected));
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
        if (normalized.isEmpty()) throw new IllegalArgumentException("exactTargetId is required");
        return normalized;
    }
}
