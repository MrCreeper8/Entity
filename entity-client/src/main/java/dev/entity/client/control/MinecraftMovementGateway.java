package dev.entity.client.control;

import dev.entity.core.control.ControlLease;
import net.minecraft.client.MinecraftClient;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

/** Minecraft-facing owner of the focus-independent movement actuator. */
public final class MinecraftMovementGateway {
    private static final Map<MinecraftClient, MinecraftMovementGateway> SHARED =
            new WeakHashMap<>();

    private final MinecraftClient client;
    private final MovementFrameActuator actuator = new MovementFrameActuator();

    private MinecraftMovementGateway(MinecraftClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public static MinecraftMovementGateway shared(MinecraftClient client) {
        Objects.requireNonNull(client, "client");
        synchronized (SHARED) {
            return SHARED.computeIfAbsent(client, MinecraftMovementGateway::new);
        }
    }

    /**
     * Phase-two controller API. Call once per Entity client tick with the
     * current renewed outer lease and exact current action generation.
     */
    public synchronized MovementFrameActuator.Submission submit(
            ExecutionKernel authority,
            ControlLease parent,
            ActionLease action,
            MovementFrame frame,
            long clientTick,
            long nowMillis) {
        requireLoadedContext();
        return actuator.submit(
                authority,
                parent,
                action,
                frame,
                currentDimension(),
                clientTick,
                client.player.age,
                nowMillis);
    }

    public synchronized boolean cancel(
            ActionLease action,
            MovementFrameActuator.Cleanup reason) {
        return actuator.cancel(action, reason);
    }

    public synchronized boolean neutralizeAll(MovementFrameActuator.Cleanup reason) {
        return actuator.neutralizeAll(reason);
    }

    public synchronized MovementFrameActuator.Snapshot snapshot() {
        return actuator.snapshot();
    }

    /** Called by the input mixin immediately after vanilla/Baritone input ticks. */
    public static Optional<MovementFrameActuator.IssuedFrame> sampleForInputTick(
            MinecraftClient client,
            long nowMillis) {
        if (client == null || client.player == null || client.world == null) {
            return Optional.empty();
        }
        MinecraftMovementGateway gateway = shared(client);
        synchronized (gateway) {
            return gateway.actuator.sample(
                    gateway.currentDimension(),
                    client.player.age,
                    nowMillis,
                    client.player.getYaw(),
                    client.player.getPitch(),
                    !client.player.isDead());
        }
    }

    /** Called by the mixin after it has installed and read back the sample. */
    public static boolean observeSampled(
            MinecraftClient client,
            MovementFrameActuator.IssuedFrame issued,
            MovementFrame sampled) {
        if (client == null) return false;
        MinecraftMovementGateway gateway;
        synchronized (SHARED) {
            gateway = SHARED.get(client);
        }
        if (gateway == null) return false;
        synchronized (gateway) {
            return gateway.actuator.observeSampled(issued, sampled);
        }
    }

    /** Called after Baritone's tick-head event and immediately before vanilla updates swimming. */
    public static Optional<MovementFrameActuator.SprintDirective> preSwimmingSprintDirective(
            MinecraftClient client,
            long nowMillis) {
        if (client == null || client.player == null || client.world == null
                || client.player.isDead()) {
            return Optional.empty();
        }
        MinecraftMovementGateway gateway;
        synchronized (SHARED) {
            gateway = SHARED.get(client);
        }
        if (gateway == null) return Optional.empty();
        synchronized (gateway) {
            return gateway.actuator.preSwimmingSprintDirective(
                    gateway.currentDimension(),
                    client.player.age,
                    nowMillis);
        }
    }

    /** Called after vanilla/Baritone's sprint gate and immediately before movement physics. */
    public static Optional<MovementFrameActuator.SprintDirective> lateSprintDirective(
            MinecraftClient client,
            MovementFrameActuator.IssuedFrame issued,
            long nowMillis) {
        if (client == null || client.player == null || client.world == null
                || client.player.isDead()) {
            return Optional.empty();
        }
        MinecraftMovementGateway gateway;
        synchronized (SHARED) {
            gateway = SHARED.get(client);
        }
        if (gateway == null) return Optional.empty();
        synchronized (gateway) {
            return gateway.actuator.lateSprintDirective(
                    issued,
                    gateway.currentDimension(),
                    client.player.age,
                    nowMillis);
        }
    }

    /** Network lifecycle mixin cleanup. */
    public static void onDisconnected(MinecraftClient client) {
        if (client == null) return;
        MinecraftMovementGateway gateway;
        synchronized (SHARED) {
            gateway = SHARED.get(client);
        }
        if (gateway != null) {
            gateway.neutralizeAll(MovementFrameActuator.Cleanup.DISCONNECT);
        }
    }

    private void requireLoadedContext() {
        if (client.player == null || client.world == null) {
            actuator.neutralizeAll(MovementFrameActuator.Cleanup.DISCONNECT);
            throw new IllegalStateException("movement frame requires a loaded player and world");
        }
    }

    private String currentDimension() {
        return client.world.getRegistryKey().getValue().toString();
    }
}
