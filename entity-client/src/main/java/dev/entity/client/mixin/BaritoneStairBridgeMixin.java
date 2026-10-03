package dev.entity.client.mixin;

import baritone.api.pathing.movement.ActionCosts;
import dev.entity.client.blueprint.BlueprintDoorMaterials;
import net.minecraft.block.BlockState;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pinned Traverse backplace branch: ordinary supported travel/side attachment already returned. */
@Mixin(targets="baritone.di",remap=false)
public abstract class BaritoneStairBridgeMixin {
    @Inject(method="a(Lbaritone/ca;IIIII)D",at=@At(value="FIELD",
            target="Lnet/minecraft/class_2246;field_10114:Lnet/minecraft/class_2248;",ordinal=2),
            cancellable=true,require=1,allow=1,remap=false)
    private static void entity2$noLowStairBackplace(baritone.ca context,int x,int y,int z,int destX,int destZ,
            CallbackInfoReturnable<Double> callback,@Local(index=9) BlockState source) {
        if(BlueprintDoorMaterials.unsafeStairBackplace(source,destX-x,destZ-z))
            callback.setReturnValue(ActionCosts.COST_INF);
    }
}
