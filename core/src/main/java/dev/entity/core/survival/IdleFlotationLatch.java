package dev.entity.core.survival;

/**
 * Keeps idle breathing ownership stable across vanilla water-edge bobbing.
 *
 * <p>Feet touching shallow water is not a reason to seize the whole body. Ownership begins only
 * after the head is actually submerged, then survives the brief waterline bob until breathing is
 * stable. A real mission boundary clears it immediately.</p>
 */
public final class IdleFlotationLatch {
    public static final long DEFAULT_DRY_HOLD_MILLIS = 1_500L;

    private final long dryHoldMillis;
    private boolean active;
    private long drySinceMillis = -1L;

    public IdleFlotationLatch() {
        this(DEFAULT_DRY_HOLD_MILLIS);
    }

    public IdleFlotationLatch(long dryHoldMillis) {
        if (dryHoldMillis < 0L) throw new IllegalArgumentException("dry hold cannot be negative");
        this.dryHoldMillis = dryHoldMillis;
    }

    public boolean update(
            boolean missionActive,
            boolean inWater,
            boolean headSubmerged,
            long nowMillis) {
        long now = Math.max(0L, nowMillis);
        if (missionActive) {
            reset();
            return false;
        }
        if (headSubmerged) {
            active = true;
            drySinceMillis = -1L;
            return true;
        }
        if (!active) return false;
        if (drySinceMillis < 0L || now < drySinceMillis) drySinceMillis = now;
        if (now - drySinceMillis >= dryHoldMillis) reset();
        return active;
    }

    public void reset() {
        active = false;
        drySinceMillis = -1L;
    }

    public boolean active() {
        return active;
    }
}
