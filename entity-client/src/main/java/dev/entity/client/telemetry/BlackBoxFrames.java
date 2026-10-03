package dev.entity.client.telemetry;

import baritone.api.BaritoneAPI;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.entity.client.baritone.FabricBaritonePort;
import dev.entity.client.baritone.AquaticTravelPolicy;
import dev.entity.client.baritone.CombatAttackJournal;
import dev.entity.client.autonomy.inventory.InventoryTransactionEngine;
import dev.entity.client.control.DirectBlockBreakLease;
import dev.entity.client.control.DirectUseLease;
import dev.entity.client.control.MinecraftActuatorGateway;
import dev.entity.core.EntityCore;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** Builds compact, read-only game snapshots for {@link BlackBoxRecorder}. */
public final class BlackBoxFrames {
    private static final int OBSERVATION_HORIZONTAL_RADIUS = 6;
    private static final int OBSERVATION_VERTICAL_RADIUS = 3;
    private static final int HEADROOM_SCAN_LIMIT_BLOCKS = 8;
    private static final int COLLISION_HORIZONTAL_RADIUS = 1;
    private static final int HOSTILE_HORIZONTAL_RADIUS = 32;
    private static final int HOSTILE_VERTICAL_RADIUS = 16;
    private static final int[][] HORIZONTAL_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private BlackBoxFrames() {
    }

    public static JsonObject snapshot(
            MinecraftClient client,
            EntityCore.TickDecision decision,
            FabricBaritonePort.Snapshot baritone,
            InventoryTransactionEngine.Diagnostics inventoryTransactions,
            String why,
            String runtimeStatus,
            long clientTick,
            BehaviorInvariantMonitor.StateSnapshot invariantState,
            List<BehaviorInvariantMonitor.Violation> invariantViolations) {
        return snapshot(
                client, decision, baritone, null, inventoryTransactions, why,
                runtimeStatus, clientTick, invariantState, invariantViolations);
    }

