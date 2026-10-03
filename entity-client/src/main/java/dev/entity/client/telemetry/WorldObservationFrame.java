package dev.entity.client.telemetry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Stable black-box contract for bounded client-loaded world observations. */
public final class WorldObservationFrame {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_HAZARD_ENTRIES = 8;
    public static final int MAX_HOSTILE_ENTRIES = 12;
    public static final int MAX_ENCODED_BYTES = 8_192;

    private static final int HASH_BYTES = 8;

    private WorldObservationFrame() {
    }

    /**
     * The first five components are the original schema and remain at their original JSON paths.
     * The five-argument constructor keeps source compatibility for existing producers and tests.
     */
    public record Snapshot(
            int nearbyPigCount,
            int nearbyCoalOreCount,
            boolean scanComplete,
            int horizontalRadius,
            int verticalRadius,
            Environment environment,
            Headroom headroom,
            CollisionNeighborhood collisionNeighborhood,
            HazardScan hazardScan,
            HostileScan hostileScan) {
        public Snapshot(
                int nearbyPigCount,
                int nearbyCoalOreCount,
                boolean scanComplete,
                int horizontalRadius,
                int verticalRadius) {
            this(
                    nearbyPigCount,
                    nearbyCoalOreCount,
                    scanComplete,
                    horizontalRadius,
                    verticalRadius,
                    Environment.unavailable(),
                    Headroom.unavailable(),
                    CollisionNeighborhood.unavailable(),
                    HazardScan.unavailable(horizontalRadius, verticalRadius),
                    HostileScan.unavailable());
        }

        public Snapshot {
            if (nearbyPigCount < 0 || nearbyCoalOreCount < 0) {
                throw new IllegalArgumentException("observation counts cannot be negative");
            }
            if (horizontalRadius < 0 || verticalRadius < 0) {
                throw new IllegalArgumentException("observation radii cannot be negative");
            }
            environment = Objects.requireNonNullElse(environment, Environment.unavailable());
            headroom = Objects.requireNonNullElse(headroom, Headroom.unavailable());
            collisionNeighborhood = Objects.requireNonNullElse(
                    collisionNeighborhood, CollisionNeighborhood.unavailable());
            hazardScan = Objects.requireNonNullElse(
                    hazardScan, HazardScan.unavailable(horizontalRadius, verticalRadius));
            hostileScan = Objects.requireNonNullElse(hostileScan, HostileScan.unavailable());
        }
    }

    public record Environment(
            boolean available,
            boolean skyVisible,
            boolean day,
            boolean night,
            boolean dimensionHasSkyLight,
            int skyLight,
            int blockLight) {
        public Environment {
            if (skyLight < -1 || skyLight > 15 || blockLight < -1 || blockLight > 15) {
                throw new IllegalArgumentException("light values must be unknown (-1) or in [0, 15]");
            }
        }

        public static Environment unavailable() {
            return new Environment(false, false, false, false, false, -1, -1);
        }
    }

    /** Column offsets start at the block containing the player's feet. */
    public record Headroom(
            boolean available,
            boolean scanComplete,
            int scanLimitBlocks,
            int clearColumnBlocks,
            boolean ceilingFound,
            int ceilingOffsetBlocks,
            boolean feetColliding,
            boolean headColliding,
            boolean aboveHeadColliding,
            String spaceClass) {
        public Headroom {
            if (scanLimitBlocks < 0 || clearColumnBlocks < 0
                    || clearColumnBlocks > scanLimitBlocks) {
                throw new IllegalArgumentException("invalid headroom bounds");
            }
            if (ceilingOffsetBlocks < -1 || ceilingOffsetBlocks >= scanLimitBlocks) {
                throw new IllegalArgumentException("invalid ceiling offset");
            }
            if (ceilingFound != (ceilingOffsetBlocks >= 0)) {
                throw new IllegalArgumentException("ceiling presence and offset disagree");
            }
            spaceClass = boundedText(spaceClass, 32);
        }

        public static Headroom unavailable() {
            return new Headroom(
                    false, false, 0, 0, false, -1,
                    false, false, false, "unknown");
        }
    }

