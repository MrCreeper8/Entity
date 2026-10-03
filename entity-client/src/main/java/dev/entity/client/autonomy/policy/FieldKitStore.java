package dev.entity.client.autonomy.policy;

import java.io.IOException;

/** Persistence boundary for Entity-owned assets that outlive mission leaves. */
public interface FieldKitStore {
    FieldKitLedger.Snapshot load() throws IOException;

    /** Implementations must either save the complete snapshot or throw. */
    void save(FieldKitLedger.Snapshot snapshot) throws IOException;
}
