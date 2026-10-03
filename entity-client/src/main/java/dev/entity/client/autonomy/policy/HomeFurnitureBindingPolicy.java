package dev.entity.client.autonomy.policy;

import java.util.Objects;

/** Exact owner preview identity; block/geometry observations are supplied by Minecraft. */
public final class HomeFurnitureBindingPolicy {
    private HomeFurnitureBindingPolicy() {}

    public record Selection(String nonce, HomeEconomySession.AssetRole role,
                            FieldKitLedger.Position position, String blockId, String facing,
                            String chestType, ConnectedChest connectedChest) {
        public Selection(String nonce, HomeEconomySession.AssetRole role,
                FieldKitLedger.Position position, String blockId, String facing) {
            this(nonce, role, position, blockId, facing,
                    role == HomeEconomySession.AssetRole.CHEST ? "single" : "", null);
        }

        public Selection {
            Objects.requireNonNull(role);
            Objects.requireNonNull(position);
            nonce = Objects.requireNonNull(nonce);
            blockId = Objects.requireNonNull(blockId);
            facing = Objects.requireNonNullElse(facing, "");
            if (!nonce.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("invalid owner selection identity");
            boolean correct = role == HomeEconomySession.AssetRole.BED
                    ? blockId.matches("minecraft:(white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black)_bed")
                    : blockId.equals("minecraft:" + role.item());
            if (!correct) throw new IllegalArgumentException("selected block does not match the Home role");
            chestType = Objects.requireNonNullElse(chestType, "");
            if (role == HomeEconomySession.AssetRole.CHEST) {
                HomeEconomySession.ChestHalf selected = new HomeEconomySession.ChestHalf(position, blockId, facing, chestType);
                var halves = connectedChest == null ? java.util.List.of(selected) : java.util.List.of(selected,
                        new HomeEconomySession.ChestHalf(new FieldKitLedger.Position(position.dimension(),
                                connectedChest.x(), connectedChest.y(), connectedChest.z()),
                                connectedChest.blockId(), connectedChest.facing(), connectedChest.type()));
                new HomeEconomySession.ContainerIdentity("selection", halves);
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

    public record Preview(Selection selection, String homeFingerprint, long generation,
                          long policyRevision, String policyDigest, long expiresAtMillis) {}

    public static Preview preview(Selection selected, HomeEconomySession.Snapshot home,
                                  long policyRevision, String policyDigest, long nowMillis) {
        if (home.home() == null) throw new IllegalArgumentException("Set Home before binding furniture");
        if (!home.home().dimension().equals(selected.position().dimension())) {
            throw new IllegalArgumentException("Selected furniture is in another Home dimension");
        }
        return new Preview(selected, home.home().fingerprint(), home.generation(),
                policyRevision, policyDigest, nowMillis + 30_000L);
    }

    public static void requireConfirmation(Preview preview, Selection selected,
            HomeEconomySession.Snapshot home, long revision, String digest, long nowMillis) {
        if (preview == null || !preview.selection().equals(selected)
                || nowMillis > preview.expiresAtMillis() || home.home() == null
                || !home.home().fingerprint().equals(preview.homeFingerprint())
                || home.generation() != preview.generation()
                || revision != preview.policyRevision() || !Objects.equals(digest, preview.policyDigest())) {
            throw new IllegalArgumentException("Furniture/Home/policy selection changed or expired; select the exact block again");
        }
    }
}
