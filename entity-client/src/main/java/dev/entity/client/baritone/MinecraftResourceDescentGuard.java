package dev.entity.client.baritone;

import dev.entity.client.autonomy.workspace.MinecraftWorkspaceProbe;
import dev.entity.client.baritone.ResourceDescentStep.BlockPosition;
import dev.entity.client.baritone.ResourceDescentStep.CellKind;
import dev.entity.client.baritone.ResourceDescentStep.Decision;
import dev.entity.client.baritone.ResourceDescentStep.Observation;
import dev.entity.client.baritone.ResourceDescentStep.RouteZone;
import dev.entity.client.baritone.ResourceDescentStep.StepRequest;
import dev.entity.client.control.MinecraftActuatorGateway;
import dev.entity.core.stewardship.ProtectedAreaPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Collision-aware final inspection for one Baritone resource-route descent. */
public final class MinecraftResourceDescentGuard {
    public record Verdict(
            boolean applicable,
            boolean authorized,
            Decision decision,
            String routeSignature,
            String detail) {
        public Verdict {
            routeSignature = Objects.requireNonNullElse(routeSignature, "");
            detail = Objects.requireNonNullElse(detail, "");
            if (applicable != (decision != null)) {
                throw new IllegalArgumentException(
                        "an applicable descent verdict must expose its typed decision");
            }
        }

        public boolean veto() {
            return applicable && !authorized;
        }
    }

    public record Snapshot(
            long inspections,
            long authorized,
            long vetoes,
            long verticalShaftVetoes,
            int maximumObservedVerticalLoss,
            String lastState,
            String lastRejection,
            String lastRoute,
            String lastDetail) {
        public Snapshot {
            lastState = Objects.requireNonNullElse(lastState, "idle");
            lastRejection = Objects.requireNonNullElse(lastRejection, "none");
            lastRoute = Objects.requireNonNullElse(lastRoute, "");
            lastDetail = Objects.requireNonNullElse(lastDetail, "");
        }
    }

    private final MinecraftClient client;
    private final MinecraftActuatorGateway actuators;
    private long inspections;
    private long authorized;
    private long vetoes;
    private long verticalShaftVetoes;
    private int maximumObservedVerticalLoss;
    private String lastState = "idle";
    private String lastRejection = "none";
    private String lastRoute = "";
    private String lastDetail = "";

    public MinecraftResourceDescentGuard(
            MinecraftClient client,
            MinecraftActuatorGateway actuators) {
        this.client = Objects.requireNonNull(client, "client");
        this.actuators = Objects.requireNonNull(actuators, "actuators");
    }