    public static JsonObject snapshot(
            MinecraftClient client,
            EntityCore.TickDecision decision,
            FabricBaritonePort.Snapshot baritone,
            AquaticTravelPolicy.Snapshot survivalAquatic,
            InventoryTransactionEngine.Diagnostics inventoryTransactions,
            String why,
            String runtimeStatus,
            long clientTick,
            BehaviorInvariantMonitor.StateSnapshot invariantState,
            List<BehaviorInvariantMonitor.Violation> invariantViolations) {
        JsonObject frame = TelemetryFrames.snapshot(
                client, decision, baritone, survivalAquatic, why);
        frame.addProperty("clientTick", clientTick);
        frame.addProperty("runtimeStatus", runtimeStatus == null ? "" : runtimeStatus);
        frame.addProperty("screen", stableScreenName(client));
        frame.add("behaviorInvariants", behaviorInvariants(invariantState, invariantViolations));
        if (inventoryTransactions != null) {
            JsonObject transaction = new JsonObject();
            transaction.addProperty("pendingClickOwner", inventoryTransactions.pendingClickOwner());
            transaction.addProperty("cursorOwner", inventoryTransactions.cursorOwner());
            transaction.addProperty("pendingRemovals", inventoryTransactions.pendingRemovals());
            transaction.addProperty("acknowledgedClicks", inventoryTransactions.acknowledgedClicks());
            transaction.addProperty("timedOutClicks", inventoryTransactions.timedOutClicks());
            InventoryTransactionEngine.ClickTimeout timeout = inventoryTransactions.lastTimeout();
            if (timeout != null) {
                JsonObject latest = new JsonObject();
                latest.addProperty("sequence", timeout.sequence());
                latest.addProperty("detectedAtMillis", timeout.detectedAtMillis());
                latest.addProperty("startedAtMillis", timeout.startedAtMillis());
                latest.addProperty("owner", timeout.owner());
                latest.addProperty("handlerSyncId", timeout.handlerSyncId());
                JsonArray watchedSlots = new JsonArray();
                timeout.watchedSlots().forEach(watchedSlots::add);
                latest.add("watchedSlots", watchedSlots);
                transaction.add("lastTimeout", latest);
            }
            frame.add("inventoryTransaction", transaction);
        }
        JsonObject controls = new JsonObject();
        controls.addProperty("nativeRandomLooking", BaritoneAPI.getSettings().randomLooking.value);
        controls.addProperty("nativeRandomLooking113", BaritoneAPI.getSettings().randomLooking113.value);
        controls.addProperty("windowFocused", client.isWindowFocused());
        controls.addProperty("cursorLocked", client.mouse != null && client.mouse.isCursorLocked());
        controls.addProperty("pauseOnLostFocus", client.options != null && client.options.pauseOnLostFocus);
        controls.addProperty("physicalAttackPressed",
                client.options != null && client.options.attackKey.isPressed());
        controls.addProperty("physicalUsePressed",
                client.options != null && client.options.useKey.isPressed());
        controls.addProperty("minecraftBreaking",
                client.interactionManager != null && client.interactionManager.isBreakingBlock());
        controls.addProperty("minecraftBreakProgress", client.interactionManager == null
                ? 0 : client.interactionManager.getBlockBreakingProgress());
        // The selected tool alone cannot distinguish chopping a log from clearing scaffold.
        // This is the observed crosshair, not a claim that a break packet was accepted.
        JsonObject crosshair = new JsonObject();
        if (client.world != null && client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit
                && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            BlockPos target = hit.getBlockPos();
            crosshair.addProperty("block", Registries.BLOCK.getId(client.world.getBlockState(target).getBlock()).toString());
            crosshair.addProperty("x", target.getX());
            crosshair.addProperty("y", target.getY());
            crosshair.addProperty("z", target.getZ());
        }
        controls.add("crosshairBlock", crosshair);
        JsonObject lastBlockAttempt = new JsonObject();
        MinecraftActuatorGateway.shared(client).lastBlockAttempt().ifPresent(attempt -> {
            lastBlockAttempt.addProperty("timestamp", attempt.timestamp());
            lastBlockAttempt.addProperty("dimension", attempt.dimension());
            lastBlockAttempt.addProperty("x", attempt.x()); lastBlockAttempt.addProperty("y", attempt.y());
            lastBlockAttempt.addProperty("z", attempt.z()); lastBlockAttempt.addProperty("block", attempt.block());
            lastBlockAttempt.addProperty("item", attempt.item()); lastBlockAttempt.addProperty("speed", attempt.speed());
            lastBlockAttempt.addProperty("blocked", attempt.blocked());
        });
        controls.add("lastBlockAttempt", lastBlockAttempt);
        DirectBlockBreakLease.Snapshot pinned =
                MinecraftActuatorGateway.shared(client).blockBreakSnapshot();
        JsonObject directBreak = new JsonObject();
        directBreak.addProperty("active", pinned.active());
        directBreak.addProperty("generation", pinned.generation());
        directBreak.addProperty("operation", pinned.operationId());
        directBreak.addProperty("dimension", pinned.dimension());
        directBreak.addProperty("target", pinned.target());
        directBreak.addProperty("side", pinned.side());
        directBreak.addProperty("heartbeatPlayerAge", pinned.heartbeatPlayerAge());
        controls.add("directBlockBreak", directBreak);
        DirectUseLease.Snapshot heldUse =
                MinecraftActuatorGateway.shared(client).heldUseSnapshot();
        JsonObject directUse = new JsonObject();
        directUse.addProperty("active", heldUse.active());
        directUse.addProperty("generation", heldUse.generation());
        directUse.addProperty("operation", heldUse.operationId());
        directUse.addProperty("dimension", heldUse.dimension());
        directUse.addProperty("heartbeatPlayerAge", heldUse.heartbeatPlayerAge());
        controls.add("directHeldUse", directUse);
        frame.add("controls", controls);
        JsonObject action = new JsonObject();
        if (decision == null) {
            action.addProperty("layer", "starting");
            action.addProperty("action", "starting");
            action.addProperty("reason", "Entity runtime is starting");
        } else {
            action.addProperty("layer", decision.layer().name().toLowerCase());
            action.addProperty("action", decision.action());
            action.addProperty("reason", decision.reason());
            action.addProperty("controlEpoch", decision.controlEpoch());
            if (decision.protectionThreat() != null) {
                action.addProperty("protectionThreatEntityId", decision.protectionThreat().entityId());
                action.addProperty("protectionThreatTarget", decision.protectionThreat().target().name().toLowerCase());
            }
            decision.lease().ifPresent(lease -> {
                JsonObject ownership = new JsonObject();
                ownership.addProperty("owner", lease.owner());
                ownership.addProperty("epoch", lease.epoch());
                ownership.addProperty("priority", lease.priority());
                ownership.addProperty("expiresAt", lease.expiresAtMillis());
                ownership.addProperty("channels", lease.channels().toString());
                action.add("lease", ownership);
            });
        }
        frame.add("decision", action);

        var player = client.player;
        if (player == null) return frame;

        JsonObject movement = new JsonObject();
        var velocity = player.getVelocity();
        movement.addProperty("velocityX", velocity.x);
        movement.addProperty("velocityY", velocity.y);
        movement.addProperty("velocityZ", velocity.z);
        movement.addProperty("yaw", player.getYaw());
        movement.addProperty("pitch", player.getPitch());
        movement.addProperty("onGround", player.isOnGround());
        movement.addProperty("touchingWater", player.isTouchingWater());
        movement.addProperty("insideWall", player.isInsideWall());
        movement.addProperty("headSubmerged", player.isSubmergedInWater());
        movement.addProperty("swimming", player.isSwimming());
        movement.addProperty("sneaking", player.isSneaking());
        movement.addProperty("usingItem", player.isUsingItem());
        movement.addProperty("blocking", player.isBlocking());
        movement.addProperty("sprinting", player.isSprinting());
        movement.addProperty("horizontalSpeed", Math.hypot(velocity.x, velocity.z));
        movement.addProperty("fallDistance", player.fallDistance);
        frame.add("movement", movement);
        frame.add("worldObservation", WorldObservationFrame.encode(observeNearbyWorld(client)));

        var inventory = player.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        JsonObject inventoryState = new JsonObject();
        inventoryState.addProperty("selectedSlot", selectedSlot);
        inventoryState.addProperty("handlerSyncId", player.currentScreenHandler.syncId);
        inventoryState.add("cursor", stack(player.currentScreenHandler.getCursorStack()));
        ItemStack selected = inventory.getStack(selectedSlot);
        inventoryState.add("selected", stack(selected));
        inventoryState.add("offhand", stack(player.getOffHandStack()));

        Map<String, Integer> totals = new TreeMap<>();
        int occupiedSlots = 0;
        for (ItemStack present : inventory) {
            if (present.isEmpty()) continue;
            occupiedSlots++;
            String id = Registries.ITEM.getId(present.getItem()).toString();
            totals.merge(id, present.getCount(), Integer::sum);
        }
        inventoryState.addProperty("occupiedSlots", occupiedSlots);
        JsonObject counts = new JsonObject();
        totals.forEach(counts::addProperty);
        inventoryState.add("counts", counts);
        frame.add("inventory", inventoryState);
        return frame;
    }

