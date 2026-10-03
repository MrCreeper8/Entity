package dev.entity.client.baritone;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.entity.client.baritone.ResourceDescentStep.BlockPosition;
import dev.entity.client.baritone.ResourceDescentStep.CellKind;
import dev.entity.client.baritone.ResourceDescentStep.ChangeDisposition;
import dev.entity.client.baritone.ResourceDescentStep.Decision;
import dev.entity.client.baritone.ResourceDescentStep.MutationLifetime;
import dev.entity.client.baritone.ResourceDescentStep.Observation;
import dev.entity.client.baritone.ResourceDescentStep.OriginalBlock;
import dev.entity.client.baritone.ResourceDescentStep.Rejection;
import dev.entity.client.baritone.ResourceDescentStep.RestorationCommit;
import dev.entity.client.baritone.ResourceDescentStep.RestorationObligation;
import dev.entity.client.baritone.ResourceDescentStep.RestoreReleaseCondition;
import dev.entity.client.baritone.ResourceDescentStep.ReverseTraversal;
import dev.entity.client.baritone.ResourceDescentStep.RouteZone;
import dev.entity.client.baritone.ResourceDescentStep.StepRequest;
import dev.entity.client.baritone.ResourceDescentStep.WorldProbe;

/**
 * Fail-closed geometry and mutation authority for one resource-route stair step.
 *
 * <p>The Minecraft adapter supplies an immutable observation snapshot. This
 * policy then authorizes only a cardinal, reversibly walkable transition whose
 * vertical change is at most one block. Any excavation is restricted to the
 * selected destination clearance column (including native overhead falling
 * clearance); the block supporting the player and the player's
 * current two body cells can never be returned as authorized mutations.</p>
 *
 * <p>Permanent excavation is available only at a resource worksite. A temporary
 * mutation requires a durable, exact restoration commit before this policy
 * returns the break coordinates. The caller must retain the reverse route and
 * may restore those blocks only after the route is released and all bodies are
 * clear.</p>
 */
public final class ResourceDescentSafetyPolicy {
    public static final int MAXIMUM_STAIR_VERTICAL_CHANGE = 1;

    /** Published by one exact native route owner, not a global Baritone setting. */
    public interface NativeOwner {
        boolean entity2$resourceDescentRequired();
        void entity2$resourceDescentRequired(boolean required);
    }

    /**
     * Native dynamicFallCost is entered only below the one-level stair landing.
     * Its water branch bypasses dry/bucket fall limits. Nonzero front mining
     * cost therefore describes exactly the deep excavation the final guard
     * refuses; clear existing water landings keep their normal native cost.
     */
    public static boolean rejectsNativeFallExcavation(Object owner, double frontMiningCost) {
        return owner instanceof NativeOwner nativeOwner
                && nativeOwner.entity2$resourceDescentRequired() && frontMiningCost > 0;
    }
    private static final List<BlockOffset> ADJACENT_OFFSETS = List.of(
            new BlockOffset(1, 0, 0),
            new BlockOffset(-1, 0, 0),
            new BlockOffset(0, 1, 0),
            new BlockOffset(0, -1, 0),
            new BlockOffset(0, 0, 1),
            new BlockOffset(0, 0, -1));

    private ResourceDescentSafetyPolicy() {
    }

    /** Native walking on existing partial-block terrain is not mine excavation. */
    public static boolean ordinaryNativeStep(BlockPosition from,BlockPosition to,boolean mutationFree) {
        return mutationFree && from.y()-to.y()==1
                && Math.abs((long)from.x()-to.x())+Math.abs((long)from.z()-to.z())==1;
    }
    /** Baritone separately admits existing-water landings; this is not a dry shaft or bucket placement. */
    public static boolean ordinaryNativeWaterLanding(BlockPosition from,BlockPosition to,boolean mutationFree,
            boolean stillWater,CellKind feet,CellKind head,CellKind support) {
        return mutationFree && from.y()>to.y()
                && Math.abs((long)from.x()-to.x())+Math.abs((long)from.z()-to.z())==1
                && stillWater && feet==CellKind.WATER && head==CellKind.OPEN
                // Native Fall already validated the water landing. A second water
                // cell supports swimming; requiring dirt here only admits puddles.
                && (support.stableFooting() || support==CellKind.WATER);
    }