    /** Inspects only a descending edge; flat and ascending edges are outside this policy. */
    public synchronized Verdict inspect(
            String routeId,
            String routeSignature,
            BlockPos sourceFeet,
            BlockPos destinationFeet, boolean nativeMutationFree, List<BlockPos> nativeBreaks) {
        Objects.requireNonNull(sourceFeet, "sourceFeet");
        Objects.requireNonNull(destinationFeet, "destinationFeet");
        int verticalLoss = sourceFeet.getY() - destinationFeet.getY();
        if (verticalLoss <= 0) {
            return new Verdict(false, true, null, routeSignature, "not a descent edge");
        }
        BlockPosition source = position(sourceFeet);
        BlockPosition destination = position(destinationFeet);
        boolean waterLanding=client.world!=null&&ResourceDescentSafetyPolicy.ordinaryNativeWaterLanding(
                source,destination,nativeMutationFree,client.world.getFluidState(destinationFeet).isStill(),
                observe(destination).kind(),observe(destination.up()).kind(),observe(destination.down()).kind());
        if(ResourceDescentSafetyPolicy.ordinaryNativeStep(source,destination,nativeMutationFree)||waterLanding) {
            lastState="native_traversal";lastRejection="none";
            lastRoute=Objects.requireNonNullElse(routeSignature,"");
            lastDetail=waterLanding?"existing native water landing needs no mutation; dry excavation policy not applicable"
                    :"existing native step needs no breaking or placing; excavation policy not applicable";
            return new Verdict(false,true,null,routeSignature,lastDetail);
        }
        ArrayList<BlockPosition> proposedBreaks = new ArrayList<>(2);
        for (BlockPos body : nativeBreaks == null ? List.of(destinationFeet, destinationFeet.up()) : nativeBreaks) {
            Observation observation = observe(position(body));
            if (!observation.kind().bodyPassable() && observation.kind().breakable()) {
                proposedBreaks.add(position(body));
            }
        }
        proposedBreaks = new ArrayList<>(ResourceDescentSafetyPolicy.withFallingClearanceColumn(
                source, destination, proposedBreaks, this::observe));
        if (ResourceDescentSafetyPolicy.ordinaryWaterEntry(verticalLoss,
                !proposedBreaks.isEmpty(), observe(destination).kind(),
                observe(destination.up()).kind(), observe(destination.down()).kind())) {
            return new Verdict(false, true, null, routeSignature,
                    "ordinary shallow-water entry; no stair excavation");
        }
        inspections++;
        maximumObservedVerticalLoss = Math.max(maximumObservedVerticalLoss, verticalLoss);
        Decision decision = ResourceDescentSafetyPolicy.evaluate(
                StepRequest.permanentWorksite(
                        Objects.requireNonNullElse(routeId, "resource-route"),
                        source,
                        destination,
                        proposedBreaks),
                cell -> {
                    Observation observation = observe(cell);
                    boolean floor = cell.equals(source.down()) || cell.equals(destination.down());
                    CellKind kind = ResourceDescentSafetyPolicy.supportedFloorKind(
                            observation.kind(), floor,
                            floor && MinecraftWorkspaceProbe.hasStableFullTopSupport(client, block(cell)));
                    return kind == observation.kind() ? observation
                            : new Observation(kind, observation.blockFingerprint(), observation.zone());
                });
        boolean accepted = decision.state() == ResourceDescentStep.DecisionState.AUTHORIZED;
        if (accepted) {
            authorized++;
        } else {
            vetoes++;
            if (decision.rejection() == ResourceDescentStep.Rejection.VERTICAL_SHAFT) {
                verticalShaftVetoes++;
            }
        }
        lastState = decision.state().name().toLowerCase(Locale.ROOT);
        lastRejection = decision.rejection().name().toLowerCase(Locale.ROOT);
        lastRoute = Objects.requireNonNullElse(routeSignature, "");
        lastDetail = decision.detail();
        return new Verdict(true, accepted, decision, routeSignature, decision.detail());
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                inspections,
                authorized,
                vetoes,
                verticalShaftVetoes,
                maximumObservedVerticalLoss,
                lastState,
                lastRejection,
                lastRoute,
                lastDetail);
    }

    private Observation observe(BlockPosition abstractPosition) {
        if (client.world == null) {
            return new Observation(CellKind.UNLOADED, "unloaded", RouteZone.RESOURCE_WORKSITE);
        }
        BlockPos position = block(abstractPosition);
        RouteZone zone = zone(position);
        if (position.getY() < client.world.getBottomY()
                || position.getY() > client.world.getTopYInclusive()
                || !client.world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
            return new Observation(CellKind.UNLOADED, "unloaded", zone);
        }
        BlockState state = client.world.getBlockState(position);
        String fingerprint = stableStateFingerprint(state);
        if (state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) {
            return new Observation(CellKind.LAVA, fingerprint, zone);
        }
        if (state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
            return new Observation(CellKind.WATER, fingerprint, zone);
        }
        if (!state.getFluidState().isEmpty()) {
            return new Observation(CellKind.OTHER_FLUID, fingerprint, zone);
        }
        if (hazard(state)) return new Observation(CellKind.HAZARD, fingerprint, zone);
        if (state.getBlock() instanceof FallingBlock) {
            return new Observation(CellKind.FALLING_BLOCK, fingerprint, zone);
        }
        if (state.getCollisionShape(client.world, position).isEmpty()
                || playerOperablePortal(state)) {
            return new Observation(CellKind.OPEN, fingerprint, zone);
        }
        boolean stable = MinecraftWorkspaceProbe.hasStableFullTopSupport(client, position);
        boolean breakable = safelyBreakable(state, position);
        CellKind kind = stable
                ? breakable ? CellKind.STABLE_BREAKABLE : CellKind.STABLE_UNBREAKABLE
                : breakable ? CellKind.OBSTRUCTING_BREAKABLE : CellKind.OBSTRUCTING_UNBREAKABLE;
        return new Observation(kind, fingerprint, zone);
    }

    private RouteZone zone(BlockPos position) {
        String dimension = currentDimension();
        boolean protectedProperty = actuators.inspectProtectedArea(
                        ProtectedAreaPolicy.Action.BREAK,
                        dimension,
                        position.getX(),
                        position.getY(),
                        position.getZ())
                .map(decision -> !decision.allowed())
                .orElse(false);
        return protectedProperty
                ? RouteZone.PROTECTED_PROPERTY
                : RouteZone.RESOURCE_WORKSITE;
    }

    private boolean safelyBreakable(BlockState state, BlockPos position) {
        if (state.hasBlockEntity() || state.getHardness(client.world, position) < 0.0F) return false;
        if (!state.isToolRequired()) return true;
        if (client.player == null) return false;
        for (var stack : client.player.getInventory().getMainStacks()) {
            if (!stack.isEmpty()
                    && stack.isSuitableFor(state)
                    && (!stack.isDamageable()
                    || stack.getMaxDamage() - stack.getDamage() > 1)) return true;
        }
        return false;
    }

    private static boolean playerOperablePortal(BlockState state) {
        String id = Registries.BLOCK.getId(state.getBlock()).getPath();
        if (state.getBlock() instanceof DoorBlock) return !id.equals("iron_door");
        if (state.getBlock() instanceof TrapdoorBlock) return !id.equals("iron_trapdoor");
        return state.getBlock() instanceof FenceGateBlock;
    }

    private static boolean hazard(BlockState state) {
        return state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.WITHER_ROSE);
    }

    private static String stableStateFingerprint(BlockState state) {
        StringBuilder fingerprint = new StringBuilder(
                Registries.BLOCK.getId(state.getBlock()).toString());
        state.getEntries().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(Properties.OPEN))
                .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
                .forEach(entry -> fingerprint.append('|')
                        .append(entry.getKey().getName()).append('=')
                        .append(entry.getValue()));
        return fingerprint.toString();
    }

    private String currentDimension() {
        return client.world == null
                ? "minecraft:unknown"
                : client.world.getRegistryKey().getValue().toString();
    }

    private static BlockPosition position(BlockPos position) {
        return new BlockPosition(position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos block(BlockPosition position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }
}
