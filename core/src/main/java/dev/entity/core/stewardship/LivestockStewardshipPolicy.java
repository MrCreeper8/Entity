package dev.entity.core.stewardship;

import java.util.Objects;

/** Pure fail-closed admission policy for deliberate food-animal hunts. */
public final class LivestockStewardshipPolicy {
    private LivestockStewardshipPolicy() {
    }

    public static Decision decide(Facts facts) {
        Objects.requireNonNull(facts, "facts");
        if (!facts.observationComplete()) {
            return Decision.deny(Code.OBSERVATION_UNAVAILABLE,
                    "loaded livestock/property observation is unavailable");
        }
        if (facts.insideProtectedProperty()) {
            return Decision.deny(Code.INSIDE_PROTECTED_PROPERTY,
                    "animals inside named protected property are protected");
        }
        if (facts.tamed()) {
            return Decision.deny(Code.TAMED, "player-tamed livestock is protected");
        }
        if (facts.customNamed()) {
            return Decision.deny(Code.NAMED, "custom-named livestock is protected");
        }
        if (facts.leashed()) {
            return Decision.deny(Code.LEASHED, "leashed livestock is protected");
        }
        if (facts.baby()) {
            return Decision.deny(Code.BABY, "baby livestock is protected");
        }
        if (facts.containedCohort()) {
            return Decision.deny(Code.CONTAINED_COHORT,
                    "loaded fenced or enclosed livestock is protected");
        }
        // A loaded cohort is not player property. Its size changes as chunks load
        // while approaching, so a global breeding quota both invents protection
        // and can invalidate the same wild target halfway through a hunt.
        return Decision.allow();
    }

    public enum Code {
        ALLOWED,
        OBSERVATION_UNAVAILABLE,
        INSIDE_PROTECTED_PROPERTY,
        TAMED,
        NAMED,
        LEASHED,
        BABY,
        CONTAINED_COHORT,
        BREEDING_FLOOR
    }

    public record Facts(
            boolean observationComplete,
            boolean insideProtectedProperty,
            boolean containedCohort,
            boolean tamed,
            boolean customNamed,
            boolean leashed,
            boolean baby,
            int loadedBreedReadyAdults) {
        public Facts {
            if (loadedBreedReadyAdults < 0) {
                throw new IllegalArgumentException(
                        "loaded breed-ready adult count cannot be negative");
            }
        }
    }

    public record Decision(boolean allowed, Code code, String detail) {
        public Decision {
            code = Objects.requireNonNull(code, "code");
            detail = Objects.requireNonNullElse(detail, "");
            if (allowed != (code == Code.ALLOWED)) {
                throw new IllegalArgumentException("allowed livestock decision/code mismatch");
            }
        }

        public static Decision allow() {
            return new Decision(true, Code.ALLOWED,
                    "wild adult outside protected property is available for hunting");
        }

        public static Decision deny(Code code, String detail) {
            if (code == Code.ALLOWED) {
                throw new IllegalArgumentException("deny code cannot be ALLOWED");
            }
            return new Decision(false, code, detail);
        }
    }
}
