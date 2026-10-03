package dev.entity.client.mixin;

import dev.entity.client.control.MinecraftMovementGateway;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears logical input ownership before a client connection is discarded. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientLifecycleMixin {
    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void entity2$neutralizeMovementOnDisconnect(CallbackInfo callback) {
        MinecraftMovementGateway.onDisconnected((MinecraftClient) (Object) this);
    }
}
