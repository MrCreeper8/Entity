package dev.entity.client.recipe;

/** Connection-scoped, at-most-once retry gate for Paper recipe discovery. */
public final class PaperRecipeDiscoveryRetry {
    private boolean pending;
    private boolean attempted;

    public void reset() {
        pending = false;
        attempted = false;
    }

    /** Records whether the most recently completed catalog unlocked its recipes. */
    public void observe(boolean applied) {
        if (applied) {
            pending = false;
        } else if (!attempted) {
            pending = true;
        }
    }

    /** Claims the connection's sole retry once a real client player/world exists. */
    public boolean claim(boolean playerAndWorldReady) {
        if (!pending || attempted || !playerAndWorldReady) return false;
        pending = false;
        attempted = true;
        return true;
    }

    public boolean pending() {
        return pending;
    }

    public boolean attempted() {
        return attempted;
    }
}
