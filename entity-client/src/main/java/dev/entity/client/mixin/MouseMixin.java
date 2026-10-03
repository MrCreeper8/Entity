package dev.entity.client.mixin;

import dev.entity.client.EntityClientMod;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a background bot window from reacquiring the owner's real cursor. */
@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Inject(method = "lockCursor", at = @At("HEAD"), cancellable = true)
    private void entity2$rejectBackgroundCursorLock(CallbackInfo callback) {
        if (EntityClientMod.isBackgroundInputIsolationEnabled()) {
            callback.cancel();
        }
    }
}
