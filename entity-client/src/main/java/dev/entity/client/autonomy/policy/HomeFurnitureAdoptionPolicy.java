package dev.entity.client.autonomy.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded observed furniture is only a proposal; ambiguity never chooses a winner. */
public final class HomeFurnitureAdoptionPolicy {
    private HomeFurnitureAdoptionPolicy() {}

    public record Observation(HomeEconomySession.AssetRole role,
                              FieldKitLedger.Position position, String state,
                              HomeFurnitureBindingPolicy.Selection selection, String blocked) {
        public Observation {
            Objects.requireNonNull(role);
            Objects.requireNonNull(position);
            Objects.requireNonNull(state);
            blocked = Objects.requireNonNullElse(blocked, "");
        }
    }

    public record Preview(String nonce, String homeFingerprint, long generation, boolean homeEnabled,
                          Map<HomeEconomySession.AssetRole, HomeEconomySession.PinnedAsset> pins,
                          Map<String, HomeEconomySession.StorageRegistration> storage, boolean storageExplicitlyMissing,
                          long revision, String digest, long expiresAtMillis,
                          List<Observation> observed,
                          List<HomeFurnitureBindingPolicy.Selection> selections, String detail) {
        public Preview {
            pins = Map.copyOf(pins);
            storage = Map.copyOf(storage);
            observed = List.copyOf(observed);
            selections = List.copyOf(selections);
        }
    }

    public static Preview preview(String nonce, HomeEconomySession.Snapshot home,
                                  long revision, String digest, List<Observation> observed, long now) {
        if (home.home() == null) throw new IllegalArgumentException("Register Home with /e home set first");
        List<HomeFurnitureBindingPolicy.Selection> selections = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (var role : HomeEconomySession.AssetRole.values()) {
            var candidates = observed.stream().filter(candidate -> candidate.role() == role).toList();
            if (candidates.isEmpty()) details.add(role.item() + "=missing");
            else if (candidates.size() > 1) details.add(role.item() + "=ambiguous (" + candidates.size() + ")");
            else {
                Observation candidate = candidates.getFirst();
                if (candidate.selection() == null) details.add(role.item() + "=unavailable (" + candidate.blocked() + ")");
                else {
                    selections.add(candidate.selection());
                    var p = candidate.position();
                    details.add(role.item() + "=" + p.x() + " " + p.y() + " " + p.z());
                }
            }
        }
        return new Preview(nonce, home.home().fingerprint(), home.generation(), home.enabled(),
                home.pinnedAssets(), home.storageRegistrations(), home.storageExplicitlyMissing(), revision, digest,
                now + 30_000L, observed, selections, String.join("; ", details));
    }

    public static void requireConfirmation(Preview preview, HomeEconomySession.Snapshot home,
                                           long revision, String digest, List<Observation> observed, long now) {
        if (preview == null || home.home() == null || now > preview.expiresAtMillis()
                || !home.home().fingerprint().equals(preview.homeFingerprint())
                // Runtime's validated Stop may persist a PAUSED control phase. That revision
                // is not furniture authority; exact identity and registrations must still match.
                || home.generation() != preview.generation() || home.enabled() != preview.homeEnabled()
                || !home.pinnedAssets().equals(preview.pins())
                || !home.storageRegistrations().equals(preview.storage())
                || home.storageExplicitlyMissing() != preview.storageExplicitlyMissing()
                || revision != preview.revision()
                || !Objects.equals(digest, preview.digest()) || !preview.observed().equals(observed)) {
            throw new IllegalArgumentException("Home, area policy or observed furniture changed/expired; use /e home adopt again");
        }
        if (preview.selections().isEmpty()) throw new IllegalArgumentException(
                "No unambiguous usable furniture to adopt; use /e home bind <role> for exact selection");
    }
}
