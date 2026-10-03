package dev.entity.client.mixin;

import baritone.api.utils.input.Input;
import dev.entity.client.baritone.NativeMiningBuoyancy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pinned native handler owns this default; no persisted key or second body frame is installed. */
@Mixin(targets="baritone.fg",remap=false)
public abstract class BaritoneMiningBuoyancyMixin {
    @Inject(method="isInputForcedDown(Lbaritone/api/utils/input/Input;)Z",at=@At("RETURN"),
            cancellable=true,require=2,allow=2,remap=false)
    private void entity2$floatWhileMiningWaitsAtSurface(Input input,CallbackInfoReturnable<Boolean> result) {
        if(input==Input.JUMP&&!result.getReturnValueZ()&&NativeMiningBuoyancy.shouldFloat(this))
            result.setReturnValue(true);
    }
}
