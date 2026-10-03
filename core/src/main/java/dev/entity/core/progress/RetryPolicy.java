package dev.entity.core.progress;

/** Unbounded retries with capped exponential delay. Missions are never dropped. */
public final class RetryPolicy {
    private final long initialDelayMillis;
    private final long maximumDelayMillis;

    public RetryPolicy() {
        this(500, 60_000);
    }

    public RetryPolicy(long initialDelayMillis, long maximumDelayMillis) {
        if (initialDelayMillis < 0 || maximumDelayMillis < initialDelayMillis) {
            throw new IllegalArgumentException("Invalid retry delays");
        }
        this.initialDelayMillis = initialDelayMillis;
        this.maximumDelayMillis = maximumDelayMillis;
    }

    public long delayForFailure(int failureNumber) {
        if (failureNumber <= 0 || initialDelayMillis == 0) {
            return initialDelayMillis;
        }
        int shift = Math.min(30, failureNumber - 1);
        long multiplied;
        try {
            multiplied = Math.multiplyExact(initialDelayMillis, 1L << shift);
        } catch (ArithmeticException overflow) {
            multiplied = Long.MAX_VALUE;
        }
        return Math.min(maximumDelayMillis, multiplied);
    }
}
