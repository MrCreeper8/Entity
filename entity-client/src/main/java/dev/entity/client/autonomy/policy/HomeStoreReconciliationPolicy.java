package dev.entity.client.autonomy.policy;

import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * Sole automatic reconciliation owner for the settings anchor and economy
 * cursor store. Trusted settings (including a genuinely absent first-run file)
 * are the source of truth. An existing corrupt or unsupported settings file is
 * not absence: reconciliation is quarantined without issuing a cursor write.
 */
public final class HomeStoreReconciliationPolicy {
    public enum SettingsAuthority {
        TRUSTED,
        QUARANTINED
    }

    public enum Action {
        NONE,
        QUARANTINE_SETTINGS,
        ENABLE_CURSOR,
        DISABLE_CURSOR,
        BIND_SETTINGS_HOME,
        REGISTER_SETTINGS_HOME,
        CLEAR_CURSOR,
        HOLD_PENDING_RECONCILIATION
    }

    private HomeStoreReconciliationPolicy() {
    }

    public static Action decide(
            SettingsAuthority authority,
            boolean settingsStockEnabled,
            HomeEconomySession.HomeAnchor settingsHome,
            HomeEconomySession.Snapshot cursor) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(cursor, "cursor");
        if (authority == SettingsAuthority.QUARANTINED) {
            return Action.QUARANTINE_SETTINGS;
        }
        boolean pending = cursor.pendingTransfer() != null
                || cursor.pendingAcquisition() != null;
        if (settingsHome == null) {
            if (pending) return Action.HOLD_PENDING_RECONCILIATION;
            if (!settingsStockEnabled && cursor.enabled()) return Action.DISABLE_CURSOR;
            if (cursor.home() != null) return Action.CLEAR_CURSOR;
            if (settingsStockEnabled && !cursor.enabled()) return Action.ENABLE_CURSOR;
            return Action.NONE;
        }
        if (cursor.home() == null) {
            if (!settingsStockEnabled) return Action.REGISTER_SETTINGS_HOME;
            return cursor.enabled() ? Action.BIND_SETTINGS_HOME : Action.ENABLE_CURSOR;
        }
        if (!cursor.home().fingerprint().equals(settingsHome.fingerprint())) {
            return pending ? Action.HOLD_PENDING_RECONCILIATION : Action.CLEAR_CURSOR;
        }
        if (settingsStockEnabled && !cursor.enabled()) return Action.ENABLE_CURSOR;
        if (!settingsStockEnabled && cursor.enabled()) {
            return pending
                    ? Action.HOLD_PENDING_RECONCILIATION
                    : Action.DISABLE_CURSOR;
        }
        return Action.NONE;
    }

    /**
     * Applies all crash-order transitions through this one durable boundary.
     * A quarantined result performs zero session mutations.
     */
    public static Result reconcile(
            SettingsAuthority authority,
            boolean settingsStockEnabled,
            HomeEconomySession.HomeAnchor settingsHome,
            HomeEconomySession session,
            long nowMillis,
            LongConsumer clearedGenerationConsumer) throws java.io.IOException {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(clearedGenerationConsumer, "clearedGenerationConsumer");
        String detail = "";
        long clearedGeneration = -1L;
        for (int transition = 0; transition < 5; transition++) {
            Action action = decide(
                    authority, settingsStockEnabled, settingsHome, session.snapshot());
            switch (action) {
                case NONE -> {
                    return new Result(false, detail, clearedGeneration);
                }
                case QUARANTINE_SETTINGS -> {
                    return new Result(true, "", -1L);
                }
                case ENABLE_CURSOR -> {
                    session.enable(nowMillis);
                    detail = "enabled Home economy cursor from settings truth";
                }
                case DISABLE_CURSOR -> {
                    session.disable(nowMillis);
                    detail = "disabled Home economy cursor from settings truth";
                }
                case BIND_SETTINGS_HOME -> {
                    session.establishHome(settingsHome, nowMillis);
                    detail = "bound Home economy cursor to settings anchor";
                }
                case REGISTER_SETTINGS_HOME -> {
                    session.ownerRegisterHome(settingsHome, nowMillis);
                    detail = "registered Home anchor with Stock off; no provisioning requested";
                }
                case CLEAR_CURSOR -> {
                    clearedGeneration = session.snapshot().generation();
                    session.clearHome(nowMillis);
                    clearedGenerationConsumer.accept(clearedGeneration);
                    detail = "cleared stale Home economy cursor from settings truth";
                }
                case HOLD_PENDING_RECONCILIATION -> {
                    return new Result(
                            false,
                            "settings changed, but the prior Home cursor is retained until its "
                                    + "durable transfer/acquisition reconciles",
                            -1L);
                }
            }
        }
        throw new java.io.IOException("Home settings/cursor reconciliation did not converge");
    }

    public record Result(boolean quarantined, String detail, long clearedGeneration) {
        public Result {
            detail = Objects.requireNonNullElse(detail, "");
            if (quarantined && clearedGeneration >= 0L) {
                throw new IllegalArgumentException(
                        "quarantined reconciliation cannot clear a Home generation");
            }
        }
    }
}
