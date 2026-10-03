package dev.entity.client.blueprint;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Credited offline library, original canaries and content-addressed owner imports. */
public final class BlueprintCatalog {
    private final Path storageRoot;

    public BlueprintCatalog(Path storageRoot) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    public List<String> list() {
        List<String> result = new ArrayList<>(List.of("starter-shelter"));
        result.addAll(BlueprintBundledLibrary.ids());
        // The UI/Tab catalog exposes one readable choice per import. Canonical hash
        // IDs remain accepted by load and remain the saved project's identity.
        result.addAll(importAliases().keySet());
        return List.copyOf(result);
    }

    public BlueprintDesign load(String id) throws IOException {
        String requested = id == null ? "" : id.toLowerCase(Locale.ROOT);
        // Selection is a catalog lookup, never a path or an implicit download.
        if (!requested.matches("[a-z0-9][a-z0-9_-]{0,95}")) {
            throw new IOException("Invalid blueprint ID. Use /e build list to choose a design; "
                    + "URLs require /e build import.");
        }
        String bundled = requested.replace('_', '-');
        if (bundled.equals("starter-shelter")) return starterShelter();
        String packaged = BlueprintBundledLibrary.canonicalId(bundled);
        if (packaged != null) return BlueprintBundledLibrary.load(packaged);
        List<String> importedIds = BlueprintImport.cachedIds(storageRoot);
        if (importedIds.contains(requested)) return BlueprintImport.load(storageRoot, requested);
        Map<String, String> aliases = importAliases();
        String imported = aliases.get(bundled);
        if (imported != null) return BlueprintImport.load(storageRoot, imported);
        List<String> available = new ArrayList<>(list());
        available.addAll(importedIds); // Old digest commands still receive exact typo suggestions.
        List<String> close = available.stream()
                .filter(candidate -> candidate.startsWith(bundled)
                        || editDistance(bundled, candidate) <= 2)
                .sorted(Comparator.comparingInt((String candidate) -> editDistance(bundled, candidate))
                        .thenComparing(Comparator.naturalOrder()))
                .limit(3).toList();
        String choices = close.isEmpty()
                ? "Available choices: " + String.join(", ", available.stream().limit(3).toList())
                : "Did you mean: " + String.join(", ", close);
        throw new IOException("Unknown blueprint ID '" + requested + "'. " + choices
                + "? Use /e build list. No design was selected.");
    }

    private Map<String, String> importAliases() {
        Map<String, String> names = new LinkedHashMap<>();
        for (String id : BlueprintImport.cachedIds(storageRoot)) {
            try { names.put(id, nameAlias(BlueprintImport.cachedName(storageRoot, id))); }
            catch (IOException invalid) { names.put(id, id); }
        }
        Map<String, Long> counts = names.values().stream().collect(java.util.stream.Collectors.groupingBy(
                name -> name, java.util.stream.Collectors.counting()));
        Set<String> reserved = new HashSet<>(names.values());
        reserved.addAll(names.keySet());
        // A source name can itself look like another design's disambiguated alias.
        // Both must be disambiguated; never transfer the old choice to that import.
        Set<String> digestCollisions = new HashSet<>();
        for (var entry : names.entrySet()) {
            for (int length = 8; length <= 64; length++) {
                String candidate = digestAlias(entry.getValue(), entry.getKey(), length);
                if (reserved.contains(candidate)) digestCollisions.add(candidate);
            }
        }
        Map<String, String> aliases = new java.util.TreeMap<>();
        for (var entry : names.entrySet()) {
            String id = entry.getKey(), name = entry.getValue(), alias = name;
            if (!name.equals(id) && (counts.get(name) > 1 || bundledName(name)
                    || names.containsKey(name) || digestCollisions.contains(name))) {
                int length = 8;
                do {
                    alias = digestAlias(name, id, length);
                    length++;
                } while (reserved.contains(alias) || aliases.containsKey(alias) || bundledName(alias));
            }
            aliases.put(alias, id);
        }
        return aliases;
    }

    private static String digestAlias(String name, String id, int length) {
        return name.substring(0, Math.min(name.length(), 79 - length)) + "-"
                + id.substring("import-".length(), "import-".length() + length);
    }

    private static boolean bundledName(String name) {
        return name.equals("starter-shelter")
                || BlueprintBundledLibrary.canonicalId(name) != null;
    }

    private static String nameAlias(String name) {
        String alias = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (alias.isEmpty()) return "imported-design";
        return alias.substring(0, Math.min(alias.length(), 70)).replaceAll("-+$", "");
    }

    /** Suggestions only: even a one-character typo must never select another design. */
    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        return previous[right.length()];
    }

    /** Network/parsing operation; callers must execute it off the Minecraft render thread. */
    public BlueprintDesign importSource(String source) throws IOException {
        return BlueprintImport.importSource(storageRoot, source);
    }

    private static BlueprintDesign starterShelter() {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (int y = 0; y < 4; y++) for (int z = 0; z < 5; z++) for (int x = 0; x < 5; x++) {
            BlockState state = y == 0 ? Blocks.COBBLESTONE.getDefaultState()
                    : y == 3 || x == 0 || x == 4 || z == 0 || z == 4
                    ? Blocks.OAK_PLANKS.getDefaultState() : Blocks.AIR.getDefaultState();
            if (z == 0 && x == 2 && (y == 1 || y == 2)) {
                state = Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.NORTH)
                        .with(DoorBlock.HALF, y == 1 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
            }
            cells.put(new BlockPos(x, y, z), state);
        }
        return new BlueprintDesign("starter-shelter", "Starter shelter (entrance at front center)",
                "Entity2 original library", 5, 4, 5, cells);
    }
}
