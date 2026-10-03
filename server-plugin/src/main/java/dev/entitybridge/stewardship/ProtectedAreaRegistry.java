package dev.entitybridge.stewardship;

import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Transactional in-memory view of Paper's durable protected-area policy. */
public final class ProtectedAreaRegistry {
    private final ProtectedAreaStore store;
    private ProtectedAreaPolicy.Snapshot snapshot;
    private boolean failClosed;
    private String warning;

    public ProtectedAreaRegistry(ProtectedAreaStore store) {
        this.store = Objects.requireNonNull(store, "store");
        ProtectedAreaStore.LoadResult loaded = store.load();
        this.snapshot = loaded.snapshot();
        this.failClosed = loaded.failClosed();
        this.warning = loaded.warning();
    }

    public synchronized ProtectedAreaPolicy.Snapshot snapshot() {
        return snapshot;
    }

    public synchronized boolean failClosed() {
        return failClosed;
    }

    public synchronized String warning() {
        return warning;
    }

    public synchronized Optional<ProtectedAreaPolicy.Area> named(String name) {
        return snapshot.named(name);
    }

    public synchronized ProtectedAreaPolicy.Snapshot add(
            ProtectedAreaPolicy.Area area) throws IOException {
        requireTrustedStore();
        Objects.requireNonNull(area, "area");
        if (snapshot.named(area.name()).isPresent()) {
            throw new IllegalArgumentException("Area '" + area.name() + "' already exists");
        }
        ArrayList<ProtectedAreaPolicy.Area> nextAreas = new ArrayList<>(snapshot.areas());
        nextAreas.add(area);
        return commit(nextAreas);
    }

    public synchronized ProtectedAreaPolicy.Snapshot remove(String name) throws IOException {
        requireTrustedStore();
        ProtectedAreaPolicy.Area existing = snapshot.named(name).orElseThrow(() ->
                new IllegalArgumentException("No area named '" + name + "' exists"));
        List<ProtectedAreaPolicy.Area> nextAreas = snapshot.areas().stream()
                .filter(area -> !area.name().equals(existing.name()))
                .toList();
        return commit(nextAreas);
    }

    private ProtectedAreaPolicy.Snapshot commit(
            List<ProtectedAreaPolicy.Area> areas) throws IOException {
        ProtectedAreaPolicy.Snapshot next = ProtectedAreaPolicy.create(
                Math.addExact(snapshot.revision(), 1L), areas);
        store.save(next);
        snapshot = next;
        failClosed = false;
        warning = "";
        return snapshot;
    }

    private void requireTrustedStore() throws IOException {
        if (failClosed) {
            throw new IOException(
                    "protected-area policy is unreadable; restore its last valid file before editing");
        }
    }
}
