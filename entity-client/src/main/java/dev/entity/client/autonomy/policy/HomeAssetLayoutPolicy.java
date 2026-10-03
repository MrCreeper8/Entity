package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses one coherent Home asset layout from already-proved physical options.
 *
 * <p>The policy knows nothing about Minecraft raycasts or block states.  The
 * live adapter supplies only options whose destination, support and interaction
 * geometry have already been verified.  Selection then guarantees that the
 * three one-cell assets and the two-cell bed never overlap.  Keeping this
 * combinator pure lets establishment preflight and sequential placement share
 * exactly the same footprint rule.</p>
 */
public final class HomeAssetLayoutPolicy {
    private HomeAssetLayoutPolicy() {
    }

    public record Cell(int x, int y, int z) {
    }

    /** One physical option for one role; BED owns both its foot and head cells. */
    public record Option(
            HomeEconomySession.AssetRole role,
            Cell primary,
            Set<Cell> occupied) {
        public Option {
            role = Objects.requireNonNull(role, "role");
            primary = Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(occupied, "occupied");
            LinkedHashSet<Cell> cells = new LinkedHashSet<>(occupied);
            if (!cells.contains(primary)) {
                throw new IllegalArgumentException("layout option must occupy its primary cell");
            }
            int expected = role == HomeEconomySession.AssetRole.BED ? 2 : 1;
            if (cells.size() != expected) {
                throw new IllegalArgumentException(
                        role + " layout option must occupy " + expected + " cell(s)");
            }
            occupied = Collections.unmodifiableSet(cells);
        }

        public static Option single(HomeEconomySession.AssetRole role, Cell cell) {
            if (role == HomeEconomySession.AssetRole.BED) {
                throw new IllegalArgumentException("bed requires a two-cell footprint");
            }
            return new Option(role, cell, Set.of(cell));
        }

        public static Option bed(Cell foot, Cell head) {
            if (Objects.equals(foot, head)) {
                throw new IllegalArgumentException("bed foot and head must differ");
            }
            return new Option(
                    HomeEconomySession.AssetRole.BED, foot,
                    new LinkedHashSet<>(List.of(foot, head)));
        }
    }

    public record Layout(Map<HomeEconomySession.AssetRole, Option> options) {
        public Layout {
            Objects.requireNonNull(options, "options");
            EnumMap<HomeEconomySession.AssetRole, Option> copy =
                    new EnumMap<>(HomeEconomySession.AssetRole.class);
            options.forEach((role, option) -> {
                if (role == null || option == null || option.role() != role) {
                    throw new IllegalArgumentException("layout role does not match its option");
                }
                copy.put(role, option);
            });
            options = Collections.unmodifiableMap(copy);
        }

        public Option require(HomeEconomySession.AssetRole role) {
            Option option = options.get(Objects.requireNonNull(role, "role"));
            if (option == null) throw new IllegalArgumentException("layout omits " + role);
            return option;
        }
    }

    /**
     * A pinned Home interaction must execute from the exact stand whose
     * sightline and bed orientation were proved during layout selection.
     * Ordinary temporary workstations may retain the historical adjacent-cell
     * tolerance because their target can be reselected without corrupting a
     * durable Home layout.
     */
    public static boolean reachedVerifiedStand(
            boolean pinnedHome,
            Cell actual,
            Cell verified) {
        Cell current = Objects.requireNonNull(actual, "actual");
        Cell expected = Objects.requireNonNull(verified, "verified");
        if (pinnedHome) return current.equals(expected);
        long dx = (long) current.x() - expected.x();
        long dy = (long) current.y() - expected.y();
        long dz = (long) current.z() - expected.z();
        return dx * dx + dy * dy + dz * dz <= 2.25;
    }

    /** Goal radius paired with {@link #reachedVerifiedStand}. */
    public static int approachRange(boolean pinnedHome, boolean exactInteraction) {
        return pinnedHome || exactInteraction ? 0 : 1;
    }

    /**
     * Selects the first non-overlapping layout in caller-provided deterministic
     * option order. Roles may be a suffix during sequential provisioning.
     */
    public static Optional<Layout> choose(
            List<HomeEconomySession.AssetRole> requiredRoles,
            Map<HomeEconomySession.AssetRole, ? extends List<Option>> candidates) {
        List<HomeEconomySession.AssetRole> roles = List.copyOf(
                Objects.requireNonNull(requiredRoles, "requiredRoles"));
        Objects.requireNonNull(candidates, "candidates");
        if (roles.isEmpty()) return Optional.of(new Layout(Map.of()));
        if (new LinkedHashSet<>(roles).size() != roles.size()) {
            throw new IllegalArgumentException("required Home roles must be unique");
        }
        EnumMap<HomeEconomySession.AssetRole, Option> chosen =
                new EnumMap<>(HomeEconomySession.AssetRole.class);
        return choose(roles, candidates, 0, new LinkedHashSet<>(), chosen)
                ? Optional.of(new Layout(chosen))
                : Optional.empty();
    }

