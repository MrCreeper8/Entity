package dev.entity.core.stewardship;

import java.util.Objects;

/** Exact zone and cleanup semantics shared by routing and final mutation fences. */
public final class CleanPropertyPolicy {
    private CleanPropertyPolicy() {
    }

    public static Zone classify(
            ProtectedAreaPolicy.Snapshot protectedAreas,
            Worksite activeWorksite,
            Coordinate coordinate) {
        Objects.requireNonNull(protectedAreas, "protectedAreas");
        Objects.requireNonNull(coordinate, "coordinate");
        if (protectedAreas.firstContaining(
                coordinate.dimension(), coordinate.x(), coordinate.z()).isPresent()) {
            return Zone.CLEAN_PROPERTY;
        }
        if (activeWorksite != null && activeWorksite.contains(coordinate)) return Zone.WORKSITE;
        return Zone.TRANSIT;
    }

    /**
     * Clean property may carry debt only for exact state Entity owns and can restore. Mines and
     * wilderness never become cleanup projects merely because Entity later returns Home.
     */
    public static CleanupDecision cleanupDecision(
            Zone zone,
            boolean entityOwned,
            boolean recordedBeforeMutation,
            boolean originalStateObserved,
            boolean reversiblyRestorable) {
        Objects.requireNonNull(zone, "zone");
        if (zone != Zone.CLEAN_PROPERTY) {
            return new CleanupDecision(
                    CleanupDebt.NONE,
                    "purposeful work outside clean property may remain and creates no cleanup debt");
        }
        if (entityOwned && recordedBeforeMutation && originalStateObserved
                && reversiblyRestorable) {
            return new CleanupDecision(
                    CleanupDebt.RECORDED_REVERSIBLE,
                    "exact Entity-owned clean-property change must be restored");
        }
        return new CleanupDecision(
                CleanupDebt.DENIED,
                "clean property mutation is denied without exact owned, save-before-change restoration");
    }

    public enum Zone {
        CLEAN_PROPERTY,
        WORKSITE,
        TRANSIT
    }

    public enum CleanupDebt {
        NONE,
        RECORDED_REVERSIBLE,
        DENIED
    }

    public record Coordinate(String dimension, int x, int y, int z) {
        public Coordinate {
            dimension = requireText(dimension, "dimension");
        }
    }

    /** One explicit active resource objective; an inactive or later mission cannot claim it. */
    public record Worksite(
            String objectiveId,
            String dimension,
            int centerX,
            int centerY,
            int centerZ,
            int horizontalRadius,
            int verticalRadius) {
        public Worksite {
            objectiveId = requireText(objectiveId, "objectiveId");
            dimension = requireText(dimension, "dimension");
            if (horizontalRadius < 0 || horizontalRadius > 512
                    || verticalRadius < 0 || verticalRadius > 384) {
                throw new IllegalArgumentException("worksite bounds are outside the hard limit");
            }
        }

        public boolean contains(Coordinate coordinate) {
            Objects.requireNonNull(coordinate, "coordinate");
            return dimension.equals(coordinate.dimension())
                    && Math.abs((long) coordinate.x() - centerX) <= horizontalRadius
                    && Math.abs((long) coordinate.y() - centerY) <= verticalRadius
                    && Math.abs((long) coordinate.z() - centerZ) <= horizontalRadius;
        }
    }

    public record CleanupDecision(CleanupDebt debt, String detail) {
        public CleanupDecision {
            Objects.requireNonNull(debt, "debt");
            detail = requireText(detail, "detail");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
