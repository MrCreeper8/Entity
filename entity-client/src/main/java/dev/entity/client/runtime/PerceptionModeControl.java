package dev.entity.client.runtime;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Command boundary only: it never starts, cancels, or defers Minecraft work. */
public final class PerceptionModeControl {
    private final PerceptionSettingsStore store;
    private final Consumer<Boolean> apply;

    public PerceptionModeControl(PerceptionSettingsStore store, Consumer<Boolean> apply) {
        this.store = Objects.requireNonNull(store, "store");
        this.apply = Objects.requireNonNull(apply, "apply");
        apply.accept(legitimate());
    }

    public boolean legitimate() { return store.settings().mode() == PerceptionSettingsStore.Mode.LEGITIMATE; }
    public String mode() { return store.settings().mode().name(); }
    public String status() { return "Perception: " + mode() + "."; }

    public String command(JsonObject args, boolean worldVerified, BooleanSupplier busy) throws IOException {
        String operation = text(args, "operation");
        if (operation.equals("status")) return status();
        if (!operation.equals("set")) throw new IllegalArgumentException("Perception operation must be set or status");
        final PerceptionSettingsStore.Mode next;
        try { next = PerceptionSettingsStore.Mode.valueOf(text(args, "mode")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Perception mode must be NORMAL or LEGITIMATE"); }
        // Retransmission/query is harmless even while work is running.
        if (next == store.settings().mode()) return status();
        if (!worldVerified || !store.bound()) throw new IllegalArgumentException("Perception is waiting for an authenticated world binding");
        if (busy.getAsBoolean()) throw new IllegalArgumentException("Perception change requires /e stop first.");
        if (store.save(next)) apply.accept(legitimate());
        return status();
    }

    private static String text(JsonObject args, String field) {
        var value = args.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Perception requires " + field);
        return value.getAsString();
    }
}
