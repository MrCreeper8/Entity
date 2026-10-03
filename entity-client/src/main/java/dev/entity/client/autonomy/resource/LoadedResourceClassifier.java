package dev.entity.client.autonomy.resource;

import dev.entity.core.stewardship.ResourceStewardshipPolicy;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded, fail-closed classifier for one loaded resource block.
 *
 * <p>This class deliberately knows nothing about Minecraft classes. The mapped
 * client adapter translates an exact loaded {@code BlockState} into a
 * {@link Cell}; deterministic tests can therefore exercise the same geometry
 * walk with a fake probe. Unknown cells never become evidence.</p>
 */
public final class LoadedResourceClassifier {
    public static final int CONSTRUCTION_CUE_RADIUS = 2;
    public static final int MAX_TREE_HORIZONTAL_DISTANCE = 8;
    public static final int MAX_TREE_VERTICAL_DISTANCE = 24;
    public static final int MAX_CONNECTED_LOGS = 256;
    public static final int MIN_CONNECTED_CANOPY_LEAVES = 4;
    public static final int MAX_SAMPLED_CELLS = 12_000;

    private static final int[][] AXIAL_NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private LoadedResourceClassifier() {
    }

    /** Observes only the geometry required by the requested resource intent. */
    public static Observation observe(
            ResourceStewardshipPolicy.Intent intent,
            Point target,
            Probe probe) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(probe, "probe");
        CachingProbe cached = new CachingProbe(probe);
        try {
            return switch (intent) {
                case MINE -> observeMine(intent, target, cached);
                case HARVEST_LOG -> observeTree(intent, target, cached);
                case HARVEST_TREE_ACCESS -> observeTreeAccess(intent, target, cached);
                case HARVEST_CROP -> observeCrop(intent, target, cached);
                case HARVEST_FLOWER -> observeFlower(intent, target, cached);
            };
        } catch (ArithmeticException coordinateOverflow) {
            return Observation.unavailable(
                    intent, target, cached.sampledCells(),
                    "bounded resource observation exceeded coordinate range");
        }
    }

    private static Observation observeMine(
            ResourceStewardshipPolicy.Intent intent,
            Point target,
            CachingProbe probe) {
        Cell targetCell = probe.cell(target);
        boolean complete = targetCell.loaded();
        boolean constructionCue = targetCell.constructionCue();
        for (int y = -CONSTRUCTION_CUE_RADIUS; y <= CONSTRUCTION_CUE_RADIUS; y++) {
            for (int x = -CONSTRUCTION_CUE_RADIUS; x <= CONSTRUCTION_CUE_RADIUS; x++) {
                for (int z = -CONSTRUCTION_CUE_RADIUS; z <= CONSTRUCTION_CUE_RADIUS; z++) {
                    Cell nearby = probe.cell(target.offset(x, y, z));
                    complete &= nearby.loaded();
                    constructionCue |= nearby.constructionCue();
                }
            }
        }
        complete &= !probe.budgetExceeded();
        return new Observation(
                intent, target, material(targetCell.category()), targetCell.category(),
                complete, constructionCue,
                false, false, false,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Set.of(), Set.of(),
                probe.sampledCells(),
                complete
                        ? "loaded target and bounded construction-cue neighborhood"
                        : "mine classification neighborhood is unknown or unloaded");
    }

    /** Reobserves one exact canopy cell without rediscovering or broadening the tree. */
    private static Observation observeTreeAccess(
            ResourceStewardshipPolicy.Intent intent,
            Point target,
            CachingProbe probe) {
        Cell targetCell = probe.cell(target);
        boolean complete = targetCell.loaded();
        boolean constructionCue = targetCell.constructionCue();
        for (int y = -CONSTRUCTION_CUE_RADIUS; y <= CONSTRUCTION_CUE_RADIUS; y++) {
            for (int x = -CONSTRUCTION_CUE_RADIUS; x <= CONSTRUCTION_CUE_RADIUS; x++) {
                for (int z = -CONSTRUCTION_CUE_RADIUS; z <= CONSTRUCTION_CUE_RADIUS; z++) {
                    Cell nearby = probe.cell(target.offset(x, y, z));
                    complete &= nearby.loaded();
                    constructionCue |= nearby.constructionCue();
                }
            }
        }
        complete &= !probe.budgetExceeded();
        return new Observation(
                intent, target, material(targetCell.category()), targetCell.category(),
                complete, constructionCue,
                false, false, false,
                Optional.empty(), Optional.empty(), Optional.ofNullable(targetCell.family()),
                Set.of(), Set.of(),
                probe.sampledCells(),
                complete
                        ? "loaded exact natural-tree access cell and construction neighborhood"
                        : "tree-access classification neighborhood is unknown or unloaded");
    }

    private static Observation observeCrop(
            ResourceStewardshipPolicy.Intent intent,
            Point target,
            CachingProbe probe) {
        Cell targetCell = probe.cell(target);
        Cell support = probe.cell(target.offset(0, -1, 0));
        boolean supportedCrop = targetCell.category() == Category.SUPPORTED_CROP;
        boolean complete = targetCell.loaded()
                && (!supportedCrop || support.loaded())
                && !probe.budgetExceeded();
        boolean mature = supportedCrop
                && support.category() == Category.CROP_SUPPORT
                && targetCell.mature();
        Optional<String> requiredReplant = supportedCrop
                ? Optional.of(targetCell.replantItem())
                : Optional.empty();
        return new Observation(
                intent, target, material(targetCell.category()), targetCell.category(),
                complete, targetCell.constructionCue(),
                false, false, mature,
                requiredReplant, Optional.empty(), Optional.ofNullable(targetCell.family()),
                Set.of(), Set.of(),
                probe.sampledCells(),
                complete
                        ? "loaded supported crop and exact farmland support"
                        : "crop or its exact support cell is unknown or unloaded");
    }

    private static Observation observeFlower(
            ResourceStewardshipPolicy.Intent intent, Point target, CachingProbe probe) {
        Cell cell = probe.cell(target);
        boolean complete = cell.loaded();
        boolean valid = SupportedFlowerHarvest.isFlower(cell.category());
        for (Point point : SupportedFlowerHarvest.footprint(cell.category(), target)) {
            if (point.equals(target)) continue;
            Cell other = probe.cell(point);
            complete &= other.loaded();
            Category expected = cell.category() == Category.TALL_FLOWER_LOWER
                    ? Category.TALL_FLOWER_UPPER : Category.TALL_FLOWER_LOWER;
            valid &= other.category() == expected && Objects.equals(cell.family(), other.family());
        }
        return new Observation(intent, target,
                valid ? ResourceStewardshipPolicy.Material.FLOWER : ResourceStewardshipPolicy.Material.UNKNOWN,
                cell.category(), complete, false, false, false, false,
                Optional.empty(), Optional.empty(), Optional.ofNullable(cell.family()),
                Set.of(), Set.of(), probe.sampledCells(),
                valid && complete ? "loaded complete supported flower" : "supported flower or its matching half is unavailable");
    }

    private static Observation observeTree(
            ResourceStewardshipPolicy.Intent intent,
            Point target,
            CachingProbe probe) {
        Cell targetCell = probe.cell(target);
        if (targetCell.category() != Category.LOG) {
            return new Observation(
                    intent, target, material(targetCell.category()), targetCell.category(),
                    targetCell.loaded() && !probe.budgetExceeded(),
                    targetCell.constructionCue(),
                    false, false, false,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Set.of(), Set.of(),
                    probe.sampledCells(),
                    targetCell.loaded()
                            ? "loaded target is not a supported overworld log"
                            : "target log cell is unknown or unloaded");
        }

        String family = targetCell.family();
        ArrayDeque<Point> frontier = new ArrayDeque<>();
        Set<Point> logs = new HashSet<>();
        frontier.add(target);
        logs.add(target);
        boolean complete = true;
        boolean constructionCue = false;
        boolean rooted = false;
        int minY = target.y();
        int maxY = target.y();

        while (!frontier.isEmpty()) {
            Point log = frontier.removeFirst();
            minY = Math.min(minY, log.y());
            maxY = Math.max(maxY, log.y());
            Cell below = probe.cell(log.offset(0, -1, 0));
            complete &= below.loaded();
            rooted |= below.category().naturalTreeGround();

            for (int[] offset : AXIAL_NEIGHBORS) {
                Point neighbor = log.offset(offset[0], offset[1], offset[2]);
                Cell cell = probe.cell(neighbor);
                complete &= cell.loaded();
                if (sameFamily(cell, Category.LOG, family) && !logs.contains(neighbor)) {
                    if (!insideTreeBounds(target, neighbor)
                            || logs.size() >= MAX_CONNECTED_LOGS) {
                        complete = false;
                    } else {
                        logs.add(neighbor);
                        frontier.addLast(neighbor);
                    }
                }
            }
        }

        // A natural tree proof is invalid beside player construction. The same
        // exact two-block trunk envelope also retains every natural canopy cell
        // which Baritone may have to cross. This is access authority, not a
        // request to clear the canopy, and it never broadens into a leaf flood.
        Set<Point> leaves = new HashSet<>();
        for (Point log : logs) {
            for (int y = -CONSTRUCTION_CUE_RADIUS; y <= CONSTRUCTION_CUE_RADIUS; y++) {
                for (int x = -CONSTRUCTION_CUE_RADIUS; x <= CONSTRUCTION_CUE_RADIUS; x++) {
                    for (int z = -CONSTRUCTION_CUE_RADIUS; z <= CONSTRUCTION_CUE_RADIUS; z++) {
                        Point position = log.offset(x, y, z);
                        Cell nearby = probe.cell(position);
                        complete &= nearby.loaded();
                        constructionCue |= nearby.constructionCue();
                        if (sameFamily(nearby, Category.NATURAL_LEAF, family)) {
                            leaves.add(position);
                        }
                    }
                }
            }
        }

        complete &= !probe.budgetExceeded();
        boolean trunkShape = logs.size() >= 2 && maxY > minY;
        boolean rootedNaturalTree = complete && rooted && trunkShape;
        boolean connectedCanopy = complete
                && leaves.size() >= MIN_CONNECTED_CANOPY_LEAVES;
        return new Observation(
                intent, target, ResourceStewardshipPolicy.Material.LOG, Category.LOG,
                complete, constructionCue,
                rootedNaturalTree, connectedCanopy, false,
                Optional.empty(), Optional.ofNullable(targetCell.replantItem()),
                Optional.of(family), Set.copyOf(logs), Set.copyOf(leaves),
                probe.sampledCells(),
                "loaded tree proof logs=" + logs.size()
                        + " canopyLeaves=" + leaves.size()
                        + " rooted=" + rooted
                        + " complete=" + complete);
    }

    private static boolean sameFamily(Cell cell, Category category, String family) {
        return cell.category() == category && family.equals(cell.family());
    }

    private static boolean insideTreeBounds(Point origin, Point candidate) {
        return absoluteDifference(origin.x(), candidate.x())
                        <= MAX_TREE_HORIZONTAL_DISTANCE
                && absoluteDifference(origin.z(), candidate.z())
                        <= MAX_TREE_HORIZONTAL_DISTANCE
                && absoluteDifference(origin.y(), candidate.y())
                        <= MAX_TREE_VERTICAL_DISTANCE;
    }

    private static long absoluteDifference(int first, int second) {
        return Math.abs((long) first - second);
    }

    private static ResourceStewardshipPolicy.Material material(Category category) {
        return switch (category) {
            case NATURAL_MINE -> ResourceStewardshipPolicy.Material.NATURAL_MINE;
            case NATURAL_GROUND -> ResourceStewardshipPolicy.Material.NATURAL_GROUND;
            case ORE -> ResourceStewardshipPolicy.Material.ORE;
            case LOG -> ResourceStewardshipPolicy.Material.LOG;
            case NATURAL_LEAF -> ResourceStewardshipPolicy.Material.NATURAL_LEAF;
            case SUPPORTED_CROP -> ResourceStewardshipPolicy.Material.CROP;
            case FLOWER, TALL_FLOWER_LOWER, TALL_FLOWER_UPPER -> ResourceStewardshipPolicy.Material.FLOWER;
            case CONTAINER -> ResourceStewardshipPolicy.Material.CONTAINER;
            case WORKSTATION -> ResourceStewardshipPolicy.Material.WORKSTATION;
            case DOOR -> ResourceStewardshipPolicy.Material.PORTAL;
            case RAIL, REDSTONE -> ResourceStewardshipPolicy.Material.REDSTONE;
            case CONSTRUCTION, PERSISTENT_LEAF ->
                    ResourceStewardshipPolicy.Material.CONSTRUCTION;
            default -> ResourceStewardshipPolicy.Material.UNKNOWN;
        };
    }

    /** Exact loaded-world semantic supplied by the mapped Minecraft adapter. */
    public enum Category {
        UNKNOWN,
        AIR,
        FLUID,
        NATURAL_MINE,
        ORE,
        NATURAL_GROUND,
        NATURAL_TREE_ROOT,
        CROP_SUPPORT,
        LOG,
        NATURAL_LEAF,
        PERSISTENT_LEAF,
        SUPPORTED_CROP,
        FLOWER,
        TALL_FLOWER_LOWER,
        TALL_FLOWER_UPPER,
        CONTAINER,
        WORKSTATION,
        DOOR,
        RAIL,
        REDSTONE,
        CONSTRUCTION,
        OTHER;

        public boolean naturalTreeGround() {
            return this == NATURAL_GROUND || this == NATURAL_TREE_ROOT;
        }
    }

    /**
     * One exact cell. Family is present only for supported logs/leaves/crops;
     * replantItem is an exact namespaced registry id for logs/crops when known.
     */
    public record Cell(
            Category category,
            String family,
            boolean mature,
            String replantItem) {
        public Cell {
            category = Objects.requireNonNull(category, "category");
            family = normalizeNullable(family);
            replantItem = normalizeNullable(replantItem);
            boolean familyRequired = category == Category.LOG
                    || category == Category.NATURAL_LEAF
                    || category == Category.PERSISTENT_LEAF
                    || category == Category.SUPPORTED_CROP
                    || SupportedFlowerHarvest.isFlower(category);
            if (familyRequired != (family != null)) {
                throw new IllegalArgumentException(
                        "family must be present exactly for classified tree/crop/flower cells");
            }
            if (category == Category.SUPPORTED_CROP && replantItem == null) {
                throw new IllegalArgumentException(
                        "supported crop requires exact replant item");
            }
            if (mature && category != Category.SUPPORTED_CROP) {
                throw new IllegalArgumentException("only supported crops can be mature");
            }
            if (replantItem != null
                    && category != Category.LOG
                    && category != Category.SUPPORTED_CROP) {
                throw new IllegalArgumentException(
                        "replant item applies only to a log family or supported crop");
            }
        }

        public static Cell of(Category category) {
            return new Cell(category, null, false, null);
        }

        public static Cell unknown() {
            return of(Category.UNKNOWN);
        }

        public static Cell log(String family, String matchingSaplingItem) {
            return new Cell(Category.LOG, family, false, matchingSaplingItem);
        }

        public static Cell naturalLeaf(String family) {
            return new Cell(Category.NATURAL_LEAF, family, false, null);
        }

        public static Cell persistentLeaf(String family) {
            return new Cell(Category.PERSISTENT_LEAF, family, false, null);
        }

        public static Cell crop(
                String family,
                boolean mature,
                String exactReplantItem) {
            return new Cell(
                    Category.SUPPORTED_CROP, family, mature, exactReplantItem);
        }

        public boolean loaded() {
            return category != Category.UNKNOWN;
        }

        public boolean constructionCue() {
            return category == Category.CONTAINER
                    || category == Category.WORKSTATION
                    || category == Category.DOOR
                    || category == Category.RAIL
                    || category == Category.REDSTONE
                    || category == Category.CONSTRUCTION
                    || category == Category.PERSISTENT_LEAF;
        }

        private static String normalizeNullable(String value) {
            if (value == null) return null;
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("classified identifier cannot be blank");
            }
            return normalized;
        }
    }

    @FunctionalInterface
    public interface Probe {
        /** Returns {@link Cell#unknown()} for every unloaded or unavailable cell. */
        Cell sample(Point point);
    }

    public record Point(int x, int y, int z) {
        public Point offset(int dx, int dy, int dz) {
            return new Point(
                    Math.addExact(x, dx),
                    Math.addExact(y, dy),
                    Math.addExact(z, dz));
        }
    }

    /** External authority and route facts; this classifier never guesses them. */
    public record AdmissionContext(
            boolean policySynchronized,
            boolean insideMatchingArea,
            boolean protectedOverlap,
            boolean matchesActiveObjective,
            boolean supportsEntityBody,
            boolean entranceBodyCell,
            boolean onlyKnownExit,
            Set<String> reservedReplantItemIds) {
        public AdmissionContext {
            reservedReplantItemIds = Set.copyOf(Objects.requireNonNull(
                    reservedReplantItemIds, "reservedReplantItemIds"));
            if (reservedReplantItemIds.stream().anyMatch(
                    item -> item == null || item.isBlank())) {
                throw new IllegalArgumentException(
                        "reserved replant ids must be exact nonblank registry ids");
            }
        }
    }

    /** Immutable observation plus a direct conversion into the core final gate. */
    public record Observation(
            ResourceStewardshipPolicy.Intent intent,
            Point target,
            ResourceStewardshipPolicy.Material material,
            Category targetCategory,
            boolean observationComplete,
            boolean constructionCue,
            boolean rootedNaturalTree,
            boolean connectedLeafCanopy,
            boolean matureCrop,
            Optional<String> requiredCropReplantItem,
            Optional<String> observedMatchingSaplingItem,
            Optional<String> family,
            Set<Point> connectedNaturalTreeLogs,
            Set<Point> connectedNaturalTreeLeaves,
            int sampledCells,
            String detail) {
        public Observation {
            intent = Objects.requireNonNull(intent, "intent");
            target = Objects.requireNonNull(target, "target");
            material = Objects.requireNonNull(material, "material");
            targetCategory = Objects.requireNonNull(targetCategory, "targetCategory");
            requiredCropReplantItem = Objects.requireNonNull(
                    requiredCropReplantItem, "requiredCropReplantItem");
            observedMatchingSaplingItem = Objects.requireNonNull(
                    observedMatchingSaplingItem, "observedMatchingSaplingItem");
            family = Objects.requireNonNull(family, "family");
            connectedNaturalTreeLogs = Set.copyOf(Objects.requireNonNull(
                    connectedNaturalTreeLogs, "connectedNaturalTreeLogs"));
            connectedNaturalTreeLeaves = Set.copyOf(Objects.requireNonNull(
                    connectedNaturalTreeLeaves, "connectedNaturalTreeLeaves"));
            detail = Objects.requireNonNull(detail, "detail").trim();
            if (sampledCells < 0 || detail.isEmpty()) {
                throw new IllegalArgumentException(
                        "observation requires nonnegative samples and detail");
            }
            if (requiredCropReplantItem.isPresent()
                    != (targetCategory == Category.SUPPORTED_CROP)) {
                throw new IllegalArgumentException(
                        "crop replant item must identify exactly a supported crop");
            }
            if (targetCategory != Category.LOG && !connectedNaturalTreeLogs.isEmpty()) {
                throw new IllegalArgumentException(
                        "only a log observation may retain a connected tree");
            }
            if (targetCategory != Category.LOG && !connectedNaturalTreeLeaves.isEmpty()) {
                throw new IllegalArgumentException(
                        "only a log observation may retain connected natural canopy cells");
            }
            if (!connectedNaturalTreeLogs.isEmpty()
                    && !connectedNaturalTreeLogs.contains(target)) {
                throw new IllegalArgumentException(
                        "connected tree identity must contain its observed target");
            }
        }

        public ResourceStewardshipPolicy.Facts facts(AdmissionContext context) {
            return facts(context, false);
        }

        /**
         * Converts the live observation while retaining an operation's original
         * whole-tree proof for an exact later log. All mutable authority,
         * identity, construction, body, and route facts remain live.
         */
        public ResourceStewardshipPolicy.Facts facts(
                AdmissionContext context,
                boolean retainedNaturalTreeProof) {
            Objects.requireNonNull(context, "context");
            // Tree saplings are useful follow-up stock, but not an admission
            // requirement: requiring one would deadlock zero-stock wood bootstrap.
            boolean exactCropReplantReserved = intent
                    != ResourceStewardshipPolicy.Intent.HARVEST_CROP
                    || requiredCropReplantItem
                    .map(context.reservedReplantItemIds()::contains)
                    .orElse(false);
            return new ResourceStewardshipPolicy.Facts(
                    intent, material,
                    context.policySynchronized(), observationComplete,
                    context.insideMatchingArea(), context.protectedOverlap(),
                    context.matchesActiveObjective(), context.supportsEntityBody(),
                    context.entranceBodyCell(), context.onlyKnownExit(),
                    constructionCue, rootedNaturalTree, connectedLeafCanopy,
                    matureCrop, exactCropReplantReserved,
                    retainedNaturalTreeProof);
        }

        public static Observation unavailable(
                ResourceStewardshipPolicy.Intent intent,
                Point target,
                int sampledCells,
                String detail) {
            return new Observation(
                    intent, target, ResourceStewardshipPolicy.Material.UNKNOWN,
                    Category.UNKNOWN, false, false,
                    false, false, false,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Set.of(), Set.of(),
                    sampledCells, detail);
        }
    }

    private static final class CachingProbe {
        private final Probe delegate;
        private final Map<Point, Cell> cells = new HashMap<>();
        private boolean budgetExceeded;

        private CachingProbe(Probe delegate) {
            this.delegate = delegate;
        }

        private Cell cell(Point point) {
            Cell cached = cells.get(point);
            if (cached != null) return cached;
            if (cells.size() >= MAX_SAMPLED_CELLS) {
                budgetExceeded = true;
                return Cell.unknown();
            }
            Cell sampled = delegate.sample(point);
            if (sampled == null) sampled = Cell.unknown();
            cells.put(point, sampled);
            return sampled;
        }

        private int sampledCells() {
            return cells.size();
        }

        private boolean budgetExceeded() {
            return budgetExceeded;
        }
    }
}