    /** Existing shallow water travel is not excavation; native swimming owns it. */
    public static boolean ordinaryWaterEntry(int verticalLoss, boolean excavation,
            CellKind feet, CellKind head, CellKind support) {
        return verticalLoss == 1 && !excavation && feet == CellKind.WATER
                && head == CellKind.OPEN && support.stableFooting();
    }

    public static CellKind supportedFloorKind(CellKind kind, boolean floorCell,
            boolean anchoredColumn) {
        return kind == CellKind.FALLING_BLOCK && floorCell && anchoredColumn
                ? CellKind.STABLE_BREAKABLE : kind;
    }

    /** Native Descend clears three cells top-down, including gravel falling into
     * its top clearance cell. Include that physical column in authority/hazard
     * checks; Baritone still owns all mining and falling-entity waits. */
    public static List<BlockPosition> withFallingClearanceColumn(BlockPosition current,
            BlockPosition next, List<BlockPosition> nativeBreaks, WorldProbe probe) {
        if (current.y()-next.y()!=1 || !nativeBreaks.contains(next.up(2))) return List.copyOf(nativeBreaks);
        LinkedHashSet<BlockPosition> result=new LinkedHashSet<>(nativeBreaks);
        for (BlockPosition at=next.up(3); ; at=at.up()) {
            Observation observation=probe.observe(at);
            if (observation==null || observation.kind()!=CellKind.FALLING_BLOCK) break;
            result.add(at);
        }
        return List.copyOf(result);
    }

    /**
     * The staircase contract belongs to excavating resource routes. Surface
     * harvesting (trees and crops) still uses a Baritone mine operation for
     * block interaction, but its ordinary terrain approach is not a mine
     * descent and may safely use normal walkable drops.
     */
    public static boolean appliesToOperation(
            String operationKind,
            Map<String, String> arguments) {
        String kind = Objects.requireNonNullElse(operationKind, "")
                .trim().toLowerCase(Locale.ROOT);
        if (!Set.of("mine", "acquire", "fetch", "get").contains(kind)) return false;
        String sourceKind = Objects.requireNonNullElse(
                Objects.requireNonNullElse(arguments, Map.<String, String>of())
                        .get("worldSourceKind"), "")
                .trim().toUpperCase(Locale.ROOT);
        return !sourceKind.equals("HARVEST");
    }

    /** Native search must not repeatedly choose falls that this final stair policy rejects. */
    public static int nativeFallLimit(String operationKind, Map<String, String> arguments, int ordinaryLimit) {
        return appliesToOperation(operationKind, arguments)
                ? Math.min(ordinaryLimit, MAXIMUM_STAIR_VERTICAL_CHANGE) : ordinaryLimit;
    }

    /** Evaluates one candidate transition against a single immutable snapshot. */
    public static Decision evaluate(StepRequest request, WorldProbe probe) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(probe, "probe");

        BlockPosition current = request.currentFeet();
        BlockPosition next = request.nextFeet();
        long dx = (long) next.x() - current.x();
        long dz = (long) next.z() - current.z();
        long horizontalDistance = Math.abs(dx) + Math.abs(dz);
        long verticalLoss = (long) current.y() - next.y();

