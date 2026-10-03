package dev.entity.client.autonomy;

import dev.entity.client.autonomy.policy.DeliveryPolicy;
import dev.entity.client.autonomy.inventory.InventoryTransactionEngine;
import dev.entity.client.baritone.FabricBaritonePort;
import dev.entity.core.control.ControlLease;
import dev.entity.core.port.BaritonePort;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Approaches a player, drops an exact quantity, and waits for pickup. */
public final class DeliveryController {
    private static final double DELIVERY_RANGE = DeliveryPolicy.HANDOFF_RANGE;
    private static final double PICKUP_OBSERVATION_RANGE = 5.0;
    private static final double STEP_AWAY_RANGE = 4.0;
    private static final long DROP_CONFIRM_TIMEOUT_MILLIS = 12_000L;
    private final MinecraftClient client;
    private final ClientInventoryController inventory;
    private Session session;

    public DeliveryController(MinecraftClient client, ClientInventoryController inventory) {
        this.client = Objects.requireNonNull(client, "client");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public Result tick(
            String missionId,
            String playerName,
            String itemId,
            int requestedCount,
            int alreadyDelivered,
            boolean allowDrop,
            String clickTransactionOwner,
            String removalTransactionOwner,
            ControlLease lease,
            FabricBaritonePort baritone,
            long nowMillis) {
        if (client.player == null || client.world == null) {
            return new Result(State.WAITING, alreadyDelivered, "waiting for Minecraft world/player");
        }
        String normalizedItem = ClientInventoryController.normalizeId(itemId);
        int requested = Math.max(1, requestedCount);
        if (session == null || !session.matches(missionId, playerName, normalizedItem, requested)) {
            if (session != null && !session.removalOwner.isBlank()) {
                inventory.clearRemovalTransaction(session.removalOwner);
            }
            session = new Session(
                    missionId,
                    playerName,
                    normalizedItem,
                    requested,
                    Math.max(0, alreadyDelivered),
                    removalTransactionOwner);
        }
        session.delivered = Math.max(session.delivered, Math.max(0, alreadyDelivered));
        int currentCount = inventory.count(normalizedItem);

        if (session.delivered >= requested) {
            if (hasOwnedDropNearby(normalizedItem)) {
                return new Result(
                        State.WAITING_FOR_PICKUP,
                        session.delivered,
                        "delivered " + requested + " " + normalizedItem + "; waiting for pickup");
            }
            return new Result(
                    State.COMPLETE,
                    session.delivered,
                    playerName + " picked up the delivered " + requested + " " + normalizedItem);
        }

        if (session.removalPending) {
            InventoryTransactionEngine.RemovalObservation observation =
                    inventory.observeRemovalTransaction(
                            session.removalOwner,
                            normalizedItem,
                            currentCount,
                            nowMillis,
                            DROP_CONFIRM_TIMEOUT_MILLIS);
            Optional<AbstractClientPlayerEntity> recipient = findPlayer(playerName);
            recipient.ifPresent(player -> stepAwayFrom(player, lease, baritone));
            return switch (observation) {
                case RESTORED -> {
                    session.removalPending = false;
                    yield new Result(
                            State.RETRY,
                            session.delivered,
                            "Entity re-picked the handoff stack; stepping back and trying the delivery again");
                }
                case TIMED_OUT_UNCHANGED, NOT_FOUND -> {
                    session.removalPending = false;
                    yield new Result(
                            State.RETRY,
                            session.delivered,
                            "the server never confirmed the inventory drop; retrying the handoff");
                }
                case TIMED_OUT_AFTER_DECREASE -> new Result(
                        State.BLOCKED,
                        session.delivered,
                        "recipient pickup proof did not arrive; their inventory may be full. "
                                + "Make space, keep the bridge connected, and use /e retry");
                case UNCHANGED, DECREASED -> new Result(
                        State.WAITING_FOR_PICKUP,
                        session.delivered,
                        "stepping outside pickup range while Paper verifies "
                                + session.pendingDropCount + " delivered " + normalizedItem);
            };
        }

        Optional<AbstractClientPlayerEntity> target = findPlayer(playerName);
        if (target.isEmpty() || !DeliveryPolicy.withinHandoffRange(
                client.player.squaredDistanceTo(target.orElseThrow()))) {
            inventory.closeHandledScreen();
            BaritonePort.Goal approach = new BaritonePort.Goal(
                    missionId + ":deliver:" + playerName.toLowerCase(Locale.ROOT),
                    "come",
                    Map.of("player", playerName, "range", Double.toString(DELIVERY_RANGE)));
            baritone.start(approach, lease);
            BaritonePort.Status status = baritone.poll(approach.missionId());
            if (status.state() == BaritonePort.State.BLOCKED) {
                return new Result(State.BLOCKED, session.delivered, status.detail());
            }
            if (status.state() == BaritonePort.State.TRANSIENT_FAILURE) {
                return new Result(State.RETRY, session.delivered, status.detail());
            }
            return new Result(
                    status.progressExpected() ? State.APPROACHING : State.HOLDING_POSITION,
                    session.delivered,
                    target.isEmpty()
                            ? "travelling toward " + playerName + " using Paper waypoints"
                            : "approaching " + playerName + "; " + status.detail());
        }

        baritone.cancel(lease.epoch(), "delivery target reached");
        AbstractClientPlayerEntity targetPlayer = target.orElseThrow();
        aimAt(targetPlayer);

        if (session.delivered < requested) {
            if (currentCount <= 0) {
                return new Result(
                        State.ITEM_MISSING,
                        session.delivered,
                        "delivery needs " + (requested - session.delivered) + " more " + normalizedItem);
            }
            if (!allowDrop) {
                return new Result(
                        State.DROP_READY,
                        session.delivered,
                        "ready to hand item " + (session.delivered + 1) + "/" + requested
                                + " to " + playerName);
            }
            int remaining = requested - session.delivered;
            ClientInventoryController.DropResult drop = inventory.dropExactBatch(
                    normalizedItem, remaining, clickTransactionOwner, nowMillis);
            ClientInventoryController.ClickResult click = drop.result();
            if (click == ClientInventoryController.ClickResult.ITEM_MISSING) {
                return new Result(State.ITEM_MISSING, session.delivered, "delivery item disappeared from inventory");
            }
            if (click == ClientInventoryController.ClickResult.CURSOR_NOT_EMPTY
                    || click == ClientInventoryController.ClickResult.SLOT_UNAVAILABLE) {
                return new Result(State.RETRY, session.delivered, "inventory could not drop item: " + click);
            }
            if (click == ClientInventoryController.ClickResult.WAITING) {
                return new Result(State.DROPPING, session.delivered, "preparing the batched inventory handoff");
            }
            session.removalPending = true;
            session.pendingDropCount = Math.max(1, drop.count());
            inventory.beginRemovalTransaction(
                    session.removalOwner,
                    normalizedItem,
                    currentCount,
                    session.pendingDropCount,
                    nowMillis);
            return new Result(
                    State.DROPPING,
                    session.delivered,
                    "handing off " + session.pendingDropCount + " " + normalizedItem
                            + " as one stack to " + playerName);
        }

        throw new IllegalStateException("delivery state exceeded requested count");
    }

    public void clear(String missionId) {
        if (session != null && session.missionId.equals(missionId)) {
            inventory.clearRemovalTransaction(session.removalOwner);
            session = null;
        }
    }

    /** Paper's cumulative receipt supersedes local inventory/drop guessing. */
    public void confirmReceipt(String missionId, int delivered) {
        if (session == null || !session.missionId.equals(missionId)) return;
        session.delivered = Math.max(session.delivered, Math.max(0, delivered));
        inventory.clearRemovalTransaction(session.removalOwner);
        session.removalPending = false;
    }

    public void clearAll() {
        if (session != null) inventory.clearRemovalTransaction(session.removalOwner);
        session = null;
    }

    private Optional<AbstractClientPlayerEntity> findPlayer(String name) {
        return client.world.getPlayers().stream()
                .filter(player -> !player.getUuid().equals(client.player.getUuid()))
                .filter(player -> player.getName().getString().equalsIgnoreCase(name))
                .min(Comparator.comparingDouble(client.player::squaredDistanceTo));
    }

    private void aimAt(Entity target) {
        Vec3d from = client.player.getEyePos();
        Vec3d to = target.getPos().add(0, target.getHeight() * 0.5, 0);
        Vec3d delta = to.subtract(from);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        client.player.setYaw(MathHelper.wrapDegrees(yaw));
        client.player.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
    }

    private void stepAwayFrom(
            AbstractClientPlayerEntity target,
            ControlLease lease,
            FabricBaritonePort baritone) {
        if (client.player == null) return;
        Vec3d away = client.player.getPos().subtract(target.getPos());
        if (client.player.squaredDistanceTo(target) >= STEP_AWAY_RANGE * STEP_AWAY_RANGE) {
            baritone.cancel(lease.epoch(), "outside delivery pickup radius");
            return;
        }
        Vec3d destination = away.lengthSquared() < 0.01
                ? client.player.getPos().add(STEP_AWAY_RANGE, 0, 0)
                : client.player.getPos().add(away.normalize().multiply(STEP_AWAY_RANGE));
        BaritonePort.Goal stepAway = new BaritonePort.Goal(
                session.missionId + ":handoff-clearance",
                "goto",
                Map.of(
                        "x", Integer.toString(MathHelper.floor(destination.x)),
                        "y", Integer.toString(client.player.getBlockY()),
                        "z", Integer.toString(MathHelper.floor(destination.z)),
                        "range", "1"));
        baritone.start(stepAway, lease);
    }

    private boolean hasOwnedDropNearby(String itemId) {
        Vec3d position = client.player.getPos();
        double rangeSquared = PICKUP_OBSERVATION_RANGE * PICKUP_OBSERVATION_RANGE;
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof ItemEntity item) || !item.isAlive()) continue;
            if (item.squaredDistanceTo(position) > rangeSquared) continue;
            String id = Registries.ITEM.getId(item.getStack().getItem()).toString();
            if (!ClientInventoryController.normalizeId(id).equals(
                    ClientInventoryController.normalizeId(itemId))) continue;
            Entity owner = item.getOwner();
            if (owner == null || owner.getUuid().equals(client.player.getUuid())) return true;
        }
        return false;
    }

    public enum State {
        APPROACHING,
        HOLDING_POSITION,
        DROPPING,
        DROP_READY,
        WAITING_FOR_PICKUP,
        WAITING,
        ITEM_MISSING,
        RETRY,
        BLOCKED,
        COMPLETE
    }

    public record Result(State state, int delivered, String detail) {
    }

    private static final class Session {
        private final String missionId;
        private final String playerName;
        private final String itemId;
        private final int requested;
        private final String removalOwner;
        private int delivered;
        private boolean removalPending;
        private int pendingDropCount;

        private Session(
                String missionId,
                String playerName,
                String itemId,
                int requested,
                int delivered,
                String removalOwner) {
            this.missionId = missionId;
            this.playerName = playerName;
            this.itemId = itemId;
            this.requested = requested;
            this.delivered = delivered;
            this.removalOwner = Objects.requireNonNull(removalOwner, "removalOwner");
        }

        private boolean matches(String missionId, String playerName, String itemId, int requested) {
            return this.missionId.equals(missionId)
                    && this.playerName.equalsIgnoreCase(playerName)
                    && this.itemId.equals(itemId)
                    && this.requested == requested;
        }
    }
}
