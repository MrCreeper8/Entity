package dev.entity.client.mixin;

import dev.entity.client.control.MinecraftMovementGateway;
import dev.entity.client.control.MovementFrame;
import dev.entity.client.control.MovementFrameActuator;
import dev.entity.client.control.MovementInputSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Installs Entity's complete frame directly after the active Input instance
 * ticks, before vanilla evaluates sprinting and movement for this tick.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerInputMixin {
    @Unique
    private MovementFrameActuator.IssuedFrame entity2$issuedMovementFrame;

    @Invoker("canStartSprinting")
    protected abstract boolean entity2$canStartSprinting();

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void entity2$resetIssuedMovementFrame(CallbackInfo callback) {
        entity2$issuedMovementFrame = null;
    }

    /**
     * Baritone's tick-head event clears sprint before the superclass chain reaches
     * {@code Entity.updateSwimming()}. Restore only the current lease-owned directive here so
     * vanilla can enter or leave the swimming pose itself.
     */
    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/AbstractClientPlayerEntity;tick()V",
                    shift = At.Shift.BEFORE))
    private void entity2$synchronizeSprintBeforeSwimming(CallbackInfo callback) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        if (client.player != player) return;

        MovementFrameActuator.SprintDirective directive =
                MinecraftMovementGateway.preSwimmingSprintDirective(
                                client, System.currentTimeMillis())
                        .orElse(null);
        if (directive != null) entity2$applySprintDirective(player, directive);
    }

    @Inject(
            method = "tickMovement",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/input/Input;tick()V",
                    shift = At.Shift.AFTER))
    private void entity2$installLeaseOwnedMovementFrame(CallbackInfo callback) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        if (client.player != player) return;

        MovementFrameActuator.IssuedFrame issued =
                MinecraftMovementGateway.sampleForInputTick(
                                client, System.currentTimeMillis())
                        .orElse(null);
        if (issued == null) return;
        entity2$issuedMovementFrame = issued;

        MovementFrame desired = issued.desired();
        MovementInputSnapshot projected = MovementInputSnapshot.from(desired);
        Input input = player.input;
        input.playerInput = new PlayerInput(
                projected.forward(),
                projected.backward(),
                projected.left(),
                projected.right(),
                projected.jump(),
                projected.sneak(),
                projected.sprint());
        ((InputAccessor) input).entity2$setMovementVector(
                new Vec2f(projected.sideways(), projected.ahead()));
        player.setYaw(desired.yaw());
        player.setPitch(desired.pitch());

        PlayerInput sampledInput = input.playerInput;
        MovementFrame sampled = new MovementFrame(
                sampledInput.forward(),
                sampledInput.backward(),
                sampledInput.left(),
                sampledInput.right(),
                sampledInput.jump(),
                sampledInput.sneak(),
                sampledInput.sprint(),
                player.getYaw(),
                player.getPitch());
        MinecraftMovementGateway.observeSampled(client, issued, sampled);
    }

    /**
     * Baritone redirects the sprint-key read in vanilla's sprint gate. Reapply only a still-live,
     * exact owned frame after that gate and before superclass movement physics consumes sprint.
     */
    @Inject(
            method = "tickMovement",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/AbstractClientPlayerEntity;tickMovement()V",
                    shift = At.Shift.BEFORE))
    private void entity2$synchronizeLateOwnedSprint(CallbackInfo callback) {
        MovementFrameActuator.IssuedFrame issued = entity2$issuedMovementFrame;
        entity2$issuedMovementFrame = null;
        if (issued == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        if (client.player != player) return;

        MovementFrameActuator.SprintDirective directive =
                MinecraftMovementGateway.lateSprintDirective(
                                client, issued, System.currentTimeMillis())
                        .orElse(null);
        if (directive == null) return;

        entity2$applySprintDirective(player, directive);
    }

    @Unique
    private void entity2$applySprintDirective(
            ClientPlayerEntity player,
            MovementFrameActuator.SprintDirective directive) {
        boolean sprint = directive == MovementFrameActuator.SprintDirective.START
                && (player.isSprinting() || entity2$canStartSprinting());
        player.setSprinting(sprint);
    }
}
