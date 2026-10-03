package dev.entity.core.build;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/** Exact source/build coordinates shared by the paired Entity2 client and server jars. */
public final class BuildIdentity {
    public static final String RESOURCE_NAME = "entity2-build-identity.properties";
    private static final Pattern VERSION = Pattern.compile(
            "^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40,64}$");
    private static final Pattern BUILD_ID = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{7,127}$");
    private static final Pattern SOURCE_STATE = Pattern.compile("^(?:clean|dirty-[0-9a-f]{64})$");

    private final int schemaVersion;
    private final Coordinates coordinates;
    private final String sourceState;
    private final Instant generatedAt;

    private BuildIdentity(
            int schemaVersion,
            Coordinates coordinates,
            String sourceState,
            Instant generatedAt) {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported build identity schema " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        this.coordinates = Objects.requireNonNull(coordinates, "coordinates");
        this.sourceState = requireMatch(
                Objects.requireNonNull(sourceState, "sourceState").trim().toLowerCase(),
                SOURCE_STATE,
                "sourceState");
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    }

    /** Loads the embedded identity and fails closed if packaging omitted or corrupted it. */
    public static BuildIdentity current() {
        return Holder.CURRENT;
    }

    public static BuildIdentity read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        Properties properties = new Properties();
        properties.load(input);
        try {
            int schema = Integer.parseInt(required(properties, "schemaVersion"));
            return of(
                    schema,
                    required(properties, "version"),
                    required(properties, "sourceCommit"),
                    required(properties, "buildId"),
                    required(properties, "sourceState"),
                    required(properties, "generatedAtUtc"));
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw new IOException("Invalid embedded Entity2 build identity: " + error.getMessage(), error);
        }
    }

    public static Coordinates coordinates(String version, String sourceCommit, String buildId) {
        return new Coordinates(version, sourceCommit, buildId);
    }

    /** Constructs and validates the complete identity carried over the bridge protocol. */
    public static BuildIdentity of(
            int schemaVersion,
            String version,
            String sourceCommit,
            String buildId,
            String sourceState,
            String generatedAtUtc) {
        Instant generatedAt;
        try {
            generatedAt = Instant.parse(
                    Objects.requireNonNull(generatedAtUtc, "generatedAtUtc").trim());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("invalid generatedAtUtc: " + generatedAtUtc, invalid);
        }
        return new BuildIdentity(
                schemaVersion,
                new Coordinates(version, sourceCommit, buildId),
                sourceState,
                generatedAt);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String version() {
        return coordinates.version();
    }

    public String sourceCommit() {
        return coordinates.sourceCommit();
    }

    public String buildId() {
        return coordinates.buildId();
    }

    public Coordinates coordinates() {
        return coordinates;
    }

    public String sourceState() {
        return sourceState;
    }

    public Instant generatedAt() {
        return generatedAt;
    }

    public boolean releaseClean() {
        return sourceState.equals("clean");
    }

    public void requireExact(BuildIdentity peer, String peerLabel) {
        Objects.requireNonNull(peer, "peer");
        List<String> mismatches = new ArrayList<>(6);
        if (schemaVersion() != peer.schemaVersion()) mismatches.add("schemaVersion");
        if (!version().equals(peer.version())) mismatches.add("version");
        if (!sourceCommit().equals(peer.sourceCommit())) mismatches.add("sourceCommit");
        if (!buildId().equals(peer.buildId())) mismatches.add("buildId");
        if (!sourceState().equals(peer.sourceState())) mismatches.add("sourceState");
        if (!generatedAt().equals(peer.generatedAt())) mismatches.add("generatedAtUtc");
        if (!mismatches.isEmpty()) {
            throw new IllegalArgumentException(
                    "Exact Entity2 build identity mismatch (" + String.join(", ", mismatches) + "): local="
                            + display() + ", "
                            + Objects.requireNonNullElse(peerLabel, "peer") + '=' + peer.display());
        }
    }

    public String display() {
        return "schemaVersion=" + schemaVersion + ", " + coordinates.display() + ", sourceState=" + sourceState
                + ", generatedAtUtc=" + generatedAt;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing property " + key);
        }
        return value.trim();
    }

    private static String requireMatch(String value, Pattern pattern, String field) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
        return value;
    }

    private static BuildIdentity loadEmbedded() {
        ClassLoader loader = BuildIdentity.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Entity2 artifact is missing required " + RESOURCE_NAME);
            }
            return read(input);
        } catch (IOException error) {
            throw new IllegalStateException("Entity2 artifact build identity is unreadable", error);
        }
    }

    private static final class Holder {
        private static final BuildIdentity CURRENT = loadEmbedded();
    }

    public record Coordinates(String version, String sourceCommit, String buildId) {
        public Coordinates {
            version = requireMatch(
                    Objects.requireNonNull(version, "version").trim(), VERSION, "version");
            sourceCommit = requireMatch(
                    Objects.requireNonNull(sourceCommit, "sourceCommit").trim().toLowerCase(),
                    COMMIT,
                    "sourceCommit");
            buildId = requireMatch(
                    Objects.requireNonNull(buildId, "buildId").trim(), BUILD_ID, "buildId");
        }

        public String display() {
            return "version=" + version + ", commit=" + sourceCommit + ", buildId=" + buildId;
        }
    }
}
