package dev.entity.client.baritone;

import dev.entity.client.protection.ProtectedAreaClientState;
import dev.entity.core.stewardship.LivestockStewardshipPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Loaded-client facts for the pure livestock hunt policy. */
final class MinecraftLivestockObserver {
    static final int LOCAL_COHORT_RADIUS_BLOCKS = 32;
    static final int CONTAINMENT_RADIUS_BLOCKS = 12;
    static final int CONTAINMENT_VERTICAL_BLOCKS = 4;
    static final int MAX_CONTAINMENT_CELLS = 4_096;

    private static final List<Direction> HORIZONTAL_DIRECTIONS = List.of(
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);

    private final MinecraftClient client;
    private final ProtectedAreaClientState protectedAreas;
    private final Logger logger;
    private final LinkedHashMap<String, LivestockStewardshipPolicy.Code> loggedDecisions =
            new LinkedHashMap<>();

    MinecraftLivestockObserver(
            MinecraftClient client,
            ProtectedAreaClientState protectedAreas,
            Logger logger) {
        this.client = Objects.requireNonNull(client, "client");
        this.protectedAreas = Objects.requireNonNull(protectedAreas, "protectedAreas");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    LivestockStewardshipPolicy.Decision decide(Entity target) {
        LivestockStewardshipPolicy.Decision decision = decideObserved(target);
        recordDecision(target, decision);
        return decision;
    }

    private LivestockStewardshipPolicy.Decision decideObserved(Entity target) {
        if (!(target instanceof PassiveEntity passive)
                || client.world == null
                || !target.isAlive()
                || target.isRemoved()) {
            return LivestockStewardshipPolicy.Decision.deny(
                    LivestockStewardshipPolicy.Code.OBSERVATION_UNAVAILABLE,
                    "target is not a live loaded passive animal");
        }
        BlockPos feet = BlockPos.ofFloored(
                target.getX(), target.getY(), target.getZ());
        if (!client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) {
            return LivestockStewardshipPolicy.Decision.deny(
                    LivestockStewardshipPolicy.Code.OBSERVATION_UNAVAILABLE,
                    "target chunk is not loaded");
        }

        String dimension = client.world.getRegistryKey().getValue().toString();
        ProtectedAreaClientState.PropertyObservation property =
                protectedAreas.observeProperty(
                        dimension, feet.getX(), feet.getZ());
        int adults = loadedLocalAdults(target);
        boolean tamed = target instanceof TameableEntity tameable && tameable.isTamed();
        boolean leashed = target instanceof Leashable leashable && leashable.isLeashed();
        LivestockStewardshipPolicy.Facts preliminary = facts(
                property.available(),
                property.protectedProperty(),
                false,
                tamed,
                target.hasCustomName(),
                leashed,
                passive.isBaby(),
                adults);
        LivestockStewardshipPolicy.Decision cheap =
                LivestockStewardshipPolicy.decide(preliminary);
        if (!cheap.allowed()) return cheap;

        Containment containment = observeContainment(feet);
        return LivestockStewardshipPolicy.decide(facts(
                containment.complete(),
                false,
                containment.contained(),
                false,
                false,
                false,
                false,
                adults));
    }

    private void recordDecision(
            Entity target,
            LivestockStewardshipPolicy.Decision decision) {
        String targetId = target == null ? "unknown" : target.getUuidAsString();
        LivestockStewardshipPolicy.Code prior = loggedDecisions.put(
                targetId, decision.code());
        if (prior == decision.code()) return;
        while (loggedDecisions.size() > 128) {
            String oldest = loggedDecisions.keySet().iterator().next();
            loggedDecisions.remove(oldest);
        }
        String type = target == null
                ? "unknown"
                : net.minecraft.entity.EntityType.getId(target.getType()).toString();
        logger.info(
                "Livestock hunt admission target={} type={} allowed={} code={} detail={}",
                targetId, type, decision.allowed(), decision.code(), decision.detail());
    }

    private static LivestockStewardshipPolicy.Facts facts(
            boolean complete,
            boolean property,
            boolean contained,
            boolean tamed,
            boolean named,
            boolean leashed,
            boolean baby,
            int adults) {
        return new LivestockStewardshipPolicy.Facts(
                complete, property, contained,
                tamed, named, leashed, baby, adults);
    }

    private int loadedLocalAdults(Entity target) {
        if (client.world == null) return 0;
        double radiusSquared = LOCAL_COHORT_RADIUS_BLOCKS
                * (double) LOCAL_COHORT_RADIUS_BLOCKS;
        int adults = 0;
        for (Entity peer : client.world.getEntities()) {
            if (peer.getType() != target.getType()
                    || !peer.isAlive()
                    || peer.isRemoved()
                    || !(peer instanceof PassiveEntity passive)
                    || passive.isBaby()
                    || target.squaredDistanceTo(peer) > radiusSquared) {
                continue;
            }
            adults++;
        }
        return adults;
    }

    private Containment observeContainment(BlockPos approximateFeet) {
        if (client.world == null) return Containment.unavailable();
        BlockPos start = passableNear(approximateFeet);
        if (start == null) return Containment.unavailable();

        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        frontier.add(start);
        visited.add(start.asLong());
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.removeFirst();
            int dx = Math.abs(current.getX() - start.getX());
            int dy = Math.abs(current.getY() - start.getY());
            int dz = Math.abs(current.getZ() - start.getZ());
            if (dx >= CONTAINMENT_RADIUS_BLOCKS
                    || dz >= CONTAINMENT_RADIUS_BLOCKS
                    || dy >= CONTAINMENT_VERTICAL_BLOCKS) {
                return Containment.open();
            }
            if (visited.size() >= MAX_CONTAINMENT_CELLS) {
                return Containment.unavailable();
            }
            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                BlockPos column = current.offset(direction);
                if (!client.world.isChunkLoaded(
                        column.getX() >> 4, column.getZ() >> 4)) {
                    return Containment.unavailable();
                }
                BlockPos next = passableTransition(column, current.getY());
                if (next != null && visited.add(next.asLong())) frontier.addLast(next);
            }
        }
        return Containment.closed();
    }

    private BlockPos passableNear(BlockPos position) {
        for (int offset : new int[]{0, 1, -1}) {
            BlockPos candidate = position.up(offset);
            if (passable(candidate)) return candidate;
        }
        return null;
    }

    private BlockPos passableTransition(BlockPos horizontal, int currentY) {
        for (int offset : new int[]{0, 1, -1}) {
            BlockPos candidate = new BlockPos(
                    horizontal.getX(), currentY + offset, horizontal.getZ());
            if (passable(candidate)) return candidate;
        }
        return null;
    }

    private boolean passable(BlockPos feet) {
        if (client.world == null
                || !client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) {
            return false;
        }
        BlockState feetState = client.world.getBlockState(feet);
        BlockState headState = client.world.getBlockState(feet.up());
        BlockState floorState = client.world.getBlockState(feet.down());
        return !ownerBoundary(feetState)
                && !ownerBoundary(headState)
                && !ownerBoundary(floorState)
                && feetState.getCollisionShape(client.world, feet).isEmpty()
                && headState.getCollisionShape(client.world, feet.up()).isEmpty()
                && !floorState.getCollisionShape(client.world, feet.down()).isEmpty();
    }

    private static boolean ownerBoundary(BlockState state) {
        return state.getBlock() instanceof FenceBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof WallBlock;
    }

    private record Containment(boolean complete, boolean contained) {
        private static Containment unavailable() {
            return new Containment(false, true);
        }

        private static Containment open() {
            return new Containment(true, false);
        }

        private static Containment closed() {
            return new Containment(true, true);
        }
    }
}
