package dev.entity.client.autonomy.resource;

import dev.entity.core.stewardship.ResourceStewardshipPolicy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable capability presented at the exact Minecraft block-break boundary. */
public record ResourceBreakScope(
        String operationId,
        ResourceActuationSession.SourceKind sourceKind,
        ResourceStewardshipPolicy.Intent targetIntent,
        ResourceActuationSession.Coordinate exactTarget,
        String exactTargetBlockId,
        Set<String> objectiveBlockIds,
        Set<String> reservedReplantItemIds,
        Set<ResourceActuationSession.Coordinate> committedNaturalTreeTargets,
        Map<ResourceActuationSession.Coordinate, String> committedNaturalTreeAccessBreaks,
        String managedFarmAreaName,
        String managedFarmAreaFingerprint,
        boolean retainedNaturalTreeProof) {
    public ResourceBreakScope {
        operationId = required(operationId, "operation id");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        targetIntent = Objects.requireNonNull(targetIntent, "targetIntent");
        exactTarget = Objects.requireNonNull(exactTarget, "exactTarget");
        exactTargetBlockId = ResourceActuationSession.normalizeId(exactTargetBlockId);
        objectiveBlockIds = normalize(objectiveBlockIds);
        reservedReplantItemIds = normalize(reservedReplantItemIds);
        committedNaturalTreeTargets = Set.copyOf(Objects.requireNonNull(
                committedNaturalTreeTargets, "committedNaturalTreeTargets"));
        committedNaturalTreeAccessBreaks = normalizeCoordinateIds(
                committedNaturalTreeAccessBreaks);
        managedFarmAreaName = Objects.requireNonNullElse(
                managedFarmAreaName, "").trim().toLowerCase(java.util.Locale.ROOT);
        managedFarmAreaFingerprint = Objects.requireNonNullElse(
                managedFarmAreaFingerprint, "").trim();
        if (managedFarmAreaName.isEmpty() != managedFarmAreaFingerprint.isEmpty()) {
            throw new IllegalArgumentException(
                    "managed farm area name and fingerprint must be supplied together");
        }
        if (!managedFarmAreaName.isEmpty()
                && (sourceKind != ResourceActuationSession.SourceKind.HARVEST
                || targetIntent != ResourceStewardshipPolicy.Intent.HARVEST_CROP)) {
            throw new IllegalArgumentException(
                    "managed farm authority applies only to an exact crop harvest");
        }
        if (!objectiveBlockIds.contains(exactTargetBlockId)) {
            throw new IllegalArgumentException(
                    "exact resource target must match the active objective");
        }
        if (sourceKind == ResourceActuationSession.SourceKind.MINE
                && targetIntent != ResourceStewardshipPolicy.Intent.MINE) {
            throw new IllegalArgumentException("mining scope requires mine intent");
        }
        if (sourceKind == ResourceActuationSession.SourceKind.HARVEST
                && targetIntent == ResourceStewardshipPolicy.Intent.MINE) {
            throw new IllegalArgumentException("harvesting scope requires harvest intent");
        }
        if (retainedNaturalTreeProof
                && (sourceKind != ResourceActuationSession.SourceKind.HARVEST
                || targetIntent != ResourceStewardshipPolicy.Intent.HARVEST_LOG)) {
            throw new IllegalArgumentException(
                    "retained natural-tree proof requires an exact harvest-log scope");
        }
        if (!committedNaturalTreeTargets.isEmpty()) {
            if (sourceKind != ResourceActuationSession.SourceKind.HARVEST
                    || targetIntent != ResourceStewardshipPolicy.Intent.HARVEST_LOG
                    || !committedNaturalTreeTargets.contains(exactTarget)) {
                throw new IllegalArgumentException(
                        "a committed natural tree requires harvest-log authority containing the exact target");
            }
            for (ResourceActuationSession.Coordinate coordinate : committedNaturalTreeTargets) {
                if (!coordinate.dimension().equals(exactTarget.dimension())) {
                    throw new IllegalArgumentException(
                            "a committed natural tree cannot cross dimensions");
                }
            }
        }
        if (!committedNaturalTreeAccessBreaks.isEmpty()) {
            if (committedNaturalTreeTargets.isEmpty()
                    || sourceKind != ResourceActuationSession.SourceKind.HARVEST
                    || targetIntent != ResourceStewardshipPolicy.Intent.HARVEST_LOG) {
                throw new IllegalArgumentException(
                        "tree access breaks require one committed natural-tree harvest");
            }
            for (ResourceActuationSession.Coordinate coordinate
                    : committedNaturalTreeAccessBreaks.keySet()) {
                if (!coordinate.dimension().equals(exactTarget.dimension())) {
                    throw new IllegalArgumentException(
                            "a committed tree access budget cannot cross dimensions");
                }
                if (committedNaturalTreeTargets.contains(coordinate)) {
                    throw new IllegalArgumentException(
                            "tree access cells cannot overlap committed logs");
                }
            }
        }
    }

    public ResourceBreakScope(
            String operationId,
            ResourceActuationSession.SourceKind sourceKind,
            ResourceStewardshipPolicy.Intent targetIntent,
            ResourceActuationSession.Coordinate exactTarget,
            String exactTargetBlockId,
            Set<String> objectiveBlockIds,
            Set<String> reservedReplantItemIds,
            Set<ResourceActuationSession.Coordinate> committedNaturalTreeTargets,
            Map<ResourceActuationSession.Coordinate, String> committedNaturalTreeAccessBreaks,
            boolean retainedNaturalTreeProof) {
        this(operationId, sourceKind, targetIntent, exactTarget,
                exactTargetBlockId, objectiveBlockIds, reservedReplantItemIds,
                committedNaturalTreeTargets, committedNaturalTreeAccessBreaks,
                "", "", retainedNaturalTreeProof);
    }

    public ResourceBreakScope(
            String operationId,
            ResourceActuationSession.SourceKind sourceKind,
            ResourceStewardshipPolicy.Intent targetIntent,
            ResourceActuationSession.Coordinate exactTarget,
            String exactTargetBlockId,
            Set<String> objectiveBlockIds,
            Set<String> reservedReplantItemIds,
            Set<ResourceActuationSession.Coordinate> committedNaturalTreeTargets,
            boolean retainedNaturalTreeProof) {
        this(operationId, sourceKind, targetIntent, exactTarget,
                exactTargetBlockId, objectiveBlockIds, reservedReplantItemIds,
                committedNaturalTreeTargets, Map.of(), "", "", retainedNaturalTreeProof);
    }

    public boolean managedFarmCropTarget() {
        return !managedFarmAreaName.isEmpty()
                && sourceKind == ResourceActuationSession.SourceKind.HARVEST
                && targetIntent == ResourceStewardshipPolicy.Intent.HARVEST_CROP;
    }

    public boolean exactTarget(
            ResourceActuationSession.Coordinate coordinate,
            String blockId) {
        return exactTarget.equals(coordinate)
                && exactTargetBlockId.equals(
                ResourceActuationSession.normalizeId(blockId));
    }

    /** Any exact log in the one initially classified tree is a Baritone target. */
    public boolean committedNaturalTreeTarget(
            ResourceActuationSession.Coordinate coordinate,
            String blockId) {
        return committedNaturalTreeTargets.contains(coordinate)
                && exactTargetBlockId.equals(
                ResourceActuationSession.normalizeId(blockId));
    }

    /** Exact natural leaf admitted only to expose this committed tree's logs. */
    public boolean committedNaturalTreeAccessTarget(
            ResourceActuationSession.Coordinate coordinate,
            String blockId) {
        String expected = committedNaturalTreeAccessBreaks.get(coordinate);
        return expected != null
                && expected.equals(ResourceActuationSession.normalizeId(blockId));
    }

    /**
     * True only for the resource objective that must be revalidated at the
     * physical boundary. Any other block is route terrain; this scope never
     * turns it into implicit protected property.
     */
    public boolean requiresResourceClassification(
            ResourceActuationSession.Coordinate coordinate,
            String blockId) {
        if (sourceKind == ResourceActuationSession.SourceKind.MINE) {
            return exactTarget(coordinate, blockId);
        }
        return exactTarget(coordinate, blockId)
                || committedNaturalTreeTarget(coordinate, blockId)
                || committedNaturalTreeAccessTarget(coordinate, blockId);
    }

    private static Set<String> normalize(Set<String> values) {
        Objects.requireNonNull(values, "values");
        return values.stream()
                .map(ResourceActuationSession::normalizeId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<ResourceActuationSession.Coordinate, String> normalizeCoordinateIds(
            Map<ResourceActuationSession.Coordinate, String> values) {
        Objects.requireNonNull(values, "committedNaturalTreeAccessBreaks");
        LinkedHashMap<ResourceActuationSession.Coordinate, String> normalized =
                new LinkedHashMap<>();
        values.forEach((coordinate, blockId) -> normalized.put(
                Objects.requireNonNull(coordinate, "tree access coordinate"),
                ResourceActuationSession.normalizeId(blockId)));
        return Map.copyOf(normalized);
    }

    private static String required(String value, String label) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return normalized;
    }
}
