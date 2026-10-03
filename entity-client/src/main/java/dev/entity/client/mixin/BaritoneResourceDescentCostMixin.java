package dev.entity.client.mixin;

import dev.entity.client.baritone.ResourceDescentSafetyPolicy;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pinned1.15 dynamicFallCost: do not plan excavation that the final stair guard rejects. */
@Mixin(targets = "baritone.dc", remap = false)
public abstract class BaritoneResourceDescentCostMixin {
    @Inject(method = "a(Lbaritone/ca;IIIDLnet/minecraft/class_2680;Lbaritone/fy;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1, remap = false)
    private static void entity2$resourceFallExcavation(baritone.ca context, int sourceY,
            int destinationX, int destinationZ, double frontMiningCost, BlockState below,
            baritone.fy result, CallbackInfoReturnable<Boolean> callback) {
        Object owner = ((BaritoneCalculationOwnerAccessor) context).entity2$calculationOwner();
        if (ResourceDescentSafetyPolicy.rejectsNativeFallExcavation(owner, frontMiningCost)) {
            result.a(); // Native infinite-cost result, not a fabricated alternative movement.
            callback.setReturnValue(false);
        }
    }
}