    private static JsonObject behaviorInvariants(
            BehaviorInvariantMonitor.StateSnapshot state,
            List<BehaviorInvariantMonitor.Violation> violations) {
        JsonObject encoded = new JsonObject();
        if (state != null) {
            encoded.addProperty("trackedSupportCycles", state.trackedSupportCycles());
            encoded.addProperty("mineRestartEvents", state.mineRestartEvents());
            encoded.addProperty("verticalReversalEvents", state.verticalReversalEvents());
            encoded.addProperty("lavaExposureActive", state.lavaExposureActive());
            encoded.addProperty("recentLavaExitRetained", state.recentLavaExitRetained());
            encoded.addProperty("protectionRetreatActive", state.protectionRetreatActive());
            encoded.addProperty("surfaceProtectionTransitions", state.surfaceProtectionTransitions());
            encoded.addProperty("suffocationEscapeActive", state.suffocationEscapeActive());
        }
        JsonArray recent = new JsonArray();
        if (violations != null) {
            for (BehaviorInvariantMonitor.Violation violation : violations) {
                if (violation == null) continue;
                JsonObject item = new JsonObject();
                item.addProperty("type", violation.type().name().toLowerCase(Locale.ROOT));
                item.addProperty("detectedAtMillis", violation.detectedAtMillis());
                item.addProperty("clientTick", violation.clientTick());
                item.addProperty("missionId", violation.missionId());
                item.addProperty("evidence", violation.evidence());
                recent.add(item);
            }
        }
        encoded.addProperty("recentViolationCount", recent.size());
        encoded.add("recentViolations", recent);
        return encoded;
    }

