package dev.entity.core.stewardship;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure, fail-closed admission policy for every deliberate Entity attack.
 *
 * <p>Minecraft adapters supply only currently observable relationship facts.
 * The policy deliberately distinguishes a hostile from an ordinary player or
 * passive animal so loss of the authenticated owner/controller snapshot never
 * disables self-defense while it does prevent an unsafe PvP guess.</p>
 */
public final class EntityDispositionPolicy {
    public static final long HUNT_OBSERVATION_RETRY_MILLIS = 5_000L;

    private EntityDispositionPolicy() {
    }

    public static Result decide(
            Purpose purpose,
            Facts facts,
            Set<String> protectedPlayerNames,
            boolean relationshipPolicyAvailable) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(facts, "facts");
        Set<String> protectedNames = Objects.requireNonNullElse(
                        protectedPlayerNames, Set.<String>of())
                .stream()
                .map(EntityDispositionPolicy::normalize)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        if (facts.self()) return Result.deny(Code.SELF, "Entity cannot attack itself");
        if (facts.teammate()) {
            return Result.deny(Code.TEAMMATE, "scoreboard teammate is friendly");
        }
        if (facts.tamed()) {
            return Result.deny(Code.TAMED, "player-tamed entity is protected");
        }
        if (facts.customNamed()) {
            return Result.deny(Code.NAMED, "custom-named entity is protected");
        }
        if (facts.leashed()) {
            return Result.deny(Code.LEASHED, "leashed entity is protected");
        }
        if (facts.baby() && facts.kind() != Kind.HOSTILE) {
            return Result.deny(Code.BABY, "baby non-hostile entity is protected");
        }

        return switch (facts.kind()) {
            case HOSTILE -> purpose == Purpose.HUNT
                    ? Result.deny(Code.WRONG_PURPOSE, "food hunts cannot select hostiles")
                    : Result.allow();
            case PLAYER -> {
                if (!relationshipPolicyAvailable) {
                    yield Result.deny(
                            Code.POLICY_UNAVAILABLE,
                            "authenticated owner/controller policy is unavailable");
                }
                if (protectedNames.contains(normalize(facts.name()))) {
                    yield Result.deny(Code.PROTECTED_PLAYER, "owner or trusted player is protected");
                }
                if (purpose != Purpose.EXPLICIT_ATTACK && purpose != Purpose.RETALIATION) {
                    yield Result.deny(
                            Code.WRONG_PURPOSE,
                            "players require an explicit target or a verified retaliatory hit");
                }
                yield Result.allow();
            }
            case PASSIVE -> purpose == Purpose.PROTECTION
                    ? Result.deny(Code.WRONG_PURPOSE, "passive entity is not a protection threat")
                    : Result.allow();
            case UTILITY, OTHER -> Result.deny(
                    Code.FRIENDLY_UTILITY,
                    "non-hostile utility entity is protected");
        };
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Converts a final, fail-closed attack denial into mission behavior.
     *
     * <p>A hunt must never issue an unsafe attack, but a target-specific denial must not
     * permanently block the whole bounded food mission either. A momentarily incomplete loaded
     * observation is held briefly because chunk/entity facts commonly settle after movement or
     * knockback; a persistent gap and definitive livestock protections reject only that animal so
     * the bounded hunt can safely choose another one.</p>
     */
    public static HuntDenialAction huntDenialAction(
            boolean boundedHunt,
            Code code,
            long unavailableForMillis) {
        Objects.requireNonNull(code, "code");
        if (unavailableForMillis < 0L) {
            throw new IllegalArgumentException("unavailable duration cannot be negative");
        }
        if (!boundedHunt) return HuntDenialAction.BLOCK;
        if (code == Code.LIVESTOCK_POLICY_UNAVAILABLE) {
            return unavailableForMillis < HUNT_OBSERVATION_RETRY_MILLIS
                    ? HuntDenialAction.WAIT_FOR_OBSERVATION
                    : HuntDenialAction.RETARGET;
        }
        return switch (code) {
            case TAMED, NAMED, LEASHED, BABY,
                    PROTECTED_LIVESTOCK,
                    CONTAINED_LIVESTOCK, BREEDING_FLOOR -> HuntDenialAction.RETARGET;
            default -> HuntDenialAction.BLOCK;
        };
    }

    public enum Purpose {
        PROTECTION,
        RETALIATION,
        EXPLICIT_ATTACK,
        HUNT
    }

    public enum Kind {
        PLAYER,
        HOSTILE,
        PASSIVE,
        UTILITY,
        OTHER
    }

    public enum HuntDenialAction {
        WAIT_FOR_OBSERVATION,
        RETARGET,
        BLOCK
    }

    public enum Code {
        ALLOWED,
        SELF,
        PROTECTED_PLAYER,
        TEAMMATE,
        TAMED,
        NAMED,
        LEASHED,
        BABY,
        POLICY_UNAVAILABLE,
        FRIENDLY_UTILITY,
        WRONG_PURPOSE,
        LIVESTOCK_POLICY_UNAVAILABLE,
        PROTECTED_LIVESTOCK,
        CONTAINED_LIVESTOCK,
        BREEDING_FLOOR,
        CREEPER_PROPERTY_BUFFER,
        CREEPER_PROPERTY_POLICY_UNAVAILABLE
    }

    public record Facts(
            Kind kind,
            String name,
            boolean self,
            boolean teammate,
            boolean tamed,
            boolean customNamed,
            boolean leashed,
            boolean baby) {
        public Facts {
            kind = Objects.requireNonNull(kind, "kind");
            name = Objects.requireNonNullElse(name, "").trim();
        }
    }

    public record Result(boolean allowed, Code code, String detail) {
        public Result {
            code = Objects.requireNonNull(code, "code");
            detail = Objects.requireNonNullElse(detail, "");
            if (allowed != (code == Code.ALLOWED)) {
                throw new IllegalArgumentException("allowed result/code mismatch");
            }
        }

        public static Result allow() {
            return new Result(true, Code.ALLOWED, "target relationship permits this attack");
        }

        public static Result deny(Code code, String detail) {
            if (code == Code.ALLOWED) throw new IllegalArgumentException("deny code cannot be ALLOWED");
            return new Result(false, code, detail);
        }
    }
}