    /**
     * Selects a layout in physical placement order and preserves the exact
     * access cell needed by every later placement.
     *
     * <p>Disjoint asset footprints are not sufficient for sequential Minecraft
     * placement. An earlier chest can occupy the only stand from which the
     * later bed may be placed even though neither of the bed blocks overlaps
     * the chest. The caller supplies the already raycast-proved stand for each
     * option; forward checking then backtracks whenever an earlier asset would
     * consume a later option's access cell.</p>
     */
    public static Optional<Layout> chooseSequential(
            List<HomeEconomySession.AssetRole> requiredRoles,
            Map<HomeEconomySession.AssetRole, ? extends List<Option>> candidates,
            Map<Option, Cell> placementAccessCells) {
        return chooseSequential(
                requiredRoles, candidates, placementAccessCells, Set.of());
    }

    /**
     * Selects a sequential layout while keeping a proved Home mobility route
     * free of asset footprints. Placement may be performed while standing in
     * that route; only leaving a solid asset in it is forbidden.
     */
    public static Optional<Layout> chooseSequential(
            List<HomeEconomySession.AssetRole> requiredRoles,
            Map<HomeEconomySession.AssetRole, ? extends List<Option>> candidates,
            Map<Option, Cell> placementAccessCells,
            Set<Cell> reservedMobilityCells) {
        List<HomeEconomySession.AssetRole> roles = List.copyOf(
                Objects.requireNonNull(requiredRoles, "requiredRoles"));
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(placementAccessCells, "placementAccessCells");
        Set<Cell> reserved = Set.copyOf(Objects.requireNonNull(
                reservedMobilityCells, "reservedMobilityCells"));
        if (roles.isEmpty()) return Optional.of(new Layout(Map.of()));
        if (new LinkedHashSet<>(roles).size() != roles.size()) {
            throw new IllegalArgumentException("required Home roles must be unique");
        }
        EnumMap<HomeEconomySession.AssetRole, Option> chosen =
                new EnumMap<>(HomeEconomySession.AssetRole.class);
        return chooseSequential(
                roles, candidates, placementAccessCells, reserved, 0,
                new LinkedHashSet<>(), chosen)
                ? Optional.of(new Layout(chosen))
                : Optional.empty();
    }

    private static boolean choose(
            List<HomeEconomySession.AssetRole> roles,
            Map<HomeEconomySession.AssetRole, ? extends List<Option>> candidates,
            int index,
            Set<Cell> occupied,
            EnumMap<HomeEconomySession.AssetRole, Option> chosen) {
        if (index >= roles.size()) return true;
        HomeEconomySession.AssetRole role = Objects.requireNonNull(
                roles.get(index), "required role");
        List<Option> available = candidates.get(role);
        List<Option> options = available == null
                ? List.of() : new ArrayList<>(available);
        for (Option option : options) {
            if (option == null || option.role() != role
                    || option.occupied().stream().anyMatch(occupied::contains)) continue;
            chosen.put(role, option);
            occupied.addAll(option.occupied());
            if (choose(roles, candidates, index + 1, occupied, chosen)) return true;
            option.occupied().forEach(occupied::remove);
            chosen.remove(role);
        }
        return false;
    }

    private static boolean chooseSequential(
            List<HomeEconomySession.AssetRole> roles,
            Map<HomeEconomySession.AssetRole, ? extends List<Option>> candidates,
            Map<Option, Cell> placementAccessCells,
            Set<Cell> reservedMobilityCells,
            int index,
            Set<Cell> occupied,
            EnumMap<HomeEconomySession.AssetRole, Option> chosen) {
        if (index >= roles.size()) return true;
        HomeEconomySession.AssetRole role = Objects.requireNonNull(
                roles.get(index), "required role");
        List<Option> available = candidates.get(role);
        List<Option> options = available == null
                ? List.of() : new ArrayList<>(available);
        for (Option option : options) {
            if (option == null || option.role() != role) continue;
            Cell access = placementAccessCells.get(option);
            if (access == null
                    || option.occupied().contains(access)
                    || occupied.contains(access)
                    || option.occupied().stream().anyMatch(reservedMobilityCells::contains)
                    || option.occupied().stream().anyMatch(occupied::contains)) {
                continue;
            }
            chosen.put(role, option);
            occupied.addAll(option.occupied());
            if (chooseSequential(
                    roles, candidates, placementAccessCells,
                    reservedMobilityCells, index + 1,
                    occupied, chosen)) {
                return true;
            }
            option.occupied().forEach(occupied::remove);
            chosen.remove(role);
        }
        return false;
    }
}
