package dev.entity.client.autonomy;

import dev.entity.client.autonomy.policy.HomeEconomySession.HomeAnchor;
import dev.entity.client.autonomy.policy.TidySettingsStore.DisposalSpot;
import dev.entity.client.protection.ProtectedAreaClientState;
import dev.entity.core.stewardship.ProtectedAreaPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** Bounded geometry for one ordinary item throw; no movement/camera owner or terrain edits. */
final class MinecraftTidyDropGeometry {
    static final int HOME_DISTANCE = 16;
    static final double MINIMUM_LANDING_DISTANCE = 1.8;
    private static final double MINIMUM_AIM_DISTANCE = 2.2;
    private static final double MAXIMUM_AIM_DISTANCE = 4.2;
    record Aim(float yaw, float pitch) { }
    record AimSearch(Optional<Aim> aim, String rejection) { }
    record StanceSearch(List<BlockPos> stands, String rejection) { }
    private final MinecraftClient client;

    MinecraftTidyDropGeometry(MinecraftClient client) { this.client = client; }

    /** Same validated throw envelope, with no Home assignment or standing/movement goal. */
    Optional<Aim> capacityAim(String worldId, ProtectedAreaClientState policy) {
        if (client.player == null || client.world == null) return Optional.empty();
        String dimension = client.world.getRegistryKey().getValue().toString();
        BlockPos feet = client.player.getBlockPos();
        HomeAnchor geometryOrigin = HomeAnchor.at(dimension, feet.getX(), feet.getY(), feet.getZ());
        for (int dy : new int[]{-1, 0, -2}) {
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                double distance = Math.hypot(dx, dz);
                if (distance < 2.2 || distance > 4.2) continue;
                BlockPos at = feet.add(dx, dy, dz);
                if (!loaded(at) || !allowed(at, dimension, policy)) continue;
                BlockState state = client.world.getBlockState(at);
                if (!state.getFluidState().isEmpty() || !state.isFullCube(client.world, at)) continue;
                DisposalSpot spot = new DisposalSpot(worldId, geometryOrigin.fingerprint(), dimension,
                        at.getX(), at.getY(), at.getZ(), Registries.BLOCK.getId(state.getBlock()).toString(),
                        BlockArgumentParser.stringifyBlockState(state), at.getX() + .5, at.getY() + 1, at.getZ() + .5);
                Optional<Aim> aim = aimAtCurrentPosition(spot, geometryOrigin, policy);
                if (aim.isPresent()) return aim;
            }
        }
        return Optional.empty();
    }

    void validateSpot(DisposalSpot spot, HomeAnchor home, ProtectedAreaClientState policy) {
        if (client.player == null || client.world == null || home == null
                || !home.dimension().equals(spot.dimension())
                || !client.world.getRegistryKey().getValue().toString().equals(spot.dimension())) {
            throw new IllegalArgumentException("Disposal spot must be in Entity's current Home dimension");
        }
        BlockPos block = new BlockPos(spot.x(), spot.y(), spot.z());
        if (block.getSquaredDistance(new BlockPos(home.x(), home.y(), home.z())) > HOME_DISTANCE * HOME_DISTANCE) {
            throw new IllegalArgumentException("Select a disposal spot within " + HOME_DISTANCE + " blocks of Home");
        }
        if (!loaded(block) || !allowed(block, spot.dimension(), policy)) {
            throw new IllegalArgumentException("Disposal spot is unloaded or inside strict player property");
        }
        BlockState current = client.world.getBlockState(block);
        if (!Registries.BLOCK.getId(current.getBlock()).toString().equals(spot.blockId())
                || !canonicalState(BlockArgumentParser.stringifyBlockState(current)).equals(canonicalState(spot.blockState()))) {
            throw new IllegalArgumentException("The selected disposal block/state changed; select it again");
        }
        if (Math.abs(spot.landingX() - (spot.x() + .5)) > .001
                || Math.abs(spot.landingZ() - (spot.z() + .5)) > .001
                || spot.landingY() < spot.y() || spot.landingY() > spot.y() + 1.501) {
            throw new IllegalArgumentException("Disposal landing does not match the selected block");
        }
        if (!current.getFluidState().isEmpty() && !current.isOf(Blocks.WATER)) {
            throw new IllegalArgumentException("Use a stable solid surface or ordinary water, not a moving/hazardous fluid column");
        }
    }

    List<BlockPos> stands(DisposalSpot spot, HomeAnchor home, ProtectedAreaClientState policy) {
        return search(spot, home, policy).stands();
    }

    StanceSearch search(DisposalSpot spot, HomeAnchor home, ProtectedAreaClientState policy) {
        validateSpot(spot, home, policy);
        return search(spot, home, feet -> safeStand(feet, spot.dimension(), policy),
                feet -> findAim(feet, feet.y + 1.32, spot, points -> trajectoryRejection(points, spot, policy)));
    }

    /** Enumerate the existing throw envelope, rather than a narrower unrelated standing ring. */
    static List<BlockPos> candidateFeet(DisposalSpot spot) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            double distance = Math.hypot(dx, dz);
            if (distance < MINIMUM_AIM_DISTANCE || distance > MAXIMUM_AIM_DISTANCE) continue;
            for (int y = (int) Math.floor(spot.landingY()) - 1; y <= (int) Math.ceil(spot.landingY()) + 1; y++) {
                candidates.add(new BlockPos(spot.x() + dx, y, spot.z() + dz));
            }
        }
        return List.copyOf(candidates);
    }

    static StanceSearch search(DisposalSpot spot, HomeAnchor home, Predicate<BlockPos> safeStand,
                               Function<Vec3d, AimSearch> aim) {
        List<BlockPos> candidates = new ArrayList<>();
        int supported = 0;
        String rejection = "No dry, loaded, unprotected standing cell with solid support and body clearance within 2.2-4.2 blocks of the landing; select beside an open bank or level ground";
        for (BlockPos feet : candidateFeet(spot)) {
            if (!safeStand.test(feet)) continue;
            supported++;
            AimSearch result = aim.apply(Vec3d.ofBottomCenter(feet));
            if (result.aim().isPresent()) candidates.add(feet);
            else rejection = result.rejection();
        }
        List<BlockPos> ordered = candidates.stream().sorted(Comparator.comparingDouble(pos -> pos.getSquaredDistance(
                new BlockPos(home.x(), home.y(), home.z())))).toList();
        return new StanceSearch(ordered, ordered.isEmpty() ? (supported == 0 ? rejection : "Standing ground exists, but " + rejection) : "");
    }

    Optional<Aim> aimAtCurrentPosition(DisposalSpot spot, HomeAnchor home, ProtectedAreaClientState policy) {
        validateSpot(spot, home, policy);
        if (!client.player.isOnGround() || client.player.isTouchingWater()
                || client.player.getVelocity().horizontalLengthSquared() > .0025) return Optional.empty();
        return aim(client.player.getPos(), client.player.getEyeY() - .3, spot, policy);
    }

    private Optional<Aim> aim(Vec3d feet, double spawnY, DisposalSpot spot, ProtectedAreaClientState policy) {
        return findAim(feet, spawnY, spot, points -> trajectoryRejection(points, spot, policy)).aim();
    }

    static AimSearch findAim(Vec3d feet, double spawnY, DisposalSpot spot, Function<List<Vec3d>, String> trajectoryRejection) {
        double dx = spot.landingX() - feet.x, dz = spot.landingZ() - feet.z;
        double distance = Math.hypot(dx, dz);
        if (distance < MINIMUM_AIM_DISTANCE || distance > MAXIMUM_AIM_DISTANCE) {
            return new AimSearch(Optional.empty(), "the landing is outside the 2.2-4.2 block throw envelope");
        }
        float yaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
        String rejection = "ordinary throw spread cannot reach this height while landing beyond pickup reach; select a closer-height surface";
        // Preserve low-arc preference, then cover vanilla pitches at the same five-degree
        // resolution. One-block uphill banks need arcs omitted by the former short list.
        for (float pitch : pitchCandidates()) {
            boolean safe = true;
            // LivingEntity#createItemEntity: horizontal spread <=.02, vertical spread <=.1.
            // Test the envelope, not only the favorable mean throw.
            for (double horizontal : new double[] {-.02, 0, .02})
                    for (double lateral : new double[] {-.02, 0, .02}) for (double vertical : new double[] {-.1, .1}) {
                List<Vec3d> trajectory = trajectory(new Vec3d(feet.x, spawnY, feet.z), yaw, pitch,
                        horizontal, lateral, vertical, spot.landingY() + .05);
                if (trajectory.isEmpty()) { safe = false; break; }
                Vec3d end = trajectory.get(trajectory.size() - 1);
                if (Math.hypot(end.x - feet.x, end.z - feet.z) < MINIMUM_LANDING_DISTANCE
                        || Math.hypot(end.x - spot.landingX(), end.z - spot.landingZ()) > 1.25) {
                    safe = false; break;
                }
                String blocked = trajectoryRejection.apply(trajectory);
                if (!blocked.isEmpty()) { rejection = blocked; safe = false; break; }
            }
            if (safe) return new AimSearch(Optional.of(new Aim(yaw, pitch)), "");
        }
        return new AimSearch(Optional.empty(), rejection);
    }

    private static List<Float> pitchCandidates() {
        List<Float> pitches = new ArrayList<>(List.of(0f, 5f, -5f, 10f, -10f, 15f, -15f, 20f));
        for (int pitch = -90; pitch <= 90; pitch += 5) if (!pitches.contains((float) pitch)) pitches.add((float) pitch);
        return pitches;
    }

    private String trajectoryRejection(List<Vec3d> trajectory, DisposalSpot spot, ProtectedAreaClientState policy) {
        for (int index = 0; index < trajectory.size(); index++) {
            Vec3d point = trajectory.get(index);
            BlockPos at = BlockPos.ofFloored(point);
            if (!loaded(at)) return "the throw arc crosses unloaded blocks; move close enough to load the landing and bank";
            if (!allowed(at, spot.dimension(), policy)) return "the throw arc or landing crosses strict player property; choose a clear unprotected side";
            if (index < trajectory.size() - 1 && !client.world.getFluidState(at).isEmpty()
                    && !(at.getX() == spot.x() && at.getZ() == spot.z())) {
                return "the throw arc enters fluid before the selected landing; choose the near edge of the bank";
            }
            if (index < trajectory.size() - 1 && !client.world.isSpaceEmpty(new Box(point.x - .14, point.y, point.z - .14,
                    point.x + .14, point.y + .25, point.z + .14))) {
                return "the throw arc is obstructed by terrain; choose a landing with open space above and beside it";
            }
        }
        return "";
    }

    /** Vanilla airborne item gravity/drag, bounded before the selected landing height. */
    static List<Vec3d> trajectory(Vec3d start, float yaw, float pitch, double spread, double verticalSpread, double landingY) {
        return trajectory(start, yaw, pitch, spread, 0, verticalSpread, landingY);
    }

    static List<Vec3d> trajectory(Vec3d start, float yaw, float pitch, double spread, double lateralSpread,
                                  double verticalSpread, double landingY) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double vx = -Math.sin(y) * (.3 * Math.cos(p) + spread) + Math.cos(y) * lateralSpread;
        double vz = Math.cos(y) * (.3 * Math.cos(p) + spread) + Math.sin(y) * lateralSpread;
        double vy = -.3 * Math.sin(p) + .1 + verticalSpread;
        List<Vec3d> points = new ArrayList<>();
        Vec3d at = start;
        points.add(at);
        for (int tick = 0; tick < 40; tick++) {
            vy -= .04;
            Vec3d next = at.add(vx, vy, vz);
            if (next.y <= landingY && vy < 0) {
                double fraction = (at.y - landingY) / (at.y - next.y);
                if (fraction < 0 || fraction > 1) return List.of();
                points.add(at.lerp(next, fraction));
                return List.copyOf(points);
            }
            points.add(next);
            at = next;
            vx *= .98; vy *= .98; vz *= .98;
        }
        return List.of();
    }

    private boolean safeStand(BlockPos feet, String dimension, ProtectedAreaClientState policy) {
        if (!loaded(feet) || !loaded(feet.up()) || !allowed(feet, dimension, policy)) return false;
        BlockState support = client.world.getBlockState(feet.down());
        if (!support.isSideSolidFullSquare(client.world, feet.down(), Direction.UP)
                || support.isOf(Blocks.MAGMA_BLOCK) || support.isOf(Blocks.CACTUS)
                || !client.world.getFluidState(feet).isEmpty() || !client.world.getFluidState(feet.up()).isEmpty()) return false;
        return client.world.isSpaceEmpty(new Box(feet.getX() + .2, feet.getY(), feet.getZ() + .2,
                feet.getX() + .8, feet.getY() + 1.8, feet.getZ() + .8));
    }

    private boolean loaded(BlockPos pos) {
        return client.world != null && client.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static boolean allowed(BlockPos pos, String dimension, ProtectedAreaClientState policy) {
        if (policy == null) return false;
        var regions = policy.observePropertyRegions();
        return regions.available() && regions.areas().stream().noneMatch(area ->
                area.kind() == ProtectedAreaPolicy.AreaKind.PROTECTED && area.contains(dimension, pos.getX(), pos.getZ()));
    }

    static String canonicalState(String state) {
        int bracket = state.indexOf('[');
        if (bracket < 0) return state;
        if (!state.endsWith("]")) throw new IllegalArgumentException("invalid block state");
        String[] properties = state.substring(bracket + 1, state.length() - 1).split(",");
        Arrays.sort(properties);
        return state.substring(0, bracket) + '[' + String.join(",", properties) + ']';
    }
}
