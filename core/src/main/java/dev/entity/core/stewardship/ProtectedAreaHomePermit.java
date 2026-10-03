package dev.entity.core.stewardship;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Exact, connection-scoped authority for one narrowly-scoped protected-area
 * operation.
 *
 * <p>The protected-area snapshot remains the authority. A permit can only
 * narrow one eligible operation to one or two exact block coordinates. Home
 * maintenance cannot authorize breaking; reversible passage can authorize only
 * an exact block interaction and is validated as a player-operable portal at
 * Paper's final event boundary.</p>
 */
public final class ProtectedAreaHomePermit {
    public static final int MAX_TARGETS = 2;
    public static final int MAX_OPERATION_ID_LENGTH = 192;
    public static final int MAX_AUTHORITY_ID_LENGTH = 192;
    public static final int MAX_PERMIT_ID_LENGTH = 96;

    private ProtectedAreaHomePermit() {
    }

    public enum Purpose {
        HOME_MAINTENANCE,
        REVERSIBLE_PASSAGE;

        public boolean permits(ProtectedAreaPolicy.Action action) {
            Objects.requireNonNull(action, "action");
            return switch (this) {
                case HOME_MAINTENANCE -> action.homeMaintenanceEligible();
                case REVERSIBLE_PASSAGE ->
                        action == ProtectedAreaPolicy.Action.BLOCK_INTERACT;
            };
        }
    }

    public record Target(String dimension, int x, int y, int z) {
        public Target {
            dimension = requiredText(dimension, 256, "dimension");
        }
    }

    public record Request(
            String permitId,
            String operationId,
            String authorityId,
            Purpose purpose,
            ProtectedAreaPolicy.Action action,
            long revision,
            String digest,
            List<Target> targets) {
        public Request {
            permitId = requiredIdentifier(
                    permitId, MAX_PERMIT_ID_LENGTH, "permit id");
            operationId = requiredText(
                    operationId, MAX_OPERATION_ID_LENGTH, "operation id");
            authorityId = requiredText(
                    authorityId, MAX_AUTHORITY_ID_LENGTH, "authority id");
            purpose = Objects.requireNonNull(purpose, "purpose");
            action = Objects.requireNonNull(action, "action");
            if (!purpose.permits(action)) {
                throw new IllegalArgumentException(
                        action.name().toLowerCase(Locale.ROOT)
                                + " is not eligible for "
                                + purpose.name().toLowerCase(Locale.ROOT));
            }
            if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
            digest = Objects.requireNonNullElse(digest, "")
                    .trim().toLowerCase(Locale.ROOT);
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("policy digest must be 64 lowercase hex digits");
            }
            targets = canonicalTargets(targets);
        }

        /** Source-compatible constructor for the shipped Home call sites. */
        public Request(
                String permitId,
                String operationId,
                String authorityId,
                ProtectedAreaPolicy.Action action,
                long revision,
                String digest,
                List<Target> targets) {
            this(permitId, operationId, authorityId, Purpose.HOME_MAINTENANCE,
                    action, revision, digest, targets);
        }
    }

    public record Result(
            String permitId,
            boolean accepted,
            long revision,
            String digest,
            long expiresAtMillis,
            String code,
            String detail) {
        public Result {
            permitId = requiredIdentifier(
                    permitId, MAX_PERMIT_ID_LENGTH, "permit id");
            if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
            digest = Objects.requireNonNullElse(digest, "")
                    .trim().toLowerCase(Locale.ROOT);
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("policy digest must be 64 lowercase hex digits");
            }
            if (accepted && expiresAtMillis <= 0L) {
                throw new IllegalArgumentException("accepted permit must have an expiry");
            }
            code = Objects.requireNonNullElse(code, "").trim();
            detail = Objects.requireNonNullElse(detail, "").trim();
        }
    }

    private static List<Target> canonicalTargets(List<Target> source) {
        Objects.requireNonNull(source, "targets");
        if (source.isEmpty() || source.size() > MAX_TARGETS) {
            throw new IllegalArgumentException(
                    "Home permit needs 1-" + MAX_TARGETS + " exact targets");
        }
        Set<Target> unique = new LinkedHashSet<>();
        for (Target target : source) {
            if (!unique.add(Objects.requireNonNull(target, "target"))) {
                throw new IllegalArgumentException("Home permit targets must be unique");
            }
        }
        String dimension = unique.iterator().next().dimension();
        if (unique.stream().anyMatch(target -> !target.dimension().equals(dimension))) {
            throw new IllegalArgumentException(
                    "one Home permit cannot span dimensions");
        }
        return List.copyOf(unique);
    }

    private static String requiredIdentifier(String value, int maximum, String label) {
        String checked = requiredText(value, maximum, label);
        if (!checked.matches("[A-Za-z0-9:_-]+")) {
            throw new IllegalArgumentException(label + " contains unsupported characters");
        }
        return checked;
    }

    private static String requiredText(String value, int maximum, String label) {
        String checked = Objects.requireNonNullElse(value, "").trim();
        if (checked.isEmpty() || checked.length() > maximum
                || checked.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(label + " is required and bounded");
        }
        return checked;
    }
}
