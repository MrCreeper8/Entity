package dev.entity.client.blueprint;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;

/** Narrow vanilla activity/terrain changes that leave the consented structure intact. */
public final class BlueprintTerrainStates {
    private BlueprintTerrainStates() { }

    public static boolean naturalSoil(BlockState state) {
        return state != null && (state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK));
    }

    public static boolean matches(BlockState wanted, BlockState actual) {
        return wanted != null && actual != null
                && (wanted.equals(actual) || naturalSoil(wanted) && naturalSoil(actual)
                    || sameFurnaceStructure(wanted, actual)
                    || sameGateAxis(wanted, actual) && actual.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,
                            wanted.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING)).equals(wanted));
    }

    /** Smelting changes LIT, not construction. All other state properties remain exact. */
    private static boolean sameFurnaceStructure(BlockState wanted, BlockState actual) {
        return (wanted.isOf(Blocks.FURNACE) || wanted.isOf(Blocks.BLAST_FURNACE) || wanted.isOf(Blocks.SMOKER))
                && actual.getBlock() == wanted.getBlock()
                && actual.with(Properties.LIT, wanted.get(Properties.LIT)).equals(wanted);
    }

    /** Opening a vanilla gate may reverse its facing without changing its geometry. */
    static boolean sameGateAxis(BlockState wanted, BlockState actual) {
        return wanted != null && actual != null && wanted.getBlock() instanceof net.minecraft.block.FenceGateBlock
                && actual.getBlock() == wanted.getBlock()
                && wanted.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING).getAxis()
                    == actual.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING).getAxis();
    }

    /** A missing grass cell uses survival dirt; existing grass is retained by matches(). */
    public static BlockState placementState(BlockState wanted) {
        return naturalSoil(wanted) ? Blocks.DIRT.getDefaultState() : wanted;
    }

    /** Immutable registry projection for native worker costs; never reads ClientWorld. */
    public static java.util.Set<Integer> naturalSoilStateIds() {
        var ids=new java.util.HashSet<Integer>();
        for(var block:java.util.List.of(Blocks.DIRT,Blocks.GRASS_BLOCK))
            for(var state:block.getStateManager().getStates())ids.add(net.minecraft.block.Block.getRawIdFromState(state));
        return java.util.Set.copyOf(ids);
    }
}
