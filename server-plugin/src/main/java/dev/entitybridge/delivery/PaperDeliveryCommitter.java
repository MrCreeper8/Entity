package dev.entitybridge.delivery;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.Objects;
import java.util.Base64;
import java.util.List;

/** Performs a conservation-safe inventory-to-inventory handoff on Paper's main thread. */
public final class PaperDeliveryCommitter {
    static final double MAX_HANDOFF_DISTANCE_SQUARED = 16.0D;

    private final Server server;
    private final DeliveryTransactionRegistry transactions;

    public PaperDeliveryCommitter(Server server, DeliveryTransactionRegistry transactions) {
        this.server = Objects.requireNonNull(server, "server");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public DeliveryCommitResult commit(DeliveryCommitRequest original) {
        Objects.requireNonNull(original, "original");
        if (!Bukkit.isPrimaryThread()) {
            return DeliveryCommitResult.rejected(
                    original, false, "wrong_thread",
                    "inventory handoff must execute on Paper's primary thread");
        }

        long now = System.currentTimeMillis();
        DeliveryTransactionRegistry.CommitAuthorization authorization =
                transactions.authorizeDirectCommit(original.missionId(), original.nonce(), now);
        if (!authorization.accepted()) {
            return DeliveryCommitResult.rejected(
                    original, false, authorization.code(), authorization.reason());
        }
        DeliveryCommitRequest request = authorization.request();
        if (request.remainingCount() == 0) {
            return success(request, "already_complete", "handoff was already completed", 0, null);
        }

        Player entity = onlinePlayer("Entity");
        if (entity == null || entity.isDead()) {
            return DeliveryCommitResult.rejected(
                    request, true, "entity_unavailable", "Entity must be alive and online");
        }
        Player recipient = onlinePlayer(request.recipient());
        if (recipient == null || recipient.isDead()) {
            return DeliveryCommitResult.rejected(
                    request, true, "recipient_unavailable", "intended recipient must be alive and online");
        }
        if (entity.getUniqueId().equals(recipient.getUniqueId())) {
            return DeliveryCommitResult.rejected(
                    request, false, "invalid_recipient", "Entity cannot hand items to itself");
        }
        if (!entity.getWorld().getUID().equals(recipient.getWorld().getUID())) {
            return DeliveryCommitResult.rejected(
                    request, true, "world_mismatch", "Entity and recipient must be in the same world");
        }
        if (entity.getLocation().distanceSquared(recipient.getLocation())
                > MAX_HANDOFF_DISTANCE_SQUARED) {
            return DeliveryCommitResult.rejected(
                    request, true, "recipient_too_far", "recipient must be within 4 blocks of Entity");
        }

        PlayerInventory source = entity.getInventory();
        PlayerInventory destination = recipient.getInventory();
        // Match the client PlayerInventory contract exactly: Entity may carry
        // the requested item in main storage, armor, or offhand. Recipient
        // insertion still uses ordinary storage/addItem capacity.
        ItemStack[] sourceBefore = cloneContents(source.getContents());
        ItemStack[] destinationBefore = cloneContents(destination.getStorageContents());
        int available = matchingCount(sourceBefore, request.itemId());
        if (available <= 0) {
            return DeliveryCommitResult.rejected(
                    request, true, "no_matching_items",
                    "Entity does not currently carry the prepared item");
        }

        int moved;
        try {
            List<DeliveryStackSelection.Remainder> exact = request.selections().isEmpty() ? List.of()
                    : DeliveryStackSelection.remaining(request.selections(), request.confirmedCount());
            // Validate the whole immutable selection before the first mutation. Never
            // substitute a named/enchanted same-ID stack or consume a retained share.
            for (var selected : exact) {
                ItemStack actual = source.getItem(selected.original().slot());
                ItemStack expected = ItemStack.deserializeBytes(
                        Base64.getDecoder().decode(selected.original().stackNbt()));
                if (!matches(expected, request.itemId()) || !matches(actual, request.itemId())
                        || expected.getAmount() != selected.original().stackCount()
                        || actual.getAmount() != selected.expectedStackCount() || !actual.isSimilar(expected)) {
                    return DeliveryCommitResult.rejected(request, false, "selected_stack_changed",
                            "prepared exact stack changed; no replacement or reserve was transferred");
                }
            }
            moved = exact.isEmpty()
                    ? transfer(source, destination, request.itemId(), request.remainingCount())
                    : transferSelected(source, destination, exact);
            if (moved <= 0) {
                return DeliveryCommitResult.rejected(
                        request, true, "recipient_full",
                        "recipient inventory cannot accept the prepared item");
            }
            DeliveryTransactionRegistry.DirectCommitRecord committed =
                    transactions.recordDirectCommit(
                            request.missionId(), request.nonce(), moved, now);
            DeliveryCommitRequest updated = committed.request();
            String code = updated.remainingCount() == 0 ? "committed_full" : "committed_partial";
            String message = updated.remainingCount() == 0
                    ? "Paper transferred the complete prepared handoff"
                    : "Paper transferred the available recipient capacity";
            return success(updated, code, message, moved, committed.receipt());
        } catch (RuntimeException failure) {
            // The ledger and both inventories form one logical transaction. If
            // durable receipt creation fails, restore both exact pre-commit
            // inventories so a replay cannot synthesize or silently lose items.
            source.setContents(sourceBefore);
            destination.setStorageContents(destinationBefore);
            return DeliveryCommitResult.rejected(
                    request, true, "commit_failed",
                    "Paper rolled back the handoff: " + safeMessage(failure));
        }
    }

    private Player onlinePlayer(String name) {
        return server.getOnlinePlayers().stream()
                .filter(player -> player.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private static int transfer(
            PlayerInventory source,
            PlayerInventory destination,
            String itemId,
            int limit) {
        int moved = 0;
        ItemStack[] contents = source.getContents();
        for (int slot = 0; slot < contents.length && moved < limit; slot++) {
            ItemStack stack = source.getItem(slot);
            if (!matches(stack, itemId)) continue;
            int offered = Math.min(stack.getAmount(), limit - moved);
            if (offered <= 0) continue;

            ItemStack payload = stack.clone();
            payload.setAmount(offered);
            int retained = stack.getAmount() - offered;
            if (retained == 0) {
                source.setItem(slot, null);
            } else {
                ItemStack reduced = stack.clone();
                reduced.setAmount(retained);
                source.setItem(slot, reduced);
            }

            Map<Integer, ItemStack> leftovers = destination.addItem(payload);
            int rejected = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            int inserted = offered - rejected;
            moved += inserted;
            if (rejected > 0) {
                // Restore to the exact source slot vacated above. No other
                // Bukkit event can interleave while this primary-thread method runs.
                ItemStack current = source.getItem(slot);
                ItemStack restored = stack.clone();
                restored.setAmount((current == null ? 0 : current.getAmount()) + rejected);
                source.setItem(slot, restored);
            }
            if (inserted == 0) break;
        }
        return moved;
    }

    private static int transferSelected(PlayerInventory source, PlayerInventory destination,
            List<DeliveryStackSelection.Remainder> selections) {
        int moved = 0;
        for (var selection : selections) {
            int slot = selection.original().slot();
            ItemStack original = source.getItem(slot).clone();
            ItemStack offered = original.clone();
            offered.setAmount(selection.count());
            ItemStack retained = original.clone();
            retained.setAmount(original.getAmount() - selection.count());
            source.setItem(slot, retained.getAmount() == 0 ? null : retained);
            int rejected = destination.addItem(offered).values().stream().mapToInt(ItemStack::getAmount).sum();
            if (rejected < 0 || rejected > selection.count()) throw new IllegalStateException("invalid insertion receipt");
            moved += selection.count() - rejected;
            if (rejected > 0) {
                retained.setAmount(retained.getAmount() + rejected);
                source.setItem(slot, retained);
                break; // durable confirmedCount describes a prefix, never a later slot
            }
        }
        return moved;
    }

    private static int matchingCount(ItemStack[] contents, String itemId) {
        int count = 0;
        for (ItemStack stack : contents) {
            if (matches(stack, itemId)) count += stack.getAmount();
        }
        return count;
    }

    private static boolean matches(ItemStack stack, String itemId) {
        return stack != null
                && !stack.getType().isAir()
                && stack.getType().getKey().toString().equals(itemId);
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static DeliveryCommitResult success(
            DeliveryCommitRequest request,
            String code,
            String message,
            int moved,
            DeliveryTransactionRegistry.Receipt receipt) {
        return new DeliveryCommitResult(
                request.missionId(), request.nonce(), true,
                request.remainingCount() > 0, code, message,
                request.recipient(), request.itemId(), moved,
                request.confirmedCount(), request.expectedCount(), request.remainingCount(),
                System.currentTimeMillis(), receipt);
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }
}
