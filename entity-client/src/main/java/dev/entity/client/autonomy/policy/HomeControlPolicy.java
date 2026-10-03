package dev.entity.client.autonomy.policy;

import java.util.Objects;

/** Pure 30-second preview/confirm boundary for destructive Home identity changes. */
public final class HomeControlPolicy {
    public static final long PREVIEW_TTL_MILLIS = 30_000L;

    public enum Action {
        RELOCATE,
        CLEAR
    }

    public record Preview(
            Action action,
            String priorHomeFingerprint,
            HomeEconomySession.HomeAnchor candidate,
            long createdAtMillis,
            long expiresAtMillis) {
        public Preview {
            action = Objects.requireNonNull(action, "action");
            priorHomeFingerprint = requireFingerprint(priorHomeFingerprint);
            if ((action == Action.RELOCATE) != (candidate != null)) {
                throw new IllegalArgumentException(
                        "only relocation preview owns a candidate anchor");
            }
            if (createdAtMillis < 0L
                    || expiresAtMillis != Math.addExact(
                    createdAtMillis, PREVIEW_TTL_MILLIS)) {
                throw new IllegalArgumentException("Home preview has an invalid deadline");
            }
        }
    }

    public record Confirmation(
            boolean valid,
            String detail,
            HomeEconomySession.HomeAnchor candidate) {
        public Confirmation {
            detail = Objects.requireNonNullElse(detail, "");
            if (valid && detail.isBlank()) {
                throw new IllegalArgumentException("valid Home confirmation needs a result detail");
            }
        }
    }

    private HomeControlPolicy() {
    }

    public static Preview relocation(
            HomeEconomySession.HomeAnchor currentHome,
            HomeEconomySession.HomeAnchor candidate,
            long nowMillis) {
        Objects.requireNonNull(currentHome, "currentHome");
        Objects.requireNonNull(candidate, "candidate");
        return new Preview(
                Action.RELOCATE, currentHome.fingerprint(), candidate,
                checkedTime(nowMillis), Math.addExact(nowMillis, PREVIEW_TTL_MILLIS));
    }

    public static Preview clear(
            HomeEconomySession.HomeAnchor currentHome,
            long nowMillis) {
        Objects.requireNonNull(currentHome, "currentHome");
        return new Preview(
                Action.CLEAR, currentHome.fingerprint(), null,
                checkedTime(nowMillis), Math.addExact(nowMillis, PREVIEW_TTL_MILLIS));
    }

    public static Confirmation confirm(
            Preview preview,
            Action expectedAction,
            HomeEconomySession.HomeAnchor currentHome,
            long nowMillis) {
        Objects.requireNonNull(expectedAction, "expectedAction");
        if (preview == null) {
            return invalid("No matching Home preview exists; run the unconfirmed command first");
        }
        if (preview.action() != expectedAction) {
            return invalid("The pending preview is for "
                    + preview.action().name().toLowerCase() + ", not "
                    + expectedAction.name().toLowerCase());
        }
        if (currentHome == null
                || !preview.priorHomeFingerprint().equals(currentHome.fingerprint())) {
            return invalid("Home changed after the preview; create a fresh preview");
        }
        if (nowMillis < preview.createdAtMillis()) {
            return invalid("Clock moved behind the Home preview; create a fresh preview");
        }
        if (nowMillis > preview.expiresAtMillis()) {
            return invalid("Home preview expired after 30 seconds; create a fresh preview");
        }
        return new Confirmation(
                true,
                expectedAction == Action.RELOCATE
                        ? "confirmed the frozen Entity-position relocation candidate"
                        : "confirmed forgetting Home while preserving physical assets",
                preview.candidate());
    }

    /** The explicit command text is itself confirmation; no hidden prior preview is required. */
    public static Confirmation confirmExplicitClear(
            HomeEconomySession.HomeAnchor currentHome,
            long nowMillis) {
        Objects.requireNonNull(currentHome, "currentHome");
        return confirm(clear(currentHome, nowMillis), Action.CLEAR, currentHome, nowMillis);
    }

    private static Confirmation invalid(String detail) {
        return new Confirmation(false, detail, null);
    }

    private static long checkedTime(long value) {
        if (value < 0L) throw new IllegalArgumentException("time cannot be negative");
        return value;
    }

    private static String requireFingerprint(String value) {
        String fingerprint = Objects.requireNonNull(value, "priorHomeFingerprint").trim();
        if (fingerprint.isEmpty() || fingerprint.length() > 128) {
            throw new IllegalArgumentException("Home fingerprint must be 1..128 chars");
        }
        return fingerprint;
    }
}
