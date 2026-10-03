package dev.entity.client.baritone;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Retains one exact interaction target until observation proves it is gone. */
public final class StableInteractionTargetSession<T> {
    private final Comparator<? super T> order;
    private final Set<T> observedRemoved = new HashSet<>();
    private T current;

    public StableInteractionTargetSession(Comparator<? super T> order) {
        this.order = Objects.requireNonNull(order, "order");
    }

    public Optional<T> select(Collection<T> remaining) {
        Objects.requireNonNull(remaining, "remaining");
        if (current != null && !wasRemoved(current) && remaining.contains(current)) {
            return Optional.of(current);
        }
        current = remaining.stream().filter(target -> !wasRemoved(target)).min(order).orElse(null);
        return Optional.ofNullable(current);
    }

    public Optional<T> current() {
        return Optional.ofNullable(current);
    }

    /** Only an observed removal grants completion; later support blocks cannot undo it. */
    public void observedRemoved(T target) {
        observedRemoved.add(Objects.requireNonNull(target, "target"));
    }

    public boolean wasRemoved(T target) {
        return observedRemoved.contains(target);
    }

    public void clear() {
        current = null;
        observedRemoved.clear();
    }
}
