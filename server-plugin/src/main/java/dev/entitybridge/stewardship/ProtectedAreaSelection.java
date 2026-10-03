package dev.entitybridge.stewardship;

import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/** One-owner preview/confirm state machine; previews never mutate active policy. */
public final class ProtectedAreaSelection {
    public static final long SELECTION_TTL_MILLIS = 5L * 60L * 1_000L;
    public static final long REMOVAL_TTL_MILLIS = 30L * 1_000L;

    private final ProtectedAreaRegistry registry;
    private PendingSelection selection;
    private PendingRemoval removal;

    public ProtectedAreaSelection(ProtectedAreaRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public synchronized PendingSelection begin(
            String owner,
            String name,
            String dimension,
            int x,
            int z,
            long nowMillis) {
        return begin(
                owner, ProtectedAreaPolicy.AreaKind.PROTECTED, name,
                dimension, x, 0, z, nowMillis);
    }

    public synchronized PendingSelection begin(
            String owner,
            ProtectedAreaPolicy.AreaKind kind,
            String name,
            String dimension,
            int x,
            int y,
            int z,
            long nowMillis) {
        String checkedOwner = required(owner, "owner");
        ProtectedAreaPolicy.AreaKind checkedKind = Objects.requireNonNull(kind, "kind");
        ProtectedAreaPolicy.Area probe = areaBetween(
                checkedKind, name, dimension, x, y, z, x, y, z);
        if (registry.named(probe.name()).isPresent()) {
            throw new IllegalArgumentException(
                    "Area '" + probe.name() + "' already exists");
        }
        selection = new PendingSelection(
                checkedOwner,
                probe.name(),
                checkedKind,
                probe.dimension(),
                x,
                y,
                z,
                nowMillis,
                null);
        removal = null;
        return selection;
    }

    public synchronized PendingSelection finish(
            String owner,
            String dimension,
            int x,
            int z,
            long nowMillis) {
        return finish(owner, dimension, x, 0, z, nowMillis);
    }

    public synchronized PendingSelection finish(
            String owner,
            String dimension,
            int x,
            int y,
            int z,
            long nowMillis) {
        PendingSelection current = requireSelection(owner, dimension, nowMillis);
        ProtectedAreaPolicy.Area preview = areaBetween(
                current.kind(), current.name(), current.dimension(),
                current.firstX(), current.firstY(), current.firstZ(), x, y, z);
        if (registry.named(preview.name()).isPresent()) {
            throw new IllegalArgumentException(
                    "Area '" + preview.name() + "' already exists");
        }
        selection = current.withPreview(preview);
        return selection;
    }

    public synchronized ProtectedAreaPolicy.Snapshot confirm(
            String owner,
            String dimension,
            long nowMillis) throws IOException {
        PendingSelection current = requireSelection(owner, dimension, nowMillis);
        if (current.preview() == null) {
            throw new IllegalArgumentException("Use /e area finish before confirming the selection");
        }
        ProtectedAreaPolicy.Snapshot result = registry.add(current.preview());
        selection = null;
        return result;
    }

    public synchronized boolean cancel(String owner) {
        boolean cancelled = false;
        if (selection != null && sameOwner(selection.owner(), owner)) { selection = null; cancelled = true; }
        if (removal != null && sameOwner(removal.owner(), owner)) { removal = null; cancelled = true; }
        return cancelled;
    }

    public synchronized void ownerUnavailable(String owner) {
        if (selection != null && sameOwner(selection.owner(), owner)) selection = null;
        if (removal != null && sameOwner(removal.owner(), owner)) removal = null;
    }

    public synchronized void ownerChangedDimension(String owner, String dimension) {
        if (selection != null && sameOwner(selection.owner(), owner)
                && !selection.dimension().equals(dimension)) {
            selection = null;
        }
    }

    public synchronized PendingRemoval beginRemoval(
            String owner,
            String name,
            long nowMillis) {
        String checkedOwner = required(owner, "owner");
        ProtectedAreaPolicy.Area area = registry.named(name).orElseThrow(() ->
                new IllegalArgumentException("No area named '" + name + "' exists"));
        removal = new PendingRemoval(checkedOwner, area, nowMillis);
        return removal;
    }

    public synchronized ProtectedAreaPolicy.Snapshot confirmRemoval(
            String owner,
            String name,
            long nowMillis) throws IOException {
        PendingRemoval current = removal;
        if (current == null || !sameOwner(current.owner(), owner)
                || nowMillis - current.startedAtMillis() > REMOVAL_TTL_MILLIS
                || !current.area().name().equals(normalize(name))) {
            removal = null;
            throw new IllegalArgumentException(
                    "Removal confirmation is absent, expired, or names another area");
        }
        ProtectedAreaPolicy.Snapshot result = registry.remove(current.area().name());
        removal = null;
        return result;
    }

    public synchronized PendingSelection selection(long nowMillis) {
        expireSelection(nowMillis);
        return selection;
    }

    public synchronized PendingRemoval removal(long nowMillis) {
        if (removal != null && nowMillis - removal.startedAtMillis() > REMOVAL_TTL_MILLIS) {
            removal = null;
        }
        return removal;
    }

    private PendingSelection requireSelection(
            String owner,
            String dimension,
            long nowMillis) {
        expireSelection(nowMillis);
        if (selection == null || !sameOwner(selection.owner(), owner)) {
            throw new IllegalArgumentException("There is no active area selection");
        }
        if (!selection.dimension().equals(dimension)) {
            selection = null;
            throw new IllegalArgumentException(
                    "The selection was cancelled because the owner changed dimension");
        }
        return selection;
    }

    private void expireSelection(long nowMillis) {
        if (selection != null && nowMillis - selection.startedAtMillis() > SELECTION_TTL_MILLIS) {
            selection = null;
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private static ProtectedAreaPolicy.Area areaBetween(
            ProtectedAreaPolicy.AreaKind kind,
            String name,
            String dimension,
            int firstX,
            int firstY,
            int firstZ,
            int secondX,
            int secondY,
            int secondZ) {
        return switch (kind) {
            case PROTECTED -> ProtectedAreaPolicy.Area.protectedBetween(
                    name, dimension, firstX, firstZ, secondX, secondZ);
            case ENTITY_BASE -> ProtectedAreaPolicy.Area.entityBaseBetween(
                    name, dimension, firstX, firstZ, secondX, secondZ);
            case MINING -> ProtectedAreaPolicy.Area.miningBetween(
                    name, dimension,
                    firstX, firstY, firstZ,
                    secondX, secondY, secondZ);
            case HARVESTING -> ProtectedAreaPolicy.Area.harvestingBetween(
                    name, dimension, firstX, firstZ, secondX, secondZ);
        };
    }

    private static boolean sameOwner(String expected, String actual) {
        return expected.equalsIgnoreCase(Objects.requireNonNullElse(actual, ""));
    }

    private static String required(String value, String label) {
        String checked = Objects.requireNonNullElse(value, "").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return checked;
    }

    public record PendingSelection(
            String owner,
            String name,
            ProtectedAreaPolicy.AreaKind kind,
            String dimension,
            int firstX,
            int firstY,
            int firstZ,
            long startedAtMillis,
            ProtectedAreaPolicy.Area preview) {
        public PendingSelection {
            owner = required(owner, "owner");
            name = required(name, "name");
            kind = Objects.requireNonNull(kind, "kind");
            dimension = required(dimension, "dimension");
            if (startedAtMillis < 0L) throw new IllegalArgumentException("time cannot be negative");
        }

        private PendingSelection withPreview(ProtectedAreaPolicy.Area area) {
            return new PendingSelection(
                    owner, name, kind, dimension,
                    firstX, firstY, firstZ, startedAtMillis, area);
        }
    }

    public record PendingRemoval(
            String owner,
            ProtectedAreaPolicy.Area area,
            long startedAtMillis) {
        public PendingRemoval {
            owner = required(owner, "owner");
            area = Objects.requireNonNull(area, "area");
            if (startedAtMillis < 0L) throw new IllegalArgumentException("time cannot be negative");
        }
    }
}