        if (horizontalDistance == 0) {
            return Decision.rejected(
                    Rejection.VERTICAL_SHAFT,
                    "a resource route may not descend in its current x/z column");
        }
        if (horizontalDistance != 1) {
            return Decision.rejected(
                    Rejection.NON_CARDINAL_STEP,
                    "a stair transition must move exactly one horizontal block");
        }
        if (verticalLoss > MAXIMUM_STAIR_VERTICAL_CHANGE) {
            return Decision.rejected(
                    Rejection.DESCENT_TOO_STEEP,
                    "a horizontal stair step may lose at most one y level");
        }
        if (verticalLoss < -MAXIMUM_STAIR_VERTICAL_CHANGE) {
            return Decision.rejected(
                    Rejection.REVERSE_ASCENT_TOO_STEEP,
                    "the reverse route would require climbing more than one y level");
        }

        Set<BlockPosition> proposedBreaks = new LinkedHashSet<>(request.proposedBreaks());
        BlockPosition currentSupport = current.down();
        Set<BlockPosition> currentBody = Set.of(current, current.up());
        if (proposedBreaks.contains(currentSupport)) {
            return Decision.rejected(
                    Rejection.CURRENT_SUPPORT_MUTATION,
                    "the block currently supporting the player is immutable");
        }
        for (BlockPosition occupied : currentBody) {
            if (proposedBreaks.contains(occupied)) {
                return Decision.rejected(
                        Rejection.CURRENT_BODY_MUTATION,
                        "a block intersecting the player's current body is immutable");
            }
        }

        Set<BlockPosition> nextBody = Set.of(next, next.up());
        for (BlockPosition proposedBreak : proposedBreaks) {
            if (proposedBreak.x()!=next.x() || proposedBreak.z()!=next.z()
                    || proposedBreak.y()<next.y()
                    || (proposedBreak.y()>next.y()+1 && verticalLoss!=1)) {
                return Decision.rejected(
                        Rejection.OUT_OF_CORRIDOR_MUTATION,
                        "stair excavation is restricted to the selected destination clearance column");
            }
        }
        if (!proposedBreaks.isEmpty() && !request.resourceMutationAuthorized()) {
            return Decision.rejected(
                    Rejection.RESOURCE_MUTATION_AUTHORITY_REQUIRED,
                    "only an explicit resource operation may excavate a stair step");
        }
        if (!proposedBreaks.isEmpty()
                && request.mutationLifetime() == MutationLifetime.NO_MUTATION) {
            return Decision.rejected(
                    Rejection.MUTATION_LIFETIME_REQUIRED,
                    "proposed excavation requires an explicit mutation lifetime");
        }

        Map<BlockPosition, Observation> observations = observationsFor(
                request, probe, currentSupport, currentBody, nextBody);
        if (observations == null) {
            return Decision.rejected(
                    Rejection.MISSING_WORLD_DATA,
                    "the world probe did not supply every required stair cell");
        }
        for (Map.Entry<BlockPosition, Observation> entry : observations.entrySet()) {
            if (entry.getValue().kind() == CellKind.UNLOADED) {
                return Decision.rejected(
                        Rejection.MISSING_WORLD_DATA,
                        "required stair cell is unloaded at " + entry.getKey());
            }
        }
        for (BlockPosition proposedBreak : proposedBreaks) {
            if (proposedBreak.y()>next.y()+2 &&
                    (observations.get(proposedBreak).kind()!=CellKind.FALLING_BLOCK
                    || !proposedBreaks.contains(proposedBreak.down()))) {
                return Decision.rejected(Rejection.OUT_OF_CORRIDOR_MUTATION,
                        "only the contiguous falling column above native clearance is affected");
            }
        }

        Decision hazard = directHazardDecision(current, next, proposedBreaks, observations);
        if (hazard != null) return hazard;

        if (!stableFooting(observations.get(currentSupport))) {
            return Decision.rejected(
                    Rejection.UNSTABLE_CURRENT_FOOTING,
                    "the current body column has no stable non-falling support");
        }
        BlockPosition nextSupport = next.down();
        if (!stableFooting(observations.get(nextSupport))) {
            return Decision.rejected(
                    Rejection.UNSTABLE_DESTINATION_FOOTING,
                    "the destination body column has no stable non-falling support");
        }
        for (BlockPosition occupied : currentBody) {
            if (!observations.get(occupied).kind().bodyPassable()) {
                return Decision.rejected(
                        Rejection.CURRENT_BODY_NOT_CLEAR,
                        "the current two-block body column is not collision-free");
            }
        }