    /** Stable encoder shared by sampled deltas and standalone combat event records. */
    public static JsonObject combatAttackEvent(CombatAttackJournal.Event attack) {
        JsonObject event = new JsonObject();
        event.addProperty("attackSequence", attack.sequence());
        // Legacy sample readers use `sequence`; standalone record readers can
        // distinguish recorder sequence from domain sequence via attackSequence.
        event.addProperty("sequence", attack.sequence());
        event.addProperty("issuedAtMillis", attack.issuedAtMillis());
        event.addProperty("clientTick", attack.clientTick());
        event.addProperty("source", attack.source());
        event.addProperty("operationMissionId", attack.operationMissionId());
        event.addProperty("operationGeneration", attack.operationGeneration());
        event.addProperty("operationStartedAtMillis", attack.operationStartedAtMillis());
        event.addProperty("controlEpoch", attack.controlEpoch());
        event.addProperty("targetUuid", attack.targetUuid());
        event.addProperty("targetType", attack.targetType());
        event.addProperty("weaponId", attack.weaponId());
        event.addProperty("cooldownProgress", attack.cooldownProgress());
        event.addProperty("distance", attack.distance());
        return event;
    }

    /** Replaces the historical ring in one black-box frame with a one-shot delta. */
    public static void setCombatAttackDelta(
            JsonObject frame,
            List<CombatAttackJournal.Event> attacks) {
        if (frame == null) return;
        JsonObject pathing = frame.getAsJsonObject("baritone");
        if (pathing == null) return;
        JsonArray delta = new JsonArray();
        if (attacks != null) {
            for (CombatAttackJournal.Event attack : attacks) {
                if (attack != null) delta.add(combatAttackEvent(attack));
            }
        }
        pathing.add("combatAttackEvents", delta);
    }

    private static WorldObservationFrame.Snapshot observeNearbyWorld(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return new WorldObservationFrame.Snapshot(
                    0, 0, false,
                    OBSERVATION_HORIZONTAL_RADIUS,
                    OBSERVATION_VERTICAL_RADIUS,
                    WorldObservationFrame.Environment.unavailable(),
                    WorldObservationFrame.Headroom.unavailable(),
                    WorldObservationFrame.CollisionNeighborhood.unavailable(),
                    WorldObservationFrame.HazardScan.unavailable(
                            OBSERVATION_HORIZONTAL_RADIUS, OBSERVATION_VERTICAL_RADIUS),
                    WorldObservationFrame.boundedHostiles(
                            false,
                            false,
                            HOSTILE_HORIZONTAL_RADIUS,
                            HOSTILE_VERTICAL_RADIUS,
                            List.of(),
                            WorldObservationFrame.MAX_HOSTILE_ENTRIES));
        }

        int pigs = 0;
        double maximumEntityDistanceSquared =
                OBSERVATION_HORIZONTAL_RADIUS * OBSERVATION_HORIZONTAL_RADIUS;
        ArrayList<WorldObservationFrame.Hostile> hostiles = new ArrayList<>();
        for (Entity entity : client.world.getEntities()) {
            if (!entity.isAlive() || entity.isRemoved()) continue;
            double dx = entity.getX() - client.player.getX();
            double dy = entity.getY() - client.player.getY();
            double dz = entity.getZ() - client.player.getZ();
            double horizontalDistanceSquared = dx * dx + dz * dz;
            if (entity instanceof PigEntity
                    && horizontalDistanceSquared <= maximumEntityDistanceSquared) {
                pigs++;
            }
            if (!(entity instanceof MobEntity hostile)
                    || (hostile.getType().getSpawnGroup() != SpawnGroup.MONSTER
                            && hostile.getTarget() != client.player)
                    || horizontalDistanceSquared
                            > HOSTILE_HORIZONTAL_RADIUS * HOSTILE_HORIZONTAL_RADIUS
                    || Math.abs(dy) > HOSTILE_VERTICAL_RADIUS) {
                continue;
            }
            LivingEntity target = hostile.getTarget();
            boolean targetingEntity = target == client.player;
            String targetingEvidence = target == null
                    ? "not_observed"
                    : targetingEntity ? "client_target" : "client_other_target";
            hostiles.add(new WorldObservationFrame.Hostile(
                    hostile.getUuidAsString(),
                    Registries.ENTITY_TYPE.getId(hostile.getType()).toString(),
                    Math.sqrt(client.player.squaredDistanceTo(hostile)),
                    client.player.canSee(hostile),
                    targetingEntity,
                    targetingEvidence,
                    target == null ? "" : target.getUuidAsString(),
                    target == null
                            ? ""
                            : Registries.ENTITY_TYPE.getId(target.getType()).toString()));
        }

