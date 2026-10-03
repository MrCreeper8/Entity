package dev.entity.client.protection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Keeps locally observed evidence independent from connection-scoped authority. */
public final class ThreatEvidenceSources<L> {
    private final Map<String, L> local = new HashMap<>();
    private final AuthoritativeThreatLedger authoritative =
            new AuthoritativeThreatLedger();

    public void putLocal(String key, L evidence) {
        local.put(required(key), Objects.requireNonNull(evidence, "evidence"));
    }

    public List<L> localSnapshot() {
        return List.copyOf(local.values());
    }

    public void removeLocalIf(Predicate<L> predicate) {
        local.values().removeIf(Objects.requireNonNull(predicate, "predicate"));
    }

    public int localSize() {
        return local.size();
    }

    public AuthoritativeThreatLedger authoritative() {
        return authoritative;
    }

    /** Revokes only evidence owned by the replaced authenticated connection. */
    public void resetAuthoritative() {
        authoritative.reset();
    }

    private static String required(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("key is required");
        return normalized;
    }
}
