package dev.entity.client.protection;

import dev.entity.core.protection.ProtectionPolicy;
import dev.entity.core.survival.WorldSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * Exact-identity retention at the Fabric observation boundary for one disengagement episode.
 *
 * <p>Fresh protection acquisition remains range-bounded. Once Runtime has selected an ordinary
 * SELF threat, however, this session lets the adapter keep observing that exact loaded entity
 * while separation carries it beyond the acquisition radius. Only live facts supplied on the
 * current tick are returned; a settings change, unload, death, identity change, or non-ordinary
 * reclassification revokes the binding.</p>
 */
public final class DistantThreatEpisodeRetention {
    private Binding binding;

    public void arm(
            String episodeId,
            String targetId,
            WorldSnapshot.ThreatTarget target,
            DistantThreatObservationFactory.CombatShape combatShape,
            ProtectionPolicy.Settings settings) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(combatShape, "combatShape");
        Objects.requireNonNull(settings, "settings");
        String episode = required(episodeId, "episodeId");
        String exactTarget = required(targetId, "targetId");
        if (target != WorldSnapshot.ThreatTarget.SELF
                || !ordinary(combatShape)
                || !settings.protectSelf()
                || settings.combatMode() == ProtectionPolicy.CombatMode.AGGRESSIVE) {
            clear();
            return;
        }
        binding = new Binding(episode, exactTarget, settings);
    }

    /** Returns the exact UUID to resolve from the current loaded-world entity set. */
    public Optional<String> targetId(ProtectionPolicy.Settings settings) {
        Objects.requireNonNull(settings, "settings");
        if (binding == null) return Optional.empty();
        if (!binding.settings().equals(settings)
                || !settings.protectSelf()
                || settings.combatMode() == ProtectionPolicy.CombatMode.AGGRESSIVE) {
            clear();
            return Optional.empty();
        }
        return Optional.of(binding.targetId());
    }

    /** Revokes and rejects a requested exact lookup from any other mission/target boundary. */
    public boolean matchesBoundary(
            String episodeId,
            String targetId,
            ProtectionPolicy.Settings settings) {
        Optional<String> retained = targetId(settings);
        if (retained.isEmpty()) return false;
        String episode = required(episodeId, "episodeId");
        String exactTarget = required(targetId, "targetId");
        if (!binding.episodeId().equals(episode)
                || !retained.orElseThrow().equals(exactTarget)) {
            clear();
            return false;
        }
        return true;
    }

    /**
     * Validates one exact current-tick world lookup. The supplied distance is evidence only from
     * the live entity and is never cached for a later tick.
     */
    public Optional<Resolution> resolve(
            ProtectionPolicy.Settings settings,
            LiveTarget liveTarget) {
        Optional<String> retained = targetId(settings);
        if (retained.isEmpty()) return Optional.empty();
        if (liveTarget == null
                || !retained.orElseThrow().equals(liveTarget.targetId())
                || !liveTarget.loaded()
                || !liveTarget.alive()
                || !ordinary(liveTarget.combatShape())) {
            clear();
            return Optional.empty();
        }
        return Optional.of(new Resolution(liveTarget.targetId(), liveTarget.distance()));
    }

    public void clear() {
        binding = null;
    }

    public boolean active() {
        return binding != null;
    }

    private static boolean ordinary(DistantThreatObservationFactory.CombatShape combatShape) {
        return combatShape == DistantThreatObservationFactory.CombatShape.ORDINARY_MELEE
                || combatShape == DistantThreatObservationFactory.CombatShape.ORDINARY_RANGED;
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private record Binding(
            String episodeId,
            String targetId,
            ProtectionPolicy.Settings settings) {
    }

    public record LiveTarget(
            String targetId,
            boolean loaded,
            boolean alive,
            DistantThreatObservationFactory.CombatShape combatShape,
            double distance) {
        public static LiveTarget unavailable(String targetId) {
            return new LiveTarget(
                    targetId,
                    false,
                    false,
                    DistantThreatObservationFactory.CombatShape.OTHER,
                    0.0);
        }

        public LiveTarget {
            targetId = required(targetId, "targetId");
            combatShape = Objects.requireNonNull(combatShape, "combatShape");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("distance must be finite and non-negative");
            }
        }
    }

    public record Resolution(String targetId, double distance) {
        public Resolution {
            targetId = required(targetId, "targetId");
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("distance must be finite and non-negative");
            }
        }
    }
}
