package dev.entity.core.stewardship;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, canonical property and resource-area policy shared by Paper and Entity. */
public final class ProtectedAreaPolicy {
    public static final int LEGACY_SCHEMA_VERSION = 1;
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_AREAS = 32;
    public static final int MAX_SIDE_BLOCKS = 256;
    public static final int MAX_MINING_HEIGHT_BLOCKS = 512;

    private ProtectedAreaPolicy() {
    }

    public static Snapshot empty() {
        return create(0L, List.of());
    }

    public static Snapshot create(long revision, Collection<Area> areas) {
        List<Area> canonical = canonicalAreas(areas);
        return new Snapshot(
                SCHEMA_VERSION,
                revision,
                digest(revision, canonical),
                canonical);
    }

    /**
     * Verifies a released schema-1 snapshot and migrates it without relaxing any
     * property boundary. Migration consumes one revision because its canonical
     * identity changes even though every old rectangle keeps the same semantics.
     */
    public static Snapshot migrateLegacySnapshot(
            long revision,
            String legacyDigest,
            Collection<Area> protectedAreas) {
        List<Area> canonical = canonicalAreas(protectedAreas);
        if (canonical.stream().anyMatch(area -> area.kind() != AreaKind.PROTECTED)) {
            throw new IllegalArgumentException("schema-1 policy may contain only protected areas");
        }
        String expected = legacyDigest(revision, canonical);
        String checked = Objects.requireNonNullElse(legacyDigest, "")
                .trim().toLowerCase(Locale.ROOT);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                checked.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("legacy protected-area digest does not match snapshot");
        }
        return create(Math.addExact(revision, 1L), canonical);
    }

    public static String digest(long revision, Collection<Area> areas) {
        if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
        List<Area> canonical = canonicalAreas(areas);
        StringBuilder encoded = new StringBuilder(128 + canonical.size() * 144);
        encoded.append(SCHEMA_VERSION).append('\n')
                .append(revision).append('\n')
                .append(canonical.size()).append('\n');
        for (Area area : canonical) {
            appendText(encoded, area.name());
            appendText(encoded, area.kind().wireName());
            appendText(encoded, area.dimension());
            encoded.append(area.minX()).append('\n')
                    .append(area.maxX()).append('\n')
                    .append(area.minZ()).append('\n')
                    .append(area.maxZ()).append('\n');
            if (area.kind() == AreaKind.MINING) {
                encoded.append(area.minY()).append('\n')
                        .append(area.maxY()).append('\n')
                        .append(area.entranceX()).append('\n')
                        .append(area.entranceY()).append('\n')
                        .append(area.entranceZ()).append('\n');
            }
        }
        return sha256(encoded);
    }

    private static String legacyDigest(long revision, Collection<Area> areas) {
        if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
        List<Area> canonical = canonicalAreas(areas);
        StringBuilder encoded = new StringBuilder(128 + canonical.size() * 96);
        encoded.append(LEGACY_SCHEMA_VERSION).append('\n')
                .append(revision).append('\n')
                .append(canonical.size()).append('\n');
        for (Area area : canonical) {
            appendText(encoded, area.name());
            appendText(encoded, area.dimension());
            encoded.append(area.minX()).append('\n')
                    .append(area.maxX()).append('\n')
                    .append(area.minZ()).append('\n')
                    .append(area.maxZ()).append('\n');
        }
        return sha256(encoded);
    }

    private static String sha256(CharSequence encoded) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(encoded.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(String.format(Locale.ROOT, "%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static Decision decide(
            Snapshot snapshot,
            boolean synchronizedPolicy,
            Action action,
            String dimension,
            int x,
            int y,
            int z,
            boolean exactOwnedHomeAction) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(action, "action");
        if (!synchronizedPolicy) {
            return Decision.denied(
                    "policy_unsynchronized",
                    "Area policy is not synchronized; Entity has no world-mutation authority",
                    null,
                    dimension,
                    x,
                    y,
                    z);
        }
        // Resource areas are positive, classified authority. They never weaken the
        // released full-height protected-property fence used by ordinary mutations.
        Area area = snapshot.firstContaining(dimension, x, z).orElse(null);
        if (area == null) return Decision.allowed("outside protected property");
        // Only an explicitly selected Entity base admits an exact Home asset
        // capability. Strict player property wins every overlap and cannot be
        // weakened by a Home pin or by another selected area's name/order.
        if (area.kind() == AreaKind.ENTITY_BASE && exactOwnedHomeAction
                && action.homeMaintenanceEligible() && action != Action.FLUID) {
            return Decision.allowed("exact registered Home asset inside Entity base '"
                    + area.name() + "'");
        }
        return Decision.denied(
                "protected_area",
                "Protected area '" + area.name() + "' forbids "
                        + action.name().toLowerCase(Locale.ROOT) + " at "
                        + x + " " + y + " " + z + " in " + area.dimension(),
                area.name(),
                area.dimension(),
                x,
                y,
                z);
    }

    /**
     * Exact, short-lived authority for toggling a verified door/gate solely as
     * part of non-destructive passage. This is intentionally separate from
     * Home maintenance so a passage permit cannot authorize containers,
     * placement, fluids, or any other protected-property mutation.
     */
    public static Decision decideReversiblePassage(
            Snapshot snapshot,
            boolean synchronizedPolicy,
            Action action,
            String dimension,
            int x,
            int y,
            int z) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(action, "action");
        if (!synchronizedPolicy) {
            return Decision.denied(
                    "policy_unsynchronized",
                    "Area policy is not synchronized; Entity has no passage authority",
                    null, dimension, x, y, z);
        }
        Area area = snapshot.firstContaining(dimension, x, z).orElse(null);
        if (area == null) return Decision.allowed("outside protected property");
        if (action == Action.BLOCK_INTERACT) {
            return Decision.allowed(
                    "exact reversible passage inside " + area.name());
        }
        return Decision.denied(
                "passage_scope_mismatch",
                "Reversible passage cannot authorize "
                        + action.name().toLowerCase(Locale.ROOT) + " inside " + area.name(),
                area.name(), area.dimension(), x, y, z);
    }

    /** Pure geometry/precedence query for the later classified resource actuator. */
    public static ResourceAuthority decideResourceAuthority(
            Snapshot snapshot,
            boolean synchronizedPolicy,
            AreaKind requiredKind,
            String dimension,
            int x,
            int y,
            int z) {
        Objects.requireNonNull(snapshot, "snapshot");
        AreaKind checkedKind = Objects.requireNonNull(requiredKind, "requiredKind");
        if (!checkedKind.resource()) {
            throw new IllegalArgumentException("resource authority requires mining or harvesting");
        }
        if (!synchronizedPolicy) {
            return ResourceAuthority.denied(
                    "policy_unsynchronized",
                    "Area policy is not synchronized; resource work is disabled",
                    "", checkedKind, checkedKind == AreaKind.HARVESTING);
        }
        Area protectedOverlap = snapshot.firstContaining(dimension, x, z).orElse(null);
        if (protectedOverlap != null) {
            return ResourceAuthority.denied(
                    "protected_overlap",
                    "Protected area '" + protectedOverlap.name()
                            + "' overrides resource authority at " + x + " " + y + " " + z,
                    protectedOverlap.name(), checkedKind,
                    checkedKind == AreaKind.HARVESTING);
        }
        // Resource areas are optional positive hints/classification. They can
        // never turn the rest of the unprotected survival world into a second
        // implicit exclusion zone.
        Area matching = snapshot.firstResourceContaining(
                checkedKind, dimension, x, y, z).orElse(null);
        if (matching == null) return ResourceAuthority.allowedWilderness(
                checkedKind,
                "unprotected wilderness remains available outside optional "
                        + checkedKind.label() + " areas");
        return ResourceAuthority.allowed(
                matching,
                checkedKind == AreaKind.HARVESTING
                        ? "inside harvesting footprint; loaded resource classification is still required"
                        : "inside exact mining volume");
    }

    private static List<Area> canonicalAreas(Collection<Area> source) {
        Objects.requireNonNull(source, "areas");
        if (source.size() > MAX_AREAS) {
            throw new IllegalArgumentException("area limit is " + MAX_AREAS);
        }
        ArrayList<Area> sorted = new ArrayList<>(source.size());
        for (Area area : source) sorted.add(Objects.requireNonNull(area, "area"));
        sorted.sort(Comparator.comparing(Area::name));
        Map<String, Area> unique = new LinkedHashMap<>();
        for (Area area : sorted) {
            if (unique.putIfAbsent(area.name(), area) != null) {
                throw new IllegalArgumentException("duplicate area name " + area.name());
            }
        }
        return List.copyOf(unique.values());
    }

    private static void appendText(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append('\n');
    }

    public enum AreaKind {
        PROTECTED,
        ENTITY_BASE,
        MINING,
        HARVESTING;

        public static AreaKind parse(String value) {
            String normalized = Objects.requireNonNullElse(value, "")
                    .trim().toUpperCase(Locale.ROOT);
            try {
                return valueOf(normalized);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(
                        "area kind must be protected, entity_base, mining, or harvesting", invalid);
            }
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String label() {
            return wireName();
        }

        public boolean resource() {
            return this == MINING || this == HARVESTING;
        }
    }

    public enum Action {
        BREAK(false),
        PLACE(true),
        FLUID(true),
        CONTAINER(true),
        BLOCK_INTERACT(true);

        private final boolean homeMaintenanceEligible;

        Action(boolean homeMaintenanceEligible) {
            this.homeMaintenanceEligible = homeMaintenanceEligible;
        }

        public boolean homeMaintenanceEligible() {
            return homeMaintenanceEligible;
        }
    }

    public record Area(
            String name,
            AreaKind kind,
            String dimension,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            int entranceX,
            int entranceY,
            int entranceZ) {
        public Area {
            name = normalizeName(name);
            kind = Objects.requireNonNull(kind, "kind");
            dimension = requiredDimension(dimension);
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("area bounds must be normalized");
            }
            long width = (long) maxX - minX + 1L;
            long depth = (long) maxZ - minZ + 1L;
            if (width > MAX_SIDE_BLOCKS || depth > MAX_SIDE_BLOCKS) {
                throw new IllegalArgumentException(
                        "area sides cannot exceed " + MAX_SIDE_BLOCKS + " blocks");
            }
            if (kind == AreaKind.MINING) {
                if (minY > maxY) {
                    throw new IllegalArgumentException("mining Y bounds must be normalized");
                }
                long height = (long) maxY - minY + 1L;
                if (height > MAX_MINING_HEIGHT_BLOCKS) {
                    throw new IllegalArgumentException("mining height cannot exceed "
                            + MAX_MINING_HEIGHT_BLOCKS + " blocks");
                }
                if (entranceX < minX || entranceX > maxX
                        || entranceY < minY || entranceY > maxY
                        || entranceZ < minZ || entranceZ > maxZ) {
                    throw new IllegalArgumentException("mining entrance must be inside its volume");
                }
            } else if (minY != 0 || maxY != 0
                    || entranceX != 0 || entranceY != 0 || entranceZ != 0) {
                throw new IllegalArgumentException(
                        kind.label() + " areas cannot carry mining Y or entrance geometry");
            }
        }

        /** Source-compatible constructor for released full-height protected areas. */
        public Area(
                String name,
                String dimension,
                int minX,
                int maxX,
                int minZ,
                int maxZ) {
            this(name, AreaKind.PROTECTED, dimension,
                    minX, maxX, 0, 0, minZ, maxZ, 0, 0, 0);
        }

        /** Released alias: a two-corner X/Z selection is protected property. */
        public static Area between(
                String name,
                String dimension,
                int firstX,
                int firstZ,
                int secondX,
                int secondZ) {
            return protectedBetween(name, dimension, firstX, firstZ, secondX, secondZ);
        }

        public static Area protectedBetween(
                String name,
                String dimension,
                int firstX,
                int firstZ,
                int secondX,
                int secondZ) {
            return new Area(
                    name, AreaKind.PROTECTED, dimension,
                    Math.min(firstX, secondX), Math.max(firstX, secondX),
                    0, 0,
                    Math.min(firstZ, secondZ), Math.max(firstZ, secondZ),
                    0, 0, 0);
        }

        public static Area miningBetween(
                String name,
                String dimension,
                int firstX,
                int firstY,
                int firstZ,
                int secondX,
                int secondY,
                int secondZ) {
            return new Area(
                    name, AreaKind.MINING, dimension,
                    Math.min(firstX, secondX), Math.max(firstX, secondX),
                    Math.min(firstY, secondY), Math.max(firstY, secondY),
                    Math.min(firstZ, secondZ), Math.max(firstZ, secondZ),
                    firstX, firstY, firstZ);
        }

        public static Area entityBaseBetween(
                String name, String dimension, int firstX, int firstZ,
                int secondX, int secondZ) {
            return new Area(name, AreaKind.ENTITY_BASE, dimension,
                    Math.min(firstX, secondX), Math.max(firstX, secondX), 0, 0,
                    Math.min(firstZ, secondZ), Math.max(firstZ, secondZ), 0, 0, 0);
        }

        public static Area harvestingBetween(
                String name,
                String dimension,
                int firstX,
                int firstZ,
                int secondX,
                int secondZ) {
            return new Area(
                    name, AreaKind.HARVESTING, dimension,
                    Math.min(firstX, secondX), Math.max(firstX, secondX),
                    0, 0,
                    Math.min(firstZ, secondZ), Math.max(firstZ, secondZ),
                    0, 0, 0);
        }

        public boolean contains(String candidateDimension, int x, int z) {
            return dimension.equals(requiredDimension(candidateDimension))
                    && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public boolean contains(String candidateDimension, int x, int y, int z) {
            return contains(candidateDimension, x, z)
                    && (kind != AreaKind.MINING || y >= minY && y <= maxY);
        }

        public boolean classificationOnly() {
            return kind == AreaKind.HARVESTING;
        }

        public long blockCount() {
            return ((long) maxX - minX + 1L) * ((long) maxZ - minZ + 1L);
        }

        public long volumeBlockCount() {
            if (kind != AreaKind.MINING) return blockCount();
            return Math.multiplyExact(blockCount(), (long) maxY - minY + 1L);
        }

        public int width() {
            return maxX - minX + 1;
        }

        public int height() {
            return kind == AreaKind.MINING ? maxY - minY + 1 : 0;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        private static String normalizeName(String value) {
            String normalized = Objects.requireNonNullElse(value, "")
                    .trim().toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9_-]{1,24}")) {
                throw new IllegalArgumentException(
                        "area name must be 1-24 letters, numbers, '_' or '-'");
            }
            return normalized;
        }

        private static String requiredDimension(String value) {
            String normalized = Objects.requireNonNullElse(value, "").trim();
            if (normalized.isEmpty() || normalized.length() > 256) {
                throw new IllegalArgumentException("dimension is required");
            }
            return normalized;
        }
    }

    public record Snapshot(
            int schemaVersion,
            long revision,
            String digest,
            List<Area> areas) {
        public Snapshot {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported area-policy schema " + schemaVersion);
            }
            if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
            List<Area> canonical = canonicalAreas(areas);
            String expected = ProtectedAreaPolicy.digest(revision, canonical);
            String normalizedDigest = Objects.requireNonNullElse(digest, "")
                    .trim().toLowerCase(Locale.ROOT);
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    normalizedDigest.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("area-policy digest does not match snapshot");
            }
            digest = normalizedDigest;
            areas = canonical;
        }

        public Optional<Area> named(String name) {
            if (name == null) return Optional.empty();
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            return areas.stream().filter(area -> area.name().equals(normalized)).findFirst();
        }

        /** Released property query: intentionally ignores resource permissions. */
        public Optional<Area> firstContaining(String dimension, int x, int z) {
            return areas.stream()
                    .filter(area -> !area.kind().resource())
                    .filter(area -> area.contains(dimension, x, z))
                    .sorted(Comparator.comparingInt(area ->
                            area.kind() == AreaKind.PROTECTED ? 0 : 1))
                    .findFirst();
        }

        /** Released property query: intentionally ignores resource permissions. */
        public List<Area> containing(String dimension, int x, int z) {
            return areas.stream()
                    .filter(area -> !area.kind().resource())
                    .filter(area -> area.contains(dimension, x, z))
                    .toList();
        }

        public List<Area> areasOfKind(AreaKind kind) {
            Objects.requireNonNull(kind, "kind");
            return areas.stream().filter(area -> area.kind() == kind).toList();
        }

        public List<Area> protectedAreas() {
            return areas.stream().filter(area -> !area.kind().resource()).toList();
        }

        public Optional<Area> firstResourceContaining(
                AreaKind kind,
                String dimension,
                int x,
                int y,
                int z) {
            if (kind == null || !kind.resource()) {
                throw new IllegalArgumentException("resource area kind is required");
            }
            return areas.stream()
                    .filter(area -> area.kind() == kind)
                    .filter(area -> area.contains(dimension, x, y, z))
                    .findFirst();
        }
    }

    public record Decision(
            boolean allowed,
            String code,
            String detail,
            String areaName,
            String dimension,
            int x,
            int y,
            int z) {
        public Decision {
            code = Objects.requireNonNullElse(code, "");
            detail = Objects.requireNonNullElse(detail, "");
            areaName = Objects.requireNonNullElse(areaName, "");
            dimension = Objects.requireNonNullElse(dimension, "");
        }

        private static Decision allowed(String detail) {
            return new Decision(true, "allowed", detail, "", "", 0, 0, 0);
        }

        private static Decision denied(
                String code,
                String detail,
                String areaName,
                String dimension,
                int x,
                int y,
                int z) {
            return new Decision(false, code, detail, areaName, dimension, x, y, z);
        }
    }

    public record ResourceAuthority(
            boolean allowed,
            String code,
            String detail,
            String areaName,
            AreaKind requiredKind,
            boolean classificationRequired) {
        public ResourceAuthority {
            code = Objects.requireNonNullElse(code, "");
            detail = Objects.requireNonNullElse(detail, "");
            areaName = Objects.requireNonNullElse(areaName, "");
            requiredKind = Objects.requireNonNull(requiredKind, "requiredKind");
        }

        private static ResourceAuthority allowed(Area area, String detail) {
            return new ResourceAuthority(
                    true, "allowed", detail, area.name(), area.kind(), area.classificationOnly());
        }

        private static ResourceAuthority allowedWilderness(
                AreaKind requiredKind,
                String detail) {
            return new ResourceAuthority(
                    true, "allowed_wilderness", detail, "", requiredKind, true);
        }

        private static ResourceAuthority denied(
                String code,
                String detail,
                String areaName,
                AreaKind requiredKind,
                boolean classificationRequired) {
            return new ResourceAuthority(
                    false, code, detail, areaName, requiredKind, classificationRequired);
        }
    }
}
