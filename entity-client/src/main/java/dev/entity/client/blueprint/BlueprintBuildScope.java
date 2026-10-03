package dev.entity.client.blueprint;

import dev.entity.core.stewardship.ProtectedAreaPolicy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Capability held only by the live leased native builder, never a gathering child. */
public final class BlueprintBuildScope implements dev.entity.client.protection.ProtectedAreaClientState.BlueprintAuthority {
    private final BlueprintProject project;
    private final Map<BlockPos,BlueprintProject.Cell> cells;
    private final BooleanSupplier valid;
    private final BlueprintSupportLedger supports;
    public BlueprintBuildScope(BlueprintProject project, BooleanSupplier valid) {
        this(project,valid,BlueprintSupportLedger.memory(project.worldId()));
    }
    public BlueprintBuildScope(BlueprintProject project, BooleanSupplier valid,BlueprintSupportLedger supports) {
        this.project=project; this.valid=valid;
        this.supports=supports;
        Map<BlockPos,BlueprintProject.Cell> copy=new HashMap<>();
        project.cells().forEach(c->copy.put(c.pos(),c)); this.cells=Map.copyOf(copy);
    }
    public boolean includes(ProtectedAreaPolicy.Action action,String dimension,BlockPos pos) {
        return (action==ProtectedAreaPolicy.Action.BREAK || action==ProtectedAreaPolicy.Action.PLACE)
                && valid.getAsBoolean() && project.dimension().equals(dimension) && cells.containsKey(pos);
    }
    /** Native construction owns main-hand placement, not vanilla's empty offhand fallback. */
    public boolean suppressEmptyOffhandProbe(net.minecraft.util.Hand hand,ItemStack stack) {
        return valid.getAsBoolean() && hand==net.minecraft.util.Hand.OFF_HAND && stack.isEmpty();
    }
    @Override
    public boolean includes(ProtectedAreaPolicy.Action action,String dimension,int x,int y,int z) {
        return includes(action,dimension,new BlockPos(x,y,z));
    }
    /** Permanent requested construction is not a temporary traversal support. */
    public boolean ownsPlacedBlock(String dimension,BlockPos pos,net.minecraft.block.BlockState observed) {
        return observed!=null && !observed.isAir()
                && includes(ProtectedAreaPolicy.Action.PLACE,dimension,pos)
                && (BlueprintTerrainStates.matches(BlueprintDesign.parseState(cells.get(pos).state()),observed)
                    ||BlueprintSupportLedger.eligible(cells.get(pos))
                        &&supports.owns(project,pos,BlueprintDesign.stateString(observed)));
    }
    public boolean breakAllowed(MinecraftClient client,BlockPos pos) {
        if (client.world==null || !includes(ProtectedAreaPolicy.Action.BREAK,
                client.world.getRegistryKey().getValue().toString(),pos)) return false;
        BlueprintProject.Cell cell=cells.get(pos);
        String now=BlueprintDesign.stateString(client.world.getBlockState(pos));
        if(BlueprintSupportLedger.eligible(cell)&&supports.owns(project,pos,now))return true;
        var observed=client.world.getBlockState(pos);
        if(observed.getBlock() instanceof net.minecraft.block.BedBlock)
            return !BlueprintTerrainStates.matches(BlueprintDesign.parseState(cell.state()),observed)
                    &&BlueprintProjects.repairableBed(pos,observed,cells.keySet(),client.world::getBlockState);
        return reviewedBreakAllowed(cell,client.world.getBlockState(pos));
    }
    static boolean reviewedBreakAllowed(BlueprintProject.Cell cell,net.minecraft.block.BlockState observed) {
        return !BlueprintTerrainStates.matches(BlueprintDesign.parseState(cell.state()),observed)
                && !observed.isAir() && BlueprintProjects.repairable(observed);
    }
    public boolean placeAllowed(MinecraftClient client,BlockPos pos,ItemStack stack,ItemPlacementContext context) {
        if (client.world==null || stack==null || !(stack.getItem() instanceof BlockItem)
                || !includes(ProtectedAreaPolicy.Action.PLACE,
                    client.world.getRegistryKey().getValue().toString(),pos)) return false;
        BlueprintProject.Cell cell=cells.get(pos);
        var now=client.world.getBlockState(pos);
        if(BlueprintSupportLedger.eligible(cell)) {
            var predicted=BlueprintDoorMaterials.itemPlacementState(((BlockItem)stack.getItem()).getBlock(),context);
            String state=predicted==null?"":BlueprintDesign.stateString(predicted);
            return now.isAir()&&BlueprintSupportLedger.material(state)
                    &&supports.admit(project,cell,state,System.currentTimeMillis());
        }
        var predicted=BlueprintDoorMaterials.itemPlacementState(((BlockItem)stack.getItem()).getBlock(),context);
        return permanentPlacementAllowed(cell,now,stack.getItem(),predicted);
    }
    static boolean permanentPlacementAllowed(BlueprintProject.Cell cell,net.minecraft.block.BlockState now,
            net.minecraft.item.Item item,net.minecraft.block.BlockState predicted) {
        var desired=BlueprintDesign.parseState(cell.state());
        boolean naturalSoil=BlueprintTerrainStates.naturalSoil(desired)&&BlueprintTerrainStates.naturalSoil(predicted)
                &&predicted.getBlock().asItem()==item;
        return (naturalSoil || desired.getBlock().asItem()==item
                    && BlueprintDoorMaterials.actualPlacementMatches(desired,predicted))
                && !now.hasBlockEntity() && !now.isOf(net.minecraft.block.Blocks.VOID_AIR)
                && (now.isReplaceable() || BlueprintTerrainStates.matches(BlueprintDesign.parseState(cell.before()),now));
    }
}
