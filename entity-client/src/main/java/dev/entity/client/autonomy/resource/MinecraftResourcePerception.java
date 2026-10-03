package dev.entity.client.autonomy.resource;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resource discovery only. Never an actuator, collision filter, or vanilla pickup prohibition. */
public final class MinecraftResourcePerception {
    private final MinecraftClient client;
    private final ResourcePerceptionMemory memory = new ResourcePerceptionMemory(4096);

    public MinecraftResourcePerception(MinecraftClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public boolean legitimate() { return memory.legitimate(); }
    public void setLegitimate(boolean value) { memory.setLegitimate(value); }
    public long revision() { scope(); return memory.revision(); }

    public boolean permitsBlock(BlockPos target) {
        scope();
        if (!legitimate()) return true;
        if (!loaded(target) || client.player == null) return false;
        String id = Registries.BLOCK.getId(client.world.getBlockState(target).getBlock()).toString();
        if (memory.block(target.asLong(), id, false)) return true;
        return memory.block(target.asLong(), id, observesBlock(target));
    }

    /** Returns the current observed position, or the last observed position, never hidden motion. */
    public Optional<Vec3d> entityPosition(Entity target) {
        scope();
        if (target == null || client.player == null || client.world == null
                || target.getWorld() != client.world) return Optional.empty();
        var position = new ResourcePerceptionMemory.Point(target.getX(), target.getY(), target.getZ());
        return memory.entity(target.getUuidAsString(), position,
                        legitimate() && (observesEntity(target) || knownProducedItem(target, position)))
                .map(point -> new Vec3d(point.x(), point.y(), point.z()));
    }

    public void forgetEntity(Entity target) {
        scope();
        if (target != null) memory.forgetEntity(target.getUuidAsString());
    }

    public void rememberProducedDrops(String action, BlockPos origin, Set<String> items) {
        scope();
        if (origin == null) return;
        memory.produced(action, new ResourcePerceptionMemory.Point(origin.getX() + .5,
                origin.getY() + .5, origin.getZ() + .5), items.stream()
                        .map(item -> item.startsWith("minecraft:") ? item.substring(10) : item)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()), System.currentTimeMillis());
    }

    private boolean knownProducedItem(Entity target, ResourcePerceptionMemory.Point position) {
        if (!(target instanceof ItemEntity item) || item.getStack().isEmpty()) return false;
        return memory.producedItem(Registries.ITEM.getId(item.getStack().getItem()).getPath(),
                position, Math.max(0L, item.getItemAge() * 50L), System.currentTimeMillis());
    }

    public boolean observesEntity(Entity target) {
        if (client.player == null || client.world == null || target == null) return false;
        Vec3d eye = client.player.getEyePos();
        return unobstructed(eye, target.getBoundingBox().getCenter())
                || unobstructed(eye, target.getEyePos());
    }

    private boolean observesBlock(BlockPos target) {
        return ResourcePerceptionRay.block(client.world, client.player.getEyePos(), target,
                ShapeContext.of(client.player));
    }

    private boolean unobstructed(Vec3d eye, Vec3d point) {
        return ResourcePerceptionRay.clear(client.world, eye, point, ShapeContext.of(client.player));
    }

    private boolean loaded(BlockPos target) {
        return client.world != null && target != null
                && client.world.getChunkManager().isChunkLoaded(target.getX() >> 4, target.getZ() >> 4);
    }

    private void scope() {
        memory.scope(client.world, client.world == null ? ""
                : client.world.getRegistryKey().getValue().toString());
    }
}
