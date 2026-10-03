package dev.entity.client.baritone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure request/result contract between Minecraft/Baritone observations and the
 * resource-descent safety policy.
 *
 * <p>The adapter is responsible for classifying live collision shapes and for
 * supplying exact block-state fingerprints. It may act on {@link Decision}
 * break coordinates only when the state is {@link DecisionState#AUTHORIZED}.</p>
 */
public final class ResourceDescentStep {
    private static final Comparator<BlockPosition> POSITION_ORDER = Comparator
            .comparingInt(BlockPosition::x)
            .thenComparingInt(BlockPosition::y)
            .thenComparingInt(BlockPosition::z);

    private ResourceDescentStep() {
    }

    /** Immutable, collision-aware observation supplied by the Minecraft adapter. */
    @FunctionalInterface
    public interface WorldProbe {
        Observation observe(BlockPosition position);
    }

    public record StepRequest(
            String routeId,
            BlockPosition currentFeet,
            BlockPosition nextFeet,
            List<BlockPosition> proposedBreaks,
            boolean resourceMutationAuthorized,
            MutationLifetime mutationLifetime,
            Optional<RestorationCommit> restorationCommit) {
        public StepRequest {
            routeId = requiredText(routeId, "routeId");
            Objects.requireNonNull(currentFeet, "currentFeet");
            Objects.requireNonNull(nextFeet, "nextFeet");
            proposedBreaks = normalizedUnique(
                    proposedBreaks, POSITION_ORDER, "proposedBreaks");
            Objects.requireNonNull(mutationLifetime, "mutationLifetime");
            restorationCommit = Objects.requireNonNull(
                    restorationCommit, "restorationCommit");
        }

        public static StepRequest permanentWorksite(
                String routeId,
                BlockPosition currentFeet,
                BlockPosition nextFeet,
                List<BlockPosition> proposedBreaks) {
            return new StepRequest(
                    routeId,
                    currentFeet,
                    nextFeet,
                    proposedBreaks,
                    true,
                    proposedBreaks.isEmpty()
                            ? MutationLifetime.NO_MUTATION
                            : MutationLifetime.PERMANENT,
                    Optional.empty());
        }
    }

    public record BlockPosition(int x, int y, int z) {
        public BlockPosition add(int dx, int dy, int dz) {
            return new BlockPosition(
                    Math.addExact(x, dx),
                    Math.addExact(y, dy),
                    Math.addExact(z, dz));
        }

        public BlockPosition up() {
            return add(0, 1, 0);
        }

        public BlockPosition up(int distance) {
            return add(0, distance, 0);
        }

        public BlockPosition down() {
            return add(0, -1, 0);
        }
    }

    public record Observation(
            CellKind kind,
            String blockFingerprint,
            RouteZone zone) {
        public Observation {
            Objects.requireNonNull(kind, "kind");
            blockFingerprint = requiredText(blockFingerprint, "blockFingerprint");
            Objects.requireNonNull(zone, "zone");
        }

        public static Observation worksite(CellKind kind, String fingerprint) {
            return new Observation(kind, fingerprint, RouteZone.RESOURCE_WORKSITE);
        }
    }

    /**
     * Classifications describe collision and route safety, not only material.
     * A partial block is stable only when its live shape gives reliable support.
     */
    public enum CellKind {
        OPEN(true, false, false, false),
        STABLE_BREAKABLE(false, true, true, false),
        STABLE_UNBREAKABLE(false, true, false, false),
        OBSTRUCTING_BREAKABLE(false, false, true, false),
        OBSTRUCTING_UNBREAKABLE(false, false, false, false),
        FALLING_BLOCK(false, false, true, false),
        WATER(false, false, false, true),
        LAVA(false, false, false, true),
        OTHER_FLUID(false, false, false, true),
        HAZARD(false, false, false, false),
        UNLOADED(false, false, false, false);

        private final boolean bodyPassable;
        private final boolean stableFooting;
        private final boolean breakable;
        private final boolean fluid;

        CellKind(
                boolean bodyPassable,
                boolean stableFooting,
                boolean breakable,
                boolean fluid) {
            this.bodyPassable = bodyPassable;
            this.stableFooting = stableFooting;
            this.breakable = breakable;
            this.fluid = fluid;
        }

        public boolean bodyPassable() {
            return bodyPassable;
        }

        public boolean stableFooting() {
            return stableFooting;
        }

        public boolean breakable() {
            return breakable;
        }

        public boolean fluid() {
            return fluid;
        }
    }

    public enum RouteZone {
        RESOURCE_WORKSITE(false),
        PROTECTED_PROPERTY(true);

        private final boolean cleanProperty;

        RouteZone(boolean cleanProperty) {
            this.cleanProperty = cleanProperty;
        }

        public boolean cleanProperty() {
            return cleanProperty;
        }
    }

    public enum MutationLifetime {
        NO_MUTATION,
        PERMANENT,
        TEMPORARY_RESTORED
    }

    public enum DecisionState {
        AUTHORIZED,
        RESTORATION_COMMIT_REQUIRED,
        REJECTED
    }

    public enum ChangeDisposition {
        NO_CHANGE,
        MAY_REMAIN_AT_RESOURCE_WORKSITE,
        RESTORE_AFTER_ROUTE_RELEASE,
        NOT_AUTHORIZED
    }

    public enum RestoreReleaseCondition {
        ROUTE_RELEASED_AND_ALL_BODIES_CLEAR
    }

    public enum Rejection {
        NONE,
        VERTICAL_SHAFT,
        NON_CARDINAL_STEP,
        DESCENT_TOO_STEEP,
        REVERSE_ASCENT_TOO_STEEP,
        CURRENT_SUPPORT_MUTATION,
        CURRENT_BODY_MUTATION,
        OUT_OF_CORRIDOR_MUTATION,
        RESOURCE_MUTATION_AUTHORITY_REQUIRED,
        MUTATION_LIFETIME_REQUIRED,
        MISSING_WORLD_DATA,
        LAVA_EXPOSURE,
        FLUID_EXPOSURE,
        FALLING_BLOCK_TRAP,
        HAZARDOUS_CELL,
        UNSTABLE_CURRENT_FOOTING,
        UNSTABLE_DESTINATION_FOOTING,
        CURRENT_BODY_NOT_CLEAR,
        BLOCK_NOT_SAFELY_BREAKABLE,
        MISSING_BODY_CLEARANCE,
        PERMANENT_PROPERTY_EXCAVATION,
        RESTORATION_COMMIT_REQUIRED
    }

    public record OriginalBlock(BlockPosition position, String blockFingerprint) {
        public OriginalBlock {
            Objects.requireNonNull(position, "position");
            blockFingerprint = requiredText(blockFingerprint, "blockFingerprint");
        }
    }

    public record RestorationObligation(
            String routeId,
            List<OriginalBlock> originalBlocks,
            RestoreReleaseCondition releaseCondition) {
        public RestorationObligation {
            routeId = requiredText(routeId, "routeId");
            originalBlocks = normalizedUnique(
                    originalBlocks,
                    Comparator.comparing(OriginalBlock::position, POSITION_ORDER),
                    "originalBlocks");
            if (originalBlocks.isEmpty()) {
                throw new IllegalArgumentException("a restoration obligation needs blocks");
            }
            Objects.requireNonNull(releaseCondition, "releaseCondition");
        }
    }

    /** Evidence supplied only after the obligation has been durably persisted. */
    public record RestorationCommit(
            String routeId,
            List<OriginalBlock> originalBlocks,
            RestoreReleaseCondition releaseCondition) {
        public RestorationCommit {
            routeId = requiredText(routeId, "routeId");
            originalBlocks = normalizedUnique(
                    originalBlocks,
                    Comparator.comparing(OriginalBlock::position, POSITION_ORDER),
                    "originalBlocks");
            if (originalBlocks.isEmpty()) {
                throw new IllegalArgumentException("a restoration commit needs blocks");
            }
            Objects.requireNonNull(releaseCondition, "releaseCondition");
        }

        public static RestorationCommit from(RestorationObligation obligation) {
            Objects.requireNonNull(obligation, "obligation");
            return new RestorationCommit(
                    obligation.routeId(),
                    obligation.originalBlocks(),
                    obligation.releaseCondition());
        }
    }

    public record ReverseTraversal(BlockPosition fromFeet, BlockPosition toFeet) {
        public ReverseTraversal {
            Objects.requireNonNull(fromFeet, "fromFeet");
            Objects.requireNonNull(toFeet, "toFeet");
        }
    }

    public record Decision(
            DecisionState state,
            Rejection rejection,
            List<BlockPosition> authorizedBreaks,
            ChangeDisposition changeDisposition,
            Optional<RestorationObligation> restoration,
            Optional<ReverseTraversal> reverseTraversal,
            String detail) {
        public Decision {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(rejection, "rejection");
            authorizedBreaks = normalizedUnique(
                    authorizedBreaks, POSITION_ORDER, "authorizedBreaks");
            Objects.requireNonNull(changeDisposition, "changeDisposition");
            restoration = Objects.requireNonNull(restoration, "restoration");
            reverseTraversal = Objects.requireNonNull(reverseTraversal, "reverseTraversal");
            detail = Objects.requireNonNullElse(detail, "");
            if (state == DecisionState.AUTHORIZED
                    && (rejection != Rejection.NONE || reverseTraversal.isEmpty())) {
                throw new IllegalArgumentException("authorized decisions need a reverse route");
            }
            if (state != DecisionState.AUTHORIZED && !authorizedBreaks.isEmpty()) {
                throw new IllegalArgumentException("unauthorized decisions may not expose breaks");
            }
            if (state == DecisionState.REJECTED
                    && (rejection == Rejection.NONE
                    || changeDisposition != ChangeDisposition.NOT_AUTHORIZED)) {
                throw new IllegalArgumentException("invalid rejected decision");
            }
            if (state == DecisionState.RESTORATION_COMMIT_REQUIRED
                    && (rejection != Rejection.RESTORATION_COMMIT_REQUIRED
                    || restoration.isEmpty())) {
                throw new IllegalArgumentException("restore gate needs its exact obligation");
            }
        }

        static Decision authorized(
                List<BlockPosition> breaks,
                ChangeDisposition disposition,
                Optional<RestorationObligation> restoration,
                ReverseTraversal reverse,
                String detail) {
            return new Decision(
                    DecisionState.AUTHORIZED,
                    Rejection.NONE,
                    breaks,
                    disposition,
                    restoration,
                    Optional.of(reverse),
                    detail);
        }

        static Decision restorationRequired(
                RestorationObligation obligation,
                String detail) {
            return new Decision(
                    DecisionState.RESTORATION_COMMIT_REQUIRED,
                    Rejection.RESTORATION_COMMIT_REQUIRED,
                    List.of(),
                    ChangeDisposition.NOT_AUTHORIZED,
                    Optional.of(obligation),
                    Optional.empty(),
                    detail);
        }

        static Decision rejected(Rejection rejection, String detail) {
            if (rejection == Rejection.NONE
                    || rejection == Rejection.RESTORATION_COMMIT_REQUIRED) {
                throw new IllegalArgumentException("invalid rejection factory reason");
            }
            return new Decision(
                    DecisionState.REJECTED,
                    rejection,
                    List.of(),
                    ChangeDisposition.NOT_AUTHORIZED,
                    Optional.empty(),
                    Optional.empty(),
                    detail);
        }
    }

    private static <T> List<T> normalizedUnique(
            List<T> values,
            Comparator<? super T> comparator,
            String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value, name + " entry"));
        copy.sort(comparator);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).equals(copy.get(index))) {
                throw new IllegalArgumentException(name + " contains duplicate " + copy.get(index));
            }
        }
        return List.copyOf(copy);
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
