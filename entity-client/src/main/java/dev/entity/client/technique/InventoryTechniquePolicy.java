package dev.entity.client.technique;

import java.util.List;
import java.util.Objects;

/**
 * Pure, fail-closed inventory of emergency techniques.
 *
 * <p>The Minecraft adapter supplies only loaded observations. This policy is
 * the single decision source for execution, capability telemetry and player
 * status; adapters must not reconstruct availability from separate booleans.</p>
 */
public final class InventoryTechniquePolicy {
    public static final String EXISTING_WATER_LANDING_ID =
            "fall.existing_water_landing";
    public static final String WATER_BUCKET_CLUTCH_ID =
            "fall.water_bucket_clutch";

    private static final List<String> EXISTING_WATER_REQUIREMENTS = List.of(
            "falling",
            "loaded real water volume",
            "clear body corridor",
            "reachable survivable landing");
    private static final List<String> WATER_BUCKET_REQUIREMENTS = List.of(
            "falling",
            "water bucket in offhand or hotbar",
            "non-ultrawarm dimension",
            "loaded reachable replaceable target on fall corridor",
            "fluid mutation permitted");

    private InventoryTechniquePolicy() {
    }

    public static CapabilityInventory evaluate(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        Capability existingWater = existingWater(observation);
        Capability waterBucket = waterBucket(observation);
        List<Capability> capabilities = List.of(existingWater, waterBucket);

        if (!observation.falling()) {
            return new CapabilityInventory(
                    capabilities, Selection.NONE, "not in a dangerous fall");
        }
        if (existingWater.available()) {
            return new CapabilityInventory(
                    capabilities, Selection.EXISTING_WATER_LANDING,
                    "selected " + EXISTING_WATER_LANDING_ID);
        }
        if (waterBucket.available()) {
            return new CapabilityInventory(
                    capabilities, Selection.WATER_BUCKET_CLUTCH,
                    "selected " + WATER_BUCKET_CLUTCH_ID);
        }
        return new CapabilityInventory(
                capabilities,
                Selection.DECLINE,
                EXISTING_WATER_LANDING_ID + ": " + existingWater.blocker()
                        + "; " + WATER_BUCKET_CLUTCH_ID + ": "
                        + waterBucket.blocker());
    }

    private static Capability existingWater(Observation observation) {
        String blocker = "";
        if (!observation.falling()) {
            blocker = "not-falling";
        } else if (!observation.existingWaterCandidatePresent()) {
            blocker = "no-existing-water-candidate";
        } else if (!observation.existingWaterLoaded()) {
            blocker = "existing-water-geometry-unloaded";
        } else if (!observation.existingWaterVolumeSurvivable()) {
            blocker = "not-a-survivable-water-volume";
        } else if (!observation.existingWaterCorridorClear()) {
            blocker = "existing-water-corridor-obstructed";
        } else if (!observation.existingWaterReachable()) {
            blocker = "existing-water-landing-unreachable";
        }
        return new Capability(
                EXISTING_WATER_LANDING_ID,
                true,
                blocker.isEmpty(),
                EXISTING_WATER_REQUIREMENTS,
                blocker);
    }

    private static Capability waterBucket(Observation observation) {
        String blocker = "";
        if (!observation.falling()) {
            blocker = "not-falling";
        } else if (observation.waterBucketAccess() == BucketAccess.NONE) {
            blocker = "water-bucket-not-carried";
        } else if (!observation.waterBucketAccess().immediatelyUsable()) {
            blocker = "water-bucket-not-in-offhand-or-hotbar";
        } else if (observation.ultrawarmDimension()) {
            blocker = "water-unavailable-in-ultrawarm-dimension";
        } else if (!observation.clutchTargetLoaded()) {
            blocker = "clutch-target-unloaded";
        } else if (!observation.clutchTargetReachable()) {
            blocker = "clutch-target-out-of-reach";
        } else if (!observation.clutchTargetReplaceable()) {
            blocker = "clutch-target-not-replaceable";
        } else if (!observation.clutchTargetOnFallCorridor()) {
            blocker = "clutch-target-off-fall-corridor";
        } else if (!observation.fluidMutationAllowed()) {
            blocker = "fluid-mutation-denied";
        }
        return new Capability(
                WATER_BUCKET_CLUTCH_ID,
                true,
                blocker.isEmpty(),
                WATER_BUCKET_REQUIREMENTS,
                blocker);
    }

    public enum BucketAccess {
        OFF_HAND,
        HOTBAR,
        INVENTORY,
        NONE;

        public boolean immediatelyUsable() {
            return this == OFF_HAND || this == HOTBAR;
        }
    }

    public enum Selection {
        NONE,
        EXISTING_WATER_LANDING,
        WATER_BUCKET_CLUTCH,
        DECLINE
    }

    /** Exact facts observed for the current body and loaded fall corridor. */
    public record Observation(
            boolean falling,
            BucketAccess waterBucketAccess,
            boolean ultrawarmDimension,
            boolean existingWaterCandidatePresent,
            boolean existingWaterLoaded,
            boolean existingWaterVolumeSurvivable,
            boolean existingWaterCorridorClear,
            boolean existingWaterReachable,
            boolean clutchTargetLoaded,
            boolean clutchTargetReachable,
            boolean clutchTargetReplaceable,
            boolean clutchTargetOnFallCorridor,
            boolean fluidMutationAllowed) {
        public Observation {
            Objects.requireNonNull(waterBucketAccess, "waterBucketAccess");
        }

        public static Observation unavailable(boolean falling, BucketAccess access) {
            return new Observation(
                    falling, access, false,
                    false, false, false, false, false,
                    false, false, false, false, false);
        }
    }

    public record Capability(
            String id,
            boolean supported,
            boolean available,
            List<String> requirements,
            String blocker) {
        public Capability {
            id = requireText(id, "id");
            requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
            blocker = Objects.requireNonNull(blocker, "blocker");
            if (!supported && available) {
                throw new IllegalArgumentException("an unsupported capability cannot be available");
            }
            if (available && !blocker.isEmpty()) {
                throw new IllegalArgumentException("an available capability cannot have a blocker");
            }
            if (!available && blocker.isEmpty()) {
                throw new IllegalArgumentException("an unavailable capability requires a blocker");
            }
        }
    }

    public record CapabilityInventory(
            List<Capability> capabilities,
            Selection selection,
            String reason) {
        public CapabilityInventory {
            capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            selection = Objects.requireNonNull(selection, "selection");
            reason = requireText(reason, "reason");
            if (capabilities.size() != 2
                    || capabilities.stream().map(Capability::id).distinct().count() != 2L) {
                throw new IllegalArgumentException("the fall capability inventory must be complete");
            }
        }

        public Capability capability(String id) {
            Objects.requireNonNull(id, "id");
            return capabilities.stream()
                    .filter(candidate -> candidate.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown capability " + id));
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }
}