        BlockPos center = client.player.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        int coalOres = 0;
        int hazardBlocks = 0;
        Map<String, WorldObservationFrame.Hazard> nearestHazards = new HashMap<>();
        boolean complete = true;
        for (int x = center.getX() - OBSERVATION_HORIZONTAL_RADIUS;
                x <= center.getX() + OBSERVATION_HORIZONTAL_RADIUS; x++) {
            for (int z = center.getZ() - OBSERVATION_HORIZONTAL_RADIUS;
                    z <= center.getZ() + OBSERVATION_HORIZONTAL_RADIUS; z++) {
                if (!client.world.isChunkLoaded(x >> 4, z >> 4)) {
                    complete = false;
                    continue;
                }
                for (int y = center.getY() - OBSERVATION_VERTICAL_RADIUS;
                        y <= center.getY() + OBSERVATION_VERTICAL_RADIUS; y++) {
                    cursor.set(x, y, z);
                    BlockState state = client.world.getBlockState(cursor);
                    if (state.isOf(Blocks.COAL_ORE)) coalOres++;
                    if (!isHazard(state)) continue;
                    hazardBlocks++;
                    String type = Registries.BLOCK.getId(state.getBlock()).toString();
                    double dx = x + 0.5 - client.player.getX();
                    double dy = y + 0.5 - client.player.getY();
                    double dz = z + 0.5 - client.player.getZ();
                    WorldObservationFrame.Hazard candidate =
                            new WorldObservationFrame.Hazard(type, Math.sqrt(dx * dx + dy * dy + dz * dz));
                    nearestHazards.merge(
                            type,
                            candidate,
                            (left, right) -> left.distance() <= right.distance() ? left : right);
                }
            }
        }

