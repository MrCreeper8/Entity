package dev.entity.client.mixin;

import dev.entity.client.EntityClientMod;
import dev.entity.client.control.MinecraftActuatorGateway;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents physical focus/cursor handling from cancelling an Entity-owned exact action. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "isWindowFocused", at = @At("HEAD"), cancellable = true)
    private void entity2$reportBackgroundWindowUnfocused(
            CallbackInfoReturnable<Boolean> callback) {
        if (EntityClientMod.isBackgroundInputIsolationEnabled()) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void entity2$ownPinnedBlockBreaking(boolean physicalAttackHeld, CallbackInfo callback) {
        if (MinecraftActuatorGateway.suppressVanillaBlockBreaking((MinecraftClient) (Object) this)) {
            callback.cancel();
        }
    }

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void entity2$rejectPhysicalAttackDuringPinnedBreak(
            CallbackInfoReturnable<Boolean> callback) {
        if (MinecraftActuatorGateway.suppressVanillaBlockBreaking((MinecraftClient) (Object) this)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void entity2$rejectDuplicatePhysicalUse(CallbackInfo callback) {
        if (MinecraftActuatorGateway.suppressVanillaItemUse((MinecraftClient) (Object) this)) {
            callback.cancel();
        }
    }
}
