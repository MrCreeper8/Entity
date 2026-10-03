package dev.entity.core.stewardship;

import java.util.Objects;

/**
 * Final fail-closed admission for one deliberate resource block mutation.
 *
 * <p>The caller supplies only loaded, immediately observed facts. Area membership
 * alone is never enough: the mutation must match the area's purpose, the current
 * objective, and the sustainability/egress invariants below.</p>
 */
public final class ResourceStewardshipPolicy {
    private ResourceStewardshipPolicy() {
    }

    public static Decision decide(Facts facts) {
        Objects.requireNonNull(facts, "facts");
        if (!facts.policySynchronized()) {
            return deny(Code.POLICY_UNSYNCHRONIZED,
                    "resource-area policy is not synchronized");
        }
        if (!facts.observationComplete()) {
            return deny(Code.OBSERVATION_UNAVAILABLE,
                    "the exact resource and surrounding loaded geometry are unavailable");
        }
        if (facts.protectedOverlap()) {
            return deny(Code.PROTECTED_OVERLAP,
                    "player-confirmed protected property overrides resource authority");
        }
        // Standing on a block is movement geometry, not property ownership. Vanilla and Baritone
        // already decide whether a downward break/fall is executable; resource stewardship must
        // not convert ordinary down-mining into a global mutation ban.
        if (facts.entranceBodyCell()) {
            return deny(Code.MINE_ENTRANCE,
                    "the selected mine entrance body column must remain usable");
        }
        if (facts.onlyKnownExit()) {
            return deny(Code.ONLY_KNOWN_EXIT,
                    "the mutation would seal or remove the only verified exit");
        }
        if (!facts.matchesActiveObjective()) {
            return deny(Code.UNRELATED_OBJECTIVE,
                    "the block is not part of the active resource objective or its exact access budget");
        }

        return switch (facts.intent()) {
            case MINE -> decideMine(facts);
            case HARVEST_LOG -> decideLog(facts);
            case HARVEST_TREE_ACCESS -> decideTreeAccess(facts);
            case HARVEST_CROP -> decideCrop(facts);
            case HARVEST_FLOWER -> decideFlower(facts);
        };
    }

    private static Decision decideMine(Facts facts) {
        if (!facts.insideMatchingArea()) {
            return deny(Code.OUTSIDE_MINING_AREA,
                    "resource mining requires an acknowledged three-dimensional mining area");
        }
        if (facts.material() != Material.NATURAL_MINE
                && facts.material() != Material.NATURAL_GROUND
                && facts.material() != Material.ORE) {
            return deny(Code.NOT_NATURAL_MINE_MATERIAL,
                    "the target is not admitted natural mine material");
        }
        if (facts.constructionCue()) {
            return deny(Code.CONSTRUCTION_CUE,
                    "nearby construction cues make this block player property, not mine material");
        }
        return allow("exact natural mine mutation is inside the selected volume");
    }

    private static Decision decideLog(Facts facts) {
        if (!facts.insideMatchingArea()) {
            return deny(Code.OUTSIDE_HARVESTING_AREA,
                    "tree harvesting requires an acknowledged harvesting footprint");
        }
        if (facts.material() != Material.LOG) {
            return deny(Code.NOT_HARVESTABLE,
                    "the target is not a harvestable log");
        }
        if (facts.constructionCue()) {
            return deny(Code.CONSTRUCTION_CUE,
                    "nearby construction blocks make this log part of a structure");
        }
        if ((!facts.rootedNaturalTree() || !facts.connectedLeafCanopy())
                && !facts.retainedNaturalTreeProof()) {
            return deny(Code.NOT_NATURAL_TREE,
                    "loaded geometry does not prove a rooted natural tree with a connected canopy");
        }
        // Sapling drops are probabilistic. Requiring one before the first log
        // would deadlock a legitimate zero-stock survival bootstrap. Crop
        // replanting remains exact below; trees are admitted by loaded natural
        // topology and construction rejection, not an invented sapling supply.
        return allow(facts.retainedNaturalTreeProof()
                ? "exact log remains inside the operation's initially proven natural tree"
                : "loaded rooted natural tree permits this log");
    }

