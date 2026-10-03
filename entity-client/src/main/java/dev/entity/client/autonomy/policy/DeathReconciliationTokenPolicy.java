package dev.entity.client.autonomy.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Durable idempotency key for the plan rebase performed at one death boundary. */
public final class DeathReconciliationTokenPolicy {
    public static final String ROOT_PARAMETER = "deathLifecycleRebaseToken";

    private DeathReconciliationTokenPolicy() {
    }

    public static String token(String missionId, long beganAtMillis) {
        String mission = Objects.requireNonNullElse(missionId, "").trim();
        if (mission.isEmpty() || beganAtMillis < 0L) {
            throw new IllegalArgumentException("death recovery token requires a mission and time");
        }
        return "death-v1:" + mission + ':' + beganAtMillis;
    }

    public static boolean alreadyApplied(
            Map<String, String> rootParameters,
            String recoveryToken) {
        Map<String, String> parameters = rootParameters == null ? Map.of() : rootParameters;
        return normalizedToken(recoveryToken).equals(
                parameters.getOrDefault(ROOT_PARAMETER, ""));
    }

    public static Map<String, String> markApplied(
            Map<String, String> confirmedFacts,
            String recoveryToken) {
        LinkedHashMap<String, String> marked = new LinkedHashMap<>(
                confirmedFacts == null ? Map.of() : confirmedFacts);
        marked.put(ROOT_PARAMETER, normalizedToken(recoveryToken));
        return Map.copyOf(marked);
    }

    private static String normalizedToken(String recoveryToken) {
        String token = Objects.requireNonNullElse(recoveryToken, "").trim();
        if (token.isEmpty() || token.length() > 512) {
            throw new IllegalArgumentException("death recovery token is outside its bounded range");
        }
        return token;
    }
}
