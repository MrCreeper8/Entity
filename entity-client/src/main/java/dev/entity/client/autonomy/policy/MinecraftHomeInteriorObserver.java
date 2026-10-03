package dev.entity.client.autonomy.policy;

import dev.entity.client.autonomy.policy.HomeEconomySession.HomeAnchor;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.BlockSample;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.Cell;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.Exposure;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.HorizontalAxis;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.Limits;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.Offset;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.PortalKind;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.PortalRelation;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.PortalSpec;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.RegionKind;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.Scan;
import dev.entity.client.autonomy.workspace.MinecraftWorkspaceProbe;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Objects;
import java.util.Optional;

/**
 * Loaded-client adapter for {@link HomeInteriorGeometryProbe}.
 *
 * <p>The only coordinate authority accepted by this observer is the durable,
 * owner-selected {@link HomeAnchor}. The scan volume and refresh cadence are
 * fixed here so a caller cannot accidentally turn factual Home observation into
 * an unbounded world search. Unloaded chunks and out-of-height cells are reported
 * as unknown without asking Minecraft for block state.</p>
 */
public final class MinecraftHomeInteriorObserver {
    public static final int SCAN_CADENCE_TICKS = 20;
    public static final Limits SCAN_LIMITS = new Limits(
            6, 4, 8, 8,
            4_096, 2_500);

    private final MinecraftClient client;
    private Observation cached = Observation.unavailable(
            Availability.HOME_NOT_SELECTED, "", "", null, -1L, 0L,
            "Home has not been selected");
    private HomeAnchor cachedHome;
    private ClientWorld cachedWorld;
    private long lastScanTick = Long.MIN_VALUE;
    private long revision;

