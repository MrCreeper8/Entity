package dev.entity.client.mixin;

import dev.entity.client.EntityClientMod;
import dev.entity.client.runtime.GlfwWindowBridge;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents the GLFW window from ever becoming visible in background mode. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwDefaultWindowHints()V",
                    shift = At.Shift.AFTER))
    private void entity2$selectWindowVisibility(CallbackInfo callback) {
        if (EntityClientMod.isBackgroundWindowEnabled()) {
            GlfwWindowBridge.applyHiddenCreationHints();
        } else if (EntityClientMod.isBackgroundInputIsolationEnabled()) {
            GlfwWindowBridge.applyInputIsolatedCreationHints();
        }
    }
}