        BlockPos eye = BlockPos.ofFloored(client.player.getEyePos());
        boolean skyVisible = client.world.isSkyVisible(eye);
        WorldObservationFrame.Environment environment = new WorldObservationFrame.Environment(
                true,
                skyVisible,
                client.world.isDay(),
                client.world.isNight(),
                client.world.getDimension().hasSkyLight(),
                client.world.getLightLevel(LightType.SKY, eye),
                client.world.getLightLevel(LightType.BLOCK, eye));
        WorldObservationFrame.Headroom headroom = observeHeadroom(client, center, skyVisible);
        WorldObservationFrame.CollisionNeighborhood collision =
                observeCollisionNeighborhood(client, center);
        boolean hostileChunkCoverage = loadedChunksCover(
                client,
                center,
                HOSTILE_HORIZONTAL_RADIUS);
        return new WorldObservationFrame.Snapshot(
                pigs,
                coalOres,
                complete,
                OBSERVATION_HORIZONTAL_RADIUS,
                OBSERVATION_VERTICAL_RADIUS,
                environment,
                headroom,
                collision,
                WorldObservationFrame.boundedHazards(
                        complete,
                        OBSERVATION_HORIZONTAL_RADIUS,
                        OBSERVATION_VERTICAL_RADIUS,
                        hazardBlocks,
                        nearestHazards.values(),
                        WorldObservationFrame.MAX_HAZARD_ENTRIES),
                WorldObservationFrame.boundedHostiles(
                        true,
                        hostileChunkCoverage,
                        HOSTILE_HORIZONTAL_RADIUS,
                        HOSTILE_VERTICAL_RADIUS,
                        hostiles,
                        WorldObservationFrame.MAX_HOSTILE_ENTRIES));
    }

    private static WorldObservationFrame.Headroom observeHeadroom(
            MinecraftClient client,
            BlockPos center,
            boolean skyVisible) {
        boolean scanComplete = client.world != null
                && client.world.isChunkLoaded(center.getX() >> 4, center.getZ() >> 4);
        boolean feetColliding = scanComplete && collides(client, center);
        boolean headColliding = scanComplete && collides(client, center.up());
        boolean aboveHeadColliding = scanComplete && collides(client, center.up(2));
        int clearBlocks = 0;
        int ceilingOffset = -1;
        if (scanComplete) {
            for (int offset = 0; offset < HEADROOM_SCAN_LIMIT_BLOCKS; offset++) {
                BlockPos position = center.up(offset);
                if (!client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
                    scanComplete = false;
                    break;
                }
                if (collides(client, position)) {
                    ceilingOffset = offset;
                    break;
                }
                clearBlocks++;
            }
        }
        boolean ceilingFound = ceilingOffset >= 0;
        String spaceClass = WorldObservationFrame.classifySpace(
                true,
                scanComplete,
                skyVisible,
                clearBlocks,
                ceilingFound,
                feetColliding,
                headColliding);
        return new WorldObservationFrame.Headroom(
                true,
                scanComplete,
                HEADROOM_SCAN_LIMIT_BLOCKS,
                clearBlocks,
                ceilingFound,
                ceilingOffset,
                feetColliding,
                headColliding,
                aboveHeadColliding,
                spaceClass);
    }

    private static WorldObservationFrame.CollisionNeighborhood observeCollisionNeighborhood(
            MinecraftClient client,
            BlockPos center) {
        boolean complete = true;
        int sampled = 0;
        int colliding = 0;
        int feetColliding = 0;
        int headColliding = 0;
        int aboveHeadColliding = 0;
        int floorSupport = 0;
        for (int dx = -COLLISION_HORIZONTAL_RADIUS;
                dx <= COLLISION_HORIZONTAL_RADIUS; dx++) {
            for (int dz = -COLLISION_HORIZONTAL_RADIUS;
                    dz <= COLLISION_HORIZONTAL_RADIUS; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos position = center.add(dx, dy, dz);
                    if (!client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
                        complete = false;
                        continue;
                    }
                    sampled++;
                    if (!collides(client, position)) continue;
                    colliding++;
                    if (dy == 0) feetColliding++;
                    else if (dy == 1) headColliding++;
                    else aboveHeadColliding++;
                }
                BlockPos floor = center.add(dx, -1, dz);
                if (!client.world.isChunkLoaded(floor.getX() >> 4, floor.getZ() >> 4)) {
                    complete = false;
                } else if (collides(client, floor)) {
                    floorSupport++;
                }
            }
        }

        int openDirections = 0;
        int enclosedDirections = 0;
        for (int[] direction : HORIZONTAL_DIRECTIONS) {
            BlockPos feet = center.add(direction[0], 0, direction[1]);
            BlockPos head = feet.up();
            if (!client.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) {
                complete = false;
                continue;
            }
            if (!collides(client, feet) && !collides(client, head)) openDirections++;
            else enclosedDirections++;
        }
        return new WorldObservationFrame.CollisionNeighborhood(
                true,
                complete,
                COLLISION_HORIZONTAL_RADIUS,
                sampled,
                colliding,
                feetColliding,
                headColliding,
                aboveHeadColliding,
                floorSupport,
                openDirections,
                enclosedDirections);
    }

    private static boolean loadedChunksCover(
            MinecraftClient client,
            BlockPos center,
            int horizontalRadius) {
        int minimumChunkX = (center.getX() - horizontalRadius) >> 4;
        int maximumChunkX = (center.getX() + horizontalRadius) >> 4;
        int minimumChunkZ = (center.getZ() - horizontalRadius) >> 4;
        int maximumChunkZ = (center.getZ() + horizontalRadius) >> 4;
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                if (!client.world.isChunkLoaded(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    private static boolean collides(MinecraftClient client, BlockPos position) {
        return !client.world.getBlockState(position)
                .getCollisionShape(client.world, position)
                .isEmpty();
    }

    private static boolean isHazard(BlockState state) {
        return state.isOf(Blocks.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.POINTED_DRIPSTONE)
                || state.isOf(Blocks.WITHER_ROSE);
    }

    private static JsonObject stack(ItemStack stack) {
        JsonObject encoded = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            encoded.addProperty("item", "minecraft:air");
            encoded.addProperty("count", 0);
            return encoded;
        }
        encoded.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
        encoded.addProperty("count", stack.getCount());
        if (stack.isDamageable()) {
            encoded.addProperty("damage", stack.getDamage());
            encoded.addProperty("maxDamage", stack.getMaxDamage());
        }
        return encoded;
    }

    /**
     * Runtime-remapped Minecraft classes have intermediary names such as
     * {@code class_479}; those names are useless to diagnostics and brittle in
     * black-box assertions.  Screen-handler types survive remapping, so expose
     * stable semantic names for the workstation screens Entity owns.
     */
    private static String stableScreenName(MinecraftClient client) {
        if (client.currentScreen == null) return "none";
        if (client.player != null) {
            if (client.player.currentScreenHandler instanceof CraftingScreenHandler) {
                return "CraftingScreen";
            }
            if (client.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler) {
                return "FurnaceScreen";
            }
        }
        return client.currentScreen.getClass().getSimpleName();
    }
}