        for (BlockPosition proposedBreak : request.proposedBreaks()) {
            Observation original = observations.get(proposedBreak);
            if (!original.kind().breakable()) {
                return Decision.rejected(
                        Rejection.BLOCK_NOT_SAFELY_BREAKABLE,
                        "stair body cell is not safely breakable at " + proposedBreak);
            }
            Decision exposure = exposedHazardDecision(
                    proposedBreak, proposedBreaks, observations);
            if (exposure != null) return exposure;
        }
        for (BlockPosition occupied : nextBody) {
            if (!proposedBreaks.contains(occupied)
                    && !observations.get(occupied).kind().bodyPassable()) {
                return Decision.rejected(
                        Rejection.MISSING_BODY_CLEARANCE,
                        "the destination needs two clear body blocks");
            }
        }

        List<OriginalBlock> originals = request.proposedBreaks().stream()
                .map(position -> new OriginalBlock(
                        position,
                        observations.get(position).blockFingerprint()))
                .toList();
        boolean touchesCleanProperty = request.proposedBreaks().stream()
                .map(observations::get)
                .map(Observation::zone)
                .anyMatch(RouteZone::cleanProperty);
        if (touchesCleanProperty
                && request.mutationLifetime() == MutationLifetime.PERMANENT) {
            return Decision.rejected(
                    Rejection.PERMANENT_PROPERTY_EXCAVATION,
                    "protected property may not become permanent excavation");
        }

        ChangeDisposition disposition;
        Optional<RestorationObligation> restoration = Optional.empty();
        if (request.proposedBreaks().isEmpty()) {
            disposition = ChangeDisposition.NO_CHANGE;
        } else if (request.mutationLifetime() == MutationLifetime.TEMPORARY_RESTORED) {
            disposition = ChangeDisposition.RESTORE_AFTER_ROUTE_RELEASE;
            restoration = Optional.of(new RestorationObligation(
                    request.routeId(),
                    originals,
                    RestoreReleaseCondition.ROUTE_RELEASED_AND_ALL_BODIES_CLEAR));
            RestorationCommit expected = RestorationCommit.from(restoration.orElseThrow());
            if (!request.restorationCommit().map(expected::equals).orElse(false)) {
                return Decision.restorationRequired(
                        restoration.orElseThrow(),
                        "persist the exact original blocks before excavation");
            }
        } else {
            disposition = ChangeDisposition.MAY_REMAIN_AT_RESOURCE_WORKSITE;
        }

