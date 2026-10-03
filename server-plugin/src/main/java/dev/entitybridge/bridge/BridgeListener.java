package dev.entitybridge.bridge;

import dev.entitybridge.delivery.DeliveryCommitRequest;
import dev.entitybridge.delivery.DeliveryCommitResult;
import dev.entitybridge.mission.Mission;
import dev.entitybridge.recipe.RecipeDiscoveryResult;
import dev.entity.core.stewardship.ProtectedAreaHomePermit;

import java.util.List;
import java.util.function.Consumer;

public interface BridgeListener {
    default void onConnectionChanged(boolean connected, String client) {
    }

    default void onResult(BridgeResult result) {
    }

    default void onTelemetry(TelemetrySnapshot telemetry) {
    }

    default void onMissionUpdated(Mission mission) {
    }

    default void onProtectedAreaPolicyAcknowledged(long revision, String digest) {
    }

    default void onProtectedAreaHomePermitRequested(
            ProtectedAreaHomePermit.Request request) {
    }

    /**
     * Invoked on a bridge I/O thread for the opt-in recipe unlock. The
     * implementation must perform Paper player/recipe access on the primary
     * thread and complete exactly once.
     */
    default void onRecipeDiscovery(
            String playerName,
            List<String> exportedRecipeIds,
            Consumer<RecipeDiscoveryResult> completion) {
        completion.accept(RecipeDiscoveryResult.unavailable(
                playerName,
                exportedRecipeIds.size(),
                "discovery_unavailable",
                "Paper recipe discovery is unavailable"));
    }

    /**
     * Invoked on a bridge I/O thread. Implementations must move the work to the
     * Bukkit primary thread and complete the callback exactly once.
     */
    default void onDeliveryCommit(
            DeliveryCommitRequest request,
            Consumer<DeliveryCommitResult> completion) {
        completion.accept(DeliveryCommitResult.rejected(
                request, false, "commit_unavailable",
                "Paper-authoritative inventory handoff is unavailable"));
    }
}
