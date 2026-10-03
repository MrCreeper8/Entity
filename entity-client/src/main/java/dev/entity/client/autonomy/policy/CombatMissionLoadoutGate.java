package dev.entity.client.autonomy.policy;

import java.util.Objects;

/**
 * Mission-scoped acknowledgement gate for an explicit {@code /e attack}.
 *
 * <p>Only equipment already carried when an attack starts is requested. A
 * missing weapon or shield is therefore a legitimate reduced loadout, never a
 * reason to hold the mission forever. Once the available loadout is either
 * acknowledged or proven unavailable, that mission is released to routing and
 * close combat without reconsidering inventory on every movement tick.</p>
 */
public final class CombatMissionLoadoutGate {
    private final CombatLoadoutCoordinator coordinator =
            new CombatLoadoutCoordinator("attack-loadout");

    private long generation;
    private String missionId = "";
    private CombatLoadoutCoordinator.Requirement requirement =
            CombatLoadoutCoordinator.Requirement.NONE;
    private CombatLoadoutCoordinator.Result terminalResult;

    public CombatLoadoutCoordinator.Result step(
            String requestedMissionId,
            CombatLoadoutCoordinator.InventoryPort inventory,
            long nowMillis) {
        String normalizedMissionId = Objects.requireNonNullElse(requestedMissionId, "").trim();
        if (normalizedMissionId.isEmpty()) {
            throw new IllegalArgumentException("attack mission id cannot be blank");
        }
        Objects.requireNonNull(inventory, "inventory");

        if (!normalizedMissionId.equals(missionId)) {
            coordinator.invalidateActive();
            generation = Math.incrementExact(generation);
            missionId = normalizedMissionId;
            requirement = carriedRequirement(inventory);
            terminalResult = null;
        }
        if (terminalResult != null) return terminalResult;

        CombatLoadoutCoordinator.Result result = coordinator.step(
                generation,
                requirement,
                CombatLoadoutCoordinator.Order.WEAPON_FIRST,
                inventory,
                nowMillis);
        if (result != CombatLoadoutCoordinator.Result.WAITING) {
            coordinator.release(generation);
            terminalResult = result;
        }
        return result;
    }

    /** Invalidates a pending click capability at any owner/safety/reconnect boundary. */
    public boolean invalidate() {
        boolean wasActive = !missionId.isEmpty();
        coordinator.invalidateActive();
        missionId = "";
        requirement = CombatLoadoutCoordinator.Requirement.NONE;
        terminalResult = null;
        return wasActive;
    }

    public String missionId() {
        return missionId;
    }

    private static CombatLoadoutCoordinator.Requirement carriedRequirement(
            CombatLoadoutCoordinator.InventoryPort inventory) {
        boolean weapon = !CombatLoadoutCoordinator.bestCarriedWeapon(inventory).isEmpty();
        boolean shield = inventory.isShieldEquippedOffhand()
                || inventory.count(CombatLoadoutCoordinator.SHIELD_ITEM) > 0;
        if (weapon && shield) {
            return CombatLoadoutCoordinator.Requirement.WEAPON_AND_SHIELD;
        }
        if (weapon) return CombatLoadoutCoordinator.Requirement.WEAPON;
        if (shield) return CombatLoadoutCoordinator.Requirement.SHIELD;
        return CombatLoadoutCoordinator.Requirement.NONE;
    }
}
