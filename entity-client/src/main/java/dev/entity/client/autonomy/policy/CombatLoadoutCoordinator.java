package dev.entity.client.autonomy.policy;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Acknowledgement-fenced sequencing for a carried combat weapon and shield.
 *
 * <p>The coordinator never infers success from a click being issued. Every
 * issued mutation must advance the inventory acknowledgement counter before
 * the requested component can become ready. A timed-out or otherwise vanished
 * acknowledgement makes the current episode unavailable rather than allowing
 * combat to begin from optimistic client inventory state.</p>
 */
public final class CombatLoadoutCoordinator {
    private static final long PASSIVE_RETRY_TIMEOUT_MILLIS = 750L;
    public static final String SHIELD_ITEM = "minecraft:shield";
    public static final List<String> CARRIED_WEAPON_ORDER = List.of(
            "minecraft:netherite_sword",
            "minecraft:diamond_sword",
            "minecraft:iron_sword",
            "minecraft:stone_sword",
            "minecraft:golden_sword",
            "minecraft:wooden_sword",
            "minecraft:netherite_axe",
            "minecraft:diamond_axe",
            "minecraft:iron_axe",
            "minecraft:stone_axe",
            "minecraft:golden_axe",
            "minecraft:wooden_axe");

    public enum Result {
        READY,
        WAITING,
        UNAVAILABLE
    }

    public enum Requirement {
        NONE,
        WEAPON,
        SHIELD,
        WEAPON_AND_SHIELD
    }

    public enum Order {
        WEAPON_FIRST,
        SHIELD_FIRST
    }

    /** Result of asking the inventory adapter to advance one equip operation. */
    public enum EquipResult {
        CLICKED,
        ALREADY_DONE,
        WAITING,
        ITEM_MISSING,
        SLOT_UNAVAILABLE,
        CURSOR_NOT_EMPTY
    }

    /**
     * Small adapter surface deliberately free of Minecraft classes so episode,
     * ordering, and acknowledgement behavior can be tested as a pure policy.
     */
    public interface InventoryPort {
        int count(String itemId);

        boolean isMainHandEquipped(String itemId);

        boolean isShieldEquippedOffhand();

        boolean isShieldUsableOffhand();

        boolean hasUnsettledMutation();

        long acknowledgedMutations();

        long timedOutMutations();

        EquipResult equip(String itemId, String transactionOwner, long nowMillis);
    }

    private long closedThroughGeneration = Long.MIN_VALUE;
    private long activeGeneration = Long.MIN_VALUE;
    private final String transactionOwnerPrefix;
    private PendingMutation pendingMutation;
    private PendingMutation retiredMutation;
    private InProgressEquip resumableEquip;
    private boolean failed;
    private long passiveRetryStartedAt = Long.MIN_VALUE;

    public CombatLoadoutCoordinator() {
        this("protection-loadout");
    }

