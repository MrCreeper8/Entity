package dev.entity.client.mixin;

import baritone.api.pathing.movement.ActionCosts;
import dev.entity.client.baritone.NativePropertyCosts;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pinned Baritone 1.15 API distribution: ca is CalculationContext; dr$a is the
 * builder's context, which overrides both costs without calling super. ProGuard
 * removed isPossiblyProtected, so inject both exact classes' surviving callers.
 * These are not passability checks: ordinary traversal through property stays legal.
 */
@Mixin(targets = {"baritone.ca", "baritone.dr$a"}, remap = false)
public abstract class BaritonePropertyCostMixin {
    @Inject(method = "a(IIILnet/minecraft/class_2680;)D", at = @At("HEAD"),
            cancellable = true, require = 1, remap = false)
    private void entity2$protectedPlacementCost(int x, int y, int z, BlockState current,
                                               CallbackInfoReturnable<Double> callback) {
        if (NativePropertyCosts.forbiddenPlacement(x, y, z,
                Block.getRawIdFromState(current), current.isReplaceable())) {
            callback.setReturnValue(ActionCosts.COST_INF);
        }
    }

    @Inject(method = "b(IIILnet/minecraft/class_2680;)D", at = @At("HEAD"),
            cancellable = true, require = 1, remap = false)
    private void entity2$protectedBreakCost(int x, int y, int z, BlockState current,
                                           CallbackInfoReturnable<Double> callback) {
        if (NativePropertyCosts.forbiddenBreak(x, y, z,
                Block.getRawIdFromState(current), current.hasBlockEntity())) {
            callback.setReturnValue(ActionCosts.COST_INF);
        }
    }
}
