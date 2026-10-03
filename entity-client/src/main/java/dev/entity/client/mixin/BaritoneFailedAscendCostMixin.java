package dev.entity.client.mixin;

import baritone.api.pathing.movement.ActionCosts;
import dev.entity.client.baritone.NativeFailedAscendEdges;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pinned MovementAscend cost: all four cardinal A* moves and instance recosts use this seam. */
@Mixin(targets = "baritone.db", remap = false)
public abstract class BaritoneFailedAscendCostMixin {
    @Inject(method = "a(Lbaritone/ca;IIIII)D", at = @At("HEAD"),
            cancellable = true, require = 1, allow = 1, remap = false)
    private static void entity2$observedFailedAscent(baritone.ca context, int x, int y, int z,
            int destinationX, int destinationZ, CallbackInfoReturnable<Double> callback) {
        Object nativeOwner = ((BaritoneCalculationOwnerAccessor) context).entity2$calculationOwner();
        if (nativeOwner instanceof NativeFailedAscendEdges.Owner owner
                && owner.entity2$failedAscendEdges().forbidden(nativeOwner, x, y, z,
                        destinationX, destinationZ, System.currentTimeMillis())) {
            callback.setReturnValue(ActionCosts.COST_INF);
        }
    }
}
