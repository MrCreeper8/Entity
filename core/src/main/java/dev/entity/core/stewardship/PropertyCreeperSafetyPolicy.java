package dev.entity.core.stewardship;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure property-relative boundary for deliberate creeper engagement.
 *
 * <p>The blast clearance protects the physical outer faces of selected blocks,
 * not only Entity's current position. The additional engagement reserve keeps a
 * normal melee approach from immediately walking a permitted creeper back into
 * the protected blast margin.</p>
 */
public final class PropertyCreeperSafetyPolicy {
    public static final double NORMAL_BLAST_CLEARANCE = 7.0;
    public static final double CHARGED_BLAST_CLEARANCE = 13.0;
    public static final double MELEE_ENGAGEMENT_RESERVE = 4.25;
    private static final double ESCAPE_PROBE_DISTANCE = 4.0;
    private static final double EPSILON = 1.0e-6;
    private static final int ESCAPE_HEADINGS = 32;

    private PropertyCreeperSafetyPolicy() {
    }

    public static Decision decide(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        double blastClearance = observation.charged()
                ? CHARGED_BLAST_CLEARANCE
                : NORMAL_BLAST_CLEARANCE;
        double engagementClearance = blastClearance + MELEE_ENGAGEMENT_RESERVE;

        List<Nearest> propertyDistances = observation.regions().stream()
                .filter(region -> region.dimension().equals(observation.dimension()))
                .map(region -> new Nearest(
                        region,
                        distanceToFootprint(
                                region,
                                observation.creeperX(), observation.creeperZ())))
                .toList();
        Comparator<Nearest> nearestOrder = Comparator.comparingDouble(Nearest::distance)
                .thenComparing(nearestRegion -> nearestRegion.region().identity());
        Nearest nearest = propertyDistances.stream()
                .min(nearestOrder)
                .orElse(null);

        if (nearest != null && nearest.distance() <= engagementClearance + EPSILON) {
            EscapeVector escape = safestOutwardEscape(
                    nearest.region(),
                    observation.playerX(), observation.playerZ(),
                    observation.creeperX(), observation.creeperZ());
            return new Decision(
                    Code.PROPERTY_COMBAT_BUFFER,
                    false,
                    false,
                    false,
                    escape,
                    Optional.of(nearest.region()),
                    nearest.distance(),
                    blastClearance,
                    engagementClearance,
                    "creeper is " + decimal(nearest.distance()) + " blocks from "
                            + nearest.region().displayName()
                            + "; withdrawing away from " + nearest.region().displayName()
                            + " until it clears " + decimal(engagementClearance)
                            + " blocks (blast " + decimal(blastClearance)
                            + " + melee reserve " + decimal(MELEE_ENGAGEMENT_RESERVE) + ")");
        }

        if (!observation.propertyPolicyAvailable()) {
            return new Decision(
                    Code.PROPERTY_POLICY_UNAVAILABLE,
                    false,
                    false,
                    false,
                    awayFromThreat(
                            observation.playerX(), observation.playerZ(),
                            observation.creeperX(), observation.creeperZ()),
                    nearest == null ? Optional.empty() : Optional.of(nearest.region()),
                    nearest == null ? Double.POSITIVE_INFINITY : nearest.distance(),
                    blastClearance,
                    engagementClearance,
                    "authenticated protected-property policy is unavailable; "
                            + "creeper approach and attack fail closed");
        }

        return new Decision(
                Code.WILDERNESS_ALLOWED,
                true,
                true,
                false,
                EscapeVector.NONE,
                nearest == null ? Optional.empty() : Optional.of(nearest.region()),
                nearest == null ? Double.POSITIVE_INFINITY : nearest.distance(),
                blastClearance,
                engagementClearance,
                nearest == null
                        ? "no selected property exists in the loaded dimension"
                        : "creeper is outside the engagement buffer of "
                        + nearest.region().displayName());
    }

    /** Distance from an entity point to the outer faces of the selected block footprint. */
    public static double distanceToFootprint(Region region, double x, double z) {
        Objects.requireNonNull(region, "region");
        requireFinite(x, "x");
        requireFinite(z, "z");
        double minimumX = region.minX() - 0.5;
        double maximumX = region.maxX() + 0.5;
        double minimumZ = region.minZ() - 0.5;
        double maximumZ = region.maxZ() + 0.5;
        double dx = x < minimumX ? minimumX - x : x > maximumX ? x - maximumX : 0.0;
        double dz = z < minimumZ ? minimumZ - z : z > maximumZ ? z - maximumZ : 0.0;
        return Math.hypot(dx, dz);
    }

    /** Positive outside the footprint, negative inside; useful for testing outward progress. */
    public static double signedDistanceToFootprint(Region region, double x, double z) {
        double outside = distanceToFootprint(region, x, z);
        if (outside > EPSILON) return outside;
        double minimumX = region.minX() - 0.5;
        double maximumX = region.maxX() + 0.5;
        double minimumZ = region.minZ() - 0.5;
        double maximumZ = region.maxZ() + 0.5;
        return -Math.min(
                Math.min(x - minimumX, maximumX - x),
                Math.min(z - minimumZ, maximumZ - z));
    }

