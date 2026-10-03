package dev.entitybridge.command;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Ephemeral owner confirmation; restart/expiry never adopts a nearby block. */
public final class HomeFurnitureSelection {
    public static final long TTL_MILLIS = 30_000L;
    private final String selectionCommand;
    private Preview pending;

    public HomeFurnitureSelection() {
        this("/e home bind <role>");
    }

    public HomeFurnitureSelection(String selectionCommand) {
        this.selectionCommand = Objects.requireNonNull(selectionCommand);
    }

    public Preview begin(String owner, Candidate candidate, long revision, String digest, long now) {
        pending = new Preview(owner, UUID.randomUUID().toString(), candidate,
                revision, digest, now + TTL_MILLIS);
        return pending;
    }

    public Preview pending(String owner, long now) {
        if (pending == null || !pending.owner().equalsIgnoreCase(owner)
                || now > pending.expiresAtMillis()) {
            pending = null;
            throw new IllegalArgumentException("No current furniture selection; look at the block and use " + selectionCommand);
        }
        return pending;
    }

    public Preview confirm(String owner, Candidate observed, long revision, String digest, long now) {
        Preview preview = pending(owner, now);
        if (!preview.candidate().equals(observed) || preview.revision() != revision
                || !preview.digest().equals(digest)) {
            pending = null;
            throw new IllegalArgumentException("Selected furniture or area policy changed; select it again");
        }
        pending = null;
        return preview;
    }

    public record Candidate(String role, String dimension, int x, int y, int z,
                            String blockId, String facing, String chestType,
                            ConnectedChest connectedChest) {
        public Candidate(String role, String dimension, int x, int y, int z,
                         String blockId, String facing) {
            this(role, dimension, x, y, z, blockId, facing,
                    "chest".equals(role) ? "single" : "", null);
        }

        public Candidate {
            Objects.requireNonNull(role);
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(blockId);
            facing = Objects.requireNonNullElse(facing, "");
            boolean valid = switch (role) {
                case "table" -> blockId.equals("minecraft:crafting_table");
                case "furnace" -> blockId.equals("minecraft:furnace");
                case "chest" -> blockId.equals("minecraft:chest");
                case "bed" -> blockId.matches("minecraft:(white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black)_bed");
                default -> false;
            };
            if (!valid || dimension.isBlank()) throw new IllegalArgumentException("Selected block does not match Home role " + role);
            chestType = Objects.requireNonNullElse(chestType, "");
            if (role.equals("chest")) {
                if (!Set.of("single", "left", "right").contains(chestType)) {
                    throw new IllegalArgumentException("Selected chest has an invalid connection type");
                }
                if (chestType.equals("single")) {
                    if (connectedChest != null) throw new IllegalArgumentException("Single chest cannot adopt a neighboring chest");
                } else {
                    Offset offset = chestConnection(facing, chestType);
                    String opposite = chestType.equals("left") ? "right" : "left";
                    if (connectedChest == null || connectedChest.x() != x + offset.x()
                            || connectedChest.y() != y || connectedChest.z() != z + offset.z()
                            || !connectedChest.blockId().equals(blockId)
                            || !connectedChest.facing().equals(facing)
                            || !connectedChest.type().equals(opposite)) {
                        throw new IllegalArgumentException("Selected double chest does not have its matching connected half");
                    }
                }
            } else if (!chestType.isEmpty() || connectedChest != null) {
                throw new IllegalArgumentException("Only chest furniture can have a connected chest");
            }
        }
    }

    public record ConnectedChest(int x, int y, int z, String blockId, String facing, String type) {
        public ConnectedChest {
            Objects.requireNonNull(blockId);
            Objects.requireNonNull(facing);
            Objects.requireNonNull(type);
        }
    }

    public record Offset(int x, int z) {}

    /** Minecraft 1.21.8 ChestBlock: LEFT connects clockwise; RIGHT counterclockwise. */
    public static Offset chestConnection(String facing, String type) {
        if (!type.equals("left") && !type.equals("right")) {
            throw new IllegalArgumentException("Single chest has no connected half");
        }
        Offset clockwise = switch (facing) {
            case "north" -> new Offset(1, 0);
            case "east" -> new Offset(0, 1);
            case "south" -> new Offset(-1, 0);
            case "west" -> new Offset(0, -1);
            default -> throw new IllegalArgumentException("Chest must face horizontally");
        };
        return type.equals("left") ? clockwise : new Offset(-clockwise.x(), -clockwise.z());
    }

    /** Bind and storage-add share the same immutable world/policy selection boundary. */
    public static JsonObject policy(String operation, Preview preview) {
        if (!Set.of("bind_preview", "bind_confirm", "storage_preview", "storage_confirm").contains(operation)) {
            throw new IllegalArgumentException("Unknown furniture selection operation");
        }
        Candidate candidate = preview.candidate();
        if (operation.startsWith("storage_") && !candidate.role().equals("chest")) {
            throw new IllegalArgumentException("Storage registration requires a chest selection");
        }
        JsonObject policy = new JsonObject();
        policy.addProperty("operation", operation);
        policy.addProperty("role", candidate.role());
        policy.addProperty("nonce", preview.nonce());
        policy.addProperty("dimension", candidate.dimension());
        policy.addProperty("x", candidate.x());
        policy.addProperty("y", candidate.y());
        policy.addProperty("z", candidate.z());
        policy.addProperty("blockId", candidate.blockId());
        policy.addProperty("facing", candidate.facing());
        if (candidate.role().equals("chest")) {
            policy.addProperty("chestType", candidate.chestType());
            if (candidate.connectedChest() != null) {
                ConnectedChest connected = candidate.connectedChest();
                JsonObject half = new JsonObject();
                half.addProperty("x", connected.x());
                half.addProperty("y", connected.y());
                half.addProperty("z", connected.z());
                half.addProperty("blockId", connected.blockId());
                half.addProperty("facing", connected.facing());
                half.addProperty("type", connected.type());
                policy.add("connectedChest", half);
            }
        }
        policy.addProperty("policyRevision", preview.revision());
        policy.addProperty("policyDigest", preview.digest());
        policy.addProperty("expiresAtMillis", preview.expiresAtMillis());
        return policy;
    }

    public record Preview(String owner, String nonce, Candidate candidate,
                          long revision, String digest, long expiresAtMillis) {}
}
