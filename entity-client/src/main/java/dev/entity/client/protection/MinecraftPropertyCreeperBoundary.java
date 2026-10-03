package dev.entity.client.protection;

import dev.entity.core.stewardship.PropertyCreeperSafetyPolicy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.mob.CreeperEntity;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** Loaded-client adapter for the pure selected-property creeper boundary. */
public final class MinecraftPropertyCreeperBoundary {
    private final MinecraftClient client;
    private final ProtectedAreaClientState protectedAreas;
    private final Supplier<Collection<PropertyCreeperSafetyPolicy.Region>> workContexts;

    public MinecraftPropertyCreeperBoundary(
            MinecraftClient client,
            ProtectedAreaClientState protectedAreas) {
        this(client, protectedAreas, List::of);
    }

    public MinecraftPropertyCreeperBoundary(
            MinecraftClient client, ProtectedAreaClientState protectedAreas,
            Supplier<Collection<PropertyCreeperSafetyPolicy.Region>> workContexts) {
        this.client = Objects.requireNonNull(client, "client");
        this.protectedAreas = Objects.requireNonNull(protectedAreas, "protectedAreas");
        this.workContexts = Objects.requireNonNull(workContexts, "workContexts");
    }

    public PropertyCreeperSafetyPolicy.Decision decide(CreeperEntity creeper) {
        Objects.requireNonNull(creeper, "creeper");
        ProtectedAreaClientState.PropertyRegionsObservation property =
                protectedAreas.observePropertyRegions();
        ArrayList<PropertyCreeperSafetyPolicy.Region> regions = new ArrayList<>();
        property.areas().stream()
                .map(PropertyCreeperSafetyPolicy.Region::named)
                .forEach(regions::add);
        // This local positional view never enters ProtectedAreaClientState or the mutation gate.
        regions.addAll(workContexts.get());

        String dimension = client.world == null
                ? "minecraft:unavailable"
                : client.world.getRegistryKey().getValue().toString();
        double playerX = client.player == null ? creeper.getX() : client.player.getX();
        double playerZ = client.player == null ? creeper.getZ() : client.player.getZ();
        return PropertyCreeperSafetyPolicy.decide(
                PropertyCreeperSafetyPolicy.Observation.of(
                        property.available() && client.world != null && client.player != null,
                        regions,
                        dimension,
                        playerX, playerZ,
                        creeper.getX(), creeper.getZ(),
                        creeper.isCharged()));
    }
}
