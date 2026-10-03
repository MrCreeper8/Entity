package dev.entity.client.autonomy;

import dev.entity.client.autonomy.policy.FieldKitLedger;
import dev.entity.client.autonomy.policy.HomeEconomySession;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoubleBlockProperties;
import net.minecraft.block.enums.ChestType;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** Reads reciprocal vanilla connection state; unrelated adjacent singles are never adopted. */
public final class MinecraftHomeStorageGeometry {
    private MinecraftHomeStorageGeometry() {}

    public static Optional<HomeEconomySession.ContainerIdentity> observe(String storageId, String dimension,
            BlockPos selected, BlockPos anchor, int radius, Function<BlockPos, BlockState> blocks,
            Predicate<BlockPos> loaded, Predicate<BlockPos> allowed, Predicate<BlockPos> openable) {
        if (radius < 1 || !eligible(selected, anchor, radius, loaded, allowed, openable)) return Optional.empty();
        BlockState state = blocks.apply(selected);
        if (!state.isOf(Blocks.CHEST)) return Optional.empty();
        var selectedHalf = half(dimension, selected, state);
        if (state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return Optional.of(new HomeEconomySession.ContainerIdentity(storageId, List.of(selectedHalf)));
        }
        BlockPos connected = selected.offset(ChestBlock.getFacing(state));
        if (!eligible(connected, anchor, radius, loaded, allowed, openable)) return Optional.empty();
        BlockState other = blocks.apply(connected);
        if (!other.isOf(state.getBlock())
                || other.get(ChestBlock.CHEST_TYPE) != state.get(ChestBlock.CHEST_TYPE).getOpposite()
                || other.get(ChestBlock.FACING) != state.get(ChestBlock.FACING)
                || !connected.offset(ChestBlock.getFacing(other)).equals(selected)) return Optional.empty();
        var otherHalf = half(dimension, connected, other);
        // Vanilla ChestBlock.getDoubleBlockType(RIGHT) == FIRST; first owns slots 0..26.
        List<HomeEconomySession.ChestHalf> ordered = ChestBlock.getDoubleBlockType(state) == DoubleBlockProperties.Type.FIRST
                ? List.of(selectedHalf, otherHalf) : List.of(otherHalf, selectedHalf);
        return Optional.of(new HomeEconomySession.ContainerIdentity(storageId, ordered));
    }

    public static boolean sameLayout(HomeEconomySession.ContainerIdentity expected,
                                     HomeEconomySession.ContainerIdentity observed) {
        return expected != null && observed != null && expected.halves().equals(observed.halves());
    }

    public static boolean screenMatches(HomeEconomySession.ContainerIdentity frozen,
            HomeEconomySession.ContainerIdentity observed, int rows) {
        return sameLayout(frozen, observed) && rows * 9 == frozen.slotCount();
    }

    private static boolean eligible(BlockPos position, BlockPos anchor, int radius,
            Predicate<BlockPos> loaded, Predicate<BlockPos> allowed, Predicate<BlockPos> openable) {
        long dx = (long) position.getX() - anchor.getX();
        long dy = (long) position.getY() - anchor.getY();
        long dz = (long) position.getZ() - anchor.getZ();
        return dx * dx + dy * dy + dz * dz <= (long) radius * radius
                && loaded.test(position) && allowed.test(position) && openable.test(position);
    }

    private static HomeEconomySession.ChestHalf half(String dimension, BlockPos position, BlockState state) {
        return new HomeEconomySession.ChestHalf(new FieldKitLedger.Position(dimension,
                position.getX(), position.getY(), position.getZ()), Registries.BLOCK.getId(state.getBlock()).toString(),
                state.get(ChestBlock.FACING).asString(), state.get(ChestBlock.CHEST_TYPE).asString());
    }
}
