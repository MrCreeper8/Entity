package dev.entity.client.mixin;

import dev.entity.client.blueprint.BlueprintDoorMaterials;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native diagonals model integer feet levels; use cardinal travel across partial roof seams. */
@Mixin(targets="baritone.dd",remap=false)
public abstract class BaritoneBuildDiagonalMixin {
    @Inject(method="a(Lbaritone/ca;IIIIILbaritone/fy;)V",at=@At("HEAD"),
            cancellable=true,require=1,allow=1,remap=false)
    private static void entity2$partialRoofUsesCardinal(baritone.ca context,int x,int y,int z,int destX,int destZ,
            baritone.fy result,CallbackInfo callback) {
        if(BlueprintDoorMaterials.unsafeBuildDiagonal(x,y,z,destX,destZ,
                p->context.a(new baritone.api.utils.BetterBlockPos(p.getX(),p.getY(),p.getZ())))) {
            result.a(); // Native MutableMoveResult.reset(): infinite cost, no fabricated transition.
            callback.cancel();
        }
    }
}