    private static EscapeVector safestOutwardEscape(
            Region region,
            double playerX,
            double playerZ,
            double creeperX,
            double creeperZ) {
        double currentProperty = signedDistanceToFootprint(region, playerX, playerZ);
        double currentThreat = Math.hypot(playerX - creeperX, playerZ - creeperZ);
        ArrayList<EscapeCandidate> candidates = new ArrayList<>(ESCAPE_HEADINGS);
        for (int index = 0; index < ESCAPE_HEADINGS; index++) {
            double angle = Math.PI * 2.0 * index / ESCAPE_HEADINGS;
            double x = Math.cos(angle);
            double z = Math.sin(angle);
            double nextX = playerX + x * ESCAPE_PROBE_DISTANCE;
            double nextZ = playerZ + z * ESCAPE_PROBE_DISTANCE;
            candidates.add(new EscapeCandidate(
                    x,
                    z,
                    signedDistanceToFootprint(region, nextX, nextZ) - currentProperty,
                    Math.hypot(nextX - creeperX, nextZ - creeperZ) - currentThreat));
        }

        Comparator<EscapeCandidate> preferred = Comparator
                .comparingDouble(EscapeCandidate::combinedProgress)
                .thenComparingDouble(EscapeCandidate::threatProgress)
                .thenComparingDouble(EscapeCandidate::propertyProgress);
        EscapeCandidate best = candidates.stream()
                .filter(candidate -> candidate.propertyProgress() >= -0.01)
                .filter(candidate -> candidate.threatProgress() >= -0.01)
                .max(preferred)
                .orElseGet(() -> candidates.stream()
                        .filter(candidate -> candidate.threatProgress() >= -0.01)
                        .max(preferred)
                        .orElseGet(() -> candidates.stream().max(preferred).orElseThrow()));
        return new EscapeVector(best.x(), best.z());
    }

    private static EscapeVector awayFromThreat(
            double playerX,
            double playerZ,
            double creeperX,
            double creeperZ) {
        double x = playerX - creeperX;
        double z = playerZ - creeperZ;
        double length = Math.hypot(x, z);
        if (length <= EPSILON) return new EscapeVector(1.0, 0.0);
        return new EscapeVector(x / length, z / length);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    public enum Code {
        WILDERNESS_ALLOWED,
        PROPERTY_COMBAT_BUFFER,
        PROPERTY_POLICY_UNAVAILABLE
    }

    public record Region(
            String name,
            String dimension,
            int minX,
            int maxX,
            int minZ,
            int maxZ) {
        public Region {
            name = requireText(name, "name");
            dimension = requireText(dimension, "dimension");
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("property bounds must be normalized");
            }
        }

        public static Region named(ProtectedAreaPolicy.Area area) {
            Objects.requireNonNull(area, "area");
            return new Region(
                    area.name(),
                    area.dimension(),
                    area.minX(), area.maxX(), area.minZ(), area.maxZ());
        }

        /** Positional avoidance only; never registers protected territory or mutation authority. */
        public static Region workContext(String label, String dimension, int minX, int maxX, int minZ, int maxZ) {
            return new Region("work-context:" + label, dimension, minX, maxX, minZ, maxZ);
        }

        public String identity() {
            return name + ':' + dimension + ':' + minX + ':' + maxX + ':' + minZ + ':' + maxZ;
        }

        public String displayName() {
            if (name.startsWith("work-context:")) return "work context '" + name.substring(13) + "'";
            return "protected area '" + name + "'";
        }
    }

    public record Observation(
            boolean propertyPolicyAvailable,
            List<Region> regions,
            String dimension,
            double playerX,
            double playerZ,
            double creeperX,
            double creeperZ,
            boolean charged) {
        public Observation {
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
            dimension = requireText(dimension, "dimension");
            requireFinite(playerX, "playerX");
            requireFinite(playerZ, "playerZ");
            requireFinite(creeperX, "creeperX");
            requireFinite(creeperZ, "creeperZ");
        }

        public static Observation of(
                boolean propertyPolicyAvailable,
                Collection<Region> regions,
                String dimension,
                double playerX,
                double playerZ,
                double creeperX,
                double creeperZ,
                boolean charged) {
            return new Observation(
                    propertyPolicyAvailable,
                    List.copyOf(regions),
                    dimension,
                    playerX, playerZ,
                    creeperX, creeperZ,
                    charged);
        }
    }

    public record EscapeVector(double x, double z) {
        public static final EscapeVector NONE = new EscapeVector(0.0, 0.0);

        public EscapeVector {
            requireFinite(x, "escape x");
            requireFinite(z, "escape z");
            double length = Math.hypot(x, z);
            if (length > EPSILON) {
                x /= length;
                z /= length;
            }
        }

        public boolean present() {
            return Math.hypot(x, z) > EPSILON;
        }
    }

    public record Decision(
            Code code,
            boolean attackAllowed,
            boolean approachAllowed,
            boolean holdPosition,
            EscapeVector escape,
            Optional<Region> property,
            double propertyDistance,
            double blastClearance,
            double engagementClearance,
            String detail) {
        public Decision {
            code = Objects.requireNonNull(code, "code");
            escape = Objects.requireNonNull(escape, "escape");
            property = Objects.requireNonNull(property, "property");
            detail = requireText(detail, "detail");
            if (attackAllowed != approachAllowed) {
                throw new IllegalArgumentException(
                        "creeper approach and attack authority must agree");
            }
            if (attackAllowed != (code == Code.WILDERNESS_ALLOWED)) {
                throw new IllegalArgumentException("allowed decision/code mismatch");
            }
            if (holdPosition && escape.present()) {
                throw new IllegalArgumentException("a hold decision cannot also escape");
            }
            if (!holdPosition && !attackAllowed && !escape.present()) {
                throw new IllegalArgumentException("a denied non-hold decision needs an escape");
            }
        }

        public boolean denied() {
            return !attackAllowed;
        }
    }

    private record Nearest(Region region, double distance) {
    }

    private record EscapeCandidate(
            double x,
            double z,
            double propertyProgress,
            double threatProgress) {
        private double combinedProgress() {
            return propertyProgress * 2.0 + threatProgress * 3.0;
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
