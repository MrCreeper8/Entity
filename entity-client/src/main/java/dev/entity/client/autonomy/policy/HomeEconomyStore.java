package dev.entity.client.autonomy.policy;

import java.io.IOException;

/** Persistence boundary for the mission-independent Home & Economy session. */
public interface HomeEconomyStore {
    HomeEconomySession.Snapshot load() throws IOException;

    /** Implementations must save the complete snapshot or throw. */
    void save(HomeEconomySession.Snapshot snapshot) throws IOException;
}