    /** Compact collision counts for the 3x3 feet/head/above-head neighborhood. */
    public record CollisionNeighborhood(
            boolean available,
            boolean scanComplete,
            int horizontalRadius,
            int sampledCells,
            int collidingCells,
            int feetLayerColliding,
            int headLayerColliding,
            int aboveHeadLayerColliding,
            int floorSupportCells,
            int openHorizontalDirections,
            int enclosedHorizontalDirections) {
        public CollisionNeighborhood {
            if (horizontalRadius < 0 || sampledCells < 0 || collidingCells < 0
                    || feetLayerColliding < 0 || headLayerColliding < 0
                    || aboveHeadLayerColliding < 0 || floorSupportCells < 0
                    || openHorizontalDirections < 0 || openHorizontalDirections > 4
                    || enclosedHorizontalDirections < 0 || enclosedHorizontalDirections > 4
                    || openHorizontalDirections + enclosedHorizontalDirections > 4) {
                throw new IllegalArgumentException("invalid collision-neighborhood summary");
            }
            if (collidingCells > sampledCells) {
                throw new IllegalArgumentException("colliding cells exceed sampled cells");
            }
        }

        public static CollisionNeighborhood unavailable() {
            return new CollisionNeighborhood(
                    false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record Hazard(String type, double distance) {
        public Hazard {
            type = boundedText(type, 64);
            distance = finiteNonNegative(distance, "hazard distance");
        }
    }

    public record HazardScan(
            boolean scanComplete,
            int horizontalRadius,
            int verticalRadius,
            int matchedBlockCount,
            int distinctTypeCount,
            int maximumEntries,
            String allEntriesHash,
            List<Hazard> nearest) {
        public HazardScan {
            if (horizontalRadius < 0 || verticalRadius < 0 || matchedBlockCount < 0
                    || distinctTypeCount < 0 || maximumEntries < 0
                    || maximumEntries > MAX_HAZARD_ENTRIES) {
                throw new IllegalArgumentException("invalid hazard-scan bounds");
            }
            allEntriesHash = boundedText(allEntriesHash, HASH_BYTES * 2);
            nearest = immutable(nearest);
            if (nearest.size() > maximumEntries || nearest.size() > distinctTypeCount) {
                throw new IllegalArgumentException("hazard list exceeds its declared bounds");
            }
        }

        public static HazardScan unavailable(int horizontalRadius, int verticalRadius) {
            return new HazardScan(
                    false,
                    Math.max(0, horizontalRadius),
                    Math.max(0, verticalRadius),
                    0,
                    0,
                    MAX_HAZARD_ENTRIES,
                    hash(List.of()),
                    List.of());
        }
    }

    /**
     * targetingEvidence is explicit about authority: client_target means the mob's observed target
     * equals Entity, client_other_target means another target was observed, and not_observed avoids
     * turning an unsynchronised client target into a false negative.
     */
    public record Hostile(
            String uuid,
            String type,
            double distance,
            boolean lineOfSight,
            boolean targetingEntity,
            String targetingEvidence,
            String targetUuid,
            String targetType) {
        public Hostile {
            uuid = boundedText(uuid, 36);
            type = boundedText(type, 64);
            distance = finiteNonNegative(distance, "hostile distance");
            targetingEvidence = boundedText(targetingEvidence, 32);
            targetUuid = boundedText(targetUuid, 36);
            targetType = boundedText(targetType, 64);
        }
    }

    public record HostileScan(
            boolean loadedEntityScanComplete,
            boolean chunkCoverageComplete,
            int horizontalRadius,
            int verticalRadius,
            int totalCount,
            int maximumEntries,
            String allEntriesHash,
            List<Hostile> hostiles) {
        public HostileScan {
            if (horizontalRadius < 0 || verticalRadius < 0 || totalCount < 0
                    || maximumEntries < 0 || maximumEntries > MAX_HOSTILE_ENTRIES) {
                throw new IllegalArgumentException("invalid hostile-scan bounds");
            }
            allEntriesHash = boundedText(allEntriesHash, HASH_BYTES * 2);
            hostiles = immutable(hostiles);
            if (hostiles.size() > maximumEntries || hostiles.size() > totalCount) {
                throw new IllegalArgumentException("hostile list exceeds its declared bounds");
            }
        }

        public static HostileScan unavailable() {
            return new HostileScan(
                    false, false, 0, 0, 0, MAX_HOSTILE_ENTRIES, hash(List.of()), List.of());
        }
    }

    public static HazardScan boundedHazards(
            boolean scanComplete,
            int horizontalRadius,
            int verticalRadius,
            int matchedBlockCount,
            Collection<Hazard> nearestPerType,
            int requestedMaximumEntries) {
        int maximumEntries = Math.max(0, Math.min(MAX_HAZARD_ENTRIES, requestedMaximumEntries));
        Map<String, Hazard> distinct = new LinkedHashMap<>();
        if (nearestPerType != null) {
            for (Hazard candidate : nearestPerType) {
                if (candidate == null || candidate.type().isBlank()) continue;
                distinct.merge(candidate.type(), candidate,
                        (left, right) -> left.distance() <= right.distance() ? left : right);
            }
        }
        List<Hazard> all = new ArrayList<>(distinct.values());
        all.sort(Comparator.comparingDouble(Hazard::distance).thenComparing(Hazard::type));
        List<String> canonical = distinct.values().stream()
                .sorted(Comparator.comparing(Hazard::type))
                .map(WorldObservationFrame::canonicalHazard)
                .toList();
        return new HazardScan(
                scanComplete,
                Math.max(0, horizontalRadius),
                Math.max(0, verticalRadius),
                Math.max(0, matchedBlockCount),
                all.size(),
                maximumEntries,
                hash(canonical),
                all.stream().limit(maximumEntries).toList());
    }

    public static HostileScan boundedHostiles(
            boolean loadedEntityScanComplete,
            boolean chunkCoverageComplete,
            int horizontalRadius,
            int verticalRadius,
            Collection<Hostile> observed,
            int requestedMaximumEntries) {
        int maximumEntries = Math.max(0, Math.min(MAX_HOSTILE_ENTRIES, requestedMaximumEntries));
        Map<String, Hostile> distinct = new LinkedHashMap<>();
        if (observed != null) {
            for (Hostile candidate : observed) {
                if (candidate == null || candidate.uuid().isBlank()) continue;
                distinct.merge(candidate.uuid(), candidate,
                        (left, right) -> left.distance() <= right.distance() ? left : right);
            }
        }
        List<Hostile> all = new ArrayList<>(distinct.values());
        all.sort(Comparator.comparingDouble(Hostile::distance).thenComparing(Hostile::uuid));
        List<String> canonical = distinct.values().stream()
                .sorted(Comparator.comparing(Hostile::uuid))
                .map(WorldObservationFrame::canonicalHostile)
                .toList();
        return new HostileScan(
                loadedEntityScanComplete,
                chunkCoverageComplete,
                Math.max(0, horizontalRadius),
                Math.max(0, verticalRadius),
                all.size(),
                maximumEntries,
                hash(canonical),
                all.stream().limit(maximumEntries).toList());
    }

    public static String classifySpace(
            boolean available,
            boolean scanComplete,
            boolean skyVisible,
            int clearColumnBlocks,
            boolean ceilingFound,
            boolean feetColliding,
            boolean headColliding) {
        if (!available || !scanComplete) return "unknown";
        if (feetColliding || headColliding) return "obstructed";
        if (ceilingFound && clearColumnBlocks == 2) return "two_block_high";
        if (skyVisible) return "surface";
        return ceilingFound ? "covered_or_cave" : "open_cave";
    }

    public static JsonObject encode(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        JsonObject encoded = new JsonObject();
        encoded.addProperty("nearbyPigCount", snapshot.nearbyPigCount());
        encoded.addProperty("nearbyCoalOreCount", snapshot.nearbyCoalOreCount());
        encoded.addProperty("scanComplete", snapshot.scanComplete());
        encoded.addProperty("horizontalRadius", snapshot.horizontalRadius());
        encoded.addProperty("verticalRadius", snapshot.verticalRadius());
        encoded.addProperty("worldObservationSchema", SCHEMA_VERSION);
        encoded.add("environment", encode(snapshot.environment()));
        encoded.add("headroom", encode(snapshot.headroom()));
        encoded.add("collisionNeighborhood", encode(snapshot.collisionNeighborhood()));
        encoded.add("hazardScan", encode(snapshot.hazardScan()));
        encoded.add("hostileScan", encode(snapshot.hostileScan()));
        return encoded;
    }

    private static JsonObject encode(Environment environment) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("available", environment.available());
        encoded.addProperty("skyVisible", environment.skyVisible());
        encoded.addProperty("day", environment.day());
        encoded.addProperty("night", environment.night());
        encoded.addProperty("dimensionHasSkyLight", environment.dimensionHasSkyLight());
        encoded.addProperty("skyLight", environment.skyLight());
        encoded.addProperty("blockLight", environment.blockLight());
        return encoded;
    }

    private static JsonObject encode(Headroom headroom) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("available", headroom.available());
        encoded.addProperty("scanComplete", headroom.scanComplete());
        encoded.addProperty("scanLimitBlocks", headroom.scanLimitBlocks());
        encoded.addProperty("clearColumnBlocks", headroom.clearColumnBlocks());
        encoded.addProperty("ceilingFound", headroom.ceilingFound());
        encoded.addProperty("ceilingOffsetBlocks", headroom.ceilingOffsetBlocks());
        encoded.addProperty("feetColliding", headroom.feetColliding());
        encoded.addProperty("headColliding", headroom.headColliding());
        encoded.addProperty("aboveHeadColliding", headroom.aboveHeadColliding());
        encoded.addProperty("spaceClass", headroom.spaceClass());
        return encoded;
    }

    private static JsonObject encode(CollisionNeighborhood collision) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("available", collision.available());
        encoded.addProperty("scanComplete", collision.scanComplete());
        encoded.addProperty("horizontalRadius", collision.horizontalRadius());
        encoded.addProperty("sampledCells", collision.sampledCells());
        encoded.addProperty("collidingCells", collision.collidingCells());
        encoded.addProperty("feetLayerColliding", collision.feetLayerColliding());
        encoded.addProperty("headLayerColliding", collision.headLayerColliding());
        encoded.addProperty("aboveHeadLayerColliding", collision.aboveHeadLayerColliding());
        encoded.addProperty("floorSupportCells", collision.floorSupportCells());
        encoded.addProperty("openHorizontalDirections", collision.openHorizontalDirections());
        encoded.addProperty("enclosedHorizontalDirections", collision.enclosedHorizontalDirections());
        return encoded;
    }

