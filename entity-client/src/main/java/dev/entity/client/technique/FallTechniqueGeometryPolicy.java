package dev.entity.client.technique;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded geometry rules shared by the mapped fall observer and tests. */
public final class FallTechniqueGeometryPolicy {
    private static final double MAX_STEER_DISTANCE = 4.25;

    private FallTechniqueGeometryPolicy() {
    }

    public static WaterDecision selectExistingWater(
            Body body,
            List<WaterCandidate> candidates) {
        Objects.requireNonNull(body, "body");
        List<WaterCandidate> observed = List.copyOf(
                Objects.requireNonNull(candidates, "candidates"));
        if (observed.isEmpty()) return WaterDecision.absent();

        List<ScoredWater> scored = observed.stream()
                .map(candidate -> score(body, candidate))
                .sorted(Comparator.comparingDouble(ScoredWater::horizontalDistanceSquared))
                .toList();
        ScoredWater selected = scored.stream().filter(ScoredWater::valid)
                .findFirst().orElseGet(() -> scored.stream()
                        .max(Comparator.comparingInt(ScoredWater::evidenceScore)
                                .thenComparing(ScoredWater::horizontalDistanceSquared,
                                        Comparator.reverseOrder()))
                        .orElseThrow());
        WaterCandidate candidate = selected.candidate();
        return new WaterDecision(
                candidate,
                true,
                candidate.loaded(),
                candidate.realWaterVolume(),
                candidate.bodyClear() && candidate.corridorClear(),
                selected.reachable(),
                selected.valid(),
                selected.horizontalDistanceSquared());
    }

    private static ScoredWater score(Body body, WaterCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        double dx = candidate.x() + 0.5 - body.x();
        double dz = candidate.z() + 0.5 - body.z();
        double horizontalSquared = dx * dx + dz * dz;
        double drop = body.feetY() - candidate.y();
        double horizontalSpeed = Math.hypot(
                body.horizontalVelocityX(), body.horizontalVelocityZ());
        double steer = Math.min(
                MAX_STEER_DISTANCE,
                0.65 + Math.sqrt(Math.max(0.0, drop)) * 0.48
                        + Math.min(1.5, horizontalSpeed * 8.0));
        boolean reachable = drop >= 0.5 && horizontalSquared <= steer * steer;
        boolean valid = candidate.loaded()
                && candidate.realWaterVolume()
                && candidate.bodyClear()
                && candidate.corridorClear()
                && reachable;
        int evidence = (candidate.loaded() ? 1 : 0)
                + (candidate.realWaterVolume() ? 2 : 0)
                + (candidate.bodyClear() ? 1 : 0)
                + (candidate.corridorClear() ? 1 : 0)
                + (reachable ? 1 : 0);
        return new ScoredWater(candidate, horizontalSquared, reachable, valid, evidence);
    }

    public static ClutchDecision validateClutch(Body body, ClutchCandidate candidate) {
        Objects.requireNonNull(body, "body");
        if (candidate == null) return ClutchDecision.absent();
        double dx = candidate.placementX() + 0.5 - body.x();
        double dz = candidate.placementZ() + 0.5 - body.z();
        double horizontalSquared = dx * dx + dz * dz;
        double hitDx = candidate.hitX() - body.x();
        double hitDy = candidate.hitY() - body.eyeY();
        double hitDz = candidate.hitZ() - body.z();
        double hitDistanceSquared = hitDx * hitDx + hitDy * hitDy + hitDz * hitDz;
        boolean loaded = candidate.supportLoaded() && candidate.placementLoaded();
        boolean reachable = hitDistanceSquared
                <= body.interactionRange() * body.interactionRange();
        boolean replaceable = candidate.upwardFace()
                && candidate.supportSolid()
                && candidate.placementReplaceable()
                && candidate.hazardFree();
        boolean corridor = candidate.placementY() < body.eyeY()
                && horizontalSquared <= 0.85 * 0.85;
        return new ClutchDecision(
                true, loaded, reachable, replaceable, corridor,
                loaded && reachable && replaceable && corridor,
                hitDistanceSquared);
    }

    public record Body(
            double x,
            double feetY,
            double eyeY,
            double z,
            double horizontalVelocityX,
            double horizontalVelocityZ,
            double interactionRange) {
        public Body {
            if (!Double.isFinite(x) || !Double.isFinite(feetY)
                    || !Double.isFinite(eyeY) || !Double.isFinite(z)
                    || !Double.isFinite(horizontalVelocityX)
                    || !Double.isFinite(horizontalVelocityZ)
                    || !Double.isFinite(interactionRange)
                    || eyeY < feetY || interactionRange <= 0.0) {
                throw new IllegalArgumentException("invalid fall body geometry");
            }
        }
    }

    public record WaterCandidate(
            int x,
            int y,
            int z,
            boolean loaded,
            boolean realWaterVolume,
            boolean bodyClear,
            boolean corridorClear) {
    }

    public record WaterDecision(
            WaterCandidate candidate,
            boolean candidatePresent,
            boolean loaded,
            boolean realWaterVolume,
            boolean corridorClear,
            boolean reachable,
            boolean valid,
            double horizontalDistanceSquared) {
        private static WaterDecision absent() {
            return new WaterDecision(null, false, false, false, false, false, false,
                    Double.POSITIVE_INFINITY);
        }
    }

    public record ClutchCandidate(
            double hitX,
            double hitY,
            double hitZ,
            int placementX,
            int placementY,
            int placementZ,
            boolean supportLoaded,
            boolean placementLoaded,
            boolean upwardFace,
            boolean supportSolid,
            boolean placementReplaceable,
            boolean hazardFree) {
    }

    public record ClutchDecision(
            boolean candidatePresent,
            boolean loaded,
            boolean reachable,
            boolean replaceable,
            boolean onFallCorridor,
            boolean valid,
            double hitDistanceSquared) {
        private static ClutchDecision absent() {
            return new ClutchDecision(
                    false, false, false, false, false, false,
                    Double.POSITIVE_INFINITY);
        }
    }

    private record ScoredWater(
            WaterCandidate candidate,
            double horizontalDistanceSquared,
            boolean reachable,
            boolean valid,
            int evidenceScore) {
    }
}
