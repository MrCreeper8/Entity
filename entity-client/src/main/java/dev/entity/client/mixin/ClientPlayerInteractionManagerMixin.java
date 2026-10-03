package dev.entity.client.mixin;

import dev.entity.client.control.MinecraftActuatorGateway;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stops an unsafe exact block mutation before Minecraft can send or apply it. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void entity2$guardProtectedBlockInteraction(
            ClientPlayerEntity player,
            Hand hand,
            BlockHitResult hit,
            CallbackInfoReturnable<ActionResult> callback) {
        if (MinecraftActuatorGateway.suppressProtectedBlockInteraction(
                MinecraftClient.getInstance(), hand, hit)) {
            callback.setReturnValue(ActionResult.FAIL);
        }
    }

    @Inject(method = "interactItem", at = @At("HEAD"), cancellable = true)
    private void entity2$guardProtectedItemInteraction(
            PlayerEntity player,
            Hand hand,
            CallbackInfoReturnable<ActionResult> callback) {
        if (MinecraftActuatorGateway.suppressProtectedItemInteraction(
                MinecraftClient.getInstance(), hand)) {
            callback.setReturnValue(ActionResult.FAIL);
        }
    }

    @Inject(
            method = {"attackBlock", "updateBlockBreakingProgress"},
            at = @At("HEAD"),
            cancellable = true)
    private void entity2$guardProtectedRouteSupportProgress(
            BlockPos target,
            Direction side,
            CallbackInfoReturnable<Boolean> callback) {
        if (MinecraftActuatorGateway.suppressProtectedBlockBreaking(
                MinecraftClient.getInstance(), target)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "breakBlock", at = @At("HEAD"), cancellable = true)
    private void entity2$guardProtectedRouteSupportCommit(
            BlockPos target,
            CallbackInfoReturnable<Boolean> callback) {
        if (MinecraftActuatorGateway.suppressProtectedBlockBreaking(
                MinecraftClient.getInstance(), target)) {
            callback.setReturnValue(false);
        }
    }
}
