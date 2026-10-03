package dev.entity.client.autonomy.policy;

import dev.entity.core.port.BaritonePort;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Negative source facts belong to one durable acquisition, never global wilderness policy. */
public final class UnavailableHarvestSources {
    public static final String KEY = "unavailableHarvestSources";
    private UnavailableHarvestSources() { }

    public static boolean canRecover(String sourceKind, BaritonePort.FailureCause cause) {
        return "HARVEST".equals(sourceKind)
                && cause == BaritonePort.FailureCause.LOADED_HARVEST_SOURCE_ABSENT;
    }

    public static Set<String> read(Map<String, String> checkpoint) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String encoded = checkpoint.getOrDefault(KEY, "");
        if (!encoded.isBlank()) for (String item : encoded.split(",")) result.add(normalize(item));
        return Set.copyOf(result);
    }

    /** Returns false for repeat evidence, so an unchanged child cannot recompile forever. */
    public static boolean record(Map<String, String> checkpoint, String item) {
        LinkedHashSet<String> failed = new LinkedHashSet<>(read(checkpoint));
        if (!failed.add(normalize(item))) return false;
        checkpoint.put(KEY, failed.stream().sorted().collect(java.util.stream.Collectors.joining(",")));
        return true;
    }

    private static String normalize(String item) {
        return InventoryReservationLedger.normalizeItem(item);
    }
}
