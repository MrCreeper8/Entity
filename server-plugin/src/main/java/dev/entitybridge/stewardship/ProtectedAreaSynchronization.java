package dev.entitybridge.stewardship;

import com.google.gson.JsonObject;
import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/** Connection-scoped acknowledgement fence for risky Entity world effects. */
public final class ProtectedAreaSynchronization {
    private final ProtectedAreaRegistry registry;
    private final Predicate<JsonObject> sender;
    private boolean connected;
    private long acknowledgedRevision = -1L;
    private String acknowledgedDigest = "";

    public ProtectedAreaSynchronization(
            ProtectedAreaRegistry registry,
            Predicate<JsonObject> sender) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public synchronized boolean connectionChanged(boolean connected) {
        this.connected = connected;
        revokeAcknowledgement();
        return connected && publishCurrent();
    }

    /** Revokes the prior revision before sending the newly durable snapshot. */
    public synchronized boolean policyChanged() {
        revokeAcknowledgement();
        return connected && publishCurrent();
    }

    public synchronized AcknowledgementResult acknowledge(long revision, String digest) {
        ProtectedAreaPolicy.Snapshot expected = registry.snapshot();
        String checkedDigest = Objects.requireNonNullElse(digest, "")
                .trim().toLowerCase(Locale.ROOT);
        if (!connected) {
            return new AcknowledgementResult(false, "no authenticated Entity connection");
        }
        if (registry.failClosed()) {
            return new AcknowledgementResult(false, "Paper policy store is fail-closed");
        }
        if (revision != expected.revision() || !same(expected.digest(), checkedDigest)) {
            revokeAcknowledgement();
            return new AcknowledgementResult(
                    false,
                    "stale or mismatched protected-area policy acknowledgement");
        }
        acknowledgedRevision = revision;
        acknowledgedDigest = expected.digest();
        return new AcknowledgementResult(true, "exact protected-area policy acknowledged");
    }

    public synchronized boolean mutationAuthorityReady() {
        ProtectedAreaPolicy.Snapshot current = registry.snapshot();
        return connected
                && !registry.failClosed()
                && acknowledgedRevision == current.revision()
                && same(acknowledgedDigest, current.digest());
    }

    public synchronized Status status() {
        ProtectedAreaPolicy.Snapshot current = registry.snapshot();
        return new Status(
                connected,
                mutationAuthorityReady(),
                current.revision(),
                current.digest(),
                acknowledgedRevision,
                acknowledgedDigest,
                registry.failClosed());
    }

    private boolean publishCurrent() {
        return sender.test(ProtectedAreaProtocol.policyFrame(
                registry.snapshot(), registry.failClosed()));
    }

    private void revokeAcknowledgement() {
        acknowledgedRevision = -1L;
        acknowledgedDigest = "";
    }

    private static boolean same(String left, String right) {
        return MessageDigest.isEqual(
                Objects.requireNonNullElse(left, "").getBytes(StandardCharsets.US_ASCII),
                Objects.requireNonNullElse(right, "").getBytes(StandardCharsets.US_ASCII));
    }

    public record AcknowledgementResult(boolean accepted, String detail) {
    }

    public record Status(
            boolean connected,
            boolean mutationAuthorityReady,
            long currentRevision,
            String currentDigest,
            long acknowledgedRevision,
            String acknowledgedDigest,
            boolean serverFailClosed) {
    }
}