    public MinecraftHomeInteriorObserver(MinecraftClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Returns a newly observed scan or the immutable scan cached for this cadence.
     * This method must be called from the Minecraft client thread.
     */
    public synchronized Observation observe(HomeAnchor home) {
        Objects.requireNonNull(home, "home");
        if (!client.isOnThread()) {
            return unavailable(
                    Availability.OFF_CLIENT_THREAD, home, "", -1L,
                    "Home geometry must be observed on the Minecraft client thread");
        }

        ClientWorld world = client.world;
        if (world == null) {
            return unavailable(
                    Availability.WORLD_UNAVAILABLE, home, "", -1L,
                    "client has not joined a world");
        }
        String observedDimension = dimension(world);
        long worldTick = world.getTime();
        if (!home.dimension().equals(observedDimension)) {
            return unavailable(
                    Availability.DIMENSION_MISMATCH, home, observedDimension, worldTick,
                    "selected Home is in " + home.dimension()
                            + "; loaded world is " + observedDimension);
        }

        BlockPos anchor = new BlockPos(home.x(), home.y(), home.z());
        if (!withinBuildHeight(world, anchor)
                || !world.isChunkLoaded(anchor.getX() >> 4, anchor.getZ() >> 4)) {
            return unavailable(
                    Availability.HOME_ANCHOR_UNLOADED, home, observedDimension, worldTick,
                    "selected Home anchor is not loaded and observable");
        }

        if (cachedWorld == world
                && home.equals(cachedHome)
                && cached.availability() == Availability.AVAILABLE
                && worldTick >= lastScanTick
                && worldTick - lastScanTick < SCAN_CADENCE_TICKS) {
            return cached;
        }

        Cell homeCell = new Cell(home.x(), home.y(), home.z());
        Scan scan;
        try {
            scan = HomeInteriorGeometryProbe.probe(
                    homeCell, SCAN_LIMITS,
                    cell -> sample(world, observedDimension, cell));
        } catch (ArithmeticException outOfRange) {
            return unavailable(
                    Availability.HOME_ANCHOR_OUT_OF_RANGE,
                    home, observedDimension, worldTick,
                    "selected Home cannot be represented by the bounded scan volume");
        }

        if (client.world != world || !observedDimension.equals(currentDimension())) {
            return unavailable(
                    Availability.WORLD_CHANGED_DURING_SCAN,
                    home, currentDimension(), -1L,
                    "loaded world changed while Home geometry was being observed");
        }

        Summary summary = Summary.from(scan);
        revision++;
        cached = new Observation(
                Availability.AVAILABLE,
                home.dimension(), observedDimension, homeCell,
                worldTick, revision,
                "bounded loaded-client Home geometry",
                Optional.of(scan), Optional.of(summary));
        cachedHome = home;
        cachedWorld = world;
        lastScanTick = worldTick;
        return cached;
    }

    /** Clears any prior Home identity and its geometry without reading the world. */
    public synchronized Observation clearHome() {
        if (cachedHome == null
                && cached.availability() == Availability.HOME_NOT_SELECTED) {
            return cached;
        }
        cachedHome = null;
        cachedWorld = null;
        lastScanTick = Long.MIN_VALUE;
        revision++;
        cached = Observation.unavailable(
                Availability.HOME_NOT_SELECTED, "", "", null,
                -1L, revision, "Home has not been selected");
        return cached;
    }

    public synchronized Observation cachedObservation() {
        return cached;
    }

    private Observation unavailable(
            Availability availability,
            HomeAnchor home,
            String observedDimension,
            long worldTick,
            String detail) {
        cachedHome = null;
        cachedWorld = null;
        lastScanTick = Long.MIN_VALUE;
        revision++;
        cached = Observation.unavailable(
                availability, home.dimension(), observedDimension,
                new Cell(home.x(), home.y(), home.z()),
                worldTick, revision, detail);
        return cached;
    }

    private BlockSample sample(
            ClientWorld expectedWorld,
            String expectedDimension,
            Cell cell) {
        if (client.world != expectedWorld
                || !expectedDimension.equals(dimension(expectedWorld))) {
            return BlockSample.unknown();
        }
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        if (!withinBuildHeight(expectedWorld, pos)
                || !expectedWorld.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return BlockSample.unknown();
        }

        BlockState state = expectedWorld.getBlockState(pos);
        boolean fluidFree = state.getFluidState().isEmpty();
        PortalSpec portal = fluidFree ? playerOperableWoodenPortal(state) : null;
        boolean support = fluidFree
                && MinecraftWorkspaceProbe.hasStableFullTopSupport(client, pos);
        boolean cover = fluidFree
                && state.isSideSolidFullSquare(expectedWorld, pos, Direction.DOWN);
        if (portal != null) {
            return BlockSample.portal(portal, support, cover);
        }

        boolean bodyClear = fluidFree
                && state.getCollisionShape(expectedWorld, pos).isEmpty();
        boolean visibleSky = bodyClear && !cover && expectedWorld.isSkyVisible(pos);
        return new BlockSample(
                true, bodyClear, support, cover, visibleSky, null);
    }

    private static PortalSpec playerOperableWoodenPortal(BlockState state) {
        if (state.getBlock() instanceof DoorBlock
                && state.isIn(BlockTags.WOODEN_DOORS)
                && DoorBlock.canOpenByHand(state)
                && state.contains(DoorBlock.HALF)
                && state.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && state.contains(Properties.HORIZONTAL_FACING)) {
            return PortalSpec.woodenDoor(horizontalAxis(
                    state.get(Properties.HORIZONTAL_FACING)));
        }
        if (state.getBlock() instanceof FenceGateBlock
                && state.isIn(BlockTags.FENCE_GATES)
                && state.contains(Properties.HORIZONTAL_FACING)) {
            return PortalSpec.woodenFenceGate(horizontalAxis(
                    state.get(Properties.HORIZONTAL_FACING)));
        }
        if (state.getBlock() instanceof TrapdoorBlock
                && state.isIn(BlockTags.WOODEN_TRAPDOORS)
                && state.contains(TrapdoorBlock.HALF)
                && state.contains(Properties.HORIZONTAL_FACING)) {
            BlockHalf half = state.get(TrapdoorBlock.HALF);
            boolean open = state.contains(TrapdoorBlock.OPEN)
                    && state.get(TrapdoorBlock.OPEN);
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            return trapdoorPortal(half, open, facing);
        }
        return null;
    }

    /**
     * A closed bottom hatch needs two complete body cells below it; a top-half
     * hatch leaves the lower body cell inside its block column. When open, its
     * placed facing names the panel edge, so both landing cells are shifted away
     * from that occupied edge while preserving a vertical lower/upper relation.
     */
    private static PortalSpec trapdoorPortal(
            BlockHalf half,
            boolean open,
            Direction facing) {
        int lowerY = !open && half == BlockHalf.TOP ? -1 : -2;
        int offsetX = open ? -facing.getOffsetX() : 0;
        int offsetZ = open ? -facing.getOffsetZ() : 0;
        return PortalSpec.woodenTrapdoor(
                new Offset(offsetX, lowerY, offsetZ),
                new Offset(offsetX, 1, offsetZ));
    }

    private static HorizontalAxis horizontalAxis(Direction direction) {
        return direction.getAxis() == Direction.Axis.X
                ? HorizontalAxis.X
                : HorizontalAxis.Z;
    }

    private static boolean withinBuildHeight(ClientWorld world, BlockPos pos) {
        return pos.getY() >= world.getBottomY()
                && pos.getY() <= world.getTopYInclusive();
    }

    private String currentDimension() {
        return client.world == null ? "" : dimension(client.world);
    }

    private static String dimension(ClientWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    public enum Availability {
        AVAILABLE,
        HOME_NOT_SELECTED,
        OFF_CLIENT_THREAD,
        WORLD_UNAVAILABLE,
        DIMENSION_MISMATCH,
        HOME_ANCHOR_UNLOADED,
        HOME_ANCHOR_OUT_OF_RANGE,
        WORLD_CHANGED_DURING_SCAN
    }

    /** Immutable cached observation; scan and summary are present only when available. */
    public record Observation(
            Availability availability,
            String expectedDimension,
            String observedDimension,
            Cell homeAnchor,
            long worldTick,
            long revision,
            String detail,
            Optional<Scan> scan,
            Optional<Summary> summary) {
        public Observation {
            availability = Objects.requireNonNull(availability, "availability");
            expectedDimension = Objects.requireNonNull(expectedDimension, "expectedDimension");
            observedDimension = Objects.requireNonNull(observedDimension, "observedDimension");
            detail = Objects.requireNonNull(detail, "detail");
            scan = Objects.requireNonNull(scan, "scan");
            summary = Objects.requireNonNull(summary, "summary");
            if (worldTick < -1L || revision < 0L) {
                throw new IllegalArgumentException("observation counters cannot be negative");
            }
            boolean available = availability == Availability.AVAILABLE;
            if ((available && (scan.isEmpty() || summary.isEmpty()))
                    || (!available && (scan.isPresent() || summary.isPresent()))) {
                throw new IllegalArgumentException(
                        "scan and summary must be present exactly when available");
            }
            if (available && homeAnchor == null) {
                throw new IllegalArgumentException("available observation requires Home anchor");
            }
        }

        public boolean available() {
            return availability == Availability.AVAILABLE;
        }

        private static Observation unavailable(
                Availability availability,
                String expectedDimension,
                String observedDimension,
                Cell homeAnchor,
                long worldTick,
                long revision,
                String detail) {
            if (availability == Availability.AVAILABLE) {
                throw new IllegalArgumentException("unavailable factory cannot create AVAILABLE");
            }
            return new Observation(
                    availability, expectedDimension, observedDimension, homeAnchor,
                    worldTick, revision, detail, Optional.empty(), Optional.empty());
        }
    }

    /** Small telemetry-ready projection of the full immutable geometry scan. */
    public record Summary(
            HomeInteriorGeometryProbe.Outcome outcome,
            int sampledBlocks,
            int standableCells,
            int coveredCells,
            int exposedCells,
            int unknownCoverCells,
            int interiorRooms,
            int exteriorRegions,
            int unknownRegions,
            int woodenDoors,
            int woodenTrapdoors,
            int woodenFenceGates,
            int roomToRoomPortals,
            int roomToExteriorPortals,
            Optional<RegionKind> homeRegionKind) {
        public Summary {
            outcome = Objects.requireNonNull(outcome, "outcome");
            homeRegionKind = Objects.requireNonNull(homeRegionKind, "homeRegionKind");
        }

        public boolean homeIsProvedInterior() {
            return homeRegionKind.orElse(null) == RegionKind.INTERIOR_ROOM;
        }

        private static Summary from(Scan scan) {
            int covered = countExposure(scan, Exposure.COVERED);
            int exposed = countExposure(scan, Exposure.EXPOSED);
            int unknownCover = countExposure(scan, Exposure.UNKNOWN);
            int rooms = countRegions(scan, RegionKind.INTERIOR_ROOM);
            int exterior = countRegions(scan, RegionKind.EXTERIOR);
            int unknown = countRegions(scan, RegionKind.UNKNOWN);
            int doors = countPortals(scan, PortalKind.WOODEN_DOOR);
            int trapdoors = countPortals(scan, PortalKind.WOODEN_TRAPDOOR);
            int gates = countPortals(scan, PortalKind.WOODEN_FENCE_GATE);
            int roomToRoom = countRelations(scan, PortalRelation.ROOM_TO_ROOM);
            int roomToExterior = countRelations(scan, PortalRelation.ROOM_TO_EXTERIOR);
            Optional<RegionKind> homeKind = scan.homeRegionId().isEmpty()
                    ? Optional.empty()
                    : scan.regions().stream()
                    .filter(region -> region.id() == scan.homeRegionId().orElseThrow())
                    .map(HomeInteriorGeometryProbe.Region::kind)
                    .findFirst();
            return new Summary(
                    scan.outcome(), scan.sampledBlocks(), scan.standableCells().size(),
                    covered, exposed, unknownCover,
                    rooms, exterior, unknown,
                    doors, trapdoors, gates,
                    roomToRoom, roomToExterior, homeKind);
        }

        private static int countExposure(Scan scan, Exposure expected) {
            return (int) scan.exposureByCell().values().stream()
                    .filter(expected::equals)
                    .count();
        }

        private static int countRegions(Scan scan, RegionKind expected) {
            return (int) scan.regions().stream()
                    .filter(region -> region.kind() == expected)
                    .count();
        }

        private static int countPortals(Scan scan, PortalKind expected) {
            return (int) scan.portals().stream()
                    .filter(portal -> portal.kind() == expected)
                    .count();
        }

        private static int countRelations(Scan scan, PortalRelation expected) {
            return (int) scan.portals().stream()
                    .filter(portal -> portal.relation() == expected)
                    .count();
        }
    }
}
