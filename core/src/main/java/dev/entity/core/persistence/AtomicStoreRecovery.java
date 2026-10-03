package dev.entity.core.persistence;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Resolves the two durable halves of a sibling-temporary atomic store.
 *
 * <p>The temporary file is not disposable until it has been decoded. It may be the only complete
 * state left after a crash. Conversely, an unsupported schema is never ignored in favor of an
 * older supported half: doing so would silently downgrade state written by newer software.</p>
 */
public final class AtomicStoreRecovery {
    private AtomicStoreRecovery() {}

    @FunctionalInterface
    public interface Decoder<T> {
        Decoded<T> decode(byte[] encoded, Path source) throws IOException;
    }

    /** Conservatively reconciles two valid halves when one store has mergeable safety evidence. */
    @FunctionalInterface
    public interface ValidPairResolver<T> {
        T resolve(T live, T temporary) throws IOException;
    }

    /** Sequence is the store's monotonic snapshot revision, not a filesystem timestamp. */
    public record Decoded<T>(T value, long sequence, int schemaVersion) {
        public Decoded {
            value = Objects.requireNonNull(value, "value");
            if (sequence < 0L) throw new IllegalArgumentException("sequence must be non-negative");
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("schemaVersion must be positive");
            }
        }
    }

    /** Signals a structurally readable file owned by a different software schema. */
    public static final class UnsupportedSchemaException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int schemaVersion;

        public UnsupportedSchemaException(String storeName, int schemaVersion) {
            super("unsupported " + storeName + " schema " + schemaVersion);
            this.schemaVersion = schemaVersion;
        }

        public int schemaVersion() {
            return schemaVersion;
        }
    }

    /** A fail-closed recovery decision with a stable classification for callers. */
    public static final class RecoveryException extends IOException {
        private static final long serialVersionUID = 1L;
        private final boolean unsupportedSchema;

        private RecoveryException(String message, boolean unsupportedSchema) {
            super(message);
            this.unsupportedSchema = unsupportedSchema;
        }

        /** True when at least one preserved candidate belongs to a newer/unknown schema. */
        public boolean unsupportedSchema() {
            return unsupportedSchema;
        }
    }

    /**
     * Loads an absent, live, or interrupted temporary snapshot without guessing between evidence.
     * Corrupt siblings are copied into a deterministic quarantine before an unambiguous valid half
     * is used. Irreconcilable pairs stay in place and are also copied, so a second load cannot turn
     * the failure into a false "absent" default.
     */
    public static <T> T load(
            Path livePath,
            Path temporaryPath,
            String storeName,
            Supplier<T> absentState,
            Decoder<T> decoder) throws IOException {
        return load(livePath, temporaryPath, storeName, absentState, decoder, null);
    }

    /** Variant for journals whose two valid halves must be conservatively merged. */
    public static <T> T load(
            Path livePath,
            Path temporaryPath,
            String storeName,
            Supplier<T> absentState,
            Decoder<T> decoder,
            ValidPairResolver<T> validPairResolver) throws IOException {
        Path live = normalize(livePath, "livePath");
        Path temporary = normalize(temporaryPath, "temporaryPath");
        String name = requireName(storeName);
        Objects.requireNonNull(absentState, "absentState");
        Objects.requireNonNull(decoder, "decoder");

        boolean liveExists = definiteExists(live);
        boolean temporaryExists = definiteExists(temporary);
        if (!liveExists && !temporaryExists) return Objects.requireNonNull(absentState.get());

        Candidate<T> liveCandidate = liveExists
                ? inspect(live, Role.LIVE, decoder) : Candidate.absent(Role.LIVE, live);
        Candidate<T> temporaryCandidate = temporaryExists
                ? inspect(temporary, Role.TEMPORARY, decoder)
                : Candidate.absent(Role.TEMPORARY, temporary);

        if (liveCandidate.unsupported() || temporaryCandidate.unsupported()) {
            throw quarantineFailure(
                    live,
                    name + " recovery refused a schema downgrade",
                    liveCandidate,
                    temporaryCandidate);
        }

        if (liveCandidate.valid() && temporaryCandidate.corrupt()) {
            quarantineAndRemove(live, temporaryCandidate, "corrupt");
            return liveCandidate.decoded().value();
        }
        if (liveCandidate.corrupt() && temporaryCandidate.valid()) {
            quarantineAndRemove(live, liveCandidate, "corrupt");
            return temporaryCandidate.decoded().value();
        }
        if (liveCandidate.valid() && temporaryCandidate.absent()) {
            return liveCandidate.decoded().value();
        }
        if (liveCandidate.absent() && temporaryCandidate.valid()) {
            return temporaryCandidate.decoded().value();
        }
        if (!liveCandidate.valid() || !temporaryCandidate.valid()) {
            throw quarantineFailure(
                    live,
                    "no trustworthy " + name + " snapshot remains",
                    liveCandidate,
                    temporaryCandidate);
        }

        if (validPairResolver != null) {
            return resolveValidPair(
                    live, name, liveCandidate, temporaryCandidate, validPairResolver);
        }
        return chooseValidPair(live, name, liveCandidate, temporaryCandidate);
    }

    private static <T> T resolveValidPair(
            Path live,
            String storeName,
            Candidate<T> liveCandidate,
            Candidate<T> temporaryCandidate,
            ValidPairResolver<T> resolver) throws IOException {
        if (liveCandidate.decoded().schemaVersion()
                != temporaryCandidate.decoded().schemaVersion()) {
            throw quarantineFailure(
                    live,
                    storeName + " valid halves use different schemas and cannot be merged",
                    liveCandidate,
                    temporaryCandidate);
        }
        try {
            return Objects.requireNonNull(resolver.resolve(
                    liveCandidate.decoded().value(), temporaryCandidate.decoded().value()));
        } catch (IOException | RuntimeException conflict) {
            IOException failure = quarantineFailure(
                    live,
                    storeName + " valid halves could not be reconciled: "
                            + Objects.requireNonNullElse(
                            conflict.getMessage(), conflict.getClass().getSimpleName()),
                    liveCandidate,
                    temporaryCandidate);
            failure.addSuppressed(conflict);
            throw failure;
        }
    }

    private static <T> T chooseValidPair(
            Path live,
            String storeName,
            Candidate<T> liveCandidate,
            Candidate<T> temporaryCandidate) throws IOException {
        Decoded<T> liveDecoded = liveCandidate.decoded();
        Decoded<T> temporaryDecoded = temporaryCandidate.decoded();
        int timeOrder = temporaryCandidate.modifiedAt().compareTo(liveCandidate.modifiedAt());
        int sequenceOrder = Long.compare(temporaryDecoded.sequence(), liveDecoded.sequence());

        if (timeOrder == 0 && Arrays.equals(liveCandidate.encoded(), temporaryCandidate.encoded())) {
            deleteBestEffort(temporaryCandidate.path());
            return liveDecoded.value();
        }

        Candidate<T> chosen;
        Candidate<T> rejected;
        if (timeOrder > 0 && sequenceOrder >= 0) {
            chosen = temporaryCandidate;
            rejected = liveCandidate;
        } else if (timeOrder < 0 && sequenceOrder <= 0) {
            chosen = liveCandidate;
            rejected = temporaryCandidate;
        } else if (timeOrder == 0 && sequenceOrder != 0) {
            chosen = sequenceOrder > 0 ? temporaryCandidate : liveCandidate;
            rejected = sequenceOrder > 0 ? liveCandidate : temporaryCandidate;
        } else {
            throw quarantineFailure(
                    live,
                    storeName + " live and temporary snapshots have conflicting ordering",
                    liveCandidate,
                    temporaryCandidate);
        }

        if (chosen.decoded().schemaVersion() < rejected.decoded().schemaVersion()) {
            throw quarantineFailure(
                    live,
                    storeName + " recovery refused to replace schema "
                            + rejected.decoded().schemaVersion() + " with older schema "
                            + chosen.decoded().schemaVersion(),
                    liveCandidate,
                    temporaryCandidate);
        }

        if (chosen.role() == Role.LIVE) {
            // A valid but older temporary file cannot be part of a later interrupted commit.
            deleteBestEffort(temporaryCandidate.path());
        }
        return chosen.decoded().value();
    }

    private static <T> Candidate<T> inspect(
            Path path,
            Role role,
            Decoder<T> decoder) throws IOException {
        FileTime modifiedAt = Files.getLastModifiedTime(path);
        byte[] encoded = Files.readAllBytes(path);
        FileTime confirmedModifiedAt = Files.getLastModifiedTime(path);
        if (!modifiedAt.equals(confirmedModifiedAt)) {
            throw new IOException("atomic-store candidate changed while being inspected: " + path);
        }
        try {
            return Candidate.valid(path, role, encoded, modifiedAt, decoder.decode(encoded, path));
        } catch (UnsupportedSchemaException unsupported) {
            return Candidate.unsupported(path, role, encoded, modifiedAt, unsupported);
        } catch (IOException | RuntimeException corrupt) {
            return Candidate.corrupt(path, role, encoded, modifiedAt, corrupt);
        }
    }

    private static void quarantineAndRemove(
            Path live,
            Candidate<?> candidate,
            String reason) throws IOException {
        archive(live, candidate, reason);
        deleteBestEffort(candidate.path());
    }

    private static void deleteBestEffort(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The content-addressed quarantine already preserves the bad half. A transient Windows
            // sharing lock must not make the independently valid half unavailable.
        }
    }

    @SafeVarargs
    private static IOException quarantineFailure(
            Path live,
            String message,
            Candidate<?>... candidates) {
        StringBuilder diagnostic = new StringBuilder(message);
        for (Candidate<?> candidate : candidates) {
            if (candidate.failure() == null) continue;
            diagnostic.append("; ").append(candidate.role().wireName).append(": ")
                    .append(Objects.requireNonNullElse(
                            candidate.failure().getMessage(),
                            candidate.failure().getClass().getSimpleName()));
        }
        boolean unsupportedSchema = false;
        for (Candidate<?> candidate : candidates) {
            unsupportedSchema |= candidate.unsupported();
        }
        RecoveryException failure = new RecoveryException(
                diagnostic.toString(), unsupportedSchema);
        for (Candidate<?> candidate : candidates) {
            if (candidate.absent()) continue;
            String reason = candidate.unsupported() ? "unsupported"
                    : candidate.corrupt() ? "corrupt" : "ambiguous";
            try {
                // Copy instead of move: the original paths keep this store fail-closed on every
                // restart while the quarantine preserves byte-exact diagnostic evidence.
                archive(live, candidate, reason);
            } catch (IOException quarantineError) {
                failure.addSuppressed(quarantineError);
            }
            if (candidate.failure() != null) failure.addSuppressed(candidate.failure());
        }
        return failure;
    }

    private static void archive(
            Path live,
            Candidate<?> candidate,
            String reason) throws IOException {
        Path directory = live.resolveSibling(live.getFileName() + ".recovery-quarantine");
        Files.createDirectories(directory);
        String hash = sha256(candidate.encoded());
        Path archive = directory.resolve(candidate.role().wireName + '-' + reason + '-' + hash);
        try {
            Files.write(
                    archive,
                    candidate.encoded(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            // Deterministic content addressing makes repeated fail-closed loads idempotent.
        }
    }

    private static String sha256(byte[] encoded) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Path normalize(Path path, String field) {
        return Objects.requireNonNull(path, field).toAbsolutePath().normalize();
    }

    private static boolean definiteExists(Path path) throws IOException {
        if (Files.exists(path)) return true;
        if (Files.notExists(path)) return false;
        throw new IOException("could not determine whether atomic-store candidate exists: " + path);
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("storeName is blank");
        return value.trim();
    }

    private enum Role {
        LIVE("live"),
        TEMPORARY("temporary");

        private final String wireName;

        Role(String wireName) {
            this.wireName = wireName;
        }
    }

    private enum Status {
        ABSENT,
        VALID,
        CORRUPT,
        UNSUPPORTED
    }

    private record Candidate<T>(
            Path path,
            Role role,
            byte[] encoded,
            FileTime modifiedAt,
            Status status,
            Decoded<T> decoded,
            Exception failure) {
        private static <T> Candidate<T> absent(Role role, Path path) {
            return new Candidate<>(path, role, new byte[0], FileTime.fromMillis(0L),
                    Status.ABSENT, null, null);
        }

        private static <T> Candidate<T> valid(
                Path path, Role role, byte[] encoded, FileTime modifiedAt, Decoded<T> decoded) {
            return new Candidate<>(path, role, encoded, modifiedAt, Status.VALID, decoded, null);
        }

        private static <T> Candidate<T> corrupt(
                Path path, Role role, byte[] encoded, FileTime modifiedAt, Exception failure) {
            return new Candidate<>(path, role, encoded, modifiedAt, Status.CORRUPT, null, failure);
        }

        private static <T> Candidate<T> unsupported(
                Path path, Role role, byte[] encoded, FileTime modifiedAt, Exception failure) {
            return new Candidate<>(path, role, encoded, modifiedAt, Status.UNSUPPORTED, null, failure);
        }

        private boolean absent() { return status == Status.ABSENT; }
        private boolean valid() { return status == Status.VALID; }
        private boolean corrupt() { return status == Status.CORRUPT; }
        private boolean unsupported() { return status == Status.UNSUPPORTED; }
    }
}
