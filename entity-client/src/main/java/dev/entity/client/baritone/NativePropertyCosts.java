package dev.entity.client.baritone;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable property-column costs published by the owner for asynchronous native planning. */
public final class NativePropertyCosts {
    public record Region(int minX, int maxX, int minZ, int maxZ) {
        public Region {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Inverted property bounds");
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    public record Coordinate(int x, int y, int z) { }
    /** State IDs are immutable registry facts, not worker-thread reads of ClientWorld. */
    public record BuildCell(int beforeState, int desiredState, boolean desiredAir, boolean exactPlacementOnly,
                            boolean temporarySupportAllowed,int ownedTemporaryState) {
        public BuildCell(int beforeState,int desiredState,boolean desiredAir,boolean exactPlacementOnly) {
            this(beforeState,desiredState,desiredAir,exactPlacementOnly,false,-1);
        }
    }
    public record Blueprint(String projectId, String digest, String operationId, long controlEpoch,
                            Map<Coordinate, BuildCell> cells, java.util.Set<Integer> naturalSoilStates,
                            Map<Integer,Coordinate> bedPartnerOffsets) {
        public Blueprint(String projectId,String digest,String operationId,long controlEpoch,Map<Coordinate,BuildCell> cells,
                java.util.Set<Integer> naturalSoilStates) {
            this(projectId,digest,operationId,controlEpoch,cells,naturalSoilStates,Map.of());
        }
        public Blueprint(String projectId,String digest,String operationId,long controlEpoch,Map<Coordinate,BuildCell> cells) {
            this(projectId,digest,operationId,controlEpoch,cells,java.util.Set.of(),Map.of());
        }
        public Blueprint {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(operationId, "operationId");
            cells = Map.copyOf(Objects.requireNonNull(cells, "cells"));
            naturalSoilStates = java.util.Set.copyOf(Objects.requireNonNull(naturalSoilStates, "naturalSoilStates"));
            bedPartnerOffsets = Map.copyOf(Objects.requireNonNull(bedPartnerOffsets,"bedPartnerOffsets"));
        }
        public boolean matches(String operation, long epoch) {
            return operationId.equals(operation) && controlEpoch == epoch;
        }
    }

    private record Snapshot(boolean enabled, boolean available, List<Region> regions, Blueprint blueprint) { }
    private static final Snapshot DISABLED = new Snapshot(false, false, List.of(), null);
    private static volatile Snapshot snapshot = DISABLED;

    private NativePropertyCosts() { }

    /** Every owning native route enables this in either mode; regions are explicit selections. */
    public static void publish(boolean enabled, boolean available, List<Region> regions) {
        Snapshot next = new Snapshot(enabled, available, List.copyOf(Objects.requireNonNull(regions, "regions")), null);
        if (!next.equals(snapshot)) snapshot = next;
    }

    /** The caller verifies this exact build operation's live lease before publication. */
    public static void publishBlueprint(boolean available, List<Region> regions, Blueprint blueprint) {
        Snapshot next = new Snapshot(true, available && blueprint != null,
                List.copyOf(Objects.requireNonNull(regions, "regions")), blueprint);
        if (!next.equals(snapshot)) snapshot = next;
    }

    /** Existing property selections cover the full Y column, not inferred Home footprints. */
    public static boolean forbidden(int x, int y, int z) {
        Snapshot current = snapshot;
        return propertyForbidden(current, x, z);
    }

    public static boolean forbiddenPlacement(int x, int y, int z, int currentState, boolean replaceable) {
        Snapshot current = snapshot;
        if (!current.enabled()) return false;
        if (!current.available()) return true;
        BuildCell cell = buildCell(current, x, y, z);
        if (cell != null) {
            if(cell.desiredAir())return !cell.temporarySupportAllowed()||!replaceable
                    ||!sameState(current,currentState,cell.beforeState())&&!sameState(current,currentState,cell.desiredState());
            // Non-full/oriented design cells use the exact builder action, never
            // generic route scaffolding which the final placement gate rejects.
            return cell.exactPlacementOnly() || sameState(current,currentState,cell.desiredState())
                    || !(replaceable || sameState(current,currentState,cell.beforeState()));
        }
        return propertyForbidden(current, x, z);
    }

    public static boolean forbiddenBreak(int x, int y, int z, int currentState, boolean hasBlockEntity) {
        Snapshot current = snapshot;
        if (!current.enabled()) return false;
        if (!current.available()) return true;
        BuildCell cell = buildCell(current, x, y, z);
        if (cell != null) {
            if(!hasBlockEntity&&cell.ownedTemporaryState()>=0&&sameState(current,currentState,cell.ownedTemporaryState()))return false;
            // The confirmed exact footprint includes repairing later player edits.
            // Containers and matching structure stay intact; native hardness/fluid
            // costs and the final live client/Paper gates still apply.
            Coordinate partner=current.blueprint().bedPartnerOffsets().get(currentState);
            boolean pairedBed=partner!=null&&buildCell(current,x+partner.x(),y+partner.y(),z+partner.z())!=null;
            return hasBlockEntity&&!pairedBed || sameState(current,currentState,cell.desiredState());
        }
        return propertyForbidden(current, x, z);
    }

    private static boolean sameState(Snapshot current,int actual,int expected) {
        return actual==expected || current.blueprint()!=null
                && current.blueprint().naturalSoilStates().contains(actual)
                && current.blueprint().naturalSoilStates().contains(expected);
    }

    private static BuildCell buildCell(Snapshot current, int x, int y, int z) {
        return current.blueprint() == null ? null : current.blueprint().cells().get(new Coordinate(x, y, z));
    }

    private static boolean propertyForbidden(Snapshot current, int x, int z) {
        if (!current.enabled()) return false;
        if (!current.available()) return true;
        for (Region region : current.regions()) {
            if (region.contains(x, z)) return true;
        }
        return false;
    }

    public static void clear() { snapshot = DISABLED; }
}
