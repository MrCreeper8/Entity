package dev.entity.client.autonomy.policy;

/** One revocable native target-height setting, scoped only to an admitted automatic mine goal. */
public final class IdleStockMiningLimit {
    private Integer previous;
    public int apply(int current, int maximum) {
        if (previous == null) previous = current;
        return Math.min(previous, maximum);
    }
    public boolean active() { return previous != null; }
    public int restore(int current) {
        if (previous == null) return current;
        int value = previous; previous = null; return value;
    }
}