        ReverseTraversal reverse = new ReverseTraversal(next, current);
        return Decision.authorized(
                request.proposedBreaks(),
                disposition,
                restoration,
                reverse,
                "cardinal stair step retains stable footing, two-block clearance, and a reverse exit");
    }

    private static Map<BlockPosition, Observation> observationsFor(
            StepRequest request,
            WorldProbe probe,
            BlockPosition currentSupport,
            Set<BlockPosition> currentBody,
            Set<BlockPosition> nextBody) {
        LinkedHashSet<BlockPosition> required = new LinkedHashSet<>();
        required.add(currentSupport);
        required.addAll(currentBody);
        required.add(request.currentFeet().up(2));
        required.add(request.nextFeet().down());
        required.addAll(nextBody);
        required.add(request.nextFeet().up(2));
        for (BlockPosition proposedBreak : request.proposedBreaks()) {
            required.add(proposedBreak);
            for (BlockOffset offset : ADJACENT_OFFSETS) {
                required.add(proposedBreak.add(offset.dx(), offset.dy(), offset.dz()));
            }
        }

        LinkedHashMap<BlockPosition, Observation> observations = new LinkedHashMap<>();
        for (BlockPosition position : required) {
            Observation observation = probe.observe(position);
            if (observation == null) return null;
            observations.put(position, observation);
        }
        return Map.copyOf(observations);
    }

    private static Decision directHazardDecision(
            BlockPosition current,
            BlockPosition next,
            Set<BlockPosition> proposedBreaks,
            Map<BlockPosition, Observation> observations) {
        for (BlockPosition body : List.of(
                current,
                current.up(),
                next,
                next.up(),
                current.down(),
                next.down())) {
            // Native MovementDescend clears the selected destination column before
            // stepping and waits for falling entities. Gravel to excavate is not
            // an overhead collapse; unselected material/support retains its guard.
            if (proposedBreaks.contains(body)
                    && observations.get(body).kind() == CellKind.FALLING_BLOCK) continue;
            Decision decision = hazardDecision(observations.get(body), body);
            if (decision != null) return decision;
        }
        for (BlockPosition overhead : List.of(current.up(2), next.up(2))) {
            if (proposedBreaks.contains(overhead)
                    && observations.get(overhead).kind()==CellKind.FALLING_BLOCK) continue;
            Decision decision = hazardDecision(observations.get(overhead), overhead);
            if (decision != null) return decision;
        }
        return null;
    }

    private static Decision exposedHazardDecision(
            BlockPosition broken,
            Set<BlockPosition> proposedBreaks,
            Map<BlockPosition, Observation> observations) {
        for (BlockOffset offset : ADJACENT_OFFSETS) {
            BlockPosition neighbour = broken.add(offset.dx(), offset.dy(), offset.dz());
            if (proposedBreaks.contains(neighbour)) continue;
            Observation observation = observations.get(neighbour);
            if (observation == null || observation.kind() == CellKind.UNLOADED) {
                return Decision.rejected(
                        Rejection.MISSING_WORLD_DATA,
                        "excavation would expose an unobserved cell at " + neighbour);
            }
            if (observation.kind() == CellKind.LAVA) {
                return Decision.rejected(
                        Rejection.LAVA_EXPOSURE,
                        "excavation would expose lava at " + neighbour);
            }
            if (observation.kind().fluid()) {
                return Decision.rejected(
                        Rejection.FLUID_EXPOSURE,
                        "excavation would expose fluid at " + neighbour);
            }
            if (observation.kind() == CellKind.HAZARD) {
                return Decision.rejected(
                        Rejection.HAZARDOUS_CELL,
                        "excavation would expose a hazardous cell at " + neighbour);
            }
            if (offset.dy() == 1 && observation.kind() == CellKind.FALLING_BLOCK) {
                return Decision.rejected(
                        Rejection.FALLING_BLOCK_TRAP,
                        "excavation would release a falling block at " + neighbour);
            }
        }
        return null;
    }

    private static Decision hazardDecision(
            Observation observation,
            BlockPosition position) {
        return switch (observation.kind()) {
            case LAVA -> Decision.rejected(
                    Rejection.LAVA_EXPOSURE,
                    "lava intersects or can fall into the stair at " + position);
            case WATER, OTHER_FLUID -> Decision.rejected(
                    Rejection.FLUID_EXPOSURE,
                    "fluid intersects or can fall into the stair at " + position);
            case FALLING_BLOCK -> Decision.rejected(
                    Rejection.FALLING_BLOCK_TRAP,
                    "falling material makes the stair unsafe at " + position);
            case HAZARD -> Decision.rejected(
                    Rejection.HAZARDOUS_CELL,
                    "a hazardous cell intersects the stair at " + position);
            case UNLOADED -> Decision.rejected(
                    Rejection.MISSING_WORLD_DATA,
                    "the stair snapshot is unloaded at " + position);
            default -> null;
        };
    }

    private static boolean stableFooting(Observation observation) {
        return observation.kind().stableFooting()
                && observation.kind() != CellKind.FALLING_BLOCK
                && !observation.kind().fluid();
    }

    private record BlockOffset(int dx, int dy, int dz) {
    }
}
