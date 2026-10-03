package dev.entitybridge.command;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** An ephemeral owner-selected drop target, independent from furniture and cleanup previews. */
public final class TidySpotSelection {
    private Preview pending;

    public Preview begin(String owner, Candidate candidate, long revision, String digest, long now) {
        pending = new Preview(owner, UUID.randomUUID().toString(), candidate, revision, digest,
                now + HomeFurnitureSelection.TTL_MILLIS);
        return pending;
    }

    public Preview pending(String owner, long now) {
        if (pending == null || !pending.owner().equalsIgnoreCase(owner) || now > pending.expiresAtMillis()) {
            pending = null;
            throw new IllegalArgumentException("No current drop-spot selection; look at the landing block or water and use /e tidy spot");
        }
        return pending;
    }

    public Preview confirm(String owner, Candidate observed, long revision, String digest, long now) {
        Preview preview = pending(owner, now);
        if (!preview.candidate().equals(observed) || preview.revision() != revision || !preview.digest().equals(digest)) {
            pending = null;
            throw new IllegalArgumentException("Selected drop spot or area policy changed; use /e tidy spot again");
        }
        pending = null;
        return preview;
    }

    public record Candidate(String dimension, String worldId, int x, int y, int z,
                            String blockId, String blockState, double landingX, double landingY, double landingZ) {
        public Candidate {
            Objects.requireNonNull(dimension);
            UUID.fromString(Objects.requireNonNull(worldId));
            Objects.requireNonNull(blockId);
            Objects.requireNonNull(blockState);
            if (dimension.isBlank() || blockId.isBlank() || blockState.isBlank()
                    || !Double.isFinite(landingX) || !Double.isFinite(landingY) || !Double.isFinite(landingZ)) {
                throw new IllegalArgumentException("Drop spot requires exact world, block and finite landing coordinates");
            }
        }
    }

    /** Collision boxes from Paper getCollisionShape are local to the selected block. */
    public record Surface(double minX, double minZ, double maxX, double maxZ, double topY) {}

    public static double landingY(int blockY, boolean waterCell, List<Surface> surfaces) {
        if (waterCell) return blockY + 0.5;
        double localTop = surfaces.stream()
                .filter(surface -> surface.minX() <= 0.5 && surface.maxX() >= 0.5
                        && surface.minZ() <= 0.5 && surface.maxZ() >= 0.5)
                .mapToDouble(Surface::topY).max().orElseThrow(() ->
                        new IllegalArgumentException("Select water or a block with a landing surface at its center"));
        if (!Double.isFinite(localTop)) throw new IllegalArgumentException("Selected landing surface is invalid");
        return blockY + localTop;
    }

    public record Preview(String owner, String nonce, Candidate candidate,
                          long revision, String digest, long expiresAtMillis) {}

    public static JsonObject policy(String operation, Preview preview) {
        if (!Set.of("spot_preview", "spot_confirm").contains(operation)) {
            throw new IllegalArgumentException("Unknown drop-spot selection operation");
        }
        Candidate candidate = preview.candidate();
        JsonObject policy = new JsonObject();
        policy.addProperty("operation", operation);
        policy.addProperty("nonce", preview.nonce());
        policy.addProperty("dimension", candidate.dimension());
        policy.addProperty("worldId", candidate.worldId());
        policy.addProperty("x", candidate.x());
        policy.addProperty("y", candidate.y());
        policy.addProperty("z", candidate.z());
        policy.addProperty("blockId", candidate.blockId());
        policy.addProperty("blockState", candidate.blockState());
        policy.addProperty("landingX", candidate.landingX());
        policy.addProperty("landingY", candidate.landingY());
        policy.addProperty("landingZ", candidate.landingZ());
        policy.addProperty("policyRevision", preview.revision());
        policy.addProperty("policyDigest", preview.digest());
        policy.addProperty("expiresAtMillis", preview.expiresAtMillis());
        return policy;
    }
}
