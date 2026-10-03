package dev.entity.client.protection;

import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.Cell;
import dev.entity.client.autonomy.policy.HomeInteriorGeometryProbe.PortalKind;
import dev.entity.client.autonomy.policy.HomeThreatShelterPolicy;
import dev.entity.client.autonomy.policy.MinecraftHomeInteriorObserver;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Loaded-client adapter for the pure observed-Home shelter policy. */
public final class MinecraftHomeThreatShelterBoundary {
    private static final double RELEVANT_HOSTILE_RADIUS_SQUARED = 32.0 * 32.0;
    private static final int MAXIMUM_RELEVANT_HOSTILES = 32;

    private final MinecraftClient client;
    private final Supplier<MinecraftHomeInteriorObserver.Observation> interiorSupplier;

    public MinecraftHomeThreatShelterBoundary(
            MinecraftClient client,
            Supplier<MinecraftHomeInteriorObserver.Observation> interiorSupplier) {
        this.client = Objects.requireNonNull(client, "client");
        this.interiorSupplier = Objects.requireNonNull(interiorSupplier, "interiorSupplier");
    }

    /**
     * Returns empty unless the selected target is a real loaded hostile and the
     * exact bounded Home topology is available in the current client world.
     */
    public Optional<HomeThreatShelterPolicy.Decision> decide(Entity primaryThreat) {
        if (primaryThreat == null
                || !(primaryThreat instanceof HostileEntity)
                || !primaryThreat.isAlive()
                || primaryThreat.isRemoved()
                || client.player == null
                || client.world == null) {
            return Optional.empty();
        }
        MinecraftHomeInteriorObserver.Observation interior = interiorSupplier.get();
        if (interior == null || !interior.available() || interior.scan().isEmpty()) {
            return Optional.empty();
        }

        var scan = interior.scan().orElseThrow();
        Set<Cell> closedDoors = new LinkedHashSet<>();
        scan.portals().stream()
                .filter(portal -> portal.kind() == PortalKind.WOODEN_DOOR)
                .map(portal -> portal.portalCell())
                .filter(this::isLoadedClosedWoodenDoor)
                .forEach(closedDoors::add);

        List<HomeThreatShelterPolicy.Threat> threats = relevantHostiles(primaryThreat);
        HomeThreatShelterPolicy.Observation facts = new HomeThreatShelterPolicy.Observation(
                true,
                scan,
                client.player.getX(), client.player.getY(), client.player.getZ(),
                primaryThreat.getUuidAsString(),
                threats,
                Set.copyOf(closedDoors));
        return Optional.of(HomeThreatShelterPolicy.decide(facts));
    }

    private List<HomeThreatShelterPolicy.Threat> relevantHostiles(Entity primaryThreat) {
        ArrayList<Entity> relevant = new ArrayList<>();
        relevant.add(primaryThreat);
        for (Entity entity : client.world.getEntities()) {
            if (entity == primaryThreat
                    || !(entity instanceof HostileEntity)
                    || !entity.isAlive()
                    || entity.isRemoved()) {
                continue;
            }
            if (entity.squaredDistanceTo(client.player)
                    <= RELEVANT_HOSTILE_RADIUS_SQUARED) {
                relevant.add(entity);
            }
        }
        relevant.sort(Comparator.comparing(Entity::getUuidAsString));
        if (relevant.size() > MAXIMUM_RELEVANT_HOSTILES) {
            relevant.subList(MAXIMUM_RELEVANT_HOSTILES, relevant.size()).clear();
            if (relevant.stream().noneMatch(entity -> entity == primaryThreat)) {
                relevant.set(relevant.size() - 1, primaryThreat);
                relevant.sort(Comparator.comparing(Entity::getUuidAsString));
            }
        }
        return relevant.stream()
                .map(entity -> new HomeThreatShelterPolicy.Threat(
                        entity.getUuidAsString(),
                        entity.getX(), entity.getY(), entity.getZ(),
                        entity.isAlive() && !entity.isRemoved()))
                .toList();
    }

    private boolean isLoadedClosedWoodenDoor(Cell cell) {
        BlockPos position = new BlockPos(cell.x(), cell.y(), cell.z());
        if (!client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) return false;
        BlockState state = client.world.getBlockState(position);
        return state.getBlock() instanceof DoorBlock
                && state.isIn(BlockTags.WOODEN_DOORS)
                && DoorBlock.canOpenByHand(state)
                && state.contains(DoorBlock.HALF)
                && state.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && state.contains(Properties.OPEN)
                && !state.get(Properties.OPEN);
    }
}
