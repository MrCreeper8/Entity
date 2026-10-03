package dev.entity.client.protection;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects one shield-facing advisory from an already policy-eligible tactical threat set.
 *
 * <p>This policy does not acquire combat targets. The caller must supply only threats admitted by
 * the protection layer; the result is an operation-scoped defensive hint while another exact
 * target remains selected. Active attackers outrank retained/idle pressure, followed by modeled
 * damage and proximity.</p>
 */
public final class VisibleRangedPressurePolicy {
    private VisibleRangedPressurePolicy() {
    }

    public static Optional<String> select(List<Candidate> candidates) {
        return Objects.requireNonNull(candidates, "candidates").stream()
                .filter(Candidate::policyEligible)
                .filter(Candidate::ranged)
                .filter(Candidate::alive)
                .filter(Candidate::lineOfSight)
                .max(Comparator
                        .comparing(Candidate::activelyAttacking)
                        .thenComparingDouble(Candidate::estimatedDamage)
                        .thenComparingDouble(candidate -> -candidate.distance())
                        .thenComparing(Candidate::targetId))
                .map(Candidate::targetId);
    }

    public record Candidate(
            String targetId,
            boolean policyEligible,
            boolean ranged,
            boolean alive,
            boolean lineOfSight,
            boolean activelyAttacking,
            double estimatedDamage,
            double distance) {
        public Candidate {
            targetId = Objects.requireNonNullElse(targetId, "").trim();
            if (targetId.isEmpty()) {
                throw new IllegalArgumentException("targetId cannot be blank");
            }
            if (!Double.isFinite(estimatedDamage) || estimatedDamage < 0.0) {
                throw new IllegalArgumentException(
                        "estimatedDamage must be finite and non-negative");
            }
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("distance must be finite and non-negative");
            }
        }
    }
}
