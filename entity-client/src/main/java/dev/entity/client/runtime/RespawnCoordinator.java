package dev.entity.client.runtime;

/** Small clock-driven state machine so death handling cannot spam respawn packets. */
public final class RespawnCoordinator {
    public static final long INITIAL_DELAY_MILLIS = 1_000;
    public static final long RETRY_INTERVAL_MILLIS = 3_000;

    private boolean active;
    private long detectedAtMillis;
    private long lastRequestAtMillis = -1;

    public Decision observe(boolean playerPresent, boolean dead, long nowMillis) {
        if (dead && playerPresent) {
            boolean entered = !active;
            if (entered) {
                active = true;
                detectedAtMillis = nowMillis;
                lastRequestAtMillis = -1;
            }
            boolean request = nowMillis - detectedAtMillis >= INITIAL_DELAY_MILLIS
                    && (lastRequestAtMillis < 0
                    || nowMillis - lastRequestAtMillis >= RETRY_INTERVAL_MILLIS);
            if (request) lastRequestAtMillis = nowMillis;
            return new Decision(entered, false, request, true);
        }
        if (active && !playerPresent) {
            return new Decision(false, false, false, true);
        }
        if (active) {
            active = false;
            detectedAtMillis = 0;
            lastRequestAtMillis = -1;
            return new Decision(false, true, false, false);
        }
        return new Decision(false, false, false, false);
    }

    public boolean active() {
        return active;
    }

    public record Decision(boolean enteredDeath, boolean respawned, boolean requestRespawn, boolean waiting) {
    }
}