    private static Decision decideTreeAccess(Facts facts) {
        if (!facts.insideMatchingArea()) {
            return deny(Code.OUTSIDE_HARVESTING_AREA,
                    "tree access requires the same acknowledged harvesting footprint");
        }
        if (facts.material() != Material.NATURAL_LEAF) {
            return deny(Code.NOT_HARVESTABLE,
                    "the exact tree-access target is not a natural leaf");
        }
        if (facts.constructionCue()) {
            return deny(Code.CONSTRUCTION_CUE,
                    "nearby construction makes this leaf part of player property");
        }
        if (!facts.retainedNaturalTreeProof()) {
            return deny(Code.NOT_NATURAL_TREE,
                    "a leaf is mutable only inside one initially proven natural-tree job");
        }
        return allow("exact natural canopy cell is inside the committed tree access budget");
    }

    private static Decision decideCrop(Facts facts) {
        if (!facts.insideMatchingArea()) {
            return deny(Code.OUTSIDE_HARVESTING_AREA,
                    "crop harvesting requires an acknowledged harvesting footprint");
        }
        if (facts.material() != Material.CROP) {
            return deny(Code.NOT_HARVESTABLE,
                    "the target is not a supported crop");
        }
        if (!facts.matureCrop()) {
            return deny(Code.IMMATURE_CROP,
                    "the crop is not mature");
        }
        if (!facts.replantItemReserved()) {
            return deny(Code.REPLANT_ITEM_MISSING,
                    "the exact crop replant item must be reserved before harvest");
        }
        return allow("mature crop and reserved replant item permit one harvest");
    }

    private static Decision decideFlower(Facts facts) {
        if (!facts.insideMatchingArea()) return deny(Code.OUTSIDE_HARVESTING_AREA,
                "flower harvesting requires the acknowledged whole-plant footprint");
        if (facts.material() != Material.FLOWER) return deny(Code.NOT_HARVESTABLE,
                "the target is not a complete supported flower");
        return allow("loaded supported flower and its complete mutation footprint are admitted");
    }

    private static Decision allow(String detail) {
        return new Decision(true, Code.ALLOWED, detail);
    }

    private static Decision deny(Code code, String detail) {
        return new Decision(false, code, detail);
    }

    public enum Intent {
        MINE,
        HARVEST_LOG,
        HARVEST_TREE_ACCESS,
        HARVEST_CROP,
        HARVEST_FLOWER
    }

    /** Material classification comes from loaded block state, never an item-name guess. */
    public enum Material {
        NATURAL_MINE,
        NATURAL_GROUND,
        ORE,
        LOG,
        NATURAL_LEAF,
        CROP,
        FLOWER,
        CONTAINER,
        WORKSTATION,
        PORTAL,
        REDSTONE,
        CONSTRUCTION,
        UNKNOWN
    }

    public enum Code {
        ALLOWED,
        POLICY_UNSYNCHRONIZED,
        OBSERVATION_UNAVAILABLE,
        PROTECTED_OVERLAP,
        OUTSIDE_MINING_AREA,
        OUTSIDE_HARVESTING_AREA,
        MINE_ENTRANCE,
        ONLY_KNOWN_EXIT,
        UNRELATED_OBJECTIVE,
        NOT_NATURAL_MINE_MATERIAL,
        NOT_HARVESTABLE,
        CONSTRUCTION_CUE,
        NOT_NATURAL_TREE,
        IMMATURE_CROP,
        REPLANT_ITEM_MISSING
    }

    public record Facts(
            Intent intent,
            Material material,
            boolean policySynchronized,
            boolean observationComplete,
            boolean insideMatchingArea,
            boolean protectedOverlap,
            boolean matchesActiveObjective,
            boolean supportsEntityBody,
            boolean entranceBodyCell,
            boolean onlyKnownExit,
            boolean constructionCue,
            boolean rootedNaturalTree,
            boolean connectedLeafCanopy,
            boolean matureCrop,
            boolean replantItemReserved,
            boolean retainedNaturalTreeProof) {
        public Facts {
            intent = Objects.requireNonNull(intent, "intent");
            material = Objects.requireNonNull(material, "material");
            if (retainedNaturalTreeProof
                    && intent != Intent.HARVEST_LOG
                    && intent != Intent.HARVEST_TREE_ACCESS) {
                throw new IllegalArgumentException(
                        "retained natural-tree proof applies only to a committed tree job");
            }
        }
    }

    public record Decision(boolean allowed, Code code, String detail) {
        public Decision {
            code = Objects.requireNonNull(code, "code");
            detail = Objects.requireNonNullElse(detail, "").trim();
            if (detail.isEmpty()) throw new IllegalArgumentException("detail is required");
            if (allowed != (code == Code.ALLOWED)) {
                throw new IllegalArgumentException("resource decision/code mismatch");
            }
        }
    }
}
