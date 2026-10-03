package dev.entity.client.autonomy.policy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds a conservative room topology from bounded, already-observed Home geometry.
 *
 * <p>This policy deliberately has no Minecraft or Paper dependency. The caller owns
 * observation authority and must report unloaded or otherwise unobservable blocks as
 * {@link BlockSample#unknown()}. The probe never asks for a block outside the hard scan
 * volume. It treats a room as proved only when every standing cell has observed cover
 * and every horizontal boundary is an observed body barrier or a canonical,
 * player-operable wooden portal. Consequently, a floating roof is not a house and an
 * unloaded wall opening never becomes an invented room.</p>
 */
public final class HomeInteriorGeometryProbe {
    private static final List<int[]> HORIZONTAL_DIRECTIONS = List.of(
            new int[]{-1, 0}, new int[]{0, -1},
            new int[]{0, 1}, new int[]{1, 0});

    private HomeInteriorGeometryProbe() {
    }

    /** Supplies only geometry the client is legitimately allowed to observe. */
    @FunctionalInterface
    public interface CellView {
        /**
         * Returns one abstract block sample. A physical portal must be attached to
         * exactly one canonical block (the lower half for a door), not to every block
         * occupied by its model.
         */
        BlockSample sample(Cell cell);
    }

    public enum Outcome {
        COMPLETE,
        HOME_ANCHOR_NOT_STANDABLE,
        LIMIT_EXCEEDED
    }

    public enum Exposure {
        COVERED,
        EXPOSED,
        UNKNOWN
    }

    public enum RegionKind {
        INTERIOR_ROOM,
        EXTERIOR,
        UNKNOWN
    }

    public enum RegionReason {
        EXPOSED_TO_SKY,
        COVER_NOT_PROVED,
        OPEN_DROP_BOUNDARY,
        UNKNOWN_BOUNDARY,
        SCAN_BOUNDARY
    }

    public enum PortalKind {
        WOODEN_DOOR,
        WOODEN_TRAPDOOR,
        WOODEN_FENCE_GATE
    }

    public enum HorizontalAxis {
        X,
        Z
    }

    public enum PortalRelation {
        ROOM_TO_ROOM,
        ROOM_TO_EXTERIOR,
        ROOM_TO_UNKNOWN,
        WITHIN_ONE_REGION,
        NON_ROOM
    }

    public record Cell(int x, int y, int z) implements Comparable<Cell> {
        public Cell offset(int dx, int dy, int dz) {
            return new Cell(
                    Math.addExact(x, dx),
                    Math.addExact(y, dy),
                    Math.addExact(z, dz));
        }

        public Cell offset(Offset offset) {
            Objects.requireNonNull(offset, "offset");
            return offset(offset.x(), offset.y(), offset.z());
        }

        @Override
        public int compareTo(Cell other) {
            int byY = Integer.compare(y, other.y);
            if (byY != 0) return byY;
            int byX = Integer.compare(x, other.x);
            if (byX != 0) return byX;
            return Integer.compare(z, other.z);
        }
    }

    public record Offset(int x, int y, int z) {
        public Offset {
            if (x == 0 && y == 0 && z == 0) {
                throw new IllegalArgumentException("portal endpoint offset cannot be zero");
            }
            if (x < -2 || x > 2 || y < -2 || y > 2 || z < -2 || z > 2) {
                throw new IllegalArgumentException(
                        "portal endpoint must be within two cells of its canonical block");
            }
        }
    }

    /**
     * A player-operable portal and its two standing-cell endpoints. The adapter
     * computes hatch endpoints from the actual trapdoor placement; wall portals
     * use the provided convenience factories.
     */
    public record PortalSpec(
            PortalKind kind,
            Offset endpointA,
            Offset endpointB) {
        public PortalSpec {
            kind = Objects.requireNonNull(kind, "kind");
            endpointA = Objects.requireNonNull(endpointA, "endpointA");
            endpointB = Objects.requireNonNull(endpointB, "endpointB");
            if (endpointA.equals(endpointB)) {
                throw new IllegalArgumentException("portal endpoints must differ");
            }
            if (kind == PortalKind.WOODEN_TRAPDOOR) {
                if (!((endpointA.y() < 0 && endpointB.y() > 0)
                        || (endpointB.y() < 0 && endpointA.y() > 0))) {
                    throw new IllegalArgumentException(
                            "trapdoor endpoints must describe lower and upper standing cells");
                }
            } else if (!oppositeUnitHorizontal(endpointA, endpointB)) {
                throw new IllegalArgumentException(
                        "door and fence-gate endpoints must oppose across one horizontal axis");
            }
        }

        public static PortalSpec woodenDoor(HorizontalAxis axis) {
            return wallPortal(PortalKind.WOODEN_DOOR, axis);
        }

        public static PortalSpec woodenFenceGate(HorizontalAxis axis) {
            return wallPortal(PortalKind.WOODEN_FENCE_GATE, axis);
        }

        public static PortalSpec woodenTrapdoor(
                Offset lowerStandingCell,
                Offset upperStandingCell) {
            return new PortalSpec(
                    PortalKind.WOODEN_TRAPDOOR,
                    lowerStandingCell,
                    upperStandingCell);
        }

        private static PortalSpec wallPortal(PortalKind kind, HorizontalAxis axis) {
            Objects.requireNonNull(axis, "axis");
            return axis == HorizontalAxis.X
                    ? new PortalSpec(kind, new Offset(-1, 0, 0), new Offset(1, 0, 0))
                    : new PortalSpec(kind, new Offset(0, 0, -1), new Offset(0, 0, 1));
        }

        private static boolean oppositeUnitHorizontal(Offset first, Offset second) {
            return first.y() == 0 && second.y() == 0
                    && first.x() == -second.x()
                    && first.z() == -second.z()
                    && Math.abs(first.x()) + Math.abs(first.z()) == 1;
        }
    }

    /**
     * Collision/cover semantics for one observed block, independent of block IDs.
     * Fluids and other cells that cannot hold a standing body should not be marked
     * {@code bodyClear} merely because they are non-solid.
     */
    public record BlockSample(
            boolean loaded,
            boolean bodyClear,
            boolean supportsStanding,
            boolean blocksCover,
            boolean visibleSky,
            PortalSpec portal) {
        public BlockSample {
            if (!loaded && (bodyClear || supportsStanding || blocksCover
                    || visibleSky || portal != null)) {
                throw new IllegalArgumentException("unknown blocks cannot expose geometry");
            }
            if (visibleSky && (!bodyClear || blocksCover || portal != null)) {
                throw new IllegalArgumentException(
                        "visible-sky sample must be clear and cannot itself be a portal");
            }
        }

        public static BlockSample unknown() {
            return new BlockSample(false, false, false, false, false, null);
        }

        public static BlockSample air() {
            return new BlockSample(true, true, false, false, false, null);
        }

        public static BlockSample openSky() {
            return new BlockSample(true, true, false, false, true, null);
        }

        public static BlockSample solid() {
            return new BlockSample(true, false, true, true, false, null);
        }

        public static BlockSample portal(PortalSpec portal) {
            return portal(portal, false, false);
        }

        public static BlockSample portal(
                PortalSpec portal,
                boolean supportsStanding,
                boolean blocksCover) {
            return new BlockSample(
                    true, false, supportsStanding, blocksCover, false,
                    Objects.requireNonNull(portal, "portal"));
        }
    }

    /** All limits are enforced before the first view read. */
    public record Limits(
            int horizontalRadius,
            int verticalBelow,
            int verticalAbove,
            int ceilingProbeHeight,
            int maximumBlockSamples,
            int maximumBodyCandidates) {
        public Limits {
            if (horizontalRadius < 1 || horizontalRadius > 64) {
                throw new IllegalArgumentException("horizontalRadius must be in [1, 64]");
            }
            if (verticalBelow < 0 || verticalBelow > 32
                    || verticalAbove < 0 || verticalAbove > 32) {
                throw new IllegalArgumentException("vertical scan spans must be in [0, 32]");
            }
            if (ceilingProbeHeight < 1 || ceilingProbeHeight > 32) {
                throw new IllegalArgumentException("ceilingProbeHeight must be in [1, 32]");
            }
            if (maximumBlockSamples < 1 || maximumBlockSamples > 2_000_000) {
                throw new IllegalArgumentException(
                        "maximumBlockSamples must be in [1, 2000000]");
            }
            if (maximumBodyCandidates < 1 || maximumBodyCandidates > 1_000_000) {
                throw new IllegalArgumentException(
                        "maximumBodyCandidates must be in [1, 1000000]");
            }
        }
    }

    public record Region(
            int id,
            RegionKind kind,
            List<Cell> cells,
            Set<RegionReason> reasons) {
        public Region {
            if (id < 0) throw new IllegalArgumentException("region id cannot be negative");
            kind = Objects.requireNonNull(kind, "kind");
            cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
            if (cells.isEmpty()) throw new IllegalArgumentException("region cannot be empty");
            Objects.requireNonNull(reasons, "reasons");
            reasons = reasons.isEmpty()
                    ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(reasons));
        }
    }

    public record PortalEdge(
            Cell portalCell,
            PortalKind kind,
            Cell endpointA,
            Cell endpointB,
            int regionA,
            int regionB,
            PortalRelation relation) {
        public PortalEdge {
            portalCell = Objects.requireNonNull(portalCell, "portalCell");
            kind = Objects.requireNonNull(kind, "kind");
            endpointA = Objects.requireNonNull(endpointA, "endpointA");
            endpointB = Objects.requireNonNull(endpointB, "endpointB");
            relation = Objects.requireNonNull(relation, "relation");
            if (regionA < -1 || regionB < -1) {
                throw new IllegalArgumentException("region references must be -1 or a region id");
            }
        }

        public OptionalInt endpointARegion() {
            return regionA < 0 ? OptionalInt.empty() : OptionalInt.of(regionA);
        }

        public OptionalInt endpointBRegion() {
            return regionB < 0 ? OptionalInt.empty() : OptionalInt.of(regionB);
        }
    }

    public record Scan(
            Outcome outcome,
            Cell homeAnchor,
            Set<Cell> standableCells,
            Map<Cell, Exposure> exposureByCell,
            List<Region> regions,
            List<PortalEdge> portals,
            OptionalInt homeRegionId,
            int sampledBlocks) {
        public Scan {
            outcome = Objects.requireNonNull(outcome, "outcome");
            homeAnchor = Objects.requireNonNull(homeAnchor, "homeAnchor");
            Objects.requireNonNull(standableCells, "standableCells");
            standableCells = Collections.unmodifiableSet(
                    new LinkedHashSet<>(standableCells));
            Objects.requireNonNull(exposureByCell, "exposureByCell");
            exposureByCell = Collections.unmodifiableMap(
                    new LinkedHashMap<>(exposureByCell));
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
            portals = List.copyOf(Objects.requireNonNull(portals, "portals"));
            homeRegionId = Objects.requireNonNull(homeRegionId, "homeRegionId");
            if (sampledBlocks < 0) {
                throw new IllegalArgumentException("sampledBlocks cannot be negative");
            }
        }

        public List<Region> rooms() {
            return regions.stream()
                    .filter(region -> region.kind() == RegionKind.INTERIOR_ROOM)
                    .toList();
        }
    }

    public static Scan probe(Cell homeAnchor, Limits limits, CellView view) {
        Objects.requireNonNull(homeAnchor, "homeAnchor");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(view, "view");

        Bounds bounds = Bounds.around(homeAnchor, limits);
        long bodyCandidates = bounds.bodyCandidateCount();
        long blockSamples = bounds.blockSampleCount();
        if (bodyCandidates < 0 || blockSamples < 0
                || bodyCandidates > limits.maximumBodyCandidates()
                || blockSamples > limits.maximumBlockSamples()) {
            return new Scan(
                    Outcome.LIMIT_EXCEEDED, homeAnchor,
                    Set.of(), Map.of(), List.of(), List.of(),
                    OptionalInt.empty(), 0);
        }

        Sampler sampler = new Sampler(view, bounds);
        sampler.loadBoundedVolume();

        TreeSet<Cell> standable = new TreeSet<>();
        for (long y = bounds.feetMinY(); y <= bounds.feetMaxY(); y++) {
            for (long x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (long z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    Cell feet = new Cell((int) x, (int) y, (int) z);
                    if (isStandable(feet, sampler)) standable.add(feet);
                }
            }
        }

        TreeMap<Cell, Exposure> exposure = new TreeMap<>();
        for (Cell feet : standable) {
            exposure.put(feet, exposure(feet, limits.ceilingProbeHeight(), sampler));
        }

        ComponentIndex index = components(standable, bounds);
        List<Region> regions = classifyRegions(index, exposure, sampler, bounds);
        List<PortalEdge> portals = portalEdges(sampler, index.regionByCell(), regions);
        int homeRegion = index.regionByCell().getOrDefault(homeAnchor, -1);
        Outcome outcome = homeRegion < 0
                ? Outcome.HOME_ANCHOR_NOT_STANDABLE
                : Outcome.COMPLETE;
        return new Scan(
                outcome, homeAnchor, standable, exposure, regions, portals,
                homeRegion < 0 ? OptionalInt.empty() : OptionalInt.of(homeRegion),
                sampler.readCount());
    }

    private static boolean isStandable(Cell feet, Sampler sampler) {
        BlockSample floor = sampler.read(feet.offset(0, -1, 0));
        BlockSample lower = sampler.read(feet);
        BlockSample upper = sampler.read(feet.offset(0, 1, 0));
        return floor.loaded() && floor.supportsStanding()
                && bodyCellClear(lower) && bodyCellClear(upper);
    }

    private static boolean bodyCellClear(BlockSample sample) {
        return sample.loaded() && sample.bodyClear() && sample.portal() == null;
    }

    private static Exposure exposure(Cell feet, int height, Sampler sampler) {
        for (int offset = 2; offset <= height + 1; offset++) {
            BlockSample sample = sampler.read(feet.offset(0, offset, 0));
            if (!sample.loaded()) return Exposure.UNKNOWN;
            if (sample.blocksCover()) return Exposure.COVERED;
            if (sample.visibleSky()) return Exposure.EXPOSED;
        }
        return Exposure.UNKNOWN;
    }

    private static ComponentIndex components(Set<Cell> standable, Bounds bounds) {
        Map<Cell, Integer> regionByCell = new LinkedHashMap<>();
        List<List<Cell>> components = new ArrayList<>();
        TreeSet<Cell> remaining = new TreeSet<>(standable);
        while (!remaining.isEmpty()) {
            Cell start = remaining.first();
            int id = components.size();
            ArrayDeque<Cell> queue = new ArrayDeque<>();
            TreeSet<Cell> cells = new TreeSet<>();
            remaining.remove(start);
            queue.add(start);
            while (!queue.isEmpty()) {
                Cell current = queue.removeFirst();
                cells.add(current);
                regionByCell.put(current, id);
                for (Cell neighbor : movementNeighbors(current, standable, bounds)) {
                    if (remaining.remove(neighbor)) queue.addLast(neighbor);
                }
            }
            components.add(List.copyOf(cells));
        }
        return new ComponentIndex(List.copyOf(components), Map.copyOf(regionByCell));
    }

    private static List<Cell> movementNeighbors(
            Cell cell,
            Set<Cell> standable,
            Bounds bounds) {
        List<Cell> neighbors = new ArrayList<>(12);
        for (int[] direction : HORIZONTAL_DIRECTIONS) {
            for (int dy = -1; dy <= 1; dy++) {
                Cell candidate = cell.offset(direction[0], dy, direction[1]);
                if (bounds.containsFeet(candidate) && standable.contains(candidate)) {
                    neighbors.add(candidate);
                }
            }
        }
        neighbors.sort(Comparator.naturalOrder());
        return neighbors;
    }

    private static List<Region> classifyRegions(
            ComponentIndex index,
            Map<Cell, Exposure> exposure,
            Sampler sampler,
            Bounds bounds) {
        List<Region> result = new ArrayList<>(index.components().size());
        for (int id = 0; id < index.components().size(); id++) {
            List<Cell> cells = index.components().get(id);
            EnumSet<RegionReason> reasons = EnumSet.noneOf(RegionReason.class);
            for (Cell cell : cells) {
                Exposure cellExposure = exposure.get(cell);
                if (cellExposure == Exposure.EXPOSED) {
                    reasons.add(RegionReason.EXPOSED_TO_SKY);
                } else if (cellExposure == Exposure.UNKNOWN) {
                    reasons.add(RegionReason.COVER_NOT_PROVED);
                }
                inspectHorizontalBoundaries(
                        cell, index.regionByCell(), sampler, bounds, reasons);
            }
            boolean definiteExterior = reasons.contains(RegionReason.EXPOSED_TO_SKY)
                    || reasons.contains(RegionReason.OPEN_DROP_BOUNDARY);
            boolean ambiguous = reasons.contains(RegionReason.COVER_NOT_PROVED)
                    || reasons.contains(RegionReason.UNKNOWN_BOUNDARY)
                    || reasons.contains(RegionReason.SCAN_BOUNDARY);
            RegionKind kind = definiteExterior
                    ? RegionKind.EXTERIOR
                    : ambiguous ? RegionKind.UNKNOWN : RegionKind.INTERIOR_ROOM;
            result.add(new Region(id, kind, cells, reasons));
        }
        return List.copyOf(result);
    }

    private static void inspectHorizontalBoundaries(
            Cell cell,
            Map<Cell, Integer> regionByCell,
            Sampler sampler,
            Bounds bounds,
            EnumSet<RegionReason> reasons) {
        for (int[] direction : HORIZONTAL_DIRECTIONS) {
            boolean connected = false;
            for (int dy = -1; dy <= 1; dy++) {
                if (regionByCell.containsKey(
                        cell.offset(direction[0], dy, direction[1]))) {
                    connected = true;
                    break;
                }
            }
            if (connected) continue;

            Cell neighborFeet = cell.offset(direction[0], 0, direction[1]);
            if (!bounds.containsFeet(neighborFeet)) {
                reasons.add(RegionReason.SCAN_BOUNDARY);
                continue;
            }
            BlockSample lower = sampler.read(neighborFeet);
            BlockSample upper = sampler.read(neighborFeet.offset(0, 1, 0));
            if (knownBodyBarrier(lower) || knownBodyBarrier(upper)) continue;
            if (!lower.loaded() || !upper.loaded()) {
                reasons.add(RegionReason.UNKNOWN_BOUNDARY);
                continue;
            }
            BlockSample floor = sampler.read(neighborFeet.offset(0, -1, 0));
            if (!floor.loaded()) {
                reasons.add(RegionReason.UNKNOWN_BOUNDARY);
            } else if (!floor.supportsStanding()) {
                reasons.add(RegionReason.OPEN_DROP_BOUNDARY);
            } else {
                // A clear supported neighbor should have joined this component.
                // Conservatism is safer than silently inventing a closed wall.
                reasons.add(RegionReason.UNKNOWN_BOUNDARY);
            }
        }
    }

    private static boolean knownBodyBarrier(BlockSample sample) {
        return sample.loaded() && (sample.portal() != null || !sample.bodyClear());
    }

    private static List<PortalEdge> portalEdges(
            Sampler sampler,
            Map<Cell, Integer> regionByCell,
            List<Region> regions) {
        Map<Integer, RegionKind> kindByRegion = new LinkedHashMap<>();
        for (Region region : regions) kindByRegion.put(region.id(), region.kind());

        List<PortalEdge> result = new ArrayList<>();
        for (Map.Entry<Cell, BlockSample> entry : sampler.samples().entrySet()) {
            PortalSpec spec = entry.getValue().portal();
            if (spec == null) continue;
            Cell endpointA = entry.getKey().offset(spec.endpointA());
            Cell endpointB = entry.getKey().offset(spec.endpointB());
            int regionA = regionByCell.getOrDefault(endpointA, -1);
            int regionB = regionByCell.getOrDefault(endpointB, -1);
            result.add(new PortalEdge(
                    entry.getKey(), spec.kind(), endpointA, endpointB,
                    regionA, regionB,
                    relation(regionA, regionB, kindByRegion)));
        }
        result.sort(Comparator.comparing(PortalEdge::portalCell));
        return List.copyOf(result);
    }

    private static PortalRelation relation(
            int regionA,
            int regionB,
            Map<Integer, RegionKind> kindByRegion) {
        if (regionA >= 0 && regionA == regionB) return PortalRelation.WITHIN_ONE_REGION;
        RegionKind first = kindByRegion.get(regionA);
        RegionKind second = kindByRegion.get(regionB);
        if (first == RegionKind.INTERIOR_ROOM && second == RegionKind.INTERIOR_ROOM) {
            return PortalRelation.ROOM_TO_ROOM;
        }
        if ((first == RegionKind.INTERIOR_ROOM && second == RegionKind.EXTERIOR)
                || (second == RegionKind.INTERIOR_ROOM && first == RegionKind.EXTERIOR)) {
            return PortalRelation.ROOM_TO_EXTERIOR;
        }
        if (first == RegionKind.INTERIOR_ROOM || second == RegionKind.INTERIOR_ROOM) {
            return PortalRelation.ROOM_TO_UNKNOWN;
        }
        return PortalRelation.NON_ROOM;
    }

    private record ComponentIndex(
            List<List<Cell>> components,
            Map<Cell, Integer> regionByCell) {
    }

    private record Bounds(
            int minX,
            int maxX,
            int feetMinY,
            int feetMaxY,
            int blockMinY,
            int blockMaxY,
            int minZ,
            int maxZ) {
        private static Bounds around(Cell anchor, Limits limits) {
            int minX = Math.subtractExact(anchor.x(), limits.horizontalRadius());
            int maxX = Math.addExact(anchor.x(), limits.horizontalRadius());
            int minZ = Math.subtractExact(anchor.z(), limits.horizontalRadius());
            int maxZ = Math.addExact(anchor.z(), limits.horizontalRadius());
            int feetMinY = Math.subtractExact(anchor.y(), limits.verticalBelow());
            int feetMaxY = Math.addExact(anchor.y(), limits.verticalAbove());
            int blockMinY = Math.subtractExact(feetMinY, 1);
            int blockMaxY = Math.addExact(
                    feetMaxY, limits.ceilingProbeHeight() + 1);
            // Boundary classification probes one horizontal neighbor. Reserve
            // that representable coordinate even though the view is never read
            // outside the declared block volume.
            Math.subtractExact(minX, 1);
            Math.addExact(maxX, 1);
            Math.subtractExact(minZ, 1);
            Math.addExact(maxZ, 1);
            Math.subtractExact(blockMinY, 2);
            Math.addExact(blockMaxY, 2);
            return new Bounds(
                    minX, maxX, feetMinY, feetMaxY,
                    blockMinY, blockMaxY, minZ, maxZ);
        }

        private boolean containsFeet(Cell cell) {
            return cell.x() >= minX && cell.x() <= maxX
                    && cell.y() >= feetMinY && cell.y() <= feetMaxY
                    && cell.z() >= minZ && cell.z() <= maxZ;
        }

        private boolean containsBlock(Cell cell) {
            return cell.x() >= minX && cell.x() <= maxX
                    && cell.y() >= blockMinY && cell.y() <= blockMaxY
                    && cell.z() >= minZ && cell.z() <= maxZ;
        }

        private long bodyCandidateCount() {
            return volume(
                    (long) maxX - minX + 1,
                    (long) feetMaxY - feetMinY + 1,
                    (long) maxZ - minZ + 1);
        }

        private long blockSampleCount() {
            return volume(
                    (long) maxX - minX + 1,
                    (long) blockMaxY - blockMinY + 1,
                    (long) maxZ - minZ + 1);
        }

        private static long volume(long x, long y, long z) {
            try {
                return Math.multiplyExact(Math.multiplyExact(x, y), z);
            } catch (ArithmeticException overflow) {
                return -1L;
            }
        }
    }

    private static final class Sampler {
        private final CellView view;
        private final Bounds bounds;
        private final TreeMap<Cell, BlockSample> samples = new TreeMap<>();

        private Sampler(CellView view, Bounds bounds) {
            this.view = view;
            this.bounds = bounds;
        }

        private void loadBoundedVolume() {
            for (long y = bounds.blockMinY(); y <= bounds.blockMaxY(); y++) {
                for (long x = bounds.minX(); x <= bounds.maxX(); x++) {
                    for (long z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                        read(new Cell((int) x, (int) y, (int) z));
                    }
                }
            }
        }

        private BlockSample read(Cell cell) {
            if (!bounds.containsBlock(cell)) return BlockSample.unknown();
            return samples.computeIfAbsent(cell, ignored -> Objects.requireNonNull(
                    view.sample(cell), "CellView returned null for " + cell));
        }

        private int readCount() {
            return samples.size();
        }

        private Map<Cell, BlockSample> samples() {
            return Collections.unmodifiableMap(samples);
        }
    }
}
