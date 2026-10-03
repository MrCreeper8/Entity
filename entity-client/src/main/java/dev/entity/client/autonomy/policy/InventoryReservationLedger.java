package dev.entity.client.autonomy.policy;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared inventory ownership for mission cargo and Entity's own supplies.
 *
 * <p>Reservations do not pretend that an item already exists. They describe
 * who may spend an item once it is present. Callers choose the purposes that
 * must be protected from the operation they are about to perform. This keeps
 * planning, survival, delivery, and future base storage on one accounting
 * model instead of giving every controller another item-count exception.</p>
 */
public final class InventoryReservationLedger {
    public enum Purpose {
        MISSION_CARGO,
        /** Ingredient/output owned by the active projected prerequisite chain. */
        PLAN_COMMITMENT,
        PERSONAL_RATION,
        FIELD_KIT,
        TOOL,
        FUEL,
        /** Carried equipment retained for an explicitly supported emergency technique. */
        EMERGENCY_TECHNIQUE,
        BUILDING_BLOCKS
    }

    public record Reservation(
            String id,
            String owner,
            String item,
            int count,
            Purpose purpose,
            String reason) {
        public Reservation {
            id = requireText(id, "id");
            owner = requireText(owner, "owner");
            item = normalizeItem(item);
            if (count <= 0) throw new IllegalArgumentException("count must be positive");
            purpose = Objects.requireNonNull(purpose, "purpose");
            reason = requireText(reason, "reason");
        }
    }

    private final Map<String, Reservation> reservations = new LinkedHashMap<>();

    public synchronized void replaceOwner(String owner, Collection<Reservation> replacements) {
        String normalizedOwner = requireText(owner, "owner");
        Objects.requireNonNull(replacements, "replacements");
        LinkedHashMap<String, Reservation> candidate = new LinkedHashMap<>();
        reservations.forEach((id, existing) -> {
            if (!existing.owner().equals(normalizedOwner)) candidate.put(id, existing);
        });
        for (Reservation reservation : replacements) {
            Objects.requireNonNull(reservation, "reservation");
            if (!reservation.owner().equals(normalizedOwner)) {
                throw new IllegalArgumentException("replacement owner does not match " + normalizedOwner);
            }
            if (candidate.putIfAbsent(reservation.id(), reservation) != null) {
                throw new IllegalArgumentException("duplicate reservation id " + reservation.id());
            }
        }
        // Commit only after the full replacement set validates. A malformed
        // replan must not erase the last known-good cargo ownership state.
        reservations.clear();
        reservations.putAll(candidate);
    }

    public synchronized void reserve(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        reservations.put(reservation.id(), reservation);
    }

    public synchronized void release(String reservationId) {
        if (reservationId != null) reservations.remove(reservationId);
    }

    public synchronized void releaseOwner(String owner) {
        if (owner == null) return;
        reservations.values().removeIf(existing -> existing.owner().equals(owner));
    }

    public synchronized void clear() {
        reservations.clear();
    }

    public synchronized int reservedCount(String item, Set<Purpose> protectedPurposes) {
        String normalized = normalizeItem(item);
        Set<Purpose> purposes = checkedPurposes(protectedPurposes);
        int total = 0;
        for (Reservation reservation : reservations.values()) {
            if (reservation.item().equals(normalized) && purposes.contains(reservation.purpose())) {
                total = Math.addExact(total, reservation.count());
            }
        }
        return total;
    }

    public synchronized int spendableCount(
            String item,
            int inventoryCount,
            Set<Purpose> protectedPurposes) {
        if (inventoryCount < 0) throw new IllegalArgumentException("inventoryCount cannot be negative");
        return Math.max(0, inventoryCount - reservedCount(item, protectedPurposes));
    }

    /** Returns normalized counts after protecting the requested reservation purposes. */
    public synchronized Map<String, Integer> spendableCounts(
            Map<String, Integer> inventoryCounts,
            Set<Purpose> protectedPurposes) {
        return spendableCountsExceptReservations(
                inventoryCounts, protectedPurposes, Set.of());
    }

    /**
     * Returns normalized spendable counts while allowing one active objective
     * to use only the reservations it explicitly owns. Exempting reservation
     * IDs, rather than whole item types, keeps identically named cargo for a
     * different batch objective protected.
     */
    public synchronized Map<String, Integer> spendableCountsExceptReservations(
            Map<String, Integer> inventoryCounts,
            Set<Purpose> protectedPurposes,
            Set<String> exemptReservationIds) {
        Objects.requireNonNull(inventoryCounts, "inventoryCounts");
        Set<Purpose> purposes = checkedPurposes(protectedPurposes);
        Objects.requireNonNull(exemptReservationIds, "exemptReservationIds");
        Set<String> exemptions = Set.copyOf(exemptReservationIds);
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        inventoryCounts.forEach((item, count) -> {
            if (count == null || count < 0) {
                throw new IllegalArgumentException("inventory counts cannot be negative");
            }
            if (count > 0) normalized.merge(normalizeItem(item), count, Math::addExact);
        });
        LinkedHashMap<String, Integer> spendable = new LinkedHashMap<>();
        normalized.forEach((item, count) -> {
            int reserved = 0;
            for (Reservation reservation : reservations.values()) {
                if (reservation.item().equals(item)
                        && purposes.contains(reservation.purpose())
                        && !exemptions.contains(reservation.id())) {
                    reserved = Math.addExact(reserved, reservation.count());
                }
            }
            int available = Math.max(0, count - reserved);
            if (available > 0) spendable.put(item, available);
        });
        return Collections.unmodifiableMap(spendable);
    }

    public synchronized Map<String, Integer> snapshot(Set<Purpose> purposes) {
        Set<Purpose> selected = checkedPurposes(purposes);
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Reservation reservation : reservations.values()) {
            if (selected.contains(reservation.purpose())) {
                result.merge(reservation.item(), reservation.count(), Math::addExact);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized Map<String, Reservation> reservations() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(reservations));
    }

    public static String normalizeItem(String raw) {
        String item = requireText(raw, "item").toLowerCase(Locale.ROOT).replace(' ', '_');
        return item.indexOf(':') >= 0 ? item : "minecraft:" + item;
    }

    private static Set<Purpose> checkedPurposes(Set<Purpose> purposes) {
        Objects.requireNonNull(purposes, "purposes");
        return purposes.isEmpty() ? EnumSet.noneOf(Purpose.class) : EnumSet.copyOf(purposes);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank");
        return trimmed;
    }
}
