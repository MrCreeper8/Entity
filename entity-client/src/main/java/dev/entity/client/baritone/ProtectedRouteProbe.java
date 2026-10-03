package dev.entity.client.baritone;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded body-cell samples for diagnosing a failed non-destructive route. */
public final class ProtectedRouteProbe {
    private static final int SAMPLES_PER_BLOCK = 4;
    private static final double BODY_RADIUS = 0.3D;

    private ProtectedRouteProbe() {
    }

    /**
     * Samples only the two blocks occupied by a standing player; it deliberately
     * excludes the supporting floor so protected ground is not mistaken for an
     * obstruction.
     */
    public static List<Cell> bodyCellsAlongLine(
            Cell startFeet,
            Cell destinationFeet,
            int maximumBlocks) {
        if (startFeet == null || destinationFeet == null) {
            throw new IllegalArgumentException("route endpoints are required");
        }
        if (maximumBlocks < 1) {
            throw new IllegalArgumentException("maximumBlocks must be positive");
        }

        long dx = (long) destinationFeet.x() - startFeet.x();
        long dy = (long) destinationFeet.y() - startFeet.y();
        long dz = (long) destinationFeet.z() - startFeet.z();
        long longestAxis = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (longestAxis == 0L) return List.of();

        double coveredFraction = Math.min(1.0, maximumBlocks / (double) longestAxis);
        int steps = Math.max(1, (int) Math.ceil(
                longestAxis * coveredFraction * SAMPLES_PER_BLOCK));
        LinkedHashSet<Cell> cells = new LinkedHashSet<>(steps * 2);
        for (int index = 1; index <= steps; index++) {
            double fraction = coveredFraction * index / steps;
            int x = floorCentered(startFeet.x(), dx, fraction);
            int y = floorLinear(startFeet.y(), dy, fraction);
            int z = floorCentered(startFeet.z(), dz, fraction);
            cells.add(new Cell(x, y, z));
            cells.add(new Cell(x, y + 1, z));
        }
        return List.copyOf(cells);
    }

    /**
     * Identifies property touched by the direct horizontal route corridor.
     * This is a planning preflight, not authority: the exact interaction
     * gateway still vetoes every physical mutation at its final coordinate.
     */
    public static Optional<Region> firstIntersectingRegion(
            String dimension,
            Cell startFeet,
            Cell destinationFeet,
            List<Region> regions) {
        String checkedDimension = Objects.requireNonNullElse(dimension, "").trim();
        if (startFeet == null || destinationFeet == null) {
            throw new IllegalArgumentException("route endpoints are required");
        }
        Objects.requireNonNull(regions, "regions");
        double startX = startFeet.x() + 0.5D;
        double startZ = startFeet.z() + 0.5D;
        double deltaX = destinationFeet.x() - startFeet.x();
        double deltaZ = destinationFeet.z() - startFeet.z();
        for (Region region : regions) {
            Objects.requireNonNull(region, "region");
            if (!region.dimension().equals(checkedDimension)) continue;
            double minX = region.minX() - BODY_RADIUS;
            double maxX = region.maxX() + 1.0D + BODY_RADIUS;
            double minZ = region.minZ() - BODY_RADIUS;
            double maxZ = region.maxZ() + 1.0D + BODY_RADIUS;
            if (segmentIntersectsRectangle(
                    startX, startZ, deltaX, deltaZ,
                    minX, maxX, minZ, maxZ)) {
                return Optional.of(region);
            }
        }
        return Optional.empty();
    }

    private static boolean segmentIntersectsRectangle(
            double startX,
            double startZ,
            double deltaX,
            double deltaZ,
            double minX,
            double maxX,
            double minZ,
            double maxZ) {
        double[] interval = {0.0D, 1.0D};
        return clipAxis(startX, deltaX, minX, maxX, interval)
                && clipAxis(startZ, deltaZ, minZ, maxZ, interval);
    }

    private static boolean clipAxis(
            double start,
            double delta,
            double minimum,
            double maximum,
            double[] interval) {
        if (Math.abs(delta) < 1.0E-9D) {
            return start >= minimum && start <= maximum;
        }
        double first = (minimum - start) / delta;
        double second = (maximum - start) / delta;
        if (first > second) {
            double swap = first;
            first = second;
            second = swap;
        }
        interval[0] = Math.max(interval[0], first);
        interval[1] = Math.min(interval[1], second);
        return interval[0] <= interval[1];
    }

    private static int floorCentered(int start, long delta, double fraction) {
        return (int) Math.floor(start + 0.5 + delta * fraction);
    }

    private static int floorLinear(int start, long delta, double fraction) {
        return (int) Math.floor(start + delta * fraction);
    }

    public record Cell(int x, int y, int z) {
    }

    public record Region(
            String name,
            String dimension,
            int minX,
            int maxX,
            int minZ,
            int maxZ) {
        public Region {
            name = Objects.requireNonNullElse(name, "").trim();
            dimension = Objects.requireNonNullElse(dimension, "").trim();
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("region bounds must be normalized");
            }
        }
    }
}
