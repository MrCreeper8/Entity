package dev.entitybridge.tracking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure transition model keyed by attacker UUID. Ordinary observations suppress
 * duplicate target events; explicit refreshes repeat the active fact so a new
 * bridge session cannot permanently miss a target acquired before reconnect.
 */
public final class AuthoritativeThreatTargetTracker {
    private final Map<UUID, Binding> active = new LinkedHashMap<>();

    public List<Transition> observe(
            AuthoritativeThreatTargetFrame.AttackerIdentity attacker,
            AuthoritativeThreatTargetFrame.TargetIdentity target
    ) {
        return update(attacker, target, false);
    }

    public List<Transition> refresh(
            AuthoritativeThreatTargetFrame.AttackerIdentity attacker,
            AuthoritativeThreatTargetFrame.TargetIdentity target
    ) {
        return update(attacker, target, true);
    }

    public List<Transition> clear(UUID attackerUuid) {
        Binding previous = active.remove(Objects.requireNonNull(attackerUuid, "attackerUuid"));
        return previous == null
                ? List.of()
                : List.of(new Transition(false, previous.target(), previous.attacker()));
    }

    public List<UUID> trackedAttackers() {
        return List.copyOf(active.keySet());
    }

    private List<Transition> update(
            AuthoritativeThreatTargetFrame.AttackerIdentity attacker,
            AuthoritativeThreatTargetFrame.TargetIdentity target,
            boolean emitUnchanged
    ) {
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(target, "target");
        UUID attackerUuid = attacker.uuid();
        Binding previous = active.get(attackerUuid);
        Binding replacement = new Binding(target, attacker);
        active.put(attackerUuid, replacement);

        if (previous == null) {
            return List.of(new Transition(true, target, attacker));
        }
        if (sameTarget(previous.target(), target)) {
            return emitUnchanged
                    ? List.of(new Transition(true, target, attacker))
                    : List.of();
        }
        return List.of(
                new Transition(false, previous.target(), previous.attacker()),
                new Transition(true, target, attacker)
        );
    }

    private static boolean sameTarget(
            AuthoritativeThreatTargetFrame.TargetIdentity first,
            AuthoritativeThreatTargetFrame.TargetIdentity second
    ) {
        return first.target() == second.target() && first.uuid().equals(second.uuid());
    }

    private record Binding(
            AuthoritativeThreatTargetFrame.TargetIdentity target,
            AuthoritativeThreatTargetFrame.AttackerIdentity attacker
    ) {
    }

    public record Transition(
            boolean active,
            AuthoritativeThreatTargetFrame.TargetIdentity target,
            AuthoritativeThreatTargetFrame.AttackerIdentity attacker
    ) {
        public Transition {
            target = Objects.requireNonNull(target, "target");
            attacker = Objects.requireNonNull(attacker, "attacker");
        }
    }
}
