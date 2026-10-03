package dev.entity.client.autonomy.resource;

import java.util.Set;

/** Only the flower blocks already offered as HARVEST sources by ResourceCatalog. */
public final class SupportedFlowerHarvest {
    private static final Set<String> SMALL = Set.of("dandelion", "poppy", "blue_orchid", "allium",
            "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
            "oxeye_daisy", "cornflower", "lily_of_the_valley");
    private static final Set<String> TALL = Set.of("sunflower", "lilac", "rose_bush", "peony");
    private SupportedFlowerHarvest() {}

    public static Set<String> supportedIds() {
        return java.util.stream.Stream.concat(SMALL.stream(), TALL.stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static LoadedResourceClassifier.Cell classify(String registryId, boolean upperHalf) {
        String id = registryId.startsWith("minecraft:") ? registryId.substring(10) : registryId;
        var category = SMALL.contains(id) ? LoadedResourceClassifier.Category.FLOWER
                : TALL.contains(id) ? upperHalf ? LoadedResourceClassifier.Category.TALL_FLOWER_UPPER
                : LoadedResourceClassifier.Category.TALL_FLOWER_LOWER
                : LoadedResourceClassifier.Category.OTHER;
        return isFlower(category) ? new LoadedResourceClassifier.Cell(category, id, false, null)
                : LoadedResourceClassifier.Cell.of(category);
    }

    public static boolean isFlower(LoadedResourceClassifier.Category category) {
        return category == LoadedResourceClassifier.Category.FLOWER
                || category == LoadedResourceClassifier.Category.TALL_FLOWER_LOWER
                || category == LoadedResourceClassifier.Category.TALL_FLOWER_UPPER;
    }

    /** Breaking either tall half removes the other: authorization always includes both. */
    public static Set<LoadedResourceClassifier.Point> footprint(
            LoadedResourceClassifier.Category category, LoadedResourceClassifier.Point target) {
        return switch (category) {
            case TALL_FLOWER_LOWER -> Set.of(target, target.offset(0, 1, 0));
            case TALL_FLOWER_UPPER -> Set.of(target, target.offset(0, -1, 0));
            default -> Set.of(target);
        };
    }
}