    private static JsonObject encode(HazardScan scan) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("source", "client_loaded_blocks");
        encoded.addProperty("shape", "cuboid");
        encoded.addProperty("detailsSelection", "nearest_per_type");
        encoded.addProperty("scanComplete", scan.scanComplete());
        encoded.addProperty("horizontalRadius", scan.horizontalRadius());
        encoded.addProperty("verticalRadius", scan.verticalRadius());
        encoded.addProperty("matchedBlockCount", scan.matchedBlockCount());
        encoded.addProperty("distinctTypeCount", scan.distinctTypeCount());
        encoded.addProperty("maximumEntries", scan.maximumEntries());
        encoded.addProperty("listedCount", scan.nearest().size());
        encoded.addProperty("omittedTypeCount", scan.distinctTypeCount() - scan.nearest().size());
        encoded.addProperty("listComplete", scan.nearest().size() == scan.distinctTypeCount());
        encoded.addProperty("allEntriesHash", scan.allEntriesHash());
        JsonArray nearest = new JsonArray();
        for (Hazard hazard : scan.nearest()) {
            JsonObject item = new JsonObject();
            item.addProperty("type", hazard.type());
            item.addProperty("distance", hazard.distance());
            nearest.add(item);
        }
        encoded.add("nearest", nearest);
        return encoded;
    }

    private static JsonObject encode(HostileScan scan) {
        JsonObject encoded = new JsonObject();
        boolean scanComplete =
                scan.loadedEntityScanComplete() && scan.chunkCoverageComplete();
        encoded.addProperty("source", "client_loaded_entities");
        encoded.addProperty("shape", "horizontal_cylinder");
        encoded.addProperty("detailsSelection", "nearest_by_distance");
        encoded.addProperty("scanComplete", scanComplete);
        encoded.addProperty("loadedEntityScanComplete", scan.loadedEntityScanComplete());
        encoded.addProperty("chunkCoverageComplete", scan.chunkCoverageComplete());
        encoded.addProperty("horizontalRadius", scan.horizontalRadius());
        encoded.addProperty("verticalRadius", scan.verticalRadius());
        encoded.addProperty("totalCount", scan.totalCount());
        encoded.addProperty("maximumEntries", scan.maximumEntries());
        encoded.addProperty("listedCount", scan.hostiles().size());
        encoded.addProperty("omittedCount", scan.totalCount() - scan.hostiles().size());
        encoded.addProperty("listComplete", scan.hostiles().size() == scan.totalCount());
        encoded.addProperty("noHostilesInCompleteScan", scanComplete && scan.totalCount() == 0);
        encoded.addProperty("allEntriesHash", scan.allEntriesHash());
        JsonArray hostiles = new JsonArray();
        for (Hostile hostile : scan.hostiles()) {
            JsonObject item = new JsonObject();
            item.addProperty("uuid", hostile.uuid());
            item.addProperty("type", hostile.type());
            item.addProperty("distance", hostile.distance());
            item.addProperty("lineOfSight", hostile.lineOfSight());
            item.addProperty("targetingEntity", hostile.targetingEntity());
            item.addProperty("targetingKnown", !"not_observed".equals(hostile.targetingEvidence()));
            item.addProperty("targetingEvidence", hostile.targetingEvidence());
            if (!hostile.targetUuid().isBlank()) item.addProperty("targetUuid", hostile.targetUuid());
            if (!hostile.targetType().isBlank()) item.addProperty("targetType", hostile.targetType());
            hostiles.add(item);
        }
        encoded.add("hostiles", hostiles);
        return encoded;
    }

    private static String canonicalHazard(Hazard hazard) {
        return hazard.type() + '|' + Double.toString(hazard.distance());
    }

    private static String canonicalHostile(Hostile hostile) {
        return String.join("|",
                hostile.uuid(),
                hostile.type(),
                Double.toString(hostile.distance()),
                Boolean.toString(hostile.lineOfSight()),
                Boolean.toString(hostile.targetingEntity()),
                hostile.targetingEvidence(),
                hostile.targetUuid(),
                hostile.targetType());
    }

    private static String hash(List<String> canonicalEntries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String entry : canonicalEntries) {
                digest.update(entry.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            byte[] full = digest.digest();
            return HexFormat.of().formatHex(full, 0, HASH_BYTES);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static double finiteNonNegative(double value, String description) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(description + " must be finite and non-negative");
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private static String boundedText(String value, int maximumCharacters) {
        String normalized = Objects.requireNonNullElse(value, "")
                .trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= maximumCharacters
                ? normalized
                : normalized.substring(0, maximumCharacters);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