    /** Gives independent protection and explicit-attack episodes distinct click ownership. */
    public CombatLoadoutCoordinator(String transactionOwnerPrefix) {
        String normalized = Objects.requireNonNullElse(transactionOwnerPrefix, "").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("transactionOwnerPrefix cannot be blank");
        }
        this.transactionOwnerPrefix = normalized;
    }

    /**
     * Advances one loadout episode by at most one inventory mutation.
     * Generations must increase between episodes; released generations cannot
     * be reopened by stale callbacks.
     */
    public Result step(
            long episodeGeneration,
            Requirement requirement,
            Order order,
            InventoryPort inventory,
            long nowMillis) {
        if (episodeGeneration < 0L) {
            throw new IllegalArgumentException("episodeGeneration cannot be negative");
        }
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(inventory, "inventory");

        if (!claimEpisode(episodeGeneration)) return Result.UNAVAILABLE;
        if (failed) return Result.UNAVAILABLE;
        Result retiredAcknowledgement = reconcileRetiredMutation(inventory);
        if (retiredAcknowledgement != null) return retiredAcknowledgement;
        Result acknowledgement = reconcilePendingMutation(inventory);
        if (acknowledgement != null) return acknowledgement;
        Result resumedEquip = resumeOwnedEquip(inventory, nowMillis);
        if (resumedEquip != null) return resumedEquip;

        String weapon = requirementNeedsWeapon(requirement)
                ? bestCarriedWeapon(inventory)
                : "";
        if (requirementNeedsWeapon(requirement) && weapon.isEmpty()) {
            return Result.UNAVAILABLE;
        }
        if (requirementNeedsShield(requirement)
                && !inventory.isShieldEquippedOffhand()
                && inventory.count(SHIELD_ITEM) <= 0) {
            return Result.UNAVAILABLE;
        }

        List<Component> sequence = sequence(requirement, order);
        for (Component component : sequence) {
            String item = component == Component.SHIELD ? SHIELD_ITEM : weapon;
            if (componentReady(component, item, inventory)) continue;

            // A correctly equipped but disabled shield must cool down; moving it
            // again cannot make it usable and would disturb the offhand.  This
            // is not an inventory mutation wait: report a temporary unavailable
            // capability so the runtime can keep retreating instead of freezing.
            if (component == Component.SHIELD && inventory.isShieldEquippedOffhand()) {
                return Result.UNAVAILABLE;
            }

            EquipResult equip = Objects.requireNonNull(
                    inventory.equip(item, transactionOwner(episodeGeneration, component), nowMillis),
                    "inventory equip result");
            switch (equip) {
                case CLICKED -> {
                    passiveRetryStartedAt = Long.MIN_VALUE;
                    pendingMutation = new PendingMutation(
                            item,
                            component,
                            inventory.acknowledgedMutations(),
                            inventory.timedOutMutations());
                    return Result.WAITING;
                }
                case ALREADY_DONE -> {
                    if (!componentReady(component, item, inventory)
                            || inventory.hasUnsettledMutation()) {
                        return Result.WAITING;
                    }
                    passiveRetryStartedAt = Long.MIN_VALUE;
                }
                case WAITING -> {
                    if (inventory.hasUnsettledMutation()) {
                        passiveRetryStartedAt = Long.MIN_VALUE;
                        return Result.WAITING;
                    }
                    if (passiveRetryStartedAt == Long.MIN_VALUE) {
                        passiveRetryStartedAt = nowMillis;
                        return Result.WAITING;
                    }
                    if (nowMillis - passiveRetryStartedAt < PASSIVE_RETRY_TIMEOUT_MILLIS) {
                        return Result.WAITING;
                    }
                    passiveRetryStartedAt = Long.MIN_VALUE;
                    return Result.UNAVAILABLE;
                }
                case CURSOR_NOT_EMPTY -> {
                    // An Entity-owned cursor transfer remains acknowledgement
                    // fenced. A manual/foreign cursor is never overwritten or
                    // dropped, but it also cannot suspend evasive movement.
                    return inventory.hasUnsettledMutation()
                            ? Result.WAITING
                            : Result.UNAVAILABLE;
                }
                case ITEM_MISSING, SLOT_UNAVAILABLE -> {
                    return Result.UNAVAILABLE;
                }
            }
        }
        return inventory.hasUnsettledMutation() ? Result.WAITING : Result.READY;
    }

    /**
     * Continues a cursor swap started by this coordinator before carried-count
     * preflight. The stage-0 pickup legitimately removes the item from carried
     * slots, so treating that transient zero as ITEM_MISSING would strand the
     * owned cursor before its destination click.
     *
     * <p>Only a positively acknowledged click issued by this coordinator can
     * populate {@link #resumableEquip}. An arbitrary/manual cursor therefore
     * still reaches CURSOR_NOT_EMPTY and remains fail-closed.</p>
     */
    private Result resumeOwnedEquip(InventoryPort inventory, long nowMillis) {
        if (resumableEquip == null) return null;
        InProgressEquip owned = resumableEquip;
        EquipResult equip = Objects.requireNonNull(
                inventory.equip(
                        owned.item(),
                        transactionOwner(activeGeneration, owned.component()),
                        nowMillis),
                "inventory equip result");
        switch (equip) {
            case CLICKED -> {
                passiveRetryStartedAt = Long.MIN_VALUE;
                resumableEquip = null;
                pendingMutation = new PendingMutation(
                        owned.item(),
                        owned.component(),
                        inventory.acknowledgedMutations(),
                        inventory.timedOutMutations());
                return Result.WAITING;
            }
            case ALREADY_DONE -> {
                if (!componentReady(owned.component(), owned.item(), inventory)
                        || inventory.hasUnsettledMutation()) {
                    return Result.WAITING;
                }
                resumableEquip = null;
                passiveRetryStartedAt = Long.MIN_VALUE;
                return null;
            }
            case WAITING -> {
                if (inventory.hasUnsettledMutation()) {
                    passiveRetryStartedAt = Long.MIN_VALUE;
                    return Result.WAITING;
                }
                if (passiveRetryStartedAt == Long.MIN_VALUE) {
                    passiveRetryStartedAt = nowMillis;
                    return Result.WAITING;
                }
                if (nowMillis - passiveRetryStartedAt < PASSIVE_RETRY_TIMEOUT_MILLIS) {
                    return Result.WAITING;
                }
                passiveRetryStartedAt = Long.MIN_VALUE;
                return Result.UNAVAILABLE;
            }
            case CURSOR_NOT_EMPTY -> {
                return inventory.hasUnsettledMutation()
                        ? Result.WAITING
                        : Result.UNAVAILABLE;
            }
            case ITEM_MISSING, SLOT_UNAVAILABLE -> {
                return Result.UNAVAILABLE;
            }
        }
        throw new IllegalStateException("unhandled equip result " + equip);
    }

    /** Releases exactly one episode. A stale release cannot clear a newer one. */
    public void release(long episodeGeneration) {
        if (episodeGeneration != activeGeneration) return;
        closedThroughGeneration = Math.max(closedThroughGeneration, episodeGeneration);
        retirePendingMutation();
        clearActive();
    }

    /** Invalidates the active episode during an unconditional owner preemption. */
    public void invalidateActive() {
        if (activeGeneration != Long.MIN_VALUE) {
            closedThroughGeneration = Math.max(closedThroughGeneration, activeGeneration);
        }
        retirePendingMutation();
        clearActive();
    }

    public OptionalLong activeGeneration() {
        return activeGeneration == Long.MIN_VALUE
                ? OptionalLong.empty()
                : OptionalLong.of(activeGeneration);
    }

    private boolean claimEpisode(long generation) {
        if (activeGeneration == generation) return true;
        if (generation <= closedThroughGeneration) return false;
        if (activeGeneration != Long.MIN_VALUE && generation < activeGeneration) return false;
        if (activeGeneration != Long.MIN_VALUE) {
            closedThroughGeneration = Math.max(closedThroughGeneration, activeGeneration);
            retirePendingMutation();
        }
        clearActive();
        activeGeneration = generation;
        return true;
    }

    /** Returns null when the caller may continue sequencing this tick. */
    private Result reconcilePendingMutation(InventoryPort inventory) {
        if (pendingMutation == null) return null;
        if (inventory.timedOutMutations() > pendingMutation.timedOutBaseline()) {
            pendingMutation = null;
            resumableEquip = null;
            failed = true;
            return Result.UNAVAILABLE;
        }
        if (inventory.acknowledgedMutations() <= pendingMutation.acknowledgedBaseline()) {
            if (!inventory.hasUnsettledMutation()) {
                // The mutation fence disappeared without either positive
                // acknowledgement or a typed timeout. Never trust optimistic
                // client slot state after that discontinuity.
                pendingMutation = null;
                resumableEquip = null;
                failed = true;
                return Result.UNAVAILABLE;
            }
            return Result.WAITING;
        }
        if (inventory.hasUnsettledMutation()) return Result.WAITING;
        PendingMutation acknowledged = pendingMutation;
        pendingMutation = null;
        resumableEquip = new InProgressEquip(
                acknowledged.item(), acknowledged.component());
        passiveRetryStartedAt = Long.MIN_VALUE;
        return null;
    }

    /**
     * A released episode may still have one server acknowledgement in flight.
     * Preserve that fence across generations so a newer episode cannot call
     * optimistic slot state ready or take over a half-finished cursor swap.
     */
    private Result reconcileRetiredMutation(InventoryPort inventory) {
        if (retiredMutation == null) return null;
        if (inventory.timedOutMutations() > retiredMutation.timedOutBaseline()) {
            retiredMutation = null;
            failed = true;
            return Result.UNAVAILABLE;
        }
        if (inventory.acknowledgedMutations() <= retiredMutation.acknowledgedBaseline()) {
            if (!inventory.hasUnsettledMutation()) {
                retiredMutation = null;
                failed = true;
                return Result.UNAVAILABLE;
            }
            return Result.WAITING;
        }
        if (inventory.hasUnsettledMutation()) return Result.WAITING;
        retiredMutation = null;
        passiveRetryStartedAt = Long.MIN_VALUE;
        return null;
    }

    private void retirePendingMutation() {
        if (pendingMutation != null) retiredMutation = pendingMutation;
        pendingMutation = null;
    }

    public static String bestCarriedWeapon(InventoryPort inventory) {
        Objects.requireNonNull(inventory, "inventory");
        for (String weapon : CARRIED_WEAPON_ORDER) {
            if (inventory.count(weapon) > 0) return weapon;
        }
        return "";
    }

    private static boolean componentReady(
            Component component,
            String item,
            InventoryPort inventory) {
        return switch (component) {
            case WEAPON -> inventory.isMainHandEquipped(item);
            case SHIELD -> inventory.isShieldEquippedOffhand()
                    && inventory.isShieldUsableOffhand();
        };
    }

    private static List<Component> sequence(Requirement requirement, Order order) {
        return switch (requirement) {
            case NONE -> List.of();
            case WEAPON -> List.of(Component.WEAPON);
            case SHIELD -> List.of(Component.SHIELD);
            case WEAPON_AND_SHIELD -> order == Order.SHIELD_FIRST
                    ? List.of(Component.SHIELD, Component.WEAPON)
                    : List.of(Component.WEAPON, Component.SHIELD);
        };
    }

    private static boolean requirementNeedsWeapon(Requirement requirement) {
        return requirement == Requirement.WEAPON
                || requirement == Requirement.WEAPON_AND_SHIELD;
    }

    private static boolean requirementNeedsShield(Requirement requirement) {
        return requirement == Requirement.SHIELD
                || requirement == Requirement.WEAPON_AND_SHIELD;
    }

    private String transactionOwner(long generation, Component component) {
        return transactionOwnerPrefix + ":g" + generation + ':'
                + component.name().toLowerCase(Locale.ROOT);
    }

    private void clearActive() {
        activeGeneration = Long.MIN_VALUE;
        pendingMutation = null;
        resumableEquip = null;
        failed = false;
        passiveRetryStartedAt = Long.MIN_VALUE;
    }

    private enum Component {
        WEAPON,
        SHIELD
    }

    private record PendingMutation(
            String item,
            Component component,
            long acknowledgedBaseline,
            long timedOutBaseline) {
        private PendingMutation {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(component, "component");
        }
    }

    private record InProgressEquip(String item, Component component) {
        private InProgressEquip {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(component, "component");
        }
    }
}
