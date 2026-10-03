package dev.entity.client.mixin;

import dev.entity.client.blueprint.BlueprintDoorMaterials;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Coerce;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import java.util.List;

/** Pinned 1.15 ProGuard selectors: only approximate material checks, never the placement ray. */
@Mixin(targets = "baritone.dr", remap = false)
public abstract class BaritoneBuilderMaterialsMixin {
    @ModifyExpressionValue(method = "onTick(ZZ)Lbaritone/api/process/PathingCommand;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/class_746;method_18276()Z", ordinal = 0),
            require = 1, allow = 1, remap = false)
    private static boolean entity2$preserveRequiredBreakSneak(boolean original,
            @Local(index = 1) baritone.dr builder,
            @Local(index = 5) BetterBlockPos target,
            @Local(index = 6) baritone.api.utils.Rotation rotation) {
        return BlueprintDoorMaterials.preserveRequiredBreakSneak(original,builder,target,rotation);
    }
    @Redirect(method = "onTick(ZZ)Lbaritone/api/process/PathingCommand;", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/class_2248;method_9605(Lnet/minecraft/class_1750;)Lnet/minecraft/class_2680;",
            ordinal = 0), require = 1, allow = 1, remap = false)
    private static BlockState entity2$actualWallItemPlacement(net.minecraft.block.Block block,
            net.minecraft.item.ItemPlacementContext context, @Local(index = 1) baritone.dr builder) {
        return BlueprintDoorMaterials.placementState(builder,block,context);
    }
    // Pinned ICONST_1 ordinal11 is the placement scan's dy upper bound.
    // Route goals below reachable attachments must have matching native candidates.
    @ModifyConstant(method = "onTick(ZZ)Lbaritone/api/process/PathingCommand;",
            constant = @Constant(intValue = 1, ordinal = 11), require = 1, allow = 1, remap = false)
    private static int entity2$scanReachableRaisedPlacements(int original,
            @Local(index = 1) baritone.dr builder) {
        return BlueprintDoorMaterials.placementScanTop(original,builder);
    }
    // Pinned ICONST_1 ordinal12 is only `dy == 1` in the air-above
    // exclusion, not the loop bound or y+1 world read. ProGuard has already
    // moved this into local1; use typed live locals, never original arguments.
    @ModifyConstant(method = "onTick(ZZ)Lbaritone/api/process/PathingCommand;",
            constant = @Constant(intValue = 1, ordinal = 12), require = 1, allow = 1, remap = false)
    private static int entity2$lowerDoorDoesNotNeedCeiling(int original,
            @Local(index = 1) baritone.dr builder,
            @Local(index = 13) int x, @Local(index = 14) int y, @Local(index = 15) int z,
            @Local(index = 16) BlockState desired) {
        return BlueprintDoorMaterials.ceilingExcludedOffset(original,builder,x,y,z,desired);
    }
    @Inject(method = "onTick(ZZ)Lbaritone/api/process/PathingCommand;", at = @At("RETURN"), require = 1, remap = false)
    private static void entity2$observeNativeDecision(
            CallbackInfoReturnable<baritone.api.process.PathingCommand> callback) {
        BlueprintDoorMaterials.observeNativeDecision(callback.getReturnValue());
    }
    @Shadow(remap = false)
    private static boolean a(BlockState current, BlockState desired) { throw new AssertionError(); }
    @Shadow(remap = false)
    private static boolean a(BlockState current, BlockState desired, boolean itemVerify) { throw new AssertionError(); }

    @Inject(method = "a(Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;Z)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void entity2$neighborConnectionsSettleDuringBuild(BlockState current,BlockState desired,
            boolean itemVerify,CallbackInfoReturnable<Boolean> callback) {
        if(BlueprintDoorMaterials.nativeConnectionMatch(current,desired))callback.setReturnValue(true);
    }

    @Inject(method = "a(I)Ljava/util/List;", at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private void entity2$carriedDoorIsNotTheCurrentFeet(int size, CallbackInfoReturnable<List<BlockState>> callback) {
        callback.setReturnValue(BlueprintDoorMaterials.normalize(this, size, callback.getReturnValue()));
    }

    // Pinned placement-goal consumer. HEAD is intentional: ProGuard reuses its
    // argument local slots before List.add. Retain the two native lower-cell guards.
    @Inject(method = "a(Ljava/util/List;Ljava/util/List;Lbaritone/dr$a;Lbaritone/api/utils/BetterBlockPos;)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void entity2$doorApproach(List<BetterBlockPos> pending, List<Goal> goals,
                                    @Coerce Object context, BetterBlockPos pos, CallbackInfo callback) {
        if (BlueprintDoorMaterials.appendApproach(this,pending,goals,pos)) callback.cancel();
    }

    // Exact pinned native break-goal consumer, not movement cost or break authority.
    @Inject(method = "a(Ljava/util/List;Lbaritone/dr$a;Lbaritone/api/utils/BetterBlockPos;)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void entity2$coveredSoilRemovalApproach(List<Goal> goals,@Coerce Object context,
                                                  BetterBlockPos pos,CallbackInfo callback) {
        if(BlueprintDoorMaterials.appendCoveredSoilRemovalApproach(this,goals,pos))callback.cancel();
    }

    // Actual vendored onTick has six valid() calls. 0..2 inspect world state;
    // 3 verifies the REAL candidate placement; only 4/5 select carried/hotbar items.
    @Redirect(method = "onTick(ZZ)Lbaritone/api/process/PathingCommand;", at = @At(value = "INVOKE",
            target = "Lbaritone/dr;a(Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;Z)Z", ordinal = 4), require = 1, remap = false)
    private boolean entity2$hotbarDoor(BlockState candidate, BlockState desired, boolean itemVerify) {
        return BlueprintDoorMaterials.approximateMatch(candidate, desired) || a(candidate, desired, itemVerify);
    }
    @Redirect(method = "onTick(ZZ)Lbaritone/api/process/PathingCommand;", at = @At(value = "INVOKE",
            target = "Lbaritone/dr;a(Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;Z)Z", ordinal = 5), require = 1, remap = false)
    private boolean entity2$carriedDoor(BlockState candidate, BlockState desired, boolean itemVerify) {
        return BlueprintDoorMaterials.approximateMatch(candidate, desired) || a(candidate, desired, itemVerify);
    }

    // The material-availability loop was inlined into this native assemble lambda.
    @Redirect(method = "a(Lbaritone/dr$a;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/Map;Ljava/util/List;Ljava/util/List;Ljava/util/List;Lbaritone/api/utils/BetterBlockPos;)V",
            at = @At(value = "INVOKE", target = "Lbaritone/dr;a(Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;)Z"), require = 1, remap = false)
    private static boolean entity2$doorMaterial(BlockState candidate, BlockState desired) {
        return BlueprintDoorMaterials.approximateMatch(candidate, desired) || a(candidate, desired);
    }
}
