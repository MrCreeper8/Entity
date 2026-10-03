package dev.entity.client.autonomy.policy;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure next-action policy for the durable Home & Economy session.
 *
 * <p>It never owns controls. Only {@link Authority#IDLE} may produce work;
 * survival, protection, direct user missions, death recovery, and disconnect
 * all produce a persisted pause before another economy action is allowed.</p>
 */
public final class HomeEconomyPolicy {
    public enum Authority {
        IDLE,
        DIRECT_USER_MISSION,
        SURVIVAL,
        PROTECTION,
        DEATH_RECOVERY,
        DISCONNECTED
    }

    public enum AssetTruth {
        MISSING,
        OWNED_CARRIED,
        OWNED_PLACED_VERIFIED,
        OWNED_PLACED_UNLOADED,
        OWNED_PLACED_MISSING,
        OWNED_LOST,
        FOREIGN_PRESENT
    }

    public enum AcquisitionTruth {
        NONE,
        ACTIVE,
        COMPLETED,
        FAILED,
        DIVERGED
    }

    public enum Action {
        WAIT_DISABLED,
        PAUSE_FOR_HIGHER_PRIORITY,
        RESUME_CHECKPOINT,
        REPORT_BLOCKED,
        REQUIRE_HOME,
        BIND_CONFIGURED_HOME,
        RETURN_HOME,
        ACQUIRE_ASSET,
        REPLACE_ASSET,
        PLACE_ASSET,
        VERIFY_ASSET,
        OPEN_CHEST,
        RECONCILE_TRANSFER,
        REBASE_TRANSFER,
        COMMIT_TRANSFER,
        ABANDON_TRANSFER,
        WITHDRAW,
        START_ACQUISITION,
        WAIT_ACQUISITION,
        RETIRE_ACQUISITION,
        CANCEL_ACQUISITION_FOR_ASSET_REPAIR,
        FILL_WATER_BUCKET,
        DEPOSIT,
        DEGRADED_READY,
        READY
    }

    public enum TransferTruth {
        COMMITTED,
        PARTIAL_COMMITTED,
        PENDING,
        REBIND_REQUIRED,
        CURSOR_OCCUPIED,
        DIVERGED
    }

    public record HomeCandidate(
            boolean idleAuthority,
            boolean alive,
            boolean loaded,
            boolean grounded,
            boolean dry,
            boolean supported,
            boolean hazardClear) {
    }

    /**
     * One fully inspected loaded-world option for an explicit Home request.
     * The exact requested block remains authoritative when it is safe.  A
     * fallback favors enough immediate workspace for a fourth owned asset
     * before route distance, preserving room for the livable-Home bed slice.
     */
    public record HomeSiteCandidate(
            int x,
            int y,
            int z,
            boolean eligible,
            boolean exactRequest,
            int immediateAssetCells,
            long distanceSquared,
            boolean skyVisible) {
        public HomeSiteCandidate {
            if (immediateAssetCells < 0 || immediateAssetCells > 4) {
                throw new IllegalArgumentException("immediateAssetCells must be 0..4");
            }
            if (distanceSquared < 0) {
                throw new IllegalArgumentException("distanceSquared must be non-negative");
            }
        }
    }

    public record Eligibility(boolean eligible, String code, String detail) {
        public Eligibility {
            code = requireId(code, "code");
            detail = requireText(detail, "detail", 1024);
            if (eligible && !code.equals("eligible")) {
                throw new IllegalArgumentException("eligible candidate must use eligible code");
            }
        }
    }

    /**
     * Durable departure gate for an acquisition that starts at Home.
     *
     * <p>The first resource action may begin only after Entity has returned to
     * the exact reserved anchor and persisted that fact. Once checkpointed,
     * the acquisition is deliberately free to travel away from Home.</p>
     */
    public enum AcquisitionDepartureAction {
        PROCEED,
        ROUTE_TO_ANCHOR,
        CHECKPOINT_ANCHOR,
        BLOCKED
    }

    public record AcquisitionDepartureDecision(
            AcquisitionDepartureAction action,
            String detail) {
        public AcquisitionDepartureDecision {
            Objects.requireNonNull(action, "action");
            detail = requireText(detail, "detail", 1024);
        }
    }

    /**
     * Durable completion gate for an acquisition that may have changed the
     * terrain around Home while travelling.
     *
     * <p>A completed planner program is not enough to clear its durable Home
     * cursor. Entity must first stand on the exact reserved anchor again. This
     * lets Baritone reclaim any temporary pillar it used to leave a natural
     * cave instead of accepting an elevated interaction perch as Home.</p>
     */
    public enum AcquisitionReturnAction {
        ROUTE_TO_ANCHOR,
        COMMIT,
        BLOCKED
    }

    public record AcquisitionReturnDecision(
            AcquisitionReturnAction action,
            String detail) {
        public AcquisitionReturnDecision {
            Objects.requireNonNull(action, "action");
            detail = requireText(detail, "detail", 1024);
        }
    }

    public record AssetObservation(
            HomeEconomySession.AssetRole role,
            AssetTruth truth,
            String ledgerAssetId) {
        public AssetObservation {
            role = Objects.requireNonNull(role, "role");
            truth = Objects.requireNonNull(truth, "truth");
            ledgerAssetId = Objects.requireNonNullElse(ledgerAssetId, "").trim();
            boolean ownedTruth = truth == AssetTruth.OWNED_CARRIED
                    || truth == AssetTruth.OWNED_PLACED_VERIFIED
                    || truth == AssetTruth.OWNED_PLACED_UNLOADED
                    || truth == AssetTruth.OWNED_PLACED_MISSING
                    || truth == AssetTruth.OWNED_LOST;
            if (ownedTruth != !ledgerAssetId.isEmpty()) {
                throw new IllegalArgumentException(
                        "owned asset truth and ledger ID must be present together");
            }
            if (ledgerAssetId.length() > 256) {
                throw new IllegalArgumentException("ledgerAssetId is too long");
            }
        }

        public static AssetObservation missing(HomeEconomySession.AssetRole role) {
            return new AssetObservation(role, AssetTruth.MISSING, "");
        }
    }

    /**
     * Exact ledger pins a Home acquisition is allowed to use.
     *
     * <p>Every role that was genuinely unpinned when the durable plan began may
     * remain absent: requiring a future chest or bed to exist would make staged
     * Home establishment circular. Every pin that did exist remains fail-closed,
     * and a formerly absent role becoming pinned underneath the active plan is
     * also rejected, so the exception cannot launder a missing, replaced, or
     * concurrently injected asset.</p>
     */
    public record PlanAssetBinding(
            boolean valid,
            Map<HomeEconomySession.AssetRole, String> ledgerIds,
            String detail) {
        public PlanAssetBinding {
            ledgerIds = Map.copyOf(Objects.requireNonNull(ledgerIds, "ledgerIds"));
            detail = Objects.requireNonNullElse(detail, "");
        }

        private static PlanAssetBinding invalid(String detail) {
            return new PlanAssetBinding(false, Map.of(), detail);
        }
    }

    /** Freezes the exact set of asset roles that did not yet have durable pins. */
    public static Set<HomeEconomySession.AssetRole> unpinnedAssetRolesAtPlanStart(
            HomeEconomySession.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        EnumSet<HomeEconomySession.AssetRole> unpinned =
                EnumSet.allOf(HomeEconomySession.AssetRole.class);
        unpinned.removeAll(snapshot.pinnedAssets().keySet());
        return Set.copyOf(unpinned);
    }

    public static PlanAssetBinding validatePlanAssetBindings(
            Map<HomeEconomySession.AssetRole, String> expectedLedgerIds,
            Set<HomeEconomySession.AssetRole> permittedUnpinnedAssets,
            HomeEconomySession.Snapshot current) {
        Objects.requireNonNull(expectedLedgerIds, "expectedLedgerIds");
        permittedUnpinnedAssets = Set.copyOf(Objects.requireNonNull(
                permittedUnpinnedAssets, "permittedUnpinnedAssets"));
        Objects.requireNonNull(current, "current");

        EnumMap<HomeEconomySession.AssetRole, String> verified =
                new EnumMap<>(HomeEconomySession.AssetRole.class);
        for (HomeEconomySession.AssetRole role : HomeEconomySession.AssetRole.values()) {
            String expected = Objects.requireNonNullElse(
                    expectedLedgerIds.get(role), "").trim();
            HomeEconomySession.PinnedAsset actual = current.pinnedAssets().get(role);
            if (expected.isBlank() && actual == null
                    && permittedUnpinnedAssets.contains(role)) {
                continue;
            }
            if (expected.isBlank() || actual == null
                    || !actual.ledgerAssetId().equals(expected)) {
                return PlanAssetBinding.invalid(
                        "home_binding_changed: exact " + role.item()
                                + " ledger pin changed");
            }
            verified.put(role, expected);
        }
        return new PlanAssetBinding(true, Map.copyOf(verified), "");
    }

    public record TransferObservation(
            int syncId,
            boolean cursorEmpty,
            boolean exactCursorCustody,
            int playerItemCount,
            int chestItemCount) {
        public TransferObservation {
            if (syncId < 0 || playerItemCount < 0 || chestItemCount < 0) {
                throw new IllegalArgumentException("transfer observation counts cannot be negative");
            }
        }
    }

    public record TransferResolution(
            TransferTruth truth,
            String code,
            String detail,
            int movedCount) {
        public TransferResolution {
            truth = Objects.requireNonNull(truth, "truth");
            code = requireId(code, "code");
            detail = requireText(detail, "detail", 1024);
            if (movedCount < 0) throw new IllegalArgumentException("movedCount cannot be negative");
        }

        public boolean committed() {
            return truth == TransferTruth.COMMITTED;
        }

        public boolean needsRebase() {
            return truth == TransferTruth.PARTIAL_COMMITTED
                    || truth == TransferTruth.REBIND_REQUIRED;
        }
    }

    public record Observation(
            Authority authority,
            HomeEconomySession.HomeAnchor configuredHome,
            boolean atHome,
            boolean homeLoaded,
            boolean homeWorkspaceSafe,
            boolean homeWorkspaceRepairable,
            Map<HomeEconomySession.AssetRole, AssetObservation> assets,
            boolean chestOpenAndObserved,
            boolean chestCanAcceptTransfer,
            Map<String, Integer> playerCounts,
            Map<String, Integer> chestCounts,
            Map<String, Integer> depositEligibleCounts,
            AcquisitionTruth acquisitionTruth,
            String acquisitionDetail,
            boolean plannerAvailable,
            boolean verifiedWaterSourceAvailable,
            TransferObservation transferObservation,
            boolean playerHasEmptyInventorySlot) {
        public Observation {
            authority = Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(assets, "assets");
            EnumMap<HomeEconomySession.AssetRole, AssetObservation> assetCopy =
                    new EnumMap<>(HomeEconomySession.AssetRole.class);
            assets.forEach((role, observation) -> {
                Objects.requireNonNull(role, "asset role");
                Objects.requireNonNull(observation, "asset observation");
                if (observation.role() != role) {
                    throw new IllegalArgumentException("asset observation key does not match role");
                }
                assetCopy.put(role, observation);
            });
            assets = Collections.unmodifiableMap(assetCopy);
            playerCounts = immutableCountMap(playerCounts, "playerCounts");
            chestCounts = immutableCountMap(chestCounts, "chestCounts");
            depositEligibleCounts = immutableCountMap(
                    depositEligibleCounts, "depositEligibleCounts");
            acquisitionTruth = Objects.requireNonNull(acquisitionTruth, "acquisitionTruth");
            acquisitionDetail = Objects.requireNonNullElse(acquisitionDetail, "").trim();
            if (acquisitionDetail.length() > 4096) {
                throw new IllegalArgumentException("acquisitionDetail is too long");
            }
        }

        /** Legacy observations do not claim unobserved receiving capacity. */
        public Observation(Authority authority, HomeEconomySession.HomeAnchor configuredHome,
                boolean atHome, boolean homeLoaded, boolean homeWorkspaceSafe,
                boolean homeWorkspaceRepairable,
                Map<HomeEconomySession.AssetRole, AssetObservation> assets,
                boolean chestOpenAndObserved, boolean chestCanAcceptTransfer,
                Map<String, Integer> playerCounts, Map<String, Integer> chestCounts,
                Map<String, Integer> depositEligibleCounts, AcquisitionTruth acquisitionTruth,
                String acquisitionDetail, boolean plannerAvailable,
                boolean verifiedWaterSourceAvailable, TransferObservation transferObservation) {
            this(authority, configuredHome, atHome, homeLoaded, homeWorkspaceSafe,
                    homeWorkspaceRepairable, assets, chestOpenAndObserved, chestCanAcceptTransfer,
                    playerCounts, chestCounts, depositEligibleCounts, acquisitionTruth,
                    acquisitionDetail, plannerAvailable, verifiedWaterSourceAvailable,
                    transferObservation, false);
        }
    }

    public record Decision(
            Action action,
            HomeEconomySession.Phase nextPhase,
            String code,
            String detail,
            HomeEconomySession.AssetRole assetRole,
            HomeStockPolicy.Transfer transfer,
            Map<String, Integer> acquisitionGoals,
            HomeStockPolicy.Analysis stock) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            nextPhase = Objects.requireNonNull(nextPhase, "nextPhase");
            code = requireId(code, "code");
            detail = requireText(detail, "detail", 4096);
            acquisitionGoals = immutableCountMap(acquisitionGoals, "acquisitionGoals");
        }

        public boolean blocked() {
            return action == Action.REPORT_BLOCKED || action == Action.REQUIRE_HOME;
        }
    }

    private HomeEconomyPolicy() {
    }

    /** Deterministic choice among already-inspected Home anchors. */
    public static Optional<HomeSiteCandidate> selectHomeSite(
            List<HomeSiteCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        Comparator<HomeSiteCandidate> ordering = Comparator
                .comparing((HomeSiteCandidate candidate) -> !candidate.exactRequest())
                .thenComparing(Comparator.comparingInt(
                        HomeSiteCandidate::immediateAssetCells).reversed())
                .thenComparingLong(HomeSiteCandidate::distanceSquared)
                .thenComparing(candidate -> !candidate.skyVisible())
                .thenComparingInt(HomeSiteCandidate::x)
                .thenComparingInt(HomeSiteCandidate::z)
                .thenComparingInt(HomeSiteCandidate::y);
        return candidates.stream()
                .map(candidate -> Objects.requireNonNull(candidate, "candidate"))
                .filter(HomeSiteCandidate::eligible)
                .filter(candidate -> candidate.immediateAssetCells() >= 4)
                .min(ordering);
    }

    /** Keeps every owned Home asset on one plane while reserving the anchor as an exit. */
    public static boolean coherentHomeAssetCell(
            HomeEconomySession.HomeAnchor home,
            int x,
            int y,
            int z) {
        Objects.requireNonNull(home, "home");
        return coherentHomeAssetCell(home.x(), home.y(), home.z(), x, y, z);
    }

    public static boolean coherentHomeAssetCell(
            int homeX,
            int homeY,
            int homeZ,
            int x,
            int y,
            int z) {
        return y == homeY && (x != homeX || z != homeZ);
    }

    /**
     * Conservative establishment preflight for one same-level Home asset cell.
     * A solid natural wall may be prepared directly, or through one visible,
     * clearable head-level access block in a full-height shaft. Completed/open
     * placement still requires the real support-face ray supplied by the caller.
     */
    public static boolean preparableHomeAssetCell(
            WorkspaceWorldProbe.Cell cell,
            WorkspaceWorldProbe.Cell overhead,
            boolean readyPlacementHit,
            boolean directWallHit,
            boolean overheadWallHit) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(overhead, "overhead");
        if (!overhead.passable() && !overhead.clearable()) return false;
        if (cell.passable()) return readyPlacementHit;
        return cell == WorkspaceWorldProbe.Cell.SOLID
                && (directWallHit || overhead.clearable() && overheadWallHit);
    }

    public static AcquisitionDepartureDecision decideAcquisitionDeparture(
            boolean checkpointMatchesHome,
            HomeEconomySession.HomeAnchor home,
            String currentDimension,
            int currentX,
            int currentY,
            int currentZ,
            boolean homeLoaded,
            boolean anchorWorkspaceSafe) {
        Objects.requireNonNull(home, "home");
        String dimension = Objects.requireNonNullElse(currentDimension, "").trim();
        if (checkpointMatchesHome) {
            return new AcquisitionDepartureDecision(
                    AcquisitionDepartureAction.PROCEED,
                    "the exact Home departure was already checkpointed");
        }
        if (!home.dimension().equals(dimension)) {
            return new AcquisitionDepartureDecision(
                    AcquisitionDepartureAction.BLOCKED,
                    "the unstarted Home acquisition is outside its Home dimension");
        }
        boolean exactAnchor = currentX == home.x()
                && currentY == home.y()
                && currentZ == home.z();
        if (!exactAnchor) {
            return new AcquisitionDepartureDecision(
                    AcquisitionDepartureAction.ROUTE_TO_ANCHOR,
                    "return to the exact reserved Home anchor before starting resource travel");
        }
        if (!homeLoaded) {
            return new AcquisitionDepartureDecision(
                    AcquisitionDepartureAction.BLOCKED,
                    "the exact Home anchor is not loaded for departure verification");
        }
        if (!anchorWorkspaceSafe) {
            return new AcquisitionDepartureDecision(
                    AcquisitionDepartureAction.BLOCKED,
                    "the exact Home anchor no longer has a safe standing workspace");
        }
        return new AcquisitionDepartureDecision(
                AcquisitionDepartureAction.CHECKPOINT_ANCHOR,
                "the exact safe Home departure anchor is physically verified");
    }

    public static AcquisitionReturnDecision decideAcquisitionReturn(
            HomeEconomySession.HomeAnchor home,
            String currentDimension,
            int currentX,
            int currentY,
            int currentZ,
            boolean homeLoaded,
            boolean anchorWorkspaceSafe) {
        Objects.requireNonNull(home, "home");
        String dimension = Objects.requireNonNullElse(currentDimension, "").trim();
        boolean exactAnchor = home.dimension().equals(dimension)
                && currentX == home.x()
                && currentY == home.y()
                && currentZ == home.z();
        if (!exactAnchor) {
            return new AcquisitionReturnDecision(
                    AcquisitionReturnAction.ROUTE_TO_ANCHOR,
                    "restore the exact reserved Home anchor before committing acquisition success");
        }
        if (!homeLoaded) {
            return new AcquisitionReturnDecision(
                    AcquisitionReturnAction.BLOCKED,
                    "the exact Home anchor is not loaded for return verification");
        }
        if (!anchorWorkspaceSafe) {
            return new AcquisitionReturnDecision(
                    AcquisitionReturnAction.BLOCKED,
                    "the exact Home anchor remains unsafe after acquisition return");
        }
        return new AcquisitionReturnDecision(
                AcquisitionReturnAction.COMMIT,
                "the exact safe Home anchor was physically restored after acquisition");
    }

    /** A persisted return phase is stronger truth than later mutable inventory counts. */
    public static AcquisitionTruth latchAcquisitionReturn(
            boolean durableReturnPending,
            AcquisitionTruth plannerTruth) {
        Objects.requireNonNull(plannerTruth, "plannerTruth");
        return durableReturnPending ? AcquisitionTruth.COMPLETED : plannerTruth;
    }

    public static Eligibility evaluateHomeCandidate(HomeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!candidate.idleAuthority()) {
            return ineligible("higher_priority_active",
                    "A direct mission or safety controller currently owns Entity");
        }
        if (!candidate.alive()) return ineligible("not_alive", "Entity must be alive");
        if (!candidate.loaded()) return ineligible("area_unloaded", "The home area is not loaded");
        if (!candidate.grounded()) return ineligible("not_grounded", "Entity must be grounded");
        if (!candidate.dry()) return ineligible("not_dry", "A home cannot be established in water");
        if (!candidate.supported()) {
            return ineligible("unsupported_floor", "The home workspace needs supported blocks");
        }
        if (!candidate.hazardClear()) {
            return ineligible("nearby_hazard", "The proposed home workspace is hazardous");
        }
        return new Eligibility(true, "eligible", "The exact Home anchor is safe to persist");
    }

    /** Command boundary for an explicit stock run; Home is checked before the toggle. */
    public static Eligibility evaluateStockRunPrerequisite(
            HomeEconomySession.HomeAnchor home,
            boolean stockEnabled) {
        if (home == null) {
            return ineligible("missing_home",
                    "No Home is set. Position Entity, use /e home set, then adopt existing furniture or explicitly request /e home setup.");
        }
        if (!stockEnabled) {
            return ineligible("stock_disabled", "Stock is off; use /e stock on first");
        }
        return new Eligibility(true, "eligible", "Home stock work may be scheduled");
    }

    /** Concise owner-facing acknowledgement; never exposes the internal Home ledger. */
    public static String stockRunAcceptedDetail(
            HomeEconomySession.HomeAnchor home,
            HomeEconomySession.Phase phase) {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(phase, "phase");
        return "Stock run started for Home at " + home.dimension() + ' '
                + home.x() + ' ' + home.y() + ' ' + home.z()
                + "; checking assets and reserves (phase "
                + phase.name().toLowerCase(Locale.ROOT) + ").";
    }

    public static Decision decide(
            HomeEconomySession.Snapshot session,
            Observation observation) {
        return decide(session, observation, HomeStockPolicy.defaults());
    }

    public static Decision decide(HomeEconomySession.Snapshot session, Observation observation,
                                  List<HomeStockPolicy.Target> stockTargets) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(stockTargets, "stockTargets");

        if (!session.enabled()) {
            return decision(Action.WAIT_DISABLED, HomeEconomySession.Phase.DISABLED,
                    "economy_disabled", "Home stock maintenance is disabled");
        }
        if (observation.authority() != Authority.IDLE) {
            if (session.phase() == HomeEconomySession.Phase.PAUSED) {
                return decision(Action.PAUSE_FOR_HIGHER_PRIORITY,
                        HomeEconomySession.Phase.PAUSED, "still_preempted",
                        "Waiting for " + authorityLabel(observation.authority()));
            }
            return decision(Action.PAUSE_FOR_HIGHER_PRIORITY,
                    HomeEconomySession.Phase.PAUSED, "preempted",
                    "Pause and preserve " + session.phase() + " for "
                            + authorityLabel(observation.authority()));
        }
        if (session.phase() == HomeEconomySession.Phase.PAUSED) {
            return decision(Action.RESUME_CHECKPOINT, session.pause().resumePhase(),
                    "resume_checkpoint", "Idle authority restored; resume the exact saved phase");
        }
        if (session.phase() == HomeEconomySession.Phase.BLOCKED) {
            return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                    session.block().code(), session.block().detail());
        }

        // An inventory intent remains bound to the old durable Home until its
        // exact chest counts are reconciled. Settings may have changed after
        // that intent was committed; never switch anchors underneath it.
        if (session.pendingTransfer() == null) {
            Decision homeIdentity = validateHomeIdentity(session, observation);
            if (homeIdentity != null) return homeIdentity;

            if (session.pendingAcquisition() != null) {
                Decision assetLoss = decideAssets(session, observation);
                if (assetLoss != null && assetLoss.action() == Action.REPLACE_ASSET) {
                    return decision(
                            Action.CANCEL_ACQUISITION_FOR_ASSET_REPAIR,
                            HomeEconomySession.Phase.PROVISIONING_ASSETS,
                            "asset_loss_interrupts_acquisition",
                            "Fence the current Home acquisition without claiming success; "
                                    + assetLoss.detail(),
                            assetLoss.assetRole(), null, Map.of(), null);
                }
                return decidePendingAcquisition(session, observation);
            }
        } else if (session.home() == null) {
            return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                    "pending_transfer_home_missing",
                    "The durable transfer has no Home identity; no inventory click is safe");
        }

        if (!observation.atHome() || !observation.homeLoaded()) {
            return decision(Action.RETURN_HOME, HomeEconomySession.Phase.RETURNING_HOME,
                    "return_home", "Route to the exact configured home before touching its assets");
        }
        Decision assetDecision = decideAssets(session, observation);
        if (!observation.homeWorkspaceSafe()) {
            // A Home approach may temporarily remove the exact anchor support
            // as part of a route that will replace it on the next movement.
            // Only the already-owned carried asset controller may retain that
            // route. No chest opening, acquisition, or stock work is allowed
            // until the anchor is physically safe again.
            if (session.pendingTransfer() == null
                    && observation.homeWorkspaceRepairable()
                    && assetDecision != null
                    && assetDecision.action() == Action.PLACE_ASSET) {
                return assetDecision;
            }
            return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                    "unsafe_home_workspace",
                    "The observed home workspace is no longer safe for inventory work");
        }

        if (session.pendingTransfer() != null) {
            Decision chestIdentity = validatePendingTransferChest(session, observation);
            if (chestIdentity != null) return chestIdentity;
            if (!observation.chestOpenAndObserved()) {
                return decision(Action.OPEN_CHEST,
                        HomeEconomySession.Phase.RECONCILING_TRANSFER,
                        "reopen_transfer_chest",
                        "Reopen the exact pinned standalone Home chest before reconciling cargo");
            }
            if (observation.transferObservation() == null) {
                return decision(Action.RECONCILE_TRANSFER,
                        HomeEconomySession.Phase.RECONCILING_TRANSFER,
                        "await_transfer_observation",
                        "Reopen the owned chest and observe both sides before another click");
            }
            TransferResolution resolution = reconcileTransfer(
                    session.pendingTransfer(), observation.transferObservation());
            if (resolution.committed()) {
                return decision(Action.COMMIT_TRANSFER,
                        HomeEconomySession.Phase.OBSERVING_STOCK,
                        resolution.code(), resolution.detail());
            }
            if (resolution.needsRebase()) {
                return decision(Action.REBASE_TRANSFER,
                        HomeEconomySession.Phase.RECONCILING_TRANSFER,
                        resolution.code(), resolution.detail());
            }
            if (resolution.truth() == TransferTruth.PENDING) {
                return decision(Action.RECONCILE_TRANSFER,
                        HomeEconomySession.Phase.RECONCILING_TRANSFER,
                        resolution.code(), resolution.detail());
            }
            return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                    resolution.code(), resolution.detail());
        }

        if (assetDecision != null) return assetDecision;

        if (!observation.chestOpenAndObserved()) {
            return decision(Action.OPEN_CHEST, HomeEconomySession.Phase.OBSERVING_STOCK,
                    "open_owned_chest", "Open and observe the exact pinned home chest");
        }

        HomeStockPolicy.Analysis stock = HomeStockPolicy.analyze(
                observation.playerCounts(), observation.chestCounts(),
                observation.depositEligibleCounts(), stockTargets);
        if (!stock.withdrawals().isEmpty()) {
            HomeStockPolicy.Transfer transfer = stock.withdrawals().get(0);
            return decision(Action.WITHDRAW, HomeEconomySession.Phase.RETRIEVING,
                    "withdraw_before_acquire",
                    "Withdraw " + transfer.count() + ' ' + transfer.item()
                            + " from the owned chest before producing more",
                    null, transfer, Map.of(), stock);
        }
        // At this boundary no acquisition or transfer owns the body/cursor.
        // Refill a carried empty bucket from an already verified nearby source
        // before starting an unrelated resource chain. A missing source must
        // not delay other stock work, and a completed fill is reobserved below.
        if (!stock.conversions().isEmpty()
                && observation.verifiedWaterSourceAvailable()) {
            return decision(Action.FILL_WATER_BUCKET, HomeEconomySession.Phase.ACQUIRING,
                    "fill_water_bucket",
                    "Fill the carried bucket from the verified nearby source before new stock acquisition",
                    null, null, Map.of(), stock);
        }
        // Free physical receiving room before acquisition when the inventory is
        // full. Once room exists, retain carried ingredients for that batch:
        // storing all raw food/planks first would force unnecessary reacquisition.
        // After acquisition, the ordinary complete surplus deposit still runs.
        if (!stock.deposits().isEmpty()
                && (stock.acquisitions().isEmpty() || !observation.playerHasEmptyInventorySlot())) {
            HomeStockPolicy.Transfer transfer = stock.deposits().get(0);
            if (!observation.chestCanAcceptTransfer()) {
                return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                        "home_chest_full",
                        "The owned chest has no verified merge or empty slot for the planned surplus",
                        null, transfer, Map.of(), stock);
            }
            return decision(Action.DEPOSIT, HomeEconomySession.Phase.DEPOSITING,
                    "deposit_verified_surplus",
                    "Deposit only the explicitly eligible surplus above carried stock floors",
                    null, transfer, Map.of(), stock);
        }
        if (!stock.acquisitions().isEmpty()) {
            if (!observation.plannerAvailable()) {
                return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                        "acquisition_planner_unavailable",
                        "The universal acquisition planner is unavailable", null, null,
                        stock.plannerGoals(), stock);
            }
            return decision(Action.START_ACQUISITION, HomeEconomySession.Phase.ACQUIRING,
                    "acquire_stock_deficits",
                    "Acquire the next stock category, then return to Home before reobserving remaining deficits",
                    null, null, stock.plannerGoals(), stock);
        }
        if (!stock.conversions().isEmpty()) {
            return decision(Action.DEGRADED_READY,
                    HomeEconomySession.Phase.DEGRADED_READY,
                    "water_bucket_deficit",
                    "Home is usable; water_bucket: 0/1 (no verified fill source). "
                            + "The deficit will be rechecked while idle",
                    null, null, Map.of(), stock);
        }
        return decision(Action.READY, HomeEconomySession.Phase.READY,
                "stock_ready", "All carried targets and owned-home storage constraints are satisfied",
                null, null, Map.of(), stock);
    }

    public static TransferResolution reconcileTransfer(
            HomeEconomySession.PendingTransfer pending,
            TransferObservation observed) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(observed, "observed");
        if (!observed.cursorEmpty()) {
            if (observed.exactCursorCustody()) {
                return transfer(TransferTruth.PENDING, "transfer_cursor_owned",
                        "The exact durable Home transfer owns the cursor; continue its "
                                + "acknowledgement-fenced clicks",
                        0);
            }
            return transfer(TransferTruth.CURSOR_OCCUPIED, "cursor_occupied",
                    "The cursor contains cargo; do not issue another home transfer click", 0);
        }
        int expectedPlayer;
        int expectedChest;
        if (pending.direction() == HomeStockPolicy.Direction.WITHDRAW) {
            expectedPlayer = Math.addExact(pending.playerCountBefore(), pending.count());
            expectedChest = Math.subtractExact(pending.chestCountBefore(), pending.count());
        } else {
            expectedPlayer = Math.subtractExact(pending.playerCountBefore(), pending.count());
            expectedChest = Math.addExact(pending.chestCountBefore(), pending.count());
        }
        if (observed.playerItemCount() == expectedPlayer
                && observed.chestItemCount() == expectedChest) {
            return transfer(TransferTruth.COMMITTED, "transfer_committed",
                    "Observed the exact source and destination count deltas with an empty cursor",
                    pending.count());
        }
        if (observed.playerItemCount() == pending.playerCountBefore()
                && observed.chestItemCount() == pending.chestCountBefore()) {
            if (observed.syncId() != pending.syncId()) {
                return transfer(TransferTruth.REBIND_REQUIRED, "transfer_handler_rebind",
                        "No transfer occurred; replan slots and durably bind the intent to the reopened chest",
                        0);
            }
            return transfer(TransferTruth.PENDING, "transfer_not_observed",
                    "No count delta is visible yet; retain the durable intent and await acknowledgement",
                    0);
        }
        int playerDelta = observed.playerItemCount() - pending.playerCountBefore();
        int chestDelta = observed.chestItemCount() - pending.chestCountBefore();
        int moved = pending.direction() == HomeStockPolicy.Direction.WITHDRAW
                ? playerDelta : chestDelta;
        boolean conserved = playerDelta == -chestDelta;
        boolean monotonic = pending.direction() == HomeStockPolicy.Direction.WITHDRAW
                ? playerDelta > 0 && chestDelta < 0
                : playerDelta < 0 && chestDelta > 0;
        if (conserved && monotonic && moved > 0 && moved < pending.count()) {
            return transfer(TransferTruth.PARTIAL_COMMITTED, "transfer_partially_committed",
                    "Observed a conserved partial transfer of " + moved
                            + "; durably rebase the remaining transfer before another click",
                    moved);
        }
        return transfer(TransferTruth.DIVERGED, "transfer_counts_diverged",
                "Observed counts match neither the before-state nor a conserved transfer delta",
                0);
    }

    private static Decision validateHomeIdentity(
            HomeEconomySession.Snapshot session,
            Observation observation) {
        if (session.home() == null && observation.configuredHome() == null) {
            return decision(Action.REQUIRE_HOME, HomeEconomySession.Phase.NEEDS_HOME,
                    "missing_home", "Establish a safe home before stock maintenance can run");
        }
        if (session.home() == null) {
            return decision(Action.BIND_CONFIGURED_HOME,
                    HomeEconomySession.Phase.PROVISIONING_ASSETS,
                    "bind_configured_home",
                    "Persist the settings-store home fingerprint before provisioning owned assets");
        }
        if (observation.configuredHome() == null) {
            return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                    "home_settings_missing",
                    "The durable economy home exists but the settings-store home was cleared");
        }
        if (!session.home().fingerprint().equals(observation.configuredHome().fingerprint())) {
            return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                    "home_binding_changed",
                    "The configured home no longer matches this economy generation; clear it explicitly");
        }
        return null;
    }

    private static Decision decidePendingAcquisition(
            HomeEconomySession.Snapshot session,
            Observation observation) {
        return switch (observation.acquisitionTruth()) {
            case ACTIVE -> decision(Action.WAIT_ACQUISITION,
                    HomeEconomySession.Phase.ACQUIRING, "acquisition_active",
                    "Resume the exact durable universal-planner program");
            case COMPLETED -> decision(Action.WAIT_ACQUISITION,
                    HomeEconomySession.Phase.RETURNING_HOME, "acquisition_completed",
                    "Planner postconditions are verified; restore the exact Home anchor before committing");
            case FAILED -> decision(Action.RETIRE_ACQUISITION,
                    HomeEconomySession.Phase.RETURNING_HOME, "acquisition_failed_retire",
                    acquisitionDetail(observation,
                            "Retire the terminal planner program and reobserve current deficits"));
            case DIVERGED -> decision(Action.RETIRE_ACQUISITION,
                    HomeEconomySession.Phase.RETURNING_HOME, "acquisition_diverged_retire",
                    acquisitionDetail(observation,
                            "Retire the completed program with missing goals and reobserve truth"));
            case NONE -> decision(Action.RETIRE_ACQUISITION,
                    HomeEconomySession.Phase.RETURNING_HOME,
                    "acquisition_checkpoint_missing_retire",
                    "Retire the missing planner checkpoint without claiming success, then reobserve stock");
        };
    }

    /** Pending cargo depends only on the exact old chest, never table/furnace health. */
    private static Decision validatePendingTransferChest(
            HomeEconomySession.Snapshot session,
            Observation observation) {
        HomeEconomySession.StorageRegistration pinned = session.pendingTransfer().containerIdentity() == null
                ? session.primaryStorage()
                : session.storageRegistrations().get(session.pendingTransfer().containerIdentity().storageId());
        AssetObservation seen = observation.assets().getOrDefault(
                HomeEconomySession.AssetRole.CHEST,
                AssetObservation.missing(HomeEconomySession.AssetRole.CHEST));
        if (pinned == null) {
            return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                    "pending_transfer_chest_unpinned",
                    "The durable transfer's exact Home chest pin is missing; no click is safe");
        }
        if (!pinned.ledgerAssetId().equals(seen.ledgerAssetId())
                || seen.truth() != AssetTruth.OWNED_PLACED_VERIFIED) {
            return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                    "pending_transfer_chest_unverified",
                    "Restore the exact registered Home chest "
                            + pinned.ledgerAssetId() + " before reconciling its cargo");
        }
        return null;
    }

    private static Decision decideAssets(
            HomeEconomySession.Snapshot session,
            Observation observation) {
        for (HomeEconomySession.AssetRole role : HomeEconomySession.AssetRole.values()) {
            HomeEconomySession.PinnedAsset pinned = session.pinnedAssets().get(role);
            AssetObservation seen = observation.assets().getOrDefault(
                    role, AssetObservation.missing(role));
            if (role == HomeEconomySession.AssetRole.CHEST && session.storageExplicitlyMissing()) {
                return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                        "home_storage_missing", "No registered storage remains; add one explicitly. No replacement will be built.");
            }
            if (role == HomeEconomySession.AssetRole.CHEST
                    && seen.truth() == AssetTruth.OWNED_PLACED_VERIFIED
                    && session.storageRegistrations().values().stream()
                    .anyMatch(storage -> storage.ledgerAssetId().equals(seen.ledgerAssetId()))) continue;
            if (pinned == null) {
                String code = seen.truth() == AssetTruth.FOREIGN_PRESENT
                        ? "foreign_asset_ignored" : "home_asset_unowned";
                String detail = seen.truth() == AssetTruth.FOREIGN_PRESENT
                        ? "A nearby " + role.item() + " is not Entity-owned; acquire and place a new one"
                        : "Acquire and register Entity's home " + role.item();
                return decision(Action.ACQUIRE_ASSET,
                        HomeEconomySession.Phase.PROVISIONING_ASSETS,
                        code, detail, role, null, Map.of(role.item(), 1), null);
            }
            if (!pinned.ledgerAssetId().equals(seen.ledgerAssetId())) {
                if (seen.truth() == AssetTruth.MISSING
                        || seen.truth() == AssetTruth.FOREIGN_PRESENT) {
                    return decision(Action.REPLACE_ASSET,
                            HomeEconomySession.Phase.PROVISIONING_ASSETS,
                            "owned_asset_missing",
                            "The pinned " + role.item()
                                    + " is absent or replaced; prove loss before provisioning another",
                            role, null, Map.of(role.item(), 1), null);
                }
                return decision(Action.REPORT_BLOCKED, HomeEconomySession.Phase.BLOCKED,
                        "asset_identity_mismatch",
                        "Observed " + role.item() + " ownership does not match the pinned ledger ID");
            }
            switch (seen.truth()) {
                case OWNED_CARRIED -> {
                    return decision(Action.PLACE_ASSET,
                            HomeEconomySession.Phase.PROVISIONING_ASSETS,
                            "place_owned_asset",
                            "Place and verify the pinned home " + role.item(), role,
                            null, Map.of(), null);
                }
                case OWNED_PLACED_UNLOADED -> {
                    return decision(Action.VERIFY_ASSET,
                            HomeEconomySession.Phase.VERIFYING_ASSETS,
                            "verify_owned_asset",
                            "Load and reobserve the pinned home " + role.item(), role,
                            null, Map.of(), null);
                }
                case OWNED_PLACED_MISSING, OWNED_LOST, MISSING, FOREIGN_PRESENT -> {
                    return decision(Action.REPLACE_ASSET,
                            HomeEconomySession.Phase.PROVISIONING_ASSETS,
                            "owned_asset_lost",
                            "The pinned " + role.item()
                                    + " is lost; preserve the loss proof and acquire a replacement",
                            role, null, Map.of(role.item(), 1), null);
                }
                case OWNED_PLACED_VERIFIED -> {
                    // This role is safe; inspect the next role.
                }
            }
        }
        return null;
    }

    private static String acquisitionDetail(Observation observation, String fallback) {
        return observation.acquisitionDetail().isBlank()
                ? fallback : observation.acquisitionDetail();
    }

    private static String authorityLabel(Authority authority) {
        return authority.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static Eligibility ineligible(String code, String detail) {
        return new Eligibility(false, code, detail);
    }

    private static TransferResolution transfer(
            TransferTruth truth,
            String code,
            String detail,
            int movedCount) {
        return new TransferResolution(truth, code, detail, movedCount);
    }

    private static Decision decision(
            Action action,
            HomeEconomySession.Phase phase,
            String code,
            String detail) {
        return decision(action, phase, code, detail, null, null, Map.of(), null);
    }

    private static Decision decision(
            Action action,
            HomeEconomySession.Phase phase,
            String code,
            String detail,
            HomeEconomySession.AssetRole role,
            HomeStockPolicy.Transfer transfer,
            Map<String, Integer> goals,
            HomeStockPolicy.Analysis stock) {
        return new Decision(action, phase, code, detail, role, transfer, goals, stock);
    }

    private static Map<String, Integer> immutableCountMap(
            Map<String, Integer> values,
            String field) {
        Objects.requireNonNull(values, field);
        LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
        values.forEach((rawItem, rawCount) -> {
            if (rawItem == null || rawItem.isBlank() || rawCount == null || rawCount < 0) {
                throw new IllegalArgumentException(field + " contains an invalid item/count");
            }
            String item = rawItem.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (item.startsWith("minecraft:")) item = item.substring("minecraft:".length());
            if (rawCount > 0) copy.merge(item, rawCount, Math::addExact);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String requireId(String value, String field) {
        String id = requireText(value, field, 128)
                .toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!id.matches("[a-z0-9_.:-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return id;
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        if (trimmed.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return trimmed;
    }
}
