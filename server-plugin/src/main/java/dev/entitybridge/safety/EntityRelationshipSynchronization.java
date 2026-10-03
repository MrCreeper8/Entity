package dev.entitybridge.safety;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.function.Predicate;

/** Publishes the current relationship policy on connection and every access mutation. */
public final class EntityRelationshipSynchronization {
    private final TrustedIdentityRegistry registry;
    private final Predicate<JsonObject> sender;
    private boolean connected;

    public EntityRelationshipSynchronization(
            TrustedIdentityRegistry registry,
            Predicate<JsonObject> sender) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public synchronized boolean connectionChanged(boolean connected) {
        this.connected = connected;
        return connected && publishCurrent();
    }

    public synchronized boolean policyChanged() {
        return connected && publishCurrent();
    }

    private boolean publishCurrent() {
        return sender.test(EntityRelationshipProtocol.policyFrame(registry.snapshot()));
    }
}
