package dev.entity.core.farm;

import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Pure, deterministic contract for one owner-selected managed-farm pass. */
public final class ManagedFarmPolicy {
    /**
     * Named-farm scans are cell-budgeted as well as radius-bounded. Sixty-four-block
     * spacing keeps the farthest corner between four coverage points within the
     * effective live crop scan instead of silently leaving gaps in a large field.
     */
    public static final int COVERAGE_SPACING = 64;

    /**
     * Finish long-distance travel outside the selected field, then start a fresh
     * local route. Baritone snapshots loaded chunks when a goal begins; four
     * blocks is close enough for the field chunk to be present in that fresh
     * snapshot without pretending that the exterior point is farm coverage.
     */
    public static final int LOCAL_ROUTE_CLEARANCE = 4;

    private static final List<CropFamily> CROPS = List.of(
            new CropFamily("wheat", "wheat", "wheat_seeds"),
            new CropFamily("carrots", "carrot", "carrot"),
            new CropFamily("potatoes", "potato", "potato"),
            new CropFamily("beetroots", "beetroot", "beetroot_seeds"));

    private ManagedFarmPolicy() {
    }

    public static List<CropFamily> crops() {
        return CROPS;
    }

    /**
     * Produces stable X/Z coverage targets for a rectangular harvesting area.
     * Each point is inside the selection and neighbouring scans overlap, so an
     * area up to the protected-area maximum can be covered without claiming
     * that one local scan observed the whole field.
     */
    public static List<CoveragePoint> coverage(ProtectedAreaPolicy.Area area) {
        Objects.requireNonNull(area, "area");
        if (area.kind() != ProtectedAreaPolicy.AreaKind.HARVESTING) {
            throw new IllegalArgumentException("managed farming requires a harvesting area");
        }
        List<Integer> xs = coverageAxis(area.minX(), area.maxX());
        List<Integer> zs = coverageAxis(area.minZ(), area.maxZ());
        ArrayList<CoveragePoint> result = new ArrayList<>(xs.size() * zs.size());
        for (int z : zs) {
            for (int x : xs) result.add(new CoveragePoint(x, z));
        }
        return List.copyOf(result);
    }

    /**
     * Returns the nearest deterministic point on a clearance ring around the
     * farm, or empty when the worker is already in that local ring. This keeps
     * remote travel and structure-aware local entry as separate path goals.
     */
    public static Optional<CoveragePoint> exteriorApproach(
            ProtectedAreaPolicy.Area area,
            int fromX,
            int fromZ) {
        Objects.requireNonNull(area, "area");
        if (area.kind() != ProtectedAreaPolicy.AreaKind.HARVESTING) {
            throw new IllegalArgumentException("managed farming requires a harvesting area");
        }
        int clearance = LOCAL_ROUTE_CLEARANCE;
        long expandedMinX = (long) area.minX() - clearance;
        long expandedMaxX = (long) area.maxX() + clearance;
        long expandedMinZ = (long) area.minZ() - clearance;
        long expandedMaxZ = (long) area.maxZ() + clearance;
        if (fromX >= expandedMinX && fromX <= expandedMaxX
                && fromZ >= expandedMinZ && fromZ <= expandedMaxZ) {
            return Optional.empty();
        }

        List<CoveragePoint> candidates = List.of(
                new CoveragePoint(saturatedCoordinate(expandedMinX),
                        clamp(fromZ, area.minZ(), area.maxZ())),
                new CoveragePoint(saturatedCoordinate(expandedMaxX),
                        clamp(fromZ, area.minZ(), area.maxZ())),
                new CoveragePoint(clamp(fromX, area.minX(), area.maxX()),
                        saturatedCoordinate(expandedMinZ)),
                new CoveragePoint(clamp(fromX, area.minX(), area.maxX()),
                        saturatedCoordinate(expandedMaxZ)));
        CoveragePoint best = candidates.getFirst();
        long bestDistance = squaredDistance(best, fromX, fromZ);
        for (int index = 1; index < candidates.size(); index++) {
            CoveragePoint candidate = candidates.get(index);
            long distance = squaredDistance(candidate, fromX, fromZ);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return Optional.of(best);
    }

    /** Completion predicate for the X/Z-only farm loading waypoint. */
    public static boolean horizontalArrivalSatisfied(
            int targetX,
            int targetZ,
            int candidateX,
            int candidateZ,
            int range) {
        if (range < 0) throw new IllegalArgumentException("range cannot be negative");
        return Math.abs((long) candidateX - targetX) <= range
                && Math.abs((long) candidateZ - targetZ) <= range;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int saturatedCoordinate(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private static long squaredDistance(CoveragePoint point, int x, int z) {
        long dx = (long) point.x() - x;
        long dz = (long) point.z() - z;
        // Selected areas are bounded, but saturating keeps this helper total at
        // the integer world extremes as well.
        try {
            return Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dz, dz));
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static List<Integer> coverageAxis(int minimum, int maximum) {
        if (minimum > maximum) throw new IllegalArgumentException("farm bounds are inverted");
        int middle = minimum + (maximum - minimum) / 2;
        if (maximum - minimum + 1 <= COVERAGE_SPACING) return List.of(middle);
        ArrayList<Integer> result = new ArrayList<>();
        int first = Math.min(maximum, minimum + COVERAGE_SPACING / 2);
        for (int coordinate = first; coordinate <= maximum; coordinate += COVERAGE_SPACING) {
            result.add(coordinate);
            if (coordinate > Integer.MAX_VALUE - COVERAGE_SPACING) break;
        }
        int last = result.getLast();
        if (maximum - last > COVERAGE_SPACING / 2) result.add(maximum);
        return List.copyOf(result);
    }

    /** Stable command-to-client binding; unrelated policy revisions do not invalidate a run. */
    public static String areaFingerprint(ProtectedAreaPolicy.Area area) {
        Objects.requireNonNull(area, "area");
        return String.join("|",
                "managed-farm-v1",
                area.name(),
                area.kind().name().toLowerCase(Locale.ROOT),
                area.dimension(),
                Integer.toString(area.minX()),
                Integer.toString(area.maxX()),
                Integer.toString(area.minZ()),
                Integer.toString(area.maxZ()));
    }

    public record CropFamily(String block, String produce, String replant) {
        public CropFamily {
            block = identifier(block, "crop block");
            produce = identifier(produce, "produce item");
            replant = identifier(replant, "replant item");
        }
    }

    public record CoveragePoint(int x, int z) {
    }

    private static String identifier(String value, String label) {
        String normalized = Objects.requireNonNullElse(value, "")
                .trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        if (!normalized.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }
}
