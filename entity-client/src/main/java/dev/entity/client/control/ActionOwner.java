package dev.entity.client.control;

/**
 * The mutually exclusive client-side actuators that may drive Entity's body.
 *
 * <p>The durable core still decides whether survival, protection, or a mission
 * owns the outer {@code ControlLease}. This enum identifies the single inner
 * actuator allowed to translate that authority into Minecraft input.</p>
 */
public enum ActionOwner {
    BARITONE_ROUTE,
    HUNT_COMBAT,
    GROUND_PICKUP,
    WORKSPACE_BREAK,
    WORKSTATION_INTERACTION,
    INVENTORY_TRANSACTION,
    DELIVERY_HANDOFF,
    SURVIVAL,
    PROTECTION,
    DEATH_RECOVERY
}
