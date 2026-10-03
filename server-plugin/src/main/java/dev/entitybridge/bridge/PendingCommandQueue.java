package dev.entitybridge.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Transient commands expire; durable mission operations remain until acknowledged. */
public final class PendingCommandQueue {
    private final int capacity;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Map<String, BridgeCommand> commands = new LinkedHashMap<>();

    public PendingCommandQueue(int capacity) {
        this(capacity, Long.MAX_VALUE, System::currentTimeMillis);
    }

    public PendingCommandQueue(int capacity, long ttlMillis) {
        this(capacity, ttlMillis, System::currentTimeMillis);
    }

    PendingCommandQueue(int capacity, long ttlMillis, LongSupplier clock) {
        if (capacity < 1 || ttlMillis < 1) {
            throw new IllegalArgumentException("capacity and ttlMillis must be positive");
        }
        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized boolean offer(BridgeCommand command) {
        Objects.requireNonNull(command, "command");
        pruneExpired();
        if (commands.containsKey(command.id())) {
            return true;
        }
        if ("stop".equals(command.action())) {
            commands.clear();
        }
        if (commands.size() >= capacity) {
            return false;
        }
        commands.put(command.id(), command);
        return true;
    }

    /** Adds an ordered command group only when every frame fits. */
    public synchronized boolean offerAll(List<BridgeCommand> orderedCommands) {
        Objects.requireNonNull(orderedCommands, "orderedCommands");
        pruneExpired();
        LinkedHashMap<String, BridgeCommand> additions = new LinkedHashMap<>();
        for (BridgeCommand command : orderedCommands) {
            Objects.requireNonNull(command, "command");
            if (!commands.containsKey(command.id())) {
                additions.putIfAbsent(command.id(), command);
            }
        }
        if (commands.size() + additions.size() > capacity) {
            return false;
        }
        additions.forEach(commands::put);
        return true;
    }

    public synchronized BridgeCommand complete(String id) {
        pruneExpired();
        return commands.remove(id);
    }

    public synchronized void removeMission(String missionId) {
        commands.values().removeIf(command -> missionId != null && missionId.equals(command.missionId()));
    }

    public synchronized List<BridgeCommand> snapshot() {
        pruneExpired();
        return new ArrayList<>(commands.values());
    }

    public synchronized int size() {
        pruneExpired();
        return commands.size();
    }

    private void pruneExpired() {
        if (ttlMillis == Long.MAX_VALUE) {
            return;
        }
        long now = clock.getAsLong();
        commands.values().removeIf(command -> !command.durable() && now - command.timestamp() >= ttlMillis);
    }
}
