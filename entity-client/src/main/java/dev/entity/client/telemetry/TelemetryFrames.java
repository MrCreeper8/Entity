package dev.entity.client.telemetry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.entity.client.baritone.AquaticTravelPolicy;
import dev.entity.client.baritone.FabricBaritonePort;
import dev.entity.core.EntityCore;
import net.minecraft.client.MinecraftClient;

public final class TelemetryFrames {
    private TelemetryFrames() {
    }

    public static JsonObject snapshot(
            MinecraftClient client,
            EntityCore.TickDecision decision,
            FabricBaritonePort.Snapshot baritone,
            String why) {
        return snapshot(client, decision, baritone, null, why);
    }

    public static JsonObject snapshot(
            MinecraftClient client,
            EntityCore.TickDecision decision,
            FabricBaritonePort.Snapshot baritone,
            AquaticTravelPolicy.Snapshot survivalAquatic,
            String why) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "telemetry");
        frame.addProperty("schema", 2);
        frame.addProperty("timestamp", System.currentTimeMillis());
        frame.addProperty("state", decision == null ? "starting" : decision.layer().name().toLowerCase());
        frame.addProperty("message", decision == null ? "Starting Entity 2" : decision.reason());
        frame.addProperty("task", decision == null || decision.mission() == null
                ? null : decision.mission().kind());
        frame.addProperty("target", decision == null || decision.mission() == null
                ? null : decision.mission().parameters().getOrDefault("player", null));

        var player = client.player;
        if (player != null) {
            frame.addProperty("health", player.getHealth());
            frame.addProperty("food", player.getHungerManager().getFoodLevel());
            frame.addProperty("air", player.getAir());
            frame.addProperty("inLava", player.isInLava());
            frame.addProperty("insideWall", player.isInsideWall());
            frame.addProperty("headSubmerged", player.isSubmergedInWater());
            frame.addProperty("swimmingPose", player.isSwimming());
            frame.addProperty("horizontalSpeed", Math.hypot(
                    player.getVelocity().x, player.getVelocity().z));
            frame.addProperty("sprintEligible",
                    player.getHungerManager().getFoodLevel() > 6 || player.getAbilities().flying);
            JsonObject position = new JsonObject();
            position.addProperty("x", player.getX());
            position.addProperty("y", player.getY());
            position.addProperty("z", player.getZ());
            position.addProperty("dimension", client.world == null
                    ? "unknown"
                    : client.world.getRegistryKey().getValue().toString());
            frame.add("position", position);
        }

        JsonObject mission = new JsonObject();
        if (decision != null && decision.mission() != null) {
            mission.addProperty("id", decision.mission().id());
            mission.addProperty("kind", decision.mission().kind());
            mission.addProperty("status", decision.mission().state().name().toLowerCase());
            mission.addProperty("phase", decision.mission().phase());
            mission.addProperty("failures", decision.mission().transientFailures());
            mission.addProperty("interruptions", decision.mission().interruptions());
        }
        frame.add("mission", mission);

        JsonObject pathing = new JsonObject();
        pathing.addProperty("missionId", baritone.missionId());
        pathing.addProperty("operation", baritone.operation());
        pathing.addProperty("pathing", baritone.pathing());
        pathing.addProperty("pathEvent", baritone.pathEvent());
        pathing.addProperty("aquaticCustody", baritone.aquaticCustody());
        pathing.addProperty("aquaticMode", baritone.aquaticMode());
        pathing.addProperty("aquaticOwner", baritone.aquaticOwner());
        pathing.addProperty("aquaticModeTicks", baritone.aquaticModeTicks());
        pathing.addProperty("aquaticStallTicks", baritone.aquaticStallTicks());
        pathing.addProperty("aquaticRecoveryAttempts", baritone.aquaticRecoveryAttempts());
        pathing.addProperty("observedSwimTicks", baritone.observedSwimTicks());
        pathing.addProperty("maximumObservedAquaticSpeed",
                baritone.maximumObservedAquaticSpeed());
        pathing.addProperty("aquaticReason", baritone.aquaticReason());
        pathing.addProperty("aquaticHeadSubmerged", baritone.aquaticHeadSubmerged());
        pathing.addProperty("aquaticSwimmingPose", baritone.aquaticSwimmingPose());
        pathing.addProperty("aquaticAir", baritone.aquaticAir());
        pathing.addProperty("aquaticMaximumAir", baritone.aquaticMaximumAir());
        pathing.addProperty("observedAquaticSpeed", baritone.observedAquaticSpeed());
        pathing.addProperty("aquaticSurfaceTransitions", baritone.aquaticSurfaceTransitions());
        pathing.addProperty("aquaticRediveTransitions", baritone.aquaticRediveTransitions());
        pathing.addProperty("aquaticShoreTransitions", baritone.aquaticShoreTransitions());
        pathing.addProperty("aquaticSprintEligible", baritone.aquaticSprintEligible());
        pathing.addProperty("movementSampleExact", baritone.movementSampleExact());
        pathing.addProperty("movementSampleDetail", baritone.movementSampleDetail());
        pathing.addProperty("operationGeneration", baritone.operationGeneration());
        pathing.addProperty("operationStartedAt", baritone.operationStartedAt());
        pathing.addProperty("completedWorkUnits", baritone.completedWorkUnits());
        pathing.addProperty("miningProcessStarts", baritone.miningProcessStarts());
        pathing.addProperty("miningProcessRestarts", baritone.miningProcessRestarts());
        pathing.addProperty("breakingTarget", baritone.breakingTarget());
        pathing.addProperty("routeSignature", baritone.routeSignature());
        pathing.addProperty("trackedRouteSupports", baritone.trackedRouteSupports());
        pathing.addProperty("protectedRouteSupports", baritone.protectedRouteSupports());
        pathing.addProperty("dependentRouteSupports", baritone.dependentRouteSupports());
        pathing.addProperty("routeSupportPlacements", baritone.routeSupportPlacements());
        pathing.addProperty("routeSupportMaximumBodyRise",
                baritone.routeSupportMaximumBodyRise());
        pathing.addProperty("routeSupportSafeReleases", baritone.routeSupportSafeReleases());
        pathing.addProperty("routeSupportDeniedBreaks", baritone.routeSupportDeniedBreaks());
        pathing.addProperty("routeSupportPlacementRollbacks",
                baritone.routeSupportPlacementRollbacks());
        pathing.addProperty("routeSupportPrematureRemovals",
                baritone.routeSupportPrematureRemovals());
        pathing.addProperty("routeSupportCapacityTrips", baritone.routeSupportCapacityTrips());
        JsonArray protectedSupports = new JsonArray();
        baritone.protectedRouteSupportCoordinates().forEach(protectedSupports::add);
        pathing.add("protectedRouteSupportCoordinates", protectedSupports);
        pathing.addProperty("doorPassageRevision", baritone.doorPassageRevision());
        pathing.addProperty("doorPassagePhase", baritone.doorPassagePhase());
        pathing.addProperty("doorPassageCoordinate", baritone.doorPassageCoordinate());
        pathing.addProperty(
                "doorPassageRestorationDue", baritone.doorPassageRestorationDue());
        pathing.addProperty("homeInteriorRevision", baritone.homeInteriorRevision());
        pathing.addProperty("homeInteriorOutcome", baritone.homeInteriorOutcome());
        pathing.addProperty("homeInteriorDimension", baritone.homeInteriorDimension());
        pathing.addProperty("homeInteriorAnchorX", baritone.homeInteriorAnchorX());
        pathing.addProperty("homeInteriorAnchorY", baritone.homeInteriorAnchorY());
        pathing.addProperty("homeInteriorAnchorZ", baritone.homeInteriorAnchorZ());
        pathing.addProperty("homeInteriorSampledBlocks", baritone.homeInteriorSampledBlocks());
        pathing.addProperty("homeInteriorRooms", baritone.homeInteriorRooms());
        pathing.addProperty(
                "homeInteriorRoomToRoomPortals", baritone.homeInteriorRoomToRoomPortals());
        pathing.addProperty("homeInteriorEgressPortals", baritone.homeInteriorEgressPortals());
        pathing.addProperty("homeInteriorHomeRegion", baritone.homeInteriorHomeRegion());
        pathing.addProperty("functionalRouteRevision", baritone.functionalRouteRevision());
        pathing.addProperty("functionalRouteCount", baritone.functionalRouteCount());
        pathing.addProperty(
                "functionalRouteRecordedCells", baritone.functionalRouteRecordedCells());
        pathing.addProperty("functionalRouteId", baritone.functionalRouteId());
        pathing.addProperty("functionalRoutePurpose", baritone.functionalRoutePurpose());
        pathing.addProperty("functionalRouteStatus", baritone.functionalRouteStatus());
        pathing.addProperty(
                "functionalRouteEdgesIssued", baritone.functionalRouteEdgesIssued());
        pathing.addProperty(
                "functionalRouteEdgesAccepted", baritone.functionalRouteEdgesAccepted());
        pathing.addProperty(
                "functionalRouteRevalidations", baritone.functionalRouteRevalidations());
        pathing.addProperty("functionalRouteFallbacks", baritone.functionalRouteFallbacks());
        pathing.addProperty("functionalRouteDetail", baritone.functionalRouteDetail());
        pathing.addProperty(
                "resourceDescentInspections", baritone.resourceDescentInspections());
        pathing.addProperty("resourceDescentAuthorized", baritone.resourceDescentAuthorized());
        pathing.addProperty("resourceDescentVetoes", baritone.resourceDescentVetoes());
        pathing.addProperty(
                "resourceDescentVerticalShaftVetoes",
                baritone.resourceDescentVerticalShaftVetoes());
        pathing.addProperty(
                "resourceDescentMaximumVerticalLoss",
                baritone.resourceDescentMaximumVerticalLoss());
        pathing.addProperty("resourceDescentState", baritone.resourceDescentState());
        pathing.addProperty("resourceDescentRejection", baritone.resourceDescentRejection());
        pathing.addProperty("resourceDescentRoute", baritone.resourceDescentRoute());
        pathing.addProperty("resourceDescentDetail", baritone.resourceDescentDetail());
        pathing.addProperty("combatStrikeSequence", baritone.combatStrikeSequence());
        JsonArray combatAttacks = new JsonArray();
        for (var attack : baritone.combatAttackEvents()) {
            JsonObject event = new JsonObject();
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
            combatAttacks.add(event);
        }
        pathing.add("combatAttackEvents", combatAttacks);
        JsonArray protectedThrowaways = new JsonArray();
        baritone.protectedThrowawayItems().stream().sorted()
                .forEach(protectedThrowaways::add);
        pathing.add("protectedThrowawayItems", protectedThrowaways);
        if (Double.isFinite(baritone.estimatedTicksRemaining())) {
            pathing.addProperty("estimatedTicksRemaining", baritone.estimatedTicksRemaining());
        }
        frame.add("baritone", pathing);

        if (survivalAquatic != null) {
            JsonObject aquatic = new JsonObject();
            aquatic.addProperty("mode", survivalAquatic.mode().name().toLowerCase());
            aquatic.addProperty("ownsMovement", survivalAquatic.ownsMovement());
            aquatic.addProperty("modeTicks", survivalAquatic.modeTicks());
            aquatic.addProperty("stalledTicks", survivalAquatic.stalledTicks());
            aquatic.addProperty("recoveryAttempts", survivalAquatic.recoveryAttempts());
            aquatic.addProperty("reason", survivalAquatic.reason());
            aquatic.addProperty("headSubmerged", survivalAquatic.headSubmerged());
            aquatic.addProperty("swimmingPose", survivalAquatic.swimming());
            aquatic.addProperty("air", survivalAquatic.air());
            aquatic.addProperty("maximumAir", survivalAquatic.maximumAir());
            aquatic.addProperty("horizontalSpeed", survivalAquatic.horizontalSpeed());
            aquatic.addProperty("surfaceTransitions", survivalAquatic.surfaceTransitions());
            aquatic.addProperty("rediveTransitions", survivalAquatic.rediveTransitions());
            aquatic.addProperty("shoreTransitions", survivalAquatic.shoreTransitions());
            aquatic.addProperty("sprintEligible", survivalAquatic.sprintEligible());
            frame.add("survivalAquatic", aquatic);
        }
        frame.addProperty("why", why);
        return frame;
    }
}
